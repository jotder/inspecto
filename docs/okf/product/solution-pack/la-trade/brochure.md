# Link Analysis trade showcase — brochure

> **Is the trade real? Prices eight times the market between two companies with one director, six containers shipped round a circle, letters of credit paid on a vessel that was somewhere else, and six new importers at one address paying an exporter thirty-six times what it shipped them, found among 12 000 trades and 300 vessels.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-trade` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Trade-finance compliance teams at a bank (letters of credit and documentary collections), and presales teams showing Link Analysis to them.

## The problems it solves

- **Mis-invoicing:** a unit price far above the market median moves value from importer to exporter under cover of a genuine shipment. Comparing each trade with a monthly reference price, then looking at who runs both companies, separates a laundering pair from a supplier that is simply expensive.
- **Phantom shipments:** a bill of lading is only paper. Checking each one against where its vessel really was on the B/L date shows letters of credit paid on goods that never sailed, without flagging honest transhipments.
- **Circular trade and shell importers:** the same boxes shipped round a ring of related companies at rising values, and newly formed importers at a formation agent's address paying an exporter for far more than it ever shipped.

## What is included

- Seven feeds (company register, directors, trades, bills of lading, a daily vessel position for 300 vessels, monthly reference prices for 62 HS codes and a year of payments) landed by seven Collectors and Pipelines; one more month waits in a staged folder, for the "new data arrives" step of a demo.
- One link Dataset of six link kinds (`traded_with`, `on_vessel`, `shipped_in`, `directed_by`, `registered_at`, `paid`) with a red-flag column, built by one Job after every load, and one smaller Dataset per saved view.
- Four saved views, one per planted story, each showing a benign look-alike beside its story that must not be mistaken for it.
- A user guide with a five-minute tour and one exercise per story, with hints and answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. An Advanced tier (personas, an Alert Rule on a unit price over three times the reference, a seeding script) is planned.

## How it works

Download the product, the Space and the data, unzip them side by side and start the server. The Pipelines land the data and the link Datasets are built on their own; open a saved view and start exploring.

## Next step

Book a guided trade-finance walk-through, or install it yourself with the [admin guide](admin-guide-install.md).

## Read more

- Tour and exercises: [user-guide.md](user-guide.md)
- Install: [admin-guide-install.md](admin-guide-install.md)
- The template manifest is `spaces/_templates/la-trade/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
