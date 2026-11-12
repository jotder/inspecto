# Link Analysis claims showcase — architecture

The `la-claims` Space Template (`spaces/_templates/la-claims/template.toon`). Shared studio, index and store seams: [la-showcase architecture](../la-showcase/architecture.md) and [la-core architecture-backend](../../module/la-core/architecture-backend.md). This page covers what is specific to claims.

## Data flow

1. Seven Collectors and Pipelines (`config/claims/`) land one extract each of `claim`, `claim_party`, `policy`, `provider_bill`, `accident_location`, `provider_directory` and `repair_invoice`.
2. The `claims_links` Job (10-minute cron) folds them into one link Dataset; `claims_policy_timing` joins claims to policies.
3. `la_index_build` fires when `claims_links` is produced; `la_detect` runs every 5 minutes.
4. Investigations, members, Entity Lists, identity groups, external references and a Case are runtime state from `node tools/seed-la-claims.mjs --apply`.

## Datasets

| Dataset | Role |
|---|---|
| `claim_dataset`, `claim_party_dataset`, `policy_dataset`, `provider_bill_dataset`, `accident_location_dataset`, `provider_directory_dataset`, `repair_invoice_dataset` | landed feeds |
| `claims_links_dataset` (`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`) | nine link kinds: `claimed_with`, `same_accident`, `serviced_by`, `represented_by`, `referred_to`, `shares_phone`, `shares_address`, `supplied_parts` (supplier to garage; `amount` = parts not fitted), `paid_by` (policyholder to a bank account paying two or more policies); symmetric kinds stored once (`src` < `dst`); `via` names the claim, accident, policy or shared value |
| `claims_policy_timing_dataset` | claim-to-inception timing |
| `claims_accidents_dataset`, `claims_night_unreported_dataset` | accident sets behind the Geo Views |

Placeholder phones and addresses (blank, all-zero, `UNKNOWN`) make no shared-attribute link.

## Settings and access

- `config/link-analysis.toon`: one masked Entity Type `party`, a four-eyes fan-out threshold, link index ON, Drafts limits, embed `frame_ancestors`. `masking_mode` is **not stated** (plan step 5, Basic tier). The edition default holds (`EditionMaskingDefault`): `typed` on any build where people sign in, `none` on Basic, where nobody can reveal and a masked graph would be unreadable.
- **Tiers.** On Basic, the Advanced tier is inert, never an error. On a live Basic boot (2026-11-08), `la_index_build` built the index (`built (full)`) and `la_detect` ran with nothing to evaluate. The Space ignores `demo-users.toon` on Basic (demo sign-in is not in the build), and the template gallery accepts the template despite its roster, because `minEdition: basic` marks the roster as the inert Advanced tier (`SpaceManager.missingFeatures`, operator 2026-11-08). The Geo Views need the Geo map (Standard and above).
- Saved views: `claims_network`, `claimant_ring_shared_identity`, `provider_money_trail`, `parts_supplier_ring`, `ghost_broker_premium_payers`. Geo Views: `accidents_map`, `staged_accident_junction_map`, `providers_map`.
- Demo Users: `siu.analyst`, `siu.manager`, `partner.reviewer`, `outsider`, `admin`. `portal/partner-portal.html` is the embed host page.
