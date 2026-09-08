---
type: Concept
title: Transforms & Modularity Seams
description: TransformCompiler (transformType → ColumnRule) and the BatchIngestStrategy seam.
resource: inspecto-etl/src/main/java/com/gamma/etl/TransformCompiler.java
tags: [engine, transform, seam, strategy]
timestamp: 2026-06-28T00:00:00Z
---

# Transforms & Modularity Seams

These behavior-preserving seams keep the engine modular (SQL / `.toon` / on-disk output unchanged).

* **`TransformCompiler`** (`inspecto-etl/src/main/java/com/gamma/etl/TransformCompiler.java`) — a pure
  SQL-expression compiler for the partition and date expressions. ⚠ *(Until 2026-09-08 this bullet described a
  `ColumnRule` registry `DATA_RULES` mapping `transformType` — `DATA_RULES` does not exist anywhere in the tree; the
  mapping lane is the Record Transformer since 2026-09-05, `configuration.md` §2 / `catalog-vs-executors.md`.)* An unrecognised non-blank type
  throws immediately (typo-safe); adding a type is a one-line registry edit. Note the deliberate asymmetry —
  data columns wrap DATE/TIMESTAMP sources in `CAST(... AS VARCHAR)` before the `TRY_STRPTIME` chain;
  partition columns route through `SqlBuilder.buildCastExpr`.
* **`ConsignmentIngestStrategy`** (`inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java`) — a
  package-private interface, one method `IngestOutcome ingest(Consignment, PipelineConfig)`, with two
  implementations: `CsvIngestStrategy` (default) and `StreamingPluginIngestStrategy` (renamed 2026-08-31; this
  page said `BatchIngestStrategy` / `CsvBatchStrategy` / `StreamingPluginBatchStrategy` until 2026-09-08) (the
  [plugin](ingestion.md) path). Each owns its DuckDB connection lifecycle; the shared commit+audit tail in
  `BatchProcessor` is path-agnostic. The interface also carries static helpers (`dropTable`, `dropView`,
  `partitionColumns`).

Related: `OutputFormat` is the third such enum-as-strategy seam (see [output & sinks](output-sinks.md)).
