---
type: Feature
title: "Risk Scores and Entity Lists — screens"
description: "Where Risk Scores and Entity Lists lives in the UI and what each screen does."
tags: [scoring, scoring-entity-lists, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Risk Scores and Entity Lists — screens

Screens for Risk Scores and Entity Lists. Design and internals: [Risk Scores and Entity Lists architecture](architecture-scoring-entity-lists.md).

- `/risk-scores` — `inspecto-ui/src/app/modules/admin/risk-scores/risk-scores.component.ts`, with
  `risk-score-form.dialog.ts`, `risk-score-factors.component.ts`, `risk-score-actions.component.ts`.
- Risk Score panel on the Incident page: `inspecto-ui/src/app/inspecto/components/risk-score-panel.component.ts`.
- Entity Lists in the Link Analysis app: `inspecto-ui/projects/la-app/src/app/entity-lists.page.ts` and the
  Investigation section `inspecto-ui/projects/link-analysis/src/link-analysis/link-analysis-entity-lists.component.ts`
  (entry editing parsed by `entity-list-entries.ts`).
