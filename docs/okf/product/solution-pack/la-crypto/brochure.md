# Link Analysis crypto laundering showcase — brochure

> **Coins stolen from a hacked wallet are peeled off, a little at a time, over 80 hops into six exchange accounts. Ransom money goes into a mixer and comes out under two hours later, 98 % of it, at a fresh address that pays one exchange account. Forty exchange accounts in forty unrelated names are all funded by one wallet cluster and all cash out to the same three bank accounts. All of it is hidden among 10 900 addresses and 44 500 transfers. You can find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-crypto` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Compliance teams at a virtual-asset service provider (an exchange, a broker, a custodian), and the investigators who work with them. It is also for presales teams who want to show Link Analysis on blockchain transfers, address clusters, exchange accounts and their KYC records, and fiat cash-outs.

## The problems it solves

- **Peel chain:** stolen coins are moved through a long chain of fresh addresses. Each one peels a small amount to an exchange and passes the rest on, so no single deposit looks large.
- **Mixer hop:** coins are sent through a mixer to break the trail. When they come out soon after, at almost the same amount, timing and amount still tie the two sides together.
- **Converging cash-out:** one owner's funds are spread across many exchange accounts opened in other people's names, and the money comes back together in a few bank accounts.

Each story has an innocent look-alike that a simple rule would also flag. A company payroll wallet pays its staff one by one, with the change going to a fresh address each time, which is the same shape as a peel chain. Many people use the mixer, and a few of them have a near miss in time or amount. An over-the-counter desk pays many customers' exchange accounts. The graph shows the difference.

## What is included

- Six synthetic feeds: addresses and their labels, on-chain transfers, address clusters, exchange accounts, the people the exchanges identified, and fiat cash-outs to bank accounts. Twelve months are loaded, and one more month can be dropped in during the demo.
- Six Collectors and Pipelines and a Dataset per feed. A Job builds one link Dataset of six link kinds after every load, including a matched-mixer-exit link worked out from timing and amount. A second Job derives one Dataset of three screens.
- Three saved views, one per story, each small enough to draw whole.
- A user guide with a five-minute tour and one exercise per story, with answers.

Every address uses an invented `syn1…` format that cannot match a real blockchain. The coins (SYC and the stable token SUSD), the five exchanges, the mixer, the company, the people and the bank accounts are all invented.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. The same download carries an Advanced tier for builds with sign-in: four personas (lead analyst, second approver, read-only reviewer, outsider), an Alert Rule for a deposit whose funds trace to a labelled hack within 5 hops, and a seeding script for the Investigation, a watch list of the exchange accounts the stolen coins reached, a Case and a four-eyes approval. It is inert on Basic.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up. Then open a saved view and expand from its busiest node.

## Next step

Book a guided crypto laundering walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-crypto/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
