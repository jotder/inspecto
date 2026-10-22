# DuckDB 2.0.x plan — adoption, evidence, the looping-query candidates and the build order

**Status: PLAN, 2026-10-05 — decided, not built** (merged 2026-10-22 with the loop-query research, which is archived).
**2026-10-22: DuckDB `2.0.0.0` GA is on Maven Central (published 2026-10-21) and was trialled — §2a.** The staging
alpha below is superseded; the trial pins the GA coordinates with no extra repository.
The operator decided on 2026-10-05: *go with DuckDB 2.0.x from now on* (the alpha now, GA expected in a couple of
weeks), for the performance and capability gains. This file is the one DuckDB 2.0.x plan: the decision, what was
measured, what is already built on unmerged branches, what is blocked, the order in which to build, and (§7) the
still-open looping-query candidates. The full loop-query research (the 13-site inventory, every experiment table)
stays readable in [`duckdb-2-loop-queries-plan.md`](../archived-documents/plans-archive/duckdb-2-loop-queries-plan.md).

## 1. Decision and constraints

- **Direction:** the project moves to DuckDB `2.0.x`. The staging alpha (`org.duckdb:duckdb_jdbc:2.0.0-alpha43385-881`,
  repository `https://duckdb-staging.duckdb.org/duckdb/duckdb-java/maven/`, repository id `duckdb`) is approved for
  evaluation; the pin that ships is the GA release from Maven Central.
- **Master stays on 1.5.6.0 until the gates below are met.** The upgrade is built on a branch (§3) and is deliberately
  not merged: the alpha is slower on engine ingest (§2) and four DuckDB-behaviour tests are red on it.
- **Breaking changes are free** (nothing after 3.x is in production): no compatibility shim for the 1.x lambda arrow, the
  1.x `json_serialize_sql` shape, or anything else — change the call sites.
- **Operator calls already given (2026-10-05):** (1) the new `skip_null` / `skip_empty` AST behaviour and the
  node-length offsets (`SqlSandboxTest` T3 / T4) are **not** adopted — re-assess before any change; (2) the `SqlGuard`
  path-parameter review is delegated to the assistant (§4); (3) the SPA SQL reader is rewritten for the 2.0 shape;
  (4) use 2.0's capabilities for the looping queries in Link Analysis and storage.

## 2. What was measured (serial, interleaved, same machine)

Harness: `tools/bench-duckdb.ps1` (merged to master; see `okf/product/layer/platform/tests-performance.md`). Four sides run
strictly one after another — A1 (1.5.6), B1 (2.0), A2 (1.5.6), B2 (2.0), 60 s apart, `-Warmup 1 -Reps 3`. A difference is
**real** only if it keeps its sign in both A-vs-B pairs and exceeds both same-version noise runs. Idle CPU before each side
was 34 / 24 / 24 / 20 % (the operator's IDE and language servers idle at ~25 % and could not be removed).

| Workload | Result on 2.0 alpha | Verdict |
|---|---|---|
| Engine ingest — union | +95 % / +106 % time (throughput roughly halved), noise ≤ 4 % | **real regression** |
| Engine ingest — generation | +79 % / +89 % time, noise ≤ 12 % | **real regression** |
| Link Analysis lookups (10^6, 10^7 edges) | `la1m` one-hop p50 +10..+23 %; `la10m` hub p50 +20..+36 %, one-hop p50 +12..+26 %, depth-2 walk p95 +19..+21 % | **probable, modest**; the other rows are at or below the 1.5.6 drift (+8..+18 % between its own two runs) |
| CSV read → transform → parquet write | write −51 % / −62 % time, total rows/s +38 % / +72 % | **real gain** |
| Scan (5M rows), concurrent readers, index build (n=1) | sign flips or inside noise | not established |
| Peak memory | +37..+82 MB on scan / readers | minor |

**Not yet explained:** *why* engine ingest regressed while plain-SQL CSV→parquet improved. The first lane to run (§5, item 1)
finds the regressing statement. Do not pin 2.0 for production before it is understood or reported upstream.

### The dedup-ledger finding (independent of the version)

A single-transaction `INSERT … ON CONFLICT DO NOTHING` into a table with a primary key, unique rows (so it never conflicts):

| Rows | 1.5.6.0 | 2.0 alpha |
|---|---|---|
| 1 k | 3.4 s (~330 rows/s) | 3.0 s |
| 5 k | 41 s | 15.8 s |
| 10 k | > 120 s | 38.7 s |
| 20 k | > 120 s | 67.8 s |

Super-linear on both, much worse on 1.5.6. Row-wise `executeUpdate` in one transaction is as slow, so `executeBatch` is not
the cause; a no-primary-key variant cannot be tested (`ON CONFLICT` needs a constraint). The production
`DbDedupLedger.claim()` (`inspecto-engine/.../consignment/DbDedupLedger.java:137-150`) uses the same pattern
(`PRIMARY KEY (pipeline, key_hash, window_start)`, one `executeUpdate` per row). **Not yet known:** how many keys a real
ingest claims per call. If that is large, the ledger is slow on every version and needs a bulk path.

## 2a. GA trial — `2.0.0.0`, 2026-10-22

**Branch:** `lane/duckdb-2-ga` at `35e8def16` (local only, worktree `C:\sandbox\duckdb2-ga`, from `origin/master`
`ae2617828`). It pins `duckdb_jdbc 2.0.0.0` (`pom.xml`, `tools/dependencies.lock`, the step / nodetype templates) and
moves every DuckDB lambda to the 2.0 form: `x ->` → `lambda x:` and `(x, i) ->` → `lambda x, i:` in `RowShaper` explode,
seven Space Template jobs / views (business-assurance forecast, telco-bypass, telco-fraud) and three tests;
`IndexBuilderTest` expects a `v2.` manifest. The ABI derivation needs no change for a plain four-part version (extensions
resolve to `v2.0.0`). Nothing of it is on master.

### Tests (full reactor, `-Pedition-advanced`, offline)

2.0 refuses the single arrow outright (`Binder Error: Deprecated lambda arrow (->) detected`; `SET
lambda_syntax='ENABLE_SINGLE_ARROW'` reverts it). With the arrows migrated, **15 tests stay red** — every one a 2.0
behaviour change, none a lambda:

| Area | Red test | What changed on 2.0 |
|---|---|---|
| 🔴 **Masking (security)** | `ControlApiBiQueryMaskingTest.aNumericDerivationOfANumericKeyIsMaskedAndACountIsNot`, `ControlApiDbBrowserMaskingTest.adHocSqlMayOnlySelectAMaskedKey` | the masked-key checks no longer catch a numeric derivation of a key or a `count(CASE WHEN key = …)` — the BI and DB-browser masking contracts are broken. **Cause not traced**; the likeliest is the new `json_serialize_sql` shape (row below) |
| 🔴 **`SqlGuard` (security)** | `SqlGuardFileFunctionContractTest.everyScalarWithAPathParameterIsRefusedUnless…` | new scalars with a `path` parameter pass the guard: `->>`, `import_database`, `index_key`, `json_*` … (the `sec/sqlguard-duckdb2` branch, §3, was written for this on the alpha) |
| SQL AST | `SqlAstContractTest`; `SqlSandboxTest` T2 (a bare bind parameter is no longer refused), T3 (the slimmed AST now deserialises), T4 (`query_location_length` end offsets) | `json_serialize_sql` shape: `NOT IN` is `OPERATOR_NOT(COMPARE_IN)`, offsets carry a length |
| Error text | `DuckDbPendingQueryPreambleTest`, `FailureTextTest` | the driver no longer writes the pending-query preamble; a conversion error now quotes the source column and its value in a new shape — the value-stripping must be re-checked |
| Capability | `PartitionWriterLayoutSpikeTest.fileSizeBytesCannotBeCombinedWithPartitionBy` | 2.0 now accepts `FILE_SIZE_BYTES` with `PARTITION_BY` (a gain; the spike's pin flips) |
| Results drift | `ColumnProfileGoldenTest` (2 distinct counts), `PartnerSettlementSeedTest` (`settlement_billed` seed type drifted from its Job SQL), `TelcoRerateGoldenTest` mutant pin | computed values or inferred types differ; each needs a look before regenerating anything |
| Probably flaky | `EngineDatasetProviderFingerprintTest.theCapIsExactly…` | `unknown:timeout` |

Green on 2.0: the LA index (`IndexBuilderTest v2.`), asn, config, graph, LA core / storage, ops, scoring, screening and
the rest (56 reactor modules ran). A later targeted re-run showed `NoClassDefFoundError` in two classes that passed in the
full run — the shared `C:/.m2` jars had been replaced by another build meanwhile (the stale-jar trap), not DuckDB.

### Benchmark — interleaved A1 (1.5.6) · B1 (2.0) · A2 (1.5.6) · B2 (2.0), `-Warmup 1 -Reps 3`

> ⚠ **Taken on a LOADED machine** (operator's call, 2026-10-22): an orphan `C:\sandbox\bench-5e8\sampler.ps1` (PID 15412,
> running since 2026-10-21) held a core throughout, and other sessions were building. Busy CPU before each side: A1 42 %,
> B1 11.5 %, A2 25.4 %, B2 14 %. Same-version drift was up to ±32 %, so only large, sign-stable differences count.
> The LA workloads (`la1m`, `la10m`) were not run: their corpus is not on this machine. Raw JSON: `C:\bench-ga2`.

Rule (§2): real only if the sign holds in both A-vs-B pairs **and** both deltas exceed the same-version drift.

| Workload | A1→B1 / A2→B2 | Same-version drift (A1→A2 / B1→B2) | Verdict |
|---|---|---|---|
| Engine ingest — union | +15.5 % / +6.3 % time | +17.2 % / +7.8 % | **not established.** The alpha's +95…106 % regression is gone or now under the noise |
| Engine ingest — generation | +6.7 % / +6.3 % time | +31.7 % / +31.2 % | **not established** (alpha: +79…89 %) |
| CSV → parquet write | −47.7 % / −39.5 % time | −4.1 % / +10.9 % | **real gain**, as on the alpha |
| CSV read, transform, total rows/s | mixed sign or under drift | | not established |
| Scan filter + aggregate | +78 % / +132 % time (2.2–2.8 ms → 5.0–5.2 ms) | −19.8 % / +4.3 % | **real regression**, small in absolute terms |
| Scan high-cardinality group by | +29.0 % / +34.8 % | −1.3 % / +3.2 % | **real regression** |
| Scan top-N · count distinct | +34.1 % / +39.8 % · +13.7 % / +24.9 % | −3.1 % / +1.1 % · −0.8 % / +9.0 % | **real regression** |
| Concurrent readers, 8 threads | −14.4 % / −17.1 % queries/s | +10.2 % / +6.7 % | **real regression**; 1 and 4 threads not established |
| Peak memory | +20…50 MB on scan / readers | | minor |

### The `store` workload (operational-store inserts) — slow on both versions

- **1.5.6.0: did not finish.** Side A1 of a first attempt ran the 50,000-row `INSERT … ON CONFLICT DO NOTHING` ledger
  shape from 12:38 until stopped at 20:03 (7.5 h, about 8.8 CPU-hours, warm-up plus three reps unfinished) on the loaded
  machine. That run was abandoned; no 1.5.6 `store` number exists.
- **2.0.0.0 alone: 61 min** for warm-up plus three reps (loaded machine, 36 % busy before it): ledger batch 247–297
  rows/s, ledger all-duplicates 145–294, single-row autocommit 110–154, upsert 234–263, **plain batched `INSERT` into a
  table with no key only 568–859 rows/s**, re-sync 1,847–2,863 rows/s.
- So the §2 dedup-ledger finding is worse than recorded: **row-at-a-time JDBC inserts into a file-backed DuckDB run at
  hundreds of rows per second on both versions**, keyed or not. Every operational store that writes this way (ledger,
  status, events) needs a bulk path (Appender or a staged `INSERT … SELECT`), independent of the pin move. The harness's
  "store ~1 min" estimate was wrong and is corrected in `tests-performance.md`.

### What the trial changes

- **The ingest gate (§6, first bullet) looks met on GA** — the engine regression is not visible above the noise — but it
  must be re-measured on a quiet machine before it is called closed.
- **New blockers on GA:** the two masking reds and the `SqlGuard` path-function red are security contracts and come
  first; then the AST (§5.3), the error-text seams and the result-drift tests.
- **Scan and concurrent-reader regressions** (+29…40 % on group-by / top-N, −15 % at 8 readers) are new evidence the
  operator should weigh against the parquet-write gain.

## 3. What is already built (unmerged branches, all local, none pushed)

| Branch | Commit | What it holds | Merge? |
|---|---|---|---|
| `worktree-agent-a6325aaa8d0cf9744` | `3879e07fa` | **The upgrade:** `duckdb.version` → `2.0.0-alpha43385-881`, the staging repository (temporary), the dependency lock, the extension-ABI derivation (`-881` is the JDBC build counter, the ABI directory is `v2.0.0-alpha43385`), lambda syntax `lambda x:` in five Space templates and two test probes, `IndexBuilderTest` `v1.` → `v2.`, an OKF DuckDB 2.0 section, rows `DUCKDB-2-MIGRATION-1` and `DUCKDB-2-SQL-AST-SPA-1` | **No** — not until the §6 gates pass |
| `sec/sqlguard-duckdb2` | `36e700ce2` | **`SqlGuard` fix** (on top of the upgrade): refuses the five new `*_external_resource` functions and `setval` by name; the contract test now pins an explicit reviewed set of 25 JSON-path / string scalars | **Unverified** — its tests were never run (§5, item 4) |
| `lane/duckdb-2-ga` | `35e8def16` | **The GA trial** (§2a): `2.0.0.0` from Central, no staging repository, every lambda migrated on current master. Supersedes the alpha upgrade branch as the base for the move; `sec/sqlguard-duckdb2` still has to be ported onto it | **No** — the §2a blockers first |

Already on master (this session): the benchmark harness (`tools/bench-duckdb.ps1`, `tools/bench-duckdb-compare.mjs`,
`tools/bench/DuckDbBench.java`, a `-Dinspecto.bench.idxDir` knob on `IndexScaleBench`) and the loop-query research plan.

**Behaviour differences found on 2.0** (all recorded in `okf/product/module/engine/spec-duckdb.md` on the upgrade branch):

- `x -> …` lambda arrow is a Binder error; `lambda x: …` works (and works on 1.5.6, so it can ship before any pin move).
  `SET lambda_syntax='ENABLE_SINGLE_ARROW'` restores the arrow. User-authored Job / expression SQL still using the arrow
  fails until edited.
- Every Link Analysis index reports `duckdb_version_changed` and rebuilds (the index records `SELECT version()`).
- `json_serialize_sql` changed shape: literal `{kind,text}`, function operands `arguments:[{name,expression}]`,
  `NOT IN` is `OPERATOR_NOT(COMPARE_IN)`, boolean literals, `query_location_length` on every node — this breaks the SPA
  reader `inspecto-ui/src/app/inspecto/query/sql-ast.ts` and `SqlAstContractTest`.
- 2.0 names a `path` parameter on `->>`, `json_*`, `variant_*`, `path_join`, `index_key`; all 25 such scalars are
  string / JSON-path functions that never touch the filesystem (measured: `json_extract('{}', '/etc/passwd')` is NULL).
- `current_setting` returns directory paths (already true on 1.5.6); `KpiEvaluatorTest` uses `current_setting('TimeZone')`.

## 4. Open decisions (the operator's, with the assistant's recommendation)

1. **`current_setting` in `SqlGuard`:** block it / allow only `TimeZone` / accept the path leak. **Recommend: allow only
   `TimeZone`** (the only use in the codebase).
2. **`SqlSandboxTest` T3 / T4:** re-assess (operator, 2026-10-05) — the tests stay red and documented until then. T4 matters
   for whether text-splicing an edit into the author's SQL is now possible.
3. **Pin policy:** stay on the alpha for the branch work, switch to the GA coordinates when 2.0.x GA reaches Maven
   Central, and delete the staging `<repositories>` block then (`DUCKDB-2-MIGRATION-1` carries that TODO).
4. **Offline builds with the alpha jar:** Maven's `_remote.repositories` records the jar as from repository id `duckdb`,
   which the project's offline build does not list. A workaround works locally
   (`-Daether.enhancedLocalRepository.trackingFilename=_none.repositories`) but CI needs the repository declared or the
   jar vendored. Decide before any CI run on the upgrade branch.

## 5. Build order for the next session

Lanes marked ∥ touch disjoint files and can run in parallel; the timing lanes need a quiet machine.

1. **Engine-ingest regression profile** (the gate for everything). On a worktree of the upgrade branch: per-statement
   timing at the JDBC boundary on 1.5.6 vs 2.0 *in the same process, alternating*; check whether a 2.0 default changed
   (`threads`, `preserve_insertion_order`, partitioned-write behaviour, row-group size, checkpoint / WAL) by setting the 1.5.6
   default explicitly; reduce to a standalone reproduction; either a connection-open `SET` that restores performance (with an
   engine test and a before/after ratio) or an upstream-ready bug report.
2. ∥ **Dedup-ledger insert rate:** find the real `claim()` batch sizes; measure the real `DbDedupLedger` on both versions
   (1 k / 10 k / 100 k keys, with pre-existing rows, autocommit vs one transaction); compare plain `INSERT`, `INSERT OR
   IGNORE`, `WHERE NOT EXISTS` and an Appender-into-staging plus one anti-join; if production batches are large, build the
   bulk path with the existing concurrency contract and the Postgres variant intact (a Postgres is on `localhost:5432`).
3. ∥ **SPA SQL reader for the 2.0 AST** (operator: yes): rewrite `sql-ast.ts` for the 2.0 shape only, regenerate the contract
   json from real `json_serialize_sql` output so `SqlAstContractTest` and the SPA specs are green together; do not touch T3 / T4
   or the `SqlGuard` test; close `DUCKDB-2-SQL-AST-SPA-1`.
4. ∥ **Verify the `SqlGuard` branch:** `-pl :inspecto-sql -am` with the contract and parse-tree tests; mutation-check the new
   reviewed set (remove one entry, expect red naming it); then merge it into the upgrade branch.
5. **Loop-query batch** (§7 below): lambda-arrow migration (the upgrade branch already covers the
   Space templates; check what is left), the temporal burst / periodicity scan as window functions (gains on both versions,
   golden-fixture parity first), and the `USING KEY` reachability spike behind `SqlGraphEngine` benchmarked at 10^6 and
   10^8 edges on both pins — adopt only if it beats `IndexedTraversal` at 10^8. Timing lanes after item 1, on a quiet machine.
6. **Re-run the interleaved A/B/A/B** after items 1–2 on the final candidate, then the full reactor and the UI suite on the
   upgrade branch before it is merged.

## 6. Gates before the upgrade branch merges to master

- Engine-ingest regression explained and either fixed on our side or reported upstream with a reproduction (§5.1).
- `SqlGuard` branch verified and merged in (§5.4); `current_setting` decided (§4.1).
- SPA reader and `SqlAstContractTest` green together (§5.3); T3 / T4 re-assessed by the operator.
- CI can resolve the driver (§4.4) and `tools/dependencies.lock` / `check-dependencies` are green.
- Full reactor (`mvn -o clean test -Pedition-advanced`) and the whole UI suite pass; the interleaved benchmark shows no
  regression on ingest and Link Analysis lookups beyond what the operator accepts.
- At GA: pin the GA version, remove the staging repository, re-run the benchmark once.

## 7. Looping / graph-style queries — the open candidates (research 2026-10-05)

> ⚠ **Timings are directions, not numbers to quote.** Every "verified" claim was run on `duckdb_jdbc
> 2.0.0-alpha43385-881` (and, where it matters, on `1.5.6.0`) with tiny throw-away queries: **2 threads, one run each,
> an alpha build, a machine busy with another benchmark**, mostly a 20 000-node / 400 000-edge in-memory graph. Each
> candidate names the benchmark that must re-measure it before anything is built. Nothing here is built and no
> candidate decision is signed beyond §1 item 4 ("use 2.0's capabilities for the looping queries").

**What the research found.** DuckDB 2.0 has **no native graph syntax**: `CREATE PROPERTY GRAPH` / `GRAPH_TABLE` /
`ANY SHORTEST` are parse errors on both versions and the community `duckpgq` extension is an HTTP 404 for both, so
decision `D9` ([`la-separation-feasibility-plan.md`](../archived-documents/plans-archive/la-separation-feasibility-plan.md))
has no native-syntax answer yet. The one big 2.0 win is **recursive-CTE `USING KEY`** (present on 1.5.6 too): on 2.0
BFS reachability ran ~3x faster (47 ms vs 161 ms) and min-label propagation 81 ms where 1.5.6 was cancelled at 6 s.
Path *enumeration* (`recursive-paths`) gains nothing (51 ms vs 47 ms). Joins, aggregates and windows showed no general
2.0 speed-up; a `RANGE`-framed window beats a self range-join ~10x on **both** versions. The harness for the LA
candidates is `InvTraversalBench` (`inspecto-geo-link/src/test/java/com/gamma/control/InvTraversalBench.java`,
gate G-R4: 5 hops over 10^6 edges under 350 ms, runs only with `-Dinspecto.bench.dir=…`).

| # | Candidate | MoSCoW | Needs the 2.0 pin? | Benchmark / proof that must pass first |
|---|---|---|---|---|
| C7 | Lambda syntax `x -> …` → `lambda x: …` (both `ba_revenue_forecast_*` Space templates, `IngestExpressionSandboxTest:85`, `SqlGuardParseTreeTest:46`; also grep the SPA SQL editor snippets) — still on master 2026-10-22, done on the upgrade branch | **Must** (precondition of the pin move) | no — works on 1.5.6 | the four sites' tests on both drivers |
| C3 | Temporal `burst` / `periodicity` in DuckDB (`count(*) OVER (… RANGE …)`, `lag(ts)` gaps, `stddev/avg`) instead of hauling ≤ 200 000 rows to the JVM (`PatternRoutes.scan`, `TemporalFindings`, `IndexedTemporal`) | **Must** | no | golden-fixture parity with the Java detector (tie rules, `skippedNoTime`, `rowCapped`, byte-identical index variant), then a 10^7-event bench |
| C1 | `UsingKeyGraphEngine`: k-hop / reachability / shortest path in one `WITH RECURSIVE … USING KEY` statement behind the `SqlGraphEngine` seam, lifting the 2-hop / 20-key caps (with C2, this is the open Could item C7 of `ANALYTICS-CORE-1`) | **Must** | measure on both; shines on 2.0 | `InvTraversalBench` G-R4 at 10^6 and 10^8 edges vs `IndexedTraversal` / `SqlGraphEngine`, both pins; parity vs `graph-paths-parity.fixture.json`. **Adopt only if it beats `IndexedTraversal` at 10^8** (a CTE cannot prune Parquet partitions per iteration — unproven); re-state the cap / four-eyes bound (D-U7) as a visited-node cap + the 5 s timeout; `SqlGuard` node whitelist for `USING KEY` not yet run |
| C2 | Components by `USING KEY` label propagation | Should | yes (regresses badly on 1.5.6) | parity with `GraphStructure.connectedComponents` (`canonical-v1`, rule `D-S4`) + a 10^6-edge bench; converged labels never checked |
| C4 | Branching-pattern legs with `ASOF JOIN` (135 ms vs 290 ms `LATERAL … LIMIT 1`) | Could | no | `ControlApiInvPatternTest` + `branching-parity.spec.ts` stay green; legs-out vs stage count |
| C8 | `ducklake` as Draft / Working Set storage (attach + create works on 2.0) | Could (spike) | yes | a spike with the `D-S5` admission numbers; snapshots, time travel, multi-writer, offline vendoring and licensing unverified |
| C5 | Multi-rung expand ladder as one statement | Could, **only after C1 has numbers** | — | the sealed-Investigation fingerprint (`sha256(canonical(rows))`) must not change |
| — | C6 path enumeration, C9 native SQL/PGQ (re-spike when a `duckpgq` build for the final 2.0 exists), C10 Parquet v2 / bloom knobs, C11 `MERGE INTO` / `VARIANT` / `vss` | Won't (now) | — | no measured gain, or not a loop query |

Lanes: C7 and C3 are safe to run anywhere; C1 and the pin-move rehearsal (§5) need a quiet machine. **Not verified —
do not cite as fact:** HNSW / `vss` use, `ducklake` beyond attach, bloom filters on VARCHAR keys, `USING KEY` over a
Parquet index scan and at 10^8 edges, converged component labels, PageRank-style iteration, the `SqlGuard` allow-list
for the `USING KEY` node, and anything about the 2.0 final release.

## References

- [`duckdb-2-loop-queries-plan.md`](../archived-documents/plans-archive/duckdb-2-loop-queries-plan.md) (archived; §7 above carries its open part) — the 13-site loop inventory, the verified `USING KEY` / `ASOF` / window experiments and the candidate list C1–C11.
- `okf/product/layer/platform/tests-performance.md` — how to run the comparison harness.
- `okf/product/module/engine/spec-duckdb.md` — the engine's DuckDB concept (the 2.0 section lives on the upgrade branch until it merges).
- `docs/BACKLOG.md` §4 — `DUCKDB-2-MIGRATION-1` and `DUCKDB-2-SQL-AST-SPA-1` (on the upgrade branch).
