---
type: Spec
title: Link Analysis — Functional Specification (`INV-1` / `CP-09`)
description: What Link Analysis is, what an analyst can do with it, and what it must do to be complete. Every clause is tagged SHIPPED / PARTIAL / NOT BUILT against a code-grounded audit, so a reader can tell at a glance what is real.
resource: inspecto-geo-link/src/main/java/com/gamma/geolink/InvRoutes.java
tags: [link-analysis, graph, investigation, spec, studio, geo-link]
timestamp: 2026-09-20T00:00:00Z
---

# Link Analysis — Functional Specification (`INV-1` / `CP-09`)

Link Analysis is Inspecto Studio's visual investigation surface: an analyst projects a graph out of
data the platform already holds, navigates it, runs graph algorithms over it, and keeps what they
found. It answers *"what is connected to what, how strongly, and through which path"* over Datasets,
lineage, pipelines and the component model.

This document specifies the **target state**. Its companion,
[`link-analysis-advancement-plan.md`](link-analysis-advancement-plan.md), is the engineering
roadmap that builds toward it.

## 0. How to read this — the status tags are the point

Every capability below carries one of three tags. A reader who trusts nothing else should still be
able to tell what exists.

| Tag | Means |
|---|---|
| ✅ **SHIPPED** | Built, reachable by an analyst, and covered by a test. Cited to `file:line`. |
| 🟡 **PARTIAL** | Something real exists but does not meet the clause as written. The gap is stated, not implied. |
| ⬜ **NOT BUILT** | No implementation. Grounding confirmed absence rather than failing to find it. |

⚠ **Grounded 2026-09-20** by two independent code reads (backend and SPA), deliberately **not** from
the advancement plan's own §1 audit — plan claims in this repo go stale fast, and four of that
document's assertions were wrong (recorded in §8 below). ⛔ When this spec and any plan disagree,
re-ground against code; do not assume either document is current.

## 1. Scope and edition

Link Analysis is the **graph** half of `CP-09`; `GeoRoutes` is the map half. Both live in the
optional `inspecto-geo-link` module.

- ✅ **SHIPPED — Professional and Enterprise only.** Built solely under the `edition-professional` /
  `edition-enterprise` Maven profiles (`inspecto-geo-link/pom.xml:17-35`). A default Personal build
  never compiles it.
- ✅ **SHIPPED — Personal degrades honestly, not silently.** `AbsentGeoLinkRoutes` answers `503 not
  installed`, and `features.geoLink` is reported through `BootstrapRoutes` / `CapabilityManifest` so
  the SPA can hide rather than offer-then-fail. Pinned by `NoGeoLinkShipsInThePersonalBuildTest`.

## 2. The model

### 2.1 What a graph is made of

- ✅ **SHIPPED — an edge is a folded aggregate, not a row.** The server projects
  `{source, target, kind?, count, attrs?}` by `GROUP BY` over a Dataset relation, so an edge carries
  a **`count`** of how many underlying rows produced it (`InvRoutes.java:170-241`). Weight is
  therefore intrinsic: two entities linked 400 times are one edge of `count: 400`.
- ✅ **SHIPPED — nodes are implied by edge endpoints.** There is no node table and no node identity
  service; a node *is* a distinct `source`/`target` value. ⚠ Consequence to keep in view: a node has
  no attributes of its own at the API layer, only those an edge carries.
- ⬜ **NOT BUILT — no persisted graph.** Nothing is stored or cached server-side; every call re-runs
  the aggregation (`InvRoutes.java:170`). This is a deliberate design position today, not an
  oversight — but it bounds everything in §6.
- ⬜ **NOT BUILT — no node identity resolution.** Two spellings of the same real-world entity are two
  nodes. Entity resolution is out of scope for this spec and would need its own.

### 2.2 Graph sources (the four planes)

An analyst chooses which plane to project. ✅ **SHIPPED** — all four, pluggable behind a
`GraphSource` interface (`graph-sources.ts:20-60`):

| Plane | What the graph is | Backed by |
|---|---|---|
| **Lineage / catalog** | how data objects derive from one another | `CatalogService.graph()` → `/catalog/graph` |
| **Artifact / component model** | how authored components reference each other | `ComponentsService` + `deriveComponentGraph` |
| **Pipeline** | a pipeline's own node/edge structure | `PipelinesService` + `toPipelineG6Data` |
| **Dataset entity projection** | entities linked by shared column values | `/inv/projection` (the `InvRoutes` path) |

⚠ **Precision that the advancement plan gets wrong:** only the **dataset entity projection** plane is
single-Dataset. The surface as a whole already spans four planes. A gap phrased as "single-dataset
projection bottleneck" must name the *endpoint*, not the *feature*.

### 2.3 Relationship discovery

- ✅ **SHIPPED — `GET /inv/schema/relationships`** infers candidate joins across Datasets by
  foreign-key naming convention (`InvRoutes.java:83-137`), so an analyst is offered plausible
  link columns instead of typing them blind.
- ✅ **SHIPPED — best-effort by design.** An unprobeable Dataset is skipped, never fatal
  (`InvRoutes.java:97-99`). ⚠ That means an empty result is ambiguous: *no relationships* and *could
  not read anything* look identical to the caller. Stated as an accepted limitation.

## 3. What an analyst can do

### 3.1 Acquire a graph

- ✅ **SHIPPED — project from a Dataset.** `POST /inv/projection` with
  `{dataset, sourceCol, targetCol, linkKindCol?, attrCols?, limit?}` (`InvRoutes.java:67`).
- ✅ **SHIPPED — expand one hop.** `POST /inv/projection/neighbors` adds a required `value` and
  returns that node's neighbourhood (`InvRoutes.java:68`), wired to the canvas's per-node **expand**
  action so the analyst grows a graph outward rather than loading it whole.
- ✅ **SHIPPED — choose roots, depth, direction and kind filters** in the query panel before running.
- ⬜ **NOT BUILT — multi-Dataset projection in one call.** An analyst cannot ask for a graph spanning
  two Datasets; they must project one and expand.
- ⬜ **NOT BUILT — server-side multi-hop traversal.** There is no recursive path expansion endpoint;
  depth beyond one hop is achieved by repeated client-driven `neighbors` calls.

### 3.2 Navigate

✅ **SHIPPED**, all on the canvas (`graph-view.component.ts`, AntV G6 v5):

- pan, zoom, drag nodes (`behaviors: ['drag-canvas','zoom-canvas','drag-element']`, `:378`)
- switch layout (layered/dagre default plus alternates)
- colour and shape nodes by kind
- expand a node one further hop
- search nodes; filter by node kind and edge kind
- collapse and re-expand a branch (`collapsedRoots`)
- ✅ **SHIPPED — a timeline slider** filters edges by a date column in `attrs`
  (`link-analysis.component.ts:294-312`), which is how an analyst sees a network evolve rather than
  as one flat blob.

### 3.3 Analyse

✅ **SHIPPED — 13 tool groups fronting 27 wired algorithms** (`link-analysis-toolbox.component.ts`,
implementations in `inspecto/graph/graph-analysis.ts`). ⚠ The toolbox is **one accordion, one group
open at a time** — not tabs. Groups: shortest path · all paths · explain node · centrality ·
communities · connected components · cycles · cut points · cohesive groups · similarity &
prediction · flow & backbone · suspicion score · pattern match.

The 27: `shortestPath`, `weightedShortestPath`, `allPaths`, `neighborhood`, `explainNode`,
`degreeCentrality`, `betweennessCentrality`, `closenessCentrality`, `eigenvectorCentrality`,
`katzCentrality`, `pageRank`, `hits`, `detectCommunities`, `louvainCommunities`,
`connectedComponents`, `findCycles`, `articulationPoints`, `bridges`, `kCore`, `triangleCount`,
`cliques`, `maxFlow`, `maximumSpanningForest`, `jaccardSimilarity`, `linkPrediction`,
`suspicionScore`, `matchPattern`.

⚠ **All run in the browser, on the main thread, as pure functions.** The backend performs no graph
analysis whatsoever — only SQL fold and filter (`InvRoutes.java:36-39` states this is deliberate).

- 🟡 **PARTIAL — pattern matching.** `matchPattern` plus loadable **pattern packs** (built-in and
  Space-authored, merged not replaced) ship today. What does **not** exist is a declarative motif
  engine with branching patterns; today's packs are structurally limited. The gap is expressiveness,
  not absence.

### 3.4 Keep and share what was found

- ✅ **SHIPPED — saved views.** A named view persists query, source, layout and display options via
  the generic component store as the `link-analysis-view` kind (`link-analysis.service.ts:11-13`),
  surviving reload.
  ⚠ **What a saved view does NOT capture:** live filter state, timeline position and branch-collapse
  state are session-only unless folded into the saved view.
- ✅ **SHIPPED — share via Exchange** (gated on `canShare`) and **comment** on a view.
- ✅ **SHIPPED — export** to JSON, PNG, SVG and GraphML, all wired
  (`link-analysis.component.ts:858-896`). GraphML matters: it is the interchange format that lets an
  analyst take a graph to external tooling.

### 3.5 Correlate with geography

- 🟡 **PARTIAL — a one-way bridge exists.** Geo's co-location dialog hands a set of co-located
  entities into `/studio/link-analysis` via the dataset-backed source
  (`colocation-graph.dialog.ts:8-10`).
- ⬜ **NOT BUILT — synchronised dual-pane brushing.** Selecting on the map does not highlight on the
  graph, or the reverse; there is no live two-way selection channel. ⚠ The advancement plan calls
  the two "decoupled", which overstates it — the handoff is real, the *synchronisation* is what is
  missing.

### 3.6 Attach findings to an investigation

- ⬜ **NOT BUILT — confirmed absent, not merely unfound.** A grep of `inspecto-geo-link` for
  `ObjectService` / `OperationalObject` returns nothing. No projection, expansion or saved view can
  be attached to a Case, Incident or operational object. The only persistence is an opaque JSON
  component.
- ⬜ **NOT BUILT — evidence snapshotting.** Nothing freezes a graph as it appeared at a moment, so a
  view re-opened after the underlying Dataset changed silently shows a different graph. ⚠ For an
  investigation tool this is the most consequential gap in this document: **a saved view is not
  evidence.**
- ⬜ **NOT BUILT — dossier export.** No automated case report assembling graphs, findings and notes.

## 4. Non-functional requirements

### 4.1 Limits, as built

| Limit | Value | Where | Behaviour at the edge |
|---|---|---|---|
| Server rows per projection | `DEFAULT_LIMIT` 2000, `MAX_LIMIT` 20000 | `InvRoutes.java:62-63,178-179` | clamps, sets `truncated: true` |
| Client analysis node cap | `ANALYSIS_NODE_CAP` 2000 | `graph-analysis.ts:11` | ⚠ **throws** on super-linear algorithms |
| Render | none | `graph-view.component.ts` | no virtualisation or level-of-detail |

🔴 **The client cap is a refusal, not a slowdown.** Betweenness and community detection *throw* above
2000 nodes (`graph-analysis.ts:204-205,324-325`). This is a **hard functional ceiling** on the size of
investigation the product supports — a materially different thing from "the UI gets slow", which is
how the advancement plan frames it. Any fix that only moves work to a worker thread removes the
freeze but **not** the ceiling.

### 4.2 Required of the target state

- **Analysis must not block the UI.** No algorithm may freeze interaction; progress must be
  observable and cancellable. ⬜ NOT BUILT.
- **The ceiling must be raised and stated.** Whatever the supported graph size is, it must be a
  published number the UI enforces gracefully — not an exception. ⬜ NOT BUILT.
- **Truncation must always be visible.** ✅ SHIPPED server-side (`truncated`); ⚠ verify the SPA
  surfaces it — not established by this grounding.

### 4.3 Safety

- ✅ **SHIPPED — no free-text SQL.** Column identifiers must match `SAFE_IDENT` (`InvRoutes.java:61`)
  and values bind as JDBC `?` parameters (`:204-210`). A quote-safety regression is pinned by test.
- ✅ **SHIPPED — fails closed.** 503 without a write root, 404 unknown Dataset, 422 bad identifier.

## 5. Acceptance criteria

Falsifiable, in the repo's house style — each states what would have to be observed.

1. **Projection is faithful.** For a Dataset with known duplicate pairs, edge `count` equals the row
   count of that pair. ✅ covered (`ControlApiInvProjectionTest`, 10 tests).
2. **Truncation is never silent.** A projection exceeding the limit returns `truncated: true` AND the
   analyst sees it. ✅ backend · ⚠ SPA half unverified.
3. **Personal offers nothing it cannot do.** With the module absent, no Link Analysis entry point is
   reachable and probing returns 503. ✅ pinned.
4. **Analysis never silently lies.** An algorithm that cannot run on a graph must say so explicitly.
   ✅ today by throwing — which satisfies "not silent" but fails "graceful"; see §4.2.
5. **A saved view reopens identically.** ⚠ Only true while the underlying Dataset is unchanged; see
   §3.6. Under the target state this becomes: a *snapshot* reopens identically, always.
6. **An analyst can get a graph out.** ✅ four export formats, GraphML for interchange.
7. **No projection can be coerced into arbitrary SQL.** ✅ pinned by identifier regex + bind params.

## 6. Structural consequences worth stating plainly

1. **Re-projection on every call** (§2.1) means cost scales with usage, not with graph size, and two
   analysts on one Dataset pay twice. It also makes §3.6's snapshotting impossible without a new
   persistence seam.
2. **All analysis is client-side** (§3.3) means the supported graph size is bounded by one browser
   tab. Server-side traversal is the only route past it.
3. **Nodes have no identity** (§2.1) means centrality and community results are only as meaningful as
   the raw column values. ⚠ An analyst can be confidently wrong here, and nothing in the product
   warns them.

## 7. Open decisions

Owed before the corresponding work starts. ⛔ None should be answered by an implementer in passing.

1. **Is a saved view evidence?** If yes, snapshotting is mandatory and needs a store; if no, say so in
   the UI so nobody treats it as such. Blocks §3.6.
2. **Where does traversal run?** Recursive CTE in DuckDB, or a worker plus a raised cap? Decides
   whether the ceiling problem is solved server-side or client-side. Blocks §4.2.
3. **What is the supported graph size?** A number must be chosen and enforced. Blocks §4.2.
4. **Does Link Analysis get a first-class node model,** or stay value-projected? Blocks §2.1's
   identity gap and any attribute-rich analysis.

## 8. Corrections to the advancement plan, recorded

Grounding refuted four claims in
[`link-analysis-advancement-plan.md`](link-analysis-advancement-plan.md). Kept here so the next
reader does not re-derive them:

1. **"25+ pure algorithms across 13 toolbox tabs"** — the real split is **27 wired algorithm
   functions** (of 34 exports; the rest are plumbing) across **13 accordion groups**. They are not
   tabs, and one group can front several metrics via a dropdown.
2. **GAP 1 "main thread starvation"** — understates it. Above 2000 nodes the affected algorithms
   **throw**. A worker fixes responsiveness and leaves the ceiling.
3. **GAP 2 "single-dataset projection"** — true of the `/inv/projection` endpoint, not of the
   feature: four source planes ship.
4. **GAP 6 "decoupled geospatial and link topologies"** — a one-way Geo→Link handoff already exists;
   what is missing is two-way synchronisation.

## References

- Backend: `inspecto-geo-link/src/main/java/com/gamma/geolink/InvRoutes.java`
- SPA: `inspecto-ui/src/app/modules/admin/studio/link-analysis/`,
  `inspecto-ui/src/app/inspecto/graph/graph-analysis.ts`
- Edition gating: [`../EDITIONS.md`](../EDITIONS.md) §CP-09
- Roadmap: [`link-analysis-advancement-plan.md`](link-analysis-advancement-plan.md)
