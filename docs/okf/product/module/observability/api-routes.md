---
type: Concept
title: HTTP routes — module.observability
description: Every /api/v1 route the observability module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.observability`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

8 route(s) registered; 8 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/events` | GET /events (generated skeleton) | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/{eventId}` | GET /events/{eventId} (generated skeleton) | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/export` | GET /events/export (generated skeleton) | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/search` | GET /events/search (generated skeleton) | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/views` | GET /events/views (generated skeleton) | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| POST | `/events/views` | POST /events/views (generated skeleton) | events | `canAuthorWorkbench` | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| POST | `/events/views/{viewId}/delete` | POST /events/views/{viewId}/delete (generated skeleton) | events | `canAuthorWorkbench` | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/metrics` | GET /metrics (generated skeleton) | metrics | — | `features/inspecto-observability/src/main/java/com/gamma/metricsapi/MetricsRoutes.java` |

## Drift

None — every route here is in the contract.
