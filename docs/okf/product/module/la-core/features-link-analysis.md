---
type: Feature
title: "Link Analysis — features"
description: "Link Analysis (LA) lets an analyst explore who is connected to whom in platform Datasets (calls, transfers, devices, accounts), record that exploration as an **Investigation**, run graph algorithms over it, and hand the result on as a sealed dossier, a Case link or a standing detection."
tags: [la-core, link-analysis, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis — features

**Availability:** Offering: every edition from Basic (`linkAnalysis` add-on in `offerings/basic.toon`: `la-storage`, `la-core`, `la-api`, `geo-link`); Advanced adds `la-store-pg`. Basic withholds the Investigation surface (see `docs/EDITIONS.md`).

## What it does

Link Analysis (LA) lets an analyst explore who is connected to whom in platform Datasets (calls, transfers,
devices, accounts), record that exploration as an **Investigation**, run graph algorithms over it, and hand the
result on as a sealed dossier, a Case link or a standing detection. It is an optional, ServiceLoader-discovered
extension of the one host process - no LA server, no LA main class.

The full backend structure is in [Link Analysis backend architecture](architecture.md);
behaviour and decisions in the [feature concept](user-guide.md). This page is the
module-level summary.

## Where it fits

| Module (manifest id) | Path | Role |
|---|---|---|
| `la-core` | `la/inspecto-la-core` | Domain and ports: op-log evaluator, `InvestigationStore` port + filesystem store, Drafts, Graph Run service |
| `la-storage` | `la/inspecto-la-storage` | Versioned Parquet link index, `SqlGraphEngine` / `RoutingGraphEngine` |
| `la-api` | `la/inspecto-la-api` | The `/inv` and `/geo` routes (87), masking, Draft orchestration, gates |
| `geo-link` | `la/inspecto-geo-link` | Bridge to the host: `DatasetProvider`, `CasePort`, `CollectorCoveragePort`, `LinkIndexBuilder`, measures and alert-rule routes |
| `la-store-pg` | `la/inspecto-la-store-pg` | Postgres `InvestigationStoreProvider` (Advanced) |

They sit on two platform-base modules, `graph` (algorithms) and `entity-store` (Entity Types, Entity Lists,
identity facts, `LinkAnalysisSettings`). The engine names no `la-*` class; it reaches LA only through SPIs it owns.

More: [screens](user-guide.md) · [API](api-link-analysis.md) · [architecture](architecture-overview.md).
