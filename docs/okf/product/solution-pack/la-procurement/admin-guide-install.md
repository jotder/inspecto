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
and seams: [showcase distribution and install layout](../la-showcase/architecture.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`spaces.ref`, `data_root`).

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
4. Create the pointer file `<parent>/inspecto-la/spaces.ref` holding one line — `../inspecto-la-spaces` (forward
   slashes, so the same file works on Windows, Linux and macOS). It names the spaces root relative to the bundle.
   Each Space's `space.toon` already names its own data root the same way (`data_root: ../inspecto-la-data/<id>`),
   so no environment variable is needed and the three folders can be moved or copied together after the first start.
5. Start the product from its folder:

   Windows:

   ```bat
   cd <parent>\inspecto-la
   bin\serve.bat
   ```

   Linux or macOS:

   ```bash
   cd <parent>/inspecto-la && bin/serve.sh
   ```

   The launcher changes to the bundle folder first, so both paths are relative to `inspecto-la/`. `PORT` picks
   another port than 8080. Keep `<parent>` free of spaces when you use absolute paths on Windows.

## Check that it loaded

On start-up the seven Pipelines land the twelve months in `data/inbox`; the `procurement_links` Job runs after each
load (and every 5 minutes, so the last load is never missed), and each time it finishes the two derived Jobs run.
Within a minute or two:

- `inspecto-la-data/la-procurement/data/<feed>/database/` holds Parquet files for `company`, `director`, `procurement_person`,
  `official`, `tender`, `bid` and `contract_change`, and nothing is under any `<feed>/quarantine` or
  `<feed>/errors`.
- `inspecto-la-data/la-procurement/data/procurement_links/` holds the link Dataset (about 11 000 rows, seven kinds);
  `procurement_approval_links/` and `procurement_debarment_links/` hold the two view Datasets.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `bid_rotation`, `cover_bids_shared_ip`,
  `official_approving_relative_firm` and `debarred_firm_reborn`. The [user guide](user-guide.md) has the tour
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

## Advanced tier: personas, the shared-IP Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `procurement_same_tender_shared_ip` is loaded but not armed, and the
`procurement_shared_ip_counts` and `procurement_shared_ip_alerts` Jobs run after each `procurement_links` build; the
second finds no armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs
people in by name with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the seven Pipelines have landed and `procurement_links` has run (a minute or two), create the runtime state
from a checkout of the repository:

```bash
node tools/seed-la-procurement.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `cover-bids` (seeded
with the IP address `100.64.73.61`, expanded once over `same_ip` to the four firms that bid from it, annotated,
snapshotted), its members, the Entity List `cover-bidders` (`CO-0153`, `CO-0309`, `CO-0702`), a Case on `CO-0074`
assigned to `procurement.manager` and linked to the Investigation, an Investigation template, one unbounded expand
held for a second person, and one open Draft. To lay the Space out offline instead of unzipping the downloads,
`node tools/seed-la-procurement.mjs --scaffold <bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `procurement.analyst` | `procurement-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `procurement.manager` | `procurement-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `procurement.reviewer` | `la-viewer`, member as reviewer | reads `cover-bids`; any change is refused (403) |
| `procurement.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `procurement_same_tender_shared_ip` (WARNING) reads the one row of
`procurement_shared_ip_counts`: it fires when any bid was sent from an IP address that another bidder had already
used, earlier, for its own bid on the same tender. On the shipped data the Alert carries **16**: 16 bids on 8 tenders,
every one a cover bid by `CO-0153`, `CO-0309` or `CO-0702` from `CO-0074`'s office address. The help-desk kiosk
`198.51.100.20` is the only other address two or more firms use, and no two of them ever bid on one tender from it, so
it never counts. The staged month adds no such bid. The count is a running total over every landed bid, so the rule
stays met after every build. It names no bidder or address; `cover_bids_shared_ip` shows them. The rule is not grouped
by tender, because `procurement_shared_ip_counts` is a Job output: a `by` column cannot be checked against its Schema
when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 29 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **16 (WARNING)**,
`seed-la-procurement.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table
above (the held expand ran once the manager approved it). One manager approve answered 422 while a cron run was
rebuilding the link Dataset; the same approve succeeded 30 seconds later. On Basic the rule stayed unarmed and the Space
had no Alert. Method and record:
[showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-procurement-data.py
node --test tools/gen-la-procurement-data.test.mjs
node tools/package-la-showcase.mjs la-procurement
```

The generator is deterministic (seed 20261108) and writes the ground truth to
`tools/gen-la-procurement-data.ground-truth.json`, which is not part of any download. The packer reads committed
content only, so commit first.

## As part of the *Public sector* industry Space

This use case also ships inside the industry Space *Public sector* (`la-public-sector`), together with the other
public-sector showcase. The recipe is `spaces/_industries/la-public-sector.toon` (use cases `la-procurement` and
`la-benefits`), and `node tools/package-la-showcase.mjs la-public-sector` composes it into one Space download and one
data download. Install them as above, with `la-public-sector` in place of this Space's id.

Composing refuses a file both use cases would ship, so procurement's people feed is called `procurement_person` (its
Pipeline, Dataset and inbox folder); benefits keeps `person`. The Advanced tier's
`config/demo-users.toon` and `config/roles.toon` are joined with `la-benefits`' (users by id, roles by name); a user or
role the two define differently is refused. The four-eyes and Drafts settings in
`link-analysis.toon` are merged by key, so both use cases state the same values. The two packs declare the shared types *Person*,
*Address*, *Bank account* and *Public-sector entity* identically, so they merge into one. Every Dataset says what its
id columns hold, and each Dataset of events has its own Mapping. In each link Dataset, the ends mixing kinds hold
*Public-sector entity*.

Views carry use-case names: `bid_rotation`, `cover_bids_shared_ip`, `official_approving_relative_firm`,
`debarred_firm_reborn`, `address_farm`, `paid_after_death`, `undeclared_work` and `tax_preparer_refund_ring`.
