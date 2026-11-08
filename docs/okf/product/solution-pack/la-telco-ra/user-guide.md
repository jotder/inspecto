---
type: Feature
title: "Link Analysis telecom revenue assurance showcase — user guide"
description: "A five-minute tour of the la-telco-ra Space from its three saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-telco-ra, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis telecom revenue assurance showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic. Subscriber numbers (MSISDNs) are in the unassigned
`+999` range, IMSIs use the `001-01` test network, and dealer and firm names are invented. Cities are real only as city
names, and countries appear only as call destinations. Premium-rate ranges exist in every country: the three in the
PBX story were picked at random. SIM box, Wangiri and international revenue share fraud are in the
[`la-showcase`](../la-showcase/index.md) Space, not here.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `dealer` | 200 | a dealer that activates SIMs: name, city. `DL-000` is the operator's own network test team |
| `subscriber` | 7 363 | a SIM: `msisdn`, `imsi`, the dealer that activated it, `activated_at`, `plan`, the dealer device it was activated on (blank for about 3 %, mostly older activations) |
| `network_usage` | about 67 000 (one file a month) | a subscriber's month on the network, summed from switch records: `period`, `minutes`, `mb` |
| `billing_record` | about 65 000 (one file a month) | the same subscriber-month as the billing system charged it: `billed_minutes`, `billed_mb` |
| `topup` | about 30 000 (one file a month) | a prepaid top-up: amount, `topped_up_at`, `channel` (`retail`, `app`, `card`) and, for retail, the point of sale `pos_id` |
| `enterprise_trunk` | 150 | a business customer's voice trunk (its phone system's connection) and the customer it belongs to |
| `trunk_call` | about 14 000 (one file a month) | one trunk's international calls to one destination on one day: `destination_country`, `number_type` (`geographic`, `mobile`, `premium`), `minutes`, and the local hour the day's calls started (`start_hour`) |

Usage and billing are monthly totals per subscriber, not daily records, which keeps the download small. A month of
usage with no billing record is usage that was never charged.

The `telco_ra_links` Job runs after every load and writes **one link Dataset**, `telco_ra_links_dataset`
(`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`), with about 21 000 rows of five kinds. An id says what a
node is: `9996…` subscriber, `DL-` dealer, `DEV-` dealer device, `POS-` point of sale, `TRK-` trunk, `ENT-` business
customer, and `CTRY-<country>-GEO`, `-MOB` or `-PRN` a call destination (geographic, mobile or premium-rate numbers
in that country).

| Link kind | From → to | `via` says |
|---|---|---|
| `activated_by` | subscriber → dealer | `plan:`, `device:`, `topups:`, `first_topup_days:`, `usage_months:`, `unbilled_months:` (months of usage with no billing record), `usage_minutes:`, `billed_minutes:`, `usage_mb:`, `last_usage:` (`amount` = total topped up) |
| `activated_on` | subscriber → dealer device | the dealer and plan |
| `topped_up` | subscriber → point of sale | top-ups there, first and last (`amount` = their total). App and card top-ups have no point of sale |
| `trunk_of` | trunk → customer | `international_minutes:` |
| `called_country` | trunk → destination | `number_type:`, `days:`, `minutes:`, `off_hours_minutes:` (calls starting before 07:00 or from 21:00, or at a weekend), `night_minutes:`, `weekend_minutes:` |

The derived `telco_ra_screen_links` Job runs each time the link Dataset is rebuilt and writes
`telco_ra_screen_links_dataset`. It has the same columns plus `screen`, the revenue-assurance check that picked each
link. Each saved view reads one screen, so every view draws whole, under the canvas's 1 000 nodes:

| `screen` | Picks | Saved view |
|---|---|---|
| `unbilled_usage` | every subscriber whose usage reached no billing record in 2 or more months, its dealer and activation device | `telco_ra_unbilled_usage` |
| `commission_spike` | every SIM that earned its dealer a commission (activated and topped up at least once) in a week when that dealer earned 50 or more, with the dealer, the device and the points of sale where the SIM was topped up | `telco_ra_commission_spikes` |
| `pbx_off_hours` | every trunk's calls to a destination with 300 or more off-hours minutes, and the customer the trunk belongs to | `telco_ra_pbx_off_hours` |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `telco_ra_unbilled_usage`.** About a dozen small pieces: one subscriber, its
   dealer and device. Each is a SIM whose first two months reached billing late. Two large stars stand apart. One is
   the network test team, `DL-000`, with 40 SIMs on its two devices. The other is a high-street dealer with 300 SIMs
   on one device.
2. **Open `telco_ra_commission_spikes`.** Two dealers each had weeks with 50 or more commission-earning SIMs. Look at
   how each one's SIMs were topped up: one dealer's SIMs fan out to ten shops, the other's all converge on one.
3. **Open `telco_ra_pbx_off_hours`.** A small view of about 20 nodes. Two firms call North America in the evening, and
   one calls Singapore every working night. Four firms that have nothing to do with each other all call the same
   premium-rate ranges, `CTRY-…-PRN`, in three countries.
4. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (see the admin guide). The
   Pipelines load October 2026 and the Jobs rebuild the links. Reopen a view to see the new month.

## Exercises

Work blind first: each exercise has a hint, then the answer. The answers are not in the data.

### 1. Unbilled usage — whose SIMs are using the network for free?

*Hint:* in `telco_ra_unbilled_usage`, read `unbilled_months:` and `usage_months:` on the `activated_by` links, then
look at `plan:` and at when each dealer's SIMs were activated (`ts`).

**Answer:** dealer **`DL-047` (Lantern Wireless, Manchester)** activated **300 SIMs on one device, `DEV-00086`, on
one day, 2026-03-17**, between 10:00 and about 16:40. Every one of them is used heavily every month from March
(800–2 500 minutes and 20–60 GB a month, against a median of about 150 minutes and 3.3 GB). Not one month reached
billing (`unbilled_months:7`, `billed_minutes:0`), and none was ever topped up. **Not the story:** the network test
team **`DL-000`** has **40 SIMs on `DEV-00001` and `DEV-00002`**. They are used and never billed too, but their
`plan:` is `Internal test`, which is the known list of test SIMs. The dozen single SIMs at other dealers reached
billing for their first two months late (`unbilled_months:2`) and have been billed normally since.

### 2. Dealer commission fraud — which dealer is activating SIMs nobody uses?

*Hint:* in `telco_ra_commission_spikes`, compare the two dealers' SIMs: `topups:`, `usage_months:` and
`last_usage:` on `activated_by`, and where the `topped_up` links lead.

**Answer:** dealer **`DL-112` (Copperfield Talk, Leeds)** activated **400 SIMs on its three devices `DEV-00204`,
`DEV-00205` and `DEV-00206`** in the six weeks from 2026-02-02. Each SIM was topped up **once, with 5.00, at the
dealer's own shop `POS-0008`**, within two days of activation. That first recharge earns the dealer the activation
commission. Each SIM then made a test call of a few minutes and was never used again (`topups:1`,
`usage_months:1`). **Not the story:** **`DL-163` (Yardley Handsets, Sheffield)**, near a university, activated **350
SIMs in the first two weeks of term (2025-10-01 to 10-12)**. Its students keep topping up every few weeks, by app or
at ten shops near the campus, and use their SIMs every month (`usage_months:12`, `topups:` 6 or more).

### 3. PBX hacking — whose phone systems are calling premium numbers at night?

*Hint:* in `telco_ra_pbx_off_hours`, look at the destination nodes: which number type do they have, and what do
`night_minutes:` and `weekend_minutes:` say about when those calls happen?

**Answer:** four trunks of four unrelated firms, **`TRK-025` (`ENT-0087`), `TRK-040` (`ENT-0040`), `TRK-087`
(`ENT-0057`) and `TRK-147` (`ENT-0045`)**, call **premium-rate numbers in three countries: `CTRY-LT-PRN`,
`CTRY-SK-PRN` and `CTRY-SI-PRN`**. Every minute is off hours: from Friday 22:00 to Monday morning, over six weekends
between 2026-04-03 and 2026-06-22. That is 1 600–5 000 minutes per trunk and destination, from trunks that otherwise
make a few daytime calls abroad. Nobody else calls those ranges. **Not the story:** **`TRK-027` (`ENT-0108`)** calls
**`CTRY-SG-GEO`**, a geographic number at its Singapore office, almost every working day. The calls start at
01:00–03:00 here, which is the office's working day, and never at a weekend (`weekend_minutes:0`). `TRK-017`
(`ENT-0080`) and `TRK-082` (`ENT-0114`) call North American offices in the evening (`CTRY-US-GEO`).

## Tester's checklist

The ground truth is `tools/gen-la-telco-ra-data.ground-truth.json` (repository only, not in the download). The
golden tests are `tools/gen-la-telco-ra-data.test.mjs` and `TelcoRaLinksJobGoldenTest`.

- [ ] All seven Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `telco_ra_links_dataset` has five kinds and one row per pair. There is one `activated_by` per landed subscriber
      and one `trunk_of` per trunk. Only the 300 story SIMs, the 40 test SIMs and the 12 late-billed SIMs have
      `unbilled_months:` of 2 or more.
- [ ] Each story's members form one connected group. Unbilled usage: `DL-047`, `DEV-00086` and the 300 SIMs.
      Commission fraud: `DL-112`, `DEV-00204`–`DEV-00206`, `POS-0008` and the 400 SIMs. PBX hacking: the four trunks,
      their four customers and the three `-PRN` destinations.
- [ ] No look-alike links to its story: `DL-000`; `DL-163`; `TRK-027`.
- [ ] Each saved view holds its story whole, with its look-alike beside it, and draws without "Result cut short".
      The largest view, `telco_ra_commission_spikes`, is about 2 400 rows and 770 nodes. October adds no SIMs to it:
      both spikes are in the landed months.
