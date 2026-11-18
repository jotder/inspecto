---
type: Feature
title: "SQL guard and helpers — spec"
description: "Classes, entry points and system properties of the com.gamma.sql package."
tags: [sql, spec]
timestamp: 2026-10-19T00:00:00Z
---

# SQL guard and helpers — spec

Module `platform/inspecto-sql` (manifest `platform/inspecto-sql/src/main/resources/META-INF/inspecto/module.toon`:
`buildRole: foundation`, `offeringRole: base`). Package `com.gamma.sql`; depends on `inspecto-util`
(`SqlIdent`, `DuckDbUtil`) and the config-spec `Finding` model.

| Class | Role | Entry points |
|---|---|---|
| `platform/inspecto-sql/src/main/java/com/gamma/sql/SqlGuard.java` | Allow-list for one read-only statement. Returns `ERROR` `Finding`s; empty means pass. Never throws. | `check(sql)`, `check(sql, trustedRelation)`, `isReadOnly`, `parseSelectNode`, `renameBaseTable` |
| `platform/inspecto-sql/src/main/java/com/gamma/sql/SqlSandbox.java` | Temporary DuckDB database; extension autoload off; caps applied. `AutoCloseable` (deletes the DB and its WAL). | `open(policy)`, `seal()`, `sealAllowing(conn, dirs[, files])`, `disableExtensionAutoload` |
| `platform/inspecto-sql/src/main/java/com/gamma/sql/SqlSandboxPolicy.java` | Record `(memoryLimit, maxThreads, queryTimeoutSeconds)`, normalised in the constructor. | `defaultPolicy()`, `withCaps(...)`, `traversalThreads()` |
| `platform/inspecto-sql/src/main/java/com/gamma/sql/SqlOracle.java` | Guard, then load inputs (sample cap 10 000 rows), seal, run the candidate; returns the produced columns and up to five preview rows, or the verbatim error. | — |
| `platform/inspecto-sql/src/main/java/com/gamma/sql/SqlViews.java` | DuckDB read expressions for Parquet/CSV Datasets; `hive_types_autocast=0`; `storeReadRoot` reads `database/` when present. | `ext`, `storeReadRoot` |

## Guard rules

- One statement (a single trailing `;` allowed), starting with `SELECT` or `WITH … SELECT`; DDL/DML refused.
- Comments are stripped outside literals before the token scan; an unterminated block comment is refused.
- Every function call, bare or double-quoted, is tested against `BLOCKED_FUNCTION`: file readers and writers,
  `*_scan`, `parquet_*`, `duckdb_*`, `pragma_*`, SQL-executing functions, extension surfaces. Only the
  path-free generators in `SAFE_TABLE_FUNCTIONS` pass. `install` / `load` / `pragma` / `set` are refused.
- Text that passes is parsed by DuckDB (`json_serialize_sql`) and every relation in the tree is judged.
- `SqlGuardFileFunctionContractTest` fails the build when a DuckDB upgrade adds a table function the
  pattern does not refuse.

## System properties

| Property | Default | Read by |
|---|---|---|
| `assist.sql.memory_limit` | `1GB` | `SqlSandboxPolicy.defaultPolicy` |
| `assist.sql.threads` | `2` | `SqlSandboxPolicy.defaultPolicy` |
| `assist.sql.timeout_seconds` | `30` | `SqlSandboxPolicy.defaultPolicy` |
| `assist.sql.traversal_threads` | `4` | `SqlSandboxPolicy.traversalThreads` (Link Analysis walk only) |

Tests: [tests-inventory](tests-inventory.md).
