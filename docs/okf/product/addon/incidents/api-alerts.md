---
type: Reference
title: "Alerts — API"
description: "Control-plane routes for Alerts, with their gates."
tags: [incidents, alerts, api]
timestamp: 2026-10-19T00:00:00Z
---

# Alerts — API

Routes for Alerts. Design and internals: [Alerts architecture](architecture-alerts.md).

All in `AlertRoutes.java`:

| Route | Gate |
|---|---|
| `GET /alerts` | none - no `withCapability` and no `CapabilityManifest` entry; any caller past the AuthN gate (everyone on Basic) |
| `GET /alerts/rules`, `GET /alerts/rules/pending` | none (as above) |
| `POST /alerts/evaluate` | `canOperateRuns` |
| `POST /alerts/{id}/ack`, `POST /alerts/{id}/resolve` | `canWorkIncidents` |
| `POST /alerts/rules`, `PUT /alerts/rules/{name}`, `DELETE /alerts/rules/{name}` | `canAuthorAlertRules` |

Investigation rules are authored only through `POST /inv/investigations/{id}/alert-rules`; the generic routes refuse that shape.
