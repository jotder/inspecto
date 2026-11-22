---
type: ProductItem
title: Acquisition and collectors
item: module.acquire
description: Documentation hub for module.acquire — every document about it, by slot.
tags: [product-item, module]
---

# Acquisition and collectors (`module.acquire`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.acquire`](../../../../../product-map/module/acquire.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.acquire - board row detail](plan-board-detail.md) |
| spec | n/a | documented in the ELT hub module/elt spec-acquisition.md, merged 2026-11-18 |
| architecture | n/a | documented in the ELT hub module/elt architecture-elt.md (Collectors part), merged 2026-11-18 |
| features | n/a | documented in the ELT hub module/elt features-elt.md (Collectors), merged 2026-11-18 |
| feature-db | current | [Acquisition and collectors — feature database](feature-db.md) |
| user-guide | n/a | documented in the ELT hub module/elt user-guide-elt.md section 21, merged 2026-11-18 |
| admin-guide | n/a | documented in the ELT hub module/elt admin-guide-elt.md, merged 2026-11-18 |
| api | n/a | documented in the ELT hub module/elt api-elt.md (Collectors and Connections), merged 2026-11-18 |
| tests | current | [Acquisition and collectors — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.acquire` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `STORE-FAMILY-PARITY-GOLDEN-STALE-1` | P3 | defect | StoreFamilyParityTest.everyFamilyResolvesExactlyAsItDidBeforeTheRosterWasOpened (inspecto-ops) fails on master, alone or in its module: i… | — |
| — | P3 | feature | Unpack (11) absent codecs | demand |
| `SCALEOUT-U1-REMOTE-ACQUIRE-1` | P3 | feature | Remote acquisition (U1) is not distributed: two pods would both fetch the same remote file. | operator |
