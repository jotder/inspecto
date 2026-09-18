# PIPELINE-DRYRUN-1 — whole-pipeline dry run design

Status: **partially shipped 2026-09-18** — gates 1-4 (acquisition-phase post-action skip, execution-phase dry
run, both signal-emit sites, the provenance marker) are all built and unit-tested, and gate 1 now has an
operator-facing trigger (`POST /runs/{name}/trigger?dryRun=true`, `CollectorService.runPipeline`/
`triggerRunAsync`/`runPipelineOffThread`, same-day follow-up). See `docs/BACKLOG.md`'s `PIPELINE-DRYRUN-1`
row (2026-09-18 block) for the full job-flow finding and the correction to this doc's `GET /provenance` claim
(it reads `DbProvenanceStore`, not `DbConsignmentOutputStore`). Does **not** yet close `PIPELINE-DRYRUN-1` —
do not archive this file until a single combined "dry-run this whole pipeline" route (both acquisition and
execution phases under one flag) lands or is explicitly descoped by the operator.

## Decisions (operator-confirmed 2026-09-18)

1. **Scope: full.** Acquisition (source fetch + land-then-ack) *and* graph execution are both in
   scope — the row's hazard (a), "land-then-ack must not fire", only matters if acquisition is covered.
2. **Mechanism: thread the existing `JobContext.dryRun()` flag to every known mutating surface**, not a
   unifying refactor. Same discipline as `DryRunServices`'s own stated failure mode: every mutating
   surface must be substituted or `dryRun()` becomes a lie. A fifth mutating surface added later without
   the same discipline silently breaks the guarantee — grep for `DatasetWriteSignal.emit` and any new
   `CollectorConnector.post` implementor when reviewing future PRs that touch acquisition/execution.
3. **Provenance: write a marked "would-have" row.** A dry run should be visible in the existing
   Lineage/Sankey overlay (`GET /provenance`), clearly tagged as simulated, rather than invisible or
   requiring a separate report surface.

## Why no single interception point exists

`PipelineExecutor.dryRun()` (T18) already exists but is scoped to previewing the transform→sink *shape*
over an already-materialized sample — it doesn't touch acquisition, doesn't run a real batch/coordinator,
and isn't wired to a full job run today. A real run is driven by two separate code paths with no common
ancestor:

- **Acquisition**: `CollectorProcessor` → `RemoteAcquisitionHandler.applyPostAction` (`:319-330`) →
  `connector.post(rf, action)` (`:323`) — the land-then-ack deletion. One call site; every
  `CollectorConnector` (S3/SFTP/FTP/Azure/GCS/Kafka/DbExport) implements `post(...)` but is reached only
  through this one site.
- **Graph execution**: `PipelineJobRunner.run(ctx)` → `PipelineExecutor.execute(...)` with a real
  `SinkWriter`/`BranchCommitCoordinator`/`sourceFinalize` (`PipelineJobRunner.java:352`).
- **Signal emission**: `DatasetWriteSignal.emit(...)` from `ConsignmentProcessJobType.java:350` and
  `MaterializeTask.java:145` — two independent sites. The shipped self-loop guard
  (`JobService.java:794`/`:840`) is scheduler-side trigger-matching, not a write-path gate — it does not
  protect a dry run's emit call.
- **Provenance ledger**: `DbConsignmentOutputStore.record(...)` (`:224`) — independent of the above.

`JobContext.dryRun()` (`JobContext.java:60`) already exists and is already consumed once, to wrap
`PlatformServices` in `DryRunServices` (`JobService.java:1303`, `PackTestHarness.java:183` — both line
numbers drifted from the original BACKLOG row's 1266/174). This design extends what already listens to
that flag rather than inventing a second one.

## What "acquisition and execution share one ctx.dryRun()" requires

Confirm before implementing (first task below): does the SAME `JobContext`/trigger cover both
`CollectorProcessor`'s acquisition pass and `PipelineJobRunner`'s execution pass for one pipeline, or are
they genuinely separate Job types/triggers (e.g. a scheduled "acquire" poll vs. an on-demand "process"
run)? If separate, `dryRun` must be threaded explicitly from whichever caller holds `ctx` into the other's
constructor/call — do not assume a single ambient flag reaches both without checking.

## The four gates

1. **`RemoteAcquisitionHandler.applyPostAction`** — skip the `connector.post(...)` call under dry run;
   log what would have happened (mirror `DryRunServices`'s `log.info("dry run: would …")` style).
2. **`PipelineJobRunner` → `PipelineExecutor.execute(...)`** — under dry run, pass a no-op `SinkWriter`,
   a no-op `sourceFinalize`, and a `BranchCommitCoordinator` variant that never persists/commits. Do NOT
   reuse `PipelineExecutor.dryRun()` (T18) as-is — it's shaped for a bounded sample preview, not a full
   batch with a real `batchId`/coordinator lifecycle. Prefer no-op strategy objects passed into the
   existing `execute(...)` signature, which already takes these as explicit parameters — no new
   interception point needed there.
3. **The two `DatasetWriteSignal.emit(...)` sites** (`ConsignmentProcessJobType.java:350`,
   `MaterializeTask.java:145`) — gate directly on the resolved dry-run flag.
4. **`DbConsignmentOutputStore.record(...)`** — under dry run, write the row with a `simulated: true`
   (or equivalent) marker instead of skipping, so `GET /provenance` can render it distinctly. Confirm the
   provenance schema/UI can carry that marker before assuming it's a one-line change — it may need a
   column addition and a UI badge.

## Verification

- Unit/integration tests per gate (this repo's convention: unit-level per change, not the full reactor
  gate — see CLAUDE.md's "Model & effort routing").
- An end-to-end test that runs a real pipeline in dry-run mode against a source connector that would
  otherwise delete its remote file, and asserts: the remote file still exists, no sink table gained rows,
  no `dataset.write` signal fired (no downstream pipeline triggered), and a `simulated: true` provenance
  row exists for the run.
