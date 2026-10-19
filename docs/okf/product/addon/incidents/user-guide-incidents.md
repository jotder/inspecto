---
type: Feature
title: "Incidents — screens"
description: "Where Incidents lives in the UI and what each screen does."
tags: [incidents, incidents, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Incidents — screens

Screens for Incidents. Design and internals: [Incidents architecture](architecture-incidents.md).

Route `/incidents` (`inspecto-ui/src/app/modules/admin/objects/incidents.routes.ts`):

- `/incidents` — inbox (`object-mail.component.ts`, data type `INCIDENT`), create via `object-create.dialog.ts`;
- `/incidents/:id` — detail (`object-detail.component.ts`) with `resolve.dialog.ts`, `categorize.dialog.ts`,
  `impact-panel.component.ts`, `postmortem-panel.component.ts`, `object-link.dialog.ts` and
  `action-requests-panel.component.ts`.

Front-end notes: [objects](../../module/ops/user-guide-objects.md).
