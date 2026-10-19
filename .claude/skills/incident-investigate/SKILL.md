---
name: incident-investigate
description: >
  Investigate an Incident, Alert, failed Run, quarantined file or exception in Inspecto. Trigger on
  "why did this run fail", "investigate incident/alert", "what raised this alert", "exception in the
  logs", "files quarantined", "triage". Routes through the addon.incidents, module.ops, module.event,
  module.observability, module.engine and layer/platform troubleshooting hubs, with the Control API
  routes to query and a symptom → hub table.
---

# incident-investigate — from symptom to cause

Vocabulary: **Incident**, **Alert Rule**, **Run**, **Collector** (`docs/GLOSSARY.md`).
Routes below are in `docs/api/openapi-v1.json` or the cited hub doc; confirm there before calling.

## Read first (hubs under `docs/okf/product/`)

| Topic | Hub / slot file |
|---|---|
| Incident model, lifecycle, permissions | `addon/incidents/architecture-incidents.md`, `addon/incidents/plan-incidents.md` |
| Alerts + Alert Rules | `addon/incidents/architecture-alerts.md`, `addon/incidents/user-guide-alerts.md` |
| Objects (Incident/Case store, workflows, findings) | `module/ops/user-guide-objects.md` |
| Events, signals | `module/event/architecture-overview.md`, `module/event/spec-events-metrics.md`, `module/event/spec-signal-backbone.md`, `module/event/user-guide-events.md` |
| Observability / backup | `module/observability/architecture-observability-backup.md` |
| Runs and run detail | `module/engine/user-guide-runs.md`, `module/engine/user-guide-run-detail.md`, `module/engine/spec-step-park-drain.md` |
| Known failure modes | `layer/platform/architecture-troubleshooting.md`, `layer/platform/architecture-gotchas.md` |
| Operating, flags | `layer/platform/admin-guide-operations-reference.md`, `layer/platform/admin-guide-launch-flags.md` |

## Control API routes

| Question | Route |
|---|---|
| Is the server healthy? | `GET /health`, `GET /health/details` |
| Which Runs, what state? | `GET /runs`, `GET /runs/runs/{runId}`, `GET /runs/{runId}/report` |
| Why did a Run fail? | `GET /runs/{runId}/errors`, `/quarantine`, `/files`, `/batches`, `/retries`, `/pending`, `/lineage` |
| Recover a Run | `POST /runs/{runId}/reprocess`, `/drain`, `/replay-rejects`, `/retries/retry-now`, `/pause`, `/resume` |
| Job runs + logs | `GET /jobs/runs`, `GET /jobs/{jobId}/runs`, `GET /jobs/{jobId}/runs/{runId}/logs`, `GET /jobs/metrics` |
| Events around the time | `GET /events`, `GET /events/search`, `GET /events/{eventId}` |
| Operational counters | `GET /metrics`, `GET /metrics/acquisition` |
| Alerts | `GET /alerts`, `GET /alerts/rules`, `POST /alerts/evaluate`, `POST /alerts/{alertId}/ack`, `POST /alerts/{alertId}/resolve` |
| Incidents (objects API) | `GET /objects`, `GET /objects/{id}`, `GET /objects/{id}/graph`, `POST /objects/{id}/transition`, `/assign`, `/rca`; `PUT /objects/{id}/findings`, `/postmortem` |
| Agent triage history | `GET /agent/triage-runs`, `GET /agent/triage-runs/{triageRunId}/similar` |

Recovery and transition routes mutate state — confirm with the operator before calling them. Use the
`smoke` skill to boot a local ControlApi for probing. Handlers:
`features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java`,
`inspecto/src/main/java/com/gamma/control/AlertRoutes.java`,
`features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java`.

## Symptom → hub

| Symptom | Start at |
|---|---|
| Run FAILED / stuck / parked | `module/engine/user-guide-run-detail.md`, `module/engine/spec-step-park-drain.md` |
| File quarantined, wrong schema, parsed=0 | `layer/platform/architecture-troubleshooting.md`, `module/etl/architecture-parsing-ingest.md` |
| Output in `year=NULL/...` partitions | `layer/platform/architecture-troubleshooting.md` |
| Collector picks nothing up / re-pick | `module/acquire/architecture-collectors.md`; troubleshooting (`.processed` markers) |
| JVM crash / native access / out of memory | `layer/platform/architecture-troubleshooting.md`, `layer/platform/admin-guide-launch-flags.md` |
| Config refused at save | `pipeline-author` skill, `module/config/spec-config-safety.md` |
| Alert fired / did not fire | `addon/incidents/architecture-alerts.md` |
| Incident transition refused | `addon/incidents/architecture-incidents.md` (workflow actions), `module/ops/user-guide-objects.md` |
| Missing or odd events | `module/event/spec-events-metrics.md` |
| Sign-in / OIDC failure | `module/oidc/` hub, `layer/platform/admin-guide-operations-reference.md` |

## Procedure

1. Pin the object: Run id, Alert id or Incident id, and the time window.
2. Pull state (`/runs/{runId}/report` or `/objects/{id}`) and errors; correlate with `/events/search`.
3. Match the symptom in the table; read that hub file before forming a hypothesis.
4. Reproduce locally (dry run per `pipeline-author`) or with a failing unit test (`test-author` skill).
5. Record the cause: Incident findings / RCA, and a `docs/BACKLOG.md` row if code must change.
