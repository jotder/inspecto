---
type: Feature
title: "Payment fraud starter — features"
description: "Payment fraud typologies, rolling-window features and an account Risk Score."
tags: [payment-fraud, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Payment fraud starter — features

**Product Map Item:** [`solution-pack.payment-fraud`](../../../../../product-map/solution-pack/payment-fraud.toon) · **Status:** ga · **Availability:** Billable content pack `INS-SP-PAYMENT-FRAUD`, Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`).

## What it is

A Space Template (`payment-fraud`, slice 1) over synthetic payment attempts, SIM changes and disputes, with a card-number scan at ingest, rolling-window features, four payment typologies and a per-account Risk Score.

## Who benefits

Card and wallet payment fraud teams.

## Key capabilities

- 3 Pipelines that scan every row for a Luhn-valid, card-shaped number and move such a file to the restricted quarantine — [etl](../../module/etl/index.md)
- 7 feature Datasets built hourly by `sql.template` Jobs over rolling windows
- Dispute labels with a maturity flag (`DISPUTED`, `NEGATIVE`, `UNMATURED`)
- 4 per-entity Alert Rules: card testing, BIN attack, velocity burst, SIM-swap takeover — [incidents](../../addon/incidents/index.md)
- Per-account Risk Score `payment_account` with a default factor table, plus a pending high-risk Alert Rule — [scoring](../../module/scoring/index.md)
- 4 KPIs and the `payment_fraud_overview` dashboard

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md)
