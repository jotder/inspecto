---
type: FeatureDb
title: Action Requests — feature database
item: addon.actionRequests
description: Generated feature list for addon.actionRequests — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, addon]
---

# Action Requests — feature database (`addon.actionRequests`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`addon.actionRequests`](../../../../../product-map/addon/actionRequests.toon).

9 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| invoke-api | consequence | action-requests | standard;advanced;preview | actionRequests | ga | `invoke-api` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| actionRequests | feature | action-requests | standard;advanced;preview | actionRequests | ga | `actionRequests` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /action-requests" | route | action-requests | standard;advanced;preview | actionRequests | ga | `"GET /action-requests"` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /action-requests/([^/]+)" | route | action-requests | standard;advanced;preview | actionRequests | ga | `"GET /action-requests/([^/]+)"` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /action-requests" | route | action-requests | standard;advanced;preview | actionRequests | ga | `"POST /action-requests"` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /action-requests/([^/]+)/approve" | route | action-requests | standard;advanced;preview | actionRequests | ga | `"POST /action-requests/([^/]+)/approve"` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /action-requests/([^/]+)/decline" | route | action-requests | standard;advanced;preview | actionRequests | ga | `"POST /action-requests/([^/]+)/decline"` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /action-requests/([^/]+)/mark-failed" | route | action-requests | standard;advanced;preview | actionRequests | ga | `"POST /action-requests/([^/]+)/mark-failed"` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /action-requests/([^/]+)/retry" | route | action-requests | standard;advanced;preview | actionRequests | ga | `"POST /action-requests/([^/]+)/retry"` | `features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon` |
