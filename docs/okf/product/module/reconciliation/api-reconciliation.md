---
type: Reference
title: "Reconciliation — API"
description: "Control-plane routes for Reconciliation, with their gates."
tags: [reconciliation, reconciliation, api]
timestamp: 2026-10-19T00:00:00Z
---

# Reconciliation — API

Routes for Reconciliation. Design and internals: [Reconciliation architecture](architecture-overview.md).

Handler: `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java`.

| Route | Gate | Purpose |
|---|---|---|
| `POST /recon/columns` | — | column inventory + cross-side auto-matches |
| `POST /recon/run` | — | one day of the Board: `{id \| config, day?, limit?, offset?, filter?, sample?}` |
| `POST /recon/breaks` | — | paged Break sets, optional dimension `path` |
| `POST /recon/rows` | — | raw rows behind one key (cardinality evidence) |
| `POST /recon/promote` | `canManageIncidents` | one Break → Incident, deduped on Break identity |
| `GET /recon/promoted` | — | Incidents promoted from a Reconciliation |
| `GET /recon/state`, `GET /recon/{id}/state` | — | recorded state |
| `POST /recon/{id}/record` | `canOperateRuns` | compute and merge all Breaks |
| `POST /recon/{id}/breaks/status` | `canOperateRuns` | `status: resolved \| open \| assigned`, `note?`, `assignee?` |

Authoring uses `/components/reconciliation/{id}` ([component registry](../processor/spec-component-registry.md)).
Fail-closed: write root unset 503, unknown id 404, unusable config or failing query 422.
