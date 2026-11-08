---
type: Feature
title: "Link Analysis card fraud showcase — install"
description: "Install the la-card showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-card, admin-guide, install, showcase]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis card fraud showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-card-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-card-<version>.zip` (about 3 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-card/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-card/data/inbox/` and `la-card/data/staged/`.
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
   another port than 8080.

   Keep `<parent>` free of spaces when you use absolute paths on Windows.

## Check that it loaded

On start-up the six Pipelines land the twelve months in `data/inbox` and the `card_links` Job runs after each
load. Within a minute or two:

- `inspecto-la-data/la-card/data/<feed>/database/` holds Parquet files for `cardholder`, `card`, `merchant`,
  `card_auth`, `chargeback` and `online_account`, and nothing is under any `<feed>/quarantine` or `<feed>/errors`.
- `inspecto-la-data/la-card/data/card_links/` holds the link Dataset (about 38 000 rows, eight kinds).
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `card_testing`, `account_takeover_ring`,
  `colluding_merchant_refunds` and `common_point_of_purchase`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-card/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-card/data/staged/<feed>/` into `inspecto-la-data/la-card/data/inbox/<feed>/`
(`card_auth`, `chargeback`, `online_account`). The Pipelines land them and the Job rebuilds the links.

## Reset

Stop the product, delete `inspecto-la-data/la-card/`, and unzip the data download again. The Space configuration is
untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-card-data.py
node --test tools/gen-la-card-data.test.mjs
node tools/package-la-showcase.mjs la-card
```

The generator is deterministic (seed 20261107) and writes the ground truth to
`tools/gen-la-card-data.ground-truth.json`, which is not part of any download. The packer reads committed content
only, so commit first.

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
