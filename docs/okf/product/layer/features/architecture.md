---
type: Concept
title: Feature modules layer — architecture
description: Every optional feature module, what it adds to the host, how it is discovered and gated per Space, and which Edition stages it.
resource: features/
tags: [layer, features, modules, editions, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Feature modules layer — architecture

A **feature module** (`offeringRole: optional` in its manifest) adds a whole product capability — routes,
config kinds, Job Types, store families — to the host. A feature an Edition lacks is a module that Edition does
not bundle; the core keeps no `if (edition)` branch. Modules whose Product Map Item has
`parent: layer.features` (`product-map/module/*.toon`) live under `features/`.

## How a feature module plugs in

1. **ServiceLoader contributions** — `src/main/resources/META-INF/services/` registers the module's
   implementations of host contracts: `com.gamma.spi.http.RouteModule` for its routes, and as needed
   `JobTypeProvider`, `ComponentKindValidator`, `ConsequenceProvider`, `MaintenanceTaskProvider`,
   `ObjectEngineProvider` and others.
2. **Manifest** — `src/main/resources/META-INF/inspecto/module.toon` declares `provides.features` (the
   per-Space switch name), `routes`, `configKinds`, `jobTypes`, `contracts`, `requires.modules`, and an
   `absentMessage` the host returns when the module is not installed. The host loads every manifest
   (`platform/inspecto-util/src/main/java/com/gamma/module/ModuleManifests.java`) and serves them at
   `GET /modules` (`inspecto/src/main/java/com/gamma/control/ModulesRoutes.java`).
3. **Per-Space switch** — installed is not enabled: `PUT /settings/modules` turns a module on or off per Space.
   Its routes are gated, and its non-HTTP work (Jobs, background sweeps, maintenance tasks) checks
   `inspecto/src/main/java/com/gamma/control/ModuleGate.java` at every tick.
4. **Build and Edition** — listed in the root `pom.xml` default `<modules>` (always compiled) or only in the
   `edition-standard` / `edition-advanced` / `edition-preview` profiles. The bundle floor is the `from:` of
   `tools/bundle-modules.mjs`; `offerings/*.toon` lists modules and add-ons per Edition (`docs/EDITIONS.md`).

## The modules

| Module hub | Directory | Adds | Requires | Reactor | Staged from |
|---|---|---|---|---|---|
| [entity-list](../../module/entity-list/index.md) | `features/inspecto-entity-list` | Entity Lists, `/entity-lists*`, `WatchListFeed` | entity-store | default | Basic (all) |
| [ops](../../module/ops/index.md) | `features/inspecto-ops` | Alerts/Incidents/Cases/Tasks objects, notes, links, tags; `workflow`, `sla-policy`, `escalation-rule` kinds; `create-incident` consequence; the `investigations` edition feature | — | edition profiles | Standard |
| [case-management](../../module/case-management/index.md) | `features/inspecto-case-management` | Case Rules, merge/split, `/cases/from-entities`, `caserule.evaluate` | ops | edition profiles | Standard |
| [action-requests](../../module/action-requests/index.md) | `features/inspecto-action-requests` | four-eyes `/action-requests*`, `invoke-api` consequence | notify-channels | edition profiles | Standard |
| [regulatory-reporting](../../module/regulatory-reporting/index.md) | `features/inspecto-regulatory-reporting` | `/regulatory-reports*` | ops | edition profiles | Standard |
| [screening](../../module/screening/index.md) | `features/inspecto-screening` | `/screening*`, `screening.run` | entity-list, ops | edition profiles | Standard |
| [scoring](../../module/scoring/index.md) | `features/inspecto-scoring` | `risk-score` kind, `risk.score`, `/risk-scores*` | entity-list | edition profiles | Standard |
| [anomaly](../../module/anomaly/index.md) | `features/inspecto-anomaly` | `anomaly-model` kind, `anomaly.score`, `/anomaly-scores*` | entity-list | edition profiles | Standard |
| [reconciliation](../../module/reconciliation/index.md) | `features/inspecto-reconciliation` | `reconciliation` kind, `recon.run`, `/recon/*` | — | edition profiles | Standard |
| [exchange](../../module/exchange/index.md) | `features/inspecto-exchange` | cross-Space offers, grants, `/exchange/*` | — | edition profiles | Standard |
| [observability](../../module/observability/index.md) | `features/inspecto-observability` | `GET /metrics`, `/events*` read API (recording stays core) | — | edition profiles | Standard |
| [backup](../../module/backup/index.md) | `features/inspecto-backup` | backup / verify / restore maintenance tasks | — | edition profiles | Standard |
| [agent](../../module/agent/index.md) | `features/inspecto-agent` | embedded assist agent (`AssistAgent`), staged as a shaded sidecar | — | default | Standard |
| [intelligence](../../module/intelligence/index.md) | `features/inspecto-intelligence` | `/agent/*` intelligence agent (`IntelligenceAgent`), sidecar with ONNX natives | — | default | Advanced |

Preview (`-Pedition-preview`) bundles every optional module for testing; its `pom.xml` list is static and
must be kept in step by hand (comment in the root `pom.xml`).

## Dependencies between features

Each manifest declares its `requires.modules`; `GET /modules` reports every module with its `requires`,
`state` and `reasons`. The chain worth knowing: case-management, regulatory-reporting and screening need
`ops`; scoring, anomaly and screening need `entity-list`; action-requests needs the `notify-channels`
provider ([providers layer](../providers/architecture.md)).

## References

- [platform architecture-module-taxonomy](../platform/architecture-module-taxonomy.md) — manifest fields and the module taxonomy
- [platform architecture-editions-model](../platform/architecture-editions-model.md) — the Edition model
- [providers layer architecture](../providers/architecture.md)
