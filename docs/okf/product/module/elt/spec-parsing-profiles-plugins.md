---
type: Concept
title: Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles — Format profiles, decision matrix and parser plugins
description: Part 2 of 3 of Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles: Per-format profiles (copy-paste starting points); Decision matrix — pick a frontend; Roadmap status and corrections to the PDF; Parser plugins — the self-describing Parser framework
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
---

# Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles — Format profiles, decision matrix and parser plugins

Part 2 of 3 of [Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles](spec-parsing.md) · previous: [The parser model, DuckDB readers and the parsing block](spec-parsing-model-block.md) · next: [XML, ASN.1, drop-in jars, Decode Profiles and BER](spec-parsing-hierarchical-decode.md).

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
  authored (2026-09-25; §P.12, [spec-parsing-decode-profile.md](spec-parsing-decode-profile.md)).

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
