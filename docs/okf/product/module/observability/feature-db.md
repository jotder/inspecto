---
type: FeatureDb
title: Observability (metrics endpoint + events feed API) — feature database
item: module.observability
description: Generated feature list for module.observability — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Observability (metrics endpoint + events feed API) — feature database (`module.observability`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.observability`](../../../../../product-map/module/observability.toon).

9 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| events | feature | observability | standard;advanced;preview |  | ga | `events` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /events" | route | observability | standard;advanced;preview |  | ga | `"GET /events"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /events/([^/]+)" | route | observability | standard;advanced;preview |  | ga | `"GET /events/([^/]+)"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /events/export" | route | observability | standard;advanced;preview |  | ga | `"GET /events/export"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /events/search" | route | observability | standard;advanced;preview |  | ga | `"GET /events/search"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /events/views" | route | observability | standard;advanced;preview |  | ga | `"GET /events/views"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /metrics" | route | observability | standard;advanced;preview |  | ga | `"GET /metrics"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /events/views" | route | observability | standard;advanced;preview |  | ga | `"POST /events/views"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /events/views/([^/]+)/delete" | route | observability | standard;advanced;preview |  | ga | `"POST /events/views/([^/]+)/delete"` | `features/inspecto-observability/src/main/resources/META-INF/inspecto/module.toon` |
