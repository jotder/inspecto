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
and seams: [showcase distribution and install layout](../la-showcase/architecture.md#distribution-and-install-layout)
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

## Advanced tier: personas, the finance-grant Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `insider_finance_grant_without_ticket` is loaded but not armed, and the
`insider_ticketless_grant_counts` and `insider_ticketless_grant_alerts` Jobs run after each `insider_links` build; the
second finds no armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs people
in by name with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the six Pipelines have landed and `insider_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-insider.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `access-creep` (seeded
with the operations manager `EMP-0007`, expanded once over `granted_by` to the 11 people whose grants it approved, the
four reports `EMP-0482`, `EMP-0636`, `EMP-1059` and `EMP-1415` among them, and to its own approver; annotated,
snapshotted), its members, the Entity List `finance-access-review` (the four reports), a Case assigned to
`insider.manager` and linked to the Investigation, an Investigation template, one unbounded expand held for a second
person, and one open Draft. To lay the Space out offline instead of unzipping the downloads,
`node tools/seed-la-insider.mjs --scaffold <bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `insider.analyst` | `insider-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `insider.manager` | `insider-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `insider.reviewer` | `la-viewer`, member as reviewer | reads `access-creep`; any change is refused (403) |
| `insider.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `insider_finance_grant_without_ticket` (WARNING) reads the one row of
`insider_ticketless_grant_counts` and fires when one or more access grants to a finance system (`AP`, `PAYROLL`,
`TREASURY`) have no ticket. On the shipped data **258** grants have no ticket, and the Alert carries **8**: the payroll
and treasury grants `EMP-0007` gave its four reports (one approver, four employees). Every other ticketless grant is to
an ordinary system such as `WIKI` or `MAIL`, which a rule on the ticket alone would have counted too. The payroll officer
`EMP-0062`, moved into payroll and granted `PAYROLL` under `REQ-163776`, is not counted, nor are the finance staff who
pick up a second finance system with a ticket. The count is a running total over every landed grant, so the rule stays
met after every build (one open Alert, not one per build); October brings no ticketless grant. The Alert names a count,
never a person; `access_creep` shows who. The rule is not grouped by approver, because `insider_ticketless_grant_counts`
is a Job output: a `by` column cannot be checked against its Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 39 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **8 (WARNING)**,
`seed-la-insider.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table above
(the held expand ran once the manager approved it). On Basic the rule stayed unarmed and the Space had no Alert. Method
and record: [showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

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
`spaces/_industries/la-enterprise.toon`, and `node tools/package-la-showcase.mjs la-enterprise` composes it with
`la-audit` (internal audit, since 2026-11-10). The two share this template's four-eyes fan-out threshold, 2000: an
industry Space states each setting once, and `la-audit` moved from 30, which refused Explore on busy entities such as
`EMP-0007` (41 links) on every edition.

Every Dataset says what its id columns hold: *Employee*, *System*, *Email domain*, *Vendor* and *Ticket*. The link
Datasets' mixed ends hold *Insider entity*. Each Dataset of events has its own Mapping: a granting manager to the
employee (`access_grant_dataset`, kind `system`), an employee to the system they read (`access_daily_dataset`), an
employee to an external mail domain (`email_external_dataset`), an approver to a requester (`expense_dataset`), the
changer to the vendor (`vendor_change_dataset`), and a manager to their report (`employee_dataset`).
