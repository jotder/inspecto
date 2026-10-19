---
type: TestsInventory
title: Event model and log — tests inventory
item: module.event
description: Generated list of the test classes for module.event, with how to run them.
tags: [tests, module]
---

# Event model and log — tests inventory (`module.event`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-event/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.event`](../../../../../product-map/module/event.toon).

8 test class(es): 8 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-event
mvn -o test -pl platform/inspecto-event -Dtest=AuditChainTest,CurrentEventSinkTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AuditChainTest` | `com.gamma.event` | unit | `platform/inspecto-event/src/test/java/com/gamma/event/AuditChainTest.java` | ASSURE-AUDIT-CHAIN-1 at the seam that builds the chain: {@link EventLog#emit} links every AUDIT / |
| `CurrentEventSinkTest` | `com.gamma.event` | unit | `platform/inspecto-event/src/test/java/com/gamma/event/CurrentEventSinkTest.java` | The contract's {@link EventSink#current()} finds this module's implementation through ServiceLoader. |
| `DbEventStoreTest` | `com.gamma.event` | unit | `platform/inspecto-event/src/test/java/com/gamma/event/DbEventStoreTest.java` | {@link DbEventStore} — the shared event backend behind {@code -Devents.backend=db} (D6). |
| `EventCoreTest` | `com.gamma.event` | unit | `platform/inspecto-event/src/test/java/com/gamma/event/EventCoreTest.java` | Unit tests for the dependency-free event core: {@link EventLevel}, {@link Event}, |
| `EventLogAndAppenderTest` | `com.gamma.event` | unit | `platform/inspecto-event/src/test/java/com/gamma/event/EventLogAndAppenderTest.java` | Tests the {@link EventLog} facade (store swap with startup-event draining + the |
| `ParquetEventStoreTest` | `com.gamma.event` | unit | `platform/inspecto-event/src/test/java/com/gamma/event/ParquetEventStoreTest.java` | Round-trip tests for {@link ParquetEventStore}: append → flush to rolling Hive-partitioned Parquet → |
| `SecretScrubberTest` | `com.gamma.event` | unit | `platform/inspecto-event/src/test/java/com/gamma/event/SecretScrubberTest.java` | Unit tests for the secret-redaction pipeline that guards the append-only event store. |
| `MetricRegistryTest` | `com.gamma.metrics` | unit | `platform/inspecto-event/src/test/java/com/gamma/metrics/MetricRegistryTest.java` | Unit tests for the zero-dep {@link MetricRegistry} — counter/gauge/histogram |
