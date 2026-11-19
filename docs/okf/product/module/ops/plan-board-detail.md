---
type: Plan
title: module.ops - board row detail
description: The narrative behind module.ops's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, ops, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
---

# module.ops - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### LEGAL-HOLD-SCOPE-1

- `LEGAL-HOLD-SCOPE-1` — **a legal hold does not reach a Pipeline's data partitions, and has no screen (narrowed 2026-11-17; filed 2026-11-15 from `ARCH-MOSCOW-UNTRACKED-1` item 7).** **Built 2026-11-17 (MoSCoW S12):** a hold now protects an Incident's evidence. `ObjectService.legalHoldReason` holds an object that carries `legalHold` or is one `CONTAINS` hop from one that does (an Incident in a held Case; a Case containing a held Incident, even when shared with an unheld one), offered to core as `ObjectAccess.legalHold` / `legalHolds` and to Link Analysis as `CasePort.legalHold`. `incident_purge`, `alert_purge` and `event_prune` (whole days from the held evidence's first day) skip held evidence and report it; `DELETE /objects/{id}/links`, Case merge and split, `DELETE /inv/investigations/{id}`, `DELETE` / `PUT …/case` and `DELETE /spaces/{id}?purge=true` answer 409 naming the hold and audit `legal_hold.refused`; releasing the hold restores normal handling at once. Notes, attachments, Snapshots and Cases have no other delete path; Drafts are not evidence (D20). **Open:** (1) `partition_prune` drops a Pipeline's data partitions by date and nothing links an Incident to the rows that raised it, so held source data is not protected (needs that lineage first); (2) a hold is set and released only through `PATCH /objects/{id}`, with no UI.
