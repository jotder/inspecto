---
type: TestsInventory
title: Access policy (roles, grants, row scope, audit trail) — tests inventory
item: module.access
description: Generated list of the test classes for module.access, with how to run them.
tags: [tests, module]
---

# Access policy (roles, grants, row scope, audit trail) — tests inventory (`module.access`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-access/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.access`](../../../../../product-map/module/access.toon).

2 test class(es): 2 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-access
mvn -o test -pl platform/inspecto-access -Dtest=AccessFileCacheSameTickTest,ComponentAccessAsOwnerTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AccessFileCacheSameTickTest` | `com.gamma.access` | unit | `platform/inspecto-access/src/test/java/com/gamma/access/AccessFileCacheSameTickTest.java` | Every access file cache must see a rewrite that keeps the SAME size and lands in the SAME mtime tick (a fast |
| `ComponentAccessAsOwnerTest` | `com.gamma.access` | unit | `platform/inspecto-access/src/test/java/com/gamma/access/ComponentAccessAsOwnerTest.java` | LD-1: the Subject-less R3 decider ({@code canViewAs}) and the caller-less PDP check ({@code RowScope.visibleAs}). |
