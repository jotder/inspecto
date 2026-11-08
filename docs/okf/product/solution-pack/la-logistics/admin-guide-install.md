---
type: Feature
title: "Link Analysis logistics cargo theft showcase — install"
description: "Install the la-logistics showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-logistics, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis logistics cargo theft showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-logistics-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-logistics-<version>.zip` (about 5.9 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-logistics/` with `space.toon` and
   `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-logistics/data/inbox/` and
   `la-logistics/data/staged/`.
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

On start-up the eight Pipelines land the twelve months in `data/inbox`; the `logistics_links` Job runs after each
load (and every 5 minutes, so the last load is never missed), and each time it finishes the
`logistics_screen_links` Job runs. Within a minute or two:

- `inspecto-la-data/la-logistics/data/<feed>/database/` holds Parquet files for `carrier`, `driver`, `place`,
  `shipment`, `trip`, `stop`, `warehouse_scan` and `loss_report`, and nothing is under any `<feed>/quarantine` or
  `<feed>/errors`.
- `inspecto-la-data/la-logistics/data/logistics_links/` holds the link Dataset (about 45 000 rows, seven kinds);
  `logistics_screen_links/` holds the three cargo-security screens the saved views read.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `driver_team_inside_job`, `theft_stop_hotspot` and
  `fictitious_carrier`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-logistics/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-logistics/data/staged/<feed>/` into
`inspecto-la-data/la-logistics/data/inbox/<feed>/` (`shipment`, `trip`, `stop`, `warehouse_scan`, `loss_report`).
The Pipelines land them and the Jobs rebuild the links. October brings about 1 130 more trips with their shipments,
stops and scans, and three background losses of October trips. No carrier or
driver changes, and the planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-logistics/`, and unzip the data download again. The Space
configuration is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-logistics-data.py
node --test tools/gen-la-logistics-data.test.mjs
node tools/package-la-showcase.mjs la-logistics
```

The generator is deterministic (seed 20261111) and writes the ground truth to
`tools/gen-la-logistics-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.
