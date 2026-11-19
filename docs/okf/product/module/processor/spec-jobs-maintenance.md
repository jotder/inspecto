---
type: Concept
title: Jobs & Scheduling — Maintenance jobs and the push and process jobs
description: Part 3 of 4 of Jobs & Scheduling: Maintenance jobs (MNT, shipped 2026-07-12); objectstore.export — the push post-action (shipped 2026-09-24); consignment.process — the third-party Consignment SPI (shipped 2026-08-04); Three corrections worth keeping about commit-fired work
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
---

# Jobs & Scheduling — Maintenance jobs and the push and process jobs

Part 3 of 4 of [Jobs & Scheduling](spec-jobs.md) · previous: [The parameter contract, failure handling and single-seam properties](spec-jobs-parameters-failure.md) · next: [Open rows this concept owns](spec-jobs-open-rows.md).

## Maintenance jobs (MNT, shipped 2026-07-12)

System maintenance is **tasks on the `maintenance` job type, never shell scripts or OS cron**. Task library:
`cleanup` (retention knobs `max_count`/`max_size`/`archive_dir`/`min_keep` — the newest N are never retired),
`ledger_prune`, `runlog_prune`, `status_prune` (the run-timestamped status CSVs under each Pipeline's
`status/`, never the persistent `_commits.log`), `notification_prune`, `receipt_prune`, `event_prune` (COMPLY-3: the
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
these three live in the optional `inspecto-backup` module (Professional+, EDITIONS `OPS-06`) and reach the switch
through the **`MaintenanceTaskProvider`** ServiceLoader seam: the built-in `switch` always wins first, and its
`default` arm consults discovered providers before throwing *unknown maintenance task*. A task claimed by two
providers is refused fail-closed rather than resolved by classpath order. Findings emit `maintenance.*` signals
for Alert Rules. A `backup`'s `maintenance_backups` catalog row registers in the running Space's own registry
(`SpaceConfigRoot.current()`, beside the Space's data root), never the JVM-wide `-Dassist.write.root`
(`BACKUP-CATALOG-SPACE-ROOT-1`, 2026-09-25).

🔴 **`restore` and `event_prune` are administrator-authored** (`MAINT-RESTORE-ESCALATION-1`, 2026-09-27). A
`restore`'s only integrity check is the sidecar manifest, which carries **its own** hash — so it proves the archive
is intact, never that the product wrote it: anyone who can place a zip + sidecar under a jailed root can make one
"valid". Restored over the config root with `overwrite: true`, a planted archive rewrote `roles.toon`, and a `power`
user (canAuthorWorkbench + canOperateRuns) granted themselves `canAdminister` — proven over real HTTP. Authoring or
editing a Job whose task is in `JobRoutes.isAdministerOnlyMaintenance` (`event_prune`, `restore`) needs
`canAdminister` at `POST`/`PUT /jobs` and `/config/write` type `job`, judged **lower-cased** as `MaintenanceJob`
dispatches it (`RESTORE` runs the restore). ⚠ Every Job-authoring door must ask that predicate — the import doors
included (through `ImportCapabilityGuard`). Other tasks judged: `backup` / `backup_verify` read and write only
archives, the prunes and `compact` forget or merge data but write no config — authoring-level. Test:
`inspecto-backup` `ControlApiRestoreJobGateTest` (the only classpath holding `restore`, so the admin probe really
runs it).

🔴 **Jobs carry a server-stamped author, and the runner re-checks it** (`MAINT-TASK-AUTHORITY-1`, 2026-09-27;
session decisions, overturnable). Seam: `control/JobAuthority.java` + `JobService.RunAuthority`.
* **Stamping.** Every Job write door — `POST`/`PUT /jobs`, `/config/write` + `/config/patch` type `job`,
  `/bundle/import` job items and pipeline-closure files, `/import`, `/pipelines/import`, `/spaces/import` —
  strips any client-sent `createdBy` / `updatedBy` / `updatedByRoles` (`JobConfig.AUTHOR_KEYS`, carried in the
  `job:` section like any param) and, when a Subject is attached, stamps `updatedBy` = the Subject id,
  `updatedByRoles` = its held (table-backed) role names, `createdBy` = the stored Job's (or the writer on a create;
  a raw-file import always stamps the importer). No Subject (Personal / no Authenticator) ⇒ stripped, not stamped.
  ⚠ A raw-file import re-encodes each `*_job.toon` (comments are lost), as `DecisionRuleGuard.guardImport` does.
  The keys are `ParameterResolver.FRAMEWORK_KEYS`, never an "undeclared parameter" warning.
* **Run-time re-check.** `ControlApi` installs `JobAuthority.runAuthority` into `JobService` (process-wide);
  `executeRun` consults it for EVERY run path (cron, event, signal, manual, replay, catch-up, dry run) before the
  parameter ladder. A Job for which `JobRoutes.requiresAdminister` holds runs only if its `updatedByRoles`,
  re-resolved against the bound Space's role table AS IT IS NOW (seed ∪ `roles.toon`, deny grants removed — the
  `PendingChanges.authorNow` idiom), still grant `canAdminister`. Otherwise the run is `REJECTED` with the reason,
  a `job.run.rejected` signal, and an `AUDIT` event `job.run.refused` (actor = the last editor). Inert while no
  Authenticator is active (behaves as before). ⚠ The re-check sees the ROLE TABLE changing, never an identity
  provider dropping a user from a group — the server holds no user → role store; the roles recorded at save are
  what is re-resolved. A Subject whose Authenticator stamps no held roles records none, so its admin-only Jobs are
  refused (fail closed).
* 🔴 **Migration: existing `restore` / `event_prune` Jobs (and config-root cleanups) PAUSE on an authenticated
  server until an administrator re-saves them** — a Job with no recorded author is refused (`records no author`,
  WARN logged once per Job). Safe by default; a hand-edited file on disk is trusted like any file on disk.
* **`cleanup` over the config root is administrator-only.** Content-aware, not the whole task:
  `JobAuthority.cleanupTouchesConfigRoot` resolves `dir` and `archive_dir` (a relative value against EVERY base a
  runner may use — the config root, `SpaceConfigRoot.current()` as `CleanupTask` does, and the working directory,
  its legacy fallback), canonicalises both sides (real path of the longest existing prefix, lower-cased on
  Windows) and flags a path inside the config root — which holds every `ReservedConfigPaths` entry — or an ANCESTOR
  of it (a sweep that walks down into it). `archive_dir` counts because archiving INTO the config tree plants
  files there. Such a cleanup needs `canAdminister` at every authoring door (same predicate, config root =
  `Roles.configRoot(ex)`, the root the gate resolves roles against) and at run time; every other cleanup stays
  `canAuthorWorkbench`.
Test: `ControlApiJobAuthorityTest` (stamping at four doors, revocation, legacy refusal, cleanup gate + run-time).

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
  ISO strings aren't reliably chronological across variable precision. The series reader is `StorageSeries`,
  shared with `space.comparison` (next bullet) so the two reports' slopes cannot disagree. Open COULD
  follow-on: predictive maintenance (AGT-5/self-healing territory, deliberately deferred).
* **Space comparison (COULD, shipped 2026-09-24)** — the `space.comparison` **Job Type** (`SpaceComparisonJob`)
  compares the storage growth of two or more Spaces per axis over the same `maintenance_storage` series:
  each Space's latest bytes and bytes/day, the spread (max − min) and the fastest grower; `top` axes by
  spread, optional `axes` filter, `window_days` (default 30). ⛔ Storage growth only — **not** a config or
  Pipeline diff. Fail-soft **per Space**: one with no history or < 2 in-window samples is reported *not
  comparable* and the rest still compare. Output is the Run message plus one `space.comparison.completed`
  signal (INFO) in the requesting Space — no Dataset, no catalog rows, no Run Artifacts (a cross-Space
  residency call, deliberately not taken).
  🔴 **Crossing the Space boundary goes through a grant, never a lookup.** In-process code can reach any hosted
  Space and `SpaceConfigRoot` deliberately enumerates none, so the engine never resolves another Space: a run
  reads only the data roots in its `SpaceStorageAccess` grant, checks EVERY requested Space against it
  **before reading any**, and fails the whole run (`SecurityException`) on a refusal. The only grant that
  names another Space is built by **`POST /space-comparisons`** (`SpaceComparisonRoutes`), gated on
  **`canAdminister`** — a cross-Space aggregate crosses the isolation "reads are open" rests on (CC6) — which
  resolves the named Spaces only AFTER the gate, then runs via `JobService.triggerSpaceComparisonRun`
  (202 + `runId`, the `triggerMaterializeRun` idiom). ⚠ **An authored or scheduled `space.comparison` job is
  refused by design**: the registered type carries `SpaceStorageAccess.ownSpaceOnly`, and a run with no Subject
  has nothing to authorize a cross-Space read. Scheduling one would need a persisted, attributable grant — declined
  2026-10-18 with "compare every Space" and persisted rows (BACKLOG §6 *Control plane & Jobs*). ⚠ On Personal nothing authenticates, so the capability wrapper is a no-op there, as
  for every gated route (the edition model, not a hole in this one). Why a Job Type and not a 22nd
  maintenance task: every maintenance task is handed exactly one `dataDir`; the cross-Space grant does not
  belong in that switch, and `BUILT_IN_TASKS` stays unchanged. Tests: `SpaceComparisonJobTest`,
  `ControlApiSpaceComparisonTest`. Design + the four decisions (2026-09-24):
  `archived-documents/plans-archive/space-comparison-design.md`.
  **UI (2026-10-11, `b584c0506`):** Settings › Spaces › **Compare Spaces** dialog
  (`inspecto-ui/src/app/modules/admin/spaces/space-comparison.dialog.ts`, client
  `inspecto/api/space-comparison.service.ts`, pure shaping `inspecto/spaces/space-comparison.ts`). The button
  shows only on a multi-Space server, with `canAdminister`, and with ≥ 2 hosted Spaces (the server still
  refuses without `canAdminister`). Sequence: `POST /space-comparisons` → poll `GET /jobs/runs/{runId}` until it
  leaves `RUNNING` → on `SUCCESS` read the result back from the Signal ledger with
  `GET /signals?type=space.comparison.completed&correlationId=<runId>&limit=1` (an ad-hoc run's correlation
  id is its run id). ⚠ API gaps the build worked around (BACKLOG §3.7 row notes, not separate items):
  the run-by-id read was untyped — ✅ closed 2026-10-11 (`5158ebe94`): `JobsService.run(runId)` /
  `JobRunById` type `GET /jobs/runs/{runId}` (fields `startedAt` / `finishedAt`, unlike the list row
  `JobRunRow`'s `startTime` / `endTime`), Compare Spaces polls through it and `SpaceComparisonService.run` is
  gone. The run-detail dialog now reads this route for `derivedFrom` (`JOB-RUN-DETAIL-DERIVEDFROM-1`, shipped). The structured result lives only in the Signal ledger (persisting
  it is decision (c)); no axis list is served, so the axes filter is free text; the 202 body and `Location`
  are not described in the OpenAPI.
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
[`okf/product/layer/platform/admin-guide-operations-reference.md`](../../layer/platform/admin-guide-operations-reference.md) §*Retention &
purging*. ⚠ **Nothing schedules `incident_purge`, and nothing should.** That is the decision, not a
residual: a shipped default that hard-deletes business records is indefensible, so standing the job up is
an operator act exactly like `receipt_prune`.

## `objectstore.export` — the push post-action (shipped 2026-09-24)

A built-in that delivers a directory under the Space data root (`local_path`, e.g. `orders/database`) to
an S3-compatible Connection (`connection`) — armed with `on_pipeline:` so it runs only after a commit.
Skip-unchanged by size + ETag, manifest written last, `FAILED` with no manifest when any object still
fails after `retries`. The transport is the `ExportConnector` SPI (`inspecto-acquire`), implemented by
`S3Connector`. As-built contract and limits:
[`okf/product/module/elt/spec-sinks.md`](../elt/spec-sinks.md).

## `consignment.process` — the third-party Consignment SPI (shipped 2026-08-04)

The Job Type that lets someone outside this repo do work over one committed Consignment, from the
[consignment-ELT plan](../../../../archived-documents/plans-archive/consignment-elt-architecture.md) §14. It is the framework half of a
two-sided seam:

| Side | Type | Who writes it |
|---|---|---|
| Author | [`ConsignmentProcessor`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/consignment/ConsignmentProcessor.java) — `id()` + `process(ProcessorContext)` | third party, discovered by `ServiceLoader` |
| Framework | [`ConsignmentProcessJobType`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/job/ConsignmentProcessJobType.java) — a `JobTypeProvider` | this repo, registered in `JobService` beside the other built-ins |

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

[`ConsignmentReader`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/consignment/ConsignmentReader.java)
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

[`SummaryEmitter`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/consignment/SummaryEmitter.java) enforces
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

🔴 **There is no `on_commit` Job trigger.**
`on_commit` is a **Signal, not an edge**: the `PipelineRel.ON_COMMIT` relation, which nothing ever produced, was
retired in 4.0.0 (X5-a) together with its `ON_COMMIT_SAME_GRAPH` graph-structure refusal. Commit-fired Jobs
ride the **Signal** bus — `JobService.mirrorPipelineCommit` turns a commit into a signal a Job trigger
matches. ⛔ A graph check was never the thing that keeps Job work at rest. The real reason Jobs are the at-rest seam
is simply that a Job is the only SPI that reads data already written.

⚠ **Do not grow the `EventType` enum for new facts — emit a `Signal`.** And **per-file facts belong in a
ledger row, not a signal**: a signal per file floods the bus on a large Consignment. There is deliberately no
signal-type constants class; the type is a string the emitter owns.

**`ProcessorContext` withholds more than the connection.** Beyond the writable `Connection` and `job()`, it
also does not expose `PipelineConfig` (a processor must read the **pinned** manifest values, not live config —
see the partition-drift rule in [consignment-addressing](../elt/spec-consignments.md)), the path
builders, or `Batch`/`PipelineNode`. Each omission is a decision, not an oversight.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*
