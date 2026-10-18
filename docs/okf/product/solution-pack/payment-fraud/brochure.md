# Payment fraud starter — brochure

> **Card testing, BIN attacks, velocity bursts and SIM-swap takeovers — detected per account, with card numbers kept out of your data.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.payment-fraud` · status **ga**. Prices are not published here.*

## Who it is for

Payment and card fraud teams at issuers, acquirers, PSPs and wallet providers.

## The problems it solves

- Card testing and BIN attacks hide in high-volume attempts.
- A SIM swap followed by payments signals an account takeover.
- Raw card numbers leak into analytical data.

## What is included

- Three Pipelines (payment attempts, SIM changes, disputes) that scan every row at ingest for a valid card number and quarantine any file that carries one.
- Seven rolling-window feature Datasets.
- Dispute labels with a maturity flag.
- Four typology Alert Rules: card testing, BIN attack, velocity burst, SIM-swap takeover.
- A per-account Risk Score with a default factor table, and a high-risk Alert Rule created after its first run.
- Four KPIs, a dashboard and a synthetic golden corpus.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md). It uses the [Scoring & Lists](../../addon/scoringLists/index.md) Add-on.

## How it works

Attempts, SIM changes and disputes land daily. The card-number scan runs before anything is stored. Jobs build rolling features, Alert Rules fire per account, and the Risk Score ranks accounts; thresholds are configuration. The scan catches standard layouts; a number split across cells or glued to other digits is not caught.

## Next step

Run it on an anonymised extract of your payment attempts in a proof of value.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/payment-fraud/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
