---
type: FeatureDb
title: Anomaly Detection — feature database
item: addon.anomalyDetection
description: Generated feature list for addon.anomalyDetection — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, addon]
---

# Anomaly Detection — feature database (`addon.anomalyDetection`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`addon.anomalyDetection`](../../../../../product-map/addon/anomalyDetection.toon).

5 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| anomaly-model | config kind | anomaly | standard;advanced;preview | anomalyDetection | ga | `anomaly-model` | `features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon` |
| anomaly | feature | anomaly | standard;advanced;preview | anomalyDetection | ga | `anomaly` | `features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon` |
| anomaly.score | job type | anomaly | standard;advanced;preview | anomalyDetection | ga | `anomaly.score` | `features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /anomaly-scores/([^/]+)/([^/]+)" | route | anomaly | standard;advanced;preview | anomalyDetection | ga | `"GET /anomaly-scores/([^/]+)/([^/]+)"` | `features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /anomaly-scores/preview" | route | anomaly | standard;advanced;preview | anomalyDetection | ga | `"POST /anomaly-scores/preview"` | `features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon` |
