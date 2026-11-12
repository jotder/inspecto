---
type: ProductItem
title: Link analysis and geo routes
item: module.la-api
description: Documentation hub for module.la-api — every document about it, by slot.
tags: [product-item, module]
---

# Link analysis and geo routes (`module.la-api`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.la-api`](../../../../../product-map/module/la-api.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Link analysis and geo routes - plan of record](plan.md) |
| spec | current | [Link analysis and geo routes - spec](spec.md) |
| architecture | current | [Link analysis and geo routes - architecture](architecture.md) |
| features | current | [Link analysis and geo routes — features](features.md) |
| feature-db | current | [Link analysis and geo routes — feature database](feature-db.md) |
| user-guide | n/a | no screens of its own - the Link Analysis screens these routes serve are documented in the la-core hub (la-core/user-guide-link-analysis.md) |
| admin-guide | current | [Link analysis and geo routes - admin guide](admin-guide.md) |
| api | current | [HTTP routes — module.la-api](api-routes.md) |
| tests | current | [Link analysis and geo routes — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-api` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-PERF-N7-1` | P3 | chore | A fixed ~80 ms per Link Analysis request (N7) and a 304 that costs as much as a 200 (N8). | — |
| `LA-PERF-N9-1` | P3 | defect | Projection latency ranges 146 ms to 20.6 s, unexplained (N9). | — |
| `LA-PERF-N10-1` | P3 | chore | About 3,550 native threads and 1.4 GB at idle (N10). | — |
| `LA-PERF-N11-1` | P3 | chore | Boot takes 3 min 35 s, 185 s before the first log line (N11). | — |
| `LA-PERF-P10A5-1` | P3 | feature | Expand and Grow fetch only the delta (P10a-5). | — |
