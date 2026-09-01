---
type: Concept
title: Job vs Pipeline Step — capability boundary
description: The full capability comparison between a Job (at-rest Executable, Java, open registry) and a Pipeline Step (in-motion graph node, compiled to SQL; Step-kind registry opening per D0-B), with the binding boundary rule and the traps that blur it.
resource: inspecto-engine/src/main/java/com/gamma/job/JobContext.java
tags: [control-plane, jobs, pipeline-graph, steps, executables, boundary, plugins]
timestamp: 2026-08-09T00:00:00Z
---

# Job vs Pipeline Step — capability boundary

Both do work on data, so they invite the question "which one should this be?". The answer is not a
matter of taste: [`GLOSSARY.md`](../../../GLOSSARY.md) §5 and
[`pipeline-graph-design.md`](../pipeline-graph/pipeline-graph-design.md) §3.8 make **in-motion
(Pipeline) vs at-rest (Job)** a *binding* line. This page carries the full capability comparison behind
that rule, verified against source 2026-08-07; extensibility and naming re-grounded 2026-09-01.

## 0. The one-line difference

**A Step is not an *Executable*** — the Pipeline is; a Step is a node inside it. A Job is imperative
Java — you implement `Job.run(ctx)` and ship a class. A Step is *today* declarative config lowered into
DuckDB SQL by `RowShaper`/`TransformCompiler` and executed by `ConsignmentGraphRunner`. In the committed
vocabulary ([`pipeline-spec.md`](../../../superpower/pipeline-spec.md) §13 D2) a Step receives a
**Consignment token** and resolves the data by reference — edges carry no records; the full runtime edge
model converges at Phase 7. Since 2026-08-29 a plugin *can* supply a Step's execution
(`PipelineNodeExecutor`, §3) — that changes who writes the SQL-shaping code, not what a Step **is**, and
it still gets no service façade. [Platform Services](platform-services.md) stage 2 (still unbuilt) is
the different, richer thing: a pack-hosted Step *program* (`EXECUTED` mode) — again without changing what
a Step **is**: a node producing consignment counters inside someone else's Run, never an Executable.

## 1. What each one is

| | Pipeline Step | Job |
|---|---|---|
| Internal type | `PipelineNode`, discriminated by the type registry — `BuiltinNodeType` (29 ids) layered under `PipelineNodeType` providers | `Job` + `JobTypeProvider` |
| Executed by | `ConsignmentGraphRunner` / `ConsignmentIngestor`, inside `CollectorProcessor`'s poll cycle | `JobService.runJob` on a virtual thread |
| Is it an **Executable**? | **No** — the *Pipeline* is the Executable; a Step is a node inside it | **Yes** — the Scheduler starts it and it produces a Run |
| Authored as | node entries in the pipeline TOON | `<name>_job.toon` |

## 2. What they genuinely share

Real symmetry, not coincidence — several of these were built to the same shape deliberately.

| Capability | Step | Job |
|---|---|---|
| **Declared config contract** | `NodeAttribute` — `key`, `label`, `type`, `tier`, `required`, `defaultValue`, `options`, `min`/`max`, `help`, `placeholder`, `dependsOn` | `ParameterDecl` — the same (minus `dependsOn`), plus `pattern`, `group`, `multi`, `secret`, `deduce`, `expressions` |
| **Server-published for the UI** | `GET /pipelines/node-types` | `GET /jobs/types[/{id}]` |
| **Overlap guard** | `PipelineRunGuard` — binary semaphore per pipeline id; poll `tryAcquire` (skip), operator `acquire` (block) | `LockingRunner.runExclusiveOrSkip` per job name; a fire during a Run records `SKIPPED` |
| **Concurrency cap** | back-pressure skips (`inspecto_acquire_backpressure_skips_total`) | `-Djobs.maxConcurrentRuns` semaphore |
| **Dry run** | `PipelineDryRun` (T18) — bounded sample through a scratch DuckDB, commits nothing | `JobContext.dryRun()` — a destructive Job Type previews instead of acting; `POST /jobs/{name}/trigger?dryRun=true` |
| **Prometheus metrics** | `inspecto_batches_total`, `inspecto_batch_duration_seconds`, `inspecto_output_rows_total`, … | `inspecto_jobs_total`, `inspecto_job_duration_seconds` |
| **Durable audit** | `ConsignmentAuditWriter` — `status` (per file), `batches`, `lineage` (ledger file names keep the pre-rename spelling — BACKLOG §4 residual) | `JobRunLedger` (`jobs_runs.csv`) + optional DuckDB projection |
| **Repeat safety** | `.processed` markers + the acquisition dedup ledger | `TriggerCoalescer`, signal chain-depth cut, `catch_up` |
| **UI surface** | `/pipelines` | `/jobs`, `/jobs/:name` |
| **Timeout / cancellation** | ⛔ none | ⛔ none — a `CronHandle.cancel()` only un-arms *future* fires |

⚠ The two dry-runs share a word, not a mechanism: the Step one previews a *candidate config* over sample
rows; the Job one makes a *real, destructive* action non-destructive. Do not describe them as one feature.

## 3. Where they differ

| Dimension | Pipeline Step | Job |
|---|---|---|
| **Data state** | **In motion** — the Step's Consignment token references rows in flight | **At rest** — data already landed |
| **Implementation** | Config → DuckDB SQL | Java class in a jar |
| **Extensibility** | **Open, in two independent halves** (both `ServiceLoader` seams): `PipelineNodeType` (descriptor) + `PipelineNodeExecutor` (execution, SHIPPED 2026-08-29) — a third party CAN ship a Step type today; a plugin needs **both** registrations. See [node-types.md §"The execution half"](../engine/node-types.md) | **Open registry**: `JobTypeProvider` via `ServiceLoader` *and* hot-deployable Job Packs |
| **Hot deploy / unload** | via a **pack**: `JobPackManager` also registers pack-contributed node types + executors into the owner-keyed overlay (`PipelineNodeTypes.register` / `PipelineNodeExecutors.register`, first pack wins per type, deregistered on unload — `JobPackManager.java:247-248`, 2026-08-31). Classpath (`META-INF/services`) providers still require a build | `JobPackManager`: watched dir, isolated `URLClassLoader`, atomic load-or-reject, in-flight-Run quiesce before unload |
| **Code trust** | classpath providers: none needed (compiled/deployed with the engine); pack-contributed ones inherit the pack jar's signature check | `-Djobs.packs.requireSignature` verifies every class entry of a pack jar |
| **Runtime context** | ⛔ none — config plus a SQL relation | `JobContext`: `runId`, `spaceId`, `trigger`, `config`, `params`, `log()`, `signals()`, `artifacts()`, `dryRun()` |
| **Unit of work** | one **Consignment**, over one input relation | one **Run** |
| **Trigger** | ⛔ cannot be scheduled — fires only inside its Pipeline's poll cycle | `cron` · `on_pipeline` · `on_signal` (+`when:`/`bind:`) · manual · catch-up |
| **Cross-run state** | ⛔ none of its own | watermarks (`PipelineWatermarkStore`), Run Artifacts, `$job.last_success_time` |
| **Emits** | nothing directly (the CONTROL trio `gap`/`alert`/`event` emit via the engine) | declared Signals + `ArtifactDecl`s |
| **Failure grain** | file → quarantine (`QUARANTINED_UNREADABLE`/`_MISMATCH`); Consignment → `FAILED`; retried by the next poll of the same input | thrown exception → `FAILED` JobRun; missing/invalid parameter → `REJECTED` before user code runs |
| **Observability grain** | per Consignment + per file; T22 per-`(node, relationship)` provenance counts | per Run: ledger row, structured `RunLog`, Artifacts |
| **Secrets** | ⛔ `NodeAttribute` has no `secret` | `ParameterDecl.secret` — masked at the response boundary |
| **`$`-Expressions** | ⛔ no `deduce`/`expressions` in the node contract | authored values resolve at fire time through `ExpressionRegistry` |

## 4. The binding rule, and how they compose

> in-motion (Pipeline) vs at-rest (Job) is a binding line, so an at-rest operator cannot be an in-motion
> node — [`GLOSSARY.md`](../../../GLOSSARY.md) §5

- **No nesting.** No `job` id has ever existed in `BuiltinNodeType`, and a sub-Pipeline is not a Step
  either.
- **Compose as producer/consumer over a shared store**: a Job fires on the Pipeline's `on_commit`, or
  binds by store name to a `sink.view`.
- **The reverse is allowed**: a `pipeline`-type Job re-running an authored Pipeline over data at rest
  makes the Job the *outer* Executable. That is composition, not embedding.

### Decision rule

- Transforming rows as they flow through one Pipeline's Consignment → **Step**.
- Own clock or event, spanning Pipelines, reading status/metadata, or reaching outside → **Job**.
- Java that must run per record/file *inside* the data path → **`ConsignmentProcessor`** (§5).

## 5. The gray zone — four things that blur the line

1. **`ConsignmentProcessor` is a Java plugin that is not a Step.** It is the SPI for per-record/file/batch
   Java in the data path, but it is hosted *by* the `consignment.process` **Job** Type over one committed
   Consignment, and gets a narrower `ProcessorContext` — its javadoc states authors never touch `Job` or
   `JobContext`. So there are already **two** plugin surfaces with different reach.
2. **A node-type plugin is a Step, but it is not the pack-hosted Step *kind*.** The old trap here
   ("`PipelineNodeType` looks extensible and is not") closed 2026-08-29: `PipelineNodeExecutor` is the
   execution half, and a plugin registering **both** halves runs today. The residual blur is between
   that — engine-classpath (or pack-overlay) code with no isolation, no `StepContext`, no watchdog —
   and the platform-services **S2-3** Step kind (isolated classloader, services ceiling, watchdog),
   which stays gated. `scaffold.mjs new nodetype` builds the former; `new step` is the latter and is
   still refused.
3. **`TriggerCoalescer` lives in `com.gamma.pipeline.exec` but is Job-only.** Package placement misleads.
4. **`transform.join` against a Reference Dataset is compile-only** — no runtime executor. So "a Step
   cannot read a second Dataset" is true *today* but is a missing executor, **not** part of the boundary.
   Do not state it as the rule; the rule is in-motion vs at-rest.

## 6. As-built gaps (2026-08-07)

- **A Job Pack's capability surface is far narrower than the plugin model implies.** `JobContext` offers
  no Dataset, Alert/Incident, Notification or query access. Built-ins reach those through constructor
  injection inside `JobService.registerBuiltins()` (`dataDir`, an `ObjectService` supplier, an
  `AlertService` supplier, the notification store, even `this`); a pack provider is instantiated no-arg by
  `ServiceLoader` and can receive none of it. A hot-deployed pack can log, emit a Signal and record
  artifact metadata — nothing else. Both sample Job Types added 2026-08-07 (`sample.hello`,
  `alert.evaluate`) required engine edits and **could not have shipped as packs**.
  → ✔ **CLOSED by platform-services S1-1…S1-7 (2026-08-09)**: `JobContext.services()` grants the declared
  `requires:` set from the boot `PlatformServiceRegistry` — v1 ids `notifications`, `incidents`, `schema`,
  `consignment-status`. `sample.hello` was migrated onto its grant and **its constructor injection removed**,
  so it is now pack-shippable, and a pack jar compiled inside `JobPackManagerTest` declares *and* consumes a
  grant, loads, and completes a Run — the evidence above is inverted. `alert.evaluate` followed on
  2026-08-10 once the `alerts` service joined the menu (D7's honest resolution), so **no built-in Job
  reaches the engine by constructor injection for its own work any more**. One as-built limit remains:
  `dataDir`-style Dataset reach is still injection-only (next bullet).
- **There is no Dataset API to hand anyone.** `SqlTemplateJob`/`ObjectsAnalyticsJob` resolve
  `Path.of(dataDir).resolve(name)` and open raw DuckDB. Dataset access is a filesystem convention, not an
  interface — it must exist before it can be granted to plugins.
  → *Deferred by design*: `DatasetAccess` follows the Consignment Selector
  ([`consignment-addressing-plan.md`](../../../archived-documents/plans-archive/consignment-addressing-plan.md) §6), then joins
  the seam as its flagship service.
- **No Job authors Notifications.** `NotificationStore.add(...)` exists; the only Job touching it is
  `maintenance` *pruning*. → ✔ **CLOSED by S1-3** (`NotificationAccess`, dedupe-collapse honoured;
  `sample.hello` is the reference consumer). The prune task is untouched.
- **No per-node metrics** — pipeline-level only.
- **No timeout or cancellation on either side.** → *Partially owned*: the **S2-3** watchdog covers
  `EXECUTED` Steps (plan R1); the Job-side watchdog stays a recorded gap beyond that plan.
- **`NodeAttribute` lacks `secret`, `pattern`, `group`, `multi` and expression support** that
  `ParameterDecl` gained in the job-parameter-contract work (re-verified 2026-09-01; it has since
  gained `dependsOn`, which `ParameterDecl` lacks). → *Fulfilled by* stage 2's widened
  `StepTypeProvider` descriptor (**S2-1**).

> ⚠ **The §6 gaps have an owner: [Platform Services](platform-services.md).** **Stage 1 is COMPLETE
> (S1-0…S1-8, 2026-08-09/10)** — the seam (grants via `requires:`), the v1 service menu, and the pack
> scaffolder + `PackTestHarness` — so the ✔ pointers above are as-built, not planned. Stage 2 (the open
> Step-kind registry, `LOWERED`/`EXECUTED`) stays gated on the branch-aware executor's armed path, so
> every §6 gap still marked *Fulfilled by* an `S2-*` step is open; it and stage 3 are tracked in
> [`BACKLOG.md`](../../../BACKLOG.md) §4. The seam's durable operating rules live in
> [`PROJECT_NOTES.md`](../../../PROJECT_NOTES.md) §5; this page's §0 wording came from **S1-0**, and the
> plan itself is archived at
> [`plans-archive/platform-services-plan.md`](../../../archived-documents/plans-archive/platform-services-plan.md).
> ⚠ Stage 2 being gated no longer means "no plugin Step at all": the classpath node-type seam
> (`PipelineNodeType` + `PipelineNodeExecutor`, plus the pack overlay) shipped independently,
> 2026-08-29/31 — what S2 still owns is the *pack-hosted* Step kind with isolation and a watchdog.

Related: [Jobs & Scheduling](jobs.md) · [Pipeline graph design](../pipeline-graph/pipeline-graph-design.md)
· [Signal backbone](signal-backbone.md) · [Platform Services](platform-services.md)
