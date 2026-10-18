---
type: Reference
title: "Collectors — API"
description: "Control-plane routes for Collectors, with their gates."
tags: [acquire, collectors, api]
timestamp: 2026-10-19T00:00:00Z
---

# Collectors — API

Routes for Collectors. Design and internals: [Collectors architecture](architecture-collectors.md).

From `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` and `ConnectionRoutes.java`:

| Route | Gate |
|---|---|
| `GET /collectors` | read |
| `POST /collectors/{id}/notify` | `canOperateRuns`; body ignored, triggers a scan; audited `collector.notified` |
| `GET /metrics/acquisition` | read |
| `GET /connections`, `GET /connections/{id}` | read |
| `POST /connections`, `PUT /connections/{id}`, `DELETE /connections/{id}` | `canOnboardConnections` |
| `POST /connections/{id}/test`, `POST /connections/test`, `POST /connections/{id}/probe` | `canOnboardConnections` |
| `GET /connections/{id}/explore`, `GET /connections/{id}/sample` | read |

Gates are listed in `platform/inspecto-access/src/main/java/com/gamma/access/CapabilityManifest.java`.
