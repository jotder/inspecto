---
type: Tutorial
title: "Tutorial 4 — Present it: a Dashboard for the business user"
description: "Open the Order-to-cash Dashboard, read what each Widget says about the unmatched value, and put the Dashboard in a menu where a business user finds it."
item: layer.product
audience: external
status: current
verified: 2026-11-19 last-edit
---

# Tutorial 4 — Present it: a Dashboard for the business user

The work of Tutorials 1 to 3 ends in a question a finance manager asks every month: **how much money is unmatched,
why, and with whom?** In this tutorial you open the **Dashboard** that answers it, learn to read its three
**Widgets**, and place it in a menu of its own so that a business user finds it without knowing the console.

**Time:** about 15 minutes.

## Before you start

- **Edition:** Standard, Advanced or Preview (the worked example's Space Template needs Standard). Dashboards and
  menus themselves are in every edition.
- **Who:** the builder who created the Space, to open the Dashboard Builder and add the menu. The business user
  needs only a role in the Space that lets them read.
- **What you need:** the `order-to-cash` Space with the **o2c_open_items** Job run once
  ([Tutorial 2](../../module/elt/user-guide-tutorial-pipeline-expectations.md), step 4): every Widget reads the open
  items.

Concept used here: [Widget and Dashboard](user-guide-concept-widget-dashboard.md).

## Step 1 — Open the Dashboard

1. Open **Platform → Studio → Dashboard Builder**. One Dashboard is listed: **o2c_overview**, *3 widget tile(s)*.
2. Click **Open** (the pencil on its card).

You are in the builder's view: **Add widget**, a **Description**, **Export PNGs**, **History**, **Share** and
**Save dashboard** sit above the three tiles. Nothing here needs changing.

```check
GET /spaces/{space}/components/dashboard/o2c_overview
as builder
expect status 200
expect data.content.tiles.length = 3
expect data.content.tiles[*].widgetId has o2c_unmatched_total
```

## Step 2 — Read the three Widgets

All three read the **o2c_open_items** Dataset and add up its **unmatched value** — for each order, the difference
between what was ordered and what was paid.

**Unmatched value (USD)** — a single number: **1,784.5**. That is the whole month's gap between orders and payments.

```check
POST /spaces/{space}/bi/query
as builder
body {"dataset": "o2c_open_items", "measures": [{"id": "unmatched", "field": "unmatched_value", "agg": "sum"}]}
expect status 200
expect data.rows[0].sum_unmatched_value = 1784.5
```

**Unmatched value by status** — the same money split by why it is unmatched:

| Status | Unmatched value | Orders behind it |
|---|---|---|
| short_paid | **684.50** | O-1017 (the payment keyed as -240), O-1022 (300 of 500 paid), O-1064 (95 of 99.50) |
| unpaid | **555.00** | O-1041, O-1088 |
| orphan_payment | **365.00** | O-9001, O-9002 — money received for orders that do not exist |
| duplicate_order | **180.00** | O-1050, ordered once, sent twice |
| paid, paid_late | **0** | every other order |

O-1012 is *paid_late*: it was paid in full, 15 days after its due date, so it adds nothing to the unmatched value. A
late payment is a different question from a missing one.

```check
POST /spaces/{space}/bi/query
as builder
body {"dataset": "o2c_open_items", "measures": [{"id": "unmatched", "field": "unmatched_value", "agg": "sum"}], "groupBy": ["status"]}
expect status 200
expect data.rows.length = 6
expect data.rows[*].status has short_paid
expect data.rows[4].status = "short_paid"
expect data.rows[4].sum_unmatched_value = 684.5
expect data.rows[5].status = "unpaid"
expect data.rows[5].sum_unmatched_value = 555
```

**Unmatched value by customer** — who to call. The tallest bar is customer **C-007** at **620.00**: the short payment
on O-1022 and the unpaid O-1041. It is the customer whose collections Case held two Incidents in
[Tutorial 3](../../addon/incidents/user-guide-tutorial-detect-respond.md).

```check
POST /spaces/{space}/bi/query
as builder
body {"dataset": "o2c_open_items", "measures": [{"id": "unmatched", "field": "unmatched_value", "agg": "sum"}], "groupBy": ["customer_id"]}
expect status 200
expect data.rows[*].customer_id has C-007
```

## Step 3 — Put the Dashboard where a business user finds it

A business user should not need to know where the Dashboard Builder is. Give the Dashboard a menu of its own.

1. Open **Platform → Studio → Menus**. The page explains that your menus appear in the sidebar beside the platform
   navigation.
2. Click **Add menu**. Type `Collections` in **Name**, leave **Icon** empty, and click **Save**.
3. On the **Collections** row, open **Actions for Collections** and choose **Add report**.
4. In **Add a report**, pick **o2c_overview** (type *Dashboard*).

**Collections** now sits at the top of the sidebar, with **o2c_overview** under it. Click it: the Dashboard opens
without the builder's controls — the three Widgets only, as the business user will see it.

To let the business user open it, give them a role in the Space that lets them read
([Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)). The **Share** button
on the Dashboard makes a public read-only link instead; it works only once an administrator has switched link
sharing on for the server.

## What you learned

- A **Dashboard** arranges **Widgets**; each Widget is one question asked of one Dataset — a total, a split by
  status, a split by customer.
- The numbers on the Dashboard are the same ones the Expectations, the Reconciliation and the Incidents found, added
  up for the people who decide.
- A **menu** puts a Dashboard in front of a business user without sending them through the builder's screens.

## Next

You have built the whole worked example: data in, checked, reconciled, escalated, worked and presented.

- Concepts: [App](user-guide-concept-app.md) — how a working Space like this one becomes something you ship.
- Start again from the overview: [Inspecto concepts](user-guide-concept-overview.md)
