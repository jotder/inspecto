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
| POST | `/recon/{reconId}/breaks/status` | Resolve, re-open or assign a Reconciliation Break | recon | `canOperateRuns` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/{reconId}/record` | Record a run of a Reconciliation | recon | `canOperateRuns` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| GET | `/recon/{reconId}/state` | Recorded state of one Reconciliation | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/breaks` | The paged Break sets of one anchor-relative pair | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/columns` | Inventory the columns of Reconciliation Datasets | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/promote` | Promote a Reconciliation Break to an Incident | recon | `canManageIncidents` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| GET | `/recon/promoted` | Breaks of a Reconciliation that have an active Incident | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/rows` | Raw rows behind one Reconciliation key | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| POST | `/recon/run` | One day of the Board: a page of grain rows + the day's exact totals and Break summary | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |
| GET | `/recon/state` | Last-run state of every Reconciliation | recon | — | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java` |

## Drift

None — every route here is in the contract.
