---
type: FeatureDb
title: Regulatory Reporting — feature database
item: module.regulatory-reporting
description: Generated feature list for module.regulatory-reporting — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Regulatory Reporting — feature database (`module.regulatory-reporting`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.regulatory-reporting`](../../../../../product-map/module/regulatory-reporting.toon).

12 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| regulatoryReporting | feature | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `regulatoryReporting` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /regulatory-reports/templates/([^/]+)" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"DELETE /regulatory-reports/templates/([^/]+)"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /regulatory-reports" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"GET /regulatory-reports"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /regulatory-reports/([^/]+)" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"GET /regulatory-reports/([^/]+)"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /regulatory-reports/([^/]+)/verify" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"GET /regulatory-reports/([^/]+)/verify"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /regulatory-reports/templates" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"GET /regulatory-reports/templates"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /regulatory-reports" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"POST /regulatory-reports"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /regulatory-reports/([^/]+)/approve" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"POST /regulatory-reports/([^/]+)/approve"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /regulatory-reports/([^/]+)/decline" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"POST /regulatory-reports/([^/]+)/decline"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /regulatory-reports/([^/]+)/request-approval" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"POST /regulatory-reports/([^/]+)/request-approval"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /regulatory-reports/([^/]+)/retry" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"POST /regulatory-reports/([^/]+)/retry"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /regulatory-reports/templates/([^/]+)" | route | regulatory-reporting | standard;advanced;preview | regulatoryReporting | ga | `"PUT /regulatory-reports/templates/([^/]+)"` | `features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon` |
