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

- Every way a Pipeline executes — ingest, graph fork, at-rest job, park-then-drain — [spec-execution-lanes](spec-pipeline-execution.md)
- Consignment status, provenance and live gauges for audit — [architecture](architecture.md)
- Bounded concurrency per Pipeline, Space and server with fair priority — [spec-consignment-concurrency](spec-consignments.md)
- Partitioned or single-file outputs with quarantine outcomes — [spec-output-sinks](spec-sinks.md)
- Test-run a Pipeline before it goes live — [spec-pipeline-test-run](spec-pipeline-execution.md)
- Runs list and run detail screens — [user-guide-runs](user-guide.md) · [user-guide-run-detail](user-guide.md)
- Processor catalog of decoders, ingests and sinks — [feature-db](feature-db.md)

## Related hubs

[etl](../elt/index.md) · [acquire](../../module/acquire/index.md) · [processor](../../module/processor/index.md) · [config](../../module/config/index.md)

More in this hub: [architecture](architecture.md) · [architecture-pipeline-graph](architecture.md) · [architecture-stage1](architecture.md) · [feature-db](feature-db.md) · [plan-pipeline-execution](plan.md) · [spec-branch-aware-ingest](spec-ingest.md) · [spec-catalog-vs-executors](spec-pipeline-model.md) · [spec-consignment-addressing](spec-consignments.md) · [spec-consignment-concurrency](spec-consignments.md) · [spec-db-layer](spec-db-layer.md) · [spec-duckdb](spec-duckdb.md) · [spec-editable-round-trip](spec-pipeline-model.md) · [spec-execution-lanes](spec-pipeline-execution.md) · [spec-ingest-wrap-spi](spec-ingest.md) · [spec-ingestion](spec-ingest.md) · [spec-live-execution](spec-pipeline-execution.md) · [spec-node-types](spec-pipeline-model.md) · [spec-object-storage-export](spec-sinks.md) · [spec-output-sinks](spec-sinks.md) · [spec-parser-plugins](spec-parsing.md) · [spec-parsing-grammar](spec-parsing.md) · [spec-parsing-options](spec-parsing.md) · [spec-pipeline-test-run](spec-pipeline-execution.md) · [spec-plugin-ingester](spec-ingest.md) · [spec-post-sync-step-chains](spec-pipeline-execution.md) · [spec-step-park-drain](spec-pipeline-execution.md) · [spec-transforms-seams](spec-pipeline-model.md) · [user-guide-run-detail](user-guide.md) · [user-guide-runs](user-guide.md)
