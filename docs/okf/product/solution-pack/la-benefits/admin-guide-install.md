---
type: Feature
title: "Link Analysis benefits and tax fraud showcase — install"
description: "Install the la-benefits showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-benefits, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis benefits and tax fraud showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-benefits-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-benefits-<version>.zip` (about 3.5 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-benefits/` with `space.toon` and
   `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-benefits/data/inbox/` and
   `la-benefits/data/staged/`.
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

On start-up the seven Pipelines land the twelve months in `data/inbox`; the `benefits_links` Job runs after each
load (and every 5 minutes, so the last load is never missed), and each time it finishes the `benefits_screen_links`
Job runs. Within a minute or two:

- `inspecto-la-data/la-benefits/data/<feed>/database/` holds Parquet files for `address`, `person`, `household`,
  `claim`, `death_register`, `payroll` and `tax_return`, and nothing is under any `<feed>/quarantine` or
  `<feed>/errors`.
- `inspecto-la-data/la-benefits/data/benefits_links/` holds the link Dataset (about 39 000 rows, six kinds);
  `benefits_screen_links/` holds the four integrity screens the saved views read.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `benefits_shared_addresses`, `benefits_paid_after_death`,
  `benefits_work_while_claiming` and `benefits_refund_concentration`. The [user guide](user-guide.md) has the tour
  and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-benefits/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-benefits/data/staged/<feed>/` into
`inspecto-la-data/la-benefits/data/inbox/<feed>/` (`claim`, `death_register`, `payroll`, `tax_return`). The
Pipelines land them and the Jobs rebuild the links. October brings about 100 new claims, its death notices (none of
them a current claimant), another month of payroll in which the twenty working jobseekers are still paid, and a few hundred
late tax returns. The planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-benefits/`, and unzip the data download again. The Space
configuration is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-benefits-data.py
node --test tools/gen-la-benefits-data.test.mjs
node tools/package-la-showcase.mjs la-benefits
```

The generator is deterministic (seed 20261109) and writes the ground truth to
`tools/gen-la-benefits-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.
