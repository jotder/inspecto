---
type: Feature
title: "Link Analysis police showcase — install"
description: "Install the la-police showcase from its three downloads (product, Space, data) side by side on a Basic edition or above, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-police, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis police showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included; the saved
Geo View needs the Geo map (Standard and above) and does nothing on Basic. Layout and seams:
[showcase plan, Distribution and install layout](../../../../superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-police-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-police-<version>.zip` (about 6 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-police/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-police/data/inbox/` and
   `la-police/data/staged/`.
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
   another port than 8080. On a Basic bundle no sign-in is needed.

   Keep `<parent>` free of spaces when you use absolute paths on Windows.

## Check that it loaded

On start-up the eleven Pipelines land the twelve months in `data/inbox` in a few seconds. The `police_links` Job
runs every five minutes, and each time it builds its Dataset the three derived Jobs (`police_property_links`,
`police_call_links`, `police_witness_links`) run straight after it. Within five minutes:

- `inspecto-la-data/la-police/data/<feed>/database/` holds Parquet files for all eleven feeds (`incident_report`,
  `person`, `incident_person`, `vehicle`, `plate_read`, `call_record`, `handset`, `custody`, `dealer_sale`,
  `property_stolen`, `address`), and nothing is under any `<feed>/quarantine` or `<feed>/errors`.
- `inspecto-la-data/la-police/data/police_links/` holds the link Dataset (about 22 000 rows, nine kinds), and
  `police_property_links/`, `police_call_links/` and `police_witness_links/` hold the three derived ones (about 70,
  1 300 and 700 rows).
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `burglary_series`, `drug_supply_handset_network` and
  `suspicious_witness`, and each draws without "Result cut short". The [user guide](user-guide.md) has the tour and
  the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-police/`.

## New data arrives (November 2026)

Copy every file of `inspecto-la-data/la-police/data/staged/<feed>/` into
`inspecto-la-data/la-police/data/inbox/<feed>/` (`incident_report`, `incident_person`, `plate_read`, `call_record`,
`custody`, `dealer_sale`, `property_stolen`). The Pipelines land them and the next `police_links` run rebuilds the
links; the month brings one more serial match to the burglary series.

## Reset

Stop the product, delete `inspecto-la-data/la-police/`, and unzip the data download again. The Space configuration
is untouched.

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-police-data.py
node --test tools/gen-la-police-data.test.mjs
node tools/package-la-showcase.mjs la-police
```

The generator is deterministic (seed 20261107) and writes the ground truth to `tools/la-police-ground-truth.json`,
which is not part of any download. The packer reads committed content only, so commit first.

## As the *Law enforcement* industry Space

The same use case also ships as the industry Space *Law enforcement* (`la-law-enforcement`). Its recipe is
`spaces/_industries/la-law-enforcement.toon` (use case `la-police`). `node tools/package-la-showcase.mjs
la-law-enforcement` composes it into `inspecto-la-space-la-law-enforcement-<version>.zip` and its data zip. Install
them as above, with `la-law-enforcement` in place of `la-police`.

Every Dataset says what its id columns hold: *Person*, *Address*, *Incident*, *Dealer*, *Item serial*, *Phone number*,
*Handset* and *Vehicle*. The four link Datasets mix kinds in one `src` and one `dst` column, so their ends hold the
generic *Police entity*. Phone numbers and handsets are kept as written (upper-trim), so the ids the links join on
never change. Each Dataset of events has its own Mapping:

| Dataset | Mapping |
|---|---|
| `call_record_dataset` | Phone number → Phone number (when `start_at`, measure `duration_s`) |
| `dealer_sale_dataset` | Person (seller) → Dealer (when `sold_at`) |
| `handset_dataset` | Person → Phone number |
| `incident_person_dataset` | Person → Incident (kind `role`) |
| `incident_report_dataset` | Person (reporter) → Incident (kind `offence_type`, when `start_at`) |
| `person_dataset` | Person → Address |
| `property_stolen_dataset` | Incident → Item serial |
| `vehicle_dataset` | Person (keeper) → Vehicle |
| the four `police_*links_dataset` | Police entity → Police entity (kind `kind`, when `ts`, measure `amount`) |

The views carry use-case names: `burglary_series`, `drug_supply_handset_network`, `suspicious_witness`, and the Geo View
`incident_map`.
