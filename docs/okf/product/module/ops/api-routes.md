---
type: Concept
title: HTTP routes — module.ops
description: Every /api/v1 route the ops module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.ops`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

43 route(s) registered; 43 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/findings/{findingId}` | GET /findings/{findingId} (generated skeleton) | findings | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/notes/{noteId}/{noteId2}` | GET /notes/{noteId}/{noteId2} (generated skeleton) | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| GET | `/notes/{noteId}/{noteId2}/attachments` | GET /notes/{noteId}/{noteId2}/attachments (generated skeleton) | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| POST | `/notes/{noteId}/{noteId2}/attachments` | POST /notes/{noteId}/{noteId2}/attachments (generated skeleton) | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| GET | `/notes/{noteId}/{noteId2}/comments` | GET /notes/{noteId}/{noteId2}/comments (generated skeleton) | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| POST | `/notes/{noteId}/{noteId2}/comments` | POST /notes/{noteId}/{noteId2}/comments (generated skeleton) | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| GET | `/objects` | GET /objects (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects` | POST /objects (generated skeleton) | objects | `canManageIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}` | GET /objects/{objectId} (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PATCH | `/objects/{objectId}` | Edit an object's operator-mutable fields (priority, severity, assignee, attributes) | objects | `canAdminister` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/ack` | POST /objects/{objectId}/ack (generated skeleton) | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/assign` | POST /objects/{objectId}/assign (generated skeleton) | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/attachments` | GET /objects/{objectId}/attachments (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/attachments` | POST /objects/{objectId}/attachments (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/category` | PUT /objects/{objectId}/category (generated skeleton) | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/comments` | GET /objects/{objectId}/comments (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/comments` | POST /objects/{objectId}/comments (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/findings` | Save a Case's Findings values (collaboration; open to anyone who can see the object) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/graph` | GET /objects/{objectId}/graph (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/impact` | Replace an Incident's or Case's typed financial impact (WS-10) | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| DELETE | `/objects/{objectId}/links` | DELETE /objects/{objectId}/links (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/links` | GET /objects/{objectId}/links (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/links` | POST /objects/{objectId}/links (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/postmortem` | PUT /objects/{objectId}/postmortem (generated skeleton) | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/rca` | POST /objects/{objectId}/rca (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/resolve` | Resolve an Incident, Alert or Case | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/transition` | Move an object through its Workflow | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/analytics` | GET /objects/analytics (generated skeleton) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/by-entity` | GET /objects/by-entity — an Entity's case history | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/rca/templates` | GET /rca/templates (generated skeleton) | rca | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/tags` | GET /tags (generated skeleton) | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags` | POST /tags (generated skeleton) | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| DELETE | `/tags/{tagId}` | DELETE /tags/{tagId} (generated skeleton) | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/{tagId}/rename` | POST /tags/{tagId}/rename (generated skeleton) | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/tags/{tagId}/targets` | GET /tags/{tagId}/targets (generated skeleton) | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/tags/assignments/{assignmentId}/{assignmentId2}` | GET /tags/assignments/{assignmentId}/{assignmentId2} (generated skeleton) | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/assignments/{assignmentId}/{assignmentId2}` | POST /tags/assignments/{assignmentId}/{assignmentId2} (generated skeleton) | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| DELETE | `/tags/assignments/{assignmentId}/{assignmentId2}/{assignmentId22}` | DELETE /tags/assignments/{assignmentId}/{assignmentId2}/{assignmentId22} (generated skeleton) | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/tags/rules` | GET /tags/rules (generated skeleton) | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/rules` | POST /tags/rules (generated skeleton) | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| DELETE | `/tags/rules/{ruleId}` | DELETE /tags/rules/{ruleId} (generated skeleton) | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/rules/{ruleId}/apply` | POST /tags/rules/{ruleId}/apply (generated skeleton) | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/workflows/{workflowId}` | GET /workflows/{workflowId} (generated skeleton) | workflows | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |

## Drift

None — every route here is in the contract.
