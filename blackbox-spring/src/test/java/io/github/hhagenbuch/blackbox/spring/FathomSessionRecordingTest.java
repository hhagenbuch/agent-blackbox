package io.github.hhagenbuch.blackbox.spring;

import tools.jackson.databind.JsonNode;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blackbox records a fathom-backed session: the agent calls fathom's {@code verify}
 * tool, which reports a STALE index, and the recorder captures that honest result
 * on the tool_result event ... exactly the trace agent-medic would diagnose from.
 * Zero changes to the agent; the fathom tool is decorated via the AgentTool seam
 * like any other. Uses a stub fathom tool so CI needs no fathom jar.
 */
@SpringBootTest(
        classes = {AgentStarterApplication.class, FathomSessionRecordingTest.FathomAgent.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class FathomSessionRecordingTest {

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
    void recordsAFathomVerifyCallAndItsStaleResult() throws Exception {
        client.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\": \"can I trust the indexed value in Money.java? verify it\"}")
                .exchange()
                .expectStatus().isOk();

        List<TraceEvent> events = readOnlyTrace();
        List<String> types = events.stream().map(TraceEvent::type).toList();

        assertThat(types).containsSubsequence(
                "session_start",
                "user_message",
                "llm_request", "llm_response",   // model asks fathom to verify
                "tool_call", "tool_result",      // fathom verify runs (recorded via the AgentTool seam)
                "llm_request", "llm_response",   // model relays the honest answer
                "session_end");

        TraceEvent toolCall = firstOfType(events, "tool_call");
        TraceEvent toolResult = firstOfType(events, "tool_result");
        assertThat(toolCall.get("name").asText()).isEqualTo("verify");
        assertThat(toolResult.get("toolUseId").asText()).isEqualTo(toolCall.get("toolUseId").asText());
        // the honesty property is what got recorded ... not a confident stale value
        assertThat(toolResult.get("result").asText()).contains("verified: false").contains("STALE");
        assertThat(toolResult.get("error").asBoolean()).isFalse();
    }

    private List<TraceEvent> readOnlyTrace() throws Exception {
        // The trace is written asynchronously off the request thread, so under
        // CI load session_end can lag the /api/chat response. Await the terminal
        // event before asserting the trajectory rather than reading eagerly.
        for (int i = 0; i < 100; i++) {
            Optional<Path> trace;
            try (Stream<Path> files = Files.list(traceDir)) {
                trace = files.filter(p -> p.toString().endsWith(".trace.jsonl")).findFirst();
            }
            if (trace.isPresent()) {
                List<TraceEvent> events = TraceReader.readEvents(trace.get());
                if (events.stream().anyMatch(e -> e.type().equals("session_end"))) {
                    return events;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("trace did not reach session_end within timeout");
    }

    private TraceEvent firstOfType(List<TraceEvent> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
    }

    /** A scripted model that asks fathom to verify, plus a stub fathom `verify` tool. */
    @TestConfiguration
    static class FathomAgent {

        @Bean
        @Primary
        LlmClient fakeLlmClient(ObjectMapper mapper) {
            AtomicInteger call = new AtomicInteger();
            return new LlmClient() {
                @Override
                public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
                    if (call.getAndIncrement() == 0) {
                        ObjectNode input = mapper.createObjectNode().put("id", "File:sample/src/Money.java");
                        ArrayNode content = mapper.createArrayNode();
                        content.addObject().put("type", "tool_use").put("id", "tu_1").put("name", "verify")
                                .set("input", input);
                        return Mono.just(new LlmResponse("", List.of(new ToolCall("tu_1", "verify", input)),
                                content, "tool_use", TokenUsage.EMPTY));
                    }
                    ArrayNode content = mapper.createArrayNode();
                    content.addObject().put("type", "text")
                            .put("text", "The index is stale, so I can't trust it ... reindex first.");
                    return Mono.just(new LlmResponse("The index is stale, so I can't trust it ... reindex first.",
                            List.of(), content, "end_turn", TokenUsage.EMPTY));
                }
            };
        }

        /** Stub of fathom's `verify` tool: reports the index no longer matches the source. */
        @Bean
        AgentTool fathomVerifyTool() {
            return new AgentTool() {
                @Override
                public String name() {
                    return "verify";
                }

                @Override
                public String description() {
                    return "Re-read an entity's source and confirm the index still matches it.";
                }

                @Override
                public ObjectNode inputSchema(ObjectMapper mapper) {
                    ObjectNode schema = mapper.createObjectNode();
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("id").put("type", "string");
                    return schema;
                }

                @Override
                public Mono<String> execute(JsonNode input) {
                    return Mono.just("File:sample/src/Money.java\nverified: false  [STALE]\n"
                            + "live source differs from the index → run reindex.");
                }
            };
        }
    }
}
