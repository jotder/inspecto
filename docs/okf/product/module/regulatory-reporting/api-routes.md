---
type: Concept
title: HTTP routes — module.regulatory-reporting
description: Every /api/v1 route the regulatory-reporting module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.regulatory-reporting`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

11 route(s) registered; 11 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/regulatory-reports` | GET /regulatory-reports (generated skeleton) | regulatory-reports | — | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| POST | `/regulatory-reports` | POST /regulatory-reports (generated skeleton) | regulatory-reports | `canWorkIncidents` | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| GET | `/regulatory-reports/{regulatoryreportId}` | GET /regulatory-reports/{regulatoryreportId} (generated skeleton) | regulatory-reports | — | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| POST | `/regulatory-reports/{regulatoryreportId}/approve` | POST /regulatory-reports/{regulatoryreportId}/approve (generated skeleton) | regulatory-reports | `canApproveChanges` | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| POST | `/regulatory-reports/{regulatoryreportId}/decline` | POST /regulatory-reports/{regulatoryreportId}/decline (generated skeleton) | regulatory-reports | `canApproveChanges` | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| POST | `/regulatory-reports/{regulatoryreportId}/request-approval` | POST /regulatory-reports/{regulatoryreportId}/request-approval (generated skeleton) | regulatory-reports | `canWorkIncidents` | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| POST | `/regulatory-reports/{regulatoryreportId}/retry` | POST /regulatory-reports/{regulatoryreportId}/retry (generated skeleton) | regulatory-reports | `canApproveChanges` | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| GET | `/regulatory-reports/{regulatoryreportId}/verify` | GET /regulatory-reports/{regulatoryreportId}/verify (generated skeleton) | regulatory-reports | — | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| GET | `/regulatory-reports/templates` | GET /regulatory-reports/templates (generated skeleton) | regulatory-reports | — | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| DELETE | `/regulatory-reports/templates/{templateId}` | Delete a Space Report Template | regulatory-reports | `canApproveChanges` | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |
| PUT | `/regulatory-reports/templates/{templateId}` | Author (create or replace) a Space Report Template | regulatory-reports | `canApproveChanges` | `features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java` |

## Drift

None — every route here is in the contract.
