---
type: FeatureDb
title: Business Insights — feature database
item: addon.businessInsights
description: Generated feature list for addon.businessInsights — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, addon]
---

# Business Insights — feature database (`addon.businessInsights`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/okf/product/layer/platform/features-feature-inventory.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`addon.businessInsights`](../../../../../product-map/addon/businessInsights.toon).

7 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| insight-profile | config kind | insights | standard;advanced;preview | businessInsights | ga | `insight-profile` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| insights | feature | insights | standard;advanced;preview | businessInsights | ga | `insights` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| insight.scan | job type | insights | standard;advanced;preview | businessInsights | ga | `insight.scan` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /insights" | route | insights | standard;advanced;preview | businessInsights | ga | `"GET /insights"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /insights/([^/]+)/([^/]+)/feedback" | route | insights | standard;advanced;preview | businessInsights | ga | `"POST /insights/([^/]+)/([^/]+)/feedback"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /insights/explain" | route | insights | standard;advanced;preview | businessInsights | ga | `"POST /insights/explain"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /insights/preview" | route | insights | standard;advanced;preview | businessInsights | ga | `"POST /insights/preview"` | `features/inspecto-insights/src/main/resources/META-INF/inspecto/module.toon` |
