---
type: Capability
title: Notifications — requirement of record
description: What was required of notifications and their email and webhook channels, what is built, what is left and what was refused.
resource: providers/inspecto-notify-channels/
tags: [notify-channels, notifications, plan, requirement, email, webhook]
timestamp: 2026-10-19T00:00:00Z
---

# Notifications — requirement of record

## Required

A notification system turning platform events into messages for people and systems, refined from a generic
SaaS requirement onto Inspecto
([notification plan](../../../../archived-documents/plans-archive/notification-system-and-audit-trail-plan.md),
*Requirement → Project mapping*): SSE instead of WebSockets, a `NotificationChannel` SPI with in-app in the
core and email / webhook in the Standard flavour, no message broker.

## Built

- In-app feed, SSE stream, preferences and categories with a critical `security` bypass — shipped 2026-06-29
  (plan status line).
- Email (`SmtpEmailChannel`) and webhook (`WebhookChannel`) in `providers/inspecto-notify-channels`, built only
  under the Standard, Advanced and Preview profiles (EDG-01; [features](features-notifications.md)).
- Digest batching, delivery receipts, bounce suppression and per-Space channel destinations
  ([architecture § 4](architecture-overview.md)).
- `WEBHOOK-EGRESS-POLICY-1` (closed 2026-09-27): webhook egress passes the egress policy with a pinned connect.
- `ASSURE-MAKER-CHECKER-RESIDUALS-1` (closed 2026-10-06): `channel` and `notification-rule` writes are
  governable and replay on approval ([config safety](../config/spec-config-safety.md)).

## Left

No open [BACKLOG](../../../../BACKLOG.md) row names this module. Known limits are in
[architecture § 9](architecture-overview.md): no retry inside a channel except the soft-bounce retry, and
suppression only for persisted destinations.

## Refused

- A message broker (Kafka / Redis / RabbitMQ): an in-process executor by design (plan, *Out of scope*).
- GeoIP and cloud cold storage stay edition / connector seams, not built here (plan, *Out of scope*).
