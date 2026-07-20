package io.github.hhagenbuch.blackbox.spring;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.AgentStarterApplication;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.TokenUsage;
import io.github.hhagenbuch.agent.llm.ToolCall;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {AgentStarterApplication.class, BlackboxRecordingIntegrationTest.FakeAgent.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BlackboxRecordingIntegrationTest {

    @TempDir
    static Path traceDir;

    @DynamicPropertySource
    static void blackboxProps(DynamicPropertyRegistry registry) {
        registry.add("blackbox.trace-dir", traceDir::toString);
        registry.add("blackbox.redact", () -> "false");
    }

    @Autowired
    WebTestClient client;

    @Test
    void recordsAChatSessionAsATraceWithZeroChangesToTheStarter() throws Exception {
        client.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\": \"what is 2 + 2? use your calculator\"}")
                .exchange()
                .expectStatus().isOk();

        List<TraceEvent> events = readOnlyTrace();
        List<String> types = events.stream().map(TraceEvent::type).toList();

        // the full trajectory the agent took, in order
        assertThat(types).containsSubsequence(
                "session_start",
                "user_message",                  // the prompt, captured from the request body
                "llm_request", "llm_response",   // model asks for the calculator
                "tool_call", "tool_result",      // calculator runs (recorded via the AgentTool seam)
                "llm_request", "llm_response",   // model answers
                "session_end");

        assertThat(firstOfType(events, "user_message").text())
                .isEqualTo("what is 2 + 2? use your calculator");

        // the tool_call/tool_result pair correlates by a single toolUseId
        TraceEvent toolCall = firstOfType(events, "tool_call");
        TraceEvent toolResult = firstOfType(events, "tool_result");
        assertThat(toolCall.get("name").asText()).isEqualTo("calculator");
        assertThat(toolResult.get("toolUseId").asText()).isEqualTo(toolCall.get("toolUseId").asText());
        assertThat(toolResult.get("result").asText()).isEqualTo("4");
        assertThat(toolResult.get("error").asBoolean()).isFalse();

        // llm_request carries a digest, not the raw messages (privacy-sane default)
        assertThat(firstOfType(events, "llm_request").get("messagesDigest").asText()).startsWith("sha256:");
    }

    private List<TraceEvent> readOnlyTrace() throws Exception {
        try (Stream<Path> files = Files.list(traceDir)) {
            Path trace = files.filter(p -> p.toString().endsWith(".trace.jsonl")).findFirst().orElseThrow();
            return TraceReader.readEvents(trace);
        }
    }

    private TraceEvent firstOfType(List<TraceEvent> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
    }

    /** Replaces the real Anthropic client with a scripted one: ask for the calculator, then answer. */
    @TestConfiguration
    static class FakeAgent {
        @Bean
        @Primary
        LlmClient fakeLlmClient(ObjectMapper mapper) {
            AtomicInteger call = new AtomicInteger();
            return new LlmClient() {
                @Override
                public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
                    if (call.getAndIncrement() == 0) {
                        ObjectNode input = mapper.createObjectNode().put("expression", "2 + 2");
                        ArrayNode content = mapper.createArrayNode();
                        content.addObject().put("type", "tool_use").put("id", "tu_1").put("name", "calculator")
                                .set("input", input);
                        return Mono.just(new LlmResponse("", List.of(new ToolCall("tu_1", "calculator", input)),
                                content, "tool_use", TokenUsage.EMPTY));
                    }
                    ArrayNode content = mapper.createArrayNode();
                    content.addObject().put("type", "text").put("text", "2 + 2 = 4");
                    return Mono.just(new LlmResponse("2 + 2 = 4", List.of(), content, "end_turn", TokenUsage.EMPTY));
                }
            };
        }
    }
}
