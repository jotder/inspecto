---
type: Plan
title: module.access - board row detail
description: The narrative behind module.access's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, access, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
---

# module.access - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### SPACE-ADMISSION-FOLLOWUPS-1

- `SPACE-ADMISSION-FOLLOWUPS-1` — **what the closed-Space change (D5, `91f0af69a`) left on purpose.** (1) Add data shows the Feed and Storage panels to a caller without `canUploadData`, who then reads two 403s: hide them (`import-dataset-card.component.ts`). (2) Nothing on screen says a Space is closed or how to admit people; admission today is the Space's own role table (Settings ▸ Access) and open/close is `space.toon` plus a restart. (3) Admission is by role name, and every LA template defines `fraud-analyst`, `la-viewer` and `la-settings`, so a Space made from an LA template admits those roles across the install: **decided 2026-11-18 (operator): template roles DO admit**; every LA scaffolder and the showcase packager now write `admission: closed`, and the demo `outsider` holds `no-access`, which no template defines, so it signs in and is admitted nowhere (`tools/la-demo-roster.test.mjs`). (4) The creator match compares the Subject id, not the issuer. (5) Un-prefixed `/bootstrap` with a closed fallback Space shows its feature flags to an outsider (settings, not data); not in the admin guide's Limits. (6) Exchange owning-Space gates ask capability, not admission. (7) The tracked scratch file `inspecto/database/_onboard/d440b992ef8394c5d93e22a702853ffbf/transfers.csv` is deleted by every processor test run: untrack it. → `archived-documents/plans-archive/2026-11-15-la-full-cycle-test-plan.md`
