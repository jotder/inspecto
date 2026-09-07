---
type: Feature
title: Events & Activity
description: The operational event stream — backend filters, live tail, saved views, CSV export, detail drill-down.
resource: inspecto-ui/src/app/modules/admin/events/events.routes.ts
tags: [feature, events, operations, live-tail, pro]
timestamp: 2026-06-28T00:00:00Z
---

# Events & Activity

Route `/events` (Operations nav group). The newest-first operational event stream
(`GET /events/search`). 🔴 **Edition-gated since EDG-01 cell 6 (2026-09-08):** this pane's whole data source is the optional `inspecto-events` module, so on Personal `bootstrap.features.events` is false — the nav entry is dropped, the Ops lens home falls back to `pipelines`, and the pane renders an explained `<inspecto-alert>`. ⛔ The **Audit log** pane is deliberately NOT gated with it: it reads the core `/audit/*` routes via `AuditService`, because EDITIONS §Audit promises Personal an audit trail. A header filter toolbar (min level · type · pipeline · free-text · limit) drives the
query; a **live-tail** toggle polls via `visibleInterval` while the tab is visible; a saved-views menu
persists filter sets; matching rows export to CSV. The grid is a **pro** [data-table](../design-system/data-table.md)
with `[searchable]="false"` `[exportable]="false"` (backend search/export are kept) and an Actions column
(row detail → correlation/type drill-down). Backed by `EventsService`.
