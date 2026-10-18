---
type: Architecture
title: Backend Architecture
description: Framework-free design — JDK HttpServer, manual DI, ServiceLoader SPIs, virtual threads, embedded DuckDB.
resource: inspecto/src/main/java/com/gamma
tags: [architecture, framework-free, spi, serviceloader, virtual-threads]
timestamp: 2026-07-07T00:00:00Z
---

# Architecture

Inspecto is deliberately **framework-free**: no Spring, no web framework, no IoC container. The whole engine
+ control plane runs on the JDK plus DuckDB.

## Pillars

* **JDK `HttpServer`** — the control plane is `com.sun.net.httpserver.HttpServer` with a single catch-all
  dispatch context. Each request runs on a fresh **virtual thread**
  (`Executors.newVirtualThreadPerTaskExecutor()`). See [Control API](../../module/processor/api-control-api.md).
* **Manual dependency injection** — collaborators (`SpaceManager`, `CollectorService`, `JobService`,
  `EventLog`, `MetricRegistry`) are constructed directly and passed as constructor args or reached via
  `static global()` singletons. No annotations, no container.
* **`ServiceLoader` SPIs** — extension points are plain `META-INF/services` SPIs. **22 distinct ones ship
  in `src/main`**, including source [connectors](../../module/connectors/spec-connectors.md) (`CollectorConnectorFactory`),
  [parser plugins](../../module/engine/spec-parser-plugins.md) (`ParserPlugin`), pipeline
  [node types](../../module/engine/architecture-pipeline-graph.md) (`PipelineNodeType`, plus `PipelineNodeExecutor`),
  the [assist agent](../../module/agent/spec-assist-agent.md) (`AssistAgent`), `RouteModule`, `JobTypeProvider`,
  `MaintenanceTaskProvider`, `StepKindRegistry`, `DecompressorPlugin`, `ObjectEngineProvider`,
  `NotificationChannel`, `AccessDecider`, and the `Authenticator` / `Subject` / `TokenRelay` trio
  implemented by `inspecto-oidc` on Standard ([auth](../../module/access/admin-guide-auth-security.md)). An absent module
  simply isn't discovered — the no-op path wins. This is what makes
  [editions build flavors](architecture-editions-model.md).
  ⚠ **[`StreamingFileIngester`](../../module/engine/spec-ingestion.md) is NOT one of them** — despite sitting beside them
  conceptually, a plugin ingester is instantiated by **fully-qualified-name reflection** off
  `schemas().ingesterClass()` (one resolver, `PluginIngesters.open`, since 2026-09-25), so it has
  no `META-INF/services` entry and is not discovered. *(Corrected 2026-09-08: this bullet used to list it as
  the "ingestion SPI".)*
* **Embedded DuckDB** — bulk ingest via the native Appender API; see [DuckDB](../../module/engine/spec-duckdb.md). Requires the
  `--enable-native-access=ALL-UNNAMED` JVM flag (see [build & run](spec-build-test.md)).
* **Virtual threads everywhere** — HTTP requests, the [job](../../module/processor/spec-jobs.md) executor, batch
  processing, and multi-source orchestration all run on bounded virtual-thread pools.

## Layered view

* **Acquisition** ([framework](../../module/acquire/architecture-framework.md)) discovers + retrieves files (local or via remote
  [connectors](../../module/connectors/spec-connectors.md)), with dedup/watermark ledgers and a stability gate.
* **Engine** ([ingestion](../../module/engine/spec-ingestion.md) → [DuckDB](../../module/engine/spec-duckdb.md) →
  [output](../../module/engine/spec-output-sinks.md)) parses, transforms, and writes partitioned output per batch.
* **Control plane** ([API](../../module/processor/api-control-api.md), [events/metrics](../../module/event/spec-events-metrics.md),
  [jobs](../../module/processor/spec-jobs.md), [multi-space](../../module/processor/architecture-multi-space.md)) exposes everything over HTTP
  and schedules work.
* **Pipeline graph** ([design](../../module/engine/architecture-pipeline-graph.md), [live execution](../../module/engine/spec-live-execution.md))
  is the authored-Pipeline IR + executor layered on top of the engine.

## Code geography

Since the WS-D reactor split the code spans several Maven modules — **23 today: 14 default + 9
profile-scoped** (rebuild the list from `pom.xml`, which is the only current source).
🔴 **The map this file used to call authoritative was stale and has been rebuilt (2026-10-08):** the current map is [../../index.md](../../index.md) with the classification in [architecture-module-taxonomy.md](architecture-module-taxonomy.md); [reactor.md](architecture-reactor.md) keeps its 2026-07-22 table as marked history. Read reactor.md for the *reasoning* behind the split, not for the module list. The **core / composition root** [`inspecto/`](../../module/engine/architecture.md)
holds `control/` (HTTP API), `service/` (spaces + host), `assist/spi/`, `report/`, `exchange/`,
`expectation/`, `intelligence/`, `model/` and ships the fat JAR. The **engine** was extracted below it:
`etl/` (ingest/transform/output) → `inspecto-etl`; `event/` + `metrics/` → `inspecto-event` (its audit core — `Event`/`EventLog`/`EventType` + `MetricRegistry` — in `inspecto-audit-spi`, D-1 step 2); `acquire/`
(acquisition) → `inspecto-acquire`; and `inspector/` (batch coordination), `pipeline/` (pipeline graph +
components), `query/` (query catalog), `job/`, `signal/`, `enrich/`, `catalog/`, `alert/`,
`notify/`, `ingester/` → `inspecto-engine`. Foundation leaves: `api/` → `inspecto-api`, `util/` (DuckDB
access + I/O helpers) → `inspecto-util`, `config/` → `inspecto-config`, the SQL sandbox → `inspecto-sql`.
⚠ **`ops/` is no longer in `inspecto-engine`**: EDG-01 cell 7 (2026-09-08) moved the whole `com.gamma.ops`
domain into the optional **`inspecto-ops`** module, reached from core through the `ObjectAccess` SPI plus a
host-declared `ObjectEngineProvider` — which is why Personal 503s the `/objects|/notes|/tags`
families ([editions model](architecture-editions-model.md)). `notify/` is likewise split: the core interfaces
stay in `inspecto-engine`, the channels ship in `inspecto-notify-channels`.

For the platform-wide layer model — the full package inventory, composition and lifecycle, the SPI surface
and the two event buses — see the deep reference [architecture layers](architecture-layers.md).
(The `ura` CLI `MainApp` moved out of `util/` to `inspector/`, so it now ships in `inspecto-engine`.)
