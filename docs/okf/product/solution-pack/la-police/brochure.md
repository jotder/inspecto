# Link Analysis police showcase — brochure

> **A burglary series traced through second-hand dealer sales and one vehicle, a drug-supply network whose controller number keeps changing, drug bases at vulnerable residents' addresses and a witness who was arrested with the suspect, hidden among 4 000 incidents and 6 000 people. Find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-police` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Police intelligence analysts and investigators, and presales teams who want to show Link Analysis on incident,
custody, vehicle, call and stolen-property data.

## The problems it solves

- **Burglary series:** 18 burglaries in three months, unsolved one by one. Stolen items resurface at two
  second-hand dealers through three sellers, and a vehicle kept by one seller's partner is read by cameras near most
  of the scenes.
- **Drug-supply network:** customers keep calling one controller number, and the number changes every few weeks.
  Most customers carry over to the new number, so the numbers can be tied together.
- **Drug bases at vulnerable residents' addresses:** three lone residents with no record are rung again and again by
  the network's runners.
- **A witness linked to the suspect:** a witness to an assault was arrested together with its suspect three months
  before.

Each story has an innocent look-alike that a simple count would flag: a taxi seen near dozens of scenes, a delivery
driver who makes hundreds of short calls, a busy shared house, and a shopkeeper who witnesses incidents outside the
shop. The graph shows the difference.

## What is included

- Eleven synthetic feeds (incident reports, people, people on incidents, vehicles, number-plate camera reads, calls,
  handsets, custody, second-hand dealer sales, stolen property, addresses): twelve months landed and one more month
  to drop in during the demo.
- Eleven Collectors and Pipelines, a Dataset per feed, one link Dataset of nine link kinds built by a Job, and three
  focused link Datasets derived from it, one per saved view.
- Three saved views (the burglary series, the handset network, the witness link) and a saved Geo View of the
  incidents.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations
are needed. The saved Geo View needs the Geo map (Standard and above). The same download carries an Advanced tier for
builds where people sign in: an analyst, a senior investigating officer who approves large expands, a read-only
reviewer and an outsider; an Alert Rule that raises a warning when stolen serials keep resurfacing through the same
seller; and a seeding script that opens the Investigation, an Entity List of the sellers and a Case. On Basic it stays
inert.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the
data on start-up and the link Job runs within five minutes; open a saved view and expand from its busiest node.

## Next step

Book a guided police-intelligence walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-police/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
