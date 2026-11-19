---
type: Capability
title: Spaces & tenancy (SPC) — Anomaly Model examples, bundles, decisions and verification
description: Part 4 of 4 of Spaces & tenancy (SPC): Decisions; Not built; Refused & superseded; As-built mechanism (pointers only); Verification
tags: [spc, capability, spaces, tenancy, multi-space, space-root, migrator, templates, metadata-bundle, exchange, isolation]
timestamp: 2026-09-08T00:00:00Z
---

# Spaces & tenancy (SPC) — Anomaly Model examples, bundles, decisions and verification

Part 4 of 4 of [Spaces & tenancy (SPC)](plan-spaces.md) · previous: [Specification — Space Templates (continued)](plan-spaces-templates-continued.md).

## 3. Specification (continued)

#### 3.5.5 Anomaly Model examples in `telco-fraud`, `mobile-money` and `aml` (`ANOMALY-DETECTION-1` S5, 2026-10-10)

Each pack ships one `anomaly-model` (`registry/anomaly-models/<id>.toon`), its `anomaly.score` Job
(`config/jobs/<prefix>_anomaly_score_job.toon`, `cron: "45 4 * * *"`) and a deferred Alert Rule
(`pending/alert-rules/`, `afterScore: {kind: anomaly-model, model: <id>}`, `max(score) gte 80` by
`[model, entity_key]`, CRITICAL), created after the model's first run (D-AD7). All three use day buckets, a 28-day
window, `seasonality: weekday`, `minBaselinePoints: 3` and `highThreshold: 80`.

| Pack | Model | Entity | Features | Peers |
|---|---|---|---|---|
| `telco-fraud` | `tf_subscriber_usage` | `a_number` over `cdr` | outgoing voice seconds, outgoing SMS, data seconds | none (the pack has no subscriber register) |
| `mobile-money` | `mm_wallet_activity` | `wallet_id` over `wallet_txn` | transaction count, transaction value | `kyc_tier` from `wallets` (`minGroupSize: 30`) |
| `aml` | `aml_account_transfers` | `account_id` over `aml_txn` | outgoing TRANSFER + WIRE count and value | none (`aml_parties` is keyed by party, not account) |

- **No Entity Lists.** No pack ships an Entity List, so no model carries a `watchList` / `exclusionList`; add one
  after creating the Space (a known heavy user belongs on an `exclusion` list, §16.2 of the design).
- **Zero-row seeds for the save gate.** The `anomaly-model` save gate reads each feature Dataset's Schema, and a fresh
  Space has no ingested data, so each feature store ships `data/<feed>/database/schema-seed.parquet` (`cdr`,
  `wallet_txn`, `wallets`, `aml_txn`; columns and types from the feed's schema TOON). The gate is not relaxed. The
  seed stays beside the ingested files (zero rows; the store is read `union_by_name`).
- **Edition.** Without the anomaly module the model is opaque config and its Job does not run; `telco-fraud`'s
  description says that part needs Standard or Advanced with Anomaly Detection.
- **Tests.** `AnomalyTemplateModelsTest` (anomaly module) runs each pack's model through the kind's save gates
  against the pack's registry and seeds and checks the Job + pending-rule wiring. `TelcoFraudTemplateGoldenTest`
  counts only the `sql.template` detection Jobs. The pack golden corpora are not extended (one or ten days of data,
  below the baseline window). No dashboard tile yet.

#### 3.5.6 The `la-showcase` Link Analysis demo (`LA-DEMO-SEED-1`, 2026-10-10)

Two templates: **`la-showcase`** (the demo) and **`la-showcase-off`** (an empty companion whose `modules.toon` disables `geoLink`, so
`/inv/*` there answers 404 `MODULE_DISABLED` while its other routes work; DR-S7). Needs the Advanced **Demo Auth** build (`package.ps1
-Edition Advanced -Distribution demo`, add `-Product la` for the la-app shell); the Link Analysis modules and `canAdminister` to create it.

- **Content.** One Collector + Pipeline + Dataset (`mule_transfers`, partition `BOOKED_DATE`, one file per UTC day, `file_template` so a missing
  day is a delivery gap). `data/inbox` carries the three landed days (ingested within one poll after boot); `data/staged` carries
  `TRANSFERS_20260904.csv` (next day) and `TRANSFERS_20260906.csv` (gap day: 0905 missing) with a one-line `README.txt` - copy one into
  `data/inbox/mule_transfers`. Three saved views (`mule_*`). Settings: `link-analysis.toon` (`masking_mode: typed`, `four_eyes_fan_out_above: 2000`,
  `index.enabled: true` set explicitly, Drafts limits). Jobs: `la_index_build` (`on_signal: pipeline.commit`, guard `$signal.pipeline == mule_transfers &&
  $signal.status == SUCCESS`, `allow_full: "true"`, `owner` is re-stamped to the applier by `TemplateSeedGate`) and `la_detect` (`*/5 * * * *`).
- **Personas** (`demo-users.toon` + `roles.toon`; an authored role REPLACES a seed role of the same name): `admin` (`admin;fraud-analyst`: Reveal),
  `analyst` (`la-analyst`: Manage Incidents, Run Link Graph Analysis, Build Link Index, Author Alert Rules, Work Incidents),
  `manager` (`la-manager`: Approve Link Expansions, Manage Incidents, Work Incidents - the second approver), `reviewer`
  (`la-viewer`: no capability at all) and `outsider` (`no-access`, defined by no `roles.toon`: since 2026-11-18 it signs in and is
  admitted to no Space, every LA demo Space being closed). The Dataset carries explicit `user` shares for all five (standing detection refuses a role-only share).
- **Data tuning (DR-S10).** The ordinary traffic is acyclic and triangle-free (first half of the accounts pays the second half) and small, so the Suspicion
  score ranks `MULE-HUB-01` first (top 3 asserted) and `SHELL-A->B->C->D->A` is the only directed cycle. Pinned by `LaShowcaseGoldenTest`
  (inspecto-graph, reads the shipped CSVs); regenerate with `tools/gen-la-showcase-data.py`. Trade-off: the ordinary traffic is less realistic than the
  `demo` Space's, and the manual's section 10 figures that depend on the old noise (170 nodes, 13 pass-through accounts) differ here.
- **Runtime state has NO template home** (checked 2026-10-10: no registry kind, `case-link.json` is import-reserved, `TemplateSeedGate` seeds registry
  components only). `tools/seed-la-showcase.mjs` seeds it over HTTP as the right personas: the Investigation `mule-hub-case` (8 ops, owner `analyst`),
  reviewers `reviewer`, `manager`, `admin` (`outsider` stays a non-member), Entity List `smurf-accounts` (12 members, one prefix range, one
  expiring entry), the msisdn watch list `telecom-watch-msisdn` (the Wangiri suspect and IRSF-01, so the telecom node dialog's
  *Add to watch list* has a list of its Entity Type), one identity assertion, a Case assigned to `manager` and linked, an Investigation Template, and the bound pass-through Alert Rule
  with standing detection. **Owner is `analyst`, not `admin`**: binding and enabling need the Investigation's owner-and-lead holding Author Alert
  Rules, and the Dataset share must name that owner. Dry run by default; `--apply` writes; re-runs skip what exists.
- **Operator steps.** (1) `pwsh -File inspecto/package.ps1 -Edition Advanced -Distribution demo [-Product la]`; (2) launch `inspecto-la-app` (it scaffolds
  `inspecto-demo/spaces-la-showcase` with both Spaces, then serves on 8097); (3) `node tools/seed-la-showcase.mjs --base http://127.0.0.1:8097` (dry run), then
  `--apply`. On a fresh spaces root the sign-in picker is empty until a Space with `demo-users.toon` exists - that is why step 2 scaffolds first.
  A Space created later from the gallery (`POST /spaces {"id":"la-showcase","template":"la-showcase"}`) is equivalent.
  **Since 2026-10-12 the `-Product la -Distribution demo` bundle ships `la-showcase` already seeded** (bundles ship no Spaces *except* this flavor): unzip,
  `serve-demo`, sign in - see [`build-test.md`](../../layer/platform/spec-build-test.md) (step 6f) and the variants runbook.
- **Variants.** `inspecto-personal` (launch config; a Basic bundle from `package.ps1` with no flags, port 8098): `GET /inv/investigations` answers 503
  `CAPABILITY_UNAVAILABLE`. **Postgres store (no Docker automation):** start the Advanced/Preview server with
  `-Dinvestigations.backend=db -Dinvestigations.db.url=jdbc:postgresql://<host>:5432/<db>` (password through the `SecretResolver`; the `la-store-pg` jar is
  Preview/Advanced only), create the Space, seed. Sample Advanced PDP policy (deny `reviewer` Investigation reads): `config/examples/access-policy-deny-persona.md`,
  inert until applied with `PUT /access/policies`; a persona-based `when` is unverified by any repository test.
- **Not verified here.** The seed script's request bodies are grounded in route code and tests and unit-tested against a mocked `fetch`; it has NOT been run
  against a live server, nor has a Space been created from the template in a Link Analysis build (the template-scanning tests create it in a build without
  the module: `la_index_build` then fails closed on every commit, which is the correct refusal). The `passThrough` value-Measure body is copied from tests of
  other measures. **Cut:** the roaming views (no roaming Dataset in the template), the `account_links` feed (identity is asserted by the seed instead).

### 3.6 Metadata Bundle v2 (SPC-4) — configuration moves, data never does

A bundle is a *serialised, self-describing subgraph* of the component graph for **instance-to-instance
promotion** (staging → production). v2 (R6, 2026-07-06) adds `items[].refs` (each `included | external`),
`items[].provenance` (`sourceSpace`, `exportedAt`, `contentHash` = SHA-256 of the canonical JSON) and
top-level `requires` (the deduped external refs — the bundle's contract with the target); v1 files stay
importable. `BundleRoutes` (`inspecto/src/main/java/com/gamma/control/BundleRoutes.java`):

- **`POST /bundle/export`** — real content + real `contentHash`; each resolvable `requires` ref is
  stamped with an `originHash`; an unsupported kind is a **422** — an honest boundary, never a silent
  omission. Since 2026-08-31 the UI seeds an authored pipeline's closure from `GET /pipelines/{name}/related`,
  because the client derived edges from `nodes[].use` alone and a grammar bound by config key travelled
  nowhere.
- **`POST /bundle/preview`** — the read-only **fit-check**: per item `new | unchanged | drifted |
  unsupported`; per `requires` entry `satisfied | different | missing` (`different` = present at another
  version).
- **`POST /bundle/import`** — `canAuthorWorkbench` → write-root 503 → **integrity pre-check 422**
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

`inspecto-exchange` (Standard+, EDG-01 cell 4) roots at `spaces/_shared/` — reserved, never a Space —
holding `offers.toon` (owner-listed Datasets / Widgets / saved views, catalog metadata only) and `grants.toon`
(the `ShareGrant` ledger tying an offer to a consumer Space). Sharing is per-item, opt-in, grant-mediated,
**read-only**, fail-closed; **cross-Space writes never**; **Schema sharing refused** (a Dataset's Result Set
is self-describing); Dashboards, Pipelines, Jobs, Queries and Expectations are integral to their Space and
move only by *copy* (a bundle). Every route 409s outside a multi-Space runtime, and `features.exchange` is
derived from `hasRoute`, not from "a container root exists" — a Basic install with `-Dspaces.root` once
advertised a Share button that 404'd. Mechanism: `exchange-sharing.md`.

### 3.8 Per-Space stores, settings documents, and the CWD trap

Everything under a `SpaceContext` is per Space: `OperationalDb`, `EventStore`, `StatusStore`,
`ComponentStore` / registry, `JobService` (the scheduler). The per-Space **settings documents** live under
the Space's config root and are bound by `Roles.ATTR_CONFIG_ROOT`, stamped pre-auth by `ControlApi`
: `roles.toon`, `access-policies.toon` (both **fail closed** when unreadable — `SEC` §3.6, §3.10),
`branding.toon`, `geo.toon`, `icon-map.toon`, `nav-menus.toon` (missing ⇒ shipped defaults), `modules.toon` (`disabled: [feature ids]`, `GET|PUT /settings/modules`, `canAdminister`; missing ⇒ every installed module enabled — the per-Space Enabled gate, MODULE-REORG-1 P2b, see [module-taxonomy](../../layer/platform/architecture-modules.md)). Store
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
MDC label. On **Advanced** it is also a **boundary**: `PolicyEngine.SEED` ships `space-isolation` (route)
and `space-isolation-rows` (row) — deny when the subject's mapped `space` home claim ≠ the bound Space,
engaging **only when a `space` claim is mapped** in `roles.toon` (unmapped ⇒ no isolation, never a bricked
API), exempting `canConfigureAccess` holders, overridable per policy name in `access-policies.toon`. The
policy detail is `SEC` §3.10; the tenancy fact is here: **Basic and Standard do not enforce tenancy** —
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
| 2026-09-07 (D8) | Delivery receipts default **`none`** — the opposite call from the dedup ledger | default-ON would mint a DB file in the CWD under `legacy()` on every Basic install |
| 2026-09-07 (EDG-01 cell 4) | `features.exchange` derives from **`hasRoute`**, not from "a container root exists" | a Basic install with `-Dspaces.root` advertised a Share button that 404'd |

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
| ✅ Canonical-pipeline selective bundle export / import — SHIPPED 2026-09-25 as the `pipeline` bundle kind; `authored-pipeline` is export-only (BUNDLE-AUTHORED-PIPELINE-STORE-1, option B) | [metadata-bundle](../processor/spec-metadata-bundle.md) | |
| Bundle residuals — `requires` present-but-different classification; per-editor "load as draft" import | `BACKLOG.md` §3 *Bundle / Exchange* | ⛔ do not fake a draft with a cross-kind `enabled: false` |
| Space-to-space comparison — residuals only; the storage-growth comparison SHIPPED 2026-09-24 (`space.comparison`, `canAdminister`-gated) and its UI 2026-10-11 (Settings › Spaces › *Compare Spaces*; [jobs](../processor/spec-jobs.md) § Space comparison) | `BACKLOG.md` §6 *Space comparison beyond the on-demand admin run* | Declined 2026-10-18: no scheduled runs, no all-Space enumeration, no persisted rows |
| Cross-Space controller / connector-direct emission (S8) | `BACKLOG.md` §3 | Optional Signal-network slice |
| Advanced distributed tier — shared-state backends, distributed scheduler | `BACKLOG.md` §2 *E1*; `REQUIREMENTS.md` NFR-8 | Design only |
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
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **The four vertical Space Templates** (Telecom RA, Fraud, Financial Audit, Link Analysis) | `ls spaces/_templates` → 30 templates (2026-11-15): Fraud (`telco-fraud`, `payment-fraud`), Telecom RA (`telco-ra`, `la-telco-ra`) and Link Analysis (`la-showcase` and nineteen `la-*` siblings) were rebuilt; only Financial Audit is still absent (`la-audit` is a Link Analysis showcase, not that pack); the packs died with the mock backend `f1553136` | A Should the register, the binding glossary, the user guide and the stakeholder capabilities page all call shipped. Product call for the last one: rebuild as a `_templates/` entry or drop the promise |
| **Nothing validates a bundle against `metadata-bundle.schema.json`** | zero Java references to the schema file | The documented contract for the promotion artifact is unenforced; the two samples can drift silently |
| **Ingested-data cloning between Spaces** | the design lock said "roadmap (future)"; no row | The zip and the bundle move config only; a Space clone with data is manual |
| **A multi-tenant user-visibility model** ("users see only the Spaces they may access") + a superuser console | the design lock's open item; nearest live surface is SEC-06 | On Standard every authenticated subject can address every Space |
| **`uat` is a half Space** — `data/` with no `config/` or `space.toon`, not bootable, not mirrored | `spaces/uat/` | Either finish it or delete it; a discover boot ignores it silently |
| **`multi-space.md` omits the export routes** and says the migrator runs on boot | `DataSourceRoutes.java`; `SpaceMigrator` has no non-CLI caller | Corrected with this spec |

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
| The four mock-only vertical seed packs (`telecom-ra`, `fraud-mgmt`, `financial-audit`, `link-analysis`, registered in the mock's `_server` pseudo-Space) | the server catalog `_templates/<id>/template.toon`, shipping `orders-starter` only at first (mock deleted 2026-08-31; 30 templates by 2026-11-15) | this spec §2; `REQUIREMENTS.md` UI-4 |
| Mock-first UI operation | a real `ControlApi` is required (2026-08-31) | `REQUIREMENTS.md` UI-4 |
| Bundle v1 `{kind, id, content}` with both-side ref re-derivation | v2 self-describing subgraph | `metadata-bundle.md` |
| `schema` as a bundle kind | retired 2026-07-31 (unification W1); kept in the TS type so old bundles parse | `metadata-bundle.md` |
| `Offer.dataset` (scalar) + four `if ("widget".equals(kind))` blocks | `Offer.datasets` + `Exchange.DERIVED_KINDS`; the scalar is read, never written | `exchange-sharing.md` |
| `DirSpaceRoot.flowsDir()` as a sibling `spaces/<id>/flows/` | `config().resolve("flows")`; the top-level dir is dead but tolerated | `module/config/spec-config-gotchas.md` |
| `config/flows/` | `pipelines/` (dual-read) | `GLOSSARY.md` §13 |
| Per-store WARN on an unhonourable `postgres` | boot-time `verifySelectable` | `db-layer.md` |
| Ten string literals for DB-family property names | the `OperationalDb.Family` roster | `db-layer.md` |
| `api-stability.md`'s "`DELETE /spaces/{id}` answers 409 unless `?purge=true`" | the rule is the reverse — corrected with this spec | `api-stability.md` |
| `multi-space.md`'s "`SpaceMigrator` migrates on boot" | a one-time CLI — corrected with this spec | `multi-space.md` |
| `GLOSSARY.md` §1's "Shipped verticals: …" and its three `docs/superpower/…` design pointers | the shipped templates (one at the time, 30 by 2026-11-15); provenance paths in `plans-archive/` — corrected with this spec | `GLOSSARY.md` §1 |
| `STAKEHOLDER_OVERVIEW.md` — never mentions Spaces, defers per-tenant ABAC to "future" | shipped Must and shipped Could; plan §8 cluster, reconciled at step 6 | — |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| `SpaceManager` / `SpaceContext` / `SpaceMigrator`, MDC isolation, the seven CRUD routes and the two 409s | `docs/okf/product/module/processor/architecture-processor.md` (`Concept`) | `SpaceManager.java` | §3.1–§3.3 |
| Bundle v2 end to end — endpoints, apply order, the boundary, `connection` and `enrichment` | `docs/okf/product/module/processor/spec-metadata-bundle.md` (`Concept`) | `BundleRoutes.java` | §3.6 |
| Offers, grants, snapshots, `_shared/`, D9 | `docs/okf/product/module/exchange/spec-exchange.md` (`Concept`) | `features/inspecto-exchange/` | §3.7 |
| Per-Space file topology, `OperationalDb`, PG-1, the CWD traps | `docs/okf/product/module/engine/spec-db-layer.md` (`Reference`) §4–§5 | `inspecto-engine` | §3.8 |
| The Space-root jail: `PathJail`, declared ∪ discovered roots | `docs/okf/product/module/config/spec-config-safety.md` (`Concept`) | `platform/inspecto-config/` | §3.8, §6.6 |
| The Spaces layout and the migrator CLI | `docs/okf/product/module/config/admin-guide-configuration-reference.md` (`Reference`) §Spaces | — | §3.2 |
| The two Space gotchas — MDC across workers; paths resolve against the CWD | `docs/okf/product/layer/platform/admin-guide-gotchas.md` (`Reference`) | — | §3.3, §3.8 |
| The seeded isolation policies | `docs/okf/product/module/access/plan-security.md` §3.10 · `docs/okf/product/module/access/admin-guide-auth-security.md` | `providers/inspecto-policy/` | §3.9 |
| The Spaces admin view and the template gallery | `docs/okf/product/layer/product/user-guide-spaces.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/spaces` | §3.10 |
| `SpacesService`, the `spaceInterceptor`, `SERVER_GLOBAL` | `docs/okf/product/layer/product/spec-multi-space.md` (`Convention`) | `inspecto-ui/src/app/inspecto/api/space.interceptor.ts` | §3.10 |
| ⚠ **No concept file covers the whole-Space zip export / import** (`DataSourceRoutes`, `BundleExporter`, `BundleImporter`) or the template catalog's file format | *(gap — one paragraph each in this spec, §3.4 and §3.5)* | `inspecto/src/main/java/com/gamma/service/BundleExporter.java` · `BundleImporter.java` | the code |

---

## 8. Verification

### 8.1 Model and lifecycle — `inspecto/src/test/java/com/gamma/service/`

| Class | Proves |
|---|---|
| `SpaceManagerTest` (21) | create / update / delete / discover, CRUD lifecycle, close-with-deadline |
| `SpaceManagerTemplateGateTest` (1) | a refused template whose cleanup fails keeps the refusal and never blocks the id |
| `SpaceBootstrapTest` (3) | booting a `SpaceContext` from disk |
| `SpaceLayoutContractTest` (5) | the convention directories; `flows/` and `pipelines/` both tolerated |
| `SpaceIdTest` (2) | the id charset and length |
| `SpaceRootTest` (1) | legacy vs directory-rooted paths |
| `SpaceMigratorTest` (7) | plan / apply / idempotence of the one-time migration |
| `BundleExporterTest` (9) · `BundleImporterTest` (8) | the zip manifest and contents; **the zip-slip jail** and source-prefix rebasing |
| `DataSourceBundleResolverTest` (3) | per-data-source bundle resolution, incl. the forward Reference closure |

### 8.2 Routes — `inspecto/src/test/java/com/gamma/control/` (real HTTP)

| Class | Proves |
|---|---|
| `ControlApiSpacesTest` (5) | `/spaces` CRUD; `authenticatedCreateSucceedsWhenNoSpaceIsHostedYet`; `purgingTheLastSpaceOnDiskIsRefused` |
| `ControlApiSpaceTemplatesTest` (7) | the gallery and `createFromTemplate` |
| `ControlApiSpaceTemplateKpiTest` (4) | a template's KPI pack meets the `/components/kpi` save gate and capability; a refusal creates no Space |
| `ControlApiMultiSpaceTest` (1) | a multi-Space server smoke |
| `ControlApiBundleTest` (16) · `ControlApiBundleImportTest` (13) · `ControlApiBundleNewKindsTest` (12) · `ControlApiPipelineBundleTest` (12) | export / preview / import contract, ordering, the newer kinds, the `authored-pipeline` round trip |
| `ExchangeAttributeScopeTest` · `NoExchangeShipsInThePersonalBuildTest` | exchange attributes private by default; Basic carries no exchange module |
| `PostgresStateStoreTest` (opt-in, `-Dinspecto.test.pg.url`; 11 skipped otherwise) | the DB-backed stores against a real Postgres |

130 `@Test` methods across the eighteen counted Space and bundle classes (recounted 2026-11-15), all in the default reactor.

### 8.3 UI specs — vitest

Fifteen: `space.interceptor.spec.ts`, `spaces.service.spec.ts`, `space-switcher.component.spec.ts`,
`spaces.component.spec.ts`, `space-form.dialog.spec.ts`, `space-template-gallery.dialog.spec.ts`, two
`import-bundle.dialog.spec.ts` (admin and transfer), `bundle-transfer.service.spec.ts`, `bundle.spec.ts`,
`content-hash.spec.ts`, `stream-bundle.spec.ts`, `stream-transfer.service.spec.ts`,
`transfer-menu.component.spec.ts`, `transfer.component.spec.ts`. ⚠ No `exchange.service.spec.ts` exists.

### 8.4 Committed artifacts

`spaces/default`, `spaces/demo`, `spaces/ucc` (complete; not bundled);
the 30 templates under `spaces/_templates/` (counted 2026-11-15; §3.5 — the only part of `spaces/`
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
| **No test that the four verticals exist** — Financial Audit still does not; the shipped templates are applied by `ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies` | `ls spaces/_templates` |
| **No test of the MDC-copy invariant across every executor** | pinned by convention and the gotcha page, not by a sweep |
| **Postgres path runs only opt-in** | `PostgresStateStoreTest` skips 11 without `-Dinspecto.test.pg.url` |
| **No Exchange UI spec** | §8.3 |
| **`uat` is never booted by any test** | it has no `config/` |
