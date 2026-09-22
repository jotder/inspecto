<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-09-22 by consolidating three documents that were archived the same day:
    link-analysis-spec.md (2026-09-20)             — target state, clause-tagged
    link-analysis-advancement-plan.md (2026-09-17) — Sprints 9-11 roadmap
    link-analysis-enquiry-model-plan.md (2026-09-22) — the investigation as an object
  This is the ONLY open backlog for Link Analysis. Nothing pending lives anywhere else.
  Retire per the three-tier lifecycle in CLAUDE.md when the work ships.
-->

# Link Analysis — Backlog Plan (`INV-1` / `CP-09`)

**One document: what ships, what is owed, what must be decided, and how each item is proven.**

| | |
|---|---|
| Status | ACTIVE PLAN — consolidated 2026-09-22; work items carry their own state below |
| Replaces | `link-analysis-spec.md` · `link-analysis-advancement-plan.md` · `link-analysis-enquiry-model-plan.md` (all archived 2026-09-22) |
| Design reference | [`link-analysis-ui-mockup.html`](link-analysis-ui-mockup.html) — clickable target state, dummy data, not product code |
| As-built mechanism | [`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) |
| Edition | Professional / Enterprise only — `CP-09` / `EDG-01`, module `inspecto-geo-link` |
| Grounding | Code reads 2026-09-20 (backend + SPA, independent) and 2026-09-22 (scale). ⛔ When this plan and code disagree, re-ground; never trust the plan |

---

## 0. How to read this

- §1 is the **as-built baseline** — what an analyst can do today, cited to code. Nothing here is work.
- §2 is the **model** the work builds toward: the investigation as an ordered object.
- §3 is the **work item register** — every pending item, one id each, with state and size.
- §4 is the **decision register** — every owed operator call, one id each. ⛔ None may be answered by an
  implementer in passing.
- §5 holds the **API contracts**; §6 the **acceptance gates**; §7 the **corrections and lessons** the three
  source documents accumulated, kept so nobody re-derives them.

Status tags: ✅ SHIPPED (built, reachable, tested, cited) · 🟡 PARTIAL (something real exists, gap stated) ·
⬜ NOT BUILT (absence confirmed, not merely unfound).

---

## 1. As-built baseline (grounded 2026-09-20)

### 1.1 Scope and edition

Link Analysis is the graph half of `CP-09`; `GeoRoutes` is the map half. Both live in the optional
`inspecto-geo-link` module, built only under the `edition-professional` / `edition-enterprise` Maven
profiles (`inspecto-geo-link/pom.xml:17-35`). Personal degrades honestly: `AbsentGeoLinkRoutes` answers
`503 not installed`, `features.geoLink` is reported through `BootstrapRoutes` / `CapabilityManifest`, pinned
by `NoGeoLinkShipsInThePersonalBuildTest`.

### 1.2 The model as it ships

- **An edge is a folded aggregate, not a row.** The server projects `{source, target, kind?, count, attrs?}`
  by `GROUP BY` over a Dataset relation (`InvRoutes.java:170-241`); `count` is how many rows produced it.
- **Nodes are implied by edge endpoints.** No node table, no identity service; a node has no attributes of
  its own at the API layer. Today an Entity *is* a raw column value, `kind: 'entity'` hardcoded
  (`entity-projection.ts:103`); `entityType` only namespaces the id and is neither queryable nor enforced.
- **No persisted graph.** Every call re-runs the aggregation. Deliberate today — and the root of the
  "a saved view is not evidence" defect (§1.6).
- **Four graph source planes** behind a `GraphSource` interface (`graph-sources.ts:20-60`): lineage/catalog
  (`/catalog/graph`), artifact/component model, pipeline, and Dataset entity projection (`/inv/projection`).
  ⚠ Only the last is single-Dataset; the feature as a whole is not.
- **Relationship discovery.** `GET /inv/schema/relationships` infers candidate joins by naming convention
  (`InvRoutes.java:83-137`), best-effort: an unprobeable Dataset is skipped, so "no relationships" and
  "could not read anything" look identical. Accepted limitation.

### 1.3 What an analyst can do

| Capability | State | Evidence |
|---|---|---|
| Project from a Dataset | ✅ | `POST /inv/projection` `{dataset, sourceCol, targetCol, linkKindCol?, attrCols?, limit?}` (`InvRoutes.java:67`) |
| Expand one hop | ✅ | `POST /inv/projection/neighbors` + `value`; wired to the per-node expand action |
| Roots, depth, direction, kind filters in the query panel | ✅ | query dock |
| Pan / zoom / drag / layout switch / colour by kind / collapse branch / search | ✅ | `graph-view.component.ts` (AntV G6 v5), 11 layouts (`:95-127`) |
| Timeline slider on a date attr | ✅ one-sided client cutoff only | `link-analysis.component.ts:409-427` |
| 13 toolbox groups fronting 27 wired algorithms, all browser-side, main thread | ✅ | `link-analysis-toolbox.component.ts:145-158`, `inspecto/graph/graph-analysis.ts` |
| Pattern matching with loadable packs (built-in + Space-authored, merged) | 🟡 | structurally limited; no branching motifs, no temporal ordering |
| Saved views (`link-analysis-view` component), share via Exchange, comment | ✅ | `link-analysis.service.ts:11-13`; filter/timeline/collapse state is session-only |
| Export JSON / PNG / SVG / GraphML | ✅ | `link-analysis.component.ts:858-896` |
| Geo → Link one-way handoff (co-location dialog) | 🟡 | `colocation-graph.dialog.ts:8-10`; no two-way sync |
| Attach to a Case / Incident / operational object | ⬜ | grep of `inspecto-geo-link` for `ObjectService` / `OperationalObject` is empty |

The 27 algorithms: `shortestPath`, `weightedShortestPath`, `allPaths`, `neighborhood`, `explainNode`,
`degreeCentrality`, `betweennessCentrality`, `closenessCentrality`, `eigenvectorCentrality`,
`katzCentrality`, `pageRank`, `hits`, `detectCommunities`, `louvainCommunities`, `connectedComponents`,
`findCycles`, `articulationPoints`, `bridges`, `kCore`, `triangleCount`, `cliques`, `maxFlow`,
`maximumSpanningForest`, `jaccardSimilarity`, `linkPrediction`, `suspicionScore`, `matchPattern`.
The toolbox is **one accordion, one group open at a time** — not tabs.

### 1.4 UI-first work already on `master` (2026-09-20, commits `fe7b678b` … `92032f78`)

Seven commits, 26 spec files / 216 tests green, production build green, driven in the preview over the demo
lineage plane. Each mock-backed surface **says so on screen**, and nothing invents a result a route did not
return.

| Surface | As built | Backend still owed (→ §3) |
|---|---|---|
| Workspace | Query dock left · Analysis / View toolbox right, both `inspectoSplit`-resizable and collapsible to icon rails; maximize-canvas; Data table bottom strip | — |
| Domain profiles | `inspecto/graph/domain-profile.ts` (generic · finance · telecom · supply chain · cyber); persisted as `LinkAnalysisView.profile` | — |
| Overlays | Legend and Working set (`workingSetStats`) minimise to pills; state persisted with the view | — |
| Two-stage filter loop | `inspecto/graph/graph-filter.ts` + `<inspecto-link-analysis-filter>`; **Apply locally** · **Push to server** (tree sent as `query.filter`, merged with `markStranded`) | **LA-01** — the body ignores `filter`; a push returns the unfiltered projection |
| Advanced search | Dialog over the data-table Pro tier; *Run on server* via `POST /db/query`; **Project result as graph** folds RESULT rows client-side, SQL text never reaches `/inv/projection` | — |
| Evidence | `inspecto/graph/graph-snapshot.ts` (canonical JSON + FNV-1a fingerprint), Snapshot dialog, Attach to Case dialog; `LinkAnalysisSnapshotsService` is a **session-scoped mock store** | **LA-03** snapshots route · **LA-12** SHA-256 |
| View toolbox | `graph-view` `[plugins]`: minimap · grid · Louvain hulls · bundling · fisheye · link-filter lens · hover / brush / lasso; layout gallery; label toggles | — |
| Rendering | Level of detail = label suppression above `LOD_LABEL_CAP = 300`; render footer prints the caps | **LA-06** |

### 1.5 Limits and safety, as built

| Limit | Value | Where | Behaviour at the edge |
|---|---|---|---|
| `PROJECTION_NODE_CAP` | **500** | `entity-projection.ts:32`, applied `:99,:150` | truncates the fetch — **the real governor** |
| `ANALYSIS_NODE_CAP` | 2 000 | `graph-analysis.ts:11`, `requireUnderCap` `:836-840` | ⚠ **throws** on super-linear algorithms |
| `DEFAULT_LIMIT` / `MAX_LIMIT` | 2 000 / 20 000 | `InvRoutes.java:62-63,178-179` | clamps, sets `truncated: true` |
| Render | none | `graph-view.component.ts` | no virtualisation, culling or WebGL |

🔴 Raising cap 2 or 3 alone changes nothing an analyst sees — projection truncates at 500 first. The
client cap is a **refusal, not a slowdown**: betweenness, Louvain, closeness, eigenvector, Katz, HITS,
cliques, maxFlow, linkPrediction and suspicionScore throw above 2 000 nodes. A worker alone fixes
responsiveness and leaves the ceiling.

Safety ✅: no free-text SQL — identifiers must match `SAFE_IDENT` (`InvRoutes.java:61`), values bind as JDBC
`?` (`:204-210`), quote-safety pinned by test; fails closed (503 no write root · 404 unknown Dataset · 422 bad
identifier). `ControlApiInvProjectionTest` (10 tests) pins that edge `count` equals the row count per pair.

### 1.6 Structural consequences

1. **Re-projection on every call** — cost scales with usage; two analysts on one Dataset pay twice; and a
   view re-opened after the Dataset changed silently shows a different graph. **A saved view is not
   evidence.** The most consequential gap in this document.
2. **All analysis is client-side** — supported graph size is bounded by one browser tab.
3. **Nodes have no identity** — centrality and community results are only as meaningful as raw column
   values, and nothing warns the analyst.
4. **The actual slowness is not G6.** `rebuild()` (`graph-view.component.ts:362-489`) unconditionally
   `graph.destroy()` → `new Graph` → `render()` on **any** `@Input` change, and every bound input is a
   `computed()` returning a new reference — so toggling labels or nudging the timeline reruns the whole
   layout synchronously (`force` / `force-cluster` / `mds` rerun the full simulation). Zero uses of
   `setData` / `updateNodeData` / `changeData`. Bug-shaped, not architecture-shaped (→ **LA-05**).

---

## 2. The target model — the investigation is an object, not a query

Raised 2026-09-22 from a criminal-fraud call-records scenario: *seed from a few subscribers → expand 2nd
degree → remove marketing and non-suspect numbers → expand survivors to 3rd degree → hide connection kinds
per level → recurring intraday slot 22:00–04:00 → min/max connections in range.* Every step is an
**operation applied to a prior result**; a projection is a pure function of a query and cannot represent
"what the analyst did next". Seven missing features share one missing object.

### 2.1 Three objects

| Object | What it is | Mutability |
|---|---|---|
| **Enquiry** ⚠ placeholder name (D-E1) | goal + ordered op log + bindings (Dataset + pinned version, projection mapping, scope). *The program.* | append-only log; header versioned |
| **Working Set** | Entities and Links from evaluating the log to a position. *The state.* A **relation defined by the log, never a stored copy** (operator call 2026-09-22) | derived; cached, invalidatable |
| **Artifact** | snapshot, exhibit, note, dossier — produced *at* a log position | immutable, anchored to `opSeq` |

An Enquiry belongs to a Case (`ObjectType.CASE`), which already owns evidence attachment.

### 2.2 The closed op vocabulary — eleven ops, no twelfth without a decision entry

| Op | Kind | Parameters |
|---|---|---|
| `seed` | extensional | entity ids, entity type |
| `seedBy` | intensional | predicate over the Dataset |
| `expand` | intensional | one hop-ladder rung (§2.4) |
| `exclude` | extensional | entity ids + reason code |
| `excludeBy` | intensional | predicate, or a named reference list |
| `keep` | extensional | entity ids — protected from later filters |
| `threshold` | intensional | measure, min, max, evaluation scope |
| `window` | intensional | time range and/or recurring intraday slot (§2.5) |
| `hide` | intensional | display-only; never affects traversal or measures |
| `annotate` | extensional | entity/link id, note, confidence |
| `snapshot` | — | freezes the Working Set as an Artifact |

⛔ **Why closed:** the shipped backend is safe because it is narrow (`SAFE_IDENT` + bind params); a closed
vocabulary renders as numbered plain-language steps for a court; every open query language converges on
SQL, badly. The engine may be sophisticated; the vocabulary must not be.

### 2.3 Rules the model must respect

- **Ordered, non-commutative.** `exclude(marketing) → expand(3)` and `expand(3) → exclude(marketing)`
  produce different graphs; only the first is what the analyst means, and the second looks plausible on
  screen. Re-ordering is an explicit edit that invalidates downstream Artifacts (or forks — D-E4).
- **Intensional and extensional ops are both first-class** and stay distinguishable: a stated rule and an
  analyst's judgement are challenged differently.
- **`hide` ≠ `exclude` ≠ `keep`:** hide = gone from display, still traversed and counted; exclude = gone
  from all three; keep = pinned. Excluded entities remain inspectable ("37 excluded — show").
- **Two evaluators, one spec:** incremental (delta on the Working Set, drives the UI) and full replay (from
  `seed` against the pinned version, drives evidence), with an equivalence check before any snapshot seals.
  The two-stage filter loop already has this shape — and today its stage 2 round-trips with **no effect**
  (LA-01); generalising the loop without closing that gap makes divergence invisible.

### 2.4 The hop ladder

Each rung is one `expand` op: `direction` (out · in · either · reciprocal) · `linkKinds` traversable at this
rung · `window` (inherit or override) · `minEvents`, `minDistinctDays` · `candidateDegreeMin/Max` evaluated
**within the window** · `maxFanOut` (strongest first) · `budget` (on breach set `truncated` and say so).

| Hop | Direction | Link kinds | Window | Min events | Min days | Cand. degree | Fan-out | Budget |
|---|---|---|---|---|---|---|---|---|
| 1 | either | all | full | 1 | 1 | — | 200 | 500 |
| 2 | either | voice, sms | 22:00–04:00 | 3 | 2 | 2–150 | 50 | 2 000 |
| 3 | out | transfer | 22:00–04:00 | 5 | 3 | 2–40 | 20 | 5 000 |

### 2.5 Time

In order of need: two-handle absolute range, server-side · recurring intraday window crossing midnight, with
day-of-week masks and calendar exclusions — 🔴 **needs an explicit timezone contract** (DuckDB's session
TimeZone is the host, not UTC; call records add network vs local vs roaming time) · thresholds evaluated
**inside** the window, on the filtered Link set · comparison mode (two windows diffed) · time-respecting
paths (A→B at t₁, B→C at t₂ > t₁) — 🔴 the shipped `layering-chain` and `pass-through` packs have **no
temporal ordering**, so they can match sequences that ran backwards · timeline playback, burst and
periodicity detection.

### 2.6 Entity identity, value measures, controls

- **Identity** (D-S4): typed Entities (subscriber, IMSI, IMEI, wallet, agent/till, handset, cell);
  resolution across identifiers; enrichment as filterable attributes; asserted fact kept distinct from
  inference. Without it `exclude` lists cannot persist across Enquiries.
- **Value semantics for the money verticals:** value-weighted Links; pass-through ratio and retention;
  structuring across sub-threshold transfers; velocity and time-to-cash-out; cash-out concentration by
  agent; benefit-transfer patterns. Expressed as named **Decision Rules** with visible thresholds, never an
  opaque score. 🔴 The demo already shows the failure: filtering `mule_large_transfers` to ≥ 5 000 makes the
  smurf deposits vanish — exactly what structuring is designed to do.
- **Controls, non-optional in a criminal matter:** scope binding · per-query audit trail (✅ mechanism ships
  — `EventLog.current().emit(...)`, `AuditLogRoutes` — ⛔ never called from `InvRoutes` / `GeoRoutes`) ·
  minimisation with explicit, logged reveal · chain of custody (SHA-256 over exhibit + records + log + pinned
  version) · four-eyes on sensitive expansions · retention and purge · per-entity annotation (today per-view
  comments only) · **coverage indicator** — which days and Collectors are missing for the window. 🔴 A gap
  in the data is visually identical to innocence; coverage is the highest-value control on this list.

### 2.7 Reporting — one log, three renderings, and negative space

⚠ **"Report" is the wrong word** — a Report here is a scheduled delivery; the evidential narrative is the
**Dossier**.

- The Working Set is exposed as a **derived relation** carrying provenance columns (`opSeq`, `seedId`,
  `hop`, reason codes). A **Widget** binds to it; Measures, Dashboards, Reports and Alert Rules follow.
  🔴 "Reproducible from the queries" holds **only if reads are version-pinned**; otherwise re-running
  reproduces the *method*, not the *finding*. With version-addressable reads (DuckLake snapshots, per
  `enterprise-scale-out-plan.md`) nothing is materialised; without them, freezing one Evidence Artifact
  materialises *that Artifact* — the narrow exception. D-E3 and this are one decision.
- **Evidence Widget** reads the pinned version and never moves; **Monitoring Widget** reads current data and
  shows drift (*"sealed 2026-09-22: 12 accounts · now: 19 · 7 admitted since"*). Pinned is the default; the
  kind is legible on the tile. 🔴 A Monitoring tile handed to an authority moves under the reader.
- Three renderings of one log: the JSON (analyst, re-runnable) · numbered plain-language steps (reviewer,
  every op **including exclusions**, with author and reason code) · the method statement in the Dossier
  (authority, with custody hash). Because every line is one op from a closed vocabulary, it renders
  mechanically — no authored prose can drift.
- 🔴 **Negative space is part of the report:** what was excluded, how many, by which op and reason code,
  separating stated rules from analyst judgement · every rung that hit `truncated` · coverage gaps · which
  measures were computed over which set and when. Gate G-E10 pins it because it is the clause most likely to
  be dropped under delivery pressure.
- **Enquiry Template** (Type) = the log with seeds and window as parameters; **Enquiry** (Instance) = one
  binding. Then `Enquiry → Template → Measure over the relation → Alert Rule → Alert → Incident → Case` —
  every noun after the first two already ships. This is what turns an investigation tool into a detection
  capability.

---

## 3. Work item register — everything pending, one id each

Sizes: S ≤ 2 days · M ≤ 2 weeks · L > 2 weeks. "Blocked on" names §4 decisions. Phase = suggested order,
not a promise.

✅ **§3.1 Foundations is COMPLETE — all four items shipped 2026-09-22** (LA-01, LA-02, LA-04, LA-05).
As-built facts are distilled into [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md)
§*Grounded limits and consequences*; read that, not these rows, for what the code now does.
⚠ The rows below are kept for provenance and record what each one actually turned out to be.

### 3.1 Foundations (do first)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **LA-01** | `filter` on `POST /inv/projection` and `/neighbors` | ✅ **SHIPPED 2026-09-22** | S–M | — (D-S5 answered) | Validate every leaf `field` against the relation's actual columns → 422 `CONFIG_VALIDATION_FAILED`; `AND (ConditionSql.predicate(filter))` into the existing `WHERE` at `InvRoutes.java:213`, **ahead of the `GROUP BY`** so `count` folds correctly; empty tree renders `TRUE`. Revive `evaluateRows` (`query-eval.ts:9`, zero live callers) as the stage-1 engine so its spec becomes live coverage. Translator unit test: a client node filter → `OR` group over `sourceCol`/`targetCol`. Cross-stage parity test (template `ConditionSqlTest.assertParity`). Persist the predicate in the view (`RuleTemplate` is the shared shape for a reusable saved filter template — do not fork). Contract §5.1. |
| **LA-02** | `truncated` reaches the analyst | ✅ **SHIPPED 2026-09-22** — a REAL defect, not just unverified | S | — | Server sets it; the working-set overlay prints it; verify end to end and pin with a spec. In investigative use an unsurfaced truncation is a false negative presented as a finding. |
| **LA-05** | Incremental `rebuild()` | ✅ **SHIPPED 2026-09-22** | S–M | — | Diff and apply via G6 update APIs; recreate only when layout id or renderer changes; memoise the `computed()` inputs so cosmetic changes do not yield new references; separate cosmetic from structural (a display change never touches layout); persist layout positions across rebuilds. **Single highest-value performance fix.** |
| **LA-04** | Audit emit from `InvRoutes` / `GeoRoutes` | ✅ **SHIPPED 2026-09-22** (server-visible acts only) | S | — | Every projection, expansion, exclusion, reveal, export → `EventLog.current().emit(...)`. Mechanism ships; nothing calls it. |

### 3.2 Phase 1 — offload, projection, evidence (Sprint 9)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **LA-03** | `POST /inv/snapshots` + `POST /cases/{id}/evidence/graph` | ⬜ backend · ✅ SPA mock store | M | D-S1, D-E2 | Serialise sub-graph, scores, positions, viewport, annotations, predicate, origin, pinned Dataset version; persist as an Artifact anchored to `opSeq`; swap `LinkAnalysisSnapshotsService.add/attach`. Contract §5.4. |
| **LA-07** | Web Worker computation (`graph-worker.ts`, `GraphAnalysisClient`) | ⬜ | M | D-S3 | Move the 27 algorithms off the main thread; zero-copy `ArrayBuffer` transfer; `PROGRESS` messages; `AbortController` cancellation. ⚠ Fixes responsiveness only — the cap stays until D-S3 states a graceful published number. |
| **LA-08** | `POST /inv/projection/multi` | ⬜ | M | D-S4 | Node mappings + edge projections across Datasets in one call; unified DuckDB union views; `__provenance_dataset` tagging. Contract §5.2. |
| **LA-06** | Rendering at scale | 🟡 LOD labels only | M | — | Viewport culling, progressive load (heaviest edges first), super-node aggregation of low-degree leaves above a threshold, published render limit in the footer. **WebGL renderer only after measuring** — `package.json:33` installs `@antv/g6` alone, no `g6-plugin-webgl` / `layout-gpu`, and §1.6(4) says the canvas is not the bottleneck. |
| **LA-09** | View toolbox completions | 🟡 | S | — | Add Fruchterman, combo force, fishbone, dendrogram layouts; remaining G6 v5 plugins (timebar, bubble sets, combos, edge bundling, context menu, snapline, history, watermark). Each is a G6 id, not an engine. |

### 3.3 Phase 2 — the object, the ladder, the clock (Sprint 10)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **LA-10** | Enquiry object + ordered op log + incremental evaluator | ⬜ | L | D-E1, D-E2, D-E3 | `POST /inv/enquiries`, `/ops`, `/replay`, `GET /log` (§5.5). Ops `seed`, `expand` (one hop over `neighbors`), `exclude`, `hide`, `keep`; real undo; replaces the mock snapshot store. *Delivers prune-then-expand — the scenario's blocking step.* |
| **LA-11** | Server-side multi-hop traversal `POST /inv/traversal/recursive-paths` | ⬜ | L | D-S2 | DuckDB recursive CTE; fences — max depth (default 6), timeout (5 000 ms), max edge yield; the primitive `expand` compiles to. Contract §5.3. Working Set materialised so pruning does not re-query; pre-aggregated contact-pair Dataset (A, B, window, count, duration, value) as substrate, raw records on drill-down. |
| **LA-13** | Hop ladder + time model | ⬜ | L | LA-10, LA-11 | Per-rung fields §2.4; absolute range + midnight-crossing intraday window + **timezone contract**; in-window thresholds; `truncated` per rung. *Delivers the motivating scenario end to end.* |
| **LA-14** | Branching pattern runtime + temporal ordering | 🟡 packs ship, linear only | L | — | `BranchingPatternEngine.ts` + `PatternQueryCompiler.java`; JSON motif schema (multi-branch, attribute constraints, `t₂ > t₁`, `DELTA ≤ 48h`); fix `layering-chain` / `pass-through` to require temporal order; ship structuring / layering / circular-financing packs. |
| **LA-15** | `POST /inv/schema/overlap-profile` | ⬜ | M | — | Cardinality and Jaccard across candidate key columns via `APPROX_COUNT_DISTINCT`; surfaces implicit foreign keys. |
| **LA-16** | Synthetic call-records Dataset | ⬜ | S | — | ⚠ No call-records Dataset exists — `roaming_tap` is operator↔operator, `mule_transfers` account↔account; neither carries A/B-party, duration, device or cell. Prerequisite for every §6.3 gate. |

### 3.4 Phase 3 — identity, value, evidence, detection (Sprint 11+)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **LA-17** | Entity model + resolution + reference lists | ⬜ | L | D-S4, D-E8 | Typed Entities, cross-identifier resolution, enrichment attributes; named reference lists that persist across Enquiries. |
| **LA-18** | Value measures as Decision Rules | 🟡 | M | LA-17 | §2.6 list; visible thresholds; structuring must survive the ≥ 5 000 filter trap. |
| **LA-19** | Evidential controls | ⬜ | L | LA-10 | Scope binding, minimisation, four-eyes, retention/purge, per-entity annotation, **coverage indicator**. |
| **LA-20** | Working Set as a log-defined derived relation + cache | ⬜ | L | D-E3, D-E7 | §2.7; the cache is a functional requirement (six tiles = six re-runs per view). |
| **LA-21** | Evidence / Monitoring Widgets | ⬜ | M | LA-20, D-E6 | Pinned default; kind on the tile; drift line. |
| **LA-12** | Dossier + three renderings + chain of custody | ⬜ | L | LA-10 | `GraphDossierBuilder.java`: summary, topology, centrality/risk tables, chronological ledger, SHA-256 manifest (replaces the FNV-1a fingerprint); JSON / numbered steps / method statement; **negative space in all three**. |
| **LA-22** | Synchronised Geo ↔ Link brushing | 🟡 one-way handoff | M | — | `GeoLinkSyncService.ts`: bounding-box on map isolates nodes; path on graph traces the route; split-pane mode. |
| **LA-23** | Enquiry Templates → Measure → Alert Rule → Incident | ⬜ | M | LA-20, D-E8 | Cheap once LA-20 lands; every downstream noun ships. |

---

## 4. Decision register — owed operator calls

⛔ None may be answered by an implementer in passing. Ids: D-S* inherited from the spec, D-E* from the
Enquiry model.

| Id | Decision | Blocks | Notes / recommendation |
|---|---|---|---|
| **D-S1** | Is a saved view evidence? | LA-03 | If yes, snapshotting needs a store; if no, the UI must say so. The SPA already labels the saved-view option *not evidence*. |
| **D-S2** | Where does multi-hop traversal run? | LA-11 | Filtering is settled server-side (LA-01). Open: recursive CTE vs worker + raised cap. |
| **D-S3** | What is the supported graph size? | LA-07, LA-06 | A published number, enforced gracefully, never an exception. Must target `PROJECTION_NODE_CAP` (500) first. |
| **D-S4** | First-class node model, or stay value-projected? | LA-08, LA-17 | Blocks identity, attribute-rich analysis and persistent exclusion lists. |
| ~~**D-S5**~~ | ~~Bind or escape the pushed-down predicate?~~ | ~~LA-01~~ | ✅ **ANSWERED 2026-09-22 (operator): (a)** — reuse `ConditionSql` and validate every `field` against the relation's actual columns. Shipped that way; an unknown field is 422 before the renderer is called. The bind-emitting variant (b) stays an explicit follow-on, not built. |
| **D-E1** | What is the object called? | every LA-10+ touchpoint | `Investigation` reserved by `GLOSSARY-CASE-1`; `Case` is `ObjectType.CASE`; `Enquiry` is the placeholder. Decide first. |
| **D-E2** | Where does a Working Set live? | LA-03, LA-10 | In-memory per session, DuckDB temp relation, or durable store. Durability is what makes replay and evidence possible. |
| **D-E3** | Dataset version pinned at creation, or per op? | LA-10, LA-20, G-E11 | Incompatible options. Pin-at-creation matches evidence. One decision with §2.7's "reproducible from the queries". |
| **D-E4** | Re-ordering the log invalidates Artifacts, or forks the Enquiry? | LA-10 | Forking is safer and costs a branching model. |
| **D-E6** | Saved Widget frozen or live by default; may a live one leave the Space? | LA-21 | §2.7 recommends frozen. |
| **D-E7** | Who may evaluate an Enquiry's derived relation? | LA-20 | Must inherit the **Case's** scope, not the Space's Dataset permissions — 🔴 otherwise a Dashboard tile is a side channel around scope binding. |
| **D-E8** | Does an Enquiry Template carry its exclusion lists? | LA-17, LA-23 | Likely: named reference lists travel; analyst-judgement sets do not. |
| **D-U1** | Rendering item: own item (LA-06) or fold into LA-07? | LA-06 | Raised 2026-09-20 at the mockup review; this plan lists it separately pending the call. |

---

## 5. API contracts

### 5.1 `filter` on `POST /inv/projection` (and `/neighbors`) — LA-01

The body gains one optional field; the response is unchanged (`{rows:[{source,target,kind?,count,attrs?}],
truncated}`).

```json
{
  "dataset": "transactions",
  "sourceCol": "payer_id", "targetCol": "payee_id", "linkKindCol": "channel",
  "attrCols": ["booked_at", "amount"], "limit": 2000,
  "filter": {
    "kind": "group", "op": "AND", "items": [
      { "kind": "condition", "field": "booked_at", "operator": "between", "value": "2026-01-01", "value2": "2026-03-31" },
      { "kind": "condition", "field": "channel", "operator": "in", "value": "wire,crypto" },
      { "kind": "group", "op": "OR", "items": [
        { "kind": "condition", "field": "payer_id", "operator": "=", "value": "ACME-001" },
        { "kind": "condition", "field": "payee_id", "operator": "=", "value": "ACME-001" }
      ]}
    ]
  }
}
```

`filter` is the `query-types.ts` condition tree verbatim (13 operators), applied **before** the `GROUP BY`.
The nested `OR` is the canonical "node present as either endpoint" form. Every leaf `field` must name a
relation column or the call is 422 `CONFIG_VALIDATION_FAILED` naming the field. Identifiers never come from
the tree unvalidated.

### 5.2 `POST /inv/projection/multi` — LA-08

```json
{
  "space": "default",
  "nodes": [
    { "dataset": "entities_registry", "idColumn": "entity_urn", "labelColumn": "legal_name", "category": "ORGANIZATION", "attributes": ["jurisdiction", "incorporation_date"] },
    { "dataset": "beneficial_owners", "idColumn": "person_id", "labelColumn": "full_name", "category": "PERSON", "attributes": ["citizenship", "risk_rating"] }
  ],
  "edges": [
    { "dataset": "ownership_links", "sourceColumn": "owner_person_id", "targetColumn": "company_urn", "type": "BENEFICIAL_OWNER", "attributes": ["share_percentage", "voting_rights"] },
    { "dataset": "bank_wires", "sourceColumn": "originating_entity_urn", "targetColumn": "beneficiary_entity_urn", "type": "WIRE_TRANSFER", "attributes": ["amount", "currency", "timestamp"] }
  ],
  "filter": { "kind": "group", "op": "AND", "items": [ { "kind": "condition", "field": "amount", "operator": ">", "value": "10000" } ] }
}
```

⚠ `filter` is the same structured tree as §5.1 — the earlier draft carried a SQL string here, which §1.5's
no-free-text rule forbids.

### 5.3 `POST /inv/traversal/recursive-paths` — LA-11

```json
{
  "space": "default", "edgeDataset": "bank_wires",
  "sourceColumn": "sender_account", "targetColumn": "recipient_account",
  "startNode": "ACC-99201", "targetNode": "ACC-44109",
  "maxDepth": 5, "weightColumn": "amount", "direction": "DIRECTED",
  "temporalConstraint": { "timestampColumn": "executed_at", "monotonic": true, "maxTotalDurationHours": 72 }
}
```

### 5.4 `POST /inv/snapshots` — LA-03

```json
{
  "space": "default",
  "title": "Layering Chain - Falcon Holdings to Swiss Account",
  "description": "Suspected mule layering network identified via Louvain community 4",
  "subgraph": { "nodeIds": ["ACC-99201", "ACC-88122", "ACC-44109"], "edgeIds": ["TX-1002", "TX-1003"] },
  "scores": { "ACC-88122": { "betweenness": 0.884, "degree": 14 } },
  "viewport": { "zoom": 1.25, "pan": { "x": 420.5, "y": -118.0 } },
  "annotations": [ { "targetId": "ACC-88122", "text": "Intermediate shell entity with 98% pass-through velocity within 4 hours." } ]
}
```

Plus, once LA-10 exists: `enquiryId`, `opSeq`, `datasetVersion`.

### 5.5 Enquiry routes — LA-10

| Endpoint | Purpose |
|---|---|
| `POST /inv/enquiries` | create; returns id + pinned Dataset version |
| `POST /inv/enquiries/{id}/ops` | append one op; returns the Working Set delta + `truncated` |
| `POST /inv/enquiries/{id}/replay` | full evaluation from `seed`; verification and evidence |
| `GET /inv/enquiries/{id}/log` | the ordered op log, renderable as plain-language steps |

---

## 6. Acceptance gates — falsifiable, house style

### 6.1 Baseline (hold today; re-check on every change)

| Id | Gate | State |
|---|---|---|
| G-B1 | Edge `count` equals the row count of the pair | ✅ `ControlApiInvProjectionTest` |
| G-B2 | A projection over the limit returns `truncated: true` **and the analyst sees it** | ✅ backend · ⚠ SPA half = LA-02 |
| G-B3 | With the module absent, no entry point is reachable and probing returns 503 | ✅ pinned |
| G-B4 | An algorithm that cannot run says so explicitly | ✅ by throwing — not graceful (D-S3) |
| G-B5 | No projection can be coerced into arbitrary SQL | ✅ regex + bind params |
| G-B6 | Four export formats, GraphML for interchange | ✅ |

### 6.2 Roadmap gates

| Id | Item | Criteria | Test |
|---|---|---|---|
| G-R1 | LA-01 | a time-window filter changes `count`, not just membership; the `OR` group implements either-endpoint; unknown `field` is 422; injection on `field` and a `contains` operand lands nowhere; stage-1 edge set equals stage-2 for the same tree and rows | real-HTTP test in `inspecto-geo-link` + translator unit + parity spec |
| G-R2 | LA-07 | betweenness and Louvain on 10 000 nodes / 50 000 edges keep the UI above 55 FPS, main thread idle > 90 % | `graph-worker.spec.ts` |
| G-R3 | LA-08 | three tables join into one graph with correct provenance and zero orphaned edges | `MultiProjectionContractTest` |
| G-R4 | LA-11 | 5-hop search over 1 000 000 edges in < 350 ms and the depth fence holds | `RecursiveTraversalEngineTest` |
| G-R5 | LA-14 | 100 % of synthetic smurfing chains found; out-of-order timestamps rejected | `pattern-engine.spec.ts` |
| G-R6 | LA-12 | the exported bundle embeds a verifiable SHA-256 manifest of nodes, edges and score vectors | `GraphDossierBuilderTest` |
| G-R7 | LA-05 | toggling labels or recolouring an edge kind does not call `graph.destroy()`; node positions survive | `graph-view` spec |

### 6.3 Enquiry-model gates (all need LA-16 first)

| Id | Gate |
|---|---|
| G-E1 | **Order is respected.** `exclude(m) → expand(3)` and `expand(3) → exclude(m)` produce different Working Sets; the difference is exactly the entities reachable only through `m`. |
| G-E2 | **Replay reproduces.** Full replay against the pinned version yields byte-identical membership to the incremental evaluation. |
| G-E3 | **Replay diverges when it should.** The same log against a later version is detected and reported, never silently served. |
| G-E4 | **Hide is not exclude.** A hidden hub still yields downstream entities on the next `expand`; an excluded one does not. |
| G-E5 | **Thresholds respect the window.** 50 lifetime contacts but 2 in-window fails `minDegree: 3`. |
| G-E6 | **The midnight-crossing window is correct.** 22:00–04:00 includes 23:30 and 01:30 next day under a stated timezone. |
| G-E7 | **Truncation reaches the analyst.** A rung over budget sets `truncated` **and** the SPA displays it. |
| G-E8 | **Nothing is silently arbitrary.** Every entity traces to the op that admitted it and the seed it descends from. |
| G-E9 | **No op can be coerced into arbitrary SQL.** The vocabulary compiles through `SAFE_IDENT` + bind params, pinned as `InvRoutes` is. |
| G-E10 | **A narrative cannot omit an exclusion.** A log with `exclude` ops renders every excluded set, count and reason code in all three renderings — pinned by a test that adds one and asserts it appears in each. |
| G-E11 | **An Evidence Widget does not move.** Reopened after growth it matches the sealed Artifact; a Monitoring Widget shows new figures **and** drift. ⚠ Fails outright without version-addressable reads — which is why D-E3 is a precondition. |
| G-E12 | **A template carries the method, not the case.** Re-bound to new seeds it yields a structurally identical log and no analyst-judgement exclusion set (D-E8). |

---

## 7. Corrections and lessons carried forward

Recorded so no reader re-derives them from the archived documents.

1. **The 2026-09-17 audit table was wrong in five places**: it invented toolbox tabs (`Structural`,
   `Filtering`, `Clustering`, `Temporal`, `Geospatial`, `Timeline`, **`Case Board`**, `Metrics` — none exist,
   and `Case Board` implied a Case UI that GAP 5 said was absent); they are accordion groups, not tabs;
   "freezes above 2 000 nodes" understated a **throw**; "single-dataset projection" is true of the endpoint,
   not the feature; the relationship-inference citation had drifted (`:83-137`, not `:188-254`).
2. **"25+ algorithms across 13 tabs"** → 27 wired functions of 34 exports across 13 groups.
3. **"Decoupled Geo and Link"** overstated it — a one-way handoff exists; synchronisation is what is missing.
4. **The two-stage filter loop needed three new pieces** → grounding found two of the three already live
   (`ConditionSql.predicate()` with three production consumers and a live-DuckDB parity test; top-N sampling
   by `cnt DESC`). Only the `filter` field is new. Plan claims in this repo go stale fast — re-ground.
5. **"The sample is an arbitrary first 2 000 groups"** → it is top-N by edge weight, the sample you want.
6. **"G6 rendering is the bottleneck; WebGL is the fix"** → the bottleneck is full teardown and relayout on
   every input change (§1.6.4). Measure before adding a renderer.
7. **A well-tested evaluator was dead while the live path had none** (`evaluateRows`, `query-eval.spec.ts`):
   reviving it must turn mirror coverage into live coverage.
8. **A filter-bag model produces a plausible wrong chart** in a criminal matter (§2.3). No UI polish surfaces
   it; only the ordered log does.

---

## References

- Backend: `inspecto-geo-link/src/main/java/com/gamma/geolink/InvRoutes.java`
- SPA: `inspecto-ui/src/app/modules/admin/studio/link-analysis/`, `inspecto-ui/src/app/inspecto/graph/`
- As-built mechanism: [`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md)
- Edition gating: [`../EDITIONS.md`](../EDITIONS.md) §CP-09
- Vocabulary: [`../GLOSSARY.md`](../GLOSSARY.md) §11 graph lock; `GLOSSARY-CASE-1`
- Capability rows: [`../okf/capabilities/studio/studio.md`](../okf/capabilities/studio/studio.md) §2 `INV-1`..`INV-4`
- Scale-out (version-addressable reads): [`enterprise-scale-out-plan.md`](enterprise-scale-out-plan.md)
- Archived sources (provenance only): [`link-analysis-spec.md`](../archived-documents/plans-archive/link-analysis-spec.md) ·
  [`link-analysis-advancement-plan.md`](../archived-documents/plans-archive/link-analysis-advancement-plan.md) ·
  [`link-analysis-enquiry-model-plan.md`](../archived-documents/plans-archive/link-analysis-enquiry-model-plan.md)
