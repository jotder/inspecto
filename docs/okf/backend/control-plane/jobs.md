---
type: Concept
title: Jobs & Scheduling
description: JobService — cron, event-triggered, and manual jobs, with an off-bus virtual-thread handoff.
resource: inspecto-engine/src/main/java/com/gamma/job/JobService.java
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
---

# Jobs & Scheduling

`JobService` (`inspecto-engine/src/main/java/com/gamma/job/JobService.java`) hosts a registry of jobs and a
virtual-thread `workers` executor. Four trigger modes:

* **Cron** — jobs with a `cron` field are armed on the shared `Scheduler`. **2026-07-20**: `Scheduler.cron()`
  now returns a `CronHandle`; `JobService.removeJob` cancels it, so a deleted/replaced job's self-re-arming
  chain actually stops instead of ticking as an inert no-op forever (the fire-time `jobs.containsKey` guard
  stays as a second line of defence against the cancel/fire race).
* **Event** — jobs with `on_pipeline` subscribe to the `ConsignmentEventBus`; `onBatchEvent` matches a `SUCCESS`
  status + pipeline name, then `submit()`s. This is the **deadlock-safe** path — `submit` hands work to
  `workers` and returns immediately, so the synchronous [event bus](events-metrics.md) never holds a
  `PipelineRunGuard` claim across a new run.
* **Signal** — jobs with `on_signal` fire when a matching Signal is published: `Signals.matchesType` is an
  exact (case-insensitive) match or a `prefix.*` glob, optionally narrowed by a `when:` guard over the
  firing Signal's payload (`WhenGuard`, fail-closed on an unparsable expression — there is no
  authoring-time validator). Self-loops are suppressed. **Authorable in the UI since 2026-08-10.**
* **Manual** — `POST /jobs/{name}/trigger`. The legacy unversioned call stays **synchronous and unchanged**;
  the same route under `/api/v1` is **async** (W5): it returns `202` + `{runId, …}` + a `Location` header,
  the caller polls `GET /jobs/runs/{runId}`, and an `Idempotency-Key` header replays the cached response on
  retry. Pipeline triggers gained the identical async contract in W5b (poll `GET /runs/runs/{runId}`).

⚠ **The write contract is the `job:` TOON section, not a DTO.** `POST /jobs` / `PUT /jobs/{name}` pass the
body straight to `JobConfig.fromMap`, so keys are **snake_case** (`on_pipeline`, `on_signal`, `catch_up`)
and type-specific parameters are **flat** alongside them. An unrecognised key is **absorbed as a parameter,
never rejected** — so a misspelled trigger key yields a job with no trigger and no error anywhere. Pinned by
`ControlApiJobCrudTest`; the full trap and the read side's deliberate asymmetry are in
[`PROJECT_NOTES.md`](../../../PROJECT_NOTES.md) §4.

`submit()` binds the `space` MDC (for non-default spaces — see [multi-space](multi-space.md)) and runs on a
virtual thread. `runJob()` uses `runner.runExclusiveOrSkip(name, …)` for a non-overlap guarantee (a job
already in flight records `SKIPPED`); different jobs run in parallel. On startup, `catchUpMissedFires()`
replays a single missed cron fire for `catch_up: true` jobs from the durable `jobs_runs.csv` ledger.

**Total-concurrency bound (ON by default since 2026-08-26 — BACKLOG D11).** The bound now ships at **4**
(`JobService.DEFAULT_MAX_CONCURRENT_RUNS`), as the other half of the DuckDB `memory_limit` pair — ⚠ whose
*memory* half has **no code default** (`DuckDbUtil.memoryLimit(null)` is `null`, no `scheduler.toon` ships; BACKLOG GAP-4;
this line said "the `memory_limit=2GB` default" until 2026-09-08):
total memory exposure is `memory_limit` x concurrent Runs, so an unbounded Run count makes any
per-instance cap meaningless. **It is owned by the server configuration** — `scheduler.toon` →
`GET/PUT /system/scheduler`, installed via `JobService.setMaxConcurrentRuns` at boot and on a PUT — and
`-Djobs.maxConcurrentRuns` is a *bootstrap default* consulted only when nothing is stored (⛔ a key served
by the settings tier must not also be read from `-D` at use time; see `SchedulerRoutes`). `0` still means
unbounded, and the bound is **hot-resizable**: a shrink drains (in-flight Runs finish, the new ceiling
gates the next admissions), and crossing back from unbounded re-seeds the permit count rather than
trusting one that unbounded Runs never took from. ⚠ `JobService` is **per space**, so in hosted
multi-space mode the worst case is the bound times the space count; in single-tenant mode (the default)
it is the process-wide bound D11's arithmetic assumes.
A bound of `N` installs a `Semaphore(N)` (`runPermits`)
acquired/released **on the worker thread inside** `submitRun`/`submitAdhocRun`, never on the caller — so a
full pool *queues* fired Runs rather than blocking the cron/event/manual dispatch thread. This is the
per-job-service analogue of the batch-ingest `maxConcurrentRuns` semaphore in
`MultiCollectorProcessor.runAll`, and the stated prerequisite for an eventual on-by-default DuckDB memory
cap (unbounded job concurrency defeats a `RAM/N` per-instance cap — see [DuckDB](../engine/duckdb.md) and
`docs/BACKLOG.md` §5). Deadlock-safe: no Job Type synchronously waits on another Run's completion (all
triggering is fire-and-forget via `workers.submit`).

`JobType` includes `ENRICH`, `REPORT`, `MAINTENANCE`, and **`PIPELINE`** (authored-Pipeline execution — see
[pipeline live execution](../pipeline-graph/live-execution.md)).

## The Job Framework (P0–P3, shipped 2026-07-09; `feat!` → 5.0)

Design of record (all phases + resolved decisions + TOON config gallery):
[`job-framework-design.md`](../../../archived-documents/plans-archive/job-framework-design.md). The durable model:

* **Job Types as plugins** — `JobTypeProvider`/`JobTypeDescriptor` (+`@JobTypeMeta`) discovered via
  `ServiceLoader`; type ids are **open strings** (not the enum). Jobs implement `run(JobContext)`; the
  context exposes the Run Log, `SignalEmitter`, `ArtifactRecorder`, and host services (data dirs, DuckDB,
  `SecretResolver`, `ViewStore`). Descriptors (`GET /jobs/types/{id}`: config schema + `ParameterDecl`s +
  emitted signal types) drive the UI's generated authoring forms.
* **Parameters** — the `ParameterResolver` resolves each declared parameter first-hit-wins:
  trigger `args` → signal `bind` (`$signal.<field>`) → job config `params:` → deduced `$`-context →
  default; an unresolved `required` parameter fails the Run fast in state **REJECTED** (fail-closed, before
  user code). **2026-07-20 SHIPPED type-inference:** a resolved value is now also checked against its
  declared `ParamType` (`INTEGER`/`DECIMAL`/`BOOLEAN`/`DATE`/`INSTANT`; `STRING`/`DATASET_REF` accept any
  non-blank string) — a mismatch (e.g. a `bind: $signal.count` extracting `"n/a"` for a required INTEGER)
  goes into `Resolution.invalidType()` and REJECTS the Run the same way a missing required parameter does,
  instead of the raw string reaching a Job's own `Integer.parseInt`/etc. and throwing uncaught mid-run.
  `$`-context includes `$today`/`$yesterday`/`$tomorrow`, the offset family `$day(-n)`/`$month(-n)`/`$year(-n)`,
  `$now` + its numeric forms `$now.epoch_seconds`/`$now.epoch_millis`, `$run.*`, `$job.last_success_time` (the natural
  namespaces never conflate: `$name` (run time) · `${param}` in `*_job_template.toon` (authoring time) ·
  `${ENV:KEY}` (config-load-time secret, never logged).
* **Signals** — one ledger (`Signal` envelope: ULID, dotted type, source Ref, correlationId, severity,
  payload) persisted through the `EventLog` seam; the framework emits `job.run.started/completed/failed/
  rejected` for every Run. Triggers v2: `on_signal:` + `when:` guard + `bind:` — Job→Job composition is
  signal chaining (chains visible via `correlationId`); a Signal announces, never decides. Read view
  `GET /signals` (`SignalRoutes` → the static `Signals.query` over the shared `EventStore`, no service
  object): filters `type` (exact or `prefix.*` glob, applied in Java), plus in-store `since`/`until`
  (epoch-milli bounds), `severity` (a min floor mapped onto the event-level ladder), `correlationId` and
  `limit`. Not a duplicate of `/events` — only here do the dotted type / severity / payload decode.
* **Run Log & Run Artifacts** — per-Run structured events, plus artifacts (`dataset`/`file` +
  `ResultSetMeta`) in `job_run_artifacts` beside `DbJobRunStore`; queryable via
  `GET /jobs/{name}/runs/{runId}/artifacts` / `/jobs/{name}/artifacts/latest` and `$upstream(...)`. A
  `file`-kind artifact's bytes download from the sibling `GET /jobs/{name}/runs/{runId}/artifacts/{artifact}/content`
  (attachment, content-type inferred from the filename; 404 when unknown, not a file, or cleaned up). Report
  Jobs record their delivered `out_dir` file as a `report` artifact, so a scheduled report is downloadable.
* **Job Packs** — hot-deployable jars in `-Djobs.packs.dir` (absent ⇒ feature off, fail-closed); watched
  with a settle delay, each pack in its own parent-first `URLClassLoader` with shaded deps.
  `GET /jobs/packs`, `POST /jobs/packs/rescan`. **2026-07-20 SHIPPED the classloader half of quiesce:**
  `JobPackManager.acquireRun`/`releaseRun` pin a pack's active-run count for the duration of a Run's
  `Job.run(ctx)` (`JobService.runJob`); `unload()` still deregisters the pack's types immediately (so a
  reload's new types are usable at once), but defers closing the old `URLClassLoader` + deleting its staged
  jar copy until the count drops to zero — a Run already executing pack code no longer risks the loader's
  resources being yanked mid-run. **2026-07-20 SHIPPED the remaining half — stale-Job rejection:** `unload()`
  now also calls a new `JobPackManager.UnloadListener` with the pack's owner key right after deregistering its
  types; `JobService` records each Job's owning pack at build time (`jobPackOwner`, keyed by job name) and,
  on that callback, adds the owning pack's job names to an `unavailableJobs` set. The shared `runJob` lifecycle
  checks this set right after building the `RunContext` and, if flagged, records the Run `REJECTED` (same
  fail-closed shape as a missing-required-parameter reject, emitting `job.run.rejected`) instead of calling
  `job.run(ctx)` on the stale instance. A rebuild (`upsertJob`) or `removeJob` clears the flag/owner mapping,
  so a reloaded pack's fresh Job runs normally again.
* **`sql.template`** — the built-in templated-SQL Job Type and first real artifact producer; its
  parameters are scanned from the SQL itself.
* **`caserule.evaluate`** — schedules the auto-grouping tail of the Alert → Incident → Case chain (C5):
  evaluates a saved Case Rule, grouping matching in-window Incidents under a Case via
  `ObjectService.evaluateCaseRule` — the same step `POST /cases/rules/{name}/evaluate` drives — and emits
  `caserule.evaluate.completed`. Required param `rule`. Evaluation is idempotent (already-grouped Incidents
  are skipped; later matches attach to the same still-open rule-raised Case), so a cron re-fire attaches new
  matches instead of cloning a Case. Requires the space Object Engine (wired via `JobService.objects()`) — a
  Run fails closed if it is not wired (unlike `recon.run`, where the Object Engine only adds an optional
  Incident promotion). Mirrors the `recon.run` built-in's shape (a schedulable wrapper over a
  manual-trigger-only service call).
* **`objects.analytics`** (shipped 2026-07-25) — samples `ObjectService.analytics(type)` for Alerts /
  Incidents / Cases / Tasks into **tall Parquet rows** under `<dataDir>/ops_analytics/` and result-stamps an
  `ops_analytics` **Dataset**, making the operational rollups bindable in Studio/BI (widgets, dashboards,
  queries, Alert Rules) — previously they were reachable only through `GET /objects/analytics`, which the mail
  UI renders directly. Optional params `types` (CSV filter, default all four; an unknown name fails the Run
  closed rather than silently sampling a subset) and `retention_days` (`0` = keep forever). Emits
  `objects.analytics.completed` (rows, types, durationMs, purged) and records a `dataset` Run Artifact.
  Requires the space Object Engine — fails closed like `caserule.evaluate`.
  * **Why a materialization job, not a view.** `OperationalObject`s live only in the JDBC
    `inspecto_ops_objects` table (single-writer `inspecto-ops.db`), so no Parquet/view surface exists for a
    `dataset` `physicalRef`/`view` to bind to, and a second connection to that DB is not allowed. The
    analytics are therefore computed **in-process** via the post-construction `Supplier<ObjectService>` seam
    and written out as an aggregate sample.
  * **Row shape is tall** — `(sampled_at TIMESTAMP, object_type, axis, "key", value DOUBLE)` — because the
    breakdown keys (status / L1-category / priority) are open-ended, so wide columns would be unstable across
    runs and spaces. `axis` ∈ `scalar` (total/backlog) · `status` · `category` · `priority` · `cycle_time`
    (count/avg_ms) · `impact` (impact_amount/records_affected). `value` is DOUBLE because `impactAmount`
    isn't a count. `key` is **quoted** in the DDL — a column name deliberately kept, quoted so no dialect's
    reserved-word list can bite (cf. the `day`/`trigger` gotchas in PROJECT_NOTES).
  * **Append per run, not full-refresh swap** — the `storage_report` catalog idiom (one timestamped file,
    readers glob the dir), *not* `MaterializeTask`'s stage/atomic-swap, because the time dimension is the
    entire gain over the live endpoint. Current-state consumers filter
    `sampled_at = (SELECT max(sampled_at) …)`; trends group by a bucket. Inline retention (epoch parsed from
    the filename, the same sortable key `storage_report` chose over an ISO string) bounds the glob; a file
    that doesn't match the pattern is left alone.
  * **Read path needed zero new code** — `DatasetRelation.relationSql` already resolves
    `physicalRef → read_parquet('<dataRoot>/ops_analytics/**/*.parquet')`, so the dataset shows up in Studio
    pickers, `/db/query`, widgets and Alert Rules for free. `ObjectsAnalyticsJobTest` reads
    back through that real seam rather than a hand-written glob — that is what proves the binding.
  * **Cadence is operator-authored** (the deferred product question dissolved rather than answered): the
    built-in registers only the *type*; a space schedules it with its own `cron:` in a `*_job.toon`. Demo seed:
    `spaces/demo/config/jobs/ops_analytics_sample_job.toon` (hourly, `retention_days: "90"`).
  * A write failure emits `objects.analytics.completed` at `WARN` **and rethrows** — the write *is* the work,
    so a swallowed failure would report a silent no-op success. Dry run computes the rows and writes nothing.
  * **Deliberate non-goals:** no Parquet/view surface for *raw* `inspecto_ops_objects` rows (row-level export
    has PII/ACL implications; these are aggregates) · no change to `GET /objects/analytics` or the mail UI ·
    no UI work at all.

## The parameter contract & runtime Expressions (shipped 2026-08-07/10)

**This section is the design of record.** *(Provenance: the plan's 17 steps, each with the wrong premise it
corrected, are in [`job-parameter-contract-plan.md`](../../../archived-documents/plans-archive/job-parameter-contract-plan.md)
— read for why, never for what is built; not maintained.)*
It replaced the hardcoded `$`-vocabulary described in the *Parameters* bullet above. Guiding principle:
**versatility over built-ins** — capability arrives by *registration*, never by editing a `switch`.

* **`ExpressionProvider` SPI + `ExpressionRegistry`.** `ParameterResolver.deduce()`'s switch is gone; the
  fifteen built-in tokens are declarations in `BuiltinExpressions`. Providers register from `ServiceLoader`
  **and** from Job Packs (owner-tagged, `deregister(owner)` on unload); a **collision fails closed in all
  three paths** — a pack redeclaring `$today` is rejected whole rather than shadowing it.
* **`ExpressionDecl` is the catalog entry**, not just an implementation detail: `token`, `form`
  (LITERAL | PREFIX | FUNCTION), `yields` (a `ParamType`), `description`, `example`, `availableIn`
  (Trigger kinds) and `contextFree`. ⚠ **`token` is not always typeable** — `$day(n)` is a *shape*;
  `sampleExpression()` is the rule for what the registry can actually evaluate (a literal's own token, a
  shaped token's `example`). A UI inserting the shape authors an unknown expression.
* **Three-way resolution, not two.** "Unregistered token" and "declared token with no value here" are
  different outcomes: an unregistered token stops the ladder and REJECTS the Run with
  `Resolution.unknownExpression`, while a `bind:` to an absent `$signal.<field>` still falls through to the
  next layer. Conflating them (both `null`) is what let a `deduce:` typo silently use the default instead.
* **`$$` escapes a literal `$`**, and it had to ship *before* authored values evaluated, or every config
  holding a literal `$` would have broken.
* **Whole-value evaluation only** (§6.1, settled): a value is evaluated only when it *is* a token.
  `report for $today` stays literal. This is the scoped-evaluation answer to the `sql.template` `$`
  collision — the SQL body keeps its own parameter namespace (`expressions: false`), and a runtime window
  is bound by indirection (`params: {from: "$event_day(-7)"}`), not by tokens inside SQL. ⚠ It also means a
  `multi` (CSV) parameter resolves a token **only when the token is the entire value**: `authored()` sees
  the whole raw string, and the CSV split happens later, in post-resolution *validation*.
* **Validation runs on the resolved value**, so a literal and an Expression result are held to the same
  contract (`options`/`pattern`/`min`/`max`, per item under `multi`). A *pre*-resolution `yields` check is
  deliberately **not** done in the resolver — at fire time it could only refuse what post-resolution
  validation already judges on the evidence (a STRING-yielding `$signal.<field>` legitimately carries a
  date). That check earns its keep at **author** time, in the picker.
* **Every run leaves a parameter RECEIPT** (`DUCKLE-C4-PARAM-PROVENANCE-1`, 2026-09-15): `ParameterResolver`
  keeps the layer that won each value (`args` · `bind` · `config` · `config:flow` · `deduce` · `default`) and the
  LOWER layers that also carried a *different* value (`Resolution.provenance`, `{source, overrode:[…]}`), and
  `JobService` writes it as a `params` run artifact (`GET /jobs/{name}/runs/{runId}/artifacts`, kind `params`,
  `detail = {name → {source, overrode}}`) AFTER the run body — so a run's output artifacts keep their positions
  (`artifacts[0]` stayed "the output" for existing readers) — and whether the run succeeded or threw. ⛔ It is
  NOT an output: `latestArtifacts` (the outputs listing and `$upstream(...)`) excludes kind `params`. Layer NAMES only, never values — a
  `secret` is a non-question, and "was a token supplied, and by whom" is exactly what the names answer. Two
  surfaces agreeing is not an override. ⚠ Not built here: the stable rejection codes (`param:unknown`,
  `param:missing`) the row also named — rejections are still prose (`JobService` joins the three lists).
* **Two runs can be DIFFED from recorded facts only** (`DUCKLE-C2-RUN-DIFF-1`, 2026-09-16):
  `GET /jobs/{name}/runs/{a}/diff/{b}` → `JobService.diffRuns` → the pure `JobRunDiff.diff(run, artifacts, run,
  artifacts)`. Six kinds, each either **compared** (a list of `{field, a, b, explanation}` differences, and an
  `explanation` array with exactly one line per difference — no prose is generated beyond what a difference
  itself says) or **not compared** with the reason stated. `invocation` = trigger + the parameter receipt above
  (layer names only, so a secret is never printed; when neither run has a receipt the kind says so in `note`);
  `execution` = status, message, duration; `output` = dataset/file artifacts BY NAME — rows, bytes, ref,
  watermark — and **absent is not zero**: an artifact one run produced and the other did not is `absent`, never
  `0 rows` (a run that died at node 2 has no counts after it). `inputs`, `code`, `runtime` are always **not
  compared**: no run records an input manifest, the job type's implementation version, or the JVM/host — a
  diff that implied parity there would be lying, and stating the gap IS the deliverable. 404 when either run is
  unknown. ⛔ Deliberately not a model summarising two receipts; the diagnosers (`HeuristicDiagnoser`,
  `ModelDiagnoser`) may consume it, none does yet. ⚠ Not built: the `***`/digest secret comparison the row
  named — moot, since values are never in the receipt to begin with.
* **`ParameterDecl` carries the whole rendering + validation contract** (eleven components: `label`,
  `tier`, `options`, `pattern`, `min`/`max`, `placeholder`, `group`, `multi`, `secret`, `expressions`, …),
  and `JobTypeDescriptor.toMap()` serves it. A 6-arg delegating constructor kept all 16 raw call sites
  compiling. `secret` masking has **one definition**, `com.gamma.job.SecretMasking` — a literal becomes
  `***`, a `${ENV:…}` reference stays visible (the reference is not itself sensitive, and hiding it leaves
  an operator unable to see how the secret is wired; house precedent is `ConnectionProfile`).
  `JobConfig.toMap()` — and therefore bundle export — is untouched, so masking still happens on the way
  out, never in the stored config.
  🔴 **It applies at THREE surfaces, and until 2026-09-15 it applied at only one** (`PARAM-SECRET-LEAK-1`).
  The response boundary (`JobRoutes.maskSecrets`) was masked; the other two were not:
  `JobService`'s `"run started"` log wrote the fully resolved map in cleartext for **every** Job, and
  `ParameterResolver`'s rejection messages embedded the offending value — which is not one leak but four
  sinks, because `invalidType` flows into the run log, the `job.run.rejected` Signal, the persisted
  `JobRun.reason`, and the run-detail API.
  ⚠ **The rule lives in `inspecto-engine`, not beside `JobRoutes` where it was written**, because two of
  its three callers are engine-side and the engine cannot depend on the control plane. ⛔ Do not copy the
  predicate back up to the boundary — a second definition of *"what is a secret worth hiding"* is exactly
  how this surface fell out of step in the first place.
  ⚠ **A declaration's own terms are deliberately NOT masked** in a rejection message — `type`, `options`,
  `pattern`, `min`/`max` are what the author wrote, not what the operator supplied, and hiding them would
  leave a rejection that says nothing. Only the value is hidden.
  ⚠ Two other things in the tree spell `"***"` and are **not** the same rule:
  `PipelineBundleRoutes.maskSecrets` masks by *key-name pattern* over a nested config tree (a different
  question), and `SampleHelloJob` masks its own echo by hardcoding the literal name `"api_token"`.
  🔴 That last one is how the leak was proven reachable rather than theoretical: `sample.hello` is a
  **shipped built-in that declares a `secret` parameter**, its body takes care not to log the value — and
  `JobService` logged it one line earlier anyway, defeating the Job's own caution before it ran.
* **Provenance is assembled by the REGISTRY, not the descriptor** (`implClass`/`source`/`version`): a
  provider cannot know its own provenance.
* **`GET /jobs/expressions`** serves the catalog **generated from the registry**, so it stays correct as
  packs load. A `contextFree` entry's `preview` is evaluated by the same evaluator a Run uses — which is
  what makes a client's preview correct *by construction* instead of a second implementation. Context-bound
  entries fall back to their declared sample: there is no firing Run at request time, and inventing one
  would show an author a value their Job will never see. ⚠ The route registers as a **fixed sub-path before
  the single-segment `/jobs/{name}` regex**; registration order is load-bearing.
* **`GET /jobs/processors`** (2026-09-10, `PROCESSOR-CATALOG-ROUTE-1`) serves the registered
  `ConsignmentProcessor` ids a `consignment.process` chain step may name — `{processors: [{id, className,
  shadowed}], total, truncated, unusable}`. A **read**, so no write-root gate; **bounded** by a hard cap
  with `total` still reporting the true count. Same fixed-sub-path-before-`/jobs/{name}` ordering rule as
  `/jobs/expressions`. ⚠ **Empty on a stock install** — the product ships no processor implementation — so a
  client must treat an empty catalog as normal and keep accepting a typed id. ⛔ A Job Pack cannot contribute
  a processor (`JobPackManager` registers four other SPIs), so the `ServiceLoader` set is authoritative and
  there is no overlay to merge. 🔴 It loads through the *same* `ServiceLoader.load` call as
  `ConsignmentProcessJobType.fromServiceLoader`, so the catalog cannot disagree with the lookup that
  resolves an id at run time; a provider the classpath cannot produce is **counted and skipped, and the scan
  continues past it** — a stale services entry throws from `hasNext()`, so stopping there would silently drop
  every provider listed after it. → [post-sync-step-chains](../engine/post-sync-step-chains.md)
* **`mail.send` + the `mail` Platform Service** are the reference generic Job Type (§9 verbatim): it
  reuses the `NotificationChannel` seam rather than opening a second SMTP client, and declares
  `requires: [mail]` because a Job that mails outward must declare that reach. ⚠ A built-in with an
  unsatisfiable `requires` is **accepted** — only the pack/classpath paths refuse (S1-7). CC is deferred
  (BACKLOG §4): the channel seam takes one recipient list.
* **`$upstream(<job>).artifact(<name>).<attr>`** exposes a predecessor's latest artifact. Its two
  event-time attrs resolve **live** against the Consignment output registry keyed by the artifact's `ref`,
  which is why they replaced the single `time_range` attr no consumer could use — one opaque
  `"<min>..<max>"` string that `SqlParamScanner` substitutes as one SQL literal and nothing split. See
  [`consignment-addressing.md`](../engine/consignment-addressing.md).

### The operations zone (`-Dops.timezone`, shipped 2026-08-15)

`com.gamma.util.OperationsZone` resolves the one zone that governs **both** cron firing
(`PipelineScheduler:117` → the `cronDue` comparison at `:373-375`; `JobService:246` → `scheduler.cron`,
next-fire and `ZonedDateTime.now`) **and** the `$today`/`$yesterday`/`$day(-1)` family
(`JobService` → `ExpressionContext.zone` → `BuiltinExpressions.fireDate` = `LocalDate.ofInstant(fireTime,
ctx.zone())`). One zone for both deliberately: a job that fires at 00:30 ops-local and then resolves
`$today` in another zone is an off-by-one-day generator.

- **Unset ⇒ `ZoneId.systemDefault()`**, byte-identical to the behaviour before it existed. That is the
  whole reason this shipped without operator migration: no existing schedule moves until someone opts in.
- **Set-but-unresolvable throws at startup**, naming the property *and* the offending value. ⛔ No silent
  fallback — that would run every schedule in the wrong zone while the operator believed otherwise, and a
  typo is indistinguishable from intent. (It can say the value; the descriptive key's `CrossFieldRule`
  cannot, since a rule's description is static.)
- 🔴 **It is NOT `meta.domain.timezone`, and that distinction is the whole design.** That key describes
  what zone the **data's** timestamps are in — a catalog annotation whose real consumer is the consignment
  event-time cut (§5.6/§10.1) — while this one is what the **operator's schedule** is expressed in. They
  routinely differ (data in UTC, a 06:00 `Asia/Kolkata` run). Wiring the catalog note into the scheduler
  would also have made firing depend on **directory scan order**: a space holds any number of
  `*_meta.toon` files and `MetadataGraphService:155` merges them last-non-blank-wins. Full reasoning:
  [`domain-timezone-behaviour-plan.md`](../../../archived-documents/plans-archive/domain-timezone-behaviour-plan.md).
- **Two more sites adopted it 2026-08-15**, because neither was a judgement once grounded:
  - `EventRoutes.epochMillis` — a bare `yyyy-MM-dd[ HH:mm:ss]` bound on `/events` is an **operator's wall
    clock**, typed into a console beside a schedule that already fires in this zone. ⚠ The resolve happens
    **outside** the parse's `catch (RuntimeException)`: inside it, a misconfigured `-Dops.timezone` would be
    caught and returned as a **400 blaming the query the operator just typed**.
  - `PackTestHarness:164` — its production counterpart `JobService:250` had **already** moved, so the
    harness was dry-running `$today` in a different zone than the real run. That is not a display choice, it
    is a harness that can go green for the wrong date; the whole point of the harness is that a pack green
    here is a pack the engine accepts.
- ✅ **Three sites stay on `systemDefault()`, to match their writers — DECIDED 2026-08-15. The sweep is
  CLOSED; this is the end state, not a deferral.** `AlertService:374` (cutoff vs. the ledger's
  `end_time`/`start_time`, written by `ConsignmentIngestor:298/360/375/386`), `ReferenceCompactor:142` (literal vs.
  the `__valid_from` column, written by `ConsignmentIngestStrategy:215`), `InspectoTools:385` (parse vs. the audit
  CSV, written by `JobService:714…1010`). Each is the **read half of a write/read pair** over a **zone-naive**
  stored string, self-consistent precisely because both halves use `systemDefault()`. The operations zone
  answers "when does the operator's schedule fire"; **none of these three is an operator-facing clock** — they
  are internal round-trips over the platform's own timestamps, so there is nothing for `-Dops.timezone` to
  say about them. ⛔ **Do not convert a reader alone** — it silently offsets every window by the gap between
  ops and host zone, and in the compactor's case rows fall outside `keep` and are **dropped**. The decision is
  recorded as a comment at all three sites, because a backlog row cannot reach someone editing the file.
- ⚠ **`ReferenceCompactor` is NOT the UTC mismatch it looks like** — probed 2026-08-15 against
  `duckdb_jdbc:1.5.2.1`: the **ICU extension is bundled and auto-loaded**, so DuckDB's session `TimeZone` is
  the **HOST** zone, not UTC, and `now()::TIMESTAMP` is the same wall clock as `LocalDateTime.now()`. The
  common "DuckDB defaults to UTC" belief holds only for an ICU-less build. An agent asserted that default as
  fact and on it called this site a live bug whose fix was to force the reader to UTC — which would have
  **created** a +05:30 skew and dropped rows. ⚠ DuckDB follows `systemDefault()` but is **blind to
  `-Dops.timezone`** (nothing issues `SET TimeZone`; `DuckDbUtil` has no setter), so were this pair ever
  migrated, the connection's zone is a **third** moving part.
- ⚠ **No `PipelineScheduler` test class exists at all**, and neither original consumer exposes its zone, so
  the guard is at the resolver (`OperationsZoneTest`, 5 tests) plus the module's existing suites. The
  resolver test was **falsified** — stubbing `resolve()` back to `systemDefault()` failed 3 of the 5. Its
  fixtures pin two zones that disagree on the date for a given instant, so it cannot pass by coincidence on
  a host whose default happens to match (this box's is `Asia/Calcutta`, +05:30). The two 2026-08-15 adopters
  DO expose their result, so both are pinned directly: `EventRoutesTimeBoundTest` (4 tests, incl. the
  "unresolvable zone is not blamed on the query" case) and `PackTestHarnessTest`'s
  `resolvesDateMacrosInTheOperationsZone`, which runs `$today` under `Pacific/Kiritimati` (+14) and `Etc/GMT+12` — 26 hours apart, so
  their dates never coincide. ⛔ Every zone in both is **named**; a test asserting `systemDefault()` passes
  everywhere and proves nothing.

## Three single-seam properties, confirmed by audit (2026-09-15)

Distilled from the duckle review before that candidate list was archived. Each was checked against the
code rather than assumed, and each is the kind of property that is **cheap to hold and expensive to
regain** — so the note exists to stop a future change quietly introducing a second seam.

- **One run id, and logs are found by FIELD not by text.** `JobService.newRunId` mints one id that threads
  unchanged into the live-run map / `JobRun` (the receipt), `RunContext`, and `RunLogEntry.runId`. Run logs
  are **one JSONL file per run named by that id**, and the read path is a direct file lookup
  (`RunLogStore.read(runId)`), never a grep over a shared log. ⇒ a run whose message text merely *mentions*
  another run's id can never be served as that run's log. ⛔ Do not add a log reader that filters by
  matching text.
- **One parameter boundary.** `ParameterResolver.resolve` has exactly **one** production call site
  (`JobService.executeRun`), and every trigger surface — manual API, cron, signal, replay — reaches it
  through `submitRun → runJob → executeRun`. ⇒ a typed parameter is validated once, wherever the fire came
  from. ⚠ SQL `$`-tokens are a **separate and deliberate** namespace (`com.gamma.query.Parameters`) with a
  different grammar and purpose; `ParameterResolver`'s own header records consolidating the two as future
  work. Do not "unify" them casually — they are two contracts, not one duplicated.
- **One config source behind two limiters.** `scheduler.toon` is parsed in exactly one place
  (`SchedulerSettings.read`), and `SchedulerRoutes.installResourceCaps` pushes its two keys out to the two
  limiters — `ConcurrencyBroker.setSystemCap` (Consignment slots) and `JobService.installMaxConcurrentRuns`
  (Run slots). **Neither limiter reads the file itself**, so there is no second parse to drift.
  ⚠ They are two **different numbers by design** — different resource types — not one number read twice;
  do not "fix" them into agreement.

### The shared-pipeline audit (`JOB-PIPELINE-PARAM-UNIQUE-1`, shipped 2026-09-12)

`SchedulerAuditTask.sharedPipelineFindings` is a pure config scan reporting every pipeline targeted by more
than one enabled job, naming **all** of them. It runs inside the on-demand `scheduler_audit` task **and** as
a default-on host audit (`JobService.auditSharedPipelines`) hooked to the same two transition sources as the
orphan audit, with the same once-per-transition debounce and the same `-Djobs.orphan.audit=false` kill
switch. The skip message now appends `— may be held by [other jobs]`.

⛔ **WARN ONLY — it must never refuse** (operator decision, 2026-09-12). Two jobs on one pipeline with
different schedules or parameters may be deliberate, so failing closed would refuse valid deployments.
⛔ Do not revisit as a refusal without re-opening that decision. ⚠ `SchedulerAuditTask`'s own javadoc still
reads *"making it fail closed needs an operator decision"* — that sentence is **stale**; the decision was
taken.

🔴 **The finding keys through `JobService.authoredPipelineKeyOf`, never its own copy.** Recomputing it as
`params().get("pipeline")` drops the Tier-3 `flow:` dual read *and* the type check — mutation-verified
2026-09-12: that change fails exactly `theLegacyFlowKeyAndTheCanonicalPipelineKeyAreTheSamePipeline` and
`aMaintenanceJobCarryingAPipelineParamIsNotASharer`. The production symptom would be a pair of jobs that
skip each other while the audit calls them healthy. ⛔ Naming only the first sharer is likewise
mutation-guarded — it leaves the operator exactly as unable to act as the bare skip message did.

## Maintenance jobs (MNT, shipped 2026-07-12)

System maintenance is **tasks on the `maintenance` job type, never shell scripts or OS cron**. Task library:
`cleanup` (retention knobs `max_count`/`max_size`/`archive_dir`/`min_keep` — the newest N are never retired),
`ledger_prune`, `runlog_prune`, `notification_prune`, `receipt_prune`, `event_prune` (COMPLY-3: the
audit-retention window over the Parquet event store, a whole-day-partition delete via `EventStore.prune`,
attached to `JobService` post-construction like the feed), `incident_purge` (see below —
the only destructive task over operator business records) (`retention_days` required — deliberate forgetting;
`notification_prune` forgets in-app feed entries older than the window whatever their read/archived state,
via `NotificationStore.prune`/`countPrunable`, the per-space feed attached to `JobService` post-construction),
`db_maintenance`
🔴 **`ledger_prune` never prunes below a source’s high watermark** (`LEDGER-PRUNE-EATS-RESUME-STATE-1`, 2026-09-15). `AcquisitionLedger.highWatermark` is `MAX(last_modified)` **derived from the very fingerprints the sweep deletes** — there is no separate watermark column — so an age-based sweep that reached the frontier deleted the source’s *resume position*, not merely its history, and every file re-ingested as NEW. The sweep is now floored: the row carrying the watermark survives, so `highWatermark` returns the same value before and after a prune.
⚠ **Two clocks, not one.** The age test reads `processed_at` (when we handled the file); the floor reads `last_modified` (the source’s own mtime). They are different columns and are not interchangeable — a floor written against `processed_at` compiles and still loses the position.
⚠ **Exactly ONE row per source is protected, not every row tied at the maximum.** Protecting all ties looks equivalent and is not: where a source’s files share one mtime (a coarse clock, a bulk copy, a generated feed) that is the entire history, and retention silently stops working while still reporting success. The protected row is chosen by a total order (`last_modified`, `processed_at`, `relative_path`) so the dry run and the sweep cannot pick different survivors.
⚠ The decision deliberately kept **two designs for one idea** rather than adding a column: file acquisition derives its watermark from fingerprints, while `DbAcquisitionLedger`’s separate row-level **export** watermark table is its own store that `prune` never touched. ⛔ A future sweep that “unifies” them is reopening the decision, not tidying.
✅ `prune` and `countPrunable` are now two call sites of ONE predicate per backend — closing that pair of `PRUNE-PREVIEW-DRIFT-1`; the row stays open for `receipt`/`notification`/`dedup` prune.
(CHECKPOINT/VACUUM over the live stores via host seams), `storage_report` (per-axis usage; on a real run
also appends a queryable per-axis sample to the `maintenance_storage` catalog Dataset — the `BackupTask`
idiom, skipped on dry-run), `storage_trend` (growth-trend analysis over that series), `scheduler_audit`,
`metadata_validate` (broken refs / duplicates / missing physical data), `file_repository_audit`. **Contributed,
not built in (since 2026-09-07, EDG-01 cell 2):** `backup` (timestamped zip + SHA-256 sidecar manifest via
`Checksums`) / `backup_verify` (archive hash first, fail-closed) / `restore` (manifest validation before any
write, zip-slip jail, conflict preview; archive-based, *not* bundle import — it covers the whole config tree) —
these three live in the optional `inspecto-backup` module (Standard+, EDITIONS `OPS-06`) and reach the switch
through the **`MaintenanceTaskProvider`** ServiceLoader seam: the built-in `switch` always wins first, and its
`default` arm consults discovered providers before throwing *unknown maintenance task*. A task claimed by two
providers is refused fail-closed rather than resolved by classpath order. Findings emit `maintenance.*` signals
for Alert Rules.

* **Dry run (MNT-1)** — `POST /jobs/{name}/trigger?dryRun=true` (v1 202 body echoes it); `JobContext.dryRun()`;
  tasks with no preview do nothing on a dry run (fail-closed).
  🔴 **A dry run and its sweep are two call sites of ONE predicate** (`PRUNE-PREVIEW-DRIFT-1`, closed
  2026-09-15). Every store-backed prune pair — `AcquisitionLedger` (both impls), `DbDeliveryReceiptStore`,
  `InMemoryDeliveryReceiptStore`, `InMemoryNotificationStore`, `DbDedupLedger` — now names its "prunable"
  rule once (`PRUNABLE` / `prunable(...)`) and both `countPrunable` and `prune` call it; the partition tasks
  were already one loop branching only at the delete. ⛔ Do not re-split them. ⚠ The worst case fixed:
  `dedup_prune`'s dry run reported the ledger's **total size** as its preview — `DbDedupLedger` had no
  `countPrunable` at all — so a preview of "holds 3" could precede a sweep that removed 1. Pinned by
  `DbDedupLedgerTest.previewMatchesTheSweep`, `AcquisitionLedgerPruneTest.previewMatchesTheSweep*` and
  `DbDeliveryReceiptStoreTest.countPrunablePreviewsAndPruneDeletes`.
* **A job of an unregistered type is skipped at BOOT, not the whole Space** (`DEMO-SPACE-PERSONAL-UNBOOTABLE-1`,
  2026-09-15). `JobService`'s constructor used to call `registry.create` for every enabled config and let the
  `unknown job type` exception escape — `SpaceManager` then logged *"Skipping space dir … failed to load"* and the
  Space vanished from `GET /spaces`. The demo space did exactly that on the core jar because ONE sample job
  (`ops_analytics_sample_job.toon`, `type: objects.analytics`, an `inspecto-ops` type) was in it. Now the
  constructor WARNs `'<job>' NOT hosted: job type '<type>' is not registered on this classpath (registered: …)`
  and continues; the config stays LISTED in `jobs()` (authored content, merely not hosted here). ⛔ Leniency is
  boot-only: `upsertJob` (an author is present) still refuses an unknown type. ⚠ Open design question, not
  decided here: whether a Space with unhosted jobs should show as `degraded` on the wire rather than silently
  complete.
* **Nightly chain (MNT-13)** — pure config: each link `on_signal: job.run.completed` +
  `when: "$signal.job == <prev> && $signal.outcome == SUCCESS"` (halt-on-failure by guard); shipped as a
  parameterized Job Template + `spaces/demo` instance.
* **`/health/details` (MNT-15)** — per-subsystem UP/DOWN/NOT_CONFIGURED (`HealthDetails`); overall DOWN iff any
  subsystem DOWN; auth-gated, deliberately not on the public-path allowlist. The bare `/health` probe stays
  public for the connectivity banner.
* **Growth trend (COULD, shipped 2026-07-23)** — `storage_trend` (`StorageTrendTask`) reads the
  `maintenance_storage` sample series `storage_report` accumulates: a two-point bytes/day slope per axis + total
  over a `window_days` window (default 30), a projected `warn_bytes` breach ETA, and the fastest-growing axes as
  archive candidates; emits `maintenance.storage.trend` (WARN) when the breach is within `warn_days` (default 14).
  Read-only, fail-soft on <2 samples. The catalog's `created_ms` (epoch millis) is the sortable/filterable key —
  ISO strings aren't reliably chronological across variable precision. Open COULD follow-ons: space-to-space
  comparison, predictive maintenance (the latter is AGT-5/self-healing territory, deliberately deferred).
### `incident_purge` — the archived-Incident retention sweep (MNT-14, shipped 2026-07-27)

The retention model is **a retention tier, NOT archive-is-terminal** (BACKLOG D5, decided 2026-07-25):
an `ARCHIVED` Incident carries a **retention window**, and expiry of that window is what makes it
purge-eligible. Archive-is-terminal would have made "archived" mean "kept forever" — exactly the posture the
NFR-7 compliance work has to be able to bound and evidence. `CLOSED→ARCHIVED` and `ARCHIVED→purge` are two
separate windows, and the sweep *enforces* a tier rather than owning a hardcoded age.

**Task:** `task: incident_purge`, `retention_days` **required** (no default — a defaulted window on the one
task that hard-deletes business records would be indefensible), optional `max_count` (default 1000) bounding
one run. Dry-run-first like every write task. Named `purge`, not `*_prune` like its siblings, because the
blast radius genuinely differs: the `*_prune` tasks trim housekeeping telemetry, this deletes operator
business records irreversibly.

Retention is **derived** — `closedAt + retention_days`, where `closedAt` is the archive timestamp the
terminal transition already stamps. No new column, no per-object expiry to keep in step. ⚠ Trade-off accepted
consciously: **shortening `retention_days` later retroactively makes older records eligible**, so the sweep
does not honour "what was promised when this was archived". If that guarantee is needed it becomes a stamped
attribute — cheap then, so not pre-built.

Two premises in the original scoping were **wrong**, and both are worth remembering as a pattern:

* "The blocker is building the `Archived` state" — it already shipped. `Workflow.defaultFor(INCIDENT)` has
  `ARCHIVED` as its sole terminal state, and terminal transitions already stamp `closedAt`.
* "G4 needs four new `JobService` store hooks, fail-CLOSED on partial attachment" — **no new hooks were
  needed at all.** `JobService.objects(ObjectService)` already existed and was already wired, and
  `ObjectService` holds all four stores as non-null final fields. ⚠ **There is therefore no partially
  attached cascade to fail closed on** — the engine is present or absent whole. Do not add per-store hooks
  beside `objects()`; four independently-nullable fields would *reintroduce* the half-cascade hazard this
  shape rules out.

The seams, all in `com.gamma.ops`:

| Seam | What it is | Why it is shaped that way |
|---|---|---|
| `ObjectQuery.closedBefore` + `oldestFirst`, entry point `ObjectQuery.purgeEligible(type,status,cutoff,limit)` | Eligibility selection | `ObjectQuery` is already the single shape driving both `matches()` (in-memory) and SQL `WHERE`, so the backends **cannot diverge** on the predicate. A 9-arg constructor still delegates to the canonical one, which is the only reason widening the record was non-breaking — **don't tidy that overload away.** |
| `ObjectService.purge(id, actor)` → `PurgeOutcome(notes, links, tagEdges)` | The cascade | `ObjectStore.delete` explicitly does not cascade; this service is the one place holding all four stores. |
| `NoteStore.deleteForTarget` · `LinkStore.removeAllIncident` · `TagAssignmentStore.removeAllForTarget` | Bulk delete-by-target | Shipped as **abstract** methods (a MAJOR widening of three `@PublicApi` interfaces) after verifying each has exactly two implementors and no test fakes. A silent no-op `default` would have orphaned rows quietly — the exact failure these prevent. |
| `ObjectService.ATTR_LEGAL_HOLD` + `hasLegalHold(o)` | Legal hold | Fail-safe: only `false`/`0`/`no`/`off` or blank clears a hold; anything else holds. The mistakes are asymmetric — wrongly keeping a record costs storage, wrongly purging one is unrecoverable. |

⚠ **Correctness comes from the cutoff, not the ordering.** Because `closedBefore` is in the `WHERE`, every
row a capped page returns is eligible whichever end of the corpus it came from — that alone kills the
"reports 0 prunable against a fully-expired corpus" failure, which was the worst outcome available here (a
silent, plausible-looking wrong answer). `oldestFirst` only decides *which* eligible rows a capped sweep
takes first.

Three further invariants the code enforces rather than merely documenting:

* **Dependents are deleted before the object.** If a later step fails the object still exists and the next
  sweep retries it; the reverse leaves notes and edges pointing at an id that no longer resolves.
* **Legal hold is re-checked inside `purge()`**, not only in the preview — a hold applied between preview
  and run has nowhere else left to take effect. `purge` throws on a held object; the task logs the refusal
  and continues, because a refusal *is* the hold working. The dry run reports held-but-expired as its own
  count ("12 eligible, 3 held"); an operator cannot trust a sweep whose arithmetic doesn't add up.
* **The store cannot filter on the hold at all** (attribute bag, not a column), so `purgeEligible` returns
  held rows by design and every caller must exclude them. That split is deliberate.
* ⚠ **A purge is NOT "all trace removed" (G3 — a stated decision).** The event ledger is append-only, so a
  purged Incident's `OBJECT_ACTIVITY` history — including its own purge record — outlives it permanently.
  The audit log is not the record being retention-managed. This is the first question a legal/DPA reviewer
  asks, so it is asserted by a test, not just written down.

Scoped to `ObjectType.INCIDENT` because `ARCHIVED` exists only in the Incident workflow; generalise when a
second type gains a terminal archive state.

**Operator-facing procedure** — the worked job TOON, the dry-run-first + legal-hold steps, and the G3
stance phrased for a legal/DPA reader live in
[`okf/backend/build-run/operations-reference.md`](../build-run/operations-reference.md) §*Retention &
purging*. ⚠ **Nothing schedules `incident_purge`, and nothing should.** That is the decision, not a
residual: a shipped default that hard-deletes business records is indefensible, so standing the job up is
an operator act exactly like `receipt_prune`.

## `consignment.process` — the third-party Consignment SPI (shipped 2026-08-04)

The Job Type that lets someone outside this repo do work over one committed Consignment, from the
[consignment-ELT plan](../../../archived-documents/plans-archive/consignment-elt-architecture.md) §14. It is the framework half of a
two-sided seam:

| Side | Type | Who writes it |
|---|---|---|
| Author | [`ConsignmentProcessor`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/ConsignmentProcessor.java) — `id()` + `process(ProcessorContext)` | third party, discovered by `ServiceLoader` |
| Framework | [`ConsignmentProcessJobType`](../../../../inspecto-engine/src/main/java/com/gamma/job/ConsignmentProcessJobType.java) — a `JobTypeProvider` | this repo, registered in `JobService` beside the other built-ins |

**Authors never touch `Job` or `JobContext`.** Nothing new was added to the Job framework for this: registration
reuses the existing `JobTypeProvider` seam (a class-based provider, as `SqlTemplateJobType` already is), so
`JobTypeRegistry`'s duplicate-id guard means a Job Pack can never displace it.

**How the Consignment id arrives without the author knowing about Signals.** `consignment_id` is a
`ParameterDecl` whose `deduce` expression is `$signal.batchId`, resolved by the existing `ParameterResolver`
against the firing Signal's payload — which `JobService.mirrorPipelineCommit` already populates for every
`pipeline.commit`. A manual run binds `consignment_id` in config instead; neither present ⇒ the run is REJECTED
before author code executes, because the parameter is `required`.

### `ProcessorContext` — what an author gets

`consignmentId()` · `outputs()` (the §11.3 registry) · `read()` · `summaries()` (guarded **and** persisted, see
below) · `log()` · `signals()` ·
`dryRun()`. `log`/`signals`/`dryRun` are **delegated member-by-member rather than exposing `JobContext`**: a
`job()` accessor would be one method instead of three, but it would leak the whole Job surface into a contract
every third-party processor binds to. `ArtifactRecorder` is deliberately *not* delegated — beside
`summaries()` it would give authors two plausible ways to emit the same thing. Signals are stamped with
`consignment_id` by the adapter.

### Reading is a narrow seam, not a `Connection`

[`ConsignmentReader`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/ConsignmentReader.java)
exposes `query(sql)` + `relations()`, **not** the JDBC `Connection` the plan originally specified. A raw handle
makes the read-modify-write §5.1 forbids trivially expressible, so the plan's own acceptance test ("a write
attempt fails") is unsatisfiable with one — and it leaks a far larger surface than the `job()` accessor rejected
above. Enforcement reuses what already existed rather than new plumbing:

- **`SqlGuard`** vets every query (single `SELECT`/`WITH`; no DDL/DML, no `read_*`/`copy`/`attach`/`set`).
- **`SqlSandbox`** provides the hardened connection (extensions off, memory/thread caps, query timeout).
- Relations come from the **§11.3 registry, not a directory glob** — a partition directory holds files from
  every Consignment that wrote that day, so a glob would silently widen the read past this unit of work. Only
  `LIVE` outputs are readable; a `LIVE` row whose file is missing is skipped with a warning rather than left to
  break every query over its relation.

⚠ **The sandbox is deliberately never `seal()`ed.** Sealing sets `enable_external_access=false`, which is why
`SqlOracle` materialises its inputs first — it can afford to, needing only column types (`LIMIT 0`). A processor
needs the actual rows, and copying a whole Consignment into scratch is the cost §11.3 exists to avoid. So
relations stay lazy views and `SqlGuard` keeps the blocked surface out. **This is invariant protection, not a
defence against hostile in-process code.**

### Summaries are guarded, then written

[`SummaryEmitter`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/SummaryEmitter.java) enforces
§7.2 at the seam, because non-composable measures produce *quietly* wrong numbers: `count` is mandatory on every
row, and every `Measure` must declare its `Composability` (`ADDITIVE` / `BUCKETED` / `COMPUTED_FROM_DETAIL`) —
undeclared is refused, never assumed. A measure whose name says it is not additive (`avg`, `ratio`, `p95`,
`distinct_*`, `min`/`max`, …) declared `ADDITIVE` is refused too. Every violation is reported at once, so a
refusal costs one repair round. `reconcile(outputs)` gives §7.2's conservation check for free — summed `count`
against the registry's detail rows — **reported, never thrown**, since summarising a filtered subset is legal.

**Storage is §7.3's `SummaryWriter`** (shipped 2026-08-04), kept separate from the guardrail — which is why the
guardrail could ship and be proved red before any format existed, and why its tests did not change when the sink
arrived. Validated rows become
`<dataDir>/_summaries/<target>/record_day=<d>/<consignmentId>_summary_out.parquet`, registered in the §11.3
registry **after** the file is revealed, under `table_name = "<target>__summary"`.

Four things to know before writing a processor that emits summaries:

- **Emit `record_day` on every row.** Without it the target is written as one **flat** file, which is the
  glob-everything read §7.3 exists to eliminate. A *mixed* target is written wholly flat (with a warning) rather
  than split across two layouts. This fallback is a deliberate operator call, taken against advice.
- **A `_measures.csv` sidecar beside each target carries composability to read time**, merged across
  Consignments, so a reader can tell it must not sum a `COMPUTED_FROM_DETAIL` column. Guarding emit alone never
  stopped a reader summing an average.
- **Target and measure names must match `[A-Za-z_][A-Za-z0-9_]{0,127}`** — they become SQL identifiers *and a
  directory name*, so anything else is refused rather than escaped, and a name cannot be both a grain key and a
  measure.
- **A summary-write failure fails the Run**, unlike the best-effort registry write: the numbers are the
  processor's output, so losing them silently would make a green Run a lie. Dry runs validate and write nothing.

⚠ **§7.4's rollup cache is still deliberately absent** — it is a cache deletable without data loss, so nothing
depends on it, and read-time aggregation has not been shown too slow. Building it would add the one
mutable-looking thing in the system ahead of evidence.

⚠ **No data root ⇒ summary persistence is off** (the no-arg `ConsignmentProcessJobType`); the guardrail still runs
and the Run warns that validated rows were not stored.

## Three corrections worth keeping about commit-fired work

🔴 **There is no `on_commit` Job trigger, and `ON_COMMIT_SAME_GRAPH` is not the at-rest enforcement.**
`on_commit` is a **Signal, not an edge** (spelled `PipelineRel.ON_COMMIT`, cross-pipeline only, until Phase 7 — it has no producer as an edge). Commit-fired Jobs
ride the **Signal** bus — `JobService.mirrorPipelineCommit` turns a commit into a signal a Job trigger
matches. `ON_COMMIT_SAME_GRAPH` is a **graph-structure refusal** (it stops an edge pointing back into its own
graph); ⛔ do not cite it as the thing that keeps Job work at rest. The real reason Jobs are the at-rest seam
is simply that a Job is the only SPI that reads data already written.

⚠ **Do not grow the `EventType` enum for new facts — emit a `Signal`.** And **per-file facts belong in a
ledger row, not a signal**: a signal per file floods the bus on a large Consignment. There is deliberately no
signal-type constants class; the type is a string the emitter owns.

**`ProcessorContext` withholds more than the connection.** Beyond the writable `Connection` and `job()`, it
also does not expose `PipelineConfig` (a processor must read the **pinned** manifest values, not live config —
see the partition-drift rule in [consignment-addressing](../engine/consignment-addressing.md)), the path
builders, or `Batch`/`PipelineNode`. Each omission is a decision, not an oversight.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

## Open rows this concept owns — the job-path semantics change (2026-09-16)

`JOB-DIR-CWD-CONTAINMENT-1` made `PathJail.resolveJobPath` the single rule for a job's relative paths.
The survey that followed ([`superpower/job-path-compat-survey.md`](../../../superpower/job-path-compat-survey.md))
found the change reaches further than the decision that authorised it. ⛔ **`JOB_PATH_KEYS` is SIX keys,
not the five the decision names** — the extra one is `pipeline_config`, the most common path key in
committed configs.

- **`JOB-PATH-COMPAT-SURVEY-1`** (P1) — on a deployed tree, **every** relative path value in **every**
  committed job config now refuses: once the old path exists, no committed value resolves identically, so
  the deliberate ambiguous-case branch fires for all 28. On a fresh checkout 15 silently re-point instead.
- **`JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1`** (P1) — `PipelineJobRunner:242` and `:266` pass
  `pipeline_config` and `data_dir` on with **no `PathJail` call at either site**: 19 of 33 values gated
  under one rule and run under another, and a containment hole. ⚠ Its javadoc at `:508` claims job configs
  bypass `ConfigSafetyValidator`, which contradicts that validator having a `checkJob`.
- **`JOB-PATH-COMPACTOR-UNJAILED-1`** — `PartitionCompactor:57` and `ReferenceCompactor:91` walk and
  delete under a raw `Path.of(cfg.require("dir"))`. Predates the change, which is why looking at the
  change did not find it.
- **`JOB-PATH-PATCH-ROUTE-WRONG-BASE-1`** — `ConfigWriteRoutes:370` uses `target.getParent()` where
  `JobRoutes:381` uses the Space root: one value, two gates, two answers.
- **`JOB-PATH-GATE-BLIND-KEYS-1`** — `RawConfig.str(raw, "job."+k)` is a dotted path from the root, so 11
  values under `params:` are invisible to the gate; `archive_dir` is resolved at run time but is not in
  `JOB_PATH_KEYS`. *(The `out_dir` third of this row was discharged 2026-09-16 — see the shipped row
  below. The other two blind spots stand.)*
- ~~**`JOB-PATH-REPORT-ENRICH-SPLIT-1`**~~ ✅ **SHIPPED 2026-09-16** — see below.
- **`JOB-PATH-DEMO-CONFIG-REPOINT-1`** — re-point the remaining **32** committed values space-relative.
  ⛔ Land it with whichever runtime row lands last, or the configs refuse in between. *(33 → 32: the one
  `out_dir` value moved with its runtime. ⛔ Not licence to re-point the rest early.)*
  🔴 **Three of the 32 are now orphaned the OTHER way** — `JOB-PATH-BACKUPTASK-SPLIT-1` (`3f384182`)
  moved `BackupTask` without them, so `config_backup` fails at run (`BackupTask:93`, `dir`, refused
  state-independently because `spaces/demo/config` is a committed directory) and `backup_verify`
  silently re-points and returns a green "no archive to verify". **The marker only ever named one of
  the two orders.** Driven, not mirrored — see the BACKLOG row and §3.1 of the survey.

### `JOB-PATH-REPORT-ENRICH-SPLIT-1` — the last two readers, and what they taught

`out_dir` (`ReportJob`) was on the plain `PathJail.requireUnderAny` — containment only,
working-directory-relative — and `config` (`EnrichJob`) went to `EnrichmentConfig.load` with **no jail
call at all**, a containment hole rather than a base mismatch. Both now go through
`PathJail.requireJobPathUnderAny(allowedRoots(), SpaceConfigRoot.current(), …)`, and **only then** did the
two keys join `JOB_PATH_KEYS`.

⛔ **The ordering is the durable rule, and it is now stated in that field's javadoc: reader first, then
the list.** Adding a key to `JOB_PATH_KEYS` while its runtime still resolves against the process working
directory manufactures precisely the gate/runtime split `PathJail.resolveJobPath` exists to end — the
gate would refuse, or accept, a draft the run then treats differently.

🔴 **`resolveJobPath` refuses the ambiguous case only when the old path EXISTS.** This is documented
behaviour, but its consequence was not: on a *fresh* tree — no old directory — a stale value does not
refuse, it **silently re-points**, and for an output directory that means an artifact written where
nobody looks while the job reports SUCCESS. The survey's fresh/deployed columns say this; a row written
from the deployed column alone called `maintenance_report_job.toon` a refusal, and it is not one here.
⚠ When judging a value's blast radius, read the column that matches the tree you are on, and check
`Files.exists` on the CWD-relative path — that single predicate is what picks the column.
