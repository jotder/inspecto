# Link Analysis utility showcase — brochure

> **Meter tampering behind one contractor, illegal reconnections, debt-dodging account churn, a grow-house load and prepaid token fraud, hidden among 1 000 electricity premises. Find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-utility` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Revenue-protection and loss-reduction teams at an electricity supplier or distribution company (non-technical loss), and presales teams who want to show Link Analysis on meter, field-work and payment data.

## The problems it solves

- **Meter tampering:** premises register 40–70 % less within two weeks of a visit by one contractor, while the transformer that supplies them sends out as much as before, so the loss on that transformer climbs.
- **Illegal reconnection:** premises cut off for arrears keep consuming, and their reconnect orders were closed by one contractor with no payment.
- **Debt-dodging account churn:** accounts close with arrears and reopen within days at the same premise under another name, paid from the same bank account.
- **Commercial load on a residential tariff:** homes that draw a flat round-the-clock load several times their street's, all paid from one account.
- **Prepaid token fraud:** one vending point credits far more kWh than the money it takes, mostly at night.

Each story has an innocent look-alike that a threshold alone would flag: a holiday home empty every winter, customers reconnected after paying, a genuine change of tenant, a home with a heat pump and an electric car, and a busy supermarket vending point. The graph shows the difference.

## What is included

- Eight synthetic feeds (premises, customer accounts, daily meter reads, transformer reads, field visits, service orders, prepaid token sales, payments): twelve months landed and one more month to drop in during the demo.
- Eight Collectors and Pipelines, a Dataset per feed, one link Dataset of six link kinds built by a Job after every load, a monthly energy balance per metered transformer, and a narrowed account Dataset for the churn view.
- Three saved views and one saved Geo View (loss by transformer area).
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. The saved Geo View needs the Geo map (Standard and above); on Basic the same figures are in the `transformer_loss` Dataset. An Advanced tier (personas, an Alert Rule on a transformer's loss going over 15 %, a seeding script) is planned.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up; open a saved view and expand from its busiest node.

## Next step

Book a guided revenue-protection walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-utility/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
