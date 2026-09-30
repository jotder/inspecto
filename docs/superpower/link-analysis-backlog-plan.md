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
| Pattern matching with loadable packs (built-in + Space-authored, merged) | 🟡 | ~~structurally limited; no branching motifs, no temporal ordering~~ — temporal ordering shipped (LA-14a), branching motifs in the browser AND over the whole Dataset server-side (LA-14b, 2026-09-23) |
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
| Evidence | `inspecto/graph/graph-snapshot.ts` (canonical JSON + SHA-256 `manifestHash`, FNV-1a until 2026-09-28), Snapshot dialog, Attach to Case dialog; `LinkAnalysisSnapshotsService` is a **session-scoped mock store** | **LA-03** snapshots route · **LA-12** SHA-256 |
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
  agent; benefit-transfer patterns. Expressed as named **Measures** with visible thresholds, each threshold an
  **Alert Rule**, never an opaque score (operator 2026-09-30; was "Decision Rules" — definitions §2.6.1). 🔴 The demo already shows the failure: filtering `mule_large_transfers` to ≥ 5 000 makes the
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

#### 2.6.1 LA-18 value measures — definitions — ✅ DECIDED 2026-09-30 (operator sign-off) · backend BUILT 2026-09-30 · SPA BUILT 2026-09-30

Operator decision 2026-09-30: each value measure is a **named Measure**; its threshold is an **Alert Rule**
(the LA-23 path); both computed over the **whole Dataset**, never the analyst's view filter, so a view narrowed
to `AMOUNT ≥ 5 000` cannot remove what a measure looks for (as `PatternRoutes` does). Backend only, first slice.
The table below is the signed-off draft; the as-built record and its two recorded deviations follow it.

**Common column roles** (all bound per call, checked against real columns, values bound, never interpolated):
`sourceCol` (payer) · `targetCol` (payee) · `valueCol` (numeric; `TRY_CAST … AS DOUBLE`, non-numeric/NULL rows
excluded and counted) · `timeCol` (timestamp) · optional `kindCol` (link kind, e.g. `CHANNEL`).
**Cash-out link** = a link whose `kindCol` value is in a declared `cashOutKinds` set (no heuristic).
**Agent / till** = the payee of a cash-out link; optionally restricted to members of an Entity List whose
Entity Type is `agent` (LA-17 seeds `agent` in the per-Space Entity Types; values normalised with the list's
sealed normaliser, D-M9). Identity is value-projected (D-S4); LA-17 resolution is not applied in this slice.
**Window** = `[from, to)` on `timeCol`, required, capped (proposed ≤ 31 days); per-entity results; entities with
no qualifying activity are absent, not zero.

| Measure | Exact formula (per entity *e*, in window) | Extra roles | Proposed default threshold · why | Demo corpus |
|---|---|---|---|---|
| **value-weighted links** | per (source,target[,kind]) pair: `sum(value)`, `count(*)` | — | none (a weighting, not a test) | ✅ mule |
| **pass-through ratio** | `out(e) / in(e)` where `in = Σ value of links into e`, `out = Σ value of links out of e whose time ≥ the first inbound time`; only if `in(e) ≥ minInbound` | `minInbound` | `≥ 0.90` with `minInbound 10 000` — a mule forwards almost everything; the floor keeps tiny accounts out | ✅ mule: `MULE-HUB-01` ≈ 0.98, `RELAY-0x` ≈ 0.985 |
| **retention** | `1 − pass-through ratio` (same inputs) | as above | `≤ 0.10` — same rule, stated the other way; offer ONE of the two, not both (question) | ✅ mule |
| **velocity** | median over *e*'s inbound links of `Δt` to the next outbound link of *e* (hours) | — | `≤ 24 h` — funds leaving within a day of arriving | ✅ mule: hub→relay forwarded same evening, relay→offshore 1–3 h |
| **time-to-cash-out** | median over *e*'s inbound links of `Δt` to the next **cash-out** link out of *e* (hours) | `kindCol`, `cashOutKinds` | `≤ 48 h` — matches the LA-14b motif `DELTA ≤ 48h` | ❌ no cash-out channel (`cash_deposit` is cash-IN) — **needs a planted story** |
| **cash-out concentration by agent** | for agent *a*: `Σ cash-out value to a / Σ all cash-out value` in window; and the distinct-payer count into *a* | `kindCol`, `cashOutKinds`, optional agent Entity List | share `≥ 0.20` AND `≥ 5` distinct payers — one till taking a fifth of all cash-out from many accounts | ❌ **needs a planted story** (no agents in mule data) |
| **structuring** (already a pattern pack) | per payee: count and distinct payers of links with `min ≤ value < max` in window | band `min`,`max` | `900 ≤ value < 1 000`, `≥ 10` legs from `≥ 5` payers — the shipped pack's band | ✅ mule: 12 smurfs × 4/day into the hub |
| **benefit-transfer** | per recipient: links whose `kindCol` ∈ `benefitKinds` followed within `Δ` by an outbound link of ≥ `share` of that value to one counterparty (a payout skimmed on) | `kindCol`, `benefitKinds`, `Δ`, `share` | `Δ ≤ 72 h`, `share ≥ 0.50`, `≥ 3` recipients to the same counterparty | ❌ **needs a planted story**; ⚠ pattern least specified in §2.6 — definition is a proposal |

`postmed_xdr` carries no money transfers (`RATED_AMOUNT` is a charge), so it exercises none of these.

**Operator decisions (2026-09-30), replacing the four open questions:** (1) the Alert Rule watches the COUNT of
entities breaching the Measure's thresholds and fires when it is > 0; the answer lists the entities (G-42 kept);
(2) pass-through only — retention is a derived column (`1 − ratio`), not a Measure; (3) the planted stories are
added; (4) the 31-day window cap and the defaults above, editable per Alert Rule.

✅ **As built (backend, 2026-09-30):**
* `ValueMeasures` (`inspecto-geo-link`) — one SQL statement per Measure over the Dataset's OWN relation
  (`InvRoutes.relationFor`, R3); no filter is accepted, so the ≥ 5 000 view trap cannot apply. Identifiers checked
  against real columns and quoted; every value bound; 10 s timeout; ≤ 10 000 entities (`truncated`); rows with a
  non-numeric value are skipped and counted (`unvalued`). The answer carries the block with every default spelled
  out (`measure`) and the thresholds in words (`threshold`).
* `GET /inv/value-measures` (`ValueMeasureRoutes`) — `dataset, sourceCol, targetCol, linkKindCol?, name, valueCol,
  timeCol, from, to` + thresholds; read-only (a GET needs no capability-manifest entry), audited
  `LINK_VALUE_MEASURED`. `valueWeightedLinks` is readable here, never alertable.
* Alert Rule — the LA-23 route `POST /inv/investigations/{id}/alert-rules` also takes `{name, severity,
  valueMeasure:{…}}`: the Investigation supplies the Dataset and column roles; comparator/threshold are fixed
  (`gte 1`); the stored rule carries every threshold (`alert.valueMeasure`, declared in `ConfigSpecs`). The sweep
  reads the WHOLE Dataset now (not the sealed log) through the same owner binding; the `InvestigationMeasureProbe`
  SPI gained a `dataRoot`. One Alert per rule (`entities breaching <name>`), never per entity.
* Demo corpus — stories (f) cash-out through `TILL-06` and (g) benefit skim to `SKIMMER-01`, appended to
  `mule_transfers` from their own RNG and id sequence (`TXV-…`); every earlier row byte-identical (checked as a
  prefix of each file); `check-split-identity-fixture.mjs` still passes. On it: structuring → `MULE-HUB-01`;
  time-to-cash-out → the 8 CASHERs only; concentration → `TILL-06`; benefit-transfer → `SKIMMER-01` first.
* **Rolling windows + agent Entity List (2026-09-30, operator "take the still-open items"):** a block takes
  EXACTLY ONE of `from`+`to` or `last: <N>h|<N>d` (≤ 31 days; 422 otherwise). `last` is stored relative on the
  Alert Rule and resolved at EVALUATION time — every GET, bind and sweep — against the server clock in **UTC** to
  `[now − N, now)`, truncated to the second, so an armed rule never watches a stale window. Timezone contract: every
  value-measure statement now runs with the DuckDB session `TimeZone` set to `UTC` (never the host's); a
  `TIMESTAMPTZ` `timeCol` reads as UTC wall time, a naive one as it is (assumed UTC). The answer carries
  `window {from, to, timezone}`. `cashOutConcentration` takes an optional `agentList`: an Entity List of Entity Type
  `agent` (422 otherwise; unknown 404; retired 409), read LIVE at the fact log's head so an armed rule follows the
  list; the window's DISTINCT cash-out payees (≤ 10 000, more refused) are normalised in Java under the list's
  SEALED normaliser (D-M9), and only payees whose key is a live EXACT member are answered (range entries are not
  applied, as with `seedBy`). The share's denominator stays ALL cash-out. When the list renders masked, each agent
  is the list's own `EntityMasking` token. No new route; `alert.valueMeasure`'s description in `ConfigSpecs` states
  both keys.
* Tests: `ValueMeasuresTest` 15, `ControlApiValueMeasureTest` 9. Mutation-checked (2026-09-30): resolving `last`
  in the host zone, baking the resolved window into the stored rule, or ignoring the clock each turn tests red;
  so do dropping the agent restriction, comparing raw instead of normalised payees, and skipping the mask. Earlier: narrowing the evaluated rows to
  `≥ 5 000` turns 7 tests red (structuring loses `HUB`); a count off by one, or a `gt 1` comparator, stops
  the firing tests.

✅ **As built (SPA, 2026-09-30):** a **Value Measures** panel in the Link Analysis Investigation side pane
(`link-analysis-value-measures.component`, form + query in `value-measures.ts`). The analyst picks the Dataset and
the column roles (autocomplete: Datasets, then that Dataset's columns), the Measure, the window and the chosen
Measure's own thresholds — a blank threshold is sent as nothing, so the server's default applies. The answer shows
the thresholds in force in words and writes their values back into the fields (edit, run again); `truncated` and
`unvalued` are shown as counts, never hidden; passThrough's `retention` column is labelled *derived: 1 − ratio*. No
view filter is ever sent (pinned by a mutation-checked test). **Watch** posts `{name, valueMeasure, severity}` to the
open Investigation, sending the block exactly as the server answered it, and says it fires ONE Alert on the COUNT of
breaching entities. It is offered only when the result was computed over the Investigation's own Dataset and roles
(the bind uses those), and never for `valueWeightedLinks`. ⚠ The rolling `last` window and `agentList` from the
parallel backend lane are typed as optional on `ValueMeasureBlock` only; the form does not offer them yet. The
four-eyes thresholds and `mergedDistinctCap` got their first surface the same day: Settings ▸ Link Analysis.

⚠ **Recorded deviations (decided in-lane, for the operator to confirm):**
* ✅ **CONFIRMED 2026-09-30 (operator):** **velocity and time-to-cash-out also take `minInbound` (default 10 000)**, like pass-through. Without a floor
  every busy ordinary account forwards within a day, so velocity would name most of the corpus.
* ✅ **DECIDED 2026-09-30 (operator): benefit-transfer defaults to `≥ 5` recipients.** At the drafted `≥ 3` it also named 8 ordinary demo accounts (random traffic from 60 benefit recipients); at `≥ 5` only `SKIMMER-01`. `ValueMeasuresTest` pins both the default and the looser twin.
* **`G-E11`** (*an Evidence Widget does not move*) — ✅ **satisfiable TODAY by materialisation.** A sealed Artifact that stores frozen `nodes`/`edges` cannot move when reopened, by construction. ⛔ The gate's note *“fails outright without version-addressable reads”* is **wrong for the frozen half**; it holds only for the Monitoring Widget's drift line, which needs a re-read, not a versioned one.
* **`G-E3`** (*divergence detected, never silently served*) — ✅ **satisfiable TODAY**: re-run the query and compare the content fingerprint (`manifestHash` already covers `{nodes, edges, metrics, predicate, origin}`). Detecting that the answer CHANGED needs no version pin; only explaining *to what* does.
* **`G-E2`** (*replay reproduces byte-identical membership against the pinned version*) — ⛔ **genuinely needs version-addressable reads**, and is the only one that does.

⇒ **This reframes D-E3's cost**: sealing and drift-detection are reachable without it; byte-identical replay is the single capability that requires building versioned Dataset reads — for which `ReferenceReader`'s SCD2 `asOf` is the in-repo precedent to copy. |
| ~~**D-E4**~~ | ~~Re-ordering the log invalidates Artifacts, or forks the Enquiry?~~ | ~~LA-10~~ | ✅ **DECIDED 2026-09-23 (operator): FORK.** Re-ordering creates a new branch of the Investigation; Artifacts stay valid against the log they were derived from. The forked log lives in the D-E2 store. — *History:* Not code-grounded — nothing exists to ground against (LA-10 unbuilt). The plan's reading stands: forking is safer and costs a branching model. ⚠ Depends on D-E1 only for naming, and on D-E2 for where the forked log would live. |
| ~~**D-E6**~~ | ~~Saved Widget frozen or live by default; may a live one leave the Space?~~ | ~~LA-21~~ (with LA-20) | ✅ **DECIDED 2026-09-23 (operator): FROZEN by default.** Live is opt-in, shows its kind on the tile and a drift line; a live Widget obeys the D-E7 rule and **cannot leave the Space**. — *History:* Not code-grounded. §2.7 recommends frozen. ⚠ The "may it leave the Space" half is **not** a Widget question — it is D-E7's scope question wearing different clothes; answer them together. |
| ~~**D-E7**~~ | ~~Who may evaluate an Enquiry's derived relation?~~ | ~~LA-20~~ | ✅ **SCOPE CONFIRMED WIDE 2026-09-23 (operator):** a policy DENY guards EVERY Investigation route (log, ops, undo, reorder, replay, dossier, Working Set) via `InvestigationRoutes.open`, not the relation alone (`f6d801f8`). ⛔ Sharing beyond the owner is NOT granted by this decision — see LA-24. — ✅ **DECIDED 2026-09-23 (operator): PolicyEngine on Enterprise; OWNER-ONLY on Professional (fail closed).** Enterprise enforces Case scope through `inspecto-policy`'s ABAC PDP; where `AccessDeciders.active()` is empty, only the Investigation's owner may evaluate its derived relation — never the dataset-sharing fallback. To be stated in the edition matrix when LA-20 ships — ✅ **stated 2026-09-23** (`EDITIONS.md` CP-09; as built in §5.7). — *History:* 🔴 **The premise is worse than the plan states.** "The Case's scope" is **not a thing the query path consults at all**: `QueryExecutor.run` takes no Subject and is identity-blind, and the dashboard tile path (`BiRoutes.biQuery`) checks only `ComponentAccess.canView(ex, dataset)` — dataset sharing, never row or Case scope. Row-level scoping **does** exist (`RowScope.visible` + the `AccessDecider` SPI) but is opt-in per route and is wired **only** into Ops object CRUD, never into BI. ⇒ the side channel the plan fears is not hypothetical; it is the default. ✅ **Reuse, do not invent**: `inspecto-policy`'s `PolicyEngine` is a real ABAC PDP (deny-overrides, fail-closed, seeded space isolation) and is the natural mechanism. ⚠ **But it is Enterprise-only** — on Personal/Standard `AccessDeciders.active()` is empty and `RowScope.visible` returns `true` always, so reusing it means **no Case-scope enforcement below Enterprise**. If Link Analysis ships below Enterprise, that is the decision. |
| ~~**D-E8**~~ | ~~Does an Enquiry Template carry its exclusion lists?~~ | ~~LA-17, LA-23~~ | ✅ **ANSWERED 2026-09-22 (operator): named reference lists TRAVEL with the template; an analyst's ad-hoc exclusions DO NOT.** A curated watchlist is method and belongs to the template; a judgement call about one graph belongs to the one Investigation that made it. Consistent with gate `G-E12`. ⚠ **Wholly greenfield** — verified 2026-09-22 that no persisted named list exists anywhere, and that `exclude` does not exist as an operation at all (today's only affordance is *Collapse branch*, session-only UI state with no reason code). ⚠ A **persistent** named list still needs stable entity identity, so delivery sequences behind D-S4's remaining half. | ✅ **VERIFIED 2026-09-22 — the claim HOLDS, it is not an undercount.** No persisted named list of entity ids exists anywhere (the only `SuppressionList` is email-domain send suppression; the only "block-list" is a config mapping syntax). 🔴 **And `exclude` does not exist as an operation at all** — today's only affordance is *Collapse branch* (`link-analysis.component.ts:1101`), which hides downstream nodes as **session-only UI state** with no reason code and no persistence. So the §2.2 `exclude`/`excludeBy` vocabulary is wholly prospective: this decision is greenfield, and its cost is the whole feature, not an extension of one. Plan's reading: named reference lists travel, analyst-judgement sets do not. ⚠ Depends on D-S4: a *persistent* exclusion list needs stable entity identity, which value-projected ids do not provide. |
| ~~**D-U1**~~ | ~~Rendering item: own item (LA-06) or fold into LA-07?~~ | ~~LA-06~~ | ✅ **CLOSED 2026-09-22 (operator).** LA-06 was disposed clause by clause — super-node folding shipped, viewport culling and progressive load refused as premature, the published render limit already shipped — so there was no unbuilt work left to file either way. ⚠ If the projection cap ever rises, the cheap starting point is still the two registered G6 built-ins named below. — original grounding follows: ⚠ **Largely moot since 2026-09-22.** LA-06 was disposed clause by clause: super-node aggregation **shipped**, viewport culling and progressive load **refused as premature** (with reasons, §3.2), and the published render limit **was already shipped**. ⇒ there is **no unbuilt LA-06 work left to file either way**, except the limit's *number*, which is D-S3's. *Recommended reading: close it, or keep it only as a placeholder should the cap ever rise.* |
| **D-U2** | Extend `postmed_xdr` into the call-records Dataset, or build a fourth feed? | LA-16 — and LA-14b, LA-11 through it | `postmed_xdr` landed 2026-09-22, **after** this plan's grounding, and already carries A-party, B-party, start, duration, cell and kind; it lacks device, explicit direction, a timezone contract, `seed-inbox.{ps1,sh}` entries (**both** files — they come in a POSIX/PowerShell pair) and a planted investigative story. ⇒ **Recommended: extend it** — a fourth feed duplicates ~80 % and splits the demo. ~~⚠ **A peer worktree is mid-build on `postmed_xdr`**; reconcile with that work before touching it.~~ ✅ **STRUCK 2026-09-22 — it LANDED** (`7ab01e0d`, 8 files) and nothing has touched it since; the tree is clean for all of it, so the collision risk is gone.

✅ **RE-GROUNDED 2026-09-22 — the cost is now SMALLER than this row states, and two of its five gaps are obsolete.** Actual columns (`postmed_xdr_schema.toon:6-23`, 18 fields) cover A-party (`MSISDN`), B-party (`OTHER_PARTY`), start (`START_AT`), duration (`DURATION_SEC`), cell (`CELL_ID`) and kind (`REC_TYPE`). **The remaining gap is exactly three columns** — `IMEI` (device; `IMSI` is a subscriber, not a handset), an explicit **DIRECTION** (`roaming_tap` emits MOC/MTC, postmed has none, so A→B vs B→A is unrecoverable), and a **stated timezone contract** (zero `utc`/`tz` occurrences; `START_AT` is naive local, and DuckDB's session TimeZone is the HOST). ⚠ Plus `OTHER_PARTY` is `'N/A'` on every DATA row, so ~30 % of rows carry no edge. ⛔ **`seed-inbox.{ps1,sh}` is no longer part of the cost**: `c5bd0f36` shipped `tools/seed-samples.mjs`, which DERIVES the seed list from each Pipeline's own `dirs.poll` “never from a hand-kept list”, and postmed_xdr is seeded today with zero edits. ✅ **And `spaces/demo/data/postmed_xdr/database/` being empty is NORMAL, not a misconfiguration** — every ingesting Pipeline declares the identical `<name>/database` shape, and an empty `database/` is what they all look like before their first run. 🔴 **Still missing: a planted investigative story.** The generator emits uniform random traffic with a fresh subscriber PER ROW, so the graph is ~1 200 disconnected edges — its only plants are data-QUALITY defects, which is a pipeline-robustness story, not an investigative one. ⇒ **extending stays the recommendation, and is now one generator + one schema file: no Java, no new Pipeline, no dataset registration.** ⚠ Committed sample data **is** permitted here — `.gitignore` carves out `!/spaces/*/data/samples/**` — and both existing generators state the invariant: every value invented, nothing trimmed from a capture. |
| **D-U3** | What identity ties a `GeoPoint` to a Link Analysis node? | LA-22 | ✅ **ANSWERED 2026-09-22 (operator): thread a real key column** — an `entityIdCol` mapping carried through `GeoProjection` → `projectPoints` → `coLocationGraph`, NOT the label and NOT `GeoPoint.id`. ✅ Feasible today because `projectPoints` already stores `attrs: row`, the entire source row (`geo-projection.ts:82`). ⇒ **LA-22 no longer waits on LA-17.** ⛔ Still true and still the trap: `GeoPoint.id` is `` `pt:${i}` ``, a positional index regenerated per run — never an identity. ⚠ `D-U4` (a map box-select dependency) remains open and is what LA-22 now waits on. | **There is none today.** The one bridge (`coLocationGraph`) re-derives `` `entity:${name}` `` from a **display string**, which can collide and need not match the projection's node ids. ⛔ **Do not adopt that key by default** — it would silently isolate the wrong nodes, which in an investigative tool is a wrong answer presented as a finding. ~~⇒ This is **the same missing object as D-S4**; sequencing LA-22 behind LA-17 costs nothing and removes the question.~~ 🔴 **REFUTED 2026-09-22.** `projectPoints` stores **`attrs: row` — the ENTIRE source row** (`geo-projection.ts:82`), so a stable key is already in memory at mapper time and a bridge is a column-mapping addition (an `entityIdCol` threaded through `GeoProjection` → `projectPoints` → `coLocationGraph`), **not** the entity model. ⇒ sequencing LA-22 behind LA-17 costs a delay and buys nothing. ⛔ **`GeoPoint.id` is a DECOY** — it looks like a stable identifier and is `` `pt:${i}` ``, a positional row index regenerated every projection run (`geo-projection.ts:76`). Any identity design that leans on it is silently wrong. |
| ~~**D-U4**~~ | ~~Add a map box-select dependency?~~ | ~~LA-22~~ | 🔴 **DISSOLVED 2026-09-22 — there is no decision here, because the capability ALREADY SHIPS, twice.** `pointInPolygon` (`geo-analysis.ts:165`, ray-casting, zero deps, specced at `geo-analysis.spec.ts:147-163`) and `withinBBox` (`geo-analysis.ts:112`, a RECTANGLE filter) are both exported and tested, and the polygon tool already does the exact thing LA-22 asks for — `geo-map.component.ts:347-352` filters the point set by the ring **and prunes routes to the surviving ids**. `filterToView():590-593` is a rectangle affordance over the same `withinBBox`. ⇒ a drag-rectangle is a **convenience over an existing affordance**, not a missing capability: `mousedown/move/up` + `map.unproject()` into the already-exported `withinBBox`, tens of lines in one component. ⛔ **And the stated CI cost does not exist**: `tools/dependencies.lock` is **Maven-only** (`check-dependencies.mjs:74-76` diffs `mvn dependency:list`; zero references to npm, package-lock or node_modules), and `ci.yml` has no npm audit or license gate. ⇒ **LA-22 is re-gated on D-U3 alone — which is answered — so its remaining work is the `entityIdCol` identity bridge, not a draw tool.** *Precedent: `BACKLOG.md:414` (POI/XLSX) dissolved the same way.* | MapLibre GL JS has **no built-in rectangle draw**; today's map offers measure/radius/polygon/note only. A box-select means a new library (terra-draw, mapbox-gl-draw) or a hand-rolled overlay. ⚠ A dependency addition is an operator call and also touches `tools/dependencies.lock`, which CI diffs. |

**Raised by LA-19 (2026-09-24) — ✅ DECIDED 2026-09-24 (operator), four of five in full; built the same day:**

Supersedes an earlier 2026-09-24 entry (819958848) recorded in a parallel session; operator confirmed this set.

* ✅ **D-U5 — DECIDED 2026-09-24 (operator): option (b).** A required `purpose` (the stated purpose / legal basis)
  on Investigation create — missing or blank is a **422** — sealed in the write-once `header.json` (so the Dossier
  manifest covers it), shown in the Dossier (`summary.purpose`, the JSON rendering's bindings, and the method
  statement's *Scope.* line) and **not enforced**. A fork inherits it with the header; a template instantiation MINTS
  an Investigation, so it requires its own `purpose` too. No `caseId` — (a)/(a′) were not chosen.
* ✅ **D-U6 — DECIDED 2026-09-24 (operator): masking is CONFIGURABLE per Space** — `masking_mode` in
  `link-analysis.toon` (`maskingMode` on `GET|PUT /settings/link-analysis`, declared in
  `ConfigSpecs.linkAnalysisSettings()`, the same per-Space settings document as the D-S3 caps): `typed` (**DEFAULT**) ·
  `all` · `none`. Masked at RENDER time in the Working Set relation, the `/ops` `/undo` `/replay` `/log` answers and
  the Dossier (incl. embedded snapshots' score tables) — never in the store, so replay and custody are untouched.
  **Reveal: per entity**, `POST /inv/investigations/{id}/reveal` under a NEW capability `canRevealLinkEntities`,
  audited as `LINK_ENTITY_REVEALED` (the tokens, never the raw ids). ⚠ **What `typed` masks TODAY** — entity typing
  (LA-17) is not built, so it uses the only type marks that exist: (1) ids SEEDED with `entityType` MSISDN / IMSI /
  ACCOUNT (per entity), and (2) EVERY id when the bound Dataset's registry `columns[]` entry for `sourceCol` or
  `targetCol` carries `classification` MSISDN / IMSI / ACCOUNT (an id records no column). Nothing else: an entity
  admitted by an expand carries no type, and **no shipped Dataset declares such a column**, so on the demo data
  `typed` masks only seeds the analyst typed. The schema file's `raw.fields[].classification` is NOT consulted —
  nothing resolves a Dataset to its schema file. ⛔ `typed` is not silently `none`: every masked response carries
  `masking{mode, masked, basis}` saying what was masked and why.
* ✅ **D-U7 — DECIDED 2026-09-24 (operator): four-eyes on a per-Space threshold.** An `expand` whose `budget` exceeds
  `four_eyes_budget_above`, or whose `maxFanOut` exceeds `four_eyes_fan_out_above` (an unbounded fan-out exceeds
  any), becomes a **`pending`** request that reads nothing until a **different** Subject holding the NEW capability
  `canApproveLinkExpansions` approves it (`POST .../pending/{rid}/approve`; `.../deny` beside it). Self-approval —
  and self-denial — is a 403; with no Subject at all it is a 403 (two people cannot be told apart). Both thresholds
  absent (the shipped default) = no four-eyes. As built (grounding option A): the pending request lives OUTSIDE the
  sealed log (`pending/<rid>.json`, listed under `pending` in `GET /log`); only an APPROVED expand enters the log,
  as the requester's op with `approval{requestedBy, approvedBy, …}`, resolved against the Working Set at approval
  time. One pending request per Investigation (a second is 409). The approver exception skips ONLY the owner check —
  R3 and the PDP still judge the approver. A template's sensitive expand is refused (422): it names no frontier to
  approve. ✅ **The two stateless read routes are gated too (operator 2026-09-24).** `/inv/projection/neighbors` and
  `/inv/traversal/recursive-paths` have no Investigation to hold a pending request, so — like a template step —
  a read above the Space's thresholds is **refused (403)** and the message points to an Investigation, where a
  second person can approve the expand (`InvRoutes.refuseIfSensitive`). Before this, both routes could read
  exactly what a pending expand was being held back from reading. The size of each read is checked against the
  same two thresholds: for neighbours, rows = fan-out = `limit`; for a traversal, rows = `maxDepth × maxEdgeYield`
  and fan-out = `maxEdgeYield` (the defaults, 6 × 10 000, are over most thresholds). The check runs after the
  Dataset-visibility 404 (so it cannot reveal a Dataset the caller cannot see) and before any query, so a refused
  call reads nothing. ⚠ A traversal validates its columns against the relation first, so a bad column is still a
  422; the neighbours read only learns a column is unknown when its query runs, so above a threshold that call
  answers 403 instead. With no
  threshold set, nothing changes. ✅ **Extended the same day (operator) to `/inv/projection` and
  `/inv/projection/multi`**, which read a whole relation: a projection returns at most `limit` links, so rows =
  fan-out = `limit`; `/multi`'s `limit` is **per mapping**, so rows = `limit × mappings` and fan-out =
  `limit × edge mappings`, judged on the whole plan before any mapping runs (the call stays fail-closed as a
  whole). ⚠ The default `limit` is 2 000, so a Space whose budget threshold is below that refuses the canvas's
  initial load until the caller asks for a smaller `limit`. Pinned by
  `ControlApiInvestigationOversightTest.theStatelessReadsAreRefusedAboveTheFourEyesThresholdsBecauseNothingCouldApproveThem`
  and `…theWholeRelationProjectionsAreRefusedAboveTheFourEyesThresholdsToo`.
* ✅ **D-U8 — DECIDED 2026-09-24 (operator): NO purge.** Deliberate: the store stays append-only evidence (D-S1/D-E2);
  no retention period, no purge task, no legal-hold record. No code.
* 🟡 **D-U9 — PARTLY DECIDED 2026-09-24 (operator).** ✅ Annotation `confidence` is the **Admiralty grade** — the
  standard NATO/Admiralty scale, source reliability **A–F** (F = cannot be judged) × information credibility **1–6**
  (6 = cannot be judged), one token such as `B2`; validated (422 otherwise), stored in the sealed annotation (absent
  when ungraded, so older hashes stand), rendered in the log/Dossier steps (*graded B2 (source usually reliable,
  information probably true)*) and in the SPA's annotation list. ⏳ **Still UNDECIDED:** link annotation ids (the
  de facto key exists — `src␀tgt␀kind` — but no wire id is chosen) and per-Collector coverage attribution.

*History — the questions as first raised:*

* **D-U5 — Scope binding.** What does an Investigation bind to, and what does the binding refuse? An Investigation
  has no Case linkage (an LA-10 deferral; LA-24 keeps owner-only). Options: (a) a required `caseId` on create
  that every read must stay inside; (b) a stated purpose/legal-basis field recorded and shown, not enforced;
  (c) wait for Case linkage. Blocks nothing else shipped.
* **D-U6 — Minimisation.** Which values are masked by default (all entity ids? only typed identifiers — MSISDN,
  IMSI, account — which need LA-17's types?), in which responses (Working Set, log, Dossier, snapshots), and who
  may *reveal* (a new capability, or `canManageIncidents`)? Is a reveal per entity or per Investigation?
* **D-U7 — Four-eyes.** Which expansions are "sensitive" (a budget/fan-out above N, a hop ≥ N, a named Dataset
  flag, any `expand` on a flagged Investigation)? Who approves (any other holder of a capability, or a named
  role)? Does a pending expansion block the log (a `pending` step) or run and await ratification?
* **D-U8 — Retention / purge.** Retention period (per Space setting? per Investigation?), what a purge removes
  (log + sets, or also snapshots and the audit events that name ids), whether a legal hold exists, and who may
  purge. ⚠ The store is append-only evidence by D-S1/D-E2, so purge contradicts it unless the decision says how.
* **D-U9 — Annotation `confidence` and link annotations.** §2.2 lists `confidence` with no scale (0–1? an ordinal
  low/medium/high? the intelligence-grading 5×5?) — refused 422 until chosen. Links have no stable id in the
  Working Set, so link annotation needs one defined (e.g. `source|target|kind`). Per-Collector coverage
  needs a decided attribution (a Collector column on the Dataset, or lineage from consignments).

✅ **GROUNDED 2026-09-24 — evidence only (the decisions above came after it and are recorded there).** *Recommended* = a reading of the
evidence. Paths under `inspecto-geo-link/src/main/java/com/gamma/geolink/` unless stated.

| Id | True today | Ruled out | Survivors + cost | Recommended reading |
|---|---|---|---|---|
| **D-U5** | The header is write-once, has no `caseId` and no purpose field (`InvestigationRoutes.java:159-174`); it is minted by THREE paths (create `:138`, fork `:272`, template instantiate `:333-395`). Access sits in `open()` **and a copy in `DossierRoutes.java:196`**. A Case is an `OperationalObject` type `CASE` with owner + assignee, **no team list** (`OperationalObject.java:30-33`). A Case id is verifiable from geo-link TODAY via `ObjectAccess.summary` — `AnnotationTargets.java:89-116` already does lookup + visibility + 503-when-ops-absent. | (a)'s "every read stays inside the Case" **as data containment** — no relation ties a Case to Dataset rows. An unverified `caseId` (the snapshot-attach precedent, `InvRoutes.java:199-221`) — the seam removes the excuse. | (a) required `caseId`, checked at create and every `open()` (both copies), in the PDP resource — **M**. (a′) *optional* `caseId`, checked when given — **S–M**, and it is LA-24's prerequisite. (b) purpose / legal-basis header field, absent when null, shown in Dossier *Scope.* (`GraphDossierBuilder.java:468`) — **S**. (c) wait — ⚠ nothing else builds the linkage (LA-24 waits on it), so (c) = never. | (b) + (a′), ≈ M. |
| **D-U6** | **No value masking and no reveal exist anywhere** (Java "mask" = secrets or the day mask). Entity ids reach the Working Set (`WorkingSetRoutes.java:78-80`), log text (`InvestigationRoutes.java:877-908`), Dossier (whose SHA-256 root covers them), **and audit events** (`LINK_EXPANDED` carries the value, `EventType.java:144`) — D-U6's list omitted audit. Schema fields already carry `classification` (`SchemaProjection.java:46`), vocabulary unowned. SEC-08 (classification-driven masking) is **Enterprise-only** by decision (`EDITIONS.md:370`). | Masking at storage — the reads bind raw ids and every hash is over raw content; render-time only. Masking as access control — the owner passes R3 and gets the same raw values from the exempt read routes (`/inv/projection`, `/neighbors`, `/multi`, `/traversal`, `/pattern`, `/bi/query`). Reveal on a GET (the manifest gates only mutating verbs). *"Reveal = `canManageIncidents`"* as separation — every owner already holds it. | Mask all ids (**M**) · mask ids of PII-classified bound columns, no LA-17 needed (**M**; the evaluator does not record the source column, so in practice "either column PII ⇒ all") · per-entity typed masking (**L**, blocked on deferred LA-17). Dossier masking needs keyed pseudonyms in the manifest to stay verifiable (+**M**). Reveal: stateless per-entity `POST` + one event (**S**) or a persisted `reveal` op, absent-when-empty (**M**). Owed with it: SEC-08 applied (Enterprise) or a Professional carve-out. | Render-time mask keyed on PII column classification across Working Set / log / Dossier; per-entity stateless reveal under a new capability. Minimises the **hand-over**, not the analyst's view. ≈ M. |
| **D-U7** | `expand` is always ONE hop — hop depth is derived state (`InvestigationEvaluator.java:246`), so "hop ≥ N" is not a field. `budget` above 20 000 is **silently clamped** (`InvestigationRoutes.java:807-808`); `maxFanOut:null` = unbounded. The read runs and seals at append (`:217`). **No two-person mechanism exists**: agent `Approval` is a bounded ring with a free-text `decidedBy`; Exchange grants separate Spaces, not people. `open()` 404s every non-owner, so **no approver can see the Investigation**. No Subject ⇒ `withCapability` is a no-op and the actor spoofable (`ApiContext.java:174,325-331`). A new log kind is skipped by evaluate/undo/reorder, **but `GraphDossierBuilder.java:75-76` would throw on it**. | Run-then-ratify as *prevention* (data already read and returned) — review only, **S**. Gating `expand` alone — `/inv/projection/neighbors` and `/inv/traversal/recursive-paths` read the same Dataset ungated. The PDP alone (stateless). Any four-eyes below Professional (no Subject to tell two people apart). | **A** pending request OUTSIDE the log (rewritable record, like the alert-rule binding `SnapshotStore.java:291-298`), approve route + new capability, approver exception in `open()` — only the approved op enters the log with `requestedBy`/`approvedBy`; evaluator, hashes, undo, fork, Dossier untouched — **M**. **B** a `pending` log kind — A + Dossier changes + freezing undo/reorder while pending — **M–L**. Either needs a new sensitivity flag (none exists; the header is write-once) and gating of the two ungated routes (**S–M**). | A, Professional+ only, with the two routes gated. ⚠ The approver exception cuts into owner-only (LA-24 / D-E7) — that is the real call. |
| **D-U8** | The store is **NOT a generalised `RunArtifactStore`** — `SnapshotStore` is its own, and says so (`SnapshotStore.java:22-24`). **No delete path exists**, and no prune task reaches `audit/snapshots/` (`RunlogPruneTask.java:39-40`). Forks seal their own row copies. `verify` reports missing/changed, but a deleted header reads as 404 = "never existed". Investigation audit events name **no** entity ids; the older projection events do, and `event_prune` drops only whole UTC days. Precedent: `incident_purge` (`inspecto-ops/.../IncidentPurgeTask.java`) — dry-run, `max_count`, skips `legalHold`, keeps the purge audited. Legal hold exists on Operational Objects only. No at-rest encryption anywhere; backups zip whole directories. | Per-event audit deletion. In-place redaction that keeps replay (every later step becomes unverifiable). Crypto-shred of EXISTING data (already plaintext, already in backups). A hold flag in the write-once header. | **1** whole-Investigation purge task (+ forks + anchored snapshots — found only by scanning; shared `attachments.jsonl` must be rewritten or left), audited, leaving a *purged* marker so `verify` answers "purged" — **M**. **2** audit-log retention only (`event_prune`) — **S**, config. **3** tombstone + redaction — **L**. **4** crypto-shred, future data only — **L+**. Hold: a separate record in the Investigation dir, or inherited via a D-U5 Case link — **S–M**. Only a maintenance task can reach every Investigation. | 1 + 2 + a hold record — copies a shipped pattern and leaves every unpurged Dossier verifiable. |
| **D-U9** | `confidence` is refused 422 before `note` is read (`InvestigationRoutes.java:781-789`); none is sealed anywhere, so any scale is additive if absent-when-empty. 🔴 **The word is taken twice** — schema-overlap inference `high`/`medium` (`InvRoutes.java:291-297`) and Tool Evidence 0–1 beside `CredibilityTier` (`GLOSSARY.md:877-881`) — and ***Annotation* is taken once** (`GLOSSARY.md:683-691`, Note/Tag over Annotation Targets). Links **do** have a de facto key: `src␀tgt␀kind`, directional, first-seen wins (`InvestigationEvaluator.java:248`); a null kind collides with a literal `"null"`. One Dataset per Investigation ⇒ pair+kind unique inside a Working Set. Collector provenance is derivable today from `batches` + `lineage` via `GET /lineage` (`LineageRoutes.java:43,78-93`); a Pipeline has exactly one Collector. | A 0–1 float (false precision for an analyst judgement); reusing either existing `confidence` meaning. `source|target|kind` as a delimited string — ids may contain `\|`. A Collector column on every row (**L**, and history cannot be backfilled). | Scale: ordinal L/M/H or a two-axis reliability × credibility grade — both **S** + a glossary term that is NOT bare *confidence*. Link id: a structured `links:[{source,target,kind}]` param validated against the Working Set, absent-when-empty — **M**, plus a rule for whether link annotations survive `exclude`. Coverage: provenance join from Dataset `filename` → consignment → Collector (**M–L**; physicalRef Datasets only, arrival ≠ event time) or arrival-only coverage from `batches` (**S–M**). | Two-axis grade under a new glossary name; tuple link key; say which coverage question (arrival vs event day) is answered. |

🔴 **Premises in the D-U5…D-U9 text above that did NOT survive verification:** (1) D-U5(a)'s "every read must
stay inside" is not expressible. (2) D-U5(c) assumes the linkage arrives elsewhere — it does not.
(3) D-U6's "typed identifiers need LA-17" — column classification suffices unless per-entity. (4) D-U6's
two reveal options are not peers — `canManageIncidents` is held by every owner. (5) D-U6 omits audit events,
which already carry raw values. (6) D-U7's "hop ≥ N" and "named Dataset flag" do not exist, and "any other
holder" cannot see the Investigation. (7) D-U8's "append-only by D-S1/D-E2" is a coding discipline, not
write-once storage, and the store is not a generalised `RunArtifactStore`. (8) D-U9's "links have no stable
id" — the key exists, only a wire id is missing; "lineage" should read **Provenance** per `GLOSSARY.md:805`.
⚠ **Also surfaced, outside these decisions:** §2.6 still calls exclusion/reveal client-side and unaudited
(exclusion is a server op audited as `LINK_INVESTIGATION_STEPPED`; reveal does not exist), and the D-E1 row
still calls the RCA noun owed although `BACKLOG.md` records *Triage / Triage Run*.

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

⚠ **Do not conflate with LA-12.** ~~`manifestHash` is **FNV-1a 64 and explicitly not cryptographic**~~ — since 2026-09-28 `manifestHash` is SHA-256 (`sha256:<hex>`, the server's format); the chain of custody is still LA-12's Dossier manifest, since the server stores `manifestHash` without recomputing it.

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

✅ **AS BUILT 2026-09-23 (backend) — the path noun is `investigations`, per D-E1**, in a new
`InvestigationRoutes` (registered beside `InvRoutes`, which was left untouched) over a pure
`InvestigationEvaluator`:

| Route | Gate | Notes |
|---|---|---|
| `POST /inv/investigations` | `canManageIncidents` | body `{id?, title?, dataset, sourceCol, targetCol, linkKindCol?}`; 503 → 422 → 404 (R3) → 422 unknown column → 403 → 409 |
| `POST /inv/investigations/{id}/ops` | `canManageIncidents` | `{op, ids?, reason?, entityType?, limit?}` → `{step, delta, truncated, read?, workingSet{entities,links,excluded,hash}}` |
| `POST /inv/investigations/{id}/undo` | `canManageIncidents` | **added** — not in this table before; 409 when there is nothing to undo |
| `POST /inv/investigations/{id}/reorder` | `canManageIncidents` | **added** — D-E4; `{order:[step…], id?, title?}` → a NEW Investigation |
| `POST /inv/investigations/{id}/replay` | read-shaped exemption | `{at?, reread?}` → `{workingSet, equivalent, mismatches, drift, diverged}` |
| `GET /inv/investigations/{id}/log` | open read, bounded (`?limit`, default 500, max 5 000, true `total` + `truncated`) | each entry carries a plain-language `text` |

* **Store (D-E2).** `SnapshotStore` was extended, not duplicated: `audit/snapshots/investigations/<id>/`
  holds `header.json` (CREATE_NEW), `log.jsonl` (append-only, the source of truth, written FIRST) and
  `sets/<step>.json` (CREATE_NEW — the Working Set each step evaluated to).
* **Seal (D-E3).** `datasetVersion` is always `null`. Each `expand` stores the rows it read, the exact query
  inputs (frontier, excluded, limit), `readAt` and a SHA-256 `fingerprint` — weak provenance, not a pin.
  Replay evaluates the sealed rows, so it cannot move (G-E11); `reread` re-runs each recorded query and
  reports per-step drift (G-E3). ⛔ G-E2 (byte-identical replay against a pinned version) stays deferred.
* **Two evaluators, one spec — honestly scoped.** The append path evaluates the round-tripped log plus the
  new step and records that position's hash; `/replay` recomputes every position from step 1 and reports
  `equivalent` + the steps whose hash disagrees. The check is pinned to be able to FAIL (a tampered hash is
  reported). ✅ **FIXED 2026-09-23 (with LA-20): replay after an undo reported `equivalent:false` on an
  UNTAMPERED log** (`seed, expand, exclude, hide, undo` → `mismatches:[4]`, found by the LA-12 dossier lane).
  `InvestigationEvaluator.evaluate` resolved the undone steps across the WHOLE log before folding, so prefix k
  skipped an op undone only later while k's recorded hash included it. Now PREFIX semantics — position k
  honours only undo entries at positions ≤ k (ops fold incrementally; an undo re-folds its own prefix); the
  `reread` drift loop uses the same prefix. Pinned by
  `ControlApiInvestigationsTest.replayAfterAnUndoIsEquivalentBecauseAPrefixIgnoresLaterUndos` (red before the
  fix; a no-re-fold mutant also dies). The full-log evaluation (append path, Working Set relation) was never
  affected: for the whole log, whole-log and prefix semantics coincide. ⚠ "Incremental" means the Dataset read is a one-hop delta; the in-memory fold itself is
  re-run from the sealed log, which needs no Dataset access.
* **Fork (D-E4).** `reorder` takes a permutation of the EFFECTIVE op steps, re-applies them in the new order,
  **re-reads** every `expand` (a new order means a new frontier, so the parent's sealed rows do not describe
  it), and moves the assembled fork into place in one rename. The header carries `parent {id, order,
  parentSteps}` and every step `derivedFrom {investigation, step}`. The original log, its sets and any snapshot
  anchored to it are byte-identical after — pinned, and the test kills both an in-place-rewrite mutant and an
  ignore-the-order mutant.
* **Access.** Owner-only (non-owner → 404, as R3 answers): grounded — snapshots, the comparable object, have
  no owner or sharing model, and `ComponentAccess` covers registry components only. Every route applies the R3
  Dataset gate; every Dataset READ goes through `ComponentAccess.canView`. `relationFor` is duplicated from
  `InvRoutes` rather than moved, to avoid editing that class under a parallel lane — fold them together later.
* **Semantics chosen where the plan was silent.** Seeding an excluded id re-admits it (a later explicit op
  wins); `exclude` of a KEPT id is a no-op reported as `protected`; `expand` with no `ids` expands the whole
  Working Set (hidden included — hide is display-only), capped at 1 000 frontier entities; excluded ids are
  filtered IN the query so an excluded hub cannot spend the fan-out budget.
* 🔴 **Plan vs code.** (1) *Real undo* is a recorded log edit (`kind: undo`), deliberately NOT a twelfth op —
  §2.2's vocabulary stays closed. (2) *"Replaces the mock snapshot store"* was already done by LA-03; nothing
  here touches it. (3) *"Returns id + pinned Dataset version"* cannot hold under D-E3; the field is present
  and `null`. (4) `undo` and `reorder` are two routes this contract did not list.
* ⏳ **Deferred:** `seedBy`, `excludeBy`, `threshold`, ~~`annotate`~~ (✅ LA-19, 2026-09-24), `snapshot` (they answer 422 *"not
  implemented yet"*, never *"unknown"*); ~~`window` and the hop-ladder rung fields (LA-13)~~ ✅ shipped 2026-09-23; a list/GET-one route; Case
  linkage of an Investigation; ~~the Investigation Template (LA-23)~~ ✅ shipped, §5.8; stamping `investigationId`/`opSeq` onto
  snapshots server-side (the snapshot body is stored verbatim, so a client can already carry them); SPA wiring.

### 5.6 Dossier routes — LA-12

✅ **AS BUILT 2026-09-23 (backend).** These routes are in a new `DossierRoutes`, registered through
`META-INF/services`. `InvestigationRoutes` was left untouched because a parallel lane (LA-20) is editing it.

| Route | Gate | Notes |
|---|---|---|
| `GET /inv/investigations/{id}/dossier` | open read, owner-only | `?at=` (a prefix, 0..steps, max 5 000) · `?snapshots=a,b` (max 20) · `?format=json` (default: the whole dossier in the envelope) `\|steps\|method` (that one rendering as `text/plain`) |
| `POST /inv/investigations/{id}/dossier/verify` | read-shaped exemption | body `{manifest}`, a bare manifest, or a whole dossier → `{verified, selfConsistent, intact, submittedRoot, currentRoot, changed, missing, added, contentChanged, integrity}` |

* **Three renderings.** All three are text, so no new dependency was needed: the re-runnable **JSON** log,
  where the sealed rows are summarised by their fingerprint and stay in the store, covered by the manifest ·
  numbered plain-language **steps** with author and time · the **method statement**, which closes with the
  custody root. Every exclusion is listed with its id, step, author, reason and basis. Undone steps, truncated
  reads, hidden entities, the unassessed coverage, which measures exist over which set, and the unpinned Dataset
  version are listed in all three (**G-E10**). ⏳ **PDF / HTML deferred:** there is no PDF library in the
  dependency set, and `format=method` is already a text document ready to hand over.
* **Manifest (G-R6).** SHA-256 over the raw stored bytes of `header.json`, each log line (`log.jsonl#<step>`),
  each `sets/<step>.json` and each included snapshot, plus canonical-JSON hashes of `entities`, `links`,
  `excluded` and `scores`. `root` is SHA-256 of the canonical manifest body; `generatedAt` sits outside it, so
  two builds of an unchanged store have the same root. Hashing log LINES rather than the file lets a prefix
  dossier (`?at=`) cover exactly what it includes and name the step that changed.
* **Two independent tamper checks.** (1) `integrity` needs no earlier manifest. It replays each position as the
  PREFIX it was at append time and checks it against the recorded `workingSetHash`. It also checks each set
  file's `hash` against the replay and against its own content, and each sealed read's rows against their
  `fingerprint`. (2) `verify` compares a held manifest with one rebuilt now. It catches what (1) cannot: a
  tamper that kept every internal hash consistent, or a change to `header.json`, which no recorded hash
  covers. A step or snapshot that has since disappeared is reported under `missing`, never refused. The
  test tampers with a set file, the header, the log and a snapshot, and edits a manifest. **Mutation-checked:**
  disabling the artefact comparison, or the set's content-hash check, turns the tests red on the tampered
  values, not on a side effect.
* **Centrality/risk tables.** No server-side graph algorithm exists, so none is computed here. The tables are
  the `metrics` a snapshot sealed, computed client-side, each labelled with the snapshot, its `createdAt` and
  its node count. Degree in `topology` is a count of the sealed links, not an algorithm.
* **Access.** Owner-only (a non-owner gets 404) plus the R3 Dataset check, both copied from
  `InvestigationRoutes.open`. Building a dossier reads no Dataset, so no `relationFor` call is involved. An
  included snapshot must be **anchored**: its stored body must carry `investigationId` equal to this
  Investigation (422 otherwise), and its `origin.dataset` must be viewable (404). Without that, the dossier
  would become the first route that returns snapshot CONTENT, open to anyone who owns any Investigation.
* **Audit.** `LINK_DOSSIER_BUILT` (at, format, root, intact) and `LINK_DOSSIER_VERIFIED` (verified, both roots,
  changed count). Both are best-effort, per LA-04.
* 🔴 **Plan vs code.** (1) ✅ **SPA SHA-256 swap SHIPPED 2026-09-28.** `graph-snapshot.ts` `manifestHash` is
  now Web Crypto SHA-256 over the UTF-8 bytes of the canonical JSON, written `sha256:<hex>` — the server's own
  format and canonicalisation (`InvestigationEvaluator.sha256(canonical(…))`), pinned by a known vector computed
  with the server's Jackson recipe (non-ASCII label, unsorted keys). `snapshotGraph`/`verifySnapshot` are async;
  the Snapshot dialog's `save()` awaits the hash with `saving` raised first, and refuses (seals nothing) outside
  a secure context. The server still stores `manifestHash` verbatim and never recomputes it, so the dossier's
  manifest, not the SPA's `manifestHash`, is the custody root. (2) *Centrality tables* are carried, not computed, as above. (3) The
  dossier is **not persisted** as an Artifact: it is regenerated deterministically, and custody is proved by
  the reader's held manifest. A stored, `opSeq`-anchored dossier Artifact is deferred. (4) `render()` and
  `open()` are copied from `InvestigationRoutes`, to be folded together once LA-20 lands. The dossier's
  `render` deliberately lists EVERY excluded id, never "and N more".
* ✅ **FIXED by LA-20 (2026-09-23, prefix semantics — see §5.5).** ~~Found while grounding (LA-10 defect, not fixed here — the file is under a parallel lane):~~
  `POST /inv/investigations/{id}/replay` reports **`equivalent:false` after ANY undo** on an untampered log.
  `InvestigationEvaluator.evaluate` collects the undone steps across the WHOLE log before folding. So replay
  position *k* skips an op undone LATER, while the `workingSetHash` recorded at *k* includes it. Probe: seed,
  expand, exclude, hide, undo, then replay answers `equivalent:false, mismatches:[4]`. The dossier's integrity
  check avoids this by evaluating prefixes. The fix is for `replay` to do the same.
* ⏳ **Deferred:** ~~SPA wiring (no Dossier UI yet)~~ — ✅ SPA wired 2026-09-23 (`link-analysis-dossier.component`:
  json summary, steps/method downloads, verify) + `at`/`snapshots` pickers 2026-09-27 (SPA sealing anchors
  `investigationId` since 2026-09-28; a snapshot from another Investigation is still refused 422) · ~~the SPA SHA-256 swap~~ (shipped 2026-09-28) · PDF/HTML renderings · a persisted
  Dossier Artifact · coverage (LA-19) · server-side centrality · snapshot dossiers without an Investigation.

### 5.7 The Working Set as a derived relation — LA-20

*(Numbered 5.7 because the parallel LA-12 dossier lane adds its own §5.6.)*

🔴 **D-E7 gate moved into `InvestigationRoutes.open` at merge (2026-09-23).** As first built, the Enterprise
PDP check (`RowScope.visible(ex, "investigation", …)`) ran on `/working-set` alone, and its test asserted `/log`
stayed 200 under a DENY — so a policy hid one view of data that `/log` (the sealed rows), `/replay` and
`/dossier` still served, and `/ops` still wrote. The check now lives in the ONE gate every Investigation route
opens through, and `DossierRoutes` uses that gate instead of its copy; `ControlApiInvestigationPolicyTest` pins
404 for the owner on log, dossier, replay and ops under a DENY (red on the old code at `/log`: 200).

✅ **AS BUILT 2026-09-23 (backend)** — `WorkingSetRoutes` in `inspecto-geo-link`, a separate `RouteModule` so the
LA-10 classes changed only by two visibility modifiers (`InvestigationRoutes.Inv` and `open` are package-private
now, reused so the owner + R3 gate is not duplicated):

| Route | Gate | Notes |
|---|---|---|
| `GET /inv/investigations/{id}/working-set` | open read (no capability) + the D-E7 row gate | `?of=entities\|links\|excluded` (default `entities`), `?limit` (default 1 000, max 10 000), `?offset` → `{relation, columns, rows, total, offset, limit, truncated, head{step, workingSetHash}, key, cached}`; 422 on a bad `of`/`limit`/`offset` |

* **Columns (§2.7 provenance).** `entities`: `entityId, type, hop, seedId, opSeq, hidden, kept` · `links`:
  `source, target, kind, count, opSeq` · `excluded`: `entityId, opSeq, reason` — negative space is a relation of
  its own, not a footnote. `opSeq` is the step that admitted (or excluded) the row. Rows are in a stable order
  (by id), so a page is reproducible.
* **Evaluated from the sealed log (D-E3)** by the same pure `InvestigationEvaluator` — no Dataset read, so the
  relation cannot move when the data grows (G-E11) and needs no second access check on the Dataset.
* **Cache.** Key = the Investigation's directory + SHA-256 of the log's **committed** bytes (up to the last
  newline, so a line being appended is never half-read). The head step and every seal fingerprint are inside
  those bytes, so the key IS "log head + seal fingerprints": an op or undo appends → new key; a fork is another
  directory → another relation; a hand-edited log hashes differently. No invalidation hook in the write path
  exists or is needed. Bounded LRU (32). The response's `cached` flag makes a hit observable. ⚠ The file is
  still read per request; the saving is the JSON parse of every sealed row, the fold and the row building. ⛔ A
  size/mtime key was rejected: it is correct only while nobody rewrites the file, which is the case replay's
  equivalence check exists for.
* **D-E7 gate — runs on EVERY request, BEFORE the cache**, so a cached relation cannot reach a caller the gate
  refuses (pinned: an owner warms the cache, a non-owner still reads 404 with no rows in the body).
  (1) `InvestigationRoutes.open` — owner-only (404 = absence) + the R3 Dataset gate. On Professional and below
  (no `AccessDecider`) that is the whole rule, and there is **no fallback to `ComponentAccess` Dataset
  sharing** — pinned with a non-owner who can view the Dataset and holds `canConfigureAccess`. (2)
  `RowScope.visible(ex, "investigation", {id, owner, dataset, parent})` — on Enterprise the `PolicyEngine`
  judges the resolved Investigation; DENY → 404 even for the owner (pinned end to end with an authored policy in
  `inspecto-policy`, which gained a test-scope dependency on `inspecto-geo-link`, the same direction rule as its
  `inspecto-ops` one). With no Subject (Personal) nothing is enforced, as everywhere.
* **Not reachable through `/bi` or `/db`.** Grounded: the relation is never registered as a DuckDB view, a
  Dataset or a registry component, so no name exists for `BiRoutes.biQuery` / `QueryExecutor.run` to address;
  the sealed files under the write root are refused by `SqlGuard` (file-reading functions and path-shaped
  identifiers). The Investigation-scoped route is the only way in — deliberately, for this slice.
* **Why a GET with no capability.** Reads are open by policy (`compliance/evidence/route-gating.md`); the gate on
  this read is ownership + the PDP, which a capability could not express. Hence no `CapabilityManifest` entry and
  no route-gating row; pinned that an owner holding no capability at all reads it.
* **Audit** — `LINK_INVESTIGATION_WORKING_SET_READ` per read (LA-04 shape: `relation`, `rows`, `total`,
  `truncated`, `cached`, `key`), best-effort.
* 🔴 **Plan vs code.** (1) D-E7 says Enterprise "enforces Case scope"; an Investigation has **no Case linkage**
  yet (an LA-10 deferral), so there is no Case attribute for a policy to test — the PDP sees `id, owner, dataset,
  parent`. (2) The `AccessDecider` contract says a policy ALLOW never widens an existing gate, so on Enterprise a
  policy can only NARROW owner-only; letting a Case team read a colleague's Investigation would be a sharing
  model, which D-E7 did not decide. (3) §2.7 says "a **Widget** binds to it" — no Widget binds yet (LA-21).
* ⏳ **Deferred:** the relation as a BI-queryable source (`/bi/query` — LA-21; the binding must carry this gate with
  it) — ✅ Measures and Alert Rules over it shipped with LA-23 WITHOUT going through `/bi`, carrying this gate (§5.8); Case linkage and Case-scoped sharing; `?at=<step>` (a relation at a past
  head); a cross-JVM cache (the cache is per process, which is correct but cold after restart).

### 5.8 Investigation Template → Measure → Alert Rule → Incident — LA-23

✅ **AS BUILT 2026-09-23 (backend)** — two new `RouteModule`s in `inspecto-geo-link`. `InvestigationRoutes` gained
ONE additive package-private method, `instantiate(...)` (the fork loop's shape, over a new binding); no existing route
changed and no registration line moved.

| Route | Gate | Notes |
|---|---|---|
| `POST /inv/investigations/{id}/template` | `canManageIncidents` + `InvestigationRoutes.open` | `{id?, title?}` → the template; 422 when the effective log has no `seed`; 409 on a taken id (write-once) |
| `GET /inv/investigation-templates/{id}` | open read, owner-only (404) | the stored template |
| `POST /inv/investigation-templates/{id}/instantiate` | `canManageIncidents`, owner-only | `{id?, title?, params:{seed1:[…]}, dataset?, sourceCol?, targetCol?, linkKindCol?}` → a NEW Investigation; 422 missing/empty/unknown parameter → 404 Dataset (R3) → 422 column → 403 → 409; atomic (assembled aside, one rename) |
| `GET /inv/investigations/{id}/measures` | open read + `open` (owner · R3 · PDP) | `?relation=&measure=` optional → `{head, measures[{name, relation, measure, value}], byKind[{kind, links, events}], measure?, key, cached}` |
| `POST /inv/investigations/{id}/alert-rules` | `canAuthorAlertRules` + `open` | `{name, relation?, measure, comparator, threshold, severity}` → `{rule, current, wouldFire, disclosure}`; 422 any other field / invalid rule / uncomputable measure → 503 no alert engine → 409 name taken |

* **Template = the method (D-E8, G-E12).** `seed` → a parameter (`seed1`, `seed2`, … in log order; ids NOT stored).
  `expand` → carried with its `limit`; an expand that named its frontier becomes a whole-Working-Set expand, listed
  under `generalised` with `exact` (whether the named frontier WAS the whole Working Set there). `exclude`, `hide`,
  `keep` → dropped, listed under `dropped` as `{step, op, count}` — never the ids or the reason text (case data).
  Undone steps are not part of the method. Any other op travels verbatim — that is where D-E8's "named reference lists
  travel" lands — ⚠ but **nothing can take that path today**: `excludeBy` answers "not implemented yet" at append, and
  no persisted named list exists (LA-17 deferred; verified again: no reference-list object in the code). Pinned: the
  stored template contains none of the case's entity ids or reasons, and re-bound to the same seed it answers the
  graph WITHOUT the analyst's exclusion (mutation-checked: carrying `exclude` makes that test red on the entity set).
* **Store: `SnapshotStore`, not `ComponentStore`** — `audit/snapshots/investigation-templates/<id>.json`,
  `CREATE_NEW`. D-E2's one durable mechanism; write-once, so an instantiated Investigation names exactly the method it
  ran (its header carries `template {id}`, every step `derivedFrom {template, step}`). `ComponentStore` rejected:
  overwritable; a registry kind is writable through the generic `/components/{type}` CRUD, around the D-E8 extraction;
  and `ComponentAccess` sharing is a sharing model nobody decided for Link Analysis objects. Owner-only, like the
  Investigation.
* **Instantiation** binds the template's column ROLES to the new Dataset (names default to the template's; a kind
  role must be bound if the template had one), applies the R3 gate to the new Dataset, and re-reads every `expand`
  against it (sealed, D-E3). The instantiated log replays `equivalent` like any other.
* **Measure over the Working Set — one grammar.** The BI Measure shorthand (`count` | `agg(field)`, validated by
  `DatasetMeasureProbe.validMeasure`, split by `MeasureCompiler.splitShorthand`) over one relation's columns,
  evaluated in-JVM from the cached LA-20 relation with SQL null rules (`sum`/`avg`/`min`/`max` on `hop`, `opSeq`,
  `count` only; an aggregate over nothing is empty). Declared set: `entities` count, `links` count, `events`
  (`sum(count)`), `excluded` count, `maxHop`, and links/events **by kind**. `WorkingSetMeasures`.
* **Alert Rule.** `AlertRule` gained a fourth, disjoint kind — `investigation:` + `relation:` (default `entities`) +
  `measure:` — refused beside `dataset`/`metric`/`window`/`when`/`maximumAge`. `AlertService` evaluates it in its own
  pass through a `ServiceLoader` SPI (`com.gamma.alert.InvestigationMeasureProbe`, implemented by `WorkingSetMeasures`;
  absent the module, the rules are inert), and fires through the EXISTING path: `ALERT_FIRED` + the `alert-rule.fired`
  Signal (correlation `alert:<rule>|<investigation>`), the ALERT object, and at CRITICAL the Incident, deduped by rule
  within the Investigation's scope (`AlertServiceTest` pins one Incident across two fires). The ledger pass skips the
  kind (it has no window — mutation-checked: without the skip every sweep NPEs). The probe's write root is resolved as
  `ControlApi.writeRoot()` resolves it (Space config, else `-Dassist.write.root` as read at boot) — not re-read per
  sweep as `DatasetMeasureProbe`'s is.
* **Access.** Authoring opens the Investigation through `InvestigationRoutes.open` (owner-only, R3, Enterprise PDP):
  a non-owner's rule is refused as a 404 even with `canAuthorAlertRules`; a DENY refuses the owner. A sweep carries no
  caller, so the gate travels as a **binding** — `investigations/<id>/alert-rules/<rule>.json` holding the rule's
  canonical SHA-256 and the owner — and the probe evaluates a rule only when that binding matches the rule as armed
  (pinned: a rule written straight into the registry and armed never evaluates; nor does a bound rule edited after
  binding; mutation-checked). The generic `POST`/`PUT /alerts/rules` refuse the `investigation:` shape (422, naming the
  route), so the Decision Rule `create-alert` consequence cannot author one either.
* **Audit** — `LINK_INVESTIGATION_TEMPLATE_SAVED`, `LINK_INVESTIGATION_TEMPLATE_INSTANTIATED`,
  `LINK_INVESTIGATION_MEASURED`, `LINK_INVESTIGATION_ALERT_RULE_BOUND` (best-effort, LA-04 shape).
* 🔴 **Plan vs code.** (1) §2.7 frames the chain as a *detection* capability; as built the alert watches the **sealed**
  Working Set (D-E3; D-E6's frozen default), so it moves when the log moves (an op, an undo, a new instantiation) and
  never when the Dataset grows. A live re-read on every sweep would need a Dataset read with no caller, which could not
  pass the R3 gate the analyst's reads pass — deferred rather than done identity-blind. (2) D-E8 speaks of exclusions
  only; `hide` and `keep` are dropped too, on the same principle (they name entities of one graph). (3) An `expand`
  that named its frontier cannot travel as-is (the names are case data) — generalised and reported, not refused.
  (4) D-E8's "named lists travel" has nothing to carry until LA-17 or `excludeBy` ships. (5) The binding carries the
  owner-only gate to sweep time, but NOT a later PDP DENY or the owner later losing sight of the Dataset — both are
  checked when the rule is bound; the PDP judges a request Subject and a sweep has none. (6) A fired Alert (and
  Incident) shows the Investigation id, relation, measure, value and threshold to everyone who can read Alerts and
  Incidents — never an entity id; the binding response states this as `disclosure`.
* ⏳ **Deferred:** live (Monitoring) evaluation and scheduled re-instantiation (a Job Type) — the two things that turn a
  one-off alert into standing detection; a template list route and template sharing; editing a bound rule (delete via
  `DELETE /alerts/rules/{name}`, then re-bind); a PDP re-check at sweep time; ~~templates carrying `window`/hop-ladder
  parameters~~ ✅ LA-13 (the rung travels whole; a `window` is a parameter defaulting to the authored window); Case linkage of the Incident to the Investigation's Case. **SPA
  follow-up:** a *Save as template* action on the Investigation tab (show `dropped`/`generalised` before saving), an
  *Instantiate* dialog (parameters + Dataset + column-role mapping), a Measures strip over the Working Set, and a
  *Watch this measure* action posting to `…/alert-rules` that shows `current`, `wouldFire` and the `disclosure` text.

---

### 5.9 Working Set Widgets — LA-21

✅ **AS BUILT 2026-09-23.** D-E6 as decided: FROZEN by default, Live opt-in, kind on the tile, a drift line, and a Live Widget cannot leave the Space.

* **Binding.** A `widget` with `vizType: working-set` (view-bound plugin, `viewKind: investigation`), `viewId` = the Investigation id, and `workingSet: {relation: entities|links|excluded, mode: frozen|live, pin: {step, workingSetHash, pinnedAt}}`. The Widget holds **no rows**: every render reads through `GET /inv/investigations/{id}/working-set`, so the D-E7 gate runs per viewer — never registered as a Dataset, never reachable through `/bi` or `/db`. A shared dashboard's non-owner gets the route's 404 and the tile says *Not available to you*. `ConfigSpecs.widget()` declares `workingSet` plus a `working-set-binding` rule, enforced on component write for Working Set Widgets only (older widgets predate the spec's rules).
* **Frozen storage = `?at=<step>`** (additive on `WorkingSetRoutes`): the relation at a past head, evaluated from the log prefix (append-only; an undo is a later entry), cached under log-hash + step, 422 past the head, gated before validation (a non-owner gets 404, not a head-probing 422). The tile compares the answered `head.workingSetHash` with its pin; a mismatch shows a broken pin and no rows. The snapshot store was not used: it would need a gate of its own.
* **Live** reads the head plus the pin (as baseline) and states `Pinned step N: x · now step M: y · a added · r removed since the pin` (row-identity diff; flagged partial when a side is truncated).
* **Authoring** — the Investigation panel's *Pin to a Widget* (name, relation, Frozen/Live) pins the current head. Explore does not offer the type; it carries a loaded binding through a re-save.
* **Leaving the Space** (`WorkingSetWidgets`): the Exchange **refuses** both modes (Live: D-E6; Frozen: D-E7 — only the owner may evaluate it, so no consumer Space could render it). A Metadata Bundle export **converts** Live → Frozen at its own pin and lists it under `converted`. The whole-Space export (`GET /export`) is exempt: it zips the write root, the Investigation's log and owner with it. The public share link renders view-bound widgets as not embeddable already.
* 🔴 **Plan vs code.** D-E6 implies a Frozen Widget may leave; D-E7 (owner-only) means nothing can render it elsewhere, so the Exchange refuses it too. The Exchange already 422'd such widgets before LA-21 — with the misleading "no dataset binding"; the refusal is now explicit.
* ⏳ **Deferred:** the bundle UI does not surface `converted` yet; Case-scoped sharing (needs a sharing model); Measures/Alert Rules over the relation (LA-23); the openapi entry stays a skeleton (no query params documented, as before).

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
| G-R5 | LA-14 | 100 % of synthetic smurfing chains found; out-of-order timestamps rejected | `branching-pattern-engine.spec.ts` + `graph-analysis.spec.ts` (LA-14a) + `branching-parity.spec.ts` / `ControlApiInvPatternTest` (one shared golden) — both halves green 2026-09-23 |
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
