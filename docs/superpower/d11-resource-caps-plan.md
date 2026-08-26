# D11 — ship the resource-cap pair as server configuration

**Status:** SHIPPED 2026-08-26 — all six slices landed; gates green (reactor 3637/0/0/5, UI 2756/5 exit 0). Ships BACKLOG §2 **D11**, measured 2026-07-27. Distil + archive per the docs lifecycle once the operator confirms.
**Decision being executed:** default `memory_limit=2GB` **+** `maxConcurrentRuns=4`, surfaced in the
UI's server configuration next to the existing concurrency knobs — not as `-D`-only flags.

## Why the pair (not either alone)

Total exposure = `memory_limit` x concurrent runs. `memory_limit` alone leaves the multiplier
unbounded (`jobs.maxConcurrentRuns` defaults to `0`); a concurrency cap alone leaves each run on
DuckDB's ~80%-of-RAM-per-instance default. `2GB` x `4` => <=8 GiB worst case (~25% of a 32 GiB box)
vs ~25 GiB *per run* today.

⚠ **`2GB` is a floor, not a starting point to tighten.** Measured: blocking operators HARD-FAIL
rather than spill — at `512MB` a 9M-group `GROUP BY` and a wide `DISTINCT` both died with
`Out of Memory Error` having spilled only ~192 MiB. An aggressive cap converts working jobs into
failing ones. `2GB` is ~2.2x the highest observed peak (1081 MiB) and costs nothing measurable.

⚠ This is **two independent fixed knobs**, NOT the *computed* cap (RAM / semaphore) that D11
explicitly rejected.

## The binding constraint found during grounding

`SchedulerRoutes.java:44` — ⛔ **"No key served here may also be read from `-D` at use time"**
(the 2026-08-15 operational-db decision against split ownership of one fact).

Both knobs violate that today if simply mirrored into `scheduler.toon`:
- `DuckDbUtil.applyGlobalDuckDbSettings` reads `System.getProperty(PROP_MEMORY_LIMIT)` on **every
  connection** (`DuckDbUtil.java:190`).
- `JobService.maxConcurrentRuns` is an inline `Integer.getInteger(...)` **field initialiser**
  (`JobService.java:164`), so no caller can feed it a config value at all.

**Resolution — mirror the precedence the scheduler tier already documents:**
`file` > `property` (bootstrap default, consulted ONLY when the file is absent) > built-in default.
Use-time code reads an **installed** value, never the property. The property keeps working for
existing deployments but stops being a second owner.

## Slices

| # | Slice | Verify |
|---|---|---|
| S1 | `SchedulerSettings` gains `duckdb_memory_limit` (String) + `max_concurrent_job_runs` (Integer); both written only when stated (absent = inherit) | record round-trip test: absent key stays absent |
| S2 | `DuckDbUtil` gains an installed global default consulted by `applyGlobalDuckDbSettings`; property becomes the bootstrap fallback, not a use-time read | `DuckDbSettingsTest` — file value wins over `-D`; neither set = DuckDB default |
| S3 | `JobService` run-permit bound becomes settable + hot-resizable (drain on shrink, mirroring `ConcurrencyBroker.setSystemCap`); `0` still means unbounded | `JobServiceTest` — `availableRunPermits()` after a resize; `0` => `-1` |
| S4 | `SchedulerRoutes` GET serves both values + provenance; PUT validates and hot-applies; `installAtBoot` installs both | `ControlApiSchedulerSettingsTest` — 503 write-root, 422 bounds, provenance flips file/property/default |
| S5 | Scheduler settings pane: two controls in the `system` form + `SchedulerView`/`SchedulerTier` fields + service params + mock handler | `scheduler.component.spec.ts` + `settings.handler.spec.ts`, axe clean |
| S6 | Docs: `okf/backend/engine/duckdb.md` D11 flips to SHIPPED; BACKLOG D11 row + §6 remainder; `okf/.../jobs.md`; USER_GUIDE knob table | vocabulary + secrets guards green |

## Deliberate non-goals

- **Preview / dry-run stay uncapped.** `ComponentPreview`, `PipelineDryRun`, `FileSampler`,
  `SchemaSuggest` etc. do NOT call `applyGlobalDuckDbSettings` (verified — only `EnrichmentEngine:120`
  and `PipelineJobRunner:228` do, plus `BatchIngestStrategy:437` on the per-config path). They run over
  bounded samples and the D11 decision exempts them. Do not widen this.
- **No computed cap.** Rejected by D11 and still rejected.
- **`max_temp_directory_size` still gets no default** — none is defensible without knowing the volume
  size (DuckDB uses ~90% of disk).
- Per-pipeline `processing.threads` and `processing.duckdb.memory_limit` keep working and keep
  winning over the server default — they are a different (narrower) scope, not a duplicate.

## Behaviour change on upgrade (intended)

An install that never set a `-D` flag moves from "unbounded concurrency, ~80% RAM per run" to
"4 concurrent Job Runs, 2GB per DuckDB instance". A 5th concurrent Run **queues**, it is not
rejected. Existing `-Dprocessing.duckdb.memory_limit` / `-Djobs.maxConcurrentRuns` values keep
winning until a file value is written, and the GET reports which one is in force.
