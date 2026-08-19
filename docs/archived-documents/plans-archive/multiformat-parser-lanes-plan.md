# Plan — Multi-format parser lanes: Excel (new), JSON/NDJSON + Fixed-length (enriched), tabbed UI for all three

**Status:** COMPLETE + ARCHIVED 2026-08-20 — X1-X4, J1, F1, D1 all shipped (X gates 3489→3493/0/0/5 + UI 2659/5 exit 0 + AOT). As-built: okf/frontend/features/grammar-config.md (the multiformat section) + okf/backend/config/parsing-options-reference.md §6.4/§6.4b. Nothing open. **Parent:** operator request 2026-08-19
("a parser for Excel like delimited on the DuckDB excel extension; similar for fixed length and
JSON/NDJSON; fixed length with single-column reader + vectorized slicing; robust grammars + an
elegant, intuitive UI for all options"). **Concept homes on ship:**
`okf/frontend/features/grammar-config.md` · `okf/backend/config/parsing-options-reference.md` ·
a new `okf/backend/engine/parser-frontends.md` if the builder grows enough to deserve one.

> **Vocabulary (binding):** these author **Grammars** (⛔ never "parser config"). The engine that
> applies one is a *Parser*. Frontend ids are engine words (`xlsx`, `fixedwidth`, `json`).

## 0. Grounded state (2026-08-19 — all probed, not assumed)

- **The architecture the operator wants already exists as a seam.** `DuckDbCsvIngester.buildReadSpec`
  (`inspecto-etl:195`) is the per-format DuckDB query builder — delimited `read_csv`, fixedwidth,
  JSON, text_regex each have a branch — and everything downstream (mapping rules, `DataTransformer`,
  `PartitionWriter` → parquet, lineage, audit) is format-agnostic and shared. Adding a format = a
  config record + a `build*ReadSpec` branch + registry/node-type touchpoints + a UI spec set.
- **Fixed length ALREADY IS "single-column reader + vectorized slicing"** (`:266-303`): `read_csv`
  with `delim='', quote='', escape=''` reads each line as one VARCHAR `line` column; the projection
  carves fields with 1-based `substring` (DuckDB vectorizes the slice), `min_record_length` drops
  banners/footers. Nothing to build engine-side; the ask resolves to grammar/UI enrichment.
- **JSON/NDJSON already ships two modes** (`buildJsonReadSpec:318`): `format: newline` = the same
  single-column line reader + `json_valid` routing + `json_extract_string` per key (malformed lines
  route away, never fail the batch); `format: array|auto` = `read_json` with an explicit all-VARCHAR
  columns map. Engine config today is only `Json(format, recordsPath)` — enrichment is additive keys.
- **Excel: no support exists** (`regulatory_xlsx` is a UI mock seed only). **Probed on the shipped
  runtime (duckdb_jdbc 1.5.2.1):** the `excel` extension is **NOT statically linked** (json/icu are);
  offline `LOAD excel` fails NOT_INSTALLED; with network `INSTALL excel` succeeds and caches in
  `~/.duckdb/extensions/v1.5.2/windows_amd64/`, after which LOAD works offline. `read_xlsx` named
  params, verbatim from `duckdb_functions()`:
  `header, all_varchar, stop_at_empty, ignore_errors, range, sheet, empty_as_varchar, normalize_names`.
  ⛔ **There is no `columns` parameter on `read_xlsx`** — the operator's mention of it is REFUSED as
  an option (the columns table + all-VARCHAR projection is our columns mechanism anyway, same as
  every other frontend).
- **Binary samples already have a transport:** `POST /parsers/{id}/preview` accepts `sample_b64`
  (byte-capped) beside `sample_text` (`ParserRoutes:72-80`) — built for ASN.1, reusable for xlsx.
- **Registration touchpoints for a new built-in** (from P3d slice C, verified): engine —
  `BuiltinParsers` (served spec), `BuiltinNodeType`, `PipelineEditable.{SUBTYPE_FRONTENDS, USE_HOME,
  LOWERABLE, isParserType}`, `PipelineConfigParser`/`ParserSpec`; the two byte-compared contract
  fixtures regenerate (`-Dnode.attributes.write=true` / `-Dstep.types.write=true`); UI —
  `PARSE_NODE_FRONTENDS`, `parsingAttributesFor`, `normalizeFrontend`/`clearMissingRoots`,
  `FRONTEND_ALIASES`/`grammarSeedsFrontend`, mock mirrors (`pipeline-editable.ts`, parsers handler).

## 1. Decisions (made now; flag ⚠ if the operator objects)

| # | Decision |
|---|---|
| P1 | **Extension provisioning is layered, fail-closed:** the engine's `read_xlsx` path calls an `ExcelExtension.ensureLoaded(conn)` helper — (1) `LOAD excel` (cached/bundled install wins); (2) else `LOAD '<dir>/excel.duckdb_extension'` when `-Dduckdb.extension.dir` is set (the air-gap deployment: the file ships beside the jar); (3) else `INSTALL excel; LOAD excel` (networked deployments); (4) else fail the batch with a message naming all three remedies. Never silent, never a partial parse. Operations doc gains a *DuckDB extensions* note. ⛔ The extension binary is NOT committed to the repo. |
| P2 | **Honest options only.** Each frontend's grammar exposes exactly the keys its builder applies — the probed `read_xlsx` set for xlsx; for JSON additive `maximum_object_size` + array-mode `ignore_errors`; fixed length exposes what the engine already reads (`record` layout/slices via the slice table, `trim`, `min_record_length`, `binary` stays P3b-refused in the drawer). No speculative knobs. |
| P3 | **xlsx projection is by selector like JSON** — selectors are the sheet's column NAMES as `read_xlsx` yields them (header row when `header=true`, else DuckDB's positional names — probed in X1); `all_varchar=true` is stamped by the ingest path (types are the mapping's concern, same rule as every frontend). Preview WITHOUT all_varchar supplies the B2 `columnTypes` inference. |
| P4 | **Sample panel stays text-first; xlsx captures as bytes.** The drawer's sample thread gains a binary arm (capture → base64, no text rendering; the parsed TABLE is the preview) — the ASN.1 precedent. |
| P5 | **Every enriched format gets the delimited-style tabbed surface** via `AttributeSpec.tab` (the shell already generalizes): xlsx = *Sheet & range · Types & columns · Robustness · Files & metadata*; json = *Format & records · Types & columns · Robustness · Files & metadata*; fixedwidth = *Record layout · Types & columns · Robustness · Files & metadata* (slice table stays projected). The R9 hidden-panels rule applies (already in the shell). |

## 2. Slices (each gated: reactor `-Pedition-enterprise` + UI suite/lint/tsc + preview)

> **Progress 2026-08-19:** X1 ✅ (7 tests, incl. the header-false positional-letters probe and the
> range/normalize_names paths) · X2 ✅ (`parsingXlsx` byte preview + B2 sniff; pasted-text refused
> toward `sample_b64`) · X3 ✅ (`PARSER_XLSX` + LOWERABLE/SUBTYPE_FRONTENDS/USE_HOME + lift/lower
> round-trip pins; ⚠ the step-types/node-attributes contract fixtures are legitimately UNCHANGED —
> that contract is the RECIPE-VERB palette, which has always carried only the bare `parser`; the
> per-format graph palette is UI-side and lands with X4) · X4 ✅ (2026-08-20: palette `parser.xlsx` +
> drawer routing + the tabbed spec set with `grammarTabsFor` first-tab labels and the files tab
> ANCHORED specless; binary sample capture → `sample_b64` through service/editor/pane; Grammar CSV
> `engineKeyOf` generalized to bare names with back-compat for raw-spec-key files; mock mirrors
> updated + the honest mock refusal, the ASN.1 precedent; UI suite 2657/5 exit 0, AOT 0, verified
> live offline) · J1 ✅ (2026-08-20: `Json` gains `maximum_object_size` +
> `ignore_errors`, array/auto only — refused at load on NDJSON; ⚠ probed: ignore_errors keeps a
> malformed record as an ALL-NULL row, not skipped; ⚠ maximum_object_size is CLAMPED up to the
> reader's buffer and unobservable at fixture scale — pinned at assembly level (emitted, spelled,
> accepted), a memory ceiling for huge docs; both knobs also ride `read_json_objects`; UI set
> tabbed *Format & records* with the knobs format-gated) · F1 ✅ (2026-08-20: fixedwidth set
> tabbed *Record layout*, the slice table homed into tab 1 via ONE `ng-template` mounted in either
> shell; date/timestamp format lists joined the json/fixedwidth Types tabs — transform-time keys
> every frontend reads) · D1's engine docs written; remaining: none — the plan closes when the
> J1/F1 gates land.

- **X1 — xlsx engine lane**: `PipelineConfig.Xlsx` record (8 probed keys) + `PipelineConfigParser`
  root `xlsx:` + `buildXlsxReadSpec` (all-VARCHAR projection by selector, `filterWhere` composes) +
  `ExcelExtension.ensureLoaded` (P1) + ingest→parquet test on a real generated `.xlsx` fixture
  (write via DuckDB `write_xlsx` in the test — no POI, no committed binary), test **skips with an
  assumption** when the extension can't load (offline CI box).
- **X2 — xlsx preview/served spec**: `BuiltinParsers` gains `xlsx` (grammarSchema = the 8 keys;
  `ingestable`), preview accepts `sample_b64` → temp file → same `read_xlsx` read (parity rule) →
  table + `columnTypes` (B2 sniff = read WITHOUT `all_varchar`).
- **X3 — node type end-to-end**: `PARSER_XLSX` (`parser.xlsx`) + the six lift/lower touchpoints +
  both contract fixtures regenerated + mock mirrors.
- **X4 — xlsx UI**: `PARSE_NODE_FRONTENDS['parser.xlsx']`, `parsingAttributesFor('xlsx')` tabbed
  (P5), palette entry, binary sample capture (P4), Grammar CSV round-trip (options only — columns
  ride as usual), `FRONTEND_ALIASES` (`excel` answers too), seedable-template gate.
- **J1 — JSON grammar enrichment**: engine additive keys (`maximum_object_size`, array-mode
  `ignore_errors`) honestly applied in `buildJsonReadSpec`; UI spec set tabbed (P5) incl. the
  existing `format`/`records` keys; docs row in `parsing-options-reference.md`.
- **F1 — fixed-length UI enrichment**: tabbed spec set (P5) exposing `trim` + `min_record_length`
  (+ whatever the record already reads — grounded at slice time), slice table projected into
  *Record layout*; no engine change expected.
- **D1 — docs**: `parsing-options-reference.md` gains the xlsx table + the enriched json/fixedwidth
  rows; operations-reference gains the extension-provisioning note; OKF grammar-config as-built.

## 3. Risks / refusals banked

- `read_xlsx` has **no `columns` param** (probed) — refused, not faked (P2).
- The offline reactor CI cannot exercise `read_xlsx` unless the box has the cached extension —
  X1's test is assumption-gated, and the parity preview test rides the same gate. 🔴 Never let the
  gate silently skip on a box that COULD load it: the assumption message names the cache dir.
- `range`/`sheet` are strings interpolated into SQL — escape via the existing `escapeSql`, and
  validate shape (`sheet` non-quote, `range` `^[A-Za-z]+[0-9]+(:[A-Za-z]+[0-9]+)?$`) fail-closed in
  `ConfigSafetyValidator`-adjacent parsing, since a grammar is operator config, not trusted input.
