---
type: Feature
title: "Utilities, DuckDB access, TOON helpers, module manifests — spec"
description: "Packages, key classes and system properties of the inspecto-util module."
tags: [util, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Utilities, DuckDB access, TOON helpers, module manifests — spec

Module `platform/inspecto-util` (manifest `platform/inspecto-util/src/main/resources/META-INF/inspecto/module.toon`:
`buildRole: foundation`, `offeringRole: base`). Two packages: `com.gamma.util` and `com.gamma.module`.

## com.gamma.util — key classes

| Class | Role |
|---|---|
| `DuckDbUtil` | Every recurring DuckDB pattern: open, settings, spill directory (`<java.io.tmpdir>/.duckdb_tmp` by default), temp DB cleanup, global `processing.duckdb.*` fallbacks |
| `ConnectionSource`, `SingleConnectionSource`, `SharedPoolConnectionSource` | Where a DB-backed store borrows a connection for one operation |
| `JdbcDrivers` | Driver loading and the shared PostgreSQL pool |
| `AbstractJdbcStore`, `BrowsableStore`, `StoreHealth` | Store base class; what each store family resolved to per Space (feeds `GET /health/details`) |
| `AtomicFiles` | Crash-safe writes: temp sibling, then rename over the target |
| `SqlIdent`, `SqlBuilder` | The one SQL quoting implementation; statement building |
| `ToonHelper`, `CanonicalHash`, `DottedPath` | TOON reading; order-independent SHA-256 of a decoded config |
| `CronExpression`, `Scheduler`, `LockingRunner`, `VirtualThreadRunner` | Scheduling and per-name serialized execution |
| `egress/EgressPolicy` | What hosts and addresses an outbound call may reach |
| `SpaceSecretKeys` | A Space's secret keys in `<config root>.secrets/`, outside every exported tree |
| `CurrentSpace` | The Space the calling thread works in |
| `Topology`, `LakehouseCatalog`, `OperationsZone` | Deployment-wide switches (below) |

## com.gamma.module

`ModuleManifests` loads every `META-INF/inspecto/module.toon` on the class loader, fail-soft per file; each
file starts with a `---` line so the shaded jar's appending transformer keeps them all. `ModuleActivator`
resolves which modules are ACTIVE or INERT (a missing required module or contract makes a dependant inert).
`KnownModules` lists every module in the source tree, installed or not.

## System properties

| Property | Default | Class |
|---|---|---|
| `processing.duckdb.memory_limit` / `.temp_directory` / `.max_temp_directory_size` / `.threads` | unset (DuckDB default) | `DuckDbUtil` — per-config value wins, then the flag |
| `db.pool.size` | `10` | `JdbcDrivers` — per-family borrow cap |
| `db.pool.process.size` | `20` | `JdbcDrivers` — connections in the one shared pool per process per server |
| `db.pool.timeoutMs` | built-in, min 250 ms | `JdbcDrivers` — borrow wait before failing |
| `inspecto.topology` | `single` | `Topology` — `single` or `partitioned`; anything else throws |
| `inspecto.ducklake.catalog` + `inspecto.ducklake.data` | unset | `LakehouseCatalog` — both or neither |
| `ops.timezone` | JVM default | `OperationsZone` — IANA id; unresolvable throws |

Code: `platform/inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java`,
`platform/inspecto-util/src/main/java/com/gamma/util/JdbcDrivers.java`,
`platform/inspecto-util/src/main/java/com/gamma/util/Topology.java`,
`platform/inspecto-util/src/main/java/com/gamma/util/LakehouseCatalog.java`,
`platform/inspecto-util/src/main/java/com/gamma/util/OperationsZone.java`,
`platform/inspecto-util/src/main/java/com/gamma/module/ModuleManifests.java`. Tests: [tests-inventory](tests-inventory.md).
