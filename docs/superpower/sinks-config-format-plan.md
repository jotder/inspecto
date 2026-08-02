# Plan — the plural `sinks:` config-format (unblocks Stage A of the branch-aware executor)

> **Status: IN FLIGHT (opened 2026-08-02).** Prerequisite named by
> [`branch-aware-executor-plan.md`](branch-aware-executor-plan.md) §0 decision 2 and §5 Stage A steps 3–4.
> Stage A's executor machinery is **already shipped as dormant** (`BatchGraphRunner`, `finalizeSource`,
> the `engages` predicate — commits `318acf2a`/`6965f6f3`); it stays dormant because **no ingest config can
> express more than one destination today**. This plan closes that gap. When it lands, the dormant predicate
> flips on with **no executor rework**.

## 0. The gap (why this is the true prerequisite)

A `*_pipeline.toon` names exactly **one** output destination, and that destination is split across two
records:

- `PipelineConfig.Dirs.database` — the write-root **path** (`inspecto-etl/.../PipelineConfig.java:47`).
- `PipelineConfig.Output` — `{format, compression, ducklake}`, **no path**
  (`inspecto-etl/.../PipelineConfig.java:109`).

Every write site reads *where* from `cfg.dirs().database()` and *how* from `cfg.output().format()/.compression()/.duckLake()`
(`BatchIngestStrategy.writeAndTrace:84`, `PartitionWriter.write`, `PipelineLift.sinkBaseConfig:216`,
`DuckLakeRegistrar:42`). So a second destination is a `{database, format, compression, ducklake}` **tuple**,
not merely a second `Output`. `PipelineEditable.lower` polices this today: it refuses `MULTI_SINK` when the
persistent sink nodes name **> 1 distinct `database` dir** (`PipelineEditable.java:200`). The limit is
**one destination per pipeline** — a config-format gap, not an executor gap (executor branch-commit is
shipped, T11/T12).

## 1. Decisions of record

1. **Shape = a top-level plural `sinks:` list** on `*_pipeline.toon`, each entry a
   `{database, format, compression, ducklake}` destination tuple. In the `.toon` codec a list-of-records is
   the **indexed-tuple** form `key[N]{col,…}:` followed by `N` comma-separated rows (the same syntax as
   `raw.fields[3]{name,selector,type}:`), e.g.:
   ```toon
   sinks[2]{database,format}:
     /data/hot,PARQUET
     /data/archive,CSV
   ```
   Per branch-aware-executor-plan §0.2: *not* a graph-native config (that would reverse W5), *not* the B8
   chain-pipelines workaround.
   → ⚠ **`ducklake` per sink is not expressible in the flat tuple row** (it is a nested map). Authoring a
   per-destination DuckLake block needs either a nested-block `.toon` representation or the editor slice (4);
   the model + engine support it (a `Sink.duckLake` populated via `fromMap`), only the flat-`.toon` authoring
   of it is deferred. The common case — plain `{database, format[, compression]}` destinations — authors fine.

2. **Single `output:` + `dirs.database` stays the one-destination shorthand.** When `sinks:` is absent the
   parser **synthesizes a one-element list** `[Sink(dirs.database, output.format, output.compression,
   output.ducklake)]`. `PipelineConfig.sinks()` therefore **always returns ≥ 1 element**; the existing single
   accessors (`dirs().database()`, `output()`) keep working unchanged and remain element 0. Every existing
   config is byte-for-byte unaffected.

3. **Semantics on the flat ingest path = fan-out / replicate (mirror), not route.** A flat `*_pipeline.toon`
   cannot author `route:`/`derive:` predicates (the editor refuses them), so every sink node is fed by a
   `data` edge and receives the **whole** batch relation. `sinks:` = "write this batch to N destinations"
   (e.g. a hot store *and* an archive, or two formats). This is exactly what the shipped predicate
   `BatchGraphRunner.dataFedSinkCount(g) > 1` engages on. **Row-routing** (different rows → different sinks)
   stays a graph-authored (`*_flow.toon`) concern and is out of scope here.
   → *If the operator actually wants per-sink row selection on flat ingest, the `Sink` record grows an
   optional `where`/selector field and this becomes a routed fan-out — flagged, not assumed. The tuple in
   decision 1 is the common core either way.*

4. **`Sink` carries no `id`/`name` in v1.** Destinations are positional; the shorthand has no name to
   preserve. A name can be added later (additive) if the UI or lineage needs to label a destination.

## 2. What a second destination must satisfy (read-site inventory)

Grep of `cfg.output()` + `cfg.dirs().database()` (the choke points a fan-out must feed once per sink):

| Site | Reads | Role |
|---|---|---|
| `BatchIngestStrategy.writeAndTrace:84–115` | `output().format()/.compression()`, `dirs().database()` | the single `PartitionWriter.write` — **the fan-out point** |
| `PipelineLift.sinkBaseConfig:216–230` | format, compression, ducklake, database | stamps the shared `sinkBase` onto every sink node |
| `DuckLakeRegistrar:42` | `output().duckLake()` | per-destination DuckLake registration |
| `DuckDbRecordSink:119,274` · `MetadataGraphBuilder:52–53` · `DecisionRuleApplier:140` | format / database | catalog + record-sink wiring |

`EnrichmentConfig.Output` (`inspecto-engine/.../enrich/EnrichmentConfig.java:97`) is a **different, same-named**
record for Stage-2 enrichment — do **not** conflate; out of scope.

## 3. Slice sequence

Each slice is independently verifiable; the predicate stays **dormant** until slice 3.

### Slice 1 — model + parse + safety-validation (SAFE / dormant) — *SHIPPED (uncommitted)*
- `PipelineConfig.Sink(String database, String format, String compression, Map<String,Object> duckLake)`
  + `List<Sink> sinks()` (always ≥ 1) + builder plumbing.
- `PipelineConfigParser`: parse top-level `sinks:`; when absent, synthesize the 1-element shorthand list.
- **Not-yet-executable gate:** a `sinks().size() > 1` config **fails loud** instead of silently writing one
  destination. → *Refined in slice 2:* the gate started in `resolveSinks` (construction) but **moved to
  `PipelineConfig.prepare()`** (the execution-load step reached only via `load()`), so a multi-sink config is
  **constructible + liftable** (editor / `PipelineLift` must represent it) yet **not runnable**. `prepare()`
  is airtight because every engine path loads via `load()`→`prepare()` (`ConfigRegistry`, `CollectorService`,
  `CollectorProcessor`, …) while the editor/compiler/draft paths use the pure `fromMap`; it catches a
  hand-edited `.toon` the HTTP-only `ConfigSafetyValidator` would miss. Removed in slice 3.
- `ConfigSafetyValidator`: per `sinks[i]` run the existing `checkPath` (path-jail on `database` +
  `ducklake.data_path`), `checkOutput` (format/compression allow-list), `checkDuckLake`; plus a `sinks`
  422 "not-yet-executable" finding for the HTTP authoring path.
- **Not touched:** `ConfigSpecs`/`ConfigJsonSchema` — there is **no list-of-records spec machinery**
  (`ConfigSpecs` is flat dotted-path `FieldSpec`s; `LIST` is list-of-scalars). Structural spec + JSON-schema
  for `sinks:` is deferred to slice 4 (the editor slice), which is the only consumer of that metadata. Until
  then the editor cannot author `sinks:` (correct — it's gated).
- → *verify:* single `output:` ⇒ `sinks()` == the shorthand tuple; a 1-entry `sinks:` parses + validates; a
  2-entry `sinks:` is rejected with the named error; a `sinks[i].database` outside the path-jail is a Finding.
  Flat-path `PipelineConfigParser`/`BatchProcessor` tests unchanged.

### Slice 2 — lift per-sink config into the graph (SAFE / dormant) — *SHIPPED (uncommitted)*
- `PipelineLift`: `sinkBaseConfig(cfg)` → `sinkConfig(Sink, cfg)`; `branch` fans the map out to one
  `sink.persistent` node per `cfg.sinks()` destination (each with its own database/format/compression/
  ducklake) instead of one shared `sinkBase`. A 1-element `sinks()` keeps id `sink<suffix>` and is
  byte-for-byte what it is today; N destinations get ids `sink<suffix>__d{i}`, all fed by `data` off the map.
- Gate relocation (see slice 1) lands here so a 2-sink config is constructible and reaches the lift.
- → *verified:* `PipelineLiftTest.liftsSinksListToADataFedFanOut` — a 2-sink `sinks:` lifts to two
  `sink.persistent` nodes with distinct `database` cfg, both `data`-fed off the map, `engages(g) == true`;
  `liftsSingleSchemaToLinearChain` (sink id `sink`, store `mini`) is the byte-for-byte guard.

### Slice 3 — multi-destination fan-out on the ingest path (FLIPS IT ON) — *implemented (uncommitted)*

**Mechanism decision (operator, 2026-08-02): direct fan-out, not `BatchGraphRunner`.** Threading the shipped
Stage A executor through ingest hit two impedance mismatches — (a) it finalises *internally* while ingest
finalises separately in `BatchProcessor.commit`→`finalizeSource` (would need an "already-finalised" flag), and
(b) `PartitionSinkWriter` wants a flow-job `partitions:` cfg while ingest derives partitions from the schema
via `PartitionDef`. And it buys no safety: the ingest commit model is *already* "write everything, then
finalise once," and `finalizeSource`'s steps (backup / markers-LAST / ledger) are **per-source-file**, so they
run exactly once regardless of destination count. So `BatchGraphRunner` stays the flow-job executor, unused by
ingest. Predicate = **`cfg.sinks().size() > 1`** (not the whole-graph `engages`, which miscounts multi-schema).

- **`BatchIngestStrategy.writeAndTrace`** (the shared choke point for CSV-Java, CSV-native-union, and plugin-
  union paths): fans the main partitioned write out to every `cfg.sinks()` destination — each under its own
  `database` root (the `dbDir` suffix beyond `dirs.database` is preserved) and its own `format`/`compression`.
  A single destination (the `output:` shorthand) is byte-for-byte the legacy write. Decision Rules run **once**
  (side effects), so the whole `writeAndTrace` is *not* re-run per destination.
- **Streaming-path guards:** the paths that bypass `writeAndTrace` (native single-member `streamingIngest`/
  `chunkedIngest`; plugin `GenerationModeIngester`) materialise instead when `sinks>1` — `CsvBatchStrategy`
  routes single-member native through `unionStreamingIngest`; `StreamingPluginBatchStrategy` forces union mode.
  (A chunking-sized input + multiple destinations thus materialises — rare, documented.)
- **Gate:** the blanket `prepare()` N>1 refusal + the `ConfigSafetyValidator` 422 are removed; `prepare()` now
  refuses **only** a versioned reference store (`reference.load: upsert|scd2`) with `sinks>1` (its single
  version history is ill-defined across destinations). Decision-rule *routing* + `sinks>1` is refused at
  runtime in `writeAndTrace` (routed outputs are single-destination). Both are documented follow-ups.
- → *verified:* `BatchProcessorSinksTest.fanOutWritesEachDestinationAndFinalisesOnce` — one batch → a file
  under **each** destination database, one backup / one marker / one SUCCESS batch row. `BatchProcessorTest`
  (5) is the byte-for-byte flat-path guard. `PipelineConfigSinksTest` covers the runnable-plain /
  refused-reference split; `ConfigSafetyValidatorTest` covers per-sink path-jail/allow-list.
  → ⚠ *toon-authoring gotcha:* in the indexed-tuple form, a `database` path (contains `:` and `/`) must be
  **quoted** in the row — `"/data/hot",PARQUET` — or the tabular decoder reads 0 rows.

### Slice 4 — lift the editor `MULTI_SINK` refusal — *implemented (uncommitted)*
- `PipelineEditable.lower`: >1 distinct `database` no longer refuses `MULTI_SINK` — it emits a plural
  `sinks:` list (distinct destinations, keyed by database; the single `output:`/`dirs.database` shorthand
  stays consistent with the first). Safe because row-routing can't reach the check: a `transform.route`/
  `derive` node is not `LOWERABLE`, so it already fails `UNSUPPORTED_NODE` — every sink here is a data/
  schema-dispatch fan-out that `sinks:` (replicate-per-destination) represents faithfully. The `MULTI_SINK`
  constant/vocabulary is kept (never emitted now). Mock `pipeline-editable.ts` mirrors the change exactly.
- **`ConfigSpecs`/`ConfigJsonSchema` spec deferred (not required):** `ConfigLoader.validate` only checks
  *declared* fields and **ignores unknown keys**, so a lowered `sinks:` config round-trips + saves without a
  spec. The spec is authoring-UX only (field metadata / JSON-schema) — a follow-up alongside the ducklake-in-
  `.toon` representation.
- → *verified:* `PipelineEditableTest.twoDistinctDatabasesLowerToASinksList` (lower emits a 2-element
  `sinks:` list); `ControlApiFlowCrudTest.twoDistinctDatabasesSaveAsAMultiSinkPipeline` (a 2-database graph
  `PUT`s 200, not 422). No UI spec pinned the old mock refusal.

## 4. Gotchas
- ⚠ **No silent-drop.** Until slice 3, a `sinks().size() > 1` config must **fail at load**, never write only
  element 0.
- ⚠ **B9 stands:** multi-sink commit is **not** cross-branch transactional — a clone may have some
  destinations committed and others retrying. `sinks:` does not change that.
- ⚠ **Markers-LAST** crash-ordering is preserved by `BatchProcessor.finalizeSource`; the fan-out reuses it,
  finalizing **once** after all sink branches commit (T11 `BranchCommitCoordinator`).
- ⚠ `Sink` is a new `@PublicApi` surface — get the key layout (decision 1) confirmed before it ships
  user-visible; slice 1 is dormant + uncommitted precisely so the shape stays correctable.

## 5. Docs to update when slices land
`okf/backend/engine/ingestion.md` (the fan-out write path) · `okf/backend/pipeline-graph/pipeline-graph-design.md`
(§ the `MULTI_SINK` lift) · `GLOSSARY.md` (Sink / destination) · `BACKLOG.md` §6 · `INDEX.md` ·
`branch-aware-executor-plan.md` Stage A steps 3–4 (flip to shipped).
