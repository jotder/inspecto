---
type: Concept
title: Events & Metrics
description: EventLog (synchronous bus + the run-claim hand-off seam), MetricRegistry, the StabilityGate, notifications + audit trail, and alert-rule authoring.
resource: platform/inspecto-event/src/main/java/com/gamma/event/EventLog.java
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
item: module.event
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-event
---

# Events & Metrics

* **`EventLog`** (`platform/inspecto-event/src/main/java/com/gamma/event/EventLog.java`) — the event bus. `global()` +
  per-space instances; `current()` routes by the calling thread's `space` MDC, falling back to global.
  ⛔ An MDC naming a Space that was **unregistered** (a late emitter outliving `CollectorService.close()`) is NOT routed to global: the event is dropped with one rate-limited stderr line (fail-closed, 2026-09-28) — it must not enter the default Space's store or audit chain.
  **Emission is synchronous on the publishing thread** (`emit()` calls each subscriber inline). This is the
  hand-off seam: the publishing thread holds that pipeline's `PipelineRunGuard` claim, so a subscriber that
  triggered a new ingest of the **same** pipeline inline would block forever on the claim its own thread holds
  — hence event-triggered work is handed to an off-bus virtual-thread pool (see [jobs](../processor/spec-jobs.md)). Before
  2026-08-01 this was the global `ingestLock`; being re-entrant, it would have double-ingested rather than
  hung (see [cross-cutting gotchas](../../layer/platform/admin-guide-gotchas.md)). `emit()` uses no SLF4J (avoids re-entrant capture) and
  swallows subscriber errors. A startup store-swap (`InMemoryEventStore` → configured backend) drains the old
  store oldest-first so nothing is lost.
* **`MetricRegistry`** (`platform/inspecto-event/src/main/java/com/gamma/metrics/MetricRegistry.java`) — counters/gauges/
  histograms keyed by name + sorted labels; `scrape()` runs registered collectors then renders Prometheus
  text. The per-space `space` label is supplied by callers as a label (no registry-level space awareness).
  The scrape endpoint `/metrics` (with `/metrics/acquisition`, `/health`, `/ready`) is one of the four
  routes that stay **unversioned** — see [versioned API](../processor/api-v1.md). *(The sunset counter
  `inspecto_legacy_api_requests_total` was removed 2026-07-25 with the unversioned business surface.)*
* **`StabilityGate`** (`platform/inspecto-acquire/src/main/java/com/gamma/acquire/StabilityGate.java`) — the acquisition
  file-readiness gate (not a health gate); one shared instance per space (see [acquisition](../elt/architecture-elt.md)).

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Notifications and the audit trail](spec-events-metrics-notifications.md)** — Notifications & audit trail (shipped 2026-06-29, `ddfa288`)
2. **[Notifications and audit — the audit hash chain onward](spec-events-metrics-audit-chain.md)**
3. **[Authorable notification rules and Alert Rule authoring](spec-events-metrics-alert-rules.md)** — Alert-rule authoring (shipped 2026-07-09) — board rows: `SES-SNS-LIVE-CHECKS-1`
4. **[Bounce handling, rate-limit classes and unmodelled keys](spec-events-metrics-decisions-keys.md)** — Decision 2026-09-06 — bounce/complaint handling stays manual until receipts persist · Link Analysis rate-limit classes · Unmodelled keys on a Notification Rule and Channel (`MODULE-REORG-P4-2`) · Unmodelled keys on a Saved View (`MODULE-REORG-P4-2`)
