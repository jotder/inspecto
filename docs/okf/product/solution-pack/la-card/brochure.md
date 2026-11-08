# Link Analysis card fraud showcase — brochure

> **Card testing, an account-takeover ring, a colluding merchant and a compromised fuel pump, hidden among 6 500 ordinary cards. Find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-card` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Card fraud operations at an issuer or payment service provider, and presales teams who want to show Link Analysis on card, device and merchant data.

## The problems it solves

- **Card testing:** one device and one network try hundreds of stolen cards at small online shops for 1.00 each.
- **Account takeover:** a few devices sign in to many online accounts and move their deliveries to drop addresses.
- **Colluding merchant:** a merchant refunds cards it never sold to, and its chargebacks run far above its category.
- **Common point of purchase:** cards defrauded weeks apart all paid at one fuel station in the same three weeks.

Each story has an innocent look-alike that a rule alone would flag: a corporate proxy shared by many genuine cardholders, a family tablet, a travel merchant hit by a strike, and a busy supermarket. The graph shows the difference.

## What is included

- Six synthetic feeds (cardholders, cards, merchants, authorisations, chargebacks, online-account changes): twelve months landed and one more month to drop in during the demo.
- Six Collectors and Pipelines, a Dataset per feed, and one link Dataset of eight link kinds built by a Job after every load.
- Four saved views, one per story.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. The same download carries an Advanced tier for builds with sign-in: four personas (lead analyst, second approver, read-only reviewer, outsider), an Alert Rule for cards still in use at the compromised pump, and a seeding script for the Investigation, the list of cards to reissue, a Case and a four-eyes approval. It is inert on Basic.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up; open a saved view and expand from its busiest node.

## Next step

Book a guided card-fraud walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-card/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
