# agent-blackbox — Design (RFC)

**Status:** Draft / pre-code (Phase 0)
**Author:** Heyward Hagenbuch

A flight recorder for agents: capture every session as a durable trace, replay
it deterministically without touching the real model or real side-effects, diff
trajectories across versions, and export any trace into an `agent-evals` golden
case. This document is the contract; code follows it in phases.

## 1. Problem

When an agent misbehaves in production, there is no stack trace for "it chose the
wrong tool and then lied about it." Observability vendors show dashboards; nobody
gives you **replay**. Without the ability to re-run exactly what happened —
safely, offline, and comparably against current code — debugging an agent is
archaeology, and eval suites are written from imagination instead of reality.

agent-blackbox borrows a classic systems discipline (record/replay — `rr`, VCR
cassettes, event sourcing) and applies it to agents. The payoff line: **every
production incident becomes a permanent eval case with one command.**

## 2. The trace format (`*.trace.jsonl`) — the real product

Append-only JSONL, one event per line, schema-versioned. See
[`TRACE-FORMAT.md`](TRACE-FORMAT.md) and
[`../schemas/agent-blackbox.trace.schema.json`](../schemas/agent-blackbox.trace.schema.json).
Event types: `session_start`, `user_message`, `llm_request`, `llm_response`,
`tool_call`, `tool_result`, `assistant_message`, `error`, `session_end`.

Design rules the format must hold:

- **Failures are events, not gaps.** An error mid-turn is written as an `error`
  event exactly where it happened. A black box exists to explain crashes.
- **Digest-by-default.** Full request payloads are not stored unless
  `--capture-full`; each `llm_request` carries a `messagesDigest`
  (`sha256:…` of the canonicalized messages). Small, privacy-sane, and still
  enough for replay to detect divergence. This default-redacted stance is a
  feature, not a limitation.
- **Redaction on write.** Configurable regex scrubbers (API keys, emails,
  card-like numbers) run before any event touches disk. A scrubbed span is
  marked `"redacted": true` — **marked, never silently altered.**
- **One file per session**, named `{sessionId}-{startedAt}.trace.jsonl`, in a
  configurable trace dir. Retention is a size cap on the dir (delete-oldest),
  documented — not a silent unbounded write.
- **Append-only and crash-tolerant.** The reader tolerates a truncated final
  line: crashes happen mid-write, and that is precisely when the trace matters
  most. A truncated tail is dropped with a warning, never a parse failure.

## 3. Recording: zero changes to the target

`blackbox-spring` is a Spring Boot auto-configuration that decorates the
starter's own abstractions via a `BeanPostProcessor`: `LlmClient` and
`ToolRegistry` beans are wrapped with recording proxies. **No code changes in
`spring-ai-agent-starter`** — add the dependency, get traces.

The architectural insight: **the starter's interfaces were the contract all
along.** Instrumenting by decorating the seam (rather than sprinkling logging
calls through business logic) means the recorder is coupled only to stable
interfaces, the target stays oblivious, and the same decorators work for any
service built on those seams. Recording is an aspect, not a feature.

## 4. Replay: deterministic, safe, judgmental

`blackbox replay trace.jsonl` boots a headless harness:

- **`ReplayLlmClient`** feeds the recorded `llm_response` events back in
  sequence — no API key, no network, no cost.
- **Recorded tool results are returned instead of re-executing tools.** Replaying
  a session must never re-send an email or hit a live endpoint. **Safety is
  non-negotiable and gets its own test** (a trace containing `send_email`
  provably does not invoke the real transport).
- The replayer is **judgmental**: at each step it compares what the *current*
  agent code did (which tool, with what input) against what the trace recorded.
  A mismatch is a **divergence report** — "turn 3: live code called `clock`,
  trace recorded `calculator`." Exit 0 = faithful, exit 1 = diverged
  (CI-compatible, same philosophy as `mcp-pact`).
- `--interactive` steps turn-by-turn — time-travel debugging in the terminal.

### Divergence semantics

Replay walks the trace's turns against a live run driven by `ReplayLlmClient`.
At each `llm_response` boundary it compares the *tool calls the live code would
make* to those recorded. Divergence classes: **tool-name mismatch**,
**tool-input mismatch**, **extra/missing tool call**, **turn-count mismatch**.
Answer-text drift alone is a *warning*, not a divergence — models paraphrase; the
trajectory is the contract. This mirrors agent-evals' "did it do the right
thing, not just say it" stance.

## 5. Diff: `blackbox diff a.trace.jsonl b.trace.jsonl`

Aligns two traces of the same conversation turn-by-turn and reports, per turn:
answer-text similarity, tool-call sequence differences, token deltas, latency
deltas. Human table + `--json`. The canonical use is "prompt v1 vs v2 on the same
input" — and it pairs with `agent-operator`, whose canary produces trace A while
main produces trace B.

## 6. Eval export: the killer command

```bash
blackbox export-eval trace.jsonl --turn 3 --out cases/incident-1234.yaml
```

Emits an `agent-evals` case: the turn's user message as the `prompt`,
`tool_called` assertions from the recorded trajectory, and a `judge` stub
(criteria templated from the recorded answer, `min_score` left blank). **The
output is deliberately a draft** — the README and CLI both say a human reviews
it, because auto-generated oracles nobody reads are how eval suites rot. Good
tools make the right thing easy; they do not pretend judgment is free.

## 7. Non-goals (MVP) — and the agent-meter boundary

- **No UI / dashboard.** This is files + CLI. A trace viewer is future work.
- **No OpenTelemetry / metrics pipeline.** Aggregate observability (latency
  percentiles, token spend over time, live tracing spans) is a *different*
  tool — call it `agent-meter`. The boundary is deliberate and stated here
  because the two will be compared: **blackbox is record/replay for individual
  sessions (debugging + regression); a meter is aggregate telemetry for
  fleets (monitoring).** blackbox answers "what did *this* session do, and does
  current code still do it?"; a meter answers "how is the fleet trending?"
- **No distributed collection.** Traces are local files; shipping them to a
  store is an integration, not a core concern.
- **No auto-approved evals.** Export produces drafts, never merged oracles.

## 8. Modules

| Module | Contents | Deps |
|---|---|---|
| `blackbox-core` | trace model, JSONL reader/writer, redaction, diff engine, eval exporter | Jackson only |
| `blackbox-spring` | auto-config, recording decorators for `LlmClient` / `ToolRegistry` | Spring Boot, starter interfaces |
| `blackbox-cli` | `replay`, `diff`, `export-eval`, `stats` (shaded jar) | core |

## 9. Build phases

0. **Design** — this document + the trace schema, committed alone.
1. **`blackbox-core`** — schema, writer (append; fsync-on-event configurable),
   streaming reader tolerant of a truncated final line, redaction engine.
   Table-driven tests including the truncated-tail case and redaction marking.
2. **`blackbox-spring`** — auto-config + decorators; an integration test boots
   the starter with a fake `LlmClient` and asserts the produced trace matches a
   golden file byte-for-byte (timestamps normalized).
3. **Replay + divergence** — `ReplayLlmClient`, tool-result stubbing, divergence
   detection with exit codes, `--interactive`. e2e: record → replay green; change
   a tool → replay red with a precise report. Plus the no-side-effects safety
   test.
4. **Diff + export-eval + README** — diff engine + CLI; the exporter emits valid
   agent-evals YAML, **validated by actually running agent-evals against it in
   CI** (the repos test each other); README GIF: incident → `export-eval` →
   failing eval → fix → green.

## 10. Guardrails

Clean room. No employer names or domains anywhere. Coupled only to the public
starter interfaces and the public agent-evals YAML format.
