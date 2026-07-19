package io.github.hhagenbuch.blackbox.replay;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link LlmClient} that replays a trace's recorded {@code llm_response} events
 * in order — no API key, no network, no cost. It is also judgmental: on each call
 * it compares the digest of the messages the current code sends to the recorded
 * {@code llm_request} digest, so a code change that assembles a different request
 * surfaces as a {@code request.digest} divergence.
 */
public final class ReplayLlmClient implements LlmClient {

    private final List<LlmResponse> responses;
    private final List<String> recordedDigests;
    private final AtomicInteger index = new AtomicInteger();
    private final List<Divergence> divergences = new ArrayList<>();

    private ReplayLlmClient(List<LlmResponse> responses, List<String> recordedDigests) {
        this.responses = responses;
        this.recordedDigests = recordedDigests;
    }

    public static ReplayLlmClient fromTrace(List<TraceEvent> events) {
        List<LlmResponse> responses = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        for (TraceEvent event : events) {
            switch (event.type()) {
                case "llm_request" -> digests.add(event.get("messagesDigest").asText());
                case "llm_response" -> responses.add(toResponse(event));
                default -> { /* ignore */ }
            }
        }
        return new ReplayLlmClient(responses, digests);
    }

    @Override
    public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
        int i = index.getAndIncrement();
        if (i < recordedDigests.size()) {
            String actual = digest(messages);
            if (!recordedDigests.get(i).equals(actual)) {
                divergences.add(new Divergence("request.digest", "seq " + (i + 1),
                        recordedDigests.get(i), actual));
            }
        }
        if (i >= responses.size()) {
            return Mono.error(new IllegalStateException("replay exhausted at call " + (i + 1)));
        }
        return Mono.just(responses.get(i));
    }

    public List<Divergence> divergences() {
        return List.copyOf(divergences);
    }

    /** How many model calls the current code actually made. */
    public int callsMade() {
        return index.get();
    }

    /** How many the trace recorded. Differ → the agent looped a different number of times. */
    public int recordedResponseCount() {
        return responses.size();
    }

    private static LlmResponse toResponse(TraceEvent event) {
        String text = event.node().path("text").asText("");
        String stopReason = event.node().path("stopReason").asText("end_turn");
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : event.node().path("toolCalls")) {
            toolCalls.add(new ToolCall(call.path("id").asText(), call.path("name").asText(), call.path("input")));
        }
        // Prefer the recorded raw content (byte-identical replay); reconstruct only for
        // older traces that predate rawContent capture.
        JsonNode rawContent = event.node().has("rawContent")
                ? event.node().get("rawContent")
                : reconstructContent(text, toolCalls);
        return new LlmResponse(text, toolCalls, rawContent, stopReason);
    }

    private static ArrayNode reconstructContent(String text, List<ToolCall> toolCalls) {
        ArrayNode content = TraceEvent.mapper().createArrayNode();
        if (!text.isEmpty()) {
            content.addObject().put("type", "text").put("text", text);
        }
        for (ToolCall call : toolCalls) {
            content.addObject().put("type", "tool_use").put("id", call.id()).put("name", call.name())
                    .set("input", call.input());
        }
        return content;
    }

    private static String digest(List<ObjectNode> messages) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(TraceEvent.mapper().writeValueAsBytes(messages));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }
}
