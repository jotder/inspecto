---
type: FeatureDb
title: Incidents — feature database
item: addon.incidents
description: Generated feature list for addon.incidents — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, addon]
---

# Incidents — feature database (`addon.incidents`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`addon.incidents`](../../../../../product-map/addon/incidents.toon).

56 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| sla-sweep | background task | ops | standard;advanced;preview | incidents;workflowSla | ga | `sla-sweep` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| escalation-rule | config kind | ops | standard;advanced;preview | incidents;workflowSla | ga | `escalation-rule` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| sla-policy | config kind | ops | standard;advanced;preview | incidents;workflowSla | ga | `sla-policy` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| workflow | config kind | ops | standard;advanced;preview | incidents;workflowSla | ga | `workflow` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| create-incident | consequence | ops | standard;advanced;preview | incidents;workflowSla | ga | `create-incident` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| ops | feature | ops | standard;advanced;preview | incidents;workflowSla | ga | `ops` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| RCA templates (`*_rca.toon`) | inventory | ops | standard;advanced;preview | incidents;workflowSla | ga | consumed by `GET /rca/templates`; **no example file/shape in the tree yet** | `docs/FEATURE_INVENTORY.md §1J` |
| objects.analytics | job type | ops | standard;advanced;preview | incidents;workflowSla | ga | `objects.analytics` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| incident_purge | maintenance task | ops | standard;advanced;preview | incidents;workflowSla | ga | `incident_purge` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /objects/([^/]+)/links" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"DELETE /objects/([^/]+)/links"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /tags/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"DELETE /tags/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /tags/assignments/([^/]+)/([^/]+)/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"DELETE /tags/assignments/([^/]+)/([^/]+)/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /tags/rules/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"DELETE /tags/rules/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /findings/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /findings/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /notes/([^/]+)/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /notes/([^/]+)/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /notes/([^/]+)/([^/]+)/attachments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /notes/([^/]+)/([^/]+)/attachments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /notes/([^/]+)/([^/]+)/comments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /notes/([^/]+)/([^/]+)/comments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects/([^/]+)/attachments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects/([^/]+)/attachments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects/([^/]+)/comments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects/([^/]+)/comments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects/([^/]+)/graph" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects/([^/]+)/graph"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects/([^/]+)/links" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects/([^/]+)/links"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects/analytics" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects/analytics"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /objects/by-entity" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /objects/by-entity"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /rca/templates" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /rca/templates"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /tags" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /tags"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /tags/([^/]+)/targets" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /tags/([^/]+)/targets"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /tags/assignments/([^/]+)/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /tags/assignments/([^/]+)/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /tags/rules" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /tags/rules"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /workflows/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"GET /workflows/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "PATCH /objects/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"PATCH /objects/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /notes/([^/]+)/([^/]+)/attachments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /notes/([^/]+)/([^/]+)/attachments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /notes/([^/]+)/([^/]+)/comments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /notes/([^/]+)/([^/]+)/comments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/ack" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/ack"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/assign" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/assign"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/attachments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/attachments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/comments" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/comments"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/links" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/links"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/rca" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/rca"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/resolve" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/resolve"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /objects/([^/]+)/transition" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /objects/([^/]+)/transition"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /tags" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /tags"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /tags/([^/]+)/rename" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /tags/([^/]+)/rename"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /tags/assignments/([^/]+)/([^/]+)" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /tags/assignments/([^/]+)/([^/]+)"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /tags/rules" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /tags/rules"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /tags/rules/([^/]+)/apply" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"POST /tags/rules/([^/]+)/apply"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /objects/([^/]+)/category" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"PUT /objects/([^/]+)/category"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /objects/([^/]+)/findings" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"PUT /objects/([^/]+)/findings"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /objects/([^/]+)/impact" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"PUT /objects/([^/]+)/impact"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /objects/([^/]+)/postmortem" | route | ops | standard;advanced;preview | incidents;workflowSla | ga | `"PUT /objects/([^/]+)/postmortem"` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| LINKS | store family | ops | standard;advanced;preview | incidents;workflowSla | ga | `LINKS` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| NOTES | store family | ops | standard;advanced;preview | incidents;workflowSla | ga | `NOTES` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| OBJECTS | store family | ops | standard;advanced;preview | incidents;workflowSla | ga | `OBJECTS` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
| TAGS | store family | ops | standard;advanced;preview | incidents;workflowSla | ga | `TAGS` | `features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon` |
