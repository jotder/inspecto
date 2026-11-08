---
type: Feature
title: "Link Analysis insider threat showcase — install"
description: "Install the la-insider showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-insider, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis insider threat showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-insider-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-insider-<version>.zip` (about 6.8 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-insider/` with `space.toon` and
   `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-insider/data/inbox/` and
   `la-insider/data/staged/`.
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

On start-up the six Pipelines land the twelve months in `data/inbox`; the `insider_links` Job runs after each
load (and every 5 minutes, so the last load is never missed), and each time it finishes the
`insider_screen_links` Job runs. Within a minute or two:

- `inspecto-la-data/la-insider/data/<feed>/database/` holds Parquet files for `employee`, `access_grant`,
  `access_daily`, `email_external`, `expense` and `vendor_change`, and nothing is under any `<feed>/quarantine` or
  `<feed>/errors`.
- `inspecto-la-data/la-insider/data/insider_links/` holds the link Dataset (about 26 000 rows, six kinds);
  `insider_screen_links/` holds the three insider-risk screens the saved views read.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `leaver_exfiltration`, `access_creep` and
  `mutual_approvals`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-insider/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-insider/data/staged/<feed>/` into
`inspecto-la-data/la-insider/data/inbox/<feed>/` (`access_grant`, `access_daily`, `email_external`, `expense`,
`vendor_change`). The Pipelines land them and the Jobs rebuild the links. October brings about 9 600 more daily
access counts, 3 100 days of external mail, 1 560 expense claims, 22 access grants and 31 vendor changes. There is no
new HR extract, and the planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-insider/`, and unzip the data download again. The Space
configuration is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-insider-data.py
node --test tools/gen-la-insider-data.test.mjs
node tools/package-la-showcase.mjs la-insider
```

The generator is deterministic (seed 20261112) and writes the ground truth to
`tools/gen-la-insider-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.

## As the *Enterprise* industry Space

This use case also ships as the industry Space *Enterprise* (`la-enterprise`). The recipe is
`spaces/_industries/la-enterprise.toon`, and `node tools/package-la-showcase.mjs la-enterprise` composes it. `la-audit`
joins when the plan's wave B gives it a Basic tier.

Every Dataset says what its id columns hold: *Employee*, *System*, *Email domain*, *Vendor* and *Ticket*. The link
Datasets' mixed ends hold *Insider entity*. Each Dataset of events has its own Mapping: a granting manager to the
employee (`access_grant_dataset`, kind `system`), an employee to the system they read (`access_daily_dataset`), an
employee to an external mail domain (`email_external_dataset`), an approver to a requester (`expense_dataset`), the
changer to the vendor (`vendor_change_dataset`), and a manager to their report (`employee_dataset`).
