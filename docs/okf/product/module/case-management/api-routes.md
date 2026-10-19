---
type: Concept
title: HTTP routes — module.case-management
description: Every /api/v1 route the case-management module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.case-management`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

7 route(s) registered; 7 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| POST | `/cases/from-entities` | Open a Case whose first members are minted from Link Analysis Entities | cases | `canManageIncidents` | `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java` |
| GET | `/cases/rules` | GET /cases/rules (generated skeleton) | cases | — | `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java` |
| POST | `/cases/rules` | POST /cases/rules (generated skeleton) | cases | `canAuthorWorkbench` | `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java` |
| DELETE | `/cases/rules/{ruleId}` | DELETE /cases/rules/{ruleId} (generated skeleton) | cases | `canAuthorWorkbench` | `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java` |
| POST | `/cases/rules/{ruleId}/evaluate` | POST /cases/rules/{ruleId}/evaluate (generated skeleton) | cases | `canAdminister` | `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java` |
| POST | `/objects/{objectId}/merge` | POST /objects/{objectId}/merge (generated skeleton) | objects | `canAdminister` | `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java` |
| POST | `/objects/{objectId}/split` | POST /objects/{objectId}/split (generated skeleton) | objects | `canAdminister` | `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java` |

## Drift

None — every route here is in the contract.
