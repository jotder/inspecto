---
type: Reference
title: "Incidents — API"
description: "Control-plane routes for Incidents, with their gates."
tags: [incidents, incidents, api]
timestamp: 2026-10-19T00:00:00Z
---

# Incidents — API

Routes for Incidents. Design and internals: [Incidents architecture](architecture-incidents.md).

Handler: `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java`.

| Route | Gate |
|---|---|
| `GET /objects`, `GET /objects/analytics`, `GET /objects/by-entity` | read, post-filtered by scope |
| `POST /objects` | `canManageIncidents` |
| `POST /objects/{id}/ack`, `/resolve`, `/transition`, `/assign` | `canWorkIncidents` |
| `PUT /objects/{id}/postmortem`, `/category`, `/impact` | `canWorkIncidents` |
| `GET` / `POST` / `DELETE /objects/{id}/links`, `GET /objects/{id}/graph` | scope guard |
| `/objects/{id}/comments`, `/attachments`, `POST /objects/{id}/rca`, `PUT /objects/{id}/findings` | scope guard |
| `PATCH /objects/{id}` | `canAdminister` |
| `GET /objects/{id}` | scope guard |

The capability gate runs before the scope guard: no capability → 403, out-of-scope object → 404.
