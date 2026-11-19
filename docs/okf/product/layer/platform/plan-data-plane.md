---
type: Capability
title: Data plane (DAT)
description: Datasets over partitioned Parquet and the relations they resolve to; Query as a Component with $-Parameters and a Result Set; the guarded SQL execution stack (SqlSandbox, SqlGuard, ExpressionGuard); /bi/query and time grains; Matrix materialization and superseded-revision retention; the DuckDB runtime and its timezone rules; the operational-store roster and Postgres; DuckLake, the warehouse runbook and object-storage export. The requirement of record for the DAT area, its specification, its decisions, and what was refused.
resource: platform/inspecto-engine/src/main/java/com/gamma/query, platform/inspecto-sql/src/main/java/com/gamma/sql, inspecto/src/main/java/com/gamma/service/OperationalDb.java
tags: [dat, capability, dataset, query, parameters, result-set, bi-query, sql-guard, expression-guard, matrix, materialize, duckdb, postgres, operational-db, ducklake, warehouse, timezone]
timestamp: 2026-09-08T00:00:00Z
---

# Data plane — capability spec (`DAT`)

> **What this page is.** The single entry point for the DAT capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Seventh of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §6-B, §7 — binding). A **Dataset** is the umbrella for any queryable
> relation the BI layer can bind to — **Table** | **Derived Table** | **Reference Dataset** | **View**; ⛔
> never *Data Store* (that is the physical **Store**). A **Matrix** is a Derived Table's user-facing label, <!-- vocab-allow: states the Data Store ban it enforces -->
> never a new type; *Cube* stays a verb. A **Query** is a first-class Component `{type: sql | structured, <!-- vocab-allow: states the Cube-noun ban it enforces -->
> source Dataset, text | model, Parameters}`; a **Parameter** is a `$`-namespace runtime binding — ⛔ never
> conflated with a `:fieldValue` rule-template placeholder or a `${ENV:KEY}` secret; a **Result Set** is the
> semantic description of a Query's output. A **Measure** is a BI aggregation — ⛔ never *Metric*. A
> **Watermark** unqualified means event-time completeness; the incremental read cursor is an *incremental
> cursor*.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Purpose, scope and requirements of record](plan-data-plane-scope.md)** — 1. Purpose & scope · 2. Requirements of record
2. **[Specification](plan-data-plane-specification.md)** — 3. Specification
3. **[Decisions, not built, refused and verification](plan-data-plane-decisions-verification.md)** — 4. Decisions · 5. Not built · 6. Refused & superseded · 7. As-built mechanism (pointers only) · 8. Verification — board rows: `DUCKLAKE-REAL-DRIVER-FAILURE-PROOF-1`
