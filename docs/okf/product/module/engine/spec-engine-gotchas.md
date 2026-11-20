---
type: Reference
title: Engine - gotchas
description: Engine, Pipeline, Job and DuckDB traps: events, the bus, concurrency, partitions, Space MDC, shared-row state.
tags: [engine, pipeline, jobs, duckdb, gotchas]
timestamp: 2026-11-19T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-engine
---

# Engine - gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- **`ResultSet.wasNull()` reports on the most recent `get*`, not on a named column.** Read it *immediately*
  after the getter whose nullness you care about, or it answers for whichever column you fetched last. Cost a
  real bug in `DbConsignmentOutputStore.producerHighWater` (2026-08-10): an absent instant read back as
  `1970-01-01`. The same trap applies to any nullable numeric — `getLong`/`getInt` return `0`, so the sentinel
  is silent by construction.

- **A write path must read a declaration it already holds — never resolve one by reverse lookup.** Going from a
  *store name* back to the dataset that describes it is ambiguous by construction (the catalog builds that map
  with `putIfAbsent`, so it is first-scan-wins and depends on directory order). Two write paths needed an
  event-time column and both had to take the local route instead: ingest uses the schema's date `PartitionDef`,
  Pipeline sinks use their own `partitions[].source`. This is why `DatasetRelation.temporalColumn` has **no
  caller** despite being built for exactly that job — see
  [`okf/product/module/elt/spec-consignments.md`](../elt/spec-consignments.md) §A.2.

- **Removing a component from a record that is persisted as JSON breaks reading the history.** A bare
  `new ObjectMapper()` fails on unknown properties by default, and every append-only JSONL store here uses one
  (`RunArtifactStore`, `RunLogStore`). Drop a field from the record and Jackson throws on every historical line
  that still carries the key — the whole run's read fails, not just the stale field. Retiring
  `RunArtifact.timeRange` (2026-08-10) needed `FAIL_ON_UNKNOWN_PROPERTIES=false` in the same change. **A reader
  of durable history must tolerate the shapes history actually contains**, so set that flag when the store is
  created, not when a field is first removed.

- **DuckDB reserved words** — `day` is a keyword: alias it (`run_day`) in SQL; quote `"trigger"` too. `rows` is
  one as well (window frames: `ROWS BETWEEN`), which is why the `consignment_outputs` registry spells its count
  column `row_count` even though the plan sketch says `rows` — matching `inspecto_pipeline_provenance` and the
  `lineage` CSV. Watch for these whenever generating SQL with date/trigger/count columns.

- **`ConsignmentEvent.pipeline()` is the LOWERCASED pipeline name** (`cfg.identity().pipelineName()`). Any name
  matching against it (triggers, `runPipeline`, `pathFor`) must use the lowercased id — tests call
  `runPipeline("up_stream")`, not `"UP_STREAM"`.

- **Synchronous bus + a held run claim ⇒ never dispatch inline** — the event bus publishes **synchronously on
  the publishing thread**, which holds that pipeline's `PipelineRunGuard` claim. An event-triggered run of the
  **same** pipeline dispatched inline blocks forever on the claim its own thread holds. Hand off to an off-bus
  virtual-thread pool (`triggerWorkers`) — same reason `JobService` hands off. The last inline holdout, the
  pre-v1 legacy trigger/notify routes, moved off the request thread 2026-07-24
  (`CollectorService.runPipelineOffThread` → submit to `triggerWorkers`, block for the result; the pre-v1
  `200 RunResult` body is preserved). So no HTTP request thread holds a run claim.
  <br>⚠ **The rule outlived its original name.** Until 2026-08-01 this was one global `ingestLock`, a
  `ReentrantLock` held across an entire poll cycle for every pipeline. Two things changed: exclusion is now
  **per pipeline** (`PipelineRunGuard`), so unrelated pipelines never block each other and the poll tick
  dispatches-and-returns; and the guard is a **non-reentrant** binary semaphore, because a claim is taken on
  the selecting thread and released by the thread that ran the work. Note the old wording was itself wrong:
  a re-entrant lock would *not* have deadlocked on an inline same-pipeline run — it would have silently
  re-entered and **double-ingested the inbox**. The hand-off is what makes the claim mean anything.

- **`JobService` total concurrency is bounded only when asked** — `-Djobs.maxConcurrentRuns` (default `0` =
  unbounded) installs a `Semaphore` acquired on the **worker** thread inside `submitRun`/`submitAdhocRun`,
  never the caller, so a full pool queues Runs rather than blocking cron/event/manual dispatch. Distinct from
  the batch-ingest `maxConcurrentRuns` (`MultiCollectorProcessor`) and from same-job non-overlap (`LockingRunner`).
  ⚠ **The ingest lane got its own hierarchy on 2026-08-25** — `ConcurrencyBroker` (per-Pipeline / per-space /
  per-server Consignment caps + a 1–3 priority share), hot-tunable from Settings ▸ Scheduler. The **T (job) lane
  is deliberately out of its scope**, so `-Djobs.maxConcurrentRuns` remains the only bound on Runs. See
  [`okf/product/module/elt/spec-ingest.md`](../elt/spec-ingest.md).

- **Incident resolution is hard-gated backend-side** (I1, 2026-07-24) — `ObjectService.commit()` rejects
  INCIDENT→RESOLVED (422) unless `attributes.postmortem` has a timeline + cause-analysis + corrective-action
  entry and `dueAt` is set; mirrors the UI `mail-model.ts` `postmortemGaps` soft-warn. Keep the two in sync.

- **T15 back-pressure SHIPPED 2026-07-25 — the per-cycle admission cap now exists.** `IntakeGovernor.capFor`
  holds a per-pipeline cap and `CollectorProcessor.admit()` truncates a cycle's candidate set to it (oldest
  first); the rest wait in the durable inbox. Off by default (`-Dingest.maxFilesPerCycle`=0 ⇒ byte-for-byte
  pre-T15 behaviour). The controller halves on **cycle overrun** and doubles back with hysteresis — **inbox lag
  is deliberately NOT the throttle input** (capping intake raises lag, so throttling on it is positive
  feedback); lag stays observability only (`CollectorProcessor.oldestInboxAgeSeconds` / `InboxStatus` /
  `inspecto_inbox_oldest_seconds`). B4 (acquisition back-pressure, 2026-08-02) is the deliberate mirror on the
  *producer* side. See `pipeline-graph-design.md` §3.5.

- **`PartitionWriter` requires non-empty partition columns** (it emits `PARTITION_BY (...)`, and `reveal` derives
  each partition from the staged file's parent dir). Unpartitioned single-file `COPY` paths live in
  `PartitionSinkWriter` and `SummaryWriter.writeFlat`; the legacy writer is untouched.

- ⚠ **Never issue DuckDB `COPY … PARTITION_BY` directly for durable output.** DuckDB names every partition file
  `data_0.parquet`, so two writers targeting the same partition **overwrite each other** — which silently converts
  the append-only invariant into a rewrite. `PartitionWriter` exists for this: it stages, then reveals each file
  under a caller-supplied per-unit-of-work name. `SummaryWriter` reuses it for exactly this reason.

- **Pipeline seed = exactly one `source_store`** in Phase-A live execution (rejects 0 or >1; multi-source merge is
  the `transform.merge` path).

- **Per-space `space` MDC must reach EVERY worker thread on the execution path.** Singleton routing reads the
  MDC on the *current* thread, and MDC does NOT cross thread-pool boundaries. Each executor running ingest/commit
  work must `MDC.getCopyOfContextMap()` on the caller + `setContextMap` on the worker + `clear()` in finally —
  `MultiCollectorProcessor.runAll`/`runConfigs` **and** `CollectorProcessor`'s per-batch executor (the batch commit,
  per-batch metrics and event log fire there, not on the poll thread). Miss one and that space's metrics/events
  silently fall back to `"default"`. The `default` space sets NO MDC, so single-space output stays label-free.

- **Promoting per-process state to a shared row changes the cost CATEGORY of every write — so audit
  the READERS, not just the writers.** `PipelineScheduler.lastRunAtMs` stamped a cadence baseline for
  *every* due pipeline each tick: free as a heap `put`, but one `UPDATE` per pipeline per tick once the
  state moved onto the shared run lease (B2, 2026-09-12). Only 2 of the 5 trigger kinds ever read the
  value back — `DEFAULT_POLL` is due every tick *without consulting it*, and `EVENT`/`MANUAL` never run
  on the loop — so the fix was to stamp only triggers that read it (`usesCadence`). ⚠ That is a
  **write-elision, not a behaviour change**: the elided value is unreadable by construction. 🔴 The
  general rule: when state crosses the process boundary, re-derive who actually consumes it; the write
  pattern that was free in heap is a hot-path round trip in a table.

- **Split the fencing decision per operation — a shared row can need a fenced WRITE and an unfenced
  READ.** On the run lease, "who may run this now" and "when did it last run" are different questions:
  the cadence write is fenced on `owner = me AND epoch = mine` (a pod paused past its TTL must not move
  another owner's baseline), but the read is deliberately owner-independent, because an expired or
  released lease still carries a valid baseline and **a pod that has never held the lease must be able
  to read it** — that is the entire cross-pod fix. ⛔ Adding an `owner`/`expires_at` predicate to the
  read would make every idle pipeline fire immediately.

- **`CREATE TABLE IF NOT EXISTS` is not a migration, and the gap is invisible on a fresh install.**
  Adding `last_run_at` to the lease table (B2) would have thrown on the first read for any deployment
  whose table was already created by B0/B1 — no ordinary test can see it, because every test starts
  from an empty database. Needs a guarded `ALTER TABLE … ADD COLUMN IF NOT EXISTS` (both DuckDB and
  Postgres support it) **plus a regression test that builds the pre-migration table by hand**. ⚠ Apply
  this to every `CREATE TABLE IF NOT EXISTS` in the ops-DB families whenever a column is added.

- **⛔ "Pipeline" names TWO disjoint id spaces, and reusing one key across them is a silent bug.** A
  **collector pipeline** is a `*_pipeline.toon` config, named through `ConfigRegistry` /
  `CollectorService.pathFor`; an **authored pipeline** is a `*_flow.toon` graph in `PipelineStore`, named by a
  pipeline job's `pipeline:`/`flow:` param. Different stores, and *nothing* enforces uniqueness between
  them, so one literal string can denote two unrelated things. 🔴 The B3 slice was designed on the
  assumption they were one namespace — that a cron pipeline-job could collide with a poll-cycle run — and
  the assumption was **false**; a shared lease would have excluded unrelated work on an accidental name
  match while fixing nothing. **Before keying any shared structure on "a pipeline id", establish which of
  the two you have.** `PipelineStore`'s class note states the split; `DbRunLease.SCOPE_AUTHORED` records it.

- **A per-name lock only excludes callers that agree on the name.** `JobService.runJob` serializes on the
  **job name**, but `triggerPipelineRun` builds a *synthetic* config named after the **pipeline id**, so an
  ad-hoc authored-pipeline run and a registered job targeting that same pipeline have different names and never
  excluded each other — they ran the one pipeline concurrently, on a single node, for as long as both paths
  existed. Two registered jobs sharing a `pipeline:` param are the same hole. ⚠ When a second entry point
  is added to an existing guarded path, check it derives the **same key**, not merely that it is guarded.

- **⚠ An exclusion claim must span the WORK, not the submit.** `if (lease.tryAcquire(x)) submit(x)` reads
  like cross-pod arming but is not: the claim is released before the run, so the next pod claims and
  submits the same firing. The plan bullet for B3 was written that way and had to be corrected in the
  build. Hold the claim in the run body's `try`/`finally`.

- **⚠ Module direction beats convenience when placing a seam.** `RunLease` lives in **inspecto**, and
  **inspecto depends on inspecto-engine** — so `JobService` (engine) cannot name it, no matter how obviously
  it is "the same idea". The fix is a narrow engine-side interface (`com.gamma.job.RunClaims`) that the host
  adapts onto at wiring time. ⛔ Do not answer this by moving the richer type downward: `RunLease` is bound
  to a `SpaceRoot` and opens operational-DB families, neither of which the engine knows about.

- **Two things that must key off one value should CALL one function, not both compute it.** The deletion
  fence's running-set and the B3 authored-pipeline claim both need a pipeline job's authored-pipeline id; `trackPipelineStart` now
  calls `authoredPipelineKey()` rather than recomputing it. The payoff showed up in mutation testing: keying `authoredPipelineKey`
  on the job name failed the *pre-existing* fence test alongside the new ones, which is the evidence they
  genuinely share a key rather than happening to agree today.
