---
type: Feature
title: "Workflow and SLA models — spec"
description: "Classes, component files and the host seam of the com.gamma.workflow models."
tags: [workflow, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Workflow and SLA models — spec

Module `platform/inspecto-workflow` (manifest
`platform/inspecto-workflow/src/main/resources/META-INF/inspecto/module.toon`: `buildRole: foundation`,
`offeringRole: base`). Package `com.gamma.workflow`. Pure models and decisions: no store, no events, no
engine. The host (`features/inspecto-ops`, the SLA sweep) adapts its records and applies the decisions.

| Class | Role |
|---|---|
| `platform/inspecto-workflow/src/main/java/com/gamma/workflow/ObjectType.java` | `INCIDENT`, `CASE`, `TASK`. No `ALERT`: rows of an unknown type load inert |
| `platform/inspecto-workflow/src/main/java/com/gamma/workflow/Workflow.java` | State machine per `ObjectType`: `defaultFor`, `load` (TOON), `problems()` validation; states upper-cased, actions lower-cased |
| `platform/inspecto-workflow/src/main/java/com/gamma/workflow/SlaPolicy.java` | Per-priority `responseMinutes` / `resolutionMinutes` (`*` fallback) in a calendar with a required IANA zone |
| `platform/inspecto-workflow/src/main/java/com/gamma/workflow/EscalationRule.java` | `on: breach` (`target: resolution` or `response`) or `on: age` (`afterMinutes`); optional `priority` and `when` condition; actions reassign, notify, raise priority |
| `platform/inspecto-workflow/src/main/java/com/gamma/workflow/GovernedItem.java` | Read view the decisions take: id, kind, status, severity, priority, assignee, open time, attributes |
| `platform/inspecto-workflow/src/main/java/com/gamma/workflow/SlaDecisions.java` | Clock stopped? deadlines to stamp, breach to record, which rule fires, new fire-once ledger |

## Persisted attribute names (contract with data on disk)

`dueAt`, `responseDueAt`, `slaBreachedAt`, `escalations` (entries `<rule>@<breach stamp>`), `escalated`.
They must not change.

## Component files

`registry/workflows/`, `registry/sla-policies/<type>.toon`, `registry/escalation-rules/<id>.toon`; a legacy
`*_workflow.toon` sits between an authored component and the built-in. Example shapes are in the class
Javadoc of `SlaPolicy` and `EscalationRule`.

## Maker-checker

Maker-checker is core control plane, not this module: `inspecto/src/main/java/com/gamma/control/PendingChanges.java`,
`inspecto/src/main/java/com/gamma/control/ApprovalPolicy.java` — see
[architecture-maker-checker](architecture-maker-checker.md).

Tests: [tests-inventory](tests-inventory.md).
