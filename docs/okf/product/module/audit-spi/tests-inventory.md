---
type: TestsInventory
title: Audit contract — tests inventory
item: module.audit-spi
description: Generated list of the test classes for module.audit-spi, with how to run them.
tags: [tests, module]
---

# Audit contract — tests inventory (`module.audit-spi`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `spi/inspecto-audit-spi/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.audit-spi`](../../../../../product-map/module/audit-spi.toon).

2 test class(es): 2 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl spi/inspecto-audit-spi
mvn -o test -pl spi/inspecto-audit-spi -Dtest=AuditAttrsTest,EventSinkHolderTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AuditAttrsTest` | `com.gamma.audit` | unit | `spi/inspecto-audit-spi/src/test/java/com/gamma/audit/AuditAttrsTest.java` | Pins {@link AuditAttrs#ALL} against the class's own constants by reflection: every public static |
| `EventSinkHolderTest` | `com.gamma.audit` | unit | `spi/inspecto-audit-spi/src/test/java/com/gamma/audit/EventSinkHolderTest.java` |  |
