---
type: How-to
title: "Change a Pipeline safely"
description: "Change a live Pipeline without breaking it: validate, dry-run on sample rows, save through the server's checks, let a second person approve where four-eyes is on, and roll back from history."
item: module.elt
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [platform/inspecto-etl, inspecto-ui/src/app/modules/admin/pipelines, inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java]
---

# Change a Pipeline safely

## Goal

Change a Pipeline that is already landing data — a new column, a stricter type — knowing what it will do before it
runs, with a second pair of eyes where your Space asks for one, and a way back.

## Before you start

- **Edition:** editing, validate, dry run and history in every edition. **Four-eyes approval** needs sign-in, so
  Standard or higher.
- **Who:** a builder (`canAuthorWorkbench`); an approver (`canApproveChanges`) who is **not** the author; an
  administrator turns approval on.
- **What you need:** the Pipeline, and a few sample rows of the data it reads.

## Steps

1. Open **Platform → Workbench → Pipelines**, open the Pipeline and click **Edit**. Make the change.
2. **Validate** in the toolbar — the editor's own checks; findings show in the Validation dock.
3. **Dry-run**, then **Run dry-run** with sample rows: you see what each Step produces, and nothing is written.
   **Run to here** runs real inbox files into a scratch area.
4. **Save pipeline**. The server checks the change again and refuses it with its findings if it would break the
   Pipeline.
5. **If four-eyes is on**, the save is held: it waits in **Operations → Pending Changes** (**Waiting**) with what it
   changes, and nothing is written until a different person clicks **Approve** (or **Decline**; the author may
   **Withdraw**). **Check Pending Changes after saving** — today the editor still says *Saved pipeline* when the
   change was held.
6. **Roll back** if needed: the editor's **⋮** menu, **History…**, restore an earlier version (it is saved as a new
   version and checked like any save).

A Pipeline that is not yet active can be changed and tried freely; **Activate** puts it live.

Turning four-eyes on is an administrator's Control API call, `PUT /settings/approval` with
`{"approval": {"pipeline": {"required": true, "fourEyes": true}}}`; there is no screen for it yet.

## Check it worked

A dry run of the worked example's `orders` Pipeline answers what its sinks would receive, and its history lists the
saved versions:

```check
POST /spaces/{space}/pipelines/authored/orders/dry-run
as builder
body {"sampleRows": [{"ORDER_ID": "O-1", "ORDER_DATE": "2026-07-01", "CUSTOMER_ID": "C-1", "CURRENCY": "USD", "AMOUNT": 10}]}
expect status 200
expect data.sinks exists
```

```check
GET /spaces/{space}/pipelines/orders/history
as builder
expect status 200
expect data.versions exists
```

## What can go wrong

- 422 on save with findings — the server's checks refused the change (a path outside the Space, a bound out of
  range, an error on an active Pipeline); fix what they name.
- 409 `a change to pipeline '<id>' is already pending approval (<id>) — it must be approved, declined, withdrawn or
  expire before another is proposed`.
- A dry run refused with a binder error — the sample rows must carry every
  column of the Pipeline's Schema (for `orders`: `ORDER_ID`, `ORDER_DATE`, `CUSTOMER_ID`, `CURRENCY`, `AMOUNT`).
- 403 *four-eyes: '<author>' proposed this change and cannot approve it — a different person must*.
- 409 on **Approve** — the Pipeline changed after the request was made; make the change again.
- If the approval settings cannot be read, every governed change is held (fail closed).

## Next

- [Handle quarantined rows](user-guide-howto-handle-quarantined-rows.md)
- [Build a Pipeline from a sample file](user-guide-howto-pipeline-from-a-sample.md)
