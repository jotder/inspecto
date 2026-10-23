---
type: ProductItem
title: Execution engine
item: module.engine
description: Documentation hub for module.engine — every document about it, by slot.
tags: [product-item, module]
---

# Execution engine (`module.engine`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.engine`](../../../../../product-map/module/engine.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Pipeline execution (PIP) — capability spec](plan-pipeline-execution.md) |
| spec | current | [Branch-aware ingest — `route:` on the poll-driven path](spec-branch-aware-ingest.md) · [Step Processor catalog vs. real node executors — the mapping/transform family](spec-catalog-vs-executors.md) · [Consignment addressing](spec-consignment-addressing.md) · [Consignment concurrency — the four-layer hierarchy](spec-consignment-concurrency.md) · [Database / persistence layer](spec-db-layer.md) · [DuckDB Integration](spec-duckdb.md) · [The editable round-trip (lift / lower)](spec-editable-round-trip.md) · [Execution lanes](spec-execution-lanes.md) · [The ingest wrap-SPI — non-DuckDB formats into the DuckDB core](spec-ingest-wrap-spi.md) · [Ingestion (StreamingFileIngester + consignment coordination)](spec-ingestion.md) · [Pipeline Live Execution](spec-live-execution.md) · [Pipeline node types & the node-type plugin seam](spec-node-types.md) · [Object-storage export (S3 / HDFS) — the push post-action and its posture](spec-object-storage-export.md) · [Output & Sinks](spec-output-sinks.md) · [Parser plugins (the self-describing Parser framework)](spec-parser-plugins.md) · [Parsing & Grammar](spec-parsing-grammar.md) · [Parsing options reference](spec-parsing-options.md) · [Pipeline test run (run-to-here)](spec-pipeline-test-run.md) · [Plugin ingester](spec-plugin-ingester.md) · [Post-sync step chains (the open DAG)](spec-post-sync-step-chains.md) · [Per-Step enabled — park and drain](spec-step-park-drain.md) · [Transforms & Modularity Seams](spec-transforms-seams.md) |
| architecture | current | [Consignment status flow (the flowfile question)](architecture-consignment-status.md) · [Pipeline graph — the backend model](architecture-pipeline-graph.md) · [Stage-1 architecture & design](architecture-stage1.md) · [Core Module (inspecto/)](architecture.md) |
| features | current | [Execution engine — features](features.md) |
| feature-db | current | [Execution engine — feature database](feature-db.md) |
| user-guide | current | [Run Detail](user-guide-run-detail.md) · [Runs (Operations)](user-guide-runs.md) |
| admin-guide | current | [Execution engine — admin guide](admin-guide.md) |
| api | n/a | the engine registers no HTTP routes; the Pipeline, Run and Job routes it serves are documented in module/processor api-routes and api-jobs-scheduler and module/etl api-parsing-ingest |
| tests | current | [Execution engine — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.engine` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P3 | feature | Branch-aware executor residuals | demand |
| — | P3 | feature | Consignment addressing | demand |
| `DATA-TIERS-1` | P2 | feature | the data layer by edition, under one product-wide edition switch: Basic gets a files-only "Add data" import (CSV / TSV, Parquet, JSON / N… | operator |
| `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1` | P3 | defect | a multi-file Consignment re-planned after a failover can write a second, differently named output, even on a shared output tree. | operator |
| — | P2 | feature | Postgres multi-user | — |
| `OPSTORE-BULK-INSERT-1` | P2 | defect | row-at-a-time JDBC inserts into a file-backed DuckDB run at hundreds of rows/s, on 1.5.6 AND 2.0. | — |
| `DUCKDB-2-WINDOW-SELF-JOIN-UPSTREAM-1` | P3 | chore | report DuckDB 2.0.0's window_self_join hive-pruning defect upstream, then drop the workaround when it is fixed. | external |
| `DUCKDB-2-DECIMAL-WIDENING-AUDIT-1` | P3 | chore | DuckDB 2.0 widens DECIMAL(18,4) ± DECIMAL(18,4) to DECIMAL(19,4) (1.5 capped at 18); only partner-settlement is pinned. | — |
| `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | P2 | feature | NARROWED 2026-10-18: the containment rule is BUILT; what is left is making the ingest lane write to it. | — |
