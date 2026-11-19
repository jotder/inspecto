---
type: Tutorial
title: "Tutorial 3 — Detect and respond: Reconciliation, Alert Rule, Incidents and a Case"
description: "Reconcile the Order-to-cash orders against their payments, read the Breaks, let an Alert Rule raise one Incident per order that matters, group them into collections Cases, and take one Case through a write-off approval."
item: addon.incidents
audience: external
---

# Tutorial 3 — Detect and respond

Expectations told you that some records are wrong. Now you ask the business question: **for every order, was it paid
— in full?** A **Reconciliation** compares the two feeds order by order and lists each difference as a **Break**. An
**Alert Rule** raises an **Incident** for every order whose difference is large enough to chase, a **Case Rule**
groups one customer's Incidents into a collections **Case**, and you take one Case through a write-off that someone
approves.

**Time:** about 30 minutes.

## Before you start

- **Edition:** Standard, Advanced or Preview — Reconciliation, Incidents and Cases start in Standard.
- **Who:** the builder who created the Space. Step 5 changes the Case workflow, which needs an administrator.
- **What you need:** the `order-to-cash` Space, with the **o2c_open_items** Job run once
  ([Tutorial 2](../../module/elt/user-guide-tutorial-pipeline-expectations.md), step 4).

Concepts used here: [Alert Rule, Expectation and Decision Rule](user-guide-concept-alert-rule.md) ·
[Alert, Incident and Case](user-guide-concept-alert-incident-case.md).

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
retry 30 every 2s
```

## Step 1 — Reconcile orders against payments

1. Open **Business → Reconciliation**. One Reconciliation is listed: **o2c_orders_vs_payments**, **orders** on the
   left, **payments** on the right, keyed on **order_id**.
2. Open it. **Day** shows **2026-07-01**: this Reconciliation reads a whole month as one day, because an order and
   its payments fall on different days.

The board compares each order's amount with the sum of the payments made against it, at a tolerance of zero. The
summary line for the day reads:

| | |
|---|---|
| groups | **122** |
| matched | **118** |
| only in A (an order nobody paid) | **2** — O-1041 (420.00) and O-1088 (135.00) |
| only in B (a payment for no order) | **2** — O-9001 (310.00) and O-9002 (55.00) |
| value breaks | **4** — O-1017 (240 ordered, -240 paid), O-1022 (500 ordered, 300 paid), O-1050 (360 ordered, 180 paid), O-1064 (99.50 ordered, 95.00 paid) |
| duplicate keys | **1** — O-1050: two order rows for one order id |

That is **8 Breaks**, plus the duplicate. **Open breaks by age** counts **9** in *0-30 days*: the eight Breaks and
the duplicate. Order O-1050 shows a value break only because it was counted twice; order O-1030, paid in
instalments, matches — the payments add up.

```check
POST /spaces/{space}/recon/breaks
as builder
body {"id": "o2c_orders_vs_payments", "day": "2026-07-01"}
expect status 200
expect data.missing_right.rowCount = 2
expect data.missing_left.rowCount = 2
expect data.value_break.rowCount = 4
expect data.cardinality_break.rowCount = 1
```

3. To record the run, open **Platform → Workbench → Jobs**, click **o2c_orders_vs_payments**, then
   **Run with parameters…** and **Run**. In its **Execution history**, the run's message reads *8 break(s) (2 missing-left, 2 missing-right, 4 value-break)*, and because it found Breaks
   it opens one **Incident** for the run (severity WARNING).

```check
POST /spaces/{space}/jobs/o2c_orders_vs_payments/trigger
as builder
expect status 200|202
save data.runId as reconRun
```

```check
GET /spaces/{space}/jobs/runs/{reconRun}
as builder
expect data.status = "SUCCESS"
expect data.message matches /8 break\(s\) \(2 missing-left, 2 missing-right, 4 value-break\)/
retry 30 every 2s
```

## Step 2 — Raise Incidents on the orders that matter

Not every Break is worth chasing. The Alert Rule **o2c_unmatched_value** reads the open items and fires for every
order whose **unmatched value is 100 or more**, at severity **CRITICAL** — one Incident per order.

1. Open **Operations → Alerts**. The rule is listed under **Alert Rules**.
2. Click **Evaluate now**. One Alert appears: *CRITICAL: 6 keys (by customer_id, order_id) breach*.

```check
POST /spaces/{space}/alerts/evaluate
as builder
expect status 200
```

3. Open **Operations → Incidents**.

**What you should see:** six CRITICAL Incidents, one per order: **O-1017** (480.00), **O-1022** (200.00), **O-1041**
(420.00), **O-1050** (180.00), **O-1088** (135.00) and **O-9001** (310.00). Each names its customer and order.

Two Breaks raised nothing: **O-1064** is short by only 4.50, and the orphan payment **O-9002** is 55.00 — both under
the threshold of 100. They stay on the Reconciliation board.

The Incidents list also holds the Incident of the Reconciliation run (WARNING) and, if you did Tutorial 2, one MAJOR
Incident per failed Expectation — **10** in the *Identified* folder in all.

```check
GET /spaces/{space}/objects?type=INCIDENT&severity=CRITICAL&limit=50
as builder
expect status 200
expect data.length = 6
expect data[*].attributes.alertKey has "o2c_unmatched_value|customer_id=C-016,order_id=O-1017"
expect data[*].attributes.alertKey has "o2c_unmatched_value|customer_id=C-011,order_id=O-9001"
```

## Step 3 — Group them into collections Cases

The Case Rule **o2c_collections_case** groups the Incidents of one customer into one **collections Case**.

1. Open **Platform → Workbench → Jobs**, click **o2c_collections_case**, then **Run with parameters…** and **Run**.
2. Open **Operations → Case Manager**.

**What you should see:** **5** Cases in the *Open* folder, all tagged **collections**, category **order-to-cash** —
one per customer. Customer **C-007** owns two of the unmatched orders, O-1022 and O-1041, so its Case holds two
Incidents; each of the other four holds one. Open a Case: its **Contents** list the Incidents it holds, and each
Incident's title names the customer and the order.

```check
POST /spaces/{space}/jobs/o2c_collections_case/trigger
as builder
expect status 200|202
save data.runId as caseRun
```

```check
GET /spaces/{space}/jobs/runs/{caseRun}
as builder
expect data.status = "SUCCESS"
retry 30 every 2s
```

```check
GET /spaces/{space}/objects?type=CASE&limit=50
as builder
expect status 200
expect data.length = 5
expect data[*].attributes.category has order-to-cash
```

## Step 4 — Add a write-off approval to the Case workflow

A collections Case ends either with the money collected or with the debt written off — and a write-off should be
approved. The built-in Case workflow has no approval step, so you add one. This step needs an administrator.

1. Open **Settings → Incident governance**.
2. In **Object type**, choose **CASE**. The **Workflow** section shows the Case's states and transitions
   (OPEN → INVESTIGATING → RESOLVED → CLOSED, and ESCALATED).
3. Under the list of states, type `PENDING_APPROVAL` in **New state** and click **Add state**. Do the same for
   `WRITE_OFF_APPROVED`.
4. Add four transitions. For each, choose the two states in **Connect from** and **to**, click **Connect states**,
   then type the action in the new row of **Transitions**:

| From | Action | To |
|---|---|---|
| INVESTIGATING | `propose_write_off` | PENDING_APPROVAL |
| PENDING_APPROVAL | `send_back` | INVESTIGATING |
| PENDING_APPROVAL | `approve_write_off` | WRITE_OFF_APPROVED |
| WRITE_OFF_APPROVED | `close` | CLOSED |

5. Click **Save workflow**. The console confirms *Workflow saved*. The change applies to the next move of any Case —
   no restart. Case Manager now has the folders *Pending approval* and *Write off approved*.

```check
POST /spaces/{space}/components/workflow
as builder
body {"id": "case", "objectType": "CASE", "initial": "OPEN", "terminal": ["CLOSED"], "transitions": [
  {"from": "OPEN", "to": "INVESTIGATING", "action": "investigate"},
  {"from": "INVESTIGATING", "to": "ESCALATED", "action": "escalate"},
  {"from": "INVESTIGATING", "to": "RESOLVED", "action": "resolve"},
  {"from": "ESCALATED", "to": "RESOLVED", "action": "resolve"},
  {"from": "RESOLVED", "to": "CLOSED", "action": "close"},
  {"from": "INVESTIGATING", "to": "PENDING_APPROVAL", "action": "propose_write_off"},
  {"from": "PENDING_APPROVAL", "to": "INVESTIGATING", "action": "send_back"},
  {"from": "PENDING_APPROVAL", "to": "WRITE_OFF_APPROVED", "action": "approve_write_off"},
  {"from": "WRITE_OFF_APPROVED", "to": "CLOSED", "action": "close"}]}
expect status 200|409
```

```check
GET /spaces/{space}/workflows/case
as builder
expect status 200
expect data.states has PENDING_APPROVAL
expect data.transitions[*].action has approve_write_off
```

## Step 5 — Work a Case to a written-off close

1. In **Operations → Case Manager**, open a Case from the *Open* folder.
2. Click **Investigate**. The Case moves to **INVESTIGATING** and offers **Escalate**, **Propose write off** and
   **Resolve**.
3. Record what you found in **Findings** — for example a summary of why the money will not be collected — and click
   **Save findings**.
4. Click **Propose write off**. The Case moves to **PENDING_APPROVAL** and offers **Approve write off** and
   **Send back**.
5. The approver opens the Case and clicks **Approve write off** (or **Send back** to return it to INVESTIGATING). The
   Case moves to **WRITE_OFF_APPROVED**.
6. Click **Close**. While Incidents in the Case are still open, the console asks *This case still has 1 open member
   incident — close anyway?*. Resolve the Incidents first, or click **OK** to close the Case and leave them open.

**Agree who approves.** The product does not stop the person who proposed a write-off from approving it. Make it a
team rule that a second person approves, and give that person a role in the Space.

```check
GET /spaces/{space}/objects?type=CASE&status=OPEN&limit=1
as builder
expect status 200
expect data.length = 1
save data[0].id as case
```

```check
POST /spaces/{space}/objects/{case}/transition
as builder
body {"action": "investigate"}
expect status 200
expect data.status = "INVESTIGATING"
```

```check
POST /spaces/{space}/objects/{case}/transition
as builder
body {"action": "propose_write_off"}
expect status 200
expect data.status = "PENDING_APPROVAL"
```

```check
POST /spaces/{space}/objects/{case}/transition
as builder
body {"action": "approve_write_off"}
expect status 200
expect data.status = "WRITE_OFF_APPROVED"
```

```check
POST /spaces/{space}/objects/{case}/transition
as builder
body {"action": "close"}
expect status 200
expect data.status = "CLOSED"
```

## What you learned

- A **Reconciliation** compares two Datasets key by key and lists every difference as a **Break**: missing on one
  side, missing on the other, or different values.
- An **Alert Rule** turns the differences that matter into **Incidents**; the rest stay Breaks.
- A **Case Rule** groups related Incidents into a **Case**, and a **Workflow** decides the moves a Case can make —
  including an approval step you add yourself.

## Next

- [Tutorial 4 — Present it: a Dashboard for the business user](../../layer/product/user-guide-tutorial-dashboard.md)
- Concepts: [Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)
