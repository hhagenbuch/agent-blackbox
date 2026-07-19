package io.github.hhagenbuch.blackbox.diff;

import io.github.hhagenbuch.blackbox.core.TraceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compares two traces of the same conversation and reports what changed:
 * the tool-call sequence, the final answer, and the number of model calls.
 * The canonical use is "prompt v1 vs v2 on the same input" (e.g. a canary trace
 * vs a main trace).
 */
public final class TraceDiff {

    /** One compared aspect. */
    public record Aspect(String name, String left, String right) {
        public boolean changed() {
            return !left.equals(right);
        }
    }

    public record Result(List<Aspect> aspects) {
        public boolean identical() {
            return aspects.stream().noneMatch(Aspect::changed);
        }

        public String render() {
            if (identical()) {
                return "diff: identical trajectory";
            }
            StringBuilder out = new StringBuilder("diff: changed\n");
            for (Aspect a : aspects) {
                if (a.changed()) {
                    out.append("  Δ ").append(a.name()).append('\n')
                            .append("      a: ").append(a.left()).append('\n')
                            .append("      b: ").append(a.right()).append('\n');
                }
            }
            return out.toString().stripTrailing();
        }
    }

    private TraceDiff() {
    }

    public static Result diff(List<TraceEvent> a, List<TraceEvent> b) {
        List<Aspect> aspects = new ArrayList<>();
        aspects.add(new Aspect("tool calls", toolSequence(a), toolSequence(b)));
        aspects.add(new Aspect("final answer", finalAnswer(a), finalAnswer(b)));
        aspects.add(new Aspect("model calls", String.valueOf(modelCalls(a)), String.valueOf(modelCalls(b))));
        return new Result(aspects);
    }

    private static String toolSequence(List<TraceEvent> events) {
        String seq = events.stream()
                .filter(e -> e.type().equals("tool_call"))
                .map(e -> e.get("name").asText())
                .collect(Collectors.joining(", "));
        return seq.isEmpty() ? "(none)" : seq;
    }

    private static String finalAnswer(List<TraceEvent> events) {
        String answer = "(none)";
        for (TraceEvent e : events) {
            if (e.type().equals("assistant_message")) {
                answer = e.text();
            } else if (e.type().equals("llm_response")) {
                String text = e.node().path("text").asText("");
                if (!text.isEmpty()) {
                    answer = text;
                }
            }
        }
        return answer;
    }

    private static long modelCalls(List<TraceEvent> events) {
        return events.stream().filter(e -> e.type().equals("llm_response")).count();
    }
}
