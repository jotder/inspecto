---
type: Feature
title: "Parsing and ingest — features"
description: "Stage-1 ingest is the road from a file in a Pipeline's inbox to typed rows in a Parquet store."
tags: [etl, parsing-ingest, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Parsing and ingest — features

**Availability:** Offering: all tiers; the ASN.1 frontend (module `inspecto-telecom-asn1`) is Standard and above.

## What it does

Stage-1 ingest is the road from a file in a Pipeline's inbox to typed rows in a Parquet store. It
groups inbox files into a **Consignment**, parses each file with one of the parse frontends, maps and
casts the columns to the declared Schema, writes partitioned Parquet, and commits the whole
Consignment atomically. A file that cannot be read goes to the **quarantine** tree; rows that fail
parsing go to a reject file beside it.

The capability spec of record is [ingestion.md](plan-ingestion.md); the design is
[stage1-architecture.md](../engine/architecture-stage1.md). This page is the module summary.

Code homes:

- Poll cycle and Consignment coordinator: `platform/inspecto-engine/src/main/java/com/gamma/inspector`
  (`CollectorProcessor`, `ConsignmentIngestor`, `MultiCollectorProcessor`).
- Planner, writer and Unpack: `platform/inspecto-etl/src/main/java/com/gamma/etl`
  (`ConsignmentPlanner`, `PartitionWriter`, `CommitLog`, `MarkerManager`, package `unpack`).
- Parser registry: `platform/inspecto-engine/src/main/java/com/gamma/parse` (`ParserPlugin`, `Parsers`).
- Control routes: `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` and
  `inspecto/src/main/java/com/gamma/control/RunRoutes.java`.

## Where it fits

Ingest starts where a [Collector](../acquire/architecture-collectors.md) has landed a file in `dirs.poll`. It ends at a
committed store that [Datasets](../../layer/product/architecture-studio-bi.md) and at-rest [Steps](architecture-pipelines-steps.md) read. Typing
rules come from the Schema registry (metamodel); storage layout is described in [storage](../entity-store/architecture-storage.md).
Every Consignment runs on a virtual thread bounded by `processing.threads`; many Pipelines share one
JVM under `MultiCollectorProcessor`.

More: [screens](user-guide-parsing-ingest.md) · [API](api-parsing-ingest.md) · [architecture](architecture-parsing-ingest.md).
