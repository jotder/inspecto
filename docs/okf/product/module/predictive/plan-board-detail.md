---
type: Plan
title: module.predictive - board row detail
description: The narrative behind module.predictive's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, predictive, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
---

# module.predictive - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### PREDICTIVE-STUDIO-PREVIEW-1

- `PREDICTIVE-STUDIO-PREVIEW-1` — **the Predictive Studio panes (list / detail / edit / History for the three kinds) and `POST /predictions/preview` (unsaved content, `canAuthorWorkbench` + save gates, a `PREVIEW_POLICY` cap).** The read route, entity panel and forecast chart shipped as `PREDICTIVE-SURFACE-1` (`okf/product/module/predictive/spec-surface.md`); models are authored through the generic Components pane until then. **Merged in 2026-11-12: `PREDICTIVE-ANALYTICS-1`** (the add-on's design row; its slices `PREDICTIVE-WINDOWED-MEASURES-1`, `-SEGMENT-RFM-1`, `-CHURN-DECLARED-1`, `-FORECAST-1` and `-SURFACE-1` shipped by 2026-11-01, so "design-only" was stale). Its open decisions travel here, answer when this row is scheduled: OD-10 churn label, OD-12 reproducibility tolerance, OD-13 validation display, OD-16 interaction with Anomaly / Risk (`okf/product/module/anomaly/plan-predictive-analytics.md` §9). OD-11 is settled by the forecast slice (`module/predictive/spec-forecast.md`); OD-15 (the Sales & Marketing pack) belongs to `PACK-SALES-MARKETING-1`.
