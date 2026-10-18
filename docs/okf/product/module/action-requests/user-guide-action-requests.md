---
type: Feature
title: "Action Requests — screens"
description: "Where Action Requests lives in the UI and what each screen does."
tags: [action-requests, action-requests, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Action Requests — screens

Screens for Action Requests. Design and internals: [Action Requests architecture](architecture-overview.md).

- **Operations > Action Requests** (`inspecto-ui/src/app/modules/admin/action-requests/`): the inbox; the detail
  shows the parsed target host first, then key, attempts and the exact payload; Approve / Decline / Retry /
  Mark as failed by status.
- **Incident / Case detail** panel (`inspecto-ui/src/app/modules/admin/objects/action-requests-panel.component.ts`).
- **Settings > Egress Allowlist** (`inspecto-ui/src/app/modules/admin/settings/egress.component.ts`) and
  **Settings > Approvers**.
