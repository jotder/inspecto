---
type: How-to
title: "Add an Expectation"
description: "State what right data means for a Pipeline's or Job's output — not null, a range, a pattern, a reference — run the check, and see a failure open an Incident."
item: module.elt
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [platform/inspecto-etl, inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java]
---

# Add an Expectation

## Goal

Catch wrong data that is perfectly well-formed — a negative amount, an unknown customer — by adding an
**Expectation** that counts the records breaking it.

## Before you start

- **Edition:** every edition. A failed Expectation opens an Incident where the Incidents Add-on is present (Standard
  and up).
- **Who:** a builder (`canAuthorWorkbench`) to add it; running checks needs `canOperateRuns`.
- **What you need:** data already landed by a Pipeline or a Job. An Expectation is attached to the **Pipeline or Job
  that writes the data**, not to a Dataset by name — for a Dataset made with Add data, that is its import Pipeline,
  which has the Dataset's name.

Concept: [Expectation](user-guide-concept-expectation.md).

## Steps

1. Open **Platform → Workbench → Expectations** and click **New expectation**.
2. **Attach to** Pipeline or Job, and pick the **Target**.
3. Choose the **Check**: **Non-null**, **Range** (a **Minimum** and/or **Maximum**), **Regex** (a **Pattern**),
   **Referential** (a **Reference dataset** and **Reference column** every value must exist in), **Condition
   (advanced)** or **Baseline (vs accepted history)**. Pick the **Column**.
4. Set the **Severity of a failure** (MAJOR by default) and leave **Enabled (evaluated in sweeps)** on.
5. **Continue**, give the **Expectation id**, and **Create**.
6. Click **Run all checks**, or the row's *Run check now*. The **Last result** column shows passed or failed and the
   number of violations.

A failure opens one Incident (severity as set); running again does not open a second one while the first is open.

## Check it worked

Run the new check (here `orders_amount_non_negative`, a range with minimum 0 on the orders Pipeline):

```check
POST /spaces/{space}/expectations/orders_amount_non_negative/evaluate
as builder
expect status 200
expect data.lastResult.status exists
expect data.lastResult.violations >= 0
```

## What can go wrong

- `expectation.range needs at least one of min/max`, `expectation.regex needs a pattern`, `expectation.referential
  needs refDataset and refColumn`, `expectation.column is required` — a field the chosen check needs is empty.
- `expectation evaluation failed: …` — the target has no data yet; run its Pipeline or Job first.
- The Run buttons are missing — you lack `canOperateRuns`.
- A check over a Job's output passes only because the Job has not run yet — run the Job first (see
  [Tutorial 2](user-guide-tutorial-pipeline-expectations.md), step 4).

## Next

- [Handle quarantined rows](user-guide-howto-handle-quarantined-rows.md)
- [Write an Alert Rule](../../addon/incidents/user-guide-howto-write-an-alert-rule.md)
