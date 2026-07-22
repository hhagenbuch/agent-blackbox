package io.github.hhagenbuch.blackbox.redact;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The redaction pattern sets and the replacement mark, with no dependency on
 * Jackson or any trace model. blackbox-core's {@code Redactor} scrubs
 * {@code TraceEvent}s with these; other projects (e.g. conductor, which
 * redacts session digests before storing them) reuse the exact same patterns
 * against plain strings via {@link Scrubber}. One source of truth for "what a
 * secret looks like."
 */
public final class Redactions {

    /** Replacement for a scrubbed span. Marked, never silently dropped. */
    public static final String MARK = "[redacted]";

    /**
     * The historical blackbox default set: emails, card-like number runs, and
     * {@code sk-}/{@code xox…} style keys. Kept byte-identical to what
     * blackbox-core shipped so existing behavior is unchanged.
     */
    public static List<Pattern> defaults() {
        return List.of(
                Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),            // email
                Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b"),              // card-like
                Pattern.compile("\\b(?:sk|xox[baprs])-[A-Za-z0-9_-]{12,}\\b")); // API keys
    }

    /**
     * A broader set for storing free-form agent output: everything in
     * {@link #defaults()} plus provider tokens, cloud keys, JWTs, private-key
     * blocks, and {@code key = value} secret assignments. Use this when the
     * text being scrubbed is a whole assistant message or transcript span
     * rather than a single structured field.
     */
    public static List<Pattern> credentials() {
        return List.of(
                Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),            // email
                Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b"),              // card-like
                Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{10,}\\b"),        // Anthropic keys
                Pattern.compile("\\b(?:sk|xox[baprs])-[A-Za-z0-9_-]{12,}\\b"), // generic API keys
                Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),       // GitHub tokens
                Pattern.compile("\\bAKIA[A-Z0-9]{16}\\b"),                 // AWS access key id
                Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"), // JWT
                Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
                Pattern.compile("(?i)(?:password|passwd|secret|token|api[_-]?key)\\s*[=:]\\s*\\S+"));
    }

    private Redactions() {
    }
}
