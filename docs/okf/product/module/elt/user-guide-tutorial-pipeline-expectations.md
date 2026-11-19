---
type: Tutorial
title: "Tutorial 2 — Make it a Pipeline with Expectations"
description: "Open the orders and payments Pipelines of the Order-to-cash worked example, run its four Expectations, and find the planted mistakes: a negative payment, two orphan payments and a duplicate order."
item: module.elt
audience: external
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Tutorial 2 — Make it a Pipeline with Expectations

In [Tutorial 1](../config/user-guide-tutorial-first-space.md) two feeds landed in your Order-to-cash Space. Now you
look at the **Pipelines** that land them and let **Expectations** — data-quality checks — tell you what is wrong with
the data. Three mistakes were planted in the sample files; by the end of this tutorial you have found all of them.

**Time:** about 20 minutes.

## Before you start

- **Edition:** Standard, Advanced or Preview (the worked example's Space Template needs Standard). Pipelines and
  Expectations themselves are in every edition.
- **Who:** a user who may run checks and Jobs in the Space — the builder who created it in Tutorial 1.
- **What you need:** the `order-to-cash` Space from [Tutorial 1](../config/user-guide-tutorial-first-space.md).

Concepts used here: [Pipeline](user-guide-concept-pipeline.md) · [Expectation](user-guide-concept-expectation.md).

## Step 1 — Open the two Pipelines

1. Open **Platform → Workbench → Pipelines**.
2. Click **Open a pipeline…**, tick **orders**, and click **Open**.

The canvas shows the orders Pipeline's steps. It picks up the files dropped in its own inbox folder, reads each one
as CSV against its Schema, skips a file it has already processed, and writes the rows as Parquet into the **orders**
Dataset. Its **Gap detection** step watches the file names (`ORDERS_<date>`) and tells you when a day's file is
missing.

3. Open **payments** the same way. It works the same way, without gap detection: payments do not arrive every day.

## Step 2 — Check what each run took in

1. Open **Platform → Workbench → Runs**, and on the **payments** row click **Open detail**.
2. The **Files** tab lists **23** payment files, each **SUCCESS**.
3. The **Quarantine** tab is **empty**.

So the files were well-formed — every row had the right columns and types. That does not make the data right: a
payment of -240 is a perfectly well-formed number. Catching that is the job of Expectations, which read the data that
landed and count the records that break what they state.

```check
GET /spaces/{space}/runs/payments/files
as builder
expect status 200
expect data.length = 23
```

```check
GET /spaces/{space}/runs/payments/quarantine
as builder
expect status 200
expect data.length = 0
```

## Step 3 — Run the Expectations

1. Open **Platform → Workbench → Expectations**. Four are listed, each with the Pipeline or Job it checks:

| Expectation | Checks | Kind |
|---|---|---|
| `orders_amount_non_negative` | every order amount is zero or more | range, min 0 |
| `payments_amount_non_negative` | every payment amount is zero or more | range, min 0 |
| `payments_order_known` | every payment names an order the orders feed knows | referential, `orders.order_id` |
| `orders_id_unique` | no order id arrives twice | range, max 1, on the open items |

2. Click **Run all checks**.

**What you should see:**

- `orders_amount_non_negative` **passed** — 0 violations.
- `payments_amount_non_negative` **failed** — **1** violation: the payment against order O-1017, keyed as -240.00
  instead of 240.00.
- `payments_order_known` **failed** — **2** violations: payments against O-9001 (310.00) and O-9002 (55.00), orders
  that do not exist. These are **orphan payments**.
- `orders_id_unique` **passed** — but only because what it reads is still empty. It checks the **open items**, a
  table a Job builds from both feeds. You run that Job next.

Each failed Expectation also opened an **Incident** (severity MAJOR), so the breach is waiting in
**Operations → Incidents** for someone to work it. Running the checks again does not open a second Incident for the
same breach while the first one is open.

```check
POST /spaces/{space}/expectations/evaluate
as builder
expect status 200
expect data[0].name = "orders_amount_non_negative"
expect data[0].lastResult.violations = 0
expect data[2].name = "payments_amount_non_negative"
expect data[2].lastResult.violations = 1
expect data[3].name = "payments_order_known"
expect data[3].lastResult.violations = 2
```

## Step 4 — Build the open items, then check for duplicates

The **open items** are one row per order id seen in either feed: what was ordered, how many times the order arrived
(`order_copies`), what was paid, the **unmatched value** (the difference between the two) and a **status** — paid,
paid_late, short_paid, over_paid, unpaid, duplicate_order or orphan_payment. A Job writes them into the
**o2c_open_items** Dataset.

1. Open **Platform → Workbench → Jobs** and click **o2c_open_items**. Its panel opens beside the list.
2. Click **Run with parameters…**, then **Run** — this Job needs no parameters. Its **Execution history** tab shows
   the run finish in a second or two with **SUCCESS**, having written **122 rows** — 120 orders plus the two orphan
   payments.

```check
POST /spaces/{space}/jobs/o2c_open_items/trigger
as builder
expect status 200|202
save data.runId as openItemsRun
```

```check
GET /spaces/{space}/jobs/runs/{openItemsRun}
as builder
expect data.status = "SUCCESS"
expect data.message matches /122 row/
retry 30 every 2s
```

3. Go back to **Expectations** and click **Run all checks** again.

`orders_id_unique` now **fails** with **1** violation: order **O-1050** has `order_copies` 2 — it arrived twice, the
duplicate you found in the Data Browser in Tutorial 1.

```check
POST /spaces/{space}/expectations/evaluate
as builder
expect status 200
expect data[1].name = "orders_id_unique"
expect data[1].lastResult.status = "FAILED"
expect data[1].lastResult.violations = 1
```

## What you learned

- A **Pipeline** collects files, reads them against a Schema and lands them in a Dataset. Its **Runs** show each file
  it took in and anything it quarantined.
- Well-formed is not the same as right. An **Expectation** states what right means and counts the records that break
  it; a failure opens an **Incident**.
- Some checks read a table a **Job** builds — run the Job first, or the check has nothing to read.

All three planted data mistakes are found: one negative payment, two orphan payments, one duplicate order.

## Next

- [Tutorial 3 — Detect and respond](../../addon/incidents/user-guide-tutorial-detect-respond.md): compare every order
  with what was paid for it, raise Incidents on the orders that matter, and work a collections Case.
- Concepts: [Alert Rule, Expectation and Decision Rule](../../addon/incidents/user-guide-concept-alert-rule.md)
