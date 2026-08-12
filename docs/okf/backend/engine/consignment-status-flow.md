---
type: Concept
title: Consignment status flow (the flowfile question)
description: What is recorded about a Consignment as it moves — per-lane identity, status, provenance, live gauges — and how an operator audits a failed file or record.
resource: inspecto-etl/src/main/java/com/gamma/etl/BatchAuditWriter.java
tags: [engine, consignment, observability, audit, provenance, quarantine, status]
timestamp: 2026-08-13T00:00:00Z
---

# Consignment status flow (the flowfile question)

"Where is this Consignment, and what happened to it?" — NiFi's flowfile question, answered by four
separate mechanisms rather than one attribute bag. As-built after the consignment-chain plan
(shipped 2026-08-12/13; provenance from S3, the live gauge from S7, the audit holes closed after).
Vocabulary: user-facing **Consignment**; code still says `Batch*` pending the GLOSSARY §13 Phase-7
sweep (`ConsignmentPlanner` landed early).

## The two lanes

| | Ingest lane (EL) | Job lane (T) |
|---|---|---|
| Identity | `batchId` = `TS_slug_seq`; `BatchManifest` authoritative per file | `runId`; `batchId` on provenance rows |
| Terminal status | `SUCCESS`/`FAILED`/`EMPTY` + per-file `QUARANTINED_*` → three CSV ledgers + `CommitLog` (`BatchAuditWriter`) | `JobRun` → `DbJobRunStore` |
| Per-step counts | `BatchProcessor.recordProvenance` → `parse`/`sink` rows (SUCCESS only) | `PipelineExecutor` records one row per node, flushed with the commit |
| Live position | `IngestProgress` (which FILE) + `StepProgress` (which STEP) | `StepProgress` (which node of the walk) |
| Terminal event | `BatchEvent` on the sync `BatchEventBus` **and** the `pipeline.batch.committed\|failed` Signal | `BatchEvent` published by `PipelineJobRunner` |

Both lanes write provenance into the same `inspecto_pipeline_provenance` matrix, through **one**
`DbProvenanceStore` shared via the per-space `ProvenanceStores` registry — DuckDB is single-writer, so
two independently-opened stores would contend. Default-off (`-Dprovenance.backend=duckdb`); absent, the
editor's per-edge weights 404 and recording is a map lookup.

## Live gauges are in-memory by decision, not by omission

`IngestProgress` (which file) and `StepProgress` (which step) are process-local
`ConcurrentHashMap`s, cleared in a `finally` so a snapshot never outlives its batch/run — success or
failure alike. Both are served by the existing poll route (`InboxStatus.current` / `.step`,
`RunRoutes`). Two alternatives were **refused** with reasons that still hold:

- ⛔ **Per-step Signals.** A Signal is a durable ledger write, and the one existing emission is already
  synchronous *inside the pipeline's run claim* (`BatchAuditWriter.flush` → `PipelineBatchSignal`).
  Per-step would multiply durable writes on the claim-holding thread — and because Signals trigger
  `on_signal` jobs, a mid-batch Signal invites triggered work to fire **while the claim is held**: the
  re-entrancy class [PROJECT_NOTES §6](../../../PROJECT_NOTES.md) forbids. Revisit only at
  per-Consignment grain with a mandatory off-bus hand-off.
- ⛔ **Periodic persistence of progress.** Stale by construction, recurring writes for a value that
  expires in seconds, and crash-orphaned rows to clean. The post-crash question is already answered by
  the ledgers.

A wedged-but-alive step therefore reads as a **stale `startedAt` on a still-present snapshot** — that
is the intended hang diagnostic. The EL lane tracks `parse`→`transform`→`sink` on the Java per-member
path only: the native streaming paths fuse those stages into one pass, so they are deliberately
untracked rather than reported falsely.

## Auditing a failed file or record

**Files.** A bad input is moved to `<quarantine>/<poll-subpath>/<reason>/<file>` (reasons:
`field_mismatch`, `unreadable`, `empty`, `corrupt_download`) — the tree itself is evidence, organised by
why. The status ledger carries a row per member file (`filename, status, parsed_rows, error_rows,
error, consignment_id`), the batches ledger the per-Consignment roll-up, the lineage ledger the
output↔input join. UI: **Run Detail** tabs (Batches / Files / Lineage / Quarantine / Commits), drill-in
by `consignment_id`, and Reprocess by batch id.

**Records.** Two distinct failure kinds, both now auditable:
1. **Parse rejects** — offending lines are written to a companion `errors/<base>_errors.csv` which
   travels *into quarantine beside its input file*. `GET /runs/{name}/errors?file=<bare name>` serves
   that content (bounded, `truncated` reports the true total), surfaced as "View the rejected rows" on
   Files rows with `error_rows > 0` and on Quarantine rows.
2. **Coercion failures** — a failed `TRY_CAST`/`TRY_STRPTIME` yields NULL and **keeps the row**.
   `DataTransformer.countCastFailures` measures these over the *same compiled expressions* the
   transform uses (never a re-derivation) and reports `cast_failures` on the batches ledger.
   ⚠ **`-1` means NOT MEASURED and writes a BLANK cell** — a path that cannot measure must never claim
   a clean batch. `EXPR` mapping rules are excluded by design: author-owned SQL has no defined
   "non-blank source".

**Job-lane rejects** are first-class relations (`filter`→`dropped`, `validate`→`invalid`,
`dedup`→`duplicate`, `merge`→`unmatched`) but scratch unless the author **wires a sink to the reject
edge** — the designed pattern ("the user never wires these, only tunes where they rest").
`ConservationCheck` alerts on an unexplained in/out imbalance either way.

⚠ **The batches-ledger header has FIVE mirrors** — `BatchAuditWriter`'s header string, its
`batchLine()` codec, `BatchRow`, and `OperationalTables.BATCHES` (the agent's SQL surface, which
declares the header explicitly). Readers parse **by header name per file** (`Csv.readInto`), so
*appending* a column never breaks old ledger files; a stale mirror silently hides it.

## Related

[ingestion](ingestion.md) · [consignment-addressing](consignment-addressing.md) ·
[output-sinks](output-sinks.md) (quarantine) · [live-execution](../pipeline-graph/live-execution.md) ·
[events-metrics](../control-plane/events-metrics.md) · [jobs](../control-plane/jobs.md) ·
GLOSSARY §2 *Consignment* / §6-B
