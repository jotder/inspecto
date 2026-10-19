---
type: Concept
title: HTTP routes — module.action-requests
description: Every /api/v1 route the action-requests module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.action-requests`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

7 route(s) registered; 7 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/action-requests` | GET /action-requests (generated skeleton) | action-requests | — | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests` | POST /action-requests (generated skeleton) | action-requests | `canWorkIncidents` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| GET | `/action-requests/{actionrequestId}` | GET /action-requests/{actionrequestId} (generated skeleton) | action-requests | — | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/approve` | POST /action-requests/{actionrequestId}/approve (generated skeleton) | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/decline` | POST /action-requests/{actionrequestId}/decline (generated skeleton) | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/mark-failed` | POST /action-requests/{actionrequestId}/mark-failed (generated skeleton) | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/retry` | POST /action-requests/{actionrequestId}/retry (generated skeleton) | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |

## Drift

None — every route here is in the contract.
