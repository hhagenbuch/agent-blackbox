package io.github.hhagenbuch.blackbox.diff;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDiffTest {

    private List<TraceEvent> trace(String toolName, String answer) {
        return List.of(
                TraceEvent.userMessage(1, "same prompt"),
                TraceEvent.ofType("tool_call").with("turn", 1).with("toolUseId", "t1").with("name", toolName),
                TraceEvent.ofType("llm_response").with("turn", 1).with("seq", 2)
                        .with("stopReason", "end_turn").with("text", answer));
    }

    @Test
    void identicalTrajectoriesReportNoChange() {
        TraceDiff.Result result = TraceDiff.diff(trace("calculator", "4"), trace("calculator", "4"));
        assertThat(result.identical()).isTrue();
        assertThat(result.render()).isEqualTo("diff: identical trajectory");
    }

    @Test
    void reportsChangedToolsAndAnswer() {
        TraceDiff.Result result = TraceDiff.diff(trace("calculator", "4"), trace("clock", "it is noon"));

        assertThat(result.identical()).isFalse();
        assertThat(result.aspects()).anySatisfy(a -> {
            assertThat(a.name()).isEqualTo("tool calls");
            assertThat(a.left()).isEqualTo("calculator");
            assertThat(a.right()).isEqualTo("clock");
            assertThat(a.changed()).isTrue();
        });
        assertThat(result.aspects()).anySatisfy(a -> {
            assertThat(a.name()).isEqualTo("final answer");
            assertThat(a.changed()).isTrue();
        });
    }
}
