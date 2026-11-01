---
type: FeatureDb
title: Predictive Analytics — feature database
item: addon.predictiveAnalytics
description: Generated feature list for addon.predictiveAnalytics — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, addon]
---

# Predictive Analytics — feature database (`addon.predictiveAnalytics`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`addon.predictiveAnalytics`](../../../../../product-map/addon/predictiveAnalytics.toon).

8 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| churn-model | config kind | predictive | standard;advanced;preview | predictiveAnalytics | ga | `churn-model` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
| forecast-model | config kind | predictive | standard;advanced;preview | predictiveAnalytics | ga | `forecast-model` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
| segmentation-model | config kind | predictive | standard;advanced;preview | predictiveAnalytics | ga | `segmentation-model` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
| predictive | feature | predictive | standard;advanced;preview | predictiveAnalytics | ga | `predictive` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
| predict.churn | job type | predictive | standard;advanced;preview | predictiveAnalytics | ga | `predict.churn` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
| predict.forecast | job type | predictive | standard;advanced;preview | predictiveAnalytics | ga | `predict.forecast` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
| predict.segment | job type | predictive | standard;advanced;preview | predictiveAnalytics | ga | `predict.segment` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /predictions/([^/]+)/([^/]+)/([^/]+)" | route | predictive | standard;advanced;preview | predictiveAnalytics | ga | `"GET /predictions/([^/]+)/([^/]+)/([^/]+)"` | `features/inspecto-predictive/src/main/resources/META-INF/inspecto/module.toon` |
