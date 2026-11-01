---
type: Concept
title: HTTP routes — module.predictive
description: Every /api/v1 route the predictive module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.predictive`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

1 route(s) registered; 1 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/predictions/{kind}/{model}/{entityKey}` | The latest prediction of one entity (or forecast series) under a saved Predictive model | predictions | `canWorkIncidents` | `features/inspecto-predictive/src/main/java/com/gamma/predictive/PredictionRoutes.java` |

## Drift

None — every route here is in the contract.
