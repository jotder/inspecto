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

### 1.7 Measured limits (2026-09-22, post-LA-05, Chromium on the dev host)

Synthetic scale-free-ish graphs (hubs + pendants, ~3 edges per node) driven through the real component and
the real layout, timed to `graph.rendered`.

🔴 **One host, one browser, one synthetic shape — so the NUMBERS DO NOT TRANSFER** (operator, 2026-09-22).
An analyst's laptop, a dense graph, or a different edge-to-node ratio all move them. ⇒ **The finding is the
SHAPE of the curve, and the conclusion is that the limit must be CONFIGURABLE with a measured default —
not a constant compiled into the SPA.** A single hardcoded 500 encodes the machine it was measured on.

**Layout — the binding constraint.** Default layered layout (`dagre`):

| Nodes | Edges | Time to rendered |
|---|---|---|
| 500 | 1 498 | **0.9 s** |
| 750 | 2 247 | **10.7 s** |
| 1 000 | 2 999 | **16.2 s** |

🔴 **That is a cliff between 500 and 750, not a slope** — 12× the time for 1.5× the nodes. The force
layout costs ~2.4× the layered one at 500 (2.1 s). ⇒ `PROJECTION_NODE_CAP = 500` is **well chosen**, and
raising it without changing the default layout would put the studio over ten seconds on a routine query.

**Analysis — not the constraint, with one exception.** At **1 999 nodes / 5 993 edges** (just under
`ANALYSIS_NODE_CAP`): centrality **2 ms** · communities **58 ms** · cut points **14 ms** · similarity **0 ms**
· link prediction **352 ms** · spanning forest **5 ms** · connected components **4 ms**.

~~🔴 **Suspicion score is the sole outlier: 6.7 s at 1 999 nodes**… It, not the other 26, is what
`ANALYSIS_NODE_CAP` should be sized for.~~

🔴 **CORRECTED 2026-09-23 — THE OUTLIER IS BETWEENNESS, AND THIS SWEEP NEVER MEASURED IT.** The row above
reads *centrality 2 ms*, but "centrality" was timed with the **default metric (degree)**; the toolbox's
dropdown offers seven, and `betweennessCentrality` sits behind the same control. Measured per COMPONENT,
median of 3, ~3 edges per node:

| algorithm | 500 | 1 000 | 2 000 |
|---|---|---|---|
| **betweennessCentrality** | **425 ms** | **2 930 ms** | **9 757 ms** |
| pageRank | 13 ms | 39 ms | 61 ms |
| kCore | 6 ms | 17 ms | 60 ms |
| triangleCount | 2 ms | 4 ms | 6 ms |
| degreeCentrality | 0.8 ms | 1.0 ms | 2.1 ms |
| *suspicionScore (the blend)* | *492 ms* | *2 286 ms* | *9 359 ms* |

⇒ **Suspicion score is betweenness plus noise** — the other four components together cost **129 ms at
2 000 nodes**. It was never the outlier; it was the only thing measured that HAPPENED TO CALL the outlier,
so betweenness's cost hid inside the blend and a decision was taken on the wrong number.

⛔ **The consequence was user-facing, not academic.** D-S3 gave *suspicion score* the lower cap while
`betweennessCentrality` kept the shared 2 000 ceiling — so choosing **"Betweenness"** from the centrality
list on a 2 000-node graph froze the main thread for about **ten seconds**, which is the exact freeze D-S3
believed it had removed. **The cap was guarding the caller, not the cause.** Fixed 2026-09-23: betweenness
now guards with the low cap, pinned by a spec.

⚠ **The lesson for every future sweep here:** a control that offers seven algorithms must be timed for
each of them, not once with its default. A per-feature timing table hides a per-option cliff.

⚠ **The plan asks for the cap to be "enforced gracefully, never an exception"; today it THROWS.** That half
of D-S3 is unmet regardless of which number is chosen.

---

### 1.6 Structural consequences

1. **Re-projection on every call** — cost scales with usage; two analysts on one Dataset pay twice; and a
   view re-opened after the Dataset changed silently shows a different graph. **A saved view is not
   evidence.** The most consequential gap in this document.
2. **All analysis is client-side** — supported graph size is bounded by one browser tab.
3. **Nodes have no identity** — centrality and community results are only as meaningful as raw column
   values, and nothing warns the analyst.
4. ~~**The actual slowness is not G6.**~~ ✅ **FIXED 2026-09-22 by LA-05 — this paragraph described a
   defect that no longer exists.** It read: `rebuild()` unconditionally `graph.destroy()` → `new Graph`
   → `render()` on **any** `@Input` change, every bound input being a `computed()` returning a new
   reference, so toggling labels reran the whole layout synchronously; zero uses of `setData`.
   `GraphViewComponent` now classifies the change by VALUE and repaints without touching layout —
   measured at **0 of 170 nodes moved** on a label toggle, same G6 instance.
   🔴 **This has a consequence for LA-06.** The culling and progressive-load clauses were queued against
   *this* diagnosis, which named LA-05 as the cause and explicitly said the canvas was **not** the
   bottleneck. With the named cause removed, those clauses rest on no measurement at all. ⇒ **Re-measure
   before building either** — CLAUDE.md §2 forbids speculative work, and an optimisation with no profile
   behind it is exactly that. The super-node-aggregation clause is a different animal: it is a
   *legibility* feature (a hairball reads as nothing), not a performance one, and does not depend on this.

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
- **Controls, non-optional in a criminal matter:** scope binding · per-query audit trail (✅ **SHIPPED 2026-09-22, LA-04** — `InvRoutes`/`GeoRoutes` now emit `link.projected`, `link.expanded`,
  `link.schema.inspected`, `geo.projected` and `geo.routes.projected`, each carrying the dataset, the result
  size and `truncated`, best-effort so an audit failure can never fail the analyst's query. ~~never called
  from `InvRoutes` / `GeoRoutes`~~ — struck; that was true at grounding and stopped being true the same day.
  ⚠ Exclusion, reveal and export remain **client-side** and so are still unaudited) ·
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

✅ **Second pass, 2026-09-22 — every gate-free item taken.** `LA-09`, `LA-14a` and `LA-15` shipped.
🔴 **Grounding moved three items OUT of the gate-free set**, and the honest result is that the set was
smaller than the register claimed:

- **`LA-22` is NOT gate-free.** Its scope assumes a bounding box on the map can isolate graph nodes, but
  **no shared identity exists between a `GeoPoint` and a `G6Node`.** The one existing bridge
  (`coLocationGraph`) re-derives a synthetic id as `` `entity:${label}` `` from a *display string*; nothing
  guarantees that matches the projection's own node ids. Separately, **MapLibre has no box-select** — adding
  one is a new dependency, not wiring. Two decisions, filed as **D-U3** and **D-U4**.
  ⚠ The row also overstates today's state: there is no route-level hand-off at all, only a preview dialog
  that happens to reuse the same G6 host.
- **`LA-16` is NOT gate-free either.** `postmed_xdr` landed on 2026-09-22, *after* this plan's 2026-09-20
  grounding, and already carries A-party, B-party, start time, duration, cell and link kind — missing only
  device, explicit direction, a timezone contract, seeding and a planted story. Building a fourth telecom
  feed beside it would duplicate ~80 %. ⚠ And a peer worktree is actively building `postmed_xdr` right now,
  so this is a collision risk as well as a scope question. Filed as **D-U2**.
- **`LA-06` was already gated and its row did not say so.** §4's `D-U1` names LA-06 in its *Blocks* column
  while the row's *Blocked on* cell read `—`. The row is corrected. Its "published render limit" clause
  additionally depends on `D-S3`'s number. ⛔ **A register whose two halves disagree is how an item gets
  picked up as free work** — the *Blocks* column is the one to trust, because it is where a decision is
  written down.

✅ **`LA-06` is now disposed of clause by clause (2026-09-22)** — it was never one item:

| Clause | Verdict |
|---|---|
| Viewport culling | ⛔ **REFUSED — premature.** `PROJECTION_NODE_CAP` is **500**; culling is a technique for 10–100k elements. G6 v5.1.1 has **no** built-in cull (no `cull`/`visible`/`viewport` style option anywhere in the typings), so it would be hand-rolled, and 🔴 the obvious implementation — filtering `this.data` — re-introduces the exact LA-05 regression: `stableKey` would read it as a data change and run a full layout **on every pan**. |
| Progressive load ("heaviest edges first") | ⛔ **REFUSED — already true, and the rest is pointless.** The server sorts `ORDER BY cnt DESC` and every downstream transform is an order-preserving `filter`, so the edge array reaching the canvas **is already in weight order** — which is also why truncation keeps the heaviest pairs and the footer's "top-N by count" is accurate. What remains is chunking a ≤500-node `setData`, a single-digit-millisecond call. No chunking pattern exists in the SPA to copy; it would be net-new work for no measurable gain. |
| Super-node aggregation | ✅ **SHIPPED.** See below. |
| Published render limit in the footer | ✅ **ALREADY SHIPPED** — `caps = {projection, analysis}` is printed in the footer. Only the *number* awaits **D-S3**. |

🔴 **Why the two refusals are the honest answer:** §1.6(4) diagnosed the slowness as the
rebuild-on-any-input and said in terms that the canvas was **not** the bottleneck. **LA-05 removed that
cause on 2026-09-22.** So both clauses now rest on no measurement at all, and CLAUDE.md §2 forbids
speculative work. The remaining real ceiling is **compute, not render**: all 27 algorithms run on the
browser main thread and `ANALYSIS_NODE_CAP` *throws* above 2 000 rather than degrading (that is LA-07/D-S3).
⚠ If LA-06 is ever reopened, two **registered** G6 built-ins cost one line each and are the cheap
starting point: `optimize-viewport-transform` (hides non-node shapes during pan/zoom) and
`auto-adapt-label` (viewport-aware, degree-sorted labels — strictly better than today's all-or-nothing
`LOD_LABEL_CAP`). Neither is free work today: both sit under D-U1, and the second under D-S3.

**Super-node aggregation, as shipped.** A hub's **pendant** leaves — nodes whose only link is to that hub
— fold into one stand-in labelled with the count, once there are at least the threshold. It is a
**legibility** transform, not a performance one: two hundred accounts each touching a single hub draw as a
hairball, and the one fact worth reading (*this hub has two hundred one-hop counterparties*) is exactly
what the hairball hides. Opt-in and off by default, because it changes what the canvas MEANS.
⛔ **Only true pendants fold**, so no path through the graph is ever removed.
🔴 **A stand-in never carries an `objectRef`** — otherwise an analyst could "open the record" of a
stand-in for 200 accounts and be shown one real account. Pinned by a spec that goes red when the
transform is mutated to inherit a member's reference.
⚠ It is excluded from the legend and kind tallies (it has no real kind), and the working-set tiles are
measured on the **pre-aggregation** graph — measured live: canvas 159 marks, tiles **170 entities**. The
tiles answer "how much am I looking at", never "how many shapes are on the canvas".

⚠ **`LA-14` is split.** `LA-14a` (temporal ordering on the existing linear matcher) shipped; `LA-14b`
(branching runtime + SQL compiler + structuring pack) is the unavoidably large half and is blocked in
practice by `LA-16`, because a timestamp attribute column joins the projection's `GROUP BY` fold key and
de-folds the graph — so client-side temporal matching only holds at demo cardinality. As written, the
small win that makes two shipped packs honest was trapped behind a two-week rewrite it does not need.

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
| **LA-03** | `POST|GET /inv/snapshots` + `POST /inv/snapshots/attach` | ✅ **SHIPPED 2026-09-23 — backend AND SPA** | S–M | — | Serialise sub-graph, scores, positions, viewport, annotations, predicate, origin, pinned Dataset version; persist as an Artifact anchored to `opSeq`; swap `LinkAnalysisSnapshotsService.add/attach`. Contract §5.4. |
| **LA-07** | Web Worker computation (`graph-worker.ts`, `GraphAnalysisClient`) | ⬜ **RE-SCOPE OR CLOSE — the premise fell 2026-09-23** | M → **S at most** | — (D-S3 answered) | 🔴 **“Move the 27 algorithms off the main thread” is the wrong shape: ONE is slow.** Measured per component, betweenness is 9 757 ms at 2 000 nodes and every other algorithm combined is **129 ms**. A worker boundary costs a serialised 500-node graph each way, so routing 26 sub-60 ms functions through it is a net LOSS. ⚠ And the case has shrunk further: betweenness now respects the 750 cap (**425 ms measured**), so the freeze LA-07 was drafted against no longer exists. What remains is ~400 ms of responsiveness against net-new build config (`tsconfig.worker.json`, `webWorkerTsConfig`), a message protocol and cancellation. *Recommended reading: close it, or re-scope to betweenness alone and only if an operator reports the 425 ms as felt.* | Move the 27 algorithms off the main thread; zero-copy `ArrayBuffer` transfer; `PROGRESS` messages; `AbortController` cancellation. ⚠ Fixes responsiveness only — the cap stays until D-S3 states a graceful published number. |
| **LA-08** | `POST /inv/projection/multi` | 🟡 | M | — (D-S4 decided: normalise + warn) | Node mappings + edge projections across Datasets in one call; unified DuckDB union views; `__provenance_dataset` tagging. Contract §5.2. 🟡 **SERVER SHIPPED 2026-09-23** (`InvRoutes.projectMulti`, gate G-R3 `MultiProjectionContractTest`); **SPA not wired** — follow-up. As built: one query per mapping (cap 16), union assembled in the response, not a DuckDB view; `limit` per mapping, `truncated` if any hit it; values RAW (D-S4 — the SPA normalises). ⛔ One Dataset the caller cannot view → the WHOLE call is 404, no partial union. The top-level `filter` applies to every edge mapping and must name columns each one has (so §5.2's example is a 422 for `ownership_links`); an edge mapping's own `filter` narrows only it. Body field `space` is ignored — the request's space scope applies, as on `/inv/projection`. |
| ~~**LA-06**~~ | ~~Rendering at scale~~ — **CLOSED 2026-09-22 (D-U1)** | ✅ clause 3 SHIPPED · ⛔ clauses 1–2 REFUSED · ✅ clause 4 already shipped | M | — (D-S3 answered) | Viewport culling, progressive load (heaviest edges first), super-node aggregation of low-degree leaves above a threshold, published render limit in the footer. **WebGL renderer only after measuring** — `package.json:33` installs `@antv/g6` alone, no `g6-plugin-webgl` / `layout-gpu`, and §1.6(4) says the canvas is not the bottleneck. |
| **LA-09** | View toolbox completions | ✅ **SHIPPED 2026-09-22** (5 of 12; 4 refused, 3 gated — see below) | S | — | Add Fruchterman, combo force, fishbone, dendrogram layouts; remaining G6 v5 plugins (timebar, bubble sets, combos, edge bundling, context menu, snapline, history, watermark). Each is a G6 id, not an engine. |

### 3.3 Phase 2 — the object, the ladder, the clock (Sprint 10)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **LA-10** | Enquiry object + ordered op log + incremental evaluator | ⬜ | L | D-E1, D-E2, D-E3 | `POST /inv/enquiries`, `/ops`, `/replay`, `GET /log` (§5.5). Ops `seed`, `expand` (one hop over `neighbors`), `exclude`, `hide`, `keep`; real undo; replaces the mock snapshot store. *Delivers prune-then-expand — the scenario's blocking step.* |
| **LA-11** | Server-side multi-hop traversal `POST /inv/traversal/recursive-paths` | ✅ **BACKEND SHIPPED 2026-09-23** — all four fences enforced in-recursion and tested with positive twins (`ControlApiInvTraversalTest` 14/14); body names follow `/inv/projection` (`dataset`/`sourceCol`/`targetCol`), not §5.3; SPA wiring and the G-R4 perf gate still open | L | — (D-S2 answered: server-side recursive CTE) | DuckDB recursive CTE; fences — max depth (default 6), timeout (5 000 ms), max edge yield; the primitive `expand` compiles to. Contract §5.3. Working Set materialised so pruning does not re-query; pre-aggregated contact-pair Dataset (A, B, window, count, duration, value) as substrate, raw records on drill-down. |
| **LA-13** | Hop ladder + time model | ⬜ | L | LA-10, LA-11 | Per-rung fields §2.4; absolute range + midnight-crossing intraday window + **timezone contract**; in-window thresholds; `truncated` per rung. *Delivers the motivating scenario end to end.* |
| **LA-14a** | Temporal ordering on the linear matcher | ✅ **SHIPPED 2026-09-22** | S | — |
| **LA-14b** | Branching pattern runtime (`BranchingPatternEngine.ts` + `PatternQueryCompiler.java`) + the structuring pack | ⬜ | L | LA-16 in practice | `BranchingPatternEngine.ts` + `PatternQueryCompiler.java`; JSON motif schema (multi-branch, attribute constraints, `t₂ > t₁`, `DELTA ≤ 48h`); fix `layering-chain` / `pass-through` to require temporal order; ship structuring / layering / circular-financing packs. |
| **LA-15** | `POST /inv/schema/overlap-profile` | ✅ **SHIPPED 2026-09-22** | M | — | Cardinality and Jaccard across candidate key columns via `APPROX_COUNT_DISTINCT`; surfaces implicit foreign keys. |
| **LA-16** | Synthetic call-records Dataset | ✅ **SHIPPED 2026-09-23** — `postmed_xdr` extended, not a fourth feed | S | — (D-U2 answered) | ⚠ No call-records Dataset exists — `roaming_tap` is operator↔operator, `mule_transfers` account↔account; neither carries A/B-party, duration, device or cell. Prerequisite for every §6.3 gate. |

✅ **LA-11's three fences, grounded 2026-09-23 — and the plan had their costs the wrong way round.**
Insertion point is a new route in `InvRoutes` beside `project`/`neighbors`, reusing that file's
validated-identifier → server-built-SQL → `QueryExecutor.Request` pipeline unchanged.

| Fence | Verdict |
|---|---|
| **timeout** | 🔴 **CHEAPEST, not the expensive one.** The mechanism already ships: `SqlSandbox.statement()`/`preparedStatement()` call `setQueryTimeout`, defaulting to **30 s** via `-Dassist.sql.timeout_seconds`. ⚠ It is JVM-wide, because `QueryExecutor.run` hardcodes `SqlSandboxPolicy.defaultPolicy()` — so the contract's 5 000 ms needs a policy-carrying `Request` field or a `run(Request, policy)` overload. **Plumbing, not invention.** ⇒ the plan's "fences have no equivalent today" is wrong for this one. |
| **max depth** | ✅ **SETTLED 2026-09-23 — write it as a BOUND PARAMETER.** A JDBC `?` survives inside a recursive member through the wrap-and-prepare path, and a bound value cannot smuggle SQL; both pinned by `QueryExecutorRecursiveCteTest` (5/5). ⇒ the caller's depth never becomes statement text, so the validate-and-clamp-then-inline fallback (the `limit` pattern) is **not needed**. D-S2's original probe had only ever used a literal, which left a security property unproven rather than proven. |
| **max edge yield** | ⛔ **The genuinely new one.** Nothing in `QueryExecutor`/`SqlSandbox`/`SqlGuard` bounds rows INSIDE a recursion; the outer `LIMIT n+1` is a paging device, proven not to bound the walk. Hand-written SQL shape. |

⚠ **`SqlGuard` is MOOT on this path** — nothing in `InvRoutes` calls it. Identifiers are validated by
`SAFE_IDENT` and the SQL is server-built, exactly as `project` does, so the guard's admission of `with`
(recorded under D-S2) is true of `QueryExecutor` generally and irrelevant here.
⚠ **`QueryExecutor.run` never calls `sandbox.seal()`**, so file access stays enabled for every dataset
query. A route that stays purely server-built inherits `project`'s posture; one that accepted
caller-shaped SQL would not.
⚠ **No existing route anywhere is recursive or long-running**, so there is no fence pattern to copy.

### 3.4 Phase 3 — identity, value, evidence, detection (Sprint 11+)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **LA-17** | Entity model + resolution + reference lists | ⬜ | L | D-S4, D-E8 | Typed Entities, cross-identifier resolution, enrichment attributes; named reference lists that persist across Enquiries. |
| **LA-18** | Value measures as Decision Rules | 🟡 | M | LA-17 | §2.6 list; visible thresholds; structuring must survive the ≥ 5 000 filter trap. |
| **LA-19** | Evidential controls | ⬜ | L | LA-10 | Scope binding, minimisation, four-eyes, retention/purge, per-entity annotation, **coverage indicator**. |
| **LA-20** | Working Set as a log-defined derived relation + cache | ⬜ | L | D-E3, D-E7 | §2.7; the cache is a functional requirement (six tiles = six re-runs per view). |
| **LA-21** | Evidence / Monitoring Widgets | ⬜ | M | LA-20, D-E6 | Pinned default; kind on the tile; drift line. |
| **LA-12** | Dossier + three renderings + chain of custody | ⬜ | L | LA-10 | `GraphDossierBuilder.java`: summary, topology, centrality/risk tables, chronological ledger, SHA-256 manifest (replaces the FNV-1a fingerprint); JSON / numbered steps / method statement; **negative space in all three**. |
| **LA-22** | Synchronised Geo ↔ Link brushing | ✅ **SHIPPED 2026-09-23 (brush half)** — `geo-link-brush.ts` (`GeoLinkBrushService` + `nodeIdsForKeys`/`pointIdsForNodes`, joined only via `entityId()`); polygon/point on the map → node emphasis, node click → point emphasis; unkeyed points never brush. ⚠ **Re-grounded:** the ident param, SELECT column, wire types and `foldServerResult` had already shipped in `beb0170b`, so the remaining work was the emitter + consumers only. **Deferred:** split-pane mode, graph path → map route tracing. | ~~S–M~~ → **M** — 🔴 **I under-sized this and the correction matters.** The geometry half does ship, but `geo-projection.ts`'s own header records the projection as **backend-first since Phase 4**, so changing `projectPoints`/`coLocations` alone is **cosmetic in production** — `GeoRoutes.java` builds the points server-side and is the load-bearing half, needing a new ident param and SELECT column. ⚠ And the “selection event” is **net-new plumbing**: `geo-map.component.ts`'s displayed set has no `@Output` at all, so today the polygon is a DISPLAY FILTER that no other pane can hear. ~9 touchpoints (type, form, wire types, `foldServerResult`, Java route, `CoLocation`, the emitter). ⚠ `coLocations` also requires `p.label` truthy to participate, so a point with no entity column never co-locates — the same trap will apply to a key unless a fallback is decided. | — (D-U3 answered · D-U4 dissolved) | `GeoLinkSyncService.ts`: bounding-box on map isolates nodes; path on graph traces the route; split-pane mode. |
| **LA-23** | Enquiry Templates → Measure → Alert Rule → Incident | ⬜ | M | LA-20, D-E8 | Cheap once LA-20 lands; every downstream noun ships. |

---

## 4. Decision register — owed operator calls

⛔ **None may be answered by an implementer in passing.** Ids: D-S* inherited from the spec, D-E* from the
Enquiry model, D-U* raised during build.

✅ **GROUNDED 2026-09-22 — every row below now carries evidence, not just a question.** The grounding is
the implementer's half: what is TRUE in the code today, which options the evidence **rules out**, and what
each survivor costs. ⛔ **The choice itself is still owed and NONE is marked answered.** Where a row says
*Recommended*, that is a reading of the evidence, not a decision.

🔴 **Three premises in the pre-grounding register did NOT survive verification.** They are struck
in place rather than quietly corrected, because a register that silently repairs itself teaches nobody:

1. The naming decision was believed to be constrained by the canonical-vocabulary guard. **It is not** —
   `tools/check-vocabulary.mjs` has eleven rules and none touches these words. D-E1 is a GLOSSARY-policy
   call enforced by review, not a CI gate.
2. `GLOSSARY-CASE-1` reserves *Investigation* on the stated grounds that it "renames four published
   routes". **`v3.11.0` contains no `AgentRoutes` and no `/agent/cases` at all** — "published" there means
   *registered in the control API*, not *shipped*. The gate's breakage set is empty.
3. §1.2 described the projected node id as `entity:<value>` with the type merely namespacing. **A
   type-scoped form `entity:<entityType>:<value>` already ships** for multi-mapping merges. §1.7 now
   carries the measured limits that replace the guessed ones.

| Id | Decision | Blocks | Grounding — what is true, what is ruled out, what each option costs |
|---|---|---|---|
| ~~**D-S1**~~ | ~~Is a saved view evidence?~~ | ~~LA-03~~ | ✅ **ANSWERED 2026-09-22 (operator).** No — settled by the stored shape — and LA-03 builds a durable snapshot store by **generalising `RunArtifactStore`**: append-only JSONL per id, no size ceiling, already this repo's artifact mechanism. ⚠ Two mechanical changes: widen its `runId` key (structural, but no architectural blocker) and make the class public. ⛔ **`ComponentStore` was rejected on SEMANTICS, not size** — it is a single OVERWRITABLE document per id, and evidence must never be silently replaced. ⇒ **LA-03 is unblocked.** | ✅ **The evidence settles the factual half.** A saved view stores the **query**, never the result (`link-analysis.service.ts:14-28`, codec `:61-72`: `query` + presentation only, no nodes/edges/metrics), and **no version pinning exists for a DATASET** (`DatasetRelation.relationSql:50-96` takes exactly `{view}` or `{physicalRef}` — no version, asOf or generation parameter). 🔴 **CORRECTED 2026-09-22 — “zero hits for as-of/time-travel” is FALSE.** `ReferenceReader.versionedView:110-120` implements **real point-in-time reads**: an append-only `load: scd2` **Reference** carries `__valid_from`/`__key_hash`/`__op`, and `EnrichmentConfig.Reference.asOf:90-92` cuts the candidate set with `WHERE __valid_from <= TIMESTAMP '<asOf>'` before picking the winning version per key. It is not reachable from `DatasetRelation` and Dataset stores lack the SCD2 columns, so the Dataset-specific conclusion stands — **but version-addressability is not unimaginable here, it is SHIPPED, and building it for Datasets means copying an in-repo pattern rather than inventing one**. ⇒ reopening re-runs the projection against live data, so ⛔ **"yes, a saved view is evidence" is ruled out by the stored shape** — it would require materialising results (a different object) or version-addressable reads (which do not exist). The SPA already says so on screen: *"Saved view only · **not evidence** — re-projects live, may change"* (`link-analysis-evidence.dialogs.ts:188-191`). ⚠ But that label lives **only** in the Attach-to-Case dialog — nothing warns on the saved-view list, on load or on reopen. ⇒ The real question left is not *yes/no* but **what LA-03 must build**: a durable snapshot store, since `LinkAnalysisSnapshotsService` is a bare in-memory `signal<GraphSnapshot[]>`, lost on reload (its own JSDoc says *“MOCK PERSISTENCE… gone on reload”*, and nothing in it touches `HttpClient`). ✅ **SIZED 2026-09-22 — smaller than “M” suggests.** The snapshot's CONTENT MODEL is already ~80 % of §5.4: subgraph (materialised), scores, predicate, annotations and origin all ship; only **positions**, a **true `{zoom, pan}` viewport** and the **Dataset version** are missing. The builder is already a pure, framework-free, spec-covered module (`snapshotGraph`, `verifySnapshot`). ⇒ **LA-03 is mostly a persistence swap.** ⚠ ~**250–300 KB** per snapshot at the 500-node cap — a document, not a blob, but larger than anything `ComponentStore` holds. ✅ `RunArtifactStore` is the nearest reusable mechanism (append-only JSONL per id, no size ceiling); its `runId` is structural but widening it to another key is **small and mechanical, with no architectural blocker** — it must also be made public. |
| ~~**D-S2**~~ | ~~Where does multi-hop traversal run?~~ | ~~LA-11~~ | ✅ **ANSWERED 2026-09-22 (operator): server-side recursive CTE**, on the evidence of the check this row demanded (`QueryExecutorRecursiveCteTest`, 3/3). ⇒ **LA-11 is UNBLOCKED.** ⛔ Its fences — max depth, timeout, max edge yield — must be expressed **INSIDE the recursion**: the executor's `LIMIT n+1` sits outside the derived table and truncates an answer the walk has already paid for, so it bounds the RESULT and never the WORK. On a cyclic graph the in-recursion depth predicate is the only thing preventing an unbounded walk. ⚠ Two costs stand as grounded: per-rung sandbox setup (every `run` opens a fresh sandbox), and the fences themselves, which have no equivalent today — both are things LA-10/D-E2 need anyway. — original grounding follows: Today: **server does one hop only** (`/inv/projection/neighbors`, one `GROUP BY` per call); all multi-hop is browser-side. ⛔ **"Worker + raised cap" does not answer this question** — it relocates client compute and leaves traversal bounded by `PROJECTION_NODE_CAP` 500 and one tab; it is a D-S3/LA-07 answer. **Recursive CTE is not blocked** by the engine (DuckDB 1.5.2.1), by `SqlGuard` (the pattern is named **`STARTS_READONLY`**, not `ACCEPT_START`; it admits `with`, and `RECURSIVE` is not a blocked keyword), or by the call shape (`QueryExecutor.Request` already carries SQL + binds). Its real costs are two things LA-10/D-E2 need anyway: **fences** (max depth, timeout, max edge yield have **no equivalent today** — only `LIMIT n+1`) and **connection lifetime** (every `run` opens a fresh sandbox, so each rung re-pays setup). ~~⚠ **One untested seam:** `QueryExecutor.wrap()` puts the caller's SQL inside `SELECT … FROM (<sql>) AS "__q"`, so a `WITH RECURSIVE` body lands in a derived table — DuckDB should accept it, but nothing here exercises it. **Require that one-hour empirical check before treating this option as free.**~~ ✅ **CHECK RUN 2026-09-22 — the seam HOLDS.** `QueryExecutorRecursiveCteTest` (3/3 green) pins three facts against real DuckDB through the unmodified `run()` path: a recursive CTE **survives the derived-table wrap**; a **depth-fenced multi-hop walk over an edge relation is expressible** in the shape LA-11 would compile; and 🔴 **the outer `LIMIT n+1` does NOT bound the recursion** — it is applied outside the derived table, so it truncates an answer the walk has **already paid for**. ⇒ the fence must live INSIDE the recursion, which makes D-S2's “fences are a real cost” concrete rather than theoretical: on a cyclic graph the in-recursion depth predicate is the only thing standing between the query and an unbounded walk. ⚠ The probe pins a **capability of the seam, not a shipped feature** — no production code emits a recursive CTE today. ⚠ Also observed: `run()` never calls `sandbox.seal()`, so this path runs with file access still enabled; irrelevant to the CTE question, relevant to LA-11's threat model. |
| ~~**D-S3**~~ | ~~What is the supported graph size?~~ | ~~LA-07, LA-06~~ | ✅ **ANSWERED 2026-09-22 (operator): keep the measured 500 / 2 000, and give suspicion score its OWN lower cap — SHIPPED at 750.** 🔴 **The number is measured, not chosen.** Suspicion score was benchmarked across the range (median of 3, ~3 edges per node): **250 → 103 ms · 500 → 402 ms · 750 → 972 ms · 1 000 → 1 608 ms · 1 500 → 3 774 ms · 2 000 → 7 277 ms**. The curve is **quadratic** — betweenness dominates, and doubling the nodes costs ~4.5× the time — so the cap is far more sensitive than a linear one: halving it from 2 000 to 1 000 cuts the work to roughly a QUARTER. **750 is the last MEASURED point under one second** (the interpolated crossing is ~760, and a default should be a number someone actually observed). ⚠ Per the 2026-09-22 operator correction it ships as a **per-space DEFAULT**, not a truth — one host, one browser, one synthetic shape. ⚠ It is a THIRD field (`suspicionNodeCap`), independent of the shared cap, because sizing one limit for 27 algorithms means either 25 fast ones are throttled or one slow one defines the experience. — original grounding follows: ✅ **MEASURED — see §1.7.** The constraint is **layout, not analysis**: default layered layout is **0.9 s at 500 nodes but 10.7 s at 750** (a cliff, 12× for 1.5× the nodes) and 16.2 s at 1 000. Meanwhile **25 of 27 algorithms stay under 60 ms even at 1 999 nodes**; the sole outlier is **suspicion score at 6.7 s**. ⇒ `PROJECTION_NODE_CAP` **500 is well chosen** and cannot rise without changing the default layout; `ANALYSIS_NODE_CAP` **2 000 is sized for the wrong algorithm**. ~~⚠ **The "enforced gracefully, never an exception" half is unmet whichever number wins** — `requireUnderCap` **throws** (`graph-analysis.ts:985-988`), applied across eight algorithms plus two inline copies.~~ 🔴 **STRUCK 2026-09-22 — the premise does not survive verification.** The line cite was stale (`:1021-1026`), but the substance is the problem: the throw is **caught at all ten sites**, the catch **clears the stale result** (`ranking.set([])`, `communities.set([])`), and the message renders in an `<inspecto-alert variant="warning">` as *“Betweenness is capped at 2000 nodes (graph has 2500).”* ⇒ the analyst already gets a **named refusal naming the cap, the actual size and the algorithm**, with nothing stale left on screen. What is exception-based is the *mechanism*, which is invisible to them. ⛔ **Converting the ten sites to an outcome value is REFUSED as churn** — it changes no analyst-visible behaviour, and CLAUDE.md §2 forbids it. ⇒ **What D-S3 still owes is the NUMBER, and nothing else.** ✅ **The CONFIGURABILITY half is SHIPPED 2026-09-22** — both caps are now per-space settings (`GET|PUT /settings/link-analysis`), the measured values are the defaults, `null` inherits, a bad persisted value is a 422 naming the field and range, and the client setters ignore nonsense so a misconfiguration cannot switch the toolbox off. ⇒ **What is still owed is only the NUMBER** (see the struck clause above — the refusal behaviour is already met). 🔴 Why it had to be configurable (operator, 2026-09-22): the measurement is a property of the host and of the data's shape, so any single value is someone else's wrong answer. *Recommended reading: ship the measured numbers as DEFAULTS, make them settable, and give suspicion score its own lower default.* ~~replace the throw with a named refusal~~ — struck: the named refusal already reaches the analyst. |
| ~~**D-S4**~~ | ~~First-class node model, or stay value-projected?~~ | ~~LA-08~~, LA-17 | ✅ **DECIDED 2026-09-23 (operator): NORMALISE + WARN — stay value-projected.** One shared `entityId()` folds case, whitespace and trailing punctuation, used by **all three** mint sites (both projection paths **and** `coLocationGraph`, `geo-analysis.ts:366`); the split-identity notice stays. The fixture's split of 3 per name column must read 0 after normalisation. ⇒ **LA-08 unblocked.** The full entity model (LA-17) stays deferred, not refused. — *History:* 🟡 **ANSWERED 2026-09-22 (operator): EVIDENCE FIRST — plant a dirty fixture, then decide.** The model is neither built nor refused; the corpus is made capable of settling it. ✅ **SHIPPED the same day**: story (e) in `gen-link-analysis-demos.py` spells **Cinder Wireless four ways** across partners while its PLMN `00103` never varies — how real interconnect feeds behave, since the PLMN is the contract key and the name is free text. Measured after: `SENDER_NAME` 16 raw → 13 normalised, `RECIPIENT_NAME` 17 → 14, **split 3** on each. ⚠ Row counts and every other planted story are **byte-identical** (the speller draws from its own RNG), so (a)–(d) are undisturbed. ⛔ Pinned by `tools/check-split-identity-fixture.mjs` in pre-push, falsified before wiring: the pre-fixture corpus fails with `SPLIT 0` naming both columns. ⇒ **The remaining choice — build the entity model or stay value-projected with the warning — is STILL OWED, but it can now be argued from a number instead of a hypothetical.** | ⛔ **"The id scheme is too entrenched to change" is ruled out.** ~~Only **two** production sites mint an entity id (`entity-projection.ts:41` and, separately, `geo-analysis.ts:366`)~~ 🔴 **CORRECTED 2026-09-22 — the cite was wrong and the count was low.** `entity-projection.ts:41` is `PROJECTION_NODE_CAP_DEFAULT`; the mint is **`entityId()` at `:71-73`**, shared by both projection paths. And there are **THREE** mint sites, not two — `geo-analysis.ts:366`'s `coLocationGraph` is a third that does not even `trim()`, so it is **looser than the two this row costed**. ⛔ A normalisation answer that touches only the named pair stays half-fixed. **Zero** sites parse or destructure an id — `G6Node.id` is opaque to every consumer. Per the repo's no-back-compat rule the id is cheap to change. ✅ **The cost of staying value-projected is concrete, not theoretical**: the id is the trimmed raw string with no case fold, alias or normalisation, so `ACME Ltd` and `Acme Ltd.` are **two nodes with two degree counts and two community memberships** — and **nothing in the UI warns** (verified: the only analysis messages are outcome strings). Compounding: the projection truncates at 500 **before** any centrality runs, so a ranking is computed over a top-500 sample of a possibly-split identity space. ⇒ **Staying value-projected is coherent only if paired with a stated warning**; without one it is a silent correctness claim. 🔴 **MEASURED 2026-09-22 — the split is UNEVIDENCED in every dataset this repo ships.** Across **24 candidate entity columns in 4 spaces**, including all five configured projection columns (`PAYER_ACCOUNT`/`PAYEE_ACCOUNT`, `SENDER_NAME`/`RECIPIENT_NAME`, `IMSI`), distinct-raw equals distinct-normalised **exactly**: split count **0**, and **zero** untrimmed values, **zero** internal double-spaces, **zero** trailing punctuation. ⚠ **The probe is trustworthy** — a positive control through the same normaliser collapsed 6 raw spellings of `ACME Ltd` to 2 (split 4), so it found nothing because there is nothing. **Cause is structural**: `gen-link-analysis-demos.py` emits every entity from a canonical literal list, so a variant spelling is impossible by construction. ⇒ **This reframes the decision.** The failure stays *structurally* possible (the path is trim-only, confirmed at `entity-projection.ts:140`), but **an entity model cannot be justified by observed damage, and staying value-projected cannot be falsified, on this corpus.** ⚠ It also means the shipped split-identity notice reads **0 on every demo graph** — correct behaviour, not a broken feature. *A cheap third option: a deliberately dirty fixture would turn D-S4 from a judgement call into a measurable one.* Building the model is genuinely L and mostly net-new (no entity registry exists — verified: every `resolve`/`canonical`/`alias`/`dedup` hit in ~250 Java files is config, path or file-level, never business identity). ⚠ **The register OVERSTATES the seams**: `objectRef` is a UI-side pointer to an existing Case/Incident (its type enum is `INCIDENT|CASE`), and the Catalog `MetadataNode` is **not a seam to inherit but a precedent to re-implement from** — different domain, no shared base type, no coupling. The one genuinely reusable *shape* is `DbFileStageStore`'s append-only log, because an entity registry accrues facts over time. ⚠ **D-S4, D-U3 and LA-17's exclusion lists are the same missing object** — decide them together or sequence them. |
| ~~**D-S5**~~ | ~~Bind or escape the pushed-down predicate?~~ | ~~LA-01~~ | ✅ **ANSWERED 2026-09-22 (operator): (a)** — reuse `ConditionSql` and validate every `field` against the relation's actual columns. Shipped; an unknown field is 422 before the renderer is called. The bind-emitting variant stays an explicit follow-on. |
| **D-E1** | What is the object called? | every LA-10+ touchpoint — **all of Phase 2** | ✅ **ANSWERED 2026-09-22 (operator): _Investigation_ / _Investigation Template_.** The collision below is resolved in this object's favour — **`GLOSSARY-CASE-1` retargets the RCA sense**, which is the side that moves more easily because that rename is unstarted and its breakage set is empty. ⚠ **One sub-question remains and is NOT an implementer's to answer: which noun the Assistant's RCA run takes instead.** Until it has one, `GLOSSARY-CASE-1` cannot be rewritten and *Investigation* cannot be entered in GLOSSARY §13 without briefly naming two concepts. — original collision, kept for provenance: ⛔ `GLOSSARY.md` §13 already allocates **Investigation** as the rename TARGET for a different concept: `GLOSSARY-CASE-1` moves the Assistant's RCA run (`com.gamma.intelligence.investigation.Case`) to *Investigation*, with touchpoints `CaseStore` → `InvestigationStore`, `cases.jsonl`, and four `/agent/cases` routes. ⚠ **What failed verification was that gate's COST claim, not its reservation** — `v3.11.0` has no `AgentRoutes`, so the rename is cheaper than filed; the word is still spoken for. ⇒ Applying this answer as-is would put **one word on two concepts**, which GLOSSARY §0 rule 2 forbids. **Resolve first:** either `GLOSSARY-CASE-1` retargets the RCA sense to another noun, or the Link Analysis object takes the runner-up. ⚠ Separately, the *namespace* `investigation` is taken in two trees (`com.gamma.intelligence.investigation`, `inspecto-ui/.../investigation/`), so even after the collision is settled the new symbols need a home that does not shadow them. | 🔴 **The guard does not constrain this** (see premise 1 above); the binding constraint is GLOSSARY §0 rules 1–3, enforced by review. ⛔ **Ruled out by evidence:** `Case` (taken twice — `ObjectType.CASE` and `com.gamma.intelligence.investigation.Case` — and §2.1 makes the new object a *child* of a Case), `Dossier` (allocated by §2.7 to the LA-12 narrative), `Working Set` (it is object #2 of three, with shipped code), `Report` (refused by §2.7), `Analysis` (the toolbox and both studios already carry the word). ✅ **Genuinely open:** **Enquiry** (zero code symbols; already the placeholder in five docs and the §5.5 route names; compounds as *Enquiry Template*) · **Inquiry** (free; US spelling — picking it means the glossary bans the other) · **Investigation** (free as an identifier, and the code already implies it: routes are `/inv/*`, the capability is `INV-1`, `InvRoutes.java:32` spells it out, and an `inspecto-ui/.../investigation/` folder exists — but the *namespace* is taken in two trees and the word is reserved by `GLOSSARY-CASE-1`, whose premise fails verification per above) · **Study** (free). ⚠ **Cost is near zero whichever wins**: LA-10 is unbuilt, so there are **no existing symbols to rename** — a new component kind is ~3 registry lines plus the hand-mirrored SPA unions. ⚠ **The answer must be a Type/Instance PAIR** (§2.7 needs *X Template* and *X*), not a single noun. |
| ~~**D-E2**~~ | ~~Where does a Working Set live?~~ | ~~LA-03, LA-10~~ | ✅ **ANSWERED 2026-09-22 (operator): the SAME durable store as snapshots.** The op log and the Working Set it evaluates to persist together, because durability is what makes replay and evidence possible at all — and one mechanism beats two half-built stores with different semantics. ⛔ The DuckDB temp relation stays ruled out on evidence (no connection survives an HTTP request). | ⛔ **"DuckDB temp relation" is RULED OUT on evidence.** `QueryExecutor.run` opens `SqlSandbox` in try-with-resources and closes it at the end of that one call — **no connection survives an HTTP request**, so a temp relation cannot persist across the ops that build a Working Set. ✅ Surviving options are **in-memory per session** (what the SPA mock does today) and **a durable store**, and the plan's own "durability is what makes replay and evidence possible" points at the latter. ⚠ **"Artifact" is aspirational** — no general Artifact store exists; the only concrete one is the package-private, run-scoped `RunArtifactStore` (append-only JSONL under `<space>/audit/artifacts/`), keyed by runId not `opSeq`. The closest reusable *shape* is `DbFileStageStore` (DuckDB-JDBC, insert-only, unique-key + `ON CONFLICT DO NOTHING`) — a log, which is what an op sequence is, unlike `ComponentStore`'s single overwritable document. |
| ~~**D-E3**~~ | ~~Dataset version pinned at creation, or per op?~~ | ~~LA-10, LA-20, G-E11~~ | ✅ **ANSWERED 2026-09-22 (operator): SEAL NOW, defer versioned reads.** Materialise and fingerprint — which satisfies **`G-E11`** and **`G-E3`** today — and record the Dataset name and read time as **weak provenance, explicitly NOT a replay pin**. ⛔ **`G-E2`'s byte-identical replay is deliberately deferred**, and it is the only gate that genuinely needs versioned Dataset reads. ⛔ File-list provenance was considered and **rejected**: `ConsignmentSelector` can pin the list, but compaction moves files to `COMPACTED_AWAY`, so the record would name files maintenance may delete — a pin that rots is worse than no pin, because it looks like one. ⚠ When replay is wanted, `ReferenceReader`'s shipped SCD2 `asOf` is the in-repo pattern to copy, not a net-new invention. | ⚠ **Entangled with D-S1 and blocked by the same absence**: there is **no version-addressable read anywhere in the backend**, so *neither* option is implementable today without first building one. ⇒ Until then, "sealing" evidence necessarily means **materialising the subgraph** — which is exactly what the client-side `GraphSnapshot` already does. Decide D-S1/LA-03's store first; this decision is downstream of it, not parallel to it.

🔴 **GROUNDED 2026-09-22 — provenance can be RECORDED but it ROTS, and that is decisive.** `ConsignmentSelector:29-52` can enumerate and pin the exact file list a read saw at that instant, so “record what I read” is buildable. But Dataset partitions are **not append-only**: `DerivedTableWriter:35-38` moves files `LIVE → SUPERSEDED → COMPACTED_AWAY`, and `MaintenanceJob:97-103` states that `compact` merges per-batch Parquet files and that **reprocess of a compacted-away batch is no longer supported**. ⇒ a recorded file list names files that maintenance may delete. **Neither replay nor durable file-provenance is available for Datasets today.**

✅ **But the three evidence gates DO NOT all need it — only one does:**
* **`G-E11`** (*an Evidence Widget does not move*) — ✅ **satisfiable TODAY by materialisation.** A sealed Artifact that stores frozen `nodes`/`edges` cannot move when reopened, by construction. ⛔ The gate's note *“fails outright without version-addressable reads”* is **wrong for the frozen half**; it holds only for the Monitoring Widget's drift line, which needs a re-read, not a versioned one.
* **`G-E3`** (*divergence detected, never silently served*) — ✅ **satisfiable TODAY**: re-run the query and compare the content fingerprint (`manifestHash` already covers `{nodes, edges, metrics, predicate, origin}`). Detecting that the answer CHANGED needs no version pin; only explaining *to what* does.
* **`G-E2`** (*replay reproduces byte-identical membership against the pinned version*) — ⛔ **genuinely needs version-addressable reads**, and is the only one that does.

⇒ **This reframes D-E3's cost**: sealing and drift-detection are reachable without it; byte-identical replay is the single capability that requires building versioned Dataset reads — for which `ReferenceReader`'s SCD2 `asOf` is the in-repo precedent to copy. |
| ~~**D-E4**~~ | ~~Re-ordering the log invalidates Artifacts, or forks the Enquiry?~~ | ~~LA-10~~ | ✅ **DECIDED 2026-09-23 (operator): FORK.** Re-ordering creates a new branch of the Investigation; Artifacts stay valid against the log they were derived from. The forked log lives in the D-E2 store. — *History:* Not code-grounded — nothing exists to ground against (LA-10 unbuilt). The plan's reading stands: forking is safer and costs a branching model. ⚠ Depends on D-E1 only for naming, and on D-E2 for where the forked log would live. |
| ~~**D-E6**~~ | ~~Saved Widget frozen or live by default; may a live one leave the Space?~~ | ~~LA-21~~ (with LA-20) | ✅ **DECIDED 2026-09-23 (operator): FROZEN by default.** Live is opt-in, shows its kind on the tile and a drift line; a live Widget obeys the D-E7 rule and **cannot leave the Space**. — *History:* Not code-grounded. §2.7 recommends frozen. ⚠ The "may it leave the Space" half is **not** a Widget question — it is D-E7's scope question wearing different clothes; answer them together. |
| ~~**D-E7**~~ | ~~Who may evaluate an Enquiry's derived relation?~~ | ~~LA-20~~ | ✅ **DECIDED 2026-09-23 (operator): PolicyEngine on Enterprise; OWNER-ONLY on Professional (fail closed).** Enterprise enforces Case scope through `inspecto-policy`'s ABAC PDP; where `AccessDeciders.active()` is empty, only the Investigation's owner may evaluate its derived relation — never the dataset-sharing fallback. To be stated in the edition matrix when LA-20 ships. — *History:* 🔴 **The premise is worse than the plan states.** "The Case's scope" is **not a thing the query path consults at all**: `QueryExecutor.run` takes no Subject and is identity-blind, and the dashboard tile path (`BiRoutes.biQuery`) checks only `ComponentAccess.canView(ex, dataset)` — dataset sharing, never row or Case scope. Row-level scoping **does** exist (`RowScope.visible` + the `AccessDecider` SPI) but is opt-in per route and is wired **only** into Ops object CRUD, never into BI. ⇒ the side channel the plan fears is not hypothetical; it is the default. ✅ **Reuse, do not invent**: `inspecto-policy`'s `PolicyEngine` is a real ABAC PDP (deny-overrides, fail-closed, seeded space isolation) and is the natural mechanism. ⚠ **But it is Enterprise-only** — on Personal/Standard `AccessDeciders.active()` is empty and `RowScope.visible` returns `true` always, so reusing it means **no Case-scope enforcement below Enterprise**. If Link Analysis ships below Enterprise, that is the decision. |
| ~~**D-E8**~~ | ~~Does an Enquiry Template carry its exclusion lists?~~ | ~~LA-17, LA-23~~ | ✅ **ANSWERED 2026-09-22 (operator): named reference lists TRAVEL with the template; an analyst's ad-hoc exclusions DO NOT.** A curated watchlist is method and belongs to the template; a judgement call about one graph belongs to the one Investigation that made it. Consistent with gate `G-E12`. ⚠ **Wholly greenfield** — verified 2026-09-22 that no persisted named list exists anywhere, and that `exclude` does not exist as an operation at all (today's only affordance is *Collapse branch*, session-only UI state with no reason code). ⚠ A **persistent** named list still needs stable entity identity, so delivery sequences behind D-S4's remaining half. | ✅ **VERIFIED 2026-09-22 — the claim HOLDS, it is not an undercount.** No persisted named list of entity ids exists anywhere (the only `SuppressionList` is email-domain send suppression; the only "block-list" is a config mapping syntax). 🔴 **And `exclude` does not exist as an operation at all** — today's only affordance is *Collapse branch* (`link-analysis.component.ts:1101`), which hides downstream nodes as **session-only UI state** with no reason code and no persistence. So the §2.2 `exclude`/`excludeBy` vocabulary is wholly prospective: this decision is greenfield, and its cost is the whole feature, not an extension of one. Plan's reading: named reference lists travel, analyst-judgement sets do not. ⚠ Depends on D-S4: a *persistent* exclusion list needs stable entity identity, which value-projected ids do not provide. |
| ~~**D-U1**~~ | ~~Rendering item: own item (LA-06) or fold into LA-07?~~ | ~~LA-06~~ | ✅ **CLOSED 2026-09-22 (operator).** LA-06 was disposed clause by clause — super-node folding shipped, viewport culling and progressive load refused as premature, the published render limit already shipped — so there was no unbuilt work left to file either way. ⚠ If the projection cap ever rises, the cheap starting point is still the two registered G6 built-ins named below. — original grounding follows: ⚠ **Largely moot since 2026-09-22.** LA-06 was disposed clause by clause: super-node aggregation **shipped**, viewport culling and progressive load **refused as premature** (with reasons, §3.2), and the published render limit **was already shipped**. ⇒ there is **no unbuilt LA-06 work left to file either way**, except the limit's *number*, which is D-S3's. *Recommended reading: close it, or keep it only as a placeholder should the cap ever rise.* |
| **D-U2** | Extend `postmed_xdr` into the call-records Dataset, or build a fourth feed? | LA-16 — and LA-14b, LA-11 through it | `postmed_xdr` landed 2026-09-22, **after** this plan's grounding, and already carries A-party, B-party, start, duration, cell and kind; it lacks device, explicit direction, a timezone contract, `seed-inbox.{ps1,sh}` entries (**both** files — they come in a POSIX/PowerShell pair) and a planted investigative story. ⇒ **Recommended: extend it** — a fourth feed duplicates ~80 % and splits the demo. ~~⚠ **A peer worktree is mid-build on `postmed_xdr`**; reconcile with that work before touching it.~~ ✅ **STRUCK 2026-09-22 — it LANDED** (`7ab01e0d`, 8 files) and nothing has touched it since; the tree is clean for all of it, so the collision risk is gone.

✅ **RE-GROUNDED 2026-09-22 — the cost is now SMALLER than this row states, and two of its five gaps are obsolete.** Actual columns (`postmed_xdr_schema.toon:6-23`, 18 fields) cover A-party (`MSISDN`), B-party (`OTHER_PARTY`), start (`START_AT`), duration (`DURATION_SEC`), cell (`CELL_ID`) and kind (`REC_TYPE`). **The remaining gap is exactly three columns** — `IMEI` (device; `IMSI` is a subscriber, not a handset), an explicit **DIRECTION** (`roaming_tap` emits MOC/MTC, postmed has none, so A→B vs B→A is unrecoverable), and a **stated timezone contract** (zero `utc`/`tz` occurrences; `START_AT` is naive local, and DuckDB's session TimeZone is the HOST). ⚠ Plus `OTHER_PARTY` is `'N/A'` on every DATA row, so ~30 % of rows carry no edge. ⛔ **`seed-inbox.{ps1,sh}` is no longer part of the cost**: `c5bd0f36` shipped `tools/seed-samples.mjs`, which DERIVES the seed list from each Pipeline's own `dirs.poll` “never from a hand-kept list”, and postmed_xdr is seeded today with zero edits. ✅ **And `spaces/demo/data/postmed_xdr/database/` being empty is NORMAL, not a misconfiguration** — every ingesting Pipeline declares the identical `<name>/database` shape, and an empty `database/` is what they all look like before their first run. 🔴 **Still missing: a planted investigative story.** The generator emits uniform random traffic with a fresh subscriber PER ROW, so the graph is ~1 200 disconnected edges — its only plants are data-QUALITY defects, which is a pipeline-robustness story, not an investigative one. ⇒ **extending stays the recommendation, and is now one generator + one schema file: no Java, no new Pipeline, no dataset registration.** ⚠ Committed sample data **is** permitted here — `.gitignore` carves out `!/spaces/*/data/samples/**` — and both existing generators state the invariant: every value invented, nothing trimmed from a capture. |
| **D-U3** | What identity ties a `GeoPoint` to a Link Analysis node? | LA-22 | ✅ **ANSWERED 2026-09-22 (operator): thread a real key column** — an `entityIdCol` mapping carried through `GeoProjection` → `projectPoints` → `coLocationGraph`, NOT the label and NOT `GeoPoint.id`. ✅ Feasible today because `projectPoints` already stores `attrs: row`, the entire source row (`geo-projection.ts:82`). ⇒ **LA-22 no longer waits on LA-17.** ⛔ Still true and still the trap: `GeoPoint.id` is `` `pt:${i}` ``, a positional index regenerated per run — never an identity. ⚠ `D-U4` (a map box-select dependency) remains open and is what LA-22 now waits on. | **There is none today.** The one bridge (`coLocationGraph`) re-derives `` `entity:${name}` `` from a **display string**, which can collide and need not match the projection's node ids. ⛔ **Do not adopt that key by default** — it would silently isolate the wrong nodes, which in an investigative tool is a wrong answer presented as a finding. ~~⇒ This is **the same missing object as D-S4**; sequencing LA-22 behind LA-17 costs nothing and removes the question.~~ 🔴 **REFUTED 2026-09-22.** `projectPoints` stores **`attrs: row` — the ENTIRE source row** (`geo-projection.ts:82`), so a stable key is already in memory at mapper time and a bridge is a column-mapping addition (an `entityIdCol` threaded through `GeoProjection` → `projectPoints` → `coLocationGraph`), **not** the entity model. ⇒ sequencing LA-22 behind LA-17 costs a delay and buys nothing. ⛔ **`GeoPoint.id` is a DECOY** — it looks like a stable identifier and is `` `pt:${i}` ``, a positional row index regenerated every projection run (`geo-projection.ts:76`). Any identity design that leans on it is silently wrong. |
| ~~**D-U4**~~ | ~~Add a map box-select dependency?~~ | ~~LA-22~~ | 🔴 **DISSOLVED 2026-09-22 — there is no decision here, because the capability ALREADY SHIPS, twice.** `pointInPolygon` (`geo-analysis.ts:165`, ray-casting, zero deps, specced at `geo-analysis.spec.ts:147-163`) and `withinBBox` (`geo-analysis.ts:112`, a RECTANGLE filter) are both exported and tested, and the polygon tool already does the exact thing LA-22 asks for — `geo-map.component.ts:347-352` filters the point set by the ring **and prunes routes to the surviving ids**. `filterToView():590-593` is a rectangle affordance over the same `withinBBox`. ⇒ a drag-rectangle is a **convenience over an existing affordance**, not a missing capability: `mousedown/move/up` + `map.unproject()` into the already-exported `withinBBox`, tens of lines in one component. ⛔ **And the stated CI cost does not exist**: `tools/dependencies.lock` is **Maven-only** (`check-dependencies.mjs:74-76` diffs `mvn dependency:list`; zero references to npm, package-lock or node_modules), and `ci.yml` has no npm audit or license gate. ⇒ **LA-22 is re-gated on D-U3 alone — which is answered — so its remaining work is the `entityIdCol` identity bridge, not a draw tool.** *Precedent: `BACKLOG.md:414` (POI/XLSX) dissolved the same way.* | MapLibre GL JS has **no built-in rectangle draw**; today's map offers measure/radius/polygon/note only. A box-select means a new library (terra-draw, mapbox-gl-draw) or a hand-rolled overlay. ⚠ A dependency addition is an operator call and also touches `tools/dependencies.lock`, which CI diffs. |

⚠ **Sequencing that falls out of the grounding, offered as a reading and not a decision:** **D-E1** unblocks
the most (all of Phase 2) and costs least (nothing is built yet). **D-S1 → D-E3** are one thread, both
waiting on the same absent version-addressable read. **D-S4 → D-U3 → D-E8** are one missing object.
**D-E6's second half is D-E7.** That is fifteen questions resting on about five real choices.

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
  "subgraph": { "nodes": [ /* full G6Node objects */ ], "edges": [ /* full G6Edge objects */ ] },
  "scores": { "ACC-88122": { "betweenness": 0.884, "degree": 14 } },
  "viewport": { "zoom": 1.25, "pan": { "x": 420.5, "y": -118.0 } },
  "annotations": [ { "targetId": "ACC-88122", "text": "Intermediate shell entity with 98% pass-through velocity within 4 hours." } ]
}
```

Plus, once LA-10 exists: `enquiryId`, `opSeq`, `datasetVersion`.

🔴 **CORRECTED 2026-09-22 — this contract said `nodeIds`/`edgeIds`, and that was a REGRESSION.** The shipped client-side `GraphSnapshot` (`graph-snapshot.ts:14-33`) already stores **full `nodes` and `edges`** under the comment *“Frozen content”*, and fingerprints `{nodes, edges, metrics, predicate, origin}` into `manifestHash`. ⛔ **Storing ids only would give the new object the exact defect that makes a saved view not-evidence**: reopening would have to re-read labels, amounts and timestamps from live data, which without version-addressable reads may have changed. A reference is not a seal. ⇒ LA-03 must PERSIST what the SPA already materialises, never narrow it.

✅ **And the gap is smaller than “M” implies.** Measured 2026-09-22 against the shipped type: subgraph, scores (as `metrics`), predicate, annotations and origin are **already captured**; only **node positions**, a **true `{zoom, pan}` viewport** (today only `{layout}`) and the **Dataset version** are missing. The snapshot BUILDER is already a pure, framework-free, spec-covered module (`snapshotGraph`, `verifySnapshot`). ⇒ **LA-03 is mostly a persistence swap**, not a modelling exercise. ⚠ A snapshot at the 500-node cap serialises to roughly **250–300 KB** — a document, not a blob, but larger than anything `ComponentStore` holds today.

⚠ **Do not conflate with LA-12.** `manifestHash` is **FNV-1a 64 and explicitly not cryptographic**; the SHA-256 chain of custody is LA-12's separate swap.

✅ **AS BUILT 2026-09-22 (backend half).** Three routes on `InvRoutes`:
`POST /inv/snapshots` seals one, `GET /inv/snapshots?limit=n` lists ids newest-first with the TRUE total and a
`truncated` flag, and `POST /inv/snapshots/attach` records a Case link. Storage is one immutable JSON file per
id under `<space>/audit/snapshots/`, written with `CREATE_NEW`.

* ⛔ **A re-POST of an existing id is a 409, never an overwrite.** That is the object's purpose, not a
  technicality: a saved view is not evidence precisely because reopening re-projects live data, and a store
  that replaced its own records would inherit the same defect.
* 🔴 **Attachment never reopens the seal.** It appends to a separate `attachments.jsonl`, because writing
  into the snapshot would change its bytes and invalidate the `manifestHash` that makes it evidence. Pinned by
  a spec asserting the file is byte-identical after an attach.
* ⛔ **An empty serialisation is refused (500).** `JsonAttributes.toPayloadJson` is deliberately TOTAL and
  answers `"{}"` on failure — sealing that would store an empty record under a real id and report success.
* **Gated on `canManageIncidents`**, the same capability as `POST /objects`, because sealing evidence and
  attaching it to a Case is Case work. ⛔ The sibling `/inv/*` POSTs take the `read-shaped` exemption whose
  stated reason is *"persists nothing"*; these persist, so claiming it would be a false declaration.
* ⚠ **The capability is written as a string LITERAL at the registration site** — `CapabilityManifestTest`
  scans with a literal-only regex, so the constant reads as *"declared but not registered"* and fails the build.

⚠ **Two gaps carried deliberately, neither introduced here:**
1. 🔴 **The routes are NOT in `openapi-v1.json` and cannot be.** The contract test lives in `inspecto`, which
   has no dependency on the optional edition module `inspecto-geo-link`, so it never sees these routes — nor
   `/inv/schema/overlap-profile`, which shipped the same day and is also absent. The guard reports green
   because *live* means *what it can see*. Filed; the fix is edition-aware contract generation, not a
   dependency edge (that would invert the edition design).
2. **No 503 write-root test.** The harness always sets a write root, so that gate is unreachable from this
   test shape — as it is for the sibling `ControlApiInvProjectionTest`.

✅ **SPA swap SHIPPED 2026-09-23.** `add`/`attach` are **removed**, not kept alongside — leaving them would
leave a silent non-persisting path identical at the call site. `save`/`attachTo` return Observables and the
signal updates **only after the server confirms**; there is no optimistic path.

* ⛔ **A refused seal leaves the dialog OPEN**, with the title, description and Case choice still on screen
  and the reason rendered in place. Closing would discard the analyst's work while nothing was written.
* ⚠ **A failed ATTACHMENT deliberately does not fail the save** — by then the snapshot is sealed on disk, so
  reporting "not saved" would be a lie about evidence that exists. It closes with `attachedTo: []`. The
  attach-ONLY dialog does stay open, because there the attachment is the whole action.
* Two alerts promising "UI-first: kept for this browser session only" are gone; this change made them false.
* 🔴 **The compiler caught none of it.** Removing public methods from an injectable compiled clean; only
  running the specs found a caller doing `new LinkAnalysisSnapshotsService()` outside DI, which now throws
  NG0203 because the service injects `HttpClient`. **A DI change is invisible to type-checking.**

⚠ **Uncovered by the one spec that exercises this flow end to end.** `link-analysis.component.spec.ts` —
which contains *"evidence: snapshot freezes the displayed graph … and Attach to Case snapshots first"* — is
**28/28 RED on master** and was so before this change (verified by stashing). Every regression that file
guards is currently uncaught.

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
| G-E11 | **An Evidence Widget does not move.** Reopened after growth it matches the sealed Artifact; a Monitoring Widget shows new figures **and** drift. ~~⚠ Fails outright without version-addressable reads — which is why D-E3 is a precondition.~~ 🔴 **CORRECTED 2026-09-22 — wrong for the FROZEN half.** A sealed Artifact that materialises `nodes`/`edges` cannot move when reopened, by construction, so the Evidence Widget clause passes without any versioned read. Only the **Monitoring Widget's drift line** needs a re-read — and comparing the re-run's `manifestHash` against the sealed one detects drift too. ⇒ D-E3 is a precondition for `G-E2` (byte-identical replay), **not for this gate**. |
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
