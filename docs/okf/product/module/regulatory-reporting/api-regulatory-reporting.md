---
type: Reference
title: "Regulatory Reporting — API"
description: "Control-plane routes for Regulatory Reporting, with their gates."
tags: [regulatory-reporting, regulatory-reporting, api]
timestamp: 2026-10-19T00:00:00Z
---

# Regulatory Reporting — API

Routes for Regulatory Reporting. Design and internals: [Regulatory Reporting architecture](architecture-overview.md).

| Route | Capability |
|---|---|
| `GET /regulatory-reports/templates` | read |
| `GET /regulatory-reports[?status=&template=&caseId=&incidentId=]` | read |
| `GET /regulatory-reports/{id}` | read (with `sourceChanged`, `approverCheck`) |
| `POST /regulatory-reports` | `canWorkIncidents` |
| `POST /regulatory-reports/{id}/request-approval` | `canWorkIncidents` |
| `POST /regulatory-reports/{id}/approve`, `/decline`, `/retry` | `canApproveChanges` |
| `GET /regulatory-reports/{id}/verify` | re-hashes the delivered file against its `.sha256` sidecar |
| `PUT`, `DELETE /regulatory-reports/templates/{id}` | `canApproveChanges` |

Draft gates, in order: capability -> write root 503 -> unknown body key 422 -> template unknown or broken 422 ->
not exactly one of `caseId` / `incidentId` 422 -> unread or non-scalar input 422 -> no operational objects 503 ->
subject absent or out of scope 404 -> a member Incident out of scope 403 -> drop directory refused 422 -> render
refused 422 -> saved `draft`.
