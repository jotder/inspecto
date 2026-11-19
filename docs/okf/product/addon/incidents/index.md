---
type: ProductItem
title: Incidents
item: addon.incidents
description: Documentation hub for addon.incidents — every document about it, by slot.
tags: [product-item, addon]
---

# Incidents (`addon.incidents`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`addon.incidents`](../../../../../product-map/addon/incidents.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Alerts & Incidents (INC)](plan-incidents.md) |
| spec | n/a | an Add-on licenses modules — see the hubs of the modules it holds |
| architecture | current | [Incidents and Alerts](architecture-incidents.md) |
| features | current | [Alerts — features](features-alerts.md) · [Incidents — features](features-incidents.md) |
| feature-db | current | [Incidents — feature database](feature-db.md) |
| user-guide | current | [Alerts](user-guide-alerts.md) · [Alert, Incident and Case](user-guide-concept-alert-incident-case.md) · [Alert Rule, Expectation and Decision Rule](user-guide-concept-alert-rule.md) · [Incidents — screens](user-guide-incidents.md) · [Tutorial 3 — Detect and respond: Reconciliation, Alert Rule, Incidents and a Case](user-guide-tutorial-detect-respond.md) |
| admin-guide | n/a | an Add-on licenses modules — see the hubs of the modules it holds |
| api | current | [Alerts — API](api-alerts.md) · [Incidents — API](api-incidents.md) |
| tests | n/a | an Add-on licenses modules — see the hubs of the modules it holds |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `addon.incidents` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LEGAL-HOLD-SCOPE-1` | P3 | feature | A legal hold does not yet protect a Pipeline's data partitions, and holds have no screen. | — |
| `ALERTS-PAGE-WORDS-1` | P3 | defect | the Alert text the server writes still uses stored ids | — |
| `MODULE-REORG-P7-INCIDENTS` | P3 | chore | Delete AlertMigration (and LegacyAlertObjects) one release after the Alert store ships. | — |
| `DOCS-SKILLS-ALERTS-AGENTIC-1` | P3 | feature | hub-routed agent skills exist for docs lookup, pipeline authoring, incident investigation and case analysis (.claude/skills/) | demand |
