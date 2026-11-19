---
type: Concept
title: Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles — The parser model, DuckDB readers and the parsing block
description: Part 1 of 3 of Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles: The unifying model: a parser *frontend* feeding the DuckDB *backend*; What the engine exposes today vs. raw DuckDB; Corrected & extended DuckDB read_csv parameter reference; Beyond CSV — the other DuckDB-native readers; The parsing: block [LIVE]
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
---

# Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles — The parser model, DuckDB readers and the parsing block

Part 1 of 3 of [Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles](spec-parsing.md) · next: [Format profiles, decision matrix and parser plugins](spec-parsing-profiles-plugins.md).

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
| `decimal_separator` (`.` or `,`; any other value refused at load) | **not** a `read_csv` param: honoured at **transform** time — a number field (`SchemaFieldTypes.isNumber`) is read with `DecimalMarkText.sql` (spaces and the other mark as digit grouping removed, a `,` mark read as the point), so `1.234,50` under `,` is `1234.5`. Absent = the plain `TRY_CAST` every Pipeline had; `.` is not the same as absent (it also drops `,` grouping). The parse preview and schema suggest read the same key (§P.3.6) | `[LIVE]` (`BASIC-ELT-PROFILE-1` U3, 2026-11-19) |

Everything else in the PDF — `sample_size`, `auto_type_candidates`, `names`,
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
`dateformat`, `timestampformat`, `decimal_separator`. ⚠ DuckDB's `decimal_separator` acts only on a column `read_csv`
itself types; the engine reads every raw column as `VARCHAR`, so the Grammar's `decimal_separator` key is applied in the
typing step instead (§P.2 table, U3 2026-11-19).

### P.3.5 Multi-file
`filename` (inject source path), `hive_partitioning` (dir → columns), `union_by_name` (align by
header across files). Note: this engine already does its own batching + Hive output, so these are
mainly for ad-hoc analyst SQL, not the ingest path.

### P.3.6 Diagnostics
`FROM sniff_csv('file')` returns the dialect DuckDB *would* pick — invaluable when authoring a new
profile. Keep the PDF's Recipe C.

**The sample profiler** — one dialect sniff and one type answer for Add data, the parse preview and schema suggest (`com.gamma.parse.SampleProfiler`, `BASIC-ELT-PROFILE-1` BE-3, operator rulings 2026-11-19) — has its own page: **[spec-parsing-sample-profiler.md](spec-parsing-sample-profiler.md)** (the sniff and how Add data follows it, the type vote and its rules, the decimal mark, what the rulings changed).

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
    decimal_separator: ","   # . | , - the mark number columns are written with; blank = a plain cast (U3)
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
