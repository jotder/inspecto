# LA separation — D-3 design (the edge/node index, its builder, and the index-backed engine)

Option D's phase **D-3** ([`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.4, §7.8, §7.10.1): an LA-owned
**edge/node index** (Parquet, queried by DuckDB, in an LA-specific partitioning scheme), the **builder** that produces it, and an
**index-backed `GraphEngine`** — so traversal and graph runs stop re-scanning a flat Dataset per level. D-S1 and D-S3 measured the gap
(it is volume, not depth); D-4 shipped the engine SPI over the in-memory Working Set and left the seam. This file is the design the
operator signs before any step. When this and the code disagree, the code wins — re-ground.

**Status: 🟡 DRAFT 2026-10-02 — decisions 1–8 OPEN, nothing built.** Answers are left empty on purpose. Every claim cites a file read on
2026-10-02 (`249e881b6`) or a spike number from §7.10.1; anything not grounded says so.

## 1. Grounded facts (2026-10-02, `249e881b6`)

### 1.1 How LA reads data today

| # | Fact | Where |
|---|---|---|
| 1 | LA reads a Dataset through ONE port, `DatasetProvider` (`dataset`, `datasets`, `relationSql`, `run`, `runPlanned`, `predicate`), discovered through `DatasetProviders` (`SpiSlot`; absent ⇒ `503 CAPABILITY_UNAVAILABLE`). The bridge implements it (`EngineDatasetProvider`) over `DatasetRead` / `QueryExecutor` / `ConditionSql`. | `inspecto-la-core/.../DatasetProvider.java`, `DatasetProviders.java`, `inspecto-geo-link/.../EngineDatasetProvider.java` |
| 2 | **The port cannot bulk-copy.** `run` returns `List<Map<String,Object>>` bounded by `limit` (+1 to detect truncation); there is no `COPY … TO` and no streaming. A builder therefore cannot write its index through `DatasetProvider.run`. What it CAN take from the port is `dataset()` + `relationSql()` — a **trusted SQL string** (`read_parquet(...)` glob or a view's derived SQL) usable on any DuckDB connection. | `DatasetProvider.java`; `DatasetRelation.java` ("the returned SQL is trusted … the only place file-reading functions appear") |
| 3 | Every `run` opens a **fresh temp DuckDB** (`SqlSandbox.open`), registers the Dataset as a view over `relationSql`, then runs the statement sealed (`enable_external_access=false`, `lock_configuration=true`, optional `allowed_directories`). Auto-install and auto-load of extensions are OFF; Parquet needs no extension. Positional binds are `List<String>` only. G-R4 recorded that a separate column probe "cost a second temp-DB open/register/close per request" (no number written down). | `QueryExecutor.run`, `SqlSandbox.java` (`open`, `seal`) |
| 4 | **R3 today is Dataset-level, not row-level.** `InvRoutes.relationFor` = unknown ⇒ 404; `ComponentAccess.canView` (owner / shares / data scopes on the *component*) false ⇒ the SAME 404; then `relationSql`. `RowScope.visible` judges a whole *resolved resource* (an Investigation) through the Enterprise PDP; it is not a filter on rows inside a Dataset. I found **no per-row filter inside a Dataset** (`DatasetRelation` adds only calculated columns). `GraphRunRoutes.scopeFingerprint` already exists as a cache-key tripwire for "the day a Dataset is read live or gains row filtering". | `InvRoutes.java` (`relationFor`), `ComponentAccess.java`, `RowScope.java`, `GraphRunRoutes.java` |
| 5 | **The Working Set is a pure function of the sealed log.** `InvestigationEvaluator` never reads a Dataset: every `expand` carries its SEALED rows (D-E3). `datasetVersion` in the write-once header is **always `null`** — "no version-addressable Dataset read exists in the backend"; the Dataset name and read time are weak provenance, "explicitly NOT a replay pin". The relation cache (`WorkingSetRoutes`, 32 entries, key = log hash) and the Graph Run cache sit above this. | `InvestigationEvaluator.java`, `InvestigationRoutes.java` (class doc), `WorkingSetRoutes.java` |
| 6 | An Investigation binds its edge shape at create: `dataset`, `sourceCol`, `targetCol`, optional `linkKindCol`, `timeCol` + `timeColZone`. `recursive-paths` takes the same columns per request plus `weightCol`, `temporalConstraint`, an arbitrary condition-tree `filter`, and `direction`. Entity ids are the **raw column values cast to VARCHAR**; normalisation (`EntityTypes.normalise`, a closed set that must agree with `entity-key.ts`) is applied when matching Entity Lists, not when reading edges. | `InvestigationRoutes.create`, `InvRoutes.recursivePaths`, `EntityTypes.java` |
| 7 | `POST /inv/traversal/recursive-paths`: ONE recursive CTE; fences INSIDE the recursion (depth bound `?`, default 6 / max 10; per-level `LIMIT` edge yield, default 10 000 / max 100 000; cycle refusal by `list_contains`; 5 s statement timeout via `traversalPolicy()`, threads `assist.sql.traversal_threads` default 4); `__walk` is `MATERIALIZED`; `__e0` stays inlined (materialising it copies the whole relation, 2× slower at 10⁸). Gate G-R4. Four-eyes: `refuseIfSensitive(rows = depth × yield, fanOut = yield)`. | `InvRoutes.java` ~l.884–1040 |
| 8 | The Graph Run engine SPI is built: `GraphEngine` (`engineId`, `supported`, `run(Algorithm, params, GraphInput, RunControl)`), `InMemoryGraphEngine`, `GraphRunService(engine, limits)` (ONE engine per service), `GraphBudget{maxNodes,maxEdges,timeoutMs}` (checked BEFORE work; terminal `BUDGET_EXCEEDED` names reason and measured size). ⚠ **`GraphInput` is a `record` of lists, not the interface D-4 §2.1 promised**; `GraphRunService.Request` carries it materialised. `RunView` already carries `engine`. | `GraphEngine.java`, `GraphInput.java`, `GraphRunService.java`, `GraphBudget.java` |
| 9 | Catalogue (28): index-shaped (bounded neighbourhood walks) = `shortestPath`, `allPaths`, `neighborhood`, `egoNetwork`, `descendants`, `degreeCentrality`; global/iterative (whole graph or all-pairs) = `connectedComponents`, `kCore`, `triangleCount`, bridges / articulation points, centralities, communities, cliques, `maxFlow`, `linkPrediction`, `findCycles`, …; weighted (`weightedShortestPath`, `maximumSpanningForest`) need weights. Working-Set `degree` folds parallel edges of one `(source,target,kind)` into one Link — an index of raw rows would count differently unless it folds too. | `Algorithm.java`, link-analysis OKF §*Graph Run* *Input rows* |
| 10 | Jobs: an **open registry** (`JobTypeProvider` → `JobTypeRegistry`, `JobService`, `JobRunLedger`) in `inspecto-engine`, which `la-*` may not import (`ALLOWED` in `tools/check-module-deps.mjs`). The existing precedent for "write a Parquet snapshot atomically" is `MaterializeTask`: `COPY … TO *.parquet.tmp`, hide priors as `*.stale`, ATOMIC_MOVE reveal, delete. Its own caveat: a reader in the swap window sees a briefly empty table. | `JobTypeProvider.java`, `MaterializeTask.java` |
| 11 | The pin is DuckDB `duckdb_jdbc` **1.5.2.1** (`pom.xml`). `DatasetRelation` for a `physicalRef` consults the Consignment catalog to **subtract superseded files** — an append-only delta cannot represent a replaced file. Legal-hold wording exists only in `inspecto-ops` (`ObjectService`, `IncidentPurgeTask`); I found **no Dataset row-retention or legal-hold mechanism** to inherit. | `pom.xml`, `DatasetRelation.java` (`storeRelationSql`); not grounded for Dataset retention |
| 12 | Module rules: `inspecto-la-graph` (JDK only), `-la-core`, `-la-api` are governed by `ALLOWED` (a TRANSITIVE closure) AND a `maven-enforcer` `bannedDependencies` list per pom; the guard fails if the two drift. `la-core` already reaches `api, util, config, audit-spi, auth-spi, http-spi, entity-store, sql, la-graph`. Bundle staging is `tools/bundle-modules.mjs` (`from: 'professional'`). | `tools/check-module-deps.mjs`, `tools/bundle-modules.mjs` |

### 1.2 What the spikes measured (§7.10.1, 2026-09-30; i7-9850H, 32 GB, DuckDB 1.5.2, warm OS cache, heavy-tailed corpus, mean degree 5)

| Question | Result |
|---|---|
| One hop, median-degree node, p50 (p95), flat unsorted file | 20 (23) / 126 (156) / **1 031 (1 215) ms** at 10⁶ / 10⁷ / 10⁸ |
| Same, entity-hash partitioned (64 buckets) + sorted by (entity, time), **bucket predicate supplied** | 24 (28) / 39 (55) / **43 (49) ms**; *Total Files Read* 64 → 1 at 10⁸ |
| Same layout, **no bucket predicate** | 29 / 40 / 97 ms — the 10× over flat is row-group min/max skipping on the sorted key; the bucket predicate adds 2.3× at 10⁸ |
| Bloom filters | **not isolated** from min/max skipping |
| Route-shaped recursive CTE, p99-degree start, yield 10 000, p50 ms at depth 2 / 4 / 6 / 8 | 10⁶: 61 / 133 / 134 / 172 · 10⁷: 306 / 392 / 467 / 438 · **10⁸: 2 698 / 3 087 / 3 091 / 3 340** (p95 up to 6 347) |
| Conclusion | **The gap is volume, not depth.** Depth ≈ linear in rows walked (≤ depth × yield); volume ≈ linear in edges scanned because a flat Dataset is re-scanned per level. Materialising `__e0` is not the fix (2× slower at 10⁸). D9 holds; D10 confirmed. Extrapolation to 10⁹ (D21) is **not measured**: ≈ 20 GB per copy, 256–1 024 buckets proposed. |

⚠ **What the spikes did NOT measure, and this design depends on** — (a) the recursive walk was measured on the FLAT layout only; the
partitioned layout was measured for a single-key probe with a literal `hash('node')`. (b) In a recursive CTE the bucket of the next
frontier node is NOT a constant, so file-level pruning is probably lost there and only row-group skipping on the sorted key
remains (the 97 ms row). Not grounded either way. (c) A bound `?` in `bucket = ?` (the route binds ids) versus a literal was not
compared. (d) Build time and on-disk size of the index were not recorded. (e) Nothing was measured through the sandbox per-call
path of fact 3. §5 step 1 makes these the first pass criteria.

## 2. The index

### 2.1 Identity: one index per (Dataset, edge mapping)

An **edge mapping** is what an Investigation already binds (fact 6): `{dataset, sourceCol, targetCol, linkKindCol?, timeCol?, timeColZone?,
weightCol?, attrCols?}`. The index is keyed by `dataset` + a hash of the mapping; Investigations and traversal requests with the same
mapping share it. A request whose mapping or `filter` needs a column the index does not hold is **not servable** and falls back to the
flat read, saying so (Decision 8). Why per mapping and not per Dataset alone: the edge columns ARE the mapping — two mappings over one
Dataset are two different graphs.

### 2.2 Physical layout

```
<writeRoot>/la-index/<datasetId>/<mappingHash>/
    CURRENT                      one line: the active version id (atomic pointer, §2.6)
    v000042/
        manifest.json            §2.6
        out/bucket=<0..N-1>/part-0.parquet    edges keyed by SOURCE
        in/bucket=<0..N-1>/part-0.parquet     the mirrored copy keyed by TARGET
        nodes/bucket=<0..N-1>/part-0.parquet  node table keyed by id
```

(`la-index` is a working name; the directory lives under the Space write root beside `audit/` — **not grounded** which root is right
for a derived, rebuildable artefact; `dataRoot` is the alternative. Decision 1 note.)

| Table | Columns | Partition | Sort | Notes |
|---|---|---|---|---|
| `out` (edges by source) | `bucket INT` (hive key), `src VARCHAR`, `dst VARCHAR`, `kind VARCHAR`, `ts TIMESTAMP`, `w DOUBLE`, `a0..ak VARCHAR` | `bucket = hash(src) % N` | `(src, ts)` | `src`/`dst` are the **raw ids cast to VARCHAR**, exactly what `recursive-paths` reads today (fact 6), so index and flat reads agree row for row. Rows with a NULL endpoint are dropped at build and **counted** in the manifest (`droppedNull`), as the flat read drops them. |
| `in` (edges by target) | same columns | `bucket = hash(dst) % N` | `(dst, ts)` | A second full copy so a reverse hop is one bucket (D-S1 only proved the forward case). |
| `nodes` | `bucket`, `id`, `out_edges`, `in_edges`, `out_links`, `in_links` (distinct `(dst,kind)` / `(src,kind)`), `first_ts`, `last_ts` | `hash(id) % N` | `id` | `*_links` are the FOLDED counts so `degreeCentrality` agrees with the Working Set (fact 9); `*_edges` are raw rows. Feeds `degree`, exact fan-out for the four-eyes gate (today an upper bound), and "does this id exist". **No entity attributes**: no attribute source for Entities is grounded; typed keys `<type>:<key>` stay a read-time concern. |

* **Both directions cost** ≈ 2× the edge Parquet. From the plan's ≈ 20 GB per 10⁹-edge copy: ≈ 40 GB per index at 10⁹, plus the node table
  (≈ edges / 5 rows), and a **peak of ≈ 2× during a rebuild** (old and new version coexist, §2.6) — ≈ 80 GB at the D21 target.
  Derived from one approximate figure; re-measure in step 1. A `max_disk_bytes` setting refuses a build whose estimate exceeds it.
* **Time.** `ts` is the instant the Investigation's `timeCol` + `timeColZone` define (`InvestigationTime`), so the zone is part of the
  mapping hash; the session `TimeZone` of the host is never used (the host-zone trap, `QueryExecutor.run` takes an explicit zone). With no
  `timeCol`, `ts` is NULL and the sort is `(src)` only.
* **Edge kinds.** One `kind VARCHAR` column (NULL when unmapped); not in the sort, so a kind filter is a row filter within the entity's rows.
* **Bucket count `N`.** Fixed per version, recorded in the manifest, chosen at build as `clamp(pow2(ceil(edges / 4·10⁶)), 16, 1 024)` — the
  plan's "a bucket near 10⁶–10⁷ edges" (§7.10.1). 64 buckets at 10⁸ is ≈ 1.6 M per bucket as measured; 10⁹ implies 256. More buckets means
  more, smaller files (and `PARTITION_BY` fan-out cost at build — not measured). Decision 3.
* **Bucket function.** The bench used DuckDB `hash(x) % N`. DuckDB does not document `hash()` as stable across versions (**not grounded**
  either way), so the manifest records the function name AND `duckdb.version`; a mismatch at read time marks the index **stale**, never
  yields a wrong answer. Java does not reimplement the hash: the engine asks DuckDB for the bucket set of a frontier in the same
  connection (`SELECT DISTINCT hash(x) % N …`) and then issues the lookup with literal bucket values.
* **Row group size** 100 000, as benched (`ROW_GROUP_SIZE 100000`). Sorting by `(entity, ts)` inside a bucket is what gives the
  10× row-group skipping; **a delta file appended later is sorted only within itself**, which is why incremental append needs compaction (§3.3).
* **Masking boundary.** The index stores RAW ids. Masking stays where it is today: after the cache, at the route (`GraphResultJson`,
  `EntityMasking`); engines and caches hold raw ids (link-analysis OKF §*Graph Run* *Masking after the cache*). The index never holds a
  masked or pseudonymised value, so a mask-mode change needs no rebuild.
* **No extensions.** Everything above is core DuckDB (Parquet read/write, hash, recursive CTE, hive partitioning); nothing is INSTALLed or
  LOADed, so an air-gapped install needs nothing extra and `autoinstall_known_extensions=false` is untouched (fact 3). The `json` functions
  are NOT assumed; a frontier is passed as binds / literals, not parsed from JSON (**not grounded**: availability of `json` in the jar).

### 2.3 Row scope and R3 — the hard part

The index is a **derived copy**; reading it must not widen access. Grounded (fact 4): the gate on a Dataset is "may this Subject view the
*Dataset component*" (owner / shares / data scopes), evaluated on every read in `relationFor`; there is no row filter inside a Dataset.

| Option | How R3 holds | Cost |
|---|---|---|
| **A. Per-(Dataset, mapping) index, gated at read on the base Dataset** (recommended) | Every index read first runs `relationFor`'s gate on the BASE Dataset (`canView`, same 404). Sharing or revoking the Dataset takes effect on the next read because the gate is evaluated at read time, not baked into the files. The manifest stores a hash of the Dataset's **relation SQL and config**; a change to the Dataset definition (including any future row filter or calculated column) makes the index stale. | One index per mapping; two analysts with different row visibility over one Dataset cannot exist today, so nothing is lost. **Invariant to state and test:** the index is valid only while the Dataset has no per-Subject row filter; the day one lands, A must become C. |
| B. Shared index across several Datasets (a combined entity graph) | The union of Datasets has no single owner or share set; a Subject who may view A but not B would read B's edges. Needs a per-edge `datasetId` column AND a per-read filter on the viewable set. | Combinatorial gates on a hot path; also the only option that gives cross-Dataset hops. **Not recommended for D-3.** |
| C. Shared index + row-scope fingerprint | Index built once; each read applies a scope predicate keyed by the Subject's `dataScopes`/attributes (`scopeFingerprint` in `GraphRunRoutes` is the existing tripwire). | Needs a scope column in the index or one index per fingerprint; no scope semantic inside a Dataset exists to compile. Defer until row filtering exists. |

The Graph Run cache key already carries `scopeFingerprint` (fact 4), so an index-backed run needs no new cache dimension beyond the
manifest version (§2.5). Decision 2.

### 2.4 Retention, GC, disk budget

* **Retention (D11, signed: "incremental append by partition; retention inherited, never longer than the raw data").** I found no
  Dataset row-retention mechanism to inherit from (fact 11). Practical reading: when the base Dataset's files shrink or are superseded
  (`DatasetRelation` already subtracts superseded files), the index is **stale and must be rebuilt**, never appended to. The manifest's
  `baseFingerprint` (file list + sizes + mtimes of the relation's inputs, plus the relation-SQL hash) is compared on every read at
  low cost (a directory listing, **cost not measured**).
* **GC.** Keep the current version plus `keep_versions - 1` previous ones (default 2), never deleting a version younger than the longest
  Graph Run lifetime (`Limits` run TTL 1 h) so a run that started on version V can finish on V.
  A version cited in an Investigation's provenance (§2.5) is NOT protected — provenance, not a replay pin; a missing version reads as
  "no longer available".
* **Disk budget.** A per-Space `max_disk_bytes`; the estimate is `rowCount × bytesPerEdge × 2 + nodes` (bytesPerEdge from step 1); a build
  over budget is refused up front with the estimate in the message. Staging is under the same root, so a failed or cancelled build
  leaves only an unreferenced `v<id>.tmp/` that the next run deletes (the `MaterializeTask` `.tmp` discipline).

### 2.5 Staleness and "the Working Set at step N"

* **Replay is unaffected.** Step N's Working Set is a function of the sealed log (fact 5); an index that moved after the read does not
  change it. D-3 must not weaken this.
* **What the index adds is the first version-addressable read.** `datasetVersion` stays `null` (the header is write-once and the
  Dataset itself is still unversioned), but an index-backed `expand` / `neighbors` read records
  `read.index = {id, version, manifestHash}` inside the entry's SEALED read, beside the existing fingerprint of the rows. `reread` can then
  say "the index moved from v42 to v44" instead of only reporting drift. This is provenance; it is not promoted to a replay pin without a decision.
* **Index-backed Graph Run.** Pin the current version at **submit** (stored on the run, echoed in the run view); an index switch mid-run
  does not change that run; the cache key gains `manifestHash`. A run over the Working Set (today) is unchanged.
* **Stale but present.** If `baseFingerprint` or the bucket function no longer match, the index still answers (it is a consistent past
  snapshot) but every answer carries `index: {version, stale: true, reason}`; a Dataset that changed in a way that could expose removed rows
  (§2.4) is the one case that must **refuse** and fall back. Decision 6.

### 2.6 Manifest, versions, atomic switch (feasibility §7.4)

`manifest.json` per version: `version`, `builtAt`, `builder` (`full`|`append`), `duckdb.version`, `bucketFn`, `buckets`, `rowGroupSize`,
`mapping` (+ hash), `dataset`, `relationSqlHash`, `baseFingerprint`, `timeColZone`, per-table `rows` / `files` / `bytes`,
`droppedNull`, `deltas[]` (appended file groups since the last full build), `parent` (previous version). Versions are **immutable
directories**. The switch is a single-line `CURRENT` file written to a temp name and `Files.move(..., ATOMIC_MOVE)` over the old one;
readers resolve `CURRENT` once per request and then read that directory only. Unlike `MaterializeTask` (fact 10) there is no
empty window: both versions exist until GC. ⚠ Linux CI traps: build every path with `Path`, put forward slashes in SQL, never assume
Windows separators or case-insensitivity, and do not test the atomic move with a destination that exists on one OS only.

## 3. The builder

### 3.1 Where it lives

`IndexBuilder` (the COPY pipeline) and `IndexBuildService` (bounded executor, run table, progress, cancel — mirroring `GraphRunService`) live
in the new **`inspecto-la-storage`**, host-free, so the same classes serve the Inspecto bridge and a future `la-app` (§7.4 "one class used
two ways"). Its only need from the host is `DatasetProvider.dataset()` + `relationSql()` (fact 2); it runs its OWN non-sandboxed DuckDB
connection (as `MaterializeTask` does) because the sandbox forbids `COPY … TO`. So **no new `IndexBuildPort` is required for the build
itself**; the bridge contributes only a thin `JobTypeProvider` adapter (`la_index_build`) if the operator wants scheduling through the platform
`JobService`/ledger (fact 10) — recommended LATER, as D-4 Decision 1(c) did for `GraphRunService`. Decision 4.

### 3.2 Full build

1. Resolve `relationSql` via the port **after** the `canView` gate; refuse a Dataset the Subject cannot view with the same 404.
2. Estimate rows (`count(*)` over the relation) and apply `max_disk_bytes`; choose `N`.
3. Stage `v<id>.tmp/`; per direction run `COPY (SELECT CAST(hash(k) % N AS INTEGER) AS bucket, … FROM (<relationSql>) WHERE src IS NOT NULL AND dst IS NOT NULL ORDER BY bucket, k, ts) TO '<dir>' (FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000)` — the bench's own statement. A calculated column or virtual Dataset is read through `relationSql`, so the index inherits it (and `ExpressionGuard`/`SqlGuard` already ran upstream).
4. Build `nodes` from `out` and `in`; **verify**: rows(out) = rows(in) = rows(relation) − droppedNull, per-bucket file presence, node count > 0; any mismatch fails the build and deletes the stage.
5. Write `manifest.json`, rename `.tmp` → `v<id>`, switch `CURRENT`, GC.

**Cost.** Not measured (the bench generated its partitioned copies but the plan records no build time or size). Order of magnitude: a global
`ORDER BY bucket, k, ts` over 10⁸ rows per direction is a spilling sort bounded by `memory_limit` and `threads`. Step 1 pass criterion
in §5 puts a number on it; until then no build time is promised.

### 3.3 Incremental append (D11) and compaction

D11 says incremental append by partition. Design: when `baseFingerprint` shows only NEW files (no file removed or superseded — fact 11),
build a delta over just those files into `deltas/d<seq>/`, bucketed the same way, and publish a new version whose manifest lists it.
Reads union main + deltas. A delta is sorted only within itself, so skipping degrades with every delta; **compaction** (a full rebuild
folded into the next version) triggers when deltas per bucket exceed `K` (proposed 8, not measured) or on demand. Any removal or
supersession forces a full rebuild. Incremental is step 8, after full build and reads are proven (Decision 5).

## 4. The index-backed engine

### 4.1 Reads: engine-owned sealed connection, level-at-a-time from Java

Two ways to run SQL against the index: (a) per statement through `DatasetProvider.run` (fact 3: a fresh temp DB per call, binds as strings,
rows as maps), or (b) an **engine-owned `SqlSandbox` per Graph Run**: open once, register views over the pinned version's
`out`/`in`/`nodes` (`read_parquet(<version dir>/out/**/*.parquet, hive_partitioning=true)`), seal with `allowed_directories` limited to that
version directory, then run level statements on the one connection. (b) amortises the open cost across levels, and `allowed_directories`
means the connection can read **only the index**, a tighter boundary than today's. Statements are server-built from validated
identifiers and bound ids (the `recursive-paths` discipline, each also checked by `SqlGuard` as defence in depth). Recommended: **(b)**.

The multi-hop walk is **driven from Java, one statement per level**, not one recursive CTE: each level (1) asks DuckDB for the bucket set of
the frontier, (2) runs `SELECT … FROM out WHERE bucket IN (<literals>) AND src IN (<frontier binds>) [AND ts / kind predicates]`
with the per-level yield `LIMIT`, (3) builds the next frontier in Java. This is the only shape in which the bucket predicate is a constant
the planner can prune on (§1.2 (b)), and it is the shape the plan's 10⁹ extrapolation assumed ("one bucket lookup per frontier node").
Fences keep their present meaning and names: `maxDepth`, per-level `maxEdgeYield` (setting `edgeYieldCapped`), `timeoutMs`, cycle refusal
(a visited set in Java), and **never a silent cap** — a level at its yield, or a run past `maxNodes`/`maxEdges`, is reported, and a Graph
Run ends `BUDGET_EXCEEDED` with the measured size, as `GraphRunService` does today.

### 4.2 `SqlGraphEngine` and who runs what

| Class | Algorithms | How |
|---|---|---|
| **Index-native** (`SqlGraphEngine`) | `neighborhood`, `egoNetwork`, `descendants` (level walks), `shortestPath` (bidirectional BFS: `out` forward, `in` backward — the reason both copies exist), `degreeCentrality` (node table, folded counts), `allPaths` (bounded level walk with the fences) | SQL per level; no whole-graph materialisation |
| **Over a materialised bounded sub-graph** (`InMemoryGraphEngine`) | everything else, including `weightedShortestPath` and `maximumSpanningForest` (iterative, need weights) and all global / iterative algorithms | the index supplies a **bounded sub-graph**: the k-hop neighbourhood of the Working Set's seeds, built by the same level walk, capped by `GraphBudget`; the result states the sub-graph it ran on (seeds, depth, node/edge counts, `truncated`) |

A Graph Run **chooses explicitly**: a new request field `input: "workingSet" | "index"` (default `workingSet`; unknown fields stay 422).
`workingSet` is today's behaviour untouched. With `index`, an index-native algorithm goes to `SqlGraphEngine`, any other to `InMemoryGraphEngine`
over the materialised sub-graph. `GET /inv/graph/algorithms` gains `engines` per algorithm. A `RoutingGraphEngine` (one `GraphEngine`
that delegates by input type; `engineId` reported per run) lets `GraphRunService` keep its single-engine constructor.

### 4.3 `GraphInput` and bounded materialisation

`GraphInput` is a record (fact 8). Change it to a **sealed interface** with two cases: `Materialised` (today's record, byte-identical
behaviour, `of(...)` closed-graph rule and `droppedDangling` unchanged) and `IndexRef(version handle, seeds, direction, kinds, window)`. The
pre-work size check in `GraphRunService.submit` needs counts an `IndexRef` does not have, so the service asks the input for an
**estimate**: `IndexRef` answers from the node table (sum of seed degrees as a lower bound); enforcement then happens **during** the walk
and the measured figure goes in the terminal `BUDGET_EXCEEDED`. The same `GraphBudget` fields, the same ceilings, the same clamp-and-echo.

### 4.4 Traversal and expansion routes

`recursive-paths` keeps its request, response and fences. When a **current** index for exactly that `(dataset, mapping)` exists, the Space
setting `index.enabled` is on, and every named column and the `filter` refer only to indexed columns, the route answers from the index
and adds `source: {kind: "index", version, stale}`; otherwise it answers from the flat Dataset exactly as today with
`source: {kind: "dataset", reason}` — **the flat read stays the default and the fallback** (Decision 8). Equivalence is proven, not
asserted: the same planted corpus through both paths must give identical path sets when no fence fires; when the yield fence fires the
surviving rows differ (the per-level `LIMIT` has no `ORDER BY` today) and the honest answer is only that `edgeYieldCapped` is true in both.
`neighbors` and Investigation `expand` follow after (step 6).

## 5. Ordered steps (each compiles, passes its unit tests, ships alone)

| # | Step | Size | Proof |
|---|---|---|---|
| 1 | **Spike D-3-S1 (test-only, no main code)** — extend `InvTraversalBench`: (a) build time + bytes of `out`+`in`+`nodes` at 10⁷ and 10⁸; (b) one hop through the **sealed sandbox** with `allowed_directories`, a literal bucket versus a bound `?` versus an `IN`-list of literals; (c) a level statement at frontier 1 / 100 / 10 000; (d) `EXPLAIN ANALYZE` isolating bloom filters on a non-sorted key; (e) a 5-level Java-driven walk at 10⁸ versus the 3 091 ms recursive-CTE figure. | S–M | **Pass:** one hop p50 ≤ 100 ms at 10⁸ through the sandbox path; 5-level walk p50 ≤ 1.5 s at 10⁸ (half the fence, vs 3 091 ms flat); 10⁸ full build completes within 30 min on the reference laptop under the default `memory_limit`; numbers written into feasibility §7.10.1. **Fail:** ⇒ revisit Decision 3 / §4.1 before any step below. |
| 2 | **`inspecto-la-storage` skeleton** + manifest model, `IndexStore` (version dirs, `CURRENT` atomic switch, GC), pom with the enforcer list, `ALLOWED['inspecto-la-storage']`, `la-api` entry, `bundle-modules.mjs` entry. No behaviour. | S–M | `check-module-deps` green and its falsification test extended (a banned edge fails); `mvn -pl inspecto-la-storage` unit tests: switch is atomic under a concurrent reader, GC keeps N, a crashed stage is cleaned, Linux-safe paths |
| 3 | **`IndexBuilder` full build** (both directions + nodes, verification, manifest). | M | Planted-corpus golden: counts, `droppedNull`, folded `*_links`, one-hop result equals the flat read for 3 sizes; mutation: drop one bucket file ⇒ build fails; cancel mid-build leaves no `CURRENT` change |
| 4 | **`IndexBuildService` + routes** `POST /inv/index/builds`, `GET /inv/index`, `GET /inv/index/builds/{id}`, `POST …/cancel`; settings `link-analysis.toon` block `index`; `LINK_INDEX_*` audit events in `LinkEventTypes`. **The four route gates**: `openapi-v1.json` (a new route fails 13 modules far from here), `CapabilityManifest` (literal capability constant, e.g. `canBuildLinkIndex`, seeded to roles in `Roles`), the `AbsentGeoLinkRoutes.SURFACE` mirror, real-HTTP tests with an ARMED Authenticator. | M–L | Real-HTTP: 404 for a non-viewable Dataset indistinguishable from absence; 503 without write root; 409 duplicate build; capability absent ⇒ 403; disk-budget refusal names the estimate |
| 5 | **Traversal from the index** (`recursive-paths`, §4.4): selection, `source` echo, fallback with reason. | M | Flat-versus-index equivalence on the planted corpus; shared-away Dataset ⇒ 404 even though an index exists; a stale bucket function ⇒ flagged; a `filter` on an unindexed column ⇒ falls back, says why; `traversalPolicy()` unchanged |
| 6 | **`neighbors` + Investigation `expand` from the index**, recording `read.index` in the sealed read; `reread` reports an index move. | M | Existing `ControlApi*Investigation*` suites unchanged and green with `index.enabled` off; replay of a log recorded on v42 after v44 exists is byte-identical |
| 7 | **`GraphInput` sealed + `SqlGraphEngine` + `RoutingGraphEngine` + `IndexSubgraph`**, `input` field on `POST /inv/graph/runs`, `engines` in the catalogue. | L | `GraphEngineParityTest`-style: `SqlGraphEngine` == `InMemoryGraphEngine` on the same graph for the six index-native algorithms (fixtures incl. parallel edges, self-loops, a cycle); BUDGET_EXCEEDED carries the measured size; a result over a sub-graph states the sub-graph; masking after the cache unchanged |
| 8 | **Incremental append + compaction + staleness probe** (§3.3). | M–L | Append of new files ⇒ results equal a full rebuild; a removed/superseded file forces a full rebuild; compaction restores the step-1 latency |

Traps for every step: **a `-pl` or `-rf` run tests the STALE sibling jar** (install `la-core`/`la-storage` before `la-api`, or use `-am`);
a **hand-kept list mirrors real state and drifts** — `ALLOWED`, the enforcer lists, `bundle-modules.mjs`, `AbsentGeoLinkRoutes.SURFACE`
and `openapi-v1.json` are five of them; surefire's "VM crash or System.exit called?"; `-Dtest=A,B` with commas, never `+`;
never run a gate worktree under `%TEMP%`; a new Parquet fixture must be generated, not committed (no data in commits).

## 6. Risks

| Risk | Mitigation |
|---|---|
| The index is a second copy of customer data (§7.4 ⚠) | Per-Dataset, gated on the base Dataset at read (§2.3), rebuilt when the base shrinks (§2.4), `max_disk_bytes`, deletable by Dataset |
| §1.2 (a)–(e) unmeasured: the benefit through the sealed per-call path and the Java-driven walk is unproven | Step 1 is first and has a stop condition; nothing below it is built on an assumption |
| `hash()` stability across a DuckDB bump | Manifest records function + version; mismatch ⇒ stale ⇒ rebuild; never a wrong answer |
| Index and flat disagree (folding, NULL endpoints, time zone, ordering under a fence) | Equivalence tests in steps 3, 5, 7; `droppedNull` and the folded counts are explicit columns |
| Disk at 10⁹ (≈ 80 GB peak) | Budget refusal; build one direction at a time is a possible later cut (not designed) |
| Row filtering lands later and silently widens the index | Manifest `relationSqlHash` makes any Dataset-definition change stale; Decision 2 names the invariant |
| 10⁹ is an extrapolation and D-S5 (concurrency) was not run | Stated; D-3 claims ≤ 10⁸ until a 10⁹ spike exists |

## 7. NOT in D-3

* A SPA surface (a "Build index" action, a stale chip): it is host UI that enters the `link-analysis` library through a token (D-5 §7.12). D-3 ships the API only.
* Cross-Dataset graphs (Decision 2 option B), per-Investigation derived indexes, parallel analyst Drafts (D-7), a vector index (D13), an external graph database (no measured gap), DuckDB 2.0 graph features (revisit when the pin moves).
* Entity attributes in the node table; typed-key (`<type>:<key>`) columns; identity-resolution merges inside the index (`resolve` stays a Working Set view).
* A scheduler for builds, a `JobPort`, and any change to `JobService` (Decision 4).
* Replacing the flat read: it stays the default and the fallback (Decision 8).
* Promoting `read.index` to a replay pin (G-E2), or filling `datasetVersion`.

## 8. Decisions owed (operator)

⛔ None may be answered by an implementer in passing.

**Decision 1 — Index granularity and home.** (a) One index per (Dataset, edge mapping) under the Space write root; (b) one shared multi-Dataset index; (c) one per Investigation. Sub-question: write root or data root for the files (not grounded which fits retention and backup better).
*Recommendation:* (a), under the write root beside `audit/`; (b) and (c) are different products (cross-Dataset hops, D-7 Drafts) and (b) multiplies the R3 gates.
**Answer:**

**Decision 2 — Row scope (R3).** (a) Gate at read on the base Dataset, the index carries no scope, manifest `relationSqlHash` invalidates on any Dataset change; (b) shared index with a row-scope predicate per read; (c) one index per scope fingerprint.
*Recommendation:* (a) — R3 is Dataset-level today (§1.1 fact 4) and a revoked share takes effect on the next read; record the invariant "valid only while a Dataset has no per-Subject row filter" and a test that fails if `DatasetRelation` gains one.
**Answer:**

**Decision 3 — Partition key, bucket count, sort, bucket function.** (a) entity-hash of source (`out`) and target (`in`), `N = clamp(pow2(edges / 4·10⁶), 16, 1 024)` fixed per version, sort `(entity, ts)`, row group 100 000, DuckDB `hash()` with function and engine version in the manifest; (b) a fixed `N = 64`; (c) time-first partitions with entity bloom filters (the other D10 option).
*Recommendation:* (a) — D10 is signed as entity-hash and D-S1 measured 64 buckets sorted by `(entity, ts)`; the count scales with rows for the D21 target; re-confirm in step 1.
**Answer:**

**Decision 4 — Builder home and trigger.** (a) A Job Type in the bridge only (needs `JobService` progress and cancel, which `inspecto-engine` lacks); (b) an LA-owned `IndexBuildService` in `inspecto-la-storage` with routes, the build running on its own DuckDB connection; (c) (b) now, a bridge `JobTypeProvider` adapter later for scheduling and the ledger.
*Recommendation:* (c) — the same reasoning as D-4 Decision 1(c); `la-*` cannot import the engine, and the builder needs only `dataset()` + `relationSql()` from the existing port, so no `IndexBuildPort` is needed.
**Answer:**

**Decision 5 — Full versus incremental, and who triggers a build.** (a) Full rebuild only; (b) full first (steps 3–7), incremental append with compaction after (step 8), build on explicit request only; (c) auto-build when a base Dataset changes.
*Recommendation:* (b) — honours signed D11 (incremental by partition) without making it the foundation; staleness is reported, never silently acted on, until an operator asks for auto-refresh.
**Answer:**

**Decision 6 — Staleness and pinning.** (a) Serve a stale index flagged `stale` with the reason, pin the version on a Graph Run at submit, record `read.index` in the sealed read of an expand, refuse only when the Dataset changed in a way that could expose removed rows; (b) refuse any stale index (fall back to flat); (c) never pin, read CURRENT silently.
*Recommendation:* (a) — replay already cannot move (sealed reads, fact 5); this gives the first honest version-addressable provenance without a replay pin and without breaking analysts when a Dataset gets a new file. `datasetVersion` stays `null`.
**Answer:**

**Decision 7 — Module name and boundaries.** (a) One new `inspecto-la-storage` (manifest, builder, build service, `SqlGraphEngine`, `RoutingGraphEngine`, `IndexSubgraph`) depending on `la-core`, with `la-api` depending on it and `la-core` never; (b) two modules (`la-storage` for the index, `la-sql-engine` for the engine); (c) the engine inside `la-core`.
*Recommendation:* (a) — one cohesive unit, and (c) would drag DuckDB JDBC and file I/O into the model module `la-graph`/`la-core` keep host-free. `ALLOWED['inspecto-la-storage']` = the `la-core` closure plus `inspecto-la-core`, mirrored in the pom enforcer, plus the `bundle-modules.mjs` entry (`from: 'professional'`).
**Answer:**

**Decision 8 — Selection and fallback.** (a) Automatic when a current index exists and the Space setting `index.enabled` is on (default **off**), always echoing `source`; Graph Run needs an explicit `input: "index"`; (b) explicit per request everywhere; (c) automatic with no setting.
*Recommendation:* (a) — shipping default off means nothing changes until an operator opts in, the flat read stays the default and the fallback with a stated reason, and a Graph Run keeps D-4 Decision 2's "an explicit action above the cap".
**Answer:**

## 9. References

* [`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.4 (data and graph layer), §7.8 (phases), §7.9 (D9–D11, D21), §7.10.1 (D-S1, D-S3 results).
* [`la-separation-d1-design.md`](la-separation-d1-design.md) — module and dependency rule; [`la-separation-d4-design.md`](../archived-documents/plans-archive/la-separation-d4-design.md) — the engine SPI and budget semantics this builds on.
* [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) §*Graph Run*.
* Code: `inspecto-la-core/src/main/java/com/gamma/la/core/` (`DatasetProvider`, `DatasetProviders`, `GraphEngine`, `GraphInput`, `GraphBudget`, `GraphRunService`, `InvestigationEvaluator`), `inspecto-la-api/src/main/java/com/gamma/la/api/` (`InvRoutes`, `InvestigationRoutes`, `WorkingSetRoutes`, `GraphRunRoutes`), `inspecto-geo-link/src/main/java/com/gamma/geolink/EngineDatasetProvider.java`, `inspecto-geo-link/src/test/java/com/gamma/control/InvTraversalBench.java`, `inspecto-engine/src/main/java/com/gamma/query/` (`DatasetRelation`, `QueryExecutor`), `inspecto-sql/src/main/java/com/gamma/sql/SqlSandbox.java`, `inspecto-engine/src/main/java/com/gamma/job/` (`JobTypeProvider`, `MaterializeTask`), `tools/check-module-deps.mjs`, `tools/bundle-modules.mjs`.
