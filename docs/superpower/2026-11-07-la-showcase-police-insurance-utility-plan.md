# Link Analysis showcase catalogue — police, insurance, utility and fifteen more (plan)

Status: draft, 2026-11-07; scope widened the same day to the **full catalogue** (operator: "ship the full
Showcase catalogue for users to play, learn and test"); 2026-11-08 the catalogue is grouped by industry and
`la-mfs` added. Product Map Items: existing `solution-pack.la-claims`,
`solution-pack.la-showcase`, `solution-pack.la-mobile-money`, `solution-pack.la-audit`; one new
`solution-pack.la-<id>` per new Space, added when its template lands.

## Goal

**Ship every Space in the catalogue below: 18 showcase Spaces in 9 industries**, each with synthetic data and planted stories, so
a user can **play** with Link Analysis on realistic data, **learn** it through exercises with answers, and
**test** it (and their own understanding) against known ground truth. Each showcase ships as **three separate
downloads**: the product, the Space (configuration) and the Space's data. A user installs the product once,
then adds any showcase by unzipping its Space and its data beside the install. **Every showcase loads and can be
explored on a Basic build**; Advanced builds add personas, Alert Rules, Investigations and Cases on top.

## Showcase catalogue

Status: **exists** = a template in `spaces/_templates/`; **planned** = specified below and scheduled in the
waves under *Steps*. Every story has a benign look-alike that must not join it (listed in the per-Space specs).
Realism notes come from the review in the next section. `la-daily-feed` and `la-showcase-off` are test fixtures,
not showcases, and are not in the catalogue.

The catalogue is grouped by **industry**, then by **use case** inside it. A template that covers two industries
is listed under both (`la-showcase`: its banking stories under Banking and finance, its telecom rings under
Telecom), with the same template id.

### Telecom

| Use case | Template | Stories | Status |
|---|---|---|---|
| Mobile money (anti-money-laundering) | `la-mobile-money` | Agent-assisted structuring (25 wallets via AG-18); 98 % pass-through mule chain to AG-39; nine-wallet device farm; four-wallet directed cycle; dormant wallet awakening; benign biller hub MER-ELECTRIC | exists |
| MFS (mobile financial services fraud) | `la-mfs` | SIM-swap wallet takeover through one call-centre login; agent split-deposit commission fraud; merchant cash-out collusion (spec 14) | exists |
| Telco fraud | `la-showcase` | Telecom rings for Wangiri, IRSF, SIM box, subscription fraud and mules; a 650-caller supernode | exists |
| Revenue assurance | `la-telco-ra` | Unbilled usage from one dealer's SIMs; dealer commission fraud with SIMs that go dormant; PBX hacking at night | exists |

### Banking and finance

| Use case | Template | Stories | Status |
|---|---|---|---|
| First-run sample (Basic) | `la-sample` | 12 smurf accounts structure cash into MULE-HUB-01 → RELAY-01/02 → OFFSHORE-77; SHELL-A..D money loop | exists |
| Financial crime tour | `la-showcase` | Mule ring and shell loop; TILL-06 takes 80 % of cash-out; SKIMMER-01 skims benefit payments; geo route of the ring | exists |
| Beneficial ownership and sanctions | `la-ownership` | Hidden owner behind a five-layer chain of 24 % stakes; a sanctioned person two hops above a customer; one nominee director on 120 companies; circular ownership | exists |
| Card and e-commerce fraud | `la-card` | Card testing from one device and IP range; account-takeover ring shipping to 5 drop addresses; colluding merchant refunding cards it never sold to; common point of purchase behind 60 compromised cards | exists |
| Trade-based money laundering | `la-trade` | Over-invoicing between companies sharing a director; containers going round in a circle; a phantom shipment on a vessel that was elsewhere; shell importers at one formation agent | planned |
| Crypto laundering | `la-crypto` | Peel chain from a hacked wallet; mixer hop matched by timing and amount; 40 accounts cashing out one cluster's funds | exists (Basic tier) |

### Insurance

| Use case | Template | Stories | Status |
|---|---|---|---|
| Claims fraud (Special Investigations Unit) | `la-claims` | 12 claimants sharing 4 phones, 3 addresses, one garage, clinic and lawyer; a passenger on 6 claims with 6 drivers; 11 night-time accidents near one junction; ring clinic bills 3× peers; 7 claims within 20 days of inception via one broker; duplicate claimant identity | exists |
| Claims fraud (additions) | `la-claims` | Parts-supplier ring (one supplier on invoices from many garages, parts never fitted); ghost broker (policies sold with false details, all paying from 2 bank accounts) | planned |

### Utilities

| Use case | Template | Stories | Status |
|---|---|---|---|
| Electricity revenue protection (non-technical loss) | `la-utility` | Meter tampering linked to one contractor's visits, seen as loss on two transformers; illegal reconnections closed by one contractor; debt-dodging account churn; commercial load on a residential tariff; prepaid token fraud from one vending point | exists |

### Law enforcement

| Use case | Template | Stories | Status |
|---|---|---|---|
| Criminal investigation | `la-police` | Burglary series traced through second-hand-dealer sales and one vehicle; drug-supply handset network with a rotating controller phone; addresses taken over as drug bases; a "witness" who was arrested with the suspect | exists |

### Public sector

| Use case | Template | Stories | Status |
|---|---|---|---|
| Bid rigging and procurement fraud | `la-procurement` | Four firms winning in turn; cover bids from one IP address; an official approving changes for a relative's firm; a debarred firm reborn under its old directors | exists |
| Benefits and tax fraud | `la-benefits` | 14 claimants at one address; payments to the deceased; claimants on an employer payroll; a tax preparer's refund ring | exists |

### Healthcare

| Use case | Template | Stories | Status |
|---|---|---|---|
| Provider billing fraud (payer) | `la-health` | Referral kickback ring to an imaging centre owned by a doctor's spouse; phantom billing for admitted or deceased patients; pill mill; upcoding clinics under one owner | exists |

### Logistics

| Use case | Template | Stories | Status |
|---|---|---|---|
| Cargo theft | `la-logistics` | Losses recurring with one driver pair and one night-shift scanner; an unscheduled-stop hotspot; a fictitious carrier taking high-value loads | exists |

### Enterprise

| Use case | Template | Stories | Status |
|---|---|---|---|
| Internal audit, accounts payable | `la-audit` | Vendor V-117 shares bank account and address with employee E-31; 5 vendors on one bank account plus an unlinked sixth; 30 invoices just under the 10 000 limit; one approver bridging the approval graph; intercompany money at quarter ends; 13 duplicate payments | exists |
| Insider threat (HR and security) | `la-insider` | A leaver's bulk reads and mail to a personal domain; access creep granted by one manager; two employees approving each other's expenses | exists |

**Count: 18 Spaces in 9 industries.** On 2026-11-08, 17 exist (`la-sample`, `la-showcase`, `la-mobile-money`,
`la-claims`, `la-audit`, `la-ownership`, `la-police`, `la-utility`, `la-card`, `la-procurement`, `la-trade`, `la-benefits`,
`la-health`, `la-telco-ra`, `la-logistics`, `la-mfs`, `la-insider`) and 1 is planned (`la-crypto`); the `la-claims`
additions land inside `la-claims`. The plan began on 2026-11-07 with 17 (5 existing, 12 new); `la-mfs` was added on
2026-11-08 (operator: Telecom must cover mobile money, MFS and other telco fraud as separate use cases).

## Realism review

Each story is checked against three tests: a real investigator would recognise it; the data that shows it is
data such a team really holds; and it is not trivially visible without Link Analysis.

| Story | Finding | Change made |
|---|---|---|
| Police: burglary series via pawn shops | Real. Second-hand dealers in many countries record seller ID and item serials. But stolen goods rarely all resurface. | Only 40 % of stolen items resurface; serials match on 60 % of those. The vehicle is seen near 11 of 18 scenes, not all. |
| Police: number-plate camera link | Real, but cameras sit on main roads, not at every scene. | Camera reads are on routes within 2 km and 30 minutes of a scene, not at the scene itself. |
| Police: rotating controller phone | Real ("burner" handsets). Handsets usually change every 2–6 weeks; customers keep their numbers. | Rotation every 3–5 weeks; at least 70 % of the customer set carries over to the new handset, so link prediction can join them. |
| Police: drug bases at vulnerable residents' homes | Real ("cuckooing" in the UK). The term is UK-specific. | The story is kept; UI and docs say "drug bases at vulnerable residents' addresses", with the local term in the user guide only. |
| Police: witness who was arrested with the suspect | Real but rare; must be one person, not many. | Kept as one person. |
| Insurance: all existing `la-claims` stories | Already matched to known Special Investigations Unit patterns (shared contact details, staged accidents, an overbilling clinic, claims soon after the policy starts). | None. |
| Insurance: parts-supplier ring | Real (a repairer billing for parts never fitted). | Added, with a benign high-volume supplier as the look-alike. |
| Insurance: ghost broker | Real (sold policies with false details; often one payment account). | Added. |
| Utility: technician-linked tampering | Real, but in practice it is usually contractors or "fixers", not staff technicians, and the drop is gradual. | Story uses a contractor; consumption drops 40–70 % within 2 weeks of a visit, with noise. |
| Utility: transformer energy balance | Real only where transformers are metered (smart grids). | Only 30 % of transformers are metered; both affected ones are metered, so the loss is visible there. |
| Utility: illegal reconnection | Real. | None. |
| Utility: debt-dodging account churn | Real (a new account in a relative's name at the same address). | None. |
| Utility: commercial load on residential tariff | Real; "grow-house" is a known example with a flat round-the-clock load. | Kept; the load profile is flat 24/7 at 3–5× the street median. |
| Utility: prepaid token fraud | Real and common where prepaid meters are used (tokens sold off-system by one vendor). | Added. |
| Card: card testing, account takeover, common point of purchase | Real; card fraud teams hold authorisations, device fingerprints and chargebacks. Point-of-purchase analysis is a standard card-scheme method. | Testing uses small amounts with an 85 % decline rate, not 100 %; compromised cards are defrauded 1–6 weeks after the point of purchase, not at once. |
| Card: colluding merchant | Real ("bust-out" merchants), but refunds alone are weak evidence. | The story pairs refunds to cards never sold to with a chargeback rate 9× the merchant's category. |
| Ownership: layered stakes under the 25 % threshold | Real; reporting thresholds are commonly 25 %. Banks hold registry and KYC data, not the whole world's registries. | Only the companies a customer touches plus 3 layers above them are in the data. |
| Ownership: sanctioned party two hops away | Real (the "50 % rule"). Name matches produce many false positives. | A near-namesake with a different birth year and nationality is the look-alike. |
| Trade: over-invoicing, circular trade, phantom shipment | Real; trade-finance teams hold letters of credit, bills of lading, vessel-position data from vendors and unit-price references. | Mispricing is 3–8× the reference only on 14 shipments; vessel positions are daily, not continuous. |
| Procurement: bid rotation and cover bids | Real (competition-authority guidance). Bid IP addresses are held by e-procurement platforms. | Losing bids are 5–12 % higher, not identical; the IP match covers 60 % of cover bids. |
| Benefits: address farms, deceased payments, undeclared work | Real; agencies match death registers and payroll data. | Death notices arrive 0–60 days late, so a short overpayment is normal (the look-alike). |
| Health: referral kickbacks, phantom billing, pill mill, upcoding | Real (payer program-integrity patterns). Distinct from `la-claims`, which is motor-injury claims. | Upcoding is shown against peers with the same patient mix; the pill mill's patients travel over 50 km. |
| Telecom revenue assurance | SIM box, Wangiri and IRSF are already in `la-showcase`. | `la-telco-ra` takes unbilled usage, dealer commission fraud and PBX hacking instead. |
| MFS: SIM-swap takeover, split deposits, merchant cash-out | Real; MFS fraud teams hold the SIM register (swaps with the staff login and channel), app logins by handset, agent and merchant transactions and commission statements. Stores really do swap many SIMs in upgrade drives, and market agents really do take many small deposits. Distinct from `la-mobile-money`, which is wallet anti-money-laundering. | A takeover drains within 2 hours from a handset the wallet never used, and the customer gets the number back days later; the split deposits show as several cash-ins per visit into wallets the agent registered itself, not as small amounts alone; the colluding merchant is matched on payments within 60 minutes of a cash-in, which many genuine shoppers also make. |
| Logistics: cargo theft | Real; shippers and carriers hold trip, GPS-stop and warehouse-scan data. Fictitious carriers (double brokering) are a known pattern. | GPS is stop-level (place, start, minutes), not every ping; losses are 1 in 400 trips in the background. |
| Insider: leaver exfiltration, access creep, mutual approvals | Real; security and HR teams hold access logs, grants and approvals. Privacy is sensitive. | All people are invented; access logs are daily counts per system, not record-level. |
| Crypto: peel chain, mixer hop, converging cash-out | Real (blockchain-analytics patterns). | Addresses use an invented format (`syn1…`) that cannot match a real chain; the mixer exit is matched by timing and amount with noise, not exactly. |
| Water instead of electricity | Water theft is real but rarer in data; electricity is the stronger demo. | Electricity only for now; water noted as a later variant. |

Candidates are not reviewed yet; each gets this review before it moves to **planned**.

## Distribution and install layout

### Three downloads

| Download | File | Contents | Unzips to |
|---|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` (today's bundle) | The server, UI, embedded Java runtime and the template gallery. **No Spaces, no data.** | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-<id>-<version>.zip` | `<id>/space.toon`, `<id>/config/` (with an empty `egress.toon`) — configuration only; `portal/` stays in the template gallery. | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-<id>-<version>.zip` | `<id>/data/<feed>/inbox/*` (landed files) and `<id>/data/staged/*` (files the user copies in during the demo). | `<parent>/inspecto-la-data/` |

`<parent>` is any folder the user chooses. The three folders are siblings, so the product can be upgraded
by replacing `inspecto-la/` without touching any Space or data.

```
<parent>/
  inspecto-la/             product (replace to upgrade)
    bin/ lib/ ui/ runtime/
    spaces/                  stays empty, apart from the template gallery
  inspecto-la-spaces/      one folder per Space (configuration)
    la-police/  la-claims/  la-utility/
  inspecto-la-data/        one folder per Space (data, then the Space's runtime state)
    la-police/{data/, audit/, duckdb/}
```

### How the server finds them (existing seams)

- **Spaces:** `SPACES_ROOT` (read by `serve.bat` / `serve.sh`, passed as `-Dspaces.root`). Set it to
  `..\inspecto-la-spaces`.
- **Data:** `-Dspaces.data.root=<mount>` sends each Space's `data/`, `audit/` and `duckdb/` to
  `<mount>/<id>` (`SpaceDataRoot`, documented in
  `docs/okf/product/module/config/admin-guide-configuration-reference.md`). Today it can only be passed
  through `INSPECTO_JAVA_OPTS`.
- The data folder becomes the Space's runtime state as soon as the server starts: the Pipelines land the
  inbox files and the Datasets and DuckDB stores are written beside them. Deleting the data folder resets the
  showcase; the Space configuration is untouched.

### Step 1 as built (LA-SHOWCASE-DIST-1, 2026-11-07)

- Launchers: `serve.bat`, `serve.sh`, `serve-demo.*` pass `SPACES_DATA_ROOT` as `-Dspaces.data.root`. No launcher
  change was needed for relative paths: the JVM resolves `spaces.data.root` against its working directory and every
  launcher `cd`s to the bundle root first. Guarded by a new `tools/check-launchers.mjs` scenario.
- Packer: `node tools/package-la-showcase.mjs <template-id>` writes `inspecto-la-space-<id>-<version>.zip` and
  `inspecto-la-data-<id>-<version>.zip` from committed content. The Space zip mirrors
  `SpaceManager.createFromTemplate` (config with `${SPACE}` replaced, `space.toon` from `name`/`tagline`);
  `template.toon` and `portal/` stay in the gallery. A template with no committed `data/` is refused.
- Version check: `space.toon min_product_version`; `SpaceVersionCheck` logs a WARN at boot, the Space still boots.
- Proved live on a Basic `-NoUi` build: product, `la-claims` Space and data unzipped side by side, started with
  `SPACES_ROOT=..\inspecto-la-spaces SPACES_DATA_ROOT=..\inspecto-la-data`. The Space booted, all six Pipelines
  landed their files (520 claims, 0 rejected), all state was written under `inspecto-la-data\la-claims`, the Space
  folder held only `space.toon` and `config\` (this run predates the packer shipping an empty `egress.toon`, so the server seeded one), a restart reused the data
  folder, and `min_product_version: "99.0.0"` produced the WARN. Not checked: the Link Analysis UI on that build.
- ⚠ `la-sample` keeps its files in `data/samples` (for the in-app load-samples option), so its data download lands
  nothing until the user copies them into `data/inbox`. Showcases meant for download keep their files in
  `data/inbox`.

- The Space zip also carries an empty `config/egress.toon`, as `SpaceManager.createFromTemplate` writes for a new
  Space; without it the server would allow the Space's current outbound hosts unreviewed.
- Decided instead of the first draft: the version lives in `space.toon` (no separate `manifest.toon`), and the
  launchers pass the path through unchanged (the JVM resolves it; see above).
- Known limits: renaming a Space in Settings rewrites `space.toon` with its four known fields, dropping
  `min_product_version`; an absolute `SPACES_DATA_ROOT` with spaces in it splits in `serve.bat` (as `SPACES_ROOT`
  already does — the documented relative paths avoid it); `4.1.0-rc1` counts as meeting `4.1.0`.

### Still open for distribution

- **Install guide:** a short admin-guide page per showcase with the three unzip steps and the start command
  (lands with each showcase's hub, plan step 6).

### Edition rule

Every Space has a **Basic tier**: Collectors, Pipelines, Datasets, the link Dataset Job, saved views and saved
Geo Views — nothing that needs sign-in personas. Personas, four-eyes approval, Alert Rules, Investigations, Cases
and the seed script are an **Advanced tier** layered on top, as `la-claims` does today. The Basic tier must load
and be explorable on a Basic build; that is the acceptance test. The Advanced tier ships in the same Space
download and stays inert on Basic.

**The existing four Advanced-only templates** (`la-showcase`, `la-mobile-money`, `la-claims`, `la-audit`, all
`minEdition: advanced` until wave B; `la-claims` flipped to `basic` on 2026-11-08) get the same split: `minEdition` drops to `basic` and anything a Basic build refuses
moves into the Advanced tier. Whether the Advanced tier really is inert on Basic (personas, Alert Rules, seed
scripts present but unused) was **proven on `la-claims` on 2026-11-08** (step 5 as built). One product change came with it
(operator 2026-11-08, "A: allow on Basic"): the template gallery no longer refuses a template for its Demo User roster
(`config/demo-users.toon`) when its `template.toon` says `minEdition: basic`. Without that change the gallery would list
the template on Basic and refuse to create it. A stated `masking_mode` is the other trap: leave it unset (see step 5).

**Alert Rules ship unarmed on Basic** (operator 2026-11-08, "A: allow, unarmed"; found on `la-showcase`, wave D relies on
it for every new Space's Alert Rule). A template whose `template.toon` says `minEdition: basic` may carry Alert Rules
(`config/registry/alert-rules/`, or a deferred `config/pending/alert-rules/`). On a build without Alert Rules
(`alert.dispatch` absent, i.e. Basic) the template gallery (`SpaceManager.missingFeatures`) and `POST /spaces` from the
template (`TemplateSeedGate`) no longer refuse them: the seed gate judges each rule's content only
(`AlertRoutes.parseContent`, so a malformed rule still refuses the create), and the boot leaves each one unarmed with a
WARN ("Not arming alert rule"), as it already did for an unzipped Space download. A template without `minEdition:
basic` is still refused, and a Decision Rule's `create-alert` consequence still counts as needing Alert Rules. Pinned by
`ControlApiSpaceTemplatesTest.aBasicTierTemplateShipsItsAlertRulesUnarmedOnBasic` (falsified on both gates).

### Play, learn, test

- **Play:** each showcase's user guide opens with a five-minute tour from its saved views.
- **Learn:** one exercise per story ("Which garage links the 12 claimants?"), each with a hint and an answer
  that names the saved view and the entities to find. The answers are in the user guide, not in the data, so a
  learner can work blind.
- **Test:** the generator's `ground-truth.json` and golden tests stay in the repository for QA. The user
  guide's answer list is the tester's checklist, including the look-alikes that must NOT appear.
- **One catalogue page** lists every showcase with its industry, edition tier, download names and size:
  `docs/okf/product/solution-pack/la-showcase/user-guide-showcase-catalogue.md`.

## Shared data-generation plan

Applies to every showcase; follows the existing generators (`tools/gen-la-claims-data.py`,
`tools/gen-la-mm-data.py`, `tools/gen-la-audit-data.py`).

- **One generator per Space:** `tools/gen-la-<id>-data.py`, standard library only, fixed seed, writes straight
  into the Space's `data/` (inbox and staged). Running it twice gives byte-identical files.
- **Ground truth file:** the generator also writes `ground-truth.json` (story id → entity ids and link ids),
  kept beside the generator, **not** shipped in the data download.
- **Golden test:** `tools/gen-la-<id>-data.test.mjs` re-runs the generator and checks the planted facts
  (counts, the ring's members, that each benign look-alike is NOT linked to its story). A Java golden test
  (as `LaClaimsGoldenTest`) checks the link Dataset Job's output.
- **Background population first, stories second:** the generator builds a realistic background (distributions
  below), then plants each story by adding or rewiring a small number of records, so stories are a few
  percent of the data, not the bulk.
- **Distributions:** heavy-tailed degrees (most entities have 1–3 links, a few have dozens); daily and weekly
  cycles in timestamps; realistic missing values (3–8 % blank optional fields); a few spelling variants of
  names and addresses so identity resolution has work to do.
- **Synthetic identity:** invented names from a fixed list, phone numbers in a reserved fictitious range,
  invented street names, real cities only at city level. No real people, businesses or plates.
  (`.claude/memory/no-data-or-client-files-in-commits.md`.)
- **Time window:** fixed calendar dates, 12 months landed in `data/<feed>/inbox`, plus the next month in
  `data/staged` for the "new data arrives" step of the demo.
- **Size:** small enough for the Basic explorer on a laptop: 20–60 k link rows per Space, data download
  under 50 MB zipped.

## Per-Space specifications

### 1. `la-police` — criminal investigation (new)

**Audience:** police intelligence analysts and investigators. **Period:** 12 months + 1 staged month.

| Feed | Rows (approx.) | Key columns |
|---|---|---|
| `incident_report` | 4 000 | incident_id, offence_type, start_at, end_at, city, lat, lon, reporting_person_id |
| `person` | 6 000 | person_id, name, birth_year, address_id, role_flags |
| `incident_person` | 9 000 | incident_id, person_id, role (suspect, victim, witness) |
| `vehicle` | 2 500 | plate, make, colour, keeper_person_id |
| `plate_read` | 40 000 | plate, camera_id, read_at, lat, lon |
| `call_record` | 30 000 | caller, callee, start_at, duration_s (from seized or lawfully obtained handsets) |
| `handset` | 500 | number, imei, attributed_person_id (often blank) |
| `custody` | 1 200 | custody_id, person_id, arrested_at, co_arrest_group |
| `dealer_sale` | 1 500 | sale_id, dealer_id, seller_person_id, item_desc, serial, sold_at |
| `property_stolen` | 2 200 | incident_id, item_desc, serial |
| `address` | 3 000 | address_id, street, city, lat, lon |

**Link kinds:** `called`, `co_arrested`, `involved_in` (person–incident, by role), `keeper_of`, `seen_near`
(plate read within 2 km and 30 min of an incident), `lives_at`, `sold_item`, `item_match` (serial match between
stolen property and a dealer sale).

**Stories and look-alikes:**

| Story | Planted facts | Benign look-alike |
|---|---|---|
| Burglary series | 18 burglaries in 3 months across 2 districts; 40 % of the items resurface at 2 dealers through 3 sellers; a vehicle kept by one seller's partner is seen near 11 scenes | A taxi plate seen near 30 scenes across the whole year and city |
| Drug supply network | 1 controller number per period, 4 runner numbers, about 60 customer numbers; the controller handset changes every 3–5 weeks, 70 % of customers carry over | A delivery driver's phone with many short calls but no carry-over pattern |
| Drug bases at vulnerable residents' addresses | 3 addresses with frequent runner calls and visits, residents with no record | A busy shared house with many residents and calls |
| Witness linked to the suspect | One person recorded as witness in 2 unrelated incidents and co-arrested with the suspect of one | A frequent, genuine witness (a shopkeeper) in 6 incidents |

**Saved views:** burglary series (incidents + dealers + vehicle); handset network over time; the witness link.
**Geo Views:** burglary scenes with plate-read routes. **Advanced tier:** personas `police.analyst` (lead),
`police.sio` (second approver), `police.reviewer` (read-only), `police.outsider`; an Alert Rule on new serial
matches; seed script `tools/seed-la-police.mjs` creating the Investigation and a Case.

#### `la-police` Basic tier as built (step 2, 2026-11-07)

- **Template** `spaces/_templates/la-police/` (`minEdition: basic`): eleven Collectors, Pipelines and Datasets
  (`config/police/`), files in `data/inbox/<feed>` (12 months, 2025-11-01..2026-10-31) and `data/staged/<feed>` (November
  2026 for the seven event feeds, including one more serial match by a series seller). The `police_links` Job (cron every
  5 minutes) folds them into one link Dataset of nine kinds, about 25 000 rows; three Jobs fire on its
  `job.dataset.produced` signal and derive one Dataset per saved view: `police_property_links` (the stolen-property
  circle), `police_call_links` (the busy handsets), `police_witness_links` (repeat witnesses). No
  `link-analysis.toon`: masking and the link index are Standard and above.
- **Generator** `tools/gen-la-police-data.py` (seed 20261107, standard library, byte-identical on a re-run) writes the
  data and `tools/la-police-ground-truth.json` (story → entity ids; not in the data download). Coordinates are rounded
  to the 5 decimals the CSV carries before any distance is measured, so the generator's 2 km / 30 minute test and the
  Job's SQL agree at the border.
- **Golden tests:** `tools/gen-la-police-data.test.mjs` (determinism; the planted facts on the files) and
  `LaPoliceLinksJobTest` (inspecto-engine: the real Pipelines land every row with nothing quarantined, the real Jobs run;
  every story's entities form one connected piece of its saved view's Dataset; no look-alike holds a link of any kind
  to a story entity; each saved view's Dataset stays under 4 000 rows).
- **Figures as generated:** series 18 burglaries, 53 items, 21 resurfaced (40 %), 13 serial matches (62 %) on 13
  scenes, the vehicle seen near 11 scenes, so every scene has a match or a read; controllers carry over 75–80 % of
  their customers; the taxi is seen near 54 scenes, none in the series; the delivery driver calls 208 numbers in the
  landed months.
- **Decided while building:**
  - A ninth link kind, `uses_number` (a person to the handset number attributed to them): without it numbers and
    people are two separate graphs and the drug bases cannot reach an address.
  - `incident_report` carries a `district` column for the two-district series.
  - Drug-base "visits" are not modelled (no feed records a visit); the runner calls carry the story.
  - Links with no time of their own (`keeper_of`, `lives_at`, `uses_number`) carry 2025-11-01; `amount` is the
    number of records behind a link.
  - A saved view draws its whole Dataset, and the canvas shows at most 1 000 nodes (`projection_node_cap`) from at
    most 4 000 rows by default (`traversal.rows_default`); past either it says "Result cut short" and a story may be
    off screen. The first handset view (every call pair) had 5 350 rows, then 2 110 nodes; now background handsets
    have fewer contacts (about 2 600 call pairs) and `police_call_links` keeps only the calls of numbers with 6 or
    more call partners (about 740 nodes). The golden test pins both limits for every view.
  - The saved Geo View is incident markers only; plate-read routes need a legs Dataset and wait for step 5. The Geo
    map is Standard and above, so the view is inert on Basic.
- **Live proof on Basic:** recorded at step 2 under Steps.

### 2. `la-claims` — insurance fraud (exists; additions)

The existing template and `tools/gen-la-claims-data.py` stay. Changes:

- **Split** into the Space and Data downloads described above.
- **Parts-supplier ring:** one parts supplier on invoices from 9 garages; for 30 claims the parts invoiced are
  not in the garage's fitted-parts list. New feed `repair_invoice` (invoice_id, claim_id, garage_id,
  supplier_id, part_code, fitted). New link kind `supplied_parts`. Look-alike: a large legitimate supplier on
  many garages whose parts are all fitted.
- **Ghost broker:** 25 policies sold through one broker code with addresses that do not exist in the address
  list, premiums paid from 2 bank accounts. New column `payer_account` on `policy`; new link kind `paid_by`.
  Look-alike: a family paying several policies from one account at a real address.
- Extend `LaClaimsGoldenTest` and `gen-la-claims-data.test.mjs` with both stories.

#### `la-claims` additions as built (step 4, 2026-11-07)

- **Generator:** `tools/gen-la-claims-data.py` draws stories 8 and 9 from a second seeded Random (`20261107`) after
  everything else, so every row of stories 1–7 is byte-identical; `policy` gains two columns at the end (its first five
  are unchanged) and 25 rows. Seven feeds, about 300 kB.
- **Parts-supplier ring:** `SUP-07` invoices 61 parts (£25 906) on 30 claims across all nine garages, and none is fitted;
  none of the 30 is a staged-ring claim. Look-alike `SUP-01`: all nine garages, 218 claims, every part fitted. Only 3 other
  claims carry an unfitted part (returns).
- **Ghost broker:** 25 policies `POL-0381`..`POL-0405` through `BRK-07` (January–July 2026, premiums £219–£333 against £380 or
  more for every genuine policy) to holders on no claim, on four streets found on no other record, paid from `BA-93000017`
  (13) and `BA-93000029` (12). Look-alike: story 7's family pays its four policies from `BA-71000001` at the address on its claims.
- **Job and views:** `claims_links` reads `repair_invoice` and `policy` too; nine kinds, 2 637 rows (`supplied_parts` 34,
  `paid_by` 29). Saved views `parts_supplier_ring` and `ghost_broker_premium_payers`; `claimant_ring_shared_identity` excludes
  `supplied_parts`.
- **Decided while building:**
  - `policy` also gains `holder_address`: "an address that does not exist" needs an address on the policy. There is no
    address register, so "does not exist" means a street found on no claim and no other policy.
  - `repair_invoice` carries an `amount` per part, so a `supplied_parts` link carries the value never fitted (0 when all fitted).
  - `paid_by` links only an account that pays two or more policies, with no upper cap. One account per policy would add 380
    leaves to the graph.
  - The ring's garage `GAR-03` buys only from `SUP-01`: a small supplier shared by `GAR-03` and one other garage joined the
    ring's Louvain community (the golden test caught it). Each small supplier serves two of the eight ordinary garages.
  - Seeding `CL-0134` and expanding twice now also reaches `SUP-01` and `SUP-07` through `GAR-03` (about 23 entities).
    The runbook's Working Set figures from 2026-10-24 predate this.
  - `minEdition` stays `advanced`; the Basic tier is wave B.
- **Tests:** `LaClaimsGoldenTest` (12, with all whole-graph figures of stories 1–7 unchanged over the nine-kind graph),
  `LaClaimsLinksJobTest` (real Pipelines and SQL, per-kind rows, unfitted value per supplier, `paid_by` accounts),
  `LaClaimsTemplateTest` (seven feeds, five views), `tools/gen-la-claims-data.test.mjs` (determinism, both stories on the files).
- **Hub:** the `la-claims` user guide has one exercise and answer per story; the runbook lists stories 8 and 9.

### 3. `la-utility` — electricity revenue protection (new)

**Audience:** utility revenue-protection and loss-reduction teams. **Period:** 12 months of daily reads +
1 staged month.

| Feed | Rows (approx.) | Key columns |
|---|---|---|
| `premise` | 8 000 | premise_id, address, city, lat, lon, tariff (residential, commercial), meter_type (smart, prepaid), transformer_id |
| `customer_account` | 9 500 | account_id, premise_id, holder_name, opened_at, closed_at, bank_account, arrears |
| `meter_read` | 8 000 × 365 (daily kWh; shipped as 12 monthly files) | premise_id, read_date, kwh, tamper_flag |
| `transformer_read` | 60 metered transformers × 365 | transformer_id, read_date, kwh_supplied |
| `field_visit` | 6 000 | visit_id, premise_id, contractor_id, visit_at, visit_type |
| `service_order` | 5 000 | order_id, premise_id, order_type (connect, disconnect, reconnect, meter_swap), closed_by, closed_at |
| `prepaid_vend` | 50 000 | vend_id, premise_id, vendor_id, amount, kwh, vend_at |
| `payment` | 60 000 | account_id, paid_at, amount, bank_account |

The link Dataset uses aggregated `meter_read`, so link rows stay inside the 20–60 k target.

**Link kinds:** `supplied_by` (premise → transformer), `account_at`, `paid_from` (account → bank account),
`visited_by` (premise → contractor), `order_closed_by`, `vended_by` (premise → vendor).

**Stories and look-alikes:**

| Story | Planted facts | Benign look-alike |
|---|---|---|
| Meter tampering | 22 premises under 2 metered transformers drop 40–70 % within 2 weeks of a visit by contractor C-14; transformer supply stays flat, so loss rises | A holiday home whose use drops every winter, with no visit |
| Illegal reconnection | 15 premises disconnected for arrears keep consuming; their reconnect orders were closed by C-14's colleague C-22 without payment | Premises reconnected after a genuine payment |
| Debt-dodging account churn | 12 accounts closed with arrears and reopened within 30 days at the same premise under another name, same bank account | A genuine change of tenant (new bank account) |
| Commercial load on a residential tariff | 6 residential premises with a flat 24/7 load at 3–5× the street median, all paid from one account | A home with a heat pump and electric car (high but cyclical load) |
| Prepaid token fraud | Vendor V-08 sells tokens to 40 prepaid premises whose kWh credited exceeds the money received, mostly at night | A large supermarket vendor with high but consistent sales |

**Saved views:** contractor C-14 network; churn by bank account; vendor V-08. **Geo Views:** loss by
transformer area. **Advanced tier:** personas `rp.analyst`, `rp.manager`, `rp.reviewer`, `rp.outsider`; an Alert
Rule on a transformer's loss going over 15 %; seed script `tools/seed-la-utility.mjs`.

#### `la-utility` Basic tier as built (step 3, 2026-11-07)

- **Template** `spaces/_templates/la-utility/` (`minEdition: basic`): eight Collectors, Pipelines and Datasets, files in
  `data/inbox/<feed>` (12 months, 2025-10-01..2026-09-30; `meter_read` and `transformer_read` as one file per month,
  the other feeds as one extract) and `data/staged/<feed>` (October 2026 for the six event feeds). Two `sql.template`
  Jobs fire on `pipeline.commit`: `utility_links` folds the feeds into one link Dataset of the six specced kinds, one
  row per pair (4 301 rows), with an extra `kwh` column; `transformer_loss` balances each metered transformer against
  its premises' meter reads per month (144 rows, `loss_band` over 15 % / over 9 % / normal). A third Job,
  `utility_bank_links` (on `utility_links`' `job.dataset.produced`), keeps the account links of bank accounts shared by
  three or more accounts and of premises that changed holder (441 rows, 531 nodes) for the churn view. Three saved
  views: `meter_tampering_contractor` and `prepaid_token_fraud_vendor` filter the link Dataset by kind,
  `account_churn_by_bank_account` reads `utility_bank_links`; one
  saved Geo View (`utility_transformer_loss_map`), inert on Basic. No `link-analysis.toon`. Ships
  `config/examples/sla-escalation-examples.md` like every template (`TemplateGovernanceExamplesTest`).
- **Generator** `tools/gen-la-utility-data.py` (seed 20261107, standard library, byte-identical on a re-run) writes the
  data and `tools/gen-la-utility-data.ground-truth.json` (story → entity ids and look-alike ids; not in the data
  download).
- **Golden tests:** `tools/gen-la-utility-data.test.mjs` (determinism; each story's facts in the files, e.g. the 40–70 %
  drop after the C-14 visit, reads of zero for a genuine disconnection) and `UtilityLinksJobGoldenTest` (inspecto-engine:
  the real Pipelines land every row with nothing quarantined, the real Jobs run; every story's entities form one
  connected piece of the link Dataset; no look-alike holds a link to a story entity; loss goes over 15 % on TX-007 and
  TX-019 only). A mutant ground truth (a premise C-14 really visited listed as a look-alike) turns the test red.
- **Figures as generated:** 1 000 premises on 40 transformers, 12 of them metered; 1 112 accounts, 365 000 meter
  reads, 551 visits, 473 service orders, 10 577 token sales, 7 368 payments landed. Loss on TX-007 goes from 6.2 % to
  17.9 %, on TX-019 from 5.1 % to 24.7 %; the other ten metered transformers stay at or under 6.7 %.
- **Deviations from the spec above:**
  - **1 000 premises instead of 8 000** (feeds scaled with them). Daily reads of 8 000 premises would put about 80 MB of
    CSV in git; 1 000 keep the committed corpus at about 12 MB (the data download is 2.1 MB zipped). In consequence the
    link Dataset has about 4.3 k rows, not 20–60 k, and the story premises are 89 of 1 000 (about 9 %), not "a few
    percent".
  - **Churn** is 6 premises that each closed two accounts with arrears and reopened within 30 days (12 closed
    accounts), all 18 accounts on one bank account, so the story is one connected group, not 12 separate pairs.
  - C-22 is tied to C-14 by three joint visits at tampered premises ("C-14's colleague").
  - `tamper_flag` is VARCHAR (0, 1 or blank): a declared INTEGER lands as VARCHAR anyway.
- **Canvas cap (fixed 2026-11-08):** `account_churn_by_bank_account` first filtered the whole link Dataset to `account_at` and
  `paid_from`: 1 954 rows but 2 932 nodes, over the 1 000-node canvas cap. It now reads the derived
  `utility_bank_links` (441 rows, 531 nodes): the two hub bank accounts and every premise that changed holder, so the
  genuine tenant changes still sit beside the churn as its look-alike. The heat-pump look-alike (one account, its own
  bank account) is not in that view; it is in `meter_read`. `UtilityLinksJobGoldenTest` now checks every saved view
  against both limits (under 4 000 rows and 1 000 nodes) and that each shows its stories' entities; the old view fails
  it at 2 932 nodes. The other two views project 679 and 310 nodes.
- **Missed last landing (worked around 2026-11-08):** `utility_links` and `transformer_loss` also run on
  `cron: "*/5 * * * *"`, as `la-card` does, because a `pipeline.commit` that lands while a run is in flight is skipped,
  not queued (`JOB-SIGNAL-OVERLAP-SKIP-1`).
- **Known gaps (open):**
  - The prepaid look-alike V-01 sells in another city, so its "no look-alike joins" check cannot fail.
  - Look-alikes are kept free of direct links only; some sit two hops from a story through a shared contractor.
- **Live proof on Basic:** recorded at step 3 under Steps.

### Specs 4–14: the wave C Spaces

These follow the same rules as specs 1–3: the shared data-generation plan, a Basic tier first, 20–60 k link rows,
12 landed months plus 1 staged month. Row counts are approximate. The Advanced tier of each is personas
`<id>.analyst`, `<id>.manager`, `<id>.reviewer`, `<id>.outsider`, one Alert Rule (named per Space) and a seed
script `tools/seed-la-<id>.mjs`. Stories are listed as **story: planted facts — look-alike**.

#### 4. `la-card` — card and e-commerce fraud

**Audience:** card fraud operations at an issuer or payment service provider.
**Feeds:**
- `cardholder` (5 000): holder_id, name, email, phone, address;
- `card` (6 500): card_id, bin, holder_id, issued_at;
- `merchant` (900): merchant_id, name, mcc, city, acquirer;
- `authorization` (150 000, aggregated per card and merchant and day for links): auth_id, card_id, merchant_id,
  amount, auth_at, device_id, ip, result;
- `chargeback` (1 800): auth_id, reason, filed_at;
- `online_account` (4 000): account_id, email, phone, device_id, ship_to_address, changed_at.

**Link kinds:** `used_at` (card → merchant), `used_device`, `from_ip`, `account_card`, `ships_to`, `charged_back`.

**Stories:**
- **Card testing:** one device and a /24 IP range try 300 cards with 1.00 authorisations at 3 small merchants,
  85 % declined — a corporate proxy IP shared by many genuine cardholders.
- **Account-takeover ring:** 8 devices sign in to 140 accounts and change the ship-to address to 5 drop
  addresses — a family sharing a tablet across 4 accounts.
- **Colluding merchant:** M-221 refunds 12 cards it never sold to; its chargebacks are 9× its category — a travel
  merchant with a chargeback spike in one strike month.
- **Common point of purchase:** 60 cards defrauded 1–6 weeks after using one fuel-station terminal in a 3-week
  window — a busy supermarket used by many cards with no later fraud.

**Alert Rule:** a new card seen at the compromised terminal.

**Basic tier as built (2026-11-07, plan step 7).** `tools/gen-la-card-data.py` (seed 20261107; ground truth
`tools/gen-la-card-data.ground-truth.json`), `tools/gen-la-card-data.test.mjs`, `CardLinksJobGoldenTest` (inspecto-engine),
template `spaces/_templates/la-card/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-card/`. Deliberate deviations: the feed is `card_auth`, not `authorization` (a SQL
keyword); `merchant` gains `channel` (pos/ecom) and `chargeback` a `chargeback_id`; `online_account` is a change log that
also carries `card_id` (the card on file, for `account_card`); scale is 5 030 cardholders, 6 500 cards, 900 merchants,
4 000 accounts, about 77 000 authorisations and 690 chargebacks (3 MB of CSV, about 38 600 link rows) rather than
150 000 and 1 800; links are one row
per pair, not per day; two link kinds were added, `refunded` (the colluding merchant's refunds, `via` = sales there) and
`visited_before_fraud` (each defrauded card to the merchants it paid in the 45 days before), which is the common-point
view; `from_ip` links a card to its /24 network; `card_links` also runs on `cron: "*/5 * * * *"` because a commit landing
while it runs is skipped (`JOB-SIGNAL-OVERLAP-SKIP-1`). **Proved live 2026-11-08** on a Basic `-NoUi` build with the product,
Space and data downloads unzipped side by side (`SPACES_ROOT=..\inspecto-la-spaces SPACES_DATA_ROOT=..\inspecto-la-data`):
17 landings, 0 rejected; after the cron run 38 598 links; through `POST /inv/projection/neighbors` with each saved view's
own filter every story is reachable from its hub (300 tested cards, 140 taken-over accounts, M-221's 35 cards, M-317's 60)
and no look-alike neighbours a story member; the staged October month landed with 0 rejected. Not checked: the Link
Analysis UI on that build.

#### 5. `la-ownership` — beneficial ownership and sanctions

**Audience:** KYC and sanctions compliance teams at a bank.
**Feeds:**
- `company` (6 000): company_id, name, jurisdiction, incorporated_at, reg_address;
- `person` (5 000): person_id, name, birth_year, nationality;
- `ownership` (9 000): owner_id (person or company), owned_id, percent;
- `officer` (8 000): company_id, person_id, role;
- `customer` (1 500): customer_id, company_id, onboarded_at;
- `payment` (40 000): from_customer, to_company, amount, paid_at;
- `sanctions_list` (400, invented): list_id, name, aliases, birth_year, nationality.

**Link kinds:** `owns` (with percent), `officer_of`, `customer_is`, `paid`, `name_match` (fuzzy match to the list,
with a score).

**Stories:**
- **Hidden owner:** a 5-layer chain across 3 jurisdictions, each stake 24 %; one person ends up owning 61 % of
  4 customers — a listed company with widely held shares.
- **Sanctions two hops away:** a customer's 50 % owner is owned by a listed person — a near-namesake of a
  listed person with a different birth year and nationality.
- **Nominee director:** one person directs 120 companies at one formation-agent address — an accountant who
  directs 4 family firms.
- **Circular ownership:** A owns B owns C owns A, with payments going round — a group with a clear parent at
  the top.

**Alert Rule:** a new ownership path that reaches a listed person within 3 hops.

**Basic tier BUILT 2026-11-07** (hub `okf/product/solution-pack/la-ownership/`, `OwnershipLinksJobGoldenTest`,
`tools/gen-la-ownership-data.test.mjs`). As built, where it differs from the spec above:
- **`name_match` uses the engine, not the generator.** The pipeline `similarity` step compares two columns of one
  row, so it cannot match a register against a list; the `ownership_links` Job calls DuckDB `jaro_winkler_similarity`
  directly (the same function the step uses) on lower-case letters of the name and every alias, threshold
  `min_name_score` 0.90, AND the same birth year AND nationality. The generator re-runs that rule on its corpus and
  refuses to write if anything but the planted pair matches.
- **61 % is 24 + 24 + 13.** Stakes of 24 % cannot multiply up to 61 %, so every stake on the 5-layer chain is 24 % and
  three vehicles at its foot hold 24 + 24 + 13 % of each of the 4 customers. The sanctioned path is customer ← 50 %
  owner ← 60 % by the matched person.
- **Columns added:** `payment.payment_id` (one row per payment) and `sanctions_list.listed_at` (the link's time).
  `birth_year` is VARCHAR (a declared INTEGER lands VARCHAR under DIRECT).
- **Scale:** 8 807 stakes, 8 085 officers, 40 061 landed payments, 44 321 link rows; data 1.8 MB unzipped.
  Background directorships are capped at 12 so nobody but the nominee stands out; background company ownership is
  acyclic, so the planted circle is the only one.
- **Staged month:** October payments plus 8 newly onboarded customers.

**Advanced tier BUILT 2026-11-09:** see step 8, "`la-ownership` BUILT 2026-11-09".

#### 6. `la-trade` — trade-based money laundering

**Audience:** trade-finance compliance at a bank.
**Feeds:**
- `company` (2 500): company_id, name, country, reg_address, incorporated_at;
- `director` (3 500): company_id, person_id;
- `trade` (12 000, letters of credit and collections): trade_id, applicant, beneficiary, hs_code, qty,
  unit_price, value, port_load, port_discharge, vessel, ship_date;
- `bill_of_lading` (12 000): bl_id, trade_id, vessel, container, shipper, consignee, port_from, port_to, date;
- `vessel_position` (300 vessels × 365, daily): vessel, date, port_or_area;
- `price_reference` (400 HS codes × 12): hs_code, month, median_unit_price;
- `payment` (30 000): from_account, to_account, amount, date.

**Link kinds:** `traded_with`, `shipped_in` (trade → container), `on_vessel`, `directed_by`, `registered_at`,
`paid`.

**Stories:**
- **Over-invoicing:** 14 shipments of one HS code at 3–8× the reference price between 2 companies sharing a
  director — a specialty supplier whose high prices are consistent for years.
- **Circular trade:** the same 6 containers go A → B → C → A in 4 months, the value rising each leg — a
  container-leasing firm's boxes moving between many unrelated shippers.
- **Phantom shipment:** 5 letters of credit paid on bills of lading naming a vessel that was elsewhere on that
  date — a transhipment where a second, real vessel carries the goods on.
- **Shell importers:** 6 importers registered within 2 months at one formation-agent address, all paying one
  exporter — long-standing companies at a large business-centre address with different directors.

**Alert Rule:** a unit price over 3× the reference.

**Basic tier BUILT 2026-11-07** (hub `okf/product/solution-pack/la-trade/`, `TradeLinksJobGoldenTest`,
`tools/gen-la-trade-data.test.mjs`). As built, where it differs from the spec above:
- **The phantom-shipment check is engine SQL, not a precomputed flag.** The `trade_links` Job LEFT JOINs each bill of
  lading to `vessel_position` on vessel and `pos_date = bl_date` and flags `vessel_elsewhere` when the vessel was in
  another port or at sea. Checking each B/L against its own vessel lets a transhipment's two legs pass; a check of the
  trade's named vessel on the ship date would flag all five look-alikes. Price ratio, relayed boxes and shared
  addresses are SQL too; the generator only re-runs every rule and refuses to write if a flag fires off-story.
- **A real world, not random positions:** 300 vessels on 30 liner services sail their loops day by day over 25 invented
  ports in 24 real countries; every genuine B/L is dated on a day its vessel is in its port of loading.
- **Trades are not nodes.** `shipped_in` and `on_vessel` run from the shipper, not the trade (trade nodes would need a
  seventh kind to reach a company and push the links past 60 000). Columns added: `company.account_id`,
  `trade.instrument`, `payment.payment_id`, `payment.trade_ref`, a `flag` column on the link Dataset.
- **62 real HS codes**, not 400, with invented prices. Staged month: October 2026 for the five monthly feeds.
- **One derived Dataset per saved view** (as `la-police`), fired on `job.dataset.produced`: 23, 90, 102 and 199 rows,
  each with its story's look-alike beside it. The link Job also has a 5-minute cron (`JOB-SIGNAL-OVERLAP-SKIP-1`).
- **SQL guard gotcha:** the word `from` inside a string literal of a `sql.template` Job is read as a FROM clause naming a
  file and the Job is refused; keep it out of `via` text.
- **Scale:** 2 500 companies, 3 514 directors, 11 896 landed trades, 11 955 B/Ls, 109 500 positions, 28 993 payments,
  49 964 link rows; data 11 MB unzipped.
- **Live proof on Basic (2026-11-07):** a Basic build with the Link Analysis UI (`package.ps1 -Edition Basic -Ui la-app`),
  `node tools/package-la-showcase.mjs la-trade` (39 Space files, 67 data files, data zip 1.9 MB). Product, Space and data
  were unzipped side by side and started with `SPACES_ROOT=..\inspecto-la-spaces SPACES_DATA_ROOT=..\inspecto-la-data`
  and nothing else. All seven Pipelines landed 62 files in 62 batches with 0 rejected files or rows. `pipeline.commit`
  fired `trade_links`, whose last run built 49 964 links, and its signal fired the four view Jobs (23, 90, 102 and 199
  rows). All state was written under `inspecto-la-data\la-trade`; the Space folder held only `space.toon` and `config\`.
  In the UI the four saved views are listed and draw without "Result cut short" (24, 112, 75 and 141 nodes). Over each
  view's `POST /inv/projection` (not truncated), every story's entities are present and form one connected piece
  (3 of 3, 4 of 4, 9 of 9, 10 of 10), every look-alike is present (9, 10, 6, 15), and no look-alike links to its story.
  Not checked: the staged month on the live build (the verification review computed the views as 23/98/102/209 rows
  with October landed, still under the cap).

#### 7. `la-procurement` — bid rigging and procurement fraud

**Audience:** public-procurement integrity units and internal audit.
**Feeds:**
- `tender` (1 200): tender_id, buyer_unit, category, estimate, published_at, awarded_to, award_value;
- `bid` (6 000): tender_id, bidder_id, amount, submitted_at, ip, doc_author;
- `company` (900): company_id, name, reg_address, phone, bank_account, incorporated_at, debarred_at;
- `director` (1 400): company_id, person_id, from, to;
- `person` (2 000): person_id, name, address_id;
- `official` (300): person_id, buyer_unit;
- `contract_change` (800): tender_id, change_value, approved_by, approved_at.

**Link kinds:** `bid_on`, `won`, `directed_by`, `same_ip`, `same_author`, `approved_change`, `lives_at`.

**Stories:**
- **Bid rotation:** 4 firms win 24 tenders in one category in turn; losing bids are 5–12 % higher; 2 of the
  firms share a director — a concentrated market where 3 large firms win often but bid independently.
- **Cover bids:** losing bids submitted from the winner's IP address within minutes, with the same document
  author — bids sent from a buyer's public help-desk kiosk.
- **Conflict of interest:** an official approves 9 contract changes for a firm whose director lives at the
  official's address — an official approving many changes across unrelated suppliers.
- **Phoenix firm:** a debarred firm's directors run a new firm that wins within 3 months — a director who joins
  an unrelated firm years later.

**Alert Rule:** a bid from an IP address already used by another bidder on the same tender.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-procurement-data.py` (seed 20261108; ground truth
`tools/gen-la-procurement-data.ground-truth.json`), `tools/gen-la-procurement-data.test.mjs`, `ProcurementLinksJobGoldenTest`
(inspecto-engine), template `spaces/_templates/la-procurement/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-procurement/`. Deliberate deviations:
- **Columns:** `director.from` / `to` are `from_date` / `to_date` (SQL keywords); `bid` gains `bid_id`, `contract_change` gains
  `change_id`, `tender` gains `awarded_at`; `director` has no role. A tender still open at the end of the staged month has a blank
  award.
- **Link kinds:** `same_ip` and `same_author` link a bidder to the shared IP address or author node (only when two or more bidders
  used it), not bidder to bidder; `same_ip`'s `via` carries the fewest minutes to another bidder's bid from that address on the same
  tender. A blank or placeholder author (`Administrator`, about 3 % of bids) never links. `approved_change` is official → winning
  firm, one row per pair (`via` = changes and tenders). The link Dataset has an extra column, `market` (the tender category, or the
  category a company mostly bids in), so the rotation view can filter to one market.
- **Views and the canvas cap:** two derived Jobs on `job.dataset.produced` (as `la-police`): `procurement_approval_links`
  (approvals, plus only the homes shared by two or more of those officials and directors) and `procurement_debarment_links`
  (directors of debarred firms, all their directorships, their firms' wins). `procurement_links` also runs on
  `cron: "*/5 * * * *"` (`JOB-SIGNAL-OVERLAP-SKIP-1`). The golden test reads each view with its own filter and pins under 4 000 rows
  and 1 000 nodes, the story whole and connected, and the look-alike on the canvas: `bid_rotation` 1 291 rows, 512 nodes; `official_approving_relative_firm` 700 rows, 556 nodes; `cover_bids_shared_ip` 20 rows, 19 nodes; `debarred_firm_reborn` 27 rows, 36 nodes.
- **Stories as built:** rotation tenders are Highways North (24, two a month), the look-alike big three win 74 % of Highways South
  in the same market view; cover bids are 27 on 10 IT tenders, 16 (60 %) from the winner's IP 2-25 minutes after it, 20 with its
  estimator as author; the kiosk is `198.51.100.20` (14 firms, bids on one tender at least an hour apart); the conflict is
  `P-00002` → `CO-0426` (9 changes; director `P-00003` at home `AD-00002`), the look-alike `P-00008` approves 34 changes (33 landed, 1 staged), at
  most 2 per supplier; the phoenix is `CO-0815` (debarred 2026-01-15) → `CO-0899` (incorporated 2026-02-02, wins `T-00519` on
  2026-04-08), the look-alike director `P-00007` joins `CO-0233` 3.5 years after `CO-0640`'s debarment.
- **Scale:** 900 companies, 2 000 people (300 officials), 1 445 directorships, 1 228 landed tenders, 6 001 landed bids, 834
  contract changes; about 11 000 link rows (the spec's row counts give far fewer than the shared plan's 20-60 k); data 0.5 MB.
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing.

#### 8. `la-benefits` — benefits and tax fraud

**Audience:** government benefits-integrity and tax-compliance teams.
**Feeds:**
- `person` (12 000): person_id, name, birth_year, address_id, phone;
- `claim` (9 000): claim_id, person_id, benefit_type, start, end, monthly_amount, bank_account;
- `household` (6 000): address_id, declared_members;
- `death_register` (300): person_id, died_on, notified_on;
- `payroll` (40 000): employer_id, person_id, period, wages;
- `tax_return` (8 000): return_id, taxpayer_id, preparer_id, refund, bank_account, filed_at;
- `address` (6 500): address_id, street, city, lat, lon.

**Link kinds:** `claims`, `lives_at`, `paid_into`, `employed_by`, `prepared_by`, `refund_to`.

**Stories:**
- **Address farm:** 14 claimants under 5 surnames at one address, each declaring a single-person household,
  paid into 3 accounts — a care home with many genuine residents and their own accounts.
- **Payments to the deceased:** 9 dead claimants are still paid into accounts that also receive 3 other claims
  — a death notified 40 days late with the overpayment returned.
- **Undeclared work:** 20 claimants on unemployment benefit appear on 2 employers' payroll — permitted
  part-time earnings under the limit.
- **Tax-preparer ring:** preparer P-12 files 80 returns with inflated refunds paid to 6 accounts the preparer
  controls — a large preparer whose refunds go to each client's own account.

**Alert Rule:** a payment to an account linked to a death-register entry.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-benefits-data.py` (seed 20261109; ground truth
`tools/gen-la-benefits-data.ground-truth.json`), `tools/gen-la-benefits-data.test.mjs`, `BenefitsLinksJobGoldenTest`
(inspecto-engine), template `spaces/_templates/la-benefits/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-benefits/`. Deliberate deviations:
- **Columns:** `claim.start` / `end` are `start_date` / `end_date` (`end` is an SQL keyword); `claim` gains `overpaid_recovered` (the
  look-alike's recovered overpayment); `household` is one row per declaration (`household_id`, `address_id`, `declared_by`,
  `declared_members`), so an address can carry 14 declarations of one; `person.phone` is not a link kind (every phone is unique).
- **Link kinds:** `claims` is person → claim and `paid_into` is claim → account, so a claim is a node; `prepared_by` and `refund_to`
  start at the taxpayer (one return each), not the return. Evidence is in `via`: `lives_at` carries residents, claimants and
  household declarations at the address; `paid_into` carries `died:`, `paid_after_death_days:` (`ongoing` while still paid) and
  `recovered:` for a claimant on the death register; `employed_by` carries `jobseeker_months:` and `over_limit_months:` (wages above
  200 a month, this showcase's permitted-earnings figure).
- **Views and the canvas cap:** one derived Job, `benefits_screen_links` (on `job.dataset.produced`), writes the four integrity
  screens into one Dataset with a `screen` column; each saved view filters one screen. Screens: addresses with 8 or more claimants;
  accounts paid a claim 30 or more days after a death (or still paid); payroll while on Jobseeker benefit; the clients of preparers
  with 60 or more returns plus refund accounts shared by 3 or more taxpayers. `benefits_links` also runs on `cron: "*/5 * * * *"`
  (`JOB-SIGNAL-OVERLAP-SKIP-1`). The golden test reads each view with its own filter and pins under 4 000 rows and 1 000 nodes, the
  story whole and connected, and the look-alike on the canvas: `address_farm` 366 rows, 320 nodes;
  `paid_after_death` 132 rows, 148 nodes; `undeclared_work` 166 rows, 208 nodes;
  `tax_preparer_refund_ring` 592 rows, 520 nodes.
- **Stories as built:** the farm is `AD-03141` (14 claimants, 5 surnames, 14 declarations of one, accounts `SYN-57707034`,
  `SYN-19642862`, `SYN-47766627`); the look-alikes are care homes `AD-00412`, `AD-02718`, `AD-05077` (31, 24, 27 residents, one
  declaration each). The deceased are 9 pensioners who died 2025-11..2026-04, notified within 14 days, pensions never stopped, into
  3 accounts that also take the Housing, Disability and Carer claims of `CIT-08276`; the look-alike `CIT-05289` died 2026-02-09, was
  notified 40 days late, the claim stopped 2 days later (`paid_after_death_days:42`) and the overpayment was recovered. Undeclared work
  is 20 jobseekers on `EMP-0217` and `EMP-0388` (4 on both) at 1 100-2 000 a month for 6-12 months; the look-alike `CIT-02003` earns
  under 200 a month at `EMP-0441` for 11 months, beside 30 more permitted earners and 30 people paid in the month their claim closed.
  The ring is `P-12`, 80 returns filed 2026-01-06..02-27 with refunds of 1 800-3 400 into 6 accounts; the look-alike `P-03` has 250
  clients (244 landed), each refunded into their own or a joint account. About 1 % of honest refunds are 2 500-5 000, so refund size
  alone does not find the ring.
- **Background deaths:** notices arrive 0-60 days after the death (60 % within a week); the claim stops 0-5 days after the notice and
  85 % of overpayments are recovered in full. A death notified in the staged month is never a current claimant: the staged month
  carries new claims only, not updates to landed ones; as generated, the staged death-register file is empty.
- **Scale:** 12 000 people at 5 245 occupied addresses of 6 500, 7 534 landed claims (spec: 9 000; the working-age claim rates were
  kept realistic rather than raised), 300 deaths, 36 479 landed payroll rows (39 537 with October), 8 000 returns (7 736 landed),
  5 258 household declarations (spec: 6 000; one per household). About 39 000 link rows; data 3.4 MB.
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing through the
  Pipelines (the screens were recomputed over landed plus staged files outside the engine: stories unchanged).

#### 9. `la-health` — provider billing fraud

**Audience:** health-insurer and public-payer program-integrity teams.
**Feeds:**
- `provider` (1 500): provider_id, specialty, address, bank_account;
- `provider_owner` (1 600): provider_id, owner_person_id;
- `member` (20 000): member_id, birth_year, address_id, died_on;
- `claim_line` (200 000, aggregated per provider and member and month for links): claim_id, provider_id,
  member_id, service_date, procedure_code, level, billed, paid;
- `admission` (3 000): member_id, hospital_id, from, to;
- `referral` (15 000): from_provider, to_provider, member_id, date;
- `prescription` (30 000): prescriber_id, pharmacy_id, member_id, drug_class, qty, paid_cash.

**Link kinds:** `treated`, `referred_to`, `owned_by`, `prescribed`, `filled_at`, `lives_at`.

**Stories:**
- **Referral kickbacks:** 2 general practitioners send 85 % of their referrals to one imaging centre owned by
  one doctor's spouse — a practice referring to the only hospital nearby.
- **Phantom billing:** a clinic bills 40 members on dates they were in hospital elsewhere or after death —
  date errors corrected within a week.
- **Pill mill:** one prescriber and 2 pharmacies; strong opioids for 70 patients who travel over 50 km and pay
  cash — an oncology practice with high opioid volume for local patients.
- **Upcoding:** 3 clinics under one owner bill the highest visit level 4× their peers with the same patient mix
  — a specialist clinic whose patients really are more complex.

**Alert Rule:** a service billed during an admission or after death.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-health-data.py` (seed 20261110; ground truth
`tools/gen-la-health-data.ground-truth.json`), `tools/gen-la-health-data.test.mjs`, `HealthLinksJobGoldenTest`
(inspecto-engine), template `spaces/_templates/la-health/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-health/`. Deliberate deviations:
- **Feeds and columns:** two more feeds, `address` (street, city, lat, lon: members' homes, premises, owners' addresses) and
  `owner` (owner_id, name, `owner_type` person or organisation, address_id), because some owners are organisations (hospital
  trusts, two pharmacy groups, a laboratory company); so `provider_owner.owner_person_id` is `owner_id`. `provider` gains
  `name` and has `address_id` for `address`; `admission.from` / `to` are `from_date` / `to_date` (SQL keywords);
  `claim_line` gains `received_on` and `reverses` (a reversal is its own line with negative amounts, which is how the
  look-alike's date errors are corrected); `referral` gains `referral_id`; `prescription` gains `prescription_id` and
  `filled_on`, and `paid_cash` is `Y` / `N`. `level` is 1-5 on office visits (`OFV`) only. Procedure codes are invented
  (`OFV`, `SPV`, `PHY`, `LAB`, `IMG-XR` / `IMG-CT` / `IMG-MR`, `INP`), not a real code set.
- **Link kinds:** one row per pair, not per provider, member and month (that would break the one-row-per-pair rule and the
  link budget). `treated` is provider → member with the evidence in `via` (`in_admission:`, `after_death:` open lines,
  `corrected:` reversed ones, `corrected_within_days:`, `admitted_at:`, `died:`); a hospital stay is not a link (a hospital
  node would join unrelated patients in the phantom view), it is read into `in_admission:`. `lives_at` is an owner who is a
  person → home only; members' homes stay attributes (20 000 one-member links add nothing and would bust the budget). The
  upcoding evidence rides on `owned_by` (`office_visits:`, `top_level_share:`, `all_providers_share:`,
  `same_age_mix_share:` - the level-5 share expected from the patients' age bands, ages counted in 2026 -
  `ratio_to_same_age_mix:`, `patients_80_plus:`). `prescribed` / `filled_at` carry `distance_km:` (haversine in the SQL
  from the address positions) and the strong-opioid and cash counts.
- **Views and the canvas cap:** one derived Job, `health_screen_links` (on `job.dataset.produced`), writes four screens with a
  `screen` column (la-benefits style). `referral_concentration` keeps only each concentrated practice's top link (60 % or
  more of 20 or more referrals): its other referrals go to the same city specialists the seaside practices use for
  specialties, which would join the look-alike to the story. `health_links` also runs on `cron: "*/5 * * * *"`
  (`JOB-SIGNAL-OVERLAP-SKIP-1`). The golden test reads each view with its own filter and pins under 4 000 rows and 1 000
  nodes, the story whole and connected, and the look-alike on the canvas but in another component:
  `referral_kickbacks` 22 rows, 23 nodes; `phantom_billing` 70 rows, 87 nodes; `pill_mill` 385
  rows, 149 nodes; `upcoding_clinics` 8 rows, 12 nodes.
- **Stories as built:** kickbacks: `PR-1426` Brackenmoor Surgery (68 of 79 referrals) and `PR-1392` Kilnworth Medical
  Practice (182 of 216) to `PR-1170` Emberlea Imaging Centre, York; its owner `OW-0434` shares home `AD-05150` with
  `OW-1270`, owner of `PR-1426`; look-alike `PR-0828` (38 of 44 to `PR-1129` Saltmere Bay Hospital, Scarborough's only
  hospital, owned by the trust `OW-0263`), beside six more Scarborough practices (shares 0.68-0.82). Phantom billing:
  `PR-1241` Sedgefold Physiotherapy, Hull, 89 open session lines for 28 patients in another hospital and 70 for 12
  patients who died 2026-01..05; look-alike `PR-0632` Willowmere Physiotherapy, Leeds, 15 patients with one line each in a
  stay, reversed in 2-6 days, plus 15 single corrected errors at other providers. Pill mill: `PR-1150` Flintcross Pain
  Clinic, Sheffield, 70 patients 61-127 km away (York, Hull, Lincoln, Scarborough), 514 scripts averaging 135 tablets
  (background median 56), 462 cash, at `PR-1060` and `PR-0594`; look-alike `PR-0031` Rowanbrook Oncology Centre, Leeds,
  60 patients within 12 km, 5 cash scripts of 360. Upcoding: `PR-0173`, `PR-1117`, `PR-1251` (urgent care in Hull,
  Lincoln, York) under `OW-0441`, level-5 share 24-29 % against 6-7 % expected (ratio 3.8-4.1); look-alike `PR-0277`
  Aldermoor Elderly Care Clinic, Lincoln, 28 % against 25 % expected (ratio 1.14), patients all over 80. The upcoding
  screen threshold is 1.75x all providers (not 2x) with 150 or more visits, so the three clinics clear it with margin
  while no background practice does.
- **Scale:** 20 000 members, 1 500 providers, 1 307 owners (1 585 ownership rows), about 81 000 landed claim lines (spec:
  200 000; kept lower to hold the data near 10 MB and the links under 60 000), 2 796 landed stays (spec: 3 000), 14 408
  landed referrals, 29 702 landed prescriptions. About 55 000 link rows; data 10 MB. The staged month adds lines, stays,
  referrals and prescriptions only (no member, provider or owner re-extract), and nothing in it bills inside a stay or
  after a death.
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing through the
  Pipelines (the screens were recomputed over landed plus staged files outside the engine with DuckDB: stories and
  look-alikes unchanged, `upcoding_clinics` gains a few elderly-care clinics).

#### 10. `la-telco-ra` — telecom revenue assurance

**Audience:** revenue-assurance and fraud teams at a mobile operator. SIM box, Wangiri and IRSF stay in
`la-showcase`.
**Feeds:**
- `subscriber` (15 000): msisdn, imsi, dealer_id, activated_at, plan, activation_device;
- `dealer` (200): dealer_id, name, city;
- `network_usage` (daily per subscriber, from switch records): msisdn, date, minutes, mb;
- `billing_record` (daily per subscriber): msisdn, date, billed_minutes, billed_mb;
- `topup` (60 000): msisdn, amount, at, channel;
- `enterprise_trunk` (150): trunk_id, customer_id;
- `trunk_call` (aggregated per trunk, destination and day): trunk_id, destination_country, minutes, start_hour.

**Link kinds:** `activated_by` (subscriber → dealer), `activated_on` (subscriber → device), `topped_up`,
`trunk_of`, `called_country`.

**Stories:**
- **Unbilled usage:** 300 subscribers with network usage and no billing record, all activated by one dealer on
  one day — internal test SIMs on a known list.
- **Dealer commission fraud:** a dealer activates 400 SIMs on 3 devices; each makes one top-up and goes dormant
  — a dealer near a university whose term-start spike stays active.
- **PBX hacking:** 4 enterprise trunks call premium numbers in 3 countries at night and at weekends — a firm
  with a real overseas office calling during that office's working hours.

**Alert Rule:** usage without billing over 1 day for a subscriber.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-telco-ra-data.py` (seed 20261110; ground truth
`tools/gen-la-telco-ra-data.ground-truth.json`), `tools/gen-la-telco-ra-data.test.mjs`, `TelcoRaLinksJobGoldenTest`
(inspecto-engine), template `spaces/_templates/la-telco-ra/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-telco-ra/`. The non-LA `spaces/_templates/telco-ra` is a separate template and is untouched.
Deliberate deviations:
- **Scale:** 7 500 subscribers (spec: 15 000), 200 dealers (`DL-000` is the operator's network test team), 357 dealer devices, 369
  points of sale, 150 trunks of 120 customers. `network_usage` and `billing_record` are **monthly** per subscriber (spec: daily),
  so the two feeds are about 67 000 and 65 000 landed rows, not millions; top-ups are about 30 000 landed (spec: 60 000, scaled
  with the subscribers); `trunk_call` is about 14 000 trunk-days. About 21 000 link rows; data 7.3 MB (the largest showcase so far).
- **Columns:** `network_usage` and `billing_record` carry `period` (the first of the month) and `billing_record` `billed_minutes` /
  `billed_mb`; `topup.at` is `topped_up_at` (`AT` is SQL) and `topup` gains `pos_id` (the retail point of sale, blank for app and
  card); `trunk_call` gains `call_date` and `number_type` (`geographic`, `mobile`, `premium`); `dealer` has no other column.
- **Link kinds:** `activated_by`, `activated_on`, `topped_up` (subscriber → point of sale, retail only), `trunk_of`,
  `called_country`. A destination node is a country and number type (`CTRY-LT-PRN`), not the bare country, so a firm calling a
  country's ordinary numbers never touches the premium-rate story. Evidence is in `via`: `activated_by` carries plan, device,
  `topups:`, `first_topup_days:`, `usage_months:`, `unbilled_months:` (usage months with no billing record), usage and billed
  minutes; `called_country` carries `off_hours_minutes:` (start before 07:00 or from 21:00, or a weekend), `night_minutes:`,
  `weekend_minutes:`.
- **Views and the canvas cap:** one derived Job, `telco_ra_screen_links` (on `job.dataset.produced`, la-benefits style), writes the
  three screens into one Dataset with a `screen` column: `unbilled_usage` (2 or more unbilled months), `commission_spike` (SIMs
  activated and topped up in a dealer-week of 50 or more) and `pbx_off_hours` (300 or more off-hours minutes to one destination).
  `telco_ra_links` also runs on `cron: "*/5 * * * *"` (`JOB-SIGNAL-OVERLAP-SKIP-1`). The golden test reads each view with its own
  filter and pins under 4 000 rows and 1 000 nodes, the story whole and connected, and the look-alike on the canvas but in another
  component: `unbilled_usage` 704 rows, 380 nodes; `dealer_commission_fraud` 2 404 rows, 768 nodes;
  `pbx_hacking_off_hours` 20 rows, 19 nodes.
- **Stories as built:** unbilled usage is `DL-047` (Manchester), 300 SIMs on `DEV-00086` on 2026-03-17, 800-2 500 minutes and
  20-60 GB in each full month from April (less in March), no billing record and no top-up; the look-alike is `DL-000`'s 40 SIMs on `DEV-00001`/`DEV-00002`
  with plan `Internal test` (the known list), plus 12 ordinary SIMs whose first two months reached billing late (`unbilled_months:2`)
  and 50 single dropped subscriber-months (`unbilled_months:1`, below the screen). Commission fraud is `DL-112` (Leeds), 400 SIMs on
  `DEV-00204`..`00206` over the six weeks from 2026-02-02, each topped up once with 5.00 at its shop `POS-0008` within 2 days, one
  month of a few test minutes, then nothing; the look-alike `DL-163` (Sheffield) activates 350 SIMs on 2025-10-01..10-12 whose
  students top up every 20-40 days (app or ten campus shops) and use them every month. PBX hacking is `TRK-025`, `TRK-040`,
  `TRK-087`, `TRK-147` (customers `ENT-0087`, `-0040`, `-0057`, `-0045`) calling premium ranges in LT, SK and SI on six weekends
  2026-04-03..06-22, every minute off hours; the look-alike `TRK-027` (`ENT-0108`) calls `CTRY-SG-GEO` on working days at
  01:00-03:00 on most days, 07:00-08:00 on the rest (that office's day), and `TRK-017`, `TRK-082` call `CTRY-US-GEO` in the evening. Background trunks call premium
  ranges only in IE and NL, in daytime.
- **Identifiers:** MSISDNs `9996…` (the unassigned +999 range, as la-showcase), IMSIs `00101…` (the 001-01 test network), dealer
  names invented, real cities at city level, countries only as destinations. No dealer-name spelling variants: nothing here
  resolves identity by name.
- **Staged month:** October adds 137 activations and a month of usage, billing, top-ups and trunk calls, and never updates a
  landed fact; the stories stay as they are (the 300 SIMs stay unbilled, the 400 stay dormant, no new attack).
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing through the
  Pipelines (the screens were recomputed over landed plus staged files outside the engine: no story changes).

**Advanced tier BUILT 2026-11-09:** see step 8, "`la-telco-ra` BUILT 2026-11-09".

#### 11. `la-logistics` — cargo theft

**Audience:** logistics security teams and cargo insurers.
**Feeds:**
- `shipment` (25 000): shipment_id, shipper, consignee, goods, value;
- `trip` (25 000): trip_id, shipment_id, driver_id, co_driver_id, vehicle, carrier_id, route, depart_at, arrive_at;
- `driver` (900): driver_id, name, phone, carrier_id;
- `carrier` (120): carrier_id, name, phone, registered_at, insured;
- `stop` (90 000, stop-level GPS): trip_id, place_id, start_at, minutes;
- `warehouse_scan` (100 000): shipment_id, warehouse_id, scan_type, at, employee_id;
- `loss_report` (80): shipment_id, reported_at, type.

**Link kinds:** `drove`, `co_drove`, `stopped_at`, `scanned_by`, `carried_by`, `lost`, `same_phone`.

**Stories:**
- **Inside job:** 12 losses share one driver pair, and one night-shift scanner is on 10 of them — a high-volume
  driver on the busiest route with 2 losses in a year.
- **Stop hotspot:** 9 high-value trips stop over 20 minutes at one unofficial lay-by before a loss — the
  official rest stop many trucks use.
- **Fictitious carrier:** a carrier registered 3 weeks ago, sharing a phone number with a known thief, takes
  5 high-value loads that never arrive — a new, insured carrier that delivers.

**Alert Rule:** a high-value trip assigned to a carrier under 60 days old.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-logistics-data.py` (seed 20261111; ground truth
`tools/gen-la-logistics-data.ground-truth.json`), `tools/gen-la-logistics-data.test.mjs`, `LogisticsLinksJobGoldenTest`
(inspecto-engine), template `spaces/_templates/la-logistics/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-logistics/`. No Geo View (the stories read from the graph; `place` carries `lat` / `lon` for one
later). Deliberate deviations:
- **Scale:** 14 000 trips and shipments over 13 months, 12 872 landed (spec: 25 000), 900 drivers, 120 carriers, 12 warehouses, 133
  places on the road; about 13 400 landed GPS stops (spec: 90 000) and 46 000 landed scans (spec: 100 000); 63 landed loss reports
  (spec: 80). The link budget set the scale: one `drove`, `carried_by` and `scanned_by` per trip is already 3 rows a trip, so 25 000
  trips would pass 60 000 link rows. About 45 000 link rows; data 5.9 MB.
- **Feeds and columns:** a ninth source, `place` (place_id, name, `place_type` warehouse / services / truck_park / fuel_station /
  layby, `area` = nearest city, lat, lon), names the warehouses (`WH-`) and the stop places (`PL-`). `shipment` gains `booked_at`;
  `driver` gains `left_on`; `warehouse_scan.at` is `scanned_at` and `loss_report.type` is `loss_type` (SQL keywords); `loss_report`
  gains `loss_id`. `trip.route` is `<origin city>-<destination city>`; `arrive_at` is blank when the truck never got there. Scan types
  are `inbound`, `putaway`, `outbound` (loaded onto the truck) and `arrived`. Phones are in the UK drama ranges, written three ways
  (`07700 900123`, `07700900123`, `+44 7700 900123`) so `same_phone` has to normalise them.
- **Link kinds:** one row per pair. `scanned_by` is trip → the employee of the **outbound** scan only (every scan would add
  about 30 000 rows; the others are read into `arrived:`). `stopped_at` keeps stops of **20 minutes or more** only (shorter ones are
  fuel and traffic), with the place's long stops, high-value long stops and lost trips in `via`. `lost` is trip → loss report
  (`LR-`). `same_phone` is carrier → driver or driver → driver (a < b) after keeping digits and turning a leading 44 into 0;
  background: 14 owner-operators share their driver's phone, and 3 pairs of drivers share one. High value is 100 000 or more.
- **Views and the canvas cap:** one derived Job, `logistics_screen_links` (on `job.dataset.produced`, la-benefits style), writes
  three screens: `inside_job` (drivers, co-drivers and outbound scanners on 2 or more lost trips), `stop_hotspot` (places where 3 or
  more lost trips stood 20 minutes or more, with every long stop there) and `new_carrier` (high-value trips of carriers under 60
  days old, with `same_phone` and the shared-phone driver's lost trips). `logistics_links` also runs on `cron: "*/5 * * * *"`
  (`JOB-SIGNAL-OVERLAP-SKIP-1`). The golden test reads each view with its own filter and pins under 4 000 rows and 1 000 nodes,
  the story whole and connected, and the look-alike on the canvas but in another component: `driver_team_inside_job` 88 rows, 79
  nodes; `theft_stop_hotspot` 333 rows, 335 nodes; `fictitious_carrier` 26 rows, 30 nodes.
- **Stories as built:** inside job: the team `DRV-0892` / `DRV-0893` (`CAR-052`) drove 12 short-delivered loads in 107 landed trips
  (2025-10-10..2026-08-20); `EMP-0123`, night shift at `WH-06` (Nottingham), scanned 10 of them out between 00:50 and 03:30 and no
  other lost load. Look-alike `DRV-0894` (`CAR-103`): 318 landed trips, about 300 on Southampton-Birmingham, 2 thefts. The generator
  keeps every other driver to at most one loss and the look-alike's scanners off the team's losses. Stop hotspot: `PL-118` Lay-by at
  Rowley Gap (near Birmingham, beside 14 roads of 2.5 hours or more): 82 long stops, 9 of them high value, all 9 stolen there
  (26-69 minutes, last GPS stop, 2025-10-24..2026-06-01, nine drivers); background high-value loads never take their break there.
  Look-alike `PL-087` Whitecross Services (London-Birmingham road): 239 long stops, 26 high value, 3 stolen (forced, so the
  services reach the screen). Fictitious carrier: `CAR-022` Larkmoor Freight Services Ltd, registered 2026-05-11, uninsured, 5
  high-value loads 2026-06-05..06-11 (24-30 days old), no GPS, `non_delivery`; its phone `+44 7700 900374` is `DRV-0895`'s
  `07700 900374` (`CAR-020`, left 2025-11-30 after the theft `LR-0006`). Look-alike `CAR-057`, an insured owner-operator
  registered 2026-03-02 (phone = its driver `DRV-0001`'s), 9 high-value loads in its first 60 days and every one of its 58 delivered.
- **Staged month:** October adds 1 128 trips with shipments, stops and scans and 3 background losses; no carrier or driver
  re-extract; the stories stay as they are (the screens recomputed over landed plus staged with DuckDB: no story changes).
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing through the
  Pipelines (only recomputed outside the engine).
- **Names:** the saved views follow the use-case naming of `2026-11-08-la-industry-spaces-plan.md` (D5, approved 2026-11-08): no
  Space or industry prefix. The template id `la-logistics` is the one this spec gives; D2 of that plan gives the same id to the
  *Logistics* industry Space, so the operator decides whether that Space is this template or takes another id.

#### 12. `la-insider` — insider threat

**Audience:** security, HR and internal investigations. All people and systems are invented.
**Feeds:**
- `employee` (3 000): employee_id, dept, role, manager_id, hired_at, notice_given_at, left_at;
- `access_grant` (12 000): employee_id, system, granted_by, granted_at, ticket_id;
- `access_daily` (daily counts): employee_id, system, date, records_read;
- `email_external` (aggregated per employee, domain and day): employee_id, domain, messages, attachment_mb;
- `expense` (20 000): expense_id, requester_id, approver_id, amount, submitted_at;
- `vendor_change` (600): vendor_id, field, changed_by, changed_at.

**Link kinds:** `reports_to`, `granted_by`, `accessed`, `mailed_domain`, `approved_for`, `changed_vendor`.

**Stories:**
- **Leaver exfiltration:** an employee who gave notice reads 20× their normal customer records in 3 weeks and
  mails 1.2 GB to a personal domain — a migration engineer with a ticketed bulk read.
- **Access creep:** manager M-7 grants payroll and treasury access to 4 reports outside their role, with no
  ticket; one of them later edits a vendor's bank details — a role change with an HR record and a ticket.
- **Mutual approvals:** 2 employees approve each other's expenses 40 times, just under the limit, mostly at
  weekends — a team lead approving the team's expenses in the normal way.

**Alert Rule:** a grant without a ticket to a finance system.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-insider-data.py` (seed 20261112; ground truth
`tools/gen-la-insider-data.ground-truth.json`), `tools/gen-la-insider-data.test.mjs`, `InsiderLinksJobGoldenTest`
(inspecto-engine), template `spaces/_templates/la-insider/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-insider/`. No Geo View (nothing here has a place). Deliberate deviations:
- **Privacy, as the realism review requires:** people are ids only (`EMP-`), with no names at all; `access_daily` holds one count
  per employee, system and day (a day with no reads has no row), never a record; `email_external` holds one row per employee,
  domain and day. Systems are generic upper-case names (`CRM`, `CUSTARCH`, `HRIS`, `AP`, `PAYROLL`, `TREASURY`, `ERP`, `DWH`,
  `CODE`, `CONTRACTS`, `MAIL`, `WIKI`, `TICKETS`); vendors are `VEN-` ids; every mail domain ends in `.example`.
- **Scale:** 3 000 employees, about 12 200 landed grants, 106 000 landed daily counts (only the record-holding systems `AP`,
  `CRM`, `CUSTARCH`, `HRIS`, `PAYROLL`, `TREASURY` are logged), 28 700 mail-days, 18 100 landed expenses (about 19 700 with
  October; spec: 20 000), 569 landed vendor changes (600 with October). About 26 000 link rows; data 6.8 MB.
- **Feeds and columns:** `employee` gains `role_since` (the HR record of a role change the access-creep look-alike needs);
  `access_daily.date` is `read_date` and `email_external` gains `sent_date` (the day it aggregates); `vendor_change.field` is
  `field_name` and it gains `change_id`. The access-grant and vendor-change extracts are one landed file plus one staged file;
  the other three are monthly. The finance systems are `AP`, `PAYROLL`, `TREASURY`; the personal webmail domains are
  `inboxly.example`, `mailnest.example`, `postmail.example` (a list in the Job SQL); the single-approver expense limit is 500.
- **Link kinds:** one row per pair. `granted_by` is grantee → approver with the grant counts in `via` (`no_ticket:`,
  `finance_after_onboarding:` = finance grants more than 30 days after the hire date, `finance_no_ticket:`, last finance grant
  and ticket). `accessed` is employee → `SYS-<system>` for every system held, with the daily median and the largest 21-day
  average of daily reads over windows of 5 read days or more (`peak_ratio:`). `approved_for` carries `near_limit:` (450 up to
  500), `weekend:` and `approved_back:`; `changed_vendor` carries `bank_changes:` and `vendor_editors:`. Every grant in the
  background is approved by the employee's own manager, so `granted_by` pairs match `reports_to` pairs (2 999 each).
- **Views and the canvas cap:** one derived Job, `insider_screen_links` (on `job.dataset.produced`), writes three screens:
  `leaver_exfiltration` (`accessed` with `peak_ratio:` 10 or more, plus that person's `mailed_domain` links of 100 MB or more),
  `access_creep` (`granted_by` with a finance grant after onboarding, plus those employees' bank-detail vendor changes) and
  `mutual_approvals` (`approved_for` approved back, or 20 approvals or more). Systems are nodes, so a view that kept every read of
  a spiking person would bridge the look-alike to the story through `SYS-MAIL` and the like: each screen keeps only the links
  that carry its evidence. `insider_links` also runs on `cron: "*/5 * * * *"`. The golden test reads each view with its own
  filter and pins under 4 000 rows and 1 000 nodes, the story whole and connected, and the look-alike on the canvas but in
  another component: `leaver_exfiltration` 3 rows, 5 nodes; `access_creep` 18 rows, 26 nodes; `mutual_approvals` 101 rows, 129
  nodes.
- **Stories as built:** leaver exfiltration: `EMP-0031`, customer account specialist, notice 2026-06-01, left 2026-06-30; daily
  median 43 in `SYS-CRM`, 860-960 on all 15 working days 2026-06-02..06-22 (`peak_ratio:20.0`); 1 228.8 MB to `postmail.example`
  on 9 of those days (3 small mails there earlier in the year). Look-alike `EMP-0386`, data migration engineer: median 25 in
  `SYS-CUSTARCH`, 500-600 on 2026-04-06..04-24 (`peak_ratio:22.1`) after a grant under `CHG-163782` on 2026-04-02; no notice, no
  CRM grant, no personal or large mail. Access creep: `EMP-0007` (operations manager; the spec's M-7) granted `PAYROLL` and
  `TREASURY` with no ticket to `EMP-0482`, `EMP-0636`, `EMP-1059`, `EMP-1415` (operations roles) on 2026-01-12..02-10, the only 8
  finance grants without a ticket; `EMP-0482` changed the bank account of `VEN-0264` on 2026-05-14, the only person ever to touch
  that vendor (everywhere else only accounts payable changes vendors). Look-alike `EMP-0062`, payroll officer since 2026-03-02
  (hired 2013), granted `PAYROLL` by the payroll manager `EMP-1802` on 2026-03-03 under `REQ-163776`; 12 finance staff picking
  up a second finance system from their own manager, ticketed, share the view. Mutual approvals: account managers `EMP-0022` and
  `EMP-0040` (different teams) approved each other 21 + 19 times, 455-498 against the 500 limit, 32 at weekends, 2025-10-21..
  2026-08-29; the only pair in the company that approves back. Look-alike `EMP-1613`, sales team lead of 20 field sales
  representatives: about 520 approvals, 19 reports at 20 or more, weekdays, 3 near the limit, never approved back.
- **Staged month:** October adds about 9 600 daily counts, 3 100 mail-days, 1 560 expenses, 22 grants and 31 vendor changes;
  no HR re-extract; the screens recomputed over landed plus staged with DuckDB show the same stories.
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing through the
  Pipelines (only recomputed outside the engine).
- **Names:** the saved views follow the use-case naming of `2026-11-08-la-industry-spaces-plan.md` (D5): no Space or industry
  prefix; the *Enterprise* table there lists them.

#### 13. `la-crypto` — crypto laundering

**Audience:** compliance at a virtual-asset service provider, and investigators. All addresses use the invented
`syn1…` format.
**Feeds:**
- `wallet` (30 000): address, first_seen, label (exchange, mixer, merchant, unknown);
- `transfer` (150 000, aggregated per address pair and day for links): tx_id, from, to, amount, asset, at;
- `cluster_hint` (30 000): address, cluster_id (common-input heuristic);
- `exchange_account` (5 000): account_id, kyc_person_id, deposit_address;
- `kyc_person` (5 000): person_id, name, birth_year, country;
- `cashout` (12 000): account_id, bank_account, amount, at.

**Link kinds:** `sent_to`, `same_cluster`, `deposit_of`, `kyc_of`, `cashed_out_to`.

**Stories:**
- **Peel chain:** a hacked wallet peels small amounts over 80 hops to 6 exchange deposit addresses — a payroll
  wallet paying many staff each month.
- **Mixer hop:** funds go through a mixer and come out within 2 hours as amounts within 2 % of the input — mixer
  users whose amounts and timing do not match.
- **Converging cash-out:** 40 KYC accounts at 2 exchanges, with unrelated names, deposit from one cluster and cash
  out to 3 bank accounts — an over-the-counter desk whose many customers deposit their own funds.

**Alert Rule:** a deposit whose funds trace to a labelled hack within 5 hops.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-crypto-data.py` (seed 20261113; ground truth
`tools/gen-la-crypto-data.ground-truth.json`), `tools/gen-la-crypto-data.test.mjs`, `CryptoLinksJobGoldenTest` (inspecto-engine),
template `spaces/_templates/la-crypto/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub
`docs/okf/product/solution-pack/la-crypto/`. Addresses are `syn1` + 26 characters of a bech32-like alphabet; the assets `SYC` and
`SUSD`, the five exchanges (Larkmoor Digital, Fernhill Coin, Ashgrove, Pebblebrook, Wrenfield), the mixer, the company, the people
and the bank accounts (`BK-` + 8 digits) are invented. Deliberate deviations:
- **Scale:** 10 875 landed addresses (spec: 30 000), 44 559 landed transfers (spec: 150 000), 2 722 hinted addresses (spec:
  30 000: only addresses spent with another get a hint), 3 154 exchange accounts and 3 021 KYC persons (spec: 5 000 each), 4 635
  landed cash-outs (spec: 12 000). With every address pair a link row the spec's sizes would pass 100 000 links; these give about
  39 500 link rows and 4.7 MB of data.
- **Columns:** `from`, `to` and `at` are SQL keywords, so `transfer.from_address`, `to_address`, `tx_at`; `cashout` gains
  `cashout_id` and its time is `cashout_at`; `exchange_account` gains `exchange` and `opened_at`. The `wallet.label` set gains
  `hacked` (reported stolen from), which the hack story and the wave D Alert Rule need. Each address pair carries one asset: SUSD for
  merchant payments and merchants' weekly sweeps, SYC for everything else. No balances are kept (exchange hot wallets and the mixer
  pool pay out without a modelled inflow; deposit addresses are not swept).
- **Link kinds:** the five specified (`sent_to` address → address, `same_cluster` address → `CL-`, `deposit_of` deposit address →
  `XA-`, `kyc_of` `XA-` → `KP-`, `cashed_out_to` `XA-` → `BK-`) plus a sixth, **`mixer_exit`**: the timing-and-amount match is
  worked out by the Job, not recorded, so it is its own link (input address → exit recipient, every exit within 6 hours and 90-100 %
  of an input; `matched:yes` within 120 minutes and at least 98 %). Evidence in `via`: `sent_to` carries `src_hop` / `dst_hop` (the
  peel shape: exactly one transfer in, two out to two addresses, both within 24 hours), `same_cluster` carries `cluster_exchange`,
  `cashed_out_to` carries `accounts_to_this_bank`.
- **Views and the canvas cap:** one derived Job, `crypto_screen_links` (on `job.dataset.produced`), writes three screens.
  `peel_chain`: every `sent_to` link in or out of a peel-shaped address chained to another, plus the exchange accounts such a hop
  paid and their KYC persons. `mixer_hop`: every `mixer_exit` link, and for matched exits the deposit, account and person.
  `converging_cash_out`: clusters with no exchange-labelled address that paid 10 or more exchange accounts, with those payments,
  accounts, persons and cash-out bank accounts. `crypto_links` also runs on `cron: "*/5 * * * *"` (`JOB-SIGNAL-OVERLAP-SKIP-1`). The
  golden test reads each view with its own filter and pins under 4 000 rows and 1 000 nodes, the story whole and connected, and the
  look-alike on the canvas but in another component: `hack_peel_chain` 785 rows, 426 nodes (the 80-hop chain is one thin component
  of 100 nodes); `mixer_hop_timing_match` 12 rows, 15 nodes; `converging_cash_out` 670 rows, 397 nodes.
- **Stories as built:** peel chain: the wallet labelled hacked `syn15z47fslrxrnhuwm4dppk77s0z7` loses 412.5 SYC (of 551 SYC it was sent) on 2026-03-14
  02:17 to the first of 80 hops; each hop sends a peel of 1.6-4.9 SYC to one of six deposit addresses and the change on, 12-140
  minutes apart, until 2026-03-18; the last change address keeps 170.6 SYC; the six accounts `XA-03111`..`XA-03116` (four exchanges,
  opened February-March 2026, persons `KP-02973`..`KP-02978` with no shared surname) cash out to their own banks. Look-alike: the
  company payroll wallet `syn1g6x78slgxx5zne6cwpmes8j554` (label `merchant`), whose month-end run of 25 hops pays the same 25 staff
  wallets and returns the rest (300 landed hops). Mixer hop: the ransom address `syn1g2sua43rg5kfcjunpv6chu7rws` (seven payments,
  about 36 SYC, April 2026) pays 11.8, 9.65 and 8.9 SYC into fresh mixer deposit addresses on 05-14, 05-21 and 06-02; each comes out
  53-67 minutes later at 98.2-98.9 % to a fresh address that deposits it all at `XA-03118` (Ashgrove Exchange, `KP-02980`).
  Look-alikes: `syn1zngjgrnpdxuwp8trqf4pddkppf` (another user's exit 75 minutes later at 92.5 %) and
  `syn1sfr9e266n86z4c8zd7k00q4uph` (99.1 % but 281 minutes later), both `matched:no`; background exits never match by
  construction (the generator moves a chance match 3 hours later). Converging cash-out: cluster `CL-01125` (30 addresses, fed by
  about 200 payments from 150 people February-June) pays the 40 accounts `XA-03119`..`XA-03158` (22 Fernhill Coin, 18
  Pebblebrook; 40 names, persons `KP-02981`..`KP-03020`), which cash out to `BK-49804167`, `BK-77665088` and `BK-95284540`.
  Look-alike: the over-the-counter desk `CL-01126` (8 addresses) pays 60 customers' own accounts, which cash out to 54 banks of their
  own. The generator also moves the last coins of any background address that would chain with another peel-shaped address, so the
  peel screen holds exactly the story and the payroll.
- **Staged month:** October adds 3 935 transfers, 275 addresses, a month of cash-outs and one more payroll run (the peel view grows
  to about 451 nodes); no story activity.
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing through the
  Pipelines (checked outside the engine only).
- **Names:** the saved views follow the use-case naming of `2026-11-08-la-industry-spaces-plan.md` (D5): `hack_peel_chain`,
  `mixer_hop_timing_match`, `converging_cash_out`, added to its *Banking and finance* table.

#### 14. `la-mfs` — mobile financial services fraud (added 2026-11-08)

**Audience:** fraud and risk teams at a mobile operator's mobile financial services (MFS) business, and at the
agent networks and merchants it serves. **Not the same as `la-mobile-money`**, which covers wallet
anti-money-laundering (structuring, mule chains, a device farm); this Space covers fraud *against* the MFS
business and its customers. All data is invented. It shares no data, names or ids with any customer MFS Space.
**Feeds:**
- `wallet` (20 000): wallet_id, msisdn, kyc_level, opened_at, registered_by_agent;
- `agent` (600): agent_id, name, area, super_agent_id, float_account;
- `merchant` (800): merchant_id, name, category, opened_at, settlement_account;
- `wallet_txn` (200 000, aggregated per pair and day for links): txn_id, from_id, to_id, type (cash_in,
  cash_out, p2p, merchant_pay), amount, at, device_id;
- `sim_event` (8 000): msisdn, event (sim_swap, port_out), at, channel (store, call_centre), staff_login;
- `device_login` (60 000): wallet_id, device_id, at;
- `agent_commission` (600 × 12): agent_id, month, transactions, commission.

**Link kinds:** `registered_by` (wallet → agent), `paid` (wallet → wallet or merchant), `cashed_in_at`,
`cashed_out_at`, `logged_in_on` (wallet → device), `swapped_by` (msisdn → staff login), `settles_to`.

**Stories:**
- **SIM-swap wallet takeover:** 30 subscribers have their SIM swapped through one call-centre login (`S-41`) in
  10 days; each wallet is logged into from a new device and drained within 2 hours to 5 mule wallets, which cash
  out at 2 agents — a busy store that performs many genuine SIM swaps for customers who keep their balances.
- **Agent split-deposit commission fraud:** agent `A-52` splits customers' cash-ins into many small deposits across
  120 wallets it registered itself, earning a commission on each; the wallets are rarely used for anything else —
  a busy market agent with many small cash-ins from distinct customers who also pay merchants and each other.
- **Merchant cash-out collusion:** merchant `MR-77`, opened 3 weeks ago, receives "payments" from 60 wallets
  minutes after each was funded at one of 3 agents, and settles every day to one bank account — a popular grocery
  with steady payments from many wallets over months.

**Alert Rule:** a wallet drained within 2 hours of a SIM swap.

**Basic tier as built (2026-11-08, plan step 7).** `tools/gen-la-mfs-data.py` (seed 20261111; ground truth
`tools/gen-la-mfs-data.ground-truth.json`), `tools/gen-la-mfs-data.test.mjs`, `MfsLinksJobGoldenTest` (inspecto-engine), template
`spaces/_templates/la-mfs/` (`minEdition: basic`; in `offerings/basic.toon` contentPacks), hub `docs/okf/product/solution-pack/la-mfs/`.
`la-mobile-money` and `mobile-money` are separate templates and are untouched; `la-mfs` shares no ids, names or data with them
(wallets `WL-`, agents `A-`, merchants `MR-`, handsets `HS-`, staff logins `S-`, bank accounts `BA-`; MSISDNs `9997…`). Deliberate
deviations:
- **Scale:** 8 000 wallets (spec: 20 000), 300 agents under 12 super agents (spec: 600), 400 merchants (spec: 800), about 101 000
  landed transactions (spec: 200 000), 1 146 SIM events (spec: 8 000), about 69 000 logins (spec: 60 000), 3 600 commission rows. With
  every pair a link row the spec's sizes would pass 100 000 links; these give about 47 000 link rows and 10.9 MB of data.
- **Columns:** `at` is a SQL keyword, so `wallet_txn.txn_at`, `sim_event.event_at` and `device_login.login_at`. `wallet_txn` gains
  type `settlement` (a merchant's sweep to its bank account), which is how daily settlement shows; `device_id` is blank on a cash-in
  (the agent's till starts it) and on a settlement. `device_login` is one row per wallet, handset and day (the day's first login).
  `agent_commission.month` is the first of the month; the commission is 0.40 a cash-in or cash-out up to 50, 0.80 up to 200, 1.50
  above, so splitting a deposit pays. Ids are zero-padded (`A-052`, `MR-077`; the spec's `A-52`, `MR-77`); `S-41` is as specified.
- **Link kinds:** as specified, with `swapped_by` from the **wallet** on that MSISDN to the staff login (an MSISDN node would need a
  wallet-to-number link to reach the money), and port-outs kept as rows, not links. Evidence in `via`: `registered_by` carries the
  wallet's cash-ins, those at its registrar, cash-outs, P2P and merchant payments; `paid` carries `within_60m_of_cash_in:`;
  `cashed_in_at` carries `per_day:`, `average:` and `merchant_pay_within_60m:`; `swapped_by` carries `new_device_minutes:`,
  `out_within_2h:` (also its `amount`, for the wave D Alert Rule "drained within 2 hours of a SIM swap") and
  `login_max_swaps_10d:`; `settles_to` carries `merchant_opened:`, `settlements:` and `settlement_days:`. No balance is
  computed: the corpus has no opening balances, so "drained" is the amount paid out within 2 hours.
- **Views and the canvas cap:** one derived Job, `mfs_screen_links` (on `job.dataset.produced`, la-benefits style), writes three
  screens with a `screen` column. `swap_burst`: swaps by a login with 15 or more swaps in any 10 days, plus the handsets first used
  within 24 hours after each swap, the payees first paid within 24 hours, and the cash-outs of any payee of two or more of those
  wallets. `split_deposit`: cash-ins at an agent with 1 000 or more averaging under 25, plus its own registrations of those wallets.
  `merchant_quick_pay`: payments to a merchant with 30 or more payers paying within 60 minutes after a cash-in, its settlement
  account, and the agents where those quick payers were cashed in. `mfs_links` also runs on `cron: "*/5 * * * *"`
  (`JOB-SIGNAL-OVERLAP-SKIP-1`). The golden test reads each view with its own filter and pins under 4 000 rows and 1 000 nodes, the
  story whole and connected, and the look-alike on the canvas but in another component: `mfs_sim_swap_bursts` 409 rows, 327 nodes;
  `mfs_split_deposits` 609 rows, 372 nodes; `mfs_merchant_quick_pay` 385 rows, 287 nodes.
- **Stories as built:** SIM-swap takeover: `S-41` swaps 30 numbers on 2026-06-08..06-17; each wallet is logged into within the hour
  from one of `HS-08913`..`HS-08918` (five victims each) and drained of 460-1 840 within 2 hours to mules `WL-07936`..`WL-07940`
  (opened in the app in May), which cash everything out within hours at `A-088` and `A-093` (Eastfield); each customer gets the number
  back at another store 1-3 days later, and nothing of theirs moves in between; `S-41` also makes ten ordinary swaps in other months.
  Look-alike `S-72` (Riverside store) makes 160 swaps, 110 in a SIM upgrade drive in March 2026; about three in four customers move
  to a new handset of their own within a day; nobody pays out more than 100 within 2 hours. Split deposits: `A-052` (Brickfield)
  registers `WL-07816`..`WL-07935` (KYC 1, 2025-12..2026-01) and splits each deposit of about 55-180 into 3-13 cash-ins of 5-25 a few
  minutes apart (6 267 cash-ins averaging 16.8, all of its cash-ins), with an occasional cash-out at `A-052`, no merchant payment
  and at most one P2P; it earns 2 897 commission against a median of 48. Look-alike `A-131` (Old Market) takes 2 779 small cash-ins
  from 250 traders, about one a visit, who also pay merchants and each other. Merchant cash-out: `MR-077`, opened 2026-09-07, paid
  from 09-09 by `WL-07941`..`WL-08000`, each opened in the three weeks before at and funded by one of `A-207`, `A-208`, `A-219`
  (Station Road), 3-25 minutes after each cash-in of 150-480, passing on 95-100 %; 22 settlements on 22 days to `BA-4194142`.
  Look-alike `MR-214` (Hillcrest grocery, since 2023) with 200 regulars all year, 52 of whom sometimes pay within an hour of a
  cash-in at their local agent; weekly settlement to `BA-3034434`. Areas keep each look-alike apart by design: background wallets
  use agents and merchants of their own area only, and `A-052`, `A-131` and the three Station Road agents serve no background wallet.
- **Staged month:** October adds 92 wallets and a month of transactions, SIM events, logins and commissions; no story activity
  (the screens recomputed over landed plus staged with DuckDB are unchanged).
- **Not checked:** a live Basic run of the packed downloads and the Link Analysis UI; the staged month landing through the
  Pipelines (checked outside the engine only).

## Steps

1. **Distribution seams:** `SPACES_DATA_ROOT` in the launchers; the packer
   `tools/package-la-showcase.mjs`; the version check; test with `la-claims` split into its two downloads →
   verify: unzip the product, Space and data into one parent folder, start Basic, the Space loads and its
   Pipelines land the data.
   **BUILT 2026-11-07 (`5412fddfd`).**

**Wave A: the first new Spaces (in flight)**

2. **`la-police`:** generator, golden tests, template (Basic tier) → verify: loads on a Basic build; every story
   is visible from its saved view; no look-alike joins a story. **BUILT 2026-11-07** (as built under `la-police`
   above). Live proof on a Basic build with the Link Analysis UI (`package.ps1 -Edition Basic -Ui la-app`):
   `node tools/package-la-showcase.mjs la-police` (47 Space files, 18 data files), product, Space and data unzipped
   side by side, started with `SPACES_ROOT=..\inspecto-la-spaces SPACES_DATA_ROOT=..\inspecto-la-data` and nothing
   else. All eleven Pipelines landed their files with 0 rejected (4 001 incidents, 6 000 people, 40 004 plate reads,
   30 168 calls); the cron fired `police_links` (22 434 links) and its signal fired the three derived Jobs (66, 1 287
   and 684 rows); all state was written under `inspecto-la-data\la-police`, the Space folder held only `space.toon`
   and `config\`. In the UI the three saved views are listed and draw without "Result cut short" (61, 736 and 751
   nodes); over the same projection each story is one connected piece from its seed (series 26 of 26 entities,
   drug network and bases 78 of 78, witness 3 of 3) and no look-alike holds a link to a story entity. Not checked:
   the staged month, the saved Geo View (Standard and above).
3. **`la-utility`:** same as step 2. **BUILT 2026-11-07 (`2eb7a0e45`)** (as built under `la-utility` above). Live
   proof on a Basic build with no UI (`package.ps1 -Edition Basic -NoUi`): `node tools/package-la-showcase.mjs
   la-utility` (35 Space files, 36 data files), product, Space and data unzipped side by side, started with
   `SPACES_ROOT=..\inspecto-la-spaces SPACES_DATA_ROOT=..\inspecto-la-data` and nothing else. All eight Pipelines landed
   their 30 files in 30 batches with 0 rejected files or rows (390 461 rows); `pipeline.commit` fired `utility_links`
   (4 301 links) and `transformer_loss` (144 rows); all state was written under `inspecto-la-data\la-utility`, the Space
   folder held only `space.toon` and `config\`. Over each saved view's projection (`POST /inv/projection` with the
   view's filter, none truncated), every story's entities are present: contractor view 23 of 23 tampering (the
   transformers are not in that view) and 16 of 16 reconnection; churn view 25 of 25 churn and 13 of 13 commercial
   load; vendor view 41 of 41 prepaid. Not checked: the Link Analysis UI (no UI in the bundle), the staged month, the
   saved Geo View (Standard and above). The churn view was over the canvas cap then (2 932 nodes); it was narrowed on
   2026-11-08 (see "Canvas cap" above) and its product hub (brochure, user guide, install) landed the same day.
4. **`la-claims` additions:** parts-supplier ring and ghost broker → same check. **BUILT 2026-11-08** (as built under
   `la-claims` above). `la-claims` is still Advanced-only, so the live proof was on a Preview Demo Auth build
   (`package.ps1 -Edition Preview -DemoAuth -NoUi`), with the Space scaffolded and seeded (`seed-la-claims.mjs --apply`:
   every step created, none failed). `la-claims-smoke.mjs --embed` passed 32 of 32, so the seven stories' scenes still hold
   over the nine-kind link Dataset. The affected-module tests (`node tools/affected-modules.mjs`, 35 modules, Advanced
   profile) passed. Not checked: the two new saved views drawn in the Link Analysis UI (no UI in the bundle), and the
   runbook's Working Set figures, which predate the additions.

**Wave B: the existing five ship in the new layout**

5. **Basic tier for the existing four** (`la-claims` first, then `la-showcase`, `la-mobile-money`, `la-audit`):
   `minEdition: basic`; move whatever Basic refuses into the Advanced tier → verify: each loads, lands its data
   and is explorable on a Basic build, and still passes its smoke (`la-claims-smoke`, `la-mm-smoke`,
   `la-audit-smoke`) on Preview.
   **`la-claims` BUILT 2026-11-08.** As built:
   - **What Basic refused: two things, neither a load error.** Every file in the template loads on a Basic build: no
     config error, no failed Run, no ERROR in the log. But (1) the stated `masking_mode: typed` made the graph unreadable
     on Basic, and (2) the template gallery refused the template, because `SpaceManager.missingFeatures`
     (`LA-SAMPLE-GALLERY-DEMOAUTH-1`) marks any template carrying `config/demo-users.toon` `creatable: false` (missing
     `demo-auth`) where no Demo Auth is installed. The API and the unzipped Space download were never refused. The rest
     only had to be confirmed inert:

     | Template part | On a Basic build (seen live) | Where it went |
     |---|---|---|
     | `link-analysis.toon` `masking_mode: typed` | **accepted, and it made the Basic tier unusable**: every id came back `masked:…`, and Basic has no Reveal (no sign-in, no Investigations) | line removed; the edition default holds (`EditionMaskingDefault`: `typed` wherever people sign in, `none` on Basic). The Preview smoke still sees typed masking |
     | `link-analysis.toon` Entity Type `party` (masked), four-eyes threshold, Drafts, embed origins | loaded; used only by masking and Investigations | stays; inert on Basic |
     | `la_index_build` Job (`la.index.build`, on `claims_links` produced) | **runs and succeeds**: `built (full) - index version 1 published` | stays in the Basic tier |
     | `la_detect` Job (`la.detect`, every 5 minutes) | runs and succeeds, `no Investigation rule breached` (nothing to evaluate) | stays; Advanced tier, inert |
     | `demo-users.toon` | the Space ignores it (no sign-in; only the demo-auth module reads it), **but the gallery refused the template** ("Not available in this edition") | stays; **decision (operator 2026-11-08, "A: allow on Basic")**: `missingFeatures` skips the `demo-auth` refusal when the template's `template.toon` says `minEdition: basic`, so the roster is the template's inert Advanced tier. Pinned by `ControlApiSpaceTemplatesTest` (`aBasicTierTemplateKeepsItsDemoUserRosterAsAnInertAdvancedTier`, falsified; `la-claims` creatable, `la-audit` and `la-mobile-money` not until their lanes flip `minEdition`) |
     | `roles.toon` | read on every edition; without sign-in it grants nothing extra | stays; Advanced tier, inert |
     | 3 saved Geo Views | listed; the Geo map is Standard and above | stay |
     | seed script, smoke | not run on Basic; they need Demo Auth | unchanged |

   - **What the la-showcase, la-mobile-money and la-audit lanes should do:** flipping `minEdition` to `basic` is
     what makes the gallery accept the template's `demo-users.toon`. Then check `masking_mode`. A stated `typed` (or any
     masking) masks a Basic graph that nobody can reveal, so leave it unset and let the edition default decide. The
     `la.index.build` and `la.detect` Job types are built in on every edition and their Platform Services (`link-index`,
     `alerts`) exist on Basic, so these Jobs run there and succeed. A Job whose type's module is missing is listed as
     `hosted: false` and never fires, which is also inert.
   - **Offerings:** `la-claims` moved from `offerings/preview.toon` to `offerings/basic.toon` contentPacks. Preview still
     carries it through its includes.
   - **Tests:** `LaClaimsTemplateTest` pins `masking_mode` as unset, with an effective mode of `none` without sign-in and
     `typed` with `auth.mode=demo`.
   - **Live proof on Basic** (`package.ps1 -Edition Basic -NoUi`; `node tools/package-la-showcase.mjs la-claims`, 43 Space
     files and 7 data files, unzipped side by side, `SPACES_ROOT` + `SPACES_DATA_ROOT`): all seven Pipelines landed with 0
     rejected (520 claims, 1 071 parties, 405 policies, 901 bills, 449 locations, 25 providers, 1 056 invoices). Then
     `claims_links` produced 2 637 rows, `claims_policy_timing` 520, and `la_index_build` and `la_detect` ran as in the
     table above. Each of the five saved views' projections (`POST /inv/projection` with the view's filter) returned
     200, not cut short, 0 masked ids: network 426 nodes, shared identity 227, provider money 321, parts suppliers 19,
     premium payers 32. The ring's garage, clinic and lawyer, `SUP-07`/`SUP-01`, the ghost broker's two accounts and the
     family's account are on the views that carry them.
   - **Preview still passes** (`package.ps1 -Edition Preview -DemoAuth -NoUi -NoBakeDemo`, the Space scaffolded from the
     edited template with `seed-la-claims.mjs --scaffold`): the seed (`--apply`) created every step and none failed.
     `la-claims-smoke.mjs --embed` passed 32 of 32, including "typed masking: the Working Set carries no raw claimant id"
     and the admin's audited Reveal, so the edition default still masks on a sign-in build.
   - **Not checked:** the template gallery on Basic with the gallery fix (the fix came after the live Basic boot; it is
     covered by `ControlApiSpaceTemplatesTest`), the Link Analysis UI on Basic (the bundle had no UI; the views were checked through their
     projections), the Geo Views on Standard, and an OIDC sign-in build (Demo Auth stands in for it: both make
     `EditionMaskingDefault` answer `typed`).

   **`la-mobile-money`, `la-audit` and `la-showcase` BUILT 2026-11-08** (`d20b08632`, `3fdb6104d`, `6b52b409c`), one
   commit per Space, the la-claims pattern. As built:
   - **What Basic refused, per Space.**
     - `la-mobile-money`: the stated `masking_mode: typed` (every wallet id `masked:…` with no Reveal) and the gallery's
       roster refusal. Line removed; `minEdition: basic` lifts the roster refusal. Nothing else.
     - `la-audit`: the same two. The `party` Entity Type stays marked masked for sign-in builds, and the committee
       portal's embed origins stay; both are inert on Basic.
     - `la-showcase`: already left `masking_mode` to the edition. What Basic refused were its **three telecom Alert Rules**
       (`registry/alert-rules/telecom_{wangiri,irsf,simbox}_lines`): the gallery marked the template `creatable: false`
       (`alert.dispatch`) and `POST /spaces` from it answered 422. The unzipped Space download already booted with each rule
       unarmed and a WARN. **Decision (operator 2026-11-08, "A: allow, unarmed")**, recorded under the Edition rule above:
       the rules stay in the template and ship unarmed on Basic. `SpaceManager.missingFeatures` and `TemplateSeedGate`
       skip the `alert.dispatch` refusal for a `minEdition: basic` template's Alert Rules (registry and pending); the seed
       gate still judges their content through `AlertRoutes.parseContent` (`parse` minus its edition door).
   - **Inert on Basic, seen live:** `la_detect` runs and succeeds ("no Investigation rule breached"); `mule_risk_score`
     (`risk.score`) is listed `NOT hosted` because the scoring module is not in Basic; `telecom_typology_alerts`
     (`alert.evaluate`) is hosted and runs after `telecom_typology_counts` with "no rule breached" (no rule is armed);
     `demo-users.toon` and `roles.toon` are ignored. `la_index_build` builds the index in all three.
   - **Offerings:** all three moved from `offerings/preview.toon` to `offerings/basic.toon`. Preview now lists no content
     packs of its own and carries every LA showcase through `includes: advanced` → Basic.
   - **Tests:** `ControlApiSpaceTemplatesTest.aBasicTierTemplateShipsItsAlertRulesUnarmedOnBasic` (gallery creatable,
     `POST /spaces` creates with the rule file in place, the same template without `minEdition: basic` still 422, a malformed
     rule still 422; mutation-checked on each gate: removing the check in `missingFeatures` fails it and
     `galleryCountsTheDemoUserSignInATemplateNeeds`, removing it in `TemplateSeedGate` fails it).
     `galleryCountsTheDemoUserSignInATemplateNeeds` now copies all five shipped templates and pins `la-claims`,
     `la-mobile-money`, `la-audit` and `la-showcase` creatable with no missing feature, with no Demo Auth installed.
     `LaMobileMoneyTemplateTest` and `LaAuditTemplateTest` pin `masking_mode` unset (`none` without sign-in, `typed` with
     `auth.mode=demo`). `tools/check-offerings.test.mjs` pins the placement.
   - **Live proof on Basic** (`package.ps1 -Edition Basic -NoUi`; each Space packed with `package-la-showcase.mjs`,
     unzipped side by side, `SPACES_ROOT` + `SPACES_DATA_ROOT`): every Pipeline landed with 0 rejected (`la-mobile-money`
     3 Pipelines, 14 `wallet_txn` days; `la-audit` 5; `la-showcase` 11, the mule, telecom and geo feeds). Link Jobs:
     `mm_links` 5 777 rows; `ap_links` 3 650 and its three derived subsets, `gl_links` 72, `ap_duplicate_payments` 13;
     `telecom_links` 16 683, `telecom_msisdn_indicators` 2 423, `telecom_typology_counts` 1. `GET /settings/link-analysis`
     answers `maskingModeInForce: none` in each. Each saved view's projection (`POST /inv/projection` with the view's
     filter) returned 200 with 0 masked ids, and the planted entities are on the views that carry them: `mm_device_farm`
     16 nodes, `mm_structuring_band` 31 (`HUB-01`, `AG-18`, `AG-39`), `mm_money_trail` 465; `ap_approval_graph` 175
     (`V-117`, `E-15`, `E-07`, `V-099`), `ap_vendor_identity` 291 (`V-117`, `E-31`), `gl_intercompany` 8 (`LE-05`,
     `LE-08`); `mule_large_transfers` 8, `mule_layering_ring` 112, `mule_structuring` 112 (`MULE-HUB-01`, `RELAY-01`,
     `OFFSHORE-77`, `SHELL-A`), `telecom_fraud_network` 767.
   - **Two views are cut at the 4 000-row default projection limit on every edition**, not only Basic:
     `mm_money_trail` (`mm_links` has 5 777 rows) and `telecom_fraud_network` (16 683). The canvas reads them the same
     way on Preview. Left as found; a narrower view is a hub decision (see "Canvas cap" above).
   - **One ERROR on the first Basic boot of `la-mobile-money`:** its `la_index_build` cron (:45) fired before the first
     `mm_links` run (:50). The build was refused (no Dataset yet), and `LaIndexBuildJob` then threw a
     NullPointerException while emitting its completion signal, because the payload carried `mode: null`. Every later
     build succeeded. It is an engine defect on every edition, not a Basic one: filed as `LA-INDEX-BUILD-REFUSED-NPE-1`
     in the BACKLOG.
   - **Preview still passes** (`package.ps1 -Edition Preview -DemoAuth -NoUi -NoBakeDemo`, the three Spaces scaffolded
     from the edited templates with `seed-la-showcase` / `seed-la-mm` / `seed-la-audit --scaffold`, data Jobs triggered
     after every file landed, then `--apply`: every stage created, none failed). The three Alert Rules are armed there.
     `la-mm-smoke` 10/10 (including "masking typed: every Measure entity is a masked token"), `la-audit-smoke` 30/30,
     `la-demo-smoke` 17/17 and `la-demo-smoke --telecom` 29/29 (including the typology Alerts). 0 ERROR lines in the
     Preview server log.
   - **Not checked:** the gallery and `POST /spaces` with the new Alert Rule rule on a live Basic bundle (the bundle was
     built before that change; it is covered by the test above), the Link Analysis UI on Basic (no UI in the bundle), the
     Geo Views and the dashboard Widget on Standard, and an OIDC sign-in build.
6. **Pack and boot-test all five** with `tools/package-la-showcase.mjs`. `la-sample` keeps its files in
   `data/samples` for the in-app *Load samples*; check that this still works from a data root.
   **BUILT 2026-11-08** (no code change). On the Basic bundle above:
   - **Packed:** `la-sample` 7 Space files and 3 data files, `la-claims` 43 and 7, `la-showcase` 61 and 16, and
     `la-mobile-money` and `la-audit` as packed for step 5. All five Space zips and five data zips unzipped side by side and booted
     together on one Basic server (`SPACES_ROOT` + `SPACES_DATA_ROOT`): all five Spaces listed, `la-claims`' seven
     Pipelines landed with 0 rejected, 0 ERROR lines.
   - **`la-sample` attached as a download lands nothing by itself**, by design: its files are in `data/samples`, and
     only *Load samples* (`POST /spaces {template, loadSamples: true}`) copies them into the inbox. An install that
     attaches the `la-sample` download instead of using the in-app sample copies `data/samples/mule_transfers` into
     `data/inbox/mule_transfers` by hand, as its `template.toon` says.
   - **Load samples from a data root works:** with `SPACES_ROOT=spaces` (the bundle's `_templates`) and
     `SPACES_DATA_ROOT` set, `POST /spaces {"id":"la-sample","template":"la-sample","loadSamples":true}` answered
     `mule_transfers: loaded, 3 files, 0 quarantined`. Every state folder (`data\`, `audit\`, `duckdb\`) was written
     under `<data root>\la-sample`, and the Space folder holds only `space.toon` and `config\`. `mule_layering_ring`
     projects 112 nodes, not cut short, with `MULE-HUB-01`, `OFFSHORE-77` and `SHELL-A` on it.

**Wave C: eleven new Spaces** (specs 4–14), one lane each, in this order: `la-card`, `la-ownership`, `la-trade`,
`la-procurement`, `la-benefits`, `la-health`, `la-telco-ra`, `la-mfs`, `la-logistics`, `la-insider`, `la-crypto`.
`la-mfs` (added 2026-11-08) sits beside `la-telco-ra` so the Telecom industry is complete together.

7. **Each Space:** generator, golden tests, template (Basic tier), packed downloads → same check as step 2.

**Wave D: Advanced tiers**

8. **Advanced tier** for every new Space (personas, Alert Rule, seed script) → verify on a Preview build with
   demo sign-in.
   **`la-police` BUILT 2026-11-08.** As built:
   - **In the template, inert on Basic** (the Edition rule as settled 2026-11-08): `config/demo-users.toon` (`admin`,
     `police.analyst` lead, `police.sio` second approver, `police.reviewer` read-only, `police.outsider` not a member),
     `config/roles.toon` (`police-analyst`, `police-sio`, `la-viewer`, `la-settings`), and `four_eyes_fan_out_above: 2000`
     plus Drafts added to the industry lane's `link-analysis.toon` (its Entity Types kept; none masked, no `masking_mode`).
   - **Alert Rule on new serial matches, as a registry rule** (the la-showcase telecom pattern, not an Investigation-bound
     one): the `police_serial_match_counts` Job (on `police_links` produced) writes one row, `repeat_seller_matches` (the
     `item_match` links of a seller with 2 or more), `repeat_sellers` and `one_off_matches`; the `police_serial_match_alerts`
     Job (`alert.evaluate`) runs after it; `police_repeat_seller_serial_matches` is `max(repeat_seller_matches) gte 2`,
     WARNING. Decided: "new serial matches" alone would fire on any resold stolen item, and the data carries six of
     those on purpose; the story is the same seller again and again, so the rule counts repeat sellers only. On the
     shipped data: 12 repeat-seller matches (`P-00476` 9, `P-04874` 3), 7 one-off (six ordinary items, and `P-00785`'s
     single series item, which the rule does not see). The Alert names a count, never a person. The measure is a running
     total, so the rule stays met after every build; the staged month's 13th match does not by itself raise a second
     Alert while the first is open. A per-build "new matches" count is a later refinement if the demo needs it.
   - **Seed** `tools/seed-la-police.mjs` (sibling of `seed-la-claims.mjs`): Investigation `burglary-series` over
     `police_links_dataset` (seed `ZX 9117 X` + `P-00476`, bounded expand, annotate, window, snapshot), members (reviewer,
     sio as reviewer, admin as lead; not the outsider), Entity List `series-sellers`, a Case assigned to `police.sio` and
     linked, one held four-eyes expand, one open Draft. The Draft step annotates `DLR-09`: the first draft named
     `P-04874`, who is two hops from the seeds and not in the Working Set (422).
   - **Tests:** `LaPoliceTemplateTest` (inspecto: masking unset, four-eyes 2000, no masked type, personas hold exactly
     their capabilities, the rule passes `AlertRoutes.parseContent`, the Jobs' signals);
     `LaPoliceLinksJobTest.theSerialMatchCountsSeeOnlyTheSeriesSellersAsRepeatSellers` (the counts equal the per-seller
     tally of the real `item_match` output, and every repeat seller is a series seller); `ControlApiSpaceTemplatesTest.
     galleryCountsTheDemoUserSignInATemplateNeeds` now copies `la-police` too and pins it creatable with no missing feature
     and no Demo Auth installed; `tools/seed-la-police.test.mjs` (mocked fetch, 9 tests; the ids pinned to the ground truth).
   - **Live, Preview** (`package.ps1 -Edition Preview -DemoAuth -NoUi -NoBakeDemo`, Space laid down with
     `seed-la-police.mjs --scaffold`, `serve-demo`): 11 Pipelines, 0 rejected; the rule armed at boot; after the cron
     `police_links` (22 434 rows) the counts Job wrote 12 / 2 / 7 and `police_serial_match_alerts` reported 1 breach,
     an OPEN WARNING with value 12. `seed-la-police.mjs --apply` created every step, none failed. Persona checks 19/19:
     analyst, sio, reviewer read the Working Set and the outsider gets 404; reviewer and outsider cannot add a step
     (403); the analyst has no approval inbox (403) and cannot approve the held expand, nor can the reviewer (403); the sio
     sees it and approves it, and it runs; the Working Set holds `ZX 9117 X`, `P-00476`, `P-05167`, `ADR-1377`, `DLR-04`;
     no id is masked. 0 ERROR lines in the server log.
   - **Live, Basic** (`package.ps1 -Edition Basic -NoUi`, Space and data split with the packer's helpers, `SPACES_ROOT` +
     `SPACES_DATA_ROOT`): 11 Pipelines, 0 rejected; all six Jobs succeed; one WARN "Not arming alert rule
     'police_repeat_seller_serial_matches'"; `police_serial_match_alerts` "no rule breached"; the three views' projections
     unchanged (61, 736, 751 nodes, not cut short, `masking: none`); 0 ERROR lines.
   - **Not checked:** the gallery on a live Basic bundle. Every committed template ships in every bundle's gallery
     (`package-spaces.ps1`), so `la-police` was there; it was not opened. `ControlApiSpaceTemplatesTest` runs its gallery
     with the Advanced features; the verification agent re-ran it with no features, as on Basic, and `la-police` was
     creatable with no missing feature. Also not checked: the Link Analysis UI (both bundles were `-NoUi`; checked
     through the API), an OIDC sign-in build, and the staged month on either build.
   - **Offering (2026-11-08, follow-up):** `la-police` was the only LA showcase missing from `offerings/basic.toon`
     `contentPacks` (the Offering's declared catalogue, read by `tools/customer-build.mjs` and the Product Map; the
     bundle gallery does not read it). Added, and pinned in `tools/check-offerings.test.mjs`.

   **`la-utility` BUILT 2026-11-08** (the first new Space with its Advanced tier; the pattern for the others). As built:
   - **In the template** (`minEdition` stays `basic`, per the Edition rule): `config/demo-users.toon` (`rp.analyst` lead,
     `rp.manager` second approver, `rp.reviewer` read-only, `rp.outsider` not a member, and `admin` defined exactly as in
     `la-showcase`, since a user id defined differently in two Spaces is a roster conflict), `config/roles.toon`
     (`rp-analyst`, `rp-manager`, `la-viewer`, `la-settings`, `fraud-analyst` for `admin`), the Alert Rule
     `registry/alert-rules/utility_transformer_loss.toon`, the Job `utility_loss_alerts` (`alert.evaluate` on
     `transformer_loss`' `job.dataset.produced`), and `four_eyes_fan_out_above: 2000` plus Drafts limits in
     `link-analysis.toon`. `masking_mode` stays unset (the edition default: `none` on Basic, `typed` with sign-in; every
     utility Entity Type is unmasked, so nothing is hidden).
   - **Seed** `tools/seed-la-utility.mjs` (sibling of `seed-la-claims.mjs`; `--scaffold`, dry run, `--apply`, idempotent;
     story ids read from the ground truth): Investigation `tampering-case` (seeded with `C-14`, `TX-007`, `TX-019`;
     bounded expand, annotate, threshold, window, resolve, snapshot), members (`rp.reviewer` and `rp.manager` reviewers,
     `admin` lead; `rp.outsider` not a member), Entity Lists `tampered-premises` (22) and `revenue-protection-watch`
     (`C-14`, `C-22`, `V-08`), a Case from `C-14` assigned to `rp.manager` and linked, an Investigation template, one
     unbounded expand held for four-eyes, one open Draft. Test: `tools/seed-la-utility.test.mjs` (mocked fetch).
   - **Decided while building:**
     - **The Alert Rule is not grouped by transformer.** A `by: [transformer_id]` rule is refused when the Space is created
       (`POST /spaces` 422, `ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies`): the `by` column is checked
       against the Dataset's Schema, and `transformer_loss` is a Job output with no Schema yet. The deferred
       `pending/alert-rules/` only waits for score-model outputs (`afterScore`), not Job outputs. So the rule is
       `max(loss_pct) gte 15` over the whole Dataset, as `la-showcase`'s telecom rules count over a Job output: the Alert
       carries the highest monthly loss, and `transformer_loss` names the transformer. A deferral for Job outputs would
       allow one Alert per transformer; not built.
     - **`transformer_loss` balances a month only once both sides have landed** (`JOIN`, was `LEFT JOIN`). On the first
       Preview boot a month's `transformer_read` file landed before its `meter_read` file, the month read as 100 % loss, and
       the Alert fired at 100. Now it fires at the first real crossing (15.2 % on TX-019).
     - The Draft step annotates `C-14` (in the Working Set) about `C-22`: `C-22` is two hops out, and annotating it was
       refused ("not in the Working Set").
   - **Tests:** `LaUtilityTemplateTest` (inspecto: masking unset, `none` without sign-in and `typed` with `auth.mode=demo`;
     four-eyes 2000; each persona's capabilities; the shared `admin`; the rule and its evaluate Job, real loaders);
     `UtilityLinksJobGoldenTest` (the rule's own measure and threshold over the real `transformer_loss`: it fires, and
     with `TX-007` and `TX-019` removed it would not; the holiday home's transformer is never over); the gallery test
     `galleryCountsTheDemoUserSignInATemplateNeeds` now also pins `la-utility` creatable with no missing feature and no
     Demo Auth installed.
   - **Live proof on Preview** (`package.ps1 -Edition Preview -DemoAuth -NoUi -NoBakeDemo`, the Space laid out with
     `seed-la-utility.mjs --scaffold`, `serve-demo`): 30 files landed, 0 rejected; the rule was armed at boot and fired
     once (CRITICAL, 15.2 %, state OPEN). `seed-la-utility.mjs --apply` created every step (the Draft on the second run,
     after the fix above), none failed. Probes, 11 of 11: the five personas sign in; `rp.analyst` reads `tampering-case`;
     `rp.outsider` gets 404 on it; `rp.reviewer` reads it (200) and a write is refused (403); `rp.manager`'s approval
     inbox holds the expand (`rp.analyst`'s inbox is 403); the Alert is listed. Every persona, `rp.outsider` included, reads
     the Datasets (they carry no `owner`/`shares`). 0 ERROR lines.
   - **Live proof on Basic** (`package.ps1 -Edition Basic -NoUi`, the same scaffold, `serve.bat`): 30 files landed, 0
     rejected; "Not arming alert rule 'utility_transformer_loss'" (WARN); `utility_loss_alerts` ran and reported no rule
     breached; the three data Jobs produced 4 301, 144 and 441 rows; the gallery lists `la-utility` creatable with no
     missing feature; masking in force `none`. 0 ERROR lines.
   - **Not checked:** the Link Analysis UI (no UI in either bundle; the personas were checked through the API), the
     packed downloads on Preview (the Space was scaffolded; the downloads carry the same `config/`), the Investigation's
     embed link, and an OIDC sign-in build.

   **`la-card` BUILT 2026-11-09** (finishing a half-done lane; the `la-utility` pattern, the `la-police` counts-Job rule). As built:
   - **In the template** (`minEdition` stays `basic`): `config/demo-users.toon` (`card.analyst` lead, `card.manager`
     second approver, `card.reviewer` read-only, `card.outsider` not a member, and `admin` defined exactly as in
     `la-showcase`: `admin;fraud-analyst;la-settings`), `config/roles.toon` (`card-analyst`, `fraud-analyst` for `admin`
     with `la-utility`'s six capabilities, `card-manager`, `la-viewer`, `la-settings`), `four_eyes_fan_out_above: 2000`
     plus Drafts limits in `link-analysis.toon` (no `masking_mode`; no Entity Type masked). The first draft gave `admin`
     `card-analyst`, which would have been a roster conflict with every other demo Space (409 `ROSTER_CONFLICT`).
   - **Alert Rule as a registry rule over a counts Job** (the `la-police` pattern): `card_compromise_counts`
     (`sql.template` on `card_links`' `job.dataset.produced`, `min_defrauded_cards: "10"`) writes one row:
     `compromised_terminals` (merchants with 10 or more `visited_before_fraud` links), `cards_seen_since_compromise`
     (distinct cards approved at one of them on or after its first defrauded card's visit, with no
     `fraud_card_not_present` chargeback) and `busiest_other_merchant`; `card_compromise_alerts` (`alert.evaluate`) runs
     after it; `card_new_cards_at_compromised_terminal` is `max(cards_seen_since_compromise) gte 1`, WARNING, not
     grouped (a Job output, as in `la-utility`).
   - **Real figures on the shipped data** (the counts Job's SQL over the real `card_links`, `card_auth`, `chargeback`;
     pinned by `CardLinksJobGoldenTest`): compromised_terminals **1** (`M-317`, first defrauded card's visit
     2026-03-02 14:31); cards_seen_since_compromise **60**; busiest_other_merchant **4** (`M-832`). The 60 are NOT the
     story's 60 defrauded cards (those are left out): they are `M-317`'s 60 other customers, every one of whom paid there
     again after the compromise began: the ground truth's 10 `in_window_not_defrauded` cards in the window, 50 more
     after it (120 cards have an approved payment at `M-317` in all). Equal numbers by coincidence; the template description and the
     guides now say which 60. `M-052` has 0 `visited_before_fraud` links and 925 cards with an approved payment there. With `M-317` taken
     out of the inputs the Job yields 0 / 0 / 4.
   - **Seed** `tools/seed-la-card.mjs` (`--scaffold`, dry run, `--apply`, idempotent; ids from the ground truth):
     Investigation `compromised-terminal` (seed `M-317`; a bounded expand over `visited_before_fraud`, `maxFanOut` 80,
     to the 60 defrauded cards; annotate, threshold, window, resolve, snapshot), members (`card.reviewer` and
     `card.manager` reviewers, `admin` lead; not `card.outsider`), Entity List `cards-to-reissue` (the 10 window cards),
     a Case from `M-317` assigned to `card.manager` and linked, an Investigation template, one unbounded expand held for
     four-eyes, one open Draft annotating `M-317` (a card to reissue is two hops out, card to `M-317` to card, so
     annotating it would be refused). The card-testing device is not seeded: it belongs to another story.
   - **Tests:** `LaCardTemplateTest` (inspecto: masking unset, `none` without sign-in and `typed` with
     `auth.mode=demo`; four-eyes 2000; no masked type; persona capabilities; every persona role defined and
     `fraud-analyst` equal to `la-utility`'s; the shared `admin`; the rule through `AlertRoutes.parseContent`; the Jobs'
     signals); `CardLinksJobGoldenTest` (runs the counts Job after `card_links`; the real figures above and the rule's own
     measure and threshold fire; the Job's own SQL without `M-317` yields no compromised terminal and would not fire;
     `M-052` is busy yet never a compromised terminal); the gallery test pins `la-card` creatable with no missing
     feature and no Demo Auth; `tools/seed-la-card.test.mjs` (mocked fetch, 11 tests). Falsified, each then restored:
     no `M-317` filter, the counts Job counting `used_at` instead of `visited_before_fraud` (`M-052` becomes a terminal,
     383 terminals in all), `masking_mode: typed`, `fraud-analyst` removed, `card-analyst` given the approve capability,
     `admin` back to `card-analyst`, `la-card` out of the gallery's Basic-tier set, and in the seed: the outsider made a
     member, the Draft annotating a card to reissue, `M-052` added to the seed.
   - **Not checked:** any live run. No Preview build with Demo Auth (the rule armed and firing at 60, the seed's
     `--apply`, the persona probes) and no Basic bundle (rule unarmed, `card_compromise_alerts` reporting no breach, 0
     rejected); the expand's `linkKinds` and the Draft annotation are as the API reads them, not observed. Also not
     checked: the Link Analysis UI, the composed *Banking and finance* industry Space with this tier, an OIDC build.

   **`la-ownership` BUILT 2026-11-09** (the `la-card` pattern; spec 5's "a new ownership path that reaches a listed person
   within 3 hops"). As built:
   - **In the template** (`minEdition` stays `basic`): `config/demo-users.toon` (`ownership.analyst` lead,
     `ownership.manager` second approver, `ownership.reviewer` read-only, `ownership.outsider` not a member, and `admin`
     byte-identical to `la-showcase`, `la-utility` and `la-card`), `config/roles.toon` (`ownership-analyst`,
     `fraud-analyst` as in `la-card` and `la-utility`, `ownership-manager`, `la-viewer`, `la-settings`),
     `four_eyes_fan_out_above: 2000` plus the same Drafts block as `la-card` in `link-analysis.toon` (no `masking_mode`; no
     Entity Type masked).
   - **Alert Rule as a registry rule over a counts Job:** `ownership_sanctions_counts` (`sql.template` on
     `ownership_links`' `job.dataset.produced`, sources `ownership_links` only) writes one row: `listed_paths` (ownership
     paths of 1, 2 or 3 `owns` hops from a `customer_is` company up to a person with a `name_match`; explicit joins, a
     3-hop path may not pass back through the customer's company), `customers_exposed`, `matched_persons`;
     `ownership_sanctions_alerts` (`alert.evaluate`) runs after it; `ownership_path_to_listed_person` is
     `max(listed_paths) gte 1`, CRITICAL, not grouped (a Job output).
   - **Real figures on the shipped data** (the Job run after `ownership_links` in `OwnershipLinksJobGoldenTest`):
     listed_paths **1** (`CUS-00528`'s company `CO-04313` ← 50 % `CO-04431` ← 60 % `PER-03904`, two hops), customers_exposed
     **1**, matched_persons **1**. By hop count over all customers: 2 235 one-hop, 1 208 two-hop and 15 three-hop paths, of
     which only that one ends at a matched person. With `PER-03904`'s links removed: 0 / 0 / 0. With a `name_match` given to
     the near-namesake `PER-02226`: 2 paths, 2 customers (he owns `CUS-00642`'s company outright, one hop), so only the
     match rule keeps him out. With one given to the hidden owner `PER-02104` (six hops above his customers): still 1. The
     8 staged customers add no path (checked by hand over the staged `customer` file, not pinned).
   - **Decided while building:**
     - **"Within 3 hops" counts `owns` hops from the customer's company to the matched person.** The `customer_is` link
       (the customer IS the company) and the `name_match` (the person IS the listed person) are identities, not hops;
       counted as links the planted path is four. A stake of any size counts (no 25 % or 50 % threshold: spec 5 names
       none, and a sanctions screen follows small stakes too). Only `owns`: an officer is not an ownership path.
     - **A running total, not a "new path" count** (as `la-police` decided). The register is a snapshot with no date a
       stake was registered (`owns.ts` is the owned company's incorporation date), so "new" could only mean "not in the
       previous build", which a `sql.template` Job cannot see. An exposure stays an exposure until it is resolved, so the
       rule stays met after every build and the open Alert is not raised twice; a later path raises the value.
     - **The industry packer now joins the roster.** `la-ownership` is the second *Banking and finance* use case with
       `config/demo-users.toon` and `config/roles.toon`, and `composeSources` refused the recipe ("'config/demo-users.toon'
       is shipped by both la-ownership and la-card"). Both files are now merged like `link-analysis.toon` (users by id,
       roles by name, a shared id defined differently refused; `mergeDemoUsers`, `mergeRoles`, `joinFile`), and the
       recipe test runs `composeSources` over every recipe's working-tree `config/`. Not visible at HEAD: the packer reads
       committed files, so `package-la-showcase.mjs la-banking` passed before the commit either way.
     - The Draft annotates `PER-03904` (one hop from the seed `SL-0137`); `CO-04431`, the 50 % owner, is two hops from
       either seed and would be refused. The bounded expand leaves it out on purpose, so the held unbounded expand is the
       step that closes the path.
   - **Seed** `tools/seed-la-ownership.mjs` (`--scaffold`, dry run, `--apply`, idempotent; ids from the ground truth):
     Investigation `sanctions-exposure` (seeds `SL-0137` + `CUS-00528`; a bounded expand over `name_match`, `owns`,
     `customer_is`, `maxFanOut` 50; annotate, window 2016-01-01 to 2026-10-01, snapshot; no threshold step, since every
     ownership link's amount is 0), members (`ownership.reviewer` and `ownership.manager` reviewers, `admin` lead; not
     `ownership.outsider`), Entity List `sanctions-path-parties` (the four parties on the path), a Case from `CUS-00528`
     assigned to `ownership.manager` and linked, an Investigation template, one unbounded expand held for four-eyes, one
     open Draft. No look-alike is in any step.
   - **Tests:** `LaOwnershipTemplateTest` (inspecto: masking unset, `none` without sign-in and `typed` with
     `auth.mode=demo`; four-eyes 2000, Drafts; no masked type; persona capabilities; every persona role defined;
     `fraud-analyst` as in `la-utility`, and `fraud-analyst`, `la-viewer`, `la-settings` as in `la-card`; the shared
     `admin`; the rule through `AlertRoutes.parseContent`; the Jobs' signals); `OwnershipLinksJobGoldenTest` (runs the
     counts Job after `ownership_links`; the figures above, the planted path, the rule fires; without `PER-03904` it would
     not; the near-namesake is one hop above a customer yet never counts, and would if matched; a listed person six hops
     up is not counted); the gallery test pins `la-ownership` creatable with no missing feature and no Demo Auth;
     `tools/seed-la-ownership.test.mjs` (mocked fetch, 11 tests); `tools/package-la-showcase.test.mjs` (the roster merge,
     and every recipe composes from the working tree). Falsified, each then restored: the counts Job counting every owner
     instead of matched persons (3 458 paths), `name_match` without the birth year and nationality (the near-namesake
     matched; 2 paths), the counts Job reaching six hops (13 paths with the hidden owner listed), `masking_mode: typed`,
     `ownership-analyst` given the approve capability, `admin` redefined, `la-viewer` given a capability, `fraud-analyst`
     removed, `la-ownership` out of the gallery's Basic-tier set; in the seed the outsider made a member, the Draft
     annotating `CO-04431`, the near-namesake's customer seeded; in the packer the roster not joined (*Banking and
     finance* refused) and a differing definition not refused.
   - **Not checked:** any live run. No Preview build with Demo Auth (the rule armed and firing at 1, the seed's `--apply`,
     the persona probes) and no Basic bundle (rule unarmed, `ownership_sanctions_alerts` reporting no breach, 0 rejected);
     the expand's `linkKinds`, the window and the Draft annotation are as the API reads them, not observed. Also not
     checked: the Link Analysis UI, the composed *Banking and finance* Space booted with both rosters, an OIDC build, and
     the packed downloads of a commit holding this tier (the packer reads HEAD).

   **`la-telco-ra` BUILT 2026-11-09** (the `la-card` pattern; spec 10's "usage without billing over 1 day for a
   subscriber"). As built:
   - **In the template** (`minEdition` stays `basic`): `config/demo-users.toon` (`telcora.analyst` lead,
     `telcora.manager` second approver, `telcora.reviewer` read-only, `telcora.outsider` not a member, and `admin`
     byte-identical to `la-showcase`, `la-card` and `la-ownership`), `config/roles.toon` (`telcora-analyst`,
     `fraud-analyst`, `telcora-manager`, `la-viewer`, `la-settings`; the three shared roles byte-identical to `la-card`'s,
     since the packer merges roles by name and the *Telecom* industry Space composes this with `la-mfs`), and
     `four_eyes_fan_out_above: 2000` plus `la-card`'s Drafts block in `link-analysis.toon` (no `masking_mode`; no Entity
     Type masked).
   - **Alert Rule as a registry rule over a counts Job:** `telco_ra_unbilled_counts` (`sql.template` on `telco_ra_links`'
     `job.dataset.produced`; sources `subscriber`, `network_usage`, `billing_record`, not the link Dataset, so it does not
     parse `via`; `min_unbilled_months: "2"`) writes one row: `unbilled_subscribers`, `known_list_subscribers`,
     `other_unbilled_subscribers`; `telco_ra_unbilled_alerts` (`alert.evaluate`) runs after it;
     `telco_ra_usage_without_billing` is `max(unbilled_subscribers) gte 1`, WARNING, not grouped (a Job output).
   - **Real figures on the shipped data** (the Job run after `telco_ra_links` in `TelcoRaLinksJobGoldenTest`, and the same
     SQL over the CSVs outside the engine): unbilled_subscribers **300** (exactly `DL-047`'s story SIMs), known_list_subscribers
     **40** (`DL-000`'s test SIMs), other_unbilled_subscribers **62** (the 12 late-billed and the 50 single dropped months).
     Landed plus staged: the same 300 / 40 / 62 (checked outside the engine, not pinned). With the story's SIMs taken out:
     0 counted. Each guard measured by dropping it: no known-list exclusion 340, no "still unbilled" condition 312, no
     billing-landed gate with September's billing file not yet landed 355 (with the gate: 300).
   - **Decided while building:**
     - **"Over 1 day" reads "over one period", and a period is a month.** The feeds were built monthly (Basic-tier
       deviation), so a day cannot be seen. A subscriber counts when NOT on the known list (plan `Internal test`), with
       usage and no billing record in 2 or more months (the `unbilled_usage` screen's own threshold), the latest of them
       still unbilled. A literal "any unbilled month" would fire on the 50 single dropped months and the 12 late-billed
       SIMs without the story (362 in all), so the rule would not tell the story from the reconciliation's ordinary noise.
       The "still unbilled" condition is what keeps the 12 late-billed SIMs out (their first two months were never billed,
       every later one was): leakage that stopped is not the alarm.
     - **The known list is the plan `Internal test`**, as the generator and the Basic-tier view state it. It is a
       literal in the SQL, not a parameter (one list, nothing asked to configure it).
     - **A month is judged only once its billing file has landed** (`JOIN` on the billing periods present), the
       `la-utility` lesson: on a first boot a month's usage file can land before its billing file, and every subscriber
       used that month would read as unbilled (355 counted in the test). `telco_ra_links`' own `unbilled_months:` in `via`
       has the same transient on a first boot; that is the Basic tier, not changed here.
     - **A running total, not a "new" count** (as `la-police` and `la-ownership` decided): an unbilled SIM stays unbilled
       until someone fixes billing, so the rule stays met after every build and the open Alert is not raised twice; the
       staged month changes nothing. A per-build "new" count would need the previous build, which a `sql.template` Job
       cannot see.
     - The Draft annotates `DL-047` about `DEV-00086`: the bounded expand follows `activated_by` only, so the device is
       two hops out (dealer, SIM, device) and annotating it would be refused.
   - **Seed** `tools/seed-la-telco-ra.mjs` (`--scaffold`, dry run, `--apply`, idempotent; ids from the ground truth):
     Investigation `unbilled-usage` (seed `DL-047`; a bounded expand over `activated_by`, `maxFanOut` 400, to the dealer's
     336 subscribers, the 300 among them; annotate, threshold, window, resolve, snapshot), members (`telcora.reviewer` and
     `telcora.manager` reviewers, `admin` lead; not `telcora.outsider`), Entity List `unbilled-sims` (the 300), a Case from
     `DL-047` assigned to `telcora.manager` and linked, an Investigation template, one unbounded expand held for four-eyes,
     one open Draft. The test dealer and its SIMs are in no step.
   - **Tests:** `LaTelcoRaTemplateTest` (inspecto: masking unset, `none` without sign-in and `typed` with
     `auth.mode=demo`; four-eyes 2000, Drafts; no masked type; persona capabilities; every persona role defined;
     `fraud-analyst`, `la-viewer`, `la-settings` as in `la-card`; the shared `admin`; the rule through
     `AlertRoutes.parseContent`; the Jobs' signals); `TelcoRaLinksJobGoldenTest` (runs the counts Job after the link Jobs;
     the figures above and the rule fires; without the story's SIMs it would not; the story's SIMs alone give 300; the
     test SIMs alone are seen and never counted; the late-billed and dropped alone are never counted; with September's
     billing not yet landed the count stays 300); the gallery test pins `la-telco-ra` creatable with no missing feature
     and no Demo Auth; `tools/seed-la-telco-ra.test.mjs` (mocked fetch, 12 tests, including the shared roles byte for byte
     as in `la-card`). Falsified, each then restored: the known list not excluded, the "still unbilled" condition dropped,
     the billing-landed gate dropped, `masking_mode: typed`, `telcora-analyst` given the approve capability,
     `fraud-analyst` removed, `la-settings` given a second capability, `admin` given `telcora-analyst`, `la-telco-ra` out
     of the gallery's Basic-tier set; in the seed the outsider made a member, the Draft annotating `DEV-00086`, the test
     dealer seeded, the expand narrowed below the dealer's 336, `la-viewer` given a capability.
   - **Not checked:** any live run. No Preview build with Demo Auth (the rule armed and firing at 300, the seed's
     `--apply`, the persona probes) and no Basic bundle (rule unarmed, `telco_ra_unbilled_alerts` reporting no breach, 0
     rejected); the expand's `linkKinds` and the Draft annotation are as the API reads them, not observed. Also not
     checked: the Link Analysis UI, the composed *Telecom* Space with `la-mfs`'s roster (its Advanced tier is a parallel
     lane; the merge was checked with `la-card`'s and `la-ownership`'s rosters through the packer's `mergeDemoUsers` /
     `mergeRoles`), an OIDC build, and the packed downloads of a commit holding this tier (the packer reads HEAD).

**Docs, with each Space**

9. **Product hub** `docs/okf/product/solution-pack/<id>/`: brochure, user guide (tour, exercises with answers),
   admin guide (install steps). Each lands in the same lane as its Space. Also: the catalogue page
   (`solution-pack/la-showcase/user-guide-showcase-catalogue.md`), a Product Map Item per new Space, BACKLOG
   rows closed, `docs/INDEX.md`, and `node tools/render-product-docs.mjs`.
   **Catalogue page BUILT 2026-11-08:** all 18 Spaces by industry, with what each teaches, its stories, its edition
   tiers (Advanced tier: built for the existing four, planned for the other thirteen until wave D lands), its zipped
   data-download size (packed from master the same day; every Space zip is under 40 KB) and a link to its user guide
   and install guide; plus install, where to start, and the industry Spaces in progress. Linked from the la-showcase user
   guide.
10. **Release:** build all 18 Space and 18 data downloads from one commit and publish them with the product.

## Open questions

- Version scheme for Space and Data downloads: follow the product version, or each Space on its own?
- Where downloads are published (internal share, website, release page)?
- Police wording for the target market (UK, US, India)? Default is neutral wording with local terms only in the
  user guide.
- Water-utility variant later?
- One combined "all showcases" download (up to about 900 MB of data at the 50 MB cap per Space) as well as
  per-Space downloads?
- Wave C order follows likely customer demand (cards and KYC first); change it if a deal needs a vertical sooner.
