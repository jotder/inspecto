---
type: ProductItem
title: Case management and objects
item: module.ops
description: Documentation hub for module.ops — every document about it, by slot.
tags: [product-item, module]
---

# Case management and objects (`module.ops`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.ops`](../../../../../product-map/module/ops.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.ops - board row detail](plan-board-detail.md) · [Case management and objects — plan of record](plan-ops.md) |
| spec | current | [Case management and objects — implementation spec](spec-ops.md) |
| architecture | current | [Case management and objects — architecture](architecture-ops.md) |
| features | current | [Case management and objects — features](features-ops.md) |
| feature-db | current | [Case management and objects — feature database](feature-db.md) |
| user-guide | current | [Incidents & Cases (Objects)](user-guide-objects.md) |
| admin-guide | current | [Case management and objects — admin guide](admin-guide-ops.md) |
| api | current | [HTTP routes — module.ops](api-routes.md) |
| tests | current | [Case management and objects — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.ops` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LEGAL-HOLD-SCOPE-1` | P3 | feature | A legal hold does not yet protect a Pipeline's data partitions, and holds have no screen. | — |
| `RUN-TRIGGER-ACTOR-SPOOF-1` | P3 | defect | ?actor= on POST /jobs/{name}/trigger and POST /jobs/runs/{id}/replay is free text written into the run ledger's trigger field (manual:<ac… | — |
