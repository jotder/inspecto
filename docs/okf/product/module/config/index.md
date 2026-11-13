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
| plan | current | [Component metamodel & Catalog (MET)](plan-metamodel.md) · [Space evolution — a Space the server understands as a whole, reached without breaking one](plan-space-evolution.md) · [Spaces & tenancy (SPC)](plan-spaces.md) |
| spec | current | [Column contract — Schema column changes and what they break (SE-3a, SE-3b)](spec-column-contract.md) · [Config Safety Validator](spec-config-safety.md) · [Pipeline config keys — the single block census](spec-pipeline-config-keys.md) · [Space lock — the server's signed record of a Space (SE-1a)](spec-space-lock.md) · [Space origin — which template made a Space (SE-5a)](spec-space-origin.md) · [Space promotion — a whole Space applied to an existing Space as a previewed change set (SE-6b)](spec-space-promotion.md) · [Space tests — cases that travel with a Space (SE-6a)](spec-space-tests.md) · [TOON Configuration](spec-toon-config.md) |
| architecture | current | [Space anatomy — every folder and file in a Space](architecture-space-anatomy.md) · [Spaces](architecture.md) |
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
| — | P2 | feature | SPACE-LINK-CENSUS-1 one declared census of the links between a Space's config files | — |
| — | P3 | feature | SPACE-EVOLUTION-1 the release-gated rest of the Space evolution | operator |
| `SPACE-UPGRADE-STUCK-VERSION-1` | P3 | defect | a Space Template upgrade whose last remaining files are conflicts never records the new version | — |
| `SPACE-PROMOTION-PARAM-TYPES-1` | P3 | defect | a promotion parameter value can change the type of the value it fills | — |
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `SPACE-ROOTED-DATA-ROOT-MIGRATION-1` | P3 | feature | an install whose data_root is rooted (or absent, i.e. -Dspaces.data.root) is still not relocatable, and there is no route that migrates it. | demand |
| `WRITER-SCAN-BLIND-RENAME-1` | P2 | defect | DecisionRuleWritersTest fails on origin/master, so every lane's gate is red. | — |
| `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | P2 | feature | NARROWED 2026-11-01 (twice): the ingest lane WRITES an allow-listed s3:// dirs.database and REGISTERS that output in DuckLake; what is le… | — |
