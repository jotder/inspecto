# Engine

The per-batch ETL pipeline: acquire → ingest → transform → write, all backed by embedded DuckDB. Its code
lives in the engine modules extracted below the [core](../modules/engine.md) in the WS-D split —
`inspecto-etl` / `inspecto-engine` / `inspecto-event` / `inspecto-acquire` (see the
[reactor map](../modules/reactor.md)).

# Concepts

* [Ingestion](ingestion.md) - the `StreamingFileIngester` SPI, union vs generation mode, and the batch coordinators.
* [DuckDB](duckdb.md) - Appender-based bulk ingest (~75×), thread auto-derivation, reserved-word quoting.
* [Output & sinks](output-sinks.md) - `OutputFormat`, partitioned vs single-file writers, quarantine outcomes.
* [Object-storage export](object-storage-export.md) - S3/HDFS export posture (DISCUSSED 2026-08-28, unscheduled — BACKLOG EXPORT-1): nothing pushes outbound today, space-local/data-tier-exported is the recommendation, HDFS only via an S3-compatible gateway.
* [Ingest wrap-SPI](ingest-wrap-spi.md) - `StreamingFileIngester` + `RecordSink` → `DuckDbRecordSink` (Appender), Generation/Union drive modes, ASN.1 as reference; E1's optional partitioning + the two deliberate `partitions[]` contracts (2026-08-19).
* [Transforms & seams](transforms-seams.md) - `TransformCompiler` and the `BatchIngestStrategy` seam.
* [Pipeline test run (run-to-here)](pipeline-test-run.md) - the scratch-only run over REAL inbox files: why the containment is the call graph rather than config, the `QuarantineManager` source-file trap, the connection-relative `files` jail, and the sample-vs-full row-count grains (2026-08-14).
* [Parsing & grammar](parsing-grammar.md) - the three frontends / one backend model, CSV knobs, delimited grammar, plugin ingesters.
* [Node types & the node-type plugin seam](node-types.md) - the `PipelineNodeType` ServiceLoader registry (providers layered LAST, so an edition may override a built-in), both halves of it — the descriptor (`PipelineNodeType`) and, since 2026-08-29, the EXECUTION seam `PipelineNodeExecutor` that closed the descriptor-only gap (a contributed type used to render and validate, then throw in `RowShaper`); authorable ≠ lowerable and why they are two flags; the transform family's `emits()` table; and why the family cannot collapse into one SQL node (`filter`/`validate`/`dedup`/`route` each emit a SECOND relation, and `enrichment` is an after-commit cross-flow path, not a graph verb) — including why `transform.dedup.marker` cannot be deleted (2026-08-29).
* [Parser plugins](parser-plugins.md) - the self-describing `ParserPlugin` SPI + `GET /parsers`: served grammar schemas, tree-capable preview, ServiceLoader discovery (2026-07-30).
* [Unpack stage](unpack-stage.md) - decompression at the Collector: why expanding BEFORE the planner needs no Consignment mutation, the entry name as DATA (and the FIVE `srcIdToFile` sites, not three), skipped members reported rather than silent, the signed-off `UnpackStatus` vocabulary (⛔ PARTIAL commits — reporting, never a gate), and the run-level unpack ledger with its one-declaration column contract (2026-08-26).
* [Stage-1 architecture](stage1-architecture.md) - the deep design of the batch ETL core (moved from `docs/architecture.md`).
* [DB / persistence layer](db-layer.md) - every store, its backend (DuckDB/Postgres), and the dialect seams (moved from `docs/DB_LAYER.md`).
* [Consignment status flow](consignment-status-flow.md) - what is recorded about a Consignment as it moves (identity, terminal status, per-step provenance, live gauges) per lane, why the step gauge is in-memory rather than Signals or persisted, and how an operator audits a failed file or record — quarantine tree, the rejected-rows route, and the `cast_failures` count (2026-08-13).
* [Consignment addressing](consignment-addressing.md) - which files a read names: the Selector filters the glob rather than replacing it, event-time bounds per output file, the revision model for a safe recompute, and the per-stream Watermark (delivered 2026-08-10).
* [Consignment concurrency](consignment-concurrency.md) - the four-layer hierarchy (per-Pipeline / per-space / per-server caps + a 1-3 priority share), stride-scheduled grants that cannot starve, what hot-applies and why a shrink drains, ⛔ why the run budget is NOT redundant with the broker (it guards the pre-execution phase), and per-Pipeline cadence + remote fetch parallelism (2026-08-25).
* [Plugin ingesters](plugins.md) - the drop-in ingester plugin model (moved from `docs/plugins.md`).
