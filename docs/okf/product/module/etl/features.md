---
type: Feature
title: "Pipelines, parsing and ingest — features"
description: "A Pipeline is a chain of Steps (collect → parse → transform Steps → sink); Stage-1 ingest is the road from a file in its inbox to typed rows in a Parquet store."
tags: [etl, pipelines-steps, parsing-ingest, features, presales]
timestamp: 2026-11-18T00:00:00Z
---

# Pipelines, parsing and ingest — features

**What this covers.** What the ETL module does, in one page for presales and stakeholders: a **Pipeline**
is the chain of Steps that turns files from a Collector into stored, typed rows, and **Stage-1 ingest** is
the part of that chain that reads each file, checks it against its Schema and commits it atomically. Each
section names its edition availability, where it fits in the product and its code homes.

**Contents**

- [Pipelines and Steps](#pipelines-and-steps)
- [Parsing and ingest](#parsing-and-ingest)

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
  `ServiceLoader` seam ([pipeline model](../engine/spec-pipeline-model.md)).
- **Step Processors** — the product taxonomy the palette lists in eight families
  (`ProcessorCatalog`, `GET /pipelines/processor-catalog`; the counts are derived in the
  [step catalog](spec-step-catalog.md)). A processor is a label over a Step kind or an engine capability,
  not a class of its own; planned entries show as "Not yet available".

The as-built references are the [pipeline-graph bundle](../../index.md) and the
[step catalog](spec-step-catalog.md); the capability specs are
[the ETL plan](plan.md) and the [engine plan](../engine/plan.md).

### Where it fits

A Pipeline binds a [Collector](../acquire/architecture.md), the [parse stage](architecture.md), transform Steps
and one or more sinks into [storage](../entity-store/architecture.md). Its at-rest half runs as a Job of type `pipeline`
on the [scheduler](../processor/architecture.md). Code homes:

- Model, lift and validator: `platform/inspecto-engine/src/main/java/com/gamma/pipeline`
  (`PipelineGraph`, `PipelineLift`, `PipelineValidator`, `PipelineEditable`, `BuiltinNodeType`,
  `PipelineNodeTypes`, `ProcessorCatalog`).
- At-rest runner: `platform/inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`.
- Routes: `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java`,
  `PipelineListRoutes.java`, `PipelineHistoryRoutes.java`, `PipelineSettingsRoutes.java`.

More: [screens](user-guide.md) · [API](api.md#pipelines-and-steps) · [architecture](architecture.md).

## Parsing and ingest

**Availability:** Offering: all tiers; the ASN.1 frontend (module `inspecto-telecom-asn1`) is Advanced only (DATA-TIERS-1 DT-12, 2026-11-17).

### What it does

Stage-1 ingest is the road from a file in a Pipeline's inbox to typed rows in a Parquet store. It
groups inbox files into a **Consignment**, parses each file with one of the parse frontends, maps and
casts the columns to the declared Schema, writes partitioned Parquet, and commits the whole
Consignment atomically. A file that cannot be read goes to the **quarantine** tree; rows that fail
parsing go to a reject file beside it.

The capability spec of record is [the ETL plan](plan.md); the design is
[the engine architecture](../engine/architecture.md). This page is the module summary.

Code homes:

- Poll cycle and Consignment coordinator: `platform/inspecto-engine/src/main/java/com/gamma/inspector`
  (`CollectorProcessor`, `ConsignmentIngestor`, `MultiCollectorProcessor`).
- Planner, writer and Unpack: `platform/inspecto-etl/src/main/java/com/gamma/etl`
  (`ConsignmentPlanner`, `PartitionWriter`, `CommitLog`, `MarkerManager`, package `unpack`).
- Parser registry: `platform/inspecto-engine/src/main/java/com/gamma/parse` (`ParserPlugin`, `Parsers`).
- Control routes: `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` and
  `inspecto/src/main/java/com/gamma/control/RunRoutes.java`.

### Where it fits

Ingest starts where a [Collector](../acquire/architecture.md) has landed a file in `dirs.poll`. It ends at a
committed store that [Datasets](../../layer/product/architecture-studio-bi.md) and at-rest [Steps](architecture.md) read. Typing
rules come from the Schema registry (metamodel); storage layout is described in [storage](../entity-store/architecture.md).
Every Consignment runs on a virtual thread bounded by `processing.threads`; many Pipelines share one
JVM under `MultiCollectorProcessor`.

More: [screens](user-guide.md#runs-and-processing-status) · [API](api.md#parsing-and-ingest) · [architecture](architecture.md).
