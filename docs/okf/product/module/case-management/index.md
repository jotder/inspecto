---
type: ProductItem
title: Case Management
item: module.case-management
description: Documentation hub for module.case-management — every document about it, by slot.
tags: [product-item, module]
---

# Case Management (`module.case-management`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.case-management`](../../../../../product-map/module/case-management.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.case-management - board row detail](plan-board-detail.md) · [Case Management — requirement of record](plan-case-management.md) |
| spec | current | [Case Management — specification](spec-case-management.md) |
| architecture | current | [Case Management](architecture-case-management.md) |
| features | current | [Case Management — features](features-case-management.md) |
| feature-db | current | [Case Management — feature database](feature-db.md) |
| user-guide | current | [Case Management — screens](user-guide-case-management.md) · [Open and work a Case](user-guide-howto-open-and-work-a-case.md) |
| admin-guide | current | [Case Management — admin guide](admin-guide-case-management.md) |
| api | current | [Case Management — API](api-case-management.md) · [HTTP routes — module.case-management](api-routes.md) |
| tests | current | [Case Management — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.case-management` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `INSIGHT-CASE-EXISTING-1` | P3 | feature | An Insight can only open a NEW Case (filed 2026-11-26, BI-INSIGHTS-1 I-17). | demand |
| `CASE-REASSIGN-CONSOLE-1` | P3 | feature | an existing Case cannot be re-assigned in the console | — |
| `CASE-TRANSITION-FOUR-EYES-1` | P3 | feature | a Workflow transition on a Case has no guard: | — |
| `D6-REAL-USER-SESSION-1` | P3 | chore | The D10 think-aloud with a REAL Case-desk lead is still unrun. | external |
| `CASE-FROM-ENTITIES-ACTOR-1` | P3 | defect | POST /cases/from-entities records the body's free-text actor on the new Case's member links (CaseOperations.openCaseFromEntities, and on… | — |
| `MODULE-REORG-P7-INCIDENTS` | P3 | chore | Delete AlertMigration (and LegacyAlertObjects) in 5.0.0; 4.0.0 ships the migration. | — |
