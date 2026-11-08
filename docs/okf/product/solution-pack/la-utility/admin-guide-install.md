---
type: Feature
title: "Link Analysis utility showcase — install"
description: "Install the la-utility showcase from its three downloads (product, Space, data) side by side on a Basic or later build, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-utility, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis utility showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` (any edition; Basic is enough) | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-utility-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-utility-<version>.zip` (about 2 MB zipped, 12 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-utility/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-utility/data/inbox/` and
   `la-utility/data/staged/`.
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

On Basic there is no sign-in, and the server listens on every network interface by default: anyone who can reach the
port can use the Space and change its configuration. For a single-user install, add `-Dcontrol.bind=127.0.0.1` (set
`INSPECTO_JAVA_OPTS=-Dcontrol.bind=127.0.0.1` before starting) or block the port with a firewall (see
[Editions, Listen address](../../../../EDITIONS.md)).

## Check that it loaded

On start-up the eight Pipelines land the twelve months in `data/inbox`, and each load fires the Jobs. Within a
minute or two:

- `inspecto-la-data/la-utility/data/<feed>/database/` holds Parquet files for `premise`, `customer_account`,
  `meter_read`, `transformer_read`, `field_visit`, `service_order`, `prepaid_vend` and `payment`, and nothing is
  under any `<feed>/quarantine` or `<feed>/errors` (30 files, 0 rejected rows).
- `inspecto-la-data/la-utility/data/utility_links/` holds the link Dataset (about 4 300 rows, six kinds),
  `transformer_loss/` the monthly energy balance (144 rows) and `utility_bank_links/` the churn view's links.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `utility_contractor_network`, `utility_churn_by_bank` and
  `utility_vendor_network`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-utility/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-utility/data/staged/<feed>/` into
`inspecto-la-data/la-utility/data/inbox/<feed>/` (`meter_read`, `transformer_read`, `field_visit`, `service_order`,
`prepaid_vend`, `payment`). The Pipelines land them and the Jobs rebuild the links and the loss.

## Reset

Stop the product, delete `inspecto-la-data/la-utility/`, and unzip the data download again. The Space configuration
is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-utility-data.py
node --test tools/gen-la-utility-data.test.mjs
node tools/package-la-showcase.mjs la-utility
```

The generator is deterministic (seed 20261107) and writes the ground truth to
`tools/gen-la-utility-data.ground-truth.json`, which is not part of any download. The packer reads committed content
only, so commit first.
