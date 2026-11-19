---
type: Reference
title: "Workflow and SLA — API"
description: "Control-plane routes for Workflow and SLA, with their gates."
tags: [workflow, workflow-sla, api]
timestamp: 2026-10-19T00:00:00Z
item: module.workflow
audience: internal
status: current
verified: 2026-11-18 last-edit
code: platform/inspecto-workflow
---

# Workflow and SLA — API

Routes for Workflow and SLA. Design and internals: [Workflow and SLA architecture](architecture-workflow.md).

| Route | Gate | Handler |
|---|---|---|
| `GET /workflows/{type}` | — | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java` |
| `POST /components/{kind}`, `PUT` / `DELETE /components/{kind}/{id}` (kind `workflow`, `sla-policy`, `escalation-rule`) | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| `POST /components/{kind}/{id}/versions/{v}/restore` | `canAdminister` | same |

`{kind}` is `workflow`, `sla-policy` or `escalation-rule`. A write that would strand live objects in a
removed state answers 409.
