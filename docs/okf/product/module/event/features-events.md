---
type: Feature
title: "Events and event log — features"
description: "The **event log** is the per-Space, append-only record of what happened: lifecycle facts (BATCH_COMMITTED, FILE_QUARANTINED, ALERT_FIRED ...), captured log lines (LOG) and the security audit trail (AUDIT, ACCESS_DENIED)."
tags: [event, events, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Events and event log — features

**Availability:** Offering: every edition (the bus is `platform/inspecto-event`; the `/events*` routes ship with the `observability` module in `features/inspecto-observability`).

## What it does

The **event log** is the per-Space, append-only record of what happened: lifecycle facts (`BATCH_COMMITTED`,
`FILE_QUARANTINED`, `ALERT_FIRED` ...), captured log lines (`LOG`) and the security audit trail (`AUDIT`,
`ACCESS_DENIED`). `EventLog` (`platform/inspecto-event/src/main/java/com/gamma/event/EventLog.java`) is the
synchronous bus that writes each event to an `EventStore` and hands it to subscribers.

## Where it fits

- SPI types: `spi/inspecto-audit-spi` - `Event`, `EventLevel`, `EventType`, `EventStore`, `EventSink`,
  `EventQuery`, `AuditChain`, `AuditAttrs` (package `com.gamma.audit`).
- Implementation: `platform/inspecto-event` - `EventLog`, `InMemoryEventStore`, `ParquetEventStore`,
  `DbEventStore`, `EventStoreAppender`, `SecretScrubber`, `AuditChainLinker`, `SavedViewStore`, plus
  `MetricRegistry` (`com/gamma/metrics/MetricRegistry.java`).
- Routes: `EventRoutes` (`features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java`)
  and `AuditLogRoutes` (`inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java`).
- Gates: Installed (every Offering ships the bus); the `/events*` routes follow the `observability` module's
  Enabled gate; Permitted per the table in section 7.

More: [screens](user-guide-events.md) · [API](api-events.md) · [architecture](architecture-overview.md).
