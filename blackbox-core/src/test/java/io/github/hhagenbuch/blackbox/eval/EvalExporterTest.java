package io.github.hhagenbuch.blackbox.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalExporterTest {

    private final YAMLMapper yaml = new YAMLMapper();

    private List<TraceEvent> trace() {
        return List.of(
                TraceEvent.sessionStart("0.1", "s1", "t", "app", "m"),
                TraceEvent.userMessage(1, "What is 973 * 481?"),
                TraceEvent.ofType("tool_call").with("turn", 1).with("toolUseId", "t1")
                        .with("name", "calculator")
                        .with("input", TraceEvent.mapper().createObjectNode().put("expression", "973 * 481")),
                TraceEvent.ofType("tool_result").with("turn", 1).with("toolUseId", "t1").with("result", "468013"),
                TraceEvent.ofType("llm_response").with("turn", 1).with("seq", 2)
                        .with("stopReason", "end_turn").with("text", "973 × 481 = 468013."),
                TraceEvent.sessionEnd("t"));
    }

    @Test
    void exportsAValidAgentEvalsCaseFromTheTrajectory() throws Exception {
        String out = EvalExporter.exportYaml(trace(), 1, "incident-1234");
        JsonNode dataset = yaml.readTree(out);

        assertThat(dataset.path("name").asText()).isEqualTo("incident-1234");
        assertThat(dataset.path("target").asText()).isEqualTo("http://localhost:8080/api/chat");

        JsonNode testCase = dataset.path("cases").path(0);
        assertThat(testCase.path("id").asText()).isEqualTo("turn-1");
        assertThat(testCase.path("prompt").asText()).isEqualTo("What is 973 * 481?"); // the real prompt

        JsonNode assertions = testCase.path("assert");
        // a tool_called assertion built from the recorded trajectory
        assertThat(assertions).anySatisfy(a -> {
            assertThat(a.path("type").asText()).isEqualTo("tool_called");
            assertThat(a.path("value").asText()).isEqualTo("calculator");
        });
        // a judge stub templated from the recorded answer, left for a human to confirm
        assertThat(assertions).anySatisfy(a -> {
            assertThat(a.path("type").asText()).isEqualTo("judge");
            assertThat(a.path("criteria").asText()).contains("468013").contains("REVIEW");
            assertThat(a.path("min_score").asInt()).isEqualTo(4);
        });
    }

    @Test
    void failsClearlyWhenTheTurnHasNoRecordedPrompt() {
        List<TraceEvent> noPrompt = List.of(
                TraceEvent.ofType("tool_call").with("turn", 1).with("toolUseId", "t1").with("name", "calculator"));
        assertThatThrownBy(() -> EvalExporter.exportYaml(noPrompt, 1, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no user_message");
    }
}
