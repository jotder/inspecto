# Link Analysis ownership showcase — brochure

> **Who really owns your customer? A hidden owner behind five layers of 24 % stakes, a sanctioned person two hops away, a nominee director of 120 companies and a circle of companies paying each other, found among 6 000 companies and 5 000 people.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-ownership` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Know-your-customer (KYC) and sanctions-compliance teams at a bank, and presales teams showing Link Analysis to them.

## The problems it solves

- **Beneficial ownership:** a customer's register entry names only its direct shareholders. Link Analysis follows the stakes upwards through holding companies in other jurisdictions to the person who really stands behind them.
- **Sanctions exposure through ownership:** a customer is not on the list, but the owner of its owner is. A fuzzy name match, held to the same birth year and nationality, joins the register to the list without flagging every near-namesake.
- **Nominee directors and circular ownership:** one person who directs a hundred companies at a formation agent's address, and companies that own each other in a circle while paying each other round it.

## What is included

- Seven feeds (company register, people, shareholdings, officers, bank customers, a year of payments and an **invented** sanctions list) landed by seven Collectors and Pipelines; one more month waits in a staged folder, for the "new data arrives" step of a demo.
- One link Dataset of five link kinds (`owns`, `officer_of`, `customer_is`, `paid`, `name_match`), built by one Job after every load.
- Four saved views, one per planted story, and a benign look-alike for each story that must not be mistaken for it.
- A user guide with a five-minute tour and one exercise per story, with hints and answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. An Advanced tier (personas, an Alert Rule on a new path to a listed person, a seeding script) is planned.

## How it works

Download the product, the Space and the data, unzip them side by side and start the server. The Pipelines land the data and the link Dataset is built on its own; open a saved view and start exploring.

## Next step

Book a guided KYC walk-through, or install it yourself with the [admin guide](admin-guide-install.md).

## Read more

- Tour and exercises: [user-guide.md](user-guide.md)
- Install: [admin-guide-install.md](admin-guide-install.md)
- The template manifest is `spaces/_templates/la-ownership/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
