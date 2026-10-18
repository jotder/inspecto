---
type: Reference
title: "Case Management — API"
description: "Control-plane routes for Case Management, with their gates."
tags: [case-management, case-management, api]
timestamp: 2026-10-19T00:00:00Z
---

# Case Management — API

Routes for Case Management. Design and internals: [Case Management architecture](architecture-overview.md).

Handler: `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java`.

| Route | Gate |
|---|---|
| `GET /cases/rules` | — |
| `POST /cases/rules`, `DELETE /cases/rules/{name}` | `canAuthorWorkbench` |
| `POST /cases/rules/{name}/evaluate` | `canAdminister` |
| `POST /cases/from-entities` | `canManageIncidents` |
| `POST /objects/{id}/merge` (`sources`) | `canAdminister` + data-scope guard |
| `POST /objects/{id}/split` (`title`, `members`) | `canAdminister` + data-scope guard |

State changes, comments, links and findings use the shared object routes in
`features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` (`POST /objects/{id}/transition`,
`/resolve`, `/assign`, all `canWorkIncidents`).
