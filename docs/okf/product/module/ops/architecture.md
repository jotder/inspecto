---
type: Concept
title: Case management and objects — architecture
description: How the operational-object module is wired into the host — provider discovery, the per-Space engine, the four stores, the event log and the seams other modules use.
resource: features/inspecto-ops/src/main/java/com/gamma/ops/OpsEngineProvider.java
tags: [ops, architecture, objects]
timestamp: 2026-10-19T00:00:00Z
---

# Case management and objects — architecture

The domain design (lifecycles, promotion paths, Cases) is in
[architecture-incidents](../../addon/incidents/architecture-incidents.md) and
[plan-incidents §3](../../addon/incidents/plan-incidents.md). This page covers how the module plugs in.
Classes: [spec](spec.md).

## Where it sits

`inspecto-ops` is an optional module bound at boot, staged in Standard and every higher Edition
([`offerings/standard.toon`](../../../../../offerings/standard.toon), add-ons `incidents` and `workflowSla`).
It was moved out of mandatory core on 2026-09-08 (EDG-01 cell 7).

`OpsEngineProvider` is found through `ServiceLoader`. A bundle without the jar has no provider, so the host holds no
engine and every operational-object surface answers 503 with the module's absent message. `OpsEditionFeatures`
also declares Alert Rules and the Link Analysis Investigation surface present wherever this module ships.

## Request path

```
Alert Rule fires / Decision Rule create-incident / POST /objects
                 |
                 v
     ObjectService (per Space) -- Workflow per ObjectType (built-in, *_workflow.toon, registry/workflows/)
       |        |        |        |
   OBJECTS    LINKS    NOTES    TAGS        (one store each, own DuckDB file or PostgreSQL)
       |
       +--> EventLog: OBJECT_OPENED / OBJECT_ACTIVITY / OBJECT_LINKED / OBJECT_SLA_BREACH
       |
       +--> sla-sweep (background) · objects.analytics (Job) · incident_purge (maintenance)
```

- **Objects mutate; events do not.** Objects live in a table store and are the source of truth; every change is
  also recorded on the append-only event log, so history survives an object's purge.
- **Links are immutable facts** (append-only edges such as `CONTAINS`, `ESCALATED_FROM`, `CAUSED_BY`).
- **Governance is live.** `GovernanceRegistry` re-reads authored Workflows, SLA policies and Escalation Rules on
  change, keeping the last valid version of a file that stops validating.

## Seams for other modules

| Seam | Used by |
|---|---|
| `ObjectEngineExtension` (`ServiceLoader`) | `inspecto-case-management` loads `*_caserule.toon` |
| `LinkedSubjectProvider` (`OpsLinkedSubjects`) | Action Requests raised from an Incident or a Case |
| `ConsequenceProvider` (`create-incident`) | Decision Rules; absent module reports the action `unavailable` |
| `JobTypeProvider`, `MaintenanceTaskProvider` | Job service and maintenance library |
| `GET /objects/by-entity` (`EntityHistory`) | Entity case history for Link Analysis and Entity profile pages |
