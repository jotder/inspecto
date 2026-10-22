---
type: Feature
title: Analytics functions
description: Read-only catalogue of the Analytics Kernel's functions (id, parameters, cost hint, result kind), grouped by kernel; registry-driven on the server.
resource: inspecto-ui/src/app/modules/admin/analytics-functions/analytics-functions.routes.ts
tags: [feature, analytics, catalogue, operations]
timestamp: 2026-10-18T00:00:00Z
---

# Analytics functions

ANALYTICS-CORE-1 Could item C8, built 2026-10-18 (decisions D-AN33…D-AN35 in
[`analytics-core-plan.md`](../../../../archived-documents/plans-archive/analytics-core-plan.md) §7). Route `/analytics-functions`
(Operations nav group, lazy). It is the UI twin of LA's graph algorithm catalogue, for the Analytics Kernel.

* **Data.** `GET /analytics/functions` through `AnalyticsCatalogueService` (`inspecto/api`). The pane holds the
  response in signals (`catalogue`, `kernel`) and derives the tab strip and the rows with `computed()`.
* **Layout.** `<inspecto-page-header>` (refresh icon button), an `<inspecto-section-tabs>` strip — *All* plus one tab
  per kernel with its count — and one **standard** `<inspecto-data-table>`: Function · Id · Kernel · Cost · Result ·
  Runs as · Step · Parameters (one line each, `formatParam`) · What it does. The formatting is framework-free in
  `analytics-functions.ts`.
* **Registry-driven.** Nothing in the pane names a function. A kernel added on the server (a new
  `AnalyticsFunctionProvider`) shows up as a new row, and a new kernel as a new tab, with no UI change.
* **Degradation.** A failed load empties the grid and toasts once; unreachable-backend messaging stays the
  connectivity banner's job. Cost is an asymptotic hint (constant · linear · n log n · quadratic), never a ceiling.
* **Tests.** `analytics-functions.component.spec.ts` (load, tabs and counts, kernel filter, failure, axe) and
  `analytics-functions.spec.ts` (the formatters). Backend: `ControlApiAnalyticsCatalogueTest`.

Back end and registration point: [analytics-kernel](spec.md) § *Function catalogue*.
