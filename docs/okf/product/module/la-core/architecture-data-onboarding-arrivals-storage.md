---
type: Concept
title: Link Analysis — data onboarding — Arrivals, arrival feeds and Dataset storage
description: Part 3 of 3 of Link Analysis — data onboarding: Arrivals: the ledger, Data status and what a later file does (LA-ARRIVAL-LEDGER-1 + LA-ARRIVAL-MODES-1, as built 2026-11-03/05); Arrival feeds: the inbox and Keep this up to date (LA-ARRIVAL-FEEDS-1, as built 2026-11-05); Dataset storage: compaction, the merge map and retention (LA-DATASET-COMPACT-1, as built 2026-11-05); Watch this folder: the …
tags: [module, link-analysis, onboarding, add-data, arrivals, dataset, architecture]
timestamp: 2026-11-12T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-20 last-edit
code: la/inspecto-la-core
---

# Link Analysis — data onboarding — Arrivals, arrival feeds and Dataset storage

Part 3 of 3 of [Link Analysis — data onboarding](architecture-data-onboarding.md) · previous: [Column roles and discovery in Basic](architecture-data-onboarding-roles-discovery.md).

## 6.17 Arrivals: the ledger, Data status and what a later file does (LA-ARRIVAL-LEDGER-1 + LA-ARRIVAL-MODES-1, as built 2026-11-03/05)

Every edition; Add data Datasets only (a Pipeline-fed Dataset answers no arrivals).

- **`GET /datasets/{id}/arrivals`** (`inspecto/.../control/DatasetArrivals.java`) is a READ over the engine's per-file
  audit ledger (`StatusStore.files`) joined to a sidecar `<dataRoot>/<id>/arrivals.jsonl`. The covered period is the
  min / max of the Dataset's `eventDate` over the arrival's own Parquet, read in a DuckDB sealed to `<dataRoot>/<id>`.
  Gates as `/freshness` (no capability); an entry whose import fields do not name ITS OWN Add data Pipeline reads nothing.
- **The sidecar** gets one line per file a run took (`DatasetArrivals.recordRun`): the upload's name, mode, sha256; every
  file's index outcome and its mode (`append` / `full` / `compact`). *Run now* records the drops it took (`sender: system`).
  A system line is keyed by name AND batch; a line written before senders were recorded reads as an upload. A file
  with NO line (imported before the sidecar existed, 2026-11-03, or its best-effort write lost) is an upload when its
  inbox name is Add data's own `add_<stamp>_<name>` (`DatasetArrivals.ADD_DATA_NAME`), shown as `<name>`; else a
  system's. A refused
  waiting file has only its sidecar line (`status: REFUSED`, `reason`, `setAside`) and is merged in by time.
- **Data status** is one component, `<inspecto-la-data-status>` (`link-analysis/src/link-analysis/data-status.component.ts`),
  under the Start box's Dataset picker and above the Rows dock table: *Received · covers · graph up to date / N files
  behind*. A refused arrival is left out. Extension point: `dataStatusLines()` in `add-data.model.ts`.
- **The Dataset card** (LA-STREAM-FACTS-1, 2026-11-07) says the same facts under their own labels in a `<dl>`:
  `datasetFacts()` (Origin, Filter, Received, Covers, Graph, Inbox / Kept up to date, Storage, Compacted; each only when
  known), built from the same parts as the one line (`dataClocks`, `feedFact`, `storageFacts`), so the words cannot
  drift. Its **Arrivals** are the shared `inspecto-data-table` (`arrivalRow` / `arrivalColumns`: File, Arrived newest
  first, How, Result, Rows, Left out, Covers, Columns; ten a page); a row a later replace set aside is muted and its
  Result says so.
- **V4:** on Basic every append (Add a file, or a drop Run now takes) builds the link index in mode `append`; only the
  first build is `full`. About 7 to 11 s per 10,000-row append at 1 M rows, end to end
  (`ControlApiOnboardLinkAnalysisTest#v4MeasuredAtScale`).
- **Three modes** for *Add a file*: `replace` (everything), `append` (add rows), `period` (D-2: the file's rows are added,
  then every older Parquet file loses its rows on the calendar days the file covers; originals kept in
  `database.period-<stamp>/` until the import succeeded). `period` needs a When column. A rewritten file makes the next
  index build `full`. Since `BASIC-ELT-PROFILE-1` B4 the engine's `ArrivalReplacer` does it, crash-safe (a journal, one
  swap, a heal to the old or the new state), and once the import succeeded the replaced files (and the files *Replace
  everything* set aside) are `REPLACED` in the Consignment output registry, so their reprocess is refused (`409`):
  [spec-arrival-replace.md](../elt/spec-arrival-replace.md).
- **The same bytes again** (D-3): append or period whose sha256 matches an upload whose rows are still held answers 409
  with the first import's date (`DatasetArrivals.alreadyImported`). Replace everything may always reload. A folder drop
  under the same name is skipped by the import Pipeline's duplicate policy and stays in the inbox.
- **Schema drift** (D-6, operator 2026-11-05): `OnboardImport.checkColumns` judges a later file by column NAME before
  anything lands. A missing column, or a delimited column at another position, refuses it naming each (422 for an upload;
  Run now, and Add a file for the drops its run takes, move a refused waiting file to `<id>/refused/`). An import with no
  header line is judged by its column count only.
- **A value a whole-number column would cut** (`DECIMAL-TEXT-NOT-BIGINT-1`, P2 defect from the 2026-11-06 Basic live
  check, as built 2026-11-06): the engine's `TRY_CAST('12.25' AS BIGINT)` rounds to 12 with no rejection, so
  `checkColumns` also refuses a later file when a column the Dataset reads as `BIGINT` holds a value with a fractional
  part ANYWHERE in the file (`OnboardDraft.Col.fraction`, one scan of the whole file, not the 1,000-row sample), with
  the same 422 / set-aside as a missing column: `'amount' holds whole numbers and this file has '12.25'`. `60.00` is
  taken (nothing is cut). Refused rather than quarantined because the arrival rules judge a whole file before it lands
  and nothing in the engine's cast marks the row. A Dataset's column type cannot be widened yet: the reason says to add
  the data as a new Dataset with the column as DOUBLE. The draft no longer proposes BIGINT for such a column (see
  `api-onboarding-authoring.md`). Test: `ControlApiDecimalColumnTest`.
- **New columns are kept as details** (`LA-ARRIVAL-NEW-COLUMNS-1`, operator 2026-11-06, as built 2026-11-06;
  `inspecto/.../control/DatasetNewColumns.java`). A run keeps the new columns EVERY file it takes carries (delimited
  text: the same leading new columns in the same order, since the engine reads by position; JSON / Parquet: by field
  name). Each kept column is appended to the import's schema (`raw.fields`, the next position for delimited text; a
  `keep` mapping) with the type its values imply (`DatasetNewColumns.typed`, operator 2026-11-06, as built in
  `DECIMAL-TEXT-NOT-BIGINT-1`): the draft's inference in every file the run takes (`0.50` and `47.00` are DOUBLE, plain
  digits BIGINT, a date with its format, a time of day TIME); whole and decimal numbers across files widen to DOUBLE; any other
  disagreement, or a number read through a decimal mark (`12,22`, `1,234.50`: a plain cast reads it as nothing), stays
  VARCHAR (`SchemaDrift.settle`, B5). The schema is written through `DatasetFeed.rewriteSchema` (schema save gates,
  history; ELT `spec-pipeline-execution-run-steps.md`). The Pipeline is re-read
  (`CollectorService.refreshConfigs`, the registry's content hash sees the schema change), and the Dataset entry gains
  `{name, type (number / date / timestamp / boolean / string; TIME is date), role: detail, keptFrom {file, at}}`
  (a versioned write). All of it is undone if the run fails. The answer and the arrival carry `keptColumns`.
  **Left out**, as before (delimited files are rewritten with the columns kept, because the engine sets aside rows with
  more fields than it reads), named in `newColumns` with `newColumnsReason`, when: the person unticked *Keep new
  columns as details* (`POST /datasets/{id}/files?newColumns=leave`; Run now, the inbox poll, feeds and a snapshot
  refresh always keep); another file of the same run lacks it; its name is taken (a Dataset column or a schema field,
  case-insensitive) or cannot name a column; or the Space's approval policy holds `dataset` or `schema` changes (a
  column is then added only on its own). **Once kept, a later file without it is refused** like any missing column
  (operator 2026-11-06, not accepted blank), the reason saying `'fee' is missing (kept from 'wider.csv' on <date>)`.
  **One clock for person-facing times (`DATES-ONE-ZONE-1`, fixed 2026-11-06):** an arrival's `received` is the
  server's local wall clock without a zone (`yyyy-MM-dd HH:mm:ss`), so `<date>` above is `keptFrom.at` (an ISO UTC
  instant on the wire) turned to the server's local day (`DatasetNewColumns.localDay`), never its UTC date part; the duplicate-import 409 ("'x' was
  already imported on <date>", from the arrival sidecar line's instant) uses the same. The UI
  shows every server-recorded time through `ledgerTime()` (`add-data.model.ts`): a zoneless value as it is, a value
  WITH a zone (`compactedAt`, a carried compaction block's `received`) in local time. Event dates (`coversFrom/To`) are
  the data's own values and are never moved. Gap: a zoneless value is the server's clock and a zoned one is turned to
  the browser's, so they agree only when the browser and the server share a zone (always on loopback Basic).
  **Reads:** every read of the Dataset's Parquet already lines files up by name - the engine Dataset read
  (`DatasetRelation` via `SqlViews.readerOverLiteral`, `union_by_name=true`; Link Analysis, Discover, BI and the index
  build read through it), `/db/table` and `/db/query`, `countRows`, the arrivals' periods and *Replace the period*
  (`SqlViews.parquet(…, true, …)`), compaction (`UNION ALL BY NAME`); an older file reads the column as empty. The row
  filter (`where`) runs on the import, over each file before it lands, and every file it sees carries every column the
  Dataset reads, so a kept column never makes it fail. A typed kept column takes a range filter and can be a Measure;
  a later value that does not parse is stored empty and counted as a cast failure, as for any typed column, and a
  fraction into a kept BIGINT column is refused (above). Tests (typing: `ControlApiDecimalColumnTest`):
  `ControlApiArrivalNewColumnsTest` (older and newer files read together, the refusal, leave, the every-file rule, Run
  now keeping on its own, JSON, an approval policy, a row filter, compaction over mixed columns),
  `ControlApiOnboardLinkAnalysisTest#aKeptNewColumnRidesTheIndexBuiltOverOlderAndNewerFiles` (made a Category, the column
  rides a full index build over the mixed files).

**Deliberately not done.** Row-key upserts (with SCD2,
D-ING4); the inbox and *Keep this up to date* (`LA-ARRIVAL-FEEDS-1`); Dataset Parquet compaction (`LA-DATASET-COMPACT-1`);
a refresh of the Start box / dock status after a run (each reads once per Dataset); a live drive on Basic and Preview.
Plan: `docs/archived-documents/plans-archive/la-data-arrival-plan.md` §5a-§5d, §6.

## 6.18 Arrival feeds: the inbox and Keep this up to date (LA-ARRIVAL-FEEDS-1, as built 2026-11-05)

Plan [`la-data-arrival-plan.md`](../../../../archived-documents/plans-archive/la-data-arrival-plan.md) P3, decision D-1. Class
`inspecto/.../control/DatasetFeed.java`, registered from `OnboardRoutes`; gates as *Add a file* (`canUploadData`, 503 no
write root, 403 `data.self_import` off, then `OnboardImport.importDataset`: 404 unknown, 409 not an import Add data wrote
for THIS id).

| Route | What |
|---|---|
| `GET /datasets/{id}/feed` | `inbox` (absolute path on the server), `waiting` (count) and `files` (oldest first, at most 50, `truncated`), `automatic` (`pipeline.automation` present), `feed {kind: uploads \| folder \| sftp, every?, connection?, readyMarker?}` read from the Pipeline itself |
| `PUT /datasets/{id}/feed` | *Keep this up to date*: `{kind, every?, connection?, readyMarker?}`; 503 without `pipeline.automation` (Basic, D-1); 422 for a Reference, a bad kind, an interval outside 1m-1d, a marker that is not a file-name suffix, an sftp feed without an sftp Connection |
| `DELETE /datasets/{id}/feed` | back to uploads (`trigger: manual`, the collector Add data wrote); every edition |

- **The feed lives in the import Pipeline, nowhere else.** The PUT sets `imports/<id>_pipeline.toon`'s `trigger`
  (`{type: schedule, every}`, default `15m`) and, inside the existing `collector`, `discovery: poll`, `connector: local`
  or `sftp` + `connection`, `accepts_upload: true`, `stability {window: 30s, size_checks: 2, ready_marker?}` and
  `duplicate {mode: checksum, algorithm: SHA256, on_change: ignore}`; the DELETE removes those feed keys and sets
  `trigger: manual`. Every other collector key (set in the guided editor) is kept. The write goes the way every Pipeline
  write does: an approval policy over Pipelines refuses it (409, `PendingChanges.holdRefusing`, as Add data's own writes;
  `ConfigWriteFunnelTest`), `SaveGate` blocks only the findings the edit introduces, the import is claimed in
  `OnboardImport.IN_FLIGHT` against a run or a delete of the same Dataset (409), `RefusalConfigAudit.write` +
  `PipelineHistory.record`, then `refreshConfigs()`; a file that does not load is put back. The quarantine is the one
  Add data already wrote.
- **A watched folder IS the Dataset's inbox** (`<dataRoot>/inbox/<id>`): no caller-supplied path, so nothing to jail.
  An SFTP feed reads the Connection's `base_path`.
- **A person's *Add a file* is imported at once on a fed Dataset** (`ADD-FILE-FEED-1`, defect, fixed 2026-11-05). The
  upload lands in that same inbox, and the folder feed's stability gate held every file 30 s, so the person's own run
  took nothing and answered 422 "nothing was imported". `OnboardImport.addStaged` now opens
  `com.gamma.inspector.RunFiles.complete({landed file})` around its run: `CollectorProcessor.collect` adds the files a
  `complete` scope names back after `gateStability` (run path only; discovery order kept). The upload is whole by
  construction (`Job.landFromDraft` moves it into the inbox complete). Every other waiting file is still gated, so a sender's
  file that has just arrived waits for the feed; on Basic (no stability block) nothing changes - Add a file still takes
  the files waiting. No double import: the run moves the upload out of the inbox into
  `<id>/backup/` as it commits it, so the feed never lists it; were it left there, the feed's checksum duplicate check
  (`duplicate {mode: checksum}`) and, below that, the ingest's own commit record would each still skip it (mutation
  check 2026-11-05: with the move AND the duplicate check both disabled, the feed lists the upload again and adds no
  row). An SFTP feed was never affected (a
  remote collector does not gate the local inbox). Same seam as `BASIC-ONBOARD-SETTLE-1` (§6.20, [architecture-watch-this-folder.md](architecture-watch-this-folder.md)). Not covered: the
  scope is not tied to one run, so a scheduled run that lists the inbox while it is open (the person's run then waits
  on the Pipeline's run lock) takes the upload; it is imported once, by the feed, and the person's run reports
  nothing imported. Tests:
  `ControlApiDatasetFeedTest` (`addAFileRightAfterKeepThisUpToDate…`, `onBasicAddAFileStillTakes…`), `RunFilesTest`.
- **Waiting files** = regular files under the inbox (depth 4, links not followed) with the import's extension, never a
  hidden one (`.landing-*` uploads in flight). A file whose name the import took before (`MarkerManager
  .isAlreadyProcessed`) is skipped by Run now and would wait for ever, so it is flagged (`alreadyImported`, per file and
  counted) and the inbox says to rename it (found by the ledger lane).
- **The index Job is proposed, not written.** The PUT answers `indexJob`: an `la.index.build` Job
  (`on_signal: pipeline.commit`, `when $signal.pipeline == <id> && SUCCESS`, the link mapping's columns, `allow_full`)
  or null without a link mapping; the client saves it through `POST /jobs`, so the Jobs route's rights, approvals and
  owner stamping decide. Without `canAuthorWorkbench` the card names the Job to ask an administrator for; a 409 (Keep
  again after Stop: the Job is already there) is said as kept.
- **UI:** `add-data/dataset-feed.component.ts` (Inbox, *Import waiting files* = the card's Run now, the Keep form with
  option pickers and a pen field, *Stop*), mounted in the card's *Keep this up to date* block; the card keeps *Open in
  editor* (console) for more settings. The *Data status* line (`feedStatusLine`: *Inbox: N files waiting* or *Kept up to
  date: …*) reaches the shared `<inspecto-la-data-status>` through the arrivals answer's `feed` (one read for the card, the
  Start box and the Rows dock).
- **Decisions taken (review on return):** (a) `on_change: ignore` - a corrected file re-sent under the SAME name is
  skipped, because a feed's arrivals add rows and re-reading it would double them; send corrections under a new name. D-2 (a watched
  feed defaults to *Replace the period*) is not applied to scheduled runs: a scheduled run has no upload to take its days from; (b) an SFTP feed
  only (no FTP / FTPS / S3) - the plan names SFTP; (c) a Reference is not offered a feed (a feed adds rows; a
  Reference is replaced); (d) the routes are `canUploadData`, like Add a file, not `canAuthorWorkbench`: the PUT
  changes only the import's own Pipeline, within fixed safe defaults.
- Tests: `ControlApiDatasetFeedTest` (real HTTP: Basic inbox + Run now, Standard PUT/DELETE and the reloaded config,
  every refusal, armed 401/403/200), `dataset-feed.component.spec.ts`, `import-dataset-card.component.spec.ts`.

## 6.19 Dataset storage: compaction, the merge map and retention (LA-DATASET-COMPACT-1, as built 2026-11-05)

Plan [`la-data-arrival-plan.md`](../../../../archived-documents/plans-archive/la-data-arrival-plan.md) P4, decisions D-5, D-7…D-10 (operator
2026-11-05). Class `inspecto/.../control/DatasetCompaction.java`, registered from `OnboardRoutes`. Since
`BASIC-ELT-PROFILE-1` B6 the merge, the map, the heal and the retention are the engine's `compact` `mode: arrivals`
(`ArrivalCompactor`), which also marks the merged-away files `COMPACTED_AWAY` so a reprocess is refused, not doubled;
`DatasetCompaction` keeps the routes and calls it ([spec-arrival-compaction.md](../elt/spec-arrival-compaction.md)).

| Route | Gate | What |
|---|---|---|
| `GET /datasets/{id}/storage` | `canUploadData` | `files`, `bytes`, `threshold` (20), `compactedAt`, `compactedArrivals`, `removedArrivals`, `retention`, `retentionAvailable` |
| `POST /datasets/{id}/compact` | `canUploadData` | *Compact now*, at any count; claims `OnboardImport.IN_FLIGHT` (409 while an import or a delete runs); the answer carries `compaction {result, filesBefore, filesAfter, rowsRemoved, message}` |
| `PUT /datasets/{id}/storage` | `canAuthorWorkbench` | `{retention: {days: N} \| {arrivals: N} \| null}` on the Dataset entry; 503 without `pipeline.automation` (Basic); an approval policy over Datasets refuses (409) |

All three after Add a file's gates (503 no write root, 403 `data.self_import`, 404 / 409 `importDataset`).

- **On its own (D-7).** `OnboardImport.Job.followOn` calls `DatasetCompaction.afterImport` first, so every import path
  (commit, Add a file, Run now) compacts past 20 files inside the person's run, before the index build; the summary
  carries `compaction`. Never throws: a failed compaction leaves the files and says so. Every edition.
- **How.** Every Parquet file under `<dataRoot>/<id>/database` (no hidden file, no link) is read in a DuckDB sealed to
  `<dataRoot>/<id>`, each row tagged with its arrival (`__seq`) and its row number, and written ONE file
  `compacted_<stamp>.parquet` ordered by arrival (`UNION ALL BY NAME`, so drifted columns line up). Arrival order: the
  segments the previous merged file holds (its map's order), then the loose files by modification time.
- **The merge map (D-8)** `<dataRoot>/<id>/compaction.json`: `merged`, `at`, `arrivals[{file, received, firstRow, rows,
  coversFrom, coversTo}]` (the arrival's own output name, its row segment, the period of its kept rows),
  `removed[{file, at, reason}]` (at most 500), `retention`. `ArrivalCompactor.merged(dataRoot, id)` answers it ONLY
  while the merged file exists, so a *Replace everything* (which sets the whole `database` folder aside) silences it.
- **The swap.** Write the merged file in `<id>/temp`, write `compaction.json.pending` (merged name, inputs, next map),
  move the merged file in, delete the inputs (only inside `database`, never the merged file), commit the map. `heal`
  (run before every compaction) finishes a pending swap whose merged file moved in, or drops one whose file did not.
  Between the move and the deletes a reader may count rows twice; an input that cannot be deleted (held open) stays
  named in the pending file and is counted twice until the next compaction removes it - the outcome says so. While a
  pending file still names one, compaction waits (`NOT_NEEDED`): its rows are in the merged file already, and merging
  it again would keep them twice for good.
- **Retention (D-9), Standard and up**, applied at compaction: *keep N arrivals* drops the oldest arrivals whole; *keep N
  days* keeps rows whose event date (`eventDate`, the When column) is within N days - a row whose date is missing or
  cannot be read as a timestamp always stays - and without a When column (or one the files do not have, checked with
  `DESCRIBE`) drops whole arrivals received before the cutoff. A policy stored on Basic is ignored
  (`retentionInForce`): Basic never drops a row.
- **Link index (D-10).** The files are rewritten, so the index's next build is a full one (its own advice, nothing added).
- **A run outside Add data compacts on its commit (`LA-DATASET-COMPACT-2`, 2026-11-05).** A Standard feed's scheduled run
  (or any run of the import Pipeline not started by Add data) never reaches `Job.followOn`. `CollectorService` subscribes ONE
  commit step to its Space's `ConsignmentEventBus` in its constructor - before `JobService.start()` subscribes - and the
  control plane sets it (`DatasetCompaction.register` → `CollectorService.commitStep`). The bus publishes synchronously on the
  run's thread, under the Pipeline's run claim, so the compaction finishes before the Space's Jobs (the `la.index.build` Job
  on `pipeline.commit`) are handed the commit; no other run of the import can write meanwhile. `DatasetCompaction.afterCommit`
  takes a SUCCESS, non-dry-run commit, finds the Space whose service published it (the attached ControlApis' Spaces), reads
  its roots under that Space's MDC, and acts only for an import Add data wrote for THIS id (`OnboardImport.isOurs`; the
  import Pipeline is named as its Dataset). It claims `OnboardImport.IN_FLIGHT` and **skips** when the claim is held: an
  import (which compacts in its own follow-on), *Compact now* or a delete is at work. Threshold and retention as on import.
  Never throws; a compaction that ran is logged. *Chosen over a `dataset.compact` Job step on `pipeline.commit`* (the row's
  suggestion): two Jobs on the same signal run concurrently, so the index build would race the swap, and the Job would
  have to be authored per Dataset. *Not* the `ImportFollowOn` seam either: a scheduled run never calls it.
  **Superseded 2026-11-19 (`BASIC-ELT-PROFILE-1` BE-1a):** the one step is now an ordered registry of named commit steps
  (`CollectorService.commitStep(name, order, phase, step)`), and `ImportCommitSteps` registers the whole follow_on step
  of the import for such a run: compaction as above (phase `COMMIT`, per commit, before the Jobs), then each
  `ImportFollowOn` (the link index) and the Expectations once per run, after its last commit and outside ingest (phase
  `RUN`). Same `isOurs` / `IN_FLIGHT` rules. `DatasetCompaction.afterCommit` is gone. See
  [spec-pipeline-execution-run-steps.md](../elt/spec-pipeline-execution-run-steps.md#commit-steps--the-work-a-commit-needs-before-it-is-done).
- **The graph after a compaction (`LA-DATASET-COMPACT-2`).** The link index's read gate (`IndexedRead.select`,
  `IndexStaleness.removedInput`) already refuses an index whose input files are gone, so the graph reads the flat Dataset
  after a compaction - but it compared the files through `InputFingerprintCache` (30 s, cleared only by an index build), so
  for up to 30 s a read was served from the index with the rows a retention removed (measured: `/inv/projection/neighbors`
  answered `source.kind: index, stale: false`). Every compaction that merged (or failed part-way) now calls each installed
  application's `ImportFollowOn.filesRewritten` (a default no-op method; inside a run, *Compact now* and a scheduled
  commit, every edition); Link Analysis's `LinkIndexFollowOn` drops the Dataset's cached fingerprints
  (`InputFingerprintCache.forget`), so the next read answers `index_stale_refused` from the flat Dataset. *The smaller fix
  was chosen*: no rebuild inside *Compact now* - the graph reads flat until the next index build (Basic: the next import;
  Standard: the next commit's Job), as after any other removal.
- **UI:** `add-data/dataset-storage.component.ts` in the card (*Storage* line, *Compact now*, on Standard the *Keep*
  picker for a person who may author); `storageStatusLine` is its *Data status* line.
- **Arrivals and Data status.** `DatasetArrivals` reads the merge map: an arrival whose own output a compaction merged is
  still *kept*, with the period of its segment. The arrivals answer carries `feed` (`DatasetFeed.summary`: kind, interval,
  Connection, `waiting`, `alreadyImported` - never the inbox path) and `storage` (`describe`), and `dataStatusLines()` adds
  one line for each (`feedSummaryLine`, `storageStatusLine`), so the card, the Start box and the Rows dock say them.
- **A merged file rewritten since** (*Replace the period*, §6.17, removes days from every older file, the merged one
  included): the map keeps the merged file's `mergedBytes` and `mergedModified` and trusts its row segments only while
  both match. **Replace the period keeps the map true** (`LA-DATASET-COMPACT-2`, as built 2026-11-06): when the file it
  rewrites IS the map's merged file and the map still holds (`ArrivalCompactor.trustedSegments`), it writes the kept
  rows in their row order (`file_row_number`), re-counts each arrival's segment and period from the same read
  (`ArrivalReplacer.recount`), and commits the map with the new size and time (`segmentsRewritten`; undone with the
  import from the copy the replace keeps aside). An arrival left with no row drops out of the map, so the arrivals answer reads it as replaced
  (not as a retention removal). So *keep N arrivals* after a *Replace the period* counts every arrival. Only when the
  map cannot be trusted (an older map, a row outside every segment, a rewrite by anything else) do its arrivals stay
  *kept* with no known period and the next compaction carries the file as ONE block (`holds` names the arrivals in it),
  counted as one arrival by *keep N arrivals* - never sliced by stale offsets.
- **Deliberately not done (review on return; board `LA-DATASET-COMPACT-2` keeps (e)):** (a) *(built 2026-11-05, above:
  a scheduled run compacts on its commit)*; (b) *Compact now* with a retention policy does not rebuild the index - the
  graph reads the flat Dataset until the next build *(since 2026-11-05 it never shows a removed row, above)*; (c) the policy is gated on `pipeline.automation` (the Standard marker
  the data-tier plan already uses; declared by `inspecto-ops` since 2026-11-05 - before, no module did, so real Standard
  bundles refused the policy and *Keep this up to date* like Basic, test `ControlApiKeepUpToDateStandardTest`); (d) a leftover that can never be deleted (a folder named `*.parquet`) keeps
  compaction waiting, saying why each time - nothing is lost or doubled; (e) *(built 2026-11-06, above: Replace the
  period keeps the map true; only a `holds` block from an untrusted map still counts as ONE arrival)*; (f) the arrivals answer has no capability (as
  before), so its `feed` tells a viewer of the Dataset the Connection's name, the ready marker and the waiting count -
  never the inbox path.
- Tests: `ControlApiDatasetCompactionTest` (21 arrivals compact with each period kept and listed as kept by the arrivals
  answer, Compact now, a Replace the period that rewrites the merged file, Replace resets the map, retention by arrivals /
  by event date / an unreadable date / a missing When column / never on Basic, the crash heal, a file held open, armed
  gates, `keepNArrivalsAfterAReplaceThePeriodCountsEachArrival`, compaction over files whose columns differ
  (`ControlApiArrivalNewColumnsTest`), `aRunOutsideAddDataCompactsOnItsCommit`: a run not started by Add data compacts, and leaves a held Dataset alone),
  `ControlApiOnboardLinkAnalysisTest#afterARetentionCompactionTheGraphNoLongerShowsTheRemovedRows` (geo-link: right after a
  *keep 1 arrival* Compact now the neighbours read is `index_stale_refused` and draws no removed row), `dataset-storage.component.spec.ts`, the Data status lines in `dataset-feed.component.spec.ts`.

## 6.20 Watch this folder: the 30 s inbox poll (BASIC-ONBOARD-1 P2, as built 2026-11-05)

Moved to its own page: [architecture-watch-this-folder.md](architecture-watch-this-folder.md#620-watch-this-folder-the-30-s-inbox-poll-basic-onboard-1-p2-as-built-2026-11-05).
