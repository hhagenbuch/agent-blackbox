package io.github.hhagenbuch.blackbox.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private final Redactor redactor = Redactor.defaults();

    static Stream<Arguments> scrubbing() {
        return Stream.of(
                Arguments.of("email", "contact me at jane.doe@example.com please", true),
                Arguments.of("api key", "the key is sk-ant-abcdef0123456789ABCDEF", true),
                Arguments.of("card-like", "card 4111 1111 1111 1111 on file", true),
                Arguments.of("clean prose", "the calculator returned 468013", false),
                Arguments.of("digest is not a secret", "sha256:deadbeefcafe", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scrubbing")
    void scrubsSensitiveSpansAndMarksTheEvent(String label, String text, boolean shouldRedact) {
        TraceEvent redacted = redactor.redact(TraceEvent.userMessage(1, text));

        assertThat(redacted.redacted()).isEqualTo(shouldRedact);
        if (shouldRedact) {
            assertThat(redacted.text()).contains(Redactor.MARK);
        } else {
            assertThat(redacted.text()).isEqualTo(text); // untouched
        }
    }

    @Test
    void scrubsNestedFieldsInObjectsAndArrays() {
        ObjectNode node = TraceEvent.MAPPER.createObjectNode();
        node.put("type", "llm_request");
        ArrayNode messages = node.putArray("messages");
        messages.addObject().put("role", "user").put("content", "email me at a@b.co");

        TraceEvent redacted = redactor.redact(TraceEvent.of(node));

        assertThat(redacted.redacted()).isTrue();
        assertThat(redacted.node().path("messages").path(0).path("content").asText())
                .isEqualTo("email me at " + Redactor.MARK);
    }

    @Test
    void doesNotMutateTheInputEvent() {
        TraceEvent original = TraceEvent.userMessage(1, "reach me at a@b.co");
        redactor.redact(original);
        assertThat(original.text()).isEqualTo("reach me at a@b.co"); // original untouched
        assertThat(original.redacted()).isFalse();
    }

    @Test
    void noneRedactorLeavesEverythingAlone() {
        TraceEvent event = TraceEvent.userMessage(1, "key sk-ant-abcdef0123456789ABCDEF");
        TraceEvent result = Redactor.none().redact(event);
        assertThat(result.text()).doesNotContain(Redactor.MARK);
        assertThat(result.redacted()).isFalse();
    }
}
