# Console first-run onboarding: from a blank install to a queryable Dataset (`ONBOARDING-FIRST-RUN-1`)

**Status: PLAN 2026-10-22 for operator review — nothing built.** The direction is decided (§2, operator
2026-10-22); the open decisions OF-1…OF-3 (§7) need answers before their slices start. When a slice ships, distil
the as-built facts into the product hubs (`layer/product`, `module/etl`, `module/processor`), close its board rows,
and archive this file when the last slice ships.

**What this plan is.** The **console companion** that [`la-onboarding-plan.md`](la-onboarding-plan.md)
(`LA-ONBOARD-1`) expects in its OB-1: `LA-ONBOARD-1` builds the Inspecto LA screens **and the shared server pieces**
(the `/onboard/files` import route, sample loading, the locked-with-reason component, local counters, the
walkthrough fixes); this plan puts the **console** on those pieces and adds what only the console needs. Decision
numbers here are **OF-n**, to stay apart from `LA-ONBOARD-1`'s OB-n.

**Inputs.**

- [`pipeline-editions-first-run-use-cases.md`](pipeline-editions-first-run-use-cases.md): the console, nine use
  cases (UC-1…UC-9) and a smart-help catalogue (H1…H9), §6 answered by the operator.
- [`okf/product/module/la-core/spec-first-time-user-use-cases.md`](../okf/product/module/la-core/spec-first-time-user-use-cases.md):
  Inspecto LA, fifteen use cases (UC-01…UC-15), walkthrough findings F-1…F-13, operator answers A-1…A-16.
- [`data-tier-editions-plan.md`](data-tier-editions-plan.md) (`DATA-TIERS-1`): what each edition may do.

**Copy rule.** Product copy never says "wizard" (`GLOSSARY.md`). The step-by-step help is the **guide**; the running
list of setup steps is the **Space guide** (H1). The button says **Add data**; the screen title says **Onboard a
file** (`LA-ONBOARD-1` OB-14), so "Onboard" stays the one verb.

## 1. Goal and measures

A first-time console user reaches **a Dataset they can query** without learning the pipeline layer, on every
edition, with help that never dead-ends.

| Measure | Today | Target |
|---|---|---|
| Steps from a file to a queryable Dataset | about 11 manual steps over 5 panes (use cases §2) | one drop, one review, one confirm, no pane change |
| Hidden knowledge needed | upload in a test dialog, *Run now* on another pane, detection only on click | none |
| Pipeline concepts met before the first Dataset | Stream, Pipeline, Collector, stage, trigger | none; the guided editor stays available (*Open in editor*) |

Counted by `LA-ONBOARD-1`'s onboarding counters (its OB-13): always on, local, never sent anywhere (A-10).

## 2. Decided (operator, 2026-10-22)

1. **File first is the primary path.** Home, the Catalog and the empty Datasets pane start with a file drop; the
   guided Pipeline editor stays one click away (*Open in editor*) as the expert path.
2. **Detected settings are pre-applied** to the draft, each with its evidence; low-confidence choices are
   highlighted and need a click to confirm. What is saved is exactly what runs (ingest stays deterministic).
3. **Locked with a reason inside screens; the navigation stays lean** (A-11, `DATA-TIERS-1` DT-13).
4. **Onboarding is its own plan**; the edition plan keeps only what its gates need (DT-14).
5. **All nine console use cases are must-have.**
6. **The Inspecto LA answers apply to the console where they touch it:** files only on Basic (A-12, DT-4);
   formats CSV / TSV, Parquet, JSON / NDJSON (A-2) plus Excel when the bundle carries the DuckDB `excel` extension
   (DT-8); the import creates and runs the Pipeline behind the scenes (A-6, DT-2); the primary entity belongs to a
   Dataset (A-7); deterministic help only on Basic (A-9); beginner measures always on and local (A-10); in a
   sign-in Space analyst imports are a per-Space switch, off by default, with `canUploadData` (A-15).

## 3. Who builds what

| Piece | Built by | Console use cases |
|---|---|---|
| `/onboard/files` draft + commit, *Add a file*, *Run now* (`POST /datasets/{id}/files`, `POST /datasets/{id}/run`); failed commit leaves nothing | `LA-ONBOARD-1` phase 2 (OB-2, OB-3) | UC-2, UC-3, UC-5 |
| Suggested selectors as column indexes (F-4); unknown `/api/…` paths answer 404 (F-13) | `LA-ONBOARD-FIXES-1` (OB-4) | UC-2 |
| Reference kind on import (entity details) | `LA-ONBOARD-1` phase 2 | UC-4 |
| Value-shape detection for Entity Types and from / to | `LA-ONBOARD-1` phase 2 | UC-8 |
| Space guide ticks from server state; first run keyed on "no Dataset yet" | `LA-ONBOARD-1` phase 3 | UC-1, UC-2 |
| Sample Space that loads and runs (`POST /spaces {template, loadSamples: true}`) | `LA-ONBOARD-1` OB-12 | UC-1 |
| Locked-with-reason component (`inspecto-locked-action`, `/bootstrap` `featureReasons`) | `LA-ONBOARD-1` OB-10 | all |
| Help rules (empty-state reason + next action, plain terms, *What can I do next?*) | `LA-ONBOARD-1` phase 8, adopted here | all |
| **Format evidence in the draft**: delimiter, header, quoting, date formats, file-name dates, the record path and fields to flatten for nested JSON, with confidence (B2) | **this plan**, contributed to the `/onboard/files` draft | UC-2, UC-3 |
| **Fix catalogue** behind "problems in plain words" (B3) | **this plan**, server side, used by both UIs | UC-7 |
| **Console entry points, Add data in the console, the console Space guide** (B1) | **this plan** | UC-1, UC-2 |
| "Start from my app" (UC-9): Add data inside Inspecto LA | `LA-ONBOARD-1` phase 4 | UC-9 |
| **Console Dataset page** (B4) | **this plan** | UC-5, UC-8 |
| **Console lookups** (B5) and **remote systems** (B6, Standard) | **this plan** | UC-4, UC-6 |
| Edition gates, Activate copy, the manual-run gesture placement | `DATA-TIERS-1` (DT-13, DT-14) | UC-5 |

## 4. Building blocks (this plan)

### B1 Console entry points, Add data and the Space guide

- **Getting started** replaces Home's three first-run cards while setup is incomplete (*Try it with sample data*,
  *Add data*, *Open my team's data*), with the **Space guide** beside it (*Space · Data · First query · (optional) a
  dashboard*), reading `LA-ONBOARD-1`'s guide state.
- **Drop a file** on Home, on the Catalog, or on an empty Datasets pane (H8) opens **Add data** inline.
- **Add data** is the same screen as Inspecto LA's (`LA-ONBOARD-1` phase 4): choose the file → check the columns
  (pre-applied, with evidence; no-code shaping, DT-7) → what the columns hold (optional in the console; the
  primary entity per A-7) → name and create (runs once) → land on the Dataset page (B4). Whether this is one
  component shared by both apps is OF-1 (recommended: yes).
- **Expert path.** *Open in editor* on the review screen and on the Dataset page opens the import's Pipeline in the
  guided editor (an ordinary Pipeline marked `origin: import`, `LA-ONBOARD-1` OB-3). The Catalog's *Onboard
  Stream / Reference* dialog stays as the advanced start (OF-3).
- On Basic, *From a database or a remote system* is shown locked with its reason (DT-4).

### B2 Format evidence in the draft

- Detection exists: the client sniff (`parsing-sniff.ts`), the server dialect sniff (`ComponentPreview.sniffDialect`
  over DuckDB `sniff_csv`) and `SchemaSuggest` (TRY_CAST vote, human date formats). What is missing is evidence,
  confidence and pre-application.
- The `/onboard/files` draft returns, per choice, the value, its evidence ("every one of 20 lines has 7 columns";
  "100% of 500 values parse as `%d/%m/%Y`") and a confidence class (OF-2).
- New detections: the record path and the fields to flatten for nested JSON (UC-3; exploding arrays into rows is
  `transform.explode`, Standard per DT-7); dates in the file name; decimal mark.
- `LA-ONBOARD-1` adds value shapes for Entity Types on the same draft; one evidence format serves both.

### B3 Fix catalogue

- Entries: decimal mark, date format, delimiter, header row, encoding, quote character, a required column missing.
  Each draft finding, commit refusal or quarantine reason that matches an entry carries a plain cause and a
  one-click fix ("`amount` has `1.234,50` in 40 rows → *Switch decimal mark*").
- A fix edits the draft and offers *Re-run*; nothing is applied silently. Every edition (correctness is not an
  edition feature). Inspecto LA's "problems in plain words" (`LA-ONBOARD-1` phase 4) reads the same catalogue.

### B4 Console Dataset page

- Summary: rows, time span, last import, quarantined rows with why (DT-15), "one-time import" or "kept up to date
  by …"; the primary entity, changeable (A-7).
- **Manual-run gesture**: *Add a file* and *Run now* together; *Re-import* replaces and says how many rows changed
  (DT-14, DT-5). On Standard also **Keep this up to date** (watch a folder, a schedule; next run time shown); on
  Basic the switch is locked with its reason; without the right to configure it becomes *Ask an administrator*.
- **What this Dataset can feed**, from its columns (H9): "looks like call records → *Explore as a network in Link
  Analysis*"; "has a date and an amount → *Make a starter dashboard*" (`POST /bi/templates/{id}/apply` exists);
  offers the edition cannot fulfil are locked with their reason.

### B5 Console lookups (References)

- *Add data → It describes entities* uses the Reference kind of the import route.
- When a new file shares a column with an existing Dataset the console says so ("`store_id` matches 98% of
  `sales.store_id`") and offers *Use as a lookup*.
- Basic shows a Reference's details and filters by them (A-5); enrichment and joins are Standard (DT-6), locked
  with their reason, with a query-time join in Studio as the Basic alternative.

### B6 Remote systems (Standard)

- *Connect a system* in Add data and in the guided editor's Collect stage: kind, form, **Test connection** with a
  plain result (DNS, port, auth, path, host key), browse the remote folder or table, pick a sample to drive the
  draft.
- On Basic: locked with its reason, databases included (DT-4).

## 5. Edition behaviour

| Block | Basic | Standard | Advanced |
|---|---|---|---|
| B1 entry points, Add data, Space guide | ✅ files only (DT-4), formats and limits DT-8 | ✅ | ✅ |
| B2 format evidence, B3 fix catalogue | ✅ | ✅ | ✅ |
| B4 Dataset page | ✅ manual run and re-import; *Keep this up to date* locked | ✅ | ✅ |
| B5 lookups | details and filters only; joins locked | ✅ | ✅ |
| B6 remote systems | locked, databases included | ✅ | ✅ |
| Assistant help (H6) | — (deterministic help only) | ✅ where configured | ✅ |

## 6. Slices

Each slice: tests in house style (real-HTTP for routes, vitest specs for the SPA, falsified negative tests), the
affected-build protocol, an independent `verification` PASS, one lane per push. UI slices apply the `angular-ui`
skill (design system, WCAG 2.2 AA, axe gate).

| Slice | Content | Depends on | Done when |
|---|---|---|---|
| C0 | Answer §7 | — | decisions recorded |
| C1 | B2 format evidence and confidence in the `/onboard/files` draft; nested JSON record path; file-name dates; decimal mark | `LA-ONBOARD-1` phase 2 contract | the draft returns evidence; property tests over sample files |
| C2 | B3 fix catalogue (server), wired into draft findings, commit refusals and quarantine reasons | C1 | every entry has a test that applies its fix and re-runs |
| C3 | B1 console entry points and Add data (the shared component) | `LA-ONBOARD-1` phases 2 and 4, C1 | file to queried Dataset in three gestures, driven in the preview |
| C4 | B1 Getting started and the console Space guide; *New from sample* on the Spaces pane | `LA-ONBOARD-1` phase 3 and OB-12 | a blank install reaches the `orders-starter` dashboard with no file-system access |
| C5 | B4 console Dataset page | C3, `DATA-TIERS-1` phase 3 | console UC-5 and UC-8 pass on Basic and Standard |
| C6 | B5 lookups; B6 remote systems with test and browse | C3, `DATA-TIERS-1` phase 4 | console UC-4 and UC-6 pass; Basic shows the locks |

C1 and C2 are server work that `LA-ONBOARD-1` phase 4 also benefits from; they can start as soon as its phase 2
contract is fixed.

**Cross-plan dependencies to agree with `LA-ONBOARD-1`** (another session's plan; this plan does not edit it): its
phase 4 "evidence, low confidence flagged" reads C1's format evidence and the OF-2 confidence rule, and OF-1 asks
its phase 4 to build the Add data screen as one shared component. Until that plan records both, they are this
plan's proposals.

## 7. Open decisions

| # | Decision | Recommendation |
|---|---|---|
| OF-1 | One Add data component for the console and Inspecto LA, or one each | **One**, in the code both apps already share (the console imports from `projects/link-analysis` today), built in `LA-ONBOARD-1` phase 4; the console passes "what the columns hold" as optional |
| OF-2 | The confidence rule for pre-applied choices | High when every sampled value agrees and the sample has at least 50 non-blank values; low otherwise; low is highlighted and confirm-gated. Proposed to `LA-ONBOARD-1` so both UIs use one rule |
| OF-3 | Does Add data replace the Catalog's *Onboard Stream / Reference* dialog | No: Add data is the primary start; the dialog stays as the advanced start next to *Open in editor*, for authors who want every stage from the outset |

**Settled elsewhere, not reopened here:** one import route (`LA-ONBOARD-1` OB-2); the Dataset is registered by a
commit that leaves nothing behind when it fails (OB-2); Excel (DT-8); measures (A-10, OB-13); the walkthrough fixes
(`LA-ONBOARD-FIXES-1`).

## 8. Not confirmed

- Whether the guided editor can open an `origin: import` Pipeline without changes.
- Whether the Catalog's empty Datasets pane is a separate component that can take a drop zone.

## 9. Board

P2 `ONBOARDING-FIRST-RUN-1` in `BACKLOG.md` §3.4 (hold: operator), indexed in §1 *Decisions owed on individual
rows*.
