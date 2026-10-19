---
type: Feature
title: "Case Management — screens"
description: "Where Case Management lives in the UI and what each screen does."
tags: [case-management, case-management, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Case Management — screens

Screens for Case Management. Design and internals: [Case Management architecture](architecture-overview.md).

Route `/cases` (`inspecto-ui/src/app/modules/admin/objects/cases.routes.ts`):

- `/cases` — "Case Manager" inbox (`object-mail.component.ts`, type `CASE`), rules via `case-rules.dialog.ts`,
  analytics via `case-analytics.dialog.ts`;
- `/cases/:id` — detail (`object-detail.component.ts`) with `case-contents.component.ts`,
  `merge-cases.dialog.ts`, `split-case.dialog.ts`, `add-member.dialog.ts`.

Front-end notes: [objects](../ops/user-guide-objects.md).
