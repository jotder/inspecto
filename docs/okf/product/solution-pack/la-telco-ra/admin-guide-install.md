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
and seams: [showcase distribution and install layout](../la-showcase/architecture.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-telco-ra-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-telco-ra-<version>.zip` (about 7.3 MB unzipped) | `<parent>/inspecto-la-data/` |

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
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `unbilled_usage`, `dealer_commission_fraud`
  and `pbx_hacking_off_hours`. The [user guide](user-guide.md) has the tour
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

## Advanced tier: personas, the usage-without-billing Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `telco_ra_usage_without_billing` is loaded but not armed, and the
`telco_ra_unbilled_counts` and `telco_ra_unbilled_alerts` Jobs run after each `telco_ra_links` build; the second finds
no armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs people in by name
with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the seven Pipelines have landed and `telco_ra_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-telco-ra.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `unbilled-usage`
(seeded with `DL-047`, expanded once over `activated_by` to the dealer's 336 subscribers, the 300 unbilled SIMs among
them, annotated, snapshotted), its members, the Entity List `unbilled-sims` (the 300), a Case assigned to
`telcora.manager` and linked to the Investigation, an Investigation template, one unbounded expand held for a second
person, and one open Draft. To lay the Space out offline instead of unzipping the downloads,
`node tools/seed-la-telco-ra.mjs --scaffold <bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `telcora.analyst` | `telcora-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `telcora.manager` | `telcora-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `telcora.reviewer` | `la-viewer`, member as reviewer | reads `unbilled-usage`; any change is refused (403) |
| `telcora.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `telco_ra_usage_without_billing` (WARNING) reads the one row of `telco_ra_unbilled_counts`. The
feeds are monthly, so the spec's "usage without billing over 1 day" is counted a subscriber-month at a time: a month
with network usage and no billing record, judged only once that month's billing file has landed (so a usage file that
lands first is not read as unbilled). The rule fires when a subscriber not on the known list (plan `Internal test`) has
2 or more such months and is still unbilled in the latest one. On the shipped data the Alert carries **300**: the SIMs
of `DL-047`. The 40 test SIMs of `DL-000` are counted apart as `known_list_subscribers` and never raise it, and the 62
ordinary subscribers with one dropped month or two late-billed months (`other_unbilled_subscribers`) do not either.
October changes none of the three figures. The count is a running total, so the rule stays met after every build. It
names no subscriber or dealer; the `unbilled_usage` view shows them. The rule is not grouped by subscriber, because
`telco_ra_unbilled_counts` is a Job output: a `by` column cannot be checked against its Schema when the Space is
created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 51 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **300 (WARNING)**,
`seed-la-telco-ra.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table above
(the held expand ran once the manager approved it). On that first boot the rule fired at 4, cleared and fired again, so
a RESOLVED Alert at 4 sits beside the OPEN one; the cause is not known yet (`LA-TELCO-RA-FIRST-BOOT-RESOLVED-ALERT-1`).
On Basic the rule stayed unarmed and the Space had no Alert. Method and record:
[showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-telco-ra-data.py
node --test tools/gen-la-telco-ra-data.test.mjs
node tools/package-la-showcase.mjs la-telco-ra
```

The generator is deterministic (seed 20261110) and writes the ground truth to
`tools/gen-la-telco-ra-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.

## As the *Telecom* industry Space

The same use case also ships as the industry Space *Telecom* (`la-telecom`). Its recipe is
`spaces/_industries/la-telecom.toon`. Today its use cases are `la-telco-ra` and `la-mfs`; `la-mobile-money` and the telecom
part of `la-showcase` join when the plan's wave B gives them a Basic tier. `node tools/package-la-showcase.mjs
la-telecom` composes it into `inspecto-la-space-la-telecom-<version>.zip` and its data zip. Install them as above,
with `la-telecom` in place of `la-telco-ra`.

Every Dataset says what its id columns hold: *Subscriber number*, *Dealer*, *Device*, *Shop*, *Enterprise trunk*,
*Enterprise customer* and *Destination country*. The two link Datasets mix kinds in one `src` and one `dst` column, so
their ends hold the generic *Telecom entity*. Each Dataset of events has its own Mapping:

| Dataset | Mapping |
|---|---|
| `subscriber_dataset` | Dealer → Subscriber number (kind `plan`, when `activated_at`) |
| `topup_dataset` | Subscriber number → Shop (kind `channel`, when `topped_up_at`, measure `amount`) |
| `trunk_call_dataset` | Enterprise trunk → Destination country (kind `number_type`, when `call_date`, measure `minutes`) |
| `enterprise_trunk_dataset` | Enterprise customer → Enterprise trunk |
| `telco_ra_links_dataset`, `telco_ra_screen_links_dataset` | Telecom entity → Telecom entity (kind `kind`, when `ts`, measure `amount`) |

The views carry use-case names: `unbilled_usage`, `dealer_commission_fraud` and `pbx_hacking_off_hours`.
