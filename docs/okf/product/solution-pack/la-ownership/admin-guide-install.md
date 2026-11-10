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

## Advanced tier: personas, the listed-person Alert Rule and the seed

The same Space download carries an Advanced tier. On Basic it is **inert**: `demo-users.toon` and `roles.toon` are
ignored (there is no sign-in), the Alert Rule `ownership_path_to_listed_person` is loaded but not armed, and the
`ownership_sanctions_counts` and `ownership_sanctions_alerts` Jobs run after each `ownership_links` build; the second
finds no armed rule. On a build with sign-in it comes to life. To show it, use the demo build, which signs people in by
name with no password (Preview, Demo Auth, loopback only), and the same Space and data downloads:

```bat
cd <parent>\inspecto-demo
set SPACES_ROOT=..\inspecto-la-spaces
set SPACES_DATA_ROOT=..\inspecto-la-data
set PORT=8096
bin\serve-demo.bat
```

Once the seven Pipelines have landed and `ownership_links` has run (a minute or two), create the runtime state from a
checkout of the repository:

```bash
node tools/seed-la-ownership.mjs --base http://127.0.0.1:8096 --apply
```

It is safe to re-run; without `--apply` it only prints the calls. It creates the Investigation `sanctions-exposure`
(seeded with the list entry `SL-0137` and the customer `CUS-00528`, expanded once over `name_match`, `owns` and
`customer_is`, annotated, snapshotted), its members, the Entity List `sanctions-path-parties` (`CUS-00528`, its company
`CO-04313`, the 50 % owner `CO-04431` and the matched person `PER-03904`), a Case assigned to `ownership.manager` and
linked to the Investigation, an Investigation template, one unbounded expand held for a second person, and one open
Draft. To lay the Space out offline instead of unzipping the downloads, `node tools/seed-la-ownership.mjs --scaffold
<bundle>\spaces` does it before the first start.

| Persona (sign in as) | Role | Sees |
|---|---|---|
| `ownership.analyst` | `ownership-analyst` (lead) | everything; opens Investigations, runs graph analysis, authors Alert Rules, reveals masked ids; cannot approve its own held expand |
| `ownership.manager` | `ownership-manager` (second approver) | the held four-eyes expand in its approval inbox, the Case assigned to it; may run Jobs |
| `ownership.reviewer` | `la-viewer`, member as reviewer | reads `sanctions-exposure`; any change is refused (403) |
| `ownership.outsider` | `la-viewer`, not a member | the Datasets, but not the Investigation (404) |
| `admin` | `admin` | administers the Space; shared with the other demo Spaces, defined identically in each |

**The Alert Rule.** `ownership_path_to_listed_person` (CRITICAL) reads the one row of `ownership_sanctions_counts`:
`listed_paths` counts the ownership paths of 1 to 3 `owns` hops from a bank customer's company up to a person with a
`name_match` to the sanctions list (same name within the Jaro-Winkler threshold, same birth year, same nationality);
the rule fires when there is at least one. On the shipped data there is **1** path (`CUS-00528`'s company `CO-04313`
← 50 % by `CO-04431` ← 60 % by `PER-03904`, two hops), 1 customer exposed and 1 matched person in the whole register.
The near-namesake `PER-02226` owns a customer's company outright but has no `name_match`, so he never counts; the hidden
owner of exercise 1 sits six hops up, past the limit. A stake of any size counts. The count is a running total over the
register as it stands, so the rule stays met after every build and the open Alert is not raised twice; the register
carries no date a stake was registered, so a per-build "new path" count is not built. It names no customer or person;
`sanctioned_owner_path` shows the path. The rule is not grouped by customer, because `ownership_sanctions_counts` is a
Job output: a `by` column cannot be checked against its Schema when the Space is created.

**Checked live (2026-11-09 release).** On the Preview demo product, with all twelve showcases of that day in one server,
the 18 files landed with 0 rejected, the Alert Rule raised one OPEN Alert at **1 (CRITICAL)**,
`seed-la-ownership.mjs --apply` created 17 steps with none failed, and every persona got the answers in the table above
(the held expand ran once the manager approved it). Unlike the other link Jobs, `ownership_links` has no `*/5` cron
fallback: a file that lands while it runs is not caught up until the next landing (`LA-OWNERSHIP-NO-CRON-FALLBACK-1`).
On Basic the rule stayed unarmed and the Space had no Alert. Method and record:
[showcase release, record](../la-showcase/admin-guide-showcase-release.md#record-the-2026-11-09-release).

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

This use case also ships inside the industry Space *Banking and finance* (`la-banking`), with the ownership, card,
trade and crypto showcases. The recipe is `spaces/_industries/la-banking.toon`; `node tools/package-la-showcase.mjs
la-banking` composes one Space download and one data download.

To share one Space, trade's `company` and `payment` feeds are called `trade_company` and `trade_payment` (Pipelines,
Datasets, inbox folders). *Account*, *Company* and *Person* are declared identically by every pack that uses them, so
they merge. Every Dataset says what its id columns hold, and each Dataset of events has its own Mapping. In each link
Dataset, the ends mixing kinds keep the pack's own generic type: *Party* (ownership), *Card entity*, *Trade entity* or
*Crypto entity*.

Ownership and card both carry an Advanced tier, so the composed Space has one roster: `demo-users.toon` and `roles.toon`
are joined by user id and role name (the `ownership.*` and `card.*` personas side by side, `admin`, `fraud-analyst`,
`la-viewer` and `la-settings` defined identically in both, so they ship once), and both Alert Rules arrive with their
Jobs. The packer refuses a shared id defined differently.

Views carry use-case names, for example `hidden_beneficial_owner`, `sanctioned_owner_path`, `common_point_of_purchase`,
`circular_trade` and `hack_peel_chain`. The sample's `mule_layering_ring` keeps its name, because the first-run page
and *Follow the money* open it by that id.
