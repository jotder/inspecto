---
type: Feature
title: "Link Analysis card fraud showcase — install"
description: "Install the la-card showcase from its three downloads (product, Space, data) side by side, start it, check that it loaded, drop in the staged month, reset it, and rebuild the downloads."
tags: [la-card, admin-guide, install, showcase]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis card fraud showcase — install

The showcase ships as three downloads that unzip **side by side** into one folder you choose (`<parent>`), so the
product can be upgraded without touching the Space or its data. It runs on every edition, Basic included. Layout
and seams: [showcase distribution and install layout](../la-showcase/architecture-showcase-catalogue.md#distribution-and-install-layout)
and the [configuration reference](../../module/config/admin-guide-configuration-reference.md) (`SPACES_DATA_ROOT`).

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-la-card-<version>.zip` | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-la-card-<version>.zip` (about 3 MB unzipped) | `<parent>/inspecto-la-data/` |

## Install and start

1. Unzip the product so that `<parent>/inspecto-la/bin/serve.bat` (or `serve.sh`) exists (the zip holds one
   `inspecto-deploy/` folder: its contents are what goes into `inspecto-la/`).
2. Unzip the Space zip into `<parent>/inspecto-la-spaces/` — it creates `la-card/` with `space.toon` and `config/`.
3. Unzip the data zip into `<parent>/inspecto-la-data/` — it creates `la-card/data/inbox/` and `la-card/data/staged/`.
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

## Check that it loaded

On start-up the six Pipelines land the twelve months in `data/inbox` and the `card_links` Job runs after each
load. Within a minute or two:

- `inspecto-la-data/la-card/data/<feed>/database/` holds Parquet files for `cardholder`, `card`, `merchant`,
  `card_auth`, `chargeback` and `online_account`, and nothing is under any `<feed>/quarantine` or `<feed>/errors`.
- `inspecto-la-data/la-card/data/card_links/` holds the link Dataset (about 38 000 rows, eight kinds).
- In Link Analysis, **File ▸ Open… ▸ Saved views** lists `card_testing`, `account_takeover_ring`,
  `colluding_merchant_refunds` and `common_point_of_purchase`. The [user guide](user-guide.md) has the tour and the exercises.

The Space folder holds only configuration; all runtime state (`data/`, `audit/`, `duckdb/`) is written under
`inspecto-la-data/la-card/`.

## New data arrives (October 2026)

Copy every file of `inspecto-la-data/la-card/data/staged/<feed>/` into `inspecto-la-data/la-card/data/inbox/<feed>/`
(`card_auth`, `chargeback`, `online_account`). The Pipelines land them and the Job rebuilds the links.

## Reset

Stop the product, delete `inspecto-la-data/la-card/`, and unzip the data download again. The Space configuration is
untouched.

## Advanced tier: personas, the compromised-terminal Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `card_new_cards_at_compromised_terminal` is loaded but not armed, and the
`card_compromise_counts` and `card_compromise_alerts` Jobs run after each `card_links` build; the second finds no
armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs people in by name
with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the six Pipelines have landed and `card_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-card.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `compromised-terminal`
(seeded with `M-317`, expanded once over `visited_before_fraud` to the 60 defrauded cards, annotated, snapshotted), its
members, the Entity List `cards-to-reissue` (the 10 cards that paid at `M-317` in the compromise window and were not
defrauded), a Case assigned to `card.manager` and linked to the Investigation, an Investigation template, one
unbounded expand held for a second person, and one open Draft. To lay the Space out offline instead of unzipping the
downloads, `node tools/seed-la-card.mjs --scaffold <bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `card.analyst` | `card-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `card.manager` | `card-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `card.reviewer` | `la-viewer`, member as reviewer | reads `compromised-terminal`; any change is refused (403) |
| `card.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `card_new_cards_at_compromised_terminal` (WARNING) reads the one row of `card_compromise_counts`:
a merchant is a compromised terminal when 10 or more cards with card-not-present fraud paid there in the 45 days before
it, and the rule fires when any card approved there since the first of those visits has not been defrauded yet. On the
shipped data there is one compromised terminal, `M-317`, and the Alert carries **60**: the 10 cards of the compromise
window and 50 regular customers who paid there after it (not the 60 defrauded cards, which the count leaves out). The
busiest other merchant saw 4 defrauded cards, and the busy supermarket `M-052` none, so neither is a compromised
terminal. The count is a running total, so the rule stays met after every build. It names no card or merchant;
`common_point_of_purchase` shows the terminal. The rule is not grouped by merchant, because `card_compromise_counts`
is a Job output: a `by` column cannot be checked against its Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 17 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **60 (WARNING)**,
`seed-la-card.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table above (the
held expand ran once the manager approved it). On Basic the rule stayed unarmed and the Space had no Alert. Method and
record: [showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## Rebuild the downloads (maintainers)

```bash
python tools/gen-la-card-data.py
node --test tools/gen-la-card-data.test.mjs
node tools/package-la-showcase.mjs la-card
```

The generator is deterministic (seed 20261107) and writes the ground truth to
`tools/gen-la-card-data.ground-truth.json`, which is not part of any download. The packer reads committed content
only, so commit first.

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
`circular_trade` and `hack_peel_chain`. Since 2026-11-10 the example-only sample (`la-sample`, its view `mule_layering_ring`)
is no longer part of this Space: *Try the sample* creates it on its own.
