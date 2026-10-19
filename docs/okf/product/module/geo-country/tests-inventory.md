---
type: TestsInventory
title: MaxMind geo-country resolver — tests inventory
item: module.geo-country
description: Generated list of the test classes for module.geo-country, with how to run them.
tags: [tests, module]
---

# MaxMind geo-country resolver — tests inventory (`module.geo-country`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-geo-country/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.geo-country`](../../../../../product-map/module/geo-country.toon).

2 test class(es): 1 unit, 1 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-geo-country
mvn -o test -pl providers/inspecto-geo-country -Dtest=MaxMindAuditRowTest,MaxMindGeoCountryResolverTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `MaxMindAuditRowTest` | `com.gamma.control` | real-HTTP control-plane test | `providers/inspecto-geo-country/src/test/java/com/gamma/control/MaxMindAuditRowTest.java` | The join the core's {@code ControlApiGeoIpTest} cannot see (it injects a fake resolver): with THIS module on the |
| `MaxMindGeoCountryResolverTest` | `com.gamma.geocountry` | unit | `providers/inspecto-geo-country/src/test/java/com/gamma/geocountry/MaxMindGeoCountryResolverTest.java` | The MaxMind binding against a real {@code .mmdb} written by {@link TestMmdb} (D12: country only, fail-soft). |
