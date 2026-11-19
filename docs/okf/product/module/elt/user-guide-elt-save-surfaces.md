---
type: Feature
title: ELT — user guide — Save, pipeline-level surfaces, guided mode and known gaps
description: Part 6 of 7 of ELT — user guide: Save, validation, refusals and history; Pipeline-level surfaces; Guided mode — the onboarding host; Runs and processing status; The Recipe view and the step cards (stale record); Known gaps; Verification culture
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
---

# ELT — user guide — Save, pipeline-level surfaces, guided mode and known gaps

Part 6 of 7 of [ELT — user guide](user-guide-elt.md) · previous: [Authoring gap list, enrich, load, ingest options and testing](user-guide-elt-enrich-load-test.md) · next: [Collectors, Runs and references](user-guide-elt-collectors-runs.md).

## 13. Save, validation, refusals and history

### 13.1 Save and the dock

Save lowers the graph to the flat `*_pipeline.toon` (`PUT /pipelines/{name}/graph`). **Save
refusals all land in the Validation dock** (persistent, click-to-select-Step), never a first-only
toast. Apply (drawer) patches in memory; Save persists; the per-tab dirty flag guards both.

**The save route runs the arming pre-checks** (2026-09-01): `armedWithoutSchema` / `routeArming` /
`stepDisable` findings, same severity split as `/config/write` (ERROR ⇒ 422 when `active: true`,
WARNING on a draft) — before this, the editor's Save could 200 a pipeline that silently failed to
arm at the next registry rebuild. Backend detail: [Pipeline spec — model](spec-pipeline-model.md).

**Validation is live while dirty** (2026-09-01): a debounced (~1.5s) pass recomputes `findings()`
after graph mutations (drawer keystrokes live outside the model until Apply, so they never
trigger it) — the dock badge updates live, and the Save button shows a WARNING state (icon +
tooltip naming the error count) while staying **enabled**: drafts may save with problems.
🔴 The debounced pass runs `refreshFindings()`, never the dock-opening `validate()` — auto-popping
the dock 1.5s after every edit is intrusive. 🔴 **The dock never describes a graph that is gone**
(2026-09-25 — after deleting a Step the dock kept its error): findings naming a Step the change
**removed** are pruned at once, on the change itself (the effect remembers the tab's last node-id set,
so a finding with an id it never knew is left alone), and a tab that turned **clean** inside the window
(Save before it fired, undo back to the baseline) still recomputes when the dock already shows findings —
only a clean tab with nothing listed arms nothing, so arrival never validates unasked. ⚠ The debounce timer goes through an overridable seam
(`armValidateTimer`) on the zone-UNPATCHED `setTimeout`: a zone-patched macrotask armed from an
effect re-enters `ApplicationRef.tick` (NG0101), and neither fakeAsync nor `vi.useFakeTimers()`
survives it in this runner — specs capture the armed callback and fire it deterministically.

### 13.2 The validation ladder

A save climbs five gates in order:

1. **Shape** — a decode failure is a 400.
2. **Graph structure** — cycles, dangling edges, unresolvable references. ⚠ **Only an ERROR blocks**; an
   unknown type and an unaudited SQL step are WARNINGs and never block.
3. **Join references** — an unresolvable by-name join is a 422.
4. **Lowering** — completeness refusals in strict mode, plus structural refusals, returned as a **named
   `refusals[]`** with a 422.
5. **Specs and safety** on the lowered map — a 422 on any ERROR.

✅ **The transform guard is on the save path since 2026-09-09.** The allow-list that refuses data-definition
statements, multiple statements and file-reading functions (`SqlGuard`) runs at the **describe preview**, at
**execution**, and on **save**: `PipelineEditable.refuseUnsafeSql` checks every `transform.sql` node on the
graph save (before classification, so the chain and the projection slot are both covered) and
`RecipeCompiler` calls the same helper; both refuse with `SQL_STEP_REFUSED` carrying the guard's message, so the
author is told *which* construct tripped it, while blankness stays `SQL_STEP_EMPTY` so one defect never reports
twice. Pinned by `SqlSavePathGuardTest`. *(Before that fix a hand-written `read_csv(...)` or data-definition
statement saved and armed cleanly and failed only at run — fail-late, never fail-open.)*

Three other things can be saved and only fail at run: a destination list naming more than one target
(refused when loaded for execution), a route with no default branch (refused at arming), and a join whose
reference resolves by name but whose columns are the dry run's question.

### 13.3 Config history survives a reload (`PIPELINE-CONFIG-HISTORY-1`, 2026-09-24)

Every successful save of a Pipeline config — from any door, not only this editor — leaves a server-side version.
**Retention is a per-Space setting**: the newest 50 are kept by default (the same cap as undo/redo) — a default the
operator can change with `PUT /settings/pipeline-history {keep: 1..1000}` (no Settings pane yet; the dialog states the
number in force). The ⋮ menu's **History…** opens `PipelineHistoryDialog`
(`pipeline-history.dialog.ts`): the versions newest first, and a line diff of the selected one
against the version before it (the default) or against the current config — the oldest kept version has no
previous one, so it falls back to current. Removed lines are `<del>`, added lines `<ins>` (text tone only, no
tinted fills). **Restore v*N*** (after a confirm that says unsaved tab edits are lost when the tab is dirty)
calls `POST /pipelines/{id}/history/{N}/restore` — a SERVER save of the version, recorded as a new version, so
the history is never rewritten and a restore is undone by restoring the version it replaced (a config last
written outside Inspecto — a hand edit, a whole-Space import — has no such version). On success the
editor drops the tab's model, undo/redo and dirty flag (they describe the replaced config) and reloads it from
`GET …/graph/raw`; a refusal (422 the version fails today's content gate, 409 a version saved before a rename)
shows the server's message and keeps the dialog open. ⚠ Restore is NOT a client-side `/config/write` of the
version: that route files a Pipeline by the identity in the body and would fork a second file for any
Pipeline registered under another filename. ⚠ The entry sits in the author-gated ⋮ menu with the other
pipeline verbs, so the Business lens does not see it (the reads are ungated; restore needs `canAuthorWorkbench`).
Storage, routes and rename/delete behaviour: [Pipeline identity § Config history](../processor/spec-pipeline-identity.md).

**`GET …/graph` is a read-only projection, and says so** (D9, 2026-09-22): its response carries
`readOnlyProjection: true` and `links.roundTrip` → `…/graph/raw` (the link derived from the request path so a
`/spaces/{id}` caller stays in its space; `WB-18`). 🔴 PUTting a `/graph` body back is refused 422 on every shipped
Pipeline; use `…/graph/raw` for the round trip.

Known save-path defects are tracked in [BACKLOG](../../../../BACKLOG.md) — see §18 before trusting a described save.

---

## 14. Pipeline-level surfaces

- **Settings** (⋮ menu → `PipelineSettingsDialog`): a Description textarea (first field, optional),
  the `produces` select; when `reference`, load-mode + comma-separated key + refresh seconds.
  Dedicated `GET/POST /pipelines/{name}/settings` — NOT `PUT .../graph`; `PipelineEditable`
  deliberately never models non-node keys (decision 2026-08-13). A hand-built form: `fieldSpecsToAttributes` deliberately
  skips served `LIST`. Backend: [Pipeline spec — model](spec-pipeline-model.md).
- **`description`** is declared (`ConfigSpecs.pipeline()`), parsed, projected conditionally by
  `GET /pipelines`, and rendered as the open-dialog row subtitle. ⛔ Display only. Editable
  post-creation via the settings surface since 2026-09-01 (trimmed on write; **blank clears the
  key from the file** rather than persisting an empty string).
- **New pipeline** from the editor (⋮ menu and the Open dialog's footer) navigates to the Catalog
  onboarding entry (`/catalog?onboard=stream`) — ⛔ never an import of the catalog create dialog
  (a feature may not import a feature); onboarding redirects back into the guided editor. A new pipeline carries a
  **stable id from birth** (2026-08-17), stamped by one scaffold (`pipelineScaffold()`) behind both create surfaces so
  neither can drift; the id is immutable once set, and paths derive from the slug id too (2026-09-06).
- **Identity and lifecycle — three tiers, by cost.** **Relabel** is free — display name only; it first stamps the
  stable id, because identity is otherwise re-derived from the name and is baked into the filename, the commit log,
  the audit exports and the ledger. **Save-as-template** is free and isolating: the config is copied, marked as a
  template and inactive (refused together as active), and **every environment binding is repointed into a template
  directory**, with the schema copied rather than referenced; companions are deliberately not copied — promoting a
  template is an explicit act, not a side effect. A template is registered normally; runnability is gated in three
  places instead, because an unregistered template would be invisible and unpromotable. **Rename** is the full
  identity migration — config, commit log, audit exports, ledger, status mirror and every dependent config —
  bracketed by a journal and resumable, because the steps span three failure domains and are not one transaction.
  ⛔ **The id is deliberately not opaque**: it names the config file, the commit log, the ledger and the catalogue
  entry — *a random id makes an operator's config directory unreadable.* Detail:
  [Pipeline identity](../processor/spec-pipeline-identity.md).
- **Duplicate…** (⋮ menu, canAuthor, refuses a dirty tab): a RUNNABLE copy via the proven
  stream-bundle retarget path — `StreamTransferService.exportPipeline` → `planStreamImport` under
  the new name → `applyImport` — so satellites (schema(s), per-segment schemas, enrich companion)
  come along, dirs re-derive, and the copy lands `active: false`, then opens as a tab. Distinct
  from save-as-template (non-runnable). The name dialog mirrors onboarding's unique-name rule
  locally.
- **Export from the list**: `StreamTransferService.exportPipeline(name)` owns the bundle build
  (reads server state via `GET /config/pipeline`; stream-vs-reference from that config's own
  `produces`), so the Open dialog's per-row download action exports WITHOUT opening a tab and the
  editor's menu item delegates to the same function. Un-gated (export is a read); an OPEN dirty
  row keeps the refusal toast, a closed row exports freely. The server-side pipeline bundle shipped 2026-09-01
  (TRANSFER-ARCH-1; [Pipeline spec — model](spec-sinks.md)); Duplicate and the row export still
  ride the client stream-bundle (BACKLOG follow-up).
- 🔴 **`produces` names two different things**: `PipelineSummary.produces` = the list of STORES;
  stream-vs-reference is the config-level `produces` behind the settings endpoint. Deciding the
  Dataset hop off the summary field is silently wrong; when the read fails, register nothing and
  warn.
- **Go-live registers the Dataset** through the shared `DatasetRegistrationService`
  (`inspecto/api`) — idempotent by `physicalRef`, `sourceName` = the store (blank falls through to
  a default naming nothing), never reverses a succeeded activation. Streams only. **A failure or
  unknown kind raises a persistent per-tab retry banner** (R6, the shared `<inspecto-alert
  variant="warning">` explained-panel — never a toast); Retry re-invokes the registration (safe —
  idempotent), success clears it, and the issue dies with the tab. ⚠ `InspectoConfirmService.confirm(message, title)` takes a title **string**; only
  `confirmDestructive` takes options.
- **Delete pipeline** reads impact first, names dependents in the confirm, sends `force` only after
  showing them, and **cascades** the `<id>_schema` / `<id>_enrich` companions and per-segment
  schemas (`ownedSegmentSchemas()` reads basenames off the in-memory graph before delete clears
  it). ⚠ The impact read is advisory — a failed read must still let the delete proceed (the server
  re-checks and 409s). ⛔ Cascade is scoped to the pipeline's own `<id>_` prefix — a hand-authored
  path elsewhere may be shared, and orphaning another pipeline's file is worse than leaving one.
- **Export/import**: `exportConfig()` (stream-config bundle, `inspecto-stream-config` v1 — format
  contract in [onboarding](../../layer/product/user-guide-onboarding.md)) and *Export document* live in one always-visible Export
  home projected into the transfer menu via `[transferExtras]` (both are read-only; a Business-lens
  operator handing a config to support is the point — A6, 2026-08-17). ⚠ `exportConfig` reads the
  SERVER-held config back and refuses while dirty (an export carrying last-saved state while the
  screen shows something else is worse than none). Stream-vs-reference comes from that config's own
  `produces`. ⛔ The Metadata Bundle (registry artifacts by **id**) and the config namespace (by
  **path**) collide on the word *schema* — never merge the two formats. *View as graph* was
  deliberately not carried over (re-confirmed 2026-08-17). "Related to a pipeline" (D9, 2026-08-31) means what it
  owns, what names it, and what an import needs — ⛔ connections excluded, because a bundle that moved them would
  move a deployment's identity between spaces.
- **Take offline** is the inverse of go-live and the same flag — an `active: false` write from the
  toolbar Deactivate; keeps landed data and the registered Dataset.

### 14.1 The Pipeline Document

The **Pipeline Document** (defined in `GLOSSARY.md`; *Export document*, `GET /pipelines/{name}/document`) is a
generated review artifact, and its contract is what keeps it honest:

- **Per-Step sections** — collect, parse, map, dedup, transform, route, summarize, sink — each with its own
  stated contents.
- **Worked examples are produced by the shipped dry-run machinery running the PRODUCTION Step logic** over a
  bounded sample, so the document cannot demonstrate behaviour the engine does not have.
- **Sign-off binds to a fingerprint** over the recipe *and* every `use:`-referenced component, so an approval
  that predates the current config is detectable.
- **The review round trip is export → edit → upload → validate → dry-run preview diff old-vs-new on the same
  sample → apply**, which is what stops the review artifact and the runtime artifact diverging.
- ⛔ **Never stored as truth.** It is a projection of config, always. ⚠ **Its real risk is being mistaken FOR the
  truth** — a signed-off document reads like a contract while the config keeps moving. The fingerprint is the whole
  mitigation: it makes staleness cheap to surface.

**As a workbook (D-8):** `GET /pipelines/{name}/document?format=xlsx` renders the same model through
DuckDB's `excel` extension (`PipelineDocumentXlsx`, `COPY … (FORMAT xlsx)`) — ⛔ no POI.

- **Bundling.** The extension is not linked into duckdb_jdbc, so a bundle must carry it: `package.ps1` step 6d stages
  `lib/duckdb/<platform>/excel.duckdb_extension` (BUNDLE-LAYOUT-1; was `duckdb-extensions/`), matched to the shipped
  DuckDB's ABI directory (`duckdb.version` 1.5.2.1 → `v1.5.2`, now 1.5.6.0 → `v1.5.6`, derived not listed — no manifest
  to update; before 2026-09-24 it took the first file of that name in the cache, and this desk's cache holds a v1.5.5
  one too), and every launcher points `-Dduckdb.extension.dir` at it.
- 🔴 **In that STAGED mode `DuckDbExtension` loads `LOAD '<dir>/excel.duckdb_extension'` and nothing else** — no bare
  `LOAD excel` (a warm `~/.duckdb` would make a bundle missing its binary pass on the build box) and no `INSTALL`; a
  missing file is a 500 naming the exact path.
- **Proven 2026-09-24, no network:** a Professional-composition bundle (the last `package.ps1` output with
  `inspecto.jar` rebuilt at this change, `spaces/` re-staged per step 4, the local-only fake authenticator in place of
  OIDC because no token could be minted), launched with `USERPROFILE` pointed at an empty directory — a probe on that
  JVM showed a bare `LOAD excel` failing with *not found* under the empty home, so DuckDB's own cache was unreachable —
  answered `?format=xlsx` with HTTP 200, a `spreadsheetml.sheet` body and a valid zip; the staged binary is
  byte-identical (SHA-256 `840f6c65…`) to `~/.duckdb/extensions/v1.5.2/windows_amd64/excel.duckdb_extension`; the
  empty home was still empty afterwards (no INSTALL wrote a cache). With the binary removed the same request returned
  500 naming `…\duckdb-extensions\windows_amd64\excel.duckdb_extension`. `PipelineDocumentXlsxTest` runs the real write
  in staged mode (skips only where no stageable binary exists) plus an ungated missing-binary test.
- 🔴 **One sheet per section, assembled by zip (`XLSX-EXPORT-LAST-SECTION-ONLY-1`, fixed 2026-09-24, operator's
  choice).** The same run found the workbook held only the LAST section: the 1.5.2 writer accepts
  `COPY … (APPEND true)` without error but rewrites the file. Each section is now COPYed to its own one-sheet workbook
  in a temp directory beside the target (removed in `finally`), and `XlsxSheetMerger` stitches them with
  `java.util.zip` — workbook 1 is the base; every further `xl/worksheets/sheet1.xml` becomes `sheetN.xml` with its
  `<sheet>`, Relationship and content-type Override. ⚠ It re-maps neither shared strings nor styles, because the
  writer emits neither: every cell is `t="inlineStr"` (the `sst` is empty) and `styles.xml` is byte-identical across
  workbooks (inspected on 1.5.2). ⛔ A later sheet with a `t="s"` cell or a differing `styles.xml` makes the merge
  THROW, so a writer upgrade fails the export instead of mis-rendering it. `PipelineDocumentXlsxTest` opens the zip and
  asserts the sheet list and each sheet's first cell (red on the old code: `[Guarantees]` alone); `XlsxSheetMergerTest`
  covers the merger ungated on hand-built parts.
- **Linux (grounded 2026-09-26).** The earlier note "Linux bundles ship no `excel`" was a desk fact, not a product
  one: this Windows desk has no Linux jmods cache, so it builds no Linux zip at all, and the release path
  (`release.yml`, ubuntu) already fetches every `linux_amd64` binary with `tools/fetch-duckdb-extensions.mjs` and
  packages with `-RequireExtensions`, which throws on a missing one. What was really missing is that the Linux binary
  had never been LOADED anywhere — no release run exists. `fetch-duckdb-extensions.mjs` now takes `--only` /
  `--platform` (selecting from package.ps1's lists; an unknown value exits 2), and `ci.yml` fetches
  `linux_amd64/excel` alone before the reactor with `DUCKDB_EXTENSION_CACHE` set, so `PipelineDocumentXlsxTest` writes
  a real workbook on Linux on every push instead of skipping. `--check` confirmed the file is published for `v1.5.2`.
  *Session decisions:* fetch in CI, not commit the binary (no binaries in git) and not a desk download (Windows desk
  cannot run it); the ~20 MB per-run fetch is accepted, and DuckDB's own extension signature check still guards the
  LOAD over plain `http`, as it does for the release fetch.
- **Proven 2026-09-27:** GitHub CI run `36299231097` (ubuntu, `15edfd6dc`) ran `PipelineDocumentXlsxTest` 5/5 with 0
  skipped — the Linux `excel` binary LOADS and writes a real workbook; and `package.ps1 -Edition Professional` ran
  green end to end on `15294b4ba` (boot smoke, `excel` staged, `jdk.management` in the runtime).

---

## 15. Guided mode — the onboarding host

Onboarding's create dialog and entry points: [onboarding](../../layer/product/user-guide-onboarding.md). The editor is the host:
`/catalog/onboard/:name(/:stage)` redirects to `/pipelines?guided=1&open=<name>&stage=<chip>`
(`onboard-redirect.ts` — the ONE statement of the target, used by the route and both Catalog
navigation sites). The matcher stays so old bookmarks resolve. `schema` and `keys` both map to the
Schema chip; an unknown stage carries no focus.

Guided mode (`?guided=1`, off by default) renders checklist chips — Collect → Parse → Schema →
Enrich → Publish — each with a status word and finding count, each click opening that stage's Step.

- ⛔ **The stage model is derived from the graph** (`pipeline-stages.ts`, reusing `NodeStatus`),
  never a second readiness opinion — that is how a chip and the canvas card under it disagree.
  ✅ **D6 (signed 2026-09-22) holds this line:** node test state may **not** read operate-lane
  provenance into `tested`. The complaint was real — four nodes read *not yet tested* over three
  completed runs and 1219 rows, and a signal that is wrong for every Pipeline in production is one
  readers learn to ignore — but the remedy is a **third source, not a second opinion**: a distinct
  *ran* badge fed by `overlayCounts` (*“last run: 400 rows · <date>”*), with the finding reworded to
  *“not yet tested in this session”*. `tested` keeps meaning *the editor ran this test*. Tracked as
  `WB-11`; row `PIPELINE-NODE-TEST-STATE-STALE-1`.
- **The run overlay is a PICKED run, not only the last one** (`PIPELINE-RUN-HISTORY-OVERLAY-1`,
  2026-09-23). The toolbar's *last run* fact is a `mat-menu` trigger over `GET /provenance/batches`
  (the server's default 20, newest first — no new storage): picking an entry re-reads `GET /provenance`
  for that batch and re-paints the edge counts, the inspector strip and the drawer's identity strip
  (`pickRun`, epoch-guarded so a slow reply for an earlier pick never paints over a later one). The
  strip then reads *“Selected run: …”* instead of *“Last run: …”*, and the trigger drops *last*, so
  an older overlay is never read as current. Selecting another Pipeline resets to its latest run.
  ⚠ It is **pick, not side-by-side**: comparing two runs is switching between them; a per-edge delta
  view is not built.
- **A dry run is marked wherever the overlay shows it** (`DRYRUN-INVISIBLE-ON-FLAT-LANE-1` half b).
  `GET /provenance/batches` carries a per-batch `simulated` (`bool_or` over the batch's rows), so the
  picker labels a dry-run entry *“· dry run”* before its counts are fetched; the inspector strip adds
  *“dry run — nothing landed”*; the canvas edge keeps its *“(simulated)”* label and is now **dashed**,
  as in the Catalog Sankey. Both lanes now record a dry run's provenance: since half a (operator
  decision 2026-09-23) a flat-lane `?dryRun=true` run writes one `parse`/`sink` record flagged
  `simulated` — its only write — so it appears in the picker marked. Since 2026-09-24 its counts are
  **real** — rows parsed, rows that would have landed — because the flat-lane dry run now parses inside a
  contained pass ([plan](plan-elt.md)).
- ⛔ **The go-live readiness gate is guided-only**: `validatePipeline` does not require a parse
  Step; a hand-built collect→sink graph is legitimate. ⚠ Every stage resolves through the served
  catalog — unresolved catalog reads as five empty stages.
- 🔴 **A refused go-live says what to DO** (`goLiveRefusal`, `pipeline-stages.ts`, 2026-09-25). A missing
  Schema has no node of its own (its chip is `empty`), so *"Schema still needs work"* was a dead end. The
  toast now adds *"Apply the Parse step to create its schema."*, and the Validation dock shows the refusal
  as an alert with an **Open the Parse step** button (`openStage` on the Parse chip — the drawer, or the
  Grammar dialog for a generic `parser`). The alert is LIVE over the checklist (`goLiveBlock`): it goes
  the moment the stage is ready, and the dock never prints "ready to activate" beside it.
- **Infos never block Activate.** From the same drive: the first toolbar Activate was reported to open
  only the Validation dock (four *"not yet tested"* infos), the confirm arriving on the second click.
  ⚠ **Not reproduced**: `activate()` returns after `validate()` only on the guided refusal or an
  error-severity finding, each with its own toast; the live preview reached the confirm on the first click
  with the dock closed/open and the drawer closed/open, after both the dialog and the drawer paths. A DOM
  spec pins it (one click on the rendered button → one confirm, infos only).
- 🔴 A chip **selects** its Step before opening it (selecting is ungated; the editing half is
  withheld by `canAuthor()`), so the strip works in every mode.
- `rejects` never promotes a stage to ✓; the Schema stage tops out at `configured` (the editor
  claims no validation it never ran).

---

## Runs and processing status

Design and internals: [Parsing and ingest architecture](architecture-elt.md#parsing-and-ingest); the Run screens in depth:
[§22 Runs and Run Detail](user-guide-elt-collectors-runs.md#22-runs-and-run-detail) below.

- **Pipeline editor, Parse definition** (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-parse-definition.component.ts`):
  frontend choice, grammar form rendered from the parser's served `grammarSchema`, and a sample preview (§6).
- **Run detail** (`inspecto-ui/src/app/modules/admin/run-detail/run-detail.component.ts`): tabs
  Batches, Files, Lineage, Quarantine, Commits, Commit retries and Report, plus "View the rejected rows"
  (`GET /runs/{name}/errors?file=` — the `errors/<base>_errors.csv` reject rows, bounded, `truncated` reporting the
  true total). Trigger, pause, resume and drain are on the Run surfaces ([API](api-elt.md#parsing-and-ingest)).
- **Processing status** (`inspecto-ui/src/app/modules/admin/processing-status/processing-status.component.ts`):
  problem files per Pipeline.
- **Collectors** (`/collectors`): *Run now* and Collector state — [§21 Collectors](user-guide-elt-collectors-runs.md#21-collectors-and-collector-configuration).

---

## 17. The Recipe view and the step cards (stale record)

🔴 **STALE.** `<app-pipeline-step-cards>` **no longer exists**: it was deleted in
`6d3c68fa` (2026-09-18, *"pipelines canvas consolidation"*), and `inspecto-ui/src` has no Recipe view and no
`onRecipeInsert`. The record below is kept because a decision rested on it (the orphan-drop decline in §6.2 assumed
insert-between lived here). Today there is **no one-gesture insert anywhere** — `insertNode` is a bare
`addNodeToModel` — which is what `WB-14` / `D8` in `archived-documents/plans-archive/workbench-trust-plan.md` reopen.

~~`<app-pipeline-step-cards>` (hosted in `pipeline-editor.component.html`) **is** the ordered `steps:`
chain editor — cards in chain order, insert-between, remove, move up/down, nested `route` branches,
wired with `[editable]`.~~

Rules that still bind wherever a Step is listed:

- ⚠ **Never key anything on `verb`** — one recipe verb authors more than one type (`transform` →
  filter or join); `type` is the unique key (duplicate `@for` track keys are an Angular runtime
  error). A verb can serve a spec the palette never reaches — when a type gains a spec, check the palette serves it.
- ⛔ One polymorphic entry with a discriminator does not work: `PipelineEditable.lower` dispatches
  on the node's `type`, never config content.
- A step card captioned the type's own label via `[typeLabel]` (from the served catalog; fallback =
  category label, never the raw type string). `uniqueNodeId` bakes the type into the id
  (`transform_join_1`) — any future retype affordance makes that id a lie. `PipelineLift` passes
  `null` instead of stamping lifted names with type labels (a name equal to the label reads as a
  visible duplicate); `filter` keeps its legacy "Row filter" name (a real, more specific one).
- **One glyph per Step type** — `typeHeroIcon(type, category)` (`pipeline-graph.ts`) is the single
  icon vocabulary for palette, step cards, and insert menu; the **category is the color**
  (`categoryColor`). Unknown served types fall back to `paletteHeroIcon(category)`. ⛔ Never give
  two palette items one glyph.
- ⚠ Icons are **SVG sprites** (heroicons/feather/material), no icon font: `<mat-icon>add</mat-icon>`
  renders the literal word. Always `svgIcon="heroicons_outline:…"` — only visible by looking at the
  page.

---

## 18. Known gaps

Owners: [BACKLOG](../../../../BACKLOG.md) and the [plan](plan-elt-not-built.md#5-not-built-and-open). The behaviour described above
is the intended-and-actual state. AUTHOR-1, DRYRUN-1, the §5 UI residuals and BUILDER-2 #8
were verified FIXED in code. Closed since: single-slot refusals for `acquisition`, `gap`, `dedup.marker` (2026-09-23);
mid-branch sub-chains and insert-into-branch (2026-09-02); TRANSFER-ARCH-1 as the server-side pipeline bundle
(2026-09-01, with residuals R1/R4/R5/R6 the same shift); the run-history overlay (2026-09-23); the server-side config
history (2026-09-24); the responsive floor and remembered Step positions (2026-09-23). Genuinely open:

- Migrate Duplicate + the row export onto the bundle routes (both still ride the client stream-bundle).
- `processing.join`, `recordDedup`, `routeNode`, `summarizeNode` stay last-one-wins, silently (BACKLOG §4).
- A Parse Apply over a stale `partitions[]` seed can clobber a Sink edit made meanwhile (§10.1).
- An inline-config Step of non-transform/sink kinds cannot be tested (§12).
- Comparing two runs side by side is not built (§15).

## 19. Verification culture

Three lessons this feature keeps re-proving, kept here because they shape every change to it:

- **Drive the preview for anything visible** — five shipped defects were wiring between correct
  units that 2500+ green unit tests could not see (icon ligatures, disclosure-hidden groups,
  `[value]` on native selects, stale drawers).
- **A test that supplies what the surface under test should produce, tests nothing** (the route-
  branch round-trip; the dirty-arming `setValue` trap).
- **Falsify guards before trusting them** — the write-ordering guards were verified by deleting
  them and watching exactly their own tests fail.

## Changes

- 2026-09-01: known gaps corrected — AUTHOR-1, DRYRUN-1, the §5 UI residuals and BUILDER-2 #8 verified fixed in code.
- 2026-09-22: the Recipe view record marked stale — `<app-pipeline-step-cards>` was deleted in `6d3c68fa`.
