---
type: Feature
title: "Business assurance — user guide"
description: "Working day to day in a Space created from the business-assurance Space Template."
tags: [business-assurance, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Business assurance — user guide

How an analyst works in a Space created from the `business-assurance` Space Template. Install steps: [admin guide](admin-guide.md).

## The data

The Space starts with seeded results so the dashboard is populated on day one:

| Dataset | What it holds |
|---|---|
| `revenue_forecast` | daily revenue with its forecast and band |
| `margin_erosion` | margin per product, channel and partner |
| `ba_revenue_forecast`, `ba_margin_lines`, `ba_margin_erosion` | the views the KPIs and Widgets read |

Once an administrator points the two Jobs at your own `daily_revenue` and `margin_lines` stores and enables them, they refresh `revenue_forecast` and `margin_erosion`.

## Dashboard

The `ba_overview` dashboard shows:

- KPI tiles for revenue, margin and days outside band;
- revenue against forecast;
- margin erosion by partner.

## Alerts

| Alert Rule | Raised when |
|---|---|
| `ba_revenue_outside_band` | daily revenue is outside its forecast band |
| `ba_revenue_drift` | daily revenue is drifting away from its trend |
| `ba_revenue_regime_change` | daily revenue shifted to a new level |
| `ba_margin_erosion` | margin eroded by more than 5 points (per product, channel and partner) |
| `ba_margin_data_quality` | a margin line was not assessed: null or negative revenue or cost |

Triage Alerts and Incidents in the Incidents screens ([Incidents user guide](../../addon/incidents/user-guide-incidents.md)).

## Runbooks

`config/runbooks/business-assurance-runbooks.md` in the Space.
