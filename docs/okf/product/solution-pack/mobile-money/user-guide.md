---
type: Feature
title: "Mobile money — user guide"
description: "How an analyst works with the Mobile money pack day to day."
tags: [mobile-money, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Mobile money — user guide

What a Space created from the `mobile-money` template gives an analyst, and the daily routine. Design detail is in [architecture](architecture.md).

## The daily cycle

| Time | What runs | Output |
|---|---|---|
| 03:00 | 7 detection Jobs (`sql.template`) over the previous day | Datasets `mm_commission`, `mm_fee`, `mm_agent_split`, `mm_round_trip`, `mm_dormant`, `mm_kyc_limit`, `mm_provisioning` |
| 04:00 | Reconciliations `mm_bank_float`, `mm_partner_settlement` | matched / unmatched wallet transactions |
| 04:30 | `mm_risk_score` | per-agent Risk Score `mm_agent` |
| 04:45 | `mm_anomaly_score` | wallet-activity Anomaly Model scores |

Schedules are in `spaces/_templates/mobile-money/config/jobs/`.

## Alerts and Incidents

Each detection Dataset has a per-entity Alert Rule:

- by `agent_id`: `mm_agent_split` and `mm_round_trip` (CRITICAL), `mm_commission` (WARNING);
- by `wallet_id`: `mm_fee`, `mm_dormant`, `mm_kyc_limit`, `mm_provisioning` (WARNING).

Two more Alert Rules wait in `config/pending/` until the scores have run once: `mm_high_risk_agent` (Risk Score at or above 60) and `mm_unusual_wallet_activity` (anomaly score at or above 80). Working Alerts and Incidents: [Alerts](../../addon/incidents/user-guide-alerts.md) · [Incidents](../../addon/incidents/user-guide-incidents.md).

## A typical day

1. Open the `mobile_money_overview` dashboard: overcharged fees, commission variance, dormant outflow, KYC excess (the four KPIs).
2. Work float breaks first: drill into the unmatched `txn_id`s of `mm_bank_float` — [Reconciliation guide](../../module/reconciliation/user-guide-reconciliation.md).
3. For an agent with split transactions and a rising Risk Score, read the score factors — [Scoring guide](../../module/scoring/user-guide-scoring-entity-lists.md) — and follow `config/runbooks/mobile-money-runbooks.md`.
4. Batch wallet-level WARNINGs (fee mis-charge, provisioning mismatch) to operations.
5. For unusual wallet activity see the [Anomaly guide](../../module/anomaly/user-guide-anomaly.md).

## Network view

This pack has no Link Analysis view. Wallet and agent network investigation uses the separate `la-mobile-money` template and the [Mobile money industry pack](../industry-mobile-money/user-guide.md).
