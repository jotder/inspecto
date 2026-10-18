# Product Map: plan

> 🟢 **PLAN 2026-10-18 (`PRODUCT-MAP-1`): decisions D-PM1…D-PM6 were taken by the operator on 2026-10-18 (§8).
> S0 (glossary), S1 (format, seed, gitignore), S2 (guard in CI) and S3 (CSV, XLSX, mind map) are done; S4 and S5 are not built.**
> **Goal:** one place that is easy to manage, laid out like a mind map, that describes every **Product Map Item**:
> modules, Add-ons, Solution packs, **Functionalities** and **Editions**. Pricing, support and sales use it, and it
> can produce a **customer-specific build** (an Offering made of only what a customer buys). People and agents both
> edit it.

## 1. What already exists (do not duplicate it)

| Fact | Where it lives today | Owner |
|---|---|---|
| Module id, build role, offering role, binding time, dependencies, routes, config kinds, Job Types, step kinds | `META-INF/inspecto/module.toon` in each module | engineering, guard-tested |
| Edition = which modules + Add-ons + Space Templates + posture | `offerings/{basic,standard,advanced,preview}.toon` | engineering, checked by `tools/check-offerings.mjs` |
| What a bundle actually ships | `tools/bundle-modules.mjs` → `tools/offering-classpath.mjs` → `modules.list` | build |
| Module × Edition matrix (human view) | generated block in `docs/EDITIONS.md` (`tools/render-offerings-matrix.mjs`) | generated |
| Solution packs | Space Templates in `spaces/_templates/<id>`; Industry packs in `META-INF/inspecto/industry-packs/<id>/` | content |
| Per-module and per-solution narrative | `docs/stakeholders/modules/`, `docs/stakeholders/solutions/` | docs |
| Licence engine | **none, on purpose**. `entitlementKey` is reserved in the manifest only | module-taxonomy.md |

The technical layer is already built: a **customer-specific build is already just another `offerings/<id>.toon`**,
checked and packaged by the existing tools. What is missing is the **commercial layer**: billability, price,
support tier, the Functionalities a customer recognises, and the parent/child tree a mind map needs.

**Design rule:** the Product Map refers to manifest and Offering **ids**. It never copies their technical facts. A
guard keeps the two in step, the same pattern `check-offerings.mjs` already uses.

## 2. Format (D-PM2: text files are the master copy)

| Option | People | Agents | Git diff and merge | Role |
|---|---|---|---|---|
| **One TOON file per Product Map Item, in folders** | ✅ text, plus generated views | ✅ the repo's native format | ✅ line diffs, no shared hot file | **the master copy** |
| `.xlsx` | ✅ | ⚠ easy to corrupt | ❌ binary | generated; commercial columns come back through the import |
| CSV | ✅ | ✅ | ⚠ one shared file | generated; the bulk-edit format |
| DuckDB file | ⚠ | ✅ | ❌ | generated, for queries |

## 3. The data model (the mind map)

The tree follows the existing architecture layers, from foundation up to Offering:

```
Product Map
├─ Layer          foundation · platform · feature · provider · solution        (from buildRole / directory)
│  └─ Module      = a manifest id (ops, la-core, reconciliation, telecom-asn1 …)
│     └─ Functionality   what a customer recognises ("Maker-checker approvals", "29 graph algorithms")
├─ Add-on         sold module set (linkAnalysis, incidents …; from offerings addons{})
├─ Solution pack  Space Template / Industry pack (telco-fraud, aml, mobile-money …)
├─ Edition        basic · standard · advanced · preview   (= offerings/*.toon)
└─ Customer build  offerings/customer-<id>.toon (Edition + chosen Product Map Items)
```

Each Product Map Item is one file: `product-map/<kind>/<name>.toon`, with id `<kind>.<name>`. **Ids are qualified by
kind** because the namespaces overlap (Add-on `reconciliation` and module `reconciliation`). Technical facts are **looked up from the
manifest by `module:`**, never typed in here:

```
id: functionality.la-graph-algorithms
kind: functionality              # layer | module | functionality | addon | solution-pack | edition
title: Graph algorithms (29)
parent: module.la-core           # makes the tree / mind map
module: la-core                  # manifest id; guard checks it exists
billable: true
orderCode: INS-LA-ALGO           # required when billable; unique
enforcement: enabled-gate        # build | enabled-gate | contract   (D-PM4)
billing:
  model: per-node                # included | flat | per-user | per-space | per-node   (D-PM5)
support:
  tier: standard                 # community | standard | premium
  owner: la-team
  runbook: docs/ui/link-analysis-user-manual.md
status: ga                       # planned | preview | ga | deprecated
docs: docs/stakeholders/modules/link-analysis.md
```

⚠ TOON has no comment syntax. The `#` notes above are for this page only.

**No price in the repo (D-PM3).** Real prices and customer records live in `product-map/private/`, which is
gitignored (a committed `.gitignore` entry, so every shift has it):
- `product-map/private/prices.csv`: `orderCode,listPrice,currency`
- `product-map/private/customers/<id>.toon`: Edition + chosen Order Codes + agreed discounts

The generator merges them in when the folder is present, and writes empty price columns when it is not.

**Enforcement (D-PM4).** Any Product Map Item can be billable. `enforcement` says how the sale is honoured:
- `build`: the item's module is left out of the bundle. This is only valid when `offeringRole` is optional or provider.
- `enabled-gate`: the item is installed but switched off per Space (`modules.toon`). The module needs a feature id.
- `contract`: the item ships, and only the contract limits its use.

The guard checks each value against the manifest, so a Functionality cannot claim `build`.

## 4. Tools (all in `tools/`, matching the existing `.mjs` guards)

1. **`product-map-seed.mjs`** (one-off): builds the skeleton from what already exists: one item per manifest, per
   Offering `addons{}`, per Space Template and Industry pack, and per Edition. Functionality items are seeded from
   ✅ **Built (S1).** As built: Functionality items are **not seeded**, because the module pages share one
   nine-heading template and list no Functionalities, so they are written by hand. A Space Template that no Offering
   ships (the LA showcases) is seeded `billable: false`, `status: preview`. An Add-on whose module another Add-on also
   names (`incidents` and `workflowSla` both resolve to `ops`) is seeded `enforcement: contract`, since it cannot be
   left out of a build on its own. Commercial fields (billing model, support tier, Order Code) are placeholders.
2. **`check-product-map.mjs`** (guard, runs in CI with the other guards; ✅ **built (S2)** with `check-product-map.test.mjs`. As built, "no price and no copied fact" is enforced by an **allowed-keys list**, not a list of banned names, so a new field has to be added to the guard deliberately): every `module:` is a real manifest id;
   every non-internal module has an item; every `parent:` resolves (no cycles); `billable:true` has an `orderCode`
   and a `billing.model`; Order Codes are unique; `enforcement` is possible for the module; no technical fact is
   duplicated; **no price field appears outside `product-map/private/`**.
3. **`render-product-map.mjs`** (generator) writes to `target/product-map/` (gitignored, not committed). ✅ **Built (S3)**
   with `render-product-map.test.mjs`, run in the Product Map CI step:
   - `product-map.xlsx`, with three sheets: **Items** (every item, filterable by kind), **Price list** (billable items)
     and **Edition matrix** (billable items × Editions). Edition membership is derived from the Offerings
     (`includes` resolved; a base/internal module and an Industry pack are in every Edition), never hand-kept. As
     built, there is one Items sheet with a `kind` filter instead of a sheet per kind. The file is written by a
     built-in zip writer (stored entries + CRC-32), because the guards job has no npm dependencies.
   - `product-map.csv` (one row per item, UTF-8 with BOM for Excel; the bulk-edit format)
   - `product-map.html`, a collapsible mind map (standalone, light and dark, with a filter box). Parentless items get
     their own group rather than being dropped.
   - `product-map.duckdb` is deferred to S6.
   Prices are merged from `product-map/private/prices.csv` (`orderCode,listPrice,currency`). **As built this is CSV,
   not `prices.toon`**: TOON keys cannot hold the `-` in an Order Code, and pricing staff work in CSV.
4. **`product-map-import.mjs`**: CSV/Excel → files, **only for the commercial columns** (title, Order Code,
   billing model, support, status, and prices, which go to `private/`). It shows a diff and refuses changes to
   ids/parents/modules. Agents never set prices.
5. **`customer-build.mjs <customer>`**: from `private/customers/<id>.toon`, resolves the Order Codes to modules
   plus their `requires.modules` closure and writes `offerings/customer-<id>.toon`. Then the **existing**
   `check-offerings.mjs` and `package.ps1` build it. It also writes a bill of materials (Order Codes, prices,
   support tier, and each item's enforcement) for the quote and for support. Items with `enabled-gate` also go
   into the customer's default `modules.toon`.

## 5. Who edits what

| Field group | Who | How |
|---|---|---|
| ids, tree, `module:` links, enforcement, status | engineering, agents | the TOON files, guard-checked |
| Functionality text | product, agents (drafts) | the TOON files |
| prices, discounts | commercial owner **only** | Excel/CSV → `product-map-import.mjs` → `private/` |
| support tier, owner, runbook | support lead | either way |

## 6. Slices

| Slice | Delivers | Done when |
|---|---|---|
| S0 ✅ | glossary terms; decisions signed | done 2026-10-18 |
| S1 ✅ | file format + `product-map-seed.mjs` + reviewed seed + `.gitignore` entry | done 2026-10-18: 87 items (6 layers, 47 modules, 15 Add-ons, 15 Solution packs, 4 Editions) |
| S2 ✅ | `check-product-map.mjs` in the guards job | a missing module item, a bad `parent:`, an impossible `enforcement` or a committed price fails CI |
| S3 ✅ | `render-product-map.mjs`: CSV + XLSX + mind-map HTML | the price list and the Edition matrix open in Excel |
| S4 | `product-map-import.mjs` round trip | edit a price in Excel → import → a one-line change in `private/prices.csv` |
| S5 | `customer-build.mjs` + the **demo-telco** pilot (D-PM6) | `package.ps1` builds a bundle with only demo-telco's modules; the bill of materials matches |
| S6 (later) | DuckDB query file; support view (module → owner/runbook); `usage` billing once metering exists | on demand |

## 7. Risks

- **Two master copies drift.** This is avoided by linking ids and deriving Edition membership, and by having the
  guard fail. This repo has drifted with hand-kept mirror lists before.
- **`enabled-gate` and `contract` are not technical enforcement.** There is no licence engine (a recorded decision),
  so a copied bundle or a Space admin can switch an item back on. The bill of materials states the enforcement
  level of each item so this is never a surprise.
- **A bought item pulls in another.** `requires.modules` edges cross Add-ons: Screening needs `ops` and `entity-list`,
  Action Requests needs `notify-channels`, Anomaly needs `entity-list`. `customer-build.mjs` (S5) must list such
  modules on the bill of materials as "included because X needs it", so the price is a decision and not an accident.
- **Private folder lost.** `product-map/private/` is not in git, so it needs its own backup. S1 documents where.

## 8. Decisions (operator, 2026-10-18)

| Id | Decision | Why |
|---|---|---|
| D-PM1 | Words: **Product Map** (the whole), **Product Map Item** (a node, with a `billable` flag), **Functionality**, **Order Code**. | "Catalog" already means the Schema/Dataset library (GLOSSARY §3), and *SKU* is banned in the Offering entry (§15). The first choice, "Catalog Item / SKU", was revised for that reason. |
| D-PM2 | TOON files in the repo are the master copy; Excel/CSV/mind map/DuckDB are generated; commercial columns come back through the import. | reviewable, agent-safe, no binary merges in the shared checkout |
| D-PM3 | Real prices and customer records both live outside git, in `product-map/private/`. | no client data in commits; the repo is pushed |
| D-PM4 | Any Product Map Item can be billable; each one declares `enforcement: build \| enabled-gate \| contract`. | sales can price at any level, and support knows what is really installed; module boundaries stay technical |
| D-PM5 | Billing models: `included`, `flat`, `per-user`, `per-space`, `per-node`. `usage` waits until a deal needs it and metering exists. | each of the five can be counted from facts the product already holds |
| D-PM6 | The S5 pilot is a made-up customer, **demo-telco**: Standard + Link Analysis Add-on + the `telco-fraud` Space Template. | the parts already exist and are tested; no real customer data |
