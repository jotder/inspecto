---
type: Feature
title: "Storage — features"
description: "Inspecto has no ORM and no single database."
tags: [entity-store, storage, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Storage — features

**Availability:** Offering: all tiers (embedded DuckDB everywhere); Postgres for operational data, DuckLake registration and the compliance archive are Standard and above.

## What it does

Inspecto has no ORM and no single database. Each Space keeps three physically distinct classes of
state ([db-layer](../engine/spec-db-layer.md) §1):

| Class | What it is | Where it lives | Engine |
|---|---|---|---|
| **Business data** (the lakehouse) | ingested and derived rows | Hive-partitioned Parquet (or CSV) under `<data root>/<store>/**` | files, read through DuckDB `read_parquet` / `read_csv` |
| **Configuration** | Pipelines, Jobs, components, connections, settings | TOON files under the Space's `config/`, with `.history/` snapshots | plain files, no JDBC |
| **Operational data** | the platform's own facts: Alerts, Incidents, job runs, ingest status, ledgers, events | one DuckDB file per family under `<space>/duckdb/`, or Postgres by selection | JDBC |

A **Sink** is where a Pipeline writes business data. The capability spec of record is
[data-plane](../../layer/platform/plan-data-plane.md).

## Where it fits

Everything else sits on it: [parsing-ingest](../etl/architecture-parsing-ingest.md) and [Pipelines](../etl/architecture-pipelines-steps.md)
write business data; [Studio](../../layer/product/architecture-studio-bi.md) reads it through Datasets; every add-on keeps its
records in the operational stores; [Spaces](../config/architecture-spaces.md) decide where each Space's roots are. Code homes:

- Space roots: `inspecto/src/main/java/com/gamma/service/SpaceRoot.java` (`DirSpaceRoot`,
  `LegacySpaceRoot`).
- Operational stores: `inspecto/src/main/java/com/gamma/service/ServiceStores.java` and
  `inspecto/src/main/java/com/gamma/service/OperationalDb.java`.
- Reads: `platform/inspecto-sql/src/main/java/com/gamma/sql/SqlViews.java`,
  `platform/inspecto-engine/src/main/java/com/gamma/query/DatasetRelation.java`.
- Writes: `platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java`.
- Configuration history: `ComponentStore` and `inspecto/src/main/java/com/gamma/control/PipelineHistory.java`.

More: [screens](user-guide-storage.md) · [API](api-storage.md) · [architecture](architecture-storage.md).
