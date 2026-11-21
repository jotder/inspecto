---
type: Concept
title: DuckDB Integration — DuckDB 2.0.1.0, the pinned version
description: Part 1 of 2 of DuckDB Integration: DuckDB 2.0.1.0 LTS (pinned 2026-11-21) on top of the 2.0.0.0 migration (DUCKDB-2-MIGRATION-1)
tags: [engine, duckdb, performance, appender, threads]
timestamp: 2026-06-28T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-engine
---

# DuckDB Integration — DuckDB 2.0.1.0, the pinned version

Part 1 of 2 of [DuckDB Integration](spec-duckdb.md) · next: [Sealed connections, time zones and aggregate state](spec-duckdb-connections-state.md).

## DuckDB 2.0.1.0 LTS (pinned 2026-11-21)

`pom.xml` `duckdb.version` = **`2.0.1.0`**. Every 2.0.0 fact in the next section still holds, except these, each
measured on both drivers side by side:

| 2.0.1 behaviour | What the product does | Pinned by |
|---|---|---|
| ⚠ **Still wrong results:** `window_self_join` still counts hive-pruned days (3 where 2 is right) | the workaround stays; reported on [duckdb/duckdb#28947](https://github.com/duckdb/duckdb/issues/28947) (2.0.1 comment 2026-11-21) | `DuckDbWindowSelfJoinWorkaroundTest` canary |
| `levenshtein`, `jaro_similarity`, `jaro_winkler_similarity` and `jaccard` count **code points**, not UTF-8 bytes (`levenshtein('é','e')` 2 → 1) — similarity scores on accented or non-Latin text change | the JVM twins (`JaroWinkler.DUCKDB`, `SimilaritySql.Method.LEVENSHTEIN`) use `TextUnit.CODE_POINT`; the Levenshtein score divides by `length()`, not `strlen()` | `SimilarityDuckDbParityTest`, `EntityGroupingParityTest`, `RowShaperSimilarityTest` |
| ⚠ **`round(DOUBLE, n)` rounds the exact binary value** (`round(52.05::DOUBLE, 1)` = 52.0, 2.0.0: 52.1; `round(2.50005::DOUBLE, 4)` = 2.5, 2.0.0: 2.5001); `round` on a DECIMAL and `round(x)` / `round(x, 0)` are unchanged | every shipped `round(<DOUBLE>, n)` with `n ≥ 1` is written `CAST(CAST(x AS DECIMAL(38,n)) AS DOUBLE)` — equal to 2.0.0's `round` on 700,000 sampled doubles at every n in 0–8, and still DOUBLE; where the divisor can be 0, `CASE WHEN isfinite(x) THEN … ELSE x END` keeps inf/NaN (a DECIMAL cast of either raises). ⛔ Do not write `round(CAST(x AS DECIMAL(38,10)), n)`: it double-rounds (`-81.865` → `-81.87`). ⚠ The Record Transformer's `num.round` stays native `ROUND` (operator, 2026-11-21): its compiler knows column names, not types, so a user's `num.round` on a DOUBLE column rounds the 2.0.1 way (`NUM-ROUND-TYPE-AWARE-1`); on the ingest side `num.*` functions run on the all-VARCHAR raw columns and do not bind at all (`RECORD-TRANSFORM-NUM-VARCHAR-1`) | `PartnerSettlementAgingGoldenTest`, `TelcoBypassClusterGoldenTest`, the template goldens |
| ⚠ **America/Havana** disagrees with the JDK's tzdata 2026b: DuckDB 2.0.1 keeps Cuba on UTC−4 from 2026-11-01T05:00Z for 3,192 hours (to about mid-March 2027); the JDK and DuckDB 2.0.0 move to UTC−5 that day. Every other zone checked (London, New York, Kyiv, Asunción, Apia; hourly 2020–2027) agrees | a Space in Havana time gets DuckDB and JVM labels an hour apart for that window; the parity test checks Havana's 2025 transition instead until they agree (`DUCKDB-2-0-1-HAVANA-TZ-1`) | `TimeGrainDuckDbParityTest` |

## DuckDB 2.0.0.0 (pinned 2026-10-23, `DUCKDB-2-MIGRATION-1`)

`pom.xml` `duckdb.version` was **`2.0.0.0`** (GA, Maven Central, no extra repository; extensions resolve to ABI
`v2.0.0`). The move from 1.5.6.0 changed these engine facts — each is pinned by the named test, so a later bump that
changes one again fails loudly:

| 2.0 behaviour | What the product does | Pinned by |
|---|---|---|
| ⚠ **Wrong results:** the `window_self_join` optimizer rewrites an aggregate `OVER (PARTITION BY …)` (`count`, `sum`, `avg`, `max`, `count(DISTINCT)`, `list` …; not `rank` / `row_number` / ordered frames) into a self-join over a copy of the scan, and the copied `read_parquet` loses hive-partition pruning — a day-1 window aggregates day-2 rows | `DuckDbUtil.applyCorrectnessSettings` runs `SET disabled_optimizers = 'window_self_join'` on every analytics connection (`openConnection`, `openInMemory`, `applyGlobalDuckDbSettings`); the setting is global-scoped, survives `lock_configuration`, and author SQL cannot `SET` it back (`SqlGuard`). Upstream report owed (`DUCKDB-2-WINDOW-SELF-JOIN-UPSTREAM-1`) | `DuckDbWindowSelfJoinWorkaroundTest` (its canary fails once the raw driver stops miscounting: then remove the setting) |
| The single-arrow lambda `x -> …` is a Binder error | every DuckDB lambda is `lambda x: …` / `lambda x, i: …` (engine, Space Templates, tests) | the template golden tests |
| `json_serialize_sql` shape: constants `literal {kind, text}`, function operands `arguments[].expression`, `NOT IN` = `OPERATOR_NOT(COMPARE_IN)`, `TRUE` a `BOOLEAN` literal, `query_location_length` on every node | the SPA condition reader (`inspecto/query/sql-ast.ts`) reads the 2.0 shape only; `BiResultMasking` reads both operand lists and fails closed on neither | `SqlAstContractTest`, `ControlApiBiQueryMaskingTest`, `ControlApiDbBrowserMaskingTest` |
| 22 non-table functions name a `path` parameter | `SqlGuard` refuses `import_database` / `export_database`, `setval` / `nextval`, `sleep_ms` / `pg_sleep` and the external-resource functions by name; the 21 JSON / VARIANT path extractors, `path_join` and `index_key` are a reviewed set that never opens a file | `SqlGuardFileFunctionContractTest` |
| A runtime error echoes the SQL as `LINE n: …`, cut off at a fixed width (a literal can lose its closing quote); the pending-query preamble is gone | `FailureText` fingerprints the whole echo line; the preamble strip stays as a sanitiser | `FailureTextTest`, `DuckDbPendingQueryPreambleTest` |
| `DECIMAL(18,4) ± DECIMAL(18,4)` widens to `DECIMAL(19,4)` (1.5 capped at 18), one digit per further term | a Job that stores a `±` of `DECIMAL(18,s)` columns `CAST`s it back to the 1.5 type: partner-settlement `variance`, telco-ra `ra_rollforward` `LEAKAGE_AMOUNT`; every other Space Template Job, View and Measure stores the same type on both engines (audit below) | `PartnerSettlementSeedTest`, `TelcoRaGoldenTest` |
| `approx_count_distinct` is sharper (1.5's sketch merged two of three dates) · `FILE_SIZE_BYTES` now combines with `PARTITION_BY` (the writer still sizes files by bucket count) · a bare `?` binds in `json_serialize_sql`, a slim tree round-trips, nodes carry a length (the tree stays read-only, operator 2026-10-05) | — | `ColumnProfileGoldenTest`, `PartitionWriterLayoutSpikeTest`, `SqlSandboxTest` T2–T4 |

Performance (interleaved A/B/A/B, 2026-10-23, quiet machine): engine ingest level with 1.5.6, parquet write 30–60 %
faster, 4–8 concurrent readers about 10–20 % slower; row-at-a-time JDBC inserts into a file-backed store run at
hundreds of rows/s on BOTH versions (`OPSTORE-BULK-INSERT-1`). The decision, every measurement and the trial history
are in the archived [`duckdb-2-plan.md`](../../../../archived-documents/plans-archive/duckdb-2-plan.md).

### Bulk inserts into the operational stores (`OPSTORE-BULK-INSERT-1`, 2026-11-01)

A row per JDBC statement into a file-backed DuckDB runs at hundreds of rows/s, batched or not. The real per-call
batch sizes decided where a bulk path pays:

| Writer | Rows per call | DuckDB path now |
|---|---|---|
| `DbDedupLedger.claim` | every distinct key of one Consignment's window (thousands to millions) | Appender into a per-call TEMP staging table, then ONE `INSERT … SELECT DISTINCT … ON CONFLICT DO NOTHING RETURNING key_hash`; `RETURNING` yields only the inserted rows, which is the won set |
| `DbStatusStore.sync` | every status row of every Pipeline, after each committing poll | Appender straight into each table, inside the sync's transaction (a failed sync still rolls back every table) |
| `RowShaper.windowedDedup` lost-key scratch table (in-memory) | every lost key (all of them on a re-ingest) | Appender |
| `DbEventStore.append` | ONE event, audit-chained | unchanged: nothing to batch |

Postgres keeps its statements: the claim stays one row per autocommit statement (the pool is truly concurrent, and a
multi-row claim could deadlock two overlapping claimers), and the sync keeps its batched `INSERT`. The DuckDB source is
one `synchronized` connection, so claims on it are serial in-process, and the file lock keeps other processes out.
Exactly one claimer wins each key; pinned by `DbDedupLedgerTest.concurrentBulkClaimsOfOverlappingKeysYieldOneWinnerPerKey`
(4 threads, overlapping slices, file-backed; it fails when the bulk path reports its whole input as won) and run on
Postgres by `PostgresStateStoreTest` / `PostgresSchemaPerSpaceStoresTest`.

Measured 2026-11-01, DuckDB 2.0.0, `tools/bench-duckdb.ps1 -Workloads store -Warmup 0 -Reps 2 -Scale 0.2` (10,000-row
ledger steps, 20 × 200-row re-syncs; rates are per row, so scale does not change them). Range over the two reps:

| Shape | Before (row per statement) | After (bulk) |
|---|---|---|
| ledger claim, new keys | `ledger_batch_rps` 224–244 rows/s | `ledger_bulk_rps` 74,930–88,532 rows/s |
| ledger claim, every key a duplicate | `ledger_dupe_rps` 182–225 rows/s | `ledger_bulk_dupe_rps` 228,689–245,654 rows/s |
| status re-sync (DELETE + insert, commit each) | `resync_rps` 1,419–1,924 rows/s | `resync_bulk_rps` 28,705–30,322 rows/s |

`upsert_rps` (215–257) and `events_batch_rps` (450–640) are unchanged: the one product upsert,
`DbConsignmentOutputStore.record`, writes one row per output file of a Consignment (a handful per call), and events
arrive one per `append`, so neither batch justifies a bulk path.

### Decimal widening audit (`DUCKDB-2-DECIMAL-WIDENING-AUDIT-1`, 2026-10-31)

**The rule, measured** on the 1.5.6.0 and 2.0.0.0 JDBC drivers side by side:

| Expression | 1.5.6.0 | 2.0.0.0 |
|---|---|---|
| `DECIMAL(18,4) + DECIMAL(18,4)` (also `-`, `abs(a - b)`, a `CASE` mixing it with a plain `DECIMAL(18,4)`) | `DECIMAL(18,4)` | `DECIMAL(19,4)` |
| `DECIMAL(18,2) - DECIMAL(18,2)` | `DECIMAL(18,2)` | `DECIMAL(19,2)` |
| `a + b - c` / five terms, all `DECIMAL(18,4)` | `DECIMAL(18,4)` / `DECIMAL(18,4)` | `DECIMAL(20,4)` / `DECIMAL(22,4)` |
| `DECIMAL(17,4) + DECIMAL(17,4)` | `DECIMAL(18,4)` | `DECIMAL(18,4)` |
| `DECIMAL(18,4) * DECIMAL(18,8)`, unary minus | `DECIMAL(18,12)`, `DECIMAL(18,4)` | same |
| `sum(DECIMAL(18,4))`, `sum(…) - sum(…)` | `DECIMAL(38,4)` | `DECIMAL(38,4)` |
| `hash()` / `CAST(… AS VARCHAR)` / `md5` of the text, one value at width 18, 19 and 22 | equal | equal |
| `99999999999999.9999 + 1` at `DECIMAL(18,4)` | Out of Range error | `100000000000000.9999` |

So the change is to the **type** of a `±` of two width-18 decimals, never to its value: a comparison, a `sum`, a
hash and the text form read the same on both engines, and 2.0 no longer overflows where 1.5 did. What a widening can
change is a **stored** type: a sink's Parquet column (`DECIMAL(18,s)` is an INT64, wider is a fixed-length byte
array), a schema seed, a downstream union. Only those were fixed.

**How it was swept:** a type probe registered every Space Template's Schemas and schema seeds as empty tables, ran
every `sql.template` Job (incremental ones bootstrapped without their own-sink `UNION ALL BY NAME` branch) and
every View `derived_sql` in chain order with `LIMIT 0`, and compared `DESCRIBE` of every output column on both
drivers: 68 Jobs and Views, 993 columns, **one** differs. Not run by the probe, read instead: `tb_clusters`,
`tb_cluster_members`, `tb_findings` (read Entity List and Risk Score tables; their only `±` are `BIGINT` counts),
`ra_recovery` (no arithmetic), and `ba_margin_erosion` / `ba_revenue_forecast` (their Views, which embed the same
SQL, were run). Measures are `sum(col)` or `count` only (`DECIMAL(38,s)` either way); Dataset `calculated` fields
are date expressions; the two mapping `custom` expressions are `DOUBLE`.

| File | Expression | 1.5 type | 2.0 type | Verdict |
|---|---|---|---|---|
| `telco-ra/config/jobs/ra_rollforward_job.toon` | `LEAKAGE_AMOUNT = OPENING + TOPUPS + ADJUSTMENTS - USAGE_DEBITS - CLOSING` / `PREV_CLOSING - OPENING` (stored) | `DECIMAL(18,4)` | `DECIMAL(22,4)` | **Fixed**: `CAST(… AS DECIMAL(18,4))`, the type 1.5 wrote; `TelcoRaGoldenTest` pins every findings sink's `LEAKAGE_AMOUNT` type |
| same | `ABS(… - CLOSING) > tolerance`, `ABS(OPENING - PREV_CLOSING) > tolerance` | `DECIMAL(18,4)` | `DECIMAL(22,4)` / `DECIMAL(19,4)` | Harmless: a comparison |
| `partner-settlement/config/jobs/ps_billed_job.toon`, `ps_findings_job.toon`, `ps_position_job.toon` | `variance`, `net` (stored) | `DECIMAL(18,4)` | `DECIMAL(19,4)` | Already `CAST(… AS DECIMAL(18,4))` (`DUCKDB-2-MIGRATION-1`); seeds pinned by `PartnerSettlementSeedTest` |
| same | `abs(amount_billed - amount) <= tolerance`, `abs(n.variance + j.variance)`, `abs(bil_amount - …)` | `DECIMAL(18,4)` | `DECIMAL(19,4)` | Harmless: a comparison |
| `partner-settlement/config/jobs/ps_rollforward_job.toon` | `sum(invoiced - paid + adjustments) OVER …` | `DECIMAL(38,4)` | `DECIMAL(38,4)` | Harmless: a `sum`, then `CAST(… AS DECIMAL(18,4))` |
| `partner-settlement/config/jobs/ps_statement_findings_job.toon` | `amount_delta = line_sum - ct_amount` (stored) | `DECIMAL(38,4)` | `DECIMAL(38,4)` | Harmless: `line_sum` is a `sum` |
| `telco-ra/config/jobs/ra_rerating_job.toon` | `LEAKAGE_AMOUNT = EXPECTED_CHARGE - CHARGE` (stored; `DECIMAL(37,2)` − `DECIMAL(18,4)`) | `DECIMAL(38,4)` | `DECIMAL(38,4)` | Harmless: one operand is already 37 wide |
| `telco-ra/config/jobs/ra_settlement_job.toon` | `BILLED_AMOUNT - EXPECTED_AMOUNT` (stored; `DECIMAL(38,4)` − `DECIMAL(38,2)`), `ABS(MIN/MAX_LINE_AMOUNT - EXPECTED_AMOUNT)` | `DECIMAL(38,4)` | `DECIMAL(38,4)` | Harmless: `sum`s |
| `telco-ra/config/jobs/ra_xdr_lost_job.toon` | `ABS(s.USAGE_UNITS - m.USAGE_UNITS)` | `BIGINT` | `BIGINT` | Harmless: not a decimal |
| `telco-rerating/config/jobs/rr_rerate_job.toon` | `CHARGE - EXPECTED_HIGH`, `EXPECTED_LOW - CHARGE` (`DECIMAL(18,4)` against `DECIMAL(37,2)`) | `DECIMAL(38,4)` | `DECIMAL(38,4)` | Harmless: one operand is already 37 wide |
| `telco-rerating/config/jobs/rr_explain_job.toon` | `EXPECTED_HIGH - ACTUAL_CHARGE`, `abs(CF_AMOUNT - ACTUAL_CHARGE)`, `abs(ACTUAL_CHARGE - ROUND(…))` | `DECIMAL(38,4)` or narrower | at most one digit wider | Harmless: comparisons and `sum`s; every stored column (`LEAKAGE_AMOUNT` `DECIMAL(38,4)`) is equal on both (the probe) |
| `mobile-money/config/jobs/mm_commission_job.toon` | `abs(commission_paid - commission_expected)` (stored) | `DECIMAL(38,2)` | `DECIMAL(38,2)` | Harmless: both are `sum`s |
| `mobile-money/config/jobs/mm_fee_job.toon` | `sum(greatest(fee - expected_fee, 0))` | `DECIMAL(38,2)` | `DECIMAL(38,2)` | Harmless: inside a `sum` |
| `mobile-money/config/jobs/mm_kyc_limit_job.toon` | `greatest(outflow - tier_limit, 0)` (stored) | `DECIMAL(38,2)` | `DECIMAL(38,2)` | Harmless: `outflow` is a `sum` |
| `features/inspecto-reconciliation/…/ReconService.java` `within` | `ABS(a - b) <= tol` over `SUM`s | `DECIMAL(38,s)` | `DECIMAL(38,s)` | Harmless: sums, compared, never stored |
| `platform/inspecto-analytics` (`AccuracySql`, `KMeansSql`, `LogisticSql`, `OutlierSql`, `Binning`) | `p - a`, `x - mean` … | `DOUBLE` | `DOUBLE` | Harmless: operands are cast to `DOUBLE` (`Binning` yields a `BIGINT` bin index) |
| `features/inspecto-ops/…/ObjectsAnalyticsJob.java` | impact amounts | `DECIMAL(21,6)` | `DECIMAL(21,6)` | Harmless: a declared table column, filled from Java |

**Rule for new SQL:** a `±` of two `DECIMAL(18,s)` columns that is **stored** gets an explicit
`CAST(… AS DECIMAL(18,s))` (or a deliberate wider type), and its template pins the stored type in a test.

* **Appender, not JDBC batch.** `DuckDbRecordSink` and `TypedRecordIngester` bulk-load via the DuckDB
  `DuckDBAppender` API (heap buffer `APPEND_BATCH = 10,000` rows). Benchmarked on 1M rows: JDBC
  `PreparedStatement.executeBatch` ≈ 6.9K rows/s vs the Appender ≈ 520–530K rows/s — **~75× faster**, at
  parity with the native CSV reader.
* **Thread auto-derivation.** `DuckDbUtil.effectiveWorkerThreads` (`platform/inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java`)
  derives per-batch `duckdb_threads`: `0` (default) with batch concurrency > 1 → `max(1, cores/concurrency)`;
  explicit `N` honored verbatim; `-1` → DuckDB's per-core default. Avoids the threads×cores oversubscription
  stall; `ConfigValidator` warns when explicit `threads × duckdb_threads` exceeds the core count.
* **GAP-4 CLOSED 2026-09-26 — `memory_limit` now has a CODE default (session decision).**
  `DuckDbUtil.memoryLimit` no longer ends at `null`: its last fallback is `DuckDbUtil.defaultMemoryLimit()` =
  **40 % of the RAM the JVM can see (`OperatingSystemMXBean.getTotalMemorySize`, the cgroup limit in a
  container) ÷ `DEFAULT_CONCURRENT_INSTANCES` (4), floored at 1 GiB**, as a whole-MiB string (32 GiB ⇒
  `3276MiB`, 16 GiB ⇒ `1638MiB`, 8 GiB ⇒ `1024MiB`). Why this shape: 40 % is the middle of `editions.md`'s
  sizing rule (*25–50 % RAM ÷ concurrency*); 4 is the Run bound's code default
  (`JobService.DEFAULT_MAX_CONCURRENT_RUNS`, pinned equal by `ControlApiSchedulerSettingsTest`), a FIXED
  divisor — not the live semaphore, which the 2026-07-25 call rejected; the 1 GiB floor is the 2026-07-27
  measurement's OOM cliff (512MB killed the blocking operators). A fixed `2GB` was not chosen: it overcommits
  an 8 GiB T1 host at 4 Runs and needlessly spills a large one. Override at any tier (per-pipeline key,
  `scheduler.toon`, `-Dprocessing.duckdb.memory_limit`); `80%` restores DuckDB's own behaviour. `GET
  /system/scheduler` serves the computed value with source `default`. ⚠ **Scope: the three paths that already
  resolved a limit** (`ConsignmentIngestStrategy.configure`, `PipelineJobRunner`, `EnrichmentEngine`). All
  three are file-backed scratch databases, so spill lands in `<dbfile>.tmp` beside them — never the CWD.
  **In-memory opens capped 2026-09-27 (`DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1`).** Every in-memory
  `jdbc:duckdb:` open now goes through `DuckDbUtil.openInMemory(spillDir)`: `memory_limit` via
  `memoryLimit(null)`, `temp_directory` = `spillDir` (created up front so DuckDB never removes it on close),
  plus the global `max_temp_directory_size` when set. ⛔ Not `applyGlobalDuckDbSettings`: an in-memory
  database's default `temp_directory` is `.tmp` **relative to the CWD** (probed on 1.5.2.1). Spill choice:
  callers with a Space data root use `DuckDbUtil.spillDirUnder(root)` = `<data root>/.duckdb_tmp` (dot-prefixed
  so dataset scans skip it) — `MaterializeTask`, `SqlTemplateJob`, `StorageReportTask`, `BackupTask`,
  `ConsignmentProcessJobType`, `RiskScoreEvaluator` (their `dataDir`/`dataRoot`), and `PartitionCompactor`, `ReferenceCompactor`,
  `StorageSeries`, `ObjectsAnalyticsJob` via `SpaceConfigRoot.currentDataRoot()` (the compactors deliberately
  NOT under the store dir they walk). No Space context ⇒ `spillDir = null` ⇒ `java.io.tmpdir`:
  `ParquetEventStore`, `ExchangeSnapshotWriter`, `PipelineDocumentXlsx`, `TypeFlow`,
  `SqlGuard`. ⚠ Data-root walks must skip the spill dir: `BackupTask` and `StorageReportTask` use
  `DuckDbUtil.regularFilesSkippingSpill` (no descent into `.duckdb_tmp`, vanished entries tolerated) so
  concurrent spill is never archived/counted and cannot fail the walk. Test: `DuckDbSettingsTest.openInMemory*`,
  `BackupTaskTest.backupNeverArchivesDuckDbSpill`, `MaintenanceLibraryTest.storageReportIgnoresDuckDbSpill`. `SqlSandbox` keeps its own 1GB cap.
  **Guard + spill proof (2026-09-28).** `RiskScoreEvaluator` reintroduced a raw open the day the row closed,
  so `NoRawInMemoryDuckDbOpenContractTest` (`inspecto-util`) now scans every top-level module's
  `src/main/java` for the in-memory URL literal not followed by `+` and fails on a hit; exempt by file:
  `DuckDbUtil` (the factory), `OperationalDbReport` (scheme allow-list), `JdbcDrivers` (prefix dispatch).
  `DuckDbSettingsTest.aRawInMemoryOpenSpillsIntoTheCwd` reproduces the defect on 1.5.2 (a 64MB-capped fill
  leaves `duckdb_temp_storage_*.tmp` under `<CWD>/.tmp`, per `duckdb_temporary_files()`), and
  `openInMemorySpillLandsInTheSpillDirNotTheCwd` shows the factory's spill in `spillDir`. Mutation-checked:
  dropping the factory's `temp_directory` or `memory_limit`, or restoring the raw Risk Score open, each turns
  a test red. **Session decision (no-Space fallback):** `java.io.tmpdir`, not a new configured scratch root —
  it is the only writable directory every opener already has, it is outside the CWD, and it needs no jail
  (the path is the JVM's own, never user input); a caller that gains a Space data root should pass it.
* **Memory / spill caps (opt-in; one knob for every scratch connection).** `DuckDbUtil.applyDuckDbSettings`
  sets `memory_limit` / `temp_directory` (spill) / `max_temp_directory_size` when a value is configured;
  unset ⇒ DuckDB's own default (≈ 80% RAM **per instance** — the aggregate-overcommit hazard under
  concurrency). The batch-ingest path caps its connections via `ConsignmentIngestStrategy.configure`
  (per-pipeline `processing.duckdb.*`). The **flow-job** (`PipelineJobRunner`) and **enrichment**
  (`EnrichmentEngine`) run scratch connections have no per-config `processing.duckdb` section, so they call
  `DuckDbUtil.applyGlobalDuckDbSettings`, which reads the global JVM fallbacks
  `-Dprocessing.duckdb.memory_limit` / `.temp_directory` / `.max_temp_directory_size` / `.threads`; the
  batch path honors the same globals as a fallback (`DuckDbUtil.globalOr`), so a single
  `-Dprocessing.duckdb.memory_limit` caps every DuckDB scratch connection uniformly. **All opt-in** — with
  no config or `-D` value set nothing is issued and behavior is unchanged — ⚠ **except `memory_limit`
  since GAP-4 (2026-09-26), which falls back to the code default above.** Set these on high-concurrency /
  multi-tenant boxes to prevent overcommit, and pair with `temp_directory` so an over-limit query spills to
  disk instead of OOM-ing. (Preview / dry-run connections — `ComponentPreview`, `PipelineDryRun`, enrichment
  `preview` — run over bounded samples and are deliberately left uncapped.)
* **D11 SHIPPED 2026-08-26 — HALF-ON until GAP-4 closed it 2026-09-26: `maxConcurrentRuns=4` is a CODE
  default; `memory_limit` had NO default at all**.
  `DuckDbUtil.memoryLimit(null)`
  returns `null`, no `scheduler.toon` ships, and the committed corpus sets the key to `""`. 2GB was the
  MEASURED recommendation (§below), never an installed default — BACKLOG GAP-4 is still open. Both are
  owned by the server
  configuration.** ⚠ **Only `maxConcurrentRuns` defaults on in code** (`JobService.java`); `DuckDbUtil.memoryLimit`
  falls through config → served `scheduler.toon` → `-Dprocessing.duckdb.memory_limit` → **DuckDB's own default**, and no
  `scheduler.toon` ships — `BACKLOG.md` GAP-4 is open. *(This line said "both on by default" until 2026-09-08.)* They are surfaced in the UI at **Settings ▸ Scheduler ▸ Resource
  caps**, persisted in `scheduler.toon`, and served with provenance (`file` | `property` | `default`) by
  `GET/PUT /system/scheduler`. Ownership moved deliberately: `DuckDbUtil.memoryLimit(configured)` is now the
  single use-time resolver — per-pipeline value > installed server value > `-Dprocessing.duckdb.memory_limit`
  (a bootstrap default only) > DuckDB's own default — because ⛔ a key served by the settings tier must not
  also be read from `-D` at use time (`SchedulerRoutes`, the 2026-08-15 operational-db decision).
  `JobService.setMaxConcurrentRuns` is the matching seam. ⚠ Preview / dry-run connections remain
  **uncapped** and must stay so: only `EnrichmentEngine`, `PipelineJobRunner` (via
  `applyGlobalDuckDbSettings`) and `ConsignmentIngestStrategy` (per-config path) resolve a limit.
  `max_temp_directory_size` still gets **no** default — none is defensible without the volume size.
  **Same-day review hardening + follow-ups, all shipped 2026-08-26:** the Run bound counts in-flight Runs
  even while unbounded (🔴 a `Semaphore` subclass that re-derived "did this Run take a permit?" from
  `cap()` at release ended at **7 permits under a cap of 4, permanently**), the installed bound is
  process-global so a space created after a PUT starts on it, and clearing it REVERTS rather than freezes.
  The **memory-limit grammar is served, not mirrored**: `SchedulerRoutes.MEMORY_LIMIT_PATTERN` is published
  as `duckdbMemoryLimitPattern` and the UI holds no regex of its own (it had two hand-maintained copies —
  identical when measured, unpinned). ⚠ One string can only serve both sides if it is **portable**: no
  inline `(?i)` (the browser compiles it with `new RegExp(p, 'i')`, and JS has no such construct) and
  **explicitly anchored** (`String.matches` anchors implicitly, `RegExp.test` does not — unanchored, the
  form accepts `2GB of RAM`). **DuckDB's proportional form is accepted** (`80%`), bounded `1..100`
  *inside* the pattern, because a bound that does not travel with the served grammar lets the form accept
  a `500%` the PUT refuses. The value is a pass-through to `SET memory_limit='…'` — nothing parses it into
  bytes, so a non-byte value breaks no arithmetic. 🔴 The offline mock had **no resource-pair gate at all**
  (it accepted `"lots"` where the server 422s and served neither value) — a divergence worth remembering as a
  failure class, though the mock itself was deleted 2026-08-31, so the server's gate is now the only gate.
* **Defaults DECIDED 2026-07-25 (BACKLOG D11 + D12).** D12 shipped that day; **D11 was declined that day**
  and stayed unimplemented until 2026-08-26 (history below).
  * **D12 — chunking is ON by default** (SHIPPED): `processing.chunking.max_file_bytes` now defaults to
    **8 GiB** (`8589934592`) instead of `0`. The threshold exists for *pathological* single files, so it was
    set far above any routine input — normal workloads never change shape, and only a genuinely outsized file
    chunks. Setting the key to `0` still disables chunking.
  * **D11 — NOT implemented.** There is still **no default `processing.duckdb.memory_limit`**: an uncapped run
    gets DuckDB's own ≈80%-of-RAM-*per-instance* default, and concurrent runs can still overcommit the box.
    The decision, if it is ever revisited, was for a conservative fixed per-instance cap + spill, **not** a cap
    computed from the concurrency semaphores — a computed cap (RAM ÷ `jobs.maxConcurrentRuns`) was rejected
    because `maxConcurrentRuns` defaults to `0` (unbounded), so the divisor is routinely unknown, and the
    batch-ingest path has its own semaphore; two independent limiters mean any "compute it" formula is wrong in
    exactly the overcommit case it was meant to prevent. Until then, set `-Dprocessing.duckdb.memory_limit`
    explicitly on high-concurrency boxes. `processing.duckdb.max_temp_directory_size` likewise has no default
    (DuckDB uses ≈90% of the disk), though spill already lands on the data volume via the batch scratch dir
    (`ConsignmentIngestStrategy.scratchDir` → `dirs.temp`). Read-path connection reuse is explicitly **not** the
    lever here (see BACKLOG §6 C6).
  * **D11 — MEASURED 2026-07-27. The number is `2GB`, and two long-standing beliefs here are wrong.**
    Measured on a 32 GiB host, DuckDB 1.5.2.1, over a CDR-shaped 12-column CSV. What the numbers say:
    - ⚠ **Peak memory does NOT scale with input size on the ingest path.** `read_csv_auto` →
      `COPY … TO parquet` streams: a **1.0 GiB** input peaked at **1081 MiB**, a **3.1 GiB** input at
      **981 MiB** — flat. So a giant file is *not* what exhausts memory, and **chunking (D12) was never
      really the memory bound** it is described as above; it bounds the unit of work and scratch, which is
      still worth having, but it is not standing in for D11.
    - ⚠ **What a cap actually governs is the blocking operators, and they hard-fail instead of spilling.**
      A 9M-group `GROUP BY` peaked at 937 MiB, a wide `DISTINCT` at 895 MiB, and **at `512MB` both died
      with `Out of Memory Error`** having spilled only ~192 MiB. Graceful degradation is not the failure
      mode. **This is the trap: an aggressive cap turns working jobs into failing ones.** (`ORDER BY`
      ~200 MiB and a self-`JOIN` ~385 MiB are cheap and never the constraint.)
    - **`2GB` is the defensible value**: ~2.2× the highest peak observed anywhere (1081 MiB), clear of the
      OOM cliff, and free — capped runs measured at or slightly *faster* than uncapped (ingest 3741 ms
      @ `2GB` vs 4928 ms uncapped; `GROUP BY` 1711 vs 2123 ms). `1GB` passed everything too but sits only
      ~1.3× over peak, uncomfortably near the cliff.
    - ⚠ **A `memory_limit` default alone does not close D11.** Total exposure = `memory_limit` ×
      concurrent runs, and `-Djobs.maxConcurrentRuns` still defaults to `0` = unbounded — so at enough
      concurrency *any* fixed per-instance cap overcommits. The pair is the fix: `memory_limit=2GB` +
      `maxConcurrentRuns=4` ⇒ ≤8 GiB worst case (~25% of a 32 GiB box) vs ~25 GiB *per run* today. Note
      this does **not** resurrect the rejected *computed* cap (RAM ÷ semaphore) — it is two independent
      fixed knobs, which is exactly what the D11 decision asked for.
    - **Method, to reproduce:** open a plain JDBC DuckDB connection, `SET temp_directory` (as
      `DuckDbUtil.applyDuckDbSettings` does) and optionally `SET memory_limit`; poll
      `SELECT sum(memory_usage_bytes), sum(temporary_storage_bytes) FROM duckdb_memory()` on a duplicated
      connection every 15 ms while the statement runs, and take the max. Sweep the cap over
      `{default, 8GB, 4GB, 2GB, 1GB, 512MB, 256MB, 128MB}` for the ingest shape and
      `{default, 4GB, 2GB, 1GB, 512MB}` for the blocking-operator shapes.
    - **Not measured, so not claimed:** scaling with thread/core count (DuckDB sizes per-thread buffers, so
      a much larger box may need more than 2 GiB), non-CSV frontends, the `materialize` task's real query
      shapes, and any RAM-relative or per-edition default.
    - **Still an operator call** — the measurement removes the blocker (BACKLOG §6); it does not ship a
      default.
* **Reserved-word quoting.** `day` is a DuckDB keyword — alias it (`run_day`) in SQL; quote `"trigger"` too.
  Watch this whenever generating SQL with date/trigger columns. See [gotchas](../../layer/platform/admin-guide-gotchas.md).
* ⚠ **The session `TimeZone` is the HOST zone, not UTC** — probed 2026-08-15 against the bundled
  `org.duckdb:duckdb_jdbc:1.5.2.1` on a plain `jdbc:duckdb:` connection:
  `current_setting('TimeZone')` returned the host zone (`Asia/Calcutta` on this box) and
  `duckdb_extensions()` reported **icu installed: true, loaded: true**. The widespread "DuckDB defaults to
  UTC" belief holds only for an **ICU-less** build, and this is not one. Consequently a SQL-side
  `now()::TIMESTAMP` writer and a Java-side `LocalDateTime.ofInstant(…, ZoneId.systemDefault())` reader are
  **the same wall clock** and agree — e.g. `ConsignmentIngestStrategy`'s `__valid_from` and
  `ReferenceCompactor`'s retention cutoff are a genuine matched pair, *not* the off-by-the-UTC-offset
  bug they resemble. ⛔ "Fixing" that reader to UTC would **create** the skew and drop rows outside `keep`.
  ⚠ Note the asymmetry: DuckDB follows `systemDefault()` but is **blind to `-Dops.timezone`** — nothing in
  the repo issues `SET TimeZone` and `DuckDbUtil` has no setter — so moving such a pair onto
  [`OperationsZone`](../processor/spec-jobs.md) means changing the connection's zone as a **third** moving
  part, not just the two Java halves.

## Changes

- 2026-11-21: pinned DuckDB 2.0.1.0 LTS — similarity built-ins count code points, `round(DOUBLE, n)` rounded via DECIMAL, Havana tzdata exempt, `window_self_join` still broken.
- 2026-09-09: the D11 headline corrected — it read “the pair is `memory_limit=2GB` + `maxConcurrentRuns=4`”, so a skimmer took away a shipped 2GB cap.
