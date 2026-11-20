---
type: ProductItem
title: Collector and export connectors
item: module.connectors
description: Documentation hub for module.connectors — every document about it, by slot.
tags: [product-item, module]
---

# Collector and export connectors (`module.connectors`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.connectors`](../../../../../product-map/module/connectors.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Inspecto as the feeder — warehouse and lake targets](plan-warehouse-targets.md) |
| spec | current | [Collector Connectors](spec-connectors.md) |
| architecture | current | [Connectors Module (providers/inspecto-connectors/)](architecture-connectors.md) |
| features | current | [Collector and export connectors — features](features-connectors.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Connections](user-guide-connections.md) · [Connect SFTP or a database](user-guide-howto-connect-sftp-or-a-database.md) |
| admin-guide | current | [Remote connectors — operator runbook](admin-guide-connectors-runbook.md) |
| api | current | [Collector connector SPI](api-connectors.md) |
| tests | current | [Collector and export connectors — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.connectors` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `DB-CONNECTION-CONSOLE-1` | P3 | defect | the console cannot make a database Connection a Collector can use: | — |
| `ONBOARDING-REMOTE-SYSTEMS-1` | P3 | feature | Connect a system on Standard: Add data and the guided editor's Collect stage offer remote kinds (SFTP, object stores, other databases) wi… | — |
