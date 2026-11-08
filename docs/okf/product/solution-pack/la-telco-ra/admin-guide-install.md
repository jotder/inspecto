---
type: Feature
title: "Link Analysis telecom revenue assurance showcase — install"
description: "Install the la-telco-ra showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-telco-ra, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis telecom revenue assurance showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-telco-ra-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-telco-ra-<version>.zip` (about 7.5 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-telco-ra/` with `space.toon` and
   `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-telco-ra/data/inbox/` and
   `la-telco-ra/data/staged/`.
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

On start-up the seven Pipelines land the twelve months in `data/inbox`; the `telco_ra_links` Job runs after each
load (and every 5 minutes, so the last load is never missed), and each time it finishes the `telco_ra_screen_links`
Job runs. Within a minute or two:

- `inspecto-la-data/la-telco-ra/data/<feed>/database/` holds Parquet files for `dealer`, `subscriber`, `network_usage`,
  `billing_record`, `topup`, `enterprise_trunk` and `trunk_call`, and nothing is under any `<feed>/quarantine` or
  `<feed>/errors`.
- `inspecto-la-data/la-telco-ra/data/telco_ra_links/` holds the link Dataset (about 21 000 rows, five kinds);
  `telco_ra_screen_links/` holds the three revenue-assurance screens the saved views read.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `telco_ra_unbilled_usage`, `telco_ra_commission_spikes`
  and `telco_ra_pbx_off_hours`. The [user guide](user-guide.md) has the tour
  and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-telco-ra/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-telco-ra/data/staged/<feed>/` into
`inspecto-la-data/la-telco-ra/data/inbox/<feed>/` (`subscriber`, `network_usage`, `billing_record`, `topup`,
`trunk_call`). The Pipelines land them and the Jobs rebuild the links. October brings 137 new activations, another
month of usage, billing and top-ups, and a month of trunk calls. The 300 unbilled SIMs are still used and still not
billed, the commission SIMs stay dormant, and there is no new PBX attack. The planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-telco-ra/`, and unzip the data download again. The Space
configuration is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-telco-ra-data.py
node --test tools/gen-la-telco-ra-data.test.mjs
node tools/package-la-showcase.mjs la-telco-ra
```

The generator is deterministic (seed 20261110) and writes the ground truth to
`tools/gen-la-telco-ra-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.
