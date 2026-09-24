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
claims beside `registry.supersede`** so a windowed dedup re-admits the redone rows), and since
2026-09-25 `POST /runs/{name}/replay-rejects` (one file's rejected records only — X4, below); parked —
`POST /runs/{name}/drain`; at-rest job lane — `POST /jobs/runs/{runId}/replay` (canOperateRuns;
re-fires the job through the normal lifecycle with its configured defaults — the run ledger persists
no per-run params, and the response `note` says so; the new run's `trigger` field carries
`replay:<originalRunId>` so the linkage is followable; 409 while the job is running or no longer
registered). **Bounded COMMIT retry (X1, 2026-09-02):** the ingest lane's retry is the next cycle's
re-encounter of a FAILED Consignment's files; `CommitRetry` bounds it — durable per-file attempt sidecars
under `<status_dir>/retries/`, exponential backoff honoured on the run path only (a waiting file is still
pending), exhaustion ⇒ quarantine under `retry_exhausted` + one CRITICAL `pipeline.batch.retry_exhausted`
Signal. ⚠ Grounding refuted the plan's queue: nothing on the ingest lane classifies transient vs fatal
(the catch sites collapse every read failure into a permanent `unreadable` quarantine, duplicated across
four strategies), "DuckDB lock" as a trigger was aspirational, and the real gap was the OPPOSITE of the
plan's fear — poison never stopped. Member-level `unreadable` stays as it is. Also
lane-wide since 2026-09-01: the Stage-2 **orphan-`output_store:` check is default-on** in every
space (transition-debounced signal; `-Djobs.orphan.audit=false` to disable) — see
[stage1-architecture](../engine/stage1-architecture.md) §Step 3.

**The COMMIT retry affordance (X1 deferrals, 2026-09-25; decisions Q1–Q4 in
[retry-affordance-design](../../../archived-documents/plans-archive/retry-affordance-design.md)).** Three routes over
`CommitRetry`, **per pipeline** (Q4) and addressed by the **poll-relative FILE path, never `batchId`**
(minted per cycle, so a batch-keyed route would be dead by the second cycle):
`GET /runs/{name}/retries` (open read; bounded at 500 with `truncated` + the true `total`),
`POST /runs/{name}/retries/retry-now {file}` and `POST /runs/{name}/retries/cancel {file}` (both
`canOperateRuns`, string literal). As built:

* **retry-now clears `nextRetryAt` ONLY — the attempt count is kept** (Q2), and the response's `note` says
  how many attempts remain before `retry_exhausted`; resetting attempts would make a poison file immortal.
* **cancel = quarantine NOW under `retry_cancelled`** (Q1) and spend the record — the same "fate decided"
  end as exhaustion. ⛔ It never merely drops the sidecar: with no sidecar the file is retried *unboundedly*
  (the trap the old "delete the sidecar by hand" workaround fell into).
* **The engine half never throws.** `CommitRetry.list/retryNow/cancel` return a `Listing` / `Outcome`
  (`RESCHEDULED`, `CANCELLED`, `NO_RETRY_STATE`, `NO_RECORD`, `NOT_IN_INBOX`, `ALREADY_QUARANTINED`, `BUSY`,
  `FAILED`); an unreadable sidecar is listed with `readable: false`, never a 500. The route maps the
  non-acting outcomes to **404** (no record / not in the inbox) and **409** (no retry state, already
  quarantined — naming the reason directory found in the quarantine tree — mid-cycle, or could not act),
  each with the reason, so nothing appears to act that did not.
* **`keepsRetryState: false` ≠ an empty list.** No `dirs.status_dir` ⇒ no records at all ⇒ unbounded retry;
  the list says so in a `note` rather than returning a reassuring `[]`.
* **Serialised against the poll**: `CollectorService.commitRetryAct` takes the pipeline's run claim with
  `tryAcquire` (never blocks) — a pipeline mid-cycle answers `BUSY` (409) and nothing is attempted, so a
  cancel can never move a file a cycle is ingesting.
* The per-file `CommitRetry.clear(File, cfg)` sits beside `clear(Consignment, cfg)`, which now loops over it;
  `CommitRetry.inboxFile(cfg, rel)` is the path jail (blank, absolute or escaping ⇒ `null` ⇒ 403).
* **UI (Q3) shipped 2026-09-25** as the Run Detail **Commit retries** tab — see
  [run-detail](../../frontend/features/run-detail.md) § *Commit retries*. The routes came first, as Q3 decided.

**Per-pipeline `processing.retry` (X1 deferral, 2026-09-25).** The cap and backoff are now per pipeline:

```
processing:
  retry:
    max_attempts: 3        # 0 = unbounded for THIS pipeline
    initial_backoff: 30s   # Collector duration grammar: bare seconds or N s|m|h|d
    max_backoff: 2h
```

Every key is independently optional and **inherits its `-Dingest.retry.*` global when unset**
(`max` 5, `backoff.initialMs` 60000, `backoff.maxMs` 3600000) — so an absent block is today's behaviour byte
for byte. The `processing.intake` posture exactly: `PipelineConfig.commitRetry()` carries only what the
author STATED (`CommitRetryPolicy`, nullable fields, `null` when the block is absent), and the merge happens
in `CommitRetry.policy(cfg)`, which `recordFailure`, `due`, the list route and retry-now's `note` all read.
⚠ **No spec defaults, deliberately** — the config pane seeds spec defaults into the saved file, which would
pin a pipeline off the live global. Gates: declared in `ConfigSpecs.pipeline()` (so `AcceptedConfigKeys`
accepts the block as `spec`-declared — `PARSER_ONLY` may only shrink, so it is not listed there);
`ConfigSafetyValidator` refuses `max_attempts` outside `[0, 1000]` and a negative or non-duration backoff
(**422** on `/config/write`); the parser refuses the same at load, naming the key. Editor: `retry__*`
attributes on `sink.persistent` nest to the node's `retry` map and lower to `processing.retry:`
(`PipelineEditable`, beside `intake`), pinned end-to-end by `NodeConfigNameContractTest`.

**Record-level replay evidence (X4, 2026-09-24/25).** The ingest lane's `?dryRun=true` runs the real
ingest pass over member copies in a deleted scratch root (`PipelineTestRun.dryIngest`, contained;
zero side effects pinned by `FlatLaneDryRunParseTest`) and reports per member a `MemberOutcome`:
the member's **kind** of end (`WOULD_LAND` / `REJECTED` / `SKIPPED` / `FAULT`) and, since 2026-09-25,
its **rejected records** — `rejects: [RejectedRecord(line, reason)]`, capped at
`PipelineTestRun.REJECTS_PER_MEMBER` (100) in file order, plus an uncapped `rejectTotal`. They are read
from the reject sidecar `<errors>/<file>_errors.csv` **before** the scratch root is deleted; the offset
is the sidecar's own `line_number` column (both CSV ingesters already wrote one — nothing was added to
the sidecar) and the reason its `reason` column, located by header name because the native ingester
has a `columns` field between them (`CsvIngester.rejectSidecar` is the one path). ⚠ The sidecar has
**two homes**: `errors/` for a member accepted while losing rows, and the quarantine tree for a member
rejected whole as a field mismatch (`QuarantineManager` moves it with the file) — the reader checks
both, and a mutant reading only `errors/` goes red. The raw line is deliberately **not** carried (reject
rows hold raw source data). Surface: `DryIngest.members()` and one `dry run: … rejected record(s), first
N of M: line L (reason); …` log line per member — the trigger's HTTP response (`202 + {runId,…}`) does
not carry it, so `docs/api/openapi-v1.json` is unchanged. Plugin decoders write no sidecar, so their
members report `rejectTotal = 0`. **Decided 2026-09-25 (operator):** the replay default is
**eject-and-continue**; this was its stated precondition.

**Record-level replay (X4, first slice, 2026-09-25).** ✅ **Eject-and-continue was already the live
behaviour** for a delimited file, on both CSV engines — nothing had to change to make it the default: a
record that fails the column count goes to the sidecar (`line_number`, `reason`, `raw_line`), the file's
other records land, and the file commits `SUCCESS` with `error_rows > 0` (pinned by
`RecordReplayTest.ejectAndContinueIsTheLiveBehaviour…`, `java` + `duckdb`). Two edges are not "eject":
a file with **zero** valid records is quarantined whole as `QUARANTINED_MISMATCH` (its sidecar moves with
it), and a failed type **coercion** nulls the value and KEEPS the row (counted in `cast_failures`, no
sidecar row). What was missing was only the replay, now `POST /runs/{name}/replay-rejects {file}`
(`canOperateRuns`) → `CollectorService.replayRejects` → `RecordReplay.replay`. Its choices:

- **Input = the sidecar's `raw_line`**, verbatim, in file order, written as a UTF-8 file of bare data lines
  into the poll root as `<stem>__replay_<sha8>.<ext>` and parsed with `PipelineConfig.forRecordReplay()`,
  which switches off every line-framing knob that described the ORIGINAL file (`skip_header_lines`,
  `has_header`, `skip_junk_lines`, `skip_tail_lines`, encoding, compression) and keeps everything that
  decides what a record means. Safe because fields map by selector index, never by header name. 🔴 This
  forced one fix: both ingesters used to rewrite an embedded `"` in `raw_line` to `'`, which on replay
  splits a quoted value into two columns and lands a **wrong row silently** (the mutant proves it); they now
  double it (`""`). Sidecars written before 2026-09-25 still carry the apostrophes.
- **Lane = the ordinary flat ingest over exactly that one file** — `CollectorProcessor.ingestCandidates`,
  the post-discovery half of `ingest` — so the replay is a real Consignment with the whole commit tail
  (outputs, manifest, backup, marker, audit, provenance, terminal event). Discovery is bypassed, so the name
  need not match `file_pattern`, and the schema is selected by the **original** file's name. It runs on the
  trigger pool under the pipeline's run claim, like a manual trigger, so no poll of it overlaps.
- **Attribution = a durable replay record** `<status_dir>/replays/<sidecar-sha256>.json`: `originalFile`,
  `sidecar`, `replayFile`, `batchId`, `status`, `outputRows`, `errorRows`, and `lines` — record *k* of the
  replay input is the original file's line `lines[k-1]`. The replayed rows' own lineage names the replay
  file; no per-row column was added.
- **Idempotence = that record, claimed by an atomic create before anything is written**, keyed on the
  sidecar's content hash: the same sidecar replayed again is **409** and lands nothing. A replay whose
  Consignment did not complete (`FAILED` or thrown) deletes its input and **releases** the claim so it can be
  retried; any other end keeps it. Records that are **still** rejected land in the replay input's OWN sidecar
  (`<stem>__replay_<sha8>_errors.csv` — in `errors/`, or in quarantine when none landed), which is a
  different sidecar and replayable in turn. **Abandoned claims (2026-09-25):** a completed record is
  never stale, but an **empty** claim (a replay that never wrote its record) is refused as *in progress* while
  younger than `RecordReplay.ABANDONED_CLAIM_AFTER` (30 min) and, once older, treated as a crash's leftover —
  its leftover input (and `.writing` temp) is deleted from the poll root and the claim re-taken atomically
  (pinned by three `RecordReplayTest` cases). ⚠ Residual: a crash in the few statements AFTER the replay
  Consignment committed but BEFORE its record was moved into place also leaves an empty claim, and
  reclaiming that one would land the records twice. ⚠ A whole-Consignment `reprocess` of the original rewrites the
  same sidecar with the same content, so its replay stays refused — correctly, since the earlier replay's
  rows were not superseded.
- **Refused (422):** a non-delimited frontend or plugin decoder, a sidecar row with no `raw_line`, and a
  sidecar whose row count reaches `rejects_limit` (it may be truncated).

⛔ **Not built — the per-pipeline `all_or_nothing` switch.** It is not a small key: the decision must be taken
per member **before** the write, and the single-member native path streams `read_csv → transform → COPY`
in one pass, so its reject count is known only after the rows have landed; the Java loop, the three native
streaming paths and the plugin lane would each need it. The nearest existing knob is
`csv_settings.ignore_errors: false` (native engine only), which FAILS the batch on the first bad row — a
retry-then-`retry_exhausted` end, not a whole-file quarantine. **Audit:** the replay is recorded by the
single `AuditTrail` seam every mutating route goes through (actor, capability `canOperateRuns`, IP), under
its own action `pipeline.rejects_replayed` since 2026-09-25 — before that it fell to the POST default and
read as `pipeline.created`; `reprocess` was already `pipeline.reprocessed` the same way. **SPA:** the
rejected-rows dialog on Run Detail carries the action since 2026-09-25 — see
[run-detail](../../frontend/features/run-detail.md). Still open: the dry run's per-record rejects on the HTTP
trigger response.

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
and `SchedulerAuditTask` reports the orphan. 🔴 **The following sentence was HISTORY stated as current and is corrected 2026-09-09** — since 2026-09-01 the check is **default-on in every space** (`JobService.auditOrphanOutputStores`, kill switch `-Djobs.orphan.audit=false`), exactly as this page says 34 lines above. It *used* to fire only from a `scheduler_audit` maintenance job
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
