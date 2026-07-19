# The `*.trace.jsonl` format

Version `0.1`. One JSON object per line (JSONL); a file is one agent session.
Normative per-line schema:
[`../schemas/agent-blackbox.trace.schema.json`](../schemas/agent-blackbox.trace.schema.json).
This page explains it; the schema file is the source of truth.

Every event has a `type`. The first line is always `session_start` (which also
carries the format version in `v`); the last, on a clean shutdown, is
`session_end`. A file may end mid-line — see [Truncation](#truncation).

## Event types

### `session_start`
| Field | Type | Meaning |
|---|---|---|
| `v` | string | Trace format version (`"0.1"`). |
| `type` | string | `session_start`. |
| `sessionId` | string | Session identifier. |
| `at` | string | ISO-8601 start time. |
| `runtime` | object | `{ app, model }` — the recorded runtime (app name, model id). |

### `user_message`
`{ type, turn, text }` — a user turn. `turn` is a 1-based turn counter shared by
all events within a turn.

### `llm_request`
| Field | Type | Meaning |
|---|---|---|
| `type` | string | `llm_request`. |
| `turn` / `seq` | integer | Turn, and per-turn model-call sequence (a turn may call the model more than once). |
| `messagesDigest` | string | `sha256:…` of the canonicalized messages sent. Digest-by-default; full payload only with `--capture-full` (then `messages` is present). |
| `toolsOffered` | string[] | Tool names offered to the model this call. |
| `messages` | array | Full message payload — **only** when captured with `--capture-full`. |

### `llm_response`
| Field | Type | Meaning |
|---|---|---|
| `type` | string | `llm_response`. |
| `turn` / `seq` | integer | Matches the `llm_request` it answers. |
| `stopReason` | string | `end_turn`, `tool_use`, … |
| `toolCalls` | array | `[{ id, name, input }]` the model requested (empty on a text turn). |
| `text` | string | Text content (may be empty on a tool-use turn). |
| `usage` | object | `{ in, out }` token counts. |
| `millis` | integer | Model call latency. |
| `provenance` | object | Optional `{ answeredBy, link }` (e.g. from castaway) — who answered and under what link state. |

### `tool_call`
`{ type, turn, toolUseId, name, input }` — a tool the loop invoked.

### `tool_result`
`{ type, turn, toolUseId, result, millis, error }` — its outcome. `error` is a
boolean; a failed tool is recorded, not dropped.

### `assistant_message`
`{ type, turn, text }` — the final answer surfaced to the user for the turn.

### `error`
`{ type, turn, where, message }` — a **first-class** failure event. `where` is
one of `llm` / `tool` / `runtime`. Errors are recorded exactly where they
happened; they are never gaps.

### `session_end`
`{ type, at }` — clean shutdown marker. Its **absence** at end-of-file means the
session crashed or is still open — itself diagnostic.

## Redaction

Any string span scrubbed by a configured redactor is replaced with a marker and
the containing event gains `"redacted": true`. Scrubbing is **marked, never
silent** — a reader can always tell a value was removed rather than absent.

## Truncation

Because writes are append-only and a black box's job is to survive crashes, the
**final line may be a partial JSON object**. Readers MUST drop an unparseable
final line (with a warning) and treat the trace as valid up to the last complete
line. A truncated tail is expected, not an error.
