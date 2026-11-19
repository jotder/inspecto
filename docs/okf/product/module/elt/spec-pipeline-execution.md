---
type: Concept
title: Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains
description: How a Pipeline runs: the two engines and their triggers, the execution lanes and the post-sync lane, run identity and the async contract, caps and the bounded retry, the at-rest job lane, the scratch test run, the whole-pipeline dry run, per-Step park and drain, failure and recovery per lane, post-sync step chains, and code pointers.
resource: platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
---

# Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. [spec-pipeline-model.md](spec-pipeline-model.md) · **spec-pipeline-execution.md** · [spec-sinks.md](spec-sinks.md)

**Contents**

- [Two engines and their triggers](#two-engines-and-their-triggers)
- [Execution lanes](#execution-lanes)
- [Lane-fork admission notes](#lane-fork-admission-notes)
- [Run identity, ledgers and the async contract](#run-identity-ledgers-and-the-async-contract)
- [Caps and the bounded retry](#caps-and-the-bounded-retry)
- [The at-rest job lane (live execution)](#the-at-rest-job-lane-live-execution)
- [The at-rest runner, and the two lifts](#the-at-rest-runner-and-the-two-lifts)
- [The scratch lane — Pipeline test run (run-to-here)](#the-scratch-lane--pipeline-test-run-run-to-here)
- [Whole-pipeline dry run (`PIPELINE-DRYRUN-1`, shipped 2026-09-20)](#whole-pipeline-dry-run-pipeline-dryrun-1-shipped-2026-09-20)
- [Per-Step `enabled` — park and drain](#per-step-enabled--park-and-drain)
- [Commit steps — the work a commit needs before it is done](#commit-steps--the-work-a-commit-needs-before-it-is-done)
- [Failure and recovery, per lane](#failure-and-recovery-per-lane)
- [Post-sync step chains (the open DAG)](#post-sync-step-chains-the-open-dag)
- [Code pointers](#code-pointers)

**What this covers.** What happens when a Pipeline actually runs. Two engines share one config vocabulary: the ingest engine moves
files from an inbox into stores, and the Job engine works over data already at rest. One Pipeline can run in
five lanes (two ingest lanes, an at-rest Job, a scratch test run, and a paused "parked" state), plus a
post-sync lane that derives tables after commit. This part says which lane does what, how each is triggered,
what each writes, how failures are retried or replayed, and how a test or dry run stays harmless.
How an author writes a Pipeline is the Pipeline model ([Part I](spec-pipeline-execution.md) above) and the
editor guide ([user-guide-elt.md](user-guide-elt.md)); the requirement of record is [plan-elt.md](plan-elt.md).

## Two engines and their triggers

This capability is **how work actually runs**, and the organising fact is that there is not one execution
engine here. There are two, triggered independently, sharing one config vocabulary:

> **The ingest engine** polls an inbox, plans Consignments and drives a Pipeline over files *in motion*.
> **The Job engine** fires on cron, on a commit, on a signal or by hand, and runs work over data *at
> rest* — including a Pipeline's own second stage, hosted as a Job.

The code names this split itself: one of its trigger enums is annotated *the two-scheduler split*. The
boundary is binding: in-motion and at-rest compose as **producer and consumer over a shared store, never by
nesting** — which is why sub-Pipelines and an `ingest` Job Type are design refusals ([plan-elt.md](plan-elt.md) §6).

### Two engines

| | Ingest engine | Job engine |
|---|---|---|
| Drives | a Pipeline over files in motion | anything over data at rest |
| Triggered by | the poll cycle, or an entry-node trigger | cron, a commit, a signal, or by hand |
| Unit of work | a **Consignment** | a **Run** of an Executable |
| Bounded by | its own semaphore, sized at construction | a hot-resizable permit holder, default four |
| Exclusion | a per-pipeline non-reentrant guard | a per-job lock that records a skip |
| Terminal record | three CSV ledgers plus the commit log | the run ledger, plus an optional database projection |

They meet at exactly one place: a commit publishes an event, and that event is what a downstream trigger
consumes. An authored Pipeline's *second* stage crosses the boundary in the other direction — it is hosted
as a Job, because a Job is the only thing that reads data already written.

How the two engines bound concurrency (the ingest run budget, the Run bound, the four-layer broker and stride
scheduling) is owned by [spec-consignments.md](spec-consignments.md#part-b--concurrency-the-four-layer-hierarchy) (Part II, Part B).

### Two trigger models

The entry-node trigger grammar is in [architecture-elt.md](architecture-elt.md); the Job trigger fields in
[../processor/spec-jobs.md](../processor/spec-jobs.md).

**Pipeline triggers** are declared on the entry node — six kinds (`PipelineTrigger.Kind`): an interval, a
cron expression, an event, manual, the default poll, or a **stream** (`{type: stream, records, max_wait}`, the
continuous lane, still on the loop scheduler; `ASSURE-PUSH-INGEST-1`). The event kind carries what it listens for — a **commit** from an upstream pipeline, or a
**dataset** write — and the two namespaces are explicitly fenced from each other so they cannot collide.
There is a coalescing window. **There is no catch-up.**

**Job triggers** are fields on the job config: cron, on-pipeline, on-signal with an optional guard, and
manual. **Catch-up is here**, and it replays exactly **one** missed fire at startup from the run ledger's
last-start times. The guard grammar is flat and **fail-closed** — a missing field or an unparsable term is
false.

**How a commit-driven trigger actually fires**, end to end: the processor takes a commit consumer, the
service layer passes it a bus, and the bus publishes to three subscribers — the job engine's
consignment-event handler, a mirror that republishes every commit as a signal so a signal trigger also
works, and the ingest scheduler's upstream-commit and dataset-write handlers.

⚠ **A `dataset.write` is announced only once the run that produced it has COMPLETED**
(`DATASET-PUBLISH-ON-FAILURE-1`, 2026-09-15). `ConsignmentProcessJobType` used to emit inside
`persistSummaries` — i.e. before `persistDerivedTables`, which throws, and before every later chain step
had run — so a run that went on to fail had already told the platform its Dataset was fresh, and an
`on: dataset` trigger could act on a write its own run then abandoned. Announcements are now accumulated
across the chain and fired after it closes. `MaterializeTask` always had this ordering (its emit sits
after the swap), so the two producers now agree.
⛔ Deferring is **sufficient** precisely because a processor signals failure by **throwing** —
`ProcessorResult` distinguishes only "did the work" from "nothing to do" — so reaching the end of the
chain *is* the success condition. ⚠ Granularity is unchanged: still one signal per (store, producer),
summed. Whether a run should emit **one** signal, and whether derived tables should announce at all, are
contract changes for `dataset.write` consumers and are deliberately still open.

🔴 **The `on: dataset` handler DOES have a self-loop guard — since 2026-09-15 (`DATASET-SELF-TRIGGER-1`).** Its javadoc previously said one was unnecessary because *“the producer is a Dataset write (a job/materialize), never the triggered pipeline’s own commit”*. That was **false for the consignment path**: `ConsignmentProcessJobType` emits from inside a pipeline’s own run, so a pipeline whose processor writes store `S` and declares `{on: dataset, from: datasets/S}` re-triggered itself — coalesced, so it degraded into a hot loop rather than a crash, and read as *busy* rather than *broken*.
⛔ **The obvious one-line guard could not have fired**, and would have looked right in review. `producer` was a bare String meaning different things at different sites — a JOB name from `MaterializeTask`, a PROCESSOR component id from `ConsignmentProcessJobType` — and `CollectorService` **dropped it entirely** before calling the scheduler, so nothing to compare ever arrived. The fix was therefore a vocabulary change first: `producer` is now a structured `Ref {kind, id}` on the Signal’s `actor` slot, and the **owning pipeline** travels as its own payload key, which is what the guard matches on.
⚠ **A `null` owning pipeline means “suppress nothing”, and that is load-bearing.** A cron or manually fired job genuinely has no owning pipeline (only an `event:<pipeline>` trigger carries one), so reading absence as “suppress” would have silently killed the ordinary materialize-then-trigger path that predates the guard.
⚠ The wiring was the defect, not the comparison — a guard unit-tested only on `PipelineScheduler` would pass while the shipped path stayed broken, which is why `CollectorServiceTriggerTest` drives the real subscriber end to end.

⚠ **There is no on-commit *job* trigger.** Commit-fired jobs ride the signal bus. Do not cite the
graph-structure refusal as the thing that keeps job work at rest — that is a pipeline-edge relation and a
different mechanism.

## Execution lanes

One Pipeline definition can execute in **five distinct lanes**. Before this page, each lane's concept
re-explained the split in its own vocabulary (~6 framings across five files, none authoritative —
inventory finding D-6). This page is now the **single owner of "which lanes exist"**; the mechanism
sections below (and [spec-ingest.md](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) for the graph fork) describe *how their lane works*.
⚠ The five lanes are the ways one Pipeline definition executes over a Consignment *before or instead of
commit*; the **post-sync lane** — `ConsignmentProcessor` Steps reading a Consignment *after* commit — is a
Job-hosted lane of its own, described in *Post-sync step chains* below.

Vocabulary (spec §13 D2, the token model): a Step receives a **Consignment token** and resolves data
by reference — edges never carry records. Every "what executes" cell below describes which code
resolves the token's data, not rows flowing along edges. *One note:* the runtime edge model itself
converges under Phase 7's major-bump window (D2's second half); the Batch→Consignment **rename** half
of Phase 7 shipped (`ff33246a`) with deliberate wire/persisted residuals — see *Naming* below.

### The lanes

| Lane | Triggered by | Divert / fork predicate | What executes | What it writes | Mechanism owner |
|---|---|---|---|---|---|
| **Ingest, flat** | the poll cycle (or `trigger:` schedule/event/manual) planning a Consignment over inbox files | the default — everything the graph fork refuses: no scratch dir, a node between map and sink, a decision rule that actually routed rows | `ConsignmentIngestor.process` → `CsvIngestStrategy` / `StreamingPluginIngestStrategy` → `DataTransformer` → `writeAndTrace`'s flat `sinks[]` loop (reference-version stamp + decision rules run here) | partitioned output per destination + the full commit tail: manifest, backup, **markers LAST**, three CSV ledgers, dedup ledger, watermark, `ConsignmentEvent` | [architecture-stage1](architecture-elt.md) (the core path) · [branch-aware-ingest](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) (the fork rules) |
| **Ingest, graph fork** | the same poll-driven ingest — this is a fork *inside* `writeAndTrace`, not a separate entry point | `route:` pipeline: `ConsignmentGraphRunner.engages(PipelineLift.lift(cfg))` (counts **branches**, not sink nodes). Non-route: `ConsignmentIngestStrategy.graphLaneCarries(cfg)` — scratch dir configured, lifted sinks 1:1 with `cfg.sinks()`, every sink `data`-fed directly off the `transform.map` seed | `ConsignmentGraphRunner.run` drives `PipelineExecutor` over the `route → sinks` subtree, seeded **below parse/map** — the lane performs only the WRITE, never re-runs upstream nodes | each branch via `IngestSinkWriter` to its `database`-paired destination, committed through a durable per-Consignment `BranchCommitLog` under `dirs.temp`; then the SAME commit tail as flat (shared code, not a mirror) | [branch-aware-ingest](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) |
| **At-rest job** (incl. the Stage-2 `pipeline_config:` chain) | a `type: pipeline` Job — cron / `on_pipeline:` / manual, or the config-less `POST /pipelines/authored/{id}/trigger` | the graph source picks the flavor, **mutually exclusive, exactly one**: `pipeline:` (an authored graph; legacy `flow:` dual-read) or `pipeline_config:` (a flat file's `steps:` chain, lifted at run time by `PipelineLift.stageTwo` into `source_store(landed) → chain → sink.persistent(output_store)`) | `PipelineJobRunner` → the production `PipelineExecutor`/`RowShaper` over `source_store` views (`SourceStoreReader`); this is where `dedup`/`join`/`summarize` execute — both ingest lanes refuse them | sink stores via `PartitionSinkWriter` (self-registers §11.3), `JobRun` → `DbJobRunStore`, watermarks, `sink.view` `ViewDefinition`s, `ConsignmentEvent` | *The at-rest job lane* (below) · [stage1-architecture §Step 3](architecture-elt.md) (the Stage-2-as-job split) |
| **Scratch** (dry-run + run-to-here) | `POST /pipelines/authored/{id}/run?to={nodeId}` (`canAuthorWorkbench` — the **simulate** verb, never `…/trigger`) and the editor's dry-run | none — always scratch, by construction: `PipelineTestRun` calls only the ingest half of `ConsignmentIngestor.process` (never commit/audit/provenance) over files **copied** into a scratch root; `PipelineExecutor.dryRun` bounds the walk to the target's ancestor closure | the real parse over picked inbox files + an in-memory `PipelineDryRun` walk (seed bounded to 1000 rows); a disabled node here is an in-memory **bypass** | **nothing in production** — scratch root only; returns `PipelineRunResult` | *The scratch lane* (below) |
| **Parked-then-drained** | not a fifth executor — the ingest graph fork's durable pause: the walk reaches a **disabled SINK** (via `processing.disabled_steps`) with a live inbound relation | armable only for a sink strictly inside an armed `route:` subtree (`StepDisableArming` refuses every other shape, at save and at `prepare()`) | park: `PipelineExecutor.ParkWriter` COPYs the branch relation to `dirs.backup()/parked/`, `ConsignmentIngestor.parkSource` records the manifest + `ParkedCommit` sidecar, status `PARKED`. Drain: `POST /runs/{name}/drain` → `DrainCommand` — **no re-walk**, registers the park tables and runs the real `finalizeSource` for the whole Consignment | park: park tables + sidecar + `pipeline.batch.parked` Signal, enabled branches already committed; drain: the ordinary commit tail over the union of sidecar outputs and its own | *Park and drain* (below) |

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
[architecture-stage1](architecture-elt.md) §Step 3.

**The COMMIT retry affordance (X1 deferrals, 2026-09-25; decisions Q1–Q4 in
[retry-affordance-design](../../../../archived-documents/plans-archive/retry-affordance-design.md)).** Three routes over
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
  [run-detail](user-guide-elt.md) § *Commit retries*. The routes came first, as Q3 decided.

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
record that fails the column count goes to the sidecar (`line_number`, `columns`, `reason`, `value_fingerprint`,
`source_file`, `source_size`, `source_sha256` — never the record itself, see below), the file's
other records land, and the file commits `SUCCESS` with `error_rows > 0` (pinned by
`RecordReplayTest.ejectAndContinueIsTheLiveBehaviour…`, `java` + `duckdb`). Two edges are not "eject":
a file with **zero** valid records is quarantined whole as `QUARANTINED_MISMATCH` (its sidecar moves with
it), and a failed type **coercion** nulls the value and KEEPS the row (counted in `cast_failures`, no
sidecar row). What was missing was only the replay, now `POST /runs/{name}/replay-rejects {file}`
(`canOperateRuns`) → `CollectorService.replayRejects` → `RecordReplay.replay`. Its choices:

- **Input = each rejected record RE-READ from the original source file** (⛔ *never store values*, operator
  decision 2026-10-03 — `INGEST-REJECT-SIDECAR-RAW-PAN-1`). The sidecar keeps only the record's **physical**
  start line (1-based, header included; both engines agree — the Java ingester reads through a
  `LineNumberReader`, DuckDB's `reject_errors.line` already is one), its columns, reason and a salted
  `value_fingerprint`, plus the source's name, size and sha256 (`CsvIngester.SourceId`). Replay looks for the
  source in the inbox, `backup/` and the quarantine tree (never `.restricted`), accepts it only when size +
  sha256 match, reads the records at those lines with the ingest's own decompression and quoted-field
  continuation (`CsvIngester.recordsAt`), and checks each against its fingerprint. A **gone** source file **→ 410**,
  source **changed** (size/sha or a fingerprint differs) **→ 409**, both before anything is claimed
  (`RecordReplay.SourceUnavailable`). The records are then written as a UTF-8 file of bare data lines
  into the poll root as `<stem>__replay_<sha8>.<ext>` and parsed with `PipelineConfig.forRecordReplay()`,
  which switches off every line-framing knob that described the ORIGINAL file (`skip_header_lines`,
  `has_header`, `skip_junk_lines`, `skip_tail_lines`, encoding, compression) and keeps everything that
  decides what a record means. Safe because fields map by selector index, never by header name.
  Sidecars written before 2026-10-03 (with `raw_line`) are refused 422: no source columns.
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
  (pinned by three `RecordReplayTest` cases). **The post-commit crash window (closed 2026-09-27):** a
  crash AFTER the replay Consignment committed but BEFORE its record was moved into place also leaves an
  empty claim. The replay's identity is fixed before the commit — its input name `<stem>__replay_<sha8>`
  is derived from the sidecar hash — so the reclaim first scans `dirs.status_dir`'s manifests
  (`RecordReplay.committedManifest`) for a live, non-`PARKED` member of that name; found ⇒ it deletes any
  leftover input, **completes the record** from that manifest (`batchId`, `status`,
  `completedFromManifest: true` — row counts are not in a manifest, so they read 0) and replays nothing.
  Pinned by `aCrashAfterTheCommitButBeforeTheRecordIsCompletedNotReplayed`. ⚠ Mutating the check away
  turns it red as `FAILED`, not as a double landing: under `duplicate_check` the second ingest of the
  same replay name is refused by its marker — so without the check the claim was never completable, and
  without `duplicate_check` it would have landed twice. A crash BEFORE the manifest write (inside the
  commit tail itself) is not covered here. ⚠ A whole-Consignment `reprocess` of the original rewrites the
  same sidecar with the same content, so its replay stays refused — correctly, since the earlier replay's
  rows were not superseded.
- **Refused (422):** a non-delimited frontend or plugin decoder, a sidecar row with no source columns, and a
  sidecar whose row count reaches `rejects_limit` (it may be truncated).

✅ **`processing.reject_mode: all_or_nothing` (built 2026-09-28, operator ask).** Absent or `eject` is the
behaviour above, byte for byte (the default path gains one boolean test per member, no extra pass).
`all_or_nothing`: a file with **any** rejected record lands **nothing** — it is quarantined whole under
`<quarantine>/…/rejects_all_or_nothing/` with its reject sidecar moved beside it, and its audit row reads
`QUARANTINED_MISMATCH` (the member vocabulary is unchanged) with the reason
`rejects_all_or_nothing: N record(s) rejected, nothing landed (reject_mode all_or_nothing)` and `error_rows = N`.
A "reject" here is only a record the parser sends to the `_errors.csv` sidecar (a parse or field-count
mismatch); type-cast failures (NULLed and counted) and later-stage checks (Expectations, Decision Rules,
dedup, steps) do not count.
**The unit is the FILE** (the Consignment member), like every other per-member quarantine: its clean
batch-mates still land, and a Consignment whose only member was refused ends `EMPTY`. Per ingest path —
the decision is always taken after the reject count is final and **before any of the file's rows are
written**, so there is no staging/rename step and a crash before the decision leaves no output at all:

| Path | Where the reject count is known | Mechanism |
|---|---|---|
| Java parse loop (`CsvIngestStrategy`) | after `CsvIngester.ingest` into the per-file `raw_f<id>` temp table | drop the temp table before it joins `raw_input` |
| Native single member (`NativeCsvStreamingEngine.streamingIngest`) | `streamUnit`: after `read_csv → transformed` (a table in the SCRATCH DB), before `writeAndTrace` | drop `transformed`, write nothing; `finishSingle` quarantines. 🔴 The BACKLOG's "streams to output before it knows its reject count" was wrong for this path: `DataTransformer.materialize` is a CTAS in the scratch DB, and `drainRejects` already ran before the write |
| Native multi-member UNION (`unionStreamingIngest`) | the per-member `COUNT(*)` probe | the member's view never joins the union |
| Native CHUNKED (`chunkedIngest`) | only after the LAST chunk — and each chunk reveals its output as it goes | **not used**: under `all_or_nothing` a chunking-sized file streams as one unit through the single-member path (the fan-out trade: it materialises in scratch) |
| Plugin lane (`StreamingPluginIngestStrategy`) | union mode: after the member's `sink.finish()`; generation mode: only after output was revealed per flush | union mode drops the member's raw tables; **generation mode is not used** under `all_or_nothing` (wins over the forced test seam too) |

Gates: `ConfigSpecs.pipeline()` declares `processing.reject_mode` (ENUM `eject`/`all_or_nothing`, no spec
default — absent means eject), `ConfigSafetyValidator` refuses any other value (**422** on `/config/write`),
and the parser refuses it at load naming the key (`PipelineConfig.RejectMode.parse`). The SPA config pane is
spec-driven, so the field appears there with no UI change; there is no editor node attribute for it.
**X4 replay:** a sidecar found in a `rejects_all_or_nothing` quarantine directory is refused (**422**) — none
of the file's good records landed, so replaying only the rejects would land the wrong half; the recovery is
the whole file (fix, move it back to the inbox). A replay input run under `all_or_nothing` is itself all or
nothing. Pinned by `RejectAllOrNothingTest` (Java single + multi, native single / union / chunked: clean
lands, one bad record lands nothing + quarantine + sidecar, eject unchanged, a failed quarantine — the stand-in
for a crash between the count and the publish — leaves nothing visible and the file in the inbox, replay
refused, unknown mode refused) and `StreamingPluginIngestStrategyTest.allOrNothing*`. ⚠ Residuals: under
`all_or_nothing` a huge single file (or a huge plugin input) materialises whole in scratch instead of
chunking/generating; a crash AFTER the decision, inside the ordinary write, is the same window `eject` has;
the chunked `eject` path names its sidecar per CHUNK (`<stem>_chunk_NNNNN_errors.csv`), so X4 replay by the
original file name does not find it (pre-existing, not changed). The nearest older knob,
`csv_settings.ignore_errors: false` (native engine only), still FAILS the batch on the first bad row — a
retry-then-`retry_exhausted` end, not a whole-file quarantine. **Audit:** the replay is recorded by the
single `AuditTrail` seam every mutating route goes through (actor, capability `canOperateRuns`, IP), under
its own action `pipeline.rejects_replayed` since 2026-09-25 — before that it fell to the POST default and
read as `pipeline.created`; `reprocess` was already `pipeline.reprocessed` the same way. **SPA:** the
rejected-rows dialog on Run Detail carries the action since 2026-09-25 — see
[run-detail](user-guide-elt.md). **Dry-run rejects on HTTP (2026-09-27):** the dry branch
of `ConsignmentIngestor.process` also files each member with `rejectTotal > 0` into `DryRunRejects`
(keyed by pipeline name, like `IngestProgress`; the run claim means one run per slot), and
`CollectorService.runPipeline` takes the slot while it still holds the claim. The v1 async poll
`GET /runs/runs/{runId}` then carries `rejects: [{consignment, file, rejectTotal, rejects: [{line, reason}]}]`
on a finished dry run — `[]` when nothing was rejected, absent on a real or failed run. Schema:
`PipelineRun` / `DryRunMemberRejects` in `openapi-v1.json`; pinned by
`ControlApiAsyncV1Test.pipelineDryRunPollCarriesTheRejectedRecords`. The legacy synchronous `RunResult`
body does not carry them, and the SPA has no pipeline-trigger dry-run panel to show them in (its dry-run
panel is the authored-pipeline `…/dry-run`, a different route) — so no UI change.

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
and `SchedulerAuditTask` reports the orphan. 🔴 **The following sentence was HISTORY stated as current and is corrected 2026-09-09** — since 2026-09-01 the check is **default-on in every space** (`JobService.auditOrphanOutputStores`, kill switch `-Djobs.orphan.audit=false`), exactly as *Recovery affordances per lane* above says. It *used* to fire only from a `scheduler_audit` maintenance job
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
[spec-ingest.md](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path). This section keeps the execution-side notes the PIP spec carried.

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

## The scratch lane — Pipeline test run (run-to-here)

**What it is:** a bounded, scratch-only run of an authored Pipeline over the user's **real** inbox
files — the "Test" in the Build → Test → Run authoring journey. Shipped 2026-08-14
(`1f0937ee`, `141caf84`, `0b2a80ba`, `0c542829`).

`POST /pipelines/authored/{id}/run?to={nodeId}` · `canAuthorWorkbench` · returns the UI's
`PipelineRunResult`.

It is a **simulate**, which is why it is author-gated and why the path is deliberately *not*
`…/trigger` — that sibling is the operate verb (`canOperateRuns`) and fires a real run. This section owns
the **scratch lane** mechanism; the lane map is *Execution lanes* above, the operate lane *The at-rest job
lane* above.

### Why it is safe: two independent containments, both structural

A test run that mutates production state would be worse than no feature at all, so containment is by
construction rather than by a flag anyone can forget.

**1 · Call-graph containment.** `ConsignmentIngestor.process` is, in order: `strategy.ingest(...)`, then
`commit(...)`, then `writeAudit(...)`, then `recordProvenance(...)`. `PipelineTestRun` calls **only the
first**. This is the load-bearing decision, because **five destinations are not derived from the config
at all** and so could never have been redirected by one:

| Destination | Resolved by | Lives in |
|---|---|---|
| Acquisition ledger (dedup fingerprint, DB-export watermark) | `-Dacquire.ledger.backend` / `.db.url` | `finalizeSource` (inside `commit`) |
| Consignment output registry | `-Dconsignment.outputs.backend`, per-space registry | `finalizeSource` |
| File stages | `-Dfile.stages.backend`, per-space registry | `finalizeSource` |
| `pipeline.batch.*` Signal | ambient `EventLog.current()` (space MDC) | `writeAudit` |
| Provenance matrix | process-wide registry | `recordProvenance` |

⛔ **If a fourth side-effecting statement is ever added to `ConsignmentIngestor.process`, do not mirror it
into `PipelineTestRun`.** The omission *is* the safety property.

**2 · Filesystem containment.** Picked files are **copied** into `scratchRoot/poll`, and the run
executes against `PipelineConfig.forScratchRun(scratchRoot)`, which re-roots every destination.

⚠ **The copy is not an optimisation to remove.** `CsvIngestStrategy` quarantines an unreadable /
field-mismatched / empty member through `QuarantineManager.quarantine`, which does a **`Files.move` of
the source file** — from *inside* the ingest half. Redirecting `dirs.quarantine` does not help, because
the source is the problem: run against the real inbox and **testing a malformed file would delete it
from the user's inbox**. (A hardlink would also work on a single filesystem but fails across volumes —
a later optimisation behind a fallback, never the default.)

🔴 **Re-rooting the sinks means re-rooting the `route:` branches with them** (`ROUTED-WRITE-COUNTS-PER-BRANCH-1`,
fixed 2026-09-23). Branch↔sink pairing is **by the branch's declared `database`**
(`PipelineLift.branchKeyForDatabase`). While `forScratchRun` moved the sinks under the scratch root and
left the branches naming production directories, the lift paired **nothing**, fell through to a plain
data edge per destination, and the test run silently **degraded to a fan-out: every sink received every
row**. It reported `rowsWritten` = rows × branches — 9 → 18 on `route_step`, 12 → 36 on `premed_events`,
the branch-count factor that named the defect — and showed a builder the whole feed under each branch.

⚠ **The counts were the symptom; the content was the damage.** A fan-out and a correct route agree on
`outputs().size()`, so any test that asserts only the shape of the result passes against both. Assert
the per-destination **content** (`RouteIngestEndToEndTest.aTestRunOfARoutedPipelineRoutesRatherThanFanningOut`).
The general rule this is an instance of: **anything `forScratchRun` re-roots, every config key that
JOINS on that value must be re-rooted in the same step** — `route.branches[].database` is the only such
join key today, and a second one would fail the same silent way.

The ingest half touches exactly five dirs — `poll`, `database`, `errors`, `quarantine`, `temp` — plus
each `sinks[].database` on fan-out. `forScratchRun` additionally **nulls the commit-half destinations**
(`backup`, `markers`, the status/batches/lineage CSVs, manifests, commit log) even though the commit
half is never reached: defence in depth, so a future caller that *does* call it still cannot write to
production. `backup == null` is what makes the source-file backup a no-op.

Building the batches directly also bypasses `CollectorProcessor`, so the dedup/marker layer never runs
and a test run cannot mark a file as already-processed.

### The `files` jail

The body is caller-supplied, so without containment this route is an arbitrary-file-read over HTTP.

- Entries are **connection-relative** — the picker fills them from `GET /connections/{id}/explore`,
  whose `ResourceNode.path` is relativized against the profile's `base_path`.
- The jail root is **derived server-side** from the pipeline's own `source.connection` profile
  (`dirs.poll` when it binds none). The request carries no connection id, so this is enforceable by
  construction. ⛔ **Never accept a client-supplied root.**
- Containment reuses **`LocalConnectionWorkbench.jail(Path, String)`** — the *same* primitive the picker
  uses, so the two surfaces cannot disagree about what is reachable. A picker that allows X beside a
  runner that allows Y is how this class of hole appears. An escape is `PathEscape` → **403**.
- ⚠ Not to be confused with `ConfigSafetyValidator.checkPathValue`, which is **advisory** (collects
  `Finding`s at authoring time) rather than enforcing.
- Non-`local` connectors are **501** — there is no local path to stage from; those files reach the
  inbox via acquisition first.
- A **Dataset-fed** Pipeline (`collector: dataset`) is **501** too, naming the Dataset (`WB-07`,
  2026-09-22). It used to answer a `{files:[…]}` run with 200 *“no rows were parsed”* — an empty
  success where the honest answer is a refusal. Row `TESTRUN-DATASET-COLLECTOR-SILENT-1`.

### Getting a file into the inbox (INBOX-UPLOAD-1, 2026-09-25)

A Stream onboarded from the Catalog binds **no connection** and polls `data/inbox/<stream>` under the Space
root, so its run-to-here jail root is `dirs.poll` — and until this route the builder had to copy a file
there by hand (found in a builder pilot). `PipelineInboxRoutes`:

- `GET /pipelines/authored/{id}/inbox` — open read: the regular files directly in `dirs.poll`, newest
  first, by **bare name** (exactly what `files` takes for a connection-less Pipeline). Capped at 500 with
  `truncated` + the true `total`.
- `POST /pipelines/authored/{id}/inbox?file=<name>[&overwrite=true]` — `canAuthorWorkbench` (like
  run-to-here). The **raw body** is the file. Gates in order: 503 no write root · 404 · 501 Dataset-fed ·
  422 no name · **403 a name that is a path** (separator, `..`, colon — refused, never resolved; the
  resolved target is still jailed to `dirs.poll`) · 413 over 64 MiB (on the declared length, before
  buffering) · 422 empty body · 409 exists without `overwrite` · then `AtomicFiles.write` — the staging
  file is a `.tmp` sibling, which the poll's in-flight exclusion ignores.
- ⚠ It writes into the **production** inbox: an ACTIVE Pipeline ingests the file on its next poll. That is
  the point for a draft Stream's first file; it is not a scratch copy.
- ⚠ A connection-bound Pipeline's run-to-here jails against the connection's `base_path`, not
  `dirs.poll`, so the editor offers the upload only when no connection is bound.
- The UI is the Run-to-here dialog's *Inbox files* section (`run-to-here.dialog.ts`): it lists the inbox,
  uploads a picked file, asks before replacing (409 → confirm → `overwrite=true`), then refreshes the list
  and selects the upload.

### Response, and the two grains in it

`PipelineRunResult` = `{seedNode, toNode, files[], relations[], output|null, warnings[]}`.

⚠ **`relations[]` counts the seeded sample; `output.rowCount` is the full parse.** The seed is bounded
(`PipelineTestRun.SEED_ROWS` = 1000 raw rows per segment) because `PipelineDryRun` is in-memory and a picked file is unbounded. A
warning names the difference whenever the two can disagree, so neither number is quietly mistaken for
the other. Per-file quarantine outcomes also surface as warnings — the operator is told a file *would*
be quarantined even though nothing moved.

`relations[].{node, rel, rowCount}` is load-bearing: the canvas marks a node ✕ on
`rel === 'unmatched' && rowCount > 0`.

The sibling `POST …/dry-run` takes `{sampleRows:[…], pipeline?}`. A body of the wrong JSON shape (a bare
array is the natural first guess) is **400**, not 500 — fixed at the shared `ControlApi.body` seam
(`WB-06`, 2026-09-22), because the 500 was every POST route's defect, not this route's; a route's own more
specific 400 still reaches the caller. Row `DRYRUN-MALFORMED-BODY-500-1`.

### The `to=` cutoff (2026-08-14)

`PipelineExecutor.dryRun(..., stopAtNodeId)` bounds the walk; `PipelineDryRun.run` and
`PipelineGraphRoutes.testRun` thread `to` down to it. `null` means the whole graph, which is the only shape any
production caller passes.

⚠ **The bound is the ancestor closure of the target, not a prefix of `topoOrder`.** Topological order is
arbitrary between sibling branches, so truncating it runs whichever branch happens to sort first and reports
counts for nodes the operator never asked about — which the canvas then marks ✓. `ancestorsOf` walks edges
backwards instead (skipping `on_commit`, which is a cross-Pipeline trigger rather than a data dependency). *(Until 2026-09-10 this
added that the offline mock's `subgraphTo` matched, "so mock and server agree" — that mock was deleted
2026-08-31, so the server's walk is the only definition of what a run-to-here covers.)*

⚠ **`execute` was not touched.** The plan expected the cutoff to thread through a walk shared with the
production executor; in fact `execute` and `dryRun` are separate loop bodies sharing only the private
`topoOrder` helper, so the production path is untouched rather than merely defaulted.

Two boundaries worth keeping straight:
- **A cutoff bounds the preview, not the parse.** The picked files are always parsed in full, because the
  parse is what seeds the walk. `to=` narrows the answer, never the work.
- **Bounded-on-purpose is not "nothing would be written".** A cutoff above every sink leaves `sinks` empty,
  which deliberately does *not* trip DRYRUN-2's "no sink received any rows" warning — that one requires a
  non-empty `sinks` list.

An unknown `to=` throws → **400**, rather than silently widening to the whole graph.

### Two blind spots closed: enrichment and webhook (G8, 2026-09-23)

Both apply to `POST …/dry-run` and to run-to-here, because both go through `PipelineDryRun.runSeeded`.
Row `PROCESSOR-RELEASE-READINESS-1`, gap G8.

**An `enrichment` node is WARNED BY NAME, not run and not refused.** The walk runs only `transform.*`
(`PipelineExecutor.isShapeable`), `transform.merge` and sinks. An `enrichment` node mid-walk used to produce
nothing, and nothing below it ran either. Its sink disappeared from `sinks[]` and no warning fired, because
both DRYRUN-2 warnings need either no node reached at all or a sink that got zero rows. Now
`PipelineExecutor.DryRunResult.notExecuted` lists every enabled node the walk reached and has no executor for.
Control terminals are left out, since they produce no data relation by design. `PipelineDryRun` adds one
warning per such node. The warning names the node, gives the reason, and lists the nodes below it that got
nothing, for example: *node 'enr' (enrichment) was NOT previewed: … preview it with POST /enrichment/preview;
so nothing reached 'sink'*.
⚖ **Why a warning and not a run:** an enrichment is a post-commit Stage-2 job. It reads the *committed store*
(partitions, earlier batches, its own references), and neither run lane runs it as a walk step. Running
it over the sample would preview something no real run does. **Why not a refusal:** that would block the
preview of every graph with an enrichment in it, because of one node the rest of the walk doesn't need.
Any future node type the walk has no executor for gets the same named warning.

✅ **A mid-walk enrichment cannot be SAVED (2026-09-24, `ENRICHMENT-MIDWALK-LANES-DISAGREE-1`, operator
decision).** The two lanes disagreed about it: `PipelineExecutor.execute` skips the node and starves
everything below it, while `PipelineEditable.lower` drops the node and so the flat file feeds its downstream
from its upstream. `PipelineEditable.lower` now refuses any `enrichment` node with an edge to another node in
the graph: **422** `{written: false, refusals: [{code: "ENRICHMENT_NOT_TERMINAL", nodeId, message}]}`, and the
message names the nodes it feeds. The canvas shows it through the same `refusals[]` path as every other lower
refusal (`showRefusals` → the Validation dock, next to the node). ⚖ The refusal sits at the SAVE, not in `PipelineValidator`. The
candidate dry run and run-to-here still accept the shape and give the named warning above, so a draft can
still be previewed. What a real run executes always comes from a saved file, and a saved file can no longer
hold this shape. A terminal enrichment still saves. So does the derived `companion` edge, because it points
INTO the enrichment and `withoutDerivedEdges` drops it before parsing anyway. Pinned by
`ControlApiEnrichmentMidWalkRefusalTest` (3, real HTTP). `ControlApiPipelineGraphRoundTripSweepTest` stays
green, so no shipped Pipeline is refused. `ControlApiPipelineCrudTest#anEnrichmentNodesCompanionBindingSavesOverHttp`
used to author the mid-walk shape and now uses the companion shape `graph/raw` really serves. Falsified: with
the check disabled the 422 test fails with the old `200 written:true`, and refusing every enrichment turns the
terminal and companion tests red.

✅ **The companion shape now reaches the walk (2026-09-24, `GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1`).**
`GET /pipelines/{name}/graph/raw` joins a `*_enrich.toon` companion to the sink with a **derived display-only**
edge `{rel: "companion", derived: true}` instead of a `data` edge (which `PipelineValidator` refuses
`ILLEGAL_EMIT` — a sink emits nothing). `parseAndValidateFlow`, shared by the
candidate dry run and `PUT …/graph`, drops that edge before parsing, so both answer 200; a hand-authored
sink `data` edge is still refused. As-built in
[editable-round-trip](spec-pipeline-model.md#derived-companion-edge--display-only-never-saved-2026-09-24) (*Derived companion edge*).

**A `sink.webhook` branch now refuses the way the job dry run does.** For every webhook sink the walk reaches,
`PipelineDryRun` calls `WebhookSink.plan`. That is the same resolution `DryRunSinkWriter` uses: config, edition
transport, Connection, token. On a bundle with no `WebhookSinkTransport` (Personal), the dry run returns
**422** with WebhookSink's own message, which names the sink and the edition. It used to show a healthy
branch with a row count. The same call also refuses an unregistered Connection or a token that doesn't
resolve, just as the job dry run does. Nothing is ever sent.

Pinned by `ControlApiDryRunBlindSpotsTest` (2, real HTTP). The `inspecto` module does not depend on
`inspecto-notify-channels`, so its test classpath matches Personal. Falsified: making each fix a no-op turns
its own test red with the old answer (`warnings: []` and `sinks: []` for the enrichment, 200 with the webhook
branch counted for the webhook).

### A SQL failure reads as the real error first (2026-09-23)

A step whose SQL is wrong 422s with *“test run failed: Binder Error: Referenced column "EVENT_TS" not
found … Candidate bindings: "EVENT_DATE" …”* — the actionable error first, the column and the candidates
kept verbatim. `POST …/dry-run` does the same (*“dry-run failed: …”*).

⚠ **Why there was anything to strip:** DuckDB's JDBC driver (1.5.2.1) runs `Statement.execute(String)` as a
pending query without checking whether preparing it failed, so a bind failure arrives as ONE native message:
*“Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result”*, a newline,
`Error: `, then the real error. The `SQLException` carries **no cause, no suppressed, no SQLState** — there
is no "real exception" to unwrap. `Connection.prepareStatement` on the same SQL reports the Binder Error
cleanly, which is how the cause was confirmed; switching the walk to prepared statements was **declined**
because the dry-run shares its SQL execution with the production executor.

✅ **One seam:** `DuckDbUtil.withoutPendingQueryPreamble` removes exactly that driver text and nothing else.
Used by `testRun`, `dryRunFlow` and `POST /components/transform/describe` (which carried its own looser
copy until now). `DuckDbPendingQueryPreambleTest` runs the real driver, so a DuckDB upgrade that rewords
the preamble goes red instead of letting it silently back in. Row `TESTRUN-BINDER-ERROR-LEAKS-PREAMBLE-1`.

**Every other 422 built from such a failure adopts the same seam (2026-09-23, `DUCKDB-PREAMBLE-OTHER-422S-1`).**
Probed on the real driver: the preamble appears on **any plain `Statement`** — `execute(String)` AND
`executeQuery(String)` — for a failure found while binding (Binder, Catalog, a table function's `IO Error:
No files found`), and **never** on a `PreparedStatement`, a Parser Error or an execution-time error (e.g.
Conversion Error). `QueryExecutor.run` registers the dataset view on a plain `Statement` and runs a query
with no binds on `SqlSandbox.statement()`, so every route over it could carry it. Adopted at: `BiRoutes`
(`/bi/query`), `QueryRoutes` (`/queries/{id}/run`), `ShareRoutes` (`/public/dashboards/{t}/query`),
`RuleRoutes` (`/rule-templates/{id}/simulate`), `DbBrowserRoutes` (`/db/query` + `/db/table`, both the
store and the `ops:` group — `BrowsableStore.exec` is a plain `Statement` too), `EnrichmentRoutes`
(`/enrichment/preview`), `ExpectationRoutes` (`/expectations/{name}/evaluate`), `ReconRoutes` (all four),
`ViewRoutes` (`/views/{name}/data`), `RouteErrors.mapPreviewErrors` (`/components/{transform,grammar}/…/preview|test`),
and in `inspecto-geo-link` `GeoRoutes` (`/geo/projection|routes`, the `SQLException` arm only) and
`InvRoutes` (projection, multi-projection, traversal). One real-HTTP test per route family asserts the
message **starts with** the real error; each went red against the unstripped message before the fix.

⚠ **Not yet adopted elsewhere:** four more routes (`BiRoutes`, `DbBrowserRoutes`, `EnrichmentRoutes`,
`ExpectationRoutes`) build a 422 from a raw DuckDB message and probably carry the same preamble —
unverified, reproduce per route before adopting the seam. Row `DUCKDB-PREAMBLE-OTHER-422S-1`.

### Defects found by the demo lanes (reported 2026-09-23)

Found by the lanes that built the domain demos (`gl_journal`, `stock_movements`, `msc_cdr`,
`in_recharges`). Each cause is the lane's hypothesis until someone reproduces it.

✅ **The seed is the PARSER's raw rows, captured before mapping** (`TESTRUN-SEED-IS-MAPPED-OUTPUT-1`, fixed
2026-09-23). **Decision (operator, 2026-09-23): seed the preview with the RAW parsed rows, so it runs the same
mapping a real ingest does.** The alternative — seed downstream of `map` and skip it — was declined: it would
leave the mapping, the step most likely to be wrong, as the one step a test run never exercises.
- 🔴 **What it was:** `PipelineTestRun.sampleRowsBySegment` read back the rows the ingest **wrote** — already
  mapped — and `testRun` passed them to `PipelineDryRun.runSeeded` as the parse node's output, so the walk
  re-applied `map` / `map_<segment>` to canonical columns. **Loud:** `in_recharges` (`AMOUNT_MINOR`) and
  `msc_cdr` (`EVENT_TIME`) refused 422 with a binder error, so the test run could not reach route or sinks.
  **Silent, and worse:** `premed_events` answered 200 with every `EVENT_TS` NULL — the written TIMESTAMP,
  stringified by JDBC and re-parsed by the `keep`'s format list, failed the format and became NULL.
- **The seam:** `DataTransformer.RAW_INPUT`, a `ScopedValue<RawInputObserver>` checked at the top of
  `DataTransformer.materialize` — the ONE point all six ingest lanes (native single / chunked / union, the
  Java parse lane, both plugin modes) funnel through. `PipelineTestRun.run` binds it around
  `strategy.ingest(...)` and samples `SELECT * FROM <raw source> LIMIT n` (up to `SEED_ROWS` = 1000 per
  segment, `__src_id` dropped) into `Result.rawRows()`. The segment is found from the schema map the
  union-mode ingester passes (identity, then value) — never from table names. Unbound, i.e. every production
  run, it is one `isBound()` check. ⚠ A scoped value, not a parameter, because a parameter would have to be
  threaded through all six lanes; ⚠ it does not cross threads, which is fine only because every lane calls
  `materialize` on the ingest thread.
- ⚠ **The sample re-scans the raw relation** (a lazy `read_csv` view on the native lanes). Production already
  scans it twice (`materialize` + `countCastFailures`), so this adds a third bounded scan in a test run only.
- ⚠ **An exception thrown by the observer is the ingest's exception** — on the single-member native lane it
  fails the batch as a transform failure (the input still reads, see below). The sampler is a plain
  `SELECT … LIMIT`, so this is theoretical today.
- Pinned by `ControlApiPipelineTestRunDemoTest` (3, real HTTP over the shipped demos, each compared against a
  REAL ingest of the same committed sample): `premed_events` — all 12 `EVENT_TS` equal to the written values,
  route voice·sms·other `5·3·4` as written; `in_recharges` — 14 `AMOUNT`s equal to the written ones, route `10·4`; `msc_cdr` —
  each `map_<segment>` row count and `EVENT_TS` equal to the written segment. ⚠ A previewed TIMESTAMP comes back
  as epoch millis of a HOST-zone `java.sql.Timestamp`; reading it as UTC is off by the host offset.

✅ **A failed batch is reported as the failure** (`TESTRUN-FAILED-BATCH-REPORTED-EMPTY-1`, fixed 2026-09-23). A
`FAILED` `PipelineTestRun.Result` now answers **422** *“test run failed: the batch FAILED after N row(s) parsed:
&lt;the batch's error&gt;”* before any preview runs, and `RunToHereDialog` renders it in its error alert (it already
surfaced any error body through `apiErrorMessage`; `run-to-here.dialog.spec.ts` pins the rendered alert). 🔴 With
the raw seed alone the hole got WORSE, not better: a failure outside the mapping — a `partitionKey` naming an
absent column fails the real transform, while the preview's map projects mapped columns only — answered 200
with a clean preview and no warning. Pinned by
`ControlApiPipelineTestRunTest.aBatchThatFailsAfterParsingIsReportedAsTheFailureNotAsEmpty`, which uses TWO files
(written when only the multi-member lane failed the BATCH on a transform error; one file now does too).

✅ **A transform failure fails the batch on the single-member lane too; it never quarantines the input**
(`SINGLE-MEMBER-TRANSFORM-FAILURE-QUARANTINES-1`, fixed 2026-09-23). `streamingIngest` held `streamUnit`'s
`materialize` inside the catch that quarantines `QUARANTINED_UNREADABLE`, so a bad `partitionKey` over ONE
readable file quarantined it — in production, moving a good file out of the inbox. The view is lazy, so the
read and the transform fail in one statement and the error text cannot tell them apart. **The seam:** when
`materialize` throws, `streamUnit` re-drives the read ALONE with `SELECT COUNT(*) FROM raw_input` — the same
readability probe `unionStreamingIngest` runs per member, so both native lanes now share one definition of
*unreadable*. Probe fails → the original error propagates and the member is `QUARANTINED_UNREADABLE` as before;
probe succeeds → `TransformFailedException` (*“transform failed for &lt;file&gt;: …”*), which `streamingIngest`
rethrows beside `SinkFlushException`, so the batch is `FAILED` and the file stays in the inbox for the retry.
**Cost: zero on a successful batch** — the probe runs only on the failure path (one extra read of a file that
already failed). A cheaper pre-read or a `DESCRIBE` bind-check was not needed and would have taxed every batch.
⚠ A runtime transform error (e.g. author SQL in an `EXPR` rule that throws) is classified correctly too — the
probe does not care WHY the transform failed. The chunked lane now classifies per chunk too — see the next
paragraph (`CHUNKED-UNREADABLE-FAILS-BATCH-1`). The Java parse lane (`IOException` only) and both plugin lanes (transform
wrapped in `SinkFlushException`) already classified correctly. Pinned by
`ConsignmentIngestorTest.singleMemberTransformFailureFailsTheBatchAndLeavesTheFileInTheInbox` and
`…singleMemberUnreadableInputIsStillQuarantinedUnreadable` (a non-gzip `.csv.gz`, which fails INSIDE
`materialize`, so it exercises the probe's unreadable branch — a probe forced to succeed turns it red).

✅ **The chunked lane quarantines an unreadable file, and rolls back the chunks it already wrote**
(`CHUNKED-UNREADABLE-FAILS-BATCH-1`, fixed 2026-09-23). `chunkedIngest` had no quarantine catch, so an
unreadable file over `processing.chunking.max_file_bytes` FAILED the batch and stayed in the inbox until
`CommitRetry` exhausted it (`retry_exhausted`, not `unreadable`), each attempt re-writing the readable chunks.
**Classification, per chunk, with no second helper:** each chunk goes through the same `streamUnit`, so the
`COUNT(*)` probe above already splits a failed `materialize` into `TransformFailedException` (batch `FAILED`,
file stays) or the raw read error (unreadable); `SinkFlushException` still fails the batch. 🔴 **The realistic
unreadable chunk is not a `read_csv` error at all** — chunks are re-written as clean UTF-8 and read with
`ignore_errors=true` — it is `FileChunker`'s own read of the source: a corrupt `.gz` fails in its constructor, a
TRUNCATED `.gz` fails in `next()` after earlier chunks were written. `FileChunker` now raises those as
`UnreadableSourceException`, kept apart from a plain `IOException` writing the chunk to scratch (disk full, a
too-long path — the host's fault, which still fails the batch, the `WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1`
lesson). The constructor closes its stream when it throws: the caller then MOVES the file, which an open handle
refuses on Windows. **Atomicity (decided 2026-09-23):** each chunk's write is revealed into the partition dirs
on its own, and a quarantine never commits (no manifest, registration or lineage), so the earlier chunks would
sit in the Dataset as unregistered orphans that a glob still reads — a half-written Consignment, silently. So
an unreadable chunk makes the whole FILE unreadable: `rollBackChunks` deletes every output file the earlier
chunks wrote and the batch's branch-commit log (which would otherwise claim those chunk scopes committed), then
the file is quarantined `QUARANTINED_UNREADABLE` with *“chunk N unreadable: … (K output file(s) written by
earlier chunks rolled back)”* — the same all-or-nothing a single-member file has. ⚠ Not undone: Decision Rule
record-quarantine copies (`quarantine/records/…`) from earlier chunks — quarantine evidence, beside the file.
⚠ A chunk output that OVERWROTE a same-named file from an earlier committed batch (same file stem re-ingested)
is deleted too; that earlier content was already gone at the overwrite. Pinned by `ChunkedStreamingTest`
(+3): `truncatedGzipLaterChunkIsQuarantinedUnreadableAndEarlierChunksAreRolledBack` (20 chunk outputs written
then removed; red with the rollback disabled, and separately with only the branch-log delete disabled),
`corruptGzipChunkedFileIsQuarantinedUnreadable`, and `chunkedTransformFailureFailsTheBatchAndLeavesTheFileInTheInbox`
(red when the per-chunk catch swallows `TransformFailedException`).

✅ **`PARKED-BRANCH-LEAK-ON-FAILED-BATCH-1` FIXED 2026-09-23:** `ConsignmentIngestor.process` now drains the batch's `ParkedBranches` entry on EVERY non-dry-run outcome — a non-SUCCESS batch (FAILED, `QUARANTINED_UNREADABLE`, …) drains and discards it, so the static registry never outlives its batch; a re-run re-parks to the same `<backup>/parked/<batchId>__<node>.parquet`. Pinned by `ChunkedStreamingTest#parkedBranchEntryIsDrainedWhenTheBatchDoesNotSucceed`. ✅ `GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1` FIXED 2026-09-24 — the companion edge is a derived display-only `rel: companion` edge the save path drops (see above). ✅ `ENRICHMENT-MIDWALK-LANES-DISAGREE-1` FIXED 2026-09-24 — a mid-walk enrichment is refused at save, `ENRICHMENT_NOT_TERMINAL` (see *Two blind spots closed* above).

✅ **A failed partition write fails the batch; it never quarantines the input**
(`WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1`, fixed 2026-09-23). A scratch path near 250 characters failed the
partition write on Windows, and the member was marked `QUARANTINED_UNREADABLE` although it read fine. The
cause was not Windows-specific: `NativeCsvStreamingEngine.streamingIngest` (the single-member native
`read_csv` lane) held the `read_csv`, the transform **and** the `writeAndTrace` in one `catch`, so any sink
failure became an unreadable-input verdict — and in a production run that verdict **moves a good file out
of the inbox**. `streamUnit` now wraps the write in `SinkFlushException` (the type the generation and union
lanes already use for the same distinction) and `streamingIngest` rethrows it, so the batch is `FAILED`
with *“partition write failed for …”* and nothing is quarantined. A genuine `read_csv` failure surfaces
before the write and is still `QUARANTINED_UNREADABLE`. Pinned by
`PipelineTestRunTest.aFailedPartitionWriteFailsTheBatchAndNeverBlamesTheInput`, which blocks the partition
directory with a regular file, so it runs on every platform. The route's scratch prefix is also shorter
(`itr_`, was `inspecto_testrun_`); the rest of a written path — the temp dir, the partition layout, the
file stem — is data and still counts toward the Windows limit. ⚠ A `FAILED` test run still answers
*“no rows were parsed”* — no longer: it answers 422 with the batch's error (above).

### A `route:<segment>` edge out of a parser IS walked (D5, signed 2026-09-22)

✅ **Decision (operator, 2026-09-22):** the test run **walks segment routes**. The alternative — declaring
segment-routed frontends out of scope with a named 422 — was considered and declined, because it would
leave 2 of 8 parse frontends, and every multi-record-type feed, with no test instrument past the decoder.

✅ **AS-BUILT since 2026-09-22 (`WB-08`), verified live 2026-09-23.** The dry-run seeds the parse node
with **one relation per segment** — `PipelineDryRun.runSeeded` takes `rel → rows`, materialises one
scratch table each, and `PipelineExecutor.dryRun` accepts a `rel → table` seed map. `liveInbound` then
follows the existing edges unchanged.

⛔ **The walker is NOT special-cased, and must not become so.** A second traversal rule for `route:` is
how a preview and a real run begin to disagree. The fix was the SEED SHAPE, which is what `D5` decided.

⚠ **Seed keys are the graph's own edge relations**, built with `PipelineRel.route(...)` over
**`PipelineLift.routeKey`** — made public for exactly this. ⛔ Never re-derive that sanitisation: it is a
name that has to match the edge exactly, and a second copy is free to drift.

⚠ **A segment that routed nothing is ABSENT from the result, not present with zero.** *Did not run* and
*ran and produced nothing* are different answers and the canvas renders them differently, so an empty
segment is never seeded with an empty table.

**Measured live 2026-09-23** against a running control plane over real bytes: `asn1_example` (BER)
returns `map_moCallRecord · data · 3 rows` carrying the decoded values and writes 3 rows under the
`moCallRecord` partition; `xml_example` (plugin ingester) returns `map_order · data · 3 rows`.

🔴 **What it was:** both returned `relations: []` with the honest DRYRUN-2 warning *“the sample reached
no node past the seed 'parse' — nothing downstream consumed it”*. The seed only ever wrote `data`, so the
walk could not leave a `parse →(route:<segment>)→ …` parser at all: two of eight parse frontends, and
every multi-record-type feed, had no test instrument past the decoder. ⚠ The plan briefly recorded this
as blocked on unrecoverable attribution — wrongly: `IngestOutcome.schemaByOutput` (output file → segment)
had carried it since the union-mode ingester wrote it; nothing was reading it out. Pinned by
`SegmentRoutedDryRunTest`; row `TESTRUN-SEGMENT-ROUTE-NO-FLOW-1`.

### Testing note worth keeping

Both safety properties were **falsification-probed**, and one probe changed the test:

- Removing the staging copy did **not** fail an assertion that merely checked "the inbox file still
  exists" — `QuarantineManager`'s own poll-root guard threw instead of moving, so the file survived for
  an unrelated reason. The test now pins containment **positively**: the staged copy must be found
  quarantined *inside* the scratch root.
- Replacing the jail with a plain `resolve()` turns the escape test red, and the failure output is the
  vulnerability in the clear — `../secret.csv` read, parsed, and its contents returned in the response.
- **The cutoff probe caught a bad test before it caught bad code.** Swapping `ancestorsOf` for a truncated
  `topoOrder` left the headline "a sibling branch does not run" test **green**: Kahn's order for the fork
  fixture is `acq, left, right, …`, so bounding at `left` gives the right set by coincidence. Only bounding
  at the sibling that sorts **later** discriminates, which is what the test does now.

**Re-run those probes rather than trusting a green suite** when touching staging, the jail, or the cutoff —
and when a probe leaves a test green, suspect the test, not the probe.

### Code

- `platform/inspecto-engine/…/inspector/PipelineTestRun.java` — `run` (binds `RAW_INPUT`), `Result.rawRows`, `sampleRows`, `deleteScratch`
- `platform/inspecto-etl/…/etl/DataTransformer.java` — `RAW_INPUT` / `RawInputObserver`, checked in `materialize`
- `platform/inspecto-etl/…/etl/PipelineConfig.java` — `forScratchRun(Path)`
- `inspecto/…/control/PipelineGraphRoutes.java` — `testRun`, `testRunRoot`, `graphFor`, `fileList`, `runResult`
- `platform/inspecto-acquire/…/acquire/LocalConnectionWorkbench.java` — `jail(Path, String)`
- `inspecto/…/control/PipelineInboxRoutes.java` — `list`, `upload` (INBOX-UPLOAD-1); test `ControlApiPipelineInboxTest` (8, real HTTP)
- `platform/inspecto-engine/…/pipeline/exec/PipelineExecutor.java` — `dryRun(…, stopAtNodeId)`, `ancestorsOf`
- `platform/inspecto-engine/…/pipeline/exec/PipelineDryRun.java` — `run(…, stopAtNodeId)`, `notExecutedWarnings`, the `WebhookSink.plan` loop in `runSeeded`
- `platform/inspecto-util/…/util/DuckDbUtil.java` — `withoutPendingQueryPreamble`
- `platform/inspecto-engine/…/query/QueryExecutor.java` — `run` (the plain-`Statement` view registration and no-bind query)
- Tests: `PipelineTestRunTest` (8), `ControlApiPipelineTestRunTest` (8, real HTTP),
  `ControlApiPipelineTestRunDemoTest` (3, real HTTP over the shipped demos vs a real ingest),
  `PipelineDryRunTest` (15, of which 5 pin the cutoff), `ControlApiDryRunBlindSpotsTest` (2, real HTTP — G8)

## Whole-pipeline dry run (`PIPELINE-DRYRUN-1`, shipped 2026-09-20)

`POST /runs/{name}/trigger?dryRun=true` runs an entire cycle — acquisition **and** the flat ingest lane —
and **lands nothing**, for **every** pipeline, with no per-pipeline caveats: no partition outputs, no
quarantine or backup moves, no manifest, no markers, no audit or commit-log rows (not even the commit
log's header file), no fingerprint/watermark ledger entries, no unpack scratch. Every suppressed mutation
is logged `dry run: would …`, so the log *is* the report of what a real run would have done. 🔴 **The ONE
durable write a dry run makes is a provenance record flagged `simulated`** (metadata only, below).

**Two flags, deliberately distinct.** `?skipPostAction=true` means *acquisition fetches but never acks the
remote original — the ingest write still happens for real*. `?dryRun=true` means *nothing happens*.
✅ **`dryRun` IMPLIES `skipPostAction`**, OR-ed in at both `RunRoutes.triggerPipeline` and
`CollectorProcessor.run`, because hazard (a) — a "dry run" that deletes the customer's remote file — is the
worst failure this feature can have and **must not depend on a caller remembering**. The converse does not
hold. The job framework's unrelated `JobContext.dryRun()` (a genuine at-rest preview) keeps its own name
and shares no method on this chain.

🔴 **`strategy.ingest` is CONTAINED, never substituted, and that is the load-bearing decision.**
A survey of the lane found **~20** durable sites inside it — `PartitionWriter.write`,
`QuarantineManager.quarantine` (×3), `SchemaDriftSignal.emit`, the `BranchCommitLog` write, the park-home
parquet `COPY`, `ParkedBranches.record`, `ConsignmentGraphRunner`'s branch commits, and the unpack plugins'
scratch expansion. Substituting each is exactly the *"every sink honours a flag"* shape that misses one —
the failure mode `DryRunServices`' own javadoc names. From 2026-09-20 the pass was therefore **skipped
whole** (true by construction, but it parsed nothing). Since 2026-09-24 (`FLAT-DRYRUN-COUNTS-ZERO-1`) it
**runs for real inside three containments**, each true by construction because nothing inside the pass is
told it is a dry run — `PipelineTestRun.dryIngest`, shared with the builder's test run:

1. **Call graph** — only `strategy.ingest` runs; `commit` / `writeAudit` / `recordProvenance` stay the
   gated tail below.
2. **Filesystem** — the pass runs over **copies** of the members (a quarantine is a `Files.move` of the
   *source*) against `PipelineConfig.forScratchRun`, which re-roots every destination under a scratch root
   on the data volume (`processing.duckdb.temp_directory`, else `dirs.temp`, else the JVM temp dir); the
   root is deleted before the result returns. The pass runs under `<batchId>__dryrun`, because the branch
   commit log is keyed by batch id and lives in the explicit DuckDB temp dir when one is set — a directory
   `forScratchRun` does not re-root — so the real id could have resumed or appended to a real batch's log.
3. **Ambient emitters** — `EventLog.CONTAINED` (a `ScopedValue` consulted by `EventLog.current()`) is bound
   to a throwaway log for the pass, so the schema-drift Signal, the dedup-dropped event and the capture
   appender's lines never reach the space's ledger. One seam at the lookup, not a flag per emitter. ⚠ It
   does not follow work onto another thread. The builder's test run gained the same binding (before it, a
   test run over a drifted file raised a real WARN Signal).

✅ **So a dry run now DOES parse.** Each member is reported in the run log, keyed on the member vocabulary:
`dry run: consignment <id> member <file> → <kind> (<MemberStatus>): N parsed, M rejected row(s) — <reason>`,
where the kind (`PipelineTestRun.MemberKind`) is **`WOULD_LAND`** (`SUCCESS` in a batch that did not fail),
**`REJECTED`** (`QUARANTINED_*` — validation; the batch carries on), **`SKIPPED`** (`SKIPPED_UNREADABLE`) or
**`FAULT`** (the batch threw — even a member that parsed lands nothing; status `not reached` when the lane
had not audited it yet, which the native union lane never has before its one transform). ⚠ Two facts the
kinds pin: a **plugin decoder that throws on one file is `REJECTED`** (`QUARANTINED_UNREADABLE`, caught per
member), not a fault; and **`SKIPPED` is unreachable under a dry run today** — `SKIPPED_UNREADABLE` comes from
the unpack stage, which a dry run skips because it writes expanded copies. The batch line carries the real
parsed and would-land totals. ⚠ Residuals: the pass **copies** every member (a dry run over a large inbox
costs its size in scratch), a `route:` pipeline with a **disabled branch sink** faults in the pass (the park
hook needs `dirs.backup`, which `forScratchRun` nulls — shared with the test run), and per-record offsets are
not reported (the reject sidecar `<errors>/<file>_errors.csv` is written in the scratch root and deleted).

**It PUBLISHES, marked — and that is safe because the consumer set is CLOSED.** `ConsignmentEvent` gained a
`boolean dryRun` component (11 in total; the pre-existing 7-arg back-compat constructor absorbed the 3 main
and 24 test sites that needed no edit). There is exactly **one** fan-out — `ConsignmentAuditWriter`
constructs the terminal event and hands it to `commitListener` (→ the bus) and `terminalBatchSink`
(→ the `pipeline.batch.committed` Signal) — so the consumers are enumerable, and were enumerated:

| Consumer | Verdict |
|---|---|
| `JobService.fireOnCommit` (the `on_pipeline` chain) | **HONOURS** — the `Firing` inherits `event.dryRun()` instead of a hardcoded `false`, so the chained Job runs dry too |
| `JobService.onSignalEvent` (the `on_signal` chain) | **HONOURS**, by a *different* mechanism — it builds its `Firing` from a `Signal`, not an event, so `PipelineConsignmentSignal.emit` and `commitPayload` put the flag **in the payload** and `signalDryRun` reads it back. It **fails closed to "real"** for any payload carrying no flag |
| `AlertService.onEvent` | **REFUSES loudly** — evaluation is not a read; it fires Alerts, advances cooldowns and may open Incidents |
| `CollectorService`'s event-store bridge | **REFUSES loudly** — a `BATCH_COMMITTED` row would assert a commit that did not happen |
| `PipelineScheduler.onUpstreamCommit` | **REFUSES loudly** — a downstream *pipeline* run has no dry mode to inherit |
| `EnrichmentService.onConsignmentEvent` | **REFUSES loudly** — a recompute writes for real, over partitions the simulated batch never wrote |
| `PipelineConsignmentSignal.emit` | **MARKED, still emitted** — it is the observability record that a simulated batch happened |

⇒ No consumer silently ignores the flag, so publishing does not make the flag a lie. ⚠ This **deliberately
and narrowly overturns** the older written invariant *"cron/event/signal fires are always real"*: such a
fire is now dry exactly when the batch that caused it was simulated, and never otherwise.

⛔ **Rejected on the way here, recorded so they are not re-proposed:** rerouting `?dryRun=true` through the
job lane (where gate 2's `DryRunSinkWriter` already works) — it covers only pipelines a `pipeline`-type Job
processes, so it would silently write for flat-lane pipelines; a narrow build plus another rename (make only
the chained Job dry) — it does not deliver a dry run at all; marking all four `ConsignmentEvent` publish
sites "for symmetry" — two of them never consult any dry-run flag, so the marker could only assert "this
was real" about runs nobody asked about; and parking the work.

⚠ **The completeness guarantee is only as good as the next PR.** A fifth mutating surface added without the
same discipline silently breaks it — when reviewing changes that touch acquisition or the ingest lane, grep
for `DatasetWriteSignal.emit`, new `CollectorConnector.post` implementors, and new writers inside
`strategy.ingest`.

🔴 **The one deliberate exception: a flat-lane dry run records ONE provenance record, flagged
`simulated` (`DRYRUN-INVISIBLE-ON-FLAT-LANE-1` a — operator decision 2026-09-23).** `ConsignmentIngestor`
calls `recordProvenance(…, simulated = dryRun)` on both paths, so the no-op writes exactly the rows a real
run writes — `parse` and `sink`, one per node, keyed by the batch id the marked `ConsignmentEvent`
carries — through the same `ProvenanceStores` seam, marked the way the job/graph lane's
`PipelineJobRunner` marks its own (`ProvenanceRow.simulated`). It is **metadata only and the only write**:
no sink file, marker, status CSV, backup, quarantine — and no commit log (its constructor wrote the CSV
header, so `CollectorProcessor.ingest` now passes it no commit-log path on a dry run). ✅ **The counts are
real** (`FLAT-DRYRUN-COUNTS-ZERO-1`, 2026-09-24): `parse` = rows the contained pass accepted, `sink` = rows it
would have landed. Until then they were the zeros of a skipped pass — the row made the dry run *visible*
but did not estimate it. As on a real run the record is written for a `SUCCESS` batch only, so a dry run
whose every member is rejected shows in the run log, not the overlay. With provenance disabled (no store registered, the
`-Dprovenance.backend` default) nothing is written at all, the same as a real run. Half (b), the UI
marking, shipped 2026-09-23 (`e02eeab2`): `GET /provenance/batches` carries a per-batch `simulated`, and
the Pipeline editor's run overlay labels, dashes and annotates a dry-run batch
([pipeline editor](user-guide-elt.md)) — so a dry run is now visible **and**
marked on both lanes. Pinned by `FlatLaneDryRunTest` (a filesystem diff of the whole pipeline tree plus
the store's `batches()` read, and its real-run control) and `FlatLaneDryRunParseTest` (per-kind outcomes,
and zero side effects against probes the real pass trips — the quarantine move and the DB-export
watermark, each with its real-run control).

**References.** Provenance: the archived plan
[`archived-documents/plans-archive/pipeline-dryrun-design.md`](../../../../archived-documents/plans-archive/pipeline-dryrun-design.md).
Route contract: `docs/api/openapi-v1.json`. Release note:
[`okf/product/module/api/plan-api-stability.md`](../api/plan-api-stability.md).
Pinned by `FlatLaneDryRunTest`, `JobServiceTest.aChainedOnPipelineFiringInheritsTheUpstreamBatchesDryRunFlag`
and `ControlApiAsyncV1Test.pipelineTriggerDryRunImpliesSkipPostAction`.

## Per-Step `enabled` — park and drain

Switching a Step off means different things per execution lane (the lane map is
[§ Execution lanes](#execution-lanes) above), and conflating them is the whole design problem this
concept solves: in the **scratch lane** a disabled node is an in-memory **bypass** (it produces
nothing, everything downstream is inert — correct there, deliberately unchanged), while on the
**ingest lanes** a bypass would *lose rows* — so there a disabled Step is a durable **pause**: the
Consignments that reach it **PARK**, and a later **drain** completes them.

Decision of record: **D-13**, ELT Phase 4 S4. Shipped 2026-08-28/29 across five slices; the slice plan is
[`elt-s4-park-drain-plan.md`](../../../../archived-documents/plans-archive/elt-s4-park-drain-plan.md).

### The durable home: `processing.disabled_steps`

One key, one home — a pipeline-level list of Step ids:

```toon
processing:
  disabled_steps[1]: sink__d1
```

`PipelineLift` overlays `enabled: false` onto each named node, so the canvas and the scratch lanes get the
behaviour for free; `PipelineEditable.toMap` **derives the list back** from node state on save. Rejected:
a per-node key in every lower branch (a 7-branch × 2-mirror drift class), and a graph-native-only home (the
graph write path that could author it is retired, so it would be dead config by construction).

⚠ `PipelineEditable.toMap` rebuilds node configs from the RAW map by ownership rules, which **drops any
lift-time overlay** unless carried explicitly. The `enabled` flag is carried explicitly for exactly that
reason; any future overlay hits the same trap.

⚠ `enabled` is a **structural** node field, not a declared `NodeAttribute` — `PipelineNode.enabled()` reads
`config["enabled"]`. Declaring it as an attribute would render it on every sink, including flat-lane ones
the save gate refuses, and would force the UI to mirror the arming rule.

### Arming — where a park is even possible

At rest the executor is seeded **downstream of parse/map**: only the `route → sinks` subtree runs as graph
nodes. So `StepDisableArming` relaxes exactly one shape — a **sink strictly inside an armed `route:`
subtree** — and refuses, at SAVE and at `prepare()`, never as a silent skip at rest:

1. any `disabled_steps` on a pipeline with **no armed `route:`** (the flat lane has no park boundary; the
   answer is dry-run / run-to-here). Lifting this is Phase 6's non-route parity project;
2. disabling `collect`/`parse`/`map` or anything at-or-upstream-of the route node (the executor is seeded
   below them; the answer is `active: false` or the Stage-B half-pause);
3. disabling the `transform.route` node itself (the divert's engagement anchor);
4. an unknown Step id (a typo must never become a silently-enabled Step);
5. **all** branches (`active: false` is that ask);
6. **no park home** (added 2026-08-29). `dirs.backup` is OPTIONAL — `PipelineConfigParser` reads it with a
   plain `get` — but `ConsignmentIngestor.parkSource` parks under `<backup>/parked`, so without it the pipeline
   arms, runs, and dies at the park with an NPE the operator reads as `park failed: null`, **one batch
   every cycle**. That is the arms-then-fails-forever shape this whole list exists to convert into an
   authoring-time answer. ⚠ `refusals()` takes `parkHomeConfigured` as a **required** argument rather than
   an overload defaulting to `true`: a caller that cannot answer must not be allowed to arm a pipeline with
   nowhere to park. Draft-map callers use `StepDisableArming.draftHasParkHome(dirs)`, which mirrors the
   parser exactly — absent and blank are both "no home".

⇒ **Consequence for `DrainCommand`:** a blank `backupPath` on a member is no longer read as a 1→N unpack
expansion. It never was one — it means `dirs.backup` was unset — and no armed batch can now be in that
state. Reporting it as an unpack problem described a fault the operator did not have; the expansion test
is now the JAR-style `archive!entry` address and only that.

⚠ `StepDisableArming.parkableSinkIds` **hand-mirrors the lift's `sink__d<i>` id grammar**. That is legitimate
only because an armed `route:` pipeline is single-schema (so the suffix is empty), and it is pinned verbatim
by `PipelineLiftTest.parkableSinkIdsMatchTheLiftedGraph`. Extend both together.

### Park — the boundary IS the materialisation boundary

`PipelineExecutor.ParkWriter` is a hook invoked when the walk reaches a **disabled SINK with a live inbound
relation**. It is `null` on every scratch path, so the bypass stays. At rest `ConsignmentIngestStrategy` supplies a
lambda that `COPY`s the branch relation to `dirs.backup()/parked/<batchId>__<nodeId>.parquet` and records it
in the batchId-keyed `ParkedBranches` stash (the `UnpackOrigins` lifecycle idiom — threading `IngestOutcome`'s
eight constructor sites was rejected).

`ConsignmentIngestor.parkSource` then does **almost nothing** of the normal commit tail, because a parked
Consignment is *uncommitted*: no DuckLake register, no §11.3 output registration, no markers, no fingerprint
stash, no watermark. What it does:

1. writes the manifest with `parkedAt` (node ids) + `parkedTables` (nodeId → Parquet path) and every member
   `PARKED` — the inspectable record of where and why the Consignment stopped;
2. moves each plain member's **original** into the park home (`dirs.backup()/parked/<poll-relative-path>`,
   recorded as `MemberEntry.backupPath`) so the next poll does not re-ingest it;
3. writes the **`ParkedCommit` sidecar** (`<parkHome>/<batchId>__pending.json`) — see below;
4. records `FileStage.PARKED` and leaves the batch status `PARKED` → Signal `pipeline.batch.parked`
   (neither committed nor failed).

The **branch commit log is KEPT** on park (it is the drain's resume record); it is deleted only when a batch
is fully committed.

⚠ **An unpack EXPANSION product's original stays in the inbox.** `batch.max_files: 1` splits an archive
across batches, so moving the shared original would strand its siblings; that original re-expands next cycle.
This is the crash posture — idempotent, wasteful, honest — and it is why a drain refuses such a batch.

### `ParkedCommit` — why a sidecar exists at all

A parked batch's **enabled** branches already committed their files. Their `PartitionOutput`s, lineage rows
and event-time bounds live **only in the ingest JVM's memory**, and `BranchCommitLog` records branch *ids*
and nothing more. Without persisting them, a drain in a later process could not run `finalizeSource` — the
register, `manifest.outputs` and the §11.3 registry would each record **half a batch**, silently. So park
writes them beside the park tables, and the drain consumes and deletes them.

### Drain — the commit tail, resumed

`DrainCommand.run(toonPath, batchId)`, reached through `POST /runs/{name}/drain` (`canOperateRuns`, the
structural sibling of `reprocess`). **Not a re-ingest and not a graph re-walk**: a route branch's table is
complete when its sink is reached, so nothing upstream needs re-running and no transform remains between the
park table and the write. Drain registers each park table with `read_parquet`, writes it through the ordinary
`IngestSinkWriter`, and then runs the ordinary `ConsignmentIngestor.finalizeSource` for the **whole** batch over
the union of the sidecar's outputs and its own.

Refusals (loud, never a partial — the `guardAgainstCompactedOutputs` posture); the route answers 409 with the
reason verbatim, and 404 only for a batch with no manifest:

- the batch is not parked;
- a parked Step is **still listed in `processing.disabled_steps`** — config is the truth about "is this Step
  on", never the manifest;
- a parked Step no longer names a node in the lifted graph;
- a park table or the sidecar is missing;
- the batch carries unpack-expansion members (their original re-expands and re-parks as a fresh batch, so
  completing the old one would commit rows the new one is about to write again).

Two mechanisms worth knowing before changing this code:

⚠ **The coordinator's `SOURCE` row is not this lane's finalisation signal.** The ingest path passes a no-op
`SourceFinalizer` to `ConsignmentGraphRunner` (finalisation belongs to `ConsignmentIngestor.commit`, once the batch
outcome exists), so a park run has *already* recorded `SOURCE` having finalised nothing. Drain therefore uses
`BranchCommitCoordinator` only for the durable, idempotent per-BRANCH skip, and runs the commit tail itself.

⚠ **The restore window.** Every poll-relative computation in `finalizeSource` — manifest `rel`, backup
destination, marker path, ledger key, stage rows — assumes the original is in the inbox. Drain therefore
moves it back from the park home immediately before finalising, rather than mirroring five relativisations.
The file is in the inbox for the length of the commit tail, which then moves it to backup and marks it. A
concurrent poll or a crash inside that window re-ingests the file — the same idempotent
`OVERWRITE_OR_IGNORE` posture a crash mid-commit has always had.

### The surfaces

- **Authoring** — the Step switch in the definition drawer's identity strip, offered only where the engine
  can park. The UI decides that **structurally**: a sink Step whose inbound edge from the `transform.route`
  Step carries a **`route:<key>`** relation (never the lift's `sink__d<i>` spelling, which the editable
  model never sees). ⚠ The RELATION is the whole test. A destination no branch names — the primary at
  `dirs.database`, in a config that also declares branches — hangs off the route node too, but by a plain
  `data` edge, because both lifts pair branches to sinks BY DATABASE and find no key for it. The first cut
  keyed on "inbound from the route node" and so offered the switch on a Step whose disable
  `StepDisableArming` refuses; only driving a real routed pipeline in the preview surfaced it. Switching on **deletes**
  the `enabled` key rather than writing `enabled: true`, so the lower derives an empty list. A switched-off
  Step reads `disabled` on the canvas — pause glyph + icon + label, never colour alone — computed **first**,
  because an author's explicit decision outranks every derived status, and it raises an `info` finding in
  Validate.
- **Operating** — a **Drain** row action on Run detail's Batches tab, visible only on a `PARKED` row (the
  engine refuses every other state, so offering it elsewhere would be an affordance that predictably fails).
  The 409 reason reaches the operator verbatim, because "re-enable the Step first" *is* the next step.
- **Inspecting** (PARK-1(a), execution residuals X3, 2026-09-02) — a `PARKED` row on
  `GET /runs/{name}/batches` carries the manifest's `parkedAt` (the Step ids it parked at) and
  `parkedTables` (Step id → durable park-table path), and the Batch detail dialog renders them as a
  *Parked at* table under a warning that the Consignment is uncommitted. **Merged at the ROUTE
  (`RunRoutes.withParkDetail`), not the ledger**, deliberately: the `_batches_` header has five mirrors
  and is projected by two status backends (file + db), so a new column there is a ledger-format change
  across every writer and reader; the manifest is already the authoritative park record and the route
  reads it by the same `dirs.manifestsDir` + batch id the drain does. Non-parked rows are untouched; a
  parked row whose manifest is missing/corrupt keeps its ledger fields — the drain's 404/409 already
  names that gap, and one broken Consignment must not fail the list. ⚠ `AuditRow` is typed
  `Record<string, string>` because a ledger row IS strings; these two keys are the one structured
  exception, narrowed locally in the dialog and excluded from its key/value summary (they rendered as
  `[object Object]` otherwise). ⚠ The BACKLOG row's own remedy — “needs a new read route” — was wrong;
  the premise (nothing served) was right. Pinned by `ControlApiTest.parkedBatchRowsCarryTheirManifestsParkDetail`
  (real HTTP, hand-written ledger row + manifest, incl. the missing-manifest case) and
  `batch-detail.dialog.spec.ts`.
- **Deliberately not built:** re-enabling a Step does **not** auto-drain (operator decision, 2026-08-29) — a
  config save must not start batch work as a side effect.

### References
- [Design](architecture-elt.md) — the graph IR, lift and executor this rides on.
- [Consignment status flow](architecture-elt.md) — where `FileStage` and the batch ledger fit.

### Decision 2026-09-06 — expansion members stay refused at drain, but the refusal names its siblings

The shared archive original cannot move while sibling batches split off the same archive still need it, and
no cross-batch member registry exists to know when the LAST sibling has drained. **Decided:** keep the refusal
and sharpen it — name the archive and the sibling batch ids so the operator drains them together. The full
lifecycle rule ("the original moves when its last sibling drains") is recorded here as the shape of the
eventual fix, not scheduled. Build: `DrainCommand.refuseExpansionMembers` + test (BACKLOG §3 `PARK-MSG-1`).

## Commit steps — the work a commit needs before it is done

(`BASIC-ELT-PROFILE-1` BE-1a, as built 2026-11-19; replaces the single `commitStep` of `LA-DATASET-COMPACT-2`.)
`CollectorService` keeps one **ordered registry of named commit steps** for the JVM:
`CollectorService.commitStep(name, order, phase, step)`. Steps of a phase run in ascending `order` (a tie: by name),
each after the one before has returned. Registering a name again replaces it (each ControlApi re-registers on attach).
A step that throws is logged and the next step runs; nothing throws out of the publishing thread (the bus also isolates
its subscribers, but that would skip the remaining steps). There are two phases:

| Phase | When | Handed | Must not |
|---|---|---|---|
| `COMMIT` | Every Consignment (SUCCESS or FAILED, dry runs included; the step filters), on the publishing thread, before the Space's Jobs are handed it. The subscriber is registered in the constructor, before `JobService.start()`. | that commit | be slow, start a run, or wait for ingest to end: the thread is inside the Consignment's `IngestActivity` scope and holds the run's claim |
| `RUN` | Once per run of a Pipeline that committed: after the run's last Consignment, outside every ingest scope, while the run's claim is still held (`commitStepRun`, around `runPipeline`'s and the poll's `runOne` run). A SUCCESS commit published outside a run (an enrichment's, a replay of rejected records, one Consignment each) runs it at once. | the run's last SUCCESS, non-dry commit | assume the Space's Jobs have not seen the commits; they have |

- **Why two phases.** A run commits once per Consignment, and `collector.consignment.max_files` (legacy `processing.batch`) defaults to `1`, so a run of
  N inbox files commits N times. Work that should happen once per run (an index build, the Expectations) would
  otherwise run N times. It also cannot run on the publishing thread: a Link Analysis index build waits while
  `IngestActivity.active()`, and that thread is inside its own Consignment's ingest scope, so the build would wait for
  itself until its timeout. `COMMIT` keeps the old guarantee (the work finishes before the Space's Jobs see the commit)
  for quick work such as compaction.
- **The import's steps** (`ImportCommitSteps`, control plane): `import.compaction` (100, `COMMIT`),
  `import.follow_on` (200, `RUN`: each `ImportFollowOn`, e.g. Link Analysis's `index`), `import.expectations` (300,
  `RUN`). They act only for a Pipeline Add data wrote for that Dataset (`OnboardImport.isOurs`), whose `import_steps`
  names `follow_on` (a Pipeline with no `import_steps`, written before BASIC-ONBOARD-1 P1, runs all four steps). Each
  claims the Dataset in `OnboardImport.IN_FLIGHT` and skips it when the claim is held. Add data's own run holds that
  claim and calls the same list in-run (`ImportCommitSteps.runAll`), so the steps run once, in the same order, with
  the same outcome shapes (`compaction`, `index`, `expectations`).
- ⚠ **A Standard feed's `la.index.build` Job still runs** (`DatasetFeed.indexJob`, offered by *Keep this up to date*).
  It now overlaps the `import.follow_on` step on a scheduled run: the follow-on's build waits for the Job's build
  (`LinkIndexFollowOn`, wait budget -1) and re-plans, so the index is not built wrong, only checked twice. It also
  still runs while `import.compaction` merges files, as before. Retiring the Job is BE-1 follow-up work, not done here.

## Failure and recovery, per lane

**One recovery affordance per lane, and they are not interchangeable**: ingest has **reprocess** — which
also *retracts that Consignment's dedup-ledger claims*, or re-ingested rows would be permanently
suppressed; a parked Consignment has **drain**, which does not re-walk but registers the park tables and
runs the real finalisation; the at-rest lane has **replay** — ⚠ backed by an in-memory, capped map, so a
replay of an evicted run, or of any run after a restart, is a 404 ([plan-elt.md](plan-elt.md) §EXE.2.3).

⚠ **Reprocess is whole-batch only**, stated verbatim in the source. Record-level replay of one file's
rejected rows is the separate `POST /runs/{name}/replay-rejects` route, with `processing.reject_mode: eject |
all_or_nothing` as the per-pipeline choice (both under *Execution lanes* above).

Park (a durable pause, never a bypass, with six arming refusals) is under *Per-Step `enabled`* above; the
orphan output-store gate (default-on in every space since 2026-09-01) under *Execution lanes*.

⚠ **Cancelling a cron handle un-arms only *future* fires.** A hanging Job is bounded since 2026-10-06 by
**`JobDeadline`** (platform-services R1): every Run gets its Job Type's deadline (default 30 min, the
long-running types the ceiling), overridable by `deadline_seconds:`, capped by `-Djob.deadlineCeilingSeconds`
(default 24 h); on expiry the Run's thread is interrupted and the Run is recorded `FAILED`. Contributed Steps
keep their own watchdog.

## Post-sync step chains (the open DAG)

After sync a Consignment's data is a **table**; everything downstream of the sink reads it **through
Consignment info**, never through a piped relation. As-built after the open-DAG plan (stages 2/3/5
shipped 2026-08-29, stage 4 shipped 2026-08-31, stage 6 refuted, the remaining questions ratified
2026-09-06). Vocabulary follows [`GLOSSARY.md`](../../../../GLOSSARY.md): **Pipeline**, **Consignment**,
**Step** (per-Consignment) vs **Job** (cross-Consignment, [job-vs-step](../processor/architecture-processor.md)),
**Collector**, **Dataset**. (The "§n" references in this section are to its own numbered subsections.) The
Job-side decisions of this lane (registry-not-glob reads, declared composability, a summary-write failure
fails the Run) are in [../processor/spec-jobs.md](../processor/spec-jobs.md).

### 1. The dataflow model (operator, 2026-08-29)

> *Sync contains data; Consignment information flows, with added information from the previous step(s).
> Any step can get Consignment data from Consignment info only.*

Two lanes, two contracts — do not blur them:

| Lane | Carrier | Contract | Authored as |
|---|---|---|---|
| **In-batch** (parser → filters → map → dedup → sink) | scratch relations inside the run | `PipelineNodeExecutor`, `steps:` chain | the canvas / flat TOON |
| **Post-sync** (this section) | the **Consignment output registry** | `ConsignmentProcessor` + `ProcessorContext` | a `type: consignment.process` ⚠ *(this read `consignment_process` with an underscore until 2026-09-09 — the registered id is dotted, and copying the old spelling authors an UNKNOWN job type)*`consignment.process` Job's `processor` / `chain_config` params |

⛔ **The sink stays terminal in the batch graph.** The first draft proposed `SINK_PERSISTENT` emitting
`DATA` so a step could chain after it on the canvas. Refuted: that pipes the *scratch* (pre-write,
pre-commit) relation, and the sink write is driven by the commit coordinator, so the reader would run
before anything is durable. The post-sync lane is the Consignment-addressed one, read **after commit**.

### 2. The carrier: `ProcessorContext` over the registry

`platform/inspecto-engine/src/main/java/com/gamma/consignment/ProcessorContext.java` is the Consignment-scoped
façade the framework resolves and hands to a processor: `consignmentId()` (resolved by the framework,
never the author), `outputs()` (every file this Consignment wrote — the **addressing authority**),
`read()` (a `ConsignmentReader`: read-only SQL over lazy views of this Consignment's files),
`summaries()`, `tables()`, `config()`, `log()`, `signals()`.

- ⛔ **No raw `Connection` — ever.** Rejected because it makes the read-modify-write the append-only
  path forbids trivially expressible. `SandboxConsignmentReader.frameworkConnection()` is
  **package-private** for the writer's use only; do not widen it.
- The registry (`DbConsignmentOutputStore`) is on by default (`-Dconsignment.outputs.backend=duckdb`).
  With it `none`, `GET /runs/{name}/outputs` (`RunRoutes`) says so rather than returning an empty list
  that would read as "nothing written".

#### Schema propagates; it is never re-declared

`platform/inspecto-etl/src/main/java/com/gamma/etl/TypeFlow.java` derives a Step's output schema by DuckDB
`DESCRIBE` over the *identical* SELECT it executes, without executing it — DuckDB is the type authority
and the Parquet footer carries the same types. So a derived table's schema **is** the base table's schema
after the SQL: nothing declares it, nothing can drift. The declared `raw.fields[]` belongs to the
**parser** only, where text becomes typed. ⚠ A post-sync step reads Parquet, so it is `TypeFlow`'s
*typed-source* case, not the all-VARCHAR CSV seed.

### 3. Stage 2 — derived tables (`ctx.tables().emit(...)`)

`DerivedTable(name, sql, partitionBy)` · `DerivedTableEmitter` (`@PublicApi`) ·
`GuardedDerivedTableEmitter` (validates + collects) · `DerivedTableWriter` (materialises after
`process()` returns and registers the files onto the **same Consignment**) — all under
`platform/inspecto-engine/src/main/java/com/gamma/consignment/`.

The four registry contract points, settled:

| Field | Decision |
|---|---|
| `tableName` | namespaced `<name>__derived` (mirrors `__summary`) so it can never collide with the sync tier; held to `[A-Za-z_][A-Za-z0-9_]{0,127}` because it becomes a **directory** — path jail at the seam third-party text enters |
| `producer` | the processor id, pinned end to end — ownership recorded, not inferred |
| `partitionKey` | one file per distinct value; what `compact` merges on and `ConsignmentSelector` prunes with. ⚠ A partition **value** reaches a path too — jailed, refused, never escaped. ⚠ Left blank it silently disables both compaction and pruning |
| `schemaFingerprint` | `DESCRIBE` over the produced relation — never authored (§2) |

Gotchas found by building it:

- 🔴 **Author SQL clears `SqlGuard`; a leading-`SELECT` shape check is not a substitute.** Falsified:
  `SELECT * FROM read_csv('/etc/passwd')` was admitted by the shape check on the same unsealed sandbox.
- ⚠ **The writer takes the framework's `ConsignmentReader`, not a fresh connection** — the author's SQL
  names lazy views that exist only on that sandbox.
- ⚠ **A derived table is not readable within the run that emits it** (materialised after `process()`
  returns, matching summaries). The *next step* sees it via the registry — the chain working as designed.
- `GuardedSummaryEmitter`/`SummaryEmitter` remain the *composable-measure* tier (`count` mandatory,
  every measure declares how it composes — refused rather than guessed). Derived tables sit beside it,
  not instead of it.

### 4. Stage 3 — the ordered chain

`ConsignmentProcessJobType` (`platform/inspecto-engine/src/main/java/com/gamma/job/`): the `processor`
parameter is **one id or an ordered comma-separated chain** (`mask,rollup,report`); `chain_config` is an
optional, positionally aligned JSON array of `{"config": {...}}` (`ParamType.JSON`, the vocabulary's
first nested shape; `ProcessorContext.config()` defaults to an empty map for standalone runs and
pre-existing third-party implementers).

- 🔴 **The one mechanism that makes it a chain: the registry is re-read per step** and a fresh
  `ConsignmentReader` built from that list, so step N sees the Consignment as step N-1 left it.
  Falsified: hoisting the read out of the loop fails step 2 with `Catalog Error: Table with name
  mid__derived does not exist`.
- 🔴 **Every step (and the `chain_config` length) is resolved before any step runs** — the data path
  is append-only, nothing rolls back, so an unresolvable id fails with "nothing has run".
- **Order is authored, never inferred.** Two steps may both read the base with no declared dependency.
- ⚠ **A repeated id is kept, not de-duplicated** — the second run sees a different Consignment state.
- **Mid-chain failure stays append-only (decided 2026-08-29).** Earlier steps' tables remain
  registered and `LIVE`; ⚠ the registry alone cannot distinguish a partial chain from a complete one —
  the Run's status is what says which.
- Save-time half: a hand-authored job whose `chain_config` does not align with its `processor` chain is
  refused at save (`ConsignmentProcessJobType`, CHAIN-CONFIG-1). Only the `params:` block is examined —
  a chain reached through `args:`/`bind:` is checked at run.
- 🔴 **The engine half of CHAIN-CONFIG-1: a `chain_config` value must be a SCALAR, and the engine
  accepts-then-CORRUPTS anything else.** `chainConfigsOf` reads each entry into a `Map<String, String>`, so
  `{"columns": ["a","b"]}` **saves, runs, and arrives at the processor as the string `"[a, b]"`** — no
  refusal anywhere. That is why the authoring surface refuses a non-scalar rather than trusting the engine.
  *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*
- ⚠ **A null value used to NPE instead of naming the key** (`Map.copyOf` rejects nulls), so it is refused by
  name at authoring with *"omit the key instead"*.
- ⛔ **A surplus `chain_config` entry is never dropped to line the two sides up.** It becomes a row with a
  blank id and the save refuses — discarding an authored config to tidy an off-by-one is the exact failure
  this surface exists to prevent, so the lossless path is the tested one.
- 🔴 **The refusal is marked touched at seed time**, because a `mat-error` on an untouched control renders
  nothing: without that, Save would refuse **with nothing on screen**.
- **It fails open, deliberately.** If the parameter is absent from the descriptor, or the `chain_config`
  cannot be represented by the structural editor, both raw fields are left exactly as they were — *a
  structural editor that cannot read a value must not be the thing that rewrites it*. Unmodelled element keys
  travel verbatim; only `config` is rewritten.
- ✅ **The processor-id picker SHIPPED 2026-09-10 (`PROCESSOR-CATALOG-ROUTE-1`).** `GET /jobs/processors`
  (`JobRoutes`, beside `/jobs/types` and `/jobs/expressions`) serves the registered `ConsignmentProcessor`
  ids from `JobService.processorCatalog()`, and the chain editor's *Processor id* field is an autocomplete
  over them. It **suggests without constraining** — no validator consults the catalog, because a processor
  jar can be deployed *after* its Job config is authored, so a catalog-backed refusal would lock an author
  out of a valid chain.
  ⚠ **The catalog is EMPTY on a stock install and that is correct, not a defect.** The product ships **zero**
  `ConsignmentProcessor` implementations — only the `tools/templates/processor` scaffold, whose services file
  still carries an unexpanded `{{packageName}}` placeholder — so entries appear only where a third party has
  put one on the classpath. That is why the empty state renders an explicit *"nothing to suggest — type the
  id"* hint rather than an inert dropdown, and why the value accrues to deployments with a deployed
  processor rather than to the stock product. `ProcessorCatalogTest` pins the zero-implementations fact so a
  future change to it is visible.
  ⛔ **Job Packs cannot contribute a processor**, so there is no pack overlay to merge into the catalog:
  `JobPackManager` registers exactly four SPIs (`JobTypeProvider`, `ExpressionProvider`, `PipelineNodeType`,
  `PipelineNodeExecutor`) and not this one. The `ServiceLoader` set is the whole set. ✅ The scaffold's own
  `tools/templates/processor/README.md` §"Deployment differs from a Job Pack" already states the operator
  half of this — **the jar goes on the engine classpath, not into `-Djobs.packs.dir`**, with no hot deploy
  and no isolated classloader — so the two halves agree. (`packs-dev/<id>/` is only the scaffold's gitignored
  *generation* directory, not a deployment target.)
  🔴 **Two ids that can never be selected are excluded from the vocabulary, not served**: a blank one (the
  lookup matches only a non-blank id) and — less obvious — **one containing a comma**, because `chainOf`
  *splits* the `processor` param on commas. ⚠ Do not confuse that with a **repeated** id inside an authored
  chain, which is deliberately legal (one processor run twice); the catalog's `shadowed` flag is the
  different case of two *classes* claiming one id, where only the first is reachable.

### 5. Retention, merge and reprocess — "connect, don't invent"

The ownership/retention model was **already shipped** before the plan asked for it; a derived table
merely arrives with a `partitionKey` and a `State`:

| Wanted | Shipped |
|---|---|
| retention of replaced files | `retire_superseded` maintenance task, gated on `retention_days` ≥ 1 |
| small-file merge per partition key, on schedule | `compact` → `PartitionCompactor` (`com/gamma/job/`) |
| lifecycle | `ConsignmentOutput.State` = `LIVE` / `SUPERSEDED` / `COMPACTED_AWAY` |
| readers not seeing retired files | `ConsignmentSelector.resolve(glob)` = glob MINUS superseded/compacted paths |

- 🔴 **The reprocess cascade is FREE — proven by test, not assumed.** `DbConsignmentOutputStore.supersede`
  is keyed on the Consignment (`WHERE consignment_id = ? AND state = 'LIVE'`); a derived table registers
  under the same `consignmentId`, so base and derivative both go `SUPERSEDED`. No lineage edge exists or
  is needed. ⚠ The cascade **marks** stale — it does not recompute; the chain must re-run per Consignment.
- 🔴 **The pre-compaction window IS the reprocess window.** `ReprocessCommand` (`com/gamma/inspector/`)
  works only because a Consignment's rows live in *its own* files; compaction destroys that 1:1 property,
  after which reprocess **refuses** (a no-op unlink + re-ingest would duplicate rows). The `compact`
  schedule ("maybe 7 days") is therefore the reprocess SLA — accepted trade (operator, 2026-08-29).
  ⚠ `supersede()` moves only `LIVE` rows; `COMPACTED_AWAY` must keep its state as the evidence. A javadoc
  claiming a "partition-rewrite path" existed was corrected: there is none.
- Deferred by decision: a per-generation **derivation edge** (would buy partial invalidation + lineage
  display; nothing shipped requires it). `EdgeKind.FEEDS` in the catalog is design-grain, not instance-grain.

### 6. `read_parquet` over registered paths (operator, 2026-08-29)

🔴 **`SqlGuard` here is NOT a security boundary** — a processor is arbitrary Java on the classpath. What
it protects is the **addressing invariant**: a `COPY … TO` in author SQL writes a file the registry never
learns about, invisible to `ConsignmentSelector`, `retire_superseded`, `compact` and the next step's
`outputs()`. Reading a *registered* path keeps the invariant, so it is allowed:

- `DbConsignmentOutputStore.isReadable(path)` = registered **and** carrying a `LIVE` row. ⚠ Readability
  is per-PATH, state is per-registration — a recompute rewrites a stable path in place, so a path may own
  a `SUPERSEDED` row beside a `LIVE` one and still be readable.
- `GuardedDerivedTableEmitter` matches only `read_parquet('<literal>')` (name, whitespace, one
  single-quoted literal), verifies each against the registry, **masks it to an identifier**, and runs
  the masked text through `SqlGuard` unchanged. Anything richer stays in the text and is refused —
  **fail-closed by construction**. `ReadablePaths.NONE` is the default for callers without a registry.
- ⚠ Validation runs on the masked text while the ORIGINAL executes — sound only because they differ
  solely by individually verified substrings, which is why verification precedes masking.

### 7. Stage 5 — a contributed in-batch Step is authorable via `steps:`

🔴 **The premise was wrong usefully:** `RecipeCompiler` has no production caller (a converter/parity
artifact). The load-bearing path is the flat file's `steps:` chain, which `PipelineLift` lifts as
`"transform." + kind` with config verbatim — open all along; only two gates were shut:

- `PipelineEditable.LOWERABLE` (`com/gamma/pipeline/`) now admits a registered **non-built-in**
  `transform.*` whose `stepKindOf` resolves; `stepsOf` keys by `stepKindOf(type)`. ⛔ **CONTRIBUTED only**:
  admitting every `transform.*` would silently make `split`/`select`/`derive`/`validate`/`merge`
  authorable (pinned by a test walking all five).
- The parser gate: `PipelineConfigParser` (`inspecto-etl`) still refuses an unknown kind **at load**,
  through the `StepKindRegistry` ServiceLoader seam (`inspecto-etl`), implemented by `NodeTypeStepKinds`
  → `PipelineNodeTypes.isKnown(...)` in the engine. **No provider ⇒ "no"**, so the lean core is unchanged.
  ⚠ Relaxing to a safe-identifier shape was tried and rightly broke `refusesAnUnknownKind` — it traded a
  load-time refusal for a run-time one. ⚠ The test provider vouches for exactly ONE obscure kind; a
  permissive one is module-global.

A **post-sync** plugin step is a different contract (`ConsignmentProcessor`, ServiceLoader, `packs-dev`
example `acme.masker`) and needed authoring (§8), not a new SPI.

### 8. Stage 4 — the lane in the UI (shipped 2026-08-31)

Read side: `GET /runs/{name}/outputs?consignmentId=` and the Batch-detail registered-outputs section.
Authoring side: `inspecto-ui/src/app/modules/admin/jobs/job-chain-editor.component.ts` + `job-chain.ts`,
rendered in `job-form.dialog.html` under `@if (chainEngaged())` — a structural editor over the
`processor` / `chain_config` param pair. ⚠ The archived plan's §6.4 row still says "hand-edited TOON";
that claim is STALE as of 2026-08-31.

### 9. Decisions ratified 2026-09-06 (operator)

- **Q1** — a post-sync step's table **registers as a Consignment output**; the retire/compact/registry
  lifecycle is reused (§5).
- **Q2** — a chained step sees the Consignment **as the previous step left it** (answered by §4's as-built).
- **Q3** — when step A re-runs, B's output is **superseded through the existing revision model** (§5).
- **Q4** — a per-Consignment matrix is a **Step**; a cross-Consignment report is a **Job**
  ([job-vs-step](../processor/architecture-processor.md)).

### 10. Refuted and deferred

- ⛔ **Stage 6 "parser output-schema publication" — REFUTED**: `ParserPlugin.preview()` already returns
  `ParseResult.Table.columnTypes`, `POST /parsers/{id}/preview` forwards it for every registered parser,
  and the parse-definition pane's `onPreviewed` offers it as the grid's **Auto** mode. It is *pre-map,
  sample-derived, advisory*; `TypeFlow` is *post-map, derived, authoritative* — siblings, not one gap.
- ⛔ Sink emitting `DATA` (§1). ⛔ Raw `Connection` on `ProcessorContext` (§2). ⛔ Opening
  `RecipeCompiler`'s verb switch (§7 — no caller).
- Deferred: derivation edge / partial invalidation (§5); folding Map into the sink (blocked by `sinks:`
  fan-out keyed on `database`). The BACKLOG row `OPEN-DAG-S1` the plan's §5 filed was CLOSED the same
  day (2026-09-06) on grounding — stage 1 (authored chains) had already shipped as §4 + §8, so nothing is owed.

### References
[consignment-addressing](spec-consignments.md#part-a--addressing) (Selector, revision model) ·
[consignment-status-flow](architecture-elt.md) · [node-types](spec-pipeline-model.md#pipeline-node-types--the-node-type-plugin-seam) ·
[transforms-seams](spec-pipeline-model.md#transforms--modularity-seams) · [plugins](spec-ingest.md#plugin-ingester) ·
[step-catalog](spec-step-catalog.md) ·
[jobs](../processor/spec-jobs.md) · [job-vs-step](../processor/architecture-processor.md)

**Provenance:** distilled from
[`open-dag-pipeline-design.md`](../../../../archived-documents/plans-archive/open-dag-pipeline-design.md)
(design 2026-08-29, stages shipped 2026-08-29/31, Q1/Q3/Q4 ratified 2026-09-06).

### Compaction is the only operation that can duplicate rows

Every other write path is append-or-replace. Compaction merges several files into one, so it has both failure
modes available — and they are not symmetric: **unlink-first loses data, which is worse than the duplicate**.
Hence the protocol: stage the merged output under a `_v2/` sibling, flip **one** marker to reveal it, then
unlink the originals lazily. A crash before the flip leaves the originals authoritative; a crash after leaves
the merged copy authoritative; neither leaves both readable. The operation must therefore be **re-runnable on
an already-compacted partition**, because a retry cannot tell which side of the flip it died on.

**The horizon is per-table and `none` must remain a legal value.** A compaction horizon is an SLA on how far
back reprocessing stays cheap, not a storage-tuning knob — so a table whose rows are never reprocessed is
entitled to `none`, and one feeding a 30-day correction window is entitled to a long horizon. ⛔ Do not make it
a single global setting.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

## Code pointers

The PIP spec's as-built pointer table ("§" references below are [plan-elt.md](plan-elt.md) sections).
⚠ **Cite the symbol, not the line** — and in this area, **check the symbol still exists**: twenty-plus
current citations named classes the 2026-08-31 rename removed (plan §EXE.5.3 item 7).

| Concern | Where it lives | Gap |
|---|---|---|
| The Job engine | `JobService` | 🔴 Javadoc contradicts its own default (§EXE.2.5) |
| The Job type registry | `JobTypeProvider` / `JobTypeRegistry` / `JobTypeDescriptor` | The maintenance descriptor was wrong both ways (§EXE.2.2) — ✅ fixed 2026-09-09 (§EXE.5.3 item 2) |
| The legacy type enum | `JobType` | Deprecated; 4 constants; ⚠ its javadoc names a retired file and key |
| Job config and templates | `JobConfig`, `JobTemplate` | ⚠ The config javadoc omits two recognised trigger keys |
| The Run bound | `JobService.DEFAULT_MAX_CONCURRENT_RUNS`, the permit holder | Deliberately not a semaphore ([spec-consignments.md](spec-consignments.md#part-b--concurrency-the-four-layer-hierarchy)) |
| Per-job non-overlap | `LockingRunner` | — |
| The ingest engine | `PipelineScheduler`, `CollectorService` | 🔴 **No test class exists at all** |
| Per-pipeline exclusion | `PipelineRunGuard` | Non-reentrant on purpose ([spec-consignments.md](spec-consignments.md#part-b--concurrency-the-four-layer-hierarchy)) |
| Pipeline triggers | `PipelineTrigger` (6 kinds + a scheduler enum) | ⚠ The glossary's trigger entry omits a shipped kind |
| The commit bus | `ConsignmentEventBus` | ⚠ Cited under its **old** name in a concept page |
| Commit steps | `CollectorService.commitStep` / `CommitPhase` / `commitStepRun`; the import's: `ImportCommitSteps` | — |
| The lane fork | `ConsignmentIngestStrategy.admittedLift` / `flatReason` | — |
| The whole-pipeline dry run | `RunRoutes.triggerPipeline`, `CollectorProcessor.run`/`ingest`, `ConsignmentIngestor.process`, `PipelineTestRun.dryIngest`, `EventLog.CONTAINED`, `ConsignmentAuditWriter` | *Whole-pipeline dry run* above — `strategy.ingest` runs contained (member copies, scratch root, throwaway event log), so parsing IS re-validated and reported per member |
| The graph runner | `ConsignmentGraphRunner` — `engages`, `run`, `dataFedSinkCount` | 🔴 The backlog names a **non-existent** class and denies its callers (§EXE.2.3) |
| Route arming | `RouteArming.refusals` | One statement, two callers |
| The two lifts | `PipelineLift.lift` / `.stageTwo` | ⚠ Both live; see *The at-rest runner, and the two lifts* above before citing either |
| The at-rest runner | `PipelineJobRunner` | — |
| Maintenance dispatch | `MaintenanceJob` (`BUILT_IN_TASKS`, 23 ids since 2026-11-15) | Seven were unadvertised (§EXE.2.2) — ✅ fixed 2026-09-09 (§EXE.5.3 item 2) |
| Contributed tasks | `MaintenanceTaskProvider` + the backup and operations modules | — |
| Compaction | `PartitionCompactor` | — |
| The bounded retry | `CommitRetry` | 🔴 No transient/fatal classifier; the zero escape hatch is now stated under *Caps and the bounded retry* |
| The memory resolver | `DuckDbUtil.memoryLimit` | ✅ Falls back to `DuckDbUtil.defaultMemoryLimit` since 2026-09-26 (§EXE.2.4) |
| Quarantine | `QuarantineManager` | — |
| Reprocess and drain | `ReprocessCommand`, `DrainCommand` | Whole-batch only, verbatim |
| The commit log | `CommitLog` | No day column, by design |
| The run ledger | `JobRunLedger` (+ an optional projection) | ⚠ The lane table names only the default-off projection |
| Idempotency | `Idempotency` | — |
| Execution routes | `RunRoutes`, `JobRoutes` | 🔴 Replay's in-memory lookup (§EXE.2.3) |
| The declared lane owner | *Execution lanes* (this page) | The orphan-gate self-contradiction was corrected 2026-09-09; the post-sync lane is now named beside the five |
| The Job concept page | [`jobs.md`](../processor/spec-jobs.md) | ⚠ Dead class citations, by line |
| The boundary page | [`job-vs-step.md`](../processor/architecture-processor.md) | ⚠ A fifth wrong node-type count |
| Concurrency | [`spec-consignments.md` Part II](spec-consignments.md#part-b--concurrency-the-four-layer-hierarchy) | ✅ The most accurate page in the area |
| The ingest fork | [`spec-ingest.md`](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) | ✅ Accurate |
| The post-sync lane | *Post-sync step chains* (this page) | The job type id misspelling was corrected 2026-09-09 |
| Resource caps | [`duckdb.md`](../engine/spec-duckdb.md) | 🔴 Its bolded headline still states the absent default |
| ~~The active plans~~ | **ALL FOUR ARCHIVED** — the first three 2026-09-10, `completeness-kpi-plan.md` 2026-09-09 | ✅ Their decisions of record are distilled into the current tier (`INDEX.md` names each target). The "what actually runs" staleness this row flagged was **corrected before the move** (Sprint 7.5): four dead class names and a "you cannot add a new Step type" claim that a shipped pack overlay had refuted |

Exactly **one** practice-typed page exists here, and it is the smallest file in the set; the operator-facing
procedure lives in a 71 KB *reference*.

---
