---
type: Feature
title: "Link Analysis crypto laundering showcase — install"
description: "Install the la-crypto showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-crypto, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis crypto laundering showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-crypto-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-crypto-<version>.zip` (about 5 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-crypto/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-crypto/data/inbox/` and `la-crypto/data/staged/`.
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

On start-up the six Pipelines land the twelve months in `data/inbox`; the `crypto_links` Job runs after each load (and
every 5 minutes, so the last load is never missed), and each time it finishes the `crypto_screen_links` Job runs. Within a
minute or two:

- `inspecto-la-data/la-crypto/data/<feed>/database/` holds Parquet files for `wallet`, `transfer`, `cluster_hint`,
  `exchange_account`, `kyc_person` and `cashout`, and nothing is under any `<feed>/quarantine` or `<feed>/errors`.
- `inspecto-la-data/la-crypto/data/crypto_links/` holds the link Dataset (about 39 500 rows, six kinds);
  `crypto_screen_links/` holds the three screens the saved views read.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `hack_peel_chain`, `mixer_hop_timing_match` and
  `converging_cash_out`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-crypto/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-crypto/data/staged/<feed>/` into `inspecto-la-data/la-crypto/data/inbox/<feed>/`
(`wallet`, `transfer`, `cluster_hint`, `exchange_account`, `kyc_person`, `cashout`). The Pipelines land them and the Jobs
rebuild the links. October brings about 3 900 transfers, 275 new addresses and a month of cash-outs, and one more payroll
run. There is no new hack, mixer hop or cash-out ring: the planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-crypto/`, and unzip the data download again. The Space configuration is
untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-crypto-data.py
node --test tools/gen-la-crypto-data.test.mjs
node tools/package-la-showcase.mjs la-crypto
```

The generator is deterministic (seed 20261113) and writes the ground truth to `tools/gen-la-crypto-data.ground-truth.json`,
which is not part of any download. The packer reads committed content only, so commit first.

## As part of the *Banking and finance* industry Space

This use case also ships inside the industry Space *Banking and finance* (`la-banking`), with the sample, ownership,
card, trade and crypto showcases. The recipe is `spaces/_industries/la-banking.toon`; `node tools/package-la-showcase.mjs
la-banking` composes one Space download and one data download.

To share one Space, trade's `company` and `payment` feeds are called `trade_company` and `trade_payment` (Pipelines,
Datasets, inbox folders). *Account*, *Company* and *Person* are declared identically by every pack that uses them, so
they merge. Every Dataset says what its id columns hold, and each Dataset of events has its own Mapping. In each link
Dataset, the ends mixing kinds keep the pack's own generic type: *Party* (ownership), *Card entity*, *Trade entity* or
*Crypto entity*.

Views carry use-case names, for example `hidden_beneficial_owner`, `sanctioned_owner_path`, `common_point_of_purchase`,
`circular_trade` and `hack_peel_chain`. The sample's `mule_layering_ring` keeps its name, because the first-run page
and *Follow the money* open it by that id.
