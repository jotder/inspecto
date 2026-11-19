---
type: Spec
title: "Inspecto LA case videos v2 — Insurance, Utilities, Law enforcement and the shared Standard module"
description: "The v2 specs of three industry case films (Insurance_ClaimantRing, Utilities_MeterTampering, LawEnforcement_BurglarySeries), the shared 90-second 'Make it defensible' module, and the lexicon of spoken forms for all twenty case films."
resource: tools/la-demo-cases.mjs
tags: [module, la-core, link-analysis, video, case-videos, spec]
timestamp: 2026-11-19T00:00:00Z
---

# Inspecto LA case videos v2 — Insurance, Utilities, Law enforcement and the shared Standard module

> Specs for three of the twenty case films, the shared Standard module and the spoken-form lexicon, written to the
> standard of [the v2 plan](../../../../superpower/2026-11-19-la-case-videos-v2-plan.md) (§3 content, §4 voice, audio and
> presentation, §5 format, §7 gates). They replace the v1 text of the same cases in
> [the v1 case scripts](brochure-video-demo-case-scripts.md) (generated from `tools/la-demo-cases.mjs`). Draft of
> 2026-11-19, not recorded, not reviewed by product or legal. `[check in UI: …]` marks a detail to confirm on the live
> product before recording. Scene lengths are estimates at about 150 spoken words a minute; the renderer paces the
> recording by the real voice length.
>
> **Screen truth (Standard :8081, Demo Analyst, checked 2026-11-19 on the saved views):** *Insurance* shows "227 entities
> masked" under the canvas, so claimant ids are aliases for the analyst; *Utilities* and *Law enforcement* ids read in
> full once labels are on (labels switch off above 80 nodes; Display options turns them on). The Telecom Space has the
> Investigations *SIM-swap wallet takeover* and *Unbilled usage at one dealer*.
> **State warning:** the demo Investigation *Meter tampering after one contractor's visits* holds recording steps from the
> training series (40 premises hidden, a keep, extra notes and a consumed four-eyes request). These specs use the saved
> views, never that Investigation, until the Space is reseeded.

## Insurance_ClaimantRing

> **A staged-accident ring: twelve claimants, one passenger, one garage, one clinic, one lawyer** · Standard · Insurance
> Space (`la-insurance`) · saved view `claimant_ring_shared_identity` · 4:45

- **Cast:** the repeat passenger (CL-0134, an alias for the analyst), the lawyer LAW-02, the garage GAR-03, the clinic
  CLI-04. **Decoys:** the family of four on one phone and one address (two claims in 18 months) and the busy general
  practice CLI-01.
- **Stakes:** a counter-fraud analyst at an insurer with a week to decide whether nine small files are one story. Each
  claim pays out; each is plausible; the loss is the nine payments and the ninth file's successor.
- **Vocabulary:** *staged accident* (a crash arranged for the payout), defined once in S02. *Passenger* is a claim role.
- **Ground truth:** `tools/gen-la-claims-data.py` header (stories 1, 2, 3, 4, 7): 12 claimants over 9 claims share 4
  phones and 3 addresses; one garage `GAR-03`, one clinic `CLI-04`, one lawyer `LAW-02`; one ring member is the
  passenger on 6 of the claims with 6 different drivers in under 7 months; 11 accidents within 400 m of one junction at
  02:00–04:00, none with a police report; `CLI-04` bills about 3.4 times its peers and is referred to only by
  `LAW-02`; benign look-alikes: the family of four and the busy clinic `CLI-01`. The plan table said "one witness and one
  phone": **corrected** (the data has one passenger, a phone and address share, and a garage, clinic and lawyer in
  common). v1's "twenty links to fourteen people and firms" for CL-0134 is not in the generator header
  [check in UI: the node's link count].
- **Before:** fresh unzip of `la-standard.zip`, Insurance Space, signed in as Demo Analyst, no Investigation open; start on
  the saved view; claimant ids are masked (say so, use roles).
- **Lead:** one ring of twelve claimants on nine claims, to refer to the special investigations unit.
  **What would change our mind:** if the garage repaired most claims in the district the shared garage would mean little
  [check in generator: all nine ring claims name `GAR-03` and the other eight garages see none of them].
  **Next actions:** refer the nine files together; keep the twelve as the Entity List *Ring claimants*; watch for the next
  claim that names the same garage, clinic and lawyer.
- **Voice style:** cold open 1.04 / 0.45 s; the S07 reveal holds 0.7 s before its second sentence; the close 0.96.
  Lexicon: the passenger's id is never spoken (masked); providers are said as "the garage", "the clinic", "the lawyer",
  with ids only in the chip and marks.
- **Try it:** open the saved view and say which of the two yellow groups is the household, and why. **Check:** can the
  claim form show this ring? *Answer: no. Each claim is clean on its own; only the shared phones, addresses and the
  garage, clinic and lawyer across claims show it.*

### S01 · Cold open  ~20 s
- Voice: A claims handler opens the ninth file in seven months from the same dark junction. [pause 0.4] A minor collision. A sore neck. A repair bill. Every file passes every check. [pause 0.6] Counter-fraud has a week to decide whether these nine files are one story.
- Chip: none (title over the first frame of the saved view, blurred).
- Action: still of the saved view under a soft blur; the story card appears: *the passenger · the garage · the lawyer*.
- Impact: none.

### S02 · Why the usual checks miss it  ~30 s
- Voice: Each claim is small. Each claimant has a clean record, a real address and a real phone. Each garage invoice is plausible. [pause 0.4] Checks read one claim at a time. A staged accident, a crash arranged for the payout, only shows when claims are read together. [pause 0.4] So what do these claimants share that the claim form never asks about?
- Chip: none.
- Action: intro card (case title, Insurance Space, *Standard*, teal-violet bar); then the saved view fades in.
- Impact: none.

### S03 · The data, in one breath  ~30 s
- Voice: Every claim party lands as one record: the person, the role, a phone, an address, and the garage, clinic and lawyer on the claim. [pause 0.4] A Job turns sameness into links. People who share a phone. People who share an address. Everyone who shares a claim. Both ends of a link are entities, and the kind of link keeps its name.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `claims_links_dataset` ▸ zoom on the Mappings area. [check in UI: the Mappings area names the from and to columns]
- Impact: mark the from and to columns once, as the voice says "both ends".

### S04 · The question  ~35 s
- Voice: Open the saved view Claimant ring shared identity. Claimants are joined when they share a phone or an address. [pause 0.4] The names read as aliases, because this Space hides claimant ids from the analyst. The shape is what matters. Two small groups stand out, drawn with yellow links. One is a family. One is not.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Claimant ring shared identity* ▸ Open ▸ hold. [check in UI: the two yellow groups at lower left and bottom of the canvas; the line "N entities masked"]
- Impact: computed impact on the open; mark both yellow groups; hold on the line "entities masked · Reveal…".

### S05 · First sweep, and the decoy  ~45 s
- Voice: First question: who has the most links? Press Busiest nodes. [pause 0.4] Seven to nine links each, for a handful of claimants. Nobody stands out, so counting will not find this ring. [pause 0.5] Now the two groups. Four people on one phone and one address, with two claims in eighteen months. That is a household. And the busy clinic beside them treats half the town. Both are ordinary. Set them aside.
- Chip: Busiest nodes
- Action: toolbox ▸ On screen ▸ Busiest nodes ▸ hold on the answer; then point at the four-person group and at the clinic. [check in UI: whether the family group sits at the left and the clinic is readable]
- Impact: grey *ruled out* marks on the family group and the clinic; the answer list is held 3 s.

### S06 · Follow one claimant  ~40 s
- Voice: Now start from the claimant who appears on the most claims. In the Investigation tab, choose Start from, one entity, and pick the Dataset. [pause 0.4] Before anything is drawn, the product tells you what it knows: how many links, and when it was first and last seen.
- Chip: Investigation · Start from · One entity
- Action: Investigation tab ▸ Start from *One entity* ▸ Dataset `claims_links_dataset` ▸ the claimant id ▸ hold on the preview line. [check in UI: a masked Space may refuse a typed raw id; if so start from the Entity List *Ring claimants* ▸ Start, and say "start from the kept list" in the voice]
- Impact: mark the preview line "links · first seen · last seen".

### S07 · The reveal  ~40 s
- Voice: There. One claimant, a passenger, on six claims in under seven months. [pause 0.4] Six different drivers. [pause 0.7] Around them, four phones and three addresses shared by twelve people. And on every one of the nine claims, the same garage, the same clinic, the same lawyer.
- Chip: Start · Select neighbours
- Action: Start ▸ wait for the draw ▸ Display options: labels on ▸ hover the passenger, the garage, the clinic, the lawyer in turn. [check in UI: providers are readable ids]
- Impact: computed impact on Start; yellow marks on the passenger and the three providers; the story card is replaced by the marks.

### S08 · Evidence you can defend  ~40 s
- Voice: Open the table under the graph. Each shared phone, each shared address, each repair line is a row you can read, sort and filter. [pause 0.4] Sort by date and the six passenger claims fall inside seven months. The clinic bills about three times its peers, and takes referrals from one lawyer only. [pause 0.6] What would change this? If that garage repaired most of the district, sharing it would mean little. It repairs none of the other claims.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by date ▸ hold on the six passenger rows. [check in UI: the Rows columns and that the date column sorts]
- Impact: mark the six rows; hold the table 3 s.

### S09 · What the team does next  ~40 s
- Voice: The lead is one ring: twelve claimants on nine claims, to refer to the special investigations unit. [pause 0.5] Three things on Monday. Refer the nine files together, not one by one. Keep the twelve as an Entity List, so every graph in this Space marks them. And watch for the next claim that names the same garage, clinic and lawyer.
- Chip: Entity Lists
- Action: rail ▸ Entity Lists ▸ *Ring claimants* ▸ hold on its members. [check in UI: the list exists and holds twelve members]
- Impact: mark the list name and its member count.

### S10 · The case file  ~25 s
- Voice: That is the case file. [pause 0.4] Lead: one ring. Evidence: shared phones and addresses, and one garage, clinic and lawyer, each a row. Next: refer, list, watch. To make a finding like this one defensible for a team, watch Make it defensible. Try it: say which yellow group is the household, and why.
- Chip: none.
- Action: case-file card (*Lead · Evidence · Next step · Try it*), 8 s; outro with the link to the shared module and to training episode F8.
- Impact: none.

## Utilities_MeterTampering

> **Twenty-two meters read low and the transformers do not: one contractor's visits come first** · Standard · Utilities
> Space (`la-utilities`) · saved view `meter_tampering_contractor` · 4:50

- **Cast:** the contractor C-14, the transformers TX-007 and TX-019, the premises between them. **Decoy:** the busiest
  contractor C-27. **Near-miss:** PRM-0737, visited by C-14 under the same transformers, whose reads did not fall.
- **Stakes:** a revenue-protection analyst at a distribution company; the power is delivered, less of it is billed.
- **Vocabulary:** *transformer* (the substation unit that feeds a group of premises), *read* (a meter reading), defined once
  in S02.
- **Ground truth:** `tools/gen-la-utility-data.ground-truth.json`, key `stories.meter_tampering`: hub `C-14`, 22 premises,
  transformers `TX-007` and `TX-019`, look-alike `PRM-0773`; `tools/gen-la-utility-data.py` (lines 13 and 170): a C-14
  visit, then a 40–70 % drop 2–14 days later, supply flat, three of the visits joint with `C-22`. v1's "C-27 with a
  hundred and sixty-four links" and "C-14 thirty-six premises, twenty-three under the two transformers" are data counts
  taken from the Space (training episode F3 notes C-27 at 157 premises) [check in UI: C-27's link count and C-14's 36
  premises]. The plan table (*the busiest contractor C-27 vs …*) is **confirmed**.
- **Before:** fresh unzip of `la-standard.zip`, Utilities Space, Demo Analyst, saved view, labels off above 80 nodes
  (Display options turns them on); **do not open the Investigation** *Meter tampering after one contractor's visits*
  until the Space is reseeded.
- **Lead:** one contractor to question and 22 premises to inspect. **What would change our mind:** the drops coming
  before the visits, or the transformers losing supply; PRM-0737 is the premise that keeps the lead honest.
  **Next actions:** inspect the 22 meters, largest drop first; question C-14, starting with the three visits joint with
  C-22; keep the premises as the Entity List *Tampered premises* and watch new visits.
- **Voice style:** ids spoken by the lexicon (C-14 "see fourteen", TX-007 "tee ex oh oh seven"); figures said once,
  rounded ("forty to seventy percent"); pace defaults.
- **Try it:** start from C-14 and find the one premise it visited under the same transformers whose reads did not drop.
  **Check:** why is the busiest contractor not the lead? *Answer: busy means it does the most work; the lead is the
  contractor whose visits come before the drops.*

### S01 · Cold open  ~20 s
- Voice: Twenty-two electricity meters start reading low in the same winter. [pause 0.4] The transformers feeding them do not. The power is still flowing. Less of it is being billed. [pause 0.6] A revenue-protection analyst has one question: what do these premises have in common?
- Chip: none.
- Action: still of the saved view, blurred; story card: *the contractor · the transformers · the premises*.
- Impact: none.

### S02 · Why the usual checks miss it  ~30 s
- Voice: Meter reads are checked one premise at a time, and every low read has an excuse: a holiday, a new boiler. A read is a meter's reading; a transformer is the unit that feeds a group of premises. [pause 0.4] The street is not the answer. The tariff is not the answer. And the field visits sit in a different file, so nothing ties a visit to a drop.
- Chip: none.
- Action: intro card (title, Utilities Space, *Standard*, amber bar); the saved view fades in.
- Impact: none.

### S03 · The data, in one breath  ~30 s
- Voice: Every field visit lands as one record: the premise, the contractor, the date, the kind of visit. [pause 0.4] A Job links premises to the contractors who visited them, and to the transformers that feed them. All three are entities. The visit date puts the links in order.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `utility_links_dataset` ▸ zoom on the Mappings area. [check in UI]
- Impact: mark the from and to columns and the time column.

### S04 · The question  ~35 s
- Voice: Open the saved view Meter tampering contractor. Premises, the contractors who visited them, and the transformers above. [pause 0.4] Labels switch off above eighty nodes, so open Display options and turn them on. The question is simple: is there a contractor whose visits come before the drops?
- Chip: File · Open… · Saved views · Display options
- Action: File ▸ Open… ▸ Saved views ▸ *Meter tampering contractor* ▸ Open ▸ brush icon ▸ Display options ▸ labels on. [check in UI: the labels option's name]
- Impact: computed impact on the open; mark the label line "node labels off above 80 nodes" before and after.

### S05 · First sweep, and the decoy  ~45 s
- Voice: Press Busiest nodes. [pause 0.4] The busiest contractor is C-27, by a wide margin. The obvious suspect. [pause 0.5] And wrong. C-27 does the most work in the city. Busy is not the same as connected to the drop. Set C-27 aside, and look for a quieter name.
- Chip: Busiest nodes
- Action: toolbox ▸ On screen ▸ Busiest nodes ▸ hold on the top row (C-27). [check in UI: C-27 leads and is readable]
- Impact: grey *ruled out* mark on C-27's node; the answer list held 3 s.

### S06 · Start from a quieter name  ~40 s
- Voice: Start from contractor C-14. In the Investigation tab, choose Start from, one entity, pick the Dataset, and type the id. [pause 0.4] Before you draw anything, the product says how many links this contractor has, and when they were first and last seen.
- Chip: Investigation · Start from · One entity
- Action: Investigation tab ▸ Start from *One entity* ▸ Dataset `utility_links_dataset` ▸ type `C-14` ▸ hold on the preview line ▸ Start.
- Impact: mark the preview line; computed impact on Start.

### S07 · The reveal  ~40 s
- Voice: C-14 visited thirty-six premises. An ordinary workload, until you ask which. [pause 0.7] Twenty-three sit under two transformers, TX-007 and TX-019. Twenty-two of those read forty to seventy percent lower within two weeks of the visit. [pause 0.4] The supply stayed flat.
- Chip: Select neighbours
- Action: select C-14 ▸ Select neighbours ▸ hover TX-007 and TX-019 ▸ hold. [check in UI: the premise count under the two transformers]
- Impact: yellow mark on C-14, TX-007 and TX-019; the story card is replaced.

### S08 · Evidence you can defend  ~45 s
- Voice: Open the table under the graph. Each visit is a row: the contractor, the premise, the date. [pause 0.4] Put the read history beside it and the drop follows the visit, premise after premise. [pause 0.6] One premise, PRM-0737, was visited too, and its reads did not fall. That is what would overturn this. If the drops came first, or the transformers lost supply, the lead would fail.
- Chip: Rows
- Action: dock ▸ Rows ▸ filter the visit rows to C-14 ▸ sort by date ▸ hold. [check in UI: the read history is in the Rows columns or in the details of a premise; if not, show the premise's details dialog]
- Impact: mark the C-14 visit rows; grey *not affected* mark on PRM-0737.

### S09 · What the team does next  ~40 s
- Voice: The lead: one contractor to question, twenty-two premises to inspect. [pause 0.5] Three things on Monday. Inspect the meters, largest drop first. Question C-14, starting with the three visits shared with C-22. And keep the premises as an Entity List, so the next visit to any of them shows on every graph.
- Chip: Entity Lists
- Action: rail ▸ Entity Lists ▸ *Tampered premises* ▸ hold on its members. [check in UI: the list exists and holds the 22 premises]
- Impact: mark the list name and its member count.

### S10 · The case file  ~25 s
- Voice: That is the case file. [pause 0.4] Lead: one contractor and twenty-two premises. Evidence: the visit rows, and the drop after each. Next: inspect, question, list. To make it defensible for a team, watch Make it defensible. Try it: find the premise C-14 visited whose reads did not fall.
- Chip: none.
- Action: case-file card, 8 s; outro with the links to the shared module and to training episode F3.
- Impact: none.

## LawEnforcement_BurglarySeries

> **Eighteen burglaries, no suspect: a seller, a dealer and a car that is read near eleven scenes** · Standard · Law
> enforcement Space (`la-law-enforcement`) · saved view `burglary_series` · 4:50

- **Cast:** the seller P-00476 (nine serial matches, all at the dealer DLR-04), the car ZX 9117 X kept by P-05167, a
  partner at the same address ADR-1377; the second dealer DLR-09 with the sellers P-04874 and P-00785. **Decoy:** the taxi
  ZX 3465 D, kept by P-05203, read near 54 incidents in the year and none in this series.
- **Stakes:** a detective sergeant leading a burglary series; the scene evidence is exhausted.
- **Vocabulary:** *serial match* (a stolen item's serial number found in a dealer's sale), *plate reader camera* (a camera
  that logs number plates with place and time), defined once in S02.
- **Ground truth:** `tools/la-police-ground-truth.json`, key `burglary_series`: 18 incidents, vehicle read near 11 of
  them (`vehicle_scenes`), plate `ZX 9117 X`, keeper `P-05167`, home `ADR-1377`, sellers `P-00476` / `P-04874` /
  `P-00785`, dealers `DLR-04` / `DLR-09`, 13 serial matches (P-00476: 9, all at DLR-04; P-04874: 3 and P-00785: 1, at
  DLR-09), `lookalike_taxi` plate `ZX 3465 D` keeper `P-05203` near 54 incidents; `tools/gen-la-police-data.py` header
  (story 1: the car is read within 2 km and 30 minutes of 11 of the 18 scenes; the keeper is the seller's partner at the
  same address). **Corrected against v1 and the plan table:** the plan table's "vehicle seen at four scenes" is wrong
  (eleven); v1 says P-00476 sold "at two dealers" but the data puts P-00476 at DLR-04 only and the two other sellers at
  DLR-09. The decoy is the taxi, not "the nearest suspect".
- **Before:** fresh unzip of `la-standard.zip`, Law enforcement Space, Demo Analyst, saved view; ids read in full.
  [check in UI: whether the taxi plate is drawn in the saved view; if not, the S05 voice names it and the mark falls on
  an empty place, so say "not drawn" in the voice]
- **Lead:** three lines of enquiry (the dealers, the sellers, the car and its keeper's address), shown as actions.
  **What would change our mind:** if the serials matched only by make and model they would be coincidences; here the
  serial numbers match. **Next actions:** visit DLR-04 and DLR-09; keep the three sellers as the Entity List *Burglary
  series sellers*; ask the camera team for the car's reads against the seven scenes it was not read near.
- **Voice style:** plates spoken by the lexicon ("zed ex nine one one seven ex"); "eleven of the eighteen" is a story
  figure, not a Dataset size; pace defaults, the reveal slower.
- **Try it:** in the saved view, find the seller whose address is the car keeper's address. **Check:** why is the
  taxi, seen near more incidents, not a lead? *Answer: it was near none of this series' scenes; the links tell a busy
  car from a connected one.*

### S01 · Cold open  ~20 s
- Voice: Eighteen burglaries in three months, across two neighbourhoods. No usable fingerprints. No witness. [pause 0.4] A detective sergeant has a map of pins and no suspect. [pause 0.6] But stolen goods have to be sold, and a sale leaves a record.
- Chip: none.
- Action: still of the saved view, blurred; story card: *the seller · the dealer · the car*.
- Impact: none.

### S02 · Why the usual checks miss it  ~30 s
- Voice: Each burglary is one report. Each second-hand sale is one dealer entry. Nobody reads the two together. [pause 0.4] A serial match is a stolen item's serial number turning up in a sale. A plate reader camera logs cars all day, and most of what it logs is noise. [pause 0.4] Kept apart, the records hide the series.
- Chip: none.
- Action: intro card (title, Law enforcement Space, *Standard*, slate bar); the saved view fades in.
- Impact: none.

### S03 · The data, in one breath  ~30 s
- Voice: Dealers log every sale: the seller, the item, the serial number, the date. Burglary reports log the stolen serials. Cameras log plates with a place and a time. [pause 0.4] A Job links sellers to dealers, items to the burglaries they came from, people to addresses, and cars to the people who keep them.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `police_property_links_dataset` ▸ zoom on the Mappings area. [check in UI]
- Impact: mark the from and to columns once.

### S04 · The question  ~35 s
- Voice: Open the saved view Burglary series. The incidents, the items, the sellers, the dealers, the addresses, and the cars read near the scenes. [pause 0.4] The view draws itself, and every id reads in full. A busy picture. Who is behind it?
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Burglary series* ▸ Open ▸ hold. [check in UI: the labels are on at this size]
- Impact: computed impact on the open.

### S05 · First sweep, and the decoy  ~45 s
- Voice: Press Busiest nodes. [pause 0.4] A number plate leads, ZX 9117 X. Then a seller, P-00476. A car, and a seller. Are they connected? [pause 0.5] First, one more car: a taxi read near fifty-four incidents this year, none of them in this series. A busy car is not a connected car. Set the taxi aside.
- Chip: Busiest nodes
- Action: toolbox ▸ On screen ▸ Busiest nodes ▸ hold on the top rows (ZX 9117 X, P-00476). [check in UI: the order of the top rows]
- Impact: grey *ruled out* mark on the taxi's plate (or the note "not drawn"); the answer list held 3 s.

### S06 · Start from the seller  ~40 s
- Voice: Start from the seller. In the Investigation tab, choose Start from, one entity, pick the Dataset, and type the id. [pause 0.4] The product says how many links this seller has, and when they were first and last seen, before anything is drawn.
- Chip: Investigation · Start from · One entity
- Action: Investigation tab ▸ Start from *One entity* ▸ Dataset `police_property_links_dataset` ▸ type `P-00476` ▸ hold on the preview ▸ Start.
- Impact: mark the preview line; computed impact on Start.

### S07 · The reveal  ~40 s
- Voice: P-00476 sold items from one burglary after another, nine times, all at one dealer, DLR-04. And lives at ADR-1377. [pause 0.7] At the same address, a partner keeps the car, ZX 9117 X. The cameras read that car near eleven of the eighteen scenes.
- Chip: Select neighbours
- Action: select P-00476 ▸ Select neighbours ▸ hover DLR-04, ADR-1377 and the car. [check in UI: the car links through the partner or the address]
- Impact: yellow marks on P-00476, DLR-04, ADR-1377 and ZX 9117 X.

### S08 · Evidence you can defend  ~40 s
- Voice: Open the table under the graph. Each sale is a row: the seller, the dealer, the item, the serial. [pause 0.4] The serials match the stolen reports, number for number. What would change this? If they matched only by make and model, they would be coincidences. They match by serial. [pause 0.4] Two more sellers use the second dealer, DLR-09. They are leads of their own.
- Chip: Rows
- Action: dock ▸ Rows ▸ filter to P-00476 ▸ hold on the sale rows; then filter to DLR-09. [check in UI: the Rows columns show the serial]
- Impact: mark the sale rows; hold the table 3 s.

### S09 · What the team does next  ~40 s
- Voice: The lead is three lines of enquiry: the dealers, the sellers, and the car with its keeper's address. [pause 0.5] On Monday: visit DLR-04 and DLR-09. Keep the three sellers as an Entity List, so every graph marks them. And ask the camera team for the car's reads against the seven scenes it was not seen near.
- Chip: Entity Lists
- Action: rail ▸ Entity Lists ▸ *Burglary series sellers* ▸ hold on its members. [check in UI: the list holds the three sellers]
- Impact: mark the list name and its member count.

### S10 · The case file  ~25 s
- Voice: That is the case file. [pause 0.4] Lead: a seller, two dealers and a car. Evidence: serial matches, and the car read near eleven scenes. Next: visit, list, ask. To make it defensible for a team, watch Make it defensible. Try it: find the seller who lives at the keeper's address.
- Chip: none.
- Action: case-file card, 8 s; outro with the links to the shared module and to training episode D3.
- Impact: none.

## Shared_MakeItDefensible

> **Make it defensible: the Standard step from a finding to a record a team can stand behind** · Standard · Telecom Space
> (`la-telecom`) · the Investigation *SIM-swap wallet takeover* · 1:30 · recorded once and linked from the end card of
> the other nineteen films (plan §4.4)

- **Cast:** the Investigation *SIM-swap wallet takeover* (`sim-swap-takeover`) and its held expand; Demo Analyst, Demo
  Manager. The Space also holds *Unbilled usage at one dealer*; open the right one.
- **Stakes:** an analyst whose finding must survive review: who did what, why, and has anything changed since.
- **Ground truth:** the screens are those recorded in training episodes F1, F4, F5 and F8 (Step log rows with number,
  kind, sentence, who and when; the *waiting for approval* line and the *Approve this expand?* dialog; *Build Dossier*,
  *Log intact*, *Manifest root*, *Verify this Dossier*; Undo is a step of its own); the figures come from the Investigation
  itself.
- **Before:** a **fresh unzip or reseeded** Telecom Space (the held expand must still be pending; training episode F5 consumed
  the one in the Utilities demo); signed in as Demo Analyst, then Demo Manager for M3 only; M3 shows the *Approve this
  expand?* dialog and presses **Cancel**, so the request is not consumed.
- **Lead:** none (this is a method film). **Next:** the training episodes F1 (the first Investigation), F4 (the step
  log), F5 (teams and four-eyes) and F8 (the Dossier).
- **Try it:** open an Investigation, build its Dossier, press Verify this Dossier. **Check:** does Undo erase a step?
  *Answer: no; Undo is recorded as a step of its own, and the step it takes back stays in the log.*

### M1 · From a finding to a record  ~15 s
- Voice: A finding is a picture. A record is something a team can check. [pause 0.4] Open an Investigation, and every move you make from here is kept.
- Chip: File · Open… · Investigations
- Action: File ▸ Open… ▸ Investigations ▸ *SIM-swap wallet takeover* ▸ Open ▸ hold on the Working Set line. [check in UI: the Working Set line]
- Impact: computed impact on the open; mark the Working Set line.

### M2 · Every step, with who and why  ~20 s
- Voice: Open the step log. Every step has a number that never changes, a sentence saying what it did, who took it, and why. [pause 0.4] Nobody can edit a row afterwards. Even taking a step back is a step: Undo is recorded, and the step it undoes stays in the log.
- Chip: Step log
- Action: dock ▸ Step log ▸ hold on one expand row, then on an Undo row if one exists. [check in UI: row wording]
- Impact: mark the number, the sentence and *by analyst* on one row.

### M3 · A step too big to take alone  ~20 s
- Voice: Some steps wait. An expand above the Space's limit says waiting for approval, and a second person decides. [pause 0.4] The manager sees the request, what it would do, and two buttons. Approving runs the step as the analyst's, and records the manager beside it.
- Chip: waiting for approval · Approve · Deny
- Action: Investigation tab ▸ Expand section ▸ hold on the held line ▸ sign out ▸ Demo Manager ▸ open the same Investigation ▸ *1 to approve* ▸ Approve ▸ hold on the dialog *Approve this expand?* ▸ **Cancel**. [check in UI: dialog wording; sign-out works only from an open Investigation page]
- Impact: mark the held line, then the badge and the two buttons.

### M4 · Sealed and verifiable  ~25 s
- Voice: To hand over, build the Dossier. It packs the Investigation, its steps and its references, and says Log intact when every recorded hash still agrees. [pause 0.4] One fingerprint, the manifest root, covers every piece.
- Chip: Build Dossier · Log intact · Manifest root
- Action: ⋯ actions ▸ Build Dossier ▸ Build Dossier ▸ scroll to *Log intact* ▸ *Manifest root*. [check in UI: the summary line]
- Impact: mark *Log intact*, then the root.

### M5 · Hand it over  ~10 s
- Voice: Press Verify this Dossier. Verified: nothing has moved since it was built. [pause 0.4] Anyone can run the same check.
- Chip: Verify this Dossier
- Action: Verify this Dossier ▸ hold on *Verified* and the three *none* lines.
- Impact: mark *Verified*.

## Lexicon

Spoken forms for every id family, abbreviation and amount form in the voice lines of all twenty case films (extracted from
`tools/la-demo-cases.mjs`; this seeds the lexicon test of plan §4.1, which must list each id of the twenty specs and its
spoken form). **Today** is what `speakable()` in `tools/la-video-voice.mjs` says; **v2 target** is the spoken form the
lexicon table must produce. The v2 convention: a number of one or two digits after the family is said as a number ("oh one",
"eighteen"), three to six digits are said digit by digit in groups of three ("zero zero seven"), alphabetic families are
said as a word when listed in the words set and letter by letter otherwise.

| Family | Example | Today | v2 target |
|--------|---------|-------|-----------|
| HUB | HUB-01 | hub zero one | hub oh one |
| MULE-HUB | MULE-HUB-01 | mule hub zero one | mule hub oh one |
| STRUCT | STRUCT-01, STRUCT-25 | struct zero one | struct oh one, struct twenty-five |
| RELAY | RELAY-1 | relay one | relay one |
| AG (agent) | AG-18 | A G one eight | A G eighteen |
| FARM | FARM-1, FARM-9 | farm one | farm one, farm nine |
| S (staff login) | S-41, S-72 | S four one | ess forty-one, ess seventy-two |
| DL (dealer) | DL-112, DL-047 | D L one one two | dee ell one one two, dee ell oh four seven |
| POS | POS-0008 | P O S zero zero zero eight | pee oh ess, zero zero zero eight |
| TILL | TILL-06 | till zero six | till oh six |
| CASHER | CASHER-01 | casher zero one | casher oh one |
| PER (person) | PER-03904 | P E R zero three nine zero four | person zero three nine zero four |
| SL (list entry) | SL-0137 | S L zero one three seven | list entry zero one three seven |
| CO (company) | CO-04431, CO-00941 | C O zero four four three one | company zero four four three one |
| CUS (customer) | CUS-00528 | C U S zero zero five two eight | customer zero zero five two eight |
| M (merchant) | M-317 | M three one seven | em three seventeen (said "merchant three one seven" in the voice) |
| CL (claimant / client) | CL-0134, CL-01125 | C L zero one three four | claimant zero one three four (never spoken where masked) |
| LAW, GAR, CLI, SUP | LAW-02, GAR-03, CLI-04, SUP-07 | L A W zero two | "the lawyer", "the garage", "the clinic", "the supplier" in the voice; the id only in chips |
| C (contractor) | C-14, C-22, C-27 | C one four | see fourteen, see twenty-two, see twenty-seven |
| TX (transformer) | TX-007, TX-019 | T X zero zero seven | tee ex oh oh seven, tee ex oh one nine |
| PRM (premise) | PRM-0737, PRM-0773 | P R M zero seven three seven | premise zero seven three seven |
| P (person, police) | P-00476, P-05167, P-00001 | P zero zero four seven six | pee zero zero four seven six |
| DLR (dealer) | DLR-04, DLR-09 | D L R zero four | dealer oh four, dealer oh nine |
| ADR (address) | ADR-1377 | A D R one three seven seven | address one three seven seven |
| INC (incident) | INC-01023 | I N C zero one zero two three | incident zero one zero two three |
| AD (address, public sector) | AD-03141 | A D zero three one four one | address zero three one four one |
| PR (provider) | PR-1170, PR-1392 | P R one one seven zero | provider one one seven zero |
| OW (owner) | OW-0434 | O W zero four three four | owner zero four three four |
| DRV (driver) | DRV-0892, DRV-0893 | D R V zero eight nine two | driver zero eight nine two |
| CAR (vehicle) | CAR-022 | C A R zero two two | vehicle zero two two |
| V (vendor) | V-117 | V one one seven | vee one seventeen (said "vendor one one seven") |
| VEN (vendor) | VEN-0264 | V E N zero two six four | vendor zero two six four |
| BA (bank account) | BA-9031 | B A nine zero three one | account nine zero three one |
| E (entry) | E-31, E-15 | E three one | ee thirty-one, ee fifteen |
| EMP (employee) | EMP-0007, EMP-0482 | E M P zero zero zero seven | employee zero zero zero seven |
| DEV (device) | DEV-FARM-01 | D E V farm zero one | device farm oh one |
| ZX (number plate) | ZX 9117 X, ZX 3465 D | zed ex nine one one seven ex | zed ex nine one one seven ex, zed ex three four six five dee |
| Phone and account numbers | 99979100001, 99891000001, +447700900897 | digit by digit | digit by digit in groups of three, with "plus" for the sign |
| SHA-256 | SHA-256 | S H A two five six | shaw two fifty-six |
| Abbreviations | SIM, MSISDN, KYC, AP, IBAN, ANPR, GP, IP | letters | letters, except SIM ("sim") and "plate reader camera" for ANPR |
| Amounts | 4 750, 5 000 | four thousand seven hundred and fifty | "just under five thousand" (the figure on screen) |
| Percent and ranges | 40–70 %, 97 % | forty to seventy percent | forty to seventy percent, ninety-seven percent |
| Durations and distances | 36 hours, 2 km, 400 m, 30 minutes | as written | thirty-six hours, two kilometres, four hundred metres, thirty minutes |
| Multiples | 3.4x | three point four x | about three times |
| Times and dates | 02:00–04:00, 2026-09-04 | two oh oh to four oh oh | from two in the morning to four, the fourth of September |
