---
type: Feature
title: Link Analysis
description: The graph investigation studio — Entity Projection over Datasets rendered on the shared G6 host, with layout/algorithm toolboxes and saved Link-Analysis Views.
resource: inspecto-ui/src/app/modules/admin/studio/link-analysis/
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
  framework-free `inspecto/graph/graph-analysis.ts` library (the extension seam — a new algorithm is a
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
  shared `inspecto/investigation` lib; when its source is `entity-projection` it is renderable as a
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

## Grounded limits and consequences (code read 2026-09-20 / 2026-09-22)

Durable as-built facts distilled from the archived `link-analysis-spec.md`. Open work against them is
tracked in ONE place: [`link-analysis-backlog-plan.md`](../../../superpower/link-analysis-backlog-plan.md).

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
  ⛔ **"Create a new Case" was deliberately NOT built here.** `POST /objects` refuses a body without at
  least one entry in `links` — for EVERY object type, measured 2026-09-22, so in a space with no objects
  none can be created through the API at all — and the UI states it as a product decision (2026-07-22,
  `object-create.dialog.ts:224`): *a case CONTAINS its members*. Link Analysis holds graph **nodes**,
  which are value-projected entity ids and not operational objects, so there is no legal link target on
  this screen. Creating one anyway would also mean a durable Case with a session-only attachment — an
  empty Case that looks like it holds evidence. Tracked as `LA-CASE-CREATE-IN-PLACE-1`; revisit once the
  SPA is wired to the sealed snapshot store.
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
  ⚠ The SPA does not call it yet — wiring is a follow-up.

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

Design (archived): [`link-analysis-and-graphsource.md`](../../../archived-documents/plans-archive/link-analysis-and-graphsource.md)
· [`link-analysis-projection-authoring-plan.md`](../../../archived-documents/plans-archive/link-analysis-projection-authoring-plan.md)
§7 (schema-relationship model, now shipped) ·
plans: [`link-analysis-studio-plan.md`](../../../archived-documents/plans-archive/link-analysis-studio-plan.md)
(§6–7, V1 now fully shipped; V2+ remains open backlog),
[`link-analysis-toolboxes-plan.md`](../../../archived-documents/plans-archive/link-analysis-toolboxes-plan.md).
