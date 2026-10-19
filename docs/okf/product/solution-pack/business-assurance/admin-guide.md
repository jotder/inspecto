---
type: Feature
title: "Business assurance — admin guide"
description: "Install, enable and verify the Business assurance Solution Pack."
tags: [business-assurance, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Business assurance — admin guide

## Prerequisites

- Edition: Basic or higher — `offerings/basic.toon` lists `business-assurance` under `contentPacks`; the template sets no `minEdition`.
- Order code `INS-SP-BUSINESS-ASSURANCE`.
- The creating user needs `canAdminister`; the template's KPI pack also needs `canAuthorWorkbench` (see `POST /spaces` in `docs/api/openapi-v1.json`).

## Install

1. List the available Space Templates: `GET /spaces/templates` — `business-assurance` should appear.
2. Create the Space: `POST /spaces` with body `{"id": "<space-id>", "template": "business-assurance"}`. A refusal (403/422) creates nothing.

The Space ships seed results in `data/revenue_forecast/seed.parquet` and `data/margin_erosion/seed.parquet`.

## Connect your data

1. Make your `daily_revenue` and `margin_lines` stores available in the Space.
2. Review the SQL in `config/jobs/ba_revenue_forecast_job.toon` (sink `revenue_forecast`) and `config/jobs/ba_margin_erosion_job.toon` (sink `margin_erosion`).
3. Enable both Jobs (they ship `enabled: false`).

## Tune

Thresholds live in the Alert Rule files under `config/registry/alert-rules/` (for example the 5-point margin erosion threshold in `ba_margin_erosion.toon`).

## Verify

- The `ba_overview` dashboard shows revenue, margin and days-outside-band tiles.
- After the Jobs run, `revenue_forecast` and `margin_erosion` carry the new days.
- Alerts appear for days outside the band and for eroded margins.

## References

[Features](features.md) · [User guide](user-guide.md) · [Architecture](architecture.md)
