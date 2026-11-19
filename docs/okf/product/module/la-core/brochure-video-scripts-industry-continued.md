---
type: Brochure
title: Inspecto LA short videos — scripts and agency briefs — Industry videos (continued)
description: Part 3 of 4 of Inspecto LA short videos — scripts and agency briefs: 
---

# Inspecto LA short videos — scripts and agency briefs — Industry videos (continued)

Part 3 of 4 of [Inspecto LA short videos — scripts and agency briefs](brochure-video-scripts.md) · previous: [Industry videos](brochure-video-scripts-industry.md) · next: [Videos 4-7, what they show and open points](brochure-video-scripts-closing-videos.md).

## 3 · Industry videos — one per industry (9 videos) (continued)

### 3.4 · Utilities (about 2 min)

| | |
|---|---|
| Audience | Revenue-protection teams at electricity utilities. |
| Film on | Basic install. |
| Ships as | *Utilities* (`la-utilities`, the industry Space composed from the `la-utility` use case): five stories in one year of synthetic data, every Dataset mapped, saved views `meter_tampering_contractor`, `account_churn_by_bank_account` and `prepaid_token_fraud_vendor`. |
| Ready | Now. Draft 3 (480p, 2026-11-08) was recorded from this script on the *Utilities* Space. |
| Optional shot | The loss-by-transformer Geo View, `TX-007` and `TX-019` over 15 %: label it *Inspecto LA Standard* (Geo Views are inert on Basic) and film it on a Standard or higher install. |
| As filmed | Every Dataset says what its id columns hold (*Premise*, *Transformer*, *Contractor*, *Account*, *Bank account*, *Vending point*) and has its own Mapping, so the Map beat shows the real column screen. The links the story uses still come from the `utility_links` Job, so the own-data line names the join. Picking `utility_links_dataset` in the Start box sets *Entity Type* to *Utility entity*. The ask starts from the 22 low meters, not from the contractor-network saved view: there, *Busiest nodes* ranks the busy contractors `C-27` and `C-21` first, and volume alone is not the story. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Electricity revenue protection | `la-utility` (built) | Meter reads and a log of field visits. | 22 premises under transformers `TX-007` and `TX-019` drop 40–70 % within 2 weeks of a visit by contractor `C-14`; transformer supply stays flat, so the two transformers' loss rises to 17.9 % and 24.7 %. | *Start a graph* ▸ *Pasted values*: the 22 low meters, drawn with their links. *Busiest nodes*: `C-14` first with 22 links, then `TX-007` and `TX-019` with 11 each. *Show on graph* on `C-14`. | 22 premises to inspect, one contractor to question. | A holiday home whose use drops every winter, with no visit (`PRM-0773`, not among the 22 pasted). |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 10 | A transformer's daily reads as they landed, steady line after line; the title over them. | Utilities · *Example · synthetic data* | Utilities lose money to meter tampering. A bypass makes the meter read low, the bill drops, and the energy is still used. To the billing system it looks like a customer using less. |
| 2 | 9 | `METER_READ_202602.csv` and `FIELD_VISIT_20260930.csv` side by side in a plain viewer, scrolling slowly. | — | Here is a year of meter reads, and the log of field visits. Twenty-two meters started reading low; the transformers feeding them didn't. |
| 3 | 11 | The Data page: open `field_visit_dataset`: `premise_id` holds *Premise*, `contractor_id` holds *Contractor*, and its Mapping reads premise → contractor, kind `visit_type`, when `visit_at`. | *What each column holds* | Say once what each column holds. Here the premise column holds premises, the contractor column holds contractors, and each visit links the two. Every file in the Space is mapped the same way. |
| 4 | 13 | *Start a graph* ▸ *Start from* ▸ *Pasted values*; paste the 22 premise ids; *Start from these*. The 22 premises draw with their links; the legend stays open on `supplied_by`, `account_at` and `visited_by`. | *Pasted values · 22 low meters* | Paste the twenty-two low meters and draw them. Each circle is a premise, a transformer, an account or a contractor. Each line is a supply, an account or a visit. |
| 5 | 9 | Fold the legend; open the toolbox from the right rail; *Busiest nodes*. The list: `C-14` 22 links, `TX-007` 11, `TX-019` 11. | *Busiest nodes* | Now ask what they have in common. Busiest nodes counts each circle's lines, so whatever the low meters share rises to the top. |
| 6 | 12 | A beat of quiet; *Show on graph* on `C-14`; a gentle push-in on it, the premises around it. | *C-14 · 22 links · TX-007 and TX-019* | One contractor, C-14, visited every one of them, and they all sit under two transformers. Twenty-two premises to inspect, one contractor to question. |
| 7 | 9 | The meter file beside the graph, `C-14` at its centre. | — | In the meter file each premise is a row on its own, and the visits live in another file. The links put them on one picture, and the contractor they share stands out. |
| 8 | 10 | The Space's home page: *My work* lists `meter_tampering_contractor`, `account_churn_by_bank_account` and `prepaid_token_fraud_vendor`. | *Ships with Inspecto LA · Utilities* | This example ships with Inspecto LA. Open the Utilities Space and explore it yourself: four more stories are planted in the same data, from illegal reconnections to prepaid token fraud. |
| 9 | 10 | The raw files dissolving into the graph. | *Play with it · your data, any domain* | These records are examples. Play with them and look for the cases we didn't show you. Then bring your own records, from any domain you can think of, set up how they link once, and ask who is connected. |
| Close | 11 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For revenue protection.* · *Try it on your own data.* | Inspecto LA. |

The list under `C-14` also offers *Add to exclusion list* (the product's standard hint for any busy node). The voice-over
passes over it; product decides whether that hint should stay on a node the analyst is looking at.

### 3.5 · Law enforcement (about 2 min)

| | |
|---|---|
| Audience | Police intelligence analysts. |
| Film on | Basic install. |
| Ships as | *Law enforcement* (`la-law-enforcement`, the industry Space composed from the `la-police` use case, every Dataset mapped); saved views `burglary_series`, `drug_supply_handset_network`, `suspicious_witness` (and the Geo View `incident_map`, inert on Basic). |
| Ready | Now. |
| Alternative segment | The drug-supply handset network: one controller number per period, its handset changing every 3–5 weeks, 75–80 % of about 60 customers carried over (look-alike: a delivery driver's phone with many short calls and no carry-over). |
| As built | The series is 18 burglaries; the saved view's tour speaks of the 13 that a dealer's recorded serial ties to a scene. Say 18 for the series and 13 for the serial matches. Links come from the `police_links` Job. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Criminal investigation | `la-police` (built) | Incident reports, stolen property, dealer sales and number-plate reads (`INCIDENT_REPORT_20261031.csv`, `DEALER_SALE_20261031.csv`). | 18 burglaries in Leeds North and Leeds East, 2026-02-04 to 2026-04-27; 21 of 53 stolen items sold to dealers `DLR-04` and `DLR-09` by sellers `P-00476`, `P-04874` and `P-00785`, the serial recorded on 13. `P-00476` lives at `ADR-1377` with `P-05167`, keeper of the car `ZX 9117 X`, read by cameras near 11 of the 18 scenes. | Saved view `burglary_series`; *Busiest nodes*: the car `ZX 9117 X` (15 links), the seller `P-00476` (12), the seller `P-04874` and the dealer `DLR-04`; *Show on graph* on the car. Filmed so in draft 1 (2026-11-08). The user guide's exercise reaches the same people by following `item_match`, `lives_at`, `keeper_of` and `seen_near`. | Three lines of enquiry: the dealers, the sellers, the car. | The black taxi `ZX 3465 D`, seen near 54 incidents across the year and the city, none in the series; not in the view, no link to the series. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 9 | Incident reports under the title. | Law enforcement · *Example · synthetic data* | A burglary series rarely leaves a suspect at the scene. But stolen goods have to be sold, and the people selling them have homes and cars. |
| 2 | 6 | `INCIDENT_REPORT_20261031.csv` and `DEALER_SALE_20261031.csv` as they landed. | — | Incident reports, stolen property with serial numbers, second-hand dealers' purchase logs, and number-plate camera reads. Different systems, different teams. |
| 3 | 8 | The Data page: open `dealer_sale_dataset`: `seller_person_id` holds *Person*, `dealer_id` holds *Dealer*; its own Mapping reads seller → dealer, when `sold_at`. | *What each column holds* | Say once what each column holds: a seller is a person, a dealer is a dealer, and each sale links the two. Every file is mapped the same way; one Job then joins them into a single list of links. |
| 4 | 9 | Saved view `burglary_series`, legend open. | *burglary_series* | Each circle is a burglary, an item, a person, a dealer, an address or a car. Burglaries point at three sellers; the sellers point at two dealers. |
| 5 | 9 | Fold the legend; open the toolbox; *Busiest nodes*. The list: `ZX 9117 X` 15 links, `P-00476` 12, `P-04874` 6, `DLR-04` 5. | *Busiest nodes* | Ask who sits in the middle. Busiest nodes counts each circle's lines, so whatever ties the series together rises to the top. |
| 6 | 10 | A beat of quiet; the series held as one group, the car joining it. | *2 dealers · 3 sellers · 1 car · 11 of 18 scenes* | Two dealers, three sellers, and a car kept at the seller's address, seen near eleven of the eighteen scenes. Three lines of enquiry. |
| 7 | 8 | The group held. | — | A taxi was seen near more incidents than that car, all over the city, and none in this series. The links tell a busy car from a connected one. |
| 8 | 10 | The Space's home page, its saved views under *My work*. | *Ships with Inspecto LA · Law enforcement* | This example ships with Inspecto LA. Open the Law enforcement Space and explore it yourself: a drug-supply phone network and a suspicious witness are planted in the same data. |
| 9 | 7 | Your data. | *Play with it · your data, any domain* | These records are examples. Play with them and look for the cases we didn't show you. Then bring your own records, from any domain you can think of, set up how they link once, and ask who is connected. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For intelligence analysts.* · *Try it on your own data.* | Inspecto LA. |

### 3.6 · Public sector (about 2 min 30 s)

| | |
|---|---|
| Audience | Public-procurement integrity units, benefits-integrity and tax-compliance teams. |
| Film on | Basic install. |
| Ships as | *Public sector* (`la-public-sector`, the industry Space composed from `la-procurement` and `la-benefits`, every Dataset mapped; Basic). |
| Ready | Now: both Spaces are built. |
| Alternative segments | `la-procurement` also ships cover bids from one IP address, an official approving changes for a relative's firm, and a debarred firm reborn under its old directors; `la-benefits` ships payments to the deceased, undeclared work, and a tax-preparer refund ring. Each has its own saved view and look-alike in its user guide. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Procurement | `la-procurement` (built) | Tenders, bids and directors (`TENDER_202601.csv`, `BID_202601.csv`, `DIRECTOR_20260930.csv`). | `CO-0117` Aire Paving, `CO-0242` Thistle Roadworks, `CO-0388` Wren Highways and `CO-0561` Quill Highways are the only bidders on the 24 Highways North tenders, winning in turn; losers bid 5–12 % above the winner. `CO-0242` and `CO-0561` share a director, `P-00001`. | Saved view `bid_rotation` (the Road maintenance market): one block of tenders is bid on by the same four firms and nobody else; hover the `bid_on` links (losers 5–12 % above); read `won` in date order; the `directed_by` links show the shared director. | A bidding pattern to refer, with the shared director named. | `CO-0021`, `CO-0055` and `CO-0090` win about three in four Highways South tenders, but others bid against them, the lowest bid always wins, and they share no director with the four. |
| B | Benefits and tax | `la-benefits` (built) | Benefit claims and household declarations. | Address farm: `AD-03141` has 14 residents, all claimants under 5 surnames, each declaring a household of one; all 14 are paid into 3 accounts (`SYN-57707034`, `SYN-19642862`, `SYN-47766627`) that nobody else uses. | Saved view `address_farm` (every address with 8 or more claimants): three care homes drawn as stars, and `AD-03141`. Click `AD-03141` for its node detail; its 14 claims meet in the 3 accounts. Not *Busiest nodes*: it would rank the larger care homes first. | 14 claims and 3 accounts to review. | The three care homes in the same view (`AD-00412` Linden Court, 31 residents; `AD-02718` Harbour View, 24; `AD-05077` Orchard House, 27): one declaration each, every resident paid into their own account. They are on screen, so the voice-over names them as genuine. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 8 | Opening: tender awards and benefit claims flash past, raw. | Public sector · *Example · synthetic data* | Public money leaks two ways: contracts won by firms that agree not to compete, and benefits paid to claims that are not what they say. |
| 2 | 8 | Map: The Data page: open `bid_dataset`: `bidder_id` holds *Company*, `tender_id` holds *Tender*; its own Mapping reads company → tender, when `submitted_at`. | *What each column holds* | Each of these began as a raw export. You say once what each column holds and how a row links two of them; then you ask. |
| A1 | 8 | Tenders under the title. | *Procurement* | When firms take turns to win, every tender looks competitive and the public still pays too much. |
| A2 | 5 | `TENDER_202601.csv` and `BID_202601.csv` as they landed. | — | Tenders, bids and amounts, and a register of company directors. |
| A3 | 9 | Saved view `bid_rotation`, legend open. | *bid_rotation* | This is the whole road-maintenance market. Each circle is a tender, a firm or a director; each line is a bid, a win or a directorship. |
| A4 | 8 | The closed block of tenders; hover a `bid_on` link; read `won` in date order. | *bid_on · won* | Ask who bids where. One block of tenders draws the same four firms and nobody else, and every losing bid is a little higher than the winner's. |
| A5 | 10 | A beat of quiet; the four firms and director `P-00001`, held. | *4 firms · 24 tenders · 1 director* | Four firms win twenty-four tenders in turn, and two of them share a director. A bidding pattern to refer. |
| A6 | 7 | The rotation beside the busy firms of the south. | — | Three other firms win even more often, but against real rivals. The links show who competes, and who only pretends to. |
| B1 | 8 | Claims under the title. | *Benefits and tax* | One address can hide many false claims: people who say they live alone, each paid separately. |
| B2 | 5 | Claims and household declarations as they landed. | — | Claims and household declarations: a name, an address, a bank account. |
| B3 | 8 | Saved view `address_farm`: four addresses; three draw as big stars. Legend open. | *address_farm* | This view keeps every address with eight or more claimants. Each circle is a person, a claim, an address or an account. Three are care homes, each resident paid into their own account. |
| B4 | 7 | Click `AD-03141`: its node detail. | *Node detail* | Ask what the fourth one is. |
| B5 | 10 | Its 14 claims meeting in 3 accounts, held. | *AD-03141 · 14 claimants · 3 accounts* | The fourth is a house: fourteen people, each living alone, paid into three accounts that nobody else uses. Claims to review. |
| B6 | 7 | The house beside a care home. | — | A care home and a farmed address both hold many claimants. Only the links to the money tell them apart. |
| 3 | 10 | Explore it: each Space's home page. | *Ships with Inspecto LA · Public sector* | Both examples ship with Inspecto LA, in the Public sector Space. Open it and explore: each comes with a short tour, and an exercise for every story, with the answers. Then go past the exercises: change a filter, start from another circle, and see what else turns up. |
| 4 | 7 | Your data. | *Play with it · your data, any domain* | These records are examples. Play with them and look for the cases we didn't show you. Then bring your own records, from any domain you can think of, set up how they link once, and ask who is connected. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 5 | 4 | End card. | *For public-sector integrity.* · *Try it on your own data.* | Inspecto LA. |

### 3.7 · Healthcare (about 2 min)

| | |
|---|---|
| Audience | Program-integrity teams at health insurers and public payers. |
| Film on | Basic install. |
| Ships as | *Healthcare* (`la-healthcare`, the industry Space composed from the `la-health` use case, every Dataset mapped); saved views `referral_kickbacks`, `phantom_billing`, `pill_mill`, `upcoding_clinics`. |
| Ready | Now. |
| As built | The shipped story is a shared home, not a spouse: the centre's owner lives at one address with the owner of one referring practice. Concentration alone is not the finding; the shared home is. Only Brackenmoor's owner has that tie; say nothing of the second York practice beyond its referrals. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Provider billing | `la-health` (built) | Referrals, providers and owners (`REFERRAL_202604.csv`, `PROVIDER_20260930.csv`, `OWNER_20260930.csv`). | `PR-1426` Brackenmoor Surgery (68 of 79 referrals) and `PR-1392` Kilnworth Medical Practice (182 of 216), both in York, send their referrals to `PR-1170` Emberlea Imaging Centre. The centre's owner `OW-0434` lives at `AD-05150` with `OW-1270`, the owner of Brackenmoor Surgery. | Saved view `referral_kickbacks`: nine practices; seven point at the only hospital in their town, two at the imaging centre; follow `owned_by` from both ends of the link, then `lives_at`. | A conflict of interest to look into. | `PR-0828` Elder Grove Medical Practice sends 38 of 44 referrals to `PR-1129` Saltmere Bay Hospital, the only hospital in Scarborough, owned by a health trust; six more Scarborough practices do the same. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 9 | Referral counts under the title. | Healthcare · *Example · synthetic data* | When a doctor profits from where patients are sent, the referrals stop following need. Payers see the claims, not the owners behind them. |
| 2 | 6 | `REFERRAL_202604.csv` and `OWNER_20260930.csv` as they landed. | — | Referrals: which practice sent which patient where. And the enrolment file: who owns each provider, and where they live. |
| 3 | 8 | The Data page: open `referral_dataset`: `from_provider` and `to_provider` hold *Provider*, `member_id` holds *Member*; its own Mapping reads provider → provider, when `referred_on`. | *What each column holds* | Say once what each column holds: practices and providers, and who referred to whom. Every file is mapped the same way; one Job then joins them into a single list of links. |
| 4 | 9 | Saved view `referral_kickbacks`, legend open. | *referral_kickbacks* | This view keeps every practice that sends most of its referrals to one place. Each circle is a practice, a provider, an owner or a home. |
| 5 | 9 | Seven practices pointing at the town hospital; two at the imaging centre; follow `owned_by`, then `lives_at`. | *owned_by · lives_at* | Seven practices send nearly everyone to the only hospital in town: that's geography. Two send theirs to a private imaging centre. Ask who owns each end. |
| 6 | 10 | A beat of quiet; the two owners meeting at `AD-05150`, held. | *Emberlea · Brackenmoor · one home* | The imaging centre's owner and the owner of one referring practice share a home. A conflict of interest to look into. |
| 7 | 8 | The town hospital's practices beside the imaging centre's. | — | Every practice here sends most referrals to one place. Only the ownership links show which of them has a reason to. |
| 8 | 10 | The Space's home page, its saved views under *My work*. | *Ships with Inspecto LA · Healthcare* | This example ships with Inspecto LA. Open the Healthcare Space and explore it yourself: phantom billing, a pill mill and upcoding clinics are planted in the same data. |
| 9 | 7 | Your data. | *Play with it · your data, any domain* | These records are examples. Play with them and look for the cases we didn't show you. Then bring your own records, from any domain you can think of, set up how they link once, and ask who is connected. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For program integrity.* · *Try it on your own data.* | Inspecto LA. |

### 3.8 · Logistics (about 2 min)

| | |
|---|---|
| Audience | Logistics security teams and cargo insurers. |
| Film on | Basic install. |
| Ships as | *Logistics* (`la-logistics-industry`, the industry Space composed from `la-logistics`, every Dataset mapped); saved views `driver_team_inside_job`, `theft_stop_hotspot`, `fictitious_carrier`. |
| Ready | Now. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Cargo theft | `la-logistics` (built) | Trips, warehouse scans and loss reports (`TRIP_202602.csv`, `WAREHOUSE_SCAN_202602.csv`, `LOSS_REPORT_20260930.csv`). | The two-driver team `DRV-0892` and `DRV-0893` of carrier `CAR-052` drove 12 loads that arrived short in 107 trips (2025-10-10 to 2026-08-20); night-shift scanner `EMP-0123` at `WH-06` scanned 10 of them onto the truck, between 00:50 and 03:30. | Saved view `driver_team_inside_job`; *Busiest nodes*: `DRV-0892` and `DRV-0893` (12 links each), then the night-shift scanner `EMP-0123` (10); *Show on graph* on the first. Filmed so in draft 1 (2026-11-08). The user guide's exercise reads `driver_losses:` and `shift:` on the same links. | Three people to interview. | `DRV-0894`, the busiest driver on the busiest route: 318 trips and 2 thefts, none scanned out by `EMP-0123`, nothing shared with the team. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 9 | Loss reports under the title. | Logistics · *Example · synthetic data* | Cargo goes missing on different routes in different months, and each loss is filed on its own. An inside job only shows when the losses are put side by side. |
| 2 | 6 | `TRIP_202602.csv` and `WAREHOUSE_SCAN_202602.csv` as they landed. | — | Trip logs: who drove which load. Scan logs: who scanned it onto the truck, and when. |
| 3 | 8 | The Data page: open `trip_dataset`: `driver_id` holds *Driver*, `shipment_id` holds *Shipment*; its own Mapping reads driver → shipment, when `depart_at`. | *What each column holds* | Say once what each column holds: drivers, loads, scanners, and which row links which. Every file in the Space is mapped the same way. |
| 4 | 9 | Saved view `driver_team_inside_job`, legend open. | *driver_team_inside_job* | Each circle is a driver, a load, a scanner or a warehouse; each line is a trip, a scan or a loss. |
| 5 | 9 | Fold the legend; open the toolbox; *Busiest nodes*. The list: `DRV-0892` 12 links, `DRV-0893` 12, `EMP-0123` 10. | *Busiest nodes* | Ask who keeps turning up. Busiest nodes counts each circle's lines across the lost loads. |
| 6 | 10 | A beat of quiet; the two drivers, `EMP-0123` and the 12 losses, held. | *2 drivers · 12 losses · 1 night scanner* | The same two drivers on twelve short loads, and the same night-shift scanner on ten of them. Three people to interview. |
| 7 | 8 | The team beside the busiest driver. | — | The busiest driver on the busiest route lost two loads too, but shares nothing with them. The links tell bad luck from a team. |
| 8 | 10 | The *Logistics* Space's home page, its saved views under *My work*. | *Ships with Inspecto LA · Logistics* | This example ships with Inspecto LA. Open the Logistics Space and explore it yourself: a theft hotspot and a fictitious carrier are planted in the same data. |
| 9 | 7 | Your data. | *Play with it · your data, any domain* | These records are examples. Play with them and look for the cases we didn't show you. Then bring your own records, from any domain you can think of, set up how they link once, and ask who is connected. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For cargo security.* · *Try it on your own data.* | Inspecto LA. |

### 3.9 · Enterprise (about 2 min 30 s)

| | |
|---|---|
| Audience | Internal audit, accounts-payable control, security, HR and internal investigations. All people and systems are invented. |
| Film on | A Basic install with the *Enterprise* Space (both segments, ids unmasked). |
| Ships as | *Enterprise* (`la-enterprise`, the industry Space composed from `la-audit` and `la-insider` since 2026-11-10, every Dataset mapped; Basic; saved views `leaver_exfiltration`, `access_creep`, `mutual_approvals`, and internal audit's `ap_vendor_identity`, `ap_approval_graph`, `gl_intercompany`). |
| Ready | Both segments now, in the *Enterprise* Space on Basic. |
| As built | The busy vendor `V-001` and the CFO `E-01` lead betweenness and are not findings: seed first, never start from a whole-graph ranking. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Internal audit | `la-audit` (built) | Vendor master and employee master (`VENDOR_MASTER_20260930.csv`, `EMPLOYEE_MASTER_20260930.csv`). | Vendor `V-117` shares bank account `BA-9031` and address `AD-9031` with employee `E-31`; its 14 invoices were all approved by `E-31`'s manager, `E-15`. | Saved view `ap_vendor_identity`; seed `V-117`, *Expand*: `E-31` joined by both `shares_account` and `shares_address`; *Connect seeds* between `V-117` and `E-31`; then `ap_approval_graph`: the only approver is `E-15`. | A conflict of interest to investigate. | The busy utilities vendor `V-001` and the CFO `E-01`: they lead betweenness and are not findings. |
| B | Insider threat | `la-insider` (built) | Access grants and a vendor change log (`ACCESS_GRANT_20260930.csv`, `VENDOR_CHANGE_20260930.csv`). | Operations manager `EMP-0007` granted `PAYROLL` and `TREASURY` to four of their own reports in January and February 2026, none in finance and none with a ticket; on 2026-05-14 one of them, `EMP-0482`, changed the bank account of vendor `VEN-0264`, which nobody else has ever changed. | Saved view `access_creep`: compare `dept:`, `role:` and `role_since:` with `finance_no_ticket:` on each `granted_by` link; then read the `changed_vendor` link. | An access review to start with one manager, and a vendor payment to stop and check. | `EMP-0062`, moved into payroll and granted `PAYROLL` the next day by the payroll manager under ticket `REQ-163776`. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 8 | Opening: vendor records and an access log flash past, raw. | Enterprise · *Example · synthetic data* | Some of the costliest fraud comes from inside: a vendor that is really an employee, or access nobody approved. |
| 2 | 8 | Map: the Data page, `ap_links_dataset`, built by the `ap_links` Job. | *One link Dataset · built by one Job* | Each of these began as a raw export. A Job, set up once, joins vendors, staff, invoices and approvals into links. |
| A1 | 8 | Vendor records under the title. | *Internal audit* | A new vendor gets set up, invoices arrive, and they are approved. Nobody checks whose bank account the money goes to. |
| A2 | 5 | `VENDOR_MASTER_20260930.csv` and `EMPLOYEE_MASTER_20260930.csv` as they landed. | — | The vendor master and the employee master, kept in different systems. |
| A3 | 8 | Saved view `ap_vendor_identity`; seed `V-117`; *Expand*. Legend open. | *ap_vendor_identity · V-117* | Start from one vendor and widen out. Each circle is a vendor, an employee, a bank account or an address; each line says who shares what. |
| A4 | 8 | *Connect seeds* between `V-117` and `E-31`; then `ap_approval_graph`. | *Connect seeds* | Ask how the vendor connects to this employee, and who approved its invoices. |
| A5 | 10 | A beat of quiet; `V-117`, `E-31` and `E-15`, held. | *V-117 · E-31 · BA-9031 · AD-9031* | The same bank account and the same address as one of your own employees, and fourteen invoices, all approved by that employee's manager. A conflict to investigate. |
| A6 | 7 | The two masters side by side. | — | Neither file says it alone. The shared account and address only meet in the links. |
| B1 | 8 | Access grants under the title. | *Insider threat* | Fraud from inside often starts with access nobody should have: granted quietly, then used once. |
| B2 | 5 | `ACCESS_GRANT_20260930.csv` and `VENDOR_CHANGE_20260930.csv` as they landed. | — | Access grants, and a log of changes to vendor records. |
| B3 | 9 | Saved view `access_creep`, legend open. | *access_creep* | Each circle is an employee, a system or a vendor; each line is a grant, or a change to a vendor. |
| B4 | 8 | Hover `EMP-0007`'s `granted_by` links: `finance_no_ticket:`, `last_finance_ticket: none`; then the `changed_vendor` link. | *granted_by · changed_vendor* | Ask who handed out finance access without a ticket, and what one of them did with it. |
| B5 | 10 | A beat of quiet; `EMP-0007`, the four reports and vendor `VEN-0264`, held. | *EMP-0007 · 4 grants · no ticket · VEN-0264* | One manager gave payroll and treasury to four people outside finance; one of them changed a vendor's bank account. An access review to start, a payment to stop and check. |
| B6 | 7 | The grants beside a normal one with a ticket. | — | A role change with a ticket looks the same in the access log. Only the links show who granted what to whom, and why. |
| 3 | 10 | Explore it: the *Enterprise* Space's home page. | *Ships with Inspecto LA · Enterprise* | This example ships with Inspecto LA. Open the Enterprise Space and explore it yourself: a leaver taking data and two colleagues approving each other's expenses are planted in the same data. |
| 4 | 7 | Your data. | *Play with it · your data, any domain* | These records are examples. Play with them and look for the cases we didn't show you. Then bring your own records, from any domain you can think of, set up how they link once, and ask who is connected. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 5 | 4 | End card. | *For internal audit and investigations.* · *Try it on your own data.* | Inspecto LA. |

---
