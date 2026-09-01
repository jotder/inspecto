---
type: Concept
title: Ingestion (StreamingFileIngester + consignment coordination)
description: The single emit-based ingestion SPI, its union/generation modes, and the consignment coordinators.
resource: inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java
tags: [engine, ingestion, spi, streaming, consignment]
timestamp: 2026-06-28T00:00:00Z
---

# Ingestion

> Vocabulary: a Consignment is the unit a Step's token references (data resolved by reference; edges
> carry no records) — the full runtime edge model converges at Phase 7
> ([`pipeline-spec.md`](../../../superpower/pipeline-spec.md) §13 D2).

## The SPI

`StreamingFileIngester` (`inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java`) is the **only**
plugin ingestion SPI (the old whole-file `FileIngester` was removed in v3.11.0). Implementations decode a
file and push records one at a time via `RecordSink.emit()`; the framework owns DuckDB table creation,
transform, partitioned write, and lineage.

## Two execution modes

`StreamingPluginIngestStrategy` (`inspecto-engine/src/main/java/com/gamma/inspector/StreamingPluginIngestStrategy.java`)
picks a mode per Consignment by inspecting member file sizes, with no extra I/O:

* **Union mode** (`UnionModeIngester`) — all members are below `processing.streaming.large_file_bytes`. Each
  member's records accumulate into a per-member raw table (`raw_<KEY>_f<srcId>`), then all are `UNION ALL`-ed
  and run through **one** transform/write/lineage pass — amortising fixed per-consignment cost.
* **Generation mode** (`GenerationModeIngester`) — the largest member is ≥ `large_file_bytes`. Each member
  streams in bounded "generations": once a segment hits `flush_records` rows it is transformed, written,
  lineage-counted, then dropped — so peak heap/scratch stays ≈ one generation regardless of file size. Each
  generation emits its own `<stem>_gNNNNN_out.*` files (valid Hive layout).

Selectors (parsed in `PipelineConfigParser`): `processing.streaming.large_file_bytes` (default **256 MB**),
`processing.streaming.flush_records` (default **5,000,000**).

## Consignment coordination

* `CollectorProcessor` (`inspecto-engine/src/main/java/com/gamma/inspector/CollectorProcessor.java`) — the per-source ETL
  entry point, split into two halves (B3b): **`acquire(cfg)`** runs the [acquisition](../acquisition/framework.md)
  phases (remote fetch-and-land; a no-op for a `local` collector), and **`ingest(cfg, onCommit)`** scans the
  inbox → groups into `Consignment`s via `ConsignmentPlanner` (bounded by
  `processing.batch.max_files`/`max_bytes`, ordered by `processing.batch.order` — the `.toon` keys keep the
  pre-rename `batch.` spelling, a deferred residual (BACKLOG §4), as does `Consignment.batchId` — **default `mtime`
  (file arrival)**, operator decision 2026-08-12; `name` is the opt-in for feeds whose stamps are
  unreliable, and any other value is refused at parse) → submits to a
  virtual-thread executor bounded by `Semaphore(processing.threads)`. `run()` = `acquire` then `ingest`, the
  self-contained one-shot used by the single-pipeline CLI `main`, `reprocess`, and the service's manual "run
  now". The always-on service instead drives the two halves on **separate timers with separate budgets** — see
  [acquisition](../acquisition/framework.md). Ingest always walks the local inbox: a remote-fetched file, once
  landed, is discovered exactly like a locally-pushed one, so `countPending` is now the exact landed backlog
  (no remote approximation).
* `ConsignmentIngestor` (`inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java`) — a thin, stateless
  coordinator: pick a [`ConsignmentIngestStrategy`](transforms-seams.md) (CSV or plugin), run `ingest()` → an
  `IngestOutcome`, then the path-agnostic tail `commit()` (DuckLake register → manifest → backup originals →
  markers → ledger, in that crash-safe order) and `writeAudit()`. Never throws for a Consignment failure — audit is
  always written (see [quarantine](output-sinks.md)). Markers/fingerprints go **last**, so a FAILED
  Consignment leaves no "already processed" record and its files are rediscovered next poll (retry is
  implicit); what is recorded about a Consignment either way — ledgers, provenance, live gauges, and how
  an operator audits a failed file or record — is [consignment status flow](consignment-status-flow.md).
* `MultiCollectorProcessor` (`inspecto-engine/src/main/java/com/gamma/inspector/MultiCollectorProcessor.java`) — the outer
  orchestrator running many `.toon` sources concurrently in one JVM, bounded by `Semaphore(sources.max)`.
  Total worker pressure = `sources.max × processing.threads × duckdb_threads`.
