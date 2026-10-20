# Partner settlement — brochure

> **Settle with each partner under its own agreement: the right tolerance, the right currency, the right cycle, and a reason for every difference.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.partner-settlement` · status **preview** (References, statement ingestion and the compare). Prices are not published here.*

## Who it is for

Wholesale finance and revenue-assurance teams at operators and MVNOs that settle interconnect, SMS termination or roaming with partners and today compare a partner statement with a flat rate in a spreadsheet.

## The problems it solves

- One partner has several agreements, each with its own currency, tolerance, cut-off and cycle, and the terms change mid-year.
- A partner statement is a versioned document, sometimes re-sent or restated, and its own totals do not always add up.
- A variance is rarely a wrong price: it is a cut-off convention, a currency conversion or a late statement, and each needs a different conversation.
- Finance needs to know a statement is overdue, not just that a day has no traffic.

## What is included

- Six Pipelines for the partners, the agreements, the FX rates, the holidays, the statement and the independently re-rated usage.
- A settlement-cycle calendar with business-day due dates, six chained Jobs from statement integrity to settlement findings, two Reconciliations (voice, SMS) and one Alert Rule per agreement.
- Findings that name their reason: amount, cut-off, FX, missing statement, unrated usage, missing FX rate.
- A synthetic corpus with planted variances and the look-alikes that must stay silent, and golden tests with mutation checks.

## What it does not do

It posts no payment, accrual or ledger entry and fetches no market FX rate. Netting, the dispute Case with its partner-facing lifecycle, aging and the evidence pack follow in later slices; roaming (TAP) and MVNO constructs are demand-gated. No real partner statement has been used: the statement format is a synthetic proposal to be fitted to a real file in the first customer pass.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md).

## Next step

Load one partner's agreements and two months of statements and our re-rated usage in a proof of value, and compare the findings with what the finance team found by hand.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/partner-settlement/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
