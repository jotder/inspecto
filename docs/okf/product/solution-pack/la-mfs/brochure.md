# Link Analysis mobile financial services fraud showcase — brochure

> **Thirty customers lose their wallet balances within two hours of a SIM swap, all through one call-centre login. An agent earns commission on hundreds of tiny deposits into wallets it opened itself. A merchant that opened three weeks ago is paid only by new wallets, minutes after each was funded with cash. All of it is hidden among 8 000 wallets, 300 agents and 400 merchants. You can find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-mfs` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Fraud and risk teams at a mobile operator's mobile financial services (MFS) business, and at the agent networks and merchants it serves. It is also for presales teams who want to show Link Analysis on wallet, SIM register, app login, agent and merchant data. Wallet anti-money-laundering (structuring, mule chains, a device farm) is in the separate [`la-mobile-money`](../la-mobile-money/index.md) Space. This one covers fraud against the MFS business and its customers.

## The problems it solves

- **SIM-swap wallet takeover:** a fraudster gets a customer's number moved to a new SIM, logs into the wallet from their own phone and empties it before the customer notices.
- **Agent split-deposit commission fraud:** an agent is paid a commission per transaction, so it splits each deposit into many small ones.
- **Merchant cash-out collusion:** a merchant account is used to turn cash into bank money. Wallets are funded at a few agents and pay the merchant at once, and the merchant settles to its bank every day.

Each story has an innocent look-alike that a simple threshold would also flag. A busy store swaps many SIMs during an upgrade drive. A market agent takes many small deposits from traders. A grocery's shoppers often pay right after cashing in next door. The graph shows the difference.

## What is included

- Seven synthetic feeds: agents, merchants, wallets, wallet transactions, SIM events, app logins per handset and day, and monthly agent commissions. Twelve months are loaded, and one more month can be dropped in during the demo.
- Seven Collectors and Pipelines and a Dataset per feed. A Job builds one link Dataset of seven link kinds after every load, and a second Job derives one Dataset of three fraud screens.
- Three saved views, one per story, each small enough to draw whole.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. An Advanced tier is planned, with personas, an Alert Rule for a wallet drained within 2 hours of a SIM swap, and a seeding script.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up. Then open a saved view and expand from its busiest node.

## Next step

Book a guided MFS fraud walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-mfs/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
