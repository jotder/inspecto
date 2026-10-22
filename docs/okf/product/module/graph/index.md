---
type: ProductItem
title: Graph algorithms
item: module.graph
description: Documentation hub for module.graph — every document about it, by slot.
tags: [product-item, module]
---

# Graph algorithms (`module.graph`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.graph`](../../../../../product-map/module/graph.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Graph algorithms — plan](plan.md) |
| spec | current | [Graph algorithms — spec](spec.md) |
| architecture | current | [Graph library](architecture-overview.md) |
| features | current | [Graph library — features](features-graph.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Graph library — screens](user-guide-graph.md) |
| admin-guide | n/a | a pure algorithm library with no config keys, -D flags, files or services of its own; every limit is set by the caller (Link Analysis limits, see the la-core hub) |
| api | current | [Graph library — API](api-graph.md) |
| tests | current | [Graph algorithms — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.graph` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `ANALYTICS-CORE-1` | P2 | feature | one home for data operations and statistics; minimise duplicate data-operations code across modules. | — |
| `DUCKDB-2-MIGRATION-1` | P2 | chore | move the DuckDB pin to 2.0.x (operator decision 2026-10-05: go 2.0.x). | external |
