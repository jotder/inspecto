---
type: Feature
title: ELT — user guide — Authoring gap list, enrich, load, ingest options and testing
description: Part 5 of 7 of ELT — user guide: Enrich; Load — sinks, partitioning and route branches; Ingest options and feed contracts; Testing what you author
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
---

# ELT — user guide — Authoring gap list, enrich, load, ingest options and testing

Part 5 of 7 of [ELT — user guide](user-guide-elt.md) · previous: [Transform and map — the Record Transformer](user-guide-elt-record-transformer.md) · next: [Save, pipeline-level surfaces, guided mode and known gaps](user-guide-elt-save-surfaces.md).

## 8. Transform and map — the Record Transformer (continued)

### 8.9 The 2026-09-03 authoring gap list, and what became of it

Confirmed absent 2026-09-03; annotated 2026-09-04 after the two authoring plans shipped (AUTHOR-SCHEMA-1 is CLOSED in
BACKLOG; what survives is AUTHORING-REDESIGN-1):

1. **No live/as-you-type validation or preview for `EXPR`** — **absorbed**: `transform.sql` reports binder errors
   as you type through the DESCRIBE route (§8.2).
2. **No expression-builder UI anywhere** — **closed** by the function catalog grid and CodeMirror (§8.2, §8.4); the
   structured table for filters shipped as (c) (§8.6).
3. **No reference-existence or `on`-column-existence validation before saving a `transform.join`** — **CLOSED
   2026-09-06** (§8.7).
4. **No automatic schema-drift detection** linking a schema rename/retype back to mapping rules — **CLOSED
   2026-09-06**: `SchemaMappingDrift` (inspecto-etl) runs on all three schema save paths (`/config/write`,
   `/config/patch`, `/components/schema/{id}`) and 422s a mapping `from` or COLUMN argument naming a column
   `raw.fields` no longer declares — cell-addressed (`mapping.fields[<name>].from`), no `compatibility: none`
   escape because both halves are one document; `custom` rows are author SQL and exempt.
5. **Mapping/join preview is opt-in and stale-prone**, 6. **no drag-and-drop field mapping**, 8. **two disconnected
   test/preview mechanisms** (drawer-level "Test this Step" vs. canvas-level `RunToHereDialog`, with no
   cross-linking) — **dropped as premature** for a surface that was redesigned.
7. **Silent stale-column fallback in the engine** — **absorbed**: a `transform.sql` binder error names the stale
   column; the `DIRECT`-rule fallback itself is unchanged on the legacy path.

Still true at save time: no `EXPR` syntax check on the legacy path (opaque until DuckDB executes it), and anything
not covered above (a typo'd expression, an out-of-range cast) surfaces only at test-time or run-time.

---

## 9. Enrich

### 9.1 The `enrichment` Step

Authors a **companion** `*_enrich.toon` through `ENRICHMENT_WIRING_ATTRIBUTES`, not `node.config`.
Every save re-registers (`POST /enrichment`) — enrichments do not hot-reload by mtime; a register
failure downgrades to a warning. **Enrichment stays first-class** (operator, 2026-09-06): the retirement narrowed
to nothing after grounding found the one committed fixture does not migrate — ⛔ not a pending rename.

- **Wiring is derived through one shared function**, `enrichmentWiringDefaults`
  (`inspecto/enrichment/enrichment-wiring.ts`): input = the Stage-1 output · output =
  `<base>/data/enriched/<name>` · trigger = `on_pipeline`. Only pipeline-DERIVED facts travel in
  `NodeConfigData.enrichmentHost`.
- 🔴 The Stage-1 store seeds **only when exactly ONE sink declares a `database`** — an invented
  store path reads zero rows everywhere while looking like it worked. ⚠ Quarantine is SINK-category
  but carries only `dir`, so the derivation filters on config, not category.
- ⚠ **A seed is one-shot** — never re-derived as the author edits (a seed moving under an edited
  form clobbers it). A bound companion's file always wins, including a `triggers.on_pipeline`
  naming a different pipeline: silently re-pointing a deployed enrichment is not an edit anyone
  asked for.
- **The two partition keys are engine-REQUIRED with `[]` as the legal "unpartitioned" value**
  (`FieldSpec.required`; `RawConfig.present` is true for `[]` — required means "the grain is
  stated"). The save sets both **explicitly** because `nestKeys` prunes an empty array; JToon
  round-trips `partitions[0]:` back to an empty list (pinned in `ConfigLoaderTest`).
- Speccing a key makes it form-OWNED (`ownedLeaves` derives from the spec table; owned leaves are
  replaced wholesale). An `output.partitions` entry may be the sink's `{column, source}` map —
  hydrate flattens to `column`, save re-marries `source` onto kept columns
  (`remarryPartitionSources`).
- ⚠ **The `list` chips type does NOT compose with `flat-keys.ts`** (which joins arrays to comma
  strings and splits back only for a hardcoded leaf set) — the two partition flat keys are
  re-seeded as real arrays after `flattenBlock`, deliberately inside the dialog. Any future `list`
  spec on a flat-key surface needs the same treatment or a decision to unify the conventions.

### 9.2 The Enrichment screen

Route `/enrichment` (Operations nav group, `inspecto-ui/src/app/modules/admin/enrichment/enrichment.routes.ts`). A
**standard**, single-select [data-table](../../layer/product/spec-ds-data-table.md) of stage-2 jobs; selecting a
job opens a detail panel with tabs: **Runs** / **Lineage** (filter by run id) / **Report** (a date-range rollup with
percentile stats). The runs/lineage rows render in a **pro** data-table; the report is a stat table. Backed by
`EnrichmentService`.

---

## 10. Load — sinks, partitioning and route branches

### 10.1 Partitioning

`<inspecto-schema-partitions-editor>` (`inspecto/schema/`) is **rendered on the Sink pane since 2026-09-04**, which
reads/writes the SAME companion schema toon's `partitions[]` key directly. ⚠ The Parse pane still seeds
`partitions[]` on load and carries it through its `overwrite: true` write, so a Parse Apply over a stale seed can
clobber a Sink edit made meanwhile — BACKLOG. Rows derive Hive segments `{column, source, type}` from schema
fields, rendered where the pane owns the schema toon (`authorsSchema()`). 🔴 The read half is a data-loss fix —
`partitions[]` is top-level in the schema toon and the pane's `overwrite: true` write DROPPED hand-authored blocks
until the pane read them back (`loadSavedSchema`). Legacy `partitionKey:` loads as the synthesized year/month/day
trio. Mixed date sources WARN, never block (the engine degrades catalog bounds). A stored `source` the schema no
longer carries stays listed — visible beats vanished. The smart launcher pre-picks a date field only when exactly
ONE exists; ⚠ native `<select>` in `@for`: bind `[selected]` per option, never `[value]` on the select. Sinks
themselves: [Pipeline spec — sinks](spec-sinks.md).

### 10.2 Route branches — the destination is IDENTITY

`database` is the **branch↔sink join key on both halves of the lift/lower round trip** (edges do
not survive the flat file): lowering stamps each branch with the database of the sink its
`route:<key>` edge feeds and skips a destination-less branch; `sinks:` is keyed by distinct
database; the lift rebuilds pairing from the stamp.

- 🔴 `addRouteBranch` creating its companion sink with an empty config was silent DATA LOSS (branch
  lowered to nothing; save reported success). `branchDestination()` derives it beside the primary
  sink's home (`<primary home>/<branchKey>/database`), falling back to `pipelineScaffold`'s
  `data/<name>`. ⚠ Quarantine sinks are excluded by the `typeof database === 'string'` test.
- ⛔ Do not "simplify" by leaving the sink blank to validate later — that state cannot be saved at
  all and looks identical to any unconfigured Step.
- 🔴 **A round-trip test that configures the artifact under test tests nothing** — the original spec
  set `database` by hand and passed the whole time.
- 🔴 **Single-slot kinds** — *`MULTI_JOIN` DOES NOT EXIST.* Two “too many of a kind”
  constants were live, `MULTI_PARSER` and `MULTI_MAP_CONFIG` (`PipelineEditable.java`) — *five since 2026-09-23,
  with `MULTI_ACQUISITION` / `MULTI_GAP` / `MULTI_MARKER`*, so a second `acquisition`, `gap` or `dedup.marker` now
  refuses by name on both the engine and the SPA lowering; the join/dedup/route/summarize codes were deleted with
  the ordered `steps:` chain and survive only inside a `PipelineCompileException` Javadoc paragraph explaining their
  own removal. (`processing.join` is last-one-wins; `recordDedup`, `routeNode`, `summarizeNode` are last-one-wins,
  silently): the discard behavior is PINNED per slot in `PipelineEditableTest`; inverting it is an operator call
  (BACKLOG §4).
- **Mid-branch `steps:` sub-chains SHIPPED 2026-09-02 (R3)**: a route branch carries `steps[]` in the shared step
  grammar — authorable on the canvas (wire `route:<key>` → step → sink; the lift presents branch chains as ordinary
  flattened nodes, lower writes them back to the branch). Mid-branch kinds = the fork-executable set; `join`,
  windowed `dedup` and nested `route` refuse by name at save. **Insert-into-branch SHIPPED 2026-09-02**
  (MIDBRANCH-UI-1): each branch header offered "add a Step at the start of branch <key>" (→ `insertBranchHead`,
  which keeps the `route:<key>` edge on the route side) and every in-branch card except the branch's tail offered
  insert-after (plain `insertStepAfter`); both open a palette narrowed to `BRANCH_STEP_TYPES` (filter / dedup /
  summarize / sql — the client mirror of `RouteArming.BRANCH_STEP_KINDS`, so it front-runs the 422 rather than
  offering `join`/`route`). ⚠ **The refusal toast is RENDERED from `BRANCH_STEP_TYPES`** (`branchStepTypesLabel()`,
  2026-09-13), never hand-listed — the same discipline the Java authority always had (`RouteArming` renders
  `new TreeSet<>(BRANCH_STEP_KINDS)`), which is exactly why the server side never drifted. Pinned by three specs
  asserting the label names every member of the set. Remove/move inside a branch stay canvas work. Design:
  [`mid-branch-transforms-design.md`](../../../../archived-documents/plans-archive/mid-branch-transforms-design.md);
  backend: [Pipeline spec — model](spec-pipeline-model.md).

**`sql` mid-branch (SQL-BRANCH-1, 2026-09-06).** A `transform.sql` step inside a `route:` branch used to compile and
then refuse at arming, because `RouteArming.BRANCH_STEP_KINDS` held filter/dedup/summarize only. `RowShaper.sql`
reads nothing but its input relation — no reference resolver, no execution context — so the ingest walk runs it
unchanged; `sql` is now in the set (and the UI's `BRANCH_STEP_TYPES`), pinned by `RouteArmingTest`,
`ControlApiRouteArmingTest` and an executed branch in `RouteIngestEndToEndTest`. The author SQL still passes
`SqlGuard` at execution. 🔴 **Trap, found by that test:** the sink partitions by the derived `year`/`month`/`day`
columns the projection slot added, so a `sql` step that NAMES its output columns drops them and the sink refuses the
batch (`partition_by expected to find year`). And `SELECT * REPLACE (…)` is refused too: `SqlGuard`'s lexical
allow-list bans the word `replace` because it guards `CREATE OR REPLACE`. The idiom that works is
`SELECT *, <expr> AS <new_col> FROM input` — add, don't rename; reshaping a column in place is the Record
Transformer's job on the trunk. Both hold mid-branch and on the trunk alike.

---

## 11. Ingest options and feed contracts

### 11.1 Content refusal — `processing.refusal: restricted_quarantine`

This is opt-in per Pipeline and **off** by default (added 2026-09-30, `RefusalQuarantine`). With it on, a file can
be refused whole in three ways:

- the Pipeline's own mapping raises DuckDB `error('INGEST_REFUSE:<CODE>')`;
- the platform card scan finds a card-number candidate in a raw cell (`processing.refusal_scan: card_number`);
- its name or header line holds a card-number candidate.

- **What triggers it.** Only the first `SQLException` in the cause chain counts, and its message must be exactly
  `Invalid Input Error: ` followed by a code matching `^INGEST_REFUSE:[A-Z][A-Z_]{0,63}$`. A conversion or cast
  error never triggers it, and neither does a code that merely appears somewhere in a message. Data content
  cannot reach it: a strict `CAST` over a value that spells the code is a `Conversion Error`, so it fails the batch
  as it always has.
- **The platform card scan.** `processing.refusal_scan: card_number` scans every raw cell except the
  `processing.refusal_scan_exempt` columns (default: none), before anything is written. The exact candidate
  shapes, the brand/length list and the measured false-trip rates are in [Spaces §3.5.1](../config/plan-spaces.md).
  - The scan **fails closed**: an error while scanning restricts the file as `INGEST_REFUSE:SCAN_FAILED`.
  - An exempt list covering every raw field is refused at load, and an unknown exempt name is warned.
  - `RefusalConfigAudit` wraps every control-plane Pipeline write (`/config/write`, `/config/patch`, graph save,
    settings, history restore, rename). A write that changes `refusal`, `refusal_scan`, `refusal_scan_exempt` or
    `refusal_retention_days` emits AUDIT `pipeline.refusal.changed` with before / after.
  - ⚠ A false trip moves a whole file. An operator who knows a column carries no card data (an order id, an IBAN,
    a phone number) exempts it, and that column is then not scanned at all.
- **What happens to the file.** It is **moved, never deleted**, to the restricted store
  `<data root>/.restricted/<pipeline>/` (`RefusalQuarantine.dir`; the data root is the Space's registered one, else
  the nearest `data/` ancestor of `dirs.quarantine`; with neither, the refusal fails closed). The file gets a generated
  `refused-<CODE>-<ms>-<n>.<ext>` name, because the original name may itself be the refused value.
  - 🔴 **Why it is outside every `dirs:` entry (round 5, 2026-09-30).** The sealed ingest connection
    (`SEC-INGEST-EXPR-EXTERNAL-ACCESS-1`) allowlists the Pipeline's `dirs:` for DuckDB `allowed_directories`, which
    matches by **prefix**. So a store under `dirs.quarantine` — its first home, `<quarantine>/.restricted/` — was
    readable by any `fn: custom` expression (`read_text('<quarantine>/.restricted/*')`).
  - Three checks keep it out:
    - The store sits beside the Pipeline dirs, never under them.
    - `PathJail.readAllowlistRefusal` refuses an allowlisted dir that is inside, or directly holds, a `.restricted`
      store, for the ingest and the enrichment seal alike.
    - The ingest seal and `restrict` both refuse (fail closed) when the store would fall under an allowlisted dir,
      for example `dirs.quarantine` = the data root. The enrichment seal also refuses an allowlist covering
      `<data root>/.restricted`.
  - Round 6 closed two gaps:
    - **The Space data root itself is refused.** This applies to every seal allowlist and to the write/import
      data-home gate (`refuseDataHome`), whether or not a store exists yet. Otherwise a connection sealed before
      another Pipeline's first refusal could read the file that refusal then restricts. The engine registers each
      Space's data root (`PathJail.registerDataRoot`), and `-Ddata.dir` and hosted `<space>/data` count too.
    - **Nested stores are refused.** Stores this process created are registered (`registerRestrictedStore`), and
      a `.restricted` one level down (a nested `data/` fallback) is refused too.
  - Undoing an audited import change is audited as `pipeline.refusal.reverted`.
  - `RefusalQuarantineTest.aMappingExpressionCannotReadTheRestrictedStore` proves it: after one file is restricted,
    a mapping that `read_text`s the store fails its batch with a permission error, and no row carries the content.
  - The directory is not the inbox, so it is never re-polled.
  - `BackupTask.secret` skips `.restricted`, so no backup carries it.
  - The quarantine listing (`FileStatusStore.quarantine`), the errors-file route and `RecordReplay` skip it.
  - `DataRef.requireUnder` refuses any data reference that addresses a `.restricted` segment, or whose read root
    contains one (DuckDB's `**` glob descends into dot-directories). That covers Datasets, Expectations and
    `collector.dataset`. The table browser and `/db/query` apply the same rule.
  - **Retention is mandatory (operator decision 2026-10-04, PCI: no stored card number).**
    `processing.refusal_retention_days` defaults to 7 when unset and must be 1..30. Outside that range,
    `ConfigSafetyValidator` refuses the save (422) and `PipelineConfig` refuses the load. A restricted file older
    than the window is **deleted** by `RefusalQuarantine.sweepExpired`. That runs with each poll cycle's
    housekeeping (`CollectorProcessor.ingest`, next to `cleanupStaleMarkers`, at most once a minute per store) and
    after each refusal.
    - Each deletion is audited as `ingest.refused.retention` with the stored name, size, sha256, reason code and
      `retention_days`. The content is never recorded.
    - The audit is emitted *before* the delete, so a crash can duplicate the record but never lose it. A failed
      delete leaves the file in place and the next sweep retries it.
    - The sweep is path-jailed. It deletes only a regular file (never a link, never a sub-directory) directly in
      this Pipeline's store, whose real parent is the store and whose name the store generated. Files in another
      Pipeline's store, in the ordinary quarantine or in backup are never deleted.
    - Replay never relies on a restricted file. `RecordReplay` reads only the inbox, backup and the ordinary
      quarantine tree (it skips `.restricted`), and the store lives outside all three.
- **What is recorded.** Only the reason code: the member row becomes `QUARANTINED_RESTRICTED`, with the generated
  `filename`, the `error` set to the code and a blank `logical_name`; one WARN line; one AUDIT event,
  `ingest.refused` in the `security` category, with the pipeline, the code, the Consignment and the stored name.
  Exception text, values and the original name are never recorded.
- **Every lane.**
  - Single-file streaming: the card scan runs first, then a refusal raised in the transform is caught.
  - `chunkedIngest`: the whole file is scanned before any chunk is written, and an author refusal in a later chunk
    rolls the earlier chunks back.
  - Union multi-member batches and the Java path: each member is scanned, and its transform probed, alone before
    it joins, so only the refused file is restricted and its batch-mates land.
- **File name and header line.** With the mode on, a file whose name or header line holds a card-number candidate
  is refused before any lane reads it (`CARD_NUMBER_IN_FILE_NAME` / `CARD_NUMBER_IN_HEADER`). The check uses the
  same brand/length regex as the SQL scan (`CardNumbers.IIN_REGEX`).
- ✅ **Failure text never stores a value (2026-10-03, was `INGEST-FAILURE-TEXT-QUOTES-VALUE-1`).** Engine error
  text quotes the offending cell (`Could not convert string '…'`, strptime's `"…"`), echoes it under a caret
  line, or repeats the CSV line (`Original Line: …`). `FailureText.scrub` (inspecto-etl) replaces every quoted
  literal (greedy to the last same quote on the line, since engines print an embedded quote unescaped), every
  caret-pointed line and every `Original Line:` tail with a salted fingerprint — generically, never by searching
  for the value. A Binder / Catalog / Parser error (authored SQL, which data never reaches) is kept verbatim
  unless the same text also carries a data-shaped error. ⚠ The price elsewhere: a quoted identifier in a data
  error is fingerprinted too. It runs once in `ConsignmentIngestor.process` before the batch ledger, the retry
  record and the event, on every member's error in the file ledger, and in the CSV / union / generation lanes'
  failure log lines (which no longer log the stack trace). The fingerprint is HMAC-SHA256 under a 32-byte random
  per-Space salt at `<space>/.inspecto/value-fingerprint.salt` (Space = nearest ancestor of the status dir holding
  `config/`). Pinned by `FailureTextTest` (six real DuckDB error shapes) and `RefusalQuarantineTest`, which scans
  the whole Space byte-wise.
- ⚠ **Not covered:** the Collector's discovery logs and its acquisition ledger see the inbox name before the
  check runs.
- It replaced the 2026-09-30 purge seam (`PURGED_REFUSED`). Data content could trigger that seam, it applied to
  every Pipeline, and it deleted irreversibly. Proof: `RefusalQuarantineTest`, and the payment pack's
  `PaymentFraudTemplateGoldenTest` ([Spaces §3.5.1](../config/plan-spaces.md)).

### 11.2 A reference build — `postmed_xdr` (2026-09-22)

A post-mediated telecom xDR feed (pipe-delimited, one file per collection interval) built from scratch in
`spaces/demo/config/postmed/` and verified against the written Parquet, not the exit code (⚠ exit 0 and a log ending
at *"Planned N batch(es)"* say nothing about the outcome — read `status/*_batches_*.csv`). What it pins down: all 18
declared types survive to Parquet (`BIGINT` for byte counters — never `DOUBLE`, lossy above 2^53);
`csv_settings.null_strings` turns `NULL`/`N/A` and an empty field into SQL NULL, so an unrated record's price stays
NULL rather than `0.00`; omitting `date_formats`/`timestamp_formats` is what the validator warns about
(`TRY_STRPTIME` → NULL). ⚠ The Hive partition columns are not uniformly typed — `year` reads back `BIGINT`,
`month`/`day` `VARCHAR`, so a predicate on `month` needs a string literal. ⚠ The CLI does no space discovery: a
`schema_file:` needs `-Dassist.safety.roots=<space>`. Its findings, all closed: `INGEST-REJECT-ACCOUNTING-1` (the
batch row now splits `rejected_files`/`rejected_rows` and `total_input_rows` counts what arrived),
`INGEST-ERRORS-CSV-PER-COLUMN-1` (one errors row per bad line, raw line once), and
`DUPLICATE-CHECK-GRAIN-UNSTATED-1` (§5). The workbench-side findings (`GRAPH-SAVE-REFORMATS-CONFIG-1`,
`UI-CREATED-PIPELINE-FLAT-HOME-1`, `READONLY-LENS-PALETTE-ENABLED-1`) are in this guide (§6.3) and the
[Pipeline spec — model](spec-pipeline-execution.md). Reproduce:
`python spaces/demo/data/samples/gen-postmed-xdr.py --seed-inbox`, then run the CLI over the Pipeline; copy
`PMXDR_20260904_001.psv.defect` in as `.psv` for the fault run. Build record:
[postmed-xdr-pipeline-build](../../../../archived-documents/plans-archive/postmed-xdr-pipeline-build.md).

### 11.3 Daily feeds — the template, the export contract and delivery checks

*(`LA-DAILY-INGEST-1` T2, T3, T7, T8.)* The solution pack that ships this shape:
[la-daily-feed](../../solution-pack/la-daily-feed/architecture-la-daily-feed.md).

**Template.** `spaces/_templates/la-daily-feed/` is the reusable shape of a daily feed for Link Analysis: Pipeline
`daily_xdr` + schema (`partitionKey: EVENT_DATE`, the UTC calendar date of `START_AT`), a poll Collector
(`connector: local`, `include: XDR_*.csv`, gap detection on `XDR_{yyyyMMdd}`), `.processed` duplicate markers, the
quarantine directories, and the Dataset `daily_xdr_dataset`; two synthetic sample days in `data/samples`. Modelled on
`postmed_xdr` (§11.2). It is a Space Template, so it is applied by `POST /spaces` with `template`, and
`ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies` loads and validates every `_templates/*` entry.
Per feed, rename the feed, columns and file pattern; keep `partitionKey` on the event-date column.
⚠ The trigger is **poll discovery only**. A cron trigger for the Collector is not a Pipeline setting today (cron exists
on Jobs and on pipeline entry nodes); the template does not claim one.

**Export contract (the INPUT guarantee).** Whoever produces the daily file (a mediation drop, or a database owner who
exports a daily CSV or Parquet file with their own scheduler and query, D-ING5) guarantees, per feed:

| Item | Guarantee |
|---|---|
| File name | one pattern per feed with the day in it, e.g. `XDR_yyyyMMdd.csv` or, with parts, `XDR_yyyyMMdd_partNNNN.csv`; the name of a file is STABLE (a feed rule); the Collector `include` glob and `gap_detection.sequence` use the same pattern |
| Grain | exactly ONE UTC event date per file; no file spans two dates. MANY files (parts) per event date are allowed, each with its own stable name |
| Parts | part numbers are ZERO-PADDED (`part0001`; lexical sorting); parts of one day are delivered together, or completeness is signalled. The platform keeps a per-day manifest of what landed (T8, below) |
| Event-date column | one DATE column (`EVENT_DATE`) equal to the UTC date of the event timestamp; it is the `partitionKey`. The day in the file name and every row's event date agree |
| Time zone | the event timestamp column is UTC wall clock with no offset in the text; the schema declares `timezone: UTC` |
| Delimiter, encoding, header | comma (or one declared delimiter), UTF-8, one header row whose names match the schema |
| Null strings | the declared `null_strings` only (template: `NULL`, `N/A`); an empty field is also null |
| Corrections | a corrected file is re-delivered as a WHOLE file under the SAME name (it overwrites cleanly), never a partial or a delta. A correction under a NEW name doubles every row of the date: the feed owner's error, not fixed in the platform, detected by the T8 duplicate-delivery check |
| Completeness | no partial days: a file is written complete (temp name, then rename) so the Collector never sees half a day |
| Late files | allowed: a day may arrive after later days; it lands in its own event-date partition |
| Reference feeds | the same stable-name rule: ONE stable file name, or `load: upsert` with a key. A dated Reference file name makes `load: replace` read both days (T6 finding) |
| Parquet export | same name, grain, column and zone rules; the schema uses `parsing.frontend: parquet` |

What the platform does when a day is delivered twice (replace vs append of the partition, the file-level duplicate
marker versus a changed file, late-data effect on the Link Analysis Index) is **not part of this contract: see T4**
(`LA-DAILY-INGEST-1`). Until T4 lands, treat a re-delivered day as unverified.

**Backfill (T7, 2026-10-06).** A 30-90 day initial load is the same door as a daily one: drop the range's daily files into
the Collector's inbox (each still one event date per file) and let the Pipeline land them; then run ONE `full` index build
over the landed range (the `la.index.build` Job with `allow_full: true`, or `POST /inv/index` mode `full`). The days are
parsed in parallel by the Pipeline and the full build is one parallel DuckDB sort, so there are no per-day index builds to
merge. `IndexAppendTest.aThirtyDayBackfillAnswersEveryReadLikeTheDayByDayRunAcrossThreeCompactions` pins that the backfill
answers every read like the day-by-day run (first day full, append per day, compact at the delta cap) and that a backfill
missing one day does not. While the range lands, each committed batch emits `pipeline.commit` (`job.dataset.produced` is
emitted only by the `sql.template` Job); a Job run that finds a build in flight WAITS and re-plans (at most 6 waits, 2 s
backoff doubling to 60 s, within `timeout_seconds`, every attempt audited), so the daily trigger need not be paused
(operator, 2026-10-06). That default bound (about 122 s) does not outlast a FULL build at 10^8 rows (852-929 s measured), so
the Job takes `wait_seconds`: unset keeps the six-wait bound, `0` never waits, any other value is a TIME budget (same
backoff, last sleep trimmed) clamped to `timeout_seconds`; a negative or non-numeric value fails the Run. Read from the saved
config only, like every other Job param (no per-run override).

**Delivery checks (T8, operator 2026-10-06).** Declare the feed's parts on the Collector:
`gap_detection.file_template: "XDR_{yyyyMMdd}_part{seq}*"` + `seq_scope: PER_BUCKET` (`seq_scope` is required when the
template holds `{seq}` and refused when it does not; the same keys the completeness KPI uses —
[acquisition spec Phase D](spec-acquisition.md#phase-d--gap-detection)). A one-file-per-day feed declares a date-only template,
`file_template: "XDR_{yyyyMMdd}.csv"` with no `seq_scope` (operator, 2026-10-06): one expected file per day, its part number
recorded as 0, a missing day = one missing file. ⚠ A literal suffix means a RENAMED correction (`XDR_20260902_fix.csv`)
does not match the series: it lands, the day's rows double, and nothing is signalled (found live 2026-10-14). The shipped
`la-daily-feed` template therefore ends in `*` (`"XDR_{yyyyMMdd}*"`), so a second file of a day is recorded as part 0 again
and signalled `RENAMED_CORRECTION`. Then (a) every committed part is recorded in `<markers>/day-manifest.tsv` (day, part,
rows, bytes); a part re-sent under the same name, the same part under a NEW name (the day's rows are now doubled) or a
new part for a day after a later day landed is signalled as `collector.delivery.anomaly` (WARN; payload `kind`, `day`,
`file`, `previousFile`); (b) each poll cycle checks the window from the first known day to the last COMPLETE UTC day, so a
missing day (including the last one) or a missing part is one `SEQUENCE_GAP` alert. **Data as of:** name the Dataset's
event-date column (`eventDate: EVENT_DATE` in the Dataset config); `GET /datasets/{id}/freshness` and the Dataset editor's
chip then show the instant the data is covered through (a DATE through its end), and an Alert Rule with `maximumAge: 2d` +
`measure: max(EVENT_DATE)` fires when the newest event is older than that, even while files keep arriving.

**Corrected same-name day (owner call closed 2026-10-14).** Under the default `duplicate_check` (path markers) a file
re-delivered under the SAME name with changed bytes is skipped (the `.processed` marker wins; only a WARN +
`DUPLICATE_SKIPPED` signal, once per file version). A feed that wants the correction to land states
`collector.duplicate: {mode: checksum, algorithm: SHA256, on_change: reprocess}` (a Phase C key that already existed;
no new key, no spec change): the fingerprint ledger replaces the marker, an UNCHANGED re-delivery (same hash, new
mtime) is still skipped silently (a counter, no batch, no signal), and a CHANGED one is re-ingested, its partition is
replaced (one file, no stale rows) and the day manifest signals one `REDELIVERED`, which the Data strip's corrected-day
banner reads. The shipped `la-daily-feed` template and the `la-showcase` / demo `mule_transfers` feed carry it.
Pinned by `LaDailyIngestAcceptanceTest#aCorrectedSameNameDayReplacesItsPartitionAndAnUnchangedOneIsSkipped` (drives
`CollectorProcessor.run`; the same test goes red on `mode: path`) and `LaDailyFeedTemplateRulesTest`. ⚠ Limits: a
date DROPPED by the correction still leaves the old file for that date (roadmap T4 b2); a `#` comment line above a block
in a pipeline TOON silently truncates the file (the `la-showcase` mule feed lost its `collector:` block that way until
this change).

---

## 12. Testing what you author

- **Run to here** — `POST /pipelines/authored/{id}/run?to=` (`PipelineGraphRoutes.testRun`,
  `canAuthorWorkbench`; backed by `PipelineTestRun`/`PipelineExecutor.dryRun`/`PipelineDryRun`): parses picked inbox
  files through the real ingest path into a scratch root (zero production side effects), previewing the graph over
  parsed rows. `to=` bounds the walk to the **ancestor closure** of the chosen Step; unknown `to=` is 400. Opened via
  `PipelineEditorComponent.openRunToHere()` / `RunToHereDialog`. This runs the actual compiled SQL, so an expression
  typo or a missing join reference WOULD surface here. Details:
  [Pipeline spec — execution](spec-pipeline-execution.md).
- **Test this Step** (drawer inline preview) — `POST /components/{transform|sink}/preview` runs an
  **inline** config over the tab's parsed rows: exactly what the operator is mid-writing.
  🔴 The gate is the Step's own TYPE (`testFamily()`), ⛔ never `data.bindKind` — `bindKind` is null
  for everything that reaches the drawer, which is exactly why a predecessor affordance was
  unreachable dead UI whose specs passed by constructing the dialog directly. 🔴 The body goes
  through the same `buildConfiguredNode` the save path uses — a second merge would drift and test
  a config that never ships. ⚠ The transform arm sends the Step's `type` INSIDE `config` or the
  route 422s. Rows arrive as plain `sampleRows` (the thread belongs to the TAB).
- ⛔ **An inline-config Step of other kinds cannot be tested by any existing backend surface** —
  the component `/test` routes resolve through `ComponentStore` and 404 on absence; do not "finish"
  this without a deliberate decision.
- **Test a bound component** — "Test <component>…" opens `ComponentFormDialog` (the one surface
  that collects a sample); `TESTABLE_KINDS` = transform/grammar/sink.
- **Dry-run** — offers **"Use the captured sample (N rows)"** from the tab's thread. ⚠ The panel is
  mounted once and outlives tab switches: its outcome is stamped with the pipeline id it came from
  and `result`/`error` are computed against the current one. ⛔ Never replace that with an effect
  that clears on id change (effects flush on Angular's schedule and can wipe a fresh result).
  ✅ **A test run's own rows seed the thread** (`WB-12`, 2026-09-22), so the captured sample is one
  click after *Run to here*. 🔴 Fixing it exposed a dead seam: `RunToHereDialog`'s Close carried no
  value, so `afterClosed()` always emitted `undefined` and every consumer of the run result — including
  the node-tested marking — was unreachable. Row `DRYRUN-SEEDS-AFTER-PARSE-1`.
- **Save-time vs test-time.** Present at save time: the schema backward-compatibility diff (§7.2); mapping-CSV shape
  validation; the `SchemaMappingDrift` check; `transform.summarize`'s `measures`/`group_by` validators; join reference
  checks (§8.7); Angular reactive-form required/pattern checks. Anything else (a typo'd expression on the legacy path,
  a join key absent from the reference dataset, an out-of-range cast) surfaces only at test-time or full run-time.

---

## Changes

- 2026-09-09: single-slot kinds corrected — `MULTI_JOIN` does not exist.
