package io.github.hhagenbuch.blackbox.replay;

/**
 * One difference between the recorded trace and what the current code does on
 * replay. The trajectory is the contract; a divergence means a behavior change.
 *
 * @param kind     short code, e.g. {@code tool.result}, {@code tool.missing}, {@code request.digest}
 * @param where    where it occurred (tool name + id, or a seq)
 * @param expected what the trace recorded
 * @param actual   what the current code produced
 */
public record Divergence(String kind, String where, String expected, String actual) {

    @Override
    public String toString() {
        return "[" + kind + "] " + where + ": recorded " + quote(expected) + ", got " + quote(actual);
    }

    private static String quote(String s) {
        return s == null ? "(none)" : "\"" + s + "\"";
    }
}
