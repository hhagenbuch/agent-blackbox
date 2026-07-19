package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.blackbox.core.Redactor;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Opens a trace per chat request and publishes the {@link RecordingSession} into
 * the Reactor Context so the downstream decorators (which never see a request or
 * a session id) can attribute their events. Writes {@code session_start} on entry
 * and {@code session_end} when the request completes.
 *
 * <p>The session key is a blackbox-assigned trace id — the seam does not carry
 * the app's own session id, and reading the request body here would consume it
 * before the controller. One request = one trace = one turn.
 */
public final class BlackboxWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(BlackboxWebFilter.class);
    private static final String CHAT_PATH = "/api/chat";

    private final BlackboxProperties props;
    private final Redactor redactor;

    public BlackboxWebFilter(BlackboxProperties props) {
        this.props = props;
        this.redactor = props.isRedact() ? Redactor.defaults() : Redactor.none();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isChatRequest(exchange)) {
            return chain.filter(exchange);
        }

        String traceId = UUID.randomUUID().toString();
        RecordingSession session;
        try {
            Path file = Path.of(props.getTraceDir())
                    .resolve(traceId + "-" + Instant.now().toEpochMilli() + ".trace.jsonl");
            session = new RecordingSession(traceId, new TraceWriter(file, redactor, props.isFsyncPerEvent()));
            session.write(TraceEvent.sessionStart("0.1", traceId, Instant.now().toString(),
                    props.getApp(), props.getModel()));
        } catch (IOException e) {
            log.warn("blackbox: could not open a trace for this request: {}", e.getMessage());
            return chain.filter(exchange);
        }

        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(RecordingSession.CONTEXT_KEY, session))
                .doFinally(signal -> {
                    session.write(TraceEvent.sessionEnd(Instant.now().toString()));
                    session.close();
                });
    }

    private boolean isChatRequest(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith(CHAT_PATH);
    }
}
