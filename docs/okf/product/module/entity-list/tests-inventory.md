---
type: TestsInventory
title: Entity lists — tests inventory
item: module.entity-list
description: Generated list of the test classes for module.entity-list, with how to run them.
tags: [tests, module]
---

# Entity lists — tests inventory (`module.entity-list`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-entity-list/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.entity-list`](../../../../../product-map/module/entity-list.toon).

6 test class(es): 2 unit, 4 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-entity-list
mvn -o test -pl features/inspecto-entity-list -Dtest=ControlApiEntityListAssuranceTest,ControlApiEntityListCategoryTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiEntityListAssuranceTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-entity-list/src/test/java/com/gamma/control/ControlApiEntityListAssuranceTest.java` | ASSURE-ENTITY-LISTS-1 (WS-12) over real HTTP, ARMED: range / CIDR entries and expiry through |
| `ControlApiEntityListCategoryTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-entity-list/src/test/java/com/gamma/control/ControlApiEntityListCategoryTest.java` | LA-GROW-EXCLUDE-1 phase 4 over real HTTP — admin category exclusion lists on the Entity List routes: the three |
| `ControlApiEntityListRegisterDatasetTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-entity-list/src/test/java/com/gamma/control/ControlApiEntityListRegisterDatasetTest.java` | ASSURE-ENTITY-LISTS-RESIDUALS-1 (3) over real HTTP: {@code POST /entity-lists/{id}/register-dataset} registers the |
| `ControlApiEntityListTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-entity-list/src/test/java/com/gamma/control/ControlApiEntityListTest.java` | LA-17 step 3 — Entity Lists over real HTTP, against the binding wire contract |
| `EntityListAbsentSurfaceParityTest` | `com.gamma.control` | unit | `features/inspecto-entity-list/src/test/java/com/gamma/control/EntityListAbsentSurfaceParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s |
| `EntityListRoutesContractTest` | `com.gamma.entitylist` | unit | `features/inspecto-entity-list/src/test/java/com/gamma/entitylist/EntityListRoutesContractTest.java` | {@link EntityListRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
