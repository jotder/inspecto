---
type: Feature
title: "Notifications — screens"
description: "Where Notifications lives in the UI and what each screen does."
tags: [notify-channels, notifications, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Notifications — screens

Screens for Notifications. Design and internals: [Notifications architecture](architecture-overview.md).

- Bell menu in the shell: `inspecto-ui/src/app/layout/common/notifications/notifications.component.ts`.
- Notification center (channels, rules, deliveries):
  `inspecto-ui/src/app/modules/admin/notification-center/notification-center.component.ts`; the old
  `notification-center` path redirects to `settings/notifications`.
- Preferences grid: `inspecto-ui/src/app/modules/admin/notification-preferences/notification-preferences.component.ts`.
- Client: `inspecto-ui/src/app/inspecto/api/notifications.service.ts`.
