---
type: ProductItem
title: Access policy (roles, grants, row scope, audit trail)
item: module.access
description: Documentation hub for module.access — every document about it, by slot.
tags: [product-item, module]
---

# Access policy (roles, grants, row scope, audit trail) (`module.access`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.access`](../../../../../product-map/module/access.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Security (SEC)](plan-security.md) |
| spec | current | [Access policy — spec](spec.md) |
| architecture | current | [Identity and Access](architecture-overview.md) |
| features | current | [Identity and Access — features](features-access.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Identity and Access — screens](user-guide-access.md) |
| admin-guide | current | [Auth & Security](admin-guide-auth-security.md) |
| api | current | [Identity and Access — API](api-access.md) |
| tests | current | [Access policy (roles, grants, row scope, audit trail) — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.access` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `BI-MASKING-ROW-VALUE-LEAKS-1` | P1 | defect | free-SQL masking (BiResultMasking) still lets a masked key out through shapes that existed before BI-MASKING-SQL-SHAPE-LEAKS-1 and are un… | — |
| `IMPORT-WHOLE-SPACE-SPLIT-1` | P3 | feature | split a whole-Space export into its kinds' own validated routes on import, if ever asked. | demand |
