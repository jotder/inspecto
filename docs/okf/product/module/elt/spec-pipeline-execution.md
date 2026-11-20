---
type: Concept
title: Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains
description: How a Pipeline runs: the two engines and their triggers, the execution lanes and the post-sync lane, run identity and the async contract, caps and the bounded retry, the at-rest job lane, the scratch test run, the whole-pipeline dry run, per-Step park and drain, failure and recovery per lane, post-sync step chains, and code pointers.
resource: platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. [spec-pipeline-model.md](spec-pipeline-model.md) · **spec-pipeline-execution.md** · [spec-sinks.md](spec-sinks.md)

**Contents**

- [Two engines and their triggers](spec-pipeline-execution-engines-lanes.md#two-engines-and-their-triggers)
- [Execution lanes](spec-pipeline-execution-engines-lanes.md#execution-lanes)
- [Lane-fork admission notes](spec-pipeline-execution-identity-caps.md#lane-fork-admission-notes)
- [Run identity, ledgers and the async contract](spec-pipeline-execution-identity-caps.md#run-identity-ledgers-and-the-async-contract)
- [Caps and the bounded retry](spec-pipeline-execution-identity-caps.md#caps-and-the-bounded-retry)
- [The at-rest job lane (live execution)](spec-pipeline-execution-identity-caps.md#the-at-rest-job-lane-live-execution)
- [The at-rest runner, and the two lifts](spec-pipeline-execution-identity-caps.md#the-at-rest-runner-and-the-two-lifts)
- [The scratch lane — Pipeline test run (run-to-here)](spec-pipeline-execution-scratch-lane.md#the-scratch-lane--pipeline-test-run-run-to-here)
- [Whole-pipeline dry run (`PIPELINE-DRYRUN-1`, shipped 2026-09-20)](spec-pipeline-execution-dry-run-recovery.md#whole-pipeline-dry-run-pipeline-dryrun-1-shipped-2026-09-20)
- [Per-Step `enabled` — park and drain](spec-pipeline-execution-dry-run-recovery.md#per-step-enabled--park-and-drain)
- [Commit steps — the work a commit needs before it is done](spec-pipeline-execution-dry-run-recovery.md#commit-steps--the-work-a-commit-needs-before-it-is-done)
- [Failure and recovery, per lane](spec-pipeline-execution-dry-run-recovery.md#failure-and-recovery-per-lane)
- [Post-sync step chains (the open DAG)](spec-pipeline-execution-post-sync.md#post-sync-step-chains-the-open-dag)
- [Code pointers](spec-pipeline-execution-post-sync.md#code-pointers)

**What this covers.** What happens when a Pipeline actually runs. Two engines share one config vocabulary: the ingest engine moves
files from an inbox into stores, and the Job engine works over data already at rest. One Pipeline can run in
five lanes (two ingest lanes, an at-rest Job, a scratch test run, and a paused "parked" state), plus a
post-sync lane that derives tables after commit. This part says which lane does what, how each is triggered,
what each writes, how failures are retried or replayed, and how a test or dry run stays harmless.
How an author writes a Pipeline is the Pipeline model ([Part I](spec-pipeline-execution.md) above) and the
editor guide ([user-guide-elt.md](user-guide-elt.md)); the requirement of record is [plan-elt.md](plan-elt.md).

## Parts

This page is split by topic into 5 parts; each part lists the sections it holds.

1. **[Two engines, their triggers and the execution lanes](spec-pipeline-execution-engines-lanes.md)** — Two engines and their triggers · Execution lanes
2. **[Identity, ledgers, caps and the at-rest lane](spec-pipeline-execution-identity-caps.md)** — Lane-fork admission notes · Run identity, ledgers and the async contract · Caps and the bounded retry · The at-rest job lane (live execution) · The at-rest runner, and the two lifts
3. **[The scratch lane — Pipeline test run](spec-pipeline-execution-scratch-lane.md)** — The scratch lane — Pipeline test run (run-to-here)
4. **[Dry run, park and drain, commit steps and recovery](spec-pipeline-execution-dry-run-recovery.md)** — Whole-pipeline dry run (`PIPELINE-DRYRUN-1`, shipped 2026-09-20) · Per-Step `enabled` — park and drain · Commit steps — the work a commit needs before it is done · Failure and recovery, per lane
5. **[Post-sync step chains and code pointers](spec-pipeline-execution-post-sync.md)** — Post-sync step chains (the open DAG) · Code pointers
