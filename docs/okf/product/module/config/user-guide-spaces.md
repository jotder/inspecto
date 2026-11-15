---
type: Feature
title: "Spaces — screens"
description: "Where Spaces lives in the UI and what each screen does."
tags: [config, spaces, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Spaces — screens

Screens for Spaces. Design and internals: [Spaces architecture](architecture.md).

- **Settings → Spaces** (`inspecto-ui/src/app/modules/admin/spaces/spaces.component.ts`): list, create,
  rename, delete, import; the template picker is
  `inspecto-ui/src/app/modules/admin/spaces/space-template-gallery.dialog.ts`. `/spaces` redirects to
  `settings/spaces` (`inspecto-ui/src/app/app.routes.ts`).
- **Settings → Modules** (`inspecto-ui/src/app/modules/admin/settings/module-settings.component.ts`): the
  per-Space module switches, through `inspecto-ui/src/app/inspecto/api/module-settings.service.ts`.
- Request prefixing for the selected Space: `inspecto-ui/src/app/inspecto/api/space.interceptor.ts`.
- **A Space you create while signed in is closed** (2026-11-15): you and the administrators see it; nobody else
  does, and it is missing from their Space switcher. To let others in, open the Space, go to Settings, Access,
  Roles and define the role they hold. No screen marks a Space as closed yet. Detail:
  [Auth & Security](../access/admin-guide-auth-security.md).
