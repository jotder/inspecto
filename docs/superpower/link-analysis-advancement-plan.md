---
type: Plan
title: Link Analysis Advancement Plan — Scalable Graph Engine, Web Worker Offload, and Forensic Case Integration
description: 3-phase engineering roadmap resolving the 7 architectural gaps of Link Analysis (INV-1 / CP-09), spanning multi-dataset projections, DuckDB recursive CTE traversal, Web Worker algorithm offloading, branching pattern motifs, and Case evidence snapshotting.
resource: inspecto-geo-link/src/main/java/com/gamma/geolink/InvRoutes.java
tags: [link-analysis, graph-engine, duckdb, web-worker, forensic-cases, studio, roadmap]
timestamp: 2026-09-17T00:00:00Z
---

# Link Analysis Advancement Plan (`INV-1` / `CP-09`)

Link Analysis is the primary visual and computational investigation environment in Inspecto Studio for discovering hidden relationships, entity clusters, money flows, and communication topologies. While the existing implementation provides rich client-side analytics (27 wired algorithms across 13 toolbox groups) and foundational projection routes, significant architectural gaps remain between the initial design plans and production requirements for enterprise-scale forensic investigations.

This plan details the grounded state of Link Analysis, enumerates the 7 core architectural gaps, and establishes a phased delivery roadmap across Sprints 9–11.

⇒ **What this plan builds toward is specified in [`link-analysis-spec.md`](link-analysis-spec.md)**
(functional specification, every clause tagged SHIPPED / PARTIAL / NOT BUILT). Read the spec for
*what the feature must do*; read this for *how and in what order*.

⇒ **What the target state looks like** is sketched in
[`link-analysis-ui-mockup.html`](link-analysis-ui-mockup.html) (2026-09-20, dummy data) — one scenario tab
per work item S1.1–S3.2; a design reference for the SPA changes, not product code.

---

## 1. Grounding & As-Built Audit

🔴 **RE-GROUNDED 2026-09-20 by two independent code reads (backend + SPA), and the 2026-09-17 table
below was WRONG IN FIVE PLACES.** The corrections are listed here rather than silently applied,
because three of them change what the roadmap should build:

1. ⛔ **The toolbox tab list was largely invented.** It named `Structural`, `Filtering`,
   `Clustering`, `Temporal`, `Geospatial`, `Timeline`, **`Case Board`** and `Metrics`. **None of
   those exist.** The real 13 accordion groups are: shortest path · all paths · explain node ·
   centrality · communities · connected components · cycles · cut points · cohesive groups ·
   similarity & prediction · flow & backbone · suspicion score · pattern match
   (`link-analysis-toolbox.component.ts:145-158`). 🔴 **A `Case Board` tab implies Case integration
   already has a UI surface. It does not** — and GAP 5 below is precisely about that absence, so the
   table contradicted the gap it sits above.
2. ⛔ **They are not tabs.** One accordion, one group open at a time — not `mat-tab`. One group can
   front several metrics through a dropdown, which is why "13 tabs" and "27 algorithms" are not in
   tension.
3. 🔴 **"Freeze the browser DOM on graphs > 2,000 nodes" understates the defect.** Above
   `ANALYSIS_NODE_CAP = 2000` the super-linear algorithms **throw** (`graph-analysis.ts:11,204-205,
   324-325`). It is a hard functional ceiling, not a slowdown — **so a Web Worker alone (S1.1) fixes
   responsiveness and leaves the ceiling exactly where it is.** See the spec §4.1.
4. ⚠ **"Single dataset projection" is true of the `/inv/projection` endpoint, not of the feature** —
   four graph source planes ship (lineage/catalog, artifact/component-model, pipeline, dataset entity
   projection; `graph-sources.ts:20-60`). Scope S1.2 to the endpoint.
5. ⚠ **Drifted citation:** schema-relationship inference is `InvRoutes.java:83-137`, not `:188-254`.

⚠ Also worth stating, because the old table did not: **Geo and Link are not fully decoupled** — a
one-way Geo→Link handoff already exists (`colocation-graph.dialog.ts:8-10`). GAP 6 is about
*synchronisation*, not connection.

The corrected baseline:

| Dimension | Planned / Documented | Shipped As-Built | Status & Grounding |
|---|---|---|---|
| **Projection API** | Ad-hoc graph generation across arbitrary datasets (`link-analysis-and-graphsource.md`) | Single dataset projection via `POST /inv/projection` and 1-hop neighbor expansion via `POST /inv/projection/neighbors` | **PARTIALLY DELIVERED** — `inspecto-geo-link/.../InvRoutes.java`. Projection operates on one dataset at a time; cannot perform multi-dataset cross-joins in a single pass. |
| **Relationship Metadata** | Schema-driven foreign key / edge discovery (`link-analysis-toolboxes-plan.md`) | `GET /inv/schema/relationships` introspects dataset metadata and suggests potential node/edge mappings | **DELIVERED** — `InvRoutes.java:188-254`. Suggests ID pairs based on column naming conventions and foreign references. |
| **Graph Algorithms** | Full graph metrics, centrality, community detection, pathfinding (`link-analysis-studio-plan.md`) | 25+ pure algorithms: Degree, Betweenness, Closeness, PageRank, Louvain modularity, Connected Components, Dijkstra, Cycle detection, Articulation Points | **DELIVERED** — `inspecto-ui/.../graph/graph-analysis.ts` (1,348 lines of pure TypeScript). |
| **Toolbox groups** | 13 specialized analysis toolboxes (`link-analysis-toolboxes-plan.md`) | 13 accordion groups (⚠ NOT tabs, and ⛔ no `Case Board`): shortest path, all paths, explain node, centrality, communities, connected components, cycles, cut points, cohesive groups, similarity & prediction, flow & backbone, suspicion score, pattern match | **DELIVERED** — `link-analysis-toolbox.component.ts:145-158`. Every group is signal-backed and reachable; none is a stub. |
| **Pattern Packs** | Pre-packaged forensic query templates (`link-analysis-pattern-packs-plan.md`) | Hardcoded client-side structural filters (rings, star hubs, bridges) | **INCOMPLETE** — Only evaluates linear topologies client-side; no declarative multi-branch query engine or temporal sequence motifs. |
| **Execution Architecture** | Responsive interactive exploration on 50,000+ nodes (`link-analysis-studio-plan.md`) | All algorithms run on the browser main thread; the backend does **no** graph analysis at all, only SQL fold/filter (`InvRoutes.java:36-39`, deliberate) | 🔴 **UNSATISFACTORY, and worse than "freezes"** — above `ANALYSIS_NODE_CAP = 2000` the super-linear algorithms **THROW** (`graph-analysis.ts:11,204-205,324-325`). A hard ceiling on supported investigation size, not a responsiveness problem. ⛔ A worker alone does not lift it. |
| **Case Evidence Integration** | Save graph substructures directly into Cases (`docs/okf/capabilities/studio/studio.md`) | Nothing. A grep of `inspecto-geo-link` for `ObjectService`/`OperationalObject` returns **zero** hits | 🔴 **UNSATISFACTORY — confirmed ABSENT, not merely incomplete.** The only persistence is an opaque `link-analysis-view` JSON component with no Case/Incident linkage. ⚠ And because projections re-run on every call, **a saved view is not evidence**: re-opening it after the Dataset changes silently shows a different graph. |

---

## 2. The 7 Architectural Gaps

### GAP 1: Main Thread Starvation on Large Graph Computations
All centrality, pathfinding, and community detection routines in `graph-analysis.ts` execute on the browser's main UI thread. For graphs exceeding 2,500 nodes and 10,000 edges, algorithms such as Brandes' betweenness centrality ($O(V \cdot E)$) and Louvain modularity optimization induce UI freezes lasting between 4 and 18 seconds, dropping frames and triggering browser unresponsive-script warnings.

### GAP 2: In-Memory / Single-Dataset Projection Bottleneck
`InvRoutes.java` projects graphs from a single relational dataset using DuckDB queries (`SELECT <source>, <target>, ... FROM <dataset>`). If an investigation requires linking people across a `citizens` dataset, bank transactions across a `wire_transfers` dataset, and company registrations across a `corporate_registry` dataset, the analyst must manually run multiple independent projections and stitch them client-side. Furthermore, N-hop graph expansions require round-trip HTTP requests per node rather than utilizing recursive SQL CTEs on DuckDB.

### GAP 3: Static Pattern Packs vs Declarative Motif Engine
The pattern packs envisioned in `link-analysis-pattern-packs-plan.md` (e.g., circular shell company layering, mule account dispersion, carousel tax fraud) require multi-hop, branching, and temporally constrained graph matching. The current implementation relies on rudimentary client-side degree and neighbor filters, unable to express conditions like:
`Node(A) -[transfer, t1]-> Node(B) -[transfer, t2 > t1, within 48h]-> Node(C) where sum(t2) >= 0.9 * sum(t1)`.

### GAP 4: Missing Cross-Dataset Overlap Profiling
While `GET /inv/schema/relationships` matches column names (e.g., `sender_id` matching `account_id`), it lacks data-level value profiling. It cannot determine if two columns actually share overlapping domains or compute Jaccard similarity between distinct datasets to proactively surface implicit relationships.

### GAP 5: Disconnection from Case Management & Evidence Store
An analyst examining a suspicious cluster in Link Analysis cannot freeze that state as immutable evidence. When navigating to Case Management, the graph selection is lost. There is no route to serialize the active sub-graph, its applied algorithmic metrics, spatial-temporal layout, and analytical notes into an evidence artifact tied to a Case ID.

### GAP 6: Decoupled Geospatial and Link Topologies
The Studio environment features both a Geo-Map viewer (`inspecto-geo-link`) and Link Analysis (`inspecto-ui/src/app/modules/admin/studio/link-analysis/`). When entities possess geographical coordinates (e.g., IP addresses, cell towers, GPS pings), there is no synchronized cross-filtering (brushing) between the spatial map and the topological graph. Selecting an edge on the graph does not highlight the corresponding trajectory on the map.

### GAP 7: Lack of Automated Forensic Dossier Generation
Investigative teams require defensible, auditable reports. Currently, exporting graph intelligence requires manual screenshots. There is no automated generation of forensic dossiers detailing node properties, computed centrality rankings, community membership tables, and chain-of-custody metadata.

---

## 3. Engineering Roadmap

### Phase 1: High-Performance UI Offload & Case Integration (Sprint 9)
*Objective: Eliminate UI freezing, enable multi-dataset unified projections, and support Case evidence snapshotting.*

#### S1.1: Web Worker Computation Pipeline (`graph-worker.ts`)
- Move all 25+ algorithms from `graph-analysis.ts` into a dedicated Web Worker (`InspectoGraphWorker`).
- Implement zero-copy `ArrayBuffer` transfer for node indices, adjacency matrices, and metric vectors.
- Add progressive progress reporting (`postMessage({ type: 'PROGRESS', progress: 0.45 })`) for long-running algorithms (Louvain, PageRank).
- Provide a non-blocking `GraphAnalysisClient` service with cancellation tokens (`AbortController`).

#### S1.2: Multi-Dataset Graph Projection
- Enhance `InvRoutes.java` to support `POST /inv/projection/multi`:
  - Accepts an array of node mapping specifications and edge projection queries across multiple registered datasets.
  - Generates unified DuckDB SQL union views joining distinct datasets within the Space.
  - Returns unified node-edge payloads with dataset provenance tagging (`__provenance_dataset`).

#### S1.3: Durable Case Evidence Snapshotting
- Implement `POST /inv/snapshots` in `InvRoutes.java`:
  - Serializes sub-graph state: node IDs, edge IDs, calculated algorithmic scores, spatial coordinates, viewport transformations, and investigator annotations.
  - Persists snapshot to the Space storage backend as JSON/Parquet evidence packages.
- Add "Attach to Case" UI action in Studio Link Analysis toolbar:
  - Invokes `POST /cases/{caseId}/evidence/graph` to link snapshot ID with the Case audit ledger.

---

#### S1.4: Two-Stage Query — refine locally, push the predicate down (added 2026-09-20)

Specified in the spec §3.7. **Ordered before S2.1 on purpose**: it delivers most of the value of
server-side filtering with none of the recursive-CTE risk, and it is the prerequisite for making
S2.1's traversal *filtered* rather than exhaustive.

🔴 **Sizing: S–M, because it is wiring, not construction.** A first design pass (chat, 2026-09-20)
named three "genuinely new" pieces — a SQL compiler for the predicate, a `filter` field, and a better
sample. Grounding refuted two of the three: `ConditionSql.predicate()` already compiles this exact
tree to DuckDB with three production consumers and a live-DuckDB parity test, and `project()`
already samples top-N by `cnt DESC` (`InvRoutes.java:215`). Only the `filter` field is new.

1. **Backend — the `filter` field.** `POST /inv/projection` and `/neighbors` accept
   `filter?: ConditionGroup`. Validate every leaf `field` against the relation's actual columns
   (the `schemaRelationships` path already reads them) → 422 `CONFIG_VALIDATION_FAILED` on an unknown
   column. Then `AND (ConditionSql.predicate(filter))` into the existing `WHERE` at
   `InvRoutes.java:213`, **ahead of the `GROUP BY`** so `count` folds correctly. An absent filter
   renders `TRUE` — no-op by construction. Tests (real-HTTP, house idiom): a time-window filter
   changes `count`, not just membership; an `OR` group over `sourceCol`/`targetCol` implements
   "node present as either endpoint"; an unknown `field` is 422; an injection probe on `field` and
   on a `contains` operand lands nowhere. ⚠ Decision §7.5 (bind vs escape) must be answered first —
   recommended (a).
2. **Client — mount the builder and revive the engine.** Host `<inspecto-query-condition-group>` on
   the Link Analysis query panel with `columns` = `sourceCol` + `targetCol` + `attrCols` (typed via
   `inferColumns()` over the working set). Stage 1 evaluates via `evaluateRows` over
   `edges.map(e => e.attrs)` with `{projection:'*', where}` — **reviving a retained evaluator with
   zero live callers**; its `query-eval.spec.ts` becomes live coverage the day this lands. Two
   actions: **Apply locally** (stage 1) and **Push to server** (stage 2, sends the tree as `filter`).
   Surface `truncated` as the loop's signal; on return, `mergeGraphs` into the working set and
   **mark** stranded nodes, never drop them.
3. **The stage-1 → stage-2 translation.** A client node filter (by G6 node id) becomes an `OR` group
   over `sourceCol`/`targetCol`; everything else passes through unchanged because the tree already
   operates on relation columns. Pin this with a unit test on the translator alone.
4. **Cross-stage parity test.** Same tree + same rows ⇒ identical edge set from stage 1 and stage 2.
   Template: `ConditionSqlTest.assertParity`. ⚠ This is the test that keeps the two evaluators in
   lockstep as operators are added; without it the repo's recorded "full parity that wasn't
   injective" failure returns.
5. **Persist the predicate.** Extend the `link-analysis-view` component to carry `filter`. Note
   `RuleTemplate` (`rule-template` component) as the shared shape for a reusable saved filter
   template; do not fork a second one.

**Acceptance:** an analyst projects a Dataset, narrows it in the browser with no round-trip, pushes
the predicate, and either receives an untruncated result or a `truncated` flag telling them to
refine further — and at no point can the pushed filter reach the statement unvalidated.

#### UI design requirements from the mockup review (operator, 2026-09-20)

Raised against [`link-analysis-ui-mockup.html`](link-analysis-ui-mockup.html) v1 and reflected in v2. They
bind the SPA work in S1.1, S1.3, S1.4 and any rendering item; none is sized here.

1. **Domain profiles.** Data comes from different domains (finance, telecom CDR, supply chain, cyber) and
   the statistics differ with the nature of the data. A **domain profile** maps the projection's columns to
   entity types, formats the edge measure, derives the working-set statistics from the attribute columns'
   detected types (temporal → span, numeric → sum, categorical → count distinct, plus derived ratios), and
   foregrounds the toolbox groups that matter for that domain. The profile is saved with the view.
2. **Side panels resize and collapse.** Query panel and toolbox are draggable gutters with a minimum and
   maximum width, collapse to an icon rail, and a single action maximises the canvas.
3. **Canvas overlays minimise.** Legend, Working set and Minimap each fold to a pill so the graph gets the
   space; the View toolbox toggles them.
4. **Advanced search beside the predicate builder.** The builder stays the primary surface; an *Advanced*
   action opens SQL over the Dataset with a **tabular** result. ⚠ Spec §4.3 (no free-text SQL) still
   holds for `/inv/projection`: the SQL rides the data-table's existing Pro SQL editor pattern
   (`SqlEditorComponent`, `sqlOverride`) and the `SqlGuard.isReadOnly` check, is seeded from the predicate
   tree, and what gets projected is the **result relation**, never the text. A graph-query dialect tab is
   reserved for a graph-store-backed edition and stays disabled over a Dataset rather than being emulated.
5. **Rendering at scale is a requirement of its own.** The spec §4.1 records *no virtualisation or
   level-of-detail* on the canvas, and no roadmap item covers it — S1.1 moves *analysis* off the main
   thread, not *drawing*. Target: WebGL renderer, level of detail (labels off when zoomed out), viewport
   culling, progressive load (heaviest edges first), and aggregation of low-degree leaves into
   super-nodes above a threshold, with the rendering limit published in the footer next to the analysis
   cap. ⇒ **Open: add a rendering item (S1.5) and size it, or fold it into S1.1.** Operator's call.
6. **A View toolbox alongside Analysis.** The right panel gains a second tab mirroring the AntV G6 v5
   example gallery: the 11 layouts the SPA already offers (`graph-view.component.ts:95-127`) plus
   Fruchterman, combo force, fishbone and dendrogram to add; lenses and selection (brush, lasso, fisheye,
   edge-filter lens, hover activate); overlays and plugins (minimap, timebar, legend, community hulls /
   bubble sets, combos, edge bundling, tooltip, context menu, snapline, history, watermark). Each is a
   G6 layout id, behavior or plugin, not a new engine.

### Phase 2: DuckDB Recursive Traversal & Declarative Motif Engine (Sprint 10)
*Objective: Shift multi-hop graph expansion to server-side DuckDB execution and deploy declarative forensic pattern matching.*

#### S2.1: Server-Side Recursive CTE Traversal
- Introduce `POST /inv/traversal/recursive-paths` in `InvRoutes.java`:
  - Executes DuckDB recursive CTE queries (`WITH RECURSIVE graph_hops AS (...)`) to resolve N-hop neighborhoods, shortest paths, and all-paths between target nodes directly in the database engine.
  - Avoids sending intermediate graph nodes over HTTP; returns only pruned paths matching depth, weight, and edge type criteria.
  - Enforces resource fences: maximum recursion depth (default: 6), query timeout (default: 5000ms), and max edge yield limit.

#### S2.2: Branching Pattern Pack Runtime
- Build `BranchingPatternEngine.ts` in frontend and `PatternQueryCompiler.java` in backend.
- Define JSON-based pattern motif schema supporting:
  - Multi-branch structures (e.g., Diamond, Funnel, Star-Burst).
  - Edge attribute constraints (amounts, dates, categories).
  - Temporal sequencing operators (`t_edge2 > t_edge1`, window `DELTA <= 48h`).
- Ship standard forensic pattern packs:
  1. *Structuring / Smurfing*: High-volume deposits under reporting thresholds followed by single aggregation transfer.
  2. *Layering / Pass-Through*: Rapid sequential transfers across shell entities with minimal balance retention.
  3. *Circular Financing*: Closed-loop directed cycles with decay ratios under 10%.

#### S2.3: Cross-Dataset Overlap Profiling
- Add `POST /inv/schema/overlap-profile` in `InvRoutes.java`:
  - Calculates cardinality and Jaccard similarity across candidate key columns between distinct datasets using DuckDB's `APPROX_COUNT_DISTINCT` (HyperLogLog).
  - Surfaces high-probability implicit foreign keys to guide investigator graph joins without requiring manual schema declarations.

---

### Phase 3: Dual-Pane Geo+Graph Brushing & Forensic Dossiers (Sprint 11)
*Objective: Unify spatial and topological exploration and automate publication of court-ready forensic packages.*

#### S3.1: Synchronized Dual-Pane Geo-Spatial & Link Brushing
- Create `GeoLinkSyncService.ts` in `inspecto-ui`:
  - Coordinates bidirectional event bus between Leaflet/MapLibre map view and Link Analysis canvas.
  - Bounding-box selection on map highlights and isolates corresponding topological nodes on graph.
  - Path selection on graph traces animated geographic transit route on map view.
  - Split-pane layout mode with responsive synchronization.

#### S3.2: Automated Forensic Case Dossier Generator
- Build `GraphDossierBuilder.java` in `inspecto-geo-link`:
  - Renders vector SVG graph representations server-side or consumes high-DPI client canvas snapshots.
  - Generates comprehensive PDF/HTML forensic report containing:
    - Executive investigation summary.
    - Graph topology diagram with identified critical nodes.
    - Tabular centrality and risk ranking metrics.
    - Chronological transaction / interaction ledger.
    - Digital signature and cryptographic hash (SHA-256) of evidence state for chain-of-custody compliance.

---

## 4. API & Contract Specifications

### S1.4 Contract: `filter` on `POST /inv/projection` (and `/neighbors`)

The existing body gains one optional field. Everything else is unchanged.

```json
{
  "dataset": "transactions",
  "sourceCol": "payer_id",
  "targetCol": "payee_id",
  "linkKindCol": "channel",
  "attrCols": ["booked_at", "amount"],
  "limit": 2000,
  "filter": {
    "kind": "group", "op": "AND", "items": [
      { "kind": "condition", "field": "booked_at", "operator": "between",
        "value": "2026-01-01", "value2": "2026-03-31" },
      { "kind": "condition", "field": "channel", "operator": "in", "value": "wire,crypto" },
      { "kind": "group", "op": "OR", "items": [
        { "kind": "condition", "field": "payer_id", "operator": "=", "value": "ACME-001" },
        { "kind": "condition", "field": "payee_id", "operator": "=", "value": "ACME-001" }
      ]}
    ]
  }
}
```

**Semantics.** `filter` is the `query-types.ts` condition tree, verbatim — the same object the
data-table's builder emits and `ConditionSql` compiles. It is applied to the underlying relation
**before** the `GROUP BY`, so edge `count` reflects only matching rows. The nested `OR` group is the
canonical "node present as either endpoint" form (spec §3.7). An absent or empty `filter` imposes no
constraint (`ConditionSql` renders `TRUE`).

**Validation, fail-closed.** Every leaf `field` must name a column of the Dataset's relation;
otherwise 422 `CONFIG_VALIDATION_FAILED` naming the offending field. Operands are quote-escaped by
`ConditionSql` (or bound, per decision §7.5). Identifiers never come from the tree unvalidated.

**Response.** Unchanged: `{rows:[{source,target,kind?,count,attrs?}], truncated}`. `truncated: true`
is the loop's signal to refine further.

### S1.2 Contract: `POST /inv/projection/multi`
```json
{
  "space": "default",
  "nodes": [
    {
      "dataset": "entities_registry",
      "idColumn": "entity_urn",
      "labelColumn": "legal_name",
      "category": "ORGANIZATION",
      "attributes": ["jurisdiction", "incorporation_date"]
    },
    {
      "dataset": "beneficial_owners",
      "idColumn": "person_id",
      "labelColumn": "full_name",
      "category": "PERSON",
      "attributes": ["citizenship", "risk_rating"]
    }
  ],
  "edges": [
    {
      "dataset": "ownership_links",
      "sourceColumn": "owner_person_id",
      "targetColumn": "company_urn",
      "type": "BENEFICIAL_OWNER",
      "attributes": ["share_percentage", "voting_rights"]
    },
    {
      "dataset": "bank_wires",
      "sourceColumn": "originating_entity_urn",
      "targetColumn": "beneficiary_entity_urn",
      "type": "WIRE_TRANSFER",
      "attributes": ["amount", "currency", "timestamp"]
    }
  ],
  "filter": "wire_transfers.amount > 10000"
}
```

### S2.1 Contract: `POST /inv/traversal/recursive-paths`
```json
{
  "space": "default",
  "edgeDataset": "bank_wires",
  "sourceColumn": "sender_account",
  "targetColumn": "recipient_account",
  "startNode": "ACC-99201",
  "targetNode": "ACC-44109",
  "maxDepth": 5,
  "weightColumn": "amount",
  "direction": "DIRECTED",
  "temporalConstraint": {
    "timestampColumn": "executed_at",
    "monotonic": true,
    "maxTotalDurationHours": 72
  }
}
```

### S1.3 Contract: `POST /inv/snapshots`
```json
{
  "space": "default",
  "title": "Layering Chain - Falcon Holdings to Swiss Account",
  "description": "Suspected mule layering network identified via Louvain community 4",
  "subgraph": {
    "nodeIds": ["ACC-99201", "ACC-88122", "ACC-44109"],
    "edgeIds": ["TX-1002", "TX-1003"]
  },
  "metrics": {
    "ACC-88122": { "betweenness": 0.884, "degree": 14 }
  },
  "viewport": {
    "zoom": 1.25,
    "pan": { "x": 420.5, "y": -118.0 }
  },
  "annotations": [
    {
      "targetId": "ACC-88122",
      "text": "Intermediate shell entity with 98% pass-through velocity within 4 hours."
    }
  ]
}
```

---

## 5. Verification & Acceptance Gates

| Gate | Target Phase | Criteria | Automated Test |
|---|---|---|---|
| **UI Non-Blocking Benchmark** | Phase 1 (S1.1) | Betweenness and Louvain execution on 10,000 nodes / 50,000 edges must not drop UI framerate below 55 FPS. Main thread idle time during calculation > 90%. | `inspecto-ui/src/app/inspecto/graph/graph-worker.spec.ts` |
| **Multi-Dataset Projection Correctness** | Phase 1 (S1.2) | `POST /inv/projection/multi` produces unified graph joining 3 distinct tables with correct provenance attributes and zero orphaned edge records. | `inspecto-geo-link/src/test/java/com/gamma/geolink/MultiProjectionContractTest.java` |
| **Recursive Traversal Bound & Performance** | Phase 2 (S2.1) | 5-hop path search over 1,000,000 edge dataset in DuckDB executes in < 350ms and strictly honours the recursion depth limit fence. | `inspecto-geo-link/src/test/java/com/gamma/geolink/RecursiveTraversalEngineTest.java` |
| **Temporal Motif Recognition Precision** | Phase 2 (S2.2) | Branching pattern pack engine identifies 100% of synthetic smurfing chains and rejects out-of-order timestamps. | `inspecto-ui/src/app/inspecto/graph/pattern-engine.spec.ts` |
| **Dossier Cryptographic Chain-of-Custody** | Phase 3 (S3.2) | Exported PDF bundle embeds verifiable SHA-256 manifest of all included nodes, edges, and metric vectors. | `inspecto-geo-link/src/test/java/com/gamma/geolink/GraphDossierBuilderTest.java` |
