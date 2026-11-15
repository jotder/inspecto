---
type: Reference
title: Cross-Cutting Gotchas
description: The non-obvious backend pitfalls that are expensive to rediscover — collected from PROJECT_NOTES §4.
resource: docs/PROJECT_NOTES.md
tags: [gotchas, pitfalls, toon, duckdb, mdc, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Cross-Cutting Gotchas

* **TOON schema serialization** — `ConfigCodec.toToon(map)` round-trips a Java-constructed `List<Map>`
  (`fields`/`rules`) back through the strict `ConfigCodec.toMap` (`ComponentStoreTest.tabularFieldsRoundTripThroughToon`;
  every shipped example in `ShippedExamplesRoundTripTest`). The old warning — that it emits no tabular form and
  the re-read throws *"Array length mismatch"* — no longer holds. The live
  trap is the strict decode: a tabular row whose width disagrees with its header (an unquoted
  `DECIMAL(18,2)`) is refused, naming the line and the quoted fix. See [TOON config](../../module/config/spec-toon-config.md).
* **DuckDB reserved words** — `day` is a keyword: alias it (`run_day`) in SQL; quote `"trigger"` too. Watch
  this whenever generating SQL with date/trigger columns. See [DuckDB](../../module/engine/spec-duckdb.md).
* **`ConsignmentEvent.pipeline()` is the LOWERCASED pipeline name** (`cfg.identity().pipelineName()`). Any name
  matching against it (triggers, `runPipeline`, `pathFor`) must use the lowercased id — e.g.
  `runPipeline("up_stream")`, not `"UP_STREAM"`.
* **Synchronous bus + a held run claim ⇒ never dispatch inline** — the
  [event bus](../../module/event/spec-events-metrics.md) publishes synchronously on the publishing thread, and that
  thread holds the emitting pipeline's `PipelineRunGuard` claim. An event-triggered run dispatched **inline**
  blocks forever on the claim its own thread holds; hand off to the off-bus virtual-thread pool
  (`triggerWorkers` / `JobService.submit`). See [jobs](../../module/processor/spec-jobs.md).
  <br>⚠ *This rule outlived the thing it was named after.* Until 2026-08-01 it was the global `ingestLock`,
  a `ReentrantLock` — and because that lock was **re-entrant**, an inline same-pipeline run would not have
  deadlocked at all: it would have silently re-entered and **double-ingested the inbox**, which is worse than
  a hang. `PipelineRunGuard` is a non-reentrant binary semaphore precisely so the failure is loud. The
  hand-off is what makes the claim mean anything either way.
* **The per-space `space` MDC must reach EVERY worker thread on the execution path.** Singleton routing reads
  the MDC on the *current* thread, and MDC does **not** cross thread-pool boundaries. Each executor running
  ingest/commit work must `MDC.getCopyOfContextMap()` on the caller + `setContextMap` on the worker +
  `clear()` in `finally` — `MultiCollectorProcessor.runAll`/`runConfigs` **and** `CollectorProcessor`'s per-batch
  executor. Miss one and that space's metrics/events silently fall back to `"default"`. See
  [multi-space](../../module/processor/architecture.md).
* **Pipeline-internal paths have two bases, neither of them the JVM CWD (inside a Space).** Until 2026-09-23
  `grammar` and `dirs.*` were bare `Paths.get(...)` against the CWD (history). Today:
  **schema references (`schema_file`, `schemas[].schema_file`, `segments`, `grammar`, `mapping_file`) go
  through `PipelineConfigParser.resolveSchemaRef` → `PathJail.resolveConfigRef`: beside the config file**
  (W1b made it config-relative first; `SCHEMA-FILE-RESOLVES-AGAINST-CWD-1` removed the CWD second on
  2026-09-23), and **data paths (`dirs.*` and the other data paths `PathJail.resolveDataPath` lists) resolve
  under the Space directory** `spaces/<id>/` (`DATA-DIRS-RESOLVE-AGAINST-CWD-1`, via
  `PipelineConfigParser.dataPaths` → `PathJail.dataPath`). ⚠ The two bases are the trap — in one config under
  `spaces/demo/config/orders/` a bare `orders_schema.toon` means the file beside the pipeline while
  `dirs.poll: inbox` means `spaces/demo/inbox`; a data path that repeats the Space's own path
  (`spaces/demo/data/…`) is refused. With **no Space** (`PathJail.spaceDirOf` is null: a single-tenant example
  or an in-memory draft) data paths keep the CWD reading.
  ⚠ ⚠ Anything that *validates* a schema reference must mirror `resolveSchemaRef` or it will reject configs
  the engine runs. `ConfigRoutes.schemaFileFindings` is an **ERROR** gate at registration and takes a
  `configDir` for exactly this reason. **W3 (2026-08-17) closed the spurious-warning half**: `POST /config/write`
  now runs the check *after* it resolves the target path (passing `target.getParent()`), `POST /validate` passes
  the write root as the draft's prospective home, and `PipelineGraphRoutes`' graph-save and `PipelineSettingsRoutes`' save-as-template both pass
  their own target's parent. ⚠ The 2-arg `schemaFileFindings` overload still exists and still means "CWD only" —
  it is correct for a lowered graph or template body that is not landing anywhere, and **wrong** for anything
  whose directory is known. Prefer the 4-arg form.
* **`PartitionWriter` requires non-empty partition columns** (it emits `PARTITION_BY (...)`). The unpartitioned
  single-file `COPY` path is `PartitionSinkWriter.writeUnpartitioned()`. See [output & sinks](../../module/engine/spec-output-sinks.md).
* **Pipeline seed must be ≥ 1 `source_store`** — `PipelineJobRunner.seedsOf` throws on zero; multi-source merge is the
  `transform.merge` path (the Phase-A "exactly one" rule was relaxed in Phase C). See
  [Pipeline live execution](../../module/engine/spec-live-execution.md).
* **Source→Collector rename (2026-07-14) residuals — what deliberately did NOT move.** <!-- vocab-allow: names the rename itself -->
  The acquisition entity is **Collector** (`CollectorConnector` SPI, `CollectorService`, routes
  `/collectors`) and the Catalog origin node is **Stream** (`NodeKind.STREAM`, id `stream:<pipeline>`,
  ex `source:`). The pipeline **TOON config key is `collector:`** — `PipelineConfigParser` reads only
  `collector:`, so a legacy `source:` block is silently ignored (no alias; zero committed `.toon` files
  carry one; `PipelineEditable.collectorKey` still writes back to whichever key a file uses). What stayed:
  the `'SOURCE'` pipeline **stage category** (`NodeCategory.SOURCE`, the `acquisition` node type's
  category). Don't "fix" that to match the glossary.
* **KPI is deliberately NOT renamed to Measure** (Flow→Pipeline backend rename, 2026-06-30). <!-- vocab-allow: names the rename itself --> The
  backend has no BI "Metric" concept — its semantic construct is **KPI** (`kpis:` in `*_meta.toon`,
  `KpiMeta`, `NodeKind.KPI`), a *distinct* canonical term ("a single-number Measure with a
  target/threshold"). The only `Metric*` types are ops ones (`MetricRegistry`, `MetricsService`,
  `AcquisitionTelemetry`) — also kept.
