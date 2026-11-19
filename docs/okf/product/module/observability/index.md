---
type: ProductItem
title: Observability (metrics endpoint + events feed API)
item: module.observability
description: Documentation hub for module.observability — every document about it, by slot.
tags: [product-item, module]
---

# Observability (metrics endpoint + events feed API) (`module.observability`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.observability`](../../../../../product-map/module/observability.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.observability - board row detail](plan-board-detail.md) · [Observability & maintenance (OPS) — capability spec — Specification — Ops Lens screens; decisions and not built](plan-observability-ops-lens-decisions.md) · [Observability & maintenance (OPS) — capability spec — Refused, as-built pointers and verification](plan-observability-refused-verification.md) · [Observability & maintenance (OPS) — capability spec — Purpose, scope and requirements of record](plan-observability-scope.md) · [Observability & maintenance (OPS) — capability spec — Specification](plan-observability-specification.md) · [Observability & maintenance (OPS) — capability spec](plan-observability.md) |
| spec | current | [Observability — spec](spec-observability.md) |
| architecture | current | [Observability and backup](architecture-observability.md) |
| features | current | [Observability and backup — features](features-observability-backup.md) |
| feature-db | current | [Observability (metrics endpoint + events feed API) — feature database](feature-db.md) |
| user-guide | current | [Observability and backup — screens](user-guide-observability-backup.md) |
| admin-guide | current | [Observability — admin guide](admin-guide-observability.md) |
| api | current | [Observability and backup — API](api-observability-backup.md) · [HTTP routes — module.observability](api-routes.md) |
| tests | current | [Observability (metrics endpoint + events feed API) — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.observability` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `DEPLOY-EXPOSURE-KIT-1` | P3 | chore | Prove the deploy kit (proxy fence for /metrics and /health/details, TLS, upgrade, off-site backup, signature verify) on a real host. | — |
