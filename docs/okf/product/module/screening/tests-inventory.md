---
type: TestsInventory
title: Screening — tests inventory
item: module.screening
description: Generated list of the test classes for module.screening, with how to run them.
tags: [tests, module]
---

# Screening — tests inventory (`module.screening`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-screening/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.screening`](../../../../../product-map/module/screening.toon).

12 test class(es): 11 unit, 1 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-screening
mvn -o test -pl features/inspecto-screening -Dtest=ControlApiScreeningTest,ScreeningAbsentSurfaceParityTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiScreeningTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-screening/src/test/java/com/gamma/control/ControlApiScreeningTest.java` | SCREENING-1 over real HTTP: {@code POST /screening/check}, {@code GET /screening/hits[/{id}]} and |
| `ScreeningAbsentSurfaceParityTest` | `com.gamma.control` | unit | `features/inspecto-screening/src/test/java/com/gamma/control/ScreeningAbsentSurfaceParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule} |
| `NameMatcherGoldenTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/NameMatcherGoldenTest.java` | Golden pin of the Screening name method's Jaro-Winkler and Match Score, written BEFORE the token metric moved to <!-- vocab-allow: quoted Javadoc --> |
| `NameMatcherTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/NameMatcherTest.java` | Golden pairs for the Screening name method (SCR-D3). Each group is judged against the default threshold |
| `ScreenerTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreenerTest.java` | {@link Screener} over a real Entity List fact log (SCR-D3 … SCR-D9, SCR-D14). |
| `ScreeningHitsTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreeningHitsTest.java` | The Screening Hit store and state machine (SCR-D10 … SCR-D12). |
| `ScreeningJobTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreeningJobTest.java` | The {@code screening.run} Job over a real Parquet Dataset (SCR-D7, SCR-D8, SCR-D11): it raises one open hit per |
| `ScreeningJobTypeManifestTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreeningJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. |
| `ScreeningJobTypeTckTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreeningJobTypeTckTest.java` | {@link ScreeningJobType} against the platform's JobTypeProvider TCK. |
| `ScreeningRevealTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreeningRevealTest.java` | SCREENING-1-D3: the raw subject is read again from the Dataset the Job screened; a list entry unmasks to its member. |
| `ScreeningRoutesContractTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreeningRoutesContractTest.java` | {@link ScreeningRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. |
| `ScreeningSignalsTest` | `com.gamma.screening` | unit | `features/inspecto-screening/src/test/java/com/gamma/screening/ScreeningSignalsTest.java` | Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. |
