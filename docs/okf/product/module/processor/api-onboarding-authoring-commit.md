---
type: Seam
title: Onboarding authoring seams (draft lifecycle, previews, register pair) — Add data — the commit onward
description: Part 3 of 3 of Onboarding authoring seams (draft lifecycle, previews, register pair): 
tags: [control-plane, onboarding, config, enrichment, pipeline, reference]
timestamp: 2026-07-16T00:00:00Z
---

# Onboarding authoring seams (draft lifecycle, previews, register pair) — Add data — the commit onward

Part 3 of 3 of [Onboarding authoring seams (draft lifecycle, previews, register pair)](api-onboarding-authoring.md) · previous: [Add data — /onboard/files](api-onboarding-authoring-add-data.md).

## Add data: `/onboard/files` (file in → Dataset out; `LA-ONBOARD-1` phase 2, 2026-10-22) (continued)

### The commit

Body (every key optional; absent = the draft's proposal): `{name, description, columns:[{name, type?, format?,
entityType? (id|null), role?, rename?, drop?}], primaryEntityType?, link?: {from, to, kind?, primaryTime?,
primaryMeasure?, details?} | null, identifyingColumn?, filter?: [{column, op, value | min | max}]}` (the row filter:
below). Columns are named by their **draft** name. A column's role
is the stated `role`, else Entity when it holds an Entity Type, else the draft's proposal while it fits the column's
type (else Detail); the link's kind / primaryTime / primaryMeasure columns take Category / Time / Measure unless a
role is stated. A stated role that contradicts the Entity Type, the link's primaries, or a Measure on a non-number
column is 422. The Dataset is written with `columns[].role` and `defaultLinkMapping.primaryTime / primaryMeasure`
(`time` / `amount` are their old names, still read for entries written before). `format.*` cannot change at commit (422:
re-draft with `?delimiter=…`), because the columns were read with it. **A DATE or TIMESTAMP type stated only here,
with no `format`, gets the format most of the column's values are written in** (`OnboardImport.withFoundFormats`: the
draft's file is read once more, as a redraft reads it). Before, such a column was read with the Pipeline's one default
format (`%Y-%m-%d %H:%M:%S`) and every other value - a whole column of `2026-03-01T10:15:00` - was imported blank with
nothing said (seen in the 2026-11-15 drive: *Covers* stayed empty). Order:

1. Decide everything first: 422 for a choice naming nothing real; 409 for a taken name (Dataset, Pipeline, config
   files or `<data root>/<id>`), an approval policy over Datasets, Pipelines or schemas, an import of that id in
   flight (the id is claimed before these checks), or
   `import.max_datasets`. The Dataset entry passes `ComponentRoutes.validateKind` before anything is written.
2. `imports/<id>_schema.toon` (raw fields = every draft column, **index selectors for delimited files** (OB-4) and
   key names otherwise; a dropped column stays a raw field and leaves the mapping; a rename is the mapping's `name`)
   and `imports/<id>_pipeline.toon`, each through `SaveGate.check` (any ERROR refuses with its findings). The
   Pipeline: `origin: import` (OB-3; a spec key read by no engine code), `trigger: {type: manual}` (it never polls,
   on any edition), the local inbox `<data root>/inbox/<id>`, `accepts_upload: true`, `duplicate_check`, PARQUET
   into `<data root>/<id>/database`, and `produces: reference` for a Reference.
3. `registerPipeline`, then the file is **copied** into the inbox as `add_<letters>_<stem>.<ext>`, `<ext>` being the
   import's own lower-case extension that its `file_pattern` globs (so `.txt`, `.tab`, `.jsonl`, `.xlsm` and `T.CSV`
   land; letters only in the stamp: a long digit run in a file name is fingerprinted `<fp:…>` in the run ledgers) and the Pipeline runs once
   (`runPipeline`, synchronous). The summary counts the ledger batches and quarantine entries the run added:
   `rowsIn` (`total_input_rows`, rejected rows included), `rowsRejected`, `quarantined:[{file, reason}]`,
   `rowsBefore`, `rowsAfter`, `rowsChanged`, `replaced`. A run that quarantined the file or loaded no row **fails**
   (422 "nothing was imported … nothing was kept").

   **The summary accounts for every row and value** (`LA-FULL-CYCLE-TEST-1` finding 5; commit, Add a file and Run now):
   * `blanked:[{column, type, values, example}]` - per column, the values present in the file that its type could not
     read. The engine blanks such a value and **keeps the row** (`TRY_CAST` / `TRY_STRPTIME`), and its own audit keeps
     only a batch total, so the import counts them per column **before** the run, in one pass over each waiting file
     through the engine's reader view with the engine's own cast expressions (`OnboardImport.Job.prescan` over
     `DataTransformer.castChecks`, the predicates `countCastFailures` sums) - what the run does, not a second opinion.
     `type` is the Dataset column type (`number|date|timestamp|boolean`), `example` one such value. A number read
     through a decimal mark is counted with this import's own expression. `[]` when nothing was blanked.
   * `unlinked:{rows, noFrom, noTo, from, to}` - for a Dataset with a link: the rows this run wrote whose From or To is
     blank. They are kept in the Dataset and draw no link, which is why an index can hold fewer links than the Dataset
     has rows.
   * `respelled:[{column, values, example, readAs}]` - per Entity column, over the rows this run wrote: how many
     distinct values differ from another only by leading or trailing spaces or letter case (distinct values less
     distinct `upper(trim())` values), one of them, and the key the Entity Type's normaliser reads it as
     (`EntityTypes.normalise`; every normaliser folds case and outer spaces). `' acc-1003 '` is `ACC-1003`. ⚠ The
     claim is only that: Entity profiles, entity counts and lookups read the normalised key, while the link index keeps
     each spelling as its own node id (on the fixture `index.nodes` is 138 for 137 accounts), so the screen says "a
     second spelling of an entity already there", not "merged".
   Both are read from the Parquet the run wrote, before any compaction; unreadable output leaves them unsaid.
   An arrival (`GET /datasets/{id}/arrivals`) carries `rowsLeftOut`: the rows of the file that are not in the Dataset
   (rejected plus left out by the row filter); `0` says none, `null` that it is not known (a filter whose rows were not
   counted). `coversFrom` / `coversTo` are filled whenever the Time column holds real dates - which the format rule
   above now ensures.
4. The Dataset entry: `{physicalRef: <id>/database, description, origin: import, kind, importPipeline, importFormat,
   eventDate (a Stream's Time: the link's time, else the body's `eventDate`, else - body silent on both - the draft's
   proposed Time; kept with no link, since it is what each arrival covers and how fresh the Dataset is -
   `ADD-DATA-STREAM-TIME-1`, 2026-11-07), columns:[{name, type: string|number|date|timestamp|boolean, classification?}],
   primaryEntityType, defaultLinkMapping, identifyingColumn}`; with sign-in `owner` is the importer, as provenance:
   no `shares` key, so the Dataset is shared with its Space like every other Dataset in it (`LA-ONBOARD-DEFECTS-1`,
   2026-10-23; it was `shares: []`, private to the importer, and no screen could share it).
5. Follow-on work **inside the same run** (DT-16), in every edition (operator 2026-11-05: a person's import always gives
   a fresh graph; the Expectations too, DT-15): each installed application's follow-on
   step, registered through the `ImportFollowOn` SPI and reported under its own key (`BASIC-ONBOARD-1` P1; the
   draft lists them as `followOns:[{key, does, needs}]`). Link Analysis registers its link index
   (`LinkIndexFollowOn`, `la/inspecto-geo-link`): `index:{result: BUILT|UP_TO_DATE|REFUSED|FAILED|SKIPPED, edges,
   nodes, …}`, `edges` and `nodes` = what the index holds after the run, every entity once - after an append too;
   the owner is the importer, a full build is allowed. Without a registered follow-on (no Link Analysis) the summary
   has no `index` key and the import ends at its Parquet. Then every enabled Expectation whose target is the Pipeline
   (`expectations:[{name, status, violations}]`). Neither failing undoes the import; both are reported.
   Compaction, the follow-ons and the Expectations are the Pipeline's `follow_on` step (`import_steps`; a Pipeline
   without `import_steps` runs it too) and are ONE list, `ImportCommitSteps` (`BASIC-ELT-PROFILE-1` BE-1a,
   2026-11-19): a run of the import Pipeline that Add data did not start (a kept-up-to-date feed's scheduled poll,
   `POST /pipelines/{id}/run`) runs the same steps in the same order, once per run, as registered commit steps
   ([spec-pipeline-execution.md](../elt/spec-pipeline-execution-dry-run-recovery.md#commit-steps--the-work-a-commit-needs-before-it-is-done));
   Add data's run holds the Dataset's claim, so they run once for it, here.

**A failed commit leaves nothing behind**: every write is undone in reverse (Dataset entry, data and inbox
directories, registration, Pipeline file, schema file), and the draft keeps its file, so the same draft commits once
the cause is fixed (`ControlApiOnboardFilesTest.aFailedCommitLeavesNothingBehind`, falsified by removing the
rollback). A 202 run is undone the same way in the background and records `failed` with the reason. The background
run uses the Space roots and service captured on the request thread, never "the current Space".

#### Keep only some rows: the commit's `filter` (`ADD-DATA-ROW-FILTER-1`, as built 2026-11-05)

DT-7 puts "filter rows" in Basic's no-code shaping. The commit body takes an additive
`filter: [{column, op, value | min | max, matchCase?}]` (absent or `[]` = every row); a row is kept when it meets **every**
condition. `column` is the **draft** column's name (like `columns[]`), and must be a kept column. `op` is one of
`eq`, `ne`, `contains` (text columns only; ignoring case unless the condition carries `matchCase: true`, operator
2026-11-05: then `contains(col, 'v')` instead of `contains(lower(col), lower('v'))`; `matchCase` on any other op is
422) and `range` (numbers, dates and times; `min` and `max`
inclusive, either optional, not both absent, `min` ≤ `max`). At most 20 conditions; a text value is at most 200
characters, with no control characters. `ImportRowFilter` checks it all in `Plan.of`, before anything is written.
A refused condition is 422 naming it, for example `filter[1] (qty eq): '1 OR 1=1' is not a whole number` or
`filter[0] (nope): the draft has no column 'nope'`. A left-out column, an unknown key and a bad op are refused the
same way.

- **Design choice: the Pipeline's post-parse predicate, not the schema.** The schema has no row filter (its
  `mapping` names and types columns only). The engine's one post-parse row filter is `processing.csv_settings.where`,
  a predicate over the **mapped, typed** columns that `DataTransformer.materialize` applies on every ingest lane and
  every frontend. The commit renders the conditions into that key. In graph form the legacy key lifts as a
  `transform.filter` node (`PipelineConfig.resolveSteps`). That is not a Basic gate: DT-9 now gates Standard in the
  screens, not on load, and no `EditionFeatures` id covers `filter`. The planned `pipeline.transform` gate
  (DATA-TIERS-1 P6) must keep it Basic, as no-code shaping.
- **No raw value reaches SQL.** Each value is parsed to its column's type first: `long`, `BigDecimal`, `LocalDate`,
  `LocalDateTime` or boolean. The literal is rendered from that parsed value (`DATE '2026-01-31'`,
  `TIMESTAMP '… hh:mm:ss'`, a plain number, `TRUE`). Text is the only kind kept as given; it goes in as a quoted
  literal (`SqlIdent.sqlStr`), and the column is a quoted identifier. The predicate then passes the Pipeline save
  gate, which binds the legacy `where` with `SqlGuard` and `TypeFlow` against the import's columns
  (`ConfigRoutes.stepConfigFindings`).
  - ⚠ **`SqlGuard` does not catch an injection here.** `"channel" = 'x' OR '1'='1'` is valid read-only SQL, so the
    quoting is the defence that matters. A mutant that splices text raw keeps every row, and
    `ControlApiImportRowFilterTest` goes red.
  - ⚠ **`ne` is `(col IS NULL OR col <> v)`, not `IS DISTINCT FROM`.** `SqlGuard`'s lexical scan reads
    `FROM '<text>'` as a file read and refuses it. `ne` keeps a row whose value is empty; every other condition
    drops it.
- **Where it is kept.** The Pipeline holds `csv_settings.where`, which is what runs. The Dataset entry holds
  `importFilter: [{column, op, value | min | max}]`, display only: it uses the Dataset's (renamed) column names and
  canonical values, and the card reads it. A later file through the same Pipeline is filtered the same way: *Add a
  file*, *Run now*, the inbox and its 30 s poll, and a Postgres snapshot refresh (`addStaged`).
- **Counted.** A filtered run's summary adds `rowsFilteredOut` and `filter` (the entry's conditions). `rowsIn` is
  then rows written + rejected + left out. Each arrival line (`arrivals.jsonl`, `GET /datasets/{id}/arrivals`)
  carries the file's `rowsFilteredOut`.
  - ⚠ The ledger cannot give this figure. The native CSV lane and the Parquet lane count `parsed_rows` /
    `total_input_rows` **after** the filter (`NativeCsvStreamingEngine.streamUnit` counts `transformed`). So before
    the run, `OnboardImport.Job.rowsRead` counts each inbox file: delimited text through the engine's own reader
    view (`DuckDbCsvIngester.createRawInputView`, rejected rows excluded), Parquet by row count. This is one extra
    read per file, and only an import with a filter pays it. Left out = that count less the rows the file wrote: the
    batch output for a single file, else the lineage ledger.
  - JSON, NDJSON and Excel are not counted apart: `rowsFilteredOut` is `null`, never a wrong 0.
- **A file the filter keeps nothing of.**
  - The native CSV lane quarantines it as `empty`, which is engine behaviour. The summary rewrites that reason to
    "the row filter kept none of its N rows".
  - An upload (commit, Add a file, snapshot refresh) of such a file is refused: 422 "nothing was imported … the row
    filter kept none of its N rows". Nothing changes.
  - Run now and the poll report it as set aside.
- **Tests.** `ControlApiImportRowFilterTest` (synthetic rows):
  - the written filter keeps exactly the rows that random conditions describe, over 10 seeds;
  - refusals naming the condition, and an injection-shaped text value that stays a literal;
  - later files: Add a file (replace and append), an inbox drop taken by Run now, and the arrivals' counts;
  - a Parquet replace handed to `addStaged`, as a snapshot's Refresh now does.

  `ControlApiPostgresSnapshotTest.aSnapshotKeepsOnlyTheFilteredRowsOnEveryRefresh` covers the real-Postgres path. It
  is skipped without `INSPECTO_TEST_PG_URL`.

#### Edit filter: `GET` / `PUT /datasets/{id}/filter` (`ADD-DATA-FILTER-EDIT-1`, as built 2026-11-06)

The conditions can be changed after the commit (`ImportFilterEdit`).
- **`GET`** answers `{dataset, filter, columns: [{name, type}]}`: the entry's `importFilter`, and the columns a condition
  may name, with the engine type the import gives each. They are read from the import's own schema
  (`imports/<id>_schema.toon`): the mapped columns by their Dataset names, typed by the raw field, or by the cast of a
  decimal-mark column (`TRY_CAST(… AS DOUBLE)`).
- **`PUT`** takes `{filter: [...]}`, the commit's shape, but `column` is the **Dataset's** name (no draft any more).
  `ImportRowFilter.of(…, dataset)` checks and renders it, so every refusal, the literal rendering and the huge-exponent
  guard are the commit's. A refusal names the Dataset: `filter[0]: 'x' has no column 'channel'`. A missing
  `filter` key is 422; `[]` or `null` keeps every row, and the `where` key and `importFilter` are removed.
- **One write, two places.** The Pipeline is rewritten through `DatasetFeed.rewritePipeline`, the same path as *Keep
  this up to date*: approval policy 409, the import claimed against a run or delete 409, the save gate (only the
  findings the edit introduces), the reload check, the Pipeline history. The entry is re-read and written inside the
  same claim, after the new Pipeline loads, so a column change made meanwhile is kept; when the write fails, the old
  Pipeline is put back (its audit record stays; that path has no test). A refused edit changes neither.
- **Next files only.** Nothing is re-read: the rows already imported and the earlier arrivals stay as they were. A
  Postgres snapshot is the exception in effect: Refresh now copies the whole table again, so the new filter then
  applies to every row (the card says so).
  Decided for this row: *Replace everything* does not re-read the last file with the new filter. The next run's
  summary carries the new `filter`.
- **Gates.** `canUploadData`, the capability every write of an import's Pipeline needs (the commit and the feed). A
  builder with `canAuthorWorkbench` alone gets 403. Then write root 503, `data.self_import` 403, unknown 404, view-only
  403, not an import Add data wrote for this id 409, and an approval policy over Datasets 409. Listed in
  `ImportCapabilityGuardTest`, `JobWritersTest` and `DecisionRuleWritersTest`.
- **Tests.** `ControlApiImportFilterEditTest` (synthetic rows):
  - an edit and then Add a file: the new filter applies, while the rows and the first arrival stay;
  - clearing it;
  - refusals that change nothing, and an injection-shaped value saved as a literal;
  - 401 and 403 (a builder), then 200 with `canUploadData`.

**Add a file** acts only on the Pipeline Add data wrote for that Dataset (`importPipeline` equal to the id and registered
from `imports/<id>_pipeline.toon`; the registry fields alone are editable, so a forged entry is 409), checks the file
has the import's format and carries the columns the schema reads (column count for delimited text, names otherwise;
an unreadable schema is 409). With `replace` it moves `database/` aside, lands, runs,
and drops the old data on success or restores it on failure; `append` adds rows. **Run now** runs whatever waits in
the inbox. Both answer the run summary with the in-run index and Expectations.

Before either runs, the waiting files are judged by the Dataset's columns (a refused one set aside in `refused/`, named
in `refused`) and the new columns every file the run takes carries are kept (`keptColumns`, `newColumnsReason`). That
is ONE list, `ImportPreRunSteps` (`BASIC-ELT-PROFILE-1` BE-1b, 2026-11-19): a run of the import Pipeline that Add data
did not start (a scheduled poll, `POST /pipelines/{id}/run`) runs the same steps first, as a registered pre-run step,
and holds the Dataset's claim through the run, so its commit steps still compact, follow on and check Expectations
([spec-pipeline-execution.md](../elt/spec-pipeline-execution-dry-run-recovery.md#pre-run-steps--the-other-side-of-the-run)). A scheduled
run of a Dataset Add data holds does not start; the next tick looks again. *Watch this folder* (`PUT
/datasets/{id}/watch`) is such a scheduled run since B1 (2026-11-19): the import Pipeline's own locked `every: 30s`
schedule, not a separate poller (la-core `architecture-data-onboarding.md` §6.20).

**Deleting an import Dataset retires its Pipeline** (`LA-ONBOARD-ORPHAN-PIPELINE-1`, 2026-10-23).
`DELETE /components/dataset/{id}` on an entry with `origin: import` and `importPipeline` equal to its id, whose
`imports/<id>_pipeline.toon` exists, unregisters that Pipeline and removes `imports/<id>_pipeline.toon`,
`imports/<id>_schema.toon` and the Pipeline's config history (`OnboardImport.claimRetirement`); the answer adds
`retiredPipeline: <id>`. The data directory `<data root>/<id>` is **kept** (so the id cannot be imported again until
it is removed; Add data's commit says so with its 409). Before this the Pipeline stayed registered with no Dataset
over it, and an entry re-created under the same id could drive it with Add a file and Run now; now that entry finds
no Pipeline (409). An entry naming another Dataset's Pipeline retires nothing (only the file Add data wrote for THIS
id), an import of that id in flight is 409, and under an approval policy over Pipelines or schemas the delete is
refused 409 (retiring would write around it); a policy over Datasets holds the delete as usual, and the approved
replay retires (tested, `LA-ONBOARD-RETIRE-GAPS-1`). The steps run **before** the registry entry is deleted and are idempotent
(unregister, schema, history, then the Pipeline file LAST - the file a retry finds it by): a failure answers an error and leaves
the entry, so the same DELETE can be retried; deleting the entry first left a Pipeline nothing could retire. Calls recorded
2026-10-24 (assistant-decided under operator delegation, pending confirmation): the data directory stays on delete (removing
data on a config delete is irreversible; to reuse the id, import under another name or remove the folder by hand), and
`POST /spaces` does not refuse a Demo Auth template (only the gallery marks it) - a server with no sign-in creates the sample
Spaces legitimately and the roster is simply unused.

Tests: `inspecto/.../OnboardDraftFormatPropertyTest` (C1 property tests over seeded generated files: the decimal mark
each file was written with, every value read back by the commit's expression, the record path and flattened selectors
of random nested documents, file-name dates in six styles; OF-2 at 200) and `inspecto/.../ControlApiOnboardFilesTest`
(draft evidence and confidence, value shapes, commit, overrides,
Reference, failed commit, 202 and a failed 202, Add a file and Run now, the column route, limits, the Excel
refusal, NDJSON and Parquet, `data.self_import` without and with sign-in, armed 401/403/200; since 2026-10-23 also
the delete retirement, the commit refused under an approval policy over Datasets or schemas with nothing written,
the 202 run read only by its starter or an administrator, and a 202 commit plus a 202 Add a file in a second Space
landing in that Space only; since 2026-11-05 a `,`-decimal file committed under its date-less name with its sums
exact, and a nested JSON export committed with `records_path` and taking a later file by Add a file) and
`la/inspecto-geo-link/.../ControlApiOnboardLinkAnalysisTest` (index BUILT in the run, `/inv/projection` with no
saved view, Entity profile links assessed from `defaultLinkMapping` and not without it, falsified by removing the
fallback in `EntityTypeBindings`).

### Choose a table: a Postgres snapshot (`PostgresSnapshot`; `BASIC-ONBOARD-1` P3, 2026-11-05)

Moved to its own page: **[api-onboarding-postgres-snapshot.md](api-onboarding-postgres-snapshot.md)** — the `/onboard/postgres/test` and `/onboard/postgres` routes, their gates and refusals, how a snapshot reads Postgres, Refresh now and the tests.

### Onboarding counters (`OnboardingRoutes`; `LA-ONBOARD-1` OB-13, 2026-10-22)

A successful commit (inline or 202) records `first_dataset` once per installation in the onboarding counters, after
the undo stack is cleared, so a rolled-back commit counts nothing. The counters themselves - the file in the
server-wide document home, the closed vocabulary, `GET /onboarding/metrics` (`canAdminister`) and
`POST /onboarding/events` (open, rate-limited) - are described in the la-core
[architecture-data-onboarding](../la-core/architecture-data-onboarding.md) §6.11. `POST /spaces` records `space_created` the same way.
