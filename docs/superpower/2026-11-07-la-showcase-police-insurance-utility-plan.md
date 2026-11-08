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
| MFS (mobile financial services fraud) | `la-mfs` | SIM-swap wallet takeover through one call-centre login; agent split-deposit commission fraud; merchant cash-out collusion (spec 14) | planned |
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
| Crypto laundering | `la-crypto` | Peel chain from a hacked wallet; mixer hop matched by timing and amount; 40 accounts cashing out one cluster's funds | planned |

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
| Cargo theft | `la-logistics` | Losses recurring with one driver pair and one night-shift scanner; an unscheduled-stop hotspot; a fictitious carrier taking high-value loads | planned |

### Enterprise

| Use case | Template | Stories | Status |
|---|---|---|---|
| Internal audit, accounts payable | `la-audit` | Vendor V-117 shares bank account and address with employee E-31; 5 vendors on one bank account plus an unlinked sixth; 30 invoices just under the 10 000 limit; one approver bridging the approval graph; intercompany money at quarter ends; 13 duplicate payments | exists |
| Insider threat (HR and security) | `la-insider` | A leaver's bulk reads and mail to a personal domain; access creep granted by one manager; two employees approving each other's expenses | planned |

**Count: 18 Spaces in 9 industries.** On 2026-11-08, 13 exist (`la-sample`, `la-showcase`, `la-mobile-money`,
`la-claims`, `la-audit`, `la-ownership`, `la-police`, `la-utility`, `la-card`, `la-procurement`, `la-trade`, `la-benefits`,
`la-telco-ra`) and 5 are planned; the `la-claims`
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
  `paid_by` 29). Saved views `claims_parts_suppliers` and `claims_premium_payers`; `claims_shared_identity` excludes
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
  views: `utility_contractor_network` and `utility_vendor_network` filter the link Dataset by kind,
  `utility_churn_by_bank` reads `utility_bank_links`; one
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
- **Canvas cap (fixed 2026-11-08):** `utility_churn_by_bank` first filtered the whole link Dataset to `account_at` and
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
  and 1 000 nodes, the story whole and connected, and the look-alike on the canvas: `procurement_bid_rotation` 1 291 rows, 512 nodes; `procurement_change_approvals` 700 rows, 556 nodes; `procurement_cover_bids` 20 rows, 19 nodes; `procurement_debarred_directors` 27 rows, 36 nodes.
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
  story whole and connected, and the look-alike on the canvas: `benefits_shared_addresses` 366 rows, 320 nodes;
  `benefits_paid_after_death` 132 rows, 148 nodes; `benefits_work_while_claiming` 166 rows, 208 nodes;
  `benefits_refund_concentration` 592 rows, 520 nodes.
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
  `health_referral_concentration` 22 rows, 23 nodes; `health_phantom_billing` 70 rows, 87 nodes; `health_pill_mill` 385
  rows, 149 nodes; `health_upcoding` 8 rows, 12 nodes.
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
  look-alikes unchanged, `health_upcoding` gains a few elderly-care clinics).

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
  component: `telco_ra_unbilled_usage` 704 rows, 380 nodes; `telco_ra_commission_spikes` 2 404 rows, 768 nodes;
  `telco_ra_pbx_off_hours` 20 rows, 19 nodes.
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

**Docs, with each Space**

9. **Product hub** `docs/okf/product/solution-pack/<id>/`: brochure, user guide (tour, exercises with answers),
   admin guide (install steps). Each lands in the same lane as its Space. Also: the catalogue page
   (`solution-pack/la-showcase/user-guide-showcase-catalogue.md`), a Product Map Item per new Space, BACKLOG
   rows closed, `docs/INDEX.md`, and `node tools/render-product-docs.mjs`.
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
