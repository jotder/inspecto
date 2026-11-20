---
type: Plan
title: module.processor - board row detail
description: The narrative behind module.processor's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, processor, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.processor
audience: internal
status: plan
verified: 2026-11-20 code-reviewed
code: inspecto
---

# module.processor - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### Platform Services Stage 2 / 3

- **Platform Services Stage 2 / 3** — everything planned is shipped (S2-0..S2-5, S3-1..S3-3; design archived `archived-documents/plans-archive/platform-services-stage2-design.md`, owner concept `okf/product/module/processor/spec-platform-services.md`). Still open: ~~the per-Run **held snapshot handle** for `DatasetAccess` is ON DEMAND (operator, 2026-10-06; a known limit in the owner concept: D-10 pins per call, so two reads in one Run may see different file lists); and~~ filtered `services()` on `ProcessorContext` (D4) and a devkit jar (D5) only on demand — each needs a design or an operator call, none is decision-free (grounded 2026-10-04: `DatasetAccess` pins per `read`). The Job-side deadline (R1) ✅ shipped 2026-10-06 (`JobDeadline`, D-13: 30 min default, 24 h ceiling; okf `platform-services.md` §7f). ⛔ Third-party `LOWERED` stays closed until a SQL-fragment guard exists. **2026-10-18 (decided under operator delegation):** re-verified — no held snapshot handle exists; every leftover is on demand and owes a design or an operator call. KEPT, re-ranked P2 → P3. **2026-11-12:** tagged `hold: demand` (product assembly plan §13.1; every leftover is on demand). **2026-11-16 (operator, MoSCoW S6):** build the Run-level held snapshot now, so every `DatasetAccess` read in one Run sees one file list; `hold: demand` removed. Filtered `services()` (D4) and the devkit jar (D5) stay on demand. **2026-11-17: the Run-level snapshot shipped** (`RunSnapshot`, `okf/product/module/processor/spec-jobs.md` "Snapshot swaps and readers"): a Job Run pins each Dataset at its first `DatasetAccess` read by hard-linking its files into `<dataRoot>/.run-snapshots/<uuid>/` under the store's read lock, every later read in the Run returns that pin across a swap, a publish waits only for a pin under way (never for a Run), and the Run's end (success, failure, cancel, deadline) deletes the links. Per call as before: `view`-backed Datasets, a pack-contributed `DatasetAccess`, pack Steps (load-time grant). Narrowed to D4 and D5; `hold: demand` back, since both wait for a consumer to ask.

## Standing refusals and decisions

Board §6: not work — kept so nobody re-files it.

### MODULE-REORG-P7-KERNEL consequence-registry leftovers

- **`MODULE-REORG-P7-KERNEL` consequence-registry leftovers** — ⛔ decided 2026-10-09, operator signed off the same day: **declined, no consumer.** Registering Alert Rule actions (an `AlertRule` has no authored actions; `create-alert`/`create-incident` already cover the Decision Rule side), a Tag Rule effect (an `apply-tag` Consequence names no object when a Decision Rule fires over records), the Case Rule effect (count-in-window grouping stays bespoke), Notification channels (already their own `NotificationChannel` SPI — a second registry duplicates it) and the Escalation target (reassign / raise-priority / notify mutate the object inside the SLA sweep) would each add a dispatch path with no user-visible gain. Step 7 (`Conditions` → condition tree) stays declined for security. Reopen an item when a Decision Rule author needs that effect as an authored Consequence. Shipped from the row: Kernel steps 1, 3–6 and the Escalation Rule edit UI (`66a982fda`).
