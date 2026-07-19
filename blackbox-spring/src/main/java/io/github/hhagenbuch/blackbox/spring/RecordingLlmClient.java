package io.github.hhagenbuch.blackbox.spring;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.ToolCall;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;

/**
 * Records each model call as {@code llm_request} / {@code llm_response} events
 * without the starter knowing. Attribution comes from the {@link RecordingSession}
 * in the Reactor Context; if no session is present (a call outside a recorded
 * request) it simply delegates.
 */
public final class RecordingLlmClient implements LlmClient {

    private final LlmClient delegate;

    public RecordingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
        return Mono.deferContextual(ctx -> {
            if (!ctx.hasKey(RecordingSession.CONTEXT_KEY)) {
                return delegate.chat(messages, tools);
            }
            RecordingSession session = ctx.get(RecordingSession.CONTEXT_KEY);
            int seq = session.nextSeq();
            long start = System.nanoTime();
            session.write(request(seq, messages, tools));
            return delegate.chat(messages, tools)
                    .doOnNext(response -> session.write(response(seq, response, millisSince(start))))
                    .doOnError(error -> session.write(
                            TraceEvent.error(1, "llm", String.valueOf(error.getMessage()))));
        });
    }

    private TraceEvent request(int seq, List<ObjectNode> messages, Collection<AgentTool> tools) {
        ArrayNode offered = TraceEvent.mapper().createArrayNode();
        tools.forEach(t -> offered.add(t.name()));
        return TraceEvent.ofType("llm_request")
                .with("turn", 1)
                .with("seq", seq)
                .with("messagesDigest", digest(messages))
                .with("toolsOffered", offered);
    }

    private TraceEvent response(int seq, LlmResponse response, long millis) {
        ArrayNode toolCalls = TraceEvent.mapper().createArrayNode();
        for (ToolCall call : response.toolCalls()) {
            ObjectNode c = toolCalls.addObject();
            c.put("id", call.id());
            c.put("name", call.name());
            c.set("input", call.input());
        }
        TraceEvent event = TraceEvent.ofType("llm_response")
                .with("turn", 1)
                .with("seq", seq)
                .with("stopReason", response.stopReason())
                .with("text", response.text())
                .with("toolCalls", toolCalls)
                .with("millis", (int) millis);
        // The provider's raw content array, replayed verbatim as the assistant message so
        // loop-replay rebuilds byte-identical context (otherwise request digests falsely diverge).
        if (response.rawContent() != null) {
            event.with("rawContent", response.rawContent());
        }
        return event;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** sha256 of the serialized messages — small and privacy-sane, still catches divergence. */
    private static String digest(List<ObjectNode> messages) {
        try {
            byte[] bytes = TraceEvent.mapper().writeValueAsBytes(messages);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return "sha256:unavailable";
        }
    }
}
