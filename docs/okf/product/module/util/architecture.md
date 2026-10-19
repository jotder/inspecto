---
type: Feature
title: "Utilities, DuckDB access, TOON helpers, module manifests — architecture"
description: "Why the utility module sits at the bottom of the reactor and how its main seams are shaped."
tags: [util, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Utilities, DuckDB access, TOON helpers, module manifests — architecture

## Position

`inspecto-util` is a foundation module: every other module may depend on it, it depends on none of them. That
is why cross-cutting state such as `CurrentSpace` lives here rather than beside its first user. Reactor
layering: [architecture-reactor](../../layer/platform/architecture-reactor.md).

## Main seams

- **Connection borrowing.** A DB-backed store asks a `ConnectionSource` for a connection for ONE operation
  (scale-out phase A). `SingleConnectionSource` serves an embedded DuckDB file; `SharedPoolConnectionSource`
  borrows from the one PostgreSQL pool per process that `JdbcDrivers` holds. `StoreHealth` records what each
  store family actually resolved to, so a silent in-memory fallback shows on `GET /health/details`.
- **DuckDB caps.** Each config's own value wins; when blank, the `-Dprocessing.duckdb.*` flag applies; when
  neither is set DuckDB's own default stays. Engine detail: [engine spec-duckdb](../engine/spec-duckdb.md).
- **Fail-closed switches.** `Topology`, `LakehouseCatalog` and `OperationsZone` resolve a JVM property and
  refuse to guess: an unknown topology, a half-configured catalog pair or an unknown zone stops the boot.
  In `partitioned` mode a store that cannot open its backend fails the boot instead of degrading to memory.
- **Module manifests.** Each module ships `META-INF/inspecto/module.toon`; `ModuleManifests` reads them all
  and `ModuleActivator` computes ACTIVE/INERT by fixpoint, so an Edition is assembled by which jars are
  present ([architecture-module-taxonomy](../../layer/platform/architecture-module-taxonomy.md)).
- **Crash-safe files.** `AtomicFiles` stages then renames, so readers never see a half-written config.

Details: [spec](spec.md).
