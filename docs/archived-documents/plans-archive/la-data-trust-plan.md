# Link Analysis data trust — what data am I looking at? (`LA-DATA-TRUST-1`)

**Status: ARCHIVED 2026-10-14 - shipped.** S1-S9 built and S10 driven live twice; as-built facts live in `okf/frontend/features/link-analysis.md` section *Data trust*; the one open decision (b, `DataChanges.lastLook` counts undo) stays on `LA-DATA-TRUST-1` in `docs/BACKLOG.md`. When a slice ships, distil its as-built
facts into `okf/frontend/features/link-analysis.md` (and `okf/capabilities/ingestion/ingestion.md` for the
Collector side), move residuals to `docs/BACKLOG.md`, then archive this plan (CLAUDE.md, tier 2).

## 1. Intent

An analyst reading a Link Analysis graph must be able to answer, without leaving the app:
**how fresh** is the data, **how complete** is it, **which Files** it came from, and **does the graph reflect
them yet** (the index). Today the LA app shows none of this: no data-as-of, a per-Collector Coverage table
buried in an open Investigation (its `perDay` rows are returned and not rendered), and an index panel that
shows counts and a 20-path sample of the diff. File-grain status exists only in the console (Processing
Status, problem files only).

## 2. Scope (operator, 2026-10-14)

- **Demo data = Files only, from an inbox, pulled periodically.** Hive JDBC and the `db` Collector stay ON HOLD
  (`D-ING5`), SCD2 / as-of join stays deferred (`D-ING4`). Nothing here touches them.
- **Pipeline must work end to end** (it did in the 2026-10-13 live drive; `ce3d95bae` re-verified clean on
  2026-10-14). **Incremental indexing** (`la.index.build` APPEND) is already built; this plan makes it visible.
- **Status lives in the LA app only**, read-only: no re-process, no re-ingest, no console admin surface.
- **All eight ideas are in scope**, in the order of section 4, plus the freshness Alert the operator chose.
- Vocabulary (`docs/GLOSSARY.md`): the unit is a **File** (Run ⊇ Consignment ⊇ File); the rules are **Alert Rules**
  and **Expectations**; the acquisition entity is a **Collector** (the old synonym is banned).

## 3. What already exists (grounded 2026-10-14 by two read-only explorers; re-verify before building on it)

| Fact | Where it lives | Served? |
|---|---|---|
| name, rows, outcome, consignment per File | `_status_*.csv` | `GET /runs/{p}/files` (no event day, no by-name filter, no index state) |
| File → Dataset partition file, rows | `_lineage_*.csv` | `GET /runs/{p}/lineage?batchId=` (the only link) |
| event day, part, rows, bytes, commit time | `<markers>/day-manifest.tsv` (`DayManifest`, written by `DeliveryCheck.afterCommit`) | **not served** |
| data-as-of (max of the event-date column) | `GET /datasets/{id}/freshness` | yes; **no LA UI** (only the console Dataset editor) |
| per-day rows, per-Collector days | `GET /inv/investigations/{id}/coverage` | yes; needs an Investigation with a time column; `perDay` unrendered |
| REDELIVERED / RENAMED_CORRECTION / PART_COUNT_CHANGED / DUPLICATE_SKIPPED | Signal `collector.delivery.anomaly` | `GET /signals?type=` (no per-File query; an UNCHANGED duplicate is silent) |
| SEQUENCE_GAP | Event, feeds `builtin-sequence-gap` + `EventAlertBridge` | `/events` |
| index version, stale, deltas, plan (added/removed/changed + 20 samples) | `GET /inv/index` | yes |
| index build result (`mode`, `code`, edges, nodes, deltas) | Signal `la.index.build.completed`, audit `LINK_INDEX_BUILD_*` (carries `version`) | version only as free text in the Job run message |
| partition files each index version covers | `IndexManifest.InputFile` (capped at `MAX_INPUT_FILES`) | **only the count** is exposed |
| inbox waiting | `GET /runs/{p}/pending` | count + oldest age, no names |

Gaps this plan closes: no joined per-File read; no File → index-version link; nothing in the LA app.
Gaps it does NOT close (state them in the UI): an unchanged duplicate is skipped silently; a File still in the
inbox is a count only; "ingested at" is the commit time, not arrival time; completeness is judged against days
and File sequence, NOT an expected-Collector roster (roster declined, `LA-COLLECTOR-COVERAGE-1`).

## 4. Order of work (each slice = code + unit tests; verify at unit level, full reactor only at handoff)

| # | Slice | Idea | Why here | Done when |
|---|---|---|---|---|
| S1 | **Files read** — extend `CollectorCoveragePort` / `HostCollectorCoveragePort` (already joins batches ↔ lineage per Dataset) to return one row per File; route `GET /inv/datasets/{id}/files` (paged, name filter); row = name, event day, rows, ingested-at, outcome, index state (`INDEXED vN` / `WAITING` / `UNKNOWN` when the manifest cap is exceeded) matched via `IndexManifest` inputs; empty-day vs missing-day falls out of it | #8 (data), foundation | everything else reads it | real-HTTP test covers every gate; openapi entry; a 0-row File is distinct from no File |
| S2 | **Data strip + drawer** in the Investigation (freshness via `/datasets/{id}/freshness`, per-day strip from `perDay` with empty/missing marked, Files table, index version / stale / files waiting, the "not known" limits above) | strip, #8 (UI) | the surface every Alert points at | vitest specs; axe clean; no hardcoded colours; empty-state not an empty grid |
| S3 | **Freshness Alert Rule**, `maximumAge` defaulting to a multiple of the Collector poll interval; strip shows the same verdict | #4 + operator's pick | cheapest, highest demo value | rule fires on a stopped feed, clears on delivery |
| S4 | **Index-lag and build-failure Alert** (Files WAITING longer than a threshold; `la.index.build` refused / failed, e.g. `BUILD_IN_PROGRESS`, `OVER_BUDGET`) | #2 | the periodic pull makes lag real | both fire in a test; strip shows WAITING / FAILED |
| S5 | **Corrected-day warning** — an anomaly signal whose day is inside an open Investigation's window shows "day D was corrected after you last looked" with a refresh prompt | #1 | highest analyst value, needs S1/S2 | signal day ∩ window match, tested both sides of the window edge |
| S6 | **Quarantined / partial File Alert**, naming File and day; the same Files show in the table outcome column | #5 | data already exists in the console | Alert text carries no raw entity key (see telco masked-Alert lesson) |
| S7 | **Volume Expectation** — row-count band per day, odd day marked on the day strip | #3 | needs a baseline from S1's per-day rows | a half-delivered day is caught, a normal day is not |
| S8 | **`la-daily-feed` template ships the rules** from S3-S7 (grown incrementally; finalised here) | #6 | last of the rules | every `_templates`-scanning test green (seed gate, scan classes) |
| S9 | **Dossier footer** — data-as-of, index version, days missing at export, OUTSIDE the hashed root | #7 | riskiest: the Dossier is deterministic and replay-verified | **design check first** against how the Dossier is sealed; if it cannot be kept out of the hash, park and say so |
| S10 | **Live drive** on a fresh `la-app` bundle: drop Files on a schedule; each shows WAITING then `INDEXED vN`; a gap day, a corrected day, a quarantined File, a stopped feed each turn the strip and raise the right Alert | — | proves the whole chain | a handoff note with what was driven and what was not |

Optional, NOT in scope unless asked: a per-Dataset `/files` page reusing the S2 table.

## 5. Decisions taken (operator 2026-10-14 unless stated)

1. Hive / `db` Collector stay on hold; Files-only demo. 2. Status in the LA app only, read-only. 3. All eight
ideas in scope; order as section 4. 4. A stale-data **Alert** is wanted (reversing the agent's lean toward a
badge only). Defaults the agent took and will state at the first report: the time column is labelled
**Ingested at** (commit time); the inbox shows as a count and oldest age, not names; index state is matched from
manifest inputs and reads `UNKNOWN` past the cap instead of guessing from timestamps.

## 6. Gotchas carried in from the project memory (read before building)

- A new route clears FOUR gates (openapi entry, capability literal, authgate coverage with a BARE route literal,
  the route-gating report); `CapabilityManifestTest`'s scanner is literal-only. Tests with no Subject make
  `withCapability` a no-op: a gate test must carry the capability.
- Template creation enforces each carried kind's gate; a new template must pass EVERY `_templates`-scanning test.
- A new loader file name needs reserving; a Collector's masked / classified fields never reach Alert text.
- Work and push from a SIBLING worktree outside `.claude/worktrees/` (this lane: `C:\sandbox\la-data-trust`).
  Maven needs `JAVA_HOME=C:\sandbox\.graalvm-cache\jdk-27-win`; quoted `-Dtest=` with COMMAS; count `Tests run`.
  UI specs via `npx ng test`, never raw vitest; `npm ci --offline` in the worktree, never a junction.
- Run every `ci.yml` no-build guard before a push, and read `gh run list` after; commit locally, push rarely
  after a green batch (CI full build is nightly).
- Guard-inventory edits need the operator: prefer a code change that sidesteps the guard.
