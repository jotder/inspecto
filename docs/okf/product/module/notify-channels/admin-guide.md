---
type: Guide
title: Notifications — admin guide
description: Enabling the email and webhook channels, the notify.* flags, permissions, storage and common delivery failures.
resource: providers/inspecto-notify-channels/src/main/resources/META-INF/inspecto/module.toon
tags: [notify-channels, notifications, admin-guide, smtp, webhook, configuration]
timestamp: 2026-10-19T00:00:00Z
---

# Notifications — admin guide

## Enabling

The in-app feed is in every edition. The email and webhook channels are the provider module
`providers/inspecto-notify-channels` (id `notify-channels`), built only under the `edition-standard`,
`edition-preview` and `edition-advanced` profiles ([features](features-notifications.md)). On Basic a
`ChannelConfig` of kind `email` or `webhook` delivers nothing. Action Requests require this module.

## Permissions

Channel and notification-rule writes, deliveries and suppressions need `canAuthorWorkbench`; the default
preference grid and archiving need `canAdminister`. Channel and notification-rule writes are held for approval
when the Approval Policy governs them ([config safety](../config/spec-config-safety.md)). Full table:
[API](api-notifications.md).

## Configuration

Flag-driven channels (system properties):

| Key | Default | Meaning |
|---|---|---|
| `notify.smtp.host` | unset | SMTP relay; unset = email channel not configured |
| `notify.smtp.port` | `25` | relay port |
| `notify.smtp.from` / `notify.smtp.to` | unset | sender; fixed recipient (required with host) |
| `notify.smtp.user` / `notify.smtp.pass` | unset | SMTP AUTH |
| `notify.smtp.starttls` | `false` | `true` = STARTTLS required |
| `notify.webhook.url` / `notify.webhook.token` | unset | webhook target; bearer token |
| `notify.webhook.timeout.seconds` | `10` | request timeout |
| `notify.security.digestMinutes` | `10` | security email digest window; `0` = immediate |
| `notify.suppression` / `notify.suppression.bounce.ttl` | on / see `SuppressionList` | hard-bounce suppression |
| `notify.mail.message.max.bytes`, `notify.mail.attachment.max.bytes` | see `MailAttachments` | `mail` service size limits |
| `delivery.receipts.backend` | in-memory | where delivery receipts are kept (`DbDeliveryReceiptStore` for a DB) |

Per-Space destinations are `channel` components (`/notifications/channels`); fields in [spec](spec.md).
Webhook egress obeys the Space's Egress Allowlist (`egress.toon`); loopback is never allowlistable.
Detail: [architecture § 5](architecture-overview.md).

## Common failures

| Symptom | Cause |
|---|---|
| no email or webhook from flags, and no error | `notify.smtp.host` / `notify.smtp.to` or `notify.webhook.url` unset: the channel is dropped at discovery |
| email send fails on TLS | relay reached by IP or by a name not on its certificate; or `starttls=true` against a relay without STARTTLS |
| webhook refused | target not allowed by the egress policy, a 3xx, or a non-2xx answer |
| an address stops receiving | a recorded hard bounce; remove it with `DELETE /notifications/suppressions` |
| a repeated alert is not re-notified | an identical unread item exists, or 5 per hour per `dedupeKey` was reached |
