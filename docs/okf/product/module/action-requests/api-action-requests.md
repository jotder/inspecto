---
type: Reference
title: "Action Requests — API"
description: "Control-plane routes for Action Requests, with their gates."
tags: [action-requests, action-requests, api]
timestamp: 2026-10-19T00:00:00Z
---

# Action Requests — API

Routes for Action Requests. Design and internals: [Action Requests architecture](architecture-overview.md).

| Route | Gate |
|---|---|
| `GET /action-requests[?status&incidentId&caseId]` | `canWorkIncidents` or `canApproveChanges`; capped at 500 |
| `GET /action-requests/{id}` | same; 404 when the linked object is not visible |
| `POST /action-requests` | `canWorkIncidents`; payload over 64 KiB is 413 |
| `POST /action-requests/{id}/approve` · `/decline` | `canApproveChanges`, Subject, four-eyes |
| `POST /action-requests/{id}/retry` | `canApproveChanges`; status `failed` |
| `POST /action-requests/{id}/mark-failed` | `canApproveChanges`; `dispatched` and idle |
| `GET|PUT /settings/egress` | PUT `canAdminister` |

A decision body carries only `reason`; any other key is 422, so a decision can never change target, method or payload.
