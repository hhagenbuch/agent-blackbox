package io.github.hhagenbuch.blackbox.eval;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;
import io.github.hhagenbuch.blackbox.core.TraceEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one recorded turn into an <a href="https://github.com/hhagenbuch/agent-evals">agent-evals</a>
 * case: the user's message as the prompt, a {@code tool_called} assertion for
 * each tool the trace shows was invoked, and a {@code judge} stub templated from
 * the recorded answer.
 *
 * <p>The output is deliberately a <em>draft</em>. Auto-generated oracles nobody
 * reads are how eval suites rot, so the judge criteria carry a REVIEW note and
 * the whole case is meant to be confirmed by a human before it is committed.
 */
public final class EvalExporter {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .build();

    private EvalExporter() {
    }

    public static String exportYaml(List<TraceEvent> events, int turn, String datasetName) {
        ObjectNode root = TraceEvent.mapper().createObjectNode();
        root.put("name", datasetName);
        root.put("target", "http://localhost:8080/api/chat");
        ArrayNode cases = root.putArray("cases");

        ObjectNode testCase = cases.addObject();
        testCase.put("id", "turn-" + turn);
        testCase.put("prompt", promptFor(events, turn));

        ArrayNode assertions = testCase.putArray("assert");
        for (String tool : toolsUsedIn(events, turn)) {
            ObjectNode a = assertions.addObject();
            a.put("type", "tool_called");
            a.put("value", tool);
        }
        ObjectNode judge = assertions.addObject();
        judge.put("type", "judge");
        judge.put("criteria", "Answer is consistent with the recorded response: \""
                + answerFor(events, turn) + "\". REVIEW: confirm the criteria and set min_score.");
        judge.put("min_score", 4);

        try {
            return YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to render eval YAML", e);
        }
    }

    private static String promptFor(List<TraceEvent> events, int turn) {
        return events.stream()
                .filter(e -> e.type().equals("user_message") && e.turn() == turn)
                .map(TraceEvent::text)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no user_message recorded for turn " + turn + " — record with the prompt captured"));
    }

    private static Set<String> toolsUsedIn(List<TraceEvent> events, int turn) {
        Set<String> tools = new LinkedHashSet<>();
        for (TraceEvent e : events) {
            if (e.type().equals("tool_call") && e.turn() == turn) {
                tools.add(e.get("name").asText());
            }
        }
        return tools;
    }

    private static String answerFor(List<TraceEvent> events, int turn) {
        String answer = "";
        for (TraceEvent e : events) {
            if (e.turn() != turn) {
                continue;
            }
            if (e.type().equals("assistant_message")) {
                return e.text();
            }
            if (e.type().equals("llm_response")) {
                String text = e.node().path("text").asText("");
                if (!text.isEmpty()) {
                    answer = text; // keep the last non-empty model text as the answer
                }
            }
        }
        return answer;
    }
}
