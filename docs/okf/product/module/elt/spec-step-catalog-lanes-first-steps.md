---
type: Concept
title: Step catalog — every Step kind, delivered and pending — The two lanes, and collect to shift
description: Part 1 of 5 of Step catalog — every Step kind, delivered and pending: The two lanes (read this before authoring a chain); collect → acquisition — the Collector; parse → the 7 parser.* frontends; transform → transform.filter — Row filter; lookup → transform.lookup — Lookup & static map transcoder; explode → transform.explode — Array exploder; unpivot → transform.matrix.unpivot — Unpivot / …
tags: [pipeline-graph, steps, catalog, processors, palette, examples, roadmap]
timestamp: 2026-09-06T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Step catalog — every Step kind, delivered and pending — The two lanes, and collect to shift

Part 1 of 5 of [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) · next: [Forecast to outlier — analytics Steps](spec-step-catalog-analytics-steps.md).

## The two lanes (read this before authoring a chain)

| Lane | Steps that execute there | How it runs | Example runner |
|---|---|---|---|
| **Ingest** | `collect`, every `parse` frontend, `route`, `sink` | The poll cycle: files land as Consignments, parse + map + sink in one pass. `route:` forks the write into one destination per branch. | `run-example …` (one shot) |
| **At rest** | `filter`, `dedup`, `join`, `summarize`, `sql` — the `steps[n]:` chain | Stage-1 lands the store; a `type: pipeline` Job (`pipeline_config: <the same file>`, `on_pipeline: <id>`) lifts the chain (`PipelineLift.stageTwo`) and runs it over the landed store on every commit, writing the top-level `output_store:`. A chain with no `output_store:` refuses to lift; an active pipeline whose chain no Job runs is an orphan finding. | `serve-example … --demo` |

Two grammars spell the same chain: the **flat file** (`steps[n]:` list of single-key maps, or the
legacy singular `processing.dedup:` / `processing.join:` / `processing.summarize:` / `csv_settings.where` /
`route:` blocks — mutually exclusive with `steps[]`, projected in the order `filter → join → dedup →
summarize → route`) and the **recipe** (`RecipeCompiler`), which compiles to the flat form. Route
branches may chain their own `steps[]`, limited to `filter` (with `where:`), `dedup` (consignment scope)
and `summarize` — `RouteArming.BRANCH_STEP_KINDS`; a `join` or a nested `route` refuses at save and at
registration. Mechanism detail: [execution-lanes](spec-pipeline-model.md), [live-execution](spec-pipeline-model.md),
[editable-round-trip](spec-pipeline-model.md).

**Every delivered Step kind has a runnable example** in two places: standalone under
[`inspecto/examples/`](../../../../../inspecto/examples/README.md) (`07-steps/` + `02-parsing/`) and, wired
together in one space, `spaces/default/config/<kind>_step/` (+ `asn1_example`, `xml_example`, and the
format-example pack), seeded by `spaces/default/data/samples/seed-inbox.{sh,ps1}`.

---

# Part A — the delivered Step kinds

Configuration tables are the served `AttributeSpec`s (key · type · tier · default · help), the same
contract the editor renders. **Flat-file spelling:** a key shown as `a.b` is nested (`a: { b: … }`) in
the `*_pipeline.toon`; a `list` is `key[n]: v1, v2`. Tier is the editor's disclosure bucket
(required · optional · advanced), not validation.

## `collect` → `acquisition` — the Collector

**Function.** The pipeline entry: discovers the files (or the Dataset snapshots) one run will take,
lands them in the inbox, and decides what counts as *new*. Exactly one per pipeline (`collector()` is
singular by construction).

**Behaviour.** Each acquire cycle lists the source through the Connection's connector (`local` inbox,
SFTP/FTPS, JDBC export, Azure Blob, Kafka, or `dataset` to consume another pipeline's parquet
snapshots), applies the include/exclude patterns, the stability window, the file-grain duplicate
policy (`path` marker · `metadata` · `checksum` · `etag`) and the post-success action, then packs the
admitted files into Consignments (`consignment.max_files` / `max_bytes`, ordered by arrival `mtime` or
`name`). Compressed inputs are unpacked here with fail-closed caps. Gap detection over a numbered series
(`gap_detection.sequence: ORDERS_{yyyyMMdd}`) raises `SEQUENCE_GAP`. The trigger keys give the pipeline
its own cadence or make it fire on a Dataset write.

**Flat-file home.** The `collector:` block plus `dirs.poll`, `processing.file_pattern`,
`processing.duplicate_check` / `marker_extension` / `retention_days`, `processing.unpack`, and the
top-level `trigger:`. The UI flattens the nested keys with `__`, shown here as dots.

**Refusals.** `connector: dataset` without `dataset:` (and the reverse) refuse at load. A Connection
that is not `local` needs a saved Connection profile.

**Configuration — the `collector:` block** (2026-09-23, `PROCESSOR-RELEASE-READINESS-1` G5). Unlike the
other Part A tables this one is the **parser's** key set (`PipelineConfigParser.parseCollector` + the
consignment caps), not only the served `AttributeSpec`s, because the canvas form is a curated subset.
*Form* names the acquisition-node attribute (`__` = nesting; `NodeAttributes.COLLECTOR`); *spec* marks a
leaf `ConfigSpecs.pipeline()` declares, so `POST /config/write` and the graph save type-check it. Every
sub-block is additive: absent means the default in the table. *Remote* = read only on the remote
acquisition path (`CollectorProcessor.acquire`); a `local` inbox never reaches it, and since 2026-09-24 a save
warns when one is set on a local inbox (`WARN_COLLECTOR_KEY_INERT`, [config safety](../config/spec-config-safety-roots-save-gate.md#collector-checks--throttle-breaker-archive-2026-09-24)).

| Key | Type · default | Form | What it does |
|---|---|---|---|
| `connector` | string · `local` | — (derived) | `local`, `dataset`, or the named Connection's connector. The form derives it at save and never asks. |
| `connection` | string | `connection` (writes `use: connection/<name>`) | The saved Connection profile — host, credentials and the connector's own options (below). |
| `dataset` | string | `dataset` | Dataset entry: the Dataset whose snapshots each cycle copies in. Only with `connector: dataset`. Spell it bare (`orders_by_region`) or as the trigger's `datasets/<id>` ref: `PipelineConfigParser` strips a leading `datasets/` at load, so the connector always receives the bare id. ⚠ Any other `/` reaches `ComponentStore.get`, which refuses it as an unsafe id. The shipped `orders_by_region_feed` uses the prefixed form and resolves (`DEMO-DATASET-FEED-UNSAFE-ID-1` refuted, pinned by `DatasetCollectorConnectorFactoryTest`). |
| `id` | string · the pipeline name | — | The Collector's id. Circuit-breaker state is keyed by it. |
| `include` | list · `processing.file_pattern` | `include` | Discovery patterns (`glob:` or `regex:` over the relative path). |
| `exclude` | list | `exclude` | Patterns removed from discovery. |
| `recursive_depth` | number · `-1` (unbounded) | `recursive_depth` | How deep discovery walks. |
| `discovery` | `poll` · `watch` · `poll` | `discovery` | `watch` = filesystem events on a local poll root. |
| `stability.window` | duration · `30s` | `stability__window` | Hold a file until it has been quiet this long. The block's presence turns the gate on; absent = off. |
| `stability.size_checks` | number · `2` | — | Consecutive polls the size must hold (minimum 1). |
| `stability.ready_marker` | string | — | A sibling marker (`{name}.done`) that releases the file at once. |
| `stability.exclude_temp_files` / `exclude_temp_patterns` | boolean · `true` / list | — | Drop `*.tmp`-style partial files (`Stability.DEFAULT_TEMP_PATTERNS` unless listed). |
| `duplicate.mode` | `path` · `metadata` · `checksum` · `etag` · `path` | `duplicate__mode` | How a re-seen file is recognised. `path` = the marker; the others use the fingerprint ledger. An unknown value reads as `path` (`DuplicatePolicy.Mode.from`). |
| `duplicate.algorithm` | string · `SHA256` | — | Checksum algorithm for `checksum` mode. |
| `duplicate.on_change` | `reprocess` · `ignore` · `alert` · `archive_old_version` · `reprocess` | `duplicate__on_change` | What a changed known file does. ⚠ An unknown value reads as `reprocess` (`DuplicatePolicy.OnChange.from`). The form offers exactly the four enum values — it once offered `skip`, which silently reprocessed; `NodeAttributesContractTest#everyOnChangeOptionNamesADuplicatePolicyValue` now pins every option to an `OnChange` value (`COLLECTOR-ON-CHANGE-SKIP-IS-REPROCESS-1`, 2026-09-23). |
| `guarantee` | `BEST_EFFORT` · `AT_LEAST_ONCE` · `EXACTLY_ONCE` · `BEST_EFFORT` | `guarantee` | The two stronger values need a content-based `duplicate.mode`; over `path` the parser warns and behaves best-effort. |
| `gap_detection.sequence` / `.enabled` | string / boolean · `true` | — (the `gap` node) | Numbered-series template (`ORDERS_{yyyyMMdd}`); a hole raises `SEQUENCE_GAP`. Authored on its own node. |
| `incremental.watermark` | `last_modified` | — | Skip files modified strictly before the ledger's high-watermark. Needs a content-based `duplicate.mode` (the parser warns otherwise; path mode never engages it). Other values disable it. |
| `fetch.parallel_fetch` | number · `1` | `fetch__parallel_fetch` | *Remote.* Files downloaded at once, each on its own connector session. |
| `fetch.rate_limit` | rate · unlimited | `fetch__rate_limit` · spec | *Remote.* Bandwidth cap: `512KB/s`, `10MBps`, `1GB/s` or bare bytes/s (1024-based). A value outside that grammar is a 422 at save (it used to fail at load). |
| `fetch.staging_dir` | path | — | *Remote.* Where a fetch stages before it lands in the inbox. |
| `fetch.mode` | string · `STAGE` | — | Parsed and round-tripped; no code reads it. |
| `retry.count` | number · `0` | `retry__count` · spec | *Remote.* Extra attempts for a failed listing or file download. `0` = one attempt. |
| `retry.backoff` | `EXPONENTIAL` · `LINEAR` · `FIXED` (`CONSTANT` = `FIXED`) · `EXPONENTIAL` | `retry__backoff` · spec | Full-jittered, capped at `max_delay` (`RetryPolicy`). The engine reads an unknown value as `EXPONENTIAL`; the spec refuses it at save. |
| `retry.initial_delay` / `retry.max_delay` | duration · `1s` / `60s` | `retry__initial_delay` / `retry__max_delay` · spec | First delay, and the cap on any one delay. |
| `circuit_breaker.failure_threshold` | number · `5` | `circuit_breaker__failure_threshold` · spec | *Remote.* Consecutive failed listings that trip the breaker. The block's presence turns the breaker on; absent = never trips. |
| `circuit_breaker.cooldown` | duration · `5m` | `circuit_breaker__cooldown` · spec | How long a tripped breaker skips acquisition before one trial listing. |
| `post_action.on_success` | `RETAIN` · `DELETE` · `MOVE` · `RENAME` · `TAG` · `RETAIN` | `post_action__on_success` · spec | *Remote.* What happens to the source-side original after a successful fetch. Checked against the connector's capabilities each cycle. An unknown value is a 422 at save (the engine would silently RETAIN). |
| `post_action.archive_path` | path template | `post_action__archive_path` · spec | The `MOVE` target — required with `MOVE` (`ERR_COLLECTOR_CONFIG_INVALID` when active). `yyyy`/`yy`/`MM`/`dd`/`HH`/`mm`/`ss` resolve against now (`PostAction.resolveTemplate`). |
| `post_action.tags` | map | — | Tags `TAG` writes. |
| `post_action.on_unsupported` | `FAIL` · `WARN_AND_CONTINUE` · `IGNORE` · `WARN_AND_CONTINUE` | — · spec | When the connector lacks the capability: `FAIL` stops the cycle, the others retain the file. |
| `consignment.max_files` / `.max_bytes` / `.order` | number · `1` / number / `mtime` · `name` | `consignment__*` | Consignment packing ([above](#collect--acquisition--the-collector)). `max_files` is spec-declared. |
| `processing.intake.max_files_per_cycle` / `.min_files_per_cycle` / `.adaptive` | number / number / boolean · inherit `-Dingest.*` | — · spec | The **local** throttle (`IntakeGovernor`): files one poll cycle may admit (global default 500; operator, 2026-10-06), the floor the adaptive controller may halve it to, and whether cycle overrun adjusts it. `0` = explicitly unbounded. Not under `collector:`, but it is the admission cap the Collector obeys; `examples/05-acquisition/intake-throttle` shows it. |

Duration = a bare number of seconds or `N` + `s`/`m`/`h`/`d` (`toMillis`); the spec's pattern is the same
rule, pinned by `CollectorResilienceSpecParityTest`. The acquisition node also borrows `trigger__*`,
marker dedup (`duplicate_check`, `marker_extension`, `retention_days`, `markers_dir`) and `unpack__*` from
outside the block. Their keys are in the served contract.

**Connection options the Collector depends on.** These are keys of the Connection profile
(`*_connection.toon` `options:`), not of `collector:`. Profile fields and every connector:
[connectors](../connectors/spec-connectors.md).

| Connector | Option | What it does |
|---|---|---|
| `sftp` | `base_path` (profile field) · `options.private_key` · `options.host_key` / `known_hosts` / `strict_host_key` | Listing root (default `.`); key-file auth; host-key pinning (`HostKeyPolicy`). `strict_host_key: true` with neither `host_key` nor `known_hosts` refuses to connect. |
| `db` | `options.query` (required) · `options.export_name` (required) · `options.watermark_column` · `options.watermark_initial` · `options.watermark_type` (`string` · `long` · `timestamp`) · `options.jdbc_url` · `options.driver` | `DbExportConnector`: runs the date-templated query and lands one CSV per cycle. `watermark_column` needs a `:watermark` placeholder in `query` (refused otherwise); the new maximum is stored only after the batch commits. |

**Examples.** `examples/07-steps/collect` · `spaces/default/config/collect_step` (include/exclude,
recursion, gap detection) · `05-acquisition/*` and `06-serve/{sequence-gap,checksum-change,
incremental-watermark}` for the duplicate and gap policies · `05-acquisition/intake-throttle` (the local
throttle, `processing.intake`: 5 files, 2 per cycle) · the infra-bound `_reference/sftp-collector` (SFTP
with `fetch.rate_limit`, `retry`, `circuit_breaker`, `post_action: MOVE`) and `_reference/db-export`
(`connector: db` with a watermark) — shape-correct, validated by `ShippedExamplesPassTheSaveGateTest`, not
runnable offline.

## `parse` → the **7**<!--count:parser-node-types--> `parser.*` frontends

**Function.** Turns a landed file into typed rows named by the schema's `raw.fields[]`
(`selector` = position, header name, JSON key, capture group or dotted record path, depending on the
frontend). The schema also carries the **mapping** — the Record Transformer that shapes those rows —
so `parse` and `map` are one node in the graph. Structural mismatches are rejected to `errors/`
(or the file is quarantined), never silently dropped; type-cast failures become NULL through `TRY_CAST`.

**Configuration.** The `parsing:` block: `frontend:` picks the family, and one sub-block carries that
family's grammar. A `parsing.grammar: grammar/<id>` reference (or a path) reuses a saved Grammar
component; inline keys win. `processing.schema_file` names the schema; `processing.csv_settings.date_formats`
/ `timestamp_formats` are required for any DATE / TIMESTAMP column, whatever the frontend.
The served step-type contract carries no per-frontend attributes: the parse surface is schema-driven
by the served grammar (`parsing-attributes.ts` in the UI). The keys each frontend reads:

| Frontend (node type) | `parsing.` block and its keys | Selector means | Example |
|---|---|---|---|
| `parser.delimited` | `delimited: {delimiter, quote, comment, has_header, skip_header_lines, skip_junk_lines, skip_tail_lines, null_strings[], date_formats[], timestamp_formats[], strict_mode, engine}` | column position (`"0"`) or header name | `01-ingest/hello-csv`, `02-parsing/pipe-delimited`, `02-parsing/no-header`, `csv_example` |
| `parser.fixedwidth` | `fixedwidth: {record: line\|bytes, trim, min_record_length, fields[n]{name,start,length}}` | field name from the slice table | `02-parsing/fixedwidth`, `fixedwidth_example` |
| `parser.json` | `json: {format: auto\|newline\|array, records_path, maximum_object_size}` | JSON key (dotted path) | `02-parsing/json-frontend`, `json_example` |
| `parser.text_regex` | `text_regex: {pattern}` — named capture groups; a non-matching line is dropped | group name | `02-parsing/text-regex-frontend` |
| `parser.xlsx` | `xlsx: {sheet, range, header, normalize_names, ignore_errors, stop_at_empty}` — DuckDB `read_xlsx`, needs the `excel` extension (`ExcelExtension`, cached or `-Dduckdb.extension.dir`), loaded fail-closed | normalised header cell | `02-parsing/xlsx-frontend`, `excel_example` |
| `parser.asn1` | `asn1: {grammar (inline X.680 text), root_type, strictness: BER\|DER\|CER, file_header_length, record_header_length, max_value_bytes, segments: {recordType: schemaPath}}` — sugar for the plugin wiring below with `Asn1RecordIngester` | dotted path into the decoded record (`party.number`); `EVENT_TYPE` is derived | `02-parsing/asn1-frontend`, `asn1_example` |
| `parser.plugin` | `plugin: {ingester: <FQCN of a StreamingFileIngester>, segments: {recordName: schemaPath}, ingester_config: {…plugin keys}}` (legacy spelling `processing.ingester` / `segments` / `ingester_config`). Shipped ingesters: `XmlRecordIngester` (`record_element`, `namespace_aware`, `encoding`), `FixedWidthRecordIngester` (`record_length`), `Asn1RecordIngester`. | one schema per **segment** (record kind); undeclared kinds are counted as junk | `02-parsing/xml-plugin-frontend`, `xml_example` |

**Refusals.** An unknown `frontend` is refused with the list. `frontend: asn1` beside an explicit
`plugin:` block refuses (two bindings). A plugin ingester with no `segments` refuses. A schema type
outside the engine's DuckDB scalar list refuses at load. A parser is always format-specific: the
generic `parser` type is authoring-legacy, retyped to `parser.<frontend>` on Apply.

**Behaviour worth knowing.** `has_header` skips the header; `skip_header_lines` is *added on top* for
junk preamble. Plugin-ingester pipelines have **no single schema**: the planner labels their
consignments `schema` (a `null` there threw until 2026-09-06). A segment's output lands under
`database/<segmentKey>/`.

## `transform` → `transform.filter` — Row filter

**Function.** Keep or drop rows. Two shapes: a **post-map SQL predicate** (`where:`, over the typed,
mapped columns — the one an explicit `steps[]` filter runs), and the **pre-parse text filters**
(`include_regex` / `exclude_regex` / `include_prefixes` / `exclude_prefixes` against one raw column,
`filter_target_column`) that speak raw-column vocabulary.

**Behaviour.** `RowShaper.filter` splits the input into `data` (kept) and `dropped` — a counted reject
relation, so nothing disappears unaccounted. ✅ **The INGEST lane keeps it too, since 2026-09-22 (`WB-09`/`D2`)** — a parse-rejected row is counted in
the batch ledger's `rejected_rows`, and `total_input_rows` includes it, so nothing disappears
unaccounted on either lane. 🔴 It did not until then: the ledger reported `rejected_count=0` for a file
that lost a truncated record, with input and output both excluding it, so the counters reconciled while
the record was missing. See [`consignment-status-flow.md`](architecture-elt.md). A NULL predicate result drops the row. Mid-branch (inside
`route:`) a filter must carry `where:`; a blank predicate or a pre-parse key refuses to arm. At rest the
legacy pre-map filter (`csv_settings.where`) is refused because the landed store no longer has raw columns.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `where` | string | required | — | **Row predicate (after parsing).** SQL over the mapped, typed columns. Rows matching are kept; NULL results are dropped. |
| `include_regex` | list | optional | — | **Include matching (before parsing).** Keep rows whose target column matches any of these regexes, checked on the raw text before parsing. |
| `exclude_regex` | list | optional | — | **Exclude matching (before parsing).** Drop rows whose target column matches any of these regexes. |
| `include_prefixes` | list | optional | — | **Include by prefix (before parsing).** Keep rows whose target column starts with any of these. |
| `exclude_prefixes` | list | optional | — | **Exclude by prefix (before parsing).** Drop rows whose target column starts with any of these. |
| `filter_target_column` | number | advanced | — | **Target column index.** Which raw column (0-based) the four before-parsing lists above match against. Ignored by the row predicate. |

**Example.** `examples/07-steps/filter` (8 → 4 rows) · `spaces/default/config/filter_step`.

## `lookup` → `transform.lookup` — Lookup & static map transcoder

**Function.** Transcode one column's values through an inline static map — `NEW → Open`, `SHIPPED → Done`
— without a reference store. The first PARTIAL Step Processor delivered (2026-09-06); a *versioned* lookup
that changes over time is still `transform.join` over a `produces: reference` pipeline.

**Behaviour.** `RowShaper.lookup` compiles the map to a `CASE CAST(column AS VARCHAR) WHEN 'k' THEN 'v' …
END` — keys and values are author **literals**, quoted through `sqlStr`, never SQL and never guarded as SQL.
In place (`SELECT * REPLACE`) unless `target` names a new column; an unmatched value passes through unchanged
unless `default` sets the literal it becomes. Comparison is textual, so a numeric code column maps without a
cast. Emits `data` only — a lookup changes values, never row counts. No legacy singular spelling: a chain
holding one always takes the `steps:` form (like `sql`); the recipe verb is `lookup`, config verbatim.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `column` | string | required | — | **Column to transcode.** The typed, mapped column whose values are looked up. |
| `mappings` | list | required | — | **Mappings (key=value).** One entry per code: the value found, `=`, the value to write. The one spelling — a map-valued attribute type is refused on purpose. |
| `target` | string | optional | in place | **Write to column.** A new column for the result; blank replaces the source column. |
| `default` | string | optional | pass through | **Unmatched values become.** The literal written when no mapping matches. |

**Example.** `examples/07-steps/lookup` (8 rows, `STATUS_LABEL` = Open/Done/Void) · `spaces/default/config/lookup_step`, run at rest by `jobs/lookup_step_rollup_job.toon` into `lookup_step_rollup`.

## `explode` → `transform.explode` — Array exploder

*Added 2026-10-06 (operator, 2026-10-06: build `transform.explode` as a NEW type; `transform.split` stays
the grandfathered, non-authorable ancestor and is not un-grandfathered).*

**Function.** One output row per element of an array column — a CDR's charge items, an order's lines —
so a nested feed becomes a flat fact table without hand-written SQL.

**Design (decisions, operator, 2026-10-06).**
- **What explodes.** A DuckDB `LIST` column (any element type, `LIST<STRUCT>` included) is unnested as is.
  A `VARCHAR` / `JSON` column holding a **JSON array** is cast to `JSON[]` first, so each element lands as
  a `JSON` value a following `sql` step can pick apart (`elem->>'amount'`). This is the shape every
  `parser.json` feed produces: the parser lands each key as VARCHAR. A JSON value that is not an array
  (an object, a scalar, malformed text) is treated like `null` — an empty array — never as an error,
  because one odd record must not fail the batch. Any other column type (a bare `STRUCT`, a number) is
  **refused at run, naming the type**: a struct is one value, there is nothing to explode.
- **Empty / null arrays.** `on_empty: keep` (default) keeps the row once with a `null` element and a `null`
  index; `on_empty: drop` drops it. Dropping is opt-in because a vanished parent row is silent data loss.
- **Order and index.** Elements come out in array order; `index_column` (optional) adds the element's
  **1-based** position. Compiled as `UNNEST(list_transform(arr, (x, i) -> {e: x, i: i}))`, not a `LATERAL`
  join, because a lateral join does not keep element order.
- **Identifiers.** `column` must be a column of the inbound relation (checked at run against `DESCRIBE`, and
  at save against the declared schema when it is known). New names (`as`, `index_column`) must match
  `[A-Za-z_][A-Za-z0-9_]{0,127}` and must not collide with a kept column or each other. Every name is
  quoted through `SqlIdent.q`; nothing the author types is spliced into SQL unquoted.
- **The array column itself.** Dropped by default (`keep_source: false`), so `as` defaults to the source column's
  own name; `keep_source: true` keeps it beside the element and then needs a distinct `as`.
- **Lane.** A `steps:` kind with no legacy singular spelling (like `lookup`), so it executes **at rest**
  with `output_store:`. It is not admitted mid-branch (`RouteArming.BRANCH_STEP_KINDS` unchanged). It
  amplifies rows, so `ConservationCheck` leaves it out, as it does `transform.split`.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `column` | string | required | — | **Array column.** A LIST column, or a VARCHAR/JSON column holding a JSON array. |
| `as` | string | optional | the source column's name | **Element column.** The column each element is written to. |
| `index_column` | string | optional | none | **Index column.** Adds the element's 1-based position. |
| `keep_source` | boolean | optional | `false` | **Keep the array column.** Keeps the original array beside the element. |
| `on_empty` | select | optional | `keep` | **Empty or null array.** `keep` the row with a null element, or `drop` it. |

**Example.** `examples/07-steps/explode` — a JSON CDR feed whose `charges` array holds 0–3 charge items,
exploded and then flattened by a `sql` step.

## `unpivot` → `transform.matrix.unpivot` — Unpivot / column flattener

*Added 2026-10-06 (operator, 2026-10-06).*

**Function.** Wide columns become `(name, value)` rows — a per-hour `H00 … H23` usage row, a quarterly
`Q1 … Q4` sheet — so a matrix-shaped feed can be grouped and charted like any long table.

**Design (decisions, operator, 2026-10-06).**
- **Name.** The node type keeps the catalog id `transform.matrix.unpivot`; its `steps:` kind is the plain
  word `unpivot` (a dotted TOON key would read as a path). Because the type is not `transform.<kind>`, the
  lift maps a kind to its type through `BuiltinNodeType.typeForStepKind`, not by string concatenation;
  `BuiltinNodeTypeFlatHomeTest` pins that every `STEP` case round-trips through it.
- **Which columns.** Exactly one of `columns` (a list of inbound names) or `columns_pattern` (a Java
  regular expression, matched with *find* — the same partial-match rule as DuckDB's `COLUMNS('…')` — against
  each inbound name, in declaration order). The pattern is resolved in Java against `DESCRIBE`, so only
  real, quoted column names reach SQL. A listed column that does not exist, a pattern that matches nothing,
  or both keys at once is refused. Every other column passes through unchanged and is repeated per row.
- **Type coercion (stated rule).** Every unpivoted column is `CAST` to `value_type` **before** the unpivot,
  so the value column has exactly one declared type whatever the input mix. Default `VARCHAR` — every DuckDB
  type converts to text, so the default never fails. Allowed: `VARCHAR`, `BOOLEAN`, `INTEGER`, `BIGINT`,
  `DOUBLE`, `DATE`, `TIMESTAMP`, `DECIMAL(p,s)`. The cast is strict (`CAST`, not `TRY_CAST`): a value that
  does not convert **fails the run** rather than landing as a silent `null`.
- **Nulls.** A `null` cell produces no row by default (DuckDB's `UNPIVOT`); `include_nulls: true` keeps it.
- **Identifiers.** `name_column` (default `name`) and `value_column` (default `value`) must match
  `[A-Za-z_][A-Za-z0-9_]{0,127}`, differ from each other and from every passed-through column.
- **Lane.** Like `explode`: a `steps:`-only kind, executed at rest, not admitted mid-branch, and left out of
  `ConservationCheck` (it multiplies rows).

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `columns` | list | optional | — | **Columns to unpivot.** Inbound column names. Exactly one of this or `columns_pattern`. |
| `columns_pattern` | string | optional | — | **Column name pattern.** A regular expression; every inbound column it matches is unpivoted. |
| `name_column` | string | optional | `name` | **Name column.** Receives the unpivoted column's name. |
| `value_column` | string | optional | `value` | **Value column.** Receives the cell value. |
| `value_type` | select | optional | `VARCHAR` | **Value type.** Every unpivoted column is cast to it first. |
| `include_nulls` | boolean | optional | `false` | **Keep empty cells.** Emit a row for a `null` cell too. |

**Example.** `examples/07-steps/unpivot` — a nested JSON CDR feed whose per-call charge breakdown arrives as
wide `charge_*` keys, unpivoted into `(CHARGE_TYPE, AMOUNT)` rows.

## `resample` → `transform.timeseries.resample` — Time-grain resampler & gap imputer

*Added 2026-10-17 (ANALYTICS-CORE-1 M5, [analytics-kernel § Time-series primitives](../analytics/spec-analytics-distribution-time-series.md#time-series-primitives)).*

**Function.** One row per key per time bucket, **every bucket between the bounds present** — a daily, weekly or
monthly series ready for a chart, a KPI or a shift, with no missing periods.

**Behaviour.** The SQL is the Analytics Kernel's (`TimeSeriesSql.resample`), bucketing by its one truncation
definition (`TimeGrain`): `second` · `minute` · `hour` · `day` · `week` (Monday, ISO) · `month` · `quarter` · `year`.
- **Measures** speak the one measure grammar (`count` | `agg(field)`, the same aggregates as `summarize`, evaluated by
  `MeasureCompiler`); output columns are the measure ids (`count`, `sum_AMOUNT`).
- **Gap fill** (as Anomaly D-AD8): a bucket with no rows gets **0** for `count`, `countDistinct` and `sum`, and
  **empty** for every other aggregate (an average of nothing is not 0).
- **Series.** Every key seen in the data gets the whole series, from `from` (or the earliest bucket with data) to `to`
  (or the latest), both truncated to the grain. A key's empty value is a key of its own; a row whose time is empty is
  left out. A series of **100,000 buckets or more** fails the run (narrow `from`/`to` or use a coarser grain).
- **The clock is always stated.** A `DATE` or zone-less `TIMESTAMP` column is bucketed on its own clock and takes no
  `timezone`. A `TIMESTAMP WITH TIME ZONE` column **needs** `timezone` (a region id such as `Europe/Berlin`, or `UTC`)
  and is bucketed on that zone's wall clock — never DuckDB's session zone, which is the host's. The bucket column is
  then that zone's local start time (a `TIMESTAMP`). Both mismatches are refused at run and at save.
- Identifiers are validated (`[A-Za-z_][A-Za-z0-9_]*`) and quoted; the output columns (keys, bucket, measure ids) must
  differ. Runs **at rest** like every `steps:` kind; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `time_column` | string | required | — | **Time column.** The DATE or TIMESTAMP column the rows are bucketed on. Rows where it is empty are left out. |
| `grain` | select | required | — | **Time grain.** The bucket size. Weeks start on Monday. |
| `timezone` | string | optional | — | **Time zone.** Only for a TIMESTAMP WITH TIME ZONE column: whose calendar the buckets follow. |
| `group_by` | list | optional | — | **Series keys.** One series per key; every key gets every bucket. |
| `measures` | list | required | — | **Measures.** `count` or `agg(field)`; an empty bucket gets 0 for count, countDistinct and sum, empty otherwise. |
| `bucket_column` | string | optional | `bucket` | **Bucket column.** Receives each bucket's start. |
| `from` | string | optional | — | **First bucket.** `2026-01-01` or `2026-01-01T08:00`; blank = the earliest bucket with data. |
| `to` | string | optional | — | **Last bucket.** Blank = the latest bucket with data. |

**Example.** `examples/07-steps/timeseries` — 7 sales over 5 days in 2 regions → 10 day rows, empty days filled with 0.

## `shift` → `transform.timeseries.shift` — Period-over-period shift

*Added 2026-10-17 (ANALYTICS-CORE-1 M5).*

**Function.** Every row gains, per listed column, the value its key had **N buckets earlier** — the previous day,
the same month last year (`periods: 12` with `grain: month`), the next week (`periods: -1`).

**Behaviour.** `TimeSeriesSql.shift`: a self-join **by bucket**, not by row position — `time_column` truncated to
the grain, plus N grain-intervals, must equal the current row's bucket — so a series with a missing bucket gets an
empty value instead of the wrong period's. Every inbound row is kept, ordered by keys then time.
- The input must hold **one row per key per bucket** (a `resample`'s output). Two rows for one key and bucket are
  refused before the join (the earlier value would be ambiguous and rows would multiply).
- The added columns are `<column><suffix>` (default `_prev`); a name that collides with an inbound column is refused.
- `periods` is a non-zero whole number within ±10,000. The clock rule is resample's: `timezone` only, and always, for a
  `TIMESTAMP WITH TIME ZONE` column.
- Runs **at rest**; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `time_column` | string | required | — | **Time column.** The bucket column (a resample's output); one row per key per bucket. |
| `grain` | select | required | — | **Time grain.** The bucket size. Weeks start on Monday. |
| `timezone` | string | optional | — | **Time zone.** Only for a TIMESTAMP WITH TIME ZONE column. |
| `group_by` | list | optional | — | **Series keys.** Shift within each key. |
| `columns` | list | required | — | **Columns to shift.** Each gains a column with its value from the earlier bucket. |
| `periods` | number | optional | `1` | **Buckets back.** 1 = previous bucket; 12 with a month grain = same month last year; −1 = next bucket. |
| `suffix` | string | optional | `_prev` | **Name suffix.** `sum_amount` becomes `sum_amount_prev`. |

**Example.** `examples/07-steps/timeseries` — the resample's daily sums with the previous day's beside them.
