---
type: Feature
title: "Platform — entitlements"
description: "How a signed entitlement narrows what an install runs: issuing one with tools/entitle.mjs, installing it beside the bundle, what it switches off, what it does not narrow yet, and how the issuer key is kept."
tags: [platform, admin-guide, entitlement, licence]
timestamp: 2026-11-11T00:00:00Z
---

# Platform — entitlements

An **entitlement** says what one customer's install may run: the Product, the Edition, the Apps and Add-ons bought, and
the optional modules those bring. It is a signed file. The server verifies it offline at boot, and it never phones
home (D-PA22, product assembly plan §12).

## What it does

- **No file: today's behaviour** (D-PA23). Every module the build carries runs, and the Investigations Add-on follows
  `-Daddons.laInvestigations` as before. `/bootstrap` tells an administrator `entitlement: {installed: false}`.
- **A verified file only narrows.** An optional or provider module the file does not list is not entitled. A module the
  build lacks cannot be switched on by a file, and base and internal modules always run. For a module left out:
  - its routes answer `503 CAPABILITY_UNAVAILABLE`, saying the module is "not in this installation's entitlement";
  - `/bootstrap` reports its features false, with that reason in `featureReasons`, so the screens hide it;
  - `/modules` lists it `INERT` with that reason, and every module that requires it goes inert too;
  - an App whose modules are inert drops out of the Product's nav;
  - its Job types are not registered, so a Job of that type is refused as an unknown type.
- **The Investigations Add-on** follows the file's `addons` (`laInvestigations`). The install setting is ignored
  while a file is installed.
- **Refused at boot,** naming the file:
  - a file without its signature;
  - a changed byte;
  - an unknown key id;
  - a bad field;
  - a file issued for another Product or Edition than the build.

  A file that does not verify is never treated as "no file".
- **The administrator's summary.** `/bootstrap.entitlement` gives the customer, Product, Edition, Apps, Add-ons, node
  count, `notBefore`, `expires` and `daysLeft`. It is shown only to a caller with `canAdminister`, or to everyone when
  there is no sign-in.

## Issue and install one

```bash
node tools/entitle.mjs issue --customer acme --title "Acme Ltd" --product inspecto --edition standard \
  --addons screening --nodes 2 --expires 2027-11-11 --key product-map/private/keys/inspecto-issuer-1.pem
```

- **What `issue` checks.** The Product, its Editions and its Apps; that each Add-on is built in the Edition; and that
  each App's Add-ons are present.
- **What it adds.** It grows the Add-ons by their closure, as the Add-on picker does: Screening brings Incidents,
  LA Investigations, Scoring Lists and Workflow SLA. It then lists the optional modules the customer may run: the
  Edition's own platform (every module no Add-on owns) plus each entitled Add-on's modules.
- **Domain products.** A Domain product's Add-ons come from its Apps (`--apps`), and its required Apps must be listed.
- **Picked Platform products.** For one, pass `--product-file product-map/private/products/<id>.toon`.
- **What it writes:** `product-map/private/entitlements/<customer>/entitlement.toon` and `entitlement.sig`, which are
  gitignored.

To install, copy both files into the bundle's `conf/` folder, or point `-Dentitlement.file` at the `.toon`, then
restart. The file is read once at boot. Renewal without a restart comes with PA-7b.

To check one: `node tools/entitle.mjs verify --file <entitlement.toon>`. To read one: `node tools/entitle.mjs show --file …`.

## The issuer key

- **Key pair.** `node tools/entitle.mjs keygen --key-id <id>` makes an Ed25519 key pair.
- **The private half** goes to `product-map/private/keys/<id>.pem`, which is gitignored. Back it up: losing it means
  re-issuing every entitlement.
- **The public half** goes as a `keyId=base64` line into
  `platform/inspecto-util/src/main/resources/META-INF/inspecto/entitlement-keys.properties`, so the core jar trusts
  it. A key file dropped beside an install is never trusted.
- **The current key** is `inspecto-issuer-1`.
- **Rotating** means making a new key id and committing its public half. Old ids stay trusted until a release removes
  them, and a key id is never reused.

## Not yet

- **Expiry** is carried and shown (`daysLeft`). The 14-day grace period, then read-only (D-PA24), the `trial`
  Distribution, renewal without a restart and the Home notice are **PA-7b**.
- **The node count** is recorded and shown, not enforced (D-PA22).
- **Only part of the surface is narrowed.** A left-out module's routes, features, Job types and module state are
  closed. Its other extension points still load from the classpath: Consequences, step kinds, Collector connectors,
  maintenance tasks and boot hooks. That is `ENTITLEMENT-SPI-NARROWING-1` on the board.

## References

- The design and decisions: `docs/superpower/2026-11-10-product-assembly-plan.md` §11 (PA-7) and §12 (D-PA22…D-PA25).
- Code: `com.gamma.module.Entitlements` (`platform/inspecto-util`), `tools/entitle.mjs`. Tests: `EntitlementsTest`,
  `ControlApiEntitlementTest` (`features/inspecto-screening`), `tools/entitle.test.mjs`.
