---
type: Concept
title: Per-Step enabled — park and drain
description: Switching off a route-branch sink parks its Consignments durably at the boundary; a drain completes them through the real commit tail.
resource: inspecto-engine/src/main/java/com/gamma/inspector/DrainCommand.java
tags: [pipeline-graph, park, drain, route, consignment, disabled-steps]
timestamp: 2026-08-29T00:00:00Z
---

# Per-Step `enabled` — park and drain

Switching a Step off means different things in the two lanes, and conflating them is the whole design
problem this concept solves:

- **Scratch lanes** (dry-run, run-to-here) — a disabled node is an in-memory **bypass**: it produces
  nothing and everything downstream is inert. Correct there, and deliberately unchanged.
- **At rest** (the ingest lane) — a bypass would *lose rows*. So a disabled Step is a durable **pause**:
  the Consignments that reach it **PARK**, and a later **drain** completes them.

Decision of record: **D-13**, ELT Phase 4 S4. Shipped 2026-08-28/29 across five slices; the slice plan is
[`elt-s4-park-drain-plan.md`](../../../archived-documents/plans-archive/elt-s4-park-drain-plan.md).

## The durable home: `processing.disabled_steps`

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

## Arming — where a park is even possible

At rest the executor is seeded **downstream of parse/map**: only the `route → sinks` subtree runs as graph
nodes. So `StepDisableArming` relaxes exactly one shape — a **sink strictly inside an armed `route:`
subtree** — and refuses, at SAVE and at `prepare()`, never as a silent skip at rest:

1. any `disabled_steps` on a pipeline with **no armed `route:`** (the flat lane has no park boundary; the
   answer is dry-run / run-to-here). Lifting this is Phase 6's non-route parity project;
2. disabling `collect`/`parse`/`map` or anything at-or-upstream-of the route node (the executor is seeded
   below them; the answer is `active: false` or the Stage-B half-pause);
3. disabling the `transform.route` node itself (the divert's engagement anchor);
4. an unknown Step id (a typo must never become a silently-enabled Step);
5. **all** branches (`active: false` is that ask).

⚠ `StepDisableArming.parkableSinkIds` **hand-mirrors the lift's `sink__d<i>` id grammar**. That is legitimate
only because an armed `route:` pipeline is single-schema (so the suffix is empty), and it is pinned verbatim
by `PipelineLiftTest.parkableSinkIdsMatchTheLiftedGraph`. Extend both together.

## Park — the boundary IS the materialisation boundary

`PipelineExecutor.ParkWriter` is a hook invoked when the walk reaches a **disabled SINK with a live inbound
relation**. It is `null` on every scratch path, so the bypass stays. At rest `BatchIngestStrategy` supplies a
lambda that `COPY`s the branch relation to `dirs.backup()/parked/<batchId>__<nodeId>.parquet` and records it
in the batchId-keyed `ParkedBranches` stash (the `UnpackOrigins` lifecycle idiom — threading `IngestOutcome`'s
eight constructor sites was rejected).

`BatchProcessor.parkSource` then does **almost nothing** of the normal commit tail, because a parked
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

## `ParkedCommit` — why a sidecar exists at all

A parked batch's **enabled** branches already committed their files. Their `PartitionOutput`s, lineage rows
and event-time bounds live **only in the ingest JVM's memory**, and `BranchCommitLog` records branch *ids*
and nothing more. Without persisting them, a drain in a later process could not run `finalizeSource` — the
register, `manifest.outputs` and the §11.3 registry would each record **half a batch**, silently. So park
writes them beside the park tables, and the drain consumes and deletes them.

## Drain — the commit tail, resumed

`DrainCommand.run(toonPath, batchId)`, reached through `POST /runs/{name}/drain` (`canOperateRuns`, the
structural sibling of `reprocess`). **Not a re-ingest and not a graph re-walk**: a route branch's table is
complete when its sink is reached, so nothing upstream needs re-running and no transform remains between the
park table and the write. Drain registers each park table with `read_parquet`, writes it through the ordinary
`IngestSinkWriter`, and then runs the ordinary `BatchProcessor.finalizeSource` for the **whole** batch over
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
`SourceFinalizer` to `BatchGraphRunner` (finalisation belongs to `BatchProcessor.commit`, once the batch
outcome exists), so a park run has *already* recorded `SOURCE` having finalised nothing. Drain therefore uses
`BranchCommitCoordinator` only for the durable, idempotent per-BRANCH skip, and runs the commit tail itself.

⚠ **The restore window.** Every poll-relative computation in `finalizeSource` — manifest `rel`, backup
destination, marker path, ledger key, stage rows — assumes the original is in the inbox. Drain therefore
moves it back from the park home immediately before finalising, rather than mirroring five relativisations.
The file is in the inbox for the length of the commit tail, which then moves it to backup and marks it. A
concurrent poll or a crash inside that window re-ingests the file — the same idempotent
`OVERWRITE_OR_IGNORE` posture a crash mid-commit has always had.

## The surfaces

- **Authoring** — the Step switch in the definition drawer's identity strip, offered only where the engine
  can park. The UI decides that **structurally**: a sink Step with an inbound edge from a `transform.route`
  Step (never the lift's `sink__d<i>` spelling, which the editable model never sees). Switching on **deletes**
  the `enabled` key rather than writing `enabled: true`, so the lower derives an empty list. A switched-off
  Step reads `disabled` on the canvas — pause glyph + icon + label, never colour alone — computed **first**,
  because an author's explicit decision outranks every derived status, and it raises an `info` finding in
  Validate.
- **Operating** — a **Drain** row action on Run detail's Batches tab, visible only on a `PARKED` row (the
  engine refuses every other state, so offering it elsewhere would be an affordance that predictably fails).
  The 409 reason reaches the operator verbatim, because "re-enable the Step first" *is* the next step.
- **Deliberately not built:** re-enabling a Step does **not** auto-drain (operator decision, 2026-08-29) — a
  config save must not start batch work as a side effect. And no manifest-level `parkedAt`/`parkedTables`
  read is exposed: the ledger's `status=PARKED` plus the drain's result is what the operator acts on.

## See also

- [Design](design.md) — the graph IR, lift and executor this rides on.
- [Consignment status flow](../engine/consignment-status-flow.md) — where `FileStage` and the batch ledger fit.
