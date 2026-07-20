package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.blackbox.core.Redactor;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Opens (or reuses) a trace for the chat request and publishes the {@link RecordingSession}
 * into the Reactor Context so the downstream decorators can attribute their events.
 *
 * <p>Traces are keyed by the application's own {@code sessionId} (read from the POST body),
 * so a multi-turn conversation is <b>one append-only trace</b> with turns numbered within
 * it — which is what the conversation-level {@code diff} and {@code export-eval --turn}
 * features need. A request with no {@code sessionId} falls back to a per-request trace.
 * A keyed conversation is finalized on {@code DELETE /api/chat/{sessionId}} (reset) or at
 * shutdown (see {@link SessionRegistry}).
 */
public final class BlackboxWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(BlackboxWebFilter.class);
    private static final String CHAT_PATH = "/api/chat";

    private final BlackboxProperties props;
    private final Redactor redactor;
    private final SessionRegistry registry;

    public BlackboxWebFilter(BlackboxProperties props, SessionRegistry registry) {
        this.props = props;
        this.redactor = props.isRedact() ? Redactor.defaults() : Redactor.none();
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isChatRequest(exchange)) {
            return chain.filter(exchange);
        }
        HttpMethod method = exchange.getRequest().getMethod();

        // Reset ends the conversation's trace.
        if (HttpMethod.DELETE.equals(method)) {
            String sessionId = sessionIdFromPath(exchange);
            if (sessionId != null) {
                registry.close(sessionId);
            }
            return chain.filter(exchange);
        }

        // POST /api/chat: the sessionId and prompt live in the body — read it (and re-inject
        // it so the controller can still consume it).
        if (HttpMethod.POST.equals(method)) {
            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .map(BlackboxWebFilter::toBytes)
                    .defaultIfEmpty(new byte[0])
                    .flatMap(body -> openTurn(replayBody(exchange, body), chain,
                            jsonField(body, "sessionId"), jsonField(body, "message")));
        }

        // Other chat methods (e.g. the SSE GET) carry the ids on the query string.
        String sessionId = exchange.getRequest().getQueryParams().getFirst("sessionId");
        String message = exchange.getRequest().getQueryParams().getFirst("message");
        return openTurn(exchange, chain, sessionId, message);
    }

    private Mono<Void> openTurn(ServerWebExchange exchange, WebFilterChain chain,
                                String sessionId, String message) {
        boolean keyed = sessionId != null && !sessionId.isBlank();
        RecordingSession session;
        try {
            session = keyed
                    ? registry.getOrCreate(sessionId, () -> newSession(sessionId))
                    : newSession(UUID.randomUUID().toString());
        } catch (UncheckedIOException e) {
            log.warn("blackbox: could not open a trace for this request: {}", e.getMessage());
            return chain.filter(exchange);
        }

        session.nextTurn();
        if (message != null && !message.isEmpty()) {
            session.write(TraceEvent.userMessage(session.turn(), message));
        }

        Mono<Void> downstream = chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(RecordingSession.CONTEXT_KEY, session));
        // Keyed sessions live across turns — the registry closes them on reset/shutdown.
        // A transient (unkeyed) trace is one turn, so close it when the request completes.
        return keyed ? downstream : downstream.doFinally(signal -> {
            session.write(TraceEvent.sessionEnd(Instant.now().toString()));
            session.close();
        });
    }

    /** Opens a new trace file and writes {@code session_start}; wraps IO failure unchecked. */
    private RecordingSession newSession(String traceId) {
        try {
            Path file = Path.of(props.getTraceDir())
                    .resolve(safe(traceId) + "-" + Instant.now().toEpochMilli() + ".trace.jsonl");
            RecordingSession session =
                    new RecordingSession(traceId, new TraceWriter(file, redactor, props.isFsyncPerEvent()));
            session.write(TraceEvent.sessionStart("0.1", traceId, Instant.now().toString(),
                    props.getApp(), props.getModel()));
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String sessionIdFromPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String prefix = CHAT_PATH + "/";
        return path.startsWith(prefix) && path.length() > prefix.length()
                ? path.substring(prefix.length())
                : null;
    }

    private static String jsonField(byte[] body, String field) {
        if (body.length == 0) {
            return null;
        }
        try {
            return TraceEvent.mapper().readTree(body).path(field).asString(null);
        } catch (JacksonException e) {
            return null; // not JSON we understand (Jackson 3 throws this unchecked)
        }
    }

    /** Re-exposes the already-read body so the controller can still consume it. */
    private ServerWebExchange replayBody(ServerWebExchange exchange, byte[] body) {
        ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
        return exchange.mutate().request(decorated).build();
    }

    private static byte[] toBytes(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    /** Keep the trace-id filename-safe (session ids are usually UUIDs, but don't assume). */
    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean isChatRequest(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith(CHAT_PATH);
    }
}
