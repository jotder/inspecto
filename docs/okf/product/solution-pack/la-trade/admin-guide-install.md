---
type: Guide
title: "Link Analysis trade showcase — install"
description: "Install the la-trade showcase from its three downloads (product, Space, data), start it on any edition, check what it loaded, reset it, and rebuild its data."
tags: [la-trade, admin-guide, install]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis trade showcase — install

The showcase runs on every edition, Basic included. It needs no sign-in, database server or network access.

## 1. Download

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>\inspecto-la\` |
| Space | `inspecto-la-space-la-trade-<version>.zip` | `<parent>\inspecto-la-spaces\` |
| Data | `inspecto-la-data-la-trade-<version>.zip` (about 2 MB; 11 MB unzipped) | `<parent>\inspecto-la-data\` |

`<parent>` is any folder you choose. Keep the three folders side by side:

```
<parent>\
  inspecto-la\                          the product (replace it to upgrade)
  inspecto-la-spaces\la-trade\          space.toon and config\
  inspecto-la-data\la-trade\data\       inbox\ (landed on first start) and staged\
```

## 2. Start

Create the pointer file `<parent>/inspecto-la/spaces.ref` holding one line — `../inspecto-la-spaces` (forward
slashes, so the same file works on every platform). Each Space's `space.toon` already names its own data root the
same way (`data_root: ../inspecto-la-data/<id>`), so no environment variable is needed and the three folders can be
moved or copied together after the first start. Then, Windows:

```
cd <parent>\inspecto-la
bin\serve.bat
```

Linux or macOS: `cd <parent>/inspecto-la && bin/serve.sh`. Setting `SPACES_ROOT` still works and overrides
`spaces.ref`, but a root named that way is not carried with the folder.

Open the address the server prints and pick the Space **Link Analysis trade showcase**. The Link Analysis screens are
in the product built with the Link Analysis app (`package.ps1 -Product la`).

## 3. Check that it loaded

On first start the seven Pipelines land the files in `data\inbox`. After each load, and every 5 minutes, the
`trade_links` Job rebuilds the link Dataset, and its completion runs the four Jobs that cut one Dataset per saved view.
Within a minute or two:

| Pipeline | Files | Rows landed | Rejected |
|---|---|---|---|
| `trade_company` | 1 | 2 500 | 0 |
| `director` | 1 | 3 514 | 0 |
| `trade` | 12 | 11 896 | 0 |
| `bill_of_lading` | 12 | 11 955 | 0 |
| `vessel_position` | 12 | 109 500 | 0 |
| `price_reference` | 12 | 744 | 0 |
| `trade_payment` | 12 | 28 993 | 0 |

The `trade_links` Dataset then holds about 50 000 links in six kinds (`traded_with`, `on_vessel`, `shipped_in`,
`directed_by`, `registered_at`, `paid`). The four view Datasets are small: `trade_over_invoicing_links` 23 links,
`trade_phantom_shipment_links` 90, `trade_circular_trade_links` 102 and `trade_shell_importers_links` 199.

## 4. New data arrives (optional demo step)

`data\staged` holds October 2026 for the five monthly feeds: `trade`, `bill_of_lading`, `trade_payment`, `vessel_position`
and `price_reference` (one file each). Copy each file into the matching `data\inbox\<feed>\` folder; the Pipeline
lands it and the link Datasets are rebuilt. Copy `vessel_position` and `price_reference` with the others: a B/L is only
checked when its vessel's position for that day has landed, and a trade's price only when its month's reference has.

## 5. Reset

Stop the server and delete `<parent>\inspecto-la-data\la-trade`, then unzip the data download again. The Space
configuration is untouched. Each Space's runtime state (`audit\`, `duckdb\`) is written beside its `data\`.

## 6. Advanced tier: personas, the unit-price Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `trade_unit_price_over_reference` is loaded but not armed, and the
`trade_over_reference_counts` and `trade_over_reference_alerts` Jobs run after each `trade_links` build; the second finds
no armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs people in by name
with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the seven Pipelines have landed and `trade_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-trade.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `over-invoicing` (seeded
with the exporter `CO-02275`, expanded once over `traded_with` and `directed_by` to its four buyers and its two
directors, the importer `CO-01461` and the shared director `PER-00291` among them; annotated, snapshotted), its members,
the Entity List `related-party-traders` (`CO-02275`, `CO-01461`, `PER-00291`), a Case assigned to `trade.manager` and
linked to the Investigation, an Investigation template, one unbounded expand held for a second person, and one open
Draft. To lay the Space out offline instead of unzipping the downloads, `node tools/seed-la-trade.mjs --scaffold
<bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `trade.analyst` | `trade-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `trade.manager` | `trade-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `trade.reviewer` | `la-viewer`, member as reviewer | reads `over-invoicing`; any change is refused (403) |
| `trade.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `trade_unit_price_over_reference` (WARNING) reads the one row of `trade_over_reference_counts` and
fires when one or more trades are priced over **3 times** the month's reference median **between an exporter and an
importer that share a director**. On the shipped data **42** trades are over 3 times the reference, and the Alert
carries **14**: the over-invoicing pair's 14 shipments, its only related pair. The specialty supplier `CO-02169`'s 28
trades are over 3 times too, but its eight buyers have their own directors, so they are not counted; seven other
trading pairs share a director, but none of them trades over 1.5 times the reference. A rule on the price alone would
have fired on the specialty supplier as well. The count is a running total over every landed trade, so the rule stays
met after every build (one open Alert, not one per build). It names no company; `over_invoicing` shows the pair. The
rule is not grouped by pair, because `trade_over_reference_counts` is a Job output: a `by` column cannot be checked
against its Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 62 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **14 (WARNING)**,
`seed-la-trade.mjs --apply` created 19 steps with none failed, and every persona got the answers in the table above (the
held expand ran once the manager approved it). On Basic the rule stayed unarmed and the Space had no Alert. Method and
record: [showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

## 7. Rebuild the downloads (maintainers)

| Step | Command |
|---|---|
| Regenerate the data (fixed seed, byte-identical) | `python tools/gen-la-trade-data.py` |
| Check the planted facts in the files | `node --test tools/gen-la-trade-data.test.mjs` |
| Check the link Datasets the Jobs build | `mvn -o test -pl platform/inspecto-engine -am -Dtest=TradeLinksJobGoldenTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Pack the Space and data downloads (from a commit) | `node tools/package-la-showcase.mjs la-trade` |

The generator also writes `tools/gen-la-trade-data.ground-truth.json` (each story's entities and look-alikes). It is
for testers and stays in the repository; it is not in the data download.

## References

[Brochure](brochure.md) · [User guide: tour and exercises](user-guide.md) · [Architecture](architecture.md) · [Configuration reference: data root](../../module/config/admin-guide-configuration-reference.md)

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
