# LA-DATA-ARRIVAL-1 — how entity and link data arrives, and how the analyst knows it

> **PLAN 2026-11-03 — decisions D-1…D-5 accepted by the operator 2026-11-03 (the recommended options, §6).**
> Board rows: `LA-ARRIVAL-LEDGER-1` (P0 + P1), `LA-ARRIVAL-MODES-1` (P2), `LA-ARRIVAL-FEEDS-1` (P3),
> `LA-DATASET-COMPACT-1` (P4). Product hub: `docs/okf/product/module/la-core/`
> (architecture §6.10 Add data import, §4 link index). Related plans: `data-tier-editions-plan.md` (DATA-TIERS-1:
> DT-3, DT-5, DT-14, DT-16, DT-17), `first-run-onboarding-plan.md` (ONBOARDING-FIRST-RUN-1),
> `link-analysis-roadmap.md` §"Daily ingestion into Link Analysis" (D-ING1…D-ING6). This plan does not reopen those
> decisions; it adds the piece none of them owns: **one model of an arrival, and one place that says what arrived.**

## 1. The goal in the analyst's words

For every Dataset I explore I can answer, without asking an admin:

1. **Where does this data come from?** A file I uploaded, or files another system drops (a folder or SFTP).
2. **When did data last arrive, and what did that arrival do?** File, rows read / kept / rejected, replaced or added.
3. **What period does the data cover?** "Calls from 1 Oct to 2 Nov" - not just "imported yesterday".
4. **Does the graph show all of it yet?** The link index may lag the files.
5. **First load vs the next ones:** what happens when a new file comes - does it replace, add, or update a period,
   and what happens if the same file comes twice?

Two arrival routes must give the same answers: **file upload** (Add data / Add a file) and **file transfer by a
foreign process** (a folder or SFTP the Space watches or is told to check).

## 2. What exists (as built, verified 2026-11-03)

| Stage | Built | Where | Edition |
|---|---|---|---|
| Upload | `POST /onboard/files` (draft, profiled) → `/commit`; Add a file `POST /datasets/{id}/files?mode=replace\|append`; Run now `POST /datasets/{id}/run` | `inspecto/.../control/OnboardRoutes.java:71-80`, `OnboardImport.java` | all (capability `canUploadData`) |
| Limits | 500 MiB per file, 10 M rows, 20 imports per Space; large files answer 202 + run id | `LaLimit.java:232-236`, `OnboardImport.java:153,199` | all, Space-layer settings |
| Landing | upload copied into the Dataset's inbox `<dataRoot>/inbox/<id>` as `.landing-*`, then atomic move to `add_<stamp>_<stem>.<ext>` | `OnboardImport.landFromDraft` :575-590 | all |
| Pipeline | one ordinary Pipeline per import Dataset (`origin: import`, `trigger: manual`, `collector.discovery: poll` on that inbox) | `OnboardImport.java:435,483` | all; Basic is manual only (DT-5, DT-17) |
| Foreign drops | Collector SPI: local folder, SFTP/FTPS, S3, db watermark; `StabilityGate` (mtime settle), `ReadyMarker`, `DuplicatePolicy` (size / mtime / checksum, on-change reprocess), durable `DbAcquisitionLedger`, quarantine, gaps (`DayManifest`, `FileSequenceGaps`) | `platform/inspecto-acquire/.../acquire/`, `providers/inspecto-connectors/.../SftpConnector.java` | connectors in all editions; **automatic polling / schedules Standard+** (DT-17) |
| Conversion | the Pipeline engine writes **partitioned Parquet** (COPY + two-step atomic rename) to `<dataRoot>/<id>/database`; optional DuckLake registration | `platform/inspecto-etl/.../PartitionWriter.java`, `DuckLakeRegistrar` | all |
| Modes | **replace** (default; previous output set aside) or **append** (rows added; before/after counted) | `OnboardImport.java:916-944, 662` | all |
| Link index | at import: one build in the user's run (`allowFull`), skipped without a link mapping. Scheduled: `la.index.build` Job, advice `UP_TO_DATE / append / compact / full` | `OnboardImport.followOn` ~:703; `la/.../ScheduledIndexBuild.java` | import-time build all editions (in-process SPI); index **routes** and the Job Standard+ |
| Freshness | `lastImport` on the Dataset entry (**the last one only**: at, replaced, rowsBefore/After, batch, file); run ledger batches + quarantine; *Data as of* computed at read time from an event-date column (`GET /datasets/{id}/freshness`, `DatasetProvider.dataAsOf`); Files list with per-file index state; freshness Alert Rules (`maximumAge`); delivery anomalies by day | `OnboardImport.java:676-697`; `DatasetProvider.java:95-102`; `la-api/.../DatasetFilesRoutes.java`; `job/FreshnessSweep.java` | `lastImport` + *Data as of* all; Files, coverage, data-changes, Alerts **Standard+ (Investigations add-on)** |

## 3. What is missing

| # | Gap | Effect on the analyst |
|---|---|---|
| G1 | **No arrival history.** `lastImport` is overwritten by every import; the run ledger has batches but no screen outside the Standard Files list. | "What changed" shows one line; after the second file the first is forgotten. |
| G2 | **No covered period per arrival.** *Data as of* is one max over the whole Dataset, at read time; nothing records "this file held 1-7 Oct". | Cannot tell a late or partial delivery from a complete one. |
| G3 | **Index lag is not said at Dataset level.** Files show INDEXED / WAITING only on Standard; Basic builds inline and says nothing later. | The graph may silently miss the newest file. |
| G4 | **Incremental semantics are blunt for uploads.** Watched daily feeds already do it right (`LA-DAILY-INGEST-1`: daily partitions, a corrected same-name day replaces its partition via `collector.duplicate` checksum + `on_change: reprocess`, per-day manifest with gap / late-day signals). An **import Dataset** (Add data) has two modes only - replace everything, or add rows - and an upload's name is `add_<stamp>_…`, so the same file uploaded twice is two arrivals (to verify, V2). | Re-sent or overlapping uploads double the links. |
| G5 | **Basic cannot take foreign drops in a way the user can see.** The import Pipeline polls its inbox when run, so a file a foreign process puts there is *probably* taken by Run now (V1) - but nothing shows the inbox path, the files waiting, or offers "import waiting files". | The only Basic route for system-fed data is a person re-uploading. |
| G6 | **Schema drift on later arrivals** (a column added, renamed or missing in file N) has no stated policy or message. | Silent rejects or a failed run with an engine message. |
| G7 | **Dataset Parquet hygiene unknown.** Many appends make many small files; index compaction exists, Dataset compaction is unconfirmed (V3). | Slow reads after weeks of daily files. |
| G8 | **Words.** GLOSSARY has no *Link index*, *Arrival*, *Data as of*. | UI and docs use different words for the same clock. |

## 4. Proposed model

**One Dataset, one feed, many arrivals.**

- **Feed** = how files come: *Uploads* (a person) or *Watched folder / SFTP* (a system). One Dataset has one feed;
  a person may still Add a file to a watched Dataset (same Pipeline, DT-2: no parallel ingest path).
- **Arrival** = one file (one Consignment) taken by the Pipeline. Recorded once, durably, in an **arrival ledger** per
  Dataset: file name, size, sha256, received at, mode, rows read / kept / rejected (+ quarantine link), **event time
  min and max** of the kept rows (when the Dataset has a *When* column), schema verdict, and index outcome
  (indexed / waiting / skipped). `lastImport` becomes the newest ledger row.
- **Three clocks**, shown together as one *Data status* line wherever a Dataset is named (Add data result, Home *What
  changed*, Start box, Rows dock):
  **Received** (last arrival) · **Covers through** (max event time) · **Graph indexed through** (newest arrival the
  index holds). Example: *Received 2 Nov 09:14 · covers 1 Oct - 2 Nov · graph up to date* or *… graph 1 file behind*.
- **Modes**, named for what the user expects:
  *Replace everything* (today's replace) · *Add rows* (today's append) · **Replace the period it covers** (new: the
  arrival's event-date days overwrite those days, others stay - the daily-partition behaviour `LA-DAILY-INGEST-1`
  already gives watched feeds (D-ING1), brought to import Datasets). A **same file again**
  (same sha256) is refused for uploads with "already imported on …" and follows `DuplicatePolicy` for folders.

## 5. Phases

| Phase | Scope | Size | Edition |
|---|---|---|---|
| **P0 verify** | V1 Run now takes foreign files from the inbox; V2 same upload twice = double rows; V3 Dataset Parquet compaction; V4 index build per append on Basic (cost at 10 M rows) | 1 day, tests only | - |
| **P1 Arrival ledger + Data status** | ledger rows written by `OnboardImport` and by Collector-fed runs (one writer: the run summary); event min/max computed from the kept Parquet; `GET /datasets/{id}/arrivals`; UI: arrival history on the Dataset card, the three-clock *Data status* line; GLOSSARY entries | backend M, UI M | **all editions** (it is the Explorer's own data, not Investigation data) |
| **P2 Incremental semantics** | *Replace the period*; duplicate-file refusal on upload (sha256); schema-drift policy (new column: kept as detail, missing column: rows rejected with a named reason) and its message | backend M, UI S | all |
| **P3 System-fed files** | Standard: *Keep this up to date* creates the watched folder / SFTP feed on the existing import Pipeline with safe defaults (stability gate, ready marker optional, checksum duplicate policy, quarantine), index Job in append mode. Basic: per D-1 | M | Standard+ automatic; Basic per D-1 |
| **P4 Storage hygiene** | Dataset Parquet compaction and retention (keep N days / N arrivals), surfaced as one line in Data status | M | per D-5 |

P1 is the one to build first: it makes every later phase visible, and it is useful on Basic today.

## 5a. P0 findings (2026-11-03, `ControlApiOnboardFilesTest`)

- **V1 confirmed:** a file a foreign process writes into `<dataRoot>/inbox/<id>` is taken by *Run now* (20 rows
  added, inbox emptied) - pinned by `aFileDroppedInTheInboxIsTakenByRunNowAndEachArrivalIsItsOwnParquetFile`. So D-1's
  *Import waiting files* is UI only: list the inbox, call Run now.
- **V2 confirmed (the defect):** the same file appended twice doubles the rows (130 → 160). `LA-ARRIVAL-MODES-1`.
- **V3:** each arrival lands as **its own Parquet file** named after its source (`foreign_drop_out.parquet`,
  `add_<stamp>_transfers_out.parquet`); nothing compacts them. Good for tracing, bad for reads in the long run
  (`LA-DATASET-COMPACT-1`).
- **V4** (index cost per append on Basic) needs Link Analysis on the classpath; measure in `la/inspecto-geo-link`
  with the ledger work.

**Ledger design, revised (smaller than §4 assumed).** The engine already writes a per-file audit row for every
arrival, uploads and drops alike (`ConsignmentAuditWriter` `_status_` ledger, `StatusStore.files(cfg)`):
`start_time, end_time, filename, status, parsed_rows, error_rows, output_paths, output_sizes_bytes, consignment_id,
origin, logical_name`. So the arrival list is a **read** over that ledger, not a new writer:

- **covered period** = Parquet column statistics (min / max of the Dataset's *When* column) of the row's
  `output_paths` - read from file metadata, nothing stored; an output a later *Replace everything* set aside reads as
  "replaced by <file>".
- **what the ledger lacks** - mode (replace / append), sha256 of the source, who sent it (upload or drop), and the
  import-time index outcome - goes into one small sidecar per Dataset, keyed by `consignment_id`, written by
  `OnboardImport` (an upload); a drop simply has no sidecar row (sender = *a system*).
- `GET /datasets/{id}/arrivals` joins the two; `lastImport` stays as it is (the Home card) until the UI reads the list.

## 6. Decisions (operator 2026-11-03: the recommended options, accepted)

- **D-1 Basic and foreign drops: manual.** Basic shows the Dataset's inbox path and *N files waiting · Import waiting
  files* (Run now). No polling on Basic; DT-5 / DT-17 stand. *(Rejected: polling an import inbox on Basic.)*
- **D-2 Default mode for a watched feed: Replace the period** when the Dataset has a *When* column, else *Add rows*.
- **D-3 Duplicates: file-level (sha256) now.** Row keys (upsert) only together with SCD2 (D-ING4, still deferred).
- **D-4 Arrival history: keep every ledger row** (small); the data files follow D-5.
- **D-5 Compaction in every edition** (it is read correctness); a **retention policy Standard+**.

## 7. Done means

- After two uploads and one folder drop, the Dataset shows three arrivals with their periods, and *Data status*
  names the newest one and whether the graph includes it - on Basic and on Standard.
- Uploading the same file twice is refused with the first import's date; a re-sent folder file follows its policy.
- A file covering 1-7 Oct sent again with corrections replaces exactly those days (Replace the period).
- Specs for each route (real-HTTP tests), UI specs, and one live drive on Basic and Preview.
