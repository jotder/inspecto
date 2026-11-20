---
type: How-to
title: "Handle quarantined rows"
description: "Find the files and rows a Pipeline set aside, read why, and get them in — replay rejected rows after a fix, or repair a quarantined file and drop it back in the inbox."
item: module.elt
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [platform/inspecto-etl, inspecto/src/main/java/com/gamma/control/RunRoutes.java]
---

# Handle quarantined rows

## Goal

When a Pipeline refuses rows or whole files, find them, see why, and get the good data in without loading anything
twice.

## Before you start

- **Edition:** every edition.
- **Who:** anyone may look; replaying rows or reprocessing a batch needs `canOperateRuns`.
- **What you need:** a Pipeline that has run. **Rejected rows** are rows inside a file that did not fit the Schema; a
  **quarantined file** was set aside whole (for example unreadable, empty, or a column count that does not match).

## Steps

1. Open **Platform → Workbench → Runs**, find the Pipeline's row and click **Open detail**.
2. **Files** tab: each file with its status — `SUCCESS` or `QUARANTINED_…` — and its parsed and rejected row counts.
   On a file with rejected rows, **View the rejected rows** shows each row and the reason.
3. **Quarantine** tab: each quarantined file with its reason (such as `field_mismatch`, `unreadable`, `empty`) and
   where it lies on the server.
4. Get the data in:
   - **Rejected rows** — fix the cause (usually the Schema: a type or format), then in the rejected-rows dialog click
     **Replay rejected records**. They are read again from the original file into a new Consignment; **Open
     Consignment N** shows it.
   - **A quarantined file** — repair the file and move it back into the Pipeline's inbox; the next poll takes it.
   - **A whole batch** — on the **Batches** tab, **Reprocess this batch**.
5. For a Dataset made with Add data, the Dataset's page in **Platform → Catalog → Datasets** also shows what was set
   aside.

## Check it worked

The Pipeline's quarantine list is empty again and its files read `SUCCESS` (here the Pipeline `payments`):

```check
GET /spaces/{space}/runs/payments/quarantine
as builder
expect status 200
expect data.length = 0
```

```check
GET /spaces/{space}/runs/payments/files
as builder
expect status 200
```

## What can go wrong

- Replay answers *already replayed*, or that the file changed since (409), or that it is gone (410) — the original
  file is needed as it was.
- Replay is refused (422) for a file that is not CSV, or whose rejected rows were cut at the recorded limit.
- `no rejected-row detail recorded for '<file>'` — nothing was recorded for that file; open the file itself.
- The **Quarantine** tab lists files for the Pipeline as a whole, not per run, and has no button to release a file —
  the remedy is to repair the file and put it back in the inbox.

## Next

- [Add an Expectation](user-guide-howto-add-an-expectation.md)
- [Tutorial 2 — make it a Pipeline with Expectations](user-guide-tutorial-pipeline-expectations.md)
