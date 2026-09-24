---
type: Reference
title: Pipeline config keys — the single block census
description: Every top-level block of <name>_pipeline.toon — who declares it (ConfigSpecs vs parser-only), who reads it, which surface authors it — plus the coverage census and the PipelineKeyCoverageContractTest ratchet.
resource: inspecto-etl/src/test/java/com/gamma/etl/PipelineKeyCoverageContractTest.java
tags: [pipeline-graph, config, pipeline, config-spec, parser, coverage, ratchet]
timestamp: 2026-09-01T00:00:00Z
---

# Pipeline config keys — the single block census

The one owner for what a `<name>_pipeline.toon` may contain at block level. Grounded against code
2026-09-01: `ConfigSpecs.pipeline()` (inspecto-config), `PipelineConfigParser` (inspecto-etl) and
`PipelineKeyCoverageContractTest` (inspecto-etl). **Code wins over every doc, this one included** — the
contract test re-derives the census from source on every build, so when this page and the test disagree,
the test is current.

## The ownership rule (state it once)

**Two authorities read this file, and they are not the same surface** (pipeline spec §3):

- **`ConfigSpecs.pipeline()`** — what the product *declares*: the spec a UI form is generated from, an
  LLM authors against, and the `POST /config/write` / graph-save 422 gate validates by.
- **`PipelineConfigParser`** — what the engine *navigates*: the loader that actually runs the file.

A block the parser reads but the spec does not declare is **engine-honoured and authoring-invisible**:
no form field, no draft validation, no round-trip guarantee. That drift is pinned and ratcheted by
`PipelineKeyCoverageContractTest.UNDECLARED_BLOCKS`, which **may only ever shrink**.

⚠ **Declaring a block merely to shrink the ratchet is gaming the guard, not closing the gap**
(`pipeline-waves-drain-plan.md` §1). Worked example: declaring `steps` was **refused** — `FieldSpec` has
no item-schema facility and `ConfigJsonSchema` maps `LIST` to a bare `"array"`, so the declaration would
remove the allow-list entry while *"no generated form can show it"* stayed true. Remove an entry only
when an author can actually see the block.

There is also a **third, utility-only reader family** outside both authorities: the pre-ETL tools in
`inspecto-util` read their own top-level sections (below) from the same file.

## The census (2026-09-01)

| Scope | Blocks the parser reads | Declared in `ConfigSpecs.pipeline()` | Parser-only (ratchet list) |
|---|---|---|---|
| Top-level | 18 | 13 | 5 |
| `processing.*` | 24 | 14 | 10 |
| **Total** | **42** | **27** | **15** |

History: 18 parser-only when the ratchet landed (2026-08-31); 17 after `output_store` was declared the
same day (gap 8); **16** after CONSIGNMENT-HOME-1 declared `collector.consignment.max_files`
(2026-09-02), which took `collector` off the list; **15** after `sinks` was declared (2026-09-24) as a
list of objects WITH an item spec (`FieldSpec.items`) — the facility whose absence is why `steps` was
refused above, so `sinks` is visible to validation and to `ConfigJsonSchema`, not merely delisted. The 15 current entries are exactly
`UNDECLARED_BLOCKS`.

⚠ **Granularity is the block, deliberately.** Leaf drift *inside* a declared block is not covered —
the known case: `dirs` is declared (poll, database, backup, temp, status_dir have `FieldSpec`s) while
`dirs.errors` / `dirs.quarantine` / `dirs.markers` / `dirs.log_dir` are engine-read and undeclared.
Likewise `collector.consignment` (canonical since 2026-09-02, CONSIGNMENT-HOME-1; `processing.batch` is its
dual-read legacy) is declared only via `max_files` (`max_bytes` / `order` are parser-only leaves), and cross-field rules reference `parsing.source_timezone` / `parsing.delimited.*` with no
matching `FieldSpec`.

## Top-level blocks

Declares: **spec** = `FieldSpec` in `ConfigSpecs.pipeline()`; **parser-only** = in `UNDECLARED_BLOCKS`.

| Block | Declares | Reads (engine) | Authors |
|---|---|---|---|
| `name` | spec (required) | `PipelineConfigParser` — identity derived from it when `id` absent | create scaffold (`pipelineScaffold()`, both create surfaces); `POST /pipelines/{n}/label` |
| `id` | spec | parser identity; names the file, `<id>_commits.log`, ledger `source_id`, Catalog Stream | stamped at create; moved only by `POST /pipelines/{n}/rename` |
| `description` | spec | no engine code — list-row subtitle | pipeline editor |
| `active` | parser-only | the arming gate: poll cycle + `MultiCollectorProcessor`; manual trigger ignores it | editor lifecycle toggle / hand |
| `template` | parser-only — **deliberate** (a lifecycle flag, kept out of schema forms; [pipeline-identity](../control-plane/pipeline-identity.md)) | parser refusal (`template`+`active`), `CollectorService.refuseIfTemplate`, `PipelineScheduler.selectDue` | written only by `save-as-template` |
| `produces` | spec | catalog registration: `REFERENCE_DATASET` origin vs Stream | Settings dialog (`GET/POST /pipelines/{n}/settings`) |
| `reference` | spec (`load`/`key`/`refresh_seconds`) | `ConsignmentIngestStrategy.stampReferenceVersions`, `EnrichmentEngine.versionedView`, `ReferenceCompactor`, `CollectorService.armReferenceRefresh` | Settings dialog |
| `stream` | spec | `MetadataGraphBuilder` Stream grouping | hand-authored (default = pipeline name) |
| `dirs` | spec block (5 of 9 leaves — see census caveat) | `CollectorProcessor`; `dirs.backup` doubles as the park home (`StepDisableArming`) | create scaffold derives the convention set; hand after |
| `collector` | spec block (a few leaves only — `collector.consignment.max_files`, and since 2026-09-23 `collector.fetch.rate_limit`, `collector.retry.*`, `collector.circuit_breaker.*`, and since 2026-09-24 `collector.post_action.*`; see the census caveat) | `parseCollector` → `Collector` → acquisition framework (connectors, stability gate, dedup ledger, gap detection, `connector: dataset`) | collector drawer (one component, one write route) |
| `parsing` | spec, partially (`parsing.grammar` is the canonical grammar ref; `source_timezone` / `delimited.*` are rule-only) | `mergeParsing` / `resolveGrammarRef` → format frontends | Parse drawer; New-pipeline writes `parsing.frontend` (D3) |
| `processing` | spec block (see next table) | ingest runtime | Parse drawer + per-key surfaces below |
| `output` | spec (`format`/`compression`/`filename_column`) | ingest strategies / `PartitionWriter` | sink node config |
| `output_store` | spec (since 2026-08-31, gap 8) | `PipelineConfig.prepare()` **arming condition** for `steps:`/`dedup`/`summarize`/`join`/`webhook`; `PipelineLift.stageTwo`; `SchedulerAuditTask` orphan report | hand / schema form; required to arm a Stage-2 chain (`stage-two-blocks-require-output-store`, ERROR at save) |
| `sinks` | spec — a list of objects: `FieldSpec.listOf` with an item spec (`database` required; `format`/`compression`/`filename_column`/`ducklake.*` optional, since an omitted key inherits `output:`), so a non-list, a non-map entry or an entry with no `database` is a 422 at save rather than a load-time throw ([output-sinks](../engine/output-sinks.md)) | `IngestSinkWriter` / `ConsignmentGraphRunner` — `database` is the branch↔sink join key | canvas (multiple destinations) |
| `route` | parser-only | `ConsignmentGraphRunner` — branch-aware **ingest lane only**; refused inside `steps:` by both paths | canvas route node + branch predicates |
| `steps` | parser-only — **entry kept deliberately** (no item-schema facility; declaring it would game the ratchet) | `PipelineLift` authored-order chain → at-rest `pipeline_config:` job | Recipe view step cards (`<app-pipeline-step-cards>`) |
| `trigger` | parser-only | `PipelineScheduler` (`every:`/`cron:` per-tick gate); dataset-commit trigger (`on:dataset`) | canvas trigger nodes (`trigger__every`/`trigger__cron` borrow the top-level keys); `trigger.type` is derived |
| `webhook` | spec (`connection`/`batch_size`/`retry`, since 2026-09-23) | `PipelineLift.stageTwo` → `sink.webhook` branch → `WebhookSink` (at rest only; `prepare()` refuses the ingest lane); **strict keys**, and an authored `url:`/`token:` is refused by name | the `sink.webhook` node drawer (verbatim) / recipe verb `webhook:` ([step-catalog](step-catalog.md#webhook--sinkwebhook--the-outbound-webhook)) |

Rules that cut across blocks:

- **The chain has two spellings and the file owns which one:** legacy singular blocks
  (`processing.dedup`/`join`/`summarize`, `route:`) or an explicit `steps:` list, never both — the
  parser refuses a file carrying both, and `lower` keeps whichever spelling the existing file uses
  (pipeline-graph-design §16).
- **Unmodelled keys travel through a save untouched** (`PipelineEditable` ownership rule): a key the
  graph editor does not model (`description`, `produces`/`reference`, `partitions`, …) round-trips
  verbatim; `produces`/`reference` got the Settings dialog precisely because passthrough is not a write
  path (§17).

## `processing.*` blocks

Declared (14): `threads`, `duckdb_threads`, `file_pattern`, `schema_file`, `ingester`,
`grammar` (deprecated alias — `parsing.grammar` wins, WARNING at save), `csv_settings`, `unpack`,
`batch` (deprecated alias — `collector.consignment` is canonical, healed on save), `priority`, `duckdb`,
`chunking`, `intake`, `streaming`.

Parser-only (10):

| Block | Reads (engine) | Authors |
|---|---|---|
| `processing.dedup` | at-rest chain (`RowShaper.dedup`) — arming needs `output_store` | node drawer (legacy singular spelling) |
| `processing.join` | at-rest chain | node drawer |
| `processing.summarize` | at-rest chain | node drawer |
| `processing.map` | `RowShaper.columnsOf`/`mappingSchemaOf` — **executes on the live ingest graph executor**, unlike its three neighbours; authored `columns`/`rules` vs derived `schema`/`csv` split pinned by `MapNodeKeyContractTest` | map node dialog; never a `steps:` kind |
| `processing.disabled_steps` | `StepDisableArming` / `PipelineLift` overlays `enabled: false` — park-and-drain ([step-park-drain](step-park-drain.md)) | **product-derived** on save from node enabled state |
| `processing.duplicate_check` | marker-file dedup on the local poll path (`CollectorProcessor`) — ⚠ the `collector.duplicate:` block is a no-op there | guided create derives it silently; hand |
| `processing.schemas` | `SchemaSelector` two-pass multi-schema dispatch (file-pattern fast path, column-count probe) — replaces `schema_file` | hand-authored only |
| `processing.segments` | plugin-ingester segment→schema map (`StreamingPluginIngestStrategy`); required non-empty when `ingester` is set | hand-authored only |

### A `segments{}` block IS a schema for the arming gate (D7, signed 2026-09-22)

✅ **Decision (operator, 2026-09-22):** a non-empty `parsing.<frontend>.segments{}` — `parsing.asn1.segments`
and its plugin twin — **counts as a schema source** wherever the code asks *“is this Pipeline armed without
a schema?”*, alongside `processing.schema_file`, `processing.schemas[]` and a plugin ingester. It is the only
schema source a segment-routed frontend has.

✅ **AS-BUILT since 2026-09-22 (`WB-03`).** The one predicate is **`ConfigRoutes.hasSchemaSource`**, called
by the write gate (`ConfigRoutes.armedWithoutSchemaFindings`, and through it every write path) and by
`POST /validate` (`ConfigPreviewRoutes`, both its `configPath` and its draft branch). ⛔ Do not add a
second implementation that merely agrees with it: two gates answering differently about one config is the
defect this closed.

⚠ **The predicate scans every `parsing.*` sub-block for a non-empty `segments{}`**, rather than matching
frontend NAMES — keying on names is exactly how `asn1` came to be missed, and a new segment-routed
frontend is covered the day it is added. An EMPTY `segments{}` is still no schema: the check is on
content, not shape.

🔴 **What it was:** measured across the corpus 2026-09-22, `POST /validate` returned no ERROR for
`asn1_example` while `PUT …/graph` with the editor's own lossless payload refused **422
`ERR_ARMED_WITHOUT_SCHEMA`** — a shipped, active, working ASN.1 Pipeline could be **opened but never
saved**. ✅ It failed closed throughout (`written:false`, file byte-identical), so nothing was corrupted;
it was simply unauthorable. ⚠ The gap was TWICE what the row described: the predicate ignored
`segments{}` **and** `/validate` never called the arming check in either branch, so an active draft with
no schema at all also validated clean. Both halves landed together. Pinned by
`ControlApiSprintAContractsTest` and by the 26-of-26 HTTP round-trip sweep
(`ControlApiPipelineGraphRoundTripSweepTest`); row `SAVE-GATE-VS-VALIDATE-DISAGREE-1`.

⚠ **The `csv_settings` validation rules are delimited-only** (`WB-04`, 2026-09-22) — the delimiter rule as
well as the `date_formats` / `timestamp_formats` ones. They used to fire on 6 of 26 shipped Pipelines that
carry no `csv_settings` block at all (JSON, Excel, ASN.1, fixed-width, XML, the parquet re-ingest), so each
was born `clean:false` for a rule that could not apply — which trains authors to skip warnings.
Falsification-probed: forcing the gate open turns the test red. Row `VALIDATE-CSV-RULE-FRONTEND-BLIND-1`.
| `processing.ingester_config` | free-form map handed to the plugin ingester | hand-authored only |
| `processing.mapping_file` | `RowShaper` — a *declared* mapping reference; authored `processing.map.columns` beside it refuses `MAPPING_CONFLICT` | hand-authored only |

### Node-config keys that are not `processing.*` blocks — `transform.sql` (2026-09-04)

A `transform.sql` Step has **no `processing.*` block** — its flat-config home is an explicit `steps:` entry
of kind `sql` (`PipelineEditable` LOWERABLE + STEP_KIND, `c119a6af`), and since 2026-09-04 it is also a
**recipe verb** (`sql`, between `transform` and `summarize`; `RecipeCompiler`/`RecipeConverter`
round-trip it, see [`catalog-vs-executors.md`](../engine/catalog-vs-executors.md)). Like any explicit
chain, an ACTIVE pipeline carrying one must declare top-level `output_store:`
(`PipelineConfig.prepare()`), and mid-branch inside `route:` it compiles but does not arm
(`RouteArming.BRANCH_STEP_KINDS` excludes it — a save-time finding, deliberate). Its node config carries **`sql`** — the one declared attribute (`NodeAttributes.TRANSFORM_SQL`,
`multiline`, required): a single `SELECT` whose input relation is the fixed alias **`input`** (`FROM
input`), rewritten to the real relation at execution. 🔴 **DDL/DML/multi-statement are refused at
EXECUTION and at `/components/transform/describe` — NOT at save** (corrected 2026-09-09): `SqlGuard` has
zero call sites on any save path, so such a statement persists cleanly and fails only when it runs.
The Angular pane stores **`fields[]`** beside it — the grid rows that generated the SQL.

🔴 **`fields[]` IS executable — this paragraph said the opposite until 2026-09-09, and that error is
load-bearing.** `RowShaper.MAP_NODE_CONFIG_KEYS` contains it and `RowShaper.isProjection` returns true
when a node carries record fields, so `fields[]` **is the mapping that runs**. `PipelineEditable`'s own
comment states the stake: *"a key that becomes executable without joining this allow-list is silently
dropped on save, which is the failure both constants exist to make impossible."* The old sentence
(“the engine never reads it … must not be added to the contract”) is exactly the reasoning that left the
client mirror `pipeline-editable.ts` carrying two keys where `PipelineEditable.MAP_AUTHORED` carries
three — the drift recorded in `pipeline-authoring.md` §2 (that mirror was deleted 2026-09-24). ⚠ Its absence also no longer means a locked table: the
“hand edit LOCKS the Step” rule was superseded 2026-09-05 by the peer Fields|SQL views. Details:
[`catalog-vs-executors.md`](../engine/catalog-vs-executors.md).

## Utility-only sections (neither authority)

Read by the pre-ETL tools in `inspecto-util`, never by the parser or the spec — they coexist in the same
file by design:

| Section | Reader |
|---|---|
| `search` | `FileOrganizer` |
| `copy_tars` | `TarArranger` / `TarInboxPreparer` |
| `backup` (top-level section, not `dirs.backup`) | `FileBackup` |

## Keys that appear in docs and examples but nothing reads

- **`version:`** — present in the worked examples (configuration.md §3, pipeline spec §3); the parser
  never reads it. Inert.
- **`source:`** — the retired pre-rename spelling of `collector:` (the 2026-07-14 GLOSSARY flip);
  the parser reads only `collector:`, so a `source:` block is silently ignored. Both the docs'
  ghost section and the parser's message-level drift were corrected 2026-09-01
  (configuration.md's acquisition section; the R1 message sweep incl. the connector factories).

## The ratchet

`PipelineKeyCoverageContractTest` (inspecto-etl) scans `PipelineConfigParser`'s source for every
top-level and `processing.*` read and compares against `ConfigSpecs.pipeline()`:

- a **new undeclared block fails the build** immediately;
- a **newly declared block must leave** `UNDECLARED_BLOCKS` or the stale-entry test fails;
- the list **only ever shrinks** — its size (15) is the remaining gap-10 debt;
- the scan is **self-falsifying**: pinned certainly-read blocks, a minimum count (≥ 35), and a pinned
  count of the two `raw`-shadowing locals guard against the scan silently matching nothing.

## The accepted-names table (generated)

The census above is a narrative; this table is the **machine-readable answer to "which blocks may a
`<name>_pipeline.toon` carry?"** — every block some component reads, which is exactly what
`AcceptedConfigKeys.acceptedBlocks("pipeline")` returns and exactly what the dead-property gate at
`POST /config/write` / `POST /config/patch` enforces (`DUCKLE-C3-DEAD-PROPERTY-1`). A block that is
not here is a **silent loss**: the engine ignores it, so the save refuses it with
`ERR_UNKNOWN_CONFIG_KEY` and, when one is close, the name the author probably meant.

⛔ **Do not hand-edit the block below.** It is rendered from `AcceptedConfigKeys` by
`AcceptedConfigKeysDocContractTest`, which fails with the exact expected text when the two disagree —
that is what makes this doc incapable of drifting from the checker.

⚠ Only the two censused scopes are listed, and only those are enforced: **top-level** and
**`processing.*`**. Every other block is accepted WHOLE (`dirs`, `collector`, `parsing`, `output`,
`reference`), because their leaves are not censused — `dirs.quarantine` and a connector's own
`collector.*` keys are engine-read with no `FieldSpec`, so a leaf-granular gate would refuse configs
that run today. A block named `x-…` is the author's own annotation: always accepted, never suggested
against, round-tripped untouched.

<!-- generated:accepted-config-keys -->

| Accepted block | Declared by |
|---|---|
| `active` | parser-only |
| `collector` | spec |
| `collector.circuit_breaker` | spec |
| `collector.consignment` | spec |
| `collector.fetch` | spec |
| `collector.post_action` | spec |
| `collector.retry` | spec |
| `description` | spec |
| `dirs` | spec |
| `dirs.backup` | spec |
| `dirs.database` | spec |
| `dirs.poll` | spec |
| `dirs.status_dir` | spec |
| `dirs.temp` | spec |
| `id` | spec |
| `name` | spec |
| `output` | spec |
| `output.compression` | spec |
| `output.ducklake` | spec |
| `output.filename_column` | spec |
| `output.format` | spec |
| `output_store` | spec |
| `parsing` | spec |
| `parsing.grammar` | spec |
| `processing` | spec |
| `processing.batch` | spec |
| `processing.chunking` | spec |
| `processing.csv_settings` | spec |
| `processing.dedup` | parser-only |
| `processing.disabled_steps` | parser-only |
| `processing.duckdb` | spec |
| `processing.duckdb_threads` | spec |
| `processing.duplicate_check` | parser-only |
| `processing.file_pattern` | spec |
| `processing.grammar` | spec |
| `processing.ingester` | spec |
| `processing.ingester_config` | parser-only |
| `processing.intake` | spec |
| `processing.join` | parser-only |
| `processing.map` | parser-only |
| `processing.mapping_file` | parser-only |
| `processing.priority` | spec |
| `processing.profile` | spec |
| `processing.schema_file` | spec |
| `processing.schemas` | parser-only |
| `processing.segments` | parser-only |
| `processing.streaming` | spec |
| `processing.summarize` | parser-only |
| `processing.threads` | spec |
| `processing.unpack` | spec |
| `produces` | spec |
| `reference` | spec |
| `reference.key` | spec |
| `reference.load` | spec |
| `reference.refresh_seconds` | spec |
| `route` | parser-only |
| `sinks` | spec |
| `steps` | parser-only |
| `stream` | spec |
| `template` | parser-only |
| `trigger` | parser-only |
| `webhook` | spec |
| `webhook.batch_size` | spec |
| `webhook.connection` | spec |
| `webhook.retry` | spec |

<!-- /generated:accepted-config-keys -->

⚠ `version:` and `source:` (the two "appear in docs, read by nothing" keys above) are **not** on this
table and are therefore now refused at the authoring gate — which is the row's point: both were
silently ignored, and `source:` in particular looked like it still worked.
