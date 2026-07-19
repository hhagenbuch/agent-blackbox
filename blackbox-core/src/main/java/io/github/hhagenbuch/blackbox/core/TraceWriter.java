package io.github.hhagenbuch.blackbox.core;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Appends events to a {@code *.trace.jsonl} file, one JSON object per line.
 * Every event is passed through the {@link Redactor} before it is written, so
 * sensitive data never reaches disk. With {@code fsyncPerEvent}, each event is
 * flushed to stable storage before the call returns — the durability a black box
 * wants (at a write-latency cost), off by default.
 */
public final class TraceWriter implements Closeable {

    private final FileOutputStream out;
    private final BufferedWriter writer;
    private final Redactor redactor;
    private final boolean fsyncPerEvent;

    public TraceWriter(Path file, Redactor redactor, boolean fsyncPerEvent) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        this.out = new FileOutputStream(file.toFile(), true); // append
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.redactor = redactor;
        this.fsyncPerEvent = fsyncPerEvent;
    }

    public void write(TraceEvent event) {
        try {
            TraceEvent redacted = redactor.redact(event);
            writer.write(TraceEvent.MAPPER.writeValueAsString(redacted.node()));
            writer.write("\n");
            writer.flush();
            if (fsyncPerEvent) {
                out.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write trace event", e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
