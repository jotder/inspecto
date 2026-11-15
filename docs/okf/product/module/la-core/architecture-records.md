---
type: Reference
title: Link Analysis — as-built reference and design records
description: The engineer-facing as-built reference for Link Analysis that the architecture pages do not carry - grounded limits and their consequences, Graph Run, the link index with its measurements, external references and the Dossier bundle, and the durable decisions of the separation (D-1, D-3, D-5, D-6) and closed-plan records.
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — as-built reference and design records

For engineers, support and power users. Each fact is dated where it was built. Where this page and the
[user guide](user-guide.md) differ on a control's name, the user guide (today's Studio) is right; the dated records
describe the behaviour, not the current layout. Module structure, op log, store port, index layout, Graph Run
budgets, route table, editions and the limits catalogue are in [architecture.md](architecture.md); gates, embed
links and the Investigation UI are in [architecture-http-and-ui.md](architecture-http-and-ui.md). This page keeps what
only it says: measurements with their numbers, decisions with their reasons, limits with their consequences.

What is where:

- [Product, code layout and the studio](#product-code-layout-and-the-studio) — naming, where the code lives, the algorithm library, V2 decisions
- [Pattern packs](#pattern-packs) · [AI-derived projection mappings](#ai-derived-projection-mappings) · [Dense-graph link labels](#dense-graph-link-labels)
- [Grounded limits and consequences](#grounded-limits-and-consequences) — limits, canvas, patterns, Value Measures, Cases, entity ids, evidence, Investigation surfaces
- [Graph Run](#graph-run) — server-side graph analysis
- [Studio UI and accessibility record](#studio-ui-and-accessibility-record)
- [Index](#index) · [Index design record](#index-design-record) · [Index scale measurements](#index-scale-measurements)
- [External references and the Dossier bundle](#external-references-and-the-dossier-bundle) — with members and Drafts
- [SPA separation record (D-5)](#spa-separation-record-d-5) · [Integration design record (D-6)](#integration-design-record-d-6) · [Platform-layer record (D-1)](#platform-layer-record-d-1)
- [Closed-plan record](#closed-plan-record) — op vocabulary, threshold / snapshot / temporal / compare, timeline playback, decisions of record
- [InvestigationStore port](#investigationstore-port) · [Consolidated as-built facts](#consolidated-as-built-facts)

---

## Product, code layout and the studio

* **Product vs capability (`ED-1`, 2026-10-17).** The product is **Inspecto LA (Link Analysis)**: the standalone `la-app`
  (browser tab, rail brand, landing heading, sign-in screen) says *Inspecto LA*. The capability keeps the name **Link
  Analysis** everywhere else: console nav, the app's own *Link Analysis* area, routes, keys and modules (GLOSSARY §11).
  The sign-in screen is the core's, shared with the console; it reads `AppEnvironment.productName`, which each host sets
  from its Product's `brand` (`products/<id>.toon` → the host's `product.generated.ts`).
* **Editions.** Routes live in `inspecto-geo-link`, in every edition since LA-EDITIONS-1 phase 3; placement, the
  `investigations` feature and the add-on: [architecture.md](architecture.md) §8. SPA side: `LA_FEATURES.investigations`
  (`SessionService.investigationsEnabled`, `/bootstrap` `features.investigations`) hides the Investigation tab, buttons,
  deep-link openers, Graph Run controls and the `/open` page's actions; `SessionService.geoLinkEnabled` mirrors
  `features.geoLink` and hides the nav entry and the Menu-Builder widget offer; a deep link that still reaches the page
  gets the core stub's 503, rendered as an edition message. ⚠ The flag is derived server-side from what registered,
  never from the edition string.
* **Where the code lives (D-5 steps 3-4, 2026-10-02).** In-workspace library `inspecto-ui/projects/link-analysis/src/`
  (folders `link-analysis`, `geo-map`, `graph`, `geo`, `investigation`, `la-host`, `api`), imported as
  `@inspecto/link-analysis` (only the `la-host` seam and the two viz registrations, `public-api.ts`) or by deep path for
  lazy routes; it reaches shared code as `@inspecto/core/*` (= `src/app/inspecto`). The canvas, `graph-types`,
  `graph-source`, `entity-key`, `graph-export` and `unique-name` stay in core. ESLint enforces core ← library ← shell; the
  Java parity tests read their fixtures from the library. Decisions: [SPA separation record](#spa-separation-record-d-5).
* **The second shell, `la-app` (D-5 step 6, 2026-10-02).** `inspecto-ui/projects/la-app/src` (`ng build la-app` →
  `dist/la-app/browser`; `gamma` unchanged): a LEFT rail (Link Analysis / Geo / Entity Lists / Alerts, Space switcher,
  user menu; 64px icons by default, 224px with labels, remembered in `localStorage` `la-app.rail-expanded`; under `md`
  (960px) a 3rem top bar; no header or footer, the footer text is the brand tooltip). The shell sets
  `--shell-chrome-height` (`0rem` beside the rail, `3rem` as a top bar), read by the Link Analysis root height formula
  (measured 2026-10-10: 1440x900 canvas 722x772 slim / 562x772 expanded; 1280x720 868x592). **Alerts (2026-10-12):**
  `/alerts` lazy-loads the console's `AlertsComponent` whole (ungated like the console) and provides
  `ALERTS_INVESTIGATION_ROUTE` = `/link-analysis` so *Open Investigation* stays in la-app (default
  `/studio/link-analysis`). Sign-in is the core's OIDC code (`inspecto/auth`). The shell answers the ten `la-host` tokens
  (`la-host.providers.ts`, list pinned by spec): six real, four stubs (table in the
  [SPA separation record](#spa-separation-record-d-5)). Entity Lists has its own page, which provides the empty
  `InvestigationSessionStore`. `/settings` hosts Settings > Link Analysis (see
  [Studio UI](#studio-ui-and-accessibility-record)); the la-showcase `admin` persona holds `canAuthorWorkbench` through
  the template role `la-settings`. Bundle choice (`package.ps1 -Product`): `okf/product/layer/platform/spec-build-test.md`.
* **The studio** (`/studio/link-analysis`, Builder lens) works on graph plane **P3 — Entity/Link graphs** (records as
  business entities), never on artifact/lineage graphs ([GLOSSARY](../../../../GLOSSARY.md) §11).
  * *Graph sources:* a **GraphSource** feeds one renderer through one query seam; the P3 source is **Entity
    Projection**, a mapping (not a store) that folds a Dataset's rows into Entities + Links.
  * *Rendering:* the shared G6 host (`src/app/inspecto/graph/`), reused by the Catalog graph and the Geo co-location
    bridge. Nodes are canvas-drawn — verify inspector logic in unit tests, not preview clicks.
  * *Algorithm library* (V2 depth, 2026-07-24): the pure `projects/link-analysis/src/graph/graph-analysis.ts` (extension
    seam: a new algorithm is a pure `(g: G6GraphData, …) ⇒ result`), surfaced as toolbox groups — *Advanced traversal*
    (`weightedShortestPath` by tie strength, `edgeWeight` = folded count; `findCycles`; Tarjan `articulationPoints` /
    `bridges`; `egoNetwork`) · *Algorithm library* (`pageRank`, closeness / eigenvector / Katz, `hits`, `kCore`,
    `triangleCount`, `cliques` Bron–Kerbosch, `maxFlow` + min-cut Edmonds–Karp, `maximumSpanningForest`,
    `jaccardSimilarity`, `linkPrediction`) · *Communities* (`detectCommunities`, toggle label propagation or
    `louvainCommunities`) · *Suspicion scoring* (`suspicionScore`, explainable 0–100 blend of degree / betweenness /
    PageRank / k-core / triangles with a per-node breakdown; top decile highlighted) · *Pattern packs*. 14 G6 layouts,
    tree shapes gated to acyclic data. Super-linear algorithms are guarded by the analysis cap.
  * *Saved investigations:* a **Link-Analysis View** (Component kind `link-analysis-view`, `projects/link-analysis/src/investigation/`);
    with source `entity-projection` it is renderable as a **Widget** (a Graph Visualization Type bound to a Dataset).
  * *Shipped slices:* backend Entity Projection over real Datasets (REQUIREMENTS INV-1, `POST /inv/projection`) with the
    V1 slice (multi-mapping, multi-root, incremental expand, SVG/GraphML export, undo/redo, `attrCols` in `InvRoutes` and
    `entity-projection.ts`). **2026-07-20** the schema-relationship model: `GET /inv/schema/relationships` infers FK
    suggestions (`<base>_id` → a Dataset named `<base>`, its `id` or a same-named column; self-references included;
    unusable Datasets skipped). **2026-07-24** the four V2 tracks above; the **timeline** (`filterByTime(g, attrCol,
    cutoff)`: edges only, an edge survives when `attrs[attrCol]` parses as a date on or before the cutoff; slotted
    kind-filter → time-filter → `collapseBranches` → `displayed()`; resets on a fresh query, undoable); **version
    history** (per-view submenu opening `ComponentHistoryDialog` with `{type:'link-analysis-view', id, label}`, works
    because `link-analysis-view` is a `ComponentStore` WRITABLE_TYPE). **2026-07-26** V2 (b) sharing (below), V2 (d)'s
    vocabulary half (`<inspecto-ai-explain screen="Link Analysis">` declares Entity · Link · Entity Projection ·
    Link-Analysis View · Dataset · Widget) and V2 (c) per-Space pattern packs. **2026-07-27** V2 (d)'s authoring half —
    V2 complete.
* **Investigation pivot** (2026-07-20): a node resolving an `objectRef` offers "View on map"; shared contract in the
  [user guide](user-guide.md).
* **Geo scene of the `la-showcase` demo** (2026-10-12; SYNTHETIC, cities only). `mule_account_locations` (113 accounts,
  city centroid + deterministic jitter, `SITE_TYPE`) and `mule_geo_legs` (planted legs, both ends geocoded); Datasets
  `mule_account_locations_dataset`, `mule_ring_legs_dataset`, `mule_cashout_legs_dataset`; Geo Views
  `mule_account_locations_map` (points), `mule_ring_route_map`, `mule_cashout_till_map` (od-routes). Story: small-town
  deposits → MULE-HUB-01 (Rotterdam) → RELAY-01/02 (Madrid, Warsaw) → OFFSHORE-77; SHELL-A..D circle four cities; cash-out
  MULE-HUB-02 (Marseille) → eight CASHER accounts → TILL-06 (Paris). ⚠ Why a pre-joined legs file: `POST /geo/routes`
  reads both coordinates from ONE relation and a virtual Dataset reads ONE store (`DatasetRelation.virtualRelationSql`),
  so the virtual Datasets filter `mule_geo_legs` by `SCENE`. The generator draws the geo scene from its OWN random
  generator, so transfer files stay byte-identical (`gen-la-showcase-data.test.mjs`); pinned by
  `ControlApiLaShowcaseGeoSceneTest`, `LaShowcaseTemplateTest`. Not verified in a browser: the map render.
* **Geo ↔ Link brushing** (LA-22, 2026-09-23): root `GeoLinkBrushService` (`geo-link-brush.ts`) holds the last
  selection; a polygon / point on the map publishes `GeoPoint.key`s, Link Analysis highlights the projected nodes
  (`[emphasis]="emphasis() ?? geoBrushEmphasis()"`), and a node click publishes back. The join goes **only** through
  `entityId()` with the last run's mapping `entityType`s, so D-S4 normalisation applies. ⛔ Never by label or `pt:<i>`.
  It brushes across routes (root-scoped); split-pane mode and graph-path → map-route tracing are deferred.
* **V2 decisions of record (2026-07-25, BACKLOG D9 / D10 / D16)** — all three generalised an existing seam:
  * **D9 sharing — shipped 2026-07-26.** The Exchange `kind` axis carries `link-analysis-view`; as-built is
    [exchange spec](../exchange/spec.md) (authoritative). LA side: "Offer for sharing" in the per-view menu, gated on
    `exchangeEnabled() && canOfferDatasets()` and source `entity-projection`. ⚠ A view's Datasets are its projection
    mappings (`query.projections[].datasetId`, else `query.projection.datasetId`), *not* `query.roots`/`query.from`
    (the lineage shape) — reading roots/from 422'd every shipped view while hand-written tests passed.
  * **D10 per-view comments — generalise the note model, shipped 2026-07-25.** `ObjectNote` carries **`targetKind`** —
    ⚠ not its `kind` (`NoteKind` COMMENT/ATTACHMENT, an orthogonal axis). Vocabulary `AnnotationKinds` = `"object"` +
    `ComponentStore.WRITABLE_TYPES` (the same strings as `BundleRoutes.OWN_STORE_KINDS` and the Exchange; D7 inherits
    it). Routes `GET/POST /notes/{targetKind}/{targetId}/comments|attachments`; `/objects/{id}/comments|attachments`
    untouched. Invariants: one gate serves reads and writes (`NoteRoutes.targetGate` is the `TargetResolver`); `object`
    reuses `ObjectRoutes`' SEC-7d + `RowScope` check (404 out of scope); component kinds gate on
    `ComponentAccess.requireView` — commenting writes nothing under `registry/`, so a view-only sharee may comment.
    Migration: `ALTER TABLE ADD COLUMN IF NOT EXISTS` + backfill `'object'` (the ACQ-7 precedent). Residuals: `objectId`
    not renamed (`targetId()` alias); `GET /notes/object/{absent}` 404s while `/objects/{absent}/comments` returns `200 []`;
    **notes are not deleted with their component**, so re-creating an id resurrects the thread. UI: "Comments" beside
    "Version history" opens `LinkAnalysisCommentsDialog` over `NotesService`. Rejected: a narrow re-key by component
    `type`+`id` (a third caller would become a third special case).
  * **D16 pattern packs** — a dedicated system Space was OVERTURNED 2026-07-26 (operator: per-Space forking is
    acceptable); shipped as a component kind, below.

## Pattern packs

*V2 (c), shipped 2026-07-26.* A **pattern pack** is a named starter motif that pre-fills the pattern-match builder: a
per-Space **`pattern-pack` `ComponentStore` kind** at `spaces/<space>/config/registry/pattern-packs/*.toon`, read over
`GET /components/pattern-pack`. Six are seeded in each tracked Space. Packs whose shape is not a path motif hint at the
fitter tool (cycles / similarity).

**Save as pattern pack** (`LA-PRODUCT-NEXT-1` phase 5b, 2026-10-30; `save-pattern-pack.dialog.ts`) saves the builder's
motif (steps or branching stages) as a new pack: name, category (money, telecom, identity), description →
`POST /components/pattern-pack`; it joins the picker at once.
- Button only for `canAuthorWorkbench` (`pattern-pack` is not in `ImportCapabilityGuard.KIND_CAPABILITY`, so the generic
  door's capability applies). With no `shares` the pack is visible to the whole Space.
- Id = the name in lower-case words joined by `-`; an id already in the picker is refused before the write (an authored
  pack with a built-in's id would silently replace it); the server's 409 covers a race.
- Content `patternPackToContent`: every row carries every column, blank where absent, so TOON writes a tabular array;
  read back unchanged by `patternPackFromContent`.
- Under maker-checker (a policy for `pattern-pack`, or an unreadable policy file) the write is held (202), `heldMessage`
  says so, and the pack appears only once approved.
- A band with both bounds cleared is saved as no band (the reader refuses a bound-less band and would drop the pack).
- Not built: overwrite / edit from here (use the component editor or version history).

As-built facts:
* **No new endpoint or capability:** two registrations (`ComponentStore.WRITABLE_TYPES`, `ComponentRegistry.TYPE_BY_DIR`);
  writes ride `canAuthorWorkbench`; version history, ETags, share filtering, `AnnotationKinds`, `InspectoTools`,
  `BundleRoutes.supported()` came free.
* ⚠ **A kind absent from `WRITABLE_TYPES` is UNREADABLE, not read-only** (`list`/`get` call `validateType`); there is no
  read-only-kind concept, which is why packs are writable over HTTP.
* ⚠ **TOON cannot encode `{}` as a list element** and a motif's step 0 is exactly that (`ConfigCodec.toToon` writes a bare
  `-` and fails to decode it). Every step carries a `direction`, the start node's the **empty string**, mapped blank →
  `undefined`. **Do not tidy the blank away.** Pinned by
  `ComponentStoreTest.patternPackStepsSurviveTheRoundTripWithABlankStartDirection`.
* **The `PATTERN_PACKS` const stays the fallback** (no packs, error, no write root): the toolbox seeds its signal with it
  and replaces it only on a non-empty response. Authored packs merge OVER built-ins by id.
* ⚠ `patternPacks` must be a signal (the toolbox is `OnPush`); the injected service is `componentsApi` (`components` is
  already the connected-components signal).
* Content is free-form TOON with no backend `validateKind` branch; the defensive UI mapper skips a malformed pack.
* ⚠ `spaces/uat/` is gitignored and unseeded, so uat runs on the const fallback; adding files there cannot be committed.
* **Why not a reserved system Space** (do not re-propose blind): a `_`-prefixed dir with `config/` passes
  `SpaceManager.discover` then dies in `SpaceBootstrap.load` at `SpaceId.of`, logging a `Skipping space dir` WARN on every
  boot; without `config/` it is unreachable through `/spaces/{id}/…` and would need a cross-space read route.
* Temporal ordering and branching packs: see [Patterns](#patterns-temporal-order-branching-and-the-server-half).

## AI-derived projection mappings

*V2 (d) authoring half, shipped 2026-07-27.* The query panel's Entity/Link tab has **Derive mapping**
(`<inspecto-ai-assist>`, 5th adopter): proposes source, target, edge label and node-attribute columns via the
non-mutating `projection_author` tool over `POST /agent/tools/{name}`. Applying patches the form **dirty and stops** —
the operator still presses Run and Save.

* **Deterministic, not a model call.** Ordered `ENDPOINT_PAIRS` (`caller/callee` … `from/to`), then the first two
  `*_id`, then **refusal** (`clean=false`, finding at `projections.0.sourceCol`) — an arbitrary pair would look authored
  and be wrong. `hint` narrows the candidate set (not a prompt; matching fewer than two columns is ignored with a
  WARNING). NL is AGT-6a A5's job; this tool is its `derive` target.
* ⚠ **The pane passes the column list in.** No agent tool or tool-layer route returns a Dataset's columns
  (`InvRoutes.schemaRelationships` builds `columnsByDataset` and throws it away); passing it dodges the
  `-Dassist.write.root` dependency that makes `query_author` hard-error without a write root.
* ⚠ **`entityType` is left unset**: on a single mapping it changes ids from `entity:<v>` to `entity:<type>:<v>` (breaking
  saved views and exports), while `buildQuery` requires it on every mapping once extras exist; a future multi-mapping
  drafter must set it on all.
* ⚠ The draft is `query.projections[]`, never `roots`/`from`.
* **Why not `component_draft` / `query_author`:** `component_draft` echoes config with findings and resolves `kind` through
  `ConfigSpecs.TYPES`, which has no `link-analysis-view`; `query_author` emits a Query. No `link-analysis-view` ConfigSpec
  was added (validation, not authoring).
* Fixed on the way: `patchFormFromView` ignored `projections[]` (a saved multi-mapping view loaded first-only); saved-view
  and draft paths now share `patchFormFromQuery`, rebuilding extras and patching `attrCols`/`entityType`.
* ⚠ Adopting the assist surface needs `provideHttpClient()` + a `ToastrService` stub in the panel spec
  (`<inspecto-ai-assist>` injects `AgentService`).
* ⚠ Offline the columns come from `SAMPLE_SOURCES` — never evidence the real path works (offline: `money_moves` →
  `from_city → to_city` + 7 attributes, 7 nodes / 11 links; `cdr_sample` correctly refuses).
* ⚠ `AiToolName` and `adaptToolResult` are a pair: missing from either renders "no suggestion" with no error. The tool
  count in `InspectoPackTest` is hard-coded (21 → 22).

## Dense-graph link labels

*As built 2026-09-28.* The shared `GraphViewComponent` hides link labels above `DENSE_EDGE_LABEL_CAP = 20` links
(`edgeLabelsHiddenByDensity`) and reveals them through a custom G6 `labelled` edge state — hover of the link or an
endpoint node, pinned by a click until the next click; own pointer events (not `hover-activate`), so it also works in the
menu-leaf `link-view-widget`; other states preserved. ≤ 20 links keep labels; node labels untouched; the legend names
every link type.
- **Why 20:** a 9px label is ~60px wide; past ~20 links on an auto-fitted canvas they collide.
- **Override:** *All link labels* (Display menu and View toolbox; disabled while *Link labels* is off), saved as
  `display.allEdgeLabels: true` (omitted when off); undo/redo snapshots it. Covered by vitest only.
- **Large-graph detail above 1,000 links** (`LA-PERF-N4-1`, 2026-11-13: arrowheads and node labels, state `detailed`,
  override `display.allDetail`) with its measurements: [spec.md](spec.md) (performance seams).

## Grounded limits and consequences

*Code read 2026-09-20 / 2026-09-22, updated through 2026-11-14.* Durable facts distilled from the archived
`link-analysis-spec.md`; open work is on `docs/BACKLOG.md` §3.12.

### Limits in force

**Every limit is configuration (`LA-CONFIG-LIMITS-1`, 2026-10-15)** — catalogue, layers, routes and hot apply:
[architecture.md](architecture.md) §9. SPA side: `LaLimitsService` (`la-limits.service.ts`) loads
`GET /settings/link-analysis/limits` per Space into the `laLimit(key)` store (`la-limits.ts`); code reads `laLimit(key)`
(the analyst's own value for `browser:true` items, else the Space value). `LA_LIMIT_FALLBACKS` is the ONE fallback table
(before the GET answers, after it fails, or in the embed viewer), pinned by `LaLimitCatalogueParityTest`. Personal values:
`localStorage` `inspecto.la.limits.personal.<space>.<actor>`.

* **Defaults now:** canvas `projection_node_cap` 1,000; `analysis_node_cap` 2,500 (selection algorithms hand over to the
  server above it, the branching matcher refuses); `suspicion_node_cap` 1,000, the ONE value browser and server share
  (suspicion computes betweenness without betweenness's guard, so a 501–1,000 node run is not refused in betweenness's
  words); per-algorithm `browser_algorithms.<id>` = min(2,500, the server inline ceiling) — betweenness 500; closeness,
  cliques, max flow, link prediction, eigenvector, Katz, HITS 1,000; Louvain, label propagation 2,500. Refusals name the
  algorithm and the cap. Betweenness's 500 is where EXACT ends (`LA-PERF-P5-1`, 2026-11-13): above it the ranking is
  sampled and marked "Approximate", up to the analysis cap. Display caps (`display.*`: LOD labels 300, layered 30, radial
  100, information density 300, clustering force 200, embed 80 / 3,000, geo 5,000) are per-analyst; `/geo`'s default
  limit is `display.geo_point_cap`.
* **Connect** sends `connect.default_hops` (3); `connect.max_hops` (6) is only the ceiling the analyst may pick.
* `MAX_CASE_MEMBERS` (100) stays an SPA constant (server `CaseRoutes.MAX_CASE_ENTITIES`). The dashboard link and geo
  widgets: [architecture.md](architecture.md) §9 *SPA loading*.

| Limit | Default | Where | At the edge |
|---|---|---|---|
| `projection_node_cap` | 1,000 | `projectionNodeCapValue()` (`entity-projection.ts`) | truncates; the 4,000-row read cap often binds first (465–767 nodes) and the notice says which cap cut it |
| `analysis_node_cap` | 2,500 | `analysisNodeCapValue()` | ⚠ throws on super-linear algorithms (caught, see below) |
| `traversal.rows_default` / `rows_max` | 4,000 / 40,000 rows | `LaLimit.java` | clamps, `truncated: true` |
| Render | none | `graph-view.component.ts` | no virtualisation, culling or WebGL (WebGL measured and refused: [spec.md](spec.md)) |

* **Measured-defaults caveat:** the shipped caps came from one host, one browser, one synthetic shape; a denser graph or
  slower laptop moves them.
* ⛔ **A persisted limit is refused, not clamped** (422 naming the key and the range): an operator typed it and deserves
  to be told; a silent clamp is for per-request parameters only.
* ⛔ **Client setters fail closed:** a cap that is not a finite integer ≥ 1 is ignored and the previous value stands (a
  `0` or `NaN` cap would switch the whole toolbox off). Pinned by a mutation-checked spec.
* ⚠ **The graph modules are pure libraries with no DI**, so limits are PUSHED in (`configureGraphLimits`,
  `configureProjectionLimits`) and enforcement sites read the live value. 🔴 A Space change RESETS to defaults before
  applying the new Space's values (else an absent field would keep the previous Space's override).
* ⛔ **The analysis-cap refusal is graceful:** `requireUnderCap` throws, all ten sites catch it, clear the stale result
  and render a warning naming the algorithm, cap and size; converting them to an outcome value was refused as churn.

### Projection, safety and the canvas

* **An edge is a folded aggregate** (`GROUP BY`, carrying `count`), nodes are implied endpoints, and nothing is persisted
  server-side — every call re-aggregates. 🔴 **A saved view is not evidence**: reopened after the Dataset changed it
  silently shows a different graph (see *saved views* below).
* **Server algorithms are all reachable** (`LA-ALGO-UI-PARITY-1`, DR-U11/U12): *All algorithms (server)*
  (`link-analysis-server-algorithms.component.ts`) builds its form from the catalogue's parameter descriptors, reuses
  `LinkAnalysisServerRunComponent` and reads answers by result kind (`graph-run-summary.ts`). Every completed answer goes
  to `applyCatalogueResult` BY KIND: `SCORES`/`HITS` → the ranking; `COMMUNITIES` and connected components' disjoint
  `GROUPS` → communities; cliques (overlapping) stay summary-only. The browser runs 21 algorithms with local code on the
  main thread; the backend's projection does SQL fold and filter only (deliberate), so the browser path is bounded by
  one tab.
* **Safety is narrowness:** identifiers match `SAFE_IDENT`, values bind as JDBC `?`; 503 without a write root, 404
  unknown Dataset, 422 bad identifier.
* **The canvas is not a rebuild** (LA-05). `GraphViewComponent.ngOnChanges` does the smallest thing: layout / plugin /
  tooltip / fill change recreates; data change is applied onto the live graph; cosmetic change (emphasis, display, theme)
  repaints without layout. Comparisons are by VALUE (`stableKey`) because every input is a fresh `computed()` reference.
  Measured on 170 nodes: toggling node labels keeps the G6 instance and moves 0 of 170 nodes. `displayOptions` and
  `canvasPlugins` carry `equal:` comparators.
* 🔴 **`graph.draw()` does not repaint** in G6 v5: it re-renders held specs without re-running style mappers. A repaint is
  `setData(this.data)` then `draw()` (mappers re-run, layout does not). Found by measurement; jsdom cannot see it.
* ⚠ **A `labelText` mapper returning `undefined` does not clear a label** (G6 reads "no change"); use `label: boolean`.
* **Two-stage filter, stage 2 live since LA-01:** `POST /inv/projection` and `/neighbors` take an optional `filter` (the
  `query-types.ts` condition tree), rendered by `ConditionSql` and AND-ed into the `WHERE` **ahead of the `GROUP BY`**, so
  `count` folds filtered rows. Every leaf `field` is checked against the relation's columns (unknown → 422
  `CONFIG_VALIDATION_FAILED`; D-S5, 2026-09-22); cost one zero-row probe per filtered call. 🔴 **The `filter` root must be
  a group** (2026-09-23; `/projection`, `/neighbors`, `/projection/multi` top-level and per-edge,
  `/traversal/recursive-paths`): a bare condition used to render `TRUE` and return every row (fail-open); now 422. An
  empty group and incomplete leaves are a deliberate no-op ([decision rules](../processor/spec-decision-rules.md)).
* **Audit** (LA-04): `link.projected`, `link.expanded`, `link.schema.inspected`, `geo.projected`, `geo.routes.projected`
  carry dataset, result size and `truncated`, best-effort (an audit failure never fails the query). Exclusion and reveal
  are audited server ops; only the browser-local canvas export emits nothing.
* **14 layouts and 8 canvas plugins**, each a G6 v5 built-in id. LA-09 added `fruchterman`, `dendrogram` and `fishbone`
  (same tree/forest gate), the `snapline` lens, and `bubble-sets`, which draws the same Louvain communities as the hull
  overlay — **mutually exclusive by construction** (bubble sets win).
* ⚠ **Refused on grounding (LA-09), with reasons:** `timebar` (needs `data` + `getTime`, duplicates the time column
  control); `history` (🔴 a second undo stack on Ctrl/Cmd+Z beside `undoPresentation`/`redoPresentation`);
  `contextmenu` (empty without a decided action set); `watermark` (content is a decision); `combo-force` is not a G6 v5
  id (nearest `combo-combined`); combos are a data-model change (`comboId`), not a plugin.
* **Super-node stand-ins** (LA-06): opt-in, off by default, labelled with the count; only nodes whose ONLY link is to the
  hub fold, so no path is hidden. Clicking opens it; toggling the control is the way back. 🔴 **A stand-in is not a
  record and never carries an `objectRef`**; it has its own `kind`, excluded from legend and tallies, and working-set
  tiles are measured before aggregation (demo: 159 marks drawn, 170 entities reported).
* ⛔ **Viewport culling and progressive edge loading REFUSED as premature** (the cap was 500 then, the edge array is
  weight-ordered, the targeted bottleneck was the pre-LA-05 rebuild). 🔴 Culling by filtering `data` would re-run a full
  layout on every pan. Cheap starting points if the cap rises: G6 `optimize-viewport-transform`, `auto-adapt-label`.
* 🔴 **An expand used to drop `truncated`** (LA-02: `mergeGraphs` returns bare `G6GraphData`); `expandNode` now carries
  it monotonically — only a fresh `run()` resets it.

### Settings documents

* **`PUT /settings/link-analysis` replaces the document** (decided 2026-10-12): a key absent from the body is written
  `null` = inherit the shipped default (never unbounded) — except limit keys, which since 2026-10-15 are KEPT when the
  body leaves them out (`SettingsRoutes.keepUnsentLimits`). The SPA sends every stated key back as read.
* **Settings ▸ Link Analysis pane** (`settings/link-analysis-settings.component`): four-eyes thresholds
  (`fourEyesBudgetAbove`, `fourEyesFanOutAbove`); `mergedDistinctCap` + `mergedDistinctCapInForce`; `seedByDistinctCap`
  only when the server reports it; since 2026-10-24 (`LA-GROW-EXCLUDE-RESIDUALS-1`) a warning per live auto-apply
  category list that would refuse every new Investigation (`problem` key of `GET /inv/exclusion-lists`).
  `maxSetBytes` (`max_set_bytes`, 2026-10-10): per sealed Working Set, default 64 MiB, 1 KiB..1 GiB, over it `413` naming
  the limit. `maxInvestigationBytes` (`max_investigation_bytes`): per-Investigation total of sealed sets (main + open
  Drafts), default 4 GiB, 1 MiB..1 TiB, `413` naming Investigation, total and budget. Both have fields shown only when
  the server reports the key. DR-U3 (2026-10-10, `LA-UI-SETTINGS-MASKING-1`): `maskingMode` (typed | all | none, blank =
  inherit, `maskingModeInForce`), `index.enabled` (inherit / on / off, neutral wording), `threads` 1..64, `queue`
  1..1000, `maxDiskBytes` 0..10^15 (0 = no limit), and the `graphRun` default budget `maxNodes`/`maxEdges`/`timeoutMs`
  (1..10,000,000; in-force values and ceilings from `GET /inv/graph/algorithms`, "-" if it fails). Read only:
  `entityTypesInForce`, `indexInForce`, `investigationStoreInForce` (`fs` | `db`, process-wide). A save keeps
  `keepVersions` and the graph-run pool keys as read, sends no `*InForce` key, omits a wholly blank `index`/`graphRun`
  block; the two selects are disabled (not hidden) without `canAuthorWorkbench`. Numeric limits moved to the limits
  section (`link-analysis-limits.component.ts`: Analysis = Space items, Host resources = install items read-only unless
  `canAdminister`, My browser = personal value + *Reset to Space value*).
* **Embed links settings** (2026-10-12): `embed.defaultTtlHours` / `maxTtlHours` (1..8760; default ≤ cap; blank cap =
  720), `refuseClassifications` (≤ 20 names of ≤ 64 chars), `embedInForce` shown, `embed.frameAncestors` (*Sites that
  may frame the viewer page*; in the form because a save that omitted it would wipe it). Client range check mirrors
  `SettingsRoutes.embed`; an all-blank block is omitted. The feature is OFF unless the host runs `-Dla.embed.enabled`
  (no status API).
* **Share (embed link) panel** (`LA-EMBED-SPA-1`, 2026-10-12; API and headers in
  [architecture-http-and-ui.md](architecture-http-and-ui.md) §6.2): `link-analysis-embed.component.ts` in the Investigation
  tab lists grants (`GET …/embeds`: label, status, expiry, views, scope, masking, Revoke behind a confirm; never a token);
  `link-analysis-embed-mint.dialog.ts` asks label, scope (`working_set` / `with_notes` / `full`), masking (the Space's mode
  or stricter, never looser), lifetime 1..`maxTtlHours`. Gate: `LensService.canShareInvestigation` AND the members
  route's `you` = lead. The token lives only in the dialog's `link` signal (`<origin>/embed/inv/<token>`), cleared on
  Done/destroy, never in a URL, storage or host state. **Viewer** (`la-app` `embed-view.page.ts`, `embed/inv/:token`,
  outside the guarded shell): read-only canvas (`layoutEmbedGraph`: one ring per hop; zoom, pan by drag or arrows,
  *Highlight*; ≤ 3000 links drawn, labels on small sets or the selection) and a searchable paged Working Set table (the
  accessible equivalent), notes and references per scope, all masked as returned; no chrome, no write control, no
  third-party call. After the first answer the address bar becomes `/embed/inv/shared` (`Location.replaceState`), so a
  reload says *open the original link again*; one neutral page for bad / expired / revoked, a separate *Could not reach
  the server* page for status 0 / 5xx; reads through a raw `HttpBackend` (no Authorization header, no interceptor sees
  the token). Closed 2026-10-14: framed only by the token's OWN Space allow-list; inlined Google `@font-face` stripped
  (system fonts, no `mat-icon`); token redacted from the host log for the page path; proxy-log redaction duty in
  [admin-guide-demo.md](admin-guide-demo.md); expiry pinned with an injected clock (`EmbedGrantsExpiryClockTest`).

### Patterns: temporal order, branching and the server half

* **Time order** (LA-14a): a step marked `afterPrevious` needs an event time strictly later than the previous step's,
  optionally within `maxGapHours`; time from an edge attribute column, `Date.parse` as the time filter. `layering-chain`
  and `pass-through` set it (48 h) in the constants AND all six authored TOON copies (authored packs merge over built-ins,
  so an untouched copy would reinstate the unordered motif). 🔴 Before, pass-through was a topology claim worded as a
  flow claim: 200 demo matches with no regard to time.
* ⛔ **A temporal motif FAILS CLOSED:** no time column → nothing and "choose a time column" (not "No matches"); an edge
  with missing or unparseable time is rejected.
* ⚠ **Only ordering survives the projection:** a timestamp arrives as a STRING (`CAST(col AS VARCHAR)`), and 🔴 an
  attribute column joins the `GROUP BY` fold key, so a timestamp de-folds the projection into one edge per instant —
  honest at demo cardinality only; the server half (below) handles scale.
* 🔴 `followsInTime` closed an LA-14a hole: an un-ordered hop recorded `NaN` and `t <= NaN` / `t - NaN > gap` are both
  false, so an unknown time counted as "in order". Pinned.
* **Branching** (LA-14b, browser, 2026-09-23; `branching-pattern-engine.ts` `matchBranchingPattern`): ordered **stages**,
  each `fan-in` / `fan-out` with a minimum of DISTINCT counterparties, optional `threshold` band (`min` inclusive, `max`
  exclusive — a reporting threshold is crossed AT its value), `windowHours`, `afterPrevious` / `maxGapHours`. It extends
  `matchPattern` (sharing `followsInTime`, `edgeTimeIndex`, `baseEdgeKind`). Ordering is per branch; a fan-in "arrives"
  when it reaches its minimum breadth.
* **Built-in branching packs** (constant-only, reach every Space via the PACK-1 merge): **Structuring** = fan-in ≥ 5 legs
  in `900 ≤ AMOUNT < 1000` within 24 h → fan-out ≥ 2 → re-converge ≥ 2, each within 48 h (🔴 a band, not "under 1,000":
  an open bound made every busy account a collector; demo `mule_structuring`, 1,928 transfers / 170 accounts → exactly
  the planted ring in ~9 ms). 2026-10-05 (`LA-INVESTIGATION-OPS-DEFERRED-1`): `layering-network` (fan-out ≥ 3 within 24 h
  → re-converge ≥ 3 within 48 h) and `circular-financing` (two split-and-merge rounds, each within 48 h).
* **Closure** (LA-CIRCFIN-2, 2026-10-05): `BranchStage.closesToStart`, valid ONLY on the last stage, a `fan-in` after ≥ 1
  other stage (else SPA refuses, server 422s); its collector must be in the ORIGIN layer (`layers[0]`) — the one place
  "never revisit a used node" is lifted. `circular-financing` sets it (a proven round trip). Seams: TS
  `matchBranchingPattern`; Java `TemporalMotifMatcher` (`platform/inspecto-graph`) + `PatternQueryCompiler` (closing
  stage prunes to legs landing on stage 0's anchor/senders); authored packs carry an optional `closesToStart` column.
  Parity: the `closure` section of `branching-parity.fixture.json` (`branching-parity.spec.ts`,
  `ControlApiInvPatternTest`). Calls: a stage flag, not a new `shape`; origin = first layer (works for a fan-in start); a
  rotated start is rejected by time; `circular-flow` keeps routing to Cycles.
* ⛔ **It REFUSES rather than answering "none":** no time column · no link with the threshold attribute · no link passing
  the threshold (a `≥ 5,000` view removed every leg before the matcher ran) · graph over the analysis cap (a refusal,
  never thrown). Work is budgeted (`BRANCHING_WORK_BUDGET`), matches capped, both surfacing `truncated`.
* ⚠ An authored branching pack is one flat TOON row per stage
  (`stages[n]{shape,minBranches,edgeKind,nodeKind,windowHours,afterPrevious,maxGapHours,thresholdAttr,thresholdMin,thresholdMax}`;
  blank = wildcard; ANY unusable row drops the pack); no TOON copy is seeded, so that shape has not been through
  `ConfigCodec`'s round-trip.
* **Server half** (LA-14b, 2026-09-23): `POST /inv/pattern/branching` (`PatternRoutes` → `PatternQueryCompiler` →
  `TemporalMotifMatcher`). 🔴 **Why:** the projection is capped (4,000 rows `cnt DESC`, 1,000 nodes) and structuring legs
  are small one-offs that sort LAST and are cut FIRST. SQL pushes each stage's kind, band and the `filter` into the
  `WHERE` of the whole Dataset and prunes with NECESSARY conditions only; the windowed search runs in Java (a
  line-for-line port of the TS matcher). Node keys normalised IN SQL with `normalizeEntityKey`'s rule. Fences: bound
  values, identifiers checked (threshold attrs too), R3 via `InvRoutes.relationFor`, ≤ 100,000 legs (`legCapped` +
  `truncated`), 5 s timeout, the work budget, match limit 200 (≤ 1,000). Refusals are 200 with `refusal` in the
  browser's words; audited `LINK_PATTERN_MATCHED`; read-shaped exemption. ⛔ **Parity is the contract**
  (`branching-parity.fixture.json` asserted by both sides). **Run on server** shows only when the graph is truncated and
  an edge mapping is loaded; the answer merges onto the working set (`branchingResultToGraph`). Deliberate differences:
  no node cap server-side; a `nodeKind` other than `entity` matches nothing. The motif matcher's platform split:
  [architecture.md](architecture.md) §5.

### Value Measures and standing detection

* **`GET /inv/value-measures`** (LA-18, 2026-09-30; `ValueMeasures`): entities breaching a named Measure's thresholds —
  `passThrough`, `velocity`, `timeToCashOut`, `cashOutConcentration`, `structuring`, `benefitTransfer`, plus non-alertable
  `valueWeightedLinks` — in a `[from, to)` window of ≤ 31 days (offsets normalised to UTC). ⛔ No view filter is accepted
  (a `≥ 5,000` view cannot hide structuring); `valueMeasureQuery` never sends one. Rolling `last: <N>h|<N>d` (exactly one
  of it or `from`/`to`; same cap), stored relative, resolved at every read, bind and sweep in **UTC** (DuckDB
  `TimeZone=UTC`; a naive `timeCol` assumed UTC); the answer's `window` states what it read. `cashOutConcentration`
  takes an optional `agentList` (Entity Type `agent`; other type 422, unknown 404, retired 409) restricting answered
  agents to live exact members under the list's sealed normaliser (denominator stays all cash-out); a masked list
  answers its own tokens. An Alert Rule binds one through `POST /inv/investigations/{id}/alert-rules` with
  `valueMeasure:{…}` (one Alert per rule when ≥ 1 entity breaches). Definitions and deviations: archived backlog plan
  §2.6.1.
* **SPA:** the Investigation pane's *Value Measures* panel and (2026-10-12) an Analysis catalogue entry
  (`link-analysis-value-measures.component`, `value-measures.ts`): Dataset + role autocomplete, blank thresholds = server
  default re-filled with values in force, `truncated` / `unvalued` as counts, rolling *Or the last* window
  (`valueMeasureWindowIssue` refuses both-or-neither), the resolved `[from, to)` shown, *Agent list* autocomplete; *Watch*
  only over the open Investigation's Dataset and roles, binding the answered `measure` block verbatim (a rolling window
  stays rolling).
* **Standing detection** (LA-LIVE-DETECTION-1 LD-1..LD-7, 2026-10-04/05; D-LD1 = option A). A value-measure rule reads the
  live Dataset on every sweep and a sweep has no caller, so it is **not evaluated until the owner enables it**
  (`POST …/standing-detection {rule}`, `canAuthorAlertRules`, owner only, bound value-measure rule only). Enable records
  the owner's authority under `standing` (id, capabilities, data scopes, IdP attributes, Dataset, masking snapshot);
  `StandingDetection` re-decides before every read (principal, refusal codes and the `la.detect` Job:
  [architecture.md](architecture.md) §7): owner id or a **`user`** share still grants view on the Dataset's CURRENT
  envelope (`ComponentAccess.canViewAs`; **a role share cannot be re-resolved off a request, so role-only is refused**;
  `canConfigureAccess` never honoured), owner still a lead, masking not **tightened**, PDP (synthetic Subject,
  `RowScope.visibleAs`) does not DENY. Every refusal is an EMPTY `Reading` with a code and an audit event
  (`LINK_STANDING_DETECTION_ENABLED / _SWEPT / _REFUSED`, aggregate only); undoing the cause resumes the sweep;
  re-enabling re-snapshots. ⚠ Residual: the synthetic Subject's grants are the enable-time snapshot with no role names,
  so a DENY keyed on `subject.roles` cannot be reproduced.
* **Routes beside it:** `GET /inv/investigations/{id}/alert-rules` (`InvestigationMeasureRoutes.listBound`; read gate
  `openForRead`, 404 non-member, 503 no write root or alert engine) lists armed bound rules with `valueMeasure`, `edited`
  (hash mismatch: a sweep refuses it) and `standingDetection {enabled, principal?, enabledAt?, dataset?}` (never
  capabilities, masking or a value); `DELETE …/standing-detection/{rule}` (idempotent; rule stays bound, sweep refuses
  `NOT_ENABLED`); `PUT …/alert-rules/{rule}` (edit in place; new hash; **recorded authority dropped**, owner re-enables);
  `GET /inv/investigation-templates` (caller's own, newest first; sharing not built, D-LD6). `la.detect` (no parameters,
  `requires: [alerts]`, Signal `la.detect.completed` with a count and Alert Rule names) calls
  `AlertAccess.evaluateInvestigationRules()`; fails the Run closed without `alerts` or the module; a dry run evaluates
  nothing. Wire shapes and D-LD2..D-LD18 ("decided by the assistant, pending operator confirmation"):
  [`la-live-detection-design.md`](../../../../archived-documents/plans-archive/la-live-detection-design.md) §8, §9.
* **SPA (LD-6, LD-7):** *Enable standing detection* (`LinkAnalysisStandingDetectionComponent`, shown on
  `canAuthorAlertRules`; the server's 403 is the owner check); sweep principal, masking snapshot, enabled time; refusal
  codes in plain language (`STANDING_REFUSAL_HELP`). *Monitoring* (`LinkAnalysisStandingMonitorComponent`) counts
  `_SWEPT` / `_REFUSED` from `GET /events/search` (counts and codes only). `LinkAnalysisBoundRulesComponent` shows the
  bound list (state survives reload) and edits severity, plus the threshold of a Working Set rule (name never changes;
  saving says the authority was dropped). One Enable button per rule.

### Schema overlap and relation SQL

* **`POST /inv/schema/overlap-profile`** (LA-15) measures what naming only guesses: Jaccard of two columns' value sets by
  inclusion–exclusion over `APPROX_COUNT_DISTINCT` (`|A∩B| = |A|+|B|−|A∪B|`) — no values into the JVM, no cross join.
  Pair count is the only quadratic axis and is capped, with the true total reported.
* 🔴 **A relation must be passed as `relationSql`, never inlined as a subquery.** `QueryExecutor.run` registers the
  relation BEFORE sealing the sandbox, the only place file-reading SQL may run; an inlined subquery works on a
  VALUES-backed fixture and is refused against a real Parquet Dataset.
* **A recursive CTE survives `QueryExecutor`'s derived-table wrap** (`QueryExecutorRecursiveCteTest`, D-S2). 🔴 The
  executor's `LIMIT n+1` is OUTSIDE the derived table, so it does not bound the recursion; a traversal fence must live
  INSIDE it (on a cyclic graph the in-recursion depth predicate is the only stop).

### Saved views, legends and node kinds

* **A saved view says what it is on every surface** (D-S1): it stores the QUERY and presentation, never rows, and no read
  is version-addressable. `SAVED_VIEW_NOT_EVIDENCE` is stated in the saved-views menu, on the dashboard widget (🔴 the
  surface that most needed it: a tile reads as a fixed report and is a live re-projection) and in the Attach-to-Case
  dialog. A LABEL, not a guarantee; sealed snapshots (below) are the evidence path.
* **The saved-view widget** (R3-04, 2026-09-26): `LinkViewWidgetComponent` (tile + Menu item) mounts
  `<inspecto-link-analysis-legend>` fed by the studio's `legendItemsFor` / `legendEdgeKindsFor` (colour =
  `display.nodeColors[kind]` else `nodeColor(kind)`, super-nodes never counted), open unless `view.legend: false`; the
  viewer's toggle is never saved. `[showDescription]="true"` (set by `MenuArtifactComponent`) renders the view's
  `description` through `<app-dashboard-header>`; a tile leaves it off.
* 🔴 **Node kinds are never derived from the profile or id prefixes.** A single `query.projection` stamps every node
  `kind: 'entity'`; `profile: telecom` shapes tile labels, measure / time columns and suggested tools only. The only
  kind-bearing path is LA-08's `query.multi.nodes[]`: one `MultiNodeMapping {dataset, idColumn, category}` per kind,
  `category` a constant (no per-row kind column), so the data needs one Dataset per entity kind plus edge Datasets.
  **Category colours** (2026-09-27): `projectMultiResult` stamps `data.color` per category from
  `CHART_CATEGORICAL_NEUTRAL` in first-seen order (a saved `display.nodeColors` still wins). The telco
  `simbox_ring_jeddah` / `fraud_entity_graph` views moved to `entity-projection-multi` (one node Dataset per kind, one
  edge Dataset per link kind, from the same rows): four colours, four-row legend.

### Cases: save, attach and create in place

* **Ops absent vs lookup failed are two states.** The Case picker fills from `GET /objects?type=CASE`. Since 2026-11-14
  (`LA-BASIC-PLACEHOLDER-CASE-1`) there are no placeholder Cases: ops-absent shows "Needs Case management (Standard and
  above).", Attach to Case is disabled, and the server refuses an attach without Case management (503) or to an absent /
  invisible Case (404); a real error clears the list and shows an `<inspecto-alert variant="error">`. 🔴 Attaching
  evidence to a fabricated Case id is a silent wrong answer. The states live in ONE place,
  `LinkAnalysisCaseFieldComponent`.
* **Save and attach are ONE action, the Case optional** (operator, 2026-09-22): **Save this analysis** = name,
  description, frozen-content summary, optional Case. Save stays enabled when the lookup fails (the analysis is kept
  unattached); the picker offers nothing then. **Attach-to-Case** survives for an analysis saved without one; there a
  Case is required and a `caseId` reaching the form while the lookup is errored is refused.
* **A Case page opens Link Analysis on itself:** an `<a>` in `object-detail`'s header (CASE only) →
  `/studio/link-analysis?case=<id>`, which pre-selects the Case in the save dialog. ⚠ The param is deliberately NOT
  stripped (the `?open=` rule): it survives reload and bookmark.
* **A new Case minted from graph nodes** (`LA-CASE-CREATE-IN-PLACE-1`, 2026-09-23): *A new Case, from graph nodes* asks a
  title and a checkbox list pre-ticked from the canvas emphasis (filter at ≥ 8). ⛔ *A case CONTAINS its members*: zero
  nodes is refused on screen and by the server.
  - **Object type INCIDENT** (a Case's Contents are Incidents, GLOSSARY §9; a new `ENTITY` type would have to teach merge,
    split, Case Rules, workflow, FindingsSpec). Titled with the raw spelling, described *Raised from Link Analysis…*. ⚠
    They land in the Incidents inbox.
  - **Identity = `entityKey` + `entityDataset`** (node id, `EntityMember` accepts `^[a-z][a-z0-9_]{0,31}:.+`; Dataset =
    the node's `provenance`, else the projected one). The same pair REUSES the object; an invisible match is not reused
    (existence-hiding). `ObjectStore.findByAttributes` (portable `LIKE` re-checked exactly) THROWS on a store error (an
    empty answer would mean a silent duplicate). Eligible nodes: `case-members.ts` `caseMemberCandidates`.
  - **One route:** `POST /cases/from-entities` (`canManageIncidents`), because `POST /objects` needs an existing link
    target. All refusals checked before the first write; writes under compensation
    (`ObjectService.openCaseFromEntities` removes what it created on failure; reused objects untouched). Basic: 503 and the
    option disabled with the reason.
  - **Order seal → create the Case → attach.** A refused Case leaves the snapshot sealed and the dialog open with the seal
    remembered; a failed attach flips to the existing-Case path with the new Case picked, so a retry never opens a second.

### Entity ids: normalised keys and typed ids

* **D-S4 (2026-09-23):** every id goes through `normalizeEntityKey` (`inspecto/graph/entity-key.ts`: case-fold, collapse
  internal whitespace, strip trailing punctuation, trim), so `ACME Ltd`, `acme ltd`, ` Acme  Ltd.` are one node with
  summed counts; the label stays the first raw spelling and each node carries `spellings`. ⚠ Pre-D-S4 saved views and
  exports no longer match. `splitIdentityGroups` uses the same key and reports a group when raw spellings exceed keys
  (shown in the working set; the hint says they are counted as ONE entity). `tools/check-split-identity-fixture.mjs`
  mirrors the key in Node — change both together. Comparison is scoped (`entity:person:bob` ≠ `entity:account:bob`).
  🔴 **It reads ZERO on every shipped dataset, correctly** (measured 2026-09-22, 24 columns in 4 Spaces; the generator
  emits canonical literals); a positive control collapses 6 spellings to 2. 🔴 **Three mint sites**, not two:
  `geo-analysis.ts` `coLocations` / `coLocationGraph` normalises too (it used to leave dangling edges).
* **Typed node ids (LA-17 D-M6, 2026-09-27):** a column whose registry `classification` is claimed by an in-force Entity
  Type mints `<type>:<key>` through the type's normaliser (`msisdn` `0044 78` → `msisdn:+4478`); others keep
  `entity:<key>`. ⛔ The SPA never guesses: the server sends `columnTypes: {col: {id, normaliser}}` on `/inv/projection`
  and `…/neighbors` (always present) and `entityType` / `sourceType` / `targetType` on `/projection/multi` rows. ONE mint,
  `endpointId(mapping, side, value)` (`entity-projection.ts`); typed mappings are stamped on the graph (`idMappings`) and
  remembered on `lastRun`, so the Investigation binding, traversal, pattern and brush mint the same ids. A value without
  its column goes through `resolveEntityId` / `entityIdCandidates`. Server ids are unaffected (evaluator, Working Set and
  `excludeBy` compare raw values / per-normaliser keys; only `EntityMember` reads node ids). ⚠ No back-compat for
  `entity:<value>` of a now-typed column. The Geo co-location graph stays untyped.

### Server traversal and multi-projection

* **`POST /inv/traversal/recursive-paths`** (LA-11, 2026-09-23): one recursive CTE returns simple paths from `startNode`
  (optionally to `targetNode`), with `direction`, `weightCol`, a validated `filter`, `temporalConstraint`. Every fence is
  INSIDE the recursion: depth a bound `?`, cycles refused by `list_contains`, edge yield a bound `LIMIT` per level
  (`edgeYieldCapped` + `truncated`), a route-local `SqlSandboxPolicy` timeout via `QueryExecutor.run(Request, policy)`.
  Current depth / timeout / path defaults are configuration ([architecture.md](architecture.md) §9; the 2026-09-23
  defaults were depth 6 / cap 10 / 5 s). Audited `LINK_TRAVERSED`. Body names `dataset`/`sourceCol`/`targetCol`. SPA
  *Find paths (server)*: From (required), To (blank = any), max hops, direction; sends the node's FIRST RAW SPELLING (the
  server compares `CAST(col AS VARCHAR)` exactly) plus the pushed `filter`; `recursivePathsToGraph` mints hops through
  `entityId()`, reuses working-set links and ADDS nodes / links beyond the slice. ⚠ No `depthUsed`: the pane shows
  `fences.maxDepth`. ⚠ A folded node starts from its first spelling only.
* **`POST /inv/projection/multi` as its own GraphSource** (LA-08 SPA, 2026-09-23): `entity-projection-multi`. Node
  mappings (Dataset · id · label? · category?) and edge mappings (Dataset · source · target · type?); a half-filled row is
  refused, > 16 refused client-side. Ids minted UNSCOPED, so one key from several Datasets is ONE node with
  `data.provenance` and `data.spellings`; edges fold across Datasets. A 404 (unknown OR not viewable) refuses the WHOLE
  query. Since 2026-10-04 (`LA-SPA-OWED-SURFACES-1`): *Attribute columns* per row (node attrs merge, first mapping wins)
  and a per-edge *Filter* (the request filter still applies to every edge mapping). **Expand** on this source:
  `MultiProjectionGraphSource.expand` reads each edge mapping's one-hop neighbourhood of the node's raw spellings (≤ 8 per
  mapping) through `/inv/projection/neighbors` and folds with `projectMultiResult`, so expanded edges merge. The older
  `entity-projection` multi-mapping path (N × `/inv/projection`, type-scoped ids) coexists.

### Evidence: snapshots, the op log and the Dossier

* **An evidence snapshot is SEALED by the server** (LA-03, 2026-09-23): `POST /inv/snapshots` writes one immutable file
  per id (409 on re-POST); `POST /inv/snapshots/attach` appends to a separate log and never reopens the sealed record
  (that would invalidate `manifestHash`). ⛔ The client updates only after the server confirms; a refused seal leaves the
  dialog open. ⚠ A failed ATTACHMENT does not fail the save. 🔴 A DI change is invisible to type-checking.
  **A snapshot captures what is on screen** (A1, 2026-09-30): `canvasData()` — the Working Set while an Investigation
  draws it, else the query graph; a Working Set snapshot's origin is `{sourceId: 'investigation', dataset, query:
  {investigationId}}`. The SPA's `manifestHash` is SHA-256 (2026-09-28: Web Crypto over canonical JSON, `sha256:<hex>`,
  the server's recipe, pinned in `graph-snapshot.spec.ts`); ⚠ `snapshotGraph`/`verifySnapshot` are async and
  `crypto.subtle` needs a secure context — elsewhere the dialog refuses to seal (never a weaker digest). A snapshot sealed
  while an Investigation is open carries its `investigationId` (outside `manifestHash`).
* **An Investigation is a server-side append-only op log** (LA-10, 2026-09-23; model and hashing:
  [architecture.md](architecture.md) §3.1). `POST /inv/investigations` binds a Dataset + mapping; `/{id}/ops` answers the
  Working Set **delta** + `truncated`; `/{id}/undo` is a recorded log edit (state after = state before the undone op);
  `/{id}/replay` re-evaluates; `GET /{id}/log` renders plain-language lines. ⚠ Replay uses PREFIX semantics (position k
  honours only undos at or before k). ⛔ **Nothing is pinned** (D-E3): `datasetVersion` is `null`; each `expand` SEALS its
  rows with a SHA-256 fingerprint, and `replay {reread:true}` reports drift per step. ⛔ **Re-ordering forks** (D-E4):
  `/{id}/reorder` creates a new Investigation naming its parent; the original stays byte-identical. Writes need
  `canManageIncidents` (Replay and Re-read stay enabled read-only); access is the member gate below.
* **Delete** (`LA-INVESTIGATION-DELETE-1`, 2026-10-27; `DELETE /inv/investigations/{id}`): a lead with
  `canManageIncidents` (else 404 / 403). **409** while linked to an OPEN Case or holding a pending approval. Removes header,
  log, sets, Drafts, members, references, alert-rule bindings, pending records, Case link and embed grants (moved to a
  tombstone, then removed; id freed). Audit `LINK_INVESTIGATION_DELETED` after the removal. ⚠ Filesystem only: Postgres
  answers **503** (append-only triggers; `LA-INVESTIGATION-DELETE-PG-1`). An exported Dossier stays; a bound Alert Rule
  stays registered (sweeps log "no investigation").
* **Rename / restate purpose** (`LA-INVESTIGATION-RENAME-1`, 2026-10-29; `PATCH /inv/investigations/{id}` `{title?,
  purpose?}`): pen fields in Settings ▸ General and the Act panel header. Lead + `canManageIncidents` (non-lead member
  403, stranger 404). Title non-blank ≤ 200; purpose non-blank within `input.purpose_length` (422), the same cap in every
  form. The sealed header is never rewritten: the edit is a `details` record (`investigation-details.json`, or the
  `details` column on Postgres; it also keeps the creator's display name, T-186). **The Dossier does not show the edit**
  (operator-confirmed 2026-11-01): an unsealed "edited" field beside a sealed manifest would let a reader take it for
  evidence. Concurrency (`LA-INVESTIGATION-EDIT-HARDENING-1`): one read-modify-write per Investigation under the store
  lock (monitor + `.store.lock`; `FOR NO KEY UPDATE` on Postgres), so edits never undo each other; an edit racing a delete
  answers 404. A corrupt details file is ignored with a WARN. Audit `LINK_INVESTIGATION_EDITED` with before / after, title
  and purpose masked per `maskingMode`.
* **Case-team sharing** (LA-24, D-U10, 2026-09-30; `InvestigationCaseRoutes`): optional `caseRef` (on create, `PUT`/
  `DELETE …/case`; owner-only, `canManageIncidents`, a visible Case, a closed one 409), stored in `case-link.json` OUTSIDE
  the sealed header. The Case's owner or assignee gets **read-only** access (log, Working Set, Dossier + verify, measures,
  `GET …/case` `access: case-member`), decided live on every read in `openForRead`. ⛔ Membership is a grant, **not a
  policy ALLOW**: R3 and the PDP still narrow. ⛔ Independence: geo-link names no `inspecto-ops` type (core
  `ObjectAccess.summary`); without the module the link is `verified:false` and grants nothing. Fail closed: closed /
  deleted Case or a member who leaves ends access; a fork or template instance does not inherit the link.
* **An `expand` is one hop-ladder rung; a `window` op sets the time window** (LA-13, 2026-09-23; `InvestigationTime`).
  Rung fields: `direction` (either · out · in · reciprocal) · `linkKinds` · `window` (`inherit` default · `full` · override)
  · `minEvents` · `minDistinctDays` · `candidateDegreeMin/Max` (over the WINDOWED graph of the whole Dataset) · `maxFanOut`
  (strongest first, `fanOutCapped`, never `truncated`) · `budget` (row cap; ⚠ `limit` is refused 422). Sealed as
  `read.query` so `reread` re-runs it exactly. ⛔ **Timezone contract:** the Investigation binds `timeCol`; a naive
  `TIMESTAMP` is wall clock in `timeColZone` (default `UTC`, recorded); a `TIMESTAMPTZ` refuses a zone; `from`/`to` carry
  an offset (half-open, epoch ms); a `slot` (crosses midnight; start inclusive) and a `days` mask need an IANA `timezone`
  and test the local day the EVENT fell on. `window` re-filters nothing already admitted. Templates carry the rung; a
  `window` becomes a `kind: "window"` parameter.
  **Calendar exclusions** (2026-10-05): `exclude` list of `"YYYY-MM-DD"` | `{date}` | `{from, to}` (inclusive, optional
  `name` 1–80), ≤ 366, canonicalised and sorted so the same calendar seals the same bytes; local calendar day in the
  window's `timezone` (required). One implementation, `InvestigationTime.predicates` (expand, `window`, `compare`, the
  Dossier rung read); `GET …/coverage` treats an excluded day as not expected. **Recurring:** `{rule:"yearly", month,
  day}`, `{rule:"nthWeekday", month, weekday, nth}` (1–5; a missing 5th never matches; yearly `02-29` leap years only),
  `{rule:"lastWeekday", month, weekday}` (2026-10-12). **The RULE is sealed, not its expansion** (a per-day test, SQL
  `month/day/isodow` arithmetic identical to `InvestigationTime.matches`); order dates then rules; caps 366 dates + 32
  rules; unknown rule / bad date / extra key 422, never logged; a window with no rules seals byte-identically
  (`InvestigationTimeRecurringTest`). SPA syntax `2026-12-25, Winter break=2026-12-24..2026-12-26`, `Xmas=*-12-25`,
  `*-11-THU#4`, `*-05-MON#L` (the `L` form server-side only; a11y spec in `investigation-window-op.component.spec.ts`). Not
  built: Easter-style and lunar holidays. ❌ **Derived Collector roster NOT built** (pinned by
  `PipelineStoreMappingPinTest`): only 1 of 89 Pipeline configs declares an `output_table`, so a roster would list ~every
  Pipeline and raise false alarms.
  **SPA:** the *Expand* dialog's *Advanced* fold (`link-analysis-expand.component`) sends only the fields set: `budget`,
  `direction`, rung `window`, `linkKinds` (comma list ≤ 100; server 422s without a link-kind column), `minEvents`,
  `minDistinctDays`, `candidateDegreeMin/Max` (min ≤ max), `maxFanOut`. The *Time window* form
  (`investigation-window-op.component`) and the rung override share `investigation-window-fields.component`; bounds in
  `investigation-rung-form.ts`; a server 422 renders verbatim. (Current expand budget bounds: [architecture.md](architecture.md) §9.)
* **The Investigation tab drives it** (LA-10 SPA, 2026-09-23; `link-analysis-investigation.component` over the
  pane-provided `InvestigationSessionStore`). Start binds Dataset + columns; ⚠ the query's `filter` is not sent (the
  create route takes none). While one is open a canvas click picks the entity and the canvas draws the **Working Set**.
  Ops send RAW spellings (the Working Set is folded through `projectTriples`/`entityId()`, the server's untrimmed value
  added to `spellings`). Exclude needs a reason; undo, replay (re-read → drift table), re-order (fork explanation) wired;
  truncation shown from the log. ⚠ `/ops` and `/undo` answer counts, so the store re-reads `GET /log` + `POST /replay`
  after each mutation (writing a `link.investigation.replayed` event per step). Listing:
  [Closed-plan record](#listing-and-link-notes).
* **The Dossier and its chain of custody** (LA-12, 2026-09-23; `DossierRoutes`, `GraphDossierBuilder`):
  `GET /inv/investigations/{id}/dossier` (`?at=`, `?snapshots=`, `?format=json|steps|method|html`): summary, topology,
  ledger, centrality / risk tables, **negative space**, integrity report and three renderings (re-runnable JSON, numbered
  steps, a method statement citing the custody hash). Every exclusion (id, step, author, reason) appears in all three
  (G-E10). **`format=html`** (LA-DOSSIER-OUTPUT-1, 2026-10-03; `DossierHtml`): one self-contained page (inline CSS +
  `@media print`, no scripts / links / fetch → browser *Save as PDF*), rendering the same already-masked map
  (`maskedDossier`); ⛔ every value through `DossierHtml.esc`; `Content-Security-Policy: default-src 'none'; style-src
  'unsafe-inline'`; pinned by `ControlApiDossierTest`. No server PDF (no PDF library). The **manifest** hashes raw stored
  bytes of `header.json`, each `log.jsonl#<step>`, each `sets/<step>.json`, each included snapshot, plus canonical
  entities, links, exclusions and score vectors (G-R6); `root` = SHA-256 over the manifest body, independent of build
  time. `POST …/dossier/verify` names each `changed` / `missing` / `added` artefact; an edited manifest fails its own root
  (`selfConsistent:false`). ⚠ **Two independent checks:** `integrity` re-checks LA-10's recorded hashes (so the first
  Dossier after a tamper reports it); a tamper that keeps internal hashes consistent, or one to the header, is caught by
  the manifest alone. The Dossier computes no centrality itself: its score tables are vectors a snapshot sealed, each
  labelled. A snapshot must be **anchored** (`investigationId` names this Investigation) or 422. Neither route persists;
  audited `LINK_DOSSIER_BUILT` / `_VERIFIED`. The server stores `manifestHash` verbatim; the manifest is the custody root.
  **Data-state footer** (LA-DATA-TRUST-1 S9, 2026-10-14): `dataFooter` on json, html and bundle — data-as-of
  (`DatasetProvider.dataAsOf`), newest index version + stale flag, Files waiting for the index, days with no rows over the
  window at the Dossier's step. 🔴 **NOT sealed**: assembled AFTER `GraphDossierBuilder.build` as a sibling of `manifest`
  (dropped by `DossierBundleRoutes.seal`), says `sealed:false`, an edited footer does not fail verification; an untaken
  reading is `null` and named in `notAssessed`; counts, days, versions only. `steps`/`method` unchanged. Proof
  `ControlApiDossierDataFooterTest`. **SPA** (`link-analysis-dossier.component`): renders json, downloads
  `steps`/`method`/HTML as Blobs through HttpClient at the dossier's own `at` and `snapshots` (never a bare href: it
  would skip the bearer); verifies the issued manifest or an uploaded file and names why a verify failed. *At step*
  (0..`summary.steps`) and *Include snapshots…* (`GET /inv/snapshots`, newest first, `limit=100`, ≤ 20 checked,
  `DossierRoutes.MAX_SNAPSHOTS`); ⚠ the list is not filtered to this Investigation, so a foreign snapshot is refused 422
  *not anchored* (verbatim).
* **The Working Set as rows** (LA-20, 2026-09-23; `WorkingSetRoutes`): `GET …/working-set?of=entities|links|excluded&limit&offset`,
  fixed columns with provenance (`opSeq`, `seedId`, `hop`, `reason`), true `total` + `truncated`, from the sealed log
  alone; **cached** under the hash of the committed log bytes (access gate runs before the cache). Readers (D-E7 amended
  by D19): a member or a linked-Case member; on Advanced also the PDP's row verdict for `resourceKind: investigation`. ⛔
  Not a Dataset or DuckDB view (`/bi`, `/db` cannot reach it). SPA: 200 a page, *Load more*, keyed
  `la-working-set-<relation>`; an empty relation renders an empty state (an empty ag-grid fails axe).
* **Templates and Measures** (LA-23 SPA, 2026-09-23): a Measures strip reads `GET …/measures`; *Watch* binds an Alert Rule
  and shows current value, `wouldFire` and the disclosure verbatim (503 explained; 403 names the Alert-Rule capability).
  *Save as template* previews the D-E8 extraction (seeds → parameters, exclude/hide/keep dropped, named expands
  generalised) as a client mirror of `InvestigationTemplateRoutes.save` (`investigation-template.ts`); the server's
  `exact` answer is the authority (no dry run; templates are write-once). *Instantiate* lists
  `GET /inv/investigation-templates` (DR-D4), asks one seed list per parameter plus Dataset and roles, and opens the new
  Investigation.
* **Working Set Widgets** (LA-21, D-E6): *Pin to a Widget* saves a `working-set` Widget (`workingSet{relation, mode,
  pin{step, workingSetHash}}`). **Frozen** re-reads `?at=<pin.step>` and refuses rows if the hash differs; **Live** reads
  the head and states the drift. Stores no rows; a viewer who cannot open the Investigation sees *Not available to you*.
  ⛔ The Exchange refuses Working Set Widgets; a bundle export converts Live → Frozen (`converted`, and the SPA warns).
* **Annotation and coverage** (LA-19, 2026-09-24): `{op:'annotate', ids, note}` (≤ 2,000 chars) through `store.apply`;
  notes from the sealed `annotations` (absent when empty); undo needs nothing special. An Admiralty grade `confidence`
  (`B2`: A–F × 1–6, D-U9) is accepted and shown, never asked by the panel. *Check coverage* reads `GET …/coverage` over
  the Investigation's own window (`from`/`to`/`timezone` accepted, no picker) and names every zero-row day; it needs a
  `timeCol` and a bounded window (422 verbatim otherwise). Start sends `timeCol` (prefilled, DR-D7, from the timeline
  column, else the Domain Profile hint, else the first all-dates edge attribute `isTemporalColumn`) and `timeColZone` only
  with it. Per-Collector coverage: [decisions of record](#decisions-of-record).
* **Purpose, masking, four-eyes** (D-U5/U6/U7, 2026-09-24): Start and Instantiate REQUIRE a *Purpose / legal basis*
  (422 without), sealed and shown in the Dossier, never enforced. Ids may arrive as `masked:<16 hex>` per `maskingMode`
  (default `typed`; rules: [architecture.md](architecture.md) §3.4). Since 2026-10-04 (`ASSURE-CLASSIFICATION-PROPAGATION-1`)
  column classification also comes from the pipeline schema's `raw.fields[].classification` followed through its mapping
  by `DatasetProvider.schemaClassification` (shared with `publish.postgres` and Risk Score evidence): strictest-wins on a
  computed column; untraceable lineage of a masked class masks every id. A pseudonym may be sent back in an op's `ids`.
  ⚠ With a four-eyes threshold set, every stateless graph read (`/inv/projection`, `/projection/multi`,
  `/projection/neighbors`, `/traversal/recursive-paths`) is **403** above it (a stateless read has nothing to approve); a
  budget threshold below the default `limit` refuses the canvas's initial load, and the SPA's display of that refusal is
  unchecked.
* **Members, Drafts, Oversight panels** (2026-10-03..11): Members reads `GET …/members`, a lead grants / revokes (`you`
  decides the controls; 404 "not available to you"; 422 last-lead). Drafts drives fork, `working-set`, `conflicts`,
  `rebase` (confirms exactly the listed conflicts), `promote` (`expectHead` = base + `behind`; 202 = four-eyes hold) and
  `discard`. **Draft as working scope** (`LA-UI-DRAFT-OPS-1`): `useDraft(draftId)` routes `apply`/`undo` to
  `…/drafts/{id}/ops|undo` and draws the Draft's log / replay; `LA-UI-DRAFT-FIX-1`: the Draft is loaded FIRST and the
  scope switches only on success; Replay is equivalence-only (no `reread`, checkbox disabled with a reason); re-order is
  disabled (a fork re-orders the MAIN log); Undo only after the Draft's `baseStep`; the list re-reads on each `lastStep`.
  **Oversight** (`link-analysis-oversight.component`): `pending[]` with requester, thresholds crossed and status;
  `canApproveLinkExpansions` → Approve / Deny (deny reason ≤ 200); `canRevealLinkEntities` → per-entity Reveal (audited).
  ⚠ The server is the boundary: self-approval and a Subject-less approve are 403 even WITH the capability; nothing is
  re-read until a decision succeeds. **Approver inbox** (2026-10-11): `GET /inv/pending?status=…&limit`
  (`canApproveLinkExpansions`; 503 / 403 / 422), each Investigation judged by `openForApproval` (explicit members:
  lead / reviewer; owner-only: any approver; R3 + PDP), the caller's own requests never listed, masked, true `total`,
  audited `LINK_EXPANSION_INBOX_READ` (counts). SPA: **Approvals → Link expansions**.
* **Entity Lists section** (LA-17 step 6, 2026-09-26; foot of the Investigation panel): title, purpose, Entity Type label
  (a type out of force says so), size, *Retired* badge — ⛔ never members (they arrive masked). *New list…*, *Add
  selection* (raw spellings via `rawIdsOf`; ⚠ selection exists only while an Investigation is open; ⚠ `masked:` pseudonyms
  are split out by `listableIds` — the members route would store the pseudonym), *Retire* (reason dialog). On an open
  Investigation *Exclude by list* / *Seed by list* append `excludeBy`/`seedBy` through `store.apply`; the notice counts
  `list.unmatched`, never displays it. Errors via `entityListErrorMessage(…, true)` (a 404 may be list or Investigation).
  Writes gated on `canManageIncidents`; hidden when `geoLinkEnabled` is off.
* **Identity resolution section** (LA-17 slice 2, 2026-09-30; `link-analysis-identities.component`): assert two typed
  identifiers are one entity (cross-type allowed, reason), *Find group*, groups with their assertions, *Retract*. ⚠ Keys go
  out NORMALISED (`identityKeyOf`); ⚠ the `key` param uses `STRICT_QUERY_CODEC` (Angular sends `+` raw, the server decodes a
  space). Reads need `canManageIncidents` too. Wire contract: [Consolidated as-built facts](#entity-model-and-live-detection).

### The call-records feed (postmed_xdr)

* `postmed_xdr` is the call-records feed (LA-16 / D-U2, extended not duplicated): `IMEI` (deliberately invalid: no TAC
  prefix, no Luhn digit), explicit `DIRECTION`, and a UTC contract in `raw.fields[].timezone`. ⚠ DATA rows keep
  `OTHER_PARTY='N/A'` (a packet session's far end is the APN). 🔴 It drew a fresh subscriber PER ROW (~1,200 disconnected
  edges); a fixed population plus four planted stories (burner rotation, one-way hub, daily repeating pair, SIM moved
  between handsets) makes **873 edges over 174 nodes**.
* ✅ **Ingested end to end** (2026-09-23): 1,283/1,283 rows across three Hive partitions, zero rejected files / rows /
  cast failures, every row reconciles by `REC_SEQ`; `IMEI` keeps leading zeros; the 352 `DIRECTION='NA'` rows are the 352
  with a NULL counterparty; the planted stories survive.
* 🔴 **The UTC declaration is correct but UNFALSIFIABLE:** `SourceZones.toNaiveUtc` with `Z = 'UTC'` is an identity, and
  so is the no-declaration path, so this run proves the values, not that the declaration applied. Pinning it needs a
  NON-UTC zone in a fixture.
* ⚠ **The `.psv.defect` fixture is INERT:** excluded by filename (`glob:**/PMXDR_*.psv`), never opened, so its five
  planted defects are never exercised; renaming it `.psv` would prove the quarantine path.

## Graph Run

*Server-side graph analysis, as built 2026-10-01 (LA separation D-4; GLOSSARY "Graph Run").* One execution of one of the
29 algorithms on the SERVER over an Investigation's Working Set — the way past the browser caps; both implementations
held equal by parity fixtures. Modules, input routing, budgets, pool, cache, `propagatedRisk`, the motif and temporal
platform split: [architecture.md](architecture.md) §5. Below: what only this page states.

* **Modules.** `inspecto-graph` (pure JDK, `platform/` since D-AN2 2026-10-17): the six `Graph*` classes plus `RunControl` /
  `GraphAborted`; old signatures delegate with a no-op `RunControl`. 17 algorithms are instrumented; the 11 without a
  `RunControl` overload (paths, ego, neighborhood, components, degree, structure, forest) cannot be cancelled mid-run.
  `inspecto-la-core`: `Algorithm` (id = the TS export name, `cost`, `inlineNodeCeiling`, typed `params`, `needs*`,
  `resultKind`, `resolve`), `GraphEngine` SPI, `InMemoryGraphEngine` (exhaustive switch, no `default`), `GraphInput`,
  `GraphBudget`, `GraphRunService`. `inspecto-la-api`: `GraphRunRoutes`, `GraphResultJson` (exhaustive switch over the
  sealed `Payload`, so a new result shape does not compile). `inspecto-graph.jar` is a core thin jar in every bundle.
* **Routes** (`/api/v1`). `GET /inv/graph/algorithms` (catalogue, `ceilings`, `defaults{…,clamped}`, `pool`, `inlineWaitMs`,
  `engines`, `resultItems`; no capability) · `POST /inv/graph/runs` (`{investigationId, at?, algorithm, params?, weights:
  "count"|"none", kinds?, budget?, input?}`; unknown field 422) → `200` with the run view when terminal or finished within
  the inline wait, else `202` + `Location` · `GET /inv/graph/runs[?investigationId=]` · `GET …/{id}` · `POST …/{id}/cancel`
  → `202 {runId,status,cancelRequested}`. At or under `inlineNodeCeiling` the request waits `INLINE_WAIT_MS` = 3,000 ms;
  above it never. Queue full `503 STORE_BUSY` (retryable); no write root `503 CONTROL_PLANE_READ_ONLY`. Reads re-run
  `openForRead` every time (lose the Investigation, lose the run: 404). Four route gates done (openapi,
  `CapabilityManifest`, the la-api / geo-link manifests' `provides.routes`, real-HTTP tests with an armed Authenticator).
* **Capability.** Start needs `canRunLinkGraphAnalysis` (seeded to `operations` / `support` / `power` / `admin`; `super` by
  convention; listed in `okf/product/module/access/plan-security.md`; SPA `LensService.canRunLinkGraphAnalysis`,
  access-catalog node `linkgraph.run`). Cancel carries none (`self-service` exemption): see gotchas.
* **States.** `QUEUED → RUNNING → COMPLETED | CANCELLED | BUDGET_EXCEEDED | FAILED`, terminals final. The budget is always
  fully stated (request, else Space `graph_run`, else default), clamped, echoed `budgetClamped`. **Never a silent cap:**
  params validated first (`InvalidGraphRequest` = 422, no run), size checked BEFORE work (`BUDGET_EXCEEDED` with reason
  NODES or EDGES and the measured size), then cache, then pool. `timeoutMs` counts from execution start. A run returning
  AFTER its deadline is `BUDGET_EXCEEDED`, answer discarded; a cancel losing the race to an uncancellable algorithm is
  `CANCELLED`, discarded. `result` (and `masking`) only for `COMPLETED`; `FAILED` names the exception class, never its
  message (it could carry an entity id).
* **Input rows.** `WorkingSetGraphInput.from(entityRows, linkRows, kinds)`: the Working Set at `at` MINUS hidden entities
  and their links (`input.hiddenEntities`); `resolve` groups NOT merged; links with a non-entity endpoint dropped and
  counted (`droppedDangling`). Edge id = wire `LinkIds.encode(source,target,kind)` (not the SPA canvas id). Weight =
  `WorkingSetGraphInput.edgeWeight(count, kind)` (positive count, else 1; `weights: "none"` unweighted). Parallel edges of
  one `(source,target,kind)` fold, so `degree` legitimately differs from a browser graph that kept them.
* **Masking after the cache.** Computed on RAW ids, cached once per Working Set, masked on the way out (`GraphResultJson.of`
  + `Ids.of(EntityMasking)`). **Edge ids: `LinkIds.decode` → mask both endpoints AND the kind → `encode`**, so a masked edge
  id equals the masked Working Set's `linkId` (asserted over the wire). A pseudonym is accepted as `from`/`to`/`node`.
  Communities are an ordered `[{id, community}]` list. **Masking oracle closed (2026-10-02):** a RAW id of a masked
  entity sent as `from`/`to`/`node` is treated as absent (`GraphRunRoutes.ABSENT_NODE`): the same empty answer an unknown
  id gives.
* **Cache scope.** Two Subjects share entries safely today because the Working Set is a pure function of the sealed log and
  Dataset gates / masking run outside the cache (`ControlApiWorkingSetSubjectScopeTest` must keep passing). **The key must
  gain the Subject's resolved row scope the day a Dataset is read live or gains row filtering.**
* **Canonical order `canonical-v1`.** Equal scores rank by UTF-16 code-unit order of the NORMALISED id, then label (Java
  `BY_SCORE_THEN_ID_THEN_LABEL`, TS `compareCanonicalV1`), replacing six `localeCompare` sites; host-independent; pinned by
  a mixed-case fixture row in both languages.
* **Audit.** `LINK_GRAPH_RUN_STARTED / _COMPLETED / _CANCELLED / _BUDGET_EXCEEDED / _FAILED`. Terminals come from a
  `GraphRunService` hook (exactly once, outside every lock, exceptions swallowed); STARTED from the request thread, so a
  fast run's terminal can precede it (both carry `runId`). Never params; a terminal event's actor is the owner.
* **SPA** (`GraphRunsService`, `LinkAnalysisServerRunComponent`, `graph-run-apply.ts`): browser-first under the cap,
  server above. A tool group shows **Run on server** INSTEAD of the local button when the Working Set exceeds that
  algorithm's `browser_algorithms.<id>` limit (suspicion: `suspicion_node_cap`); the count is the Working Set's (hidden
  left out); no Investigation → disabled with "open an Investigation". No `at` sent. Polls at 1 s. `buildServerIdMap`
  matches server ids against every raw spelling a node folded and maps a `linkId` by re-minting the canvas edge id; an id
  the canvas does not draw is dropped, never invented. BUDGET_EXCEEDED shows code, budget, size and ONE next action ("run
  again with a budget of N" only if it fits under `ceilings`, else "filter the Working Set or pick a cheaper algorithm").
  Progress: prefers `progress.fraction` and `consumed.elapsedMs` when > 0, else "N steps done" and a browser-measured wait.
* **Selection algorithms** (2026-10-01): shortest / weighted shortest path, all paths, cycles, cut points (articulation
  points and bridges = TWO server runs) and maximum spanning forest use one threshold, `selectionNodeCapValue()` (= the
  analysis cap), lowered to the server's `inlineNodeCeiling` (`serverCeilings`), never raised. The local button is
  replaced ONLY when a server run can start (`serverFirst`: over threshold, Investigation open, capability held). Server
  selections go through `applyServerResult` into the same `applyPath` / `applyAllPaths` / `applyCycles` / `applyCutPoints`
  / `applySpanningForest`. Cut-point halves combine only when computed against the same id map; "No cut points" only when
  both halves ran on it.
* **Not drawn on the canvas.** `countDropped` / `droppedNotice` state `N of M result nodes and K of L result links are not
  drawn on the canvas right now`; no reveal action (collapse and filters act on the query graph).
* **The Investigation canvas draws the Working Set whole, up to ONE render ceiling.** `workingSetToGraph` folds links
  through `projectTriples` with the node cap OFF (the 2026-10-01 preview drew `1200 nodes · 551 links` for 1,200 / 1,501
  because the cap dropped every link past the 500th node). `WORKING_SET_LINK_RENDER_CEILING` = 5,000 links (constant):
  above it the heaviest are drawn (count desc, then id), `omittedLinks` counted, notice `Showing 5,000 of 20,000 links
  (render limit)`. Kind filter, timeline and super-node grouping act on the query graph only, so they are disabled with
  the reason while the Working Set is drawn.
* **Parity.** The six `graph-*-parity.fixture.json` files are asserted in TS and Java (`GraphEngineParityTest` too), plus a
  route-level test feeding `graph-algorithms-parity.fixture.json` through Dataset → Investigation → Working Set → run
  (`degree` deliberately not asserted). `GraphComplexityEquivalenceTest` / `graph-complexity-equivalence.spec.ts` keep the
  pre-fix `kCore`, `weightedShortestPath`, `linkPrediction` as references (`LA-GRAPH-QUADRATIC-1`). `GraphAlgorithmsBench`
  (`-Dinspecto.bench=true`, `-Dinspecto.bench.only=`) sets `inlineNodeCeiling` = largest benched node count with median ≤
  1,000 ms (500 for betweenness and suspicion).
* **Gotchas and deliberate decisions.**
  * A well-formed node id naming no node yields the algorithm's empty answer (as the browser and fixtures do); only an
    absent / blank / mistyped id, unknown name, bad type or out-of-range value is refused. Count params accept 0.
  * `linkPrediction` stays a JOB (every pair sharing a neighbour: quadratic by definition at a hub);
    `jaccardSimilarity`'s cost follows the chosen node's degree, so a node ceiling alone does not bound it.
  * **Live progress:** `consumed.elapsedMs` is monotonic since execution began (0 while QUEUED); `progress {work, fraction,
    known}`, `fraction` meaningful only when `known` (reported next to `checkpoint()`, never in an inner loop).
    Reporting: betweenness, closeness, link prediction (per source); PageRank, eigenvector, Katz, HITS, label propagation
    (per iteration of the requested maximum — early convergence finishes below 1). Louvain and the 11 uninstrumented
    report nothing.
  * **Cancel on someone else's run is 404** (same body as an unknown run; no existence oracle), and cancel re-runs
    `openForRead`. Starter and administrator (`Roles.CAN_ADMINISTER`, or no Subject) cancel; a finished run is 409
    (operator-delegated 2026-10-02; `LA-GRAPH-RUN-CANCEL-STARTER-COVERAGE-1` pins the Case-member 404 with a mutant).
  * With an Investigation open, browser runs and From / To pickers use the Working Set canvas (`canvasData()`), the graph
    server runs map onto (operator 2026-10-02).
  * **Service lifetime:** one `GraphRunService` per Space write root (`GraphRunServices`), created lazily, closed by
    `ApiContext.onClose` or when unused for 1 h (`IDLE_TTL_MS` = finished-run retention) with nothing in flight AND
    nothing finished within that hour (`lastActivityMillis()` counts a finish), or at once when the Space directory is
    gone. Sweep on access; one lock covers sweep, lookup, create, close. Worker threads time out after 30 s idle
    (`allowCoreThreadTimeOut`).
  * **`maxRuns` is soft:** it bounds FINISHED runs; live runs are bounded by the pool, so the table holds at most
    `maxRuns + threads + queue`.
  * **Result size — never silent:** each list (`scores`, `hubs`, `authorities`, `groups`, `ids`, `communities`, `links`,
    `selections`, sub-graph `nodes`/`edges`, `nodeIds`/`edgeIds`) is cut to `graph_run.max_result_items` (default 10,000,
    ceiling 1,000,000; echoed `resultItems{limit,default,ceiling,clamped}`) **on the way out, like masking** (the cache
    keeps the full result; raising needs no re-run). Keeps the FIRST canonical-v1 entries; `result.truncated` +
    `result.lists.<name> = {total, returned, limit, truncated}` (nested lists such as `groups[0]` only when cut). A sub-graph
    returns only edges with both nodes returned. SPA `truncationNotice` ("Showing the first 10,000 of 25,311 scores (the
    server cap is 10,000; raise graph_run.max_result_items in Settings)").
* **Closed 2026-10-02:** `LA-COVERAGE-FLOOR-HOME-1` (geo-link's coverage profile writes `jacoco-aggregate/jacoco.csv`;
  `check-coverage.mjs` keeps the best row per class; floor 78.0%); `LA-INDEX-FINGERPRINT-COST-1` (`InputFingerprint.MAX_FILES`
  10,000); `LA-INDEX-READER-POOL-1` (`IndexReader.borrow`, max 4 idle per version; nothing calls `evictAll()` at close
  yet); `LA-INDEX-STAGE-RACE-1` (`IndexStore.stage()` retries on `FileAlreadyExistsException`, 16-thread test);
  `LA-INDEX-BUILDER-TEST-TIMING-1` (cancel test polls for stage bytes).

## Studio UI and accessibility record

As-built UI notes not on the [Investigation UI page](architecture-http-and-ui.md) (§6.9, §6.14); history lines dropped
where a later bullet supersedes them.

* **Starter cards, Dataset suggestions, result actions** (2026-10-10, operator walkthrough: "the screen gives a new user
  nothing to do"). Empty canvas = `inspecto-link-analysis-starter-cards`: *Follow the money (example)* loads the view
  `pickFollowTheMoney` scores by money words (hidden when none scores); *Explore a Dataset* opens the Query dock and
  `startExplore()` (the next full mapping also runs); *Open a saved view* (hidden with none). `la-starter.ts`:
  `suggestLinkColumns` (role pairs payer/payee … a/b over string-like columns; a shared remainder like `_ACCOUNT` scores
  higher), `rankDatasets`, `topSeeds`. Columns of a Dataset that declares none are probed through
  `DatasetRowsService.columns` (≤ 12, background); no row counts (no metadata carries one; no new route). A Dataset pick
  pre-fills a blank Source/Target only. `inspecto-option-picker` has an opt-in `outlined` input. A ranking row (Centrality,
  Suspicion, Similarity) → `pickNode`: emphasis, `GraphViewComponent.centerOn` (`graph.focusElement`; lands within 3px of
  centre) and selection. **Start an Investigation from the top results** fills `queuedSeeds` (top 5, pseudonyms skipped);
  nothing is created until Start (one `seed` op) or *Seed the queued entities*.
* **la-app Settings by audience** (2026-10-16): `settings.page.ts` is a compact master-detail page (`:section` route): **My
  settings** (*My browser*), **This Space** (*Link Analysis* via `canAuthorWorkbench`, *Analysis limits* via per-item
  `editable`, *Access* = `AccessRolesComponent` via `canConfigureAccess`), **This server** (*Host resources*). Every
  section listed for everyone; one not changeable opens read-only with a *Read-only* chip (the server's 403 still judges).
  Default-preserving inputs `LinkAnalysisLimitsComponent.sections` and `LinkAnalysisSettingsComponent.withLimits`. No
  unsaved-changes guard yet. Role ASSIGNMENT stays in the IdP (WSO2 groups = role names).
* **Settings ▸ My settings ▸ Investigation access** (2026-10-30, `LA-PRODUCT-NEXT-1` phase 3; leads only):
  `investigation-access.page.ts` lists Investigations the caller can open with members and ACTIVE embed links; Revoke only
  where the caller is lead AND holds `canManageIncidents` (members) or `canShareInvestigation` (links), through the
  Investigation's own routes. The last lead never gets Revoke; the owner folds in as lead. Revoking a member revokes their
  links (server side), so links are re-read. A Case-shared Investigation has no member list (its card says so). ⛔ No
  admin-wide view (a non-member gets 404 by design, D19). Not paged ("Showing the first N of M"); four reads at a time; a
  failing read degrades only its card.
* **Footer and limits** (2026-10-29, `LA-INV-EXPAND-1`; supersedes the 2026-10-16 footer status bar): no status bar under
  the canvas; graph model and Refresh in ⚙ Settings ▸ Graph model; counts on the Graph counts card, which leads with *N
  nodes · M links on screen* (`onScreenCounts()` over `canvasData`, so the Working Set while one is open) above *Totals for
  the full graph, before grouping*. **Limits** = `inspecto-link-analysis-caps` on that card (icon button
  `heroicons_outline:scale`, popover `top-full right-0`, Escape returns focus). **Installed modules** is the ⚙ Settings
  section `modules` (*This server*; `GET /modules` read when first shown, filtered to `geo-link` + `la-*`). The canvas
  render strip keeps only what the render left out (`role="group"`: axe refuses an `aria-label` on a role-less empty div).
* **Expand in the Investigation tab** (`LA-INV-EXPAND-1`, 2026-10-29): `LinkAnalysisExpandComponent` `embedded` is
  projected into the tab (`<ng-content select="[laExpand]">`, writers only; a reader sees *Needs Incident management.*);
  every entry point (toolbar, G, More ▸ Expand…, right-click) calls `openExpand()`, which opens the tab and focuses the
  section. Order: From, Degrees (`inspecto-stepper-field`, `role="spinbutton"`, inert bound buttons with reasons from
  `disabled-reasons.ts`), Direction, Expand with preview / four-eyes / why-not lines, then closed Links / Time / Limits
  folds. The embedded preview reads 300 ms after its start changes and only while active. The tab lists no steps: **Open
  the Step log** / **Investigation settings…**; Replay stays in Settings ▸ *Replay and drift*. Tab strip: plain text tabs,
  `flex-nowrap overflow-x-auto`.
* **Unsaved start** (`LA-UNSAVED-INVESTIGATION-1`, 2026-10-29): the start form (`LinkAnalysisStartBoxComponent`
  `mode="investigation"`) asks no Name or Purpose (those come on Save) — Time column, Time zone, 1-based Degrees. Unsaved
  state is `InvestigationSessionStore.unsaved` (`unsaved-investigation.ts`); `saveUnsaved` creates with
  `POST /inv/investigations` and replays each step through `/ops`, stopping at a refused or held step; no server route
  added. More steps menu = `STEP_GROUPS` (Connect · Time · Compare). Plan:
  [`la-unsaved-investigation.md`](../../../../archived-documents/plans-archive/la-unsaved-investigation.md). Later work
  (undo / redo, clean up, exclude while unsaved): [architecture-http-and-ui.md](architecture-http-and-ui.md) §6.9.1.
* **Degrees trims the last Expand** (`LA-DEGREES-TRIM-1`, 2026-10-30): the form takes `lastExpand` and emits
  `degreesChange` after 300 ms; unsaved, `trimExpandStep` / `extendExpandStep` cut or extend from `UnsavedStep.stages` with
  no server call; saved, `GrowExcludeStore.trimLastExpand` posts one `undo` per removed step and `growInvestigation(…,
  extend=true)` adds degrees. The log is append-only, so a trim leaves undone steps; a refusal restores the stepper.
  **`LA-DEGREES-TRIM-2`** (2026-11-01): every `expand` of one Expand carries a client `group` id (`InvestigationRoutes.expandGroup`:
  non-blank ≤ 64 chars else 422; never read by the evaluator); without a run from this browser, `boundRun` binds the newest
  steps sharing the newest group. Plan: [`la-degrees-trim.md`](../../../../archived-documents/plans-archive/la-degrees-trim.md).
* **Graph text alternative** (2026-10-03): the shared graph host is `role="figure"` with an `aria-label` of the counts
  (`graphSummaryLabel`); the positive `tabindex="1"` G6 puts on its canvas layers is reset to `0` after `render()`
  (`demotePositiveTabindex`; stacked layers are one tab stop). The node-list view was removed 2026-11-02: the dock table
  (Links / Nodes, Enter selects) is the text alternative. The Analysis / View / Investigation switch is a `role="tablist"`.
  Focus on first draw (2026-10-14, spec-level): the canvas wrapper is `role="region"` *Link graph*, `tabindex="-1"`; on the
  FIRST draw out of the landing state focus moves there only if it was on the removed trigger or `body`, and a sr-only
  `role="status"` says *Graph drawn, N nodes, M links*.
* **Accessibility fixes, durable** (`LA-A11Y-AUDIT-1`, passes 2026-10-03 to 2026-11-01; axe 4.12.1, wcag2a/aa/21a/21aa/22aa
  + best-practice, both schemes): `<footer>`, `role="navigation"` on the nav host, `role="status"` on the loading bar;
  `ariaModal: true` via `MAT_DIALOG_DEFAULT_OPTIONS` (⚠ the token REPLACES `new MatDialogConfig()` — spread it or dialogs
  lose `role`; it was never copied into `projects/la-app`, so every la-app dialog was `aria-modal="false"` until
  2026-10-14); `text-secondary` not `opacity-60` on headings (light contrast); docks STACK below `md` (a row of fixed-px
  docks left the canvas 0px at 320px / 400% zoom); the busy sign-in button holds a decorative spinner + hidden *Signing in*
  (`nested-interactive`); `aria-controls` only while the bottom panel is rendered; legend link-kind toggles min 24px
  (2.5.8); *Open Investigation <title>* buttons; `<inspecto-ai-explain>` `label` input (*About Link Analysis tools*); a
  forced-colors `Highlight` outline on pressed toggles (`forced-colors.spec.ts`); a *Skip to main content* link first in the
  console shell (`skipToContent()` focuses `main#main-content` without touching the URL); switcher names *<label>, switch
  lens/space* (2.5.3). 2026-11-01 la-app reflow fixes at 320 / 640px: top bar `min-h-12 max-md:flex-wrap`; Geo query
  summary `inline-block`; the Geo page's map gets a `min-h-64` floor (page only — the dashboard Geo widget must still
  shrink). Driven live clean: Dossier and identity dialogs over a real Investigation, a real server run, Geo in `la-claims`.
  ⚠ Testing traps: finish `document.getAnimations()` before axe after a scheme switch; the ag-Grid `aria-required-children`
  / `scrollable-region-focusable` hits appear only while `ag-delay-render` is on `.ag-root`, which needs an animation frame
  the hidden Browser pane does not deliver — a **documented vendor exception with no workaround** (decided 2026-10-18);
  `inspecto-data-table` never mounts ag-Grid's empty header (empty state / skeleton). Recorded, not fixed: Material menu
  overlays report best-practice `region` (CDK overlay); in the Settings read-only state the older numeric knobs stay
  enabled while only Save is withheld (server answers 403). **Still manual:** a screen-reader reading, forced-colors paint
  (the pane cannot emulate it), WebGL map canvases. Spec-level audit table: every screen (sign-in, la-app shell / Settings /
  Entity Lists / Access, Settings > Link Analysis, Investigation tab sections, all-algorithms group, Installed modules,
  canvas, Alerts grid, data tables) clean in jsdom axe; `color-contrast` and dark scheme are real-browser steps.
* **Still open** (`docs/BACKLOG.md` §3.12): `LA-A11Y-AUDIT-1` (manual items above). The la-app OIDC run against a real IAM
  closed 2026-10-13 ([admin guide](admin-guide.md)).

## Index

*D-3, as built 2026-10-02 to 2026-10-06.* The edge/node index of a Dataset — Parquet under `<Space write root>/la-index`,
built by `IndexBuildService` (`inspecto-la-storage`), exposed by `IndexRoutes` (`inspecto-la-api`). Layout, bucket,
build memory, modes, staleness, build service, reader, served routes and the age gate: [architecture.md](architecture.md)
§4; reasons and measurements: [Index design record](#index-design-record). Below: route contracts and SPA.

* `IndexBuildService.close()` records every cancel BEFORE interrupting the pool, so a builder that throws on interrupt still
  ends `CANCELLED`, never `FAILED`.
* **Routes.** `POST /inv/index/builds` (`{dataset, sourceCol, targetCol, kindCol?, timeCol?, timeColZone?, weightCol?,
  attrCols?, mode?}`, 202 + Location; 409 second live build of the same mapping, the build id named only to its starter;
  422 bad column or estimate over budget) · `GET /inv/index` (viewable Datasets' current versions: rows / bytes / builtAt,
  `droppedNull`, `tables {name: {rows, files, bytes}}`, `versions [{version, bytes, current}]` from manifests, `stale` +
  `reason` + `reasons` + `removedInput` + `fingerprint: known|unknown` + `inputFiles`, `deltas`, `plan`) · `GET …/builds/{id}`
  · `POST …/builds/{id}/cancel`. Every route runs the base-Dataset view gate first: an unviewable Dataset and its builds are
  the same 404 as absent (valid only while no per-Subject row filter exists).
* **Capability and settings.** Start needs `canBuildLinkIndex`; cancel is starter or administrator. `index {enabled,
  max_disk_bytes, keep_versions, threads, queue}`; the estimate (rows × 34 × 2; 0 = no limit) is counted after the duplicate
  check, bounded (10 s, ≤ 2 at once; timeout 422, busy 503, never skipping the budget). Audit `LINK_INDEX_BUILD_STARTED |
  _COMPLETED | _CANCELLED | _FAILED`.
* **Pinned versions (D7-2).** `IndexStore.pins()` (`pins.json`): gc never deletes a version with an unexpired pin (30-day
  TTL, warning in the last 7 days; an unreadable pin file → gc deletes nothing); the reader pool spares pinned readers.
* **Recursive paths from the index** (step 5): only when `index.enabled` is not false, a published index matches source /
  target, covers weight, temporal and every `filter` field (not the weight column), `maxDepth` ≤ 2 and ≤ 20 distinct
  frontier keys per level; else the flat Dataset answers as before. Every answer carries `source`: `{kind:'index', version,
  stale, staleReason?, fingerprint}` or `{kind:'dataset', reason, details?}` (closed reasons `index_disabled`, `no_index`,
  `mapping_not_indexed`, `column_not_indexed`, `time_zone_not_servable`, `filter_not_indexed`, `index_stale_refused`,
  `depth_over_index_cap`, `frontier_over_index_cap`, `index_read_failed`, `rung_not_indexable`, `stale_unknown`). A frontier
  over the cap mid-walk discards the partial answer and re-answers whole from the flat Dataset. Staleness = the
  `GET /inv/index` computation: removed / replaced input, changed relation SQL, different bucket function or unapplied
  deltas → refused (removed rows could be exposed); files only ADDED → served `stale: true`; a DuckDB version difference →
  served stale; unlistable files → served `fingerprint: 'unknown'`. Fingerprint cached 30 s per (Dataset, mapping), dropped
  on build completion. `link.traversed` gains `source`, `indexVersion` / `indexStale` / `fingerprint` /
  `indexStaleReasons` or `sourceReason`.
* **Neighbours and Investigation `expand`** (step 6, 2026-10-03; `IndexedRead.select` shared): `/inv/projection/neighbors`
  folds the value's `out` and `in` copies (self-loop once), flat order `cnt DESC, source, target`, cut at `limit`; needs
  `linkKindCol`, every `attrCols` entry and `filter` field indexed. An `expand` uses the index only for a SIMPLE rung (no
  `window`, `minDistinctDays`, candidate degree bound, merged traversal, `only`; ≤ 20 frontier entities) and reproduces the
  CTE's fold, `excluded`, `linkKinds`, direction (incl. `reciprocal`), `minEvents`, per-anchor `maxFanOut` with
  `fanOutCapped`, and the budget cut, so the sealed `fingerprint` is identical on both paths. The sealed read carries
  `read.index = {version, stale, fingerprint}` BESIDE the fingerprint; `replay` with `reread` adds `indexVersionSealed` /
  `indexVersionNow` to drift rows (`diverged` stays about the fingerprint). Why the flat path answered:
  `read.fallback = {reason, details?}` beside the fingerprint, outside the state hash (DR-U4, 2026-10-10); audit
  `link.investigation.stepped` gains `source` / `indexVersion` / `indexStale` / `fallbackReason`.
* **Graph Run from the index** (step 7, 2026-10-03): `input: "index"` with `dataset`, `sourceCol`, `targetCol`,
  `linkKindCol?`, and `seeds` (1..20) for `degreeCentrality`; only `neighborhood` (hops ≤ 2), `egoNetwork` and seeds-only
  `degreeCentrality` (engine `index`, listed per algorithm in `engines`). Every unservable case is a stated 422
  (`index_disabled`, `no_index`, unfit mapping / column, `index_stale_refused`, non-native algorithm, hops > 2, seeds > 20,
  `at`, an Investigation that hides entities); a walk past 20 keys per level (the final induced-edge pass included) ends
  `BUDGET_EXCEEDED` / `INDEX_CAP`. Pre-work size check uses the seeds' degrees. Response adds `source` and `input.kind:
  "index"`; cache key carries the index version; audit `link.graph.run.*` gains `source` / `indexVersion`. Order
  canonical-v1. Detail: [D-3 design](../../../../archived-documents/plans-archive/la-separation-d3-design.md) §5.6.
* **Incremental append, compaction and the plan** (step 8, 2026-10-03): `mode` `full` | `append` | `compact`. `append`
  indexes only input files added since the live version as a self-sorted delta and publishes the next immutable version
  (parent files hard-linked, manifest `builder: append`, `parent`, `deltas[]`); reads union main + deltas through the
  reader's glob, so every read path answers exactly what a full rebuild answers. Sound only for pure additions — a removed,
  superseded or rewritten file, changed relation SQL, bucket function or DuckDB version, a Dataset that is not a plain local
  store read, no new file, or 8 deltas already is a 409 naming why. `compact` merges deltas by reading the INDEX, restoring
  one file per bucket. `plan {recommended: none|append|full|compact, appendable, reasons, added, removed, changed, samples}`
  is advice only. The per-append `nodes` rows are per-file partial folds (nothing reads them; compaction recomputes).
* **Staleness detail.** Grounded in `DatasetProvider.inputFingerprint`: an added file → `input_files_changed`,
  `removedInput: false`; deleted or touched → `removedInput: true`; a view, > 10,000 files, a listing past its 2 s budget or
  reached after `GET /inv/index`'s 5 s request budget → `fingerprint: unknown` with `fingerprintReason` (`no-files` /
  `too-many-files` / `timeout` / `budget`), never claimed current.
* **SPA: Run on index** (2026-10-03): offered only when the algorithm's `engines` include `index` AND `GET /inv/index` is
  `enabled` with ≥ 1 index (otherwise absent, never disabled-with-fallback). Sends the index's OWN mapping; several indexes
  → an Index picker; client fences state themselves (hops > 2, seeds 0 or > 20, no Investigation, no capability). The
  Explain tab hosts index-only `neighborhood`, `egoNetwork` and seeds-only `degreeCentrality` (*Node to score* chips). An
  index answer is NOT applied to the canvas (no apply path): version, stale chip, a summary. A 422 is worded by
  `indexRefusalMessage` + the server's sentence, never retried on the Working Set. Specs
  `link-analysis-server-run.index.spec.ts`.
* **SPA: Edge index tool** (`LA-INDEX-SPA-SURFACES-1`, closed 2026-10-04; lens `canBuildLinkIndex`, no Access-Catalog
  node): lists version, deltas, stale chip and the `plan` advice; **Build mode** radios preselected from the advice; polls
  the build and refreshes on COMPLETED; a mode the server cannot apply is its 409 in words (`indexBuildErrorMessage`), never
  retried as another mode. *Cancel build* (`indexBuildCancelMessage`). *Index a different mapping instead* (`index-mapping.ts`)
  starts with `mode: 'full'`; a zone never travels without its time column. DR-U5: shows the in-force serving state, an
  Index/Flat line per mapping, and builds the FIRST index. `expandSourceNote` (one `role="status"` sentence:
  `Answered from the link index vN` / `Answered from the Dataset because <words>`, `FALLBACK_REASON_TEXT` in
  `index-source.ts`) also shows under *Find paths (server)* and after a canvas neighbour expand. ⚠ The log step line adds
  `read from link index vN` only when the log view carries `read.index`, which today it strips. Not driven live.
* **Scheduled build** (`la.index.build`, LA-DAILY-INGEST-1 T5, 2026-10-06; wiring: [architecture.md](architecture.md)
  §4, §7): triggered by `pipeline.commit` (`job.dataset.produced` is emitted only by the `sql.template` Job); `attr_cols`
  must be a comma string (a TOON array fails closed `MAPPING_INVALID`); runs only the mode `plan` recommends (`append`;
  `compact` then `append` at the 8-delta cap; `full` only with `allow_full`); a server refusal is one recorded `REFUSED`,
  never a retry; audit `LINK_INDEX_SCHEDULED_RUN`. The `owner` is server-stamped on save unless the saver holds
  `canConfigureAccess` (`JobAuthority.stamp`, at every write door incl. Space Template seeding); the run reads every input
  only from the saved config. Config shape and residuals: [roadmap](../../../../superpower/link-analysis-roadmap.md) "T5
  as built".

## Index design record

*Scope, decisions, measurements, seams.* Retired design
[archived](../../../../archived-documents/plans-archive/la-separation-d3-design.md); steps 1-8 built; step 1 was a gated
spike (`InvIndexSpikeBench`).

**Scope: Path A (operator 2026-10-02), set by the spike.** At 10^8 edges on one laptop (i7-9850H, 32 GB, DuckDB 1.5.2,
warm, heavy-tailed, mean degree 5): one hop through the sealed path **43 ms** (flat unsorted file 1,031 ms); full build
**368 s** (peak working set 17.9 GB); the Java-driven 5-level walk **3.0 s / 4.3 s p50 against a 1.5 s criterion: FAIL**.
Only an equality on ONE key reaches the scan as a zone-map filter; a multi-key `IN`, `OR`, `VALUES` join or `unnest` read
most row groups (~72% of rows at 50 keys: `IN` 3.2 s, `VALUES` join 4.2 s), and per-key `UNION ALL` is linear (0.7 s at 20
keys, 2.2 s at 50). So the index serves single-entity lookups, one hop both ways, **depth ≤ 2 and ≤ 20 keys per level**
(`IndexedTraversal.MAX_DEPTH` / `FRONTIER_CAP`, the same 20 in `SqlGraphEngine`) and seeds-only `degreeCentrality`; deep or
wide multi-hop, global and iterative algorithms and bidirectional BFS stay on the flat CTE and the in-memory engine. Depth 3
is derived (~1.4 s, no margin), never measured, so it is out.
⚠ **Where the index loses:** through the route every request pays a fresh sealed connection (~100-200 ms); at 10^6 edges it
is NOT faster (one hop 209-466 vs 293-373 ms flat; a 20-key depth-2 walk loses ~3x); from 10^7 it wins 2-5x for a hop and
1.5-3x at depth 2; 10^8 not re-run through the route. Hence `index.enabled` defaulted false until 2026-10-10 (operator: on
by default; an unfresh index still falls back).

**Layout details beyond architecture §4.** `CURRENT` is a one-line pointer (temp file `force`d then `ATOMIC_MOVE`). Ids are
RAW values cast to VARCHAR (masking stays at the route, after the caches, so a mask-mode change needs no rebuild); `ts` naive
UTC, the effective zone in the manifest. Size is 34 bytes per edge for all three tables (3.43 GB at 10^8, 1.28x the flat
file), so the disk estimate is `rows × 34 × 2` with no node term. Classes: la-storage (host-free; DuckDB JDBC its only
non-`inspecto-*` need) `BucketFunction`, `IndexMapping`, `IndexManifest`, `IndexStore`, `IndexBuilder`, `IndexBuildService`,
`IndexPlan`, `IndexReader`, `IndexedTraversal`, `SqlGraphEngine`, `RoutingGraphEngine`; la-core ports `DatasetProvider`
(`relationSql`, `inputFingerprint`, `relationSqlOverFiles`), `InputFingerprint`, sealed `GraphInput`, `IndexCapExceeded`;
la-api `IndexRoutes`, `IndexedRead` (the ONE gate, closed `Reason` enum), `IndexedRecursivePaths`, `IndexedNeighbors`,
`IndexedExpand`, `IndexStaleness`, `InputFingerprintCache`, `IndexBuildServices`. `ALLOWED` in `check-module-deps.mjs` and
each pom's enforcer list agree (la-storage → la-api and la-core → la-storage stay red); `bundle-modules.mjs` stages
la-storage. `IndexReader` seals its sandbox with `sealAllowing` = that version directory only,
`enable_external_access=false`, configuration locked. The builder's own DuckDB takes only trusted relation SQL from the port
AFTER the view gate and never sees a Subject.

**Signed decisions (1-8, operator 2026-10-02).**
1. One index per (Dataset, edge mapping) under the Space write root (cross-Dataset and per-Investigation indexes are other
   products).
2. **Row scope (R3) is Dataset-level:** every read runs the base-Dataset view gate (revocation bites on the next read);
   `relationSqlHash` marks a definition change stale. Valid ONLY while a Dataset has no per-Subject row filter; the day one
   lands it becomes a per-scope index.
3. Entity-hash buckets, sort (entity, ts). **Amendment: `md5_number_lower(entity) % N`, not DuckDB `hash()`** —
   `hash()` is deterministic across connections and processes but not reproducible in Java nor proven stable across DuckDB
   versions; MD5 matches Java `MessageDigest` (bytes 8-15 little-endian, `Long.remainderUnsigned`) on 1,004/1,004 ids and
   4,000+ in `BucketFunctionTest`, builds ~16% slower, looks up at the same speed. The manifest records `bucketFn` and
   `duckdb.version` (mismatch = stale). MD5 byte order inside DuckDB across versions is still not proven.
4. The builder is an LA-owned `IndexBuildService` with its own routes (la-* cannot import `JobService`); no `IndexBuildPort`.
   Scheduling later came as the `la.index.build` Job Type through the `link-index` Platform Service.
5. Full build first; append and compaction only on explicit request — staleness is reported, never silently acted on.
6. Serve a stale index flagged, pin the version at Graph Run submit, record `read.index` BESIDE the fingerprint; refuse only
   when removed rows could be exposed. `read.index` is provenance, NOT a replay pin (promoting it needs a decision).
7. One module, `inspecto-la-storage`.
8. Automatic selection for read routes when `index.enabled` is on, always echoing `source`; a Graph Run needs explicit
   `input: "index"` and never falls back to the Working Set.

**Gotchas worth keeping.**
* **Fingerprint cost:** ~0.19 ms per file (1 file 3 ms, 1,000 files 182 ms, 10,000 files 1.9 s; Windows, warm), one listing
  per indexed Dataset per `GET /inv/index`; cap 10,000; cache 30 s, ≤ 256 entries. The manifest records ≤
  `IndexManifest.MAX_INPUT_FILES` = 5,000 files; above that only the hash, so such a Dataset rebuilds in full only.
* **Consignment SQL hash:** a `physicalRef` relation SQL pins the file list, so `IndexBuilder.relationSqlHash` blanks the
  list inside `read_parquet(` / `read_csv(` before hashing (the hash is the DEFINITION; files are tracked by the
  fingerprint). Manifests from before the fix read once as `relation_sql_changed`. A list of ~20 long paths once threw
  `StackOverflowError` in that regex (surfaced as `index build failed: null`); fixed with possessive quantifiers
  (`IndexBuilderTest.theRelationHashSurvivesALongPinnedFileList`).
* **Two copies of the data:** a rebuild peaks at ~2x (old and new coexist until GC; `keep_versions` default 2; a stage
  touched in the last 5 minutes is never collected).
* A non-UTC `timeColZone` makes a temporal constraint unservable (`time_zone_not_servable`: the flat path compares wall
  clock, the index stores UTC instants). A filter on the weight column is unservable (stored as DOUBLE).
* `neighbors` and `expand` fold EVERY row of a value, so a node of millions of rows is bounded only by the 5 s statement
  timeout (then `index_read_failed`, flat answers). When the per-level `LIMIT` yield fence fires the rows differ from the
  flat path's (no `ORDER BY`); only the flag compares.
* An `egoNetwork` of a node with > 19 distinct neighbours ends `INDEX_CAP` (the induced-edge pass looks up one key per kept
  node). Exactness over reach; a hub is a Working Set job.
* Mutation-pinned (each red): Java bucket off by one, partial walk served instead of fallback, view gate removed, frontier
  cap disabled, version missing from the cache key, `index` silently rerouted to `workingSet`, "removed file forces a full
  build" in `IndexPlan`, reader glob narrowed to main files.

**Not built (D-3).** `window` / `at`, hidden entities and `merged` rungs on the index; auto-compact; `IndexSubgraph` and deep
multi-hop (Path A); a completed 10^9 run; cross-Dataset graphs.

## Index scale measurements

*`LA-INDEX-SCALE-MEASURE-1`, 2026-10-05 to 2026-10-21.* Harness `IndexScaleBench` (la-storage tests;
`-Dinspecto.bench.dir`, `-Dinspecto.bench.edges=N`; knobs `deltaEdges`, `maxDeltas`, `skipDeltas`, `skipCompact`,
`buildOnly`, `buildMemory`, `threads`, `samples`; never in the default suite). Method: D-S5 skew corpus (source ~ nodes ×
u^3, target ~ nodes × u^2, nodes = edges / 5 — the id DOMAIN, see the correction below) as real Parquet, a real FULL build,
1-8 appended deltas of 100,000 edges, then `COMPACT`; reads through `IndexReader.edges` and `IndexedTraversal.walk`
(undirected, depth 2) over 60 random keys after one warm-up. Windows laptop, 12 hardware threads, warm.

| Edges | FULL build | Index size | 1 key p50 / p95, 0 deltas | 1 key p50, 8 deltas | 1 key p50, compacted | depth-2 walk p50 / p95 |
|---|---|---|---|---|---|---|
| 10^6 | 4 s | 0.02 GB | 26.6 / 43.5 ms | 35.3 ms | 18.3 ms | 229 / 538 ms |
| 10^7 | 22 s | 0.23 GB | 20.2 / 27.3 ms | 29.9 ms | 15.7 ms | 32 / 489 ms |
| 10^8 | 447 s (32 buckets, 8 GB cap) | 2.23 GB | 20.9 / 31.0 ms | 37.8 ms | not run | 21.8 / 32.0 ms |

* **`K = 8` holds:** ~+0.2 ms per delta on a single key at 10^8 (20.9, 24.4, 27.8, 33.9, 37.8 ms at 0-8); a hub key 36.2 →
  56.2 ms; a 20-key lookup 408 → 766 ms; no cliff; compaction restores the single-file figure.
* **Latency is flat in N** for a key lookup (~20 ms from 10^6 to 10^8). A 20-key frontier is ~0.4 s at every scale (~20 ms
  per key, sequential), so the 20-key cap, not N, bounds a walk; the 5 of 60 walks refused `FrontierOverCap` at 10^8 are hubs.
* **Caveats:** no weight / extra columns (22 B per edge here; does not retire the 34 B estimate); fixed delta size; COMPACT
  not run at 10^8.
* **10^9 is not measured.** Build time grows ~20x per 10x, so ~2.5 h. 2026-10-13: the 10^9 corpus (1,519 s, 20 GB) was
  built but the FULL build stopped at the 30% free-disk floor (21.8 GB written): it needs > ~65 GB working disk beside the
  corpus (a host with ~250 GB free and ≥ 16 GB build memory). Runs at 5x10^8 (8 GB, then 12 GB cap), 3x10^8 and 2x10^8 (6 GB)
  each failed with Out of Memory in the then-global `nodes` phase (5x10^8 at 12 GB: `out` 24.5 min, `in` 52.5 min, OOM at
  11.1 GiB; sampled peak working set 11.77 GB; bench dir peak 22.8 GB). Their "distinct nodes" and "B/node" readings are
  WITHDRAWN (the id domain, not distinct nodes). The `nodes` phase is now bounded (below), but no build above 10^8 has been
  re-run. Recipe for a large host: `tests-index-scale-run.md`.

### The `nodes` phase and the degree statistics, bounded

*2026-10-21, lane `la-nodes-mem`.* **What was wrong.** `nodes` was one statement over the whole edge relation (two global
`GROUP BY`s each with `count(DISTINCT (neighbour, kind))`, a `FULL JOIN`, a global `ORDER BY`); its state is the set of
distinct (node, neighbour, kind) links, about one per edge, so it grows with the EDGE count. The corpus has at most ~2x10^6
distinct ids at any scale (587,878 at 3x10^6 edges, 1,551,076 at 3x10^7, 1,778,237 at 10^8). DuckDB spilled it but not
enough: with a spill directory it died `failed to pin block` at 256 MB for 3x10^7 edges / 8 threads (completed at 512 MB),
64 MB for 3x10^6 / 1 thread (96 MB completed), 128 MB for 10^7 / 2 threads. A global `GROUP BY src, dst, kind` rewrite
died at the same 256 MB (the structure, not the spelling). Failure is not monotone in the cap: at 10^8 the old code
completed at 8, 2 and 1 GB. The degree table's per-key `count(DISTINCT)` had the same flaw (died at 2 GB over 10^8).

**The change** (`IndexBuilder.copyNodes`, `linkStats`): `out` and `in` first; `nodes` folded one bucket at a time from those
written tables (per non-empty bucket one `COPY` of the `FULL JOIN` of that bucket's `out` and `in` folds — a node's two sides
share its bucket), sorted by id, into `nodes/bucket=B/data_0.parquet`. Working set = one bucket (~4x10^6 edges); the relation
is no longer scanned for `nodes` (it was scanned twice). Degree table = `SELECT x, count(*) FROM (SELECT DISTINCT x, o …)
GROUP BY x`.

**Same index.** `IndexNodesFoldTest` (9 cases; the replaced statement kept verbatim as reference, both ways `EXCEPT ALL`,
column types, bucket placement, id order, one file per non-empty bucket, no dir for an empty bucket, `IndexBuilder.verify`;
1 and 5 edges over 64 buckets, 400 over 1 and 7, 5,000 and 40,000 skewed with NULLs, parallel and self edges, a mapping with
neither kind nor time, FULL + two APPENDs + COMPACT vs one FULL). `IndexLinkStatsParityTest` (kind counts, degree quantiles,
histogram, node counts per type; untyped, typed, many-to-one key, two-type endpoints). At scale: two FULL builds of 3x10^7
(old, new) are multiset-identical incl. bucket; at 10^8, 4 of 32 buckets (221,912 nodes) equal the old statement.

**The bound at small scale** (gated `IndexNodesMemBench`): 3x10^6 edges, 64 MB, 1 thread — FULL completes (73 s first run:
out 24, in 25, nodes 9.7, statistics 13), the replaced statement OOMs (60.9/61.0 MiB). 3x10^7, 512 MB, 2 threads — completes
(214 s: out 63, in 62, nodes 14.5, statistics 69.7); the old degree statement OOMs (485.4/488.2 MiB).

**Whole FULL builds at 10^8 edges** (32 buckets, 8 threads, seconds; shared noisy host — phase times indicative):

| `buildMemory` | code | out | in | nodes | statistics | total | result |
|---|---|---|---|---|---|---|---|
| 8 GB | unmodified | 115 | 212 | 420 | 270 | 1024 | completes |
| 8 GB | new | 117 | 235 | 36 | 180 | 575 | completes |
| 2 GB | unmodified | 161 | 179 | 547 | - | - | **OOM in the statistics** (1.8 GiB) |
| 2 GB | new | 146 | 167 | 35 | 235 | 590 | completes |
| 1 GB | unmodified | 153 | 163 | 695 | 316 | 1334 | completes |
| 1 GB | new | 157 | 160 | 38 | 280 | 642 | completes |
| 512 MB | new | OOM in `out` after 88 s | | | | | `out` (unchanged) needs > 0.5 GB with 8 threads |

At 3x10^7 (4 GB, 8 threads): 276 s (nodes 112, statistics 66) old vs 153 s (nodes 14.8, statistics 40) new. `nodes` now
costs ~0.35-0.5 µs per edge at 3x10^7 and 10^8 (old 3.7 and 4.2-7.0). Peak working set follows the cap (1.4 / 2.4 / 8.1 GB
at 1 / 2 / 8 GB: DuckDB fills what it is given), so it is no measure of need — the lowest cap that completed is. Entity
cardinality and the production `index.build_memory_pct`: [architecture.md](architecture.md) §9, §10.

**Not measured.** Any build above 10^8 edges; the `out` / `in` sorts and the statistics' other global steps beyond 10^8; a
corpus with a realistic node count; APPEND, COMPACT and read latency at these caps.

## External references and the Dossier bundle

*D-6 / D-7, as built 2026-10-03.* Integration by reference, never by trust; decisions in the
[Integration design record](#integration-design-record-d-6).

* **External references.** `GET` / `POST /inv/investigations/{id}/references`, body `{system, type, id, url?, label?}`,
  append-only `references.jsonl` beside the op log (`SnapshotStore.appendReference`), OUTSIDE the header and the Dossier
  manifest. No edit or delete; a duplicate `(system, type, id)` and the 201st are 409. `POST` is `canManageIncidents` and
  lead; `GET` the read gate. 🔴 **Never trusted:** `trusted:false`, never dereferenced or read by the analysis, grants no
  access (only `PUT …/case` shares). `url` absolute `http`/`https`, a host, no credentials, else 422. Audited
  `LINK_INVESTIGATION_REFERENCE_ADDED`.
* **Investigation members** (D7-1, D19 amending D-E7; `InvestigationMembers`, `InvestigationMemberStore`,
  `InvestigationMemberRoutes`). Roles and fold: [architecture.md](architecture.md) §3.1. The creator is the implicit first
  lead, so an Investigation with NO `members.jsonl` behaves as owner-only (nothing migrated). `GET …/members` (any member),
  `POST …/members {subject, role}` and `POST …/members/revoke {subject}` (lead; `canManageIncidents`; non-lead 403,
  non-member 404); last lead 422; audited `LINK_INV_MEMBER_GRANTED` / `_REVOKED`. **The gate** is `InvestigationRoutes.open*`:
  every member READS (log, Working Set, Dossier and bundle, measures, coverage, references, Case link, replay, Graph Runs,
  the list); only a lead WRITES the main log (ops, undo, reorder, template, alert rules, Case link, references, reveal); a
  Case member reads only, never replays; four-eyes approval is lead or reviewer once a members record exists (any capability
  holder before). R3 and the PDP judge every member and only narrow: the PDP resource carries `members`, a DENY hides the
  Investigation even from a lead and its 403s are withheld behind it.
* **Drafts** (D-7; D7-3 to D7-6, D16-D21; `DraftStore`, `DraftRoutes`; shape and states:
  [architecture.md](architecture.md) §3.3). ONE live Draft per member (D17: a second fork is 409 naming the first). The
  writer is the main log's (`SnapshotStore.appendStepAt`); steps CONTINUE the numbering. **Base-state rule:** state = the
  evaluator over main entries 1..k + the Draft's own — nothing copied, so a later main step never changes it (`behind` /
  `stale` report how far main moved); `baseLogHash` fails a Draft CLOSED (409) if the main prefix was rewritten. Routes
  `…/drafts` (POST fork, GET list), `…/drafts/{draftId}` + `/log`, `/working-set` (same cached relation, masking after
  cache), `/replay` (full re-fold incl. persisted set hashes), POST `/ops`, `/undo` (same validation; a Draft undoes only
  its own ops; a four-eyes-sensitive expand is refused 422), `/discard`. Gate: member gate, then a lead or reviewer reads
  another's Draft (D7-Q8), an analyst gets 404; only the ACTOR writes (lead or reviewer 403); the actor or a lead discards.
  **Pins:** fork pins the CURRENT version of every index serving the bound columns (none without an index: reads are sealed
  at use); discard unpins; fork is assembled in a scratch dir and renamed in. The rename (`DraftStore.moveRetrying`) retries
  a Windows `AccessDeniedException` up to 20 times (5-80 ms backoff; an antivirus / indexer handle on a fresh `header.json`
  blocks a directory `ATOMIC_MOVE` — 4 of 50 bench forks hit it), then **503 `STORE_BUSY`** leaving nothing. **Discard**
  keeps `header.json` + `discarded.json` and DELETES log and sets (sealed rows may hold personal data); per-step audit
  events keep the ops. No Dossier, bundle or evidence route for a Draft (D20). Audit `LINK_DRAFT_FORKED / _OP_APPENDED /
  _UNDONE / _DISCARDED` (never rows).
  **Rebase and promote** (D7-5): GET `/conflicts` (writes nothing), POST `/rebase` (`{confirm?, expectHead?}`, actor), POST
  `/promote` (`{expectHead?}`, actor or lead). Rebase replays EFFECTIVE ops (undone ops compacted, renumbered M+1..) over the
  current head, re-seals each expand against freshly pinned versions and swaps the Draft in (`replaceRebased`, two renames,
  old restored on failure). Each rebased state is serialised once (canonical bytes give both `workingSetHash` and the set
  file); the fold runs before the main lock (`DraftRebase.verify`; the lock re-checks byte-identical): 800 steps 36.7 s →
  8.4 s (`LA-DRAFT-REBASE-COST-1`, `DraftRebaseCostTest`). A linear rebase was declined 2026-10-04 (the cost is the
  whole-state sealed hash; a chained hash would change every sealed hash and the promote byte pin). Ops are classed
  `no-op` / `changed` / `superseded` / `blocked`; the last two must be named in `confirm` (D7-Q7), else 409. A Draft's
  expand reads ITS pinned version (no longer published → flat Dataset); a pin past 30 days refuses ops and promote with 409
  "must rebase"; a rebase re-pins. Promote needs base == main head and an undo-free Draft, re-verifies under main then Draft
  lock, appends each step with `draft{id, actor, baseStep, step, promotedBy}` provenance, rolls main back on any failure,
  then closes the Draft. Each promoted step reuses the Draft's sealed `workingSetHash` and hard-links its set file, reused
  only when its head carries that hash and step AND its bytes SHA-256 to it; otherwise re-sealed from the fold, whose final
  hash must equal the Draft's (800 steps 34.9 s → 3.3 s warm / 8.3 s cold; linear in set bytes; `LA-DRAFT-PROMOTE-COST-1`,
  `DraftPromoteCostTest`). A promote carrying an expand sensitive under thresholds NOW in force is held (202) on the
  four-eyes queue. Audit `LINK_DRAFT_REBASED / _PROMOTED`.
  **Checkpointed append** (D7-4): `DraftCheckpoints` keeps the last State per Draft (valid while `log.jsonl` keeps size +
  mtime) so an op is one `copy()` + `apply`; an undo still folds; the `baseLogHash` verdict is cached per main log file;
  `/replay` clears and re-verifies. **Admission, hibernate, expiry** (D7-6): Space cap (51st fork 409; hibernated count),
  heavy jobs (rebase / conflict replay, cold relation build, `expand`) under the heavy permits, full cap = 429 at once.
  Idle → `hibernated.json` (checkpoint and cache released; log, sets, pins kept; next access wakes with one cold fold);
  expiry = a discard with `expired:true`, `LINK_DRAFT_EXPIRED`, `expiryWarning` from 7 days out. The three numbers are
  per-Space `drafts` keys (2026-10-04): `max_open` (1..1000, 50), `hibernate_after_minutes` (1..10080, 60),
  `expire_after_days` (1..3650, 30), wire `drafts{…}` + `draftsInForce`; read per call via
  `LinkAnalysisSettings.forRoot(…).effectiveDrafts()`; PUT refuses (422) out-of-range, unknown keys and an expiry not
  longer than hibernation; a hand-edited unordered pair reads as defaults. Settings **Drafts** group: three fields, blank =
  inherit, the expiry > hibernation rule shown client-side. Idle time = `DraftLifecycle.touch` (memory + `accessed.json`
  every 5 min); the sweep is lazy on list / open / fork. `drafts/index.json` is a rebuildable listing index;
  `DraftStore.recover` restores or deletes `.old-<id>-*` left by a crashed rebase after verifying the header. Detail:
  [`la-separation-d7-design.md`](../../../../archived-documents/plans-archive/la-separation-d7-design.md) §4-§6, §12.
* **Dossier bundle.** `GET …/dossier/bundle?at=&snapshots=` returns `inspecto-dossier-bundle/1`: masked Dossier, masked
  references, `custody {manifestRoot, referencesCount, referencesHash}` and a SHA-256 `seal` over the canonical JSON of
  everything but `seal` and `generatedAt`. `POST …/dossier/bundle/verify` (a bundle, or `{bundle}`) answers `{verified,
  sealIntact, rootMatches, referencesIntact, referencesAddedSince, problems, custody}` — the seal, the embedded root, that
  the bundle's references are still the first N, then the SAME manifest comparison as `/dossier/verify`
  (`DossierRoutes.verifyManifest`). A failure is a result, not an error. Audited `LINK_DOSSIER_EXPORTED` /
  `LINK_DOSSIER_BUNDLE_VERIFIED`.
* **Masking, R3, four-eyes.** Masked as it leaves (references too); manifest and `referencesHash` hash the RAW store, so the
  root is identical masked or not and a masked bundle verifies; the seal covers what shipped. Read gate = the Dossier's. A
  pending sensitive expand is not in the sealed log, so not in the bundle.
* **Gotchas.** Offline-checkable: the seal and the manifest's own root; whether the store still agrees needs the verify
  route. `DossierRoutes` was split (`parseAt`, `parseSnapshotIds`, `maskedDossier`, `verifyManifest`) so Dossier, verify and
  bundle share one build. `POST …/references` is a `CapabilityManifest` entry, the bundle verify a read-shaped exemption;
  all four routes are in `AbsentModuleRoutes` and `openapi-v1.json`. Trust between installations (I1): not built, no live
  call needs it.

## SPA separation record (D-5)

Retired design and edge-classification companion
[archived](../../../../archived-documents/plans-archive/la-separation-d5-design.md); steps 1-7 built. As-built placement:
[Product, code layout and the studio](#product-code-layout-and-the-studio).

**Signed decisions (1-7, operator 2026-10-02).**
1. Shared API clients STAY in core behind `@inspecto/core/*`; only LA-only clients moved (`inv.service`,
   `graph-runs.service`, `link-analysis-settings.service`, `geo.service`, `geo-settings.service`, `notes.service`,
   `inv-identity.service`; 1,678 lines). Moving all of `api/` was rejected: its closure leaves the directory (94 files), and
   the barrel lies (40 LA imports of the barrel close over all 72 clients) — measure the injected set.
2. The canvas, `catalog-graph`, `graph-types`, `graph-source`, `entity-key`, `graph-export`, `unique-name` stay in core (14
   host files use the canvas, 14 dialogs `unique-name`); the library holds algorithms, brush, snapshot, filter, history,
   branching engine and `geo`. Two edges the closure could not see (`MultiNodeMapping` / `MultiEdgeMapping`, `ObjectNote`)
   moved to core.
3. In-workspace libraries via `tsconfig` `paths`, NO ng-packagr (offline-safe, one `npm ci`); boundaries are ESLint rules
   (core bans `@inspecto/link-analysis`).
4. Names `@inspecto/core`, `@inspecto/link-analysis`, apps `gamma` (rename deferred) and `la-app`.
5. The OIDC client moved to core (`inspecto/auth`: `SessionService`, guard, interceptor, `pkce`, sign-in and callback) — one
   PKCE implementation, one security review.
6. The LA flavor is a UI flavor, not an edition: `package.ps1 -Product inspecto|la` (`-Ui` removed by `PRODUCT-ASSEMBLY-1`
   PA-2, 2026-11-10). A fifth edition is the promotion path if a customer needs a bundle without the other modules.
7. One bundle ships exactly one SPA (`ui/`); `package.ps1` copies `inspecto-ui/dist/<Ui>` BY NAME and empties the bundle
   directory first.

**Gotchas worth keeping.**
* **The barrel / MapLibre trap.** `public-api.ts` is deliberately tiny; re-exporting widgets and `MapViewComponent` dragged
  MapLibre (1.2 MB) and the widgets into `main` (1.42 → 1.88 MB) because esbuild keeps a module's side effects. Lazy
  consumers import deep paths. `inspecto-ui/tools/check-bundle-shape.mjs` + `bundle-budget.json` (`ui.yml`, plus
  `angular.json` budgets) pin chunk placement and size.
* **Lint scope must follow a move** or it silently stops: the restricted-import and dynamic-import rules name
  `projects/link-analysis/**` and `@inspecto/core/*` and forbid `SessionService` at `inspecto/auth/session.service`
  (mutation-checked; `la-dynamic-import-lint.test.mjs`, `la-static-import-lint.test.mjs`). The design-token guard `ROOTS`,
  Prettier / typecheck globs gained `projects`; vitest needs `../projects/**/*.spec.ts`; `tailwind.config.js` `content` must
  scan `./projects/**` or library-only classes are purged.
* **Hand-kept mirrors name SPA paths** (9 Java parity tests, `check-vocabulary.mjs` path keys and `SOURCE_GLOBS`, doc
  citations): the six `graph-*-parity` fixtures and `branching-parity.fixture.json` moved with their specs;
  `entity-normaliser-parity.fixture.json` stayed in core (`entity-key.spec` reads it).
* **`la-app` token provision** (`LA_APP_TOKEN_PROVISION`, pinned; a token not provided throws by design, only `LA_FEATURES`
  has a safe default — all off):

  | Token | la-app | Behaviour |
  |---|---|---|
  | `LA_DATASETS` | real | Component registry `dataset` kind, no Studio code |
  | `LA_CASES` | real | core `ObjectsService`; `available` follows the `ops` flag |
  | `LA_TAGS`, `LA_TRANSFER`, `LA_AI_ASSIST` | real | core dialogs / components |
  | `LA_FEATURES` | real | live `SessionService` signals from `/bootstrap` |
  | `LA_WIDGETS`, `LA_CATALOG`, `LA_PIPELINE_GRAPH` | stub, `available: false` | one `console.info` per page load; affordance hidden (`GraphSourcesService.sources` filters, `byId` still resolves) |
  | `LA_DASHBOARD_HEADER` | stub | empty component; the Link view widget is never embedded |

  A STRING asset entry in `angular.json` resolves against the PROJECT root and silently copied nothing for la-app — use
  `{glob, input, output}`. The Entity Lists page must provide the empty `InvestigationSessionStore` (NG0201 found only by
  driving the route).
* **Packaging proof** (2026-10-02): both flavors with `-Edition Standard` exited 0 with the boot smoke; the la-app zip's
  index page is titled `Inspecto Link Analysis`; `check-sbom-modules`, `check-launchers`, `check-bundle-platform` and
  `bundle-modules.mjs` needed no change. The la-app IAM client id (`AUTH_OIDC_CLIENT_ID`; launchers default `inspecto-ui`)
  is a deployment decision in `docs/EDITIONS.md`.
* **la-app over Demo User auth** (driven 2026-10-03, no IAM): `-Dauth.mode=demo`
  ([local testing](../../layer/platform/admin-guide-local-testing.md)) is a token relay behind `/auth/exchange`, NOT an
  OIDC provider (no discovery, authorize or JWKS), so the IdP redirect, `state`/PKCE and ID-token validation are not
  exercised. Proven: guard bounce to `/sign-in`, `POST /auth/exchange` 200, Bearer-scoped calls (401 without / forged),
  reload resumes via `/auth/refresh`, sign-out clears `inspecto.session.resumable`. Not driven: token expiry and the
  401-then-refresh retry (`auth.interceptor.spec.ts`); no la-app browser e2e harness exists (backend half `DemoAuthHttpTest`).
* **Real WSO2** (2026-10-05 / 10-09 / 10-10; closed 2026-10-13 as `LA-APP-REAL-SIGNIN-1`, setup and limits in the
  [admin guide](admin-guide.md)): gotchas — `groups` must be an access-token attribute; the post-logout URL must be a
  registered callback; `serve.bat` does not wire `tokenEndpoint`/`rolesClaim` from env; the built-in browser cannot pass a
  self-signed certificate page. Fixed 2026-10-10: an unconfigured OIDC build shows "Single sign-on isn't set up" and does not
  redirect; the authorize request carries a one-shot `nonce` that `/auth/exchange` forwards and `OidcTokenRelay` checks
  against the `id_token`; `OidcTokenRelay.bootstrapAuth()` publishes the public client config from
  `-Dauth.oidc.authorizeUrl` (+ `scopes`, `endSessionUrl`, `clientId`), so nothing is baked in at build.
* **Not built (D-5):** the "licence text" item; a fifth `LA` edition; module federation (signed no); a second `-Dui.dir`;
  renaming `gamma`; a mobile or SSR build.

## Integration design record (D-6)

Behaviour: [External references and the Dossier bundle](#external-references-and-the-dossier-bundle); retired design
[archived](../../../../archived-documents/plans-archive/la-separation-d6-design.md). Integration is BY REFERENCE: no
installation trusts another; a live call (and so trust relationship I1) is needed only by a feature that makes one.

* **D6-1** storage: append-only `references.jsonl` under `audit/` (a reserved import path, so an imported bundle cannot
  plant one), not a header field (the header is write-once; a reference is a relationship, not evidence), hence outside the
  manifest; ≤ 200 per Investigation (check-and-append is one locked step); a fork or instantiated template starts with none.
* **D6-2** never trusted: caller text, never dereferenced, never merged into the Working Set or log; `url` restricted
  because `javascript:` / `file:` / `ftp:` would be an XSS or local-read vector.
* **D6-3** gates: `POST` is `canManageIncidents` (Case work, no new capability).
* **D6-4 / D6-5** the bundle is sealed (SHA-256 over canonical JSON, keys sorted, `generatedAt` outside) and verifiable
  (custody: manifest root and a references-prefix hash; later additions reported, a rewritten one fails; re-sealing an edited
  bundle is caught by custody). The export persists nothing, so it takes no capability. Accepted trade-off: a read-only Case
  member can export what they can already read.
* **D6-6** the embeddable view was deferred on purpose (new principal type, CSP / `frame-ancestors` and cookie decisions, a
  read-only shell; a leaked URL hands masked evidence to a holder with no audit identity) — since built as embed links
  (`LA-EMBED-VIEW-1`, 2026-10-12/13): [architecture-http-and-ui.md](architecture-http-and-ui.md) §6.2.
* **D6-7** no trust between installations: a reference is a pointer, a bundle a file, verification local.
* Proof: `ControlApiDossierBundleTest` (real HTTP, armed Authenticator); negatives mutation-checked (seal always true,
  references unmasked, prefix unchecked, POST not lead-only, URL checks removed, `trusted` flipped).

## Platform-layer record (D-1)

*Module carve-out, as built 2026-10-01* (verified at `ab36c6e9c`: full reactor, 41 modules, 7,319 tests). Link Analysis no
longer needs `inspecto-processor`, `-etl` or `-engine`. Design
[archived](../../../../archived-documents/plans-archive/la-separation-d1-design.md); execution order in
[`la-separation-execution-plan.md`](../../../../archived-documents/plans-archive/la-separation-execution-plan.md). Today's
module map: [architecture.md](architecture.md) §2.

| Module | Holds |
|---|---|
| `inspecto-audit-spi` | `Event` `EventLog` `EventType` (+ `MetricRegistry`); cuts the old `inspecto-event` → `inspecto-etl` edge (`ParquetEventStore` stays in `inspecto-event`) |
| `inspecto-auth-spi` | the LOWER half of the control contract: `RequestAttrs` `WriteRootProvider` `ApiException` `ErrorCodes` `SpiSlot` `WriteGates` `Subject` `Roles` `ComponentAccess` `RowScope` `AccessDecider(s)` `AccessPolicies` `AuditTrail` `AccessGrants` `Authenticator(s)` `CapabilityManifest` `GeoCountryResolver(s)` + the OIDC helpers |
| `inspecto-http-spi` | the UPPER half: `ApiContext` (extends `WriteRootProvider`) `RouteModule` `Handler` `Envelope` `Idempotency` |
| `inspecto-la-core` | model, evaluator, pattern engine, Snapshot store, `LinkEventTypes`, ports `DatasetProvider` / `CasePort` |
| `inspecto-la-api` | LA routes plus `GraphDossierBuilder` `ValueMeasures` `EntityMasking` |
| `inspecto-geo-link` | the **bridge** (artifactId unchanged): `EngineDatasetProvider`, `HostCasePort`, Alert-Rule-bound `InvestigationMeasureRoutes` / `WorkingSetMeasures` |

**Decisions (operator, 2026-10-01).** (1) `ApiContext` is the SPI; host services (`service()`, `spaces()`, `sseStreams()`)
moved to `HostContext extends ApiContext`, reached by `HostContext.of(ctx)`. (2) Package names kept (`com.gamma.control`,
`com.gamma.event`) — a pure move. (3) The 27 `LINK_*` event types moved to `com.gamma.la.core.LinkEventTypes`, values
unchanged (persisted in the audit trail). (4) Entity List routes went to core (SEP-08); host-free statics in
`inspecto-entity-store` `EntityListFacts`. (5) `la-*` may not reach `inspecto-processor`, `-etl`, `-engine`, `-acquire` —
enforced by a `maven-enforcer` rule in each governed pom AND `tools/check-module-deps.mjs` (`ALLOWED`, mutation-checked). (6)
The HTTP / auth cycle was broken, not merged: auth classes used `ApiContext` only through static request-attribute helpers,
now `RequestAttrs` + a one-method `WriteRootProvider`, with delegating aliases so no call site changed.

**Seams.** Two ports, not three: `AlertPort` was dropped (nothing in `la-*` calls the Alert service; Alert-Rule code is
bridge code). An unbound port reports its feature absent (`DatasetProviders.require()` → `503 CAPABILITY_UNAVAILABLE`;
unbound `CasePort` = "ops not installed"), never half-working; both `*Ports` have `forTestAbsent`. `PendingChanges` stays
host-side.

**Gotchas.** A new module is one more place the hand-kept mirrors must agree (the la-api and geo-link manifests'
`provides.routes`, `CapabilityManifest`, bundle lists): list it in its Offering (`offerings/<edition>.toon`, from which
`bundle-modules.mjs` derives the bundle since PA-1), give it a `CLASSPATH_ORDER` position and add it to the static profile
list in `pom.xml` in the SAME commit. An import closure is a prediction, not proof (a first script treated `/*` in a string
as a comment and undercounted 46 → 20 classes, and missed fully-qualified inline references): re-ground with a string-aware
tokenizer and prove with `mvn clean test-compile` of a clean checkout.

**Deferred.** The split package `com.gamma.control` across `inspecto-http-spi`, `inspecto-auth-spi` and
`inspecto-processor` forbids JPMS; a rename to `com.gamma.spi.*` only if JPMS or a clean public surface is wanted.

## Closed-plan record

`link-analysis-backlog-plan.md` was retired 2026-10-01 when gate `G-R4` certified
([archived](../../../../archived-documents/plans-archive/link-analysis-backlog-plan.md); §5 holds per-route contracts and
tamper-test narratives). Every item `LA-01`…`LA-24` and decision `D-S1`…`D-U11` is shipped, answered or declined; the
remainder is on `docs/BACKLOG.md` §3.12. This section keeps what no other section states.

### Measured limits: the shape, not the numbers

One host, one browser, one synthetic shape (2026-09-22). The default `dagre` layout has a **cliff between 500 and 750
nodes** (0.9 s → 10.7 s; 16.2 s at 1,000; force ~2.4x dearer at 500) — the reason the projection cap was set at 500; it has
since risen to 1,000 (`LA-CONFIG-LIMITS-1`) and every layout is now capped where it reaches G6 ([spec.md](spec.md)).
Analysis is not the constraint except **betweenness** (425 ms / 2.9 s / 9.8 s at 500 / 1,000 / 2,000 nodes; the other 26
algorithms ~129 ms total at 2,000). 🔴 Suspicion score was first blamed because it was the only thing measured that called
betweenness; the sweep timed only the centrality control's default — **time every option a control offers**. Suspicion
score's measured curve (D-S3) is QUADRATIC: 250 → 103 ms, 500 → 402 ms, 750 → 972 ms, 1,000 → 1,608 ms, 2,000 → 7,277 ms
(doubling nodes ≈ 4.5x time); 25 other algorithms finish under 60 ms at 2,000 nodes. (D-S3 chose 750; the default is now
1,000, see [Limits in force](#limits-in-force).) `LA-07` (Web Worker) CLOSED 2026-09-23, not built (a serialised-graph round
trip plus worker build config does not repay one slow algorithm; reconfirmed by PERF-D5).

### The model's closed rules

The Investigation is the program, the Working Set its derived state, the Artifact immutable and anchored to a log position.
The op vocabulary is **closed — no new op without a decision entry** (today's evaluable set:
[architecture.md](architecture.md) §3.1); reasons: the backend is safe because it is narrow (`SAFE_IDENT` + bind
parameters), a closed set renders mechanically as numbered plain-language steps for a court, and every open query language
converges on SQL. `undo` is a recorded log edit, not an op; `resolve` (LA-17) and `connect` came under their own decisions;
an op outside the vocabulary answers 422 *"unknown"*. The log is **ordered and non-commutative** (`exclude → expand` ≠
`expand → exclude`; re-ordering forks); **`hide` ≠ `exclude` ≠ `keep`** (hide: gone from display, still traversed and
counted; exclude: gone from all three, inspectable as `excluded`; keep: protected from later excludes). Two evaluators, one
spec (incremental on append, full replay), with an `equivalent` check pinned to be able to FAIL. **Negative space is part
of every rendering** (G-E10).

### Investigation ops: threshold, snapshot, temporal and compare

* **`threshold {min?, max?, measure?}` and `snapshot {label?}`** (2026-10-04/05, `LA-INVESTIGATION-OPS-DEFERRED-1`, the
  narrowest reading of plan §2.2). `threshold` measures each entity over the whole Working Set: `degree` (default, absent from
  the entry so older hashes replay; distinct counterparties, hidden still counts, self-loops don't), `weightedDegree` (links
  touching it, each kind + direction once — the multigraph degree) or `eventCount` (sum of folded `count`). ⚠ Call: the
  Working Set carries ONE weight, the folded count, so sum-of-weights would equal `eventCount`; renaming is one line if the
  operator prefers. Band `min` inclusive / `max` exclusive (≥ one bound, `min < max`, no `ids`); outside is excluded as
  `exclude` (keep protects; reason `threshold: <measure> outside [min, max)`; never re-admitted by a later expand), measured
  ONCE (no cascade). `snapshot` is a **marker**: moves nothing, writes no file (the log position is the artifact). Both ride
  `/ops`; templates carry both. SPA `investigation-band-ops.component.ts` (*Degree threshold*, *Log marker*, a *Measure*
  select) with a client mirror of the 422s; the log line is the server's `text`.
  **Declined, reasoned (2026-10-05):** `snapshot` sealing a `/inv/snapshots` Artifact (every log position is already
  reconstructible and verified, and that Artifact is client-computed; the SPA snapshot dialog meets the need); a
  re-evaluable `threshold` (an exclusion deletes the entity's links and later expands drop rows touching it, so a
  re-measure would be on a truncated graph; fixing it means shadow links in the state and hash, changing every old log —
  the honest path is a later `seed`).
* **The `temporal` op** (2026-10-05): `{op:"temporal", mode:"burst"|"periodicity", series?, windowSeconds?, minEvents?,
  maxCv?, limit?}` — a marker (Working Set and hash do not move). It runs the SAME scan as `POST /inv/pattern/temporal`
  (`PatternRoutes.scan`: gates, four-eyes refusal, row cap, index serving) over the Investigation's OWN dataset and columns,
  narrows to the Working Set at that position, and seals `entry.temporalFindings` (`{dataset, params, workingSet, count,
  results, outsideWorkingSet, truncated, rowCapped, skippedNoTime, timeNote, servedFrom, readAt, sealed:true, basis,
  fingerprint}`; fingerprint SHA-256 over content, not `basis`/`readAt`/`servedFrom`). Knobs validated with the route's
  bounds (422), stored with defaults filled. Calls: (1) the Investigation's columns, never the request's; (2) a link finding
  kept when some Working Set link has that source and target, an entity finding when admitted — the rest counted in
  `outsideWorkingSet`, never named; (3) the scan reads at the route maximum (1,000) and `limit` cuts after narrowing; (4) the
  Investigation's window is NOT applied; (5) a time column is required. Append, `replayOp` (Draft rebase / promote) and
  `reorder` RE-SEAL over the new Working Set; replay never re-reads; the log view carries counts
  (`TemporalFindings.summary`); `GraphDossierBuilder` hashes the fingerprint (a forged count is `integrity` red, `/dossier/verify`
  names `log.jsonl#N`) and adds `temporalFindings [{step, findings}]` only when an effective step carries one; masking at
  render; templates drop it (`CASE_OPS`). Test `ControlApiInvestigationTemporalOpTest`. SPA *Seal these findings* sends the
  knobs on screen (`sealTemporal`); the server re-runs the scan, so what is sealed is its answer. Dossier HTML renders
  *Comparisons* and *Temporal findings* only when present (every other Dossier byte-identical).
  ❌ **Windowing the scan DECLINED (2026-10-05):** the scan is shared with the stateless route and the index path (neither
  has a window); a windowed seal would differ silently from what the panel shows; clipping changes the statistics (a burst
  cut at an edge, a `cv` over a clipped series); old logs are not at risk. `compare` with `window: "inherit"` and activity
  mode covers the need.
* **Comparison read** (2026-10-04): `GET /inv/investigations/{id}/compare?aFrom&aTo&bFrom&bTo&timezone&at&mode&minAbsDelta`
  (`InvestigationComparisonRoutes`, `WindowComparison`) diffs two windows over the Working Set at `at`: links and entities in
  A only, B only, both (`countA`/`countB`), `neither`, each capped at 500 with the true `count`. *Why a read:* a sealed Working
  Set has no timestamps, so a time-sliced diff needs the LIVE Dataset (`sealed: false`). Universe = the Working Set's
  entities and links only (never-admitted pairs and excluded entities never appear); present = ≥ 1 event in the window;
  rungs not applied; one shared optional `timezone`, no slot / day mask; needs `timeCol`; > 50,000 links in one window is
  422 (no silent sample); ≤ 2,000 entities. Masked at render (names nothing new; a `masking_mode: all` test pins it).
  Access `openForRead` + R3; audited `LINK_INVESTIGATION_COMPARED`. The Dossier carries a `comparison` section ONLY when the
  same parameters are passed, labelled unsealed, outside the manifest. `mode=activity` and `minAbsDelta` as for the op.
  Test `ControlApiInvestigationComparisonTest`.
* **Comparison as a SEALED op — `compare`** (2026-10-05): `{op:"compare", windowA, windowB, mode?, minAbsDelta?}` (each side a
  `window` object via `InvestigationTime.window`, so slot / days / timezone work per side; needs `timeCol`). A marker;
  `WindowComparison.compare` runs over the Working Set *before* the step and `seal`s it (JSON-normalised, `sealed: true`,
  fingerprint over `{windowA, windowB, workingSet, links, entities}`, no `readAt`, so the Dossier root stays deterministic).
  Custody: the diff is inside the hashed `log.jsonl#<step>` line; `GraphDossierBuilder` recomputes the fingerprint per step
  (a hand-edited `eventsA` goes red on verify AND integrity); the Dossier gets `comparisons [{step, comparison}]` (absent
  when none). Fork and Draft promote RE-SEAL; templates DROP it (`CASE_OPS`). Log view: counts only. No new route. Test
  `ControlApiInvestigationCompareOpTest`. **`window: "inherit"`** per side: params keep `"inherit"`, the seal carries the
  concrete window it resolved to plus `inherited: ["B"]`; refused 422 when the Investigation has no window at that step.
  **`mode: "activity"`**: adds `activity {links:{changed, unchanged}, entities:{…}}`, `changed` capped at 500 with
  `delta = countB - countA`, sorted by |delta| then id; an entity's count = in-window events on its Working Set links
  (self-loop once). **`minAbsDelta`** (≥ 1, activity only, else 422): smaller moves counted under `belowMinDelta`, floor
  echoed. `mode`, `activity`, `inherited` and the floor join the fingerprint ONLY when present, so earlier seals verify
  byte-identically. SPA `InvestigationCompareOpComponent` (two window field sets, per-side *Use the Investigation's window*,
  *By event count*, *Smallest count change to list*). ⚠ DR-D1: every plain `<form (ngSubmit)>` must import `FormsModule`
  (`ReactiveFormsModule` alone leaves the native submit uncancelled and the page reloads; specs click the real button and
  assert `defaultPrevented`). DR-D3/D4/D6 (2026-10-10): a successful *Seed* sets `showWorkingSet` true; server-run controls
  take `[investigationOpen]`; Value Measures prefill from the Investigation; 413/429 through `limitRefusalMessage`.

### Timeline playback

*2026-10-05, SPA only* (`link-analysis-timeline-playback.component`, `timeline-playback.ts`). Steps the timeline column's
`[min, max]` in 20 equal slices (play 1 s per slice, pause, previous, next, scrubber, reset) and highlights links whose
date falls in the slice (half-open; the last slice includes the max) through canvas `emphasis`. Writes NOTHING to the graph
or the time filter; a link without a parseable date is in no slice. `role=status` names slice and count (`aria-live` off
while playing). Play is offered under `prefers-reduced-motion` too (it starts only on a press; WCAG 2.2.2; operator
2026-10-29). Calls: the range is the TIMELINE column's (a Working Set has no per-link timestamps, so playback is query-graph
only); an aggregated link plays at the one date it carries; fixed 20 slices.
**Window, age fade, Seal this window** (`LA-PRODUCT-NEXT-1` phase 4, 2026-10-30): *Window* = This slice (default) / Last day /
7 / 30 days / From the start, reaching back from the handle; *Age fade* (on by default) runs opacity from 1 at the handle to
0.45 at the window start (above the 0.2 out-of-window dim); *Seal this window* (Investigation open, `canManageIncidents`,
the Investigation's event-time column = the timeline's) appends a `window` op with `[from, to)` UTC instants. Calls: links
outside are dimmed not hidden (hiding re-runs layout every tick); the scrubber is not recorded by Undo; dates are read like
the timeline filter (`Date.parse`, a naive timestamp in the browser's zone) while the server reads `timeColZone`, so on
such a Dataset a sealed window can sit an offset away from the highlight.

### Temporal patterns: time-respecting paths, burst and periodicity

*2026-10-04/05.* Built as **stateless Dataset reads**, not ops or graph-run algorithms: the Working Set and
`/inv/graph/runs` carry counts, never edge timestamps.
* **Time-respecting paths** already existed: `temporalConstraint {timestampCol, monotonic, maxTotalDurationHours}` on
  `/inv/traversal/recursive-paths` since LA-11 (*monotonic* = non-decreasing, ties pass). Added
  **`temporalConstraint.maxGapHours`** (positive; 422 without `monotonic: true`); flat CTE and index walk both apply it
  (`ControlApiInvIndexedTraversalTest`). It only removes paths; gates unchanged.
* **`POST /inv/pattern/temporal`** (`PatternRoutes`; maths in `LinkTemporalDetector`; the DuckDB cut and current row cap:
  [architecture.md](architecture.md) §5): `{dataset, sourceCol, targetCol, timeCol, mode, series?, filter?, limit?,
  windowSeconds?, minEvents?, maxCv?}`. Series = event times of one directed link as spelled (not normalised). *Burst:* a
  `windowSeconds` window (default 60, 1–86,400) holding ≥ `minEvents` (default 5, 2–1,000), overlapping windows merged,
  edge inclusive. *Periodicity:* ≥ `minEvents` (default 5, min 3) with gap CV (population σ / mean) ≤ `maxCv` (default 0.1,
  0–1); reports the MEDIAN gap as `periodSeconds`. Out-of-range is 422, never clamped. Strongest first, cut to `limit`
  (default 200, max 1,000). Fences: one bound statement with a row cap (`rowCapped` + `truncated`), 5 s timeout, four-eyes
  `refuseIfSensitive` (403; no Investigation to hold an approval), unparseable times skipped and counted (`skippedNoTime`).
  Times return as wall-clock text with NO zone claimed. Masking: returns only the asked columns, the same exposure as
  `/inv/pattern/branching`. Read-shaped; audited `LINK_PATTERN_MATCHED`. 🔴 DuckDB reserves `at`: a test column so named
  made every read 422.
* **Per-entity series and index serving** (2026-10-05): `series: "entity"` pools every event an entity takes part in (a
  self-loop row is ONE event); findings carry `entity`; the answer gains `series` and `source`. `IndexedTemporal` (via
  `IndexedRead`) serves when an index maps the same columns AND its time column IS the request's `timeCol` in UTC AND every
  filter field is indexed — a full ordered scan of `out` feeding the SAME detection, identical by construction
  (`ControlApiInvTemporalIndexedTest`); otherwise a closed reason. Calls: a new field `series` (not an overload of `mode`); an
  entity series fans the same edge rows out in Java; the index path is a full scan (saves the relation evaluation, not the
  Dataset-size cost); view gate and four-eyes run before the index. Gotcha: `ResultSet.wasNull()` speaks of the LAST column
  read — a `getString` between `getLong` and `wasNull` turned NULL times into 1970 until the equivalence test caught it.
* **SPA overlay** (`link-analysis-temporal-findings.component.ts`, Whole Dataset tab): needs a time column and an edge
  mapping (`traversalTargets`); 🔴 unlike paths and branching it **never adds nodes or links** — each finding is matched to
  drawn links via `resolveEntityId`; undrawn ones stay listed, disabled, counted. States `truncated` / `rowCapped` /
  `skippedNoTime` and the server's `timeNote`; numbers clamped client-side; not wired to a saved view.

### Listing and link notes

*2026-10-01.* `GET /inv/investigations` lists what the caller may read — own, member, and shared through an open linked
Case, each judged by `openForRead` (R3 and a PDP DENY hide it) — `{id, title, dataset, owner, createdAt, headStep, caseRef?,
access, readOnly}`, newest first, `limit`/`offset`/`total`/`truncated`. Link notes are sent by `linkId` (*Annotate link*),
the sealed `linkAnnotations` render, and the Dossier stamps `linkId` on its link annotations.

### Decisions of record

* **D-E3 deferral — byte-identical replay (`G-E2`) needs version-addressable Dataset reads and is the ONLY gate that does.**
  `G-E11` and `G-E3` are met by sealing + fingerprints. A recorded file list was **rejected** as a pin (compaction moves
  files to `COMPACTED_AWAY`; a pin that rots is worse than none). When replay is wanted, copy `ReferenceReader`'s SCD2
  `asOf` read.
* **D-U8 — NO purge** (operator 2026-09-24): no retention period, purge task or legal-hold record; reopen only on a
  regulator or retention policy (pattern: `incident_purge`).
* **D-U9 — per-Collector coverage, shipped 2026-10-05** (`LA-COLLECTOR-COVERAGE-1`): `collectors` attributed through
  `CollectorCoveragePort` (bridge `HostCollectorCoveragePort`): each Pipeline's `batches` CSV (Consignment → output store)
  joined to its `lineage` CSV (Consignment → event-day partition, rows), kept when they wrote the Dataset's
  `physicalRef`/`sourceName`, attributed to `collector().id()`. The Dataset-filename join was **rejected** (lineage `src_id`
  is a member index; the ledger / `file_stages` are opt-in with no list-by-file read; `file_name` optional; basenames
  collide). No evidence ⇒ `assessed:false`, never "no gaps". The Dossier does not seal live coverage. Residuals:
  never-delivering Collectors are invisible ([architecture.md](architecture.md) §10); partition days use the Pipeline's
  pinned zone. `HostCollectorCoveragePort.day()` is pinned for `year=/month=/day=` and `dt=` forms. SPA: a table
  (Collector · days covered of expected · missing days); `assessed:false` prints "Per-Collector coverage is not assessed
  for this Dataset." plus the server's note.
* **D-U11 answers (2026-09-30):** A1 a snapshot captures what is on screen · A2 the cash-out share denominator is ALL
  cash-out · A3 the agent Entity List is read LIVE and its head seq/hash recorded on each firing (`evidence.agentList` /
  `agentListSeq` / `agentListHash`) · A4 a retired list is 409 · A5 value-measure queries run in UTC · A9 `coverage` is
  readable by Case members (reverses D-U10's owner-only) · A10 pinned Widgets are unaffected. Merged-exclude follow-ups: the
  resolution view widens by the SAME matching rule as exclude / expand; only an UNAMBIGUOUS untyped match counts; old dev
  logs replaying not-equivalent are acceptable; the Dossier line states the full outcome.
* **Naming (D-E1):** *Investigation* / *Investigation Template*; the Assistant's RCA run took *Triage* (`GLOSSARY-CASE-1`).
* **`SEP-02`** (2026-10-01): keep the hand-kept la-api / geo-link manifests' `provides.routes`, guarded both ways by
  `GeoLinkAbsentSurfaceParityTest` (a drift incident is recorded in its header); a module-contributed list cannot serve the
  stub, which must exist precisely when the module is NOT on the classpath; a build-time generated descriptor was weighed and
  not chosen.

### `G-R4` certification (LA-11)

5-hop recursive paths over a heavy-tailed 10^6-edge Dataset through the real route: warm p95 **209 / 188 / 171 ms** from a
median-degree / p99 / top-hub start (all < 350 ms; cold p95 388 / 181 / 163 ms over 5 runs). The fix that mattered:
`EXPLAIN ANALYZE` showed the recursion running TWICE (`__walk` read by the paths and by the level widths) →
`__walk AS MATERIALIZED`. The column probe is folded into the walk's session (`QueryExecutor.runPlanned`); the walk has its
own thread cap (install limit `traversal.threads`, else `-Dassist.sql.traversal_threads`; default 4; other sandbox queries
keep `assist.sql.threads`, 2). Harness `InvTraversalBench` (`@Tag("bench")`); method in
[`la-separation-feasibility-plan.md`](../../../../archived-documents/plans-archive/la-separation-feasibility-plan.md)
§7.10.1.

### Gotchas worth keeping

* All `/inv/…` paths are in `docs/api/openapi-v1.json` (an old note said they could not be) — trust the file.
* A DI change is invisible to type-checking; `link-analysis.component.spec.ts` (the only end-to-end snapshot-and-attach
  spec) was found 28/28 red on master (2026-09-23), hiding every regression it guards — read a flow spec's last green date.
* A well-tested evaluator can be DEAD while the live path has none (`evaluateRows` had zero callers).
* Plan claims went stale within days; re-ground against code before acting on any plan row — including these.
* Live check 2026-09-30 (Enterprise demo bundle, three Demo Users): 33/33 steps passed once the demo Dataset classified its
  account columns `ACCOUNT` and `account_links_dataset` existed for identity import.

Design (archived): [`link-analysis-and-graphsource.md`](../../../../archived-documents/plans-archive/link-analysis-and-graphsource.md)
· [`link-analysis-projection-authoring-plan.md`](../../../../archived-documents/plans-archive/link-analysis-projection-authoring-plan.md)
§7 · [`link-analysis-studio-plan.md`](../../../../archived-documents/plans-archive/link-analysis-studio-plan.md) §6–7 ·
[`link-analysis-toolboxes-plan.md`](../../../../archived-documents/plans-archive/link-analysis-toolboxes-plan.md).

## InvestigationStore port

*S0 + S1, as built 2026-10-04; backends compared in [architecture.md](architecture.md) §3.2.* Sidecar records sit behind
`InvestigationStore` (la-core), keyed by id and `Scope(investigationId, draftId|null)`, never a Path;
`InvestigationStores.of(writeRoot)` is the one place a backend is chosen. Writers carry the version they read
(`append(scope, expectedVersion, …)`, `appendMember(expectedCount)`); a lost race is
`InvestigationVersionConflictException`, retried by the route (`untilWon`, 20 attempts) and answered
`409 CONFLICT_STALE_VERSION` if it keeps losing. Lines and sets come back byte for byte (the sealed hashes depend on it).
Drafts are port operations with preconditions (`promoteDraft` / `replaceDraft`); `decide` claims a pending request by
compare-and-set (`replacePending`) before its append and hands the claim back only if the act fails before any append; the
mask key is `maskKey(id)`; per-pod caches are keyed by `cacheKey(scope)` + `logToken(scope)`; an interrupted promote is
finished or undone by `recoverDrafts` (an intent file written before the first main step; `promoting.json` allow-listed in
`ImportLoaderInventoryTest` `NOT_CONFIG`). FS `log()` returns committed lines only (a torn last line ignored). Design:
[`investigation-store-design.md`](../../../../archived-documents/plans-archive/investigation-store-design.md) §13.

* **Postgres selection (S2-S6).** `-Dinvestigations.backend=fs|db` (default `fs`); `db` is the optional
  `inspecto-la-store-pg` (`InvestigationStoreProvider` by ServiceLoader): one schema `space_<id>` per Space (the platform's
  `OperationalDb.schemaFor` naming re-implemented, since la-core cannot see core; validated `[a-z_][a-z0-9_]{0,62}`; ids
  `COLLATE "C"`), one pooled HikariCP source per database, schema created once under a transaction-scoped advisory lock.
  Connection `-Dinvestigations.db.url/.user/.password`, each falling back to `-Dinspecto.db.*`. **Fail closed:** unknown
  backend, `db` without the module, no / non-PostgreSQL URL, unreachable or vanished database → `503 CAPABILITY_UNAVAILABLE`
  (SQLSTATE class 08 / 28 / 53 / 57P or a pool that cannot lend = the same); nothing cached, the next request retries; never
  falls back to the filesystem (that would fork the evidence). Main log, sets, members and references are append-only by
  trigger (`WHEN (OLD.draft = '')`, SQLSTATE 23000, PostgreSQL 14+ for `CREATE OR REPLACE TRIGGER`; a trigger not a REVOKE
  because the app connects as table owner; a DBA can drop them). A Space that switches backend starts empty (no importer,
  D-IS10). Operator 2026-10-05: no per-set cap yet; no boot-time refusal (`db` judged on first use per Space); process-global
  switch.
* **PG locking:** `SELECT … FOR NO KEY UPDATE`, not `FOR UPDATE` (deliberate deviation from D-IS4: a Draft append's FK takes
  `FOR KEY SHARE` on the Investigation row, so `FOR UPDATE` would deadlock promote vs Draft append). Order Space row →
  Investigation row → Draft row; no advisory lock except the bootstrap one. Deliberate PG/FS differences: `recoverDrafts`
  no-op; `logToken` = entry count (main) / `version:state` (Draft); promote seals a Draft set by `INSERT … SELECT` (D-IS12 a);
  `touchDraft` writes at most every 5 min; a write to an Investigation with no header row is an IOException.
  `drafts/index.json` and `.fork-* / .rebase-* / .old-*` do not exist on PG.
* **Two-JVM race proof (S7, 2026-10-05).** `InvestigationStoreTwoJvmRace` (append chain, `decide` CAS, mask key, promote)
  found the filesystem store NOT safe across processes (3 of 4 cases tore; mask key held); `FsInvestigationStore` now takes
  an OS `FileChannel.lock` on `<investigation>/.store.lock` inside the Investigation monitor (order: monitor, file lock,
  Draft monitor; Draft-scope appends take the Investigation monitor first; recovery waits for a live promote). Creates,
  forks, references, Case link / Alert Rule binding and the Space-wide Draft cap remain per-JVM (last-writer-wins).
  Known race-only deviations: a fork pins the index before the D17 / cap judgment (a refused fork pins then unpins);
  promote / rebase 409 share one message for two causes; the expiry marker is built under the Draft monitor but idle time is
  read just before.
* **Proofs.** Postgres passed the shared contract and the race harness (4/4) on PG 18.6 (2026-10-05); end to end 2026-10-13
  the contract ran 45/45 and an Advanced `la-app` bundle on `-Dinvestigations.backend=db` seeded and smoke-tested
  `la-showcase` (17/17, again after restart; recipe in
  [`admin-guide-variants-runbook.md`](../../solution-pack/la-showcase/admin-guide-variants-runbook.md) §A); 2026-11-01
  57/57 (contract 47, race 4, embed grants 6) incl. `updateDetailsIsOneReadModifyWriteAndAnAbsentInvestigationWritesNothing`
  (8 writers × 25 edits, none lost). Run recipe (javadoc of `PgInvestigationStoreContractTest`): env
  `INSPECTO_TEST_PG_URL` (not `-D`), `-DforkCount=0`, the driver in the owning module, `MAVEN_OPTS=-Duser.timezone=…`;
  per-test SKIP without a URL; the contract runs a 4-connection pool against 16-writer races. Operator-run mutation checks
  (FS + PG mutants 1-14) are still OWED.
* **S0 measurements:** ~291 B per entity per sealed set (10,000 = 2.9 MB; 100,000 = 29.1 MB); 10,000 entities × 2,000 steps
  ≈ 3,061 MB per Investigation; an expand log line ~74 B per sealed row (5,000 rows = 370 KB); FS `readLog` p50 9.2 ms and
  `prefixHash` p50 16.8 ms at 2,000 steps / 5.95 MB. NUL is written as the escape `\u0000`.
* **Packaging:** staged with the Advanced and Preview bundles; `package.ps1` launcher classpath and service-file check; Space
  id = the directory above `config` in the write root, `default` for the legacy root. Out of the seam: the Parquet index,
  pin ledger, snapshots / attachments, Dossier bundles; sweeps run in-request.

## Consolidated as-built facts

Carried over 2026-10-05 when eight LA plan / design docs were archived into the
[roadmap](../../../../superpower/link-analysis-roadmap.md); only facts not stated above are kept.

### Separation and D-7 Drafts

* `SnapshotStore.appendStep` delegates to a static `appendStepAt(dir, …)`; a Draft appends via `DraftStore.appendStep`, which
  takes the log line back if the set file cannot be written (main keeps log-first order). Promote lands sets via
  `SnapshotStore.appendStepSharingSet` (hard link, byte copy where unsupported); the reuse test is that the set file's head
  is exactly `{"hash":"<h>","step":<n>,"workingSet":` and its bytes SHA-256 to the sealed hash (operator rule "nothing
  unverified reaches main", 2026-10-03).
* Promote refuses a Draft holding an undo entry (a log compacted without renumbering is not the state shown); rebase first.
* Four-eyes: a Draft cannot hold a sensitive expand (422 at append); sensitivity is judged at PROMOTE; approve re-checks the
  Draft log hash and main head (409 "deny and request again"); rebase re-reads without approval (a Draft's reads are private).
* Real-state D-S5 bench at 10^8 edges: open p50 37 ms; 0.4 MB heap per Draft (50 ≈ 20 MB); light p95 28 ms solo vs 815 ms
  closed loop (CPU saturation, 20 threads on 12); a saturated heavy limit moves light p95 28 → 43 ms. `DraftConcurrencyBench`
  lives in `inspecto-la-api` (la-storage cannot see `DraftAdmission` / `DraftPromote`).
* `IndexStore.gc` runs under a per-directory pin lock (JVM monitor + `.pins.lock`); an unreadable `pins.json` THROWS and gc
  deletes nothing; pinned append versions survive their parent's gc (per-file hard links).
* Plan-vs-code corrections: access was never purely owner-only (LA-24 Case member, D-U7 approver); no Dataset version exists;
  op log and Working Sets are JSON and the evaluator never touches DuckDB; the evaluator is TOTAL (an op naming an absent id
  is a no-op — what makes fork / rebase evaluate).
* Draft role matrix: a lead gets 403 writing another's Draft; a revoked actor's Draft freezes (a lead may read / discard); the
  owner is only the FIRST lead; legacy (no `members.jsonl`) approver = any `canApproveLinkExpansions` holder; 403 is raised
  only after R3 + PDP, so a policy DENY still reads 404.
* Fork atomicity: staged in `drafts/.fork-*` (a leading dot never matches `DRAFT_ID`); pins taken first, released on failure;
  the one-per-member check + pin + rename under the Investigation's `drafts` lock; server-generated `draft-<uuid>` matched
  EXACTLY (422 otherwise); fork answers 201 + Location.
* Rebase: the Draft header is REWRITTEN (not write-once) with `rebases[]`; steps carry `rebasedFrom`; commit takes main lock
  THEN Draft lock; a rebase at the current head is legal (renews an expired pin); `IndexedRead.select(…, pinned)` /
  `IndexedExpand.attempt(…, pinned)` take `mappingHash → version`.
* D-S4 graph traps: closed-graph assumption (a dangling edge leaks a phantom neighbour); multigraph rules (degree counts
  parallel edges and a self-loop twice; kCore / triangles / cliques collapse them); `hits` tie noise; `GraphAlgorithms.Edge`
  carries no weight (weighted functions take an edge-id map); an iterative mutant can hang the suite (parity tests need a
  timeout).
* SPA D-5 prep: `no-restricted-imports` ignores `ImportExpression`, hence the `no-restricted-syntax` selector set
  `laDynamicImportSelectors`; `<inspecto-la-host-slot>` (`LaHostSlotComponent`, `ngDoCheck` inputs, subscribed outputs) exists
  because `NgComponentOutlet` cannot apply inputs / outputs.
* Packaging trap: a static Maven profile module list must stay in step with `tools/bundle-modules.mjs`.

### Entity model and live detection

*Entity List storage and the fact chain are outlined in [entity-list spec](../entity-list/spec.md) and
[architecture.md](architecture.md) §3.5.*

* **Entity Types:** normaliser closed enum `default | digits | e164 | upper-trim`, Java and TS identical via
  `entity-normaliser-parity.fixture.json` (`entity-key.spec.ts`, `EntityTypesTest`). "Whitespace" = the JavaScript `\s` set
  on both sides (Java `\s`, `trim()`, `strip()` miss NBSP); final sigma folds to sigma. Settings validation (422, never
  clamped): id `^[a-z][a-z0-9_]{0,31}$` unique, `entity` reserved, non-blank label, a classification claimed by ≤ 1 type, ≤ 64
  types, a stated list replaces the defaults and may not be empty.
* **Phone numbers (SP5, 2026-10-06):** the sealed `e164` normaliser keeps a national number national (no default country, no
  trunk-prefix strip), loses the `+` after a leading `(`, does no length check, so only `+` ids are longest-prefix
  enrichable. DuckDB longest-prefix enrichment of 10^7 numbers ~2 s; a new-only daily upsert ~4 s vs ~10 s full re-derive
  (10^9 extrapolated). Evidence and the open normaliser decision: roadmap "SP5 findings".
* **Entity List wire contract:** `GET /entity-lists` → `{lists:[summary], headSeq, headHash}`, summary
  `{id,title,purpose,normaliser,entityType,size,retired,createdAt,createdBy,lastSeq}`; `GET /entity-lists/{id}?at=<seq>` →
  summary + sorted masked `members` + `atSeq` + `headHash` (`at` past head 422; absent at `at` 404). `POST /entity-lists`
  `{id?,title,purpose,entityType,reason}` → 201; purpose `allow|block|watch|exclusion`; id `^[a-z0-9][a-z0-9_-]{0,63}$`; an
  ever-used id 409. `POST …/members` `{add?,remove?,reason}` → 200 with `changed` (empty after normalising or in both sets
  422; ≤ 5,000 values; a no-op writes nothing; retired or type out of force 409). `POST …/retire` (already retired 409).
  Every write: non-blank `reason`, `canManageIncidents`, no write root 503, unknown 404; title ≤ 200, reason ≤ 1,000, value ≤
  512. Reads need only Space access (503 without a write root). Audit `ENTITY_LIST_CHANGED {listId,kind,added,removed,seq}`
  (counts).
* **Fact log:** `<write root>/audit/entity-facts/<12-digit seq>.json` (`seq, at, actor, reason, kind, listId?, …, prevHash`),
  published temp file + fsync + hard link (atomic no-replace on every OS; `AtomicFiles` always replaces, so it is not used);
  appends under the per-directory lock, responses sent after leaving it. Kinds `list.created` (seals `normaliser`; absent
  reads `default`), `list.member.added/removed` (sorted `keys[]`), `list.retired`, `identity.asserted {a,b,via}`,
  `identity.retracted {assertionSeq}`. The chain is re-verified on every read (break → 500 `INTEGRITY_VIOLATION`, no cache);
  a deleted LAST fact is undetectable from the log alone; orphan `.fact-*.tmp` files are ignored, never swept.
* **List ops in Investigations:** `excludeBy`/`seedBy` `{op, listId, reason?}`; refusal order 404, retired 409, type not in
  force 409, > 5,000 members 422; sealed `list {listId, atSeq, headHash, entityType, normaliser, purpose, masked,
  members[]}`. `excludeBy` records `excluded` and remembers `excludedKeys [{normaliser,key,step}]` (hashed only when
  non-empty; a remembered key does not block an already-admitted entity). `seedBy` reads `SELECT DISTINCT CAST(col AS
  VARCHAR)` per bound column, capped by `seed_by_distinct_cap` (1..100,000, default 20,000; 422 above), sealed as `read{ids[],
  fingerprint,…}`. Templates carry `{op, step, listId}` and re-resolve at the head; fork carries the seal verbatim.
* **Identity resolution routes:** `GET /inv/entity-identities?at=`; `GET …/group?key=&at=` (exact; key via `getRawQuery()` so
  `%2B` is not a space); `POST /inv/entity-identities` `{a,b,reason}` → 201; `POST …/{seq}/retract`; `POST …/import`. Group
  `{id (smallest member key), members[], assertions[{seq,a,b,via,actor,at,reason}]}`; a redundant assertion is extra
  evidence; cross-type allowed. Both reads need `canManageIncidents` (membership-oracle probe fixed). Event
  `ENTITY_IDENTITY_CHANGED {kind,seq,assertionSeq,groupSize}`. Import `{dataset,aCol,bCol,aType?,bType?,reason,limit?}` → 201
  (200 when nothing new), counts only (`imported`, `skipped{alreadyAsserted,duplicate,empty,self}`, `rowsRead`, `truncated`,
  `fingerprint`, `via: "dataset:<id>@<fingerprint>"`, `fences`); `limit` 1..10,000 (default 1,000), 10 s; idempotent (a
  retracted pair is never re-asserted); four-eyes from the D-U7 thresholds.
* **`resolve` op and merged traversal:** `resolve {atSeq?}` seals `{atSeq, atHash, headSeq, headHash, groups[], types{},
  columnTypes{}}`; a VIEW, not a rewrite (traversal, exclusion, counts stay raw); `toMap` adds `resolution` / `resolvedTo`
  only when in force (older hashes unchanged); measure `identities`; `/log` shows `resolutionSummary`; fork keeps the seal,
  template re-seals. 422: `ids` given, bad / past-head `atSeq`, > 5,000 member keys. `merged: true` on `expand` / `exclude`
  (422 without a resolve; hashed only when true): merged expand widens the frontier via a DISTINCT read capped by
  `merged_distinct_cap` (default 20,000), frontier cap 1,000; merged exclude seals groups and remembers `excludedGroups`; an
  untyped value is a member only when UNAMBIGUOUS (`rawMemberKeys`). Fork re-seals; a merged op re-ordered before its resolve
  is 422. Tests `ControlApiInvestigationResolveTest`, `…MergedTraversalTest`, `ControlApiEntityIdentityTest`,
  `…ImportTest`.
* **Live detection details:** decision register D-LD1..D-LD18 (only D-LD1 operator-decided): D-LD8 `canConfigureAccess`
  never honoured; D-LD9 only tightening refuses; D-LD16 an edit drops `standing`, write order component → binding → re-arm.
  Template list → `{templates:[{id,title,createdAt,dataset,investigation,parameters,ops}]}` (counts; a host without sign-in
  lists all); disable → `{rule,investigation,enabled:false,wasEnabled}`; PUT adds `replaced:true`,
  `standingDetection:"disabled: …"`, 404 not bound, 409 armed rule edited out of band. Events
  `LINK_STANDING_DETECTION_DISABLED`, `LINK_INVESTIGATION_ALERT_RULE_EDITED {rule,investigationId,standingDetectionDropped}`.
  `la.detect` is authored as `config/jobs/<name>.toon` `job:{name,type:la.detect,schedule:"<cron>"}`; Signal
  `la.detect.completed {job,fired,rules[]}`. `RowScope.visibleAs` uses an inert package-private `SweepExchange`. Enable reads
  the ARMED rule (a stored copy hashes differently after the TOON number round-trip). Gates per new route:
  `CapabilityManifest`, the manifests' `provides.routes`, `openapi-v1.json`, `compliance/evidence/route-gating.md`, auth-gate
  baseline; `JobWritersTest` row for the PUT; `ConfigWriteFunnelTest.WRITERS` / `DecisionRuleWritersTest` list `sealList` /
  `sealResolution` as fact-log readers (operator-approved 2026-09-30).
