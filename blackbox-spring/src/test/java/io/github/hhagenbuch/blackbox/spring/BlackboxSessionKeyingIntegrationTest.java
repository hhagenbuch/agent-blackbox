package io.github.hhagenbuch.blackbox.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.AgentStarterApplication;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.TokenUsage;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversation-level guarantee: turns that share a {@code sessionId} land in ONE
 * append-only trace, numbered within it — which is what multi-turn {@code diff} and
 * {@code export-eval --turn} operate on. (The per-request fallback is covered in
 * {@link BlackboxRecordingIntegrationTest}, whose POST carries no {@code sessionId}.)
 */
@SpringBootTest(
        classes = {AgentStarterApplication.class, BlackboxSessionKeyingIntegrationTest.FakeAgent.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BlackboxSessionKeyingIntegrationTest {

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
    void twoTurnsOnOneSessionIdAppendToOneTraceNumberedByTurn() throws Exception {
        turn("conv-42", "what is the capital of France?");
        turn("conv-42", "and its population?");

        // one conversation -> one file, not one file per request
        assertThat(traceFiles()).hasSize(1);

        List<TraceEvent> events = readOnlyTrace();
        assertThat(events.stream().map(TraceEvent::type).toList())
                .containsOnlyOnce("session_start"); // opened once, then appended to

        List<TraceEvent> userMessages = events.stream()
                .filter(e -> e.type().equals("user_message")).toList();
        assertThat(userMessages).hasSize(2);
        assertThat(userMessages.get(0).turn()).isEqualTo(1);
        assertThat(userMessages.get(0).text()).isEqualTo("what is the capital of France?");
        assertThat(userMessages.get(1).turn()).isEqualTo(2);
        assertThat(userMessages.get(1).text()).isEqualTo("and its population?");

        // a keyed session stays open across turns...
        assertThat(events.stream().map(TraceEvent::type)).doesNotContain("session_end");

        // ...until the conversation is reset, which finalizes the trace.
        client.delete().uri("/api/chat/conv-42").exchange().expectStatus().isNoContent();
        assertThat(readOnlyTrace().stream().map(TraceEvent::type)).contains("session_end");
    }

    private void turn(String sessionId, String message) {
        client.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sessionId\": \"" + sessionId + "\", \"message\": \"" + message + "\"}")
                .exchange()
                .expectStatus().isOk();
    }

    private List<Path> traceFiles() throws Exception {
        try (Stream<Path> files = Files.list(traceDir)) {
            return files.filter(p -> p.toString().endsWith(".trace.jsonl")).toList();
        }
    }

    private List<TraceEvent> readOnlyTrace() throws Exception {
        return TraceReader.readEvents(traceFiles().get(0));
    }

    /** Stateless fake: every turn answers directly (no tool), so each POST is one chat call. */
    @TestConfiguration
    static class FakeAgent {
        @Bean
        @Primary
        LlmClient fakeLlmClient(ObjectMapper mapper) {
            return new LlmClient() {
                @Override
                public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
                    ArrayNode content = mapper.createArrayNode();
                    content.addObject().put("type", "text").put("text", "ok");
                    return Mono.just(new LlmResponse("ok", List.of(), content, "end_turn", TokenUsage.EMPTY));
                }
            };
        }
    }
}
