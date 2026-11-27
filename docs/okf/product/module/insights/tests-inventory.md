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

14 test class(es): 10 unit, 4 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-insights
mvn -o test -pl features/inspecto-insights -Dtest=BusinessAssurancePilotInsightsTest,ControlApiExplainDepth2Test
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `BusinessAssurancePilotInsightsTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/BusinessAssurancePilotInsightsTest.java` | INSIGHT-PILOT-STALE-CORPUS-1: the {@code business-assurance} pilot (D-BI6) finds Insights out of the box. Its |
| `ControlApiExplainDepth2Test` | `com.gamma.insights` | real-HTTP control-plane test | `features/inspecto-insights/src/test/java/com/gamma/insights/ControlApiExplainDepth2Test.java` | {@code POST /insights/explain} with {@code depth: 2} over real HTTP under an ARMED authenticator |
| `ControlApiExplainTest` | `com.gamma.insights` | real-HTTP control-plane test | `features/inspecto-insights/src/test/java/com/gamma/insights/ControlApiExplainTest.java` | {@code POST /insights/explain} over real HTTP and DuckDB under an ARMED authenticator (BI-INSIGHTS-1 P2): the |
| `ControlApiInsightBriefTest` | `com.gamma.insights` | real-HTTP control-plane test | `features/inspecto-insights/src/test/java/com/gamma/insights/ControlApiInsightBriefTest.java` | {@code GET /insights/brief} over real HTTP under an ARMED authenticator (BI-INSIGHTS-1 P4 BR-0): 401, the Profile the |
| `ControlApiInsightsTest` | `com.gamma.insights` | real-HTTP control-plane test | `features/inspecto-insights/src/test/java/com/gamma/insights/ControlApiInsightsTest.java` | The Insight feed, feedback and replay preview over real HTTP under an ARMED authenticator (BI-INSIGHTS-1 P3): the |
| `InsightBriefTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightBriefTest.java` | Golden selection of the Dashboard Brief (BI-INSIGHTS-1 P4 BR-0, plan §4.1): the rules, over hand-made stored rows. |
| `InsightProfileValidatorTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightProfileValidatorTest.java` | The {@code insight-profile} save gates and the reserved {@code insights_} store prefix. |
| `InsightsAbsentSurfaceParityTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightsAbsentSurfaceParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@link ExplainRoutes} and {@link InsightsRoutes} register — |
| `InsightScanDimensionTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanDimensionTest.java` | The I-9 concentration and I-10 distribution-shift Insight Types of the scan, over the same fake fetcher as |
| `InsightScanJobTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanJobTest.java` | {@code insight.scan} (BI-INSIGHTS-1 I-8) run by a real JobService over Parquet in DuckDB: registered through |
| `InsightScanJobTypeManifestTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. |
| `InsightScanJobTypeTckTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanJobTypeTckTest.java` | {@link InsightScanJobType} against the platform's JobTypeProvider TCK. |
| `InsightScanTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/InsightScanTest.java` | The I-8 scan over a fake {@link InsightScan.Fetcher} (no DuckDB): what it finds, what false-discovery control keeps |
| `PairExplainGoldenTest` | `com.gamma.insights` | unit | `features/inspecto-insights/src/test/java/com/gamma/insights/PairExplainGoldenTest.java` | Depth 2 (BI-EXPLAIN-DEPTH2-1) contributions by pairs of dimensions, computed by hand. |
