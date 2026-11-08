---
type: Feature
title: "Link Analysis provider billing fraud showcase — install"
description: "Install the la-health showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-health, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis provider billing fraud showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-health-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-health-<version>.zip` (about 10 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-health/` with `space.toon` and
   `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-health/data/inbox/` and
   `la-health/data/staged/`.
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

On start-up the nine Pipelines land the twelve months in `data/inbox`; the `health_links` Job runs after each load
(and every 5 minutes, so the last load is never missed), and each time it finishes the `health_screen_links` Job
runs. Within a minute or two:

- `inspecto-la-data/la-health/data/<feed>/database/` holds Parquet files for `address`, `owner`, `provider`,
  `provider_owner`, `member`, `claim_line`, `admission`, `referral` and `prescription`, and nothing is under any
  `<feed>/quarantine` or `<feed>/errors`.
- `inspecto-la-data/la-health/data/health_links/` holds the link Dataset (about 55 000 rows, six kinds);
  `health_screen_links/` holds the four program-integrity screens the saved views read (about 500 rows).
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `health_referral_concentration`,
  `health_phantom_billing`, `health_pill_mill` and `health_upcoding`. The [user guide](user-guide.md) has the tour
  and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-health/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-health/data/staged/<feed>/` into
`inspecto-la-data/la-health/data/inbox/<feed>/` (`claim_line`, `admission`, `referral`, `prescription`). The
Pipelines land them and the Jobs rebuild the links. October brings about 6 500 claim lines, about 190 hospital
stays, about 1 100 referrals and about 2 400 prescriptions. The member, provider and owner extracts are not
re-sent, and nothing in October bills inside a stay or after a death: the planted stories stay as they are (the
`health_upcoding` view gains a few elderly-care clinics as their visit counts pass 150).

## Reset

Stop the product, delete `inspecto-la-data/la-health/`, and unzip the data download again. The Space configuration
is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-health-data.py
node --test tools/gen-la-health-data.test.mjs
node tools/package-la-showcase.mjs la-health
```

The generator is deterministic (seed 20261110) and writes the ground truth to
`tools/gen-la-health-data.ground-truth.json`, which is not part of any download. The packer reads committed content
only, so commit first.
