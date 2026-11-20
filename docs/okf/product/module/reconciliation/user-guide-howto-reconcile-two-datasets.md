---
type: How-to
title: "Reconcile two Datasets"
description: "Compare two Datasets record by record on a key — what is only on one side, and where the values differ beyond a tolerance — and read the Breaks for a day."
item: module.reconciliation
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [features/inspecto-reconciliation, inspecto-ui/src/app/modules/admin/reconciliation]
---

# Reconcile two Datasets

## Goal

Find where two Datasets that should agree do not — orders against payments, billed against delivered — as
**Breaks**: keys on one side only, and values that differ beyond a tolerance.

## Before you start

- **Edition:** Standard, Advanced or Preview (the Reconciliation Add-on). Basic has no Reconciliation.
- **Who:** a builder (`canAuthorWorkbench`) to define it; recording a run and working Breaks needs `canOperateRuns`.
- **What you need:** two Datasets with a common key column, and **a date column on each** (a column marked as the
  time of the row) — a Reconciliation compares one day at a time. A Dataset made with Add data has one if you
  confirmed its time column in step 3 of [Add a file](../../layer/product/user-guide-howto-add-a-file.md).

## Steps

1. Open **Business → Reconciliation** and click **New reconciliation**.
2. Give it a **Name**, pick the **Left dataset** (the anchor, your record of truth) and the **Right dataset**. A
   **Third dataset** is optional, for a three-way comparison against the anchor.
3. Pick the **Key column(s)** that identify a record on both sides.
4. Under **Compare columns**, click **Add** for each value to compare: the column, how to aggregate it (sum or count),
   and a tolerance — exact, ± absolute or ± percent.
5. Optionally set the Board's bands — **Warn from %** and **Breach over %**.
6. Click **Test definition** (it checks every key and compare column exists), then **Create**.
7. Open it: the Board shows the day's matched and unmatched totals. **Record run** keeps the result; **All breaks**
   lists the Breaks — filter by *Only in A*, *Only in B*, *Value breaks* or *Duplicate keys*. **Schedule** on the list
   creates a Job that runs it every day.

## Check it worked

The day's Breaks come back by type — here the worked example's `o2c_orders_vs_payments` for 1 July 2026: two orders
never paid, two payments for unknown orders, four amounts that differ:

```check
POST /spaces/{space}/recon/breaks
as builder
body {"id": "o2c_orders_vs_payments", "day": "2026-07-01"}
expect status 200
expect data.missing_right.rowCount = 2
expect data.missing_left.rowCount = 2
expect data.value_break.rowCount = 4
```

## What can go wrong

- `Dataset '<id>' has no date column; mark one 'role: temporal' in its columns or set its dateField` — every side
  needs a date column; there is no whole-period comparison.
- `no reconciliation '<id>'` (404), or a `day` that is not an ISO date (422).
- **Record run** refuses a day with more Breaks than a run may keep (50,000).
- The menu item is missing — the edition has no Reconciliation.

## Next

- [Write an Alert Rule](../../addon/incidents/user-guide-howto-write-an-alert-rule.md) — raise Incidents on the Breaks
  that matter.
- [Tutorial 3 — detect and respond](../../addon/incidents/user-guide-tutorial-detect-respond.md)
