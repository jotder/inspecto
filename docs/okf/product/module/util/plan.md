---
type: Feature
title: "Utilities, DuckDB access, TOON helpers, module manifests — plan"
description: "Requirement of record for the shared utility module: what is required, built, left and refused."
tags: [util, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Utilities, DuckDB access, TOON helpers, module manifests — plan

**Product Map Item:** [`module.util`](../../../../../product-map/module/util.toon) · **Status:** ga

## Required

One shared home, with no dependency on any feature module, for the helpers every module needs: DuckDB
connections and caps (NFR-1 throughput, NFR-2 footprint in [REQUIREMENTS](../../../../REQUIREMENTS.md)),
crash-safe file writes (NFR-3 resilience), SQL identifier quoting, TOON reading, module manifests (so an
Edition is assembled from optional modules — `MODULE-REORG-1`), and the deployment-wide switches for
scale-out (topology, shared lakehouse catalog, operations zone).

## Built

- DuckDB access and global caps: `DuckDbUtil`, `ConnectionSource` (borrow-scoped), `JdbcDrivers` (shared
  PostgreSQL pool), `StoreHealth`.
- Files: `AtomicFiles`, `FileBackup`, `FileWalker`, the tar helpers, `CsvLedger`.
- Config: `ToonHelper`, `CanonicalHash`, `DottedPath`, `CronExpression`.
- Security helpers: `SqlIdent`, `egress/EgressPolicy` (from `ASSURE-ACTION-REQUESTS-1`), `SpaceSecretKeys`.
- Module system: `ModuleManifest(s)`, `ModuleActivator`, `KnownModules`, `ModuleStatus`.
- Scale-out switches: `Topology` (phase A), `LakehouseCatalog` (phase C), `OperationsZone`.

## Left

Scale-out rows that touch these switches stay open in [BACKLOG](../../../../BACKLOG.md) under the
`SCALEOUT-*` ids (for example `SCALEOUT-OBJSTORE-DATABASE-DIR-1`); none is owned by this module alone.

## Refused

A served settings key must not also be read from `-D` at use time (split ownership; stated on
`DuckDbUtil`'s global-fallback helper). An unrecognised `-Dinspecto.topology` is never defaulted.
