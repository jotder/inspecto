---
type: FeatureDb
title: Link analysis and geo routes — feature database
item: module.la-api
description: Generated feature list for module.la-api — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Link analysis and geo routes — feature database (`module.la-api`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.la-api`](../../../../../product-map/module/la-api.toon).

98 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| entityPages | feature | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `entityPages` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| geoLink | feature | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `geoLink` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| geoMap | feature | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `geoMap` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| investigations | feature | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `investigations` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /inv/investigations/([^/]+)/case" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"DELETE /inv/investigations/([^/]+)/case"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "DELETE /inv/uploads/([^/]+)/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"DELETE /inv/uploads/([^/]+)/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/datasets/([^/]+)/files" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/datasets/([^/]+)/files"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/entities/([^/]+)/([^/]+)/profile" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/entities/([^/]+)/([^/]+)/profile"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/entity-identities" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/entity-identities"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/entity-identities/group" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/entity-identities/group"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/entity-types" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/entity-types"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/entity-types/([^/]+)/profile" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/entity-types/([^/]+)/profile"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/exclusion-lists" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/exclusion-lists"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/graph/algorithms" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/graph/algorithms"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/graph/runs" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/graph/runs"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/graph/runs/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/graph/runs/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/index" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/index"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/index/builds/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/index/builds/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/integration" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/integration"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigation-templates" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigation-templates"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigation-templates/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigation-templates/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/case" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/case"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/compare" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/compare"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/coverage" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/coverage"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/dossier" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/dossier"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/dossier/bundle" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/dossier/bundle"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/drafts" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/drafts"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/drafts/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/drafts/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/drafts/([^/]+)/conflicts" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/drafts/([^/]+)/conflicts"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/drafts/([^/]+)/log" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/drafts/([^/]+)/log"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/drafts/([^/]+)/replay" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/drafts/([^/]+)/replay"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/drafts/([^/]+)/working-set" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/drafts/([^/]+)/working-set"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/embeds" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/embeds"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/log" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/log"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/members" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/members"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/references" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/references"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/investigations/([^/]+)/working-set" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/investigations/([^/]+)/working-set"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/pending" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/pending"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/schema/relationships" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/schema/relationships"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/snapshots" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/snapshots"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/uploads" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/uploads"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/uploads/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/uploads/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /inv/value-measures" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /inv/value-measures"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "GET /public/investigations/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"GET /public/investigations/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /geo/projection" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /geo/projection"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /geo/routes" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /geo/routes"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/case-history" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/case-history"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/deep-links/resolve" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/deep-links/resolve"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/entities/([^/]+)/resolve" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/entities/([^/]+)/resolve"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/entities/([^/]+)/suggest" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/entities/([^/]+)/suggest"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/entity-identities" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/entity-identities"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/entity-identities/([^/]+)/retract" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/entity-identities/([^/]+)/retract"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/entity-identities/import" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/entity-identities/import"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/graph/runs" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/graph/runs"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/graph/runs/([^/]+)/cancel" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/graph/runs/([^/]+)/cancel"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/inbound/investigations" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/inbound/investigations"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/index/builds" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/index/builds"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/index/builds/([^/]+)/cancel" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/index/builds/([^/]+)/cancel"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigation-templates/([^/]+)/instantiate" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigation-templates/([^/]+)/instantiate"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/dossier/bundle/verify" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/dossier/bundle/verify"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/dossier/verify" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/dossier/verify"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/drafts" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/drafts"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/drafts/([^/]+)/discard" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/drafts/([^/]+)/discard"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/drafts/([^/]+)/ops" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/drafts/([^/]+)/ops"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/drafts/([^/]+)/promote" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/drafts/([^/]+)/promote"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/drafts/([^/]+)/rebase" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/drafts/([^/]+)/rebase"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/drafts/([^/]+)/undo" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/drafts/([^/]+)/undo"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/embeds" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/embeds"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/embeds/([^/]+)/revoke" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/embeds/([^/]+)/revoke"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/expand/preview" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/expand/preview"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/handoff" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/handoff"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/members" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/members"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/members/revoke" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/members/revoke"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/ops" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/ops"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/pending/([^/]+)/approve" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/pending/([^/]+)/approve"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/pending/([^/]+)/deny" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/pending/([^/]+)/deny"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/references" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/references"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/reorder" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/reorder"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/replay" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/replay"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/reveal" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/reveal"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/template" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/template"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/investigations/([^/]+)/undo" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/investigations/([^/]+)/undo"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/pattern/branching" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/pattern/branching"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/pattern/temporal" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/pattern/temporal"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/projection" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/projection"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/projection/multi" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/projection/multi"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/projection/neighbors" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/projection/neighbors"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/projection/neighbors/preview" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/projection/neighbors/preview"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/reference-rows" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/reference-rows"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/schema/overlap-profile" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/schema/overlap-profile"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/snapshots" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/snapshots"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/snapshots/attach" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/snapshots/attach"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/traversal/recursive-paths" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/traversal/recursive-paths"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "POST /inv/uploads/([^/]+)" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"POST /inv/uploads/([^/]+)"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /inv/integration" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"PUT /inv/integration"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
| "PUT /inv/investigations/([^/]+)/case" | route | la-api | basic;standard;advanced;preview | linkAnalysis | ga | `"PUT /inv/investigations/([^/]+)/case"` | `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon` |
