---
type: Concept
title: Jobs & Scheduling — The Job Framework
description: Part 1 of 4 of Jobs & Scheduling: The Job Framework (P0–P3, shipped 2026-07-09; feat! → 5.0)
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
---

# Jobs & Scheduling — The Job Framework

Part 1 of 4 of [Jobs & Scheduling](spec-jobs.md) · next: [The parameter contract, failure handling and single-seam properties](spec-jobs-parameters-failure.md).

## The Job Framework (P0–P3, shipped 2026-07-09; `feat!` → 5.0)

Design of record (all phases + resolved decisions + TOON config gallery):
[`job-framework-design.md`](../../../../archived-documents/plans-archive/job-framework-design.md). The durable model:

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
  `GET /jobs/packs`, `POST /jobs/packs/rescan`. **2026-09-25, breaking: trust gate T1.** A jar loads only
  when the SHA-256 of its staged bytes is listed in `-Djobs.packs.allowlist`. With no allowlist, every jar
  is refused. The file is re-read on every rescan, so removing a line unloads the pack. Boot is refused when
  the allowlist is inside the packs dir or under a control-plane write root. Refused jars show as
  `state: "rejected"` rows with a cause. The gate is the same for every pack kind: Job Types, expression
  tokens, node types, node executors, and (since 2026-09-25) **parsers**, the fifth kind. Details:
  [parser-plugins.md](../elt/spec-parsing.md), *Drop-in parser jars*.
  **2026-09-25: the staged copies live under a server-owned dir** (trust design slice P0), not the system
  temp dir that any local process can write: `<auditDir>/job-packs-staging` (the Space's own audit dir), or
  `-Djobs.packs.stagingDir` when set, one unique `job-packs-*` dir per manager inside it. A staging root
  inside the packs dir refuses boot, because a packs-dir writer could then rewrite staged bytes after they
  were hashed. ⚠ A crashed process leaves its staged copies behind; they are inert (nothing loads from a
  dir it did not create this run) and can be deleted by hand.
  **2026-07-20 SHIPPED the classloader half of quiesce:**
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
  **2026-09-24 FIXED a verify/load TOCTOU (SEC-7 signing gate):** `load()` used to signature-verify the
  jar in the WATCHED dir and only then copy it to staging, so a jar swapped between verify and copy loaded
  unverified. It now **stages first** (a fresh `createTempFile` in the owner-only staging dir, out of the
  watched-dir writer's reach), then hashes **and** `verifySignature`s the staged
  copy and loads exactly those bytes; the recorded `hash` (inventory, `job.pack.*` payloads) is the staged
  file's sha256, and a verification failure deletes the staged copy. The watched jar's hash remains only
  the rescan change trigger — if it differs from the recorded one after a mid-load swap, the next rescan
  reloads. Pinned by `JobPackStagingTest` (package-private `beforeStage`/`afterStage` seams swap the
  watched file inside the window; both mutants — the old order, and staging first but verifying the
  watched jar — go red).
* **`sql.template`** — the built-in templated-SQL Job Type and first real artifact producer; its
  parameters are scanned from the SQL itself. 🔴 Its SQL is sandboxed at run time since 2026-09-24
  (`SQL-TEMPLATE-SANDBOX-1`): the substituted text must pass `SqlGuard` (one read-only `SELECT`/`WITH`, the
  `transform.sql` allow-list), and the connection is sealed to the data root — see
  [Auth & Security](../access/admin-guide-auth-security.md#the-sqltemplate-job-runs-behind-sqlguard-and-a-sealed-connection-sql-template-sandbox-1-2026-09-24).
  **Incremental mode by per-day partitions (operator, 2026-10-10; `ASSURE-PACK-TELCO-FRAUD-1` (b)).**
  `incremental: {by: day, column: <date col>, lookback: N, retain: M}` makes a run replace only the last `N` days
  (ending on the trigger's `day` param, declared for incremental Jobs only, else today UTC) of its sink,
  laid out Hive-style as `<sink>/<column>=YYYY-MM-DD/part-<run>.parquet` with the date column held in the
  folder name only, so the store reader (`hive_partitioning=true`) gives it back as a DATE — as the
  **last** column. Every older day is never opened (byte-identical, pinned). Output rows outside the window
  are dropped and counted in the run message: a **late row older than the lookback is not picked up** —
  re-run with a wider lookback (or a `day`) to backfill it. Seams: `IncrementalSpec` (the block, flattened
  to `incremental.*` params by `JobConfig.fromMap` and re-nested by `toMap`), `IncrementalSink` (lock,
  layout, replace, recovery). **Save gates (422):** `by` must be `day`, `lookback` 1..366, `column` a plain
  identifier that appears in the SQL, only on `sql.template`; and `POST/PUT /jobs` refuses a sink that is
  not empty or already `<column>=` day folders (a flat snapshot, another layout, or another incremental
  Job's ownership marker `.sql-template-incremental`). That the column is really in the output and typed
  DATE is checked at run, **before the sink is touched** — the SQL's `$params` cannot be resolved at save.
  **Concurrency / crash safety:** a replace holds the sink lock (an in-JVM monitor plus a `FileLock` on
  `<sink>/.incremental.lock`); per day it stages `*.parquet.tmp`, hides the live files as `*.stale`,
  reveals by one atomic move, then drops the stale files. Recovery at the start of every replace: a
  surviving `.tmp` means the reveal never happened → restore the old files; only `.stale` → drop them.
  So a crash leaves the old or the new partition, never a mix. The hide→reveal holds the store's
  `SnapshotSwapLock` write lock, so it waits for a `sql.template` Job reading that store
  (`JOB-SNAPSHOT-SWAP-READER-RACE-1`, 2026-11-14; see "Snapshot swaps and readers" below). Measured 2026-10-10 (6M source rows →
  3M output rows over 30 days, aggregate SQL that reads the whole source): full snapshot 1.6-1.7 s and
  22.8 MB rewritten per run; incremental `lookback: 1` 0.9-1.0 s and ~0.8 MB (one day) rewritten; the
  30-day backfill took 3.0 s. The read side is unchanged unless the SQL filters its source by the window.
  **Enabled in the telco-fraud pack (2026-10-11):** all 14 detection Jobs run `incremental: {by: day,
  column: window_date, lookback: 1, retain: 31}` with `day: "$yesterday"` (the same day as `window_start`; a manual or
  golden run passes `day` beside the literal window), no longer read their own sink, and ship their zero-row
  schema seed as `data/fraud_<typology>/window_date=1970-01-01/seed.parquet` (a placeholder day the Job
  never replaces; a flat `seed.parquet` is refused by the layout gate). The Dataset reader leaves
  `hive_partitioning` unset and DuckDB auto-detects the `window_date=` folders, so `window_date` reaches
  Alert Rules, KPIs (`timeField: window_date`) and BI as a DATE. Each Job carries `retain: 31` (its old
  `retention_days` horizon: the window plus 30 earlier days), so evidence still ages out and a key heals on the same day (pinned by the golden test's day-31 and day-32 runs).
  **Retention: `retain: M` (2026-10-11).** Optional; an integer from `lookback` to 3660, refused 422 at save
  otherwise; absent = keep every day. After the replace, under the same lock, `IncrementalSink.prune`
  deletes each `<column>=YYYY-MM-DD` folder dated before `day - (M - 1)` (so M days are kept). Only folders
  whose name is exactly `<column>=<ISO date>`; a folder holding `seed.parquet` (a template's zero-row
  schema seed) and every other entry, dot-files (marker, lock) included, are never touched. A failed
  delete is logged (WARN) and skipped — retention never fails the run. The delete never leaves the
  partition folder: a symlink or other reparse point (a Windows junction, which Java reports as
  `isOther()`, not a symlink) is removed as a link and never descended, and an entry whose real path is
  outside the folder is left (WARN). A reader racing a prune may get a transient file-not-found for an
  expired day; it is retry-safe and never returns wrong data. Pinned by
  `SqlTemplateIncrementalTest` (prune, seed / non-matching folders kept, failure tolerated, validation) and
  `ControlApiIncrementalJobTest` (422).
* **`expectation.evaluate`** (2026-10-14) - puts the data-quality Expectations on a clock: the same evaluation as `POST /expectations/evaluate` (`ExpectationRunner`, shared with the route): every enabled Expectation is checked against its target's at-rest data, `lastResult` is persisted, a failure opens a correlated Incident (deduped while one is open) and emits `expectation.violated`; then `expectation.evaluate.completed`. Optional param `expectation` narrows to one. Lives in the `inspecto` module (ServiceLoader, `ExpectationEvaluateJobType`), resolves the Space's config and data roots at run time, reaches Incidents through the optional `objects` grant (absent: the breach is evented, not promoted). A dry run evaluates nothing and says so. The telco-fraud template ships it as `fraud_expectation_evaluate` (01:45).
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
    (count/avg_ms) · `impact` (records_affected) · `impact.<ISO 4217>` (count / suspected / confirmed /
    recovered / prevented / outstanding — one axis per currency, WS-10). `value` is DOUBLE because an impact
    amount isn't a count. `key` is **quoted** in the DDL — a column name deliberately kept, quoted so no dialect's
    reserved-word list can bite (cf. the `day`/`trigger` gotchas in `module/engine/spec-engine-gotchas.md`).
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
  * **The impact ledger rides the same run (WS-10, `ASSURE-IMPACT-LEDGER-1`, 2026-09-26).** Beside the tall
    sample it writes `<dataDir>/impact_ledger/impact_<epochMs>_out.parquet` and stamps an **`impact_ledger`**
    Dataset: **one row per Incident/Case carrying a typed impact, per run** — `sampled_at, object_id,
    object_type, status, disposition, category, created_at, currency, suspected, confirmed, recovered,
    prevented, outstanding, period`, amounts `DECIMAL(21,6)` (the exact bounds `Impact` admits, so nothing
    rounds; `outstanding` computed at write, never read from storage). Chosen as **snapshots, not current
    values only**: the latest run is `sampled_at = max(sampled_at)`, and recovery-over-time falls out of the
    same Dataset. The Disposition is the Incident's `attributes.disposition`, else the Case's Findings value.
    Same retention (`retention_days`, prefix `impact_`), the `types` filter applies (only INCIDENT / CASE ever
    produce rows), and it works on **either** objects backend because it reads through `ObjectService`, not the
    table. It reads **every** impacted object, paged through `ObjectService.allMatching` (it once stopped
    silently at `ObjectQuery.MAX_LIMIT` = 10 000 per type — `ASSURE-IMPACT-LEDGER-RESIDUALS-1` (1), fixed
    2026-09-28, `ObjectsAnalyticsJobTest.ledgerReadsEveryImpactedObjectBeyondOneQueryPage`); the rows
    themselves are still collected in memory before the write. ⚠ **Freshness = the job's cadence**: a Measure over it lags a live edit until the next run — there is
    still no live view of `inspecto_ops_objects` (single-writer DB, the non-goal below). **Decided 2026-09-28
    (session, `ASSURE-IMPACT-LEDGER-RESIDUALS-1` (2)): that stays the design** — refresh on demand is
    `POST /jobs/{id}/trigger` (`canOperateRuns`) on the Space's `objects.analytics` Job; a live relation over
    the objects table is the row-level surface the non-goal below declines, and event-driven re-writes would
    add a Parquet file per impact edit. Reopen only on a named consumer that needs sub-cadence figures. Pinned by
    `ObjectsAnalyticsJobTest.writesTheImpactLedgerAsADatasetAnyMeasureCanRead` (read back through
    `DatasetRelation`, plus a per-currency `sum(outstanding)` over the latest snapshot).
  * 🔴 **Both `inspecto-ops` Job Types declare `requires: objects`** (fixed 2026-09-28). They reach the engine
    only through `JobContext.services()`, which holds exactly what the descriptor requires (S1-2); declaring
    nothing, **every `objects.analytics` and `caserule.evaluate` run in a deployed build failed** "needs the
    space Object Engine … not installed" — so the ledger was never written outside tests, which construct the
    Job directly and never pass the grant. Found by the impact-ledger browser pass. `CollectorService` now
    opens the Object Engine and registers the `objects` service **before** the boot-time JobService, which
    validates `requires:` at registration. Pinned end to end by `OpsJobTypesRunInAServiceTest` (lazy and boot
    JobService, a real `CollectorService`).
  * A write failure emits `objects.analytics.completed` at `WARN` **and rethrows** — the write *is* the work,
    so a swallowed failure would report a silent no-op success. Dry run computes the rows and writes nothing.
  * **Deliberate non-goals:** no Parquet/view surface for *raw* `inspecto_ops_objects` rows (row-level export
    has PII/ACL implications; these are aggregates) · no change to `GET /objects/analytics` or the mail UI ·
    no UI work at all.

**Snapshot swaps and readers (`JOB-SNAPSHOT-SWAP-READER-RACE-1`, 2026-11-14).** Every snapshot writer
(`SqlTemplateJob.swapIn`, `MaterializeTask`, `ScoreOutputDirs.swapIn` for the `_latest` score Datasets,
`IncrementalSink.swapDay`) publishes by hiding the old `*.parquet` as `*.stale`, revealing the new file, then
deleting the hidden ones. A reader that resolved its file list before the hide holds the old **path**, and DuckDB
opens a file by path at bind and again at scan, so the rename alone fails it with "No files found that match the
pattern" — the error the showcase logged. A reader that already has the file open keeps reading it. A glob read in
the hide window does not see an empty Dataset either: it fails the same way. ⛔ So a grace period for `*.stale`
files fixes nothing; the path is gone at the rename.

`SnapshotSwapLock` (`platform/inspecto-engine/src/main/java/com/gamma/job/`) is one fair read/write lock per store
directory for the JVM. A `sql.template` Job holds the read lock on its source stores from the moment it pins
them until its `CREATE TABLE … AS` has finished, and releases it before its own swap (so a Job reading the
store it writes never waits on itself). Every swap holds the write lock only around hide and reveal. Read locks
are taken in path order, and a writer holds no other SnapshotSwapLock while it waits (`IncrementalSink` waits holding its own
sink monitor and file lock, which no reader takes), so there is no deadlock.
**Every reader and writer, since 2026-11-15** (`SNAPSHOT-SWAP-UNLOCKED-READERS-1`). Three ways to take the lock:
- **Pin:** `ConsignmentSelector` lists and pins a store's files under the read lock. A reader that holds no store yet
  queues behind a writer already waiting, so new readers cannot starve a publish; only a reader that already holds a
  store may overtake queued writers (waiting just for a swap under way), so readers holding several stores cannot
  deadlock. While a publish is queued, a new reader of that store waits for it too.
- **Interactive readers** (BI, Alert Rule measures, Link Analysis, the DB browser — everything that runs through
  `QueryExecutor`): pin under the lock, then scan **unlocked**; if the scan fails because a pinned file is gone,
  re-render the relation (`DatasetRelation` registers how) and run it once more inside `SnapshotSwapLock.hold()`.
  Measured on a 40M-row store with a 19.7 s query: holding the lock through the scan made a publish wait 18.5-21.2 s
  and queued a 1 s-later COUNT behind it for ~20 s; not holding it, a publish took 10-26 ms.
- **Job readers** (`sql.template`, `materialize`, a Pipeline's `source_store`, `expectation.evaluate`): hold
  the store from the pin until the scan ends; a publish waits for them.
- **Readers on their own connection** (since 2026-11-15, `SNAPSHOT-SWAP-READER-RESIDUALS-1`): `exchange` snapshot
  publish, `recon.run` (renders its sides afresh inside the hold, never a cached spec), `postgres.publish`, and an
  enrichment's reference and Stage-1 input globs (a glob read pins the store, so it waits for a compaction under way)
  hold from pin to scan like a Job. A Link Analysis index build re-renders its relation on the build thread
  (`DatasetProvider.readHeld`) and holds it until the build has read it. A caller that wraps a registered relation in
  more SQL still gets the retry: every relation the wrapper embeds is re-rendered in place. The registry holds the
  last 1,024 relations strongly (a wrapper keeps only its own string, so a weak entry would die at the next GC); two
  renders of an identical relation share one entry, and the newest render is kept.
  `retire_superseded` deletes a file under `SnapshotSwapLock.writeAbove`, waiting for a reader that pinned it
  before it was superseded.
- **Run-level snapshots** (since 2026-11-17, Platform Services Stage 2, MoSCoW S6): a Job reading through the
  `datasets` Platform Service (`DatasetAccess`) gets `RunSnapshot`, so the first read of a Dataset in a Run pins it and
  every later read in the same Run returns that pin; the next Run pins afresh. Holding the read lock for the Run was
  rejected: a publish would then wait for the slowest Run reading its store, up to the Job deadline (24 h ceiling).
  A pin keeps the **files** instead: inside `hold()`, under the store's read lock and only while it enumerates and
  links, each file gets a hard link at `<dataRoot>/.run-snapshots/<uuid>/<n>/<its path under the data root>`, and the
  relation reads the links. The path below `<n>/` keeps the `key=value` folders, so a Hive-partitioned store (a sink
  written with `PARTITION_BY`) reads the same partition columns pinned as per call. A swap's rename or a retirement's
  delete then removes only the store's name for the file; the Run's name keeps the bytes until the Run ends. Keeping
  the `*.stale` files longer would not do it (the path is gone at the rename, see above). So a swap waits only for a
  pin under way (list and link one Dataset), never for a Run.
  **Where hard links fail** (no hard links on the file system, a store on another volume) the file is **copied, but not
  under the lock**: it is opened under the lock and copied from that handle after the lock is released. Copying from
  the path after the release would be wrong: a swap could rename the file away in between and the pin would lose it.
  An open handle is not reached by a rename or a delete, so the copy holds exactly the pinned bytes. A pin that would
  copy more than 1 GiB, or more than 256 files (each is held open until the copy ends, so a store of many small
  files would meet the open-file limit), is not made: that Dataset is read per call for the rest of the Run, with a WARN, rather than
  failing the Run, so no Run that worked before Run-level snapshots fails because the host cannot link. Each pin that
  copies logs one WARN. A failed pin deletes its partial `<n>/` folder.
  The Run's relation is not registered with `QueryExecutor.repinnable`: a lost-pin retry may never move a Run onto a
  newer snapshot (and with the links in place no pin is lost). Released by `JobService` in the Run's `finally` on
  success, failure, cancel (`interruptRuns`) and deadline, and by `PackTestHarness` when its Run returns; a read after
  that is refused. A live Run holds an OS file lock on `<uuid>/.lock`; the next Run that pins in the data root deletes
  every other Run folder whose lock it can take (a crash, a close that could not delete), in this process or another
  (a scale-out or HA peer on the same data root), and never one younger than a minute, whose owner may not have
  locked it yet. The sweep never opens the lock file of a live Run of its own process (an in-process set): on Linux,
  closing any channel to a file releases every lock the process holds on it, so one probe would unlock the Run for
  every other process (reproduced in an `eclipse-temurin` container; Windows locks belong to the handle). Every
  data-root walk skips `.run-snapshots/` as it skips `.duckdb_tmp/` (`DuckDbUtil.internalDir`): backup, the storage
  report, `cleanup`, the object-store export and `file_repository_audit`. Retries inside one Run share its pins.
  **Covered:** every Job Type declaring `requires: [datasets]` — today only Job Pack types; no built-in Job Type reads
  through `DatasetAccess`. **Not covered, per-call as before:** a `view`-backed Dataset (no file list to pin), a
  pack-contributed `DatasetAccess`, and a pack Step in a Pipeline (its grant is taken at load time, not per Run). Every
  built-in reader above (`sql.template`, `materialize`, `source_store`, `expectation.evaluate`, `recon.run`,
  `exchange`, `postgres.publish`, enrichment, the scoring and report Jobs, Link Analysis index builds, every
  `QueryExecutor` reader) keeps its own rule. ⚠ A link shares the file's bytes: a writer must publish by rename, never
  rewrite a live `*.parquet` in place (the writers listed below publish by rename or write new files).
Every writer that hides, replaces or deletes a live `*.parquet` holds the write lock: the snapshot swaps,
`ReferenceCompactor`, `PartitionCompactor`, `DatasetPeriod` replace and restore, `IncrementalSink` prune and
rollback, `ScoreOutputWriter` prune; `ScoreOutputWriter` also sweeps a crashed run's `.tmp` / `.stale`. A swap
refuses a thread that already holds a read lock on the same store instead of waiting forever. `<store>/database`
keys as `<store>`.
Limits: a publish waits for the readers in flight when it queued, plus any reader that overtook it while holding
another store (bounded by the writer's deadline, 24 h by default); runs of one score model take turns (a monitor per
history directory), so the leftover sweep never meets another run's file;
one JVM only; a Link Analysis index build holds its store for the whole build, so a publish queued behind it, and
new pins of that store behind the publish, wait for the build to end. Hidden files
never match the readers' `**/*.parquet` glob and are not counted as storage.
