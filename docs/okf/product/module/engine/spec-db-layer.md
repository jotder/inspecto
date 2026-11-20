---
type: Reference
title: Database / persistence layer
description: How state is stored on disk — the three data classes, the operational store inventory and relational schemas, the per-space file topology, and how to run operational data on Postgres.
resource: features/inspecto-ops/src/main/java/com/gamma/ops
tags: [persistence, duckdb, postgres, schema, topology, stores]
timestamp: 2026-07-16T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-engine
---

# Database / Persistence Layer
> **Deep reference — the detail tier.** Start at [Engine section index](../../index.md) for the summary; this page is the long form it points to. *(Moved from the retired root-level `DB_LAYER.md` (docs consolidation, 2026-07-16).)*

> **Scope:** how Inspecto stores state on disk — the three data classes, the operational
> (relational) table schemas, the per-space file topology, and how to run operational data on
> Postgres. Vocabulary follows [`GLOSSARY.md`](../../../../GLOSSARY.md) (**Store** = physical backend,
> **Dataset** = queryable relation).

> **⚠️ Keep this current.** This doc is derived from the source files listed below — when any of
> them changes (a table's DDL/columns, a store's backend wiring, the per-space file layout, a
> `-D*.backend` toggle, or Postgres behavior), update the matching section here (and
> [`archived-documents/plans-archive/db-browser-design.md`](../../../../archived-documents/plans-archive/db-browser-design.md) if browsable tables/stores
> change). A `PostToolUse` hook (`.claude/hooks/post-tool-db-layer-doc.sh`) reminds you on edits to
> these files. Source of truth for the DDL is each store's `initSchema()` — keep the SQL blocks in §3
> byte-accurate.
> **Derived from:** `ops/DbObjectStore` · `ops/link/DbLinkStore` · `ops/note/DbNoteStore` ·
> `service/DbStatusStore` · `job/DbJobRunStore` · `pipeline/exec/DbProvenanceStore` ·
> `acquire/DbAcquisitionLedger` · `event/ParquetEventStore` · `service/ServiceStores` ·
> `service/SpaceRoot` · `util/JdbcDrivers` · `util/DuckDbUtil`.

---

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Three data classes and the operational store inventory](spec-db-layer-data-classes.md)** — 1. Three data classes · 2. Operational store inventory
2. **[Schemas (operational, non-Parquet)](spec-db-layer-schemas.md)** — 3. Schemas (operational, non-Parquet)
3. **[Schemas — consignment outputs onward](spec-db-layer-schemas-continued.md)**
4. **[File topology, Postgres and the raw tables](spec-db-layer-topology-postgres.md)** — 4. File topology (per space) · 5. Running operational data on Postgres · 6. Browsing the raw tables · Why `__consignment_id` is on the row, not just in the registry · Proving the JDBC stores on real PostgreSQL (DAT-6)
