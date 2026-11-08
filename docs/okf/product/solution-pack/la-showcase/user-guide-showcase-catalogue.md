---
type: Feature
title: "Link Analysis showcase catalogue — user guide"
description: "Pick a Link Analysis showcase Space: the 18 templates grouped by 9 industries, with what each teaches, its planted stories, its edition tiers, its download size and its user guide; how to install one, and where to start."
tags: [la-showcase, user-guide, showcase, catalogue]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis showcase catalogue — user guide

Inspecto ships **18 Link Analysis showcase Spaces in 9 industries**. Each is a Space Template
(`spaces/_templates/<id>`) with invented data and planted stories, so you can **play** with Link Analysis on realistic
data, **learn** it through exercises with answers, and **test** it (and your own understanding) against known ground
truth. Every story has a benign look-alike that must not join it; the user guides name them. Synthetic data only: no
real people, businesses or plates.

This page helps you pick one. The screens are the shared studio ones, described in the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Design and plan:
[showcase plan](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md).

## Where to start

1. **`la-sample` first.** *Try it with sample data* on the Link Analysis *Getting started* page creates it in one click:
   one Pipeline, three days of invented transfers and one saved view that draws a money-mule ring. It needs no download.
2. **Then one Space per industry** that matters to you. A good first pick in each, all with exercises and answers in
   their user guide:

   | Industry | Start with |
   |---|---|
   | Telecom | `la-telco-ra` |
   | Banking and finance | `la-card` |
   | Insurance | `la-claims` |
   | Utilities | `la-utility` |
   | Law enforcement | `la-police` |
   | Public sector | `la-procurement` |
   | Healthcare | `la-health` |
   | Logistics | `la-logistics` |
   | Enterprise | `la-insider` |

3. **Testers:** the exercise answers are the tester's checklist, including the look-alikes that must **not** appear;
   most guides also end with a *Tester's checklist* or *For testers* section. The answers are in the guide, not in the data, so you can work
   blind.

## How to install a showcase

A showcase ships as **three downloads** that you unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching any Space or its data:

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space (configuration) | `inspecto-la-space-<id>-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-<id>-<version>.zip` | `<parent>/inspecto-la-data/` |

Start the product with `SPACES_ROOT` pointing at the Spaces folder and `SPACES_DATA_ROOT` at the data folder
(relative to the bundle: `..\inspecto-la-spaces` and `..\inspecto-la-data`). The Pipelines land the data on start.
To reset a showcase, stop the product, delete the Space's data folder and unzip its data download again. Install the product once and add as many showcases as you like.

The full steps, the start command and the checks are in each Space's install guide; see the *Install* column below.
The variables are in the [configuration reference](../../module/config/admin-guide-configuration-reference.md).
`la-sample` is the exception: its files are in `data/samples`, so use *Try it with sample data* in the app, or copy
`data/samples/mule_transfers` into `data/inbox/mule_transfers` by hand.

## Editions

Every showcase has a **Basic tier**: Collectors, Pipelines, Datasets, the link Dataset Job, saved views and saved Geo
Views (`la-sample` is smaller: one Pipeline, one Dataset and one saved view, no Jobs). It loads and can be explored on every edition, Basic included. Nobody signs in, so masking is off and you read
the real ids on the canvas. Saved Geo Views need the Geo map (Standard and above).

An **Advanced tier** adds Demo Users (sign-in personas), typed masking with the admin's Reveal, Investigations,
four-eyes approval, Alert Rules and a seeded Case. It ships in the same Space download and stays inert on Basic (Alert
Rules ship unarmed). Four showcases have one today; for the other thirteen it is planned (wave D of the plan).

## Sizes

Sizes are the zipped downloads, packed with `node tools/package-la-showcase.mjs <id>` from master on 2026-11-08. Every
Space download is under 40 KB; the column below is the data download. The product download is the same for every
showcase and is not counted.

## The catalogue

A template that covers two industries is listed under both, with the same template id: `la-showcase` appears under
Telecom and under Banking and finance.

### Telecom

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-mobile-money` | Anti-money-laundering on mobile-money wallets: structuring, pass-through and cash-out | Agent-assisted structuring through agent `AG-18`; a pass-through mule chain to `AG-39`; a nine-wallet device farm; a four-wallet directed cycle; a dormant wallet waking up; look-alike: the benign biller hub `MER-ELECTRIC` | Basic; **Advanced tier:** five Demo Users, typed masking, Investigations, four-eyes, Drafts, the Dossier, a bound Alert Rule and a seeded Case | 0.1 MB | [user guide](../la-mobile-money/user-guide.md) (a working day) · [runbook with expected values](../la-mobile-money/admin-guide-showcase-runbook.md) |
| `la-mfs` | Mobile financial services fraud: who controls a wallet, an agent or a merchant | SIM-swap wallet takeover through one call-centre login; agent split-deposit commission fraud; merchant cash-out collusion | Basic; Advanced tier: planned | 2.9 MB | [user guide, 3 exercises](../la-mfs/user-guide.md#exercises) · [install](../la-mfs/admin-guide-install.md) |
| `la-showcase` | The full studio tour, telecom side: telecom fraud rings and a supernode | Telecom rings for Wangiri, IRSF, SIM box, subscription fraud and mules; a 650-caller supernode (its banking stories are under Banking and finance) | Basic; **Advanced tier:** five Demo Users, typed masking, Investigations, four-eyes, the Suspicion score's factors, three telecom Alert Rules (unarmed on Basic) and a seeded Case | 0.8 MB | [user guide](../la-showcase/user-guide.md) (walk-through) · [variants runbook](admin-guide-variants-runbook.md) |
| `la-telco-ra` | Telecom revenue assurance: usage, dealers and enterprise trunks | Unbilled usage from one dealer's SIMs; dealer commission fraud with SIMs that go dormant; PBX hacking at night | Basic; Advanced tier: planned | 2.0 MB | [user guide, 3 exercises](../la-telco-ra/user-guide.md#exercises) · [install](../la-telco-ra/admin-guide-install.md) |

### Banking and finance

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-sample` | Your first graph: one Dataset, one saved view, one ring | 12 smurf accounts structure cash into `MULE-HUB-01` → `RELAY-01`/`RELAY-02` → `OFFSHORE-77`; `SHELL-A` to `SHELL-D` circulate money in a loop | Basic only (no Advanced tier) | 0.01 MB (or none: *Try it with sample data*) | [user guide](../la-sample/user-guide.md) |
| `la-showcase` | The full studio tour, banking side: rings, loops, cash-out and the geo route | The mule ring and the shell loop; `TILL-06` takes 80 % of the cash-out; `SKIMMER-01` skims benefit payments; the geo route of the ring | as under Telecom | 0.8 MB | [user guide](../la-showcase/user-guide.md) (walk-through) |
| `la-ownership` | Beneficial ownership and sanctions: following stakes and officer links | A hidden owner behind a five-layer chain of 24 % stakes; a sanctioned person two hops above a customer; one nominee director of 120 companies; circular ownership | Basic; **Advanced tier:** five Demo Users, Investigations, four-eyes, Drafts, an Alert Rule on an ownership path reaching a listed person within 3 hops (unarmed on Basic), and a seed script for the Investigation, an Entity List of the parties on the path and a Case | 1.0 MB | [user guide, 4 exercises](../la-ownership/user-guide.md#exercises) · [install](../la-ownership/admin-guide-install.md) |
| `la-card` | Card and e-commerce fraud: devices, accounts, merchants and where cards were compromised | Card testing from one device and network; an account-takeover ring shipping to 5 drop addresses; a colluding merchant refunding cards it never sold to; a common point of purchase behind 60 compromised cards | Basic; **Advanced tier:** five Demo Users, Investigations, four-eyes, Drafts, an Alert Rule on cards still in use at a compromised terminal (unarmed on Basic), and a seed script for the Investigation, an Entity List of cards to reissue and a Case | 1.8 MB | [user guide, 4 exercises](../la-card/user-guide.md#exercises) · [install](../la-card/admin-guide-install.md) |
| `la-trade` | Trade-based money laundering: companies, directors, shipments and vessels | Over-invoicing between two companies that share a director; containers shipped round a circle of three companies; letters of credit paid on a bill of lading naming a vessel that was elsewhere; new importers at one formation-agent address | Basic; **Advanced tier:** five Demo Users, Investigations, four-eyes, Drafts, an Alert Rule on a unit price over 3 times the reference between companies that share a director (unarmed on Basic), and a seed script for the Investigation, an Entity List of the related parties and a Case | 1.9 MB | [user guide, 4 exercises](../la-trade/user-guide.md#exercises) · [install](../la-trade/admin-guide-install.md) |
| `la-crypto` | Crypto laundering: following coins through hops by timing and amount | A peel chain from a hacked wallet; a mixer hop matched by timing and amount; 40 exchange accounts cashing out one cluster's funds | Basic; Advanced tier: planned | 2.7 MB | [user guide, 3 exercises](../la-crypto/user-guide.md#exercises) · [install](../la-crypto/admin-guide-install.md) |

### Insurance

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-claims` | Claims fraud for a Special Investigations Unit: shared identities, providers and money | 12 claimants sharing 4 phones, 3 addresses, one garage, clinic and lawyer; a passenger on 6 claims with 6 drivers; 11 night-time accidents near one junction; a ring clinic billing 3× its peers; 7 claims within 20 days of inception through one broker; a duplicate claimant identity; a parts-supplier ring (parts never fitted); a ghost broker (policies paid from 2 bank accounts) | Basic; **Advanced tier:** Demo Users, typed masking, Investigations, four-eyes, Drafts, the Dossier and a seeded Case | 0.07 MB | [user guide, 2 exercises](../la-claims/user-guide.md#exercises) · [runbook with expected values](../la-claims/admin-guide-showcase-runbook.md) |

### Utilities

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-utility` | Electricity revenue protection (non-technical loss): contractors, premises, accounts and vending points | Meter tampering linked to one contractor's visits, seen as loss on two transformers; illegal reconnections; debt-dodging account churn; commercial load on a residential tariff; prepaid token fraud from one vending point | Basic; **Advanced tier:** five Demo Users, Investigations, four-eyes, Drafts, an Alert Rule on transformer loss over 15 % (unarmed on Basic), and a seed script for the Investigation, two Entity Lists and a Case | 2.1 MB | [user guide, 5 exercises](../la-utility/user-guide.md#exercises) · [install](../la-utility/admin-guide-install.md) |

### Law enforcement

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-police` | Criminal investigation: incidents, people, vehicles, handsets and addresses | A burglary series traced through second-hand-dealer sales and one vehicle; a drug-supply handset network with a rotating controller number; drug bases at vulnerable residents' addresses; a witness who was arrested with the suspect | Basic; **Advanced tier:** five Demo Users, Investigations, four-eyes, Drafts, an Alert Rule on stolen serials resurfacing through one seller (unarmed on Basic), and a seed script for the Investigation, an Entity List of the sellers and a Case | 1.2 MB | [user guide, 4 exercises](../la-police/user-guide.md#exercises) · [install](../la-police/admin-guide-install.md) |

### Public sector

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-benefits` | Benefits and tax fraud: claimants, addresses, payroll and preparers | An address farm of 14 claimants; payments to the deceased; jobseekers on an employer's payroll; a tax preparer's refund ring | Basic; **Advanced tier:** five Demo Users, Investigations, four-eyes, Drafts, an Alert Rule on benefits still paid to the dead 30 days after the death notice (unarmed on Basic), and a seed script for the Investigation, an Entity List of the deceased still paid and a Case | 0.9 MB | [user guide, 4 exercises](../la-benefits/user-guide.md#exercises) · [install](../la-benefits/admin-guide-install.md) |
| `la-procurement` | Bid rigging and procurement fraud: tenders, bidders, officials and directors | Bid rotation among four firms; cover bids from one IP address; an official favouring a relative's firm; a debarred firm reborn under its old directors | Basic; **Advanced tier:** five Demo Users, Investigations, four-eyes, Drafts, an Alert Rule on a bid sent from an IP address another bidder already used on the same tender (unarmed on Basic), and a seed script for the cover-bids Investigation, an Entity List of the cover bidders and a Case | 0.25 MB | [user guide, 4 exercises](../la-procurement/user-guide.md#exercises) · [install](../la-procurement/admin-guide-install.md) |

### Healthcare

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-health` | Provider billing fraud for a payer: referrals, patients and clinics | A referral kickback ring to an imaging centre owned by a doctor's spouse; phantom billing for patients who could not have been there; a pill mill; upcoding clinics | Basic; Advanced tier: planned | 2.5 MB | [user guide, 4 exercises](../la-health/user-guide.md#exercises) · [install](../la-health/admin-guide-install.md) |

### Logistics

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-logistics` | Cargo theft: trips, drivers, stops, scanners and carriers | An inside job (losses recurring with one driver pair and one night-shift scanner); an unscheduled-stop hotspot; a fictitious carrier taking high-value loads | Basic; Advanced tier: planned | 1.3 MB | [user guide, 3 exercises](../la-logistics/user-guide.md#exercises) · [install](../la-logistics/admin-guide-install.md) |

### Enterprise

| Template | What it teaches | Stories | Edition | Data | Guide |
|---|---|---|---|---|---|
| `la-audit` | Internal audit of accounts payable and the journal: vendors, approvers and intercompany money | Vendor `V-117` shares a bank account and address with employee `E-31`; 5 vendors on one bank account; 30 invoices just under the 10 000 limit; one approver bridging the approval graph; intercompany money at quarter ends; 13 duplicate-payment pairs | Basic; **Advanced tier:** Demo Users, typed masking of parties, Investigations, four-eyes, Drafts, the Dossier, the committee portal, a bound Alert Rule and a seeded Case | 0.06 MB | [user guide](../la-audit/user-guide.md) (a working day) · [runbook with expected values](../la-audit/admin-guide-showcase-runbook.md) |
| `la-insider` | Insider threat for HR and security: access, mail and approvals | A leaver's bulk reads and mail to a personal domain; access creep granted by one manager; two employees approving each other's expenses | Basic; Advanced tier: planned | 1.3 MB | [user guide, 3 exercises](../la-insider/user-guide.md#exercises) · [install](../la-insider/admin-guide-install.md) |

The four older showcases (`la-showcase`, `la-mobile-money`, `la-claims`, `la-audit`) install the same way; their
runbooks cover the Advanced-tier demo (seed script and smoke) on a build where people sign in.

## Industry Spaces (in progress)

A peer plan, [Link Analysis industry Spaces](../../../../superpower/2026-11-08-la-industry-spaces-plan.md) (approved
2026-11-08), composes **one Space per industry** (*Telecom*, *Banking and finance*, *Insurance*, *Utilities*, *Law
enforcement*, *Public sector*, *Healthcare*, *Logistics*, *Enterprise*) from the use-case templates above, with every
Dataset mapped and saved views named after their use case. It is in progress: a recipe for each of the nine is in
`spaces/_industries/<id>.toon`, and `node tools/package-la-showcase.mjs <industry id>` composes it into a Space
download and a data download (for example `la-utilities`, `la-law-enforcement`, `la-telecom`). Every *install* guide
linked above, and the `la-claims` runbook, has a section on installing that use case's industry Space. The industry Spaces are not in the
in-app template gallery yet. The use-case templates on this page stay as they are, so everything here still holds as
the industry Spaces land.

## References

[la-showcase hub](index.md) · [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout) ·
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md)
