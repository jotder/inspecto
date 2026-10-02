# LA separation — D-3 design (the edge/node index, its builder, and the index-backed engine)

Option D's phase **D-3** ([`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.4, §7.8, §7.10.1): an LA-owned
**edge/node index** (Parquet, queried by DuckDB, in an LA-specific partitioning scheme), the **builder** that produces it, and an
**index-backed `GraphEngine`** — so traversal and graph runs stop re-scanning a flat Dataset per level. D-S1 and D-S3 measured the gap
(it is volume, not depth); D-4 shipped the engine SPI over the in-memory Working Set and left the seam. This file is the design the
operator signs before any step. When this and the code disagree, the code wins — re-ground.

**Status: DECISIONS 1–8 SIGNED 2026-10-02 (scope narrowed, Path A) — steps 1 (spike), 2 (store), 3 (builder, §5.2), 4 (build service + routes, §5.3) and 5 (traversal from the index, §5.4) done; steps 6–8 buildable.** Nothing is built except the test-only step 1 spike (results in §5.1). Every claim cites a file read on
2026-10-02 (`249e881b6`) or a spike number from §7.10.1; anything not grounded says so.

## Scope decision 2026-10-02 (Path A)

The step 1 spike (§5.1) answered the question the design left open, and the operator accepted **Path A: narrow D-3 to what the spike showed wins.** What was measured at 10⁸ edges: one hop through the sealed path **43 ms** (PASS), full build **368 s** (PASS), but the Java-driven 5-level walk **3.0 s / 4.3 s p50** (FAIL against 1.5 s). Only an equality on a single key reaches the scan as a zone-map filter; a multi-key `IN`, a join on `VALUES` and `unnest` read most row groups (50-key frontier about 3.2 s); one equality statement per key (`UNION ALL`) is the best multi-key shape but linear in k (about 0.7 s at 20 keys, 2.2 s at 50).

**The index serves:**

* **single-entity lookups** — a node-exists / degree lookup and a one-hop read (forward on `out`, reverse on `in`): one equality, 43 ms at 10⁸;
* **bounded neighbourhoods of small depth with a stated frontier cap** — **depth ≤ 2 and frontier ≤ 20 keys per level**, each key one equality statement (the `UNION ALL` shape). *Derivation from the §5.1 table (10⁸, p50):* 20 keys cost 691 ms and 50 keys 2 160 ms, so ≈ 35 ms per key; a depth-2 walk issues 1 + ≤ 20 lookups ≈ 0.75 s, inside the 1.5 s criterion with margin. Depth 3 would issue up to 1 + 20 + 20 ≈ 41 lookups ≈ 1.4 s — at the criterion with no margin, and that composed figure is **derived, not measured** — so depth 3 is out of this release; 50 keys (2.2 s) and every multi-key `IN` / join shape are over it. The cap is a design value derived from those rows; step 5 / 7 tests pin it as a setting with a ceiling, and a wider cap needs a new measurement first;
* **`degreeCentrality`** — from the node table (folded counts), no walk.

**The index does NOT serve** deep or wide multi-hop: `recursive-paths` beyond the cap, `allPaths`, `shortestPath` / `descendants` over a large frontier, and every global or iterative algorithm. These **stay on the flat recursive-CTE path and the in-memory engine over the Working Set exactly as today**. **The Java-driven deep walk (§4.1 as first drafted) is dropped.** The bidirectional BFS over the index is **deferred**: it needs a multi-key frontier per level, which is the shape the spike showed does not prune. It reopens only if a measurement shows a multi-key shape within budget (parallel per-key statements on separate connections were not tested).

Where the body below (§2, §3, §4, §5, §6, §7, §8) was written before this decision, it has been revised to match; the Decision recommendations are quoted as signed, so where a recommendation names something this scope removes (Decision 7 lists `IndexSubgraph`; Decision 8 speaks of Graph Run `input: "index"` generally) the **Scope section and §4 govern** — `IndexSubgraph` is not built, and `input: "index"` accepts only the index-native algorithms.

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
path of fact 3. §5 step 1 makes these the first pass criteria. **(a)–(e) were measured 2026-10-02 — see §5.1; (b) is now answered: file-level pruning
survives for a bucket list, but row-group pruning only for equality on ONE key, not for a multi-key frontier.**

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
| `out` (edges by source) | `bucket INT` (hive key), `src VARCHAR`, `dst VARCHAR`, `kind VARCHAR`, `ts TIMESTAMP`, `w DOUBLE`, `a0..ak VARCHAR` | `bucket = md5_number_lower(src) % N` | `(src, ts)` | `src`/`dst` are the **raw ids cast to VARCHAR**, exactly what `recursive-paths` reads today (fact 6), so index and flat reads agree row for row. Rows with a NULL endpoint are dropped at build and **counted** in the manifest (`droppedNull`), as the flat read drops them. |
| `in` (edges by target) | same columns | `bucket = md5_number_lower(dst) % N` | `(dst, ts)` | A second full copy so a reverse hop is one bucket (D-S1 only proved the forward case). **Kept under Path A:** a bounded reverse neighbourhood is the same one-key equality on this copy as the forward one is on `out` (§5.1 Q1), so the 2× cost buys a measured 43 ms reverse hop. |
| `nodes` | `bucket`, `id`, `out_edges`, `in_edges`, `out_links`, `in_links` (distinct `(dst,kind)` / `(src,kind)`), `first_ts`, `last_ts` | `md5_number_lower(id) % N` | `id` | `*_links` are the FOLDED counts so `degreeCentrality` agrees with the Working Set (fact 9); `*_edges` are raw rows. Feeds `degree`, exact fan-out for the four-eyes gate (today an upper bound), and "does this id exist". **No entity attributes**: no attribute source for Entities is grounded; typed keys `<type>:<key>` stay a read-time concern. |

* **Both directions cost** ≈ 2× the edge Parquet. From the plan's ≈ 20 GB per 10⁹-edge copy: ≈ 40 GB per index at 10⁹, plus the node table
  (≈ edges / 5 rows), and a **peak of ≈ 2× during a rebuild** (old and new version coexist, §2.6) — ≈ 80 GB at the D21 target.
  Derived from one approximate figure; **grounded 2026-10-02 by step 1 (§5.1)**: out + in + nodes = 3.43 GB at 10⁸ edges (34 bytes per edge, 1.28× the 2.67 GB flat file, not 2×), so ≈ 34 GB at 10⁹ is an extrapolation of that ratio, not a measurement. A `max_disk_bytes` setting refuses a build whose estimate exceeds it.
* **Time.** `ts` is the instant the Investigation's `timeCol` + `timeColZone` define (`InvestigationTime`), so the zone is part of the
  mapping hash; the session `TimeZone` of the host is never used (the host-zone trap, `QueryExecutor.run` takes an explicit zone). With no
  `timeCol`, `ts` is NULL and the sort is `(src)` only.
* **Edge kinds.** One `kind VARCHAR` column (NULL when unmapped); not in the sort, so a kind filter is a row filter within the entity's rows.
* **Bucket count `N`.** Fixed per version, recorded in the manifest, chosen at build as `clamp(pow2(ceil(edges / 4·10⁶)), 16, 1 024)` (measured N at 10⁸ was 32 buckets) — the
  plan's "a bucket near 10⁶–10⁷ edges" (§7.10.1). 64 buckets at 10⁸ is ≈ 1.6 M per bucket as measured; 10⁹ implies 256. More buckets means
  more, smaller files (and `PARTITION_BY` fan-out cost at build — not measured). Decision 3.
* **Bucket function (Decision 3 amendment, 2026-10-02): `md5_number_lower(entity) % N`.** The bench used DuckDB `hash(x) % N`; step 1 showed `hash()` is deterministic across
  connections, JVM processes and a two-day-old persisted index but **not reproducible in Java** and **not proven stable across DuckDB versions** (§5.1 Q2). `md5_number_lower(x)` (returns `UBIGINT`) matches Java `MessageDigest` MD5 of the UTF-8 bytes
  (bytes 8–15 read little-endian, `Long.remainderUnsigned(v, N)`) on **1 004 / 1 004 ids**, builds about 16 % slower at 10⁷ (one run each, indicative) and looks up at the same speed. MD5's output is fixed by the standard, so the engine **computes the bucket in Java with no round trip**
  and a DuckDB upgrade cannot make an index silently stale through the bucket function. The manifest still records `bucketFn` (`md5_number_lower`) and `duckdb.version`; that DuckDB keeps *this function's* byte order across versions is, like `hash()`, **not proven** (§5.1 Q2), so a mismatch marks the index stale — and because Java reproduces the bucket, a `bucketFn` mismatch can only be a **deliberate change** of the function, never silent drift.
* **Row group size** 100 000, as benched (`ROW_GROUP_SIZE 100000`). Sorting by `(entity, ts)` inside a bucket is what gives the
  10× row-group skipping; **a delta file appended later is sorted only within itself**, which is why incremental append needs compaction (§3.3).
* **Masking boundary.** The index stores RAW ids. Masking stays where it is today: after the cache, at the route (`GraphResultJson`,
  `EntityMasking`); engines and caches hold raw ids (link-analysis OKF §*Graph Run* *Masking after the cache*). The index never holds a
  masked or pseudonymised value, so a mask-mode change needs no rebuild.
* **No extensions.** Everything above is core DuckDB (Parquet read/write, hash, recursive CTE, hive partitioning); nothing is INSTALLed or
  LOADed, so an air-gapped install needs nothing extra and `autoinstall_known_extensions=false` is untouched (fact 3). The `json` functions
  are NOT assumed; a frontier is passed as binds / literals, not parsed from JSON (**grounded 2026-10-02 by step 1**: `json` is built into the pinned jar and works on a sealed, autoload-off connection, §5.1 Q4; the design still does not need it).

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
  `baseFingerprint` (file list + sizes + mtimes of the relation's inputs, plus the relation-SQL hash; **grounded as built in 5.3a**) is compared on every read
  (**grounded 2026-10-02 by step 1**: ≈ 0.1 ms per file listed with size and mtime — 0.9 ms for 1 file, 110 ms for 1 000, 943 ms for 10 000; 16 ms for 64 files in 64 subdirectories — so "low cost" holds only for a Dataset with few files, §5.1 Q5).
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
* **Stale but present.** If `baseFingerprint` or the manifest's `bucketFn` / `duckdb.version` no longer match (a bucket-function mismatch can only be a deliberate change, §2.2), the index still answers (it is a consistent past
  snapshot) but every answer carries `index: {version, stale: true, reason}`; a Dataset that changed in a way that could expose removed rows
  (§2.4) is the one case that must **refuse** and fall back. Decision 6.

### 2.6 Manifest, versions, atomic switch (feasibility §7.4)

`manifest.json` per version: `version`, `builtAt`, `builder` (`full`|`append`), `duckdb.version`, `bucketFn` (`md5_number_lower`), `buckets`, `rowGroupSize`,
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
3. Stage `v<id>.tmp/`; per direction run `COPY (SELECT CAST(md5_number_lower(k) % N AS INTEGER) AS bucket, … FROM (<relationSql>) WHERE src IS NOT NULL AND dst IS NOT NULL ORDER BY bucket, k, ts) TO '<dir>' (FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000)` — the bench's own statement. A calculated column or virtual Dataset is read through `relationSql`, so the index inherits it (and `ExpressionGuard`/`SqlGuard` already ran upstream).
4. Build `nodes` from `out` and `in`; **verify**: rows(out) = rows(in) = rows(relation) − droppedNull, per-bucket file presence, node count > 0; any mismatch fails the build and deletes the stage.
5. Write `manifest.json`, rename `.tmp` → `v<id>`, switch `CURRENT`, GC.

**Cost — grounded 2026-10-02 by step 1 (§5.1 Q3):** 1.7 s / 18.3 s / **368 s** (6.1 min) at 10⁶ / 10⁷ / 10⁸ edges for out + in + nodes on the
reference laptop under the default `memory_limit` (25 GiB = 80 % of 32 GB), peak process working set 0.3 / 2.3 / **17.9 GB**. The 10⁹ build time is not
measured (see §5.1 for what is extrapolated). Whether the global `ORDER BY bucket, k, ts` spilled to disk at 10⁸ was not checked; on a machine with less RAM it would, untested.

### 3.3 Incremental append (D11) and compaction

D11 says incremental append by partition. Design: when `baseFingerprint` shows only NEW files (no file removed or superseded — fact 11),
build a delta over just those files into `deltas/d<seq>/`, bucketed the same way, and publish a new version whose manifest lists it.
Reads union main + deltas. A delta is sorted only within itself, so skipping degrades with every delta; **compaction** (a full rebuild
folded into the next version) triggers when deltas per bucket exceed `K` (proposed 8, not measured) or on demand. Any removal or
supersession forces a full rebuild. Incremental is step 8, after full build and reads are proven (Decision 5).

## 4. The index-backed engine

### 4.1 Reads: engine-owned sealed connection, one equality statement per key

Two ways to run SQL against the index: (a) per statement through `DatasetProvider.run` (fact 3: a fresh temp DB per call, binds as strings,
rows as maps), or (b) an **engine-owned `SqlSandbox` per request**: open once, register views over the pinned version's
`out`/`in`/`nodes` (`read_parquet(<version dir>/out/**/*.parquet, hive_partitioning=true)`), seal with `allowed_directories` limited to that
version directory, then run the statements on the one connection. (b) amortises the open cost, and `allowed_directories`
means the connection can read **only the index**, a tighter boundary than today's. Statements are server-built from validated
identifiers and bound ids (the `recursive-paths` discipline, each also checked by `SqlGuard` as defence in depth). Recommended: **(b)**.

**The statement shape is fixed by the spike: one equality per key.** Each frontier key is `SELECT … FROM out WHERE bucket = ? AND src = ? [AND ts / kind predicates] LIMIT <yield>`,
with the bucket **computed in Java** (`md5_number_lower`, §2.2), and a level is the `UNION ALL` of those statements for its keys. This is the only shape in which both the
bucket predicate and the key reach the scan as constants (§5.1 Q1: 1 file read, 3 rows scanned of 10⁸, 43 ms). Reverse reads use the same shape on `in` with `dst`.
⚠ **Measured 2026-10-02 (§5.1 Q1/Q3): a multi-key `src IN (…)`, an `OR`, a join on `VALUES` and `unnest(?)` lose row-group pruning (≈ 72 % of 10⁸ rows scanned at 50 keys), and even the per-key `UNION ALL` is linear in k (0.7 s at 20 keys, 2.2 s at 50).**
So the engine walks **at most two levels and at most 20 keys per level (Scope section)**; a level whose frontier exceeds the cap, or a request deeper than the cap, is **not** answered from the index (the caller falls back to the flat path with the stated reason). The Java-driven deep multi-hop walk
that §5.1 measured at 3.0–4.3 s p50 for 5 levels at 10⁸ is **dropped**.
Fences keep their present meaning and names: `maxDepth` (ceiling 2 on the index), per-level `maxEdgeYield` (setting `edgeYieldCapped`), `timeoutMs`, cycle refusal
(a visited set in Java), and **never a silent cap** — a level at its yield is reported, and a run past `maxNodes`/`maxEdges` ends `BUDGET_EXCEEDED` with the measured size, as `GraphRunService` does today.

### 4.2 `SqlGraphEngine` and who runs what

| Class | Algorithms | How |
|---|---|---|
| **Index-native** (`SqlGraphEngine`) | `neighborhood`, `egoNetwork` (within the cap: depth ≤ 2, frontier ≤ 20 keys per level), `degreeCentrality` (node table, folded counts), and a node-exists / degree lookup | one equality statement per key (§4.1); no whole-graph materialisation |
| **Everything else** (`InMemoryGraphEngine` over the Working Set, **as today**) | `shortestPath`, `allPaths`, `descendants`, `weightedShortestPath`, `maximumSpanningForest` and every global / iterative algorithm | unchanged — the index is not consulted. `shortestPath` / `allPaths` / `descendants` would need a multi-key frontier per level, the shape §5.1 showed does not prune; the bidirectional BFS over `out` + `in` is **deferred** for that reason (Scope section) |

A Graph Run **chooses explicitly**: the request field `input: "workingSet" | "index"` stays (default `workingSet`; unknown fields stay 422).
`workingSet` is today's behaviour untouched. With `index`, an index-native algorithm goes to `SqlGraphEngine`; **any other algorithm, or an index-native one whose depth or frontier exceeds the cap, is refused with a stated reason (422 / `BUDGET_EXCEEDED` naming the cap), never silently rerouted.** `GET /inv/graph/algorithms` gains `engines` per algorithm (only the index-native ones list `index`). A `RoutingGraphEngine` (one `GraphEngine`
that delegates by input type; `engineId` reported per run) lets `GraphRunService` keep its single-engine constructor; it routes **only** the index-native cases. There is no `IndexSubgraph` (the bounded sub-graph feeding the in-memory engine): over-cap work stays on the Working Set path.

### 4.3 `GraphInput` and the pre-work estimate

`GraphInput` is a record (fact 8). Change it to a **sealed interface** with two cases: `Materialised` (today's record, byte-identical
behaviour, `of(...)` closed-graph rule and `droppedDangling` unchanged) and `IndexRef(version handle, seeds, direction, kinds, window)`. The
pre-work size check in `GraphRunService.submit` needs counts an `IndexRef` does not have, so the service asks the input for an
**estimate**: `IndexRef` answers from the node table (sum of seed degrees = the level-1 frontier); a seed set whose estimate already exceeds the frontier cap is refused up front, and enforcement then happens **during** the walk
with the measured figure in the terminal `BUDGET_EXCEEDED`. The same `GraphBudget` fields, the same ceilings, the same clamp-and-echo.

### 4.4 Traversal and expansion routes

`recursive-paths` keeps its request, response and fences. When a **current** index for exactly that `(dataset, mapping)` exists, the Space
setting `index.enabled` is on, every named column and the `filter` refer only to indexed columns, **and the request's `maxDepth` ≤ 2 and the estimated frontier ≤ 20 keys per level (seed degrees from the node table; checked again on the measured frontier after level 1)**, the route answers from the index
and adds `source: {kind: "index", version, stale}`; otherwise it answers from the flat Dataset exactly as today with
`source: {kind: "dataset", reason}` — the reason names the cause (`depth_over_index_cap`, `frontier_over_index_cap`, `unindexed_column`, `no_current_index`, …); a frontier found over the cap mid-walk discards the partial index result and re-answers from the flat path, so one response never mixes the two — **the flat read stays the default and the fallback** (Decision 8). Equivalence is proven, not
asserted: the same planted corpus through both paths must give identical path sets when no fence fires; when the yield fence fires the
surviving rows differ (the per-level `LIMIT` has no `ORDER BY` today) and the honest answer is only that `edgeYieldCapped` is true in both.
`neighbors` and Investigation `expand` follow (step 6, built: §5.5).

## 5. Ordered steps (each compiles, passes its unit tests, ships alone)

| # | Step | Size | Proof |
|---|---|---|---|
| 1 | ✅ **DONE 2026-10-02 (§5.1): one hop 43 ms PASS, 10⁸ build 368 s PASS, 5-level walk 3.0 / 4.3 s FAIL ⇒ scope narrowed to Path A.** **Spike D-3-S1 (test-only, no main code)** — extend `InvTraversalBench`: (a) build time + bytes of `out`+`in`+`nodes` at 10⁷ and 10⁸; (b) one hop through the **sealed sandbox** with `allowed_directories`, a literal bucket versus a bound `?` versus an `IN`-list of literals; (c) a level statement at frontier 1 / 100 / 10 000; (d) `EXPLAIN ANALYZE` isolating bloom filters on a non-sorted key; (e) a 5-level Java-driven walk at 10⁸ versus the 3 091 ms recursive-CTE figure. | S–M | **Pass:** one hop p50 ≤ 100 ms at 10⁸ through the sandbox path; 5-level walk p50 ≤ 1.5 s at 10⁸ (half the fence, vs 3 091 ms flat); 10⁸ full build completes within 30 min on the reference laptop under the default `memory_limit`; numbers written into feasibility §7.10.1. **Fail:** ⇒ revisit Decision 3 / §4.1 before any step below. |
| 2 | **`inspecto-la-storage` skeleton** + manifest model, `IndexStore` (version dirs, `CURRENT` atomic switch, GC), pom with the enforcer list, `ALLOWED['inspecto-la-storage']`, `la-api` entry, `bundle-modules.mjs` entry. No behaviour. | S–M | `check-module-deps` green and its falsification test extended (a banned edge fails); `mvn -pl inspecto-la-storage` unit tests: switch is atomic under a concurrent reader, GC keeps N, a crashed stage is cleaned, Linux-safe paths |
| 3 | ✅ **BUILT 2026-10-02** (as built: §5.2). Planned as: **`IndexBuilder` full build** (both directions + nodes, verification, manifest). | M | Planted-corpus golden: counts, `droppedNull`, folded `*_links`, one-hop result equals the flat read for 3 sizes; mutation: drop one bucket file ⇒ build fails; cancel mid-build leaves no `CURRENT` change |
| 4 | ✅ **BUILT 2026-10-02** (as built: §5.3). Planned as: **`IndexBuildService` + routes** `POST /inv/index/builds`, `GET /inv/index`, `GET /inv/index/builds/{id}`, `POST …/cancel`; settings `link-analysis.toon` block `index`; `LINK_INDEX_*` audit events in `LinkEventTypes`. **The four route gates**: `openapi-v1.json` (a new route fails 13 modules far from here), `CapabilityManifest` (literal capability constant, e.g. `canBuildLinkIndex`, seeded to roles in `Roles`), the `AbsentGeoLinkRoutes.SURFACE` mirror, real-HTTP tests with an ARMED Authenticator. | M–L | Real-HTTP: 404 for a non-viewable Dataset indistinguishable from absence; 503 without write root; 409 duplicate build; capability absent ⇒ 403; disk-budget refusal names the estimate |
| 5 | ✅ **BUILT 2026-10-02** (as built: §5.4). Planned as: **Traversal from the index, within the cap** (`recursive-paths`, §4.4): used ONLY when the request's depth ≤ 2 and the estimated frontier ≤ 20 keys per level, otherwise the flat path with the stated reason; `source` echo. | M | Flat-versus-index equivalence on the planted corpus (inside the cap); a depth-3 request and a 21-key frontier both fall back with their reason;  shared-away Dataset ⇒ 404 even though an index exists; a stale bucket function ⇒ flagged; a `filter` on an unindexed column ⇒ falls back, says why; `traversalPolicy()` unchanged |
| 6 | **`neighbors` + Investigation `expand` from the index**, recording `read.index` in the sealed read; `reread` reports an index move. | M | Existing `ControlApi*Investigation*` suites unchanged and green with `index.enabled` off; replay of a log recorded on v42 after v44 exists is byte-identical |
| 7 | **`GraphInput` sealed + `SqlGraphEngine` + `RoutingGraphEngine`** (index-native cases only: `neighborhood`, `egoNetwork`, `degreeCentrality`, node lookup; no `IndexSubgraph`), `input` field on `POST /inv/graph/runs`, `engines` in the catalogue. | M–L | `GraphEngineParityTest`-style: `SqlGraphEngine` == `InMemoryGraphEngine` on the same graph for the index-native algorithms (fixtures incl. parallel edges, self-loops, a cycle); a non-native algorithm or an over-cap request is refused with the cap named; BUDGET_EXCEEDED carries the measured size; masking after the cache unchanged |
| 8 | **Incremental append + compaction + staleness probe** (§3.3). | M–L | Append of new files ⇒ results equal a full rebuild; a removed/superseded file forces a full rebuild; compaction restores the step-1 latency |

Traps for every step: **a `-pl` or `-rf` run tests the STALE sibling jar** (install `la-core`/`la-storage` before `la-api`, or use `-am`);
a **hand-kept list mirrors real state and drifts** — `ALLOWED`, the enforcer lists, `bundle-modules.mjs`, `AbsentGeoLinkRoutes.SURFACE`
and `openapi-v1.json` are five of them; surefire's "VM crash or System.exit called?"; `-Dtest=A,B` with commas, never `+`;
never run a gate worktree under `%TEMP%`; a new Parquet fixture must be generated, not committed (no data in commits).

### 5.1 Step 1 results — 2026-10-02 (test-only spike, nothing in main code)

**Method.** `inspecto-geo-link/src/test/java/com/gamma/control/InvIndexSpikeBench.java` (new class beside `InvTraversalBench`; `@Tag("bench")`,
skipped unless BOTH `-Dinspecto.bench=true` and `-Dinspecto.bench.dir=<dir>` are set — verified: without them all 7 tests report *skipped*).
Corpus = the D-S1 corpus (`edges_<n>.parquet`, heavy-tailed, mean degree 5, no `kind` column, so the index here has no `kind`). Index built as §2.2 / §3.2:
`bucket = hash(key) % N`, `N = clamp(pow2(ceil(edges / 4·10⁶)), 16, 1 024)` → **16 / 16 / 32** at 10⁶ / 10⁷ / 10⁸, `ORDER BY bucket, key, ts`, `PARTITION_BY (bucket)`,
`ROW_GROUP_SIZE 100000`, `COMPRESSION zstd`, plus the `nodes` table. Reads go through a **sealed connection** replicating `SqlSandbox.sealAllowing`
(`allowed_directories` = the index dir, `enable_external_access=false`, `lock_configuration=true`, autoload off, `threads=4` as `traversalPolicy()`), views over
`read_parquet('<dir>/**/*.parquet', hive_partitioning=true)`, and every timing is **prepare + bind + drain through `PreparedStatement`** (the route's shape).
Machine: i7-9850H (6 cores / 12 threads), 32 GB, Windows 11, JDK 27, jar `duckdb_jdbc` 1.5.2.1 (`SELECT version()` reports `v1.5.2`). Warm OS cache (no drop on
Windows). ⚠ **Noise:** the machine was not otherwise idle — two peer `java.exe` processes were resident (about 0.4 GB and 0.2 GB working set, CPU not sampled); nothing of mine ran
concurrently with a timed section. One-hop and level figures are p50 / p95 over **20 samples** (first of 21 dropped); frontier-10 000 levels over 8, walks over 7, the
`frontier` shape sweep over 8 (4 at k = 1 000) — **p95 over 8 or fewer samples is effectively the maximum.** Generated index data stays under the shared bench dir (`d3idx_<n>`), never committed.

**Pass / fail against the criteria §5 step 1 states.** The criterion text names no start node for the walk; I used the D-S3 start (p99-degree, the one the 3 091 ms figure was
measured with) AND the median-degree start, and report both.

| Criterion (§5 step 1) | Measured | Verdict |
|---|---|---|
| one hop p50 ≤ 100 ms at 10⁸ through the sandbox path | **43 ms** p50 (51 p95), bound `?` src + bound bucket, sealed | ✅ **PASS** |
| 5-level walk p50 ≤ 1.5 s at 10⁸ (half the fence; vs 3 091 ms flat CTE) | **3 013 ms** p50 (median-degree start), **4 342 ms** (p99-degree start, the D-S3 start); 5 190 / 4 561 ms without a bucket predicate | ❌ **FAIL** — for the p99 start it is 1.4× SLOWER than the 3 091 ms recursive-CTE figure |
| 10⁸ full build ≤ 30 min, default `memory_limit` | **368 s = 6.1 min** (out 66 s, in 84 s, nodes 218 s); peak working set 17.9 GB | ✅ **PASS** |
| numbers written into feasibility §7.10.1 | not done in this change (design doc only) | ⏳ open |
| (d) `EXPLAIN ANALYZE` isolating bloom filters on a non-sorted key | **not run** | not measured |

Per the step's own rule, **FAIL ⇒ "revisit Decision 3 / §4.1 before any step below"** — nothing is decided here; the numbers are for the operator.
The walk semantics differ from the CTE it is compared with: the CTE enumerates paths (`list_contains` cycle refusal), the Java walk is a BFS with a visited set and a per-level `LIMIT 10000`;
the walks read 31 502 / 20 433 edges (p99 / p50 start) and reached 31 392 / 20 380 nodes, with frontiers 1 / 39 / 1 463 / 9 992 / 9 955 and 1 / 3 / 30 / 400 / 9 985.

**The five questions.**

| # | Question | Answer | Verdict |
|---|---|---|---|
| Q1 | Does a bound `bucket = ?` prune partition files like the literal? | **Yes**, and so does `bucket = hash(?) % N` computed in SQL, for one key | ✅ |
| Q2 | Is `hash()` stable; can Java compute the bucket? | stable across connections, processes, and a 2-day-old persisted index; **not proven across versions**; **not reproducible in Java** (alternative measured) | ✅ partial |
| Q3 | Build time, size, skew, lookup latency | measured to 10⁸; tables below | ✅ (a multi-key frontier is the weak point) |
| Q4 | `json`, Parquet write options in the pinned jar | all present except `ROW_GROUP_SIZE_BYTES` (errored, not needed) | ✅ |
| Q5 | Cost of the staleness fingerprint | negligible for the SQL hash; about 0.1 ms per file for the file listing | ✅ measured |

**Q1 — pruning, bound vs literal (10⁸, N = 32, median-degree node, sealed).** `Total Files Read` from `EXPLAIN ANALYZE`
(prepared, with the binds); the scan operator emitted **3 rows** of 10⁸, i.e. row-group skipping on the sorted key worked too.

| Predicate shape | p50 / p95 (ms) at 10⁶ | at 10⁷ | at 10⁸ | Total Files Read at 10⁸ |
|---|---|---|---|---|
| literal `src` + literal bucket | 21.6 / 24.4 | 27.3 / 29.8 | 44.0 / 52.8 | 1 |
| bound `src` + bound bucket, `setInt` | 17.6 / 24.0 | 29.1 / 33.3 | 43.1 / 51.4 | 1 |
| bound `src` + bound bucket, `setLong` (BIGINT vs INTEGER column) | 18.3 / 23.5 | 25.2 / 35.6 | 50.4 / 54.6 | 1 |
| bound `src` + `bucket = CAST(hash(CAST(? AS VARCHAR)) % N AS INTEGER)` in SQL | 20.8 / 21.9 | 24.7 / 28.9 | 45.1 / 68.2 | 1 |
| `IN` of one literal + `bucket IN` one literal | 16.2 / 23.6 | 26.2 / 31.9 | 44.2 / 55.1 | 1 |
| bound `src`, **no** bucket predicate | 21.0 / 23.7 | 29.7 / 33.2 | **83.9 / 99.7** | **32** (all) |
| same bound/bucket shape, **unsealed**, default threads | 15.3 / 18.4 | 24.8 / 27.4 | 40.9 / 50.4 | not recorded |

A bound parameter prunes exactly as the literal does; the seal costs about 2-3 ms at 10⁸ (within the p95 spread). The bucket predicate is worth 1.9× at 10⁸ (D-S1: 2.3×).

**Q1 / Q3 — multi-key frontier (the case §1.2 (b) worried about; random frontier of existing nodes, 10⁸, sealed, p50 ms):**

| frontier k | A `src IN (?…)` + `bucket IN` literals | B `OR` of `(src=? AND bucket=?)` | C `UNION ALL` of per-key equalities | D join `VALUES(src,bucket)` | E join `unnest(?)` | files read (A) | rows scanned of 10⁸ (A) |
|---|---|---|---|---|---|---|---|
| 1 | 30 | 25 | 26 | 26 | 27 | 1 | 2 (the result) |
| 2 | 93 | 101 | **57** | 93 | 95 | 2 | 1.7 M |
| 5 | 394 | 188 | **158** | 382 | 438 | 5 | 9.0 M |
| 10 | 1 279 | 589 | **411** | 947 | 927 | 10 | 21 M |
| 20 | 1 948 | 1 057 | **691** | 1 752 | 1 588 | 17 | 39 M |
| 50 | 3 190 | 4 122 | **2 160** | 4 155 | 3 441 | 26 | 72 M |
| 100 | 4 266 | not run | not run | 4 695 | 4 418 | 31 | 95 M |
| 1 000 | 5 027 | not run | not run | 4 406 | 4 136 | 32 | 99.7 M |

(At 10⁷ the same sweep: A 24 / 50 / 94 / 160 / 307 / 432 / 491 / 528 ms for k = 1 / 2 / 5 / 10 / 20 / 50 / 100 / 1 000; B and C reach 17.5 s and 24.8 s at k = 1 000; D / E plateau at 0.5-0.7 s.
B and C at k of 100 or more at 10⁸ were skipped as already 17-25 s at 10⁷.) **Reading:** only an equality on ONE key reaches the scan as a zone-map filter. A multi-key `IN`, a join, and the `VALUES` / `unnest`
forms read most row groups (the scan emits tens of millions of rows); the per-key `UNION ALL` form keeps pruning per key but its cost is linear in k and then worse than the scan. With
100 000-row groups and about 1 100 row groups at 10⁸, a frontier of 50 or more random keys touches almost every group, so a level statement saturates at a 4-5 s near-full scan.
The bucket predicate prunes FILES (A reads 17 of 32 files at k = 20) but gave no row-group benefit; at k = 100 / 10 000 a bucket-less statement was measured *no slower* (3.6 / 4.1 s vs 5.4 / 5.9 s with the bucket list at 10⁸; cause not investigated, p95 up to 8 s, so machine noise cannot be excluded).
Untested: running several per-key equality statements in parallel on separate connections.

Level statements as the route would issue them (`e_out WHERE bucket IN (…) AND src IN (…)`, 10⁸): **frontier 1 gives 35 ms** (81 ms without the bucket list); **100 gives 5 356 ms** (bound, p95 7 640); **10 000 gives 5 900 ms**, 56 508 rows (p95 7 060).
The `SELECT DISTINCT hash(v) % N` round trip that supplies the bucket list costs 0.9 / 3.4 / 258 ms for 1 / 100 / 10 000 ids at 10⁸ (428 ms for 10 000 at 10⁶). It is paid per level, which is why the bucketed walk is slower than the bucket-less one on small data (10⁶, p99 start: 1 741 vs 790 ms).

**Q2 — `hash()` stability and the Java bucket.**
* Two separate in-memory DuckDB instances, one JVM: identical `hash()` over 1 004 ids (including the empty string, non-ASCII, and a `7:550123456789` typed key).
* Two separate JVM processes (pids 16136 and 19396): identical MD5 over the 1 004 hash values (`d4736b35…`).
* Persisted: the D-S1 `edges_<n>_b64` copies were written on 2026-09-30 by an earlier process; recomputing `hash(src) % 64` now matches the stored `bucket` on **every row: 0 mismatches of 10⁶, 10⁷ and 10⁸**.
* ⚠ **Cross-version stability is NOT proven.** The writer's `created_by` and the runtime are the same build (`v1.5.2`, `8a5851971f`); only `duckdb_jdbc` 1.5.2.1 exists under `~/.m2`, and nothing was downloaded.
* **Java cannot compute `hash()`.** I found no documented algorithm and did not reverse-engineer one; `String.hashCode() % 64` agrees with it on 19 of 1 004 ids (chance level, 1 / 64). So "the engine supplies the bucket predicate without a round trip" is **not available with `hash()`**; the round trip above (or a Java-reproducible function) is the choice.
* **Measured alternative: `md5_number_lower(x) % N`** (returns `UBIGINT`). Java `MessageDigest` MD5 of the UTF-8 bytes, **bytes 8-15 read little-endian, `Long.remainderUnsigned(v, N)`, equals the SQL bucket on 1 004 / 1 004 ids**; the three other byte / endianness readings agree on about 1.7 % (chance). An index built with it: build 1.9 s / 21.2 s at 10⁶ / 10⁷ (vs 1.7 s / 18.3 s with `hash()`; one run each, so +16 % at 10⁷ is indicative); one-hop 19.0 / 22.3 ms p50, 1 file read; skew 1.12× at 10⁷ (max / median). It would remove the bucket round trip, but that round trip is small next to the scan-dominated levels above. MD5's output is fixed by the standard; that DuckDB keeps *this function's* byte order across versions is, like `hash()`, **not proven**, and the manifest check (function name + version) applies either way.

**Q3 — build, size, skew (out + in + nodes; 10⁸ is measured, 10⁹ is not).**

| | 10⁶ | 10⁷ | 10⁸ |
|---|---|---|---|
| buckets `N` | 16 | 16 | 32 |
| build wall time: out / in / nodes / **total** | 0.6 / 0.6 / 0.6 / **1.7 s** | 5.3 / 5.7 / 7.3 / **18.3 s** | 65.6 / 84.4 / 217.8 / **367.9 s** |
| peak process working set (`tasklist`, 2 s samples) | 0.32 GB | 2.28 GB | **17.9 GB** (DuckDB-accounted 18.2 GB; `memory_limit` 25 GiB, 12 threads) |
| `out` / `in` / `nodes` bytes | 14.5 / 14.5 / 3.6 MB | 148.1 / 147.2 / 34.3 MB | 1 542.7 / 1 534.2 / 352.1 MB |
| index total vs flat file | 32.6 MB vs 23.2 MB (1.40×) | 329.6 MB vs 249.1 MB (1.32×) | **3.43 GB vs 2.67 GB (1.28×)**; 34 bytes per edge |
| files per table; row groups (out / in / nodes) | 16; 23 / 21 / 16 | 16; 120 / 122 / 32 | 32; 1 103 / 1 097 / 241 |
| rows per bucket `out` min / median / max | 59 305 / 61 612 / 75 599 | 609 317 / 619 707 / 686 726 | 3 044 373 / 3 117 388 / 3 453 317 (max ÷ median 1.11) |
| rows per bucket `in` min / median / max | 60 629 / 62 711 / 63 752 | 619 034 / 625 446 / 630 579 | 3 107 625 / 3 127 076 / 3 139 065 (1.00) |
| verification (§3.2 step 4): rows(out) = rows(in) = rows(flat); node rows | pass; 199 182 | pass; 1 991 728 | pass; 19 917 017 |

The skew is mild even with a hub carrying about 0.37 % of all edges (368 k at 10⁸): the largest `out` bucket is 11 % above the median. Build time is not linear (10× the rows took 11× then 20× the time): the 10⁸ nodes step (two `GROUP BY`s and a `FULL JOIN`) dominates, and the process held 17.9 GB.
**Extrapolated, not measured:** at 10⁹ the on-disk size is about 34 GB (the bytes-per-edge ratio) against §2.2's 40 GB estimate. No build time or memory figure is extrapolated for 10⁹: the 10⁸ build already used 70 % of the default `memory_limit`, so a 10⁹ build would spill and its time is unknown.

**Q4 — the pinned jar (`duckdb_jdbc` 1.5.2.1, runtime `v1.5.2`).**
* `json` is **built in** (`duckdb_extensions()`: `json loaded=true installed=true`; nothing to INSTALL) and **works on a sealed connection with autoinstall / autoload off and `enable_external_access=false`**. `json_extract`, `json_extract_string`, `json_group_array`, `CAST(… AS JSON)`, `from_json` + `unnest`, `json_array`, `json_valid` all returned correct results.
* `COPY … (FORMAT parquet, …)` accepted: `PARTITION_BY`, `ROW_GROUP_SIZE`, `COMPRESSION zstd` / `snappy` (files read back as ZSTD / SNAPPY — **the default with no COMPRESSION option was SNAPPY, so `zstd` must be stated**), `COMPRESSION_LEVEL`, `FILE_SIZE_BYTES`, `PER_THREAD_OUTPUT`, `OVERWRITE_OR_IGNORE`, `PARQUET_VERSION V2`, `bloom_filter_false_positive_ratio`; manual per-bucket files (one `COPY` per bucket, named by the caller) read back with a glob — 250 000 of 250 000 rows. **`ROW_GROUP_SIZE_BYTES '8MB'` errored** ("Attempting to execute an unsuccessful or closed pending query result"; not investigated, the design does not use it). A `PARTITION_BY` copy into a non-empty directory errors ("Directory … is not empty! Enable OVERWRITE option"), which the staged `.tmp` discipline of §2.4 avoids.
* `parquet_metadata` shows a `bloom_filter_offset` on some columns of a default-written file, so DuckDB writes bloom filters without being asked; whether they help was **not isolated** (step (d) not run).

**Q5 — staleness fingerprint cost (`baseFingerprint` = file list + sizes + mtimes of the relation's inputs, plus the relation-SQL hash).**

| Part | p50 / p95 |
|---|---|
| SHA-256 of a 700-char relation SQL | 0.06 / 0.37 ms |
| Java listing of N files (relative name, size, mtime) + SHA-256: 1 file | 0.9 / 1.1 ms |
| 64 files | 10.4 / 14.0 ms |
| 1 000 files | 110 / 133 ms |
| 10 000 files | **943 / 1 152 ms** |
| the 64-subdirectory bench dirs (`edges_<n>_b64`, recursive walk) | 15.6 / 21.2 ms |
| DuckDB `glob()` count of 1 / 64 / 1 000 / 10 000 files (names only, no size or mtime; includes opening a fresh instance) | 1.0 / 1.8 / 7.4 / 66.7 ms (p95 1.1 / 2.3 / 9.6 / 81.6) |

(20 samples each, synthetic files on local NTFS, warm.) The SQL hash is free; the file listing costs about 0.1 ms per file with `readAttributes`, so a Dataset of tens of thousands of files would spend seconds per read on it, more than a 43 ms hop, unless it is cached or sampled. The cheaper `glob()` probe sees only names (a file rewritten in place is invisible to it). How the Dataset's input files are enumerated in production (`DatasetRelation` consults the Consignment catalog for `physicalRef` Datasets) was not measured, and how often the fingerprint should be taken is a decision, not a fact.

**Not measured / extrapolated, in one place.** 10⁹ (build time, memory, size beyond the 34 GB ratio); DuckDB cross-version `hash()` / `md5_number_lower`; bloom filters on a non-sorted key (step (d)); concurrent reads (D-S5); parallel per-key statements; a Dataset with many input files through the real `relationSql`; the build under a smaller `memory_limit`; cold disk cache. **Machine noise** is as stated in Method.

### 5.2 Step 3 as built — 2026-10-02 (`IndexBuilder`, `BucketFunction`, `IndexStore` hardening)

* **`BucketFunction`** is the ONE definition of the bucket: `sql(idExpr, N)` = `CAST(md5_number_lower(<expr>) % N AS INTEGER)` (what the builder emits into `COPY ... PARTITION_BY (bucket)`) and `bucketOf(id, N)` = MD5 of the UTF-8 bytes, digest bytes 8..15 little-endian, `Long.remainderUnsigned` (what the engine computes). `bucketsFor(edges)` is the signed `clamp(pow2(ceil(edges / 4e6)), 16, 1024)`. **Proof:** `BucketFunctionTest` loads 4 000+ ids (ASCII, typed keys, empty and blank, control characters, `U+0085`/`U+2028`, case pairs, composed vs decomposed e-acute, emoji, a 100 000-char id, 2 500 seeded random BMP strings) into DuckDB and asserts SQL == Java for every id at N = 1, 2, 16, 17, 64, 1000, 1024. Mutants red: Java reads digest bytes 0..7, SQL uses `md5_number_upper`.
* **`IndexBuilder.build(Request)`** — request = dataset id, `IndexMapping`, the TRUSTED relation SQL (a SELECT the caller obtained from `DatasetProvider.relationSql` AFTER its own view gate; the builder never calls the gate and never sees a Subject), the `IndexStore`, the caller's `baseFingerprint` (the builder lists no files), `Options{buckets override, memoryLimit, threads, CancelToken, progress}`. It runs its own non-sandboxed in-memory DuckDB (`org.duckdb:duckdb_jdbc`, version managed by the parent pom; the enforcer allowlist and `ALLOWED` are unchanged because DuckDB is not an `inspecto-*` module), session `TimeZone` pinned to UTC, the relation registered as a temp view, one `count(*)` pass for rows and `droppedNull`, then per direction the bench's own COPY (`ORDER BY bucket, key, ts`, `ROW_GROUP_SIZE 100000`, `COMPRESSION zstd`), then `nodes` (folded distinct `(neighbour, kind)` links, `first_ts`/`last_ts`). Verification: rows(out) = rows(in) = rows(relation) − droppedNull; every bucket directory is `bucket=<0..N-1>` and holds a parquet file (an absent directory is provably empty because the totals must match); node count > 0 and Σ`out_edges` = rows(out), Σ`in_edges` = rows(in). Then `manifest.json` (`builder: full`, `bucketFn`, per-table rows/files/bytes, `relationSqlHash` = SHA-256 of the SQL text, DuckDB version from the connection) and the atomic publish. `IndexBuilder.verify(versionDir)` re-verifies a published version against its own manifest.
* **Failure and cancel.** Any failure closes the connection, deletes the stage (`IndexStore.discard`) and leaves CURRENT alone. `CancelToken.cancel()` sets a volatile flag (checked between statements) AND calls `Statement.cancel()` on the running statement from the cancelling thread — proven on a 30 M-row COPY cancelled 1.5 s in (stage gone, CURRENT still v000001; the mutant without `Statement.cancel()` is red). `memory_limit`, `threads` and a `temp_directory` under the stage (`.spill`, removed before publish) are honoured; the limit and the zone are validated before they reach SQL. A background heartbeat refreshes the stage mtime every 30 s.
* **The mapping grew** (breaking changes are free): `IndexMapping(src, dst, kind?, time?, timeColZone?, weight?, attrs)`; the zone is part of the mapping hash. `ts` is stored as a NAIVE UTC timestamp: a naive TIMESTAMP/DATE column is read in the explicit zone (UTC when none; the effective zone is recorded in the manifest), a TIMESTAMPTZ column is an instant and REFUSES a zone, any other type is refused. Tested under three JVM default zones and a DST zone (`America/New_York`). `kind`/`ts`/`w` are NULL when unmapped; weight and attributes are stored as `w DOUBLE` and `a0..ak VARCHAR` (positional). `droppedNull` now means "src or dst NULL" only (a NULL time keeps the row).
* **`IndexStore` hardening (the independent verifier's review)** — each with a test and a red mutant: `segment()` rejects `:`, `< > " | ? *`, control characters, trailing dot/space and the Windows device names (CON, PRN, AUX, NUL, COM1–9, LPT1–9, with or without an extension); directory names are **NFC-normalised and case-folded** (upper then lower), so `Ds`/`ds` and composed/decomposed characters are ONE index on Linux and Windows alike (the original id is kept in the manifest); `gc(minAge)` never deletes an in-flight stage — a stage touched within `STAGE_LIVENESS` (5 min) is spared even for `minAge` ZERO, refreshed by `IndexStore.heartbeat`; `gc` also deletes orphan `CURRENT.tmp-*`; `publish()` refuses a LOWER version than CURRENT (a per-directory JVM lock plus a file lock, so concurrent publishes cannot regress it; the refused stage is left for its owner to discard); the CURRENT temp file is `FileChannel.force`d before the atomic move (not observable in a test, so there is no mutant for it).
* **Tests:** 14 `IndexBuilderTest` (13 run + the gated bench), 4 `BucketFunctionTest`, 17 `IndexStoreTest`, 5 `IndexManifestTest`. Golden planted corpus: 17 rows in three parquet files read through `read_parquet(glob)`, 3 NULL-endpoint rows dropped, parallel edges, a self-loop, multi-kind, NULL kind, NULL time, ids differing by case and by normalisation; the node table equals an independent Java oracle, and one hop out AND in equals the flat read through the same relation SQL for 11 ids. Mutants red: NULL-endpoint filter removed, verification disabled (all layers), `Statement.cancel` removed, re-verify ignoring the manifest.
* **Measured** (generated corpus, `-Dinspecto.bench=true -Dinspecto.bench.edges=N`, the §5.1 laptop, noisy, one run each; the relation is a hash expression re-evaluated by each pass, so it is slower than the flat parquet file of §5.1): 10⁶ edges **7.1 s** (out 2.4, in 2.1, nodes 1.5; 16 buckets; 23 MB); 10⁷ edges **80.5 s** (out 25.4, in 25.5, nodes 26.7; 16 buckets; 225 MB). Not run at 10⁸ (§5.1 measured 368 s from a flat file).
* **Deviations / what the design got wrong:** (1) the design's `IndexMapping` had no kind column and a mandatory time column — fixed above; (2) `ts` is a naive UTC TIMESTAMP, not zoned, so a reader never meets the session zone; (3) the manifest comment said `droppedNull` counted "src, dst or time" — corrected to src or dst; (4) the global `ORDER BY` is spill-safe by DuckDB's design but a spill was not proven here (the 256 MB test builds 400 000 rows; whether it spilled was not checked); (5) "per-bucket file presence" cannot tell a bucket that should hold rows from an empty one — the row totals do that job; (6) `COPY` of an empty relation writes no directory, so an empty relation is refused ("no edge with both endpoints") instead of publishing an empty index.

### 5.3 Step 4 as built - 2026-10-02 (`IndexBuildService`, the index routes, settings, capability, audit)

* **`IndexBuildService`** (`inspecto-la-storage`, host-free, modelled on `GraphRunService`): bounded daemon-thread executor, run table `QUEUED -> RUNNING -> COMPLETED | CANCELLED | FAILED`, progress from the builder's callback, cancel through the builder's `CancelToken`, finished-run retention (TTL + `maxRuns`), a once-per-run terminal hook, owner = starter id, `close()` cancels running builds. One live build per (dataset, mapping): the key is the store's on-disk directory (case-folded, normalised), so `Calls` and `calls` are one index and the second start is `DUPLICATE` (409). `FAILED` carries the exception class name only. A COMPLETED build wins a cancel that arrived in the same instant (a version exists). After a publish it calls `IndexStore.gc(1 h)` best effort (the design's "never younger than a run TTL"). The relation comes from a caller-supplied `RelationSource` (dataset id to trusted SQL + fingerprint), called once on the submitting thread; the service never sees a Subject. Disk budget and `keepVersions` ride on the `Request` (read per request), so a changed setting applies to the next build.
* **Routes (`IndexRoutes`, `inspecto-la-api`)**: `POST /inv/index/builds` (always 202 + Location), `GET /inv/index`, `GET /inv/index/builds/{id}`, `POST /inv/index/builds/{id}/cancel`. Every route stands behind the base-Dataset gate of Decision 2: start runs `InvRoutes.relationFor` (unknown 404, not viewable the SAME 404) and then checks the mapped columns against the relation (422); reads and cancels re-check that the Dataset still exists and is viewable and answer the 404 of an unknown build otherwise (so does a non-starter); `GET /inv/index` walks only viewable Datasets and skips a manifest whose recorded dataset differs from the one asked about (the directory is case-folded). Root = `<Space write root>/la-index` (503 without a write root). One service per write root through `IndexBuildServices` (idle close, closed with `ApiContext.onClose`).
* **Staleness** (`GET /inv/index`): `stale` + `reason` from the manifest's `relationSqlHash` vs the Dataset's relation SQL now, `bucketFn` vs `BucketFunction.NAME`, and `duckdb.version` vs the server's. A stale index is still listed.
* **Capability** `canBuildLinkIndex` (`Roles.CAN_BUILD_LINK_INDEX`, literal in `withCapability`), seeded to exactly the roles that hold `canRunLinkGraphAnalysis`; cancel is a manifest `self-service` exemption (starter or administrator). Reads need none.
* **Settings** (`link-analysis.toon`, `GET/PUT /settings/link-analysis`): `index {enabled (default FALSE), max_disk_bytes (0/unset = no limit), keep_versions (default 2), threads (default 1), queue (default 4)}`; 422 outside 0..10^15 / 1..100 / 1..64 / 1..1000. `enabled` is echoed by `GET /inv/index` and read by nothing else: no read path uses an index until step 5.
* **Audit**: `LINK_INDEX_BUILD_STARTED | _COMPLETED | _CANCELLED | _FAILED` with `dataset`, `mappingHash`, `runId`, and (terminal) `elapsedMs`, `version`/`rows`/`edges`/`buckets` or `failure` (class only); never column names.
* **Deviations / what the design got wrong:** (1) **the base fingerprint is NOT grounded in files** - `DatasetProvider` cannot enumerate a relation's inputs and a `glob()` over paths scraped from SQL text is neither cheap nor safe, so the fingerprint is `relation-sql-only:<sha256>`: a Dataset whose DEFINITION changed reads stale, one that merely gained or lost a file does not (design 2.4's file/size/mtime list needs a port method; not built). (2) The disk estimate is `rows x 34 x 2`, with NO separate node term: the measured 34 bytes per edge (5.1) is already out + in + nodes, so the x2 is the safety factor and adding nodes would count them twice. (3) The estimate needs one `count(*)` pass, run synchronously inside submit ONLY when `max_disk_bytes` is positive (the refusal is then a 422 before any run exists); 0 costs nothing; the duplicate check runs FIRST, the count is bounded by a 10 s statement timeout (a timeout refuses with 422 naming the way out, never skipping the budget) and capped at 2 concurrent estimates per service (extra callers get 503 STORE_BUSY), and a 409 duplicate names the live build id only to its starter (another viewer is told only that a build is running). (4) Step 3's builder gained `countRows`, `duckdbVersion` and `relationSqlHash`; `IndexStore` gained `mappingHashes`. (5) la-api now depends on la-storage (`ALLOWED`, the enforcer list, the module-deps test extended: la-storage -> la-api and la-core -> la-storage stay red).

### 5.3a Step 4 follow-up - the staleness fingerprint is grounded in input files (2026-10-02)

Retracts deviation (1) of 5.3; signed decisions are untouched (6a: serve stale flagged, refuse only when removed rows could be exposed).

* **Port.** `DatasetProvider.inputFingerprint(dataset, dataRoot, writeRoot)` (default `null` = unknown, so existing implementers and doubles compile and la-core stays host-free; deviation from the brief's `(datasetId)` signature: the data root is needed, as for `relationSql`) returns an `InputFingerprint` (la-core): `files:<sha256>` over the files sorted by path, one line `path TAB size TAB mtimeMillis`; or the sentinels `no-files:<sha256 of relation SQL>` (a view-backed Dataset, nothing to list) and `too-many-files:100000` (more than `InputFingerprint.MAX_FILES`, not listed). Paths are relative to the data root (a `shared/` ref: prefixed with the ref, relative to the snapshot), so a moved Space root does not read stale. A store directory that does not exist yet is an empty KNOWN list (files arriving later are additions), not unknown.
* **Bridge.** `EngineDatasetProvider` -> `DatasetRead.inputFiles` -> `DatasetRelation.inputFiles`, which shares `storeReadRoot` with `storeRelationSql` and walks through the new `ConsignmentSelector.readableFiles` (the same walk, hidden-segment rule and Consignment-catalog subtraction of superseded files `select(root, ext)` uses; unlike `select` it answers with no registry, subtracting nothing, and stops at the cap). Nothing is scraped from SQL text. A virtual Dataset (`sql` + `sourceName`) lists its source store.
* **Cost** (directory walk + attribute read + sha, Windows dev laptop, warm cache, best of 5): 1 file 3 ms, 100 files 32 ms, 1,000 files 182 ms, 10,000 files 1.9 s (about 0.19 ms per file, roughly twice the step-1 spike because the walk and the per-file attribute read are separate). At the 100,000 cap that is about 19 s on this machine: the cap bounds memory and the walk, not latency; `GET /inv/index` pays one listing per indexed Dataset per call. Lower `MAX_FILES` if that proves too slow.
* **Manifest.** New optional `inputFiles` (path, size, mtime), recorded when the fingerprint is real and at most `IndexManifest.MAX_INPUT_FILES` (5,000) files, else omitted (only the hash remains). `formatVersion` stays 1: the field is additive, an older reader ignores it, and a manifest without it (every earlier one, including those with `relation-sql-only:` fingerprints) reads as not recorded and as an unknown fingerprint.
* **`IndexStaleness.compute(manifest, relationSqlHash, inputFingerprint, bucketFn, duckdbVersion)`** (la-api; the one method `GET /inv/index` and later consumers call) returns stale, reason codes in precedence order `input_files_changed`, `relation_sql_changed`, `relation_unresolvable`, `bucket_function_changed`, `duckdb_version_changed`, `removedInput`, and `fingerprintKnown`. The input comparison runs only when BOTH fingerprints are real; otherwise `fingerprint: unknown`, no stale claim and no currency claim. `removedInput` is true when the input changed and a recorded file is gone or differs in size/mtime; a pure addition leaves it false; a change with no recorded list cannot be classified and is true (conservative). A relation-SQL change alone does not set it. `GET /inv/index` keeps the prose `reason` and adds `reasons`, `removedInput`, `fingerprint` and `inputFiles`.
* **Note for readers of the SQL hash.** With a Consignment registry the physicalRef SQL pins the file list, so an added file used to change `relationSqlHash` too and read as a definition change (which the traversal gate must refuse, so an addition could never be served stale). Fixed 2026-10-02: `IndexBuilder.relationSqlHash` blanks the pinned list directly inside `read_parquet(` / `read_csv(` before hashing, so the hash is the Dataset's DEFINITION and the files are tracked only by the input fingerprint; a literal list elsewhere in the SQL stays part of the definition. Manifests written before that fix hash the raw SQL and read once as `relation_sql_changed`.
* **Hot path (5.4).** The traversal gate takes this fingerprint through `InputFingerprintCache` (la-api): a 30 s TTL per (write root, Dataset, mapping), at most 256 entries, dropped when a build for that Dataset and mapping completes; a file changed inside the TTL is noticed one TTL later.

### 5.4 Step 5 as built - 2026-10-02 (`recursive-paths` from the index, within the cap)

* **Classes.** `IndexReader` (`inspecto-la-storage`): ONE `SqlSandbox` per request (the route's `traversalPolicy()`: memory cap, `traversal_threads`, 5 s statement timeout on every statement), views `e_out` / `e_in` over the pinned version's `out` / `in` Parquet (`hive_partitioning`), then `SqlSandbox.sealAllowing(conn, [versionDir])`: `enable_external_access=false`, `lock_configuration=true`, `allowed_directories` = that one directory. Each key is one equality statement `WHERE bucket = <literal> AND src|dst = ?` (bucket from `BucketFunction.bucketOf`, key BOUND, per-key `LIMIT` = the yield), the keys of a level `UNION ALL`ed into one round trip; `UNDIRECTED` adds the `in` copy per key. `IndexedTraversal` (same module) is the walk: `Params` / `Result` / `FrontierOverCap`, constants `MAX_DEPTH = 2` and `FRONTIER_CAP = 20`. `IndexedRecursivePaths` (`inspecto-la-api`) is the selection: it decides servable-or-not, renders the filter, runs the walk and names the reason. `InvRoutes.recursivePaths` calls it right after `relationFor` (the view gate) and before the flat planner; the flat code is untouched apart from `source` and the audit attrs.
* **What the index can answer EXACTLY.** The index is chosen among the Dataset's published indexes by source and target column (case-insensitive); kind, zone and attribute columns are not part of a traversal request so any value matches. `weightCol` and the temporal column must be the index's own. Not servable, so the flat read answers and says why: a temporal constraint over a time column indexed in a zone other than UTC (`time_zone_not_servable`: the flat path compares the raw wall-clock values, the index stores UTC instants, and durations differ across a DST change - a UTC mapping is exact); a `filter` leaf on the weight column (`filter_not_indexed`: stored as DOUBLE, the flat path sees the original type) or on any column the mapping does not hold; any request column the index lacks (`column_not_indexed`). The filter tree is rewritten leaf by leaf onto `src, dst, kind, ts, a0..` and rendered by the same `ConditionSql`.
* **Reasons (the closed enum on `source.reason`).** `index_disabled`, `no_index`, `mapping_not_indexed`, `column_not_indexed`, `time_zone_not_servable`, `filter_not_indexed`, `index_stale_refused`, `depth_over_index_cap`, `frontier_over_index_cap`, `index_read_failed`. Served: `source = {kind: 'index', version, stale, staleReason?, fingerprint: 'known'|'unknown'}`; refused: `{kind: 'dataset', reason: 'index_stale_refused', details}`. The flat path ALWAYS adds `source` (including `index_disabled`), so the body is today's body plus that one key.
* **Staleness (decision 6a, ONE definition with `GET /inv/index`).** The gate calls `IndexStaleness.compute` with the relation-SQL hash, the current input fingerprint and the server's bucket function and DuckDB version, then: `removedInput` (a recorded file gone, resized or re-stamped, or no recorded list to classify with) is REFUSED (`index_stale_refused`, `source.details` = the reasons' text; removed rows could be exposed); a changed relation SQL, an unresolvable relation, a bucket function that is not `md5_number_lower` (Java would read the wrong bucket) and a manifest with delta files are REFUSED too; files only ADDED is SERVED with `stale: true` and `staleReason: 'input_files_changed: N files added since the build'` (the index misses the new rows; the flat Dataset returns more paths, and the flag says so); a `duckdb.version` difference is served with `stale: true`; an UNKNOWN fingerprint (no enumerable files, `too-many-files:`, a provider that cannot say, a pre-fingerprint manifest) is served with `source.fingerprint: 'unknown'` and nothing claimed. The fingerprint is taken through `IndexRoutes.currentInput` (the helper the build and `GET /inv/index` use). The version is pinned by reading `CURRENT` once, before anything else.
* **Fences keep their names.** `maxDepth` (index ceiling 2), per-level `maxEdgeYield` (a level reaching it sets `edgeYieldCapped`; `truncated` follows), the 5 s statement timeout (a timed-out or otherwise failed index read falls back with `index_read_failed`, so the flat path may then spend its own 5 s), cycle refusal (a path never revisits a node), four-eyes (`refuseIfSensitive` with the same arguments, called after the request is known servable and before any read). When a key's own edges reach the yield the flag is set even if later filters leave fewer paths: the conservative direction, never a silent cap. When the yield fence fires the rows differ from the flat path's (its per-level `LIMIT` has no `ORDER BY`); only the flag is comparable and the test asserts only that.
* **One response never mixes the two paths.** A frontier of more than 20 distinct keys at any level throws inside the walk; the partial result is dropped and the whole answer comes from the flat Dataset with `frontier_over_index_cap`. Depth 1 with a 25-key hub is ONE lookup and is served; the cap is on keys looked up, measured on the actual frontier.
* **Audit.** `link.traversed` gains `source` (`index` / `dataset`), and `indexVersion` + `indexStale` or `sourceReason`.
* **Proof.** `ControlApiInvIndexedTraversalTest` (12, real HTTP, armed Authenticator): equivalence of 17 requests on the planted corpus (parallel edges, self-loop, cycles, multi-kind, NULL kind, NULL time, weights, NULL-endpoint rows, an `America/New_York` index across the 2026-03-08 DST day, directed / undirected, target, weight, monotonic and duration constraints, kind / attribute / NULL filters, a 20-key hub) through the index and through the flat Dataset: same path set, `truncated`, `edgeYieldCapped` and `fences`; yield fence on both; each negative path with a servable probe (disabled, no index, other mapping, unindexed weight / time / filter field, non-UTC temporal, depth 3, frontier 25, changed relation SQL, shared-away 404, four-eyes 403, audit attrs). `IndexReaderTest` (5): per-key LIMIT and filter, the `in` copy, a wrong bucket misses the row, `read_parquet` of another existing file through the reader's connection is refused and `SET enable_external_access` is locked, a foreign bucket function or a delta manifest is refused. The existing `ControlApiInvTraversalTest` (15), `InvRoutesTraversalPolicyTest`, the projection / investigation suites and `ControlApiIndexTest` pass unchanged. Mutants, each red (4 / 1 / 1 failures): the Java bucket shifted by one, the mid-walk fallback replaced by serving the partial walk, the view gate removed (a stranger got 200). Contract: `openapi-v1.json` documents the route (it was a generated skeleton) with the `source` object; the path set is still 395.
* **Measured (the 5.1 laptop, noisy, p50 of 20 warm requests through the real route, start nodes of out-degree about 2 / 8 / 20; `InvIndexedTraversalBench`, `-Dinspecto.bench.dir=`).** One hop, index vs flat: 10^6 edges 209 / 466 / 224 ms vs 293 / 373 / 329 ms; 10^7 edges 118 / 73 / 96 ms vs 536 / 279 / 261 ms. Depth 2: 10^6 427 / 404 / 1 203 ms vs 418 / 346 / 372 ms; 10^7 199 / 195 / 511 ms vs 639 / 518 / 771 ms. Index builds 7.1 s (10^6) and 23.8 s (10^7) through the route. **Reading:** at 10^6 the index is NOT faster (every request pays a fresh sealed connection, the temp-DB open plus two view bindings that list the version's files, roughly 100-200 ms; the 20-key depth-2 case issues 21 equality statements and loses 3x); at 10^7 it wins 2-5x for one hop and 1.5-3x at depth 2. 10^8, where the spike measured one hop at 43 ms against 1 031 ms flat, was not re-run through the route.
* **Deviations / what the design got wrong.** (1) The design said the request's columns "define the mapping and its hash"; a traversal request carries neither kind, zone nor attribute columns, so the index is matched by source + target and coverage, not by `IndexMapping.hash()`. (2) The response has no edge ids or kinds (the flat body never had them), so equivalence compares nodes, hops and weight. (3) A non-UTC time zone makes temporal constraints non-servable; the design had not seen that the flat path compares raw wall-clock values. (4) The reader opens through `SqlSandbox` (temp-file DB, the policy's caps and timeout) rather than a bare in-memory connection, so it shares the route's memory and thread caps. (5) `IndexReader` is not pooled: the fixed per-request open cost is why 10^6 does not win; a per-Space pooled reader is a possible later change, not designed. (6) The frontier estimate "from the node table before the walk" is not used: the cap is enforced on the measured frontier, which is exact and costs nothing extra.

### 5.5 Step 6 as built - 2026-10-03 (`neighbors` and Investigation `expand` from the index)

* **Shared gate.** `IndexedRead` (`inspecto-la-api`) now holds what step 5 had inside `IndexedRecursivePaths`: the setting, the choice among published indexes (source + target column, then a route-supplied fit test), the staleness gate (one definition with `GET /inv/index`), the closed `Reason` enum (one new value, `rung_not_indexable`, with the cause in `details`) and `Outcome<R>` (`source()` for the response, `readIndex()` for a sealed read). `IndexedRecursivePaths`, `IndexedNeighbors` and `IndexedExpand` add only their fit test and read.
* **Storage.** `IndexReader.fold(key, side, extraCols, kinds, filterSql)`: one equality statement on the key's bucket, `GROUP BY src, dst, extras`. It folds EVERY row of the key (a count is exact only over all of them), so its cost is the key's own degree, never a per-key `LIMIT`; the bucket is computed in Java, the key and the kind list are bound, extra columns are validated (`kind|a<N>`).
* **`neighbors`.** After `relationFor` and `refuseIfSensitive`. `fold(value, OUT)` and `fold(value, IN)` are merged as a SET (a self-loop is in both copies, once in the answer), sorted `cnt DESC, source, target, kind/attrs NULL first` on UTF-8 bytes, cut at `limit`; `truncated` = more rows than `limit`. Servable only when `linkKindCol`, each `attrCols` entry and each `filter` field are indexed columns. The body gains `source`; the audit event `link.expanded` gains `source`, `indexVersion`, `indexStale` or `sourceReason`.
* **Investigation `expand`.** `InvestigationRoutes.read` tries `IndexedExpand` first; null/failed/declined falls through to the untouched CTE. SIMPLE rung only: no `window`, `minDistinctDays`, `candidateDegreeMin/Max`, `merged`; at most 20 distinct frontier entities (`FRONTIER_CAP`); a non-empty `linkKinds` with a `linkKindCol`; `maxFanOut >= 1`. Why exact: a pair touching a frontier entity lies wholly in that entity's `out` (as source) or `in` (as target) bucket, so the per-frontier folds equal the CTE's `pairs`; the rest (exclusion of either endpoint, direction filter incl. the `reciprocal` reverse-pair test, `minEvents`, per-anchor rank by `cnt DESC, s, t, k NULLS FIRST`, the number cut by `maxFanOut`, final order and the budget cut) is reproduced in Java. A frontier is looked up with `2 x |frontier|` statements.
* **Sealing.** `read.index = {version, stale, fingerprint:'known'|'unknown'}` is set only when the index answered, and is NOT in `fingerprint` (which stays `sha256(canonical(rows))`), so a read sealed from the index and one from the Dataset over the same data carry the same fingerprint. `replay` (no `reread`) never reads data, so it is byte-identical before and after a newer index version exists; `reread` adds `indexVersionSealed` / `indexVersionNow` to a drift row when either is present and `diverged` stays fingerprint-only.
* **Pool.** `IndexReader.evictAll()` is wired into `IndexRoutes.register` (API close) and into the build-completed callback.
* **Proof.** `ControlApiInvIndexedExpansionTest` (9, real HTTP, armed Authenticator): neighbours parity on a planted corpus (parallel edges, self-loop, NULL kind, NULL-endpoint rows, kind/attr/filter combinations) and a 25-child hub cut at `limit` in order; every fallback with its reason and a served twin (disabled, no index, unindexed attr/filter/kind, other mapping, stale-refused, 404 for a stranger, 403 four-eyes); expand fingerprint parity over directions, `linkKinds`, `minEvents`, `maxFanOut`, `budget`, exclusion and a 20-entity frontier; non-simple rungs (window, `minDistinctDays`, degree bounds, 21 entities) keep the CTE; replay/reread index-move reporting; audit. Mutation-tested: dropping the self-loop dedupe, the exclusion, the window refusal, the reciprocal reverse test, the frontier cap and the stale refusal each turn the intended test red. Plus `IndexReaderTest` (fold) and `IndexedExpandTest`.
* **Deliberate limits / gotchas.** (1) `neighbors` and `expand` fold all rows of the value, so a node of millions of rows is bounded only by the 5 s statement timeout (then `index_read_failed` and the flat path answers). (2) The Investigation response does not name the fallback reason (only the absence of `read.index`); `rung_not_indexable` and the others are visible on `neighbors`. (3) Ties among identical `(source, target)` with different kind/attrs are ordered deterministically on the index and arbitrarily by the flat SQL, so `neighbors` is compared as a set. (4) `merged` rungs and windowed rungs stay on the flat CTE (a window needs the time zone contract on the index; deferred with step 7).

## 6. Risks

| Risk | Mitigation |
|---|---|
| The index is a second copy of customer data (§7.4 ⚠) | Per-Dataset, gated on the base Dataset at read (§2.3), rebuilt when the base shrinks (§2.4), `max_disk_bytes`, deletable by Dataset |
| §1.2 (a)–(e) unmeasured: the benefit through the sealed per-call path and the Java-driven walk is unproven | **Measured (§5.1): one hop PASS, deep walk FAIL — resolved by narrowing the scope (Path A); deep and wide multi-hop stays on the flat path** |
| Bucket-function stability across a DuckDB bump | The bucket is `md5_number_lower(x) % N` (Decision 3 amendment): Java reproduces it (1 004 / 1 004), so the engine needs no round trip and a function change can only be deliberate; manifest records `bucketFn` + `duckdb.version`; mismatch ⇒ stale ⇒ rebuild; never a wrong answer. MD5 byte order inside DuckDB across versions is not proven |
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
* **Deep multi-hop and wide frontiers over the index** (Path A): depth > 2, a frontier over 20 keys per level, `allPaths`, `shortestPath` / `descendants` over a large frontier, the bidirectional BFS, the Java-driven deep walk, and `IndexSubgraph`; they stay on the flat recursive-CTE path and the in-memory engine.
* Promoting `read.index` to a replay pin (G-E2), or filling `datasetVersion`.

## 8. Decisions owed (operator)

⛔ None may be answered by an implementer in passing. **Decisions 1–8 signed 2026-10-02** (the operator said "go with your recommendations"); Decision 3 carries one assistant amendment, marked on its Answer line.

**Decision 1 — Index granularity and home.** (a) One index per (Dataset, edge mapping) under the Space write root; (b) one shared multi-Dataset index; (c) one per Investigation. Sub-question: write root or data root for the files (not grounded which fits retention and backup better).
*Recommendation:* (a), under the write root beside `audit/`; (b) and (c) are different products (cross-Dataset hops, D-7 Drafts) and (b) multiplies the R3 gates.
**Answer:** (a), under the write root beside `audit/`; (b) and (c) are different products (cross-Dataset hops, D-7 Drafts) and (b) multiplies the R3 gates. — operator 2026-10-02 (accepted the recommendation)

**Decision 2 — Row scope (R3).** (a) Gate at read on the base Dataset, the index carries no scope, manifest `relationSqlHash` invalidates on any Dataset change; (b) shared index with a row-scope predicate per read; (c) one index per scope fingerprint.
*Recommendation:* (a) — R3 is Dataset-level today (§1.1 fact 4) and a revoked share takes effect on the next read; record the invariant "valid only while a Dataset has no per-Subject row filter" and a test that fails if `DatasetRelation` gains one.
**Answer:** (a) — R3 is Dataset-level today (§1.1 fact 4) and a revoked share takes effect on the next read; record the invariant "valid only while a Dataset has no per-Subject row filter" and a test that fails if `DatasetRelation` gains one. — operator 2026-10-02 (accepted the recommendation)

**Decision 3 — Partition key, bucket count, sort, bucket function.** (a) entity-hash of source (`out`) and target (`in`), `N = clamp(pow2(edges / 4·10⁶), 16, 1 024)` fixed per version, sort `(entity, ts)`, row group 100 000, DuckDB `hash()` with function and engine version in the manifest; (b) a fixed `N = 64`; (c) time-first partitions with entity bloom filters (the other D10 option).
*Recommendation:* (a) — D10 is signed as entity-hash and D-S1 measured 64 buckets sorted by `(entity, ts)`; the count scales with rows for the D21 target; re-confirm in step 1.
*Amendment (assistant, from step 1 data, 2026-10-02):* the bucket function is `md5_number_lower(entity) % N` instead of DuckDB `hash()` — reproducible in Java (1 004 / 1 004 ids), stable across DuckDB versions because MD5 is a fixed algorithm (the function's byte order across versions is not proven), build about 16 % slower, same lookup speed — so the engine computes the bucket without a round trip and a DuckDB upgrade cannot make an index silently stale. `N = clamp(pow2(edges / 4·10⁶), 16, 1 024)` is unchanged (measured N at 10⁸: 32 buckets).
**Answer:** Accepted with the amendment above — operator 2026-10-02 (accepted the recommendation; the md5 amendment is the assistant's, from step 1 data - veto by editing this line)

**Decision 4 — Builder home and trigger.** (a) A Job Type in the bridge only (needs `JobService` progress and cancel, which `inspecto-engine` lacks); (b) an LA-owned `IndexBuildService` in `inspecto-la-storage` with routes, the build running on its own DuckDB connection; (c) (b) now, a bridge `JobTypeProvider` adapter later for scheduling and the ledger.
*Recommendation:* (c) — the same reasoning as D-4 Decision 1(c); `la-*` cannot import the engine, and the builder needs only `dataset()` + `relationSql()` from the existing port, so no `IndexBuildPort` is needed.
**Answer:** (c) — the same reasoning as D-4 Decision 1(c); `la-*` cannot import the engine, and the builder needs only `dataset()` + `relationSql()` from the existing port, so no `IndexBuildPort` is needed. — operator 2026-10-02 (accepted the recommendation)

**Decision 5 — Full versus incremental, and who triggers a build.** (a) Full rebuild only; (b) full first (steps 3–7), incremental append with compaction after (step 8), build on explicit request only; (c) auto-build when a base Dataset changes.
*Recommendation:* (b) — honours signed D11 (incremental by partition) without making it the foundation; staleness is reported, never silently acted on, until an operator asks for auto-refresh.
**Answer:** (b) — honours signed D11 (incremental by partition) without making it the foundation; staleness is reported, never silently acted on, until an operator asks for auto-refresh. — operator 2026-10-02 (accepted the recommendation)

**Decision 6 — Staleness and pinning.** (a) Serve a stale index flagged `stale` with the reason, pin the version on a Graph Run at submit, record `read.index` in the sealed read of an expand, refuse only when the Dataset changed in a way that could expose removed rows; (b) refuse any stale index (fall back to flat); (c) never pin, read CURRENT silently.
*Recommendation:* (a) — replay already cannot move (sealed reads, fact 5); this gives the first honest version-addressable provenance without a replay pin and without breaking analysts when a Dataset gets a new file. `datasetVersion` stays `null`.
**Answer:** (a) — replay already cannot move (sealed reads, fact 5); this gives the first honest version-addressable provenance without a replay pin and without breaking analysts when a Dataset gets a new file. `datasetVersion` stays `null`. — operator 2026-10-02 (accepted the recommendation)

**Decision 7 — Module name and boundaries.** (a) One new `inspecto-la-storage` (manifest, builder, build service, `SqlGraphEngine`, `RoutingGraphEngine`, `IndexSubgraph`) depending on `la-core`, with `la-api` depending on it and `la-core` never; (b) two modules (`la-storage` for the index, `la-sql-engine` for the engine); (c) the engine inside `la-core`.
*Recommendation:* (a) — one cohesive unit, and (c) would drag DuckDB JDBC and file I/O into the model module `la-graph`/`la-core` keep host-free. `ALLOWED['inspecto-la-storage']` = the `la-core` closure plus `inspecto-la-core`, mirrored in the pom enforcer, plus the `bundle-modules.mjs` entry (`from: 'professional'`).
**Answer:** (a) — one cohesive unit, and (c) would drag DuckDB JDBC and file I/O into the model module `la-graph`/`la-core` keep host-free. `ALLOWED['inspecto-la-storage']` = the `la-core` closure plus `inspecto-la-core`, mirrored in the pom enforcer, plus the `bundle-modules.mjs` entry (`from: 'professional'`). — operator 2026-10-02 (accepted the recommendation)

**Decision 8 — Selection and fallback.** (a) Automatic when a current index exists and the Space setting `index.enabled` is on (default **off**), always echoing `source`; Graph Run needs an explicit `input: "index"`; (b) explicit per request everywhere; (c) automatic with no setting.
*Recommendation:* (a) — shipping default off means nothing changes until an operator opts in, the flat read stays the default and the fallback with a stated reason, and a Graph Run keeps D-4 Decision 2's "an explicit action above the cap".
**Answer:** (a) — shipping default off means nothing changes until an operator opts in, the flat read stays the default and the fallback with a stated reason, and a Graph Run keeps D-4 Decision 2's "an explicit action above the cap". — operator 2026-10-02 (accepted the recommendation)

## 9. References

* [`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.4 (data and graph layer), §7.8 (phases), §7.9 (D9–D11, D21), §7.10.1 (D-S1, D-S3 results).
* [`la-separation-d1-design.md`](la-separation-d1-design.md) — module and dependency rule; [`la-separation-d4-design.md`](../archived-documents/plans-archive/la-separation-d4-design.md) — the engine SPI and budget semantics this builds on.
* [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) §*Graph Run*.
* Code: `inspecto-la-core/src/main/java/com/gamma/la/core/` (`DatasetProvider`, `DatasetProviders`, `GraphEngine`, `GraphInput`, `GraphBudget`, `GraphRunService`, `InvestigationEvaluator`), `inspecto-la-api/src/main/java/com/gamma/la/api/` (`InvRoutes`, `InvestigationRoutes`, `WorkingSetRoutes`, `GraphRunRoutes`), `inspecto-geo-link/src/main/java/com/gamma/geolink/EngineDatasetProvider.java`, `inspecto-geo-link/src/test/java/com/gamma/control/InvTraversalBench.java`, `inspecto-engine/src/main/java/com/gamma/query/` (`DatasetRelation`, `QueryExecutor`), `inspecto-sql/src/main/java/com/gamma/sql/SqlSandbox.java`, `inspecto-engine/src/main/java/com/gamma/job/` (`JobTypeProvider`, `MaterializeTask`), `tools/check-module-deps.mjs`, `tools/bundle-modules.mjs`.
