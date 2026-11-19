---
type: Reference
title: Configuration reference — Configuration reference and Spaces
description: Part 1 of 2 of Configuration reference: Configuration Reference; Spaces (multi-project layout)
tags: [config, toon, spaces, type-mapping, postgres]
timestamp: 2026-07-16T00:00:00Z
---

# Configuration reference — Configuration reference and Spaces

Part 1 of 2 of [Configuration reference](admin-guide-configuration-reference.md) · next: [The collector block, type mapping and the Postgres state store](admin-guide-configuration-reference-collector-types.md).

## Configuration Reference

The framework uses three config files in `.toon` format (JToon). Only the generation config is hand-authored; the other two are machine-generated and then maintained.

Config files live under a space's `config/<adapter>/` directory (e.g. `spaces/<id>/config/<adapter>/`; see [Spaces](#spaces-multi-project-layout) below).  A relative data path (`dirs.*`, `sinks[].database`, an enrichment's `input`/`output.database` and `references.<n>.path`, a `local` connection's `base_path`) resolves under the config's **Space directory** (`spaces/<id>/`), so shipped configs say `data/orders/database` (`DATA-DIRS-RESOLVE-AGAINST-CWD-1`, 2026-09-23; see [config safety](spec-config-safety-path-containment.md#decision-2026-09-23--a-relative-data-path-resolves-under-the-space-directory)). With no Space — a single-tenant example directory — it keeps the working-directory reading, which is the launch directory the example scripts `cd` into.

**Config refs are the exception: they resolve beside their own config, and ONLY there (W1b 2026-07-31 added config-relative first; `SCHEMA-FILE-RESOLVES-AGAINST-CWD-1` 2026-09-23 removed the working-directory second).** The portable form is a bare basename — `schema_file: orders_schema.toon` next to `orders_pipeline.toon` — and every shipped config now uses it. That makes a space directory relocatable/renamable/importable with no edits, and lets the server launch from any directory. ⚠ A repo-root-relative ref (`spaces/<id>/config/…`) no longer loads: it is refused naming both paths when that file exists from the CWD, and not found otherwise — respell it beside its config. See [config safety](spec-config-safety-path-containment.md#path-containment-one-primitive-five-callers). Applies to `schemas[].schema_file`, `parsing.<asn1|plugin>.segments` values and — since a later consistency fix — `processing.grammar` as well. `dirs.*` are data paths and resolve under the Space directory instead (above) — two bases, both independent of the working directory: in one file `orders_schema.toon` sits beside the config while `dirs.poll: data/inbox/orders` means `<space>/data/inbox/orders`.

**Since W3 (2026-08-17) the bare form is what the product WRITES**, not merely what it tolerates: the Pipelines Parse drawer, the stream-config import planner and the `orders-starter` space template all emit `<name>.toon`. Reading portable refs had worked since W1b while every writer still emitted `spaces/<space>/config/<name>.toon`, so no config was portable in practice unless hand-edited. Pre-W3 long-form refs were respelled in 2026-09-23 (above); the UI compares a stored reference by **name**, never by path, so it still recognises its own long-form schemas.

## Spaces (multi-project layout)

One server can host **many isolated spaces** (projects) at once. A space is a self-contained directory under a
container root, resolved in this order: `-Dspaces.root=<dir>` → `SPACES_ROOT` → a `spaces.ref` pointer file at the
app root → `<app root>/spaces`. The **app root** is `-Dapp.root` / `APP_ROOT`, else the working directory, and the
launchers change to the bundle root first, so by default it is the install. `spaces.ref` holds one line, the spaces
root, written rooted or relative to the app root; present but empty, or naming something that is not a directory, is
a loud boot error rather than a silent fall back to `./spaces`. Every launcher — `serve.sh`, `serve.bat` and both
`serve-demo.*` — passes `-Dspaces.root` only when `SPACES_ROOT` is set, so the pointer is reachable from a shipped
bundle (`SPACE-RELOCATABLE-ROOTS-1`); the flag wins over it, so one that always passed the flag would ignore the
pointer silently. `tools/check-launchers.mjs` proves this by EXECUTING all four:

```
spaces/
  <id>/                     # id: [a-z0-9-], 1–63 chars, not starting with '-'
    space.toon              # manifest: display_name, description, created_at, optional data_root
    config/                 # this space's *.toon tree (scanned at boot; hot-reloadable); every file in it:
                            #   architecture-space-anatomy.md
      pipelines/            # authored pipelines (`flows/` is read only as a legacy fallback)
    data/  ( data/events/ ) # partition output, inbox, backups  ( + rolling Parquet events )   ┐ under the
    audit/                  # run journal, watermarks, commit logs                               │ data root
    duckdb/                 # this space's *.duckdb / *.db stores                                ┘
  <id>.ref                  # a Space reference: one line, the path of a Space folder kept elsewhere
                            #   (rooted, or relative to the app root)
```

**Data root — config here, high-growth state on a bigger disk.** `data/`, `audit/` and `duckdb/` live under the
Space's data root: `data_root: <path>` in `space.toon` — rooted, or relative to the app root — else
`-Dspaces.data.root=<mount>` → `<mount>/<id>`, else the Space folder. Configs are unchanged — `data/orders/inbox` or `../data/backups` resolve onto the data root.
Example (all configs in `C:\sandbox\spaces`, all data in `C:\data`):

```
-Dspaces.root=C:\sandbox\spaces -Dspaces.data.root=C:\data
C:\sandbox\spaces\demo\{space.toon, config\}      C:\data\demo\{data\, audit\, duckdb\, .inspecto-space}
```

The launchers (`serve.bat`, `serve.sh`, `serve-demo.*`) pass `SPACES_DATA_ROOT` as `-Dspaces.data.root`; unset leaves
the flag off. Both `SPACES_ROOT` and `SPACES_DATA_ROOT` may be relative to the bundle root. ⚠ **They are not the
portable way to attach a showcase**, because neither names a `data_root` — and a blank `data_root` is stamped with
the Space's ABSOLUTE path, so the install boots nowhere else once it has been started. Name both roots in files
instead (LA-SHOWCASE-DIST-1 + `SPACE-RELOCATABLE-ROOTS-1`; `tools/package-la-showcase.mjs` writes the `data_root`):

```
<parent>\inspecto-la\                  the product download
<parent>\inspecto-la\spaces.ref        one line: ../inspecto-la-spaces
<parent>\inspecto-la-spaces\<id>\      the Space download: space.toon (data_root: ../inspecto-la-data/<id>) + config\
<parent>\inspecto-la-data\<id>\        the data download: data\ (then the Space's audit\ and duckdb\)
inspecto-la\bin\serve.bat
```

Both roots are then relative to the bundle, every stamp is written relative, and `<parent>` may be moved, copied or
re-zipped whole at any time — started or not.

`space.toon` may name `min_product_version: "<x.y.z>"`; at boot each Space asking for a newer product than the running
one is named in a WARN (it still boots). A `dev` run checks nothing.

Moving an existing Space: stop the server, move its `data/`, `audit/`, `duckdb/` into `<data root>/` (nothing else
may be in that folder), set the flag or `data_root`, start. Moving a whole INSTALL is free when every root is
relative (the stamps are written relative too); an install whose `data_root` is rooted keeps absolute stamps and
refuses every Space at the new path — stop it and delete the `.inspecto-space` files, which are then rewritten. A Space whose folder still holds files in those dirs,
or whose data root is stamped (`.inspecto-space`) by another Space folder, or whose mount is missing, is not booted
(`GET /spaces` lists it as skipped with the reason). ⚠ Keep `duckdb/` off object-storage mounts (s3fs,
mountpoint-s3): DuckDB needs random writes.

Link Analysis and Entity List records follow the data root too: `audit/snapshots/` (snapshots and Investigations),
`audit/la-uploads/` (analyst upload records, the daily quota ledger) and `audit/entity-facts/` (the identity fact
log) sit under `<data root>/audit/`. A Space with no data root keeps them in `config/audit/`, as before.
*Migration note:* nothing to do by hand. The first time the server opens one of them after a data root is set, it
moves the files from `config/audit/<dir>` to `<data root>/audit/<dir>` and removes the emptied folders. It never
overwrites: a file already in both places stays in both, the server logs a WARN naming it, and the data-root copy
is the one read — compare the two and delete the stale one by hand. A file the move could not take is still read
from `config/audit/`.

**Space references.** `<id>.ref` in the spaces root holds the absolute path of a Space folder elsewhere (e.g.
`C:\sandbox\spaces\demo` from an install's `spaces\demo.ref`). The folder must be named `<id>` and lie outside the
spaces root; a real `<id>/` beside the ref wins. Purge of a referenced Space deletes only the `.ref`.

**From Settings ▸ Spaces, without a restart** (`SPACE-LOCATIONS-1`). Set the launch setting
`SPACES_ALLOWED_ROOTS` (`-Dspaces.allowed.roots`; e.g. `D:\inspecto-spaces;E:\inspecto-data` on Windows) to the
folders you will use, then: *Attach existing Space* writes the `.ref` and starts the Space; *Detach* stops it and
deletes the `.ref`, leaving both folders; *Change data folder* writes `data_root` for a Space that holds no data yet
(no files in `data/` or `audit/`); New Space ▸ *Advanced: folders* creates the Space into a pre-made empty Space folder
(named the Space id) and data folder. Each folder is checked first and the screen shows why one is refused. A Space
folder and its data folder may not sit side by side in one parent folder (the boot refuses the data folder), so use
two parent folders as above. Without the setting these actions are switched off. Rules and routes:
[Space anatomy §2.2](architecture-space-anatomy.md).

Each space is fully isolated: its own service, scheduler, event log, stores, connection registry and metric
`space` label. Store **backends** (`status.backend`, `objects.backend`, `events.backend`, …) stay process-global
`-D` flags; only their location defaults move under the space root.

- **API:** the space segment sits **inside** `/api/v1` — `GET /api/v1/spaces/acme/pipelines`, never
  `GET /spaces/acme/pipelines` (dispatch strips `/api/v1` first, then matches `/spaces/{id}/…`; the v1
  gate re-reads the *raw* URI, so a space-outside path is never an API path at all). An unknown id is a
  `404`; `/health`, `/ready`, `/metrics` stay un-prefixed and server-global; an un-prefixed API path
  resolves the `default` (or sole) space. The wire protocol lives in
  [multi-space](../processor/architecture-processor.md) — do not restate routes here.
- **Space CRUD** is server-global and lives on the wire-protocol page — the seven routes are documented
  in [multi-space](../processor/architecture-processor.md); this file covers on-disk layout and `SpaceMigrator`
  only.
- **Single-tenant mode** (no `-Dspaces.root` AND a config/dir passed on the CLI) hosts one `default` space and is
  byte-identical to the pre-spaces behaviour; CRUD returns `409`.

### Migrating a flat deployment

There is no flat fallback — migrate the existing single-tenant layout into `spaces/default/` once:

```
java -cp inspecto.jar com.gamma.service.SpaceMigrator \
     [--id default] [--root ./spaces] [--from .] [--dry-run] <configDir>
```

It relocates `<configDir>` → `spaces/<id>/config` and the working-dir artifacts (`database/` → `data/`,
`jobs_audit/` → `audit/`, `inspecto-events/` → `data/events/`, the `*.duckdb`/`*.db` files → `duckdb/`) and writes
`space.toon`. It is **idempotent** (a re-run relocates nothing) and `--dry-run` prints the plan without moving.
**Caveat:** a config that references a schema by an *absolute* path keeps pointing at the old location after its
file moves — author relative paths, or fix them up after migrating. Custom (non-default) flat locations must be
moved by hand.

A `spaces/<id>.ref` is followed: the config and manifest go into the folder it names; a ref that cannot be followed
stops the migration before anything moves. When the Space's data lives on another volume (`space.toon data_root`,
or `-Dspaces.data.root`), `data/`, `audit/` and `duckdb/` go onto that data root, which is checked and claimed first
(an unmounted volume stops the migration). Pass the same `-Dspaces.data.root` to the migrator as to the server.

### Configuration by source format

Configuration is organized **per source format** — each format has its own
block and its own knobs, by design (see
[Design Philosophy](../elt/architecture-elt.md)). Pick the row that matches your
input; the rest of this section details each block.

| Source format | Ingest path | Key config block(s) | Notable knobs |
|---|---|---|---|
| **Delimited text** (CSV, CSV.GZ, TSV, pipe-delimited) | built-in | `processing.csv_settings` + a `schema_file` (or `schemas[]` for multi-schema) | `delimiter`, `engine` (`auto`/`duckdb`/`java`), `skip_header_lines`, `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns`, `has_header`, `date_formats`, `timestamp_formats` |
| **Messy text dumps** (SQL\*Plus exports with banners/footers, ragged columns) | built-in (Java engine) | same as above, with the messy-file knobs set | `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns` → forces `engine: java` under `auto` |
| **Fixed-width text** (column-positional records, one record per line) | built-in (native `read_csv`+`substring`) | `frontend: fixedwidth` + a `fixedwidth:` block (inline or in a `*.grammar.toon`) + a `schema_file` | `record: line`, `trim`, `min_record_length`, `fields[]{name,start,length}` |
| **Binary / proprietary / multi-event-type** (CDR blobs, fixed-length binary, anything one parser splits into several record types) | [plugin](../elt/spec-ingest-plugin-ingester.md#plugin-ingester) | `processing.ingester` + `processing.segments` + optional `processing.ingester_config` | `ingester` (FQCN), per-segment schema files, free-form `ingester_config` map for format-specific settings (`record_length`, `byte_order`, …) |

Common to **all** formats: `dirs.*`, `output.format` (`PARQUET`/`CSV`; **absent = `PARQUET`** since
2026-09-25 — see *Output format and stored types* below),
`collector.consignment.*` (legacy `processing.batch.*`), `processing.threads`, the `partitions[]` declaration in
each schema, and the audit/manifest machinery. Format-specific blocks only
cover *how the bytes become rows* — once rows exist, the M..N partition-and-write
path is identical regardless of source format.

### Output format and stored types

A declared type survives to rest **only in a Parquet store**. A CSV store is text: every reader of one
(`SqlViews` → `read_csv(…, all_varchar=true)`, used by `POST /db/query`, `GET /db/table`, Datasets and the
at-rest Steps) gets `VARCHAR` back, whatever `raw.fields` or a sibling `_structure.csv` declared — and a
typed value was already rendered as text on the way out (`42.10` → DOUBLE → `"42.1"`).

🔴 Found driving the UI 2026-09-25: a Pipeline authored in the editor (`web_orders`: a Structure CSV
declaring BIGINT/DATE/DOUBLE, a keep-only Record Transformer slot, a `sink.persistent` with no format)
ran SUCCESS and `/db/query` answered `typeof(...) = VARCHAR` for every column. Neither the parse, nor
`keep`, nor the `/db/query` path dropped the type: the write did, because an absent `output.format`
defaulted to **CSV** (`PipelineConfig.Builder.outputFormat` and `PipelineConfigParser.parseOutputAndSinks`).
The default is now **PARQUET** in the engine, in `ConfigSpecs` and in the served `NodeAttributes.OUTPUT`
table (and the SPA's `OUTPUT_ATTRIBUTES` mirror). An explicit `format: CSV` is unchanged and still
stores text, deliberately — it is the hand-off format. Every shipped Pipeline declares its format, so
only configs that omitted it change. Pinned by `TypedOutputThroughRecordTransformerTest` (engine: the
stored Parquet's types) and `ControlApiDbQueryTypedOutputTest` (the same run read back over real HTTP);
a declared `VARCHAR` field stays `VARCHAR` in both.

### How a source becomes partitioned output (the transform model)

Configuration drives a deliberately thin, **per-record** transformation. One batch of input
files becomes **one** DuckDB `CREATE TABLE … AS SELECT …`, and the three config files each own
one part of it:

| Config file | Owns | Authored |
|---|---|---|
| `*_gen.toon` (Generation) | how to *read the sample* — delimiter, junk/tail trimming, which columns are dates/timestamps | by hand |
| `*_schema.toon` (Schema) | **the transform itself** — `raw.fields[]` (bind output field → source column by zero-based `selector` + declare its type), `mapping.rules[]` (how each target column is produced), `partitions[]` (derive the Hive partition columns) | generated, then tuned |

🔴 **The rule that decides the format of a NEW config kind: flat tables are CSV, and anything nested or
secret-bearing is TOON.** The CSV kinds are deliberately Excel-editable, for the operators who onboard
vendor feeds by hand. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*
| `*_pipeline.toon` (Pipeline) | runtime — directories, output format, threads, dedup, and the `date_formats`/`timestamp_formats` lists the casts use | generated, then tuned |

The path every row takes:

```
raw bytes ──(built-in delimited reader │ plugin ingester)──►  VARCHAR staging row
          ──(one mapping rule per column → typed scalar expr)──►  typed output row
          ──(partitions[] → year / month / day / …)──►  Hive-partitioned Parquet/CSV
```

**The boundary that shapes all of this:** a mapping rule is a **scalar expression over a single
row of one table** — cast a type, rename, pick a column, compose a date. It *cannot* join, look
up, or aggregate, because Stage-1 is stateless per record — which is exactly what makes every
batch parallel and safely re-runnable. Joins, lookups, and aggregation live in **Stage-2
enrichment**: a separate `*_enrich.toon` with hand-written SQL over the committed output. See
[Architecture → Design Philosophy & Scope](../elt/architecture-elt.md).

### 1. Generation Config (`<source>_gen.toon`)

**Read by nothing since 2026-11-06** — it was the input of the retired `create-schema` CLI (the `ura` utilities);
**Add data** now infers the schema. The `csv_settings` knobs below are the same keys the engine reads from a
pipeline's `csv_settings`, so their reference stays here; `type_patterns` is read by nothing.

```yaml
csv_settings:
  delimiter: ","
  engine: auto              # auto | duckdb | java  (CSV parse engine — see below)
  skip_header_lines: 0      # blank lines before the column-name header row
  skip_junk_lines: 13       # max lines to scan past header looking for first data row
  skip_tail_lines: 2        # footer lines to discard at EOF (SQL*Plus row-count etc.)
  skip_tail_columns: 0      # trailing source metadata columns to strip
  date_formats[3]: %d-%b-%y, "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"
  timestamp_formats[3]: %d-%b-%y, "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"

type_patterns:
  dates[4]: REVERSAL_DATE, ENTRY_DATE, EVENT_DATE, POPULATION_DATE_TIME
  timestamps[4]: PRE_APPLY_TIME, PRE_EXPIRE_TIME, CUR_EXPIRE_TIME, STARTTIMEOFBILLCYCLE
```

**`skip_junk_lines`** is a *cap*, not a fixed count. CollectorProcessor uses adaptive detection — it scans forward until it finds a line with enough columns that does not echo the header. Use `-1` to scan without limit (for sources like Oracle <data_source>s with variable-length preambles).

**`engine`** (default `auto`) selects the CSV parse engine. This is a pure
performance lever — output is identical for clean files (proven by parity test).

| Value | Behaviour |
|---|---|
| `auto` | Native DuckDB `read_csv` when `skip_junk_lines`, `skip_tail_lines`, and `skip_tail_columns` are all `0`; the Java parser otherwise. Safe default — no existing source's parse semantics change. |
| `duckdb` | Always use DuckDB's vectorized, multi-threaded `read_csv`. **4–5× faster ingest** (more on wide schemas — see [`performance.md`](../../layer/platform/tests-performance.md)). Force this on messy-but-validated sources after confirming output parity. |
| `java` | Always use the original line-by-line Java parser. |

> The native reader rejects rows with *more* columns than the schema declares,
> whereas the Java parser keeps them and ignores the extras. That is precisely
> what `skip_tail_columns` handles, so `auto` routes any config using it to the
> Java path, and `ConfigValidator` warns if you force `engine: duckdb` alongside
> `skip_tail_columns > 0`. Everything else (leading banners, footers, short rows,
> blank lines) is rejected identically by both engines.

**Date/timestamp format arrays** use JToon inline syntax: `key[n]: val1, val2, ..., valN`. All values must be on a single line.

---

### 2. Schema Config (`<source>_schema.toon`)

**Machine-generated** by **Add data**; edit manually if needed. This is the source of truth for the ETL transformation.

```yaml
# Option A — legacy single-key shorthand (CSV path)
partitionKey: REVERSAL_DATE          # column used to derive year/month/day partitions

# Option B — explicit partitions[] list (plugin ingester / multi-type events)
# partitions:
#   - column: record_type            # output partition column name — must not equal a mapped column, ignoring case
#     source: EVENT_TYPE             # column in the raw DuckDB table (set by ingester)
#     type: VARCHAR
#   - column: year
#     source: REVERSAL_DATE
#     type: DATE_YEAR
#   - column: month
#     source: REVERSAL_DATE
#     type: DATE_MONTH
#   - column: day
#     source: REVERSAL_DATE
#     type: DATE_DAY

raw:
  fields[3]:
    - name: ACCOUNT_NUMBER
      selector: 0                    # zero-based column index in the source CSV
      type: VARCHAR
    - name: REVERSAL_DATE
      selector: 4
      type: DATE
    - name: AMOUNT
      selector: 12
      type: DOUBLE

mapping:
  canonicalName: reversals
  rawName: reversals
  fields[3]:                     # the Record Transformer field list — what the generators emit
    - name: ACCOUNT_NUMBER
      from: ACCOUNT_NUMBER
      fn: keep                   # pass-through; the type cast comes from raw.fields[].type
    - name: REVERSAL_DATE
      from: REVERSAL_DATE
      fn: keep
    - name: AMOUNT
      from: AMOUNT
      fn: keep
```

**Partition columns** are declared by `partitionKey:` (shorthand — derives `year`/`month`/`day` from
one column) or an explicit `partitions[]` list. The `DATE_YEAR`/`DATE_MONTH`/`DATE_DAY` types extract
the component from the `source` column, parsing its value with **`timestamp_formats` when the source
field is declared `TIMESTAMP`**, otherwise with **`date_formats`** (`VARCHAR`/`DATE` sources). Put the
matching pattern in the right list: a `TIMESTAMP` value like `2018-04-09-00.00.00.000000` will **not**
match a date-only pattern. **An unparsed value yields NULL, and every such row lands under
`year=__HIVE_DEFAULT_PARTITION__/month=__HIVE_DEFAULT_PARTITION__/day=__HIVE_DEFAULT_PARTITION__`** — DuckDB's
name for a NULL partition value. There is no `1900/01/01` sentinel for unparsed values (that was the
pre-E1 no-partition fallback, and a schema with no partition now writes flat files). So `partitionKey:` on a
text column that is not a date sends every row to that one folder; partition a text column with an
explicit `partitions[]` entry of type `VARCHAR` instead. (`VARCHAR` partition columns pass through as-is;
`DOUBLE`/`INTEGER` use `TRY_CAST`.) Pinned by
`DataTransformerCustomPartitionTest.anUnparsableDatePartitionSourceLandsUnderTheHiveDefaultPartition`.

Two more rules, both `PARTITION-KEY-VALIDATION-GAPS-1` (fixed 2026-09-23):

- **A partition `source` must be a `raw.fields[]` name.** A partition is cut from the **raw** relation,
  before mapping, so a `partitionKey` naming a column only the mapping produces (e.g. a `custom`
  `POSTING_DATE`) cannot bind. It used to validate clean and fail the run with a DuckDB binder error;
  `ConfigValidator` now reports it (startup `WARN`, and `POST /validate` with `configPath` answers
  `clean:false` with the warning). It is a report, not a refusal, because a plugin ingester may `define`
  raw columns beyond `raw.fields[]` — for any other source the binder still fails the run closed.
- **A partition `column` must not equal a mapped column, ignoring case.** DuckDB column names are
  case-insensitive, so `account_class` beside a mapped `ACCOUNT_CLASS` is a duplicate: DuckDB silently
  renamed the partition column `account_class_1`, `PARTITION_BY` bound to the mapped column, and the folders
  came out `ACCOUNT_CLASS=<mapped value>` with `account_class_1` left in the data files (measured). There is
  no way to write both, so `DataTransformer.selectFor` — the one seam the run, the test run and the derived
  schema share — **refuses** it naming both columns, and `ConfigValidator` reports it. Rename the partition
  column (the `gl_journal` demo uses `acct_class`).

The shipped `excel_example` used `partitionKey: CATEGORY` (every row under the Hive default); it now
declares `partitions[1]{column,source,type}: item_category,CATEGORY,VARCHAR`, pinned end to end by
`DemoCorpusIngestTest.excelExamplePartitionsByCategoryNotUnderTheHiveDefault`.

**A `DATE_*` partition must be cut from a field declared `DATE`, `TIMESTAMP` or `TIMESTAMPTZ`**
(`DATE-PARTITION-ON-TEXT-SHIPPED-1`, fixed 2026-09-23). `ConfigValidator` reports a `partitionKey:` or
`DATE_*` entry whose `source` `raw.fields[]` declares any other type — once per source, startup `WARN` and
`POST /validate {configPath}` → `clean:false`. It judges the **declared** type because that is all a config
says: a `VARCHAR` field can hold date text `date_formats` parses, but it is also how an identifier or a code
reached a date partition, loaded clean and put every row under the Hive default. A field that holds dates
should say so — the declared type is also what picks the format list the partition parses with. A report,
not a refusal. `ShippedDatePartitionsAreDateTypedTest` (`inspecto-etl`) runs the rule over every shipped
Space and `inspecto/examples` Pipeline and fails on any hit. Its first run found seven:

| Config | Was | Now |
|---|---|---|
| `asn1_example` (default) and `examples/02-parsing/asn1-frontend` | `partitionKey: IMSI` | `served_imsi,IMSI,VARCHAR` — the grammar carries no time field |
| `orders_by_region_feed` (demo) | `partitionKey: REGION` | `sales_region,REGION,VARCHAR` — the feed is one row per region |
| `examples/02-parsing/xlsx-frontend` | `partitionKey: CATEGORY` | `item_category,CATEGORY,VARCHAR` (as `excel_example`) |
| `msc_cdr` (demo), all three segments | `EVENT_TIME` declared `VARCHAR` | declared `TIMESTAMP` — it already partitioned correctly through `date_formats`; the folders are unchanged (`year=2026/month=08/day=01`) and the `custom` `strptime(EVENT_TIME, …)` still reads the raw text |

Pinned end to end by `DemoCorpusIngestTest.asn1ExamplePartitionsByImsiNotUnderTheHiveDefault`,
`…ordersByRegionFeedPartitionsByRegionNotUnderTheHiveDefault` (collects a snapshot through its own
`connector: dataset`) and the day folders now asserted in `…mscCdrSplitsFiveFourThreeByChoiceAlternative`.
⚠ `served_imsi` is one folder per subscriber — right for a three-record example, the wrong partition for
a production CDR store.

The generators write one **`keep`** field per raw column (⛔ decided 2026-09-10, `MAPPING-GEN-1`; they wrote the
legacy `rules[]` until then, a shape **0 of 24** committed schemas used). Every field carries an **`fn`** marker — that
marker, not the key name, is what `RecordTransform.isFieldList` keys on, so a row without one is not a field list.
The **23**<!--count:sql-mapping-functions--> verbs are the served catalog (`GET /components/transform/describe`,
`sql-functions.contract.json`): `keep` · `text.*` (trim, upper, lower, replace, substring, split_part, join, pad_left)
· `num.*` (round, multiply, divide, abs) · `date.*` (parse, format, part, truncate, `concat_parts`, `from_filename`)
· `logic.*` (default_if_empty, if_then_else) · `convert.type` · `custom` (any per-row DuckDB scalar expression, emitted
verbatim). Arguments ride an `args` map; the editor's Fields | SQL views and the mapping grid author exactly this shape.

**`selector`** is the zero-based column index in the raw source CSV — decoupled from position in the output schema, so source column order changes do not break the pipeline.

#### Legacy spelling: `mapping.rules[]` + `transformType` (READ ONLY)

Schemas written before 2026-09-05 may still carry `rules[{targetColumn, sourceExpression, transformType}]`. The
engine keeps reading them through **`RecordTransform.fromMappingRules`** — one branch, deliberately retained — so
nothing needs migrating (the committed corpus is already all `fields[]`). ⛔ **No generator writes this shape any
more**: not `POST /config/suggest/schema`, and not the editor's Parse pane (which wrote it until
2026-09-10). The mapping below is kept only so an old file can
be read, and so its `transformType` values are understood when met:

**`transformType`** controls how the source expression is evaluated. It is **optional and
case-insensitive — blank or omitted means `DIRECT`**. An unrecognised *non-blank* value (a typo
like `EXPER`) is rejected at transform time rather than silently treated as `DIRECT`. Recognised
values, with the `fields[]` verb that replaces each:

| Value | `sourceExpression` format | Description |
|---|---|---|
| `DIRECT` *(default — leave blank/omit)* → **`keep`** | column name | Pass-through with a type cast (DATE/TIMESTAMP/DOUBLE/VARCHAR) driven by the field's declared type in `raw.fields[]`. ⚠ Those four are the **only** coerced types (`TransformCompiler.direct`): any other declared type — notably `INTEGER` — falls through uncast and lands as VARCHAR. For a true integer column declare the field `VARCHAR` and add an `EXPR` rule, `TRY_CAST(COL AS INTEGER)`. |
| `EXPR` → **`custom`** | any DuckDB **scalar** expression | Emitted **verbatim**. Unqualified column names resolve against the source row, so the full DuckDB scalar-function library is available — e.g. `UPPER(TRIM(MSISDN))`, `TRY_CAST(AMT AS DOUBLE) / 100.0`, `CASE WHEN ERRORCODE='0' THEN 'OK' ELSE 'FAIL' END`. You own validity and any explicit cast. **Per-row scalar only** — no aggregates or joins (those are Stage-2). |
| `CONCAT_DT` → **`date.concat_parts`** | `DATE_COL\|TIME_COL` | Concatenate two raw columns into a single TIMESTAMP: `COALESCE(TRY_STRPTIME(date \|\| ' ' \|\| time, ...))` |
| `FILENAME_DATE` → **`date.from_filename`** | `COL\|PREFIX` or `COL\|PREFIX\|FORMAT` | Extract an 8-digit date from a filename-style column using a fixed prefix. The default format is `%Y%m%d`. **Restricted to `EVENT_DATE` only** — an `IllegalArgumentException` is thrown at startup if used on any other target column. |

**`FILENAME_DATE` example** — <data_source> CDR files carry the event date in the filename (`cbs_cdr_vou_20180409_601_101_057726.add`) rather than a data column:
```yaml
mapping:
  rules[1]{targetColumn,sourceExpression,transformType}:
    EVENT_DATE,FILENAME|cbs_cdr_vou_,FILENAME_DATE
```
The generated SQL is: `TRY_STRPTIME(regexp_extract(raw_input."FILENAME", 'cbs_cdr_vou_([0-9]{8})', 1), '%Y%m%d')::DATE`

**`EXPR` example** — derive columns with arbitrary DuckDB scalar functions, referencing raw columns by name (tabular form; quote the cell because it contains commas/parens):
```yaml
mapping:
  rules[3]{targetColumn,sourceExpression,transformType}:
    ACCOUNT_KEY,  "UPPER(TRIM(ACCOUNT_NUMBER))",                       EXPR
    AMOUNT_MAJOR, "TRY_CAST(RECHARGE_AMT AS DOUBLE) / 100.0",          EXPR
    RESULT,       "CASE WHEN ERRORCODE='0' THEN 'OK' ELSE 'FAIL' END", EXPR
```
The expression is emitted verbatim into the `SELECT`, so it must be a valid **per-row scalar**
expression (the schema is operator-authored and trusted — it is not sandbox-validated, and an
invalid expression fails the batch at `CREATE TABLE … AS SELECT`).

> Each rule compiles to one column expression (`etl/TransformCompiler`); `DataTransformer`
> assembles them — plus the derived partition columns — into the single `CREATE TABLE … AS
> SELECT …`. The type cast a `DIRECT` rule applies comes from the field's declared type in
> `raw.fields[]` and the pipeline's `date_formats`/`timestamp_formats` (see
> [Type Mapping Reference](admin-guide-configuration-reference-collector-types.md#type-mapping-reference)).

#### Extending the transform — three levels

Most onboarding needs **only config** (level 1) — and with `EXPR` that already includes the full
DuckDB scalar-function library. Levels 2 and 3 are deliberate code seams in the engine (not a
config SPI), reached only when config genuinely can't express the need:

| Level | Reach for it when | Where / how |
|---|---|---|
| **1 — Record Transformer field** *(config, no code)* | rename/select (`keep`), cast a type (`convert.type`), compose a timestamp (`date.concat_parts`), derive a date from the filename (`date.from_filename`), **or any per-row DuckDB scalar expression (`custom`)** — a legacy `mapping.rules[]` row is read as one of these | a row in `mapping.fields[]` |
| **2 — New named `transformType`** *(engine code)* | you want a **reusable, named** verb across many schemas (e.g. a domain checksum) rather than repeating the same `EXPR` everywhere | add the verb to the **`RecordTransform`** catalog (`platform/inspecto-etl/src/main/java/com/gamma/etl/RecordTransform.java`) — a one-line addition returning a DuckDB **scalar** expression (one row in → one row out). 🔴 It must not drift from `inspecto-ui/src/app/modules/admin/pipelines/sql-functions.ts`; `RecordTransformContractTest` pins the pair by writing `sql-functions.contract.json` from the catalog. |
| **3 — Plugin ingester** *(engine code)* | the **input format** isn't delimited text — binary, fixed-width, ASN.1 — or one file splits into several event-type tables | implement [`StreamingFileIngester`](../elt/spec-ingest-plugin-ingester.md#plugin-ingester): you parse and `emit` records; the framework still applies the same `mapping.rules[]` / `partitions[]` to them |

Anything that needs **more than one row** — a join to a reference table, a `GROUP BY`, a running
total — is not a transform and does not belong in any of these levels. It is **Stage-2
enrichment** (`*_enrich.toon`, hand-written SQL over the partitioned output).

---

### 3. Pipeline Config (`<source>_pipeline.toon`)

**Machine-generated** by the UI's create scaffold (Add data); edit to adjust runtime
settings. One file configures everything for a source: the `dirs`, `output`, `parsing` and `processing`
sections drive the ETL runtime. The top-level `search`, `copy_tars` and `backup` sections of the retired
`ura` pre-ETL CLI (2026-11-06) are read by nothing.

**The block-by-block reference lives in one place:**
[Pipeline config keys — the single block census](spec-pipeline-config-keys.md).
That page owns, for every top-level block (`collector:`, `parsing:`, `processing.*`, `output:`/`sinks:`,
`output_store:`, `route:`, `steps:`, `trigger:`, `produces`/`reference`/`stream`, `template`, …):
who **declares** it (`ConfigSpecs.pipeline()` vs parser-only), who **reads** it (the engine class),
which surface **authors** it, and the declared-vs-parser-only coverage census with its ratchet
(`PipelineKeyCoverageContractTest`). This section deliberately no longer duplicates that material —
including the multi-schema `schemas[]` dispatch, the `dirs.*` semantics and the `output.format`/
`compression` tables, whose details now live with the census page and the engine concepts it links.

> **JToon note:** The `.toon` format does not support `#` comment lines. Parsing stops at the first unrecognised character, so do not add inline or standalone comments.
> 🔴 **This is not always an error.** A comment between two top-level sections makes `JToon.decode` **truncate the file there**, and `PipelineConfig.load` accepts the truncated map with no error and no warning — every key after the comment is silently gone (verified 2026-08-18: a comment block in `spaces/demo/config/orders/orders_pipeline.toon` dropped `output_store:`, `steps:` and `collector:`). Decode an edited config and compare its top-level key list; a successful load is not evidence.

---

### Activation (`active:`) and config caching

**`active`** is a top-level boolean that gates whether the pipeline is **executed**:

| `active` | Behaviour |
|---|---|
| `true` | The pipeline runs on every poll cycle (and via the `MultiCollectorProcessor` CLI). |
| `false` *(default — key absent ⇒ `false`)* | The pipeline is still parsed, indexed and visible in `GET /pipelines`, but **never executed**. |

The default is **off** so a freshly-dropped or half-edited config never runs until you explicitly arm it
with `active: true`. Toggling it requires no restart — the change is picked up on the next cycle (see below).
An operator who explicitly triggers a single pipeline (`POST /pipelines/{name}/trigger`) runs it regardless of
`active` — the gate only governs the *automatic* poll cycle and the multi-source CLI.

**Config caching / hot-reload.** Pipeline configs and the schema / grammar / segment files they reference are
**parsed once and cached**, not re-read every cycle. Each poll cycle re-checks the modification time of every
config file; a pipeline is re-parsed only when its `*_pipeline.toon` *or* one of its referenced files actually
changes on disk (so edits — including flipping `active` — are picked up automatically), and an unchanged
pipeline incurs no disk I/O and no schema-reload logging. Each run still gets its own run-timestamped
status/batch/lineage CSVs; only the parse is cached.

---

## Changes

- 2026-09-08: the "new named `transformType`" cell used to name a `ColumnRule` in a `DATA_RULES` registry in `etl/TransformCompiler`. `DATA_RULES` does not exist anywhere in the tree — the Record Transformer replaced that lane.
