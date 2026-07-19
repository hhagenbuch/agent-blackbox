package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps one open {@link RecordingSession} per application {@code sessionId}, so a
 * multi-turn conversation is a single append-only trace (turns numbered within it)
 * rather than one file per request. A session is closed — {@code session_end} written —
 * when the conversation is reset (DELETE) or the app shuts down.
 */
public final class SessionRegistry {

    private final Map<String, RecordingSession> open = new ConcurrentHashMap<>();

    /** The session for {@code sessionId}, opening one via {@code factory} on first turn. */
    public RecordingSession getOrCreate(String sessionId, Supplier<RecordingSession> factory) {
        return open.computeIfAbsent(sessionId, key -> factory.get());
    }

    /** Finalize and close a conversation's trace (e.g. on reset); no-op if unknown. */
    public void close(String sessionId) {
        finish(open.remove(sessionId));
    }

    @PreDestroy
    public void closeAll() {
        for (String sessionId : List.copyOf(open.keySet())) {
            close(sessionId);
        }
    }

    private void finish(RecordingSession session) {
        if (session != null) {
            session.write(TraceEvent.sessionEnd(Instant.now().toString()));
            session.close();
        }
    }
}
