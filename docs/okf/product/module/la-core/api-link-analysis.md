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
| Investigations | `GET`/`POST /inv/investigations`, `PATCH`/`DELETE /inv/investigations/{id}` (rename and restate the purpose, merged per field and serialised per Investigation, 404 when deleted under the edit / delete); the list's items carry an additive `ownerName` (the owner's display name as recorded in the Investigation's details at create, fork or instantiate, masked as the title; an older Investigation without one names only the caller's own, from the signed-in Subject), `.../ops`, `.../expand/preview` (Grow preview: dry-run expand, appends nothing), `POST /inv/expand/preview` (an unsaved Investigation's dry run of its `seed` / `expand` steps; nothing stored), `/undo`, `/reorder`, `/replay`, `/log`, `/working-set`, `/members`, `/case` |
| Four-eyes | `GET /inv/pending`, `.../pending/{id}/approve`, `/deny` |
| Drafts | `.../drafts`, `/rebase`, `/promote`, `/discard`, `/conflicts` |
| Dossier, embeds | `.../dossier`, `/dossier/bundle`, `/verify`, `.../embeds` |
| Graph, index | `GET /inv/graph/algorithms`, `POST /inv/graph/runs`, `GET /inv/index`, `POST /inv/index/builds` |
| Entities | `/inv/entity-types`, `DELETE /inv/entity-types/{id}` (an unused Entity Type; `canAuthorWorkbench`; 409 names the Dataset columns, live Entity Lists or Investigations that still use it, or refuses the last type), `/inv/entity-identities`, `/inv/entities/{type}/{key}/profile` |
| geo-link | `.../measures`, `/data-changes`, `/alert-rules`, `/standing-detection` |

Request gate order: authenticate -> rate limit -> ABAC -> module enabled -> capability -> open gate (member, policy).
