# Telecom re-rating (tariff validation) — brochure

> **Check what you billed against your own tariff, independently of the billing system, and see which of the differences a tariff defect explains.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.telco-rerating` · status **preview** (tariff versioning and ambiguity). Prices are not published here.*

## Who it is for

Revenue-assurance teams at operators and MVNOs that want an independent re-rating of their rated usage.

## The problems it solves

- The tariff table itself is the biggest source of error: overlapping versions, gaps and bad rates are usually found only after a billing run, call by call.
- One duplicate tariff row can raise hundreds of identical findings; here it is one finding on the table, and the calls it touches are judged against the one agreed amount.
- Where a tariff is genuinely ambiguous, a guess would overstate leakage. The pack gives the range an ambiguous tariff allows and claims only what lies outside it.
- Over-billing is a customer and regulatory exposure, not recovered revenue; it is reported as its own figure and never nets against under-charging.

## What is included

- Two Pipelines (rated usage, tariff versions) and two chained Jobs.
- A tariff-hygiene finding per defect and a re-rated row per call, with the tariff version it used.
- A synthetic corpus with planted defects and the look-alikes that must stay silent, and a golden test that holds the Jobs to them.

## What it does not do

It does not rate production traffic, write to a billing system or price anything. It does not yet explain causes, group them, compare with the invoice or raise Alerts; those follow in later slices. A re-rated amount shows a charge differs from the tariff as written, not that the table is right.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md).

## Next step

Run it on an anonymised extract of your tariff table and one day of rated usage in a proof of value.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/telco-rerating/template.toon`.
- The starter control it extends: [Telecom revenue assurance](../telco-ra/brochure.md).
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
