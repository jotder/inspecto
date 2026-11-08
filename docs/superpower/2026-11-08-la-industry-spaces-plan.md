# Link Analysis industry Spaces — one Space per industry, mapped Datasets, use-case view names (plan)

*Status: approved 2026-11-08; step 1 (the *Utilities* spike) built. Product Map Items: the `la-*`
solution packs; the videos that use them: `docs/okf/product/module/la-core/brochure-video-scripts.md`.*

## Goal

The operator, 2026-11-08, after reviewing the 3.4 video draft:

1. **A Space per industry, named after the industry** (*Telecom*, *Banking and finance*, *Insurance*, *Utilities*,
   *Law enforcement*, *Public sector*, *Healthcare*, *Logistics*, *Enterprise*), holding every use case of that industry.
2. **Every relevant Dataset mapped in the Space:** each id column says what it holds (an Entity Type), and each link
   Dataset has a Mapping, so the Data page shows the data mapped, not *Detail*.
3. **Saved views named as short, self-explanatory use cases** (`meter_tampering_contractor`, not
   `utility_contractor_network`).

## What exists today (measured 2026-11-08)

- 15 showcase templates under `spaces/_templates/la-*`, one per use case, each with its own feeds, Pipelines, link Job,
  saved views, data, user guide and admin guide; 12 are content packs in `offerings/basic.toon`.
- About 35 code and test files cite a template path; 16 golden tests pin a template's Job output or figures
  (`*LinksJobGoldenTest`, `La*GoldenTest`, `ControlApiLaShowcaseFiguresTest`, `ControlApiSpaceSamplesTest`).
- Name collisions if templates are merged as they are:
  - **Banking:** `company`, `payment` and `mule` feeds and Datasets appear twice.
  - **Public sector:** `person` appears twice.
  - **Telecom:** `la_detect_job` and `la_index_build_job` appear twice.
- Editions: `la-showcase`, `la-mobile-money` and `la-audit` are still `minEdition: advanced`; the rest are Basic.
  Telecom and Banking would mix the two.
- Most showcases set no Entity Types: their columns read *Detail*, and a Job writes one `src`/`dst` link Dataset with no
  Mapping. `la-sample` (account columns), `la-ownership`, `la-trade`, `la-claims`, `la-audit` and `la-mobile-money`
  already classify their link columns.

## Decisions proposed

**D1 — Industry templates are composed, not hand-merged.** A tool, `tools/compose-industry-space.mjs`, builds
`spaces/_templates/<industry>/` from the use-case templates. It copies each one's config, data and saved views under one
`space.toon`, and puts a use-case prefix on every name that would collide (`card_payment`, `trade_payment`,
`procurement_person`). The use-case templates stay the source of truth, so the 16 golden tests keep testing them
unchanged. One new test per industry template proves it boots, lands every feed with nothing rejected, and opens every
saved view. *Alternative:* replace the use-case templates with nine merged ones and move every golden test. That is
more churn, and it loses the small per-use-case downloads.

**D2 — Space ids and names.** Ids `la-telecom`, `la-banking`, `la-insurance`, `la-utilities`, `la-law-enforcement`,
`la-public-sector`, `la-healthcare`, `la-logistics`, `la-enterprise`. Display names are the industry names above.
The per-use-case templates stay in the gallery, or are hidden from it: the operator decides.

**D3 — Edition.** An industry Space's Basic tier holds its Basic use cases. The Advanced-only use cases (`la-showcase`,
`la-mobile-money`, `la-audit`) join their industry Space when the plan's wave B gives them a Basic tier. Until then,
*Telecom* ships revenue assurance only, *Banking and finance* ships sample, ownership, card and trade, and *Enterprise*
waits. *Alternative:* industry Spaces take the highest edition among their parts, so *Telecom* and *Banking* become
Advanced-only.

**D4 — Mapping.** In each use-case template, every id column says what it holds. A shared Entity Type set per
industry is declared once in the industry Space's `link-analysis.toon` (for example *Premise*, *Transformer*,
*Contractor*, *Account*, *Bank account*). Each link Dataset gets a Mapping (*From* `src`, *To* `dst`, *Kind* `kind`),
so it appears mapped on the Data page and in the Start box. A Space with several link Datasets also gets one combined
graph model (*Entity/Link (several Datasets)*), so one id is one node across use cases. Product confirms how a link
Dataset whose ids are mixed (premise, contractor and account in one `src` column) declares its Entity Types: per link
kind, or one generic type plus the id prefix. Settle this with a spike on `la-utility` before the other packs.

**D5 — Use-case view names.** Rename every saved view to a short use case, in the use-case template (the source).
Update its user guide, admin guide, golden tests and the video scripts in the same change. Proposed names, one industry
as the pattern:

| Today | Proposed |
|---|---|
| `utility_contractor_network` | `meter_tampering_contractor` |
| `utility_churn_by_bank` | `account_churn_by_bank_account` |
| `utility_vendor_network` | `prepaid_token_fraud_vendor` |

The full list goes in this plan, one table per industry, for the operator to approve before any rename.

**D6 — Downloads.** One Space download and one data download per industry
(`inspecto-la-space-la-utilities-<version>.zip`); `tools/package-la-showcase.mjs` packs the composed template like any
other.

## Use-case view names (step 2, approved by the operator 2026-11-08)

Rules:
- A name says the use case a viewer will find, in a few words, in `snake_case`, with no Space or industry prefix: the
  Space already says the industry.
- A name is unique inside its industry Space, because composing refuses two use cases that ship the same file name.
- Geo Views end in `_map`.
- Where a view holds two stories, the name leads with the first, and its description keeps both.
- *Utilities* is done (step 1).

**Telecom** (`la-telco-ra` now; `la-mobile-money` and the telecom part of `la-showcase` after wave B)

| Use case | Today | Proposed |
|---|---|---|
| Revenue assurance | `telco_ra_unbilled_usage` | `unbilled_usage` |
| Revenue assurance | `telco_ra_commission_spikes` | `dealer_commission_fraud` |
| Revenue assurance | `telco_ra_pbx_off_hours` | `pbx_hacking_off_hours` |
| Mobile money | `mm_money_trail` | `wallet_money_trail` |
| Mobile money | `mm_structuring_band` | `agent_structuring` |
| Mobile money | `mm_device_farm` | `wallet_device_farm` |
| Telco fraud | `telecom_fraud_network` | `suspect_number_to_sim_box` |

**Banking and finance** (`la-sample`, `la-ownership`, `la-card`, `la-trade` now; the mule part of `la-showcase` after wave B)

| Use case | Today | Proposed |
|---|---|---|
| First look | `mule_layering_ring` (`la-sample`) | `sample_mule_ring` |
| Ownership and sanctions | `ownership_hidden_owner` | `hidden_beneficial_owner` |
| Ownership and sanctions | `ownership_sanctions_path` | `sanctioned_owner_path` |
| Ownership and sanctions | `ownership_nominee_director` | `nominee_director` |
| Ownership and sanctions | `ownership_circular` | `circular_ownership` |
| Card fraud | `card_testing` | `card_testing` (already a use case) |
| Card fraud | `card_takeover_ring` | `account_takeover_ring` |
| Card fraud | `card_merchant_disputes` | `colluding_merchant_refunds` |
| Card fraud | `card_common_point` | `common_point_of_purchase` |
| Trade finance | `trade_over_invoicing` | `over_invoicing` |
| Trade finance | `trade_phantom_shipment` | `phantom_shipment` |
| Trade finance | `trade_circular_trade` | `circular_trade` |
| Trade finance | `trade_shell_importers` | `shell_importers` |
| Financial crime tour | `mule_layering_ring` (`la-showcase`) | `mule_layering_ring` |
| Financial crime tour | `mule_large_transfers` | `mule_ring_large_transfers` |
| Financial crime tour | `mule_structuring` | `structuring_smurfing` |
| Financial crime tour (map) | `mule_account_locations_map` | `mule_account_locations_map` |
| Financial crime tour (map) | `mule_ring_route_map` | `mule_ring_route_map` |
| Financial crime tour (map) | `mule_cashout_till_map` | `cash_out_till_map` |

**Insurance** (`la-claims`)

| Today | Proposed |
|---|---|
| `claims_network` | `claims_network` (the overview; kept) |
| `claims_shared_identity` | `claimant_ring_shared_identity` |
| `claims_provider_money` | `provider_money_trail` |
| `claims_parts_suppliers` | `parts_supplier_ring` |
| `claims_premium_payers` | `ghost_broker_premium_payers` |
| `claims_accidents_map` | `accidents_map` |
| `claims_junction_cluster_map` | `staged_accident_junction_map` |
| `claims_providers_map` | `providers_map` |

**Law enforcement** (`la-police`)

| Today | Proposed |
|---|---|
| `police_burglary_series` | `burglary_series` |
| `police_handset_network` | `drug_supply_handset_network` |
| `police_witness_link` | `suspicious_witness` |
| `police_incident_map` | `incident_map` |

**Public sector** (`la-procurement`, `la-benefits`)

| Use case | Today | Proposed |
|---|---|---|
| Procurement | `procurement_bid_rotation` | `bid_rotation` |
| Procurement | `procurement_cover_bids` | `cover_bids_shared_ip` |
| Procurement | `procurement_change_approvals` | `official_approving_relative_firm` |
| Procurement | `procurement_debarred_directors` | `debarred_firm_reborn` |
| Benefits and tax | `benefits_shared_addresses` | `address_farm` |
| Benefits and tax | `benefits_paid_after_death` | `paid_after_death` |
| Benefits and tax | `benefits_work_while_claiming` | `undeclared_work` |
| Benefits and tax | `benefits_refund_concentration` | `tax_preparer_refund_ring` |

**Healthcare** (`la-health`)

| Today | Proposed |
|---|---|
| `health_referral_concentration` | `referral_kickbacks` |
| `health_phantom_billing` | `phantom_billing` |
| `health_pill_mill` | `pill_mill` |
| `health_upcoding` | `upcoding_clinics` |

**Enterprise** (`la-audit`, after wave B)

| Today | Proposed |
|---|---|
| `ap_approval_graph` | `invoice_approvals` |
| `ap_vendor_identity` | `vendor_employee_conflict` |
| `gl_intercompany` | `intercompany_round_trip` |

**Decisions this table needs:**

- **`la-showcase` spans two industries.** Its telecom view (and its eight telecom feeds) belong to *Telecom*, its mule
  views and Geo Views to *Banking and finance*. The recipe takes whole templates, so either `la-showcase` is split into
  two use-case templates before it joins (recommended, with wave B), or it goes whole into *Banking and finance* and
  *Telecom* points at it.
- **`mule_layering_ring` is in two templates** (`la-sample`, `la-showcase`). Renaming the sample's view to
  `sample_mule_ring` lets both sit in *Banking and finance*. Their Datasets (`mule_transfers_dataset`) and feeds collide
  too, so the sample's need the same prefix.
- **Renames touch peers' golden tests and guides:** one lane per industry renames its views, the tests that cite them,
  the user and admin guides and the video scripts, as the *Utilities* spike did.

## Steps

1. **Spike, `la-utility` to *Utilities* (one industry end to end):**
   - Entity Types and a Mapping on its Datasets;
   - the three views renamed;
   - the compose tool writes `la-utilities`;
   - boot on a Basic install, check the Data page shows the mapping, and open every view;
   - re-record the 3.4 draft on it.

   Exit: the operator approves the look.
2. **The view-name tables** for all industries in this plan: approved 2026-11-08 (section above).
3. **One lane per industry,** in order *Insurance*, *Law enforcement*, *Healthcare*, *Public sector* (with the `person`
   collision), *Banking and finance* (Basic parts), *Telecom* (revenue assurance). Each lane:
   - mapping, renames and the composed template;
   - its tests;
   - user and admin guides, and the scripts;
   - pushed on its own.
4. **Advanced-only packs** join when wave B flips them.
5. **Docs:** product hubs per industry Space, the showcase catalogue, `offerings/basic.toon` content packs, and the
   video scripts (Space names, view names, the Map beat now on the real *Entities* screen).

### Step 1 as built (spike, 2026-11-08) — *Utilities*

The operator approved this plan and the spike on 2026-11-08 ("Approve, start spike").

- **Composition lives in the packer, not as committed copies.** `spaces/_industries/la-utilities.toon` is a recipe:
  `name`, `tagline`, `minEdition`, `icon` and `useCases[n]`.
  - `tools/package-la-showcase.mjs <industry-id>` composes from committed content. `composeSources` refuses a path two
    use cases both ship, and `mergeEntityTypes` merges `config/link-analysis.toon` by id, refusing a differing
    re-declaration.
  - Committed copies would have duplicated 3.7 MB of data per industry, about 25 MB in all, with two sources free to
    drift. Three new cases in `tools/package-la-showcase.test.mjs`.
- **Mapping as built** (`la-utility`):
  - Column `classification` plus `primaryEntityType` on every Dataset, and `defaultLinkMapping` (`from`, `to`, `kind`,
    `primaryTime`, `primaryMeasure`) on every Dataset of events. A new `config/link-analysis.toon` declares *Premise*,
    *Transformer*, *Contractor*, *Vending point*, *Account*, *Bank account* and *Utility entity*.
  - The link Datasets mix kinds in one `src`/`dst` column, so their ends hold the generic *Utility entity* (D4 settled
    this way).
  - The Data page then reads, for example, "`premise_id` holds Premise", "`contractor_id` holds Contractor", and the
    Dataset's own Mapping "premise_id → contractor_id · kind visit_type · when visit_at".
- **Views renamed:** `utility_contractor_network` → `meter_tampering_contractor`, `utility_churn_by_bank` →
  `account_churn_by_bank_account`, `utility_vendor_network` → `prepaid_token_fraud_vendor`.
  - Updated in the Job and Dataset descriptions, `UtilityLinksJobGoldenTest`, the user and admin guides, the showcase
    plan and the video scripts.
  - Each view also holds a second story (the contractor view shows the illegal reconnections too; the churn view shows
    the commercial-load homes). The names lead with the first.
- **Proved on a Basic install (2026-11-08):** composed from the working tree with the packer's own functions and
  booted.
  - The switcher reads *Utilities*, all feeds landed and the three renamed views open.
  - *Busiest nodes* over the pasted 22 low meters still gives `C-14` 22 links, `TX-007` and `TX-019` 11 each.
  - Targeted tests PASS: `IndustryPacksTest`, `UtilityLinksJobGoldenTest`, `RefusalQuarantineTest`,
    `ShippedPipelinesWriteUnderTheirSpaceDirectoryTest`, `ControlApiSpaceTemplatesTest`,
    `ControlApiSpaceTemplateSeedGateTest`, `RepoSpacesConfigValidationTest`, `SpaceManagerTemplateGateTest` and
    `TemplateGovernanceExamplesTest`. The full gate was NOT run.
- **Gotcha for the next packs:** mapped Datasets add *Preset queries* to the Start box and change its default Dataset, so
  a script or recorder must pick the link Dataset explicitly.
- **Verified independently (PASS, 2026-11-08).** The review's fix landed: a recipe naming a missing use case is now
  refused, where it was silently skipped. Its notes for steps 2–5:
  - `mergeEntityTypes` accepts only an `entity_types`-only `link-analysis.toon`; `la-claims` also states
    `four_eyes_fan_out_above` and `index:`, so composing it needs those keys handled first.
  - The same classification claimed under two type ids by two use cases is caught only at boot (`EntityTypes.validate`),
    not by the packer.
  - No test checks a template's Dataset entries against `DatasetEntryRules` and the declared types; add one with the
    next pack.
  - A real collision exists today: `config/examples/sla-escalation-examples.md` ships in both `la-utility` and
    `la-claims`.
- **Not done in the spike:** the bundle's in-app template gallery (`package.ps1` stages only `spaces/_templates`, so
  `la-utilities` is not in the gallery yet), and the D2 choice to hide the per-use-case template.

### Step 3, first lane as built (2026-11-08) — *Insurance*

- Recipe `spaces/_industries/la-insurance.toon` (use case `la-claims`).
- The packer ships a lone use case's `config/link-analysis.toon` verbatim. `la-claims`' file also holds four-eyes,
  index, embed and drafts settings, which a merge would refuse. Merging still applies only when several use cases
  ship one.
- Mapping:
  - Claimants and providers stay the one masked *Party*.
  - *Claim*, *Policy*, *Broker* and *Accident* are new and unmasked: they name no person.
  - Seven Datasets of events got their own Mapping; the three accident Datasets and the provider directory are typed
    only. `LaClaimsTemplateTest` now pins the five types.
- Views renamed per the step 2 table (`claims_network` kept as the overview), in the templates, three tests'
  references, `tools/la-claims-smoke.mjs` and the docs.
- Proved on the Basic install: the Space boots as *Insurance* and all seven feeds land with 0 rejected.
  `claim_party_dataset` reads "`claim_id` holds Claim" and "`party_id` holds Party".
  - `claims_links` runs on its 10-minute schedule, so the link views stay empty until its first run after boot (as
    before).
- Targeted tests PASS:
  - entity-store, engine and graph: `IndustryPacksTest`, `LaClaimsLinksJobTest`, `LaClaimsGoldenTest`;
  - processor: `LaClaimsTemplateTest`, `RepoSpacesConfigValidationTest`, `ControlApiSpaceTemplatesTest`,
    `ControlApiSpaceTemplateSeedGateTest`, `SpaceManagerTemplateGateTest`,
    `ShippedPipelinesWriteUnderTheirSpaceDirectoryTest`;
  - geo-link: `ControlApiLaClaimsGeoSceneTest`.

  The full gate was NOT run.

## Open questions for the operator

- D1: compose (recommended) or replace the use-case templates?
- D2: keep the per-use-case templates visible in the gallery, or show only the industry Spaces?
- D3: Basic parts only (recommended), or Advanced-only *Telecom* and *Banking*?
- D5: approve the naming pattern from the *Utilities* table before the other tables are written.
