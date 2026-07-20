package io.github.hhagenbuch.blackbox.replay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.agent.tools.impl.CalculatorTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- trace builders ---

    private TraceEvent toolUseResponse(int seq, String tool, ObjectNode input) {
        ArrayNode toolCalls = mapper.createArrayNode();
        toolCalls.addObject().put("id", "tu" + seq).put("name", tool).set("input", input);
        return TraceEvent.ofType("llm_response").with("turn", 1).with("seq", seq)
                .with("stopReason", "tool_use").with("toolCalls", toolCalls);
    }

    private TraceEvent endTurn(int seq, String text) {
        return TraceEvent.ofType("llm_response").with("turn", 1).with("seq", seq)
                .with("stopReason", "end_turn").with("text", text);
    }

    /** A one-tool session: prompt → calls a tool → answers. */
    private List<TraceEvent> session(String tool, ObjectNode input, String recordedResult) {
        List<TraceEvent> t = new ArrayList<>();
        t.add(TraceEvent.userMessage(1, "do the thing"));
        t.add(toolUseResponse(1, tool, input));
        t.add(TraceEvent.ofType("tool_call").with("turn", 1).with("toolUseId", "tu1")
                .with("name", tool).with("input", input));
        t.add(TraceEvent.ofType("tool_result").with("turn", 1).with("toolUseId", "tu1")
                .with("result", recordedResult));
        t.add(endTurn(2, "done"));
        return t;
    }

    private AgentTool emailSpy(AtomicInteger sends) {
        return new AgentTool() {
            public String name() {
                return "send_email";
            }

            public String description() {
                return "sends an email";
            }

            public ObjectNode inputSchema(ObjectMapper m) {
                return m.createObjectNode();
            }

            public Mono<String> execute(JsonNode input) {
                sends.incrementAndGet();
                return Mono.just("sent");
            }
        };
    }

    // --- tests ---

    @Test
    void faithfulWhenTheAgentReproducesTheTrajectory() {
        ObjectNode input = mapper.createObjectNode().put("expression", "2 + 2");
        DivergenceReport report = new Replayer(List.of(new CalculatorTool()))
                .replay(session("calculator", input, "4"), Set.of());
        assertThat(report.faithful()).isTrue();
        assertThat(report.exitCode()).isZero();
    }

    @Test
    void executedToolWhoseBehaviorChangedDiverges() {
        ObjectNode input = mapper.createObjectNode().put("expression", "2 + 2");
        // recorded result was "999", but the real calculator returns "4"
        DivergenceReport report = new Replayer(List.of(new CalculatorTool()))
                .replay(session("calculator", input, "999"), Set.of("calculator"));
        assertThat(report.faithful()).isFalse();
        assertThat(report.divergences()).anySatisfy(d -> {
            assertThat(d.kind()).isEqualTo("tool.result");
            assertThat(d.expected()).isEqualTo("999");
            assertThat(d.actual()).isEqualTo("4");
        });
    }

    @Test
    void toolIsNotExecutedByDefaultButIsWithExecute() {
        AtomicInteger sends = new AtomicInteger();
        ObjectNode input = mapper.createObjectNode().put("to", "bob@example.com");

        new Replayer(List.of(emailSpy(sends))).replay(session("send_email", input, "sent"), Set.of());
        assertThat(sends.get()).isZero(); // safe default: never executed

        new Replayer(List.of(emailSpy(sends))).replay(session("send_email", input, "sent"), Set.of("send_email"));
        assertThat(sends.get()).isEqualTo(1); // opted in
    }

    @Test
    void modelCallCountMismatchDiverges() {
        // trace records two model calls, but an end_turn first response makes the agent stop after one
        List<TraceEvent> trace = List.of(
                TraceEvent.userMessage(1, "hi"), endTurn(1, "hello"), endTurn(2, "extra unused"));
        DivergenceReport report = new Replayer(List.of()).replay(trace, Set.of());
        assertThat(report.divergences()).anySatisfy(d -> assertThat(d.kind()).isEqualTo("model.calls"));
    }

    @Test
    void replayNeedsARecordedPrompt() {
        List<TraceEvent> noPrompt = List.of(endTurn(1, "answer"));
        assertThatThrownBy(() -> new Replayer(List.of()).replay(noPrompt, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no user_message");
    }
}
