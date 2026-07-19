package io.github.hhagenbuch.blackbox.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import io.github.hhagenbuch.agent.tools.impl.CalculatorTool;
import io.github.hhagenbuch.agent.tools.impl.ClockTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceReader;
import io.github.hhagenbuch.blackbox.replay.Divergence;
import io.github.hhagenbuch.blackbox.replay.DivergenceReport;
import io.github.hhagenbuch.blackbox.replay.Replayer;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * blackbox replay &lt;trace.jsonl&gt; [--stub &lt;tool&gt;]... [--json]
 * </pre>
 *
 * Replays the recorded tool calls against the current tools and reports
 * divergence. Exit 0 = faithful, 1 = diverged, 2 = usage error. Tools named with
 * {@code --stub} are never executed (safety for side-effecting tools).
 */
public final class Cli {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length < 1 || !args[0].equals("replay")) {
            return usage("expected the 'replay' subcommand");
        }
        String tracePath = null;
        boolean json = false;
        Set<String> stubbed = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--json" -> json = true;
                case "--stub" -> {
                    if (i + 1 >= args.length) {
                        return usage("--stub needs a tool name");
                    }
                    stubbed.add(args[++i]);
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        return usage("unknown flag: " + args[i]);
                    }
                    if (tracePath != null) {
                        return usage("unexpected extra argument: " + args[i]);
                    }
                    tracePath = args[i];
                }
            }
        }
        if (tracePath == null) {
            return usage("missing <trace.jsonl>");
        }

        List<TraceEvent> events = TraceReader.readEvents(Path.of(tracePath));
        // The starter's tools, reconstructed for re-execution. (A richer CLI would
        // discover the target's tools; the MVP wires the starter's known set.)
        ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool(), new ClockTool()));
        DivergenceReport report = new Replayer(registry, stubbed).replay(events);

        System.out.println(json ? toJson(report) : report.render());
        return report.exitCode();
    }

    private static String toJson(DivergenceReport report) {
        ObjectNode root = TraceEvent.mapper().createObjectNode();
        root.put("faithful", report.faithful());
        ArrayNode arr = root.putArray("divergences");
        for (Divergence d : report.divergences()) {
            ObjectNode node = arr.addObject();
            node.put("kind", d.kind());
            node.put("where", d.where());
            node.put("expected", d.expected());
            node.put("actual", d.actual());
        }
        return root.toPrettyString();
    }

    private static int usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: blackbox replay <trace.jsonl> [--stub <tool>]... [--json]");
        return 2;
    }
}
