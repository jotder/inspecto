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
| GET | `/findings/{findingId}` | Effective Findings sections of an object kind | findings | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/notes/{noteId}/{noteId2}` | All notes on a target | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| GET | `/notes/{noteId}/{noteId2}/attachments` | Attachments on a target | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| POST | `/notes/{noteId}/{noteId2}/attachments` | Attach evidence to a target | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| GET | `/notes/{noteId}/{noteId2}/comments` | Comments on a target | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| POST | `/notes/{noteId}/{noteId2}/comments` | Comment on a target | notes | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java` |
| GET | `/objects` | List operational objects | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects` | Open an Incident or other operational object | objects | `canManageIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}` | One operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PATCH | `/objects/{objectId}` | Edit an object's operator-mutable fields (priority, severity, assignee, attributes) | objects | `canAdminister` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/ack` | Acknowledge an operational object | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/assign` | Assign an operational object | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/attachments` | Attachments of an operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/attachments` | Attach evidence to an operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/category` | Set an operational object's category | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/comments` | Comments on an operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/comments` | Comment on an operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/findings` | Save a Case's Findings values (collaboration; open to anyone who can see the object) | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/graph` | Correlation graph around an operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/impact` | Replace an Incident's or Case's typed financial impact (WS-10) | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| DELETE | `/objects/{objectId}/links` | Remove a link between operational objects | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/{objectId}/links` | Correlation links of an operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/links` | Link two operational objects | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| PUT | `/objects/{objectId}/postmortem` | Save an Incident's postmortem | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/rca` | Seed an RCA skeleton on an operational object | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/resolve` | Resolve an Incident, Alert or Case | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| POST | `/objects/{objectId}/transition` | Move an object through its Workflow | objects | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/analytics` | Operational object analytics | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/objects/by-entity` | GET /objects/by-entity — an Entity's case history | objects | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/rca/templates` | List RCA templates | rca | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| GET | `/tags` | List tags | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags` | Create a tag | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| DELETE | `/tags/{tagId}` | Delete a tag | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/{tagId}/rename` | Rename a tag everywhere | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/tags/{tagId}/targets` | Everything carrying a tag | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/tags/assignments/{assignmentId}/{assignmentId2}` | Tags on one target | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/assignments/{assignmentId}/{assignmentId2}` | Apply a tag to a target | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| DELETE | `/tags/assignments/{assignmentId}/{assignmentId2}/{assignmentId22}` | Remove a tag from a target | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/tags/rules` | List Tag Rules | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/rules` | Save a Tag Rule | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| DELETE | `/tags/rules/{ruleId}` | Delete a Tag Rule | tags | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| POST | `/tags/rules/{ruleId}/apply` | Apply a Tag Rule to existing objects | tags | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java` |
| GET | `/workflows/{workflowId}` | Effective workflow of an object kind | workflows | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |

## Drift

None — every route here is in the contract.
