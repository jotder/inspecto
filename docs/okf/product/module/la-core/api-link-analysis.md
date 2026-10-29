---
type: Reference
title: "Link Analysis — API"
description: "Control-plane routes for Link Analysis, with their gates."
tags: [la-core, link-analysis, api]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis — API

Routes for Link Analysis. Design and internals: [Link Analysis architecture](architecture-overview.md).

Grouped; the authoritative list is each module's `META-INF/inspecto/module.toon`.

| Group | Routes |
|---|---|
| Exploration | `POST /inv/projection`, `/inv/projection/neighbors`, `/inv/projection/neighbors/preview` (Grow preview, read-only, Basic too), `/inv/traversal/recursive-paths`, `/inv/pattern/*`, `POST /geo/projection`, `/geo/routes` |
| Investigations | `GET`/`POST /inv/investigations`, `PATCH`/`DELETE /inv/investigations/{id}` (rename and restate the purpose / delete), `.../ops`, `.../expand/preview` (Grow preview: dry-run expand, appends nothing), `/undo`, `/reorder`, `/replay`, `/log`, `/working-set`, `/members`, `/case` |
| Four-eyes | `GET /inv/pending`, `.../pending/{id}/approve`, `/deny` |
| Drafts | `.../drafts`, `/rebase`, `/promote`, `/discard`, `/conflicts` |
| Dossier, embeds | `.../dossier`, `/dossier/bundle`, `/verify`, `.../embeds` |
| Graph, index | `GET /inv/graph/algorithms`, `POST /inv/graph/runs`, `GET /inv/index`, `POST /inv/index/builds` |
| Entities | `/inv/entity-types`, `/inv/entity-identities`, `/inv/entities/{type}/{key}/profile` |
| geo-link | `.../measures`, `/data-changes`, `/alert-rules`, `/standing-detection` |

Request gate order: authenticate -> rate limit -> ABAC -> module enabled -> capability -> open gate (member, policy).
