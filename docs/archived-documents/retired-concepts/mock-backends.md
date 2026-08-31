---
type: Convention
title: Mock Backends
description: The unified, flag-gated mockApiInterceptor (inspecto/mock/) — per-space handlers + seed packs — that lets the UI run fully offline, enveloping v1 responses at its edge.
resource: inspecto-ui/src/app/inspecto/mock/mock-api.interceptor.ts
tags: [mock, interceptor, offline, environment, seeds, v1-envelope]
timestamp: 2026-07-07T00:00:00Z
---

> **RETIRED 2026-08-31.** The offline UI mock backend was deleted — `inspecto-ui/src/app/inspecto/mock/`,
> `environment.offline.ts`, the `offline` build/serve configurations and `npm run start:offline` are all gone,
> along with the ten `environment.mock*` flags and every production branch on them. The UI now talks only to a
> real ControlApi. Kept for provenance; **not current**. See `docs/superpower/mock-backend-removal-plan.md`.

# Mock Backends

The UI can run **fully offline** via the unified **`mockApiInterceptor`** (`inspecto/mock/`), registered in
`app.config.ts` right after the `v1Interceptor`. It is a persistent, per-space in-memory backend: `MockFlags`
from `environment.ts` (`mockSpaces`, `mockStudio`, `mockFlows`, `mockJobs`, `mockOps`, `mockDemo`,
`mockConnectionProbe`, `mockAuthMode`) gate which of the ~12 **handlers** (`mock/handlers/`) answer —
production builds enable none. Build philosophy: *build UI first, full mock backend, wire the real backend
later.*

## v1 envelope at the mock edge

Because the real backend serves `/api/v1` with an envelope and the first-position `v1Interceptor` unwraps it,
the mock layer **envelopes its responses at its own edge**: `v1SuccessBody` / `v1ErrorBody` in
`mock/mock-http.ts` shape the reply exactly like the backend's `Envelope`. The handlers themselves stay
**raw-DTO** — only the edge wraps. See [API & data](api-and-data.md).

## Seed packs

`mock/seeds/` ships realistic per-space packs: the default space seeds the **Studio** (demo dataset,
**Link Analysis** link-table + saved view, **Geo Map** coordinate dataset + saved geo view), plus
`link-analysis`, `pipeline-case-studies`, `financial-audit`, `fraud-mgmt`, `telecom-ra`, and `operations`
packs.

## Notes

* The `/components/{type}` CRUD store backs the reusable component types, incl. `rule` (the
  [rule](../design-system/rule.md) templates — `'rule'` is a `ComponentType` but is **not** in the
  `COMPONENT_TYPES` palette list).
* **Persistence**: the `MockStore` snapshots every mutation to `localStorage` (memory in tests), so authored
  mock data **survives a reload**; each space is seeded exactly once, and `reset()` restores pristine seeds
  (a schema-version bump discards old snapshots).
  ⚠ **Correcting a seed is not enough — bump `MOCK_STORE_KEY`.** Because each space seeds exactly once and
  the snapshot persists, a corrected seed reaches **only first-time visitors**; every existing browser goes
  on serving the old data indefinitely. This bit the W2/U-D node-type rename (2026-07-31): without the
  v20→v21 bump, existing sessions would have kept authoring pipelines with node types the backend has never
  had. The version comment should say *why* it moved, so the next bump can tell which data it invalidated.
* ⚠ **Contract parity covers graph TOPOLOGY, not just refusals** (MOCK-1, 2026-08-15). The mock's
  `pipeline-editable.ts` lift emitted a `transform.map` node only for an authored `processing.map`, while
  `PipelineLift.branch` emits one on **every** path — only its config is conditional. So the offline editor
  drew a graph one node shorter than the server's for most pipelines, and one node shorter **per branch**
  for a selector/segments pipeline. It was never a *leniency* hole (nothing was accepted offline that the
  server refuses), which is why the usual refusal-parity checks all passed.
  🔴 **The whole UI suite passed with a zero delta both before and after the fix** — nothing pinned the
  derived node at all. Treat a green suite across a topology change as evidence that the topology is
  *unpinned*, not that it is right; the guard has to be added with the fix.
* The Pro [data-table](../design-system/data-table.md) SQL editor runs SQL **in-browser** via AlaSQL (no
  backend) — independent of the mock layer.
* ⚠ **`MockHandler` is synchronous, and that is load-bearing.** Its return type was widened to
  `MockResponse | Promise<MockResponse>` in 2026-08-14 so the `/db/query` mock could await the lazily
  imported SQL engine; it compiles, but **17 handler specs** call handlers directly and read `.status` /
  `.body` off the result, so all of them break. That is far too much blast radius for one endpoint —
  the change was reverted. If a mock genuinely needs to await, give the interceptor a separate,
  explicitly typed async list rather than widening the shared type.
* **`/db/*` offline IS `SAMPLE_SOURCES`** (2026-08-14, Catalog split S2 slice C): `/db/catalog` lists every
  sample store and `/db/table` pages the named one with server-derived DuckDB types, roles and cardinality
  (a port of `ResultSetDescriptor`), the same 200-default / 5000-max clamp, and real `truncated`. One
  offline reality for the Data Browser, the Dataset store picker and the seeded space templates — before
  it, the handler answered every request with three fixed `orders` rows regardless of the table asked for.
  ⚠ **`/db/query` answers a 501 for valid SQL** rather than executing it (see the sync rule above). That is
  deliberate: the old echo-the-table behaviour meant an offline query "worked" while proving nothing. A
  guard-shaped **422** (non-SELECT, `;`-chained) is checked FIRST, so a refusal the server would make is
  still rehearsed offline. ⛔ Do not restore the echo.
