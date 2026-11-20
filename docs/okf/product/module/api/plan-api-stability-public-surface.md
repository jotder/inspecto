---
type: Reference
title: API stability policy — The public surface and what is internal
description: Part 3 of 3 of API stability policy: The public surface (as it stands for the pending 4.0.0; unchanged since 2.0.0 except where noted); Explicitly internal (do not depend on); What is *not* covered by code-level stability — but is still stable; Why no module-info.java
tags: [api, stability, publicapi, semver, release]
timestamp: 2026-07-16T00:00:00Z
item: module.api
audience: internal
status: plan
verified: 2026-11-20 code-reviewed
code: platform/inspecto-api
---

# API stability policy — The public surface and what is internal

Part 3 of 3 of [API stability policy](plan-api-stability.md) · previous: [Release notes — breaking configuration and CLI changes onward](plan-api-stability-breaking-config.md).

## The public surface (as it stands for the pending 4.0.0; unchanged since 2.0.0 except where noted)

Two audiences depend on the framework from outside:

**Plugin authors** (implementing a custom ingester):

| Type | Since | Role |
|---|---|---|
| `com.gamma.etl.StreamingFileIngester` | 3.10.0 | **The** plugin ingester SPI — emit records into a sink; the framework owns tables/transform/write/lineage and picks union vs generation mode by file size. (Sole SPI since 3.11.0.) |
| `com.gamma.etl.RecordSink` | 3.10.0 | Framework-provided callback a `StreamingFileIngester` writes records into (`define`/`emit`/`reject`/`junk`) |
| `com.gamma.etl.IngestResult` | 1.0.0 | Row counts (CSV path); plugin counts flow through `RecordSink` |
| `com.gamma.etl.PipelineConfig` (+ nested records `Identity`, `Dirs`, `Processing`, `CsvSettings`, `Output`, `Schemas`, `DuckDbSettings`, `Chunking`) | 1.0.0 / records 2.0.0 (`DuckDbSettings`/`Chunking` 3.10.0) | Passed to `ingest(...)`; read for paths, settings, `ingesterConfig`; `Processing` adds `largeFileBytes`/`flushRecords` streaming controls (3.11.0) |

> **Removed in 3.11.0 (breaking):** the whole-file `com.gamma.etl.FileIngester` and its nested `Segment` (both since 1.3.0). The plugin SPI is unified on `StreamingFileIngester`; the framework now runs the same ingester in *union mode* (many small files → one transform/write) or *generation mode* (huge single files → bounded scratch), chosen per batch by `processing.streaming.large_file_bytes`. Port `FileIngester` plugins to `StreamingFileIngester` (see [plugins.md](../elt/spec-ingest.md)). This is a **deliberate exception** to the within-major-version stability promise above, made to consolidate the plugin SPI before it had wide external adoption.

> **Pending major 4.0.0 — NOT YET RELEASED (the agent-module reshape):** the **core ETL `@PublicApi` surface above is unchanged** — Stage-1/Stage-2/control/embedder types and signatures carry over from 3.x untouched. The bump, when 4.0.0 ships, is driven by two things: (1) a **runtime-floor bump for the agent modules**, and (2) the optional `inspecto-agent` module was reshaped onto shared orchestrator primitives (`SyncOrchestrator` / `Capability` / confidence-escalation / `AuditSink`), and `com.gamma.assist.AssistResult.confidence` changed from a `String` to a numeric `double`. Only code driving the assist module's result type was affected.
>
> **Current Java floor (moved 2026-09-17):** the core compiles at `maven.compiler.release=27` and is built and bundled on JDK 27. The **agent modules require a JDK 27 runtime** (their model-transport jars are class-file v69), which a 27 floor clears by construction — so the two-floor split this paragraph documented from 2026-07-07 (core 27 / agent 27+, reconciled only by the bundled runtime) no longer exists. **agent-kernel itself was replaced 2026-07-07**: its reasoning layer is vendored into `inspecto-agent` (`com.gamma.agent.kernel.*`) and the model transport is **eoiagent** (`com.eoiagent:eoiagent-core|-model`) — the assist SPI (`com.gamma.assist.spi.AssistAgent`) is unchanged. The Standard edition adds three control-plane SPIs — `com.gamma.spi.auth.Authenticator` / `Subject` / `TokenRelay` (implemented by the `inspecto-oidc` module) — which follow the same stability policy.

**Embedders** (driving the ETL from Java instead of the CLI):

| Type | Since | Role |
|---|---|---|
| `com.gamma.inspector.CollectorProcessor` | 1.0.0 | Run one source (`run(cfg)` / `main`) |
| `com.gamma.inspector.MultiCollectorProcessor` | 1.6.0 | Run many sources concurrently (`runAll` / `main`) |
| `com.gamma.etl.PipelineConfig.load(String)` | 1.0.0 | Parse a pipeline `.toon` into a config |

**Service & control line** (long-running host, Stage-2 enrichment, REST control plane):

| Type | Since | Role |
|---|---|---|
| `com.gamma.service.CollectorService` | 2.2.0 | Always-on host: registry, poll schedule, event bus, control surface (`fromArgs`, `runAllOnce`, `runPipeline`, `pause`/`resume`, `pipelines`, `statusStore`) |
| `com.gamma.service.EnrichmentService` | 2.3.0 | Orchestrates Stage-2 enrichment against batch-commit events + schedules; exposes the run-audit read surface (`configs`, `views`, `runs`, `lineage`) backing `GET /enrichment[...]` (v2.9.0) |
| `com.gamma.enrich.EnrichmentAuditReader` | 4.0.0 (the type since 2.9.0; unmarked in `v3.11.0`, marked by the M3 freeze below) | Read side of the Stage-2 audit — reads back the `<job>_enrich_runs.csv` / `_enrich_lineage.csv` ledgers as JSON-ready rows |
| `com.gamma.control.ControlApi` | 2.4.0 | Embedded REST control plane over a running `CollectorService`. History: v3.0 added scoped token auth; the **editions realignment (2026-06-16) removed token auth from the core** — the common core is auth-free (Personal), and Standard re-adds OIDC via the `Authenticator`/`Subject`/`TokenRelay` SPIs. 2026-07-06: versioned `/api/v1` envelope (W1); 2026-07-25: the unversioned business surface retired (API-5). *(This cell said "v4.8 … alongside byte-for-byte legacy routes" until 2026-09-08 — no v4.8 ever existed and the legacy routes are gone.)* |
| `com.gamma.assist.spi.AssistAgent` | 3.0.0 | SPI for the optional embedded assist agent; discovered via `ServiceLoader` (or `CollectorService.registerAgent`) and wired in-process before `start()`. Implemented in the optional `inspecto-agent` module (M0) |
| `com.gamma.service.DbStatusStore` | 2.6.0 | Database-backed `StatusStore` (engine-neutral JDBC; DuckDB by default — zero extra dep; Postgres for a future distributed deployment, bring-your-own driver) — selected via `-Dstatus.backend=db` |
| `com.gamma.report.ReportService` | 2.8.0 | Rolls `StatusStore` audit into a live status snapshot + a historical batch-audit report (backs `GET /status`, `/report`, `/pipelines/{name}/report`); rolls the Stage-2 run audit into `enrichmentReport` (backs `GET /enrichment/{job}/report`, v2.9.0); reports accept a `Window` date range + report duration percentiles p50/p95/p99 (v2.10.0) |
| `com.gamma.job.JobService` | 2.8.0 | Registry + scheduler for config-driven jobs (cron / event / manual) hosting ingest, enrichment, report and maintenance work uniformly (backs `GET /jobs`, `/jobs/{name}/runs`, `POST /jobs/{name}/trigger`) |
| `com.gamma.job.JobConfig` | 4.0.0 (the type since 2.8.0; unmarked in `v3.11.0`, marked by the M3 freeze below) | A `*_job.toon` definition: name, `type`, `cron`, `on_pipeline`, `enabled`, type-specific params |

### Agent-consumed core surface (frozen for the pending 4.0.0, M3)

The optional `inspecto-agent` (and `-intelligence`) modules are an L5 consumer that, per
[architecture-platform.md](../../layer/platform/architecture-platform.md), must touch core **only through SPI interfaces +
`@PublicApi` types**. The read-only surface they actually consume — captured once in
`UccAgentContext` and reached by the capabilities/skills — is now `@PublicApi`-frozen `since = "4.0.0"`
so this contract can't drift silently. It is deliberately a *freeze*, not a wrapping facade: a parallel
`agent.spi` DTO layer was rejected because the modularization is reactor-internal (one deployable — no
build against an API jar), so wrapping would duplicate data shapes for no payoff. The frozen surface:

| Package | Types |
|---|---|
| `com.gamma.catalog` (+`.spi`) | `ConfigSource`, `Description`, `IdScheme`, `MetadataGraph`, `MetadataGraphService`, `MetadataNode`, `NodeKind`, `OperationalOverlay`, `Provenance`, `DescriptionProvider` |
| `com.gamma.config` (`.io`/`.safety`/`.spec`) | `ConfigCodec`, `ConfigLoader`, `ConfigSafetyValidator`, `SafetyPolicy`, `ConfigSpec`, `ConfigSpecs`, `FieldSpec`, `Finding`, `RawConfig`, `Severity` |
| `com.gamma.sql` | `SqlOracle`, `SqlSandboxPolicy` |
| `com.gamma.signal` | `Ref`, `Severity`, `Signal` |
| `com.gamma.util` | `CronExpression` (relocated from `com.gamma.service`, WS-D §1.7) |
| `com.gamma.service` | (`CollectorService` already public) |
| `com.gamma.etl` / `com.gamma.enrich` / `com.gamma.job` / `com.gamma.event` / `com.gamma.report` | `ConsignmentEvent`, `StatusStore` (StatusStore relocated from `com.gamma.service`, WS-D §1.7); `EnrichmentAuditReader`, `EnrichmentConfig`; `JobConfig`; `EventLog`; `ReportService.Window` |

## Explicitly internal (do not depend on)

`CsvIngester`, `DuckDbCsvIngester`, `DataTransformer`, `TransformCompiler`,
`PartitionWriter`, `OutputFormat`, `LineageCollector`, `SchemaSelector`,
`ConsignmentIngestor`, `ConsignmentPlanner`, `MarkerManager`, `QuarantineManager`,
`DuckLakeRegistrar`, `CommitLog`, `ConsignmentAuditWriter`, the `com.gamma.util.*` CLI
tools, and all of
`ManifestStore`/`Consignment`/`PartitionDef`/`PartitionOutput`/`LineageRow` are
implementation detail. (The `com.gamma.inspector` batch-ingest strategy seam —
`ConsignmentIngestStrategy`, `CsvIngestStrategy`, `StreamingPluginIngestStrategy`, `DuckDbRecordSink`,
`IngestOutcome`, `MemberAudit`, all package-private — is likewise internal.) They are stable enough for the framework's own use but
carry no cross-version guarantee. Plugin authors interact with them only
indirectly (e.g. you emit records that `DataTransformer` later reads —
that contract is documented on `StreamingFileIngester`/`RecordSink`, not on `DataTransformer`).

## What is *not* covered by code-level stability — but is still stable

- **HTTP Control API contract** (`docs/api/openapi-v1.json`) — additive-only within `/api/v1` (rules in
  [`docs/api/README.md`](../../../../api/README.md)); every public operation has a real summary and description
  (`tools/check-openapi-summaries.mjs`).
- **Pipeline `.toon` config format** — treated as a stable user-facing contract;
  changes are additive and documented in [configuration.md](../config/admin-guide-configuration-reference.md).
- **On-disk output** (Hive-partitioned Parquet/CSV layout, audit CSVs, commit log
  format) — forward-compatible; a format change would be called out as breaking.

These carry forward across the 1.x → 2.0 boundary unchanged; 2.0 broke only the
**Java embedding API** (the `PipelineConfig` field → nested-record move).

## Why no `module-info.java`

A JPMS module descriptor exporting only the public packages was considered and
**declined**: the project ships one thin jar per first-party module beside a shaded `inspecto.jar`
(the processor) that bundles several automatic-module dependencies (DuckDB JDBC, univocity, JToon, Jackson, SLF4J,
commons-*, gson, opencsv). Layering JPMS over that combination is high-risk
(split packages, automatic-module naming, shade interactions) for little gain
over the annotation + this policy. The `@PublicApi` marker is the lighter,
sufficient mechanism. Revisit only if the project ever stops shading.

## Changes

- `BatchPlanner` renamed to `ConsignmentPlanner`, and `Batch` to `Consignment`.
