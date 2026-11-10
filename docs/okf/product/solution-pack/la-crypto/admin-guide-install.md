---
type: Feature
title: "Link Analysis crypto laundering showcase — install"
description: "Install the la-crypto showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-crypto, admin-guide, install, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis crypto laundering showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase distribution and install layout](../la-showcase/architecture-showcase-catalogue.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-crypto-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-crypto-<version>.zip` (about 5 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-crypto/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-crypto/data/inbox/` and `la-crypto/data/staged/`.
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

On start-up the six Pipelines land the twelve months in `data/inbox`; the `crypto_links` Job runs after each load (and
every 5 minutes, so the last load is never missed), and each time it finishes the `crypto_screen_links` Job runs. Within a
minute or two:

- `inspecto-la-data/la-crypto/data/<feed>/database/` holds Parquet files for `wallet`, `transfer`, `cluster_hint`,
  `exchange_account`, `kyc_person` and `cashout`, and nothing is under any `<feed>/quarantine` or `<feed>/errors`.
- `inspecto-la-data/la-crypto/data/crypto_links/` holds the link Dataset (about 39 500 rows, six kinds);
  `crypto_screen_links/` holds the three screens the saved views read.
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `hack_peel_chain`, `mixer_hop_timing_match` and
  `converging_cash_out`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-crypto/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-crypto/data/staged/<feed>/` into `inspecto-la-data/la-crypto/data/inbox/<feed>/`
(`wallet`, `transfer`, `cluster_hint`, `exchange_account`, `kyc_person`, `cashout`). The Pipelines land them and the Jobs
rebuild the links. October brings about 3 900 transfers, 275 new addresses and a month of cash-outs, and one more payroll
run. There is no new hack, mixer hop or cash-out ring: the planted stories stay as they are.

## Reset

Stop the product, delete `inspecto-la-data/la-crypto/`, and unzip the data download again. The Space configuration is
untouched.

## Advanced tier: personas, the hack-trace Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `crypto_deposit_traced_to_hack` is loaded but not armed, and the
`crypto_hack_trace_counts` and `crypto_hack_trace_alerts` Jobs run after each `crypto_links` build; the second finds no
armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs people in by name with
no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the six Pipelines have landed and `crypto_links` has run (a minute or two), create the runtime state from a checkout
of the repository:

```bash
node tools/seed-la-crypto.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `hack-peel-chain` (seeded
with the hacked wallet `syn15z47fslrxrnhuwm4dppk77s0z7` and the first hop `syn1mdsja4svnugvd4chxrt2xnywxr`, expanded once
along `sent_to` to the second hop, the first deposit address and the wallet's earlier payees; annotated, narrowed to
14-18 March 2026, snapshotted), its members, the Entity List `peel-chain-accounts` (`XA-03111` to `XA-03116`), a Case
assigned to `crypto.manager` and linked to the Investigation, an Investigation template, one unbounded expand held for a
second person, and one open Draft. To lay the Space out offline instead of unzipping the downloads, `node
tools/seed-la-crypto.mjs --scaffold <bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `crypto.analyst` | `crypto-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `crypto.manager` | `crypto-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `crypto.reviewer` | `la-viewer`, member as reviewer | reads `hack-peel-chain`; any change is refused (403) |
| `crypto.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `crypto_deposit_traced_to_hack` (CRITICAL) reads the one row of `crypto_hack_trace_counts` and fires
when the coins stolen from a wallet labelled `hacked` (reported stolen from) reach one or more exchange deposit addresses
**within 5 transfers** of the theft. The wallet feed carries no theft time, so the Job takes the theft to be the hacked
wallet's **last transfer out** (a hacked wallet is emptied, and its owner stops using it) and follows each address's
transfers made at or after the coins arrived. On the shipped data the Alert carries **2**: the deposit addresses of
`XA-03111` (the first hop's peel, transfer 2) and `XA-03115` (the third hop's, transfer 4); the chain's other four deposit
addresses are 7 or more transfers out. `deposits_from_earlier_payments` is **3**: the wallet's owner paid four merchants
before the theft and their sweeps reach three deposit addresses; tracing from every transfer out of the wallet would have
counted them too. The payroll wallet's month-end run has the peel chain's shape but nothing labelled hacked pays it, so
it is never traced. The count is a running total over every landed transfer, so the rule stays met after every build (one
open Alert, not one per build). It names no address; `hack_peel_chain` shows the chain. The rule is not grouped by
address, because `crypto_hack_trace_counts` is a Job output: a `by` column cannot be checked against its Schema when the
Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 28 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **2 (CRITICAL)**,
`seed-la-crypto.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table above
(the held expand ran once the manager approved it). The Alert can open at a partial 1 while the files land; it is
refreshed in place to 2. On Basic the rule stayed unarmed and the Space had no Alert. Method and record:
[showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-crypto-data.py
node --test tools/gen-la-crypto-data.test.mjs
node tools/package-la-showcase.mjs la-crypto
```

The generator is deterministic (seed 20261113) and writes the ground truth to `tools/gen-la-crypto-data.ground-truth.json`,
which is not part of any download. The packer reads committed content only, so commit first.

## As part of the *Banking and finance* industry Space

This use case also ships inside the industry Space *Banking and finance* (`la-banking`), with the ownership, card,
trade and crypto showcases. The recipe is `spaces/_industries/la-banking.toon`; `node tools/package-la-showcase.mjs
la-banking` composes one Space download and one data download.

To share one Space, trade's `company` and `payment` feeds are called `trade_company` and `trade_payment` (Pipelines,
Datasets, inbox folders). *Account*, *Company* and *Person* are declared identically by every pack that uses them, so
they merge. Every Dataset says what its id columns hold, and each Dataset of events has its own Mapping. In each link
Dataset, the ends mixing kinds keep the pack's own generic type: *Party* (ownership), *Card entity*, *Trade entity* or
*Crypto entity*.

Views carry use-case names, for example `hidden_beneficial_owner`, `sanctioned_owner_path`, `common_point_of_purchase`,
`circular_trade` and `hack_peel_chain`. The sample's `mule_layering_ring` keeps its name, because the first-run page
and *Follow the money* open it by that id.
