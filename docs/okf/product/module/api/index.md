---
type: ProductItem
title: Public API annotations
item: module.api
description: Documentation hub for module.api — every document about it, by slot.
tags: [product-item, module]
---

# Public API annotations (`module.api`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.api`](../../../../../product-map/module/api.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [API stability policy](plan-api-stability.md) |
| spec | current | [Public API annotations — spec](spec.md) |
| architecture | current | [Public API annotations — architecture](architecture.md) |
| features | current | [Public API annotations — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | n/a | a single compile-time annotation with no screens and no user configuration |
| admin-guide | n/a | nothing to install, configure or operate; no flags, files or runtime state |
| api | n/a | no HTTP routes; the module is one Java annotation |
| tests | n/a | the module has no behaviour to test and no test sources |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.api` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-ONBOARD-FIXES-1` | P2 | defect | defects found in the 2026-10-22 blank-slate walkthrough (use cases §7): suggested schema selectors are names that registration refuses (F… | — |
