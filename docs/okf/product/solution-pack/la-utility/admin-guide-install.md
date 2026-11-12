---
type: Feature
title: "Link Analysis utility showcase — install"
description: "Install the la-utility showcase from its three downloads (product, Space, data) side by side on a Basic or later build, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-utility, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis utility showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase distribution and install layout](../la-showcase/architecture.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` (any edition; Basic is enough) | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-utility-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-utility-<version>.zip` (about 2 MB zipped, 12 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-utility/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-utility/data/inbox/` and
   `la-utility/data/staged/`.
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
   another port than 8080.

   Keep `<parent>` free of spaces when you use absolute paths on Windows.

On Basic there is no sign-in, and the server listens on every network interface by default: anyone who can reach the
port can use the Space and change its configuration. For a single-user install, add `-Dcontrol.bind=127.0.0.1` (set
`INSPECTO_JAVA_OPTS=-Dcontrol.bind=127.0.0.1` before starting) or block the port with a firewall (see
[Editions, Listen address](../../../../EDITIONS.md)).

## Check that it loaded

On start-up the eight Pipelines land the twelve months in `data/inbox`, and each load fires the Jobs. Within a
minute or two:

- `inspecto-la-data/la-utility/data/<feed>/database/` holds Parquet files for `premise`, `customer_account`,
  `meter_read`, `transformer_read`, `field_visit`, `service_order`, `prepaid_vend` and `payment`, and nothing is
  under any `<feed>/quarantine` or `<feed>/errors` (30 files, 0 rejected rows).
- `inspecto-la-data/la-utility/data/utility_links/` holds the link Dataset (about 4 300 rows, six kinds),
  `transformer_loss/` the monthly energy balance (144 rows) and `utility_bank_links/` the churn view's links.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `meter_tampering_contractor`, `account_churn_by_bank_account` and
  `prepaid_token_fraud_vendor`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-utility/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-utility/data/staged/<feed>/` into
`inspecto-la-data/la-utility/data/inbox/<feed>/` (`meter_read`, `transformer_read`, `field_visit`, `service_order`,
`prepaid_vend`, `payment`). The Pipelines land them and the Jobs rebuild the links and the loss.

## Reset

Stop the product, delete `inspecto-la-data/la-utility/`, and unzip the data download again. The Space configuration
is untouched.

## Advanced tier: personas, the loss Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `utility_transformer_loss` is loaded but not armed (a WARN, "Not arming
alert rule"), and the `utility_loss_alerts` Job runs and reports no rule breached. On a build with sign-in it comes to
life. To show it, use the demo build, which signs people in by name with no password (Preview, Demo Auth, loopback
only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the eight Pipelines have landed (a minute or two), create the runtime state from a checkout of the repository:

```bash
node tools/seed-la-utility.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `tampering-case`
(seeded with `C-14`, `TX-007` and `TX-019`, expanded once, annotated, snapshotted), its members, the Entity Lists
`tampered-premises` (the 22 premises) and `revenue-protection-watch` (`C-14`, `C-22`, `V-08`), a Case assigned to
`rp.manager` and linked to the Investigation, an Investigation template, one unbounded expand held for a second
person, and one open Draft. To lay the Space out offline instead of unzipping the downloads,
`node tools/seed-la-utility.mjs --scaffold <bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `rp.analyst` | `rp-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `rp.manager` | `rp-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `rp.reviewer` | `la-viewer`, member as reviewer | reads `tampering-case`; any change is refused (403) |
| `rp.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces |

**The Alert Rule.** `utility_transformer_loss` fires (CRITICAL) when a metered transformer's monthly loss in
`transformer_loss` reaches 15 %. The Alert carries the highest monthly loss. It fires while the months land, because
TX-019 reaches 15 % from March 2026 (TX-007 from July); with all twelve months in, the loss is 24.7 % on TX-019 and 17.9 % on TX-007, and every
other metered transformer, the holiday home's included, stays under 7 %. Open `transformer_loss` (`loss_band = loss over
threshold`) to see which transformer. The rule is not grouped by transformer, because `transformer_loss` is a Job
output: a `by` column cannot be checked against its Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 30 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **24.7 (CRITICAL)**,
`seed-la-utility.mjs --apply` created 21 steps with none failed, and every persona got the answers in the table above
(the held expand ran once the manager approved it). The Alert opens at the first crossing of 15 % while the months land
and is refreshed in place to the current highest loss, 24.7 % on TX-019, keeping its first-fire time. On Basic the rule
stayed unarmed and the Space had no Alert. Method and record:
[showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-utility-data.py
node --test tools/gen-la-utility-data.test.mjs
node tools/package-la-showcase.mjs la-utility
```

The generator is deterministic (seed 20261107) and writes the ground truth to
`tools/gen-la-utility-data.ground-truth.json`, which is not part of any download. The packer reads committed content
only, so commit first.

## As the *Utilities* industry Space

The same use case also ships as the industry Space *Utilities* (`la-utilities`). Its recipe,
`spaces/_industries/la-utilities.toon`, names the use-case templates it is composed from; today that is `la-utility`
alone. The packer composes it from committed content:

```bash
node tools/package-la-showcase.mjs la-utilities
```

That writes `inspecto-la-space-la-utilities-<version>.zip` and `inspecto-la-data-la-utilities-<version>.zip`. Install
them as above, with `la-utilities` in place of `la-utility`. A file two use cases would both ship is refused. Entity
types in `config/link-analysis.toon` are merged by id. Every Dataset says what its id columns hold, and each Dataset
of events has its own Mapping:

| Dataset | Mapping |
|---|---|
| `premise_dataset` | Premise → Transformer |
| `customer_account_dataset` | Account → Premise |
| `payment_dataset` | Account → Bank account (when `paid_at`, measure `amount`) |
| `field_visit_dataset` | Premise → Contractor (kind `visit_type`, when `visit_at`) |
| `service_order_dataset` | Premise → Contractor (kind `order_type`, when `closed_at`) |
| `prepaid_vend_dataset` | Premise → Vending point (when `vend_at`, measure `amount`) |
| `utility_links_dataset`, `utility_bank_links_dataset` | Utility entity → Utility entity (kind `kind`, when `ts`, measure `amount`) |

The link Datasets mix premises, contractors, accounts and the rest in one `src` and one `dst` column, so their ends
hold the one generic type *Utility entity*. In the Start box, picking `utility_links_dataset` sets that type.
