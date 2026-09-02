---
type: Concept
title: Execution lanes
description: The single owner of "which lanes exist" — every way a Pipeline executes (ingest flat, ingest graph fork, at-rest job, scratch, parked-then-drained), what triggers each, the divert predicate, what runs, what it writes, and which concept owns the mechanism.
resource: inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java
tags: [pipeline-graph, execution, lanes, ingest, job, scratch, park, drain]
timestamp: 2026-09-01T00:00:00Z
---

# Execution lanes

One Pipeline definition can execute in **five distinct lanes**. Before this page, each lane's concept
re-explained the split in its own vocabulary (~6 framings across five files, none authoritative —
inventory finding D-6). This page is now the **single owner of "which lanes exist"**; the mechanism
concepts describe *how their lane works* and point here for the map.

Vocabulary (spec §13 D2, the token model): a Step receives a **Consignment token** and resolves data
by reference — edges never carry records. Every "what executes" cell below describes which code
resolves the token's data, not rows flowing along edges. *One note:* the runtime edge model itself
converges under Phase 7's major-bump window (D2's second half); the Batch→Consignment **rename** half
of Phase 7 shipped (`ff33246a`) with deliberate wire/persisted residuals — see *Naming* below.

## The lanes

| Lane | Triggered by | Divert / fork predicate | What executes | What it writes | Mechanism owner |
|---|---|---|---|---|---|
| **Ingest, flat** | the poll cycle (or `trigger:` schedule/event/manual) planning a Consignment over inbox files | the default — everything the graph fork refuses: no scratch dir, a node between map and sink, a decision rule that actually routed rows | `ConsignmentIngestor.process` → `CsvIngestStrategy` / `StreamingPluginIngestStrategy` → `DataTransformer` → `writeAndTrace`'s flat `sinks[]` loop (reference-version stamp + decision rules run here) | partitioned output per destination + the full commit tail: manifest, backup, **markers LAST**, three CSV ledgers, dedup ledger, watermark, `ConsignmentEvent` | [stage1-architecture](../engine/stage1-architecture.md) (the core path) · [branch-aware-ingest](../engine/branch-aware-ingest.md) (the fork rules) |
| **Ingest, graph fork** | the same poll-driven ingest — this is a fork *inside* `writeAndTrace`, not a separate entry point | `route:` pipeline: `ConsignmentGraphRunner.engages(PipelineLift.lift(cfg))` (counts **branches**, not sink nodes). Non-route: `ConsignmentIngestStrategy.graphLaneCarries(cfg)` — scratch dir configured, lifted sinks 1:1 with `cfg.sinks()`, every sink `data`-fed directly off the `transform.map` seed | `ConsignmentGraphRunner.run` drives `PipelineExecutor` over the `route → sinks` subtree, seeded **below parse/map** — the lane performs only the WRITE, never re-runs upstream nodes | each branch via `IngestSinkWriter` to its `database`-paired destination, committed through a durable per-Consignment `BranchCommitLog` under `dirs.temp`; then the SAME commit tail as flat (shared code, not a mirror) | [branch-aware-ingest](../engine/branch-aware-ingest.md) |
| **At-rest job** (incl. the Stage-2 `pipeline_config:` chain) | a `type: pipeline` Job — cron / `on_pipeline:` / manual, or the config-less `POST /pipelines/authored/{id}/trigger` | the graph source picks the flavor, **mutually exclusive, exactly one**: `pipeline:` (an authored graph; legacy `flow:` dual-read) or `pipeline_config:` (a flat file's `steps:` chain, lifted at run time by `PipelineLift.stageTwo` into `source_store(landed) → chain → sink.persistent(output_store)`) | `PipelineJobRunner` → the production `PipelineExecutor`/`RowShaper` over `source_store` views (`SourceStoreReader`); this is where `dedup`/`join`/`summarize` execute — both ingest lanes refuse them | sink stores via `PartitionSinkWriter` (self-registers §11.3), `JobRun` → `DbJobRunStore`, watermarks, `sink.view` `ViewDefinition`s, `ConsignmentEvent` | [live-execution](live-execution.md) · [stage1-architecture §Step 3](../engine/stage1-architecture.md) (the Stage-2-as-job split) |
| **Scratch** (dry-run + run-to-here) | `POST /pipelines/authored/{id}/run?to={nodeId}` (`canAuthorWorkbench` — the **simulate** verb, never `…/trigger`) and the editor's dry-run | none — always scratch, by construction: `PipelineTestRun` calls only the ingest half of `ConsignmentIngestor.process` (never commit/audit/provenance) over files **copied** into a scratch root; `PipelineExecutor.dryRun` bounds the walk to the target's ancestor closure | the real parse over picked inbox files + an in-memory `PipelineDryRun` walk (seed bounded to 1000 rows); a disabled node here is an in-memory **bypass** | **nothing in production** — scratch root only; returns `PipelineRunResult` | [pipeline-test-run](../engine/pipeline-test-run.md) |
| **Parked-then-drained** | not a fifth executor — the ingest graph fork's durable pause: the walk reaches a **disabled SINK** (via `processing.disabled_steps`) with a live inbound relation | armable only for a sink strictly inside an armed `route:` subtree (`StepDisableArming` refuses every other shape, at save and at `prepare()`) | park: `PipelineExecutor.ParkWriter` COPYs the branch relation to `dirs.backup()/parked/`, `ConsignmentIngestor.parkSource` records the manifest + `ParkedCommit` sidecar, status `PARKED`. Drain: `POST /runs/{name}/drain` → `DrainCommand` — **no re-walk**, registers the park tables and runs the real `finalizeSource` for the whole Consignment | park: park tables + sidecar + `pipeline.batch.parked` Signal, enabled branches already committed; drain: the ordinary commit tail over the union of sidecar outputs and its own | [step-park-drain](step-park-drain.md) |

Two boundary rules the table encodes, worth stating once:

- 🔴 **The two ingest lanes are one write, two mechanisms.** The graph fork replaces only the write
  segment of `writeAndTrace`; parse/map are never re-run and `dedup`/`join`/`summarize` between map and
  sink are refused on **both** — they execute only in the at-rest job lane (batch independence is the
  dividing rule, spec §7.1).
- 🔴 **`…/run` vs `…/trigger` is the scratch/production boundary.** `…/run` is the simulate (scratch
  lane), `…/trigger` the operate (at-rest job lane). The verbs must never be merged.

**Recovery affordances per lane (2026-09-02):** ingest lanes — `POST /runs/{name}/reprocess`
(whole-Consignment redo; refuses when an output was compacted away; **retracts the run's dedup-ledger
claims beside `registry.supersede`** so a windowed dedup re-admits the redone rows); parked —
`POST /runs/{name}/drain`; at-rest job lane — `POST /jobs/runs/{runId}/replay` (canOperateRuns;
re-fires the job through the normal lifecycle with its configured defaults — the run ledger persists
no per-run params, and the response `note` says so; the new run's `trigger` field carries
`replay:<originalRunId>` so the linkage is followable; 409 while the job is running or no longer
registered). The persistent retry queue stays a spec item
([`execution-residuals-plan.md`](../../../superpower/execution-residuals-plan.md) §X1). Also
lane-wide since 2026-09-01: the Stage-2 **orphan-`output_store:` check is default-on** in every
space (transition-debounced signal; `-Djobs.orphan.audit=false` to disable) — see
[stage1-architecture](../engine/stage1-architecture.md) §Step 3.

## Identity and status, per lane

(Supersedes the two-lane table that lived in
[consignment-status-flow](../engine/consignment-status-flow.md), which still owns the mechanisms —
gauges, ledgers, quarantine, audit drill-down.)

| | Ingest lanes (flat + graph fork) | At-rest job lane | Scratch lane | Parked |
|---|---|---|---|---|
| Identity | `batchId` = `TS_slug_seq`; `ConsignmentManifest` authoritative per file | `runId`; `batchId` on provenance rows | none durable — the scratch root is deleted | the parked `batchId` (the drain resumes it) |
| Terminal status | `SUCCESS`/`FAILED`/`EMPTY` + per-file `QUARANTINED_*` → three CSV ledgers + `CommitLog` (`ConsignmentAuditWriter`) | `JobRun` → `DbJobRunStore` | the `PipelineRunResult` response | `PARKED` (neither committed nor failed) until drained |
| Per-step counts | `ConsignmentIngestor.recordProvenance` → `parse`/`sink` rows (SUCCESS only) | `PipelineExecutor` records one row per node, flushed with the commit | `relations[]` in the response (seeded sample) | branch commits in `BranchCommitLog` (kept as the drain's resume record) |
| Live position | `IngestProgress` (which FILE) + `StepProgress` (which STEP) | `StepProgress` (which node of the walk) | n/a (bounded, synchronous) | n/a (durably at rest) |
| Terminal event | `ConsignmentEvent` on the sync `ConsignmentEventBus` **and** the `pipeline.batch.committed\|failed` Signal | `ConsignmentEvent` published by `PipelineJobRunner` | none | `pipeline.batch.parked` Signal; the drain's commit emits the ordinary terminal event |

Both provenance-writing lanes share **one** `DbProvenanceStore` per space (`ProvenanceStores` —
DuckDB is single-writer). Default-off (`-Dprovenance.backend=duckdb`).

**Across the lanes (X2, 2026-09-02):** the at-rest lane records which ingest-lane Consignments it READ
— `inspecto_job_run_sources` beside the run row, fed from the files the Consignment selector kept and
mapped through the default-ON `consignment_outputs` registry, never from the rows (ordinary output files
carry no per-row batch id). `GET /jobs/runs/{runId}` → `derivedFrom[]`; `GET /runs/{name}/outputs` →
`derivedRuns[]`. Absent when the registry or run store is off — unknown is not empty. Schema and the
three decisions behind it: [db-layer §3.5](../engine/db-layer.md).

## The Stage-2 chain is a job, not a sixth lane

A flat `*_pipeline.toon` carrying `steps:` + `output_store:` arms on the *declared promise* that a
`pipeline_config:` job exists to run the chain over the landed store — `output_store:` does not create
the job. 🔴 Author the chain, skip the job, and the pipeline ingests forever while the transform never
runs. Two closures (2026-09-01): `pipeline_config` is a declared parameter of the `pipeline` Job Type,
and `SchedulerAuditTask` reports the orphan — but ⚠ only from a `scheduler_audit` maintenance job
(`spaces/demo/config/jobs/scheduler_audit_job.toon` ships one; a space without it has no orphan
detection). Full detail: [stage1-architecture §Step 3](../engine/stage1-architecture.md).

## Naming

The Batch→Consignment rename shipped as one commit (`ff33246a`): `ConsignmentIngestor` (🔴 **not**
`ConsignmentProcessor` — that name is the third-party post-sync SPI), `ConsignmentIngestStrategy`,
`ConsignmentGraphRunner`, `ConsignmentEvent`/`ConsignmentEventBus`, `ConsignmentManifest`,
`ConsignmentAuditWriter`. ⚠ Wire and persisted spellings are deliberately unchanged (BACKLOG §4):
the `batch_id` ledger columns keep their read-alias, `GET /runs/{n}/batches` keeps its path, the
`__batch_id` output column and the `batch_id` `.toon` job key are deferred decisions, and the
`DbProvenanceStore`/`DbStatusStore` DDL columns await a data migration.
