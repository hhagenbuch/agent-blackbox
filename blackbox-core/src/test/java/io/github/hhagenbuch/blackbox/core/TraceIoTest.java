package io.github.hhagenbuch.blackbox.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIoTest {

    private List<TraceEvent> session() {
        return List.of(
                TraceEvent.sessionStart("0.1", "s1", "2026-07-19T00:00:00Z",
                        "spring-ai-agent-starter", "claude-sonnet-5"),
                TraceEvent.userMessage(1, "What is 973 * 481?"),
                TraceEvent.assistantMessage(1, "468013"),
                TraceEvent.sessionEnd("2026-07-19T00:00:02Z"));
    }

    @Test
    void writesAndReadsBackFaithfully(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s1.trace.jsonl");
        try (TraceWriter writer = new TraceWriter(file, Redactor.none(), false)) {
            session().forEach(writer::write);
        }

        List<TraceEvent> read = TraceReader.readEvents(file);
        assertThat(read).isEqualTo(session());
        assertThat(read).extracting(TraceEvent::type)
                .containsExactly("session_start", "user_message", "assistant_message", "session_end");
    }

    @Test
    void appendsAcrossWriterSessions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s1.trace.jsonl");
        try (TraceWriter w = new TraceWriter(file, Redactor.none(), false)) {
            w.write(TraceEvent.userMessage(1, "first"));
        }
        try (TraceWriter w = new TraceWriter(file, Redactor.none(), false)) {
            w.write(TraceEvent.userMessage(2, "second"));
        }
        assertThat(TraceReader.readEvents(file)).extracting(TraceEvent::text)
                .containsExactly("first", "second");
    }

    @Test
    void fsyncPerEventStillRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s1.trace.jsonl");
        try (TraceWriter w = new TraceWriter(file, Redactor.none(), true)) { // fsync on every event
            w.write(TraceEvent.userMessage(1, "durable"));
        }
        assertThat(TraceReader.readEvents(file)).extracting(TraceEvent::text).containsExactly("durable");
    }

    @Test
    void toleratesATruncatedFinalLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s1.trace.jsonl");
        try (TraceWriter w = new TraceWriter(file, Redactor.none(), false)) {
            w.write(TraceEvent.userMessage(1, "complete"));
            w.write(TraceEvent.assistantMessage(1, "also complete"));
        }
        // simulate a crash mid-write: append a partial JSON object with no newline
        Files.writeString(file, "{\"type\":\"user_message\",\"turn\":2,\"tex",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        TraceReader.Result result = TraceReader.read(file);
        assertThat(result.truncatedTailDropped()).isTrue();
        assertThat(result.events()).hasSize(2); // the two complete events survive
    }

    @Test
    void throwsOnCorruptionThatIsNotTheFinalLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s1.trace.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"user_message\",\"turn\":1,\"text\":\"ok\"}",
                "{ this is not json }",
                "{\"type\":\"assistant_message\",\"turn\":1,\"text\":\"ok\"}") + "\n");

        assertThatThrownBy(() -> TraceReader.read(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt trace at line 2");
    }
}
