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
and seams: [showcase distribution and install layout](../la-showcase/architecture-showcase-catalogue.md#distribution-and-install-layout)
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
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `address_farm`, `paid_after_death`,
  `undeclared_work` and `tax_preparer_refund_ring`. The [user guide](user-guide.md) has the tour
  and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-benefits/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-benefits/data/staged/<feed>/` into
`inspecto-la-data/la-benefits/data/inbox/<feed>/` (`claim`, `death_register`, `payroll`, `tax_return`). The
Pipelines land them and the Jobs rebuild the links. October brings about 100 new claims, another month of payroll
(12 of the twenty working jobseekers are still paid) and a few hundred late tax returns; its death-register file is
empty. The planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-benefits/`, and unzip the data download again. The Space
configuration is untouched.

## Advanced tier: personas, the payments-to-the-deceased Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `benefits_paid_to_the_deceased_after_notice` is loaded but not armed,
and the `benefits_deceased_counts` and `benefits_deceased_alerts` Jobs run after each `benefits_links` build; the
second finds no armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs
people in by name with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the seven Pipelines have landed and `benefits_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-benefits.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `paid-after-death`
(seeded with the three accounts `SYN-13618314`, `SYN-23138817` and `SYN-96993903`, expanded over `paid_into` to the
12 claims paid into them and over `claims` to their 10 claimants, annotated, snapshotted), its members, the Entity
List `deceased-still-paid` (the 9 pensioners), a Case from `CIT-08276` assigned to `benefits.manager` and linked to
the Investigation, an Investigation template, one unbounded expand held for a second person, and one open Draft. To
lay the Space out offline instead of unzipping the downloads, `node tools/seed-la-benefits.mjs --scaffold
<bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `benefits.analyst` | `benefits-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `benefits.manager` | `benefits-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `benefits.reviewer` | `la-viewer`, member as reviewer | reads `paid-after-death`; any change is refused (403) |
| `benefits.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `benefits_paid_to_the_deceased_after_notice` (WARNING) reads the one row of
`benefits_deceased_counts` and fires when any claim of a person on the death register is still paid more than **30
days after the death was notified** (or is still running that long after it). On the shipped data the Alert carries
**9**: the nine pensions, paid into **3** accounts that also take the **3** claims of one living person. Days are
counted from the notice, not from the death: notices arrive 0–60 days after a death and a claim stops 0–5 days after
its notice, so a short overpayment is normal. **314** claims were paid after a death and stopped within the grace
(the slowest 5 days after its notice), the death notified 40 days late (`CIT-05289`, paid 42 days after the death)
among them; none of them counts. A rule counting from the death instead would have flagged 34 of them. The count is a
running total, so the rule stays met after every build while those claims are in the data. It names no one;
`paid_after_death` shows the accounts. The rule is not grouped by account, because `benefits_deceased_counts` is a
Job output: a `by` column cannot be checked against its Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 40 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **9 (WARNING)**,
`seed-la-benefits.mjs --apply` created 20 steps with none failed, and every persona gate held (a second approved expand
admitted no new entity, because of an earlier threshold step). On Basic the rule stayed unarmed and the Space had no
Alert. Method and record:
[showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-benefits-data.py
node --test tools/gen-la-benefits-data.test.mjs
node tools/package-la-showcase.mjs la-benefits
```

The generator is deterministic (seed 20261109) and writes the ground truth to
`tools/gen-la-benefits-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.

## As part of the *Public sector* industry Space

This use case also ships inside the industry Space *Public sector* (`la-public-sector`), together with the other
public-sector showcase. The recipe is `spaces/_industries/la-public-sector.toon` (use cases `la-procurement` and
`la-benefits`), and `node tools/package-la-showcase.mjs la-public-sector` composes it into one Space download and one
data download. Install them as above, with `la-public-sector` in place of this Space's id.

Composing refuses a file both use cases would ship, so procurement's people feed is called `procurement_person` (its
Pipeline, Dataset and inbox folder); benefits keeps `person`. The two packs declare the shared types *Person*,
*Address*, *Bank account* and *Public-sector entity* identically, so they merge into one. Every Dataset says what its
id columns hold, and each Dataset of events has its own Mapping. In each link Dataset, the ends mixing kinds hold
*Public-sector entity*.

Views carry use-case names: `bid_rotation`, `cover_bids_shared_ip`, `official_approving_relative_firm`,
`debarred_firm_reborn`, `address_farm`, `paid_after_death`, `undeclared_work` and `tax_preparer_refund_ring`.
