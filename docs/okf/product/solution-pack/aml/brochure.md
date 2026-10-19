# AML — brochure

> **Anti-money-laundering typologies, an account Risk Score, screening and Link Analysis starters — ready to run on your ledger.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.aml` · status **ga**. Prices are not published here.*

## Who it is for

AML and financial-crime compliance teams at banks, payment providers and mobile-money operators.

## The problems it solves

- Structuring, threshold crossings and smurfing slip through daily volume.
- Wires to high-risk jurisdictions are not tied back to the account.
- Parties are not screened consistently against sanctions and PEP lists.
- Suspicious-transaction reporting is assembled by hand.

## What is included

- Two Pipelines (account ledger, party register) with schemas.
- Four daily detection Jobs — structuring, threshold crossing, smurfing, watch-list traffic — each with a per-account Alert Rule.
- An account Risk Score and an account-transfer Anomaly Model, each with an Alert Rule created after the first run.
- A screening Job against sanctions and PEP Entity Lists that you create (shipped disabled).
- A Link Analysis account-transfer view and four money pattern packs.
- An illustrative STR Report Template, three KPIs, a dashboard and runbooks.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md). It uses the [Scoring & Lists](../../addon/scoringLists/index.md), [Anomaly Detection](../../addon/anomalyDetection/index.md), [Screening](../../addon/screening/index.md) and [Regulatory Reporting](../../addon/regulatoryReporting/index.md) Add-ons included there.

## How it works

The pack starts on a synthetic golden corpus. Each detection Job runs daily over the previous day with a look-back window; every threshold is configuration. Alerts become Incidents and Cases, the Risk Score ranks accounts, and analysts open any account in Link Analysis to see who it moves money with.

## Next step

Run the typologies on a sample of your own ledger in a proof of value.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/aml/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
