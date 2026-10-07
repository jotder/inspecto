# DuckDB 2.0.x adoption — decision, evidence and the build order for the next session

**Status: PLAN, 2026-10-05 — decided, not built.** The operator decided on 2026-10-05: *go with DuckDB 2.0.x from
now on* (the alpha now, GA expected in a couple of weeks), for the performance and capability gains. This file is the
single place for the decision, what was measured, what is already built on unmerged branches, what is blocked, and the
order in which to build. The looping-query research is in
[`duckdb-2-loop-queries-plan.md`](duckdb-2-loop-queries-plan.md); this file does not repeat it.

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

Harness: `tools/bench-duckdb.ps1` (merged to master; see `okf/backend/build-run/performance.md`). Four sides run
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

## 3. What is already built (unmerged branches, all local, none pushed)

| Branch | Commit | What it holds | Merge? |
|---|---|---|---|
| `worktree-agent-a6325aaa8d0cf9744` | `3879e07fa` | **The upgrade:** `duckdb.version` → `2.0.0-alpha43385-881`, the staging repository (temporary), the dependency lock, the extension-ABI derivation (`-881` is the JDBC build counter, the ABI directory is `v2.0.0-alpha43385`), lambda syntax `lambda x:` in five Space templates and two test probes, `IndexBuilderTest` `v1.` → `v2.`, an OKF DuckDB 2.0 section, rows `DUCKDB-2-MIGRATION-1` and `DUCKDB-2-SQL-AST-SPA-1` | **No** — not until the §6 gates pass |
| `sec/sqlguard-duckdb2` | `36e700ce2` | **`SqlGuard` fix** (on top of the upgrade): refuses the five new `*_external_resource` functions and `setval` by name; the contract test now pins an explicit reviewed set of 25 JSON-path / string scalars | **Unverified** — its tests were never run (§5, item 4) |

Already on master (this session): the benchmark harness (`tools/bench-duckdb.ps1`, `tools/bench-duckdb-compare.mjs`,
`tools/bench/DuckDbBench.java`, a `-Dinspecto.bench.idxDir` knob on `IndexScaleBench`) and the loop-query research plan.

**Behaviour differences found on 2.0** (all recorded in `okf/backend/engine/duckdb.md` on the upgrade branch):

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
5. **Loop-query batch** (from `duckdb-2-loop-queries-plan.md` §6): lambda-arrow migration (the upgrade branch already covers the
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
- Full reactor (`mvn -o clean test -Pedition-enterprise`) and the whole UI suite pass; the interleaved benchmark shows no
  regression on ingest and Link Analysis lookups beyond what the operator accepts.
- At GA: pin the GA version, remove the staging repository, re-run the benchmark once.

## References

- [`duckdb-2-loop-queries-plan.md`](duckdb-2-loop-queries-plan.md) — the 13-site loop inventory, the verified `USING KEY` / `ASOF` / window experiments and the candidate list C1–C11.
- `okf/backend/build-run/performance.md` — how to run the comparison harness.
- `okf/backend/engine/duckdb.md` — the engine's DuckDB concept (the 2.0 section lives on the upgrade branch until it merges).
- `docs/BACKLOG.md` §4 — `DUCKDB-2-MIGRATION-1` and `DUCKDB-2-SQL-AST-SPA-1` (on the upgrade branch).
