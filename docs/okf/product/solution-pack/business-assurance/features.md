---
type: Feature
title: "Business assurance — features"
description: "Seasonal revenue forecast with a prediction band, and margin erosion by product, channel and partner."
tags: [business-assurance, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Business assurance — features

**Product Map Item:** [`solution-pack.business-assurance`](../../../../../product-map/solution-pack/business-assurance.toon) · **Status:** ga · **Availability:** billable Solution Pack `INS-SP-BUSINESS-ASSURANCE`, listed under `contentPacks` in `offerings/basic.toon` (Standard, Advanced and Preview build on Basic); Space Template `business-assurance`.

## What it is

A business assurance pack over a deterministic synthetic corpus: 26 weeks of daily revenue, and 8 weeks of sales lines across 4 products, 3 channels and 3 partners.

## Who benefits

Finance, commercial and business-assurance teams watching revenue and margin.

## Key capabilities

- Holt-Winters additive revenue forecast in DuckDB SQL with a prediction band (`revenue_forecast` Dataset).
- Revenue Alert Rules on the forecast: outside band (one Alert per day), drift from trend, and regime change (a shift to a new level).
- Margin model by product, channel and partner (`margin_erosion` Dataset) with an Alert Rule for margin eroded by more than 5 points.
- A margin data-quality Alert Rule for lines with null or negative revenue or cost.
- Two `sql.template` Jobs (`ba_revenue_forecast_job`, `ba_margin_erosion_job`) that run the same SQL over your own `daily_revenue` and `margin_lines` stores; both ship disabled.
- Three KPI definitions (revenue, margin, days outside band), Widgets and the `ba_overview` dashboard.
- Runbooks: `config/runbooks/business-assurance-runbooks.md`.

## Related hubs

[Architecture](architecture.md) · [Revenue assurance architecture](architecture-revenue-assurance.md) · [Brochure](brochure.md) · [incidents](../../addon/incidents/index.md)
