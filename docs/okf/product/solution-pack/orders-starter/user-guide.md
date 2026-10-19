---
type: Feature
title: "Orders starter — user guide"
description: "How a user works with the Orders starter Space."
tags: [orders-starter, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Orders starter — user guide

What a Space created from `orders-starter` shows, and how to use it as a pattern for your own feed. Design: [architecture](architecture.md).

## Watch the feed land

1. Copy the files of `data/samples/orders` into `data/inbox/orders`.
2. The `orders` Pipeline picks up each `ORDERS_<yyyyMMdd>.csv`, applies the Schema (adding `GROSS`) and writes Parquet. A repeated file is skipped by the duplicate check; a missing day is reported by gap detection. See [Pipelines](../../module/etl/user-guide-pipelines.md).
3. The `orders_id_non_null` Expectation checks that every row has an `ORDER_ID`.

## Look at the result

- Query `orders_dataset` in the Catalog — [Catalog guide](../../layer/product/user-guide-catalog.md).
- Open the `orders_overview` dashboard: **Total orders** (count of `order_id`) and **Revenue by region** (sum of `gross` by `region`).

## Make it yours

Replace the sample feed with your own CSV: change the Pipeline's poll folder and file pattern, the Schema columns and the Expectation column, then point the Widgets at your fields — [Pipeline editor](../../module/etl/user-guide-pipeline-editor.md).
