---
type: TestsInventory
title: Real-time Detection — tests inventory
item: module.realtime
description: Generated list of the test classes for module.realtime, with how to run them.
tags: [tests, module]
---

# Real-time Detection — tests inventory (`module.realtime`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-realtime/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.realtime`](../../../../../product-map/module/realtime.toon).

3 test class(es): 2 unit, 1 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-realtime
mvn -o test -pl features/inspecto-realtime -Dtest=ControlApiRealtimeGateTest,RealtimeLatencyHarnessTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiRealtimeGateTest` | `com.gamma.realtime` | real-HTTP control-plane test | `features/inspecto-realtime/src/test/java/com/gamma/realtime/ControlApiRealtimeGateTest.java` | PA-9d over real HTTP (D-PA44): a windowed Alert Rule is saved only where the Real-time Detection Add-on is present |
| `RealtimeLatencyHarnessTest` | `com.gamma.realtime` | unit | `features/inspecto-realtime/src/test/java/com/gamma/realtime/RealtimeLatencyHarnessTest.java` | PA-9d: the latency harness of D-PA39 - "p95 ≤ 5 s from broker to Alert, ≤ 10 s to Incident" - on the PRODUCTION |
| `RealtimeSessionTest` | `com.gamma.realtime` | unit | `features/inspecto-realtime/src/test/java/com/gamma/realtime/RealtimeSessionTest.java` | PA-9d: {@link RealtimeSession}, the commit hook over real Parquet - only a producer's own committed files are evaluated; |
