---
type: Reference
title: "Anomaly Detection — API"
description: "Control-plane routes for Anomaly Detection, with their gates."
tags: [anomaly, anomaly, api]
timestamp: 2026-10-19T00:00:00Z
---

# Anomaly Detection — API

Routes for Anomaly Detection. Design and internals: [Anomaly Detection architecture](architecture-overview.md).

Handler: `features/inspecto-anomaly/src/main/java/com/gamma/anomaly/AnomalyScoreRoutes.java`.

| Route | Gate | Purpose |
|---|---|---|
| `GET /anomaly-scores/{model}/{entityKey}` | `canWorkIncidents` | latest score with Feature explanation; existence-hiding 404 |
| `POST /anomaly-scores/preview` | `canWorkIncidents` (+ `canAuthorWorkbench` for unsaved content) | score one entity now, write nothing |

Authoring uses the generic `/components/anomaly-model/{id}` routes.
