---
type: TestsInventory
title: SQL guard and helpers — tests inventory
item: module.sql
description: Generated list of the test classes for module.sql, with how to run them.
tags: [tests, module]
---

# SQL guard and helpers — tests inventory (`module.sql`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-sql/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.sql`](../../../../../product-map/module/sql.toon).

6 test class(es): 6 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-sql
mvn -o test -pl platform/inspecto-sql -Dtest=SqlGuardFileFunctionContractTest,SqlGuardParseTreeTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `SqlGuardFileFunctionContractTest` | `com.gamma.sql` | unit | `platform/inspecto-sql/src/test/java/com/gamma/sql/SqlGuardFileFunctionContractTest.java` | {@code SQLGUARD-PARQUET-METADATA-1}: {@link SqlGuard} must refuse every DuckDB function that can take a |
| `SqlGuardParseTreeTest` | `com.gamma.sql` | unit | `platform/inspecto-sql/src/test/java/com/gamma/sql/SqlGuardParseTreeTest.java` | {@code SQLGUARD-COMMA-RELATION-1} — the second layer of {@link SqlGuard}: DuckDB's own parse tree |
| `SqlGuardTest` | `com.gamma.sql` | unit | `platform/inspecto-sql/src/test/java/com/gamma/sql/SqlGuardTest.java` | Adversarial tests for the SQL allow-list — the lexical half of the M6 sandbox (gap G4). The cases |
| `SqlOracleTest` | `com.gamma.sql` | unit | `platform/inspecto-sql/src/test/java/com/gamma/sql/SqlOracleTest.java` | Tests the {@code kpi-to-sql} validation oracle against real seeded partitions (M6, gap G4). It must: |
| `SqlSandboxTest` | `com.gamma.sql` | unit | `platform/inspecto-sql/src/test/java/com/gamma/sql/SqlSandboxTest.java` | Tests the hardened DuckDB connection — the native half of the M6 sandbox (gap G4). The point is the |
| `SqlViewsTest` | `com.gamma.sql` | unit | `platform/inspecto-sql/src/test/java/com/gamma/sql/SqlViewsTest.java` | Pins the rendering rules of the only place in the product where a filesystem path becomes SQL text. |
