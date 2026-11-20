---
type: Tutorial
title: "Lab 2 — Exposure per customer: an Alert Rule of your own, and the first Incident worked"
description: "A Lab after Tutorial 3: on the Order-to-cash Space, write an Alert Rule that raises one Incident per customer whose total unmatched value is 300 or more, find which customers it names, accept the largest one, and let the checker grade the result through the Control API."
item: addon.incidents
audience: training
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto/src/main/java/com/gamma/control/AlertRoutes.java, features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java]
---

# Lab 2 — Exposure per customer

In [Tutorial 3](user-guide-tutorial-detect-respond.md) the Alert Rule `o2c_unmatched_value` raised one Incident per
**order** whose unmatched value is 100 or more. Credit control asks a different question: **which customers owe us —
or are owed — 300 or more in total?** You answer it with an Alert Rule of your own, then take the first Incident into
work. This page says *what* to build; the checker at the end reads your Space through the Control API.

**Time:** about 25 minutes. **Learning Path:** builder, stage *Build* (after Tutorial 3).

## Before you start

- **Edition:** Standard, Advanced or Preview — Alert Rules and Incidents start in Standard. Labs are training material,
  kept out of a sold edition's Help (decision D-UE7); render them with
  `node tools/render-handbook.mjs --out <dir> --training`.
- **Who:** a builder who may write Alert Rules (`canAuthorAlertRules` — the seed `power` Role has it, `admin` alone does
  not), run them (`canOperateRuns`) and work Incidents (`canWorkIncidents`).
- **Starting state:** the `order-to-cash` Space with the **o2c_open_items** Job run once
  ([Tutorial 2](../../module/elt/user-guide-tutorial-pipeline-expectations.md), step 4). Whether you did Tutorial 3 or
  not does not matter: the checker counts only the Incidents your rule raises.
- How to write an Alert Rule, field by field: [Write an Alert Rule](user-guide-howto-write-an-alert-rule.md).

## The brief

| | |
|---|---|
| **Rule id** | `lab_customer_exposure` — the checker finds your rule, and its Incidents, by this id |
| **Watch** | the open items (Dataset **o2c_open_items**), over their current data |
| **Measure** | the total unmatched value |
| **Fires when** | the total is **300 or more** |
| **One Incident per** | customer |
| **Severity** | high enough that every firing opens an Incident |

Choose the Measure, the Comparator, the Severity and the **Incident per** column yourself.

## Step 1 — Write the rule and evaluate it

Open **Operations → Alerts**, write the rule, and click **Evaluate now**.

## Step 2 — Read what it found

Open **Operations → Incidents** and find the Incidents your rule opened. Answer:

1. Which customers does it name, and with what total each?
2. Customer **C-007** had **two** Incidents in Tutorial 3. How many does it have from your rule, and why?
3. Customer **C-010** had an Incident in Tutorial 3. Why has your rule none for it?
4. One of the customers is named only because of a payment for an order that does not exist. Which one?

## Step 3 — Take the largest exposure into work

Open the Incident of the customer with the **largest** total and click **Accept**. If the Incident has no category
yet, the console asks for one first — pick any. An accepted Incident is assigned to you and moves from *Identified*
to *Diagnosing*.

## Check your work

Run the checker against your Space:

```
node tools/run-handbook-checks.mjs --base-url <your server> --page lab_exposure_per_customer --space <your Space id>
```

It reads your rule, the Incidents it opened — found by the words every one of them carries, *at or above the
threshold of 300* — and the one you accepted. Every step must pass.

```check
GET /spaces/{space}/alerts/rules
as builder
expect status 200
expect data[*].name has lab_customer_exposure
```

```check
GET /spaces/{space}/objects?type=INCIDENT&q=threshold%20of%20300&limit=100
as builder
expect status 200
expect data.length = 3
expect data[*].severity has CRITICAL
expect data[*].attributes.rule has lab_customer_exposure
expect data[*].attributes.alertKey has "lab_customer_exposure|customer_id=C-007"
expect data[*].attributes.alertKey has "lab_customer_exposure|customer_id=C-011"
expect data[*].attributes.alertKey has "lab_customer_exposure|customer_id=C-016"
```

```check
GET /spaces/{space}/objects?type=INCIDENT&q=threshold%20of%20300&status=DIAGNOSING&limit=100
as builder
expect status 200
expect data.length = 1
expect data[0].attributes.alertKey = "lab_customer_exposure|customer_id=C-007"
expect data[0].assignee exists
```

A step that fails names what it found instead. None found: the threshold is not 300, the rule is not Critical, or
the open items were never built. The right number but the wrong keys: **Incident per** is empty or names more than
the customer. Nothing in *Diagnosing*: the Incident is not accepted yet.

## What you learned

- The same Dataset answers different questions depending on the **Measure** and the **Incident per** key: per order
  in Tutorial 3, per customer here.
- Only a Critical Alert opens an Incident, and evaluating again does not open a second one while the first is open.
- **Accept** is the first step of working an Incident: it gets an owner and moves to *Diagnosing*.

## Next

- [Open and work a Case](../../module/case-management/user-guide-howto-open-and-work-a-case.md)
- [Tutorial 4 — Present it](../../layer/product/user-guide-tutorial-dashboard.md)
