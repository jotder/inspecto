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
| Top-level | 18 | 11 | 7 |
| `processing.*` | 24 | 14 | 10 |
| **Total** | **42** | **25** | **17** |

History: 18 parser-only when the ratchet landed (2026-08-31); 17 after `output_store` was declared the
same day (gap 8). The 17 current entries are exactly `UNDECLARED_BLOCKS`.

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
| `reference` | spec (`load`/`key`/`refresh_seconds`) | `BatchIngestStrategy.stampReferenceVersions`, `EnrichmentEngine.versionedView`, `ReferenceCompactor`, `CollectorService.armReferenceRefresh` | Settings dialog |
| `stream` | spec | `MetadataGraphBuilder` Stream grouping | hand-authored (default = pipeline name) |
| `dirs` | spec block (5 of 9 leaves — see census caveat) | `CollectorProcessor`; `dirs.backup` doubles as the park home (`StepDisableArming`) | create scaffold derives the convention set; hand after |
| `collector` | parser-only | `parseCollector` → `Collector` → acquisition framework (connectors, stability gate, dedup ledger, gap detection, `connector: dataset`) | collector drawer (one component, one write route) |
| `parsing` | spec, partially (`parsing.grammar` is the canonical grammar ref; `source_timezone` / `delimited.*` are rule-only) | `mergeParsing` / `resolveGrammarRef` → format frontends | Parse drawer; New-pipeline writes `parsing.frontend` (D3) |
| `processing` | spec block (see next table) | ingest runtime | Parse drawer + per-key surfaces below |
| `output` | spec (`format`/`compression`/`filename_column`) | ingest strategies / `PartitionWriter` | sink node config |
| `output_store` | spec (since 2026-08-31, gap 8) | `PipelineConfig.prepare()` **arming condition** for `steps:`/`dedup`/`summarize`/`join`; `PipelineLift.stageTwo`; `SchedulerAuditTask` orphan report | hand / schema form; required to arm a Stage-2 chain (`stage-two-blocks-require-output-store`, ERROR at save) |
| `sinks` | parser-only | `IngestSinkWriter` / `BatchGraphRunner` — `database` is the branch↔sink join key | canvas (multiple destinations) |
| `route` | parser-only | `BatchGraphRunner` — branch-aware **ingest lane only**; refused inside `steps:` by both paths | canvas route node + branch predicates |
| `steps` | parser-only — **entry kept deliberately** (no item-schema facility; declaring it would game the ratchet) | `PipelineLift` authored-order chain → at-rest `pipeline_config:` job | Recipe view step cards (`<app-pipeline-step-cards>`) |
| `trigger` | parser-only | `PipelineScheduler` (`every:`/`cron:` per-tick gate); dataset-commit trigger (`on:dataset`) | canvas trigger nodes (`trigger__every`/`trigger__cron` borrow the top-level keys); `trigger.type` is derived |

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
input`), rewritten to the real relation at execution; DDL/DML/multi-statement refused. The Angular pane
stores **`fields[]`** beside it — the Simple-mode rows that generated the SQL. `fields[]` is an authoring
artifact the engine never reads (its absence means the SQL was hand-written and the Simple table is
locked); it is not a declared attribute and must not be added to the contract as one. Details:
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
- the list **only ever shrinks** — its size (17) is the remaining gap-10 debt;
- the scan is **self-falsifying**: pinned certainly-read blocks, a minimum count (≥ 35), and a pinned
  count of the two `raw`-shadowing locals guard against the scan silently matching nothing.
