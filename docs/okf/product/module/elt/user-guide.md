---
type: Feature
title: ELT — user guide
description: One guide to building and running a Pipeline, from a blank install to a queryable Dataset — the first-run journey and use cases, the Pipeline editor (shell, canvas, Step configuration, Parse, schema authoring, the Record Transformer and the transform.sql pane, joins, filters, enrichment, sinks and route branches), ingest options and feed contracts, testing, saving and history, pipeline-level surfaces, guided mode, the Collectors and Connections screens with the one collector-block form, and the Runs and Run Detail screens.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipelines.routes.ts, inspecto-ui/src/app/modules/admin/pipelines/pipeline-transform-sql-definition.component.ts, inspecto-ui/src/app/modules/admin/enrichment/enrichment.routes.ts, inspecto-ui/src/app/modules/admin/collectors/collectors.routes.ts, inspecto-ui/src/app/inspecto/collector/collector-config.component.ts, inspecto-ui/src/app/modules/admin/runs/runs.routes.ts
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
---

# ELT — user guide

**What this covers.** How a person turns a file into a Dataset they can query, and how the **Pipeline
editor** works when they do it by hand. It starts with the first-time journey (who the first user is, the
steps they take today, the nine use cases the product is built against), then follows a Pipeline in build
order: the editor, collecting, parsing, the schema, transforming and mapping, enriching, loading into sinks,
ingest options, testing, saving, and the screens that show a Run. Two appended sections cover the
**Collectors** and **Connections** screens (§21) and the **Runs** and **Run Detail** screens (§22).

**Contents**

1. [Getting started — from a blank slate to a queryable Dataset](#1-getting-started--from-a-blank-slate-to-a-queryable-dataset)
2. [The Pipeline editor — shell, lenses, tabs and docks](#2-the-pipeline-editor--shell-lenses-tabs-and-docks)
3. [Steps — the vocabulary, the flags and the authoring model](#3-steps--the-vocabulary-the-flags-and-the-authoring-model)
4. [Configuring a Step — the definition drawer](#4-configuring-a-step--the-definition-drawer)
5. [Collect — the `acquisition` Step](#5-collect--the-acquisition-step)
6. [Parse — drawer vs dialog, the claimed slot and the sample thread](#6-parse--drawer-vs-dialog-the-claimed-slot-and-the-sample-thread)
7. [Schema authoring](#7-schema-authoring)
8. [Transform and map — the Record Transformer](#8-transform-and-map--the-record-transformer)
9. [Enrich](#9-enrich)
10. [Load — sinks, partitioning and route branches](#10-load--sinks-partitioning-and-route-branches)
11. [Ingest options and feed contracts](#11-ingest-options-and-feed-contracts)
12. [Testing what you author](#12-testing-what-you-author)
13. [Save, validation, refusals and history](#13-save-validation-refusals-and-history)
14. [Pipeline-level surfaces](#14-pipeline-level-surfaces)
15. [Guided mode — the onboarding host](#15-guided-mode--the-onboarding-host)
16. [Runs and processing status](#runs-and-processing-status)
17. [The Recipe view and the step cards (stale record)](#17-the-recipe-view-and-the-step-cards-stale-record)
18. [Known gaps](#18-known-gaps)
19. [Verification culture](#19-verification-culture)
20. [References](#20-references)

**Reading order.** §21 and §22 joined this guide on 2026-11-18, when the Collectors and Runs guides were
merged into it. They are appended so that §1–§20 keep their numbers, and they sit just before §20
References, which stays last. Read §21 with §5 (it is the full contract of the Collect Step, before §12
testing and §13 saving), and §22 with §16.

21. [Collectors and Collector configuration](#21-collectors-and-collector-configuration) — the Collectors and
    Connections screens and the one `collector:` form (§21.1–§21.8)
22. [Runs and Run Detail](#22-runs-and-run-detail) — the Runs list and the Run Detail tabs (§22.1–§22.2)

> **Scope and owners.** This guide owns the **editor UI** truth (operator decision 2026-09-01: single source of
> truth, split by layer). It defers to:
> - [Grammar configuration](../config/user-guide-grammar-config.md) — the Parse pane's internals and everything Grammar;
> - [§21 Collectors](#21-collectors-and-collector-configuration) (below) — the `acquisition` Step surface;
> - [Inline AI authoring](../intelligence/user-guide-inline-ai-authoring.md) — the `<inspecto-ai-assist>` adoption;
> - [`okf/backend/pipeline-graph/`](../../index.md) and the [Pipeline spec — model](spec-pipeline.md) —
>   the backend Pipeline model (lift/lower/validator/executor, the token model);
> - [`archived-documents/plans-archive/pipeline-spec.md`](../../../../archived-documents/plans-archive/pipeline-spec.md) — **archived 2026-09-10**, provenance only.
>
> **Current truth only** — superseded states are kept only where knowing them prevents re-breaking something.
> The dated change-log forms of the merged pages are in git history and the archived plans they cite.
>
> **Vocabulary.** The canonical term is **Step** (GLOSSARY §2). ⚠ The UI copy and much of the code
> still say "node" (`node-attributes.ts`, `/pipelines/node-types`, …) — the Node→Step rename is
> tracked in GLOSSARY §13 as NOT STARTED. This guide writes *Step* and quotes code names verbatim.
> Product copy never says "wizard"; this guide says **guide** for step-by-step help.

---

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
| 5 | ✋ Parse stage | Guided editor | Upload or paste a sample; **click** the sniff suggestion chip (JSON / NDJSON / delimiter, never applied automatically); check header, quote, date formats against the "Sample resolves to" line |
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
[architecture](../../layer/product/architecture.md)). Backed by `PipelinesService` / `ComponentsService` against a
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
deliberately NOT the `*_pipeline.toon`: the [editable round-trip](spec-pipeline.md)
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

## 3. Steps — the vocabulary, the flags and the authoring model

### 3.1 The authoring shape

Three principles decide what the authored shape can express, and they are easy to violate by adding a feature
that looks harmless:

1. ⛔ **Only `route` creates a user-visible branch.** A filter's dropped rows, a validation's invalid rows, a
   parse's unmatched rows and a dedup's duplicates are **reject streams routed by the Guarantee** — the
   author never wires a reject; they tune where rejects rest.
2. **A recipe is a TREE, and what it deliberately cannot express is a DAG.** The trunk stays linear and
   branches nest; fan-in fights the batch model and stays canvas-authored.
3. 🔴 **Sending the same data to many destinations is not routing.** The `sinks:` list covers it as **one**
   Step. `mode: clone` is only for branches that **diverge in processing** after the split — reaching for it
   to write two copies is the mistake this principle exists to prevent.

**The graph is derived, and three transformations read the file — they are not the same transformation**
(conflating them is the most common mistake in this area):

| Transformation | Direction | Used by |
|---|---|---|
| **lift** | file → graph | Every read: the editor's read-only view, list summaries, and the live ingest lane — *an internal representation only, never a file rewrite* |
| **toMap / lower** | file → editable graph → file | The **authoring round trip**, and the only path that writes |
| **stageTwo** | file → a source-chain-sink graph | The at-rest job runner. It ignores the ingest head entirely and has its own refusal set |

**The round-trip seam is `toMap` and `lower`, not `lift`.** `toMap` calls `lift` internally for topology
only, then **overwrites every node's config with the verbatim raw section that node owns**. `lower` walks
the posted graph and **deep-copies the existing map**, mutating it in place — so the persisted file is
never a value taken straight off the graph. That is what makes unmodelled keys survive, and it is pinned
end to end: `toMap` → decode → `lower(strict)` must equal the original raw map. Three round-trip tests each
guard a different failure: the **verbatim** test (unmodelled keys included); a **real-codec file** test,
because "a block can be perfectly modelled, perfectly parsed from a map, and still be unwritable or
unreadable as a file"; and a **reachable-path** test that drives the editor's actual write route to an engine
field, because a key can be read by the executor while being unreachable from the file the editor writes.
Backend detail: [Pipeline spec — model](spec-pipeline.md).

### 3.2 The Step-type vocabulary is the engine's, and only the engine's

**Since 2026-09-02 the palette renders the served Step Processor TAXONOMY when it is available**
(`GET /pipelines/processor-catalog`, `ProcessorCatalog` in `inspecto-engine`, pinned by
`processor-catalog.contract.json`): eight families (Collectors & Ingestion · Extraction & Format Parsers ·
Data Quality · Transformers & Dimensional Modeling · Analytics/Time-Series · Enrichment & AI/ML · Control &
Governance · Sinks), **122**<!--count:processors--> processors, **every one visible** *(⚠ this said 121 until 2026-09-09; `processor-catalog.contract.json` carries 122<!--count:processors--> — 54<!--count:processors-delivered--> delivered / 17<!--count:processors-partial--> partial / 51<!--count:processors-planned--> planned)*. A processor whose `addable` flag is true (it maps
onto an authorable node type) is an ordinary add/drag entry for THAT node type; a planned processor, or a
capability that is not a Step (a Collector guarantee, a job type, a Studio surface), renders **inactive** —
`role=button aria-disabled`, tooltip and accessible name carrying why, a `soon` / `via <capability>` chip —
and never emits `pick`. Family headers count `addable/total`. The node-type groups below remain the
fallback for a server that does not serve the taxonomy (404 → `paletteProcessors` stays `null`). The
taxonomy is the operator's product board (`EDITIONS.md` §Step Processors) rendered in-product; the engine's
executable vocabulary is still exactly what follows.

🔴 **The palette is served twice over, and its fallback is silent.** A node-type route always loads; the
taxonomy loads alongside it. When the taxonomy route fails, the error arm sets the value to nothing and says
nothing — no message, no log, no banner; the palette silently falls back to the plainer list, and the family
headers, the counts and the "planned" entries vanish. The three host error arms are now tested (a failed
processor-catalog leaves `paletteProcessors` **`null`**, not `[]`, so the node-type groups stay; a failed
node-type route empties the palette; a served-but-EMPTY verb list is treated as *not served*). Whether a
degraded palette should also fire the alert component is an open product decision ([plan](plan.md)).

The palette is a **faithful port of the backend enum `BuiltinNodeType`** as served by
`GET /pipelines/node-types`: `acquisition` (SOURCE), `parser` and the `parser.*`
per-format family (PARSE), the `transform.*` family + `enrichment` (TRANSFORM),
`sink.persistent|materialized|view` (SINK), `gap` (CONTROL; `adapter`/`alert`/`event` deleted, operator, 2026-10-06) — ⚠ the `gap` here is a
CONTROL **node type**, unrelated to the `gap` edge relation below. The palette publishes **one entry per
SHAPE, not per verb** (which is why `transform` appears more than once), and ⚠ **`parse` and `map` serve
`attributes: []` deliberately** — each has a richer editor of its own, so a generic attribute spec there would
be a worse second way to author the same thing.

Edge `rel`s are `PipelineRel` constants, and in the token vocabulary they are **three outlets**: `data`
(default — the token continues), `reject:<reason>` over `unmatched`/`dropped`/`invalid`/`duplicate`, and
operator-named `route:*`. 🔴 **`success`/`failure` are NOT sink-emitted** — an earlier revision of this
line said so; `PipelineRel` records that they are "constructed nowhere … declared vocabulary with no
producer at all". They, with `on_commit` and `gap`, are **Signals, not edges**. See
[the token model](spec-pipeline.md).

⚠ **An invented vocabulary survives unnoticed because nothing rejects it**: `PipelineValidator`
flags an unknown type as a *warning*, `PipelineCodec` stores `config` as an unchecked map, and
`PipelineCompiler` silently drops an unknown-typed node. The palette once carried 13 invented types
for exactly this reason. `pipelines.handler.spec.ts` pins the palette to the enum.

### 3.3 Three different kinds of "supported" — `authorable`, `lowerable`, `executable`

Three orthogonal flags decide what a type can do, and ⛔ **they must not be collapsed**:

| Flag | Means | If absent |
|---|---|---|
| **authorable** | the palette offers it | it exists but cannot be added |
| **lowerable** | a save may persist it | the save **refuses** the node by name |
| **executable** | the executor has a branch for it | it saves fine and **throws at first run** |

- **`lowerable`** (per type, served) answers *"can a save lower this back to the flat
  `*_pipeline.toon`?"* It does NOT mean "the engine can run it". Conflating the two is how the
  palette once presented 20 equal options, 11 failing at Save *(⚠ that count is the pre-per-format era — `BuiltinNodeType` carried **30** constants as of 2026-09-09)* with 422 `UNSUPPORTED_NODE`. The
  palette dims non-lowerable entries; a grandfathered pipeline containing them gets a **warning
  banner, not read-only** — deleting the offending Step is the only repair, and read-only would lock
  it out.
- **`authorable`** = lowerable ∧ not read-compat-only (`PipelineEditable.isAuthorable`). A retired
  type (e.g. `transform.dedup.marker`, folded into the acquisition Step) stays `lowerable` so an old
  graph still saves, but is never offered again. The flag is optional on the client type: an older
  server omits it and the palette falls back to `lowerable`. Read-compat is a **lower-only**
  property — nothing lifts a retired type, so its `editableConfig` branch is dead the moment the
  lift changes. Exactly two types differ on the first two flags today, deliberately: filtering the palette
  on lowerability *"would force the choice between offering a node nothing should create and refusing to
  save a graph that legitimately still carries one"* — an editor opened before a fold still holds the old graph.
- ⚠ Before refusing a type in `lower`, check whether **lift emits it**: `PipelineLift` emits
  `transform.filter` for any pipeline with row filters, so refusing it would break open-then-save.

**Config shapes are published for a subset only.** **34**<!--count:node-types-with-attributes--> of the **49**<!--count:node-types--> built-in node types have a server-published attribute
table; the rest get the typed free-form editor **by documented design**, not by omission (§4).

### 3.4 Five registration points a new Step kind must reach

The commonly-cited list of four is incomplete, and the fourth item overstates one layer:

1. **The type enum and its attribute table** — makes the type *known* and describable. Nothing more.
2. **The executor's dispatch** — a hard-coded branch per built-in. A provider seam exists, but **only for
   contributed non-built-in types**; a new built-in without its own branch compiles, saves, and throws at
   first run.
3. **The lowerable set and the chain-kind map** — the first gates whether a save may persist the node at
   all; the second maps a chain node's type to the flat file's kind string on write.
4. **The runtime lift** — ⚠ **an ordinary new kind needs no code here.** The lift derives a chain node's
   type generically from its kind string. Only route-shaped kinds need extra bookkeeping for branch and
   sink fan-out.
5. 🔴 **The Recipe compiler's per-verb switch** — the point the four-item list misses. A Step wired
   through all of the above is **still not authorable through the linear Recipe surface** without a case
   here; an unmatched verb is refused as unsupported. A sixth, weaker surface controls whether the type is
   merely *discoverable* in the served verb catalogue.

**No single test fails when a type is added without full registration.** Every enum value is
auto-registered into the known-types map, so it is immediately visible to the catalogue, while the
lowerable set, the kind map, the attribute table and the executor's dispatch are four independently
maintained collections. A missed registration surfaces as a **named refusal at save** or an **exception at
execution** — never as a build failure. That is partly deliberate: several built-ins are excluded from
authoring and lowering forever.

**One node type is executable and unreachable.** ⛔ `transform.merge` has no authoring or persistence route
at all: it is absent from the lowerable set and from the verb catalogue **by decision**, alongside four
siblings; admitting them would silently reverse those decisions, and a graph carrying one is refused at save
with `UNSUPPORTED_NODE`. It is also the **only** node type whose input *count* comes from graph structure: the
executor takes its inputs as a caller-supplied list built from the node's live inbound edges and requires at
least two (`RowShaper.merge`); every other shapeable node gets exactly one input from a single edge. A join's
second input is *config*-derived — a reference resolved by name, not a second inbound edge — and a route's
multiple *outputs* are structural while its input follows the ordinary one-edge pattern.

**The pane dispatch is a hard-coded template chain, not a registry**: three bespoke arms for acquisition,
transform-SQL and the parser family, then a generic pane for every remaining kind. A fifth bespoke pane means
a fifth arm. ⛔ A single polymorphic entry with a discriminator does not work, because lowering dispatches on
the node's *type*, never on config content; ⛔ and nothing may be keyed on the *verb*: one verb appears twice,
so only the type is unique.

### 3.5 A spec `key` IS the engine's config key — there is no mapping layer

`AttributeSpec.key` is written **verbatim** into the Step's config; the app has no case-conversion
or key-mapping layer anywhere (the only transform is `component-model/flat-keys.ts`). A wrong spec
key produces a control persisting a value nothing reads, and nothing rejects it. Rules learned
closing D1–D9 (full history: `NodeConfigNameContractTest` docblock and git history):

- **Bind a key check to the runtime that executes the file this editor actually saves.** The editor
  writes the flat `*_pipeline.toon`; "read somewhere in `PipelineLift`/`RowShaper`" is too weak —
  `RowShaper` is reachable only from the authored-graph path, and `POST/PUT /pipelines/authored`
  are 405. Use **`PipelineEditable`**, not `PipelineLift.lift`, as the left-hand side: the legacy
  lift emits a *different vocabulary* (`includes` plural vs `include` verbatim).
- **A key with zero readers may be MISNAMED, not phantom** — check for a differently-named engine
  equivalent before deleting (upsert-by-key is `reference: {load: upsert, key: [...]}`, not
  `key_columns`). **Deadness is per Step type** (`mode` is real for `transform.route`, `table` for
  `sink.persistent`) — never a global dead-key list.
- **`transform.filter` has two filtering moments — never collapse them.** Pre-parse
  `include_*`/`exclude_*` (+ `filter_target_column`) inline into the `read_csv` SELECT over ONE raw
  physical column; post-parse `where` is SQL over the mapped, typed columns. `amount > 0` is
  inexpressible as a regex over unparsed text and vice versa. ⚠ `filter_target_column` lifts only
  alongside a pre-parse list.
- The name contract is checked by **driving the save**, not comparing strings:
  `PipelineEditable.toMap → fromMap → [set key] → lower → toToon → PipelineConfig.load → assert
  sentinel` (`NodeConfigNameContractTest`). Equality is only asserted where the Step's cfg IS the
  vocabulary; `acquisition`/`sink.persistent` carry whole raw blocks and the shared tables are a
  curated subset by design.

### 3.6 Step config specs are SERVED; the client table is a fallback

`GET /pipelines/node-types` carries `attributes[]` per type (authored in
`platform/inspecto-engine/.../pipeline/NodeAttributes.java`); the drawer prefers them and
`pipelines/node-attributes.ts` is the fallback until the catalog resolves.

- **A served empty array ≠ absent**: empty = the server says the type has no schema (honoured, never
  re-enables the client table); absent = catalog not yet answered (`??`).
- 🔴 **The Step vocabulary feeds a committed contract**:
  ⚠ **It covers 34<!--count:node-types-with-attributes--> of the 49<!--count:node-types--> built-in node types** — only those that carry an attribute spec. 🔴 That gap is the root of the five-way "node types" count: `BuiltinNodeType` is the roster and this contract is the subset with attributes — two different sets, one noun. Both figures are derived by `tools/check-doc-counts.mjs`.
  `inspecto/contracts/node-attributes.contract.json` (`NodeAttributesContractTest` +
  `node-attributes.spec.ts`), regenerated with `-Dnode.attributes.write=true`. (The second one,
  `step-types.contract.json`, was retired 2026-09-24 (`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`) with `GET /pipelines/step-types`.) ⚠ `inspecto/contracts/`
  is byte-compared and `.prettierignore`-exempted by path. When a contract test fails, decide which
  side is wrong FIRST — regenerating moves the goalposts.
- ⚠ Serialization must use `LinkedHashMap` — `Map.of`/`Map.copyOf` are unordered and make the
  contract test fail at random.
- **A shared attribute table is correct per *block*, not per *node***: `COLLECTOR_ATTRIBUTES` serves
  two adopters that reach `collector:` differently, so a key can be real for one and unreachable for
  the other. Treat "declared but unreachable" as a per-adopter question; the fix is **derive the
  adopter's table (never fork it) or move the key to the Step the engine reads it from** — and ask
  where the engine reads the key before choosing ([§21 Collectors](#21-collectors-and-collector-configuration)).
- Sinks are specced only for the `output:` keys the backend reads; remaining `transform.*` types are
  **deliberately unspecced**, falling back to the typed extra-config editor (§4). Declared
  defaults **persist on save** even when untouched (product-confirmed: configs stay explicit).

---

## 4. Configuring a Step — the definition drawer

Every drawer-served kind — filter, route, join, summarize, record dedup, sinks, gap detection,
plugins, and `enrichment` with its companion write+register — configures in the right-dock
definition drawer via `pipeline-config-definition.component.ts`. (`NodeConfigDialog` is retired,
2026-08-21; only PARSE keeps a dialog path — see §6.)

- **Selection is configuration** (flipped THREE times, final by explicit operator ask 2026-08-22 —
  ⛔ do not change again without the operator): selecting a Step opens its config pane directly
  (`followSelectionIntoDefinition`); a DIRTY pane confirms before following. The slim summary
  survives only where the pane cannot serve: the read-only lens and dialog-custody parse Steps
  (auto-popping a modal stays obnoxious — those keep select-then-Configure). Palette add opens the
  new Step's pane. ⚠ `inspectorSummaryNode` was hard-wired to `null` from `57b8e4b67` until
  2026-09-25, so clicking a custody Step (e.g. the format-less `parser` Catalog ▸ Onboard Stream
  scaffolds) left the idle hint up; it now summarises exactly the non-drawer kinds, with an
  **Open Grammar editor** button (PIPE-PROPS-CUSTODY-1).
- **Identity is on the page**: the inspector's `compact` identity strip renders above all definition
  panes — Name + Description as always-visible inputs seeded from the Step, committed on blur via
  `rename` (a no-op blur emits nothing). ⚠ All panes pass the Step's own `name`/`description`
  through their build explicitly — the Step is rebuilt from scratch, so omitting them DELETES the
  name on every apply. `renameSelected` also patches the open draft.
- **Apply is armed by an edit** — each pane feeds `dirtyChange`; deriving dirty "on interaction"
  has failed twice (a derived output schema not counted; mapping edits not counted at all — the
  Load pane emitted on `form.valueChanges`; programmatic seeding ends `markAsPristine`).
  🔴 A spec for dirty-arming must drive the DOM: `setValue` does not mark a control dirty, so a
  programmatic test passes against the broken build. Apply is an **in-memory patch** (plan D2);
  Save writes the pipeline.
- **Unmodelled keys render in a typed editor**, not a generic key/value grid:
  `pipeline-extra-config.component.ts` shows each unmodelled key by name with a TYPE-matched
  validated control (boolean select · numeric · JSON textarea · text). `buildConfiguredNode` takes
  typed `extras`; pristine entries emit the ORIGINAL value reference so an untouched value (e.g.
  route `branches`) survives an apply byte-identical. Keys are not editable; adding one is offered
  only where the type has no schema. Since 2026-09-05 every definition pane's `Additional config` is a
  property-row list (label · value · pencil) and every Collector sub-form is flat — no Advanced gear, no
  chevron disclosure.
- **Flat ↔ nested bridging**: nested `collector:` sub-blocks are authored with `__` spec keys
  (`duplicate__mode`); the drawer runs `nestKeys` on save and `flattenBlock` on load. Both halves
  matter — without the load half a real nested block falls into the extra-config editor as a JSON
  string and, applied last, overwrites the schema form's own value. Save **deep-merges** each
  nested root over the Step's prior config (`mergeBlock`): several engine-read sub-keys have no
  `AttributeSpec` (`duplicate.algorithm`, `stability.size_checks`, `post_action.tags`, …), and a
  rebuild would silently destroy hand-authored TOON.
- ⚠ `connection` is stripped by both lift and lower (carried on `use:`, never mirrored in config) —
  the attribute **writes the binding**: seeded from `use` on load, written back to `use` on save. A
  Connection is NOT a `ComponentType` (`GET /components/connection` does not exist); the picker
  lives in the shared `<inspecto-collector-config>`.
- **Apply is blocked by two different things**, and only one is a server statement: a 422 from the describe
  route — deliberately the *only* server failure that may lock the pane, because an offline or unavailable
  response must never stop an author saving — and a purely local per-row problem count that involves no
  round trip at all (§8).

---

## 5. Collect — the `acquisition` Step

Renders the shared **`<inspecto-collector-config>`** over the shared `COLLECTOR_ATTRIBUTES` — mode
toggle (Local inbox | Connection | Dataset), Test connection, create-a-Connection, derived
`collector.connector`. Full contract: [§21 Collectors](#21-collectors-and-collector-configuration) below; engine side:
[acquisition spec](spec-acquisition.md).

**Marker dedup lives on the acquisition Step** (file-grain duplicate detection is decided in the
poll cycle; `transform.dedup.marker` is retired read-compat). Config keys unchanged
(`processing.duplicate_check` + `dirs.markers`):

- `duplicate_check` is an explicit boolean, authoritative in both directions — presence of
  `retention_days` is NOT the switch (clearing a detail field must not silently disable dedup), and
  an explicit `false` must not fall through to a legacy marker Step. One statement of the rule,
  `PipelineLift.markerHome`, is called by all three readers.
- The switch's label states its grain — **Marker dedup (file)** — and its help names the record-grain
  alternative (the `dedup` Step, `transform.dedup`, at rest via `output_store:`): a builder read the
  bare "Marker dedup" as covering duplicate records, which pass through it by design
  (`DUPLICATE-CHECK-GRAIN-UNSTATED-1`, 2026-09-23). `processing.duplicate_check` is **file-grain**
  (marker/path-keyed): a re-emitted record passes.
- The marker keys are their own `MARKER_DEDUP` list, NOT part of `COLLECTOR_ATTRIBUTES` — they live
  in `processing:`/`dirs:` and are only borrowed by the Step; folding them into the block table
  would hand the other adopter fields it writes to a block nothing reads.
- ⚠ `lower` dumps the acquisition Step's config wholesale into `collector:` minus `ACQ_FOREIGN_KEYS`
  — a borrowed key missing from that set lands in the wrong block silently.
- ⚠ The group renders `tier: 'required'` + `required: false` (always visible, never mandatory) — as
  `optional` it sat behind the disclosure and rendered as a heading over nothing; only driving the
  preview showed it.

⛔ **A single collector, permanently** (2026-08-11): the list design was refuted by grounding — every
stateful acquisition subsystem is keyed on one durable collector id, so a list would force a ledger re-keying
migration to reach semantics that composition already provides.

---

## 6. Parse — drawer vs dialog, the claimed slot and the sample thread

The Parse surface itself — tabs, options, columns grid, Grammar CSV round-trip, plugin formats — is
[Grammar configuration](../config/user-guide-grammar-config.md)'s. What is the editor's follows.

### 6.1 A Parse Step is three things, and the drawer is its one home (decision 2026-09-06)

Operator's model: a parse Step is **a Grammar** (how the source format is read — optional when the format is
hardcoded), **the Schemas it emits** (its events), and **its other properties**. The drawer's Parse pane is
organised on those three sections, and it is the home for every parse node the drawer can represent — including
a **dangling grammar binding**, which opens the drawer with the Grammar section flagged "template missing"
instead of falling back to the custody dialog. `GrammarEditorDialog` survives only for binary fixed-width and an
unmappable generic `parser`. **Shipped 2026-09-06 (PARSE-HOME-1):** `isDrawerParse` admits a dangling per-format
binding; `definitionDraft` seeds a blank Grammar of the node's frontend and KEEPS `use:` (Apply, not opening,
replaces the binding); the host's `danglingGrammarId` feeds the pane's `[grammarMissing]`, whose Grammar section
renders the warning naming the template. Pinned by the editor and pane specs. The stale comment claiming bound
nodes keep the dialog went with it.

**The sectioned Parse pane** (`pipeline-parse-definition.component.ts`, redesign shipped `d012f721`
2026-09-04) serves every parse subtype by `frontend`; the **grammar drawer** (`<inspecto-grammar-editor>`,
sample-as-thread: choose file → view → type → options → test → table / tree) is the one Grammar surface for the
five DuckDB-native formats, with `ParserTreeComponent` for hierarchical previews; binary fixed-width keeps the
`GrammarEditorDialog` by operator decision (its geometry lives in `ingester_config`). Previews call
`POST /config/preview/parsing` and `/config/preview/schema` (`ConfigPreviewRoutes`). Field **tiers**
(`required | optional | advanced`, D13) drive disclosure — ⚠ every `tier: 'required'` field still ships
`required: false` validators (an open observation question — [plan](plan.md)). ⚠ **The lone hard default in the
entire parser/grammar surface is `transform.route`'s `mode: 'case'`** — so "required" means *shown in the top
disclosure tier*, never *validator-enforced*. **A Grammar lives inline on its Step**; a **Grammar Template** is a
copy source (operator, 2026-08-15) — the `use: grammar/<id>` form stays read-supported, never authored; "Save as
template…" was replaced by a **Grammar CSV** round-trip (2026-08-19). The Onboarding Parsing stage **no longer
exists** (P6-e); the editor is the guided surface.

### 6.2 Custody, the claimed slot, and one sample thread per tab

- **Custody split**: a per-format parse Step (`PARSE_NODE_FRONTENDS`) defines in the right-dock
  Parse drawer; `GrammarEditorDialog` (`grammar-editor.dialog.ts`) survives ONLY for **two** shapes —
  binary fixed-width, and a generic `parser` whose config maps to no frontend (`isDrawerParse`). A **dangling**
  `use: grammar/<id>` opens the DRAWER (§6.1).
  **A parser is always format-specific — never author the generic type** (operator directive
  2026-08-21; D3 2026-08-31: a new pipeline asks for the parser format). A generic `parser` whose config maps to a built-in frontend migrates on edit:
  `definitionDraft` presents it re-typed with `csv_settings` folded in under the engine's own merge
  precedence (`parsing:` wins), and Apply converges the Step on the specific type. Fail-closed to
  the dialog otherwise.
- **The parse slot is claimed, not queued**: a flat config has ONE `parsing:` block, and every new
  pipeline lifts with a generic `parser` placeholder. `claimParseSlot()` runs before either palette
  entry point — an untouched placeholder is **re-typed in place** (same id, both edges intact); a parse
  Step carrying config is authored work and the add is REFUSED naming the format that holds the slot.
  Predicate: `isParseNodeType` — ⛔ never the catalog CATEGORY (the rule must hold before the catalog
  resolves). ⛔ This is NOT auto-connecting: an ordinary Step still landed unconnected on purpose
  (considered and declined). 🔴 **That decline is AMENDED (D8, signed 2026-09-22).** It rested on the Recipe
  view's insert-between as the author's alternative; `<app-pipeline-step-cards>` was deleted in `6d3c68fa`
  (2026-09-18), so there is no one-gesture insert anywhere and inserting mid-chain costs three graph
  operations. The narrow reopening: when a node **is selected**, an added Step is wired **after** it —
  the selected node's outgoing `data` edge is rewired through the new node; with nothing selected the
  bare add stands, because there is no anchor to guess from. ⚠ Validation keeps catching orphans either
  way (*“has no input connection”*), so this is ergonomics, not integrity. Tracked as `WB-14`; row
  `PALETTE-ADD-DROPS-ORPHAN-1`.
- **One sample thread per tab**: `DefinitionStateService` instances live in a `Map<id, …>` beside
  `cachedModels`, born in `select()`, dropped by `forgetTab()`. 🔴 A Map, NOT a `providers:` entry —
  DI providers are static per component instance and this editor is ONE instance hosting every tab.
  `InspectoSamplePanelComponent` takes the thread as an **input** (also keeps it pure — the D2
  rule every definition pane follows). ⚠ Reading the Map inside the `sampleThread()` computed does
  not track it — deliberate; `selectedId` is the only dependency that can change the answer.
- **The Grammar dialog shares the thread too** (2026-09-25). A new Pipeline's placeholder parse Step is
  a generic `parser` with no format, so its first configuration happens in `GrammarEditorDialog`, whose
  editor runs `sampleMode: 'own'`. It used to keep that sample to itself: a builder pasted and
  test-parsed there, pressed Save, and found the drawer's Sample card empty and the Record Transformer
  offering 0 fields until the sample was pasted **again**. `openNodeConfig` now passes the tab's
  `sampleThread()` as `GrammarEditorDialogData.sampleThread`; the dialog seeds its sample box from it,
  captures the sample into it on a **table** Test parse and on **Save** (a no-op when it is the sample the
  thread already holds — re-capturing resets every downstream result), and its `previewFn` writes the
  parsed rows exactly as the drawer's does, **clearing them on a failed re-parse**. The captured sample is
  named *"sample from Edit Grammar"* on the Sample card.
- 🔴 **The Grammar dialog's Save writes the Schema, exactly as the drawer's Apply does** (2026-09-25,
  found driving a new Pipeline `web_orders`). Save used to patch the node in memory only, so after Test
  parse (6 cols) → Save the strip still read *Schema: Not configured*, Activate was refused on Schema,
  and the drawer then opened over the carried sample with **Apply disabled** — no visible way to create
  the schema. Now, with a table Test parse (or the thread's parse it opened over), a flat format, and no
  `schema_file` on the node yet, Save writes `<pipeline>_schema` FIRST and closes naming it in
  `schema_file`; a failed write keeps the dialog open with an error alert (the node never names a missing
  file). ONE write, not two: `parse-output-schema.ts` holds the derivation (`derivedSchemaRows`,
  `inferredSchemaTypes`) and the re-read-then-write (`writeParseSchema`, with the SCHEMA-FILE-NAME-1
  `file:` naming and the partitions/extras carry-through) that `PipelineParseDefinitionComponent.submitWithSchema`
  also calls. A node that already names a schema is left alone (the drawer re-reads, never re-derives
  over a saved one). The thread's `ParsingPreview` now carries the parse's `columnTypes`, so a surface
  opening on it derives the same typed schema.
- **A derived field name keeps the sample header's case** (`FIELD-NAME-CASE-1`, builder pilot
  2026-09-25). `sanitizeIdentifier` (`inspecto/schema/schema-fields-editor.component.ts`) used to
  uppercase every parsed column, so a lowercase CSV header (`match_id,date,…`) was written as
  `MATCH_ID, DATE, …` and — since the declared name IS the landed column — the Parquet and every UI label
  came out shouty. Nothing downstream needed it: `Identifiers.validate` accepts `[A-Za-z_][A-Za-z0-9_]*`
  and the engine writes the declared name verbatim. It now rewrites only what the rule refuses (spaces,
  punctuation, a leading digit). The grid's duplicate check folds case instead, because DuckDB does: `id`
  and `ID` are one landed column. Schemas already saved keep their uppercase names; nothing rewrites them.
- **The Parse drawer arms Apply over a carried parse.** Opened on a node with no `schema_file` while the
  thread holds a table parse of the pane's own frontend (the dialog's, or its own before a Discard), the
  pane derives the schema from it through `onPreviewed` — the one derivation — so it reads *unapplied*
  and Apply writes it. Before, the thread's parse was not a form edit, nothing was dirty, and Apply stayed
  greyed out over "parsed · 6 cols · 4 rows".
- The strip mounts in the **parse drawer** (where the sample is consumed) and supplies the grammar
  editor's `previewFn` — a function because `previewed` fires on SUCCESS only, and a failing
  re-parse must not leave a stale "parsed · N cols" chip standing. Only a **table** result feeds
  the thread (parsed = "rows a downstream step can cast"); a record tree leaves it untouched.
  `Parse sample` lives in the sample strip; the FIRST derivation **expands the `types` section** —
  labelled *"How values are understood"*, a `mat-expansion-panel`, not a tab — via `showTab('types')`,
  which since the sectioned redesign means "expand";
  **Re-derive from this sample** confirms destructively naming the loss and is implemented as
  "clear `schemaHydrated` and re-run the parse" so one derivation path exists.
- **Write-ordering guards** (both falsified by deleting them and watching their tests fail):
  a saved schema is re-read on open and a fresh parse never re-derives over it (the operator's
  names/types/includes are the truth); a hand-authored `schema_file` outside the naming convention
  is reported and never touched.
- **Drift is reported, never applied** (`POST /config/suggest/schema`): only *adding new columns*
  is automatable; a type change and a missing field are human calls, shown old→new. The diff joins
  on the **selector**, not the name (renaming an output column is deliberate). ⛔ There is no
  `renamed` category and cannot be one — from draft + sample, a rename is indistinguishable from
  remove+add.
- Type suggestions come from the server (`SchemaSuggest`); `narrowToSchemaType()` maps its wider
  vocabulary (`BIGINT → DOUBLE`, unknown → `VARCHAR`) onto the four types
  `TransformCompiler.direct()` casts. The **selector** stays client-derived (frontend-dependent;
  the server sees only rows). The shared `raw.fields[]` grid lives in `inspecto/schema/` (a feature
  may not import a feature); hosts seed `[rows]`, own every write, and reach it via a **signal**
  `viewChild`.
- **Parse pane companions (`d012f721`):** the Parse drawer is sectioned (not tabbed), flat compact rows, one
  "Columns that come out" table; **partitioning moved from Parse to the Sink pane** (§10) and the Collection
  pointer is read-only on Parse — see [grammar configuration](../config/user-guide-grammar-config.md). The Parse
  checkbox that appends a read-only `file_name` row is default OFF (§6.3).
- **"Sample resolves to"** (AUTHORING-REDESIGN-1 (i)) shipped on the **Parse** pane's property rows
  ([grammar configuration](../config/user-guide-grammar-config.md) § *The sectioned Parse pane*).

### 6.3 Cross-Step fields on the Parse pane

- **`output.filename_column`** (source-file lineage) genuinely lives on the SINK. Pattern for any
  cross-Step field: the HOST resolves the target through the same `primaryOutputSink()` guard the
  enrichment seed uses (never a second derivation); `null` ⇒ the pane renders nothing, never
  guesses. The pane stays pure (`[filenameColumnTarget]` in, `(filenameColumnChange)` out); inline
  validation uses an explicit `role="alert"` paragraph — **never `<mat-error>`** (no `NgControl`,
  so the field never enters error state). The host commits through `applyNodePatch` (the immediate-
  write precedent canvas rename set). New pipelines seed `output.filename_column: file_name` in
  `pipelineScaffold()` — the one choke point both create surfaces use; a scaffold fact, never an
  engine default (which would silently grow every existing store). The lineage column is shown
  read-only in the `types` section's columns table — ⅋ never a "Column metadata list", which left Parse
  entirely with the metadata grid (D2) — (it IS an output column, stamped at write
  time) — never as a fake `schemaSeed` row, which risks being written back as authored.
- ⛔ **A created Pipeline stays FLAT at the space config root — `config/<id>/` was REFUSED (D3,
  signed 2026-09-22, 🔴 REVERSED 2026-09-23).** The scaffold writes `<name>_pipeline.toon` beside its
  satellites at the root, and that is deliberate. The decision was signed on the premise that *every
  shipped Pipeline lives in a per-Pipeline directory*; counted on disk that is false —
  `config/orders/` holds THREE Pipelines and the directory names are DOMAIN words, not ids
  (`config/postmed/` → `postmed_xdr`). Nesting by id would have added a THIRD layout.
  ✅ What the complaint cared about already holds: **the Pipeline and its satellites land together**,
  and an author who wants a domain home passes `subdir:`, which works today.
  ⚠ It was implemented and reverted TWICE. The second attempt found the cost: a bare
  `schema_file: <name>.toon` resolves BESIDE ITS OWN CONFIG, so nesting the Pipeline without moving
  its schema separates them and the save warns its own schema does not resolve.
  ✅ One durable gain survives: `ConfigFileSupport.resolveConfigFile` now finds a config in
  `<dir>/<name>/` even when nothing has registered it, so the per-DOMAIN homes every shipped Pipeline
  actually uses are reachable to read/patch/delete. Row: `UI-CREATED-PIPELINE-FLAT-HOME-1` (closed).

### 6.4 Parse traps a builder cannot see in the form

Fixed-width: `start` is a **0-based** offset (1-based counting shifts every field one character and parses
"successfully"); `has_header` defaults `true` for `fixedwidth` too, silently eating a line — a server-published
default, deliberately not flipped (migration, not UI fix). Every `parsing:` key, per frontend, with its refusals:
[ingest spec — parsing](spec-ingest.md).

---

## 7. Schema authoring

> The Parse pane's own columns table ("Columns that come out"), its sections and defaults are in
> [grammar configuration](../config/user-guide-grammar-config.md); the standalone `SchemaEditorDialog` below is unchanged
> since the 2026-09-04 authoring pass ([`parse-pane-redesign-plan.md`](../../../../archived-documents/plans-archive/parse-pane-redesign-plan.md), `d012f721`).

### 7.1 Where the schema lives

- **`processing.schema_file` is the PARSER Step's key** (where `PipelineLift` puts it and
  `PARSER_NO_SCHEMA` checks it) — the Parse drawer authors the output schema.
- ⛔ **No single drawer over schema + mapping + table** — it would span three Step types and break the
  one-node-in/one-node-out contract every drawer holds (operator, 2026-08-16).

### 7.2 The schema editor — `SchemaEditorDialog`

- **Component:** `SchemaEditorDialog` (`schema-editor.dialog.ts`) — a flat grid over
  `<inspecto-editable-grid>` with columns name / selector / **type** / description / unit / classification.
  Type is a **free-text DuckDB SQL type**, deliberately not a dropdown (comment).
- **Both hand-typed and inferred**: rows can be typed directly, or "Suggest from sample" calls
  `ConfigService.suggestSchema()` (`POST /config/suggest/schema`, TRY_CAST voting) against `data.sampleRows`
  captured by an earlier Test-Parse. Inference only fills the grid as a draft — nothing
  persists until Save. A suggested DATE fills the **Date format** column (`raw.fields[].format`,
  a strptime pattern such as `%B %d,%Y`) — without it a non-ISO date lands NULL; see §7.4. For a column holding an
  epoch count (`1700000000`, with `0` for none), declare the type `TIMESTAMP` and type `epoch` (seconds) or
  `epoch_ms` (milliseconds) in **Date format**: it lands as the UTC time and `0` lands empty — no `sql` step needed
  ([ingest spec — parsing](spec-ingest.md)).
- **Suggested selectors follow the frontend** (LA-ONBOARD-FIXES-1, F-4): the route's optional body key
  `frontend` (the value `/config/preview/parsing` reports) picks the selector. Delimited — the default when
  it is absent — and fixed-width get the column's **zero-based index** in the sample's order, because their
  load refuses a name; json / text_regex / xlsx / parquet / plugin keep the column name; an unknown value is
  a 400. The grammar editor's onward link passes its active frontend through `SchemaEditorData.frontend`
  (`plugin` for a plugin grammar). The save gate backs it: a pipeline write whose delimited/fixed-width schema still selects by name is
  refused `ERR_SELECTOR_NOT_INDEX` (active or not — it would not load), with the load's own message.
  The drift diff (`config` in the body) follows the same rule (`LA-ONBOARD-DRIFT-1`, 2026-10-23): for an
  index-addressed frontend (delimited, the default, and fixed-width) a selector of digits is the sample column
  at that POSITION, so an up-to-date index-selected draft is not drifted; any other selector is still a column
  name. ⚠ The Parse pane's drift check sends no `frontend`, so the server reads it as delimited — a json schema
  whose key is all digits would be matched by position there. A **schema written on its own**
  (`/config/write` or `/config/patch`, type `schema`) that a delimited / fixed-width Pipeline already names
  (`schema_file` or `schemas[].schema_file`, resolved beside that Pipeline, a draft whose schema does not exist
  yet included) is refused `ERR_SELECTOR_NOT_INDEX` when a selector is a name — judged by loading each such
  Pipeline against the candidate schema (`SaveGate.referencedSchemaSelectorFindings`), before the BACKWARD gate.
- **Inference (a separate, upstream endpoint):** `POST /parsers/{id}/preview` (`ParserRoutes.java`)
  runs a DuckDB `auto_detect` sniff and returns per-column types. Stateless — writes nothing.
- **Save:** `ConfigService.write('schema', config, {overwrite:true, ...compatibility})` →
  `POST /config/write type=schema`, persisted as `<name>.toon` (the typed Name — ⚠ **not** `<name>_schema.toon`,
  and nothing points the parse node's `schema_file` at it: this onward link "saves itself, node untouched",
  so a Draft Schema… named anything but the node's referenced file is a file no pipeline loads; the Parse
  pane's own Apply — and, since 2026-09-25, the Grammar dialog's own Save after a Test parse, through the
  same `writeParseSchema` (`pipelines/parse-output-schema.ts`) — is the write that keeps the two together,
  `SCHEMA-FILE-NAME-1`) beside `<name>_mapping.csv` and — since
  STRUCTURE-CSV-1 (2026-09-06) — `<name>_structure.csv` for `raw.fields` (`StructureCsv`, `ConfigFileSupport`),
  both merged back on read so the UI always sees the conflated document. A field with a key the CSV cannot hold
  (`timezone`, `partitions`, …) keeps the whole list inline; the sibling is then removed.
- **Editing an existing schema is real and gated:** a backward-compatibility diff,
  `SchemaCompatibility.check(existing, draft)` (`platform/inspecto-config/.../safety/SchemaCompatibility.java`),
  flags removed fields, non-widening type changes, and moved selectors as ERROR findings, mapped back onto
  grid cells by field name (`schema-editor.dialog.ts`) with an inline alert and a "Save
  anyway (skip compatibility check)" escape hatch (`compatibility:"none"` param,
  `ConfigWriteRoutes.java`). ⚠ **The BACKWARD gate has ONE documented escape hatch: a breaking overwrite is
  refused 422 unless the write carries `compatibility: "none"`.** Without that spelling an operator meets a 422
  with no stated way forward.
- **This gate protects the schema file only — it never reads the sibling mapping CSV.** `SchemaCompatibility`
  operates purely on `raw.fields` diffs and does not cross-check `MappingCsv`'s `targetColumn`/`sourceExpression`
  values; it only prevents the schema-side edit that *would* orphan a mapping rule, and that protection is
  bypassable via `compatibility:"none"`. Since 2026-09-06 `SchemaMappingDrift` closes the gap on the mapping side
  (§8.9, gap 4).
- **The generic downstream-staleness warning** is prose in the Parse-definition pane when a parse's produced
  columns no longer match the saved output schema (`pipeline-parse-definition.component.ts`, "anything downstream
  that expects the old columns will need updating" + a "Replace the output schema" button). It fires on a
  *parser format* change, never on a manual edit made through `SchemaEditorDialog`, and never names which
  mapping rules would break.

### 7.3 The derived output schema beside the authored one (`DERIVED-SCHEMA-PANEL-ORPHAN-1`, shipped 2026-09-15)

`DerivedSchemaPanelComponent` — a live `GET /config/schema/derived` consumer that was mounted NOWHERE for two
days — is wired into `SchemaEditorDialog`. `pipeline` threads pipeline-editor → `GrammarEditorDialogData` →
`SchemaEditorData` → the panel, because the route derives from the REGISTERED identity (`pipelineId()`), so it
was three levels of plumbing rather than a template edit. ⛔ Hidden for `home: 'registry'` — a registry schema
belongs to no pipeline, and an empty panel would read as "this pipeline writes nothing". ⚠ Labelled **"last
saved"**, and the label is load-bearing: the route derives from the SAVED config while the dialog edits a draft,
so mid-edit it shows the schema before the current edit. A test pins each direction.

### 7.4 Date formats, epoch timestamps and the source time zone

**Per-field date format (2026-09-25).** `raw.fields[].format` is a DATE/TIMESTAMP/TIMESTAMPTZ field's own
strptime pattern (`%B %d,%Y` for `March 22,2025`). When set it **replaces** — never joins — the pipeline's
`date_formats`/`timestamp_formats` for that field (`SchemaFieldTypes.formatsOf/formatsFor`), in the data
column (`RecordTransform.compile`), its `DATE_*` partition and event time (`TransformCompiler.dateExpr`) and
the cast-failure audit alike: a shared list trying other patterns first is how `%d/%m/%Y` silently reads as
`%m/%d/%Y`. A value the format does not match follows the existing coercion policy — NULL, row kept, counted
by `countCastFailures` (there is no reject/quarantine for coercion failures). `Identifiers.validateSchema`
refuses a format on a non-date (or absent) type, one with no `%` directive or a single quote (it is inlined
into a SQL literal), and a zone directive. `POST /config/suggest/schema` emits it: `SchemaSuggest` tries
`DATE_FORMATS` on a column no bare cast accepts, and a DATE carries the format it was proven with; a sample
under which two formats give different dates (`03/04/2025`) gets **no** format and stays VARCHAR. ⚠ DuckDB
strptime matches whitespace exactly, so a column mixing `March 22,2025` and `March 22, 2025` fits no single
format and is suggested VARCHAR. `SchemaExtractor` regeneration does not preserve `format` (it re-infers).

**Epoch counts (2026-11-15, `MAPPING-EPOCH-TIMESTAMP-1`):** the reserved values `epoch` / `epoch_ms` on a
`TIMESTAMP` field read a count of seconds / milliseconds since 1970 as its UTC wall clock — no source zone
applies, `0` is NULL and not a counted failure, a non-integer is a counted NULL; rules and reasons in
[ingest spec — parsing](spec-ingest.md).

**Time zone of the incoming data.** Precedence `raw.fields[].timezone_column > raw.fields[].timezone > parsing.source_timezone >
none` (`SourceZones`); `TIMESTAMPTZ` with no zone source and `%z` / `%Z` formats are **refused at config load**;
`FILENAME_DATE` and `DATE` are zone-exempt (a date has no instant to shift). The DuckDB session's own zone is the
host's and is a different rule. Mechanism: [engine — DuckDB](../engine/spec-duckdb.md).

### 7.5 Wide feeds — D8 and D9 on the schema side

Decided 2026-09-04 after driving the clickable mockup (Artifact *Pipeline Authoring Redesign*,
`b5e7ec6c-5bb2-467a-a513-93bfda987f3d`), built the same day (`4e64fe4a`, `c769719d`), verified 2026-09-06.

🔴 **What prompted them: the mockup's sample was chosen to flatter the design.** Both boards were drawn
over a 7-column orders CSV, where "one row per field" reads as clarity. This product's real feeds are not
that shape — ASN.1, fixed-width and network sources routinely carry hundreds of fields — and at 600 the
same table is a wall. The evidence was already in the repo (the parser lanes, the **122**<!--count:processors-->-processor catalog — corrected 2026-09-09)
and was not applied. Both mockup boards now carry 65 columns instead of 7. ⚠ The BACKLOG row `AUTHORING-WIDE-1`
said "none built" for two days after — a fresh grep, not the row, is the source of truth ([[stale-residuals-in-shipped-rows]]).

**D8 — Parse does NOT drop columns; Transform owns exclusion.** The Parse pane settles **what a column is**:
that it exists, its name, its type, its synonym. It does **not** offer include/exclude (Parse's columns table
carries no include control; `SchemaFieldRow.include` survives only for Grammar-CSV round-trips). Leaving a field
out of the output is `transform.sql`'s job and only its job, so an author hunting a missing field has exactly one
place to look. ⚠ **The reason is cost, not tidiness.** The two affordances looked identical and were not:
excluding in Parse edits the **schema**, which `SchemaCompatibility` gates on a BACKWARD contract (§7.2) — so
undoing it can be *refused*, or force a re-test. Excluding in Transform is a clause in the SELECT: reversible for
free. The expensive one is simply not offered. 🔴 **Knock-on, deliberate:** the stored schema is now always the
full column set, so the Catalog will list columns that never reach a sink. Anything reading the schema as "what
lands in storage" is reading it wrongly — the schema describes the **file**, the SELECT describes the **output**.

**D9 — Both field tables are built for a wide feed.** Search over name and synonym (Parse also matches the
selector); a page-size choice that reaches down to 10. ⚠ The **filter idiom differs by grid**: Transform has
view-only filter chips **with counts** (All · Changed · Calculated · Needs attention), Parse has a **type dropdown
with no counts** — a type sweep is the question a wide schema asks. ⚠ The two grids deliberately **do not** share
a default: Transform pages `[10, 20, 100]` defaulting to 10 because its rows are tall (a function select plus
per-parameter controls), Parse `[10, 25, 50, 100]` defaulting to 50 because its rows are dense one-liners and a
type sweep wants many at once. Symmetry here would cost more than it buys. 🔴 **`#` is the position in the FULL
list (`seq`), never the row's index in the current view** — on Parse it is the physical column position in the
file, so renumbering it under a filter would be a lie; page 2 starts at #11. The Parse sample values render inline
per row, so they cover only the current page by construction; there is no separate caption saying so.

---

## 8. Transform and map — the Record Transformer

### 8.1 The projection slot is a Record Transformer; two nodes, one Step kind

> **Two nodes, one Step kind (TRANSFORMER-TWO-NODES-1, operator 2026-11-15).** The projection slot is a
> Record Transformer **instance** named **"Shape &lt;table&gt;"**, or **"Shape records"** when no table is known
> (`PipelineLift`; before 2026-11-15 the fallback name was "Record Transformer", the Step kind's own label).
> It saves as `processing.map.fields[]` and runs at ingest, in Stage 1. A **Record Transformer added from the
> palette** (`transform.record`) is a different node: a chain `sql` Step (`steps: - sql:`) that runs **after
> load**, at rest, from a `type: pipeline` Job writing the top-level `output_store:`. A chain with no
> `output_store:` never runs, so the save refuses it naming each Step (`ERR_CHAIN_WITHOUT_OUTPUT_STORE`, 422
> on an active Pipeline; `WARN_CHAIN_WITHOUT_OUTPUT_STORE` on an inactive draft). With a store but no enabled
> `type: pipeline` Job naming the Pipeline's file in `pipeline_config:`, the chain never runs either, and the save
> warns (`WARN_CHAIN_WITHOUT_PIPELINE_JOB`) until you add that Job. To shape what lands, edit the
> "Shape …" node; add a palette Record Transformer only to re-shape the landed store into another one.

**2026-09-05 — `transform.map` is DELETED.** The projection slot `PipelineLift` fills between parser and
sink is always a **Record Transformer** (`transform.sql`, id `map` / `map_<key>`); `BuiltinNodeType.TRANSFORM_MAP`,
the `map` recipe verb, `TransformCompiler`'s rule path and the UI's Load pane are gone. A stored
`mapping.rules[]` still LOADS: `DataTransformer.recordFields` converts it to `fields[]` through
`RecordTransform.fromMappingRules` (DIRECT → `keep`, EXPR → `custom`, CONCAT_DT → `date.concat_parts`,
FILENAME_DATE → `date.from_filename`), and `PipelineLift` does the same for `processing.map.rules`. Every
schema under `spaces/` was migrated by `MappingMigrator` (block-list TOON form when a row needs `args`).
⚠ On the ingest lane `date.concat_parts` compiles exactly as CONCAT_DT did (parser timestamp formats +
the date column's source zone) — the same lane-specific special case `keep` has. Plan:
`archived-documents/plans-archive/delete-transform-map-plan.md`. **The legacy spelling is bridged at read time,
permanently**: converted on **every read**, never rewritten to disk; that is why keeping the old read path costs
nothing and why deleting it was refused — migration is by tool, never by breaking stored schemas.

Facts that once named `transform.map` now read as "the projection slot (`transform.sql`, id `map`)":

- `MAP_AUTHORED` (`columns`/`rules`/`fields`) lowers to `processing.map`; `schema`/`csv` on the slot are
  `MAP_DERIVED` — engine-resolved, dropped by `lower`.
- 🔴 The slot is on EVERY lifted graph — readiness logic keys on **authored evidence**, never that Step's
  presence or status (`isProjectionSlot` in `pipeline-editable.ts` is the discriminator — the id grammar, never
  the type: **map is a slot, not a step**). Since 2026-09-25 an empty slot reads `configured`, not
  `unconfigured` (`computeNodeStatus` exempts `isProjectionSlot`): it is the pass-through, and the old "Needs
  config" chip on every new Pipeline's Record Transformer was false.
- **The projection path.** The executor dispatches a transform node on whether it is a **projection** — decided
  by the node carrying record fields, not by its type — sending it either to the projection path or to the
  verbatim-SQL path. The projection path prefers an authored column list outright; otherwise it derives one from a
  schema, from `fields`, or from legacy rules. ⚠ **An authored column list beats both `rules` and `fields` when
  several are present**, and nothing refuses authoring more than one — the only collision refused is a column
  list together with a declared mapping file.
- **Test mapping is gone** (removed with the Load drawer and `transform.map` in `42fa41fe5`; nothing in
  `inspecto-ui/` names it). A mapping is previewed in the standalone Mapping component
  (`mapping-editor.dialog.ts`): *Preview with sample data* dry-runs a synthesized graph with the edited
  rules carried inline. The board row "P4 Test mapping on a generic `parser` node" was deleted as
  obsolete on 2026-11-12.

### 8.2 The `transform.sql` pane — a fields grid over a function catalog

`PipelineTransformSqlDefinitionComponent` (`pipeline-transform-sql-definition.component.ts`, shipped `7e13dd82`;
engine half `98ffc90b`; plan [`sql-transform-v1-plan.md`](../../../../archived-documents/plans-archive/sql-transform-v1-plan.md)),
a bespoke pane reached from the editor's routing arm `dn.type === 'transform.sql'` → `<app-pipeline-transform-sql-definition>`
(`pipeline-editor.component.html`). The `transform.map` arm and `<app-pipeline-load-definition>` were DELETED
2026-09-05 (the projection slot is a `transform.sql`, so it opens this pane); every other `transform.*` kind stays on
the generic `pipeline-config-definition` schema-form. A node TYPE routes to its own pane — the rule that already held
for map/parse/sink. ⅋ The pane is **bespoke — NOT the shared fields editor**: it does not import
`<inspecto-schema-fields-editor>`. Engine half: [Pipeline spec — model](spec-pipeline.md).

🔴 **This surface flipped twice on 2026-09-04. The SQL-only middle state is NOT current.** The Step
shipped SQL-only that morning (`24171333`, which deleted the 2026-09-03 fields grid), and the operator
rejected it the same day: *"mapping with functions and parameters is not realistic"* — a raw SQL box is
not an authoring surface for a non-technical user, and the legacy `transform.map` rule grid (four
`transformType` constants, two of which pack their arguments into a `|`-delimited string) is not a
mapping model either. The grid is back, and its verb enum is replaced by a **function catalog with typed
parameters**. D3 (no `where` — filtering stays `transform.filter`), D4 (never parse SQL back into rows — **superseded 2026-09-05** by the bounded reconciler, below)
and D7 (600+ columns stay usable) still hold; D5's five-verb list and D6's lock are superseded by the
catalog and by persisting `fields[]`. (The old SQL-only wording, kept for the record: one SQL `<textarea>` seeded
for a new Step with an explicit column list over the upstream sample columns, else `SELECT * FROM input`, with
"Columns that come out" in the shared fields editor fed from the test run's DESCRIBE `columnTypes`.)

- **The function catalog** (`sql-functions.ts`) is the vocabulary and the ONE place a function's plain
  label, its typed parameters and its SQL template live together. `SqlFunction = {id, label, category,
  template, params}`; `SqlFunctionParam = {name, label, type, default, options, optional}` where `type`
  drives BOTH the rendered control and the escaping: `column` → quoted identifier, `text` → single-quoted
  literal with quotes doubled, `number` → validated then verbatim, `enum` → one of `options` verbatim,
  `sql` → verbatim (the deliberate escape hatch, `custom` only). **23**<!--count:sql-mapping-functions--> functions *(corrected 2026-09-09 from `sql-functions.ts`: 1 Keep, 8 Text, 4 Numbers, 6 Dates, 2 Logic, 1 Convert, 1 Custom — earlier pages said "~20" and "24")* across Keep · Text ·
  Numbers · Dates · Logic · Convert · Custom. A row's source column binds to the template's `{source}`
  automatically — that IS the operator's "auto map parameters against sql functions".
  ⚠ **Every conversion is `TRY_CAST`, never bare `CAST`, and division guards with `NULLIF`** — a
  catalog-wide unit test asserts it, because a bad cell must null one cell, not kill the batch.
  ⚠ **A `text` parameter is deliberately not trimmed** — a single space is a legitimate separator for
  "Join with another column"; trimming reported a valid row as missing its separator (caught by a test).
- **The grid** (mockup layout verbatim): `#` · Field name · From · What to do · Comes out as · Sample ·
  leave-out, over a search box and view-only filter chips with counts (All · Changed · Calculated ·
  Needs attention), under a 10/20/100 pager, with "Add a calculated field" and a **Left out** tray whose
  chips put a column back. `#` is the position in the FULL ordered list, computed before filtering and
  paging, so page 2 starts at 11 — search, filters and paging are lenses that never change what is
  written. A function's parameters render as controls beneath its picker on the same row.
- **Compilation** (`pipeline-transform-sql.ts`, pure, unit-tested): `compileField` → `{expr}` or
  `{problem}`; `generateSql` emits `<expr> AS <name>` per complete row over the fixed `input` relation
  and **skips a row with a problem so the generated SQL always parses**, while `canApply()` is false
  until every problem is cleared — an incomplete row can never be saved silently.
- **Persisted shape** (`submit()`): **`{ sql, fields }`** — `submit()` writes both on every Apply and `seedFrom`
  rehydrates the grid from `fields`. 🔴 It is NOT `{ sql }` only, and a stored `fields[]` is NOT dropped.
  `readFields` also opens a node written by the retired five-verb grid, mapping `verb`/`castType`/`formula` onto
  catalog ids. 🔴 **Except in the projection slot** (`isProjectionSlot`, id `map`): there `submit()` writes
  `{ fields }` only (SQL view → the reconciler's rows; a non-projection is refused by `slotSqlRefusal`,
  which blocks Apply), and an empty grid writes `{}` — the pass-through. `processing.map` has no home for
  `sql`, so sending both made every new Pipeline's first Save 422 `UNSUPPORTED_MAP_KEY` (2026-09-25).
- ⚠ `sql` is the single `NodeAttributes` entry — but that is the DECLARED attribute schema, **not** the
  set of keys the engine reads. 🔴 `fields` is a live executable contract: `RowShaper`'s
  `MAP_NODE_CONFIG_KEYS` includes it and runs it as the mapping, and `PipelineEditable.MAP_AUTHORED`
  allow-lists it precisely so `lower` cannot drop it — *"a key that becomes executable without joining this
  allow-list is silently dropped on save"*. ⅋ Do not "clean up" `fields` off a `transform.sql` node:
  that deletes the author's grid and, on the projection slot, its mapping. (An older page called `fields` an
  authoring artifact that "rides the `steps:` chain opaquely" — superseded; see the [plan](plan.md) §2.4.)
- **Fields and SQL are two views of ONE Step (2026-09-05, operator decision; supersedes D4).** The pane's
  header carries a `Fields | SQL` segmented switch (the toolbar's Recipe | Canvas idiom). Fields → SQL is
  the compiler: the generated SQL lands in CodeMirror (`<inspecto-sql-codemirror>`, editable — never a
  `<textarea>`). SQL → Fields is the **bounded reconciler** (`pipeline-transform-sql-reconcile.ts`, pure, 21 specs):
  it accepts exactly a flat `SELECT <item>, … FROM input` projection and turns every item into a row — a bare
  column is *Keep as it is*; an expression matching a catalog template in reverse gets its function and parameters
  back, **but only when re-compiling that row reproduces the expression** (whitespace/keyword case aside — a
  recognition that would rewrite the SQL is not a recognition; bare `CAST` therefore stays custom, never becomes
  `TRY_CAST`); anything else is *Write my own expression* with the text verbatim; an unaliased expression keeps an
  empty name so the grid asks for one. Anything beyond a projection — `WHERE`, `JOIN`, `GROUP BY`, CTE, `DISTINCT`,
  `*` mixed with items — leaves the **Fields tab disabled with the reason** as its tooltip and a line under the
  editor, and never blocks saving (D3 unchanged: filtering belongs to a Filter Step). It is NOT a SQL parser and
  needs no DuckDB extension; a `WHERE` is made structurally visible on the Filter Step instead (§8.6).
  ⚠ **Switching views never changes the SQL; only an edit does.** Dirty is the generated SQL compared to what
  was loaded (not the rows), so Fields → SQL → Fields arms nothing — except that a hand-written SQL shown as
  fields regenerates in canonical spelling, which honestly arms Apply. A node carrying `sql` with no `fields`
  opens in the **SQL view, as written**, with Fields one click away; an Apply from the SQL view writes
  `{ sql, fields: [] }` so it reopens as SQL — no grid is invented behind the author's back, the reconciler
  offers it again from there. **Hand-written SQL is always saved back**, whether or not it reconciles. The
  destructive "Start a field list from the incoming columns" button is gone.
  ⚠ Editing hand-written SQL saves — since 2026-09-04: binding `valueChange` straight to the signal once
  emitted no `dirtyChange`, and `canApply()` once refused the SQL mode; both closed (`onSqlEdited` →
  `touched()`; the binder check is what guards what is typed).
- **Opening a Step never arms Apply.** Seeding emits `dirtyChange(false)`; dirty is a real comparison of
  `{sql, fields}` against what was loaded. ⚠ This was a live defect on the legacy `transform.map` pane
  (a Step showed "unapplied" from a single click with no edit); the grid has a regression test for it.
- **Test against sample** reuses "Test this Step" verbatim: `ComponentsService.previewTransform({type,
  sql}, sampleRows)` with the GENERATED sql — `transform.sql` qualifies by the `transform.*` prefix. The
  run fills each row's **Sample** value.
- **Types arrive without a run (2026-09-04, BACKLOG (a) closed).** A 300ms-debounced effect posts the
  generated SQL and the upstream columns to `POST /components/transform/describe`
  (`ComponentsService.describeTransform`, over `TypeFlow.describe`'s execution-free `DESCRIBE`) and fills
  **Comes out as** from the answer; a preview run's types are the fallback, not the source. The debounce
  timer is cleared on `DestroyRef.onDestroy` so a drawer closed mid-keystroke fires nothing.
- ⚠ **The DESCRIBE is sent the DECLARED upstream types** (`[upstreamColumnTypes]`, read off the parser
  companion's `raw.fields[]` by the editor and keyed by name). Assuming VARCHAR for every column made
  DuckDB REFUSE valid SQL — `AMOUNT * 2` over a declared DOUBLE answered "No function matches …
  *(VARCHAR, INTEGER_LITERAL)" — and that refusal blocked Apply. A false refusal in an authoring pane is
  worse than no derivation at all. Declared type wins; the sample row's shape is the fallback.
- **Binder errors are as-you-type, and ONLY a 422 blocks Apply.** DuckDB's message renders in a warning
  alert above the grid ("This does not run yet") and clears `canApply()`. The route strips the JDBC
  wrapper line the driver puts in front of every failed `DESCRIBE` ("Attempting to execute an
  unsuccessful or closed pending query result"), which was otherwise the FIRST thing the author read;
  everything from the real error on is verbatim, because it names the column and lists the candidates. Any other failure — offline, a 404 on an older control
  plane, a 503 — says nothing about the SQL, so it clears the alert and leaves Apply available: a pane
  that locks the author out because the backend is unreachable is worse than one that saves unverified
  SQL (three specs in `Transform pane: live schema derivation` pin all three arms).
- **Authoring without sample rows.** The grid seeds from `[upstreamColumns]`, passed by the editor
  (`upstreamSchemaColumns()`): the field names of the parser's companion schema when it declares any,
  else the parsed sample's keys. Before this, a Step opened with an empty grid until the operator ran a
  parse.
- 🔴 **The SCHEMA's names win over the sample header** (2026-09-25, found by driving the UI). The parse
  Step renames every raw column to its declared field by selector, so the run hands the next Step
  `ORDER_ID`, never the header's `order_id`. The editor used to prefer the sample's keys, which listed
  every field in the header's case AND — because `[upstreamColumnTypes]` is keyed by schema name — matched
  no declared type, so every field read VARCHAR however the test parse typed it. The sample rows the pane
  shows are re-keyed the same way (`schemaNamedRows`, `pipeline-transform-sql.ts`: a positional selector
  takes the i-th column, a name selector that key). ⚠ The companion is **re-read after every parse
  Apply** (`parserSchemaRevision`): a scaffolded pipeline names `<id>_schema.toon` before the file
  exists, so the first read 404s and the Apply that writes it leaves `schema_file` unchanged — tracking
  the reference alone kept the empty pre-Apply read.
- **Duplicate output names refuse.** `compileFields` marks every row sharing a trimmed name with a
  problem ("Output column names must be unique"), which blocks Apply — DuckDB would otherwise accept the
  SELECT and hand the sink two columns of the same name.
- **Row order is the output order** — ▲/▼ per row reorder `fields[]`, and `generateSql` emits in that
  order.
- **Audit boundary:** saving a `transform.sql` node yields one WARNING `SQL_STEP_UNAUDITED` from the
  validator; a WARNING never blocks save (proven by test — [Pipeline spec — model](spec-pipeline.md)).
  The SQL guard (`SqlGuard`) refuses data-definition statements, multiple statements and file-reading functions at
  the describe preview, at execution and — since 2026-09-09 — on save (`SQL_STEP_REFUSED`, §13.2).

**D10 — Transform opens on *Changed*, not *All*** (2026-09-04, built the same day). Default filter is `changed`,
with a banner stating what is implied — *"N other fields pass through unchanged — nothing to do for them"* — and a
**Show them** action. A wide feed becomes three rows to read instead of six hundred. ⚠ **This inverts the
Simple-vs-SQL argument** that justified the fields grid. The grid is **O(n) in fields**; SQL is **O(1) in fields and
O(k) in changes**. For wide data `SELECT *` with three overrides is genuinely the simpler surface — which is why the
grid's model must be *"everything passes through; show me what I changed"* rather than *"enumerate every field"*.
Keep this in view before adding any feature that requires the grid to list all fields.

### 8.3 Column metadata on a chain Step (AUTHORING-REDESIGN-1 (e), BUILT 2026-10-11)

Each row of a CHAIN Step's grid has a **Describe** toggle (`aria-expanded`, one row open at a time) with the
schema field's four keys — description · unit · classification · synonym — and nothing else.

- **Stored on the `fields[]` row** (`SqlField.description?/unit?/classification?/synonym?`), so a rename
  carries it and a removed row takes it along; a blanked value deletes the key. `fields[].name` is the
  authoritative output column (the grid generates the SELECT). A Step applied from the **SQL view** writes
  `fields: []`, so it carries no metadata, and neither does a raw-SQL Step — when any row is described,
  that Apply first asks (`InspectoConfirmService.confirmDestructive`, "Drop column metadata?", naming the
  count and the masking loss); Cancel leaves the grid and the SQL untouched.
- ⛔ **Not offered in the projection slot** (`isProjectionSlot`): its `fields[]` compile into
  `processing.map`, which the Catalog never reads, so the metadata would be dead config.
- **Save gate:** `ConfigRoutes.stepColumnMetadataFindings` (via `SaveGate`, so `/config/write`, patch and
  the graph PUT) refuses with **`ERR_STEP_COLUMN_METADATA_INVALID`**, always ERROR, when a value is a map or
  list, or when a row carrying metadata has a blank name or a name another row also outputs
  (case-insensitive). Route-branch sub-chains are walked too. Proof: `ControlApiStepColumnMetadataTest`.
  The classification vocabulary is the schema's: closed per Space and refused at save when unknown
  (`ERR_UNKNOWN_CLASSIFICATION`), normalised upper-case.
- **The reader — Catalog:** `PipelineSchemas.stepOutputs` → `MetadataGraphBuilder.addStepOutputs` adds a
  **`NodeKind.STEP`** node `step:<pipeline>/step<n>` (n counts `sql` Steps with `fields[]`, depth first,
  branches included; label `sql #n`), `origin -DECLARES-> step`, `step -DESCRIBES-> col:<pipeline>/step<n>/<NAME>`
  (same attrs as a schema column, minus `type`, which is not known statically), and `MATERIALIZES` from the
  LAST such Step to the pipeline's event tables. `CatalogOverlay` treats STEP as stage 1. UI: shape
  `triangle`, colour token `STEP`, label "Step" (`catalog-graph.ts`, `CATALOG_NODE_KINDS`). Proof:
  `MetadataGraphServiceTest`, `ControlApiCatalogTest.aSqlStepsDescribedColumnsAreReadableOverHttp`,
  `catalog-graph.spec.ts`.
- **The reader — masking (operator decision 2026-10-11, option B, ADDITIVE ONLY):** a Step column's
  sensitive classification is merged into `EvidenceMasker.schemaClassification` strictest-wins — the one
  resolver evidence masking, `publish.postgres`, publication approval, `transform.mask` and Link Analysis
  share. ⛔ It never clears `UNKNOWN_LINEAGE`: a `sql` Step plus a classified raw column still fails
  closed. Proof: `EvidenceMaskerTest`, `PostgresPublishJobTest` (Step-classified column dropped; stripping it
  breaks the approval; fail-closed unchanged). ⚠ The lineage reader loads pipelines with
  `loadForValidation`, so an ACTIVE `steps:` pipeline without `output_store:` (which does not load) still
  reads as unreadable → fail closed.
- **Not built:** option (C) — the Step's declaration REPLACING the fail-closed rule (it would let an
  omission unmask a column); see BACKLOG `AUTHORING-REDESIGN-1` (e).

### 8.4 Expression building

- **The expression-building surface** is the Transform pane's function picker per row over `SQL_FUNCTIONS`
  (**23**<!--count:sql-mapping-functions--> functions in 7 categories — corrected 2026-09-09), with a form control per declared parameter and
  "Try it on the sample" (§8.2).
- **CodeMirror has THREE hosts**, not two: the SQL query workbench (`inspecto/data-table/sql/`), the
  enrichment editor (`inspecto/enrichment/enrichment-editor.component.ts`) and **the Transform pane's SQL
  view** (`pipeline-transform-sql-definition.component.ts`). Monaco/ace are absent entirely.
- No SQL function catalog is surfaced on the join authoring surface — only free hints in tooltips/captions.

### 8.5 The step workbench — what a Step reads, and which of it the Step uses (SHIPPED 2026-09-07)

Three authoring controls built as WORKBENCH-S4 (design: `archived-documents/plans-archive/step-workbench-s4-design.md`).
**No new endpoint** — every fact on screen is one the editor already held.

- **Field list (`step-workbench.component.ts`, `step-workbench-fields.ts`).** The upstream columns with their
  declared types, split into **used by this step** / **not used**, filtered by a type-ahead, clickable. It
  rides beside the `transform.sql` pane in BOTH views: in Fields it reflects the generated SQL, in SQL the
  hand-written one.
  - 🔴 **Reference detection cannot use a word boundary.** `_` is a word character, so `amount` matches inside
    `total_amount` and the list would claim a column is used when nothing reads it. The match requires a
    non-identifier character on each side — which is what a SQL identifier boundary actually is. Both specs
    pin the `amount` / `total_amount` pair.
  - ⚠ It is a **lexical scan, not a parser**, on purpose: a SQL parser here would be a second implementation
    of the dialect the engine owns. The failure mode is a field shown in the wrong GROUP — cosmetic — never
    a wrong config write, because the workbench **writes nothing**. It emits a name; the host decides.
  - **Operator decision 2026-09-07:** the list is capped (`FIELD_LIST_CAP = 50`) with the filter always
    visible. 🔴 The cap applies to what is RENDERED, never to what is SEARCHED — filtering runs over every
    column and the result is then capped, so a column at position 300 is one keystroke away. A cap that also
    narrowed the search would hide columns with no way to reach them.
  - **A click** inserts the quoted identifier at the caret in the SQL view (`SqlCodemirrorComponent.insertAtCursor`,
    which dispatches through the editor's own update path so `valueChange` carries the new document), and in
    the Fields view adds a `keep` row — or, when the column is ALREADY an output, searches the grid for it
    rather than adding a surprising duplicate.
- **Input strip (`step-workbench-inputs.ts`).** One line saying what the Step reads, from the authored
  `edges[]` — the canvas is the source of truth for edges, so this is a projection of the model, not a fetch.
  The relation is named whenever it is not a plain `DATA` edge, because a route branch is a different input.
  - 🔴 **The planned merge/join input PICKER was refused on grounding.** There are no "input keys" to write:
    `transform.merge` declares no attributes at all and takes its inputs as a positional list the executor
    builds from the graph edges (`RowShaper.merge`), and `transform.join`'s second input is a `reference`,
    not an inbound edge. A picker would have written a key nothing reads. Edges stay authored on the canvas.
    ⛔ Stronger than that: `transform.merge` is absent from `PipelineEditable.LOWERABLE` **by decision**
    (§3.4) — do not build it a config surface (BACKLOG §6).
- **Summarize grouping (`summarize-editor.component.ts`, `summarize-editor.ts`).** A group-by chip row and a
  measures table in place of two bare `list` controls, hosted as a REGION INSIDE the generic
  `pipeline-config-definition` pane — not a fourth routing arm, because that pane's S2 decision is explicitly
  one pane for every remaining kind. It writes the same two flat string lists it always did.
  - 🔴 **Two traps this shape creates, both spec-pinned.** (1) Two surfaces for one key: the schema form must
    STOP rendering `group_by`/`measures`, which `formSpecs()` does — while `specs()` stays whole, because
    `buildConfiguredNode` reads the spec list to place each key. (2) Apply would DELETE both keys once the
    form no longer carries them, so `buildNode()` merges the editor's values back in.
  - ⛔ The aggregate options are `MEASURE_AGGS`, read from `measure-grammar.contract.json` and pinned to the
    engine's `MeasureCompiler.AGGS` by `MeasureGrammarContractTest`. The component spec asserts the rendered
    `<option>` list equals it, so a hard-coded list cannot creep back.
  - 🔴 **A measure this UI cannot parse round-trips verbatim** (`MeasureRow.raw`) and is refused by
    `validate()` with the grammar's own message — never dropped. Opening a node and pressing Apply must not
    be a way to lose what someone wrote. `transform.summarize`'s `measures`/`group_by` have dedicated
    `configValidators` (`pipeline-config-definition.component.ts`).

### 8.6 Filter — the row predicate's structure (SHIPPED 2026-09-23, `AUTHORING-REDESIGN-1` (c))

A `transform.filter` Step's drawer shows **What gets kept** beneath its `where` field: DuckDB's own parse
tree of the predicate, rendered as a read-only table. Plan of record (archived):
[`authoring-ast-table-design.md`](../../../../archived-documents/plans-archive/authoring-ast-table-design.md) §7.

- **Home (Q1).** On the Filter Step, not the `transform.sql` pane, whose D3 ("filtering belongs to its own
  Step") stands. `transform.join` has no SQL to parse and is out of scope.
- **Seam.** `POST /components/sql/ast` `{sql, fragment: 'predicate'}` (`ComponentRoutes` → `SqlAst`) wraps
  the text as `SELECT 1 FROM input WHERE <pred>`, runs `json_serialize_sql(?::VARCHAR)` on a **sealed
  `SqlSandbox`** (Q4) and returns the `where_clause` node verbatim. A parse error is a **200 `{ok:false}`**
  with the position rebased onto the author's text; a "predicate" carrying its own ORDER BY / GROUP BY /
  LIMIT / UNION is refused `NOT_A_PREDICATE` by comparing the tree to a fixed skeleton. **No `SqlGuard`**
  (Q3): nothing binds; ⛔ not a precedent for `/components/transform/describe`, which binds. (The precondition —
  the engine handing back its own parsed statement as data on a sealed connection — was discharged 2026-09-07.)
- **Read-only, for good (Q2).** No AST→SQL path exists and none may be added: the round trip drops
  comments and re-spells everything. Editing goes through the shipped Query Core instead, and only when
  (1) `astToConditionGroup` recognises the tree, (2) the text carries no comment, and (3) `compileWhere`'s
  spelling re-parses to the **same** tree (`sameSqlStructure`, offsets ignored). The result is written into
  the `where` field only on an explicit accept of the exact before/after; untouched text stays byte for byte.
- **Contract (Q5).** `contracts/sql-ast.contract.json` pins ONLY the node types and keys the SPA reads;
  `SqlAstContractTest` proves the engine emits them, `sql-ast.spec.ts` proves the reader handles exactly
  them. ⛔ Never read `query_location`: start-only, and a CAST carries 2^64-1, which a JS number cannot hold.
- **Degrade.** Parse error → `<inspecto-alert>` warning, saving never blocked; route unreachable → a quiet
  status line; an unrepresentable part stays in the table as an explained row, never dropped.
- ⚠ Traps: `NOT a = 1` parses as `a != 1`; `(a AND b) AND c` flattens to one conjunction; `TRUE` is a CAST
  of `'t'`; a DECIMAL arrives as an unscaled integer + `scale`. Nesting in the table is carried by
  visually-hidden "Level N" text, not `aria-level` (valid only inside a `treegrid`).

### 8.7 Lookup / join — `transform.join` (generic `AttributeSpec` form)

- Rendered by the shared `PipelineConfigDefinitionComponent` + `InspectoSchemaFormComponent`, driven by
  `NodeAttributes.TRANSFORM_JOIN`'s spec (`node-attributes.ts`, mirroring
  `NodeAttributes.java`): `reference` (`autocomplete`, required, placeholder `reference/rates`) and
  `on` (`list`, required, join key columns).
- **`reference` is autocomplete with real suggestions, never a hard constraint:**
  `referenceOptionLoader()` (`entity-option-loaders.ts`) calls `CatalogService.references()`
  (backed by `GET /catalog/references`, `CatalogRoutes.java`) and maps results to
  `reference/<label>` options, wired via `[optionLoaders]` (`pipeline-config-definition.component.ts`).
  Per the shared form's own doc comment (`schema-form.component.ts`): "suggestions assist, they never
  constrain — free text stays valid."
- **Save-time checks (since 2026-09-06):** `PipelineValidator` refuses `JOIN_REFERENCE_MISSING` / `JOIN_ON_MISSING`
  and `PipelineGraphRoutes` refuses `UNKNOWN_JOIN_REFERENCE` (a `reference/<pipeline>` naming no loaded pipeline, or
  one without `produces: reference`). Which columns the reference carries stays the dry run's question — nothing
  cross-checks `on` against the reference dataset's real columns.
- **An authored reference has a home for two node kinds only**; on any other kind it is refused by name
  (2026-08-14 — the picker had been keyed on category rather than type, so lowering dropped every other reference
  **in silence** while the save returned success).
- **No dedicated join-result preview.** The closest thing is the generic, opt-in "Test this Step" button
  available to any `transform.*` node (`ComponentsService.previewFamilyFor`, matches `type.startsWith('transform.')`,
  `components.service.ts`) — posts the on-screen config + the tab's already-captured sample
  rows to `POST /components/transform/preview`, rendering `<inspecto-step-preview-result>`
  (`pipeline-config-definition.component.ts`). It only works if the tab already has sample rows
  from a prior Test-Parse, and is a manual click, not automatic feedback while typing `reference`/`on`.

### 8.8 The deleted Load pane — history of the decisions it encoded

The Load pane (`pipeline-load-definition.component.ts`) was DELETED 2026-09-05. The catalog carries ONE entry for
this surface — `transform.record`, *Record Transformer*, folded 2026-09-04 from `transform.expression` +
`transform.cast` + `quality.cleanse.trim` — pointing at `transform.sql`. Type *coercion* folded in with it; the
schema *contract* stayed on Parse as `quality.schema.validator`, re-scoped, because the declared types are the
cast-failure audit's denominator. The Components feature's mapping editor
(`modules/admin/components/mapping-editor.dialog.ts`) still authors `rules[]` for a `mapping` COMPONENT; the engine
reads those as fields at run time. What follows is kept as HISTORY of the decisions the pane encoded — and 🔴 every
constraint below is still armed on a stored `mapping.rules[]`, which `RecordTransform.fromMappingRules` converts to
`fields[]` (read path only since 2026-09-05; nothing AUTHORS these any more):

- Rule rows were a `FormArray`, seeded from the node's already-authored `rules` if present, else from a
  legacy `columns: [{name, expr}]` projection read as DIRECT rules (2026-09-04 — before that a
  columns-authored node showed an EMPTY grid, so the operator saw no mapping where the engine ran one),
  else one straight-through identity rule per schema field, auto-seeded as a pending "proposal" that
  armed Apply. This was the only auto-map-by-name behavior; it was not a re-invocable action.
- ⚠ **Applying rules REPLACED `columns`.** `MAP_AUTHORED` holds both keys and `RowShaper.columnsOf` returns
  `columns` before it looks at rules, so a node keeping both would execute the old projection over the
  operator's saved edit — a write that is real and dead at once. `submit()` therefore deleted `columns`
  whenever it wrote a non-empty `rules` (pinned by `emits rules and drops the columns key they replace`).
  An empty grid deleted nothing: a stray Apply must not wipe an authored projection.
- **Per row:** target column (free-text, pattern-validated); rule — `mat-select` of `transformType`
  (`DIRECT` / `EXPR` / `CONCAT_DT` / `FILENAME_DATE`, from `TRANSFORM_TYPES`); the from-column control varied by
  type — `DIRECT`: dropdown of schema field names; `CONCAT_DT`: Date + Time column dropdowns; `FILENAME_DATE`:
  filename-column dropdown + free-text Prefix + free-text Format, placeholder `%Y%m%d`; `EXPR`: **a single
  free-text `sourceExpression` input**, tooltip "A per-row scalar expression, emitted verbatim — you own any
  explicit cast".
- **`EXPR` was pure free text** — no syntax check, no autocomplete, no column picker; the engine emitted it
  **verbatim** into the compiled SQL (`TransformCompiler.java`), unparsed until DuckDB ran it. A whitespace trim
  was only achievable by hand-typing `TRIM(col)`.
- **The two specialised types pack parameters into `sourceExpression` as `|`-delimited positions** (`MappingCsv`
  drops every other key): `CONCAT_DT` = `<dateCol>|<timeCol>` always both (compiler reads `parts[1]`
  unconditionally); `FILENAME_DATE` = `<col>|<prefix>|<strptime>` with trailing blanks DROPPED, not emitted (an
  empty third position interpolates `''` into `TRY_STRPTIME`), and may only write **`EVENT_DATE`** (enforced
  server-side; the pane refused rather than posting a guaranteed 422). A transform type nothing has heard of (a
  hand-authored `LOOKUP`) is preserved, never dropped — *not offering* must never become *destroying*.
- **Silent stale-reference fallback (backend):** a `DIRECT` rule naming a column no longer in the schema did not
  error at mapping-save time — `TransformCompiler.direct()` did `fieldTypes.getOrDefault(source, VARCHAR)`,
  silently defaulting the cast; the mismatch surfaced only as a DuckDB "unknown column in `raw_input`" error at
  execution time. (On `transform.sql` a binder error now names the stale column instead; the `DIRECT` fallback on
  the legacy path is unchanged.)
- **Preview was opt-in and server-round-trip:** "Test mapping" posted the rules + the Parse tab's captured sample
  rows to `POST /config/preview/schema`, rendering a capped result table (`MAX_PREVIEW_ROWS=20`); any rule edit
  invalidated it (`invalidateMapping()`) and it did not auto-rerun. No drag-and-drop field mapping.
- **Client-side `ruleProblem()`** only structurally validated `CONCAT_DT`/`FILENAME_DATE` arity, mirroring the
  engine's `MappingRules`. **Mapping CSV save validates shape only:** `MappingCsv.parse` checks required headers
  and row width, never that `targetColumn` exists in the schema or that `sourceExpression` is valid SQL
  (`MappingCsv.java`; the write path `ConfigWriteRoutes.java`, `splitMapping`, does the same).

### 8.9 The 2026-09-03 authoring gap list, and what became of it

Confirmed absent 2026-09-03; annotated 2026-09-04 after the two authoring plans shipped (AUTHOR-SCHEMA-1 is CLOSED in
BACKLOG; what survives is AUTHORING-REDESIGN-1):

1. **No live/as-you-type validation or preview for `EXPR`** — **absorbed**: `transform.sql` reports binder errors
   as you type through the DESCRIBE route (§8.2).
2. **No expression-builder UI anywhere** — **closed** by the function catalog grid and CodeMirror (§8.2, §8.4); the
   structured table for filters shipped as (c) (§8.6).
3. **No reference-existence or `on`-column-existence validation before saving a `transform.join`** — **CLOSED
   2026-09-06** (§8.7).
4. **No automatic schema-drift detection** linking a schema rename/retype back to mapping rules — **CLOSED
   2026-09-06**: `SchemaMappingDrift` (inspecto-etl) runs on all three schema save paths (`/config/write`,
   `/config/patch`, `/components/schema/{id}`) and 422s a mapping `from` or COLUMN argument naming a column
   `raw.fields` no longer declares — cell-addressed (`mapping.fields[<name>].from`), no `compatibility: none`
   escape because both halves are one document; `custom` rows are author SQL and exempt.
5. **Mapping/join preview is opt-in and stale-prone**, 6. **no drag-and-drop field mapping**, 8. **two disconnected
   test/preview mechanisms** (drawer-level "Test this Step" vs. canvas-level `RunToHereDialog`, with no
   cross-linking) — **dropped as premature** for a surface that was redesigned.
7. **Silent stale-column fallback in the engine** — **absorbed**: a `transform.sql` binder error names the stale
   column; the `DIRECT`-rule fallback itself is unchanged on the legacy path.

Still true at save time: no `EXPR` syntax check on the legacy path (opaque until DuckDB executes it), and anything
not covered above (a typo'd expression, an out-of-range cast) surfaces only at test-time or run-time.

---

## 9. Enrich

### 9.1 The `enrichment` Step

Authors a **companion** `*_enrich.toon` through `ENRICHMENT_WIRING_ATTRIBUTES`, not `node.config`.
Every save re-registers (`POST /enrichment`) — enrichments do not hot-reload by mtime; a register
failure downgrades to a warning. **Enrichment stays first-class** (operator, 2026-09-06): the retirement narrowed
to nothing after grounding found the one committed fixture does not migrate — ⛔ not a pending rename.

- **Wiring is derived through one shared function**, `enrichmentWiringDefaults`
  (`inspecto/enrichment/enrichment-wiring.ts`): input = the Stage-1 output · output =
  `<base>/data/enriched/<name>` · trigger = `on_pipeline`. Only pipeline-DERIVED facts travel in
  `NodeConfigData.enrichmentHost`.
- 🔴 The Stage-1 store seeds **only when exactly ONE sink declares a `database`** — an invented
  store path reads zero rows everywhere while looking like it worked. ⚠ Quarantine is SINK-category
  but carries only `dir`, so the derivation filters on config, not category.
- ⚠ **A seed is one-shot** — never re-derived as the author edits (a seed moving under an edited
  form clobbers it). A bound companion's file always wins, including a `triggers.on_pipeline`
  naming a different pipeline: silently re-pointing a deployed enrichment is not an edit anyone
  asked for.
- **The two partition keys are engine-REQUIRED with `[]` as the legal "unpartitioned" value**
  (`FieldSpec.required`; `RawConfig.present` is true for `[]` — required means "the grain is
  stated"). The save sets both **explicitly** because `nestKeys` prunes an empty array; JToon
  round-trips `partitions[0]:` back to an empty list (pinned in `ConfigLoaderTest`).
- Speccing a key makes it form-OWNED (`ownedLeaves` derives from the spec table; owned leaves are
  replaced wholesale). An `output.partitions` entry may be the sink's `{column, source}` map —
  hydrate flattens to `column`, save re-marries `source` onto kept columns
  (`remarryPartitionSources`).
- ⚠ **The `list` chips type does NOT compose with `flat-keys.ts`** (which joins arrays to comma
  strings and splits back only for a hardcoded leaf set) — the two partition flat keys are
  re-seeded as real arrays after `flattenBlock`, deliberately inside the dialog. Any future `list`
  spec on a flat-key surface needs the same treatment or a decision to unify the conventions.

### 9.2 The Enrichment screen

Route `/enrichment` (Operations nav group, `inspecto-ui/src/app/modules/admin/enrichment/enrichment.routes.ts`). A
**standard**, single-select [data-table](../../layer/product/spec-ds-data-table.md) of stage-2 jobs; selecting a
job opens a detail panel with tabs: **Runs** / **Lineage** (filter by run id) / **Report** (a date-range rollup with
percentile stats). The runs/lineage rows render in a **pro** data-table; the report is a stat table. Backed by
`EnrichmentService`.

---

## 10. Load — sinks, partitioning and route branches

### 10.1 Partitioning

`<inspecto-schema-partitions-editor>` (`inspecto/schema/`) is **rendered on the Sink pane since 2026-09-04**, which
reads/writes the SAME companion schema toon's `partitions[]` key directly. ⚠ The Parse pane still seeds
`partitions[]` on load and carries it through its `overwrite: true` write, so a Parse Apply over a stale seed can
clobber a Sink edit made meanwhile — BACKLOG. Rows derive Hive segments `{column, source, type}` from schema
fields, rendered where the pane owns the schema toon (`authorsSchema()`). 🔴 The read half is a data-loss fix —
`partitions[]` is top-level in the schema toon and the pane's `overwrite: true` write DROPPED hand-authored blocks
until the pane read them back (`loadSavedSchema`). Legacy `partitionKey:` loads as the synthesized year/month/day
trio. Mixed date sources WARN, never block (the engine degrades catalog bounds). A stored `source` the schema no
longer carries stays listed — visible beats vanished. The smart launcher pre-picks a date field only when exactly
ONE exists; ⚠ native `<select>` in `@for`: bind `[selected]` per option, never `[value]` on the select. Sinks
themselves: [Pipeline spec — sinks](spec-pipeline.md).

### 10.2 Route branches — the destination is IDENTITY

`database` is the **branch↔sink join key on both halves of the lift/lower round trip** (edges do
not survive the flat file): lowering stamps each branch with the database of the sink its
`route:<key>` edge feeds and skips a destination-less branch; `sinks:` is keyed by distinct
database; the lift rebuilds pairing from the stamp.

- 🔴 `addRouteBranch` creating its companion sink with an empty config was silent DATA LOSS (branch
  lowered to nothing; save reported success). `branchDestination()` derives it beside the primary
  sink's home (`<primary home>/<branchKey>/database`), falling back to `pipelineScaffold`'s
  `data/<name>`. ⚠ Quarantine sinks are excluded by the `typeof database === 'string'` test.
- ⛔ Do not "simplify" by leaving the sink blank to validate later — that state cannot be saved at
  all and looks identical to any unconfigured Step.
- 🔴 **A round-trip test that configures the artifact under test tests nothing** — the original spec
  set `database` by hand and passed the whole time.
- 🔴 **Single-slot kinds** — *corrected 2026-09-09: `MULTI_JOIN` DOES NOT EXIST.* Two “too many of a kind”
  constants were live, `MULTI_PARSER` and `MULTI_MAP_CONFIG` (`PipelineEditable.java`) — *five since 2026-09-23,
  with `MULTI_ACQUISITION` / `MULTI_GAP` / `MULTI_MARKER`*, so a second `acquisition`, `gap` or `dedup.marker` now
  refuses by name on both the engine and the SPA lowering; the join/dedup/route/summarize codes were deleted with
  the ordered `steps:` chain and survive only inside a `PipelineCompileException` Javadoc paragraph explaining their
  own removal. (`processing.join` is last-one-wins; `recordDedup`, `routeNode`, `summarizeNode` are last-one-wins,
  silently): the discard behavior is PINNED per slot in `PipelineEditableTest`; inverting it is an operator call
  (BACKLOG §4).
- **Mid-branch `steps:` sub-chains SHIPPED 2026-09-02 (R3)**: a route branch carries `steps[]` in the shared step
  grammar — authorable on the canvas (wire `route:<key>` → step → sink; the lift presents branch chains as ordinary
  flattened nodes, lower writes them back to the branch). Mid-branch kinds = the fork-executable set; `join`,
  windowed `dedup` and nested `route` refuse by name at save. **Insert-into-branch SHIPPED 2026-09-02**
  (MIDBRANCH-UI-1): each branch header offered "add a Step at the start of branch <key>" (→ `insertBranchHead`,
  which keeps the `route:<key>` edge on the route side) and every in-branch card except the branch's tail offered
  insert-after (plain `insertStepAfter`); both open a palette narrowed to `BRANCH_STEP_TYPES` (filter / dedup /
  summarize / sql — the client mirror of `RouteArming.BRANCH_STEP_KINDS`, so it front-runs the 422 rather than
  offering `join`/`route`). ⚠ **The refusal toast is RENDERED from `BRANCH_STEP_TYPES`** (`branchStepTypesLabel()`,
  2026-09-13), never hand-listed — the same discipline the Java authority always had (`RouteArming` renders
  `new TreeSet<>(BRANCH_STEP_KINDS)`), which is exactly why the server side never drifted. Pinned by three specs
  asserting the label names every member of the set. Remove/move inside a branch stay canvas work. Design:
  [`mid-branch-transforms-design.md`](../../../../archived-documents/plans-archive/mid-branch-transforms-design.md);
  backend: [Pipeline spec — model](spec-pipeline.md).

**`sql` mid-branch (SQL-BRANCH-1, 2026-09-06).** A `transform.sql` step inside a `route:` branch used to compile and
then refuse at arming, because `RouteArming.BRANCH_STEP_KINDS` held filter/dedup/summarize only. `RowShaper.sql`
reads nothing but its input relation — no reference resolver, no execution context — so the ingest walk runs it
unchanged; `sql` is now in the set (and the UI's `BRANCH_STEP_TYPES`), pinned by `RouteArmingTest`,
`ControlApiRouteArmingTest` and an executed branch in `RouteIngestEndToEndTest`. The author SQL still passes
`SqlGuard` at execution. 🔴 **Trap, found by that test:** the sink partitions by the derived `year`/`month`/`day`
columns the projection slot added, so a `sql` step that NAMES its output columns drops them and the sink refuses the
batch (`partition_by expected to find year`). And `SELECT * REPLACE (…)` is refused too: `SqlGuard`'s lexical
allow-list bans the word `replace` because it guards `CREATE OR REPLACE`. The idiom that works is
`SELECT *, <expr> AS <new_col> FROM input` — add, don't rename; reshaping a column in place is the Record
Transformer's job on the trunk. Both hold mid-branch and on the trunk alike.

---

## 11. Ingest options and feed contracts

### 11.1 Content refusal — `processing.refusal: restricted_quarantine`

This is opt-in per Pipeline and **off** by default (added 2026-09-30, `RefusalQuarantine`). With it on, a file can
be refused whole in three ways:

- the Pipeline's own mapping raises DuckDB `error('INGEST_REFUSE:<CODE>')`;
- the platform card scan finds a card-number candidate in a raw cell (`processing.refusal_scan: card_number`);
- its name or header line holds a card-number candidate.

- **What triggers it.** Only the first `SQLException` in the cause chain counts, and its message must be exactly
  `Invalid Input Error: ` followed by a code matching `^INGEST_REFUSE:[A-Z][A-Z_]{0,63}$`. A conversion or cast
  error never triggers it, and neither does a code that merely appears somewhere in a message. Data content
  cannot reach it: a strict `CAST` over a value that spells the code is a `Conversion Error`, so it fails the batch
  as it always has.
- **The platform card scan.** `processing.refusal_scan: card_number` scans every raw cell except the
  `processing.refusal_scan_exempt` columns (default: none), before anything is written. The exact candidate
  shapes, the brand/length list and the measured false-trip rates are in [Spaces §3.5.1](../config/plan-spaces.md).
  - The scan **fails closed**: an error while scanning restricts the file as `INGEST_REFUSE:SCAN_FAILED`.
  - An exempt list covering every raw field is refused at load, and an unknown exempt name is warned.
  - `RefusalConfigAudit` wraps every control-plane Pipeline write (`/config/write`, `/config/patch`, graph save,
    settings, history restore, rename). A write that changes `refusal`, `refusal_scan`, `refusal_scan_exempt` or
    `refusal_retention_days` emits AUDIT `pipeline.refusal.changed` with before / after.
  - ⚠ A false trip moves a whole file. An operator who knows a column carries no card data (an order id, an IBAN,
    a phone number) exempts it, and that column is then not scanned at all.
- **What happens to the file.** It is **moved, never deleted**, to the restricted store
  `<data root>/.restricted/<pipeline>/` (`RefusalQuarantine.dir`; the data root is the Space's registered one, else
  the nearest `data/` ancestor of `dirs.quarantine`; with neither, the refusal fails closed). The file gets a generated
  `refused-<CODE>-<ms>-<n>.<ext>` name, because the original name may itself be the refused value.
  - 🔴 **Why it is outside every `dirs:` entry (round 5, 2026-09-30).** The sealed ingest connection
    (`SEC-INGEST-EXPR-EXTERNAL-ACCESS-1`) allowlists the Pipeline's `dirs:` for DuckDB `allowed_directories`, which
    matches by **prefix**. So a store under `dirs.quarantine` — its first home, `<quarantine>/.restricted/` — was
    readable by any `fn: custom` expression (`read_text('<quarantine>/.restricted/*')`).
  - Three checks keep it out:
    - The store sits beside the Pipeline dirs, never under them.
    - `PathJail.readAllowlistRefusal` refuses an allowlisted dir that is inside, or directly holds, a `.restricted`
      store, for the ingest and the enrichment seal alike.
    - The ingest seal and `restrict` both refuse (fail closed) when the store would fall under an allowlisted dir,
      for example `dirs.quarantine` = the data root. The enrichment seal also refuses an allowlist covering
      `<data root>/.restricted`.
  - Round 6 closed two gaps:
    - **The Space data root itself is refused.** This applies to every seal allowlist and to the write/import
      data-home gate (`refuseDataHome`), whether or not a store exists yet. Otherwise a connection sealed before
      another Pipeline's first refusal could read the file that refusal then restricts. The engine registers each
      Space's data root (`PathJail.registerDataRoot`), and `-Ddata.dir` and hosted `<space>/data` count too.
    - **Nested stores are refused.** Stores this process created are registered (`registerRestrictedStore`), and
      a `.restricted` one level down (a nested `data/` fallback) is refused too.
  - Undoing an audited import change is audited as `pipeline.refusal.reverted`.
  - `RefusalQuarantineTest.aMappingExpressionCannotReadTheRestrictedStore` proves it: after one file is restricted,
    a mapping that `read_text`s the store fails its batch with a permission error, and no row carries the content.
  - The directory is not the inbox, so it is never re-polled.
  - `BackupTask.secret` skips `.restricted`, so no backup carries it.
  - The quarantine listing (`FileStatusStore.quarantine`), the errors-file route and `RecordReplay` skip it.
  - `DataRef.requireUnder` refuses any data reference that addresses a `.restricted` segment, or whose read root
    contains one (DuckDB's `**` glob descends into dot-directories). That covers Datasets, Expectations and
    `collector.dataset`. The table browser and `/db/query` apply the same rule.
  - **Retention is mandatory (operator decision 2026-10-04, PCI: no stored card number).**
    `processing.refusal_retention_days` defaults to 7 when unset and must be 1..30. Outside that range,
    `ConfigSafetyValidator` refuses the save (422) and `PipelineConfig` refuses the load. A restricted file older
    than the window is **deleted** by `RefusalQuarantine.sweepExpired`. That runs with each poll cycle's
    housekeeping (`CollectorProcessor.ingest`, next to `cleanupStaleMarkers`, at most once a minute per store) and
    after each refusal.
    - Each deletion is audited as `ingest.refused.retention` with the stored name, size, sha256, reason code and
      `retention_days`. The content is never recorded.
    - The audit is emitted *before* the delete, so a crash can duplicate the record but never lose it. A failed
      delete leaves the file in place and the next sweep retries it.
    - The sweep is path-jailed. It deletes only a regular file (never a link, never a sub-directory) directly in
      this Pipeline's store, whose real parent is the store and whose name the store generated. Files in another
      Pipeline's store, in the ordinary quarantine or in backup are never deleted.
    - Replay never relies on a restricted file. `RecordReplay` reads only the inbox, backup and the ordinary
      quarantine tree (it skips `.restricted`), and the store lives outside all three.
- **What is recorded.** Only the reason code: the member row becomes `QUARANTINED_RESTRICTED`, with the generated
  `filename`, the `error` set to the code and a blank `logical_name`; one WARN line; one AUDIT event,
  `ingest.refused` in the `security` category, with the pipeline, the code, the Consignment and the stored name.
  Exception text, values and the original name are never recorded.
- **Every lane.**
  - Single-file streaming: the card scan runs first, then a refusal raised in the transform is caught.
  - `chunkedIngest`: the whole file is scanned before any chunk is written, and an author refusal in a later chunk
    rolls the earlier chunks back.
  - Union multi-member batches and the Java path: each member is scanned, and its transform probed, alone before
    it joins, so only the refused file is restricted and its batch-mates land.
- **File name and header line.** With the mode on, a file whose name or header line holds a card-number candidate
  is refused before any lane reads it (`CARD_NUMBER_IN_FILE_NAME` / `CARD_NUMBER_IN_HEADER`). The check uses the
  same brand/length regex as the SQL scan (`CardNumbers.IIN_REGEX`).
- ✅ **Failure text never stores a value (2026-10-03, was `INGEST-FAILURE-TEXT-QUOTES-VALUE-1`).** Engine error
  text quotes the offending cell (`Could not convert string '…'`, strptime's `"…"`), echoes it under a caret
  line, or repeats the CSV line (`Original Line: …`). `FailureText.scrub` (inspecto-etl) replaces every quoted
  literal (greedy to the last same quote on the line, since engines print an embedded quote unescaped), every
  caret-pointed line and every `Original Line:` tail with a salted fingerprint — generically, never by searching
  for the value. A Binder / Catalog / Parser error (authored SQL, which data never reaches) is kept verbatim
  unless the same text also carries a data-shaped error. ⚠ The price elsewhere: a quoted identifier in a data
  error is fingerprinted too. It runs once in `ConsignmentIngestor.process` before the batch ledger, the retry
  record and the event, on every member's error in the file ledger, and in the CSV / union / generation lanes'
  failure log lines (which no longer log the stack trace). The fingerprint is HMAC-SHA256 under a 32-byte random
  per-Space salt at `<space>/.inspecto/value-fingerprint.salt` (Space = nearest ancestor of the status dir holding
  `config/`). Pinned by `FailureTextTest` (six real DuckDB error shapes) and `RefusalQuarantineTest`, which scans
  the whole Space byte-wise.
- ⚠ **Not covered:** the Collector's discovery logs and its acquisition ledger see the inbox name before the
  check runs.
- It replaced the 2026-09-30 purge seam (`PURGED_REFUSED`). Data content could trigger that seam, it applied to
  every Pipeline, and it deleted irreversibly. Proof: `RefusalQuarantineTest`, and the payment pack's
  `PaymentFraudTemplateGoldenTest` ([Spaces §3.5.1](../config/plan-spaces.md)).

### 11.2 A reference build — `postmed_xdr` (2026-09-22)

A post-mediated telecom xDR feed (pipe-delimited, one file per collection interval) built from scratch in
`spaces/demo/config/postmed/` and verified against the written Parquet, not the exit code (⚠ exit 0 and a log ending
at *"Planned N batch(es)"* say nothing about the outcome — read `status/*_batches_*.csv`). What it pins down: all 18
declared types survive to Parquet (`BIGINT` for byte counters — never `DOUBLE`, lossy above 2^53);
`csv_settings.null_strings` turns `NULL`/`N/A` and an empty field into SQL NULL, so an unrated record's price stays
NULL rather than `0.00`; omitting `date_formats`/`timestamp_formats` is what the validator warns about
(`TRY_STRPTIME` → NULL). ⚠ The Hive partition columns are not uniformly typed — `year` reads back `BIGINT`,
`month`/`day` `VARCHAR`, so a predicate on `month` needs a string literal. ⚠ The CLI does no space discovery: a
`schema_file:` needs `-Dassist.safety.roots=<space>`. Its findings, all closed: `INGEST-REJECT-ACCOUNTING-1` (the
batch row now splits `rejected_files`/`rejected_rows` and `total_input_rows` counts what arrived),
`INGEST-ERRORS-CSV-PER-COLUMN-1` (one errors row per bad line, raw line once), and
`DUPLICATE-CHECK-GRAIN-UNSTATED-1` (§5). The workbench-side findings (`GRAPH-SAVE-REFORMATS-CONFIG-1`,
`UI-CREATED-PIPELINE-FLAT-HOME-1`, `READONLY-LENS-PALETTE-ENABLED-1`) are in this guide (§6.3) and the
[Pipeline spec — model](spec-pipeline.md). Reproduce:
`python spaces/demo/data/samples/gen-postmed-xdr.py --seed-inbox`, then run the CLI over the Pipeline; copy
`PMXDR_20260904_001.psv.defect` in as `.psv` for the fault run. Build record:
[postmed-xdr-pipeline-build](../../../../archived-documents/plans-archive/postmed-xdr-pipeline-build.md).

### 11.3 Daily feeds — the template, the export contract and delivery checks

*(`LA-DAILY-INGEST-1` T2, T3, T7, T8.)* The solution pack that ships this shape:
[la-daily-feed](../../solution-pack/la-daily-feed/architecture.md).

**Template.** `spaces/_templates/la-daily-feed/` is the reusable shape of a daily feed for Link Analysis: Pipeline
`daily_xdr` + schema (`partitionKey: EVENT_DATE`, the UTC calendar date of `START_AT`), a poll Collector
(`connector: local`, `include: XDR_*.csv`, gap detection on `XDR_{yyyyMMdd}`), `.processed` duplicate markers, the
quarantine directories, and the Dataset `daily_xdr_dataset`; two synthetic sample days in `data/samples`. Modelled on
`postmed_xdr` (§11.2). It is a Space Template, so it is applied by `POST /spaces` with `template`, and
`ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies` loads and validates every `_templates/*` entry.
Per feed, rename the feed, columns and file pattern; keep `partitionKey` on the event-date column.
⚠ The trigger is **poll discovery only**. A cron trigger for the Collector is not a Pipeline setting today (cron exists
on Jobs and on pipeline entry nodes); the template does not claim one.

**Export contract (the INPUT guarantee).** Whoever produces the daily file (a mediation drop, or a database owner who
exports a daily CSV or Parquet file with their own scheduler and query, D-ING5) guarantees, per feed:

| Item | Guarantee |
|---|---|
| File name | one pattern per feed with the day in it, e.g. `XDR_yyyyMMdd.csv` or, with parts, `XDR_yyyyMMdd_partNNNN.csv`; the name of a file is STABLE (a feed rule); the Collector `include` glob and `gap_detection.sequence` use the same pattern |
| Grain | exactly ONE UTC event date per file; no file spans two dates. MANY files (parts) per event date are allowed, each with its own stable name |
| Parts | part numbers are ZERO-PADDED (`part0001`; lexical sorting); parts of one day are delivered together, or completeness is signalled. The platform keeps a per-day manifest of what landed (T8, below) |
| Event-date column | one DATE column (`EVENT_DATE`) equal to the UTC date of the event timestamp; it is the `partitionKey`. The day in the file name and every row's event date agree |
| Time zone | the event timestamp column is UTC wall clock with no offset in the text; the schema declares `timezone: UTC` |
| Delimiter, encoding, header | comma (or one declared delimiter), UTF-8, one header row whose names match the schema |
| Null strings | the declared `null_strings` only (template: `NULL`, `N/A`); an empty field is also null |
| Corrections | a corrected file is re-delivered as a WHOLE file under the SAME name (it overwrites cleanly), never a partial or a delta. A correction under a NEW name doubles every row of the date: the feed owner's error, not fixed in the platform, detected by the T8 duplicate-delivery check |
| Completeness | no partial days: a file is written complete (temp name, then rename) so the Collector never sees half a day |
| Late files | allowed: a day may arrive after later days; it lands in its own event-date partition |
| Reference feeds | the same stable-name rule: ONE stable file name, or `load: upsert` with a key. A dated Reference file name makes `load: replace` read both days (T6 finding) |
| Parquet export | same name, grain, column and zone rules; the schema uses `parsing.frontend: parquet` |

What the platform does when a day is delivered twice (replace vs append of the partition, the file-level duplicate
marker versus a changed file, late-data effect on the Link Analysis Index) is **not part of this contract: see T4**
(`LA-DAILY-INGEST-1`). Until T4 lands, treat a re-delivered day as unverified.

**Backfill (T7, 2026-10-06).** A 30-90 day initial load is the same door as a daily one: drop the range's daily files into
the Collector's inbox (each still one event date per file) and let the Pipeline land them; then run ONE `full` index build
over the landed range (the `la.index.build` Job with `allow_full: true`, or `POST /inv/index` mode `full`). The days are
parsed in parallel by the Pipeline and the full build is one parallel DuckDB sort, so there are no per-day index builds to
merge. `IndexAppendTest.aThirtyDayBackfillAnswersEveryReadLikeTheDayByDayRunAcrossThreeCompactions` pins that the backfill
answers every read like the day-by-day run (first day full, append per day, compact at the delta cap) and that a backfill
missing one day does not. While the range lands, each committed batch emits `pipeline.commit` (`job.dataset.produced` is
emitted only by the `sql.template` Job); a Job run that finds a build in flight WAITS and re-plans (at most 6 waits, 2 s
backoff doubling to 60 s, within `timeout_seconds`, every attempt audited), so the daily trigger need not be paused
(operator, 2026-10-06). That default bound (about 122 s) does not outlast a FULL build at 10^8 rows (852-929 s measured), so
the Job takes `wait_seconds`: unset keeps the six-wait bound, `0` never waits, any other value is a TIME budget (same
backoff, last sleep trimmed) clamped to `timeout_seconds`; a negative or non-numeric value fails the Run. Read from the saved
config only, like every other Job param (no per-run override).

**Delivery checks (T8, operator 2026-10-06).** Declare the feed's parts on the Collector:
`gap_detection.file_template: "XDR_{yyyyMMdd}_part{seq}*"` + `seq_scope: PER_BUCKET` (`seq_scope` is required when the
template holds `{seq}` and refused when it does not; the same keys the completeness KPI uses —
[acquisition spec Phase D](spec-acquisition.md#phase-d--gap-detection)). A one-file-per-day feed declares a date-only template,
`file_template: "XDR_{yyyyMMdd}.csv"` with no `seq_scope` (operator, 2026-10-06): one expected file per day, its part number
recorded as 0, a missing day = one missing file. ⚠ A literal suffix means a RENAMED correction (`XDR_20260902_fix.csv`)
does not match the series: it lands, the day's rows double, and nothing is signalled (found live 2026-10-14). The shipped
`la-daily-feed` template therefore ends in `*` (`"XDR_{yyyyMMdd}*"`), so a second file of a day is recorded as part 0 again
and signalled `RENAMED_CORRECTION`. Then (a) every committed part is recorded in `<markers>/day-manifest.tsv` (day, part,
rows, bytes); a part re-sent under the same name, the same part under a NEW name (the day's rows are now doubled) or a
new part for a day after a later day landed is signalled as `collector.delivery.anomaly` (WARN; payload `kind`, `day`,
`file`, `previousFile`); (b) each poll cycle checks the window from the first known day to the last COMPLETE UTC day, so a
missing day (including the last one) or a missing part is one `SEQUENCE_GAP` alert. **Data as of:** name the Dataset's
event-date column (`eventDate: EVENT_DATE` in the Dataset config); `GET /datasets/{id}/freshness` and the Dataset editor's
chip then show the instant the data is covered through (a DATE through its end), and an Alert Rule with `maximumAge: 2d` +
`measure: max(EVENT_DATE)` fires when the newest event is older than that, even while files keep arriving.

**Corrected same-name day (owner call closed 2026-10-14).** Under the default `duplicate_check` (path markers) a file
re-delivered under the SAME name with changed bytes is skipped (the `.processed` marker wins; only a WARN +
`DUPLICATE_SKIPPED` signal, once per file version). A feed that wants the correction to land states
`collector.duplicate: {mode: checksum, algorithm: SHA256, on_change: reprocess}` (a Phase C key that already existed;
no new key, no spec change): the fingerprint ledger replaces the marker, an UNCHANGED re-delivery (same hash, new
mtime) is still skipped silently (a counter, no batch, no signal), and a CHANGED one is re-ingested, its partition is
replaced (one file, no stale rows) and the day manifest signals one `REDELIVERED`, which the Data strip's corrected-day
banner reads. The shipped `la-daily-feed` template and the `la-showcase` / demo `mule_transfers` feed carry it.
Pinned by `LaDailyIngestAcceptanceTest#aCorrectedSameNameDayReplacesItsPartitionAndAnUnchangedOneIsSkipped` (drives
`CollectorProcessor.run`; the same test goes red on `mode: path`) and `LaDailyFeedTemplateRulesTest`. ⚠ Limits: a
date DROPPED by the correction still leaves the old file for that date (roadmap T4 b2); a `#` comment line above a block
in a pipeline TOON silently truncates the file (the `la-showcase` mule feed lost its `collector:` block that way until
this change).

---

## 12. Testing what you author

- **Run to here** — `POST /pipelines/authored/{id}/run?to=` (`PipelineGraphRoutes.testRun`,
  `canAuthorWorkbench`; backed by `PipelineTestRun`/`PipelineExecutor.dryRun`/`PipelineDryRun`): parses picked inbox
  files through the real ingest path into a scratch root (zero production side effects), previewing the graph over
  parsed rows. `to=` bounds the walk to the **ancestor closure** of the chosen Step; unknown `to=` is 400. Opened via
  `PipelineEditorComponent.openRunToHere()` / `RunToHereDialog`. This runs the actual compiled SQL, so an expression
  typo or a missing join reference WOULD surface here. Details:
  [Pipeline spec — execution](spec-pipeline.md).
- **Test this Step** (drawer inline preview) — `POST /components/{transform|sink}/preview` runs an
  **inline** config over the tab's parsed rows: exactly what the operator is mid-writing.
  🔴 The gate is the Step's own TYPE (`testFamily()`), ⛔ never `data.bindKind` — `bindKind` is null
  for everything that reaches the drawer, which is exactly why a predecessor affordance was
  unreachable dead UI whose specs passed by constructing the dialog directly. 🔴 The body goes
  through the same `buildConfiguredNode` the save path uses — a second merge would drift and test
  a config that never ships. ⚠ The transform arm sends the Step's `type` INSIDE `config` or the
  route 422s. Rows arrive as plain `sampleRows` (the thread belongs to the TAB).
- ⛔ **An inline-config Step of other kinds cannot be tested by any existing backend surface** —
  the component `/test` routes resolve through `ComponentStore` and 404 on absence; do not "finish"
  this without a deliberate decision.
- **Test a bound component** — "Test <component>…" opens `ComponentFormDialog` (the one surface
  that collects a sample); `TESTABLE_KINDS` = transform/grammar/sink.
- **Dry-run** — offers **"Use the captured sample (N rows)"** from the tab's thread. ⚠ The panel is
  mounted once and outlives tab switches: its outcome is stamped with the pipeline id it came from
  and `result`/`error` are computed against the current one. ⛔ Never replace that with an effect
  that clears on id change (effects flush on Angular's schedule and can wipe a fresh result).
  ✅ **A test run's own rows seed the thread** (`WB-12`, 2026-09-22), so the captured sample is one
  click after *Run to here*. 🔴 Fixing it exposed a dead seam: `RunToHereDialog`'s Close carried no
  value, so `afterClosed()` always emitted `undefined` and every consumer of the run result — including
  the node-tested marking — was unreachable. Row `DRYRUN-SEEDS-AFTER-PARSE-1`.
- **Save-time vs test-time.** Present at save time: the schema backward-compatibility diff (§7.2); mapping-CSV shape
  validation; the `SchemaMappingDrift` check; `transform.summarize`'s `measures`/`group_by` validators; join reference
  checks (§8.7); Angular reactive-form required/pattern checks. Anything else (a typo'd expression on the legacy path,
  a join key absent from the reference dataset, an out-of-range cast) surfaces only at test-time or full run-time.

---

## 13. Save, validation, refusals and history

### 13.1 Save and the dock

Save lowers the graph to the flat `*_pipeline.toon` (`PUT /pipelines/{name}/graph`). **Save
refusals all land in the Validation dock** (persistent, click-to-select-Step), never a first-only
toast. Apply (drawer) patches in memory; Save persists; the per-tab dirty flag guards both.

**The save route runs the arming pre-checks** (2026-09-01): `armedWithoutSchema` / `routeArming` /
`stepDisable` findings, same severity split as `/config/write` (ERROR ⇒ 422 when `active: true`,
WARNING on a draft) — before this, the editor's Save could 200 a pipeline that silently failed to
arm at the next registry rebuild. Backend detail: [Pipeline spec — model](spec-pipeline.md).

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
  skips served `LIST`. Backend: [Pipeline spec — model](spec-pipeline.md).
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
  (TRANSFER-ARCH-1; [Pipeline spec — model](spec-pipeline.md)); Duplicate and the row export still
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
  contained pass ([plan](plan.md)).
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

Design and internals: [Parsing and ingest architecture](architecture.md#parsing-and-ingest); the Run screens in depth:
[§22 Runs and Run Detail](#22-runs-and-run-detail) below.

- **Pipeline editor, Parse definition** (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-parse-definition.component.ts`):
  frontend choice, grammar form rendered from the parser's served `grammarSchema`, and a sample preview (§6).
- **Run detail** (`inspecto-ui/src/app/modules/admin/run-detail/run-detail.component.ts`): tabs
  Batches, Files, Lineage, Quarantine, Commits, Commit retries and Report, plus "View the rejected rows"
  (`GET /runs/{name}/errors?file=` — the `errors/<base>_errors.csv` reject rows, bounded, `truncated` reporting the
  true total). Trigger, pause, resume and drain are on the Run surfaces ([API](api.md#parsing-and-ingest)).
- **Processing status** (`inspecto-ui/src/app/modules/admin/processing-status/processing-status.component.ts`):
  problem files per Pipeline.
- **Collectors** (`/collectors`): *Run now* and Collector state — [§21 Collectors](#21-collectors-and-collector-configuration).

---

## 17. The Recipe view and the step cards (stale record)

🔴 **STALE — corrected 2026-09-22.** `<app-pipeline-step-cards>` **no longer exists**: it was deleted in
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

Owners: [BACKLOG](../../../../BACKLOG.md) and the [plan](plan.md#5-not-built-and-open). The behaviour described above
is the intended-and-actual state. ⚠ **Corrected 2026-09-01**: AUTHOR-1, DRYRUN-1, the §5 UI residuals and BUILDER-2 #8
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

## 21. Collectors and Collector configuration

How a person works with Collectors in the console: the **Collectors** screen that
lists every configured collection task, the **Connections** screen where endpoints and credentials are
kept, and the one form used to configure what a Collector does (local inbox, a Connection, or another
Pipeline's Dataset). It also records the rules that form follows, so the next change to it does not
re-break them. The engine side is in [the acquisition spec](spec-acquisition.md) and [architecture](architecture.md#collectors).
*(Merged from the acquire hub's user guide on 2026-11-18; §5 is the short version inside the editor.)*

### 21.1 The screens

Route `/collectors` (Workbench nav group). Lists configured collection tasks in a **standard**
[data-table](../../layer/product/spec-ds-data-table.md) (`autoHeight`). Backed by `CollectorsService`.
Design and internals: [Collectors architecture](architecture.md#collectors).

- **Collectors** — route `collectors`, `inspecto-ui/src/app/modules/admin/collectors/collectors.component.ts`,
  with the detail drawer `collector-detail.dialog.ts`.
- **Connections** — route `settings/connections` (the old `connections` path redirects),
  `inspecto-ui/src/app/modules/admin/connections/connections.component.ts`, plus the graded probe /
  explore / sample view `connection-workbench.component.ts`.

*(Renamed from "Sources" per the Source→Collector glossary flip, GLOSSARY §2/§3. <!-- vocab-allow: names the rename itself -->
The route, folder and service had already moved; the old screen doc had not — it still claimed `/sources`,
`SourcesService` and an "Acquisition" nav group.)*

### 21.2 Configuring a Collector — the one surface

One surface authors a pipeline's `collector:` block: the [pipeline editor](#2-the-pipeline-editor--shell-lenses-tabs-and-docks)'s
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
inbox. See [§5 Collect](#5-collect--the-acquisition-step) and the [`dataset` scheme](spec-acquisition.md#the-connector-spi-and-the-resolvable-schemes).

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
waiting retry. How these runs execute underneath is [spec-pipeline.md](spec-pipeline.md).
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
[spec-pipeline.md](spec-pipeline.md), Part II).

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
(backend: [execution-lanes](spec-pipeline.md) X4). Rules it follows:

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
COMMIT retry (backend: [execution-lanes](spec-pipeline.md) § *The COMMIT retry
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

- Plan of record (requirements, decisions, open work, refusals, verification): [plan.md](plan.md)
- Routes: [api.md](api.md) · Module summary: [features.md](features.md) · Design: [architecture.md](architecture.md) ·
  Step catalog: [spec-step-catalog.md](spec-step-catalog.md) · Admin: [admin-guide.md](admin-guide.md)
- Specs: [Pipeline — model, execution, sinks](spec-pipeline.md) · [ingest — Consignments, parsing](spec-ingest.md) ·
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
