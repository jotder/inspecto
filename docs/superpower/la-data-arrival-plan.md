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

## 5b. P1 as built (2026-11-03, assistant-decided under operator delegation "take decisions on my behalf")

- `GET /datasets/{id}/arrivals` (`inspecto/.../control/DatasetArrivals.java`, registered in `DatasetRoutes`): a READ gate as
  `/freshness` (503 no write root · 400 bad id · 404 unknown or shared away); a Dataset no import made answers 200 with no
  arrivals and the reason. Newest first, at most 200 (`truncated`). Per arrival: `file, sender (upload | system),
  received, status, rowsRead, rowsRejected, mode, sha256, index, consignment, kept, coversFrom, coversTo`. The import
  fields of the entry are never trusted alone: only the Add data Pipeline written for THIS id is read (a forged
  `importPipeline` answers no arrivals), as `OnboardImport.importDataset` does. Accepted for now (review 2026-11-03): the
  whole per-file ledger is read per request before the newest 200 are cut; the sha256 is read in the request thread.
- Sidecar `<dataRoot>/<id>/arrivals.jsonl`, one line per successful upload, written after the run (first commit and Add a
  file), matched to the ledger row by the **landed file name** (unique per upload, `add_<stamp>_…`; one run may take an
  upload and a waiting drop together, even in separate Consignments). Best effort: a failed write only makes that upload read as *from a system*.
- Period: min / max of the Dataset's `eventDate` over the arrival's own Parquet outputs, read in a DuckDB sealed to
  `<dataRoot>/<id>`; outputs outside it are never read. No `eventDate` → no period.
- UI: the Add data **Dataset card** shows *Data status* (received · covers · graph, the graph part only when the upload
  recorded an index outcome) and an **Arrivals** list (five, then *Show all*), re-read after every Add a file / Run now.
- **Decisions taken (review on return):** (a) a *system* arrival says nothing about the graph rather than guess - Run
  now's own index outcome is not recorded per file yet; (b) the *Data status* is on the Dataset card only for now - the
  Start box and dock lines wait for a second request, so the Explorer screens stay as they are; (c) no capability on
  the read (as `/freshness`): the list holds file names and counts, never row values; (d) the sidecar is not versioned
  or signed - it is display data, the audit ledger stays the record.
- Tests: `ControlApiOnboardFilesTest#theArrivalsOfAnImportDatasetAreListedNewestFirstWithTheirPeriod` (real HTTP),
  `import-dataset-card.component.spec.ts` (rendering, five-then-all, a11y; the wording helpers).

## 5c. P1 finished (2026-11-05): V4, Run now's index outcome, Data status on the Start box and dock

- **V4 measured** (`la/inspecto-geo-link` `ControlApiOnboardLinkAnalysisTest#onBasicEachAppendIsIndexedAsAnAppendAndTheArrivalsSayIt`,
  and `#v4MeasuredAtScale` by hand with `-Dla.v4.rows=N`): on Basic every *Add a file* in append mode and every *Run now*
  that takes a dropped file builds the link index in mode **append** (the new file only); only the first build is `full`.
  Wall time of the whole Add a file (run + row count + index + Expectations; 10,000 appended rows, this laptop):

  | Dataset rows | first commit | each append (3) | Run now, one dropped file |
  |---|---|---|---|
  | 2,000 (200-row appends) | 2.1 s | 3.5 - 3.8 s | 2.5 s |
  | 100,000 | 5.9 s | 6.6 - 7.5 s | 4.9 s |
  | 1,000,000 | 11.7 s | 7.4 - 10.7 s | 3.9 s |

  The append cost grows far slower than the Dataset (10× the rows: about +3 s), so the index is no reason to stop
  appending on Basic. 10 M rows was not measured: the base file passes the 64 MiB inline limit and runs as a followed
  (202) run; expect tens of seconds per append, not a rebuild. A *Replace the period* rewrites existing Parquet files, so
  its next build is `full` (the plan advice for a rewritten input) - said in P2.
- **Run now records each file it took** in the sidecar (`sender: system`, `mode: append`, the run's index outcome), so
  decision (a) of §5b is reversed: a *system* arrival now says what the graph did. One run that takes an upload and a
  waiting drop records both. A system line is keyed by file name **and** batch (a foreign process may reuse a name). Every
  line now carries `at` and, beside `index`, `indexMode` (append / full / compact / none). The run summary's `index`
  carries `mode` too.
- **A re-sent drop under the same name is skipped** by the import Pipeline's duplicate policy (Run now answers
  `rowsIn: 0`, no arrival) - pinned in `ControlApiOnboardFilesTest#theArrivalsOfAnImportDatasetAreListedNewestFirstWithTheirPeriod`.
  The file stays in the inbox (P3's *N files waiting* must say why such a file is not taken).
- **Data status is one component**, `<inspecto-la-data-status>` (`link-analysis/src/add-data/data-status.component.ts`):
  `[dataset]` (it reads `GET /datasets/{id}/arrivals` itself) or `[arrivals]` (the host's read). On the Dataset card, under
  the Start box's Dataset picker (decision (b) of §5b reversed), and above the Rows dock's table for the drawn Dataset. The
  graph clock: *graph up to date* when the newest arrival was indexed, *graph N files behind* when newer arrivals recorded
  a failed or refused build, nothing when nothing was recorded. **Extension point** for P3 / P4: `dataStatusLines()` in
  `add-data.model.ts` - one more line per field of the arrivals answer.

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

## 8. P5 — the Data page: Datasets, Entities and named Mappings (operator 2026-11-03)

**Operator ask:** "not only Entities - the Mapping is saved and registered as a Dataset; the user manages and saves the
Dataset, its Entities and Mappings, handy." **Operator choices (2026-11-03):** a *named mapping saved as its own
Dataset* over the same data, and a new **Data** page in Inspecto LA (`LA-DATA-PAGE-1`).

**BUILT 2026-11-03 (P5a + P5b):** `DatasetEntryRules.validateMapping` (on every Dataset write: `basedOn` names an Add
data Dataset, `physicalRef` is exactly the base's, `basedOn` only on a Mapping), the base-view check in
`ComponentRoutes.writeComponent` (404 like reading the base), the delete guard (409 naming the Mappings), *Add a file* /
*Run now* on a Mapping refused naming the base; the Inspecto LA **Data** page (`/data`, `data.page.ts` + pure
`data-page.ts`) with the Dataset card, Entities (the existing `PUT /datasets/{id}/columns/{c}`) and Mappings (create /
edit / delete through the component routes). Tests: `ControlApiOnboardFilesTest#aMappingReadsItsBasesDataItsOwnWay…`,
`data-page.spec.ts`, `data.page.spec.ts`. User guide §3.4. **Left:** M3 - a Mapping has no link index of its own (a
*Build index* gesture or Standard's Jobs); P5c the live check on Basic.

**Review 2026-11-03, fixed:** a hidden base and a missing base both answer 404, checked before the shape rules (no
existence oracle); a Mapping may carry no `sql` / `view` / `sourceName` / `calculated` and only columns of its base;
Run now on a Mapping is refused like Add a file. **Recorded, not changed (assistant-decided under delegation):** (a) a
plain Dataset entry may already point its `physicalRef` at any store - that predates Mappings and is the component
routes' existing rule, not widened here; (b) a new Mapping is visible to the Space unless shared, like any Dataset the
component routes create - its author must see the base; (c) Mappings are not re-checked when their base is edited
(a base `physicalRef` change leaves them stale until their next save; deleting the base is refused while they exist).

**Model.**
- An **import Dataset** (Add data) owns the files, the Pipeline and the arrivals; it keeps its own default mapping.
- A **Mapping** is a Dataset entry over the SAME stored data with its own reading of it: `physicalRef` = the import's,
  `basedOn: <import id>`, `origin: mapping`, its own `columns[].entityType` / classification, `primaryEntityType`,
  `defaultLinkMapping {from, to, kind?, time?, amount?, details?}` and `eventDate`. One file read as *calls* and as
  *SMS*, or *sender → receiver* and *device → number*, without copying data. It appears everywhere a Dataset does
  (Start box, Open, Explore, widgets) because it IS one.
- The server already validates the mapping against the entry's columns (`DatasetEntryRules`); a Mapping is saved
  through the Dataset entry route. New: `basedOn` is checked (must name an import Dataset the author may view; its
  `physicalRef` must equal the base's) and a Mapping takes no files (*Add a file* / *Run now* answer 409: "add files to
  <base>").

**The Data page** (`/data`, left navigation between Link Analysis and Entity Lists):
- every Dataset as a card: name, kind, *Data status* line, *Arrivals* (import Datasets), *Add a file* / *Run now*;
- **Entities**: each column's Entity Type (or *a detail*), editable (the existing `PUT /datasets/{id}/columns/{c}`);
- **Mappings**: the import's own mapping and every Mapping based on it; *New mapping* and *Edit* open the Add data
  step-3 editor ("What does each column hold?" + "Each row is a link") over the base's columns, with a name;
  *Delete* removes the Mapping entry only (never the data).

**Decisions (assistant-decided under delegation, to review):** (M1) a Mapping is a plain Dataset entry, no new store;
(M2) deleting an import Dataset that Mappings are based on is refused (409, naming them) until they are deleted - no
dangling entries; (M3) a Mapping gets no link index of its own at save (exploration reads the Dataset; Standard's Jobs
or a later *Build index* add it); (M4) names follow the Dataset id rule and must not clash with an existing Dataset.

**Phases.** P5a backend (`basedOn` rules, files refused on a Mapping, delete refused while Mappings exist, tests);
P5b the Data page (list + Entities + Mappings, the step-3 editor reused, specs + a11y); P5c live check on Basic.
