# Telecom revenue assurance — brochure

> **Prove every call, message and session was switched, mediated, rated, billed and settled — and put a value on what was not.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.telco-ra` · status **ga**. Prices are not published here.*

## Who it is for

Revenue-assurance teams at operators and MVNOs.

## The problems it solves

- Records are lost between switch, mediation and rating.
- Rated usage is not billed, or is rated at the wrong tariff.
- Prepaid balances do not roll forward.
- Interconnect partners settle at the wrong rate.

## What is included

- Eight Pipelines on canonical schemas (switch, mediated and rated xDRs, billed invoices, tariff, balance ledger, interconnect rates and statements).
- Two Reconciliations: a three-way switch–mediation–rating completeness check and rated vs billed.
- Controls for lost xDRs, re-rating against the effective-dated tariff, balance roll-forward and interconnect settlement.
- Leakage and data-quality Datasets with their own Alert Rules, and a leakage impact ledger.
- Three KPIs, two Expectations, a dashboard (leakage found / confirmed / recovered) and a completeness tile (ships disabled).

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md). It uses the [Reconciliation](../../addon/reconciliation/index.md) and [Incidents](../../addon/incidents/index.md) Add-ons; a Reconciliation break opens an Incident whose impact and Disposition feed the impact ledger.

## How it works

The pack starts on a synthetic corpus with planted leakages and benign look-alikes. Replace each feed with your own extract; tolerances and thresholds are configuration.

## Next step

Bring a day of switch, mediation and billing extracts for a leakage proof of value.

## Read more

- Architecture: [revenue assurance](../business-assurance/architecture-revenue-assurance.md)
- The template manifest is `spaces/_templates/telco-ra/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
