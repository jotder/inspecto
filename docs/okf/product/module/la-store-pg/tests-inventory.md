---
type: TestsInventory
title: Link analysis PostgreSQL store — tests inventory
item: module.la-store-pg
description: Generated list of the test classes for module.la-store-pg, with how to run them.
tags: [tests, module]
---

# Link analysis PostgreSQL store — tests inventory (`module.la-store-pg`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `la/inspecto-la-store-pg/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.la-store-pg`](../../../../../product-map/module/la-store-pg.toon).

3 test class(es): 3 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl la/inspecto-la-store-pg
mvn -o test -pl la/inspecto-la-store-pg -Dtest=PgEmbedGrantStoreTest,PgInvestigationStoreContractTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `PgEmbedGrantStoreTest` | `com.gamma.la.core` | unit | `la/inspecto-la-store-pg/src/test/java/com/gamma/la/core/PgEmbedGrantStoreTest.java` | {@link EmbedGrantStoreContract} over {@link PgInvestigationStore} on a real PostgreSQL (LA-EMBED-PG-STORE-1), one throwaway schema per case. |
| `PgInvestigationStoreContractTest` | `com.gamma.la.core` | unit | `la/inspecto-la-store-pg/src/test/java/com/gamma/la/core/PgInvestigationStoreContractTest.java` | {@link InvestigationStoreContract} (la-core's test-jar: the SAME 29 cases the filesystem store runs) over |
| `PgInvestigationStoreTwoJvmRaceTest` | `com.gamma.la.core` | unit | `la/inspecto-la-store-pg/src/test/java/com/gamma/la/core/PgInvestigationStoreTwoJvmRaceTest.java` | {@link InvestigationStoreTwoJvmRace} over {@link PgInvestigationStore}: two real JVMs, each with its OWN connection pool, on ONE |
