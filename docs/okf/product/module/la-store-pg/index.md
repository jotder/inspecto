---
type: ProductItem
title: Link analysis PostgreSQL store
item: module.la-store-pg
description: Documentation hub for module.la-store-pg — every document about it, by slot.
tags: [product-item, module]
---

# Link analysis PostgreSQL store (`module.la-store-pg`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.la-store-pg`](../../../../../product-map/module/la-store-pg.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Link analysis PostgreSQL store - plan of record](plan.md) |
| spec | current | [Link analysis PostgreSQL store - spec](spec.md) |
| architecture | current | [Link analysis PostgreSQL store - architecture](architecture.md) |
| features | current | [Link analysis PostgreSQL store — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | n/a | no screens - a storage backend chosen by JVM properties, invisible to end users |
| admin-guide | current | [Link analysis PostgreSQL store - admin guide](admin-guide.md) |
| api | n/a | implements la-core's InvestigationStoreProvider SPI and adds no contract of its own; the SPI is specified in la-core/spec.md |
| tests | current | [Link analysis PostgreSQL store — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-store-pg` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-INVESTIGATION-STORE-DESIGN-1` | P3 | feature | ⏸ POSTPONED 2026-10-23 (operator: the next release ships without Postgres | operator |
| `LA-INVESTIGATION-DELETE-PG-1` | P3 | feature | delete an Investigation on the Postgres store. | operator |
