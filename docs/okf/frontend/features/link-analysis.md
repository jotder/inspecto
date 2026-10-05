---
type: Feature
title: Link Analysis
description: The graph investigation studio — Entity Projection over Datasets rendered on the shared G6 host, with layout/algorithm toolboxes and saved Link-Analysis Views.
resource: inspecto-ui/projects/link-analysis/src/link-analysis/
tags: [feature, studio, graph, entity, link, g6, investigation]
timestamp: 2026-07-07T00:00:00Z
---

# Link Analysis

> **Edition (2026-09-07, EDG-01 cell 3b).** Link analysis's backend routes live in the optional `inspecto-geo-link` module —
> Standard and Enterprise only (EDITIONS `CP-09`). `SessionService.geoLinkEnabled` mirrors `/bootstrap`
> `features.geoLink`; when false the nav entry and the Menu-Builder widget offer are **hidden**, and a deep link
> that still reaches the page gets a 503 from the core stub, which the component renders as an edition message
> rather than a generic query failure. ⚠ The flag is derived server-side from what actually registered, never
> guessed from the edition string.

> **Where the code lives (D-5 steps 3-4, 2026-10-02).** The Link Analysis and Geo code is the in-workspace library
> `inspecto-ui/projects/link-analysis/src/` (folders `link-analysis`, `geo-map`, `graph`, `geo`, `investigation`, `la-host`, `api`),
> imported as `@inspecto/link-analysis` (only the `la-host` seam and the two viz registrations - see `public-api.ts`) or by deep path for
> lazy routes; it reaches shared code as `@inspecto/core/*` (= `src/app/inspecto`). The canvas, `graph-types`, `graph-source`, `entity-key`,
> `graph-export` and `unique-name` stay in core. ESLint enforces the arrow (core <- library <- shell); the Java parity tests read their
> fixtures from the library. Design and as-built facts: `docs/archived-documents/plans-archive/la-separation-d5-design.md` (archived).

> **The second shell, `la-app` (D-5 step 6, 2026-10-02).** `inspecto-ui/projects/la-app/src` is a separate Angular application
> (`ng build la-app` -> `dist/la-app/browser`; `gamma` is unchanged): a top bar (Link Analysis / Geo / Entity Lists, Space switcher, user
> menu), a landing page, and the library's lazy routes. Sign-in is the CORE's OIDC code (`inspecto/auth`), not a second client. It
> answers the ten `la-host` tokens in `la-host.providers.ts`; `LA_APP_TOKEN_PROVISION` lists them and a spec pins that the list is the
> ten. **Six are real** (`LA_DATASETS` over the Component registry, `LA_CASES`, `LA_TAGS`, `LA_TRANSFER`, `LA_AI_ASSIST`, `LA_FEATURES`) and
> **four are stubs** that report once on the console (`console.info`, never an error) and set `available: false` so the library hides the
> affordance: `LA_WIDGETS` ("Pin to a Widget"), `LA_CATALOG` (the reuse-graph source), `LA_PIPELINE_GRAPH` (the provenance source) and
> `LA_DASHBOARD_HEADER` (an empty header; la-app has no dashboards). Entity Lists get their own page there, which provides the empty
> `InvestigationSessionStore` the section reads. The bundle choice (`package.ps1 -Ui`) is in `okf/backend/build-run/build-test.md`.

The Builder-lens studio at `/studio/link-analysis` for graph investigation. Keep the four graph planes
distinct ([`GLOSSARY.md`](../../../GLOSSARY.md) §11): this studio works on **P3 — Entity/Link graphs**
(records as business entities), never on artifact/lineage graphs.

* **Sources** — a **GraphSource** feeds one renderer through one query seam; the P3 source is
  **Entity Projection**: a mapping (not a store) that folds a Dataset's rows into Entities + Links
  (column → source/target Entity, optional columns → Link type/attributes).
* **Rendering** — the shared G6 host (`src/app/inspecto/graph/`), reused by the Catalog graph and the
  Geo co-location bridge. Nodes are canvas-drawn — verify inspector logic in unit tests, not preview clicks.
* **Toolboxes** — Layout (11 G6 layouts; tree shapes gated to acyclic data) and Algorithm, plus
  paths/neighborhood/centrality analysis. The **V2 algorithm depth** (2026-07-24) lives in the pure,
  framework-free `inspecto-ui/projects/link-analysis/src/graph/graph-analysis.ts` library (the extension seam — a new algorithm is a
  pure `(g: G6GraphData, …) ⇒ result` drop-in) and is surfaced as accordion groups in
  `link-analysis-toolbox.component`:
  * *Advanced traversal* — `weightedShortestPath` (Dijkstra by tie strength, `edgeWeight` = folded
    count), `findCycles` (canonicalized directed cycles), `articulationPoints`/`bridges` (Tarjan),
    `egoNetwork`.
  * *Algorithm library* — `pageRank`, closeness/eigenvector/katz centrality, `hits`, `kCore`,
    `triangleCount`, `cliques` (Bron–Kerbosch), `maxFlow`+min-cut (Edmonds–Karp),
    `maximumSpanningForest`, `jaccardSimilarity`, `linkPrediction`.
  * *Communities* — `detectCommunities` with a `communityMethod` toggle of label propagation or
    **`louvainCommunities`** (`link-analysis-toolbox.component`). *(Added to this inventory 2026-09-08: the
    tool shipped and both `REQUIREMENTS` `INV-1` and the user guide promised Louvain, but this list — the
    mechanism's source of truth — omitted it.)*
  * *Suspicion scoring* — `suspicionScore`, an explainable 0–100 composite (degree/betweenness/
    PageRank/k-core/triangles) with a per-node factor breakdown; the toolbox highlights the top decile.
  * *Pattern packs* — a picker (`pattern-packs.ts`) that pre-fills the motif builder from parameterized
    starter templates (layering chain, pass-through, inbound collector, forwarding relay, circular flow,
    shared associates); packs whose shape isn't a path motif hint at the fitter tool (cycles/similarity).
  Guarded by `ANALYSIS_NODE_CAP` (2000) where super-linear; 53 pure unit tests + 11 toolbox specs.
* **Saved investigations** — a **Link-Analysis View** (Component kind `link-analysis-view`) via the
  shared `investigation` folder of the library (`inspecto-ui/projects/link-analysis/src/investigation/`); when its source is `entity-projection` it is renderable as a
  **Widget** (a Graph Visualization Type bound to a Dataset).
* **Status** — UI shipped mock-first; the backend Entity Projection over real Datasets shipped
  (REQUIREMENTS INV-1, `POST /inv/projection`), including the full V1 slice (multi-mapping, multi-root,
  incremental expand, SVG/GraphML export, undo/redo, `attrCols` — the last is fully implemented both
  backend (`InvRoutes`) and UI (`entity-projection.ts`), not open despite an earlier stale note here).
  **2026-07-20 shipped the schema-relationship model**, §7's other deferred half: `GET
  /inv/schema/relationships` infers naming-convention FK suggestions across Datasets (`<base>_id` column
  → a Dataset named `<base>`, linked to its `id` column or a same-named column), so the Studio can
  pre-fill multi-mapping projections instead of requiring every column pair hand-picked. Self-references
  (e.g. `manager_id`) are included; unusable Datasets are skipped, not fatal.
  **2026-07-24 shipped four V2 tracks** (see Toolboxes above): advanced traversal, the algorithm
  library, suspicion scoring, and pattern packs. **2026-07-24 also shipped the timeline**: a pure
  `filterByTime(g, attrCol, cutoff)` in `graph-analysis.ts` (edges only — an edge survives only when its
  `attrs[attrCol]` parses as a date on or before the cutoff; nodes are untouched, same non-mutating
  contract as `filterByKinds`) plus a toolbar "Timeline" menu (column picker over every `attrs` key seen
  in the loaded graph + a `mat-slider` cutoff, rail bounds from that column's parseable date extent) in
  `link-analysis.component`. It slots into the existing filter pipeline — kind-filter → time-filter →
  `collapseBranches` → the shared `displayed()` graph-view binding — so no new filtering mechanism was
  needed; resets on a fresh query and via "Clear search & filters", and participates in undo/redo like
  the other presentation filters. **2026-07-24 also shipped version history**: each saved view in the
  toolbar "Saved views" menu is now a small submenu (Load view · Version history), the history entry
  opening the shared `ComponentHistoryDialog` (`inspecto/components/component-history.dialog`) with
  `{type:'link-analysis-view', id, label}` — the same dialog the widget/query/dataset/dashboard hosts
  use, working as-is because `link-analysis-view` is a `ComponentStore` WRITABLE_TYPE (so
  `/components/{type}/{id}/versions` + `restore` apply); a successful restore reloads the view list.
  Frontend-only (`ComponentsService.versions/restore` were already wired).
  **2026-07-26 shipped V2 (b) sharing** — the Exchange `kind` axis now carries `link-analysis-view`; see the
  D9 bullet below and [exchange-sharing.md](../../backend/control-plane/exchange-sharing.md). **2026-07-26
  also shipped V2 (d)'s vocabulary half**: `<inspecto-ai-explain screen="Link Analysis">` in the header
  declares six canonical terms (Entity · Link · Entity Projection · Link-Analysis View · Dataset · Widget),
  making this the 12th adopter — the pane most in need of it, since the glossary bans using Entity/Link for
  artifacts or assets and this is the one studio where they are the subject. No backend
  (`glossary_lookup` is non-mutating). **2026-07-26 also shipped V2 (c)**: pattern packs are now a per-Space
  `pattern-pack` component kind (see *Pattern packs* below) rather than a hardcoded const.
  **V2 (d)'s authoring half shipped 2026-07-27 — V2 is now complete** (see *AI-derived projection mappings*
  below).
* **Investigation pivot** (ui-design-review R8, 2026-07-20) — a node resolving an `objectRef` offers
  "View on map" (pivots to Geo Map Analysis with the same record); see
  [Investigation Pivot](investigation-pivot.md) for the shared contract.
* **Geo ↔ Link brushing** (LA-22, shipped 2026-09-23) — `link-analysis/geo-link-brush.ts`'s root
  `GeoLinkBrushService` holds the last selection. On the Geo Map, closing a polygon (or clicking a point)
  publishes the displayed points' `GeoPoint.key`s; Link Analysis highlights the nodes those keys project to
  (`[emphasis]="emphasis() ?? geoBrushEmphasis()"` — an explicit emphasis wins). A node click publishes back
  and the map emphasises the points whose key projects to it. The join goes **only** through `entityId()`
  with the last run's mapping `entityType`s, so id-minting normalisation (D-S4) reaches the brush for free.
  ⛔ Never label, never `pt:<i>`: a point with no mapped `entityIdCol` never brushes. ⚠ It is a brush
  across **routes** (the service is root-scoped, so it survives navigation) — the split-pane mode and
  graph-path → map-route tracing in the original row are deferred.
* **V2 decisions of record — 2026-07-25 product session (BACKLOG D9 / D10 / D16).** All three remaining V2
  blockers were product calls, and all three were answered in favour of generalizing an existing seam rather
  than adding a link-analysis-specific one:
  * **D9 sharing — SHIPPED end-to-end 2026-07-26.** Saved views belong in the Exchange, and the Exchange
    `kind` axis was widened to carry `link-analysis-view`. Full as-built (derived-kind closure, live-only
    grants, the `GET /exchange/views/...` render route) lives in
    [exchange-sharing.md](../../backend/control-plane/exchange-sharing.md) — **that doc is authoritative**,
    not this one. The link-analysis-side facts: "Offer for sharing" sits in the per-view menu beside
    Comments/Tags (the D10 idiom), gated on `exchangeEnabled() && canOfferDatasets()` **and** on the view's
    source being `entity-projection`. ⚠ **Only an entity-projection view is shareable, and its Datasets are
    its projection mappings** (`query.projections[].datasetId`, else `query.projection.datasetId`) — *not*
    `query.roots`/`query.from`, which is the lineage/provenance shape whose roots are catalog assets and
    Pipelines. Every shipped saved view uses the single-mapping shape, so reading roots/from 422s all of them
    while still passing hand-written tests — verify this one in the preview, not only in specs.
  * **D10 per-view comments — generalize the note model**, do not re-key `ObjectNote` by component
    `type`+`id`. A note becomes attachable to any `(kind, id)` target, so Incidents/Cases stay one adopter
    instead of the special case the model is currently shaped around. Rejected alternative: the narrow re-key,
    which buys the same feature and guarantees a third caller becomes a third special case. This aligns with
    the generic-tag direction (BACKLOG D7) — grouping and annotation should both address components uniformly.

    **SHIPPED end-to-end 2026-07-25** — backend (`d703a74d`), UI half same day. As built:
    `ObjectNote` carries a **`targetKind`** — ⚠ *not* its pre-existing `kind`, which is `NoteKind`
    (COMMENT/ATTACHMENT) and an orthogonal axis; the two must never be conflated. The vocabulary is
    `AnnotationKinds` = `"object"` + `ComponentStore.WRITABLE_TYPES`, which already contains
    `link-analysis-view` — **no new enum, and no competing vocabulary**, since `BundleRoutes.OWN_STORE_KINDS`
    and the Exchange axis use the same strings. **D7 inherits this scheme.** New surface is
    `GET/POST /notes/{targetKind}/{targetId}/comments|attachments`; the existing `/objects/{id}/comments`
    and `/attachments` are untouched shipped routes.
    Invariants worth preserving: **one gate serves reads and writes** (`NoteRoutes.targetGate` *is* the
    `TargetResolver`) so existence and authorization cannot diverge between paths; `object` reuses
    `ObjectRoutes`' SEC-7d + `RowScope` check verbatim, answering 404 out-of-scope, so the generic path is
    not a way around it; component kinds gate on `ComponentAccess.requireView`, not edit — **commenting is
    collaboration and writes nothing under `registry/`, so a view-only sharee may comment**.
    Migration follows the `DbAcquisitionLedger` (ACQ-7) precedent: in-place `ALTER TABLE ADD COLUMN IF NOT
    EXISTS` + backfill to `'object'` in `initSchema`, idempotent on DuckDB and Postgres.
    Deliberate residuals: `objectId` was **not** renamed (`targetId()` is an alias; keeps ~30 call sites and
    the JSON stable) · `GET /notes/object/{absent}` 404s while `GET /objects/{absent}/comments` still returns
    `200 []` · **notes are not deleted with their component**, so re-creating an id resurrects the thread.
    UI: a "Comments" action sits next to "Version history" in the saved-views per-row menu, opening
    `LinkAnalysisCommentsDialog` (modeled on `ComponentHistoryDialog`) over a new `NotesService` — no
    "currently loaded view" state needed, since both actions already operate per-row on the views list.
  * **D16 pattern packs — ~~a dedicated system Space owns the domain-seeded packs~~ OVERTURNED
    2026-07-26 (operator): per-Space forking is acceptable.** The original rationale (one authoritative copy,
    so a fix to a shipped pattern reaches every Space) was weighed and dropped — packs are per-Space content
    and the central-fix guarantee is not required. **Shipped as a `pattern-pack` component kind**; see
    *Pattern packs* below for the as-built shape and the two costs that killed the system-Space option.

## Pattern packs (V2 (c) — shipped 2026-07-26)

A **pattern pack** is a named starter motif that pre-fills the pattern-match builder. Packs are an ordinary
per-Space **`pattern-pack` `ComponentStore` kind**, authored at
`spaces/<space>/config/registry/pattern-packs/*.toon` and read by the toolbox over
`GET /components/pattern-pack`. Six are seeded in each tracked Space.

* **No new endpoint and no new capability.** `/components/{type}` CRUD is generic (the `findings-spec`
  precedent), so the whole backend change is two registrations: `ComponentStore.WRITABLE_TYPES` and
  `ComponentRegistry.TYPE_BY_DIR`. Writes ride the generic `canAuthorWorkbench` gate. Version history,
  ETags, `ComponentAccess` share filtering, `AnnotationKinds`, `InspectoTools` and `BundleRoutes.supported()`
  all read `WRITABLE_TYPES` dynamically and came free.
* ⚠ **`WRITABLE_TYPES` is misnamed for this purpose: a kind absent from it is UNREADABLE, not merely
  read-only** — `list`/`get` call `validateType` too. There is no read-only-kind concept and this change
  deliberately did not invent one; that is *why* packs are writable over HTTP rather than a served-only
  catalog.
* ⚠ **TOON cannot encode `{}` as a list element**, and a motif's step 0 (the start node) is exactly that.
  `ConfigCodec.toToon` writes a bare `-` and then **fails to decode its own output**. So the persisted shape
  gives every step a `direction`, the start node's being the **empty string**, and `patternPackFromContent`
  maps blank → `undefined`. **Do not "tidy" the blank away** — it silently breaks every seeded pack on read.
  Pinned by `ComponentStoreTest.patternPackStepsSurviveTheRoundTripWithABlankStartDirection`.
* **The shipped `PATTERN_PACKS` const stays the fallback**, used when a Space has no packs, on error, or with
  no write root — so the catalog is populated synchronously and is never blank. The toolbox seeds its signal
  with the const and replaces it only on a non-empty response.
* ⚠ **`patternPacks` must be a signal**: the toolbox is `OnPush`, so reassigning a plain field from the HTTP
  callback renders nothing. ⚠ And the injected service is **`componentsApi`** — `components` is already this
  component's connected-components signal, so reusing the name is a duplicate-identifier compile error.
* Pack content is **free-form TOON with no backend `validateKind` branch**; the guard is instead the
  defensive UI mapper, which skips a malformed pack rather than drawing a broken option. Revisit if packs
  ever get an authoring UI.
* ⚠ **`spaces/uat/` is gitignored** and is deliberately unseeded (`tools/seed-uat.ps1` has no registry step),
  so uat runs on the const fallback. Adding the files "to fix it" cannot work — they are not committable.
* **Why not a reserved system Space** (the two costs that overturned D16, recorded so it is not re-proposed
  blind): a `_`-prefixed sentinel dir holding `config/` **passes** `SpaceManager.discover`'s filter and then
  dies in `SpaceBootstrap.load` at `SpaceId.of` (which forbids a leading underscore), logging a spurious
  `Skipping space dir` WARN on **every boot**; and a sentinel *without* `config/` cannot be reached through
  `/spaces/{id}/…` at all, so it would need a dedicated cross-space read route.

## AI-derived projection mappings (V2 (d) authoring half — shipped 2026-07-27)

The query panel's Entity/Link tab has a **Derive mapping** button (`<inspecto-ai-assist>`, the 5th adopter)
that proposes which column is the source entity, which is the target, which labels the edge and which travel
as node attributes. Backed by a **new non-mutating `projection_author` tool** over the existing
`POST /agent/tools/{name}` dispatch. Applying a draft patches the form **dirty and stops there** — the
operator still presses Run and Save, so the human stays the actor.

* **Deterministic, not a model call.** The authoring act is column *selection*, so name-shape scoring answers
  it: an ordered `ENDPOINT_PAIRS` table (`caller/callee` … `from/to`), then a first-two-`*_id` fallback, then
  **refusal** — an unmappable list returns `clean=false` with a finding anchored at
  `projections.0.sourceCol`, never a guess, because an arbitrary pair produces a graph that looks authored
  and is wrong. `hint` **narrows the candidate set**; it is not a prompt, and a hint matching fewer than two
  columns is ignored with a WARNING. NL is AGT-6a **A5**'s job and this tool is its `derive` target.
* ⚠ **The pane supplies the column list as an argument, deliberately.** No agent tool and no tool-layer
  route returns a Dataset's columns — the belt only sees the operational-store `table` vocabulary, and
  `InvRoutes.schemaRelationships` builds a `columnsByDataset` map only to **throw it away**. The panel
  already holds the real list, so passing it in is cheaper *and* dodges the `-Dassist.write.root`
  dependency that any `ComponentStore`-backed column lookup would inherit (it is what makes `query_author`
  hard-error without a write root).
* ⚠ **`entityType` is left unset.** Set on a *single* mapping it changes node ids from `entity:<v>` to
  `entity:<type>:<v>`, breaking byte-identity with existing saved views and exports — while `buildQuery`
  *requires* it on every mapping once extras exist. The tool emits exactly one mapping, so unset is the only
  correct answer; a future multi-mapping drafter must set it on **all** of them.
* ⚠ **The draft is `query.projections[]`, never `query.roots`/`from`** — a view's Datasets come from its
  projection *mappings*, the premise that 422'd every shipped view during V2 (b) sharing.
* **Why not `component_draft` or `query_author`** (the call, 2026-07-26): `component_draft` cannot draft — it
  echoes the config it was handed back with findings, and its `kind` resolves through `ConfigSpecs.TYPES`,
  which has **no `link-analysis-view`**, so it returns `ok=false` today. `query_author` emits a Query, not a
  mapping. No `link-analysis-view` ConfigSpec was added: it would buy validation, not authoring, and the
  defensive UI mapper is already the boundary (the `pattern-pack` precedent).
* **Fixed on the way through: `patchFormFromView` ignored `projections[]`.** It read only
  `query.projection`, so a *saved* multi-mapping view loaded first-only — a pre-existing load-path bug, not
  one the draft introduced. It now rebuilds the extras `FormArray` and patches `attrCols`/`entityType` too,
  and both the saved-view and draft paths share one `patchFormFromQuery`.
* ⚠ **Adopting the assist surface broke every test in the panel's spec** until `provideHttpClient()` + a
  `ToastrService` stub were added — `<inspecto-ai-assist>` injects `AgentService`. The same trap as the
  toolbox's `ComponentsService` injection.
* ⚠ **Offline the columns come from `SAMPLE_SOURCES`**, so an offline draft is over sample columns and is
  never evidence the real column path works. (Verified offline: `money_moves` → `from_city → to_city` +
  7 attributes, applied and run to a 7-node/11-link graph; `cdr_sample` correctly **refuses** — it has only
  one id-shaped column.)
* ⚠ **`AiToolName` and `adaptToolResult` are a pair.** A tool missing from either yields an empty candidate
  list, which renders as "no suggestion" with **no error** — the failure mode that makes a new tool look like
  a model problem. `projection_author` joins the shared `{kind,id,clean,findings,draft}` branch.
* ⚠ **The tool count in `InspectoPackTest` is hard-coded** (21 → 22). Any new belt tool trips it.

## Dense-graph link labels (as-built 2026-09-28)

A dense canvas drew every link-type label, so the telco demo ring (18 nodes / 42 links) was unreadable.
The shared `GraphViewComponent` (`inspecto-ui/src/app/inspecto/graph/graph-view.component.ts`) now
applies a **density rule**: above `DENSE_EDGE_LABEL_CAP = 20` links (`edgeLabelsHiddenByDensity`), link
labels are hidden and revealed by a custom G6 `labelled` edge state — on hover of the link or of an
endpoint node (every link touching it), and pinned by a click until the next click. It uses its own pointer
events, not `hover-activate`, so it also works in the menu-leaf `link-view-widget`, which passes no
behaviors; other states (e.g. `active`) are preserved. Sparse graphs (≤ 20) keep labels exactly as before;
node labels are untouched; the legend still names every link type.

- **Why 20:** a 9px link label is ~60px wide; past ~20 links on an auto-fitted canvas they collide with each
  other and with node labels, while a small investigation stays fully labelled.
- **Override:** the Display menu and View toolbox gain **All link labels** (disabled while *Link labels* is
  off). It persists in the saved view as `display.allEdgeLabels: true` (omitted when off, so older views
  and views without it get the density rule); undo/redo snapshots it.
- Not driven live in the browser this change — covered by vitest only.

## Grounded limits and consequences (code read 2026-09-20 / 2026-09-22)

Durable as-built facts distilled from the archived `link-analysis-spec.md`. Open work against them is
tracked on the board (`docs/BACKLOG.md` §3.12 — see *Closed-plan record* below); the plan that held it is
archived: [`link-analysis-backlog-plan.md`](../../../archived-documents/plans-archive/link-analysis-backlog-plan.md).

| Limit | Value | Where | Behaviour at the edge |
|---|---|---|---|
| `PROJECTION_NODE_CAP` | **500** | `entity-projection.ts:32`, applied `:99,:150` | truncates the fetch — the real governor |
| `ANALYSIS_NODE_CAP` | 2 000 | `graph-analysis.ts:11`, `requireUnderCap` `:836-840` | ⚠ **throws** on the super-linear algorithms |
| `DEFAULT_LIMIT` / `MAX_LIMIT` | 2 000 / 20 000 | `InvRoutes.java:62-63,178-179` | clamps, sets `truncated: true` |
| Render | none | `graph-view.component.ts` | no virtualisation, culling or WebGL |

* **An edge is a folded aggregate** (`GROUP BY`, carrying `count`), nodes are implied endpoints with no
  identity service, and **nothing is persisted server-side** — every call re-runs the aggregation. So
  🔴 **a saved view is not evidence**: reopened after the Dataset changed it silently shows a different graph.
* **All 27 algorithms run in the browser, on the main thread.** The backend does SQL fold and filter only
  (`InvRoutes.java:36-39`, deliberate). Supported graph size is therefore bounded by one tab.
* **Safety is narrowness:** identifiers must match `SAFE_IDENT` (`InvRoutes.java:61`), values bind as JDBC
  `?`; 503 without a write root, 404 unknown Dataset, 422 bad identifier.
* **The canvas is not the performance bottleneck**, and since LA-05 it is not a rebuild either.
  `GraphViewComponent.ngOnChanges` classifies the change and does the smallest thing that satisfies it:
  a layout, plugin, tooltip or fill change recreates; a data change is applied onto the live graph; a
  cosmetic change (emphasis, display overrides, theme) repaints without running layout. Every comparison
  is by VALUE (`stableKey`), because each bound input is a `computed()` yielding a fresh reference — an
  identity check would rebuild on every toggle. Measured in the preview on a 170-node projection: toggling
  node labels keeps the same G6 instance and moves **0 of 170** nodes, where it previously re-laid-out
  the whole canvas. `displayOptions` and `canvasPlugins` carry `equal:` comparators so an unchanged value
  never reaches the host at all.
* 🔴 **`graph.draw()` DOES NOT REPAINT** — the single most surprising thing about the G6 v5 host.
  It re-renders from the element specs it already holds; it does **not** re-evaluate the style mapper
  functions. A repaint must re-set the data (`setData(this.data)` then `draw()`), which re-runs the
  mappers and does **not** re-run layout. Found by measurement, not by reading: with a bare `draw()` the
  "node labels off" toggle left every label on the canvas while the component's own state said they were
  off — a silent, invisible no-op that no unit test in jsdom can see.
* ⚠ **A `labelText` mapper that returns `undefined` does not clear a label.** G6 reads `undefined` as
  "no change" and keeps the previous text. Use G6's own `label: boolean` switch to remove the label shape.
* **The two-stage filter loop's stage 2 is live since LA-01**: `POST /inv/projection` and `/neighbors`
  accept an optional `filter` (the `query-types.ts` condition tree verbatim), rendered by the existing
  `ConditionSql` and `AND`-ed into the `WHERE` **ahead of the `GROUP BY`**, so `count` folds over the
  filtered rows. Every leaf `field` is checked against the relation's actual columns first and an unknown
  one is 422 `CONFIG_VALIDATION_FAILED` naming the field — identifiers never reach the renderer
  unvalidated (operator decision D-S5, 2026-09-22). Cost: a filtered call probes the relation's columns
  with one extra zero-row query; unfiltered calls are unchanged. 🔴 **The `filter` root must be a group**
  (2026-09-23, on `/projection`, `/neighbors`, `/projection/multi` top-level and per-edge, and
  `/traversal/recursive-paths`): a bare condition at the top level used to render `TRUE` and return every
  row with a 200 while the fields were still validated — fail-open. It is now a 422 naming the expected
  group shape. The SPA always sends a `ConditionGroup`, so nothing it builds is refused. An empty group
  and incomplete leaves are still a deliberate no-op (see [decision rules](../../backend/control-plane/decision-rules.md)).
* **Projection, expansion and schema inspection are audited** (LA-04): `InvRoutes`/`GeoRoutes` emit
  `link.projected`, `link.expanded`, `link.schema.inspected`, `geo.projected` and `geo.routes.projected`,
  each carrying the dataset, the result size and `truncated`, best-effort so an audit failure can never
  fail the analyst's query. ⚠ Exclusion, reveal and export remain **client-side** and so are still
  unaudited — they have no server surface to emit from.
* **The View toolbox offers 14 layouts and 8 canvas plugins**, each a G6 v5 built-in id, and LA-09 added
  what was genuinely drop-in: the `fruchterman` layout, plus `dendrogram` and `fishbone`, which carry the
  same tree/forest gate as the three hierarchical layouts already did. `snapline` is a new lens, and
  `bubble-sets` draws the SAME Louvain communities as the hull overlay in G6's set renderer — the two are
  **mutually exclusive by construction** (both draw one shape per community, so enabling both would paint
  every community twice; bubble sets win and hulls are skipped).
* ⚠ **"Each is a G6 id, not an engine" is only two-thirds true** — four of LA-09's named items were
  REFUSED on grounding rather than wired, and the reasons are worth keeping because each would have
  shipped something worse than nothing:
  * `timebar` — its options require a `data` array and a `getTime` accessor; it is not a bare flag. It
    also duplicates the pane's existing time column + cutoff control, so wiring it is a UX decision.
  * `history` — 🔴 the studio **already has its own Ctrl/Cmd+Z undo** over presentation state
    (`undoPresentation`/`redoPresentation`). G6's history plugin would put a second, competing undo stack
    on the same keystroke.
  * `contextmenu` — empty without a decided action set, and node actions already live in the element
    detail dialog.
  * `watermark` — its content (case id? "not evidence"?) is a decision, and a blank watermark toggle is
    not a feature.
  * Two more are not drop-ins at all: **`combo-force` is not a real G6 v5 id** (the nearest built-in is
    `combo-combined`), and **"combos" is a data-model change**, not a plugin — it needs `comboId` on the
    graph data.
* **The two graph caps are per-space SETTINGS, not constants** — `GET|PUT /settings/link-analysis`
  (`link-analysis.toon`, `canAuthorWorkbench`, the same shape as branding and geo). `null` on either
  field means **inherit the shipped default**, never *unbounded*. The shipped values (500 projection,
  2 000 analysis) are **measurements, not truths**: they came from one host, one browser and one
  synthetic graph shape, and a denser graph or a slower laptop moves them.
* ⛔ **A persisted cap is refused, not clamped.** A non-integer or anything outside `1..100000` is a
  **422 naming the field and the range** — because an operator typed it and deserves to be told. A silent
  clamp is the pattern for a per-request parameter, not for a stored setting.
* ⛔ **The client-side setters fail closed too.** A cap that is not a finite integer ≥ 1 is ignored and
  the previous value stands: a cap of `0` or `NaN` would put every graph over the limit and switch the
  whole analysis toolbox off, which is far worse than ignoring a bad setting. Pinned by a spec that goes
  red when the guard is mutated away.
* ⚠ **The graph modules are pure libraries with no dependency injection**, so limits are PUSHED into
  them (`configureGraphLimits`, `configureProjectionLimits`) rather than read out, and the enforcement
  sites read the live value instead of capturing it. 🔴 A space change RESETS to the defaults before
  applying the new space's values — otherwise an absent field would silently mean "keep the previous
  space's override", which is one space tuned by another's settings.
* **A hub's pendant leaves can fold into one stand-in** (LA-06): opt-in, off by default, labelled with
  the count. Only nodes whose ONLY link is to that hub fold, so no path is ever hidden. Clicking a
  stand-in opens it; turning the control off and on again is the way back, because once opened there is
  no stand-in left to click.
* 🔴 **A stand-in is not a record and must never carry an `objectRef`.** It would let an analyst open
  one real account's detail page believing it represented the two hundred the stand-in folds. It also
  carries its own `kind`, so it is excluded from the legend and kind tallies rather than counted as an
  entity, and the working-set tiles are measured **before** aggregation — live on the demo graph, the
  canvas draws 159 marks while the tiles correctly report 170 entities.
* ⛔ **Viewport culling and progressive edge loading were REFUSED as premature** — the projection cap is
  500 nodes, the edge array is already weight-ordered from the server, and the bottleneck those clauses
  targeted was the pre-LA-05 rebuild, which no longer exists. 🔴 A culling pass that filtered the
  canvas's `data` would re-introduce the LA-05 regression, running a full layout on every pan.
* **A pattern can require its hops to be in TIME ORDER** (LA-14a). A step marked `afterPrevious` must
  carry an event time strictly later than the previous step's, optionally within `maxGapHours`; the time
  comes from an edge attribute column chosen in the pane, parsed with `Date.parse` exactly as the time
  filter does. `layering-chain` and `pass-through` now set it, within 48 hours, in the built-in constants
  **and in all six authored TOON copies** — authored packs merge OVER built-ins by id, so a TOON copy left
  untouched would have silently reinstated the unordered motif.
* 🔴 **Before that, a pass-through match was a topology claim wearing the language of a flow claim.**
  `A → B → C` matched whether B forwarded to C a day after or a year before receiving from A. Measured on
  the demo projection: the pack reported **200 matches** with no regard to time.
* ⛔ **A temporal motif FAILS CLOSED.** With no time column the matcher returns nothing and the toolbox
  says why — "choose a time column" — because "No matches" is the same sentence a genuinely empty result
  produces, and would tell the analyst the chain is absent when it was never looked for. An edge whose
  time is missing or unparseable is rejected on the same principle.
* ⚠ **Ordering is all that survives the projection.** A timestamp reaches the graph as a STRING
  (`CAST(col AS VARCHAR)`), and 🔴 an attribute column **joins the `GROUP BY` fold key**, so selecting a
  timestamp de-folds a projection into one edge per instant. Temporal matching is therefore honest at demo
  cardinality and needs the SQL compiler (LA-14b) at call-record scale. Comparing values within one
  dataset is unaffected by the host-timezone question, because every value is parsed the same way.
* **A pattern can BRANCH** (LA-14b, browser half, 2026-09-23). `inspecto-ui/projects/link-analysis/src/graph/branching-pattern-engine.ts`
  (`matchBranchingPattern`) runs a motif of ordered **stages**, each a `fan-in` or `fan-out` with a minimum
  number of DISTINCT counterparties, an optional per-leg `threshold` band (`min` inclusive, `max` exclusive —
  a reporting threshold is crossed AT its value), an optional `windowHours`, and LA-14a's `afterPrevious` /
  `maxGapHours`. It **extends** `matchPattern` rather than replacing it: linear packs still run there, and the
  two share `followsInTime`, `edgeTimeIndex` and `baseEdgeKind`, so they cannot disagree about when a link
  happened. Ordering is **per branch** (each leg against the time the match reached ITS tail node), and a
  fan-in "arrives" when it reaches its minimum breadth — so a collector that wires out before enough deposits
  landed is not structuring.
* The built-in **Structuring** pack (`PatternPack.stages`, `steps: []`) is fan-in ≥ 5 legs in
  `900 ≤ AMOUNT < 1000` within 24 h → fan-out ≥ 2 → re-converge ≥ 2, each ordered within 48 h. The band is
  rendered as editable fields in the toolbox. 🔴 **A band, not an open "under 1 000"**: ordinary traffic is a
  long tail below any threshold, and an open bound made every busy account a "collector". On the demo corpus
  (`mule_structuring` view, 1 928 transfers, 170 accounts) it finds exactly the one planted ring in ~9 ms.
* **Two more built-in branching packs (2026-10-05, `LA-INVESTIGATION-OPS-DEFERRED-1`)**, same constant-only shape as Structuring (no TOON copy, reach every Space via the PACK-1 merge, no threshold):
  `layering-network` = fan-out ≥ 3 within 24 h → re-converge ≥ 3 within 48 h (the web a single-path `layering-chain` misses);
  `circular-financing` = two successive split-and-merge rounds (fan-out ≥ 2 → fan-in ≥ 2 → fan-out ≥ 2 → fan-in ≥ 2), each after the one before within 48 h.
  **Closure (LA-CIRCFIN-2, 2026-10-05):** `BranchStage.closesToStart` — valid ONLY on the LAST stage, which must be a `fan-in` after ≥ 1 other stage (else the SPA matcher refuses and the server 422s). Its collector must be a member of the ORIGIN layer (`layers[0]`: the splitter of a stage-0 fan-out, the senders of a stage-0 fan-in) instead of a NEW node — the one place the "never revisit a used node" rule is lifted. `circular-financing` sets it, so a match is a proven round trip; the open web it used to find (merging on a new exit) is no longer a match. Seams: TS `matchBranchingPattern`; Java `BranchingPatternEngine` (port) + `PatternQueryCompiler` (parse + validation, and a necessary-condition SQL prune: the closing stage keeps only legs landing on stage 0's anchor/senders); authored TOON packs carry an optional `closesToStart` column. Parity: the `closure` section of `branching-parity.fixture.json` is asserted by `branching-parity.spec.ts` and `ControlApiInvPatternTest` (closed ring matches; open ring and a mis-ordered ring do not). Calls: a flag on the stage (not a new `shape`) so existing packs and the wire shape are unchanged; origin = first layer so it also works for a fan-in start; per-stage ordering still applies, so a rotated start (hub → … → origin) is rejected by time. `circular-flow` keeps routing to Cycles for exact single loops. Ids are `layering-network` (not `layering`, which would read as a rename of `layering-chain`) and `circular-financing`.
* ⛔ **It REFUSES rather than answering "none"** — no time column · no link carrying the threshold
  attribute · **no link passing the threshold at all** (plan §2.6: `mule_large_transfers`' `AMOUNT ≥ 5 000`
  removed every leg before the matcher ran, and "no matches" would have declared the structuring absent) ·
  a graph over `ANALYSIS_NODE_CAP`, returned as a refusal, never thrown. Work is budgeted
  (`BRANCHING_WORK_BUDGET`) and the match list capped, both surfacing `truncated`.
* ⚠ An authored branching pack is one FLAT TOON tabular row per stage
  (`stages[n]{shape,minBranches,edgeKind,nodeKind,windowHours,afterPrevious,maxGapHours,thresholdAttr,thresholdMin,thresholdMax}`);
  blank = wildcard / no bound, and ANY unusable row drops the whole pack. No TOON copy is seeded — the
  built-in reaches every Space through the PACK-1 merge — so that shape has not yet been through
  `ConfigCodec`'s round-trip.
* **Value Measures over the whole Dataset** (LA-18, backend, 2026-09-30): `GET /inv/value-measures`
  (`inspecto-geo-link` `ValueMeasures`) answers the entities breaching a named Measure's visible thresholds —
  `passThrough` (retention as a derived column), `velocity`, `timeToCashOut`, `cashOutConcentration`,
  `structuring`, `benefitTransfer`, plus the non-alertable `valueWeightedLinks` — in a `[from, to)` window of at
  most 31 days (a `from`/`to` with `Z` or an offset is normalised to UTC). No view filter is accepted, so a `≥ 5 000` view cannot hide structuring. An Alert Rule binds one
  through `POST /inv/investigations/{id}/alert-rules` with `valueMeasure:{…}` and fires when at least one entity
  breaches (one Alert per rule). Definitions, defaults and deviations: plan §2.6.1. **SPA** (2026-09-30): the
  Investigation side pane's *Value Measures* panel (`link-analysis-value-measures.component`, `value-measures.ts`)
  — Dataset + role autocomplete, threshold fields left blank for the server default and re-filled with the values
  in force after a run, `truncated` / `unvalued` shown as counts, *Watch* only over the open Investigation's own
  Dataset and roles, sending the answered `measure` block verbatim. ⛔ `valueMeasureQuery` never sends a `filter`.
  Since 2026-09-30 the window may instead be ROLLING — `last: <N>h|<N>d` (exactly one of it or `from`/`to`, same
  31-day cap), stored relative and resolved at every read, bind and sweep against the server clock in **UTC**
  (statements run with DuckDB `TimeZone=UTC`; a naive `timeCol` is assumed UTC); the answer's `window` states the
  `[from, to)` it read. `cashOutConcentration` takes an optional `agentList` — an Entity List of Entity Type
  `agent` (other type 422, unknown 404, retired 409) — restricting the answered agents to its live exact members
  under the list's sealed normaliser (the share's denominator stays all cash-out); a masked list answers its own
  tokens, never raw values. **SPA (2026-10-04):** the form takes a rolling *Or the last* window (`7d` / `24h`; From/To
  became optional and `valueMeasureWindowIssue` refuses both-or-neither before any call, as the server's exactly-one
  rule does) and states the `[from, to)` UTC the server resolved (`window`, with "rolling, the last 7d"); for
  *Cash-out concentration* an *Agent list* autocomplete lists non-retired Entity Lists of Entity Type `agent`
  (suggestions only — a missing list capability leaves it free text). `last` and `agentList` travel only where they
  apply, and *Watch* binds the answered `measure` block verbatim, so a rolling window stays rolling.
  **Standing detection (LA-LIVE-DETECTION-1 LD-1 to LD-3, 2026-10-04; operator decision D-LD1 = option A).** A
  value-measure rule reads the live Dataset on every Alert sweep, and a sweep has no caller, so it is **not
  evaluated until the Investigation's owner enables it**: `POST /inv/investigations/{id}/standing-detection
  {rule}` (`canAuthorAlertRules`, owner only, a bound value-measure rule only). The sweep acts as
  `sweep:<investigation id>`, which holds no capability; enable records the owner's authority under `standing` in the
  rule's binding (id, capabilities, data scopes, IdP attributes, Dataset, a masking snapshot `{mode, columns[]}`), and
  `StandingDetection` RE-DECIDES it before every Dataset read: the owner id or a **`user`** share still grants view on
  the Dataset's CURRENT envelope (`ComponentAccess.canViewAs`; **a role share cannot be re-resolved off a request, so
  a role-only grant is refused**, and `canConfigureAccess` is never honoured), the owner is still a lead, the masking
  basis has not **tightened**, and the PDP (asked as a synthetic Subject, `RowScope.visibleAs`) does not DENY. Every
  refusal is `Reading` EMPTY with a code (`NOT_ENABLED · NO_OWNER · BINDING_CHANGED · DATASET_GONE ·
  DATASET_NOT_SHARED · ROLE_SHARE_ONLY · NOT_LEAD · MASKING_TIGHTENED · POLICY_DENIED · UNDECIDABLE`) and an audit
  event (`LINK_STANDING_DETECTION_ENABLED / _SWEPT / _REFUSED`, aggregate only, never an id); undoing the cause
  resumes the sweep without re-enabling, and re-enabling re-snapshots. ⚠ Residuals: the synthetic Subject's grants are
  the enable-time snapshot and carry no role names, so a DENY keyed on `subject.roles` cannot be reproduced. **LD-6 (SPA, 2026-10-04):** under a bound value-measure rule the Value Measures panel
  shows *Enable standing detection* (`LinkAnalysisStandingDetectionComponent`; shown on `canAuthorAlertRules`, the
  server's 403 is the owner check because the SPA host edge exposes no actor), the answer's sweep principal
  `sweep:<id>`, masking snapshot and enabled time, and every refusal code in plain language
  (`standing-detection.ts` `STANDING_REFUSAL_HELP`: what it means, what the analyst does). A *Monitoring* block
  (`LinkAnalysisStandingMonitorComponent`) counts `_SWEPT` / `_REFUSED` events from `GET /events/search` — counts and
  reason codes only, never an Investigation or entity id. **LD-7 (2026-10-05): read-back, list, edit.** `GET /inv/investigations/{id}/alert-rules` (`InvestigationMeasureRoutes.listBound`; read gate = the Investigation's `openForRead`, no capability, 404 for a non-member, 503 without a write root or alert engine) lists the armed rules whose `investigation` is this one and that have a binding, each with `valueMeasure`, `edited` (hash mismatch: a sweep refuses it) and `standingDetection {enabled, principal?, enabledAt?, dataset?}` (never the recorded capabilities, masking or a value). `LinkAnalysisBoundRulesComponent` (`inspecto-link-analysis-bound-rules`, inside the Value Measures panel for the open Investigation, re-read after a Watch) shows that list, so Enable/Disable state survives a reload (`LinkAnalysisStandingDetectionComponent` takes it as `initial`; this session's enable/disable answer overrides it), and edits a rule in place through the existing `PUT …/alert-rules/{rule}` (severity for every rule, plus the threshold of a Working Set rule; the body is rebuilt from the listed rule, the name never changes; saving drops the owner's authority and says so). It replaced the old post-Watch standing panel, so there is one Enable button per rule. The SPA still calls no template list route (template sharing declined, D-LD6). Decisions D-LD2 to D-LD18 are recorded as "decided by the assistant,
  pending operator confirmation" in `docs/archived-documents/plans-archive/la-live-detection-design.md` §8.
  **LD-4 and LD-5 (2026-10-04).** The Job Type **`la.detect`** (no parameters, `requires: [alerts]`, Signal
  `la.detect.completed` carrying a count and Alert Rule names only) is a clock over the existing evaluation: it calls
  `AlertAccess.evaluateInvestigationRules()`, which runs only Investigation-bound rules through the same probe as the
  full sweep, so the authority re-decision above applies unchanged. It fails the Run closed when the `alerts` service
  or the Link Analysis module is absent; a dry run evaluates nothing. Author it as an ordinary Job; its cron is the
  cadence. Also: `GET /inv/investigation-templates` (the caller's own templates, newest first, summaries with counts;
  sharing is not built), `DELETE /inv/investigations/{id}/standing-detection/{rule}` (disable; idempotent; the rule stays
  bound and a sweep refuses `NOT_ENABLED`) and `PUT /inv/investigations/{id}/alert-rules/{rule}` (edit in place; the new
  hash replaces the old and **the recorded authority is dropped**, so the owner enables it again). Wire shapes:
  `docs/archived-documents/plans-archive/la-live-detection-design.md` §9.

* **Settings ▸ Link Analysis** (2026-09-30, `settings/link-analysis-settings.component`): the four-eyes thresholds
  (`fourEyesBudgetAbove`, `fourEyesFanOutAbove`), `mergedDistinctCap` with `mergedDistinctCapInForce`, and
  `seedByDistinctCap` only when the server reports that key. ⚠ `PUT /settings/link-analysis` REPLACES the
  document, so the save sends every other stated key back as read (node caps, masking mode, Entity Types).
  Since 2026-10-01 the document also holds `graphRun` (`graph_run` in `link-analysis.toon`; LA separation D-4 step 6):
  `{maxNodes, maxEdges, timeoutMs}` = the DEFAULT budget of a server graph run that states none (clamped to the
  server's hard ceilings and echoed by `GET /inv/graph/algorithms`), `{threads, queue}` = the Space's graph-run workers
  and waiting line (read when the Space's service is first used - restart to change). Every key absent = the shipped
  default. The form has no field for it yet; a save round-trips it untouched (pinned by the component spec).
* **The server half runs the SAME motif over the whole Dataset** (LA-14b, 2026-09-23):
  `POST /inv/pattern/branching` (`inspecto-geo-link` `PatternRoutes` → `PatternQueryCompiler` →
  `BranchingPatternEngine`). 🔴 **Why:** the projection is capped (2 000 links, `cnt DESC`; and 500 nodes in the
  browser), and structuring legs are small one-offs — they sort LAST and are cut FIRST, so on a large feed the
  browser matcher looks at a graph the ring was removed from. **Split of work:** SQL pushes each stage's kind,
  threshold band and the query `filter` into the `WHERE` of the whole Dataset and prunes with NECESSARY
  conditions only (stage-0 anchor breadth; a later leg must leave a node the previous stage reached, strictly
  after the earliest leg that reached it); the windowed per-branch search then runs in Java over those few legs,
  a line-for-line port of the TS matcher. Node keys are normalised IN SQL with `normalizeEntityKey`'s rule, so a
  breadth count sees the nodes the browser draws. Fences: every value bound, every identifier checked against
  the relation's real columns (threshold attrs too), R3 via `InvRoutes.relationFor`, ≤ 100 000 legs
  (`legCapped` + `truncated`), a 5 s statement timeout, the work budget, a match limit (200, ≤ 1 000).
  Refusals are a 200 with `refusal` in the browser's words. Audited `LINK_PATTERN_MATCHED`; read-shaped exemption.
  ⛔ **Parity is the contract:** `link-analysis/branching-parity.fixture.json` is asserted by BOTH
  `branching-parity.spec.ts` and `ControlApiInvPatternTest` against one `expected`. The toolbox offers
  **Run on server** only when the graph is truncated and an edge mapping is loaded; the answer is merged onto
  the working set (`branchingResultToGraph`) like LA-11's paths. ⚠ Differences, deliberate: no node cap
  server-side, and a stage `nodeKind` other than `entity` matches nothing (every projected node is an entity).
* 🔴 **`followsInTime` also closed an LA-14a hole**: an un-ordered hop with no time was recorded as `NaN`, and
  a later ordered hop compared against it with `t <= NaN` / `t - NaN > gap` — both false — so an unknown
  time silently counted as "in order". Pinned by a spec that goes red on the old comparison.
* **`POST /inv/schema/overlap-profile` measures what naming only guesses** (LA-15). The sibling
  `GET /inv/schema/relationships` infers a foreign key from a `<base>_id` column name; this one measures
  the real overlap of two columns' value sets, so an implicit join with no naming hint is visible and a
  name match whose values never meet can be discounted. Jaccard comes from inclusion–exclusion over
  `APPROX_COUNT_DISTINCT` — `|A∩B| = |A|+|B|−|A∪B|` — so no values cross into the JVM and there is no
  cross join. Pair count is the only quadratic axis and is capped, with the true total still reported.
* 🔴 **A relation must be passed as `relationSql`, never inlined as a subquery.** `QueryExecutor.run`
  registers the relation BEFORE it seals the sandbox, and that registration is the only place
  file-reading SQL may run — an inlined subquery works against a VALUES-backed test fixture and is
  refused against a real Parquet-backed Dataset, so the fixtures would never have shown it.
* 🔴 **An expand used to drop `truncated`** (LA-02): `mergeGraphs` returns a bare `G6GraphData` and
  structurally loses the flag, so a neighbourhood that hit the row limit or the node cap read as a
  complete finding. `expandNode` now carries it onto the signal, monotonically — only a fresh `run()`
  resets it.

* **A saved view says what it is on every surface that offers one** (decision D-S1). A view stores the
  QUERY and its presentation, never result rows, and no backend read is version-addressable — so reopening
  re-projects against whatever the Dataset holds now. One exported constant, `SAVED_VIEW_NOT_EVIDENCE`,
  is stated in the saved-views menu (before the analyst picks one), on the dashboard widget, and alongside
  the pre-existing wording in the Attach-to-Case dialog. 🔴 **The dashboard tile was the surface that
  most needed it and the one nobody had listed** — a tile reads as a fixed report and is in fact a live
  re-projection on every render. ⚠ This is a LABEL, not a guarantee: making a view reproducible is LA-03,
  and needs a durable snapshot store that does not exist yet.
* **The saved-view widget draws the studio's legend and, on a Menu item, the view's description** (R3-04,
  2026-09-26). `LinkViewWidgetComponent` (dashboard tile + Menu item) mounts `<inspecto-link-analysis-legend>`
  over the canvas, fed by the SAME `legendItemsFor` / `legendEdgeKindsFor` the studio now calls
  (`link-analysis-overlays.component.ts` — kind → count, colour = the view's `display.nodeColors[kind]` else
  `nodeColor(kind)`, super-nodes never counted), open unless the view saved `view.legend: false` (the studio's
  own restore default); the viewer's toggle is never saved. With `[showDescription]="true"` — set by
  `MenuArtifactComponent`, whose host owns the page title — the view's `description` renders beneath it through
  `<app-dashboard-header>`, the line Dashboards use; a tile leaves it off (the tile card has its own title).
  🔴 **Node kinds are NOT derived by the profile or from id prefixes — nowhere in the SPA.** A single
  `query.projection` (`projectTriples`) stamps EVERY node `kind: 'entity'`, so a legend over the telco demo's
  `fraud_entity_graph` / `simbox_ring_jeddah` reads one row, *entity*, in one colour — in the studio too.
  `profile: telecom` shapes tile labels, measure/time columns and suggested tools only. The only kind-bearing
  path is an LA-08 multi-projection, `query.multi.nodes[]`: one `MultiNodeMapping {dataset, idColumn,
  category}` per kind, `category` a CONSTANT stamped on every node that mapping yields (there is no per-row kind
  column and no per-mapping filter), so the data needs one Dataset (or view) per entity kind — e.g. SIMs, IMEIs,
  Cells, Dealers — each listing its ids, plus the existing link Dataset as `query.multi.edges[]`. Deriving kind
  from a value prefix ("SIM …", "IMEI …") would be a new product rule, not built.
  ✅ **Category colours (2026-09-27, R3-04):** a category is free-form (not a catalog `NodeKind`), so
  `nodeColor()` used to paint every one the fallback grey even on the multi path. `projectMultiResult` now
  stamps `data.color` per category from `CHART_CATEGORICAL_NEUTRAL` in first-seen order (unmapped endpoints
  stay plain `entity`, uncoloured); the canvas already honours `data.color` and `legendItemsFor` reads it
  (a saved `display.nodeColors` override still wins). The widget's legend + description already shipped.
  ✅ The telco `simbox_ring_jeddah` / `fraud_entity_graph` views moved to `entity-projection-multi` on 2026-09-27
  (operator: the generator split, not an id-prefix rule). There is one node Dataset per kind (SIM / Device / Cell /
  Dealer) and one edge Dataset per link kind, all derived from the same link rows, so node ids and edge endpoints
  match. Driven: the ring shows four colours and a four-row legend.
* **"The ops module is absent" and "the Case lookup failed" are two states, and Attach-to-Case now says
  which** (found 2026-09-22 while grounding D-S1). The dialog fills its Case picker from
  `GET /objects?type=CASE` and used to fall back to `LinkAnalysisSnapshotsService.mockCases` on ANY error,
  so an ops service that was down, unauthorised or unreachable rendered exactly like an edition that
  simply has no ops module — two placeholder Cases, offered as attachable targets, in the one dialog whose
  purpose is attaching EVIDENCE. 🔴 **Attaching evidence to a fabricated Case id is a silent wrong answer
  in an investigative tool**, and nothing downstream would have caught it. The placeholders survive on the
  ops-absent path (a deployment fact the analyst can act on); a real error clears the list and renders an
  `<inspecto-alert variant="error">` in the picker's place. The three states live in ONE place,
  `LinkAnalysisCaseFieldComponent`, so the two dialogs that ask for a Case cannot drift on them.
  ⚠ The snapshot/attach flow is still client-side in the SPA, so this is about the UI's honesty, not
  about persistence. (LA-03's BACKEND half shipped 2026-09-22 — `SnapshotStore`, `POST /inv/snapshots`
  + `/attach` — but nothing in the SPA calls it yet.)
* **Saving the analysis and attaching it to a Case are ONE action, and the Case half is optional**
  (decision 2026-09-22, operator). The snapshot dialog and the attach dialog merged into **Save this
  analysis**: name, description, the frozen-content summary, and an *optional* Case. 🔴 **This reversed
  the same day's first answer to the errored lookup, which disabled the submit button** — correct for
  Attach-to-Case, where attaching to nothing is not an outcome, but wrong for saving: it made an ops
  service being down cost the analyst their analysis, not merely the attachment. Save now stays enabled
  when the lookup fails and the analysis is kept unattached. ⛔ The picker still offers nothing in that
  state — an unattached analysis is honest, an analysis attached to a fabricated Case id is not.
  **Attach-to-Case survives as a second action** for an analysis saved without a Case; there the Case is
  genuinely required, and a `caseId` that reaches its form while the lookup is errored is still refused,
  because nothing offered it and so nothing vouches that the Case exists.
* **A Case page opens Link Analysis on itself** — an `<a>` (not a button: it navigates, so it carries a
  real href) in `object-detail`'s header actions, rendered for `objectType === 'CASE'` only, pointing at
  `/studio/link-analysis?case=<id>`. The pane reads that param and pre-selects the Case in the save
  dialog, so an analysis started from a Case is saved straight back onto it. ⚠ The param is **deliberately
  not stripped** (the `?open=` rule, not the `?create=1` one): it is the address of a Case-scoped
  investigation and has to survive a reload and a bookmark.
* **A new Case can be created in place, MINTED from graph nodes** (`LA-CASE-CREATE-IN-PLACE-1`, operator
  decision 2026-09-23; built the same day). The Save dialog's Case box offers *An existing Case, or none* /
  *A new Case, from graph nodes*; the second asks a Case title and shows a checkbox list of the nodes that
  can become members, pre-ticked from the canvas emphasis (`selectedNodeIds`, passed by the host from
  `emphasis()`), with a filter once there are ≥ 8. ⛔ **The 2026-07-22 rule stands** (`object-create.dialog.ts`:
  *a case CONTAINS its members*): an empty Case was offered to the operator and NOT chosen, so zero picked
  nodes is refused on screen and by the server alike.
  - **Object type: INCIDENT.** A Case's Contents are Incidents (GLOSSARY §9), and every Case surface — merge,
    split, Case Rules, the Contents list — speaks Incidents; a new `ENTITY` object type would have had to
    teach all of them, plus workflow, FindingsSpec and the SPA's type lists. A minted Incident is titled with
    the node's raw spelling and described *Raised from Link Analysis: Entity … in Dataset …*. ⚠ The cost is
    real: these land in the Incidents inbox like any manually raised Incident.
  - **Identity = `entityKey` + `entityDataset`** (attributes on the Incident): the node id
    (`entity:[<scope>:]<D-S4 key>`, or typed `<type>:<key>` for a classified column — LA-17 D-M6; the server's
    `EntityMember` accepts `^[a-z][a-z0-9_]{0,31}:.+`) plus the source
    Dataset — the node's own `provenance` when recorded (several merged Datasets joined, sorted), else the
    Dataset the pane projected. Minting the same pair again REUSES the object; a match the caller cannot see
    (data scope) is not reused, so existence-hiding holds. The lookup is `ObjectStore.findByAttributes` —
    portable SQL (`LIKE` on the exact JSON fragment, re-checked exactly), and it THROWS on a store error,
    because an empty answer there means "mint", i.e. a silent duplicate. Which nodes qualify:
    `case-members.ts` `caseMemberCandidates` — a node with an `objectRef` to an Incident joins as that
    Incident; stranded nodes, super-nodes, Case refs, non-Entities and Entities with no knowable Dataset are
    excluded.
  - **One route, because composition could not work:** `POST /cases/from-entities` (`ObjectRoutes`, gated
    `canManageIncidents` like `POST /objects`) mints/reuses and opens the Case with its `CONTAINS` links.
    `POST /objects` cannot build this — it needs an existing link target, which a fresh space lacks.
    Everything refusable is checked before the first write; the writes run under compensation
    (`ObjectService.openCaseFromEntities` removes every object it created, with links/notes/tag edges, if
    any write throws; reused objects are never touched). Personal: 503 via `AbsentObjectRoutes`, and the
    dialog disables the option with the reason (`SessionService.opsEnabled()`); a 403 surfaces as the
    server's message.
  - **Order: seal → create the Case → attach.** A refused Case leaves the snapshot sealed and unattached and
    the dialog OPEN, with the seal remembered (title/description lock), so *Save* retries only the Case step;
    a failed attach after the Case exists flips the form to the existing-Case path with the new Case picked,
    so a retry attaches and never opens a second Case. The host's toast names the Case it attached to.
* **Entity ids are NORMALISED, and the projection still reports the spellings it folded** (decision D-S4,
  as built 2026-09-23). Link Analysis stays value-projected (no alias resolution, no entity model), but every
  id goes through ONE key, `normalizeEntityKey` (`inspecto/graph/entity-key.ts`: case-fold, collapse internal
  whitespace, strip trailing punctuation, trim), so `ACME Ltd`, `acme ltd` and ` Acme  Ltd.` are one node
  (`entity:acme ltd`) with summed edge counts. The label stays the first raw spelling — never the lowercase
  key — and each node carries its distinct raw `spellings`. `projectTriples` now folds server triples that
  normalise to the same edge id (summing `count`). ⚠ Ids changed case: a saved view or export holding
  pre-D-S4 raw ids no longer matches freshly projected ids.
  `splitIdentityGroups` uses the SAME `normalizeEntityKey` (its private `identityKey` copy is gone) and
  reports a group when distinct raw spellings exceed distinct keys — from a node's `spellings`, or from
  raw ids on a pre-D-S4 graph. The working set counts and names them in both the expanded panel and the
  minimized pill; the hint now says they are counted as ONE entity, because two spellings genuinely can
  be two entities and the analyst must see the fold. `tools/check-split-identity-fixture.mjs` mirrors the
  key in plain Node (it cannot import TS) — change both together.
  ⚠ Comparison is scoped: `entity:person:bob` and `entity:account:bob` are two entities by construction,
  and super-node stand-ins are skipped because their label is a count, not a name.
  🔴 **It reads ZERO on every dataset this repo ships, and that is CORRECT** — measured 2026-09-22 across 24 candidate entity columns in 4 spaces, including all five configured projection columns: distinct-raw equals distinct-normalised exactly, with zero untrimmed values, zero double-spaces and zero trailing punctuation. `gen-link-analysis-demos.py` emits every entity from a canonical literal list, so a variant spelling is impossible by construction. ⛔ **Do not read a zero as a broken detector** — a positive control collapses 6 spellings of `ACME Ltd` to 2. It is a guard against dirty data the demo corpus does not contain.
  🔴 **There are THREE id mint sites, not the two D-S4 named** — `geo-analysis.ts`'s `coLocations` /
  `coLocationGraph` is the third; it now normalises too (the fold identity, node ids AND edge endpoints —
  edges previously pointed at `entity:<label>` while nodes used the key, so a keyed projection produced
  dangling edges).
* ⛔ **The analysis cap's refusal was already graceful, and its NUMBER is now answered** (D-S3: keep 500 / 2 000, and give suspicion score its own 750 — see below). `requireUnderCap`
  throws, but all ten sites catch it, clear the stale result, and render the message in a warning alert
  naming the algorithm, the cap and the actual size. Converting the ten to an outcome value was **refused
  as churn** — it changes nothing the analyst can see.
* **A recursive CTE survives `QueryExecutor`'s derived-table wrap** (`QueryExecutorRecursiveCteTest`,
  the empirical check D-S2 demanded before LA-11 could be costed). Bounded multi-hop traversal over an
  edge relation is expressible in the shape LA-11 would compile. 🔴 **But the executor's `LIMIT n+1` is
  applied OUTSIDE the derived table, so it does not bound the recursion** — it truncates an answer the
  walk has already paid for. A traversal fence must live INSIDE the recursion; on a cyclic graph the
  in-recursion depth predicate is the only thing preventing an unbounded walk.
* **`POST /inv/traversal/recursive-paths` walks multi-hop paths server-side** (LA-11, backend shipped
  2026-09-23). One recursive CTE over the edge Dataset returns the simple paths from `startNode`
  (optionally only those ending at `targetNode`), with `direction`, `weightCol`, a `filter` validated
  like LA-01's, and `temporalConstraint` (monotonic timestamps, total-duration bound). Every fence is
  INSIDE the recursion: **depth** is a bound `?` (default 6, hard cap 10), **cycles** are refused by
  `list_contains` on the path, **edge yield** is a bound `LIMIT` per recursion level (sets
  `edgeYieldCapped` + `truncated`), and a **5 s timeout** comes from a route-local `SqlSandboxPolicy`
  through the new `QueryExecutor.run(Request, policy)` overload. Audited as `LINK_TRAVERSED`. The body
  uses this file's `dataset`/`sourceCol`/`targetCol` names, not §5.3's `edgeDataset`/`sourceColumn`.
  **SPA wired 2026-09-23** — the toolbox's *Find paths (server)* group (`InvService.recursivePaths`): From
  (required) and To (optional, blank = any node) over the loaded query's edge mappings, max hops, and
  follow-direction / either-direction. The host sends the node's FIRST RAW SPELLING (the server compares
  `CAST(col AS VARCHAR)` exactly, never the normalised id) plus the pushed `filter` (AND a multi mapping's
  own), and `recursivePathsToGraph` mints every hop through `entityId()` with the mapping's `entityType`, reuses
  a working-set link between two hops in either direction, and ADDS any node or `path` link the walk reached
  beyond the loaded slice so every path can be highlighted. The answer states `searched up to N hops`,
  the longest path, and a warning for `edgeYieldCapped` / `truncated`. ⚠ The response has **no
  `depthUsed`** — what the pane shows is `fences.maxDepth`, the clamped fence the server applied. ⚠ A
  folded node (several spellings) starts the walk from its first spelling only.
* **`POST /inv/projection/multi` is wired as its own GraphSource** (LA-08 SPA half, 2026-09-23):
  `entity-projection-multi`, "Entity/Link (several Datasets)" in the query dock. Rows are node mappings
  (Dataset · id column · label column? · category?) and edge mappings (Dataset · source · target · link
  type?); a half-filled row is refused, never dropped, and more than 16 is refused before the server 422s.
  `projectMultiResult` mints every id UNSCOPED through `entityId()`, so one normalised key from several
  Datasets is ONE node carrying `data.provenance` (shown in the hover tooltip and the node-detail
  *Datasets* row) and every raw spelling in `data.spellings`; edges fold across Datasets with summed counts
  and their own provenance. The per-mapping `mappings[]` summary renders under the canvas alerts with each
  mapping's own `truncated`. A 404 — one Dataset unknown OR not viewable — reads as a refusal of the WHOLE
  query with no partial graph (`invErrorMessage`); a 422 shows the server's reason. ⚠ Deliberately not
  wired until 2026-10-04, now shipped (`LA-SPA-OWED-SURFACES-1`): each node mapping and edge mapping row has an
  *Attribute columns* multi-select (`attributes`), and each edge mapping its own *Filter* (the shared condition
  editor over that Dataset's typed columns — an empty tree sends no `filter`; the pushed request filter still applies
  to EVERY edge mapping, the row's own narrows only it). A node mapping's attribute values (RAW) land on the node
  (`data.attrs`, merged across the mappings that name it, first mapping wins a clashing key) and show as rows in the
  node detail dialog; an edge's attributes split a folded pair as on the single-mapping path. **Expand** now works on
  this source: `MultiProjectionGraphSource.expand` reads each EDGE mapping's one-hop neighbourhood of the node's raw
  spellings (`GraphSource.expand`'s new optional 4th argument, `data.spellings`, at most 8 per mapping) through
  `POST /inv/projection/neighbors` — the same Dataset, columns and `attributes`, `filter` = the request filter ANDed
  with the mapping's own, link type = the mapping's constant `type` — and folds the answers with
  `projectMultiResult`, so an expanded edge has the same id as a queried one and merges rather than duplicates.
  ⚠ The existing `entity-projection` multi-mapping path (N × `/inv/projection`, type-scoped ids) is
  unchanged — the two coexist.

* **Suspicion score carries its OWN cap, lower than the shared one** (D-S3, 750 by default, a third
  per-space setting beside the projection and analysis caps). Sizing one limit for 27 algorithms forces
  a bad trade: 25 of them finish under 60 ms at 2 000 nodes, and suspicion score takes ~7 s there.
  🔴 **The number is measured and the curve is QUADRATIC** — 250 → 103 ms, 500 → 402 ms, 750 → 972 ms,
  1 000 → 1 608 ms, 2 000 → 7 277 ms, because betweenness dominates the blend. Doubling the nodes costs
  ~4.5× the time, so halving the cap cuts the work to about a quarter, not a half. 750 is the last
  measured point under a second; the interpolated crossing is ~760, and a default should be a number
  someone observed. ⚠ Like the other two it is a DEFAULT, not a truth, and it fails closed on nonsense
  (a cap of 0 or NaN is ignored and the previous value stands). The refusal names THIS cap, so an
  analyst who can run every other tool at 2 000 nodes is told why this one stopped sooner.

* **An evidence snapshot is SEALED by the server, and a failed save does not look like a saved one**
  (LA-03, shipped 2026-09-23). `POST /inv/snapshots` writes one immutable file per id and answers **409 on
  a re-POST**; `POST /inv/snapshots/attach` appends to a separate log and **never reopens the sealed
  record**, because rewriting it would change its bytes and invalidate the `manifestHash` that makes it
  evidence. ⛔ The client signal updates **only after the server confirms** — the previous `add()` was a
  synchronous mutation that could not fail, so the dialog always closed and the analyst always believed the
  analysis was kept. A refused seal now leaves the dialog open with the work intact. ⚠ A failed ATTACHMENT
  does not fail the save: the snapshot is already sealed, and saying otherwise would be a lie about
  evidence that exists. 🔴 Removing the old methods compiled clean — **a DI change is invisible to
  type-checking**, and only the specs found a caller constructing the service outside an injector.
* **An Investigation is a server-side, append-only op log** (LA-10, backend shipped 2026-09-23;
  `InvestigationRoutes` + `InvestigationEvaluator` in `inspecto-geo-link`). `POST /inv/investigations` binds
  one Dataset + projection mapping; `/{id}/ops` appends `seed` · `expand` (one hop) · `exclude` (reason
  required) · `hide` · `keep` and answers the Working Set **delta** + `truncated`; `/{id}/undo` is a real undo
  (a recorded log edit — the state after it is byte-identical to the state before the undone op);
  `/{id}/replay` re-evaluates the whole log and `GET /{id}/log` renders each step as a plain-language line.
  ⚠ Replay uses PREFIX semantics — position k honours only the undos at or before k; until 2026-09-23 it
  resolved undos across the whole log and reported an untampered log with an undo as not equivalent.
  ⛔ **Nothing is pinned** (D-E3): `datasetVersion` is always `null`; each `expand` instead SEALS the rows it
  read with a SHA-256 fingerprint, so replay cannot move when the data grows, and `replay {reread:true}`
  re-runs every recorded query and reports drift per step rather than serving it. ⛔ **Re-ordering forks**
  (D-E4): `/{id}/reorder` creates a new Investigation whose header names its parent and order; the
  original log, its per-step Working Sets and any snapshot anchored to them stay byte-identical. The log and
  Working Sets live in the snapshot store (D-E2, `audit/snapshots/investigations/<id>/`). Access is
  **owner-only** (a non-owner reads 404), writes need `canManageIncidents`, and a bound Dataset shared away
  from the caller makes the Investigation read as absent.
* **Case-team sharing** (LA-24, decision D-U10, shipped 2026-09-30; `InvestigationCaseRoutes`). An
  Investigation may carry an OPTIONAL `caseRef` — `caseRef` on create, `PUT`/`DELETE /inv/investigations/{id}/case`
  (owner-only, `canManageIncidents`; linking also needs a Case the caller can see, and refuses a closed one 409).
  It lives in `case-link.json`, OUTSIDE the sealed header, so it moves neither the header nor the Dossier manifest.
  The linked Case's owner or assignee gets **READ-ONLY** access — log, Working Set, Dossier (+ verify), measures,
  `GET …/case` (`access: case-member`, `readOnly: true`) — decided live on every read through
  `InvestigationRoutes.openForRead`; every write stays on the owner-only `open`, and a non-member still reads 404.
  ⛔ Membership is a separate explicit grant, **not a policy ALLOW**: the R3 Dataset check and the Enterprise PDP
  still run after it and can only narrow (a DENY hides it from members too). ⛔ **Independence:** geo-link names
  no `inspecto-ops` type — it reads the Case through core's `ObjectAccess.summary` (owner, assignee, `closed`),
  empty without the module; then the link is stored `verified:false`, grants nothing, and `GET …/case` says why.
  Fail closed (for operator review): a closed/deleted Case or a member who leaves ends access; a fork or template
  instance does not inherit the link.
* **An `expand` is one hop-ladder rung, and a `window` op sets the time window** (LA-13, backend shipped
  2026-09-23; `InvestigationRoutes`, `InvestigationTime`). Rung fields: `direction` (either · out · in ·
  reciprocal) · `linkKinds` · `window` (`inherit` — the default — · `full` · an override) · `minEvents` ·
  `minDistinctDays` · `candidateDegreeMin/Max`, evaluated over the WINDOWED graph of the whole Dataset, not the
  frontier · `maxFanOut` (strongest first, reported as `fanOutCapped`, never as `truncated`) · `budget` (row cap;
  a breach sets `truncated`). ⚠ `limit` was renamed `budget` and is **refused** (422), not silently defaulted.
  The rung is resolved and sealed as `read.query`, so `reread` re-runs it exactly. ⛔ **Timezone contract**
  (DuckDB's session zone is the host's, so it never touches a value): the Investigation binds `timeCol`; a naive
  `TIMESTAMP` is read as wall clock in the declared `timeColZone` (default `UTC`, recorded in the header), a
  `TIMESTAMPTZ` is an instant and refuses a zone; `from`/`to` must carry an offset and form a half-open range
  compared as epoch ms; a `slot` (`22:00–04:00` crosses midnight; start inclusive, end exclusive) and a `days`
  mask need an explicit IANA `timezone` and test the local day the EVENT fell on. `window` re-filters nothing
  already admitted — earlier sealed reads are evidence as made. Templates carry the whole rung; a `window`
  becomes a parameter (`kind: "window"`) whose default is the authored window. ✅ **Calendar exclusions
  (2026-10-05):** a window's `exclude` list removes named dates / inclusive date ranges (holidays) beside the slot
  and day mask. Entry = `"YYYY-MM-DD"` | `{date}` | `{from, to}`, each with an optional `name` (1–80 chars), at most
  366, canonicalised to `{from, to[, name]}` sorted by date so the same calendar seals the same bytes. They are
  wall-clock like the mask: they test the LOCAL calendar day the EVENT fell on in the window's `timezone`, which
  they therefore require (no default). One implementation, `InvestigationTime.predicates`, so every reader that takes
  a window honors them (expand, the `window` op, `compare`, the Dossier's rung read) and `describe` names them in the
  log line; `GET …/coverage` treats an excluded day as not EXPECTED, so a holiday is not reported as a gap. Calls:
  per-date (not per-instant) exclusion; ranges inclusive at both ends (unlike the half-open `[from, to)` instants,
  because they name calendar days). The SPA window form
  takes them as one text field (`2026-12-25, Winter break=2026-12-24..2026-12-26`). ✅ **Recurring exclusion rules
  (2026-10-05):** an `exclude` entry may be `{rule:"yearly", month, day[, name]}` (every 25 Dec) or
  `{rule:"nthWeekday", month, weekday, nth[, name]}` (4th Thursday of November; `nth` 1–5, a 5th a month lacks never
  matches; yearly `02-29` matches leap years only). Calls: **the RULE is sealed, not its expansion** — the sealed
  bytes stay canonical, an unbounded window (no `from`/`to`) needs no horizon, and a replay re-derives the same days;
  so "expanded over `[from,to)`" is realised as a per-day test on the local calendar day in the window's
  `timezone` (SQL `month(lt)`/`day(lt)`/`isodow(lt)`/`(day(lt)-1)//7+1` in `InvestigationTime.predicates`, the same
  arithmetic as `InvestigationTime.matches` used by coverage; no clock, no host zone). Canonical order: dates (by
  date) then rules (by month, day or nth/weekday, name); caps `MAX_EXCLUDES` 366 dates + `MAX_RULES` 32 rules; an
  unknown `rule`, bad month/day (`04-31`, `02-30`), weekday or `nth`, or extra keys is 422 and never logged. A window
  with no rules seals byte-identically to before (pinned by `InvestigationTimeRecurringTest`). SPA text syntax
  `[Name=]*-MM-DD` and `[Name=]*-MM-DOW#N` (`Xmas=*-12-25`, `*-11-THU#4`), client-validated against the same bounds
  (a11y spec in `investigation-window-op.component.spec.ts`). ⏳ Not built: "last weekday of month", Easter-style
  computed holidays, lunar calendars. ✅ The rest
  (`threshold`, `snapshot`, comparison mode — read route, then the sealed `compare` op on 2026-10-05 —, time-respecting paths and burst / periodicity shipped 2026-10-04, below). **SPA (2026-10-03, `LA-SPA-OWED-SURFACES-1` slice):** the expand form's collapsed *Advanced expand
  settings* (`investigation-expand-rung.component`) sends `budget` (1–20 000; the server CLAMPS above, so the SPA
  refuses) · `direction` · rung `window` (`inherit` · `full` · *A window for this rung…*, an override object) · `linkKinds`
  (comma-separated, trimmed and deduplicated, at most 100; blank = all kinds; the server 422s it without an Investigation
  link-kind column) · `minEvents` ·
  `minDistinctDays` · `candidateDegreeMin/Max` (min ≤ max) · `maxFanOut`, only the fields set (blank = server
  default), to BOTH expand buttons; per-rung `truncated` stays the existing *Incomplete Working Set* alert. The
  *Time window* form (`investigation-window-op.component`) appends a `window` op: ISO instants WITH offset/Z,
  an `HH:mm` slot (a crossing-midnight note), a day mask, an IANA zone from `time-zones.ts` — required with a slot
  or mask, client-side as on the server — or *All time* (`'full'`). Bounds live in `investigation-rung-form.ts`;
  a server 422 still renders verbatim in each form. The window FIELDS are one component
  (`investigation-window-fields.component`) shared by the `window` op form and the rung override, so both send the
  same object the server validates (`from`/`to` with an offset, an `HH:mm` slot, a day mask, an IANA zone required
  with a slot or mask); the override applies to that rung only and an *Investigation's window* choice later ignores
  whatever the override still holds. *(Both shipped 2026-10-04, `LA-SPA-OWED-SURFACES-1` closed.)*
  `seedBy` / `excludeBy` shipped 2026-09-26
  over Entity Lists (`LA-17`, design §4.4) — see the Entity Lists paragraph below.
* **The Investigation tab drives it** (LA-10 SPA half, 2026-09-23): the right dock's third tab
  (`link-analysis-investigation.component` over the pane-provided `InvestigationSessionStore`, so the session
  survives the dock collapsing). *Start Investigation* needs a last run of ONE `entity-projection` mapping
  and binds its Dataset + source/target/kind columns — ⚠ the query's `filter` is NOT sent, because the
  create route takes none, and the panel says so. While one is open, a canvas click picks the entity instead
  of opening the detail dialog; the canvas draws the **Working Set** (hidden entities left off, "Show the
  query graph" to pick seeds). Ops send the node's RAW spellings — the Working Set graph is folded through
  `projectTriples`/`entityId()` so its node ids match the query graph's (D-S4), and the server's untrimmed
  value is added to `spellings` because the fold trims. Exclude has a required reason field; undo, replay
  (optional re-read → per-expand drift table) and re-order (up/down → an on-screen "this creates a fork"
  explanation → switch to the fork, whose header lineage is shown) are all wired. Truncation is shown from
  the log (every effective expand whose sealed read was cut), not only from the last response.
  ⚠ *(Superseded 2026-10-01 — see *Closed-plan record*: `GET /inv/investigations` lists what the caller may
  read and the tab has *List Investigations* + *Open by id*.)* This paragraph was written when no list route
  existed: the SPA also remembers the ids it created and saves them with the
  view (`LinkAnalysisView.investigations`: id, title, entityType, parentId); restoring a view opens none.
  ⚠ `/ops` and `/undo` answer the Working Set as COUNTS and the delta's link changes as counts, so the store
  re-reads `GET /log` + `POST /replay` after every mutation — which also writes a `link.investigation.replayed`
  audit event per step. ⚠ Not wired: the six deferred ops, a client-side `canManageIncidents` gate (a 403 is
  surfaced instead), drag-to-reorder, replay `at`.
* **An Investigation has a Dossier with a SHA-256 chain of custody** (LA-12, backend shipped 2026-09-23;
  `DossierRoutes` + `GraphDossierBuilder` in `inspecto-geo-link`). `GET /inv/investigations/{id}/dossier`
  (`?at=` a prefix, `?snapshots=` exhibits, `?format=json|steps|method`) answers summary, topology, a
  chronological ledger, centrality/risk tables, **negative space**, an integrity report and the three
  renderings: the re-runnable JSON log, numbered plain-language steps, and a method statement that cites the
  custody hash. Every exclusion, with its id, step, author and reason, appears in **all three** (G-E10).
  `format=steps|method` answer `text/plain`. **`format=html`** (LA-DOSSIER-OUTPUT-1, 2026-10-03) answers ONE
  self-contained printable page (`DossierHtml`): inline CSS with an `@media print` sheet, no scripts, no links,
  no external fetch, so a browser *Save as PDF* gives the usable PDF. It renders the SAME already-masked map the
  json answers (masking applied once, in `maskedDossier`): summary, masking, steps, method, ledger, negative space,
  topology, scores, working set, integrity and the manifest with its root. ⛔ Every interpolated value goes
  through `DossierHtml.esc` (`& < > " '`) — titles, notes and masked keys are analyst/data-controlled — and the
  response carries `Content-Type: text/html; charset=utf-8` plus `Content-Security-Policy: default-src 'none';
  style-src 'unsafe-inline'` as a second line of defence. Pinned (and mutation-checked) by
  `ControlApiDossierTest.theHtmlDossierIsSelfContainedAndEscapesEveryValue` / `…IsMaskedLikeTheJson`. No server
  PDF: no PDF library is in the dependency set. The **manifest** hashes the raw stored bytes of `header.json`,
  every log line (`log.jsonl#<step>`), every `sets/<step>.json` and every included snapshot, plus the canonical
  entities, links, exclusions and score vectors (G-R6); its `root` is SHA-256 over the manifest body and does
  not depend on when the dossier was built. `POST …/dossier/verify` rebuilds the manifest from the store and
  names each artefact that is `changed`, `missing` or `added`; an edited manifest fails its own root
  (`selfConsistent:false`). ⚠ **Two independent checks**: the `integrity` section re-checks the hashes LA-10
  recorded (per-step replay against `workingSetHash`, each set file against its own `hash`, each sealed read
  against its `fingerprint`), so the FIRST dossier after a tamper already reports it. A tamper that keeps every
  internal hash consistent, or one to the header (no recorded hash covers it), is caught by the manifest
  alone. ⛔ **No server-side graph algorithm exists**, so the dossier computes no centrality. Its score tables
  are the vectors a snapshot sealed, computed client-side, each labelled with the snapshot, its time and its
  node count. A snapshot must be **anchored**, meaning its body's `investigationId` names this Investigation,
  or it is refused (422), so a dossier cannot be used to read another analyst's snapshot. The SPA writes that
  anchor (2026-09-28): a snapshot sealed while an Investigation is open carries its id (the open
  `InvestigationSessionStore.activeId`); one sealed with none open has no `investigationId` key and so cannot be
  included in any Dossier. The anchor is outside `manifestHash`. **A snapshot captures what is on screen**
  (decision A1, operator 2026-09-30): *Save this analysis* seals `canvasData()` — the Working Set while an
  Investigation draws it, the query graph otherwise (including while one is open with *show Working Set* off) —
  so the node picker, the hashed nodes/edges and the origin all describe the drawn graph. A Working Set
  snapshot's origin is `{sourceId: 'investigation', dataset: <bound Dataset>, query: {investigationId}}` with a
  null predicate; `manifestHash` is computed in the dialog (`snapshotGraph`) over that same graph. Access matches the
  Investigation: owner-only, plus the R3 Dataset check. Neither route persists anything; both are audited
  (`LINK_DOSSIER_BUILT` / `LINK_DOSSIER_VERIFIED`). The SPA's snapshot `manifestHash` is SHA-256 too
  (2026-09-28): Web Crypto over the UTF-8 bytes of the canonical JSON, written `sha256:<hex>` — the server's
  format and canonicalisation (`InvestigationEvaluator.sha256(canonical(…))`), pinned in `graph-snapshot.spec.ts`
  by a vector computed with the server's Jackson recipe. ⚠ `snapshotGraph`/`verifySnapshot` are **async**, and
  `crypto.subtle` exists only in a secure context (https or localhost): elsewhere the Snapshot dialog refuses
  to seal and says why, never falling back to a weaker digest. The server stores `manifestHash` verbatim and
  never recomputes it — the Dossier manifest remains the custody root.
  **SPA half (2026-09-23):** the Investigation panel's *Dossier* section (`link-analysis-dossier.component`)
  renders the json dossier, downloads `steps`/`method` (and, since 2026-10-03, *Download HTML* → `<id>-html.html`) as Blobs through HttpClient at the dossier's own `at`
  (never a bare href — it would skip the bearer), and verifies either the manifest just issued or an uploaded
  file holding a manifest or a whole dossier. A failed verify names *why*: an edited manifest
  (`selfConsistent:false`), a store whose own hashes disagree (`intact:false`), a moved root, and every changed,
  missing, added and content-changed artefact. **Scope pickers (2026-09-27):** an *At step* field (blank = the
  head; validated 0..`summary.steps` once a dossier is known, since the server 422s outside it) and *Include
  snapshots…*, which lists `GET /inv/snapshots` (ids only, newest first, `limit=100`, `total`/`truncated`
  shown) as checkboxes capped at 20 (`DossierRoutes.MAX_SNAPSHOTS`). The downloads reuse the on-screen
  dossier's `at` AND `snapshots`, so file and view agree. ⚠ The list is NOT filtered to this Investigation —
  the route cannot tell — so a snapshot sealed under ANOTHER Investigation (or none) is refused 422 *not
  anchored* (surfaced verbatim). Since 2026-09-28 the SPA anchors a snapshot to the open Investigation when it
  seals it (`investigationId`), so snapshots sealed while this Investigation is open are accepted.
* **The Working Set is addressable as rows** (LA-20, backend shipped 2026-09-23; `WorkingSetRoutes`).
  `GET /inv/investigations/{id}/working-set?of=entities|links|excluded&limit&offset` answers a relation with
  fixed columns carrying provenance (`opSeq`, `seedId`, `hop`, and `reason` for exclusions), bounded with the
  true `total` + `truncated`, evaluated from the sealed log alone. It is **cached** under the hash of the
  committed log bytes, so an op, an undo or a fork can never be answered from a stale entry — and the access gate
  runs before the cache on every read. **Who may read it (D-E7, amended 2026-10-03 by D19):** a MEMBER of the
  Investigation (lead, analyst or reviewer; the creator is the implicit lead, see *Investigation members* below) or
  a linked-Case member on Professional and below (anyone else reads 404; no fallback to Dataset sharing); on
  Enterprise additionally the `PolicyEngine`'s row verdict for `resourceKind: investigation`, which can hide it even
  from a lead but never widens it. ⛔ It is
  **not** a Dataset or DuckDB view — `/bi` and `/db` cannot reach it; binding it to a Widget is LA-21.
  **SPA half (2026-09-23):** *Working Set rows* in the Investigation panel pages it with true offsets (200 a page,
  *Load more* appends) in a `<inspecto-data-table>` keyed `la-working-set-<relation>`, and states `truncated`,
  the head step and whether the answer was `cached`. An empty relation renders an empty state, not an empty grid
  (an empty ag-grid fails axe `aria-required-children`).
* **Investigation Templates and Measures** (LA-23, SPA half 2026-09-23;
  `link-analysis-template-measures.component` + `link-analysis-template.dialogs`). A Measures strip reads
  `GET …/measures`; *Watch* binds an Alert Rule to one Measure and shows the answered current value,
  `wouldFire`, and the backend's disclosure text verbatim. A 503 (no alert engine) is an explained notice, and a
  403 names the Alert-Rule authoring capability, not Incident management. *Save as template* shows what the D-E8
  extraction will do **before** the save: seeds become parameters, exclude/hide/keep are dropped, named expands
  are generalised. ⚠ The route has no dry run and templates are write-once, so that preview is a client-side
  mirror of `InvestigationTemplateRoutes.save` over the loaded log (`investigation-template.ts`). The server's
  answer, with `exact`, is shown after the save and is the authority. *Instantiate* has no template list to pick
  from (there is no list route), so it reads a template by id. It then asks one seed list per parameter, plus the
  Dataset and column roles through autocomplete loaders (the template's roles are the defaults), and opens the
  new Investigation.
* **Working Set Widgets** (LA-21, 2026-09-23; D-E6). The Investigation panel's *Pin to a Widget* saves a
  `working-set` Widget (`viewId` = the Investigation, `workingSet{relation, mode, pin{step, workingSetHash}}`).
  **Frozen** (default) re-reads `?at=<pin.step>` and refuses to show rows if the answered hash differs from the pin;
  **Live** reads the head and states the drift since the pin; the tile always shows *Frozen*/*Live*. It stores no
  rows and reads only the Investigation-scoped route, so a dashboard viewer who is not the owner sees *Not
  available to you*. ⛔ A Live Widget cannot leave the Space: the Exchange refuses Working Set Widgets (Frozen too,
  per D-E7), a bundle export converts Live → Frozen at its pin (`converted`; the SPA's export — the Transfer menu and the
  Transfer page — reads `converted` from `POST /bundle/export` and warns `Exported changed (a Live item cannot leave
  its Space): widget/<id> (live to frozen)`, because the downloaded copy is not what was selected). As-built:
  `docs/archived-documents/plans-archive/link-analysis-backlog-plan.md` §5.9.
* **Annotation and the coverage indicator** (LA-19; backend 2026-09-24 in `InvestigationRoutes` /
  `InvestigationEvaluator` / `InvestigationCoverageRoutes`, SPA half the same day in the Investigation panel).
  *Annotate* sends `{op:'annotate', ids, note}` (note required, ≤ 2 000 chars) for the selected entity's Working
  Set ids through the same `store.apply` path as exclude; the notes render from the sealed state's
  `annotations` (ABSENT when empty) as an *Annotations* list and under the selected entity, and the log line is
  the server's own text. Undo needs nothing special: the store re-reads `/replay`, whose state no longer carries
  the note. A 422 (an id not in the Working Set, an over-long note) is the panel's error alert. `confidence` is
  never asked or sent by the panel; since D-U9 (2026-09-24) the server accepts an Admiralty grade (`B2`: source
  reliability A–F × information credibility 1–6), and the *Annotations* list shows one in brackets when present. *Check coverage* reads
  `GET …/coverage` with NO query parameters, i.e. over the Investigation's own window (the service accepts
  `from`/`to`/`timezone`; no picker asks for them yet), and names every day with zero rows in the window's zone,
  or says every day has data; it always states that per-Collector coverage is not assessed
  (`collectors.assessed:false`). A result is shown only while its Investigation is the open one. ⚠ The route
  needs a `timeCol` in the header and a bounded window: *Start Investigation* sends `timeCol` (since 2026-10-03)
  from its optional *Time column* field, else the canvas time slider's column (`[timeCol]` input); *Start
  Investigation* also asks *Time column zone (optional)* (an IANA zone; blank = UTC, recorded explicitly) and sends
  `timeColZone` only together with a time column — the server refuses a lone zone, and a `TIMESTAMPTZ` column
  refuses one in its own 422, shown verbatim. Without either, or before a `window` op bounds the
  window, coverage still answers 422, shown verbatim.
* **Purpose, masking, four-eyes** (LA-19 operator decisions 2026-09-24, D-U5/D-U6/D-U7 — decision record in
  `docs/archived-documents/plans-archive/link-analysis-backlog-plan.md` §4). *Start Investigation* now REQUIRES a *Purpose / legal
  basis* field (the server answers 422 without one) and the template *Instantiate* form carries the same required
  field; the value is sealed in the header and shown in the Dossier, never enforced. Entity ids in every
  Investigation response may arrive MASKED as `masked:<16 hex>` per the Space's `maskingMode` (default `typed`:
  ids whose Entity Type is `masked` — seeded with such an `entityType` or one naming no type in force, on or matched
  by an Entity List of such a type, or every id when a bound Dataset column's classification maps to such a type;
  LA-17 step 5, 2026-09-26). That column classification comes from the Dataset registry AND, since 2026-10-04
  (`ASSURE-CLASSIFICATION-PROPAGATION-1`, operator), from the pipeline schema's `raw.fields[].classification` followed
  through its mapping by the platform's one lineage resolver (`DatasetProvider.schemaClassification`, the one
  `publish.postgres` and Risk Score evidence use): several classes on one computed column resolve strictest-wins
  (masked if any input's type is masked), and untraceable lineage of a masked class masks every id (fail closed).
  The panel shows the pseudonym as given and may send it back in an op's `ids` — the server resolves it.
  **Oversight surface** (`link-analysis-oversight.component`, 2026-10-03, `LA-SPA-OWED-SURFACES-1` slice): lists
  `GET …/log`'s `pending[]` — each request's requester, the thresholds it crossed (`sensitivity.exceeded` and the
  Space's `fourEyes*Above`) and its status; a holder of `canApproveLinkExpansions` gets **Approve / Deny** (optional
  deny reason ≤ 200) over `POST …/pending/{rid}/approve | deny`; a holder of `canRevealLinkEntities` gets per-entity
  **Reveal** over `POST …/reveal` for each `masked:` id of the Working Set, showing the value and that it was audited.
  Both gates are `LensService` identity capabilities (no Access-Catalog node carries them yet). ⚠ The server is the
  boundary: self-approval and a Subject-less approve are **403** even WITH the capability, 404/409/422 likewise —
  every refusal renders in place verbatim, and nothing is re-read until a decision succeeds (`(decided)` → the
  store re-opens the Investigation). A pending expand's own `POST …/ops` answer is not rendered as a step; the
  re-read log's `pending[]` is what shows it.
  ⚠ When a Space sets a four-eyes threshold, every stateless graph read — the projection that loads the canvas
  (`/inv/projection`, `/inv/projection/multi`), *expand node* (`/inv/projection/neighbors`) and the traversal
  (`/inv/traversal/recursive-paths`) — is **refused with 403** above it, because a stateless read has nothing that
  could be approved (2026-09-24). With the default `limit` of 2 000, a budget threshold below that refuses the
  canvas's initial load. The SPA has no dedicated state for that refusal; what it displays has
  not been checked.
* **Entity Lists section** (LA-17 step 6, SPA half, 2026-09-26; `link-analysis-entity-lists.component` +
  `link-analysis-entity-lists.dialogs`, hosted at the foot of the Investigation panel in BOTH its states). It reads
  `GET /entity-lists` and shows each list's title, purpose, Entity Type label (from the settings'
  `entityTypesInForce`; a type no longer in force says so), size and a *Retired* status badge — ⛔ **never its
  members**: they arrive masked per `maskingMode`, and member browsing is out of scope. *New list…* (`POST
  /entity-lists`: title, purpose, Entity Type, required reason; the id is left to the server to mint) keeps a
  409/422 in the dialog. *Add selection* sends the selected canvas entity's RAW spellings (`rawIdsOf`) to `…/members`
  `{add, reason}` and states `changed`; ⚠ the selection is `InvestigationSessionStore.selected`, which a canvas click
  sets only **while an Investigation is open** — there is no multi-node selection. ⚠ `masked:<hex>` pseudonyms are
  split out and never sent (`listableIds`): the members route would store the pseudonym as a member, since only an
  Investigation op's `ids` resolve one. *Retire* is a warn-coloured reason dialog (the dialog is the confirm). On the
  open Investigation, *Exclude by list* (reason ≤ 200, as `exclude`) and *Seed by list* append `{op:'excludeBy'|'seedBy',
  listId}` through `store.apply`, so undo, replay and the log refresh are the existing op path; the notice reads the
  step's nested `list.removed` / `list.seeded` and COUNTS `list.unmatched` (keys or a count — never displayed). List-op
  failures go through `entityListErrorMessage(…, true)` because a 404 there may be the list OR the Investigation.
  Writes are gated on `LensService.canManageIncidents()` (the rest of the panel still has no client gate — a 403 is
  surfaced); the section renders nothing when `SessionService.geoLinkEnabled` is off (a deep link reaches the page
  even though the nav hides it). A 503 is an explained info notice.
* **Identity resolution section** (LA-17 slice 2, SPA half, 2026-09-30; `link-analysis-identities.component`, below
  the Entity Lists section) over `/inv/entity-identities*`: assert that two typed identifiers are one entity
  (option-picker per Entity Type in force, cross-type allowed, required reason), *Find group* for one key, the Space's
  groups with members and the assertions that joined them, and *Retract* per assertion (the shared reason dialog,
  warn-coloured). ⚠ Keys go out NORMALISED through `identityKeyOf` (`typedEntityKey`) — the group read is exact.
  ⚠ The `key` query param uses `STRICT_QUERY_CODEC` (`inv.service.ts`): Angular's default codec sends `+` raw and the
  server decodes it as a space. Members are rendered verbatim — masked tokens stay tokens. Reads need
  `canManageIncidents` too (no call without it); 403 shows the server's message; 503 is an info alert.
* **Typed node ids (LA-17 D-M6, as built 2026-09-27).** A projection column whose Dataset registry
  `columns[].classification` is claimed (trimmed, case-insensitive) by an in-force Entity Type mints
  `<type>:<key>` — `typedEntityKey`, the type's normaliser (`msisdn` `0044 78` → `msisdn:+4478`); every other column
  keeps `entity:<key>` (or the free-text `entity:<scope>:<key>`, which a column type overrides). ⛔ The SPA never
  guesses which columns are typed: the SERVER resolves it (`InvRoutes.columnTypes`, the same classification lookup
  `EntityMasking` uses) and sends `columnTypes: {col: {id, normaliser}}` on `/inv/projection` and `…/neighbors`
  (always present, `{}` when untyped) and `entityType` / `sourceType` / `targetType` on `/inv/projection/multi`
  rows. The ONE mint is `endpointId(mapping, 'source'|'target', value)` in `entity-projection.ts`; the GraphSources
  stamp the typed mappings on the graph (`idMappings`) and the component remembers them on `lastRun` (the
  `entity-projection` query is replaced by its typed mappings via `resolveRunQuery`), so the Investigation binding
  (`InvestigationRef.sourceType/targetType`, persisted with the saved view), traversal, pattern and brush all mint
  the same ids. ⚠ A value that arrives WITHOUT its column — a Working Set seed, a path hop, a pattern match value, a
  Geo key — goes through `resolveEntityId` / `entityIdCandidates`: the candidate already drawn wins, else the start
  / seed as a source and later hops as targets; the brush tries every candidate. ⚠ Server ids are unaffected: the
  evaluator, Working Set and `excludeBy` compare RAW values / per-normaliser keys, never node ids — the only
  server-side node-id reader is the Case-from-Entities `EntityMember` key, widened to accept typed ids. ⚠ No
  back-compat (D-M6): a saved view, snapshot or Case member holding `entity:<value>` for a now-typed column no longer
  matches. ⚠ The Geo co-location graph (`coLocationGraph`) stays untyped — it is drawn in its own dialog and never
  compared with projection ids.
* ✅ **The feed is INGESTED, not merely authored** (verified end to end 2026-09-23): 1 283/1 283 rows land
  across three Hive partitions, `rejected_files=0`, `rejected_rows=0`, `cast_failures=0`, and every row
  reconciles to the source PSV by `REC_SEQ` with zero value mismatches. `IMEI` keeps its leading zeros as
  VARCHAR (no numeric coercion), and the 352 `DIRECTION='NA'` rows are exactly the 352 with a NULL
  counterparty. The planted stories survive the round trip — one IMEI on 5 IMSIs, one IMSI on 3 IMEIs, the
  45-row hub.
* 🔴 **The UTC declaration is CORRECT but currently UNFALSIFIABLE.** The host session zone is UTC+5:30 and
  no value shifted — `13:08:53` landed as `13:08:53`, and a host-zone shift would have produced `07:38:53`
  and a spurious `day=08-31` partition. ⛔ But `SourceZones.toNaiveUtc` compiles a declared zone to
  `timezone('UTC', timezone(Z, …))`, which **with `Z = 'UTC'` is an identity — and so is the
  no-declaration path**. So this run proves the VALUES are right while proving nothing about whether the
  declaration was applied or ignored; both emit byte-identical output. The declaration only becomes
  load-bearing against a later `TIMESTAMPTZ` cast (`SourceZones.toInstant`). **Pinning it needs a
  NON-UTC zone in a fixture** — a probe that would otherwise succeed.
* ⚠ **The `.psv.defect` fixture is INERT.** It is excluded by FILENAME (`glob:**/PMXDR_*.psv` never matches
  `.psv.defect`), not rejected: the collector never opens it, `quarantine/` and `errors/` stay empty and
  `rejected_files=0`. Its five planted defects — a non-numeric duration, an impossible date, a duplicate
  `REC_SEQ`, a truncated row, a `SUSPENSE` status — are therefore **never exercised**. Safe, but it tests
  nothing; renaming it to `.psv` is what would make it prove the quarantine path.
* **`postmed_xdr` is the call-records feed** (LA-16 / D-U2, extended rather than duplicated). It gained
  `IMEI` (deliberately unusable as a real identifier: no allocated TAC prefix, no valid Luhn digit), an
  explicit `DIRECTION` (without which A→B versus B→A is unrecoverable from the row), and a **UTC contract
  stated where a machine reads it** — `raw.fields[].timezone`, not prose — which compiles to a value
  identity and defends against DuckDB's session TimeZone being the host's. ⚠ DATA rows keep
  `OTHER_PARTY='N/A'` deliberately: a packet session's far end is the APN the row already carries, and
  inventing a peer would manufacture edges no real feed produces. 🔴 **It drew a fresh subscriber PER ROW,
  so its graph was ~1 200 disconnected edges** — useless for the analysis it was built to feed. A fixed
  population plus four planted stories (burner rotation, a one-way hub, a daily repeating pair, a SIM moved
  between handsets) makes it **873 edges over 174 nodes**.

## Graph Run (server-side graph analysis — as-built 2026-10-01)

A **Graph Run** is one execution of one of the 28 graph algorithms on the SERVER over an Investigation's Working Set
(LA separation D-4; `GLOSSARY` *Graph Run*). The browser keeps its own copy of the algorithms and runs them under the
per-Space node caps; a Graph Run is the way past those caps. Both implementations are held equal by the parity fixtures.

**Module layout.**

| Module | Holds |
|---|---|
| `inspecto-la-graph` (pure JDK) | the 28 ported algorithms (six `Graph*` classes) plus `RunControl` / `GraphAborted` (`checkpoint()`, progress, deadline). The old signatures delegate with a no-op `RunControl`, so the parity fixtures are untouched. 17 algorithms are instrumented; the 11 without a `RunControl` overload (paths, ego, neighborhood, components, degree, structure, forest) cannot be cancelled mid-run |
| `inspecto-la-core` (`com.gamma.la.core`) | `Algorithm` (the catalogue: id = the TS export name, `cost` SYNC/JOB as a hint, `inlineNodeCeiling`, typed `params`, `needsWeights` / `needsSource` / `needsTarget` / `needsNode`, `resultKind`, `resolve(params)`), the `GraphEngine` SPI (`engineId`, `supported`, `run`), `InMemoryGraphEngine` (exhaustive switch, no `default`), `GraphInput`, `WorkingSetGraphInput`, `GraphResult`, `InvalidGraphRequest`, `GraphBudget`, `GraphRunException`, `GraphRunService` |
| `inspecto-la-api` (`com.gamma.la.api`) | `GraphRunRoutes`, `GraphResultJson` (the one serializer: an exhaustive `switch` over the sealed `Payload`, so a 13th result shape does not compile) |

`la-core` depends on `la-graph` (allowlisted in `tools/check-module-deps.mjs` and both enforcer lists; `inspecto-la-graph.jar` is staged
in the bundle). The SPI does not mention memory: an index-backed engine is a later phase (D-3), not built.

**Routes** (all `/api/v1`). `GET /inv/graph/algorithms` (catalogue, `ceilings`, `defaults{maxNodes,maxEdges,timeoutMs,clamped}`,
`pool{threads,queue}`, `inlineWaitMs`; a read, no capability) · `POST /inv/graph/runs` (`{investigationId, at?, algorithm, params?,
weights: "count"|"none", kinds?, budget?}`; an unknown field is 422) answers `200` with the run view when it is terminal at submit or
finishes within the inline wait, else `202` + `Location` · `GET /inv/graph/runs[?investigationId=]` · `GET /inv/graph/runs/{id}` ·
`POST /inv/graph/runs/{id}/cancel` answers `202 {runId,status,cancelRequested}`. A Working Set at or under the algorithm's
`inlineNodeCeiling` is awaited `INLINE_WAIT_MS` = 3 000 ms; above it the request never waits. A queue-full submit is `503 STORE_BUSY`
(retryable), a missing write root `503 CONTROL_PLANE_READ_ONLY`. Reads and the list re-run `openForRead` on the run's Investigation every
time (lose the Investigation, lose the run: 404). The four route gates are done: `openapi-v1.json`, `CapabilityManifest`, the
`AbsentGeoLinkRoutes.SURFACE` mirror, and real-HTTP tests with an armed Authenticator.

**Capability and settings.** Starting a run needs **`canRunLinkGraphAnalysis`** (`Roles.CAN_RUN_LINK_GRAPH_ANALYSIS`, a literal in
`withCapability`; seeded to `operations` / `support` / `power` / `admin`, `super` by the all-capabilities convention; listed in
`okf/capabilities/security/security.md`). **Cancel carries no capability**: the service's starter-or-administrator check is the gate, and
the manifest declares a `self-service` exemption. Settings (`link-analysis.toon` block `graph_run`, wire `graphRun`, 1..10 000 000 each,
a bad value is 422): `max_nodes`, `max_edges`, `timeout_ms` (the DEFAULT budget), `threads`, `queue` (read when the Space's service is
built - first use, or the first use after it was closed idle), `max_result_items` (the response-size cap, read per request). `Limits.standard()` supplies the rest and is deliberately not a setting: default budget 50 000 nodes /
500 000 edges / 30 s, HARD ceilings 500 000 / 5 000 000 / 300 s, 2 threads, queue 16, run TTL 1 h, `maxRuns` 200, cache TTL 10 min,
32 cache entries.

**States and the budget.** `QUEUED → RUNNING → COMPLETED | CANCELLED | BUDGET_EXCEEDED | FAILED`; all four terminals are final. The
budget the service sees is always fully stated (the request, else the Space's `graph_run`, else the service default), clamped to the
ceilings, and echoed with `budgetClamped`. **"Never a silent cap":** `submit` validates params first (`InvalidGraphRequest` is the 422,
no run is created), checks size BEFORE work (terminal `BUDGET_EXCEEDED` with reason NODES or EDGES and the measured size; the engine is
never reached), then the cache, then the bounded pool. `timeoutMs` counts from execution start, not queueing. A run that returns AFTER
its deadline (an algorithm with no checkpoint) is also `BUDGET_EXCEEDED` and its answer is discarded; a cancel that loses the race to a
finished uncancellable algorithm is `CANCELLED` and discarded. **A `result` (and `masking`) exists only for `COMPLETED`**, and only
`COMPLETED` is cached. `FAILED` names the exception class, never its message (it could carry an entity id).

**Input rows.** `WorkingSetGraphInput.from(entityRows, linkRows, kinds)` takes the evaluator's neutral row maps. The row set is the
Working Set at `at` MINUS entities a `hide` op hid and the links touching them (counted in `input.hiddenEntities`); `resolve` identity
groups are NOT merged. Links with an endpoint that is no `entities` row are dropped and counted (`droppedDangling`), never silently.
Edge id = the wire `LinkIds.encode(source,target,kind)`, the `linkId` the links relation serves (NOT the SPA's canvas id). Weight =
`GraphPaths.edgeWeight(count, kind)`: a positive `count`, else 1; `weights: "none"` is unweighted. Parallel edges of one
`(source,target,kind)` fold into one Link, so an edge-counting figure (`degree`) legitimately differs from a browser graph that kept them.

**Masking after the cache.** Results are computed on RAW ids and cached once per Working Set, then masked on the way out
(`GraphResultJson.of` with `Ids.of(EntityMasking)`), the same discipline as `WorkingSetRoutes`. Node ids and labels go through the token
map; **edge ids are `LinkIds.decode` → mask both endpoints AND the kind → `encode`**, so a masked edge id equals the `linkId` the masked
Working Set serves (asserted over the wire). A masked pseudonym is accepted back as `from` / `to` / `node`. Communities are an ordered
`[{id, community}]` list (pair order is part of the answer).

**Cache key and scope.** Relation key + row-scope fingerprint (a tripwire) + algorithm + RESOLVED params + weights spec. The Working Set
is a pure function of the sealed log, which holds the Dataset reads of write time; Dataset gates and masking run outside the cache, so
two Subjects share entries safely today (`ControlApiWorkingSetSubjectScopeTest` is the proof and must keep passing). **The key must gain
the Subject's resolved row scope the day a Dataset is read live or gains row filtering.**

**Canonical order (`canonical-v1`).** Equal scores rank by UTF-16 code-unit order of the NORMALISED entity id, then the label (Java
`BY_SCORE_THEN_ID_THEN_LABEL`, TS `compareCanonicalV1`), replacing six `localeCompare` sites; it is host-independent. Pinned by a
mixed-case fixture row asserted in both languages.

**Audit.** `LINK_GRAPH_RUN_STARTED`, `_COMPLETED`, `_CANCELLED`, `_BUDGET_EXCEEDED`, `_FAILED` in `LinkEventTypes`. The terminal four
come from a `GraphRunService` terminal hook (exactly once per run, outside every lock, exceptions swallowed); STARTED is emitted from the
request thread, so a fast run's terminal event can precede STARTED by milliseconds (both carry `runId`). Attributes never include params;
the actor of a terminal event is the run's owner (the canceller is not recorded).

**Where it runs — the SPA** (`GraphRunsService`, `LinkAnalysisServerRunComponent`, `graph-run-apply.ts`). Browser-first under the cap,
server above it. A tool group shows **Run on server** INSTEAD of its local button when the Working Set's node count exceeds that
algorithm's browser cap (`suspicionNodeCap` for betweenness and suspicion; `analysisNodeCap` for closeness, eigenvector, Katz, HITS,
label propagation, Louvain, cliques, link prediction, max-flow); the server's `inlineNodeCeiling` is not used for the decision. The count
is the Working Set's (hidden entities left out), not the displayed query graph's; with no Investigation open the control is disabled with
the stated reason "open an Investigation". No `at` is sent (the canvas shows the committed head). `run()` starts, then polls at 1 s until
terminal. The id map (`buildServerIdMap`) matches server node ids against every raw spelling a canvas node folded and maps a `linkId` by
re-minting the canvas edge id; an id the canvas does not draw is dropped, never invented. BUDGET_EXCEEDED shows the server's code, budget
and measured size and exactly ONE next action ("run again with a budget of N" only when it fits under `ceilings`, else "filter the
Working Set or pick a cheaper algorithm"). On the SPA the capability is `LensService.canRunLinkGraphAnalysis`, access-catalog node
`linkgraph.run`.

**Selection algorithms (2026-10-01).** Shortest and weighted shortest path, all paths, cycles, cut points (articulation points and bridges, TWO server algorithms with one Run on server control each) and the maximum spanning forest have no browser cap of their own, so they get the same browser-first / server-above route. The ONE threshold is `selectionNodeCapValue()` in `graph-analysis.ts` (= the analysis cap, so one setting moves every decision); the toolbox lowers it to the server's per-algorithm `inlineNodeCeiling` (`serverCeilings` input, from `GET /inv/graph/algorithms`) and never raises it. The local button is replaced ONLY when a server run can really start (`serverFirst`: over the threshold, an Investigation open, the capability held); otherwise it stays, because these never refuse locally and a big query graph must not lose them. Server selections (`selection`, `selections`, `ids` - bridges name LINKS, the rest nodes) go through `applyServerResult` into the SAME `applyPath` / `applyAllPaths` / `applyCycles` / `applyCutPoints` / `applySpanningForest` the local runs call. Cut points are two server runs whose halves combine ONLY when both were computed against the same id map (= the same Working Set); a half from an earlier map is dropped, and "No cut points" is claimed only when both halves ran on this map (otherwise the message names the half that was checked).

**Not drawn on the canvas.** `countDropped` / `droppedNotice` (`graph-run-apply.ts`) count the distinct node and link ids of a COMPLETED result that the id map cannot place, and the toolbox states `N of M result nodes and K of L result links are not drawn on the canvas right now`; a later browser run clears it. There is no reveal action: collapse and the kind/time filters act on the QUERY graph, not on an open Investigation's canvas, so what is missing is not collapsed.

**The Investigation canvas draws the Working Set whole, up to ONE render ceiling.** `workingSetToGraph` (`investigation-state.ts`) folds the Working Set's links through `projectTriples` with the node cap switched off (its entities are already bounded server-side; the cap protects the QUERY-graph projection) — the 2026-10-01 preview drew `1200 nodes · 551 links` for 1,200 entities / 1,501 links because that cap silently dropped every link needing a node past the 500th. Rendering still has a real limit, so `WORKING_SET_LINK_RENDER_CEILING` (5,000 links, a constant — no setting) bounds it: above it the heaviest links are drawn (count descending, then edge id), `ProjectedGraph.omittedLinks` carries how many were left out, and the render footer and a notice state `Showing 5,000 of 20,000 links (render limit)`. Hidden entities' links are removed first, so the ceiling counts what would otherwise be drawn. The kind filter, the timeline and super-node grouping act on the query graph only, so while the canvas shows the Working Set they are **disabled with the reason**; there is no collapse entry either, because a node click on that canvas picks the Investigation's entity.

**Parity guarantee.** The six `graph-*-parity.fixture.json` files are asserted in TS and in Java (engine-level `GraphEngineParityTest`
too), plus a route-level test that feeds `graph-algorithms-parity.fixture.json` through a real Dataset → Investigation → Working Set →
`POST /inv/graph/runs` (components, k-core, triangles, shortest paths, neighborhood; `degree` deliberately not asserted, see Input rows).
`GraphComplexityEquivalenceTest` and `graph-complexity-equivalence.spec.ts` keep the pre-fix `kCore`, `weightedShortestPath` and
`linkPrediction` as private references (`LA-GRAPH-QUADRATIC-1`, closed 2026-10-01). `GraphAlgorithmsBench` (`-Dinspecto.bench=true`,
`-Dinspecto.bench.only=`) is the timing harness behind `inlineNodeCeiling` (largest benched node count with median ≤ 1 000 ms; 500 for
betweenness and suspicion).

**Deliberate decisions and gotchas.**

* A well-formed node id that names no node is NOT a 422; it yields the algorithm's empty answer (the browser and the fixtures do). Only an
  absent, blank or mistyped node id, an unknown name, a bad type or an out-of-range value is refused, before any work. Count params accept 0.
* Hidden entities are excluded and `resolve` groups are not merged (see Input rows).
* `linkPrediction` stays a JOB: its output is every pair that shares a neighbour, which a hub makes quadratic by definition.
  `jaccardSimilarity` cost depends on the chosen node's degree, so a node-count ceiling alone does not bound it.
* **Live progress.** A RUNNING run's `consumed.elapsedMs` is the monotonic time since it began executing (0 while QUEUED; final at the end),
  and `progress` is `{work, fraction, known}`. `fraction` is the algorithm's own 0..1 (`RunControl.progress(done, total)`, called next to
  `checkpoint()` once per source or sweep, never in an inner loop) and is meaningful only when `known` is true; `known: false` means this
  algorithm reports none and `fraction` 0 is "unknown", not "just started". A COMPLETED run that reports one shows 1. Reporting: betweenness,
  closeness and link prediction (per source), PageRank, eigenvector, Katz, HITS and label propagation (per iteration, of the requested
  maximum - a run that converges early finishes below 1 until it completes). **Louvain** (passes run to convergence) and the 11 algorithms
  without a `RunControl` overload report nothing. `known` is additive: a client that reads only `fraction` keeps working.
  The SPA prefers the server's `progress.fraction` and `consumed.elapsedMs` whenever they are > 0 and falls back to "N steps done" and a browser-measured wait otherwise.
* Cancel on someone else's run is **404**, the answer of an unknown run (same status and body shape, no existence oracle), exactly as a
  READ of it is; cancel also re-runs the Investigation read gate (`InvestigationRoutes.openForRead`), so a caller who lost access to the
  Investigation gets that 404 too. The starter and an administrator (`Roles.CAN_ADMINISTER`, or no Subject at all) cancel as before; a
  finished run is still **409**. (Operator-delegated decision 2026-10-02, fail-closed; the service still throws FORBIDDEN as a backstop.)
* A server result for a node or link the canvas does not draw is dropped by the id map, and the toolbox says how many (see above).
* With an Investigation open, the toolbox's browser-side runs and the From / To pickers use the Working Set canvas (`canvasData()`), the same graph server runs map onto; with none open they use the query graph (operator decision 2026-10-02).
* **Service lifetime.** One `GraphRunService` per Space write root, held by `GraphRunServices` (la-api): created lazily, closed by
  `ApiContext.onClose(Runnable)` (`ControlApi.close()` runs its hooks first), **or earlier** - closed and forgotten when unused for 1 h
  (`IDLE_TTL_MS`, the finished-run retention, so closing never shortens what an analyst can still read) with no run in flight AND no run finished within that hour (`GraphRunService.lastActivityMillis()` counts a finish as use, so a result that completed after the last poll is not discarded by another Space's sweep), or at once
  when its Space's directory is gone. The sweep runs on access (touching another Space); one lock covers sweep, lookup, create and close,
  so nothing is created after close and a racing creation cannot leak. Worker threads additionally time out after 30 s idle
  (`allowCoreThreadTimeOut`), so even the only Space's idle pool holds no thread; the next run starts one. `threads`/`queue` apply when a
  service is built (a rebuilt one re-reads them).
* **`maxRuns` is soft by design.** It bounds FINISHED runs only (retention never drops a live one), but live runs are hard-bounded by the
  pool (`threads + queue`, a further submit is `503 STORE_BUSY`), so the run table holds at most `maxRuns + threads + queue` entries.
* **Result size - never a silent cap.** Each list of a result (`scores`, `hubs`, `authorities`, `groups`, `ids`, `communities`, `links`,
  suspicion `scores`, `selections`, sub-graph `nodes` and `edges`, and a selection's `nodeIds`/`edgeIds`) is cut to `graph_run.max_result_items`
  (wire `graphRun.maxResultItems`, default 10 000, hard ceiling 1 000 000 - clamped and echoed by `GET /inv/graph/algorithms`
  `resultItems{limit,default,ceiling,clamped}`) **on the way out, like masking**; the cache keeps the full result, so raising the setting needs
  no re-run. The cut keeps the FIRST entries of the canonical-v1 order (the top of a ranking). It is always said: `result.truncated` is true
  when anything was cut and `result.lists.<name> = {total, returned, limit, truncated}` exists for every top-level list (a nested list - one
  group, one path - gets an entry such as `groups[0]` only when cut). The limit is per list, not per payload. Both keys are additive.
  A sub-graph returns only edges whose BOTH nodes are returned (no dangling edge); when node cutting leaves edges out, `lists.edges` is
  `{total: every edge, returned: what is here, truncated: true}`, so `returned` can be below `limit`. **The SPA says it:** a COMPLETED
  result with `truncated` shows a warning beside the not-drawn notice (`truncationNotice` in `graph-run-apply.ts`, toolbox
  `serverTruncated`), built from `lists`, e.g. "Showing the first 10,000 of 25,311 scores (the server cap is 10,000; raise
  graph_run.max_result_items in Settings)"; a nested cut reads "the first 2 of 5 members of group 1".
* **Masking oracle closed (2026-10-02).** While masking hides an entity, a RAW id of it sent as `from` / `to` / `node` is treated as a node
  that does not exist (`GraphRunRoutes.ABSENT_NODE`, decided at the route): the run completes with the same empty answer an unknown id
  gives, so the response cannot say whether the raw id is in the Working Set. A pseudonym still resolves; an id masking leaves in the
  clear (typed masking) and every id with masking off are unchanged.

**Closed 2026-10-02:** `LA-COVERAGE-FLOOR-HOME-1` — the Link Analysis routes are tested in `inspecto-geo-link`, so geo-link's coverage profile writes `jacoco-aggregate/jacoco.csv` and `tools/check-coverage.mjs` keeps the best row per class (CI green on `950455149`; the floor stays 78.0%). `LA-INDEX-FINGERPRINT-COST-1` — the input-fingerprint file cap (`InputFingerprint.MAX_FILES`) is 10,000, not 100,000; above it the existing `too-many-files:` sentinel applies (the index claims nothing about staleness). `LA-INDEX-READER-POOL-1` — `IndexReader.borrow` reuses idle sealed connections per version directory (max 4 idle) and borrowing a newer sibling version closes the older one's handles; `IndexedRecursivePaths` uses it (nothing calls `evictAll()` at service close yet). `LA-INDEX-STAGE-RACE-1` — `IndexStore.stage()` retries on `FileAlreadyExistsException` (the directory creation is the atomic claim), covered by a 16-thread test. `LA-INDEX-BUILDER-TEST-TIMING-1` — the cancel test now polls for bytes in the stage instead of sleeping 1500 ms (the 30M-row size and its ~2 GB spill are unchanged). `LA-GRAPH-RUN-CANCEL-STARTER-COVERAGE-1` — a Case-team member who can read the Investigation but did not start the run now gets 404 on cancel (a mutant removing the route-level starter clause turns this into a 403 that would confirm the run exists; the new test kills it).

**Still open — filed on the board (`docs/BACKLOG.md` §3.12).** `LA-A11Y-AUDIT-1`. Also: `LA-APP-REAL-SIGNIN-1` (la-app OIDC never run against a real IAM). **Accessibility, second pass 2026-10-03** (`LA-A11Y-LIVE-REVERIFY-1` closed): axe 4.12.1 re-run in a real browser in both schemes — the loading-bar name, the Cliques result-button height (min 24 px) and the light scheme hold. Fixed in the shell and the Link Analysis panels: `<footer>`, `role="navigation"` on the nav host and `role="status"` on the loading bar (axe `region`), `ariaModal: true` on every Material dialog via `MAT_DIALOG_DEFAULT_OPTIONS` (⚠ that token REPLACES `new MatDialogConfig()`, so spread it or the dialog loses `role`), and `text-secondary` instead of `opacity-60` on the Query / Toolbox / Filter predicate headings (light contrast). The app has no scheme toggle, so light was driven through `GammaConfigService`; finish `document.getAnimations()` before an axe run after a live switch or stalled transitions read as a contrast failure. **Seventh pass 2026-10-05 (real browser, Demo User auth):** the docks STACK below `md` (`max-md:flex-col` on the workspace, `max-md:!w-full` on both asides, `max-md:hidden` on the drag handles) because a row of fixed-px docks left the canvas 0 px wide at 320 px (400% zoom); the Toolbox header row wraps; G6's stacked canvas layers are one tab stop (first 0, rest -1). Axe was clean on every driven state; screen-reader reading, forced-colors paint, Dossier / identity dialogs remain unverified (see the `LA-A11Y-AUDIT-1` row). **Third pass 2026-10-03 (specs only, not driven live):** the shared graph host `inspecto-graph-view` is `role="figure"` with an `aria-label` giving the node and link counts (`graphSummaryLabel`), and the positive `tabindex="1"` G6 puts on its canvas layers is reset to `0` once `render()` resolves (`demotePositiveTabindex`) — the canvas stays focusable for G6's keyboard behaviours but no longer jumps the tab order. The remaining option-D phases are in
[`la-separation-feasibility-plan.md`](../../../archived-documents/plans-archive/la-separation-feasibility-plan.md) §7.8.

* **Graph text alternative and toolbox tablist (2026-10-03, fourth accessibility pass).** The canvas toolbar's *Show as list* toggle (`aria-pressed`) swaps the `<canvas>` for `inspecto-link-analysis-node-list` over the same `canvasData()` (masked ids as drawn; the Working Set over an open Investigation): a `role="grid"` of nodes (roving tabindex, Arrow / Home / End; Enter or Space goes through `onNodeClick`, as a canvas click does) with the selected node's links under it, paged at 500 nodes and 500 links; the canvas is `inert` meanwhile and its figure name points to the list. The Analysis / View / Investigation switch is a `role="tablist"` with `tabpanel` panes (roving tabindex, Arrow / Home / End). Proved with axe 4.12.1 in a real browser (0 violations, G6 `tabindex` 0).

## Index (D-3, as built 2026-10-02)

The edge/node index of a Dataset ([`la-separation-d3-design.md`](../../../archived-documents/plans-archive/la-separation-d3-design.md) §5.2, §5.3): Parquet under `<Space write root>/la-index`, built by `IndexBuildService` (`inspecto-la-storage`) and exposed by `IndexRoutes` (`inspecto-la-api`). `IndexBuildService.close()` records every cancel BEFORE it interrupts the worker pool, so a build whose builder throws on interrupt (an interrupted DuckDB call) still ends `CANCELLED`, never `FAILED`.

* **Routes.** `POST /inv/index/builds` (body `{dataset, sourceCol, targetCol, kindCol?, timeCol?, timeColZone?, weightCol?, attrCols?}`, 202 + Location, 409 for a second live build of the same Dataset + mapping, 422 for a bad column or an estimate over the budget), `GET /inv/index` (viewable Datasets' current versions with rows / bytes / builtAt and `stale` + `reason` + `reasons` codes + `removedInput` + `fingerprint: known|unknown` + `inputFiles`), `GET /inv/index/builds/{id}`, `POST /inv/index/builds/{id}/cancel`. Every route runs the base-Dataset view gate first: a Dataset the caller may not view, and every build over it, is the same 404 as an absent one (design Decision 2: valid only while a Dataset has no per-Subject row filter).
* **Capability and settings.** Starting needs `canBuildLinkIndex` (seeded like `canRunLinkGraphAnalysis`); cancel is the starter or an administrator. `link-analysis.toon` `index {enabled, max_disk_bytes, keep_versions, threads, queue}`; a build estimated above `max_disk_bytes` (rows x 34 bytes x 2; 0 = no limit) is refused up front with the estimate; the duplicate check runs first, the count is bounded (10 s statement timeout, at most 2 at once; a timeout refuses 422 and a busy cap 503, never skipping the budget), and a 409 duplicate names the build id only to its starter. Audit: `LINK_INDEX_BUILD_STARTED | _COMPLETED | _CANCELLED | _FAILED`.
* **Pinned versions (D7-2).** `IndexStore.pins()` (`IndexPins`, a `pins.json` in the index directory) lets a Draft pin the version it read: `IndexStore.gc` never deletes a version with an unexpired pin (30-day TTL, warning inside the last 7 days; an unreadable pin file makes gc delete nothing) and the reader pool spares a pinned version's idle readers. See `docs/archived-documents/plans-archive/la-separation-d7-design.md` section 5.
* **What is served from the index (step 5, as built 2026-10-02).** `POST /inv/traversal/recursive-paths`, and ONLY it, answers from the index when the Space setting `index.enabled` is true (default false: nothing changes) AND a published index matches the request's source / target column AND covers its weight column, temporal column and every `filter` field (kind, attribute, source, target columns; NOT the weight column) AND `maxDepth` <= 2 AND no level has more than 20 distinct frontier keys. Otherwise the flat Dataset answers exactly as before. Every answer carries `source`: `{kind:'index', version, stale, staleReason?, fingerprint: 'known'|'unknown'}` or `{kind:'dataset', reason, details?}` (reasons: `index_disabled`, `no_index`, `mapping_not_indexed`, `column_not_indexed`, `time_zone_not_servable`, `filter_not_indexed`, `index_stale_refused`, `depth_over_index_cap`, `frontier_over_index_cap`, `index_read_failed`). A frontier found over the cap mid-walk discards the partial index answer and re-answers whole from the flat Dataset. Staleness is the SAME computation as `GET /inv/index` (`IndexStaleness`, grounded in the Dataset's input files): a REMOVED or replaced input file, a changed relation SQL, a different bucket function or unapplied delta files is refused (`index_stale_refused`, with `details`) because removed rows could be exposed; files only ADDED is served with `stale: true` and a `staleReason` (the index misses the new rows); a DuckDB version difference is served with `stale: true`; a Dataset whose files cannot be listed (view-backed, too many files) is served with `fingerprint: 'unknown'`. The current fingerprint is cached per (Dataset, mapping) for 30 s and dropped when a build completes. The view gate runs first, so a shared-away Dataset is the same 404 whether or not an index exists. The audit event `link.traversed` gains `source`, `indexVersion` / `indexStale` / `fingerprint` / `indexStaleReasons` or `sourceReason`. Design 5.4 has the as-built notes and timings.
* **Neighbours and Investigation `expand` (step 6, as built 2026-10-03).** Same switch, same view gate, same staleness gate (`IndexedRead.select`, shared with `recursive-paths`). `POST /inv/projection/neighbors` folds the value's rows on the `out` copy (as source) and the `in` copy (as target), counting a self-loop once, in the flat order `cnt DESC, source, target`, cut at `limit` (`truncated` as before); it needs the `linkKindCol`, every `attrCols` entry and every `filter` field to be indexed columns, and adds `source` (`{kind:'index',...}` or `{kind:'dataset', reason}`) to the body. An Investigation `expand` is answered from the index only for a SIMPLE rung (no `window`, `minDistinctDays`, candidate degree bound or merged traversal; at most 20 distinct frontier entities): it reproduces the CTE's fold per (source, target, kind), `excluded`, `linkKinds`, direction (incl. `reciprocal`), `minEvents`, per-anchor `maxFanOut` rank with `fanOutCapped`, and the budget cut, so the sealed `fingerprint` (`sha256(canonical(rows))`) is identical on both paths. When the index answered, the sealed read carries `read.index = {version, stale, fingerprint}` BESIDE the fingerprint, never inside it; a non-simple rung (`rung_not_indexable`) or any failure keeps the flat CTE. `replay` is unchanged; `replay` with `reread` adds `indexVersionSealed` / `indexVersionNow` to a drift row (only when either is set) and `diverged` stays about the fingerprint alone. Audit: `link.expanded` and `link.investigation.stepped` gain `source` / `indexVersion` / `indexStale` (stepped only when the index answered).
* **Graph Run from the index (step 7, as built 2026-10-03).** `POST /inv/graph/runs` takes `input: "workingSet"` (default, byte-identical to before) or `"index"`. With `index` the body adds `dataset`, `sourceCol`, `targetCol`, `linkKindCol?` and, for `degreeCentrality`, `seeds` (1..20); only `neighborhood` (hops <= 2), `egoNetwork` and a SEEDS-ONLY `degreeCentrality` run (engine id `index`, `GET /inv/graph/algorithms` lists `engines` per algorithm). `GraphInput` is sealed (`Materialised` | `IndexRef`); `RoutingGraphEngine` routes by input type, never as a fallback. Every case the index cannot serve exactly is a stated 422 (`index_disabled`, `no_index`, unfit mapping / column, `index_stale_refused`, non-native algorithm, hops > 2, seeds > 20, `at`, an Investigation that hides entities); a walk past 20 looked-up keys per level (the final induced-edge pass included) ends `BUDGET_EXCEEDED` / `INDEX_CAP` naming the cap. The pre-work size check uses the seeds' degrees; the answer is measured afterwards against the same budget. The response adds `source` and `input.kind: "index"` (estimated sizes); the cache key carries the index version; audit `link.graph.run.*` gains `source` / `indexVersion`. Result order is canonical-v1 (nodes and edges by id). Detail and deferrals: `archived-documents/plans-archive/la-separation-d3-design.md` section 5.6.
* **SPA: Run on index (as built 2026-10-03).** `LinkAnalysisServerRunComponent` offers **Run on index** only when the catalogue `engines` of its algorithm include `index` AND `GET /inv/index` (`GraphRunsService.loadIndexes`, signal `indexes`) says `enabled` with at least one index; otherwise the button is absent, never disabled-with-a-fallback. It sends `input: "index"` with the index's OWN `dataset` / `mapping.sourceCol` / `targetCol` / `kindCol` (as `linkKindCol`) and `seeds` for `degreeCentrality` only; several indexes give an Index picker. Client-side fences state themselves before sending (hops > 2, seeds 0 or > 20, no Investigation, no capability). The Explain tab of the toolbox hosts it for `neighborhood` (`indexOnly`: no Working Set button). An index answer is NOT applied to the canvas (no apply path for GRAPH / seeds scores): the control states the version (`source.version`), a stale chip when `source.stale` (and beside the button when the listed index is stale), and a node / link or top-score summary. A 422 is put in words by `indexRefusalMessage` (reason codes, hops, seeds, `at`, hidden entities) followed by the server's own sentence, and the run is never repeated on the Working Set. Specs: `link-analysis-server-run.index.spec.ts`.
* **SPA index surfaces (`LA-INDEX-SPA-SURFACES-1`, as built 2026-10-03).** The Explain tab of the toolbox hosts three index-only `LinkAnalysisServerRunComponent`s: `neighborhood` (hops), `egoNetwork` (the same Node, no hops) and a seeds-only `degreeCentrality` over a **Node to score** picker (Add node, removable chips, 1..20, the server ids from `serverIds`; none picked states why). A new **Edge index** tool (`LinkAnalysisIndexBuildComponent`, gated by the new lens `canBuildLinkIndex`, an identity capability with no Access-Catalog node) lists the index with its version, deltas, stale chip and the `plan` advice (recommended mode, counts, reasons, samples), a **Build mode** radio fieldset (`full` | `append` | `compact`, preselected from the advice, `full` when `none`) and **Build index**, which `POST`s `/inv/index/builds` with the index's OWN mapping plus `mode`, polls `GET /inv/index/builds/{id}` (`GraphRunsService.startBuild` / `watchBuild`) and refreshes `GET /inv/index` on COMPLETED. The advice never blocks: a mode the server cannot apply is its 409 in words (`indexBuildErrorMessage`), never retried as another mode. The Investigation panel states, in one non-blocking `role="status"` sentence (`expandFallbackNote`), why the last `expand` was answered by the flat Dataset: the sealed read carries `read.fallback = {reason, details?}` (the same closed reason vocabulary as `source.reason`; only when the flat Dataset answered an expand that could have tried the index, BESIDE the fingerprint and never in the sealed state hash, so old logs replay identically), and the audit event `link.investigation.stepped` gains `fallbackReason`. **Cancel and a new mapping (2026-10-04, `LA-INDEX-SPA-SURFACES-1` closed):** while a build is QUEUED or RUNNING a
  *Cancel build* button posts `POST /inv/index/builds/{id}/cancel` (`GraphRunsService.cancelBuild`); the polling already
  running reads the terminal `CANCELLED` (an interrupted builder never reads `FAILED`), the button disables and the
  line says *Cancelling…* meanwhile, and a 403 / 404 / 409 is put in words (`indexBuildCancelMessage`). *Index a
  different mapping instead* swaps the Build mode radios for a Dataset + columns form (`index-mapping.ts`: `dataset`,
  `sourceCol`, `targetCol` required; `kindCol`, `timeCol`, `timeColZone`, `weightCol`, `attrCols` optional) that
  starts a build with `mode: 'full'` — append and compact only exist against a live version of the SAME mapping — and
  a zone never travels without its time column; with index support on but NO index listed the form is the only
  option (the old "No index to build" text now means index support is off). The server stays the judge of the columns. Specs: `link-analysis-index-surfaces.spec.ts`. Not driven live: the index routes need a Professional-edition server with `index.enabled`.
* **Incremental append, compaction and the plan (step 8, as built 2026-10-03).** `POST /inv/index/builds` takes `mode`: `full` (default), `append` or `compact`. `append` indexes ONLY the input files added since the live version, as a delta sorted within itself, and publishes the NEXT immutable version (the parent's files hard-linked in, the delta in the same bucket directories, manifest `builder: append`, `parent`, `deltas[]`); reads union main + deltas through the reader's own glob, so every read path (fold, edges, degree, traversal, neighbours, expand, `SqlGraphEngine`) answers exactly what a full rebuild answers, and each answer carries the version that produced it. It is sound only for pure additions: a removed, superseded or rewritten file, a changed relation SQL, a different bucket function or DuckDB version, a Dataset that is not a plain local store read (virtual, view-backed, shared), no new file, or 8 deltas already (compact first) is a 409 naming why, and a full build is the way. `compact` merges the deltas into one sorted main by reading the INDEX (not the Dataset), restoring one file per bucket and carrying the coverage record over unchanged. `GET /inv/index` items gain `deltas` and `plan {recommended: none|append|full|compact, appendable, reasons, added, removed, changed, samples}`: advice only - nothing appends, compacts or switches a version unless asked, and an index with added files is still served stale-flagged meanwhile. The per-append `nodes` rows are per-file partial folds (nothing reads them; compaction recomputes). See design §5.7.
* **What it does NOT do yet.** `index.enabled` defaults to false. Staleness is grounded in the Dataset's input files (`DatasetProvider.inputFingerprint`, design 5.3a): an added file reads `input_files_changed` with `removedInput: false`, a deleted or touched one `removedInput: true`; a Dataset with nothing to list (a view), more than 10,000 files, a listing past its 2 s budget or one reached after `GET /inv/index`'s 5 s request budget reports `fingerprint: unknown` with `fingerprintReason` (`no-files` / `too-many-files` / `timeout` / `budget`) and is never claimed current.

## External references and the Dossier bundle (D-6, as built 2026-10-03)

Design and decisions D6-1…D6-7: [`la-separation-d6-design.md`](../../../archived-documents/plans-archive/la-separation-d6-design.md). Integration by reference, never by trust.

* **External references.** `GET` / `POST /inv/investigations/{id}/references`, body `{system, type, id, url?, label?}`. Stored append-only in `references.jsonl` beside the op log (`SnapshotStore.appendReference`), OUTSIDE the write-once header and the Dossier manifest, so adding one never invalidates an issued Dossier. No edit or delete; a duplicate `(system, type, id)` and the 201st reference are 409. `POST` is `canManageIncidents` and owner-only; `GET` is the read gate (owner or linked-Case member). 🔴 A reference is **never trusted**: `trusted:false` on every record, never dereferenced, never read by the analysis, and it grants no access (a reference naming a Case shares nothing; only `PUT …/case` does). `url` is absolute `http` / `https`, with a host and no credentials, else 422. Audited `LINK_INVESTIGATION_REFERENCE_ADDED`.
* **Investigation members** (D7-1, 2026-10-03; D19, which amends D-E7; `InvestigationMembers` in `inspecto-la-core`,
  `InvestigationMemberStore` + `InvestigationMemberRoutes` in `inspecto-la-api`). Roles `lead · analyst · reviewer`,
  an append-only `members.jsonl` beside `header.json` (`{seq, ts, actor, subject, role, op: grant|revoke}`); the
  current role is the FOLD (last op per Subject wins; a revoke removes it). The creator is the implicit first lead, so an
  Investigation with NO `members.jsonl` is owner-only exactly as before (nothing is migrated). The last lead cannot be
  revoked or demoted (422). `GET …/members` (any member), `POST …/members {subject, role}` and
  `POST …/members/revoke {subject}` (lead only; `canManageIncidents` plus the lead check; a non-lead member 403, a
  non-member the 404 an unknown id gets); audited `LINK_INV_MEMBER_GRANTED` / `LINK_INV_MEMBER_REVOKED`.
  **The gate** is the one `InvestigationRoutes.open*`: every member READS (log, Working Set, Dossier and bundle,
  measures, coverage, references, Case link, replay, Graph Runs, the list), only a lead WRITES the main log (ops, undo,
  reorder, template, alert rules, Case link, references, reveal) and a member who may not gets 403; a Case member
  (LA-24) still reads only, never replays; four-eyes approval (D-U7) is a lead or reviewer once the Investigation has a
  members record (any holder of the capability before). R3 and the Enterprise PDP still judge every member and only
  narrow: the PDP resource now carries `members` (`subject → role`), a DENY hides the Investigation even from a lead
  and its 403s are withheld behind it. Analysts write their own Draft (next bullet), never the main log.
* **Drafts (D-7, D7-3 built 2026-10-03; D16-D21; `DraftStore` + `InvestigationMembers.Role` in `inspecto-la-core`, `DraftRoutes` in
  `inspecto-la-api`).** A Draft is a member's working copy of ONE Investigation, forked from the main log at `baseStep k`; ONE
  live Draft per member (D17: a second fork is 409 naming the first). Storage: `investigations/<id>/drafts/<draftId>/{header.json,
  log.jsonl, sets/<step>.json}` (+ `discarded.json` once discarded); the writer is the main log's own (`SnapshotStore.appendStepAt`),
  the Draft's steps CONTINUE the numbering (k+1 ...). **Base-state rule:** the Draft's state = the evaluator over (main log entries
  1..k + the Draft's own entries) as one list - nothing is copied, so a later main step never changes it (`behind` / `stale` report
  how far main moved) and `baseLogHash` (sha256 of the main log's first k lines) fails a Draft CLOSED (409) if the main prefix was
  rewritten. Routes `.../drafts` (POST fork, GET list), `.../drafts/{draftId}` (+ `/log`, `/working-set` over the same cached relation
  function and masking-after-cache, `/replay` = full re-fold equivalence incl. the persisted `sets/` hashes), POST `/ops` and `/undo`
  (the SAME validation, sealing and rules as the main log; a Draft undoes only its OWN ops; a four-eyes-sensitive expand is refused
  422 - the pending queue is the main log's) and POST `/discard`. Gate: the D7-1 member gate, then the Draft rule - a lead or reviewer
  reads another's Draft (D7-Q8), an analyst gets the 404 of absence; only the ACTOR writes (a lead or reviewer gets 403); the actor or
  a lead discards (a reviewer 403). **Pins:** fork pins the CURRENT version of every index serving the bound columns
  (`IndexStore.pins().pin(version, draftId)`); none when there is no index (D7-Q2 - the Draft still works, reads are sealed at use);
  discard unpins; fork is assembled in a scratch dir and renamed in (a failed rename leaves no directory and no pin). The rename (fork and both rebase renames, `DraftStore.moveRetrying`) retries a Windows
  `AccessDeniedException` up to 20 times with 5-80 ms backoff (an antivirus / indexer handle on the fresh `header.json` blocks a
  directory rename; the D7-7 bench saw 4 of 50 forks hit it); still denied, the fork answers **503 `STORE_BUSY`** and leaves nothing. **Discard** keeps
  `header.json` + `discarded.json` (who, when, head, log hash) and DELETES the log and sets (sealed rows may hold personal data); the
  per-step audit events keep the ops. No dossier, bundle or evidence route exists for a Draft (D20). Audit: `LINK_DRAFT_FORKED` /
  `_OP_APPENDED` / `_UNDONE` / `_DISCARDED` (ids, actor, steps - never rows).
  **Rebase and promote (D7-5, built 2026-10-03):** GET `.../drafts/{id}/conflicts` (report, writes nothing), POST
  `/rebase` (`{confirm?, expectHead?}`, actor only) and POST `/promote` (`{expectHead?}`, the actor or a lead; a reviewer 403). Rebase
  replays the Draft's EFFECTIVE ops (undone ops and undo entries are compacted away, steps renumbered M+1..) over the current main
  head through the append validation, re-seals every expand against freshly pinned index versions and swaps the new Draft in
  (`DraftStore.replaceRebased`, two renames, old restored if the second fails). Each rebased state is serialised once - its canonical
  bytes give both its `workingSetHash` and its set file - and the fail-closed fold runs before the main lock (`DraftRebase.verify`;
  the lock re-checks main and the Draft log byte-identical): 800 steps 36.7 s -> 8.4 s, still O(state) per step since nothing sealed
  survives the renumbering (`LA-DRAFT-REBASE-COST-1`, pinned by `DraftRebaseCostTest` against the old algorithm); a linear rebase was examined and declined 2026-10-04 - the cost is the whole-state sealed hash, not the set-file format, and a chained hash would change every sealed hash and the promote byte pin; design doc section 6). Each op is classed `no-op` / `changed` (re-sealed
  fingerprint differs, both counts) / `superseded` (a no-op AND main holds the same op) / `blocked` (refused on the new base); the
  last two are never carried and must be named in `confirm` (D7-Q7), else 409. A Draft's expand reads ITS pinned version
  (`IndexStore.version(n)`, `IndexedRead.select(..., pinned)`; a version no longer published falls back to the flat Dataset); a pin
  past 30 days refuses ops and promote with 409 "must rebase", and a rebase (even at the current head) re-pins. Promote needs base ==
  main head and an undo-free Draft (the state records admission steps, so promoting a compacted log would not reproduce it), re-verifies
  both under the main then Draft lock, appends each step with `draft{id, actor, baseStep, step, promotedBy}` provenance, rolls the main
  log back if any append or the `promoted.json` marker fails, then closes the Draft. Each promoted step reuses the Draft's own sealed
  `workingSetHash` and hard-links its set file (byte copy where links are unsupported) - the step numbers coincide and provenance is
  not folded, so it is the same output. Nothing unverified reaches main: a set file is reused only when its head carries that hash
  and step AND its working-set bytes SHA-256 to that hash (one read + hash per file); a missing, foreign or tampered one is re-sealed
  from the fold, and the fold's final hash must still equal the Draft's. That removed the per-step re-serialisation of the whole state
  (800 steps 34.9 s before, 3.3 s after warm, 8.3 s cold; linear in the bytes the sets hold; `LA-DRAFT-PROMOTE-COST-1`, pinned by `DraftPromoteCostTest` against the old
  algorithm) (log and sets deleted, header + marker kept, pins
  released). A promote carrying an expand that is sensitive under the thresholds NOW in force is held as a pending request (202) on the
  four-eyes queue and decided by the existing approve / deny routes. Audit `LINK_DRAFT_REBASED` / `_PROMOTED`. **Checkpointed append
  (D7-4):** `DraftCheckpoints` keeps the last evaluated State per Draft (valid while `log.jsonl` keeps size + mtime) so an op is one
  `copy()` + `apply`, not a re-fold of main prefix + own log; an undo still folds. The verified `baseLogHash` verdict is cached per
  main log file (size + mtime), so an unchanged main log is not re-read or re-hashed; `/replay` clears it and re-verifies. Caches are
  in memory (a restart = one cold fold). **Admission, hibernate, expiry (D7-6, built 2026-10-03):** at most 50 open Drafts per Space (default)
  (51st fork 409, hibernated ones count); heavy Draft jobs (rebase / conflict replay, a cold Working Set relation build, an `expand`
  op) run under `min(4, cores/3)` permits and a full cap answers 429 at once, never queues. A Draft idle 1 h (default) hibernates
  (`hibernated.json`; checkpoint + cached relation released; log, sets, pins kept; the next authorised access wakes it with one cold
  fold), idle 30 d (default) it expires (a discard with `expired:true`, pins released, `LINK_DRAFT_EXPIRED`, `expiryWarning` from 7 days out).
  **The three numbers are per-Space `drafts` keys (`LA-DRAFT-PROMOTE-COST-1`, 2026-10-04):** `link-analysis.toon` `drafts: {max_open
  (1..1000, 50), hibernate_after_minutes (1..10080, 60), expire_after_days (1..3650, 30)}`, wire `drafts{maxOpen,hibernateAfterMinutes,
  expireAfterDays}` plus the echoed `draftsInForce` on `GET|PUT /settings/link-analysis`. They replaced the static fields on
  `DraftLifecycle`; `DraftAdmission.maintain` / `maintainSpace` and `DraftRoutes` (fork cap, expired-409 text, `expiresAt`) read
  `LinkAnalysisSettings.forRoot(inv.writeRoot()).effectiveDrafts()` per call. PUT refuses (422, never clamps) an out-of-range or
  unknown key and an expiry not longer than the hibernation; a hand-edited unordered pair reads as all defaults. The Settings page
  has a **Drafts** group of three number fields (blank = inherit; placeholder and hint show the value in force from `draftsInForce`),
  range-checked and with the expiry>hibernation rule shown client-side (a blank counts as its in-force value); blank keys are omitted
  from the PUT (no stated key = no `drafts` block) and a server 422 shows inline in the server's words.
  Idle time = `DraftLifecycle.touch` (memory + `accessed.json` every 5 min); the sweep is lazy on list / open / fork (no scheduler).
  `drafts/index.json` is a rebuildable listing index (header `size:mtime`) so a listing reads no header; `DraftStore.recover` restores
  or deletes the `.old-<id>-*` a crashed rebase leaves, only after verifying the header. No per-Draft `draft.duckdb` exists (nothing
  needs it yet). See `docs/archived-documents/plans-archive/la-separation-d7-design.md` sections 4 and 12.
* **Dossier bundle.** `GET /inv/investigations/{id}/dossier/bundle?at=&snapshots=` returns `inspecto-dossier-bundle/1`: the masked Dossier, the masked references, `custody {manifestRoot, referencesCount, referencesHash}` and a SHA-256 `seal` over the canonical JSON of everything but `seal` and `generatedAt`. `POST …/dossier/bundle/verify` (body a bundle, or `{bundle}`) answers `{verified, sealIntact, rootMatches, referencesIntact, referencesAddedSince, problems, custody}`: the seal, the embedded manifest root, that the bundle's references are still the first N of the store, then the SAME manifest comparison `/dossier/verify` runs (`DossierRoutes.verifyManifest`). A failure is a result, not an error. Audited `LINK_DOSSIER_EXPORTED` / `LINK_DOSSIER_BUNDLE_VERIFIED`.
* **Masking, R3, four-eyes.** Masked per `maskingMode` as it leaves (references too); the manifest and `referencesHash` hash the RAW store, so the root is identical masked or not and a masked bundle verifies; the seal covers what shipped. Read gate = the Dossier's (owner or Case member, R3 on the Dataset and every snapshot, the Enterprise PDP). A pending sensitive expand is not in the sealed log, so it is not in the bundle.
* **Gotchas.** The offline-checkable part is the seal and the manifest's own root; whether the store still agrees needs the verify route. `DossierRoutes` was split (`parseAt`, `parseSnapshotIds`, `maskedDossier`, `verifyManifest`) so the Dossier, its verify and the bundle share one build. The new `POST …/references` is a `CapabilityManifest` entry, the bundle verify a read-shaped exemption, all four routes are in `AbsentGeoLinkRoutes` and `openapi-v1.json`.
* **Not built.** The embeddable view (URL + scoped token): `LA-EMBED-VIEW-1`. Trust between installations (I1): no live call exists to need it.
* **Scheduled build (`la.index.build`, LA-DAILY-INGEST-1 T5, 2026-10-06).** A Job Type, triggered by `job.dataset.produced`, refreshes the Index of one configured Dataset mapping through the `link-index` Platform Service (`LinkIndexAccess` in the engine; the `inspecto-geo-link` bridge `ScheduledLinkIndexBuilder` binds it to `ScheduledIndexBuild` in `inspecto-la-api`; no new route). It runs as the delegated principal `index-build:<job>` holding only `canBuildLinkIndex`; its authority is the Job's recorded `owner` user id, re-decided every run like standing detection (owner must still view the Dataset by id or `user` share; a role share is refused). `attr_cols` must be a comma-separated string (a TOON array fails closed as `MAPPING_INVALID`). It runs only the mode `plan` recommends (`append`; `compact` then `append` at the 8-delta cap; `full` only with `allow_full`), through the same `IndexBuildService` as the route, and a server refusal is one recorded `REFUSED` (the Run fails with the code), never a retry. Audit `LINK_INDEX_SCHEDULED_RUN`. Details, config shape and residuals: [`link-analysis-roadmap.md`](../../../superpower/link-analysis-roadmap.md) "T5 as built". The `owner` is server-stamped on save from the saving Subject's id unless the saver holds `canConfigureAccess` (`JobAuthority.stamp`, at every write door including Space Template seeding and a type-less `template:` instance; the run reads `owner` only from the saved config, never trigger args / signal bind; no Subject: typed value kept).

## Index design record (D-3: scope, decisions, measurements, seams)

The as-built behaviour is in *Index (D-3, as built)* above; this section keeps what the retired design
([archived](../../../archived-documents/plans-archive/la-separation-d3-design.md), provenance only) decided and measured, so the
reasons survive. Steps 1-8 are built; step 1 was a test-only spike (`InvIndexSpikeBench`, a gated harness, not a test).

**Scope: Path A (operator 2026-10-02), set by the step 1 spike.** Measured at 10^8 edges on one laptop (i7-9850H, 32 GB, DuckDB 1.5.2,
warm cache, heavy-tailed corpus, mean degree 5): one hop through the sealed path **43 ms** (flat unsorted file 1,031 ms), full build
**368 s** (6.1 min, peak working set 17.9 GB), but the Java-driven 5-level walk **3.0 s / 4.3 s p50 against a 1.5 s criterion: FAIL**. Only
an equality on ONE key reaches the scan as a zone-map filter; a multi-key `IN`, an `OR`, a join on `VALUES` and `unnest` read most row groups
(about 72 % of the rows at 50 keys), and one equality statement per key joined by `UNION ALL` is linear in the key count (0.7 s at 20
keys, 2.2 s at 50). So the index serves single-entity lookups, a one-hop read both ways, **bounded neighbourhoods of depth <= 2 and
<= 20 keys per level** (`IndexedTraversal.MAX_DEPTH` / `FRONTIER_CAP`, the same 20 in `SqlGraphEngine`), and a seeds-only
`degreeCentrality`. It does NOT serve deep or wide multi-hop (`allPaths`, `shortestPath` / `descendants` over a large frontier, every
global or iterative algorithm, the bidirectional BFS): those stay on the flat recursive-CTE path and the in-memory engine. Depth 3 is
derived (about 1.4 s, no margin), never measured, so it is out; a wider cap needs a new measurement first.
⚠ **Where the index loses.** Through the real route every request pays a fresh sealed connection (about 100-200 ms): at 10^6 edges the
index is NOT faster (one hop 209-466 ms against 293-373 ms flat; a 20-key depth-2 walk loses about 3x), from 10^7 it wins 2-5x for a
hop and 1.5-3x at depth 2; at 10^8 it was not re-run through the route. That is why `index.enabled` defaults to false.

**Layout and seams.** `<Space write root>/la-index/<dataset>/<mappingHash>/CURRENT` (one-line atomic pointer, temp file `force`d then
`ATOMIC_MOVE`) and immutable `v<n>/` directories holding `manifest.json` and Parquet `out/` (edges by source), `in/` (the mirror by
target) and `nodes/` (folded counts), each `bucket=<0..N-1>/`, rows sorted by (entity, ts), row group 100,000, zstd. Ids are the RAW
column values cast to VARCHAR (masking stays at the route, after the caches, so a mask-mode change needs no rebuild); a NULL endpoint is
dropped and counted (`droppedNull`); `ts` is a NAIVE UTC timestamp (a TIMESTAMPTZ column refuses a zone, the effective zone is in the
manifest, never the host's). `N = clamp(pow2(ceil(edges / 4e6)), 16, 1024)`, fixed per version. Size is 34 bytes per edge for all three
tables (3.43 GB at 10^8, 1.28x the flat file), so the disk estimate is `rows x 34 x 2` with no separate node term. Modules and classes:

| Where | What |
|---|---|
| `inspecto-la-storage` (host-free; DuckDB JDBC is its only non-`inspecto-*` need) | `BucketFunction`, `IndexMapping`, `IndexManifest`, `IndexStore`, `IndexBuilder`, `IndexBuildService`, `IndexPlan`, `IndexReader`, `IndexedTraversal`, `SqlGraphEngine`, `RoutingGraphEngine` |
| `inspecto-la-core` | the ports: `DatasetProvider` (`relationSql`, `inputFingerprint`, `relationSqlOverFiles`), `InputFingerprint`, sealed `GraphInput` (`Materialised` / `IndexRef`), `IndexCapExceeded` |
| `inspecto-la-api` | `IndexRoutes`, `IndexedRead` (the ONE gate: setting, choice of index, staleness, closed `Reason` enum), `IndexedRecursivePaths`, `IndexedNeighbors`, `IndexedExpand`, `IndexStaleness`, `InputFingerprintCache`, `IndexBuildServices` |

`ALLOWED` in `tools/check-module-deps.mjs` and each pom's enforcer list agree (la-storage -> la-api and la-core -> la-storage stay red in
the module-deps test); `tools/bundle-modules.mjs` stages la-storage `from: 'professional'`. `IndexReader` opens ONE `SqlSandbox` per
request, binds views over the pinned version's Parquet, and seals it (`sealAllowing` = that version directory only,
`enable_external_access=false`, configuration locked); `borrow` reuses idle connections per version directory (max 4). The builder runs
its OWN non-sandboxed DuckDB (the sandbox forbids `COPY ... TO`), takes only a trusted relation SQL from the port AFTER the caller's view
gate and never sees a Subject.

**Signed decisions (1-8, operator 2026-10-02, all recommendations; 3 carries one amendment).**
1. One index per (Dataset, edge mapping), under the Space write root. A cross-Dataset index and a per-Investigation index are different
   products (cross-Dataset hops, D-7 Drafts).
2. **Row scope (R3) is Dataset-level**: every index read first runs the base-Dataset view gate (same 404 as an absent Dataset), so a
   revoked share bites on the next read; the manifest's `relationSqlHash` marks any Dataset-definition change stale. Invariant, stated:
   valid ONLY while a Dataset has no per-Subject row filter; the day one lands the design becomes a per-scope index.
3. Entity-hash buckets, sort (entity, ts). **Amendment (assistant, from step 1 data): the bucket function is `md5_number_lower(entity) % N`,
   not DuckDB `hash()`.** `hash()` is deterministic across connections and processes but not reproducible in Java and not proven stable
   across DuckDB versions; MD5 matches Java `MessageDigest` (digest bytes 8-15 little-endian, `Long.remainderUnsigned`) on 1,004 of 1,004
   ids in the spike and on 4,000+ in `BucketFunctionTest`, builds about 16 % slower, looks up at the same speed. The engine computes the bucket
   in Java with no round trip and a DuckDB upgrade cannot make an index silently stale through it; the manifest records `bucketFn` and
   `duckdb.version`, a mismatch is stale, and a bucket-function change can only be deliberate. MD5 byte order inside DuckDB across versions
   is still not proven.
4. The builder is an LA-owned `IndexBuildService` with its own routes (la-* cannot import the engine's `JobService`); no `IndexBuildPort`.
   A `JobTypeProvider` adapter for scheduling is "later" and not built.
5. Full build first, incremental append and compaction after, only on explicit request - staleness is reported, never silently acted on.
6. Serve a stale index flagged, pin the version at Graph Run submit, record `read.index` BESIDE the sealed fingerprint; refuse only when
   removed rows could be exposed. `datasetVersion` stays `null` and `read.index` is provenance, NOT a replay pin (promoting it needs a decision).
7. One module, `inspecto-la-storage`.
8. Selection is automatic for the read routes when `index.enabled` is on (default OFF), always echoing `source`; a Graph Run needs an
   explicit `input: "index"` and never falls back to the Working Set.

**Gotchas worth keeping.**
* **Fingerprint cost.** Listing a Dataset's input files costs about 0.19 ms per file (1 file 3 ms, 1,000 files 182 ms, 10,000 files 1.9 s;
  Windows laptop, warm), and `GET /inv/index` pays one listing per indexed Dataset per call. The cap `InputFingerprint.MAX_FILES` is
  10,000 (above it the `too-many-files:` sentinel claims nothing); `InputFingerprintCache` holds a result 30 s per (Dataset, mapping), at
  most 256 entries, dropped when a build for it completes. The manifest records at most `IndexManifest.MAX_INPUT_FILES` = 5,000 files; over
  that only the hash is kept, so such a Dataset can only be rebuilt in full.
* **Consignment SQL hash.** With a Consignment registry a `physicalRef` relation SQL pins the file list, so an added file would change
  `relationSqlHash` and read as a definition change, which the traversal gate must refuse. `IndexBuilder.relationSqlHash` therefore
  blanks the pinned list inside `read_parquet(` / `read_csv(` before hashing: the hash is the DEFINITION, files are tracked only by the
  input fingerprint. Manifests written before that fix read once as `relation_sql_changed`.
* **Two copies of the data.** The index is a second copy, so the disk estimate is a budget (`index.max_disk_bytes`) and a rebuild peaks at
  about 2x (old and new version coexist until GC; `keep_versions` default 2, a stage touched in the last 5 minutes is never collected).
  Extrapolating to 10^9 edges (about 34 GB per index) is NOT measured (a 10^9 build would take about 2.5 h); D-3 claims <= 10^8.
* A non-UTC `timeColZone` makes a temporal constraint non-servable (`time_zone_not_servable`): the flat path compares raw wall-clock
  values and DST changes durations, the index stores UTC instants. A filter on the weight column is not servable (stored as DOUBLE).
* `neighbors` and `expand` fold EVERY row of a value (a count is exact only over all of them), so a node of millions of rows is bounded only
  by the 5 s statement timeout, after which `index_read_failed` and the flat path answers. When the per-level `LIMIT` yield fence fires the
  rows differ from the flat path's (its `LIMIT` has no `ORDER BY`); only the flag is comparable.
* An `egoNetwork` of a node with more than 19 distinct neighbours ends `BUDGET_EXCEEDED` / `INDEX_CAP` although the expansion was one
  lookup: the final induced-edge pass looks up one key per kept node. Exactness over reach; a hub is a Working Set job.
* `nodes` after an append is per-file partial folds (a node can appear once per file group, degree sums stay exact); nothing reads it and
  compaction recomputes it, so `tables.nodes.rows` of an appended version counts rows, not distinct nodes.
* Pinned by mutation tests (each red): the Java bucket shifted by one, partial walk served instead of fallback, view gate removed, frontier
  cap check disabled, version missing from the cache key, `index` silently rerouted to `workingSet`, the "removed file forces a full build"
  line in `IndexPlan`, the reader glob narrowed to the main files (ignores deltas).

**Not built (D-3).** The Investigation response naming WHY it fell back (only `neighbors` shows
`rung_not_indexable` and the rest); `window` / `at`, hidden entities and `merged` rungs on the index; a scheduler or auto-compact; the
`JobTypeProvider` adapter; `IndexSubgraph` and deep multi-hop (Path A); the 10^9 run (`K = 8` deltas and latency at 10^8 are
measured, see *Index scale measurements*); cross-Dataset graphs. Filed: `LA-INDEX-SPA-SURFACES-1`, `LA-INDEX-SCALE-MEASURE-1`.

## Index scale measurements (LA-INDEX-SCALE-MEASURE-1, 2026-10-05)

Harness: `IndexScaleBench` in `inspecto-la-storage` tests (`-Dinspecto.bench.dir=<dir> -Dinspecto.bench.edges=N`; knobs `deltaEdges`,
`maxDeltas`, `skipDeltas`, `skipCompact`, `buildMemory`, `samples`; never in the default suite). Method: D-S5 skew corpus (source ~
nodes x u^3, target ~ nodes x u^2, nodes = edges / 5) written as real parquet files, a real `IndexBuilder` FULL build, then 1-8 appended
delta files of 100,000 edges each (`IndexBuilder.Mode.APPEND`), then `COMPACT`; reads through `IndexReader.edges` and
`IndexedTraversal.walk` (undirected, depth 2) over 60 random keys per series after one warm-up. Windows laptop, 12 hardware threads, warm.

| Edges | FULL build | Index size | 1 key p50 / p95, 0 deltas | 1 key p50, 8 deltas | 1 key p50, compacted | depth-2 walk p50 / p95 |
|---|---|---|---|---|---|---|
| 10^6 | 4 s | 0.02 GB | 26.6 / 43.5 ms | 35.3 ms | 18.3 ms | 229 / 538 ms |
| 10^7 | 22 s | 0.23 GB | 20.2 / 27.3 ms | 29.9 ms | 15.7 ms | 32 / 489 ms |
| 10^8 | 447 s (32 buckets, 8 GB cap) | 2.23 GB | 20.9 / 31.0 ms | 37.8 ms | not run | 21.8 / 32.0 ms |

* **`K = 8` holds.** The cost of a delta is linear and small: about +0.2 ms per delta on a single key at 10^8 (20.9, 24.4, 27.8, 33.9, 37.8 ms
  at 0, 2, 4, 6, 8); a hub key 36.2 to 56.2 ms; a 20-key lookup 408 to 766 ms. No cliff before the cap; compaction restores the
  single-file figure.
* **Latency is flat in N** for a key lookup (about 20 ms from 10^6 to 10^8). A walk costs its frontier: a 20-key frontier is about 0.4 s
  at every scale (about 20 ms per key, sequential), so the 20-key cap, not N, bounds a walk. The 10^6 / 10^7 depth-2 p95 is the 20-key
  frontier case; at 10^8 the random keys of this corpus are cold (degree about 1) so the walk p95 is low; the 5 of 60 walks refused as
  `FrontierOverCap` are the hubs.
* **Caveats.** The corpus has no weight or extra columns, so 22 bytes per edge is below the documented 34 and does not retire that
  estimate. Delta size was fixed at 100,000 edges. COMPACT was not run at 10^8. **10^9 was not run**: build time grows about 20x per
  10x (4 s, 22 s, 447 s), so about 2.5 h, over the 20-minute limit per measurement; the 34 GB, 2x rebuild peak and the 10^9
  `DraftConcurrencyBench` re-run remain extrapolations (backlog row `LA-INDEX-SCALE-MEASURE-1`).
* **Defect found by the 10^8 build.** The pinned-file-list regex in `IndexBuilder.relationSqlHash` recursed per character and threw
  `StackOverflowError` (surfaced as `index build failed: null`, after the whole build ran) for a list of about 20 long paths; fixed with
  possessive quantifiers, pinned by `IndexBuilderTest.theRelationHashSurvivesALongPinnedFileList`.

## SPA separation record (D-5: library, second shell, packaging)

As-built facts are in the two blockquotes at the top of this concept; the retired design and its edge-classification companion are
[archived](../../../archived-documents/plans-archive/la-separation-d5-design.md) (provenance). Steps 1-7 are built.

**Signed decisions (1-7, operator 2026-10-02, all recommendations).**
1. Shared API clients STAY in core behind the `@inspecto/core/*` alias (`src/app/inspecto`); only the LA-only clients (`inv.service`,
   `graph-runs.service`, `link-analysis-settings.service`, `geo.service`, `geo-settings.service`, `notes.service`, `inv-identity.service`;
   1,678 lines) moved. Moving the whole `api/` was rejected: its closure leaves the directory (94 files), and the closure through the `api`
   barrel lies (40 LA imports of the barrel close over all 72 clients) - measure the injected set.
2. The canvas (`graph-view.component`), `catalog-graph`, `graph-types`, `graph-source`, `entity-key`, `graph-export` and `unique-name`
   stay in core (14 host files use the canvas, 14 dialogs `unique-name`); the library holds the algorithms, brush, snapshot, filter,
   history, branching engine and `geo`. Two core edges the closure could not see (`MultiNodeMapping` / `MultiEdgeMapping`, `ObjectNote`) moved to core.
3. In-workspace libraries wired by `tsconfig` `paths`, NO ng-packagr (offline-safe, one `npm ci`); boundaries are ESLint rules (core <- library <- shell; core bans `@inspecto/link-analysis`).
4. Names `@inspecto/core`, `@inspecto/link-analysis`, apps `gamma` (rename deferred) and `la-app`.
5. The OIDC client code moved to core (`inspecto/auth`: `SessionService`, guard, interceptor, `pkce`, sign-in and callback pages) - one PKCE
   implementation, one security review. `SessionService` needed no split.
6. The LA product flavor is a UI flavor, not an edition: `package.ps1 -Ui gamma|la-app` (default `gamma`). A fifth edition is the promotion path
   when a customer needs a bundle without the other Professional modules; no `-Pedition-la` exists.
7. One bundle ships exactly one SPA (`ui/`); `package.ps1` copies `inspecto-ui/dist/<Ui>` BY NAME and empties the bundle directory first
   (the old "first `index.html` under dist" became ambiguous with two applications).

**Gotchas worth keeping.**
* **The barrel/MapLibre trap and the bundle-shape guard.** `public-api.ts` is deliberately tiny (the `la-host` seam plus
  `registerLinkAnalysisViz` / `registerGeoMapViz`). A first version also re-exported the widget components and `MapViewComponent`; a static
  import from `app.config` then dragged MapLibre (1.2 MB) and the LA widgets into `main` (1.42 to 1.88 MB), because esbuild keeps a source
  module's side effects. Lazy consumers import deep paths. `inspecto-ui/tools/check-bundle-shape.mjs` + `bundle-budget.json` (a `ui.yml`
  step, plus `angular.json` `initial` / `bundle main` budgets) pin chunk placement and size.
* **Lint scope followed the move or it would have silently stopped.** The restricted-import and dynamic-import rules were keyed to
  `studio/{link-analysis,geo-map}/**`; they now name `projects/link-analysis/**` and `@inspecto/core/*`, and also forbid `SessionService` at its
  new path `inspecto/auth/session.service`. Mutations (re-add a forbidden import) go red; `tools/la-dynamic-import-lint.test.mjs` and
  `la-static-import-lint.test.mjs` pin the regexes. The design-token guard `ROOTS` and the Prettier / typecheck globs had to gain `projects`;
  `vitest` discovery needs `../projects/**/*.spec.ts` (a bare `projects/**` matched nothing), and `tailwind.config.js` `content` had to scan
  `./projects/**` or the utility classes used only by the library were purged from the stylesheet.
* **Hand-kept mirrors named SPA paths** (9 Java parity tests, `check-vocabulary.mjs` path keys and `SOURCE_GLOBS`, doc citations): the six
  `graph-*-parity` fixtures and `branching-parity.fixture.json` MOVED with their specs; `entity-normaliser-parity.fixture.json` STAYED in core
  because `entity-key.spec` reads it.
* **`la-app` token provision** (`LA_APP_TOKEN_PROVISION`, pinned by spec; a token not provided throws by design, only `LA_FEATURES` has a
  safe default):

  | Token | la-app | Behaviour |
  |---|---|---|
  | `LA_DATASETS` | real | Component registry `dataset` kind, no Studio code |
  | `LA_CASES` | real | core `ObjectsService`; `available` follows the `ops` flag |
  | `LA_TAGS`, `LA_TRANSFER`, `LA_AI_ASSIST` | real | core dialogs / components |
  | `LA_FEATURES` | real | live `SessionService` signals from `/bootstrap` |
  | `LA_WIDGETS`, `LA_CATALOG`, `LA_PIPELINE_GRAPH` | stub, `available: false` | one `console.info` per page load; the affordance is hidden (`GraphSourcesService.sources` filters, `byId` still resolves) |
  | `LA_DASHBOARD_HEADER` | stub | empty component; the Link view widget is never embedded |

  A STRING asset entry in `angular.json` resolves against the PROJECT root and silently copied nothing for la-app: use `{glob, input, output}`.
  The Entity Lists page must provide the empty `InvestigationSessionStore` (NG0201 found only by driving the route).
* **Packaging proof.** `-Ui la-app` and `-Ui gamma`, each with `-Edition Professional`, exited 0 with the boot smoke; the la-app zip's
  index page is titled `Inspecto Link Analysis`, the gamma zip's UI folder is file-for-file `dist/gamma/browser`. `check-sbom-modules`,
  `check-launchers`, `check-bundle-platform` and `bundle-modules.mjs` needed no change (none names the UI folder). The IAM client id for
  la-app (`AUTH_OIDC_CLIENT_ID`; the launchers default to `inspecto-ui`) is a deployment decision, written in `docs/EDITIONS.md`.

**la-app signs in over Demo User auth (driven live 2026-10-03, no IAM).** `-Dauth.mode=demo` (module `inspecto-demo-auth`,
[local-testing-without-iam.md](../../backend/editions/local-testing-without-iam.md)) is a token relay behind `/auth/exchange`, NOT an OIDC provider:
there is no discovery, authorize or JWKS endpoint, so the IdP redirect, `state`/PKCE round trip and ID-token validation are NOT exercised.
Recipe (test-only; no client id is involved): run a Demo-build backend (`inspecto-demo` bundle jars, `serve-demo.bat` flags, a Space with
`config/demo-users.toon`, `-Dcontrol.port=<free>`), `npx ng build la-app --configuration development`, copy `dist/la-app/browser/*` into the
backend's `-Dui.dir`, open `/`. Proven: unauthenticated `/` and `/link-analysis` bounce to `/sign-in` (guard); the Demo User picker sends
`POST /auth/exchange` 200 and lands on the landing page; Space-scoped calls are 200 with the Bearer (401 without or with a forged one); a full
reload on `/link-analysis` resumes via `POST /auth/refresh` 200; *Sign out* posts `/auth/logout` 200, clears `inspecto.session.resumable`, and a
reload goes to `/sign-in` with no refresh attempt. Not driven live: token expiry (15 min access token) and the interceptor's 401-then-refresh retry
(covered by `auth.interceptor.spec.ts`). No defects found; no automated la-app-over-demo-auth test exists (the repo has no browser e2e harness; the
backend half is `DemoAuthHttpTest`).

**Real WSO2 run (2026-10-05, backend half).** Local WSO2 IS, Professional + `-Ui la-app` bundle: authorize redirect (state, S256), real login/consent, `/auth/exchange` (httpOnly refresh cookie, JWT access token, `groups` -> capabilities), gating, refresh, replay refusal, logout and RP-initiated logout all behaved; driven by a script, not the SPA - the built-in browser cannot pass WSO2's self-signed certificate page. Gotchas: `groups` must be an access-token attribute; the post-logout URL must be registered as a callback URL; `serve.bat` does not wire `tokenEndpoint`/`rolesClaim` from env. Details and the open SPA click-through: `LA-APP-REAL-SIGNIN-1`.

**Not built (D-5).** Real OIDC sign-in for la-app against a real IAM (the Demo User path above and the moved specs cover the SPA half); the
"licence text" item (nothing in the bundle names the UI flavor to attach it to); `/bootstrap` was proven on a scratch Standard backend,
not by calling the packaged zip's server; a fifth `LA` edition; module federation (signed no); a second `-Dui.dir`; renaming `gamma`; a mobile or SSR
build. Filed: `LA-APP-REAL-SIGNIN-1`.

## Integration design record (D-6: decisions and the deferral)

The behaviour is in *External references and the Dossier bundle* above; the retired design is
[archived](../../../archived-documents/plans-archive/la-separation-d6-design.md). Integration is BY REFERENCE: no installation trusts another, and
a live call (and so the trust relationship I1) is needed only by a feature that makes one - none of the three D-6 items does.

* **D6-1** storage: an append-only `references.jsonl` under `audit/` (a reserved import path, so an imported bundle cannot plant one), not a
  header field (the header is write-once; a reference is a relationship, not evidence), hence outside the Dossier manifest; at most 200 per
  Investigation (the check-and-append is one locked step), a fork or an instantiated template starts with none.
* **D6-2** never trusted: caller text, never dereferenced, never merged into the Working Set or the log, grants nothing, `trusted:false`;
  `url` must be absolute `http` / `https` with a host and no credentials (`javascript:` / `file:` / `ftp:` would be an XSS or local-read vector).
* **D6-3** gates: `POST` is `canManageIncidents` (Case work, no new capability) and owner-only; `GET` is the read gate.
* **D6-4 / D6-5** the bundle is sealed (SHA-256 over canonical JSON, keys sorted, `generatedAt` outside) and verifiable (custody: manifest
  root and a references prefix hash; later additions are reported, a rewritten one fails; re-sealing an edited bundle is caught by custody);
  masked per `maskingMode` as it leaves, while the manifest and `referencesHash` hash the RAW store so the root is identical masked or not.
  Four-eyes holds by construction (a pending expand is not in the sealed log). The export persists nothing, so it takes no capability.
  Accepted trade-off: a read-only Case member can export what they can already read.
* **D6-6 deferred, on purpose (`LA-EMBED-VIEW-1`).** An embeddable view needs a NEW principal type in `inspecto-auth-spi` (a token scoped to one
  Investigation: read-only, expiring, revocable, with a token store, mint and revoke routes, an expiry sweep and an exemption in
  `ComponentAccess` / the PDP / R3 for a caller who is not a user), `frame-ancestors` / CSP and cross-origin cookie decisions, and a read-only SPA
  shell; a leaked URL would hand masked evidence to a holder with no audit identity. These are decisions for the operator, not ones to
  take silently; the bundle covers the sharing need that exists today. Build only when a consumer needs a live, revocable view.
* **D6-7** no trust between installations (I1) is unbuilt: a reference is a pointer, a bundle a file, verification local.
* Proof: `ControlApiDossierBundleTest` (real HTTP, armed Authenticator), key negatives mutation-checked (seal always true, references
  unmasked, references prefix unchecked, POST not owner-only, URL scheme and credential checks removed, `trusted` flipped). The route table:
  `POST …/references` is a `CapabilityManifest` entry, the bundle verify a read-shaped exemption, all four routes in `AbsentGeoLinkRoutes`
  and `openapi-v1.json`.

**Still open - filed on the board (`docs/BACKLOG.md` §3.12), separation phases.** `LA-INDEX-SPA-SURFACES-1` · `LA-INDEX-SCALE-MEASURE-1` ·
`LA-EMBED-VIEW-1` · `LA-APP-REAL-SIGNIN-1`. The remaining option-D phase (D-7, Drafts) is active in
[`la-separation-d7-design.md`](../../../archived-documents/plans-archive/la-separation-d7-design.md).

## Platform-layer record (D-1: module carve-out, as built 2026-10-01)

Option D's phase D-1 gave Link Analysis a thin shared platform so it no longer needs `inspecto-processor`, `inspecto-etl`
or `inspecto-engine`. Built in seven steps, verified at `ab36c6e9c` (full reactor, 41 modules, 7319 tests green). The
design is archived ([`la-separation-d1-design.md`](../../../archived-documents/plans-archive/la-separation-d1-design.md);
its execution order in [`la-separation-execution-plan.md`](../../../archived-documents/plans-archive/la-separation-execution-plan.md)).

| Module | Holds |
|---|---|
| `inspecto-audit-spi` | `Event` `EventLog` `EventType` (+ `MetricRegistry`, which `EventLog` calls back); cuts the old `inspecto-event` → `inspecto-etl` edge (`ParquetEventStore` stays in `inspecto-event`) |
| `inspecto-auth-spi` | the LOWER half of the control contract: `RequestAttrs` `WriteRootProvider` `ApiException` `ErrorCodes` `SpiSlot` `WriteGates` `Subject` `Roles` `ComponentAccess` `RowScope` `AccessDecider(s)` `AccessPolicies` `AuditTrail` `AccessGrants` `Authenticator(s)` `CapabilityManifest` `GeoCountryResolver(s)`, plus the OIDC helpers of `inspecto-security` |
| `inspecto-http-spi` | the UPPER half, depends on auth-spi: `ApiContext` (extends `WriteRootProvider`) `RouteModule` `Handler` `Envelope` `Idempotency` |
| `inspecto-la-graph` | the six `Graph*` algorithm classes — JDK only |
| `inspecto-la-core` | model, evaluator, pattern engine, Snapshot store, `LinkEventTypes`, and the ports `DatasetProvider` / `CasePort` |
| `inspecto-la-api` | the LA routes over http-spi/auth-spi, plus `GraphDossierBuilder` `ValueMeasures` `EntityMasking` (they call route statics) |
| `inspecto-geo-link` | the **bridge** (artifactId unchanged): `EngineDatasetProvider`, `HostCasePort`, and the Alert-Rule-bound `InvestigationMeasureRoutes` / `WorkingSetMeasures` |

**Decisions (operator, 2026-10-01).** (1) `ApiContext` is the SPI; host services (`service()`, `spaces()`, `sseStreams()`)
moved to `HostContext extends ApiContext` in the core, reached by `HostContext.of(ctx)`; LA-owned ports only for LA's real
needs. (2) Package names kept (`com.gamma.control`, `com.gamma.event`) — a pure move. (3) The 27 `LINK_*` event types moved to
`com.gamma.la.core.LinkEventTypes`, values unchanged (they are persisted in the audit trail). (4) Entity List routes went to
core (SEP-08) before LA left; host-free statics live in `inspecto-entity-store` as `EntityListFacts`. (5) The dependency rule —
`la-*` may not reach `inspecto-processor`, `-etl`, `-engine` or `-acquire` — is enforced twice: a `maven-enforcer` rule in
each governed pom AND `tools/check-module-deps.mjs` (policy table `ALLOWED`, derived from the poms, mutation-checked).
(6) The HTTP and auth contracts were mutually dependent; the cycle was broken (not merged into one module) because the auth
classes used `ApiContext` only through static request-attribute helpers — now `RequestAttrs` + a one-method
`WriteRootProvider`, with `ApiContext` keeping delegating aliases so no call site changed.

**Seams.** Two ports, not the three designed: `AlertPort` was dropped because nothing in `la-*` calls the Alert service —
Alert-Rule code is bridge code by nature. An unbound port makes its feature report itself absent (`DatasetProviders.require()`
→ `503 CAPABILITY_UNAVAILABLE`; unbound `CasePort` = the "ops not installed" behaviour), never half-working; both `*Ports`
have `forTestAbsent`. Routes, URLs and `openapi-v1.json` did not change. `PendingChanges` stays host-side.

**Gotchas.**
* A new module is one more place the hand-kept mirrors (`AbsentGeoLinkRoutes.SURFACE`, `CapabilityManifest`, the bundle
  module lists) must agree: add it to `tools/bundle-modules.mjs` and the static profile list in `pom.xml` in the SAME commit.
* An import closure is a prediction, not proof. The first closure script treated a `/*` inside a string literal as a
  comment and undercounted 46 → 20 classes; it also could not see fully-qualified inline references, which the compiler
  found. Re-ground with a string-aware tokenizer and prove with `mvn clean test-compile` of a clean checkout.

**Deliberately deferred.** The split package `com.gamma.control` across `inspecto-http-spi`, `inspecto-auth-spi` and
`inspecto-processor` works on the classpath but forbids JPMS; a rename to `com.gamma.spi.*` is a separate mechanical change,
done only if JPMS or a clean public surface is wanted.

## Closed-plan record (2026-10-01)

`link-analysis-backlog-plan.md` was the only open backlog for Link Analysis from 2026-09-22; it was retired
2026-10-01 when gate `G-R4` certified ([archived](../../../archived-documents/plans-archive/link-analysis-backlog-plan.md);
its §5 holds the per-route contracts and tamper-test narratives, kept for provenance). Every work item
`LA-01`…`LA-24` and every decision `D-S1`…`D-U11` is SHIPPED, ANSWERED or DECLINED; the unbuilt remainder is
filed on the board under the ids named below. The facts above are the as-built; this section adds only what
no paragraph above states.

**Measured limits — the shape, not the numbers.** One host, one browser, one synthetic shape (operator
2026-09-22), so the numbers do not transfer and the caps are per-Space settings. The shape does: the default
`dagre` layout has a **cliff between 500 and 750 nodes** (0.9 s → 10.7 s; 16.2 s at 1 000; force layout ~2.4×
dearer at 500), which is why `PROJECTION_NODE_CAP = 500` is well chosen and cannot rise without changing the
default layout. Analysis is not the constraint with ONE exception, **betweenness** (425 ms / 2.9 s / 9.8 s at
500 / 1 000 / 2 000 nodes; the other 26 algorithms total ~129 ms at 2 000). 🔴 *Suspicion score* was first
blamed because it was the only thing measured that called betweenness; the toolbox's centrality control offers
seven algorithms and the sweep timed only the default (degree), so a per-feature table hid a
per-option cliff — **time every option a control offers**. Betweenness now shares the lower 750 cap.
`LA-07` (Web Worker for the algorithms) was therefore CLOSED 2026-09-23, not built: one slow algorithm under the
750 cap does not repay a serialised-graph round trip plus new worker build config. Reopen only if an analyst
reports the ~425 ms as a felt freeze. `LA-06`'s culling and progressive-load clauses were refused as premature
(see above); if the projection cap ever rises, the cheap starting points are G6's registered
`optimize-viewport-transform` and `auto-adapt-label`.

**The model's closed rules** (the Investigation is the program, the Working Set its derived state, the Artifact
immutable and anchored to a log position). The op vocabulary is **closed — no twelfth op without a decision
entry**: `seed` · `seedBy` · `expand` · `exclude` · `excludeBy` · `keep` · `threshold` · `window` · `hide` ·
`annotate` · `snapshot`; reasons: the backend is safe because it is narrow (`SAFE_IDENT` + bind parameters), a
closed set renders mechanically as numbered plain-language steps for a court, and every open query language
converges on SQL. As built, `undo` is a recorded log edit and **not** a twelfth op; `resolve` (LA-17) was added
under its own decision (design `D-M…`) to seal identity groups. All eleven are now evaluable (`InvestigationRoutes.DEFERRED` is gone; an op outside the vocabulary answers 422 *"unknown"*).
**`threshold {min?, max?}` and `snapshot {label?}` (2026-10-04, `LA-INVESTIGATION-OPS-DEFERRED-1`) — the NARROWEST reading
of plan §2.2** (the plan row says only "measure, min, max, evaluation scope" / "freezes the Working Set as an Artifact"):
`threshold`'s measure is an entity's **degree** (distinct counterparties among the Working Set's links at that step;
hidden still counts, self-loops don't), its scope is the whole Working Set, the band is `min` inclusive / `max`
exclusive (at least one required, `min < max`, no `ids`); everything outside is excluded exactly as `exclude` (keep
protects, reason `threshold: degree outside [min, max)`, never re-admitted by a later expand), measured ONCE so it
does not cascade. `snapshot` is a **marker**: it moves nothing and writes no file — the artifact is the log position
(every entry already carries the Working Set hash). Both ride `POST /inv/investigations/{id}/ops`, so the gates are
the existing `canManageIncidents` route, openapi entry and manifest row — no new route; templates carry both verbatim.
**SPA (2026-10-04):** `investigation-band-ops.component.ts` holds two sibling forms beside the `window` form
(`inspecto-la-threshold-op` *Degree threshold*, `inspecto-la-snapshot-op` *Log marker*), same pattern: host-owned
reactive form, `store.apply(...)`, the server's 422 shown verbatim in an `inspecto-alert`. Client-side mirror of the
422s: threshold needs at least one bound, `min` an integer >= 0, `max` an integer >= 1, `min < max`; snapshot `label`
is optional, trimmed, <= 2000 chars; neither sends `ids`. The op log line is the SERVER's `text` (the SPA does no
op formatting), so nothing client-side renders these two. `InvestigationOpName` / `InvestigationOpRequest` gained both ops.
**`threshold` measures (2026-10-05):** `{measure?}` — `degree` (default; absent from the entry, so every older log and
hash replays byte-identically), `weightedDegree` (links touching the entity, each kind + direction once, so a
counterparty reached by two kinds weighs 2) and `eventCount` (sum of those links' folded event `count`). ⚠ Call: the
Working Set carries ONE weight, the folded event count, so "weighted degree" as sum-of-weights would equal
`eventCount`; `weightedDegree` is therefore the multigraph degree and `eventCount` is the weighted one (rename is a
one-line change if the operator prefers another word). Self-loops count for no measure; band, protection, no-cascade
and reason (`threshold: <measure> outside [min, max)`) are as for degree. An unknown measure is 422. SPA: a *Measure*
select on the threshold form (sent only when not `degree`). **`snapshot` does NOT seal a `/inv/snapshots` Artifact —
reasoned decline (2026-10-05):** the Working Set at any log position is already reconstructible and verified (each
entry carries its Working Set hash; replay checks it; the Dossier `at` freezes any position), so a server-side copy
would be a second source of truth that bloats the log; and a `/inv/snapshots` Artifact is a CLIENT-computed record
(layout, metrics, `manifestHash`) the server cannot author. The need it would serve (frozen metrics/layout) is met by
the SPA's snapshot dialog, whose Artifact anchors to the Investigation and is already carried with custody by the
Dossier. Revisit only if an operator needs a server-authored capture independent of the log. **Re-evaluable `threshold` DECLINED (2026-10-05, reasoned).** Re-admitting what an earlier threshold excluded is not sound with the sealed-replay model: an exclusion DELETES the entity's links from the Working Set, and every later `expand` drops rows touching an excluded id, so the links a re-admission needs are gone from the state (and absent from the sealed rows). A re-evaluated degree would be measured on a truncated graph and would admit entities whose measure is wrong; fixing that means retaining shadow links in the state and the hash, which changes every old log's bytes. The explicit path already exists and is honest: a later `seed` re-admits an excluded id (analyst intent wins) and an `expand` re-reads its links. Revisit only on an operator ask, as a new op, never by changing `threshold`. The log is **ordered and non-commutative** (`exclude → expand` ≠ `expand → exclude`;
re-ordering forks, D-E4); **`hide` ≠ `exclude` ≠ `keep`** (hide: gone from display, still traversed and counted;
exclude: gone from all three, still inspectable as the `excluded` relation; keep: protected from later
excludes). Two evaluators, one spec — incremental on append, full replay from the sealed log — with an
`equivalent` check that is pinned to be able to FAIL. **Negative space is part of every rendering** (G-E10).
Calendar exclusions and timeline playback shipped 2026-10-05 (below and in the window section) — see
`LA-INVESTIGATION-OPS-DEFERRED-1`.

**Timeline playback (2026-10-05, SPA only).** A strip above the canvas
(`link-analysis-timeline-playback.component`, pure slicing in `timeline-playback.ts`) steps through the timeline
column's `[min, max]` in 20 equal slices — play / pause (1 s per slice, stops at the end) / previous / next / a
range scrubber / reset — and highlights the links whose `attrs[col]` date falls in the current slice (half-open, the
last slice includes the max) through the shared canvas `emphasis`. It writes NOTHING to the graph, the timeline
filter or its cutoff, and a link with no parseable date is active in no slice. The status line (`role=status`)
names the slice and its link count (`aria-live` off while playing, so it does not read every tick). **Reduced
motion:** under `prefers-reduced-motion: reduce` nothing advances by itself — Play is disabled and says why; step and
scrub remain. Calls made: the range is the TIMELINE column's, not the Investigation's `window` — a Working Set has
no per-link timestamps (it is sealed), so playback exists on the query-graph canvas only, like the timeline filter
beside it; a link carries ONE date, so an aggregated link plays at the date it carries; fixed 20 slices, no
slice-size knob; no jump to a burst. Clearing the time column clears the highlight.

**Time-respecting paths and burst / periodicity (2026-10-04).** Two operator-approved pieces, both built as
**stateless Dataset reads, not Investigation ops and not graph-run algorithms** — an op only appends a
state transition to the log, and the Working Set (`entities` / `links` relations) and `/inv/graph/runs` carry
counts, never edge timestamps, so only the Dataset routes (which read the `timeCol`) can see event times.
* **Time-respecting paths already existed**: `POST /inv/traversal/recursive-paths` took
  `temporalConstraint {timestampCol, monotonic, maxTotalDurationHours}` since LA-11 (2026-09-23) — *monotonic* IS
  "consecutive edges in non-decreasing time order" (ties pass). The row's "unbuilt" claim was stale; the only
  missing piece was the per-hop gap, now **`temporalConstraint.maxGapHours`** (positive; **422 without
  `monotonic: true`**, because a gap bounds a time-ORDERED path). Flat CTE and index walk
  (`IndexedTraversal`) both apply it, pinned equal by `ControlApiInvIndexedTraversalTest`. It only ever removes
  paths, so it cannot widen anything; the existing four-eyes gate, depth/yield/timeout fences and the Dataset
  view gate (R3: unviewable = 404) are unchanged. A time column read in a non-UTC zone still falls back to the flat
  read (`time_zone_not_servable`), as before.
* **`POST /inv/pattern/temporal`** (`PatternRoutes`, body `{dataset, sourceCol, targetCol, timeCol, mode:
  "burst"|"periodicity", series?: "link"|"entity", filter?, limit?, windowSeconds?, minEvents?, maxCv?}`; pure maths in
  `LinkTemporalDetector`). The series is the event times of one **link** = a directed source→target pair as the Dataset
  spells the values (not normalised; `series: "link"`, the default). *Burst*: a `windowSeconds` window (default 60, 1–86 400) holding
  ≥ `minEvents` (default 5, 2–1 000) events, overlapping windows merged into one maximal burst, window edge
  inclusive. *Periodicity*: ≥ `minEvents` (default 5, min 3) events whose gap coefficient of variation (population
  σ / mean) ≤ `maxCv` (default 0.1, 0–1); reports the MEDIAN gap as `periodSeconds`. Out-of-range numbers are a 422,
  never clamped. Results are strongest-first (burst: most events; periodic: lowest CV) and cut to `limit`
  (default 200, max 1 000), `truncated` says so. **Fences:** one bound statement of ≤ 200 000 rows (`rowCapped` +
  `truncated`, never silent), a 5 s timeout, D-U7 four-eyes (`refuseIfSensitive`, 403 above the Space's threshold — the
  route has no Investigation to hold an approval), rows with no parseable time skipped and counted
  (`skippedNoTime`). Times come back as the Dataset's wall-clock text with NO zone claimed (gaps are differences of
  the stored values, as in the recursive-paths flat read). **Masking:** the route reads a Dataset the caller can view
  and returns only the two endpoint columns and times it was asked for — the same exposure as
  `/inv/pattern/branching`; it takes no Investigation, so no `EntityMasking` applies and none can be widened.
  Read-shaped (exempt in `CapabilityManifest`, audited as `LINK_PATTERN_MATCHED`). 🔴 DuckDB reserves `at`: a test
  column so named made every read 422.
* **Per-ENTITY series and the edge index (2026-10-05, `LA-INVESTIGATION-OPS-DEFERRED-1`).** `series: "entity"` (an
  unknown value is a 422; absent = `"link"`, so today's callers see no change) pools EVERY event an entity takes part in,
  as source or as target, into one series; a **self-loop row is ONE event** of its entity (not two). A finding then
  carries `entity` instead of `source` / `target`; the maths, the bars, the order and every fence are the link mode's
  (the row cap and `skippedNoTime` count EDGE ROWS, the same rows either way). The answer gains `series` and `source`
  (`{kind: "index"|"dataset", reason?}`). **Index serving** (`IndexedTemporal`, via the ONE gate `IndexedRead`): when a
  published index maps the same source and target columns AND its time column IS the request's `timeCol`, read in UTC,
  AND every `filter` field is an index column, the rows come from a full ordered scan of the index's `out` copy
  (`IndexReader.scanLinkTimes`: `src, dst, epoch_ms(ts)` in the flat statement's order, ≤ 200 001 rows) and feed the SAME
  detection the flat read feeds — so the findings are identical by construction, and
  `ControlApiInvTemporalIndexedTest` PROVES it (index on vs off, both series, both modes, a filter, a self-loop, a NULL
  time, NULL endpoints, ARMED Authenticator). Otherwise the flat read answers with a closed reason
  (`no_index`, `index_disabled`, `column_not_indexed` — e.g. an index built without the time column — `time_zone_not_servable`,
  `filter_not_indexed`, `index_stale_refused`, `index_read_failed`). **Calls made:** (1) the request mode is a NEW field
  `series`, not an overload of `mode` (which is already burst/periodicity); (2) an entity series reads the SAME edge
  rows as the link series and fans them out in Java rather than a second index lookup per entity, so no per-key
  bucket reads and no new index column; (3) the index path is a FULL scan (no per-key equality: a temporal scan has no
  key), so it saves the relation's evaluation, not the Dataset-size cost — the row cap still applies; (4) the base-Dataset
  view gate and the four-eyes refusal run BEFORE the index is considered, so a Dataset shared away is the same 404.
  Pure gotcha: `ResultSet.wasNull()` speaks of the LAST column read — reading `getString` between `getLong` and
  `wasNull` turned every NULL time into 1970 on the index path until the equivalence test caught it. The SPA panel
  gained a "Series per: Each link / Each entity" selector (`series` on the wire); an entity finding highlights its NODE
  and is listed by name. ✅ Sealing a finding set as a `temporal` op shipped 2026-10-05 (below, *The `temporal` op*).
* **SPA overlay (2026-10-05).** `link-analysis-temporal-findings.component.ts` is a panel under the Analysis tab of the
  Toolbox (a sibling of the toolbox, shown/hidden with `[class.hidden]`). It needs a **time column** and an **edge mapping**
  (the host's `traversalTargets`, the same ones server paths and the server pattern use), calls
  `InvService.temporalPattern` and hands the answer to the pure `temporal-findings.ts` (`temporalResultToState`).
  🔴 Unlike `recursivePathsToGraph` / `branchingResultToGraph` it **never adds nodes or links**: each finding's raw
  endpoints are minted with `resolveEntityId` and matched to the drawn links running source -> target; one the canvas
  does not draw stays listed, disabled and counted as "not drawn on this canvas" (the scan ran over the whole Dataset,
  not the loaded slice). A click highlights that link; *Highlight all* and a fresh run highlight every drawn finding.
  The panel states `truncated` / `rowCapped` / `skippedNoTime` and the server's `timeNote` (no zone claimed). Numbers
  are clamped to the server's ranges client-side so a typo is not a 422. The panel is not wired to a saved view. A
  **Seal these findings** button (below, *The `temporal` op*) appends the scan to the open Investigation's log.

**The `temporal` op (2026-10-05, `LA-INVESTIGATION-OPS-DEFERRED-1`) — a burst / periodicity finding set sealed into the log.**
`{op:"temporal", mode:"burst"|"periodicity", series?:"link"|"entity", windowSeconds?, minEvents?, maxCv?, limit?}` through
`/ops`: a marker like `snapshot` / `compare` — the Working Set and its hash do not move. `InvestigationRoutes` runs the SAME scan as
`POST /inv/pattern/temporal` (`PatternRoutes.scan`, now a static the op shares: same gates, four-eyes refusal, row cap, edge-index
serving) over the Investigation's OWN `dataset` / `sourceCol` / `targetCol` / `timeCol`, narrows the findings to the Working Set at that
log position, and seals them into `entry.temporalFindings` (`TemporalFindings`): `{dataset, params, workingSet, count, results,
outsideWorkingSet, truncated, rowCapped, skippedNoTime, timeNote, servedFrom, readAt, sealed:true, basis, fingerprint}`. The
`fingerprint` is SHA-256 over the content (not `basis`, `readAt`, `servedFrom`). Knobs are validated with the route's bounds (422, never
clamped) and stored with every default filled. **Calls made:** (1) the columns are the Investigation's, never the request's — a sealed set
must say what the Investigation said, and a client-supplied finding list would be unverifiable; (2) the Working Set narrowing: a link
finding is kept when some Working Set link has that source and target (any kind), an entity finding when the entity is admitted — what is
dropped is counted in `outsideWorkingSet` and never named, so the op names nothing the analyst could not already see; (3) the scan reads
at the route's maximum limit (1 000) and the op's `limit` cuts AFTER the narrowing, so `truncated` also covers the scan's own cut;
(4) the Investigation's window is NOT applied (the stateless route has none, times are wall-clock as written); (5) a time column is
required (422, as `compare`). **Wiring (mirrors `compare`):** `InvestigationEvaluator` treats it as a marker; append, `replayOp` (Draft rebase / promote) and `reorder` (fork)
RE-SEAL over the new Working Set (a fork ordering the scan before the expand seals zero findings — tested); replay never re-reads the
Dataset; the log view carries counts only (`TemporalFindings.summary`); `GraphDossierBuilder` hashes the fingerprint (a forged
count is `integrity` red with `sealed temporal findings hash to`, and `/dossier/verify` names `log.jsonl#N`), puts a top-level
`temporalFindings` section `[{step, findings}]` only when an effective step carries one (undone steps are skipped), and the root stays
deterministic; masking is applied at render over the raw ids (`EntityMasking`); `InvestigationTemplateRoutes.CASE_OPS` drops it as case
evidence (it names the case's own links). Gate: the existing `/ops` one (`canManageIncidents`; 401/403 tested). Tests:
`ControlApiInvestigationTemporalOpTest`. **SPA:** the temporal panel's *Seal these findings* button (disabled with a hint when no
Investigation is open) sends the knobs of the scan on screen (`link-analysis.component.ts` `sealTemporal`) and shows the step, the
count kept, those outside the Working Set and the fingerprint — the server re-runs the scan, so what is sealed is its answer, not the
panel's rows. ✅ **Dossier HTML shipped 2026-10-05**: `format=html` renders a *Comparisons* section (the sealed `comparisons`) and a
*Temporal findings* section (the sealed `temporalFindings`), each ONLY when the log has such an op (so every other Dossier renders
byte-identically), through the generic `DossierHtml.value` table over the already-masked map (masking stays upstream in `maskedDossier`,
every value `esc`ed; no new code path to mask). Test: `ControlApiInvestigationTemporalOpTest.theHtmlDossierRendersTheSealedFindingsMaskedAndDeterministic`
(absent without the op; deterministic apart from the `generated` stamp; no raw id under `masking_mode: all`). The host's `sealTemporal` is
spec'd in `link-analysis.component.spec.ts` (knobs sent per mode, sealed summary, server refusal, no-scan no-op).
❌ **Applying the Investigation's window to the `temporal` scan DECLINED 2026-10-05.** (1) The scan is `PatternRoutes.scan`, shared with
the stateless `POST /inv/pattern/temporal` and the edge-index serving path (`IndexedTemporal`); neither has a window, so a windowed seal would
need a second, row-filtered read path proven identical to both. (2) The panel shows a whole-range scan and *Seal these findings* seals "its
answer"; a windowed seal would silently differ from what the analyst is looking at, in a way the Working Set narrowing (already disclosed
as `outsideWorkingSet`) does not. (3) Clipping changes the meaning: a burst straddling a window edge is cut, and a periodicity `cv` over a
clipped series is a different statistic. (4) Old logs are NOT at risk (replay never re-reads the Dataset, findings are frozen), so this is a
semantics call, not a compatibility one. The analyst who wants a windowed answer has `compare` (`window: "inherit"`, activity mode). Reopen only
if an operator asks for a windowed scan by name; it would be a new `window` knob on the scan route first, then the op.

**Comparison mode (2026-10-04, `LA-INVESTIGATION-OPS-DEFERRED-1`) — a READ route, not an op.** `GET /inv/investigations/{id}/compare?aFrom&aTo&bFrom&bTo&timezone&at`
(`InvestigationComparisonRoutes`, semantics in `WindowComparison`) diffs two time windows over the Working Set at log position `at` (default head):
links and entities present in A only, B only, both (links carry `countA`/`countB`), plus `neither`, each list capped at 500 with the true `count`. *Why a read:*
a sealed Working Set holds folded counts with no timestamps (see `window`), so a time-sliced diff can only come from the LIVE Dataset — the same live read
`coverage` makes — and it changes nothing in the Working Set; an op would have to seal the result into the entry and thread through append, undo, fork, Draft rebase,
template and replay for evidence a reader re-runs by asking again. The answer says `sealed: false`. *Semantics (narrowest):* the universe is the Working Set's
entities and links only (an `e–f` pair in the Dataset that was never admitted, and an excluded entity, never appear — tested); a link is *present* in a window with
>= 1 event there, an entity when it is an endpoint of a present link; expand rungs (`minEvents`, direction, link kinds, degree) are not applied; windows are `from`/`to`
(+ one shared optional `timezone`; no slot/day mask), validated by `InvestigationTime.window`; needs a `timeCol`; exact, a read over 50 000 links in one window is a 422
(no silent sample); at most 2 000 Working Set entities. *Masking:* applied at render with `EntityMasking` exactly as the Working Set is; every id in the answer is one the
Working Set already holds, so it names nothing new (a `masking_mode: all` test pins no raw id leaks). Access: `openForRead` (owner / Case member, R3, PDP) + the R3 gate on
the Dataset read; audited best-effort as `LINK_INVESTIGATION_COMPARED`. *Dossier:* `GET …/dossier` carries an extra `comparison` section ONLY when the same window
parameters are passed; it is labelled `sealed: false` and sits OUTSIDE the manifest and integrity check (the rest of the Dossier reads no Dataset, and the manifest root is
unchanged by it); the `steps`/`method` renderings and the HTML print do not include it. Gates cleared: `AbsentGeoLinkRoutes`, `openapi-v1.json`, the RouteModule service
file; a GET needs no `authgate` bare literal, `CapabilityManifest` row or `route-gating` entry (read route). Test: `ControlApiInvestigationComparisonTest`.
Both open questions (per-side `window: inherit`; event-count *activity* diffs) were built into the sealed `compare` op below (2026-10-05); the read route gained `mode=activity` on 2026-10-05 (below).

**Comparison as a SEALED op — `compare` (2026-10-05, `LA-INVESTIGATION-OPS-DEFERRED-1`, operator-recommended slice).** The read route above stays (live, unsealed); `POST /inv/investigations/{id}/ops`
now also takes `{op:"compare", windowA:{…}, windowB:{…}}` (each side is a `window` object — `InvestigationTime.window`, so `from`/`to`/`slot`/`days`/`timezone`; needs a `timeCol`). **Design calls:**
* **A marker op, like `snapshot`:** the evaluator's `apply` does nothing for it, so the Working Set and its hash do not move; the artefact is `entry.comparison`.
* **Sealed at append, never re-read:** the route runs `WindowComparison.compare` over the Working Set *before* the step (the same live Dataset read the route does, R3 gate included), then `WindowComparison.seal`s it
  into the entry: JSON-normalised, `sealed: true`, plus a `fingerprint` = SHA-256 over `{windowA, windowB, workingSet, links, entities}`. Nothing time-varying is inside (no `readAt`), so a Dossier root over a log carrying it is
  deterministic; replay carries the sealed diff and re-reads nothing.
* **Custody:** the diff lives in the `log.jsonl#<step>` line, which the Dossier manifest already hashes byte for byte, so `/dossier/verify` and the bundle's custody check cover it with no manifest change. `GraphDossierBuilder.build`
  also recomputes the fingerprint per `compare` step and reports a mismatch under `integrity.failures` (probe: a hand-edited `eventsA` goes red on BOTH the verify and the integrity check). The Dossier gets a top-level
  `comparisons: [{step, comparison}]` (effective, non-undone steps; absent when none, so older manifests/Dossiers are byte-identical), the `steps` / `method` renderings and the ledger carry a line, and the `json` rendering carries counts only.
* **Fork / Draft promote / template:** a fork (`reorder`) and a Draft promote RE-SEAL over the new Working Set (a different order is a different universe; as an `expand` re-reads). A template DROPS it (`CASE_OPS`: two concrete windows are
  this case's evidence, not method), like an `exclude`.
* **Log view** (`GET …/log`) and the plain-language line show counts only (`links only in A n, …`); the append answer carries the full sealed `comparison`. Masking is at render over raw sealed ids, as for the Working Set.
* **No new route:** `/ops` already gates on `canManageIncidents` (bare literal in `InvestigationRoutes.register`), `openapi-v1.json` does not enumerate ops, and the route-gating table is unchanged. Test: `ControlApiInvestigationCompareOpTest`
  (real HTTP, with a Subject: gate 403/401, tamper probe, masking, determinism, fork re-seal, refusals). Slot/day masks per side fall out of reusing `InvestigationTime.window` (pinned: a `days:["TUE"]` side drops a Wednesday event).
* **`window: "inherit"` per side + `mode: "activity"` (2026-10-05, design calls).** (1) *inherit* is the string `"inherit"` for `windowA` and/or `windowB` (the same spelling `expand` already uses). The stored params keep `"inherit"`; the SEALED diff carries the
  CONCRETE window it resolved to (the Investigation's window at that step, `state.window`) plus `inherited: ["B"]`, so replay and the Dossier read a fixed fact and never re-resolve. It is **refused 422 when the Investigation has no window at that step** (it
  reads the full time range: an unbounded side is almost surely a mistake and would make a diff against "everything"); a fork / Draft promote re-resolves it against the new order's window and refuses the same way. Both sides may inherit (a diff of a
  window with itself: nothing moves). (2) *activity* = `mode: "activity"` (absent / `"presence"` = the old behaviour, kept OUT of the params so earlier steps are byte-identical). The presence sections stay; the sealed diff adds `mode` and
  `activity: {links:{changed, unchanged}, entities:{changed, unchanged}}` where `changed` is a capped (500) section of `{source,target,kind,countA,countB,delta}` / `{id,countA,countB,delta}` with `delta = countB - countA`, sorted by |delta| desc then id
  (deterministic). An entity's count is the in-window events on the Working Set links it ends (a self-loop once); still Working Set only, exact, same row cap. (3) *Fingerprint:* `mode`, `activity` and `inherited` join the hashed content ONLY when present, so
  a presence diff sealed before this slice still verifies; a presence and an activity diff of the same windows seal different fingerprints. The Dossier root stays deterministic (no clock); the tamper probe on a hand-edited `delta` goes red on the integrity
  check. The log line and `GET …/log` carry counts only (`event count changed on n links and m entities`). The GET read route and the threshold on the delta were built the same day (below). Test additions: `ControlApiInvestigationCompareOpTest`
  (`activityModeSeals…`, `aSideMayInheritTheInvestigationsWindow`).
* **`GET …/compare` `mode=activity` + `minAbsDelta` (2026-10-05, design calls).** (1) The read route takes `mode=presence|activity` (default presence; anything else 422) and answers the SAME `activity` section as the op, from the same code (`WindowComparison.compare`), still live and `sealed:false`, masked at render. (2) `minAbsDelta` (integer >= 1; the op's body field, the route's query parameter; 422 unless activity mode) lists as `changed` only moves with |delta| >= the floor; the smaller moves are counted under `belowMinDelta` per section (`unchanged` keeps meaning delta 0), and the floor is echoed as `activity.minAbsDelta`. On the op it is sealed in the params and, being inside `activity`, in the fingerprint ONLY when given, so every diff sealed earlier verifies byte-identically; the log line names the floor and `GET …/log` counts carry `belowMinDelta`. SPA field shipped 2026-10-05: with *By event count* ticked the compare form shows *Smallest count change to list (optional)* (a number input; a non-integer or < 1 is refused in place with `role=alert`, nothing is sent; unticking *By event count* sends no floor; the server's own 422 remains the backstop). Tests: `ControlApiInvestigationComparisonTest.activityModeOnTheReadRoute…`, `ControlApiInvestigationCompareOpTest.minAbsDeltaIsSealedOnlyWhenGiven…`. No new route, so no new gate: the GET is unchanged in the openapi skeleton and route-gating report.
* **SPA:** `InvestigationCompareOpComponent` (`inspecto-la-compare-op`) beside the window / threshold / marker forms in the Investigation pane — two `inspecto-la-window-fields` (the window op's own field set), a per-side *Use the Investigation's window* checkbox (sends `"inherit"`, hides that side's fields), a *By event count* checkbox (sends `mode:"activity"`) and `Compare and seal`.

**Listing and link notes (2026-10-01).** `GET /inv/investigations` lists what the caller may read — own plus
those shared through an open linked Case, each judged by `openForRead` so R3 and a PDP DENY hide it — `{id,
title, dataset, owner, createdAt, headStep, caseRef?, access: owner|case-member, readOnly}`, newest first,
`limit`/`offset`/`total`/`truncated`. The Investigation tab has *List Investigations* (rows openable; Case-shared
rows read-only) and *Open by id*; link notes are sent by `linkId` (*Annotate link*), the sealed `linkAnnotations`
render, and the Dossier stamps `linkId` on its link annotations.

**Decisions of record not stated above.**
* **D-E3 deferral — byte-identical replay (`G-E2`) needs version-addressable Dataset reads and is the ONLY gate
  that does.** `G-E11` (an Evidence Widget does not move) and `G-E3` (divergence detected, never served) are
  satisfied today by sealing + fingerprint comparison. A recorded file list was **rejected** as a pin: compaction
  moves files to `COMPACTED_AWAY`, so it would name files maintenance may delete — a pin that rots is worse than
  none. When replay is wanted, copy `ReferenceReader`'s shipped SCD2 `asOf` read rather than inventing one.
* **D-U8 — NO purge** (operator 2026-09-24): no retention period, no purge task, no legal-hold record; the store
  stays append-only evidence. Reopen only if a regulator or retention policy demands it; the pattern to copy is
  `incident_purge`.
* **D-U9 — per-Collector coverage SHIPPED 2026-10-05** (`LA-COLLECTOR-COVERAGE-1`). The coverage route's `collectors`
  part is attributed through `CollectorCoveragePort` (bridge `HostCollectorCoveragePort`): each Pipeline's `batches` CSV
  (Consignment → output store) joined to its `lineage` CSV (Consignment → event-day partition, rows), batches kept when
  they wrote the Dataset's `physicalRef`/`sourceName`, attributed to `collector().id()`. The Dataset-filename join was
  **rejected**: lineage `src_id` is a member index, the acquisition ledger / `file_stages` are opt-in with no list-by-file
  read, `file_name` is optional and basenames collide. No attribution evidence ⇒ `assessed:false` (never "no gaps"). The
  Dossier does not seal live coverage (its root is deterministic); its negative-space note points at the route. Residual:
  never-delivering Collectors are invisible; partition days use the Pipeline's pinned zone.
  SPA (2026-10-05): the Investigation pane's Coverage section renders `collectors.collectors` as a table (Collector · days
  covered of expected · missing days); `assessed:false` prints "Per-Collector coverage: not assessed" — never a blank or
  "no gaps". `HostCollectorCoveragePort.day()` is pinned for the `year=/month=/day=` and `dt=` partition forms.
* **D-U11 (2026-09-30) answers** that no paragraph above carries: A1 a snapshot captures what is on screen · A2 the
  cash-out share denominator is ALL cash-out · A3 the agent Entity List is read LIVE and its fact-log head seq/hash
  is recorded on each firing (`evidence.agentList` / `agentListSeq` / `agentListHash`) · A4 a retired list is 409 ·
  A5 value-measure queries run in UTC · A9 `coverage` is readable by Case members (reverses D-U10's owner-only) ·
  A10 pinned Widgets are unaffected. Merged-exclude follow-ups: the resolution view is widened by the SAME
  matching rule as exclude/expand; only an UNAMBIGUOUS untyped match counts; old dev logs replaying
  not-equivalent are acceptable (no compat path); the Dossier line states the full outcome.
* **Naming (D-E1):** *Investigation* / *Investigation Template*; the Assistant's RCA run took *Triage* instead
  (`GLOSSARY-CASE-1`, closed).
* **`SEP-02` (separation plan, decided 2026-10-01):** keep `AbsentGeoLinkRoutes.SURFACE`, guarded in both
  directions by `GeoLinkAbsentSurfaceParityTest`; revisit when the standalone LA host exists. A module-contributed
  list cannot serve the stub, which must exist precisely when the module is NOT on the classpath.

**`G-R4` certification (LA-11).** 5-hop recursive paths over a heavy-tailed 10⁶-edge Dataset, through the real
route: warm p95 **209 / 188 / 171 ms** from a median-degree / p99 / top-hub start (all < 350 ms; the gate is stated
on warm runs, cold p95 388 / 181 / 163 ms over 5 runs). The fix that mattered: `EXPLAIN ANALYZE` showed the
recursion running TWICE (`__walk` read by the paths and again by the level widths) → `__walk AS MATERIALIZED`.
Also: the column probe is folded into the walk's own session (`QueryExecutor.runPlanned`), and the walk runs under
its own `-Dassist.sql.traversal_threads` (default 4; every other sandbox query keeps `assist.sql.threads`, 2).
Harness `InvTraversalBench` (`@Tag("bench")`, skipped unless `-Dinspecto.bench.dir`); method in
`la-separation-feasibility-plan.md` §7.10.1.

**Gotchas worth keeping.**
* The plan once said the `/inv/*` routes could not appear in `openapi-v1.json` because the contract test lives
  in a module with no edge to the optional `inspecto-geo-link`. **That is no longer true** — all 38 `/inv/…`
  paths are in `docs/api/openapi-v1.json`; trust the file, not the old note.
* A DI change is invisible to type-checking (removing public methods from an injectable compiled clean; only
  the specs found a caller using `new Service()`), and `link-analysis.component.spec.ts` (the only spec driving the
  snapshot-and-attach flow end to end) was found 28/28 red on master (2026-09-23) and had hidden every regression
  it guards — read a flow spec's last green date.
* A well-tested evaluator can be DEAD while the live path has none (`evaluateRows` had zero callers): reviving it
  must turn mirror coverage into live coverage.
* Plan claims here went stale within days (five-place-wrong audit table; a premise that "published routes"
  would break a rename when none had shipped; line cites drifting). Re-ground against code before acting on any
  plan row — including these.
* Live check 2026-09-30 (Enterprise `-DemoAuth` bundle, three Demo Users): 33/33 steps passed once the demo Dataset
  classified its account columns as `ACCOUNT` and `account_links_dataset` existed for identity import.

**Still open — filed on the board (`docs/BACKLOG.md` §3.12).** 
`LA-INVESTIGATION-OPS-DEFERRED-1` · `LA-LIVE-DETECTION-1` · `LA-DOSSIER-OUTPUT-1` · `LA-COLLECTOR-COVERAGE-1` ·
`LA-DRAFT-PROMOTE-COST-1` (filed 2026-10-03 when `LA-SEP-SPIKES-1` closed; both halves shipped - promote linear 2026-10-03, the Draft cap and idle periods as `drafts` settings keys 2026-10-04 - and the row is closed). Standing refusals with reopen triggers are in §6 under *Link Analysis & Geo*.

Design (archived): [`link-analysis-and-graphsource.md`](../../../archived-documents/plans-archive/link-analysis-and-graphsource.md)
· [`link-analysis-projection-authoring-plan.md`](../../../archived-documents/plans-archive/link-analysis-projection-authoring-plan.md)
§7 (schema-relationship model, now shipped) ·
plans: [`link-analysis-studio-plan.md`](../../../archived-documents/plans-archive/link-analysis-studio-plan.md)
(§6–7, V1 now fully shipped; V2+ remains open backlog),
[`link-analysis-toolboxes-plan.md`](../../../archived-documents/plans-archive/link-analysis-toolboxes-plan.md).

## InvestigationStore port (S0 + S1, as built 2026-10-04)

The Investigation sidecar records now sit behind `InvestigationStore` (`inspecto-la-core`), keyed by id and `Scope(investigationId, draftId|null)`, never a Path; `FsInvestigationStore` is the only implementation and `InvestigationStores.of(writeRoot)` the one place a backend is chosen. Writers carry the version they read (`append(scope, expectedVersion, ...)`, `appendMember(expectedCount)`); a lost race is `InvestigationVersionConflictException` (the ObjectStore convention), retried by the route and answered `409 CONFLICT_STALE_VERSION` if it keeps losing. Lines and sets come back byte for byte (the sealed `workingSetHash`, `prefixHash`, the set re-hash all depend on it). Drafts (create, close, promote, rebase swap, lifecycle, listing) are port operations too, with preconditions (`promoteDraft` / `replaceDraft`), `decide` claims a pending request by compare-and-set before its append, the mask key is `maskKey(id)`, per-pod caches are keyed by `cacheKey(scope)` + `logToken(scope)`, and a promote a crash interrupted is finished or undone by `recoverDrafts` (an intent file written before the first main step). S1 is complete. Measurements, behaviour decisions and as-built notes: `archived-documents/plans-archive/investigation-store-design.md` sections 13.1 to 13.3 (row `LA-INVESTIGATION-STORE-DESIGN-1`).

### Postgres backend and its selection (S2 + S3 to S6, as built 2026-10-04)

`-Dinvestigations.backend=fs|db` (default `fs`, every edition) is read by `InvestigationStores.of`. `db` is the optional `inspecto-la-store-pg` module (Enterprise and Preview bundles; an `InvestigationStoreProvider` found by `ServiceLoader`): `PgInvestigationStore`, one schema `space_<id>` per Space (the platform's `OperationalDb.schemaFor` naming, re-implemented in the module because la-core cannot see core), one pooled HikariCP source per database shared by all Spaces, the schema created once under a transaction-scoped advisory lock. Connection: `-Dinvestigations.db.url/.user/.password`, each falling back to `-Dinspecto.db.*`. **Fail closed:** an unknown backend value, `db` with no module, no or a non-PostgreSQL URL, an unreachable or vanished database are all `503 CAPABILITY_UNAVAILABLE` (a store failure with SQLSTATE class 08/28/53/57P, or a pool that cannot lend, is the same 503); nothing about a failure is cached, the next request retries, and it never falls back to the filesystem because that would fork the evidence. The sealed main log, its sets, the members and the references are append-only by trigger (UPDATE / DELETE / TRUNCATE refused with SQLSTATE 23000; a Draft's own rows stay deletable); a trigger and not a REVOKE because the application connects as the table owner. A Space that switches backend starts empty (no importer, D-IS10). Not shipped: the per-set size limit (needs an operator number) and the two-JVM race proof (S7).

**Two-JVM race proof (S7, 2026-10-05).** `InvestigationStoreTwoJvmRace` runs two real JVMs against one store (append chain, `decide` compare-and-set, mask key, promote). It found that the filesystem store was NOT safe across processes (append, `decide` and promote tore); `FsInvestigationStore` now takes an OS file lock `<investigation>/.store.lock` inside the Investigation monitor (order: monitor, file lock, Draft monitor). Creates, forks, references, case-link / Alert Rule binding and `createDraft`'s Space-wide cap remain per-JVM. The Postgres variant exists but has not yet run against a server. Detail: `docs/archived-documents/plans-archive/investigation-store-design.md` 13.6.

* **Investigation store on Postgres — proven 2026-10-05** — `PgInvestigationStore` (optional `inspecto-la-store-pg`, `-Dinvestigations.backend=db`) passed the shared 29-case contract (32 with its PG-only cases) and the two-JVM race harness (4/4: append, decide, mask key, promote) on a real PG 18.6; no race found. Run recipe: javadoc of `PgInvestigationStoreContractTest` (env `INSPECTO_TEST_PG_URL`, `-DforkCount=0`, session timezone via `MAVEN_OPTS`). Design: `archived-documents/plans-archive/investigation-store-design.md` 13.6.

## Consolidated as-built facts (moved from the retired plan docs, 2026-10-05)

Durable facts carried over when eight LA plan/design docs were archived into one roadmap ([`superpower/link-analysis-roadmap.md`](../../../superpower/link-analysis-roadmap.md)). Each bullet names its source. Some may overlap earlier sections; where they disagree, the code and the earlier section win. Items tagged [verify/check] were only grep-checked for prior coverage.

### Separation, D-7 Drafts and the embed view


Method: grep of OKF for key identifiers (spot-check only). Already in OKF, so not listed: Draft store, pins/TTL, hibernate, expiry, recover, `drafts/index.json`, STORE_BUSY / moveRetrying, DraftPromoteCost / DraftRebaseCost, the declined linear rebase, canonical-v1, the backlog rows.

1. `SnapshotStore.appendStep` delegates to a static `appendStepAt(dir, ...)`; a Draft appends via `DraftStore.appendStep`, which takes the log line back if the set file cannot be written (main log keeps its log-first order). Promote lands sets via `SnapshotStore.appendStepSharingSet` (hard link, byte copy where unsupported); that name is NOT in OKF. (d7 sec 4 D7-3, sec 6)
2. Promote reuses a set file only if its head is exactly `{"hash":"<h>","step":<n>,"workingSet":` AND the bytes to the closing brace SHA-256 to the sealed `workingSetHash` (operator rule "nothing unverified reaches main", 2026-10-03); promote is linear in set-file BYTES, not strictly in steps. (d7 sec 6) [verify against OKF ~l.1266]
3. Promote refuses a Draft containing an undo entry (a log compacted without renumbering is not the state shown); it must rebase first, which compacts it. (d7 sec 4 D7-5)
4. Four-eyes recorded call: a Draft cannot hold a sensitive expand (422 at append); sensitivity is judged at PROMOTE against thresholds then in force; approve re-checks the Draft log hash and main head (409 "deny and request again"); rebase re-reads without approval because a Draft's reads are private. (d7 sec 4 D7-5)
5. Windows gotcha: a directory `ATOMIC_MOVE` is denied while ANY handle is open inside it (antivirus / indexer on a just-written `header.json`; 4 of 50 forks failed in the bench). STORE_BUSY is in OKF; the cause statement is not. (d7 sec 6)
6. Real-state D-S5 bench at 10^8 edges: open p50 37 ms; 0.4 MB heap per Draft (50 Drafts ~ 20 MB); light p95 28 ms solo / with think time vs 815 ms closed loop (CPU saturation, 20 threads on 12 HW threads); saturated heavy limit moves light p95 28 -> 43 ms; `DraftConcurrencyBench` lives in `inspecto-la-api` (moved from `inspecto-la-storage`, which cannot see `DraftAdmission` / `DraftPromote`). (d7 sec 6; feasibility 7.10.3) [check OKF *Index scale measurements* for overlap]
7. `IndexStore.gc` runs under a per-directory pin lock (JVM monitor + `.pins.lock`); an unreadable `pins.json` THROWS and gc deletes nothing (fail closed); pinned append versions survive their parent's gc because they are per-file hard links; the `IndexReader.borrow` sibling eviction was a thrash not a correctness hazard, now pinned siblings are spared. (d7 sec 5 D7-2) [OKF l.1190 covers part]
8. Plan-vs-code corrections: access was never purely owner-only (LA-24 Case-member read, D-U7 approver); no Dataset version exists (`datasetVersion` always null); op log and Working Sets are JSON and the evaluator never touches DuckDB; the evaluator is TOTAL (op naming an absent id is a no-op, which is what makes fork/rebase evaluate). (d7 sec 2)
9. Draft role x route matrix: lead gets 403 writing another's Draft; revoked actor's Draft freezes (lead may read/discard); last lead cannot be revoked/demoted (422); owner is only the FIRST lead; legacy (no `members.jsonl`) approver = any `canApproveLinkExpansions` holder; 403 is raised only after R3 + PDP so a policy DENY still reads 404. (d7 sec 4, sec 9) [verify OKF *Investigation members*]
10. Discard deletes `log.jsonl` + `sets/` (sealed rows may be personal data) but keeps header + marker + per-step audit events (fingerprints/counts, never rows). (d7 sec 4)
11. Fork atomicity: staged in `drafts/.fork-*` (leading dot never matches `DRAFT_ID`), pins taken first and released on failure, one-per-member check + pin + rename under the Investigation's `drafts` lock; server-generated `draft-<uuid>` matched EXACTLY (422 otherwise); fork answers 201 + Location, not 202. (d7 sec 4)
12. Rebase facts: header REWRITTEN (not write-once) with `rebases[]`; steps carry `rebasedFrom`; optimistic commit takes main lock THEN Draft lock; `DraftRebase.verify` runs outside the main lock; rebase at the current head is legal (renews an expired pin); `IndexedRead.select(..., pinned)` / `IndexedExpand.attempt(..., pinned)` take `mappingHash -> version` and a no-longer-published pinned version reads as "no index" (flat Dataset answers). (d7 sec 4 D7-5)
13. SEP-02 decision: keep the hand-kept `AbsentGeoLinkRoutes.SURFACE` guarded by `GeoLinkAbsentSurfaceParityTest` (a drift incident is recorded in its header); a build-time generated surface descriptor was weighed and not chosen. (feasibility sec 4 D-0)
14. D-3 step-1 hash facts: DuckDB `hash()` is deterministic across connections/processes but not reproducible in Java; `md5_number_lower(x) % N` matches Java MD5 on 1004/1004 ids at ~16% slower build; only an equality on ONE key prunes row groups (frontier of 50 keys: IN 3.2 s, VALUES join 4.2 s, per-key UNION ALL 2.2 s). (feasibility 7.10.1) [likely in OKF *Index design record*; verify]
15. D-S4 traps: closed-graph assumption (a dangling edge leaks a phantom neighbour); multigraph rules (degree counts parallel edges and a self-loop twice; kCore/triangles/cliques collapse them); `hits` tie noise; `GraphAlgorithms.Edge` carries no weight so weighted functions take an edge-id map; an iterative mutant can hang the suite (parity tests need a timeout). OKF has only `label propagation` and `canonical-v1`. (feasibility 7.13)
16. SPA D-5 prep: `LA_FEATURES` is the only host token with a SAFE default (all off, never throws); `no-restricted-imports` ignores `ImportExpression`, hence the `no-restricted-syntax` selector set `laDynamicImportSelectors` + `la-dynamic-import-lint.test.mjs`; `<inspecto-la-host-slot>` (`LaHostSlotComponent`, `ngDoCheck` inputs, subscribed outputs) exists because `NgComponentOutlet` cannot apply inputs/outputs. (feasibility 7.12) [verify OKF *SPA separation record*]
17. Packaging trap: a static Maven profile module list must be hand-synced with `tools/bundle-modules.mjs` (PREVIEW precedent). (feasibility 1.4) [likely in `editions-model.md`]
18. Embed doc: design only, nothing as-built; its EMB-1..14 decisions are carried in sec-separation.md.

Items tagged [verify] were not fully checked; items 1, 3-5, 8, 10-13, 15 are the most likely true gaps.

### Investigation store


2. PG writers lock with `SELECT ... FOR NO KEY UPDATE`, not `FOR UPDATE` (deliberate deviation from D-IS4 wording): a Draft append FK takes `FOR KEY SHARE` on the Investigation row, so `FOR UPDATE` would deadlock promote vs Draft append. Lock order: Space row, Investigation row, Draft row; no advisory lock except the bootstrap one. Source: 13.4.
3. PG vs FS differences on purpose: `recoverDrafts` no-op; `logToken` = entry count (main) / `version:state` (Draft); promote seals a Draft set by server-side `INSERT ... SELECT` (D-IS12 a) not a hard link; `touchDraft` writes at most every 5 min; write to an Investigation with no header row = IOException. Source: 13.4.
4. PG schema: `la_space` (cap lock), `la_investigation` (header, `mask_key` bytea, `case_link`), `la_log`/`la_set` (`draft=''` is main), `la_member`, `la_reference` (unique inv,key), `la_alert_binding`, `la_pending`, `la_template`, `la_draft` (`version bigint`); schema name validated `[a-z_][a-z0-9_]{0,62}`; ids ordered `COLLATE "C"`. Source: 13.4, section 7.
5. Append-only triggers carry `WHEN (OLD.draft = '')`, SQLSTATE 23000, need PostgreSQL 14 (`CREATE OR REPLACE TRIGGER`); limit: a DBA can drop them. (OKF has the trigger-not-REVOKE reason only.) Source: 13.5.
6. Test recipe traps: env var `INSPECTO_TEST_PG_URL` (not -D), `-DforkCount=0`, driver in owning module, `MAVEN_OPTS=-Duser.timezone=Asia/Kolkata`; per-test SKIP without URL; contract runs on a 4-connection pool against 16-writer races. Source: 13.4, 13.5.
7. Operator-run mutation checks (FS + PG mutants 1-14) are still OWED and enumerated. Source: 13.2-13.5.
8. S0 measurements: ~291 B/entity per sealed set (10,000 = 2.9 MB; 100,000 = 29.1 MB); 10,000 entities x 2,000 steps = ~3,061 MB per Investigation; expand log line ~74 B/sealed row (5,000 rows = 370 KB); FS readLog p50 9.2 ms and prefixHash p50 16.8 ms at 2,000 steps / 5.95 MB; NUL is written as the escape `\u0000`. Source: 13.1.
9. FS semantics: `log()` returns committed lines only (torn last line ignored), keeping the relation cache key backend-neutral; one monitor map shared with `InvestigationRoutes.lock(Path)`; `untilWon` 20 attempts then 409 `CONFLICT_STALE_VERSION`; `decide` claims by `replacePending` CAS before the append and hands the claim back only if the act fails before any append; `promoting.json` allow-listed in `ImportLoaderInventoryTest` `NOT_CONFIG`. Source: 13.2, 13.3.
10. FS cross-process fix: `<investigation>/.store.lock` OS `FileChannel.lock`; order Investigation monitor, file lock, Draft monitor; Draft-scope appends take the Investigation monitor first; recovery waits for a live promote; before the fix 3 of 4 FS cases failed (append, decide, promote; mask key held). Source: 13.6.
11. Known race-only deviations: fork pins the index before D17/cap judgment (refused fork pins then unpins); promote/rebase 409 one message for two causes; expiry marker built under Draft monitor but idle time read just before. Source: 13.3.
12. Operator decisions 2026-10-05 (no per-set cap now; no boot-time refusal, `db` judged on first use per Space; process-global switch). Source: 13.5.
13. Out of seam: D-3 Parquet index, DuckDB-per-Draft files, pin ledger, snapshots/attachments, Dossier bundles; `drafts/index.json` and `.fork-*/.rebase-*/.old-*` disappear on PG; sweeps (hibernate idle 1 h, expire idle 30 d) run in-request, no scheduler. Source: sections 2, 5.
14. Packaging/wiring: staged `from: 'enterprise'` (Enterprise 19, Preview 19 modules); `package.ps1` launcher classpath and service-file check; Space id = directory above `config` in the write root, `default` for the legacy root. Source: 13.5.

### Entity model and live detection


Already covered there (skip): LD refusal codes, `standing` binding, `canViewAs`, `la.detect`, template list/disable/edit routes, typed node ids / `columnTypes`, Entity Lists + Identity resolution SPA sections. `okf/backend/control-plane/entity-lists.md` outlines the SHA-256 chain, `audit/entity-facts/` and `mask.key`; items marked [check] may already be there.

#### Entity Types
- Normaliser is a closed enum `default | digits | e164 | upper-trim`; Java and TS stay identical via `inspecto-ui/src/app/inspecto/graph/entity-normaliser-parity.fixture.json` (fed to `entity-key.spec.ts` and `EntityTypesTest`). "Whitespace" means the JavaScript `\s` set on both sides (Java `\s`, `trim()`, `strip()` miss NBSP); final sigma folds to sigma on both sides. (entity design 4.1, 4.3.2)
- Settings validation (422, never clamped): id `^[a-z][a-z0-9_]{0,31}$` and unique; id `entity` reserved; label non-blank; a classification claimed by at most one type; at most 64 types; a stated list replaces the defaults and may not be empty. Wire `entityTypes` / `entityTypesInForce`. (4.1, 8.2)

#### Entity List wire contract (4.3.1, 4.3.2)
- `GET /entity-lists` -> `{lists:[summary], headSeq, headHash}`; summary `{id,title,purpose,normaliser,entityType,size,retired,createdAt,createdBy,lastSeq}`. `GET /entity-lists/{id}?at=<seq>` -> summary + `members` (sorted, masked per `maskingMode`) + `atSeq` + `headHash` (hash of the fact AT `atSeq`); `at` past head 422, list absent at `at` 404.
- `POST /entity-lists` `{id?,title,purpose,entityType,reason}` -> 201; purpose `allow|block|watch|exclusion`; id `^[a-z0-9][a-z0-9_-]{0,63}$`; an ever-used id (retired too) 409. `POST .../members` `{add?,remove?,reason}` -> 200 with `changed`; value empty after normalising, or in both sets, 422; at most 5 000 values per call; a no-op writes nothing; retired list 409; list whose type left force 409. `POST .../retire` (already retired 409).
- Every write: non-blank `reason` (422), `canManageIncidents`, no write root 503, unknown list 404; bounds title <= 200, reason <= 1 000, value <= 512. Reads need only Space access but also answer 503 with no write root. Audit `ENTITY_LIST_CHANGED {listId,kind,added,removed,seq}` (counts, never keys).

#### Fact log and chain verification
- Fact file: `<write root>/audit/entity-facts/<12-digit seq>.json`, fields `seq, at, actor, reason, kind, listId?, ..., prevHash`; published by temp file + fsync + hard link (atomic no-replace on every OS; `AtomicFiles` always replaces so is not used); appends under the per-directory lock, responses sent after leaving it. Kinds: `list.created` (seals `normaliser`; absent reads as `default`), `list.member.added/removed` (sorted changed `keys[]`), `list.retired`, `identity.asserted {a,b,via}`, `identity.retracted {assertionSeq}` (no `listId`). (4.2, 4.3.2, 8.2)
- The whole chain is re-verified on every read; a break answers `INTEGRITY_VIOLATION` (500); no cache. A deleted LAST fact is undetectable from the log alone (only a head hash cited elsewhere, e.g. a Dossier). Orphan `.fact-*.tmp` files are ignored and never swept. [check]
- Masking-key caveat: list members and identity keys mask with the per-Space `entity-facts/mask.key`, so a token differs from an Investigation's token (its own `mask.key`) for the same value. `excludeBy` compares raw keys server-side, unaffected. (4.3.2) [check]

#### Entity list ops in Investigations (4.4.2)
- `excludeBy`/`seedBy` request `{op, listId, reason?}`; refusal order 404, retired 409, type not in force 409, > 5 000 members 422; sealed `list {listId, atSeq, headHash, entityType, normaliser, purpose, masked, members[]}`. `excludeBy` records removals in `excluded` and remembers keys in `excludedKeys [{normaliser,key,step}]` (hashed only when non-empty); a remembered key does not block an entity already admitted. `seedBy` reads `SELECT DISTINCT CAST(col AS VARCHAR)` per bound column, cap `SEED_BY_DISTINCT_CAP` (setting `seed_by_distinct_cap`, 1..100 000, default 20 000), 422 above it, sealed as `read{ids[],fingerprint,...}`. Step result `list {..., removed|seeded, unmatched[]}`.
- Templates carry `{op, step, listId}` only and re-resolve at the head on instantiate; fork carries a list op's seal verbatim.

#### Identity resolution routes (8.2)
- `GET /inv/entity-identities?at=`; `GET /inv/entity-identities/group?key=&at=` (exact, no normalising; key read via `getRawQuery()` because `%2B` otherwise becomes a space); `POST /inv/entity-identities` `{a,b,reason}` -> 201; `POST .../{seq}/retract`; `POST .../import`. Group `{id (smallest member key), members[], assertions[{seq,a,b,via,actor,at,reason}]}`; a redundant assertion is accepted as extra evidence; cross-type allowed. Both reads need `canManageIncidents` (membership-oracle probe fixed); Entity List reads stay Space-open. Event `ENTITY_IDENTITY_CHANGED {kind,seq,assertionSeq,groupSize}`.
- Import `{dataset,aCol,bCol,aType?,bType?,reason,limit?}` -> 201 (200 when nothing new) with counts only (`imported`, `skipped{alreadyAsserted,duplicate,empty,self}`, `rowsRead`, `truncated`, `fingerprint`, `via`, `fences`); `limit` 1..10 000 (default 1 000), 10 s timeout; `via: "dataset:<id>@<fingerprint>"`; idempotent (a retracted pair is never re-asserted); four-eyes from the D-U7 thresholds, not the `entity-list` Pending Change.

#### `resolve` op and merged traversal (8.3)
- `resolve {atSeq?}`: seal `{atSeq, atHash, headSeq, headHash, groups[], types{}, columnTypes{}}`; a VIEW, not a rewrite (traversal, exclusion, counts stay raw); `toMap` adds `resolution` and per-entity `resolvedTo` only when in force (older hashes unchanged); measure `identities = countDistinct(identity)`; `/log` shows `resolutionSummary`; fork keeps the seal, template re-seals at the head. 422: `ids` given, bad/past-head `atSeq`, > 5 000 member keys.
- `merged: true` on `expand` / `exclude` (422 without a resolve in force; hashed only when true). Merged expand widens the frontier to group members via a DISTINCT read capped by `merged_distinct_cap` (default 20 000, 422 above), frontier cap 1 000, fences count the combined per-group fan-out. Merged exclude seals groups, remembers `excludedGroups`; an untyped value is a member only when UNAMBIGUOUS (`rawMemberKeys`); the log/Dossier line states what left, what `keep` protected and what was only blocked. Fork re-seals; a merged op re-ordered before its resolve is 422.
- Pinning tests: `ControlApiInvestigationResolveTest`, `ControlApiInvestigationMergedTraversalTest`, `ControlApiEntityIdentityTest`, `ControlApiEntityIdentityImportTest`.

#### Live detection details not in OKF (live design 8, 9)
- Decision register D-LD1..D-LD18 and status (only D-LD1 operator-decided); D-LD8 `canConfigureAccess` never honoured; D-LD9 only tightening refuses; D-LD16 edit drops `standing`, write order component, binding, re-arm. [only a pointer exists today]
- `GET /inv/investigation-templates` -> `{templates:[{id,title,createdAt,dataset,investigation,parameters,ops}]}` (counts; a Personal host lists all); disable response `{rule,investigation,enabled:false,wasEnabled}`; PUT adds `replaced:true`, `standingDetection:"disabled: ..."`, 404 not bound, 409 armed rule edited out of band. Events `LINK_STANDING_DETECTION_DISABLED`, `LINK_INVESTIGATION_ALERT_RULE_EDITED {rule,investigationId,standingDetectionDropped}`.
- `la.detect` is authored as `config/jobs/<name>.toon` `job:{name,type:la.detect,schedule:"<cron>"}`; Signal `la.detect.completed {job,fired,rules[]}`; `AlertAccess.evaluateInvestigationRules()` default = the full sweep. `RowScope.visibleAs` uses an inert package-private `SweepExchange`. Enable reads the ARMED rule (a stored copy hashes differently after the TOON number round-trip).
- Gates cleared per new route: `CapabilityManifest`, `AbsentGeoLinkRoutes.SURFACE`, `docs/api/openapi-v1.json`, `compliance/evidence/route-gating.md`, auth-gate baseline; `JobWritersTest` row for the PUT only; `ConfigWriteFunnelTest.WRITERS` / `DecisionRuleWritersTest` list `sealList` / `sealResolution` as fact-log readers (operator-approved 2026-09-30).
