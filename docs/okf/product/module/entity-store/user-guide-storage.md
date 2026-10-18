---
type: Feature
title: "Storage — screens"
description: "Where Storage lives in the UI and what each screen does."
tags: [entity-store, storage, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Storage — screens

Screens for Storage. Design and internals: [Storage architecture](architecture-storage.md).

- **Operational DB** (`inspecto-ui/src/app/modules/admin/settings/operational-db.component.ts`):
  what each store family uses, and a connection test. There is no save; the database is a
  deployment choice.
- **Data browser** and **Catalog** for reading stores ([data-plane](../../layer/platform/plan-data-plane.md) §3.9).
- **Component history dialog** (`inspecto-ui/src/app/inspecto/components/component-history.dialog.ts`)
  and the Pipeline editor's history.
