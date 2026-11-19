---
type: ProductItem
title: Link Analysis storage
item: module.la-storage
description: Documentation hub for module.la-storage — every document about it, by slot.
tags: [product-item, module]
---

# Link Analysis storage (`module.la-storage`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.la-storage`](../../../../../product-map/module/la-storage.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.la-storage - board row detail](plan-board-detail.md) · [Link analysis storage - plan of record](plan-la-storage.md) |
| spec | current | [Link analysis storage - spec](spec-la-storage.md) |
| architecture | current | [Link analysis storage - architecture](architecture-la-storage.md) |
| features | current | [Link analysis storage — features](features-la-storage.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | n/a | no screens of its own - the index tool and staleness badges are documented in the la-core user guide (la-core/user-guide-la-core.md) |
| admin-guide | current | [Link analysis storage - admin guide](admin-guide-la-storage.md) |
| api | n/a | no HTTP routes and no SPI - an internal library called only by la-api, whose index routes are in la-api/api-routes.md |
| tests | current | [Link Analysis edge index — 10^9 scale run plan (clause 3 of `LA-INDEX-SCALE-MEASURE-1`)](tests-index-scale-run.md) · [Link Analysis storage — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-storage` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-ENTITY-KEY-NEW-CASE-PAIRS-1` | P3 | defect | The SQL entity key and the Java key still differ for about 55 newer Unicode case pairs. | — |
| `LA-INDEX-SCALE-MEASURE-1` | P3 | chore | Measure the link index at 10^9 rows (clause 3) on a bigger machine. | operator |
