---
type: Concept
title: Output & Sinks
description: Where a Pipeline's rows land — the OutputFormat strategy, partitioned vs single-file writers, the store-layout contract, multi-destination sinks with the output default layer and per-sink DuckLake, an object-store dirs.database, quarantine outcomes, and the objectstore.export push post-action.
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [engine, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
---

# Output & Sinks

## What this covers

Where a Pipeline's output goes once it has been parsed and shaped. It covers the file formats and writers,
the rule that keeps every store a top-level directory so reads never double-count, writing one Pipeline to
several destinations (with shared defaults and one DuckLake catalog per destination), writing straight into
an S3-compatible bucket, what happens to files that cannot be ingested, and the `objectstore.export` Job Type
that pushes committed output to S3 after a successful run.

## Contents

1. Writers, store layout and destinations — `OutputFormat` and the writers, the store-layout contract,
   multi-destination `sinks:`, object-store `dirs.database`, quarantine outcomes
2. Object-storage export (S3 / HDFS) — the push post-action and its posture

## Writers, store layout and destinations

* **`OutputFormat`** (`platform/inspecto-etl/src/main/java/com/gamma/etl/OutputFormat.java`) — an enum-as-strategy with
  `PARQUET` (compressible) and `CSV`. Each constant carries its own `copyToken()`/`extension()`/
  `supportsCompression()`; `resolve(token)` maps the config token (anything but `"PARQUET"` → `CSV`).
* **`PartitionWriter`** (`platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java`) — writes a materialized
  table to Hive-partitioned output via DuckDB `COPY … PARTITION_BY`. **Requires non-empty partition columns**
  (default `["year","month","day"]`), excludes the internal `__src_id` column, uses a two-step atomic rename,
  and parallelises rename fan-out above 16 partitions.
* **`PartitionSinkWriter`** (`platform/inspecto-engine/src/main/java/com/gamma/pipeline/exec/PartitionSinkWriter.java`) — the
  [pipeline-engine](spec-pipeline-execution.md) sink writer: delegates to `PartitionWriter` when partitions are
  declared, else writes a **single unpartitioned file**. (`sink.view` subtypes write no bytes — they register
  a view definition instead.)

### Store-layout contract (decided 2026-07-18 — closes the UAT double-count)

One store-layout contract governs where sink bytes land and what reads sweep (root cause of the
UAT-proven +72% double-count: a Pipeline Job's `data_dir` pointed inside the `orders` store, its sink
nested at `orders/rollup/`, and the dataset's recursive glob counted both):

* **Write side** — a persistent store is a **top-level directory under the space data root**.
  `PipelineJobRunner.requireTopLevelSinks` fails a Pipeline Job run closed *before any bytes are written*
  when a sink would resolve deeper (a `data_dir` pointed inside another store's tree, or a slashed
  `store` name). A `data_dir` fully **outside** the data root stays allowed (external export). Job
  configs bypass `ConfigSafetyValidator`, so this is enforced at run time.
* **Read side** — `SqlViews.storeReadRoot(dir)`: a **pipeline-shaped store** (one with a
  `database/` subtree) is read at its *mapped output* (`<store>/database/**`), so `backup/`,
  `quarantine/` (incl. Decision-Rule record quarantine) and any stray nested trees never leak into
  reads. A flat snapshot store (no `database/`) reads unchanged. Applied by `DatasetRelation`
  (`physicalRef` datasets), `SourceStoreReader` (Pipeline Job seeds + `sql.template` sources — a Pipeline Job can
  now seed straight from an ingest pipeline's store by name, no `data_dir` hack),
  `PipelineJobRunner.deriveViewSql`, and `ExpectationEvaluator`. An **explicit deeper ref**
  (`orders/database`, `orders/backup`) is honoured as written; `shared/…` Exchange refs are exempt.
  (`DbBrowserRoutes` already resolves pipeline stores to `dirs.database` by pipeline lookup.)

Tests: `DatasetRelationTest.physicalRefWithDatabaseSubtreeReadsMappedOutputOnly`,
`PipelineJobRunnerTest` (`seedReadsAPipelineShapedStoresMappedOutputOnly`,
`sinkNestedInsideAnotherStoreFailsClosed`, `slashedSinkStoreNameFailsClosed`,
`externalDataDirStaysAllowed`).

### Multi-destination `sinks:` (shipped 2026-08-02 — `0cdc9dff` + `79dcb3e6`)

A `*_pipeline.toon` may declare a top-level plural **`sinks:`** list — one `{database, format,
compression, ducklake}` tuple per destination. The single `output:` + `dirs.database` pair is the
one-element shorthand; `PipelineConfig.sinks()` is never empty (it synthesises the shorthand).

* **Model / parse / validate** — `PipelineConfig.Sink` (`@PublicApi 4.8.0`) + `resolveSinks(...)`
  (synthesises the shorthand, never throws); `PipelineConfigParser` parses the `sinks:` list (each entry
  needs a non-blank `database`); `ConfigSafetyValidator.checkSink` path-jails every `database` /
  `ducklake.data_path` and allow-lists format/compression.
* **Structural spec — refused at SAVE, not at run (2026-09-24).** `ConfigSpecs.pipeline()` declares
  `sinks` as a **list of objects**: `FieldSpec.listOf` with an item spec (`FieldSpec.items`, the
  list-of-objects facility added for it). `database` is the one required item key — every other key
  inherits `output:` (below), so requiring it would refuse valid files; `format` is the `CSV|PARQUET`
  enum, `ducklake` a map, `ducklake.enabled` a boolean. `ConfigLoader.validate` refuses a `sinks:` that is
  not a list (a map, or the comma-string a scalar list tolerates), a non-map entry and an entry with no
  `database`, naming the index (`sinks[1].database`) — so `/config/write` and every other save path
  answer **422**. Before, a `sinks:` map parsed as *no sinks* (the pipeline silently wrote to its one
  `output:`) and a missing `database` threw at config load. `ConfigJsonSchema` projects the item spec as
  the array's `items`, so an LLM author sees the element shape; the `/config` draft pane skips a field
  with `items` (it has no flat control for one — the canvas authors destinations). ⚠ The item spec is
  what let `sinks` leave `AcceptedConfigKeys.PARSER_ONLY` honestly — delisting `steps` without one was
  refused as gaming the ratchet ([pipeline-config-keys](../config/spec-pipeline-config-keys.md)).
  Tests: `ConfigLoaderTest` (the four `sinks` cases), `ConfigJsonSchemaTest.aListOfObjectsProjectsItsItemSpecAsTheArraysItems`,
  `ControlApiSinkDuckLakeSharedTableTest.writeRefusesAMalformedSinksBlock` (real HTTP).
* **Ingest fan-out** — `ConsignmentIngestStrategy.writeAndTrace` (the shared choke point) fans the main
  partitioned write to every `cfg.sinks()` destination, each under its own `database` root (the `dbDir`
  suffix beyond `dirs.database` is preserved) and its own format/compression. **Predicate =
  `cfg.sinks().size() > 1`** — *not* the whole-graph `ConsignmentGraphRunner.engages`, which miscounts a
  multi-schema batch. A single destination is byte-for-byte the legacy write. **Direct fan-out, not
  `ConsignmentGraphRunner`** — the ingest commit model is already "write everything, finalise once," and
  `finalizeSource` (backup / markers-LAST / ledger) is per-source-file, so it runs exactly once
  regardless of destination count. `ConsignmentGraphRunner` is not used by this fan-out. (⚠ When written, 2026-08-02, this line called it "the
  flow-job executor, unused by ingest"; since 2026-08-29 it drives the ingest graph fork — see
  [spec-ingest.md](spec-ingest.md).)
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
  ill-defined across destinations) - **permanent (operator, 2026-10-06)**: the stamp reads ONE prior version store (`existingStoreReader(dbDir, …)`)
  to skip unchanged rows, so N destinations would be N version histories that the enrichment views would
  each read as the truth — the same "one history is ill-defined across branches" rule `graphWriteAndTrace`
  holds permanently for `route:`. Multi-sink commit is **not** cross-branch transactional (B9 stands) — a
  clone may have some destinations committed and others retrying.
* **Decision-rule *routing* + `sinks>1` is CARRIED (operator, 2026-10-06).** The routed rows are
  **replicated** under every `sinks[].database` — the fan-out's own rule for the remainder: the pipeline
  `RouteSink` in `DecisionRuleApplier.apply(…, PipelineConfig, …)` re-roots `dbDir`'s suffix beyond
  `dirs.database` under each destination, writes `<dest db>/<rel>/<route destination>` in that
  destination's `format`/`compression`, and returns one output + lineage set per destination. The rules
  still run ONCE, above the lane fork; the old refusals in `flatWriteAndTrace`, `graphWriteAndTrace`
  and `flatReason` are gone, so both lanes carry it. A single destination is byte-for-byte the legacy
  write under `dbDir`. Routed copies do not apply `filename_column` (the routed write never did).
  Pinned by `DecisionRuleWiringTest.routeWithTwoSinksReplicatesRoutedRowsOnThe{Flat,Graph}Lane`.

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

### Object-store `dirs.database` (shipped 2026-11-01 — `SCALEOUT-OBJSTORE-DATABASE-DIR-1`)

The ingest lane writes Stage-1 output to an `s3://bucket/prefix` `dirs.database` that lies under the operator
allow-list `-Dassist.safety.object_roots` (containment rule: module.config `spec-config-safety.md`
§ *Object-store containment*). Scale-out plan §5.4 bullet 6, credentials route (a):

```
dirs:
  database: s3://inspecto-lakehouse/space-a/orders
output:
  connection: lake_s3          # an s3 Connection (*_connection.toon): host/port, username = access key,
                               # password = ${ENV:…} secret, options.region, options.protocol http|https
```

* **Where it happens.** `ConsignmentIngestStrategy.configure` calls `ObjectStoreDatabase.prepare` BEFORE the
  seal (LOAD is file access and is refused after it): containment re-checked; the Connection resolved from
  `ConnectionRegistry` (this Space); its host refused unless it is a DNS name or a canonical IPv4 dotted quad
  (`EgressPolicy.checkHost`, plus no `:` or `[`: no port and no IPv6 literal, which `ENDPOINT` cannot carry
  unbracketed; no `/`, `@`, `?`, `#`, whitespace: DuckDB dials the authority it parses from `ENDPOINT`, so
  `evil:80/x.allowed.com` would pass an allow-list pattern yet dial `evil` — verification finding; and no
  decimal / octal / hex / short / leading-zero / trailing-dot spelling, which libcurl reads as an address the
  Safety Policy never compared — item (e), 2026-11-13, `ObjectStoreIngestTest.aNumericHostSpelling…`) and then
  passed through `EgressGate` (DuckDB's S3 client dials from native code, past every Java-side egress hop); `httpfs` loaded; then `CREATE OR REPLACE SECRET inspecto_output_store
  (TYPE s3, …, SCOPE '<prefix>/')` (the trailing `/` matters: DuckDB matches a scope as a string prefix) — temporary, in memory, shown redacted by `duckdb_secrets()`. The seal adds the
  prefix to `allowed_directories`; `ingestAllowedDirs` leaves the URI out of the local list.
* **What the write looks like** is `PartitionWriter.writeToObjectStore`: no staging, no reveal, a repeat write
  accumulates (`spec-db-layer.md` § the output registry).
* **Sub-directories** (`table:`, segment keys, Decision Rule routes) join through `PathJail.childOf`, a
  `/`-join for a URI; the joined key is judged by `requireObjectStore` at the write, so a `..` table name is
  refused there.
* **Refused by name** (each a `SQLException` at `configure`, so the batch fails before any I/O), decided
  under operator delegation 2026-11-01, operator-confirmed 2026-11-01: no / unregistered / non-`s3` / tunnelled or
  proxied / keyless Connection; a `sinks:` list; `produces: reference`. Readers of
  an object-store pipeline's output (DB browser, join reference, enrichment) skip it, and the seal then
  refuses the read. Left open on the row: fan-out, reference stores, readers.
* **DuckLake registration (built 2026-11-01).** `output.ducklake.enabled` is accepted with an object-store
  database, so `-Dinspecto.topology=partitioned` (which requires registration) can use one. The registrar
  opens its own, unsealed connection, which held no credentials; `ConsignmentIngestor.finalizeSource` now
  passes it a `DuckLakeRegistrar.ConnectionSetup` from `ObjectStoreDatabase.registrarSetup`, run after the
  extensions load and before the `ATTACH`: the same checks as `prepare` (containment, shape, Connection,
  bare host, `EgressGate`), then the same prefix-scoped `inspecto_output_store` secret. ⛔ Because that
  connection has no seal, every written file is first checked against the database prefix: it must start
  with `<prefix>/`, have no empty, `.` or `..` segment, and contain no `\` or glob byte (`* ? [ ] { }`), or the
  batch fails before registration (a sibling prefix, `..`, another bucket, a local path). Operator decision
  2026-11-02, replacing the 2026-11-01 use of `PathJail.requireObjectStore` here: a prefix test, not the
  containment rule's key character set, so a partition value DuckDB percent-encodes
  (`ts=2026-11-01%2010%3A00%3A00` — the `%` is what the old rule refused) registers. ⛔ This check, not the
  secret's scope, is the boundary: DuckDB matches the scope on the string BEFORE it normalises `..` (measured:
  `<prefix>/a/../../other/x` is sent to `/other/x` with the scoped credentials), and `SqlViews.pathList`
  rewrites `\` to `/`, which is why `\` is refused (verification finding 2026-11-02). DuckDB's own writer
  escapes all of these bytes, so real output never contains them. The setup is built only when the lake is enabled, so
  an unregistered pipeline is unchanged. The lake's own `data_path` / catalog credentials are untouched:
  the secret is scoped to the database prefix, so a lake whose `data_path` is in a bucket outside that prefix still needs its
  own credentials, as before.
* **The Connection's `base_path` is not consulted** — the allow-list bounds where; the Connection supplies
  only the endpoint and credentials.

Tests: `ObjectStoreIngestTest` (18 — every refusal one key away from the accepted shape; the live case runs a
CSV pipeline into MinIO and an `fn: custom` expression reading the same bucket outside the prefix lands
nothing; a second live case registers the bucket output in a local-file DuckLake catalog and reads both rows
back from the lake — red when the setup is not passed; `aWrittenFileOutsideThePrefixIsNotRegistered` is red
when the per-file containment check is removed; both run live 2026-11-01 against MinIO, none skipped. ⚠ A
shared Postgres catalog under `partitioned` was not run live; SKIPS without `INSPECTO_TEST_S3_ENDPOINT`/`_KEY`/`_SECRET`, and a skip is not a pass),
`ConfigSafetyValidatorTest.outputConnectionIsRequiredForAnObjectStoreDatabaseAndRefusedForALocalOne`.

### Quarantine outcomes

* `QUARANTINED_UNREADABLE` — the ingester threw (file unreadable/undecodable).
* `QUARANTINED_MISMATCH` — the ingester emitted **zero** rows (parsed, but nothing came out).
* `SinkFlushException` — a framework/schema error during a generation flush → **fail the batch** (not
  quarantine). Audit is always written regardless (see [ingestion](spec-ingest.md)).

## Object-storage export (S3 / HDFS) — the push post-action and its posture

**Status:** the push post-action **SHIPPED 2026-09-24** (`EXPORT-1`, built on the operator's request by
name that day; the row is closed). Discussed 2026-08-28; the options analysis below is kept as the record
of why this shape and not another.
**Related:** [jobs](../processor/spec-jobs.md) (the Job framework it runs in) · [dataset consumption / the `dataset` connector](../acquire/spec.md)
(the inbound mirror of this question) · [connectors](../connectors/spec-connectors.md) ·
[operations reference](../../layer/platform/admin-guide-operations-reference.md).

### As built (2026-09-24) — `objectstore.export`

A built-in **Job Type**, not a pipeline key: a Job with `on_pipeline: <name>` fires on that Pipeline's
batch COMMIT and never on a failed run, which is exactly "after a successful run" — so the pipeline TOON
learns nothing about exports.

```toon
job:
  name: push_orders
  type: objectstore.export
  on_pipeline: orders
  connection: lake
  local_path: orders/database
```

| Param | | Meaning |
|---|---|---|
| `connection` | required | Id of an `s3` Connection (`*_connection.toon`). Its `base_path` = `bucket[/prefix]` is the export root; `username` / `password` are the access key and a `${ENV:…}`-style secret reference, resolved at signing time. ⛔ Credentials never appear in a pipeline or job TOON. |
| `local_path` | required | A directory under the **Space data root** — a store's `database/`, or one Dataset's output directory. |
| `remote_prefix` | optional | Key prefix under the Connection's `base_path`; default = `local_path`. A `.`/`..` segment is refused. |
| `retries` | optional, `3` | Retries per request (0–10), exponential backoff with full jitter (`RetryPolicy`). |

**The seams.**

| Piece | Where | Note |
|---|---|---|
| `ExportConnector` / `ExportConnectorFactory` | `inspecto-acquire` `com.gamma.acquire` | The outbound mirror of `CollectorConnector` / `CollectorConnectorFactory`; `forProfile` is the `ServiceLoader` lookup by `connection.connector`. |
| `S3Connector` implements `ExportConnector` too | `inspecto-connectors` | ONE class serves both directions, so `AwsSigV4` signing and the endpoint/`base_path` rules cannot drift. `stat` = HeadObject, `put` = PutObject streamed from disk with a signed `Content-MD5`. `S3ConnectorFactory` is listed in `META-INF/services/com.gamma.acquire.ExportConnectorFactory`. |
| `ObjectStoreExportJobType` | `inspecto-engine` `com.gamma.job` | Registered as a built-in by `JobService` with the Space `dataDir`. |

**The contract** (pinned by `ObjectStoreExportJobTest` in `inspecto-connectors`, against a JDK
`HttpServer` fake S3 that re-computes each PUT's SigV4 signature and checks the body against the signed
SHA-256 and `Content-MD5`):

- **Idempotent.** A file is skipped when the remote object's size AND ETag equal the local size and MD5.
  A single-part PUT's ETag is the body's MD5; any other ETag shape (multipart, SSE-KMS) never matches, so
  the file is re-sent — the conservative answer, never a false skip.
- **Upload, then manifest.** `_inspecto_export_manifest.json` (job, run id, time, every key + size + MD5)
  is written at the export prefix LAST. A consumer that keys on the manifest never reads a half-delivered
  export.
- **Failure is failure.** A request that still fails after `retries` returns the run `FAILED` and the
  manifest is not written. Files already sent stay (object stores have no transaction); the next run
  skips them.
- **Path-jailed local reads.** `local_path` is contained in the data root by `PathJail.require`; every
  file is re-checked with `PathJail.contains`; a symlink anywhere in the tree refuses the run. All
  refusals fire in the planning pass, before any byte is sent.
- **Skipped locally:** hidden files and `*.tmp` (in-flight atomic writes).
- **Dry run** (`POST /jobs/{name}/trigger?dryRun=true`) stats the remote side and reports what would be
  uploaded; nothing is PUT, no manifest.

**Deliberate limits.**

- ⚠ **Single-part PUT only** — a file over 5 GiB refuses the run up front. Multipart upload is not built;
  engine-written Parquet partitions sit far below it.
- ⚠ A Connection that declares a `tunnel` or `proxy` is **refused**, as `WebhookSink` does: the export
  transport does not dial through either, and silently bypassing an administrator's proxy is worse.
- ⚠ **`local_path` is not in `ConfigSafetyValidator.JOB_PATH_KEYS`.** That list is for keys resolved
  against `SpaceConfigRoot.jobPathBase`; this key resolves against the Space DATA root, so adding it
  there would manufacture the gate/runtime split the list's own javadoc warns about. It is jailed at run
  time. No pipeline key was added, so `ConfigSpecs` / `AcceptedConfigKeys` / the contract JSONs are
  untouched.
- Only `s3` has a transport. GCS (interoperability mode) and HDFS reach it through an S3-compatible
  endpoint; ⛔ never `hadoop-client`.

### The grounded facts (2026-08-28)

1. **Nothing pushed outbound before `objectstore.export`.** Every connector in `inspecto-connectors`
   (S3, GCS, Azure, SFTP, FTP, Kafka, DB-export) was *acquisition-side* — they fetch **in**. Outputs are
   local parquet written by `PartitionWriter`.
2. **`output.ducklake` registers, it does not relocate.** `DuckLakeRegistrar` ATTACHes a DuckLake
   catalog (`DATA_PATH` from config) and registers already-written *local* paths — bytes stay put.
3. **The engine is deliberately local-filesystem-shaped.** Atomic temp+rename writes
   (`AtomicFiles`), marker files, path jails, DuckDB file databases, config-registry directory
   scans, poll-dir semantics. S3 has no atomic rename and no real directories, so a whole *space*
   on S3 (natively or via a FUSE mount) silently changes the meaning of the crash-safe commit
   ordering. **Posture: space (config + working state) local; data tier exported.**
4. Reusable plumbing already in-repo for an outbound build: `AwsSigV4`,
   `AbstractHttpObjectStoreConnector`, and the object-store workbench family.

### Options, by ambition

| Option | Effort | What it gets |
|---|---|---|
| **Operator sync** — `aws s3 sync` / rclone of `data/<store>/database/` on a schedule | zero code, works today | outputs mirrored to S3; consumers read via DuckDB `httpfs` |
| **Push post-action / upload task** — an outbound mirror of the connector SPI: a post-commit uploader (job task or sink option) reusing `AwsSigV4` + the object-store HTTP plumbing | small–medium build | product-managed, per-pipeline "also deliver committed outputs to `s3://…`", audited |
| **DuckLake `DATA_PATH 's3://…'`** — DuckDB writes parquet straight to S3 via `httpfs` | medium — a real re-plumb: `PartitionWriter` owns the local write + swap today | the data tier *lives* on S3, catalog-managed; the space stays local |
| **HDFS directly** | the expensive one — DuckDB has no HDFS support; `hadoop-client` is a huge dependency tree colliding with the lean-SBOM / framework-free non-negotiable; WebHDFS-over-REST is buildable like the other HTTP connectors but only if mandated | — |

### Recommendation of record

If the Hadoop cluster exposes an **S3-compatible gateway** (Ozone S3, MinIO, or the org's object
store), everything reduces to the S3 column — reuse `AwsSigV4`, never take a Hadoop dependency.
Sequence of record was **operator sync first** to prove the consumption pattern, then the **push
post-action** only on demand — the operator asked for it by name 2026-09-24 and it shipped the same day
(§*As built*). `aws s3 sync` / rclone remains a valid zero-code alternative. The full-space-on-S3 idea is recommended *against* for
the semantic reasons in fact 3, not effort.

### The object-store lane, ACCEPTED against a live MinIO (2026-09-15)

`AIRGAP-S3-EXTENSIONS-1` closed on this run. `PartitionWriter.writeToObjectStore` opens with
`DuckDbExtension.ensureLoaded(conn, "httpfs", …)` (`PartitionWriter.java`), and `PartitionWriterObjectStoreTest`
(**4/4 green, 1.15 s**) drove that method against the sandbox's MinIO (`127.0.0.1:9000`, bucket
`inspecto-lakehouse`) with the explicit load in place — the acceptance the row refused to take from a green
reactor, because all four tests `assumeTrue`-skip without an endpoint and had never entered the method since
the load call was added.

* ⚠ **`INSPECTO_TEST_S3_ENDPOINT` is `host:port`, NOT a URL.** The test feeds it straight into
  `SET s3_endpoint=…` and derives `s3_use_ssl` from a `https` prefix; passing `http://127.0.0.1:9000` produced
  `Could not resolve hostname … 'http://http://127.0.0.1%3A9000/…'` and **four 45-second timeouts** that read
  like a dead MinIO. The first run of the day was red for exactly this reason; the code was never at fault.
* What the run does NOT prove: that an **air-gapped** bundle loads `httpfs` from `duckdb-extensions/<plat>/`.
  On this box `ensureLoaded`'s first step (`LOAD httpfs`) succeeds from the developer cache, so the flat-file
  fallback (`-Dduckdb.extension.dir`) was not exercised. That half is packaging's, measured separately
  (`AIRGAP-EXTENSIONS-CI-1`, the staged 10 files; autoload over the flat dir FAILS, explicit `LOAD '<file>'` WORKS).
* Still open, and NOT this row's: there is **no S3 credential/endpoint config surface in `src/main`** — the five
  `SET s3_*` statements live only in the test. Scale-out phase C §5.4 bullet 6 owns it, and its eager-vs-deferred
  resolution posture is a §1 operator call (`BACKLOG.md`). ⚠ As of 2026-09-15. Since 2026-11-01 the ingest
  lane has one — `output.connection` for an object-store `dirs.database` (*Object-store `dirs.database`* above,
  credentials route (a)).
