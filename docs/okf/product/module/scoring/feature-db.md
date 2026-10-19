---
type: FeatureDb
title: Scoring — feature database
item: module.scoring
description: Generated feature list for module.scoring — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Scoring — feature database (`module.scoring`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.scoring`](../../../../../product-map/module/scoring.toon).

5 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| risk-score | config kind | scoring | standard;advanced;preview | scoringLists | ga | `risk-score` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| scoring | feature | scoring | standard;advanced;preview | scoringLists | ga | `scoring` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| risk.score | job type | scoring | standard;advanced;preview | scoringLists | ga | `risk.score` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /risk-scores/([^/]+)/([^/]+)" | route | scoring | standard;advanced;preview | scoringLists | ga | `"GET /risk-scores/([^/]+)/([^/]+)"` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /risk-scores/preview" | route | scoring | standard;advanced;preview | scoringLists | ga | `"POST /risk-scores/preview"` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
