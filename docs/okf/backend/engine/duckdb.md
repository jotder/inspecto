---
type: Concept
title: DuckDB Integration
description: Appender-based bulk ingest (~75× vs JDBC), thread auto-derivation, and reserved-word quoting.
resource: inspecto-engine/src/main/java/com/gamma/inspector/DuckDbRecordSink.java
tags: [engine, duckdb, performance, appender, threads]
timestamp: 2026-06-28T00:00:00Z
---

# DuckDB Integration

The engine embeds DuckDB natively (requires the `--enable-native-access=ALL-UNNAMED` JVM flag — see
[build & run](../build-run/build-test.md)).

* **Appender, not JDBC batch.** `DuckDbRecordSink` and `TypedRecordIngester` bulk-load via the DuckDB
  `DuckDBAppender` API (heap buffer `APPEND_BATCH = 10,000` rows). Benchmarked on 1M rows: JDBC
  `PreparedStatement.executeBatch` ≈ 6.9K rows/s vs the Appender ≈ 520–530K rows/s — **~75× faster**, at
  parity with the native CSV reader.
* **Thread auto-derivation.** `DuckDbUtil.effectiveWorkerThreads` (`inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java`)
  derives per-batch `duckdb_threads`: `0` (default) with batch concurrency > 1 → `max(1, cores/concurrency)`;
  explicit `N` honored verbatim; `-1` → DuckDB's per-core default. Avoids the threads×cores oversubscription
  stall; `ConfigValidator` warns when explicit `threads × duckdb_threads` exceeds the core count.
* **Memory / spill caps (opt-in; one knob for every scratch connection).** `DuckDbUtil.applyDuckDbSettings`
  sets `memory_limit` / `temp_directory` (spill) / `max_temp_directory_size` when a value is configured;
  unset ⇒ DuckDB's own default (≈ 80% RAM **per instance** — the aggregate-overcommit hazard under
  concurrency). The batch-ingest path caps its connections via `BatchIngestStrategy.configure`
  (per-pipeline `processing.duckdb.*`). The **flow-job** (`PipelineJobRunner`) and **enrichment**
  (`EnrichmentEngine`) run scratch connections have no per-config `processing.duckdb` section, so they call
  `DuckDbUtil.applyGlobalDuckDbSettings`, which reads the global JVM fallbacks
  `-Dprocessing.duckdb.memory_limit` / `.temp_directory` / `.max_temp_directory_size` / `.threads`; the
  batch path honors the same globals as a fallback (`DuckDbUtil.globalOr`), so a single
  `-Dprocessing.duckdb.memory_limit` caps every DuckDB scratch connection uniformly. **All opt-in** — with
  no config or `-D` value set nothing is issued and behavior is unchanged. Set these on high-concurrency /
  multi-tenant boxes to prevent overcommit, and pair with `temp_directory` so an over-limit query spills to
  disk instead of OOM-ing. (Preview / dry-run connections — `ComponentPreview`, `PipelineDryRun`, enrichment
  `preview` — run over bounded samples and are deliberately left uncapped.)
* **D11 SHIPPED 2026-08-26 — the pair is `memory_limit=2GB` + `maxConcurrentRuns=4`, both on by default and
  both owned by the server configuration.** They are surfaced in the UI at **Settings ▸ Scheduler ▸ Resource
  caps**, persisted in `scheduler.toon`, and served with provenance (`file` | `property` | `default`) by
  `GET/PUT /system/scheduler`. Ownership moved deliberately: `DuckDbUtil.memoryLimit(configured)` is now the
  single use-time resolver — per-pipeline value > installed server value > `-Dprocessing.duckdb.memory_limit`
  (a bootstrap default only) > DuckDB's own default — because ⛔ a key served by the settings tier must not
  also be read from `-D` at use time (`SchedulerRoutes`, the 2026-08-15 operational-db decision).
  `JobService.setMaxConcurrentRuns` is the matching seam. ⚠ Preview / dry-run connections remain
  **uncapped** and must stay so: only `EnrichmentEngine`, `PipelineJobRunner` (via
  `applyGlobalDuckDbSettings`) and `BatchIngestStrategy` (per-config path) resolve a limit.
  `max_temp_directory_size` still gets **no** default — none is defensible without the volume size.
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
    (`BatchIngestStrategy.scratchDir` → `dirs.temp`). Read-path connection reuse is explicitly **not** the
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
  Watch this whenever generating SQL with date/trigger columns. See [gotchas](../gotchas/cross-cutting.md).
* ⚠ **The session `TimeZone` is the HOST zone, not UTC** — probed 2026-08-15 against the bundled
  `org.duckdb:duckdb_jdbc:1.5.2.1` on a plain `jdbc:duckdb:` connection:
  `current_setting('TimeZone')` returned the host zone (`Asia/Calcutta` on this box) and
  `duckdb_extensions()` reported **icu installed: true, loaded: true**. The widespread "DuckDB defaults to
  UTC" belief holds only for an **ICU-less** build, and this is not one. Consequently a SQL-side
  `now()::TIMESTAMP` writer and a Java-side `LocalDateTime.ofInstant(…, ZoneId.systemDefault())` reader are
  **the same wall clock** and agree — e.g. `BatchIngestStrategy:215`'s `__valid_from` and
  `ReferenceCompactor:142`'s retention cutoff are a genuine matched pair, *not* the off-by-the-UTC-offset
  bug they resemble. ⛔ "Fixing" that reader to UTC would **create** the skew and drop rows outside `keep`.
  ⚠ Note the asymmetry: DuckDB follows `systemDefault()` but is **blind to `-Dops.timezone`** — nothing in
  the repo issues `SET TimeZone` and `DuckDbUtil` has no setter — so moving such a pair onto
  [`OperationsZone`](../control-plane/jobs.md) means changing the connection's zone as a **third** moving
  part, not just the two Java halves.

Output is written via DuckDB `COPY` — see [output & sinks](output-sinks.md).
