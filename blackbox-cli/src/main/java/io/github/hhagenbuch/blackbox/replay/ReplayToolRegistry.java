package io.github.hhagenbuch.blackbox.replay;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The tool side of loop-replay. <strong>Safe by default: a tool is never
 * executed</strong> — its recorded result is authoritative, so replaying a
 * session that sent an email cannot send it again. A tool named in
 * {@code execute} is re-run and its result compared to the recording (behavioral
 * verification), keeping stub and verify as distinct, opt-in actions.
 *
 * <p>It also records divergence when the agent calls a tool the trace didn't
 * (or with a different input), i.e. the current code took a different action.
 */
public final class ReplayToolRegistry extends ToolRegistry {

    private final Set<String> execute;
    private final Map<String, Deque<JsonNode>> recordedInputs = new HashMap<>();
    private final Map<String, Deque<String>> recordedResults = new HashMap<>();
    private final List<Divergence> divergences = new ArrayList<>();

    public ReplayToolRegistry(List<AgentTool> tools, List<TraceEvent> events, Set<String> execute) {
        super(tools);
        this.execute = Set.copyOf(execute);
        index(events);
    }

    private void index(List<TraceEvent> events) {
        Map<String, String> resultByToolUseId = new HashMap<>();
        for (TraceEvent e : events) {
            if (e.type().equals("tool_result")) {
                resultByToolUseId.put(e.get("toolUseId").asText(), e.get("result").asText());
            }
        }
        for (TraceEvent e : events) {
            if (e.type().equals("tool_call")) {
                String name = e.get("name").asText();
                recordedInputs.computeIfAbsent(name, k -> new ArrayDeque<>()).add(e.get("input"));
                recordedResults.computeIfAbsent(name, k -> new ArrayDeque<>())
                        .add(resultByToolUseId.get(e.get("toolUseId").asText()));
            }
        }
    }

    @Override
    public Mono<String> execute(String name, JsonNode input) {
        Deque<JsonNode> inputs = recordedInputs.get(name);
        if (inputs == null || inputs.isEmpty()) {
            divergences.add(new Divergence("tool.call", name, "(not called)", "called with " + input));
            // No recording to stand in for; run it only if explicitly allowed, else no-op.
            return execute.contains(name) ? super.execute(name, input) : Mono.just("");
        }
        JsonNode recordedInput = inputs.poll();
        String recordedResult = recordedResults.get(name).poll();
        if (!Objects.equals(recordedInput, input)) {
            divergences.add(new Divergence("tool.input", name,
                    String.valueOf(recordedInput), String.valueOf(input)));
        }

        if (execute.contains(name)) {
            String actual = super.execute(name, input).block();
            if (recordedResult != null && !recordedResult.equals(actual)) {
                divergences.add(new Divergence("tool.result", name, recordedResult, actual));
            }
            return Mono.just(actual);
        }
        // Safe default: return the recorded result, do not execute.
        return Mono.just(recordedResult != null ? recordedResult : "");
    }

    public List<Divergence> divergences() {
        return List.copyOf(divergences);
    }
}
