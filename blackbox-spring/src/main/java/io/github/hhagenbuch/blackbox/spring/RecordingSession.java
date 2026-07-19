package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceWriter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The recording state for one request, carried in the Reactor Context so the
 * decorators can find it without the starter passing anything down. One HTTP
 * chat request is one trace file and one logical turn; {@code seq} orders the
 * model calls within it.
 */
public final class RecordingSession {

    /** Reactor Context key under which a session is stored for the request's reactive chain. */
    public static final String CONTEXT_KEY = "blackbox.session";

    private final String traceId;
    private final TraceWriter writer;
    private final AtomicInteger seq = new AtomicInteger();

    public RecordingSession(String traceId, TraceWriter writer) {
        this.traceId = traceId;
        this.writer = writer;
    }

    public String traceId() {
        return traceId;
    }

    public int nextSeq() {
        return seq.incrementAndGet();
    }

    /** Writes an event; failures are swallowed so recording never breaks the request. */
    public synchronized void write(TraceEvent event) {
        try {
            writer.write(event);
        } catch (RuntimeException ignored) {
            // a black box must never take down the flight
        }
    }

    public void close() {
        try {
            writer.close();
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }
}
