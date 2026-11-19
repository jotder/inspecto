---
type: Concept
title: Jobs & Scheduling — Open rows this concept owns
description: Part 4 of 4 of Jobs & Scheduling: Open rows this concept owns — the job-path semantics change (2026-09-16)
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
item: module.processor
audience: internal
status: current
verified: 2026-11-19 last-edit
code: inspecto
---

# Jobs & Scheduling — Open rows this concept owns

Part 4 of 4 of [Jobs & Scheduling](spec-jobs.md) · previous: [Maintenance jobs and the push and process jobs](spec-jobs-maintenance.md).

## Open rows this concept owns — the job-path semantics change (2026-09-16)

`JOB-DIR-CWD-CONTAINMENT-1` made `PathJail.resolveJobPath` the single rule for a job's relative paths.
The survey that followed ([`archived-documents/plans-archive/job-path-compat-survey.md`](../../../../archived-documents/plans-archive/job-path-compat-survey.md))
found the change reaches further than the decision that authorised it. ⛔ **`JOB_PATH_KEYS` is SIX keys,
not the five the decision names** — the extra one is `pipeline_config`, the most common path key in
committed configs.

- ~~**`JOB-PATH-COMPAT-SURVEY-1`**~~ ✅ **CLOSED 2026-09-17 (`1d94876f3`).** Filed as "on a deployed tree
  every relative path in every committed job config refuses"; re-driven over the real
  `PathJail.resolveJobPath`, every defect it named had shipped. 🔴 The re-drive found six values the survey
  never listed — repo-relative `pipeline_config` (`spaces/<x>/config/...`) in five
  `spaces/default/config/jobs/*_step_rollup_job.toon` and `spaces/demo/config/jobs/orders_rollup_job.toon`,
  which double the path under the Space-root rule — re-pointed Space-relative in the same commit as the
  runner split below, because the two only work together.
- ~~**`JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1`**~~ ✅ **CLOSED 2026-09-17 (`1d94876f3`)**, operator design call
  option 1 (additive read root). `PipelineJobRunner` read `pipeline_config` and `data_dir` with no
  `PathJail` call; both now go through `PathJail.requireJobPathUnderAny` against the config READ root
  (`SpaceConfigRoot.currentConfigReadRoot()`; `data_dir` is CHECKED, not rewritten, because the authored
  string is baked into durable view SQL). The per-key base is now `SpaceConfigRoot.jobPathBase` — see
  `JOB-PATH-SINGLE-TENANT-GATE-BASE-1` below. Pinned by `PipelineJobRunnerPathJailTest`.
- ~~**`JOB-PATH-COMPACTOR-UNJAILED-1`**~~ ✅ **SHIPPED 2026-09-16** — both compactors now use the job rule,
  matching the three `MaintenanceJob` siblings. ⛔ `ReferenceCompactor.compact(Path,long)` stays unjailed
  on purpose: `CollectorService` feeds it a pipeline's own resolved dirs, not an authored value.
- ~~**`JOB-PATH-PATCH-ROUTE-WRONG-BASE-1`**~~ ✅ **SHIPPED 2026-09-16, and the defect was not the one the
  row named.** `ConfigWriteRoutes.safetyBase` (now `safetyFindings`) dispatches on TYPE, because `configDir` legitimately means
  two different things: a pipeline/schema resolves its `schema_file`/`grammar` refs against the config
  file's own directory, a job against the Space config root. ⛔ "Make them the same" was the wrong fix.
  The real third base was `/config/write:71`, which passed **no base at all** and judged a job against the
  process working directory. ⚠ Neither route has ever had a `type:"job"` caller — latent, never live.
- ~~**`JOB-CONFIG-THIRD-PRODUCER-1`**~~ ✅ **CLOSED 2026-09-16 (`a0f82161d`).** Bundle import's
  `BundleRoutes.JobBundleSource.write` jailed the FILE (`WriteGates.jail`) but never sent the job's path
  VALUES to `ConfigSafetyValidator`, unlike its sibling `JobRoutes.parseJob`; it now runs
  `ConfigSafetyValidator.checkJob` too. ⇒ Every job-config producer runs the same gate.
- ~~**`JOB-PATH-GATE-BLIND-KEYS-1`**~~ ✅ **CLOSED 2026-09-16 (`8773cc471`) — one third SHIPPED, one third
  REFUTED, one third re-filed (and since discharged).** `archive_dir` is now in `JOB_PATH_KEYS` (seven keys): `CleanupTask` already resolved it
  through `requireJobPathUnderAny` against the Space root, so the gate and the jail agreed on the rule and
  only the gate was not looking — and **no committed job config carries the key**, so listing it refuses
  nothing that exists. 🔴 **The dotted-path half is REFUTED as a reachable defect.** `RawConfig.str` does
  walk a dotted path from the root, and 5 committed path values (not 11 — 11 was the survey's *total*
  gate-blind count, which also included `out_dir` and `store`, blind for a different reason) plus 4 `${…}`
  placeholders do sit where `"job."+k` cannot reach. But that shape is a **Job Template instance**
  (`template:` + `params:`), which carries no `job.type`, and `job.type` is `FieldSpec.required` on
  `ConfigSpecs.job()` — so `POST/PUT /jobs`, `/config/write` and `/config/patch` all refuse the whole body
  before the safety gate's blindness can matter. A template instance reaches the system only through
  `ServiceBootstrap`, which expands `params:` into the flat `job:` block before the run-time jails read it.
  Widening `checkJob` to `job.params.*` would refuse nothing any route can accept. ⚠ **`out_dir` was held
  OUT deliberately** — `ReportJob` jailed it with the plain `PathJail.requireUnderAny` (containment only,
  working-directory-relative), so listing it would have *manufactured* a gate/runtime split — **and the
  row missed a second key of the same shape: `config` (`EnrichJob`) reached `EnrichmentConfig.load`
  with no jail at all.** ✅ **Both were discharged the same day by `JOB-PATH-REPORT-ENRICH-SPLIT-1`,
  reader first and only then the list** — see that section below. ⇒ `JOB_PATH_KEYS` is now **nine**:
  the seven after `archive_dir` joined, plus `out_dir` and `config`.
- ~~**`JOB-PATH-DEMO-CONFIG-REPOINT-1`**~~ ✅ **CLOSED 2026-09-23 with no code (`21b3803cc`) — remainder:
  0 real values.** After the five 2026-09-16 re-points (`d433ac0a2`), 24 were left: the 19
  `PipelineJobRunner` values need none, because `JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1` option 1 resolves
  them against the config READ root (the launch dir, where they always pointed), and since
  `JOB-PATH-SINGLE-TENANT-GATE-BASE-1` the save gate agrees; 4 are `${…}` placeholders, not values; 1 is
  `store`, which no job-path rule covers. ⛔ The durable lesson from its sequencing: a config and its
  reader's rule move together — configs ahead of their runtime refuse, and a runtime ahead of its configs
  is the same break (below).
  ~~🔴 **Three of the 32 are now orphaned the OTHER way**~~ ✅ **CLOSED 2026-09-16, same day.**
  `JOB-PATH-BACKUPTASK-SPLIT-1` (`3f384182`) moved `BackupTask` without the three configs it reads, so
  `config_backup` failed at run and `backup_verify` returned a green "no archive to verify" over a
  doubled directory. **The marker only ever named one of the two orders** — configs ahead of their
  runtime — and a runtime ahead of its configs is the same break. Re-pointed to `dir: .` and
  `backup_dir: ../data/backups`, each driven to land byte-identically on the legacy target, and now
  pinned by `DemoBackupJobPathsResolveUnderTheSpaceRootTest` — the first thing in the repo that holds
  a committed config value and its reader's rule together.

### `JOB-PATH-SINGLE-TENANT-GATE-BASE-1` — a job's base is chosen PER KEY, by one resolver

In the single-tenant layout a job has **two** bases, not one. `PipelineJobRunner` reads `pipeline_config`
and `data_dir` against `SpaceConfigRoot.currentConfigReadRoot()` — the launch dir, which
`SpaceManager.single()` registers (`serve-example.sh` cd's into the example) — while the maintenance
tasks, `ReportJob` and `EnrichJob` read their keys against `SpaceConfigRoot.current()` — the write root,
`-Dassist.write.root` (e.g. `<example>/out/write`). The save gates judged **every** key from the write
root, so a value that RUNS (`pipeline_config: orders_pipeline.toon` in `inspecto/examples/07-steps/*`)
was refused by `resolveJobPath`'s ambiguous-case branch, and re-saving that job through `POST|PUT /jobs`
answered 422.

**As built (2026-09-23):** `SpaceConfigRoot.jobPathBase(key[, spaceRoot])` is the one answer —
`CONFIG_READ_ROOT_JOB_KEYS` (`pipeline_config`, `data_dir`) go to the read root, every other key to the
Space config root. `PipelineJobRunner` calls it for its two reads; all four job save gates
(`JobRoutes.parseJob`, `BundleRoutes` job import, `/config/write`, `/config/patch`) pass it to
`ConfigSafetyValidator.checkJob(raw, policy, baseForKey)`. Pinned by `ControlApiJobPathSingleTenantBaseTest`
(real HTTP, launch-dir fixture) and `SpaceConfigRootTest.jobPathBaseSplitsPerKeyBetweenTheReadRootAndTheSpaceRoot`.

- ⚠ **Multi-Space mode is unchanged by construction:** a self-contained Space registers no read root,
  so `currentConfigReadRoot()` falls back to the same `root().config()` `current()` returns.
- ⛔ **A new key that joins `JOB_PATH_KEYS` must also be placed in (or out of)
  `CONFIG_READ_ROOT_JOB_KEYS`** by what its reader calls — the gate reads the base from there.
- ⚠ `schema_file` is not a job key and not part of this rule (`SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`).

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

- **`FENCE-STORE-SILENTLY-INERT-1`** — `DeletionFence.check` `continue`s past a target store with
  no resting producer, so a job's `store:` naming nothing disarms the fence with **no error, no warning
  and no event**. ⛔ It shipped that way: `compact_job.toon` carried a miscopied `store: out/database` from
  2026-07-08 until `819e597b` while the only produced store was `sales`, and the example's `probes.txt`
  advertised fencing that could never fire. ⚠ A store may LEGITIMATELY have no producer yet, which is why
  the silent skip exists — the open call is warn-at-registration versus stay silent.

- **`JOB-PARAM-UNDECLARED-UNREPORTED-1`** — `ParameterResolver` reports missing, mistyped and
  unknown-expression params, but never an **undeclared** one. This is the dead-property risk for jobs, and
  it cannot be closed by `AcceptedConfigKeys`: `job`'s accepted set is per-type and not statically
  enumerable (runtime-mutable registry, `ServiceLoader` types, config-derived `sql.template` params,
  edition-varying `maintenance` tasks). ⛔ Any check belongs at `JobService`/`ParameterResolver` where the
  descriptor is known, and must WARN rather than refuse — built-in jobs read params their own descriptors
  do not declare, so a descriptor is a UI/API contract, not the read set.

- **`COMPACT-SUCCEEDS-ON-A-MISSING-DIR-1`** — `PartitionCompactor` returns
  `JobResult.ok("directory not present, nothing to do")` when `dir` is absent, so on a tree where the
  directory does not exist a **broken** config reports SUCCESS. Today's regression surfaced only because
  `serve-example.sh` pre-creates the directory. ⛔ The fix is not simply "throw" — a compactor with
  nothing to compact is a legitimate no-op; the question is whether an UNRESOLVABLE dir is distinguishable
  from an EMPTY one, and it is.

### Closed 2026-09-17 — `COMPACT-SUCCEEDS-ON-A-MISSING-DIR-1`, and the distinction it asked for does not exist

✅ **SHIPPED**, but **not the fix the row named.** The row asked to separate an UNRESOLVABLE `dir` from an
EMPTY one. 🔴 **They are not separable at that point.** Every genuinely unresolvable case — blank, URI
(`s3://…`), UNC, unparseable, outside the jail — already throws `PathJail.Escape` from
`requireJobPathUnderAny` and never reaches the `isDirectory` check. What remains, a stale-but-jail-contained
relative path, is byte-for-byte identical to a store nothing has written to yet; erroring there refuses every
legitimate FIRST RUN.

✅ **The real, decidable case is `dir` EXISTS but is a regular file.** `!Files.isDirectory(dir)` is true, so
the task reported *"directory not present, nothing to do"* — a config no run can ever satisfy, reported as
SUCCESS. `Files.exists(dir) && !Files.isDirectory(dir)` decides it exactly; absent and empty stay a no-op.

⚠ **The row named ONE file; the shape was in FOUR** — `PartitionCompactor`, `CleanupTask`,
`StorageReportTask`, `PartitionPruneTask`, **two of which delete**. Pinned by
`MaintenanceDirNotADirectoryTest`, whose mutation control is the unmodified tree: 4 failures without the fix,
and its two positive controls (absent, empty) green in BOTH runs — the proof the no-op was not sacrificed.

✅ **Closed 2026-09-17 — the FIFTH site, `REFERENCE-COMPACTOR-SAME-SHAPE-1`.** `ReferenceCompactor` was
deliberately left out above because its guard sits in `compact(Path root, long historyDays)`, a **public
overload `CollectorService.compactReferenceStore` also calls** with a pipeline's `dirs.database` — a DERIVED
root, not an authored one. ⛔ **The one-line fix does not belong on the shared overload.** It goes on the
config adapter `run(JobConfig)`, immediately after `PathJail.requireJobPathUnderAny` — **the same seam the
jail already sits on, and for the identical reason**, which the method's own comment had stated since it was
written. The `compact(…)` guard is untouched, so the timer-driven pipeline lane keeps its silent no-op.
Same three tests in the same class (refusal + absent/empty positive controls); mutation control is again the
unmodified tree, 1 failure without the fix with both controls green in BOTH runs.

### Closed 2026-09-17 — `JOB-PARAM-UNDECLARED-UNREPORTED-1`: a descriptor is a CONTRACT, not the read set

`ParameterResolver.Resolution` carries `undeclared` (the authored `config` layer minus the decl names) and
`JobService` warns on the run log beside the three REJECTED diagnostics. ⛔ **WARNING only, never
fail-closed** — and the reason is measured: **NINE** keys are read straight through `config.require()`/`opt()`
that no `JobTypeDescriptor` declares (`data_dir`, `batch_id`, `flow`, `on_pipeline_gate`, `sleep_ms`, `top`,
`history_days`, `max_attempts`, `backoff_minutes`). ⚠ Since then seven are declared (`JOB-DESCRIPTORS-LIE-TO-THE-FORM-1`),
and `batch_id` became `consignment_id` in 4.0.0 (D-12): `consignment_id` is declared, and the old key is still read
(warned) and not reported as undeclared while the `pipeline` type declares `consignment_id`. A strict version refuses working configs on day one.

✅ **Blast radius: ZERO of 21 committed `*_job.toon` warn** (both `job_template` instances expanded,
`sql.template`'s `$`-tokens counted). Quiet **by construction, not by suppression** — hence one framework
exclusion (`on_pipeline_gate`) plus `flow` excused only when a `pipeline` decl exists.

⇒ **One open row this leaves: `JOB-DESCRIPTORS-LIE-TO-THE-FORM-1`** — those nine keys cannot be offered,
typed or bounded by any UI; declaring them changes the published `GET /jobs/types/{id}` contract, so it needs
its own call. ⚠ It became MORE visible on 2026-09-17, not less: a built-in job would now warn if those
descriptors were ever tightened.
✅ The other two residuals of this row — `PACKHARNESS-NO-UNDECLARED-1` and `REFERENCE-COMPACTOR-SAME-SHAPE-1`
— were **both CLOSED the same day**; see their sections below.

### Closed 2026-09-17 — `PACKHARNESS-NO-UNDECLARED-1`: the warning reaches the pack author

The row's premise held on inspection: `PackTestHarness.fire` holds a real `ParameterResolver.Resolution`,
`rejection()` correctly omits `undeclared`, and nothing else looked at it — so the one diagnostic aimed at a
pack author was the one surface that dropped it.

**Surfaced on the Run Log, not as a new `Outcome` field.** `Outcome.log()` + `logged(fragment)` is already the
harness's diagnostic channel (it is how dry-run substitution and every `ctx.log()` line are asserted), and the
harness's whole claim is to be a proxy for a real Run — so the author should read *the* production line, in
the production position, rather than a parallel structured channel the engine has no counterpart for.

⚠ **The message is built once**, by `ParameterResolver.undeclaredWarning(typeId, undeclared)`, called from both
`JobService` and the harness. Two hand-written copies of the same warning is the mirror-drift shape this repo
has paid for repeatedly; there is no second spelling to drift.

⚠ **Deliberate fidelity choice:** the warn sits **after** the `rejection()` early return, exactly as in
`JobService`. A run that is REJECTED therefore shows no undeclared warning *in either place* — the harness
would be lying if it reported something production does not.

Pinned by `PackTestHarnessTest.anUndeclaredParameterWarnsTheAuthorWithoutRejectingTheRun`. The load-bearing
assertion is `logged("thershold")` — the typo'd key by name. Mutation-proved by stubbing the warn
(`if (false && …)`): that one assertion goes red on an **empty** run log, 11/12 of the class still green. Its
neighbour `assertEquals("SUCCESS", …)` — which pins "reporting it never becomes a rejection" — **passed under
the mutant**, and is recorded here as the half of the test that cannot detect the regression.

⚠ **A Job whose type nothing registers is listed `hosted:false` (`MODULE-REORG-P4-1`, 2026-10-07).** `GET /jobs` used to list it with a live `nextFire` though it
could never fire. `JobView` now carries `hosted` and `reason`; an unhosted job has an empty `nextFire` and a reason naming the type ("no installed module or Job
Pack provides it"), and the Jobs pane badges it "Not installed". The reason is generic when no known module declares the type; since P4e manifests declare
`provides.jobTypes`, so a type a known-but-absent module declares gets `missingModule` and a reason naming the module. The detail read
(`GET /jobs/{name}`) stays the raw config on purpose. Pinned by `ModuleRemovalJobTest` and `jobs.component.spec.ts`.

⚠ **A `maintenance` Job whose task belongs to a switched-off module is `SKIPPED` too (P4f, 2026-10-08).** `provides.maintenanceTasks` declares the owner (ops: `incident_purge`); `JobService.jobTaskGate` is consulted next to the Job Type gate, so every fire is recorded `SKIPPED` ("switched off in this Space"), a manual trigger or replay answers 404 `MODULE_DISABLED`, and it resumes on re-enable. Pinned by `JobTaskGateTest` and `OpsBackgroundManifestTest`.
