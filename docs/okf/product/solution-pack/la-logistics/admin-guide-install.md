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
and seams: [showcase distribution and install layout](../la-showcase/architecture.md#distribution-and-install-layout)
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

## Advanced tier: personas, the new-carrier Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `logistics_high_value_trip_new_carrier` is loaded but not armed, and the
`logistics_new_carrier_counts` and `logistics_new_carrier_alerts` Jobs run after each `logistics_links` build; the
second finds no armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs
people in by name with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the eight Pipelines have landed and `logistics_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-logistics.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `fictitious-carrier`
(seeded with the carrier `CAR-022`, expanded once over `carried_by` and `same_phone` to its 5 high-value loads and to
`DRV-0895`, the driver whose phone it gives as its own; annotated, snapshotted), its members, the Entity List
`suspect-carriers` (`CAR-022`, `DRV-0895`), a Case assigned to `logistics.manager` and linked to the Investigation, an
Investigation template, one unbounded expand held for a second person, and one open Draft. To lay the Space out
offline instead of unzipping the downloads, `node tools/seed-la-logistics.mjs --scaffold <bundle>\spaces` does it
before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `logistics.analyst` | `logistics-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `logistics.manager` | `logistics-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `logistics.reviewer` | `la-viewer`, member as reviewer | reads `fictitious-carrier`; any change is refused (403) |
| `logistics.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `logistics_high_value_trip_new_carrier` (WARNING) reads the one row of
`logistics_new_carrier_counts` and fires when one or more trips worth **100 000** or more are given to a carrier
registered **under 60 days** before departure **that is not insured**. On the shipped data **16** high-value trips go
to a carrier under 60 days old, and the Alert carries **5**: the loads of `CAR-022`, the only uninsured one. The new
owner-operator `CAR-057`'s 9 and the 2 of `CAR-091` and `CAR-115` are insured and not counted, and the 11 established
uninsured carriers are not new. The spec's rule read literally ("a high-value trip assigned to a carrier under 60 days
old") would have fired on the 11 insured loads as well, so the rule adds the one fact the look-alike story sets apart,
insurance. It reads the carrier register when the load is given, never a loss report: the background losses (about
one trip in 400) cannot raise it. The count is a running total over every landed trip, so the rule stays met after
every build (one open Alert, not one per build). It names no carrier; `fictitious_carrier` shows it. The rule is not
grouped by carrier, because `logistics_new_carrier_counts` is a Job output: a `by` column cannot be checked against
its Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 52 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **5 (WARNING)**,
`seed-la-logistics.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table above
(the held expand ran once the manager approved it). Until 2026-11-13 every boot, on every edition, logged a harmless WARN
"Pipeline toon is missing required section 'job'": the Job loader also read the saved view `driver_team_inside_job.toon`
for its file name (`JOB-LOADER-SAVED-VIEW-SUFFIX-1`, fixed: the boot scan skips `registry/`). On Basic the rule stayed unarmed and the Space had no Alert. Method and record:
[showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-logistics-data.py
node --test tools/gen-la-logistics-data.test.mjs
node tools/package-la-showcase.mjs la-logistics
```

The generator is deterministic (seed 20261111) and writes the ground truth to
`tools/gen-la-logistics-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.

## As the *Logistics* industry Space

The same use case also ships as the industry Space *Logistics*, under the id `la-logistics-industry`. That id
cannot be `la-logistics`: a recipe of that name would make the packer compose instead of packing this template's own
downloads. The recipe is `spaces/_industries/la-logistics-industry.toon`, and `node tools/package-la-showcase.mjs
la-logistics-industry` composes it.

Every Dataset says what its id columns hold: *Shipment*, *Trip*, *Driver*, *Carrier*, *Place*, *Warehouse*,
*Employee* and *Vehicle*. The link Datasets' mixed ends hold *Logistics entity*. Each Dataset of events has its own
Mapping: a driver to the shipment (`trip_dataset`), a scanner employee to the shipment (`warehouse_scan_dataset`, kind
`scan_type`), a trip to the place it stopped (`stop_dataset`), and a driver to the carrier (`driver_dataset`).
