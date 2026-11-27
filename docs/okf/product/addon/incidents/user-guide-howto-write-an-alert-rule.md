---
type: How-to
title: "Write an Alert Rule"
description: "Watch a Measure of a Dataset — a count or an aggregate, optionally per key — against a threshold, fire Alerts, and open Incidents for the critical ones."
item: addon.incidents
audience: external
status: current
verified: 2026-11-27 code-reviewed
code: [features/inspecto-ops, inspecto/src/main/java/com/gamma/control/AlertRoutes.java]
---

# Write an Alert Rule

## Goal

Be told when a number crosses a line — unmatched value above 1,000, more than 30 failed logins per account — with an
**Alert**, and for critical ones an **Incident** someone has to work.

## Before you start

- **Edition:** Standard, Advanced or Preview. On Basic the save is refused as a Standard+ feature.
- **Who:** `canAuthorAlertRules` to write one (the seed `power` Role has it; `admin` alone does not); **Evaluate now**
  needs `canOperateRuns`; acknowledging and resolving Incidents needs `canWorkIncidents`.
- **What you need:** a Dataset with the column you want to measure.

Concept: [Alert Rule, Expectation and Decision Rule](user-guide-concept-alert-rule.md) ·
[Alert, Incident and Case](user-guide-concept-alert-incident-case.md).

## Steps

1. Open **Operations → Alerts** and, under **Alert Rules**, click **New Alert Rule**.
2. **Watch**: *A Dataset Measure (over its current data)* — or *A Pipeline's processing Metric* to watch a Pipeline
   itself.
3. Pick the **Dataset** and the **Measure**: `count`, or an aggregate of a column such as `sum(unmatched_value)`.
4. Pick the **Comparator** (> ≥ < ≤), the **Threshold** (a positive number) and the **Severity**: Info, Warning or
   Critical. **Only a Critical Alert opens an Incident**; Info and Warning fire Alerts only.
5. Optionally: **Incident per** (one Incident per value of a key, such as per customer), **Storm cap**, **Heal after
   sweeps**, a **Description** and a **Runbook**.
6. **Continue**, give the **Rule id**, and **Create rule**.
7. Click **Evaluate now**. Fired Alerts appear at the top of the page; critical ones open Incidents in
   **Operations → Incidents**. Evaluating again does not open a second Incident while the first is open.

## Count only some rows

"Alert when more than 5 shipments are undelivered" is one rule: a `count` Measure with a **row filter**, `when` — a
condition group over the Dataset's own columns. The Measure then counts only the rows that match:

```json
{"name": "undelivered-shipments", "dataset": "shipments", "measure": "count", "comparator": "gt", "threshold": 5,
 "severity": "WARNING",
 "when": {"kind": "group", "op": "AND",
          "items": [{"kind": "condition", "field": "delivered_on", "operator": "isNull"}]}}
```

Operators: `= != < <= > >= between in contains startsWith endsWith matches isNull isNotNull`; groups nest, with `op`
`AND` or `OR`. It works with **Incident per** too (the filter applies before the grouping). Today you write the filter
through the API (`POST /alerts/rules`) or an agent; the rule form keeps a stored filter when you edit the rule but
cannot edit it yet. "Every row must satisfy X" is an [Expectation](user-guide-concept-alert-rule.md), not this.

## Check it worked

The rule is listed, and a sweep fires it — here the worked example's `o2c_unmatched_value`, which opens one critical
Incident per unpaid order:

```check
GET /spaces/{space}/alerts/rules
as builder
expect status 200
expect data[*].name has o2c_unmatched_value
```

```check
POST /spaces/{space}/alerts/evaluate
as builder
expect status 200
```

```check
GET /spaces/{space}/objects?type=INCIDENT&severity=CRITICAL&limit=50
as builder
expect status 200
expect data.length > 0
```

## What can go wrong

- `alert.threshold must be a positive number`, or `alert.measure must be count or agg(field)…` — fix the Measure or
  the Threshold.
- 422 for an **Incident per** column that is not in the Dataset's Schema.
- 422 `ERR_ALERT_ROW_FILTER_INVALID` — the row filter has an incomplete condition or an unknown operator, names a
  column the Dataset does not have, or filters on a masked column (an Alert never reveals a masked value, so it may
  not count rows by one). Another Dataset's column needs a derived Dataset first.
- `alert engine not armed (no alert-rule components loaded)` from **Evaluate now** — no rule is saved yet.
- A Warning rule fires but no Incident appears — only Critical opens Incidents.
- 403 on save — you lack `canAuthorAlertRules`; ask for the `power` Role.

## Next

- [Open and work a Case](../../module/case-management/user-guide-howto-open-and-work-a-case.md)
- [Detect in real time on a stream](../../module/realtime/user-guide-howto-detect-in-real-time.md)
