---
type: ProductItem
title: Config model and codecs
item: module.config
description: Documentation hub for module.config — every document about it, by slot.
tags: [product-item, module]
---

# Config model and codecs (`module.config`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.config`](../../../../../product-map/module/config.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Component metamodel & Catalog (MET)](plan-metamodel.md) · [Spaces & tenancy (SPC)](plan-spaces.md) |
| spec | current | [Config Safety Validator](spec-config-safety.md) · [Pipeline config keys — the single block census](spec-pipeline-config-keys.md) · [TOON Configuration](spec-toon-config.md) |
| architecture | current | [Spaces](architecture-spaces.md) |
| features | current | [Spaces — features](features-spaces.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Config](user-guide-config.md) · [Grammar configuration (the one surface)](user-guide-grammar-config.md) · [Spaces — screens](user-guide-spaces.md) |
| admin-guide | current | [Configuration reference](admin-guide-configuration-reference.md) |
| api | current | [Spaces — API](api-spaces.md) |
| tests | current | [Config model and codecs — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.config` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | P2 | feature | NARROWED 2026-10-18: the containment rule is BUILT; what is left is making the ingest lane write to it. | operator |
