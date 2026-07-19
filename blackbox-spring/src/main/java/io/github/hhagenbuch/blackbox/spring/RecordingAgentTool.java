package io.github.hhagenbuch.blackbox.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Records each tool invocation as {@code tool_call} / {@code tool_result} events.
 * Decorates the {@code AgentTool} seam (not the concrete {@code ToolRegistry}),
 * so it captures the real execution — including exceptions — behind a clean
 * interface. A per-invocation {@code toolUseId} pairs the call and its result.
 */
public final class RecordingAgentTool implements AgentTool {

    private final AgentTool delegate;

    public RecordingAgentTool(AgentTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        return delegate.inputSchema(mapper);
    }

    @Override
    public Mono<String> execute(JsonNode input) {
        return Mono.deferContextual(ctx -> {
            if (!ctx.hasKey(RecordingSession.CONTEXT_KEY)) {
                return delegate.execute(input);
            }
            RecordingSession session = ctx.get(RecordingSession.CONTEXT_KEY);
            String toolUseId = UUID.randomUUID().toString();
            long start = System.nanoTime();
            session.write(TraceEvent.ofType("tool_call")
                    .with("turn", 1)
                    .with("toolUseId", toolUseId)
                    .with("name", delegate.name())
                    .with("input", input == null ? TraceEvent.mapper().nullNode() : input));
            return delegate.execute(input)
                    .doOnNext(result -> session.write(result(toolUseId, result, millisSince(start), false)))
                    .doOnError(error -> session.write(
                            result(toolUseId, String.valueOf(error.getMessage()), millisSince(start), true)));
        });
    }

    private TraceEvent result(String toolUseId, String result, long millis, boolean error) {
        return TraceEvent.ofType("tool_result")
                .with("turn", 1)
                .with("toolUseId", toolUseId)
                .with("result", result)
                .with("millis", (int) millis)
                .with("error", TraceEvent.mapper().getNodeFactory().booleanNode(error));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
