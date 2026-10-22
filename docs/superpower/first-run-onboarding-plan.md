# First-run onboarding: from a blank install to a Dataset an application can use (`ONBOARDING-FIRST-RUN-1`)

**Status: PLAN 2026-10-22 for operator review — nothing built.** The direction is decided (§2, operator
2026-10-22); the open decisions OB-1…OB-6 (§8) need answers before their slices start. When a slice ships, distil
the as-built facts into the product hubs (`layer/product`, `module/etl`, `module/la-core`, `edition/basic`), close
its board rows, and archive this file when the last slice ships.

**Inputs.**

- [`pipeline-editions-first-run-use-cases.md`](pipeline-editions-first-run-use-cases.md): the console, nine use
  cases (UC-1…UC-9) and a smart-help catalogue (H1…H9).
- `docs/okf/product/module/la-core/spec-first-time-user-use-cases.md` (drafted in parallel, not yet committed when
  this was written): Inspecto LA, fifteen use cases (UC-01…UC-15) and a live blank-slate walkthrough (findings F-1…F-13).
- [`data-tier-editions-plan.md`](data-tier-editions-plan.md) (`DATA-TIERS-1`): what each edition may do. This plan
  builds the experience; that plan decides the limits. Its phase 1 ("Add data", DT-1) is built here.

**Copy rule.** Product copy never says "wizard" (`GLOSSARY.md`). The step-by-step help is the **guide**; the
running list of setup steps is the **setup checklist**.

## 1. Goal and measures

A first-time user reaches **a Dataset an application can use** (queryable in the console, drawable in Inspecto LA)
without learning the pipeline layer, on every edition, with help that never dead-ends.

| Measure | Today | Target |
|---|---|---|
| Steps from a file to a queryable Dataset (console) | about 11 manual steps over 5 panes | one drop, one review, one confirm, no pane change |
| From a file to a first graph (Inspecto LA, Basic) | impossible in the UI; 9 API calls (F-3, F-4) | the same three gestures plus "what the columns hold" |
| Pipeline concepts a Basic user meets | about 6 (Collector, Pipeline, schema, parse settings, sink, trigger) | none; "one-time import" only |
| Hidden knowledge needed | upload in a test dialog, *Run now* on another pane, detection only on click | none |

How the measures are taken is OB-6.

## 2. Decided (operator, 2026-10-22)

1. **File first is the primary path.** Home, the Catalog, the empty Datasets pane and Inspecto LA's empty "Explore
   a Dataset" card start with a file drop. The guided Pipeline editor stays one click away (*Open in editor*) as the
   expert path.
2. **Detected settings are pre-applied** to the draft, each with its evidence; low-confidence choices are
   highlighted and need a click to confirm. What is saved is exactly what runs: ingest stays deterministic
   (`auto_detect=false`, typed casts).
3. **Locked with a reason, never hidden** (also `DATA-TIERS-1` DT-13).
4. **Onboarding is its own plan** (this one); the edition plan keeps only what its gates need (DT-14).
5. **All nine console use cases are must-have.** The Inspecto LA data-in use cases (UC-01…UC-07) share the same
   building blocks and ride with them.

## 3. Use cases to building blocks

| Building block (§4) | Console use cases | Inspecto LA use cases |
|---|---|---|
| B1 Setup checklist and getting started | UC-1, UC-2 (H1) | UC-01, UC-14, UC-15 |
| B2 First Space | UC-1 | UC-02 (F-2) |
| B3 "Add data" | UC-2, UC-3, UC-9 (H8) | UC-03, UC-04 (F-3, F-4) |
| B4 Explained detection | UC-2, UC-3 (H2) | UC-03 step 2, UC-07 |
| B5 Findings with fixes | UC-7 (H3) | UC-15 |
| B6 The Dataset page | UC-5, UC-8 (H9) | UC-05, UC-06, UC-08, UC-14 |
| B7 Sample data | UC-1 (H7) | UC-01 |
| B8 Locks, empty states, contextual help | all (H4, H5) | UC-13, UC-15 (F-1, F-10) |
| B9 Lookups | UC-4 (H9) | UC-04 |
| B10 Remote systems (Standard) | UC-6 | UC-06 |
| B11 Embedded in applications | UC-8, UC-9 | UC-03 (from the LA card), UC-08 |

The Inspecto LA steps after the data is in (focus, first graph, grow, discover, keep and share: UC-09…UC-13) and
the LA-only findings (F-6…F-12) stay with the Link Analysis work; this plan supplies their starting point.

## 4. Building blocks

### B1 Setup checklist and getting started

- A **Getting started** panel replaces Home's three cards (console) and the LA home cards while setup is
  incomplete. Choices: *Try it with sample data* (B7), *Use my own data* (B2 then B3), *Join my team's data* (when
  Spaces with Datasets exist).
- A **setup checklist** stays visible, collapsible, until done: *Space · Data · (LA) What the columns hold · First
  query or first graph · (optional) a list*. Every tick is **computed from server state**, never clicked.
- First-run detection keys on **"no Dataset yet"**, not "zero Runs" (`home.component.ts:147` today), so the
  checklist survives a failed first run.
- One component and one state read, used by the console and by `la-app`.

### B2 First Space

- With zero Spaces, both UIs offer *New Space* (today Inspecto LA draws no switcher at all, F-2; the console's
  Spaces pane offers *New from template* on a multi-space server).
- Name; *What kind of data?* (pack: Mobile Money, Telecom, Generic, or a vertical template, with three example
  column names each); in Inspecto LA also *What do you look at first?* (the primary entity, LA Q-7).
- A template the edition cannot run stays visible, **locked with its reason** (today `missingFeatures`).
- Create, then straight into B3.

### B3 "Add data" — one path, every edition (DT-1)

One server-side composition and one UI, embeddable anywhere (Home, Catalog, Datasets, the LA card).

1. **Choose the file.** Drag and drop or pick: CSV, Parquet, JSON, NDJSON (Excel per LA Q-2); a one-off Postgres
   snapshot on Basic (DT-4). Limits shown before upload (DT-8).
2. **Check the columns.** The first rows as they will load, with every detected choice pre-applied and explained
   (B4); no-code shaping (types, rename, choose columns, filter rows, dedupe; DT-7). Problems counted, not hidden
   ("12 rows have no `to_account`").
3. **What the columns hold** (shown when the target application needs it, always in Inspecto LA): *who* columns →
   Entity Type, *link* columns → from / to, optional kind, time, amount; the rest → details. Proposed, then
   confirmed.
4. **Name and create.** The name is proposed from the file name (a date in it recognised as a file-name date). One
   confirm saves the hidden Collector and Pipeline (DT-2), **runs it once**, registers the Dataset (today
   `DatasetRegistrationService.ensure` runs only at Activate) and, in Inspecto LA, saves the Entity Type mapping and
   a default link mapping.
5. **Land on the Dataset** (B6), or back in the application that started it (B11).

**Server.** One route, `POST /data/import` (name to be fixed with the endpoint skill; OB-1), composes what exists:
the inbox upload (`PipelineInboxRoutes`), schema suggestion (`/config/suggest/schema`), the config write and
register (`POST /config/write`, `POST /runs`), a one-shot run, and Dataset registration. It reuses
`LA-ANALYST-UPLOAD-1`'s landing and ingest chain and moves its 503-on-Basic gate. It answers on Basic: it is the
Basic way in. The four endpoint gates apply.

**Expert path.** *Open in editor* opens the same Pipeline in the guided editor; on Standard it is an ordinary,
editable Pipeline (DT-2, `DATA-TIERS-1` phase 7).

### B4 Explained detection

- Detection exists: the client sniff (`parsing-sniff.ts`: JSON array, NDJSON, delimiter), the server dialect sniff
  (`ComponentPreview.sniffDialect` over DuckDB `sniff_csv`), and `SchemaSuggest` (TRY_CAST vote, human date formats).
  What is missing is **evidence, confidence and pre-application.**
- Each detector returns, per choice, the value, its evidence ("every one of 20 lines has 7 columns"; "100% of 500
  values parse as `%d/%m/%Y`") and a confidence class (high / low); the threshold rule is OB-2.
- New detections: the record path and fields to flatten for nested JSON (console UC-3); file-name dates; value
  shapes for Entity Types (phone number lengths, IMEI check digit, IBAN pattern, e-mail; LA UC-07); from / to
  columns from names and value overlap.
- Pre-applied to the draft; low-confidence choices highlighted and confirm-gated (§2 point 2).

### B5 Findings with fixes

- A small **fix catalogue**: decimal mark, date format, delimiter, header row, encoding, quote character, a
  required column missing. Each save finding, run failure or kept-aside row reason that matches a catalogue entry
  carries a plain cause and a one-click fix ("`amount` has `1.234,50` in 40 rows → *Switch decimal mark*").
- A fix edits the draft and offers *Re-run*; nothing is applied silently.
- In every edition: correctness explanations are not an edition feature.

### B6 The Dataset page

- Summary: rows, time span, last import, rows kept aside with why (DT-15), "one-time import" or "kept up to date
  by …" (LA UC-05).
- **Manual-run gesture**: *Add a file* and *Run now* together; *Re-import* replaces and says how many rows
  changed (DT-14, DT-5). On Standard also **Keep this up to date** (watch a folder, a schedule; next run time
  shown); on Basic the switch is locked with its reason; without the right to configure it becomes *Ask an
  administrator* (LA UC-06).
- **What this Dataset can feed**, from its columns (H9): "looks like call records → *Explore as a network in Link
  Analysis*"; "has a date and an amount → *Make a starter dashboard*" (`POST /bi/templates/{id}/apply` exists);
  offers the edition cannot fulfil are locked with their reason.
- In Inspecto LA: the default link mapping, editable (LA UC-08).

### B7 Sample data

- `orders-starter`: *Load the sample data* copies the samples into the inbox and runs the Pipeline (today a manual
  copy on the server's disk; DT-14), then offers the dashboard and a query.
- Inspecto LA: *Try it with sample data* creates the `la-showcase` Space and opens a ready graph, saying plainly
  that the data is invented (LA UC-01). Offered to users who may create Spaces (LA Q-8).

### B8 Locks, empty states and contextual help

Rules for every screen (from LA UC-15, which the console adopts):

1. Every empty state names the reason and offers the one next action (F-1).
2. Every refusal names the feature and the edition once, then offers what this edition can do instead (F-10). The
   shared **locked-with-reason** component (DT-13) reads the server's `refusal()` text.
3. Product terms appear with a plain explanation on first use (Dataset = "a table Inspecto keeps").
4. The setup checklist and *What can I do next?* are always one click away.
5. Each stage and finding links to the one doc section that explains it (H5).
6. The assistant (H6) is offered only where it is available, with the reason when it is not; Basic help is
   deterministic.

### B9 Lookups (References)

- *Add data → It describes entities* onboards a Reference: "Which column identifies the entity?"; the other
  columns become details; re-import replaces and says how many rows changed (LA UC-04).
- When a new file shares a column with an existing Dataset the product says so ("`store_id` matches 98% of
  `sales.store_id`") and offers *Use as a lookup*.
- Edition: showing details on nodes and filtering by them on Basic; enrichment and joins Standard (DT-6), locked
  with their reason on Basic, with a query-time join in Studio as the Basic alternative.

### B10 Remote systems (Standard)

- The Collect stage and "Add data" offer *Connect a system*: kind, form, **Test connection** with a plain result
  (DNS, port, auth, path, host key), browse the remote folder or table, pick a sample to drive B3 step 2.
- On Basic: locked with its reason, except the one-off Postgres snapshot (DT-4).

### B11 Embedded in applications

- B3 is one embeddable component: Inspecto LA's empty "Explore a Dataset" card and *Add data* run it inline and
  return to the application with the Dataset bound (DT-1, LA UC-03).
- An application can pass what it needs (for example "which columns are entities, which is time") so step 3
  asks only that.
- Inspecto LA never shows Pipelines; anything beyond "Keep this up to date" hands off to the console with the
  Pipeline pre-filled (LA Q-6).

## 5. Edition behaviour

| Block | Basic | Standard | Advanced |
|---|---|---|---|
| B1, B2, B4, B5, B7, B8 | ✅ | ✅ | ✅ |
| B3 "Add data" | ✅ (limits DT-8; Postgres snapshot DT-4) | ✅ (and *Keep this up to date*) | ✅ |
| B6 Dataset page | ✅ manual run and re-import; *Keep this up to date* locked | ✅ | ✅ |
| B9 Lookups | details and filters only; joins locked | ✅ | ✅ |
| B10 Remote systems | locked (except the Postgres snapshot) | ✅ | ✅ |
| B11 Embedded | ✅ | ✅ | ✅ |
| Assistant help (H6) | — (deterministic help only) | ✅ where configured | ✅ |

## 6. Prerequisite fixes (from the Inspecto LA walkthrough)

Cross-cutting findings this plan depends on; reported in the LA use cases and not re-verified here (OB-5).

| Finding | What | Needed by |
|---|---|---|
| F-2 | With zero Spaces there is no Space switcher and no *New Space* | B2 |
| F-3 | Basic has no way to bring data in (the upload is inside Investigations, 503 on Basic) | B3 |
| F-4 | `/config/suggest/schema` proposes column selectors by name, which registration refuses ("must be a zero-based column index"); fixing them trips the schema compatibility gate | B3, B4 |
| F-13 | `GET /datasets` answers 200 with the app's HTML instead of 404 (the list is `/components/dataset`) | B6 |

## 7. Slices

Each slice: tests in house style (real-HTTP for routes, vitest specs for the SPA, falsified negative tests), the
affected-build protocol, an independent `verification` PASS, one lane per push. UI slices apply the `angular-ui`
skill (design system, WCAG 2.2 AA, axe gate).

| Slice | Content | Depends on | Done when |
|---|---|---|---|
| O0 | Answer §8; verify the §6 findings; file defect rows for those that hold | — | decisions recorded |
| O1 | Prerequisite fixes F-2, F-4, F-13 | O0 | each has a failing-then-passing test |
| O2 | B4 detection with evidence and confidence (server and client), nested JSON record path, value shapes | O0 | detectors return evidence; property tests over sample files |
| O3 | B3 server: the import route, hidden Collector + Pipeline, one-shot run, Dataset registration, limits, Basic gate move | O1, `DATA-TIERS-1` DT-1, DT-2, DT-4, DT-8 | a Basic real-HTTP test goes file → Dataset in one call |
| O4 | B3 UI as one embeddable component; entry points on Home, Catalog, Datasets and the LA card (B11) | O2, O3 | file to queried Dataset in three gestures, driven in the preview |
| O5 | B1 setup checklist and getting started; B2 first Space; B7 sample data (`orders-starter` button, `la-showcase`) | O3 | a blank install reaches a populated dashboard and a first graph with no file-system access |
| O6 | B6 Dataset page: summary, manual-run gesture, re-import, *Keep this up to date* (Standard), feed offers | O3, `DATA-TIERS-1` phase 3 | console UC-5 and UC-8 pass on Basic and Standard |
| O7 | B5 fix catalogue; B8 rules, the shared locked-with-reason component, contextual doc links | O2; DT-13 | every catalogue entry has a test that applies its fix and re-runs |
| O8 | B9 lookups; B10 remote systems with test and browse | O4, `DATA-TIERS-1` phase 4 | console UC-4 and UC-6 pass; Basic shows the locks |

O1 and O2 can start together; O5 to O8 can run in parallel once O3 and O4 are in.

## 8. Open decisions

| # | Decision | Recommendation |
|---|---|---|
| OB-1 | One server route for "Add data", or the UI composing the existing routes | **One route**: the console and Inspecto LA share it, the Basic gate and the limits sit in one place, and a test can prove file → Dataset in one call |
| OB-2 | The confidence rule for pre-applied choices | High when every sampled value agrees and the sample has at least 50 non-blank values; low otherwise; low is highlighted and confirm-gated |
| OB-3 | Does "Add data" register the Dataset before or after the first run succeeds | After a run that loaded at least one row; a run that loads nothing leaves the draft and the fixes (B5), not an empty Dataset |
| OB-4 | Excel (LA Q-2) | Not until a native reader exists (DT-8); the file picker says "save as CSV" |
| OB-5 | The LA walkthrough's findings F-2, F-4, F-13 | Verify in O0 (claims are hypotheses), then file each that holds as a defect row |
| OB-6 | Measuring the §1 targets (LA Q-10) | Local and anonymous, off by default on Basic; time from launch to first Dataset and checklist completion |

LA questions Q-1 (Space per case or per domain), Q-7 (primary entity per Space) and Q-8 (sample Space for whom)
are answered in the LA use cases' own refinement; this plan follows them.

## 9. Not confirmed

- Whether `DatasetRegistrationService.ensure` can be called server-side from the import route or is UI-only today
  (it is a console service; the server-side equivalent was not surveyed).
- Whether `LA-ANALYST-UPLOAD-1`'s ingest chain can run without an existing Collector.
- The Inspecto LA findings in §6 (OB-5).

## 10. Board

P2 `ONBOARDING-FIRST-RUN-1` in `BACKLOG.md` §3.4 (hold: operator), indexed in §1 *Decisions owed on individual
rows*.
