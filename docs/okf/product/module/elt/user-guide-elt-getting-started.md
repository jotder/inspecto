---
type: Feature
title: ELT — user guide — Getting started and the Pipeline editor
description: Part 1 of 7 of ELT — user guide: Getting started — from a blank slate to a queryable Dataset; The Pipeline editor — shell, lenses, tabs and docks
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# ELT — user guide — Getting started and the Pipeline editor

Part 1 of 7 of [ELT — user guide](user-guide-elt.md) · next: [Steps, Step configuration and Collect](user-guide-elt-steps.md).

## 1. Getting started — from a blank slate to a queryable Dataset

> **Status as written 2026-10-22:** use cases agreed with the operator (§1.6); file first is primary, detected
> settings are pre-applied with low confidence flagged, missing capabilities are shown locked with a reason, and
> all nine use cases are must-have. This section states what a first-time console user wants, what they must do,
> and how the product carries them the whole way — it is the requirement the onboarding plans built against.
>
> **Owners.** The edition gates are [`data-tier-editions-plan.md`](../../../../archived-documents/plans-archive/data-tier-editions-plan.md)
> (`DATA-TIERS-1`, DT-13 and DT-14); the onboarding experience is
> [`first-run-onboarding-plan.md`](../../../../archived-documents/plans-archive/first-run-onboarding-plan.md) (`ONBOARDING-FIRST-RUN-1`, the
> console) on the shared import route of [`la-onboarding-plan.md`](../../../../archived-documents/plans-archive/la-onboarding-plan.md)
> (`LA-ONBOARD-1`, Inspecto LA). The Inspecto LA side of the same journey:
> [`la-core/spec-first-time-user-use-cases.md`](../la-core/spec-first-time-user-use-cases.md). The onboarding screens
> themselves: [onboarding](../../layer/product/user-guide-onboarding.md).
>
> **Decision numbers.** This section was first written for the draft `PIPELINE-EDITIONS-1`, merged into
> `DATA-TIERS-1` on 2026-10-22 and never published on its own. Its numbers map as: D-PE1 → DT-5 and DT-17 · D-PE2, D-PE3, D-PE4 → DT-16 ·
> D-PE5 → DT-7 (shaping), DT-15 (Expectations, quarantine and dedupe stay on Basic, decided) and DT-6 (lookups) ·
> D-PE6 → DT-4 (decided: files only on Basic, every remote adapter Standard) · D-PE7 → DT-12 · D-PE8 → DT-19 ·
> D-PE9 → the Outputs row and the `pipeline.egress` gate · D-PE10 → DT-18 · D-PE11 → DT-9.
>
> **Grounding.** The "today" steps come from two read-only surveys of the checkout on 2026-10-22 (`26a2dde24`): one of
> the console (`inspecto-ui/src/app`), one of the docs and control routes. Key claims were spot-checked (Home's
> first-run panel, the Dataset auto-registration toast, the inbox upload living in the Run-to-here dialog,
> `orders-starter` polling). Treat a file reference as a pointer to read, not as proof.

### 1.1 Who the first-time user is

| Persona | Edition | Wants | Knows |
|---|---|---|---|
| **P1 Solo analyst** | Basic (no sign-in) | Get a file they already have into a table they can query and explore, today | Their data; spreadsheets; maybe SQL. Not Pipelines, Collectors or TOON |
| **P2 Data engineer** | Standard | Onboard a recurring feed that runs by itself and feeds a downstream app (Link Analysis, Cases, Reconciliation, dashboards) | Files, schedules, databases, SFTP; new to Inspecto's vocabulary |
| **P3 Downstream app user** | any | Use an app (for example Link Analysis) and needs "my data" in it | The app's domain; does not want to learn the pipeline layer at all |
| **P4 Evaluator** | any (often Basic) | Judge in ten minutes whether the product is worth adopting | Nothing about the product |

The first three goals all end at the same object: **a Dataset that is queryable and that a downstream app can
bind**. Everything below is measured against reaching it.

### 1.2 The journey as built (2026-10-22)

From a blank install (P1, CSV file, Basic). ✋ = a manual step the user must know to do.

| # | Step | Where | What the user must decide or do |
|---|---|---|---|
| 1 | Open the console | `/` → `/home` (`menu-landing.ts`) | Nothing. Home shows a first-run panel with three cards when there are **zero Runs**: *Onboard a Stream*, *Author a Pipeline*, *Query it in Studio* (`home.component.html`) |
| 2 | ✋ Have a Space | Spaces pane | On a multi-space server with none: *New space* or *New from template* (gallery; a template the edition cannot run shows `missingFeatures`). On a single-tenant server there is nothing to do |
| 3 | ✋ Start onboarding | Home card → `/catalog?onboard=stream` | Choose Stream or Reference, type a unique name, an optional description; inbox and output folders under *Advanced* |
| 4 | ✋ Collect stage | Guided editor `/pipelines?guided=1` | Accept the local inbox (default) |
| 5 | ✋ Parse stage | Guided editor | Upload or paste a sample; **click** the sniff suggestion chip (JSON / NDJSON / delimiter, never applied automatically); check header, quote, date formats against the "Sample resolves to" line (the server's own dialect sniff, the one Add data reads); numbers written `1.234,50` need **Decimal mark** = comma (*How values are understood*); a Parquet sample is a binary file, chosen with the **Parquet** format |
| 6 | ✋ Schema stage | Guided editor | Ask for a schema suggestion (`/config/suggest/schema`, TRY_CAST vote); review and **accept** types; fix names |
| 7 | Enrich stage | Guided editor | Optional; skip |
| 8 | ✋ Publish stage | Guided editor | Accept the output |
| 9 | ✋ Fix findings | Validation dock | Read findings; Activate is refused while any error remains |
| 10 | ✋ Activate | Editor toolbar | Confirm "The collector starts picking up files on its next poll cycle". The Dataset is **auto-registered** (toast: *queryable under Catalog ▸ Datasets*) |
| 11 | ✋ Get the file into the inbox | Run-to-here dialog **only** (*Upload to inbox*), or copy it into `data/inbox/<stream>` by hand | Know that the upload lives inside a test dialog |
| 12 | Wait or ✋ run | Next poll cycle, or *Run now* on the **Collectors** pane | Know that a different pane has the button |
| 13 | ✋ Check the run | `/runs/<name>` | Read status, row counts, quarantine |
| 14 | ✋ Query | Catalog ▸ Datasets or `/studio/queries` | Find the Dataset, write or build a query |

**Count:** about 11 manual steps, 5 panes (Home, Catalog, Pipelines editor, Collectors, Runs, plus Studio), and
three pieces of hidden knowledge: the upload lives in a test dialog, *Run now* lives on another pane, and inference
must be clicked to apply.

**What already helped.** The Home first-run panel; the guided stage chips with a status word and a finding count;
deterministic inference (client sniff, server `sniff_csv`, `SchemaSuggest` with human date formats); go-live
findings that name the missing stage; Dataset auto-registration; template gating by edition; inline attribute help.

**What was missing.** No next-step prompt after a stage; no upload on Home or Catalog; no drag-and-drop; inference
never pre-applied; first-run detection keys on zero Runs, not "no Dataset yet"; Activate copy assumes polling; the
assistant answers 503 in every shipped bundle (no bundle ships its implementor, `spec-assist-agent.md`), and it is
Standard and up anyway.

### 1.3 Use cases

Each use case gives the goal, today's path, the friction, the **assisted path** (what the product should do), and
what the edition plan must account for. Smart help (H1–H9) is catalogued in §1.4.

#### UC-1 See value in ten minutes with sample data (P4, P1; every edition)

- **Goal.** Without bringing any data, see a Dataset, a query and a dashboard working.
- **Today.** Create a Space from the `orders-starter` template; then ✋ copy the sample CSVs from `data/samples/orders`
  into `data/inbox/orders` on the server's disk; the Pipeline is `active: true` and polls, so files load on the next
  cycle; open the `orders_overview` dashboard.
- **Friction.** The only manual step needs file-system access to the server, which an evaluator in a browser does not
  have. Nothing on screen tells them to do it.
- **Assisted path.** The template Space opens on a **Space guide** (H1) whose first item is *Load the sample data*:
  one button copies the samples into the inbox and starts a run, then the guide shows the run finishing and offers
  *Open the dashboard* and *Query the Dataset*.
- **Done when.** A new user reaches a populated dashboard with no file-system access and no reading.
- **Edition plan impact.** Under D-PE1 (manual-only Basic) `orders-starter` no longer polls on Basic, so *Load the
  sample data* must also **run** the Pipeline. The pack's README line ("copy them into data/inbox") becomes a button.

#### UC-2 Onboard my first CSV into a queryable Dataset (P1; every edition) — the core use case

- **Goal.** "I have `sales_2026-10.csv`. I want to query it."
- **Today.** §1.2, steps 2 to 14.
- **Friction.** Concepts first (Stream, Pipeline, Collector, stage) before the user has shown the product their file;
  naming before seeing data; inference behind clicks; upload hidden in a test dialog; *Run now* on another pane.
- **Assisted path (file first).**
  1. **Drop the file** on Home, on the Catalog, or on an empty Datasets pane (H8). Upload goes to a new Stream's inbox.
  2. The product **infers everything it can, deterministically**: format, delimiter, header, quoting, column names,
     types, date formats (H2). It proposes a Stream name from the file name (`sales_2026-10.csv` → `sales`, the date
     recognised as a file-name date).
  3. One **review screen**: a preview grid of the first rows as they will load, each inferred choice shown with its
     evidence ("`;` — every one of 20 lines has 7 columns"; "`order_date` DATE, format `%d/%m/%Y`, 100% of 500
     values"), each changeable in place. Low-confidence choices are highlighted, not hidden.
  4. **Create and run.** One button saves the Pipeline, runs it once on the uploaded file, and registers the Dataset.
     Findings that block it come with a fix (H3).
  5. **Land on the Dataset**: row count, quarantine count with a link to why, a preview, and *Query it*, *Chart it*,
     *Use it in Link Analysis* (H1 next steps).
  The guided editor stays one click away (*Open in editor*) for anyone who wants every stage.
- **Done when.** File to queried Dataset in one upload, one review and one confirm, with no pane changes.
- **Edition plan impact.** This path uses only Basic capabilities (local inbox, core parse and schema, a manual run),
  so it must work identically on Basic. Activate copy must not promise polling on Basic (D-PE1).

#### UC-3 Onboard a JSON or nested file (P1, P2; every edition)

- **Goal.** Load an export from an API or app that is a JSON array, NDJSON or nested records.
- **Today.** The client sniff tells array from NDJSON; nested records need `records_path` and dotted selectors typed
  by hand; the JSON tree doc still describes nested values as stringified.
- **Assisted path.** The review screen shows the record tree, proposes the repeating array as the record path, and
  lets the user tick the fields to flatten (dotted selectors generated). Arrays inside records are offered as a
  child Dataset or a JSON column, with the trade-off stated.
- **Edition plan impact.** Flattening at parse time is a parse capability (Basic). Exploding arrays into rows is
  `transform.explode`; D-PE5 must say whether that is Basic or Standard, because nested files are common first files.

#### UC-4 Add a lookup table and use it (P1, P2)

- **Goal.** "My sales have a `store_id`; I have a stores list; I want the store name and region on each row."
- **Today.** Onboard a **Reference** (Keys & Load stage, full replace by default), then add an Enrich stage on the
  Stream that joins it.
- **Assisted path.** When a second file shares a column with an existing Dataset, the product says so ("`store_id`
  matches 98% of `sales.store_id`") and offers *Use as a lookup*. The Enrich stage pre-fills the join and previews
  the match rate and unmatched keys.
- **Edition plan impact.** D-PE5 puts enrichment in Standard. Then Basic should still let the user onboard a
  Reference and **query** both (a join in Studio), while the in-pipeline enrich shows a **locked** affordance with a
  plain reason (H4), never a dead end.
- **As built (`ONBOARDING-FIRST-RUN-1` C6a, 2026-11-05).** Basic onboards References (A-5). The Add data draft names
  shared columns with their match ("`store_id` matches 98% of `sales.store_id`", counts only, never over a column masked
  for the person) and *Use as a lookup* re-reads the file as details identified by that column; the Reference's console
  card shows its rows and filters them; the Dataset it describes offers enrichment, locked on Basic with the Studio
  join as the way, opening the guided editor on Standard (no pre-filled Enrich stage, no match-rate preview there).
  Detail: [onboarding](../../layer/product/user-guide-onboarding.md) (*Lookups*).

#### UC-5 The next file arrives (P1 Basic, P2 Standard)

- **Goal.** Tomorrow's file should land in the same Dataset.
- **Today.** Drop it in the inbox folder; polling picks it up (every edition today).
- **Assisted path, Basic (manual-only under D-PE1).** On the Stream or Dataset page: *Add a file* (upload) and *Run
  now* side by side; the run checks the new file against the schema and reports drift in plain words ("new column
  `discount`", "`amount` has 3 non-numbers").
- **Assisted path, Standard.** After the first successful manual run, the guide offers *Run this automatically*:
  watch the folder, or a schedule, with the next run time shown. On Basic the same item is shown locked with the
  reason (H4).
- **Edition plan impact.** This is where D-PE1 is felt every day. Basic must make upload-and-run one gesture, or
  manual-only reads as broken rather than as a limit.

#### UC-6 Connect a remote system (P2; Standard)

- **Goal.** Pull files from SFTP or S3, or rows from Postgres, on a schedule.
- **Today.** Create a Connection (`canOnboardConnections`; secrets), then pick it in the Collect stage.
- **Assisted path.** The Collect stage offers *Connect a system*: pick a kind, fill the form, **Test connection** with
  a plain-language result (DNS, port, auth, path, host-key), browse the remote folder or table, pick a sample file
  from it to drive the rest of UC-2's review.
- **Edition plan impact.** D-PE6 makes remote adapters Standard and up. On Basic the Collect stage shows local inbox
  and upload, and *Connect a system* as locked (H4).

#### UC-7 My first run failed or quarantined rows (every persona; every edition)

- **Goal.** Understand what went wrong and fix it without reading logs.
- **Today.** Run detail shows status, counts and quarantine; findings show in the editor dock.
- **Assisted path.** A failed or partly quarantined run names the **cause class** and the **fix**: "`amount` has
  `1.234,50` in 40 rows — this file uses `,` as the decimal mark → *Switch decimal mark*", "date `31/02/2026` is not
  a real date (2 rows) → kept in quarantine, *Show rows*". One click applies the fix to the draft and *Re-run*.
- **Edition plan impact.** None: correctness and its explanations are in every edition.

#### UC-8 Feed a downstream app or a dashboard (P3, P2)

- **Goal.** "I want this data in Link Analysis" or "on a dashboard".
- **Today.** Bind the Dataset by hand in Studio, then build Measure, Widget, Dashboard; or bind entities for Link
  Analysis in its own settings.
- **Assisted path.** The Dataset page lists **what it can feed**, inferred from its columns: "looks like call
  records — `caller`, `callee`, `start_time` → *Explore as a network in Link Analysis*"; "has a date and an amount →
  *Make a starter dashboard*" (`POST /bi/templates/{id}/apply` exists). Each offer maps columns with a preview and
  one confirm.
- **Edition plan impact.** The offers follow the edition: on Basic, Link Analysis is the explorer; dashboard Link
  Analysis widgets are Standard (D-LAE2). An offer the edition cannot fulfil is shown locked, not hidden, so the
  upgrade path is visible.

#### UC-9 Start from my app, not from the pipeline layer (P3)

- **Goal.** Inside Link Analysis (console or standalone app): "add my data" without visiting the Catalog.
- **Assisted path.** The app's empty state offers *Add data*: it runs UC-2's file-first path inline, with the
  review screen pre-asking the app's needs (which columns are the entities, which the time), then returns to the
  app with the data bound.
- **Edition plan impact.** The pipeline layer is the platform under the apps: its onboarding must be embeddable as
  one reusable path, not tied to the Catalog page.

### 1.4 Smart help catalogue

| ID | Help | What it does | Edition | Exists (2026-10-22)? |
|---|---|---|---|---|
| H1 | **Space guide** | A per-Space checklist of the next useful steps (load data, check the run, query, feed an app), ticked by real state (a Dataset exists, a run succeeded), dismissible, never modal | all | Partly: Home's three cards, shown only while there are zero Runs |
| H2 | **Explained inference** | Deterministic detection of format, delimiter, header, types, dates, file-name dates, record path; each choice shows its evidence and confidence and is pre-applied to a draft the user reviews | all | Detection exists (`parsing-sniff.ts`, `sniff_csv`, `SchemaSuggest`); pre-applying with evidence does not |
| H3 | **Findings with fixes** | Every save, run or quarantine finding carries a plain cause and a one-click fix where one is safe | all | Findings exist; fixes do not (go-live names the missing stage) |
| H4 | **Edition-aware locks** | A capability the edition lacks is shown locked with one line on why and which edition has it; never a control that only fails | all | Partly: `/bootstrap` hides nav entries and Space Templates show `missingFeatures` |
| H5 | **Contextual docs** | Each stage and finding links to the one doc section that explains it | all | Inline attribute `help` text only |
| H6 | **Assistant** | Natural-language help: explain a finding, propose a config, write a first query (`SuggestConfig`, `KpiToSql`, draft-only) | Standard and up | Built but answers 503 in every bundle (`PKG-5`) |
| H7 | **Sample data** | One-click sample load for template Spaces; a "try with a sample file" link in an empty Space | all | Samples ship in `orders-starter`; loading them is manual |
| H8 | **Drop-a-file empty states** | Home, Catalog and Datasets empty states accept a dropped file and start UC-2 | all | Not found |
| H9 | **Column-based suggestions** | "This column matches that Dataset", "this looks like call records" — the offers in UC-4 and UC-8 | all (offers follow the edition) | Not found |

**Key point for the edition plan.** Basic has no assistant (H6), so first-run help on Basic must be
**deterministic**: H1 to H5 and H7 to H9. That fits the principle of no weaker correctness: the deterministic helpers
are also the ones a reviewer can trust and test.

### 1.5 What the use cases asked of the edition plan (carried 2026-10-22)

Points 1, 2 and 5 became DT-13 and DT-14; points 3 and 4 are DT-6 and DT-7; points 6 and 7 are
`ONBOARDING-FIRST-RUN-1`. All three plans are now archived.

1. **D-PE1 needs a manual-run experience, not just a gate.** *Add a file* and *Run now* sit together on the
   Stream and Dataset pages and in the guide (UC-2, UC-5); Activate copy ("next poll cycle") follows the edition.
2. **`orders-starter` loads and runs its samples from a button** (UC-1), since it does not poll on Basic.
3. **D-PE5 places `transform.explode` and enrichment with nested files and lookups in mind** (UC-3, UC-4); if
   they go to Standard, Basic needs the query-time alternative (a join in Studio) and a locked affordance (H4).
4. **Whether Basic onboards References** (UC-4) — decided: it does (A-5, §UC-4 as built).
5. **Locked, not hidden** (H4) is a plan rule for every gate: the step palette, triggers, remote Collect, egress
   Job types and downstream offers.
6. **New work outside the gates**: file-first onboarding (UC-2), explained inference (H2), findings with fixes
   (H3), the Space guide (H1), drop-a-file empty states (H8), column-based suggestions (H9), and an embeddable
   onboarding path for apps (UC-9).
7. **First-run detection** keys on "no Dataset yet", not "zero Runs", so the guide stays useful after a failed
   first run.

### 1.6 Decisions (operator answers, 2026-10-22)

| Date | Decision |
|---|---|
| 2026-10-22 (Q1) | **File first is primary.** Home, the Catalog and empty Datasets panes start with a file drop; the guided editor stays one click away (*Open in editor*) as the expert path |
| 2026-10-22 (Q2) | **Pre-apply detected settings, flag low confidence.** Every detected choice goes into the draft with its evidence; low-confidence choices are highlighted and need a click to confirm. What is saved is exactly what runs (ingest stays deterministic) |
| 2026-10-22 (Q3) | **Locked with reason**, everywhere: a capability the edition lacks is shown disabled with one line on why and which edition has it — never hidden |
| 2026-10-22 (Q4) | **Onboarding UX is its own companion plan** (`ONBOARDING-FIRST-RUN-1`); the edition plan takes only what its gates need (`DATA-TIERS-1` DT-14) |
| 2026-10-22 (Q5) | **All nine use cases (UC-1 to UC-9) are must-have** for the first release |

**Consequences recorded in the edition plan** (`DATA-TIERS-1` DT-13 and DT-14): every gate shows its control locked
with a reason; *Add a file* and *Run now* sit together on Basic; Activate copy follows the edition; `orders-starter`
gets a load-and-run sample button. Everything else in §1.5 point 6, plus pre-applied inference (Q2), file-first
entry points (Q1) and first-run detection (§1.5 point 7), is
[`first-run-onboarding-plan.md`](../../../../archived-documents/plans-archive/first-run-onboarding-plan.md).

---

## 2. The Pipeline editor — shell, lenses, tabs and docks

Route `/pipelines` (Workbench nav group), dir `modules/admin/pipelines/`. An **AntV G6** interactive
graph editor for authoring **Pipelines** (the DAG artifact — never "flow"). Gesture model:
click = select (and open the Step's config pane — see *Selection is configuration*, §4),
double-click = configure, plain drag = move, **Shift+drag = draw edge** (two-click Connect also
available). ⚠ The modifier is the gesture's only "handle", so the canvas **states it**: an author sees
*"Shift+drag from one Step to another to connect them"* bottom-left (`data-testid="connect-hint"`, hidden
in the read-only lens), the Connect tooltip names it, and the canvas's accessible name says Shift+drag —
it used to say "drag Step-to-Step to connect", and a builder dragging Step to Step saw the Step move and
no edge appear (2026-09-25). The editor keeps a persistent `Graph` and mutates in place (G6 patterns:
[architecture](../../layer/product/architecture-product.md)). Backed by `PipelinesService` / `ComponentsService` against a
real ControlApi — ⚠ the offline mock backend was **deleted 2026-08-31**; `npm start` needs the
backend on :4204.

`/pipelines` is a **single route with no guard**. View, editor and topology are internal modes of one
component, selected by query parameters. Authoring is gated *inside* the component by a capability check
that disables and hides individual affordances — new, save, delete — while **the screen itself stays
reachable by every persona view**: independent confirmation of the settled rule that a persona view never
hides a screen.

### 2.1 Three lenses, tabs, and laziness

`PipelinesComponent` hosts **`view` · `editor` · `topology`**:

- **View and Edit are one component** — `PipelineEditorComponent` with `[readOnly]="mode() === 'view'"`.
  Same toolbar, tab strip, palette, canvas and docks. `canAuthor()` = authoring lens AND not
  read-only, checked on every canvas mutation path (defence-in-depth, not hidden buttons).
- **Topology** is the store-joined overview: every chosen pipeline on one canvas wired through the
  synthetic STORE nodes they share. Tabs answer *what is in this pipeline*; topology answers *how do
  these fit together*. ⚠ Mode ids are `view`/`editor`/`topology`; `combined` once meant the
  read-only editor and must not be resurrected.
  ⚠ **The editor stays mounted (CSS-hidden) while Topology shows** — never `@if`/`@else` it away: it
  owns every tab's graph + dirty flag, and unmounting dropped an Applied-but-unsaved Step
  (`PIPELINE-EDITOR-VIEWSWITCH-LOSS-1`, 2026-10-11).
- **Laziness is the rule.** Arrival fetches only `GET /pipelines` (names + flags) and the Step-type
  catalog. `GET /pipelines/combined` fires on first Topology entry only (Refresh refetches). An Open
  dialog returns the **full desired set** of tabs; a tab's graph is lifted when it first activates.
- ⚠ **Per-tab state parks on switch** — graph AND dirty flag, via `parkCurrent()`, the one path every
  tab change takes (a first version parked only in `activateTab` while `select` also switched tabs,
  silently discarding edits). A dirty tab refuses an untick-close (toast) and confirms on its own
  close button — the only two ways edits can be discarded. The active tab's `dirty` mirrors into the
  per-tab set through **one effect**, so scattered `dirty.set(...)` sites stay tab-ignorant.
- ⚠ **`select()` is a load, not an idempotent setter** — re-running it refetches the graph and
  discards that tab's unsaved edits. The `?open=` effect fires once per id for this reason, and
  `?open=` also switches the pane to Edit (guided chips are no-ops in View).
- **Unsaved work is guarded at the browser edge too** (2026-09-01): a `beforeunload` handler arms
  when the active tab's `dirty()` OR any parked dirty flag is set — it checks the signal directly
  as well as the per-tab set, because the mirror effect runs on Angular's schedule and
  `beforeunload` fires on the browser's clock. **The open-tab set persists** across reloads
  (`localStorage 'inspecto.pipelines.openTabs'`, `{open, selected}`): restored names are filtered
  against the served list, tabs re-lift lazily, the `?open=` deep link wins selection, and ⚠ a
  failed list fetch does not arm the persist mirror — a backend-down reload must not wipe the
  stored set with `[]`. Dirty edits are deliberately NOT persisted (the guard owns that).
  **Ctrl/Cmd+S saves** (preventDefault always; `save()` only when `canAuthor() && dirty()`).
- **Undo/redo per tab** (R4, 2026-09-01): bounded snapshot stacks (50) beside `cachedModels`,
  capturing the PRE-mutation model JSON (the save-path serialization) at every mutation choke
  point — the same paths that arm dirty. Ctrl/Cmd+Z · Ctrl/Cmd+Y / Cmd+Shift+Z on the one keydown
  handler; ⚠ text-entry targets keep their NATIVE undo (deliberate). Restore is LOCAL —
  `model.set` + a `canvasEpoch` bump driving the graph host's `rebuildEpoch` input (the same
  `rebuild()` a tab switch takes) — ⛔ never `select()`, which refetches and discards. Dirty
  recomputes against a per-tab baseline stamped at load and after each save/activate write, so
  undoing to baseline clears it honestly; the stack survives a save (undo past it re-arms dirty)
  and dies with `forgetTab`. A dirty drawer confirms before an undo (declining aborts). ⚠ Node
  drags never snapshot — moves are purely visual and touch no model state.
- **Open dialog MRU + pins** (R5): `inspecto.pipelines.mru` (cap 8, recorded inside the dialog's
  own `confirm()` from newly-ticked ids) + `inspecto.pipelines.pinned` (per-row star,
  `aria-pressed`); Pinned/Recent sections render above the full list from ONE shared row
  template, stale ids dropped on render, search filters across sections.
- **A Pipeline that does not load is a BROKEN ENTRY in the Open dialog, never a missing one**
  (`PIPELINE-LOAD-FAILURE-INVISIBLE-1`, operator decision 2026-09-23). `GET /pipelines` appends
  `{name, path, loadError: {file, line?, message}}` rows for registered files that failed to load
  ([TOON config](../config/spec-toon-config.md)). The editor reads `PipelinesService.listWithBroken()`
  and splits it with `splitPipelineRows`: healthy rows feed `flows()` (tabs, restore, name checks) as
  before, broken rows feed `brokenPipelines()` → the dialog's `broken` data, rendered after the list with an
  error `<inspecto-status-badge label="Does not load">`, the loader's message verbatim and the pipeline
  file path, and **a checkbox — it opens in REPAIR mode** (no pin or export: there is no loaded graph to
  export). ⚠ Until 2026-09-25 (`SCHEMA-FILE-NAME-1` (d)) the row had no checkbox "since there is no graph
  to open", so choosing it and pressing Open did nothing and a pipeline broken by a dangling
  `schema_file` could not be opened to fix that reference. `GET …/graph/raw` now serves such a file (see
  [TOON config](../config/spec-toon-config.md)) with a `loadError`, which `select()` keeps OFF the
  model (it must never ride a save) in a per-tab map → `selectedLoadError()` → an error banner over the
  canvas ("This pipeline does not load — fix it here and Save"); a successful save clears it. A stored
  open tab naming a broken file is restored like a healthy one, and a re-list keeps it. The **empty canvas
  counts them too** (2026-09-25): when every registered Pipeline is broken it reads *"No pipeline loads —
  This Space has N pipelines, but none of them load"* with a **Show pipelines…** action opening this
  dialog, and *"No pipeline open"* appends *"M more do not load"*; *"No authored pipelines"* is kept for a
  Space that genuinely has none (it used to show over 16 broken ones). ⛔ Every OTHER consumer
  (Catalog registry, Link Analysis, the dashboard editor's stale-widget check) calls `list()`, which
  drops broken rows — do not switch one to `listWithBroken()` without deciding what it should do with a
  Pipeline that cannot run.

### 2.2 The edit shell — toolbar and docks

**Edit mode is a full-bleed editor shell, not an admin page** — `pipelines.component.html` branches
on `mode()`; no page header, no `p-6 sm:p-10`; the mode toggle and `<inspecto-ai-explain>` project
into the editor toolbar via `[toolbarEnd]` (one bar, one `<h1>`). Regions, all collapsing toward the
canvas:

- **Toolbar** — one compact divider-grouped row (document · file · run · edit · assist). The open
  pipeline is a **mat-menu title button**, not a `mat-form-field` (the gamma theme's floating label
  costs ~25px of bar height — re-measure before "restoring the select"). It carries the STABLE
  selection cluster — Run to here · Preview data · Connect · Delete — whose slots **disable by
  selection kind** rather than appearing/disappearing. Run to here and Preview data are READS
  outside `canAuthor()` (the Business lens keeps them); Connect and Delete are inside it. Dry-run is
  a `beaker` (never reuse `play`), homed in the "More pipeline actions" menu, which sits OUTSIDE the
  author gate with author verbs gated item-by-item.
- **Left dock** — the Step palette (`app-pipeline-palette`), search + collapsible categories; a
  search overrides the fold state. ⚠ The palette's `groups` must be a **signal input** — as a plain
  `@Input` its `filtered()` computed caches the empty first pass and the catalog never appears.
- **Right dock** — `Properties` / `Assist` tabs; the definition drawer lives here.
  `showRightTab('assist')` is the toolbar sparkle button.
- **Bottom dock** — one `bottomTab` signal (`'dryrun' | 'validation' | null`); dry-run output and
  validation findings are one dock, never two stacked bands.

Both side docks are `[inspectoSplit]` (persisted `inspecto.split.pipelines.{palette,inspector}`),
collapsing to a 40px icon rail. Shell gotchas (recorded in the `angular-ui` skill): the shell bounds
itself to the viewport (`calc(100dvh - 120px)`); the split handle stays mounted while collapsed; the
G6 host needs its own `ResizeObserver`; a maximized dock overlays the body row absolutely
(`width:100%` beside `shrink-0` siblings clips its own footer). Opening a parse pane transiently
widens the dock to 420px (`InspectoSplitDirective.ensureAtLeast` — never persisted).

**Responsive floor** (`WORKBENCH-RESPONSIVE-FLOOR-1`, 2026-09-23): below a **1024px viewport**
(`PipelineEditorComponent.RESPONSIVE_FLOOR_PX`) the **palette** collapses to its rail, so the canvas
keeps a usable width; the Properties dock stays, because it is where a Step is configured. The rail's
own button reopens it. `applyResponsiveFloor(width)` acts only when the viewport **crosses** the floor
(on init and on `window:resize`): it never fights the author — a palette reopened while narrow stays
open, and widening reopens only a palette the FLOOR closed, not one the author closed. It is
viewport-based on purpose, not a container measure: the preview browser delivers no `ResizeObserver`
callbacks, and the shell's nav drawer already leaves the page below `md` (960px).

**Fit to view** (2026-09-25): the canvas fits the whole graph after every (re)build — open, tab switch,
undo restore, Auto-arrange — and again whenever a resize (a dock opening, closing or dragged) leaves
content outside the canvas box; a graph that still fits keeps the author's zoom and pan
(`PipelineEditorGraphComponent.fitToView` / `onHostResize`). 🔴 G6 clamps a fit to `zoomRange`, so with
the operator's **0.75 wheel floor** `autoFit: 'view'` could not shrink a four-Step graph into the ~200px
canvas left at 1024px with both docks open — acquisition and sink sat clipped off either edge. The fit
now runs with a 0.2 lower bound and then restores the floor at `min(0.75, fitted zoom)`, so the wheel
can always zoom back out to the whole graph but never below it; a 24px `padding` keeps edge labels off
the border. jsdom cannot host G6: the specs drive the fit against a stand-in that clamps like G6 does.

**Remembered Step positions** (the layout half of the row once filed as `PIPELINE-CONFIG-HISTORY-AND-LAYOUT-1`,
2026-09-23; the history half, `PIPELINE-CONFIG-HISTORY-1`, shipped 2026-09-24 — see §13):
dragging a Step persists every node's position (`node:dragend` → `persistLayout()`), and the next open
restores them instead of re-running the automatic `antv-dagre` layout. **Home = browser
`localStorage`, `inspecto.pipelines.layout.<space>.<pipelineId>`** (`pipelines/pipeline-layout.ts`) —
deliberately NOT the `*_pipeline.toon`: the [editable round-trip](spec-pipeline-model.md)
carries no coordinates, and a position key there would be config the engine never reads that every
writer must preserve. Positions are a view preference, like the dock widths and grid layouts beside
them. Rules: a stored layout is applied **only when it covers every node** (a Step it does not know —
added by a config edit or on another device — makes the whole graph fall back to the automatic
layout, rather than half-restored beside a stranger); stale ids are ignored; once a layout exists, an
added Step joins it. The canvas-corner **Auto-arrange** button (`resetLayout()`) forgets it and
re-lays out; it is not author-gated (a view preference). Accepted costs: the layout does not follow
the author to another device, and a Pipeline id change starts from the automatic layout.

**Refusals land in a dock, not a toast.** A 422's named refusals and findings are all rendered into a
persistent validation dock rather than a first-only toast. A 503 is special-cased to an explained
read-only message naming the missing write root.

---
