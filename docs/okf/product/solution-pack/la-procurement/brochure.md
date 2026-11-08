# Link Analysis procurement fraud showcase — brochure

> **Bid rotation, cover bids, an official approving changes for a relative's firm, and a debarred firm reborn under its old directors, hidden among 1 200 ordinary tenders. Find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-procurement` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Public-procurement integrity units and internal audit teams, and presales teams who want to show Link Analysis on tender, bid, company-register and contract data.

## The problems it solves

- **Bid rotation:** a few firms take turns to win one buyer's tenders, the others bidding a little higher each time.
- **Cover bids:** "competitors" send their bids from the winner's own office, minutes after it, written by its estimator.
- **Conflict of interest:** an official keeps approving contract changes for a firm whose director lives at the official's home.
- **Phoenix firm:** a debarred firm's directors start a new company that is winning public contracts within weeks.

Each story has an innocent look-alike that a rule alone would flag: three large firms that win often but compete honestly, the buyer's public help-desk kiosk that many small firms bid from, a busy contracts manager who approves many changes for many suppliers, and a director who joins an unrelated firm years after a debarment. The graph shows the difference.

## What is included

- Seven synthetic feeds (companies, directors, people, buying officials, tenders, bids, contract changes): twelve months landed and one more month to drop in during the demo.
- Seven Collectors and Pipelines, a Dataset per feed, one link Dataset of seven link kinds built by a Job after every load, and two small derived Datasets for the approvals and debarment views.
- Four saved views, one per story, each small enough to draw whole.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. An Advanced tier (personas, an Alert Rule for a bid from an IP address another bidder on the same tender already used, a seeding script) is planned.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up; open a saved view and expand from its busiest node.

## Next step

Book a guided procurement-integrity walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-procurement/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
