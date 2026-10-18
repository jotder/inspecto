---
type: Reference
title: API Services Catalog
description: The inspecto/api resource services, shared HTTP helpers, interceptors, and async utilities.
resource: inspecto-ui/src/app/inspecto/api/index.ts
tags: [api, services, interceptors, catalog]
timestamp: 2026-06-28T00:00:00Z
---

# API Services Catalog

All services are `@Injectable({providedIn:'root'})`, inject `HttpClient`, return `Observable<T>`, and are
re-exported from the `inspecto/api` barrel. See [API & data conventions](spec-api-and-data.md).

## Resource services

| Service | Backs feature(s) |
|---|---|
| `RunsService` | [runs](../../module/engine/user-guide-runs.md), [run-detail](../../module/engine/user-guide-run-detail.md) |
| `CollectorsService` | [collectors](../../module/acquire/user-guide-collectors.md) |
| `ConnectionsService` · `ConnectionProbeService` | [connections](../../module/connectors/user-guide-connections.md) |
| `PipelinesService` · `ComponentsService` | [pipelines](../../module/etl/user-guide-pipeline-editor.md), [components](spec-components.md) |
| `CatalogService` | [catalog](user-guide-catalog.md) |
| `EventsService` | [events](../../module/event/user-guide-events.md) |
| `AlertsService` | [alerts](../../addon/incidents/user-guide-alerts.md) |
| `ObjectsService` | [objects (cases/issues)](../../module/ops/user-guide-objects.md) |
| `EnrichmentService` | [enrichment](../../module/etl/user-guide-enrichment.md) |
| `JobsService` | [jobs](../../module/processor/user-guide-jobs.md) |
| `AcquisitionMetricsService` · `ReportsService` | [dashboard](user-guide-dashboard.md), reports |
| `ConfigService` | [config](../../module/config/user-guide-config.md), [model-settings](../../module/agent/user-guide-model-settings.md) |
| `AssistService` | [assist](../../module/intelligence/user-guide-assist.md) |
| `HealthService` | connectivity probe (`/health`) |
| `SpacesService` | [multi-space scoping](spec-multi-space.md), [spaces](user-guide-spaces.md) |
| `IconMapService` | [icon-settings](user-guide-icon-settings.md) |

## Shared helpers

* `api-base.ts` — `apiUrl('/path')`, `toParams({…})`.
* `auto-refresh.ts` — `visibleInterval(ms)` (pauses while the tab is hidden) for live-tail/polling.
* `optimistic.ts` — `optimisticMutate({…})` (see [forms & state](spec-forms-state.md)).
* `connectivity.service.ts` — signals driving the [connectivity banner](spec-ds-connectivity-banner.md).
* `models.ts` — shared DTO interfaces.

## Interceptors (registered in `app.config.ts`)

* `errorInterceptor` — global error → connectivity / per-screen handling ([errors & connectivity](spec-errors-connectivity.md)).
* `spaceInterceptor` — multi-space path rewrite ([multi-space](spec-multi-space.md)).
