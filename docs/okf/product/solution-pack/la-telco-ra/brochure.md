# Link Analysis telecom revenue assurance showcase — brochure

> **Three hundred SIMs that use the network every day and never reach billing, a dealer earning commission on 400 SIMs that go silent after one top-up, and four company phone systems dialling premium-rate numbers abroad at night. All of it is hidden among 7 500 subscribers and 150 business trunks. You can find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-telco-ra` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Revenue-assurance and fraud teams at a mobile operator, and presales teams who want to show Link Analysis on activation, usage, billing, top-up and switch data. SIM box, Wangiri and international revenue share fraud are in the separate [`la-showcase`](../la-showcase/index.md) Space; this one covers three other leaks.

## The problems it solves

- **Unbilled usage:** SIMs that use the network but never reach the billing system, so their usage is never charged.
- **Dealer commission fraud:** a dealer activates SIMs only to collect the activation commission. Each SIM gets one small top-up and is never used again.
- **PBX hacking:** a business's phone system is broken into, and its lines call premium-rate numbers abroad while the office is closed.

Each story has an innocent look-alike that a simple reconciliation or threshold check would also flag. The network test team's own SIMs are never billed either. A dealer near a university activates hundreds of SIMs when term starts. A firm calls its Singapore office every night, because that is the office's working day. The graph shows the difference.

## What is included

- Seven synthetic feeds: dealers, subscribers, monthly network usage, monthly billing records, top-ups, enterprise trunks and their international calls. Twelve months are loaded, and one more month can be dropped in during the demo.
- Seven Collectors and Pipelines and a Dataset per feed. A Job builds one link Dataset of five link kinds after every load, and a second Job derives one Dataset of three revenue-assurance screens.
- Three saved views, one per story, each small enough to draw whole.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. An Advanced tier is planned, with personas, an Alert Rule for usage without billing and a seeding script.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up. Then open a saved view and expand from its busiest node.

## Next step

Book a guided revenue-assurance walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-telco-ra/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
