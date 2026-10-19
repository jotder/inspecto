---
type: Reference
title: "Risk Scores and Entity Lists — API"
description: "Control-plane routes for Risk Scores and Entity Lists, with their gates."
tags: [scoring, scoring-entity-lists, api]
timestamp: 2026-10-19T00:00:00Z
---

# Risk Scores and Entity Lists — API

Routes for Risk Scores and Entity Lists. Design and internals: [Risk Scores and Entity Lists architecture](architecture-scoring-entity-lists.md).

| Route | Gate | Handler |
|---|---|---|
| `GET /risk-scores/{model}/{entityKey}` | `canWorkIncidents`; existence-hiding 404 | `features/inspecto-scoring/src/main/java/com/gamma/risk/RiskScoreRoutes.java` |
| `POST /risk-scores/preview` | `canWorkIncidents` (+ `canAuthorWorkbench` for unsaved content) | same |
| `GET /entity-lists`, `GET /entity-lists/{id}` | Space access | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java` |
| `POST /entity-lists` | `canManageIncidents` | same |
| `POST /entity-lists/{id}/members` | `canManageIncidents` (may be held) | same |
| `POST /entity-lists/{id}/retire` | `canManageIncidents` (may be held) | same |
| `POST /entity-lists/{id}/match` | Space access (read-shaped POST, 1..5 000 values) | same |
| `POST /entity-lists/{id}/register-dataset` | `canAuthorWorkbench` | same |

Models are authored through `/components/risk-score/{id}`.
