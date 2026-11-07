# Link Analysis showcase Spaces — police, insurance, utility (plan)

Status: draft, 2026-11-07. Product Map Items: new `solution-pack.la-police`, `solution-pack.la-utility`;
existing `solution-pack.la-claims`.

## Goal

Showcase Spaces for Link Analysis, each with synthetic data and planted stories. Each is shipped as **three
separate downloads**: the product, the Space (configuration) and the Space's data. A user installs the
product once, then adds any showcase by unzipping its Space and its data beside the install. The first three
are police, insurance and utility.

## Showcase catalogue

Status: **exists** = a template in `spaces/_templates/`; **planned** = specified below; **candidate** = not
yet specified. Every story has a benign look-alike that must not join it (listed in the per-Space specs).
Realism notes come from the review in the next section.

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
| `la-trade` | Trade finance | Trade-based money laundering | Over- and under-invoicing; circular shipments; shell importers | candidate |
| `la-procurement` | Public sector / enterprise | Bid rigging | Bidders sharing directors or addresses; rotating winners | candidate |
| `la-card` | Payments / e-commerce | Card and e-commerce fraud | Device or IP shared across accounts; card testing; colluding merchant | candidate |
| `la-benefits` | Government | Benefits and tax fraud | Many claimants at one address; payments to the deceased; tax-preparer ring | candidate |
| `la-telco-ra` | Telecom | Revenue assurance | SIM-box gateways; interconnect route abuse | candidate |
| `la-ownership` | Banking / compliance | Beneficial ownership and sanctions | Ultimate-owner chains; a sanctioned party two hops away | candidate |
| `la-health` | Healthcare | Billing fraud | Provider-patient referral ring; phantom billing | candidate |
| `la-logistics` | Logistics | Cargo theft | Driver, warehouse and route recurring together | candidate |
| `la-insider` | Enterprise / HR | Insider threat | Access shared through a manager; colluding approvers | candidate |
| `la-crypto` | Crypto | Laundering | Funds split across many wallets; mixer hops; converging exchange cash-outs | candidate |

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

## Steps

1. **Distribution seams:** `SPACES_DATA_ROOT` in the launchers; the packer
   `tools/package-la-showcase.mjs`; the version check; test with `la-claims` split into its two downloads →
   verify: unzip the product, Space and data into one parent folder, start Basic, the Space loads and its
   Pipelines land the data.
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
3. **`la-utility`:** same as step 2.
4. **`la-claims` additions:** parts-supplier ring and ghost broker → same check.
5. **Advanced tier** for `la-police` and `la-utility` (personas, Alert Rules, seed scripts) → verify on a
   Preview build with demo sign-in.
6. **Docs:** product hubs `docs/okf/product/solution-pack/<id>/` (brochure, user guide, admin guide with the
   install steps); BACKLOG rows; `docs/INDEX.md`; `node tools/render-product-docs.mjs`.

## Open questions

- Version scheme for Space and Data downloads: follow the product version, or each Space on its own?
- Where downloads are published (internal share, website, release page)?
- Police wording for the target market (UK, US, India)? Default is neutral wording with local terms only in the
  user guide.
- Water-utility variant later?
