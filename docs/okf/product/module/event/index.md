---
type: ProductItem
title: Event model and log
item: module.event
description: Documentation hub for module.event — every document about it, by slot.
tags: [product-item, module]
---

# Event model and log (`module.event`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.event`](../../../../../product-map/module/event.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Event model and log — plan](plan.md) |
| spec | current | [Events & Metrics](spec-events-metrics.md) · [Signal Backbone (canonical envelope, agent context fabric, gated agentic write)](spec-signal-backbone.md) |
| architecture | current | [Events and event log](architecture-overview.md) |
| features | current | [Events and event log — features](features-events.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Events & Activity](user-guide-events.md) |
| admin-guide | current | [Event model and log — admin guide](admin-guide.md) |
| api | current | [Events and event log — API](api-events.md) |
| tests | current | [Event model and log — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.event` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `SES-SNS-LIVE-CHECKS-1` | P3 | chore | live checks of the SES/SNS adapter, when a customer names SES | external |
| `OPSTORE-BULK-INSERT-1` | P2 | defect | row-at-a-time JDBC inserts into a file-backed DuckDB run at hundreds of rows/s, on 1.5.6 AND 2.0. | — |
