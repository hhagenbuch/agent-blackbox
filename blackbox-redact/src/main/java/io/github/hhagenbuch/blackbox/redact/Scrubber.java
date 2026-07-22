package io.github.hhagenbuch.blackbox.redact;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Applies a set of redaction patterns to plain strings. Immutable and
 * thread-safe. A scrub reports whether anything was replaced, so a caller can
 * mark a record {@code redacted} the way blackbox marks a {@code TraceEvent} —
 * removed spans are always visible as {@link Redactions#MARK}, never dropped
 * silently.
 */
public final class Scrubber {

    private final List<Pattern> patterns;

    public Scrubber(List<Pattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /** blackbox's historical default patterns. */
    public static Scrubber withDefaults() {
        return new Scrubber(Redactions.defaults());
    }

    /** The broader credential set, for scrubbing free-form agent output. */
    public static Scrubber forCredentials() {
        return new Scrubber(Redactions.credentials());
    }

    /** The scrubbed text and whether any span was replaced. */
    public record Result(String text, boolean redacted) {
    }

    /** Scrub {@code value}; null in, null out (reported as not redacted). */
    public Result scrub(String value) {
        if (value == null) {
            return new Result(null, false);
        }
        String out = value;
        for (Pattern pattern : patterns) {
            out = pattern.matcher(out).replaceAll(Redactions.MARK);
        }
        return new Result(out, !out.equals(value));
    }

    /** Convenience: the scrubbed text only. */
    public String apply(String value) {
        return scrub(value).text();
    }
}
