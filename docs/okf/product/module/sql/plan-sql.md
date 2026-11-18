---
type: Feature
title: "SQL guard and helpers — plan"
description: "Requirement of record for the SQL guard, sandbox and oracle: what is required, built, left and refused."
tags: [sql, plan]
timestamp: 2026-10-19T00:00:00Z
---

# SQL guard and helpers — plan

**Product Map Item:** [`module.sql`](../../../../../product-map/module/sql.toon) · **Status:** ga

## Required

Any SQL the platform runs on a user's or an agent's behalf must not reach the host's files, extensions or
system surface, and must not change state. NFR-4 (air-gap operation) in
[REQUIREMENTS](../../../../REQUIREMENTS.md) and the "working as designed" gates in
[BACKLOG](../../../../BACKLOG.md) (`SqlGuard` is one of them) depend on it.

## Built

- `SqlGuard` — lexical and parse-tree allow-list for a single read-only `SELECT`/`WITH` (since 3.6.0).
- `SqlSandbox` + `SqlSandboxPolicy` — a capped DuckDB connection, sealed before untrusted SQL runs.
- `SqlOracle` — validates generated SQL against real catalog partitions.
- `SqlViews` — the one place that decides how a stored Dataset is read.
- Closed BACKLOG rows: `SQLGUARD-PARQUET-METADATA-1` and `SQLGUARD-COMMA-RELATION-1` (guard gaps),
  `SEC-ENRICH-TRANSFORM-SQL-UNSEALED-1` (Enrichment `transformSql` guarded and sealed),
  `SEC-DRYRUN-EXPR-UNSEALED-1` (dry-run scratch connection sealed), `VIRTUAL-DATASET-SQL-RENAME-1`
  (`SqlGuard.renameBaseTable`), and the bound-parameter rows `DECISION-RULE-SQL-GUARD-1`,
  `MEASURE-SQL-BIND-1`, `LA-FILTER-SQL-BIND-1`.

## Left

No open BACKLOG row targets this module.

## Refused

`CREATE MACRO` outside `AUTHORING-REDESIGN-1` (d), and handing callers a raw `Connection`, are on the
refused list in [BACKLOG](../../../../BACKLOG.md).
