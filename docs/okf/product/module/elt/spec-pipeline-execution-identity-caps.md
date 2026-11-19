---
type: Concept
title: Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains — Identity, ledgers, caps and the at-rest lane
description: Part 2 of 5 of Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains: Lane-fork admission notes; Run identity, ledgers and the async contract; Caps and the bounded retry; The at-rest job lane (live execution); The at-rest runner, and the two lifts
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains — Identity, ledgers, caps and the at-rest lane

Part 2 of 5 of [Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains](spec-pipeline-execution.md) · previous: [Two engines, their triggers and the execution lanes](spec-pipeline-execution-engines-lanes.md) · next: [The scratch lane — Pipeline test run](spec-pipeline-execution-scratch-lane.md).

## Execution lanes (continued)

### Identity and status, per lane

(Supersedes the two-lane table that lived in
[consignment-status-flow](architecture-elt.md), which still owns the mechanisms —
gauges, ledgers, quarantine, audit drill-down.)

| | Ingest lanes (flat + graph fork) | At-rest job lane | Scratch lane | Parked |
|---|---|---|---|---|
| Identity | `batchId` = `TS_slug_seq`; `ConsignmentManifest` authoritative per file | `runId`; `batchId` on provenance rows | none durable — the scratch root is deleted | the parked `batchId` (the drain resumes it) |
| Terminal status | `SUCCESS`/`FAILED`/`EMPTY` + per-file `QUARANTINED_*` → three CSV ledgers + `CommitLog` (`ConsignmentAuditWriter`) | `JobRun` → `DbJobRunStore` | the `PipelineRunResult` response | `PARKED` (neither committed nor failed) until drained |
| Per-step counts | `ConsignmentIngestor.recordProvenance` → `parse`/`sink` rows (SUCCESS only) | `PipelineExecutor` records one row per node, flushed with the commit | `relations[]` in the response (seeded sample) | branch commits in `BranchCommitLog` (kept as the drain's resume record) |
| Step Receipts (X5) | flat: three phases (`parse`, `transform`, `sink:<n>` per destination); graph fork: the two phases then one per walked Step — every status, flushed at the status-final point (L3) | one per walked Step (seeds and sinks included) → `consignment_steps`, flushed in a `finally`, success or failure | none | the parked sink `PARKED` with a `park` pointer; then one `drain` receipt per parked sink (L3) |
| Live position | `IngestProgress` (which FILE) + `StepProgress` (which STEP) | `StepProgress` (which node of the walk) | n/a (bounded, synchronous) | n/a (durably at rest) |
| Terminal event | `ConsignmentEvent` on the sync `ConsignmentEventBus` **and** the `pipeline.batch.committed\|failed` Signal | `ConsignmentEvent` published by `PipelineJobRunner` on SUCCESS; on failure the `pipeline.batch.failed` Signal alone (X5 D9 = c) | none | `pipeline.batch.parked` Signal; the drain's commit emits the ordinary terminal event |

Both provenance-writing lanes share **one** `DbProvenanceStore` per space (`ProvenanceStores` —
DuckDB is single-writer). Default-off (`-Dprovenance.backend=duckdb`).

**Step Receipts on the at-rest job lane (X5 L1, 2026-11-17).** `PipelineJobRunner` walks the graph with a
`StepReceiptRecorder` — a `PipelineExecutor.WalkObserver` that forwards every count to the provenance collector it
wraps, so the provenance rows and the conservation alert are unchanged — and writes the Consignment's receipts to
the default-ON registry (`consignment_steps`, [db-layer §3.9-a](../engine/spec-db-layer.md)) **in the run's `finally`**, once,
success or failure. That is the point: a failed run's provenance rows are lost (they are written after the walk
returns), its receipts are not. Lane `job`, `runId` = the job run, `seq` = the node's topological index.

- **What a receipt holds:** rows in (a seed's own count; a sink's consumed count; else the sum over its live inbound
  edges), rows per outlet named canonically (`PipelineRel.canonical`: `dropped` → `reject:dropped`, so either
  spelling of the reject relations reads the same), a 12-hex fingerprint of the main relation's columns, and for an
  on-disk sink an `outputs` pointer (the store, and how many files this run wrote to it). A dry run's receipts are
  `simulated` and point at nothing.
- **Failure:** the Step that threw is `FAILED` with a code (`STEP_FAILED` / `STEP_TIMEOUT` / `STEP_DISABLED` from a
  contributed Step's `StepFailure`, else `SQL_ERROR` / `IO_ERROR` from the cause, else `STEP_FAILED`; the cause chain
  is walked by identity, at most 32 deep, so a cyclic chain cannot hang the walk) and the message; every Step the
  walk would still have run is `NOT_REACHED`. A throw while a node is being worked on before it starts (a seed's or
  a sink's row count) is that node's failure. A sink is timed and judged at its branch commit, and is `OK` only once
  the coordinator has recorded its branch durable: a failed write or a failed branch record is the sink's `FAILED`.
  A branch a replay finds already durable is `OK`. A throw between Steps (the "no sink branches" refusal, the source
  finalisation) is no Step's: every receipt stays as it was and the run's own record carries the failure. A code a
  contributed Step invents is mapped onto the closed set (`STEP_FAILED`, the raw code at the head of the message).
  The run still fails exactly as before.
- **Without a receipt observer** (`WalkObserver.NONE`, a plain `ProvenanceCollector`: every pre-X5 caller, and the
  `ConsignmentGraphRunner.run` overloads that take none) the walk's Step bookkeeping is inert — no schema probe, no
  diagnosis, nothing on failure.
- **Conservation:** after a completed walk, each `ConservationCheck` imbalance is written onto that Step's `diag`
  (`CONSERVATION_LOSS` / `CONSERVATION_AMPLIFICATION`, with `in` / `out` counts) whether or not the provenance store
  is on; the `PIPELINE_CONSERVATION_IMBALANCE` event is still raised only with the provenance store, as before.
- **A failed run raises `pipeline.batch.failed` ALONE (X5 D9 = c, L3, 2026-11-17).** Never a `ConsignmentEvent`:
  `JobService.mirrorPipelineCommit` turns every such event into a `pipeline.commit` Signal, so `on_signal:
  pipeline.commit` Jobs see no change. `PipelineConsignmentSignal.emitJobFailed`: WARN, subject the Pipeline (id),
  source the Job, correlation the Consignment; payload `dryRun`, `status: FAILED`, `durationMs`, `error` (quoted
  values fingerprinted, at most 240 chars) and — when a Step owns the failure — `failedStep` / `code` from its FAILED
  receipt (none for a refusal before the walk — a missing pipeline, a jail error — which raises it too; subject then
  the authored `pipeline:`, else the Job). **Exactly one per Run that ENDS FAILED** (L3 review): each attempt hands
  the Signal it would raise to its `RunContext` (a retry replaces or clears it) and `JobService` raises it after
  recording the Run, only when the recorded status is `FAILED` — so a CANCELLED Run (a cancel, a switched-off module,
  a closing Space, `interruptRuns`) and an attempt a retry recovered raise nothing; a Run whose every attempt failed
  raises one, with the final attempt's facts. A Job run directly (the CLI's standalone context) raises none.
  OpsMonitor's `batch_rerun` ignores it (source `job:`): a job run recovers by its own `retries`. Pinned by
  `PipelineJobBatchFailedSignalTest` (failed, recovered retry, every attempt failed, cancelled, refused) and
  `OpsMonitorTest.ignoresAFailedJobRunsBatchFailedSignal`.
- **Never store values (L3 review):** `StepReceipt.Diagnostics` scrubs its message in its constructor — the one door
  every diagnosis enters by, on every lane — with `FailureText.scrub`, the scrub the failure Signals use: a quoted
  literal or echoed line becomes `<redacted>` (no Space salt is known there), while a text the ingest lane already
  scrubbed under its Space keeps its `<fp:…>` tokens (`FailureText.scrub` is idempotent on its own tokens, so a
  read-back changes nothing). Pinned on the three paths: `RouteIngestEndToEndTest` (graph fork, drain) and
  `PipelineJobRunnerTest.aFailedStepsQuotedValueNeverReachesTheReceiptTable`, each reading the raw column.

**Step Receipts on the ingest lanes, park and drain (X5 L3, 2026-11-17).** The flat lane runs no Steps, so it is
told in PHASES; the graph fork tells the same phases and then every Step it walks. Collected while the pass runs in
an `IngestTrail` carried in the CALL CONTEXT (`IngestTrail.CURRENT`, a `ScopedValue` that
`ConsignmentIngestor.process` binds around exactly one pass — never a map keyed by the Consignment id, which two
Pipelines, Spaces or attempts can mint alike; a workbench test run binds a throwaway, and the binding ends with the
pass whatever it throws) and flushed once at the status-final point — **for every status** (SUCCESS,
EMPTY, FAILED, PARKED) — best effort, never changing the outcome.

- **Phases:** `parse` (`in` = rows read + sidecar rejects; `data` = rows read, `reject:unmatched` = the parser's
  sidecar rows and a `rejects` pointer counting the members that have one; `castFailures` in `diag.counts`;
  `LANE_FLAT` with the reason when the write stayed flat — the kill switch by name, else `flatReason`),
  `transform` (`in` = rows meeting the Decision Rules; outlets `data` = what went on, plus one per rule that moved
  rows — **`route:<rule>`** for a route, `reject:quarantined` / `reject:dropped` for a quarantine / drop, one count
  per matched row, from `DecisionRuleApplier.Result.moved`), then on the flat lane one **`sink:<n>`** per `sinks[]`
  entry (`n` = the 0-based index, the lift's `__d<n>`; `in` = rows written there, an `outputs` pointer with its
  file count). An `EMPTY` pass has `parse` alone. Kind `phase`; `ms` from the phase marks.
- **Graph fork:** `ConsignmentGraphRunner.run(…, observer)` walks with a `StepReceiptRecorder` (lane
  `ingest-graph`); its receipts reach the trail in a `finally`, so a throwing walk keeps them. Each sink gets an
  `outputs` pointer (files that branch wrote). A Consignment written in several calls (chunks, segments) merges a
  Step met again into one receipt (rows summed, worst outcome kept). The phases are told on `ingest-graph` when every
  write took the fork, else on `ingest-flat`; each lane numbers its own `seq`.
- **Identity:** `runId` = the ingest Run — the `run_id` its `consignment_outputs` rows carry — not `''` (a plan §2.5
  deviation, below). A dry run's receipts are `simulated`, point at nothing, and are the Consignment's (the suffixed
  dry pass is re-keyed); a workbench test run leaves none.
- **Failure (plan §3.4):** a pass that failed names the Step it failed at — a walked Step that threw (its own
  diagnosis: `SQL_ERROR` / `IO_ERROR` / `STEP_FAILED`…), else the furthest phase it reached (`STEP_FAILED`, the
  scrubbed error text), or on the flat write tail the destination being written — FAILED, everything after it
  NOT_REACHED. A failure no Step owns (the commit tail after a pass that succeeded, a park, a lease loss, a lane
  refusal between phases) marks nothing FAILED. ⚠ On the native and plugin readers parse and transform are one
  statement, so a failure before the write tail reads `parse`; the plugin lane's generation mode writes outside the
  fork, so its sink counts come from the outcome and a failure there also reads `parse`.
- **`pipeline.batch.failed` carries the same `failedStep` / `code`** (`PipelineConsignmentSignal.attributing` binds
  them around the audit flush; the `ConsignmentEvent` record itself is unchanged). Absent when no Step owns it.
- **Park:** the parked sink is `PARKED`, `in` = the rows on its live inbound edge, pointer `park` with `path` =
  `parked/<consignment>__<step>.parquet` under `dirs.backup`; the live branches commit as OK.
- **Drain** (`DrainCommand`, the body of `POST /runs/{name}/drain`): one lane-`drain` receipt per parked sink under
  the drain's own Run (also passed to the commit tail, so its registry rows carry the same `run_id`): OK with the
  rows and files it wrote, FAILED for the write that threw, NOT_REACHED after it; a sink already durable from an
  interrupted drain is OK with nothing written. Recorded in a `finally`. The park receipt stays: receipts are never
  rewritten.
- Pinned by `FlatVsGraphLaneParityTest.bothLanesLeaveTheSameSinkRowsOnTheirStepReceiptTrails`,
  `RouteIngestEndToEndTest.aStepThatThrowsOnTheGraphForkIsNamedOnItsReceiptAndOnTheFailedSignal` /
  `aParkedConsignmentsTrailShowsTheParkThenTheDrain`, `DecisionRuleWiringTest.theTransformReceiptCountsWhatEachRuleMovedAndWhatWentOn`;
  the trail's isolation by `FlatVsGraphLaneParityTest.twoConcurrentSameIdConsignmentsInDifferentPipelinesKeepSeparateTrails`
  and `aWorkbenchTestRunNeverTouchesTheTrailOfThePassAroundIt`.
- **Deviation from plan §2.5 item 6 / §3.2:** ingest receipts carry the ingest Run id, not `''`. The plan's premise
  ("ingest-lane receipts have no run id") was wrong — `ConsignmentIngestor.process` has one — and with `''` a retried
  Consignment (same members, same id) would have its second attempt dropped by `ON CONFLICT DO NOTHING`, so the
  trail would show the first failure and never the success.
- ⚠ **Not built:** nothing serves the receipts over HTTP yet (L4, `GET /consignments/{consignmentId}`); a drain
  failure raises no Signal (unchanged — the route answers the error).

**Across the lanes (X2, 2026-09-02):** the at-rest lane records which ingest-lane Consignments it READ
— `inspecto_job_run_sources` beside the run row, fed from the files the Consignment selector kept and
mapped through the default-ON `consignment_outputs` registry, never from the rows (ordinary output files
carry no per-row batch id). `GET /jobs/runs/{runId}` → `derivedFrom[]`; `GET /runs/{name}/outputs` →
`derivedRuns[]`. Absent when the registry or run store is off — unknown is not empty. Schema and the
three decisions behind it: [db-layer §3.5](../engine/spec-db-layer.md).

### The Stage-2 chain is a job, not a sixth lane

A flat `*_pipeline.toon` carrying `steps:` + `output_store:` arms on the *declared promise* that a
`pipeline_config:` job exists to run the chain over the landed store — `output_store:` does not create
the job. 🔴 Author the chain, skip the job, and the pipeline ingests forever while the transform never
runs. Two closures (2026-09-01): `pipeline_config` is a declared parameter of the `pipeline` Job Type,
and `SchedulerAuditTask` reports the orphan. Since 2026-09-01 the check is **default-on in every space** (`JobService.auditOrphanOutputStores`, kill switch `-Djobs.orphan.audit=false`), exactly as *Recovery affordances per lane* above says. It *used* to fire only from a `scheduler_audit` maintenance job
(`spaces/demo/config/jobs/scheduler_audit_job.toon` ships one; a space without it has no orphan
detection). Full detail: [stage1-architecture §Step 3](architecture-elt.md).

### Naming

The Batch→Consignment rename shipped as one commit (`ff33246a`): `ConsignmentIngestor` (🔴 **not**
`ConsignmentProcessor` — that name is the third-party post-sync SPI), `ConsignmentIngestStrategy`,
`ConsignmentGraphRunner`, `ConsignmentEvent`/`ConsignmentEventBus`, `ConsignmentManifest`,
`ConsignmentAuditWriter`. ⚠ Wire and persisted spellings are deliberately unchanged (BACKLOG §4):
the `batch_id` ledger columns keep their read-alias, `GET /runs/{n}/batches` keeps its path, the
`__batch_id` reference column and the `batch_id` job key became `__consignment_id` / `consignment_id` in 4.0.0
(D-12, legacy reads kept), and the
`DbProvenanceStore`/`DbStatusStore` DDL columns await a data migration.

## Lane-fork admission notes

The lane fork itself — what admits a write to the graph lane, the `-Dingest.lane=auto|graph|flat` flag, the
decision-rule and scratch-dir rules, the engagement predicate and the parity test — is owned by
[spec-ingest.md](spec-ingest-branch-aware.md#11-branch-aware-ingest--route-on-the-poll-driven-path). This section keeps the execution-side notes the PIP spec carried.

✅ **The ELT amendment §6 step-2 parity gate is MET (2026-09-16, `GRAPH-LANE-RULE-ROUTED-1`, `b6b90fa2c`):** the whole suite
under `-Dingest.lane=graph` passed with zero "cannot carry pipeline" refusals, once rule-routed writes rode the
graph lane (`DecisionRuleApplier` runs above the lane fork, so both lanes see the same remainder). The root pom
carries `<ingest.lane>auto</ingest.lane>` and hands it to surefire, so `mvn -Dingest.lane=graph test` runs the
WHOLE suite with the flat lane disabled — the gate as D-2 wrote it; re-run it after each lane change rather
than reading a projection round-trip test as parity. (The first run that day was NOT met: 13 `inspecto-engine`
tests refused, on the multi-schema sink count and on rule-routed rows; both gaps shipped the same day.) The
rule-routed gap looked representational (`PipelineLift` never sees a Decision Rule), but no graph node was
needed: the routed outputs only had to be merged into the graph lane's written list. Routing combined with
route branches stays refused on both lanes. (Routing with a multi-destination `sinks[]` is carried since
2026-10-06 — see [Part III](spec-sinks.md) below.)

### A multi-schema write is admitted PER SEGMENT (2026-09-16)

A `segments:` batch reaches the write path **once per segment** (`UnionModeIngester`): each call materialises
that segment's `transformed_<KEY>` table and writes under `database/<segKey>`. So the lane fork asks its
admission question about the sub-chain that call actually writes — `map_<segKey> → sink_<segKey>` — and the
write stays **one seed feeding one sink**, exactly as for a single-schema batch. `-Dingest.lane=auto` diverts
these writes to the graph lane by default (the operator's call: real production exposure over a path only the
gate exercises).

🔴 **Two readings of this gap were wrong before the code was written, and both are worth keeping.** (1) The
refusal *states* an arity — *sink count (3) differs from sinks[] (1)* — which reads as a count to relax; it is
not. (2) The next reading, that the seeding contract is structurally violated because a per-schema lift gives
each branch its own map, implied a multi-seed walk. Also wrong: the caller had **already** decomposed the batch
per segment, so no multi-seed is needed. What was actually broken is narrower than either: **the admission
asked at pipeline granularity while the caller works at segment granularity.** The lesson generalises — when a
refusal message names a quantity, check which caller's granularity that quantity was measured at.

⚠ `writeScope` is overloaded across the four callers — `""` whole-batch, the chunk base name when chunked,
the segment key only in the per-segment loop — so a scope counts as a segment key only when the config
declares a segment by that name. A `schemas[]` selector batch's schema therefore travels in its OWN argument
(`selectedTable`, 2026-09-24) rather than in the scope.

⚠ **`segments:` is parsed only when `processing.ingester:` is also set.** Without it a multi-schema config
lifts as SINGLE-schema (a linear `acq → parse → map → sink`) and quietly proves nothing — it cost the first
spike fixture of this work.

⚠ The executor was never the obstacle: `PipelineExecutor.execute` has always taken a **map** of seeds
(multi-source, T32 Phase C) and walks per-schema trees when given them. `ConsignmentGraphRunner.engages` /
`dataFedSinkCount` still report *false* / *1* for such a graph — they gate **route** pipelines and treat
per-schema dispatch as trunk, which is deliberate and not in conflict with the above.

The reason-for-flat enumeration is the most operator-useful thing on this path: an authored route that does
not engage, a decision rule that routed rows, no scratch directory, a destination-count mismatch, a
non-projection node between projection and write, or a sink fed through another node.

## Run identity, ledgers and the async contract

**Run ⊇ Consignment ⊇ File.** Identity is the run-and-consignment pair, because the Run is the *attempt* —
so a reprocess is a new Run over the same Consignment. Intrinsic and inherited file status are deliberately
separate enums — see [architecture-elt.md](architecture-elt.md).

**The commit log is one append-only synced file** and its header carries no run timestamp: it answers *did
this Consignment finish*, and it is distinct from the per-run audit. There is no day column.

**The async contract is the same shape on both engines**: a trigger returns 202 with a run id and a
location header, and the caller polls. An idempotency key is cached on method, path and key for ten minutes
with a thousand-entry cap, only for non-server-error statuses, and a replay is flagged in the response
headers.

⚠ **The at-rest lane's always-on terminal record is the run ledger CSV, not the database projection.** The
lane table above names `DbJobRunStore`, which is **default-off**.

## Caps and the bounded retry

**The resource-cap pair ships on.** The Run bound is on by default in code (four — see
[spec-consignments.md](spec-consignments.md#part-b--concurrency-the-four-layer-hierarchy)); the memory limit has a **code default** since 2026-09-26
(GAP-4: 40 % of JVM-visible RAM ÷ a fixed 4, floored at 1 GiB — `DuckDbUtil.defaultMemoryLimit`; the
measurement behind it is in [../engine/spec-duckdb.md](../engine/spec-duckdb.md)). Precedence for the limit is per-pipeline, then
server-installed, then a bootstrap flag, then that code default (`DuckDbUtil.memoryLimit` never returns null;
configure `80%` to get the database's own behaviour back). Preview and dry-run connections are **deliberately
exempt** — they run over bounded samples. Total exposure is the limit times the concurrent Run count, which is exactly
why an unbounded Run count would make any per-instance cap meaningless. The two standing refusals (a cap of
memory ÷ live concurrency; any temporary-directory-size default) are in [plan-elt.md](plan-elt.md) §6.

**The commit retry is bounded** (default five attempts, `CommitRetry`; the routes, the per-pipeline
`processing.retry` block and the record-level replay are under *Execution lanes* above). What it fixed:
**the retry already existed** — the next poll cycle re-encountered the files — and it was **unbounded**, so
a poison Consignment failed every cycle forever. 🔴 **There is still no transient-versus-fatal classifier** —
treating a database lock as the trigger *"was aspirational"*; failure is recorded on any commit or park
failure regardless of cause. A zero setting (`-Dingest.retry.max=0`, or `processing.retry.max_attempts: 0`)
restores the pre-bound, unbounded behaviour.

⚠ **The retry does not cover a crash mid-archive**, which relies on idempotent overwrite instead: the
sidecar records a *returned* failure, and a crash returns nothing.

## The at-rest job lane (live execution)

An authored Pipeline is **job-style**: it reads data at rest (`source_store`), runs `transform.*` nodes, and
writes to a sink store. It is hosted as a [`JobType.PIPELINE`](../processor/spec-jobs.md) job on the existing
`JobService` scheduler (cron / `on_pipeline` / manual). This is the **at-rest job lane** — for how it sits
beside the other four lanes see *Execution lanes* above. Design of record (T32 plan,
shipped 2026-06-18/19):
[`flow-live-execution-plan.md`](../../../../archived-documents/plans-archive/flow-live-execution-plan.md).

### `PipelineJobRunner.run()`

`PipelineJobRunner` (`platform/inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`):

1. Load the `PipelineGraph` from `PipelineStore`.
2. `seedsOf(g)` — find every node with a non-blank `source_store` cfg key; **throws if none** (zero seeds is
   a hard error). The Phase-A MVP allowed exactly one seed; Phase C relaxed it to **≥1** (a downstream
   `transform.merge` joins/unions multiple sources).
3. For each seed, `SourceStoreReader.registerView(…)` registers a DuckDB view over the at-rest Parquet/CSV.
4. `PipelineExecutor.execute(…)` walks the graph (see [design](architecture-elt.md)).
5. `PartitionSinkWriter` (the `SinkWriter` impl) delegates each sink write to
   [`PartitionWriter`](spec-sinks.md#writers-store-layout-and-destinations).
6. Optionally collect provenance (`DbProvenanceStore`, one row per node — shared with the ingest lane
   through `ProvenanceStores` since 2026-08-12) and run `ConservationCheck` → emit
   `PIPELINE_CONSERVATION_IMBALANCE` events when records are lost at a non-amplifying node. The
   executor also reports its live position per node (`StepProgress`, cleared in a `finally`) — see
   [consignment status flow](architecture-elt.md).
7. Advance `PipelineWatermarkStore` per source (opt-in incremental mode); register `sink.view` outputs as durable
   `ViewDefinition`s.
8. Publish a `ConsignmentEvent`, return a `JobResult`.

Config (`*_job.toon`): `type: pipeline`, plus **exactly one of the two graph sources** (mutually
exclusive; `PipelineJobRunner` enforces "pick one" at run time — ⛔ neither is `required` in the
descriptor, see [stage1-architecture §Step 3](architecture-elt.md)):

- `pipeline: <authored-pipeline-id>` — an authored graph from `PipelineStore` (canonical since the
  Tier 3 rename, vocabulary plan §4 — the pre-rename `flow:` key still reads, dual-read only, never
  written again);
- `pipeline_config: <path/to/*_pipeline.toon>` — a **flat file's Stage-2 `steps:` chain**, lifted at
  run time by `PipelineLift.stageTwo` into `source_store(landed) → chain → sink.persistent(output_store)`
  (the flat file stays the truth). A `transform.join` here resolves its Reference Dataset through the
  shared `ReferenceReader`, identically to Stage-2 enrichment.


### Config-less ad-hoc run (2026-07-18)

`POST /pipelines/authored/{id}/trigger` fires an authored Pipeline **once, with no `*_job.toon`**:
`JobService.triggerFlowRun(flowId, actor)` builds a synthetic, **never-registered** `type: pipeline` config
and runs it through the exact registered-job lifecycle — deletion-fence `runningFlows()` tracking, per-Pipeline-id
non-overlap (a re-fire while running records `SKIPPED`), the durable run ledger, and `GET /jobs/runs/{runId}`
polling — so `GET /jobs` stays config-only while `GET /jobs/{flowId}/runs` serves the ad-hoc history (runs are
recorded under the Pipeline id — `flowId` in code; the chain `ConsignmentEvent` is likewise published under it, so downstream
`on_pipeline:` consumers key on it, not on a job name). Response mirrors `POST /jobs/{name}/trigger`:
`202 {runId, pipeline, status}` + `Location`. **Deliberately `…/trigger`, not `…/run`** — `…/run` is the
scratch lane's **simulate** verb, `…/trigger` the **operate**; see *Execution lanes* and *The scratch
lane* on this page.
Prefer a persisted `*_job.toon` when a run needs `data_dir`/`consignment_id` overrides (`batch_id` before 4.0.0), a schedule,
or chaining; the ad-hoc route takes no params.

## The at-rest runner, and the two lifts

A pipeline-type Job reads a config and lifts only the **second stage**, then runs that chain over the
already-landed store (graph-source and seed rules above).

⚠ **Two lifts exist and both are live. Neither is stale, and confusing them is the area's classic error:**

| Lift | Produces | Callers |
|---|---|---|
| the **full** lift | the ingest topology | the lane admission, the processor, the drain command, and the editor's round trip |
| the **stage-two** lift | the at-rest remainder only | the pipeline job runner, and nothing else |

The note that "stage-two is the wrong lift" was a **scoping correction to one plan draft**, which had
proposed computing the *ingest* fork check from the stage-two lift — impossible, because it knows nothing
of ingest topology or route branches. It was corrected to the full lift, which is what the admission uses
today. Each belongs where it is called.

The stage-two lift's refusal set is complete and named: no chain, no output store, a multi-schema selector
(one seed cannot pick a store), a route step (route's home is the ingest lane), and a legacy pre-map filter
carrying pre-parse keys.

## Changes

- 2026-09-09: the orphan-output check corrected — the page stated its pre-2026-09-01 history (a `scheduler_audit` job only) as current.
