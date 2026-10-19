---
type: Feature
title: "Mobile money — features"
description: "Wallet, agent, bank and partner controls over mobile-money feeds."
tags: [mobile-money, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Mobile money — features

**Product Map Item:** [`solution-pack.mobile-money`](../../../../../product-map/solution-pack/mobile-money.toon) · **Status:** ga · **Availability:** Billable content pack `INS-SP-MOBILE-MONEY`, Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`).

## What it is

A Space Template (`mobile-money`) that wires wallet, agent, bank and partner controls over five synthetic feeds: wallet ledger, wallet register, trust-account bank statement, partner settlement file and core-network subscriber snapshots.

## Who benefits

Mobile-money revenue assurance, fraud and operations teams.

## Key capabilities

- 5 Pipelines with Schemas — [etl](../../module/etl/index.md)
- 2 Reconciliations: wallet vs bank float, wallet vs partner settlement — [reconciliation](../../module/reconciliation/index.md)
- 7 daily detection Jobs: commission over/under-pay, fee mis-charge, agent split transactions, agent round-tripping, dormant-wallet reactivation, KYC tier-limit breach, wallet-vs-core provisioning mismatch
- 7 per-entity Alert Rules keyed on the offending agent or wallet — [incidents](../../addon/incidents/index.md)
- Per-agent Risk Score (split, round-trip, commission) with a pending high-risk Alert Rule — [scoring](../../module/scoring/index.md)
- Wallet-activity Anomaly Model with a pending unusual-activity Alert Rule — [anomaly](../../module/anomaly/index.md)
- 4 KPIs, the `mobile_money_overview` dashboard and runbooks

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md) · [Mobile money industry pack](../industry-mobile-money/index.md)
