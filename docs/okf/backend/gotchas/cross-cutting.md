---
type: Reference
title: Cross-Cutting Gotchas
description: The non-obvious backend pitfalls that are expensive to rediscover — collected from PROJECT_NOTES §4.
resource: docs/PROJECT_NOTES.md
tags: [gotchas, pitfalls, toon, duckdb, mdc, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Cross-Cutting Gotchas

* **TOON schema serialization** — `ConfigCodec.toToon(map)` does **not** emit tabular-array format. A schema
  whose `fields`/`rules` are Java-constructed `List<Map>` round-trips as nested maps and the parser throws
  *"Array length mismatch: declared N, found 0"*. In tests, write the schema as an inline TOON string
  (`fields[N]{name,selector,type}: …`), not via `toToon(schemaMap)`. See [TOON config](../config/toon-config.md).
* **DuckDB reserved words** — `day` is a keyword: alias it (`run_day`) in SQL; quote `"trigger"` too. Watch
  this whenever generating SQL with date/trigger columns. See [DuckDB](../engine/duckdb.md).
* **`BatchEvent.pipeline()` is the LOWERCASED pipeline name** (`cfg.identity().pipelineName()`). Any name
  matching against it (triggers, `runPipeline`, `pathFor`) must use the lowercased id — e.g.
  `runPipeline("up_stream")`, not `"UP_STREAM"`.
* **Synchronous bus + a held run claim ⇒ never dispatch inline** — the
  [event bus](../control-plane/events-metrics.md) publishes synchronously on the publishing thread, and that
  thread holds the emitting pipeline's `PipelineRunGuard` claim. An event-triggered run dispatched **inline**
  blocks forever on the claim its own thread holds; hand off to the off-bus virtual-thread pool
  (`triggerWorkers` / `JobService.submit`). See [jobs](../control-plane/jobs.md).
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
  [multi-space](../control-plane/multi-space.md).
* **Pipeline-internal paths resolve against the JVM CWD, not the space root — `schema_file` excepted since
  2026-07-31.** `grammar` and `dirs.*` are still bare `Paths.get(...)` in `PipelineConfigParser` with no
  rebasing to `spaces/<id>/`, and only the *space discovery* layer (`-Dspaces.root`) is space-relative — so
  those must stay repo/bundle-root-relative, and `SpaceMigrator` cannot auto-fix absolute/author-relative ones.
  **Schema references (`schema_file`, `schemas[].schema_file`, `parsing.plugin.segments`) now go through
  `PipelineConfigParser.resolveSchemaRef`: config-relative first, CWD second** (unification W1b), so a bare
  basename beside the pipeline is portable. ⚠ The mixed model is the trap — in one config file a bare
  `orders_schema.toon` resolves while a bare `dirs.poll: inbox` still means `<CWD>/inbox`.
  ⚠ ⚠ Anything that *validates* a schema reference must mirror `resolveSchemaRef` or it will reject configs
  the engine runs. `ConfigRoutes.schemaFileFindings` is an **ERROR** gate at registration and takes a
  `configDir` for exactly this reason; its two WARNING call sites still pass `null` (a draft has no directory),
  so a portable draft gets a spurious "unresolvable" warning at validate/save until W3 lands.
* **`PartitionWriter` requires non-empty partition columns** (it emits `PARTITION_BY (...)`). The unpartitioned
  single-file `COPY` path is `PartitionSinkWriter.writeUnpartitioned()`. See [output & sinks](../engine/output-sinks.md).
* **Pipeline seed must be ≥ 1 `source_store`** — `PipelineJobRunner.seedsOf` throws on zero; multi-source merge is the
  `transform.merge` path (the Phase-A "exactly one" rule was relaxed in Phase C). See
  [Pipeline live execution](../pipeline-graph/live-execution.md).
* **Source→Collector rename (2026-07-14) residuals — three tokens that deliberately did NOT move.**
  The acquisition entity is **Collector** (`CollectorConnector` SPI, `CollectorService`, routes
  `/collectors`) and the Catalog origin node is **Stream** (`NodeKind.STREAM`, id `stream:<pipeline>`,
  ex `source:`), but: the pipeline **TOON config key `source:`** block is kept (renaming breaks
  authored TOON — a separate migration if ever wanted); the `'SOURCE'` pipeline **stage category** is
  unchanged; `collector.*` pipeline node types were already correct. Don't "fix" these to match the
  glossary.
* **KPI is deliberately NOT renamed to Measure** (Flow→Pipeline backend rename, 2026-06-30). The
  backend has no BI "Metric" concept — its semantic construct is **KPI** (`kpis:` in `*_meta.toon`,
  `KpiMeta`, `NodeKind.KPI`), a *distinct* canonical term ("a single-number Measure with a
  target/threshold"). The only `Metric*` types are ops ones (`MetricRegistry`, `MetricsService`,
  `AcquisitionTelemetry`) — also kept.
