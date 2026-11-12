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
and seams: [showcase distribution and install layout](../la-showcase/architecture.md#distribution-and-install-layout)
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
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `referral_kickbacks`,
  `phantom_billing`, `pill_mill` and `upcoding_clinics`. The [user guide](user-guide.md) has the tour
  and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-health/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-health/data/staged/<feed>/` into
`inspecto-la-data/la-health/data/inbox/<feed>/` (`claim_line`, `admission`, `referral`, `prescription`). The
Pipelines land them and the Jobs rebuild the links. October brings about 6 500 claim lines, about 190 hospital
stays, about 1 100 referrals and about 2 400 prescriptions. The member, provider and owner extracts are not
re-sent, and nothing in October bills inside a stay or after a death: the planted stories stay as they are (the
`upcoding_clinics` view gains a few elderly-care clinics as their visit counts pass 150).

## Reset

Stop the product, delete `inspecto-la-data/la-health/`, and unzip the data download again. The Space configuration
is untouched.

## Advanced tier: personas, the billed-in-hospital-or-after-death Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `health_billed_in_admission_or_after_death` is loaded but not armed, and
the `health_phantom_counts` and `health_phantom_alerts` Jobs run after each `health_links` build; the second finds no
armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs people in by name
with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the nine Pipelines have landed and `health_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-health.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `phantom-billing`
(seeded with `PR-1241`, expanded once over `treated` to its patients, annotated, snapshotted), its members, the Entity
List `billed-in-hospital-or-after-death` (the 40 patients `PR-1241` billed while they were in hospital elsewhere or
after they died), a Case assigned to `health.manager` and linked to the Investigation, an Investigation template, one
unbounded expand held for a second person, and one open Draft. To lay the Space out offline instead of unzipping the
downloads, `node tools/seed-la-health.mjs --scaffold <bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `health.analyst` | `health-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `health.manager` | `health-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `health.reviewer` | `la-viewer`, member as reviewer | reads `phantom-billing`; any change is refused (403) |
| `health.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `health_billed_in_admission_or_after_death` (WARNING) reads the one row of `health_phantom_counts`
and fires when any open claim line is dated inside the member's stay at another hospital or after the member died. On
the shipped data the Alert carries **159**: 89 lines in a stay and 70 after a death, for 40 members, all billed by one
provider, `PR-1241`. The 30 lines that fell in a stay but were reversed within days (15 at the look-alike `PR-0632`, 15
single errors elsewhere) are counted apart in `corrected_lines` and never fire it. A hospital's own lines inside its
own stays are not "in a stay elsewhere", so the busy seaside hospital `PR-1129` (the busiest node of
`referral_kickbacks`) is never counted. The count is a running total, so the rule stays met after every build; the
October month adds nothing to it. It names no member or provider; `phantom_billing` shows the clinic. The rule is not
grouped by provider, because `health_phantom_counts` is a Job output: a `by` column cannot be checked against its
Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 53 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **159 (WARNING)**,
`seed-la-health.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table above
(the held expand ran once the manager approved it). On Basic the rule stayed unarmed and the Space had no Alert. Method
and record: [showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-health-data.py
node --test tools/gen-la-health-data.test.mjs
node tools/package-la-showcase.mjs la-health
```

The generator is deterministic (seed 20261110) and writes the ground truth to
`tools/gen-la-health-data.ground-truth.json`, which is not part of any download. The packer reads committed content
only, so commit first.

## As the *Healthcare* industry Space

The same use case also ships as the industry Space *Healthcare* (`la-healthcare`). Its recipe is
`spaces/_industries/la-healthcare.toon` (use case `la-health`). `node tools/package-la-showcase.mjs la-healthcare`
composes it into `inspecto-la-space-la-healthcare-<version>.zip` and its data zip. Install them as above, with
`la-healthcare` in place of `la-health`.

Every Dataset says what its id columns hold: *Provider*, *Member*, *Owner*, *Address*, *Claim* and *Bank account*.
The two link Datasets mix kinds in one `src` and one `dst` column, so their ends hold the generic *Health entity*. Each
Dataset of events has its own Mapping:

| Dataset | Mapping |
|---|---|
| `referral_dataset` | Provider → Provider (when `referred_on`) |
| `claim_line_dataset` | Provider → Member (kind `procedure_code`, when `service_date`, measure `billed`) |
| `prescription_dataset` | Provider (prescriber) → Member (kind `drug_class`, when `filled_on`, measure `qty`) |
| `admission_dataset` | Member → Provider (hospital) (when `from_date`) |
| `provider_owner_dataset` | Provider → Owner |
| `provider_dataset` | Provider → Bank account |
| `member_dataset`, `owner_dataset` | Member or Owner → Address |
| `health_links_dataset`, `health_screen_links_dataset` | Health entity → Health entity (kind `kind`, when `ts`, measure `amount`) |

The views carry use-case names: `referral_kickbacks`, `phantom_billing`, `pill_mill` and `upcoding_clinics`.
