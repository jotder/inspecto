---
type: Reference
title: Engine seams and performance
description: The durable engine seams: the single ingestion SPI, union versus generation mode, and their performance levers.
tags: [engine, ingestion, performance, seams]
timestamp: 2026-11-19T00:00:00Z
---

# Engine seams and performance

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- **Single ingestion SPI:** `StreamingFileIngester` (emit-based) is the **only** ingestion SPI. Per-batch the
  framework picks **union** mode (many small files → per-member views `UNION ALL` → one transform/write pass) vs
  **generation** mode (one huge file → bounded flushing). Selector `processing.streaming.large_file_bytes`
  (default 256 MB); generation budget `processing.streaming.flush_records` (default 5,000,000).
- **DuckDB `Appender` ingest** (vs JDBC `executeBatch`) ≈ **75× faster** (1M-row bench ~6.9k → ~510k rows/s).
- **Modularity seams** (behavior-preserving; SQL/`.toon`/on-disk output unchanged): `OutputFormat`
  (enum-as-strategy), `TransformCompiler` (`transformType → ColumnRule`), `ConsignmentIngestStrategy` (Csv/Plugin →
  typed `IngestOutcome`; `ConsignmentIngestor` is a thin coordinator).
- **Auto-derive `duckdb_threads`** — `DuckDbUtil.effectiveWorkerThreads`: `0`=auto `max(1,cores/concurrency)`,
  `>0`=verbatim, `-1`=DuckDB per-core default; single-batch→all cores. Avoids the threads×cores oversubscription
  stall (~+15% tax, widens with cores).
- **Quarantine semantics:** throw → `QUARANTINED_UNREADABLE`; 0 emitted rows → `QUARANTINED_MISMATCH`;
  `SinkFlushException` → fail the batch.
- **Output files: the JSON manifest is authoritative for EXISTENCE, `consignment_outputs` only for STATE.**
  `PartitionOutput(partition, outputFile, bytes)` is an *ephemeral* return value — produced by
  `PartitionWriter.reveal()`, consumed once, discarded — in **three** paths: ingest, `EnrichmentEngine`, and
  `PartitionSinkWriter`. (`DecisionRuleApplier` is *not* a fourth: its `RouteSink` already calls
  `LineageCollector`, and `ConsignmentIngestStrategy.writeAndTrace` seeds its accumulators from `applied.outputs()`, so
  routed-rule outputs reach the ingest hook for free. The hook is `ConsignmentIngestor.finalizeSource`, once per
  Consignment — *not* `writeAndTrace`, which has four callers and is invoked **per segment** in union mode
  and **per chunk** in chunked mode. ⚠ Since 2026-08-29 that multiplicity is load-bearing: those callers pass a
  **write scope** so the batch's shared branch-commit ledger keeps their sinks distinct — without it the second
  and later writes read as "already committed" and their rows vanish. See
  [`okf/product/module/elt/spec-ingest.md`](../elt/spec-ingest.md) §"The lane fork".)
  The durable registry (`DbConsignmentOutputStore`, plan §11.3) is **default-off** and `ServiceStores` degrades a
  failed open to `null`, so **never read a missing registry row as proof a file does not exist** — `ConsignmentManifest`/
  `ManifestStore` stays the artifact of record. Note also that no per-file row count exists at write time (a
  multi-file partitioned `COPY` reports none): ingest sums `LineageCollector`'s per-`(srcId, partition)` counts,
  while enrichment and sinks use `ConsignmentOutputs.countByPartition` (needs no `__src_id`).
  → [`db-layer.md`](spec-db-layer.md) §3.9.
- **Platform Services — the one way a Job reaches an engine facility** (2026-08-09, plan Stage 1 S1-1…S1-7).
  `PlatformServiceRegistry` is built at boot in `CollectorService` and **must be populated before
  `JobService` is constructed** (its constructor registers built-ins and scans packs, and registration
  validates `requires:` fail-closed). A Job Type declares `requires: [<id>]` on its `JobTypeDescriptor`;
  `JobService.runJob` grants exactly that set into `JobContext.services()`, so an **undeclared service stays
  invisible even when it exists** (that honesty is what makes the declaration a security statement, not a
  label). Ids: `notifications`, `incidents`, `schema`, `consignment-status`, `alerts`. Mutating services are
  substituted by `DryRunServices` under a dry run (log the would-be effect, act on nothing) — **every new
  mutating service must be added there**, or `dryRun()` becomes a lie the moment a Job calls it. Two rules
  worth keeping: a **built-in** may declare a grant even when no registry is wired (lean/embedded
  `JobService`, e.g. an engine unit test — the service ships in the same build), while packs and classpath
  providers are always strict; and a grant is only worth declaring if the code **looks it up** — the reach
  `AlertService` has via its own `IncidentAccess` was never `alert.evaluate`'s grant to claim (D7), which
  is why that Job waited for an `alerts` service (shipped 2026-08-10) instead of taking a decorative one.
- **A service whose call cannot be previewed still needs the dry-run treatment — a stand-in is not enough.**
  `alerts` evaluation *is* the action (a breach fires an Alert, advances a cooldown, may open an Incident),
  so `DryRunServices` returns empty AND the consuming Job reports "nothing was evaluated". Returning empty
  alone would let a caller print "no rule breached", which is a worse lie than the one MNT-1 forbids.
  `alert.evaluate` shipped with exactly that bug for three days: it ignored `dryRun()` entirely, so a
  preview fire really evaluated. **When you grant a Job a mutating service, check the Job honours the flag
  too** — the substitution protects the store, not the Job's own reporting.
- **A boot lambda must not capture a `final` field that is assigned later in the same constructor** — it is a
  `variable might not have been initialized` compile error, not a runtime NPE, and it will greet anyone
  registering a service beside the `notifications`/`incidents` block in `CollectorService`. Bind through the
  existing accessor instead (`n -> notificationService().notify(n)`, `IncidentAccess.over(this::objects)`),
  which also gets the live per-space value rather than a boot-time snapshot.
- **An `INCIDENT` does not start `OPEN` and `resolve` is fenced.** Its workflow is
  `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED` with **only `ARCHIVED` terminal**, and `resolve` throws
  `IllegalState … missing: timeline, cause analysis, corrective actions, SLA` until the postmortem is
  complete (`ObjectService.java:1292`). A test that wants a *terminal* Incident should `archive` (legal
  straight from `IDENTIFIED`), not `resolve`. `ALERT` is the one that starts `OPEN` with `RESOLVED` terminal.
- **`com.gamma.util` CLI cluster** (~11 `main()` tools: `MainApp`, `TarExtractor`, …) sat at low coverage; it was
  **deleted 2026-11-06** with the `ura` CLI (only `MainApp migrate-configs` remains). Tested engine+control-plane
  is ~86%. → [`performance.md`](../../layer/platform/tests-performance.md).
