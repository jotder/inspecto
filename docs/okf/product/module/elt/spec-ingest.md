---
type: Concept
title: Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest
description: How files are ingested into the DuckDB core: the one emit-based ingestion SPI and its one sink, union/generation execution modes, segment schemas and the two partition-declaration contracts, output layout, the shipped ingesters and the plugin author workflow, Consignment coordination, failover and graceful stop, write-path identity and output_per_member, branch-aware route: ingest, and the contract tests.
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java, platform/inspecto-engine/src/main/java/com/gamma/consignment, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java, platform/inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java, platform/inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
---

# Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. **spec-ingest.md** · [spec-consignments.md](spec-consignments.md) · [spec-parsing.md](spec-parsing.md)

**Contents**

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

**What this covers.** Ingest is the step where a landed file is read, shaped and written as partitioned
Table files. DuckDB does the loading and writing for every format; formats DuckDB cannot read are decoded
in Java by a plugin that feeds records in. This part explains that plugin contract, how big and small
files are handled, how files are grouped into Consignments and committed (including what happens when a
node dies or a Space stops mid-run), how output files are named so a retry does not duplicate rows, and
how a Pipeline with `route:` branches writes each branch on the ordinary ingest path.

> Vocabulary: a Consignment is the unit a Step's token references (data resolved by reference; edges
> carry no records) — the full runtime edge model converges at Phase 7
> ([spec-pipeline-model.md](spec-pipeline-model.md#the-token-model--the-design-of-record) § *The token model*). The parse mechanisms and the
> `parsing:` block are [Part III](spec-parsing.md) below; acquisition (getting files to the inbox) is
> [spec-acquisition.md](spec-acquisition.md) and [architecture-elt.md](architecture-elt.md).

## Plugin ingester

**Direction it implements** (canonized 2026-08-19, E3 of the delimited-grammar-properties plan Part II):
DuckDB *is* the execution core — it loads and writes everything; formats DuckDB cannot read natively
**wrap** the core by parsing records in Java and feeding them in. This section names the shipped seam so
nobody rebuilds it.

Use the plugin ingester when:

- The file format is binary, proprietary, or otherwise not parseable by `CsvIngester`.
- A single input file emits **multiple event types** (e.g. CALL + SMS interleaved in one CDR file) that must land in separate output tables with different schemas.
- Partition columns such as `event_type` are **computed by the parser** — they do not exist as raw data columns but are derived from the record structure.

The CSV path is unchanged for all existing Pipelines. Plugin ingester is an opt-in mode activated by adding `processing.ingester:` (or `parsing.plugin.ingester`, see [§P.5](spec-parsing.md#p5-the-parsing-block-live) below) to the pipeline toon.

### The SPI pair

- **`StreamingFileIngester`** (`inspecto-etl`, `com.gamma.etl`;
  `platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java`) — `ingest(File,
  RecordSink, srcId, PipelineConfig)`. It is the **only** plugin ingestion SPI (the old whole-file
  `FileIngester` was removed in v3.11.0). One implementation per binary/grammar-driven format. Chosen
  **by config** (`processing.ingester` / `parsing.plugin.ingester`, an FQCN instantiated reflectively) —
  this is deliberate and correct for config-chosen implementations; it is *not* a `ServiceLoader`
  candidate, because the config names one class, not a discovered set. (A pack-provided ingester resolves
  through its parser's loader — [§P.11 *Drop-in parser jars*](spec-parsing.md#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25) below.)
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
[§P.10 *Loading to Tables*](spec-parsing.md#loading-to-tables--asn1recordingester-2026-07-31) below.

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
  [§A.2](spec-consignments.md#a2-event-time-at-write) below).

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
three-tier precedence is in [§P.5](spec-parsing.md#p5-the-parsing-block-live) below.)

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
[§P.6.3](spec-parsing.md#p63-fixed-width--fixed-length-records-live) below. Reach for this plugin only when records are not newline-delimited.)

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
§P.10 below: [*The tree→segments bridge*](spec-parsing.md#the-treesegments-bridge--xmlrecordingester-2026-08-30) and [*Loading to Tables*](spec-parsing.md#loading-to-tables--asn1recordingester-2026-07-31).

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
- Reference your class by FQCN in the pipeline toon: `processing.ingester: com.acme.events.MyIngester`. The framework loads it by FQCN reflection and `getDeclaredConstructor().newInstance()` — the class must be public with a no-arg constructor. On the classpath it resolves on the engine loader. Shipped in an allowlisted Job Pack instead (no restart), it resolves through the pack's loader when the pack's `ParserPlugin` names it in `ingesterClass()` — see [§P.11 *Drop-in parser jars*](spec-parsing.md#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25) below.

**6. Production health checks.** The framework reports plugin loading at startup:

```
[CONFIG] Plugin ingester: com.acme.events.MyIngester  segments: [CALL, SMS]
```

If you see `Cannot instantiate streaming ingester: ...` with a `ClassNotFoundException`, your JAR isn't on the classpath. If you see it with a `NoSuchMethodException`, you're missing the public no-arg constructor. A `ClassCastException` means the class doesn't implement `StreamingFileIngester`. (Since slice P3, an ingester that no loaded parser names and the engine loader cannot find fails with the named "is not on the classpath and no loaded parser names it" error instead — [§P.11 *Drop-in parser jars*](spec-parsing.md#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25) below.)

Per-file outcomes appear in `<pipeline>_status_<runTimestamp>.csv`:

- `SUCCESS` — at least one segment produced rows; output written
- `QUARANTINED_UNREADABLE` — ingester threw (file moved to `dirs.quarantine/unreadable/`)
- `QUARANTINED_MISMATCH` — every segment emitted 0 rows (file moved to `dirs.quarantine/field_mismatch/`)

---

## 8. Consignment coordination

* `CollectorProcessor` (`platform/inspecto-engine/src/main/java/com/gamma/inspector/CollectorProcessor.java`) — the per-Pipeline ELT
  entry point, split into two halves (B3b): **`acquire(cfg)`** runs the [acquisition](architecture-elt.md)
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
  [Part II, Part B](spec-consignments.md#part-b--concurrency-the-four-layer-hierarchy) below). `run()` = `acquire` then `ingest`, the
  self-contained one-shot used by the single-pipeline CLI `main`, `reprocess`, and the service's manual "run
  now". The always-on service instead drives the two halves on **separate timers with separate budgets** — see
  [acquisition](architecture-elt.md). Ingest always walks the local inbox: a remote-fetched file, once
  landed, is discovered exactly like a locally-pushed one, so `countPending` is now the exact landed backlog
  (no remote approximation).
* `ConsignmentIngestor` (`platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java`) — a thin, stateless
  coordinator: pick a `ConsignmentIngestStrategy` ([spec-pipeline-model.md](spec-pipeline-model.md#transforms--modularity-seams), transform seams) (CSV or plugin), run `ingest()` → an
  `IngestOutcome`, then the path-agnostic tail `commit()` (DuckLake register → manifest → backup originals →
  markers → ledger, in that crash-safe order) and `writeAudit()`. Never throws for a Consignment failure — audit is
  always written (see [quarantine](spec-sinks.md#quarantine-outcomes)). Markers/fingerprints go **last**, so a FAILED
  Consignment leaves no "already processed" record and its files are rediscovered next poll (retry is
  implicit); what is recorded about a Consignment either way — ledgers, provenance, live gauges, and how
  an operator audits a failed file or record — is [consignment status flow](architecture-elt.md#e5-consignment-status-flow-the-flowfile-question).
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
  (How the Space close escalates an overrun drain is [architecture-elt.md](architecture-elt.md#e6-stopping-a-space-graceful-stop).)
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
[spec-pipeline-execution.md](spec-pipeline-execution.md#execution-lanes).

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
2026-10-06 — the routed rows are replicated to every destination ([spec-sinks.md](spec-sinks.md#multi-destination-sinks-shipped-2026-08-02--0cdc9dff--79dcb3e6) § *Refusals*).

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

## References

- [architecture-elt.md](architecture-elt.md) — engine architecture, the M..N multiplexer, back-pressure, the
  Consignment status flow, the format-specific configuration principle; acquisition (Collectors, fetch,
  discovery — getting files to the inbox)
- [spec-pipeline-execution.md](spec-pipeline-execution.md) — [node types, the token model, transform seams](spec-pipeline-execution.md) ·
  [execution lanes, Build→Test→Run containment, post-sync step chains](spec-pipeline-execution.md) ·
  [the graph-lane sink kinds; quarantine](spec-sinks.md)
- [spec-db-layer.md §3.9](../engine/spec-db-layer.md) — the catalog's DDL and its two readers
- [operations-reference.md](../../layer/platform/admin-guide-operations-reference.md) — `retire_superseded`
- [HA/DR drill runbook](../../layer/platform/admin-guide-ha-dr-drill.md)
- [admin-guide-configuration-reference.md](../config/admin-guide-configuration-reference.md) — every config key, incl. the fixed-width frontend
- [user-guide-grammar-config.md](../config/user-guide-grammar-config.md) — authoring Grammars, the Parse drawer
- [spec-component-registry.md](../processor/spec-component-registry.md) — Grammar Templates as components
- [ELT plan](plan-elt.md) — the ING capability spec (requirements of record)
- Archived designs: [delimited-grammar-design.md](../../../../archived-documents/plans-archive/delimited-grammar-design.md) ·
  [parser-plugins-trust-design.md](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md) ·
  [parsing-repeated-child-design.md](../../../../archived-documents/plans-archive/parsing-repeated-child-design.md)
- [GLOSSARY.md](../../../../GLOSSARY.md) §2 *Consignment*, §5 *Parser*, *Grammar*, *Grammar Template*, *Decode Profile*, §6-B
