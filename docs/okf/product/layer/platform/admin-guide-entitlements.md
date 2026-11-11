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

## Expiry, read-only and renewal (PA-7b, D-PA24)

| State | When | What runs | Who is told |
|---|---|---|---|
| `none` | no file, in a build that does not need one | everything built (D-PA23) | an administrator, on Home |
| `ok` | valid, more than 30 days left, or no expiry | what the file allows | — |
| `expiring` | 30 days or fewer left | what the file allows | an administrator, in a banner |
| `grace` | expired, for 14 days | what the file allows | an administrator, in a banner naming the first read-only day |
| `read-only` | after the grace, or a `trial` build without its file | reads only | everyone, in a banner |

- **Read-only.** Reads keep working: GET routes, plus the requests the capability manifest records as reads or
  sign-in (the exemption categories `read-shaped` such as `/bi/query`, `group-gated`, `stateless-compute`,
  `identity-flow` and `self-verifying-public`). So do dashboards and exports.
  - Every other request answers `423 ENTITLEMENT_READ_ONLY`, naming the expiry.
  - No Pipeline runs: not polled, acquired, chained or drained. A watched folder's waiting files wait, the scheduled
    Enrichment recompute and the Exchange snapshot sweep skip their ticks, and a direct run is refused (a dry run
    still runs).
  - A shared dashboard's query keeps working, and so does a provider's delivery-status callback, which only records
    the outcome of a message already sent (both `self-verifying-public` in the capability manifest).
  - Every Job fire is recorded `SKIPPED` with the reason.
  - Nothing is removed.
- **Renewal without a restart.** Replace `conf/entitlement.toon` and `.sig`: the server checks the two files every
  few seconds and takes a replacement that verifies.
  - A replacement that does not verify (a bad signature, an unknown key, another Product or Edition, not yet
    valid) is not taken. Neither is a removed file. In both cases the last verified entitlement stays in force, and
    administrators see the problem in the banner (`/bootstrap.entitlement.problem`).
  - With no file at boot, a file added later is picked up the same way.
- **A file not yet valid** (`notBefore` in the future) is refused at boot and never taken on a renewal. Issue a
  renewal with today's `--not-before`.

## The trial Distribution

`trial` is a production build that carries a customer's entitlement with an expiry. Issue the entitlement, then:

```bash
node tools/entitle.mjs issue --customer acme --title "Acme Ltd" --product inspecto --edition standard --addons screening \
  --expires 2027-01-31 --key product-map/private/keys/inspecto-issuer-1.pem
pwsh -File inspecto/package.ps1 -Product inspecto -Edition Standard -Distribution trial \
  -EntitlementFile product-map/private/entitlements/acme/entitlement.toon
```

`package.ps1` checks the file before staging it as `conf/entitlement.toon` and `.sig`. It verifies the
signature, the Product and the Edition, and refuses a trial entitlement with no expiry. A production build may
carry one with `-EntitlementFile` too; a demo build refuses it. A `trial` bundle whose file is removed boots
read-only: "no file" means everything only outside a trial build. Like every check on an install, this keeps an honest
customer honest. It is not tamper-proof: the trial flag is in `lib/product.json`.

## Not yet

- **The node count** is recorded and shown, not enforced (D-PA22).
- **Only part of the surface is narrowed.** A left-out module's routes, features, Job types and module state are
  closed. Its other extension points still load from the classpath: Consequences, step kinds, Collector connectors,
  maintenance tasks and boot hooks. That is `ENTITLEMENT-SPI-NARROWING-1` on the board.

## References

- The design and decisions: `docs/superpower/2026-11-10-product-assembly-plan.md` §11 (PA-7) and §12 (D-PA22…D-PA25).
- Code: `com.gamma.module.Entitlements` (`platform/inspecto-util`), `tools/entitle.mjs`. Tests: `EntitlementsTest`,
  `ControlApiEntitlementTest` (`features/inspecto-screening`), `tools/entitle.test.mjs`.
