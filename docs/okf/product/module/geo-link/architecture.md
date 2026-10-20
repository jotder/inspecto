---
type: Concept
title: Geo and link adapters — architecture
description: inspecto-geo-link as the bridge between the host-free Link Analysis modules and the platform, the ports it implements and how it is discovered.
resource: la/inspecto-geo-link/
tags: [module, geo-link, link-analysis, spi, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Geo and link adapters — architecture

`inspecto-geo-link` (module id `geo-link`) is the **bridge**: since LA separation D-1 step 5b,
[la-core](../la-core/index.md) and [la-api](../la-api/index.md) are written against ports and never import an
engine type. This module is the one place that knows both worlds — it implements the ports over the engine,
the object store and the outbound machinery, and holds the routes that need both.

## Ports and contracts it provides (all via `ServiceLoader`)

| Contract (`META-INF/services/...`) | Implementation | Over |
|---|---|---|
| `com.gamma.la.core.DatasetProvider` | `EngineDatasetProvider` | engine `DatasetRead`, `QueryExecutor`, `ConditionSql`; maps records field-for-field |
| `com.gamma.la.core.CasePort` | `HostCasePort` | host `ObjectAccess` (filled by the optional ops module) |
| `com.gamma.la.core.CollectorCoveragePort` | `HostCollectorCoveragePort` | each Pipeline's `batches` and `lineage` audit CSVs, attributed to its Collector |
| `com.gamma.la.core.OutboundPort` | `HostOutboundPort` | Connection (`https`) + `EgressPolicy` + the edition's `WebhookSinkTransport` |
| `com.gamma.la.core.UploadLandingPort` | `HostUploadLandingPort` | Pipelines whose Collector sets `collector.accepts_upload: true` and polls a local directory |
| `com.gamma.linkindex.LinkIndexBuilder` | `ScheduledLinkIndexBuilder` | binds the `link-index` Platform Service to la-core's `ScheduledIndexBuild` |
| `com.gamma.alert.InvestigationMeasureProbe` | `WorkingSetMeasures` | evaluates an Alert Rule's Measure over an Investigation's Working Set |
| `com.gamma.spi.http.RouteModule` | `InvestigationMeasureRoutes`, `InvestigationDataChangesRoutes`, `IntegrationWebhooks`, `EmbedExpirySweepModule` | routes and background hooks started with the API |

Files: `la/inspecto-geo-link/src/main/resources/META-INF/services/`. Manifest:
`la/inspecto-geo-link/src/main/resources/META-INF/inspecto/module.toon` (`features: geoLink, investigations`,
`requires: la-api, la-core`, `bindingTime: boot`).

## Request paths

- **Measure → Alert Rule → Incident** (LA-23): `GET …/measures` computes the Working Set Measures;
  `POST …/alert-rules` binds an ordinary `alert-rule` armed in the running `AlertService`. A sweep has no
  caller, so a binding beside the Investigation (rule hash + owner) carries the gate; `WorkingSetMeasures`
  evaluates nothing else. A rule over the live Dataset additionally needs `POST …/standing-detection`, which
  re-decides the owner's authority at every sweep.
- **Corrected-day warning** (LA-DATA-TRUST-1 S5): `GET …/data-changes` selects `collector.delivery.anomaly`
  Signals for Pipelines that wrote the Dataset (`DataChanges`), newer than the analyst's last look; no
  evidence answers `assessed:false`.
- **Signed webhooks** (LA-EDITIONS-1 phase 6): a process-wide `EventLog` tap; delivery through `OutboundPort`
  on a virtual thread, HMAC-SHA256 signature, no entity ids, audited as `link.webhook.delivered` / `.failed`.
- **Embed expiry sweep**: a daemon timer every 10 minutes over Spaces with `geoLink` on.

Diagram of the whole Link Analysis stack: [la-core architecture](../la-core/architecture.md).

## Editions

Default-reactor module (root `pom.xml`) and bundled in **every** Edition, Basic included
(`tools/bundle-modules.mjs` `from: 'all'`; `offerings/basic.toon` add-on `linkAnalysis`). The Investigation
surface is still gated in Basic by the `investigations` edition feature, which only the Standard-and-up
`inspecto-ops` declares; the Geo map routes (`GeoRoutes`) are gated the same way by their own `geoMap` edition feature
(D-LAE5, 2026-10-20), never through `geoLink`. Outbound delivery needs a `WebhookSinkTransport` (`inspecto-notify-channels`,
Standard and up).

## References

- [spec](spec.md) · [admin-guide](admin-guide.md) · [plan](plan.md) · [api-routes](api-routes.md)
- [la-core architecture-overview](../la-core/architecture-overview.md)
