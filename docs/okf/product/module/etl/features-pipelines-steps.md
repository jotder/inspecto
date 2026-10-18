---
type: Feature
title: "Pipelines and Steps — features"
description: "A **Pipeline** is a chain of **Steps**: collect → parse → transform Steps → sink."
tags: [etl, pipelines-steps, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Pipelines and Steps — features

**Availability:** Offering: all tiers; the `transform.mask` Step is Advanced.

## What it does

A **Pipeline** is a chain of **Steps**: `collect → parse → transform Steps → sink`. It is authored
as one file, `<id>_pipeline.toon`, and edited either as text or as a graph in the Pipeline editor.
The graph is an immutable IR (`PipelineGraph`) lifted from the flat config; the editor's save lowers
it back into the same file, so there is one source of truth.

Two vocabularies describe Steps:

- **Step kinds** — the node types the engine executes (`BuiltinNodeType`, served with their
  attribute specs by `GET /pipelines/node-types`). Plugins add more through the `PipelineNodeType`
  `ServiceLoader` seam ([node-types](../engine/spec-node-types.md)).
- **Step Processors** — the 119-entry product taxonomy the palette lists in eight families
  (`ProcessorCatalog`, `GET /pipelines/processor-catalog`). A processor is a label over a Step kind or
  an engine capability, not a class of its own; planned entries show as "Not yet available".

The as-built references are the [pipeline-graph bundle](../../index.md) and the
[step catalog](spec-step-catalog.md); the capability specs are
[pipeline-authoring](plan-pipeline-authoring.md) and
[pipeline-execution](../engine/plan-pipeline-execution.md).

## Where it fits

A Pipeline binds a [Collector](../acquire/architecture-collectors.md), the [parse stage](architecture-parsing-ingest.md), transform Steps
and one or more sinks into [storage](../entity-store/architecture-storage.md). Its at-rest half runs as a Job of type `pipeline`
on the [scheduler](../processor/architecture-jobs-scheduler.md). Code homes:

- Model, lift and validator: `platform/inspecto-engine/src/main/java/com/gamma/pipeline`
  (`PipelineGraph`, `PipelineLift`, `PipelineValidator`, `PipelineEditable`, `BuiltinNodeType`,
  `PipelineNodeTypes`, `ProcessorCatalog`).
- At-rest runner: `platform/inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`.
- Routes: `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java`,
  `PipelineListRoutes.java`, `PipelineHistoryRoutes.java`, `PipelineSettingsRoutes.java`.

More: [screens](user-guide-pipelines.md) · [API](api-pipelines-steps.md) · [architecture](architecture-pipelines-steps.md).
