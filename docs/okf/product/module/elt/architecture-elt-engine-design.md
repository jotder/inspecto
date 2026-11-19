---
type: Architecture
title: ELT — architecture — Engine code, design scope and how a file becomes Tables
description: Part 2 of 3 of ELT — architecture: Where the engine's code lives; Design Philosophy & Scope; How a file becomes Tables
tags: [elt, acquisition, phases, dedup, watermark, stability, ledger, multi-location, composition, pattern, module, core, composition-root, inspecto-processor, engine, architecture, stage1, multiplexer, ingest, layout, pipeline-graph, lift, node-types, derived, tokens, sinks, consignment, observability, audit, provenance, quarantine, status]
timestamp: 2026-11-18T00:00:00Z  # merged 2026-11-18 from the elt, acquire and engine architecture pages; the multi-location ingest part carried type Practice (2026-08-25)
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# ELT — architecture — Engine code, design scope and how a file becomes Tables

Part 2 of 3 of [ELT — architecture](architecture-elt.md) · previous: [Acquisition, Collectors, parsing and Pipelines](architecture-elt-acquisition-parsing.md) · next: [The Pipeline graph, consignment status and directory layout](architecture-elt-graph-consignments.md).

## E1. Where the engine's code lives

The module layout, the package map and the packaging model are owned by the
[platform architecture](../../layer/platform/architecture-platform.md) (§3 *Shape of the system*, *Where the packages
are*, §4 *Packaging*) and the [module taxonomy](../../layer/platform/architecture-modules.md); this page
does not restate them. The ELT code sits in three platform modules:

| Module | ELT packages under `com.gamma` | Covered here |
|---|---|---|
| `platform/inspecto-acquire` | `acquire` — connectors contract, ledger, stability gate, retry | [Collectors](architecture-elt-acquisition-parsing.md#collectors), [spec-acquisition.md](spec-acquisition.md) |
| `platform/inspecto-etl` | `etl` — the Stage-1 config model (`PipelineConfig`), ingesters, batch planning, quarantine, lineage, partitioned output, `ConsignmentEventBus` | [Parsing and ingest](architecture-elt-acquisition-parsing.md#parsing-and-ingest), [spec-parsing.md](spec-parsing.md) |
| `platform/inspecto-engine` | `inspector` (Collector poll cycle, ingest strategies, the command-line entry points `CollectorProcessor` and `MainApp`), `pipeline` (Pipeline graph, component registry, executor), `consignment`, `parse`, `ingester`, `enrich`, `job`, and the rest of the engine | [§E4](architecture-elt-graph-consignments.md#e4-the-pipeline-graph--the-backend-model), [spec-pipeline-model.md](spec-pipeline-model.md) |

Two corrections to older text, verified against the code on 2026-11-18: `com.gamma.ops` (Incidents,
Cases) is the optional `features/inspecto-ops` module, not the engine; and there is no single fat jar —
a bundle is thin first-party jars plus `inspecto.jar` (the processor and third-party code; the `inspecto/`
shade plugin excludes the first-party modules), on a `jlink` runtime. Build and run:
[build & test](../../layer/platform/spec-build-test.md).

### Ingest package structure

```
com.gamma
  inspector/
    CollectorProcessor          — single-Pipeline ELT runner; virtual threads + the shared ConcurrencyBroker (batch fan-out)
    MultiCollectorProcessor     — runs many Pipelines concurrently in one JVM (outer M..N orchestrator)
    ConsignmentIngestor           — thin per-batch coordinator: selects a ConsignmentIngestStrategy, then drives the shared commit → audit tail
    ConsignmentIngestStrategy      — ingest+transform+write seam (delimited-text vs plugin); returns a typed IngestOutcome (+ shared dropTable/msg helpers)
    CsvIngestStrategy         — built-in delimited-text path → transform → write → lineage. Native (read_csv) batches stream with
                               NO raw_f/raw_input table copies: single member via one read_csv VIEW, many members via
                               per-member views UNION ALL-ed into one transform (materialised once). Files over
                               processing.chunking.max_file_bytes are streamed in bounded chunks (FileChunker). The Java
                               parse engine keeps the per-file temp table → raw_input(__src_id) staging path.
    FileChunker              — streams an oversized file into bounded, header-replicating chunks (one on disk at a time)
    StreamingPluginIngestStrategy — the plugin path (StreamingFileIngester): per batch, picks union mode (many small
                               files → emit into per-member tables → union per segment → one transform/write/lineage) or
                               generation mode (huge file → DuckDbRecordSink flushes bounded generations) by file size
    DuckDbRecordSink         — framework RecordSink impl: Appender-loads emitted rows to DuckDB; generation-flushes on a
                               row budget, or (union mode) leaves raw tables for the strategy to union
    IngestOutcome            — record: status, survivors, outputs, lineage, per-member audit, totals (commit/audit input)
    MemberAudit              — record: per-input-file audit row accumulated during ingest
    ReprocessCommand         — `POST /runs/{pipeline}/reprocess`: delete outputs/markers, restore members, re-run
  etl/
    PipelineConfig           — immutable config object; static factory loads and validates .toon
    SchemaSelector           — two-pass schema dispatch (file-pattern fast path + column-count probe)
    CsvIngester              — Java line-by-line CSV/CSV.GZ parser → DuckDB staging table (fallback engine)
    DuckDbCsvIngester        — native vectorized read_csv ingest (4-5× faster; default for clean configs)
    DataTransformer          — assembles the transform SELECT (CREATE TABLE AS); delegates per-column SQL to TransformCompiler
    TransformCompiler        — pure per-column SQL compiler; transformType → expression function registry (functional injection)
    PartitionWriter          — COPY … PARTITION_BY + two-step atomic file reveal; format via the OutputFormat enum-strategy
    OutputFormat             — enum-as-strategy: extension + COPY token + compression applicability per output format
    MarkerManager            — .processed sentinel files for idempotent ingest; retention-based cleanup
    QuarantineManager        — moves zero-valid-row and unreadable files to quarantine/
    DuckLakeRegistrar        — optional: registers written Parquet files into a DuckLake catalog
    ConsignmentAuditWriter         — thread-safe append to the per-run status, batches, and lineage CSVs
    ConsignmentPlanner             — groups polled files into batches respecting max_files / max_bytes limits
    ManifestStore            — writes and reads per-batch JSON manifests under status_dir/manifests/
    LineageCollector         — tracks input-to-output row counts for the lineage CSV; dynamic partition paths
    IngestResult             — record: parsedRows, errorRows, junkCandidateRows
    PartitionOutput          — record: output paths and sizes produced by one batch
    StreamingFileIngester    — the plugin SPI for custom parsers; ingest() emits records into a RecordSink
    RecordSink               — framework callback the ingester emits into (define/emit/reject/junk)
    PartitionDef             — record + enum for explicit partitions[] declarations; backward-compat fromSchema()
  ingester/
    TypedRecordIngester      — reference StreamingFileIngester for type-tagged text records; multi-segment dispatch
  util/
    ToonHelper               — load/validate .toon files; require/opt section helpers
    TarUtil                  — deleteTree
    SqlBuilder               — typed DuckDB SELECT expressions (COALESCE/TRY_STRPTIME chains)
    LogSetup                 — TeeOutputStream; per-run timestamped log file wired to stdout/stderr
    DuckDbUtil               — JDBC URL builder; temp DB file lifecycle; datetime formatter
```

**Design principle:** `CollectorProcessor` is a pure orchestrator — it creates the thread pool and drives the per-batch lifecycle, but contains zero business logic itself. Every concern is owned by a focused single-responsibility class: batch planning (`ConsignmentPlanner`), parsing (`CsvIngester`), transformation (`DataTransformer`), deduplication (`MarkerManager`), quarantine (`QuarantineManager`), registration (`DuckLakeRegistrar`), and auditing (`ConsignmentAuditWriter`, `ManifestStore`, `LineageCollector`). All shared low-level helpers live in `com.gamma.util` and are reused across the engine.

**Behavior-injection seams.** Variant behavior is injected into the engine rather than branched inline, so the orchestration code stays thin and a new variant is a closed-set edit:

- **`ConsignmentIngestStrategy`** (`CsvIngestStrategy` / `StreamingPluginIngestStrategy`) — the per-batch ingest+transform+write path. `ConsignmentIngestor.process` selects one by config (the built-in delimited-text path when no `ingester`, else the streaming plugin engine) and consumes its typed `IngestOutcome`; the shared commit → audit tail is path-agnostic. The plugin engine then self-selects union vs generation mode per batch by file size.
- **`StreamingFileIngester`** (SPI, by FQCN) — custom parsers emit records into a `RecordSink`; the reference `TypedRecordIngester` splits one input into many typed segment streams ([spec-parsing.md](spec-parsing.md)).
- **`TransformCompiler`** — a `transformType → ColumnRule` function registry; `DataTransformer` assembles the SELECT and delegates each column expression.
- **`OutputFormat`** — enum-as-strategy owning each format's extension, COPY token, and compression rule, used by `PartitionWriter`.
- **`StatusStore`** (`FileStatusStore` / `DbStatusStore`) and the **`ConsignmentEventBus`** (`Consumer<ConsignmentEvent>` observers) — pluggable audit backend and commit-event fan-out.

These keep the data path lean while making formats, ingest paths, transforms, and audit sinks independently extensible and testable. See [design-notes → D7](../../../../archived-documents/design-notes.md#d7--engine-modularity-pass-behavior-injection-seams--done-v390).

---

## E2. Design Philosophy & Scope

> **Scope note.** This section describes **Stage-1**: the M..N multiplexer ingest engine
> (`com.gamma.etl` + `com.gamma.inspector`) — still the heart of the data plane. Around it the platform
> has since grown the **Stage-2 enrichment engine** (`com.gamma.enrich`, 2.x), which deliberately *does*
> the joins/aggregations listed below as Stage-1 non-goals; the service + control plane; **authored
> Pipelines** (`com.gamma.pipeline` — [§E4](architecture-elt-graph-consignments.md#e4-the-pipeline-graph--the-backend-model)); multi-space tenancy;
> the Component metamodel; editions as build flavors; and the optional assist agent. The package-level
> **layer map** lives in [`architecture-platform.md`](../../layer/platform/architecture-platform.md) (layer/platform). The
> non-goals below are **still correct for Stage-1** — they are what keep each batch embarrassingly
> parallel and crash-isolated.

This is a deliberately small ELT engine built around one idea: an **M..N
multiplexer**. A batch of **M** input files is demultiplexed and routed into
**N** partitioned output files — explicitly *not* one-to-one. Understanding this
framing explains every design choice that follows, and the non-goals are as
important as the goals.

### The M..N multiplexer

Records flow from many inputs to many outputs; the input file count and the
output file count are decoupled. Two complementary mechanisms do the routing:

- **Partition fan-out (the "N").** Every surviving record is routed to an output
  file by its partition key — typically `year/month/day` derived from a date
  column, optionally prefixed by other columns (e.g. `event_type`). A batch of
  M files spanning 30 days produces ~30 partition files per segment, regardless
  of how the input was split across files.
- **Segment demultiplexing (plugin path).** A single input file can carry
  multiple record types (e.g. CALL + SMS interleaved in one CDR file). The
  [plugin ingester](spec-ingest-plugin-ingester.md#plugin-ingester) splits these into independent typed
  streams, each with its own schema and its own partitioned output tree. One
  input → many typed outputs.

Combined: **M input files → unioned per record type → N partitioned outputs.**
The batch is the unit of work; partition key and segment type are the routing
keys.

### Minor, per-record transformations only

Transformations are **stateless and applied to each record independently** —
the kind of work that maps cleanly onto DuckDB's vectorized SQL:

- Type coercion — VARCHAR → `DATE` / `TIMESTAMP` / `DOUBLE` / `INTEGER`
- Column selection and renaming (raw `selector` index → target column)
- Partition-key derivation (`YEAR()` / `MONTH()` / `DAY()` from a date)
- Lightweight composition — `CONCAT_DT` (date + time → timestamp),
  `FILENAME_DATE` (extract a date embedded in the filename)

That's the whole transformation vocabulary. It's intentionally narrow.

### Deliberate non-goals (Stage-1)

The **Stage-1 ingest engine does not** — by design, not by omission (Stage-2 enrichment
does these; see the scope note above):

- **Join against external / reference data.** No dimension lookups, no
  enrichment from a second data origin, no foreign-key resolution.
- **Aggregate across records.** No `GROUP BY` rollups, no windowing, no dedup
  across rows.
- **Hold state across records or batches.** Each record is transformed in
  isolation; each batch is independent.

> ⚠ **Read the list above as the *rule*, not as a description of every line of code.** It was audited
> against the implementation on 2026-08-11 and three things were found crossing it. One has been removed;
> two are deliberate and remain. Recording them here because the gap between this list and the code is
> what made the boundary hard to reason about when deciding where the pipeline `steps:` chain executes.
> **That question is now answered — see [*The Stage-2 transform chain, at rest*](#the-stage-2-transform-chain-at-rest-shipped-2026-08-11) below.**
>
> - ✅ **Record dedup (`processing.dedup`) used to run here** — a `ROW_NUMBER()` QUALIFY in
>   `BatchIngestStrategy` (now `ConsignmentIngestStrategy`), the one genuine cross-record operation in the multiplexer. **Removed
>   2026-08-11**: dedup is a transform concern, so in ELT terms it belongs in the T, not the EL.
>   The key still parses, lifts and round-trips; `prepare()` now **refuses to arm** a pipeline carrying
>   it, exactly as it does for `summarize`, `join` and `route`. Stage-2 executes it.
> - ⚠ **Two `GROUP BY` aggregates remain, and they are load-bearing.** `LineageCollector` runs
>   `SELECT __src_id, <partCols>, COUNT(*) … GROUP BY` and `ConsignmentOutputs` runs
>   `min()/max(__event_time) … GROUP BY` per write. An earlier version of this page called lineage
>   "audit metadata, not output data" — **that is not accurate**: `ConsignmentOutputs.fromLineage` turns
>   those counts into durable output-registry rows.
> - ⚠ **Reference versioning holds state across batches.** For `produces: reference` with
>   `load: upsert|scd2`, `stampReferenceVersions` reads the store's *existing* on-disk versions and skips
>   rows identical to the current one.
>
> What actually unifies the three survivors — and the test to apply to anything proposed for Stage-1 — is
> **batch independence**, not statelessness per record. Lineage and bounds are within-batch; reference
> versioning touches only its own store's history. None of them requires shared state across concurrently
> running batches, which is the property the non-goals exist to protect.

### Why the non-goals matter

These constraints are what make the engine fast, parallel, and crash-isolated.
Because no batch needs shared reference state, every batch is an embarrassingly
parallel, single-pass unit with its own DuckDB connection (see
[Batch Processing](../../layer/platform/admin-guide-operations-reference-batch-processing.md#batch-processing)). Adding joins or lookups would force
shared state, serialize the batch model, and break the clean M..N routing. If
you need heavy joins or enrichment, do it **downstream** — query the
Hive-partitioned Parquet output through the
[warehouse query layer](../../layer/platform/api-integrations-ducklake.md#warehouse-query-layer--dbeaver-via-pg_duckdb) or a
DuckLake catalog, where a real SQL engine can join across the whole dataset. The
multiplexer's job ends at partition-and-write.

Since the 2.x line, that downstream answer is **first-class and in-platform**: the
**Stage-2 enrichment engine** (`com.gamma.enrich`) reads the Hive-partitioned output as
views and runs exactly these joins/aggregations as its own `transform` SQL, idempotently and
incrementally (event- or schedule-driven), under the same service/control plane. So "do it
downstream" no longer means "leave the platform" — Stage-1 stays a clean multiplexer, and
Stage-2 owns the cross-record work. See [v3-architecture.md](../../../../archived-documents/v3-architecture.md) for the
two-stage map.

### Format-specific configuration

Because different file formats need different handling, configuration is
organized **per format** rather than forced into one shape — and that
divergence is intended. Delimited text uses `csv_settings`; binary/proprietary
formats use a plugin `ingester` plus `ingester_config`. See
[Configuration by source format](../config/admin-guide-configuration-reference-spaces.md#configuration-by-source-format) for the map,
and [spec-parsing.md](spec-parsing.md) for the parse model.

---

## E3. How a file becomes Tables

```
┌──────────────── STEP 1: Bootstrap (once per Pipeline) ────────────────────────┐
│                                                                               │
│   sample file ──►  Add data ──► <name>_schema.toon                            │
│                             └──► <name>_pipeline.toon                         │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 2: ELT Processing (continuous) ──────────────────────────┐
│                                                                               │
│   <name>_pipeline.toon ───┐                                                   │
│   <name>_schema.toon ─────┼──► CollectorProcessor ──┬──►  Parquet / CSV       │
│   inbox/<date>/*.csv.gz ──┘                         └──►  DuckLake (optional) │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 3: Analytics Query Layer (optional) ─────────────────────┐
│                                                                               │
│   warehouse_setup.sql ──► pg_duckdb extension ──► PostgreSQL :5432            │
│                                    │                      ▲                   │
│   database/**/*.parquet ───────────┘                      │                   │
│                                                           │                   │
│   DBeaver / any PG client ────────────────────────────────┘                   │
│   (standard PostgreSQL driver; DuckDB transparent to users)                   │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

### Step 1 — Schema bootstrapping (run once)

Onboard a sample file with **Add data**: it auto-infers column types from the sample and writes:
- `<name>_schema.toon` — field definitions and mapping rules (the ELT source of truth)
- `<name>_pipeline.toon` — runtime directories, threading, output format

These generated files are committed to version control and edited as needed. **Schema inference is never involved in production processing.**
(The `ura` pre-ELT staging utilities and their `create-schema` command were retired 2026-11-06; Collectors bring files in.)

### Step 2 — ELT processing (continuous)

```
CollectorProcessor  <pipeline_config.toon>
```

**CollectorProcessor** is the runtime engine — it reads both generated config files, polls the inbox
directory, and for each unprocessed file:
1. Ingests the file into a per-worker DuckDB staging table (the built-in delimited-text reader, or a custom plugin ingester)
2. Applies typed SQL transformations via DuckDB
3. Writes partitioned output (Parquet or CSV)
4. Optionally registers output into DuckLake
5. Writes a `.processed` marker and updates the status log

How Consignments are planned, ingested and committed is [spec-ingest.md](spec-ingest.md).

### The Stage-2 transform chain, at rest (shipped 2026-08-11)

The boundary audit above left one question open: **where does a flat pipeline's transform chain
execute**, given that `dedup`/`summarize`/`join` were all removed from — or never ran on — the linear
ingest path? Answer: as a **Pipeline Job over the store Step 2 landed**, never inside the EL.

A flat `*_pipeline.toon` declares its chain and where the shaped result rests:

```
name: orders_etl
output_store: orders_shaped        # authored, never derived — the store the chain writes
steps[2]:
  - dedup:
      keys[1]: ORDER_ID
  - summarize:
      group_by[1]: ORDER_DAY
      measures[1]: count
```

and a job runs it:

```
job:
  name: orders_shape
  type: pipeline
  pipeline_config: config/orders_pipeline.toon    # lifted at run time — the flat file stays the truth
  on_pipeline: orders_etl                          # each ingest commit shapes the new rows
```

`PipelineLift.stageTwo` projects the chain into `source_store(landed) → chain → sink.persistent(output_store)`;
`PipelineJobRunner` executes it through the production `PipelineExecutor`/`RowShaper`. A `transform.join`
resolves its Reference Dataset through the shared `ReferenceReader` — the same resolution the Stage-2
enrichment uses, so a versioned reference store's current/as-of view is derived identically on both routes.

🔴 **The job is not optional, and nothing runs the chain without it.** `output_store:` arms the chain on
the *declared promise* that a `pipeline_config:` job exists; it does not create one. Author the chain,
skip the job, and the pipeline ingests forever while the transform never runs — the one failure this
split makes possible.

Two things close that gap, both as of 2026-09-01:

- **`pipeline_config` is a declared parameter of the `pipeline` Job Type**, so the generated Jobs form can
  author it. ⚠ It and `pipeline:` are the two graph sources — **mutually exclusive, exactly one
  required** — a constraint no single declaration expresses, so **both are optional** in the descriptor and
  `PipelineJobRunner` enforces "pick one" at run time. ⛔ Do not restore `required` on `pipeline`: that
  made the at-rest job unrepresentable (the contract demanded a key this path must not carry), and
  `JobServiceTest.thePipelineTypeDeclaresBothGraphSourcesAndRequiresNeither` now pins it.
- **The orphan check is DEFAULT-ON in every space** (2026-09-01, fail-closed fix): the scan
  (`SchedulerAuditTask.orphanOutputStoreFindings`, extracted — one statement, two hosts) runs from
  `JobService.auditOrphanOutputStores()` after every registry rebuild and on job upsert/remove,
  emitting one `maintenance.scheduler.findings` WARN signal per orphan **transition** (never per
  cycle; resolved-then-regressed re-emits). Kill switch `-Djobs.orphan.audit=false`. The
  `scheduler_audit` maintenance job remains the richer on-demand audit
  (`spaces/demo/config/jobs/scheduler_audit_job.toon`). ⚠ History: until 2026-09-01 the check fired
  ONLY from that job and no shipped space but `demo` had one — a fail-open gate; a stock space had
  no orphan detection at all ("the one failure this split makes possible"). A Job covers a Pipeline when
  its `pipeline_config:`, resolved as the runner resolves it, names a file whose registered id is that
  Pipeline's (`PipelineConfigIds.registeredIdOf`, the save gate's rule too); until 2026-11-15 it was the
  file name less `_pipeline.toon`, which flagged every `07-steps` sample (`SCHEDULER-AUDIT-PIPELINE-NAME-1`).

**`output_store:` is the arming condition.** `prepare()` refuses to arm a pipeline carrying
`summarize`/`dedup`/`join`/`steps:` *unless* `output_store:` is authored — with it, the file itself
declares the EL/T split; without it the keys have no execution route and arming would silently run
neither. ⚠ An absolute refusal was the original design and was **wrong**: it would have meant Stage 1
could never land the store the chain reads, making the route unreachable.

⛔ **Not covered by this route**: ingest-side `route:` demux —
one `output_store` cannot name N branch destinations, so a `route` step refuses here too — and
multi-destination `sinks:` arming. ⚠ **Refused at rest as well:** a *legacy* (pre-map) filter, whose
predicate speaks raw-column vocabulary the landed store no longer has, and any filter carrying pre-parse
include/exclude keys. An explicit `steps:` filter is post-map and runs fine.

---
