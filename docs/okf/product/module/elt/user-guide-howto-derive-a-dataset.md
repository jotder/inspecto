---
type: How-to
title: "Derive a Dataset from other Datasets"
description: "Join or reshape two Datasets into a new one that the platform keeps up to date: a sql.template Job writes the result, a Dataset entry registers it, then check it before you chart it."
item: module.elt
audience: external
status: current
verified: 2026-11-21 code-reviewed
code: [platform/inspecto-engine/src/main/java/com/gamma/job, inspecto/src/main/java/com/gamma/control/JobRoutes.java, spaces/_templates/order-to-cash/config/jobs/o2c_open_items_job.toon]
---

# Derive a Dataset from other Datasets

## Goal

Build a **derived Dataset**: one whose rows are computed from other Datasets, such as one row per order with what was
invoiced and what was paid. A virtual Dataset cannot do this, because its SQL reads **one** store. A derived Dataset is
**two objects**: a **Job** that runs the SQL and writes a store, and a **Dataset entry** that registers that store so
Reports, KPIs, Reconciliations and Widgets can read it. Saving the Job does not register the Dataset, and the Dataset
returns no rows until the Job has run once.

## Before you start

- **Edition:** every edition that includes Jobs (see [the editions](../../../../EDITIONS.md)).
- **Who:** a person, or an agent whose changes another person approves, with the right to author Jobs and Datasets
  (`canAuthorWorkbench`).
- **What you need:** two Datasets that already hold rows. The example uses the `orders` and `payments` Datasets of the
  *order-to-cash* Space Template (the shipped Job is `o2c_open_items`; the example below saves it as `open_items`).

Concepts: [Dataset](../../layer/product/user-guide-concept-dataset.md).

## Steps

1. **Write the SQL over store names.** The `sources` of the Job are the stores to read, written as a comma-separated
   list (`orders,payments`). Inside the SQL, each store is a table of that name: `FROM orders`, `FROM payments`.
   Parameters are `$name` tokens: each is a flat key of the Job (`terms_days: "14"` fills `$terms_days`; `as_of: "$today"`
   fills `$as_of` with the day of the run). Cast money to `DECIMAL(18,2)` in the SQL so sums are exact.
2. **Check the Job** with `POST /validate` (type `job`) before you save it. It reports findings and saves nothing.
3. **Save the Job** with `PUT /jobs/{name}`. The body is the Job's own keys, **flat**: `type: sql.template`, `sql`,
   `sources`, `sink_dataset` (the store the result is written to), `cron`, `as_of`, your parameters and `enabled`. A key
   the Job does not know is absorbed as a parameter, never refused, so check the spelling: `sink_dataset`, not
   `sinkDataset`. Never nest parameters under `params`.
4. **Run it once:** `POST /jobs/{name}/trigger`, then read `GET /jobs/{name}/runs` until the run is `SUCCESS`. A
   `sql.template` run says how many rows it wrote and to which store.
5. **Register the Dataset:** `PUT /components/dataset/{id}` with `physicalRef` set to the Job's `sink_dataset` and, if
   Reconciliations or KPIs will cut it by period, a `dateField` naming a date column that is **never null**. Check the
   entry first with `POST /validate` (type `dataset`).
6. **Read it:** `GET /datasets/{id}/rows`.

```check
POST /validate
as builder
body {"type":"dataset","id":"open_items","config":{"physicalRef":"open_items","description":"One row per order id seen in either feed"}}
expect status 200
expect data.clean = true
```

## Check it worked

The Dataset registered, and it reads what the Job wrote. In the shipped Space the Dataset is `o2c_open_items`.

```check
GET /spaces/{space}/datasets/o2c_open_items/rows?limit=5
as builder
expect status 200
expect data.statistics.rowCount > 0
expect data.columns.length > 0
```

## What can go wrong

- `no dataset 'open_items'` from a query: the Job exists but nothing registered its store. Do step 5.
- The Dataset has no rows: the Job has not run yet or failed. Read `GET /jobs/{name}/runs` and its log.
- A trigger is missing from the Job: you used `onPipeline` or another camelCase key. Job keys are snake_case
  (`on_pipeline`, `on_signal`, `catch_up`) and a wrong one is kept as an inert parameter.
- Rows for one side only (an unmatched order or payment) have an empty date, so a KPI or Reconciliation that cuts by
  period drops them. Give the Dataset a `dateField` that is never null, for example
  `coalesce(order_date, last_payment_date)` computed in the SQL.
- `GET /jobs/types` answers 404 "no jobs registered" until the Space has its first Job. The Job type to use here is
  `sql.template`; once any Job exists the types list names it.
- A virtual Dataset that tries to join: its SQL reads one store (`sourceName`), so any other table does not exist
  there. Use this recipe instead.

## Next

- [Add an Expectation](user-guide-howto-add-an-expectation.md)
- [Add a file](../../layer/product/user-guide-howto-add-a-file.md)
