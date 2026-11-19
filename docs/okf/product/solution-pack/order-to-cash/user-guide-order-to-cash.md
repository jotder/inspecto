---
type: Feature
title: "Order-to-cash assurance — user guide"
description: "How a user works with a Space created from the order-to-cash template: land the feeds, read the Expectations, Breaks, Incidents and Cases, and the Dashboard."
tags: [order-to-cash, user-guide, handbook]
timestamp: 2026-11-19T00:00:00Z
item: solution-pack.order-to-cash
audience: internal
status: current
verified: 2026-11-19 last-edit
code: spaces/_templates/order-to-cash
---

# Order-to-cash assurance — user guide

What a Space created from `order-to-cash` does, and what you should find in it. This is the Handbook's worked example: the
tutorials walk through it step by step. Design and ground truth: [architecture](architecture-order-to-cash.md). Needs the
Standard, Advanced or Preview edition.

## Create the Space

**From the console:** Spaces → **New from template** → *Order-to-cash assurance*, with **Load samples** ticked.
**Over the Control API:**

```bash
curl -X POST http://localhost:8080/api/v1/spaces -H "Content-Type: application/json" \
  -d '{"id":"o2c","template":"order-to-cash","loadSamples":true}'
```

Without samples, copy `data/samples/orders/*.csv` into `data/inbox/orders` and `data/samples/payments/*.csv` into
`data/inbox/payments`. The `orders` and `payments` Pipelines land each file as Parquet.

## Run the checks

1. **Expectations** (Data quality → Expectations → *Evaluate all*). Expect `payments_amount_non_negative` to fail with 1
   violation (a payment keyed as -240.00 against O-1017) and `payments_order_known` with 2 (payments against O-9001 and
   O-9002, orders that do not exist). `orders_amount_non_negative` passes. `orders_id_unique` reads the open items, so run
   the `o2c_open_items` Job first; it then fails with 1 (O-1050 arrived twice).
2. **Open items** (Jobs → `o2c_open_items` → *Run now*). One row per order id, with `unmatched_value` and a `status`.
3. **Reconciliation** (Reconciliation → `o2c_orders_vs_payments`, day 2026-07-01, the month). Expect 8 Breaks: O-1041 and
   O-1088 unpaid (missing on the payments side), O-9001 and O-9002 orphan payments (missing on the orders side), and value
   breaks on O-1017, O-1022 (300.00 of 500.00 paid), O-1050 and O-1064 (95.00 of 99.50). O-1050 is also a cardinality
   break: one order key, two order rows.
4. **Incidents** (the Alert Rule `o2c_unmatched_value` runs with the alert evaluation). One Incident per order whose
   unmatched value is 100 or more: O-1017, O-1022, O-1041, O-1050, O-1088, O-9001. O-1064 (4.50) and O-9002 (55.00) stay
   Breaks and raise nothing. The Reconciliation run itself also opens one Incident because it found Breaks, so the
   Incidents list shows seven in all; only the six order Incidents carry a `customer_id` and join a Case.
5. **Cases** (Jobs → `o2c_collections_case` → *Run now*). One collections Case per customer: five, and customer C-007's
   Case holds two Incidents (O-1022 and O-1041).
6. **Dashboard** `o2c_overview`: the unmatched value in total, by status and by customer.

O-1012 is paid in full but 15 days late: it is no Break and no Incident; the open items show it as `paid_late`.

## Work a Case with the write-off approval

An administrator first applies the collections workflow (`config/governance/case-workflow.json`) with
`POST /components/workflow`. A Case then moves `OPEN → INVESTIGATING`, and from there either through the built-in
`resolve → RESOLVED → close`, or through the write-off step: `propose_write_off → PENDING_APPROVAL`, then
`approve_write_off → WRITE_OFF_APPROVED → close`, or `send_back` to `INVESTIGATING`. The product does not stop the person who
proposed a write-off from approving it: agree in your team that a second person approves.

## Make it your own

Point the two Pipelines at your own order and payment extracts (same columns, or edit the Schemas), change `terms_days` on
the `o2c_open_items` Job, and the Alert Rule's `threshold` to your materiality.
