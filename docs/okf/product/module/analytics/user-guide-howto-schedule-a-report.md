---
type: How-to
title: "Schedule a report"
description: "Export a Dataset as CSV or Excel on a schedule, into the Space's exports folder, and tell recipients by e-mail where it is."
item: module.analytics
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [platform/inspecto-analytics, inspecto-ui/src/app/modules/admin/kpi-reports]
---

# Schedule a report

## Goal

Have a Dataset exported every morning as a CSV or Excel file, and its recipients told where to find it.

## Before you start

- **Edition:** every edition for the export. The e-mail needs an e-mail channel set up (Standard and up).
- **Who:** a builder (`canAuthorWorkbench`) to schedule it; `canOperateRuns` to run it now.
- **What you need:** the Dataset to export.
- **What a scheduled report is today:** an export of **one Dataset**, as CSV or an Excel workbook, cut at 10,000 rows.
  A Dashboard cannot be scheduled, and the e-mail carries the file's location, not the file.

## Steps

1. Open **Business → KPI & Reports**, section **Scheduled Dataset exports**, and click **Schedule Dataset export**.
2. Fill the **Schedule id**, the **Dataset**, the **Export format** (CSV or Excel workbook) and the **Trigger**:
   **Cron schedule** with its **Cron expression** (`0 0 6 * * *` is 06:00 every day), or **Manual only**.
3. Optionally list **Recipients (comma-separated)**. Leave **Enabled (armed)** on and click **Schedule**.
4. On its row: *Run now*, *Download latest export*, *Edit schedule*, *Delete schedule*. Each run writes its file to the
   Space's `exports/<schedule id>` folder.

## Check it worked

Run the schedule now and read the file it wrote (here a schedule `daily_orders`):

```check
POST /spaces/{space}/jobs/daily_orders/trigger
as builder
expect status 200|202
save data.runId as exportRun
```

```check
GET /spaces/{space}/jobs/runs/{exportRun}
as builder
expect data.status = "SUCCESS"
retry 30 every 2s
```

```check
GET /spaces/{space}/jobs/daily_orders/artifacts/latest
as builder
expect status 200
expect data[0].kind = "file"
```

## What can go wrong

- The run succeeds but says *not mailed (no email channel configured)* or *not mailed (no mail service granted to this
  Run)* — an operator sets up the e-mail channel; the file is still written.
- `recipients needs out_dir: only a delivered report can be mailed`.
- An old schedule of a Dashboard fails with *Dashboard export is not supported — recreate this schedule for a
  Dataset*.
- The export stops at 10,000 rows — filter the Dataset or a Query first.
- *Writes are disabled* — the server has no write root.

## Next

- [Build a Dashboard](../../layer/product/user-guide-howto-build-a-dashboard.md)
