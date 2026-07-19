package io.github.hhagenbuch.blackbox.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * One event in a {@code *.trace.jsonl} file, backed by a Jackson {@link ObjectNode}.
 *
 * <p>The node-backed model is deliberate: the trace format has nine event types
 * with varied and evolving fields (provenance, {@code --capture-full} payloads,
 * redaction marks), and every consumer — writer, reader, redactor, diff — works
 * naturally over the JSON tree. Typed accessors are provided for the fields the
 * core needs; anything else is reachable via {@link #get}.
 */
public final class TraceEvent {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode node;

    private TraceEvent(ObjectNode node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    public static TraceEvent of(ObjectNode node) {
        return new TraceEvent(node);
    }

    /** Starts a new event of the given type. */
    public static TraceEvent ofType(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        return new TraceEvent(node);
    }

    // --- fluent builders for the common events (more added as recording lands) ---

    public static TraceEvent sessionStart(String version, String sessionId, String at, String app, String model) {
        TraceEvent e = ofType("session_start");
        e.node.put("v", version);
        e.node.put("sessionId", sessionId);
        e.node.put("at", at);
        ObjectNode runtime = e.node.putObject("runtime");
        runtime.put("app", app);
        runtime.put("model", model);
        return e;
    }

    public static TraceEvent userMessage(int turn, String text) {
        return ofType("user_message").with("turn", turn).with("text", text);
    }

    public static TraceEvent assistantMessage(int turn, String text) {
        return ofType("assistant_message").with("turn", turn).with("text", text);
    }

    public static TraceEvent error(int turn, String where, String message) {
        return ofType("error").with("turn", turn).with("where", where).with("message", message);
    }

    public static TraceEvent sessionEnd(String at) {
        return ofType("session_end").with("at", at);
    }

    public TraceEvent with(String field, String value) {
        node.put(field, value);
        return this;
    }

    public TraceEvent with(String field, int value) {
        node.put(field, value);
        return this;
    }

    public TraceEvent with(String field, JsonNode value) {
        node.set(field, value);
        return this;
    }

    // --- accessors ---

    public ObjectNode node() {
        return node;
    }

    public String type() {
        return node.path("type").asText();
    }

    public boolean redacted() {
        return node.path("redacted").asBoolean(false);
    }

    public int turn() {
        return node.path("turn").asInt();
    }

    public String text() {
        return node.path("text").asText(null);
    }

    public JsonNode get(String field) {
        return node.get(field);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TraceEvent other && node.equals(other.node);
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    @Override
    public String toString() {
        return node.toString();
    }
}
