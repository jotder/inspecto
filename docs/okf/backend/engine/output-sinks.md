---
type: Concept
title: Output & Sinks
description: The OutputFormat strategy, partitioned vs single-file writers, and quarantine outcomes.
resource: inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [engine, output, sink, partition, quarantine]
timestamp: 2026-06-28T00:00:00Z
---

# Output & Sinks

* **`OutputFormat`** (`inspecto-etl/src/main/java/com/gamma/etl/OutputFormat.java`) — an enum-as-strategy with
  `PARQUET` (compressible) and `CSV`. Each constant carries its own `copyToken()`/`extension()`/
  `supportsCompression()`; `resolve(token)` maps the config token (anything but `"PARQUET"` → `CSV`).
* **`PartitionWriter`** (`inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java`) — writes a materialized
  table to Hive-partitioned output via DuckDB `COPY … PARTITION_BY`. **Requires non-empty partition columns**
  (default `["year","month","day"]`), excludes the internal `__src_id` column, uses a two-step atomic rename,
  and parallelises rename fan-out above 16 partitions.
* **`PartitionSinkWriter`** (`inspecto-engine/src/main/java/com/gamma/pipeline/exec/PartitionSinkWriter.java`) — the
  [pipeline-engine](../pipeline-graph/live-execution.md) sink writer: delegates to `PartitionWriter` when partitions are
  declared, else writes a **single unpartitioned file**. (`sink.view` subtypes write no bytes — they register
  a view definition instead.)

## Store-layout contract (decided 2026-07-18 — closes the UAT double-count)

One store-layout contract governs where sink bytes land and what reads sweep (root cause of the
UAT-proven +72% double-count: a flow job's `data_dir` pointed inside the `orders` store, its sink
nested at `orders/rollup/`, and the dataset's recursive glob counted both):

* **Write side** — a persistent store is a **top-level directory under the space data root**.
  `PipelineJobRunner.requireTopLevelSinks` fails a flow run closed *before any bytes are written*
  when a sink would resolve deeper (a `data_dir` pointed inside another store's tree, or a slashed
  `store` name). A `data_dir` fully **outside** the data root stays allowed (external export). Job
  configs bypass `ConfigSafetyValidator`, so this is enforced at run time.
* **Read side** — `SqlViews.storeReadRoot(dir)`: a **pipeline-shaped store** (one with a
  `database/` subtree) is read at its *mapped output* (`<store>/database/**`), so `backup/`,
  `quarantine/` (incl. Decision-Rule record quarantine) and any stray nested trees never leak into
  reads. A flat snapshot store (no `database/`) reads unchanged. Applied by `DatasetRelation`
  (`physicalRef` datasets), `SourceStoreReader` (flow seeds + `sql.template` sources — a flow can
  now seed straight from an ingest pipeline's store by name, no `data_dir` hack),
  `PipelineJobRunner.deriveViewSql`, and `ExpectationEvaluator`. An **explicit deeper ref**
  (`orders/database`, `orders/backup`) is honoured as written; `shared/…` Exchange refs are exempt.
  (`DbBrowserRoutes` already resolves pipeline stores to `dirs.database` by pipeline lookup.)

Tests: `DatasetRelationTest.physicalRefWithDatabaseSubtreeReadsMappedOutputOnly`,
`PipelineJobRunnerTest` (`seedReadsAPipelineShapedStoresMappedOutputOnly`,
`sinkNestedInsideAnotherStoreFailsClosed`, `slashedSinkStoreNameFailsClosed`,
`externalDataDirStaysAllowed`).

## Multi-destination `sinks:` (shipped 2026-08-02 — `0cdc9dff` + `79dcb3e6`)

A `*_pipeline.toon` may declare a top-level plural **`sinks:`** list — one `{database, format,
compression, ducklake}` tuple per destination. The single `output:` + `dirs.database` pair is the
one-element shorthand; `PipelineConfig.sinks()` is never empty (it synthesises the shorthand).

* **Model / parse / validate** — `PipelineConfig.Sink` (`@PublicApi 4.8.0`) + `resolveSinks(...)`
  (synthesises the shorthand, never throws); `PipelineConfigParser` parses the `sinks:` list (each entry
  needs a non-blank `database`); `ConfigSafetyValidator.checkSink` path-jails every `database` /
  `ducklake.data_path` and allow-lists format/compression.
* **Ingest fan-out** — `ConsignmentIngestStrategy.writeAndTrace` (the shared choke point) fans the main
  partitioned write to every `cfg.sinks()` destination, each under its own `database` root (the `dbDir`
  suffix beyond `dirs.database` is preserved) and its own format/compression. **Predicate =
  `cfg.sinks().size() > 1`** — *not* the whole-graph `ConsignmentGraphRunner.engages`, which miscounts a
  multi-schema batch. A single destination is byte-for-byte the legacy write. **Direct fan-out, not
  `ConsignmentGraphRunner`** — the ingest commit model is already "write everything, finalise once," and
  `finalizeSource` (backup / markers-LAST / ledger) is per-source-file, so it runs exactly once
  regardless of destination count. `ConsignmentGraphRunner` stays the flow-job executor, unused by ingest.
* **Bypass guards** — paths that skip `writeAndTrace` (native single-member `streamingIngest`/
  `chunkedIngest`; plugin `GenerationModeIngester`) materialise via the union path when `sinks>1`
  (`CsvIngestStrategy`, `StreamingPluginIngestStrategy`).
* **Editor round-trip** — `PipelineEditable.lower` no longer refuses `MULTI_SINK`; a graph with >1
  distinct sink database lowers to a `sinks:` list (a NEW list's shorthand is built from the first
  destination; over a file that already has one, `output:` is the default layer and is preserved — see
  below). Safe because a `transform.route`/`derive` node is not `LOWERABLE` (fails
  `UNSUPPORTED_NODE` first), so every sink reaching here is a replicate-per-destination fan-out. The
  `MULTI_SINK` constant was deleted with the pipeline spec's Wave 0 (2026-08-31) — it had been
  unreachable since this change, and an unreachable refusal code reads as a live one.
* **Refusals (deliberate, at load/runtime).** A **versioned reference store** (`reference.load:
  upsert|scd2`) + `sinks>1` is refused at `PipelineConfig.prepare()` (single version history is
  ill-defined across destinations). **Decision-rule *routing* + `sinks>1`** is refused at runtime in
  `writeAndTrace` (routed outputs are single-destination). Multi-sink commit is **not** cross-branch
  transactional (B9 stands) — a clone may have some destinations committed and others retrying.

⚠ **Toon authoring:** in the indexed-tuple form `sinks[N]{database,format}:`, a `database` path
(contains `:` and `/`) **must be quoted** — `"/data/hot",PARQUET` — or the tabular decoder reads 0 rows.

**`output:` is the DEFAULT LAYER for every `sinks[]` entry** (operator decision 2026-09-23,
`SINKS-ENTRY-IGNORES-OUTPUT-DEFAULTS-1`). All four keys the two share — `format`, `compression`,
`ducklake`, `filename_column` — resolve **entry's own value → `output.*` → hard default** (`format` =
`CSV`, the rest none). An explicit entry value always wins, including an opt-out (`compression: none`).
Before this, an entry took nothing from `output:`: the parser defaulted an omitted `format` to the
literal `"CSV"` and left `compression` null, so the shipped `premed_events` (`output.compression:
snappy` above three `{database,format}` entries) never passed its codec to the write.

* **Where it resolves** — `PipelineConfigParser` leaves an omitted entry key **null** (no `"CSV"`
  default any more); `PipelineConfig.resolveSinks` fills it from `output:`. So `cfg.sinks()` always
  carries **effective** values, and every writer (`IngestSinkWriter`, `ConsignmentIngestStrategy`, the
  test-run scratch sinks) and `PipelineLift`'s display graph see the inherited value with no change of
  their own.
* **The editor does not materialise it** — `PipelineEditable.toMap` gives a sink node only its own
  entry's keys, and `lower` **preserves `output:` verbatim** while the file keeps a plural `sinks:`
  block (no node models that layer). Before,
  `lower` rebuilt `output:` from the primary node, which dropped the inherited codec on every no-edit save
  and would have let one destination's explicit value re-point every other destination's inheritance.
  **Collapsing to one destination** drops `sinks:` and folds the layer into the shorthand (the node's
  own value, else the one it was inheriting). `RecipeConverter` takes the shorthand destination's keys
  from its matching `sinks:` entry, not from `output:`, so the recipe round trip does not stamp the
  inherited value onto `sinks[0]` either.
* ⚠ **snappy is DuckDB's own parquet default.** A null compression appends no `COMPRESSION` option, so
  `premed_events`'s bytes happened to be snappy before the fix too. The bug only showed on disk for any
  *other* codec. That is why the on-disk test uses `zstd`: with the fallback reverted it reads `SNAPPY`,
  not `ZSTD`.

Tests: `PipelineConfigSinksTest` (incl. `anEntryInheritsEveryOutputKeyItOmits`,
`anEntrysOwnValueOverridesTheOutputLayer`), `ConfigSafetyValidatorTest` (per-sink jail/allow-list),
`PipelineLiftTest.liftsSinksListToADataFedFanOut`,
`ConsignmentIngestorSinksTest.fanOutWritesEachDestinationAndFinalisesOnce`,
`ConsignmentIngestorSinksTest.anEntryThatOmitsCompressionWritesTheOutputBlocksCodec` (reads the parquet
codec off disk), `…anEntrysOwnCompressionOverridesTheOutputBlock`,
`PipelineEditableTest.twoDistinctDatabasesLowerToASinksList`,
`PipelineEditableTest.anInheritedOutputValueRoundTripsWithoutBeingMaterialisedPerSink`,
`…anExplicitPerSinkValueOverridesWithoutRepointingTheLayer`,
`…collapsingToOneDestinationFoldsTheInheritedValueIntoTheShorthand`, and the fixture sweeps
`LiftLowerFixtureSweepTest` + `RecipeConverterTest` over the real `premed_events`,
`ControlApiPipelineCrudTest.twoDistinctDatabasesSaveAsAMultiSinkPipeline`.

**Each sink registers into ITS OWN effective `ducklake`** (operator decision 2026-09-23,
`SINK-DUCKLAKE-IGNORED-1`). The parser, `resolveSinks` and `PipelineLift` always carried a per-sink
lake, but `DuckLakeRegistrar.register` read only `cfg.output().duckLake()` and pooled every written file
into it — a sink's own lake was silently dropped, and a two-sink pipeline registered **both** sinks' files
into the output lake (measured live: `lake_out` held `A=3 B=5`, `lake_a` had no table).

* **Attribution** — `DuckLakeRegistrar.plan` assigns each written file to the sink whose `database` root
  contains it (the root every write site re-roots under: `IngestSinkWriter.write`,
  `ConsignmentIngestStrategy.flatWriteAndTrace`); the **deepest** root wins, so nested databases do not
  claim each other's files. A multi-sink file under **no** root is refused (`IllegalStateException`), not
  registered somewhere arbitrary. **One sink** (the shorthand, or a one-entry `sinks:`) takes every file
  with no attribution — byte-for-byte the old behaviour.
* **Partitioned** — the "registration is mandatory" check runs for every sink that wrote, **before any
  ATTACH**, and names the offending `sinks[]` database; a sink that wrote nothing is skipped.
* ⛔ **Two sinks registering into ONE lake table are REFUSED** (operator decision 2026-09-23,
  `SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1`). Before it, two sinks inheriting the same lake both inserted
  into its one table, so a replicate fan-out held each row once per destination — silently. The rule is
  `com.gamma.etl.SinkLakeCollisions` (one copy, plain data in, the `RouteArming` pattern):
  * **Identity** = `catalog_url` (trimmed, verbatim — `./lake.ducklake` and `lake.ducklake` are NOT
    unified) + `schema` (default `main`) + the table **that would actually be registered**. Only
    `enabled: true` lakes register, so only they collide. The effective lake is entry → `output.ducklake`.
  * **The registered table is not always the block's `table` key.** `register` is handed `batch.table()`,
    which overrides it; that is non-null exactly on a multi-schema pipeline (`processing.schemas[].table`;
    `ConsignmentPlanner` maps blank / `default` to `null`). So there a per-sink `table` does **not**
    separate two sinks sharing a catalog, and the refusal says to give one its own `schema` or
    `catalog_url` instead. On a single-schema pipeline it suggests a per-sink `table`.
  * **Where it bites** — `PipelineConfig.prepare()` throws the first collision, **unconditionally** (a
    shape rule like the versioned-reference-with-many-sinks rule above it, not an `active`-gated arming
    rule). The save path — `/config/write`, `/config/patch`, `PUT /pipelines/{name}/graph`, bundle import
    and the draft branch of `POST /validate` — reports every collision as
    `ERR_SINK_DUCKLAKE_SHARED_TABLE` at **ERROR even on an inactive draft**, because a WARNING would write
    a file that then fails at registration. `POST /validate {configPath}` now answers **422** (not 500) for
    any `prepare()` refusal. `fromMap` (editor / lift) does not refuse, so such a file still opens.
  * ⚠ It lives in `ConfigRoutes`, **not** `ConfigSafetyValidator`: `inspecto-config` is below
    `inspecto-etl`, so the validator cannot call the rule without a second copy of it. The draft side
    approximates the parser's plugin-ingester test (`processing.ingester` / `parsing.plugin.ingester`);
    a grammar-component or `frontend: asn1` ingester beside a `schemas[]` list is not modelled there.

Tests: `DuckLakeRegistrarPerSinkTest` (7), `SinkDuckLakeSharedTableTest` (8, `prepare()`),
`ControlApiSinkDuckLakeSharedTableTest` (4, real HTTP). A live two-catalog DuckLake run (ducklake extension cached)
read `lake_a → A=3`, `lake_out → B=5` after the fix; it is not in the reactor, for the network-INSTALL
reason `DuckLakeRegistrarTest` records.

## Quarantine outcomes

* `QUARANTINED_UNREADABLE` — the ingester threw (file unreadable/undecodable).
* `QUARANTINED_MISMATCH` — the ingester emitted **zero** rows (parsed, but nothing came out).
* `SinkFlushException` — a framework/schema error during a generation flush → **fail the batch** (not
  quarantine). Audit is always written regardless (see [ingestion](ingestion.md)).
