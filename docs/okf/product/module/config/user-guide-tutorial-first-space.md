---
type: Tutorial
title: "Tutorial 1 — Your first Space and Dataset"
description: "Create the Order-to-cash Space from its Space Template, see its two sample feeds land, and look at the orders Dataset: its columns, its rows and the files it came from."
item: module.config
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-config
---

# Tutorial 1 — Your first Space and Dataset

In this tutorial you create a **Space** — the workspace that holds one team's data, checks and screens — and look at
your first **Dataset**, the table your data lands in. It is the first of four builder tutorials on one worked example,
**Order-to-cash assurance**: a company's orders checked against the payments its customers made. By the end of the
four you have Expectations, a Reconciliation, Incidents, a Case and a Dashboard, all on the same data.

**Time:** about 15 minutes.

## Before you start

- **Edition:** Standard, Advanced or Preview. The Order-to-cash Space Template ships from Standard up.
- **Who:** a user who may create Spaces and author Alert Rules, because the template brings an Alert Rule with it. The
  built-in *admin* role alone is not enough; *admin* together with *power*, or *super*, is.
- **What you need:** nothing else. The template brings its own sample files: one month (July 2026) of synthetic orders
  and payments for a fictional company, with a few mistakes planted on purpose so that the later tutorials have
  something to find.

New to the words? Read [Space](user-guide-concept-space.md) and
[Dataset](../../layer/product/user-guide-concept-dataset.md) first, or keep them open beside this page.

## Step 1 — Create the Space from the template

1. Open **Settings → Spaces**.
2. Click **New from template**.
3. Pick **Order-to-cash assurance**. The dialog says *Its sample data is loaded and each of its Pipelines runs once,
   so its Datasets are not empty.*
4. **Id** is filled in as `order-to-cash`. Keep it, so that the names in these tutorials match your screen.
5. Click **Create space**. It takes a few seconds.
6. Switch to the new Space with the Space picker at the top of the console: **Order-to-cash assurance**.

**What happened:** the template wrote everything the worked example needs into the Space — two Pipelines, three
Datasets, four Expectations, a Reconciliation, an Alert Rule, a Case Rule, three Jobs, three Widgets and a Dashboard —
and put the sample files into each Pipeline's inbox. Each Pipeline then ran once and landed its files.

A new Space is closed: only you and the administrators can open it until someone gives other people a role in it
(see [Roles and Access Policies](../access/user-guide-concept-roles-access-policies.md)).

```check
POST /spaces
as builder
body {"id": "{space}", "template": "order-to-cash", "loadSamples": true}
expect status 200|409
```

## Step 2 — See the files land

1. Open **Platform → Workbench → Runs**.
2. You see two rows, **orders** and **payments**. The **Committed** column counts the files each Pipeline landed:
   **10** for orders (one file a day, 1 to 10 July) and **23** for payments (one file for each day a payment arrived).
3. On the **orders** row, click **Open detail**, then the **Files** tab. Each file is listed with its name
   (`ORDERS_20260701.csv` …), its status **SUCCESS**, and how many rows it parsed.

The **Quarantine** tab beside it is empty: every row of every file matched the Pipeline's Schema.

```check
GET /spaces/{space}/runs/orders/files
as builder
expect status 200
expect data.length = 10
expect data[*].status has SUCCESS
```

## Step 3 — Look at the orders Dataset

1. Open **Platform → Catalog → Datasets**. The Space holds three: **orders**, **payments** and **o2c_open_items**.
   The third is empty until [Tutorial 2](../elt/user-guide-tutorial-pipeline-expectations.md) fills it.
2. Open **Platform → Catalog → Data Browser** and pick **orders** in the list on the left.

You see **121 row(s)** and the columns `order_id`, `order_date`, `customer_id`, `currency`, `amount` and the date
parts the Pipeline adds. That is one row more than there are orders: **120 orders, 121 rows**.

3. Click **Search rows** and search for `O-1050`. It is there twice, with the same date, customer and amount: the
   order was sent again in the next day's file. You will catch it with an Expectation in Tutorial 2.

```check
GET /spaces/{space}/datasets/orders/rows?limit=500
as builder
expect status 200
expect data.statistics.rowCount = 121
expect data.columns[*].name has order_id
expect data.columns[*].name has amount
```

4. Pick **payments**: **123 row(s)**, one per payment, with `payment_id`, `payment_date`, `order_id`, `customer_id`,
   `amount` and `method`. Search for `O-1017`: its payment was keyed as **-240** — another planted mistake.

```check
GET /spaces/{space}/datasets/payments/rows?limit=500
as builder
expect status 200
expect data.statistics.rowCount = 123
```

## What you learned

- A **Space** holds one team's data and everything built on it; a **Space Template** fills a new Space with a
  ready-made set of parts.
- A **Dataset** is the table a feed lands in. Its rows come from the files a **Pipeline** read.
- You can see what landed in three places: the Pipeline's **Runs** (files and quarantine), the **Datasets** list, and
  the rows themselves in the **Data Browser**.

## Next

- [Tutorial 2 — Make it a Pipeline with Expectations](../elt/user-guide-tutorial-pipeline-expectations.md): open the
  two Pipelines and let four Expectations find the planted mistakes.
- Concepts: [Space Template](user-guide-concept-space-template.md) ·
  [Pipeline](../elt/user-guide-concept-pipeline.md)
