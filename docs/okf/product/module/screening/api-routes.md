---
type: Concept
title: HTTP routes — module.screening
description: Every /api/v1 route the screening module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.screening`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

5 route(s) registered; 5 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| POST | `/screening/check` | Screen subjects against Entity Lists (Match Scores; persists nothing) | screening | — | `features/inspecto-screening/src/main/java/com/gamma/screening/ScreeningRoutes.java` |
| GET | `/screening/hits` | List Screening Hits, newest first | screening | — | `features/inspecto-screening/src/main/java/com/gamma/screening/ScreeningRoutes.java` |
| GET | `/screening/hits/{hitId}` | One Screening Hit | screening | — | `features/inspecto-screening/src/main/java/com/gamma/screening/ScreeningRoutes.java` |
| POST | `/screening/hits/{hitId}/decide` | Decide a Screening Hit (confirm, dismiss or escalate) | screening | `canWorkIncidents` | `features/inspecto-screening/src/main/java/com/gamma/screening/ScreeningRoutes.java` |
| GET | `/screening/hits/{hitId}/reveal` | Reveal a Screening Hit's raw subject and matched entry | screening | `canRevealLinkEntities` | `features/inspecto-screening/src/main/java/com/gamma/screening/ScreeningRoutes.java` |

## Drift

None — every route here is in the contract.
