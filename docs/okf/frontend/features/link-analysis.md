---
type: Feature
title: Link Analysis
description: The graph investigation studio — Entity Projection over Datasets rendered on the shared G6 host, with layout/algorithm toolboxes and saved Link-Analysis Views.
resource: inspecto-ui/src/app/modules/admin/studio/link-analysis/
tags: [feature, studio, graph, entity, link, g6, investigation]
timestamp: 2026-07-07T00:00:00Z
---

# Link Analysis

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
  (`glossary_lookup` is non-mutating). Remaining V2 (BACKLOG): **(c)** domain-seeded pattern packs owned by a
  dedicated system Space (D16), and **(d)'s authoring half** — open, and *not* a mechanical adoption: nothing
  drafts a **projection mapping** (column→Entity choices over a Dataset's real columns) today, and that
  mapping is the pane's actual authoring act, so which L1 tool backs it is still a call.
* **Investigation pivot** (ui-design-review R8, 2026-07-20) — a node resolving an `objectRef` offers
  "View on map" (pivots to Geo Map Analysis with the same record); see
  [Investigation Pivot](investigation-pivot.md) for the shared contract.
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

    **SHIPPED end-to-end 2026-07-25** — backend (`56ca3559`), UI half same day. As built:
    `ObjectNote` carries a **`targetKind`** — ⚠ *not* its pre-existing `kind`, which is `NoteKind`
    (COMMENT/ATTACHMENT) and an orthogonal axis; the two must never be conflated. The vocabulary is
    `NoteTargets` = `"object"` + `ComponentStore.WRITABLE_TYPES`, which already contains
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
  * **D16 pattern packs — a dedicated system Space owns the domain-seeded packs**, not the
    space-template-gallery seeding path. Rationale: packs are installation-wide reference content, and seeding
    them into user Spaces would fork them per Space, so a fix to a shipped pattern could never reach the
    copies. A reserved system Space keeps one authoritative copy that every Space reads.

Design (archived):
[`link-analysis-and-graphsource.md`](../../../archived-documents/plans-archive/link-analysis-and-graphsource.md)
§7 (schema-relationship model, now shipped) ·
plans: [`link-analysis-studio-plan.md`](../../../archived-documents/plans-archive/link-analysis-studio-plan.md)
(§6–7, V1 now fully shipped; V2+ remains open backlog),
[`link-analysis-toolboxes-plan.md`](../../../archived-documents/plans-archive/link-analysis-toolboxes-plan.md).
