---
type: FeatureDb
title: Exchange (publication and ingestion contracts) — feature database
item: module.exchange
description: Generated feature list for module.exchange — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Exchange (publication and ingestion contracts) — feature database (`module.exchange`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.exchange`](../../../../../product-map/module/exchange.toon).

14 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| exchange-snapshot-refresh | background task | exchange | standard;advanced;preview | multiEntity | ga | `exchange-snapshot-refresh` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| exchange | feature | exchange | standard;advanced;preview | multiEntity | ga | `exchange` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /exchange/datasets/([^/]+)/([^/]+)" | route | exchange | standard;advanced;preview | multiEntity | ga | `"GET /exchange/datasets/([^/]+)/([^/]+)"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /exchange/grants" | route | exchange | standard;advanced;preview | multiEntity | ga | `"GET /exchange/grants"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /exchange/offers" | route | exchange | standard;advanced;preview | multiEntity | ga | `"GET /exchange/offers"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /exchange/views/([^/]+)/([^/]+)" | route | exchange | standard;advanced;preview | multiEntity | ga | `"GET /exchange/views/([^/]+)/([^/]+)"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /exchange/widgets/([^/]+)/([^/]+)" | route | exchange | standard;advanced;preview | multiEntity | ga | `"GET /exchange/widgets/([^/]+)/([^/]+)"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /exchange/grants/([^/]+)/(approve\|deny\|revoke)" | route | exchange | standard;advanced;preview | multiEntity | ga | `"POST /exchange/grants/([^/]+)/(approve\|deny\|revoke)"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /exchange/grants/([^/]+)/expiry" | route | exchange | standard;advanced;preview | multiEntity | ga | `"POST /exchange/grants/([^/]+)/expiry"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /exchange/grants/([^/]+)/pin" | route | exchange | standard;advanced;preview | multiEntity | ga | `"POST /exchange/grants/([^/]+)/pin"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /exchange/offers" | route | exchange | standard;advanced;preview | multiEntity | ga | `"POST /exchange/offers"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /exchange/refresh" | route | exchange | standard;advanced;preview | multiEntity | ga | `"POST /exchange/refresh"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /exchange/requests" | route | exchange | standard;advanced;preview | multiEntity | ga | `"POST /exchange/requests"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /exchange/signal-offers" | route | exchange | standard;advanced;preview | multiEntity | ga | `"POST /exchange/signal-offers"` | `features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon` |
