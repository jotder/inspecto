---
name: endpoint
description: >
  Add a new ControlApi route in house style. Trigger on "ENDPOINT <METHOD /path>" or any request
  to add or change a control-plane HTTP route. Encodes the fail-closed gate order (write-root
  503 → spec/ConfigSafetyValidator 422 → path jail 403 → conflict 409 → act atomically),
  ConfigCodec reuse, and the mandatory real-HTTP test class covering every gate.
---

# /endpoint — new ControlApi route

House style for control-plane routes. The core is **auth-free** — no auth/scope/401 logic.

## Gate order (fail closed, in this order)

1. **Write-root disabled → 503** — when `-Dassist.write.root` is unset (this is a write gate, not auth).
2. **Spec + `ConfigSafetyValidator` ERROR → 422** — validate the payload against the ConfigSpec.
3. **Path jail → 403** — `resolve().normalize().startsWith(root)` on every user-supplied path.
4. **Conflict → 409** — existing resource / concurrent-change checks.
5. **Act atomically** — write temp + move, or single mutation; no partial state on failure.

## Rules

- Reuse `ConfigCodec` for (de)serialization — never hand-roll TOON/JSON mapping.
- Register the route in `ControlApi` following the surrounding pattern (JDK HttpServer, manual DI).
- **Real-HTTP test class covering every gate**, modeled on `ControlApiConfigWriteTest`
  (ephemeral port, actual requests, one test per gate + the happy path).
- ⚠ **A 2xx `/api/v1` body is the ENVELOPE `{data, metadata, links, diagnostics}`** (`Envelope.shape`),
  so a happy-path test must read its payload under `data` — `JSON.readTree(body).get("data")`, the
  idiom in `ControlApiAsyncV1Test`/`ControlApiDbBrowserTest`. Gate tests that only assert a status
  code pass either way, so this surfaces as NPEs in the happy path ALONE and reads like a handler bug
  when it is a test bug. Non-2xx uses `{error: {…}}` instead — a different shape, not the envelope.
- A **read** route has no write-root gate: skip 503 rather than inventing one. Prefer a key that
  cannot traverse (a bare name, separators refused → 403) over jailing a caller-supplied path, and
  jail the resolved result anyway.
- Bound any collection a route returns (`?limit=` or a hard cap + a `truncated` flag reporting the
  TRUE total) — a diagnostic read must not become an unbounded export.
- Full verify before reporting done: GAUNTLET — see the `build-verify` skill
  (`mvn -o clean test`; UI trio only if UI files changed).
- Leave the change uncommitted unless the operator asks (release-workflow skill governs commits).
