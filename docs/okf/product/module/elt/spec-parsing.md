---
type: Concept
title: Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles
description: Three parse mechanisms over one DuckDB backend, the read_csv reference, the other DuckDB-native readers, the parsing: block and per-format profiles, the decision matrix, the ParserPlugin framework, XML and ASN.1 ingest, drop-in parser jars, Decode Profiles, deferred items and BER hostile-input handling.
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java, platform/inspecto-engine/src/main/java/com/gamma/consignment, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java, platform/inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java, platform/inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
---

# Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. [spec-ingest.md](spec-ingest.md) · [spec-consignments.md](spec-consignments.md) · **spec-parsing.md**

**Contents**

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
> [spec-ingest.md](spec-ingest.md). Authoring Grammars in the UI is
> [grammar-config](../config/user-guide-grammar-config.md) and the Parse pane is
> [the ELT user guide](user-guide-elt.md#61-a-parse-step-is-three-things-and-the-drawer-is-its-one-home-decision-2026-09-06) §6.1;
> the requirement of record is ING in [the ELT plan](plan-elt.md#21-ingestion) §2.1. (The plan's old
> §3.3 *one `parsing:` block* is §P.5 here; its old §3.9 *Parse pane* is that user-guide §6.1.)

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
3. **The plugin frontend** (`StreamingFileIngester`, [§ Plugin ingester](spec-ingest.md#plugin-ingester) above)
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
| `quote`, `escape`, `comment` | `quote`/`escape`/`comment` (single char, validated fail-closed at load; both engines). `quote: ""` is **no quote** (DuckDB's `quote=''`, NUL on the Java path); only an absent `quote` takes the default `"` (BE-3 slice 2, 2026-11-19) | `[LIVE]` (5.2) |
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

**One sniff in the product (`BASIC-ELT-PROFILE-1` BE-3 slice 1, 2026-11-19).** The engine's sample profiler,
`com.gamma.parse.SampleProfiler.dialect(conn, file, encoding)` (`inspecto-engine`), runs `sniff_csv` with no authored
options and returns DuckDB's answer under the grammar option names (`delimiter`, `quote`, `escape`, `comment`,
`has_header`, `skip_header_lines`, `date_format`, `timestamp_format`), unshaped: DuckDB's `(empty)` ("none found") is
passed through, and an unsettled dialect (mixed line endings) throws. Both readers call it and keep their own shaping:
the parse preview (`ComponentPreview.parsing`, the `resolved` line) shows the map and shows nothing when the sniff
fails; Add data (`OnboardDraft`) passes its chosen encoding, falls back to its own delimiter guess (with `"`) when the
sniff throws, and adds its evidence and confidence. `DialectSniffGoldenTest` pins both readers' answers.

**Add data follows the sniff (BE-3 slice 2; operator ruling 2026-11-19, "follow the sniff").** Slice 1 left three
differences; they are now one rule — Add data takes the sniff's answer as it is: a quote not found (`(empty)`) is **no
quote** (it was `"`), so a stray quote such as `1,"x` is text in its value and the file imports (it was a 422); and the
lines the sniff skips before the header are skipped (`format.skipLines`, present only when the sniff skips lines), so
the real header is read (the first line was). The person's own `?delimiter=`, `?quote=` and `?header=` still win over
the sniff. The Pipeline Add data writes reads the file the same way: `csv_settings.quote` is written as the draft read
it, `""` included (`quote: ""` = no quote, see the key table above), and `skip_header_lines` when the draft skipped
lines — `ControlApiOnboardDialectTest` imports each case. Consequence to know: a later file of the same import is read
with the first file's quote, so a no-quote import reads a later file's quoted fields as text (a quoted delimiter then
splits the field and the line no longer fits — inferred from the reader, not tested); the quote entry of the fix catalogue offers `"` when the draft's lines do not split alike. ⛔ The
grammar editor cannot author "no quote" (clearing the field removes the key, which means `"`).

**One type inference (BE-3 slice 2, 2026-11-19).** `SampleProfiler.types(conn, relation, limit)` is the one entry for
column types. Per column it answers two ways: `duckdb`, the type DuckDB's own reader gives the column (`DESCRIBE`:
the auto-detect sniff for the parse preview's `read_csv(…, auto_detect=true)`, the declared type for Parquet, `VARCHAR`
for an all-text read), and the **vote** (`type`, `format`) over the first `limit` values read as text — the TRY_CAST
vote that was `SchemaSuggest`'s (`BIGINT` → `DOUBLE` → `TIMESTAMP`/`DATE` → `BOOLEAN`, then `SampleProfiler.DATE_FORMATS`
with ambiguity refused). Each caller keeps its own answer: the parse preview's `columnTypes` give `duckdb`; schema
suggest (`SchemaSuggest.infer`, over the preview's parsed rows) gives the vote; Add data (`OnboardDraft.typed`) gives
the vote — or `duckdb` for a file that declares its types — and then its own layers: the decimal mark (C1), a later
fraction (DECIMAL-TEXT-NOT-BIGINT-1), almost-all numbers or dates (finding 3), the timestamp format and the person's
stated types. `TypeInferenceGoldenTest` pins the three answers; where they differ on a TYPE (not only on whether a
format is reported) a `disagreement…` test keeps each one, for the operator to decide: dates written in words (text
in the preview, `DATE` with `%B %d, %Y` in the other two), times of day (`TIME` in the preview, text in the other two),
numbers with a decimal comma and columns that are almost all numbers (numbers only in Add data), and the full-cycle
transfers fixture (an amount and a booking time that are text in the preview and suggest, a number and a date with a
time in Add data).

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
    segments:                # multi-event-type output (see spec-parsing.md)
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
  slices; see [§6 *Fixed-length binary records*](spec-ingest.md#fixed-length-binary-records-fixedwidthrecordingester) above.

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
([§ Execution modes](spec-ingest.md#execution-modes--the-framework-picks-by-file-size) above) keeps TB-scale
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
model, unchanged — see [Part I](spec-parsing.md) above). A file type is parsed by providing a **Grammar**
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
[ELT user guide](user-guide-elt.md); the engine-relevant contract points are:

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
