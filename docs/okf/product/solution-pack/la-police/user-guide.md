---
type: Feature
title: "Link Analysis police showcase — user guide"
description: "A five-minute tour of the la-police Space from its three saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-police, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis police showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic: names, streets, plates (`ZX …`), serials,
handset IMEIs and phone numbers (Ofcom drama ranges `07700 900xxx` and `0113`, `0114`, `0115`, `0116`, `0161`
`496 0xxx`) are invented; only the cities are real, and only at city level.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `incident_report` | 4 001 | an incident: offence type, the window it happened in (`start_at`, `end_at`), district, city, location, reporting person |
| `person` | 6 000 | a person: name, birth year, address, flags (`prior_record`, `vulnerable`); 25 people appear twice, spelt differently |
| `incident_person` | 8 529 | a person on an incident, with a role: `suspect`, `victim` or `witness` |
| `vehicle` | 2 500 | a vehicle: plate, make, colour, keeper |
| `plate_read` | 40 004 | a number-plate camera read: plate, camera, time, the camera's location on a main road |
| `call_record` | 30 168 | a call from a seized or lawfully obtained handset: caller, callee, start, duration |
| `handset` | 500 | a handset number: IMEI, the person it is attributed to (often blank) |
| `custody` | 1 088 | a person arrested; people arrested together share a `co_arrest_group` |
| `dealer_sale` | 1 498 | an item sold to a second-hand dealer: dealer, seller, item, serial (blank when not recorded) |
| `property_stolen` | 2 411 | an item reported stolen on an incident, with its serial when known |
| `address` | 3 000 | an address: street, district, city, location |

The `police_links` Job runs every five minutes and writes **one link Dataset**, `police_links_dataset`
(`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`), about 22 000 rows of nine kinds. `amount` is the number
of records behind a link. An id says what a node is: `INC-` incident, `P-` person, `ADR-` address, `ZX …` plate,
`+44…` phone number, `DLR-` dealer.

| Link kind | From → to | `via` says |
|---|---|---|
| `called` | caller number → callee number | calls and seconds |
| `co_arrested` | person — person (stored once) | the custody group |
| `involved_in` | person → incident | the role (`role:suspect`, `role:victim`, `role:witness`) |
| `keeper_of` | person → plate | colour and make |
| `seen_near` | plate → incident | the cameras: a read within 2 km and 30 minutes of the incident's window |
| `lives_at` | person → address | the address |
| `uses_number` | person → handset number | the IMEI |
| `sold_item` | seller → dealer | the sales |
| `item_match` | incident → seller | the serial stolen in the incident, the sale and the dealer that recorded it |

Each saved view reads a smaller link Dataset that a Job derives from `police_links_dataset` after every build, so
it fits on the canvas (under 1 000 nodes):

| Saved view | Link Dataset | What it keeps |
|---|---|---|
| `burglary_series` | `police_property_links_dataset` | every `item_match`, the matched sellers' `sold_item`, `lives_at` and `keeper_of` of the sellers and everyone at their addresses, `seen_near` of the plates they keep |
| `drug_supply_handset_network` | `police_call_links_dataset` | the calls of every number with 6 or more call partners, `uses_number` of those numbers, `lives_at` of their people |
| `suspicious_witness` | `police_witness_links_dataset` | people who witnessed 2 or more incidents, everyone on those incidents, and those witnesses' co-arrests |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `burglary_series`.** A few small groups, and one large one: thirteen
   burglaries point at three sellers, the sellers point at two dealers, and a plate in the middle is seen near a
   string of burglaries.
2. **Open `drug_supply_handset_network`.** Most groups are small: one handset and its handful of contacts. Look for
   numbers with dozens of partners: a few of them share most of their partners and all call the same four numbers.
   Set the time range to March to July 2026 and play it: the busy number changes, its callers do not.
3. **Open `suspicious_witness`.** People who witnessed more than one incident, with everyone on those incidents.
   Most are a witness and two incidents. One witness has a `co_arrested` link to a suspect.
4. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (admin guide). The
   Pipelines land November 2026 and the next Job run rebuilds the links; reopen `burglary_series` to see one
   more serial match.

## Exercises

Work blind first: each has a hint, then the answer. The answers are not in the data.

### 1. Burglary series — which burglaries, which sellers, and which vehicle?

*Hint:* in `burglary_series`, find the seller with the most `item_match` links. Follow `lives_at` from that
seller, then `keeper_of` and `seen_near`.

**Answer:** **18 burglaries** in **Leeds North and Leeds East** between **2026-02-04 and 2026-04-27**, from
`INC-01023` to `INC-01962`, all daytime break-ins while the occupants were out. Of the 53 items stolen, 21 (40 %)
were sold to dealers **`DLR-04`** and **`DLR-09`** by three sellers, **`P-00476`**, **`P-04874`** and
**`P-00785`**, and the dealers recorded the matching serial on 13 of those (62 %), one serial on each of 13 scenes.
Seller `P-00476` lives at **`ADR-1377`** (38 Teasel Street, Leeds North) with **`P-05167`**, who keeps the silver
Castell **`ZX 9117 X`**. Cameras within 2 km read it within 30 minutes of **11 of the 18** scenes. Every scene has a
serial match or a read, so all 18 join one group. **Not the story:** the black Everly taxi **`ZX 3465 D`**, kept by
`P-05203`, is seen near **54** incidents across the whole year and the whole of Leeds, none of them in the series;
it is not in this view, and in `police_links_dataset` it has no link to any series incident or person.

### 2. Drug-supply network — which numbers controlled it, and in what order?

*Hint:* in `drug_supply_handset_network`, take a number with about 30 partners that calls four numbers again and again.
Run **Link prediction** or compare its partners with those of the other busy numbers.

**Answer:** five controller numbers took over from one another, every 3 to 5 weeks:

| Controller number | In use |
|---|---|
| `+447700900897` | 2026-03-02 to 2026-03-28 |
| `+447700900121` | 2026-03-31 to 2026-04-28 |
| `+447700900079` | 2026-04-29 to 2026-05-28 |
| `+447700900069` | 2026-05-29 to 2026-06-21 |
| `+447700900354` | 2026-06-24 to 2026-07-14 |

Each was called by 30 to 36 customer numbers, **75 to 80 %** of them carried over to the next controller, and **60
customer numbers** in all. Every controller called the same four runner numbers: `+447700900341`, `+447700900496`,
`+447700900407` and `+447700900340`. No controller number is attributed to anyone. **Not the story:** the delivery
driver's number **`+447700900260`** (attributed to `P-00371`) makes short calls to **208** numbers all year, each
once to three times; no partner carries over to another number, and it never calls a controller, runner or
customer.

### 3. Drug bases at vulnerable residents' addresses — which addresses?

(In the UK this is called "cuckooing".)

*Hint:* in `drug_supply_handset_network`, follow the four runner numbers to numbers that are not controllers, then
`uses_number` and `lives_at`.

**Answer:** the runners rang three numbers again and again between March and July 2026: `+447700900191`,
`+447700900780` and `+447700900272`. They are attributed to **`P-04162`**, **`P-00174`** and **`P-04856`**, who live
alone at **`ADR-1225`** (90 Nightjar Lane, Leeds South), **`ADR-1597`** (103 Redwing Grove, Leeds West) and
**`ADR-2337`** (127 Larch Row, Leeds West). All three are flagged `vulnerable`, and none has a custody record or
appears on an incident. **Not the story:** the shared house **`ADR-0264`** (28 Birch Way, Sheffield East) has 11
residents and 7 attributed numbers that call each other and their own contacts all year; none of them ever calls a
runner, controller or customer.

### 4. A witness linked to the suspect — who?

*Hint:* in `suspicious_witness`, look for a `co_arrested` link between two people on the same incident.

**Answer:** **`P-01956`** is a witness to two unrelated incidents: an assault, **`INC-02159`** (Leeds West,
2026-05-14 22:41), and a theft, **`INC-03169`** (Sheffield South, 2026-08-21). The suspect in the assault,
**`P-00954`**, was arrested together with `P-01956` on 2026-02-11 (custody group `CAG-1030`). No one else in the data
witnessed an incident whose suspect they had been arrested with. **Not the story:** the shopkeeper **`P-01231`** is a
witness to **6** incidents outside a shop in Bradford East over the year, and
has no custody record at all.

## Advanced tier: Investigation, four-eyes and the serial-match Alert Rule

The same Space carries an Advanced tier. It works only on a build where people sign in (Standard and above; the
showcase is demonstrated on a Preview build with Demo Auth). On Basic it stays inert: nothing in it runs or refuses to
load. Setting it up is in the [admin guide](admin-guide-install.md#advanced-tier-preview-with-demo-sign-in).

| Persona (Demo User) | Role in the story | What they can do |
|---|---|---|
| `police.analyst` | intelligence analyst, lead of the Investigation | runs the analysis, adds steps, asks for a large expand, authors Alert Rules; cannot approve their own request |
| `police.sio` | senior investigating officer, second approver | a member of the Investigation (reviewer); approves or denies a held expand; runs Jobs; owns the Case |
| `police.reviewer` | reviewer, read-only | a member: reads the Investigation, adds nothing, approves nothing |
| `police.outsider` | not a member | cannot open the Investigation |
| `admin` | platform administrator | lead member; edits the Link Analysis settings |

No Entity Type is masked: every id is synthetic and names no real person, so no persona needs a Reveal.

**The Investigation** `burglary-series` (over `police_links_dataset`) starts from the plate `ZX 9117 X` and the seller
`P-00476`, expands one step, notes the plate, and narrows the time to February to May 2026. The Entity List
`series-sellers` holds the three sellers. The Case *Burglary series, Leeds North and East, Feb-Apr 2026* is linked to
it and assigned to `police.sio`.

**Four-eyes:** an expand with no fan-out limit (or a fan-out above 2 000) does not run; it waits in
`police.sio`'s approval inbox. Try it: as `police.analyst`, expand with no limit, then sign in as `police.sio` and
approve it.

**The Alert Rule** `police_repeat_seller_serial_matches` watches the stolen-property matches. After each build of
`police_links_dataset`, the `police_serial_match_counts` Job counts the `item_match` links, split into matches by a
seller who has sold stolen items from 2 or more incidents and one-off matches. The rule raises a WARNING when the first
count is 2 or more. On the shipped data it reads **12**: the series sellers `P-00476` (9) and `P-04874` (3). The **7
one-off matches** (six ordinary stolen items resold once each, and `P-00785`'s single series item) never count: one
stolen item at a dealer is everyday, the same seller again and again is a pattern. The Alert names the count, not a
person; find the sellers in the Investigation. The staged month brings one more match by `P-04874`, so the count
rises to 13. The count is a running total: while the first Alert is open, the 13th match does not raise
a second one.

### 5. Advanced tier — who may see and do what?

*Hint:* sign in as each persona and open `burglary-series`; as `police.analyst`, expand with no limit.

**Answer:** `police.analyst`, `police.sio`, `police.reviewer` and `admin` can open it; `police.outsider` gets "not
found". Only `police.analyst` and `admin` add steps. The unlimited expand is held; `police.analyst` and
`police.reviewer` cannot approve it; `police.sio` approves it and it runs. **Not the story:** the one-off sellers
of ordinary stolen goods never raise the Alert, however many of them there are.

## Tester's checklist

The ground truth is `tools/la-police-ground-truth.json` (repository only, not in the download); the golden tests
are `tools/gen-la-police-data.test.mjs` and `LaPoliceLinksJobTest`; the Advanced tier's are `LaPoliceTemplateTest`
and `tools/seed-la-police.test.mjs`.

- [ ] All eleven Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `police_links_dataset` has nine kinds and one row per pair and kind.
- [ ] Each saved view draws without "Result cut short".
- [ ] Each story's members form one connected group in its view: the 18 burglaries with the 3 sellers, `P-05167`,
      `ADR-1377`, `ZX 9117 X`, `DLR-04` and `DLR-09`; the 5 controllers with the 4 runners, the 60 customers, the 3
      base numbers, their residents and addresses; `P-01956` with `P-00954` and `INC-02159`.
- [ ] No look-alike links to a story entity: `ZX 3465 D` and `P-05203`, `+447700900260` and `P-00371`, `ADR-0264`
      and its residents and numbers, `P-01231`.
- [ ] Advanced tier, on a Demo Auth build: the Alert Rule is armed and raises one WARNING with value 12; the seed runs
      with nothing failed; the outsider cannot open `burglary-series`; only `police.sio` can approve the held expand.
- [ ] Basic: the log says "Not arming alert rule 'police_repeat_seller_serial_matches'", `police_serial_match_alerts`
      finds no rule breached, and nothing errors.
