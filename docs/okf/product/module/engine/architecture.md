---
type: Architecture
title: Execution engine — architecture
description: How the execution engine is built — where its code lives since the WS-D split, the M..N multiplexer ingest design and its non-goals, the Pipeline graph backend model (derived graph, token rule, lift, validator, commit, triggers, back-pressure, provenance), the Consignment status flow, graceful Space stop and the Space directory layout.
resource: inspecto/, platform/inspecto-engine/src/main/java/com/gamma/pipeline, platform/inspecto-etl/src/main/java/com/gamma/etl, platform/inspecto-etl/src/main/java/com/gamma/etl/ConsignmentAuditWriter.java
tags: [module, core, composition-root, inspecto-processor, engine, architecture, stage1, multiplexer, ingest, layout, pipeline-graph, lift, node-types, derived, tokens, sinks, consignment, observability, audit, provenance, quarantine, status]
timestamp: 2026-11-18T00:00:00Z
---

# Execution engine — architecture

**What this covers.** The engine is the part of Inspecto that turns arriving files into partitioned
Tables and runs authored Pipelines over them. This page explains how it is put together: which modules
hold the code, the "many files in, many partitioned files out" ingest design and what it deliberately
does not do, how a Pipeline is modelled as a graph that is derived from its config file, what is
recorded about each Consignment as it moves (so an operator can audit a failed file or record), how a
Space stops cleanly, and how a Space's files are laid out on disk.

**Contents**

1. [Where the engine's code lives](#1-where-the-engines-code-lives)
2. [Design Philosophy & Scope](#design-philosophy--scope) — the M..N multiplexer ingest engine
3. [How a file becomes Tables](#3-how-a-file-becomes-tables) — bootstrap, continuous processing, the at-rest transform chain
4. [The Pipeline graph — the backend model](#4-the-pipeline-graph--the-backend-model)
5. [Consignment status flow (the flowfile question)](#5-consignment-status-flow-the-flowfile-question)
6. [Stopping a Space (graceful stop)](#6-stopping-a-space-graceful-stop)
7. [Directory layout](#7-directory-layout)
8. [References](#references)

---

## 1. Where the engine's code lives

### Core Module (`inspecto/`)

The **composition root** (artifactId `inspecto-processor`, jar `inspecto.jar`) — the module that wires
the object graph together and shades the single deployable fat JAR. It keeps **zero network/AI
dependencies**: remote protocols live in [connectors](../connectors/architecture.md), hosted models in
[agent-hosted](../agent-hosted/architecture.md).

> **Not the engine anymore.** Before the WS-D reactor split (2026-07-22) `inspecto/` held the whole
> engine; the engine was then extracted into the sibling modules below, and `inspecto/` now depends
> *down* on them. Authoritative build order, version management, and the extraction playbook live in
> [../../layer/platform/architecture-modules.md](../../layer/platform/architecture-modules.md).

What lives here now — `inspecto/src/main/java/com/gamma/`: `service` (the always-on host — `CollectorService`, the
[control API](../processor/api-control-api.md) wiring, [`SpaceManager`](../processor/architecture.md)),
`control` (the ~50 HTTP routes), `report`, `assist` (the assist SPI — [interface only](../agent/spec-assist-agent.md)),
`exchange`, `expectation`, `intelligence`, and `model`.

### Extracted engine modules (WS-D) — `inspecto/` depends down on these

The concept docs are unchanged by the move; only the home module changed:

- **`inspecto-engine`** (`inspecto-engine`) — the engine cluster: `signal`, `query`,
  [`pipeline` / pipeline-graph](#4-the-pipeline-graph--the-backend-model), `inspector` ([DuckDB](spec-duckdb.md) sinks
  plus the fat-jar entry points `CollectorProcessor` and `MainApp`), `ingester`, `ops`,
  [`job`](../processor/spec-jobs.md), `enrich`, `alert`, `notify`, `catalog`.
- **`inspecto-etl`** (`inspecto-etl`) — [`etl`](spec-ingest.md): `PipelineConfig`, the
  ingesters, batch planning, quarantine, lineage, [partitioned Parquet output](spec-sinks.md).
- **`inspecto-event`** (`inspecto-event`) — [`event` + `metrics`](../event/spec-events-metrics.md):
  the Operational-Intelligence event store + metric registry; owns `logback.xml`.
- **`inspecto-acquire`** (`inspecto-acquire`) — the [acquisition framework](../acquire/architecture.md):
  connectors, connection profiles/registry/workbench, the fingerprint ledger, stability gate,
  retry/circuit-breaker/rate-limit policies.
- Foundation leaves **`inspecto-api`** (`@PublicApi`), **`inspecto-util`** (DuckDB access + CSV/file/tar
  helpers), **`inspecto-config`** ([TOON config](../config/spec-toon-config.md) — spec/io/safety), and
  **`inspecto-sql`** (the sandboxed read-only DuckDB SQL surface).

Modularization is reactor-internal — the single `inspecto.jar` is unchanged (shade has no
include-list, so the sibling modules' classes and resources bundle automatically).

Build/run: see [build & test](../../layer/platform/spec-build-test.md).

### Ingest package structure

```
com.gamma
  inspector/
    CollectorProcessor          — single-Pipeline ETL runner; virtual-thread + semaphore batch fan-out
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
- **`StreamingFileIngester`** (SPI, by FQCN) — custom parsers emit records into a `RecordSink`; the reference `TypedRecordIngester` splits one input into many typed segment streams ([spec-ingest.md](spec-ingest.md)).
- **`TransformCompiler`** — a `transformType → ColumnRule` function registry; `DataTransformer` assembles the SELECT and delegates each column expression.
- **`OutputFormat`** — enum-as-strategy owning each format's extension, COPY token, and compression rule, used by `PartitionWriter`.
- **`StatusStore`** (`FileStatusStore` / `DbStatusStore`) and the **`ConsignmentEventBus`** (`Consumer<ConsignmentEvent>` observers) — pluggable audit backend and commit-event fan-out.

These keep the data path lean while making formats, ingest paths, transforms, and audit sinks independently extensible and testable. See [design-notes → D7](../../../../archived-documents/design-notes.md#d7--engine-modularity-pass-behavior-injection-seams--done-v390).

---

## Design Philosophy & Scope

> **Scope note.** This section describes **Stage-1**: the M..N multiplexer ingest engine
> (`com.gamma.etl` + `com.gamma.inspector`) — still the heart of the data plane. Around it the platform
> has since grown the **Stage-2 enrichment engine** (`com.gamma.enrich`, 2.x), which deliberately *does*
> the joins/aggregations listed below as Stage-1 non-goals; the service + control plane; **authored
> Pipelines** (`com.gamma.pipeline` — [§4](#4-the-pipeline-graph--the-backend-model)); multi-space tenancy;
> the Component metamodel; editions as build flavors; and the optional assist agent. The package-level
> **layer map** lives in [`architecture.md`](../../layer/platform/architecture.md) (layer/platform). The
> non-goals below are **still correct for Stage-1** — they are what keep each batch embarrassingly
> parallel and crash-isolated.

This is a deliberately small ETL engine built around one idea: an **M..N
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
  [plugin ingester](spec-ingest.md#plugin-ingester) splits these into independent typed
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
[Batch Processing](../../layer/platform/admin-guide-operations-reference.md#batch-processing)). Adding joins or lookups would force
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
[Configuration by source format](../config/admin-guide-configuration-reference.md#configuration-by-source-format) for the map,
and [spec-parsing.md](spec-parsing.md) for the parse model.

---

## 3. How a file becomes Tables

```
┌──────────────── STEP 1: Bootstrap (once per Pipeline) ────────────────────────┐
│                                                                               │
│   sample file ──►  Add data ──► <name>_schema.toon                            │
│                             └──► <name>_pipeline.toon                         │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 2: ETL Processing (continuous) ──────────────────────────┐
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
- `<name>_schema.toon` — field definitions and mapping rules (the ETL source of truth)
- `<name>_pipeline.toon` — runtime directories, threading, output format

These generated files are committed to version control and edited as needed. **Schema inference is never involved in production processing.**
(The `ura` pre-ETL staging utilities and their `create-schema` command were retired 2026-11-06; Collectors bring files in.)

### Step 2 — ETL processing (continuous)

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

## 4. The Pipeline graph — the backend model

> **Scope (2026-09-01 consolidation):** this section owns the **backend Pipeline model** — the IR,
> the lift, the validator, the execution model and its decisions of record. Its siblings own the
> rest: [spec-pipeline-model.md](spec-pipeline-model.md) (the type registry + execution SPI, the token
> model, and the authoring round-trip — refusal codes, parser family, settings, dry-run),
> [spec-pipeline-execution.md](spec-pipeline-execution.md) (which lanes run a pipeline),
> [../config/spec-pipeline-config-keys.md](../config/spec-pipeline-config-keys.md) (the config-key census),
> [../etl/user-guide.md](../etl/user-guide.md) (the Pipeline editor UI). The plan of record is
> [`archived-documents/plans-archive/pipeline-spec.md`](../../../../archived-documents/plans-archive/pipeline-spec.md). The 2026-06 design-era
> prose (motivation, roadmap, T-checklist, capability gate) is archived verbatim at
> [`plans-archive/pipeline-graph-design-era-2026-06.md`](../../../../archived-documents/plans-archive/pipeline-graph-design-era-2026-06.md).

### 4.1 The model in one paragraph — and the token rule

A Pipeline is authored and stored as the flat `<name>_pipeline.toon`; the **graph is DERIVED, not
stored**. `PipelineLift` lifts the file (plus its referenced `*_schema.toon`) into the IR —
`PipelineGraph(name, active, nodes, edges)`, `PipelineNode(id, type, name, description, cfg, use,
enabled)`, `PipelineEdge(from, rel, to)` — which the validator, the projections, and the editor
consume. Saving reverses it (`PipelineEditable.lower`, [spec-pipeline-model.md](spec-pipeline-model.md)).

🔴 **Edges are wiring, not data carriers (adopted 2026-08-30, spec §13 D2).** A Step receives a
**Consignment token** and resolves data **by reference** (`ProcessorContext.outputs()`/`read()`);
no rows travel an edge, `transform.map` is never executed as a node at all, and six of the ten
declared `PipelineRel` relations never appear in a lifted graph. Documentation is written in this
token vocabulary now; the remaining runtime pieces converge with the amendment plan's Phase 7 — the
corrected model and its migration are [spec-pipeline-model.md](spec-pipeline-model.md) § *The token model*.
Everything else here — IR, lift, validator, registry, commit model — stands as built.

**The three outlets (the token vocabulary, and what the constants below mean).** `data` — the token
continues (the token model wrote `main`; X5 decision D4 keeps `data`) · `reject:<reason>` — one reject kind
carrying its reason · `route:<key>` — content demux. **As built in 4.0.0 (X5-a)** — the history below explains
why; the membership of record is [spec-pipeline-model.md](spec-pipeline-model.md) § *The token model*.

🔴 **The ten `PipelineRel` constants are NOT one vocabulary, and the old control/split grouping is the
conflation being removed.** They split **four and four** around the edge itself:

* `data` — the edge; the token continues (default when an edge omits `rel`).
* **Four were Signals, not edges at all** — `success` · `failure` · `on_commit` · `gap`. The first three were
  **retired in 4.0.0** (no producer; an authored edge naming one is dropped on decode with a `RETIRED_RELATION`
  warning); `gap` stays an edge to its CONTROL node.
* **Four are one reject kind differing only in reason** — `UNMATCHED` · `DROPPED` · `INVALID` · `DUPLICATE`,
  whose values are `reject:unmatched` … `reject:duplicate` since 4.0.0; `PipelineRel.canonical` reads the old
  bare spellings (authored graphs, stored provenance).
* `route:*` — operator-named content demux; survives the token model unchanged.

⛔ **`unmatched` is a reject, not a control relationship.** An earlier revision of this line grouped it
with `success`/`failure`/`gap`/`on_commit` as a five-member "control set" against a three-member
"diverted set"; that is the 5/3 misgrouping the design of record corrected to 4/4 on 2026-09-13. Do not
reintroduce it.

⚠ Two SEPARATE properties, easy to merge and wrong to: "is not a Signal" and "is not lifted".
`PipelineLift` builds only `data`, `reject:unmatched`, `gap` and `route:*` — so `reject:dropped`/`reject:invalid`/
`reject:duplicate` are real at runtime yet never drawn as edges.

### 4.2 Two graphs — keep them distinct

The **lineage graph** (`com.gamma.catalog.MetadataGraphService`) is a derived projection of configs
answering "where did this column come from". The **pipeline graph** (`com.gamma.pipeline`) is the
derived topology answering "what runs, in what order, where do outcomes go". The pipeline graph
compiles down into the lineage graph (a sink's store yields the table node), and both render
through the same G6 component with different projections. There is a third, parallel concern — the
**provenance data plane** ([4.9](#49-the-provenance-data-plane-shipped)): quantities painted onto the pipeline graph's edges for one concrete
run.

### 4.3 Node types, categories, and the sink family

The registry and its execution SPI are owned by [spec-pipeline-model.md](spec-pipeline-model.md):
`PipelineNodeTypes` layers `BuiltinNodeType` with `ServiceLoader<PipelineNodeType>` providers,
`PipelineNodeExecutor` is the execution half (shipped 2026-08-29), and node-type **packs**
hot-load through an owner-keyed overlay (2026-08-31). Facts that shape the model:

- Every type declares a `category` (`SOURCE`/`PARSE`/`TRANSFORM`/`SINK`/`CONTROL`), a UI
  `label`/`description`, and its `emits`/`accepts` relations (+ `emitsNamedRoutes()` for `route`).
  Grouping code asks **category, not type string** (`PipelineNodeTypes.isCategory`) — the sink and
  parser families extend without edits there.
- **Sink is a family**: `sink.persistent` (rests bytes), `sink.materialized` (per-batch upsert
  rollup), `sink.view` (nothing rests — a logical store consumers bind to; carries a
  `derived_sql`). `PipelineStores.producedStores(...).restsOnDisk()` is the persistence flag the
  **deletion fence** reads: only a resting store can be a deletion hazard; a view never
  participates. A legacy `*_pipeline.toon` only ever lifts `sink.persistent`;
  `materialized`/`view` are authored-only.
- **Parse is a family** too — `parser` plus `parser.<frontend>` subtypes; retype/lower rules live
  in [spec-pipeline-model.md](spec-pipeline-model.md) (the parser family).
- Every node carries a user `name`/`description`; lifted nodes get derived defaults (a lifted sink
  is named after its store), and `PipelineLift` deliberately does NOT stamp type labels as names.

### 4.4 The lift — what the flat file encodes (capability table, as currently true)

`PipelineLift.lift(PipelineConfig)` reads the pipeline file **and its referenced schema file(s)**
(the transform vocabulary, field selectors and partitions live in `*_schema.toon`, not the pipeline
file). Encodings of record — the 2026-06 G-table, updated to what is true today:

| Capability | Encoding today |
|---|---|
| CSV row-filters (`filter_target_column` + include/exclude) | a `transform.filter` node between parser and map — index-anchored, pre-naming. `PipelineLift` EMITS this type for any pipeline with row filters, so refusing it in `lower` would break open-then-save. |
| File-grain dedup | **both subsystems ride the `acquisition` Step** (fingerprint policy `collector.duplicate.*` since 2026-08-04; marker dedup `processing.duplicate_check` + `dirs.markers` since P5 2026-08-16, single rule statement `PipelineLift.markerHome`). ⚠ The design-era encoding (distinct `transform.dedup.marker`/`.fingerprint` nodes) is RETIRED: the fingerprint node was removed 2026-08-04 (dedup executes in the `CollectorProcessor` poll cycle, not a transform), and `transform.dedup.marker` is read-compat only — accepted, never emitted. |
| Multi-schema `schemas[]` selector | a `parser` dispatcher emitting `route:<key>` edges carrying `{priority, file_pattern, column_count}`; first-match-wins preserved; `reject:unmatched` → quarantine. |
| Plugin-ingester `segments` | `parser` + ingester binding, emitting `route:<segment-key>` per segment table; fan-out is opaque to the graph (the plugin owns keys at runtime). |
| `incremental.watermark` | `acquisition.cfg.incremental` + the cross-field warn (watermark ⇒ a content-based `duplicate.mode`). |
| `post_action` | a **success-side finalizer on `acquisition`** (there is no `failure` edge; it was retired in 4.0.0); `on_unsupported` governs the failure branch; capability-checked against the connector SPI at run time. |
| Gap detection | `acquisition` emits `gap` → a `gap` CONTROL node. |
| Pipeline ↔ enrichment/job boundary | **two Pipelines/Jobs joined by declared store name** — each sink declares the store it produces, each consumer its `source_store`, and `PipelineStores.superimpose` derives the producer→consumer topology from configs alone. Never `on_pipeline` name-coupling. |
| Dead top-level keys (`version:`/`search:`/`copy_tars:`/`backup:`) | dropped by the lift — never reproduced. |
| The transform chain | two spellings, the FILE owns which (`steps:` vs the legacy singular blocks) — [spec-pipeline-model.md](spec-pipeline-model.md) (the round-trip). |

The lift retypes a parser subtype only on an EXPLICIT `parsing.frontend` (delimited is the implicit
default and must not retype deployed files on a read) — full rules in
[spec-pipeline-model.md](spec-pipeline-model.md) (the parser family).

### 4.5 The validator

`PipelineValidator.validate(g)` returns typed findings (`Issue` records — ERROR/WARNING, stable codes);
`validateOrThrow` gates execution, and the save path 422s on ERRORs. Checks: DAG over `data` edges
(`CYCLE`, names the path; control/`route:*` edges excluded, matching the walk), dangling endpoints
(no exemption since `on_commit` was retired in 4.0.0), duplicate ids, no-entry-node,
emit/accept wiring against the type contract (`ILLEGAL_EMIT`/`ILLEGAL_ACCEPT` — handlers needn't
list every inbound outcome; the emitter governs), **pairing** (`ILLEGAL_PAIRING` — an outcome or route
edge's target must accept that relation **or** accept `data`, deliberately so that anything consuming rows
may also consume a reject or a route stream; an unregistered plugin type is *warned*, not blocked),
and `UNKNOWN_TYPE` as a **warning** (nothing
rejects an unknown type harder than that — which is why an invented palette once survived unnoticed;
the UI contract tests now pin the served vocabulary). *(The pairing rule was distilled 2026-09-10 (Sprint 7.6)
from the three archived plans; this was their only home.)*

With a registry, `validate(g, ComponentRegistry)` adds `UNKNOWN_USE_REF` (ERROR) for a dangling
`use:` of a real component kind and `UNKNOWN_USE_KIND` for a bad kind (one typo must not read as two
faults). ⚠ Skipped when the space has no write root (the registry reads empty there and every
binding would look dangling). A `transform.join` is checked the same two-layered way since 2026-09-06:
`JOIN_REFERENCE_MISSING` / `JOIN_ON_MISSING` are config shape (engine, always), `UNKNOWN_JOIN_REFERENCE`
is existence (control plane, registry-gated — resolved through `ReferenceReader.parse` + the loaded
pipelines exactly as the run resolves it; a `path:` reference is syntax-checked only). ⚠ **Save-time resolution is not run-time resolution**:
`PipelineJobRunner` never calls `effectiveConfig` — a `use:` binding is resolved for preview/save
validation, not honoured by a run. That gap is recorded, not closed.

### 4.6 Execution model — commit, branches, and where things actually run

Which lane runs a pipeline (ingest flat, ingest graph-fork, at-rest job, scratch, parked) is owned
by [spec-pipeline-execution.md](spec-pipeline-execution.md); the branch-aware ingest fork by
[spec-ingest.md](spec-ingest.md) § *Branch-aware ingest*. Model facts:

- **The committable unit is `(batch, branch)`.** `BranchCommitLog` (durable, fsync-per-record,
  phase `BRANCH`/`SOURCE` = the partial-commit state) + `BranchCommitCoordinator`: commit
  per-branch, then source-finalisation (backup → **markers LAST** → ledger/watermark) gated on
  *all branches committed*, run exactly once, idempotent on replay. One healthy branch stays
  committed when a sibling fails; the failed branch retries/circuit-breaks independently; sink
  writes are idempotent (deterministic partition filenames). Cross-branch all-or-nothing is
  deliberately out of scope.
- **`PipelineExecutor`** (the at-rest/authored lane) validates, walks `data` edges topologically
  (Kahn), compiles transforms via `RowShaper` (multi-named-relation
  SQL: filter/validate/route case+clone/dedup/split/map/select/derive/merge — one `shape` per node in
  order; the `fuse()` chain-fusion was deleted 2026-09-06), routes each produced relation along its edge, and drives the
  coordinator at sinks. ⚠ Under the token rule ([4.1](#41-the-model-in-one-paragraph--and-the-token-rule)) this SQL-relation walk is the **at-rest lane's
  mechanism**, not the general edge semantics — the ingest lane diverts at `writeAndTrace`, and the
  runtime's remaining convergence on the token model lands with Phase 7.
- **Conservation invariant**: at a non-amplifying node `recordsIn = recordsOut + diverted +
  dropped`; at `route` clone / `split`, conservation is over records *accounted for* and the
  per-branch sum is a tracked amplification factor. An imbalance is silent data loss —
  `ConservationCheck` → `FLOW_CONSERVATION_IMBALANCE` event (LOSS/AMPLIFICATION) → alert.

### 4.7 Scheduling, triggers, and the two drivers

- **Entry nodes are scheduled; everything downstream is data-driven.** `PipelineTrigger` parses
  `schedule` (`every:`/`cron:`) / `event` (`on:`/`from:`/`coalesce:`) / `manual` / absent ⇒ the
  service poll interval (zero regression for legacy configs). `CollectorService.runAllOnce` gates
  each pipeline by its trigger; `EVENT` Pipelines fire from a bus subscriber off the publishing thread
  through a per-Pipeline `TriggerCoalescer` (an event storm collapses to one non-overlapping run);
  `MANUAL` runs only via the trigger endpoint. A Pipeline never overlaps itself (`PipelineRunGuard`).
- **Two drivers, one responsibility each** (the boundary rule's owner is
  [`control-plane/job-vs-step.md`](../processor/architecture.md)): the **loop scheduler** drives
  pipelines and owns ingest exclusively; the **job scheduler** (`JobService`, cron/event/manual)
  drives jobs over data at rest and never ingests (`JobType.INGEST` was deleted — an `ingest` job
  is a config error). The dividing line is **in-motion (pipeline) vs at-rest (job)**, not the
  operation's name. Stage B split the loop side into two coordinated timers (acquire → inbox →
  ingest, producer/consumer across the durable inbox, per-pipeline `acquireGuard`/`runGuard`) —
  one execution model with an explicit hand-off, not two schedulers racing one inbox.
- **Deletion is the one cross-driver hazard.** `DeletionFence.check` conflicts only when a delete
  targets a **resting** store with an active producer/consumer; `CollectorService.checkDeletion`
  folds authored Pipelines and in-flight runs into the check (`STORE_DELETE_CONFLICT`). Maintenance
  jobs (the deleters) stay standalone and own this fence.
- **Misfire/catch-up**: `catch_up:` (default false) — on start, a cron job whose fire elapsed while
  down submits one immediate run; a never-run job is not force-fired. (Quartz was rejected — the
  config file is the durable schedule.)

### 4.8 Back-pressure — admission control, never queues

No inter-node queues: the durable inbox is the queue, and back-pressure is admission control.

- **`IntakeGovernor`** (per-pipeline per-cycle admission cap, oldest-first, **on by default at 500
  files/cycle** (operator, 2026-10-06: soak waived; `0` = unbounded) via
  `-Dingest.maxFilesPerCycle`, floor `-Dingest.minFilesPerCycle`, `-Dingest.backpressure.adaptive`;
  per-Pipeline TOON override `processing.intake.*`, absent block = inherit globals). The controller
  halves the cap on **cycle overrun** and doubles it back under half the interval — the 2× gap is
  the hysteresis. ⚠ **Inbox lag / pending depth are deliberately NOT throttle inputs** — throttling
  on them is positive feedback (admitting less raises both) and would pin a backlogged-but-healthy
  pipeline at the floor; they stay observability surfaces (`inspecto_inbox_oldest_seconds`).
  Exported gauge: `inspecto_intake_cap`. How it composes with the Consignment slot broker:
  [spec-consignments.md](spec-consignments.md) (concurrency).
- **Acquire high-water (B4)**: acquisition de-schedules itself when the inbox backlog reaches
  `-Dacquire.backpressure.highWater` (0 = off) — throttles the *producer*, the deliberate mirror of
  the intake governor, which must never throttle the ingest *consumer* on backlog.
- Fixed ceilings: `sources.max × processing.threads × duckdb_threads`, DuckDB `memory_limit` /
  `max_temp_directory_size`, per-Collector `CircuitBreaker` + `RateLimiter`. Fan-out (`route` clone,
  `split`) charges the **amplified** volume against the batch budget; overflow chunks and branches
  run sequentially.

### 4.9 The provenance data plane (shipped)

Per-(node, relationship) row counts are collected inline by `ProvenanceCollector` during authored
runs and persisted by `DbProvenanceStore` when `-Dprovenance.backend=duckdb` is set (default off).
`GET /provenance?flow=&consignment=` + `GET /provenance/batches?flow=` feed the editor's last-run overlay
and the per-edge Sankey; the conservation invariant ([4.6](#46-execution-model--commit-branches-and-where-things-actually-run)) is checked over these counts. The plane
shares the pipeline graph's topology — quantities painted onto its edges.

### 4.10 Decisions of record and boundaries

Design-era decisions D1–D12 (runtime = topology over the batch engine · TOON files + `use:`
registry · route case/clone · merge = SQL over predecessors · adapter lands files (land-then-ack,
at-least-once) · entry-node triggers · admission back-pressure on cycle overrun · `(batch, branch)`
commit · in-file component identity, no version pinning · DAG over `data` edges · test = bounded
scratch dry-run · legacy auto-lift with parity gate) all stand; the full statements with rationale
are in the [archived design text](../../../../archived-documents/plans-archive/pipeline-graph-design-era-2026-06.md)
§9. The decisions of record taken 2026-08-31 (D1–D10) are in
[the etl plan](../etl/plan.md) § *Decisions*, and the
token model itself is [spec-pipeline-model.md](spec-pipeline-model.md) § *The token model*.

Boundaries still true (v1 non-goals, archived §12 for the full table): no decoupled per-node
scheduling or inter-node queues; no live × live keyed join; adapter ingestion is at-least-once; no
component version pinning; transforms must be SQL-expressible (imperative logic = the plugin SPI);
DAGs only; per-Pipeline trigger granularity; no cross-branch transactional commit; multi-tenant RBAC is
an edition concern.

Review risks R1–R6 (2026-06-16): all resolved — R1 (row-shaping machinery) built as `RowShaper`;
R2 (`(batch, branch)` commit) built as `BranchCommitLog`/`Coordinator`; R3 (branch executor)
**CLOSED 2026-08-26**, armed on the ingest path at the `writeAndTrace` choke point
([spec-ingest.md](spec-ingest.md) § *Branch-aware ingest*); R4 (fan-out lift) built; R5
(`on_commit` cross-Pipeline rule) enforced until the relation was retired in 4.0.0 (X5-a); R6 (`ingest` job type) deleted.

### 4.11 Route branches and arming (pointer)

A `route:` branch is `{key, where, database}` — `key` and `database` are DERIVED (edge name; the
sink the edge feeds, stamped as the branch↔sink join key), `where` is authored. `RouteArming`
refuses an armed branch with no `where` (WARNING while `active: false`, 422 once active; the
`default:` branch is not exempt). ⛔ `branches` must never get an `AttributeSpec` — a specced key is
form-owned and would destroy the derived pair. Full account:
[spec-pipeline-model.md](spec-pipeline-model.md) (route branches); runtime fork:
[spec-ingest.md](spec-ingest.md) § *Branch-aware ingest*.

---

## 5. Consignment status flow (the flowfile question)

"Where is this Consignment, and what happened to it?" — NiFi's flowfile question, answered by four
separate mechanisms rather than one attribute bag. As-built after the consignment-chain plan
(shipped 2026-08-12/13; provenance from S3, the live gauge from S7, the audit holes closed after).
Vocabulary: **Consignment** in code too since the Phase-7 rename shipped (`ff33246a` —
`ConsignmentAuditWriter`, `ConsignmentManifest`, `ConsignmentEvent`, …); ⚠ wire and persisted
spellings deliberately kept `batch_id`/`batches` (ledger columns via read-alias, `GET /runs/{n}/batches`).
The persisted trio (the DB columns, the `__batch_id` reference column, the `batch_id` job key) became
`consignment_id` / `__consignment_id` in 4.0.0 (D-12, 2026-11-17), with legacy reads kept.

### Per lane

The per-lane identity/status table lives in
[spec-pipeline-execution.md](spec-pipeline-execution.md) — this section owns the recording mechanisms
below (gauges, ledgers, quarantine, drill-down).

All provenance-writing lanes share the same `inspecto_pipeline_provenance` matrix, through **one**
`DbProvenanceStore` shared via the per-space `ProvenanceStores` registry — DuckDB is single-writer, so
two independently-opened stores would contend. Default-off (`-Dprovenance.backend=duckdb`); absent, the
editor's per-edge weights 404 and recording is a map lookup.

### Live gauges are in-memory by decision, not by omission

`IngestProgress` (which file) and `StepProgress` (which step) are process-local
`ConcurrentHashMap`s, cleared in a `finally` so a snapshot never outlives its batch/run — success or
failure alike. Both are served by the existing poll route (`InboxStatus.current` / `.step`,
`RunRoutes`). Two alternatives were **refused** with reasons that still hold:

- ⛔ **Per-step Signals.** A Signal is a durable ledger write, and the one existing emission is already
  synchronous *inside the pipeline's run claim* (`ConsignmentAuditWriter.flush` → `PipelineConsignmentSignal`).
  Per-step would multiply durable writes on the claim-holding thread — and because Signals trigger
  `on_signal` jobs, a mid-batch Signal invites triggered work to fire **while the claim is held**: the
  re-entrancy class [PROJECT_NOTES §6](../../../../PROJECT_NOTES.md) forbids. Revisit only at
  per-Consignment grain with a mandatory off-bus hand-off.
- ⛔ **Periodic persistence of progress.** Stale by construction, recurring writes for a value that
  expires in seconds, and crash-orphaned rows to clean. The post-crash question is already answered by
  the ledgers.

A wedged-but-alive step therefore reads as a **stale `startedAt` on a still-present snapshot** — that
is the intended hang diagnostic. The EL lane tracks `parse`→`transform`→`sink` on the Java per-member
path only: the native streaming paths fuse those stages into one pass, so they are deliberately
untracked rather than reported falsely.

### Auditing a failed file or record

**Files.** A bad input is moved to `<quarantine>/<poll-subpath>/<reason>/<file>` (reasons:
`field_mismatch`, `unreadable`, `empty`, `corrupt_download`, and since X1 `retry_exhausted` — a file whose
Consignment failed at COMMIT `-Dingest.retry.max` times; see the bounded-retry note in
[operations-reference](../../layer/platform/admin-guide-operations-reference.md)) — the tree itself is evidence, organised by
why. The status ledger carries a row per member file (`filename, status, parsed_rows, error_rows,
error, consignment_id`), the batches ledger the per-Consignment roll-up, the lineage ledger the
output↔input join. UI: **Run Detail** tabs (Batches / Files / Lineage / Quarantine / Commits), drill-in
by `consignment_id`, and Reprocess by batch id.

**Records.** Two distinct failure kinds, both now auditable:
1. **Parse rejects** — offending lines are written to a companion `errors/<base>_errors.csv` which
   travels *into quarantine beside its input file*. `GET /runs/{name}/errors?file=<bare name>` serves
   that content (bounded, `truncated` reports the true total), surfaced as "View the rejected rows" on
   Files rows with `error_rows > 0` and on Quarantine rows.
2. **Coercion failures** — a failed `TRY_CAST`/`TRY_STRPTIME` yields NULL and **keeps the row**.
   `DataTransformer.countCastFailures` measures these over the *same compiled expressions* the
   transform uses (never a re-derivation) and reports `cast_failures` on the batches ledger.
   ⚠ **`-1` means NOT MEASURED and writes a BLANK cell** — a path that cannot measure must never claim
   a clean batch. `EXPR` mapping rules are excluded by design: author-owned SQL has no defined
   "non-blank source".

**Job-lane rejects are ONE kind carrying a reason** — `reject:<reason>`, where the reason names which
operator diverted the rows (`filter`→`dropped`, `validate`→`invalid`, `dedup`→`duplicate`,
`merge`→`unmatched`). ⚠ They are four *reasons*, not four relations; since 4.0.0 (X5-a) the
constants carry the `reject:<reason>` spelling, and the old bare spellings are read as aliases. Rejects are scratch unless the author **wires a sink to the reject
outlet** — the designed pattern ("the user never wires these, only tunes where they rest").
`ConservationCheck` alerts on an unexplained in/out imbalance either way.

#### Rejects are counted in TWO columns, files and rows (D2 — AS-BUILT 2026-09-22)

✅ **SHIPPED 2026-09-22 (`WB-09`).** The batches ledger header is
`…,member_count,rejected_files,rejected_rows,total_input_rows,…`, and `total_input_rows` counts what
ARRIVED. ⚠ **A wholly-rejected member counts in `rejected_files` and NOT in `rejected_rows`** — its rows
were never parsed, so counting them as rows would double-count the file as rows it never had.

🔴 **The header has SEVEN construction/mirror sites, not the four the plan listed.** Beyond
`ConsignmentAuditWriter`'s header string, its `batchLine` codec, `ConsignmentRow` and
`OperationalTables.BATCHES` (the agent's SQL surface, which declares the header explicitly), the
compiler found `DrainCommand` and four test fixtures across three
modules. ✅ That is the case for the typed record over the string header: **a stale string mirror
mis-reads silently; a stale constructor does not compile.** Readers parse **by header name per file**
(`Csv.readInto`), so *appending* a column never breaks old ledger files; a stale mirror silently hides it.

⚠ **`error_rate` changed meaning, deliberately.** The alert measure is
`1 - sum(total_output_rows)/sum(total_input_rows)`; with the old input count excluding dropped rows, a
file that lost a record reported **0%**. It now reports the real rate, so thresholds tuned against the
old always-reconciling numerator may fire where they used to stay quiet. ✅ The alert measure was
already NAMED `rejected_files` while reading a column called `rejected_count`; the rename ends that
mismatch too.

Pinned by `ConsignmentAuditWriterTest.theBatchLedgerCountsRejectedFilesAndRejectedRowsSeparately`,
which asserts the two columns by POSITION as well as by name — readers parse by header name, so a codec
emitting them in the wrong order would swap files and rows with every column still present.

*(The decision, for provenance:)* `rejected_count` is **split, not redefined** — `rejected_files`
(today's value: members not `SUCCESS`) **and** `rejected_rows` (the sum of `IngestResult.errorRows`), with
`total_input_rows` = parsed + rejected rows so the ledger reconciles to the file on disk. Renaming rather
than overloading is what the ledger needs: one number that meant both was the ambiguity. ✅ Breaking the
header is free — nothing after 3.x shipped.

🔴 **Why:** measured 2026-09-22 on a 20-data-row file carrying one row truncated mid-write to 9 of 18
fields, the batch row read `status=SUCCESS · rejected_count=0 · total_input_rows=19 ·
total_output_rows=19` — **the counters reconcile (19 = 19) while a record is missing.** The drop is
recorded only in `<dirs.errors>/<file>_errors.csv`, which nothing in the audit row points at. An operator
reconciling output against the store by these counters concludes nothing was lost.

⚠ The invariant the split buys: `total_input_rows = total_output_rows + rejected_rows` when no Step drops
rows; a Step that drops rows is the other, already-honest half (see
[`spec-step-catalog.md`](../etl/spec-step-catalog.md)'s counted reject relation). Tracked as `WB-09` in
`archived-documents/plans-archive/workbench-trust-plan.md`; row `INGEST-REJECT-ACCOUNTING-1`.

### Intrinsic and inherited status must not collapse into one enum

A file's own outcome and the outcome of the Consignment containing it are different facts, and both are
needed: a `COMMITTED` file inside a `FAILED` Consignment is a real and common state (the file landed; the
Consignment failed later, on another file or at commit). Folding them into a single status loses the ability
to answer "which files actually made it?" after a partial failure — which is exactly the question reprocessing
asks.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

---

## 6. Stopping a Space (graceful stop)

`SpaceManager.close()` closes each Space under a 10 s budget (`CLOSE_DEADLINE_MS`). `CollectorService.close()`
first asks in-flight runs to stop, then drains them (Jobs, timers, trigger workers, enrichment, the notification
queue), and closes the stores last. A busy Space can overrun the budget; its drain is then abandoned on a daemon
thread. **Since `DEMO-BAKE-WAL-RACE-1` (2026-11-12) an abandoned close still closes the Space's stores**
(`CollectorService.closeStoresNow()`, which also closes the Job run ledger and provenance store through
`JobService.closeStores()`), under a second budget (5 s since `JOB-OUTLIVES-CLOSE-1`, below). A closing DuckDB store checkpoints, so no `.wal` is left;
work still running then fails on a closed store, which is the stop it was already asked to make. Each store
serialises its one connection (`SingleConnectionSource`), so a store close waits for the statement in flight and
never cuts it; only a store still busy after the second budget keeps its `.wal`, which DuckDB replays at the next
start. The store close runs once, whichever path reaches it first.

**Since `JOB-OUTLIVES-CLOSE-1` (2026-11-15) an overrun drain is escalated before it is abandoned.** A Job Run that
did not stop for the cooperative cancel (the claim's `cancelled()`, read at `CommitFence.checkpoint`) is interrupted:
`SpaceManager` calls `CollectorService.interruptRuns()` → `JobService.interruptRuns()` (`shutdownNow()` on the Run
executor; the Space is marked closing, so an interrupted Run is recorded `CANCELLED`, a Run still waiting for a run
slot is recorded `SKIPPED` "the Space is shutting down", and a Run spends the interrupt before it writes its own
records). The drain then gets up to 5 s more (`STOP_GRACE_MS`) to finish, its own
store close included; only a drain still busy after that is abandoned, and `closeStoresNow()` gets what is left of
the second budget, 5 s. The worst case per Space stays two budgets (20 s). ⚠ Trade-off: the flush budget is halved on
every overrun, also when the drain is stuck outside any Run (an agent or a notification queue that will not close),
where the grace cannot help and only shortens the flush; a store close waits only for its statement in flight, so 5 s
is normally ample. Before this a Run still writing its Dataset
went on writing after `SpaceManager.close()` had returned — on a loaded machine `ControlApiSpaceTemplateSeedGateTest`
could not delete `t-la-utility/data/transformer_loss`, and its log showed 2–3 Spaces abandoned per run even when it
passed. `sql.template` now checks the cancel before each write under the data root (before it creates the sink,
after its query, before each Decision Rule route and before the snapshot swap), so a cancelled Run reveals no
snapshot. ⚠ Not covered: a Run inside a native DuckDB statement stops only when that statement returns (an interrupt
does not cancel it); such a Run is still abandoned, as before, with the WARN. ⚠ Known limit: an interrupt that lands while a Run emits an
event closes the event journal's `FileChannel` until its next rotation, exactly as a Run deadline expiry
(`JobDeadline`) already can; the events appended until then are buffered only ("survives only a clean shutdown",
`ParquetEventStore`), not crash-durable. Tests: `SpaceCloseStopsRunsTest`,
`JobCloseStopTest`.

Decision: close the stores at the deadline rather than extend the budget while Jobs finish. A container runtime
stops a process after its own grace period (10 s for `docker stop`, 30 s for a Kubernetes pod) and Spaces close one
after another, so a longer budget would not hold; a checkpoint alone would not either, because work that is still
writing would add to the WAL again before the JVM exits. Test: `SpaceCloseFlushTest`.

How a Pipeline run and a Job Run react to the cooperative cancel (`RunCancelledException`, `CANCELLED`
outcomes, lease hand-over) is [spec-ingest.md](spec-ingest.md) § *Failover and graceful stop*.

---

## 7. Directory layout

Since multi-space, everything an installation owns lives under a **Space** directory
(`-Dspaces.root`, default `./spaces`); single-space installs use the `default` Space. Legacy flat
layouts are migrated once via `com.gamma.service.SpaceMigrator` — there is no flat fallback.

```
sandbox-root/                       ← working directory for local runs (the JVM CWD)
  packs/                            ← host-wide Job Packs (job-framework §12; -Djobs.packs.dir default)
  spaces/                           ← -Dspaces.root; one directory per Space
    _shared/                        ← THE EXCHANGE (cross-Space sharing) — reserved, not a Space
    <space-id>/
      space.toon                    ← Space manifest
      config/<name>/                ← *_gen.toon, *_schema.toon, *_pipeline.toon (+ grammars, jobs, alerts)
        share-grants/               ← owner-side Share Grants (cross-Space dataset/widget sharing)
      data/…                        ← the per-pipeline dirs.* trees: inbox, partitioned Parquet
                                      output (Tables), backup, errors, quarantine, markers,
                                      status, temp scratch, logs
      audit/                        ← batch/file audit ledgers
      duckdb/                       ← per-Space DuckDB state
      flows/                        ← authored Pipeline definitions (storage dir name is historical)
      views/                        ← ViewDefinitions (T32-C)
  warehouse_setup.sql               ← pg_duckdb warehouse schema, views (run once on server)
```

**Layout contract (§L0).** The four persistence axes — `config/` (authored definitions), `data/` (the
data plane), `audit/` (ledgers) and `duckdb/` (embedded-DB state) — must never mix. This is checked at
Space boot by `com.gamma.service.SpaceLayoutContract` and is **advisory**: every departure emits a
`LAYOUT_CONTRACT_VIOLATION` WARN event, never a boot failure (matching the warning-and-skipping posture
of `SpaceManager.discover`). Every new subsystem must declare its home here before landing:

| Subsystem | Home | Axis |
|---|---|---|
| Job run logs | `audit/` | audit |
| Job Run Artifacts DB | `duckdb/` | duckdb |
| Job Packs (host-wide) | `<install-root>/packs/` | (install scope) |
| Share Grants (owner-side) | `config/share-grants/` | config |
| The Exchange (cross-Space) | `spaces/_shared/` | (install scope, reserved) |
| ViewDefinitions | `views/` | config-adjacent |

Contract rules enforced by the check: canonical subdirs (`data/ audit/ duckdb/`) must exist; no
embedded-DB file (`*.duckdb`/`*.db`/`*.wal`) may sit outside `duckdb/`; no unrecognised top-level entry
may appear at the Space root. `_shared/` under `-Dspaces.root` is **not** a Space (discovery admits only
dirs with a `config/` subtree, so it is skipped) — see
[storage-layout-and-sharing-plan](../../../../archived-documents/plans-archive/storage-layout-and-sharing-plan.md) §2.

⚠️ **A relative `dirs.*` path resolves under the Space directory, not the JVM working directory** —
write it Space-relative (`data/<pipeline>/database`); a config ref sits beside its config
(`DATA-DIRS-RESOLVE-AGAINST-CWD-1` / `SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`, 2026-09-23 — see
[config safety](../config/spec-config-safety.md)). `SpaceMigrator` still cannot rewrite absolute paths.

---

## References

- [spec-ingest.md](spec-ingest.md) — the ingest SPI, Consignment coordination, failover, branch-aware ingest
- [spec-consignments.md](spec-consignments.md) — Consignment addressing (Selector, revisions, Watermark) and concurrency
- [spec-parsing.md](spec-parsing.md) — parse mechanisms, the `parsing:` block, parser plugins
- [spec-pipeline-model.md](spec-pipeline-model.md) — node types, the token model, the authoring round-trip
- [spec-pipeline-execution.md](spec-pipeline-execution.md) — execution lanes, live execution, test runs
- [spec-sinks.md](spec-sinks.md) — output sinks and quarantine
- [spec-duckdb.md](spec-duckdb.md) · [spec-db-layer.md](spec-db-layer.md)
- [events-metrics](../event/spec-events-metrics.md) · [jobs](../processor/spec-jobs.md)
- [layer/platform architecture](../../layer/platform/architecture.md) — the platform-wide layer model
- [GLOSSARY.md](../../../../GLOSSARY.md) §2 *Consignment* / §6-B
