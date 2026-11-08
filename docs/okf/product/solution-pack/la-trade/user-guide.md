---
type: Feature
title: "Link Analysis trade showcase — user guide"
description: "A five-minute tour of the la-trade Space and one exercise per planted story (over-invoicing, phantom shipment, circular trade, shell importers), each with a hint and an answer, and the look-alikes that must not join them."
tags: [la-trade, user-guide]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis trade showcase — user guide

A trade-finance compliance team at a bank checks the letters of credit (LC) and documentary collections it finances
for trade-based money laundering. This Space holds a synthetic company register with its directors, a year of trades
and their bills of lading (B/L), where each of 300 vessels was every day, a monthly reference price per HS code and the
payments between the companies' accounts. Four stories are planted in it; each has a benign look-alike that must not
be mistaken for it. Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Install: [admin guide](admin-guide-install.md).

Everything is synthetic. No company, person, vessel, port, street or container is real. Countries are real (the
two-letter code after each port name), and HS codes are real codes of the public Harmonized System with invented prices.

## What is in the Space

| Node looks like | Is a | Comes from |
|---|---|---|
| `CO-01461` | company (name, country, registered address, incorporation date, bank account) | `company` |
| `PER-00291` | director (a person; a few people are registered twice under a spelling variant) | `director` |
| `MV TAMARIND SPIRIT` | vessel | `bill_of_lading`, `vessel_position` |
| `KLNU7238600` | container: four letters and seven digits | `bill_of_lading` |
| `SUITE 9, 41 LARKSPUR ROW, LONDON` | registered address, upper-cased | `company` |

One link Dataset, `trade_links_dataset`, joins them with six kinds of link, one link per pair:

| Kind | From → to | Carries |
|---|---|---|
| `traded_with` | exporter (beneficiary) → importer (applicant) | `amount`: trade value summed; `via`: trades, HS codes, the highest unit price as a multiple of the reference (`max_x_reference`), the trades over 3 times it and any trade whose B/L failed the vessel check |
| `on_vessel` | shipper → vessel on its B/Ls | `amount`: B/Ls; `via`: any B/L whose vessel was elsewhere, with the vessel's real position that day |
| `shipped_in` | shipper → container | `amount`: B/Ls; `via`: consignees, and the box's relays and shippers |
| `directed_by` | company → director | `via`: the director's name |
| `registered_at` | company → address | `via`: how many companies share the address, and the incorporation date |
| `paid` | payer company → payee company | `amount`: payments summed; `via`: how many, how many settle a trade, the last date |

The `flag` column marks a red flag, blank otherwise: `price_over_reference` (a unit price over 3 times the month's
reference median), `vessel_elsewhere` (a B/L whose vessel, on the B/L date, was not in the B/L's port of loading),
`transhipment` (a trade carried on two vessels), `relayed` (a box whose consignee ships it on), `many_shippers` (a box
loaded by 6 or more shippers) and `shared_address` (5 or more companies at one address).

**The vessel check.** For every B/L the link Job looks up the B/L's vessel on the B/L's date in `vessel_position`. If
the vessel was in another port or at sea, the B/L is flagged `vessel_elsewhere`. The check is on each B/L and its own
vessel, never on the trade: a transhipped trade names its second (ocean) vessel, which was not in the port of loading
on the ship date, but each leg's B/L matches its own vessel.

## Five-minute tour

1. The **Inspecto LA** home page lists the four saved views, one per story: `trade_over_invoicing`,
   `trade_phantom_shipment`, `trade_circular_trade` and `trade_shell_importers`. Each reads its own small Dataset
   (between 23 and about 200 links), so it draws in full, and each shows its story beside a look-alike. The
   description of each view says what to look for.
2. Open `trade_over_invoicing`. Two shapes appear: a star of one German supplier and its eight buyers, and a small
   group of two companies joined through one person they share as director. Open a `traded_with` link and read `max_x_reference` in `via`.
3. Open `trade_phantom_shipment`. Most of it is transhipments: a shipper linked to two vessels. One shipper is linked
   to a single vessel by a link whose `flag` is `vessel_elsewhere`; its `via` lists five B/Ls and where the vessel
   really was on each date.
4. Open `trade_circular_trade`. Six boxes linked to the same three shippers stand apart from a looser web of six boxes,
   each loaded by ten different shippers.
5. To follow a node out of a view into the whole Dataset, use **File ▸ New**, **Start a graph**: Dataset
   `trade_links_dataset`, start from **One entity**, Entity Type **Trade entity**, type an id such as `CO-00597` and
   choose **Draw**, then **Expand** (**G**).

The exercises below take each story in turn. Try them blind first; the answers follow each hint.

## Exercises

### 1. Over-invoicing

*Which two companies trade one product at several times its market price, and what ties them together?*

- **Hint:** saved view `trade_over_invoicing`. Compare the two shapes: who the buyers are, how many there are, how
  far above the reference the prices go, and whether a director links seller and buyer.
- **Answer:** `CO-02275` Kingfisher Marine Ltd (Hong Kong) shipped **14** consignments of integrated circuits
  (HS `854231`) to `CO-01461` Bluefin Trading Ltd (UK) between October 2025 and August 2026, at **3.6 to 7.8 times**
  the month's reference price, about 22.7 million in all, every one paid by letter of credit. Both companies are
  directed by **`PER-00291` Martin Castell**. Value moves from the UK importer to the Hong Kong exporter under cover of
  real shipments.
- **Not the answer:** `CO-02169` Kestrelhoff Medizintechnik GmbH (Germany, incorporated 1987) sells medical instruments
  (HS `901890`) at a steady **3.3 to 3.9 times** the reference all year to eight unrelated buyers in the UK, the
  Netherlands and the US (28 trades), each buyer with its own directors. Its prices are high but consistent: a
  specialty supplier, not a laundering pair. An Alert Rule on "over 3 times the reference" would raise both; the
  shared director and the jumpy prices tell them apart.

### 2. Phantom shipment

*Which letters of credit were paid on bills of lading for goods that cannot have sailed?*

- **Hint:** saved view `trade_phantom_shipment`. Look for a link with `flag` `vessel_elsewhere` and read its `via`.
  Then check the vessel in `vessel_position_dataset` on those dates.
- **Answer:** `CO-01200` Dunmore Resources A.S. (Türkiye, incorporated January 2024) presented **5** B/Ls for copper
  cathodes (HS `740311`) loading at *Marbleport (TR)* on **`MV TAMARIND SPIRIT`**: `BL-0004138` (12 Feb 2026),
  `BL-0005359` (20 Mar), `BL-0006703` (28 Apr), `BL-0007964` (4 Jun) and `BL-0009386` (15 Jul). On those dates the vessel
  was at *Red Sands (EG)*, *Lantern Bay (VN)*, *at sea in the North Sea*, *Red Sands* and *Red Sands*; its service never
  calls at Marbleport. The five LCs (`TR-004131`, `TR-005341`, `TR-006670`, `TR-007926`, `TR-009334`) were paid by the
  importers `CO-01568` Yellowpine Components Ltd and `CO-02046` Highmoor Trading Ltd, **3 165 075.08** in all. Each B/L
  names a container (`ZEDU…`) that appears on no other document.
- **Not the answer:** five transhipments, among them `TR-004859`, Eastwind Textiles Ltd (`CO-01555`, Hong Kong) to
  `CO-01837`: the trade names `MV TAMARIND CREST`, which was not at Harbour Crest (HK) on the ship date. But the goods
  went on two B/Ls: `MV HALCYON GLORY` from Harbour Crest to Saltmere (SG) on 4 March, then `MV TAMARIND CREST` from
  Saltmere on 10 April, and each vessel was in its B/L's port of loading on its B/L's date. The others are `TR-005681`,
  `TR-004674`, `TR-001572` and `TR-011122` (exporters `CO-02497`, `CO-00264`, `CO-01639`, `CO-01311`). The view also
  holds 24 ordinary background transhipments (27 once the October files are staged).

### 3. Circular trade

*Which containers keep coming back to the company that first shipped them, and what happens to the value?*

- **Hint:** saved view `trade_circular_trade`. Look for boxes flagged `relayed` and read `consignees` and
  `box_relays` in `via`; then compare the `traded_with` links between the same companies.
- **Answer:** the six boxes `KLNU7238600`, `KLNU0722138`, `KLNU9605085`, `KLNU6490823`, `KLNU5682691` and
  `KLNU7946736` travel together **twice round** `CO-00941` Moonridge Holdings FZE (UAE) → `CO-00388` Moonridge
  Electronics Pvt Ltd (India) → `CO-00213` Highmoor Textiles Sdn Bhd (Malaysia) → back to `CO-00941`, between 18 March
  and 27 June 2026: six trades of laptops (HS `847130`, `TR-005290`, `TR-005652`, `TR-006000`, `TR-006757`,
  `TR-008054`, `TR-008712`), the same 1 100 units each time, invoiced at **450 681, 502 678, 582 373, 641 729,
  706 299 and 788 128**. Each consignee ships the boxes on (5 relays per box). The goods go nowhere; the invoices grow.
- **Not the answer:** the leasing pool's boxes `PWCU0827459`, `PWCU9046170`, `PWCU3999100`, `PWCU2508727`,
  `PWCU0444452` and `PWCU6477031` (flag `many_shippers`) are each loaded by **10** different shippers, and no box ever goes back
  to a company that received it. A few of those shippers also trade with each other, as busy companies do, but none of
  them ships a box on after receiving it. Boxes are reused; that is what leasing is.

### 4. Shell importers

*Which importers were set up together and pay an exporter far more than it ever shipped them?*

- **Hint:** saved view `trade_shell_importers`. Two addresses host many companies. Compare the incorporation dates in
  `via` on `registered_at`, the directors, and the `paid` amounts with the `traded_with` amounts.
- **Answer:** six importers registered at **Suite 9, 41 Larkspur Row, London** and incorporated between **2 March and
  24 April 2026**: `CO-01501`, `CO-02455`, `CO-00780`, `CO-01014`, `CO-01087` and `CO-02491`. Two directors run them,
  `PER-00104` Oscar Delacroix (four) and `PER-00068` Vera Lindqvist (two). From May they all pay `CO-00597` Driftmark
  Industries Co., Ltd (China): **4 176 723.44** in the landed months, against goods worth only **114 699.10** shipped to
  them, about **36 times** more money than trade.
- **Not the answer:** fourteen companies at **Floor 3, Kingsbury Business Centre, 200 Aldwick Road, London**,
  incorporated between 1996 and 2014, each with its own directors and its own trading partners (for example
  `CO-00216`, `CO-00419`, `CO-00819`, `CO-00876`). A busy address is not suspicious by itself.

## For testers

The answers above are the checklist. The generator's ground truth, `tools/gen-la-trade-data.ground-truth.json`, lists
each story's entities and look-alikes; `tools/gen-la-trade-data.test.mjs` checks the facts in the files and
`TradeLinksJobGoldenTest` checks that, in the link Dataset the Job builds and in each saved view's Dataset, each story is
one connected group, no look-alike has a link to it, each look-alike is in its story's view, and each view stays under
the canvas limit of 1 000 nodes. Look-alikes are kept apart from their story's entities by direct links only: through
the busy vessels and background partners in the whole Dataset, they can sit a few hops away, which is ordinary trade.

## References

[Brochure](brochure.md) · [Install](admin-guide-install.md) · [Architecture](architecture.md) · [Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md)
