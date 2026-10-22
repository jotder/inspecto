---
type: Spec
title: Product Map — the commercial tree, its guard, its views and customer builds (as built)
description: How the Product Map (product-map/) is shaped and kept in step with the manifests and Offerings, what the render, import and customer-build tools do, the operator decisions D-PM1 to D-PM8, and the known residuals.
tags: [product-map, editions, packaging, commercial, tooling]
timestamp: 2026-10-22T00:00:00Z
---

# Product Map — as built

**Status: BUILT 2026-10-18 (`PRODUCT-MAP-1`, slices S0–S5); distilled here 2026-10-22** from the archived
[`product-map-plan.md`](../../../../archived-documents/plans-archive/product-map-plan.md). Day-to-day use (folders,
commands) is in [`product-map/README.md`](../../../../../product-map/README.md); the words are in `GLOSSARY.md` §15.

## Shape and the one design rule

The Product Map is one TOON file per **Product Map Item** at `product-map/<kind>/<name>.toon`, id `<kind>.<name>`
(kinds: layer · module · functionality · addon · solution-pack · edition). Ids are **qualified by kind** because the
namespaces overlap (Add-on `reconciliation` and module `reconciliation`). **The map names manifest and Offering ids
and never copies their technical facts** (roles, dependencies, which Edition holds what); tools look them up.
Edition membership is derived from `offerings/*.toon` (`includes` resolved; base / internal modules and Industry
packs are in every Edition), never hand-kept. A customer-specific build is technically just another Offering; the
map adds the commercial layer (billability, Order Code, billing model, support, the parent tree).

## Decisions (operator, 2026-10-18)

| Id | Decision |
|---|---|
| D-PM1 | Words: **Product Map**, **Product Map Item** (with a `billable` flag), **Functionality**, **Order Code** — not *Catalog Item* (Catalog is the Schema / Dataset library) and not *SKU* |
| D-PM2 | The TOON files are the master copy; CSV, XLSX, the mind map (and later DuckDB) are generated; commercial columns come back only through the import |
| D-PM3 | Real prices and customer records live outside git in `product-map/private/` (gitignored, so it needs its own backup) |
| D-PM4 | Any item can be billable; each declares `enforcement: build \| enabled-gate \| contract` — `build` only when the module's `offeringRole` is optional or provider; `enabled-gate` needs a feature id; a Functionality cannot claim `build`. There is no licence engine (`entitlementKey` stays reserved), so `enabled-gate` and `contract` are not technical enforcement |
| D-PM5 | Billing models `included`, `flat`, `per-user`, `per-space`, `per-node`; `usage` waits for a deal and metering |
| D-PM6 | The pilot customer is the made-up **demo-telco** (Standard + `INS-AO-LINK-ANALYSIS` + `INS-SP-TELCO-FRAUD`) |
| D-PM7 | In a customer build the Edition gives the *platform* (its modules no Add-on names, plus posture); only bought Add-ons, Solution packs and items are added; an item outside the Edition is refused |
| D-PM8 | A customer bundle is produced by **trimming** the Edition bundle `package.ps1` builds; `package.ps1`, `bundle-modules.mjs`, `offering-classpath.mjs` and their guards are unchanged |

## Tools and their as-built behaviour

- **`product-map-seed.mjs`** — adds items for new modules, Add-ons, packs and Editions; never overwrites. Functionality
  items are written by hand (the module pages list none). A Space Template no Offering ships is seeded
  `billable: false`, `status: preview`; an Add-on whose module another Add-on also names (`incidents` and `workflowSla`
  → `ops`) is seeded `enforcement: contract`.
- **`check-product-map.mjs`** (CI guards job) — every `module:` is a real manifest id, every non-internal module has
  an item, `parent:` resolves without cycles, billable items have an `orderCode` (unique) and `billing.model`,
  `enforcement` is possible for the module. "No price and no copied fact" is an **allowed-keys list**, so a new field
  must be added to the guard deliberately.
- **`render-product-map.mjs`** → `target/product-map/` (not committed): `product-map.xlsx` (sheets Items, Price list,
  Edition matrix; written by a built-in zip writer because the guards job has no npm dependencies), `product-map.csv`
  (UTF-8 with BOM) and `product-map.html` (collapsible mind map; parentless items get their own group). Prices merge
  from `private/prices.csv` — CSV, not TOON, because a TOON key cannot hold the `-` in an Order Code.
- **`product-map-import.mjs`** — dry run unless `--write`, which applies only when the changed tree passes the guard.
  Rows match by `id` (or `orderCode` on the Price list sheet); importable: title, billable, orderCode, billingModel,
  supportTier, supportOwner, status, listPrice, currency (empty cell removes the field). Any change to kind, parent,
  link, enforcement or docs, or an unknown id, refuses the whole import. Files are rewritten in seed key order, so an
  edit changes only its own lines. Agents never set prices.
- **`customer-build.mjs <customer> [--bundle <dir>]`** — reads `private/customers/<id>.toon`, writes the customer
  Offering and a priced bill of materials to `target/customers/<id>/` (never `offerings/`: a customer name is client
  data). BOM line types: `edition`, `bought`, `required` (kept only because a bought item needs it — charging for it is
  a decision), `installed-not-sold`. A bought Space Template's needs are derived from the manifests (`config/jobs/*`
  `type:` → `provides.jobTypes`; `config/registry/<kind>s/` → `provides.configKinds`). `--bundle` copies the Edition
  bundle, removes left-out jars, their per-jar SBOMs and unbought Space Templates, filters `modules.list`, stamps
  `edition.properties` with `customer=` / `offering=`, and adds `customer-<id>.toon` and an unpriced `customer-bom.csv`.
  Pilot 2026-10-18: a 42-jar Standard bundle trimmed to 30 jars and one Space Template; both booted healthy
  (`telco-fraud` pulled in Anomaly Detection, which pulled in `entity-list`, both as `required`).

## Known residuals

The first two are row `PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1`; S6 is row `PRODUCT-MAP-S6-1`.

- **A pack's ops config is not detected.** Configs under a Template's `config/ops/` (for example `telco-fraud`'s Case
  Rule) name no owner in any manifest, so `customer-build` cannot derive that they need `ops` / `case-management`; in
  the pilot that Case Rule loads inert. Fix: a manifest declaration for ops configs.
- **The combined edition SBOM over-declares a trimmed bundle** (`sbom/inspecto-<edition>.{cdx,spdx}.json` still lists
  removed jars; per-jar SBOMs are removed). Safe direction, not exact.
- `enabled-gate` items are not written into a default `modules.toon` (no item uses `enabled-gate` yet).
- **Not built (S6, on demand):** a DuckDB query file of the map, a support view (module → owner / runbook), `usage`
  billing once metering exists.
