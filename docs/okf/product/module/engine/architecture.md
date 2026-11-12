---
type: Module
title: Execution engine
description: The execution engine's entry page in two parts — the Core Module inspecto/ (the composition root — control plane + application packages, ships the fat JAR; artifactId inspecto-processor; the ingestion engine was extracted to sibling modules in the WS-D split) and the Consignment status flow (what is recorded about a Consignment as it moves — per-lane identity, status, provenance, live gauges — and how an operator audits a failed file or record).
resource: inspecto/, platform/inspecto-etl/src/main/java/com/gamma/etl/ConsignmentAuditWriter.java
tags: [module, core, composition-root, inspecto-processor, engine, consignment, observability, audit, provenance, quarantine, status]
timestamp: 2026-07-22T00:00:00Z  # the Core Module part; the Consignment status flow part carried type Concept, 2026-08-13
---

# Execution engine

The entry page of the `engine` hub, in two parts: where the engine's code lives since the WS-D reactor
split (the composition root `inspecto/` and the modules extracted from it), and what is recorded about a
Consignment as it moves. Two larger subjects have their own topic pages:
[Pipeline graph — the backend model](architecture-pipeline-graph.md) and
[Stage-1 architecture & design](architecture-stage1.md).

**On this page**

- [Core Module (`inspecto/`)](#core-module-inspecto) — the composition root and the extracted engine modules.
- [Consignment status flow (the flowfile question)](#consignment-status-flow-the-flowfile-question) —
  provenance, live gauges, ledgers and auditing a failed file or record.

## Core Module (`inspecto/`)

The **composition root** (artifactId `inspecto-processor`, jar `inspecto.jar`) — the module that wires
the object graph together and shades the single deployable fat JAR. It keeps **zero network/AI
dependencies**: remote protocols live in [connectors](../connectors/architecture.md), hosted models in
[agent-hosted](../agent-hosted/architecture.md).

> **Not the engine anymore.** Before the WS-D reactor split (2026-07-22) `inspecto/` held the whole
> engine; the engine was then extracted into the sibling modules below, and `inspecto/` now depends
> *down* on them. Authoritative build order, version management, and the extraction playbook live in
> [../../layer/platform/architecture-modules.md](../../layer/platform/architecture-modules.md).

### What lives here now

`inspecto/src/main/java/com/gamma/`: `service` (the always-on host — `CollectorService`, the
[control API](../processor/api-control-api.md) wiring, [`SpaceManager`](../processor/architecture.md)),
`control` (the ~50 HTTP routes), `report`, `assist` (the assist SPI — [interface only](../agent/spec-assist-agent.md)),
`exchange`, `expectation`, `intelligence`, and `model`.

### Extracted engine modules (WS-D) — `inspecto/` depends down on these

The concept docs are unchanged by the move; only the home module changed:

- **`inspecto-engine`** (`inspecto-engine`) — the engine cluster: `signal`, `query`,
  [`pipeline` / pipeline-graph](architecture-pipeline-graph.md), `inspector` ([DuckDB](spec-duckdb.md) sinks
  plus the fat-jar entry points `CollectorProcessor` and `MainApp`), `ingester`, `ops`,
  [`job`](../processor/spec-jobs.md), `enrich`, `alert`, `notify`, `catalog`.
- **`inspecto-etl`** (`inspecto-etl`) — [`etl`](spec-ingestion.md): `PipelineConfig`, the
  ingesters, batch planning, quarantine, lineage, [partitioned Parquet output](spec-output-sinks.md).
- **`inspecto-event`** (`inspecto-event`) — [`event` + `metrics`](../event/spec-events-metrics.md):
  the Operational-Intelligence event store + metric registry; owns `logback.xml`.
- **`inspecto-acquire`** (`inspecto-acquire`) — the [acquisition framework](../acquire/architecture.md):
  connectors, connection profiles/registry/workbench, the fingerprint ledger, stability gate,
  retry/circuit-breaker/rate-limit policies.
- Foundation leaves **`inspecto-api`** (`@PublicApi`), **`inspecto-util`** (DuckDB access + CSV/file/tar
  helpers), **`inspecto-config`** ([TOON config](../config/spec-toon-config.md) — spec/io/safety), and
  **`inspecto-sql`** (the sandboxed read-only DuckDB SQL surface).

Modularization is reactor-internal — the single `inspecto.jar` is unchanged (shade has no
include-list, so the sibling modules' classes and resources bundle automatically).

Build/run: see [build & test](../../layer/platform/spec-build-test.md).

### Stopping a Space (graceful stop)

`SpaceManager.close()` closes each Space under a 10 s budget (`CLOSE_DEADLINE_MS`). `CollectorService.close()`
first asks in-flight runs to stop, then drains them (Jobs, timers, trigger workers, enrichment, the notification
queue), and closes the stores last. A busy Space can overrun the budget; its drain is then abandoned on a daemon
thread. **Since `DEMO-BAKE-WAL-RACE-1` (2026-11-12) an abandoned close still closes the Space's stores**
(`CollectorService.closeStoresNow()`, which also closes the Job run ledger and provenance store through
`JobService.closeStores()`), under a second 10 s budget. A closing DuckDB store checkpoints, so no `.wal` is left;
work still running then fails on a closed store, which is the stop it was already asked to make. Each store
serialises its one connection (`SingleConnectionSource`), so a store close waits for the statement in flight and
never cuts it; only a store still busy after the second budget keeps its `.wal`, which DuckDB replays at the next
start. The store close runs once, whichever path reaches it first.

Decision: close the stores at the deadline rather than extend the budget while Jobs finish. A container runtime
stops a process after its own grace period (10 s for `docker stop`, 30 s for a Kubernetes pod) and Spaces close one
after another, so a longer budget would not hold; a checkpoint alone would not either, because work that is still
writing would add to the WAL again before the JVM exits. Test: `SpaceCloseFlushTest`.

## Consignment status flow (the flowfile question)

"Where is this Consignment, and what happened to it?" — NiFi's flowfile question, answered by four
separate mechanisms rather than one attribute bag. As-built after the consignment-chain plan
(shipped 2026-08-12/13; provenance from S3, the live gauge from S7, the audit holes closed after).
Vocabulary: **Consignment** in code too since the Phase-7 rename shipped (`ff33246a` —
`ConsignmentAuditWriter`, `ConsignmentManifest`, `ConsignmentEvent`, …); ⚠ wire and persisted
spellings deliberately kept `batch_id`/`batches` (ledger columns via read-alias, `GET /runs/{n}/batches`,
the `__batch_id` output column, the `batch_id` `.toon` job key — deferred residuals, BACKLOG §4).

### Per lane

The per-lane identity/status table lives in
[execution-lanes.md](spec-execution-lanes.md) — this part owns the recording mechanisms
below (gauges, ledgers, quarantine, drill-down).

All provenance-writing lanes share the same `inspecto_pipeline_provenance` matrix, through **one**
`DbProvenanceStore` shared via the per-space `ProvenanceStores` registry — DuckDB is single-writer, so
two independently-opened stores would contend. Default-off (`-Dprovenance.backend=duckdb`); absent, the
editor's per-edge weights 404 and recording is a map lookup.

### Live gauges are in-memory by decision, not by omission

`IngestProgress` (which file) and `StepProgress` (which step) are process-local
`ConcurrentHashMap`s, cleared in a `finally` so a snapshot never outlives its batch/run — success or
failure alike. Both are served by the existing poll route (`InboxStatus.current` / `.step`,
`RunRoutes`). Two alternatives were **refused** with reasons that still hold:

- ⛔ **Per-step Signals.** A Signal is a durable ledger write, and the one existing emission is already
  synchronous *inside the pipeline's run claim* (`ConsignmentAuditWriter.flush` → `PipelineConsignmentSignal`).
  Per-step would multiply durable writes on the claim-holding thread — and because Signals trigger
  `on_signal` jobs, a mid-batch Signal invites triggered work to fire **while the claim is held**: the
  re-entrancy class [PROJECT_NOTES §6](../../../../PROJECT_NOTES.md) forbids. Revisit only at
  per-Consignment grain with a mandatory off-bus hand-off.
- ⛔ **Periodic persistence of progress.** Stale by construction, recurring writes for a value that
  expires in seconds, and crash-orphaned rows to clean. The post-crash question is already answered by
  the ledgers.

A wedged-but-alive step therefore reads as a **stale `startedAt` on a still-present snapshot** — that
is the intended hang diagnostic. The EL lane tracks `parse`→`transform`→`sink` on the Java per-member
path only: the native streaming paths fuse those stages into one pass, so they are deliberately
untracked rather than reported falsely.

### Auditing a failed file or record

**Files.** A bad input is moved to `<quarantine>/<poll-subpath>/<reason>/<file>` (reasons:
`field_mismatch`, `unreadable`, `empty`, `corrupt_download`, and since X1 `retry_exhausted` — a file whose
Consignment failed at COMMIT `-Dingest.retry.max` times; see the bounded-retry note in
[operations-reference](../../layer/platform/admin-guide-operations-reference.md)) — the tree itself is evidence, organised by
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

**Job-lane rejects are ONE kind carrying a reason** — `reject:<reason>`, where the reason names which
operator diverted the rows (`filter`→`dropped`, `validate`→`invalid`, `dedup`→`duplicate`,
`merge`→`unmatched`). ⚠ They are four *reasons*, not four relations; the constants keep their
pre-token spelling until Phase 7. Rejects are scratch unless the author **wires a sink to the reject
outlet** — the designed pattern ("the user never wires these, only tunes where they rest").
`ConservationCheck` alerts on an unexplained in/out imbalance either way.

#### Rejects are counted in TWO columns, files and rows (D2 — AS-BUILT 2026-09-22)

✅ **SHIPPED 2026-09-22 (`WB-09`).** The batches ledger header is
`…,member_count,rejected_files,rejected_rows,total_input_rows,…`, and `total_input_rows` counts what
ARRIVED. ⚠ **A wholly-rejected member counts in `rejected_files` and NOT in `rejected_rows`** — its rows
were never parsed, so counting them as rows would double-count the file as rows it never had.

🔴 **The header has SEVEN construction/mirror sites, not the four the plan listed.** Beyond
`ConsignmentAuditWriter`'s header string, its `batchLine` codec, `ConsignmentRow` and
`OperationalTables.BATCHES`, the compiler found `DrainCommand` and four test fixtures across three
modules. ✅ That is the case for the typed record over the string header: **a stale string mirror
mis-reads silently; a stale constructor does not compile.**

⚠ **`error_rate` changed meaning, deliberately.** The alert measure is
`1 - sum(total_output_rows)/sum(total_input_rows)`; with the old input count excluding dropped rows, a
file that lost a record reported **0%**. It now reports the real rate, so thresholds tuned against the
old always-reconciling numerator may fire where they used to stay quiet. ✅ The alert measure was
already NAMED `rejected_files` while reading a column called `rejected_count`; the rename ends that
mismatch too.

Pinned by `ConsignmentAuditWriterTest.theBatchLedgerCountsRejectedFilesAndRejectedRowsSeparately`,
which asserts the two columns by POSITION as well as by name — readers parse by header name, so a codec
emitting them in the wrong order would swap files and rows with every column still present.

*(The decision, for provenance:)* `rejected_count` is **split, not redefined** — `rejected_files`
(today's value: members not `SUCCESS`) **and** `rejected_rows` (the sum of `IngestResult.errorRows`), with
`total_input_rows` = parsed + rejected rows so the ledger reconciles to the file on disk. Renaming rather
than overloading is what the ledger needs: one number that meant both was the ambiguity. ✅ Breaking the
header is free — nothing after 3.x shipped.

🔴 **Why:** measured 2026-09-22 on a 20-data-row file carrying one row truncated mid-write to 9 of 18
fields, the batch row read `status=SUCCESS · rejected_count=0 · total_input_rows=19 ·
total_output_rows=19` — **the counters reconcile (19 = 19) while a record is missing.** The drop is
recorded only in `<dirs.errors>/<file>_errors.csv`, which nothing in the audit row points at. An operator
reconciling output against the store by these counters concludes nothing was lost.

⚠ The invariant the split buys: `total_input_rows = total_output_rows + rejected_rows` when no Step drops
rows; a Step that drops rows is the other, already-honest half (see
[`step-catalog.md`](../etl/spec-step-catalog.md)'s counted reject relation). Tracked as `WB-09` in
`archived-documents/plans-archive/workbench-trust-plan.md`; row `INGEST-REJECT-ACCOUNTING-1`.

⚠ **The batches-ledger header has FIVE mirrors** — `ConsignmentAuditWriter`'s header string, its
`batchLine()` codec, `ConsignmentRow`, and `OperationalTables.BATCHES` (the agent's SQL surface, which
declares the header explicitly). Readers parse **by header name per file** (`Csv.readInto`), so
*appending* a column never breaks old ledger files; a stale mirror silently hides it.

### Related

[ingestion](spec-ingestion.md) · [consignment-addressing](spec-consignment-addressing.md) ·
[output-sinks](spec-output-sinks.md) (quarantine) · [live-execution](spec-live-execution.md) ·
[events-metrics](../event/spec-events-metrics.md) · [jobs](../processor/spec-jobs.md) ·
GLOSSARY §2 *Consignment* / §6-B

### Intrinsic and inherited status must not collapse into one enum

A file's own outcome and the outcome of the Consignment containing it are different facts, and both are
needed: a `COMMITTED` file inside a `FAILED` Consignment is a real and common state (the file landed; the
Consignment failed later, on another file or at commit). Folding them into a single status loses the ability
to answer "which files actually made it?" after a partial failure — which is exactly the question reprocessing
asks.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*
