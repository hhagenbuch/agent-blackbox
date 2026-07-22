package io.github.hhagenbuch.blackbox.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubberTest {

    static Stream<Arguments> defaults() {
        return Stream.of(
                Arguments.of("email", "contact me at jane.doe@example.com please", true),
                Arguments.of("api key", "the key is sk-ant-abcdef0123456789ABCDEF", true),
                Arguments.of("card-like", "card 4111 1111 1111 1111 on file", true),
                Arguments.of("clean prose", "the calculator returned 468013", false),
                Arguments.of("digest is not a secret", "sha256:deadbeefcafe", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("defaults")
    void defaultSetMatchesBlackboxHistory(String label, String text, boolean shouldRedact) {
        var result = Scrubber.withDefaults().scrub(text);
        assertThat(result.redacted()).isEqualTo(shouldRedact);
        if (shouldRedact) {
            assertThat(result.text()).contains(Redactions.MARK);
        } else {
            assertThat(result.text()).isEqualTo(text);
        }
    }

    @Test
    void credentialsSetCatchesMoreShapes() {
        var s = Scrubber.forCredentials();
        assertThat(s.scrub("token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").redacted()).isTrue();
        assertThat(s.scrub("aws AKIAIOSFODNN7EXAMPLE now").redacted()).isTrue();
        assertThat(s.scrub("password = hunter2hunter2").redacted()).isTrue();
        assertThat(s.scrub("sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUV").redacted()).isTrue();
    }

    @Test
    void marksButDoesNotDrop() {
        var out = Scrubber.withDefaults().apply("reach me at a@b.co");
        assertThat(out).isEqualTo("reach me at " + Redactions.MARK);
    }

    @Test
    void nullSafeAndReportedNotRedacted() {
        var r = Scrubber.withDefaults().scrub(null);
        assertThat(r.text()).isNull();
        assertThat(r.redacted()).isFalse();
    }

    @Test
    void emptyPatternSetNeverRedacts() {
        var r = new Scrubber(java.util.List.of()).scrub("sk-ant-abcdef0123456789ABCDEF");
        assertThat(r.redacted()).isFalse();
        assertThat(r.text()).isEqualTo("sk-ant-abcdef0123456789ABCDEF");
    }
}
