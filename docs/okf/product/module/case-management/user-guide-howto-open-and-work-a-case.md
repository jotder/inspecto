---
type: How-to
title: "Open and work a Case"
description: "Open a Case — from Incidents by a Case Rule, or by hand linked to an Alert, Incident or Case — then move it through its workflow, record findings and comments, and close it."
item: module.case-management
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [features/inspecto-case-management, features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java]
---

# Open and work a Case

## Goal

Turn one or more Incidents into a **Case** — a piece of work with an owner, a state, findings and a history — and take
it from open to closed.

## Before you start

- **Edition:** Standard, Advanced or Preview (Case Management with the Incidents Add-on).
- **Who:** opening a Case needs `canManageIncidents`; moving it through its workflow needs `canWorkIncidents`.
- **What you need:** at least one Alert, Incident or Case to link — every Case links to one.

Concept: [Alert, Incident and Case](../../addon/incidents/user-guide-concept-alert-incident-case.md).

## Steps

### Open it

- **From Incidents, by a Case Rule** — in **Operations → Case Manager**, the **Case rules** button opens the rules
  that group Incidents into Cases: **Rule name**, the **Raised case title**, a **Threshold** of Incidents within a
  **Window (min)**, **Group by key**, **Priority** and filters. **Save & evaluate** opens the Cases now.
- **By hand** — in **Operations → Case Manager**, click **New case**: **Title**, **Description**, **Severity**,
  **Priority**, **Assignee**, **Tags**, the **Linked entities** (at least one Alert, Incident or Case) and an SLA in
  minutes, then **Create**.

### Work it

1. The Case Manager's folders are the workflow's states: *My Cases*, *Open*, *Investigating*, *Escalated*,
   *Resolved*, *Closed*. Click a Case.
2. Move it with the action buttons in its panel — **Investigate**, **Escalate**, **Resolve**, **Close**; only the moves
   its workflow allows from the current state are offered.
3. Write what you found and **Save findings**. On the Case's own page, the **Comments** tab takes comments
   (**Add a comment**, **Add**).
4. Close it. If it still has open member Incidents, or no findings recorded, the console asks you to confirm.

## Check it worked

Take an open Case into investigation — the same step as **Investigate** — and see its new state:

```check
GET /spaces/{space}/objects?type=CASE&status=OPEN&limit=1
as builder
expect status 200
save data[0].id as case
```

```check
POST /spaces/{space}/objects/{case}/transition
as builder
body {"action": "investigate"}
expect status 200
expect data.status = "INVESTIGATING"
```

## What can go wrong

- *Link at least one existing entity.* — every Case links to an Alert, Incident or Case; with nothing to link, the
  dialog says so.
- 422 on a move — the workflow does not allow it from the current state; the legal moves are in the Space's Case
  workflow.
- The buttons are missing — you lack `canWorkIncidents` (or `canManageIncidents` for **New case**).
- **An existing Case cannot be re-assigned in the console**: the **Assignee** is set when the Case is opened. To
  change it later, use `POST /objects/<case>/assign` in the Control API.
- There is no *Create case* button on an Incident: Incidents become Cases through a Case Rule.

## Next

- [Tutorial 3 — detect and respond](../../addon/incidents/user-guide-tutorial-detect-respond.md) — a collections Case
  with a four-eyes write-off.
- [Build a Dashboard](../../layer/product/user-guide-howto-build-a-dashboard.md)
