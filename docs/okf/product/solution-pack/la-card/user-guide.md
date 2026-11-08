---
type: Feature
title: "Link Analysis card fraud showcase — user guide"
description: "A five-minute tour of the la-card Space from its four saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-card, user-guide, showcase]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis card fraud showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic: names, streets, phones (Ofcom drama ranges),
e-mail domains (`example.com`) and IP ranges (documentation and shared-address ranges) are invented, and card ids
(`CRD-…`) are not card numbers.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `cardholder` | 5 030 | a cardholder: name, e-mail, phone, address (30 people appear twice, spelt differently) |
| `card` | 6 500 | an issued card: id, BIN group, holder, issue date |
| `merchant` | 900 | a merchant: name, category code (`mcc`), `pos` or `ecom`, city, acquirer |
| `card_auth` | about 70 000 (one file a month) | an authorisation: card, merchant, amount, time, device and IP (online only), `approved`, `declined` or `refund` |
| `chargeback` | about 670 | a disputed authorisation: reason, day filed |
| `online_account` | about 4 700 | a change to an online shopping account: card on file, device, ship-to address |

The `card_links` Job runs after every load and writes **one link Dataset**, `card_links_dataset`
(`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`), about 38 000 rows of eight kinds. An id says what a
node is: `CRD-` card, `M-` merchant, `DEV-` device, `OA-` online account, a `/24` network, or an upper-case
address.

| Link kind | From → to | `via` says |
|---|---|---|
| `used_at` | card → merchant | authorisations, declines, days |
| `refunded` | card → merchant | refunds, and the sales that card made there |
| `used_device` | card or online account → device | authorisations and declines, or account changes |
| `from_ip` | card → /24 network | distinct IPs, authorisations, declines |
| `account_card` | online account → card on file | account changes |
| `ships_to` | online account → address | the day it was set, the devices that set it |
| `charged_back` | card → merchant | reason@month of each chargeback |
| `visited_before_fraud` | defrauded card → merchant | days between the purchase and the fraud |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `card_testing`.** Two nodes carry hundreds of cards each. Hover one: a single
   device whose cards were each tried once or twice, almost all declined. The other big network carries as many
   cards, each from its own device and with hardly any declines.
2. **Open `account_takeover_ring`.** Most accounts are a pair (one device, one address). One cluster is not: a few
   devices fan out to many accounts, and those accounts meet again at a handful of addresses.
3. **Open `colluding_merchant_refunds`.** Two merchants stand out by chargebacks. Look at `via`: one has disputes in
   one month only; the other has them all year, and also refunds to cards marked `sales:0`.
4. **Open `common_point_of_purchase`.** Each defrauded card points at the shops it used in the six weeks before. One
   merchant collects most of them.
5. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (admin guide, step 6).
   The Pipelines land October 2026 and the Job rebuilds the links; reopen a view to see them.

## Exercises

Work blind first: each has a hint, then the answer. The answers are not in the data.

### 1. Card testing — which device and network tried the cards, where, and when?

*Hint:* in `card_testing`, find the device whose cards almost all have `declined` in `via`; then expand one of its
cards on `used_at`.

**Answer:** device `DEV-02616` and network `203.0.113.0/24` tried **300 cards** on the night of **2026-06-14**
(01:10 to about 04:40) for **1.00** each at three small online shops, `M-608`, `M-613` and `M-655` (category
5999). About 85 % of the 400 attempts were declined. **Not the story:** network `198.51.100.0/24` is a corporate
proxy used by about 180 genuine cards on weekdays in office hours, each from its own device, almost never
declined; it shares no card with the testers.

### 2. Account takeover — how many devices, how many accounts, which drop addresses?

*Hint:* in `account_takeover_ring`, seed an address shared by more than ten accounts and expand twice.

**Answer:** **8 devices** (`DEV-00618`, `DEV-01583`, `DEV-04568`, `DEV-06083`, `DEV-07080`, `DEV-07746`,
`DEV-08714`, `DEV-08781`) changed **140 online accounts** between 2026-01-12 and 2026-02-28, most at night, and
moved them to **5 drop addresses**: `UNIT 7 BROOK YARD LEEDS`, `2 ELDER COURT SHEFFIELD`,
`FLAT 12 9 DUNNOCK AVENUE LEICESTER`, `41 COBBLE ROW BRISTOL`, `FLAT 3 18 MERCER STREET MANCHESTER`. Within three
days each account's card bought online from the same device, through `192.0.2.0/24`; those purchases were later
charged back as `account_takeover`. **Not the story:** tablet `DEV-02601` is shared by the Garside family's
accounts at `27 LARCH GROVE LEEDS` all year; no account outside the family uses it.

### 3. Colluding merchant — which merchant pays out to cards it never sold to?

*Hint:* in `colluding_merchant_refunds`, look for `refunded` links whose `via` says `sales:0`.

**Answer:** **`M-221` (Voltbay Direct, electronics)** refunded **12 cards** that never bought there, 420 to 940
each, between April and August 2026, and its chargebacks (`merchandise_not_received`, spread over the year) run
about **9×** the rate of the other electronics merchants. **Not the story:** **`M-140` (Skylark Holidays,
travel)** has about 40 chargebacks too, but nearly all `service_not_provided` filed in **2026-07**, the strike
month, and no refunds; none of its customers is one of `M-221`'s disputed or refunded cards.

### 4. Common point of purchase — where were the cards compromised?

*Hint:* in `common_point_of_purchase`, which merchant has by far the most links? Check the dates in `via` and in
`used_at`.

**Answer:** **`M-317` (Kestrel Fuel Parkway, Sheffield)**: **60 cards** that paid there between **2026-03-02 and
2026-03-22** suffered card-not-present fraud 1 to 6 weeks later (chargebacks `fraud_card_not_present`). No other
merchant links more than a handful of defrauded cards. Ten more cards used the pump in those weeks and were not
defrauded, which is normal. **Not the story:** **`M-052` (Harvest Fair Superstore, Sheffield)** is used by about
940 cards, none of them later defrauded, so it does not appear in this view at all.

## Tester's checklist

The ground truth is `tools/gen-la-card-data.ground-truth.json` (repository only, not in the download); the
golden tests are `tools/gen-la-card-data.test.mjs` and `CardLinksJobGoldenTest`.

- [ ] All six Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `card_links_dataset` has eight kinds and one row per pair.
- [ ] Each story's members form one connected group: 300 cards with `DEV-02616`, `203.0.113.0/24` and the three
      shops; 140 accounts with the 8 devices and 5 drops; `M-221` with its 12 refunded and 23 charged-back cards;
      `M-317` with the 60 defrauded cards.
- [ ] No look-alike links to its story: `198.51.100.0/24`, `DEV-02601`, `27 LARCH GROVE LEEDS`, `M-140`, `M-052`.
