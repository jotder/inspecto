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
* **Defaults DECIDED 2026-07-25 (BACKLOG D11 + D12) — still to be implemented.** Both knobs above become
  **on by default** instead of opt-in, because the current unset behavior (≈80% RAM *per instance*) means
  concurrent runs overcommit and can take the whole box down, including the HTTP API.
  * **D11 — a conservative fixed per-instance cap + spill, not a cap computed from the concurrency
    semaphores.** A computed cap (RAM ÷ `jobs.maxConcurrentRuns`) was rejected: `maxConcurrentRuns` defaults
    to `0` (unbounded), so the divisor is routinely unknown, and the batch-ingest path has its own semaphore —
    two independent limiters mean any "compute it" formula is wrong in exactly the overcommit case it was
    meant to prevent. A fixed conservative value plus `temp_directory` spill degrades to slow-but-correct
    rather than failing, which is the right trade for a default. Operators who know their box can still raise
    it; the computed approach stays available to them as arithmetic they do once, not logic we guess at.
  * **D12 — turn chunking on with a large threshold** (`processing.chunking.max_file_bytes`, `0`/disabled
    today). The cap exists for *pathological* single files, so the default must be high enough that normal
    workloads never change shape and only a genuinely outsized file chunks. This is a second, independent
    safety net: D11 bounds memory per connection, D12 bounds what a single file can ask for in the first
    place.
  * Sequencing note: D11 lands first — it is the actual box-protecting change, and D12's threshold is easier
    to pick once a memory cap makes the failure mode "spill" rather than "OOM". Read-path connection reuse is
    explicitly **not** the lever here (see BACKLOG §6 C6).
* **Reserved-word quoting.** `day` is a DuckDB keyword — alias it (`run_day`) in SQL; quote `"trigger"` too.
  Watch this whenever generating SQL with date/trigger columns. See [gotchas](../gotchas/cross-cutting.md).

Output is written via DuckDB `COPY` — see [output & sinks](output-sinks.md).
