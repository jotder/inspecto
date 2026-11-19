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
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Component metamodel & Catalog (MET)](plan-metamodel.md) · [Space evolution — a Space the server understands as a whole, reached without breaking one](plan-space-evolution.md) · [Spaces & tenancy (SPC)](plan-spaces.md) |
| spec | current | [Column contract — Schema column changes and what they break (SE-3a, SE-3b)](spec-column-contract.md) · [Config Safety Validator](spec-config-safety.md) · [Pipeline config keys — the single block census](spec-pipeline-config-keys.md) · [Space lock — the server's signed record of a Space (SE-1a)](spec-space-lock.md) · [Space origin — which template made a Space (SE-5a)](spec-space-origin.md) · [Space promotion — a whole Space applied to an existing Space as a previewed change set (SE-6b)](spec-space-promotion.md) · [Space tests — cases that travel with a Space (SE-6a)](spec-space-tests.md) · [TOON Configuration](spec-toon-config.md) |
| architecture | current | [Spaces](architecture-config.md) · [Space anatomy — every folder and file in a Space](architecture-space-anatomy.md) |
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
| — | P2 | feature | SPACE-LINK-CENSUS-1 the link census: the default flip and the plan scope not built | — |
| — | P3 | defect | COMPONENT-DELETE-IN-USE-WORDS-1 the Components screen words every refused delete as "referenced by a pipeline" | — |
| — | P3 | feature | SPACE-EVOLUTION-1 the release-gated rest of the Space evolution | operator |
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `SPACE-TO-APP-CONSOLE-1` | P3 | feature | turn a working Space into a Space Template, and into an App, from the console. | — |
| `SPACE-ROOTED-DATA-ROOT-MIGRATION-1` | P3 | feature | an install whose data_root is rooted (or absent, i.e. -Dspaces.data.root) is still not relocatable, and there is no route that migrates it. | demand |
| `SPACE-ADMISSION-FOLLOWUPS-1` | P3 | chore | what the closed-Space change (D5, 91f0af69a) left on purpose. | — |
| `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | P2 | feature | ⏸ POSTPONED 2026-11-16 (operator, MoSCoW S7): waits until a multi-Pod deployment is scheduled, together with the postponed scale-out rows… | operator |
