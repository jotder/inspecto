---
type: Concept
title: Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains — Dry run, park and drain, commit steps and recovery
description: Part 4 of 5 of Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains: Whole-pipeline dry run (PIPELINE-DRYRUN-1, shipped 2026-09-20); Per-Step enabled — park and drain; Commit steps — the work a commit needs before it is done; Failure and recovery, per lane
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
---

# Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains — Dry run, park and drain, commit steps and recovery

Part 4 of 5 of [Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains](spec-pipeline-execution.md) · previous: [The scratch lane — Pipeline test run](spec-pipeline-execution-scratch-lane.md) · next: [Post-sync step chains and code pointers](spec-pipeline-execution-post-sync.md).

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
[§ Execution lanes](spec-pipeline-execution-engines-lanes.md#execution-lanes) above), and conflating them is the whole design problem this
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
