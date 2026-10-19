---
type: FeatureDb
title: Case Management — feature database
item: module.case-management
description: Generated feature list for module.case-management — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Case Management — feature database (`module.case-management`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.case-management`](../../../../../product-map/module/case-management.toon).

9 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| cases | feature | case-management | standard;advanced;preview | caseManagement | ga | `cases` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| caserule.evaluate | job type | case-management | standard;advanced;preview | caseManagement | ga | `caserule.evaluate` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /cases/rules/([^/]+)" | route | case-management | standard;advanced;preview | caseManagement | ga | `"DELETE /cases/rules/([^/]+)"` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /cases/rules" | route | case-management | standard;advanced;preview | caseManagement | ga | `"GET /cases/rules"` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /cases/from-entities" | route | case-management | standard;advanced;preview | caseManagement | ga | `"POST /cases/from-entities"` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /cases/rules" | route | case-management | standard;advanced;preview | caseManagement | ga | `"POST /cases/rules"` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /cases/rules/([^/]+)/evaluate" | route | case-management | standard;advanced;preview | caseManagement | ga | `"POST /cases/rules/([^/]+)/evaluate"` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/merge" | route | case-management | standard;advanced;preview | caseManagement | ga | `"POST /objects/([^/]+)/merge"` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/split" | route | case-management | standard;advanced;preview | caseManagement | ga | `"POST /objects/([^/]+)/split"` | `features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon` |
