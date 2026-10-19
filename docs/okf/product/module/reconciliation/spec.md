---
type: Concept
title: Reconciliation — implementation spec
description: Developer-facing spec of the inspecto-reconciliation module — classes and records, the one-day grain computation, the Break lifecycle store, caches, limits and the module seams.
resource: features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconService.java
tags: [reconciliation, spec, breaks, duckdb, backend]
timestamp: 2026-10-19T00:00:00Z
---

# Reconciliation — implementation spec

Package `com.gamma.recon` in
[`features/inspecto-reconciliation`](../../../../../features/inspecto-reconciliation/src/main/java/com/gamma/recon/).
The overview and lifecycle diagram are in [architecture](architecture-overview.md); the full as-built behaviour of
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
per side) = not cached. `weigh` is an estimate (`RECON-UPLIFT-RESIDUALS-1` (5a)).

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
