---
type: Feature
title: "Order-to-cash assurance — architecture"
description: "How the order-to-cash Space Template, the Handbook's worked example, wires two feeds to Expectations, a Reconciliation, Incidents, Cases and a Dashboard."
tags: [order-to-cash, architecture, handbook]
timestamp: 2026-11-19T00:00:00Z
item: solution-pack.order-to-cash
audience: internal
status: current
verified: 2026-11-19 last-edit
code: spaces/_templates/order-to-cash
---

# Order-to-cash assurance — architecture

Space Template `order-to-cash` (`spaces/_templates/order-to-cash/template.toon`), the one worked example every Handbook
tutorial builds on (`USER-HANDBOOK-1`, decision D-UE3; plan `docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md`
§5.1). Offering: Standard and up (`offerings/standard.toon` `contentPacks`; Advanced and Preview include Standard). The
template declares `minEdition: standard` and `requiresModules: case-management, ops`; the Reconciliation module ships in
Standard.

## How it fits together

```
data/inbox/orders/ORDERS_<yyyyMMdd>.csv      data/inbox/payments/PAYMENTS_<yyyyMMdd>.csv
  -> Pipeline `orders`                          -> Pipeline `payments`
  -> Dataset `orders`                           -> Dataset `payments`
       Expectation orders_amount_non_negative       Expectations payments_amount_non_negative,
                                                    payments_order_known (referential -> orders.order_id)
          \                                        /
           Reconciliation `o2c_orders_vs_payments` (key order_id, sum(amount), tolerance 0, one month a day)
             -> Breaks: missing_right (unpaid), missing_left (orphan payment), value_break, cardinality_break
           Job `o2c_open_items` (sql.template) -> Dataset `o2c_open_items` (one row per order id)
             -> Expectation orders_id_unique (order_copies <= 1)
             -> Alert Rule `o2c_unmatched_value` (sum(unmatched_value) >= 100 by customer_id, order_id; CRITICAL)
                  -> one Incident per order
                  -> Case Rule `o2c_collections_case` (groupBy customer_id) -> one collections Case per customer
             -> Widgets -> Dashboard `o2c_overview`
```

## Parts

| Part | File | Notes |
|---|---|---|
| Pipelines | `config/orders/`, `config/payments/` | poll `data/inbox/<feed>`, CSV, duplicate check, Parquet + snappy; gap detection on the daily orders feed only (payments do not arrive every day) |
| Datasets | `config/registry/datasets/orders.toon`, `payments.toon`, `o2c_open_items.toon` | `orders` and `payments` carry a calculated `period_start` (month of the order / payment date) as their `dateField`, so the Reconciliation reads one month as one "day" |
| Expectations | `config/registry/expectations/` | two `range min 0`, one `referential`, and `orders_id_unique` as `range max 1` on the open items' `order_copies` (there is no `unique` Expectation kind) |
| Reconciliation | `config/registry/reconciliations/o2c_orders_vs_payments.toon` + `config/jobs/o2c_orders_vs_payments_job.toon` | `cardinality: one_to_many` (an order may be paid in instalments); the `recon.run` Job also opens one Incident per run with Breaks (the built-in behaviour) |
| Open items | `config/jobs/o2c_open_items_job.toon` | parameters `as_of` (`$today`) and `terms_days` (14); `status` is one of paid, paid_late, short_paid, over_paid, unpaid, duplicate_order, orphan_payment; `unmatched_value = abs(order_amount - paid_amount)` |
| Alert Rule | `config/registry/alert-rules/o2c_unmatched_value.toon` | CRITICAL, so each key is an Incident; a difference under 100 stays a Break only |
| Case Rule | `config/ops/o2c_collections_caserule.toon` + `config/jobs/o2c_collections_case_job.toon` | `caserule.evaluate`; category `order-to-cash` |
| Collections workflow | `config/governance/case-workflow.json` | adds `propose_write_off` → `PENDING_APPROVAL` → `approve_write_off` / `send_back`; keeps every built-in Case move; an administrator applies it with `POST /components/workflow` (a Space Template carries no governance) |
| SLA examples | `config/examples/sla-escalation-examples.md` | inert, labelled examples of an Incident and a Case SLA Policy and two Escalation Rules (every template ships one) |
| Dashboard | `config/registry/dashboards/o2c_overview.toon` + three Widgets | unmatched value: total (KPI), by status, by customer |
| Samples | `data/samples/orders/`, `data/samples/payments/` | written by `tools/gen-order-to-cash-data.mjs` |

## The synthetic data and its ground truth

`tools/gen-order-to-cash-data.mjs` (seeded, byte-for-byte reproducible) writes ten daily order files (2026-07-01..10, 120
orders, 121 rows) and one payment file per day a payment arrives (123 payments, all in July 2026), and
`tools/gen-order-to-cash-data.ground-truth.json` — the planted defects and what each shipped control must report:

| Planted | Order | What the controls report |
|---|---|---|
| Negative amount | O-1017 (240.00 ordered, a payment keyed -240.00) | `payments_amount_non_negative` 1 violation; value_break; Incident (480.00) |
| Orphan payments | O-9001 (310.00), O-9002 (55.00) | `payments_order_known` 2 violations; missing_left ×2; Incident for O-9001 only |
| Duplicate order | O-1050 (180.00, re-sent the next day) | `orders_id_unique` 1 violation; value_break and cardinality_break; Incident (180.00) |
| Partial payments | O-1022 (300.00 of 500.00), O-1064 (95.00 of 99.50) | value_break ×2; Incident for O-1022 only (4.50 is under the threshold) |
| Missing payments | O-1041 (420.00), O-1088 (135.00) | missing_right ×2; Incident ×2 |
| Late payment | O-1012 (paid in full 15 days after its due date) | no Break; open items status `paid_late`, `days_late` 15 |

Look-alikes that stay clean: O-1030 and O-1075 paid in instalments, O-1099 paid on its due date. Totals: Expectations 0 / 1 / 2 / 1;
8 Breaks (2 missing-left, 2 missing-right, 4 value-break) plus 1 cardinality break; 6 Incidents; 5 collections Cases
(customer C-007 holds O-1022 and O-1041).

Tests: `tools/gen-order-to-cash-data.test.mjs` (determinism and the planted facts, a CI `guards` step),
`OrderToCashGoldenTest` (`features/inspecto-reconciliation`: the Space created through `POST /spaces`, both feeds landed by
their Pipelines, the Jobs run, every Expectation, Break, open-items status and Incident key equal to the ground truth) and
`OrderToCashCaseTest` (`features/inspecto-case-management`: the workflow applied over HTTP, the Incidents grouped into the
Cases, the write-off approval moves).

## Version 2 — the upgrade the tutorials perform

T5 ("Ship it") and T6 ("Run it") upgrade an installed App from version 1 (this template as shipped) to version 2. Version 2 is
written down here, not built, so the tutorials make the change themselves:

| Change | Part |
|---|---|
| A new Expectation `payments_on_time`: `targetType: job`, `target: o2c_open_items`, `column: days_late`, `kind: range`, `max: 0`, severity MINOR — over the samples it reports 1 violation (O-1012) | `config/registry/expectations/payments_on_time.toon` |
| A new Widget `o2c_days_late_by_customer` (bar: `customer_id` × max `days_late`) added as a fourth tile of `o2c_overview` | `config/registry/widgets/`, `config/registry/dashboards/o2c_overview.toon` |
| `template.toon` contents and the App manifest version bumped to 2 | `template.toon`, the App package |

Upgrading carries the Space's data and Incidents over; the new Expectation and Widget appear without re-ingesting.

## Gaps the worked example found

- **No console path from a Space to a Space Template or an App** — on the board as `SPACE-TO-APP-CONSOLE-1`. Only the App
  kit CLI (`tools/inspecto-app.mjs`) packages one; the tutorials (T5) use the CLI.

The rest are candidates for the board, not filed:

- **No four-eyes on a Case transition.** A Workflow transition has no guard (no capability, no "another person than the one
  who proposed"); four-eyes exists only for config changes (the per-kind approval policy, `ASSURE-MAKER-CHECKER-1`). The
  shipped write-off step is a state the team agrees to honour, not an enforced second pair of eyes.
- **No `unique` Expectation kind.** "No duplicate order ids" is a `range max 1` over a Job-written count.
- **A Space Template carries no governance.** The collections workflow ships as a body an administrator applies.
- **A Reconciliation is read one day at a time.** Orders and payments fall on different days, so both Datasets reconcile on
  a calculated month (`period_start`); a payment in the month after its order would show as two Breaks.
