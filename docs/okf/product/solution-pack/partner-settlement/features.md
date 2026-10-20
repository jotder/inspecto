---
type: Feature
title: "Partner settlement — features"
description: "Agreement-aware partner statement settlement: effective-dated terms, a cycle calendar, statement integrity and a per-agreement compare."
tags: [partner-settlement, features, presales]
timestamp: 2026-10-20T00:00:00Z
---

# Partner settlement — features

**Product Map Item:** [`solution-pack.partner-settlement`](../../../../../product-map/solution-pack/partner-settlement.toon) · **Status:** preview (slices S1 to S3: References, statement ingestion, the compare) · **Availability:** Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`). Commercial terms are not set.

## What it is

A Space Template (`partner-settlement`) that reads a partner's settlement statement, prices our own usage independently, and compares them per agreement, per cycle, per service and per day, using that agreement's own tolerance, currency and calendar.

## Who benefits

Wholesale finance and revenue-assurance teams at operators and MVNOs that settle interconnect, SMS termination or roaming with partners.

## Key capabilities

- Six Pipelines: partners, effective-dated settlement agreements, daily FX rates, public holidays, the partner statement (versioned lines with control totals) and the re-rated usage feed — [etl](../../module/etl/index.md)
- A settlement-cycle calendar: statement due, dispute window end and payment due, rolled to business days for the partner's country
- Statement integrity: control total mismatch, duplicate, restated version, unknown partner, unknown agreement; a re-sent line counts once and the highest version wins
- A per-agreement compare in the settlement currency: tolerance (percent or absolute), FX and effective dating come from the agreement, not the SQL
- Findings that say why: amount mismatch, cut-off difference, FX difference, missing statement (only once overdue), unrated usage, missing FX rate
- Two Reconciliations (voice, SMS) giving Break lifecycle and Incidents on the normalised compare, and one Alert Rule that fires once per agreement — [reconciliation](../../module/reconciliation/index.md)
- Zero-row schema seeds, a runbook and a synthetic corpus with planted variances and look-alikes

## Not yet

Netting and settlement positions, the authored dispute Workflow and the Case per partner and cycle, aging and accruals, the evidence pack, wholesale / MVNO constructs and roaming (TAP). It posts no payment or ledger entry and fetches no market FX rate. No real partner statement has been seen: the statement Schema is a synthetic proposal.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md)
