# Link Analysis mobile money — architecture

The `la-mobile-money` Space Template (`spaces/_templates/la-mobile-money/template.toon`). Shared studio, index and store seams: [la-showcase architecture](../la-showcase/architecture.md) and [la-core architecture](../../module/la-core/architecture.md). This page covers what is specific to mobile money.

## Data flow

1. Three Collectors and Pipelines land `wallet_txn` (14 daily files), `wallet_master` and `agent_master`. Staged next-day files in `data/staged/wallet_txn` are copied into `data/inbox/wallet_txn` by hand.
2. The `mm_links` Job (every 10 minutes) folds them into one link Dataset: 5 777 rows (5 732 transactions plus 45 shared pairs).
3. `la_index_build` runs on a cron five minutes after `mm_links` (the index-build Job type fires on a clock or a Pipeline commit, not on another Job's output); `la_detect` runs the standing detection.
4. Investigations, members, Entity Lists, a Case and the bound `mm-structuring` Alert Rule are runtime state from `node tools/seed-la-mm.mjs --apply`.

## Datasets

| Dataset | Role |
|---|---|
| `wallet_txn_dataset`, `agent_master_dataset` | landed feeds |
| `mm_links_dataset` (`src`, `dst`, `kind`; `ts`, `amount`, `via`) | link kinds `p2p`, `cash_in`, `cash_out`, `merchant_pay`, `shared_device`, `shared_id` |
| `mm_kyc_dataset` | masked KYC reference (Enrichment on the Entity profile) |

Placeholder handsets (`UNKNOWN`, blank, all-zero) are excluded explicitly by the Job.

## Settings and access

- `config/link-analysis.toon`: four-eyes fan-out threshold 100, link index ON, Drafts. `masking_mode` is **not stated** (plan wave B, step 5, Basic tier). The edition default holds (`EditionMaskingDefault`): `typed` on any build where people sign in, `none` on Basic, where nobody can reveal and a masked graph would be unreadable.
- **Tiers.** The Basic tier is the three Pipelines, `mm_links`, `la_index_build`, the Datasets and the saved views. The Advanced tier is masking, four-eyes, Drafts, `la_detect`, the roster and roles, the portal and the seed script's runtime state. On Basic the Advanced tier is inert, never an error: the Space ignores `demo-users.toon` (demo sign-in is not in the build), `la_detect` has no bound Alert Rule to evaluate, and the template gallery accepts the template despite its roster, because `minEdition: basic` marks the roster as the inert Advanced tier (`SpaceManager.missingFeatures`, operator 2026-11-08).
- Saved views: `mm_money_trail`, `mm_structuring_band`, `mm_device_farm`.
- Demo Users: `mm.analyst`, `mm.manager`, `mm.reviewer`, `mm.outsider`, `admin`. `portal/regulator-portal.html` is the embed host page.
