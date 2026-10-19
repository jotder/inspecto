---
type: Feature
title: "Workflow and SLA models — plan"
description: "Requirement of record for Workflows, SLA policies, Escalation Rules and maker-checker."
tags: [workflow, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Workflow and SLA models — plan

**Product Map Item:** [`module.workflow`](../../../../../product-map/module/workflow.toon) · **Status:** ga

## Required

Configurable lifecycles for operational objects (Incident, Case, Task), SLA targets counted in a business
calendar, escalation on breach or age, and a second-person approval for config changes. Rows of record in
[BACKLOG](../../../../BACKLOG.md): `ASSURE-WORKFLOW-SLA-1` and `ASSURE-MAKER-CHECKER-1`.

## Built

- `ASSURE-WORKFLOW-SLA-1` — closed 2026-10-04: `workflow`, `sla-policy` and `escalation-rule` component
  kinds, the SLA sweep, fire-once escalation ([architecture-workflow-sla](architecture-workflow-sla.md)).
- `ASSURE-MAKER-CHECKER-1` — shipped 2026-09-27; residual rows `ASSURE-MAKER-CHECKER-MULTIPOD-1` (closed
  2026-09-28: a Job's run-time writes stay outside the hold) and `ASSURE-MAKER-CHECKER-RESIDUALS-1` (closed
  2026-10-06: `channel` and `notification-rule` became governable)
  ([architecture-maker-checker](architecture-maker-checker.md)).

## Left

`ASSURE-WORKFLOW-SLA-RESIDUALS-1` (closed 2026-10-18): item (b), the graphical state-diagram editor, shipped
2026-10-11 (`workflow-state-editor.component.ts`). Item (a), re-checking a
deleted assignee at sweep time, was retired 2026-10-18 because no principal directory exists to ask. The
recorded policy for when it is built: skip the reassign, still notify and raise priority, one WARN per breach.

## Refused

None recorded for this module.
