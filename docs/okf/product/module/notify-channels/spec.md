---
type: Concept
title: Notifications — specification
description: Implementation detail of the notification engine and the notify-channels provider — classes, SPI, storage, delivery and egress.
resource: platform/inspecto-engine/src/main/java/com/gamma/notify/
tags: [notify-channels, notifications, spec, smtp, webhook, spi]
timestamp: 2026-10-19T00:00:00Z
---

# Notifications — specification

## Engine (`com.gamma.notify`, `platform/inspecto-engine`)

| Class | Role |
|---|---|
| `NotificationService` | receives events, matches rules, dispatches, keeps the feed and SSE listeners |
| `NotificationRule`, `NotificationRules`, `NotificationTemplate` | notification-rule model and rendering |
| `NotificationCategory` | `pipeline`, `job`, `ops`, `collaboration`, `security` (critical) |
| `NotificationPreferences`, `NotificationPreferenceOverrides` | default grid and per-Subject overrides |
| `NotificationRateLimiter` | `DEFAULT_MAX_PER_HOUR = 5` identical items per rolling hour |
| `NotificationStore` (`InMemoryNotificationStore`), `NotificationReadState` | the feed and per-reader read state |
| `ChannelConfig` | a persisted per-Space destination: `id`, `kind`, `target`, `description`, `enabled`, `createdAt`, `template`, `digestMinutes` |
| `DeliveryReceipt`, `DeliveryReceiptStore` (`InMemoryDeliveryReceiptStore` / `DbDeliveryReceiptStore`, `-Ddelivery.receipts.backend`), `DeliveryStatusAdapter` | one receipt per external attempt, correlated by delivery id |
| `SuppressionList` | hard-bounce suppression of persisted targets |
| `SecurityTriggers` | security-category events |
| `NotificationChannel` (`@PublicApi` SPI) | discovered with `ServiceLoader`; dropped unless `configured()` |
| `NotificationAccess`, `MailAccess`, `MailAttachments` | the `notifications` and `mail` Platform Services |

## Provider (`providers/inspecto-notify-channels`, package `com.gamma.notify.channel`)

[module.toon](../../../../../providers/inspecto-notify-channels/src/main/resources/META-INF/inspecto/module.toon):
id `notify-channels`, `bindingTime: boot`, contracts `NotificationChannel` and `WebhookSinkTransport`.

| Class | Role |
|---|---|
| `SmtpEmailChannel` | channel id `email`; delivery id as SMTP `Message-ID`; TLS server identity always checked |
| `WebhookChannel` | channel id `webhook`; JSON POST with `X-Inspecto-Delivery-Id`; refuses attachments |
| `WebhookEgress`, `PinnedHttp` | egress-policy check, pinned connect, no 3xx follow, non-2xx = failure |
| `HttpWebhookSinkTransport` | the transport for the Pipeline webhook sink and for Action Request dispatch |

## Dispatch, configuration, limits

Dispatch steps: [architecture § 4](architecture-overview.md). `notify.*` flags:
[architecture § 5](architecture-overview.md). Routes: [API](api-notifications.md).
Limits: [architecture § 9](architecture-overview.md).
