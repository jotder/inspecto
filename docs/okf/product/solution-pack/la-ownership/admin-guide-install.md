---
type: Guide
title: "Link Analysis ownership showcase — install"
description: "Install the la-ownership showcase from its three downloads (product, Space, data), start it on any edition, reset it, and rebuild its data."
tags: [la-ownership, admin-guide, install]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis ownership showcase — install

The showcase runs on every edition, Basic included. It needs no sign-in, database server or network access.

## 1. Download

| Download | File | Unzip into |
|---|---|---|
| Product | `inspecto-deploy-<platform>.zip` | `<parent>\inspecto-la\` |
| Space | `inspecto-la-space-la-ownership-<version>.zip` | `<parent>\inspecto-la-spaces\` |
| Data | `inspecto-la-data-la-ownership-<version>.zip` (about 1 MB) | `<parent>\inspecto-la-data\` |

`<parent>` is any folder you choose. Keep the three folders side by side:

```
<parent>\
  inspecto-la\                              the product (replace it to upgrade)
  inspecto-la-spaces\la-ownership\          space.toon and config\
  inspecto-la-data\la-ownership\data\       inbox\ (landed on first start) and staged\
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

Open the address the server prints and pick the Space **Link Analysis ownership showcase**.

## 3. Check that it loaded

On first start the seven Pipelines land the files in `data\inbox`; after each load the `ownership_links` Job rebuilds
the link Dataset. Within a minute or two:

| Pipeline | Rows landed | Rejected |
|---|---|---|
| `company` | 6 000 | 0 |
| `person` | 5 000 | 0 |
| `ownership` | 8 807 | 0 |
| `officer` | 8 085 | 0 |
| `customer` | 1 500 | 0 |
| `payment` (12 files) | 40 061 | 0 |
| `sanctions_list` | 400 | 0 |

The `ownership_links` Dataset then holds about 44 000 links in five kinds: `owns`, `officer_of`, `customer_is`,
`paid` and exactly one `name_match`.

## 4. New data arrives (optional demo step)

`data\staged` holds October 2026: `payment\PAYMENT_202610.csv` and `customer\CUSTOMER_20261031.csv` (8 new customers).
Copy each file into the matching `data\inbox\<feed>\` folder; the Pipeline lands it and the link Dataset is rebuilt.

## 5. Reset

Stop the server and delete `<parent>\inspecto-la-data\la-ownership`, then unzip the data download again. The Space
configuration is untouched. Each Space's runtime state (`audit\`, `duckdb\`) is written beside its `data\`.

## 6. Rebuild the downloads (maintainers)

| Step | Command |
|---|---|
| Regenerate the data (fixed seed, byte-identical) | `python tools/gen-la-ownership-data.py` |
| Check the planted facts in the files | `node --test tools/gen-la-ownership-data.test.mjs` |
| Check the link Dataset the Job builds | `mvn -o test -pl platform/inspecto-engine -am -Dtest=OwnershipLinksJobGoldenTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Pack the Space and data downloads | `node tools/package-la-showcase.mjs la-ownership` |

The generator also writes `tools/gen-la-ownership-data.ground-truth.json` (each story's entities and look-alikes). It
is for testers and stays in the repository; it is not in the data download.

## References

[Brochure](brochure.md) · [User guide: tour and exercises](user-guide.md) · [Configuration reference: data root](../../module/config/admin-guide-configuration-reference.md)

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
