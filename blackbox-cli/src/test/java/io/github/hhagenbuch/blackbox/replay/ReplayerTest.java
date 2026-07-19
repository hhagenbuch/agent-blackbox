package io.github.hhagenbuch.blackbox.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import io.github.hhagenbuch.agent.tools.impl.CalculatorTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** A trace fragment: calculator(2 + 2) recorded as returning "4". */
    private List<TraceEvent> calculatorTrace(String recordedResult) {
        ObjectNode input = mapper.createObjectNode().put("expression", "2 + 2");
        return List.of(
                TraceEvent.ofType("tool_call").with("turn", 1)
                        .with("toolUseId", "t1").with("name", "calculator").with("input", input),
                TraceEvent.ofType("tool_result").with("turn", 1)
                        .with("toolUseId", "t1").with("result", recordedResult));
    }

    @Test
    void faithfulWhenTheToolStillBehavesTheSame() {
        ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool()));
        DivergenceReport report = new Replayer(registry, Set.of()).replay(calculatorTrace("4"));
        assertThat(report.faithful()).isTrue();
        assertThat(report.exitCode()).isZero();
    }

    @Test
    void divergesWhenAToolsBehaviorChanged() {
        // a "calculator" that now returns the wrong answer
        ToolRegistry registry = new ToolRegistry(List.of(fixedTool("calculator", "5")));
        DivergenceReport report = new Replayer(registry, Set.of()).replay(calculatorTrace("4"));

        assertThat(report.faithful()).isFalse();
        assertThat(report.exitCode()).isEqualTo(1);
        Divergence d = report.divergences().get(0);
        assertThat(d.kind()).isEqualTo("tool.result");
        assertThat(d.expected()).isEqualTo("4");
        assertThat(d.actual()).isEqualTo("5");
    }

    @Test
    void stubbedToolIsNeverExecutedOnReplay() {
        AtomicInteger sends = new AtomicInteger();
        AgentTool email = new AgentTool() {
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
                sends.incrementAndGet(); // the real side-effect
                return Mono.just("sent");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(email));
        List<TraceEvent> trace = List.of(
                TraceEvent.ofType("tool_call").with("turn", 1)
                        .with("toolUseId", "e1").with("name", "send_email")
                        .with("input", mapper.createObjectNode().put("to", "bob@example.com")),
                TraceEvent.ofType("tool_result").with("turn", 1)
                        .with("toolUseId", "e1").with("result", "sent"));

        DivergenceReport report = new Replayer(registry, Set.of("send_email")).replay(trace);

        assertThat(sends.get()).isZero(); // provably no side-effect
        assertThat(report.faithful()).isTrue();
    }

    @Test
    void removedToolSurfacesAsDivergence() {
        ToolRegistry empty = new ToolRegistry(List.of()); // calculator no longer exists
        DivergenceReport report = new Replayer(empty, Set.of()).replay(calculatorTrace("4"));
        assertThat(report.faithful()).isFalse();
        assertThat(report.divergences().get(0).actual()).startsWith("ERROR: unknown tool");
    }

    private AgentTool fixedTool(String name, String result) {
        return new AgentTool() {
            public String name() {
                return name;
            }

            public String description() {
                return "test";
            }

            public ObjectNode inputSchema(ObjectMapper m) {
                return m.createObjectNode();
            }

            public Mono<String> execute(JsonNode input) {
                return Mono.just(result);
            }
        };
    }
}
