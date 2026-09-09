---
type: Capability
title: Spaces & tenancy (SPC)
description: One server, many isolated Spaces — the on-disk layout and the one-time migrator, runtime CRUD and the /spaces seam, MDC-routed singleton isolation, whole-Space zip export/import, the shipped template catalog, the Metadata Bundle v2 for cross-instance promotion, the Exchange boundary, and the seeded per-tenant isolation policies. The requirement of record for the SPC area, its specification, its decisions, and what was refused.
resource: inspecto/src/main/java/com/gamma/service/SpaceManager.java, inspecto/src/main/java/com/gamma/control/BundleRoutes.java
tags: [spc, capability, spaces, tenancy, multi-space, space-root, migrator, templates, metadata-bundle, exchange, isolation]
timestamp: 2026-09-08T00:00:00Z
---

# Spaces & tenancy — capability spec (`SPC`)

> **What this page is.** The single entry point for the SPC capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Fifth of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../superpower/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §1, §14 — binding). A **Space** is a fully isolated project
> environment in one installation; activity in one Space is invisible to another. A **Space Template** is a
> reusable blueprint that instantiates a new Space — Type → Instance. A **Metadata Bundle** is a selective,
> **configuration-only** export for moving definitions between *instances*; **Bundle v2** carries its own
> refs, provenance and `requires`. Both are distinct from the **whole-Space zip** (a clone of one Space's
> config tree). The **Exchange** family shares an item *across* Spaces by grant; a **Share** is intra-Space
> (`SEC`). An **Edition** is a build flavour.

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

Five requirements in `REQUIREMENTS.md` §3.9, all recorded shipped. **One is wrong about what exists, and one
Must overstates what "isolated" means on two of three editions.** ⚠ **`EDITIONS.md`'s feature × edition
matrix is authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `SPC-1` | Isolated **Spaces** (config / data / audit / duckdb per Space), CRUD without restart, one-time migrator | Must | ✅ SHIPPED — ⚠ "isolated" is a **layout** on Personal/Standard and an **enforced boundary** only on Enterprise (CP-07, SEC-06; §3.9) | All |
| `SPC-2` | Whole-Space zip export / import with dry-run preview | Must | ✅ SHIPPED | All |
| `SPC-3` | **Space Templates** (vertical blueprints: Telecom RA, Fraud, Financial Audit, Link Analysis) | Should | 🟡 **MECHANISM SHIPPED, CONTENT ABSENT** — the server-side catalog exists; **one** template ships (`orders-starter`); **none of the four named verticals exists** in any shipped artifact (§2 corrections) | All |
| `SPC-4` | **Metadata Bundle v2**: selective config-only transfer with refs, provenance / `contentHash`, `requires`, drift fit-check | Should | ✅ SHIPPED 2026-07-07 (+ `authored-pipeline`, `job`, `saved-view` 2026-07-18; `connection` 2026-07-25; `enrichment` 2026-08-31) | All |
| `SPC-5` | Per-tenant ABAC | Could | ✅ SHIPPED 2026-07-24 (two seeded policies; engage only when a `space` claim is mapped) | E |

**Corrections this table makes to its predecessor**, each verified against source:

- **`SPC-3` read `SHIPPED (UI seed packs)`.** The four vertical seed packs were **mock-only TypeScript seed
  functions** in the offline mock backend, and the mock backend was **deleted on 2026-08-31**
  (`f1553136`). Nothing replaced them: the real catalog is `<spacesRoot>/_templates/<id>/template.toon`
  (`SpaceManager.templates()`, `inspecto/src/main/java/com/gamma/service/SpaceManager.java:221-257`) and
  the tree ships exactly one entry, `spaces/_templates/orders-starter/` ("a tiny retail-orders feed").
  The gallery has no client-side fallback and renders "This server publishes no space templates" when the
  catalog is empty. The names survive only in a UI code comment. A Should recorded green over content that
  no longer exists — and the **binding glossary** (`GLOSSARY.md` §1 "Shipped verticals: …"),
  `USER_GUIDE.md` and `stakeholders/PRODUCT_CAPABILITIES.md` repeated it. All four are corrected with this
  spec; the four verticals are §5's lead item and need a product call (rebuild as `_templates/` entries, or
  drop the promise).
- **`SPC-1`'s "Isolated Spaces" is true of the *mechanism* on every edition and of the *boundary* only on
  Enterprise.** Every Space has its own service, scheduler, event log, stores, connection registry and
  metric label, and requests are bound by the `space` MDC — but nothing in the core *denies* a
  cross-Space request; the deny exists only in `inspecto-policy`'s two seed policies (`EDITIONS.md` CP-07:
  "isolation is a *layout* in P/S; enforced by seeded policies only in E"). And one process-global flag can
  collapse the layout: a global `-D*.db.url` funnels **every** Space into one shared file (`db-layer.md`).
  The row keeps ✅ because the requirement text names the layout; the note is here so nobody reads
  "isolated" as "enforced" on Personal.

**Two more corrections in sibling docs, made with this spec:** `api-stability.md`'s release notes stated
the `DELETE /spaces/{id}` rule **backwards** ("409 unless `?purge=true`") — the code refuses **only with**
`?purge=true` and only for the last Space on disk (`SpaceRoutes.java:120-127`); and `multi-space.md` said
`SpaceMigrator` runs "on boot" — nothing but its own CLI invokes it (§3.2).

**One standing note no status token can carry:** ⚠ **`-Dspaces.root` unset is not "one Space", it is the
legacy flat layout.** `SpaceRoot.legacy()` resolves every store CWD-relative, so a default-ON DB family
mints a database file in the working directory of every Personal install and of every test JVM. The repo
learned this twice (dedup ledger; delivery receipts, which therefore default to `none`) and pins the test
side in the root `pom.xml` surefire properties (§3.8).

## 3. Specification

### 3.1 The model: one server, many Spaces, one wrapped engine

`SpaceManager` (`inspecto/src/main/java/com/gamma/service/SpaceManager.java`) hosts N `SpaceContext`s, each
a thin holder of `id` + `SpaceRoot` + manifest + **its own unchanged `CollectorService`** (the ~40-method
facade is `@PublicApi`; wrapping it kept the embedding API intact and avoided re-auditing every per-Space
lock). Two boot modes (`ControlApi.main`, `ControlApi.java:379-397`):

| Mode | When | Layout |
|---|---|---|
| **single** — `SpaceManager.single(CollectorService)` | `-Dspaces.root` unset | `SpaceRoot.legacy()`: the pre-Spaces **flat, CWD-relative** layout; byte-identical to the product before multi-space; CRUD verbs answer `409 "this server hosts a single space"` |
| **discover** — `SpaceManager.discover(root)` | `-Dspaces.root=<dir>` | every `spaces/<id>/` with a `config/` subtree boots as a Space (`SpaceRoot.under(base)`: `config/ data/ audit/ duckdb/` + `space.toon`); **zero Spaces ⇒ exit 1** |

A `SpaceId` is `[a-z0-9][a-z0-9-]{0,62}` (`SpaceIdTest`); `default` is the default id
(`EventLog.DEFAULT_SPACE_ID`) and is not editable. `_`-prefixed directories are **sentinels, never Spaces**:
`_templates/` (the catalog) and `_shared/` (the Exchange ledger). Runtime CRUD — `create`, `createFromBundle`,
`createFromTemplate`, `update`, `delete(id, purge)` — is serialised on a `lifecycleLock`; reads are
lock-free. Committed Spaces: `default`, `demo`, `ucc` (complete, mirrored into `inspecto-deploy/spaces/`) and
`uat` (a `data/` stub with no `config/` — not bootable, not mirrored).

### 3.2 The one-time migrator

`SpaceMigrator` (`…/service/SpaceMigrator.java`) turns the flat layout into a Space directory: `configDir →
config/`, `database/ → data/`, `jobs_audit/ → audit/`, `inspecto-events/ → data/events/`, `*.duckdb` /
`*.db` (+ `.wal`) `→ duckdb/` (`plan`, `:69-81`); idempotent (source-exists-and-target-absent gate), `--dry-run`
first, writes `space.toon`. **It is a CLI, run once by the operator** (`:155-176`) — no boot path invokes it,
and there is **no flat fallback** for a discovered server (design lock: migration required). ⚠ Absolute
`schema_file` references are not rewritten (`configuration.md`). `SpaceLayoutContract` pins the convention
directories and tolerates both `flows/` and `pipelines/` (Tier 3, 2026-08-04 — dual-read, no on-disk move).

### 3.3 The `/spaces` seam and what is bound per request

`ControlApi.dispatch` matches `SPACE_PREFIX` `^/spaces/([a-z0-9][a-z0-9-]{0,62})(/.*)$` (`ControlApi.java:189`)
**after** stripping `/api/v1` — the Space segment sits after the version: `/api/v1/spaces/<id>/…`. The id is
validated, the path rewritten, and the `space` **SLF4J MDC** key bound for the request and cleared in
`finally` (`:665-688`); an un-prefixed path binds `spaces.current()` (`boundSpace`, `:1038-1043`). Five
process-wide singletons resolve per Space by reading `EventLog.currentSpaceId()` — `EventLog`,
`MetricRegistry` (its `space` label), `ConnectionRegistry`, `StabilityGate`, `AcquisitionLedgers` — plus the
static per-Space maps `SpaceManager` cleans on delete (`DecisionRules`, `ConsignmentOutputStores`,
`FileStages`, `DedupLedgers`, `ProvenanceStores`). The `default` Space sets **no** MDC, so single-Space output
stays label-free. 🔴 **The MDC does not cross thread-pool boundaries**: every executor on the execution
path copies it onto its worker (`gotchas/cross-cutting.md`). `EventLog.currentSpaceId()` never returns null,
so `env.space` is always bound for ABAC (`SEC` §3.10).

**Space CRUD is server-global and un-prefixed** (`SpaceRoutes.java`): `GET /spaces` (`:45`) ·
`GET /spaces/_meta → {multiSpace}` (`:52`, **the** capability probe — never infer mode from list length; a
fresh discover server lists `[]`) · `GET /spaces/templates` (`:56`, empty — not 409 — on a single-tenant
server) · `POST /spaces {id, display_name?, description?, template?}` (`:58`) · `POST /spaces/import` (`:60`,
seed a **new** Space from a zip) · `PUT /spaces/{id}` (`:62`, rename / re-describe; `default` refused) ·
`DELETE /spaces/{id}?purge=` (`:64`). **`DELETE` has two distinct 409s:** every CRUD verb on a single-tenant
server (`requireMultiSpace`), and — **only with `?purge=true`** — the **last Space directory on disk**
(`:120-127`, `isLastOnDisk`), because `main()` refuses to boot an empty root; deregister-only on the last
Space stays allowed. The eight paths the SPA never prefixes are `SERVER_GLOBAL`: `/health`, `/ready`,
`/metrics`, `/spaces*`, `/bootstrap`, `/auth`, `/public`, `/exchange`.

### 3.4 Whole-Space zip export / import (SPC-2)

`DataSourceRoutes` (`inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java:38-41`): `GET
/spaces/{id}/export` (the whole config tree + `space.toon`, manifest `bundle.toon` — `BundleExporter`),
`POST /spaces/{id}/import/preview` (the **dry run**: contents, `hasSpaceToon`, per-item conflicts, findings,
`valid` — writes nothing) and `POST /spaces/{id}/import[?on_conflict=overwrite]` (`BundleImporter`); a
per-data-source export exists beside it (`GET /spaces/{id}/datasources/{ds}/export`). `BundleImporter`
**jails every zip entry** — an entry resolving outside `configDir` throws (`:84-104`, the zip-slip pin, tested
with a re-packed archive) — and **rebases** `spaces/<source>/…` path prefixes to the target Space (W3).
`POST /spaces/import` reuses the same importer to seed a brand-new Space. ⚠ `multi-space.md` enumerates the
seven CRUD routes and omits the export routes; corrected with this spec.

### 3.5 Space Templates (SPC-3)

A template is a **server-global catalog entry**, deliberately **not a Component kind** (product owner,
2026-07-03): `<spacesRoot>/_templates/<id>/template.toon` (`{id, name, tagline, description, icon,
contents[]}` — the UI's `SpaceTemplateInfo`) beside a `config/` tree and an optional `data/`.
`createFromTemplate` (`SpaceManager.java:269-318`) mints the convention directories, copies `config/`
**rewriting `${SPACE}` tokens** in every `.toon` (template configs address their own Space as
`spaces/${SPACE}/…` — the portable bare-form the product writes, W3) and copies `data/` verbatim. An
unreadable template is warned and skipped, never fatal. The SPA's gallery (`SpaceTemplateGalleryDialog`) is
two-step ask-the-minimum and renders whatever the server publishes. **What is published: one template,
`orders-starter`** — a pipeline, a quality rule, a dataset and a live dashboard. Nothing named Telecom,
Fraud, Financial Audit or Link Analysis exists (§2, §5).

### 3.6 Metadata Bundle v2 (SPC-4) — configuration moves, data never does

A bundle is a *serialised, self-describing subgraph* of the component graph for **instance-to-instance
promotion** (staging → production). v2 (R6, 2026-07-06) adds `items[].refs` (each `included | external`),
`items[].provenance` (`sourceSpace`, `exportedAt`, `contentHash` = SHA-256 of the canonical JSON) and
top-level `requires` (the deduped external refs — the bundle's contract with the target); v1 files stay
importable. `BundleRoutes` (`inspecto/src/main/java/com/gamma/control/BundleRoutes.java`):

- **`POST /bundle/export`** (`:115`) — real content + real `contentHash`; each resolvable `requires` ref is
  stamped with an `originHash`; an unsupported kind is a **422** — an honest boundary, never a silent
  omission. Since 2026-08-31 the UI seeds an authored pipeline's closure from `GET /pipelines/{name}/related`,
  because the client derived edges from `nodes[].use` alone and a grammar bound by config key travelled
  nowhere.
- **`POST /bundle/preview`** (`:116`) — the read-only **fit-check**: per item `new | unchanged | drifted |
  unsupported`; per `requires` entry `satisfied | different | missing` (`different` = present at another
  version).
- **`POST /bundle/import`** (`:118`) — `canAuthorWorkbench` → write-root 503 → **integrity pre-check 422**
  (MNT-16: only findings the import would *introduce*) → sequential upsert in `APPLY_ORDER` (a referenced
  kind precedes its referencer: `connection, grammar, mapping, schema, transform, sink, dataset, query,
  widget, dashboard, reconciliation, authored-pipeline, enrichment, job, saved-view`, `:104-107`); existing
  items default to skip (per-item `overwrite`); identical hash ⇒ `unchanged` — **idempotent re-promotion**;
  per-item outcomes, the batch never aborts. Every kind is served through one `BundleSource` seam whatever
  its backing store.

**Boundary and invariants.** **Data never travels** — a dataset item is metadata; runs, batches, Incidents,
watermarks and server TOON are out of scope by design. **Secrets never travel** — a `connection` exports
**reference-only**: `ConnectionProfile.toBundleMap()` **omits** literal secret keys (⛔ not masks — a `***`
sentinel would be a persisted lie that round-trips as a value), only `${ENV:…}` references remain, and import
**fails the item** on any present, non-blank, non-reference secret-shaped field — defence in depth. The
on-disk key spelling (`base_path`) is the bundle's, not the API's. In a manifest **`schema` means the
registry id** (operator, 2026-09-06). Schema: `docs/api/schemas/metadata-bundle.schema.json` + two samples —
⚠ **no Java test validates a bundle against that schema**; it is documentation (§8.6).

### 3.7 The Exchange boundary (cross-Space, by grant)

`inspecto-exchange` (Standard+, EDG-01 cell 4) roots at `spaces/_shared/` — reserved, never a Space —
holding `offers.toon` (owner-listed Datasets / Widgets / saved views, catalog metadata only) and `grants.toon`
(the `ShareGrant` ledger tying an offer to a consumer Space). Sharing is per-item, opt-in, grant-mediated,
**read-only**, fail-closed; **cross-Space writes never**; **Schema sharing refused** (a Dataset's Result Set
is self-describing); Dashboards, Pipelines, Jobs, Queries and Expectations are integral to their Space and
move only by *copy* (a bundle). Every route 409s outside a multi-Space runtime, and `features.exchange` is
derived from `hasRoute`, not from "a container root exists" — a Personal install with `-Dspaces.root` once
advertised a Share button that 404'd. Mechanism: `exchange-sharing.md`.

### 3.8 Per-Space stores, settings documents, and the CWD trap

Everything under a `SpaceContext` is per Space: `OperationalDb`, `EventStore`, `StatusStore`,
`ComponentStore` / registry, `JobService` (the scheduler). The per-Space **settings documents** live under
the Space's config root and are bound by `Roles.ATTR_CONFIG_ROOT`, stamped pre-auth by `ControlApi`
(`:767`): `roles.toon`, `access-policies.toon` (both **fail closed** when unreadable — `SEC` §3.6, §3.10),
`branding.toon`, `geo.toon`, `icon-map.toon`, `nav-menus.toon` (missing ⇒ shipped defaults). Store
**backends** are process-global `-D` flags (`-Dinspecto.db=duckdb|postgres`, `-Dstatus.backend`, …) — the
selection, not the files, is shared, and an unhonourable `postgres` **fails at boot** (`verifySelectable`,
PG-1). Each Space's root joins the config-path jail as a *discovered* root (`SafetyPolicy.defaultPolicy()` =
declared ∪ discovered, registered before `SpaceBootstrap.load`); ⛔ the jail is **not** rooted at
`-Dspaces.root` and config paths are never resolved against the Space root — every shipped config authors
from the server root.

🔴 **The CWD trap.** `SpaceRoot.legacy()` is CWD-relative by design, so a store family that is **ON by
default** writes a database file into the working directory of every single-tenant install and every test
JVM. Two instances: the dedup ledger (fixed by pinning `-Ddedup.ledger.backend=jdbc:duckdb:`,
`-Dconsignment.outputs.backend=jdbc:duckdb:` and `-Dstatus.backend=jdbc:duckdb:` in the root `pom.xml`
surefire properties — ⛔ as the *backend* value, not `*.db.url`) and delivery receipts (which therefore
default to `none`). Any new DB-backed family must decide its default with this in mind.

### 3.9 What "isolated" means per edition (SPC-5)

On every edition a Space is a **layout**: its own directory, service, scheduler, ledger, stores, registry,
MDC label. On **Enterprise** it is also a **boundary**: `PolicyEngine.SEED` ships `space-isolation` (route)
and `space-isolation-rows` (row) — deny when the subject's mapped `space` home claim ≠ the bound Space,
engaging **only when a `space` claim is mapped** in `roles.toon` (unmapped ⇒ no isolation, never a bricked
API), exempting `canConfigureAccess` holders, overridable per policy name in `access-policies.toon`. The
policy detail is `SEC` §3.10; the tenancy fact is here: **Personal and Standard do not enforce tenancy** —
any authenticated subject can address any Space by URL. That is the deliberate edition placement of
2026-07-23, not a gap; it is stated because `SPC-1` reads "isolated" for `All`.

### 3.10 The SPA

`SpacesService` holds the active id (restored from `localStorage`), probes `GET /spaces/_meta` and lists
`GET /spaces` in parallel; the header **space-switcher** appears only when `multiSpace` and Spaces exist, and
switching **hard-reloads** at the lens home. The `spaceInterceptor` rewrites `/api/v1/<path>` →
`/api/v1/spaces/<id>/<path>` for every feature call, no-ops with no active Space, and exempts the eight
`SERVER_GLOBAL` paths — so **every other feature stays space-agnostic**; the Spaces admin view and the
switcher are the only space-aware UI. Per-Space branding is edited in the Space form (`BrandingService`
re-fetches on Space change). The single `ImportBundleDialog` serves both "create from zip" and "import into
existing" with the dry-run's conflict / finding sections and an overwrite checkbox. The Metadata Bundle has its
own `TransferMenuComponent` ("export with dependencies / export this only / import…", Import gated
`canAuthorWorkbench`) on pipeline, dashboard, dataset, geo-map, link-analysis and widget editors and lists —
⚠ the glossary's "every editor" is broader than the ten consumers found. ⚠ `/bootstrap` emits
`features.multiSpace` and the SPA does not read it — by the stated convention it asks `/spaces/_meta`.

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### The model

| Date | Decision | Why |
|---|---|---|
| 2026-07 (design lock) | **One server, many Spaces; wrap `CollectorService`, never rewrite it** | the facade is `@PublicApi` and ~40 methods; a rewrite would break embedders and force re-auditing every lock |
| 2026-07 (design lock) | Layout `spaces/<id>/{config, data, audit, duckdb}` + `space.toon`; **migration required, no flat fallback** for a discovered server | one layout to reason about; the flat layout survives only as single-tenant `legacy()` |
| 2026-07 (design lock) | Per-request binding is an explicit **`ApiContext` view + MDC**, ⛔ not a `ThreadLocal<SpaceContext>` | implicit ambient state under a virtual-thread executor |
| 2026-07 (design lock) | Editions and access control stay out of the core — ⛔ no `if (edition == …)` | the `ServiceLoader` SPI seam is the only place they enter (`SEC`) |
| 2026-07-07 | Singleton isolation via the **`space` SLF4J MDC**; `default` sets none | byte-identical single-Space output; five singletons need no signature change |
| 2026-07-07 | The Space segment sits **after** `/api/v1` | dispatch strips the version first; the SPA composes the same way |
| 2026-07-07 | The mode probe is **`GET /spaces/_meta`**, never the list length | a fresh discover server lists `[]` |
| 2026-07-25 (D4) | `DELETE /spaces/{id}?purge=true` **refused with 409 for the last Space on disk**; deregister-only stays allowed | `main()` refuses to boot an empty root; the predicate is the exact negation of the boot condition, counted on disk not in memory |
| 2026-08-04 (Tier 3) | `config/flows/` → `pipelines/`, **dual-read, no on-disk move**; `SpaceLayoutContract` allows both | a rename of a directory every Space owns is a migration, not a commit |
| 2026-08-14 | `PathJail` roots are `-Dassist.safety.roots` ∪ **discovered Space roots**, ⛔ never `-Dspaces.root`; config refs resolve against the **CWD**, never the Space root | the flag is unset in single-tenant mode and in the job runner; every shipped config authors from the server root |
| 2026-08-14 (PG-1) | One backend selection `-Dinspecto.db=duckdb|postgres`; an unhonourable `postgres` **fails at boot** | deployments had come up "healthy" with stores silently switched off |
| 2026-08-15 | The operational-DB screen reports and validates, **never writes** | the process serving the UI cannot configure its own dependency |
| 2026-09-07 (D8) | Delivery receipts default **`none`** — the opposite call from the dedup ledger | default-ON would mint a DB file in the CWD under `legacy()` on every Personal install |
| 2026-09-07 (EDG-01 cell 4) | `features.exchange` derives from **`hasRoute`**, not from "a container root exists" | a Personal install with `-Dspaces.root` advertised a Share button that 404'd |

### Templates, bundles, exchange

| Date | Decision | Why |
|---|---|---|
| 2026-07-03 (product) | A Space Template is a **server-global catalog entry, not a Component kind** | templates carry seed content that instantiates a Space; a Component lives *inside* one |
| 2026-07-06 (R6) | **Bundle v2**: refs + provenance + `requires`, additive; v1 stays importable | the target must validate without re-deriving lineage on both sides |
| 2026-07-07 | An unsupported bundle kind is a **422**, never a silent omission | an honest boundary beats a bundle that looks complete |
| 2026-07-25 (D2) | `connection` travels **reference-only, secrets stripped not masked**; import fails an item carrying a raw secret | a bundle lands in git, CI and support tickets; a `***` sentinel is a persisted lie |
| 2026-07-26 (D9) | Widen the Exchange `kind` axis (`Offer.datasets`) rather than build a parallel view-sharing mechanism; explicit `snapshot` on a view is **422** | silently coercing to `live` would hide a misunderstanding of what a view is |
| 2026-07-26 (operator, D16 **overturned**) | **No dedicated system Space**; shipped pattern packs fork per Space | a `_`-sentinel Space either warns every boot or is unreachable via `/spaces/{id}/…`; the two costs are recorded so the shape is not re-proposed blind |
| 2026-08-31 | Bundle gains `enrichment`; the apply-order invariant is "referenced before referencer", not "every kind listed" | a pipeline travelled without its derived columns |
| 2026-09-06 (operator) | In a manifest, **`schema` means the registry id**; a pipeline-owned `<name>_schema.toon` travels under its own kind | one word, one meaning on the wire |
| 2026-09-06 (operator) | **Postgres multi-user is PARKED** until a multi-operator install exists | no consumer; the plan is archived and BACKLOG §6 requires it to exist before code |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| **Postgres multi-user** — pool behind `JdbcDrivers`, replace `browseConnection()`, **schema-per-Space** URL wiring, `CaseStore` PG impl, concurrency test | `BACKLOG.md` §3 *Postgres multi-user* — ⛔ **PARKED by §6**; `EDITIONS.md` OPS-03 | Isolation on Postgres is a **schema**, not a database (a connection binds one database) |
| Canonical-pipeline selective bundle export / import; retire the `authored-pipeline` kind that still targets the retired `PipelineStore` | `BACKLOG.md` §3 *Canonical-pipeline selective bundle export/import* | |
| Bundle residuals — `requires` present-but-different classification; per-editor "load as draft" import | `BACKLOG.md` §3 *Bundle / Exchange* | ⛔ do not fake a draft with a cross-kind `enabled: false` |
| Space-to-space comparison (Maintenance COULD tier) | `BACKLOG.md` §3 *Job framework* | Builds on the preview's drift classification |
| Cross-Space controller / connector-direct emission (S8) | `BACKLOG.md` §3 | Optional Signal-network slice |
| Enterprise distributed tier — shared-state backends, distributed scheduler | `BACKLOG.md` §2 *E1*; `REQUIREMENTS.md` NFR-8 | Design only |
| Row / column-level sharing policy | design §6 of the archived sharing plan | Not filed as a row; listed in `exchange-sharing.md` as future |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-GLOSSARY-1`, `SPEC-MOCKRESIDUE-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **The four vertical Space Templates** (Telecom RA, Fraud, Financial Audit, Link Analysis) | `ls spaces/_templates` → `orders-starter` only; the packs died with the mock backend `f1553136` | A Should the register, the binding glossary, the user guide and the stakeholder capabilities page all call shipped. Product call: rebuild as `_templates/` entries or drop the promise |
| **Nothing validates a bundle against `metadata-bundle.schema.json`** | zero Java references to the schema file | The documented contract for the promotion artifact is unenforced; the two samples can drift silently |
| **Ingested-data cloning between Spaces** | the design lock said "roadmap (future)"; no row | The zip and the bundle move config only; a Space clone with data is manual |
| **A multi-tenant user-visibility model** ("users see only the Spaces they may access") + a superuser console | the design lock's open item; nearest live surface is SEC-06 | On Standard every authenticated subject can address every Space |
| **`uat` is a half Space** — `data/` with no `config/` or `space.toon`, not bootable, not mirrored | `spaces/uat/` | Either finish it or delete it; a discover boot ignores it silently |
| **`multi-space.md` omits the export routes** and says the migrator runs on boot | `DataSourceRoutes.java:38-41`; `SpaceMigrator` has no non-CLI caller | Corrected with this spec |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 A `ThreadLocal<SpaceContext>` — REFUSED (design lock)

It works under the virtual-thread executor and is implicit ambient state. The binding is an explicit
request-scoped view plus the MDC, and the MDC is copied onto every worker deliberately (§3.3).

### 6.2 A dedicated system Space for shipped pattern packs — OVERTURNED (operator, 2026-07-26, D16)

A `_`-sentinel directory with `config/` passes `SpaceManager.discover` and then dies in `SpaceId.of`; one
without `config/` is unreachable via `/spaces/{id}/…`. Per-Space forking of packs is accepted; the two costs
are recorded here so the shape is not re-proposed blind.

### 6.3 Space Templates as a Component kind — REFUSED (product owner, 2026-07-03)

Templates are server-global and carry seed *content*; a Component lives inside the Space a template creates.
The catalog is a directory of `template.toon` files, the API is `GET /spaces/templates` + `POST /spaces
{template}`. (The **pipeline** "Save as template" is a different, in-Space concept — `template: true` on a
registered pipeline, `PIP` — and the two must not be conflated.)

### 6.4 Sharing what belongs to a Space — REFUSED (sharing design, 2026-07-09 → 07-19)

**Schema sharing** (a Dataset's Result Set is self-describing; the consumer never needs the producer's
ETL-side Schema); sharing Dashboards, Pipelines, Jobs, Queries or Expectations (integral to their Space —
*copy* semantics belong to bundles); wholesale or public scope; **cross-Space writes, ever**; per-row/column
masking in v1. Coercing an explicit `snapshot` on a shared view to `live` — refused: it would hide a real
misunderstanding.

### 6.5 Secrets in a bundle in any form — REFUSED (D2, 2026-07-25)

Not plaintext, not bundle-encrypted (a bundle lands in git, CI and support tickets), and not masked (`***`
round-trips as a value). Strip, and fail the item on import if a raw secret is present anyway.

### 6.6 Jail and path decisions — REFUSED (2026-08-14)

Rooting `PathJail` at `-Dspaces.root` (only `ControlApi` reads it, it sits above the engine, and it is unset
in single-tenant mode and the job runner); resolving relative config refs against the Space root (every
shipped config authors from the server root — a double prefix would break every Space); a per-Space
**static** safety-root list (superseded by declared ∪ discovered); `SafetyPolicy`'s silent `[CWD]`
substitution for an empty root list (falsified by a one-file probe; empty stays empty and throws).

### 6.7 Postgres shapes — REFUSED (archived plan, parked 2026-09-06)

**Database**-per-Space (a PG connection binds one database ⇒ N pools of one; isolation is a **schema**);
routing operational reads through the postgres-duckdb plugin (wire-protocol scans compete with OLTP);
customer-run PgBouncer (an in-process pool if anything); an edition Maven profile gating the JDBC dependency
(the sidecar mechanism already existed); a `PUT` on the operational-DB screen.

### 6.8 Backup shapes — REFUSED (maintenance plan)

Incremental / differential / point-in-time backup, backup encryption, resume-interrupted-backup: Inspecto is
a file-based **per-Space appliance**; a full config + DuckDB snapshot is cheap and restorable, encryption is
the operator's filesystem call, "snapshots are small; re-run is the resume". Restore routed through bundle
import was refused too: the bundle covers component kinds, the zip covers the whole config tree.

### 6.9 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| The four mock-only vertical seed packs (`telecom-ra`, `fraud-mgmt`, `financial-audit`, `link-analysis`, registered in the mock's `_server` pseudo-Space) | the server catalog `_templates/<id>/template.toon`, shipping `orders-starter` only (mock deleted 2026-08-31) | this spec §2; `REQUIREMENTS.md` UI-4 |
| Mock-first UI operation | a real `ControlApi` is required (2026-08-31) | `REQUIREMENTS.md` UI-4 |
| Bundle v1 `{kind, id, content}` with both-side ref re-derivation | v2 self-describing subgraph | `metadata-bundle.md` |
| `schema` as a bundle kind | retired 2026-07-31 (unification W1); kept in the TS type so old bundles parse | `metadata-bundle.md` |
| `Offer.dataset` (scalar) + four `if ("widget".equals(kind))` blocks | `Offer.datasets` + `Exchange.DERIVED_KINDS`; the scalar is read, never written | `exchange-sharing.md` |
| `DirSpaceRoot.flowsDir()` as a sibling `spaces/<id>/flows/` | `config().resolve("flows")`; the top-level dir is dead but tolerated | `PROJECT_NOTES.md` |
| `config/flows/` | `pipelines/` (dual-read) | `GLOSSARY.md` §13 |
| Per-store WARN on an unhonourable `postgres` | boot-time `verifySelectable` | `db-layer.md` |
| Ten string literals for DB-family property names | the `OperationalDb.Family` roster | `db-layer.md` |
| `api-stability.md`'s "`DELETE /spaces/{id}` answers 409 unless `?purge=true`" | the rule is the reverse — corrected with this spec | `api-stability.md` |
| `multi-space.md`'s "`SpaceMigrator` migrates on boot" | a one-time CLI — corrected with this spec | `multi-space.md` |
| `GLOSSARY.md` §1's "Shipped verticals: …" and its three `docs/superpower/…` design pointers | the one shipped template; provenance paths in `plans-archive/` — corrected with this spec | `GLOSSARY.md` §1 |
| `STAKEHOLDER_OVERVIEW.md` — never mentions Spaces, defers per-tenant ABAC to "future" | shipped Must and shipped Could; plan §8 cluster, reconciled at step 6 | — |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| `SpaceManager` / `SpaceContext` / `SpaceMigrator`, MDC isolation, the seven CRUD routes and the two 409s | `docs/okf/backend/control-plane/multi-space.md` (`Concept`) | `SpaceManager.java` | §3.1–§3.3 |
| Bundle v2 end to end — endpoints, apply order, the boundary, `connection` and `enrichment` | `docs/okf/backend/control-plane/metadata-bundle.md` (`Concept`) | `BundleRoutes.java` | §3.6 |
| Offers, grants, snapshots, `_shared/`, D9 | `docs/okf/backend/control-plane/exchange-sharing.md` (`Concept`) | `inspecto-exchange/` | §3.7 |
| Per-Space file topology, `OperationalDb`, PG-1, the CWD traps | `docs/okf/backend/engine/db-layer.md` (`Reference`) §4–§5 | `inspecto-engine` | §3.8 |
| The Space-root jail: `PathJail`, declared ∪ discovered roots | `docs/okf/backend/config/config-safety.md` (`Concept`) | `inspecto-config/` | §3.8, §6.6 |
| The Spaces layout and the migrator CLI | `docs/okf/backend/config/configuration.md` (`Reference`) §Spaces | — | §3.2 |
| The two Space gotchas — MDC across workers; paths resolve against the CWD | `docs/okf/backend/gotchas/cross-cutting.md` (`Reference`) | — | §3.3, §3.8 |
| The seeded isolation policies | `docs/okf/capabilities/security/security.md` §3.10 · `docs/okf/backend/editions/auth-security.md` | `inspecto-policy/` | §3.9 |
| The Spaces admin view and the template gallery | `docs/okf/frontend/features/spaces.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/spaces` | §3.10 |
| `SpacesService`, the `spaceInterceptor`, `SERVER_GLOBAL` | `docs/okf/frontend/conventions/multi-space.md` (`Convention`) | `inspecto-ui/src/app/inspecto/api/space.interceptor.ts` | §3.10 |
| ⚠ **No concept file covers the whole-Space zip export / import** (`DataSourceRoutes`, `BundleExporter`, `BundleImporter`) or the template catalog's file format | *(gap — one paragraph each in this spec, §3.4 and §3.5)* | `inspecto/src/main/java/com/gamma/service/BundleExporter.java` · `BundleImporter.java` | the code |

---

## 8. Verification

### 8.1 Model and lifecycle — `inspecto/src/test/java/com/gamma/service/`

| Class | Proves |
|---|---|
| `SpaceManagerTest` (12) | create / update / delete / discover, CRUD lifecycle, close-with-deadline |
| `SpaceBootstrapTest` (3) | booting a `SpaceContext` from disk |
| `SpaceLayoutContractTest` (5) | the convention directories; `flows/` and `pipelines/` both tolerated |
| `SpaceIdTest` (2) | the id charset and length |
| `SpaceRootTest` (1) | legacy vs directory-rooted paths |
| `SpaceMigratorTest` (2) | plan / apply / idempotence of the one-time migration |
| `BundleExporterTest` (8) · `BundleImporterTest` (9) | the zip manifest and contents; **the zip-slip jail** and source-prefix rebasing |
| `DataSourceBundleResolverTest` (2) | per-data-source bundle resolution |

### 8.2 Routes — `inspecto/src/test/java/com/gamma/control/` (real HTTP)

| Class | Proves |
|---|---|
| `ControlApiSpacesTest` (4) | `/spaces` CRUD; `authenticatedCreateSucceedsWhenNoSpaceIsHostedYet`; `purgingTheLastSpaceOnDiskIsRefused` |
| `ControlApiSpaceTemplatesTest` (2) | the gallery and `createFromTemplate` |
| `ControlApiMultiSpaceTest` (1) | a multi-Space server smoke |
| `ControlApiBundleTest` (11) · `ControlApiBundleImportTest` (9) · `ControlApiBundleNewKindsTest` (10) · `ControlApiPipelineBundleTest` (7) | export / preview / import contract, ordering, the newer kinds, the `authored-pipeline` round trip |
| `ExchangeAttributeScopeTest` · `NoExchangeShipsInThePersonalBuildTest` | exchange attributes private by default; Personal carries no exchange module |
| `PostgresStateStoreTest` (opt-in, `-Dinspecto.test.pg.url`; 11 skipped otherwise) | the DB-backed stores against a real Postgres |

About 87 `@Test` methods across the sixteen Space and bundle classes, all in the default reactor.

### 8.3 UI specs — vitest

Fifteen: `space.interceptor.spec.ts`, `spaces.service.spec.ts`, `space-switcher.component.spec.ts`,
`spaces.component.spec.ts`, `space-form.dialog.spec.ts`, `space-template-gallery.dialog.spec.ts`, two
`import-bundle.dialog.spec.ts` (admin and transfer), `bundle-transfer.service.spec.ts`, `bundle.spec.ts`,
`content-hash.spec.ts`, `stream-bundle.spec.ts`, `stream-transfer.service.spec.ts`,
`transfer-menu.component.spec.ts`, `transfer.component.spec.ts`. ⚠ No `exchange.service.spec.ts` exists.

### 8.4 Committed artifacts

`spaces/default`, `spaces/demo`, `spaces/ucc` (complete; mirrored under `inspecto-deploy/spaces/`);
`spaces/_templates/orders-starter/` (the one template; mirrored); `spaces/uat/` (a `data/` stub).
`docs/api/schemas/metadata-bundle.schema.json` + `docs/api/schemas/samples/{dashboard-closure,single-widget}.bundle.json`.

### 8.5 Guards

The root `pom.xml` surefire properties pin the three DB-family backends so no test JVM mints a database in
its working directory (§3.8) — a guard in build-configuration clothing. `check-doc-links` keeps the schema
and sample paths reachable. `ConfigSafetyValidator` + `PathJail` (`config-safety.md`) are the runtime guards
on every path a Space's config declares.

### 8.6 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **No test validates a bundle against `metadata-bundle.schema.json`** | zero Java references to the file |
| **No test that the four verticals exist** — because they do not | `ls spaces/_templates` |
| **No test of the MDC-copy invariant across every executor** | pinned by convention and the gotcha page, not by a sweep |
| **Postgres path runs only opt-in** | `PostgresStateStoreTest` skips 11 without `-Dinspecto.test.pg.url` |
| **No Exchange UI spec** | §8.3 |
| **`uat` is never booted by any test** | it has no `config/` |
