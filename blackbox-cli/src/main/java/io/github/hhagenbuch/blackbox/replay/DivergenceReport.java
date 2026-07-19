package io.github.hhagenbuch.blackbox.replay;

import java.util.List;

/**
 * The outcome of a replay: faithful (no divergences → exit 0) or diverged
 * (→ exit 1), same CI-friendly philosophy as mcp-pact.
 */
public record DivergenceReport(List<Divergence> divergences) {

    public DivergenceReport {
        divergences = List.copyOf(divergences);
    }

    public boolean faithful() {
        return divergences.isEmpty();
    }

    public int exitCode() {
        return faithful() ? 0 : 1;
    }

    public String render() {
        if (faithful()) {
            return "replay: faithful — current code reproduces the recorded trajectory";
        }
        StringBuilder out = new StringBuilder("replay: DIVERGED (" + divergences.size() + ")\n");
        for (Divergence d : divergences) {
            out.append("  ✗ ").append(d).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
