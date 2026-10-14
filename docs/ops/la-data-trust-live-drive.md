# Link Analysis data trust: live drive (S10 of `LA-DATA-TRUST-1`)

Driven 2026-10-14 on a fresh Enterprise `la-app` demo bundle (`package.ps1 -Edition Enterprise -DemoAuth -Ui la-app -NoBakeDemo`, built from master `388651a19`), served on port 8147, with a Space `la-feed` created from the `la-daily-feed` template through `POST /spaces`. Personas copied from the `la-showcase` template (`roles.toon`, `demo-users.toon`, `link-analysis.toon`) because `la-daily-feed` ships none. Synthetic days came from a scratchpad script (nothing committed). The built-in browser drove the SPA for steps 3 and 4; the rest was driven over the control API as the Demo Users.

Plan: [la-data-trust-plan.md](../superpower/la-data-trust-plan.md). As-built: [link-analysis.md](../okf/frontend/features/link-analysis.md), section *Data trust*.

## Setup facts that matter for a demo

- **Poll interval is 60 s** (the Collector's default), so the freshness limit (`auto` = 2x poll) is 120 s with no change made. Files appear in the Files table 0-60 s after they are dropped.
- `la-daily-feed` ships **no `la.index.build` Job and no Job that evaluates Expectations**. The index Job was created by hand (`la_index_build`, `on_signal: pipeline.commit`, `allow_full: true`, `owner: admin`; first File gave a full build, every later one an append, 0.5-0.8 s each).
- The system Job `system.freshness-sweep` (every minute) is armed by the freshness rules and evaluates them without a hand trigger. `file_problem` and `la_index_build` Alerts are raised from Signals by `EventAlertBridge`, also without a trigger.
- Test-only changes, all restored: the index-lag rule limit was set to `2m` (shipped `2h`) and put back to `2h`; a second index Job `la_index_build_alt` (different mapping, `allow_full: false`) was created to provoke a refused build and is disabled.

## Step table

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

## Defects found and fixed

A second session drove the same plan the same day and landed `f3a90e6a3` on master first; this drive's duplicate engine and as-of fixes were dropped in favour of it. Only D2 is from this drive.

| ID | Defect | Fix |
|---|---|---|
| D1 | **Freshness and index-lag Alert Rules never worked on a Collector-fed Dataset.** Both clocks read only `dataset.write`, which only a Job emits; verdicts stayed `unknown` and a stopped feed never fired | Fixed by `f3a90e6a3` (`PipelineCommitProbe`). Verified here on an equivalent fix: stale after a restart (age 5852 s), Alert fired from the sweep, RESOLVED when a File landed; index-lag fired at a 2m limit and resolved on the covering build |
| D2 | Strip said "days not assessed" for an Investigation with no window (the coverage read refuses an unbounded window) | This change: retry over the span of the Files, and the drawer says so. Specs added |
| D3 | "Data as of" read one day late | Fixed by `f3a90e6a3` |
| D5 (below) | A renamed correction doubles its day silently | Fixed by `f3a90e6a3` (not re-driven here) |
| R10 | `outputFiles` exposed absolute server paths | Fixed by `f3a90e6a3` |

## Defects found and NOT fixed on the first pass, fixed afterwards

| ID | Pri | Fix |
|---|---|---|
| R2 (`LA-EXPECTATION-CLOCK-1`) | P1 | The `expectation.evaluate` Job type already existed on master (the drive's bundle predated it); `la-daily-feed` now ships `la_rows_band_evaluate` (on every commit of `daily_xdr`) |
| R1, R3 (`LA-DAILY-FEED-JOBS-1`) | P2 | The template ships `la_index_build`; the Dossier footer judges days over the span of the Files when the Investigation has no window (`windowBasis: files`). No persona roster in the template: personas belong to the Demo Auth build |
| R6 (`LA-PARTITION-NULL-ROWS-1`) | P2 | Rows in the NULL partition count as error rows and the File reads PARTIAL in the Files route. They are still loaded (a deliberate Pipeline behaviour), only no longer reported as a clean SUCCESS |

Still open, small: R4, R5, R7, R8, R9 (P3, overlap `LA-DATA-TRUST-POLISH-1`).

T1 (trap): an incremental Maven package left `CollectorService` calling the old `indexLagProbe(Function)`: boot died with `NoSuchMethodError`. Delete the stale class (or `clean`) after changing a signature across modules.

## Not driven

The corrected-day banner and its Refresh in a live Space (no signal of a kind it shows could be produced, see D5); the fixed strip re-checked in the browser (pane hidden, timers throttled; covered by specs and the API); a live axe pass; a masked persona; the `-DemoAuth` bundle bake (used `-NoBakeDemo`).
