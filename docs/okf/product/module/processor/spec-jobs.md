---
type: Concept
title: Jobs & Scheduling
description: JobService — cron, event-triggered, and manual jobs, with an off-bus virtual-thread handoff.
resource: platform/inspecto-engine/src/main/java/com/gamma/job/JobService.java
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
---

# Jobs & Scheduling

`JobService` (`platform/inspecto-engine/src/main/java/com/gamma/job/JobService.java`) hosts a registry of jobs and a
virtual-thread `workers` executor. Four trigger modes:

* **Cron** — jobs with a `cron` field are armed on the shared `Scheduler`. **2026-07-20**: `Scheduler.cron()`
  now returns a `CronHandle`; `JobService.removeJob` cancels it, so a deleted/replaced job's self-re-arming
  chain actually stops instead of ticking as an inert no-op forever (the fire-time `jobs.containsKey` guard
  stays as a second line of defence against the cancel/fire race).
* **Event** — jobs with `on_pipeline` subscribe to the `ConsignmentEventBus`; `onBatchEvent` matches a `SUCCESS`
  status + pipeline name, then `submit()`s. This is the **deadlock-safe** path — `submit` hands work to
  `workers` and returns immediately, so the synchronous [event bus](../event/spec-events-metrics.md) never holds a
  `PipelineRunGuard` claim across a new run.
* **Signal** — jobs with `on_signal` fire when a matching Signal is published: `Signals.matchesType` is an
  exact (case-insensitive) match or a `prefix.*` glob, optionally narrowed by a `when:` guard over the
  firing Signal's payload (`WhenGuard`, fail-closed on an unparsable expression — there is no
  authoring-time validator). Self-loops are suppressed. **Authorable in the UI since 2026-08-10.**
  **Coalescing (operator, 2026-09-28):** by default a burst of matching Signals folds into one follow-up Run
  (the storm guard). Each coalescing Job holds at most ONE queued follow-up; a Signal that lands while it is
  queued folds into it, and a Signal that lands while a Run is in flight queues one that **waits** for that Run
  (signal, cron or manual) instead of being `SKIPPED` "previous run still in flight" — so the last landing of a
  burst is always run after it (`JOB-SIGNAL-OVERLAP-SKIP-1`, 2026-11-13; before it a per-Job `TriggerCoalescer`
  only queued the Run, so the lock skipped every Signal during a Run). A waiting follow-up holds one pool slot
  under `setMaxConcurrentRuns`, and re-checks shutdown, the module gate and read-only when it gets the lock, so
  it is `SKIPPED` with the reason if any changed while it waited. A cross-node arming claim held elsewhere still skips. A Job with `coalesce: false` gets **one Run per Signal**,
  each carrying its own Signal's payload, queued in order on a fair per-Job lane instead of being `SKIPPED`.
  `incident.open` defaults to `coalesce: false` (one Incident per case — cross-Space D10); every other type
  defaults to `true`. The key lives in `params` and round-trips. Bound: at most 1000 queued Runs per Job
  (`JobService.DEFAULT_MAX_PENDING_SIGNAL_RUNS`); past it a Signal is refused — a `SKIPPED` Run plus a
  `job.signal.refused` WARN Signal. Design: [`cross-space-consequence-design.md`](../../../../archived-documents/plans-archive/cross-space-consequence-design.md) §8 D13.
* **Manual** — `POST /jobs/{name}/trigger`. The legacy unversioned call stays **synchronous and unchanged**;
  the same route under `/api/v1` is **async** (W5): it returns `202` + `{runId, …}` + a `Location` header,
  the caller polls `GET /jobs/runs/{runId}`, and an `Idempotency-Key` header replays the cached response on
  retry. Pipeline triggers gained the identical async contract in W5b (poll `GET /runs/runs/{runId}`).

**A disabled job is "not scheduled", not "not runnable" (operator, 2026-09-25).** `enabled: false` switches
off every **automatic** path — cron arming (and so `nextFire`), catch-up, `on_pipeline`, `on_signal`, and a
Decision Rule's `start-job` consequence when the rule is applied automatically (*"job 'x' is disabled — not
started"*). It does **not** switch off the **manual** ones: `POST /jobs/{name}/trigger` (the KPI & Reports page's
*Run now*), its `?dryRun=true` preview, `POST /jobs/runs/{runId}/replay` and a person's
`POST /decision-rules/{name}/apply` all run it ([decision-rules](spec-decision-rules.md)). The seam: `JobService` now
builds **every** configured job into its `jobs` map (boot and `upsertJob`), and the automatic paths gate on
`JobConfig.enabled()` over `configs` — ⛔ never on membership of `jobs`, which no longer means "enabled". Until
this rule the map held only enabled jobs, so *Run now* on a disabled job answered a misleading 404 *"no job
named"* while `GET /jobs` listed it (found on the telco demo rehearsal — all four report jobs ship disabled).
⚠ Building a disabled job is **lenient**: one that cannot build (e.g. a pipeline job with no authored-pipeline
store) is a WARN, not a boot failure or a refused disable — it just stays untriggerable. A genuinely unknown
name is still 404; replay's 409 *"no longer registered"* now means deleted (or its type is not hosted), not
disabled. Pinned by `JobServiceTest.disabledJobIsNotScheduledButRunsManually` (enabled twins as the positive
control), `togglingEnabledKeepsExactlyOneBuiltJobAndArmsOnlyWhenEnabled`, and over HTTP by
`ControlApiJobActionsTest.aDisabledJobIsManuallyTriggerableButNotStartedByADecisionRule`.

⚠ **The write contract is the `job:` TOON section, not a DTO.** `POST /jobs` / `PUT /jobs/{name}` pass the
body straight to `JobConfig.fromMap`, so keys are **snake_case** (`on_pipeline`, `on_signal`, `catch_up`)
and type-specific parameters are **flat** alongside them. An unrecognised key is **absorbed as a parameter,
never rejected** — so a misspelled trigger key yields a job with no trigger and no error anywhere. Pinned by
`ControlApiJobCrudTest`; the full trap and the read side's deliberate asymmetry are in
[`PROJECT_NOTES.md`](../../../../PROJECT_NOTES.md) §4.

`submit()` binds the `space` MDC (for non-default spaces — see [multi-space](architecture-processor.md)) and runs on a
virtual thread. `runJob()` uses `runner.runExclusiveOrSkip(name, …)` for a non-overlap guarantee (a job
already in flight records `SKIPPED`); different jobs run in parallel. On startup, `catchUpMissedFires()`
replays a single missed cron fire for `catch_up: true` jobs from the durable `jobs_runs.csv` ledger.

**Total-concurrency bound (ON by default since 2026-08-26 — BACKLOG D11).** The bound now ships at **4**
(`JobService.DEFAULT_MAX_CONCURRENT_RUNS`), as the other half of the DuckDB `memory_limit` pair — ⚠ whose
*memory* half gained its code default only on 2026-09-26 (GAP-4: 40 % RAM ÷ 4, ≥ 1 GiB — the 4 is this
bound's default; this line said "the `memory_limit=2GB` default" until 2026-09-08):
total memory exposure is `memory_limit` x concurrent Runs, so an unbounded Run count makes any
per-instance cap meaningless. **It is owned by the server configuration** — `scheduler.toon` →
`GET/PUT /system/scheduler`, installed via `JobService.setMaxConcurrentRuns` at boot and on a PUT — and
`-Djobs.maxConcurrentRuns` is a *bootstrap default* consulted only when nothing is stored (⛔ a key served
by the settings tier must not also be read from `-D` at use time; see `SchedulerRoutes`). `0` still means
unbounded, and the bound is **hot-resizable**: a shrink drains (in-flight Runs finish, the new ceiling
gates the next admissions), and crossing back from unbounded re-seeds the permit count rather than
trusting one that unbounded Runs never took from. ⚠ `JobService` is **per space**, so in hosted
multi-space mode the worst case is the bound times the space count; in single-tenant mode (the default)
it is the process-wide bound D11's arithmetic assumes.
A bound of `N` installs a `Semaphore(N)` (`runPermits`)
acquired/released **on the worker thread inside** `submitRun`/`submitAdhocRun`, never on the caller — so a
full pool *queues* fired Runs rather than blocking the cron/event/manual dispatch thread. This is the
per-job-service analogue of the batch-ingest `maxConcurrentRuns` semaphore in
`MultiCollectorProcessor.runAll`, and the stated prerequisite for an eventual on-by-default DuckDB memory
cap (unbounded job concurrency defeats a `RAM/N` per-instance cap — see [DuckDB](../engine/spec-duckdb.md) and
`docs/BACKLOG.md` §5). Deadlock-safe: no Job Type synchronously waits on another Run's completion (all
triggering is fire-and-forget via `workers.submit`).

`JobType` includes `ENRICH`, `REPORT`, `MAINTENANCE`, and **`PIPELINE`** (authored-Pipeline execution — see
[pipeline live execution](../elt/spec-pipeline-execution.md)).

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[The Job Framework](spec-jobs-framework.md)** — The Job Framework (P0–P3, shipped 2026-07-09; `feat!` → 5.0)
2. **[The parameter contract, failure handling and single-seam properties](spec-jobs-parameters-failure.md)** — The parameter contract & runtime Expressions (shipped 2026-08-07/10) · Failure handling: retry, alarm, misfire, re-run (`JOB-RELIABILITY-1`, decision D8, 2026-10-27) · Three single-seam properties, confirmed by audit (2026-09-15)
3. **[Maintenance jobs and the push and process jobs](spec-jobs-maintenance.md)** — Maintenance jobs (MNT, shipped 2026-07-12) · `objectstore.export` — the push post-action (shipped 2026-09-24) · `consignment.process` — the third-party Consignment SPI (shipped 2026-08-04) · Three corrections worth keeping about commit-fired work
4. **[Open rows this concept owns](spec-jobs-open-rows.md)** — Open rows this concept owns — the job-path semantics change (2026-09-16)
