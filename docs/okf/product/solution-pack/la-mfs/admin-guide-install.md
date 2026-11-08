---
type: Feature
title: "Link Analysis mobile financial services fraud showcase — install"
description: "Install the la-mfs showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-mfs, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis mobile financial services fraud showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-mfs-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-mfs-<version>.zip` (about 11 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-mfs/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-mfs/data/inbox/` and `la-mfs/data/staged/`.
4. Start the product from its folder with both roots set, relative to the bundle:

   Windows:

   ```bat
   cd <parent>\inspecto-la
   set SPACES_ROOT=..\inspecto-la-spaces
   set SPACES_DATA_ROOT=..\inspecto-la-data
   bin\serve.bat
   ```

   Linux or macOS:

   ```bash
   cd <parent>/inspecto-la && SPACES_ROOT=../inspecto-la-spaces SPACES_DATA_ROOT=../inspecto-la-data bin/serve.sh
   ```

   The launcher changes to the bundle folder first, so both paths are relative to `inspecto-la/`. `PORT` picks
   another port than 8080. Keep `<parent>` free of spaces when you use absolute paths on Windows.

## Check that it loaded

On start-up the seven Pipelines land the twelve months in `data/inbox`; the `mfs_links` Job runs after each load (and
every 5 minutes, so the last load is never missed), and each time it finishes the `mfs_screen_links` Job runs. Within a
minute or two:

- `inspecto-la-data/la-mfs/data/<feed>/database/` holds Parquet files for `agent`, `merchant`, `wallet`, `wallet_txn`,
  `sim_event`, `device_login` and `agent_commission`, and nothing is under any `<feed>/quarantine` or `<feed>/errors`.
- `inspecto-la-data/la-mfs/data/mfs_links/` holds the link Dataset (about 47 000 rows, seven kinds);
  `mfs_screen_links/` holds the three fraud screens the saved views read.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `mfs_sim_swap_bursts`, `mfs_split_deposits` and
  `mfs_merchant_quick_pay`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-mfs/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-mfs/data/staged/<feed>/` into `inspecto-la-data/la-mfs/data/inbox/<feed>/`
(`wallet`, `wallet_txn`, `sim_event`, `device_login`, `agent_commission`). The Pipelines land them and the Jobs rebuild
the links. October brings 92 new wallets and another month of transactions, SIM events, logins and commissions. There is
no new takeover, split deposit or colluding merchant: the planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-mfs/`, and unzip the data download again. The Space configuration is
untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-mfs-data.py
node --test tools/gen-la-mfs-data.test.mjs
node tools/package-la-showcase.mjs la-mfs
```

The generator is deterministic (seed 20261111) and writes the ground truth to `tools/gen-la-mfs-data.ground-truth.json`,
which is not part of any download. The packer reads committed content only, so commit first.

## As part of the *Telecom* industry Space

This use case also ships inside the industry Space *Telecom* (`la-telecom`), beside `la-telco-ra`. The recipe is
`spaces/_industries/la-telecom.toon`, composed by `node tools/package-la-showcase.mjs la-telecom`.

Every Dataset says what its id columns hold: *Wallet*, *Agent*, *Merchant*, *Staff login* and *Settlement account*.
*Subscriber number*, *Device* and *Telecom entity* are declared exactly as `la-telco-ra` declares them, so the two
packs merge. `wallet_txn_dataset` and the link Datasets mix wallets, agents and merchants in one column, so their
ends hold *Telecom entity*.

Each Dataset of events has its own Mapping:

| Dataset | Mapping |
|---|---|
| `wallet_txn_dataset` | Telecom entity → Telecom entity (kind `type`, when `txn_at`, measure `amount`) |
| `sim_event_dataset` | Staff login → Subscriber number (kind `event`, when `event_at`) |
| `wallet_dataset` | Agent → Wallet (when `opened_at`) |
| `device_login_dataset` | Wallet → Device (when `login_at`) |
| `agent_dataset` | Agent → Agent (its super-agent) |
| `merchant_dataset` | Merchant → Settlement account |
