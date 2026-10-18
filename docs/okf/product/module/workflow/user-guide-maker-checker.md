---
type: Feature
title: "Maker-checker (Pending Changes) — screens"
description: "Where Maker-checker (Pending Changes) lives in the UI and what each screen does."
tags: [workflow, maker-checker, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Maker-checker (Pending Changes) — screens

Screens for Maker-checker (Pending Changes). Design and internals: [Maker-checker (Pending Changes) architecture](architecture-maker-checker.md).

- **Operations > Pending Changes** (`/pending-changes`, `inspecto-ui/src/app/modules/admin/pending-changes/`):
  waiting or all changes; author, reason, expiry, decision and a line diff. Approve / Decline show only with
  `canApproveChanges`; Withdraw only to the author.
- **Settings > Approvers** (`inspecto-ui/src/app/modules/admin/settings/approver-roster.component.ts`).
- The policy has no dedicated screen found in the SPA; it is set through `PUT /settings/approval`.
