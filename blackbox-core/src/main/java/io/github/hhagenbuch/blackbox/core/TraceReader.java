package io.github.hhagenbuch.blackbox.core;

import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads a {@code *.trace.jsonl} file into events.
 *
 * <p>Crash-tolerant by contract: because writes are append-only and a black
 * box's job is to survive crashes, the <em>final</em> line may be a partial JSON
 * object. An unparseable final line is dropped (with a count on the returned
 * {@link Result}); an unparseable line anywhere earlier is real corruption and
 * throws. That distinction is the whole point — a truncated tail is expected,
 * mid-file garbage is not.
 */
public final class TraceReader {

    /** The events read, plus whether a truncated final line was dropped. */
    public record Result(List<TraceEvent> events, boolean truncatedTailDropped) {
    }

    private TraceReader() {
    }

    public static Result read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<TraceEvent> events = new ArrayList<>();
        boolean truncated = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                events.add(TraceEvent.of((ObjectNode) TraceEvent.MAPPER.readTree(line)));
            } catch (Exception parseError) {
                if (i == lines.size() - 1) {
                    truncated = true; // truncated final line — tolerate
                    break;
                }
                throw new IllegalStateException(
                        "corrupt trace at line " + (i + 1) + ": " + parseError.getMessage(), parseError);
            }
        }
        return new Result(events, truncated);
    }

    /** Convenience: just the events, tolerating a truncated tail. */
    public static List<TraceEvent> readEvents(Path file) {
        return read(file).events();
    }

    public static Stream<TraceEvent> stream(Path file) {
        return readEvents(file).stream();
    }
}
