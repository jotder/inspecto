---
type: Tutorial
title: "Lab 1 — Trust the feeds: write three Expectations of your own"
description: "A Lab after Tutorial 2: on the Order-to-cash Space, add three Expectations of your own — a non-null, a regex and a range check — run them, and let the checker grade the result through the Control API."
item: module.elt
audience: training
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java, inspecto/src/main/java/com/gamma/expectation/ExpectationEvaluator.java]
---

# Lab 1 — Trust the feeds

A **Lab** is an exercise with a checker: this page tells you *what* to build, not *how*, and the checker reads your
Space through the Control API and says whether you built it. In
[Tutorial 2](user-guide-tutorial-pipeline-expectations.md) you ran the four Expectations the worked example ships.
Here you write three of your own on the same feeds.

**Time:** about 25 minutes. **Learning Path:** builder, stage *Build* (after Tutorial 2).

## Before you start

- **Edition:** Standard, Advanced or Preview (the `order-to-cash` Space Template needs Standard). Labs ship in the
  Preview and the training bundle (decision D-UE7).
- **Who:** a builder who may author Expectations and run checks (`canAuthorWorkbench` and `canOperateRuns`) — the user
  who created the Space in Tutorial 1.
- **Starting state:** a Space created from the **Order-to-cash assurance** Space Template with its sample files
  loaded ([Tutorial 1](../config/user-guide-tutorial-first-space.md), step 1). A Space you already worked through
  Tutorials 2 and 3 is fine: the checker reads only what this Lab asks you to add.
- How to add an Expectation, field by field: [Add an Expectation](user-guide-howto-add-an-expectation.md).

## The brief

The finance team wants three more guarantees on the two feeds before anyone trusts a report built on them. Write
each one as an Expectation, with exactly the **Expectation id** given — the checker finds your work by that id.

| Expectation id | Attach to | What it guarantees |
|---|---|---|
| `lab_orders_customer_present` | the **orders** Pipeline | every order names the customer it is invoiced to |
| `lab_payments_method_known` | the **payments** Pipeline | every payment method is one of `card`, `transfer` or `cheque` — nothing else |
| `lab_orders_over_800` | the **orders** Pipeline | no order is above 800 (the credit limit for an order without a credit check) |

Pick the **Check** that fits each guarantee yourself: one is a non-null check, one a pattern, one a range. Leave the
**Severity of a failure** at MAJOR and **Enabled** on.

## Step 1 — Write the three Expectations

Open **Platform → Workbench → Expectations** and add the three. The column names are the ones the Data Browser shows
for the orders and payments Datasets (`customer_id`, `method`, `amount`).

## Step 2 — Run them and read the result

Click **Run all checks**. Then answer, from the **Last result** column:

1. Which of your three Expectations passed, and which failed?
2. How many orders break `lab_orders_over_800`?
3. A failed Expectation opened something in **Operations → Incidents**. What, and at which severity?

Write your answers down; your instructor (or the checker below) tells you whether they hold.

## Check your work

Run the checker against your Space (the persona `builder` is the user who did the Lab):

```
node tools/run-handbook-checks.mjs --base-url <your server> --page lab_trust_the_feeds --space <your Space id>
```

It runs each of your Expectations once more and reads it back — what it is attached to, its check, its column and its
result. Every step must pass.

```check
POST /spaces/{space}/expectations/lab_orders_customer_present/evaluate
as builder
expect status 200
expect data.targetType = "pipeline"
expect data.target = "orders"
expect data.kind = "non_null"
expect data.column = "customer_id"
expect data.lastResult.status = "PASSED"
```

```check
POST /spaces/{space}/expectations/lab_payments_method_known/evaluate
as builder
expect status 200
expect data.targetType = "pipeline"
expect data.target = "payments"
expect data.kind = "regex"
expect data.column = "method"
expect data.lastResult.status = "PASSED"
expect data.lastResult.violations = 0
```

```check
POST /spaces/{space}/expectations/lab_orders_over_800/evaluate
as builder
expect status 200
expect data.targetType = "pipeline"
expect data.target = "orders"
expect data.kind = "range"
expect data.column = "amount"
expect data.max = 800
expect data.lastResult.status = "FAILED"
expect data.lastResult.violations = 16
```

A step that fails names the expectation it could not meet and what it found instead — for example
`data.kind = "range"` found `"regex"`: you picked the wrong check.

## What you learned

- An Expectation is attached to the **Pipeline that writes the data**, and names one column.
- A pattern check must say what *all* of the value looks like, or a value that merely contains a good word passes.
- A business limit is a data-quality check too: it fails without anything being malformed, and the failure becomes an
  **Incident** someone has to work.

## Next

- [Tutorial 3 — Detect and respond](../../addon/incidents/user-guide-tutorial-detect-respond.md), then
  [Lab 2 — Exposure per customer](../../addon/incidents/user-guide-lab-exposure-per-customer.md).
- [Handle quarantined rows](user-guide-howto-handle-quarantined-rows.md)
