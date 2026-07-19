package io.github.hhagenbuch.blackbox.replay;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import io.github.hhagenbuch.blackbox.core.TraceEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays a trace's tool calls against the <em>current</em> tools and reports
 * divergence — the safe, judgmental half of replay.
 *
 * <p>For each recorded {@code tool_call} it runs the current tool with the
 * recorded input and compares the result to the recorded {@code tool_result}. A
 * mismatch (a tool whose behavior changed, or one that was renamed/removed) is a
 * divergence. Tools named in {@code stubbed} are <strong>never executed</strong>
 * — their recorded result stands — so replaying a session that sent an email
 * cannot send it again. Safety is the default posture, not an afterthought.
 */
public final class Replayer {

    private final ToolRegistry tools;
    private final Set<String> stubbed;

    public Replayer(ToolRegistry tools, Set<String> stubbed) {
        this.tools = tools;
        this.stubbed = Set.copyOf(stubbed);
    }

    public DivergenceReport replay(List<TraceEvent> events) {
        Map<String, TraceEvent> resultByToolUseId = new HashMap<>();
        for (TraceEvent event : events) {
            if (event.type().equals("tool_result")) {
                resultByToolUseId.put(event.get("toolUseId").asText(), event);
            }
        }

        List<Divergence> divergences = new ArrayList<>();
        for (TraceEvent event : events) {
            if (!event.type().equals("tool_call")) {
                continue;
            }
            String name = event.get("name").asText();
            String toolUseId = event.get("toolUseId").asText();
            JsonNode input = event.get("input");
            TraceEvent recorded = resultByToolUseId.get(toolUseId);
            String recordedResult = recorded != null ? recorded.get("result").asText() : null;

            if (stubbed.contains(name)) {
                // Safety: do not execute — the recorded result is authoritative.
                continue;
            }

            String actual = execute(name, input);
            if (recordedResult != null && !recordedResult.equals(actual)) {
                divergences.add(new Divergence("tool.result", name + " (" + toolUseId + ")",
                        recordedResult, actual));
            }
        }
        return new DivergenceReport(divergences);
    }

    private String execute(String name, JsonNode input) {
        try {
            // ToolRegistry already converts unknown tools and failures into "ERROR: …" strings,
            // so a renamed/removed tool surfaces as a divergence rather than an exception.
            return tools.execute(name, input).block();
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
