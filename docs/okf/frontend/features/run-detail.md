---
type: Feature
title: Run Detail
description: One running Pipeline's batches, files, lineage, quarantine, commits, and report tabs, with a batch-detail dialog. Carries a breadcrumb.
resource: inspecto-ui/src/app/modules/admin/run-detail/run-detail.routes.ts
tags: [feature, runs, detail, breadcrumb]
timestamp: 2026-07-07T00:00:00Z
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
action needs an explicit step, unlike the read-only tabs. Remaining known gap: no record-level replay —
reprocess is still whole-batch only (tracked separately if ever prioritized).

**Files tab: real field names + the live step gauge (2026-08-13).** The Files tab's `GET /runs/{name}/files`
rows are the `_status_` ledger header **verbatim** (`BatchAuditWriter`): `start_time, end_time, filename,
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

⚠ **The offline mock must mirror the server's exact row shapes** (`inspecto/mock/handlers/demo.handler.ts`) —
this was drifted before 2026-08-13 (invented `file_name`/`quarantined_at`/`PROCESSED` spellings that also made
the offline Files tab always show "0 Succeeded"), pinned now in `demo.handler.spec.ts`. The mock's **batches**
rows had the same drift class (invented `status: 'COMMITTED'`, `input_files`/`input_rows`/`output_rows`/
`rejected_files`/`committed_at` columns) — also fixed 2026-08-13 (`de781124`): `batches()` now returns the
real `BatchAuditWriter` header verbatim (`consignment_id, pipeline, schema_name, output_table, start_time,
end_time, status, member_count, rejected_count, total_input_rows, total_output_rows, output_file_count,
total_output_bytes, duration_ms, error, cast_failures`), status is `SUCCESS`/`FAILED` (the mock never
generates `EMPTY`), `cast_failures` of `-1` ("not measured") is written blank not as `"-1"`, and
`ops.handler`'s alert-evaluation math (`rowsInWindow`/`ledgerMetric`) reads the same real column names
`AlertService` does. Pinned in `demo.handler.spec.ts`. No open residual remains in this area.
