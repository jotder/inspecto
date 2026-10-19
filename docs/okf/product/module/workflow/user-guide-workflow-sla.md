---
type: Feature
title: "Workflow and SLA — screens"
description: "Where Workflow and SLA lives in the UI and what each screen does."
tags: [workflow, workflow-sla, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Workflow and SLA — screens

Screens for Workflow and SLA. Design and internals: [Workflow and SLA architecture](architecture-workflow-sla.md).

Settings → Incident governance:

- `inspecto-ui/src/app/modules/admin/settings/incident-governance.component.ts` — policies, rules, workflows;
- `inspecto-ui/src/app/modules/admin/settings/workflow-diagram.component.ts` — graphical state-diagram editor;
- `inspecto-ui/src/app/modules/admin/settings/workflow-state-editor.component.ts`;
- SLA badges in the inbox from `slaBadges()` in `inspecto-ui/src/app/modules/admin/objects/mail-model.ts`.
