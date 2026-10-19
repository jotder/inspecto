---
type: Concept
title: HTTP routes — module.geo-link
description: Every /api/v1 route the geo-link module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.geo-link`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

7 route(s) registered; 7 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/inv/investigations/{investigationId}/alert-rules` | GET /inv/investigations/{investigationId}/alert-rules (generated skeleton) | inv | — | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java` |
| POST | `/inv/investigations/{investigationId}/alert-rules` | POST /inv/investigations/{investigationId}/alert-rules (generated skeleton) | inv | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java` |
| PUT | `/inv/investigations/{investigationId}/alert-rules/{alertruleId}` | PUT /inv/investigations/{investigationId}/alert-rules/{alertruleId} (generated skeleton) | inv | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java` |
| GET | `/inv/investigations/{investigationId}/data-changes` | GET /inv/investigations/{investigationId}/data-changes (generated skeleton) | inv | — | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationDataChangesRoutes.java` |
| GET | `/inv/investigations/{investigationId}/measures` | GET /inv/investigations/{investigationId}/measures (generated skeleton) | inv | — | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java` |
| POST | `/inv/investigations/{investigationId}/standing-detection` | POST /inv/investigations/{investigationId}/standing-detection (generated skeleton) | inv | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java` |
| DELETE | `/inv/investigations/{investigationId}/standing-detection/{standingdetectionId}` | DELETE /inv/investigations/{investigationId}/standing-detection/{standingdetectionId} (generated skeleton) | inv | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java` |

## Drift

None — every route here is in the contract.
