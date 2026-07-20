package io.github.hhagenbuch.blackbox.cli;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CliStatsTest {

    @Test
    void summarizesEventsTurnsToolsAndDuration() {
        List<TraceEvent> events = List.of(
                TraceEvent.sessionStart("1", "s1", "2026-07-20T10:00:00Z", "app", "claude-sonnet-5"),
                TraceEvent.userMessage(1, "what is 2 + 2?"),
                TraceEvent.ofType("tool_call").with("turn", 1).with("name", "calculator"),
                TraceEvent.ofType("tool_result").with("turn", 1).with("result", "4"),
                TraceEvent.ofType("tool_call").with("turn", 2).with("name", "clock"),
                TraceEvent.assistantMessage(2, "4"),
                TraceEvent.sessionEnd("2026-07-20T10:00:02Z"));

        String report = Cli.statsReport(events);

        assertThat(report).contains("events: 7");
        assertThat(report).contains("turns: 2");
        // tools are listed once each, in call order
        assertThat(report).contains("tools used: calculator, clock");
        assertThat(report).contains("duration: 2000 ms");
        // per-type counts include the repeated type
        assertThat(report).contains("tool_call: 2");
    }

    @Test
    void reportsUnknownDurationWhenTheSessionNeverEnded() {
        // a truncated trace (crash mid-session) still summarizes rather than blowing up
        List<TraceEvent> events = List.of(
                TraceEvent.sessionStart("1", "s1", "2026-07-20T10:00:00Z", "app", "claude-sonnet-5"),
                TraceEvent.userMessage(1, "hello"));

        String report = Cli.statsReport(events);

        assertThat(report).contains("duration: (unknown)");
        assertThat(report).contains("tools used: (none)");
        assertThat(report).contains("turns: 1");
    }
}
