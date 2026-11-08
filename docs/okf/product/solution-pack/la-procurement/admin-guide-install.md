---
type: Feature
title: "Link Analysis procurement fraud showcase — install"
description: "Install the la-procurement showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-procurement, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis procurement fraud showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-procurement-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-procurement-<version>.zip` (about 0.5 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-procurement/` with `space.toon` and
   `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-procurement/data/inbox/` and
   `la-procurement/data/staged/`.
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

On start-up the seven Pipelines land the twelve months in `data/inbox`; the `procurement_links` Job runs after each
load (and every 5 minutes, so the last load is never missed), and each time it finishes the two derived Jobs run.
Within a minute or two:

- `inspecto-la-data/la-procurement/data/<feed>/database/` holds Parquet files for `company`, `director`, `person`,
  `official`, `tender`, `bid` and `contract_change`, and nothing is under any `<feed>/quarantine` or
  `<feed>/errors`.
- `inspecto-la-data/la-procurement/data/procurement_links/` holds the link Dataset (about 11 000 rows, seven kinds);
  `procurement_approval_links/` and `procurement_debarment_links/` hold the two view Datasets.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `procurement_bid_rotation`, `procurement_cover_bids`,
  `procurement_change_approvals` and `procurement_debarred_directors`. The [user guide](user-guide.md) has the tour
  and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-procurement/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-procurement/data/staged/<feed>/` into
`inspecto-la-data/la-procurement/data/inbox/<feed>/` (`tender`, `bid`, `contract_change`). The Pipelines land them
and the Jobs rebuild the links. Most of October's tenders are still open, so they carry bids but no award yet.

## Reset

Stop the product, delete `inspecto-la-data/la-procurement/`, and unzip the data download again. The Space
configuration is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-procurement-data.py
node --test tools/gen-la-procurement-data.test.mjs
node tools/package-la-showcase.mjs la-procurement
```

The generator is deterministic (seed 20261108) and writes the ground truth to
`tools/gen-la-procurement-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.
