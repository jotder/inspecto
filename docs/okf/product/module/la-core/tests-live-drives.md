---
type: Reference
title: Link Analysis - live drives and user sessions
description: The recorded live acceptance drives of Link Analysis (onboarding, data trust, analyst coverage, Investigation tab) with their set-up, steps, results and defects, and the think-aloud session script for real users. The generated test-class list is tests-inventory.
tags: [module, la-core, link-analysis, tests]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis - live drives and user sessions

Each section is one recorded drive: re-run it after a change to the screens it names. Unit and HTTP test classes:
[tests-inventory](tests-inventory.md).

- [Onboarding acceptance drive (LA-ONBOARD-1, 2026-10-22)](#onboarding-acceptance-drive-la-onboard-1-2026-10-22)
- [Data trust drive (LA-DATA-TRUST-1 S10, 2026-10-14)](#data-trust-drive-la-data-trust-1-s10-2026-10-14)
- [Analyst coverage test (UI-only, impersonated analyst, 2026-10-15)](#analyst-coverage-test-ui-only-impersonated-analyst-2026-10-15)
- [Investigation tab re-measure (LA-INVESTIGATE-UX-1, 2026-11-02)](#investigation-tab-re-measure-la-investigate-ux-1-2026-11-02)
- [Think-aloud session script (real user, template)](#think-aloud-session-script-real-user-template)

## Onboarding acceptance drive (LA-ONBOARD-1, 2026-10-22)

*Was `tests-live-drives.md#onboarding-acceptance-drive-la-onboard-1-2026-10-22`: Inspecto LA onboarding - acceptance live drive (`LA-ONBOARD-1` §1).*

*The acceptance of `LA-ONBOARD-1` (archived plan
[`la-onboarding-plan.md`](../../../../archived-documents/plans-archive/la-onboarding-plan.md) §1): a first-time user goes
from an empty install to a finding with the screens alone. Run 2026-10-22 on the phase 8 worktree (base `5f39c3328`),
results in §3 and §4. Re-run it after any change to the la-app first-run path; the beginner path it follows is the
[end user guide](user-guide.md) §2 *Getting started*.*

### 1. Set-up

| Item | Value |
|---|---|
| Bundle | Basic, `la-app` UI: `pwsh -File inspecto\package.ps1 -Ui la-app -NoRuntime -SkipBootCheck` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `pwsh -File inspecto\package.ps1 -Product la -NoRuntime -SkipBootCheck`)* (Java 27 on the path) |
| Spaces root | a NEW empty folder holding only a copy of the bundle's `spaces/_templates` (templates, no Space) |
| Server | `serve.bat` with `PORT=18388`, `SPACES_ROOT=<that folder>`; own port, own browser tab |
| Data | `transfers.csv` (2,215 transfers between 312 accounts: a mule ring `VICTIM-01 → MULE-1..6 → MULE-HUB → CASHOUT-A/B/C` and a busy `MERCHANT-BIG` with 700 transfers) and `accounts.csv` (312 accounts, `account_id, holder, kyc_level`); synthetic, kept outside the repository |
| Rule | only the screens: no call to the Inspecto API by hand. The files were handed to the page's own file chooser by a script (a person would pick them); a few buttons were pressed by a DOM click when the pane's scaled viewport made a pointer click unreliable - each such button is the screen's own |

### 2. Script (Basic, no sign-in)

1. Open the app. Expect **Getting started**, the Space guide at 0 of 4, and **What can I do next?** in the left menu.
2. **What can I do next?** → **Create a Space**: name *Mobile money*, kind *Mobile Money*, **Create**. Expect to land on
   **Onboard a file**.
3. *Events between entities*, choose `transfers.csv`. Check the columns: what was detected is applied with its evidence.
   **Create and run**. Expect the run summary in plain words.
4. **Add details about these entities**, choose `accounts.csv`; confirm any guess; **Create and run**.
5. Link Analysis → **Start from...**: Dataset *transfers*, **One entity**, Entity Type *Account*, type `MULE-H`, pick
   **MULE-HUB** from the type-ahead, **Start**. Expect MULE-HUB and its ring.
6. **Grow** (preview, then **Grow**). Expect the preview to say what it adds, then the graph grown.
7. Analysis → **Find something interesting** → **How does A reach B**: `VICTIM-01` → `CASHOUT-A`; and **Which groups stick
   together**.
8. **The whole Dataset** → **Who is busiest** → *MERCHANT-BIG ... Add it to an exclusion list?* → **Add to an exclusion
   list**: create *Busy merchants* (purpose *Never follow these (exclusion)*).
9. **How does A reach B** on the whole Dataset, **Start from these**, then **Save as a case** *Mule ring*.
10. Home: **My work** lists *Mule ring*; open it. Expect the ring drawn again.
11. Read **Settings → Getting started counters**. Scan every screen for Pipeline words.

### 3. Results (Basic, 2026-10-22)

| Step | Result | What the screen showed |
|---|---|---|
| 1 Open | PASS | *Getting started*: **Try it with sample data** / **Use my own data**, Space guide *0 of 4 done*. **What can I do next?** offered **Create a Space** (why: "Nothing exists yet: a Space is a place for one kind of data...") and **Try it with sample data**, with the Space guide under them. |
| 2 Space | PASS | The Create a Space dialog asked a name and *What kind of data?* (three kinds, each with example columns); **Create** landed on *Onboard a file* in the new Space. |
| 3 transfers | PASS | Format, separator, header and every column type applied with evidence; *from_account* / *to_account* → Account, From/To, kind *channel*, time *ts*, amount *amount*, main kind Account - nothing to confirm. Result: "2,215 rows were read from transfers.csv; 2,215 rows were kept. The link index was built: 2,215 links between 312 entities." Dataset card *one-time import* with **Add a file** / **Run now**; *Keep this up to date* locked with the Standard reason. |
| 4 accounts | PASS | *account_id* → Account, "Which column identifies each entity?" *account_id*; one guess (*The header line*) had to be confirmed first; "312 rows were read ... 312 rows were kept". |
| 5 Start | PASS | The type-ahead listed "MULE-HUB - 9 links, first seen 3 Sep 2026, last seen 30 Sep 2026"; **Start** drew "1 start and 9 linked wallets" (10 nodes, 9 links), MULE-HUB in the middle. |
| 6 Grow | PASS | Preview: "This step adds about 1 wallet and 6 transfers"; after **Grow** 11 nodes, 15 links, with **Undo**. A second Grow added nothing (the ring is closed) and said so. |
| 7 Ring | PASS | "VICTIM-01 reaches CASHOUT-A in 3 transfers: VICTIM-01 → MULE-1 → MULE-HUB → CASHOUT-A." Groups: "Group 1: 6 wallets ... (CASHOUT-A, CASHOUT-B, CASHOUT-C and 3 more)", "Group 2: 5 wallets ... (MULE-1, MULE-2, MULE-3 and 2 more)". |
| 8 Exclude | PASS after a fix | Whole Dataset: 312 nodes; "MERCHANT-BIG takes part in 270 transfers, the most on this graph" with the exclusion hint. **First run: FAIL** - the list picker offered nothing ("No choice matches") and no *Create a new list…*: the explorer graph had no Entity Type. Fixed in this phase (the start Dataset's primary Entity Type; the dialog now says why when no list can take them); re-run: *Create a new list…* preselected with *Never follow these (exclusion)*, "1 added to “Busy merchants”", MERCHANT-BIG hidden with Undo. |
| 9 Case | PASS | **Start from these** on the path: "Drew 4 starts and 7 linked wallets"; **Save as a case** → "Saved the case “Mule ring”." |
| 10 Come back | PASS | Home: Space guide gone (done), **My work** → *Mule ring*, "Saved 22 Oct 2026 · No Entity List yet" (the name, not the id: the phase 8 fix); *What changed*: "transfers: imported 22 Oct 2026, +2,215 rows", "accounts: ... +312 rows". Opening it drew "4 starts and 7 linked wallets" again. |
| 11 Counters | PASS | Settings → Getting started counters: first launch 22:32, **first Space after 1 min 1 s, first Dataset after 1 min 28 s, first graph after 2 min 40 s**, Space guide done after 9 min 31 s. Leaving Add data with a file read counted *Add data: checking the columns*. |
| Pipeline words | PASS after a fix | First scan: the Link Analysis footer said "... the lineage, component or pipeline planes ..." with no graph drawn - reworded. After it, no Pipeline / Collector / schema word on Getting started, Add data, Link Analysis, Entities (both tabs), Entity Lists, My work, What can I do next? or the Settings sections, except the role id `pipeline-developer` listed under Settings → Access (filed, `LA-ONBOARD-RESIDUALS-1`). |

**Time to first graph (from the counters):** 2 min 40 s from the first launch (the server start) to the first drawn
graph, against the 10-minute target. The drive is scripted, not a think-aloud: a first-time user's time stays to be
measured with real people (`LA-GROW-EXCLUDE-THINK-ALOUD-1` covers that kind of session).

### 4. The sign-in variant (2026-10-22)

**How it was run.** The demo sign-in bundle (`package.ps1 -Edition Advanced -DemoAuth -Ui la-app` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Product la -Distribution demo`)*) could not be packaged
offline: it needs the PostgreSQL driver 42.7.12 in the local Maven repository, which this sandbox lacks. The variant was
run instead from the Basic bundle's own jars plus `inspecto-ops.jar` (the Investigation surface) and
`inspecto-demo-auth.jar` (built with `mvn -o -pl providers/inspecto-demo-auth -am package`), launched with the
`serve-demo` flags (`-Dauth.mode=demo -Dcontrol.bind=127.0.0.1 ...`) and **`-Daddons.laInvestigations=false`**, port 18390.
`/bootstrap` answered `edition: standard`, `laLevel: explorer`, `featureReasons.investigations` = "Needs the Investigations
add-on, which this installation does not hold." Demo sign-in reads its users from a Space, so the spaces folder held one
Space, *mobile-money*, with only `demo-users.toon` and `roles.toon` copied from the `la-showcase` template (as an
administrator would set up).

| Check | Result | What the screen showed |
|---|---|---|
| Add data locked (no right) | PASS | Demo FM Analyst: *Onboard a file* is the reason "Adding data needs the right to upload data in this Space - ask an administrator"; **What can I do next?** shows **Add data** locked with the same line. |
| Add data locked until turned on | PASS after a fix | Demo Admin: "Add data is turned off in this Space: its data comes from Pipelines - ask an administrator to turn it on" - but there was no screen for the administrator to turn it on (only `PUT /settings/data`). Built in this phase: **Settings → This Space → Add data** (*People may add data in this Space*, read-only for others) and, on the locked screen, "You may turn it on: Settings, Add data" for the person who may. Turned on → `transfers.csv` imported (2,215 rows, index built). |
| Masking on | PASS | Demo Admin, Start from: "This Entity Type is masked: type the whole value - nothing is suggested"; MULE-HUB typed whole → "Drew 1 start and 9 linked nodes"; the query panel: "Masking: typed (ids shown as aliases)". |
| Investigation actions locked | PASS | Demo FM Analyst, toolbox **Open the Investigation** → **Start an Investigation** locked: "Needs the Investigations add-on, which this installation does not hold." |
| Analyst sees the imported data | FAIL (filed) | The Dataset the administrator added is `owner: admin`, `shares[0]`: private to its owner. The FM Analyst's Start from... said "No Dataset in this Space has a link yet" and Inspecto LA has no screen to share it (`LA-ONBOARD-RESIDUALS-1`). Fixed 2026-10-23: an import is shared with its Space (`architecture-data-onboarding.md` §6.12). |
| Sign out, sign in as someone else | FAIL (filed) | After signing out and in as another Demo User in the same tab, Link Analysis showed the previous person's page state (the page is kept alive across navigation) until a reload (`LA-ONBOARD-DEFECTS-1`). Fixed 2026-10-23 (`architecture-data-onboarding.md` §6.12). |

## Data trust drive (LA-DATA-TRUST-1 S10, 2026-10-14)

*Was `tests-live-drives.md#data-trust-drive-la-data-trust-1-s10-2026-10-14`: Link Analysis data trust: live drive (S10 of `LA-DATA-TRUST-1`).*

Driven 2026-10-14 on a fresh Enterprise `la-app` demo bundle (`package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Product la -Distribution demo -NoBakeDemo`)*, built from master `388651a19`), served on port 8147, with a Space `la-feed` created from the `la-daily-feed` template through `POST /spaces`. Personas copied from the `la-showcase` template (`roles.toon`, `demo-users.toon`, `link-analysis.toon`) because `la-daily-feed` ships none. Synthetic days came from a scratchpad script (nothing committed). The built-in browser drove the SPA for steps 3 and 4; the rest was driven over the control API as the Demo Users.

Plan: [la-data-trust-plan.md](../../../../archived-documents/plans-archive/la-data-trust-plan.md). As-built: [link-analysis.md](user-guide.md), section *Data trust*.

### Setup facts that matter for a demo

- **Poll interval is 60 s** (the Collector's default), so the freshness limit (`auto` = 2x poll) is 120 s with no change made. Files appear in the Files table 0-60 s after they are dropped.
- `la-daily-feed` ships **no `la.index.build` Job and no Job that evaluates Expectations**. The index Job was created by hand (`la_index_build`, `on_signal: pipeline.commit`, `allow_full: true`, `owner: admin`; first File gave a full build, every later one an append, 0.5-0.8 s each).
- The system Job `system.freshness-sweep` (every minute) is armed by the freshness rules and evaluates them without a hand trigger. `file_problem` and `la_index_build` Alerts are raised from Signals by `EventAlertBridge`, also without a trigger.
- Test-only changes, all restored: the index-lag rule limit was set to `2m` (shipped `2h`) and put back to `2h`; a second index Job `la_index_build_alt` (different mapping, `allow_full: false`) was created to provoke a refused build and is disabled.

### Step table

| # | Step | Result | What was seen |
|---|---|---|---|
| 1 | Ingest, periodic pull | PASS | Both sample days were pulled by the schedule (no hand trigger), 3 rows each. Days 0903-0917 added one at a time, each ingested within a poll |
| 2 | Index append | PASS | First File after the Job existed: `built (full)` v1; every later File `built (append)` v2, v3 ... v12; 0.5-0.8 s |
| 3 | Investigation, strip, drawer | PASS after fix | Strip read `OK, Data as of 5 Sep 2026, days not assessed, index v2`. Drawer showed Fresh, Complete, Files (name, event day, rows, error rows), Index. Defects D2 and D3 below, and a cramped table (R4) |
| 4 | WAITING then INDEXED | PASS | With the Job disabled, 0906 read `WAITING` and the strip turned WARNING (`index v3 (stale), 1 file waiting`); the next File's build covered both (`INDEXED 4`) |
| 5 | Missing day, zero-row File | PASS with notes | 0907 skipped: `sequence_gap` Alert, coverage over a bounded window reports `missingDays: [2026-09-07]`. A header-only File is QUARANTINED (`empty`) with no day, listed in the Files table and Alerted, so it never reads as a missing day (R5) |
| 6 | Corrected day | FAIL here, fixed on master by `f3a90e6a3` | Same name, changed rows: `DUPLICATE_SKIPPED` Signal, data NOT replaced, no banner. New name `XDR_20260904_v2.csv`: ingested, day 0904 doubled (310+320), NO Signal, no banner Banner and Refresh never triggered in a live Space |
| 7 | Quarantined / partial File | PASS | One Alert per File and day, naming the File, no entity value: `File quarantined: XDR_20260910.csv (day 20260910)`, `File loaded with rejected rows: XDR_20260913.csv`. Files table outcome QUARANTINED / PARTIAL (15 error rows). 20 garbage rows were NOT rejected (R6) |
| 8 | Volume band | PASS with notes | 0912 with 60 rows (20%) flagged `below`. Needs `POST /expectations/evaluate`; nothing evaluates it on a clock (R2). The 3-row sample days make 0904 and 0905 false positives (R7) |
| 9 | Stopped feed, index lag, refused build | PASS after fix | Freshness fired (WARNING, RESOLVED when the next File landed). Index-lag fired with the limit at 2m and resolved on the covering build. Refused build: `la_index_build` Alert `FULL_NOT_ALLOWED`. All three were dead before fix D1 |
| 10 | Dossier footer | PASS | `dataFooter`: `sealed: false`, as-of, index version 12, files waiting 0, `notAssessed: [days]`. Two exports 2 s apart had the same root hash; `dossier/verify` returned `verified: true`; the bundle downloaded (200) |
| 11 | Alert Rules evaluated without a hand trigger | PASS for Alert Rules, FAIL for Expectations | Freshness sweep and Signal bridge fire unattended. The volume Expectation does not (R2) |
| 12 | Accessibility and masking | PARTIAL | Strip and drawer vitest specs include an axe run (green). The strip and drawer hold no entity key by construction; no raw MSISDN appeared in the Files route as `fm.analyst` and `ra.analyst` read it (HTTP 200). A live axe pass and a masked-column persona were NOT DRIVEN |

### Defects found and fixed

A second session drove the same plan the same day and landed `f3a90e6a3` on master first; this drive's duplicate engine and as-of fixes were dropped in favour of it. Only D2 is from this drive.

| ID | Defect | Fix |
|---|---|---|
| D1 | **Freshness and index-lag Alert Rules never worked on a Collector-fed Dataset.** Both clocks read only `dataset.write`, which only a Job emits; verdicts stayed `unknown` and a stopped feed never fired | Fixed by `f3a90e6a3` (`PipelineCommitProbe`). Verified here on an equivalent fix: stale after a restart (age 5852 s), Alert fired from the sweep, RESOLVED when a File landed; index-lag fired at a 2m limit and resolved on the covering build |
| D2 | Strip said "days not assessed" for an Investigation with no window (the coverage read refuses an unbounded window) | This change: retry over the span of the Files, and the drawer says so. Specs added |
| D3 | "Data as of" read one day late | Fixed by `f3a90e6a3` |
| D5 (below) | A renamed correction doubles its day silently | Fixed by `f3a90e6a3` (not re-driven here) |
| R10 | `outputFiles` exposed absolute server paths | Fixed by `f3a90e6a3` |

### Defects found and NOT fixed on the first pass, fixed afterwards

| ID | Pri | Fix |
|---|---|---|
| R2 (`LA-EXPECTATION-CLOCK-1`) | P1 | The `expectation.evaluate` Job type already existed on master (the drive's bundle predated it); `la-daily-feed` now ships `la_rows_band_evaluate` (on every commit of `daily_xdr`) |
| R1, R3 (`LA-DAILY-FEED-JOBS-1`) | P2 | The template ships `la_index_build`; the Dossier footer judges days over the span of the Files when the Investigation has no window (`windowBasis: files`). No persona roster in the template: personas belong to the Demo Auth build |
| R6 (`LA-PARTITION-NULL-ROWS-1`) | P2 | Rows in the NULL partition count as error rows and the File reads PARTIAL in the Files route. They are still loaded (a deliberate Pipeline behaviour), only no longer reported as a clean SUCCESS |

Still open, small: R4, R5, R7, R8, R9 (P3, overlap `LA-DATA-TRUST-POLISH-1`).

T1 (trap): an incremental Maven package left `CollectorService` calling the old `indexLagProbe(Function)`: boot died with `NoSuchMethodError`. Delete the stale class (or `clean`) after changing a signature across modules.

### Not driven

The corrected-day banner and its Refresh in a live Space (no signal of a kind it shows could be produced, see D5); the fixed strip re-checked in the browser (pane hidden, timers throttled; covered by specs and the API); a live axe pass; a masked persona; the `-DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `-Distribution demo`)* bundle bake (used `-NoBakeDemo`).

## Analyst coverage test (UI-only, impersonated analyst, 2026-10-15)

*Was `tests-live-drives.md#analyst-coverage-test-ui-only-impersonated-analyst-2026-10-15`: Link Analysis analyst coverage test (UI-only, impersonated analyst).*

*Status: started 2026-10-15. Bundle: `inspecto-la-app` launch config (Enterprise demo auth, Space `la-showcase`, seeded with
`tools/seed-la-showcase.mjs`). Every step is done through the browser UI as a Demo User; no direct API calls except to
read back evidence. Results are in section 3; a use case marked NOT DRIVEN was not driven.*

### 1. Use cases

| Id | Persona | Goal | Surfaces covered |
|---|---|---|---|
| UC1 | `fm.analyst` | Find a mule ring from a saved view | Start cards, Open saved view, Suspicion score, Pattern match (Structuring), Explain node |
| UC2 | `fm.analyst` | Build a graph by hand from a Dataset | Query panel, column mapping, Filters, size limits, footer counts, legend chips |
| UC3 | `fm.analyst` | Quantify behaviour | Value Measures: Pass-through, Velocity, Cash-out concentration |
| UC4 | `fm.analyst` | Run an Investigation end to end | Create, seed, expand, exclude, annotate, window, Identity resolution, snapshot, replay and drift |
| UC5 | `fm.analyst` + `fm.manager` | Four-eyes expansion | A fan-out above the limit is held; the second approver releases it |
| UC6 | `fm.analyst` | Hand over evidence | Save this analysis, Case from nodes, Dossier, export |
| UC7 | `fm.analyst` | Investigate a number | MSISDN card, Expand next degree, Telecom profile |
| UC8 | `fm.analyst` | Know what the graph stands on | Data trust strip: freshness, coverage, Files, index state |
| UC9 | `ra.analyst` / `demo.manager` | Read-only user is refused honestly | Disabled or hidden controls, a clear refusal, no write |
| UC10 | `admin` | Operate | Settings, Entity Lists, Geo area, Share (embed link), Reveal |
| UC11 | any | Usability and accessibility sweep | Keyboard path, empty and error states, dark and light, narrow window |

### 2. Method

Sign in as a Demo User on the sign-in page, drive only visible controls, record per use case: PASS / DEFECT / USABILITY with
the on-screen evidence. Each finding gets an id `F-n`, a severity (P1 wrong or lost data, P2 blocks a task, P3 friction)
and, when fixed, the commit.

### 3. Results

Run 2026-10-15 on a bundle rebuilt from the working tree (`package.ps1 -Edition Advanced -DemoAuth -Ui la-app` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Product la -Distribution demo`)*, 11m51s) after the first
bundle turned out to be from 2026-10-11 and lacked the Link expansions screen: **check a bundle's jar and `ui\index.html` dates before
testing from it.** Driven through the browser as Demo Users; the pane was 875-1430 px wide during the run.

| Id | Use case | Result |
|---|---|---|
| UC1 | Mule ring from the saved view | PARTIAL: 112 nodes / 224 links as in the manual; Suspicion score top-20. Layering chain refused for want of a time column (F-6). *Explain node*, cash-out not driven. |
| UC2 | Build a graph by hand | PASS: Dataset picker lists link-like Datasets first with a reason; picking `mule_ring_legs_dataset` filled FROM_ACCOUNT / TO_ACCOUNT; Run query drew 20 nodes / 20 links with the Masking badge. Filters not driven. |
| UC3 | Value Measures | PARTIAL: form opens; prefill defect found and fixed (F-8). A Measure was not run. |
| UC4 | Investigation end to end | PARTIAL: opened `mule-hub-case` (9 entities, 7 links, 1 excluded), Expand next degree. Seed, exclude, window, identity, snapshot, replay not driven. |
| UC5 | Four-eyes | PASS: analyst's expand held ("waiting for approval", request p1); manager approved it in the Oversight card; ran as step 9; Working Set 9 -> 15 entities. |
| UC6 | Evidence hand-over | PASS: *Save this analysis* froze a 112-node snapshot and showed its SHA-256; the Dossier of `mule-hub-case` reads "Log intact, all 9 recorded hashes agree", lists nine steps including the four-eyes approval, and masks an entity id inside a free-text reason. *Case from nodes* and the sealed bundle download not driven. Found F-15. |
| UC7 | Investigate a number | PASS: MSISDN card switched the profile to Telecom, asked the purpose, created the Investigation and expanded one degree: 403 entities, 403 links (30 days to 29 Sep 2026). Found F-14. |
| UC8 | Data trust | PARTIAL: strip reads "Data as of 4 Sep 2026 . 3 of 3 days . index v2". The seed reported "4 covered, missing 2026-09-05"; not reconciled. |
| UC9 | Read-only reviewer (`ra.analyst`) | PASS: opens and reads; 31 controls disabled, each with the missing capability named. |
| UC10 | Admin | PASS (read, plus Reveal): Reveal turned the hub's alias into its real id in one click, marked "reveal audited" (no reason is asked: a policy question, F-16). The embed Share card says embed links are off on this host; the mint itself was proven in the earlier LA-EMBED-VIEW-1 pass and not re-run.  Settings shows each limit with its in-force value; Entity Lists shows both lists and a verified Identity Fact chain (7 facts). Opening another user's Investigation link as admin gives a clear red "not available" message. Minting an embed link and Reveal not driven. |
| UC11 | Usability and axe | axe-core 0 violations (dark) on Link Analysis with a graph, the open Investigation, Entity Lists, Settings, Geo and Alerts. One run on the Investigation page right after a Reveal reported 3 (colour contrast on text read as black, an ag-grid header and a grid scroll region); two re-runs were clean, so it is recorded as not reproducible. Light mode, the Save dialog and the Value Measures form were not scanned. |

#### Findings

| Id | Sev | Finding | Status |
|---|---|---|---|
| F-1 | P2 | After a graph draws, the Query panel collapses to its summary and the **Masking badge disappears**; `masked:<hash>` ids then have no on-screen reason. | FIXED (query-panel html + spec); confirmed live on the rebuilt bundle. |
| F-6 | P2 | A tool's refusal ("This pattern requires the hops to be in time order...") renders in the Analysis alert at the TOP of the Toolbox, far above the button at the bottom of a long panel, and was gone on the next read: the click looks like it did nothing. The suggested *Layering chain* also cannot run on the starter-card graph, which carries no time column. | Alert FIXED (scrolls into view; spec). The starter card still has no time column by design of its mapping; the messages now say where to add one: *Attribute columns* in the Query panel, then *Time column* under *Filter edges by time* (they used to point at a Query-panel field that does not exist). |
| F-7 | P3 | Oversight (Approve / Deny) sits at the very bottom of the Investigation panel, far from *Expand next degree* where "waiting for approval" is shown. | OPEN |
| F-8 | P2 | **Value Measures opened with every field empty** although the Investigation knows its Dataset, payer, payee, link kind and time column: the toolbox never passed the Investigation header to the component, which already supports seeding. | FIXED (toolbox `investigationHeader` input; confirmed live: Dataset, PAYER_ACCOUNT, PAYEE_ACCOUNT, CHANNEL, BOOKED_AT prefilled). A spec of the toolbox wiring itself was not added (the child needs heavy providers). |
| F-9 | P3 | The Oversight card showed a raw nanosecond instant (`2026-10-15T01:36:58.322167800Z`) and "budget above —" for an unset limit. | FIXED (local time; names only the limits that are set; spec). |
| F-10 | P3 | Expand status line repeated: "Expand one hop from every entity: 6 new entities, 7 links added." appears twice under the button. | FIXED 2026-11-01: not a double render. They were two sealed expand steps with the same text: the seeded step 2 (6 entities, 7 links, read back from the stored Working Set) and the later "every entity" expand. A one-hop line now names its step, e.g. "Expand one hop from every entity (step 2): ..." (`degreeOutcomeMessage`; spec fails without it). |
| F-11 | P3 | On the Investigation canvas the `masked:<16 hex>` labels are drawn large and overlap each other and the links. Shortening the label while keeping the full alias in a tooltip would help; not done, as aliases are also used to pick and search. | FIXED 2026-11-01: the canvas draws `masked:6065…` (node `canvasLabel`). The node `label` stays the full alias, so the hover text, the Selected line, search and ops are unchanged; nothing masking hides is shown (`workingSetToGraph`; spec fails without it). |
| F-12 | P3 | Investigation list shows the title rewritten with an alias ("Mule hub masked:6065..."): a title containing an entity id is masked. Intended: an id in Investigation text is masked like the id. The showcase seeds no longer put an entity id in an Investigation's name, reasons or notes (LA-FULL-CYCLE-TEST-1 finding 13, `tools/seed-la-investigation-text.test.mjs`) | CLOSED 2026-11-16 |
| F-2 | P3 | *Risk Score model* field under Suspicion score is an empty box with no placeholder. | OPEN |
| F-3 | P3 | Stacked layout (< 960 px): a Toolbox rail icon opens its panel below the fold without scrolling it into view. | OPEN, seen once |
| F-4 | P3 | Sign-in (studio shell, 800 px): Demo User name overlaps its title line. | OPEN, screenshot only; not re-seen in the la-app bundle |
| F-5 | P3 | In the studio shell the analyst lands on a Business Lens pointing at `kpi-reports`. | OPEN, observation (the la-app bundle is the product shape) |
| F-14 | P2 | The Investigation tab keeps the previous tab's scroll position (the three tabs share one scroll container), so the *Investigate a number* card switches to it with the required **Purpose** field out of view; seen three times. | FIXED (resets to the top on a tab change; spec fails with the fix removed). Confirmed live only by the unit spec: the fix was built after the last live pass. |
| F-15 | P2 | *Save this analysis* is enabled for a user without *Incident management*; only after typing a title does it fail, saying "The analysis could not be saved" and hiding the server's reason (`missing capability 'canManageIncidents'`). *Attach to Case* next to it is disabled with its reason. | FIXED for the message (the dialog now shows the server's reason; spec). The button is now disabled, with the reason, for a user without Incident management (spec). |
| F-16 | P3 | Reveal is one click with no reason asked; each reveal is audited. Whether a reason should be required is a policy call. | OPEN, question |
| F-13 | P3 | The *Open by id* row of the Investigation tab shows "—" with a pencil and a disabled Open button before anything is typed. | OPEN, observation |

Not a defect, recorded so nobody repeats it: `/approvals` in the la-app bundle redirects to `/`; four-eyes decisions are made in the Oversight card of the Investigation.

| F-17 | P2 | **Case from graph nodes is unavailable on a masked Space**: masked pseudonyms are refused by the server as Case keys, so with `typed` masking the ring view offers no member, and the dialog blamed "a known source Dataset". The manual (section 10) tells the analyst to tick the hub and relays. | MESSAGE FIXED (names masking, points to Reveal rights or linking an Investigation to a Case; spec). The manual sentence needs a masking caveat: OPEN. |
| F-18 | P1 (a11y) | Four toolbar menus (*Search nodes*, *Filter edges by time*, *Save the current view*, *Display options*) put a form in Material's `role="menu"` panel, and *Filter node types* put checkboxes there: axe `aria-required-children`, critical, whenever one is open. Earlier audits did not open them. | FIXED: node types are `menuitemcheckbox` items; the four form menus are re-roled to a named `dialog` by `FormMenuDirective` (spec). Measured live: all 11 toolbar menus clean. |
| F-19 | P3 | *Verify this manifest* is disabled with no stated reason until *Build dossier* has produced a manifest. | FIXED 2026-11-01: the button (now *Verify this Dossier*) gives "Build the Dossier first." as its tooltip and as a line under it, like other disabled Link Analysis controls (`DISABLED_REASON.buildDossier`; spec fails without it). |

**Driven in this round:** Case from graph nodes (F-17); Dossier *Build dossier* then *Verify this manifest* ("The manifest matches the store now", root `sha256:d892...`); the toolbar menus under axe. **Not downloaded:** *Download sealed bundle* writes a file to the machine and was left for the operator. **Still not driven:** the Filter predicate drawer (not found on the la-app list view), light mode (a theme switch is not available from the page here, so a class toggle gave invalid contrast figures, discarded), minting an embed link on a host with embeds on.

## Investigation tab re-measure (LA-INVESTIGATE-UX-1, 2026-11-02)

*Was `tests-live-drives.md#investigation-tab-re-measure-la-investigate-ux-1-2026-11-02`: Investigation tab - live re-measure of the undriven workflows (2026-11-02).*

*The click / depth targets of the Investigation tab redesign (`LA-INVESTIGATE-UX-1`, archived plan
[`2026-10-31-la-investigation-tab-redesign-plan.md`](../../../../archived-documents/plans-archive/2026-10-31-la-investigation-tab-redesign-plan.md)
§2) for the workflows its first live drive (§7.1, 2026-11-01) did not reach. Clicks are pointer presses (typing a value
counts as one); depth is the number of popups or dialogs stacked at the deepest point. Re-run this after any change to
the Investigation tab's start form, Expand filter, Clean up or canvas toolbar.*

### 1. Set-up

| Item | Value |
|---|---|
| Bundle | `package.ps1 -Edition Preview -DemoAuth -Ui la-app -NoBakeDemo` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Preview -Product la -Distribution demo -NoBakeDemo`)*, built from `origin/master` `dc1300f1b` in a sibling worktree |
| Server | the staged `inspecto-demo\bin\serve-demo.bat` (root `serve-demo.bat` before BUNDLE-LAYOUT-1) on `127.0.0.1:8092`, started in a terminal and stopped with Ctrl+C (graceful) |
| Space | a COPY of the demo's `la-showcase`; the shared demo at `inspecto-preview\inspecto-la` was not touched |
| User | Demo User `admin`, built-in browser pane (800 × 600 shown, 1024 px CSS width) |
| Investigation | ONE throwaway on `telecom_links_dataset` (time column `last_seen`), suspect MSISDN 99979100001, 10 recorded steps, deleted with *⋯ ▸ Delete…* (its log then answers 404; the seeded *mule-hub-case* untouched). A first attempt on `mule_transfers_dataset` (2 steps) was deleted through the API once it showed that Dataset has one Entity Type and no usable time column |
| Evidence | every result read from `GET …/inv/investigations/{id}/log` and the counts line, not only the screen |

No shipped la-showcase Dataset binds two Entity Types: `telecom_links_dataset` is MSISDN → MSISDN and
`mule_transfers_dataset` is Account → Account. P3 could not be driven there (see §2).

### 2. Results

| # | Workflow | Target: clicks · depth | Measured | Result | Evidence |
|---|---|---|---|---|---|
| W1 | Seed from one entity, Dataset not yet picked | 4 · 1 | **5 · 1** (tab, Dataset popup, pick, type the value, *Start*) | FAIL by one (= baseline) | `seed` + `expand` steps 1–2. A Time column of type DATE was offered and broke Start: `LA-INVESTIGATE-TIMECOL-DATE-1` |
| W1 | *Investigate this* on a node of an explored graph | 2 · 1 | 3 · 1 (right-click, *Investigate this*, *Start*), then refused | **FAIL** | the form is filled with the masked label `masked:c2e9…`; *Start* says it cannot be investigated: `LA-INVESTIGATE-THIS-MASKED-1` |
| W3 | Expand with the *30 days* chip | +1 · 0 | +2 · 0 the first time (open the Time group, *30 days*), **+1 · 0** after (the group stays open, `la.fold.expand.time`) | PASS | step 3 `expand`, window *30 days up to the latest event*; the group summary reads *Last 30 days* |
| W4 | Cap fan-out at 50 per entity | 2 + typing · 0 | 3 + typing · 0 the first time (open *Fan-out and busy*, pen, 50, *Expand*), 2 + typing after | PASS | step 4 `expand` *at most 50 links per entity, strongest first*; 895 → 1,038 entities |
| P3 | Clean up ▸ hide an Entity Type | 2 · 0 | — | NOT DRIVEN | no la-showcase Dataset binds two Entity Types (above) |
| P4 | Clean up ▸ hide everything outside a time window | 2 · 0 | **3 + typing · 0** (From pen, type, *Find what is outside the window*, *Hide 79 with no link event in the window*) | PASS in function, FAIL on count | steps 5–6: `hide` 79 + `annotate` *Clean up: no link event in [2026-09-20T00:00:00Z, the end)*; counts *79 hidden*. No window chip; the fields' example instants are placeholders: `LA-INVESTIGATE-CLICKS-2` (1) |
| P5 | Two nodes, *Keep paths between these 2* | 3 · 0 | **3 · 0** (click A, Shift+click B, *Keep paths between the 2 selected (hide 948)*) | PASS | steps 7–8: `hide` 948 + `annotate` *Clean up: kept the paths between …*; 11 entities left on screen |
| K1 | Lasso a subgraph, *Keep* on the selection bar | 2 · 0 | lasso not drivable here; Keep = **K** key (1 · 0) | PARTIAL | step 9 `keep` 2, counts *2 kept*. The pane's drag is a straight line (encloses nothing) and the canvas ignores synthetic pointer paths, so the lasso needs a person; at 1024 px the selection bar's *Keep* is folded away and not in ⋯: `LA-INVESTIGATE-CLICKS-2` (2) |
| W5 | Node menu ▸ *Expand, this once ▸ Incoming only* (re-check) | 2 · 1 | **3 · 2** (right-click, *Expand, this once*, *Incoming only*) | PASS (one press and one level over, as on 2026-11-01) | step 10 `expand` `direction in` |

**Seen, not filed:** the one-off *Incoming only* and the 30-day chip both carried into later expands the filter set
before them (the chip's hint says *This Expand only* while the chip stays selected; the window equalled the
Investigation's own here, so the step log cannot tell); the header says *this Investigation sets no time window* on an
Investigation whose time column is `last_seen`.

### 3. Re-drive after the fixes (2026-11-02)

`LA-INVESTIGATE-TIMECOL-DATE-1`, `-THIS-MASKED-1` and `-CLICKS-2` fixed (as built in
[`architecture-http-and-ui.md`](architecture-http-and-ui.md) §6.9). Same set-up as §1: `package.ps1 -Edition Preview -DemoAuth -Ui la-app -NoBakeDemo` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Preview -Product la -Distribution demo -NoBakeDemo`)*
built from the fix worktree, `serve-demo.bat` on `127.0.0.1:8092`, a COPY of `la-showcase`, Demo User `admin`, 1024 px.

| Defect | Workflow | Result | Evidence |
|---|---|---|---|
| TIMECOL-DATE | W1 start on `mule_transfers_dataset`, Time column popup | **PASS** | the popup lists *None* and **BOOKED_AT · TIMESTAMP** only (`BOOKED_DATE` and the text columns are gone; `BOOKED_AT`, not declared on the Dataset, is now offered). Start from MULE-HUB-01 with `BOOKED_AT`: `POST …/inv/expand/preview` 200, *Drew 1 entity and 14 linked subscribers*. The first build showed only *None*: `/db/table` sends the coarse `date` for both columns, so the stored type is now read with `typeof()` |
| THIS-MASKED | right-click a masked node ▸ *Investigate this* | **NOT DRIVEN live** | the pane's synthetic right-click did not reach a G6 node on `telecom_fraud_network` (Find centred the hub; no node menu opened). Covered by the canvas spec: a revealer gets the real key through `POST /inv/deep-links/resolve {reveal: true}`; anyone else gets no request, a blank value and the reason. A person should re-check it once |
| CLICKS-2 (1) | Clean up ▸ Outside a time window on a saved throwaway (time column `BOOKED_AT`) | **PASS** | the fields read *Blank: from the start* / *Blank: to the end*; *30 days* sets From = 30 days before the latest event (`2026-08-05T00:28:00.000Z`), To blank, the chip pressed, and reads at once: *Hide 0 with no link event in the window* (every one of the 15 entities had an event in the window, so nothing to hide; *7 days* the same). Chip + Hide = 2 presses where there is something to hide |
| CLICKS-2 (2) | 1024 px, ⋯ ▸ Select all, ⋯ ▸ *Keep the selected (K)* | **PASS** | ⋯ shown (canvas tools folded); the item is enabled with a selection; the counts read *15 kept* |
| CLICKS-2 (3) | W1 with the Dataset not yet picked | unchanged, 5 · 1 | the baseline; the Dataset pick is the extra press. Accepted, not changed |

The throwaway `inv-5a025dc5-…` was deleted with *Investigation actions ▸ Delete* (its folder is gone; `mule-hub-case` and
`delivery-gap-case` untouched); the server was stopped with Ctrl+C (graceful: *ControlApi stopped*) and the viewport reset.

### 4. W5: is flattening worth it?

Not on its own. The one-off sits one submenu deep because the node menu's top level is already ten items; lifting
*Incoming only* to the top would make the menu longer for every right-click to save one press on a rare action. The
common case already costs 1 · 0 (double-click with the tab's filter) or 2 · 1 (*Expand*), and an analyst who wants
incoming-only repeatedly sets the Direction chip once and keeps double-clicking. If it is ever done, the cheaper way is
a keyboard path (e.g. Shift+G for the opposite direction) rather than another top-level item.

## Think-aloud session script (real user, template)

*Was `tests-live-drives.md#think-aloud-session-script-real-user-template`: D6 think-aloud session script — a real Case-desk lead.*

**Row:** `D6-REAL-USER-SESSION-1` · **Date prepared:** 2026-10-12 · **Surface:** Case Manager, *Findings* form editor.

The archived design (`docs/archived-documents/plans-archive/findings-spec-authoring-ui-design.md`, sections 1 and 8)
defines the persona (a case-management lead, not a developer), tasks T1-T8, and the real criterion: **T2, T6, T7
and T5 done unaided, PASS or FAIL per task**. The proxy session (`docs/okf/product/module/ops/user-guide-objects.md`, *Proxy
acceptance session 2026-09-29*) found five defects; all five are closed. A real lead has to be found and booked
(operator decision D14); an agent cannot run this session.

### Pre-session checklist

Do these before the participant arrives (about 30 minutes):

1. A build of current `master` with the `demo` Space and a bundle-local `demo-users.toon` holding a lead who has
   `canManageIncidents` and a second user who has not (the proxy used `package.ps1 -Edition Advanced -DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Distribution demo`)*;
   use a clean port, 8080 may be held).
2. **Seed one Incident and one Case in the Space** (a `create-incident` Decision Rule over the API, as the proxy did) so
   task 5 does not dead-end on an empty Space. State openly in the notes that this is seeded.
3. Reset the `findings-spec` for the Space to the built-in (delete any custom component) so every participant starts
   from the same four fields.
4. A second person as note-taker; screen and audio recording with the participant's consent; no real customer data
   on screen (DATA-GOV-1).
5. **Check before the session: a Case type's own Findings form (`FINDINGS-PER-CASE-TYPE-1`, decided 2026-10-06).** The Case concept page
   (`okf/product/addon/incidents/plan-incidents.md`) describes an optional per-Case-type form (`case.<caseType>`, falling back to the generic
   spec). The proxy session of 2026-09-29 predates it. Confirm on the build you will use that a Case type with its own spec shows that
   form and a type without one shows the generic form, because a real lead will ask whether a change alters the form for all their
   case types. Log their reaction as a finding.

### Opening (read aloud, do not demonstrate)

"You are the lead of a fraud desk. Your team writes down what they find when they close a Case. We are testing the
product, not you. Please say out loud whatever you are thinking, including what you expect a button to do before you
click it. I will not help unless you ask to stop. There are no wrong answers."

Ask: role, how long on a desk, whether they have ever changed what the team records.

### Tasks

Verbatim wording to read. The facilitator never says field, dialog, key, or Findings spec.

| Task | Read aloud | Pass (all unaided) | Fail signals to log |
|---|---|---|---|
| T2 Add | "Your team never records how much money was recovered. Add a way to record that, as a number." | Opens the editor from Case Manager, adds a numeric field named by them, marks it required if asked "must it always be filled", sees it in the preview | Cannot find the editor; types over the wrong box; confuses name and label; gives up or asks for help |
| T6 Conditional | "Your team also wants a free-text note, but only when the Case was written off." | Adds a text field; sets it to show only for the right answer of the choice field; verifies in the preview that it appears and disappears | Cannot find "show only when"; reads the condition word wrongly; sets it on the wrong field |
| T7 Preview | "Before you save, show me what your team will see." | Uses the preview without being told it exists; chooses the answer that triggers the note | Saves first and checks on a real Case; does not notice the preview |
| T5 Remove | "Last year's field 'Root cause' is no longer wanted. Remove it. What do you think happens to Cases that already have an answer?" | Removes it; reads the warning (if any) and says correctly that old answers stay stored or are hidden, per the product | Removes it and expects old answers deleted or does not question it; cannot find the control; unclear whether it was saved |

Run order is T2, T6, T7, T5 (the design's order). After T7, have them save, then open a real Case and fill the form
once as the check that "what I designed is what my team sees". Optional T1 and T8 only if time remains. Then the
view-only user: "Sign in as your colleague, who is not a lead. What can they do here?"

### Facilitator rules

Silence for 20 seconds before any prompt; only "What are you thinking?" or "What did you expect to happen?"; log every
click and every verbal "I expected X"; time each task; stop a task at 5 minutes and mark it FAIL with the reason;
read the browser console after the session for errors (count click events before filing a dead-button defect: the
viewport-emulation trap).

### After the session

Ask: what was confusing, what would you change, would you use this. Record per task PASS / PASS-with-help / FAIL, the
verbatim quotes, and any new defect as a row in the same `objects.md` section (*Real-lead session YYYY-MM-DD*).
Compare against the proxy floor: any PASS the proxy gave that the lead failed is the headline. The row closes when
T2, T6, T7 and T5 are recorded; a FAIL becomes its own P2 row.
