---
type: ProductItem
title: Business Insights
item: module.insights
description: Documentation hub for module.insights — every document about it, by slot.
tags: [product-item, module]
---

# Business Insights (`module.insights`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.insights`](../../../../../product-map/module/insights.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | missing | |
| spec | missing | |
| architecture | current | [Business Insights](architecture-insights.md) |
| features | missing | |
| feature-db | current | [Business Insights — feature database](feature-db.md) |
| user-guide | current | [Business Insights — screens](user-guide-insights.md) |
| admin-guide | missing | |
| api | current | [HTTP routes — module.insights](api-routes.md) |
| tests | current | [Business Insights — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.insights` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `BI-INSIGHTS-1` | P2 | feature | Advanced insights on business data in Widgets and Dashboards, authored and tuned as Insight Profiles. | — |
| `INSIGHT-CASE-EXISTING-1` | P3 | feature | An Insight can only open a NEW Case (filed 2026-11-26, BI-INSIGHTS-1 I-17). | demand |
| `ACTION-TEMPLATE-ESCAPE-1` | P3 | defect | An Action Request payload cannot carry literal {{…}} text (filed 2026-11-26, BI-INSIGHTS-1 I-17). | — |
| `INSIGHT-SCAN-WRITER-UNLISTED-1` | P2 | defect | ConfigWriteFunnelTest.everyConfigWriterRepoWideReachesTheHoldOrIsOnTheInventory is red on master: InsightScanJobType#register (writes the… | — |
