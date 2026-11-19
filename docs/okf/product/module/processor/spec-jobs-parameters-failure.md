---
type: Concept
title: Jobs & Scheduling — The parameter contract, failure handling and single-seam properties
description: Part 2 of 4 of Jobs & Scheduling: The parameter contract & runtime Expressions (shipped 2026-08-07/10); Failure handling: retry, alarm, misfire, re-run (JOB-RELIABILITY-1, decision D8, 2026-10-27); Three single-seam properties, confirmed by audit (2026-09-15)
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
item: module.processor
audience: internal
status: current
verified: 2026-11-19 last-edit
code: inspecto
---

# Jobs & Scheduling — The parameter contract, failure handling and single-seam properties

Part 2 of 4 of [Jobs & Scheduling](spec-jobs.md) · previous: [The Job Framework](spec-jobs-framework.md) · next: [Maintenance jobs and the push and process jobs](spec-jobs-maintenance.md).

## The parameter contract & runtime Expressions (shipped 2026-08-07/10)

**This section is the design of record.** *(Provenance: the plan's 17 steps, each with the wrong premise it
corrected, are in [`job-parameter-contract-plan.md`](../../../../archived-documents/plans-archive/job-parameter-contract-plan.md)
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
  a processor (`JobPackManager` registers five other SPIs, parsers the fifth since 2026-09-25), so the `ServiceLoader` set is authoritative and
  there is no overlay to merge. 🔴 It loads through the *same* `ServiceLoader.load` call as
  `ConsignmentProcessJobType.fromServiceLoader`, so the catalog cannot disagree with the lookup that
  resolves an id at run time; a provider the classpath cannot produce is **counted and skipped, and the scan
  continues past it** — a stale services entry throws from `hasNext()`, so stopping there would silently drop
  every provider listed after it. → [post-sync-step-chains](../elt/spec-pipeline-execution.md)
* **`mail.send` + the `mail` Platform Service** are the reference generic Job Type (§9 verbatim): it
  reuses the `NotificationChannel` seam rather than opening a second SMTP client, and declares
  `requires: [mail]` because a Job that mails outward must declare that reach. ⚠ A built-in with an
  unsatisfiable `requires` is **accepted** — only the pack/classpath paths refuse (S1-7). CC is deferred
  (BACKLOG §4): the channel seam takes one recipient list.
* **`$upstream(<job>).artifact(<name>).<attr>`** exposes a predecessor's latest artifact. Its two
  event-time attrs resolve **live** against the Consignment output registry keyed by the artifact's `ref`,
  which is why they replaced the single `time_range` attr no consumer could use — one opaque
  `"<min>..<max>"` string that `SqlParamScanner` substitutes as one SQL literal and nothing split. See
  [`consignment-addressing.md`](../elt/spec-consignments.md).

### The operations zone (`-Dops.timezone`, shipped 2026-08-15)

`com.gamma.util.OperationsZone` resolves the one zone that governs **both** cron firing
(`PipelineScheduler` → the `cronDue` comparison; `JobService` → `scheduler.cron`,
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
  `*_meta.toon` files and `MetadataGraphService` merges them last-non-blank-wins. Full reasoning:
  [`domain-timezone-behaviour-plan.md`](../../../../archived-documents/plans-archive/domain-timezone-behaviour-plan.md).
- **Two more sites adopted it 2026-08-15**, because neither was a judgement once grounded:
  - `EventRoutes.epochMillis` — a bare `yyyy-MM-dd[ HH:mm:ss]` bound on `/events` is an **operator's wall
    clock**, typed into a console beside a schedule that already fires in this zone. ⚠ The resolve happens
    **outside** the parse's `catch (RuntimeException)`: inside it, a misconfigured `-Dops.timezone` would be
    caught and returned as a **400 blaming the query the operator just typed**.
  - `PackTestHarness` — its production counterpart `JobService` had **already** moved, so the
    harness was dry-running `$today` in a different zone than the real run. That is not a display choice, it
    is a harness that can go green for the wrong date; the whole point of the harness is that a pack green
    here is a pack the engine accepts.
- ✅ **Three sites stay on `systemDefault()`, to match their writers — DECIDED 2026-08-15. The sweep is
  CLOSED; this is the end state, not a deferral.** `AlertService` (cutoff vs. the ledger's
  `end_time`/`start_time`, written by `ConsignmentIngestor`), `ReferenceCompactor` (literal vs.
  the `__valid_from` column, written by `ConsignmentIngestStrategy`), `InspectoTools` (parse vs. the audit
  CSV, written by `JobService`). Each is the **read half of a write/read pair** over a **zone-naive**
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

## Failure handling: retry, alarm, misfire, re-run (`JOB-RELIABILITY-1`, decision D8, 2026-10-27)

**Survey, before the change.** Existing: non-overlap + cross-pod claims; a `catch_up: true` cron job runs once on
startup when a fire was missed while the server was down (`JobService.catchUpMissedFires`; needs at least one earlier
run as baseline; off by default); `POST /jobs/{name}/trigger` and `POST /jobs/runs/{runId}/replay` (both
`canOperateRuns`); a `job.run.failed` CRITICAL Signal only for a *thrown* failure; a built-in notification rule
`builtin-job-failed` (event `JOB_FAILED`). Missing: any retry (the `retries` parameter of `sample.hello` was declared
and read by nothing); **nothing ever emitted `JOB_FAILED`, so the built-in rule was dead and a failed scheduled Job
told nobody**; the Jobs list showed only the status label, not the reason.

**As built.**
- **Retry** (`RetryPolicy`). Authored `retries:` (0–5) and `retry_backoff_seconds:` (first wait, doubles per retry, cap
  600 s, default 30 s); both are framework keys, never reported undeclared. Default 2 retries for `sql.template`,
  `report`, `risk.score`, `anomaly.score`, none for other types; `retries: 0` switches it off. Only a **transient**
  failure is retried: an `IOException` / `SQLException` (or either as a cause) thrown by the run body. ⛔ A SQL-guard
  refusal, config error, missing parameter, returned `FAILED` result or deadline expiry is permanent and is never
  retried. The retries run inside the one Run (claims and deadline stay held, so no overlap); each attempt is
  logged to the Run Log.
- **Alarm** (`JobService.raiseFailureAlarm`). When a Run ends `FAILED` — after its retries — and was not fired by hand,
  it emits a `JOB_FAILED` event (correlation id = run id; attrs `job`, `type`, `runId`, `trigger`, `error`), which the
  built-in `builtin-job-failed` rule turns into a notification, and opens one Incident per Job (`dedupe: job-failed:<job>`,
  severity ERROR) when the operational-objects seam is wired. A manual Run is not alarmed: its author is watching.
  Best-effort — an alarm failure never changes the Run's recorded result.
- **List** — `JobView.lastMessage` carries the last run's error text only while that run is `FAILED`; the Jobs list
  shows it in a **Last error** column. **Run again** (arrow icon) appears on a failed Job row and on a failed row of the
  run history, reuses `POST /jobs/{name}/trigger`, and is gated on `canOperateRuns`.
- **Misfire** — unchanged: `catch_up: true` is the explicit, per-Job opt-in; without it a missed fire is skipped.

**Deferred.** Retry of a *returned* `FAILED` result (a Job type cannot yet say "transient"); catch-up of more than one
missed fire; a catch-up baseline for a Job that has never run.

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

### Journal read-backs stream, they do not load (`AUDIT-LOG-UNBOUNDED-READ-1`, 2026-09-24)

Every read-back of an append-only journal goes line by line through `Files.newBufferedReader`, never
`Files.readAllLines`: `JobRunLedger.lastStartTimes` / `lastSuccessEnd` / `lastSuccessRunId`
(`jobs_runs.csv`), `RunLogStore.read`, `RunArtifactStore.read`, `com.gamma.etl.CommitLog.committedBatchIds`,
`PipelineRenameRoutes.readPendingRenames` (`rename.journal`) and `SnapshotStore.attachmentsOf`
(`attachments.jsonl`). The answers are unchanged: same UTF-8 decoding, same line splitting, and no new cap.
A torn trailing line is handled as before. The CSV folds skip it as malformed. The two JSONL stores still
fail the read loudly.

- ⛔ **Do not "optimise" the `JobRunLedger` reads into a tail read.** They are folds, the *latest by
  timestamp* and not the last row: runs append at their END, so a long run started earlier lands after a
  shorter one started later. `JobRunLedgerStreamedReadTest` puts the newest row first to pin this.
- **Deliberately left whole-file:** `PartitionCompactor.heal` and `ReferenceCompactor.heal` (the journal
  is one compaction's candidate list, already in memory during `compact()`, and deleted on success), and
  `BranchCommitLog.forEachRow` (one file per batch, deleted once the batch commits). These are bounded, not
  growing, so streaming them buys nothing.
- No shared helper: no caller wants "the last N lines", so the tail-read helper the row suggested has no
  user.
