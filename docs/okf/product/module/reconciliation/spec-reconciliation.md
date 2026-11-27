---
type: Concept
title: Reconciliation — implementation spec
description: Developer-facing spec of the inspecto-reconciliation module — classes and records, the one-day grain computation, the Break lifecycle store, caches, limits and the module seams.
resource: features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconService.java
tags: [reconciliation, spec, breaks, duckdb, backend]
timestamp: 2026-10-19T00:00:00Z
item: module.reconciliation
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: features/inspecto-reconciliation
---

# Reconciliation — implementation spec

Package `com.gamma.recon` in
[`features/inspecto-reconciliation`](../../../../../features/inspecto-reconciliation/src/main/java/com/gamma/recon/).
The overview and lifecycle diagram are in [architecture](architecture-reconciliation.md); the full as-built behaviour of
the Board and its screens, including the performance history, is in the [user guide](user-guide-reconciliation.md);
routes are in [api-routes](api-routes.md). This page maps the code.

## Classes

| Class | Role |
|---|---|
| `ReconConfigLoader` | saved `reconciliation` component → `ReconService.Spec`; validates `bands`. Shared by the routes and the Job so both reconcile identically |
| `ReconService` | stateless comparison. Records `Side(datasetId, relationSql, columnMap, filter)`, `Measure(name, agg, toleranceType, tolerance)`, `Spec(sides, keyColumns, measures, includeRecordCount, cardinality, carriedImpact)` (side 0 is the anchor), `DayResult`, `Page`, `BreakSet`; enum `Cardinality`; `Grain` (an `AutoCloseable` grain table) |
| `ReconDay` | scopes a Spec to one calendar day of each side's temporal column; `Scoped(spec, day, availableDays, fingerprint)`; holds the result `Cache` |
| `ReconBreaks` | Break identity, `merge` into recorded state, the lifecycle; record `Break(pair, key, keyValues, type, column, leftValue, rightValue, diff, status, note, firstSeenAt, lastSeenAt, occurrences, recurrences, assignee, absentRuns, day)` |
| `ReconStateStore` | `<write-root>/recon-state/<id>.json`; record `State(reconciliation, lastRunAt, runs, breaks)`; temp file + atomic move, read-modify-write on one lock |
| `ReconRoutes` | the ten `/recon/*` routes (`RouteModule`) |
| `ReconRunJob` / `ReconRunJobType` | Job Type `recon.run` (`JobTypeProvider`), params `reconciliation` (required, config-only) and `day` (optional DATE); `requires: [objects]` |
| `ReconSignals` | `recon.run.completed` |
| `ReconKindValidator` | `ComponentKindValidator` for kind `reconciliation`: rejects bad `bands` at save (422) on every component writer |
| `ReconComponentDeleteHook` | `ComponentDeleteHook`: deleting the component deletes its state file |

## Computation

- One day at a time (`RECON-PERF-1`): each side is filtered to one day of its Dataset's temporal column
  (`role: temporal`, else `dateField`); a side with neither is refused. No requested day = the latest day present on
  any side; `availableDays` lists at most `MAX_DAYS` (366), newest first.
- `ReconService.dayRun` builds one `FULL OUTER JOIN` grain table per day, sides pre-aggregated per key (so
  `cardinality` is an assertion, not a matching strategy), classifies each row with the bit flags
  `CLS_MISSING_A/B/C`, `CLS_VALUE`, `CLS_CARDINALITY`, and derives totals and the Break summary in one aggregate.
  NULL keys match with `IS NOT DISTINCT FROM`. The implicit Measure is `__records` (`RECORDS`).
- Row filters: `all`, `breaks`, `missing_a`, `missing_b`, `missing_c`, `value_break` (`FILTERS`). Identifiers must
  match `[A-Za-z_][A-Za-z0-9_]*`; filters pass `ExpressionGuard`; SQL runs in an ephemeral `SqlSandbox` over
  `DatasetRelation` SQL.
- Break types (`ReconBreaks.TYPES`): `missing_right`, `missing_left`, `cardinality_break`, `value_break`; pairs `AB`
  and `AC` (`PAIRS`) for a 3-way Reconciliation.

## Cache

`ReconDay.Cache`: an LRU of at most 32 result entries (grain, breaks, rows), bounded by bytes
(`-Drecon.cache.max_bytes`; default max heap / 8 clamped to 64 MiB..1 GiB), plus a separate LRU of 512 small
entries (scope, days, prune, stats). Keys carry the input fingerprint (spec plus every side's file stamps), so a
changed file or config is a new key. No fingerprint (view-backed or virtual Dataset, or more than 50 000 input files
per side) = not cached. `weigh` is an estimate, measured 1.17x the retained heap on a 200k-key 3-way day (`ReconDayWeighMeasureTest`, `RECON_PERF=1`).

## Access and masking

`RECON-READ-UNGATED-1` (fixed 2026-11-26). `POST /recon/columns`, `/recon/run`, `/recon/breaks` and `/recon/rows` stay
read-shaped exemptions, like `POST /bi/query`: what guards them is the Dataset's own sharing and the caller's masking.

- **Sharing.** Every side named by the config (`ReconConfigLoader.datasetIds`) must pass `ComponentAccess.canView`
  before any SQL runs, else the same 404 `unknown dataset '<id>'` as an absent Dataset, so a shared-away id cannot be
  told from a missing one. A saved Reconciliation read by `id` must be viewable too. The check is explicit, not inside
  the resolve lookups, because `ReconDay` reuses a resolved spec across callers. `POST /recon/{id}/record` shares the
  same check.
- **Masking per side.** For a caller without `canRevealLinkEntities`, each side gets the masking `/bi/query` would apply
  to its Dataset (`DatasetCellMasking`, the control plane's public facade over `BiResultMasking`). A key column is
  masked when any side hides its physical column; `/recon/rows` masks each side's raw rows by that side's Dataset and
  masks the echoed key. A masked cell is the Space alias (`masked:<hex>`), the token every other read shows.
- **After the cache.** `ReconDay.Cache` holds the raw answer, shared across callers; masking runs on a copy after the
  cache read, so one caller's raw answer never reaches another.
- **Refused for a caller who cannot reveal (422):** a `sum` Measure or the carried Break impact over a hidden column (it
  returns the column's values, as `/bi/query` refuses); a side `filters` expression with any word naming a hidden
  column (a prefix `LIKE` bisects the raw values, a failed cast prints one; with an untraceable lineage every undeclared
  word counts; an internal `__` name such as the side view `__recon_b` is refused outright, since it reads the whole row); and a `masked:` alias sent as a `path` or `key` value of a hidden key column (it matches no raw value;
  the UI shows the reason). A raw key value is accepted (D-AK15).
- **Error text withheld (2026-11-27, AGENT-KIT-1 W6 D2 security review).** DuckDB quotes a stored value in a run-time
  error (a classified VARCHAR key compared with another side's integer or date key: `Could not convert string
  '<value>'`). For a caller with masking in force, a failed comparison's 422 on run, breaks or rows keeps a compile-time
  error's text (Parser, Binder, Catalog) and withholds any other, as `/bi/query` does (`DatasetCellMasking.scrub`).
- **Accepted residuals:** pages and Break lists are ordered by the raw key, so their order follows the raw key's order.
  When one side's Dataset classifies the key and the other's does not, `/recon/rows` shows the unclassified side's raw
  value next to the alias, linking the two; the caller can read that side raw through `/bi/query` anyway.
- **Not covered yet (`RECON-STATE-READS-UNMASKED-1`):** `GET /recon/state`, `GET /recon/{id}/state`,
  `GET /recon/promoted` and the answer of `POST /recon/{id}/record` return recorded Break keys raw, and the two state
  reads do not ask `canView` of the Reconciliation.
- **Agents (AGENT-KIT-1 W6 D2, 2026-11-27).** The four reads are open to every agent, a person's or a machine's (MCP Tool
  `reconcile_preview`), masked exactly as above (an agent never reveals) and weighted in the agent budget (run and breaks
  5, rows 2, columns 1). Each masking step above stamps the answer after the cache read, so an agent's answer whose copy
  was not masked is withheld (500). Record, Break status and promote stay refused to agents; the state GETs were already.
  As built: [spec-agent-data-seam.md § D2](../mcp/spec-agent-data-seam.md#d2--the-reconciliation-reads).

## Limits

| Constant | Value | Where |
|---|---|---|
| Board page default / max | 50 / 200 | `ReconRoutes` |
| `sample` max keys | 100 000 | `ReconRoutes` |
| Break list default / max limit | 200 / 5 000 | `ReconRoutes` |
| note / assignee length | 2 000 / 200 | `ReconRoutes` |
| `/recon/promoted` and state-list caps | 1 000 each | `ReconRoutes` |
| Breaks per recorded run | 50 000 (refused above, never truncated) | `ReconStateStore.MAX_BREAKS` |
| retained recorded days | 90 | `ReconStateStore.RETAIN_DAYS` |
| Breaks per state file | 200 000 | `ReconStateStore.MAX_STATE_BREAKS` |
| auto-closed Break kept for | 3 absent runs | `ReconBreaks.RECURRENCE_WINDOW_RUNS` |

## Incidents

`POST /recon/promote` opens one Incident per Break, deduped on the Incident attribute `breakId`
(`BREAK-DEDUPE-GRAIN-1`); `GET /recon/promoted` indexes by it. The `recon.run` Job opens at most one active
Incident per Reconciliation on a breach (`hasActive`), best-effort: a failure is logged, the run still succeeds.
Both need the `objects` Platform Service from `inspecto-ops`.

## Tests

[tests-inventory](tests-inventory.md); the perf harness is `ReconPerfTest` (`RECON_PERF=1`).
