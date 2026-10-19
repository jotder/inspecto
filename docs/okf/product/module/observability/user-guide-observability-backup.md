---
type: Feature
title: "Observability and backup — screens"
description: "Where Observability and backup lives in the UI and what each screen does."
tags: [observability, observability-backup, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Observability and backup — screens

Screens for Observability and backup. Design and internals: [Observability and backup architecture](architecture-observability-backup.md).

- **Overview** (`inspecto-ui/src/app/modules/admin/dashboard/dashboard.component.ts`): readiness,
  status, report percentiles, acquisition tiles, raw `/metrics` behind a toggle.
- **Events** (`inspecto-ui/src/app/modules/admin/events/events.component.ts`) and **Audit log**
  (`inspecto-ui/src/app/modules/admin/audit-logs/audit-logs.component.ts`).
- **Processing status** and **Run detail** ([parsing-ingest](../etl/architecture-parsing-ingest.md) §6).
- **Jobs** for backup and restore; there is no dedicated backup screen and no metrics browser.
