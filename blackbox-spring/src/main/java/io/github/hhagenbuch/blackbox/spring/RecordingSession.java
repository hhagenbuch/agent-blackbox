package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceWriter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The recording state for one conversation, carried in the Reactor Context so the
 * decorators can find it without the starter passing anything down. A trace spans the
 * whole session (all its chat requests): {@code turn} counts the requests/turns, and
 * {@code seq} orders the model calls within the current turn.
 */
public final class RecordingSession {

    /** Reactor Context key under which a session is stored for the request's reactive chain. */
    public static final String CONTEXT_KEY = "blackbox.session";

    private final String traceId;
    private final TraceWriter writer;
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger turn = new AtomicInteger();

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

    /** Advance to the next turn (called once per chat request); returns the new turn number. */
    public int nextTurn() {
        return turn.incrementAndGet();
    }

    /** The current turn — what the decorators tag their events with. */
    public int turn() {
        return Math.max(1, turn.get());
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
