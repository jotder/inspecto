---
type: Feature
title: "Anomaly Detection — screens"
description: "Where Anomaly Detection lives in the UI and what each screen does."
tags: [anomaly, anomaly, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Anomaly Detection — screens

Screens for Anomaly Detection. Design and internals: [Anomaly Detection architecture](architecture-overview.md).

- `/anomaly-models` — Studio pane (`inspecto-ui/src/app/modules/admin/anomaly-models/anomaly-models.component.ts`)
  with `anomaly-model-form.dialog.ts`, `anomaly-model-features.component.ts` and `anomaly-model-preview.component.ts`.
- Entity anomaly panel `inspecto-ui/src/app/inspecto/components/anomaly-panel.component.ts` (data via
  `inspecto-ui/src/app/inspecto/api/anomaly-scores.service.ts`): band badge, top Features, day series. Hosted on the
  Incident page, the Risk Score entity view and Link Analysis.
