---
type: ProductItem
title: Audit contract
item: module.audit-spi
description: Documentation hub for module.audit-spi — every document about it, by slot.
tags: [product-item, module]
---

# Audit contract (`module.audit-spi`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.audit-spi`](../../../../../product-map/module/audit-spi.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | n/a | a contract module carved out by MODULE-REORG-1; its requirement of record is decision D-MR1 in docs/okf/product/layer/platform/spec-module-reorg-decisions.md, and audit behaviour is planned in the event and access hubs |
| spec | n/a | the contract's invariants are stated per type in api.md and the design in architecture.md; the implementation lives in the event module hub |
| architecture | current | [Audit contract — architecture](architecture.md) |
| features | current | [Audit contract — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | n/a | a pure Java contract with no screens; the audit trail screens are documented in the event hub user guide |
| admin-guide | n/a | nothing to install or configure in this jar; event storage and retention settings belong to the event module |
| api | current | [Audit contract — Java API](api.md) |
| tests | current | [Audit contract — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).
