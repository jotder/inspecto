# DuckDB 2.0 for the looping / graph-style queries — research and plan

> **Status: 🟡 RESEARCH + DESIGN 2026-10-05 — nothing built, no decision signed.** Operator goal: get the full
> advantage of DuckDB 2.0.x for Link Analysis, storage/index, lineage, hop expansion and path finding.
> Every "verified" claim below was run on `duckdb_jdbc 2.0.0-alpha43385-881` (and, where it matters, on the
> repo pin `1.5.6.0`) with tiny throw-away queries — **2 threads, one run each, an alpha build, a machine
> that was busy with another benchmark**. Treat every timing as a *direction*, not a number to quote; each
> candidate names the benchmark that must re-measure it before anything is built.

## 1. Headline

1. **DuckDB 2.0 (alpha) has no native graph syntax.** `CREATE PROPERTY GRAPH` / `GRAPH_TABLE` / `ANY SHORTEST`
   are parse errors on 2.0 and on 1.5.6, and the community `duckpgq` extension is an HTTP 404 for both
   `v2.0.0-alpha43385` and `v1.5.6`. Decision `D9` in
   [`la-separation-feasibility-plan.md`](../archived-documents/plans-archive/la-separation-feasibility-plan.md) ("DuckDB 2.0's own graph features
   when the pin moves") has therefore **no native-syntax answer yet**; what 2.0 actually offers is the
   items below.
2. **The one big, verified win is recursive-CTE `USING KEY`** (key-based recursion, with the `recurring.<cte>`
   table). It exists on 1.5.6 *and* 2.0, but on 2.0 it is **~3x faster for BFS reachability and orders of
   magnitude faster for label propagation** (§3.1). It turns "visit each node once, keep the best
   distance/parent/label" into ONE statement — the shape the index traversal, Working Set expand and
   connected-components work currently approximate with per-hop JDBC round trips and an in-JVM frontier cap of 20.
3. **Path *enumeration* (every simple path, `list_contains` cycle refusal — `recursive-paths`) gets no 2.0
   speed-up** (51 ms vs 47 ms). It stays as is.
4. **Moving the pin to 2.0 BREAKS something we ship:** the `x -> …` lambda arrow is rejected by default
   (`Deprecated lambda arrow (->) detected … SET lambda_syntax='ENABLE_SINGLE_ARROW'`). Two
   `ba_revenue_forecast_*` template files and two tests use it (§5).
5. **Cheap wins that do not need 2.0 at all**: a `RANGE`-framed window function beats a self range-join for
   burst detection by ~9-13x (§3.3), and the temporal `burst` / `periodicity` scan hauls up to 200 000 rows into
   the JVM to do what the window function does in-engine.

## 2. Inventory — every loop over SQL in the codebase

"Round trips" = JDBC `executeQuery` calls issued by a Java loop for ONE request.

| # | Place | What it does | How it loops | Caps |
|---|---|---|---|---|
| L1 | `inspecto-la-api/.../InvRoutes.java:1018` (`recursive-paths`, also the `cycles` tool, `:915-917`) | Enumerates every simple path from a start node; optional target, temporal constraint, weights | **One** `WITH RECURSIVE __walk` CTE: path as a `list`, `list_append`, cycle refusal `NOT list_contains(path, t)`, depth bound `?`, `LIMIT ?` on the recursive member | depth default 6 / max 10 (`:86-87`); `maxEdgeYield` max 100 000 (`:89`); paths max 10 000 (`:91`); 5 s timeout (`:92`); sensitive-data four-eyes `refuseIfSensitive` |
| L2 | `inspecto-la-storage/.../IndexedTraversal.java:56-110` + `inspecto-la-api/.../IndexedRecursivePaths.java` (D-3 step 5) | The same walk answered from the edge **index** (Parquet, bucketed) | Java `for depth` loop; each level = ONE `reader.edges(keys, sides, filter, perKeyLimit)` call (`IndexReader.java:138`) with the whole frontier as a key list; paths extended in the JVM | `MAX_DEPTH = 2`, `FRONTIER_CAP = 20` distinct keys per level (`:36-39`) — over either, the partial answer is discarded and the request falls back to L1 |
| L3 | `inspecto-la-storage/.../SqlGraphEngine.java:121-152` (`neighborhood` / `egoNetwork`) | k-hop induced sub-graph on the index | Java `for h < hops`, **per frontier key** one `links(reader, …, Side.OUT/IN)` lookup, then one OUT lookup per kept node for the induced edges | `MAX_HOPS = 2` (`:59`), `FRONTIER_CAP = 20` (`:61`); over-cap throws `IndexCapExceeded` ("a deeper neighbourhood runs on the Working Set") |
| L4 | `SqlGraphEngine.degreeCentrality` (`:165-195`) | Degree of seed nodes | one `reader.fold` per seed (per side) | seeds ≤ frontier cap |
| L5 | `inspecto-la-api/.../IndexedExpand.java` and `InvestigationRoutes.java:386-445, :1135` (Investigation `expand` rung, `LA-13`) | One hop-ladder rung: folded links of a frontier, direction / `minEvents` / `maxFanOut` | Simple rung: index lookups per frontier entity (2 each: out + in). Otherwise ONE flat CTE with `ROW_NUMBER() OVER (PARTITION BY anchor …)` fan-out rank. The ladder itself is client-driven — one request per rung | index frontier ≤ 20 (`IndexedExpand.FRONTIER_CAP`); flat frontier ≤ `MAX_FRONTIER` 1 000 (`InvestigationRoutes:131`); budget ≤ `MAX_EXPAND_BUDGET` 20 000 (`:134`); windowed / `minDistinctDays` / degree-bounded / merged rungs never use the index |
| L6 | `inspecto-la-core/.../PatternQueryCompiler.java:181-243` + `inspecto-la-api/.../PatternRoutes.java` (`/inv/pattern/branching`) | Staged branching motif: stage k legs only out of nodes stage k-1 reached, in time order | **One** SQL with chained CTEs `__e0 … __s{k}` (one pair per stage), `GROUP BY` distinct legs, `ORDER BY stage, s, t, ts`; matching then done in Java by `BranchingPatternEngine` | stages ≤ 8 (`PatternQueryCompiler:60`); `MAX_LEGS = 100 000` leave DuckDB (`PatternRoutes:63`); matches ≤ 1 000; Java `WORK_BUDGET = 500 000` leg examinations (`BranchingPatternEngine:25`) |
| L7 | `PatternRoutes.scan` (`/inv/pattern/temporal`) and `TemporalFindings` (the `temporal` op) | Burst / periodicity of each link's (or entity's) event times | One bound statement pulls **up to 200 000 rows** to the JVM, which groups and detects (no window function) | `TEMPORAL_MAX_ROWS = 200 000`, limit ≤ 1 000, 5 s timeout; a tail past the row cap → `rowCapped` |
| L8 | `IndexedTemporal` (`inspecto-la-api`) | Same temporal scan fed from the index | index read, JVM detection | same row cap |
| L9 | `inspecto-la-graph/.../GraphPaths.java`, `GraphStructure.java`, `GraphIterative.java`, `GraphCentrality.java` (28 ported algorithms, `D-S4`) | Shortest path, flow, components, k-core, cycles, centrality, iterative (PageRank-style) | **Pure in-memory Java** over an already-loaded `Graph`; no SQL | node cap only where the caller imposes one; the index engine (`SqlGraphEngine`) covers just 2 hops / 20 keys, bigger graphs run on the Working Set |
| L10 | `inspecto-engine/.../catalog/MetadataGraphService.java:263-281` (`bfs`, metadata **lineage**) | Lineage / impact over Catalog edges | Java BFS over an in-memory edge list, depth-limited | `depth` argument; **not SQL** |
| L11 | `spaces/_templates/business-assurance/config/{jobs/ba_revenue_forecast_job,views/ba_revenue_forecast_view}.toon` | Holt-style revenue forecast | `WITH RECURSIVE s` (a sequential smoother — a genuine recurrence) | row count of the daily series |
| L12 | `inspecto-engine/.../QueryExecutor` + `QueryExecutorRecursiveCteTest` | Pins that `SELECT … FROM (<WITH RECURSIVE …>)` (the `__q` wrap) works | not a loop — the seam's capability test (decision `D-S2`) | — |
| L13 | `IndexBuilder`/`IndexBuildService` (`inspecto-la-storage`) | Builds `out/` `in/` `nodes/` Parquet `PARTITION_BY (bucket)`, `ROW_GROUP_SIZE 100000`, zstd, sorted `(bucket, key, ts)` | one `COPY` per side; incremental publishes | `BucketFunction` signed bucket count |

Not found: no `USING KEY` anywhere in `src/main`; `WITH RECURSIVE` appears only in L1, L11 (and tests). The
"cycles tool" is L1's cycle refusal plus `GraphStructure.findCycles` (L9). **`tools/bench-duckdb.ps1` does not
exist in the tree** — the existing measurement harness is the test-scope `InvTraversalBench`
(`inspecto-geo-link/src/test/java/com/gamma/control/InvTraversalBench.java`: gate **G-R4** — 5 hops over
1 000 000 edges under 350 ms through `recursive-paths` — plus spikes `D-S1` / `D-S3`; runs only with
`-Dinspecto.bench.dir=…`). Every "how to prove" below extends that class unless it says otherwise.

## 3. Verified experiments (2.0.0-alpha43385 vs 1.5.6.0)

Setup unless stated: `SET threads=2`; random graph, 20 000 nodes, 400 000 edges (mean out-degree 20); one run.

### 3.1 Recursive CTE `USING KEY`

| Query | 2.0 | 1.5.6 |
|---|---|---|
| BFS reachability to depth 6, each node once (`USING KEY (n)`, `LEFT JOIN recurring.b … WHERE r.n IS NULL`) | **47 ms**, 20 001 nodes | 161 ms, 20 001 |
| same + parent pointer `p` (for path reconstruction) | **61 ms** | 162 ms |
| plain `WITH RECURSIVE … UNION` reachability to depth 6 | 68 ms | 69 ms |
| min-label propagation (connected components, `USING KEY (n)`, `min(label) GROUP BY`) over 300 seeds | **81 ms** | **cancelled at 6 s** |
| 5-seed 2-hop neighbourhood in one statement (`unnest([…])` seeds) | 27 ms (hop sizes 5 / 114 / 2 069) | 9 ms |
| path-list enumeration (L1 shape), depth 3 → 9 975 paths; depth 4 → 198 668 paths | 33 ms / 51 ms | 15 ms / 47 ms |

Reading: `USING KEY` reaches the same node set as plain recursion (20 001 on both) and, on 2.0, at roughly the plain
CTE's cost *or better*, while also carrying distance and parent. Caveat that must be measured, not assumed: **a
recursive CTE cannot prune Parquet partitions per iteration** (the join side is a scan), so over the bucketed
index the win is unproven — that is exactly what the build items' benchmark has to answer (§6). The label
propagation result shows an order-of-magnitude regression on 1.5.6 that 2.0 does not have, but the converged
labels were not checked for correctness here.

### 3.2 Everything else run

| Feature | 2.0 | 1.5.6 | Verdict |
|---|---|---|---|
| `INSTALL duckpgq FROM community` | **HTTP 404** (`v2.0.0-alpha43385`) | HTTP 404 (`v1.5.6`) | ⛔ unavailable on both |
| `CREATE PROPERTY GRAPH`, `GRAPH_TABLE`, `ANY SHORTEST` | parse error | parse error | ⛔ **no native SQL/PGQ in either core** |
| `lambda x: …` syntax | ok | ok | ✅ works on the pin already |
| `x -> …` arrow | **rejected by default**; `SET lambda_syntax='ENABLE_SINGLE_ARROW'` restores it (verified) | ok | ⚠ breaking change on pin move |
| `ASOF JOIN` | ok | ok | ✅ both |
| `LATERAL`, `UNNEST(…, recursive := true / max_depth := n)`, `list_reduce` | ok | ok | ✅ both |
| `MERGE INTO … WHEN MATCHED / NOT MATCHED` (on a PK table) | ok | ok | ✅ both |
| `VARIANT` type, `'{…}'::variant` | ok | ok | ✅ both |
| `COPY … PARTITION_BY (k)` + `write_partition_columns`, hive read | ok | ok | ✅ both |
| `parquet_version v2`, `bloom_filter_false_positive_ratio` options on `COPY` | accepted | accepted | ✅ accepted; **no measurable effect** on a sorted 1 M-row file (bloom offsets were NULL for the INT columns on both; point lookups 2-6 ms on both) |
| `json_serialize_sql` of a `USING KEY` query and of an `ASOF JOIN` | parses (`SELECT_NODE`, no error) | — | ✅ the `SqlGuard` parse-tree seam can see them; the guard's node whitelist for the `USING KEY` node was NOT run |
| `INSTALL vss` / `LOAD vss` | ok | ok | ✅ loads; ⚠ HNSW index build **not verified** (my test column was a `LIST`, needs `FLOAT[N]`) |
| `ducklake`: `LOAD`, `ATTACH 'ducklake:…' (DATA_PATH …)`, `CREATE TABLE`, `SELECT` | **ok** | not compared (the 1.5.6 attach hit a pre-existing 2.0-written catalog file, an unfair test) | ✅ works on 2.0; snapshots / time travel / concurrency **not verified** |

### 3.3 Window and temporal patterns (1 000 000 events, 5 000 entities, 30 days)

| Query | 2.0 | 1.5.6 |
|---|---|---|
| burst count: `count(*) OVER (PARTITION BY s ORDER BY ts RANGE BETWEEN 3600 PRECEDING AND CURRENT ROW)` | **270 ms** | 198 ms |
| same via self range-join (`b.ts BETWEEN a.ts-3600 AND a.ts`) | 2 521 ms | 2 700 ms |
| "next event" `lead(ts) OVER (PARTITION BY s ORDER BY ts)` | 140 ms | 131 ms |
| 100 000 probes: `ASOF JOIN` next/previous event | **135 ms** | 116 ms |
| same via `LATERAL … ORDER BY ts LIMIT 1` | 290 ms | 232 ms |
| 1 M x 1 M hash join + aggregate (`a.t = b.s`) | 1 889 ms | 1 922 ms |
| `GROUP BY s,t` (980 k groups) / `list(t)` adjacency build | 79 / 46 ms | 78 / 33 ms |

Reading: **2.0 gives no general join/aggregate/window speed-up in this alpha** — the 2.0 value for us is
`USING KEY`, not raw throughput. The window-vs-range-join gap (~10x) exists on the pin today.

## 4. Candidates

Notation: **Now** = current approach · **2.0** = the 2.0 approach · benefit / risk / proof / effort.
MoSCoW is about the *DuckDB-2.0 goal*, not the whole LA roadmap.

### C1 — k-hop neighbourhood / reachability / shortest path in one `USING KEY` statement — **Must**
- **Now:** L3 (per-key JDBC lookups, 2 hops, 20 keys) and L9 `shortestPath` / `neighborhood` (in-memory, needs a loaded graph); L2 depth ≤ 2.
- **2.0:** one `WITH RECURSIVE … USING KEY (node)` over the edge relation (index `out/` or the flat Dataset), carrying `d` and a parent `p`; the target stops the recursion; the path is rebuilt by following `p`. Bound = `d < ?`; frontier = a seed list (`unnest`).
- **Benefit:** deeper than 2 hops and wider than 20 keys *inside the engine*, no per-key round trip, no discarded partial walk; shortest path without enumerating paths.
- **Risk:** (a) per-iteration partition pruning — a CTE join scans the whole index and may lose to 20 point lookups at 10^8 edges (the very figure the 20-key cap came from, ~35 ms per key); (b) all-shortest-paths (several equal parents) needs a different state shape than one parent column; (c) `SqlGuard` node whitelist; (d) the cap/four-eyes semantics (D-U7) must be re-stated, since "keys looked up" no longer bounds the work — bound by a visited-node cap and the existing 5 s timeout.
- **Prove:** `InvTraversalBench` G-R4 scenario at 10^6 and 10^8 edges, `USING KEY` vs `IndexedTraversal`/`SqlGraphEngine` for the same seeds, on **both** pins; correctness = identical reached set and distances vs `SqlGraphEngine` and `GraphPaths.shortestPath` (existing parity fixture `graph-paths-parity.fixture.json`).
- **Effort:** M (new `GraphEngine` implementation behind the existing `SqlGraphEngine` SPI; fall back to today's engine when the measured gate fails).

### C2 — Iterative graph algorithms in SQL (components, label propagation, k-core style) — **Should**
- **Now:** L9 in-memory Java only; a graph past the caller's node cap cannot be analysed server-side.
- **2.0:** `USING KEY` label propagation (verified 81 ms vs >6 s on 1.5.6); PageRank-style iteration is *not* a fit (a fixed-point over all keys each round — `USING KEY` updates keys, so measure before promising).
- **Benefit:** component / community analysis over the whole index without loading it into the JVM.
- **Risk:** only worthwhile **if the pin moves to 2.0** (it regresses badly on 1.5.6); correctness of converged labels not yet checked; parity with `GraphStructure.connectedComponents` (same `canonical-v1` ordering) is required by the `D-S4` rule.
- **Prove:** new parity test over the existing components fixture + a bench on 10^6 edges.
- **Effort:** M.

### C3 — Temporal scan (`burst`, `periodicity`) pushed into DuckDB — **Must**
- **Now:** L7 / L8 — up to 200 000 rows to the JVM; a bigger Dataset is `rowCapped` and the answer is a prefix.
- **2.0 (also valid on the pin):** burst = `count(*) OVER (… RANGE BETWEEN w PRECEDING AND CURRENT ROW)` filtered `>= minEvents`; periodicity = gaps from `lag(ts)`, then `stddev/avg` (coefficient of variation) per series in one `GROUP BY`. Only the findings (≤ 1 000) cross to the JVM.
- **Benefit:** the 200 000-row cap stops being a correctness fence; the verified window frame is ~9-13x faster than the equivalent self range-join; far less data in the JVM.
- **Risk:** result parity with the Java detector (tie rules, rows with no parseable time → `skippedNoTime` count must be reproduced); the index-served variant (L8) must keep byte-identical output.
- **Prove:** `ControlApiInvPatternTest`-style golden fixture run against both implementations; a new bench scenario at 10^7 events.
- **Effort:** S-M.

### C4 — Branching-pattern legs: `ASOF` / `LATERAL` ordering in the compiler — **Could**
- **Now:** L6 — chained CTEs per stage; Java does the temporal matching (`WORK_BUDGET 500 000`, `MAX_LEGS 100 000`).
- **2.0:** stage k+1 "first leg after the stage-k node was reached" as `ASOF JOIN` (verified 135 ms vs 290 ms for the `LATERAL … LIMIT 1` form) instead of the post-hoc Java time check.
- **Benefit:** fewer legs leave DuckDB; tighter pruning before the 100 000-leg cap.
- **Risk:** the Java engine is a line-for-line port of the browser `matchBranchingPattern` under a shared golden fixture — changing what the SQL prunes must not change which matches exist (only which a `limit` cuts).
- **Prove:** the existing `ControlApiInvPatternTest` + `branching-parity.spec.ts` fixture stay green; bench legs-out vs stage count.
- **Effort:** M. Lower priority: the cap is rarely the limit today (not measured).

### C5 — Expand ladder for non-simple rungs — **Could**
- **Now:** L5 — windowed / `minDistinctDays` / degree-bounded / merged rungs always run the flat CTE; the index serves only frontiers ≤ 20.
- **2.0:** none verified. Candidate only: a multi-rung ladder as one `USING KEY` statement (C1's engine) instead of one request per rung.
- **Benefit/risk:** speculative; the fingerprint contract (`sha256(canonical(rows))`) makes any change to row order a breaking change to sealed Investigations. **Do not start before C1 has numbers.**
- **Effort:** L.

### C6 — Path enumeration (`recursive-paths`, `cycles`) — **Won't (no 2.0 gain)**
- Verified no change (51 ms vs 47 ms for 198 668 paths). Keep L1 and `IndexedTraversal` as they are; C1 offers a *cheaper alternative* when the caller only needs the shortest path or reachability, not all paths.

### C7 — Lambda syntax migration — **Must (a precondition of any pin move)**
- **Now:** `x -> …` in `spaces/_templates/business-assurance/config/jobs/ba_revenue_forecast_job.toon:4`, `…/views/ba_revenue_forecast_view.toon:4`, tests `IngestExpressionSandboxTest.java:85` and `SqlGuardParseTreeTest.java:46`.
- **2.0:** rewrite as `lambda x: …` (verified on **both** 1.5.6 and 2.0), or set `lambda_syntax='ENABLE_SINGLE_ARROW'` for the transition. Rewriting is better — it works on today's pin, so it can ship before the pin moves (Breaking changes are free here; no shim).
- **Also grep user-authored SQL in Space configs and the SPA** for the arrow before the move (`spaces/` and `inspecto*/src` were scanned; the SPA SQL editor's built-in snippets were not).
- **Prove:** the four sites' tests on both drivers. **Effort:** XS.

### C8 — `ducklake` as Draft / Working Set storage — **Could (research spike, not a build)**
- **Now:** per-sandbox DuckDB files over a read-only base (`la-separation-d7-design.md`, parallel analyst sandboxes).
- **2.0:** `ATTACH 'ducklake:…'` + `CREATE TABLE` works on 2.0 (verified). Snapshots, time travel, multi-writer behaviour and licensing/ABI (`check-native-licences`) were **not** verified.
- **Benefit (unproven):** versioned edge index with cheap "pin a baseline" semantics for `D-7`.
- **Risk:** the extension is repository-installed (`install_mode REPOSITORY`) — an offline/air-gapped deployment needs it vendored like other native pieces; the catalog format is version-bound.
- **Prove:** a spike with the `D-S5` admission numbers. **Effort:** M (spike), L (adoption).

### C9 — Native SQL/PGQ (`GRAPH_TABLE`, `ANY SHORTEST`) — **Won't (for now; re-spike when it exists)**
- Not available (§3.2). Re-open as a new spike the day a `duckpgq` build for the final 2.0 appears, as the signed `D9` text says. C1 is the interim.

### C10 — Parquet knobs (v2, bloom filters, `PARTITION_BY` variants) — **Won't (no measured gain)**
- Options accepted, no effect on the index-shaped file in my probe; the existing layout (bucket partition, sorted `(bucket, key, ts)`, row groups of 100 000) already prunes. Bloom filters for VARCHAR key columns are **unverified** — if node ids are strings, one targeted probe is cheap, but it is not a priority.

### C11 — `MERGE INTO`, `VARIANT`, `vss` — **Won't (not loop queries)**
- `MERGE INTO` and `VARIANT` work on the pin already; `vss` loads but HNSW use is unverified and outside the loop-query scope (entity similarity is a separate row if wanted).

## 5. Pin-move checklist (2.0 is an alpha; final not out)

1. C7 first (no 2.0 needed).
2. `tools/dependencies.lock` and `pom.xml` `duckdb.version` change together (the lock is a guard).
3. The whole suite on 2.0, then `InvTraversalBench` G-R4 on both pins — "2.0 is faster" is **only** established for `USING KEY` so far.
4. Extension availability offline: `ducklake`, `vss` are repository extensions; the core set used today (`parquet`, `json`, `icu`) is statically linked.
5. A trial `INSTALL` writes into the user profile's `.duckdb/extensions` — this research did that for `vss` and `ducklake` on both versions (no repo files touched).

## 6. Order and recommended first batch

| Order | Item | MoSCoW | Needs 2.0 pin? |
|---|---|---|---|
| 1 | C7 lambda syntax migration | **Must** | no |
| 2 | C3 temporal scan in SQL | **Must** | no |
| 3 | C1 `USING KEY` traversal engine | **Must** | measure on both; shine on 2.0 |
| 4 | C2 components by label propagation | Should | yes |
| 5 | C4 branching legs with `ASOF` | Could | no |
| 6 | C8 `ducklake` spike | Could | yes |
| 7 | C5 expand ladder in one statement | Could (after C1) | — |
| — | C6, C9, C10, C11 | Won't (now) | — |

**Recommended first batch — four parallel lanes** (disjoint files, so they can run concurrently):

1. **Lane A — C7 lambda migration** (XS): 2 TOON templates + 2 tests; run on both drivers. Files: `spaces/_templates/business-assurance/...`, `IngestExpressionSandboxTest`, `SqlGuardParseTreeTest`.
2. **Lane B — C3 temporal scan in DuckDB** (S-M): `PatternRoutes.scan`, `TemporalFindings`, `IndexedTemporal`; golden-fixture parity first, then a 10^7-event bench. Keeps the wire contract and `rowCapped` semantics.
3. **Lane C — C1 `UsingKeyGraphEngine` spike + bench** (M): a new implementation behind the `GraphEngine` seam in `inspecto-la-storage`, plus a new `InvTraversalBench` scenario (reachability + shortest path, 10^6 and 10^8 edges, both pins). **Gate:** adopt only if it beats `IndexedTraversal` at the same seeds at 10^8 edges; otherwise keep it as the deeper-than-2-hops engine only. No route change in this lane.
4. **Lane D — Pin-move rehearsal** (S): a throw-away worktree on `duckdb.version = 2.0.0-alpha43385-881` running the module tests that touch DuckDB (`inspecto-sql`, `inspecto-engine`, `inspecto-la-*`) to list *every* 2.0 incompatibility beyond the lambda arrow — the research only proved a handful of statements. Output: a findings list added to `docs/BACKLOG.md`; no merge.

Lane C and D need the machine quiet (benchmark timings); A and B are safe to run anywhere.

## 7. What could not be verified (do not cite as fact)

- HNSW / `vss` index use (only `INSTALL`/`LOAD`).
- `ducklake` snapshots, time travel, concurrent writers, offline vendoring.
- Bloom filters on VARCHAR columns; Parquet v2 read/write speed on a realistic file.
- `USING KEY` over a **Parquet index scan** (partition pruning per iteration) and at 10^8 edges — only a 400 000-row in-memory table.
- Correctness of the converged component labels; `PageRank`-style iteration.
- The `SqlGuard` allow-list behaviour for the `USING KEY` node (only that `json_serialize_sql` parses it).
- Adaptive/out-of-core join and aggregate claims from the 1.4-1.5 / 2.0 release notes: the only join/aggregate probe (1 M x 1 M) showed no difference, so no claim is made either way.
- The 2.0 final release (this is an alpha; behaviour and the extension repositories can change).

## References

- [`la-separation-feasibility-plan.md`](../archived-documents/plans-archive/la-separation-feasibility-plan.md) §7.10 — spikes `D-S1`..`D-S5`, decision `D9` (graph engine order), `DuckPGQ` dropped 2026-10-01.
- [`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) — the Link Analysis concept.
- `inspecto-geo-link/src/test/java/com/gamma/control/InvTraversalBench.java` — the measurement harness (gate `G-R4`).
- `docs/GLOSSARY.md` — canonical vocabulary (Dataset, Working Set, Investigation, Pipeline).
