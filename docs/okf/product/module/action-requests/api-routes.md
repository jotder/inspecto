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

9 route(s) registered; 9 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/action-requests` | List Action Requests | action-requests | — | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests` | Propose an Action Request | action-requests | `canWorkIncidents` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| GET | `/action-requests/{actionrequestId}` | One Action Request | action-requests | — | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/approve` | Approve an Action Request | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/decline` | Decline an Action Request | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/mark-failed` | Mark a stuck Action Request failed | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| POST | `/action-requests/{actionrequestId}/retry` | Retry a failed Action Request | action-requests | `canApproveChanges` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/ActionRequestRoutes.java` |
| GET | `/incidents/{incidentId}/briefs` | Briefs of an Incident | incidents | `canWorkIncidents` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/IncidentBriefRoutes.java` |
| POST | `/incidents/{incidentId}/briefs` | Build an Incident Brief | incidents | `canWorkIncidents` | `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/IncidentBriefRoutes.java` |

## Drift

None — every route here is in the contract.
