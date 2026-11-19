---
type: Capability
title: Observability & maintenance (OPS) — capability spec — Specification — Ops Lens screens; decisions and not built
description: Part 3 of 4 of Observability & maintenance (OPS) — capability spec: Decisions (dated one-liners); Not built
status: current
---

# Observability & maintenance (OPS) — capability spec — Specification — Ops Lens screens; decisions and not built

Part 3 of 4 of [Observability & maintenance (OPS) — capability spec](plan-observability.md) · previous: [Specification](plan-observability-specification.md) · next: [Refused, as-built pointers and verification](plan-observability-refused-verification.md).

## 3. Specification (continued)

### 3.8 The Ops Lens screens as consumers

| Screen (route, component) | Contracts consumed | Edition behaviour |
|---|---|---|
| **Events** (`/events`, `events.component.ts`) | `/events/search` (level/type/pipeline/q/correlationId, offset paging), live tail by `visibleInterval` polling (2/5/10/30/60 s), `/events/export` → client Blob, `/events/views` CRUD, `EventDetailDialog` re-filters by correlation/type, "What led to this" → `AiStatusDialog` | `features.events=false` → explained state, no call; Ops lens home falls back to `/pipelines` (`OPS_HOME_WITHOUT_EVENTS`, `app.routes.ts`) |
| **Audit log** (`audit-logs.component.ts`) | core `/audit/search` for `AUDIT` + `ACCESS_DENIED` via `forkJoin`; columns Time, Actor, Action, Category, Target, IP, Message | **not** gated on `features.events` — reads core routes on every edition |
| **Overview** (`/overview`, `dashboard.component.ts`) | `/ready`, `/status`, `/report` (p50/p95/p99, outcomes), `/metrics/acquisition` KPIs, raw `/metrics` text behind a `showMetrics` toggle, recent events | each fetch degrades independently; the events tile is skipped when `eventsEnabled` is false; the raw-metrics toggle meets a `503` on Basic (§5) |
| **Runs / Run detail** (`/runs`, `/runs/:name`) | `RunRoutes` list/trigger/pause/resume/reprocess; tabs batches/files/lineage/quarantine/commits/report; `InboxStatus` current/step gauges; "Failed consignments retry automatically" notice; "View the rejected rows" | Business lens read-only (2026-07-03) |
| **Jobs** (`/jobs`, `jobs.component.ts`) | schedules mode (list/trigger/enable/reschedule/edit/delete) and **reporting** mode (`/jobs/metrics|runs|failures`, 5 s live tail); the authoring form takes its **task picker from `GET /jobs/types`** — which maintenance tasks appear is entirely what the backend registered, so backup/restore vanish on Basic with no UI change | reporting 404 → `reportDisabled` explained |
| **Processing status** (`processing-status.component.ts`) | `/status`, `/status/problem-files`; row action opens Run detail | |
| **Scheduler settings** (`settings/scheduler.component.ts`) | `GET|PUT /system/scheduler`; `memoryLimit` validated by the server-served DuckDB grammar; provenance chips | saves gated on `canOperateRuns` |
| **Operational DB** (`settings/operational-db.component.ts`) | `GET /system/operational-db`, `POST …/test`; no PUT | `canConfigureAccess` |

There is no metrics-browsing UI and no backup/restore screen beyond the Jobs form — both by design.

### 3.9 The completeness KPI — design of record (K1–K4 built 2026-10-06)

⚠ Distilled 2026-09-09 from `completeness-kpi-plan.md`, archived the same day. The operator hold
(2026-08-30) was LIFTED 2026-10-06. **As built:** the `kpi.completeness` built-in Job Type
(`inspecto-engine` `com.gamma.job.KpiCompletenessJob`) is K4 and the caller of K1 (`dailyVolume`) and K3
(`VolumeBaseline.assess`). Its first act is `requireDurable` over `StoreHealth.of(spaceId)` — an absent
entry refuses like `NOT_CONFIGURED`, dry runs included, and a refusal emits no `kpi.completeness.*` Signal.
Parameters: `pipeline` (defaults to the Job's single `on_pipeline:` trigger; a `pipeline` that names a different Pipeline than `on_pipeline:` is refused at save and load — `JobConfig.fromMap` → 422 naming both; neither set, or several triggers and no parameter, refuses the run — operator, 2026-10-10, §7-d), `record_day` (default yesterday in the zone the Pipeline's record days are written in — UTC when it declares `parsing.source_timezone`, since ingest then normalises event time to naive UTC; else `-Dops.timezone` (operator, 2026-10-09); a per-field `timezone` is not consulted), `baseline_window`
28, `min_baseline_days` 7, `tolerance` 0.3. Signals (`com.gamma.signal.SignalType`, operator 2026-10-06):
`kpi.completeness.evaluated` every run (WARN on BREACH), `kpi.completeness.breached` on BREACH only, plus
one Incident per Pipeline through `IncidentAccess` (scope and dedupe key = the pipeline). ⛔ `NO_BASELINE` /
`NO_OBSERVATION` open nothing. **`KPI-UNKNOWN-1` as built:** an unmeasured day carries no `files`/`rows`
keys; the null-day bucket rides as `unknownDayFiles`/`unknownDayRows`; `baselineRows` is omitted and
`deviation` is JSON null where undefined. **K2 as built (operator, 2026-10-06):** the Collector's `gap_detection` takes an optional `file_template`
(one `{datePattern}` + an optional `{seq}` token, e.g. `CDR_{yyyyMMddHH}_{seq}_*`) and `seq_scope`
(`PER_BUCKET` | `CONTINUOUS`, never defaulted; required only when `{seq}` is present and refused when it is
absent). A date-only template such as `XDR_{yyyyMMdd}.csv` (operator, 2026-10-06) expects ONE file per date
bucket: an empty bucket is then exactly one missing file (counted in `missingFiles` as well as `emptyBuckets`) and
the payload carries no `seqScope`, validated fail-closed by `GapTemplateGrammar` at config
load and by the `gap-file-template-well-formed` CrossFieldRule at authoring. ⛔ `gap_detection.sequence`
and the live `GapDetector` are untouched — a one-token template cannot count files. The job's
`sequence_template` / `seq_scope` parameters win over the Collector's; `check_files` (default true) turns
the file half off. It refuses before reading when no template is set (naming
`collector.gap_detection.file_template`) or `fileStages` is not durable (naming `-Dfile.stages.backend`,
default `none`). Filenames come from `DbFileStageStore.relativePaths` (processing time, padded a day each
side; throws on failure) into `FileSequenceGaps.analyze`; the payload adds `fileTemplate`, `seqScope`,
`observedFiles`, `missingFiles` (exact interior holes), `emptyBuckets` (a bucket count, never files) and
`unmatchedFiles`. `check_files` stays default true (fail closed) and the refusal names `check_files: false`
as the opt-out. ⚠ **KNOWN LIMIT (operator, 2026-10-10, §7-c):** `file_stages` is a best-effort index, so
`missingFiles` is only as complete as it — a file that arrived but was never indexed reads as missing. The payload
labels the count `missingFilesSource: "file-stages"` ("based on the file index"); the `kpi_completeness` Dataset
carries no such column (adding one changes the store's schema), so read `missing_files` with the same caveat.

**Incidents and streaks (operator, 2026-10-06).** A volume BREACH **or** a file gap emits
`kpi.completeness.breached` and opens **one Incident per Pipeline-day** — dedupe attribute `pipelineDay`
= `<pipeline>@<day>`, scope the pipeline — carrying both findings (`volumeStatus`, `rows`/`baselineRows`,
`missingFiles`/`emptyBuckets`). A later same-day run goes through `IncidentAccess.openOrUpdateIncident`: changed
findings are merged into the active Incident's attributes (`ObjectAccess.saveAttributes`, audited as an
`OBJECT_ACTIVITY` `findings-updated` event by actor `system`); identical findings are suppressed; the title and
message are not rewritten. Dry-run / pack-harness stand-ins keep the open-only default. Three or more consecutive days with nothing registered emit `kpi.completeness.unknown_streak`
at WARN (`unknownStreakDays` in the payload) and open **no** Incident. The 28 / 7 / 0.3 defaults are confirmed.
**§8-D completeness tile — Signals behind a Dataset (operator, 2026-10-09).** Each run also upserts its
Pipeline-day row (`pipeline`, `record_day`, `status`, `rows`, `baseline_rows`, `deviation`, `missing_files`,
`unknown_streak_days`, `evaluated_at`) into `<dataRoot>/kpi_completeness/kpi_completeness.parquet`
(`KpiCompletenessOutput`): read, drop the Pipeline-day, add, `COPY` to a temp file, atomic move — a same-day
re-run replaces its row. Ownership follows the Risk Score output pattern: the directory is created with a
`.kpi-completeness-output` marker, and an existing directory without it is refused untouched. No data root (a bare
test or CLI run) leaves the run signal-only with a warning. The `telco-ra` template ships the `kpi_completeness_dataset`
(`physicalRef: kpi_completeness`), an ordinary table Widget `ra_completeness_status` (Pipeline × day × status, rows,
newest day first) on `ra_overview`, and a `ra_switch_kpi_completeness` Job over `switch_xdr` that ships **disabled**
(it refuses unless the consignment-output store is durable). Pinned by `KpiCompletenessJobTest` and
`KpiCompletenessWidgetTest` (template created through `POST /spaces`, tile rendered through `POST /bi/query`).
⚠ The table shows every stored day, newest first; it does not filter to the latest day.
**Before the first run (LIVEFIX2, 2026-10-09).** The template also ships `data/kpi_completeness/schema-seed.parquet`
(zero rows, the store's nine columns) and the Job's ownership marker `.kpi-completeness-output`, so a fresh Space's
tile reads as empty instead of DuckDB's "No files found" (which also leaked the server path). The marker is needed:
without it `claim()` refuses the pre-existing directory and the first run fails. The seed stays beside the Job's
file; the Dataset's `**/*.parquet` glob unions the two by name. Pinned by `KpiCompletenessWidgetTest`.
**Editor field (operator, 2026-10-09).** The gap node carries a `NodeAttributes.GAP` spec — `file_template`
and `seq_scope` only (`sequence` keeps its grammar in the dialog's free-form section). The node lowers to
`collector.gap_detection` (`PipelineEditable.gapSection`); `NodeConfigNameContractTest.gapTemplateAttributesReachTheEngine`
proves the round trip.
**Signal-type constants (§8-C, operator, 2026-10-09).** Core dotted Signal types live in
`com.gamma.signal.SignalType`; each optional module keeps its own in a constants class beside its jobs
(`ReconSignals`, `BackupSignals`, `ScreeningSignals`, `ScoringSignals`, `CaseSignals`, `OpsSignals`), so core
never names module vocabulary. Every value is pinned byte-for-byte by `SignalTypeTest` / `<Module>SignalsTest`.
No dotted literal is left at an emit site (2026-10-10): `JobService` and `LaIndexBuildJob` (Link Analysis is core, so `LA_INDEX_BUILD_COMPLETED` sits in `SignalType` beside `LA_DETECT_COMPLETED`) use `SignalType`. `risk.score.produced` is a cross-module contract — scoring emits it, core's `CollectorService` matches it — so it lives in core `SignalType.RISK_SCORE_PRODUCED` and `ScoringSignals.RISK_SCORE_PRODUCED` aliases it; they cannot drift. ⚠ The `JobTypeDescriptor` emitted-signal lists in `JobService` stay literal: they are catalog declarations, not emit sites.
Design archived: `archived-documents/plans-archive/completeness-kpi-k4-design.md`.

**The two deviation bases (operator, 2026-08-30 — ⛔ do not re-ask):**

- **File deviation is EXACT, from the sequence template** — the template already implies how many files a
  period should hold.
- **Record deviation is STATISTICAL, from a rolling prior-period baseline.** ⛔ There is no declared
  expected row count and **inventing one was refused**: a fabricated target would make every deviation an
  artefact of the fabrication rather than a fact about the data.

**Two ways this can silently report nothing. Both must fail loudly** — a completeness KPI that quietly
reports zero is worse than one that is absent, because it manufactures false confidence in exactly the
number it exists to check.

1. `-Dconsignment.outputs.backend=none` makes recording a no-op (fail-open by design). The job must
   **refuse and say so**, never emit zeros.
2. ⚠ **`bounds` is nullable** for sinks with no partition or event time — dataset and enrichment sinks.
   Those pipelines' daily counts are **UNKNOWN, not zero**, and the KPI must carry that distinction **end to
   end**. 🔴 Only the first trap ever reached the board; this one is now `KPI-UNKNOWN-1`.

**Why `consignment_outputs` is the substrate, and what was rejected** (the correction is load-bearing — the
operator's own first choice could not answer the question):

- ⛔ **`CommitLog`** was that first choice. One row per batch, **no partition or day column**: a batch spans
  record-days and a record-day receives rows from many batches, so per-day counts are **not derivable**.
  Durable and default-on — simply the wrong shape.
- ⛔ **The `_lineage_<runTimestamp>.csv` ledger** *does* carry `partition,row_count` per record-day, but it
  is **buffered rather than fsync'd** and written **one file per run**, so reading it means globbing many
  files and it can lose a tail on a crash. Not a ledger to build a KPI on.
- ✅ **`consignment_outputs`** — durable, a per-output-file `rows` count, `record_day`/`bounds`, written from
  the ordinary ingest path, on by default since 2026-08-10. See `okf/product/module/engine/spec-db-layer.md` §3.9.

⚠ **Naming tension, stated rather than hidden.** The requirement is "a KPI *not related with* Consignment"
and the chosen store is *named* `consignment_outputs`. The coupling is nominal — it is the ordinary
per-output-file registry on the normal write path, not a Consignment-only structure. **If the name later
matters, that is a rename, not a redesign.**

**Verify gates.** These are the acceptance criteria; K1's and K3's had no home anywhere before this.

| Slice | Gate |
|---|---|
| **K1** | a day receiving rows from several batches sums correctly · a disabled registry **refuses** rather than returning zeros · a null-`bounds` sink reads UNKNOWN |
| **K2** | a window-edge silent hour is found · an interior hole is exact · the undetectable tail and the uncountable empty bucket are both pinned |
| **K3** | steady reports no deviation · a halved day breaches · an empty history reads `NO_BASELINE` · the unknown-day bucket neither raises the baseline nor stands in for a missing target day |
| **K4** | real cron arming · a breach opens **exactly one** Incident across repeated runs · a pipeline whose registry is off **fails the run visibly** |

🔴 **K1 is the whole risk.** K2–K4 are assembly over existing parts; K1 is the only slice that has to be
right about what the data actually says.

`VolumeBaseline`'s contract is worth keeping here because it is counter-intuitive: a day **absent** from
K1's series is **not a zero** (absence covers both "received nothing" and "was not expected to run"), so
absent days never enter the baseline and an absent target day is `NO_OBSERVATION`; the baseline is the
**lower median, not a mean**, so one recompute spike neither manufactures nor masks the next day's breach;
and a **zero baseline yields a null deviation** — undefined, not −100 %.

### 3.10 Baseline Expectations (`DUCKLE-C8`, 2026-09-24)

The sixth Expectation kind, `baseline`, asks *"does this input look like the inputs we accepted?"* rather
than *"does any row violate a rule?"*. It **profiles** the target's at-rest data (`row_count`; per profiled
column `null_count`, `null_rate`, `distinct_count`, `min`, `max`, `mean` — the last three numeric via
`TRY_CAST`, so `null` and never compared on a text column), per group when `groupBy` is set, and compares
every cell with the **median** of the same cell over the last N **accepted** profiles. One limit pair
(`maxIncrease` / `maxDecrease`, `percent` of the baseline's magnitude or `absolute`, inclusive) applies to
every checked cell; an author wanting different limits per measure writes a second baseline Expectation.
Keys and defaults: [step-catalog § Expectations](../elt/spec-step-catalog-outbound-sinks.md#qualityconstraintcheck--expectations).

**Storage — built first, because nothing existed.** `BaselineProfileStore` keeps one JSON document per
Expectation at `<write-root>/expectation-baselines/<name>.json`: every recorded profile (accepted or not)
plus an `ops[]` audit log. It is durable or absent — **no write root ⇒ 503**, and an unreadable document is
an error (**503**), never "no history": reading a torn file as empty would silently reset the baseline and
pass whatever came next. Refused and accepted profiles are capped separately (100 each), oldest first, so a
run of refusals can never evict the baseline. Deleting the Expectation deletes its history, so a re-created
one of the same name does not inherit it.

**The acceptance rules, as built:**

* **Every evaluation records its profile** — a FAILED (refused) run included — unaccepted.
* **A profile is accepted only if the whole run succeeded.** `POST /expectations/{name}/evaluate`: this
  check PASSED. `POST /expectations/evaluate` (the sweep): **every** enabled Expectation in the sweep PASSED
  — one failing `non_null` anywhere leaves every baseline profile of that sweep recorded but unaccepted.
* **Cold start:** no accepted profile (or a cell no accepted profile has) ⇒ nothing to compare ⇒ that cell
  passes; the first successful run's profile becomes the baseline.
* **`requireExistingGroups`** (needs `groupBy`): a group absent from the input is a `missing_group`
  violation when its median row count over the window — **absence counting as zero** — is above zero. So a
  group seen once in a long window is not "existing"; a group the baseline typically has is.
* **Audited ops**, both `canOperateRuns` (the same gate as evaluation — they change what the next run
  passes): `POST /expectations/{name}/baseline/accept` `{profileId?}` (default: most recent; 404 unknown ·
  409 already accepted) and `POST /expectations/{name}/baseline/clear` (un-accepts all; history kept). Each
  appends an `ops[]` entry carrying the actor and **the value it replaced** (`replaced.window` = the accepted
  ids that were the window before an accept; `replaced.accepted` = what a clear un-accepted) and returns it;
  `AuditTrail` records them as `expectation.accepted` / `expectation.cleared`. 422 on a non-baseline kind.

`lastResult` of a baseline run carries `profileId`, `baselineSize` and up to 50 `findings`
(`group`, `measure`, `column`, `baseline`, `current`, `direction` = `above` · `below` · `missing_group`);
`violations` is the true total. A `groupBy` yielding more than 1,000 groups is refused (422), not stored.

⚠ **Not `VolumeBaseline` (§3.9).** That design uses the *lower* median and treats an absent day as no
observation; this kind uses the ordinary median (an even window averages the middle pair) and counts an
absent *group* as zero rows. The two answer different questions and must not be unified by accident.
⚠ The record constructor (`Expectation.Baseline`) is the whole value validator — Expectations are not in
`AcceptedConfigKeys`; `ConfigSpecs.expectation()` declares the eight keys so the authoring census accepts
them, plus a `baseline-needs-a-limit` rule. ⛔ **Not built:** Studio buttons for accept/clear (API only;
the form authors the kind), and per-measure limits.

## 4. Decisions (dated one-liners)

| Date | Decision | Who / where |
|---|---|---|
| 2026-06-29 | Notifications + audit trail shipped (`ddfa288`): **no message broker** — in-process virtual threads over an append-only `EventStore` is the idiom; email is an edition seam | `events-metrics.md` §Notifications; archived notification plan |
| 2026-06-29 | `AuditTrail` is **one interceptor in `ControlApi.dispatch`**, not per handler; authentication events out of scope (deferred, later made a standing entry) | same |
| 2026-07-06 | Signal network: **full unification** — Event/Alert/Notification become views over one ledger, not additive-converge | product owner, `signal-network-plan.md` (archive) |
| 2026-07-08 | OPS-5's discharge criterion is the signed verification protocol; cannot close offline | `docs/okf/product/layer/platform/tests-provenance-conservation.md` |
| 2026-07-12 | Maintenance = tasks on the `maintenance` Job Type, never shell/OS cron; dry run is fail-closed (no preview ⇒ no-op); `/health/details` auth-gated, `/health` stays public; authoring stays in Workbench → Jobs | MNT plan (archive), `jobs.md` §Maintenance |
| 2026-07-18 | **Signal-primary flip** (D1a): implement the `openapi-v1.json` `Signal`; `Event` becomes the projection | operator, `event-signal-backbone-plan.md` (archive) |
| 2026-07-19 | No `@PublicApi` bump for the envelope change — 4.x unreleased (D2); AG-UI is a thin edge adapter, "AG-UI-shaped, domain-named" (D3) | same |
| 2026-07-22 | `/signals/tree` requires `correlationId`; tree and `signal_timeline` share `Signals.assembleTree` | `signal-backbone.md` |
| 2026-07-23 | Conservation imbalance gets a built-in notification rule (`ops`, WARN) so both LOSS and AMPLIFICATION notify | `events-metrics.md`; verification protocol |
| 2026-07-23 | `storage_trend`: `created_ms` is the sort key (ISO strings are not reliably chronological); predictive maintenance deferred to AGT-5 | `jobs.md` |
| 2026-07-25 | Incident retention is a **retention tier, not archive-is-terminal** (D5) | `jobs.md` §incident_purge |
| 2026-07-25 | `inspecto_legacy_api_requests_total` removed; `/health`, `/ready`, `/metrics`, `/metrics/acquisition` are the four routes that stay unversioned | `events-metrics.md`, `ControlApi.isInfraRoute` |
| 2026-07-27 | `incident_purge` (MNT-14): `retention_days` required, no default; derived from `closedAt`; legal hold fail-safe and re-checked in `purge()`; **G3 — a purge is not "all trace removed"**; **nothing schedules it and nothing should** | `jobs.md`, `operations-reference.md` §Retention |
| 2026-08-01 | The run-claim hand-off seam moved from the global `ingestLock` to `PipelineRunGuard`; every `EventLog` subscriber must hand off | `events-metrics.md`, `signal-backbone.md` §Gotchas |
| 2026-08-12 | **Per-step Signals refused** — in-memory step progress, poll-read (S7/G6) | consignment-chain plan (archive) |
| 2026-08-26 | D11: `maxConcurrentRuns=4` on by default, owned by `scheduler.toon`/`/system/scheduler`; `-D` is bootstrap only; **no computed cap**, preview connections uncapped, `max_temp_directory_size` no default | `jobs.md`, `d11-resource-caps-plan.md` (archive) |
| 2026-08-29 | A recompute that superseded revisions WARNs when no enabled `retire_superseded` job exists — the silent disk cost made loud | `operations-reference.md` |
| 2026-08-30 | The audit-retention window is **one year**; applied by a partition delete, never a SQL `DELETE` | operator; `controls-matrix.md` G5 |
| 2026-08-31 | `-Dstatus.backend` default flipped to **`db`** | `OperationalDb.java`; `operations-reference.md` §Status backend |
| 2026-09-02 | `event_prune` shipped (COMPLY-3); `partition_prune` followed 2026-09-06 | `jobs.md`, `EDITIONS.md` JOB-03 |
| 2026-09-07 | EDG-01 cell 2: backup tasks moved to `inspecto-backup` via `MaintenanceTaskProvider`; duplicate claims refused fail-closed; Basic refuses loudly, never a silent SKIPPED | operator; `EDITIONS.md` OPS-06 |
| 2026-09-07 | EDG-01 cell 5: only the `/metrics` **exposition** gated (`inspecto-metrics`); `MetricRegistry` stays core; path stays public — "a scraper carries no token" | `EDITIONS.md` CP-13 |
| 2026-09-07 | EDG-01 mechanism: **ServiceLoader modules, ⛔ not `-D` capability switches** — a switch leaves the code in a bundle that ships no authenticator and binds every interface | operator; `EDITIONS.md` §EDG-01 |
| 2026-09-07 | `living-operational-system.md` distilled to the OKF north star with **no as-built column** ("a state column rots") | `BACKLOG.md` §5 |
| 2026-09-08 | EDG-01 cell 6: the events feed + audit CSV export moved **whole** to `inspecto-events` (gating the CSV alone needed an `if` inside a core route, banned by §Assembly); recording not gated; new core `AuditLogRoutes` keeps `/audit/*` on every edition | operator; `EDITIONS.md` CP-13, §Audit |
| 2026-09-08 | This spec: OPS-2's "ungated" cell, OPS-3's "tamper-evident"/"sign-ins", OPS-4's "off by default" and the 7-field GLOSSARY envelope corrected; `OPS`/Ops-Lens naming applied to REQUIREMENTS §3.7 | this file §2 |
| 2026-09-24 | `DUCKLE-C8` baseline Expectation kind shipped with its durable profile store; acceptance only on a whole successful run (the sweep counts as one run); accept/clear gated `canOperateRuns` and audited with the replaced value | §3.10 |
| 2026-09-26 | Read-shaped POSTs, as declared in `CapabilityManifest.EXEMPTIONS`, are not audited — the manifest is the single list; a read is classified like a GET, so a read-shaped export stays audited as an export (R2-12) | operator; §3.3 Layer 3 |
| 2026-09-28 | A mutating request that fails with a 5xx is audited as an attempted action: one `AUDIT` row, "(failed, HTTP n)", classified like a success, carrying the Correlation-ID; recorded on the failure path in `routeDispatch`, so never twice (`AUDIT-ERRORED-REQUEST-UNRECORDED-1`) | session decision; §3.3 Layer 3 |

## 5. Not built

### 5.1 Tracked (a `docs/BACKLOG.md` row exists)

| Item | Row |
|---|---|
| OPS-5 provenance conservation **live soak** — no code left; outcome log empty | §2 *OPS-5 provenance conservation* |
| Deployment-topology live validation (closes OPS-5's "needs a live deploy" too) | §2 *Deployment topology live validation* |
| Compliance program NFR-7 external sub-items; G6 restore drill record + RTO/RPO targets | §2 *Compliance program (NFR-7)*; §5 *Compliance repo-side artifacts* |
| Signal/Decision follow-ons: optional S8 (connector-direct emission, cross-space controller); a general event-triggered consequence policy gate (today `/apply`-only); RFC 6902 AG-UI deltas | §3 P3 *Signal / Decision networks* |
| Maintenance COULD tier — **the full list, landed 2026-09-10 (Sprint 7.2) from the archived maintenance plan, where it was its only home**: space-to-space comparison (✅ **SHIPPED 2026-09-24** as the `space.comparison` Job Type — `okf/product/module/processor/spec-jobs.md`; residuals on the board) · predictive maintenance (AGT-5) · AI recommendations (AGT-5 P1+) · self-healing · backup **encryption** and compression tuning (the backup writer is a plain zip: no cipher, no compression-level knob) · **incremental/differential** backup (the writer walks the whole tree every run — no diff, no watermark) · backup **deduplication** · a **health score** (the health route answers UP/DOWN/NOT_CONFIGURED only; a numeric composite was deferred in the plan itself) · **agent-session retention** — ⚠ **blocker still live (checked 2026-09-10)**: agent sessions are a `ConcurrentHashMap` cleared on shutdown, and none of the module's four durable rings backs one, so there is nothing to retain yet. ⛔ Two of the plan's COULDs must NOT be carried forward: **growth-trend analysis + archive recommendations SHIPPED** as `storage_trend` (§3 above dates it), and **Dev/Prod maintenance "profile presets"** is refuted as framed — job templates exist, but no Dev/Prod tiering concept exists anywhere in `spaces/`. Its open question *"does archived material need its own retention tier"* is answered by the code: **archive is terminal** — `incident_purge` hard-deletes, and `archive_instead_of_delete` moves files once and never re-walks them | §3 P3 *Job framework* |
| D8 residuals (soft-bounce retry, SES/SNS, GeoIP, per-user prefs) — `INC`'s, listed for the ledger's sake | §3 P2 *Notifications*, `D8-SUPPRESS-1` |
| Standing LEAVEs recorded as working-as-designed: MNT-14 has no UI and no shipped instance; "nothing prunes by default"; `-Djobs.maxConcurrentRuns` is the only bound; `AuditTrail` has no authentication events; D11's uncapped preview connections; `BackupTask.restore`'s jail (PATH-2); ARCH-OPS-SCC (85-file ripple) | §6 |

### 5.2 UNTRACKED — found 2026-09-08, no board row yet

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-GLOSSARY-1`, `SPEC-ORPHANPAGE-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** `GET /signals/stream` **ADOPT** — the Events pane subscribes, polling stays as the fallback → `CLIENT-HALVES-1` (row closed; the Events pane subscribes to `/signals/stream`).
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

1. ✅ **CLOSED — `GET /signals/stream` (SSE) has a client.** The Events pane subscribes through
   `EventsService.stream()` (an `EventSource`), polling stays as the fallback (`CONSUMER-PAIRS-1`).
2. **No `event_prune` Job instance ships anywhere** (`spaces/`, `docs/ops`), so the one-year audit window
   is stated policy on every deployment that does not author it. Either ship a disabled template in
   `spaces/demo` (like `retention_job_template`) or record the omission as a deliberate operator act the
   way `incident_purge` is.
3. ✅ **CLOSED 2026-09-27 — tamper-evidence shipped** (`ASSURE-AUDIT-CHAIN-1`: hash chain, signed daily
   anchors, `GET /audit/verify`; AU-9 evidence §6).
4. **The Overview's raw-metrics toggle on Basic** calls `/metrics` and receives `503`; the tile has a
   per-call `catchError` but whether it renders the *explained* state (module absent) rather than a
   generic failure was not verified.
5. **Three Ops Lens screens have no OKF Feature page**: Audit log, Processing status, and the Scheduler /
   Operational-DB settings panes (`okf/product/index.md` already says "not yet documented").
6. **`operations-reference.md`'s route table** carries no `/audit/*` and no `/health/details` row — the
   core audit read surface and MNT-15 were absent from the long-form operations manual. Two rows were added
   in this commit; the rest of that 1,100-line Reference still carries its own "not the authority" banner.
7. **`docs/okf/product/module/backup/admin-guide-backup-restore.md`** cited its plan of record at a `docs/superpower/` path that
   no longer exists (archived) — fixed in this commit; the runbook also predates cell 2 and still reads as
   if backup were core (the edition caveat is one line).
8. **`ChannelConfig` cannot override a rule-level `template`** — the Signal-Backbone S2 slice archived
   unbuilt; recorded in the `INC` spec §5, repeated here because the field lives on the ledger side.
9. **`SignalIngress` is tested but deliberately unwired** (its ERROR+ floor differs from the
   `ContextBroker`'s WARN+); `AgentAskResult.artifact` has no live producer — `AGT`'s to decide, listed
   because both ride this ledger.
