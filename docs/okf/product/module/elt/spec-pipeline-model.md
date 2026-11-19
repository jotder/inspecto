---
type: Concept
title: Pipeline model — node types, the Step catalog, transforms and the editable round-trip
description: The backend model of a Pipeline: the PipelineNodeType / PipelineNodeExecutor plugin seams, the token model and the transform family, the Step Processor catalog vs the real executors, the transform seams, and the PipelineEditable lift/lower round-trip with every named refusal, settings, dry-run context, route branches, the parser family and the bundles.
resource: platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Pipeline model — node types, the Step catalog, transforms and the editable round-trip

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. **spec-pipeline-model.md** · [spec-pipeline-execution.md](spec-pipeline-execution.md) · [spec-sinks.md](spec-sinks.md)

**Contents**

- [Pipeline node types & the node-type plugin seam](spec-pipeline-model-node-types.md#pipeline-node-types--the-node-type-plugin-seam)
- [Step Processor catalog vs. real node executors](spec-pipeline-model-processor-catalog.md#step-processor-catalog-vs-real-node-executors)
- [Transforms & Modularity Seams](spec-pipeline-model-round-trip.md#transforms--modularity-seams)
- [The editable round-trip — the editor's graph ⇄ the canonical config](spec-pipeline-model-round-trip.md#the-editable-round-trip--the-editors-graph--the-canonical-config)

**What this covers.** What a Pipeline *is* to the engine. Every Step on the canvas is a node of a known type; this part lists
those types, what each one emits, and how a plugin adds a new one. It explains why the Step catalog the
author picks from has fewer real executors behind it than entries, how the SQL Step and the Record
Transformer work, and how the editor's graph is saved to — and read back from — the `*_pipeline.toon` file
the engine runs, including every named reason a save is refused. How a Pipeline then runs is
[Part II](spec-pipeline-execution.md) below; the editor screens are [user-guide-elt.md](user-guide-elt.md).

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Pipeline node types and the node-type plugin seam](spec-pipeline-model-node-types.md)** — Pipeline node types & the node-type plugin seam
2. **[Step Processor catalog vs. real node executors](spec-pipeline-model-processor-catalog.md)** — Step Processor catalog vs. real node executors
3. **[Transforms, modularity seams and the editable round-trip](spec-pipeline-model-round-trip.md)** — Transforms & Modularity Seams · The editable round-trip — the editor's graph ⇄ the canonical config
4. **[The editable round-trip — the parser family onward](spec-pipeline-model-parser-family.md)**
