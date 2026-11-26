---
type: TestsInventory
title: Business Insights — tests inventory
item: module.insights
description: Generated list of the test classes for module.insights, with how to run them.
tags: [tests, module]
---

# Business Insights — tests inventory (`module.insights`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-insights/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.insights`](../../../../../product-map/module/insights.toon).

8 test class(es): 6 unit, 2 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-insights
mvn -o test -pl features/inspecto-insights -Dtest=ControlApiExplainTest,ControlApiInsightsTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiExplainTest` | `com.gamma.insights` | real-HTTP control-plane test | `features/inspecto-insights/src/test/java/com/gamma/insights/ControlApiExplainTest.java` | {@code POST /insights/explain} over real HTTP and DuckDB under an ARMED authenticator (BI-INSIGHTS-1 P2): the |
| `ControlApiInsightsTest` | `com.gamma.insights` | real-HTTP control-plane test | `features/inspecto-insights/src/test/java/com/gamma/insights/ControlApiInsightsTest.java` | The Insight feed, feedback and replay preview over real HTTP under an ARMED authenticator (BI-INSIGHTS-1 P3): the |
| `InsightProfileValidatorTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightProfileValidatorTest.java` | The {@code insight-profile} save gates and the reserved {@code insights_} store prefix. |
| `InsightsAbsentSurfaceParityTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightsAbsentSurfaceParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@link ExplainRoutes} and {@link InsightsRoutes} register — |
| `InsightScanJobTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanJobTest.java` | {@code insight.scan} (BI-INSIGHTS-1 I-8) run by a real JobService over Parquet in DuckDB: registered through |
| `InsightScanJobTypeManifestTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. |
| `InsightScanJobTypeTckTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanJobTypeTckTest.java` | {@link InsightScanJobType} against the platform's JobTypeProvider TCK. |
| `InsightScanTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanTest.java` | The I-8 scan over a fake {@link InsightScan.Fetcher} (no DuckDB): what it finds, what false-discovery control keeps |
