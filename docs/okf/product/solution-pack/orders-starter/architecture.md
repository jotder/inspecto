---
type: Feature
title: "Orders starter — architecture"
description: "How the Orders starter template wires a feed to a dashboard."
tags: [orders-starter, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Orders starter — architecture

Space Template `orders-starter` (`spaces/_templates/orders-starter/template.toon`). Offering: every Edition from Basic (`offerings/basic.toon` `contentPacks`).

## How it fits together

```
data/inbox/orders/*.csv
  -> Pipeline `orders` (orders_schema.toon, derived GROSS column, Parquet out)
       -> Expectation `orders_id_non_null` (ORDER_ID non-null)
  -> Dataset `orders_dataset` (physicalRef orders/database)
       -> Widgets `orders_kpi_total` (kpi: count of order_id)
                  `orders_revenue_by_region` (bar: sum of gross by region)
       -> Dashboard `orders_overview`
```

## Parts

| Part | File | Notes |
|---|---|---|
| Pipeline | `config/orders/orders_pipeline.toon` | polls `data/inbox/orders`, `glob:**/*.csv`, 2 threads, duplicate check (30-day markers), Parquet + snappy |
| Schema | `config/orders/orders_schema.toon` | the CSV columns plus derived `GROSS` |
| Gap detection | same Pipeline, `collector.gap_detection` | daily sequence `ORDERS_{yyyyMMdd}` |
| Expectation | `config/registry/expectations/orders_id_non_null.toon` | `kind: non_null` on `ORDER_ID`, target the `orders` Pipeline |
| Dataset | `config/registry/datasets/orders_dataset.toon` | the queryable output |
| Dashboard | `config/registry/dashboards/orders_overview.toon` | two tiles: KPI (span 1) and bar (span 2) |
| Samples | `data/samples/orders/` | three daily files, 2026-07-01 to 2026-07-03 |

## Modules used

[etl](../../module/etl/index.md) for the Pipeline, Schema and Expectation; Studio BI for Widgets and the dashboard.

## Limits

No Jobs, Alert Rules or scores — it is a starter to copy for a first feed.
