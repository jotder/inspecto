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
| GET | `/events` | Recent operational events | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/{eventId}` | One operational event | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/export` | Export operational events | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/search` | Search operational events | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/events/views` | List saved event views | events | — | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| POST | `/events/views` | Save an event view | events | `canAuthorWorkbench` | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| POST | `/events/views/{viewId}/delete` | Delete a saved event view | events | `canAuthorWorkbench` | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` |
| GET | `/metrics` | Prometheus metrics scrape | metrics | — | `features/inspecto-observability/src/main/java/com/gamma/metricsapi/MetricsRoutes.java` |

## Drift

None — every route here is in the contract.
