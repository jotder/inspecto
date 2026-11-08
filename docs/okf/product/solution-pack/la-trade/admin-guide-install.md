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

Windows:

```
cd <parent>\inspecto-la
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
bin\serve.bat
```

Linux or macOS: `SPACES_ROOT=../inspecto-la-spaces SPACES_DATA_ROOT=../inspecto-la-data bin/serve.sh`.

Open the address the server prints and pick the Space **Link Analysis trade showcase**. The Link Analysis screens are
in the product built with the Link Analysis app (`package.ps1 -Ui la-app`).

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

`data\staged` holds October 2026 for the five monthly feeds: `trade`, `bill_of_lading`, `payment`, `vessel_position`
and `price_reference` (one file each). Copy each file into the matching `data\inbox\<feed>\` folder; the Pipeline
lands it and the link Datasets are rebuilt. Copy `vessel_position` and `price_reference` with the others: a B/L is only
checked when its vessel's position for that day has landed, and a trade's price only when its month's reference has.

## 5. Reset

Stop the server and delete `<parent>\inspecto-la-data\la-trade`, then unzip the data download again. The Space
configuration is untouched. Each Space's runtime state (`audit\`, `duckdb\`) is written beside its `data\`.

## 6. Rebuild the downloads (maintainers)

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

This use case also ships inside the industry Space *Banking and finance* (`la-banking`), with the sample, ownership,
card, trade and crypto showcases. The recipe is `spaces/_industries/la-banking.toon`; `node tools/package-la-showcase.mjs
la-banking` composes one Space download and one data download.

To share one Space, trade's `company` and `payment` feeds are called `trade_company` and `trade_payment` (Pipelines,
Datasets, inbox folders). *Account*, *Company* and *Person* are declared identically by every pack that uses them, so
they merge. Every Dataset says what its id columns hold, and each Dataset of events has its own Mapping. In each link
Dataset, the ends mixing kinds keep the pack's own generic type: *Party* (ownership), *Card entity*, *Trade entity* or
*Crypto entity*.

Views carry use-case names, for example `hidden_beneficial_owner`, `sanctioned_owner_path`, `common_point_of_purchase`,
`circular_trade` and `hack_peel_chain`. The sample's `mule_layering_ring` keeps its name, because the first-run page
and *Follow the money* open it by that id.
