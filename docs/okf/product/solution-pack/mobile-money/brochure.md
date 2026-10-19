# Mobile money — brochure

> **Prove the wallet platform agrees with the bank and partners, and catch agent and wallet abuse every day.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.mobile-money` · status **ga**. Prices are not published here.*

## Who it is for

Mobile-money operators: revenue assurance, fraud and finance teams responsible for float, commissions and agent networks.

## The problems it solves

- The wallet ledger and the trust-account bank statement do not agree.
- Partner settlement files differ from the wallet platform.
- Commissions and fees are over- or under-charged.
- Agents split transactions under the reporting limit or round-trip money; dormant wallets wake up; KYC tier limits are breached; wallets and the core network disagree.

## What is included

- Five Pipelines with schemas (wallet ledger, bank statement, partner settlement, wallet register, core subscriber snapshots).
- Two Reconciliations: wallet vs bank float, wallet vs partner settlement.
- Seven daily detection Jobs, each with a per-entity Alert Rule.
- A per-agent Risk Score and a wallet-activity Anomaly Model, each with an Alert Rule created after the first run.
- Four KPIs, a dashboard and runbooks.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md). It uses the [Reconciliation](../../addon/reconciliation/index.md), [Scoring & Lists](../../addon/scoringLists/index.md) and [Anomaly Detection](../../addon/anomalyDetection/index.md) Add-ons. Pair it with the [Mobile money industry pack](../../solution-pack/industry-mobile-money/brochure.md) for Link Analysis vocabulary.

## How it works

Feeds land daily; the Reconciliations match wallet, bank and partner records and raise breaks; the detection Jobs and Risk Score flag agents and wallets; every threshold and rate is configuration.

## Next step

Bring a month of wallet, bank and partner extracts for a reconciliation proof of value.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/mobile-money/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
