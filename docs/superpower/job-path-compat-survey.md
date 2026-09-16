---
type: Survey
title: Job path compatibility survey (JOB-PATH-COMPAT-SURVEY-1)
description: Every committed job config's relative path values, classified against the real PathJail.resolveJobPath rule after JOB-DIR-CWD-CONTAINMENT-1.
resource: inspecto-config/src/main/java/com/gamma/config/safety/PathJail.java
tags: [config, safety, path-jail, jobs, survey, compatibility]
timestamp: 2026-09-16T00:00:00Z
---

# Job path compatibility survey — `JOB-PATH-COMPAT-SURVEY-1`

**Status: SURVEY COMPLETE 2026-09-16.** The precondition `a7ab607b`'s own commit message called for and
never performed. Surveyed at `caea50ff`.

**Verdict in one line:** the change is **not** the "four demo rows plus eleven examples, mostly silent"
that `docs/BACKLOG.md` records. **33 relative path values across 24 committed job configs** are affected,
**every one of them is broken in at least one surface**, and the classification is **state-dependent** —
the same value classifies as a silent re-point on a fresh checkout and as a loud refusal on a deployment
that has ever run. ⛔ **Nothing in this survey is "unaffected by accident"**: there is not a single
committed job config carrying an absolute path.

---

## 1. How this was measured — the rule was DRIVEN, not mirrored

⛔ A hand-mirrored copy of a rule has drifted four times in this repo, so no logic was reimplemented.
`PathJail.java` was compiled **from the working tree** (`javac -sourcepath inspecto-config/src/main/java`,
pulling in `SafetyPolicy` / `DiscoveredRoots` / the `PublicApi` annotation) and the real
`PathJail.resolveJobPath(base, value, field)` was called over the real authored values, once with the
Space config root as `base` (the new rule) and once with `null` (the legacy working-directory rule the
method still honours). Classification is a comparison of the two returns plus `Files.exists` on each —
no re-derivation of the rule's branches.

Each run used the **real working directory** that surface uses:

| Surface | CWD | `base` | Established by |
|---|---|---|---|
| A Space's jobs | repo root | `spaces/<id>/config` | `SpaceBootstrap.java:46` registers `root.config()`; `SpaceRoot.java:208` `config()` = `base/config` |
| `inspecto/examples/**` under serve | the example dir | `out/write` | `serve-example.sh:48,66` — `cd "$DIR"`, `-Dassist.write.root=out/write`; its own comment says "single-tenant serve over `.` registers **no space base**", and `LegacySpaceRoot.config()` returns `null` (`SpaceRoot.java:146`), so `SpaceConfigRoot.forSpace` falls through to the `assist.write.root` property |
| `POST`/`PUT /jobs` gate | server CWD | `SpaceConfigRoot.current()` | `JobRoutes.java:381-382` |
| `PATCH /config/patch` gate | server CWD | **`target.getParent()`** | `ConfigWriteRoutes.java:370-371` — see §5(d) |

### 1.1 Positive controls (the probes are proven to fire)

⚠ A probe that cannot return a hit reports "absent" and exits 0. Every zero below is backed by a control
that **did** return a hit on the same probe:

| Probe | Control | Result |
|---|---|---|
| `grep -l "^job:"` over `git ls-files` | must find the four demo files the row names | found all four — **but missed 3 more** (`*_job_template.toon` files declare `job_template:`, not `job:`). The universe below is the union of both spellings, taken from the symbol, not from the row's file list. |
| key regex `^\s*(data_dir\|pipeline_config\|dir\|backup_dir\|archive\|archive_dir\|target_dir)\s*:` | `backup_dir` must be found | 35 hits incl. 4 × `backup_dir` — so the **zero hits for `archive` and `target_dir` are a proven absence**, not a broken probe |
| `resolveJobPath` refusal branch | `job.dir: spaces/demo/config/jobs` from base `spaces/demo/config` **must** refuse | REFUSED, naming both paths ✔ |
| `resolveJobPath` no-op branch | `job.dir: C:/sandbox` (absolute) **must** return identically under both rules | `UNAFFECTED-absolute` ✔ |
| the same refusal control, re-run in a scratch tree where the old path does **not** exist | **must flip** to a silent re-point | it flipped ✔ — this is the proof that the classification is state-dependent, not a property of the value |

---

## 2. The universe — 37 committed job configs, 24 of them carrying a path

Taken from `git ls-files` by content, both declaration spellings (`^job:` **and** `^job_template:`),
then a full key census over those 37 files (every `^\s*[a-z_]+:` key, counted) so no path-shaped key
could hide behind an assumed list.

**13 files carry no path value at all** and are unconditionally UNAFFECTED:
`06-serve/job-on-commit/heartbeat_job.toon`, `06-serve/maintenance-library/{db_maintenance,ledger_prune}_job.toon`,
`spaces/demo/config/jobs/{db_maintenance,ops_analytics_sample,orders_15m_rollup,orders_by_region_materialize,orders_summary_followup,orders_summary_sql,runlog_retention,sample_hello,scheduler_audit,stream_failure_watch}_job.toon`.

### 2.1 The key list in the decision text is WRONG in both directions

The operator decision names five keys (`dir` / `data_dir` / `backup_dir` / `archive` / `target_dir`).
The code's actual list, `ConfigSafetyValidator.JOB_PATH_KEYS` (`:122-131`), is **six** — it adds
`pipeline_config`, which is the **single most common path key in committed configs (12 of 33 values)**
and the one that produces the most refusals. Meanwhile:

* `archive` and `target_dir` appear in **zero** committed configs (proven absence, control above).
* **`archive_dir`** is resolved through the new rule at run time (`CleanupTask.java:47`) but is **not**
  in `JOB_PATH_KEYS`, so the save gate never checks it.
* **`out_dir`** (`ReportJob.java:122-125`) and **`store`** (`JobService.java:443`,
  `PartitionCompactor.java:57`) are path-shaped job keys that **neither** the gate nor the new rule
  covers. Both appear in committed configs.

---

## 3. Per-config classification

`old` = the legacy CWD-relative resolution. `new` = resolution against `base`. Two columns because the
verdict genuinely differs: **fresh** = a checkout where the old directory has never been created;
**deployed** = a server that has already run these jobs once under the old rule, so the old path exists.

⚠ `params:`-nested and `job_template:`-nested values are marked **gate-blind**: `ConfigSafetyValidator`
reads `RawConfig.str(raw, "job." + k)`, a **dotted path from the root**, so `job.params.dir` and
`job_template.job.dir` are invisible to the 422 gate. They still reach the runtime after template
expansion.

### 3.1 `spaces/demo` (base `spaces/demo/config`, CWD repo root)

| File:line | Key | Value | fresh | deployed | Gate sees it? | Runtime rule |
|---|---|---|---|---|---|---|
| `spaces/demo/config/jobs/backup_retention_job.toon:5` | `params.dir` | `spaces/demo/data/orders/backup` | BROKEN (re-points to `…/config/spaces/demo/data/orders/backup`) | **NOW REFUSES** | no (gate-blind) | `CleanupTask` → new rule |
| `spaces/demo/config/jobs/backup_verify_job.toon:6` | `backup_dir` | `spaces/demo/data/backups` | BROKEN | **NOW REFUSES** | yes | `BackupTask:172` → **old rule** (split) |
| `spaces/demo/config/jobs/config_backup_job.toon:6` | `params.dir` | `spaces/demo/config` | **NOW REFUSES** | **NOW REFUSES** | no (gate-blind) | `BackupTask:82` → **old rule** (split) |
| `spaces/demo/config/jobs/config_backup_job.toon:7` | `params.backup_dir` | `spaces/demo/data/backups` | BROKEN | **NOW REFUSES** | no (gate-blind) | `BackupTask:83` → **old rule** (split) |
| `spaces/demo/config/jobs/orders_rollup_job.toon:4` | `pipeline_config` | `spaces/demo/config/orders/orders_pipeline.toon` | **NOW REFUSES** | **NOW REFUSES** | yes | `PipelineJobRunner:242` → **no jail at all** |
| `spaces/demo/config/jobs/orders_weekly_compact_job.toon:7` | `dir` | `data/orders` | BROKEN | **NOW REFUSES** | yes | `PartitionCompactor:57` → **raw `Path.of`, no jail at all** |
| ~~`spaces/demo/config/jobs/maintenance_report_job.toon:6`~~ ✅ **RE-POINTED 2026-09-16** to `../data/reports` | `out_dir` | ~~`spaces/demo/data/reports`~~ | ~~BROKEN~~ **UNAFFECTED** | ~~NOW REFUSES~~ **UNAFFECTED** | ~~no~~ **yes** (in `JOB_PATH_KEYS`) | `ReportJob:127` → **new rule** |
| `spaces/demo/config/jobs/retention_job_template.toon:11` | template `dir` | `${dir}` | n/a — unexpanded placeholder | n/a | no (gate-blind) | expanded before use |
| `spaces/demo/config/jobs/chained_backup_job_template.toon:13,14` | template `dir`,`backup_dir` | `${dir}`,`${backup_dir}` | n/a | n/a | no (gate-blind) | expanded before use |

🔴 **The board's headline claim is WRONG.** `docs/BACKLOG.md` says `config_backup_job.toon:6-7` is a
"LOUD REFUSAL — this committed job is now UNSAVABLE". It **refuses**, yes — but **not at save**: the
value lives under `params:`, which the dotted-path gate cannot see, so the job saves fine. The refusal is
a *run-time* one, and only once `BackupTask` is moved onto `resolveJobPath` (`JOB-PATH-BACKUPTASK-SPLIT-1`).
Today that job still runs, CWD-relative, exactly as before.

### 3.2 `spaces/default` (base `spaces/default/config`, CWD repo root)

| File:line | Key | Value | fresh | deployed |
|---|---|---|---|---|
| `spaces/default/config/jobs/dedup_step_rollup_job.toon:4` | `pipeline_config` | `spaces/default/config/dedup_step/dedup_step_pipeline.toon` | **NOW REFUSES** | **NOW REFUSES** |
| `…/filter_step_rollup_job.toon:4` | `pipeline_config` | `spaces/default/config/filter_step/filter_step_pipeline.toon` | **NOW REFUSES** | **NOW REFUSES** |
| `…/join_step_rollup_job.toon:4` | `pipeline_config` | `spaces/default/config/join_step/join_step_pipeline.toon` | **NOW REFUSES** | **NOW REFUSES** |
| `…/sql_step_rollup_job.toon:4` | `pipeline_config` | `spaces/default/config/sql_step/sql_step_pipeline.toon` | **NOW REFUSES** | **NOW REFUSES** |
| `…/summarize_step_rollup_job.toon:4` | `pipeline_config` | `spaces/default/config/summarize_step/summarize_step_pipeline.toon` | **NOW REFUSES** | **NOW REFUSES** |

⚠ These five (and `orders_rollup`) refuse in **both** columns because a `pipeline_config` names a
**committed file**: the old path is in git, so it always exists and the ambiguous-case branch always
fires. Only the values naming *generated* directories are state-dependent.

🔴 **`docs/BACKLOG.md` does not mention `spaces/default` at all.** These five are gate-visible,
top-level, and refuse unconditionally — the *loudest* class in the whole survey, and the row missed
every one of them. They refuse because the authored value is already **space-root-prefixed**: resolving
it against the Space root doubles the prefix. That is the shape of nearly every committed value here,
which is why the change bites so widely.

### 3.3 `inspecto/examples/**` (base `out/write`, CWD the example dir)

Driven in a scratch copy of each example with the `out/…` tree the runner creates
(`serve-example.sh:50` — `mkdir -p out/inbox out/database out/backup … out/write`). ⛔ **Because the
runner creates those directories before boot, the old path ALWAYS exists at the moment the rule runs**,
so the "deployed" column is the only one that applies to a served example.

| File:line | Key | Value | Verdict (served) | Gate sees it? | Runtime rule |
|---|---|---|---|---|---|
| `06-serve/maintenance-library/backup_retention_job.toon:5` | `params.dir` | `out/backup` | **NOW REFUSES** | no (gate-blind) | `CleanupTask` → new rule ⇒ **this example is BROKEN at run** |
| `06-serve/maintenance-library/quarantine_retention_job.toon:5` | `params.dir` | `out/quarantine` | **NOW REFUSES** | no (gate-blind) | `CleanupTask` → new rule ⇒ **BROKEN at run** |
| `06-serve/maintenance-library/compact_job.toon:6` | `dir` | `out/database` | **NOW REFUSES** (gate) | yes | `PartitionCompactor:57` → no jail ⇒ still runs |
| `06-serve/maintenance-library/compact_job.toon:9` | `store` | `out/database` | not covered | no | `JobService:1407` — plain string |
| `06-serve/maintenance-library/retention_job_template.toon:11` | template `dir` | `${dir}` | n/a | no | expanded |
| `06-serve/pipeline-job/rollup_job.toon:5` | `data_dir` | `out` | **NOW REFUSES** (gate) | yes | `PipelineJobRunner:266` → no jail ⇒ still runs |
| `07-steps/{dedup,filter,join,lookup,sql,summarize}/*_rollup_job.toon:4` | `pipeline_config` | `orders_pipeline.toon` | **NOW REFUSES** (gate) ×6 | yes | `PipelineJobRunner:242` → no jail ⇒ still runs |
| `07-steps/{…}/*_rollup_job.toon:5` | `data_dir` | `out` | **NOW REFUSES** (gate) ×6 | yes | `PipelineJobRunner:266` → no jail ⇒ still runs |

🔴 **The board's "silent re-point" claim for the examples is REFUTED.** `docs/BACKLOG.md` records
`inspecto/examples/**` (11 files, `data_dir: out`, `dir: out/backup`, …) as a **silent re-point**. Driven
against the tree the runner actually builds, **every one of them REFUSES** — the runner pre-creates
`out/*`, so the old path exists and the loud-refusal branch is exactly the branch that fires. The row's
guard-only-fires-when-the-old-path-exists insight is right; its conclusion that the examples escape it
is wrong, because the runner is what creates the path.

🔴 **Two maintenance-library examples are BROKEN AT RUN TODAY, not just at save.**
`backup_retention` and `quarantine_retention` are `task: cleanup` ⇒ `CleanupTask.java:36`, which already
calls `requireJobPathUnderAny(..., SpaceConfigRoot.current(), ...)`. With `base = out/write` their
`out/backup` / `out/quarantine` refuse. These are two of the five jobs the example's own `probes.txt`
advertises. **This is the one place where shipped, documented behaviour is broken right now** — nothing
else in this survey is worse than a save-time refusal.

---

## 4. Counts

By **value** (33 relative path values in 24 files; **0 absolute values anywhere**):

Two scenarios, both driven. **A** = a fresh checkout with the examples never served (no `out/`, no
generated data directories). **B** = a server that has run these jobs once under the old rule, and a
served example (the runner has created `out/*`) — i.e. every real deployment.

| Class | A · fresh & unserved | B · deployed / served |
|---|---|---|
| UNAFFECTED (absolute, or identical under both rules) | **0** | **0** |
| MEANING CHANGED (resolves to a *different existing* location) | **0** | **0** |
| **NOW REFUSES** | **13** | **28** |
| BROKEN / silent re-point (new target exists nowhere) | **15** | **0** |
| n/a — unexpanded `${…}` template placeholder | 4 | 4 |
| not covered by the rule at all (`store`) | 1 | 1 |

By **file**: 24 of 37 committed job configs carry at least one affected value; 13 carry none.
By **surface**: **22** values are visible to the `POST /jobs` 422 gate; **11 are gate-blind** — 5 under
`params:`, 4 under `job_template:` (the dotted-path lookup cannot reach either), 1 `out_dir` and 1
`store` whose keys are not in `JOB_PATH_KEYS` at all.

⚠ **Scenario B is 28 = every covered value.** That is not a coincidence and not a coverage estimate: once
the old path exists, *no* committed value can resolve identically under both rules, so the ambiguous-case
branch fires for all of them. **On a real deployment, this change refuses every relative path in every
committed job config.**

⛔ **MEANING CHANGED is empty, and that is not good news.** Nothing quietly started reading a *different
real* directory, because the doubled-prefix target never exists. What actually happens on a fresh install
is worse and duller: the value re-points to a path that exists nowhere, so a cleanup sweeps an empty
directory, a backup writes into a freshly-created `config/spaces/demo/data/backups`, and a report is
delivered somewhere no one looks — all reporting success.

---

## 5. Defects — what needs a row, and what is working as designed

### Working as designed (no row)

* The **refusal itself**. Every `NOW REFUSES` above is the operator's deliberate ambiguous-case refusal
  doing precisely its job. The volume is the finding; the mechanism is correct.
* `PathJail.resolveJobPath` matches its javadoc exactly, including the `base == null` legacy path.

### DEFECTS — recommended new rows

**(a) `JOB-PATH-DEMO-CONFIG-REPOINT-1` (P2) — re-point every committed job config to a space-relative
value.** *(**32 remain** as of 2026-09-16: `maintenance_report_job.toon:6`'s `out_dir` was re-pointed to
`../data/reports` in the same change that moved its reader, `JOB-PATH-REPORT-ENRICH-SPLIT-1`. Driven
proof it is behaviour-preserving: the new value resolves to `…/spaces/demo/data/reports`, byte-identical
to what the legacy CWD rule returned from the repo root. ⛔ That is the pattern — **one value moves when
its reader moves** — not licence to re-point the other 32 while `PipelineJobRunner` and the compactors
are still on the old rule.)* All 33 values are authored either space-root-prefixed (`spaces/demo/…`) or CWD-prefixed
(`out/…`, `data/orders`). Under the new rule the correct spelling is relative **to the Space config
root** — e.g. `spaces/demo/config/jobs/orders_rollup_job.toon` should carry `orders/orders_pipeline.toon`,
not `spaces/demo/config/orders/orders_pipeline.toon`. ⚠ **Do this in the same change as
`JOB-PATH-BACKUPTASK-SPLIT-1`**, not before: re-pointing the four backup values while `BackupTask` still
resolves CWD-relative makes those jobs fail at run instead of at save. Includes `spaces/default`'s five,
which the board does not currently list.

**(b) `JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1` (P2) — the gate and the *pipeline-job* runtime disagree,
and nobody has counted this one.** `PipelineJobRunner.java:242` passes `pipeline_config` straight to
`PipelineConfig.load(flatPath)` and `:266` passes `data_dir` into the sinks — **with no jail and no
`resolveJobPath` at either site**. The 422 gate resolves both against the Space config root.
⇒ **19 of the 33 surveyed values** (12 × `pipeline_config`, 7 × `data_dir`) are gated under one rule and
run under another — a *larger* split than `JOB-PATH-BACKUPTASK-SPLIT-1`, which the board records as the
only one. 🔴 It is also a **containment hole**, not merely a base mismatch: these two sites do not call
`PathJail` at all.

**(c) `JOB-PATH-COMPACTOR-UNJAILED-1` (P2, security-adjacent) — `dir` reaches the filesystem unjailed
in the compactors.** `PartitionCompactor.java:57` and `ReferenceCompactor.java:91` both do
`Path.of(cfg.require("dir"))` — raw, no jail, no resolution — then `Files.walk` it and merge/delete
files under it. Every other `dir` reader (`CleanupTask:36`, `PartitionPruneTask:40`,
`StorageReportTask:45`) jails. `task: compact` is a shipped, documented Job Type with two committed
configs (`spaces/demo/config/jobs/orders_weekly_compact_job.toon`,
`inspecto/examples/06-serve/maintenance-library/compact_job.toon`). ⚠ This is **independent of
`JOB-DIR-CWD-CONTAINMENT-1`** — it predates it and would be a defect under either rule.

**(d) `JOB-PATH-PATCH-ROUTE-WRONG-BASE-1` (P2) — a third base for the same key.**
`ConfigWriteRoutes.java:370-371` validates a patched job with `configDir = target.getParent()`, which for
a job is `spaces/<id>/config/**jobs**` — not the Space config root `POST /jobs` uses
(`JobRoutes.java:381-382`), and not the CWD the runtime uses. **Driven:**
`backup_dir: spaces/demo/data/backups` resolves to `…/config/jobs/spaces/demo/data/backups` through the
patch route versus `…/config/spaces/demo/data/backups` through `POST /jobs`. ⛔ `resolveJobPath`'s own
javadoc says the gate and the jail must not diverge; here **two gates** diverge from each other.

**(e) `JOB-PATH-GATE-BLIND-KEYS-1` (P2) — the 422 gate cannot see a template-instance path, nor
`archive_dir`, nor `out_dir`.** Three distinct blind spots, one row:
* `RawConfig.str(raw, "job." + k)` is a **dotted path from the root**, so the 7 committed values under
  `params:` and the 4 under `job_template:` are never checked. ⚠ This is exactly why the board's
  "`config_backup_job.toon` is now UNSAVABLE" claim is false.
* `archive_dir` is resolved with the **new** rule at run (`CleanupTask:47`) but is absent from
  `JOB_PATH_KEYS` — checked at run, never at save.
* ~~`out_dir` is a real delivery directory (`ReportJob:122-125`, jailed CWD-relative) and is in neither
  list, so the operator's decision does not reach it at all. One committed config uses it.~~
  ✅ **CLOSED 2026-09-16 by `JOB-PATH-REPORT-ENRICH-SPLIT-1`**, together with a third blind key this
  survey missed: **`config`** (`EnrichJob:59` → `EnrichmentConfig.load`) reached the filesystem with **no
  `PathJail` call at all**. §2's "path-shaped keys neither covers" named `out_dir` and `store` but not
  `config`, because the census keyed on the *authored* configs — and `config` appears in **zero** of them
  (re-confirmed against a `target:` positive control). ⚠ **A key census over committed values cannot find
  a key nobody has authored yet**; the reader side has to be censused too. `store` remains uncovered.
  Both moved onto `requireJobPathUnderAny` **before** joining `JOB_PATH_KEYS`, which is now the stated
  order in that field's javadoc.

### Not a defect, but record it

* `inspecto/examples/06-serve/pipeline-job/rollup_job.toon:4` carries **`flow:`**, and its write root
  ships `write/flows/sales_rollup_flow.toon`. `PipelineJobRunner:244` documents `flow:` as a read-only
  pre-rename key. ⚠ **Not a guard violation** — `tools/check-vocabulary.mjs` was run and passes all 219
  committed TOON configs, so the legacy spelling is an accepted exemption, not an oversight. Recorded
  only because it is the one committed job config still needing a resave to shed the pre-rename key —
  and a resave now goes through the very gate this survey is about.

---

## 6. What this survey could NOT verify

* **No job was actually run.** Every verdict comes from driving the real `resolveJobPath` over the real
  values with the real bases; the downstream *consequences* (a cleanup sweeping an empty directory, a
  backup writing into `config/spaces/…`) are read off the call sites, not observed in a live run. A
  live `SMOKE` over `spaces/demo` would settle §3.1's runtime column.
* **The `spaces/demo` bootstrap was not booted**, so `SpaceConfigRoot.forSpace("demo")` returning
  `spaces/demo/config` is read from `SpaceBootstrap.java:46` + `SpaceRoot.java:208`, not observed.
* **Whether the UI ever posts an *expanded* template instance** to `POST /jobs` (which would make the
  gate-blind `params:` values visible after all) — `JobRoutes.parseJob` validates the body as sent, and
  what the SPA sends was not traced.
* **`spaces/ucc` and `spaces/_templates` carry no job configs at all** (verified by `git ls-files`), so
  the "a sample space that does not boot on Personal" note has no job-path surface to check.
* **No config failed to parse.** Every one of the 37 files is well-formed TOON by inspection; this was
  not checked by running the parser.

---

## 7. Where this doc belongs

It is here in `docs/superpower/` because its work is **in flight** — five follow-up rows in §5 and
`JOB-PATH-BACKUPTASK-SPLIT-1` are all open. ⛔ **When those ship**, distil §1 (the four surfaces and
their bases), §2.1 (the real key list and its three blind spots) and §4's "MEANING CHANGED is empty"
finding into [`okf/backend/config/config-safety.md`](../okf/backend/config/config-safety.md) beside the
existing path-containment section, move anything still open to `docs/BACKLOG.md`, `git mv` this file to
`docs/archived-documents/plans-archive/`, and run `graphify update .`.
