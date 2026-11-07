# Link Analysis showcase catalogue — police, insurance, utility and fourteen more (plan)

Status: draft, 2026-11-07; scope widened the same day to the **full catalogue** (operator: "ship the full
Showcase catalogue for users to play, learn and test"). Product Map Items: existing `solution-pack.la-claims`,
`solution-pack.la-showcase`, `solution-pack.la-mobile-money`, `solution-pack.la-audit`; one new
`solution-pack.la-<id>` per new Space, added when its template lands.

## Goal

**Ship every Space in the catalogue below: 17 showcase Spaces**, each with synthetic data and planted stories, so
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

| Template | Industry | Use case | Stories | Status |
|---|---|---|---|---|
| `la-sample` | Banking | First-run sample (Basic) | 12 smurf accounts structure cash into MULE-HUB-01 → RELAY-01/02 → OFFSHORE-77; SHELL-A..D money loop | exists |
| `la-showcase` | Banking + telecom | Financial crime and telecom fraud tour | Mule ring and shell loop; TILL-06 takes 80 % of cash-out; SKIMMER-01 skims benefit payments; telecom rings for Wangiri, IRSF, SIM box, subscription fraud and mules; a 650-caller supernode; geo route of the ring | exists |
| `la-mobile-money` | Mobile money | Anti-money-laundering | Agent-assisted structuring (25 wallets via AG-18); 98 % pass-through mule chain to AG-39; nine-wallet device farm; four-wallet directed cycle; dormant wallet awakening; benign biller hub MER-ELECTRIC | exists |
| `la-claims` | Insurance | Claims fraud (Special Investigations Unit) | 12 claimants sharing 4 phones, 3 addresses, one garage, clinic and lawyer; a passenger on 6 claims with 6 drivers; 11 night-time accidents near one junction; ring clinic bills 3× peers; 7 claims within 20 days of inception via one broker; duplicate claimant identity | exists |
| `la-claims` (additions) | Insurance | Claims fraud | Parts-supplier ring (one supplier on invoices from many garages, parts never fitted); ghost broker (policies sold with false details, all paying from 2 bank accounts) | planned |
| `la-audit` | Finance / audit | Internal audit, accounts payable | Vendor V-117 shares bank account and address with employee E-31; 5 vendors on one bank account plus an unlinked sixth; 30 invoices just under the 10 000 limit; one approver bridging the approval graph; intercompany money at quarter ends; 13 duplicate payments | exists |
| `la-police` | Law enforcement | Criminal investigation | Burglary series traced through second-hand-dealer sales and one vehicle; drug-supply handset network with a rotating controller phone; addresses taken over as drug bases; a "witness" who was arrested with the suspect | planned |
| `la-utility` | Electricity | Revenue protection (non-technical loss) | Meter tampering linked to one contractor's visits, seen as loss on two transformers; illegal reconnections closed by one contractor; debt-dodging account churn; commercial load on a residential tariff; prepaid token fraud from one vending point | planned |
| `la-card` | Payments / e-commerce | Card and e-commerce fraud | Card testing from one device and IP range; account-takeover ring shipping to 5 drop addresses; colluding merchant refunding cards it never sold to; common point of purchase behind 60 compromised cards | planned |
| `la-ownership` | Banking / compliance | Beneficial ownership and sanctions | Hidden owner behind a five-layer chain of 24 % stakes; a sanctioned person two hops above a customer; one nominee director on 120 companies; circular ownership | planned |
| `la-trade` | Trade finance | Trade-based money laundering | Over-invoicing between companies sharing a director; containers going round in a circle; a phantom shipment on a vessel that was elsewhere; shell importers at one formation agent | planned |
| `la-procurement` | Public sector / enterprise | Bid rigging and procurement fraud | Four firms winning in turn; cover bids from one IP address; an official approving changes for a relative's firm; a debarred firm reborn under its old directors | planned |
| `la-benefits` | Government | Benefits and tax fraud | 14 claimants at one address; payments to the deceased; claimants on an employer payroll; a tax preparer's refund ring | planned |
| `la-health` | Healthcare (payer) | Provider billing fraud | Referral kickback ring to an imaging centre owned by a doctor's spouse; phantom billing for admitted or deceased patients; pill mill; upcoding clinics under one owner | planned |
| `la-telco-ra` | Telecom | Revenue assurance | Unbilled usage from one dealer's SIMs; dealer commission fraud with SIMs that go dormant; PBX hacking at night (SIM box, Wangiri and IRSF stay in `la-showcase`) | planned |
| `la-logistics` | Logistics | Cargo theft | Losses recurring with one driver pair and one night-shift scanner; an unscheduled-stop hotspot; a fictitious carrier taking high-value loads | planned |
| `la-insider` | Enterprise / HR | Insider threat | A leaver's bulk reads and mail to a personal domain; access creep granted by one manager; two employees approving each other's expenses | planned |
| `la-crypto` | Crypto | Laundering | Peel chain from a hacked wallet; mixer hop matched by timing and amount; 40 accounts cashing out one cluster's funds | planned |

**Count: 17 Spaces** (5 exist, 12 new); the `la-claims` additions land inside `la-claims`.

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
`minEdition: advanced` today) get the same split: `minEdition` drops to `basic` and anything a Basic build refuses
moves into the Advanced tier. Whether the Advanced tier really is inert on Basic (personas, Alert Rules, seed
scripts present but unused) is **not yet proven**; wave B proves it on `la-claims` first.

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

### Specs 4–13: the wave C Spaces

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

## Steps

1. **Distribution seams:** `SPACES_DATA_ROOT` in the launchers; the packer
   `tools/package-la-showcase.mjs`; the version check; test with `la-claims` split into its two downloads →
   verify: unzip the product, Space and data into one parent folder, start Basic, the Space loads and its
   Pipelines land the data.
   **BUILT 2026-11-07 (`5412fddfd`).**

**Wave A: the first new Spaces (in flight)**

2. **`la-police`:** generator, golden tests, template (Basic tier) → verify: loads on a Basic build; every story
   is visible from its saved view; no look-alike joins a story.
3. **`la-utility`:** same as step 2.
4. **`la-claims` additions:** parts-supplier ring and ghost broker → same check.

**Wave B: the existing five ship in the new layout**

5. **Basic tier for the existing four** (`la-claims` first, then `la-showcase`, `la-mobile-money`, `la-audit`):
   `minEdition: basic`; move whatever Basic refuses into the Advanced tier → verify: each loads, lands its data
   and is explorable on a Basic build, and still passes its smoke (`la-claims-smoke`, `la-mm-smoke`,
   `la-audit-smoke`) on Preview.
6. **Pack and boot-test all five** with `tools/package-la-showcase.mjs`. `la-sample` keeps its files in
   `data/samples` for the in-app *Load samples*; check that this still works from a data root.

**Wave C: ten new Spaces** (specs 4–13), one lane each, in this order: `la-card`, `la-ownership`, `la-trade`,
`la-procurement`, `la-benefits`, `la-health`, `la-telco-ra`, `la-logistics`, `la-insider`, `la-crypto`.

7. **Each Space:** generator, golden tests, template (Basic tier), packed downloads → same check as step 2.

**Wave D: Advanced tiers**

8. **Advanced tier** for every new Space (personas, Alert Rule, seed script) → verify on a Preview build with
   demo sign-in.

**Docs, with each Space**

9. **Product hub** `docs/okf/product/solution-pack/<id>/`: brochure, user guide (tour, exercises with answers),
   admin guide (install steps). Each lands in the same lane as its Space. Also: the catalogue page
   (`solution-pack/la-showcase/user-guide-showcase-catalogue.md`), a Product Map Item per new Space, BACKLOG
   rows closed, `docs/INDEX.md`, and `node tools/render-product-docs.mjs`.
10. **Release:** build all 17 Space and 17 data downloads from one commit and publish them with the product.

## Open questions

- Version scheme for Space and Data downloads: follow the product version, or each Space on its own?
- Where downloads are published (internal share, website, release page)?
- Police wording for the target market (UK, US, India)? Default is neutral wording with local terms only in the
  user guide.
- Water-utility variant later?
- One combined "all showcases" download (up to about 850 MB of data at the 50 MB cap per Space) as well as
  per-Space downloads?
- Wave C order follows likely customer demand (cards and KYC first); change it if a deal needs a vertical sooner.
