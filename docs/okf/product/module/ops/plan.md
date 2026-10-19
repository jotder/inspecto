---
type: Capability
title: Case management and objects — plan of record
description: Where the requirement of record for the operational-object module (Alerts, Incidents, Cases, Tasks, notes, links, tags) lives, and what the module itself was required to do, built, left and refused.
resource: features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon
tags: [ops, plan, requirements, incidents, cases]
timestamp: 2026-10-19T00:00:00Z
---

# Case management and objects — plan of record

The requirement of record for the Alert → Incident → Case chain is the INC capability spec in the `incidents`
add-on hub: [plan-incidents](../../addon/incidents/plan-incidents.md) (§2 requirements, §3.4 Incidents, §3.5 Cases,
§3.6 Notes and Tags, §3.7 retention). This page records only what is specific to the `ops` module.

## Required

- One operational-object engine (objects, links, notes, tag assignments) over a workflow-driven lifecycle, every
  change emitted to the event log (`OBJECT_OPENED` / `OBJECT_ACTIVITY`) — [admin-guide](admin-guide.md).
- Durable by default (`OBJECTS-BACKEND-DEFAULT-MEMORY-1`, operator 2026-09-25: default `db`, never `memory`).
- Server-side resolve gate for an Incident (I1, 2026-07-24) — [plan-incidents §3.4](../../addon/incidents/plan-incidents.md).
- Retention for Archived Incidents with a fail-safe legal hold (MNT-14) —
  [mnt-14-incident-retention-plan](../../../../archived-documents/plans-archive/mnt-14-incident-retention-plan.md).

## Built

| Item | Where |
|---|---|
| Mail-like three-pane screen for `/incidents` and `/cases` | [user-guide-objects](user-guide-objects.md); [incidents-mail-ui-design](../../../../archived-documents/plans-archive/incidents-mail-ui-design.md) |
| Cases, correlation links and graph | [case-management-design](../../../../archived-documents/plans-archive/case-management-design.md) |
| Moved out of mandatory core into the optional module (EDG-01 cell 7, 2026-09-08) | [spec](spec.md) |
| Authored Workflows, SLA policies and Escalation Rules re-read without restart (ASSURE-WORKFLOW-SLA-1) | [spec](spec.md) |
| `objects.analytics` Job Type materialising rollups as the `ops_analytics` Dataset | [case-analytics-dataset-plan](../../../../archived-documents/plans-archive/case-analytics-dataset-plan.md) |
| `GET /objects/by-entity` — an Entity's case history (2026-10-11) | [plan-incidents §3.4](../../addon/incidents/plan-incidents.md) |
| `create-incident` Decision Rule consequence | [spec](spec.md) |

## Left

Open items are tracked in [BACKLOG](../../../../BACKLOG.md); the INC open list is in
[plan-incidents](../../addon/incidents/plan-incidents.md).

## Refused / decided against

- The `*_escalation.toon` loader — retired (`RETIRE-HALVES-1`, 2026-09-14). Escalation itself came back as the
  `escalation-rules` component kind (Built, above): on breach it can raise priority or reassign and emits `OBJECT_ESCALATED`.
- A shipped default schedule for `incident_purge` — nothing deletes business records unless an operator stands up
  the Job.
- Purging the audit trail with an Incident — the event ledger is append-only (MNT-14 G3).
