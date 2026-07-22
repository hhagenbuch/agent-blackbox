package io.github.hhagenbuch.blackbox.core;

import io.github.hhagenbuch.blackbox.redact.Redactions;
import io.github.hhagenbuch.blackbox.redact.Scrubber;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scrubs sensitive spans out of an event <em>before it touches disk</em>. A
 * scrubbed span is replaced with {@link #MARK} and the event is flagged
 * {@code "redacted": true} — marked, never silently altered, so a reader can
 * always tell a value was removed rather than absent.
 *
 * <p>The patterns and the string-level scrubbing live in the Jackson-free
 * {@code blackbox-redact} module ({@link Scrubber}), so the same redaction any
 * project can reuse on plain strings is exactly what runs here on trace events.
 */
public final class Redactor {

    /** @see Redactions#MARK */
    public static final String MARK = Redactions.MARK;

    private final Scrubber scrubber;

    public Redactor(List<Pattern> patterns) {
        this.scrubber = new Scrubber(patterns);
    }

    /** No-op redactor (used when redaction is disabled). */
    public static Redactor none() {
        return new Redactor(List.of());
    }

    /** Common scrubbers: emails, card-like number runs, and {@code sk-}/{@code xox…} style keys. */
    public static Redactor defaults() {
        return new Redactor(Redactions.defaults());
    }

    /** Returns a redacted copy of the event; the input is not mutated. */
    public TraceEvent redact(TraceEvent event) {
        ObjectNode copy = event.node().deepCopy();
        if (scrub(copy)) {
            copy.put("redacted", true);
        }
        return TraceEvent.of(copy);
    }

    private boolean scrub(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value.isTextual()) {
                    String replaced = apply(value.asText());
                    if (!replaced.equals(value.asText())) {
                        object.put(field.getKey(), replaced);
                        changed = true;
                    }
                } else {
                    changed |= scrub(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isTextual()) {
                    String replaced = apply(value.asText());
                    if (!replaced.equals(value.asText())) {
                        array.set(i, StringNode.valueOf(replaced));
                        changed = true;
                    }
                } else {
                    changed |= scrub(value);
                }
            }
        }
        return changed;
    }

    private String apply(String value) {
        return scrubber.apply(value);
    }
}
