---
type: Plan
title: module.reconciliation - board row detail
description: The narrative behind module.reconciliation's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, reconciliation, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.reconciliation
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: features/inspecto-reconciliation
---

# module.reconciliation - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### PACK-PARTNER-SETTLEMENT-1

- `PACK-PARTNER-SETTLEMENT-1` — **partner settlement function pack, slices W1…W3 open; S0…S3 (the smallest cut) BUILT 2026-10-20, S4 (netting, positions, roll-forward) BUILT 2026-10-21, S5 (disputes as Cases), S6 (aging, accruals, the dashboard) and S7 (the dispute evidence pack) BUILT 2026-11-01, S8 (glossary terms, matrix rows C and Q, solution-overview row) DONE 2026-11-01** (competitive gap Q, 2026-10-17: Subex, TEOCO; the re-rating half, gap C, is `PACK-RERATING-1`): the `partner-settlement` Space Template (`spaces/_templates/partner-settlement`, hub `okf/product/solution-pack/partner-settlement/`) holds four References (partner, effective-dated settlement agreement, FX rate, holiday), a settlement-cycle calendar, the versioned partner statement feed with its integrity control, a stand-in `rerated_usage` feed, a finance payment feed, twelve chained `sql.template` Jobs (then the dispute lines, the line-level movements, the netting position per group and cycle month with the disputed amount beside the net, the roll-forward, the aging per agreement cycle with payments applied oldest first, and the accruals), two Reconciliations (voice, SMS), one Alert Rule per agreement, one Incident per dispute line and per closing dispute window, a payment-due Alert per open cycle, a Case Rule giving one dispute Case per partner and cycle, three KPIs, the `settlement_overview` dashboard and the dispute evidence pack (Report Templates `settlement-dispute-pack` JSON and `-csv`, drafted from the dispute Case through the new generic Report Template `rows` source of Regulatory Reporting, refusing a line with no re-rating run); the dispute CASE workflow, SLA Policy and Escalation Rule ship as bodies an administrator applies (PS-O5, answered under delegation 2026-11-01, pending confirmation), with golden tests and mutation passes (hub `okf/product/solution-pack/partner-settlement/plan-as-built.md`). **Still open:** W1…W3 wholesale / MVNO / roaming, demand-gated. **Open decisions:** confirm the S5 and S6 decisions taken under delegation (PS-O5, PS-S5-1…8, PS-S6-1…10 and PS-S7-1…5, the last being the Regulatory Reporting `rows` seam chosen over widening the Alert Rule key, hub `plan-as-built.md`); a reporting currency for the KPI tiles (one currency today, PS-S6-9); sign the §5 contract with the re-rating plan: the shipped re-rating `rerated_usage` is a per-call retail Dataset and does not match R1 to R8 (`superpower/telco-packs-plan.md` §4.2), so who writes the wholesale re-rating adapter is open (R6, the completeness Signal, is not implemented); cross-currency netting (one currency per position today); an opening-balance feed; a payment-integrity finding; the real statement layout once a real file is seen. Plan: [`superpower/telco-packs-plan.md`](../../../../superpower/telco-packs-plan.md) §2, §4 (PS-D1…PS-D10 ANSWERED 2026-10-20) → `okf/product/layer/product/brochure-competitive-matrix.md`
