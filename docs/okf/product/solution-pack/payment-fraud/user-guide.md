---
type: Feature
title: "Payment fraud starter — user guide"
description: "How a fraud analyst works with the Payment fraud pack day to day."
tags: [payment-fraud, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Payment fraud starter — user guide

What a Space created from the `payment-fraud` template gives a fraud analyst. Design detail is in [architecture](architecture.md).

## Data arriving

Three feeds — `payment_attempts`, `sim_changes`, `disputes` — land through their Pipelines. A file holding a card-shaped, Luhn-valid number is not ingested; it goes to the restricted quarantine. The scan misses a number split across two cells, glued to other digits or in an unlisted layout (`template.toon`).

## The hourly cycle

| When | What runs | Output |
|---|---|---|
| :00 | 7 feature Jobs | `pf_account_activity`, `pf_attempt_labels`, `pf_bin_declines`, `pf_daily_summary`, `pf_device_small_amounts`, `pf_instrument_velocity`, `pf_sim_swap_payments` |
| :30 | `pf_risk_score` | Risk Score `payment_account` per account |

Each feature Dataset ships as a zero-row seed that the first run replaces.

## Alerts and Incidents

All four typology Alert Rules are CRITICAL and keyed on the offender: `pf_card_testing` by `device_id`, `pf_bin_attack` by `bin`, `pf_velocity_burst` by `instrument_token`, `pf_sim_swap_takeover` by `account_id`. After the first Risk Score run the pending `pf_high_risk_account` (score at or above 60) is added. Working them: [Alerts](../../addon/incidents/user-guide-alerts.md) · [Incidents](../../addon/incidents/user-guide-incidents.md).

## A typical day

1. Open `payment_fraud_overview`: attempted amount, declined attempts, disputed amount, disputes opened.
2. Triage card-testing and BIN-attack Alerts first.
3. For a SIM-swap takeover, read the account's Risk Score factors (SIM-swap payments, velocity, declines, disputes) — [Scoring guide](../../module/scoring/user-guide-scoring-entity-lists.md) — then follow `config/runbooks/payment-fraud-runbooks.md`.
4. Use matured labels in `pf_attempt_labels` (120-day maturity) to judge which past Alerts were real before changing thresholds.
