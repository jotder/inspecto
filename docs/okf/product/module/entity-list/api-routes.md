---
type: Concept
title: HTTP routes — module.entity-list
description: Every /api/v1 route the entity-list module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.entity-list`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

7 route(s) registered; 7 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/entity-lists` | GET /entity-lists (generated skeleton) | entity-lists | — | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |
| POST | `/entity-lists` | POST /entity-lists (generated skeleton) | entity-lists | `canManageIncidents` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |
| GET | `/entity-lists/{entitylistId}` | GET /entity-lists/{entitylistId} (generated skeleton) | entity-lists | — | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |
| POST | `/entity-lists/{entitylistId}/match` | POST /entity-lists/{entitylistId}/match (generated skeleton) | entity-lists | — | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |
| POST | `/entity-lists/{entitylistId}/members` | POST /entity-lists/{entitylistId}/members (generated skeleton) | entity-lists | `canManageIncidents` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |
| POST | `/entity-lists/{entitylistId}/register-dataset` | Register an Entity List's sidecar as a Dataset | entity-lists | `canAuthorWorkbench` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |
| POST | `/entity-lists/{entitylistId}/retire` | POST /entity-lists/{entitylistId}/retire (generated skeleton) | entity-lists | `canManageIncidents` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |

## Drift

None — every route here is in the contract.
