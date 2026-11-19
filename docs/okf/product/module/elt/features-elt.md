---
type: Feature
title: "Collectors, parsing, ingest and Pipelines — features"
description: "A Collector lands files from a remote system in a Pipeline's inbox; Stage-1 ingest turns them into typed rows in a Parquet store; a Pipeline is the chain of Steps (collect → parse → transform Steps → sink) that the execution engine runs."
tags: [elt, acquire, collectors, pipelines-steps, parsing-ingest, engine, features, presales]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Collectors, parsing, ingest and Pipelines — features

**What this covers.** What the ELT module does, in one page for presales and stakeholders, in the order data
travels: a **Collector** fetches files from wherever they live and lands each one exactly once; **Stage-1
ingest** reads each file, checks it against its Schema and commits it atomically as typed rows; a
**Pipeline** is the chain of Steps that ties these together and continues over the stored data; and the
**execution engine** runs every Pipeline with bounded concurrency, provenance and quarantine. Each section
names its edition availability, where it fits in the product and its code homes.

**Contents**

- [Collectors](#collectors)
- [Parsing and ingest](#parsing-and-ingest)
- [Pipelines and Steps](#pipelines-and-steps)
- [Execution engine](#execution-engine)

## Collectors

**Availability:** Offering: all tiers (local, SFTP, FTP/FTPS, DB, S3, Azure, GCS); the Kafka Collector is Standard and above.

### What it does

A **Collector** is a configured collection task bound to one **Connection** (a named endpoint plus
credentials). It reaches a remote system, decides which files are new and complete, fetches them once,
and lands them in the Pipeline's inbox (`dirs.poll`). Everything after landing is ingest's job
([parsing and ingest](#parsing-and-ingest)). The capability spec of record is
[the ELT plan](plan-elt-scope-requirements.md#acq1-acquisition--connectivity-acq); this page is the module summary.

Code homes:

- SPI and engine-side logic: `platform/inspecto-acquire/src/main/java/com/gamma/acquire` —
  `CollectorConnector`, `CollectorConnectorFactory`, `CollectorConnectors`, `ConnectionProfile`,
  `StabilityGate`, `AcquisitionLedger`, `DuplicatePolicy`, `GapDetector`, `RetrievalPlanner`,
  `IntegrityChecker`, `CircuitBreaker`, `RateLimiter`, `PostAction`, `retry/RetryPolicy.java`.
- Remote connectors: `providers/inspecto-connectors/src/main/java/com/gamma/acquire/connectors`.
- Kafka connector: `providers/inspecto-connectors-kafka/src/main/java/com/gamma/acquire/kafka`.
- Cycle driver: `platform/inspecto-engine/src/main/java/com/gamma/inspector/CollectorProcessor.java`.

### Where it fits

Collectors are the first stage of the platform base: a remote system feeds a Collector, the Collector
lands bytes, and a [Pipeline](#pipelines-and-steps) parses and loads them into [storage](../entity-store/architecture-entity-store.md).
The connector schemes are pluggable through `ServiceLoader`: `CollectorConnectors.forConfig` resolves
`local` to the built-in `LocalFileSystemConnector` and any other scheme to a registered
`CollectorConnectorFactory` matched on `scheme()`. An unknown scheme fails fast; there is no fall-back
to `local`.

| Scheme | Class | Module |
|---|---|---|
| `local` | `LocalFileSystemConnector` | `inspecto-acquire` (built in) |
| `sftp` | `SftpConnector` | `inspecto-connectors` |
| `ftp`, `ftps` | `FtpConnector` (two factories) | `inspecto-connectors` |
| `db` | `DbExportConnector` | `inspecto-connectors` |
| `s3` | `S3Connector` + `AwsSigV4` | `inspecto-connectors` (SDK-free) |
| `azure` | `AzureBlobConnector` + `AzureSharedKey` | `inspecto-connectors` (SDK-free) |
| `gcs` | `GcsConnector` + `GcpServiceAccountToken` | `inspecto-connectors` (SDK-free) |
| `kafka` | `KafkaConnector` | `inspecto-connectors-kafka` |

The registry files are
`providers/inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory`
and the matching file in `inspecto-connectors-kafka`.

More: [screens](user-guide-elt.md) · [API](api-elt.md#collectors-and-connections) · [architecture](architecture-elt.md).

## Parsing and ingest

**Availability:** Offering: all tiers; the ASN.1 frontend (module `inspecto-telecom-asn1`) is Advanced only (DATA-TIERS-1 DT-12, 2026-11-17).

### What it does

Stage-1 ingest is the road from a file in a Pipeline's inbox to typed rows in a Parquet store. It
groups inbox files into a **Consignment**, parses each file with one of the parse frontends, maps and
casts the columns to the declared Schema, writes partitioned Parquet, and commits the whole
Consignment atomically. A file that cannot be read goes to the **quarantine** tree; rows that fail
parsing go to a reject file beside it.

The capability spec of record is [the ELT plan](plan-elt-scope-requirements.md#11-ingestion-ing); the design is
[the architecture](architecture-elt.md). This page is the module summary.

Code homes:

- Poll cycle and Consignment coordinator: `platform/inspecto-engine/src/main/java/com/gamma/inspector`
  (`CollectorProcessor`, `ConsignmentIngestor`, `MultiCollectorProcessor`).
- Planner, writer and Unpack: `platform/inspecto-etl/src/main/java/com/gamma/etl`
  (`ConsignmentPlanner`, `PartitionWriter`, `CommitLog`, `MarkerManager`, package `unpack`).
- Parser registry: `platform/inspecto-engine/src/main/java/com/gamma/parse` (`ParserPlugin`, `Parsers`).
- Control routes: `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` and
  `inspecto/src/main/java/com/gamma/control/RunRoutes.java`.

### Where it fits

Ingest starts where a [Collector](#collectors) has landed a file in `dirs.poll`. It ends at a
committed store that [Datasets](../../layer/product/architecture-studio-bi.md) and at-rest [Steps](architecture-elt.md) read. Typing
rules come from the Schema registry (metamodel); storage layout is described in [storage](../entity-store/architecture-entity-store.md).
Every Consignment runs on a virtual thread bounded by `processing.threads`; many Pipelines share one
JVM under `MultiCollectorProcessor`.

More: [screens](user-guide-elt-save-surfaces.md#runs-and-processing-status) · [API](api-elt.md#parsing-and-ingest) · [architecture](architecture-elt.md).

## Pipelines and Steps

**Availability:** Offering: all tiers; the `transform.mask` Step is Advanced.

### What it does

A **Pipeline** is a chain of **Steps**: `collect → parse → transform Steps → sink`. It is authored
as one file, `<id>_pipeline.toon`, and edited either as text or as a graph in the Pipeline editor.
The graph is an immutable IR (`PipelineGraph`) lifted from the flat config; the editor's save lowers
it back into the same file, so there is one source of truth.

Two vocabularies describe Steps:

- **Step kinds** — the node types the engine executes (`BuiltinNodeType`, served with their
  attribute specs by `GET /pipelines/node-types`). Plugins add more through the `PipelineNodeType`
  `ServiceLoader` seam ([Pipeline spec — model](spec-pipeline-model.md)).
- **Step Processors** — the product taxonomy the palette lists in eight families
  (`ProcessorCatalog`, `GET /pipelines/processor-catalog`; the counts are derived in the
  [step catalog](spec-step-catalog.md)). A processor is a label over a Step kind or an engine capability,
  not a class of its own; planned entries show as "Not yet available".

The as-built references are the [pipeline-graph bundle](../../index.md) and the
[step catalog](spec-step-catalog.md); the capability spec is [the ELT plan](plan-elt.md) — authoring in
[§1.2](plan-elt-scope-requirements.md#12-pipeline-authoring-pip-1), execution in [§EXE.1](plan-elt-scope-requirements.md#exe1-pipeline-execution-pip-2--pip-7).

### Where it fits

A Pipeline binds a [Collector](#collectors), the [parse stage](#parsing-and-ingest), transform Steps
and one or more sinks into [storage](../entity-store/architecture-entity-store.md). Its at-rest half runs as a Job of type `pipeline`
on the [scheduler](../processor/architecture-processor.md). Code homes:

- Model, lift and validator: `platform/inspecto-engine/src/main/java/com/gamma/pipeline`
  (`PipelineGraph`, `PipelineLift`, `PipelineValidator`, `PipelineEditable`, `BuiltinNodeType`,
  `PipelineNodeTypes`, `ProcessorCatalog`).
- At-rest runner: `platform/inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`.
- Routes: `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java`,
  `PipelineListRoutes.java`, `PipelineHistoryRoutes.java`, `PipelineSettingsRoutes.java`.

More: [screens](user-guide-elt.md) · [API](api-elt.md#pipelines-and-steps) · [architecture](architecture-elt.md).

## Execution engine

**Product Map Item:** [`module.engine`](../../../../../product-map/module/engine.toon) · **Status:** ga · **Availability:** Platform base — present in every Edition (Basic, Standard, Advanced, Preview).

### What it is

The execution engine runs every Pipeline: it ingests Consignments, applies steps on embedded DuckDB and writes outputs. It owns execution lanes, concurrency, provenance and quarantine.

### Who benefits

Pipeline authors, operators watching runs, and auditors tracing a failed file or record.

### Key capabilities

- Every way a Pipeline executes — ingest, graph fork, at-rest job, park-then-drain — [Pipeline spec](spec-pipeline-execution.md)
- Consignment status, provenance and live gauges for audit — [architecture](architecture-elt.md)
- Bounded concurrency per Pipeline, Space and server with fair priority — [ingest spec](spec-consignments.md)
- Partitioned or single-file outputs with quarantine outcomes — [Pipeline spec](spec-sinks.md)
- Test-run a Pipeline before it goes live — [Pipeline spec](spec-sinks.md)
- Runs list and run detail screens — [user guide](user-guide-elt.md)
- Processor catalog of decoders, ingests and sinks — [engine feature-db](../engine/feature-db.md)

### Related hubs

[acquire](../acquire/index.md) · [engine](../engine/index.md) · [processor](../processor/index.md) · [config](../config/index.md)

Former engine pages and where they now live: [architecture](architecture-elt.md) · [architecture-pipeline-graph](architecture-elt.md) · [architecture-stage1](architecture-elt.md) · [feature-db](../engine/feature-db.md) · [plan-pipeline-execution](plan-elt-scope-requirements.md#exe1-pipeline-execution-pip-2--pip-7) · [spec-branch-aware-ingest](spec-ingest.md) · [spec-catalog-vs-executors](spec-pipeline-model.md) · [spec-consignment-addressing](spec-consignments.md) · [spec-consignment-concurrency](spec-consignments.md) · [spec-db-layer](../engine/spec-db-layer.md) · [spec-duckdb](../engine/spec-duckdb.md) · [spec-editable-round-trip](spec-pipeline-model.md) · [spec-execution-lanes](spec-pipeline-model.md) · [spec-ingest-wrap-spi](spec-ingest.md) · [spec-ingestion](spec-ingest.md) · [spec-live-execution](spec-sinks.md) · [spec-node-types](spec-sinks.md) · [spec-object-storage-export](spec-sinks.md) · [spec-output-sinks](spec-sinks.md) · [spec-parser-plugins](spec-parsing.md) · [spec-parsing-grammar](spec-parsing.md) · [spec-parsing-options](spec-parsing.md) · [spec-pipeline-test-run](spec-pipeline-execution.md) · [spec-plugin-ingester](spec-parsing.md) · [spec-post-sync-step-chains](spec-pipeline-execution.md) · [spec-step-park-drain](spec-pipeline-execution.md) · [spec-transforms-seams](spec-pipeline-execution.md) · [user-guide-run-detail](user-guide-elt.md) · [user-guide-runs](user-guide-elt.md)
