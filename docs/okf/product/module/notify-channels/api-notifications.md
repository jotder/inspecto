---
type: Reference
title: "Notifications — API"
description: "Control-plane routes for Notifications, with their gates."
tags: [notify-channels, notifications, api]
timestamp: 2026-10-19T00:00:00Z
---

# Notifications — API

Routes for Notifications. Design and internals: [Notifications architecture](architecture-overview.md).

All in `NotificationRoutes`; gates per `platform/inspecto-access/src/main/java/com/gamma/access/CapabilityManifest.java`.

| Route | Gate |
|---|---|
| `GET /notifications`, `GET /notifications/unread-count`, `GET /notifications/stream` (SSE) | authenticated |
| `POST /notifications/read-all`, `POST /notifications/{id}/read`, `POST /notifications/{id}/unread` | self-service |
| `GET` / `PUT /notifications/preferences` | self-service (caller's own override) |
| `GET` / `PUT /notifications/preferences/default` | PUT `canAdminister` |
| `GET` / `POST /notifications/channels`, `PUT` / `DELETE /notifications/channels/{id}` | writes `canAuthorWorkbench` |
| `GET` / `POST /notifications/rules`, `PUT` / `DELETE /notifications/rules/{id}` | writes `canAuthorWorkbench` |
| `GET /notifications/deliveries`, `GET` / `DELETE /notifications/suppressions` | `canAuthorWorkbench` |
| `DELETE /notifications/{id}` (archive, shared per Space) | `canAdminister` |
