---
type: Feature
title: "Observability and backup — features"
description: "Two platform concerns that keep a deployment operable:  - **Observability** — engine counters exposed for Prometheus, the event ledger, the who-did-what   audit trail, run reports with percentiles, and health and readiness probes."
tags: [observability, observability-backup, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Observability and backup — features

**Availability:** Offering: health, status, run reports and the audit log in all tiers; the Prometheus endpoint, the Events screen routes (`inspecto-observability`) and backup / restore (`inspecto-backup`) are Standard and above.

## What it does

Two platform concerns that keep a deployment operable:

- **Observability** — engine counters exposed for Prometheus, the event ledger, the who-did-what
  audit trail, run reports with percentiles, and health and readiness probes.
- **Backup and restore** — tasks on the `maintenance` Job Type that archive a directory tree with a
  SHA-256 manifest, verify an archive, and restore it fail-closed.

The capability spec of record is [observability](plan-observability.md); the
event and metrics plumbing is [events-metrics](../event/spec-events-metrics.md); the
runbook is [`backup-restore-runbook.md`](../backup/admin-guide-backup-restore.md).

## Where it fits

The counters and the event bus are core plumbing written to from across the engine; the two
optional modules add only surfaces:

- `features/inspecto-observability` — `MetricsRoutes` (`GET /metrics`) and `EventRoutes` (`/events*`).
- `features/inspecto-backup` — `BackupTask`, `BackupTaskProvider` (the `MaintenanceTaskProvider`
  seam), `BackupSignals`, and `BackupEditionFeatures` (declares `sink.archive` and `sink.ducklake`).

Absent `inspecto-observability`, core `AbsentModuleRoutes` answers `503`. Absent `inspecto-backup`,
`backup`, `backup_verify` and `restore` are unknown maintenance tasks and vanish from the Jobs form.

More: [screens](user-guide-observability-backup.md) · [API](api-observability-backup.md) · [architecture](architecture-observability-backup.md).
