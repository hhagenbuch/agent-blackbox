# agent-blackbox

[![CI](https://github.com/hhagenbuch/agent-blackbox/actions/workflows/ci.yml/badge.svg)](https://github.com/hhagenbuch/agent-blackbox/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)

> When an agent misbehaves in production, you get a support ticket and a shrug.
> There's no stack trace for "it chose the wrong tool and then lied about it."
> `agent-blackbox` is the flight recorder: every session is captured as an
> append-only trace — messages, tool calls, model/provenance, timings — that you
> can **replay deterministically** (no API key, no side-effects), **diff**
> against another run, and **export as a regression eval** so the same failure
> can never ship again.

**Every production incident becomes a permanent eval case with one command.**
Debugging and testing stop being separate activities.

**Status: Phase 0 — design.** This repo currently contains the design and the
trace-file schema. See [`docs/DESIGN.md`](docs/DESIGN.md) and
[`docs/TRACE-FORMAT.md`](docs/TRACE-FORMAT.md). Code lands in phases; roadmap below.

## How it works

1. **Record** — add the `blackbox-spring` dependency to an agent service and
   every session is written to a `*.trace.jsonl` flight record. **Zero code
   changes**: it decorates the seams (`LlmClient`, `ToolRegistry`) the agent
   already has.
2. **Replay** — `blackbox replay trace.jsonl` re-runs the session against a
   headless harness: recorded model responses are fed back (no key, no network),
   recorded tool results are returned (**no side-effects — a replay never
   re-sends an email**), and the replayer compares what the *current* code does
   against the trace. Faithful → exit 0; **diverged → exit 1 with a precise
   report** ("turn 3: live called `clock`, trace recorded `calculator`").
3. **Diff** — `blackbox diff a.trace.jsonl b.trace.jsonl` aligns two runs of the
   same conversation turn-by-turn: answer similarity, tool-call differences,
   token/latency deltas. (Pairs with a canary vs. main comparison.)
4. **Export** — `blackbox export-eval trace.jsonl --turn 3 --out case.yaml`
   turns a recorded turn into an [agent-evals](https://github.com/hhagenbuch/agent-evals)
   case: the prompt, `tool_called` assertions from the real trajectory, and a
   `judge` stub for a human to confirm.

It closes a loop no single repo can:
[spring-ai-agent-starter](https://github.com/hhagenbuch/spring-ai-agent-starter)
produces behavior → **agent-blackbox captures it** →
[agent-evals](https://github.com/hhagenbuch/agent-evals) enforces it.

## Modules

| Module | Contents |
|---|---|
| `blackbox-core` | trace model, JSONL reader/writer, redaction, diff engine, eval exporter (Jackson only) |
| `blackbox-spring` | Spring Boot auto-config + recording decorators for `LlmClient` / `ToolRegistry` |
| `blackbox-cli` | `replay`, `diff`, `export-eval`, `stats` (shaded jar) |

## Design highlights

- **Failures are events, not gaps** — an error mid-turn is recorded exactly where
  it happened. Debugging the *bad* runs is the whole point.
- **Digest-by-default** — full request payloads aren't stored unless
  `--capture-full`; a `messagesDigest` keeps traces small and privacy-sane while
  still catching divergence. Redaction runs **on write** and marks scrubbed
  spans (`"redacted": true`) rather than silently altering them.
- **Replay safety is non-negotiable** — recorded tool results are replayed;
  real transports are never invoked. It has its own test.

## Roadmap

- [ ] Phase 0 — design doc + trace schema (this)
- [x] Phase 1 — `blackbox-core`: format + reader/writer (truncation-tolerant) + redaction
- [ ] Phase 2 — `blackbox-spring`: decorate-the-seam recording, zero target changes
- [ ] Phase 3 — replay + divergence detection + `--interactive`, with the no-side-effects safety test
- [ ] Phase 4 — diff + `export-eval` (validated by running agent-evals in CI) + README GIF

## License

MIT — see [LICENSE](LICENSE).
