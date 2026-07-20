package io.github.hhagenbuch.blackbox.cli;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.impl.CalculatorTool;
import io.github.hhagenbuch.agent.tools.impl.ClockTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceReader;
import io.github.hhagenbuch.blackbox.diff.TraceDiff;
import io.github.hhagenbuch.blackbox.eval.EvalExporter;
import io.github.hhagenbuch.blackbox.replay.Divergence;
import io.github.hhagenbuch.blackbox.replay.DivergenceReport;
import io.github.hhagenbuch.blackbox.replay.Replayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * blackbox replay      &lt;trace.jsonl&gt; [--execute &lt;tool&gt;]... [--json]
 * blackbox diff        &lt;a.trace.jsonl&gt; &lt;b.trace.jsonl&gt; [--json]
 * blackbox export-eval &lt;trace.jsonl&gt; [--turn N] [--out FILE] [--name NAME]
 * </pre>
 *
 * Exit codes: replay — 0 faithful / 1 diverged; all — 2 on usage error.
 */
public final class Cli {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length < 1) {
            return usage("expected a subcommand: replay | diff | export-eval");
        }
        return switch (args[0]) {
            case "replay" -> replay(args);
            case "diff" -> diff(args);
            case "export-eval" -> exportEval(args);
            default -> usage("unknown subcommand: " + args[0]);
        };
    }

    // --- replay ---

    private static int replay(String[] args) {
        String tracePath = null;
        boolean json = false;
        Set<String> execute = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--json" -> json = true;
                case "--execute" -> {
                    if (i + 1 >= args.length) {
                        return usage("--execute needs a tool name");
                    }
                    execute.add(args[++i]);
                }
                default -> {
                    if (args[i].startsWith("--") || tracePath != null) {
                        return usage("bad replay arguments");
                    }
                    tracePath = args[i];
                }
            }
        }
        if (tracePath == null) {
            return usage("missing <trace.jsonl>");
        }
        List<TraceEvent> events = TraceReader.readEvents(Path.of(tracePath));
        // The starter's tools, available for --execute'd behavioral comparison. By default
        // NO tool runs — recorded results are authoritative, so replay has no side-effects.
        Replayer replayer = new Replayer(List.of(new CalculatorTool(), new ClockTool()));
        DivergenceReport report = replayer.replay(events, execute);
        System.out.println(json ? replayJson(report) : report.render());
        return report.exitCode();
    }

    // --- diff ---

    private static int diff(String[] args) {
        String left = null;
        String right = null;
        boolean json = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--json")) {
                json = true;
            } else if (left == null) {
                left = args[i];
            } else if (right == null) {
                right = args[i];
            } else {
                return usage("bad diff arguments");
            }
        }
        if (left == null || right == null) {
            return usage("diff needs <a.trace.jsonl> <b.trace.jsonl>");
        }
        TraceDiff.Result result = TraceDiff.diff(
                TraceReader.readEvents(Path.of(left)), TraceReader.readEvents(Path.of(right)));
        System.out.println(json ? diffJson(result) : result.render());
        return 0;
    }

    // --- export-eval ---

    private static int exportEval(String[] args) {
        String tracePath = null;
        int turn = 1;
        String out = null;
        String name = "exported-from-blackbox";
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--turn" -> turn = Integer.parseInt(args[++i]);
                case "--out" -> out = args[++i];
                case "--name" -> name = args[++i];
                default -> {
                    if (args[i].startsWith("--") || tracePath != null) {
                        return usage("bad export-eval arguments");
                    }
                    tracePath = args[i];
                }
            }
        }
        if (tracePath == null) {
            return usage("missing <trace.jsonl>");
        }
        String yaml = EvalExporter.exportYaml(TraceReader.readEvents(Path.of(tracePath)), turn, name);
        if (out == null) {
            System.out.print(yaml);
        } else {
            try {
                Files.writeString(Path.of(out), yaml);
                System.err.println("wrote eval case to " + out + " — review the judge criteria before committing");
            } catch (Exception e) {
                System.err.println("error: could not write " + out + ": " + e.getMessage());
                return 2;
            }
        }
        return 0;
    }

    private static String replayJson(DivergenceReport report) {
        ObjectNode root = TraceEvent.mapper().createObjectNode();
        root.put("faithful", report.faithful());
        ArrayNode arr = root.putArray("divergences");
        for (Divergence d : report.divergences()) {
            arr.addObject().put("kind", d.kind()).put("where", d.where())
                    .put("expected", d.expected()).put("actual", d.actual());
        }
        return root.toPrettyString();
    }

    private static String diffJson(TraceDiff.Result result) {
        ObjectNode root = TraceEvent.mapper().createObjectNode();
        root.put("identical", result.identical());
        ArrayNode arr = root.putArray("aspects");
        for (TraceDiff.Aspect a : result.aspects()) {
            arr.addObject().put("name", a.name()).put("a", a.left()).put("b", a.right())
                    .put("changed", a.changed());
        }
        return root.toPrettyString();
    }

    private static int usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage:");
        System.err.println("  blackbox replay <trace.jsonl> [--execute <tool>]... [--json]");
        System.err.println("  blackbox diff <a.trace.jsonl> <b.trace.jsonl> [--json]");
        System.err.println("  blackbox export-eval <trace.jsonl> [--turn N] [--out FILE] [--name NAME]");
        return 2;
    }
}
