# Business assurance — brochure

> **See revenue drift and margin erosion the day they happen, not at quarter end.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.business-assurance` · status **ga**. Prices are not published here.*

## Who it is for

Finance, commercial and business-assurance teams who track daily revenue and margin by product, channel and partner.

## The problems it solves

- A bad revenue day hides inside normal seasonality.
- Margin erodes slowly in one product, channel or partner and no one owns spotting it.

## What is included

- A seasonal (Holt-Winters) revenue forecast with a prediction band; an Alert for each day outside the band.
- Margin Datasets by product, channel and partner, with an Alert where margin eroded past the threshold.
- Five Alert Rules, three KPI definitions and a dashboard.
- Two Jobs (shipped disabled) that run the same SQL over your own revenue and margin feeds.

## How it fits with the Editions

Ships with every Edition from [Basic](../../edition/basic/brochure.md) up. On [Standard](../../edition/standard/brochure.md) and above, its Alerts can feed Incidents and Cases.

## How it works

The pack starts on a deterministic synthetic corpus (26 weeks of revenue, 8 weeks of sales lines) so you see results immediately. Point the two Jobs at your own daily revenue and margin Datasets, tune the thresholds in configuration, and enable them.

## Next step

Run it on a few months of your own revenue extract in a proof of value.

## Read more

- Architecture: [architecture.md](architecture.md)
- Architecture: [architecture-revenue-assurance.md](architecture-revenue-assurance.md)
- The template manifest is `spaces/_templates/business-assurance/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
