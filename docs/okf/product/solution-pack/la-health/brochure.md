# Link Analysis provider billing fraud showcase — brochure

> **Two practices steering their referrals to an imaging centre owned by one doctor's spouse, a clinic billing patients who were in hospital or already dead, a pain clinic whose patients drive 100 km and pay cash, and three clinics under one owner billing the top visit level, hidden among 20 000 members and 80 000 claim lines. Find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-health` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Program-integrity and special-investigations teams at health insurers and public payers, and presales teams who want to show Link Analysis on claim lines, hospital stays, referrals, prescriptions and the provider enrolment file.

## The problems it solves

- **Referral kickbacks:** practices send most of their referrals to one provider, and the provider's owner is related to the practice's owner.
- **Phantom billing:** a clinic bills for sessions on days its patients were in hospital elsewhere, or after they died.
- **Pill mill:** one prescriber writes large strong-opioid prescriptions for patients who travel far and pay cash, filled at the same few pharmacies.
- **Upcoding:** clinics under one owner bill the highest visit level far more often than their patients' ages explain.

Each story has an innocent look-alike that a simple threshold check alone would flag: a seaside practice that sends nearly everything to the only hospital in town, a clinic whose date errors were reversed within a week, an oncology centre with many strong-opioid patients, and an elderly-care clinic whose patients really are over 80. The graph shows the difference.

## What is included

- Nine synthetic feeds (addresses, owners, providers, provider ownership, members, claim lines, hospital stays, referrals, prescriptions): twelve months landed and one more month to drop in during the demo.
- Nine Collectors and Pipelines, a Dataset per feed, one link Dataset of six link kinds built by a Job after every load, and one derived Dataset of four program-integrity screens.
- Four saved views, one per story, each small enough to draw whole.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. The same download carries an Advanced tier for builds with sign-in: four personas (lead analyst, second approver, read-only reviewer, outsider), an Alert Rule for a service billed during a hospital stay elsewhere or after death, and a seeding script for the Investigation, the list of patients billed, a Case and a four-eyes approval. It is inert on Basic.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up; open a saved view and expand from its busiest node.

## Next step

Book a guided program-integrity walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-health/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
