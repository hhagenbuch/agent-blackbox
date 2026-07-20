package io.github.hhagenbuch.blackbox.replay;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private TraceEvent llmResponseWithTool() {
        ArrayNode toolCalls = mapper.createArrayNode();
        toolCalls.addObject().put("id", "tu_1").put("name", "calculator")
                .set("input", mapper.createObjectNode().put("expression", "2 + 2"));
        return TraceEvent.ofType("llm_response").with("turn", 1).with("seq", 1)
                .with("stopReason", "tool_use").with("toolCalls", toolCalls);
    }

    private TraceEvent llmResponseText() {
        return TraceEvent.ofType("llm_response").with("turn", 1).with("seq", 2)
                .with("stopReason", "end_turn").with("text", "the answer is 4");
    }

    @Test
    void replaysRecordedResponsesInOrder() {
        ReplayLlmClient client = ReplayLlmClient.fromTrace(List.of(llmResponseWithTool(), llmResponseText()));

        LlmResponse first = client.chat(List.of(), List.of()).block();
        assertThat(first.stopReason()).isEqualTo("tool_use");
        assertThat(first.wantsTools()).isTrue();
        assertThat(first.toolCalls().get(0).name()).isEqualTo("calculator");

        LlmResponse second = client.chat(List.of(), List.of()).block();
        assertThat(second.stopReason()).isEqualTo("end_turn");
        assertThat(second.text()).isEqualTo("the answer is 4");
    }

    @Test
    void flagsARequestDigestDivergence() {
        // a recorded request digest that the actual (empty) messages won't match
        TraceEvent recordedRequest = TraceEvent.ofType("llm_request").with("turn", 1).with("seq", 1)
                .with("messagesDigest", "sha256:recorded-something-else");
        ReplayLlmClient client = ReplayLlmClient.fromTrace(List.of(recordedRequest, llmResponseText()));

        client.chat(List.of(), List.of()).block();

        assertThat(client.divergences()).hasSize(1);
        assertThat(client.divergences().get(0).kind()).isEqualTo("request.digest");
        assertThat(client.divergences().get(0).expected()).isEqualTo("sha256:recorded-something-else");
    }
}
