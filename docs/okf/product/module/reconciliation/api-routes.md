---
type: Concept
title: HTTP routes — module.reconciliation
description: Every /api/v1 route the reconciliation module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.reconciliation`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

10 route(s) registered; 10 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| POST | `/recon/{reconId}/breaks/status` | POST /recon/{reconId}/breaks/status (generated skeleton) | recon | `canOperateRuns` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/{reconId}/record` | POST /recon/{reconId}/record (generated skeleton) | recon | `canOperateRuns` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| GET | `/recon/{reconId}/state` | GET /recon/{reconId}/state (generated skeleton) | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/breaks` | The paged Break sets of one anchor-relative pair | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/columns` | POST /recon/columns (generated skeleton) | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/promote` | POST /recon/promote (generated skeleton) | recon | `canManageIncidents` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| GET | `/recon/promoted` | GET /recon/promoted (generated skeleton) | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/rows` | POST /recon/rows (generated skeleton) | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/run` | One day of the Board: a page of grain rows + the day's exact totals and Break summary | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| GET | `/recon/state` | GET /recon/state (generated skeleton) | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |

## Drift

None — every route here is in the contract.
