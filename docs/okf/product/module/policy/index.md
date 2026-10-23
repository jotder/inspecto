---
type: ProductItem
title: Attribute-based access policy
item: module.policy
description: Documentation hub for module.policy — every document about it, by slot.
tags: [product-item, module]
---

# Attribute-based access policy (`module.policy`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.policy`](../../../../../product-map/module/policy.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Attribute-based access policy — plan of record](plan.md) |
| spec | current | [Attribute-based access policy — implementation spec](spec.md) |
| architecture | current | [Attribute-based access policy — architecture](architecture.md) |
| features | current | [Attribute-based access policy — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Attribute-based access policy — user guide](user-guide.md) |
| admin-guide | current | [Attribute-based access policy — admin guide](admin-guide.md) |
| api | n/a | implements the AccessDecider and EditionFeatureProvider contracts owned by module.auth-spi and the core; the /access/policies routes are core routes documented in module.access api-access.md |
| tests | current | [Attribute-based access policy — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.policy` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `BI-MASKING-FREE-SQL-ORACLES-1` | P1 | defect | a caller without canRevealLinkEntities can still TEST a masked key's value through free SQL, with every output cell masked — found by the… | operator |
