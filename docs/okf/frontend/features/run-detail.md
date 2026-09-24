---
type: Feature
title: Run Detail
description: One running Pipeline's batches, files, lineage, quarantine, commits, and report tabs, with a batch-detail dialog. Carries a breadcrumb.
resource: inspecto-ui/src/app/modules/admin/run-detail/run-detail.routes.ts
tags: [feature, runs, detail, breadcrumb]
timestamp: 2026-09-25T00:00:00Z
---

# Run Detail

Route `/runs/:name` (carries a list→name [breadcrumb](../conventions/routing-and-navigation.md)), dir
`modules/admin/run-detail/`. Drills into one running Pipeline across tabs — batches / files / lineage
(filterable by batch id) / quarantine / commits / report — in **standard**
[data-tables](../design-system/data-table.md); a row opens the **batch-detail dialog** (mini/single-select
grids inside). Backed by `RunsService`.

**Registered outputs on the batch-detail dialog (shipped 2026-08-29).** `GET /runs/{name}/outputs?consignmentId=`
returns one Consignment's registered outputs — sync's own files **and** whatever a post-sync step derived
onto it — so the post-sync lane, which was real and invisible in the UI, is finally visible with each row
attributed to the step that wrote it (`producer`).

🔴 **`enabled` is not decoration, and off must not render like empty.** The output registry is switchable
(`-Dconsignment.outputs.backend=none`); an empty table with it OFF would read as *"this Consignment wrote
nothing"*, which is false — the **manifest**, not the registry, is authoritative for a file's existence.
Registry-off renders an `<inspecto-alert>` explaining it; genuinely-empty renders the table's own empty
state. Pinned by three specs.

⚠ **Fetched OUTSIDE the dialog's core `forkJoin`.** A registry that is off, or a backend too old to serve
the route, must degrade to an explanation — not blank a dialog that already has its summary, members and
lineage.

**Quarantine remediation (D-ETL, shipped 2026-07-20):** the Quarantine tab lists `GET /runs/{name}/quarantine`
rows via the same generic audit-row grid. ⚠ **Corrected 2026-08-13**: the server's `FileStatusStore.quarantine`
*synthesizes* these rows off the on-disk `<reason>/<filename>` layout, so each row carries only `file, reason,
path, size_bytes` — **no batch/consignment id, no timestamp**. **Lineage & details** and **Reprocess this
batch** therefore hide on Quarantine rows (`visible: (r) => !!r['consignment_id']`); only **View the rejected
rows** is offered there, since a quarantined file's whole content was rejected. The Batches tab still shows
Lineage/Reprocess normally — those rows do carry a `consignment_id`. Reprocess always asks
`InspectoConfirmService.confirm()` before calling `POST /runs/{name}/reprocess {batchId}` — a real mutating
action needs an explicit step, unlike the read-only tabs.

**Record-level replay (X4, shipped in the UI 2026-09-25).** The **rejected-rows dialog** (`rejected-rows.dialog.ts`
— opened by *View the rejected rows* on a Files row with `error_rows > 0` and on every Quarantine row) carries a
**Replay rejected records** action → `RunsService.replayRejects` → `POST /runs/{name}/replay-rejects {file}`
(backend: [execution-lanes](../../backend/pipeline-graph/execution-lanes.md) X4). Rules it follows:

- Offered only once the reject file loaded with `rowCount > 0` — never on the 404 empty state or a load error.
- Always asks `InspectoConfirmService.confirm()` first; the text says it replays **only the rejected lines, as a
  new Consignment**, **once per reject file**, and warns that reject files written **before 2026-09-25** may have
  turned `"` into `'` (the ingester fix that date), so a quoted value there can land split or altered.
- ⚠ **Disabled, not hidden**, without `lens.canOperateRuns()` — the button stays with a visible reason line and
  a tooltip. This deliberately differs from the grid row actions above (reprocess/drain *hide* for the
  Business lens): inside an already-open dialog a vanished button just looks broken.
- Success renders an `<inspecto-alert variant="success">` with the new Consignment id and an **Open Consignment**
  button (the batch-detail dialog), and the replay button stays disabled — a reject file replays once. A
  non-`SUCCESS` terminal status (every record rejected again) is a warning naming the replay file whose own reject
  file holds them. A `200 status: FAILED` is **not stored**: the server released its claim, so a warning toast
  says nothing landed and the button stays usable.
- Refusals go through `replayRejectsErrorMessage(err, file)` (`inspecto/api/runs.service.ts`), which prefixes the
  next step to the server's reason: **409** already replayed / still in flight, **422** cannot be replayed
  (non-CSV, no `raw_line`, possibly truncated at `rejects_limit`), **403** needs `canOperateRuns` and a bare file
  name; anything else falls back to `apiErrorMessage`. Pinned by `rejected-rows.dialog.spec.ts`.

**Files tab: real field names + the live step gauge (2026-08-13).** The Files tab's `GET /runs/{name}/files`
rows are the `_status_` ledger header **verbatim** (`ConsignmentAuditWriter`): `start_time, end_time, filename,
status, parsed_rows, error_rows, output_paths, output_sizes_bytes, duration_ms, error, consignment_id`. Status
is `SUCCESS` or one of `QUARANTINED_UNREADABLE|QUARANTINED_MISMATCH|QUARANTINED_EMPTY` — there is no per-file
`FAILED` (that only exists at the batch-summary level). Alongside the file-history grid, the tab now renders
the **live step gauge**: `GET /runs/{name}/pending`'s `InboxStatus.step` (`{consignmentId, step, index, total,
startedAt}`) is shown as "‹step› · Step N of M · in step for ‹age›" — the age of `startedAt`, computed **once
per load** (not from the template, which would re-derive `Date.now()` every change-detection pass and throw
NG0100), is the design's only hang signal since the in-memory snapshot is always "present" while running.

**Batches tab: FAILED retries automatically (2026-08-13).** Markers/fingerprints are written last in the
commit sequence, so a FAILED consignment leaves no "already processed" record and its files are simply
rediscovered on the next inbox poll — retry is implicit, not an action an operator takes. The Batches tab
surfaces this: an `<inspecto-alert variant="info">` above the grid, shown only when the loaded ledger actually
contains a `FAILED` row, explains that the files will reappear as Pending on the Files tab with nothing
further needed. The all-`SUCCESS` case renders no banner.

⚠ **The row shapes are the server's, and the mock that had to mirror them is deleted.** Corrected
2026-09-09: `mock/handlers/demo.handler.ts` and `demo.handler.spec.ts` went with the offline mock backend
on **2026-08-31**, so the pinning this paragraph described no longer exists — the authority is now
`ConsignmentAuditWriter.java:48`, whose header is
`consignment_id,pipeline,schema_name,output_table,start_time,end_time,status,…`. The drift history is kept
because it names the failure class: before 2026-08-13 the mock had invented
`file_name`/`quarantined_at`/`PROCESSED` spellings, which also made the offline Files tab always show
"0 Succeeded". The mock's **batches**
rows had the same drift class (invented `status: 'COMMITTED'`, `input_files`/`input_rows`/`output_rows`/
`rejected_files`/`committed_at` columns) — also fixed 2026-08-13 (`de781124`): `batches()` now returns the
real `ConsignmentAuditWriter` header verbatim (`consignment_id, pipeline, schema_name, output_table, start_time,
end_time, status, member_count, rejected_count, total_input_rows, total_output_rows, output_file_count,
total_output_bytes, duration_ms, error, cast_failures`), status is `SUCCESS`/`FAILED` (the mock never
generates `EMPTY`), `cast_failures` of `-1` ("not measured") is written blank not as `"-1"`, and
`ops.handler`'s alert-evaluation math (`rowsInWindow`/`ledgerMetric`) reads the same real column names
`AlertService` does. Pinned in `demo.handler.spec.ts`. No open residual remains in this area.
