---
type: Feature
title: "AML — features"
description: "Anti-money-laundering typologies, an account Risk Score, an Anomaly Model, screening and Link Analysis starters over a synthetic account feed."
tags: [aml, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# AML — features

**Product Map Item:** [`solution-pack.aml`](../../../../../product-map/solution-pack/aml.toon) · **Status:** ga · **Availability:** billable Solution Pack `INS-SP-AML`, listed under `contentPacks` in `offerings/standard.toon` (Advanced and Preview include Standard); Space Template `aml`, `minEdition: standard`.

## What it is

An AML function pack over two synthetic feeds: a core-banking account ledger (`aml_txn`) and a party register (`aml_parties`). Every threshold is configuration.

## Who benefits

AML analysts, compliance officers and screening reviewers.

## Key capabilities

- Four daily detection Jobs (`sql.template`) over the previous day with a look-back window, each with a per-account Alert Rule:
  - structuring — repeated cash deposits just under the reporting limit;
  - threshold crossing — a day's cash over the limit, single or split;
  - smurfing — many small payers into one account (`aml_fan_in`);
  - watch-list traffic — wires to a configured high-risk jurisdiction list.
- Account Risk Score (`risk-scores/aml_account.toon`) combining structuring, smurfing and high-risk-wire signals; its Alert Rule ships pending and is created after the score's first run.
- Account-transfer Anomaly Model (`anomaly-models/aml_account_transfers.toon`) comparing each account's daily outgoing transfer and wire count and value with its own history; its Alert Rule is likewise pending.
- Screening Job of the party register against two Entity Lists (`aml_sanctions`, `aml_pep`) that you create; the Job ships disabled.
- Link Analysis starters: an account-transfer view and four money pattern packs (circular flow, inbound collector, layering chain, pass-through).
- Illustrative STR Report Template (`regulatory-report-templates/aml-str.toon`).
- Three KPIs, the `aml_overview` dashboard and runbooks (`config/runbooks/aml-runbooks.md`).

## Related hubs

[Architecture](architecture.md) · [Brochure](brochure.md) · [scoringLists](../../addon/scoringLists/index.md) · [anomalyDetection](../../addon/anomalyDetection/index.md) · [screening](../../addon/screening/index.md) · [regulatoryReporting](../../addon/regulatoryReporting/index.md) · [linkAnalysis](../../addon/linkAnalysis/index.md)
