---
type: Concept
title: HTTP routes — module.anomaly
description: Every /api/v1 route the anomaly module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.anomaly`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

2 route(s) registered; 2 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/anomaly-scores/{model}/{entityKey}` | The latest Anomaly Score of one entity under a saved anomaly-model, with its explanation and recent runs | anomaly-scores | `canWorkIncidents` | `features/inspecto-anomaly/src/main/java/com/gamma/anomaly/AnomalyScoreRoutes.java` |
| POST | `/anomaly-scores/preview` | Preview one entity's Anomaly Score under a saved or unsaved model, writing nothing | anomaly-scores | `canWorkIncidents` | `features/inspecto-anomaly/src/main/java/com/gamma/anomaly/AnomalyScoreRoutes.java` |

## Drift

None — every route here is in the contract.
