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
> [`docs-consolidation-plan.md` §5.2](../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
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

Five requirements from `REQUIREMENTS.md` §3.9 (that section was stripped to an index on 2026-09-09 — this file is their only home now), all recorded shipped. **One is wrong about what exists, and one
Must overstates what "isolated" means on two of three editions.** ⚠ **`EDITIONS.md`'s feature × edition
matrix is authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `SPC-1` | Isolated **Spaces** (config / data / audit / duckdb per Space), CRUD without restart, one-time migrator | Must | ✅ SHIPPED — ⚠ "isolated" is a **layout** on Personal/Standard and an **enforced boundary** only on Enterprise (CP-07, SEC-06; §3.9) | All |
| `SPC-2` | Whole-Space zip export / import with dry-run preview | Must | ✅ SHIPPED | All |
| `SPC-3` | **Space Templates** (vertical blueprints: Telecom RA, Fraud, Financial Audit, Link Analysis) | Should | 🟡 **MECHANISM SHIPPED, CONTENT PARTIAL** — the server-side catalog exists; **five** templates ship (`orders-starter`; since 2026-09-30 the `business-assurance`, `telco-fraud` and `telco-ra` content packs — `telco-ra` is the generic half of Telecom RA, §3.5 — and `payment-fraud` slice 1, §3.5.1); Financial Audit and Link Analysis do not exist in any shipped artifact (§2 corrections) | All |
| `SPC-4` | **Metadata Bundle v2**: selective config-only transfer with refs, provenance / `contentHash`, `requires`, drift fit-check | Should | ✅ SHIPPED 2026-07-07 (+ `authored-pipeline`, `job`, `saved-view` 2026-07-18; `connection` 2026-07-25; `enrichment` 2026-08-31) | All |
| `SPC-5` | Per-tenant ABAC | Could | ✅ SHIPPED 2026-07-24 (two seeded policies; engage only when a `space` claim is mapped) ⚠ **Named for grep:** the decision seam those policies enforce through is `AccessDecider` (`inspecto-policy/.../PolicyEngine.java`, exercised by `ControlApiAccessDeciderTest`) — until 2026-09-09 that name appeared only in `REQUIREMENTS.md`. | E |

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

⚠ **The whole-Space zip does not import into an existing Space** (operator 2026-09-29): `POST
/spaces/{id}/import` answers a `kind: space` bundle 403 — *a whole-Space export cannot be imported into an
existing Space; import data sources one at a time, or create a new Space from it*. It seeds a NEW Space through
Space creation. See [auth-security.md](../../backend/editions/auth-security.md) (import section).

`DataSourceRoutes` (`inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java:38-41`): `GET
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
[editable round-trip §22](../../backend/pipeline-graph/editable-round-trip.md). `BundleImporter`
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
two-step ask-the-minimum and renders whatever the server publishes. **What is published: five templates** —
`orders-starter` (a pipeline, a quality rule, a dataset and a live dashboard), and, since 2026-09-30,
`business-assurance`, `telco-fraud` and `telco-ra` (below) and `payment-fraud` slice 1 (§3.5.1). Nothing named Financial Audit or Link Analysis
exists (§2, §5).

**`telco-ra` — the telecom revenue-assurance pack** (`ASSURE-PACK-TELCO-RA-1`, wave 5.2 of the assurance plan,
2026-09-30). ✅ **SHIPPED — row closed 2026-10-04**: items (1)-(6) done or decided; the vendor-specific half is
parked in the plan's §4 (needs real feeds).

- **What it is.** Configuration only: no new Step Processor.
  - The vendor feed mapping stays parked.
  - The pack needs **Professional or Enterprise**, because it ships Alert Rules (`alert.dispatch`). The gallery
    marks it `creatable: false` on Personal.
  - `ra_objects_analytics` needs the ops module.
- **Feeds.** Eight canonical synthetic feeds, each with a Pipeline and a Schema.
  - Money is `DECIMAL(18,4)`.
  - `tariff` carries `EFFECTIVE_FROM` / `EFFECTIVE_TO`.
  - Called numbers are in the fictional `+1-555-01xx` range.
- **Reconciliations.**
  - `ra_xdr_completeness`: 3-way, switch → mediation → rating.
  - `ra_rated_vs_billed`: needs `includeRecordCount: false`, because many rated rows face one invoice row.
  - Both run as `recon.run` Jobs.
  - Since `fix(recon)` in the same lane, the run message, Signal and Incident count the Breaks of **every**
    pair. Before that they counted A↔B only, from the run summary's `byType`.
- **`sql.template` control Jobs.**
  - **Chained, not staggered (2026-10-04).** `ra_rerating` keeps its cron; `ra_rollforward` →
    `ra_settlement` → `ra_leakage` and `ra_data_quality` each use `on_signal: job.run.completed` with
    `when: "$signal.job == <previous> && $signal.outcome == SUCCESS"` (halt-on-failure by guard), so the union
    Jobs never read a half-written findings Dataset. ⚠ Every completion also records a SKIPPED run on the
    other links (guard false) — a test waiting for a link must ignore SKIPPED
    (`TelcoRaGoldenTest.theFindingsJobsChainOnSignalFromOneTrigger`).
  - `ra_xdr_lost`: distinct lost or short xDRs.
  - `ra_rerating`: the tariff row in force at the call **start**. It emits one row per call, and a
    duplicate or overlapping row becomes an `ambiguous_tariff` data-quality finding, never a fan-out.
  - `ra_rollforward`: checks the movement rule, day-to-day continuity, and a NULL field (data quality).
  - `ra_settlement`: sums the statement per partner/day first, then FULL OUTER JOINs it against our switch
    minutes × rate. It flags these reasons, in priority order:
    - `missing_statement` (data quality).
    - `unknown_partner`: the whole billed total, every line, is the leakage.
    - `no_traffic`.
    - `split_statement` (data quality): several lines whose total is within tolerance of the expected amount.
      It is a legitimate split bill, so it never reaches `ra_leakage` or the CRITICAL Alert.
    - `duplicate_statement`: the amount is the billed total of ALL lines minus the expected amount. It never
      depends on which line is "first". `LINES_DISAGREE` marks a line that is also off-tolerance, and
      `STATEMENT_ID` reports the lowest id, for reference only.
    - `amount_mismatch`.
  - Each control writes every finding to `<control>_findings`. Every row carries `REASON`, and `FINDING` is
    `leakage` or `data_quality`. A leakage row always has a non-NULL amount.
  - `ra_leakage` takes only the leakage rows, and its Alert Rule `ra_leakage_found` is CRITICAL.
  - `ra_data_quality` takes only the data-quality rows, and its Alert Rule is WARNING.
  - ⚠ A dataset Alert Rule has no row filter (`when` scopes only ledger-metric rules), so the split has to be
    two Datasets.
  - Both rules are keyed `by: CONTROL` (2026-10-04): one Alert per firing control and rule, never per entity
    (G-42). The seed gate checks a `by` column against the Dataset's Schema, so `ra_leakage` and
    `ra_data_quality` ship a zero-row `schema-seed.parquet` (below).
  - Timestamps are naive, so every feed must arrive in one agreed timezone.
  - Tolerances are Job keys. A `$param` arrives as a string literal, so the SQL `CAST`s it to
    `DECIMAL(18,4)`.
  - ⚠ `CEIL` returns a DOUBLE: a settlement minute count must be cast back to `BIGINT`, or every downstream
    amount silently turns into a float. The golden test caught this.
- **Alert Rules.** Both use `gte 1` and `by: CONTROL`. ⚠ An Alert Rule threshold must be positive, so "any row" cannot be
  written `gt 0`.
- **KPIs (2026-10-04).** `registry/kpis/` ships `leakage_found` (`sum(LEAKAGE_AMOUNT)`), `leakage_items` and
  `data_quality_items` (`count`), month grain over `EVENT_DATE`; the three matching dashboard tiles are bound by
  `kpiId`. The seed gate reads a KPI's Dataset Schema and every pack Dataset is empty at apply, so
  `data/ra_leakage/` and `data/ra_data_quality/` each ship a **zero-row `schema-seed.parquet`** (generated with
  DuckDB, as `payment-fraud` does): `CONTROL`, `ITEM_KEY`, `REASON`, `FINDING` VARCHAR, `EVENT_DATE` DATE,
  `LEAKAGE_AMOUNT` DECIMAL(38,4). The gate is NOT relaxed. ⚠ `TelcoRaGoldenTest` pins each seed to its Job's
  output schema (a drift, e.g. the `DECIMAL(38,4)` the Job really emits, fails it) and that the first run replaces
  the seed. The seed carries the two Jobs' columns only; the other findings Datasets need none.
  - ⚠ **Breaks are not xDRs.** On the golden corpus, 15 completeness Breaks are **9** distinct lost or short
    xDRs, because a record lost at mediation breaks both pairs. The dashboard shows the xDR count
    (`ra_xdr_lost`).
- **Golden test.** `inspecto/src/test/java/com/gamma/job/TelcoRaGoldenTest.java` runs the template's
  own Jobs and Reconciliations over `TelcoRaCorpus` (seed `20260930`, 600 xDRs). It asserts the exact
  `key|reason` set per control, and one row per finding:

  | Control | Findings |
  |---|---|
  | Completeness | 15 Breaks / 9 xDRs |
  | Rated vs billed | 4 |
  | Re-rating | 5 `rate_mismatch` (2 half-rate + 3 still on the pre-change rate), 12 `ambiguous_tariff` and 1 `no_tariff` |
  | Roll-forward | 6 (3 movement, 2 continuity, 1 NULL opening) |
  | Settlement | 10 (2 over-billed, 1 missing statement, 2 unknown partner (one day duplicated: 90.00, not 45.00), 3 duplicates, 2 split statements: one exact, one at +0.9 % of a 1 % tolerance) |

  - The three duplicates are an exact copy of a correct line, an over-billed line billed twice, and a
    different line that sorts first. The test pins each one's exact loss.
  - Split by Dataset: **17 leakage rows** in `ra_leakage` and **17 data-quality rows** in `ra_data_quality`.

  - It also checks that leakage amounts are never NULL, that they are DECIMAL, and that the committed samples
    are byte-identical to the generator (`-Dtelcora.regenerate=true`).
  - **End to end (2026-10-04).** `theTemplateIngestsEvaluatesAndAlertsEndToEnd` creates a Space from the template,
    drops each feed's CSV in `data/inbox/<feed>`, ingests it through the template's own 8 Pipelines
    (`CollectorService.runPipeline`), runs the Jobs (the `on_signal` chain included), evaluates the Alert Rules and
    the Expectations, and asserts the same pinned rows (17 + 17). Six Alerts fire, one per firing control and rule
    (`ra_leakage_found` CRITICAL: re-rating 5, roll-forward 5, settlement 7; `ra_data_quality` WARNING: 13, 1, 3;
    never per entity), and the KPIs evaluate to 161.16 USD, 17 and 17; both shipped Expectations PASS the
    clean corpus, and a `non_null` probe on the NULL opening balance proves an Expectation can fail. The test moved
    from `inspecto-engine` to `inspecto` because Expectations and the Space bootstrap live there. No Pipeline
    needed a fix to ingest its feed. The direct-load tests stay as the fast unit-level pin.
  - **Impact ledger stays manual (decided 2026-10-04, item (2) of `ASSURE-PACK-TELCO-RA-1`, no build).** A Break's amount
    does not reach the impact ledger by itself; `recon.run` semantics are unchanged and an analyst records impact from a
    Break explicitly (`PUT /objects/{id}/impact`). No automatic ledger write.
  - The benign look-alikes are real **boundary** cases:
    - a call spanning the tariff change;
    - one cent under the re-rating tolerance;
    - 0.04 against the 0.05 billing and roll-forward tolerances;
    - 0.9 % against the 1 % settlement tolerance;
    - goodwill adjustments;
    - rounding by one usage unit.
  - **Mutation-checked, each one run separately and red.** Mutations that tighten a tolerance under its
    look-alikes:
    - completeness 1 → 0;
    - rated-vs-billed 0.05 → 0.03;
    - re-rating 0.02 → 0.005;
    - roll-forward 0.05 → 0.03;
    - settlement 1.0 → 0.8.

    Mutations that remove or change a rule:
    - a re-rating join without effective dates;
    - re-rating at the call's end;
    - roll-forward without adjustments, without continuity, or without the NULL rule;
    - settlement with a LEFT JOIN instead of the FULL OUTER JOIN;
    - an unknown partner's amount set to NULL;
    - no duplicate-statement rule;
    - `ra_leakage` keeping data-quality rows;
    - a duplicate's loss taken as the total minus one line;
    - the highest statement id reported;
    - a duplicate outranking an unknown partner;
    - `no_tariff` routed as leakage;
    - lines-disagree ignoring the lowest line;
    - no split-statement rule;
    - a split bound of an exact sum only;
    - a split routed as leakage.
  - ⚠ The golden test loads the corpus straight into the stores. It does not ingest through the eight
    Pipelines.
- `ControlApiSpaceTemplateSeedGateTest` applies the template through the real seed gate.

**A template may carry a KPI pack** (`config/registry/kpis/`, `ASSURE-KPI-DEFINITIONS-RESIDUALS-1` (1),
2026-09-28). `SpaceRoutes.createSpace` hands `createFromTemplate` a seed gate
(`KpiRoutes.requireTemplateKpis`) that runs after the WHOLE tree is copied — so the template's Datasets are on
disk — and before boot: the caller needs `canAuthorWorkbench` (the `/components/kpi` door's capability; the
zero-Space recovery create asks it too, since `POST /spaces` is `canAdminister` always), and each KPI meets
`KpiRoutes.requireMeasure` against the NEW Space's registry and `data/`. The first refusal is a 403 / 422, the
half-made tree is deleted and nothing is registered; a `kpis/*.toon` the registry cannot read is refused,
never seeded unchecked. The tree is seeded in a unique `_staging/<id>-<nanos>/` (discovery never boots it) and
renamed into place only once the gate passes, so a cleanup that fails (a Windows lock) is logged and suppressed —
the refusal stays the answer — and the leftover never blocks the id. With no Subject (Personal, an unauthenticated
recovery create) Dataset access is fail-open; a template Dataset shared away from the caller is refused as
"shared away", not as absent (template content is the server's own, so there is nothing to hide). A KPI tile in the template binds by `options.kpi.kpiId` (no new mechanism). ⚠ The gate
reads the Dataset's Schema, so a KPI over a Dataset that the template's own pipeline has not filled yet (a
`physicalRef` store with no Parquet — every fresh `orders-starter` store) is REFUSED at apply; that is why
`orders-starter` ships no KPI pack.

**The business assurance pack** (`spaces/_templates/business-assurance/`, `ASSURE-PACK-BUSINESS-ASSURANCE-1`,
wave 5.4, 2026-09-30) is the first content pack, and it dodges that trap by **generating its corpus in SQL**:
three hand-authored views (`config/views/ba_*_view.toon`, plain `derived_sql`) each open with a CTE that builds
a deterministic synthetic corpus from `range()` (a fixed-offset Weyl sequence into Box-Muller — no RNG state, so
every DuckDB and every run gives the same rows), then run the model over it. The Datasets are `view:`-backed, so
their Schema exists the moment the template is copied and the KPI / Alert Rule `by` gates pass at apply. What it
ships: `ba_revenue_forecast` — a **Holt-Winters additive** forecast (season 7, α 0.25 β 0.02 γ 0.15) written as
a `WITH RECURSIVE` whose state row carries the seven seasonal terms as a DuckDB `LIST` (`list_transform` with an
index lambda replaces one slot per step), with `lower_band` / `upper_band` = forecast ± 4σ̂, where σ̂ is a
MAD estimate over week-on-week differences (independent of the recursion, so an anomaly cannot widen its own
band). A point outside the band updates the state with the forecast instead of the actual (clipping), so one
spike does not drag the next season. ⚠ **Clipping alone can never re-base**: in the first build a +300 level
shift flagged every one of the 82 following days and a +8/day trend ramp flagged none (verification FAIL,
2026-09-30). So the recursion also carries (1) a **regime rule** — `regime_k` (3) consecutive out-of-band days
are a regime change: the K-th day sets `regime_change = 1`, the level re-bases on the actual and the run's
earlier days are NOT reported as `outside_band` (only a run shorter than K is a spike); (2) a two-sided
**CUSUM drift detector** over the in-band residuals in σ̂ units (`cusum_k` 0.25, `cusum_h` 8): crossing `h` sets
`drift_change = 1`, re-bases the level and adds a slope estimate from the CUSUM run to the trend; out-of-band
days do not feed it, so a spike or a shift never trips it. Consequence for a live tail: a run that is still
shorter than K reads as `outside_band` and becomes a regime change (its spike Alerts heal) if it reaches K, so
a real shift can raise up to K − 1 spike Alerts before its one regime Incident. The anchor row must type the
CUSUM state `DOUBLE`: a `0.0` literal is `DECIMAL(2,1)`. At the default `cusum_h` 8 the sum resets before it
reaches 10, so this matters only when `cusum_h` is raised; the golden test raises it to 12 and fails without the
cast. ⚠ **Drift bound:** the CUSUM catches a trend change of roughly ≥ 0.2σ/day (+4/day on this corpus, caught on
day 143); anything slower is absorbed by the trend term and never flagged (a +2/day ramp is a golden
known-limit case), and a change inside the 28-day warm-up is absorbed silently or reported late as drift. All
constants live in the SQL's `p` CTE (view and Job alike); `outside_band`, `regime_change` and `drift_change` are
0 through a 28-day warm-up. The margin model assesses erosion only for a group with `status = assessed`: a
group with a null or negative revenue / cost line in the windows is `data_quality` (flag `data_quality_flag`,
its own Alert Rule) instead of reading as a margin change; no baseline lines is `new`; fewer than `min_lines`
(10) lines or `min_revenue` (1000) in either window is `insufficient`; `erosion_pp` is NULL for all three. `ba_margin_lines` / `ba_margin_erosion`
— revenue − cost by product / channel / partner, margin %, and `erosion_pp` = baseline (prior 28 days) margin %
minus recent (last 28 days) margin %. Five per-entity Alert Rules (`ba_revenue_outside_band`,
`ba_revenue_regime_change`, `ba_revenue_drift`: `max(<flag>)` `by [ds]` `gte 1`, CRITICAL; `ba_margin_erosion`:
`max(erosion_pp)` `by [product, channel, partner]` `gt 5`, WARNING; `ba_margin_data_quality`, WARNING), three KPI
definitions, one dashboard, and two **disabled** `sql.template` Jobs that run the SAME model
SQL over the user's own `daily_revenue` / `margin_lines` stores (`BusinessAssurancePackGoldenTest` pins each view
to contain its Job's SQL verbatim, and the Job's SQL to pass `SqlGuard`). ⚠ An Alert Rule `threshold` must be
positive, so a 0/1 flag is armed as `gte 1`, not `gt 0`. ⚠ A WARNING rule raises an Alert but no Incident.
Golden result: 2 detections (the planted −400 day, 2026-06-04; the planted +15 % cost line, P3/online/PB), 0
false positives against 4 planted look-alikes; forecast RMSE 10.3 against the noise-free signal (σ = 20),
MAPE 1.7 %. Variant corpora (the test rewrites the corpus under the view's own model SQL): a ±level shift from
day 100 → exactly 1 regime change (day 102), then silence; a ±8/day ramp → exactly 1 drift detection within 21
days (measured 19 / 13); bad margin input → 2 data-quality Alerts, `new` and `insufficient` groups silent. The
forecast Dataset's description is kept under 60 characters so Alert text names it (`DatasetMeasureProbe.label`). Runbooks: `config/runbooks/business-assurance-runbooks.md`.

**Product gaps the pack recorded** (both decided 2026-10-04): the forecast is NOT a Measure function — the
Measure shorthand is `count | agg(field)`, so a forecast inside a KPI or Alert Rule would need an engine change;
the Dataset form was taken instead, and stays (no build; revisit if a customer needs forecasting inside an ad-hoc Measure). A hand-authored view cannot read another view, so each view inlines its
corpus CTE; this is by design: layering stays explicit through Job-written Datasets (avoids cycles, hidden cost and lineage ambiguity). A template's runbooks live at `config/runbooks/<pack>-runbooks.md` (`createFromTemplate` copies only `config/` and
`data/`, and rewrites `${SPACE}` in `.toon` files only); all three assurance packs follow this, and the seed-gate test
pins it. There is still no runbook component kind.
Each Job's `sink_dataset` is a shipped Dataset (`revenue_forecast`, `margin_erosion`; `physicalRef` = the sink dir) with a zero-row
`data/<sink>/seed.parquet` (the telco-fraud pattern), so enabling a Job needs no hand re-point; regenerate the seeds with env
`BA_SINK_REGENERATE=true` (`BusinessAssuranceSinkDatasetTest`). The Alert Rules and dashboard still read the view-backed demo Datasets. Decision (operator, 2026-10-04): repointing them to the sink
Datasets stays MANUAL until the pack has been driven live; no second Alert Rule set ships.

**The telecom fraud pack — `spaces/_templates/telco-fraud/`** (`ASSURE-PACK-TELCO-FRAUD-1`, wave 5.1 of
`superpower/assurance-capability-plan.md`, BUILT 2026-09-30, verified 2026-10-03, not closed). Three feed Pipelines + schemas (`cdr`,
`subscriber_events`, `payments`; each schema needs a `mapping:` — a `raw:`-only schema fails every file on the
Consignment path with "a mapping with neither fields[] nor rules[]"); ten detection windows as `sql.template` Jobs
(`config/jobs/fraud_<typology>_job.toon`: IRSF, Wangiri, SIM-box, premium-rate, roaming high usage, SIM-swap,
subscription / identity, dealer activations, voucher / EVD, payment reversal), each writing one row per candidate
entity per `window_date` to its sink Dataset `fraud_<typology>`; and one per-entity Alert Rule per typology, `by` =
the offender alone (`msisdn`, the ringing number, `id_doc`, `dealer_id` or `voucher_serial`), Measure `max(<feature>)`
over every retained window, `stormCap: 500`. No new Step Processor. **Thresholds are configuration in two places**: the Job's parameters say what
counts (`irsf_prefixes`, `premium_prefixes`, `home_cc`, `max_ring_seconds`, `callback_hours`, `max_cells`,
`exempt_msisdns`, `exempt_doc_prefixes`, `window_hours`, `traffic_grace_hours`, `window_start` / `window_end`,
`retention_days`) and the Alert Rule's `threshold` (`gt`) says how much is too much. **Schedule:** every detection Job carries `cron: "0 2 * * *"` and the rolling window `window_start: "$yesterday"` / `window_end: "$today"` (job-parameter Expressions, resolved at fire time in the Job's zone), so a created Space detects the previous day each night; a manual run (and the golden test) overrides both with literal timestamps. Also: four KPIs + tiles, one
dashboard (`telco_fraud_overview`: the four KPI tiles plus one bar Widget per typology over its sink Dataset,
keyed on the offender — the golden test renders every tile through `/kpis/{id}/value` and `/bi/query`), and `config/runbooks/telco-fraud-runbooks.md` (plain Markdown: there is no
runbook component kind; parked on demand, decided 2026-10-04: it would be new model + SPA + API surface with no named consumer, so no build until a customer asks), which names the known false-positive sources that have no defence. **Edition gating (decided 2026-10-04): none** — the template ships to all editions (editions are build flavours, the template is plain config; anyone holding the needed capabilities can create it). **Two product gaps decided DEFER 2026-10-04, no build until a customer asks:** a Dataset whose Schema comes from its producing Job (the pack ships zero-row `data/fraud_*/seed.parquet` Schema seeds instead) and an incremental Job Type mode (the Jobs emulate it with daily full-window runs); both are model-level changes touching every pack. Known limits (not product gaps): thresholds are generic defaults (the vendor-specific half
stays parked); each run reads and rewrites the whole retained sink, and the identity / dealer / voucher / reversal
sinks keep every entity, so they grow with the subscriber base.

Detection semantics worth knowing: dialled numbers are normalised to one E.164-digit format (`+` / `00` stripped,
national `0…` → `home_cc` + NSN) before a prefix list is matched with `starts_with`, entries of any length; a prefix
list entry that is not 1–15 digits fails the run (DuckDB `error()`), never matches silently. Wangiri counts ring
targets that call the ringing number back within `callback_hours` AFTER the ring, so a flash-call OTP sender stays
silent. SIM-swap anchors each payment to the latest swap before it, so a payment counts once however many swaps
precede it. Dealer activations look for traffic up to `traffic_grace_hours` past the window, so late-day activations
are not idle.

⚠ **A `sql.template` run replaces its whole sink** (stage-and-swap; the Job Type has no partition-incremental
output). So each Job reads its own sink (`sources: …,fraud_<typology>`) and re-emits the rows of every OTHER
`window_date` within `retention_days` beside the current window's — partition-incremental by `window_date`, done in
the SQL; a same-window re-run replaces that window, never duplicates it. A later window's run neither raises nor
heals an earlier offender's Alert; a key heals only when all its evidence ages out (its Incident stays open —
D-P11). ⚠ **The storm cap counts every retained breaching key**: `AlertService.evaluateGrouped` collapses a rule
into ONE storm Alert once the total exceeds `stormCap`, and no new offender gets its own Alert until it drops back.
A first cut keyed on offender + `window_date` with `stormCap: 50` hit that around day 11 at the corpus rate; keying
on the offender bounds the count by distinct offenders, and `stormCap` must stay above `retention_days` x daily
offenders. ⚠ **Scaling**: each run reads and rewrites the whole retained sink (≈ `retention_days` x daily
candidates); `fraud_simbox` keeps only lines with ≥ `min_candidate_targets` targets, the identity / dealer / voucher /
reversal sinks still keep every entity. ⚠ **The exemption lists are a trust assumption**: `exempt_doc_prefixes`
matches a prefix of `id_doc`, which the dealer enters, so a made-up `CORP-FAKE` document is exempted (pinned in the
corpus as a known risk). The lists must come from a verified register, never from a field the monitored party
controls; matching a registered-document Reference Dataset instead is open. ⚠ **Seeds, and
why.** The per-entity Alert Rule save gate (`AlertRoutes.requireGroupingColumns`, run by the template seed gate) and
the KPI gate both read the Dataset's Schema and fail closed on a Dataset with no data, and the Jobs read their own
sink; so the template ships a **zero-row seed snapshot** per sink (`data/fraud_<typology>/seed.parquet`), replaced by
the first run. A test regenerates them from each Job's SQL and fails when a seed's Schema drifts. ⚠ The Alert Rules
are Professional+: a Personal build refuses the whole template at `POST /spaces`, though the gallery still lists it.

**Golden corpus**: synthetic, from a fixed-seed generator (`inspecto/src/test/java/com/gamma/control/TelcoFraudCorpus.java`,
seed `20260930`; fictional numbering — home country code `999`, foreign callers `287` / `289`) committed under
`data/samples/`, with planted offenders (one JUST above each threshold) and planted look-alikes (one exactly AT each
threshold, plus flash-call OTP, a registered PBX line, a business traveller, a corporate multi-line document,
late-day activations, a two-swap / one-payment line). `TelcoFraudTemplateGoldenTest` boots the template through
`POST /spaces`, ingests the corpus through the three Pipelines on the production Consignment path
(`CollectorProcessor.run`; every CSV row must land), runs the ten Jobs, asserts each Alert Rule raises EXACTLY its
planted offenders (5/4/4/5/4/4/3/3/4/4 = 40 Alerts) and no look-alike, re-runs the same window (no duplicate row, no
Alert change), then runs the next window (nothing raised or dropped); a second case runs 16 retained days of
fresh offenders at the corpus rate — 640 Alerts, every offender its own, no storm. Each defence was mutation-checked red.

**Every registry kind a template seeds is gated, not only `kpi`** (`ASSURE-KPI-DEFINITIONS-RESIDUALS-1` (1) ⚠,
2026-09-28). The seed gate is `TemplateSeedGate.require`, one table-driven pass over the staged tree before boot:
(1) **capability** — `ImportCapabilityGuard.checkFiles(…, newSpace=true)` over the staged `config/`, the same
kind→capability table `/spaces/import` and `/components/{kind}` use (`connection` → `canOnboardConnections`,
`alert-rule` → `canAuthorAlertRules`, `findings-spec` → `canManageIncidents`, an `event_prune`/`restore` Job →
`canAdminister`; a `registry/` file whose kind cannot be told is refused); run on the zero-Space recovery
create too (`TEMPLATE-RECOVERY-IMPORT-GATE-1`, 2026-10-03); (2) **save validation** — every `ComponentStore.WRITABLE_TYPES` component meets
what `ComponentRoutes.writeComponent` runs before its write: `validateKind(api, …)`, `AlertRoutes.parse` for an
`alert-rule` (edition feature, Investigation refusal, `by` Schema check), `DecisionRuleGuard.prepare` for a
`decision-rule` (the invoke-api gate, with the template's own `*_connection.toon` Connections counted as carried,
as `/spaces/import` counts them) — judged against the NEW Space's `config/` / `data/` through explicit-root overloads
(`validateKind`, `AlertRoutes.parse`, `RiskScoreRoutes.requireStorable`/`requireNotReserved`); a registry file of the
kind's own suffix (`ComponentStore.suffixFor` — `.csv` for a mapping) the store cannot read is refused. A seeded
Decision Rule is rewritten with `prepare`'s stamps: the applying actor is creator and maker, and the template's
`createdBy`/`updatedBy`/`restoredMakers` are dropped. The recovery create skips the kind-table capability but still
asks an invoke-api rule's `canWorkIncidents` (deliberate: outbound calls always need it); (3) `kpi` as above. First refusal → 403 / 422 naming the kind, no Space. `ControlApiSpaceTemplateSeedGateTest`
applies EVERY shipped template and pins both refusals. (4) **structure** (2026-10-04) — every staged Pipeline (`*_pipeline.toon`)
and Job (`jobs/*.toon`) runs through `SaveGate.check` with `Referents.MAY_ARRIVE_LATER` (a template's Connections are
filled in later, so a missing one is not a defect; a structurally invalid config such as an unread block is), judged from
the file's own directory; the first ERROR refuses the template. (5) **Connections** (2026-10-04, closes
`ASSURE-KPI-DEFINITIONS-RESIDUALS-1`) — every `*_connection.toon` runs `ConnectionRoutes.validateProfile`, what
`POST`/`PUT /connections` run before their write (the connector's option checks, and for `https` the egress host
check that refuses a numeric-form IP); a refusal is 422 `template connection '<file>' is refused`, no Space.

#### 3.5.1 The `payment-fraud` content pack — slice 1 (`ASSURE-PACK-PAYMENT-FRAUD-1`, 2026-09-30)

> **Closed as BUILT (operator, 2026-10-06).** Further typologies and customer sign-offs come on customer request.
> The Alert Rule over the template's own Risk Score output (`pf_high_risk_account`, `max(score) >= 60` by
> `model, entity_key`) ships as a deferred seed — see §3.5.2.

Wave 5.3 of `superpower/assurance-capability-plan.md`, generic half. `spaces/_templates/payment-fraud/` ships
config and a synthetic corpus; it uses the opt-in `processing.refusal` mode of the ingest engine. There is no new
Step Processor and no new route.

- **Corpus (WS-40).** There are three feeds over 2026-07-01..03: `payment_attempts`, `sim_changes` and `disputes`.
  `disputes` has only a day-3 file, because an empty CSV would quarantine as `empty`. The corpus is generated by
  `PaymentFraudCorpus` in the engine test sources, with the fixed seed `20260701`; `main <template>/data/samples`
  regenerates it. The golden test asserts that the committed CSVs are byte-for-byte what the generator writes. Every
  identifier is a synthetic token.
- **Runbook: a refused card-number file is deleted (operator decision 2026-10-04, PCI).** A refused file stays in
  the restricted store only for `refusal_retention_days` (default 7, 1..30; the template ships 30). After that the
  poll-cycle housekeeping deletes it, and the AUDIT event `ingest.refused.retention` records its stored name, size,
  sha256, reason and retention, never its content. To investigate a refusal, use that event and the
  `ingest.refused` event inside the window, then fix the file at its origin and resend it. Never copy a restricted
  file out of the store. Nothing replays from the store, so a deletion costs no replay
  ([Ingestion §3.6](../ingestion/ingestion.md)).
- **Card-number scan at ingest (WS-40): what it covers, exactly.** The three Pipelines set
  `processing.refusal: restricted_quarantine`, `refusal_scan: card_number` and `refusal_retention_days: 30` (the
  maximum). The scan is the platform's (`RefusalQuarantine.cardScanSql`, [Ingestion §3.6](../ingestion/ingestion.md)). The schemas carry
  no scan expression.
  - **What it reads:** every raw cell except the `refusal_scan_exempt` columns (default: none), before anything is
    written.
  - **Normalisation:** combining marks (Mn, Me) and format / zero-width characters (Cf) are stripped, and 41 Unicode
    Nd digit blocks are folded to ASCII. A well-formed date or timestamp is skipped.
  - **No IBAN skip.** It was removed in round 4, because a card number dressed as an IBAN evaded it
    (`DE00 4111 1111 1111 1111`, `ID12 4111111111111111`, both now golden trips). An IBAN column is exempted with
    `refusal_scan_exempt`.
  - **The scan fails CLOSED.** If it cannot run (a read or SQL error), the file is restricted with
    `INGEST_REFUSE:SCAN_FAILED`, never landed. Restricting is chosen over failing the batch because a failed batch
    leaves the unscanned file in the inbox, re-polled every cycle. (The batch error text no longer quotes cell
    values since 2026-10-03: [Ingestion](../ingestion/ingestion.md).)
  - **The exempt list cannot switch the scan off.** A list covering every raw field is refused at load. A name that
    is no raw field is warned. Every control-plane write that changes a refusal key emits `pipeline.refusal.changed`
    (AUDIT, before / after).
  - **Candidates:** runs of digit groups joined by 1–5 non-alphanumerics. A candidate is one group of 13–19 digits or
    a 4-4-4-4 / 4-4-4-4-3 / 4-6-5 / 4-6-4 window.
  - **Accepted when both hold:**
    - Luhn-valid;
    - brand prefix **and** length match exactly this list, shared with the Java name/header check
      (`CardNumbers.IIN_REGEX`):

      | Brand | Prefix | Lengths |
      |---|---|---|
      | Amex | 34 / 37 | 15 |
      | Diners | 30 / 36 / 38 / 39 | 14–19 |
      | JCB | 35 | 16–19 |
      | Visa | 4 | 13 / 16 / 19 |
      | incl. Maestro | 50–59 | 13–19 |
      | Mastercard 2-series | 22–27 | 16 |
      | Discover / UnionPay / Maestro | 6 | 13–19 |

  - **Cost:** linear. A 1 MB digit cell costs under 2 s over a clean file.
  - **Golden cases.** 32 trips of published test numbers: every separator spelling, 2 and 3 separators, NBSP, en-dash,
    zero-width, a combining mark, four digit scripts, `+`, embedded text, CVV, expiry, Amex 4-6-5, JCB (plain and
    grouped), Diners 30/36/38 (incl. 4-6-4), Maestro 50/56/67, two numbers dressed as IBANs, and the number in `AMOUNT`
    (34 in all). 11 true negatives ingest,
    among them a Luhn-valid 15-digit IMEI (no JCB length), a DE IBAN in 4-digit groups and an E.164 number. The corpus's
    350 realistic references never trip.
  - **Measured false-trip rates** (`RefusalQuarantineTest.falseTripRatesAreMeasured`, 10 000 each, fixed seed, the
    production SQL):

    | Data | Rate |
    |---|---|
    | 16-digit numeric order ids | **4.39 %** |
    | DE IBANs in 4-digit groups | **3.79 %** (no IBAN skip since round 4) |
    | E.164 phone numbers | **0.95 %** (they collide with Diners 30/36/38/39 at 14 digits) |

    ⚠ Every false trip moves a WHOLE file to the restricted quarantine. The trade-off is the operator's: list a column
    known to carry no card data (an order id, an IBAN, a phone number) in `refusal_scan_exempt`. It is then not
    scanned at all, so a real number in it lands.
  - 🔴 **Not caught:** a number split across two cells; one glued to other digits with no separator; any other layout;
    a prefix or length outside the list; an unfolded digit script; anything in an exempt column.
- **File name and header line** are checked by the refusal mode itself (`CARD_NUMBER_IN_FILE_NAME` /
  `CARD_NUMBER_IN_HEADER`). The restricted row records a generated name that carries the code.
  - A number inside a *malformed* row never reaches the scan. Since 2026-10-03 (never store values) its rejects
    sidecar keeps only line, columns, reason and a salted fingerprint
    (`aCardNumberInAMalformedRowIsNeverCopiedIntoTheRejectSidecar`, a byte-wise scan of the whole Space).
  - The raw SOURCE file itself is kept (`all_or_nothing` quarantines it whole; `backup/` keeps every raw file that
    landed) until the opt-in `processing.raw_copy_retention_days` (>= 1; unset = kept forever) ages it out: after each
    backup, `RawCopyRetention` deletes files older than the window under `backup/` and `quarantine/` - never
    `backup/parked/` (awaits `drain`) nor the `.restricted` store (its own `refusal_retention_days`). The window is
    also how long a reject stays replayable. A file NAME can embed a value and is recorded at discovery, before the name check:
    `FileNames.safe` (inspecto-etl) replaces every non-timestamp run of 8+ digits with its salted
    fingerprint (`FailureText.fingerprint`, same per-Space salt). It is applied to the acquisition ledger's key AND
    name (`CollectorProcessor` lookup and `ConsignmentIngestor` record use the same transform, so dedup identity is
    unchanged), the `[DEDUP]` log line, and the remote-acquisition logs and `AcquisitionTelemetry` events. A name
    with no such run is returned unchanged, so an existing ledger still matches; losing the salt re-keys the
    value-bearing names once. Pinned by `FileNamesTest` and
    `SourceConfigIntegrationTest.aValueInAnInboxNameIsNeverLedgeredButDedupIdentityHolds`.
  - **Every other name that is only DISPLAYED or logged carries it too** (closed 2026-10-04): `CommitRetry`,
    `UnpackStage`, `NativeCsvStreamingEngine`, the lane failure logs and ingest-progress names,
    `QuarantineManager`, `DuckDbCsvIngester`, the status ledger's `filename` / `origin` / `logical_name` columns
    (`ConsignmentIngestorTest.aValueInAFileNameIsNeverWrittenToTheStatusLedgerButTheRealFileStillLands`) and the
    unpack ledger. `FailureText.scrub` (every stored or logged failure text) also runs `FileNames.safeText`, which
    transforms a file-name-shaped token (long digit run plus an extension) in an exception message that quotes a
    path - a plain count such as `12345678 bytes` is left alone. `safe` is idempotent: it skips an `<fp:...>`
    span, because a 16-hex fingerprint can itself hold 8 decimal digits in a row.
  - **Heuristic widened to dashes and spaces** (`4111-1111-1111-1111`, `91 98765 43210`: short groups, compacted
    before fingerprinting so the dashed and the plain spelling agree). A chain that starts with a valid date
    (`2026-10-03-1`) is a stamp, so a dated name keeps its ledger key. Measured over the 5428 tracked file names
    of the repo: one extra hit (a font range), none among inbox-shaped names.
  - **The OUTPUT file name is value-free too** (closed 2026-10-04, `INGEST-OUTPUT-NAME-EMBEDS-SOURCE-STEM-1`):
    `FileNames.outputStem` renders the source stem through `safe`, spelling each `<fp:X>` span `fp-X` (`<`, `>`
    and `:` are not legal in a file name). It is stable per name and Space, so an `OVERWRITE_OR_IGNORE` re-run lands
    on the same file; a name with no value run is unchanged. The status row's `output_paths` is value-free with it
    (`ConsignmentIngestorTest.aValueInAFileNameIsNeverWrittenToTheStatusLedgerButTheRealFileStillLands`). ⚠ Losing
    the salt renames those outputs once.
  - **So are the reject sidecar, the chunk scratch name and the replay input name** (2026-10-04):
    `FileNames.errorsFileName` (`<outputStem>_errors.csv`) is the ONE spelling the writers (`ParserSpec`,
    `QuarantineManager`) and the readers (`GET .../rejected-rows` in `RunRoutes`, `RecordReplay`) agree on, so
    lookups still resolve from the REAL file name the caller passes; `FileChunker` chunk files and
    `RecordReplay.replayName` use `outputStem`. `outputStem` is idempotent (an `fp-<16 hex>` span is never
    re-rendered), so a chunk's own sidecar is value-free too. ⚠ Breaking, on purpose: a value-bearing name's
    existing `_errors.csv` is no longer found under its old name; a name with no value run is unchanged. Replay
    still re-reads the SOURCE file by its real name.
  - **Residual, by design: names that RESOLVE a path stay real.** The per-Consignment manifest, `backup/` and
    quarantine paths, markers, the lineage ledger (queried by input file) and the `filename_column` data column
    hold the real
    name; they are the raw-copy surface the retention window above governs. **Not caught, by choice:** a value
    split by underscores or dots (those join ordinals and versions), and a value spelled in letters.
- **Feature Datasets (WS-41).** Five `sql.template` Jobs run on an hourly cron. Thresholds are Job parameters. Every
  window is **rolling**, anchored on each attempt, and half-open: `(t − window, t]`, so a span of exactly the window
  is outside it.
  - `pf_device_small_amounts`: distinct instruments ≤ `$small_amount` (2.00) per device, over `$window_hours` (24).
  - `pf_bin_declines`: distinct declined instruments per BIN, over 24 h.
  - `pf_instrument_velocity`: attempts per instrument over `$window_minutes` (60). Partition and grouping are both
    the instrument.
  - `pf_sim_swap_payments`: approved payments ≥ `$risky_amount` (200) within 24 h after a SIM change, on a device the
    account never used before that change.
  - `pf_account_activity`: attempts, declines, disputes and the account's own 60-minute velocity. Partition and
    grouping are both the account.

  Each sink ships a **zero-row `schema-seed.parquet`** under `data/<sink>/`, which the first run's swap-in replaces.
  🔴 Without it the seed gate REFUSES the template: an Alert Rule's `by` check and the Risk Score's factor-column
  check both read the Dataset's Schema, and a Job-produced Dataset has none until it runs. The golden test asserts
  that each seed's `DESCRIBE` equals the Job output's.
- **KPIs and Dashboard (WS-44, slice 2, 2026-10-04).** The `pf_daily_summary` Job (`sql.template`, no parameters)
  writes one row per day (`activity_date`, attempts, declined attempts, attempted amount, disputes, disputed amount),
  with its own zero-row seed. Four KPIs read it (`disputed_amount`, `disputes_opened`, `attempted_amount`,
  `declined_attempts`) as KPI tiles beside four bar Widgets (one per typology Dataset) on `payment_fraud_overview`.
  KPIs sit on the Job sink, not on the raw feeds: the seed gate reads each KPI's Dataset when the Space is created,
  and a raw feed has no data yet. `PaymentFraudDashboardTest` (inspecto) creates the Space through `POST /spaces`,
  ingests, runs the Jobs and renders every tile through `/kpis/{id}/value` and `/bi/query`. Runbooks (2026-10-04): `config/runbooks/payment-fraud-runbooks.md` (card-number tripwire, the four typologies, the Risk Score, labels and maturity), delivered to the created Space and pinned by `ControlApiSpaceTemplateSeedGateTest`.
- **Dispute labels with a maturity flag (WS-44, slice 3, 2026-10-04; operator decision).** `pf_attempt_labels` (Job
  parameter `maturity_days`, **default 120 = the chargeback window, configurable; the operator may change it**)
  writes one row per attempt: `disputed`, `mature`, and `label` = `DISPUTED` (any age), `NEGATIVE` (undisputed and
  older than the window) or `UNMATURED` (undisputed, still inside it - not yet a trustworthy negative). Age is
  measured against the newest attempt date in the data, so a replayed corpus is deterministic. It is a Dataset, not
  an Alert Rule (G-42). `PaymentFraudDashboardTest` pins the default (no negatives in a 3-day corpus) and
  `maturity_days=1` (negatives appear, disputed stay positive).
- **Typologies (WS-42).** There are four per-entity CRITICAL Alert Rules. `AlertRule` accepts only
  `WARNING · INFO · CRITICAL`, so the Risk Score concept's "`CRITICAL` or `ERROR`" does not apply here.
  - `pf_card_testing`: `by device_id`, ≥ 8.
  - `pf_bin_attack`: `by bin`, ≥ 15.
  - `pf_velocity_burst`: `by instrument_token`, ≥ 6.
  - `pf_sim_swap_takeover`: `by account_id`, ≥ 1.
- **Risk Score (WS-43).** `registry/risk-scores/payment_account.toon` scores entity `account`, with
  `highThreshold` 60. Its **default** factor table is SIM-swap payments ×60 (cap 60), account velocity ×5 (cap 40),
  declines ×4 (cap 20) and disputes ×20 (cap 40). It runs as the `pf_risk_score` `risk.score` Job.
  - 🔴 **Its Alert Rule cannot ship in the template.** A rule over `risk_scores_payment_account_latest` needs that
    store's Schema at apply time, and seeding the store would mean forging the `.risk-score-output` ownership
    marker. Add the rule after the first scoring run, as [Risk Scores](../../backend/control-plane/risk-scores.md)
    documents.
- **Golden test.** `PaymentFraudTemplateGoldenTest` (inspecto-engine, 11 tests) runs a verbatim copy of the template
  end to end: the ingest, the five Jobs through `JobService`, the model through `RiskScoreEvaluator`, and every Alert
  Rule through `AlertService` + `DatasetMeasureProbe`.
  - **Exact detections:**
    - card testing: {`dev_ct_01`, `dev_ct_02` (4 + 4 across midnight)};
    - BIN attack: {`498765`, `498700` (8 + 8 across midnight)};
    - velocity: {`tok_vb_01`, `tok_vb_02`, `tok_vb_03` (6 attempts spanning 59:59), `tok_shared` (one instrument, two
      accounts)};
    - SIM swap: {`acc_ss01`, `acc_ss02`};
    - high risk: {`acc_ss01` 65, `acc_ss02` 65, `acc_vb01` 87, `acc_ct_guest` 60}.
  - **Look-alikes that stay silent:**
    - a 7-instrument device, a kiosk at normal amounts, and a payroll-card BIN run;
    - 5 attempts in 48 minutes, 6 attempts spanning **exactly 60:00**, and 6 over 61 minutes;
    - SIM swaps at 50.5 h, on the usual device, below 200, and declined;
    - the shared instrument's two accounts, which score 15 each (their own 3 attempts, not the instrument's 6).
  - **Mutants.** Each of these turns the test red:
    - no digit folding;
    - no separator run;
    - `AMOUNT` left out of the scan;
    - separators `{1,5}` → `{1,1}` (the 2- and 3-separator probes land);
    - no 4-4-4-4 layout (the CVV and expiry probes land);
    - no IIN requirement (3 realistic corpus references trip);
    - no mark / zero-width stripping;
    - a `find()` instead of an anchored reason code (`RefusalQuarantineTest`);
    - no per-member probe in the union lane;
    - no chunk rollback;
    - the original name kept in `logical_name`;
    - the restricted directory derived as a SIBLING of `dirs.quarantine`;
    - dropping the 6, JCB 35 or Diners 30/36/38/39 ranges, or narrowing 50–59 to 51–55;
    - the IBAN skip put back (the two dressed numbers land);
    - `.restricted` readable through the table browser, `/db/query` or a Dataset over its ancestor;
    - a replay reading a sidecar under `.restricted`;
    - an exempt list covering every raw field accepted;
    - the scan failing open;
    - an ignored exempt list;
    - no retention audit;
    - `.restricted` not excluded from backup, from the quarantine listing or from the errors-file route;
    - the store back under `dirs.quarantine`, where a mapping expression could read it (round 5);
    - an import door not auditing a dropped `refusal_scan` (round 5);
    - the `scanFile` fail-closed branch returning null (round 5);
    - `>=` on the velocity bound;
    - calendar-day grouping for card testing and BIN attack;
    - an instrument partition for the account velocity;
    - earlier, a card-testing threshold of 7, and a SIM window of 72 h.
  - `ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies` applies the template through the seed gate.
- **Deferred to later slices** (open on the row): WS-44 (disputes as labels with a maturity flag, payment KPIs and
  dashboards), further typologies (operator call). WS-44 shipped as slices 2–3; runbooks shipped 2026-10-04.
 

#### 3.5.2 Deferred seed — an Alert Rule over the template's own Risk Score output (`TEMPLATE-RISK-SCORE-ALERT-RULE-1`, 2026-10-06)

**Decision (operator, 2026-10-06): DEFERRED SEED.** A per-entity Alert Rule's `by` columns are checked against its
Dataset's Schema at save, and a Risk Score's `risk_scores_<model>_latest` store has no Schema — and may not even be
named by a Dataset (`RiskScoreRoutes.requireStorable`) — until the model first runs. Seeding a Schema or the store
would forge the ownership marker, so a template declares the rule as PENDING instead, and nothing is forced.

- **Where it is recorded (in the Space).** `config/pending/alert-rules/<name>.toon`: the Alert Rule body plus
  `afterRiskScore: <model>`. It is copied with the template like any config file. At apply the seed gate checks it
  (`TemplateSeedGate`, step after `kpi`): the edition carries Alert Rules, it parses (`AlertRule.fromMap`), it names a
  `risk-score` the template seeds, and its `dataset` is that model's `risk_scores_<model>_latest`. The capability
  table classifies the path as `alert-rule` (`ImportCapabilityGuard`, `canAuthorAlertRules`) and so does the
  approval-policy classifier (`PendingChanges.kindOfConfigPath`).
- **What fires it.** The `risk.score` Job emits `risk.score.produced` after it has written the scores; each Space's
  `CollectorService` subscribes to its own event log and calls `PendingAlertRules.onRiskScoreProduced` for that model.
  For each pending rule naming the model it registers the `_latest` Dataset (id = physicalRef) through the Dataset save
  gate if absent, then runs `AlertRoutes.parse` (the `by` Schema check included), writes `registry/alert-rules/<name>`,
  arms it in the `AlertService` and deletes the pending file. **Once:** the pending file is the only trigger, and the
  call is synchronized; an Alert Rule already under that name is never overwritten (the pending entry is dropped).
- **A refusal stays pending.** Any gate refusal — no output yet, a Schema mismatch, an edition without Alert Rules, or
  a Space approval policy that holds `alert-rule` or `dataset` changes (a writer outside any request cannot be
  approved) — keeps the file, emits an AUDIT event `alert-rule.pending.refused` with the reason, and is retried on the
  model's next run. `alert-rule.pending.created` / `.dropped` record the other outcomes (actor `system`).
- **Security inventories (operator, 2026-10-06).** `PendingAlertRules` is a *background template materializer*: it
  refuses while an approval policy governs `alert-rule`/`dataset`. `PendingAlertRules#ensureLatestDataset` is on
  `ConfigWriteFunnelTest.WRITERS` with that reason (`#onRiskScoreProduced` reaches `PendingChanges.governs` itself, so
  the scan counts it as held). Both sites are on `DecisionRuleWritersTest.GUARDED_BY`: before any write
  `#onRiskScoreProduced` runs `DecisionRuleGuard.refuseUnattended` — a background writer has no Subject to hold
  `canWorkIncidents`, so a rule body with an `invoke-api` consequence is refused (422, fail closed) and stays pending
  + audited like every other refusal.
- **Status.** `GET /alerts/rules/pending` lists `{name, afterRiskScore, dataset}` for what is still waiting; a
  refusal's reason is in the audit log. There is no SPA surface yet; the rule appears on the Alert Rules page once
  created.
- **Use.** `payment-fraud` ships `pf_high_risk_account` (`max(score) >= 60` by `model, entity_key`, CRITICAL) after
  `payment_account`. Tests: `PendingAlertRulesTest` (pending, refused + audited, created once, never overwritten, held
  under an approval policy, a `DecisionRuleGuard` refusal stays pending), `ControlApiSpaceTemplateSeedGateTest` (capability and content refusal at apply) and
  `PaymentFraudDashboardTest` (the live Space's own `pf_risk_score` run creates and arms it).

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
  widget, dashboard, reconciliation, pipeline, authored-pipeline, enrichment, job, saved-view` — an
  `authored-pipeline` item is refused 422 before any write since 2026-09-25, the kind is export-only); existing
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

`inspecto-exchange` (Professional+, EDG-01 cell 4) roots at `spaces/_shared/` — reserved, never a Space —
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

**Admission control is part of the layout since 2026-09-10** (`SPACES-GOVERNOR-1`). `IntakeGovernor` — the T15 per-cycle
file cap and its overrun controller — keyed its state by pipeline id alone while every sibling singleton was per Space, so
two Spaces running a same-named pipeline shared one cap and one tenant's surge throttled the other's. It now keys learned
caps and per-pipeline overrides by **(Space, pipeline)**, the Space being the calling thread's MDC exactly as
`EventLog.current()` routes (bound by `CollectorService.underSpace` around every poll and by `ControlApi` around every
request; the default Space runs with none and resolves to `default`, byte-identical to before). The fleet-wide policy
stays process-wide on purpose: `PUT /system/scheduler` is system scope. Pinned by
`IntakeGovernorTest.aSaturatedSpaceDoesNotThrottleAnotherSpacesSameNamedPipeline`. This was the one non-Space-scoped
singleton the scale-out grounding found — a single-node isolation defect, and a prerequisite for putting several
Spaces on one pod.

### 3.10 The SPA

`SpacesService` holds the active id (restored from `localStorage`), probes `GET /spaces/_meta` and lists
`GET /spaces` in parallel; the header **space-switcher** appears only when `multiSpace` and Spaces exist, and
switching **hard-reloads** at the lens home. The switcher menu ends with **New space…** (after a divider, only with `canAdminister` — the grant
`POST /spaces` enforces once any Space is hosted): it opens the same `SpaceFormDialog` as Settings → Spaces
(`inspecto/spaces/`, shared since 2026-09-25) and on create **switches to the new Space**, while a create from
Settings leaves the active Space unchanged. The `spaceInterceptor` rewrites `/api/v1/<path>` →
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
| **Postgres multi-user** — pool behind `JdbcDrivers`, replace `browseConnection()`, **schema-per-Space** URL wiring, `TriageRunStore` PG impl, concurrency test | `BACKLOG.md` §3 *Postgres multi-user* — ⛔ **PARKED by §6**; `EDITIONS.md` OPS-03 | Isolation on Postgres is a **schema**, not a database (a connection binds one database) |
| ✅ Canonical-pipeline selective bundle export / import — SHIPPED 2026-09-25 as the `pipeline` bundle kind; `authored-pipeline` is export-only (BUNDLE-AUTHORED-PIPELINE-STORE-1, option B) | [metadata-bundle](../../backend/control-plane/metadata-bundle.md) | |
| Bundle residuals — `requires` present-but-different classification; per-editor "load as draft" import | `BACKLOG.md` §3 *Bundle / Exchange* | ⛔ do not fake a draft with a cross-kind `enabled: false` |
| Space-to-space comparison — residuals only; the storage-growth comparison SHIPPED 2026-09-24 (`space.comparison`, `canAdminister`-gated) | `BACKLOG.md` §3.7 *Job framework — space-to-space comparison (residuals)* | Scheduled cross-Space runs need a persisted grant decision |
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
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **The four vertical Space Templates** (Telecom RA, Fraud, Financial Audit, Link Analysis) | `ls spaces/_templates` → `orders-starter` + `telco-fraud` (Fraud rebuilt 2026-09-30, §3.5; the other three still absent); the packs died with the mock backend `f1553136` | A Should the register, the binding glossary, the user guide and the stakeholder capabilities page all call shipped. Product call: rebuild as `_templates/` entries or drop the promise |
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
| `SpaceManagerTemplateGateTest` (1) | a refused template whose cleanup fails keeps the refusal and never blocks the id |
| `SpaceBootstrapTest` (3) | booting a `SpaceContext` from disk |
| `SpaceLayoutContractTest` (5) | the convention directories; `flows/` and `pipelines/` both tolerated |
| `SpaceIdTest` (2) | the id charset and length |
| `SpaceRootTest` (1) | legacy vs directory-rooted paths |
| `SpaceMigratorTest` (2) | plan / apply / idempotence of the one-time migration |
| `BundleExporterTest` (8) · `BundleImporterTest` (9) | the zip manifest and contents; **the zip-slip jail** and source-prefix rebasing |
| `DataSourceBundleResolverTest` (3) | per-data-source bundle resolution, incl. the forward Reference closure |

### 8.2 Routes — `inspecto/src/test/java/com/gamma/control/` (real HTTP)

| Class | Proves |
|---|---|
| `ControlApiSpacesTest` (4) | `/spaces` CRUD; `authenticatedCreateSucceedsWhenNoSpaceIsHostedYet`; `purgingTheLastSpaceOnDiskIsRefused` |
| `ControlApiSpaceTemplatesTest` (2) | the gallery and `createFromTemplate` |
| `ControlApiSpaceTemplateKpiTest` (4) | a template's KPI pack meets the `/components/kpi` save gate and capability; a refusal creates no Space |
| `ControlApiMultiSpaceTest` (1) | a multi-Space server smoke |
| `ControlApiBundleTest` (11) · `ControlApiBundleImportTest` (11) · `ControlApiBundleNewKindsTest` (10) · `ControlApiPipelineBundleTest` (7) | export / preview / import contract, ordering, the newer kinds, the `authored-pipeline` round trip |
| `ExchangeAttributeScopeTest` · `NoExchangeShipsInThePersonalBuildTest` | exchange attributes private by default; Personal carries no exchange module |
| `PostgresStateStoreTest` (opt-in, `-Dinspecto.test.pg.url`; 11 skipped otherwise) | the DB-backed stores against a real Postgres |

About 92 `@Test` methods across the eighteen Space and bundle classes, all in the default reactor.

### 8.3 UI specs — vitest

Fifteen: `space.interceptor.spec.ts`, `spaces.service.spec.ts`, `space-switcher.component.spec.ts`,
`spaces.component.spec.ts`, `space-form.dialog.spec.ts`, `space-template-gallery.dialog.spec.ts`, two
`import-bundle.dialog.spec.ts` (admin and transfer), `bundle-transfer.service.spec.ts`, `bundle.spec.ts`,
`content-hash.spec.ts`, `stream-bundle.spec.ts`, `stream-transfer.service.spec.ts`,
`transfer-menu.component.spec.ts`, `transfer.component.spec.ts`. ⚠ No `exchange.service.spec.ts` exists.

### 8.4 Committed artifacts

`spaces/default`, `spaces/demo`, `spaces/ucc` (complete; not bundled);
`spaces/_templates/orders-starter/` and `spaces/_templates/telco-fraud/` (the two templates — the only part of `spaces/`
a bundle ships, via `inspecto/package-spaces.ps1`); `spaces/uat/` (a `data/` stub).
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
