---
type: Feature
title: "Observability — spec"
description: "Developer reference for features/inspecto-observability: the two RouteModules, edition gating, masking and the seams they read."
tags: [observability, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Observability — spec

Module `features/inspecto-observability`. It owns only the HTTP **read** surface; recording stays in
core (`platform/inspecto-event`, `MetricRegistry`) in every edition. Requirement of record:
[plan](plan-observability.md); route contract: [api](api-routes.md).

## Assembly

- `module.toon`: `buildRole: implementation`, `offeringRole: optional`, `bindingTime: boot`,
  `provides.features: events`, eight routes. Shipped in Standard and above (EDITIONS `CP-13`).
- Two `com.gamma.spi.http.RouteModule` implementations registered through
  `META-INF/services/com.gamma.spi.http.RouteModule`; OpenAPI contributed by
  `META-INF/inspecto/openapi.fragment.json`.
- Absent module: core `AbsentModuleRoutes` answers every listed path with 503 and the module's
  `absentMessage`; `/bootstrap` reports `features.events=false`, so the SPA drops the Events nav entry.
  No `if` inside a core route (EDITIONS §Assembly).

## `MetricsRoutes` (package `com.gamma.metricsapi`)

`GET /metrics` returns `MetricRegistry.global().scrape()` as Prometheus text 0.0.4. `/metrics` is in
`ControlApi.PUBLIC_PATHS` (unauthenticated — scrapers carry no token). `/metrics/acquisition` is not
here; it belongs to `AcquisitionRoutes`.

## `EventRoutes` (package `com.gamma.eventsapi`)

Feature id `events`. Reads go through `HostContext.of(api).service().events()` (the Space's
`EventStore`) and `savedViews()`.

| Route | Behaviour |
|---|---|
| `GET /events` | legacy: newest `?limit=` (default 50) from the live-tail ring; on `/api/v1`: cursor page over full retained history, `limit` 1–500 |
| `GET /events/search` | `EventQuery` from `level, type, pipeline, correlationId, q, from, to, limit, offset` |
| `GET /events/export` | JSON list, or `?format=csv`; `type=AUDIT` / `ACCESS_DENIED` gets the audit-shaped CSV |
| `GET /events/{id}` | exact id over buffer + Parquet, else 404 |
| `GET /events/views` | list saved views |
| `POST /events/views`, `POST /events/views/{name}/delete` | gated by `canAuthorWorkbench` — views are server-wide, not per user |

**Masking (D-P8).** Every read passes through `AuditReadMasking.forRequest`; a caller without
`canRevealLinkEntities` gets classified values masked. For a masked caller `q` and `correlationId` are
matched against the masked event, never the raw text, and the page is sliced after that filter.

## Tests

`EventRoutesContractTest`, `MetricsRoutesContractTest`, `ObservabilityRoutesManifestParityTest`
(manifest routes = registered routes), plus real-HTTP `ControlApiEventsTest`,
`ControlApiEventsPageTest`, `ControlApiEventsReadMaskingTest`, `MetricsExpositionTest`,
`SavedViewUnmodelledKeysTest`. Inventory: [tests](tests-inventory.md).
