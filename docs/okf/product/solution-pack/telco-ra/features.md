---
type: Feature
title: "Telecom revenue assurance — features"
description: "Completeness, re-rating, roll-forward and settlement controls over canonical synthetic telecom feeds."
tags: [telco-ra, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom revenue assurance — features

**Product Map Item:** [`solution-pack.telco-ra`](../../../../../product-map/solution-pack/telco-ra.toon) · **Status:** ga · **Availability:** billable Solution Pack `INS-SP-TELCO-RA`, listed under `contentPacks` in `offerings/standard.toon` (Advanced and Preview include Standard); Space Template `telco-ra`, `minEdition: standard`.

## What it is

A revenue-assurance starter on canonical synthetic schemas: switch, mediated and rated usage xDRs, billed invoices, a tariff table, a prepaid balance ledger, interconnect rates and partner statements. Tolerances and thresholds are configuration.

## Who benefits

Revenue-assurance teams at operators and MVNOs.

## Key capabilities

- Eight Pipelines with canonical schemas.
- Three-way switch–mediation–rating completeness Reconciliation, with a lost-xDR Job.
- Rated-vs-billed Reconciliation.
- `sql.template` controls for re-rating against the effective-dated tariff, balance roll-forward and interconnect settlement.
- Leakage and data-quality findings in separate Datasets, each with its own Alert Rule (one Alert per control).
- Reconciliation breaches open an Incident whose impact and Disposition feed the impact ledger.
- Three KPIs, two Expectations, the `ra_overview` dashboard (leakage found / confirmed / recovered) and a completeness tile (`kpi.completeness` Job, ships disabled).
- A synthetic golden corpus with planted leakages and benign look-alikes.

## Related hubs

[Architecture](architecture.md) · [Brochure](brochure.md) · [reconciliation](../../addon/reconciliation/index.md) · [incidents](../../addon/incidents/index.md) · [telecomIndustryPack](../../addon/telecomIndustryPack/index.md)
