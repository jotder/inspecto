---
type: Feature
title: "Link Analysis procurement fraud showcase — user guide"
description: "A five-minute tour of the la-procurement Space from its four saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-procurement, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis procurement fraud showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic: company and person names, streets, phones
(Ofcom drama ranges), bank accounts (`SYN-…`) and IP addresses (shared-address and documentation ranges) are
invented; only city names are real.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `company` | 900 | a firm on the supplier register: name, registered address, phone, bank account, incorporated, debarred (blank for most) |
| `director` | 1 445 | a directorship: company, person, `from_date`, `to_date` (blank while serving) |
| `procurement_person` | 2 000 | a person: name and home address id (`AD-…`) |
| `official` | 300 | a buying official: person and buyer unit |
| `tender` | 1 228 (one file a month) | a tender: buyer unit, category, estimate, published, awarded, winner, award value |
| `bid` | about 6 000 (one file a month) | a bid: tender, bidder, amount, time, IP address, document author |
| `contract_change` | about 800 | an approved change that raises a contract's value: tender, value, official, day |

The `procurement_links` Job runs after every load and writes **one link Dataset**, `procurement_links_dataset`
(`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`, `market`), about 11 000 rows of seven kinds. An id says
what a node is: `CO-` company, `T-` tender, `P-` person, `AD-` home address, an IP address, or a document author
such as `y.fenwick`. `market` is the tender category of a tender link, or the category a company mostly bids in.

| Link kind | From → to | `via` says |
|---|---|---|
| `bid_on` | company → tender | `rank:2/4`, and how far above the award a losing bid was (`vs_award:+7.3%`) |
| `won` | company → tender | number of bids and the estimate |
| `directed_by` | company → person | `from:` and `to:` dates, and `company_debarred:` for a debarred firm |
| `same_ip` | company → IP address another bidder also used | bids, the fewest minutes to another bidder's bid from it on the same tender, tenders |
| `same_author` | company → document author another bidder's bids also carry | bids, tenders |
| `approved_change` | official → firm whose contract they changed | number of changes, tenders (`amount` = value added) |
| `lives_at` | person → home address | — |

A blank document author, or a placeholder such as `Administrator`, is no evidence: it never makes a `same_author`
link. Two derived Jobs feed the approvals and debarment views (`procurement_approval_links`,
`procurement_debarment_links`), so every saved view draws whole, under the canvas's 1 000 nodes.

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `bid_rotation`.** The whole Road maintenance market. One block of
   tenders is bid on by the same four firms and nobody else; hover the `bid_on` links and every loser is 5–12 %
   above the award. Elsewhere three firms win most tenders, but the field changes from tender to tender.
2. **Open `cover_bids_shared_ip`.** Only two IP addresses are shared by different bidders. One carries a firm
   and its three regular "competitors" (`same_tender_gap_min` in single figures), plus one document author they
   all share. The other carries 14 small firms, with no author in common.
3. **Open `official_approving_relative_firm`.** Officials and the firms whose contracts they changed. Only a handful
   of home addresses (`AD-…`) are on the canvas; one of them closes a triangle between an official and a firm, and
   that official-to-firm link's `via` reads `changes:9`.
4. **Open `debarred_firm_reborn`.** Every director of a debarred firm and where they went. Most stop at
   their old firm. Two people jump straight to a new firm that is already winning tenders.
5. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (admin guide). The
   Pipelines land October 2026 and the Jobs rebuild the links; reopen a view to see them.

## Exercises

Work blind first: each has a hint, then the answer. The answers are not in the data.

### 1. Bid rotation — which firms take turns, for which buyer, and how do they keep it neat?

*Hint:* in `bid_rotation`, find tenders with exactly four bidders who always appear together; then
read `won` in date order.

**Answer:** **`CO-0117` Aire Paving Ltd, `CO-0242` Thistle Roadworks LLP, `CO-0388` Wren Highways Ltd and
`CO-0561` Quill Highways Group** are the only bidders on the **24 Highways North** road tenders of the year (two a
month) and win them **in turn**, in that order, in order of publication. Each losing bid is **5–12 % above** the
winner, never the same figure twice, and every award is above the buyer's estimate. **`CO-0242` and `CO-0561`
share a director, `P-00001` (Jude Stanhope).** **Not the story:** **`CO-0021` Union Surfacing Limited, `CO-0055`
Orchard Highways LLP and `CO-0090` Quill Roadworks Group** win about three in four Highways South tenders, but they
skip some, other firms bid against them, the lowest bid always wins, and their losing margins run from under 1 %
to over 20 %. They never bid on a Highways North rotation tender and share no director with the four.

### 2. Cover bids — who sends their bids from the winner's office?

*Hint:* in `cover_bids_shared_ip`, look at the `via` of each IP address's links: which one has a gap of minutes
on the same tender?

**Answer:** IP address **`100.64.73.61`** is the office of **`CO-0074` Orchard Systems Group**, which wins **10 IT
tenders** whose only other bidders are **`CO-0153` Linden Data Limited, `CO-0309` Aire Digital Ltd and `CO-0702`
Harbour Systems LLP**, each 5–12 % higher. **16 of their 27 cover bids** (60 %) came from that address 2–25 minutes
after `CO-0074`'s own bid, and **20** carry `CO-0074`'s estimator **`y.fenwick`** as document author. **Not the
story:** **`198.51.100.20`** is the buyer's public help-desk kiosk. Fourteen small firms bid from it now and then,
hours or tenders apart, each with its own author; none of them touches the IT ring.

### 3. Conflict of interest — which official favours which firm, and why?

*Hint:* in `official_approving_relative_firm`, find a home address (`AD-…`) that an official shares with a director,
then read the `via` of the link between that official and the director's firm.

**Answer:** **`P-00002` (Eben Rowntree), a Parks official,** approved **9 contract changes** (each adding 8–25 %)
on six grounds-maintenance tenders won by **`CO-0426` Thistle Arboriculture Group**. A director of that firm,
**`P-00003` (Tobin Rountree)**, lives at the official's home address **`AD-00002`**: a relative, the surname
spelt differently. No other official approved more than 3 changes for one firm. **Not the story:** **`P-00008`
(Zara Yelland)**, a Facilities contracts manager, approved **33 changes** in the landed months (34 once the staged month is in), but for many different suppliers, never
more than 2 for any one, and she shares a home with no director.

### 4. Phoenix firm — where did the debarred firm go?

*Hint:* in `debarred_firm_reborn`, find a `directed_by` link whose `to:` date is days after a
`company_debarred:` date, and follow that person.

**Answer:** **`CO-0815` North Cleaning Ltd** was debarred on **2026-01-15**. Its directors **`P-00004` (Kira
Abbott) and `P-00005` (Flynn Blackwood)** resigned on 2026-01-20 and became directors of **`CO-0899` North
Facility Hygiene Ltd**, incorporated **2026-02-02** with the old firm's phone number and a near-identical
address. It won cleaning tender **`T-00519`**, awarded **2026-04-08**: 83 days after the debarment. **Not the
story:** **`P-00007` (Hana Garside)** directed **`CO-0640` Red Building Services Limited**, debarred in 2022, and
joined **`CO-0233` Amber Travel Group**, a transport firm founded in 2009, in November 2025: years later, another
trade, nothing carried over. One more person in the view, `P-00384`, left `CO-0050` Linden Recycling Ltd when it
was debarred in 2023 and has directed `CO-0543` Blue Surfacing LLP since 2014: a second seat held all along, not a
move after the debarment. The other debarred firms' directors never reappear.

## Tester's checklist

The ground truth is `tools/gen-la-procurement-data.ground-truth.json` (repository only, not in the download); the
golden tests are `tools/gen-la-procurement-data.test.mjs` and `ProcurementLinksJobGoldenTest`.

- [ ] All seven Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `procurement_links_dataset` has seven kinds and one row per pair; no `same_author` link goes to a blank or
      `Administrator` author; only `100.64.73.61` and `198.51.100.20` have `same_ip` links.
- [ ] Each story's members form one connected group: the four road firms, their 24 tenders and `P-00001`;
      `CO-0074`, its three covers, `100.64.73.61` and `y.fenwick`; `P-00002`, `CO-0426`, `P-00003` and `AD-00002`;
      `CO-0815`, `CO-0899`, `P-00004`, `P-00005` and `T-00519`.
- [ ] No look-alike links to its story: `CO-0021`, `CO-0055`, `CO-0090`; `198.51.100.20`; `P-00008`;
      `CO-0233`, `P-00007`.
- [ ] Each saved view holds its story whole and its look-alike beside it, and draws without "Result cut short".

## Advanced tier: the same stories with sign-in

On a build with sign-in (the demo build: see the [admin guide](admin-guide-install.md#advanced-tier-personas-the-shared-ip-alert-rule-and-the-seed)),
after `node tools/seed-la-procurement.mjs --apply`, sign in as each persona and check what each one sees:

1. **`procurement.analyst` (lead):** **Alerts** lists `procurement_same_tender_shared_ip` (WARNING) with the value
   16: bids sent from an IP address that another bidder had already used for its own bid on the same tender. Open the
   Investigation `cover-bids`: it is seeded with `100.64.73.61` and expanded once over `same_ip` to the four firms
   that bid from it (`CO-0074` and its three covers) of exercise 2. The Entity List `cover-bidders` holds
   `CO-0153`, `CO-0309` and `CO-0702`. The Case "Cover bids for CO-0074" is linked to it.
2. **Four-eyes:** an unbounded expand by `procurement.analyst` is held, not run. Sign in as
   **`procurement.manager`**: it is in the approval inbox; approve or refuse it. `procurement.analyst` cannot approve
   its own request.
3. **`procurement.reviewer`:** opens `cover-bids` and reads every step; any change is refused.
4. **`procurement.outsider`:** sees the Datasets, but `cover-bids` is not found (404).
5. **Draft:** `procurement.analyst` has one open Draft that annotates `CO-0074` about its estimator `y.fenwick`;
   promote it, or leave it.

**Not the story:** the help-desk kiosk `198.51.100.20` is shared by more firms than any other address, but no two of
them ever bid on the same tender from it, so it never counts; without the three cover bidders the rule would not
fire at all.
