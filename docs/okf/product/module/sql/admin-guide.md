---
type: Feature
title: "SQL guard and helpers — admin guide"
description: "Tuning the SQL sandbox caps and reading SQL guard refusals."
tags: [sql, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# SQL guard and helpers — admin guide

The module has no files of its own on disk and no routes. An operator meets it through two things.

## Sandbox caps (JVM flags)

| Flag | Default | Effect |
|---|---|---|
| `-Dassist.sql.memory_limit` | `1GB` | DuckDB `memory_limit` on every sandbox connection |
| `-Dassist.sql.threads` | `2` | DuckDB `threads` on sandbox connections |
| `-Dassist.sql.timeout_seconds` | `30` | JDBC statement timeout per sandbox query |
| `-Dassist.sql.traversal_threads` | `4` | Link Analysis recursive-paths walk only; the install limit `traversal.threads` in `la-limits.toon` overrides it |

Values are normalised by `SqlSandboxPolicy`, so they are a safety backstop, not a tuning knob
([ADVANCED_GUIDE](../../../../ADVANCED_GUIDE.md) §5.7 and its property table). Each sandbox uses a
temporary DuckDB file that is deleted, with its WAL, on close.

## Common failures

| Symptom | Cause | Action |
|---|---|---|
| A query, an Enrichment `transformSql` or a virtual Dataset is refused with a SqlGuard finding | Not one read-only `SELECT`/`WITH`, or it calls a blocked function (`read_*`, `*_scan`, `parquet_*`, `duckdb_*`, `pragma_*`, `glob`, `query` …) | Rewrite it against the registered Dataset; reading files directly is refused by design |
| A preview fails with a DuckDB timeout or out-of-memory error | A sandbox cap was hit | Narrow the query; raise the flag only if the host has room |
| A DuckDB upgrade fails the build at `SqlGuardFileFunctionContractTest` | The new DuckDB adds a table function the guard does not refuse | Extend `BLOCKED_FUNCTION` or `SAFE_TABLE_FUNCTIONS` in `platform/inspecto-sql/src/main/java/com/gamma/sql/SqlGuard.java` |
