---
type: Reference
title: Link Analysis — as-built reference and design records — Product, code layout, pattern packs and labels
description: Part 1 of 6 of Link Analysis — as-built reference and design records: Product, code layout and the studio; Pattern packs; AI-derived projection mappings; Dense-graph link labels
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — as-built reference and design records — Product, code layout, pattern packs and labels

Part 1 of 6 of [Link Analysis — as-built reference and design records](architecture-records.md) · next: [Grounded limits and consequences](architecture-records-grounded-limits.md).

## Product, code layout and the studio

* **Product vs capability (`ED-1`, 2026-10-17).** The product is **Inspecto LA (Link Analysis)**: the standalone `la-app`
  (browser tab, rail brand, landing heading, sign-in screen) says *Inspecto LA*. The capability keeps the name **Link
  Analysis** everywhere else: console nav, the app's own *Link Analysis* area, routes, keys and modules (GLOSSARY §11).
  The sign-in screen is the core's, shared with the console; it reads `AppEnvironment.productName`, which each host sets
  from its Product's `brand` (`products/<id>.toon` → the host's `product.generated.ts`).
* **Editions.** Routes live in `inspecto-geo-link`, in every edition since LA-EDITIONS-1 phase 3; placement, the
  `investigations` feature and the add-on: [architecture-la-core.md](architecture-la-core.md) §8. SPA side: `LA_FEATURES.investigations`
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
  Java parity tests read their fixtures from the library. Decisions: [SPA separation record](architecture-records-dossier-separation.md#spa-separation-record-d-5).
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
  [SPA separation record](architecture-records-dossier-separation.md#spa-separation-record-d-5)). Entity Lists has its own page, which provides the empty
  `InvestigationSessionStore`. `/settings` hosts Settings > Link Analysis (see
  [Studio UI](architecture-records-studio-index.md#studio-ui-and-accessibility-record)); the la-showcase `admin` persona holds `canAuthorWorkbench` through
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
  [user guide](user-guide-la-core.md).
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
    [exchange spec](../exchange/spec-exchange.md) (authoritative). LA side: "Offer for sharing" in the per-view menu, gated on
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
* Temporal ordering and branching packs: see [Patterns](architecture-records-grounded-limits.md#patterns-temporal-order-branching-and-the-server-half).

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
  override `display.allDetail`) with its measurements: [spec-la-core.md](spec-la-core.md) (performance seams).
