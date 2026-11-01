---
type: Feature
title: "Partner settlement — features"
description: "Agreement-aware partner statement settlement: effective-dated terms, a cycle calendar, statement integrity, a per-agreement compare, netting positions with a roll-forward, disputes as Cases, and aging with accruals and a dashboard."
tags: [partner-settlement, features, presales]
timestamp: 2026-11-01T00:00:00Z
---

# Partner settlement — features

**Product Map Item:** [`solution-pack.partner-settlement`](../../../../../product-map/solution-pack/partner-settlement.toon) · **Status:** preview (slices S1 to S6: References, statement ingestion, the compare, netting positions and the roll-forward, disputes as Cases, aging and accruals) · **Availability:** Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`). Commercial terms are not set.

## What it is

A Space Template (`partner-settlement`) that reads a partner's settlement statement, prices our own usage independently, and compares them per agreement, per cycle, per service and per day, using that agreement's own tolerance, currency and calendar.

## Who benefits

Wholesale finance and revenue-assurance teams at operators and MVNOs that settle interconnect, SMS termination or roaming with partners.

## Key capabilities

- Seven Pipelines: partners, effective-dated settlement agreements, daily FX rates, public holidays, the partner statement (versioned lines with control totals), finance payments and notes, and the re-rated usage feed — [etl](../../module/etl/index.md)
- A settlement-cycle calendar: statement due, dispute window end and payment due, rolled to business days for the partner's country
- Statement integrity: control total mismatch, duplicate, restated version, unknown partner, unknown agreement; a re-sent line counts once and the highest version wins
- A per-agreement compare in the settlement currency: tolerance (percent or absolute), FX and effective dating come from the agreement, not the SQL
- Findings that say why: amount mismatch, cut-off difference, FX difference, missing statement (only once overdue), unrated usage, missing FX rate
- Two Reconciliations (voice, SMS) giving Break lifecycle and Incidents on the normalised compare, and one Alert Rule that fires once per agreement — [reconciliation](../../module/reconciliation/index.md)
- Settlement positions per netting group and cycle (payable, receivable, net, in the settlement currency and each original currency) and a roll-forward whose closing carries month to month; a line with no FX rate is counted and named, never summed or dropped
- Disputes as Cases: every amount mismatch and FX difference is a dispute line (one Incident each), grouped into one Case per partner and cycle; the disputed amount rides beside the net on the position, never in it; an Incident reminds when a cycle's contractual dispute window is about to close — [case-management](../../module/case-management/index.md)
- A dispute workflow (`OPEN → INVESTIGATING → DISPUTED → AGREED | REJECTED → CREDIT_PENDING → CLOSED`), a Case SLA Policy and an age Escalation Rule, shipped as bodies an administrator applies once — [workflow](../../module/workflow/index.md)
- Aging per agreement and statement cycle: finance's payments and notes applied oldest cycle first, the open balance and the open disputed amount in configurable age buckets (0-30, 31-60, 61-90, 90+ days by default), and an Alert when an open balance reaches its payment due date
- Accruals, reported and never posted: our expected amount for a cycle whose statement has not arrived (unbilled before its due date, late after it) and a configurable provision on the disputed amount (default 100 %)
- A `settlement_overview` dashboard: open disputed amount, open balance aged over 60 days and accrued unbilled as KPI tiles, and the open balance by age, the disputes by partner and the accruals by agreement as bars — [analytics](../../module/analytics/index.md)
- Zero-row schema seeds, a runbook and a synthetic corpus with planted variances and look-alikes

## Not yet

The evidence pack, wholesale / MVNO constructs and roaming (TAP). The KPI tiles count one currency (USD by default). It posts no payment, accrual or ledger entry and fetches no market FX rate. No real partner statement has been seen: the statement Schema is a synthetic proposal.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md)
