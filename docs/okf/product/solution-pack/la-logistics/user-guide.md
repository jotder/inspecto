---
type: Feature
title: "Link Analysis logistics cargo theft showcase — user guide"
description: "A five-minute tour of the la-logistics Space from its three saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-logistics, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis logistics cargo theft showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic. Carriers, drivers, warehouses, places,
shippers and consignees are invented. Cities are real only as city names. Phone numbers are in the UK's reserved
drama ranges (`07700 900…`, `0113 496 0…` and so on).

## What is in the Space

The data is what a shipper or cargo insurer holds about the loads it sends through a network of twelve
warehouses, one per city, carried by 120 haulage carriers.

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `carrier` | 120 | a haulage carrier on the approved register: name, `phone` as the carrier gave it, `registered_at`, `insured` (`Y` or `N`) |
| `driver` | 900 | a driver a carrier has named: name, mobile `phone` (blank for about 3 %), `carrier_id`, `left_on` for a driver who has left |
| `place` | 145 | the twelve warehouses (`WH-`) and 133 places on the road (`PL-`): `place_type` (`warehouse`, `services`, `truck_park`, `fuel_station`, `layby`), nearest city (`area`), `lat`, `lon` |
| `shipment` | about 12 900 (one file a month) | one load: shipper, consignee, `goods`, declared `value`, `booked_at` |
| `trip` | about 12 900 (one file a month) | the trip that carries one shipment: driver, `co_driver_id` for a two-driver team, vehicle, carrier, `route` (origin-destination city), `depart_at`, `arrive_at` (blank when the truck never got there) |
| `stop` | about 13 400 (one file a month) | stop-level GPS from the carriers' telematics: a place where a truck stood still, `start_at` and `minutes`. Not every ping. A carrier that sends no telematics has no rows |
| `warehouse_scan` | about 46 000 (one file a month) | a barcode scan of a shipment: `scan_type` (`inbound`, `putaway`, `outbound` when it is loaded onto the truck, `arrived` at the destination), `scanned_at`, the warehouse and the employee whose handheld scanned it |
| `loss_report` | 63 | a loss on a shipment: `reported_at` and `loss_type` (`theft`, `shortage` when it arrived with goods missing, `non_delivery`) |

A load worth **100 000 or more is high value**. About one trip in 200 ends in a loss report.

The `logistics_links` Job runs after every load and writes **one link Dataset**, `logistics_links_dataset`
(`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`), with about 45 000 rows of seven kinds. An id says what
a node is: `DRV-` driver, `TR-` trip (its shipment is `SH-` with the same number), `EMP-` warehouse employee, `PL-`
place on the road, `CAR-` carrier, `LR-` loss report. Every trip-side link carries the load in `via`: `route:`,
`goods:`, `value:`, `high_value:` (`Y` or `N`), `arrived:` (`Y` when the destination warehouse scanned it in) and
`lost:` (the loss type, or `N`).

| Link kind | From → to | `via` adds |
|---|---|---|
| `drove` | driver → trip | `co_driver:`, `driver_trips:` and `driver_losses:` (the driver's whole year, as driver or co-driver) |
| `co_drove` | co-driver → trip | `driver:`, and the co-driver's own `driver_trips:` and `driver_losses:` |
| `scanned_by` | trip → employee who scanned the load out onto the truck | `warehouse:`, `shift:` (`early` 06–14, `late` 14–22, `night` 22–06), `scanner_outbound:`, `scanner_night:`, `scanner_losses:` |
| `stopped_at` | trip → place where it stood **20 minutes or more** | `minutes:`, `place_type:`, `last_stop:` (the trip's last GPS stop), `place_long_stops:`, `place_high_value_long_stops:`, `place_lost_trips:`. Shorter stops are fuel and traffic; they stay in the `stop` Dataset |
| `carried_by` | trip → carrier | `carrier_age_days:` at departure, `insured:`, `gps_stops:` (how many stops the trip sent) |
| `lost` | trip → loss report | `loss_type:`, `reported_hours_after_departure:` |
| `same_phone` | carrier → driver, or driver → driver, whose numbers match once written as digits | `phone:`, both numbers as given, `dst_carrier:`, `dst_left_on:`, `dst_driver_losses:` |

Only the scan that loads the truck becomes a link. The other scans stay in `warehouse_scan` and show up as
`arrived:`.

The derived `logistics_screen_links` Job runs each time the link Dataset is rebuilt and writes
`logistics_screen_links_dataset`. It has the same columns plus `screen`, the check that picked each link. Each saved
view reads one screen, so every view draws whole, under the canvas's 1 000 nodes:

| `screen` | Picks | Saved view |
|---|---|---|
| `inside_job` | every driver, co-driver and outbound scanner on 2 or more lost trips, with those trips and their loss reports | `driver_team_inside_job` |
| `stop_hotspot` | every place where 3 or more lost trips stood 20 minutes or more, with every trip that stood there that long and the loss reports of the lost ones | `theft_stop_hotspot` |
| `new_carrier` | every high-value trip given to a carrier under 60 days old, its loss report if it was lost, the carrier's `same_phone` links, and the lost trips of a driver whose phone it shares | `fictitious_carrier` |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `driver_team_inside_job`.** About 80 nodes. One driver, `DRV-0894`, has two lost
   trips. Beside that driver is a tight cluster: two drivers on the same twelve lost trips, with one warehouse employee
   tied to ten of them. A few employees each sit between two unrelated losses.
2. **Open `theft_stop_hotspot`.** Two stars. One is busy official services with about 240 long stops, three of
   them lost. The other is a lay-by with about 80. Look at which of the lay-by's trucks carried a high-value
   load, and what happened to them.
3. **Open `fictitious_carrier`.** Small groups, one per new carrier that took a high-value load in its first
   60 days. Most delivered. One carrier's loads all end in a loss report. Its `same_phone` link leads to a driver
   who has a loss of their own.
4. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (see the admin guide). The
   Pipelines load October 2026 and the Jobs rebuild the links. Reopen a view to see the new month.

## Exercises

Work blind first: each exercise has a hint, then the answer. The answers are not in the data.

### 1. Inside job — who keeps losing loads?

*Hint:* in `driver_team_inside_job`, compare `driver_trips:` and `driver_losses:` on the `drove` and `co_drove`
links. Then read `warehouse:` and `shift:` on the `scanned_by` links, and `loss_type:` on `lost`.

**Answer:** the two-driver team **`DRV-0892` (Priya Jones) and `DRV-0893` (Stephen Uttley)** of **`CAR-052`
(Eastgate Carriers Ltd)** drove **12 loads that arrived short** (`loss_type:shortage`) between 2025-10-10 and
2026-08-20. That is 12 losses in 107 trips. **`EMP-0123`**, a night-shift scanner at **`WH-06` (Trentfield
Distribution Centre, Nottingham)**, scanned **10 of them** out onto the truck, between 00:50 and 03:30. The loads
were electronics, spirits, pharmaceuticals and tobacco, worth 46 700–91 300 each. Three people to interview.
**Not the story:** **`DRV-0894` (Daniel Hargreaves, `CAR-103`)** is the busiest driver on the busiest route,
Southampton–Birmingham, with **318 trips and 2 thefts** (`driver_losses:2`). Neither theft was scanned out by
`EMP-0123`, and the driver shares nothing with the team.

### 2. Stop hotspot — where do trucks get robbed?

*Hint:* in `theft_stop_hotspot`, compare the two places' `place_long_stops:`,
`place_high_value_long_stops:` and `place_lost_trips:`. Then look at `last_stop:` and `high_value:` on the trips
that were lost.

**Answer:** **`PL-118` (Lay-by at Rowley Gap, near Birmingham)** is an unofficial lay-by. About 80 trucks stood
there 20 minutes or more in the year, and **9 of them carried a high-value load. All 9 were stolen there**
(`place_high_value_long_stops:9`, `place_lost_trips:9`). Each stood 26–69 minutes. It was their last GPS stop, and
nothing arrived (`arrived:N`, `loss_type:theft`). They were nine different drivers, between 2025-10-24 and
2026-06-01, carrying electronics, pharmaceuticals and tobacco worth 144 900–207 300 on roads past the lay-by
(Birmingham–Leeds, Birmingham–Doncaster, London–Manchester, Doncaster–Bristol). **Not the story:**
**`PL-087` (Whitecross Services)** is official services on the London–Birmingham road. About **240 trucks** took a
long break there, 26 of them with high-value loads, and **3** high-value loads were stolen. That is a theft now
and then at a busy place, not a trap.

### 3. Fictitious carrier — which new carrier is not a carrier?

*Hint:* in `fictitious_carrier`, read `carrier_age_days:`, `insured:`, `gps_stops:` and `arrived:` on each
carrier's `carried_by` links. Then follow its `same_phone` link.

**Answer:** **`CAR-022` (Larkmoor Freight Services Ltd)** registered on **2026-05-11** and was uninsured
(`insured:N`). Between 2026-06-05 and 06-11, 24–30 days later, it took **5 high-value loads** (pharmaceuticals and
tobacco worth 145 200–257 700). **None arrived** (`loss_type:non_delivery`), and none sent a single GPS stop
(`gps_stops:0`). Its phone, written `+44 7700 900374`, is the mobile of **`DRV-0895` (Hamish Hughes)**, written
`07700 900374`. That driver worked for `CAR-020` until **2025-11-30**, after a load they drove was stolen on 2025-11-05
(`LR-0006`). **Not the story:** **`CAR-057` (Oakfield Distribution Ltd)** registered on 2026-03-02 and is insured.
In its first 60 days it took 9 high-value loads, and it delivered every one of its 58 loads. It shares a phone too,
but with its own driver **`DRV-0001`**: it is an owner-operator. Other new carriers in the view delivered their
loads.

## Tester's checklist

The ground truth is `tools/gen-la-logistics-data.ground-truth.json` (repository only, not in the download). The
golden tests are `tools/gen-la-logistics-data.test.mjs` and `LogisticsLinksJobGoldenTest`.

- [ ] All eight Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `logistics_links_dataset` has seven kinds and one row per pair. There is one `drove`, one `carried_by` and one
      `scanned_by` per landed trip. Only `DRV-0892`, `DRV-0893` and `DRV-0894` have `driver_losses:` of 2 or more.
- [ ] Each story's members form one connected group. Inside job: `DRV-0892`, `DRV-0893`, `EMP-0123`, the 12 trips and
      their loss reports. Stop hotspot: `PL-118`, the 9 trips and their loss reports. Fictitious carrier: `CAR-022`,
      `DRV-0895`, the 5 trips and their loss reports.
- [ ] No look-alike links to its story: `DRV-0894`; `PL-087`; `CAR-057`.
- [ ] Each saved view holds its story whole, with its look-alike beside it but not joined to it, and draws without
      "Result cut short". The largest view, `theft_stop_hotspot`, has about 330 rows and 335 nodes.
- [ ] After the staged October lands, the stories stay as they are: no new loss joins any of them.

## Advanced tier: the same stories with sign-in

On a build with sign-in (the demo build: see the [admin guide](admin-guide-install.md#advanced-tier-personas-the-new-carrier-alert-rule-and-the-seed)),
after `node tools/seed-la-logistics.mjs --apply`, sign in as each persona and check what each one sees:

1. **`logistics.analyst` (lead):** **Alerts** lists `logistics_high_value_trip_new_carrier` (WARNING) with the value
   5: high-value trips given to a carrier under 60 days old that is not insured. Open the Investigation
   `fictitious-carrier`: it is seeded with `CAR-022` and expanded once over `carried_by` and `same_phone` to its 5
   loads and to `DRV-0895` (exercise 3). The Entity List `suspect-carriers` holds `CAR-022` and `DRV-0895`. The Case
   "Fictitious carrier CAR-022" is linked to it.
2. **Four-eyes:** an unbounded expand by `logistics.analyst` is held, not run. Sign in as **`logistics.manager`**: it
   is in the approval inbox; approve or refuse it. `logistics.analyst` cannot approve its own request.
3. **`logistics.reviewer`:** opens `fictitious-carrier` and reads every step; any change is refused.
4. **`logistics.outsider`:** sees the Datasets, but `fictitious-carrier` is not found (404).
5. **Draft:** `logistics.analyst` has one open Draft that annotates `DRV-0895` about his old trips; promote it, or
   leave it.

**Not the story:** 16 high-value trips went to a carrier under 60 days old. The owner-operator `CAR-057` took 9 of
them, and two more went to other new carriers; all of them are insured, so the rule does not count them. Without
`CAR-022` the rule would not fire at all. The rule reads the carrier register when the load is given, not the loss
reports, so the background losses (about one trip in 400) cannot raise it. A rule on lost loads would have: one
high-value load of `CAR-091`, a new insured carrier, arrived short in the background.
