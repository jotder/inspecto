---
type: Concept
title: Link Analysis modules - layer architecture
description: Every module of the Link Analysis layer, how each plugs into the host, and where to read more.
resource: la/
tags: [layer, la, link-analysis, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis modules - layer architecture

The Link Analysis layer (`layer.la`) is the set of modules under `la/` whose Product Map Items name `parent: layer.la`
in `product-map/module/`. Link Analysis is an **optional, ServiceLoader-discovered extension of the one host process**:
there is no LA main class and no LA server. The deep as-built design is
[la-core architecture](../../module/la-core/architecture.md); this page is the map.

## Modules

| Module (hub) | Maven module | Role | Plugs in through | Editions |
|---|---|---|---|---|
| [la-core](../../module/la-core/index.md) | `la/inspecto-la-core/` | Domain: Investigation op log and evaluator, `InvestigationStore` port + filesystem store, Drafts, Graph Run, the ports | defines the SPIs (`InvestigationStoreProvider`, `DatasetProvider`, `CasePort`, `CollectorCoveragePort`, `OutboundPort`, `UploadLandingPort`); no registrations | all (`offerings/basic.toon`) |
| [la-storage](../../module/la-storage/index.md) | `la/inspecto-la-storage/` | Versioned Parquet link index: build, read, index-backed graph engine | a library called by la-api; implements la-core's `GraphEngine` | all |
| [la-api](../../module/la-api/index.md) | `la/inspecto-la-api/` | HTTP surface: 87 `/inv` and `/geo` routes, gates, masking, Draft orchestration, indexed reads | `META-INF/services/com.gamma.spi.http.RouteModule` (25 route modules) | all; Investigation routes Standard and above |
| [geo-link](../../module/geo-link/index.md) | `la/inspecto-geo-link/` | Bridge to the host: implements la-core's ports over engine services; scheduled index builds, measures, Alert Rules, standing detection | `RouteModule`, the la-core ports, `LinkIndexBuilder`, `InvestigationMeasureProbe` | all |
| [la-store-pg](../../module/la-store-pg/index.md) | `la/inspecto-la-store-pg/` | PostgreSQL Investigation store for multi-pod deployments | `META-INF/services/com.gamma.la.core.InvestigationStoreProvider`, selected by `-Dinvestigations.backend=db` | Advanced, Preview (`offerings/advanced.toon`) |

Platform modules the layer builds on (not part of it): `platform/inspecto-graph` (pure-Java graph algorithms) and
`platform/inspecto-entity-store` (Entity Types, Entity Lists, identity facts, the `LaLimit` catalogue).

## Dependency direction

```
            host (ControlApi, CollectorService, JobService, AlertService)
                 ▲ ServiceLoader only: RouteModule, ports, LinkIndexBuilder, InvestigationMeasureProbe
   geo-link ─────┤   (depends on la-api, la-core, engine modules)
   la-api ───────┘   (depends on la-core, la-storage)
      │
      ├── la-storage ──> la-core
      ▼
   la-core ──> platform graph, entity-store
   la-store-pg ──> la-core          (found by la-core's InvestigationStores, never named)
```

- **Ports point inward.** la-core declares what it needs; la-storage, geo-link and la-store-pg supply it.
- **The engine names no `la-*` class** (enforced by `tools/check-engine-names-no-la.mjs`). When a jar is absent, the
  host answers its routes 503 `CAPABILITY_UNAVAILABLE` with the manifest's `absentMessage`.
- Each module's metadata of record is its `META-INF/inspecto/module.toon` (for example
  `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon`).

## Enablement

Installed (jar staged by the edition) → Enabled (feature `geoLink` per Space in `modules.toon`) → Permitted
(capability, Investigation membership, Dataset visibility, Advanced PDP). The Investigation surface additionally needs
the `investigations` edition feature, which only `inspecto-ops` declares, so Basic is the Link Analysis explorer only
([la-core architecture](../../module/la-core/architecture.md) §8).

## A request end to end

1. The SPA (console or the `la-app` shell, same host) calls an `/inv` route.
2. la-api gates it, then opens the Space's store through la-core (`fs` or la-store-pg).
3. Reads go to la-storage's index when a fresh index exists, else to the flat Dataset relation that geo-link's
   `EngineDatasetProvider` supplies.
4. Graph Runs go to la-core's `GraphRunService`, which routes by input type to the in-memory or index engine.
5. Output is masked and audited; live detection and scheduled index builds run as host Jobs through geo-link.

## Configuration in one place

Space `link-analysis.toon` and install `la-limits.toon` (every limit, catalogue `LaLimit`), plus JVM properties
`-Dinvestigations.backend` / `-Dinvestigations.db.*` and `-Dla.embed.enabled`. Admin guides:
[la-api](../../module/la-api/admin-guide.md), [la-storage](../../module/la-storage/admin-guide.md),
[la-store-pg](../../module/la-store-pg/admin-guide.md), [geo-link](../../module/geo-link/admin-guide.md).
