---
type: Feature
title: "Notifications — features"
description: "Notifications turn platform events into messages for people and systems."
tags: [notify-channels, notifications, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Notifications — features

**Availability:** Offering: in-app feed in every edition; the email and webhook channels (`providers/inspecto-notify-channels`) in the Standard, Advanced (includes Standard) and Preview Offerings, not Basic.

## What it does

Notifications turn platform events into messages for people and systems. There are three channels:

- **In-app** — intrinsic to `NotificationService`
  (`platform/inspecto-engine/src/main/java/com/gamma/notify/NotificationService.java`): a stored feed with
  per-reader read state and a live Server-Sent Events push.
- **Email** — `SmtpEmailChannel` (channel id `email`).
- **Webhook** — `WebhookChannel` (channel id `webhook`), which POSTs the notification as JSON.

Email and webhook are implementations of the `@PublicApi` SPI `NotificationChannel`
(`platform/inspecto-engine/src/main/java/com/gamma/notify/NotificationChannel.java`), discovered with
`ServiceLoader`. They live in the provider module `providers/inspecto-notify-channels`, whose
`META-INF/inspecto/module.toon` declares `id: notify-channels`, `bindingTime: boot` and provides the
contracts `NotificationChannel` and `WebhookSinkTransport`. The same module also ships
`HttpWebhookSinkTransport`, the HTTP transport used by the Pipeline webhook sink.

## Where it fits

The engine side (`com.gamma.notify` in `platform/inspecto-engine`) owns rendering, dedupe, preferences,
receipts and suppression. The control-plane routes are in
`inspecto/src/main/java/com/gamma/control/NotificationRoutes.java`. The transports are a separate provider
so the Basic build ships no external channel: the root `pom.xml` adds `providers/inspecto-notify-channels`
only under the `edition-standard`, `edition-preview` and `edition-advanced` profiles
(EDG-01). Notifications are also a Platform Service (`notifications`, via `NotificationAccess`) distinct from
the `mail` Platform Service (`MailAccess`), which sends to recipients an author names — see
[Platform Services](../../layer/platform/architecture-platform-services.md).

More: [screens](user-guide-notifications.md) · [API](api-notifications.md) · [architecture](architecture-overview.md).
