---
type: Capability
title: Ingestion & parsing (ING)
description: Stage-1 — how inbox files become Consignments and committed Parquet: the poll and planner, the batch-atomic commit, the two ingest strategies and their union/generation modes, the parse frontends behind one parsing block (ten tokens, eight formats), the Parser-plugin SPI and the wrap-SPI, Grammars and Grammar Templates, the Unpack stage and its archive verdict, schema casting and quarantine, Expectations, and the source time zone. The requirement of record for the ING area, its specification, its decisions, and what was refused.
resource: inspecto-engine/src/main/java/com/gamma/inspector, inspecto-etl/src/main/java/com/gamma/etl, inspecto-engine/src/main/java/com/gamma/parse
tags: [ing, capability, ingestion, parsing, consignment, commit, grammar, parser-plugin, streaming-file-ingester, unpack, archive, quarantine, expectation, asn1, xml, xlsx, source-timezone]
timestamp: 2026-09-08T00:00:00Z
---

# Ingestion & parsing — capability spec (`ING`)

> **What this page is.** The single entry point for the ING capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Eighth of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §2, §5, §6-B — binding). A **Consignment** is a set of files ingested
> and processed together as one unit of work; ⛔ never *Batch* for the entity (the wire spellings `batch_id` /
> `batches` stay by decision). A **Parser** reads one format into rows; a **Grammar** is the authored options
> that tell it how, living **inline** on the parse Step; a **Grammar Template** is a Grammar saved to the
> `grammar` kind as a **copy source** — ⛔ never "linked", "shared" or "referenced". An **Archive** is a
> container file; an **Entry** is one inner file — ⛔ never *member* (a Member is a file in a Consignment);
> **Unpack** is the Collector-level stage that turns Archives and compressed streams into plain files, ⛔ not a
> parser and not bare *decompression*. An **Expectation** is the data-quality rule engine (§4 *Rules*); a
> **Segment** is one record kind a plugin ingester emits (⚠ undefined in the glossary — §5).

## 1. Purpose & scope

ING is **the road from a file in the inbox to rows a Dataset can read**: how files are grouped into a
Consignment, how each is decoded whatever its format, how records are typed and where the ones that fail go,
and how the whole group lands atomically or not at all. Its defining properties are that **every format
converges on one DuckDB backend** — native reads where DuckDB can, Java decoders wrapping the same core where it
cannot — and that **a failed Consignment leaves no trace of having been processed**, so retry is implicit.

**In scope:** the poll cycle's ingest half (`CollectorProcessor.ingest`, `MultiCollectorProcessor`),
`ConsignmentPlanner` and `collector.consignment`; `ConsignmentIngestor`, the two `ConsignmentIngestStrategy`
implementations, union and generation modes, the crash-safe commit order (`CommitLog`, `MarkerManager`,
`PartitionWriter` staging and reveal); the `parsing:` block and its **10**<!--count:parsing-frontend-tokens--> frontend tokens; `ParserPlugin` and
`Parsers`; `StreamingFileIngester` / `RecordSink` / `DuckDbRecordSink` (the wrap-SPI); the delimited, fixed-width,
JSON, text/regex, XLSX, Parquet, ASN.1 and XML lanes; Grammars, Grammar Templates and the `grammar` kind; the
Unpack stage, its caps, verdicts and ledger; schema casting, cast-failure measurement, quarantine reasons and
reject rows; Expectations; the source time zone at parse; the Parse pane, grammar drawer and previews.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| Reaching the remote system, deciding a file is new and stable, landing it in the inbox | `ACQ` — ING starts where `acquire` hands to `ingest` |
| The Schema and Mapping *registry*, `SchemaFieldTypes` as vocabulary, `fields[]` vs `rules[]` | `MET` — ING *applies* the cast and reports its failures |
| The Record Transformer (`transform.sql`), Steps, the graph lane, `route:`, the at-rest chain | `PIP` — ING owns the flat ingest lane up to the landed store |
| What a landed store means to a read, `DatasetRelation`, the store-layout contract | `DAT` |
| Decision Rules and Alert Rules (the other two rule engines) | `PIP` / `INC` — ING owns Expectations because `REQUIREMENTS.md` homes them here |
| The signal ledger, gauges as an observability surface | `OPS` |

## 2. Requirements of record

Six requirements from `REQUIREMENTS.md` §3.2 (that section was stripped to an index on 2026-09-09 — this file is their only home now), all recorded shipped. **Two describe a narrower build than
exists, one describes a broader one, and one has no UI at all.** ⚠ **`EDITIONS.md`'s feature × edition
matrix is authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `ING-1` | Stage-1 M..N multiplexer: parse → transform → partition → commit, batch-atomic | Must | ✅ SHIPPED | All |
| `ING-2` | Format frontends: delimited grammar, fixed-width (text + binary), plugin `StreamingFileIngester` SPI | Must | ✅ SHIPPED — ⚠ **understated**: the built set is **eight formats** (§3.3) | All |
| `ING-3` | Compressed input streaming (gzip / bz2 / zip) | Must | ✅ SHIPPED — **superseded in shape** by the Unpack stage (2026-08-23): streams *and* Archives, at the Collector, with caps and a verdict (§3.5) | All |
| `ING-4` | Schema casting + reject routing (quarantine: unreadable / mismatch / sink-flush fail) | Must | ✅ SHIPPED | All |
| `ING-5` | Unified `parsing:` block + JSON/NDJSON + text/regex frontends | Must | ✅ SHIPPED 2026-07-07 — ⚠ "LDIF block-records stay PROPOSED" is stale: blank-line record splitting is **live** via `text_regex.record_split` (2026-08-28) | All |
| `ING-6` | **Expectation** engine: data-quality rules against a Schema | Must | ✅ **server SHIPPED — no UI.** `/expectations*` CRUD + evaluate exist; **no file in the SPA mentions Expectations**; evaluation is a COUNT over **at-rest Parquet**, not at ingest (§3.7) | All |

**Corrections this table makes to its predecessor and the concept pages**, each verified against source:

- **`ING-2` names three frontends; `PipelineConfigParser.FRONTENDS` (`:1590-1591`) accepts **10**<!--count:parsing-frontend-tokens--> tokens for
  eight formats** — `delimited`, `fixedwidth`/`fixed_width`, `json`, `text_regex`, `xlsx`/`excel`, `parquet`,
  `asn1`, `plugin` — and `BuiltinParsers.IDS` (`:29`) serves **6**<!--count:builtin-parsers--> DuckDB-native built-ins including **`parquet`**,
  which appears on **no** board row, no `step-catalog.md` row and no `EDITIONS.md` `SP-PRS-*` row. The
  documentation states the count as three, five, six, seven and ten in six places; the code says ten tokens,
  eight formats, six built-ins.
- **`ING-6` is a Must with no product surface.** `ExpectationRoutes` (`inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:47-54`)
  serves list / evaluate / evaluate-one / create / update / delete; a case-insensitive search of every `.ts`
  under `inspecto-ui/src/app` finds **zero** occurrences of "expectation". The `<inspecto-query-panel>`
  condition builder that `queries.md` says Expectations reuse exists — for Queries and Datasets. Same shape as
  the `API`, `DAT` and `MET` client-half findings.
- **`ING-5`'s LDIF note is stale**, and `parsing-options-reference.md` contradicts itself on it: §6.6 says
  `[LIVE via text_regex]` with `record_split: blank_line`, its decision matrix still says `[PROPOSED]`.
  Corrected in both places with this spec.
- **`EDITIONS.md` `ING-05` promises "nested archives"**; `UnpackLimits.depth` defaults to **1** and any other
  value is **refused by name** — a `.tar.gz` inside a `.zip` is extracted as an opaque Entry and quarantines.
  Corrected with this spec.
- **`ingest-wrap-spi.md` names the mode selector `processing.streaming.generation_threshold_bytes`**; the
  parser reads **`large_file_bytes`** (`PipelineConfigParser.java:243`) and no other page or code uses the
  first name. Corrected with this spec.
- **`transforms-seams.md` still describes `ConsignmentIngestStrategy` / `CsvIngestStrategy` and a `DATA_RULES`
  registry**; the types are `ConsignmentIngestStrategy` / `CsvIngestStrategy` / `StreamingPluginIngestStrategy`
  (2026-08-31) and `DATA_RULES` does not exist (`configuration.md` corrected it 2026-09-08). Corrected with
  this spec.

**One standing note no status token can carry:** ⚠ **A Consignment that fails leaves nothing behind on
purpose.** Markers and fingerprints are written **last** in the commit order, so a FAILED Consignment has no
"already processed" record and its files are rediscovered on the next poll — retry is implicit, bounded since
X1 by `-Dingest.retry.max` (exhaustion quarantines with reason `retry_exhausted`). Read every status and every
ledger with that in mind: a file's own outcome and its Consignment's outcome are **two facts** (a `COMMITTED`
file inside a `FAILED` Consignment is normal), and collapsing them loses the question reprocessing asks.

## 3. Specification

### 3.1 The Consignment: from inbox to a unit of work

`CollectorProcessor.ingest(cfg, onCommit)` (`inspecto-engine/src/main/java/com/gamma/inspector/CollectorProcessor.java:95`)
scans the local inbox — a remote-fetched file, once landed, is discovered exactly like a locally-pushed one —
expands Archives through **Unpack** (§3.5, at `:117`, *before* planning), then **`ConsignmentPlanner`**
(`inspecto-etl/src/main/java/com/gamma/etl/ConsignmentPlanner.java`) cuts the candidate list into Consignments
bounded by **`collector.consignment: {max_files, max_bytes, order}`** — the canonical home since 2026-09-02
(CONSIGNMENT-HOME-1; `processing.batch.*` is dual-read and healed on save, nothing writes it), with `order`
**`mtime`** (arrival) by default, `name` as the opt-in for feeds with unreliable stamps, and anything else
refused at parse. `processing.intake.max_files_per_cycle` caps the fleet per cycle (`0` = unbounded). Each
Consignment runs on a virtual thread bounded by `Semaphore(processing.threads)`; `MultiCollectorProcessor`
runs many pipelines in one JVM under `Semaphore(sources.max)` — total worker pressure is
`sources.max × processing.threads × duckdb_threads`. The planner's audit label is the selected schema's
`raw.name`, or the literal `schema` for a plugin pipeline (a `null` there threw until 2026-09-06, so no plugin
pipeline could plan a Consignment through the poll cycle).

### 3.2 The ingest strategies, the two modes, and the commit

**`ConsignmentIngestor`** (`…/inspector/ConsignmentIngestor.java:43`, ex `BatchProcessor` — ⛔ not
`ConsignmentProcessor`, which is the post-sync SPI) is a stateless coordinator: pick a
**`ConsignmentIngestStrategy`** — `CsvIngestStrategy` for DuckDB-native frontends, `StreamingPluginIngestStrategy`
for the wrap-SPI — run `ingest()` → `IngestOutcome`, then the path-agnostic **`commit()`** in crash-safe order:
DuckLake register → manifest → backup originals → **markers → ledger last**, then `writeAudit()`. It never
throws for a Consignment failure; audit is always written.

The plugin strategy picks a **mode per Consignment by member size, with no extra I/O**: **union mode**
(`UnionModeIngester`) when every member is below **`processing.streaming.large_file_bytes`** (default 256 MB)
— per-member raw tables `UNION ALL`-ed through one transform / write / lineage pass; **generation mode**
(`GenerationModeIngester`) when the largest member is at or above it — each member streams in bounded
generations of **`flush_records`** rows (default 5 000 000), each generation transformed, written and dropped,
so peak heap stays one generation regardless of file size (`<stem>_gNNNNN_out.*`, a valid Hive layout).

**The write is staged and revealed.** `PartitionWriter` (`inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java:158-208`)
`COPY`s into a staging directory, then reveals by atomic rename — `COPY … PARTITION_BY` for a keyed schema,
a single-file `COPY` for an **unkeyed** one (E1: the `year=1900/month=01/day=01` sentinel bucket is retired; an
unkeyed segment writes a **flat** store). `CommitLog` records committed ids; `MarkerManager` writes the
per-file marker only after commit and cleans stale ones; `OVERWRITE_OR_IGNORE` makes a crash mid-archive
idempotent for already-committed Entries. Since 2026-08-11 **record dedup is not a Stage-1 concern** —
`processing.dedup` is refused at `prepare()`; the Step runs at rest. Since 2026-08-26 a `route:` pipeline
**executes on the poll-driven path** (branch-aware ingest) and `-Dingest.lane=auto|graph|flat` selects the lane
(2026-09-02; the flat path's deletion is Row 15, release-gated). The landed store is what `DAT` reads.

### 3.3 One `parsing:` block, ten tokens, eight formats, three frontends

`PipelineConfigParser.parseParsing` (`:927`) reads the **`parsing:`** block into a private `Grammar` record
(the split is by *state*, not size — `toon-config.md`); `parsing.delimited` and the legacy
`processing.csv_settings` are aliases and **`parsing:` wins**; ⚠ an unknown `delimited.*` key is **not**
rejected at load (`mergeParsing` flattens with no key allow-list, only its scalar siblings are allow-listed).
`FRONTENDS` (`:1590-1591`) = `delimited · fixedwidth | fixed_width · json · text_regex · xlsx | excel · parquet ·
asn1 · plugin`. Three engines sit behind them:

| Frontend | Engine | Notes |
|---|---|---|
| **delimited** | DuckDB `read_csv`; the Java `CsvIngester` when `skip_junk_lines` / `skip_tail_*` force it | all-VARCHAR read, typing at transform; `strict_mode` is a three-way select (engine default on / on / off); prefix + regex row filters; boundary pre-scan window `max(skip_junk_lines, 1024)` |
| **fixedwidth** (text) | `read_text` + SQL substrings | binary fixed-length records are the plugin `FixedWidthRecordIngester` |
| **json** / NDJSON | `read_json` | `records_path` dotted selector (2026-07-31); `format: array` + `ignore_errors` refused at load; `[*]` refused |
| **text_regex** | `read_text` + SQL regex | `record_split: blank_line | <literal>` (2026-08-28) — this is what makes **LDIF block records live** |
| **xlsx** / excel | DuckDB `excel` extension, **three-layer fail-closed load**; `all_varchar` stamped by ingest | `read_xlsx` `columns` does not exist on 1.5.2.1 — refused, not faked |
| **parquet** | native | a built-in with **no board row anywhere** |
| **asn1** | `Asn1ParserPlugin` → `Asn1RecordIngester` (`asn-parser/asn-decoders`) | served 2026-07-31; grammar optional for preview, required for ingest; `frontend: asn1` synthesises the plugin and refuses a co-present `parsing.plugin` |
| **plugin** | any `StreamingFileIngester` FQCN | `XmlRecordIngester` (2026-08-30) is the tree → segments bridge |

**`ParserPlugin`** (`com.gamma.parse`, `inspecto-engine`, `@PublicApi(since = "4.0.0")`) is the self-describing
contract both engines serve: `grammarSchema` (the served `FieldSpec` vocabulary the options form renders),
preview, and `ingesterClass()`; **preview and ingest are deliberately separate capabilities**, and
`Parsers.ingestable()` is a **display flag** nothing in config, validation or dispatch consults. `Parsers` is a
collect-all `ServiceLoader` registry (⛔ not `SpiSlot`); a duplicate id fails startup; a built-in id cannot be
overridden because the built-ins' preview *is* the ingest engine. `GET /parsers` and the preview routes are
compute-only. **No shipped parser is preview-only any more** (XML gained its ingester 2026-08-30).

### 3.4 The wrap-SPI: Java-parsed records into the DuckDB core

`StreamingFileIngester` (`inspecto-etl`, `@PublicApi`; the only plugin SPI since `FileIngester` was removed in
3.11.0) — `ingest(File, RecordSink, srcId, PipelineConfig)`, one implementation per format, chosen **by config**
(`parsing.plugin.ingester` / legacy `processing.ingester`, an FQCN instantiated reflectively — deliberately not
`ServiceLoader`, because the config names one class). `RecordSink` (`@PublicApi(since = "3.10.0")`) —
`define / emit / reject / junk`, keyed by **segment**. The one sink is **`DuckDbRecordSink`**: 10 000-row buffers
flushed through the JDBC **`DuckDBAppender`** (~75× faster than row inserts — ≈520 K rows/s vs ≈6.9 K; never
staged CSV, which is a fallback for *out-of-process* producers only), then the *identical* downstream as native
ingest. **A plugin ingester with no `segments` is refused at config load**; a segment key = the record kind;
`raw.fields[].selector` is a dotted path; an undeclared record is `sink.junk()`; a trailing derived
`EVENT_TYPE` carries the segment key. **Preview and ingest share one walker** (`XmlRecordReader`,
`Asn1` alike) — a selector authored against the preview's labels must resolve identically at load, or a column
silently goes NULL; a selector must name a leaf that occurs **once** (a container or a repeated sibling yields
NULL — "a lie dressed as a decoded value" was refused). Any ASN.1 / XML parse error fails the **whole file**
(`QUARANTINED_UNREADABLE`), never a prefix. `partitions[]` has **two readers by posture**: the ingest schema's
is fail-closed, the sink node's degrades (E2 unification refused).

### 3.5 Unpack — Archives and streams become files before planning

`com.gamma.etl.unpack` (`UnpackStage`, `UnpackLimits`, `UnpackOrigins`, `UnpackLedger`, `Decompressors`,
`BuiltinDecompressors`, `ArchiveDecompressorPlugin`), at the Collector, **before `ConsignmentPlanner`** — the
whole trick: an Entry becomes an ordinary File from birth, so the Consignment model is untouched and an Archive
can outlive one Consignment (500 Entries at `max_files: 100` plan five). Kinds: 1→1 streams (`.gz`, `.bz2`,
`.Z`) and 1→N Archives (`.zip`, `.tar`, `.tar.gz`); **xz and zstd have no plugin** (a dependency sign-off).
**Caps, fail-closed** (`ConfigSpecs`): `processing.unpack.max_entries` (10 000), `max_entry_bytes`,
`max_total_bytes`, `max_ratio` (the bomb cap), `depth` **= 1, refused otherwise**; the zip-slip jail
(`ArchiveDecompressorPlugin.java:157-158`, tested with a re-packed archive); `data_extensions` published, an
empty list honoured as a choice. Identity is **extension-insensitive** (a collision is not a bug — the allow-list
is the remedy). **The archive verdict is a Run-level fact**, one row per Archive per Run in the `unpack` ledger
(`UnpackLedger.COLUMNS`, declared once): `UNPACKED · UNPACKED_PARTIAL · UNREADABLE · EMPTY`; 🔴
**`UNPACKED_PARTIAL` commits** — reporting, never a gate; `EMPTY` and `UNREADABLE` share one code path and stay
distinct by `entriesFound()`, never by string-matching. Lineage and `filename_column` record the **Entry
name** (`good.csv`), never the workspace's `00001_` prefix and never `archive!entry` (that is the manifest and
quarantine address); the per-file ledger's `logical_name` is the Archive's identity. The two decompression
vocabularies were unified on `Compression.INLINE_SUFFIXES` with a **one-way** drift guard (the SPI is wider on
purpose).

### 3.6 Casting, measuring, quarantining

Typing happens at transform: `SchemaFieldTypes.castSql` casts each mapped column to its declared, fail-closed
type (`MET`); a failed `TRY_CAST` / `TRY_STRPTIME` yields NULL and **keeps the row**, and
`DataTransformer.countCastFailures` measures those over the *same compiled expressions* — ⚠ `-1` means **not
measured** and writes a **blank** cell; a path that cannot measure never claims a clean Consignment; `EXPR`
rules are excluded by design. **Files** that fail move to `<quarantine>/<poll-subpath>/<reason>/<file>` with
reasons `field_mismatch · unreadable · empty · corrupt_download · retry_exhausted` — the tree is evidence
organised by why; **parse rejects** travel beside their input as `errors/<base>_errors.csv`, served by
`GET /runs/{name}/errors?file=` (bounded, `truncated` reports the true total) and surfaced as "View the
rejected rows". The status ledger carries a row per member (`filename, status, parsed_rows, error_rows,
error, consignment_id`), the batches ledger the roll-up (⚠ its header has **five mirrors**; readers parse by
header name, so a stale mirror hides a column silently), the lineage ledger the output ↔ input join. Live
progress (`IngestProgress`, `StepProgress`) is **in-memory by decision** — a wedged step reads as a stale
`startedAt`. The Run Detail's tabs are Batches / Files / Lineage / **Quarantine** / Commits.

### 3.7 Expectations

`com.gamma.expectation` (`inspecto`): an `expectation` component (a condition tree in the shared query-types
grammar) targets a pipeline or job output; **`ExpectationEvaluator`** counts violating records with a
server-built `COUNT` over the target's **at-rest Parquet** in a DuckDB sandbox (unsealed — it reads Parquet by
absolute path); a FAILED check opens a deduped `expectation:<name>` Incident through `IncidentAccess` and emits
`EXPECTATION_FAILED` → notifications. Routes (`ExpectationRoutes.java:47-54`): `GET /expectations`,
`POST /expectations/evaluate`, `POST /expectations/{name}/evaluate`, `POST | PUT | DELETE` gated
`canAuthorWorkbench`. ⚠ **This is not ingest-time validation** — `REQUIREMENTS.md` homes it under ING and
words it "validating records against a Schema"; the glossary homes it under Rules; the build is an at-rest
count. ⚠ **No UI exists** (§2).

### 3.8 The source time zone at parse

`SourceZones` (`inspecto-etl/src/main/java/com/gamma/etl/SourceZones.java`) is the one home: precedence
`raw.fields[].timezone_column > raw.fields[].timezone > parsing.source_timezone > none`; compiled as
`timezone('UTC', timezone(Z, <naive-parse>))` to naive UTC at `SchemaFieldTypes.castSql`,
`TransformCompiler.dateExpr` and `concatDt`; `TIMESTAMPTZ` with no zone source and `%z` / `%Z` formats are
**refused at config load**; `FILENAME_DATE` and `DATE` are zone-exempt (a date has no instant to shift). The
DuckDB session's own zone is the host's and is a different rule (`DAT` §3.7).

### 3.9 Authoring: the Parse pane, the grammar drawer, Grammar Templates

The **sectioned Parse pane** (`pipeline-parse-definition.component.ts`, redesign shipped `d012f721`
2026-09-04) serves every parse subtype by `frontend`; the **grammar drawer** (`<inspecto-grammar-editor>`,
sample-as-thread: choose file → view → type → options → test → table / tree) is the one Grammar surface for the
five DuckDB-native formats, with `ParserTreeComponent` for hierarchical previews; binary fixed-width keeps the
`GrammarEditorDialog` by operator decision (its geometry lives in `ingester_config`). Previews call
`POST /config/preview/parsing` and `/config/preview/schema` (`ConfigPreviewRoutes`). Field **tiers**
(`required | optional | advanced`, D13) drive disclosure — ⚠ every `tier: 'required'` field still ships
`required: false` validators (an open observation question). ⚠ **The lone hard default in the entire
parser/grammar surface is `transform.route`'s `mode: 'case'`** — so "required" means *shown in the top
disclosure tier*, never *validator-enforced*. The pre-agreed rule that will decide it is in
`okf/frontend/features/grammar-config.md` §"The D13 field-tier session". **A Grammar lives inline on its Step**; a
**Grammar Template** is a copy source (operator, 2026-08-15, reversing the 2026-08-04 `use: grammar/<id>`
binding — that form stays read-supported, never authored); "Save as template…" was replaced by a **Grammar
CSV** round-trip (2026-08-19). Parse **does not drop columns** (D8, 2026-09-04) — exclusion is
`transform.sql`'s — at the cost that `SchemaCompatibility` gates the schema backward. A Parse Step is three
things — Grammar · the Schemas it emits · other properties — and the drawer is their one home (PARSE-HOME-1,
2026-09-06). The Onboarding Parsing stage **no longer exists** (P6-e); the editor is the guided surface.

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### The lane and the commit

| Date | Decision | Why |
|---|---|---|
| 2026-06-28 | **Three frontends, one DuckDB backend** | every format converges on mapping + transform + partition + lineage |
| 3.10.0 → 3.11.0 | `StreamingFileIngester` + `RecordSink` are the plugin SPI; whole-file `FileIngester` **removed** | one contract; the framework picks union vs generation by size |
| — | **Markers and fingerprints go last**; a FAILED Consignment leaves no record | retry is implicit and safe |
| 2026-08-11 | `processing.dedup` **leaves Stage-1**; refused at `prepare()`; the Step runs at rest | dedup over a landed store is decidable; mid-stream is not |
| 2026-08-12 (operator) | Consignment `order` defaults **`mtime`**; `name` opt-in; else refused | arrival order is what a feed means; stamps lie only sometimes |
| 2026-08-19 (E1) | An unkeyed segment writes a **flat store**; the `year=1900` sentinel retired | a sentinel partition is a lie about the data |
| 2026-08-19 (E2) | The two `partitions[]` readers **stay separate** — ingest fail-closed, sink degrades | a wrong layout at ingest corrupts the store; a good sink write must not fail over an advisory |
| 2026-08-19 (E3) | The Appender path is **the** wrap; staged CSV is for out-of-process producers only | built, faster, no second disk write |
| 2026-08-26 → 2026-09-06 | `route:` executes on the poll path; `mode: clone` **arms** (reversing the first refusal) | the graph lane admits non-route pipelines; committed branches must be visible |
| 2026-09-01 | Orphan `output_store` audit **default-on** (`-Djobs.orphan.audit=false` to kill) | a landed store nobody reads is a silent loss |
| 2026-09-02 (CONSIGNMENT-HOME-1) | `processing.batch.*` → **`collector.consignment.*`**, dual-read, healed on save | the planner runs in the poll cycle before any sink exists |
| 2026-09-06 | A plugin pipeline can **plan** a Consignment (audit label `schema`) | a `null` label had thrown for every plugin pipeline |
| 2026-09-07 | **Intrinsic and inherited status never collapse** into one enum | a `COMMITTED` file in a `FAILED` Consignment is exactly what reprocessing asks about |

### Parsing and plugins

| Date | Decision | Why |
|---|---|---|
| 2026-07-07 | One **`parsing:`** block; `csv_settings` / `processing.ingester` are aliases and `parsing:` wins | one home per concept |
| 2026-07-31 | **ASN.1 served**: grammar optional for preview, required for ingest; hex-first; INTEGER 42 is never `"*"` | a structural dump is honest; a faked decode is not |
| 2026-07-31 | `frontend: asn1` **synthesises** `parsing.plugin` and refuses a co-present one | one config key must win |
| 2026-07-31 | JSON `records_path` dotted; `format: array` + `ignore_errors` **refused at load**; `[*]` refused | each would load looking honoured and fail every Consignment, or return zero rows silently |
| 2026-08-01 | The `asn-parser` reactor split resolved; corpus tests **opt-in and data-gated** — *skipped is the expected state* | the corpus is customer data (DATA-GOV-1) |
| 2026-08-16 (operator) | Binary fixed-width **keeps `GrammarEditorDialog`** | its geometry is `ingester_config`; the slice table would govern nothing |
| 2026-08-19 (X1–X3) | XLSX shipped on the `excel` extension with a **three-layer fail-closed load**; `read_xlsx columns` **refused, not faked** | the parameter does not exist on 1.5.2.1 |
| 2026-08-28 | `text_regex.record_split` verified live — **LDIF block records are live** | blank-line splitting is the whole LDIF need |
| 2026-08-30 | `XmlRecordIngester`: **preview and ingest share one walker**; a selector names a leaf that occurs once; XML keys under `ingester_config` | a second walker would resolve authored selectors to NULL silently |
| — | **A built-in parser id cannot be overridden**; a duplicate id fails startup | the built-ins' preview *is* the ingest engine |
| 2026-09-04/06 | `strict_mode` is a **three-way select** (engine default on / on / off), not `default: true` | a placeholder writes nothing; a default must equal the engine's |

### Unpack

| Date | Decision | Why |
|---|---|---|
| 2026-08-23 (operator, `d6cd55b7`) | **Unpack sits at the Collector, before planning**; the Consignment model is unchanged | an Entry is a File from birth; "update the Consignment" needs a merge the immutable manifest has no seam for |
| 2026-08-26 (operator) | Four verdicts; **`UNPACKED_PARTIAL` commits**; `EMPTY` ≠ `UNREADABLE` by `entriesFound()` | a bad Entry must not discard 499 good ones; an operator must hear "empty" apart from "locked" |
| 2026-08-26 → ratified 2026-09-06 | Lineage records the **Entry name**, never `archive!entry` nor the index prefix | a data column must not carry a key consumers parse |
| 2026-08-26 | The verdict is **Run-level**, one ledger row per Archive per Run | an Archive can outlive a Consignment |
| — | `depth` **= 1**; nested archives refused by name | recursion needs a bound nobody asked for |
| 2026-08-28 | One decompression vocabulary (`Compression.INLINE_SUFFIXES`), **one-way** drift guard | the SPI is deliberately wider; reverse containment would forbid `.Z` / `.tar` |
| — | Identity is extension-insensitive; the collision is **not a bug** | the allow-list is the remedy, a two-tier scheme would break the actual requirement |

### Authoring

| Date | Decision | Why |
|---|---|---|
| 2026-08-04 (user) | Store "both": inline by default, extractable to a `grammar` component; *Grammar* canonical, "parser config" banned | one word, one store |
| 2026-08-15 (operator) | **Grammar Templates are copies, never bindings** — reverses the `use: grammar/<id>` contract; the form stays read-supported | every other *Template* in the glossary is a copy source; a live binding made an edit reach Steps nobody opened |
| 2026-08-19 | **Grammar CSV** round-trip replaces "Save as template…"; `GrammarTemplateDialog` deleted | a file is the portable form |
| 2026-09-04 (operator, D1–D4) | The **sectioned Parse pane**; plain-language labels; mockup rows with no engine key **not built**; grounded defaults vs help-only | a UI knob must name an engine key |
| 2026-09-04 (D8) | **Parse does not drop columns**; Transform owns exclusion | one owner for the projection; the schema then gates backward |
| 2026-09-06 (PARSE-HOME-1) | A Parse Step is Grammar · Schemas · properties; the drawer is the one home; a dangling binding opens it | three surfaces for one Step confused every author |
| 2026-08-16 (operator) | ⛔ No single drawer over schema + mapping + table | it would span three Step types |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| Unpack (11): **xz / zstd codecs**; `.Z` has no round-trip test; multi-part archives; the UI cannot author `data_extensions[0]:`; a stale `META-INF/services` header | `BACKLOG.md` §3 *Onboarding, Catalog, Parsing* | a dependency sign-off, not a build |
| Unpack (5)(8)(10) — partial never fails; nested refused; crash mid-archive re-ingests committed Entries by design | `BACKLOG.md` §6 | LEAVE |
| **ASN.1 grammar as a stored module reference**, not pasted text; a drop-in `plugins/` jar directory | `BACKLOG.md` §3 *Parsing (Stage-1)* | the prerequisite for a per-vendor transform home |
| **P4 test mapping on a generic `parser` node** | `BACKLOG.md` §3 *Authoring* | re-scope before building |
| **D13 parser field tiers** — the onboarding-observation session; `tier: 'required'` ships `required: false`. The pre-agreed analysis rule and the deliverable that closes it are in `okf/frontend/features/grammar-config.md`; the kit itself is archived but still runnable | `BACKLOG.md` §2 | externally gated |
| **Row 15** — delete the legacy flat read path (`-Dingest.lane` is the precondition) | `BACKLOG.md` §2 | release-gated |
| D-8 **XLSX export** (zero groundwork, no spreadsheet library) | `BACKLOG.md` §3 | |
| Column metadata editing on the Transform pane (Parse D2) | `BACKLOG.md` §3 (e) | needs a backend home |
| `SP-PRS-09…17` planned parsers (key-value, YAML, ASN.1 PER/XER, EBCDIC, protobuf, Avro, PCAP, Grok, syslog) | `EDITIONS.md` board | no BACKLOG row |
| Delimited robustness knobs (a)(c) | archived board snapshot 2026-09-06 only | no live row |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-NOPROOF-1`, `SPEC-GLOSSARY-1`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** Expectations (`ING-6`, a Must) **ADOPT — build the UI**; the Must stands as written → `BACKLOG.md` §3 `EXPECTATIONS-UI-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **Expectations have no UI** | zero "expectation" hits under `inspecto-ui/src/app`; routes exist | `ING-6` is a Must reachable only by `curl`; the `<inspecto-query-panel>` it was meant to reuse exists |
| **`parquet` is a built-in frontend on no board** | `BuiltinParsers.IDS`; `FRONTENDS` | shipped, untested-as-a-row, invisible to `EDITIONS.md` and `step-catalog.md` |
| **The frontend count is stated six ways** (3 / 5 / 6 / 7 / 9 / 10) across five pages and the user guide | §2 | FOUR sets share one noun: **10**<!--count:parsing-frontend-tokens--> `parsing.frontend` tokens (eight formats once the two aliases fold), **6**<!--count:builtin-parsers--> DuckDB-native built-ins, **7**<!--count:parser-node-types--> `parser.*` node types, and three byte→row *mechanisms* (a prose taxonomy, not a count in code). The three derivable sets are marked and derived by `tools/check-doc-counts.mjs` (2026-09-09); the mechanism sense is written as *mechanisms*, never *frontends* |
| **`Segment` has no glossary entry** | `GLOSSARY.md` | the load-bearing plugin-ingest word is undefined in the binding vocabulary |
| **`ingestable` is a display flag with a stale reading in `onboarding.md`** ("XML today") | `parser-plugins.md` vs `onboarding.md:210-212` | corrected with this spec |
| **The "Batch" names survive in `transforms-seams.md`, `duckdb.md`, `FEATURE_INVENTORY.md`** | `ConsignmentIngestStrategy`, `CsvIngestStrategy` | 8 days after the rename; corrected in the seams page with this spec, the rest are a sweep |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 Extending the ingester SPI to emit files; "updating" a Consignment with expanded Entries — REFUSED (Unpack design)

Unpack produces *files*, the wrap-SPI produces *records* — an unpacked Entry may itself go to a
`StreamingFileIngester`. The manifest is immutable and `ManifestStore` has no merge; expanding one step
earlier is free. Forcing an Archive's Entries into one Consignment would void the `max_files` / `max_bytes`
memory bound.

### 6.2 Failing the Consignment on a partial archive; nested archives; `archive!entry` in a data column — REFUSED

One bad Entry must not discard 499 good ones; `depth` other than 1 is refused by name; `filename_column`
carries the Entry name because a key consumers must parse does not belong in data.

### 6.3 One partition grammar (E2); staged CSV from inside a plugin — REFUSED (2026-08-19)

Folding either `partitions[]` reader into the other imports a hard failure into the degrade lane or softens
the fail-closed one. The Appender is built and faster; never demote to staging CSV in-process.

### 6.4 Config that would load looking honoured and then fail — REFUSED at load

`format: array` + `ignore_errors`; `records_path [*]`; `compression: zip | tar | Z` in config (only
`auto | gzip | zstd | none`); a plugin with no `segments`; `TIMESTAMPTZ` without a zone; `%z` formats; a
`root_type` without a grammar. Each is a caller error, not a silent fallback.

### 6.5 Knobs nothing has needed — REFUSED

ASN.1 trailer length and `lengthOffset` / `lengthSize` / endianness (no corpus file uses them);
`decimal_separator` at the dialect (ingest reads VARCHAR); newline style, `sample_size`, `all_varchar`,
`auto_type_candidates`, `names`, `union_by_name`, `buffer_size`, file globs in the grammar (auto-detected,
all-VARCHAR by design, or the Collector's); `read_xlsx columns` (does not exist — refused, not faked); a
`timezone_column` editor (~418 zone names per row).

### 6.6 Per-step Signals and periodic progress persistence — REFUSED (consignment chain)

A Signal is a durable write on the claim-holding thread and triggers `on_signal` jobs while the claim is held
(the re-entrancy class `PROJECT_NOTES.md` §6 forbids); periodic persistence is stale by construction and
leaves crash-orphaned rows. Live gauges stay in memory; the ledgers answer the post-crash question.

### 6.7 Overriding a built-in parser id; a per-entry Signal in Unpack; reusing the batch semaphore for Unpack — REFUSED

The built-ins' preview is the ingest engine, so an override lets preview diverge from production; Unpack
runs before planning, so the Consignment semaphore cannot bound it.

### 6.8 A breaking engine slice removing `use: grammar/` (D3) — REFUSED

It costs nothing, is pinned, and a hand-authored file may still carry it; the resolution was to stop
*authoring* the binding, not to build a write route that removes it.

### 6.9 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| Whole-file `FileIngester` (since 1.3.0) | `StreamingFileIngester` (3.11.0 — a deliberate within-major exception) | `api-stability.md` |
| `processing.csv_settings` / `processing.ingester`; the standalone `*.grammar.toon` file | the unified `parsing:` block (2026-07-07); Grammars inline on the Step; the `grammar` kind as a template | `parsing-options-reference.md` — ⚠ `parsing-grammar.md` still describes the 4.1 shape |
| `processing.batch.*` | `collector.consignment.*` (2026-09-02) | `GLOSSARY.md` §13 |
| `Batch` / `BatchProcessor` / `BatchIngestStrategy` / `CsvBatchStrategy` | `Consignment` / `ConsignmentIngestor` / `ConsignmentIngestStrategy` / `CsvIngestStrategy` (2026-08-31) | `GLOSSARY.md` §13; `transforms-seams.md` corrected with this spec |
| Record dedup in Stage-1 | the at-rest `dedup` Step (2026-08-11) | `stage1-architecture.md` |
| The `year=1900` sentinel bucket | a flat unpartitioned store (E1) | `ingest-wrap-spi.md` |
| `use: grammar/<id>` as a live binding (2026-08-04) | Grammar Templates as copies (2026-08-15); the form read-only | `GLOSSARY.md` §5 |
| "P3d retires `GrammarEditorDialog` entirely" | binary fixed-width and the unmappable generic `parser` keep it (operator, 2026-08-16); the dangling case moved to the drawer 2026-09-06 | `grammar-config.md` |
| The 4-tab delimited surface (U1–U5); `<inspecto-property-rows>`; `GrammarTemplateDialog`; the 9-type hardcoded `parser-types.ts`; the mock `/components/grammar/preview`; the Onboarding Parsing stage | the sectioned pane, one schema-form per section, Grammar CSV, the served `ParserPlugin` contract, the editor as the guided surface | `grammar-config.md`, `parser-plugins.md` |
| XML preview-only; `xml.` grammar root | `XmlRecordIngester`; keys under `ingester_config` (2026-08-30) | `parser-plugins.md` |
| The node-count engagement predicate for the graph lane | branch count (`dataFedSinkCount`) — "refuted by its own falsification test" | `branch-aware-ingest.md` |
| "An absolute refusal of `steps:` was the original design and was WRONG" | `steps[]` runs at rest via a `type: pipeline` job | `stage1-architecture.md` |
| `DATA_RULES` / `TransformCompiler` as the mapping lane | the Record Transformer (2026-09-05) | `configuration.md`; `transforms-seams.md` corrected with this spec |
| `ingest-wrap-spi.md`'s `generation_threshold_bytes`; `EDITIONS.md`'s "nested archives"; `parsing-options-reference.md`'s LDIF `[PROPOSED]` row; `onboarding.md`'s "XML today" preview-only; `USER_GUIDE.md`'s "nine format types" and `Run ⊇ Batch ⊇ File` | corrected with this spec | those files |
| `parsing-grammar.md`'s 4.1 account (`*.grammar.toon`, `csv_settings`) | superseded 2026-07-07; the page is flagged with this spec, a rewrite is owed | `parsing-grammar.md` |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| The planner, the two strategies, union / generation, the commit order | `docs/okf/backend/engine/ingestion.md` (`Concept`) | `inspecto-engine/src/main/java/com/gamma/inspector` | §3.1–§3.2 |
| The wrap-SPI, `DuckDbRecordSink`, the two `partitions[]` readers, the contract tests | `docs/okf/backend/engine/ingest-wrap-spi.md` (`Seam`) | `inspecto-etl` | §3.4 |
| The plugin ingester reference: SPI contract, execution modes, segment schema, `FixedWidthRecordIngester` | `docs/okf/backend/engine/plugins.md` (`Reference`) | — | §3.4 |
| `ParserPlugin`, `Parsers`, ASN.1, the XML bridge, the reactor split | `docs/okf/backend/engine/parser-plugins.md` (`Concept`) | `inspecto-engine/src/main/java/com/gamma/parse` | §3.3–§3.4 |
| Every `parsing:` key, per frontend, with the refusals | `docs/okf/backend/config/parsing-options-reference.md` (`Reference`) | — | §3.3 |
| The three frontends and the live CSV knobs — ⚠ carries the 4.1 `*.grammar.toon` account | `docs/okf/backend/engine/parsing-grammar.md` (`Concept`) | — | flagged with this spec |
| Unpack — placement, DATA, the verdict, the ledger, identity, traps | `docs/okf/backend/engine/unpack-stage.md` (`Concept`) | `inspecto-etl/src/main/java/com/gamma/etl/unpack` | §3.5 |
| Status flow — gauges, ledgers, quarantine, drill-down, the two-facts rule | `docs/okf/backend/engine/consignment-status-flow.md` (`Concept`) | — | §3.6 |
| Stage-1 module map, dedup's exit, the at-rest chain, orphan audit | `docs/okf/backend/engine/stage1-architecture.md` (`Architecture`) | — | §3.2 |
| `route:` on the poll path, the lane flag, the engagement predicate | `docs/okf/backend/engine/branch-aware-ingest.md` (`Concept`) | — | §3.2 |
| The run budget, the broker, the fetch lane | `docs/okf/backend/engine/consignment-concurrency.md` (`Concept`) | — | bounds |
| The Appender, threads, the source zone | `docs/okf/backend/engine/duckdb.md` (`Concept`) | `DuckDbUtil.java` | §3.4, §3.8 |
| The strategy seams — ⚠ still names `ConsignmentIngestStrategy` and `DATA_RULES` | `docs/okf/backend/engine/transforms-seams.md` (`Concept`) | — | corrected with this spec |
| The Parse pane, the drawer, templates as copies, D8–D10 | `docs/okf/frontend/features/grammar-config.md` (`Feature`) · `schema-mapping-authoring.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/pipelines` | §3.9 |
| Onboarding as the editor's checklist; the parse flow | `docs/okf/frontend/features/onboarding.md` (`Feature`) | — | §3.9 |
| ⚠ **No page owns Expectations as a subject** (`com.gamma.expectation`); the only accounts are `REQUIREMENTS.md`'s cell and `decision-rules.md`'s condition-tree paragraph | *(gap)* | `inspecto/src/main/java/com/gamma/expectation` | §3.7 |

---

## 8. Verification

### 8.1 The lane and the commit — `inspecto-engine` · `inspecto-etl`

`ConsignmentIngestorPluginTest` (the end-to-end wrap pin: a toy `StreamingFileIngester` into a partitioned store
and — `unkeyedSegmentWritesAFlatStoreWithLineage` — a flat one, asserting rows, layout and lineage),
`FinalizeSourceConcurrencyTest` (8 Consignments finalising through the shared DB stores; the same-file marker
race with exactly one loser), `ConsignmentAuditWriterTest` (contiguous ledger blocks under concurrency),
`ConsignmentStatusAccessTest`, `PartitionWriterTest` · `PartitionWriterFormatTest`, `PipelineLiftTest`,
`PipelineJobRunnerTest` (the store-layout contract), the `ConsignmentPlanner` and `MarkerManager` tests.

### 8.2 Parsing lanes and plugins

`DelimitedGrammarTest` · `FixedWidthTest` (`inspecto-etl`; ⚠ both **skip** when their fixture pipeline is
absent — two of the reactor's five standing skips), `ParsersTest` (registry, duplicate-id failure, the
display-flag stub), `XmlRecordIngesterTest` (`previewLabelsAreTheSelectorsThatResolve` — the one-walker
rule), `Asn1ParserPlugin` tests, `RealGrammarsTest` · `ParityCheckTest` (`asn-parser`; **skip** unless
`-Dasn.corpus.tests=true` and the customer corpus is on disk — DATA-GOV-1; the other three standing skips),
`ExcelExtension` tests, `SourceZones` tests, `PipelineConfigParser` tests (`FRONTENDS`, alias precedence,
`records_path`, refusals at load).

### 8.3 Unpack — `inspecto-etl/src/test/java/com/gamma/etl/unpack/`

`UnpackStage`, `UnpackLimits`, `UnpackLedger`, `UnpackOrigins` tests; `CompressionTest.inlineVocabularyIsOwnedByPlugins`
(the one-way drift guard); the zip-slip jail pinned with a **re-packed** archive; the `EMPTY` vs `UNREADABLE`
`entriesFound()` split.

### 8.4 Casting, quarantine, expectations — `inspecto` · `inspecto-etl`

`DataTransformer` cast-failure tests (`-1` = blank), `QuarantineManager` tests, `ControlApiExpectationTest`
(`inspecto-ops` — the routes, the deduped Incident, `EXPECTATION_FAILED`), `ExpectationEvaluator` tests.

### 8.5 UI specs — vitest (~30 on the parse surface)

`pipeline-parse-definition.component.spec.ts`, `grammar-editor.component.spec.ts`, `grammar-editor.dialog.spec.ts`,
`grammar-csv.spec.ts`, `parsing-attributes.spec.ts`, `parsing-sniff.spec.ts`, `parser-tree.component.spec.ts`,
`pipeline-collection-definition.component.spec.ts` (consignment + unpack attributes), `run-detail.component.spec.ts`,
`batch-detail.dialog.spec.ts`, plus the pipeline-editor suite.

### 8.6 Committed artifacts

`inspecto/examples/02-parsing/` — nine format-lane examples (`fixedwidth`, `json-frontend`, `text-regex-frontend`,
`xlsx-frontend`, `xml-plugin-frontend`, `pipe-delimited`, `no-header`, `compressed-gzip`, `asn1-frontend`), each a
`pipeline.toon` (+ `grammar.toon` for fixed-width); `07-steps`; 76 `.toon` files under `examples/` in all.
⚠ **No committed `parquet` example** and no committed `expectation` component.

### 8.7 Guards

`CompressionTest`'s one-way vocabulary guard; `ParsersTest`'s duplicate-id and display-flag pins; the
`step-types` and `node-attributes` contract pairs (parse verbs and attributes); `check-vocabulary` keeps *Batch*
(the entity), *member* (for an Entry) and *parser config* out of the docs; `SchemaFieldTypes` at load.

### 8.8 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **Five reactor tests skip by default** — `RealGrammarsTest` ×2, `ParityCheckTest` (corpus-gated), `DelimitedGrammarTest`, `FixedWidthTest` (fixture-gated) | `assumeTrue` in each; no current page names all five |
| **No UI test of Expectations** — no UI | §2 |
| **`.Z` has no round-trip test**; xz / zstd have no codec to test | `BACKLOG.md` §3 |
| **No `parquet` frontend example or board row** | §8.6 |
| **The `tier: 'required'` fields ship `required: false`** — the D13 observation is un-run | `BACKLOG.md` §2 |
| **The flat lane's deletion (Row 15) has no test because it is not done** | `branch-aware-ingest.md` |
