---
type: Feature
title: "SQL guard and helpers — architecture"
description: "How the two-layer SQL defence (guard, then sealed sandbox) is put together and where it is used."
tags: [sql, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# SQL guard and helpers — architecture

## Two layers, neither sufficient alone

```
candidate SQL ──► SqlGuard (lexical + DuckDB parse tree) ──finding──► refuse (no connection opened)
                        │ pass
                        ▼
      SqlSandbox.open ── trusted caller registers its inputs (file access still on)
                        ▼
      seal / sealAllowing(dirs) ── enable_external_access=false (or allow-listed dirs), lock_configuration=true
                        ▼
      untrusted SQL runs under memory / thread / timeout caps
```

- The guard runs **before** DuckDB sees the text, because `EXPLAIN` can evaluate smuggled functions while
  planning. It errs toward rejection: a false reject costs a repair round, a false accept is a hole.
- The sandbox is the backstop: once sealed, a guard bypass still cannot reach the filesystem or change
  configuration. `sealAllowing` keeps named directories readable for callers that need their own inputs.
- `SqlViews` makes the oracle and production Enrichment read a Dataset the same way.

## Where it is used

Per [BACKLOG](../../../../BACKLOG.md): Enrichment `transformSql` (run and preview), the Pipeline dry run's
scratch connection, virtual Dataset rename (`renameBaseTable`), persisted queries, and the Link Analysis
recursive-paths walk (`traversalThreads`). DuckDB connection handling in general:
[engine spec-duckdb](../engine/spec-duckdb.md).

Details: [spec](spec.md).
