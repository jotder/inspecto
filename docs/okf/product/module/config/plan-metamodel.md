---
type: Capability
title: Component metamodel & Catalog (MET)
description: Everything authored is a Component — the kind registry and ComponentStore, config specs, ETag concurrency, version history, delete protection and integrity, the single ref derivation; the Catalog read model (MetadataGraph, NodeKind, IdScheme, the four graph planes); the Schema and Mapping registry with fail-closed field types; and Stream/Reference onboarding. The requirement of record for the MET area, its specification, its decisions, and what was refused.
resource: platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentStore.java, platform/inspecto-engine/src/main/java/com/gamma/catalog
tags: [met, capability, component, component-store, kind-registry, catalog, metadata-graph, node-kind, id-scheme, schema, mapping, field-types, onboarding, lineage]
timestamp: 2026-09-08T00:00:00Z
---

# Component metamodel & Catalog — capability spec (`MET`)

> **What this page is.** The single entry point for the MET capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Sixth of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §3, §10, §11 — binding). A **Component** is a configured, named,
> persisted *instance*; a **Component Type** (= **Kind**) is the reusable *template* that declares its config
> schema. The **Registry** is the **derived** reuse graph over Components — not a store. A **Schema** is
> structure only (fields, Attribute Types, selectors, classification); a **Mapping** is the reusable field map
> — ⛔ never fold a Mapping back inside a Schema. The **Catalog** indexes a Space's Schemas and Datasets; a
> **Stream** is an event/fact data origin, a **Reference** a dimension origin — ⛔ never *Data Source*. The
> four graph planes keep their own words: **P1** artifact (`part-of` / `uses`), **P2** lineage
> (`EMITS … CONSUMES`), **P2′** provenance (`flowed-through`), **P3** entity/link. A canvas node is a **Step**.

## 1. Purpose & scope

MET is **the spine every other area hangs on**: the rule that everything authored is a Component of a
declared kind, the store and routes that persist and version it, the derivation that knows what references
what (so a delete can refuse, a bundle can close over dependencies, and a graph can be drawn), the read
model that shows an operator where data comes from and goes, and the Schema and Mapping registry that tells
the engine what a record looks like. Its defining property is that **relationships are derived, never
stored**, and that **the store is federated per kind** — deliberately not one generic editor or one table.

**In scope:** `ComponentStore`, `WRITABLE_TYPES`, `ComponentRegistry`, the on-disk layout and `.history/`;
`ConfigSpecs` / `ConfigSpec` / `FieldSpec` and the UI's `AttributeSpec`; the `/components/{type}` routes,
`ContentHash` ETags, versions and restore; per-kind write hooks; delete protection (`PipelineReferences`,
Exchange consumers) and `ComponentIntegrity`; the single ref derivation (`refsForComponent`) and its
consumers; the Catalog read model (`com.gamma.catalog`: `MetadataGraph`, `MetadataGraphService`, `NodeKind`,
`EdgeKind`, `IdScheme`, `OperationalOverlay`) and `GET /catalog/*`; the four graph planes; the Schema and
Mapping registry, `SchemaFieldTypes`, `SchemaExtractor` (retired with `ura`, 2026-11-06), `resolveSchemaRef`, the `rules[]` → `fields[]`
spelling state; field classification; Stream / Reference onboarding as a composed flow; the Catalog and
Components SPA.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| The pipeline editor, Step palette, `PipelineEditable`, the Record Transformer | `PIP` (authoring) — MET owns the *kinds* a `use:` binds, not the canvas |
| Datasets, Queries, Widgets, Dashboards as *features* | `DAT` / Studio — MET owns only that they are Component kinds in one store |
| Moving Components between instances (Bundle v2) and between Spaces (Exchange) | `SPC` — MET owns the refs the bundle closes over |
| Who may write a Component (`canAuthorWorkbench`) | `SEC` |
| Path containment for `schema_file` and every config-declared path | `TOOL` / config safety |
| Link Analysis and Geo Map (the P3 plane's *studios*) | Studio (`BI`+`INV`) — MET owns the plane vocabulary only |

## 2. Requirements of record

Five requirements from `REQUIREMENTS.md` §3.10 (that section was stripped to an index on 2026-09-09 — this file is their only home now), all recorded shipped. **Two describe a shape and a scope the
backend does not have.** ⚠ **`EDITIONS.md`'s feature × edition matrix is authoritative for the Edition
column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `MET-1` | Everything authored is a **Component** `{kind, name, config, parts?, wiring?}`; the kind registry declares config schemas | Must | ✅ SHIPPED — ⚠ **that shape is the SPA's** (`inspecto/component-model/`); the server stores `Component(type, name, path, content)` with a free-form `content` map, and only 4 of the 34 writable kinds have a `ConfigSpec` (§3.1) | All |
| `MET-2` | Derived **Registry** reuse graph + Catalog + lineage graph (canonical edge / node kinds) | Must | ✅ SHIPPED | All |
| `MET-3` | Single ref derivation (`deriveRefs`) feeding reuse graph, bundles, **delete-protection** | Must | ✅ SHIPPED (R1) — ⚠ the single derivation is **client-side** (`refsForComponent`); server-side delete protection is a fixed set of refusals (owner-only for a shared component, pipeline `use:` refs, Exchange grants, stranded Workflow states, a Runbook an Alert Rule links, a Dataset Mappings read) — a widget a dashboard tiles, or a dataset a widget binds, is only *reported* by the link census unless `-Dlinks.delete=refuse` (§3.4) | All |
| `MET-4` | **Stream** read model in the Catalog | Should | ✅ SHIPPED 2026-07-08 — ⚠ `/catalog/streams` is **per-Collector**; the glossary's grouped Stream (`stream:` membership) is a different node (§3.6) | All |
| `MET-5` | Draft / published Component version history (W3b) | Could | ✅ SHIPPED 2026-07-09 — ⚠ undocumented in its own concept page until this spec ⚠ **Named for grep:** the history UI is `ComponentHistoryDialog` (`inspecto-ui/src/app/inspecto/components/component-history.dialog.ts`). | All |

**Corrections this table makes to its predecessor and its concept page**, each verified against source:

- **`MET-1`'s shape is a frontend model, stated as if it were the store.** Five documents spell the Component
  five ways (`{kind, name, …}`, `{kind, id, …}`, `{kind; id; name; space?; …}`, `{kind, id, config}`); the
  persisted record is `ComponentRegistry.Component(type, name, path, content)`
  (`platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentRegistry.java`) — no `kind`, `config`,
  `parts` or `wiring` field exists server-side, and the recursive shape lives in the SPA's
  `component-model/`. The requirement is met *as a model*; the row now says where the model lives.
- **`MET-3`'s "delete-protection" is narrower than "feeding".** `ComponentRoutes.deleteComponent`
  (`inspecto/src/main/java/com/gamma/control/ComponentRoutes.java`) refuses on a fixed list (§3.4), led by
  `PipelineReferences` (`use:` refs) and active Exchange grants. `ComponentIntegrity`'s broken-ref rules
  (widget → dataset / query, dashboard tile → widget, reconciliation → dataset) run in the `metadata_validate`
  task and the bundle-import pre-check, **not on delete**; on delete the same widget and dashboard links are
  census links (`LinkRefusals.delete`), reported by default and refused only in `refuse` mode. The archived
  design named exactly this gap and closed it in the *mock* integrity rules — now deleted with the mock.
- **The concept page says `schema` is not a Component — it is.** `component-registry.md` recorded the
  2026-07-31 retirement (unification W1); the ELT amendment **reversed it on 2026-08-05**, and
  `ComponentStore.WRITABLE_TYPES` (`ComponentStore.java`) carries `schema` and `mapping` today; the
  bundle orders them; the UI palette lists them. Four current docs disagreed. Corrected with this spec.
- **The concept page says a stale `If-Match` is "precondition-failed".** It is `409 CONFLICT_STALE_VERSION`
  (`ETags.requireMatch`; `API` §3.5). ⚠ In the Components pane only the schema editor sends `If-Match` (§3.9).
- **`REQUIREMENTS.md` calls the derivation `deriveRefs`.** The function is `refsForComponent`
  (`inspecto-ui/src/app/inspecto/component-model/refs.ts`); `ComponentKind.deriveRefs` is the per-kind
  *override seam* on it. No Java symbol of either name exists.

**Two standing notes no status token can carry:**

1. ✅ **The Schema's mapping block has one written spelling: `mapping.fields[]`.** The engine **prefers
   `mapping.fields[]`** (`DataTransformer.recordFields`, `platform/inspecto-etl/src/main/java/com/gamma/etl/DataTransformer.java`,
   falling back to `rules[]` via `RecordTransform.fromMappingRules`) since `transform.map` was deleted on
   2026-09-05. The operator decided `MAPPING-SPELLING-1` on 2026-09-10 (emit `fields[]`, keep the `rules[]`
   read bridge) and `MAPPING-GEN-1` shipped it the same day: `ConfigPreviewRoutes` emits `mapping.fields[]`
   and no `transformType`, and `SchemaExtractor` did too until it was deleted on 2026-11-06. All 201 committed
   `*_schema.toon` carry `mapping.fields[]`; none carries `rules[]` (counted 2026-11-15). *(History: the
   2026-09-08 premise that the committed schemas were unmigrated was wrong — they were already on `fields[]`.)*
2. ⚠ **One word, two config shapes — deliberately.** `schema` names both the **registry component** (a
   column list under `registry/schemas/`, validated by `ConfigSpecs.schemaComponent()`) and the **TOON
   schema config** (`<name>_schema.toon` with `raw:` / `mapping:`, validated by `ConfigSpecs.schema()`).
   Recorded 2026-07-27 as a known violation of one-word-one-concept that *stands* because renaming either is
   an on-disk break; say *schema component* or *schema config*. The Phase-1 split (Schema CSV + Mapping
   CSV) is what retires it, and `mapping` is a CSV kind today (`ComponentRegistry.CSV_KINDS`).

## 3. Specification

### 3.1 The Component model, as stored and as modelled

**Stored.** `ComponentStore` (`platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentStore.java`,
`@PublicApi(since = "4.0.0")`) persists `<write-root>/registry/<typeDir>/<id>.toon` — `.csv` for the CSV
kinds (`mapping`) — and `ComponentRegistry` scans it into `Component(type, name, path, content)`.
**`WRITABLE_TYPES` is 34 kinds:** `grammar`, `schema`, `mapping`, `transform`, `sink`, `dataset`, `widget`,
`dashboard`, `query`, `expectation`, `requirement`, `link-analysis-view`, `geo-map-view`, `decision-rule`,
`reconciliation`, `access-catalog`, `access-profile`, `alert-rule`, `risk-score`, `anomaly-model`,
`segmentation-model`, `churn-model`, `forecast-model`, `channel`, `notification-rule`, `findings-spec`,
`pattern-pack`, `preset-query`, `rule-template`, `kpi`, `runbook`, `workflow`, `sla-policy`, `escalation-rule`
(counted 2026-11-15; the last three are written only through their `canAdminister` literal routes).
`connection` is **deliberately excluded** (its own
secret-aware CRUD). ⚠ **A new kind needs two registrations** — `WRITABLE_TYPES` and
`ComponentRegistry.TYPE_BY_DIR` — pinned by `ComponentStoreTest.everyWritableTypeHasARegistryDir`.
**Persistence is federated by kind on purpose:** `WRITABLE_TYPES` widens only when a kind needs real storage
(W3 widened it for the Studio kinds on 2026-07-07, the day after the adoption plan called widening a
"risk explicitly avoided"); pipelines (`PipelineStore`), jobs (`JobService`) and connections keep their own.

**Modelled.** The recursive shape `{kind, id, name, config, parts?, wiring?}` — atomic kinds without parts,
composites (Pipeline, Dashboard, Job) with parts and a wiring strategy `graph | layout | schedule |
mapping | none` — is the SPA's `component-model/` (`component-kind.ts`, `component-registry.ts`, `refs.ts`),
with strategy seams as **string keys** (`editorKey` / `runnerKey`) so the model imports no Angular.
Adoption D0 → P4 completed 2026-06-28.

**Declared config schemas.** `ConfigSpecs.TYPES` (`platform/inspecto-config/src/main/java/com/gamma/config/spec/ConfigSpecs.java`)
is **nine** types — `pipeline, enrichment, job, schema, meta, alert, expectation, widget, dashboard` — served
by `/config/spec/{type}` and on `/bootstrap.configSpecs`; a `ConfigSpec` is `(type, fields, rules)`, a
`FieldSpec` typed by `FieldType` ∈ `STRING · INT · LONG · BOOL · ENUM · FILEPATH · CRON · SQL · MAP · LIST`.
**Most registry kinds have no `ConfigSpec`** — they have a `validateKind` hook (§3.3) or nothing; a blanket
"registry kinds have no spec" was refused because `widget` and `dashboard` specs describe their components
accurately. The SPA renders specs through `<inspecto-schema-form>` from `AttributeSpec[]` — a **frontend**
vocabulary pinned by `attribute-spec.contract.json` and compared by `FindingsSpecContractTest`; `AttributeSpec`
is not a Java class. ⚠ `ConfigSpecs` has **no `mapping()` spec**; `TYPES` omits `mapping` (`GLOSSARY.md` §13).

### 3.2 Routes, ETags, versions

`ComponentRoutes` (`inspecto/src/main/java/com/gamma/control/ComponentRoutes.java`): `GET /components/{type}`
· `GET /components/{type}/{id}` · `POST /components/{type}` (`409` if it exists) · `PUT /components/{type}/{id}`
· `DELETE /components/{type}/{id}` · `GET …/{id}/versions` · `POST …/{id}/versions/{v}/restore`;
every mutation gated `canAuthorWorkbench`, then write-root `503`. **Concurrency:** `ContentHash`
(`inspecto/src/main/java/com/gamma/control/ContentHash.java`, SHA-256 over key-sorted JSON, parity-pinned
with the SPA's `content-hash.ts` by `ContentHashTest`) is the ETag; reads honour `If-None-Match` → `304`;
a `PUT` with a stale `If-Match` is **`409 CONFLICT_STALE_VERSION`**; an absent header passes.
**History (MET-5):** every write archives the prior copy to `<typeDir>/.history/<id>.v<N>.toon` — a
sub-directory, so the registry scan never mis-reads it as a duplicate — keep-N `-Dcomponents.history.keep`
(default 10, min 1; `ComponentStore.java`); restore is itself a versioned write. Preview:
`POST /components/{type}/{id}/test` runs a transform / grammar / sink over sample rows on a **throwaway**
DuckDB (`ComponentPreview`); the schema cast check is `POST /config/preview/schema`.

### 3.3 Per-kind write hooks

`ComponentRoutes.writeComponent` → `validateKind`: `findings-spec` (id ↔
`objectType`, section vocabulary, `422`); `schema` (re-runs `ConfigLoader.validate` +
`ConfigSafetyValidator` — the same gate as `/config/write` — plus the **schema-compatibility gate**
: a narrowing change is `422` with cell-anchored findings unless `?compatibility=none` is
confirmed, with drift detected by `SchemaMappingDrift`); `mapping` (`MappingRules.validate`). ⚠ The SPA's
schema editor therefore writes through the **gated `POST /config/write type=schema`**, while the mapping
editor writes through plain component CRUD plus `POST /components/mapping/validate` — two paths for two
kinds, by design.

### 3.4 Delete protection, integrity, and the one derivation

**Server-side delete protection** (`deleteComponent`): `404` if absent; **`403`** if the component is shared
and the caller is not its owner (`ComponentAccess.requireDelete`); **`409`** if
`PipelineReferences.referencedBy` finds a pipeline `use:` reference; **`409`** if `activeConsumers` finds a
cross-Space Exchange grant; **`409`** if a `workflow` change would strand live objects in a dropped state
(`requireNoStrandedObjects`); **`409`** if an Alert Rule links the `runbook` (`requireNoLinkingAlertRules`);
**`409`** if Mappings read the `dataset` (`DatasetEntryRules.mappingsOf`). Census links still naming it
(`LinkRefusals.delete`, `SPACE-LINK-CENSUS-1`) are reported as `brokenLinks` under the default
`-Dlinks.delete=shadow` and refused `409` only under `refuse` (`?force=true` overrides); `refuse` is built and
ready, and the default flip waits for the operator (D-LC3). That is the whole set. **`ComponentIntegrity`**
(`platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentIntegrity.java`) is pure functions over an
in-memory universe — `brokenRefs` (widget → dataset / query, dashboard tile → widget, reconciliation →
dataset), `brokenPipelineRefs` (expectation / decision-rule `target`), `duplicates` (content-identical apart
from name) — consumed by the `metadata_validate` maintenance task and the bundle-import pre-check (MNT-16:
only findings the import would *introduce*), **not by delete**. Pipeline *configs* are protected separately:
`DELETE /config/pipeline/{name}` `409`s with the dependent list unless `?force=true`
(`PipelineDependents.scan`, 2026-08-14), with a read-only `GET /config/pipeline/{name}/impact`.

**The one derivation (MET-3, R1)** is **`refsForComponent`** in
`inspecto-ui/src/app/inspecto/component-model/refs.ts`: "the ONE derivation of *what does this config
reference?*" — previously four sites re-derived it with different completeness (reuse-graph `partsFor`,
bundle `refsOf`, mock integrity rules, `dashboardParts`); a registered `ComponentKind.deriveRefs` takes
precedence as the seam future kinds implement. It yields `Ref {kind, id, rel, via}` and feeds the Usage graph
and the bundle closure (the server merges its own `GET /pipelines/{name}/related` edges since 2026-08-31,
because a grammar bound by **config key** was invisible to a `use:`-only derivation). ⚠ The `use:` prefix is
the **singular** `connection/`; the plural is tolerated on read only for pre-2026-08-04 graphs.

### 3.5 The Catalog read model

`com.gamma.catalog` (`inspecto-engine`): `MetadataGraph`, `MetadataGraphBuilder`, `MetadataGraphService`,
`MetadataNode`, `MetadataEdge`, `NodeKind`, `EdgeKind`, `IdScheme`, `Description` / `DescriptionProvider`
(SPI), `Provenance`, `OperationalOverlay`, `CatalogOverlay`, `SchemaProjection`, `SemanticModel`. Nodes are
**derived from configuration** (pipelines, collectors, KPI definitions) and **hydrated at read** with a
runtime `OperationalOverlay` — never from audit; operational state rides `MetadataNode.overlay()`, not an
edge.

| Vocabulary | Values |
|---|---|
| `NodeKind` (12) | ETL half — `STREAM · RAW_SCHEMA · STEP · COLUMN · TABLE · DERIVED_TABLE · REFERENCE_DATASET` (`STEP` = a `sql` Step describing its output columns, 2026-10-11, [schema-mapping-authoring §0](../elt/user-guide.md)); semantic — `KPI · REPORT`; Studio BI — `DATASET · WIDGET · DASHBOARD` (⚠ this row read "(7)" and omitted `REPORT` until 2026-09-13) |
| `EdgeKind` (9, the P2 lineage plane) | `EMITS · DECLARES · DESCRIBES · MATERIALIZES · FEEDS · JOINS_INTO · COMPUTED_FROM · CONSUMES · BINDS_TO` |
| `IdScheme` tokens | `stream:` · `schema:` · `event:` · `col:` · `xform:` · `ref:<enrich>/<ref>` · `ref:<pipeline>` (a produced Reference) · `kpi:` · `report:` · `dataset:` · `widget:` · `dashboard:` |

**The Studio BI layer** (`MetadataGraphBuilder.addStudioLayer`, 2026-09-13) reads `dataset` / `widget` /
`dashboard` components through `ConfigSource.components(type)` — a channel added because a Widget's
`datasetId` names a **Studio component** (`registry/widgets/*.toon`), not a catalog node. It wires
`DASHBOARD → WIDGET → DATASET` with `CONSUMES` and `DATASET → STREAM | REFERENCE_DATASET` with
`BINDS_TO`.

🔴 **The bridge does not always resolve, and that is modelled, not hidden.** A Studio Dataset names its
origin by `sourceName` and/or the head segment of `physicalRef` — and that head names an **origin, not a
table**. It is sometimes a Job output store the catalog has no node kind for (`rollup`), and sometimes
absent (`kind: virtual`, `physicalRef: null`). Such a Dataset still gets a node, carrying `resolved=false`
with no `BINDS_TO` edge; an edge is drawn only to a node that exists (broken component refs are
`ComponentIntegrity`'s report, not the builder's).

⚠ **That binding rule is mirrored in FOUR places and cannot be shared** (different modules):
`MetadataGraphBuilder.addStudioLayer`, `PipelineDependents.datasets` (the delete-impact and dependents
routes), `DataSourceBundleResolver.datasetReadsStore` (`physicalRef` half only) and
`PipelineRenameRoutes.names`. ⛔ Do not let `sourceName` shadow `physicalRef` — the mirrors
check both, so a Dataset naming two origins binds to two.

⚠ **A Dataset node's `binding` attr is a DIFFERENT question from `resolved`, and a FIFTH mirror.**
`binding` (`view` | `physicalRef` | `unbound`) is `BiRoutes.datasets`' vocabulary and answers on **key
presence**, so a `physicalRef: null` reads as `physicalRef` on both routes; `resolved` says whether that
declared binding reaches a catalog node. Keep the two apart — answering `unbound` for a declared-but-
unresolvable ref would make one Dataset describe itself two ways on two routes.

All of the above is covered by `MetadataGraphStudioLayerTest`
(`platform/inspecto-engine/src/test/java/com/gamma/catalog/MetadataGraphStudioLayerTest.java`).

`CatalogRoutes` (`inspecto/src/main/java/com/gamma/control/CatalogRoutes.java`): `GET /catalog` (tables) ·
`/catalog/streams` (**per-Collector** data-origin nodes shaped to the UI `MetadataNode` contract —
MET-4) · `/catalog/references` (`REFERENCE_DATASET` nodes, 2026-07-14) · `/catalog/kpis` ·
`/catalog/graph` (`from / depth / direction / kinds / edgeKinds / overlay`) · `/catalog/resolve?table=|pipeline=`
(**unique match only** — zero and several both `404`, a blank `output_table` batch resolves by
pipeline since 2026-09-06) · `/catalog/tables/{id}` (node + depth-2 neighbours; its `(.+)` is greedy,
so no sub-paths). There is no `/catalog/search`, `/catalog/lineage` or `/catalog/schemas`. **Stream grouping**
(2026-07-24): pipelines sharing a `stream:` membership key collapse under one `stream:<logical>` node in
`MetadataGraphBuilder` — the glossary's "one sub-system = one Stream"; `/catalog/streams` is a separate
per-Collector projection and is unaffected (§2 note on MET-4). **Reference production** (`produces:
reference`, `reference: {load: replace | upsert | scd2, key[], refresh_seconds}`) is the P0–P3 slice of
`onboarding-authoring.md`.

### 3.6 The four graph planes — one renderer, four vocabularies

| Plane | Relates | Words | Backed by |
|---|---|---|---|
| **P1 artifact** | Components | Component / Part — `part-of`, `uses` | the derived Registry (`refsForComponent`) — rendered as the Catalog's **Usage** tab |
| **P2 lineage** | data assets | Asset — the nine `EdgeKind`s | `MetadataGraphService` — the **Lineage** tab |
| **P2′ provenance** | a Consignment's records through Steps | Step — `flowed-through` (+ row counts) | `DbProvenanceStore` (`platform/inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java`) + `GET /lineage?store=` |
| **P3 entity / link** | records as business entities | Entity / Link | the Entity Projection over a Dataset — built frontend-first, then server-side (`InvRoutes`, `POST /inv/projection`) |

Two collisions were resolved and must not regress: lineage `USES` → **`CONSUMES`** so it stops colliding with
the artifact graph's `uses`; and `LineageRow` is **Provenance** data, not the Lineage graph. **Lineage** is
design-time and structural; **Provenance** is the run-time recorded fact. ⛔ Do not join provenance on
`batch_id` — the two halves (ingest file → store; authored pipeline step → step) stitch on the **store name**.
⚠ The Usage tab embeds the reuse graph *inside* the Catalog while the vocabulary keeps P1 and P2 as distinct
planes — the archived analysis's open question "one graph or two?" is answered *two vocabularies, one
screen*, and the glossary's Catalog definition ("Schemas and Datasets") predates the tab set.

### 3.7 The Schema and Mapping registry

- **Schema config** (`<name>_schema.toon`, pipeline-owned): `raw.fields[] {name, selector, type, description,
  unit, classification}` + the `mapping:` block. **`raw.fields[].type` is fail-closed** —
  `SchemaFieldTypes` (`platform/inspecto-etl/src/main/java/com/gamma/etl/SchemaFieldTypes.java`, operator decision
  2026-08-22) is the one vocabulary: every DuckDB scalar reachable by `TRY_CAST` from text is honoured and
  compiles to a known cast; a type outside it is **refused at config load** (`Identifiers.validateSchema`)
  rather than degraded to `VARCHAR` — before 2026-08-22 a `type: BIGINT` silently produced a string column.
  `classification` was a free string when this was written; since 2026-11-14 it is a closed vocabulary per
  Space, refused at save, and masking reads it — `transform.mask` masks by `classifications:`
  (`MaskSpec`, `EvidenceMasker`) (`okf/product/module/config/spec-config-safety.md`, "The
  classification vocabulary").
- **Resolution.** `PipelineConfigParser.resolveSchemaRef` (`platform/inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java`):
  beside the config only, via `PathJail.resolveConfigRef` (never the CWD, since 2026-09-23); `schema/<id>` → `registry/schemas/<id>.toon`, `grammar/<id>` →
  `registry/grammars/<id>.toon`, `mapping/<id>` → `registry/mappings/<id>.csv`; `ConfigSafetyValidator` and
  `ConfigRoutes` call the same resolver. ⚠ **No `registry/schemas/` directory exists in any committed
  Space** — every shipped schema is pipeline-owned.
- **Generation.** `SchemaExtractor` (CLI `create-schema`, deleted 2026-11-06 with the `ura` utilities) inferred
  `<source>_schema.toon` + `<source>_pipeline.toon` from a sample by DuckDB type inference; `ConfigPreviewRoutes` serves `/config/preview/schema` (TRY_CAST a draft against sample rows),
  `/config/suggest/schema` and `/config/schema/derived` (DESCRIBE over a saved pipeline's output). Both
  generators emitted **`mapping.fields[]`** since `MAPPING-GEN-1` (2026-09-10) (§2 note 1).
- **Mapping** is a CSV component kind (`registry/mappings/<id>.csv`, `MappingRules.validate`,
  `POST /components/mapping/validate`), the reusable field map; `MappingMigrator` rewrites a schema's
  `rules[]` block to `fields[]` **as text** (parse-and-reserialise would churn hundreds of untouched lines),
  `--dry-run` first — a CLI, no UI surface.

### 3.8 Onboarding — a composed flow, not a route

There is **no `/onboarding/*` route module**. A Stream or Reference is onboarded by composition: the Catalog's
"Onboard" CTA (`canAuthorWorkbench`) writes a minimal `active: false` draft via `POST /config/write`,
registers it via `POST /runs` for immediate Catalog visibility, and hands off to the pipeline editor as the
guided surface (the old per-stage wizard routes redirect there); **a draft is an inactive pipeline**
(D1–D4, 2026-07-16), validation is the chained existing tests, and the unregister counterpart
(`DELETE /config/pipeline/{name}` → `CollectorService.unregisterPipeline`, `EnrichmentService.unregister`)
shipped 2026-07-20. A Stream can also be imported from an exported stream bundle. The 2026-07-30
Onboarding ↔ Pipeline *split* into two planes was **reversed the next day** (U-A…U-G): one editor, the
graph editor writes the canonical `*_pipeline.toon`. Seams: `onboarding-authoring.md`.

### 3.9 The SPA

**Catalog** (`/admin/catalog`): tabs Streams (default) · References · Tables · KPIs · Lineage (`graph`) ·
Usage · Shared with / by me (Exchange-gated); `MetadataNode` / `NodeKind` in `inspecto/api/models.ts`
as an **open** union. ⚠ **One concept, two spellings — corrected 2026-09-14.** The wire carries
`RAW_SCHEMA` (`CatalogRoutes` serializes `kind().name()`, so the enum constant IS the wire value); the
UI's `SCHEMA` is the *pipeline editor's* *synthetic* visual kind (`categoryVisualKind`: PARSE → SCHEMA),
as is `ENRICHMENT`. 🔴 The SPA had modelled `SCHEMA` only, so **every real catalog schema node rendered
unstyled** — the previously recorded note "the UI also names `SCHEMA`, `REPORT`, `ENRICHMENT` the server
never emits" was right about the synthetics but missed that a wire kind had no home. Both Schema
spellings are now styled identically, pinned by a spec so a palette de-duplication cannot split them.
One G6 `GraphViewComponent` renders every plane.

**Node styling — `colour names the layer, shape names the structural role`** (operator decision
2026-09-14, `CATALOG-KIND-STYLING-1`). All twelve wire kinds now carry a colour, a shape and a GLOSSARY
label (`STEP`, added 2026-10-11, is a green triangle labelled **Step**): `DERIVED_TABLE` displays as **Matrix**, `REFERENCE_DATASET` as **Reference Dataset**, and the
three Studio BI kinds (`DATASET` / `WIDGET` / `DASHBOARD`) share one magenta family separated by shape
(rect / hexagon / star), so the BI layer reads as a layer. ⛔ `ENRICHMENT` is left **deliberately
unlabelled** — GLOSSARY §5 D-4 retires the user-facing term ("author no new user-facing 'Enrichment'
copy"); a spec pins its absence. 🔴 **TypeScript polices none of this**: `NodeKind` ends in `| string`,
so `Record<NodeKind, …>` is `Record<string, …>` and a missing kind is not an error — the runtime list
`CATALOG_NODE_KINDS` (one entry per enum constant) plus its completeness specs is the only guard, and a
new `NodeKind` must be added there. ⚠ **Glyphs are NOT part of this surface:** the catalog mapper
(`toG6Data`) sets no `iconSrc`, and `graph-view.component` only draws an icon when one is present, so
`KIND_GLYPH` is reached solely by the pipeline editor — a glyph added for a catalog-only kind is dead
code. Shape is likewise live *only* for the catalog (the pipeline editor forces `rect`). **Components**
(`/admin/components`): `COMPONENT_TYPES = grammar · schema · mapping · transform · sink` is the editable
palette (the `ComponentType` union is wider); generic CRUD through `ComponentsService`; a History action on
all five; a `409` on delete shows "referenced by a pipeline" with **no force option** (unlike pipeline
configs). ⚠ **Only the schema editor sends `If-Match`** (the list's `contentHash`, via
`ComponentsService.update`'s `ifMatch`); the generic `component-form.dialog` and the mapping editor send none,
so grammar / mapping / transform / sink edits are last-write-wins in the product's own client (`API` §5). The schema grid's `type` column is free text validated server-side by
`SchemaFieldTypes`. ⚠ The SQL ↔ fields reconciler and the Fields | SQL views are the **pipeline editor's**
Transform pane, not Components. No `Data Source` or Node-for-Step residue remains in Catalog / Components
copy.

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### The metamodel

| Date | Decision | Why |
|---|---|---|
| 2026-06-28 | **Everything authored is a Component**; the recursive shape lives in the SPA's `component-model/`; strategy seams are **string keys**, not classes | one spine for authoring; the model library must import no Angular |
| 2026-06-28 | **The relationship graph is derived, never stored** | a stored edge is a second truth that drifts |
| 2026-06-28 | **No single generic wiring editor; no storage unification; adapters register-only; no JSON-schema validation engine** | `Wiring` is data — a graph editor ≠ a grid ≠ a channel mapper ("NiFi gets one editor only because everything there is a DAG"); an adapter over ~80 lines means the kind is not ready |
| 2026-07-06 (R1) | **One ref derivation** — `refsForComponent`, with `ComponentKind.deriveRefs` as the per-kind seam | four sites re-derived refs with different completeness |
| 2026-07-06 (R3) | `query` joins the graph with a **`binds`** edge (`widget → query → dataset`) | the projection layer is a real dependency |
| 2026-07-07 (W3) | `WRITABLE_TYPES` widened for the Studio kinds; **ETag concurrency** on `/components` | the seam that let Studio persist for real; the adoption plan's "not now" was a sequencing call, not a refusal |
| 2026-07-09 (W3b) | **Version history** in `.history/`, a sub-directory, keep-N | the registry scan must never mis-read an archived copy as a duplicate |
| 2026-07-27 | The **`schema` two-shape collision stands** — say *schema component* / *schema config* | renaming either is an on-disk and UI-union break; the Phase-1 split retires it |
| 2026-07-27 | A new kind needs **two registrations** (`WRITABLE_TYPES` + `TYPE_BY_DIR`), test-pinned | a kind writable but unscannable is invisible |
| 2026-07-31 (U-C) → **reversed 2026-08-05** | `schema` retired from `WRITABLE_TYPES` → **reinstated** by the ELT amendment as the structure-only Schema (CSV), with **Mapping** a new CSV kind | no code path had resolved a component id to a runnable schema; the amendment made Schema a first-class, mapping-free component |
| 2026-08-14 | Discarding a draft **checks dependents first** — `DELETE /config/pipeline/{name}` `409` unless `?force=true`, plus `GET …/impact`; deliberately **not** an extraction of `PipelineRenameRoutes.rewriteDependents` | a delete must not orphan what the rename logic knows about |
| 2026-08-22 (operator) | **Schema field types fail closed** — `SchemaFieldTypes` is the one vocabulary; unknown type = load error | a typo had been producing a string column silently |
| 2026-09-05 | `transform.map` **deleted**; **`fields[]` is engine-read**; `rules[]` stays readable **permanently** | the documented rule "`fields[]` is authoring-only" was reversed deliberately; deletion of the read path is out of scope |
| 2026-09-06 (operator) | In a bundle manifest **`schema` means the registry id** | one word, one meaning on the wire |

### The Catalog

| Date | Decision | Why |
|---|---|---|
| 2026-07-14 | **Source → Collector; `SOURCE` → `STREAM`**; `IdScheme` `source:` → `stream:`; a **References** read model and tab added; **no version bump** | "Source" collided with the data-origin sense; nothing had shipped on 4.x | <!-- vocab-allow: names the Source→Collector rename itself -->
| 2026-07-16 (D1–D4) | Both Stream and Reference onboard in v1; **Streams is the Catalog's default tab**; a draft is an inactive pipeline; validation is the chained tests | ask the minimum; reuse what exists |
| 2026-07-16 (D2) | ⛔ Do **not** rename the Catalog section to "Streams" | it also holds Tables / KPIs / Lineage / Usage; the glossary binds Catalog = index of Schemas and Datasets |
| 2026-07-24 | **Stream grouping**: pipelines sharing `stream:` collapse under one node; `/catalog/streams` stays per-Collector | the glossary's one-sub-system-one-Stream, without breaking the shipped grid |
| 2026-07-30 → **reversed 2026-07-31** | Onboarding ↔ Pipeline split into two planes → **unified** (U-A…U-G): one editor, canonical `*_pipeline.toon` | a second authoring plane duplicated every shape; the Dataset handoff survived |
| 2026-08-14 | A batch links to its store by **`GET /catalog/resolve?table=`, unique match only**; ⛔ no synthetic `table` backfill | a wrong lineage edge is worse than an absent one; zero and several both `404` |
| 2026-08-26 | Archive inner file → **Entry**, never Member | the container ↔ content boundary must stay sayable |
| 2026-09-06 | A blank `output_table` batch still gets a Catalog link, **resolved by pipeline** | no store edge is invented; the pipeline is a real node |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| ✅ ~~**`MAPPING-SPELLING-1`** — do the generators emit `mapping.fields[]`?~~ **Decided 2026-09-10** (emit `fields[]`, keep the `rules[]` read path) and **shipped** as `MAPPING-GEN-1` the same day | §2 note 1 | Done |
| ✅ ~~`MAP_AUTHORED` drift~~ **FIXED + PINNED 2026-09-09** — the mirror carried `fields`, and `MapNodeKeyContractTest` parsed `pipeline-editable.ts` and asserted both sets against the Java ones; ⛔ the mirror was deleted outright 2026-09-24 (dead since the mock went) | `pipeline-authoring.md` §2 | Done as a contract, not a fifth hand-fix — then retired with its subject |
| Onboarding residuals — ✅ D5-ref (`reference.delete` marker column) + D6-ref (`reference.order_by`) **SHIPPED 2026-09-25** (`okf/product/module/processor/api-onboarding-authoring.md`), their Settings-dialog UI and the onboarding template start 2026-10-11 (the onboarding row closed); enrichment/job identity by name | [pipeline-identity](../processor/spec-pipeline-identity.md) (identity-by-name accepted 2026-10-11) | Identity-by-name only — accepted, closed |
| Unification W4 (`EnrichmentService` incremental vs full recompute) and W5 (promotion-grade export; import-time referential integrity) | `BACKLOG.md` §3 | |
| ✅ Canonical-pipeline selective bundle export — SHIPPED 2026-09-25 as the `pipeline` bundle kind; `authored-pipeline` kept export-only rather than retired (option B) | [metadata-bundle](../processor/spec-metadata-bundle.md) | |
| `component_draft` / AI drafting for `grammar` / `transform` / `sink` — no low-risk slice; none has a `ConfigSpec` | `BACKLOG.md` §3 | Design first |
| `D-11` hand-authored `relations` component | `BACKLOG.md` §3 | Deferred until a business relation no Pipeline exercises |
| ✅ ~~`findings-spec` authoring UI~~ **Built** — `findings-spec-editor.dialog.ts` (`inspecto-ui/src/app/modules/admin/objects/`), `PUT` + `If-Match` once authored | — | Done |
| Catalog lifecycle — live saves hot-reload silently (banner-mitigated); no per-date retention | `BACKLOG.md` §4 *Catalog lifecycle* | |
| ✅ ~~Entity Projection **backend** + a schema-relationship model (P3)~~ **Built** — `POST /inv/projection*` and `GET /inv/schema/relationships` (`InvRoutes`, INV-1 V1) | `GLOSSARY.md` §11 (V2+ is open backlog) | Done |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-GLOSSARY-1`, `SPEC-ORPHANPAGE-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.

⛔ **Two schema-registry alternatives were considered and REFUSED, and the reasons still bind** *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*:
an **external schema-registry service** adds a running server and a second source of truth, against the
file-based, no-catalog, offline, single-node doctrines; and **a separate schema IDL** would be a third type
system needing lossy mapping both ways, when the data plane is already Parquet plus DuckDB. What was
borrowed instead is the *vocabulary and guarantees* — subjects, versions, compatibility classes — laid over
seams that already exist, the first of which is that **the store IS the registry**.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **Delete protection is a fixed list, and the widget / dashboard links are only reported by default** | `ComponentRoutes.deleteComponent` (§3.4); `LinkRefusals.delete` defaults to `shadow` (`refuse` built and ready; the flip waits for the operator, D-LC3); `ComponentIntegrity` runs only in `metadata_validate` and bundle import | Deleting a dataset a widget binds, or a widget a dashboard tiles, succeeds (with `brokenLinks` in the response) unless `-Dlinks.delete=refuse`. `MET-3` reads "delete-protection" as if it were general |
| **No `ConfigSpec` for `mapping`** (and for 29 other writable kinds) | `ConfigSpecs.TYPES` = 9, of which 4 are writable kinds | The Components pane cannot render a spec-driven form for them; `GLOSSARY.md` §13 lists it as OPEN (a) |
| **The Components pane's generic form and mapping editor send no `If-Match`** | only `schema-editor.dialog.ts` passes `ifMatch` in `inspecto-ui/src/app/modules/admin/components/` | Last-write-wins on grammar / mapping / transform / sink edits (shared with `API` §5's item) |
| ✅ **Closed 2026-11-14** (`CLASSIFICATION-UNMASKED-CLASS-1`): the field-classification vocabulary has an owner | `ClassificationVocabulary`; page: `spec-config-safety.md`, "The classification vocabulary" | was: free string, nobody could say what values are legal |
| **No concept file owns the Catalog read model** (`com.gamma.catalog`) | eight okf files mention `MetadataGraph*`; none owns it; the values live only in the glossary | `NodeKind`, `EdgeKind`, `IdScheme`, `MetadataGraphBuilder`, `CatalogOverlay` are documented by this spec alone |
| **`component-registry.md` is 4 KB for the spine of the product** and omitted `.history/`, `ComponentIntegrity`, `mapping`, `/pipelines/step-types` | the concept page | Corrected in part with this spec; a fuller concept page is owed |
| **`/catalog/streams` (per-Collector) vs the grouped `stream:` node** are two Stream read models under one word | `CatalogRoutes.java`; `MetadataGraphBuilder` grouping | The glossary binds the grouped sense; the grid shows the other |
| **`BUNDLE-SCHEMA-1` is cited as a BACKLOG §6 row by two current docs and is not there** | it exists only in the 2026-09-06 archive snapshot, marked FIXED 2026-08-31 | Corrected with this spec (`metadata-bundle.md`, `spaces.md`) |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 One generic wiring editor; one storage layer; one validation engine — REFUSED (2026-06-28)

`Wiring` is data and authoring stays per kind; persistence stays federated; validators are small hand-written
functions. The metamodel is a spine, not a framework — an adapter over ~80 lines means that kind is not ready.

### 6.2 Renaming the Catalog to "Streams" — REFUSED (product owner, 2026-07-16)

The Catalog also holds Tables, KPIs, Lineage and Usage, and the glossary binds the word. Streams became the
*default tab* instead.

### 6.3 A synthetic `table` attribute so every batch gets a Catalog link — REFUTED (2026-08-14)

"A wrong lineage edge is worse than an absent one." `resolve` is unique-match-only; zero and several both
`404`; the blank-`output_table` case resolves by pipeline instead (2026-09-06). A `/catalog/tables/…`
sub-path for resolve was refused too — the route's `(.+)` is greedy.

### 6.4 Deleting `mapping.rules[]` or the `transform.map` read path — REFUSED (2026-09-05)

`rules[]` stays readable permanently; the migration is by tool, never by breaking stored schemas.

### 6.5 The `AUTHOR-SCHEMA-1` validation-gate plan — SUPERSEDED the day it was drafted (2026-09-03)

Judged too complex; the operator chose the parse-pane redesign and the SQL-first Transform instead. Nothing
was built from it.

### 6.6 `transform.merge` config attributes — REFUSED (2026-09-07)

The node is absent from `PipelineEditable.LOWERABLE` by decision and refuses at save; attributes would give a
config pane to an unsavable node.

### 6.7 "Registry kinds have no `ConfigSpec`" as a blanket rule — REFUSED

`widget` and `dashboard` specs describe their components accurately; a blanket reroute breaks two working
kinds.

### 6.8 Holding an onboarding draft client-side to defer its name — REFUSED

A draft is server state (an inactive pipeline); "⚠ Do not implement" stands in the board.

### 6.9 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| The 2026-06-29 lock `Collector → Source`; `NodeKind.SOURCE`, `source:` tokens | Source → **Collector**, `STREAM`, `stream:`, a References read model (2026-07-14) | `GLOSSARY.md` §13 | <!-- vocab-allow: names the Source→Collector rename itself -->
| `schema` retired from `WRITABLE_TYPES` (U-C, 2026-07-31) | reinstated 2026-08-05 as the structure-only Schema component; Mapping a new CSV kind | this spec §4; `pipeline-spec.md` |
| The Onboarding ↔ Pipeline two-plane split (2026-07-30) | unification U-A…U-G (2026-07-31) | archived split plan's own header |
| The per-stage onboarding wizard routes | the pipeline editor as the guided surface; a redirect matcher | `onboarding.md` |
| Four independent ref derivations (`partsFor`, `refsOf`, mock integrity, `dashboardParts`) | `refsForComponent` (R1) | `refs.ts` |
| "`fields[]` is an authoring artifact the engine never reads" (four sites) | `fields[]` engine-read (2026-09-05) | `catalog-vs-executors.md` |
| The three-type cast `switch` with an uncast `default` | `SchemaFieldTypes`, fail-closed (2026-08-22) | `SchemaFieldTypes.java` |
| Lineage `USES`; `LineageRow` as lineage | `CONSUMES`; Provenance | `GLOSSARY.md` §11 |
| `EVENT_TABLE / TRANSFORMED_TABLE / REFERENCE_TABLE` | `Table / Derived Table / Reference Dataset` | `GLOSSARY.md` §11 |
| `GET /pipelines/node-types` as the sole palette source | ✅ true again since 2026-09-24 — `/pipelines/step-types` was served and read alongside until the Recipe view went, then retired | `GLOSSARY.md` §13 |
| The mock integrity rules as the home of widget / dashboard delete protection | deleted with the mock (2026-08-31); the server's census links (`LinkRefusals.delete`) report them, refuse only in `refuse` mode | this spec §3.4 |
| `component-registry.md`'s "`schema` is NOT a component", "precondition-failed", node-types-only palette | corrected with this spec | `component-registry.md` |
| `config.md`'s "the `FieldSpec → AttributeSpec` port is still open" | shipped — the Config pane is on `<inspecto-schema-form>` (`frontend/log.md`) | `config.md` |
| `STAKEHOLDER_OVERVIEW.md`'s "authored CRUD" pipelines surface, "Issue & case management", `open → assigned → …` | retired / renamed; plan §8 cluster, reconciled at step 6 | — |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| `ComponentStore`, the kinds, routes, ETags, preview, safe delete | `docs/okf/product/module/processor/spec-component-registry.md` (`Concept`) | `ComponentStore.java` | §3.1–§3.4 — ⚠ 4 KB; corrected with this spec, still owed a fuller page |
| The `use:` seam, `bindKindFor`, the palette contract, `transform.sql` internals | `docs/okf/product/module/elt/spec-pipeline.md` · `node-types.md` (`Concept`) | `inspecto-engine` | how a kind is bound and executed |
| The TOON key reference incl. the schema config and its `mapping:` block | `docs/okf/product/module/config/admin-guide-configuration-reference.md` (`Reference`) | — | §3.7 — read with §2 note 1 |
| `schema_file` / `grammar` / `mapping` resolution and the jail | `docs/okf/product/module/config/spec-config-safety.md` (`Concept`) | `platform/inspecto-config/` | §3.7 |
| Draft lifecycle, previews, the register pair, Reference production | `docs/okf/product/module/processor/api-onboarding-authoring.md` (`Seam`) | — | §3.8 |
| Bundle refs, `APPLY_ORDER`, `ComponentIntegrity` in the import pre-check | `docs/okf/product/module/processor/spec-metadata-bundle.md` (`Concept`) | `BundleRoutes.java` | §3.4 |
| The layer table naming `catalog` as L2 | `docs/okf/product/layer/platform/architecture.md` (`Architecture`) | — | the one structural line on the read model |
| Catalog tabs, resolve, the Data Browser | `docs/okf/product/layer/product/user-guide-catalog.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/catalog` | §3.9 |
| Onboarding as the editor's checklist; stream bundle import | `docs/okf/product/layer/product/user-guide-onboarding.md` (`Feature`) | — | §3.8 |
| The schema / mapping grids, the compatibility gate's UI | `docs/okf/product/module/elt/user-guide.md` (`Feature`) | — | §3.7 |
| The Components pane | `docs/okf/product/layer/product/spec-components.md` (`Feature`, 0.9 KB) | — | §3.9 — ⚠ a stub |
| 🔴 **No concept file owns the Catalog read model** (`com.gamma.catalog`: `MetadataGraph*`, `NodeKind`, `EdgeKind`, `IdScheme`, `CatalogOverlay`) | *(gap)* | `platform/inspecto-engine/src/main/java/com/gamma/catalog` | §3.5–§3.6 of this spec are the only account |
| 🔴 **No page owns `SchemaFieldTypes`** (the classification vocabulary is owned by `spec-config-safety.md` since 2026-11-14) | *(gap)* | `platform/inspecto-etl/src/main/java/com/gamma/etl/SchemaFieldTypes.java` | §3.7 |

---

## 8. Verification

### 8.1 Store, registry, refs — `inspecto-engine` (default reactor)

| Class | Proves |
|---|---|
| `ComponentStoreTest` | CRUD round trip, `.history/` archive and keep-N, CSV kinds, **`everyWritableTypeHasARegistryDir`** |
| `ComponentRegistryTest` | scan, `dirForType`, `TYPE_BY_DIR` |
| `PipelineReferencesTest` | the delete-protection `use:` scan |
| `CatalogModelTest` · `CatalogOverlayTest` · `MetadataGraphServiceTest` · `DescriptionProviderTest` | graph build, traverse, overlay hydration, the description SPI |
| `MappingComponentTest` · `MappingCsvDualReadTest` · `SchemaAccessTest` | the CSV kind, dual-read of `rules[]` / `fields[]`, schema access |
| `BindKindHomeContractTest` | `bindKindFor` (TS, category-keyed) ↔ `PipelineEditable.USE_HOME` (Java, type-keyed) via `bind-kinds.contract.json` |

### 8.2 Routes — `inspecto/src/test/java/com/gamma/control/`

`ControlApiComponentsTest` (CRUD, `409` create-exists, ETag / `If-Match` 409, versions, restore, safe delete),
`ControlApiMetadataV1Test`, `ControlApiBundleImportTest` · `ControlApiBundleNewKindsTest` (the integrity
pre-check, `schema` as a bundle kind), `ControlApiPipelineCrudTest` (dependents `409` / `?force`, `impact`),
`CollectorServiceCatalogTest` (streams / references / resolve), `ContentHashTest` (the SPA parity vectors).
About 43 test classes reference the store, registry, catalog or schema generator.

### 8.3 Schema generation and types — `inspecto-util` · `inspecto-etl`

`StructureCsvTest` (generation, classification column, merge), `PipelineValidatorTest`
and `PipelineDryRunTest` (the fail-closed field-type refusal at load), `RecordTransformContractTest`
(`sql-functions.contract.json` — both sides compile SQL).

### 8.4 UI specs — vitest (15 files, ~137 cases)

`catalog.component.spec.ts` (tabs, onboarding CTA), `catalog-graph.spec.ts` (per-kind colour/shape/label completeness over `CATALOG_NODE_KINDS`, legend),
`graph-view.component.spec.ts`, `node-detail.dialog.spec.ts`, `onboard-redirect.spec.ts`,
`onboarding-create.dialog.spec.ts`, `platform-kinds.spec.ts`, `registry.component.spec.ts`,
`sharing.component.spec.ts`, `store-lineage.component.spec.ts`, `component-form.dialog.spec.ts`,
`components.component.spec.ts`, `components-data-provider.spec.ts`, `mapping-editor.dialog.spec.ts`
(rules grid + validate), `schema-editor.dialog.spec.ts` (fields grid + `422` findings); plus the cross-language
`attribute-spec`, `bind-kinds`, `node-attributes` contract specs (`step-types` retired 2026-09-24).

### 8.5 Committed artifacts

Registry components under `spaces/*/config/registry/`: dashboards 2 · datasets 3 · decision-rules 1 ·
expectations 3 · geo-map-views 1 · grammars 1 · link-analysis-views 1 · mappings 1 · pattern-packs 3 ·
queries 1 · reconciliations 1 · requirements 1 · sinks 1 · transforms 1 · widgets 2 — **and zero
`registry/schemas/`**. Pipeline-owned `*_schema.toon`: 201 tracked (counted 2026-11-15), **all carrying the
inline `mapping:` block as `mapping.fields[]`, none on `rules[]`**.

### 8.6 Guards

`ComponentStoreTest.everyWritableTypeHasARegistryDir` (the two-registration rule); the four cross-language
contract pairs of §8.4 (a vocabulary that moves on one side fails the other); `check-vocabulary` keeps *Data
Source* and Node-for-Step out of the docs; `SchemaFieldTypes` at config load is the runtime guard on every <!-- vocab-allow: names the Source→Collector rename itself -->
declared type.

### 8.7 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **No test that deleting a widget a dashboard tiles is refused by default** — because it is not (census `shadow`) | §3.4 |
| **No test pins `NodeKind` / `IdScheme` / `EdgeKind` values** as a contract; the SPA's union is open and names kinds the server never emits | `models.ts` vs `NodeKind.java` |
| **No dedicated `ComponentIntegrityTest`** — coverage rides the bundle-import tests | §8.2 |
| ~~**No test of a migrated `fields[]` schema in a committed Space**~~ — moot: every committed schema is on `fields[]` | §8.5 |
| ✅ Closed 2026-11-14: `ClassificationVocabularyTest`, `ControlApiClassificationVocabularyTest` | §3.7 |
| **No client-side `If-Match` test on the generic component form or the mapping editor** — neither sends it (the schema editor does) | §3.9 |
