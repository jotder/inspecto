---
type: Concept
title: Ingest, Consignments and parsing — how a landed file becomes Table rows
description: How files are ingested into the DuckDB core — the one emit-based ingestion SPI and its one sink, union/generation execution modes, segment schemas and the two partition-declaration contracts, the shipped ingesters and the plugin author workflow, Consignment planning/commit/failover and graceful stop, write-path identity and output_per_member, branch-aware route: ingest; Consignment addressing (event time, the Selector, revisions, the Watermark) and concurrency (the four-layer hierarchy, priority shares, named pools, hot-apply); and parsing (three parse mechanisms over one DuckDB backend, the read_csv reference, the parsing block and per-format profiles, the ParserPlugin framework, XML and ASN.1 ingest, drop-in parser jars, Decode Profiles, BER hostile-input handling).
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java, platform/inspecto-engine/src/main/java/com/gamma/consignment, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java, platform/inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java, platform/inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
---

# Ingest, Consignments and parsing

**What this covers.** Everything between a file landing in the inbox and its rows sitting in partitioned
Table files. Part I is the ingest step itself: the plugin contract, execution modes, segment schemas,
output layout, how files are grouped into Consignments and committed, failover, output naming and `route:`
branches. Part II is how readers address Consignment output (Selector, revisions, Watermark) and how many
Consignments run at once. Part III is parsing: how the bytes of each format become rows, and the parser
plugin framework. Getting files to the inbox is [spec-acquisition.md](spec-acquisition.md); what a
Pipeline does with the rows afterwards is [spec-pipeline.md](spec-pipeline.md).

**Contents**

- [Part I — Ingest](#part-i--ingest)
  - [Plugin ingester](#plugin-ingester)
  - [Execution modes — the framework picks by file size](#execution-modes--the-framework-picks-by-file-size)
  - [3. Segment schemas and the partition declaration](#3-segment-schemas-and-the-partition-declaration)
  - [Pipeline config (plugin)](#pipeline-config-plugin)
  - [5. Output layout](#5-output-layout)
  - [6. Shipped ingesters](#6-shipped-ingesters)
  - [7. Plugin author workflow](#7-plugin-author-workflow)
  - [8. Consignment coordination](#8-consignment-coordination)
  - [9. Failover and graceful stop](#9-failover-and-graceful-stop)
  - [10. Write-path identity and output naming](#10-write-path-identity-and-output-naming)
  - [11. Branch-aware ingest — `route:` on the poll-driven path](#11-branch-aware-ingest--route-on-the-poll-driven-path)
  - [12. Contract tests](#12-contract-tests)
- [Part II — Consignments](#part-ii--consignments)
  - [Part A — Addressing](#part-a--addressing)
  - [A.1 The one rule everything follows](#a1-the-one-rule-everything-follows)
  - [A.2 Event time at write](#a2-event-time-at-write)
  - [A.3 The Selector](#a3-the-selector)
  - [A.4 Revisions — how a recompute stays safe](#a4-revisions--how-a-recompute-stays-safe)
  - [A.5 The Watermark — completeness](#a5-the-watermark--completeness)
  - [A.6 Three storage invariants that are cheap now and unfixable later](#a6-three-storage-invariants-that-are-cheap-now-and-unfixable-later)
  - [Part B — Concurrency (the four-layer hierarchy)](#part-b--concurrency-the-four-layer-hierarchy)
  - [B.1 The grain rule everything follows](#b1-the-grain-rule-everything-follows)
  - [B.2 `ConcurrencyBroker` — one monitor, three counters, weighted grants](#b2-concurrencybroker--one-monitor-three-counters-weighted-grants)
  - [B.2a Named execution pools — admission only (2026-09-24, `DUCKLE-C10-ADMISSION-POOLS-1`)](#b2a-named-execution-pools--admission-only-2026-09-24-duckle-c10-admission-pools-1)
  - [B.3 ⛔ The run budget is NOT redundant with the broker](#b3--the-run-budget-is-not-redundant-with-the-broker)
  - [B.4 Hot-apply: what changes live, and how a shrink behaves](#b4-hot-apply-what-changes-live-and-how-a-shrink-behaves)
  - [B.5 Precedence and provenance (the "two declarations" rule)](#b5-precedence-and-provenance-the-two-declarations-rule)
  - [B.6 Two mechanisms, two questions — `IntakeGovernor` vs the broker](#b6-two-mechanisms-two-questions--intakegovernor-vs-the-broker)
  - [B.7 Per-Pipeline cadence and fetch parallelism](#b7-per-pipeline-cadence-and-fetch-parallelism)
  - [B.8 Operating it](#b8-operating-it)
  - [B.9 Decision 2026-09-06 — the fetch lane stays FIFO](#b9-decision-2026-09-06--the-fetch-lane-stays-fifo)
- [Part III — Parsing](#part-iii--parsing)
  - [P.1 The unifying model: a parser *frontend* feeding the DuckDB *backend*](#p1-the-unifying-model-a-parser-frontend-feeding-the-duckdb-backend)
  - [P.2 What the engine exposes **today** vs. raw DuckDB](#p2-what-the-engine-exposes-today-vs-raw-duckdb)
  - [P.3 Corrected & extended DuckDB `read_csv` parameter reference](#p3-corrected--extended-duckdb-read_csv-parameter-reference)
  - [P.4 Beyond CSV — the other DuckDB-native readers](#p4-beyond-csv--the-other-duckdb-native-readers)
  - [P.5 The `parsing:` block `[LIVE]`](#p5-the-parsing-block-live)
  - [P.6 Per-format profiles (copy-paste starting points)](#p6-per-format-profiles-copy-paste-starting-points)
  - [P.7 Decision matrix — pick a frontend](#p7-decision-matrix--pick-a-frontend)
  - [P.8 Roadmap status and corrections to the PDF](#p8-roadmap-status-and-corrections-to-the-pdf)
  - [P.9 Parser plugins — the self-describing Parser framework](#p9-parser-plugins--the-self-describing-parser-framework)
  - [P.10 XML and ASN.1 — hierarchical parsers that ingest](#p10-xml-and-asn1--hierarchical-parsers-that-ingest)
  - [P.11 Drop-in parser jars — trust gate, staging dir, pack parsers, ingest and preview gate SHIPPED (2026-09-25)](#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25)
  - [P.12 Decode Profile — one vendor's decode settings, kept once (2026-09-25, slices C1–C4)](#p12-decode-profile--one-vendors-decode-settings-kept-once-2026-09-25-slices-c1c4)
  - [P.13 Deferred by decision (the trust design's open items)](#p13-deferred-by-decision-the-trust-designs-open-items)
  - [P.14 BER hostile-input handling — fixed 2026-09-17, value cap 2026-09-24](#p14-ber-hostile-input-handling--fixed-2026-09-17-value-cap-2026-09-24)
- [References](#references)

---

## Part I — Ingest

**What this covers.** Ingest is the step where a landed file is read, shaped and written as partitioned
Table files. DuckDB does the loading and writing for every format; formats DuckDB cannot read are decoded
in Java by a plugin that feeds records in. This part explains that plugin contract, how big and small
files are handled, how files are grouped into Consignments and committed (including what happens when a
node dies or a Space stops mid-run), how output files are named so a retry does not duplicate rows, and
how a Pipeline with `route:` branches writes each branch on the ordinary ingest path.

> Vocabulary: a Consignment is the unit a Step's token references (data resolved by reference; edges
> carry no records) — the full runtime edge model converges at Phase 7
> ([spec-pipeline.md](spec-pipeline.md#the-token-model--the-design-of-record) § *The token model*). The parse mechanisms and the
> `parsing:` block are [Part III](#part-iii--parsing) below; acquisition (getting files to the inbox) is
> [spec-acquisition.md](spec-acquisition.md) and [architecture.md](architecture.md).

## Plugin ingester

**Direction it implements** (canonized 2026-08-19, E3 of the delimited-grammar-properties plan Part II):
DuckDB *is* the execution core — it loads and writes everything; formats DuckDB cannot read natively
**wrap** the core by parsing records in Java and feeding them in. This section names the shipped seam so
nobody rebuilds it.

Use the plugin ingester when:

- The file format is binary, proprietary, or otherwise not parseable by `CsvIngester`.
- A single input file emits **multiple event types** (e.g. CALL + SMS interleaved in one CDR file) that must land in separate output tables with different schemas.
- Partition columns such as `event_type` are **computed by the parser** — they do not exist as raw data columns but are derived from the record structure.

The CSV path is unchanged for all existing Pipelines. Plugin ingester is an opt-in mode activated by adding `processing.ingester:` (or `parsing.plugin.ingester`, see [§P.5](#p5-the-parsing-block-live) below) to the pipeline toon.

### The SPI pair

- **`StreamingFileIngester`** (`inspecto-etl`, `com.gamma.etl`;
  `platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java`) — `ingest(File,
  RecordSink, srcId, PipelineConfig)`. It is the **only** plugin ingestion SPI (the old whole-file
  `FileIngester` was removed in v3.11.0). One implementation per binary/grammar-driven format. Chosen
  **by config** (`processing.ingester` / `parsing.plugin.ingester`, an FQCN instantiated reflectively) —
  this is deliberate and correct for config-chosen implementations; it is *not* a `ServiceLoader`
  candidate, because the config names one class, not a discovered set. (A pack-provided ingester resolves
  through its parser's loader — [§P.11 *Drop-in parser jars*](#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25) below.)
- **`RecordSink`** (`inspecto-etl`) — `define / emit / reject / junk`, keyed by segment. What a
  wrap implementation calls; it never touches DuckDB directly.

Implementations decode a file and push records one at a time via `RecordSink.emit()`; the framework
owns DuckDB table creation, transform, partitioned write, and lineage. Implement it in the same fat JAR
with a public zero-arg constructor:

```java
package com.acme.etl;

import com.gamma.etl.*;
import java.io.File;

public class MyCdrIngester implements StreamingFileIngester {

    @Override
    public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
        try (var decoder = openYourDecoder(file)) {     // your TLV / binary / record walker
            for (var record : decoder) {
                switch (record.type()) {
                    // emit(segmentKey, values…) — values positional, matching the segment's columns.
                    // Do NOT pass __src_id; the framework adds it.
                    case CALL -> sink.emit("CALL", record.id(), "CALL", record.date());
                    case SMS  -> sink.emit("SMS",  record.id(), "SMS",  record.date());
                    case BAD  -> sink.reject(record.typeKey());   // known type, malformed → errorRows
                    default   -> sink.junk();                     // unknown type, skipped → junkCandidateRows
                }
            }
        }
        // Returning normally signals end-of-file; the framework performs the final flush.
    }
}
```

**Contract:**

| Rule | Detail |
|---|---|
| Columns | Either call `sink.define(key, columns)` once per segment before emitting, **or** rely on the framework deriving columns from that segment's `raw.fields`. You *must* `define` explicitly when a `partitions[]` source column is ingester-derived and not in `raw.fields` (e.g. `EVENT_TYPE`). |
| `emit` values | Positional, matching the declared/derived column count and order; stored as `VARCHAR` (`null` ⇒ SQL `NULL`). Do **not** pass a `__src_id` value — the framework adds that column itself. |
| Quarantine | Throw (e.g. `IOException`) → `QUARANTINED_UNREADABLE`. Emit zero records across all segments → `QUARANTINED_MISMATCH`. |
| Don't flush yourself | Do **not** create DuckDB tables, transform, or write output — the framework does, and bounds scratch for you. |
| Segment keys | Must match the keys declared in `processing.segments:` in the pipeline toon. |

> **Migrating from the old `FileIngester`?** The whole-file `FileIngester` SPI (return `List<Segment>` of pre-built DuckDB tables) was **removed in v3.11.0**. Port to `StreamingFileIngester`: replace table creation + inserts with `sink.emit(...)` calls, drop the `raw_<KEY>_f<srcId>` table bookkeeping and the `__src_id` handling (the framework owns both now), and let counts flow through `emit`/`reject`/`junk` instead of `IngestResult`.

### The one sink implementation

**`DuckDbRecordSink`** (`inspecto-engine`, `com.gamma.inspector`) buffers 10 000 rows per segment
and flushes through the **JDBC `DuckDBAppender`** — never staged CSV, never row INSERTs — then runs
the *identical* downstream as native CSV ingest: `DataTransformer.materialize` →
`PartitionWriter.write` → `LineageCollector`. Since B4 it also honors `output.filename_column`
(the per-row source-file lineage column), and since E1 an **unkeyed segment schema writes a flat
unpartitioned store** instead of the retired `year=1900/month=01/day=01` sentinel bucket.

**Intermediate CSV is a fallback for *out-of-process* producers only.** The in-process Appender
path is already built, faster, and avoids a second disk write — never demote to staging CSV from
inside a `StreamingFileIngester`.

**Reference implementation:** `Asn1RecordIngester` (`com.gamma.telecom.asn1`) — Java decodes BER/DER
records against an X.680 grammar and emits them per segment; everything downstream is the shared
DuckDB path. This is exactly the operator's "feed Java-parsed records into it". Its rules are in
[§P.10 *Loading to Tables*](#loading-to-tables--asn1recordingester-2026-07-31) below.

---

## Execution modes — the framework picks by file size

The same ingester serves both ingestion shapes; the [`StreamingPluginIngestStrategy`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/inspector/StreamingPluginIngestStrategy.java) chooses one **per Consignment** with zero extra I/O (member sizes are already known):

| Mode | When | What it does | Output |
|---|---|---|---|
| **Union** (`UnionModeIngester`) | the Consignment's largest member is `< processing.streaming.large_file_bytes` (the common many-small-files case) | each member's emitted records accumulate into a per-member raw table `raw_<KEY>_f<srcId>`; after all members, each segment's tables are `UNION ALL`-ed and transformed/written **once** for the whole Consignment | **consolidated** — one set of partition files per Consignment |
| **Generation** (`GenerationModeIngester`) | the largest member is `>= processing.streaming.large_file_bytes` (a genuinely huge single file) | each member streams in bounded "generations": once a segment hits `flush_records` rows it is transformed, written, lineage-counted, then dropped — peak heap and scratch stay ≈ one generation regardless of total file size | per-generation files (`<stem>_gNNNNN_out.*`) coexisting in the partition dirs (valid Hive layout) |

Union mode amortises the fixed per-Consignment cost (one temp DB, one transform, one write) across thousands of small files and consolidates output instead of fragmenting it. Generation mode is the only way to process a multi-hundred-GB / TB file the framework cannot otherwise split (line-based CSV auto-chunking via `processing.chunking` does not apply to opaque records). Both modes conserve row counts and produce byte-identical transform/lineage output for the same input.

Tuning knobs (both optional, under `processing.streaming`; parsed in `PipelineConfigParser`):

| Key | Default | Description |
|---|---|---|
| `large_file_bytes` | `268435456` (256 MB) | A Consignment whose largest member is `>=` this runs in generation mode; smaller ones use union mode. `0` forces union mode always. |
| `flush_records` | `5000000` | Rows per generation flush (generation mode only); bounds scratch per generation. |

*(The wrap-SPI page named a `generation_threshold_bytes` key for this until 2026-09-08; that key exists nowhere.)*

To make many-small-files efficient, also raise `collector.consignment.max_files` so the planner packs many files into each union Consignment.

---

## 3. Segment schemas and the partition declaration

### Segment schema toon (`partitions[]`) {#segment-schema-toon-partitions}

Each segment key has its own schema toon. Use the `partitions[N]{...}` tabular list syntax (JToon's array form) instead of the legacy `partitionKey:` shorthand. Each row maps an output partition column to a raw table source column and specifies how to derive the value.

> **JToon list syntax matters.** Prefer the `name[N]{col1,col2,col3}:` tabular form for flat rows — the form `raw.fields[N]{...}` uses. ⚠ The block-list form `name[N]:` + `- key: value` entries DOES parse (verified 2026-09-05: `MappingMigrator` writes it for `mapping.fields[]` rows that carry nested `args`, and `ConfigCodec.toMap`/`JToon.decode` reads it back), but a bare `- item` list under a key with NO `[N]` count is what silently decodes as `null`. A YAML-style list silently parses as `null`, and `PartitionDef.fromSchema` then falls through to the empty-list branch, so every row lands in the `year=1900/month=01/day=01` sentinel partition. Symptoms: single output file regardless of how many distinct dates you have.

```yaml
# file: spaces/default/config/events/call_schema.toon

partitions[4]{column,source,type}:
  record_type,EVENT_TYPE,VARCHAR   # from the column the ingester emits; NOT named event_type — see below
  year,EVENT_DATE,DATE_YEAR
  month,EVENT_DATE,DATE_MONTH
  day,EVENT_DATE,DATE_DAY

raw:
  name: call
  format: CSV
  fields[3]{name,selector,type}:
    ID,"0",VARCHAR
    EVENT_TYPE,"1",VARCHAR
    EVENT_DATE,"2",DATE

mapping:
  canonicalName: call
  rawName: call
  rules[3]{targetColumn,sourceExpression,transformType}:
    ID,ID,DIRECT
    EVENT_TYPE,EVENT_TYPE,DIRECT
    EVENT_DATE,EVENT_DATE,DIRECT
```

⛔ **A partition `column` must not equal a mapped column, ignoring case** (`PARTITION-KEY-VALIDATION-GAPS-1`,
2026-09-23). DuckDB column names are case-insensitive, so `event_type` next to the mapped `EVENT_TYPE` is a
duplicate: DuckDB silently renamed the partition column `event_type_1`, `PARTITION_BY` bound to the mapped
column, and the folders came out `EVENT_TYPE=…` with `event_type_1` left in the data files. The transform now
refuses the pair (`DataTransformer.selectFor`) and `POST /validate` reports it; this example used to be
exactly that shape. The partition `source` must be a `raw.fields[]` name — a partition is cut before mapping.

**`PartitionDef.type` values:**

| Type | SQL generated | Use for |
|---|---|---|
| `VARCHAR` | direct column reference | String columns the ingester computes (e.g. `EVENT_TYPE`) |
| `DOUBLE` | `TRY_CAST(col AS DOUBLE)` | Numeric partition key |
| `INTEGER` | `TRY_CAST(col AS INTEGER)` | Integer partition key |
| `DATE_YEAR` | `YEAR(TRY_STRPTIME(CAST(col AS VARCHAR), fmt))::VARCHAR` | Year component of a date column |
| `DATE_MONTH` | `LPAD(MONTH(…)::VARCHAR, 2, '0')` | Zero-padded month |
| `DATE_DAY` | `LPAD(DAY(…)::VARCHAR, 2, '0')` | Zero-padded day |

The `DATE_*` types cast the source column to `VARCHAR` before parsing, so the same schema toon works whether the raw DuckDB column is already typed `DATE` (plugin path) or is a VARCHAR string (CSV path).

The legacy `partitionKey: COLUMN` shorthand synthesises three `DATE_YEAR` / `DATE_MONTH` / `DATE_DAY` entries automatically and remains fully supported for CSV Pipelines.

### The partition declaration — two contracts, deliberately

The word `partitions[]` appears in two homes with two *deliberately different* readers
(E2's unification was refused 2026-08-19 — the difference is posture, not drift):

- **Ingest schema** (`PartitionDef.fromSchema`, `inspecto-etl`): requires a `type` per entry,
  hard-fails on a malformed list, reads the legacy `partitionKey:` — fail-closed, because a wrong
  partition layout at ingest corrupts the store.
- **Sink node** (`SinkPartitions`, `inspecto-engine`): bare column or `{column, source}`, degrades
  per D3 — a write whose bytes are good must not fail over an advisory declaration; entries with no
  usable `column` are refused with a named error before a byte is written (detail:
  [§A.2](#a2-event-time-at-write) below).

Both feed the ONE writer: `PartitionWriter` owns partitioned `COPY … PARTITION_BY` **and** (E1)
the unpartitioned single-file `COPY`, with staging + atomic per-file reveal for both, on both lanes.

---

## Pipeline config (plugin) {#pipeline-config-plugin}

Replace `schema_file:` with `ingester:` and `segments:`. The `duplicate_check:` block is optional (plugin path does not use `.processed` markers by default).

```yaml
name: EVENTS_ETL

dirs:
  poll:       inbox/events
  database:   database/events
  backup:     backup/events
  temp:       temp/events
  errors:     errors/events
  quarantine: quarantine/events
  status_dir: status/events
  log_dir:    logs/events

output:
  format: CSV           # or PARQUET

processing:
  threads: 1
  file_pattern: "glob:**/*.bin"
  ingester: com.acme.etl.MyCdrIngester   # fully-qualified StreamingFileIngester in the fat JAR
  segments:
    CALL: spaces/default/config/events/call_schema.toon  # key must match the segment key the ingester emits
    SMS:  spaces/default/config/events/sms_schema.toon
  streaming:                # optional — mode selection + generation budget
    large_file_bytes: 268435456   # ≥ this (per member) → generation mode; else union mode
    flush_records: 5000000        # rows per generation flush
collector:
  consignment:
    max_files: 1000         # pack many small files per union consignment (raise for the many-small case)
  csv_settings:
    delimiter: ","
    skip_header_lines: 0
    skip_junk_lines: 0
    skip_tail_lines: 0
    date_formats[1]: "%Y-%m-%d"
    timestamp_formats[1]: "%Y-%m-%d"
```

| Key | Required | Description |
|---|---|---|
| `processing.ingester` | yes | Fully-qualified class name of a `StreamingFileIngester` implementation in the fat JAR |
| `processing.segments` | yes (when ingester is set) | Ordered map of segment key → schema toon path; validated at startup |
| `processing.streaming.large_file_bytes` | no | Generation-mode threshold in bytes (default 256 MB); `0` = always union |
| `processing.streaming.flush_records` | no | Rows per generation flush (default 5,000,000) |
| `processing.ingester_config` | no | Free-form map for plugin-specific settings (e.g. `record_length`, `byte_order`). Plugins read it via `cfg.schemas().ingesterConfig().get("key")`. Defaults to empty map. |

(`parsing.plugin.{ingester,ingester_config,segments}` is the newer spelling of the same triple; the
three-tier precedence is in [§P.5](#p5-the-parsing-block-live) below.)

> `segments:` must be a non-empty map when `ingester:` is set; a missing or empty map throws `IllegalArgumentException` at startup. Each schema file must exist; a missing file throws `FileNotFoundException`.
>
> **Identifier validation.** At config load, every name that will be interpolated into SQL DDL — `raw.fields[].name`, `mapping.rules[].targetColumn`, `partitions[].column / source`, `partitionKey`, the `schemas[].table` value — is validated against `^[A-Za-z_][A-Za-z0-9_]*$`. Names containing spaces, dots, quotes, hyphens, or SQL operators fail the load with a precise location (e.g. `segment[CALL].raw.fields[].name`). This is a hard fail, not a warning.
>
> **Config sanity warnings.** A separate post-load pass logs SLF4J warnings for suspicious-but-legal patterns: no partitions declared (rows would collapse to the `1900/01/01` sentinel), empty `date_formats`, `retention_days <= 0`, `threads > 1` with `batch.max_files = 1`. Warnings appear at startup; they don't block the run.

---

## 5. Output layout

Output files are written under `database/<pipeline>/<SEGMENT_KEY>/` and partitioned by the columns declared in that segment's `partitions[]` list. Multiple segments from the same input file land in independent sub-trees:

```
database/events/
  CALL/
    record_type=CALL/
      year=2020/
        month=04/
          day=03/
            events_20200403_out.csv       # union mode: one consolidated file per partition
  SMS/
    record_type=SMS/
      year=2020/
        month=04/
          day=03/
            events_20200403_out.csv
```

In **generation mode** each bounded generation writes its own per-partition file (`<stem>_gNNNNN_out.*`), which coexist in the partition directories — valid Hive layout, the same trade-off the CSV auto-chunker makes. In **union mode** the batch's members are consolidated, so each partition gets a single file (named for the sole member's stem when the batch has one survivor, else the batch id). How names change under `processing.output_per_member` is [§10](#10-write-path-identity-and-output-naming).

All lineage (input → output row counts), audit files (`_batches_`, `_lineage_`, `_status_`), and per-batch JSON manifests are written identically to the CSV path — one consolidated `ConsignmentRow` per batch, with the segment keys used as the `schemaLabel`.

---

## 6. Shipped ingesters

### `TypedRecordIngester` (reference implementation)

The repo ships one production-ready ingester, `com.gamma.ingester.TypedRecordIngester`, which handles the common case of **type-tagged text records** — one record per line where the first field selects the segment:

```
CALL,C001,2020-04-03,42
SMS,S001,2020-04-03,+15551234567
CALL,C002,2020-04-04,17
```

For each line, field 0 is matched against the keys of `processing.segments:`. Fields 1..N are mapped positionally to that segment's `raw.fields` list — so the schema's field order must match the input column order. Lines whose prefix isn't in `segments:` are silently skipped (`sink.junk()`).

Behaviour worth knowing:

- Every emitted value is stored as `VARCHAR` in DuckDB. `DataTransformer` handles type coercion at transform time via `CAST(... AS VARCHAR)` + `TRY_STRPTIME` — pre-typing would force every plugin to re-implement the same parsing logic.
- The ingester `define`s a trailing `EVENT_TYPE` column on every segment (emitted with the segment key), so schemas can reference `EVENT_TYPE` as a partition source without redeclaring it in `raw.fields`.
- Blank lines and lines starting with `#` are skipped without being counted as errors. Lines with the wrong field count for a known segment go through `sink.reject(key)` (counted as `errorRows`) and are dropped.
- Field delimiter comes from `processing.csv_settings.delimiter` (default `,`).

Wire it up exactly like a custom ingester:

```yaml
processing:
  ingester: com.gamma.ingester.TypedRecordIngester
  segments:
    CALL: spaces/default/config/events/call_schema.toon
    SMS:  spaces/default/config/events/sms_schema.toon
  csv_settings:
    delimiter: ","
```

See `TypedRecordIngester.java` for the full source — it's deliberately compact (~120 lines) and is the recommended starting point for forking your own typed-record variant.

### Fixed-length binary records (`FixedWidthRecordIngester`) {#fixed-length-binary-records-fixedwidthrecordingester}

For **binary** fixed-length records (no delimiter, no newlines — each record is exactly *N* bytes), the
repo ships `com.gamma.ingester.FixedWidthRecordIngester`. (Fixed-width **text**, one record per line, is
handled natively by the engine — set `frontend: fixedwidth`; see
[configuration.md](../config/admin-guide-configuration-reference.md#fixed-width-frontend-frontend-fixedwidth) and
[§P.6.3](#p63-fixed-width--fixed-length-records-live) below. Reach for this plugin only when records are not newline-delimited.)

It reads `record_length` bytes per record and carves each field by byte `(start,length)`, decoding with
the configured `encoding` and trimming per `trim`. Column **names/types** come from the segment schema's
`raw.fields` (positional); the `ingester_config` supplies only the byte geometry. A trailing partial
record is `reject`ed; an `IOException` quarantines the file as `QUARANTINED_UNREADABLE`; zero records
emitted ⇒ `QUARANTINED_MISMATCH`.

```yaml
processing:
  ingester: com.gamma.ingester.FixedWidthRecordIngester
  segments:
    REC: spaces/default/config/subscriber/subscriber_schema.toon   # exactly one segment
  ingester_config:
    record_length: 40
    encoding: utf-8                                  # optional (default UTF-8)
    trim: both                                       # none | left | right | both (default both)
    fields[4]{name,start,length}:                    # positional to the segment's raw.fields
      ACCOUNT_NUMBER,0,12
      EVENT_DATE,12,10
      PLAN_CODE,22,6
      BALANCE,28,12
```

Because it's a `StreamingFileIngester`, the framework runs it in union mode (many small files) or
generation mode (one huge file ≥ `processing.streaming.large_file_bytes`) automatically — bounded
memory either way.

### Hierarchical ingesters (XML, ASN.1)

`XmlRecordIngester` (the tree→segments bridge) and `Asn1RecordIngester` load through the same
`parsing.plugin` machinery; their selector, segment-key and failure rules are in
§P.10 below: [*The tree→segments bridge*](#the-treesegments-bridge--xmlrecordingester-2026-08-30) and [*Loading to Tables*](#loading-to-tables--asn1recordingester-2026-07-31).

---

## 7. Plugin author workflow

End-to-end recipe for shipping a custom `StreamingFileIngester` to a deployed pipeline:

**1. Set up your project.** Depend on the inspecto fat JAR. The minimal Maven snippet:

```xml
<dependency>
    <groupId>com.gamma.inspector</groupId>
    <artifactId>inspecto-processor</artifactId>
    <version>4.0.0</version>
    <scope>provided</scope>
</dependency>
```

Use `<scope>provided</scope>` because the deployment server already has the fat JAR — you don't want to repackage it into yours.

**2. Implement `StreamingFileIngester`.** Decode the file and `emit` records; let the framework own tables, transform, write, and lineage. The fastest path uses no JDBC at all — just `sink.emit(...)`. For derived partition columns not in `raw.fields` (e.g. `EVENT_TYPE`), call `sink.define(key, columns)` once per segment before the first emit. `TypedRecordIngester` is the conservative baseline — fork it when your format diverges.

**3. Write the segment schema files.** One toon per segment key. Use the JToon `partitions[N]{column,source,type}:` tabular form (see the warning in [§3](#segment-schema-toon-partitions) — YAML-style lists silently break partitioning). `raw.fields` describes the data columns your ingester emits; ingester-derived columns (like `EVENT_TYPE`) go in `partitions[]` and must be `define`d on the sink.

**4. Test locally before deploying.** Pattern after `TypedRecordIngesterTest` / `ConsignmentIngestorPluginDeepTest`: construct a `PipelineConfig` from an in-test temp pipeline toon, build a `Consignment`, and call `ConsignmentIngestor.process(batch, cfg, audit)`. This exercises the full plugin path including `DataTransformer` + `PartitionWriter` against a real DuckDB instance. Smoke-test with rows on **at least two distinct dates** — single-date tests can mask the partition-fan-out bug class — and with **two members sharing a partition** to confirm union consolidation.

**5. Package and deploy.**

- `mvn package` produces `your-ingester-x.y.z.jar`
- On the server, put your JAR on the classpath alongside the inspecto JAR. The `run.sh` / `run.bat` wrappers shipped by `package.ps1` use `-jar inspecto.jar`; switch them to `-cp "inspecto.jar:your-ingester-*.jar" com.gamma.inspector.CollectorProcessor <pipeline.toon>` (use `;` instead of `:` on Windows).
- Reference your class by FQCN in the pipeline toon: `processing.ingester: com.acme.events.MyIngester`. The framework loads it by FQCN reflection and `getDeclaredConstructor().newInstance()` — the class must be public with a no-arg constructor. On the classpath it resolves on the engine loader. Shipped in an allowlisted Job Pack instead (no restart), it resolves through the pack's loader when the pack's `ParserPlugin` names it in `ingesterClass()` — see [§P.11 *Drop-in parser jars*](#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25) below.

**6. Production health checks.** The framework reports plugin loading at startup:

```
[CONFIG] Plugin ingester: com.acme.events.MyIngester  segments: [CALL, SMS]
```

If you see `Cannot instantiate streaming ingester: ...` with a `ClassNotFoundException`, your JAR isn't on the classpath. If you see it with a `NoSuchMethodException`, you're missing the public no-arg constructor. A `ClassCastException` means the class doesn't implement `StreamingFileIngester`. (Since slice P3, an ingester that no loaded parser names and the engine loader cannot find fails with the named "is not on the classpath and no loaded parser names it" error instead — [§P.11 *Drop-in parser jars*](#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25) below.)

Per-file outcomes appear in `<pipeline>_status_<runTimestamp>.csv`:

- `SUCCESS` — at least one segment produced rows; output written
- `QUARANTINED_UNREADABLE` — ingester threw (file moved to `dirs.quarantine/unreadable/`)
- `QUARANTINED_MISMATCH` — every segment emitted 0 rows (file moved to `dirs.quarantine/field_mismatch/`)

---

## 8. Consignment coordination

* `CollectorProcessor` (`platform/inspecto-engine/src/main/java/com/gamma/inspector/CollectorProcessor.java`) — the per-Pipeline ELT
  entry point, split into two halves (B3b): **`acquire(cfg)`** runs the [acquisition](architecture.md)
  phases (remote fetch-and-land; a no-op for a `local` Collector), and **`ingest(cfg, onCommit)`** scans the
  inbox → groups into `Consignment`s via `ConsignmentPlanner` — whose audit label is the selected schema's
  `raw.name`, or the literal `schema` for a plugin-ingester pipeline, whose selection carries NO single
  schema (segments are chosen per record at ingest; a `null` there threw until 2026-09-06, so no plugin
  pipeline could plan a batch through `CollectorProcessor`) — (bounded by
  `collector.consignment.max_files`/`max_bytes`, ordered by `collector.consignment.order` — the canonical home
  since 2026-09-02 (CONSIGNMENT-HOME-1; `processing.batch.*` is the dual-read legacy spelling, healed on save);
  `Consignment.batchId` keeps its wire spelling — **default `mtime`
  (file arrival)**, operator decision 2026-08-12; `name` is the opt-in for feeds whose stamps are
  unreliable, and any other value is refused at parse) → submits to a
  virtual-thread executor bounded by the Consignment slot broker (per-Pipeline cap `processing.threads`;
  [Part II, Part B](#part-b--concurrency-the-four-layer-hierarchy) below). `run()` = `acquire` then `ingest`, the
  self-contained one-shot used by the single-pipeline CLI `main`, `reprocess`, and the service's manual "run
  now". The always-on service instead drives the two halves on **separate timers with separate budgets** — see
  [acquisition](architecture.md). Ingest always walks the local inbox: a remote-fetched file, once
  landed, is discovered exactly like a locally-pushed one, so `countPending` is now the exact landed backlog
  (no remote approximation).
* `ConsignmentIngestor` (`platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java`) — a thin, stateless
  coordinator: pick a `ConsignmentIngestStrategy` ([spec-pipeline.md](spec-pipeline.md#transforms--modularity-seams), transform seams) (CSV or plugin), run `ingest()` → an
  `IngestOutcome`, then the path-agnostic tail `commit()` (DuckLake register → manifest → backup originals →
  markers → ledger, in that crash-safe order) and `writeAudit()`. Never throws for a Consignment failure — audit is
  always written (see [quarantine](spec-pipeline.md#quarantine-outcomes)). Markers/fingerprints go **last**, so a FAILED
  Consignment leaves no "already processed" record and its files are rediscovered next poll (retry is
  implicit); what is recorded about a Consignment either way — ledgers, provenance, live gauges, and how
  an operator audits a failed file or record — is [consignment status flow](architecture.md#e5-consignment-status-flow-the-flowfile-question).
* `MultiCollectorProcessor` (`platform/inspecto-engine/src/main/java/com/gamma/inspector/MultiCollectorProcessor.java`) — the outer
  orchestrator running many `.toon` Pipelines concurrently in one JVM, bounded by `Semaphore(sources.max)`.
  Total worker pressure = `sources.max × processing.threads × duckdb_threads`.

---

## 9. Failover and graceful stop

### Failover is at-least-once unless the output tree is shared (`HA-KILL9-DOUBLE-INGEST-1`, closed 2026-10-10; residual `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1`)

The acknowledgement (backup move, then the node-local processed marker) is written AFTER the output on purpose,
so a node killed between the two leaves a durable output and an un-acked file; a survivor re-ingests it.
A single-file batch writes `<stem>_out.<ext>` to the same partition path, so on a shared or synced output tree the
second write overwrites the first; on node-local trees (the 2026-10-10 drill) both copies exist. A multi-file batch is
named by its batch id (hash of its member set), so a re-planned member set writes a second file even on shared
storage. `CommitFence` stops a lease-lost holder committing but not writing outputs first: it is consulted only at the commit points (source finalisation, landing of acquired files), never before an output write. `DbDedupLedger` is not
involved (it serves `transform.dedup` only). Pinned by `KillBeforeAckDuplicateTest`; options and status in
BACKLOG; the operator-facing contract is in the [HA/DR drill runbook](../../layer/platform/admin-guide-ha-dr-drill.md).

### Cooperative cancel on graceful stop (`HA-GRACEFUL-STOP-LEASE-RELEASE-1`, as built 2026-10-11)

`CommitFence` alone only refuses commit points, not output writes, so releasing a lease while its run thread was
still writing would let a survivor become a second writer. `CollectorService.close()` therefore first calls
`RunLease.cancelRuns()` on the run and acquire leases (a signal only: every claim's `cancelled()` turns true); each
run registers that with `CommitFence.hold(..., claim::state, claim::cancelled)`. `CommitFence.checkpoint` (before
each Consignment batch in `ConsignmentIngestor.process` and at the top of `ConsignmentIngestStrategy.writeAndTrace`,
the shared output-write choke point) and `CommitFence.check` (every commit point) throw `RunCancelledException`, a
`LeaseLostException` subtype, so existing handling applies: no retry attempt spent, input not quarantined (the
strategies rethrow it past their quarantine/FAILED catches), nothing committed, inputs stay in the inbox, and
`CollectorProcessor` does not count it as a failed batch.

- **It is still not a success** (fixed 2026-10-11, `HA-CANCEL-RESIDUALS-1`): `CollectorProcessor` lets every other
  batch drain, then rethrows the `RunCancelledException` (a real batch failure still wins as
  `ConsignmentProcessingException`), `ConsignmentIngestor` rethrows a cancel that first lands at the commit point
  after the batch's audit, and `MultiCollectorProcessor.RunResult` counts it in a separate `cancelled` (neither
  `failed` nor `succeeded`). Pipeline runs therefore have a `CANCELLED` outcome like Job runs (operator decision
  2026-10-11): the async manual-run record (`GET /runs/runs/{runId}`) is `CANCELLED` when the run was cancelled and
  nothing genuinely failed (a genuine failure still wins), and `PipelineScheduler` logs it at INFO without bumping
  `inspecto_source_run_failures_total` or marking the poll failed (`RunCancellationTest`, `GracefulStopHandoverTest`).
  No pipeline-run-level Signal exists, and a cancelled batch emits no `pipeline.batch.committed`, so nothing keyed on
  success fires.
- **A cancel landing at the commit point** still writes that batch's audit row as `FAILED` ("lease lost" text), as a
  genuine lease loss does; a genuine lease loss (any other `LeaseLostException` at the commit or park fence) is
  rethrown after that audit too (`INGEST-COMMIT-LEASE-LOST-OUTCOME-1`, fixed 2026-10-12): `CollectorProcessor` counts
  it as a failed batch (`ConsignmentProcessingException`, so `RunResult.failed`, a failed async run record and a
  scheduler failure, never `CANCELLED`, which stays graceful-stop only), spending no retry attempt (`onBatchThrown`
  skips it), with no quarantine and the inputs left in the inbox (`CommitLeaseLostOutcomeTest`).
- **Release.** The claim is still released only by the run thread's own try-with-resources, i.e. strictly after it
  stopped, so safety never depends on checkpoint density, and `triggerWorkers.close()` joins the threads. Measured:
  cancel to survivor holding the lease about 25 ms in the lease test (TTL 60 s). If `SpaceManager.closeWithDeadline`
  still expires with a run alive it logs a WARN and the lease is deliberately NOT released (TTL path, fail safe).
- **Remote acquisition** (`HA-CANCEL-RESIDUALS-1` (2), 2026-10-11): `RemoteAcquisitionHandler.materializeRemote`
  resolves `CommitFence.cancelSignal(ACQUIRE, pipeline)` on the caller thread (parallel fetch workers do not carry the
  MDC Space that keys the fence) and checks it before each file, so a cancel stops between whole
  fetch→land→post-action units: nothing is half-acked, landed files are ordinary inbox files, unstarted files stay on
  the remote (a crash inside one unit keeps the documented land-then-ack at-least-once posture).
- **Output already written stays** (`HA-CANCEL-RESIDUALS-1` (4) declined): output written by an in-flight batch before
  its cancel point deliberately stays on disk — the deterministic batch id makes the cancelled run's uncommitted files
  the same paths the survivor writes, and on the TTL path (close deadline expired) the run thread can outlive the
  lease, so a delete could destroy the survivor's output; the survivor's in-place overwrite is the self-heal.
- **Granularity.** A chunked file stops between chunks (each chunk passes the `writeAndTrace` checkpoint; the chunks
  already written stay, lost-lease posture; `RunCancellationTest.aCancelledChunkedFileStopsBetweenChunks`), but a
  single long native streaming write, one chunk, or one file's remote fetch is not interruptible mid-way, so release
  waits for that unit to end or the 10 s deadline (mid-unit interruption declined 2026-10-18, BACKLOG §6).

### Job runs under a graceful stop

- **Cooperative cancel** (`HA-CANCEL-RESIDUALS-1` (1), `597d1893a`): `RunClaims.Claim.cancelled()` (the `claimsOver`
  adapter delegates to `RunLease.Claim.cancelled()`); `CollectorService` keeps the two Job leases `wireJobClaims`
  opens (`SCOPE_JOB` arming, `SCOPE_AUTHORED`) and `close()` calls `cancelRuns()` on them before `jobs.close()`
  drains; `JobService.runJob` registers `arming.cancelled() || authored.cancelled()` on its `Scope.JOB` hold, so
  `PipelineJobRunner`'s fence checks throw `RunCancelledException`; `JobService.runJob` catches that subtype apart
  from other exceptions and records the Run `CANCELLED` (`JobResult.cancelled`, a new terminal status beside
  `SUCCESS`/`FAILED`/`SKIPPED`/`REJECTED`) with one INFO `job.run.completed` (`outcome: CANCELLED`) — never `FAILED`
  or a CRITICAL `job.run.failed`, so a planned stop does not read as a Job failure. A genuine lease loss (any other
  `LeaseLostException`) still records `FAILED` + CRITICAL `job.run.failed` (`JobRunCancelledTest`).
- **A Space closing under a Job Run** (`JOB-SHUTDOWN-FAILED-1`, fixed 2026-11-06): the server's shutdown hook calls
  `SpaceManager.beginClose()` BEFORE `ControlApi.close()` (whose close hooks stop services a Job body calls, e.g. the
  Link Analysis index build service), and `CollectorService.close()` calls it again first thing; it sets
  `JobService.closing`. From then a Run that reaches `runJob` is recorded `SKIPPED` "the Space is shutting down" and
  never starts, and a Run whose body throws anything, or returns a `FAILED` result (as `la.index.build` does when the
  index service is gone), while closing is `CANCELLED` with the same message (a transient failure is not retried into
  a closing Space; a deadline expiry stays `FAILED`), one INFO `job.run.completed`, no CRITICAL `job.run.failed` and
  no Job-failed alarm or Incident (before: a cron `la_index_build` logged `FAILED in 0ms — null`). A failure with
  nothing closing is unchanged (`JobShutdownTest`). Claims are released only by the run thread, as above; a Job type
  whose body never reaches a `Scope.JOB` fence check is not interrupted and simply finishes within the deadline.
  (How the Space close escalates an overrun drain is [architecture.md](architecture.md#e6-stopping-a-space-graceful-stop).)
- **End to end:** `GracefulStopHandoverTest` closes a real `CollectorService` over a shared DB lease with a pipeline
  run and a Job run parked mid-flight, asserts the run's checkpoint throws `RunCancelledException`, and that a second
  owner then claims the lease; the parked run thread polls its own `CommitFence.cancelSignal` until `close()` has
  raised it (no fixed sleep).

---

## 10. Write-path identity and output naming

### Write-path identity audit (`SCALEOUT-WRITEPATH-IDENTITY-AUDIT-1`, closed 2026-10-18)

`batchId` is deterministic: `ConsignmentPlanner.buildBatch` calls `ConsignmentId.of(slug, seq, pollRoot, members)`, which hashes the sorted member paths and sizes (no clock). So a re-execution with the SAME member set rewrites the same names; a re-plan with a DIFFERENT member set mints a new id. What each write path does on such a re-plan:

| Write path | Named from | Re-plan with another member set |
|---|---|---|
| `ConsignmentIngestStrategy.consolidatedBaseName` (2+ survivors; callers `writeAndTrace`, `DrainCommand`) | `batchId` | **second output file**: the open hole, closed only with `processing.output_per_member` on (default off, operator call in BACKLOG §1) |
| same, 1 survivor, and `memberStem` with the setting on | member stem / origin hash | overwrite in place |
| versioned reference `__v_<batchId>` | `batchId` | a second version, by design (append-only store) |
| park table, manifest, `ParkedCommit`, branch commit log | `batchId` | a second state file; no data output |
| `RefusalQuarantine` `refused-<code>-<millis>-<seq>` | wall clock | another quarantine copy |
| `PartitionCompactor` / `ReferenceCompactor` `compacted_<millis>_out` | wall clock | replaces its journalled inputs; not an ingest re-run |
| `DbFileStageStore`, `DbConsignmentOutputStore` | keyed incl. `batchId` / `consignment_id` + path, `ON CONFLICT` | same id is a no-op; a new id adds rows |
| `DbAcquisitionLedger` watermark | `source_key` (DELETE + INSERT) | replaces the row |

### Output-stem collision guard and the stage-2 design (`INGEST-MULTIFILE-BATCH-REPLAN-DUP-1`, operator staging 2026-10-12)

**Stage 1, built.** Two members of ONE batch with the same output stem (`a/x.bin`, `b/x.bin`: `FileNames.outputStem` drops the directory) used to write the same `<stem>_gNNNNN_out.*` in the same partition; the later reveal replaced the earlier, so the first member's rows were GONE while lineage still counted both and both read as `SUCCESS` (reproduced 2026-10-12: one file holding only the second member's row, 2 lineage rows). Decision D3: the second member FAILS CLOSED. `ConsignmentIngestStrategy.claimOutputStem` is the one place a stem is claimed (case-folded, the tree may sit on a case-insensitive filesystem); a collision quarantines the member under `output_stem_collision` (`QuarantineManager.REASON_OUTPUT_STEM_COLLISION`; the quarantine path keeps its directory) and returns a `QUARANTINED_MISMATCH` audit row whose error starts `output_stem_collision:`. Only `GenerationModeIngester` names a member's output by its stem inside a multi-member batch, so only it calls the guard. The consolidated lanes (`CsvIngestStrategy`, `NativeCsvStreamingEngine.unionStreamingIngest`, `UnionModeIngester`, the `DrainCommand` resume) go through `consolidatedBaseName`: a multi-member batch is named by its batch id and a one-survivor batch by its own stem, so they cannot collide inside a batch (pinned by `OutputStemCollisionTest`: a same-stem union batch lands both). The single-member `streamingIngest` and `chunkedIngest` units are one member by construction. ⚠ Residual, not guarded: ACROSS batches, `b/x.bin` arriving in a later batch still replaces an earlier batch's `x_out` for the same partition, because a deliberate `OVERWRITE_OR_IGNORE` re-run of the same file lands on the same name and the engine cannot tell the two apart without per-output origin in the registry. Stage 2 does not fix it either unless the name carries the origin path (below).

**Stage 2, BUILT 2026-10-12 as an opt-in (default off, no flip yet): `processing.output_per_member`.** The design bullets below stay as the record; **As built** (after them) says where the build differs.
* Where it lives: the Pipeline `processing` block, parsed in `PipelineConfigParser` beside `chunking` and `reject_mode` into a `PipelineConfig` accessor; declared in `ConfigSpecs` (a `processing.output_per_member` FieldSpec, boolean) and checked in `ConfigSafetyValidator` as a boolean (an unknown value is an error, like `reject_mode`); the Pipeline schema form and the OKF config page name it. A new TOON key also needs its flat-config home, so check the Pipeline save round trip.
* Semantics: on, every member of a multi-member batch writes its OWN output file per partition, named `<stem>_out.<ext>` (the lone-member name that already exists), so a re-planned member set rewrites the same names and the `HA-KILL9` hole becomes overwrite-in-place on a shared tree. Lineage per output file then has exactly one srcId (`LineageCollector.collect` over a per-member `WHERE __src_id = n` projection). Versioned reference stores keep `baseName + "__v_" + batchId`, untouched. Node-local trees stay at-least-once.
* The stem must be unique per origin, not per file name: with the setting on, the stage-1 refusal becomes a naming rule. Recommended spelling when two members share a stem is `<stem>__<8 hex of the salted origin-relative-path hash>_out.<ext>`; never the raw path (never-store-values, file-name half). That spelling also closes the cross-batch residual above.
* Call-site changes. Add one helper `ConsignmentIngestStrategy.memberBaseName(member, batch, cfg, allMembers)` and one `writeAndTraceEach(...)` looping the batch's survivors. `CsvIngestStrategy` and `UnionModeIngester` replace the single `writeAndTrace(... consolidatedBaseName ...)` with the per-member loop; `NativeCsvStreamingEngine.unionStreamingIngest` does the same over its UNION view filtered by `__src_id`; `DrainCommand` must rebuild the same names from the manifest's member entries (`originalRelPath`), so the helper takes manifest-reconstructed survivors, and a parked commit written before a flip resumes with the names it was parked under (record the setting's value in the parked state, never re-read it). `GenerationModeIngester` already names per member (`<stem>_gNNNNN_out`); it only needs the origin-unique stem. `consolidatedBaseName` stays for the setting-off path.
* Compaction interplay. `PartitionCompactor` (`min_files` default 4, `min_age_days`) merges every `.parquet` in a partition directory older than the cutoff into one `compacted_<millis>_out.parquet`, journalled, and flips the merged-away paths to `COMPACTED_AWAY` in `consignment_outputs`. N files per partition per batch trips `min_files` after one 4-member batch, which is the intended path, but compaction removes the per-member names the failover overwrite relies on: a survivor re-ingesting an already-compacted member writes a NEW `<stem>_out` beside the merged file and duplicates rows (the documented reprocess-of-compacted-away trade-off). So with the setting on, `min_age_days` must exceed the failover horizon (lease TTL plus the longest un-acked window). The task reads `read_parquet` only: per-member naming on a CSV Pipeline has no compaction answer and must be documented as small-file growth.
* Measurement plan before any flip: a 50-member batch over the cricket or telecom fixtures, 3 partitions, Parquet and CSV, setting off vs on. Record sink wall time, output file count per partition, bytes, DuckDB scratch peak, `PartitionCompactor` time to merge 50 files, and a Dataset read over 50 files vs 1. Write the numbers into the BACKLOG row, not a claim.
* Flip-the-default criteria (all of): the 50-file sink time within 1.25x of setting-off; a compaction run returns the partition to one file with the `consignment_outputs` rows flipped; `KillBeforeAckDuplicateTest.knownHoleMultiFileBatchReplannedAfterKillDuplicates` flipped to assert 2 with the setting on; `DrainCommand` resume uses the same names; no reader globs `<batchId>_out*` (grep `_out`); and the operator accepts the file-count change for every existing Pipeline. Until then the known-hole test stays unchanged and passing.
* **As built (stage 2, 2026-10-12).** `processing.output_per_member` is a boolean (`PipelineConfig.outputPerMember()`, parsed beside `reject_mode`, a `ConfigSpecs` BOOL field defaulting false, a `ConfigSafetyValidator` boolean check; it needed no inventory entry and `PipelineKeyCoverageContractTest` / `AcceptedConfigKeysDocContractTest` stayed green). Off is byte-identical to before (`OutputPerMemberTest.offNamingIsUnchanged`, `OutputPerMemberEndToEndTest.offStillLandsOneBatchNamedFile`). On:
  * **The name** is `<stem>__<8 hex>`: `ConsignmentIngestStrategy.memberStem` appends the first 8 hex of the Space-salted keyed fingerprint (`FailureText.fingerprint`, the same salt `FileNames.outputStem` uses, so never the raw path) of the member's ORIGIN path: the file's path relative to `dirs.poll`, or the absolute path when it is not under it; an unpack-expanded entry hashes its archive's inbox path. The name therefore depends on the member alone (a re-plan under another batch id, another srcId or another member set rewrites the same file) and on where it came from (`a/x.csv` and `b/x.csv` get different names, in one batch or across batches, which closes `INGEST-OUTPUT-ORIGIN-NAMING-1`). A member moved to another directory is a different member, by design; two members whose paths differ only by case hash apart.
  * **Every lane names members this way, including single-member batches**, so a member written alone and later re-planned inside a multi-member batch (or the reverse) lands on one name: `memberStem` replaces the bare stem in `consolidatedBaseName` (the lone-survivor case), `NativeCsvStreamingEngine.streamingIngest` and `chunkedIngest` (chunks are `<stem>__<hash>_cNNNNN`), and `GenerationModeIngester` (`<stem>__<hash>_gNNNNN`; with the hash the stage-1 `claimOutputStem` refusal can no longer fire for two different paths).
  * **The consolidated lanes** (`CsvIngestStrategy`, `NativeCsvStreamingEngine.unionStreamingIngest`, `UnionModeIngester` per segment) call one new seam, `ConsignmentIngestStrategy.writeAndTraceEach`: off it is the old single `writeAndTrace` under `consolidatedBaseName`; on, a lone owner of the table writes as before under its member stem, and several owners each get a scratch copy of the table filtered on `__src_id` written under their own stem (so lineage per output file has exactly one srcId; the cost is one extra copy of each member's rows in the scratch DB, dropped right after). Each per-member write carries its own branch-ledger scope `<scope>#m<srcId>` (`MEMBER_SCOPE`; `segmentWrite` strips the suffix): a second write under the batch's scope would read as already committed in the `BranchCommitLog` and its rows would vanish (mutation-checked: dropping the suffix loses the second member's rows in the union lanes).
  * **Not as designed: `DrainCommand` is unchanged.** A parked-branch drain exists only for `route:` pipelines, and per-member naming of a parked branch table would need per-member parking. So the setting is REFUSED together with `route:` at load (`PipelineConfig.requireRunnable`, "output_per_member is not supported with route: branches yet"), and the drain keeps rebuilding `consolidatedBaseName` from the manifest. That also removes the "parked before a flip" case: a Pipeline cannot be flipped on while it has `route:`. Lifting the refusal is the open work (per-member park + drain).
  * **A versioned reference store is never split** (`produces: reference` with `load: upsert|scd2`): its `__v_<batchId>` batch-unique names are the point of that store, so it keeps `consolidatedBaseName` even with the setting on.
  * **Compaction (`PartitionCompactor`, read 2026-10-12; reads by name since 2026-11-13).** It merges every direct-child `*.parquet` older than `min_age_days` once a partition directory holds `min_files` (default 4) of them, into `compacted_<millis>_out.parquet`, journalled, and flips the merged paths to `COMPACTED_AWAY` in `consignment_outputs`. Per-member files are ordinary `*.parquet` children, so merging them is row-safe (a union of the same rows, no loss, no duplicate) and a 4-member batch trips `min_files` after one batch. What it DROPS is the per-member name the failover overwrite relies on: a survivor re-ingesting an already-compacted member writes a fresh `<stem>__<hash>_out.parquet` beside the merged file, so rows duplicate. So with the setting on, `min_age_days` must exceed the failover horizon (lease TTL plus the longest un-acked window); the setting does not enforce it. It reads its candidates with `union_by_name=true`, like every store read, so files of a store that gained a column merge by column name (before 2026-11-13 such a partition either failed to compact or lost the newer columns, depending on which file came first). It reads `*.parquet` only: a CSV Pipeline's per-member files (the shape this setting was tested on) are never compacted, so the setting is small-file growth there with no answer.
  * **Measurement procedure (documented, NOT run; no harness was built).** A 50-member batch over the cricket or telecom fixtures (`batch.max_files: 50`), three event-date partitions, Parquet and CSV, `output_per_member` off vs on. Record per run: the sink wall time (the status CSV's sink stage), the output file count and bytes per partition, the DuckDB scratch peak (`temp` dir high-water mark; on adds one filtered copy per member), the time for `compact` (`min_files: 4`, `min_age_days: 0`) to merge the 50 files of a partition, and a Dataset `SELECT count(*)` over 50 files vs the one compacted file. Write the numbers into the BACKLOG row, not a claim.
  * **Tests.** `OutputPerMemberTest` (seam: two members, two files with stable names; a re-plan with a different member set rewrites the same file; same stem, different directory, also in a later batch; one srcId per output file), `OutputPerMemberEndToEndTest` (the four CSV lanes through `CollectorProcessor.run`), `OutputStemCollisionTest` (generation mode and the multi-schema union lane with the setting on), `KillBeforeAckDuplicateTest.multiFileBatchReplannedAfterKillDoesNotDuplicateWithOutputPerMember` (the pinned hole, flipped: 2 rows; the off-case `knownHole...` test is untouched and still asserts 4), `OutputPerMemberConfigTest` and `ConfigSafetyValidatorTest.outputPerMemberMustBeABoolean`.
  * **Still open before any flip** (the criteria above, minus the two now met): the 50-member measurement, a compaction return to one file with the registry flipped for per-member files, the operator accepting the file-count change, `route:` support with the drain, and a grep that no reader globs `<batchId>_out*`.
  * **Flip decision (2026-10-18, ASSISTANT-DECIDED under operator delegation 2026-10-18; SIGNED by the operator 2026-10-18).** Keep `output_per_member` OFF. The criteria above stay the gate, and since every lane (single-member batches included) takes the `memberStem` name, flipping renames stored outputs of existing Spaces: (sign-off given 2026-10-18 for keeping it OFF; a future flip still needs its own call), and the first flip is for new Pipelines only. Reverse: none needed, nothing flipped. Row: `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1`.

---

## 11. Branch-aware ingest — `route:` on the poll-driven path

**Shipped 2026-08-26** (`b3a8bd40` → `23b9265d`; plan:
[`branch-aware-executor-arming-plan.md`](../../../../archived-documents/plans-archive/branch-aware-executor-arming-plan.md)).
Closes design §13 R3: an **active** pipeline carrying `route:` executes its branch tree on the
ordinary ingest path — the graph editor's route vocabulary finally runs where it is authored.

This section owns the **ingest flat / graph-fork mechanism**; the map of all lanes lives in
[spec-pipeline.md](spec-pipeline.md#execution-lanes).

### How it runs

- **Divert point:** `ConsignmentIngestStrategy.writeAndTrace` — the one choke point every ingest path
  funnels through with the live DuckDB connection and the materialised `transformed` table (the
  connection is strategy-scoped, which is why no higher divert is possible). A `route:` pipeline
  diverts when `ConsignmentGraphRunner.engages(PipelineLift.lift(cfg))`; **since 2026-08-29 a NON-route
  pipeline diverts too** whenever the two lanes are provably the same write — see *The lane fork*
  below. Either way the write segment is replaced by `graphWriteAndTrace`.
- **Machinery:** `ConsignmentGraphRunner.run` (the `SinkWriter` overload) drives `PipelineExecutor` over
  the `route → sinks` subgraph, seeded at the route node's upstream (the map node — parse/map are
  never re-run), committing each branch through a durable per-batch `BranchCommitLog` under
  `dirs.temp` via `BranchCommitCoordinator`.
- **Writes:** `IngestSinkWriter` (com.gamma.inspector) writes each branch to the `sinks[]`
  destination its key was paired with at lift time — matched by the branch's `database`, the same
  join key `PipelineLift.branchKeyForDatabase` uses — with the destination's own
  format/compression/`filename_column`, per-branch `LineageCollector` rows and event-time bounds.
- **Parity by shared code, not mirrors:** the method returns the flat `Written` shape into
  `IngestOutcome`, so `commit`/`finalizeSource`/`writeAudit` — manifest, backup, markers-LAST,
  dedup ledger, watermark, all three CSV ledgers, `ConsignmentEvent` → signals/enrichment, provenance —
  are the SAME code as the flat path. `IngestSinkWriter` deliberately does NOT register §11.3;
  `finalizeSource` registers from the returned lineage exactly as always. The runner's
  once-after-all-branches hook is a documented no-op for the same reason.

### The lane fork (ELT Phase 6 slices A–C2, 2026-08-29)

The graph lane is no longer route-only. `ConsignmentIngestStrategy.graphLaneCarries(cfg)` admits a
non-route pipeline when the write is reproducible there, which is now every shape a pipeline can
actually be armed in: **one or many destinations** (the lift emits a sink node per `sinks[]` entry
and the executor writes each independently — the fan-out gains per-destination crash resumption the
flat loop never had), **a versioned reference store** (the stamp runs before the walk exactly as it
does flat; only the ROUTE combination stays refused, because one version history across branches is
ill-defined), and **several writes per batch**. The seed generalises to `seedFeedingTheWrite` — *the
node whose data relation IS the materialised table* — so the walk performs the WRITE and never
re-runs parse/map. That is also why `withMappingContext` (a `PipelineDryRun`-only patch) is still
not needed here.

Refused, and left flat by name: a node BETWEEN map and sink (`dedup`/`join`/`summarize` — carrying
them means EXECUTING them at rest, which is Stage-2 work), and a pipeline with **no configured
scratch dir**.

**The flag (ELT amendment §6 step 3 / D-2, built 2026-09-02).** `-Dingest.lane=auto|graph|flat`, read
in `ConsignmentIngestStrategy.admittedLift` (the fork's decision, extracted pure so it is testable).
`auto` (default) is the admission above. `graph` **disables the legacy flat lane**: a write the graph lane
cannot carry fails the batch with an `IllegalStateException` naming the pipeline and the reason
(`flatReason` — no scratch dir, a node between map and the write, …) rather
than quietly diverting flat. That is what the "one verification minor" runs with: every remaining
dependency on the flat lane surfaces as a refusal. `flat` is the kill switch (never divert). Any other
value is refused. Pinned by `IngestLaneFlagTest`. ⚠ The deletion of the flat readers stays release-gated
(BACKLOG row 15, D-2) — the flag is its precondition, not its trigger.

Three mechanisms a change here must respect:

1. 🔴 **`writeAndTrace` has four callers and two write a batch in SEVERAL calls** (one per chunk, one
   per segment), reusing the same sink ids against ONE shared branch ledger. Each caller passes a
   **write scope**; `BranchCommitCoordinator` records `<scope>::<branch>` while handing the bare id
   to the writer. Whole-batch callers pass `""` and record exactly the keys they always did, which
   is what keeps the drain — reading bare sink ids back out — and the single-log cleanup working.
2. 🔴 **Decision Rules are a space-registry fact, not a config property**, so the admission cannot
   see them statically. `DecisionRuleApplier.apply` runs ONCE above the fork and its RESULT is part
   of the admission: a Decision Rule that actually routed rows keeps the pipeline flat.
3. 🔴 **`dirs.temp` is optional, and the graph lane keeps a DURABLE ledger.** All three sites that
   spell the log path — create, drain-resume, cleanup — go through
   `ConsignmentIngestStrategy.branchCommitLogPath`, and a pipeline without a scratch dir stays flat rather
   than parking that ledger in a shared `%TEMP%`, where a stale `branch_commit_<batchId>.log` makes
   the coordinator skip the branch and the batch writes NOTHING.

Parity is proven, not asserted: `FlatVsGraphLaneParityTest` runs one materialised table through both
lanes and diffs output files, partitions, rows on disk, the lineage matrix and event-time bounds on
each admitted shape — and the whole reactor now exercises simple pipelines through the graph lane on
every run.

### The engagement predicate

`ConsignmentGraphRunner.dataFedSinkCount` counts **branches**, not sink nodes: distinct `route:*`
relations reaching a SINK-category node, plus one for the trunk when any sink is plain-`data`-fed.
So: flat single-sink = 1 · plain `sinks[2]` fan-out = 1 (N destinations of ONE branch — stays on
`writeAndTrace`'s flat fan-out with its reference-versioning and Decision Rules) · a two-branch
route = 2 · multi-schema selector = 1 (its `route:*` rels terminate at map nodes, not sinks).
⚠ The original node-count predicate engaged for plain fan-out and was refuted by its own
falsification test (`ConsignmentGraphRunnerLiftEngagementTest`); a stale `PipelineLiftTest` pin had
encoded the wrong belief. ⚠ And do not read this predicate as a claim about capability: it answers
*"is there a second BRANCH worth diverting for?"* for the route lane. Whether the graph lane can
perform a given write is `graphLaneCarries`' question, and a plain fan-out — one branch, N
destinations — is carried there since slice B.

### Fail-closed arming (`PipelineConfig.prepare()`)

Each arming check refuses BY NAME a shape that would drop rows silently:

| Check | Why |
|---|---|
| `default:` required, naming a branch key | `mode: case` labels an unmatched row NULL and the executor emits it on NO relation — no default = silent discard |
| every branch has a `database` matching a **distinct** `sinks[]` destination | the branch↔sink pairing is by database; unmatched or shared = a branch whose rows land nowhere |
| ~~`mode: clone` refused~~ | arms since 2026-09-06 — see the decision at the end of this section |
| multi-schema (selector/segments): every branch `where:` must bind in EVERY schema's mapped row | one shared `route:` block applies to every schema (2026-09-24); a predicate on a column only some schemas map would fail mid-run on the others, after the earlier schemas' branches committed — see *Multi-schema route* below |

Runtime refusals in `graphWriteAndTrace`: Decision Rule routing + route branches; a versioned
reference store per branch. Decision Rule routing + `sinks>1` (no `route:`) is carried in both lanes since
2026-10-06 — the routed rows are replicated to every destination ([spec-pipeline.md](spec-pipeline.md#multi-destination-sinks-shipped-2026-08-02--0cdc9dff--79dcb3e6) § *Refusals*).

### Deliberately not built (residuals — BACKLOG §6)

Mid-branch transforms in the recipe's route verb (compiles refused: "a route branch compiles as exactly
one sink step for now"). ~~`mode: clone` arming~~ (2026-09-06) · ~~multi-schema + route~~ (2026-09-24,
below) · ~~the pre-fetch intake cap~~ (2026-11-17, MoSCoW C8: the byte cap runs before a remote fetch when
the listing sizes every file; [acquisition spec](spec-acquisition.md) § *The poll cycle and its two timers*). The flat homes
(`transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest) stay refused; board §6
*Pipelines — execution*.

**The save-time arming pre-check SHIPPED 2026-08-26** — and this section's earlier reason for deferring it
("arming validates at engine LOAD on both server and mock — a 422-on-save would be UX polish, not a
gap") was wrong in the half that mattered. Arming did validate at load, but neither `/validate` nor
`/config/write` calls `prepare()`, so a save returned `written: true` and the operator learned their
branch tree was unarmable at the next run. A fail-closed gate the author never sees is a log line.

### Where the arming checks live

`RouteArming.refusals(route, sinkDatabases, schemaColumns[, mayBind])` (`inspecto-etl`) is the ONE
statement of the checks, with two callers holding the config in two different states. `schemaColumns` is
`null` for a single-schema pipeline, else every schema's name → its MAPPED columns (check 4's input):

| Caller | State | Behaviour |
|---|---|---|
| `PipelineConfig.prepare()` | parsed config, at registration | throws the FIRST refusal — registration is all-or-nothing |
| `ConfigRoutes.routeArmingFindings` | unparsed DRAFT map, at save | reports ALL refusals as `Finding`s; `active: true` ⇒ ERROR (422, nothing written), `active: false` ⇒ WARNING naming when it will bite |

The columns come from different places, deliberately: `prepare()` feeds `TypeFlow.transformedColumns`
per schema (DuckDB-authoritative); the save path feeds each schema file's raw fields ∪ `mapping.fields[]`
(as `routeColumnFindings` models one schema; an unreadable schema is an empty list = not judged) and
passes `mayBind` = its `SqlGuard` check, so an untrusted predicate never reaches the binder unguarded.
Only a genuine unknown-column binder error refuses — `RouteArming.isUnknownColumn`, now the one place
that line is drawn (`ConfigRoutes.isUnknownColumn` delegates to it).

⛔ Do not run the draft through `PipelineConfig.fromMap` to reuse the parsed form: `fromMap`
hard-fails on an unresolvable schema reference, which the save path keeps a WARNING on an inactive
draft (the file may be created after the save) and refuses only when active (`ERR_SCHEMA_FILE_UNRESOLVED`,
[config safety](../config/spec-config-safety.md)) — the draft still has to be judgeable without it. `armedWithoutSchemaFindings`
makes the same call, for the same reason, and says so in its javadoc. The checks take plain data so
both callers can supply it from what they have — restating them over raw maps in the control plane
would be the hand-mirrored-map drift this repo has already paid for three times.

### Multi-schema route — the segment-scoped lift (shipped 2026-09-24)

Design + slice record (archived): [`branch-aware-segment-lift-design.md`](../../../../archived-documents/plans-archive/branch-aware-segment-lift-design.md).
Operator decisions 2026-09-24: **one shared `route:`** for every schema (no per-schema route blocks);
**`<branch database>/<segKey>/…`** landing (one store per branch × segment); a predicate binding in some
schemas only **refuses arming**; the plugin `segments` path and the CSV `schemas[]` selector path ship
**together**; **partial-batch semantics** accepted (below).

- **The walk runs on one schema's slice.** `PipelineLift.scope(graph, key)` is a VIEW over the one lift —
  `map_<key>` plus everything downstream, same node ids and configs (so branch↔sink pairing, ledger keys
  and park ids are unchanged); `null` key = identity. `admittedLift` admits, seeds and walks the scoped
  graph whenever the write's key is known, on the route AND the non-route admission. The STORED graph is
  still the full lift, so `PipelineEditable.lower` and the round-trip are untouched — a scoped lift is never
  a second emitter.
- **The key.** A segments write: `segmentWrite(cfg, writeScope)` (the segment key IS the scope) → the
  lift's `routeKey`. A selector batch: `selectorWrite(cfg, selectedTable)` — the table its schema was
  selected under, passed to `writeAndTrace` as its OWN argument by all three selector call sites
  (`CsvIngestStrategy`, `NativeCsvStreamingEngine.unionStreamingIngest` and `streamUnit`). ⚠ Not through
  `writeScope`: the single-member streaming and chunked lanes already use it for the chunk base name
  (the ledger discriminator), and a selector batch is one schema in one call, so its ledger scope stays
  `""`. ⚠ Keyed for `route:` pipelines only — a non-route selector write keeps its existing (flat)
  admission; widening that lane is a separate change.
- 🔴 **The defect it fixed (D1).** Before, a selector batch knew no key, so the seed was the FIRST
  `transform.route` node's upstream: every non-first schema's rows were routed by the first schema's
  route and written through its sinks — attributed to the wrong Dataset. Pinned in `SegmentScopedRouteTest`.
- **Parking.** `StepDisableArming.parkableSinkIds(route, sinkDbs, schemaKeys)` emits `sink_<key>__d<i>`.
  🔴 Without it, once multi-schema arms, `disabled_steps: [sink__d1]` passes the gate and matches NO lifted
  node — a silently-enabled step. `liftKey` mirrors `PipelineLift.routeKey`, pinned against a real lift
  (`PipelineLiftTest.multiSchemaParkableSinkIdsMatchTheLiftedGraph`).
- **Drain.** `DrainCommand` resolves the schema by manifest: a selector batch's entry is the one named by
  `outputTable`; a segments batch writes each parked sink under its own segment's home
  (`database/<segKey>`, that segment's partitions) — one `IngestSinkWriter` per segment.
- **Partial batch (accepted semantics).** Each segment commits its own branches under ledger scope
  `segKey`. If segment 2 throws after segment 1 committed, the batch is FAILED with segment 1's branch
  files durable; the file stays in the inbox, the ledger survives, and the retry (same content-derived
  Consignment id) skips what the ledger holds — no duplicate rows (`SegmentRouteEndToEndTest`).
  ⚠ **Known gap, pinned in that test (not introduced here — a single-schema route whose second branch
  fails resumes the same way):** the resumed commit never sees the skipped branches' outputs, so its
  manifest — and the DuckLake register / §11.3 output registry fed from it — omits them. Their rows are
  durable and the lineage LEDGER is whole (the failed run's audit wrote it); only the output registry is
  short. The park path solved this with the `ParkedCommit` sidecar; the failure path has none.
- ⚠ `ConsignmentGraphRunner.engages` / `dataFedSinkCount` still exclude a multi-schema parser's
  `route:<key>` dispatch edges; on a scoped graph there are none left to exclude.
- **Arming (check 4) is mutation-checked.** Re-inserting the old blanket multi-schema refusal turned 7 tests
  red when S4 was built (the slice record's count, not re-run since), so the per-schema bind check cannot silently regress to a refusal.
- ⚠ **The end-to-end "kill after segment 1" is a write failure, not a JVM kill.** `SegmentRouteEndToEndTest`
  blocks segment 2's branch directory with a file, lets the batch FAIL after segment 1 committed, removes
  the blocker and runs the next cycle. That leaves the same durable state a kill does (ledger, committed
  files, inbox file), minus the FAILED audit row. Its fixture is a test-resources copy of the demo
  `stock_movements` Pipeline with a `route:` on `QTY` — ⛔ never a `spaces/demo` edit.

**Why the walk was already scoped, and why lifting the refusal alone was still wrong.** Since the
per-segment `writeAndTrace` calls (2026-09-16), a segments walk seeded at `map_<key>` reached only that
segment's route and sinks, because `PipelineExecutor` skips every node with no live inbound relation. That
scoping was an accident of the seed. Everything that reasons about the graph before and after the walk —
the seed choice for a selector batch (D1), the route admission, arming, crash drain, partial-batch retry —
was not scoped, which is what the slices fixed.

**Options not taken (2026-09-24).** *Fix only the seed and delete the refusal* — rejected: it leaves
admission, arming, drain and retry unscoped, and scoping stays an emergent property of a skip rule nothing
tests for this purpose. *Per-schema `route:` blocks* (`segments.<key>.route` / `schemas[i].route`) — not
built, because the operator chose one shared block. It stays available as an ADDITIVE config home if
per-segment routing semantics are ever wanted; it would need a new config home, spec/JSON-schema, editor,
lowering and recipe-grammar changes.

### Traps pinned along the way

- `PipelineLift.stageTwo` is the **at-rest Stage-2 chain** lift (refuses without `output_store:`);
  the ingest-topology lift is `PipelineLift.lift(cfg)`.
- `PartitionSinkWriter` is Pipeline-Job-shaped (one `dataDir/store` root, no lineage, self-registers
  §11.3) — never reuse it for ingest destinations.
- A TOON tuple row with an **unquoted Windows path** fails as "Array length mismatch … found 0"
  (the drive colon); quote every path cell.

### Decision 2026-09-06 — `mode: clone` arms, and committed branches become visible

B9's constraint was that nothing surfaces partial-commit state. The `BranchCommitLog` is already durable per
`(batch, branch, phase)`; only the read surface was missing. **Decided:** arm `clone` and merge
`committedBranches[]` / `sourceFinalized` into `GET /runs/{name}/batches` beside the park detail, rendered on the
Batches tab. **Shipped 2026-09-06:** `RouteArming` no longer refuses `clone`; `RunRoutes.withParkDetail` merges
`committedBranches[]` + `sourceFinalized` onto any batch row whose branch commit log still exists (a fully
committed batch deletes its log, so only unfinished ones carry the keys); the batch dialog lists them.

---

## 12. Contract tests

`ConsignmentIngestorPluginTest` (`inspecto-engine`; named `BatchProcessorPluginTest` until the 2026-08-31 rename — the wrap-SPI page carried the old name until 2026-09-08) is the end-to-end pin: a toy
`StreamingFileIngester` drives records through the whole wrap into a **partitioned** store
(CALL/SMS segments, `event_type/year/month/day`) and — `unkeyedSegmentWritesAFlatStoreWithLineage`
— into an **unpartitioned flat** store, asserting rows, layout (no sentinel), and the lineage
ledger. The concurrency claims of the finalization stores are pinned at the ledger by
`ConsignmentAuditWriterTest.concurrentFlushesKeepEachBatchBlockContiguous` (E4, narrowed — see the plan),
and since 2026-08-19 by **`FinalizeSourceConcurrencyTest`** (the E4 remainder, ex-BACKLOG §4 (b)):
8 distinct batches finalizing concurrently through the SHARED `DbConsignmentOutputStore` /
`DbAcquisitionLedger` / `DbFileStageStore` (registry reconciliation, ledger PROCESSED, the full
crash-ordered stage trail, one manifest per batch), plus the same-file marker race — exactly ONE
loser, on the atomic `Files.createFile`. ⚠ The race fixture deliberately has no backup dir (a
same-file `Files.move` race throws platform-dependent exceptions before the marker), and the
harness pins exactly the stores' own `synchronized`+one-connection claim — a future connection
pool must re-run it.

---

## Part II — Consignments

**What this covers.** A Consignment is a set of files ingested and processed together as one unit of
work. This part answers two operational questions about them. Part A: once Consignments have written
their output files, how does a reader pick the right files — skipping old revisions after a recompute —
and how can anything tell which files cover a time window and whether that window is complete? Part B:
how many Consignments may run at once, per Pipeline, per Space and per server, how priority shares the
free slots without starving anyone, and which settings change live.

Vocabulary follows [`GLOSSARY.md`](../../../../GLOSSARY.md): **Consignment**, **Pipeline**, **Collector**,
and §6-B (**Consignment Selector**, **Watermark**).

## Part A — Addressing

> **Scope:** how a read decides *which files it names*, and how a Consignment's event-time extent is
> recorded so anything can answer "which files can contain this window" and "has this window closed".
> As-built from the consignment-addressing plan, delivered 2026-08-10
> ([archived plan](../../../../archived-documents/plans-archive/consignment-addressing-plan.md)).

> **⚠️ Keep this current.** Derived from `ConsignmentSelector`, `StreamWatermark`,
> `ConsignmentOutputs`, `DbConsignmentOutputStore`, `PartitionSinkWriter`, `DataTransformer` and
> `MaintenanceJob`. The catalog's DDL lives in [spec-db-layer.md §3.9](../engine/spec-db-layer.md); the retirement task in
> [operations-reference.md](../../layer/platform/admin-guide-operations-reference.md).

## A.1 The one rule everything follows

**An optional index may never be the authority for what exists.** The `consignment_outputs` catalog is
fail-open by construction — a configured `none` or a failed open leaves no registry, and the
per-Consignment JSON manifest stays authoritative for a file's *existence* while the catalog is
authoritative for its *state*. Every design decision below falls out of that:

- The Selector **filters** a glob; it never produces the file list.
- A file with **no catalog row stays in** the list. Unknown is a possible match, never an exclusion.
- Null bounds mean **unknown**, never *empty*.

The practical consequence is that switching the catalog on changes nothing a reader sees, and an
installation whose data predates the registry needs no backfill. There is no migration.

## A.2 Event time at write

Bounds are recorded per **output file**. Two write paths can establish them, each from a declaration it
already holds — neither ever infers which column is temporal, because a relation's only `TIMESTAMP`
column is not evidence that it *is* event time:

| Path | Source of event time | Absent when |
|---|---|---|
| Ingest (`ConsignmentIngestor` → `DataTransformer`) | `__event_time`, coerced from the schema's date `PartitionDef` and **excluded from written output** | no date partition declared, or every row failed to parse |
| Pipeline sink (`PartitionSinkWriter`) | `TRY_CAST(<source> AS TIMESTAMP)`, where `source` is a `partitions[]` entry's declared raw column | no entry declares one, entries disagree, or it is not a plain identifier |

Enrichment and the Consignment processor **record a producer since 2026-08-10**, and both record bounds since
2026-08-11 — but by opposite mechanisms, because they are opposite problems:

| Writer | `producer` | `bounds` |
|---|---|---|
| Enrichment (`EnrichmentEngine`) | the enrichment's own `cfg.name()`, on the main **and** routed/quarantine writes | **SHIPPED 2026-08-11.** An `output.partitions` entry may be the sink's `{column, source}` map instead of a bare name; the parser folds the declared sources to `Output.eventTimeSource` and `boundsOf` runs the same `TRY_CAST(<source> AS TIMESTAMP)` fold as the sink |
| Consignment processor (`ConsignmentProcessJobType`) | the **processor id**, threaded through `SummaryWriter.write` | **SHIPPED 2026-08-11 — stated, not derived.** `SummaryRow` gained an optional `EventTimeBounds bounds`; `SummaryWriter` folds the declarations per output file. See A.2-A |

⚠ **Neither writer was ever missing its registry row** — both always called `ConsignmentOutputStores.record`; the
two columns just arrived null. Worth knowing before someone goes looking for an absent write path.

⚠ **The producer is not the table.** Enrichment's routed/quarantine write goes to `dest`, which more than one
enrichment can write, so `dest` would be a useless producer; and the process job's producer is the *processor*,
not the Job Type, because two processors can summarise one target and `producerHighWater` groups by producer.

### A.2-A Summary bounds are *stated by the processor*, never derived

Every other writer folds bounds **out of** the rows it is writing. A summary row has no rows — it is already an
aggregate — so the only party that ever saw the detail is the processor that aggregated it. That inverts the
mechanism: `SummaryRow` carries an optional `EventTimeBounds bounds`, built with `EventTimeBounds.of(min, max)`,
and `SummaryWriter.boundsByPartition` folds those declarations per output file.

⛔ **Do not "simplify" this into a pointer at a grain key** (the shape the backlog originally proposed: "declare
which key is event time"). `record_day` is the *bucket* the rows fell into, so reading it as an event time
collapses a whole day to its first instant, and the Selector then **skips a file that does overlap the query** —
a false negative, strictly worse than the null a reader correctly treats as "cannot prune". A grain key is only
a correct event time in the degenerate case where the grain *is* an instant, which the stated form expresses
anyway as `of(t, t)`.

Three properties fall out, each pinned by a test:

- **A file is bounded only when every row in it declared a range.** One silent row means the file holds events
  outside the fold, and a bound that under-covers its file is exactly the false negative above. One undeclared
  row drops the whole file's bound. *(Verified by disabling the check and watching the test go red.)*
- **`spreadMs` is derived, never stated.** `EventTimeBounds.of` computes it, `GuardedSummaryEmitter` refuses a
  hand-built bounds whose spread disagrees with its own endpoints, and the per-file fold re-derives it from the
  folded endpoints rather than summing per-row spreads (which would double-count overlap).
- **The endpoints must be ISO-8601 local date-*time*.** Enforced at `emit()`, not at write time, because the
  registry compares them lexicographically in SQL — a bare `2026-07-01` would compare wrong rather than fail,
  and it is precisely the grain-as-event-time mistake arriving in another costume.

Absence stays legal and stays the default: a processor that declares nothing records `null`, exactly as every
processor written before this did. Nothing in the `ConsignmentProcessor` interface changed, so the three in-repo
implementers needed no edit — ⚠ contrary to this item's "breaking for every implementation" framing, which
assumed the pointer shape. `tools/templates/processor/` was updated anyway, because its job is to *teach* the
capability: a new processor that never learns bounds exist is the failure the template exists to prevent.

⚠ **The enrichment and sink declarations are the same shape but NOT the same reader.** `EnrichmentConfig`
folds its own copy (`eventTimeSourceOf`) because it parses `.toon` into a record at load time, while
`SinkPartitions` reads a node config map at write time. They are kept behaviourally identical on purpose —
same four null cases, same identifier guard — so an author who writes one declaration gets one answer. If a
third writer ever wants it, extract the fold rather than adding a third copy.

The sink declaration has **one reader**: `SinkPartitions` (`pipeline/exec`), shared by the writer that acts on
it and the `ComponentPreview` that predicts what it will do. Keeping that logic in both files let them disagree
twice in one day, each time costing an author their bounds with no signal anywhere — so `eventTimeSource` is
derived from `declaredSources` rather than parsing the list again, and the preview warns at authoring time on
exactly the declarations the writer refuses: none declared, two that disagree, one blank, one that is not a
plain identifier. Deliberately **not** unified with `PartitionDef.fromSchema` on the ingest side — same config
word, different contract (`type` required per entry, non-list hard-fails, legacy `partitionKey` fallback; see
[§3](#3-segment-schemas-and-the-partition-declaration) above).

⚠ An entry declaring **no `column`** (or a blank one) is the single `partitions[]` defect this path refuses
rather than degrades past. It names no directory segment, so accepting it silently writes the store to a layout
its readers do not glob for; it previously stringified the whole entry into a directory named `{source=…}`.
`PartitionSinkWriter` throws before writing a byte, and the preview warns while the config is still editable.

⚠ **Why not the dataset's `role: temporal`?** Because reaching a dataset *from* a store name is a
reverse lookup that is ambiguous by construction (`putIfAbsent`, first-scan-wins), so the bounds would
depend on directory scan order. `DatasetRelation.temporalColumn` therefore has no caller on any write
path, and the general principle is: **a write path must read a declaration it already holds.**

`record_day` is derived from these bounds when a file's event times share a day, else from the
`year`/`month`/`day` partition segments. It is superseded — one day per file cannot express a file that
straddles two — and nothing in the engine reads it.

## A.3 The Selector

```
resolve(glob) = glob  MINUS  paths the catalog marks SUPERSEDED / COMPACTED_AWAY
```

Two enumerators, because only one of the seven readers in the product takes a `Connection`:

- **With a connection** (`SourceStoreReader`) — DuckDB's own `glob()` expands the very pattern the read
  will use, so the two cannot disagree about what exists. This is the better mechanism.
- **Without one** (`DatasetRelation`, whose `relationSql` has call sites in three modules) — a
  filesystem walk, kept to exactly the `<root>/**/*.<ext>` shape all readers use. It **skips hidden
  segments**: `PartitionCompactor`'s safety model depends on its intermediates being invisible to
  readers' globs, and a walk that promoted a `.staging/` tree into an explicit list would make data
  appear in reads that was never there.

Both sides of the path comparison go through the same normaliser, because the registry stores the
writer's own spelling — which may be relative — while an enumerator answers in its own. Comparing raw
would match nothing and report success.

**A path with any `LIVE` row is never excluded**, whatever dead rows also name it: output naming is not
one-file-per-Consignment, so one path can own an old `SUPERSEDED` row and a current `LIVE` one.

⛔ **Not a pruner.** Bounds-based window pruning is deliberately unbuilt: a file whose bounds miss the
window contains no matching rows, so the query's own predicate already excludes them — identical
answers, pure performance, and the measured ceiling was 1.3–2.6× wall-clock (rung A) against 29–88×
fewer rows scanned, because DuckDB already skips row groups on Parquet statistics.

✅ **Torn multi-file reads across a recompute — CLOSED 2026-08-29.** Subtraction always fixed *stale
inclusion*; the still-open half was that an empty exclusion set fell back to the caller's own **live
glob string**, which DuckDB re-expands against the filesystem at actual scan time — so a file written by
a concurrent recompute between `resolve()`'s enumeration and the query's own scan could be silently
pulled into a read the catalog never approved. `ConsignmentSelector` now **always pins** the enumerated
file list to an explicit array once any registry exists, falling back to the live glob only when there
is no registry at all or enumeration itself fails. ⚠ **Accepted tradeoff:** the SQL always carries an
explicit file array now (no longer a bare glob string in the common no-exclusions case), and a pinned
list can fail loudly if `retire_superseded` deletes one of its files mid-read, where the old glob
fallback would have silently rescanned around it — judged the lesser risk against a silent wrong answer.
Regression-pinned by `ConsignmentSelectorTest.aFileWrittenAfterResolveIsInvisibleToTheAlreadyPinnedRead`:
a file written after `resolve()` runs is provably absent from the pinned read it returns.

### Which readers are filtered, and which deliberately are not (2026-08-28)

Filtering belongs on readers of **pipeline sink** stores — the only stores a full recompute can leave an
old revision in. Traced per reader:

| Reader | Filtered? | Why |
|---|---|---|
| `SourceStoreReader` | yes — `ConsignmentSelector.resolve` | reads a sink store on a connection |
| `DatasetRelation` (`physicalRef`) | yes — `sourceLiteral` (walks; no connection in scope) | the dataset read of a sink store |
| `DatasetRelation` (`view`) + `ViewQuery` | yes — **rendered at read time**, see below | executes a *persisted* definition |
| `DbBrowserRoutes.browseStore`, `ExpectationEvaluator` | yes — `sourceLiteral` (since 2026-09-06) | both read a pipeline sink store and were missing from this table: the Data Browser re-globbed at scan time and showed a superseded revision during a recompute; an Expectation counted it |
| `EnrichmentEngine` ×2, `ConsignmentIngestStrategy` | ⛔ no, correctly | read Stage-1 ingest output / the ingest-written reference store — append-only, never `supersedeOtherRevisions` targets, so no catalog-marked file can appear |
| `ReferenceCompactor` | ⛔ no, correctly | owns an equivalent safety model (`*.refcompact.tmp` / `*.parquet.refcompacting` + journal) — a second authority, not a missing one |

**A persisted read has to be rendered, not stored.** `PipelineJobRunner.deriveViewSql` builds a
`sink.view`'s SQL once and it is executed arbitrarily later, so the source read is written as the
`ViewReaderSql.READER_TOKEN` (`{{reader}}`) placeholder with `reader_root`/`reader_format` recorded
beside it, and every executor renders it through the Selector at read time. The two alternatives are
both wrong in opposite directions, which is why the template exists: a **baked-in glob** keeps reading a
revision the catalog has since superseded (silent double-counting — the defect this closed), and a
**baked-in file list** breaks loudly the moment retention deletes those files. Plain SQL — hand-authored
views, definitions written before the template — executes verbatim, so this needed no migration; a
templated definition that has lost its reader fields **refuses**, because rendering it unfiltered would
reintroduce exactly the staleness it exists to remove.

## A.4 Revisions — how a recompute stays safe

A full pipeline recompute used to rewrite its sink files **in place**. That was atomic per file, so a
reader saw either the old bytes or the new — and it was load-bearing, not merely a risk, because every
reader globs. Ending it therefore required the Selector to land **first**.

The current model:

1. The sink base name always carries the **batch id**, so a recompute writes a new revision beside the
   old one. Not a `<generation>` counter: that needs durable state the optional registry cannot
   guarantee, `_g<N>_` is already `DuckDbRecordSink`'s spelling for a memory-bounded flush chunk, and a
   batch-derived name preserves what the old stable name existed for — a same-batch-id replay still
   rewrites its own path, so it stays idempotent. The registry's never-written `generation` column was
   deleted on 2026-09-24 for the same reason ([../engine/spec-db-layer.md](../engine/spec-db-layer.md) §3.9) — nothing here pins by generation.
2. `supersedeOtherRevisions(table, keep)` marks earlier revisions dead. **Full recomputes only** — an
   incremental run appends a slice, so superseding there would discard every increment before it.
   `keep` is required: omitting it would mark the recompute's own fresh files stale and empty the table.
3. Nothing is deleted at flip time. The `retire_superseded` maintenance task removes the bytes of files
   older than an explicit `retention_days`, deleting **files and keeping rows** — a row for a deleted file
   is harmless, while dropping the row of a file still on disk would let it back into every glob.
   ⚠ **`retention_days` counts from the file's last write, not from when it was superseded** — the
   registry stores no supersede time. A revision written 30 days ago and superseded today goes at the next
   pass of a 7-day job. What lets a read already in flight finish on the revision it started with is
   `SnapshotSwapLock.writeAbove`: the delete waits for every reader holding a store above the file
   (`SnapshotSwapLockTest.retireSupersededWaitsForAReaderHoldingTheStoreBeforeItDeletesAFile`), not the
   retention window.

⚠ **Without a `retire_superseded` job defined, every full recompute leaves a complete extra copy of its
output on disk permanently.** The old overwrite was O(1) disk; this is not. **No longer silent
(2026-08-29):** `PipelineJobRunner` now warns naming the affected store(s) the moment step 2 actually
supersedes something and no enabled `retire_superseded` job is configured — see
[operations-reference.md](../../layer/platform/admin-guide-operations-reference.md) for the wiring. A copyable example ships
**disabled** at `spaces/demo/config/jobs/retire_superseded_job.toon` (2026-09-23): the task reads only
`retention_days` and walks the process-wide output registry, so it carries **no** path or store key.

⛔ **Off by default — decided 2026-11-17 (MoSCoW C7), do not re-open without the operator.** The task is
safe to run: the delete waits for readers (`writeAbove`, above), it never deletes a path any `LIVE` row
still names, however old (`unreadablePaths`;
`MaintenanceLibraryTest.retireSupersededSparesAnAgedFileThatALiveRowStillNames`), and the only `SIMULATED`
row writer, `DryRunSinkWriter`, records a placeholder path rather than a real file. It stays off because the product's retention principle is *"retention is never on by default: every
deleting task is inert until an operator defines a Job"* ([operations-reference.md](../../layer/platform/admin-guide-operations-reference.md)),
and `retire_superseded` would be the one exception, armed in every Space. Two concrete surprises it would
cause: on the first pass it would delete every superseded revision a pre-release 4.0.0 install has kept
since 2026-08-10, and because retention counts from write time, a revision an operator meant to keep "for a
week" after a recompute could go within minutes of it. What 4.0.0 does instead: the WARN above, the
disabled demo job to copy, and the release note in
[plan-api-stability](../api/plan-api-stability.md) (no 3.x release keeps superseded revisions — 3.x
overwrote in place — so the disk growth is new at the upgrade). Pinned by
`DemoRetireSupersededJobShipsDisabledTest.shipsDisabledSoTheDefaultIsNotFlipped` and
`JobServiceTest.retireSupersededCheckIsWiredFromLiveJobConfigNotJustBuildTime`.

## A.5 The Watermark — completeness

Overlap answers "which files *can* contain this window". Completeness-needing checks (non-monotonic,
absence, gap) also need "has this window *closed*", which is undefined without a watermark:

```
watermark(stream) = min over producers of max(event_time_max)      -- stream key = table_name
window [lo, hi) is complete when watermark >= hi + allowed_lateness
```

Derived on read, never stored. `min over producers` rather than a plain `max` because parallel producers
into one table are the normal CDR case: one producer running ahead must not close a window a slower one
still owes rows inside.

- **`SUPERSEDED` excluded, `COMPACTED_AWAY` included.** The two non-live states are opposites here:
  compacted data was genuinely delivered and still exists inside the merged file, so dropping it would
  make the watermark travel *backwards* on compaction.
- **Observed within a horizon** (24 h default) rather than a declared producer set, which goes stale
  silently — a decommissioned producer nobody removed would freeze the stream forever.
- **Conservative in every ambiguous direction.** Staleness must be *proven* (an unparseable `written_at`
  keeps its producer in the set), and any in-horizon producer with unknown event time suppresses the
  answer. Advancing too far closes a window while rows are still owed, which fires an absence check on
  data that was merely late; not advancing is merely no answer.

Absent means **unknown**, so completeness-needing checks do not fire rather than firing on a guess. A
table written by enrichment or the Consignment processor has a watermark only when its writer declares
bounds (A.2: the enrichment's `{column, source}` partition entry, the processor's stated `EventTimeBounds`);
both record a producer since 2026-08-10.

⚠ **Built, not yet consumed (re-grounded 2026-11-17).** No production code calls `StreamWatermark.of` or
`DbConsignmentOutputStore.producerHighWater`; only their tests do. No shipped Alert Rule or Job asks "has this
window closed" yet, so the properties above describe the primitive, not a running check.

⚠ **Vocabulary.** *Watermark* is reserved for this concept. `PipelineWatermarkStore` is an incremental
**read cursor** and `$job.last_success_time` is **wall clock**; neither answers window close.

### A.5-A Reading the range from a Job

A downstream Job binds its window to what a predecessor produced through two Expression attrs, **resolved
live against this registry** at fire time:

```
$upstream(<job>).artifact(<store>).event_time_min | .event_time_max
```

`DbConsignmentOutputStore.bounds(table)` folds `min(event_time_min)`/`max(event_time_max)` over the same
predicate the watermark uses — `SUPERSEDED` excluded, `COMPACTED_AWAY` included — and returns **empty rather
than half a window**, because a caller handed one end would silently scan to the epoch.

Three properties are deliberate:

- **Derived, never stored.** A.4's revisions mean a recompute writes a new revision and supersedes the old, so
  a range copied onto the artifact at write time would go on describing superseded data. Reading it here
  makes the answer move when the data does.
- **Keyed on the sink `store` name**, the one identifier that is both `RunArtifact.ref` and this table's
  `table_name`. `PipelineJobRunner` records one artifact per store it wrote for exactly this purpose; before
  2026-08-10 it recorded none, so the accessor had no handle to key off.
- **They yield strings, not instants.** The stored bounds are zone-less local date-times (A.2), so an
  `INSTANT`-typed parameter would reject them — but each end is a valid SQL timestamp literal in the
  single-quoted form the substituter produces, which is what binding a window actually requires.

⛔ **The retired `time_range` attr is not coming back.** It yielded one opaque `"<min>..<max>"` string that
nothing split, so it could never be bound to a predicate; it was always `null` besides. Addressing step 8 was
closed on that basis rather than built. As-built detail:
[`archived-documents/plans-archive/job-parameter-contract-plan.md`](../../../../archived-documents/plans-archive/job-parameter-contract-plan.md) §5-B, step 17.

### A.5-B Ingest-written streams — no accessor, by design (closed 2026-11-17, MoSCoW C7)

The board carried "the ingest-side Consignment-scoped accessor waits for a consumer" from 2026-08-10 to
2026-11-17. ⚠ **That accessor was never code.** It was the option the job-parameter contract deferred
(archived plan §5-B, point 4): an Expression that would read the event-time range of a store written by
**ingest** rather than by a Job. Grounded 2026-11-17:

- **Ingest records bounds already.** `ConsignmentIngestor.finalizeSource` writes registry rows keyed on the
  Consignment's table, with bounds from the schema's date partition (A.2), so the data such an accessor would
  read is there.
- **No Expression can name it.** `$upstream(<job>).artifact(<store>)` resolves through a Job's last
  successful Run (`JobService.latestArtifacts`). A Collector's ingest is not a Job and records no Run
  Artifact, so the attrs have no handle on an ingest-written table — the gap the deferral named.
- **Nothing asks for it.** No Space, example, template or UI uses `event_time_min`/`event_time_max` on an
  ingest table; the Job editor's parameter catalog lists only the `$upstream` form. The completeness
  primitive that would read ingest streams (A.5) has no consumer either.

**Decision: not built.** Adding a second token (for example a stream-keyed `$stream(<table>).event_time_max`)
now would be a surface with no caller and a second word for one concept. Build it when a real consumer
appears — a Job that must bind its window to what a Collector landed, or a completeness check over an ingest
stream — and key it on the registry `table_name`, never on a store→dataset reverse lookup (A.2). The board
row is closed; this section is the reopen trigger.

## A.6 Three storage invariants that are cheap now and unfixable later

**A partition-affecting config must be PINNED in the manifest at load time.** Any setting that decides which
partition a row lands in (the partition expression, the event-time field, the time zone) is recorded with the
Consignment and **reprocessing must use the pinned value, never the current config**. Otherwise editing a
pipeline silently re-partitions only the rows reprocessed after the edit — *partition drift*, which reads as
data corruption and has no cheap repair. ⚠ This is trivial to add before there is data and effectively
impossible to retrofit after.

**No output file ever contains rows from two Consignments.** One-directional: one Consignment may write many
files, but a file belongs to exactly one. This is what makes a Consignment's output deletable by unlinking
whole files, and it is why compaction (which merges across Consignments) is the one operation that has to be
handled specially — see [post-sync step chains](spec-pipeline.md#post-sync-step-chains-the-open-dag).

**Two kinds of delete, only one of which is safe.** Unlinking a whole file owned by a single Consignment is
fine. **Rewriting** a file that holds several Consignments' rows to remove one of them is the read-modify-write
this model exists to avoid — it is the operation that loses data when it fails halfway.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

---

## Part B — Concurrency (the four-layer hierarchy)

As-built after the scheduler-system-config plan (shipped 2026-08-25, commits `1b872515` → `275bf764`;
[archived plan](../../../../archived-documents/plans-archive/scheduler-system-config-plan.md)).

## B.1 The grain rule everything follows

**A permit counts a Consignment being executed — never a Pipeline, never a run.**

The operator constraint is host I/O and CPU, and the unit of heavy work is a Consignment being
parsed → transformed → written. A run that is planning, or parked waiting for a slot, costs
essentially nothing. Because every tier counts the same unit, `3` / `12` / `16` are comparable
numbers rather than three different things wearing one name.

| Layer | Cap | Where configured | Grain |
|---|---|---|---|
| Per Pipeline | `processing.threads` | pipeline TOON (editor: sink node) | Consignments of ONE pipeline |
| Per space | `max_concurrent_consignments` | space `scheduler.toon` · Settings ▸ Scheduler | across that space |
| Per server | `max_concurrent_consignments` | system `scheduler.toon` · Settings ▸ Scheduler | across the whole process |
| Share weight | `processing.priority` (1–3) | pipeline TOON (editor: sink node) | who wins a freed slot |
| Named pool | `pools:` (name → cap) · chosen by `processing.pool` | **system** `scheduler.toon` · `PUT /system/scheduler` | Consignments admitted in that pool (B.2a) |

⚠ **`processing.threads` IS the per-pipeline Consignment cap.** The name predates the concept and is
kept only because renaming it is a GLOSSARY §13 decision; its help text says what it means.

## B.2 `ConcurrencyBroker` — one monitor, three counters, weighted grants

Process-wide singleton (`shared()`/`use()`, the `IntakeGovernor` idiom). A worker calls
`admit(space, pipeline, cap, priority)` before heavy work and closes the returned `Permit` after;
`admit` parks the virtual thread until all three tiers have room.

- **Per-pipeline FIFO queues** — Consignment arrival order within a Pipeline is preserved by
  construction (which is what makes the `mtime` planner ordering mean anything downstream).
- **Stride scheduling** for grants: `stride = 6 / priority`, lowest accumulated `pass` wins. Priority
  3 gets ~3× the throughput share of priority 1 **and priority 1 provably keeps a non-zero share** —
  weights are *shares*, never precedence. Deterministic, so the ratio is unit-testable.
- **No banked credit**: a pipeline returning from idle joins at the current virtual time, so it can
  neither burst on saved-up credit nor be punished for having been quiet.
- **Inert unless configured**: with no space/system cap the only bound is the pipeline's own cap —
  byte-identical to the run-local `Semaphore(threads)` this replaced (`CollectorProcessor.ingest`).

⛔ **Three refused designs** (do not re-propose):

- **A global priority-ordered queue.** Strict priority ordering *is* the starvation trap: a
  priority-3 pipeline with a 100k-file backlog owns the head forever. It also reorders Consignments
  within a Pipeline, breaking arrival order.
- **A separate consignment-generation service.** Planning stays in the poll cycle; only *admission
  to execution* is shared.
- **Nested semaphores per tier.** No global view for the fairness decision, and cross-tier ordering
  is a deadlock hazard. One monitor guarding three counters instead.

## B.2a Named execution pools — admission only (2026-09-24, `DUCKLE-C10-ADMISSION-POOLS-1`)

A fourth broker tier, with five binding properties (adopted 2026-09-15, scale-out plan §4.1; built 2026-09-24):

1. **Admission only.** A pool answers *"may this Consignment start now"*; `grantNext` skips a waiter
   whose pool is full, so a pool can only refuse a grant the other tiers allow. It **never widens**
   `processing.threads`, a space cap, the server cap or any memory limit (pinned by
   `ConcurrencyBrokerPoolTest.aPoolNeverWidensThePipelineOrServerCap`).
2. **Server-defined only.** Pools live in the **server-wide** `scheduler.toon` as `pools: {name: cap}`
   (`0` = unbounded), installed at boot and hot-applied by `PUT /system/scheduler` `{"pools":{…}}`
   (absent = preserve, `null`/`{}` = clear, any bad name or cap 422s the whole write). Never in a
   space document. A Pipeline only **chooses** one with `processing.pool` (sink node ▸ *Execution
   pool*); the validator checks only the name's shape (`ConfigSafetyValidator.POOL_NAME`, shared by
   both sides). **Unknown ⇒ `default`** (`ConcurrencyBroker.resolvePool`) — neither a refusal nor an
   implicit new pool. `default` always exists and is unbounded unless the server caps it.
3. **Waiting is a recorded state.** `admit(space, pipeline, pool, cap, priority, ticketId)` takes the
   Consignment id (`batchId`) as the ticket id, so the admission is listed in `snapshot().admissions`
   **before** it is granted: `state: queued` with a `queueReason` naming the tier that held it
   (`pool 'heavy' full (2 in use)`, `pipeline cap reached …`, `space cap …`, `server cap …`), then
   `state: running` with `queueMs`. The `Permit` carries the same (`id()`, `queueReason()`,
   `queueMs()`), and `CollectorProcessor` logs every admission that queued. The listing is capped at
   100 rows with `admissions_total` / `admissions_truncated` beside it, because a cycle submits every
   Consignment up front and they all park on `admit`.
4. **⛔ A supervisor takes no slot.** There is no API to admit a dispatcher. `CollectorProcessor`'s
   batch loop submits every Consignment and waits on the futures holding **no** permit; a supervisor
   holding one while its children need the same pool deadlocks at exactly the pool size. Pinned both
   ways: `aSupervisorHoldingNoSlotCompletesItsChildrenInAPoolOfOne` (with a timeout) and its
   counterfactual `theCounterfactualSupervisorHoldingASlotWedgesItsChild`, plus the real dispatcher
   in `CollectorProcessorPoolTest` (four Consignments through a pool of one, under a timeout).
5. **Metric = free permits per pool**: `freePermits(pool)` (`null` when unbounded — "not applicable"
   stays distinct from a real zero), `snapshot().pools.<name>.{cap,in_flight,free}` on
   `GET /system/scheduler` `live.pools`, and the Prometheus gauge `inspecto_pool_free_permits{pool=…}`
   (bounded pools only).

⚠ **Deliberate limits.** The ticket list is JVM-local and in memory, like every broker counter — the
id is durable because it is the Consignment's, not because the queue is persisted; moving admission
state to Postgres is phase B's U2 work. A gauge series for a pool the server has since removed keeps its
last value until restart (`MetricRegistry` has no series removal). `JobService`'s Run bound
(B.3) is **not** pooled.

## B.3 ⛔ The run budget is NOT redundant with the broker

`PipelineScheduler.runPermits` (`-Dservice.max.runs`) looks like a second budget for the same lane.
It is not, and **retiring it would be a real regression** — a claim the plan itself made and the code
refuted (2026-08-25).

The broker admits **Consignment execution**. `runPermits` is the only bound on everything a cycle
does **before** the first broker permit is taken (`CollectorProcessor.ingest`):

- the stale-marker sweep,
- `collect()` discovery,
- **checksum dedup, which reads whole files** (`ledgerFilter` → `Checksums.of`),
- **archive expansion, which writes to temp and is itself virtual-thread parallel**
  (`UnpackStage.expand`),
- the planner's per-file `length()`.

Remove it and every pipeline hashes and unpacks simultaneously with nothing bounding the disk. **The
two budgets govern different *phases* of a run, not the same fact twice.**

## B.4 Hot-apply: what changes live, and how a shrink behaves

Everything in the table below applies to the running server. **A shrink drains, never interrupts** —
Consignments already executing finish and the new ceiling gates the next admissions. Aborting a
mid-commit ingest is not something the commit sequence was designed for (it is crash-*idempotent*,
which is a different property).

| Knob | Mechanism |
|---|---|
| system / space Consignment caps | `ConcurrencyBroker.setSystemCap` / `setSpaceCap` |
| `poll_seconds`, `acquire_poll_seconds` (per space) | hold the `ScheduledFuture`, `cancel(false)`, re-register one interval out |
| intake globals (`intake_*`) | `IntakeGovernor.setGlobalPolicy` |
| `processing.*` (threads, priority, batch, intake) | already live — re-read per run via `ConfigRegistry.rebuild` |

⚠ **A cadence change must retarget the governor's overrun threshold** (`PipelineScheduler
.pollIntervalMs`). The T15 controller measures overrun *against the poll interval*; leaving it stale
throttles pipelines against a budget no longer in force.

⚠ **A changed `IntakeGovernor` policy clears every learned cap** — each was learned under the old
thresholds and could sit above the new base or below the new floor. An unchanged re-install is a
no-op, so the per-cycle re-install does not disturb adaptation.

## B.5 Precedence and provenance (the "two declarations" rule)

A **stored key** in `scheduler.toon` is the source of truth; the `-D` flag is a **bootstrap default
consulted only while that key is absent**. Provenance follows the **key, never file presence**
(fixed 2026-08-26): a document storing only the intake globals or the resource pair leaves the cap
owned by `-Dscheduler.max.consignments` — before the fix, saving *anything* on the form wrote the
effective cap into the file and silently seized its provenance to `file` forever, so the next
deploy's changed flag did nothing. The cap is now nullable end-to-end (absent key = inherit, stated
`0` = unbounded), the UI seeds it only from `source === 'file'`, and an explicit `null` in a PUT
hands ownership back to the flag, live. `GET /system/scheduler` reports, per key, the effective
value **and** its `source` (`file` | `property` | `default`), so two declarations can never leave an
operator guessing which won.

⛔ **No key may be writable through the settings document and also read from `-D` at use time.** A
knob is either file-sourced (with `-D` as its absent-file default) or it stays `-D`-only. Split
ownership of one fact is what the 2026-08-15 operational-db decision forbids — and is why
`/system/operational-db` correctly still has no PUT while `/system/scheduler` does.

**A validation grammar is served, never mirrored.** The DuckDB memory-limit grammar the PUT gates on —
an absolute size (`2GB`) or a proportion of host RAM (`80%`, operator ask 2026-08-26) — is
published by the same GET as `duckdbMemoryLimitPattern` (`SchedulerRoutes.MEMORY_LIMIT_PATTERN`), and
the form installs it — the UI holds **no regex of its own**. Before this the grammar lived in two
hand-maintained copies that were identical when measured and unpinned, i.e. a drift where the form
accepts what the server refuses. ⚠ One string can only serve both sides if it stays **portable**: no
inline `(?i)` (the browser compiles it with `new RegExp(p, 'i')`, and JS has no inline-flag construct
— it throws) and **explicitly anchored**, because `String.matches` anchors implicitly while
`RegExp.test` does not — unanchored, the form would accept `2GB of RAM`. Both properties are pinned by
a test, since neither is visible at the call site. ⚠ A **semantic bound must live inside the pattern**,
not beside it: the percentage form is bounded `1..100` within the grammar, because a separate server-side
range check would let the form accept a `500%` the PUT refuses — reintroducing the very drift this
replaces. An absent or uncompilable pattern degrades to
blank-only validation and lets the 422 speak; a client-side fallback guess would just be the mirroring
again. ⚠ The offline mock was the one legitimate place to copy the gate — it *stood in for* the server, so it had
to gate identically — and it had **no resource-pair gate at all** until this landed, accepting `"lots"`
where the server 422s. *(That mock was deleted 2026-08-31, so the server's gate is now the only gate; what is worth keeping is the divergence as a failure class, not the mirroring.)*

**A PUT merges per key**: absent = preserve stored, explicit `null` = clear and revert live to the
`-D` default. ⚠ A cap-only PUT that rewrote the whole document would silently destroy a stored
cadence — caught in review, and the same wipe existed in the offline mock.

**Every change is journalled** (operator decision 2026-08-26). The generic `AuditTrail` already
classifies these PUTs and records who / when / from where / status; what a path-classified audit row
cannot carry is *what the numbers became*, which is the half an incident review actually asks for. So
a `SCHEDULER_SETTINGS_CHANGED` event carries the **deltas** — one attribute per changed key rendered
`"<old> -> <new>"`, plus `tier` and `scope` — alongside the actor. Three properties, each pinned by a test:

- **Only an actual change is journalled.** A re-save of identical settings emits nothing; a trail that
  logs unchanged re-saves teaches an investigator to skim past it.
- **Only the keys that moved appear** in the delta, so the entry reads as the change it was.
- **An unset value renders `inherit`, not `null`** — that is what it means, and the trail is read by
  people.

⚠ It emits to **`api.service().eventLog()`** (the bound space's log), not `EventLog.current()`: a
hosted space has its own `EventLog`, while `current()` routes by the thread's space MDC and falls
back to global — so a bare `/system/scheduler` call would file the entry in a log the operator's
`/events` view never reads. That mis-filing is exactly what the first version did, and the test
caught it. Same seam `PipelineRenameRoutes` uses for `PIPELINE_RENAMED`. Journalling is best-effort: a
settings change that succeeded must never fail on its own audit entry.

## B.6 Two mechanisms, two questions — `IntakeGovernor` vs the broker

They compose; neither replaces the other.

- **Broker** — *how many Consignments may execute right now* (instantaneous slots).
- **IntakeGovernor** — *how many files one poll cycle may admit* (backlog fairness across time),
  halving a pipeline's cap while its runs overrun the poll interval and restoring it when they fit
  (defaults and flags: [architecture.md](architecture.md) §4.8).

The governor's signal is **cycle overrun, not inbox lag**: admitting fewer files cannot reduce inbox
age or pending depth, so throttling on those is positive feedback that pins a healthy-but-backlogged
pipeline at the floor. Inbox lag stays an *alerting* surface, never a throttle input.

That pairing is what drains a 100k-file backlog fairly: bounded runs, permit released between them,
other pipelines interleaving at the run boundaries.

## B.7 Per-Pipeline cadence and fetch parallelism

- **Cadence** — `trigger: {every: 30s|5m|1d}` or `cron:`, gated per tick by
  `PipelineScheduler.dueThisTick`. Cron wins when both are stated. ⚠ **The space's poll tick is the
  resolution floor**: a 30s pipeline needs the space tick at ≤ 30s. Authored on the acquisition node
  (`trigger__every` / `trigger__cron`) — these are **top-level `trigger:` keys the node borrows**,
  not `collector:` block keys.
- **Fetch parallelism** — `collector.fetch.parallel_fetch` (default 1 = sequential) switches one
  pipeline's download loop to a bounded **connector-session pool** in `RemoteAcquisitionHandler`;
  each worker gets its own session because connectors are not thread-safe. `fetch.rate_limit` caps
  bandwidth. ⚠ **Remote Collectors only** — a local inbox has nothing to download; files are pushed
  in by the producer, and the poll cycle just discovers them (with the stability window guarding
  against a half-written file, and `discovery: watch` for immediate pickup). Collector configuration
  as a whole is owned by [spec-acquisition.md](spec-acquisition.md).
- Acquisition is **already continuous**: its own timer, its own per-pipeline guard, overlapping its
  pipeline's ingest, with two fetches of the same pipeline skipped rather than queued.

## B.8 Operating it

`ConfigValidator` warns when `scheduler cap × duckdb_threads > cores`. ⚠ It reads the **installed
fleet cap** via `ConfigValidator.fleetConsignmentCap(IntSupplier)` — the etl module sits below the
engine and cannot see the broker, so `CollectorService`'s constructor installs
`ConcurrencyBroker.shared()::systemCap`. The CLI orchestrator installs nothing and keeps its
`-Dsources.max` check. (Before 2026-08-25 the check read only `sources.max` and was therefore silent
in server mode — the one deployment shape that runs a fleet.)

`GET /system/scheduler`'s `live` block answers *what is waiting and why*: `system_free` (**`null` when
unbounded — a fake `0` reads as "wedged"**), per-pipeline in-flight/waiting/priority, and `throttled`
— the pipelines the governor has admitted **below** their base cap, joined in the route because the
broker cannot see the governor. Only actually-throttled rows appear, hard-capped at 50 with the true
total beside it. Rendered on **Settings ▸ Scheduler** (`canOperateRuns`).

## B.9 Decision 2026-09-06 — the fetch lane stays FIFO

`processing.priority` weights run admission through `ConcurrencyBroker`; `acquirePermits` stays a plain
semaphore. No fetch-lane contention has been observed. **Decided:** keep FIFO; the trigger to revisit is the
first observed fetch-lane wait, at which point a second `ConcurrencyBroker` instance on the fetch lane is a
small change. ⛔ `PipelineScheduler.runPermits` is not retired. Recorded as a standing decision (BACKLOG §6).

---

## Part III — Parsing

**What this covers.** Parsing is how the bytes of a file become rows. Inspecto pushes as many formats as
possible onto DuckDB's own fast readers (CSV, JSON, Excel, Parquet, fixed-width and regex-carved text) and
uses Java decoders only for binary or tree-shaped formats such as ASN.1 call records and XML. Whatever
reads the file, the same backend then types, maps, partitions and audits the rows. This part lists every
parse option the engine reads, gives copy-paste starting points per format, and describes the plugin
framework that lets a new format be deployed and configured with no UI change.

> ⚠ **"Frontend" names three different things.** As a *mechanism* there are three (DuckDB-native read,
> DuckDB-native + SQL regex, Java `StreamingFileIngester`). As a **legal `parsing.frontend` config
> value** there are ten tokens / eight distinct formats (`PipelineConfigParser.FRONTENDS`, §P.5).
> As a **registered `ParserPlugin` id** there are six built-ins (`BuiltinParsers.IDS`) plus any
> `ServiceLoader` providers, plus parsers from allowlisted Job Packs (2026-09-25; §P.11). Read "exactly three" as a mechanism claim, never as a config constraint.
> How the parsed rows are written (the ingest SPI, union/generation modes, segments) is
> [Part I](#part-i--ingest) above. Authoring Grammars in the UI is
> [grammar-config](../config/user-guide-grammar-config.md); the requirement of record is the ING
> capability spec in [the ELT plan](plan.md) (§3.3, §3.9).

**Status legend** used throughout:
`[LIVE]` = exposed by the engine today · `[NATIVE]` = DuckDB can do it, engine doesn't surface it
yet · `[PLUGIN]` = reachable today via a `StreamingFileIngester` · `[PROPOSED]` = config grammar
in this doc, not yet implemented.

## P.1 The unifying model: a parser *frontend* feeding the DuckDB *backend*

Every format converges on the **same** typing/transform/partition backend (mapping + transform +
partition + lineage). Only the **frontend** (how raw bytes become rows) changes. There are exactly three
frontend *mechanisms* (the config's `frontend:` tokens in §P.5 are a finer set — one token per format,
several formats per mechanism):

```
                                         ┌──────────────────────────────────────────┐
 delimited / SQL*Plus  ──► read_csv  ──► │                                            │
 JSON / NDJSON         ──► read_json ──► │  raw VARCHAR rows in a DuckDB temp table   │
 fixed-width / LDIF /  ──► read_text ──► │                  │                         │
   simple XML / KV         + SQL regex   │   mapping.rules[] (TransformCompiler)      │
                                         │                  │  typed scalar exprs     │
 ASN.1 / BER-TLV /     ──► StreamingFile │   partitions[]   ▼  (year/month/day/…)     │
   custom binary /         Ingester      │   ──► Hive-partitioned Parquet/CSV + lineage│
   true XML / complex      .emit(record) │                                            │
                                         └──────────────────────────────────────────┘
        FRONTEND (format-specific)                BACKEND (shared, DuckDB-powered)
```

1. **DuckDB-native `read_csv`** — delimited files, SQL*Plus dumps.
2. **`read_json`/`read_text` + SQL regex** — JSON, fixed-width, LDIF, key-value, flat XML. The
   DuckDB-native frontends (`read_csv`, `read_json`, `read_text`/`read_blob`) parse the bytes *inside*
   DuckDB. Fast, vectorized, zero Java parsing. Good for delimited, JSON, and
   "regular-enough-to-regex" text.
3. **The plugin frontend** (`StreamingFileIngester`, [§ Plugin ingester](#plugin-ingester) above)
   decodes in Java and `emit()`s records. The *only* path for genuinely binary/grammar-driven formats —
   ASN.1/BER-TLV, proprietary CDRs, deeply nested XML.

**Key consequence:** "use DuckDB as a parser extensively" is the right instinct — push as many
formats as possible onto `read_csv`/`read_json`/`read_text` and reserve the Java plugin for true
binary. The backend (mapping + partition + lineage + audit) is identical regardless of frontend, so
a format's onboarding cost is *only* its frontend.

This part is also the companion to `docs/Parsing Options Reference.pdf`. The PDF is an excellent
**generic DuckDB `read_csv` cheat-sheet**; this part (1) **validates and corrects** that material against
current DuckDB, (2) grounds it in **this engine's actual config + SPI**, and (3) **extends** it to every
format — delimited, fixed-width, semi-structured, JSON, XML, LDIF, and binary (ASN.1, custom).

---

## P.2 What the engine exposes **today** vs. raw DuckDB

The native CSV path (`DuckDbCsvIngester.buildReadSpec`) emits a **fixed** `read_csv` call:

```sql
read_csv('<file>',
  columns={'c0':'VARCHAR', 'c1':'VARCHAR', ...},   -- width = maxSelector+1, all VARCHAR
  delim='<csv_settings.delimiter>',
  header=false,
  skip=<skip_header_lines + (has_header?1:0)>,
  -- optional, only when set: encoding=, compression=, nullstr=[…], strict_mode=,
  --                          quote=, escape=, comment=   (delimited frontend only)
  ignore_errors=true,
  null_padding=false,
  auto_detect=false,
  store_rejects=true)
```

Files are read all-VARCHAR; typing happens at transform time via `TRY_STRPTIME`. The `auto` engine uses
native `read_csv` unless `skip_junk_lines`/`skip_tail_lines`/`skip_tail_columns` force the Java
`CsvIngester` path (messy files). Native is ~4–5× faster. The config knobs that actually reach DuckDB
today are **only**:

| `csv_settings` key | → `read_csv` param | Status |
|---|---|---|
| `delimiter` | `delim` | `[LIVE]` |
| `has_header` + `skip_header_lines` | `skip` (combined) | `[LIVE]` |
| `engine` (`auto`/`duckdb`/`java`) | selects native vs Java path | `[LIVE]` |
| `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns` | **Java path only** (route away from native) | `[LIVE]` |
| `date_formats`, `timestamp_formats` | used at **transform** time (`TRY_STRPTIME`), *not* at read time | `[LIVE]` |
| `quote`, `escape`, `comment` | `quote`/`escape`/`comment` (single char, validated fail-closed at load; both engines) | `[LIVE]` (5.2) |
| `encoding`, `compression`, `null_strings`, `strict_mode` | `encoding`/`compression`/`nullstr`/`strict_mode` | `[LIVE]` (4.1) |

Everything else in the PDF — `decimal_separator`, `sample_size`, `auto_type_candidates`, `names`,
`union_by_name`, `hive_partitioning`, read-time `dateformat` — is **`[NATIVE]`**: DuckDB supports
it, but it is **not reachable from config** (deliberately: ingest is all-VARCHAR; typing is the
mapping's job).

> Type detection difference worth internalizing: this engine reads **everything as VARCHAR** and
> types later via `mapping.rules[]` + `TRY_STRPTIME`/`TRY_CAST` (so a bad value becomes `NULL`, never
> a failed batch). DuckDB's own sniffer/`dateformat`/`decimal_separator` are therefore mostly
> redundant here — by design. Read-time typing is only worth exposing for `read_json` (where types
> are structural) and for performance edge cases.

### P.2.1 The honoured `raw.fields[].type` vocabulary (2026-08-22)

`SchemaFieldTypes` (`inspecto-etl`) is the **one** authority on which declared field types the engine
produces and the SQL each compiles to. Three sites used to carry their own copy of a three-type list
— `TransformCompiler.direct` (the cast switch), `TransformCompiler.dateExpr` (date-vs-else), and
`DataTransformer.coercedSourceColumn` (cast-failure measurability) — and all three now delegate.

**Honoured** (every DuckDB scalar type reachable by `TRY_CAST` from text): `VARCHAR`, `BOOLEAN`,
`TINYINT`/`SMALLINT`/`INTEGER`/`BIGINT`/`HUGEINT` and their `U…` unsigned twins, `FLOAT`, `DOUBLE`,
`DECIMAL(p,s)` (precision 1‥38, scale 0‥p), `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ`, `UUID`,
`BLOB`.

**Refused, fail closed** — anything else, at config load (`Identifiers.validateSchema`). Nested
`LIST`/`STRUCT`/`MAP`/`UNION` (a parsed text token cannot become one), `JSON` (extension-dependent,
and this deployment does not statically link DuckDB extensions), `INTERVAL`/`BIT`/`ENUM` (no sane
text form off a feed).

🔴 **Why fail closed.** Before this, the cast switch had a `default` branch that emitted the column
**uncast**, and nothing validated the declared type — so `type: BIGINT` silently produced a VARCHAR
store, surfacing only when something downstream tried arithmetic on it. A typo is now a load error.
An **absent or blank** type stays legal and still means `VARCHAR`.

Two consequences worth knowing:
- `DATE`/`TIMESTAMP`/`TIMESTAMPTZ` parse through the pipeline's `date_formats`/`timestamp_formats`
  after a `CAST(… AS VARCHAR)` (load-bearing: an already-typed column from the plugin lane has to
  reach `TRY_STRPTIME` as text). Everything else is a plain `TRY_CAST`, keeping the "bad value ⇒
  NULL, never a failed batch" contract.
- A config that declared `INTEGER` before this change produced text and now produces a real
  `INTEGER`, so its output Parquet schema changes on the next write — the declaration finally being
  honoured, but it can trip the schema BACKWARD gate.

### P.2.2 Epoch-count field formats (`MAPPING-EPOCH-TIMESTAMP-1`, 2026-11-15)

A field's own `raw.fields[].format` is a strptime pattern, with two **reserved values** that are not:
`epoch` (a whole count of seconds since 1970-01-01T00:00:00Z) and `epoch_ms` (milliseconds); case-insensitive.
They replace the `sql` step with `to_timestamp(x::BIGINT)` and a `0` guard that a 10-digit epoch feed needed.
`SchemaFieldTypes.epochSql` is the one compilation, used by the data column (`castSql`), its `DATE_*`
partition and event time (`TransformCompiler.dateExpr`) and the schema preview (`ComponentPreview`).

| Decision | Behaviour | Why |
|---|---|---|
| Type | honoured on `TIMESTAMP` only; `DATE`, `TIMESTAMPTZ` and every other type are **refused at load** (`Identifiers.validateFieldFormat`) | a `DATE` would need a zone to pick the calendar day, and a `TIMESTAMPTZ` demands a source zone it would not use |
| Time zone | lands as the **UTC wall clock** via `make_timestamp(µs)` (naive, session-independent); `parsing.source_timezone` does not shift it, and a field `timezone` / `timezone_column` beside it is **refused** as dead config | an epoch is already an instant; `to_timestamp(x)::TIMESTAMP` would render in DuckDB's session zone, which is the host's (measured: `+05:30` on an `Asia/Calcutta` host, moving the day partition) |
| `0` and blank | NULL, and **not** counted by the cast-failure audit (`epochNoneSql`); whitespace-only counts as blank | `0` is the feed's "none", like a blank |
| Invalid | anything not `[+-]?[0-9]+` after trimming spaces, tabs and CR/LF, or out of the `TIMESTAMP` range, is NULL, the row kept and counted — never a failed batch | the policy a strptime miss already has; a bare `TRY_CAST` would round `1700000000.5` and accept `1.7e9`; DuckDB's one-argument `TRIM` strips spaces only, so the trim names its characters |
| Range | guarded on the **count**: `epoch` −9223372022400 ‥ 9223372036854, `epoch_ms` −9223372022400000 ‥ 9223372036854775 — the µs range `make_timestamp` renders on DuckDB 2.0.0.0, 290309-12-22 (BC) ‥ 294247-01-10 | measured: below the minimum `make_timestamp` returns a value `TRY` does **not** catch and that fails later on read ("Date out of range in timestamp conversion"); inside the bounds the multiplication cannot overflow |
| xlsx | an epoch field is never rewritten as an Excel day-serial (`DuckDbCsvIngester.xlsxSerialAsText`) | the rewrite turned `0` into 1899-12-30 and any count under 2958466 into an unrelated date |

⚠ Recognition is "the field's ONLY format is `epoch`/`epoch_ms`", which a field `format` always is; a pipeline
`timestamp_formats` list of exactly that one entry would read the same way, but only the field key is
documented and validated. Fractional seconds are not supported (a `sql` step still covers them).

---

## P.3 Corrected & extended DuckDB `read_csv` parameter reference

Supersedes the PDF's §1–§5 tables. Verified against DuckDB 1.x (the engine bundles 1.5.6; measurements below were probed on 1.5.2.1).

### P.3.1 Dialect & structure
| Param | Type | Default | Notes |
|---|---|---|---|
| `delim` / `sep` | VARCHAR | `,` | Multi-byte allowed. |
| `header` | BOOL | auto | First non-skipped row = names. |
| `skip` | BIGINT | 0 | Physical lines skipped at top, **before** header detection. |
| `quote` | VARCHAR | `"` | |
| `escape` | VARCHAR | `"` | |
| `comment` | VARCHAR | `''` | Lines starting with prefix dropped. DuckDB ≥ 0.10. |
| `new_line` | VARCHAR | auto | `\n` / `\r` / `\r\n`. |
| `encoding` | VARCHAR | `utf-8` | `utf-16`, `latin-1` common in Oracle/telco dumps. |
| `compression` | VARCHAR | auto | `auto`/`gzip`/`zstd`/`none` — needed for `.csv.gz`. |
| `max_line_size` | BIGINT | 2000000 | Raise for very wide rows (537-col vouchers, packed records). |
| `parallel` | BOOL | true | |

### P.3.2 Schema & types — **the `columns`/`types`/`names` distinction the PDF gets wrong**
| Param | Effect |
|---|---|
| `columns={name:type,…}` | Forces **names + types** and **disables the sniffer entirely**. This is what the engine uses (all `VARCHAR`). |
| `types=[…]` or `{name:type}` | Overrides types **positionally or by name**, keeps sniffing the rest. |
| `names=[…]` | Overrides **names only**. |
| `all_varchar` | BOOL, skip type detection → all VARCHAR. (Equivalent to the engine's hard-coded `columns`.) |
| `auto_detect` | BOOL — master switch for the sniffer. Engine sets `false`. |
| `sample_size` | BIGINT (default 20480; `-1` = whole file). |
| `auto_type_candidates` | LIST of types the sniffer may pick. |

### P.3.3 Errors / messy data / footers
| Param | Notes |
|---|---|
| `ignore_errors` | Drop unparseable rows instead of failing the query. Engine sets `true`. |
| `null_padding` | Pad short rows with NULL on the right. Engine sets `false` for delimited; the line readers (fixed-width, regex) default to `true` (short rows → reject). |
| `strict_mode` | BOOL, default `true`; `false` tolerates quote/column drift. |
| `nullstr` / `null` | LIST → SQL NULL, e.g. `['','NULL','NaN','N/A','-']`. |
| `store_rejects` | BOOL — **the engine's real error channel.** Rejected rows land in `reject_errors`/`reject_scans`, drained to `errors/<base>_errors.csv`. Missing from the PDF. |
| `rejects_table`, `rejects_scan`, `rejects_limit` | Tune the reject tables. |

🔴 **For `ignore_errors`, `null_padding` and `store_rejects`, ABSENT is not `false`.** A blank means *the
engine's historical default*, which is explicitly a different thing from an authored `false` — so writing
`false` to "make it explicit" can change behaviour. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*

**Decision 2026-09-06 — `strict_mode` is a three-way choice.** `ignore_errors` and `store_rejects` carry
real defaults since 2026-09-04; `strict_mode` stayed a blank-means-engine-default boolean and rendered as
an OFF-looking toggle while the engine default is ON. **Decided:** render it as a select — *Engine default
(on)* / *On* / *Off* — the blank-option idiom the engine picker already uses, so the default writes
nothing. ⛔ Not `default: true` (that would stamp every saved grammar). Build: `parsing-attributes.ts` +
spec (BACKLOG §3 `STRICT-MODE-1`).

### P.3.4 Format (read-time typing — mostly redundant in this engine, see §P.2 note)
`dateformat`, `timestampformat`, `decimal_separator`.

### P.3.5 Multi-file
`filename` (inject source path), `hive_partitioning` (dir → columns), `union_by_name` (align by
header across files). Note: this engine already does its own batching + Hive output, so these are
mainly for ad-hoc analyst SQL, not the ingest path.

### P.3.6 Diagnostics
`FROM sniff_csv('file')` returns the dialect DuckDB *would* pick — invaluable when authoring a new
profile. Keep the PDF's Recipe C.

---

## P.4 Beyond CSV — the other DuckDB-native readers

### P.4.1 JSON / NDJSON — semi-structured
```sql
-- newline-delimited JSON (one object per line) → typed columns
FROM read_ndjson('events.jsonl', columns={id:'BIGINT', ts:'TIMESTAMP', payload:'JSON'});
-- nested arrays/objects: read as JSON, then unnest / json_extract in mapping rules
FROM read_json('nested.json', maximum_object_size=104857600);
```
Pairs with `mapping.rules[]` of `transformType: EXPR` using `json_extract(payload,'$.a.b')`,
`UNNEST`, etc. **Frontend = `read_json`; backend unchanged.** As a configured frontend it is `[LIVE]` (§P.6.4).

### P.4.2 Whole-file readers — the gateway to fixed-width, LDIF, KV, flat XML `[NATIVE]`
```sql
read_text('f.dat')  -- → one row, column `content` VARCHAR (whole file)
read_blob('f.bin')  -- → one row, column `content` BLOB
```
With `read_text` + `string_split(content,'\n')` + `UNNEST` you get one row per physical line, then
`substring`/`regexp_extract` carve fields. This turns DuckDB into a positional/regex parser for any
*line-regular* text format without writing Java. (The shipped fixed-width and text_regex frontends use
the streaming `read_csv` single-column form instead — §P.6.3.)

---

## P.5 The `parsing:` block `[LIVE]`

A single top-level `parsing:` block per pipeline selects a **frontend** and its options. It
generalizes `csv_settings` (which remains the `delimited` frontend — `parsing.delimited`
and `processing.csv_settings` are aliases, `parsing:` keys win) and reuses the existing
`raw.fields[]` / `mapping.rules[]` / `partitions[]` backend verbatim. A config with no `parsing:` block
parses exactly as before. An unknown `parsing.frontend` is rejected at load.

**Where a Grammar lives.** Since 2026-07-07 a Grammar lives **inline** on its parse Step in the unified
`parsing:` block; `processing.csv_settings` is a read alias `parsing:` wins over; a reusable Grammar is a
**Grammar Template** in the `grammar` component kind — a copy source, never a binding (2026-08-15; GLOSSARY
§P.5). Grammars/schemas are also editable as reusable [components](../processor/spec-component-registry.md).
*(History: v4.1 externalised these per-data-file-type settings — `delimiter`, `quote`, `escape`,
`encoding`, `compression`, `strict_mode`, `null_strings`, boundary pre-scan — into a reusable
`*.grammar.toon` that many event-type schemas could reference, implemented in
`PipelineConfig.resolveGrammar`, `CsvSettings`, `DuckDbCsvIngester.readOptions`/`filterWhere`, and
`BoundaryScanner`; that account is superseded by the inline model above. The design of record is archived at
[`delimited-grammar-design.md`](../../../../archived-documents/plans-archive/delimited-grammar-design.md).)*

**Row-level filters.** A grammar can carry filters injected post-parse as WHERE predicates —
`include_prefixes` (anchored prefix allow-list), `include_regex` (allow), `exclude_regex` (deny). ⚠ Author
scalar lists in TOON with the tabular form `key[N]: v1, v2` — JToon reads a YAML-style `["a","b"]` as one
literal string; omit the key entirely for an empty list.

⚠ **The plugin ingester has a THREE-tier config home, not two** (`PipelineConfigParser.parsePlugin`). One block is chosen **whole**, then each key falls back to the legacy triple:
1. inline `parsing.plugin.{ingester,ingester_config,segments}`;
2. else the referenced Grammar component's own top-level `plugin:` map — reachable only when that
   grammar file is *block-shaped* (`isParsingBlock`: it carries a `delimited:` or a
   `plugin:` map), because a Grammar component is just an extracted `parsing:` block;
3. else the legacy `processing.ingester` / `processing.ingester_config` / `processing.segments` triple.
Tiers 1 and 2 are never merged. A fourth spelling, `frontend: asn1`, **synthesizes** the block from the
`asn1:` map and **refuses** a co-present `parsing.plugin` / `processing.ingester`.
A plugin ingester with no `segments` is refused at config load, and `frontend: plugin`
with no `ingester` anywhere is refused. The class must be on the fat-JAR classpath (or in an allowlisted
Job Pack, §P.11) with a public no-arg constructor, and segment keys must match the schema.

```yaml
parsing:
  frontend: delimited        # delimited | fixedwidth (fixed_width) | json | text_regex | xlsx (excel) | parquet | asn1 | plugin
  # ── shared options ──────────────────────────────────────────────────────────
  encoding: utf-8            # utf-8 | utf-16 | latin-1
  compression: auto          # auto | gzip | zstd | none

  # ── frontend: delimited (csv_settings, plus the [NATIVE] knobs) ──────────────
  delimited:
    delimiter: ","
    quote: "\""        # ⚠ TOON has no single-quote string delimiter — '"' parses as the LITERAL
                        # three characters '"' , not a quoted ". Escape the double quote instead
                        # (probed 2026-08-20 while authoring spaces/default/config/csv_example/).
    escape: '"'
    comment: "#"
    has_header: true
    skip_header_lines: 0
    skip_junk_lines: 0       # adaptive preamble scan (Java path); -1 = unlimited (SQL*Plus)
    skip_tail_lines: 0       # footer drop ("N rows selected.")
    skip_tail_columns: 0
    null_strings: ["", "NULL", "NaN", "N/A"]
    strict_mode: true        # false → tolerate quote/column drift (the engine reads strict_mode, NOT strict)
    engine: auto             # auto | duckdb | java

  # ── frontend: fixedwidth (positional slices; text = read_csv 1-col + substring) ──
  fixedwidth:
    record: line             # line | bytes
    record_length: 0         # for record: bytes (0 = newline-delimited)
    fields[3]{name,start,length}:    # 0-based start, char length
      ACCOUNT_NUMBER, 0,  16
      EVENT_DATE,     16, 20
      AMOUNT,         36, 12
    trim: true

  # ── frontend: json ───────────────────────────────────────────────────────────
  json:
    format: newline          # newline (NDJSON) | array | auto
    records_path: "$"        # "$" = the document IS the record array; or a dotted
                             # path to a nested array, e.g. payload.records
    columns:                 # optional explicit typing; else all JSON
      id: BIGINT
      ts: TIMESTAMP

  # ── frontend: text_regex (one record per line/block, named capture groups) ───
  text_regex:
    record_split: "\n"       # line | "\n\n" (blank-line blocks, e.g. LDIF entries)
    pattern: "^(?P<key>[A-Z_]+): (?P<value>.*)$"
    # OR a positional capture → field map via mapping.rules[] EXPR over regexp_extract

  # ── frontend: asn1 (first-class BER/DER, definition-surface P3c) ─────────────
  # Sugar for the plugin wiring below: at load PipelineConfigParser#asn1PluginBlock synthesizes
  # the Asn1RecordIngester binding, so everything downstream is the one plugin path. The grammar
  # is INLINE X.680 text (carried as ingester_config.grammar_text) OR a stored .asn file
  # (grammar_file, carried as-authored as ingester_config.grammar and resolved BESIDE this config,
  # like schema_file); text wins when both are set, and preview + ingest read both through the one
  # Asn1GrammarSource, which checks the extension and jails the file once.
  # An explicit plugin: block alongside is refused (which binding wins would be undefined).
  asn1:
    grammar: "CDR DEFINITIONS ::= BEGIN ... END"   # inline X.680 module text, OR
    grammar_file: msc_cdr.asn   # a stored .asn/.asn1 module beside this config (one required)
    root_type: CallEventRecord                     # required
    strictness: BER            # BER (default) | DER | CER
    file_header_length: 0      # bytes skipped once at file start (e.g. 50, Huawei framing)
    record_header_length: 0    # bytes preceding each record's TLV (e.g. 4)
    max_value_bytes: 67108864  # optional cap on ONE primitive value (default 64 MiB); a larger
                               # declared length fails the record (and so the file) before allocation
    segments:                  # {recordName: schemaPath}, required to ingest
      moCallRecord: config/cdr/mo_call_schema.toon

  # ── frontend: plugin (binary / grammar-driven) ───────────────────────────────
  plugin:
    ingester: com.acme.etl.Asn1CdrIngester     # StreamingFileIngester FQCN
    segments:                # multi-event-type output (see spec-ingest.md)
      CALL: config/cdr/call_schema.toon
      SMS:  config/cdr/sms_schema.toon
    ingester_config:         # free-form, plugin-specific (the "grammar" pointer)
      asn1_grammar: config/cdr/ber.asn1
      byte_order: big
      record_tag: "0xA1"
```

**Mapping to reality:** all **10**<!--count:parsing-frontend-tokens--> `parsing.frontend` tokens are **implemented**: `delimited` (= `csv_settings`), `plugin` (= `processing.ingester`/`segments`), `fixedwidth` (native
`read_csv`+`substring`; binary via the shipped `com.gamma.ingester.FixedWidthRecordIngester`
plugin — §P.6.3), `json` (`read_ndjson`/`read_json`, selectors = top-level JSON keys — §P.6.4), and
`text_regex` (`read_csv` 1-col + `regexp_extract` named groups, selectors = group names — §P.6.5),
`xlsx`/`excel` (DuckDB `excel` extension, three-layer fail-closed load), `parquet` (native),
and `asn1` (synthesized `Asn1RecordIngester` binding, grammar inline — P3c).
**Selector rule:** for `delimited` and `fixedwidth` a `raw.fields[].selector` is a zero-based column INDEX (`"0"`, `"1"`, ...); a name there is refused at config load (and by `POST /validate`, 422) naming the Pipeline, the column and the value.
`text_regex.record_split` is `[LIVE]` too (`blank_line` or any literal delimiter — shipped, see
§P.6.6; verified in code 2026-08-28). `quote`/`escape`/`comment` are `[LIVE]`
since 5.2 (single-char, validated fail-closed at load, honored by both engines).
⚠ **An unknown `delimited.*` key is NOT rejected at load** — `mergeParsing` flattens the block with
no key allow-list, so an unread key is carried but silently ignored. Only an unknown
`parsing.frontend` and a malformed known key (e.g. a multi-char `quote`) fail the load. Author from
the served grammar schema (`GET /parsers`), which lists exactly the keys the engine reads.

---

## P.6 Per-format profiles (copy-paste starting points)

### P.6.1 Standard delimited CSV/TSV `[LIVE]`
```yaml
parsing: { frontend: delimited, delimited: { delimiter: ",", has_header: true, engine: auto } }
```

### P.6.2 SQL*Plus / Oracle spool dump `[LIVE]`
Banner + `Enter password:` + `ERROR/ORA-…` preamble + `N rows selected.` footer. See the adaptive
junk-scan in `CsvIngester`.
```yaml
parsing:
  frontend: delimited
  encoding: latin-1                 # Oracle dumps are often not UTF-8 ([LIVE] since 4.1)
  delimited:
    delimiter: ","
    engine: java                    # skip_* force the Java path anyway
    has_header: true
    skip_junk_lines: -1             # unlimited adaptive preamble scan
    skip_tail_lines: 2              # "N rows selected." + blank
# Oracle DD-MON-YYYY dates handled at transform time:
#   date_formats / timestamp_formats: "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"
```

### P.6.3 Fixed-width / fixed-length records `[LIVE]`
No native DuckDB fixed-width reader, so the engine carves slices itself. Two routes, both shipped:
- **Fixed-width text** (`frontend: fixedwidth`, `record: line`) — **native, no Java parser.** Each
  physical line is read into a single VARCHAR column via `read_csv` (empty `delim`/`quote`/`escape`,
  so a line is never split or quote-merged — streaming, gz-aware, reject-capturing), then each schema
  field is carved with `substring(line, start+1, length)`. Slice index = `raw.fields[].selector`, so
  the event `_schema.toon` is authored exactly like a CSV file and the typing/partition/lineage
  backend runs unchanged. `min_record_length` (default = the widest slice end) drops blank lines,
  banners, and footers.
  ```sql
  -- generated for a 3-field layout (start/length per field); 0-based start → substring is 1-based
  SELECT trim(substring("line", 1, 16))  AS "ACCOUNT_NUMBER",
         trim(substring("line", 17, 20)) AS "EVENT_DATE",
         trim(substring("line", 37, 12)) AS "AMOUNT"
  FROM (SELECT "line" FROM read_csv('f.dat', columns={'line':'VARCHAR'},
               delim='', quote='', escape='', header=false, skip=0, ignore_errors=true)
        WHERE length("line") >= 48);
  ```
  Config: `frontend: fixedwidth` + a `fixedwidth:` block (`record`, `trim`, `min_record_length`,
  `fields[]{name,start,length}`) — inline under `csv_settings` or in a reusable `*.grammar.toon`.
  Worked example: `spaces/default/config/subscriber/`. (The earlier `read_text`+`UNNEST(string_split(...))`
  sketch was rejected — it materialises the whole file as one VARCHAR cell; the `read_csv` single-column
  form streams instead.)
- **Binary fixed-length** (no newlines, `record_length: N`) — the shipped
  `com.gamma.ingester.FixedWidthRecordIngester` (a `StreamingFileIngester`), wired via
  `processing.ingester` + `processing.segments` + an `ingester_config` carrying `record_length`,
  `encoding`, `trim`, and `fields[]{name,start,length}`. It reads N-byte records and `emit()`s byte
  slices; see [§6 *Fixed-length binary records*](#fixed-length-binary-records-fixedwidthrecordingester) above.

### P.6.4 JSON / NDJSON `[LIVE]`
```yaml
parsing:
  frontend: json
  json: { format: newline }        # newline (NDJSON, default) | array | auto
# raw.fields[].selector = the top-level JSON key; every field lands VARCHAR and is typed by the
# schema exactly like a CSV column. Nested values: select the wrapping key, then carve with an
# EXPR mapping rule: json_extract_string(body,'$.user.id'), etc.
```
Malformed NDJSON lines are dropped (`json_valid` filter) — routed away from the output without
failing the batch (they carry no `store_rejects` entry, so they do not land in the errors CSV).
Explicit `json.columns` typing is unnecessary (the engine lands VARCHAR and types at transform).

**Reader knobs (multiformat J1, 2026-08-20 — `format: array | auto` ONLY; refused at load under
`newline`, whose line reader has neither):** `json.maximum_object_size` — byte bound on a single
document/record the reader buffers (blank = engine default). ⚠ Probed: read_json CLAMPS it up to
its internal buffer, so it is unobservable at small scale — its real job is a memory ceiling for
huge documents, and the test pin is assembly-level (option emitted, spelled right, accepted), not
behavioral. Rides `read_json_objects` on the nested `records_path` path too.

`json.ignore_errors` — ⚠ **a second, sharper probed refutation, found while designing the format
example.** DuckDB's `read_json` honors `ignore_errors` **only when the resolved shape is
`newline_delimited`** — for a genuine JSON-array or single-object document it HARD-REJECTS the
option: *"Parse errors cannot be ignored for JSON formats other than 'newline_delimited'"*. This
holds for `read_json_objects` too (the nested `records_path` path), so it is not an `array`-only
quirk. Consequences:
- `format: array` **always** resolves to that rejected shape, so a config combining
  `format: array` + `ignore_errors: true` would load looking honored and then fail every batch at
  ingest — **refused at config load instead** (`PipelineConfigParser.parseJson`).
- `format: auto` is content-dependent: DuckDB's own sniff decides the actual shape, so
  `ignore_errors` takes effect only when the file *itself* looks line-delimited (multiple
  JSON-ish values separated by newlines) — which is exactly what `JsonParsingTest`'s passing
  fixture is, not a general array/object document. The UI gates the field to `format: auto` only
  and documents this precisely; it is not offered under `array`.

**Nested `records_path` (2026-07-31).** When the records sit in an array *inside* the document, point
`records_path` at it with the **same dotted convention as a field selector** — `payload.records`, or
`$.payload.records` (the `$.` prefix is optional; escape a literal dot as `\.`, doubled in TOON). The
two path layers are independent, so a dotted field selector still walks inside each record:
`records_path: payload.records` + `selector: "addr.city"` works with no extra configuration.

- Requires `format: array` or `auto`. A nested path with `format: newline` is **rejected at load** —
  in NDJSON each line already IS a record, so there is no enclosing document to walk.
- A nested path switches the reader to `read_json_objects` + `unnest(json_extract(…)::JSON[])`
  (`DuckDbCsvIngester.buildNestedJsonReadSpec`); `records_path: "$"` keeps the original `read_json`
  path unchanged.
- **Mis-authored paths fail the file, they do not ingest nothing.** A path that is absent raises
  `json.records_path not found in document: …`; one naming an object or scalar raises
  `Conversion Error: Expected ARRAY, but got OBJECT`. An **empty** array is honestly zero records, not
  an error. (The `[*]` wildcard form was rejected precisely because it returns zero rows silently in
  all three cases.)

### P.6.4b MS Excel (.xlsx) `[LIVE]` (multiformat X1–X3, 2026-08-19)
```yaml
parsing:
  frontend: xlsx                   # alias: excel
  xlsx:
    sheet: "Invoices"              # optional; omitted = the workbook's first sheet
    range: "A1:H500"               # optional A1-style anchor or span; omitted = the whole sheet
    header: true                   # default true — header cells name the columns
    stop_at_empty: false           # stop at the first fully empty row
    ignore_errors: false           # unrepresentable cells land NULL instead of failing the file
    normalize_names: false         # lower_snake identifiers from the header cells
# raw.fields[].selector = the SHEET COLUMN NAME as read_xlsx yields it: the header cell when
# header: true (post-normalize_names when set), else DuckDB's positional letters A, B, C…
# Every field lands VARCHAR (all_varchar is STAMPED by ingest, not an option) and is typed by the
# schema exactly like a CSV column.
```
Runs on DuckDB's **`excel` extension**, which is *not* statically linked into the JDBC driver —
`com.gamma.etl.ExcelExtension` loads it fail-closed in three layers: `LOAD` (cached/pre-installed) →
`LOAD` from `-Dduckdb.extension.dir` (**the air-gapped deployment**: ship this platform's
`excel.duckdb_extension` beside the jar) → `INSTALL excel` (networked, caches under `~/.duckdb`).
All three failing fails the batch with a message naming every remedy. ⛔ `read_xlsx` has **no
`columns` parameter** (probed on 1.5.2.1) — the columns table is the columns mechanism, as on every
frontend. Preview rides `sample_b64` (the workbook is binary) and its B2 type sniff is the same
relation without `all_varchar`.

✅ **A real date cell lands as a date in a date-declared field** (`EXCEL-DATES-ARRIVE-AS-SERIALS-1`,
fixed 2026-09-23). An Excel date/time is a day-serial NUMBER with a date style, and `all_varchar`
renders the number (probed on 1.5.2.1: `DATE '2026-08-03'` → `46237.0`, with a time of day
`46237.4376…`). So for a raw field whose declared `type` is `DATE`, `TIMESTAMP` or `TIMESTAMPTZ`, the
xlsx projection (`DuckDbCsvIngester.xlsxSerialAsText`) turns a bare number in the serial range
(≤ 2958465 = 9999-12-31) back into the date it encodes — 1900 system, epoch 1899-12-30, `DATE` floors
the time away, `TIMESTAMP` rounds it to the millisecond — and lands it as TEXT in the Pipeline's **own
first** `csv_settings.date_formats` / `timestamp_formats` entry (ISO when the list is empty), so the
unchanged typing step always parses it and a `DATE_*` partition can be cut from it. The column still
lands VARCHAR; a `VARCHAR`-declared field is never touched (it keeps the serial), and a non-numeric
cell passes through as keyed. Pinned by `XlsxParsingTest` (3 tests) and by
`DemoCorpusIngestTest.glJournalSplitsFiveThreeThreeTwoByAccountClass` over the committed `gl_journal`
workbook, whose schema now declares `POSTING_DATE … DATE` with a plain `keep`.
⚠ `all_varchar` cannot tell a numeric cell from a TEXT cell of digits: in a date-declared field a text
cell of 1–7 digits (`260803`) is read as a serial. An 8-digit `yyyymmdd` text is past the serial range
and passes through untouched. ⚠ Preview is unchanged — it shows sheet columns, not schema fields, so
its rows still show the serial; its B2 `columnTypes` sniff reports the column `DATE`.

### P.6.5 XML `[PLUGIN]` (or `text_regex` `[LIVE]` for *flat* XML)
DuckDB has no core XML reader. For flat, one-element-per-line XML, `text_regex` with
`regexp_extract` works. For real nested XML, use the shipped `XmlRecordIngester` (§P.10) or write a
`StreamingFileIngester` around a StAX/SAX streaming parser and `emit()` per element — streaming (not DOM)
keeps memory bounded on large files.

### P.6.6 LDIF `[LIVE via text_regex]` — `record_split: "\n\n"` (or `blank_line`) is shipped
LDIF = blank-line-separated entries; within an entry, `attr: value` lines (and `attr:: base64`).
```yaml
parsing:
  frontend: text_regex
  text_regex:
    record_split: "\n\n"            # one record per LDIF entry (blank-line delimited)
    pattern: "^(?P<attr>[\\w;-]+):: ?(?P<value>.*)$"   # handle ':' and '::' (base64)
# Pivot attr/value to columns via mapping rules, or emit long-format (dn, attr, value).
```
Caveats `text_regex` must handle: **line folding** (continuation lines start with a space) and
**base64** (`::`). If your LDIF uses either heavily, prefer a `[PLUGIN]` that unfolds first.

### P.6.7 ASN.1 / BER-TLV (telco CDRs) `[PLUGIN]` — your existing parser
DuckDB **cannot** parse ASN.1. The shipped route is `frontend: asn1` (§P.5, §P.10). To wrap your *own* ASN.1
parser instead, implement a `StreamingFileIngester`:
```java
public class Asn1CdrIngester implements StreamingFileIngester {
  public void ingest(File f, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
    String grammar = (String) cfg.schemas().ingesterConfig().get("asn1_grammar"); // your .asn1
    try (var decoder = Asn1Decoder.open(f, grammar)) {     // YOUR parser
      sink.define("CALL", List.of("ID","EVENT_TYPE","EVENT_DATE","DURATION"));
      for (var rec : decoder) {
        switch (rec.tag()) {
          case CALL -> sink.emit("CALL", rec.id(), "CALL", rec.date(), rec.duration());
          case SMS  -> sink.emit("SMS",  rec.id(), "SMS",  rec.date());
          case BAD  -> sink.reject(rec.tagName());
          default   -> sink.junk();
        }
      }
    }
  }
}
```
Wire via `parsing.frontend: plugin` (or `processing.ingester` + `segments`). The **ASN.1
grammar file is plugin config** (`ingester_config.asn1_grammar`) — the framework stays
format-agnostic; DuckDB still does typing/partitioning on the emitted records. The multi-`segment`
mechanism is literally "emit events" (CALL/SMS/… → separate event tables).

### P.6.8 Custom binary (fixed or TLV) `[PLUGIN]`
Same shape as P.6.7. Put `record_length`, `byte_order`, field offsets in `ingester_config`; your
ingester reads structs and `emit()`s. The `large_file_bytes`/generation mode
([§ Execution modes](#execution-modes--the-framework-picks-by-file-size) above) keeps TB-scale
binaries within bounded memory.

---

## P.7 Decision matrix — pick a frontend

| Format | Frontend | Native to DuckDB? | Status |
|---|---|---|---|
| CSV/TSV/pipe, well-formed | `delimited` (engine `auto`/`duckdb`) | yes | `[LIVE]` |
| SQL*Plus / Oracle spool | `delimited` (engine `java`, skip_*) | partial | `[LIVE]` |
| Dirty/“total chaos” text | `delimited` (Java path, null_strings) | yes | `[LIVE]` partial |
| Fixed-width text | `fixedwidth` → `read_csv`(1 col)+substring | via read_csv | `[LIVE]` |
| Fixed-length binary | `plugin` (`FixedWidthRecordIngester`) | no | `[LIVE]` (shipped ingester) |
| JSON / NDJSON | `json` → `read_json`/`read_ndjson` | yes | `[LIVE]` |
| Excel workbook | `xlsx` → `read_xlsx` (`excel` extension) | via extension | `[LIVE]` |
| Flat XML / key-value | `text_regex` | via read_csv(1-col) | `[LIVE]` |
| Nested XML | `plugin` (`XmlRecordIngester`, StAX streaming) | no | `[LIVE]` (shipped ingester, 2026-08-30) |
| LDIF | `text_regex` (or `plugin` if folded/base64) | via read_csv(1-col) | `[LIVE]` via `text_regex` `record_split: blank_line`, 2026-08-28 (needs `"\n\n"` split; this cell said PROPOSED until 2026-09-08) |
| ASN.1 / BER-TLV CDR | `asn1` (synthesized `Asn1RecordIngester`) or `plugin` (your parser) | no | `[LIVE]` / `[PLUGIN]` |
| Proprietary binary | `plugin` | no | `[PLUGIN]` |

**Rule of thumb:** line-regular text → a DuckDB-native frontend (`read_csv`/`read_text`+regex);
structurally-typed text → `read_json`; anything binary or grammar-driven → `StreamingFileIngester`.

---

## P.8 Roadmap status and corrections to the PDF

The implementation roadmap (smallest-to-largest, each behavior-preserving for existing CSV Pipelines) is
**complete**; each step reuses `mapping.rules[]` + `partitions[]` + lineage/audit unchanged — the whole
point of the frontend/backend split:

1. **Surface the safe `[NATIVE]` knobs on the existing CSV path** — `encoding`, `compression`,
   `null_strings`, `strict_mode` (4.1) and `quote`, `escape`, `comment` (5.2) through
   `DuckDbCsvIngester.buildReadSpec` and the Java `CsvIngester`. ✅
2. **`fixedwidth` frontend** — ✅ a `buildReadSpec` branch compiles `fields[]` offsets to the
   `read_csv`(1-col)+`substring` SQL in §P.6.3 (text, reusing the whole CSV streaming/union/chunk path) and a
   byte-slicer (`FixedWidthRecordIngester`, binary). No new dependency.
3. **`json` frontend** — ✅ `read_ndjson`/`read_json` wrapped as a frontend; selectors are top-level JSON
   keys, everything lands VARCHAR; lean on `EXPR` mapping rules (`json_extract`) for nesting.
4. **`text_regex` frontend** — ✅ line records via `read_csv`(1-col) + `regexp_extract` with named groups;
   blank-line block records too (`record_split: blank_line` or any literal delimiter —
   `PipelineConfigParser` → `TextRegex.recordSplit()` → `DuckDbCsvIngester.buildTextRegexBlockReadSpec`);
   escalate to `[PLUGIN]` only for folding/base64-heavy LDIF.
5. **The unified `parsing:` block** — ✅ `csv_settings` = `parsing.delimited`,
   `processing.ingester`/`segments` = `parsing.plugin`; existing toons keep working unchanged.

Corrections to fold back into the PDF:

1. Split the conflated **`columns` vs `types` vs `names`** row (§P.3.2 above).
2. Add **`store_rejects`/`rejects_table`** — it's how errors are actually captured.
3. Add **`encoding`, `compression`, `max_line_size`** — required for `.gz` and non-UTF-8 dumps.
4. Add **`read_json`/`read_ndjson`** and **`read_text`/`read_blob`** sections — they unlock
   everything beyond CSV.
5. Mark **`exclude_prefixes` / `exclude_target_column`** clearly as **app-side, not DuckDB** (and
   note they are not implemented here; the equivalent today is `skip_junk_lines` + a `WHERE`/`EXPR`).
6. State the engine's **read-as-VARCHAR-then-`TRY_STRPTIME`** typing model, so readers know
   `dateformat`/`decimal_separator`/sniffer settings are largely redundant on the ingest path.

---

## P.9 Parser plugins — the self-describing Parser framework

The E of ELT: any file loads into one or more **Tables** (the segment → partitioned CSV/Parquet
model, unchanged — see [Part I](#part-i--ingest) above). A file type is parsed by providing a **Grammar**
(GLOSSARY §5). Internally there are two **fully transparent** parser engines — DuckDB-native reads
for the built-ins, custom Java decoders for everything DuckDB can't consume — unified behind one
self-describing SPI so a new format can be *deployed and configured as a plugin* with **zero UI or
control-plane change**. This section is the self-description layer over both: discovery, served grammar
schemas, preview.

### The SPI (`com.gamma.parse.ParserPlugin`, engine, `@PublicApi since = "4.0.0"`)

| Method | The authoring question it answers |
|---|---|
| `id()` / `label()` / `hierarchical()` | what am I called; are my records tree-shaped? |
| `grammarSchema()` → `List<FieldSpec>` | which options do I take? (the SAME `FieldSpec` vocabulary `GET /config/spec/{type}` serves — dotted paths relative to the grammar map) |
| `suggest(byte[])` | clues sniffed from a sample (offered as chips, never auto-applied) |
| `preview(byte[], grammar)` → `ParseResult` | parse part of the contents: a `Table` (columns/rows) or a `Tree` (record forest) |
| `previewRows(byte[], grammar, configDir)` → `Optional<ParseResult.Rows>` | (default empty) the rows a hierarchical grammar's `derive` / `children` would load, served next to the tree |
| `ingesterClass()` → `Optional<String>` | the FQCN of my `StreamingFileIngester` when the format can load to Tables TODAY |

**`ParseResult.Rows` (PARSING-PREVIEW-DERIVED-1, 2026-10-12, operator: a new variant, not a synthetic child node on the record tree):** `Asn1ParserPlugin.previewRows` runs the ingester's OWN row path (`Asn1RecordIngester.emitRecords`, shared, not copied) over the first `max_records` records into a collecting sink, so what the drawer shows is what ingest writes. Shape: `Rows(List<RowSet>)`, one `RowSet` per declared segment `{segment, child, columns, derivedColumns, rows (<= 200), rowCount (all)}`. `POST /parsers/{id}/preview` stays a `kind: 'tree'` response and ADDS `derived` (the rows) only when the grammar declares `derive` or `children`, or `derivedError` when they cannot be built (a bad segment schema: the tree is never lost to it); a grammar declaring neither is byte-identical to before. It needs `asn1.segments` (the segment schemas name the raw columns), read through the same jail as the Pipeline load; the drawer sends the SAVED block's `derive` / `children` / limits / `segments` (none is a grammar-form field), so an unsaved segments edit does not preview rows yet. The SPA: `app-parser-derived-rows` under the tree in `grammar-editor` (ASN.1 only; derived columns highlighted, child segments tagged, an empty-state sentence when none are declared).

`ParseResult.Tree`'s node shape mirrors the UI's `ParserTreeNode` verbatim, so the control plane
serializes without translation. **Preview and ingest are deliberately separate capabilities**: a
hierarchical parser without an ingester is *preview-only* (`ingestable: false` in the catalog) —
tree-shaped records cannot honestly land in Tables until the flatten configuration exists.
⚠ **No shipped parser is preview-only any more** (XML gained its bridge 2026-08-30), so the
preview-only *mechanism* is pinned by a stub plugin in `ParsersTest`, not by a deployed example —
otherwise a regression in the flag would go unseen precisely because every real parser passes.

### Registry (`com.gamma.parse.Parsers`) + discovery

Built once at class-load: the **six** built-in adapters (`BuiltinParsers.IDS` — delimited /
fixedwidth / json / text_regex / xlsx / parquet, whose `preview` delegates to
`ComponentPreview.parsing`, i.e. the exact DuckDB read specs `DuckDbCsvIngester` runs at ingest) merged
with `ServiceLoader.load(ParserPlugin.class)` (`META-INF/services/com.gamma.parse.ParserPlugin`).
⚠ **Duplicate ids fail startup loudly** — deliberately unlike `PipelineNodeTypes`' override-a-
builtin rule: the built-ins' preview IS the engine that ingests, so an override would let a
preview diverge from production parsing. ⚠ `Parsers.load` must NOT use `Map.copyOf` — it discards
iteration order, and catalog order (built-ins first) is part of the contract.

**Plus an owner-keyed pack overlay (2026-09-25, slice P2).** `Parsers.register(plugin, owner)` /
`deregister(owner)` add and remove the parsers a loaded Job Pack contributes, keyed by the pack's jar
filename; `ownerOf(id)`, `sourceOf(id)` (`builtin` | `classpath` | `pack:<jar>`) and
`forIngester(fqcn)` read it. Catalog order is built-ins, then classpath providers, then packs in load
order. `register` refuses an invalid id, **any id the built-ins or classpath providers hold** (a pack can
never replace one), an id another pack owns (first pack wins), and **an ingester class name another
registered parser already names** — otherwise which pack's loader resolved that class would depend on load
order. Details in §P.11.

**Reference plugin: `XmlParserPlugin`** (engine, registered via the services file) — JDK StAX, DTDs
and external entities disabled outright (XXE), grammar `ingester_config.record_element` (local name
or slash path; blank = the root's direct children) / `namespace_aware` / `encoding` / `max_records`;
`suggest()` proposes the root's repeated child. **Ingestable since 2026-08-30** — it names
`com.gamma.ingester.XmlRecordIngester` (§P.10), the tree→segments bridge.

⚠ **The grammar keys live under `ingester_config`, not an `xml.` root** (moved 2026-08-30). That
block is exactly what the pipeline persists for the ingester, so preview and load are configured by
ONE set of keys; a separate preview-only spelling would have had to be mapped onto the ingest one
somewhere, and that mapping is precisely where a silent drift lives. `max_records` is the one
preview-only key and the ingester ignores it. Nothing operator-authored used the old `xml.` spelling
(only code, tests and the mock), so this was a clean flip, not a compat layer.

**Second plugin: `Asn1ParserPlugin`** — **since 2026-10-07 (MODULE-REORG-1 P7) it lives in the optional
`inspecto-telecom-asn1` module** (module id `telecom-asn1`, the *Telecom industry pack* Provider, plan §8a),
registered through THAT module's own `META-INF/services/com.gamma.parse.ParserPlugin`; the engine's services file
keeps only `XmlParserPlugin`. The module is staged from Professional up and is **NOT shipped in Personal** (a
deliberate behaviour change): without it `Parsers` has no `asn1`, an `asn1` ingest fails with the
`PluginIngesters.notLoaded` message (which now names the module), and `parser.asn1.ber` in the Step Processor
catalog reads *planned / not installed* (`ProcessorCatalog.Pack`, resolved at read time from `Parsers`) rather than
delivered. The three classes live in the module-owned package `com.gamma.telecom.asn1` (P3g, 2026-10-08; they were a deliberate split
of the engine's `com.gamma.parse`/`com.gamma.ingester` until then), so no package is shared with another module and the jar can be signed.
`com.gamma.telecom.asn1.Asn1RecordIngester` is the persisted config string (`frontend: asn1` synthesises it; hand-written
`plugin.ingester` names it); the rename was a breaking change taken freely, no alias. Pinned by
`Asn1PluginRegistrationTest` (that module). It is **the first hierarchical parser that is `ingestable: true`**,
because it names an ingester (`com.gamma.telecom.asn1.Asn1RecordIngester`, §P.10). Wraps the
`asn-facade` module's public `Asn1Decoder`/`RecordMapper` (`providers/asn-parser/asn-decoders/asn-facade`,
`com.gamma.asn:asn-facade:0.1.0-SNAPSHOT`, resolved from the reactor since 2026-08-01 — see the coordinate
note in §P.10). Grammar: `asn1.grammar` (the ASN.1 module text) **or** `asn1.grammar_file` (a stored
`.asn` module, 2026-09-23 — below) / `asn1.root_type` / `asn1.strictness`
(BER/DER/CER) / `asn1.file_header_length` / `asn1.record_header_length` / `asn1.max_value_bytes`
(the single-value cap, §P.14) / `asn1.max_records`. No `suggest()`.

### A grammar is either pasted TEXT or a stored `.asn` FILE (operator decision 2026-09-23)

A stored module is a path-jailed `.asn` file under the Space's config — deliberately **not** a new registry kind.
Both spellings resolve through ONE class, `com.gamma.telecom.asn1.Asn1GrammarSource`, which the preview
(`Asn1ParserPlugin.preview`), the flat config (`frontend: asn1`) and the ingester
(`Asn1RecordIngester`) all call, so a grammar that previews is the grammar that ingests:

- **Text wins when both are set** — the "parsing: keys win" overlay rule the ingester already applied.
- **A relative ref resolves like `schema_file`** — `PathJail.resolveConfigRef`, never the working
  directory (`SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`). In a Pipeline it is resolved **beside the Pipeline's
  own config file** (a sibling name: `grammar_file: msc_cdr.asn`), by `PipelineConfigParser`, into
  `Schemas.ingesterGrammar()` — the ingester never sees the config's directory. The preview has no config
  file, so the Parse drawer sends its Pipeline's location: `POST /parsers/{id}/preview` takes an optional
  body `subdir` (the Pipeline's config directory relative to the write root — the same meaning as every
  satellite route's `subdir`, `''` = at the root), and the route resolves the ref beside
  `writeRoot/<subdir>` via `ParserPlugin.preview(sample, grammar, configDir)` — so **one spelling
  (`grammar_file: msc_cdr.asn`) previews and ingests** for a Pipeline in a subdirectory too
  (`BUNDLE-ASN1-GRAMMAR-FILE-1`, 2026-09-23). Only with **no** Pipeline context (no `subdir` key: the
  stand-alone Grammar dialog) does it fall back to the **bound Space's config root**
  (`SpaceConfigRoot.current()`). An absolute `subdir` is 400; the base is not jailed — the resolved FILE
  is, once, below.
- The file ref is jailed **once**, by `Asn1GrammarSource`, with `PathJail.requireUnderAny(allowedRoots())`
  **before** the readability probe (an escaping ref is refused, never reported "not readable", which
  would leak whether a path outside the roots exists). The parser resolves but does **not** jail.
- ⚠ **The extension is enforced (`.asn` / `.asn1`) first, before anything touches the disk.**
  `POST /parsers/{id}/preview` is compute-only with no capability for the built-in ASN.1 parser, and a compile error can echo the text
  it choked on — an unrestricted ref would let a preview read any file under the roots back through an
  error message.
- `frontend: asn1` carries `asn1.grammar_file` as-authored as the path key `ingester_config.grammar`
  (and `asn1.grammar` as `grammar_text`), so a save writes back the relative value; the resolved path
  lives apart, in `ingesterGrammar()`. The load refuses an `asn1:` block carrying neither spelling.
- The preview route maps the jail's `PathJail.Escape` to **403**; other grammar problems stay 422.
- The drawer needs no bespoke UI: the ASN.1 form is SERVED from `grammarSchema()`, which now lists
  `asn1.grammar_file` beside `asn1.grammar`.
- Proof: `DemoCorpusIngestTest.mscCdrWithItsGrammarInAnAsnFilePreviewsAndIngestsIdenticallyToInline`
  moves the committed `msc_cdr` grammar into a `.asn` file and pins the same preview tree and the same
  rows in every segment as the inline original.
- **A Pipeline bundle carries it and re-points it** (`BUNDLE-ASN1-GRAMMAR-FILE-1`, 2026-09-23). The
  resolved module is in `referencedFiles()`, so the export ships it as a satellite under its basename,
  and `PipelineBundleRoutes.rewriteSatelliteRefs` rewrites `parsing.asn1.grammar_file` to that basename
  like every other satellite ref. ⚠ Before this, an import of a non-sibling spelling
  (`grammars/msc_cdr.asn`) still returned **200 and registered** — nothing resolves the module at
  registration — and would only have failed at the first ingest. Proof (real HTTP):
  `ControlApiPipelineBundleTest.anAsn1GrammarFileTravelsAndIsRepointedBesideTheImportedPipeline` (the
  msc_cdr demo, grammar in a `.asn` file, export → import → the drawer's preview with `subdir` equals the
  inline preview); `ControlApiParsersTest.asn1PreviewResolvesAGrammarFileBesideThePipelineSubdir`.

**The grammar is OPTIONAL for preview — structural dump (2026-07-31).** BER is self-describing
(every value carries its own tag and length), so with `asn1.grammar` blank the plugin skips the
facade entirely and walks the raw TLV forest via `RecordReader` (which takes no schema), labelling
nodes by tag — `[APPLICATION 1]`, `[0]`, `[PRIVATE 3]` — instead of by schema name. This is what
lets an operator inspect an unknown vendor's file **before** they have its `.asn` module, which is
exactly the onboarding situation; supply the grammar and the same bytes return with real names.
⚠ Preview-only: **ingest still requires a grammar**, because anonymous tags cannot be mapped onto
segment columns (a column named `[0]` is worthless). ⚠ `root_type` without a grammar is a caller
error, not a silent fallback — it is a half-filled form.
⚠ **Values render hex-first** (`2A`, and `6869 "hi"` for 2+ printable bytes). Text is an annotation,
never a replacement: without a grammar there is no type, and 0x2A *is* printable, so rendering
INTEGER 42 as `"*"` would be a lie dressed as a decoded value. Truncated at 32 bytes.

**Framing is served, but only the knobs real files vary by.** The two length fields cover every
layout in the parity corpus (file header 0 or 50 bytes; record header absent or 4 bytes, always
`skipOnly` so records stay delimited by their own BER length). 0x00/0xFF inter-record padding is
**unconditional** — the legacy `ASN1Utils.readTag` skips both before every record tag and the
parity harness pins the rewrite to that. ⚠ Consequence: a record header made of those bytes is
eaten as padding before the header is counted. Deliberately NOT served: trailer length and the
length-prefix machinery (`lengthOffset`/`lengthSize`/endianness/`lengthIncludesHeader`) that
`Framing.RecordHeaderSpec` can express — no corpus file uses either, so serving them would be
offering knobs nothing has needed. They stay available in asn-core the moment a real file demands
them.

### Control plane (`ParserRoutes`, all read/compute-only — no write gate; a capability only for a pack parser's preview)

- `GET /parsers` → `[{id, label, hierarchical, ingestable, ingesterClass?, source, grammarSchema}]`.
  `source` (2026-09-25, additive) is `builtin`, `classpath` or `pack:<jar filename>` for a parser a Job
  Pack contributed.
- `POST /parsers/{id}/preview` `{grammar, sample_text | sample_b64}` → the UI's `ParserPreview`
  union `{kind: 'table' | 'tree', …}`. 404 unknown id · 400 missing/oversized sample (text 1MB,
  b64 4MB — binary formats need bytes) · 422 caller errors with the reason. The grammar-shaped
  sibling of `POST /config/preview/parsing`, which stays byte-identical as the draft-true path.
  **403 `PERMISSION_DENIED` when the parser came from a Job Pack and the caller lacks
  `canAuthorWorkbench`** (operator D4 2026-09-25, §P.11). Built-in and classpath parsers stay open.
- `GET /parsers/asn1/profile?profile_file=<ref>&subdir=<dir>` → `{asn1: {…}}`, a Decode Profile's own block as
  authored (2026-09-25; §P.12).

### UI adoption

The authoring screens are described in [grammar-config](../config/user-guide-grammar-config.md) and the
[ELT user guide](user-guide.md); the engine-relevant contract points are:

- **Onboarding Parsing stage**: the file-type toggle appends the served non-builtin parsers; their
  options form renders the served schema via `fieldSpecsToAttributes`
  (`inspecto/component-model/field-spec-mapper.ts` — unknown field shapes are SKIPPED, never
  guessed, the `findingsAttributes` idiom); Test parse hits `/parsers/{id}/preview` and renders
  table or tree. The plugin preview is pane-local — the sample thread's parsed hop stays
  builtin-only.
- **Save gating** is per-capability, not "plugins can't save": enabled when the selected plugin
  is `ingestable` **and** serves an `ingesterClass`; still disabled (with the honest
  preview-only note) otherwise.
- **Segments editor** (`segments-editor.component`, in the Parsing pane — deliberately *not* a new
  stage: stages are static arrays with no runtime-conditional precedent, and the editor needs the
  decoded tree directly above it). **Derive from preview** proposes one segment per record type
  with a column per LEAF path; column names are generated through the engine's identifier rule,
  since `Identifiers.validateSchema` makes a violation a hard startup failure. Save writes one
  schema toon per segment via `ConfigService.write('schema', …)` at the Schema stage's convention
  path, *then* patches `parsing.plugin` — in that order, so the pipeline never names a file that
  does not exist yet. Partitions default to the derived `EVENT_TYPE`, because an empty
  `partitions[]` silently sends every row to the `year=1900` sentinel partition.
  ⚠ **Residual:** the editor re-hydrates segment *keys* from a saved config but not their columns
  (those live in the referenced schema toons, which the pane does not read back) — re-editing an
  existing stream needs a re-derive.
  ⚠ Bespoke nested `FormArray` by necessity: `FieldSpec` cannot express "a list of segments, each
  with a list of columns" — `ConfigSpecs.schema()` hits the identical wall and says so.
- **Pipelines Parser dialog**: runs entirely on the served contract now (catalog + real preview) —
  the old mock-only `/components/grammar/preview`, the 9-type hardcoded `parser-types.ts` catalog
  and the ASN.1 module picker are **gone**; grammar components persist as
  `{parser_type, <nested grammar>}` (old prototype flat-key contents simply render empty forms).
- ⛔ **A stand-in must never be more lenient than the server.** *(This was `parsers.handler`'s parity rule,
  pinned in its own spec, until the offline mock was deleted 2026-08-31; the rule outlives the mock and
  applies to any fake, stub or fixture that answers for this catalog.)*

---

## P.10 XML and ASN.1 — hierarchical parsers that ingest

ASN.1 (the operator's target format) is **served as of 2026-07-31** via `Asn1ParserPlugin` — it appears in
the Onboarding Parsing stage's toggle and the Pipelines Parser dialog with zero UI change, exactly as
designed. ⚠ It was preview-only until 2026-07-31; it now names `com.gamma.telecom.asn1.Asn1RecordIngester`
via `ingesterClass()`, so `Parsers.ingestable()` (`Parsers.java`) is **true**.

### The tree→segments bridge — `XmlRecordIngester` (2026-08-30)

**This is what closed "Parsing Stage-1 (b)"**, the slice that kept every hierarchical parser
preview-only. There was never a structural blocker: `Parsers.ingestable()` is a *display* flag
derived from `ParserPlugin.ingesterClass()`, and nothing in the config, validation or ingest-dispatch
path ever consulted it. XML was preview-only because no ingester existed, full stop.

`com.gamma.ingester.XmlRecordIngester` (engine) loads through the same `parsing.plugin` machinery as
ASN.1 (`frontend: plugin` + `plugin.ingester`/`segments`/`ingester_config`) and follows its rules
verbatim — segment key = the record element's local name, `raw.fields[].selector` is a dotted path,
undeclared records are `sink.junk()`, a trailing derived `EVENT_TYPE` carries the segment key, and a
malformed document fails the file (`QUARANTINED_UNREADABLE`) rather than loading its prefix.

🔴 **The load-bearing decision is that preview and ingest share ONE walker**, `com.gamma.parse
.XmlRecordReader`. An operator authors a selector against the labels they saw in the preview tree; a
second StAX walker that labelled nodes even slightly differently would resolve those selectors to
`NULL` at load while the preview kept looking correct — a silent, per-column data loss with no error
anywhere. `XmlRecordIngesterTest.previewLabelsAreTheSelectorsThatResolve` pins this by asserting the
selector vocabulary against the *plugin's own* preview output, not a hand-written list.

The selector vocabulary is therefore the preview's labels: a child element by name, an attribute as
`@id`, and an element carrying both text and children as `#text`.

⚠ **A selector must name a leaf that occurs ONCE.** A container yields `NULL` (the ASN.1 rule), and
so does a step matching *repeated* sibling elements — XML has no first-one-wins rule that is not a
guess, and silently taking one of five `<line>` elements would be a lie dressed as a decoded value.
A repeated element is not a column; give it its own segment by naming it as the `record_element`.

**Several record kinds in one document** load by leaving `record_element` blank (every direct child
of the root is then a record) and declaring one segment per kind; an undeclared kind is junk, not a
silent drop. With an explicit `record_element` the other kinds are simply never records.

`ingester_config`: `record_element` · `namespace_aware` · `encoding` (`max_records` is preview-only
and ignored here).

⚠ **A plugin ingester with no segments is refused at CONFIG LOAD** (`PipelineConfigParser
.parsePlugin`), before the ingester is constructed — so the ingester's own segment guard is
unreachable through a loaded config and exists only for the public SPI's direct callers. A test that
tries to reach it through `PipelineConfig.load` is testing the parser, not the ingester.

### Loading to Tables — `Asn1RecordIngester` (2026-07-31)

ASN.1 loads through the **existing `parsing.plugin` machinery**, not a new path: `frontend: plugin`
+ `plugin.ingester` + `plugin.segments` + `plugin.ingester_config`. ⚠ `asn1` is a *catalog id for
preview/authoring*, **and also a first-class `parsing.frontend` value**: `PipelineConfigParser`'s
`FRONTENDS` set has ten tokens —
`{delimited, fixedwidth, fixed_width, json, text_regex, xlsx, excel, parquet, asn1, plugin}` — and
`frontend: asn1` synthesizes this exact plugin wiring (`parsePlugin`), refusing a co-present
`parsing.plugin` / `processing.ingester`. Writing the wiring out by hand under `frontend: plugin`
remains equivalent.

`com.gamma.telecom.asn1.Asn1RecordIngester` (module `inspecto-telecom-asn1`, package `com.gamma.telecom.asn1`, owned by that module alone):

- **Segment key = the decoded record's own name.** For the corpus's union-style vendor grammars
  (a SET/SEQUENCE whose tagged components are the record types) `SchemaBinder.bind` names a record
  by the *matched alternative* — e.g. `moCallRecord` — so that name is the segment key. A
  single-type grammar yields the root type name. An undeclared record type is `sink.junk()`,
  mirroring how `TypedRecordIngester` treats an unknown type prefix.
- **`raw.fields[].selector` is a DOTTED PATH**, not a positional index — the one real divergence
  from the text ingesters. `party.number` walks the `RecordMapper.toMap` record map.
- ⚠ **A selector must name a leaf.** A container (sub-record, or a repeated field's list) yields
  `NULL`, not a stringified subtree — deliberately matching the legacy transform engine, which also
  drops lists of scalars rather than inventing a join. A repeated field is not one column; give it
  its own segment — or, since 2026-10-12, a `derive` / `children` block (§P.13, D9).
- A trailing derived **`EVENT_TYPE`** column carries the segment key (the `TypedRecordIngester`
  convention), so schemas can partition by record type without redeclaring it.
- ⚠ **Any parse error fails the whole file** → `QUARANTINED_UNREADABLE`. These framings carry no
  length prefix, so `SKIP_RECORD` cannot resync; half-ingesting a CDR file is worse than
  quarantining it. Input is memory-mapped (`ByteSource.map`), so files >2 GB are fine.

`ingester_config`: `grammar` (path to the `.asn` module) or `grammar_text` (inline module; wins when
both are set) — one of the two required · `root_type` (required) ·
`strictness` · `file_header_length` · `record_header_length` · `max_value_bytes`.

### ASN.1 status items (tracked in BACKLOG §4 "Parsing (Stage-1)")

- ~~**Declarative decode profile — the grammar source.**~~ **CLOSED 2026-09-23.** Framing was served
  earlier; the grammar source is now too. *(This bullet said until 2026-09-23 that the plugin takes
  only pasted module text. That was already half-wrong — the ingester accepted a jailed `.asn` path
  under `ingester_config.grammar` all along — and is now wholly wrong: preview, `frontend: asn1` and
  the drawer all take `asn1.grammar_file`, see §P.9.)* A per-vendor module is stored as a `.asn` file in
  the Space's config (the corpus keeps them that way, e.g. `mtnOCC.asn`). One vendor's decode settings
  have a home since 2026-09-25: the **Decode Profile** (§P.12).
- ~~**The Maven coordinate split**~~ **RESOLVED 2026-08-01.** The root `pom.xml` now aggregates
  `providers/asn-parser/asn-decoders`, so `com.gamma.asn:asn-facade` resolves from the reactor and the manual
  `mvn install` is gone (verified with the local repo's `com/gamma/asn` deleted: 23 modules,
  asn-facade [7/23] before inspecto-engine, `mvn -o clean test` green, 2178 tests). Aggregation
  only — that tree keeps its own parent and inherits nothing from `inspecto-parent`. *(First
  documented as done 2026-07-31, but that pom edit was never committed — the `<modules>` entry was
  lost across a shift and re-landed 2026-08-01; a fresh `~/.m2` still needed the manual install in
  between.)* The OLD `asn-parser-v2:1.2.1`
  (`providers/asn-parser/pom.xml`) is **deleted**: zero consumers, and its parent
  `com.gamma.asn.decoders:asn-decoders:1.1.3-dev` existed nowhere, so it could not build.
  ⚠ **`providers/asn-parser/src/main/java` survives the deletion and must not be cleaned up as an orphan** —
  `legacy-code/pom.xml` compiles it via `<sourceDirectory>../../src/main/java</sourceDirectory>`
  (45 files, confirmed in the build log). It retires with `legacy-code` after Phase 4.
- ⚠ **`providers/asn-parser/src/test/` is a tree of manual scratch programs, NOT tests** (operator decision
  2026-09-24, `LEGACY-ASN-SRC-TREE-UNBUILT-1`: rename, don't wire). No `testSourceDirectory` points
  at it, so its 21 files compile on no build path. The six that carried `*Test` names were renamed
  to the `*Harness` convention so nothing claims coverage it lacks: `Test` → `SchemaCsvHarness`,
  `ASNFileReaderTest` → `ASNFileReaderHarness`, `BERDecoderTest` → `BERDecoderHarness`,
  `SbinHuaMscAsnTest` → `SbinHuaMscAsnHarness`, `FixedLengthFileReaderTest` →
  `FixedLengthFileReaderHarness`, `RTDMS_ASN_Test` → `RTDMS_ASN_Harness`. `asn-golden`'s
  `GoldenCapture` cites `RTDMS_ASN_Harness.<method>` as provenance for 7 of its 9 golden cases —
  rename that file again only together with those comments. Real ASN.1 coverage lives in the
  `providers/asn-parser/asn-decoders` modules.
- ⚠ **Corpus-backed tests are opt-in AND data-gated** (DATA-GOV-1). `RealGrammarsTest` (asn-schema)
  and `ParityCheckTest` (asn-golden) `assumeTrue` on **both** `-Dasn.corpus.tests=true` **and** the
  operator data being present on disk, so by default — and on any corpus-less checkout, including a
  `git worktree` (the corpus is gitignored, so worktrees never receive it) — they SKIP and the build
  is green; *skipped* is the expected state, not a regression. Exercise them where the corpus lives
  (the main checkout) with `mvn test -Dasn.corpus.tests=true`. The property alone does nothing
  without the data; the data alone no longer runs them without the property (2026-08-01).
- **Drop-in parser jars** and the **segments editor** (unlock guided Save for ingestable custom parsers)
  — apply to any custom parser, not ASN.1-specific. The drop-in half's design is
  [`parser-plugins-trust-design.md`](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md) (every slice
  shipped 2026-09-25). What shipped is §P.11; this page is now the current knowledge, the design is provenance.

---

## P.11 Drop-in parser jars — trust gate, staging dir, pack parsers, ingest and preview gate SHIPPED (2026-09-25)

Operator decisions 2026-09-25: **D1** a parser arrives as a **fifth Job Pack kind** through the existing
`JobPackManager` loader (`-Djobs.packs.dir`). There is no second `plugins/` directory. **D2** T1 SHA-256
allowlist is required; signer anchoring (T2) is not built. **D3** fail closed for Job Packs too. **D4**
`POST /parsers/{id}/preview` needs the Pipeline-authoring capability for pack parsers only. **D7** no
edition gate. **D8** no out-of-process host.

**As built (slice P1, the gate every pack kind now passes):**
- `-Djobs.packs.allowlist=<file>` (`PackAllowlist`, `inspecto-engine` `com.gamma.job`). Format: one
  `<64-hex sha256>  <file>  [note]` per line, so `sha256sum` output works as-is. Only the hash decides.
  Blank lines and `#` lines are skipped.
- **Fail closed:** packs dir set with no allowlist ⇒ **every** jar is refused, cause `not trusted: no
  jobs.packs.allowlist configured`. An unreadable file, or a single malformed line, refuses every jar
  (the cause names the file or the line). An unlisted hash is refused with `not trusted: sha256 <hash> is
  not in jobs.packs.allowlist`.
- **Verify before load, no TOCTOU:** the hash that is checked is the SHA-256 of the **staged copy the
  `URLClassLoader` reads**. It is taken after staging and before any class is defined. That is the same
  staged-bytes rule the earlier verify/load TOCTOU fix (`f90ddcf25`) set up for the signature check.
  `JobPackTrustTest` pins both swap windows. A jar swapped before staging is refused under its own hash.
  A jar swapped after staging loads only the vetted bytes, and is then refused on the next rescan.
- **Rescan re-reads the allowlist.** Approve = add the line and rescan (`POST /jobs/packs/rescan` or any
  change in the packs dir). Revoke = remove the line and rescan, which **unloads** the loaded pack and then
  refuses it.
- **A3, boot refusal:** the constructor throws, so the Space does not load, when the allowlist lies inside
  the packs dir or under `assist.write.root`, `spaces.root` or any `PathJail.allowedRoots()` root. By
  design there is no API or UI route that approves a jar.
- **Signals / log / inventory:** each refusal emits `job.pack.rejected {file, hash, cause}` and a WARN
  `[PACKS] rejected …` line. `GET /jobs/packs` lists refused jars as `{file, hash, state: "rejected",
  cause}` next to the `state: "loaded"` rows, and a row is dropped when its jar leaves the dir. The
  startup log line names the allowlist, or says it is `NOT CONFIGURED`.
- `-Djobs.packs.requireSignature` is unchanged. It is an **integrity** check on top of the allowlist, and
  it still never looks at who signed.

**As built (slice P2, a parser is the fifth pack kind):**
- `JobPackManager.load` runs a fifth `ServiceLoader` loop for `ParserPlugin` over the pack's loader, keeping
  only providers that loader defined. A pack carrying **only** a parser is valid. Its parsers register
  through `Parsers.register(p, <jar filename>)` after the other four kinds.
- **Atomic refusal.** A parser id that a built-in or classpath parser holds, that another pack owns, or
  whose ingester class another registered parser already names, throws inside `load`. The catch
  deregisters **all five** kinds, so the pack is rejected whole: its Job Types, tokens, node types and
  executors never stay registered. The cause names the id (`job.pack.rejected`, `GET /jobs/packs`).
- **Unload / revoke** (`unload`: jar removed, hash changed, or hash no longer listed) calls
  `Parsers.deregister(owner)`. The parser leaves `GET /parsers` and `POST /parsers/{id}/preview` 404s.
- **Provenance.** `GET /parsers` rows carry `source: "pack:<jar filename>"` (the Job Type vocabulary). The
  pack's manifest id/version stay in `GET /jobs/packs`.
- ⚠ **One process-wide registry, one packs dir.** `-Djobs.packs.dir` is JVM-wide and every Space's
  `JobService` builds a manager over it, so `register` under the **same** owner replaces rather than
  refuses. Consequence (shared with the node-type overlay): one Space's unload deregisters the parser for
  all of them. A pack is normally replaced, not removed.
- Proof: `JobPackParserTest` (real jars compiled at test time, off the classpath: parser-only pack loads
  with `pack:` provenance; jar removal and hash revocation unregister; a pack whose parser is `delimited`
  is rejected and its Job Type rolled back, paired with the same mixed pack under a free id loading both
  kinds; a second pack claiming a taken id is rejected and the first keeps it). `ParsersTest` pins the
  overlay rules; `ControlApiParsersTest.catalogNamesEachParsersProvenanceIncludingAPacksParser` the wire.

**As built (slice P3, a pack parser ingests):**
- **One resolver**, `com.gamma.inspector.PluginIngesters.open(cfg)`, replaced the two one-argument
  `Class.forName(ingesterClass)` sites (`GenerationModeIngester`, `UnionModeIngester`). They now receive the
  ingester from `StreamingPluginIngestStrategy`. The Pipeline still stores only the FQCN
  (`parsing.plugin.ingester`). The resolver finds the registered parser naming that class
  (`Parsers.forIngester`). A pack-owned one is loaded through **the parser's own class loader**. Anything
  else resolves on the engine loader, as before.
- **The pack is pinned for the whole batch.** `PackRunLeases.acquire(owner)` is the same per-owner counter
  a Job Run and a pipeline-graph walk take, released when the strategy's `try` closes. An unload or
  revocation mid-ingest deregisters the parser at once, but the loader close is **deferred** until the
  ingest ends, so the batch finishes on the vetted bytes it started with. The resolver re-checks the
  registration after taking the pin, so an unload that won the race is reported, not half-used.
- **Named failure.** A Pipeline whose ingester no loaded parser names and the engine loader cannot find
  throws `IllegalStateException` "streaming ingester <fqcn> is not on the classpath and no loaded parser
  names it; if a Job Pack provided it, that pack is not loaded (removed, revoked or refused, see GET
  /jobs/packs)". There is no `ClassNotFoundException` in the cause chain. It fails the Run the same way the
  old "Cannot instantiate" error did (out of `ConsignmentIngestor.process`). That message remains for a
  class that is found but cannot be constructed.
- Proof: `JobPackParserTest` — a pack ingester ingests two CALL rows end to end, paired with the probe that
  `Class.forName(fqcn)` on the engine loader throws `ClassNotFoundException`. An unload while the ingester
  is blocked leaves the pack draining until the ingest ends. **Mutation-checked**: with the pin removed,
  that test goes red on the `isDraining` assertion. A Pipeline naming an unloaded pack's ingester gets the
  named error.

**As built (slice P4, D4 preview gate):**
- `ParserRoutes.preview` checks `Parsers.ownerOf(id)` after the 404 and, for a pack parser, calls
  `ApiContext.requireCapability(ex, "canAuthorWorkbench")` (a literal, like every gate) **before** any pack
  code runs. `canAuthorWorkbench` is the capability that authors Pipelines (`PUT /pipelines/{id}/graph`),
  so previewing third-party code over caller-chosen bytes has the same bar as configuring its ingest.
  Built-in and classpath parsers stay open.
- **Recorded, not silent.** The gate depends on the parser, and the manifest can only say all-or-nothing
  per route, so the route stays in `CapabilityManifest.EXEMPTIONS`. Its category is the new
  **`provenance-gated`**, and the reason names the in-handler gate. That is the same shape as `POST /spaces`
  (`recovery-route`). The route-gating evidence (`compliance/evidence/route-gating.md`) shows that category.
- ⚠ Like every gate, it is a **no-op without a Subject** (Personal). D7 (no edition gate) means the trust
  gate itself is identical in every edition; this preview gate only bites where an authenticator runs.
- Proof (real HTTP, forced authenticator): `ControlApiPackParserPreviewTest` — a viewer gets 403 and the
  pack's `preview` is never called; the same request as an author gets 200; the same viewer previews
  `delimited`; an unknown id is still 404; no authenticator ⇒ 200. The refusal test was red before the gate.

**As built (slice P0, where the loaded bytes live):**
- The staged copy is what the `URLClassLoader` reads **and** what the allowlist hash is taken of, so it no
  longer sits in the system temp dir, which any local process can write. `JobService` passes
  `JobPackManager.stagingRootFor(auditDir)`: `-Djobs.packs.stagingDir` when set, else
  `<auditDir>/job-packs-staging` (the Space's own server-written state dir). Each manager creates one
  unique owner-only `job-packs-*` dir inside it, so Spaces sharing a JVM-wide root never delete each
  other's copies.
- **A staging root inside the packs dir refuses boot** (`IllegalStateException` naming both paths): a
  packs-dir writer could otherwise rewrite staged bytes after they were hashed.
- The 4- and 5-argument constructors are test conveniences that stage in the sibling `<packs dir>.staging`.
  Production never uses them.
- **Orphaned staging dirs are swept at the next use (2026-10-06).** Each manager holds an OS file lock on
  the sibling `<job-packs-*>.lock` for its whole life; the OS drops it when the process dies. Before a
  manager creates its own dir, `JobPackManager.sweepOrphans(root)` removes a `job-packs-*` dir only when
  that lock can be taken now — so a live owner (this JVM or another process) is never touched. A dir with
  **no** lock file (made before this change, or a live manager mid-creation) cannot be proven orphaned
  and is **kept** — delete those by hand. Containment: a symlink entry is skipped (`NOFOLLOW_LINKS`); a
  dir whose real path is not `<real root>/<name>` (a Windows junction) is skipped; only regular files
  directly inside are deleted, never followed or recursed, and a dir holding anything else stays.
  Proof: `JobPackStagingSweepTest` (orphan removed with its lock; in-JVM lock kept; a lock held by a
  **child process** kept, then swept once it exits; no-lock dir kept; symlink/junction escape untouched;
  a second manager sweeps a crashed dir but not the first manager's). **Mutation-checked**: ignoring the
  lock turns three tests red; dropping the `NOFOLLOW` and real-path checks turns the junction test red.
- Proof: `JobPackStagingTest` (the copy's parent is the given root; a root inside the packs dir refuses
  boot; the root is the audit dir unless overridden). **Mutation-checked**: back on `createTempDirectory`,
  the first test goes red on the parent assertion.

---

## P.12 Decode Profile — one vendor's decode settings, kept once (2026-09-25, slices C1–C4)

Operator decisions 2026-09-25: **D5** a key the Pipeline sets wins, and `segments` is **replaced whole**,
never merged. **D6** a bundle carries the profile as its own satellite and rewrites the refs inside it,
refusing basename collisions, and never inlines it. **D9** no explode-style option for repeated children
until prevalence is measured (since measured and built — §P.13). **D10** the name is **Decode Profile** (GLOSSARY §5).

**Shape.** A satellite `.toon` whose one top-level block is `asn1:`, holding the same keys a Pipeline's
`parsing.asn1` takes. It is not a registry kind and has no routes of its own. It is written, read and
jailed like the grammar file.

```
config/vendors/ericsson_msc/ericsson_msc.decode.toon      asn1:
                                                            grammar_file: ericsson_msc.asn
                                                            root_type: CallEventRecord
                                                            strictness: BER
                                                            segments:
                                                              moCallRecord: mo_call_schema.toon
config/msc/msc_switch1_pipeline.toon                      parsing:
                                                            frontend: asn1
                                                            asn1:
                                                              profile_file: ../vendors/ericsson_msc/ericsson_msc.decode.toon
                                                              max_value_bytes: 4194304
```

**One overlay, `com.gamma.etl.DecodeProfile.overlay`.** The load (`PipelineConfigParser.asn1PluginBlock`, before
its required-key checks, so a profile-backed Pipeline hits the same refusals) and the preview
(`Asn1ParserPlugin.preview`) both call it, so a profile that previews is the profile that ingests:
- `profile_file` must end `.toon`. That is checked before anything touches the disk. The ref resolves
  beside the referring config (`PathJail.resolveConfigRef`) and is jailed (`requireUnderAny(allowedRoots())`)
  **before** the readability probe, so an escaping ref is an escape (403 on the preview), never "not
  readable".
- Every relative ref **inside** the profile (`grammar_file`, the `segments` values) resolves **beside the
  profile** and comes back absolute. The downstream resolvers still jail it: `Asn1GrammarSource` for the
  grammar, `resolveSchemaRef` for a segment. A `schema/<id>` registry ref is left as an id.
- Keys the Pipeline sets win, key by key. A **null or blank value is unset**, not an override, so an empty
  `grammar_file` cannot clear the profile's.
- Refused at load: a profile that names another `profile_file` (no nesting), a file with no `asn1:` block,
  and a file holding anything besides `asn1:`.
- ⚠ **Nothing of a non-profile file is echoed.** The ASN.1 preview is open to any authenticated user and
  any `.toon` under the roots can be named, so a TOON parse failure does not quote the codec's message and a
  file without `asn1:` does not list its keys. Only a real profile's own extra keys are named.
- The profile, its grammar and its segment schemas are in `referencedFiles()` (reload trigger + bundle
  closure). A segment the Pipeline replaced is not.

**Save gate.** `ConfigSafetyValidator` (pipeline) refuses a `parsing.asn1.profile_file` that is not `.toon`,
or that escapes, with an ERROR finding, so `/config/write`, `/config/patch` and bundle import answer 422
before the load would refuse it. Not a `ConfigSpecs` / accepted-key census entry: the census stops at the
top-level `parsing` block, and the served `grammarSchema` (which now lists `asn1.profile_file`) is the
declaration for `asn1.*`.

**The Parse drawer (UI).** The served form holds every `asn1.*` field at its default. On the server every
key the Pipeline sets wins over the profile, so persisting the whole form would silently fork the Pipeline
off its profile (`record_header_length: 0` over the profile's `4`). `decodeProfileOverrides`
(`pipeline-parse-definition.component.ts`) keeps only `profile_file`, `segments`, keys the saved block
already set non-blank, and keys the user changed (`GrammarEditorComponent.dirtyKeys`, FLAT control keys —
`asn1__strictness`; the function matched a dotted spelling until 2026-09-25, which silently dropped every
such edit on Save). Blank values are always dropped. The drawer's preview sends the same subset with its
`subdir`. A block without a profile is untouched.

**The drawer reads the profile back (2026-09-25)** through `GET /parsers/asn1/profile?profile_file=<ref>&subdir=<pipeline dir>`
(`ParserRoutes.decodeProfile`), which returns `{asn1: <the profile's own block, AS AUTHORED>}` — refs stay
relative to the profile, never the resolved absolute paths. It reads through `DecodeProfile.read`, the half of
`overlay` that resolves, jails and validates, so the read and the overlay cannot disagree on what a profile
is: `.toon` checked first, jail before the readability probe (escape **403**), and the same never-echo error
text (**422**: a `.toon` that is not a profile, or not valid TOON, is refused without quoting it). Missing
`profile_file` or an absolute `subdir` is **400**. `subdir` means what it means on the preview. Read-only, so
ungated (a GET needs no manifest entry) — open to the same users as a built-in's preview. The drawer seeds its
form with the profile's values under the Pipeline's and lists each key's provenance (*from profile* / *set
here* / *default*) — see [grammar-config.md](../config/user-guide-grammar-config.md). Save is unchanged.
**Profile segments in the drawer (2026-10-06).** The same section lists the profile's own `segments`
(key → schema ref, as authored, `profileSegmentsOf`), marked *replaced by this Pipeline's own* when the
Pipeline carries `segments` (D5: replaced whole). A profile-backed Pipeline with **no** `segments` of its own
and an empty segments editor now Applies without a `segments` key and writes no segment schema — the
inherited ones are the profile's. This is checked only once the profile has been read and names segments;
an unread, unreadable or segment-less profile still meets *Add at least one segment* (the load refuses a
plugin ingester with no segments anyway). Proof: the *Decode Profile read-back* block of
`pipeline-parse-definition.component.spec.ts` (list + a11y, Apply without segments, the negative refusal).

**Bundles (D6).** Export ships the profile byte-verbatim as its own satellite beside its grammar and schemas,
and the Pipeline keeps only `profile_file` (never inlined, which would import as N un-shared copies). Two
different files under one basename are the existing **409**. Import rewrites `parsing.asn1.profile_file`
(`rewriteSatelliteRefs`) and, inside the landed profile, `asn1.grammar_file` and the `segments` values
(`PipelineBundleRoutes.rewriteProfile`) to the bundled basenames, before the satellites land and the save
gate runs. The manifest sha256 is checked against the bytes as shipped.

**Scope.** ASN.1 only. XML's `ingester_config` has the same per-vendor shape but no customer asking. The
legacy tree operations on repeated children (`flattenList` / `reduce` / `cartesianJoin`) have no home on
purpose (D9; what was built instead is in §P.13).

Proof: `UnifiedParsingBlockTest` (profile-only load with refs beside the profile; Pipeline scalar wins;
blank is unset; `segments` replaced whole; escape; `.txt` refused; nesting and extra blocks refused),
`ConfigSafetyValidatorTest.aDecodeProfileRefIsJailedAndMustNameAToonFile`,
`ControlApiParsersTest.asn1PreviewHonoursADecodeProfileBesideThePipelineSubdir` (profile preview equals
inline, the Pipeline's `root_type` wins, escape 403),
`ControlApiPipelineBundleTest.aDecodeProfileTravelsAsItsOwnSatelliteAndItsRefsAreRewritten` +
`twoProfileSatellitesSharingABasenameAreRefused`,
`DemoCorpusIngestTest.mscCdrSplitIntoADecodeProfilePreviewsAndIngestsIdenticallyToInline` (the msc_cdr demo
split into profile + Pipeline: same preview tree, the same rows in every segment),
`ControlApiDecodeProfileReadTest` (real HTTP with a viewer Subject: as-authored values beside the subdir,
escape 403, `.asn` 422, two non-profile `.toon` files refused without echoing a marker, 400s), and the drawer
specs `decodeProfileOverrides` + *Decode Profile read-back* (seeded effective values, provenance, an edited
profile key becomes *set here* and Save writes only the overrides, late catalog + late profile, read failure;
mutation-checked: without the seed or with the dotted dirty key, four go red). **Mutation-checked**: merging `segments`, probing readability before the jail,
dropping the preview overlay, and dropping the in-profile import rewrite each go red.

---

## P.13 Deferred by decision (the trust design's open items)

- **D8 — out-of-process parser host (T5).** The only real containment for parser code, deferred until a
  customer requires running parser code nobody has reviewed. Classloader isolation is not a security claim.
- **D9 — tree operations on repeated children.** **Measured 2026-10-11** (main checkout, read-only; DATA-GOV-1
  respected: grammars, tx configs and legacy code only, no captures read). 24 of the 31 operator ASN.1 grammars
  (gitignored `providers/asn-parser/config/`) parse; they hold 1266 repeated-child occurrences (595 distinct
  element types). In the grammars that keep OPTIONAL, 39% of occurrences have a mandatory component; 7 grammars
  have OPTIONAL stripped, so the grammar alone cannot say. **Prevalence is many.** Operator use settles it:
  every vendor family with a real legacy tx config (Ericsson OCC/CCN, GGSN/PGW, Huawei GMSC, Huawei/Aftel IMS,
  TAP, NRTRDE) derives core columns from a repeated child (subscriber identity ← `servedSubscriptionIDs`,
  DA charge ← `dedicatedAccounts`, volume / duration ← `usedServiceUnits`, PGW billable volume ←
  `listOfServiceData` group-by + sum) or runs flatten / reduce / delimit on one (23 directive uses in 9 configs).
  ⚠ "Give it its own segment" is **not available for ASN.1 today**: `Asn1RecordIngester` keys segments by record
  name only (~:124) and NULLs any `List` (~:189), so those columns load NULL. The "cheaper before C1" window has
  passed (C1–C4 shipped 2026-09-25). The design states no numeric threshold. **Operator decided 2026-10-11 to design it —
  ✅ designed and operator-approved 2026-10-11; slices 1, 2 and 3 SHIPPED** (`derive` select / reduce, `children` explode, drawer list):
  [`docs/archived-documents/plans-archive/parsing-repeated-child-design.md`](../../../../archived-documents/plans-archive/parsing-repeated-child-design.md).
  - **`derive` (slice 1).** An optional `derive` block under `asn1:` (segment key → column → operation; closed vocabulary, no expression)
    is compiled by `com.gamma.etl.DeriveSpec` (validated on `DecodeProfile.read` and at config load, `ASN1-DERIVE-*`
    codes; replaced whole on overlay) and evaluated per record by `Asn1RecordIngester`: VARCHAR derived columns
    after `raw.fields`, before `EVENT_TYPE`; NULL for missing path / empty list / no match / non-numeric (never 0);
    row cardinality unchanged; no `derive` = byte-identical rows. ⚠ As-built gotchas: a `SEQUENCE OF X` decodes as
    `{field: {X: [..]}}`, so the list path is `field.X` (explicit, no auto-unwrap); TOON has no flow syntax, so
    write `where:` as a nested block. `max_list_elements` (default 10 000,
    ceiling 1 000 000) over-cap fails the file. **`group_by` was REMOVED from the `derive` vocabulary 2026-10-12**
    (operator D6): it never changed the scalar; a config that still names it is refused with
    `ASN1-DERIVE-UNKNOWN-OP` ("group_by was removed ... use a children explode"). One row per group is the explode.
    `select` equality matches the **stringified** leaf (numbers and enum names alike). The per-file counters
    `derive_non_numeric` / `derive_no_match` are logged at INFO, not written to a sink summary.
  - **`children` (slice 2, SHIPPED 2026-10-12)** — `children:` (parent segment key → list path → child segment key; validated by
    `com.gamma.etl.ChildSpec` on `DecodeProfile.read` and at load; replaced whole on overlay): each element of the
    list becomes a row of a DECLARED child segment, besides the record's own row. The child schema's selectors
    resolve relative to the child element, plus `$index` (0-based, as a string) and `$parent.<dotted leaf>`; no
    engine surrogate key (the parent leaf is the join key); any other `$...` selector is refused
    (`ASN1-CHILD-BAD-SELECTOR`). Codes: `ASN1-CHILD-UNKNOWN-SEGMENT` (parent or child not in `segments`),
    `-BAD-PATH`, `-SELF`, `-NESTED` (explode is one level), `-LIMIT`; runtime `ASN1-CHILD-TOO-MANY-ROWS` fails the
    file (never truncates) when one record explodes past `max_child_rows_per_record` (default 10 000, ceiling
    1 000 000, summed over a record's lists). A child segment is not matched by record name (a record carrying its
    name is junk); a `derive` on a child segment runs over the child element. TOON accepts the dotted list path as
    a map key (`usage.usage: Usage`). Tests: `ChildSpecTest`, `Asn1RecordIngesterChildTest` over the synthetic
    `repeated_child` case, and over `repeated_child_nested` (the child element holds a nested list, so a child `derive` yields real sum / count / first values, `PARSING-CHILD-DERIVE-CORPUS-1`).
  - **Slice 3 SHIPPED 2026-10-12** — the Parse drawer lists the effective `derive` read-only
    ("N derived columns on M segments", segment / column / operation; the Pipeline's own `asn1.derive` wins whole
    over the profile's). **Preview rows SHIPPED 2026-10-12** (`PARSING-PREVIEW-DERIVED-1`): the derived columns are
    served beside the record Tree as `ParseResult.Rows` (see `previewRows`, §P.9) and shown as a drawer table.
  - **Still open (deferred by decision, design archived 2026-10-22):** the counters channel stays log-only (operator D5:
    `RecordSink` is not widened); `ASN1-DERIVE-NOT-IN-GRAMMAR` (a list path that is not a `SEQUENCE OF` in the
    compiled grammar) is not implemented — it needs a walk over the compiled grammar's schema, and since 7 of 24
    grammars have OPTIONAL stripped it could check path existence only, never mandatory-ness; slice 4, an optional
    profile editor for `derive` / `children`, is operator-gated (the drawer stays read-only; row
    `PARSING-DERIVE-EDITOR-1`).
- **T2 — signer anchoring** (`-Djobs.packs.trustStore`, SEC-7). Not built. D2 made T1 the required gate.

---

## P.14 BER hostile-input handling — fixed 2026-09-17, value cap 2026-09-24

✅ **`BER-LENGTH-OVERFLOW-1` (P1) is closed.** A long-form 8-byte length of `Long.MAX_VALUE` made
`valueOffset + valueLength` wrap negative, so the `end > limit` guard passed and `BerReader` returned a Tlv
with a **negative `endOffset`** that `RecordReader` counted as `recordsOk++` and used as its cursor — a
malformed record recorded as successfully parsed. Both sites now compare against a remaining-bytes budget
(`valueLength > limit - valueOffset`), where both operands are non-negative and the subtraction cannot
overflow. ✅ **`BER-FRAMING-UNCHECKED-READ-1` too**: `Framing.Fixed.recordLength` bounds its header read, and
`RecordReader` takes the framing calls inside the recovery `try`.

⚠ **A truncated TAIL reports `ParseError(STOP_FILE)` even under `RecoveryPolicy.SKIP_RECORD`** — a header that
cannot be read yields no boundary to resync to. Records already read are still delivered.

✅ **`BER-VALID-BUT-HUGE-ALLOCATION-1` is closed (2026-09-24) with a configurable single-value cap** —
operator decision: a cap, not a streaming accessor, so `Tlv.value()` keeps its `byte[]` return type. The
hazard was a length that is *valid* but enormous: measured with a 3 GB stub source, `04 84 95 02 F9 00`
parsed cleanly, and just under 2 GB `Tlv.value()` would have allocated a `byte[]` sized by
attacker-controlled input. No bounds check can reject that, because the length is legitimate.

- **Where it lives:** `BerReader.read(src, offset, limit, strictness, maxValueBytes)` refuses a
  **primitive** value whose declared length exceeds the cap, as a `BerParseException`:
  `value of [APPLICATION 3] declares 5 bytes, over the max_value_bytes cap of 4 (at offset 2)`. The check runs
  at parse time, after the in-bounds check (a truncated value still reports truncation), so no Tlv exists
  for anything to allocate from. The cap passes through `RecordReader` (6-arg constructor) and
  `Asn1Decoder.decode(…, maxValueBytes)`. The old signatures still exist and use the default. A
  refusal is an ordinary record failure: it reaches the `ErrorListener` as a `ParseError`, and
  `SKIP_RECORD` skips a length-prefixed record the way it skips any other bad record.
- **Constructed values are not capped.** Their length only bounds where children are parsed, and no
  decoder copies a constructed value. A record far larger than the cap decodes as long as each leaf is under it.
- **Default `BerReader.DEFAULT_MAX_VALUE_BYTES` = 64 MiB.** Measured 2026-09-24 over every shipped
  sample (`MSC01_20260801_0800.ber`, `CDR_20260801.ber`, `corpus-synthetic`) and the operator corpus
  (Huawei IMS/MSC, Ericsson CCN/OCC/SDP, IMS and SGSN CDRs): the largest primitive value is **128 bytes** and
  the largest record about 9 KB. 64 MiB therefore refuses no real file and still caps the worst case at an
  allocation any JVM that runs the engine can afford.
- **Pipeline config:** `asn1.max_value_bytes` (a served `grammarSchema` field, so the preview and the Parse
  drawer honour it) and `ingester_config.max_value_bytes`. `frontend: asn1` carries it across the same way
  as the framing knobs (`PipelineConfigParser.asn1PluginBlock`). One parser, `Asn1ParserPlugin.maxValueBytes`,
  serves both spellings, so the cap a preview uses is the cap an ingest uses. Unset or blank means the
  default. Anything that is not a whole number ≥ 1 is a config error naming the key. Because of the
  *any parse error fails the file* behaviour (§P.10), an over-cap value quarantines the file `QUARANTINED_UNREADABLE`.
- Not a `ConfigSpecs` / accepted-key census entry: `ConfigSpecs` declares no `parsing.asn1.*` key,
  and the pipeline census stops at the top-level `parsing` block. The parser's served
  `grammarSchema` is the declaration for `asn1.*`.

---

## References

- [architecture.md](architecture.md) — engine architecture, the M..N multiplexer, back-pressure, the
  Consignment status flow, the format-specific configuration principle; acquisition (Collectors, fetch,
  discovery — getting files to the inbox)
- [spec-pipeline.md](spec-pipeline.md) — [node types, the token model, transform seams](spec-pipeline.md#part-i--the-pipeline-model) ·
  [execution lanes, Build→Test→Run containment, post-sync step chains](spec-pipeline.md#part-ii--execution) ·
  [the graph-lane sink kinds; quarantine](spec-pipeline.md#part-iii--sinks)
- [spec-db-layer.md §3.9](../engine/spec-db-layer.md) — the catalog's DDL and its two readers
- [operations-reference.md](../../layer/platform/admin-guide-operations-reference.md) — `retire_superseded`
- [HA/DR drill runbook](../../layer/platform/admin-guide-ha-dr-drill.md)
- [admin-guide-configuration-reference.md](../config/admin-guide-configuration-reference.md) — every config key, incl. the fixed-width frontend
- [user-guide-grammar-config.md](../config/user-guide-grammar-config.md) — authoring Grammars, the Parse drawer
- [spec-component-registry.md](../processor/spec-component-registry.md) — Grammar Templates as components
- [ELT plan](plan.md) — the ING capability spec (requirements of record)
- Archived designs: [delimited-grammar-design.md](../../../../archived-documents/plans-archive/delimited-grammar-design.md) ·
  [parser-plugins-trust-design.md](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md) ·
  [parsing-repeated-child-design.md](../../../../archived-documents/plans-archive/parsing-repeated-child-design.md)
- [GLOSSARY.md](../../../../GLOSSARY.md) §2 *Consignment*, §5 *Parser*, *Grammar*, *Grammar Template*, *Decode Profile*, §6-B
