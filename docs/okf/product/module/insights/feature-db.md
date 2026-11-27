---
type: FeatureDb
title: Business Insights — feature database
item: module.insights
description: Generated feature list for module.insights — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Business Insights — feature database (`module.insights`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/okf/product/layer/platform/features-feature-inventory.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.insights`](../../../../../product-map/module/insights.toon).

8 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| insight-profile | config kind | insights | basic;standard;advanced;preview | businessInsights | ga | `insight-profile` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| insights | feature | insights | basic;standard;advanced;preview | businessInsights | ga | `insights` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| insight.scan | job type | insights | basic;standard;advanced;preview | businessInsights | ga | `insight.scan` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /insights" | route | insights | basic;standard;advanced;preview | businessInsights | ga | `"GET /insights"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /insights/brief" | route | insights | basic;standard;advanced;preview | businessInsights | ga | `"GET /insights/brief"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /insights/([^/]+)/([^/]+)/feedback" | route | insights | basic;standard;advanced;preview | businessInsights | ga | `"POST /insights/([^/]+)/([^/]+)/feedback"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /insights/explain" | route | insights | basic;standard;advanced;preview | businessInsights | ga | `"POST /insights/explain"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /insights/preview" | route | insights | basic;standard;advanced;preview | businessInsights | ga | `"POST /insights/preview"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
