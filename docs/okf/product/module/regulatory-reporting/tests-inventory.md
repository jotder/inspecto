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

6 test class(es): 4 unit, 2 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-regulatory-reporting
mvn -o test -pl features/inspecto-regulatory-reporting -Dtest=ControlApiRegulatoryReportsTest,ControlApiRegulatoryTemplatesTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiRegulatoryReportsTest` | `com.gamma.regreporting` | real-HTTP control-plane test | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ControlApiRegulatoryReportsTest.java` | Regulatory Reports over real HTTP ({@code REGULATORY-REPORTING-1}) WITH an armed Authenticator (with no Subject |
| `ControlApiRegulatoryTemplatesTest` | `com.gamma.regreporting` | real-HTTP control-plane test | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ControlApiRegulatoryTemplatesTest.java` | Report Template authoring over real HTTP ({@code REGULATORY-REPORTING-1}): {@code PUT / DELETE |
| `RegulatoryReportRoutesContractTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/RegulatoryReportRoutesContractTest.java` | {@link RegulatoryReportRoutes} against the platform test kit's RouteModule TCK: every mutating route is capability-gated. |
| `RegulatoryReportRoutesManifestParityTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/RegulatoryReportRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule} |
| `ReportRendererTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ReportRendererTest.java` | {@link ReportRenderer}: the three formats, repeating groups, escaping, and every refusal (never truncation). |
| `ReportTemplateTest` | `com.gamma.regreporting` | unit | `features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ReportTemplateTest.java` | {@link ReportTemplate}: the built-in loads, a bad template lists EVERY problem, and a Space template wins. |
