# First-time user use cases: from a blank slate to a queryable Dataset

> 🟢 **§6 ANSWERED 2026-10-22 (operator):** file first is primary, detected settings are pre-applied with low
> confidence flagged, missing capabilities are shown locked with a reason, onboarding UX gets its own companion plan,
> and all nine use cases are must-have.
>
> ➡ **Edition plan: [`data-tier-editions-plan.md`](data-tier-editions-plan.md) (`DATA-TIERS-1`).** This doc was
> written for the draft `PIPELINE-EDITIONS-1`, which was merged into `DATA-TIERS-1` on 2026-10-22 and never
> published on its own. Its decision numbers map as: D-PE1 → DT-5 and DT-17 · D-PE2, D-PE3, D-PE4 → DT-16 ·
> D-PE5 → DT-7 (shaping), DT-15 (Expectations, quarantine and dedupe stay on Basic, decided) and DT-6 (lookups) ·
> D-PE6 → DT-4 (decided: files only on Basic, every remote adapter Standard) · D-PE7 → DT-12 · D-PE8 → DT-19 ·
> D-PE9 → the Outputs row and the `pipeline.egress` gate · D-PE10 → DT-18 · D-PE11 → DT-9. The onboarding experience is
> [`first-run-onboarding-plan.md`](first-run-onboarding-plan.md) (`ONBOARDING-FIRST-RUN-1`, the console) on the shared
> import route of [`la-onboarding-plan.md`](la-onboarding-plan.md) (`LA-ONBOARD-1`, Inspecto LA). The Inspecto LA side of
> the same journey: [`spec-first-time-user-use-cases.md`](../okf/product/module/la-core/spec-first-time-user-use-cases.md).
>
> 🟡 **DRAFT 2026-10-22.** It describes what a first-time user wants, what they must do today, and how the product
> could carry them the whole way. Nothing here is built.
>
> **Grounding.** The "today" steps come from two read-only surveys of the checkout on 2026-10-22 (`26a2dde24`): one of
> the console (`inspecto-ui/src/app`), one of the docs and control routes. Key claims were spot-checked (Home's
> first-run panel, the Dataset auto-registration toast, the inbox upload living in the Run-to-here dialog,
> `orders-starter` polling). Treat a file reference as a pointer to read, not as proof.
>
> **Copy rule.** Product copy never says "wizard" (`GLOSSARY.md`; `user-guide-onboarding.md`). This doc says
> **guide** for the step-by-step help.

## 1. Who the first-time user is

| Persona | Edition | Wants | Knows |
|---|---|---|---|
| **P1 Solo analyst** | Basic (no sign-in) | Get a file they already have into a table they can query and explore, today | Their data; spreadsheets; maybe SQL. Not Pipelines, Collectors or TOON |
| **P2 Data engineer** | Standard | Onboard a recurring feed that runs by itself and feeds a downstream app (Link Analysis, Cases, Reconciliation, dashboards) | Files, schedules, databases, SFTP; new to Inspecto's vocabulary |
| **P3 Downstream app user** | any | Use an app (for example Link Analysis) and needs "my data" in it | The app's domain; does not want to learn the pipeline layer at all |
| **P4 Evaluator** | any (often Basic) | Judge in ten minutes whether the product is worth adopting | Nothing about the product |

The first three goals all end at the same object: **a Dataset that is queryable and that a downstream app can
bind**. Everything below is measured against reaching it.

## 2. The journey as built today

From a blank install (P1, CSV file, Basic). ✋ = a manual step the user must know to do.

| # | Step | Where | What the user must decide or do |
|---|---|---|---|
| 1 | Open the console | `/` → `/home` (`menu-landing.ts`) | Nothing. Home shows a first-run panel with three cards when there are **zero Runs**: *Onboard a Stream*, *Author a Pipeline*, *Query it in Studio* (`home.component.html:22, 49-95`) |
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

**What already helps.** The Home first-run panel; the guided stage chips with a status word and a finding count;
deterministic inference (client sniff, server `sniff_csv`, `SchemaSuggest` with human date formats); go-live
findings that name the missing stage; Dataset auto-registration; template gating by edition; inline attribute help.

**What is missing.** No next-step prompt after a stage; no upload on Home or Catalog; no drag-and-drop; inference
never pre-applied; first-run detection keys on zero Runs, not "no Dataset yet"; Activate copy assumes polling; the
assistant answers 503 in every shipped bundle (no bundle ships its implementor, `spec-assist-agent.md`), and it is
Standard and up anyway.

## 3. Use cases

Each use case gives the goal, today's path, the friction, the **assisted path** (what the product should do), the
smart help involved (catalogue in §4), and what the edition plan must account for.

### UC-1 See value in ten minutes with sample data (P4, P1; every edition)

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

### UC-2 Onboard my first CSV into a queryable Dataset (P1; every edition) — the core use case

- **Goal.** "I have `sales_2026-10.csv`. I want to query it."
- **Today.** §2, steps 2 to 14.
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

### UC-3 Onboard a JSON or nested file (P1, P2; every edition)

- **Goal.** Load an export from an API or app that is a JSON array, NDJSON or nested records.
- **Today.** The client sniff tells array from NDJSON; nested records need `records_path` and dotted selectors typed
  by hand; the JSON tree doc still describes nested values as stringified.
- **Assisted path.** The review screen shows the record tree, proposes the repeating array as the record path, and
  lets the user tick the fields to flatten (dotted selectors generated). Arrays inside records are offered as a
  child Dataset or a JSON column, with the trade-off stated.
- **Edition plan impact.** Flattening at parse time is a parse capability (Basic). Exploding arrays into rows is
  `transform.explode`; D-PE5 must say whether that is Basic or Standard, because nested files are common first files.

### UC-4 Add a lookup table and use it (P1, P2)

- **Goal.** "My sales have a `store_id`; I have a stores list; I want the store name and region on each row."
- **Today.** Onboard a **Reference** (Keys & Load stage, full replace by default), then add an Enrich stage on the
  Stream that joins it.
- **Assisted path.** When a second file shares a column with an existing Dataset, the product says so ("`store_id`
  matches 98% of `sales.store_id`") and offers *Use as a lookup*. The Enrich stage pre-fills the join and previews
  the match rate and unmatched keys.
- **Edition plan impact.** D-PE5 puts enrichment in Standard. Then Basic should still let the user onboard a
  Reference and **query** both (a join in Studio), while the in-pipeline enrich shows a **locked** affordance with a
  plain reason (H4), never a dead end. Decide whether Basic onboards References at all.

### UC-5 The next file arrives (P1 Basic, P2 Standard)

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

### UC-6 Connect a remote system (P2; Standard)

- **Goal.** Pull files from SFTP or S3, or rows from Postgres, on a schedule.
- **Today.** Create a Connection (`canOnboardConnections`; secrets), then pick it in the Collect stage.
- **Assisted path.** The Collect stage offers *Connect a system*: pick a kind, fill the form, **Test connection** with
  a plain-language result (DNS, port, auth, path, host-key), browse the remote folder or table, pick a sample file
  from it to drive the rest of UC-2's review.
- **Edition plan impact.** D-PE6 makes remote adapters Standard and up. On Basic the Collect stage shows local inbox
  and upload, and *Connect a system* as locked (H4).

### UC-7 My first run failed or quarantined rows (every persona; every edition)

- **Goal.** Understand what went wrong and fix it without reading logs.
- **Today.** Run detail shows status, counts and quarantine; findings show in the editor dock.
- **Assisted path.** A failed or partly quarantined run names the **cause class** and the **fix**: "`amount` has
  `1.234,50` in 40 rows — this file uses `,` as the decimal mark → *Switch decimal mark*", "date `31/02/2026` is not
  a real date (2 rows) → kept in quarantine, *Show rows*". One click applies the fix to the draft and *Re-run*.
- **Edition plan impact.** None: correctness and its explanations are in every edition.

### UC-8 Feed a downstream app or a dashboard (P3, P2)

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

### UC-9 Start from my app, not from the pipeline layer (P3)

- **Goal.** Inside Link Analysis (console or standalone app): "add my data" without visiting the Catalog.
- **Assisted path.** The app's empty state offers *Add data*: it runs UC-2's file-first path inline, with the
  review screen pre-asking the app's needs (which columns are the entities, which the time), then returns to the
  app with the data bound.
- **Edition plan impact.** The pipeline layer is the platform under the apps (the plan's framing): its onboarding must
  be embeddable as one reusable path, not tied to the Catalog page.

## 4. Smart help catalogue

| ID | Help | What it does | Edition | Exists today? |
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

## 5. What this means for the edition plan (for refinement)

Points that should change or extend the edition plan before it is finalised, as written on 2026-10-22 (the D-PE
numbers map to `DATA-TIERS-1` as in the note at the top). Points 1, 2 and 5 became DT-13 and DT-14; points 3 and 4
are DT-6 and DT-7; points 6 and 7 are `ONBOARDING-FIRST-RUN-1`.

1. **D-PE1 needs a manual-run experience, not just a gate.** Manual-only Basic only works if *Add a file* and *Run
   now* sit together on the Stream and Dataset pages and in the guide (UC-2, UC-5). Today *Run now* is on the
   Collectors pane and upload is inside the test dialog. Activate copy ("next poll cycle") must follow the edition.
2. **`orders-starter` must load and run its samples from a button** (UC-1), since it will not poll on Basic.
3. **D-PE5 must place `transform.explode` and enrichment with nested files and lookups in mind** (UC-3, UC-4). If
   they go to Standard, Basic needs the query-time alternative (a join in Studio) and a locked affordance (H4).
4. **Decide whether Basic onboards References** (UC-4).
5. **Locked, not hidden** (H4) should be a plan rule for every gate it adds: the step palette, triggers, remote
   Collect, egress Job types and downstream offers. The plan today says "hide or badge"; the use cases argue for
   badge with reason.
6. **New work outside the gates**, a candidate companion plan rather than part of this one: file-first onboarding
   (UC-2), explained inference (H2), findings with fixes (H3), the Space guide (H1), drop-a-file empty states (H8),
   column-based suggestions (H9), and an embeddable onboarding path for apps (UC-9).
7. **First-run detection** should key on "no Dataset yet", not "zero Runs", so the guide stays useful after a failed
   first run.

## 6. Questions for the operator — ANSWERED 2026-10-22

| # | Question | Operator answer (2026-10-22) |
|---|---|---|
| Q1 | Is UC-2 (file first, one review, one confirm) the primary path, with the guided editor as the expert path? | **Yes: file first is primary.** Home, the Catalog and empty Datasets panes start with a file drop; the guided editor stays one click away (*Open in editor*) |
| Q2 | Pre-apply detected settings, or offer them as chips? | **Pre-apply, flag low confidence.** Every detected choice goes into the draft with its evidence; low-confidence choices are highlighted and need a click to confirm. What is saved is exactly what runs (ingest stays deterministic) |
| Q3 | Locked-with-reason (H4) or hidden for capabilities the edition lacks? | **Locked with reason**, everywhere: the control is shown disabled with one line on why and which edition has it |
| Q4 | Onboarding UX work in the edition plan, or its own plan? | **Its own companion plan** (`ONBOARDING-FIRST-RUN-1`). The edition plan takes only what its gates need (`DATA-TIERS-1` DT-14) |
| Q5 | Which use cases are must-have for the first release? | **All nine** (UC-1 to UC-9) |

**Consequences recorded in the edition plan** (`DATA-TIERS-1` DT-13 and DT-14): every gate shows its control locked
with a reason; *Add a file* and *Run now* sit together on Basic; Activate copy follows the edition; `orders-starter`
gets a load-and-run sample button. **Everything else in §5 point 6, plus pre-applied inference (Q2), file-first
entry points (Q1) and first-run detection (§5 point 7), is
[`first-run-onboarding-plan.md`](first-run-onboarding-plan.md).**
