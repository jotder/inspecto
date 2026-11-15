---
type: Feature
title: Entity Lists — user guide
description: Where Entity Lists appear in the UI and how to create a list, edit its entries, retire it and register it as a Dataset.
resource: inspecto-ui/projects/la-app/src/app/entity-lists.page.ts
tags: [entity-list, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Entity Lists — user guide

## Screens

- **Entity Lists page** in the Link Analysis app (left rail) —
  [`entity-lists.page.ts`](../../../../../inspecto-ui/projects/la-app/src/app/entity-lists.page.ts). Listing,
  creating, adding to and retiring a list work here; excluding or seeding by a list needs an open Investigation
  and stays off.
- **Entity Lists section inside an Investigation** —
  [`link-analysis-entity-lists.component.ts`](../../../../../inspecto-ui/projects/link-analysis/src/link-analysis/link-analysis-entity-lists.component.ts),
  where a list can also exclude or seed the graph. See the [Link Analysis user guide](../la-core/user-guide.md).

The Risk Scores side is in the [scoring user guide](../scoring/user-guide-scoring-entity-lists.md).

## Tasks

- **New list…** — title, purpose (`allow · block · watch · exclusion`), Entity Type and a reason. The Entity Type
  must be in force in the Space's Link Analysis settings.
- **Edit entries…** — one entry per line: an exact key, `+4478*` (prefix), `lo..hi` (equal-length range) or a line
  holding `/` (CIDR block). Choose Add or Remove; an optional expiry on an add must be in the future. A bad line is
  refused by number and nothing is sent. The dialog shows the list's ranges and expiring entries as served
  (masked), and badges an expired entry.
- **Retire** — a retired list matches nothing and keeps its history.
- **Register as Dataset** — after a confirm, creates Dataset `entity_list_<id>` over the list's Parquet sidecar.
  ⚠ The Dataset reads raw values: masking does not apply. Needs `canAuthorWorkbench`.
- **Refresh** — re-reads the lists; disabled without `canManageIncidents`.

## Things to know

- Under an approval policy for `entity-list`, member edits and retire report *held for approval as `<pc-id>`*
  and apply only when a second person approves. An add whose every entry expires within 24 h applies at once.
- An expiring add never shortens a permanent entry; remove it and add it again.
- Writes need `canManageIncidents`; reading needs Space access only.

The rules behind each behaviour: [spec](spec.md).
