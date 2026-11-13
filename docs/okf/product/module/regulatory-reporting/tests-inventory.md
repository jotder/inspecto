---
type: TestsInventory
title: Regulatory Reporting — tests inventory
item: module.regulatory-reporting
description: Generated list of the test classes for module.regulatory-reporting, with how to run them.
tags: [tests, module]
---

# Regulatory Reporting — tests inventory (`module.regulatory-reporting`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-regulatory-reporting/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.regulatory-reporting`](../../../../../product-map/module/regulatory-reporting.toon).

9 test class(es): 6 unit, 3 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-regulatory-reporting
mvn -o test -pl features/inspecto-regulatory-reporting -Dtest=ControlApiRegulatoryReportRowsTest,ControlApiRegulatoryReportsTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiRegulatoryReportRowsTest` | `com.gamma.regreporting` | real-HTTP control-plane test | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ControlApiRegulatoryReportRowsTest.java` | A Report Template's {@code rows} source over real HTTP with an armed Authenticator: the rows of a Dataset behind the <!-- vocab-allow: quoted Javadoc --> |
| `ControlApiRegulatoryReportsTest` | `com.gamma.regreporting` | real-HTTP control-plane test | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ControlApiRegulatoryReportsTest.java` | Regulatory Reports over real HTTP ({@code REGULATORY-REPORTING-1}) WITH an armed Authenticator (with no Subject |
| `ControlApiRegulatoryTemplatesTest` | `com.gamma.regreporting` | real-HTTP control-plane test | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ControlApiRegulatoryTemplatesTest.java` | Report Template authoring over real HTTP ({@code REGULATORY-REPORTING-1}): {@code PUT / DELETE |
| `PartnerSettlementDisputePackTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/PartnerSettlementDisputePackTest.java` | {@code PACK-PARTNER-SETTLEMENT-1} slice S7, the template half: the shipped {@code settlement-dispute-pack} Report Templates |
| `RegulatoryReportRoutesContractTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/RegulatoryReportRoutesContractTest.java` | {@link RegulatoryReportRoutes} against the platform test kit's RouteModule TCK: every mutating route is capability-gated. |
| `RegulatoryReportRoutesManifestParityTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/RegulatoryReportRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule} |
| `RegulatoryReportsStateZoneTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/RegulatoryReportsStateZoneTest.java` | SE-4 step 1 for the Regulatory Report store: read from {@code state/regulatory-reports} first, then {@code config/}; |
| `ReportRendererTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ReportRendererTest.java` | {@link ReportRenderer}: the three formats, repeating groups, escaping, and every refusal (never truncation). |
| `ReportTemplateTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ReportTemplateTest.java` | {@link ReportTemplate}: the built-in loads, a bad template lists EVERY problem, and a Space template wins. |
