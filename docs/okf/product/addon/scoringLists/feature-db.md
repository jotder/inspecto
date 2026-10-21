---
type: FeatureDb
title: Scoring & Lists — feature database
item: addon.scoringLists
description: Generated feature list for addon.scoringLists — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, addon]
---

# Scoring & Lists — feature database (`addon.scoringLists`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`addon.scoringLists`](../../../../../product-map/addon/scoringLists.toon).

14 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| entityList | feature | entity-list | basic;standard;advanced;preview | scoringLists | ga | `entityList` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /entity-lists" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"GET /entity-lists"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /entity-lists/([^/]+)" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"GET /entity-lists/([^/]+)"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /entity-lists" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"POST /entity-lists"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /entity-lists/([^/]+)/match" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"POST /entity-lists/([^/]+)/match"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /entity-lists/([^/]+)/members" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"POST /entity-lists/([^/]+)/members"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /entity-lists/([^/]+)/register-dataset" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"POST /entity-lists/([^/]+)/register-dataset"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /entity-lists/([^/]+)/retire" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"POST /entity-lists/([^/]+)/retire"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /entity-lists/([^/]+)/attributes" | route | entity-list | basic;standard;advanced;preview | scoringLists | ga | `"PUT /entity-lists/([^/]+)/attributes"` | `features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon` |
| risk-score | config kind | scoring | standard;advanced;preview | scoringLists | ga | `risk-score` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| scoring | feature | scoring | standard;advanced;preview | scoringLists | ga | `scoring` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| risk.score | job type | scoring | standard;advanced;preview | scoringLists | ga | `risk.score` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /risk-scores/([^/]+)/([^/]+)" | route | scoring | standard;advanced;preview | scoringLists | ga | `"GET /risk-scores/([^/]+)/([^/]+)"` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /risk-scores/preview" | route | scoring | standard;advanced;preview | scoringLists | ga | `"POST /risk-scores/preview"` | `features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon` |
