---
type: Feature
title: "Link Analysis showcase Spaces — new-user guide"
description: "For someone opening Inspecto Link Analysis for the first time with a showcase Space installed: what the data is, how it is mapped, how to observe it, what you are trying to find in each of the 18 Spaces, and the steps to find it, with worked examples on la-sample and la-utility."
tags: [la-showcase, user-guide, showcase, onboarding]
timestamp: 2026-11-15T00:00:00Z
item: solution-pack.la-showcase
audience: internal
status: current
verified: 2026-11-19 last-edit
code: spaces/_templates/la-showcase
---

# Link Analysis showcase Spaces — new-user guide

This page is for your first hour with a Link Analysis showcase Space. It does not repeat the screens: those are in the
[Link Analysis user guide](../../module/la-core/user-guide-la-core.md). To pick a Space, use the
[showcase catalogue](user-guide-showcase-catalogue.md).

## What a showcase Space is

A showcase Space is a Space Template (`spaces/_templates/<id>`) with invented data. Somewhere in the data are a few
**planted stories**: fraud or abuse that someone put there on purpose, with known members. Next to most stories is a
**look-alike**: something innocent that looks similar at first sight. Each Space's user guide gives the answers.
No real people, firms or plates are in the data.

There are 18 showcase Spaces in 9 industries. `la-sample` is the smallest. The other 17 each have a Basic tier and
an Advanced tier (see [Editions](user-guide-showcase-catalogue.md#editions)).

Read any showcase in the same five steps:

1. **Data.** What feeds arrive, which Pipelines land them, which Datasets they make.
2. **Mapping.** What each id column holds, and how rows become links.
3. **Observation.** Open a saved view and read the canvas.
4. **Intentions.** Know what you are looking for: the story, and the look-alike that must stay apart.
5. **How-to.** Work the steps: open, expand, confirm, and on Advanced, sign in and follow the Alert.

The rest of this page takes them in that order.

## 1. Data

### Feeds, Pipelines and Datasets

- A **feed** is a set of files of one kind: claims, meter reads, transfers. The data download puts them in
  `data/inbox/<feed>/`.
- A **Pipeline** reads the files of one feed, checks them against a schema and writes them as Parquet. One Pipeline
  per feed. Its Collector watches the inbox folder.
- A **Dataset** is the table Link Analysis reads. Each Pipeline output is a Dataset.
- A **link Job** runs after each load. It reads the feed Datasets and writes a **link Dataset**: one row per link, with
  the two ends, the kind of link and the evidence. Most saved views read a link Dataset, not a raw feed.

Example from `la-utility`: eight feeds (`premise`, `customer_account`, `meter_read`, `transformer_read`,
`field_visit`, `service_order`, `prepaid_vend`, `payment`) land through eight Pipelines. The `utility_links` Job then
writes one link Dataset, `utility_links_dataset`. Two more Jobs write `transformer_loss` and `utility_bank_links`, and a
fourth, `utility_loss_alerts`, serves the Alert Rule.

`la-sample` is the exception: one Pipeline (`mule_transfers`), one Dataset (`mule_transfers_dataset`), no Jobs.

### Where to see them: the Data page

Open **Data** in the left menu. It lists every Dataset in the Space. Datasets a Pipeline or a Job keeps up to date
are grouped as **Streams** and **References**; each reads *From a Pipeline* in the list and *Kept up to date by a
Pipeline* in its header, so there is nothing to add by hand. One whose Pipeline has not written rows yet says *Waiting
for its first run*.

Open a Dataset to see:

- **Columns**: what each column holds and its role (an Entity Type, or *Nothing: a detail*);
- **Mappings**: how its rows are read as links;
- **Discover** ▸ *Show what is in it*: its link rows, the period its Time covers, about how many entities of each
  Entity Type, its kinds of link with their counts, and the ten busiest entities at each end.

Discover is on every edition, Basic included.

### What lands

When the product starts, the Pipelines land everything in `data/inbox`, and each load fires the Jobs. This takes a
minute or two. Each Space's install guide lists what to check: the Parquet folders, nothing in `quarantine` or
`errors`, the link Dataset, and the saved views in **File ▸ Open… ▸ Saved views**.

For `la-utility` the install guide expects 30 files and 0 rejected rows, and a link Dataset of about 4,300 rows in
six kinds.

### The staged month

Most of the new showcases also ship one more month of data in `data/staged/<feed>/` (`la-showcase` stages a next-day
file and a gap-day file instead; the four older showcases describe theirs in their runbooks). It is not loaded at start. Copy those files
into `data/inbox/<feed>/` to show new data arriving: the Pipelines land them, the Jobs rebuild the links, and a saved
view you reopen draws the new rows. The install guides describe it under *New data arrives* (for example October 2026
for `la-utility`, November 2026 for `la-police`).

To start again, stop the product, delete the Space's data folder and unzip the data download again. The Space
configuration is not touched.

## 2. Mapping

### Entity Types: what an id column holds

An **Entity Type** says what kind of thing an id is: an Account, a Premise, a Contractor. Two columns join up in the
graph only when they hold the same Entity Type. Most Spaces declare their Entity Types in
`config/link-analysis.toon`.

See them on the **Entities** page (left menu). The **Entity Types** tab has one card per type with its distinct count.
The **Columns** tab lists each id-like column per Dataset and what it holds.

Two patterns you will meet:

- **A narrow type per column.** In `la-sample`, `PAYER_ACCOUNT` and `PAYEE_ACCOUNT` both hold **Account**, so a payer
  in one row and a payee in another meet as one node.
- **One broad type on a link Dataset.** A link Job writes many kinds of thing into the same two columns. In
  `utility_links_dataset`, `src` and `dst` both hold **Utility entity**, and the id prefix tells you what the node is:
  `PRM-` premise, `TX-` transformer, `ACC-` account, `BA-` bank account, `C-` contractor, `V-` vending point. The
  same idea is **Party** in `la-ownership` and **Trade entity** in `la-trade`.

### Link Datasets and their Mapping

A **Mapping** says how a Dataset's rows are read as links: which column is **From**, which is **To**, and optionally
the **Kind of link**, **When** and **Measure**. A showcase Dataset carries its own Mapping. For
`utility_links_dataset` it is From `src`, To `dst`, kind `kind`, time `ts`, amount `amount`.

On the Data page, **Mappings** shows it. **New mapping** saves another reading of the same rows under its own name,
without copying data. A Mapping appears in the Start box like any Dataset.

### Why a column reads *Detail*

The Data page shows each column's role: **Entity**, **Time**, **Measure**, **Category** or **Detail**. When a column
states no role, the page works it out:

- it holds an Entity Type → **Entity**;
- it is a date or timestamp, the Mapping's time or the Dataset's event date → **Time**;
- it is the Mapping's amount → **Measure**;
- it is the Mapping's kind → **Category**;
- anything else → **Detail**: carried along, not filtered on.

So in `utility_links_dataset`, `kwh` and `via` read *Detail*: the Mapping does not name them. That is expected. A
Detail column is still in the data, and a view can carry it on the link: `meter_tampering_contractor` carries `via`.
Open a link and read `via`: it names the evidence (visits, orders, sales).

### Masking: Basic and Advanced

- **Basic:** nobody signs in, so masking is off. You read the real ids on the canvas (`MULE-HUB-01`, `C-14`).
- **Advanced (a build where people sign in):** a masked Entity Type shows as an alias such as `masked:1d4d76be…`. The
  same alias always stands for the same entity, so you can still count, search and follow it. Someone with Reveal
  rights can turn it back into the real id. The Graph model says **Masking on: ids shown as aliases**.

Typed masking is part of the Advanced tier of `la-showcase`, `la-mobile-money`, `la-claims` and `la-audit` (in
`la-claims` and `la-audit` the masked Entity Type is the party). The other fourteen Spaces, `la-sample` included,
mark every Entity Type `masked: false`, so their ids read in clear.

## 3. Observation

### Saved views are use cases

Each Space ships saved views named after a use case: `meter_tampering_contractor`, `card_testing`,
`burglary_series`. Most stories have their own view. A view usually keeps only the kinds of link its story needs, and
its description says what to look for.

Open one from **File ▸ Open… ▸ Saved views**, or from **My work ▸ Saved views** on the home page. A saved view
re-reads the data each time it opens. It is not evidence.

### The canvas

- Each node is an entity; each link is a row (or a group of rows) of the Dataset.
- The **legend** names each Entity Type and link kind. Its chips hide a kind from the drawing only.
- Click a node for its detail: id, links, neighbours, attributes, **Explain node** and a **Summary** of its links.
- The dock's **Rows** tab shows the links and nodes behind the picture.
- **Display options** (the brush) switch labels, colours, sizes and overlays such as **Community outlines**.

### The 1,000-node cap

The canvas admits at most **1,000 nodes**, and a server read returns at most 4,000 links by default. When a cap cuts
the data, the screen says **Result cut short**. A view that draws a whole Dataset through its filter
is cut to the busiest 1,000 nodes: a picture of the data, not yet of a story. To find the story, start small: one
entity, then expand.

Some showcase views draw in full: `la-trade`'s each read a small Dataset, and `la-utility`'s are checked to draw
without being cut short. Others draw a whole Dataset: `la-ownership`'s views are cut to the busiest 1,000 nodes, so
start small there.

### Thick links

A link can stand for many rows. In `la-sample`'s `mule_layering_ring`, links are folded per payer, payee, channel and
payee country, and a link's width is the number of transfers. A thick link is many transfers between the same two
accounts. In `utility_links_dataset` the Job already writes one row per pair; `amount` and `via` sum up the pair.

### Geo Views

Some Spaces ship saved Geo Views. They need the Geo map, so **Standard and above**. On Basic, read the same Dataset
in Discover instead.

| Space | Saved Geo Views |
|---|---|
| `la-showcase` | `mule_account_locations_map`, `mule_ring_route_map`, `mule_cashout_till_map` |
| `la-claims` | `accidents_map`, `providers_map`, `staged_accident_junction_map` |
| `la-police` | `incident_map` |
| `la-utility` | `utility_transformer_loss_map` |

Selecting an area on the map highlights the matching entities on the graph, and back.

### How a look-alike sits apart

The testers' checklists state the test: a story's members form one connected group, and a look-alike does not link
to them. That is the test: draw the story,
then find the look-alike. It will be busy, or odd in the same way, but it is in another group, or it has no link to
the story's key node.

Example from `la-utility`: the holiday home `PRM-0773` uses almost nothing every winter, like a tampered meter. But no
contractor ever visited it, and its transformer is not one of the two with the loss. It has no link to `C-14`.

## 4. Intentions

### What you are trying to find

Every showcase asks a small number of questions, one per planted story. For example: *which contractor's visits are
followed by meters reading less?* You win when you can name the members of the story and show why the look-alike is
not one of them.

- A **planted story** is a group of entities that behave in one suspicious way: a ring, a chain, a hub, a loop.
- A **look-alike** is innocent but shares one feature with the story: it is busy, or it shares an address, or it
  uses little energy. It exists so you learn not to judge on one feature. Reach alone is not guilt.

Work blind first. Each Space's user guide has exercises with the steps, then the answer, then the look-alike.

### The 18 Spaces

The view in the last column is a good first one: the first view the Space's user guide opens. The `la-mobile-money`
guide works from an Investigation instead; there the column gives the view of its first story. `la-showcase` covers
two industries and is listed under both.

**Telecom**

| Space | What you look for | Open first |
|---|---|---|
| `la-mobile-money` | Structuring through one agent, a pass-through mule chain, a device farm, a wallet cycle | `mm_structuring_band` |
| `la-mfs` | Who controls a wallet, an agent or a merchant: SIM-swap takeovers, split deposits, cash-out collusion | `mfs_sim_swap_bursts` |
| `la-showcase` | Telecom fraud rings (Wangiri, IRSF, SIM box, subscription fraud) and a supernode | `telecom_fraud_network` |
| `la-telco-ra` | Unbilled usage, dealer commission fraud, PBX hacking at night | `unbilled_usage` |

**Banking and finance**

| Space | What you look for | Open first |
|---|---|---|
| `la-sample` | A money-mule ring into `MULE-HUB-01`, and a loop of four shell accounts | `mule_layering_ring` |
| `la-showcase` | The mule ring, the shell loop, the cash-out till and the skimmer | `mule_structuring` |
| `la-ownership` | A hidden owner, a sanctioned person two hops up, a nominee director, circular ownership | `hidden_beneficial_owner` |
| `la-card` | Card testing, account takeover, a colluding merchant, a common point of purchase | `card_testing` |
| `la-trade` | Over-invoicing, phantom shipments, circular trade, shell importers | `over_invoicing` |
| `la-crypto` | A peel chain from a hacked wallet, a mixer hop, 40 accounts cashing out one cluster | `hack_peel_chain` |

**Insurance**

| Space | What you look for | Open first |
|---|---|---|
| `la-claims` | Claimants who share phones, addresses and providers; a parts-supplier ring; a ghost broker | `claimant_ring_shared_identity` |

**Utilities**

| Space | What you look for | Open first |
|---|---|---|
| `la-utility` | Meter tampering after one contractor's visits, illegal reconnections, account churn, prepaid token fraud | `meter_tampering_contractor` |

**Law enforcement**

| Space | What you look for | Open first |
|---|---|---|
| `la-police` | A burglary series, a drug-supply handset network, drug bases, a witness linked to the suspect | `burglary_series` |

**Public sector**

| Space | What you look for | Open first |
|---|---|---|
| `la-benefits` | An address farm, payments to the dead, jobseekers on a payroll, a tax preparer's refund ring | `address_farm` |
| `la-procurement` | Bid rotation, cover bids from one IP address, an official and a relative's firm, a debarred firm reborn | `bid_rotation` |

**Healthcare**

| Space | What you look for | Open first |
|---|---|---|
| `la-health` | Referral kickbacks, phantom billing, a pill mill, upcoding clinics | `referral_kickbacks` |

**Logistics**

| Space | What you look for | Open first |
|---|---|---|
| `la-logistics` | An inside job by one driver pair, a stop hotspot, a fictitious carrier | `driver_team_inside_job` |

**Enterprise**

| Space | What you look for | Open first |
|---|---|---|
| `la-audit` | A vendor that is an employee, shared bank accounts, split invoices, an approver bridging the approval graph | `ap_vendor_identity` |
| `la-insider` | A leaver taking data, access creep from one manager, mutual expense approvals | `leaver_exfiltration` |

## 5. How-to

### The steps, for any Space

1. **Install.** Unzip the three downloads side by side and start the product
   ([how to install a showcase](user-guide-showcase-catalogue.md#how-to-install-a-showcase)). `la-sample` needs no
   download: use *Try it with sample data*.
2. **Open the Space.** Pick it in the Space switcher.
3. **Check the data landed.** Open **Data**. Each Dataset should be there. Open the link Dataset and use **Discover**
   ▸ *Show what is in it*: it should have link rows, kinds and a period. The install guide's *Check that it loaded*
   gives the expected folders and counts.
4. **Open the view.** **File ▸ Open… ▸ Saved views**, then the first view from the table above. Read the view's
   description first.
5. **Expand.** Click the node the story turns on and **Expand** (**G**, or right-click ▸ *Expand…*). The preview line
   says what the step adds before it adds it. Very busy entities are held, not drawn.
6. **Or start small.** **File ▸ New** opens **Start a graph**. Pick the Dataset, **One entity**, the Entity Type, type
   the id and choose **Draw**. Then Expand. Use this when a view is too dense.
7. **Search around.** In **Pattern match**, switch the step list to **Conditions**: pick a start Entity Type, then per
   condition a direction, a link kind and a neighbour type (**Add condition** adds one), and choose **Find matches**.
   It searches the graph drawn on screen only; to reach entities not drawn yet, use Expand. The **Conditions** switch
   is not offered for a pattern with a time order between steps.
8. **Confirm against the look-alike.** Find the look-alike named in the Space's user guide. Check it is not linked to
   the story's members, or that the feature it shares does not hold up (a link carries 0, the pattern is seasonal, the
   price is the list price).
9. **On Advanced only:**
   1. Start the demo build, which signs people in by name with no password, over the same Space and data downloads
      (each install guide has the commands).
   2. Once the Pipelines have landed, run the seed from a checkout of the repository:
      `node tools/seed-la-<id>.mjs --base http://127.0.0.1:8096 --apply` (`tools/seed-la-mm.mjs` for
      `la-mobile-money`; the port is the one the install guide starts the demo build on). Without `--apply` it only
      prints the calls. It creates the Space's runtime state, such as the Investigation and the Case; the install
      guide lists what.
   3. Sign in as the Space's lead persona (each user guide has the table of personas).
   4. Open **Alerts**. Where the Space has an Alert Rule, its Alert is listed; the user guide gives the expected
      value. On Basic the same Alert Rule is loaded but not armed, so there is no Alert.
   5. Open the seeded Investigation. A large expand is held for a second person: sign in as the manager persona and
      approve or refuse it in the approval inbox. Nobody can approve their own request.

### Worked example: `la-sample`

1. On the Link Analysis **Getting started** page choose **Try the sample**. It creates the Space *Sample: invented
   data*, loads three days of invented transfers and opens Link Analysis with the ring drawn.
2. **Data.** Open **Data**. There is one Dataset, `mule_transfers_dataset`, kept up to date by the `mule_transfers`
   Pipeline.
3. **Mapping.** `PAYER_ACCOUNT` and `PAYEE_ACCOUNT` both hold **Account**. The Mapping is From `PAYER_ACCOUNT`, To
   `PAYEE_ACCOUNT`, time `BOOKED_DATE`. Masking is off: `la-sample` has no sign-in.
4. **Observation.** The view is `mule_layering_ring`. Links are folded per payer, payee, channel and payee country;
   width is the number of transfers.
5. **Intention.** Twelve `SMURF` accounts each place cash deposits just under 1,000 into `MULE-HUB-01`
   (structuring). The hub passes the money on within hours to `RELAY-01` and `RELAY-02`, which move it to
   `OFFSHORE-77` (pass-through). Separately, `SHELL-A` → `SHELL-B` → `SHELL-C` → `SHELL-D` → `SHELL-A` circulate
   money in a loop.
6. **How-to.**
   1. Click `MULE-HUB-01`: the twelve smurf accounts link in, and it links out to `RELAY-01` and `RELAY-02`.
   2. In the right panel's **On screen** tab, **Route from A to B** from one smurf account to `OFFSHORE-77` follows
      the chain through the hub and a relay.
   3. The view's description names what to run: **Pattern match** with the layering-chain, pass-through,
      inbound-collector and circular-flow patterns; **Suspicion score**, which ranks the hub first; and
      **Communities**, which separates the ring from the ordinary transfers.
   4. The ordinary transfers are the contrast: they stay outside the ring's group.

For the full exercise on the same data, with figures, see *Find a money-mule ring* in the
[Link Analysis user guide](../../module/la-core/user-guide-la-core-examples-roles.md#13-worked-examples) (it uses
`la-showcase`, which carries the same corpus).

### Worked example: `la-utility` (Utilities)

Electricity revenue protection: energy leaves a transformer but is not paid for.

1. **Install** the three `la-utility` downloads and start the product
   ([install guide](../la-utility/admin-guide-install.md)). It runs on Basic.
2. **Data.** Open **Data**. The eight feed Datasets are there, plus `utility_links_dataset`,
   `transformer_loss_dataset` and `utility_bank_links_dataset`. In Discover, `utility_links_dataset` has about 4,300 links in six kinds: `supplied_by`,
   `account_at`, `paid_from`, `visited_by`, `order_closed_by`, `vended_by`.
3. **Mapping.** `src` and `dst` hold **Utility entity**; read the id prefix (`PRM-`, `TX-`, `C-`, …). `kwh` and `via`
   read *Detail*.
4. **Observation.** Open **File ▸ Open… ▸ Saved views ▸ `meter_tampering_contractor`**. It keeps only `visited_by`
   and `order_closed_by` links: premises and the contractors who visited them or closed their orders. The busiest
   contractors (`C-27`, `C-21`) are not the story. Volume alone says nothing here.
5. **Intention.** Which contractor's visits are followed by meters reading less, and where does the energy go?
6. **How-to.**
   1. In the view, start from contractor `C-14` alone (**One entity**) and **Expand** once: its premises appear.
   2. Open a `visited_by` link: `ts` is the first visit day, and `via` lists the visits (`visit:<id>:<type>`).
   3. Answer, from the Space's ground truth (Basic has no screen that compares meter reads or shows transformer
      loss): `C-14` visited 32 premises; 22 of them register 40–70 % less within two weeks of its visit. All 22 sit
      under `TX-007` or `TX-019`, whose loss rises from 6.0 % to 17.8 % and from 4.6 % to 24.7 %.
   5. **Look-alike:** `PRM-0773`, the holiday home. It has no link to `C-14`.
   6. **New data:** copy `data/staged/<feed>/` into `data/inbox/<feed>/`. October 2026 lands and the Jobs rebuild
      the links and the loss. Reopen the view.
7. **On Standard and above**, open the Geo View `utility_transformer_loss_map`: the two transformers move over the
   15 % threshold.
8. **On Advanced**, start the demo build, run `node tools/seed-la-utility.mjs --base http://127.0.0.1:8096 --apply`,
   and sign in as `analyst`:
   - **Alerts** lists `utility_transformer_loss` (CRITICAL);
   - the Investigation `tampering-case` is seeded with `C-14`, `TX-007` and `TX-019` and expanded once;
   - the Entity List `tampered-premises` holds the 22 premises;
   - an unbounded expand by `analyst` is held; sign in as `manager` to approve it.

The other four `la-utility` stories (illegal reconnections, account churn, commercial load, prepaid tokens) are
exercises 2–5 in the [`la-utility` user guide](../la-utility/user-guide-la-utility.md#exercises).

## References

- [Link Analysis showcase catalogue](user-guide-showcase-catalogue.md): pick a Space, install, editions, sizes.
- [Link Analysis user guide](../../module/la-core/user-guide-la-core.md): every screen and control named here.
- Each Space's user guide:
  [`la-audit`](../la-audit/user-guide-la-audit.md) ·
  [`la-benefits`](../la-benefits/user-guide-la-benefits.md) ·
  [`la-card`](../la-card/user-guide-la-card.md) ·
  [`la-claims`](../la-claims/user-guide-la-claims.md) ·
  [`la-crypto`](../la-crypto/user-guide-la-crypto.md) ·
  [`la-health`](../la-health/user-guide-la-health.md) ·
  [`la-insider`](../la-insider/user-guide-la-insider.md) ·
  [`la-logistics`](../la-logistics/user-guide-la-logistics.md) ·
  [`la-mfs`](../la-mfs/user-guide-la-mfs.md) ·
  [`la-mobile-money`](../la-mobile-money/user-guide-la-mobile-money.md) ·
  [`la-ownership`](../la-ownership/user-guide-la-ownership.md) ·
  [`la-police`](../la-police/user-guide-la-police.md) ·
  [`la-procurement`](../la-procurement/user-guide-la-procurement.md) ·
  [`la-sample`](../la-sample/user-guide-la-sample.md) ·
  [`la-showcase`](user-guide-la-showcase.md) ·
  [`la-telco-ra`](../la-telco-ra/user-guide-la-telco-ra.md) ·
  [`la-trade`](../la-trade/user-guide-la-trade.md) ·
  [`la-utility`](../la-utility/user-guide-la-utility.md)
- Install guides:
  [`la-benefits`](../la-benefits/admin-guide-install.md) ·
  [`la-card`](../la-card/admin-guide-install.md) ·
  [`la-crypto`](../la-crypto/admin-guide-install.md) ·
  [`la-health`](../la-health/admin-guide-install.md) ·
  [`la-insider`](../la-insider/admin-guide-install.md) ·
  [`la-logistics`](../la-logistics/admin-guide-install.md) ·
  [`la-mfs`](../la-mfs/admin-guide-install.md) ·
  [`la-ownership`](../la-ownership/admin-guide-install.md) ·
  [`la-police`](../la-police/admin-guide-install.md) ·
  [`la-procurement`](../la-procurement/admin-guide-install.md) ·
  [`la-telco-ra`](../la-telco-ra/admin-guide-install.md) ·
  [`la-trade`](../la-trade/admin-guide-install.md) ·
  [`la-utility`](../la-utility/admin-guide-install.md)
- Runbooks for the four older showcases:
  [`la-showcase` variants](admin-guide-variants-runbook.md) ·
  [`la-mobile-money`](../la-mobile-money/admin-guide-showcase-runbook.md) ·
  [`la-claims`](../la-claims/admin-guide-showcase-runbook.md) ·
  [`la-audit`](../la-audit/admin-guide-showcase-runbook.md)
- [Showcase release guide](admin-guide-showcase-release.md): building and boot-testing a release.
