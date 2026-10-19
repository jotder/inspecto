# Link Analysis claims showcase — architecture

The `la-claims` Space Template (`spaces/_templates/la-claims/template.toon`). Shared studio, index and store seams: [la-showcase architecture](../la-showcase/architecture.md) and [la-core architecture](../../module/la-core/architecture.md). This page covers what is specific to claims.

## Data flow

1. Six Collectors and Pipelines (`config/claims/`) land one extract each of `claim`, `claim_party`, `policy`, `provider_bill`, `accident_location` and `provider_directory`.
2. The `claims_links` Job (10-minute cron) folds them into one link Dataset; `claims_policy_timing` joins claims to policies.
3. `la_index_build` fires when `claims_links` is produced; `la_detect` runs every 5 minutes.
4. Investigations, members, Entity Lists, identity groups, external references and a Case are runtime state from `node tools/seed-la-claims.mjs --apply`.

## Datasets

| Dataset | Role |
|---|---|
| `claim_dataset`, `claim_party_dataset`, `policy_dataset`, `provider_bill_dataset`, `accident_location_dataset`, `provider_directory_dataset` | landed feeds |
| `claims_links_dataset` (`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`) | seven link kinds: `claimed_with`, `same_accident`, `serviced_by`, `represented_by`, `referred_to`, `shares_phone`, `shares_address`; symmetric kinds stored once (`src` < `dst`); `via` names the claim, accident or shared value |
| `claims_policy_timing_dataset` | claim-to-inception timing |
| `claims_accidents_dataset`, `claims_night_unreported_dataset` | accident sets behind the Geo Views |

Placeholder phones and addresses (blank, all-zero, `UNKNOWN`) make no shared-attribute link.

## Settings and access

- `config/link-analysis.toon`: masking `typed` with one masked Entity Type `party`, a four-eyes fan-out threshold, link index ON, Drafts limits, embed `frame_ancestors`.
- Saved views: `claims_network`, `claims_shared_identity`, `claims_provider_money`. Geo Views: `claims_accidents_map`, `claims_junction_cluster_map`, `claims_providers_map`.
- Demo Users: `siu.analyst`, `siu.manager`, `partner.reviewer`, `outsider`, `admin`. `portal/partner-portal.html` is the embed host page.
