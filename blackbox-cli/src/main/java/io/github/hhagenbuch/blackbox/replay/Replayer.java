package io.github.hhagenbuch.blackbox.replay;

import tools.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.core.AgentLoop;
import io.github.hhagenbuch.agent.core.ConversationMemory;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.blackbox.core.TraceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Replays the recorded session through the <em>real</em> {@link AgentLoop},
 * driven by a {@link ReplayLlmClient} (recorded model responses, no key/network)
 * and the recorded prompt. This is the headline: it re-runs the current agent
 * code over the recorded inputs and reports where it now behaves differently.
 *
 * <p>Divergence classes:
 * <ul>
 *   <li>{@code request.digest} — the current code assembled a different request
 *       to the model (a loop-logic or context change);</li>
 *   <li>{@code tool.call} / {@code tool.input} — it invoked a different tool or
 *       input than recorded;</li>
 *   <li>{@code tool.result} — a re-executed ({@code --execute}) tool's output
 *       changed;</li>
 *   <li>{@code model.calls} — it looped a different number of times.</li>
 * </ul>
 * Tools are stubbed with recorded results by default — no side-effects.
 */
public final class Replayer {

    private final List<AgentTool> tools;

    public Replayer(List<AgentTool> tools) {
        this.tools = tools;
    }

    public DivergenceReport replay(List<TraceEvent> events, Set<String> execute) {
        String prompt = recordedPrompt(events);

        ReplayLlmClient llm = ReplayLlmClient.fromTrace(events);
        ReplayToolRegistry registry = new ReplayToolRegistry(tools, events, execute);
        AgentProperties props = new AgentProperties("", "replay", 4096, 24, 0, List.of());
        AgentLoop loop = new AgentLoop(llm, registry, new ConversationMemory(), props, new ObjectMapper());

        // Drive the real agent over the recorded inputs.
        loop.run("replay", prompt).block();

        List<Divergence> divergences = new ArrayList<>();
        divergences.addAll(llm.divergences());
        divergences.addAll(registry.divergences());
        if (llm.callsMade() != llm.recordedResponseCount()) {
            divergences.add(new Divergence("model.calls", "turn",
                    llm.recordedResponseCount() + " model call(s)", llm.callsMade() + " model call(s)"));
        }
        return new DivergenceReport(divergences);
    }

    private String recordedPrompt(List<TraceEvent> events) {
        return events.stream()
                .filter(e -> e.type().equals("user_message"))
                .map(TraceEvent::text)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "trace has no user_message — replay needs the recorded prompt "
                                + "(record with a recorder that captures it)"));
    }
}
