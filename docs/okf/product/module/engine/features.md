---
type: Feature
title: "Execution engine — features"
description: "The execution engine runs every Pipeline: it ingests Consignments, applies steps on embedded DuckDB and writes outputs."
tags: [engine, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Execution engine — features

**Product Map Item:** [`module.engine`](../../../../../product-map/module/engine.toon) · **Status:** ga · **Availability:** Platform base — present in every Edition (Basic, Standard, Advanced, Preview).

## What it is

The execution engine runs every Pipeline: it ingests Consignments, applies steps on embedded DuckDB and writes outputs. It owns execution lanes, concurrency, provenance and quarantine.

## Who benefits

Pipeline authors, operators watching runs, and auditors tracing a failed file or record.

## Key capabilities

- Every way a Pipeline executes — ingest, graph fork, at-rest job, park-then-drain — [spec-execution-lanes](spec-execution-lanes.md)
- Consignment status, provenance and live gauges for audit — [architecture-consignment-status](architecture-consignment-status.md)
- Bounded concurrency per Pipeline, Space and server with fair priority — [spec-consignment-concurrency](spec-consignment-concurrency.md)
- Partitioned or single-file outputs with quarantine outcomes — [spec-output-sinks](spec-output-sinks.md)
- Test-run a Pipeline before it goes live — [spec-pipeline-test-run](spec-pipeline-test-run.md)
- Runs list and run detail screens — [user-guide-runs](user-guide-runs.md) · [user-guide-run-detail](user-guide-run-detail.md)
- Processor catalog of decoders, ingests and sinks — [feature-db](feature-db.md)

## Related hubs

[etl](../../module/etl/index.md) · [acquire](../../module/acquire/index.md) · [processor](../../module/processor/index.md) · [config](../../module/config/index.md)

More in this hub: [architecture-consignment-status](architecture-consignment-status.md) · [architecture-pipeline-graph](architecture-pipeline-graph.md) · [architecture-stage1](architecture-stage1.md) · [architecture](architecture.md) · [feature-db](feature-db.md) · [plan-pipeline-execution](plan-pipeline-execution.md) · [spec-branch-aware-ingest](spec-branch-aware-ingest.md) · [spec-catalog-vs-executors](spec-catalog-vs-executors.md) · [spec-consignment-addressing](spec-consignment-addressing.md) · [spec-consignment-concurrency](spec-consignment-concurrency.md) · [spec-db-layer](spec-db-layer.md) · [spec-duckdb](spec-duckdb.md) · [spec-editable-round-trip](spec-editable-round-trip.md) · [spec-execution-lanes](spec-execution-lanes.md) · [spec-ingest-wrap-spi](spec-ingest-wrap-spi.md) · [spec-ingestion](spec-ingestion.md) · [spec-live-execution](spec-live-execution.md) · [spec-node-types](spec-node-types.md) · [spec-object-storage-export](spec-object-storage-export.md) · [spec-output-sinks](spec-output-sinks.md) · [spec-parser-plugins](spec-parser-plugins.md) · [spec-parsing-grammar](spec-parsing-grammar.md) · [spec-parsing-options](spec-parsing-options.md) · [spec-pipeline-test-run](spec-pipeline-test-run.md) · [spec-plugin-ingester](spec-plugin-ingester.md) · [spec-post-sync-step-chains](spec-post-sync-step-chains.md) · [spec-step-park-drain](spec-step-park-drain.md) · [spec-transforms-seams](spec-transforms-seams.md) · [user-guide-run-detail](user-guide-run-detail.md) · [user-guide-runs](user-guide-runs.md).
