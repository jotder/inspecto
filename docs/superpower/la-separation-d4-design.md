<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-01 (option D, phase D-4). DESIGN ONLY — nothing is built. DECISIONS 1–7 SIGNED 2026-10-01 (operator; Decision 3 with a changed sort key, see its Answer).
  Retire per the three-tier lifecycle in CLAUDE.md when D-4 ships (or is declined).
-->

# LA separation — D-4 design (the server-side graph engine)

Option D's phase **D-4** ([`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.4, §7.8): run Link Analysis
(LA) graph algorithms on the server, behind one SPI, as asynchronous jobs "with progress, cancel and a stated budget — never a silent
cap" (§7.4). D-S4 answered the parity question (28 algorithms ported to `inspecto-la-graph`, §7.13); nothing yet *calls* them. This file
is the design the operator signs before any step. When this and the code disagree, the code wins — re-ground.

## 1. Grounded facts (2026-10-01, `6bf594a2f`)

| # | Fact | Where |
|---|---|---|
| 1 | The toolbox imports 25 ported algorithms and runs them synchronously on the main thread. `egoNetwork` `isForest` `descendants` are ported but not called by the toolbox. The other 3 imports (`matchPattern`, `matchBranchingPattern`, `explainNode`) are class-C and stay in the browser | `link-analysis-toolbox.component.ts:18-60`; `la-separation-feasibility-plan.md` §7.13 |
| 2 | Three caps, all per-space settings with browser defaults: **projection 500** (truncates, sets `truncated`), **analysis 2 000** (throws), **suspicion/betweenness 750** (throws). The 750 cap guards `betweennessCentrality` AND `suspicionScore`; betweenness measured 9.8 s at 2 000 nodes, quadratic | `entity-projection.ts:60,174,235,405`; `graph-analysis.ts:24,78,283-293,411-414,1101-1105,1591`; `LinkAnalysisSettings.java:45` |
| 3 | What the analyst sees at a cap: **analysis** — an `Error("X is capped at N nodes (graph has M).")` caught and put in `analysisError` (a message, no result, no way forward); **projection** — a `truncated` flag and a footer line "projection N nodes (server) · analysis M nodes (browser)". Nowhere does the UI offer to run the work somewhere else | `link-analysis-toolbox.component.ts:441-444,479-482,744-747,780-832`; `link-analysis.component.html:776-793`; `link-analysis.component.ts:705` |
| 4 | Already server-side: recursive traversal `POST /inv/traversal/recursive-paths` (fences depth 6, edge yield 10 000 → max 100 000, 5 s timeout, `truncated` + `edgeYieldCapped` + `fences` echoed), branching pattern `POST /inv/pattern/branching` (`legCapped`, `fences`), the hop ladder (expand params with a row `budget`, `truncatedSteps`). All three state their fences in the answer — the shape D-4 generalises | `InvRoutes.java:78-92,101,1015`; `PatternRoutes.java:63,201`; `inv.service.ts:140-175,213`; `link-analysis-investigation.component.html:145-151` |
| 5 | The server's materialised graph already exists: `InvestigationEvaluator.Relation(key, headStep, workingSetHash, tables)` with tables `entities` `links` `excluded`, an access-ordered LRU keyed by the sha256 of the committed log, served by `GET /inv/investigations/{id}/working-set?of=&at=`. `Entity(id,type,hop,seed,admittedBy)`, `Link(source,target,kind,count,admittedBy)`; ids are RAW in the cache and masked AFTER it | `WorkingSetRoutes.java:85-150`; `InvestigationEvaluator.java:91-94`; `EntityMasking.java:27-60` |
| 6 | `la-graph` input is `Graph(List<Node(id,label)>, List<Edge(id,source,target)>)`, results are `Selection` / `Score` / `List<List<String>>`. **No edge data**: weighted functions take `Map<edgeId, weight>`. The six `Graph*` classes are pure JDK; none has a cancel or progress hook | `GraphAlgorithms.java:27-36,38`; `inspecto-la-graph/pom.xml` |
| 7 | The browser's edge weight is `data.count`, else the numeric suffix of `data.kind` (`"x · 3"`), else 1; a Working Set `Link` carries `count` directly | `graph-analysis.ts:916-922`; `InvestigationEvaluator.java:94` |
| 8 | **Tie-break.** Six `localeCompare` sites order equal scores (`graph-analysis.ts:266,1361,1462,1498,1550,1616`). Every plain `.sort()` in the file (lines 340,381,397,415,514,1080,1085,1338,1524) compares UTF-16 code units — **identical to Java `String.compareTo`**. Java ports use ordinal compare. `normalizeEntityKey` lowercases or uppercases most keys, which narrows but does not close the gap | `GraphAlgorithms.java:91-93`; `entity-key.ts:22,42` |
| 9 | Existing long-running work is `JobService` (`inspecto-engine`): ad-hoc runs via `submitAdhocRun`, poll `GET /jobs/runs/{id}`, `202` + `Location` on trigger. `JobRun` is `(runId, job, type, trigger, start, end, status, durationMs, message)` — **no progress field**; the only `cancel` in `JobService` is the cron handle. I found no running-run cancel. `inspecto-engine` is banned for `la-*` | `JobService.java:724-777,1146-1180`; `JobRoutes.java:95-99,164-168`; `JobRun.java`; `tools/check-module-deps.mjs:32-33` |
| 10 | Streams: SSE exists for signals (`SignalRoutes.java:150-185`, `HostContext.sseStreams()`); `SseStreams` is package-private in `inspecto` — the host, not the SPI | `SignalRoutes.java`; `SseStreams.java:17` |
| 11 | `la-api` reaches `la-graph` today **not at all**. `ALLOWED['inspecto-la-core']` / `['inspecto-la-api']` do not list `inspecto-la-graph`, and each pom carries a matching enforcer allowlist; `inspecto-la-graph` itself is `[]` (JDK only) | `check-module-deps.mjs:24-34` |
| 12 | LA read routes (traversal, pattern, working-set) carry no `withCapability`; access is per-Investigation (`openForRead`: owner, Case member, R3, PDP). Capability-gated LA routes use `canManageIncidents`. 27 `LINK_*` event types are in `inspecto-audit-spi` (Decision 3 of D-1: they move to `la-core` when owed) | `InvestigationRoutes.java:1587-1599`; `InvRoutes.java:111`; `EventType.java:146-258`; `Roles.java:115` |

## 2. Target shape

```
 SPA  ── POST /inv/graph/runs ──►  la-api  GraphRunRoutes ──► la-core  GraphEngine (SPI) ◄── InMemoryGraphEngine ──► la-graph (pure JDK)
        ◄── 202 + Location ──        │                         ▲   GraphRunService (executor · run table · budget · cancel)
        GET /inv/graph/runs/{id}     │                         └── GraphInput (Working Set → Graph + weights, §3.2)
        POST …/{id}/cancel           └─ audit: LINK_GRAPH_* ──►  later: SqlGraphEngine (D-3, over the edge index) — SEAM ONLY here
```

### 2.1 The `GraphEngine` SPI (in `la-core`, package `com.gamma.la.core`)

`la-core` is the home: `la-graph` must stay JDK-only (fact 6, `ALLOWED = []`), and the SPI needs `Budget` and the input type that bind to the
Working Set. `la-core` then depends on `la-graph` — an allowlist addition (§5 step 1).

```java
interface GraphEngine {                      // one implementation per backing store
    String id();                             // "memory" now; "sql-index" at D-3
    Set<Algorithm> supports();               // the catalogue below
    GraphResult run(Algorithm a, Map<String,Object> params, GraphInput in, RunControl ctl) throws GraphAborted;
}
```

* `Algorithm` is an enum of the 28 ported ids; each carries `Cost {SYNC, JOB}`, a declared parameter schema, and the input it needs (`Weights` yes/no).
* `RunControl` is defined in **`la-graph`** (JDK only): `checkpoint()` (throws `GraphAborted` on cancel or deadline), `progress(done,total)`, `budget()`. The 28
  algorithms get an overload taking it; the existing signatures delegate with a no-op `RunControl`, so **parity fixtures are untouched**.
* `GraphResult` = `{ kind: scores|selection|groups|graph, payload, order: "canonical-v1" }`.
* Engines: **`InMemoryGraphEngine`** (la-graph over a materialised Working Set) is the whole of D-4. **`SqlGraphEngine`** over the Parquet edge index and any DuckPGQ
  engine are D-3/D-2: D-4 only guarantees the SPI does not mention memory — `GraphInput` is an interface (`nodes()`, `edges()`, `weightOf(edgeId)`), so an
  index-backed input can stream instead of materialising.

### 2.2 Algorithm catalogue (classification is a complexity estimate; the Java timing bench is owed, §6)

| Class | Algorithms | Why |
|---|---|---|
| **SYNC** (linear-ish; answer inline) | `shortestPath` `neighborhood` `egoNetwork` `degreeCentrality` `connectedComponents` `kCore` `triangleCount` `articulationPoints` `bridges` `isForest` `descendants` `weightedShortestPath` `maximumSpanningForest` `jaccardSimilarity` `pageRank` | One pass, fixed iteration count (browser measured 60 ms at 2 000 nodes for most — plan §1.7 via `graph-analysis.ts:17-22`) |
| **JOB** (super-linear or unbounded) | `betweennessCentrality` `closenessCentrality` `suspicionScore` `louvainCommunities` `detectCommunities` `cliques` `findCycles` `allPaths` `maxFlow` `linkPrediction` `eigenvectorCentrality` `katzCentrality` `hits` | Quadratic or worse in nodes, or exponential in path/clique count; all need `checkpoint()` in the hot loop |

A SYNC algorithm still goes through the job path when the graph exceeds its per-algorithm node threshold (decision A).

## 3. The pieces

### 3.1 Async job model — submit, progress, cancel, result

Reuse or not (fact 9): `JobService` is the right *idea* (runId, 202 + `Location`, poll) but cannot be used as-is — it lives in `inspecto-engine` (banned for
`la-*`), `JobRun` has no progress, and there is no running-run cancel. Options in Decision 1. Recommended: **an LA-owned `GraphRunService` in `la-core`** (bounded
`ExecutorService`, an in-memory run table, cooperative cancel), mirroring the `JobRoutes` wire shape so the SPA reuses one polling pattern; the bridge may
mirror terminal states into the Job ledger later.

* **States:** `QUEUED → RUNNING → COMPLETED | CANCELLED | BUDGET_EXCEEDED | FAILED`. All four terminals are final and observable.
* **Budget (stated, enforced, echoed).** `budget = { maxNodes, maxEdges, timeoutMs }`. Defaults come from per-space settings (new keys beside the existing
  caps in `LinkAnalysisSettings`, `link-analysis.toon`) with hard server ceilings, clamped and echoed like `InvRoutes`' `fences` (fact 4).
  The budget is checked **before** work (size) and **during** (deadline via `checkpoint()`).
* **"Never a silent cap" means three things.** (1) The server never returns a smaller answer shaped like a complete one: a run past its budget ends
  `BUDGET_EXCEEDED` with **no result payload**, the budget, and the measured size (`{nodes, edges}`); (2) any list-limited answer sets `truncated` +
  `total` + the `limit` that cut it; (3) every response echoes `budget` and `consumed {nodes, edges, elapsedMs}`. The UI shows the exact numbers and one next action
  (raise the budget if allowed, filter the Working Set, or pick a cheaper algorithm) — the browser's bare `"is capped at N nodes"` string (fact 3) is retired
  once the server path exists.
* **Progress:** `GET` run returns `progress {done, total, phase}` from `RunControl.progress`; polling at 1 s is the default. An SSE stream is optional
  and out of D-4 (fact 10: `SseStreams` is host-side).
* **Cancel:** `POST /inv/graph/runs/{id}/cancel` sets the flag; the run ends `CANCELLED` at its next `checkpoint()` (bounded latency, stated in the response as
  `cancelRequested`). Idempotent; a finished run answers 409.
* **Retention:** results keyed by `(Relation.key(), algorithm, params, weightsSpec)` — deterministic, so the same request is a cache hit (the Working Set already
  works this way, fact 5). TTL and size bound in settings.

### 3.2 `GraphInput` — the adapter from a Working Set (fact 6, 7)

`WorkingSetGraphInput` (in `la-core`, next to `InvestigationEvaluator`) builds from `Relation.tables()`: `Node(id,label)` from `entities` (label = id's display key,
the same the SPA draws), `Edge(id,source,target)` from `links`. **Edge ids must equal the ids the SPA mints** (`LinkIds`, `InvRoutes`/`WorkingSetRoutes`' wire id
of D-U9) or a returned `Selection.edgeIds` cannot be highlighted — this is the adapter's one hard requirement and its proof. Weights: `Link.count` becomes
`weightOf(edgeId)`, matching `edgeWeight()` (`graph-analysis.ts:916`) for projection-built graphs; `la-graph` stays unchanged (it already takes a `Map<edgeId,Double>`).
Closed-graph assumption (§7.13): the adapter drops any link whose endpoint is not an `entities` row, and **reports the count dropped** in the result (not silent).
Edge kinds are not needed by any of the 28 algorithms; a kind filter is applied in the adapter (`kinds` param), not in `la-graph`.

### 3.3 Route contract (sketch — paths are new; none collides with `InvRoutes`/`WorkingSetRoutes`)

| Route | Request | Success | Errors |
|---|---|---|---|
| `GET /inv/graph/algorithms` | — | `200 [{id, label, cost, params[], needsWeights}]` + `engine`, `ceilings` | 503 `CAPABILITY_UNAVAILABLE` (no engine) |
| `POST /inv/graph/runs` | `{investigationId, at?, algorithm, params?, weights: "count"\|"none", kinds?, budget?}` | `200 {result, budget, consumed}` for an inline SYNC run, else `202` + `Location: /api/v1/inv/graph/runs/{id}` + `{runId, status, budget}` | 422 `CONFIG_VALIDATION_FAILED` (unknown algorithm, bad params); 403 / 404 as `openForRead`; 413-style 422 when the **size is already over the hard ceiling**; 503 write-root |
| `GET /inv/graph/runs/{id}` | — | `200 {status, progress, budget, consumed, result?, reason?}` | 404 |
| `POST /inv/graph/runs/{id}/cancel` | — | `202 {status}` | 404; 409 if already terminal |
| `GET /inv/graph/runs` | `?investigationId=` | list of the caller's runs | — |

* **Capability gate:** reads inherit the Investigation's own access (`openForRead`, fact 12). Starting a JOB run consumes compute, so gate it: recommended a
  new capability name checked with the literal string (the repo's `CapabilityManifestTest` scanner is literal-only) — Decision 4. Cancel: the starter or an administrator.
* **Audit** (LA event class; constants live with the other 27 `LINK_*` — Decision 3 of D-1 moves them to `la-core`, so add these there, not to `audit-spi`):
  `LINK_GRAPH_RUN_STARTED`, `LINK_GRAPH_RUN_COMPLETED`, `LINK_GRAPH_RUN_CANCELLED`, `LINK_GRAPH_RUN_BUDGET_EXCEEDED`, `LINK_GRAPH_RUN_FAILED`; attrs
  `{investigationId, algorithm, nodes, edges, elapsedMs, key, engine}`. Never log params that embed entity ids unmasked.
* **New routes need the four gates** (memory: `new-route-passes-four-gates-not-one.md`): `openapi-v1.json` entry, `CapabilityManifest`, the `AbsentGeoLinkRoutes.SURFACE`
  mirror, and a real-HTTP test with a `Subject` (a Subject-less test makes `withCapability` a no-op and passes against an UNGATED route).

### 3.4 Masking (D-U6) and row scope on results

Results are computed on RAW ids (so ranks and tie-breaks are the true ones and the cache holds one entry per `Relation.key()`), then masked on the way out with
`EntityMasking.of(inv, …).apply(...)` — the same AFTER-the-cache discipline as `WorkingSetRoutes` (fact 5). Every field that can carry an id is masked: `Score.id`,
`Score.label`, `Selection.nodeIds`, group members, path nodes; edge ids use the D-U9 wire id minted from the MASKED values. Consequence to state in the UI: a masked
ranking is ordered by the raw label, so tied masked ids appear unsorted — acceptable, and truthful. **Row scope:** the Working Set was materialised from Dataset reads
already filtered by the caller's `RowScope`; the engine adds no read path of its own, so it cannot widen scope. ⚠ A cached `Relation` is shared by key: confirm the key
includes the scope (it is the sha256 of the log — scope may not be in it); if not, the result cache must be keyed per Subject scope (Decision 5; **not grounded**, §6).

### 3.5 Where it runs — the SPA fallback

Today every algorithm runs in the browser under a cap. D-4 does not remove that. Proposal: **browser-first under the cap, server above** — the SPA reads
`GET /inv/graph/algorithms` (per-algorithm node ceilings; this also fixes the stale `/bootstrap` comments, fact 2/`graph-analysis.ts:19`); at or under the
browser cap it runs locally (instant, offline, no Audit event); over it, the toolbox button becomes "Run on server" and starts a run. The SPA decides by node
count; the server only enforces. **Parity guarantee:** the 6 `graph-*-parity.fixture.json` files, asserted by both languages (Java 30 / TS 128 tests); D-4
adds one *route-level* parity test that feeds a fixture's Working Set through `POST /inv/graph/runs` and asserts the fixture's expected values (so the adapter
and ids are covered, not only the algorithms). A browser result and a server result for the same input must be equal under the rules on the fixtures (exact,
or epsilon 1e-9 for floats).

### 3.6 The canonical order (the open D-S4 residual)

Proposal **`canonical-v1`: UTF-16 code-unit order on the label, then on the id.** In Java that is what `String.compareTo` already does (`GraphAlgorithms.java:92`); in
TS it is `a < b ? -1 : a > b ? 1 : 0`, i.e. what the nine plain `.sort()` calls already do (fact 8). So the migration is **six TS call sites** (the `localeCompare`
list) plus a new fixture row with mixed case, digits, punctuation, an accented and a non-BMP label (existing fixtures use lowercase ASCII, which both orders agree
on). Cost of migration: a visible browser change only where today's order differs (uppercase before lowercase, accents after `z`, punctuation placement); entity labels
are already case-folded by `normalizeEntityKey`, so real impact is expected to be small — **measure on a corpus before signing** (§6). Rejected: a Java `Collator` (depends on
JDK/ICU version; the answer would change by host). Second tie rule owed: the `hits` eigenvector ties within 2e-16 (§7.13) — rank by canonical order after rounding scores to 12 digits.

## 4. NOT in D-4

The Parquet edge/node index (D-2), index-backed traversal and `SqlGraphEngine` (D-3), DuckPGQ (D-S2 cannot run on the pinned DuckDB 1.5.2), the external graph database,
parallel analyst sandboxes (D-S5), a server-side `matchPattern` (class C, stays in the browser), SSE progress, results shared across Investigations, and the app shell.

## 5. Ordered steps (each compiles, passes its unit tests, ships alone)

| Step | Work | Proof | Size |
|---|---|---|---|
| **1** | Add `inspecto-la-graph` to `la-core`'s pom, `ALLOWED['inspecto-la-core']`, `ALLOWED['inspecto-la-api']` (transitive) and each enforcer allowlist | `check-module-deps.mjs` green; mutation: add `inspecto-engine` to `la-core` → red in both layers | S |
| **2** | `RunControl` in `la-graph` + overloads on the 28 algorithms with a `checkpoint()` in each hot loop (betweenness, closeness, louvain, label propagation, cliques, findCycles, allPaths, maxFlow, iterative family) | All 30 parity tests unchanged and green; new tests: cancel mid-run throws `GraphAborted` per JOB algorithm; a mutant that removes a `checkpoint` is caught by a deadline test (mutant can hang — give tests a timeout, §7.13) | M |
| **3** | `canonical-v1`: change the 6 TS sites + add the mixed-label fixture row asserted in both languages | TS and Java parity green on the new row; the old `localeCompare` mutant turns the TS spec red | S |
| **4** | `GraphEngine`, `Algorithm`, `GraphInput`, `WorkingSetGraphInput`, `InMemoryGraphEngine` in `la-core` | Unit tests: adapter edge ids equal `LinkIds`/wire ids; dropped-dangling count reported; weights = `count`; SYNC results equal the fixtures | M |
| **5** | `GraphRunService` (executor, run table, budget, cancel, retention, result cache) | Tests: budget-before-work, deadline, cancel latency, terminal states, a result never present after `BUDGET_EXCEEDED` (negative test with a probe that would otherwise succeed) | M |
| **6** | `GraphRunRoutes` in `la-api` + `openapi-v1.json` + capability + `LINK_GRAPH_*` events + masking | Real-HTTP test class covering 202/200/422/403/404/409/503, masked ids, no-Subject-bypass; route-level parity test (§3.5) | M |
| **7** | SPA: `GraphRunsService`, "Run on server" over the cap, progress + cancel UI, budget-exceeded banner with numbers; footer shows server ceilings | vitest for the three states; driven in the preview on a Working Set above 750 nodes (the cap error is replaced by a run) | M |
| **8** | Docs: OKF `link-analysis` concept, GLOSSARY entry for Graph Run, plan retire | `check-vocabulary`, `check-doc-links` green | S |

Total **L** (steps 2, 4–7 are the weight). Steps 1 and 3 are independent and can run first in parallel.

## 6. Risks and what I could not ground

* **JOB/SYNC split is an estimate.** Only the browser's timings are measured (`graph-analysis.ts:17-22,49-70`); no Java timing exists. A 1-hour bench of the 13 JOB algorithms at 10³ / 10⁴ / 10⁵ nodes sets the thresholds; do it before step 5.
* ~~**Is the result cache scope-safe?** I did not establish whether `Relation.key()` (sha256 of the log, `InvestigationEvaluator.java`) varies with the caller's `RowScope`. If not, a per-Subject-scope key is needed (Decision 5).~~ Grounded in §6.1 B: safe today.
* **Edge-id agreement.** I did not read `LinkIds` against the SPA's mint end to end; the adapter proof in step 4 is the check.
* **How the SPA builds a `G6GraphData` from a Working Set** (`link-analysis-investigation.store.ts:71`) was not traced; browser and server may disagree on which links a "graph" holds (excluded, hidden) — step 4 must pin it.
* **Memory.** A 10⁶-edge Working Set materialised as `Graph` objects was not measured; `budget.maxEdges` ceiling is a guess until step 4's tests run.
* **Count discrepancy.** The feasibility plan says 34 algorithms were ported (§7.10 D-S4 row); the D-1 design says 59 `LINK_*` constants where `EventType.java` holds 27; the six classes and the §7.13 table total **28**. This design uses 28 and 27.
* **Thread model.** Whether LA may own a thread pool (versus the host's) was not checked against the Windows/service lifecycle (`ControlApi.close()` interrupts SSE only).
* Stale `limits.projectionNodeCap` at `/bootstrap` claim in `entity-projection.ts:58` is the same wrong claim `graph-analysis.ts:19` already corrected; not fixed here.

### 6.1 Measurements (2026-10-01)

Evidence for Decisions 3 and 5. **No decision is answered here.** Throwaway scripts (not committed) read the repo's own data; numbers are reproducible with Node 24 `Intl.Collator()` default (what `localeCompare` uses in the SPA) against plain UTF-16 code-unit comparison (what `String.compareTo` does in Java, `GraphAlgorithms.java:92`).

#### A. Canonical order — how often do browser order and Java order disagree?

**The six sites are confirmed** (`graph-analysis.ts`): `:266` (label), `:1361` (path, `a[0]`), `:1462` (edge `e.id`), `:1498` (label), `:1550` (`source`), `:1616` (label); `grep -c '\.sort()'` finds the nine plain sorts the design cites. Each is a tie-break after a score or weight, so order only matters among equal scores.

**Method.** Corpora (distinct values per VARCHAR column, read-only via DuckDB): every Space's sample CSV/Parquet (`spaces/{default,demo,ucc,telco-assurance,cricket-analytics,_templates}`, 1 401 columns). "Entity-like" = column name matches account/msisdn/imsi/customer/payer/payee/name/city/venue/team/player/region/sender/recipient/id/plmn (a heuristic, 266 columns). Per column: sort by code unit, then count (i) adjacent pairs the ICU collator orders the other way, (ii) positions where the two sorted lists differ. A second pass builds the real degree graph of five demo/cricket datasets and ranks nodes by degree with each tie-break (disagreement only matters among tied scores).

| Corpus | Columns (differing) | Labels | Adjacent pairs disagreeing | Positions differing | Cause |
|---|---|---|---|---|---|
| The six `graph-*-parity.fixture.json` | n/a | all lowercase ASCII **by design** | 0 | 0 | proves nothing: both orders agree on that alphabet |
| `demo` entity-like | 81 (10) | 9 210 | 10 of 9 129 (0.11 %) | 25 | upper vs lower of the same letter (`Quartz` vs `QUARTZ`, `acc-` vs `ACC-`) |
| `cricket-analytics` entity-like | 9 (2) | 155 | 3 of 146 (2.1 %) | 8 | `player_of_the_match`, `top_scorer`: case |
| `default` entity-like | 59 (1) | 294 | 1 of 235 | 6 | `REGION`: case |
| `telco-assurance` entity-like | 55 (0) | 32 328 | **0** | 0 | none |
| `ucc` (all columns; synthetic `V1_n`/`IDnnn`) | 895 (0) | 3 432 | **0** | 0 | none |
| `telco-assurance` all columns (catalogue free text) | 228 (29) | 33 899 | 43 (0.13 %) | 222 | case 34, accent 5, punct 3, digit/punct 1 |
| Typed ids `<type>:<key>` (msisdn/imsi/account/imei, `normalizeEntityKey` applied) | n/a | 8 856 | **0** | 0 | already lower-cased |
| Every group above, after `normalizeEntityKey` | n/a | n/a | 0, except 9 free-text pairs (telco catalogue) | n/a | n/a |

**Real degree ties** (what an analyst sees; score = degree, tie-break = label):

| Graph | Nodes | Nodes in a tie | Rank positions that differ (raw label) | (normalised id) |
|---|---|---|---|---|
| demo `mule_transfers` PAYER to PAYEE (3 files) | 193 | 189 | **0** (all `ACC-nnnn`, one case) | 0 |
| demo `account_links` | 7 | 6 | **6 of 7** (`ACC-1133`, `MULE-HUB-01` against a lowercase `acc-1121`) | 0 |
| demo `roaming_tap` operator names | 14 | 0 | 0 | 0 |
| cricket team-team / venue-team / top-scorer-player-of-match | 10 / 23 / 66 | 8 / 22 / 66 | 0 / 0 / 0 | 0 |

Classes that separate the orders (probe `a:1 a_1 a-1 a.1 a 1 a1 A1 b B é z Z`): code-unit order is `A1 B Z a 1 a-1 a.1 a1 a:1 a_1 b z é`; ICU order is `a 1 a_1 a-1 a:1 a.1 a1 A1 b B é z Z`. The four classes: **(1) upper vs lower case** (code units put every capital before every small letter; ICU interleaves), **(2) punctuation vs digits** (ICU puts all punctuation before digits; code units split it around them), **(3) accents** (`é` after `z` in code units), **(4) space** (first in ICU).

**Verdict for the corpora we have: cosmetic, with one user-visible case.** Over the id spelling (`normalizeEntityKey` lower-cases and strips trailing punctuation) the divergence is **zero** on every entity corpus and on all typed ids. It appears only where the SORT KEY is the raw label spelling, and only for data whose spelling mixes case (cricket player names, `REGION`, operator names, one demo account list where `acc-1121` meets `ACC-...`). Among real degree ties it moved 6 of 7 nodes in that one graph and none in the other five. What `canonical-v1` changes for an analyst: in mixed-case lists, equal-score nodes show capitals first (`ACC-1133`, `MULE-HUB-01`, then `acc-1121`) instead of alphabetical-ignoring-case; browser, Java and server then agree byte for byte. **Not established:** a non-ASCII or non-Latin entity corpus (none in the repo, so accents and apostrophes in real names such as `Müller` are unmeasured); the `hits` rounding tie (§3.6). **The design should state before signing** whether the sort key is the raw label or the normalised id: it is 6 of 7 versus 0.

*In light of §6.1:* the evidence favours (a), the cost being cosmetic on every corpus we hold, but is silent on non-Latin names, so the decision should also say whether the key is the raw label or the id.

#### B. Is a cached algorithm result scope-safe?

(a) **Does the key already include scope? No, and it does not need to.** `WorkingSetRoutes.Relation` (`inspecto-la-api`, **not** `InvestigationEvaluator`; `:89`) is cached under `<inv dir> \0 sha256(committed log) [@at]` (`:180`, LRU of 32, `:81`). The relation is computed by `InvestigationEvaluator.evaluate(log, at, null)` (`:197`): **its only input is the sealed log**. It reads no Dataset and takes no `HttpExchange` or Subject. Whatever a Dataset read returned was sealed into the log at write time by the writer.

(b) **What can vary per caller, and where it sits:**

| Caller-varying input | Where | Inside the cached value? |
|---|---|---|
| Who may open the Investigation (owner, Case member, PDP `RowScope.visible` on resource kind `investigation`) | `InvestigationRoutes.open` `:1601-1609`, via `openForRead` before `relation()` (`WorkingSetRoutes.java:105`) | No: decided every request, BEFORE the cache |
| Who may view the bound Dataset (`ComponentAccess.canView`) | `InvestigationRoutes.java:1606`; `InvRoutes.relationFor:831-835` on every live read | No: per request |
| **Row-level scope of Dataset data** | **does not exist.** `RowScope` is a resource-visibility PEP (ALLOW/DENY per record, `RowScope.java:30`); `DatasetProvider.relationSql(dataset, dataRoot, writeRoot)` (`DatasetProvider.java:78`) takes **no Subject**; `Subject.dataScopes` is read nowhere in `inspecto-la-*` or `inspecto-geo-link` (grep: 0) | n/a |
| Entity masking (D-U6/LA-19) | `EntityMasking.of(inv, ...)` (`WorkingSetRoutes.java:147`) is a function of Space settings (`EntityMasking.java:103`), the log and the bound columns' classification, with **no Subject**; applied AFTER the cache (`:148`) | No, and the token is per Investigation, not per Subject |

So two Subjects who both pass the gates see **identical raw and identical masked** rows for one Investigation at one log position. A per-Subject key component would only duplicate entries.

(c) **Existing proof?** None for two Subjects reading the same Working Set (`ControlApiInvestigationCaseShareTest` proves the 200/404 gate, not equal bodies). **New:** `inspecto-geo-link/src/test/java/com/gamma/control/ControlApiWorkingSetSubjectScopeTest.java`, real HTTP with an armed Authenticator and two Subjects. (1) Owner and Case member get identical `entities` and `links` rows and key, and the member's read is `cached:true`. (2) Under `masking_mode: all` both get the same masked tokens, and once the Dataset is restricted to its owner the member gets 404 from a warm cache while the owner gets 200 (the same call returned 200 one statement earlier, so the 404 is a real probe). Run: 2 tests, 0 failures.

**Conclusion: SAFE** to share a cache across Subjects for a result that is a pure function of the sealed log, provided (i) the Investigation and Dataset gates run before the cache lookup on every request, (ii) the cached value holds raw ids and masking is applied after, as `:148` does, and (iii) the key adds only what the computation reads: `<log hash> + algorithm + version + params + weights`. **It becomes UNSAFE the day** `GraphInput` reads a Dataset live (D-3's `SqlGraphEngine`) or a Dataset gains real row-level filtering; then the key needs the Subject's resolved scope fingerprint, computed where `relationFor` runs. Worth a guard test that fails when `Subject.dataScopes` or a Dataset row filter first reaches LA code. **Not established:** Enterprise PDP policies keyed on `resource.*` are per request and so safe here, but no Enterprise policy was run against this route.

*In light of §6.1:* option (a) minus the Subject fingerprint is enough today (key = log hash + algorithm + params + weights); keep the fingerprint as a documented trigger, not a component.

## 7. Decisions owed (operator)

⛔ None may be answered by an implementer in passing.

**Decision 1 — Job infrastructure.** (a) An LA-owned `GraphRunService` in `la-core` with the `JobRoutes` wire shape; (b) a `JobPort` implemented in the bridge over `JobService`, which first needs progress and running-run cancel added to `inspecto-engine`; (c) (b) later, (a) now.
*Recommendation:* (c) — the engine has no cancel or progress today, `la-*` cannot import it, and the ledger can mirror terminal states afterwards.
**Answer:** (c) an LA-owned `GraphRunService` in `la-core` now, with the `JobRoutes` wire shape; a `JobPort` bridge over the platform `JobService` later, once the engine has progress and running-run cancel. — operator 2026-10-01

**Decision 2 — Who decides browser vs server.** (a) Browser-first under the cap, "Run on server" above it; (b) always server when the engine is present; (c) per-space setting.
*Recommendation:* (a) — small graphs keep their instant, offline, un-audited path and D-4 changes nothing for them.
**Answer:** (a) browser-first under the cap; an explicit "Run on server" action above it. — operator 2026-10-01

**Decision 3 — Canonical order.** (a) `canonical-v1` UTF-16 code-unit order on label then id, change the six TS sites; (b) `localeCompare` semantics in Java via `Collator`; (c) leave divergent and document.
*Recommendation:* (a) — no host dependence, six edits, matches the nine `.sort()` calls already in the file; sign only after the corpus measurement in §6. In light of §6.1: the divergence is zero over normalised ids and cosmetic over raw labels in our corpora (one mixed-case graph moved 6 of 7 ranks), so (a) stands; state whether the sort key is the label or the id.
**Answer:** (a) `canonical-v1`, **with the sort key changed from the design's first proposal**: UTF-16 code-unit order on the **normalised entity id**, then the label as the final tiebreak (§6.1: zero divergence over normalised ids on every corpus; the raw-label key is the one that moves 6 of 7 ranks on the demo `account_links` graph). The six `localeCompare` sites in `graph-analysis.ts` change. — operator 2026-10-01

**Decision 4 — Capability for starting a JOB run.** (a) A new capability; (b) reuse `canManageIncidents`; (c) no gate beyond Investigation access.
*Recommendation:* (a) — starting compute is not an Incident action and not free; a literal-string gate with a real-HTTP test.
**Answer:** (a) a new capability, a literal-string gate with a real-HTTP gate test. — operator 2026-10-01

**Decision 5 — Result cache key.** (a) `Relation.key()` + algorithm + params + weights + the Subject's row-scope fingerprint; (b) per-Subject only; (c) no cache.
*Recommendation:* (a), after confirming whether `Relation.key()` already carries scope (§6). In light of §6.1: the key carries no scope and needs none today (the relation is a pure function of the sealed log; gates and masking run outside the cache); drop the Subject fingerprint, keep it as a documented trigger.
**Answer:** (a) the Working Set `Relation` key + algorithm + params + weights; **no scope component today** (§6.1 B: safe). Tripwire, to be written into the engine's tests: the day a Dataset is read live or gains row filtering, add the Subject's row-scope fingerprint — `ControlApiWorkingSetSubjectScopeTest` is the existing proof that must keep passing. — operator 2026-10-01

**Decision 6 — Over-budget outcome.** (a) Terminal `BUDGET_EXCEEDED`, no payload, numbers stated; (b) return a flagged partial result.
*Recommendation:* (a) — a partial centrality ranking reads as a complete one; "never a silent cap" is safest when no result exists.
**Answer:** (a) terminal `BUDGET_EXCEEDED`, no result payload, the budget and what was reached stated. — operator 2026-10-01

**Decision 7 — Inline answer for SYNC algorithms.** (a) `200` inline for SYNC under its threshold, `202` otherwise (one route, two outcomes); (b) always `202`.
*Recommendation:* (a) — one extra round trip for every degree centrality is not worth the uniformity.
**Answer:** (a) `200` inline for SYNC algorithms under their threshold, `202` otherwise — one route, two outcomes. — operator 2026-10-01

## 8. References

[`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.2–§7.4, §7.10–§7.13 · [`la-separation-d1-design.md`](la-separation-d1-design.md) (module layout, ports, dependency
rule, Decision 3) · [`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) (the SPA caps and the browser algorithms) ·
[`../GLOSSARY.md`](../GLOSSARY.md).
