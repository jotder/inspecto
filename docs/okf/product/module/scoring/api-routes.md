---
type: Concept
title: HTTP routes — module.scoring
description: Every /api/v1 route the scoring module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.scoring`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

2 route(s) registered; 2 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/risk-scores/{model}/{entityKey}` | The latest Risk Score of one entity under a saved risk-score model, with its factors | risk-scores | `canWorkIncidents` | `features/inspecto-scoring/src/main/java/com/gamma/risk/RiskScoreRoutes.java` |
| POST | `/risk-scores/preview` | Preview one entity's Risk Score under a saved or unsaved model, writing nothing | risk-scores | `canWorkIncidents` | `features/inspecto-scoring/src/main/java/com/gamma/risk/RiskScoreRoutes.java` |

## Drift

None — every route here is in the contract.
