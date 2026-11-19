---
type: Capability
title: Spaces & tenancy (SPC) — Purpose, requirements and the Space model
description: Part 1 of 4 of Spaces & tenancy (SPC): Purpose & scope; Requirements of record; Specification
tags: [spc, capability, spaces, tenancy, multi-space, space-root, migrator, templates, metadata-bundle, exchange, isolation]
timestamp: 2026-09-08T00:00:00Z
item: module.config
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: platform/inspecto-config
---

# Spaces & tenancy (SPC) — Purpose, requirements and the Space model

Part 1 of 4 of [Spaces & tenancy (SPC)](plan-spaces.md) · next: [Specification — Space Templates](plan-spaces-templates.md).

## 1. Purpose & scope

SPC is **the boundary between projects on one server**: where a Space's files live, how a request is bound
to exactly one Space, how a Space is created, cloned, seeded, promoted and deleted without a restart, and
what stops one Space from seeing another. Its defining property is that the per-instance engine was
**wrapped, not rewritten**: every Space owns an unchanged `CollectorService`, and the single-tenant server
is byte-identical to the pre-multi-space product.

**In scope:** `SpaceManager`, `SpaceContext`, the `SpaceRoot` layouts and the one-time `SpaceMigrator`;
`-Dspaces.root` boot; the `/spaces` CRUD and the `/spaces/{id}/…` dispatch seam; MDC-routed singleton
isolation and the `default` Space; whole-Space zip export / import with dry-run preview; the shipped template
catalog; Metadata Bundle v2 (`BundleRoutes`) and its boundary; the space-level contract of Exchange; per-Space
settings documents; the seeded isolation policies as the tenancy contract; and the SPA's space scoping.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| The isolation *policies* themselves — `PolicyEngine.SEED`, attributes, the two enforcement points | `SEC` §3.10 — SPC states the tenancy contract they deliver (SPC-5) and where it does *not* hold |
| The Offer / Share Grant lifecycle, snapshots, the `_shared/` ledger | `DAT`/`MET` — `exchange-sharing.md`; SPC owns only the Space-boundary rule |
| Which stores a Space owns and how they persist (`OperationalDb`, DuckDB vs Postgres) | `DAT` — `db-layer.md`; SPC owns the *per-Space* topology and the CWD trap |
| Path containment for config-declared paths (`PathJail`, `SafetyPolicy`) | `TOOL`/config safety — SPC owns the fact that a Space's root joins the jail union |
| Pipeline "Save as template" (`template: true`, in-Space) | `PIP` — a different template, deliberately (§6) |
| Backup / restore of a Space (`inspecto-backup`) | `OPS` — SPC owns the zip *export* the operator uses to clone; restore is a maintenance task |

## 2. Requirements of record

Five requirements from `REQUIREMENTS.md` §3.9 (that section was stripped to an index on 2026-09-09 — this file is their only home now), all recorded shipped. **One is wrong about what exists, and one
Must overstates what "isolated" means on two of three editions.** ⚠ **`EDITIONS.md`'s feature × edition
matrix is authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `SPC-1` | Isolated **Spaces** (config / data / audit / duckdb per Space), CRUD without restart, one-time migrator | Must | ✅ SHIPPED — ⚠ "isolated" is a **layout** on Basic/Standard and an **enforced boundary** only on Advanced (CP-07, SEC-06; §3.9) | All |
| `SPC-2` | Whole-Space zip export / import with dry-run preview | Must | ✅ SHIPPED | All |
| `SPC-3` | **Space Templates** (vertical blueprints: Telecom RA, Fraud, Financial Audit, Link Analysis) | Should | 🟡 **MECHANISM SHIPPED, CONTENT PARTIAL** — the server-side catalog exists; **30** templates ship under `spaces/_templates/` (counted 2026-11-15): ten packs (`orders-starter`, `business-assurance`, `telco-fraud`, `telco-ra` — the generic half of Telecom RA, §3.5 — `payment-fraud`, `mobile-money`, `aml`, `partner-settlement`, `telco-bypass`, `telco-rerating`) and twenty `la-*` Link Analysis templates (`la-showcase` and its companions, §3.5.6, and the per-industry showcases, among them `la-audit`); a Financial Audit pack in the original sense still does not exist | All |
| `SPC-4` | **Metadata Bundle v2**: selective config-only transfer with refs, provenance / `contentHash`, `requires`, drift fit-check | Should | ✅ SHIPPED 2026-07-07 (+ `authored-pipeline`, `job`, `saved-view` 2026-07-18; `connection` 2026-07-25; `enrichment` 2026-08-31) | All |
| `SPC-5` | Per-tenant ABAC | Could | ✅ SHIPPED 2026-07-24 (two seeded policies; engage only when a `space` claim is mapped) ⚠ **Named for grep:** the decision seam those policies enforce through is `AccessDecider` (`providers/inspecto-policy/.../PolicyEngine.java`, exercised by `ControlApiAccessDeciderTest`) — until 2026-09-09 that name appeared only in `REQUIREMENTS.md`. | E |

**Corrections this table makes to its predecessor**, each verified against source:

- **`SPC-3` read `SHIPPED (UI seed packs)`.** The four vertical seed packs were **mock-only TypeScript seed
  functions** in the offline mock backend, and the mock backend was **deleted on 2026-08-31**
  (`f1553136`). Nothing replaced them: the real catalog is `<spacesRoot>/_templates/<id>/template.toon`
  (`SpaceManager.templates()`, `inspecto/src/main/java/com/gamma/service/SpaceManager.java`) and
  the tree then shipped exactly one entry, `spaces/_templates/orders-starter/` ("a tiny retail-orders feed")
  — 30 since (SPC-3 row).
  The gallery has no client-side fallback and renders "This server publishes no space templates" when the
  catalog is empty. The names survive only in a UI code comment. A Should recorded green over content that
  no longer exists — and the **binding glossary** (`GLOSSARY.md` §1 "Shipped verticals: …"),
  `USER_GUIDE.md` and `okf/product/layer/product/features-product-capabilities.md` repeated it. All four are corrected with this
  spec; the four verticals are §5's lead item and need a product call (rebuild as `_templates/` entries, or
  drop the promise).
- **`SPC-1`'s "Isolated Spaces" is true of the *mechanism* on every edition and of the *boundary* only on
  Advanced.** Every Space has its own service, scheduler, event log, stores, connection registry and
  metric label, and requests are bound by the `space` MDC — but nothing in the core *denies* a
  cross-Space request; the deny exists only in `inspecto-policy`'s two seed policies (`EDITIONS.md` CP-07:
  "isolation is a *layout* in P/S; enforced by seeded policies only in E"). And one process-global flag can
  collapse the layout: a global `-D*.db.url` funnels **every** Space into one shared file (`db-layer.md`).
  The row keeps ✅ because the requirement text names the layout; the note is here so nobody reads
  "isolated" as "enforced" on Basic.

**Two more corrections in sibling docs, made with this spec:** `api-stability.md`'s release notes stated
the `DELETE /spaces/{id}` rule **backwards** ("409 unless `?purge=true`") — the code refuses **only with**
`?purge=true` and only for the last Space on disk (`SpaceRoutes.java`); and `multi-space.md` said
`SpaceMigrator` runs "on boot" — nothing but its own CLI invokes it (§3.2).

**One standing note no status token can carry:** ⚠ **`-Dspaces.root` unset is not "one Space", it is the
legacy flat layout.** `SpaceRoot.legacy()` resolves every store CWD-relative, so a default-ON DB family
mints a database file in the working directory of every Basic install and of every test JVM. The repo
learned this twice (dedup ledger; delivery receipts, which therefore default to `none`) and pins the test
side in the root `pom.xml` surefire properties (§3.8).

## 3. Specification

### 3.1 The model: one server, many Spaces, one wrapped engine

`SpaceManager` (`inspecto/src/main/java/com/gamma/service/SpaceManager.java`) hosts N `SpaceContext`s, each
a thin holder of `id` + `SpaceRoot` + manifest + **its own unchanged `CollectorService`** (the ~40-method
facade is `@PublicApi`; wrapping it kept the embedding API intact and avoided re-auditing every per-Space
lock). Two boot modes (`ControlApi.main`, `ControlApi.java`):

| Mode | When | Layout |
|---|---|---|
| **single** — `SpaceManager.single(CollectorService)` | `-Dspaces.root` unset | `SpaceRoot.legacy()`: the pre-Spaces **flat, CWD-relative** layout; byte-identical to the product before multi-space; CRUD verbs answer `409 "this server hosts a single space"` |
| **discover** — `SpaceManager.discover(root)` | `-Dspaces.root=<dir>` | every `spaces/<id>/` with a `config/` subtree boots as a Space (`SpaceRoot.under(base)`: `config/ data/ audit/ duckdb/` + `space.toon`); **zero Spaces is a clean state** (since 2026-09-25 — bundles ship no Spaces): boot WARNs, `/health` `/ready` `/spaces` answer, Space-scoped routes answer **503** "No Space is attached", `POST /spaces` creates one (it was *exit 1*) |

A `SpaceId` is `[a-z0-9][a-z0-9-]{0,62}` (`SpaceIdTest`); `default` is the default id
(`EventLog.DEFAULT_SPACE_ID`) and is not editable. `_`-prefixed directories are **sentinels, never Spaces**:
`_templates/` (the catalog) and `_shared/` (the Exchange ledger). Runtime CRUD — `create`, `createFromBundle`,
`createFromTemplate`, `update`, `delete(id, purge)` — is serialised on a `lifecycleLock`; reads are
lock-free. Committed Spaces: `default`, `demo`, `ucc` (complete) and
`uat` (a `data/` stub with no `config/` — not bootable). None of them is bundled: a bundle ships only
`spaces/_templates/`, staged from committed content by `inspecto/package-spaces.ps1`.

### 3.2 The one-time migrator

`SpaceMigrator` (`…/service/SpaceMigrator.java`) turns the flat layout into a Space directory: `configDir →
config/`, `database/ → data/`, `jobs_audit/ → audit/`, `inspecto-events/ → data/events/`, `*.duckdb` /
`*.db` (+ `.wal`) `→ duckdb/` (`plan`); idempotent (source-exists-and-target-absent gate), `--dry-run`
first, writes `space.toon`. **It is a CLI, run once by the operator** — no boot path invokes it,
and there is **no flat fallback** for a discovered server (design lock: migration required). ⚠ Absolute
`schema_file` references are not rewritten (`configuration.md`). `SpaceLayoutContract` pins the convention
directories and tolerates both `flows/` and `pipelines/` (Tier 3, 2026-08-04 — dual-read, no on-disk move).

### 3.3 The `/spaces` seam and what is bound per request

`ControlApi.dispatch` matches `SPACE_PREFIX` `^/spaces/([a-z0-9][a-z0-9-]{0,62})(/.*)$` (`ControlApi.java`)
**after** stripping `/api/v1` — the Space segment sits after the version: `/api/v1/spaces/<id>/…`. The id is
validated, the path rewritten, and the `space` **SLF4J MDC** key bound for the request and cleared in
`finally`; an un-prefixed path binds `spaces.current()` (`boundSpace`). Five
process-wide singletons resolve per Space by reading `EventLog.currentSpaceId()` — `EventLog`,
`MetricRegistry` (its `space` label), `ConnectionRegistry`, `StabilityGate`, `AcquisitionLedgers` — plus the
static per-Space maps `SpaceManager` cleans on delete (`DecisionRules`, `ConsignmentOutputStores`,
`FileStages`, `DedupLedgers`, `ProvenanceStores`). The `default` Space sets **no** MDC, so single-Space output
stays label-free. 🔴 **The MDC does not cross thread-pool boundaries**: every executor on the execution
path copies it onto its worker (`gotchas/cross-cutting.md`). `EventLog.currentSpaceId()` never returns null,
so `env.space` is always bound for ABAC (`SEC` §3.10).

**Space CRUD is server-global and un-prefixed** (`SpaceRoutes.java`): `GET /spaces` ·
`GET /spaces/_meta → {multiSpace}` (**the** capability probe — never infer mode from list length; a
fresh discover server lists `[]`) · `GET /spaces/templates` (empty — not 409 — on a single-tenant
server) · `POST /spaces {id, display_name?, description?, template?}` · `POST /spaces/import` (seed a
**new** Space from a zip) · `PUT /spaces/{id}` (rename / re-describe; `default` refused) ·
`DELETE /spaces/{id}?purge=`. **`DELETE` has two distinct 409s:** every CRUD verb on a single-tenant
server (`requireMultiSpace`), and — **only with `?purge=true`** — the **last Space directory on disk**
(`isLastOnDisk`), because `main()` refuses to boot an empty root; deregister-only on the last
Space stays allowed. The eight paths the SPA never prefixes are `SERVER_GLOBAL`: `/health`, `/ready`,
`/metrics`, `/spaces*`, `/bootstrap`, `/auth`, `/public`, `/exchange`.

### 3.4 Whole-Space zip export / import (SPC-2)

⚠ **The whole-Space zip does not import into an existing Space** (operator 2026-09-29): `POST
/spaces/{id}/import` answers a `kind: space` bundle 403 — *a whole-Space export cannot be imported into an
existing Space; import data sources one at a time, or create a new Space from it*. It seeds a NEW Space through
Space creation. See [auth-security.md](../access/admin-guide-auth-security.md) (import section).

`DataSourceRoutes` (`inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java`): `GET
/spaces/{id}/export` (the whole config tree + `space.toon`, manifest `bundle.toon` — `BundleExporter`),
`POST /spaces/{id}/import/preview` (the **dry run**: contents, `hasSpaceToon`, per-item conflicts, findings,
`valid` — writes nothing) and `POST /spaces/{id}/import[?on_conflict=overwrite]` (`BundleImporter`); a
per-data-source export exists beside it (`GET /spaces/{id}/datasources/{ds}/export`). Its closure
(`DataSourceBundleResolver.findComponentsFor`) carries the registry components that **reverse-reference** the
Pipeline: Decision Rules and Expectations whose `targetType`/`target` name it (or a bundled job), Datasets
whose `physicalRef` reads its store, and Alert Rules whose `onPipeline` names it or whose `dataset` names a
bundled Dataset (W4/W5, 2026-09-23 — `ControlApiBundleImportTest.anExportCarriesTheAlertRulesAndExpectations…`).
An Alert Rule with no `onPipeline` watches every Pipeline and stays behind; **forward** references out of a
bundled *component* (an Expectation's `refDataset`) are deliberately not chased. The Pipeline's *own* forward
reads are (2026-09-24, `DataSourceBundleResolver.findReferencesFor`): its `*_enrich.toon` companions travel,
and every Reference Dataset it joins by name travels as its `produces: reference` Pipeline + connection +
schemas, indexed in the manifest's `references` map; an import into a Space that already hosts that Reference
keeps the target's copy and names it in `referencesKept` rather than 409ing — see
[editable round-trip §22](../elt/spec-pipeline-model.md). `BundleImporter`
**jails every zip entry** — an entry resolving outside `configDir` throws (the zip-slip pin, tested
with a re-packed archive) — and **rebases** `spaces/<source>/…` path prefixes to the target Space (W3).
`POST /spaces/import` reuses the same importer to seed a brand-new Space. ⚠ `multi-space.md` enumerates the
seven CRUD routes and omits the export routes; corrected with this spec.
