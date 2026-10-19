# Telecom fraud — brochure

> **Fourteen telecom fraud typologies, per offender, from your CDR, subscriber and payment feeds.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.telco-fraud` · status **ga**. Prices are not published here.*

## Who it is for

Fraud management teams at mobile and fixed operators and MVNOs.

## The problems it solves

- IRSF, Wangiri, SIM boxes and premium-rate abuse cost money every hour they run.
- Subscription, dealer, voucher, recharge and internal fraud are spread across different systems.
- Correlated alarms are worked one by one instead of as one Case.

## What is included

- Three Pipelines (CDR, subscriber events, payments) with schemas and seven data-quality Expectations.
- Fourteen detection Jobs — IRSF, Wangiri, SIM box, premium rate, roaming high usage, SIM swap, subscription/identity, dealer activations, voucher/EVD, payment reversal, recharge, data-charging bypass, internal fraud, manual adjustments — each with a per-offender Alert Rule.
- Two Decision Rules and a Case Rule that groups correlated critical Incidents.
- A nightly export of IRSF offenders.
- A subscriber-usage Anomaly Model with an Alert Rule created after its first run.
- KPIs, a dashboard and runbooks.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md). Pair it with the [Telecom industry pack](../../solution-pack/industry-telecom/brochure.md) for Link Analysis and, for raw switch records, the [Telecom ASN.1 decoders](../../addon/telecomIndustryPack/index.md).

## How it works

Feeds land and are checked by Expectations; each typology Job runs over its window and its Alert Rule raises an Alert per offender; the Case Rule groups correlated Incidents for one analyst. Every threshold is configuration.

## Next step

Run the fourteen typologies on a week of your CDRs in a proof of value.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/telco-fraud/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
