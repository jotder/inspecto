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
  (`Executors.newVirtualThreadPerTaskExecutor()`). See [Control API](./control-plane/control-api.md).
* **Manual dependency injection** — collaborators (`SpaceManager`, `CollectorService`, `JobService`,
  `EventLog`, `MetricRegistry`) are constructed directly and passed as constructor args or reached via
  `static global()` singletons. No annotations, no container.
* **`ServiceLoader` SPIs** — extension points are plain `META-INF/services` SPIs. **22 distinct ones ship
  in `src/main`**, including source [connectors](./acquisition/connectors.md) (`CollectorConnectorFactory`),
  [parser plugins](./engine/parser-plugins.md) (`ParserPlugin`), pipeline
  [node types](./pipeline-graph/pipeline-graph-design.md) (`PipelineNodeType`, plus `PipelineNodeExecutor`),
  the [assist agent](./agent/assist-agent.md) (`AssistAgent`), `RouteModule`, `JobTypeProvider`,
  `MaintenanceTaskProvider`, `StepKindRegistry`, `DecompressorPlugin`, `ObjectEngineProvider`,
  `NotificationChannel`, `AccessDecider`, and the `Authenticator` / `Subject` / `TokenRelay` trio
  implemented by `inspecto-security` on Standard ([auth](./editions/auth-security.md)). An absent module
  simply isn't discovered — the no-op path wins. This is what makes
  [editions build flavors](./editions/editions-model.md).
  ⚠ **[`StreamingFileIngester`](./engine/ingestion.md) is NOT one of them** — despite sitting beside them
  conceptually, a plugin ingester is instantiated by **fully-qualified-name reflection** off
  `schemas().ingesterClass()` (`UnionModeIngester.java:181`, `GenerationModeIngester.java:144`), so it has
  no `META-INF/services` entry and is not discovered. *(Corrected 2026-09-08: this bullet used to list it as
  the "ingestion SPI".)*
* **Embedded DuckDB** — bulk ingest via the native Appender API; see [DuckDB](./engine/duckdb.md). Requires the
  `--enable-native-access=ALL-UNNAMED` JVM flag (see [build & run](./build-run/build-test.md)).
* **Virtual threads everywhere** — HTTP requests, the [job](./control-plane/jobs.md) executor, batch
  processing, and multi-source orchestration all run on bounded virtual-thread pools.

## Layered view

* **Acquisition** ([framework](./acquisition/framework.md)) discovers + retrieves files (local or via remote
  [connectors](./acquisition/connectors.md)), with dedup/watermark ledgers and a stability gate.
* **Engine** ([ingestion](./engine/ingestion.md) → [DuckDB](./engine/duckdb.md) →
  [output](./engine/output-sinks.md)) parses, transforms, and writes partitioned output per batch.
* **Control plane** ([API](./control-plane/control-api.md), [events/metrics](./control-plane/events-metrics.md),
  [jobs](./control-plane/jobs.md), [multi-space](./control-plane/multi-space.md)) exposes everything over HTTP
  and schedules work.
* **Pipeline graph** ([design](./pipeline-graph/pipeline-graph-design.md), [live execution](./pipeline-graph/live-execution.md))
  is the authored-Pipeline IR + executor layered on top of the engine.

## Code geography

Since the WS-D reactor split the code spans several Maven modules — **23 today: 14 default + 9
profile-scoped** (rebuild the list from `pom.xml`, which is the only current source).
🔴 **The map this file used to call authoritative is stale**: [reactor.md](./modules/reactor.md) is headed
*"Reactor shape (2026-07-22)"* and mentions `inspecto-ops`, `inspecto-events`, `inspecto-metrics`,
`inspecto-exchange` and `inspecto-geo-link` **zero times** — all five were created by the EDG-01 edition
extractions after it was written. Read it for the *reasoning* behind the split, not for the module list. The **core / composition root** [`inspecto/`](./modules/engine.md)
holds `control/` (HTTP API), `service/` (spaces + host), `assist/spi/`, `report/`, `exchange/`,
`expectation/`, `intelligence/`, `model/` and ships the fat JAR. The **engine** was extracted below it:
`etl/` (ingest/transform/output) → `inspecto-etl`; `event/` + `metrics/` → `inspecto-event`; `acquire/`
(acquisition) → `inspecto-acquire`; and `inspector/` (batch coordination), `pipeline/` (pipeline graph +
components), `query/` (query catalog), `job/`, `signal/`, `enrich/`, `catalog/`, `alert/`,
`notify/`, `ingester/` → `inspecto-engine`. Foundation leaves: `api/` → `inspecto-api`, `util/` (DuckDB
access + I/O helpers) → `inspecto-util`, `config/` → `inspecto-config`, the SQL sandbox → `inspecto-sql`.
⚠ **`ops/` is no longer in `inspecto-engine`**: EDG-01 cell 7 (2026-09-08) moved the whole `com.gamma.ops`
domain into the optional **`inspecto-ops`** module, reached from core through the `ObjectAccess` SPI plus a
host-declared `ObjectEngineProvider` — which is why Personal 503s the `/objects|/notes|/queues|/tags`
families ([editions model](./editions/editions-model.md)). `notify/` is likewise split: the core interfaces
stay in `inspecto-engine`, the channels ship in `inspecto-notify-channels`.

For the platform-wide layer model — the full package inventory, composition and lifecycle, the SPI surface
and the two event buses — see the deep reference [architecture layers](./architecture-layers.md).
(The `ura` CLI `MainApp` moved out of `util/` to `inspector/`, so it now ships in `inspecto-engine`.)
