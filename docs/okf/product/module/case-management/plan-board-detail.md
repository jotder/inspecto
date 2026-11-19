---
type: Plan
title: module.case-management - board row detail
description: The narrative behind module.case-management's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, case-management, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.case-management
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: features/inspecto-case-management
---

# module.case-management - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### D6-REAL-USER-SESSION-1

- `D6-REAL-USER-SESSION-1` — **the D10 think-aloud with a REAL Case-desk lead is still unrun.** The D6 row closed 2026-09-29 as *accepted on proxy (operator 2026-09-29)*: an agent acting as a Case-desk lead drove the *Findings fields* dialog end to end on a `4dbe84d1a` demo build. A proxy cannot tell what a lead who has never seen the product finds confusing. Run the design's §8 protocol (T2, T6, T7, T5 unaided) with a real lead, and record pass or fail per task in `objects.md`. (The plan was archived on 2026-09-29 by operator decision, to `archived-documents/plans-archive/findings-spec-authoring-ui-design.md`.) → `okf/product/module/ops/user-guide-objects.md` (*Proxy acceptance session 2026-09-29*) **2026-10-12:** the facilitator script is ready (`okf/product/module/la-core/tests-live-drives.md#think-aloud-session-script-real-user-template`: pre-session checklist, T2/T6/T7/T5, pass/fail signals); running it needs a real Case-desk lead.

### MODULE-REORG-P7-INCIDENTS

- `MODULE-REORG-P7-INCIDENTS` — **Incidents / Case Management split — one item left (everything else shipped 2026-10-06..09; item 2 declined 2026-10-11).** (1) A dated action: delete `AlertMigration` (and `LegacyAlertObjects`) one release after the Alert store ships — **delete when a release containing `2879a70b6` has shipped** (parked 2026-10-09: operator plans no production release now) (it adopts a legacy ALERT object into the Alert store once; after that release no install holds one). ~~(2) Workflow & SLA slice 2~~ — STEP 1 DONE 2026-10-09 (`d599aed4c`: the pure SLA/escalation decisions live in `inspecto-workflow` as `SlaDecisions` over a `GovernedItem` view; ops keeps the writes, events and cap). ~~Step 2: the SECOND governed source and the `GovernedItemProvider` SPI~~ — **DECLINED 2026-10-11 (operator): no demand for an SLA on Breaks nobody promoted.** A promoted Break already gets a per-Break SLA through `POST /recon/promote` (`BREAK-INCIDENT-1`) into an Incident governed by the INCIDENT sla-policy / escalation rules; no consumer has asked for SLAs on never-promoted Breaks. Revisit only when a named consumer wants one — the researched five-step Breaks plan and calls Q1-Q6 are the playbook in the OKF doc below (it also corrects the archived plan's stale blockers). Kept decision: the `ops` id, packages and directory stay. **2026-11-16 (operator, MoSCoW C5, restated the same day):** keep `AlertMigration` (and `LegacyAlertObjects`) through 4.0.0 and delete them in 5.0.0. No released version carries the Alert store (`2879a70b6` landed on trunk 2026-10-07 and no tag contains it; the newest tag is `v3.11.0`, and the 3.x line is retired), so 4.0.0 is the first release with it and must ship the migration; the 5.0.0 notes say a 3.x install upgrades through a 4.x release first (`okf/product/module/api/plan-api-stability.md` §Release notes). → `okf/product/layer/platform/spec-module-reorg-decisions.md`
