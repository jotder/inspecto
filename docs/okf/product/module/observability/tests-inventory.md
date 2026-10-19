---
type: TestsInventory
title: Observability (metrics endpoint + events feed API) — tests inventory
item: module.observability
description: Generated list of the test classes for module.observability, with how to run them.
tags: [tests, module]
---

# Observability (metrics endpoint + events feed API) — tests inventory (`module.observability`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-observability/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.observability`](../../../../../product-map/module/observability.toon).

8 test class(es): 3 unit, 5 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-observability
mvn -o test -pl features/inspecto-observability -Dtest=ControlApiEventsPageTest,ControlApiEventsReadMaskingTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiEventsPageTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-observability/src/test/java/com/gamma/control/ControlApiEventsPageTest.java` | {@code GET /events} cursor pagination over real HTTP (api-contract-design §7): on the {@code /api/v1} |
| `ControlApiEventsReadMaskingTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-observability/src/test/java/com/gamma/control/ControlApiEventsReadMaskingTest.java` | OBJECT-READ-MASKING-RESIDUALS-1: a stored {@code OBJECT_OPENED} row may hold the object's title RAW (the log is append-only), |
| `ControlApiEventsTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-observability/src/test/java/com/gamma/control/ControlApiEventsTest.java` | Integration tests for the Phase-1 Event Viewer routes over real HTTP: the live-tail feed |
| `MetricsExpositionTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-observability/src/test/java/com/gamma/control/MetricsExpositionTest.java` | EDG-01 cell 5, the POSITIVE half: with {@code inspecto-observability} on the classpath, {@code GET /metrics} <!-- vocab-allow: quoted Javadoc --> |
| `SavedViewUnmodelledKeysTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-observability/src/test/java/com/gamma/control/SavedViewUnmodelledKeysTest.java` | {@code MODULE-REORG-P4-2} (Saved View): {@code POST /events/views} read only the named fields of its body, so any other |
| `EventRoutesContractTest` | `com.gamma.eventsapi` | unit | `features/inspecto-observability/src/test/java/com/gamma/eventsapi/EventRoutesContractTest.java` | {@link EventRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `ObservabilityRoutesManifestParityTest` | `com.gamma.eventsapi` | unit | `features/inspecto-observability/src/test/java/com/gamma/eventsapi/ObservabilityRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s |
| `MetricsRoutesContractTest` | `com.gamma.metricsapi` | unit | `features/inspecto-observability/src/test/java/com/gamma/metricsapi/MetricsRoutesContractTest.java` | {@link MetricsRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
