# Link Analysis logistics cargo theft showcase — brochure

> **Twelve loads that arrive short, all driven by the same two-driver team and ten of them scanned out by the same night-shift scanner. A lay-by where every high-value truck that stopped for long was robbed. A carrier three weeks old, using a dismissed driver's phone number, whose five high-value loads never arrive. All of it is hidden among about 12 900 trips, 900 drivers and 120 carriers. You can find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-logistics` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Logistics security teams, shippers and cargo insurers, and presales teams who want to show Link Analysis on trip, GPS stop, warehouse scan and loss data.

## The problems it solves

- **Inside jobs:** losses that keep happening around the same people, such as a driver team and the warehouse employee who loads their trucks.
- **Theft hotspots:** a place where trucks stop and loads disappear.
- **Fictitious carriers:** a new "carrier" that takes high-value loads and never delivers them. This is often called double brokering.

Each story has an innocent look-alike that a simple count or threshold would also flag. The busiest driver on the busiest route loses a load now and then. Busy official services see the odd theft among hundreds of breaks. New carriers start up every month, and an owner-operator's phone is naturally its own driver's. The graph shows the difference.

## What is included

- Eight synthetic feeds: carriers, drivers, places (warehouses and stops on the road), shipments, trips, stop-level GPS, warehouse scans and loss reports. Twelve months are loaded, and one more month can be dropped in during the demo.
- Eight Collectors and Pipelines and a Dataset per feed. A Job builds one link Dataset of seven link kinds after every load, and a second Job derives one Dataset of three cargo-security screens.
- Three saved views, one per story, each small enough to draw whole.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. An Advanced tier is planned, with personas, an Alert Rule for a high-value trip given to a carrier under 60 days old, and a seeding script.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up. Then open a saved view and expand from its busiest node.

## Next step

Book a guided cargo-security walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-logistics/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
