---
type: Feature
title: "Workflow and SLA — features"
description: "Authored **workflows** (the states and actions an Incident or Case may take), **SLA Policies** (response and resolution targets per priority, on a business calendar) and **Escalation Rules** (what happens on a breach or at an age)."
tags: [workflow, workflow-sla, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Workflow and SLA — features

**Availability:** Offering: add-on `workflowSla` in `offerings/standard.toon` — `status: built`, module `ops`. The models are built (`ASSURE-WORKFLOW-SLA-1`, closed 2026-10-04); see §9.

## What it does

Authored **workflows** (the states and actions an Incident or Case may take), **SLA Policies** (response and
resolution targets per priority, on a business calendar) and **Escalation Rules** (what happens on a breach
or at an age). The pure models live in `platform/inspecto-workflow` (module id `workflow`, "Workflow and SLA
models", `offeringRole: base`); the runtime sweep lives in `features/inspecto-ops`.

- `platform/inspecto-workflow/src/main/java/com/gamma/workflow/Workflow.java` — definition, built-ins, validation, `apply`.
- `platform/inspecto-workflow/src/main/java/com/gamma/workflow/SlaPolicy.java` — targets + calendar (`addWorkingMinutes`).
- `platform/inspecto-workflow/src/main/java/com/gamma/workflow/EscalationRule.java` — trigger `BREACH` | `AGE`, priority ladder `LOW < MINOR < MAJOR < CRITICAL`.
- `platform/inspecto-workflow/src/main/java/com/gamma/workflow/SlaDecisions.java` — deadline and breach decisions.

## Where it fits

The models are platform base; they are applied to `INCIDENT` and `CASE` objects by `ObjectService` in
`features/inspecto-ops` (`sweepIncidentSla`), with authored files loaded by `GovernanceRegistry`. Without
`inspecto-ops` there are no objects to govern and `GET /workflows/{type}` answers 503. See
[incidents](../../addon/incidents/architecture-incidents.md) and [case-management](../case-management/architecture-overview.md).

More: [screens](user-guide-workflow-sla.md) · [API](api-workflow-sla.md) · [architecture](architecture-workflow-sla.md).
