---
type: Feature
title: ELT — user guide — Collectors, Runs and references
description: Part 7 of 7 of ELT — user guide: Collectors and Collector configuration; Runs and Run Detail; References
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# ELT — user guide — Collectors, Runs and references

Part 7 of 7 of [ELT — user guide](user-guide-elt.md) · previous: [Save, pipeline-level surfaces, guided mode and known gaps](user-guide-elt-save-surfaces.md).

## 21. Collectors and Collector configuration

How a person works with Collectors in the console: the **Collectors** screen that
lists every configured collection task, the **Connections** screen where endpoints and credentials are
kept, and the one form used to configure what a Collector does (local inbox, a Connection, or another
Pipeline's Dataset). It also records the rules that form follows, so the next change to it does not
re-break them. The engine side is in [the acquisition spec](spec-acquisition.md) and [architecture](architecture-elt-acquisition-parsing.md#collectors).
*(Merged from the acquire hub's user guide on 2026-11-18; §5 is the short version inside the editor.)*

### 21.1 The screens

Route `/collectors` (Workbench nav group). Lists configured collection tasks in a **standard**
[data-table](../../layer/product/spec-ds-data-table.md) (`autoHeight`). Backed by `CollectorsService`.
Design and internals: [Collectors architecture](architecture-elt-acquisition-parsing.md#collectors).

- **Collectors** — route `collectors`, `inspecto-ui/src/app/modules/admin/collectors/collectors.component.ts`,
  with the detail drawer `collector-detail.dialog.ts`.
- **Connections** — route `settings/connections` (the old `connections` path redirects),
  `inspecto-ui/src/app/modules/admin/connections/connections.component.ts`, plus the graded probe /
  explore / sample view `connection-workbench.component.ts`.

*(Renamed from "Sources" per the Source→Collector glossary flip, GLOSSARY §2/§3. <!-- vocab-allow: names the rename itself -->
The route, folder and service had already moved; the old screen doc had not — it still claimed `/sources`,
`SourcesService` and an "Acquisition" nav group.)*

### 21.2 Configuring a Collector — the one surface

One surface authors a pipeline's `collector:` block: the [pipeline editor](user-guide-elt-getting-started.md#2-the-pipeline-editor--shell-lenses-tabs-and-docks)'s
**`acquisition`** Step definition drawer (also reached through guided mode's Collect chip — the
onboarding stage-rail shell that used to host a second screen was deleted 2026-08-16, see
[Onboarding](../../layer/product/user-guide-onboarding.md)).

Historically two screens rendered it; they always shared the store, the model, the spec table, the
form renderer and the engine (W2/U-D, 2026-07-31), and the chrome/write-route/node-model gap was
unified 2026-08-04. The per-*block*-not-per-*node* rules below date from that two-adopter era and
still hold — the shared table must stay correct for any future second adopter.

### 21.3 One store, one spec table

Both write `collector:` in `spaces/<space>/config/<name>_pipeline.toon`, parsed into
`PipelineConfig.Collector`, executed by `CollectorProcessor` → `CollectorConnectors.forConfig`.
Both render `COLLECTOR_ATTRIBUTES` (`inspecto/component-model/collector-attributes.ts`) — **the**
table, asserted by object identity on both sides so a forked copy fails the suite.

### 21.4 One component — `<inspecto-collector-config>`

`inspecto/collector/collector-config.component.ts`: the **Local inbox | Connection | Dataset** mode
toggle, the schema form, Test connection, create-a-Connection in place (shared
[ConnectionFormDialog](../connectors/user-guide-connections.md)), and the derived-connector readout. **No write path** —
hosts read `value()` / `resolveConnector()` and persist through their own route, because the two
persisted shapes genuinely differ: a `collector:` block vs. a node's raw config plus a
`use: connection/<id>` binding.

- ⚠ **`connector` is never asked, always derived** (local ⇒ `local`, else the picked Connection's own
  type). `CollectorConnectors.forConfig` dispatches on `collector.connector` while handing that
  factory the profile named by `collector.connection`, and never checks the two agree — so one place
  must decide both. A hand-authored non-local connector with no Connection is *grandfathered*, not
  destroyed.
- ⚠ **Un-binding a Connection is the mode toggle, not blanking the picker.** An empty picker while
  still in Connection mode is refused; a Connection-less non-local collector is the state that used
  to fail at run time.
- ⚠ **An unreachable Connections service is not a verdict.** `connections.list()` degrades to `[]` on
  error, which reads exactly like a list that answered and lacks the id — so the ghost-id refusal used
  to fire on an unchanged, previously-valid node whenever the service was down. The component tracks
  `loading | ok | failed` separately from the array: only `ok` licenses *"not a saved Connection"*, and
  under `failed` the id the node was **loaded with** keeps its stored connector. A *newly picked* id is
  still refused there — there is nothing to derive its connector from — with a distinct "could not be
  loaded, retry" message, so the guard is narrowed, not weakened.
- ⚠ **A host swapping the spec list at runtime must carry the live values across the swap.**
  Reassigning `<inspecto-schema-form>`'s `specs` rebuilds every control from its declared default,
  so the mode toggle silently wiped the form until this component started re-seeding.

### 21.5 Dataset mode — consuming another Pipeline's output

*(UI-S7, 2026-08-28.)* The third mode authors the **dataset entry** the engine shipped in ELT Phase 3 S3c-2: `collector:
{connector: dataset, dataset: <id>}`, resolved to the producer's snapshot dir fresh at every acquire
cycle by the `dataset` connector scheme, which copies new parquet snapshots into this pipeline's
inbox. See [§5 Collect](user-guide-elt-steps.md#5-collect--the-acquisition-step) and the [`dataset` scheme](spec-acquisition-connectors-control.md#the-connector-spi-and-the-resolvable-schemes).

- The mode owns its field the same way Connection does: `dataset` is asked **only** in Dataset mode,
  `connection` only in Connection mode, and `connector` stays **derived** (Dataset mode ⇒ `dataset`).
- ⚠ **`post_action__*` is hidden in Dataset mode.** The dataset connector **forces RETAIN** — a
  consumer must never delete or move a producer's snapshots, whatever its config says — so offering
  the knob would author config the engine silently ignores (the written-but-never-read shape).
- Resolve **fails closed** with no Dataset picked, which is the engine's own paired gate
  (`PipelineConfigParser` refuses `dataset` without `connector: dataset` and vice versa) moved up
  front, rather than discovered at run time.

#### 21.5.1 The trigger half

The entry node also carries `trigger__on` / `trigger__from` / `trigger__coalesce`, authoring
`{type: event, on: dataset, from: datasets/<id>, coalesce: …}` — the S3b trigger the `dataset.write`
Signal fires. Two rules:

- ⚠ **`trigger.type` is DERIVED, never asked**: picking an event `on` writes `type: event`, and
  clearing it withdraws the derived type. `PipelineTrigger.of` only reads `on`/`from` under
  `type: event`, so an `on:` under a schedule type is config nothing reads.
- ⚠ **The modeled trigger leaves are form-authoritative.** `buildConfiguredNode`'s deep merge is
  root-granular, so without deleting a blanked leaf explicitly a cleared `on`/`from` would be
  resurrected from the stored value on every save.
- `on: commit` (an upstream Pipeline commit) is deliberately **not** in the form — it stays an
  Additional-config key rather than a second event vocabulary to keep in step.

### 21.6 One write route — `POST /config/patch`

Onboarding's stage saves were a wholesale file replace after a **client-side** merge: a stale-read
clobber race. `POST /config/patch` (`ConfigWriteRoutes.java`) decodes the stored TOON, deep-merges the
posted block server-side (maps recurse, scalars/lists replace, JSON `null` **deletes** a key),
validates the whole merged draft, and writes atomically — same response shape as `/config/write`,
so findings routing is unchanged. Every onboarding stage got the fix from that one route.
See [Config](../config/user-guide-config.md).

The graph save (`PUT /pipelines/{name}/graph`) keeps merging through `PipelineEditable.lower(g,
existing)` — deliberately **not** rerouted, which would put two writers on one operation.

⚠ TS delete markers must be `null` on the wire (`nullifyDeletes` in `component-model/flat-keys.ts`) —
JSON drops `undefined`.

### 21.7 Dedup belongs to acquisition

*(The `transform.dedup.fingerprint` removal.)* File duplicate detection executes **inside the
`CollectorProcessor` poll cycle** — `ledgerFilter` reads `collector.duplicate` before a file is ever
collected. `transform.dedup.fingerprint` therefore never had a runtime of its own: `PipelineLift`
synthesized it, `lower()` dissolved it back into `collector:`, and `PipelineCompiler` emitted no step for
it. Modelling it as a transform told the operator the check happens *after* collection, and its node
overlay silently beat a `duplicate.mode` typed on the acquisition node (the D9 tripwire).

It was removed 2026-08-04. `duplicate` and `incremental` ride the acquisition node, where the engine
reads them; a stored graph carrying a fingerprint node is refused at save with `UNSUPPORTED_NODE`.

⚠ **`transform.dedup.marker` is a different thing and was not touched** — it seeds real
`processing.duplicate_check` settings. Only `gap_detection` remains a collector-block key owned by a
node other than acquisition (`NOT_ACQ_OWNED` in `PipelineEditable`).

### 21.8 At rest

`collector.connection` is the persisted form; `use: connection/<id>` is **edit-time only** —
synthesized by `PipelineLift`, lowered back by `PipelineEditable`. Runtime reads only
`collector.connection`. Presenting it as a binding on both surfaces needed no migration.

---

## 22. Runs and Run Detail

The two operator screens for Pipelines that are running. **Runs** lists every running Pipeline with
run-now, pause and history actions. Clicking a row opens **Run Detail**, which shows one Pipeline's
Consignments (batches), files, lineage, quarantined files, commits, files waiting on a commit retry and a
report, and lets an operator reprocess a batch, drain a parked one, replay rejected records or act on a
waiting retry. How these runs execute underneath is [spec-pipeline-execution.md](spec-pipeline-execution.md).
*(Merged from the engine hub's user guide on 2026-11-18; *Runs and processing status* above is the short summary.)*

### 22.1 Runs (Operations)

Route `/runs` (Workbench nav group), dir `modules/admin/runs/`. The operations view over executing
Pipelines: a **standard** [data-table](../../layer/product/spec-ds-data-table.md) (`autoHeight`) of the `RunView` /
`RunResult` / `RunStatus` models with per-row run-now/pause/history actions; row-click opens
Run Detail (below, `/runs/:name`). Backed by `RunsService`.

**Run now** follows the v1 async contract (W5b): the trigger returns `202` + `{runId, …}` and the pane shows
a `Run "<name>" started.` toast. The 202 arrives while the run is still going, so the immediate list re-fetch
races it (a run that finished in 0.3 s still showed `Committed 0` until a manual refresh — fixed 2026-09-25).
The pane therefore also polls `GET /runs/runs/{runId}` via `RunsService.awaitRun` — backoff
`RUN_POLL_BACKOFF_MS` (500 ms, 1 s, 2 s, 4 s, then every 5 s), capped at `RUN_POLL_MAX` = 60 polls — and
re-fetches the list once the run leaves `RUNNING` (or a poll fails: 404 = evicted/unknown, or the cap is
hit). This runs whether or not *Auto* is on. Poll delays use `visibleDelay`, so a hidden tab stops polling
and resumes on return; the poll is torn down with the pane (`takeUntilDestroyed`).

**Auto** (toggle, on by default) re-fetches the list every `DEFAULT_REFRESH_MS` = **15 s** via
`visibleInterval` — paused while the tab is hidden, and skipped while the reprocess dialog is open. Grounded
2026-09-25: this clock was working; the stale row came from the trigger race above, not from Auto. Neither
refresh is a server push — there is no SSE on this pane.

**Business lens is read-only observe here** (product decision 2026-07-03): trigger/pause/resume/reprocess
are hidden *and* method-guarded on `lens.readOnly()` (defense-in-depth) — a deliberate pane-specific
exception to the Jobs precedent, where run-now/enable stay operational in every lens. New panes default to
the Jobs heuristic unless the plan says "read-only observe" this explicitly.

### 22.2 Run Detail

Route `/runs/:name` (carries a list→name [breadcrumb](../../layer/product/spec-routing-navigation.md)), dir
`modules/admin/run-detail/`. Drills into one running Pipeline across tabs — batches / files / lineage
(filterable by batch id) / quarantine / commits / commit retries / report — in **standard**
[data-tables](../../layer/product/spec-ds-data-table.md); a row opens the **batch-detail dialog** (mini/single-select
grids inside). Backed by `RunsService`. A `PARKED` row on the Batches tab carries a **Drain** action and the
batch-detail dialog a *Parked at* table (see *Per-Step `enabled` — park and drain* in
[spec-pipeline-execution.md](spec-pipeline-execution.md), Part II).

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
rows via the same generic audit-row grid. The server's `FileStatusStore.quarantine`
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
(backend: [execution-lanes](spec-pipeline-execution.md) X4). Rules it follows:

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
  next step to the server's reason: **409** already replayed / still in flight — or, when the reason says
  *changed*, the source file changed since the rejects were recorded — **410** the source file is gone (values
  are never stored, so replay re-reads the source), **422** cannot be replayed
  (non-CSV, no source columns, possibly truncated at `rejects_limit`), **403** needs `canOperateRuns` and a bare file
  name; anything else falls back to `apiErrorMessage`. Pinned by `rejected-rows.dialog.spec.ts`.

**Commit retries tab (X1 UI, shipped 2026-09-25).** `CommitRetriesPanelComponent`
(`commit-retries.panel.ts`) lists `GET /runs/{name}/retries` — the files of THIS pipeline waiting on a bounded
COMMIT retry (backend: [execution-lanes](spec-pipeline-execution.md) § *The COMMIT retry
affordance*). The panel loads itself when the tab opens and reloads when the bound pipeline changes; Run Detail's
own `loadTab()` skips it. Rules it follows:

- 🔴 **"keeps no retry state" is its own warning, never the empty state.** `keepsRetryState: false` (no
  `dirs.status_dir`: failures are retried every cycle without bound) renders an `<inspecto-alert variant="warning">`;
  only a pipeline that keeps state and has nothing waiting gets the *No retries pending* `<inspecto-empty-state>`.
  The two look identical as an empty list and mean opposite things.
- Columns: file (the poll-relative path, the key both actions take), **attempts / max** (`/ unbounded` when the
  policy is not bounded), **next retry** (a `due` status badge, else the time), last error, and a **Record** column
  that badges `unreadable` sidecars (their attempts read `—`); a warning above the grid counts them. A policy line
  (cap, backoff range) sits beside the reload button. `truncated` shows *first N of total*.
- **Retry now** posts `{file}` and renders the server's `note` in a success alert — it says the attempt count was
  **kept** and how many attempts remain (Q2). **Cancel** first asks `confirmDestructive()`, which says the file is
  quarantined **now under `retry_cancelled`** and **not retried again**, then posts.
- ⚠ **Disabled, not hidden**, without `lens.canOperateRuns()` — both row actions stay with a tooltip reason and a
  reason line above the grid (the rejected-rows replay convention).
- Refusals go through `commitRetryErrorMessage(err, file, action)` (`inspecto/api/runs.service.ts`, sibling of
  `replayRejectsErrorMessage`). A **409** has three causes the server tells apart only in its reason text, so the
  reason picks the prefix: the pipeline is **mid-cycle** (try again when it finishes), it **keeps no retry state**,
  or the file is **already quarantined** (its fate is decided); anything else is "could not be acted on". **404** =
  no record any more / left the inbox (reload), **403** = capability or a path outside the poll directory. The
  server's reason is always appended. Pinned by `commit-retries.panel.spec.ts` (HTTP mocked with
  `HttpTestingController`, plus an axe check).

**Files tab: real field names + the live step gauge (2026-08-13).** The Files tab's `GET /runs/{name}/files`
rows are the `_status_` ledger header **verbatim** (`ConsignmentAuditWriter`): `start_time, end_time, filename,
status, parsed_rows, error_rows, output_paths, output_sizes_bytes, duration_ms, error, consignment_id`. Status
is `SUCCESS` or one of `QUARANTINED_UNREADABLE|QUARANTINED_MISMATCH|QUARANTINED_EMPTY|QUARANTINED_RESTRICTED` (the last only with the opt-in `processing.refusal: restricted_quarantine`: the Pipeline's own mapping refused the file with a reason code, or its name / header carried a card number; or the platform card scan (`refusal_scan: card_number`) found a card-shaped number; the file was MOVED to the restricted store `<data root>/.restricted/<pipeline>/` under a generated name, and the row's `filename` is that generated name, its `error` the reason code alone and its `logical_name` blank — `okf/product/module/config/plan-spaces.md` §3.5.1) — there is no per-file
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
`ConsignmentAuditWriter.java`, whose header is
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

---

## 20. References

- Plan of record (requirements, decisions, open work, refusals, verification): [plan-elt.md](plan-elt.md)
- Routes: [api-elt.md](api-elt.md) · Module summary: [features-elt.md](features-elt.md) · Design: [architecture-elt.md](architecture-elt.md) ·
  Step catalog: [spec-step-catalog.md](spec-step-catalog.md) · Admin: [admin-guide-elt.md](admin-guide-elt.md)
- Specs: [Pipeline — model, execution, sinks](spec-pipeline-model.md) · [ingest — Consignments, parsing](spec-parsing.md) ·
  [acquisition](spec-acquisition.md)
- Collectors and Runs: [§21](#21-collectors-and-collector-configuration) and [§22](#22-runs-and-run-detail) above
- Archived plans of the 2026-09-04 authoring pass:
  [`parse-pane-redesign-plan.md`](../../../../archived-documents/plans-archive/parse-pane-redesign-plan.md) and
  [`sql-transform-v1-plan.md`](../../../../archived-documents/plans-archive/sql-transform-v1-plan.md).
- **Grounding of the schema/mapping sections (2026-09-03):** direct reads of `schema-editor.dialog.ts`,
  `pipeline-load-definition.component.ts`, `pipeline-config-definition.component.ts`,
  `pipeline-parse-definition.component.ts`, `schema-form.component.ts`, `entity-option-loaders.ts`,
  `components.service.ts`, `node-attributes.ts`, `package.json` (frontend), and `SchemaCompatibility.java`,
  `MappingCsv.java`, `TransformCompiler.java`, `ConfigWriteRoutes.java`, `ConfigFileSupport.java`, `ParserRoutes.java`,
  `NodeAttributes.java`, `CatalogRoutes.java`, `PipelineGraphRoutes.java` (backend).

## Changes

- 2026-08-13: quarantine remediation corrected — the server synthesizes Quarantine rows from the on-disk `<reason>/<filename>` layout, so they carry no batch id and no timestamp.
