---
type: Capability
area: PIP
title: Pipeline execution (PIP) — capability plan
description: The requirement of record for running work (PIP-2 through PIP-7) — purpose and scope, the corrected execution rows and counts, dated decisions, what is not built, what was refused or superseded, and how it is verified. The as-built specification lives in spec-pipeline-execution.md and the files it names.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §3.3 PIP-2 through PIP-7, and EDITIONS JOB-03/JOB-04/CP-03 (this file corrects them, see §2)
timestamp: 2026-11-18T00:00:00Z
---

# Pipeline execution (`PIP`)

## What this covers

The plan of record for how Inspecto runs work: the ingest engine that moves files into stores and the Job
engine that works over data at rest. It states what is required (and where older registers are wrong), the
decisions taken and when, what is still open, what was deliberately refused, and how the claims are checked.
It is trimmed: the as-built mechanism that used to be §3 now lives in the spec files (§3 below maps each old
section to its new home), so this file stays a short list of commitments and open work.

## Contents

1. Purpose & scope
2. Requirements of record (the six execution rows, one number per fact, bounded or absent rows, stale
   defaults)
3. Specification — where it now lives
4. Decisions (dated one-liners)
5. Not built (externally gated, actionable, findings filed from this spec)
6. Refused & superseded
7. As-built pointers — where they now live
8. Verification

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.3 or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is
> stated in place. §3 maps the specification to the spec files, §4 the dated decisions, §5 what is not
> built, §6 what was refused, §7 the pointers, §8 how it is verified.
>
> ⛔ **This is the second of two `PIP` specs.** Authoring — the editor, the config contract, the Step
> catalog — is [`pipeline-authoring`](../elt/plan.md), which owns `PIP-1`.
> This file owns **`PIP-2` through `PIP-7`**. "Orchestration" is folded in here; the word is not a
> glossary term.
>
> ⚠ **When written (2026-09-09) the area had twenty-seven current documents and six plans holding decisions
> of record**, one declared single-owner concept page, and exactly **one** practice-typed page — the
> smallest file in the set; the operator-facing procedure lived in a 71 KB *reference*, typed as a lookup
> table rather than a procedure. Since 2026-11-18 the engine's execution documents are
> [spec-pipeline-execution.md](spec-pipeline-execution.md), [spec-pipeline-model.md](spec-pipeline-model.md),
> [spec-sinks.md](spec-sinks.md) and [user-guide.md](user-guide.md), beside [spec-ingest.md](spec-ingest.md),
> [spec-consignments.md](spec-consignments.md) and [architecture.md](architecture.md).
>
> 🔴 **The loudest finding is a release gate resting on a class that does not exist** (§2.3).

## 1. Purpose & scope

This capability is **how work actually runs**. The organising fact — and the thing most of the
documentation blurs — is that there is not one execution engine here. There are two, they are triggered
independently, and they share one config vocabulary:

> **The ingest engine** polls an inbox, plans Consignments and drives a Pipeline over files *in motion*.
> **The Job engine** fires on cron, on a commit, on a signal or by hand, and runs work over data *at
> rest* — including a Pipeline's own second stage, hosted as a Job.

The code names this split itself: one of its trigger enums is annotated *the two-scheduler split*. Almost
every count in this area is stated two or three ways because a document measured one engine and wrote the
sentence as though it covered both.

The boundary between them is binding, not stylistic: in-motion and at-rest compose as **producer and
consumer over a shared store, never by nesting** — which is why sub-Pipelines and an `ingest` job type are
both design refusals rather than gaps (§6).

**In scope**

* **The two engines** — what triggers each, what bounds each, and where they meet.
* **The two trigger models**, their different vocabularies, and which one each board row describes.
* **The two concurrency mechanisms** and why retiring either was refused.
* **The ingest lane fork** — flat versus graph, what admits a write to the graph lane, and the flag that
  overrides it.
* **The at-rest runner** and the two lifts that serve different lanes.
* **Run identity and the ledgers** — Run ⊇ Consignment ⊇ File, the commit log, the run ledger, the async
  trigger contract and idempotent replay.
* **Resource caps and the bounded retry** — what is on by default and what is not.
* **The post-sync derived lane**, the maintenance library, and retention.
* **Failure handling** — quarantine, the orphan gate, park and drain, and the one recovery affordance per
  lane.

**Out of scope**

* Authoring anything — the editor, the config contract, the Step catalog, the registration points. All
  the sibling's.
* Acquisition mechanics (the collectors, the ledger, the stability gate) — `ACQ`.
* The signal and event backbone as a subsystem — `OPS`. This spec uses signals; it does not own them.

## 2. Requirements of record

### 2.1 The six execution rows

| Row | Register says | Correction of record |
|---|---|---|
| **PIP-2** | Medallion ELT: raw → clean partitioned → derived — `SHIPPED`, all | ✅ Accurate. Worth adding what the register does not say: the derived tier is a **separate post-sync lane**, Consignment-addressed and read **after** commit, because emitting from the sink would pipe the pre-commit scratch relation. |
| **PIP-3** | Incremental event-driven processing: on-pipeline commit triggers, watermarks, cron + catch-up — `SHIPPED`, all | ⚠ **Two trigger models, and this row mixes them.** Catch-up is **Job-only** and replays exactly **one** missed fire; watermarks are per at-rest job, not per trigger. The pipeline-side model has no catch-up at all and adds an interval kind, a default-poll kind and coalescing. See §2.2. |
| **PIP-4** | Scheduler + Jobs (atomic Executables; **Run ⊇ Batch ⊇ File** status hierarchy) — `SHIPPED`, all | 🔴 **The hierarchy was renamed on 2026-08-03 to Run ⊇ Consignment ⊇ File**, and identity is the run-and-consignment pair, because a reprocess is a *new Run over the same Consignment*. This register is what the board, the backlog and commit messages cite, and it is still stale — as are three other current files, one of which contradicts itself twelve lines apart. |
| **PIP-5** | Async run triggers: 202 + runId + poll, idempotent replay — jobs **and** pipelines — `SHIPPED`, all | ⚠ Accurate for the contract. **But replay's backing store is in-memory** (§2.3), so the *replay* half is bounded in a way no register or board row states. |
| **PIP-6** | Job templates — `SHIPPED` | ✅ Accurate, and the design detail is worth keeping: templates resolve **once, at load**, so the scheduler and the job service only ever see plain, fully-resolved configs. ⚠ **Named for grep (2026-09-09):** the file suffix is `*_job_template.toon`, substitution is `${param}`, and a job references a template with the `template:` and `params:` keys. The mechanism was described here; the four literals a reader would search for lived only in `REQUIREMENTS.md`. |
| **PIP-7** | Maintenance job library (retention, compaction, housekeeping) — `SHIPPED` | 🔴 **Understated by a factor of five, and nothing is armed.** The row names four tasks. The engine handles **23** built-in ids, and five more arrive from providers (§2.2). The "curated library" it points at is **five example job files** an operator must copy; no scheduler config ships, and the shipped default space contains no maintenance job at all. The whole housekeeping posture is opt-in — which is correct by decision, but "SHIPPED" without that word misreads. ⚠ **Named for grep (2026-09-09):** the contributed backup-verification task id is `backup_verify`, and the retention tasks take `min_age_days` — ⛔ keep it beyond the reprocess horizon, because reprocessing a compacted-away consignment is unsupported. |

### 2.2 One number per fact

Every count in this area is stated two or more ways, and the pattern from the authoring spec repeats: the
*narrative* documents are wrong and the *generated or catalogued* ones are right. Measured 2026-09-09:

| Fact | Measured | What the documents say |
|---|---|---|
| Job types | **10 core + 2 from the operations module = 12** | the glossary says 4 in its binding entry and 9 in its own rename table; the jobs concept page says 4, naming a deprecated enum; the board lists 10 — but a *different* 10, omitting alert evaluation and the sample, and including the two module ones |
| Maintenance tasks | **23 built-in + 5 contributed = 28** (re-counted 2026-11-15: `MaintenanceJob.BUILT_IN_TASKS`, counting `heartbeat` and `noop` apart; contributed `backup`, `backup_verify`, `restore`, `incident_purge`, `audit_anchor_export`) | the requirement says 4; the board says 16; the jobs page says 13 + 3; **the served descriptor says 16** |
| Trigger vocabulary | **two models** — pipeline: interval, cron, event (`commit`/`dataset`), manual, default-poll, and since the push-ingest lane stream (six `PipelineTrigger.Kind`s, re-counted 2026-11-15), with coalescing and no catch-up; job: cron, on-pipeline, on-signal + guard, manual, with catch-up | the binding glossary entry lists 4 and **omits on-signal entirely** while counting one mechanism twice; the active plan says 3; the jobs page says 4; the board and the boundary page say 5 |
| Built-in node types | **49**<!--count:node-types--> | an execution page says 29 — a *fifth* wrong count of this one, which the authoring spec did not catch |
| Lanes | the declared owner says **five**; three other pages say **two**, three different ways | and the owner's own table **omits the post-sync lane** that another page calls one of two |

🔴 **The served maintenance descriptor is wrong in both directions at once, and it drives the UI.** The
authoring form is descriptor-driven, so an operator sees exactly what that string lists. It advertises
**four tasks that do not exist on Personal** — the three backup tasks and the incident purge — and the
purge is precisely the one deliberately *removed* from the built-in dispatch so it would not be on every
edition. And it **hides seven shipped tasks**: the dedup, event, partition and receipt prunes, the
reference compaction, the supersede retirement, and the heartbeat. On Personal the form offers four things
that will be refused and can never reach seven that work.

### 2.3 Three rows that are green over something bounded or absent

🔴 **A release gate rests on a class that does not exist.** The backlog's pipeline-graph row — cited as
Row 15's parity blocker — reads *"`ConsignmentGraphRunner` has zero production callers, blocked on ingest output
parity, do not discharge by wiring `engages()`"*. Measured: **there is no such class** (it was renamed in
the 2026-08-31 Consignment commit), and the class that does exist is **called in production twice over** —
its `engages()` drives the live lane admission, and its `run(...)` is invoked on the ingest path. The row
is wrong on the name *and* on the claim, and it is load-bearing for a release-gated item.

🔴 **Replay is backed by an in-memory map, capped and lost on restart.** `CP-03` lists
*validate → save → arm → test-run → run → replay* as ✅ in all three editions with no caveat, and two more
documents present replay as unconditional. The route resolves the original run from a synchronized
insertion-ordered map with an eviction hook — so **replay of anything evicted, or of anything at all after
a restart, is a 404**. The observability spec additionally misattributes that 404 to the durable job
backend flag; it is the wrong cause, and the two failure modes are opposite — replay works with the
projection off for a run still in memory, and fails with the projection on for one that has been evicted.

**The intake file cap is on by default** — `ingest.maxFilesPerCycle` = 500
(`IntakeGovernor.DEFAULT_MAX_FILES_PER_CYCLE`; operator, 2026-10-06, the 2026-09-15 soak precondition
waived). An explicit `0` — system property or per-Pipeline `intake.max_files_per_cycle` — means unbounded.
Adaptive back-pressure and `ingest.minFilesPerCycle` are unchanged; the byte cap stays off by default.

### 2.4 The default that does not exist, in six more places

The embedded database's memory cap is documented as a shipped 2 GB default in six current locations beyond
the two the editions spec already corrected. The resolver returns **nothing** when no value is installed,
no scheduler config ships, and the committed corpus sets the key to an empty string.

The instructive one: **the backlog asserts the 2 GB default in its own standing-decisions list while its
own gap row, in the same file, records the opposite** and says explicitly not to close the gap off that
decision. Two more give two further framings — one says the default is the database's own ~80% of RAM,
another shows 16 GB, and a third lists the key among "fixed ceilings" without noting that none is
installed. Five framings of one absent default.

⚠ **The measurement behind the number is sound and worth keeping** even though the default was never
installed: 2 GB was ~2.2× the highest observed peak, and the finding that matters is that peak memory does
**not** scale with input on this path — what a cap governs is the blocking operators, and they **hard-fail
instead of spilling**. An aggressive cap turns working jobs into failing ones. That is why no default is
defensible without knowing the host. ✅ **Closed 2026-09-26 (GAP-4)** by a default that does know it: 40 %
of the JVM-visible RAM ÷ 4, floored at 1 GiB (`DuckDbUtil.defaultMemoryLimit`).

### 2.5 "The only bound" is stale in three documents and in the code

Three current files state that the job concurrency flag is *the only bound on Runs*, one of them adding
*"default 0 = unbounded"*. The concurrency half of the resource-cap pair has been **on by default at four
since 2026-08-26**, owned by the server configuration with the flag as a bootstrap default only. And
🔴 **the class's own javadoc still says "0 = unbounded, the default"** directly above the constant that
sets it to four.

There are also **two** bounds, not one (§3.3), so the sentence is wrong twice.

## 3. Specification — where it now lives

The as-built specification that stood here (§3.1–§3.11) moved on 2026-11-18 (`DOCS-ELT-CONSOLIDATE-1`) into
the spec files. A "§3.x" reference elsewhere in this file resolves through this table.

| Was | Now |
|---|---|
| §3.1 Two engines | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Two engines and their triggers* |
| §3.2 Two trigger models (incl. the `dataset.write`-on-completion ordering and the `on: dataset` self-loop guard) | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Two trigger models*; the entry-node trigger grammar in [architecture.md](architecture.md), the Job trigger fields in [../processor/spec-jobs.md](../processor/spec-jobs.md) |
| §3.3 Two concurrency mechanisms (the Run bound, the ingest budget, the four-layer broker, stride scheduling) | [spec-consignments.md](spec-consignments.md) |
| §3.4 The ingest lane fork | [spec-ingest.md](spec-ingest.md) (admission, the `-Dingest.lane` flag, decision rules, scratch dir, the engagement predicate, lane parity); the parity gate's status, the per-segment admission and the reason-for-flat list in [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Lane-fork admission notes* |
| §3.5 The at-rest runner and the two lifts | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *The at-rest runner, and the two lifts* |
| §3.6 Run identity, ledgers and the async contract | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Run identity, ledgers and the async contract*; intrinsic vs inherited file status in [architecture.md](architecture.md) |
| §3.7 Caps and the bounded retry | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Caps and the bounded retry*; the memory limit in [spec-duckdb.md](spec-duckdb.md) |
| §3.8 The post-sync derived lane | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Post-sync step chains*; the Job-side decisions (registry-not-glob reads, declared composability, a summary-write failure fails the Run) in [../processor/spec-jobs.md](../processor/spec-jobs.md) |
| §3.9 Maintenance and retention | [../processor/spec-jobs.md](../processor/spec-jobs.md) (the maintenance library, contributed tasks, retention, the purge and legal hold); compaction (quiet-window, crash-journal safe, query-transparent; a compacted-away batch refuses reprocess) in [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Post-sync step chains* |
| §3.10 Failure, park and recovery | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Failure and recovery, per lane* and *Per-Step `enabled` — park and drain* |
| §3.11 Whole-pipeline dry run (`PIPELINE-DRYRUN-1`) | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Whole-pipeline dry run* |

## 4. Decisions (dated one-liners)

| Date | Decision | Who |
|---|---|---|
| 2026-06-17 | ⛔ **No `ingest` job type** — ingestion is the Pipeline's sole responsibility and an `ingest` job is a config error; a Job is strictly downstream over data at rest, never re-acquisition | design |
| 2026-06-18 | **An authored Pipeline at rest is hosted as a Job**, on the existing scheduler — the Job is the only seam that reads data already written | engineering |
| 2026-07-09 | **Job Types are plugins on an open registry keyed by string id**, not an enum; descriptors drive the generated authoring forms | engineering |
| 2026-07-09 | **An unresolved required parameter fails the Run before user code**, in a rejected state | engineering |
| 2026-07-12 | **Housekeeping is tasks on one job type — never shell scripts or host cron**, so findings emit signals an Alert Rule can consume | engineering |
| 2026-07-12 | **A task with no preview does nothing on a dry run** — fail-closed, never falling through to the real action | engineering |
| 2026-07-18 | ⛔ **`…/run` and `…/trigger` must never be merged** — one simulates into scratch, the other operates | engineering |
| 2026-07-20 | **A cron registration returns a cancellable handle and removal cancels it**, with the fire-time guard kept as a second line — otherwise a deleted job's self-re-arming chain ticks as an inert no-op **forever** | engineering |
| 2026-07-20 | **A parameter type mismatch rejects exactly like a missing required parameter** — else the raw string reaches the job's own parse and throws uncaught mid-run | engineering |
| 2026-07-20 | **Job-pack quiesce, both halves** — the loader is pinned per in-flight Run and a stale job is rejected rather than run, because a Run already executing pack code must not have its resources yanked | engineering |
| 2026-07-25 (**T15**) | **Cycle overrun, not inbox lag, is the throttle signal** — admitting fewer files cannot reduce inbox age, so throttling on it is **positive feedback** that pins a healthy-but-backlogged pipeline at the floor | engineering |
| 2026-07-25 (**D12**) | **Chunking on by default at 8 GiB** — the threshold exists for pathological single files, so it sits far above any routine input | operator |
| 2026-07-25 (**D5**) | **Incident retention is a retention tier, not archive-is-terminal** — otherwise "archived" would mean kept forever, the posture compliance must bound | operator |
| 2026-07-27 | **Named a purge, not a prune, and the window is required with no default** — a defaulted window on the one task that hard-deletes business records would be indefensible | engineering |
| 2026-07-27 | **Retention is derived from the close time, not stamped** — trade-off taken consciously: shortening the window later retroactively widens eligibility | engineering |
| 2026-07-27 (**G3**) | **A purge is not "all trace removed"** — the append-only ledger outlives the record, including its own purge entry, asserted **by a test** because it is a reviewer's first question | engineering |
| 2026-07-27 | **Correctness comes from the cutoff, not the ordering**; legal hold is re-checked *inside* the purge and dependents go before the object — the cutoff in the predicate kills the "reports zero prunable against a fully-expired corpus" failure | engineering |
| 2026-07-27 | **The memory cap was measured at 2 GB** — ~2.2× the highest observed peak. ⚠ Peak memory does **not** scale with input here; a cap governs the blocking operators, and they **hard-fail instead of spilling**, so an aggressive cap turns working jobs into failing ones | engineering |
| 2026-08-01 | **Exclusion moved from one global lock to a per-pipeline non-reentrant guard** — a reentrant lock would not have deadlocked, it would have silently re-entered and **double-ingested the inbox** | engineering |
| 2026-08-02 | **Sub-Pipeline and embedded-Job nesting ruled out by design** — in-motion versus at-rest is a *binding* line; they compose as producer and consumer over a shared store | product |
| 2026-08-03 | **Run ⊇ Consignment ⊇ File replaces Run ⊇ Batch ⊇ File**, identity being the run-and-consignment pair, because the Run is the attempt and a reprocess is a new Run over the same Consignment | vocabulary |
| 2026-08-04 | **Post-sync authors never touch the Job surface** — the context delegates member by member, because an accessor would leak the whole surface into a third-party contract | engineering |
| 2026-08-04 | **Reading is a narrow seam, never a raw database handle** — a handle makes the forbidden read-modify-write trivially expressible, which had made the plan's own acceptance test unsatisfiable | engineering |
| 2026-08-04 | **Relations come from the registry, never a directory glob** — a partition directory holds files from every Consignment that wrote that day | engineering |
| 2026-08-04 | **Composability must be declared; undeclared is refused** — non-composable measures produce quietly wrong numbers, and guarding the write alone never stopped a reader averaging an average | engineering |
| 2026-08-04 | **A summary-write failure fails the Run** — the numbers are the output, and losing them silently makes a green Run a lie | engineering |
| 2026-08-05 (**D-2**) | **A converter plus one flagged verification minor, then the legacy readers are deleted — no permanent dual format.** The flag *is* the verification window, and it is the gate on Row 15 | operator |
| 2026-08-05 (**D-9**) | **Within-Consignment dedup in v1**; the windowed keyed ledger is designed and **never faked with unbounded history** | operator |
| 2026-08-05 (**D-12**) | **The Consignment rename is taken inside the major window and sequenced last**, with read-aliases for persisted rows rather than a hard cutover — post-release the same rename costs a deprecation cycle | operator |
| 2026-08-05 (**D-13**) | **Per-Step pause with park-at-boundary semantics**, so a parked Consignment stays uncommitted; dry-run and run-to-here remain the scratch-only paths | operator |
| 2026-08-06 | **"Job" un-banned** — again the canonical user-facing term for a scheduled Executable over data at rest | operator |
| 2026-08-07 | **Capability arrives by registration, never by editing a switch**, and a collision **fails closed in all three paths** — a pack redeclaring a token is rejected whole rather than shadowing it | engineering |
| 2026-08-07 | **Three-way resolution, not two** — an unregistered token rejects, a declared token with no value here falls through; conflating them is what let a typo silently use the default | engineering |
| 2026-08-09 (**D0**) | **A Job reaches an engine facility only through a declared grant, fail-closed at registration** — an undeclared service stays absent and the menu grows by demand, never speculatively | engineering |
| 2026-08-11 | **Record dedup moved out of the first stage** for failing the batch-independence test — the dividing rule is **batch independence**, not statelessness per record | engineering |
| 2026-08-12 | **Consignment ordering defaults to file arrival time**; name-ordering is the opt-in for feeds with unreliable stamps, and any other value is refused at parse | operator |
| 2026-08-15 | **One operations zone governs both cron firing and the date-token family** — a job firing just after midnight in one zone and resolving its date token in another is an **off-by-one-day generator**. Unset inherits the host; set-but-unresolvable **throws at startup**, never a silent fallback | engineering |
| 2026-08-15 | 🔴 **It is not the data's timezone key** — that describes the data's timestamps, and wiring it into the scheduler would make firing depend on **directory scan order** | engineering |
| 2026-08-15 | ✅ **Three reader sites stay on the host zone and the sweep is closed** — each is the read half of a pair over a zone-naive stored string; ⛔ converting a reader alone silently offsets every window, and in the compactor's case **drops rows** | engineering |
| 2026-08-15 | ⛔ **No key writable through the settings document may also be read from a launch flag at use time** — split ownership of one fact is exactly what that decision forbids | engineering |
| 2026-08-25 | **A permit counts a Consignment being executed — never a Pipeline, never a run**, so the four tiers are comparable numbers | engineering |
| 2026-08-25 | **Stride scheduling, not priority ordering** — the lowest priority *provably* keeps a non-zero share, with no banked credit, and the ratio is unit-testable | engineering |
| 2026-08-25 | ⛔ **The ingest run budget is not redundant with the broker** — a claim the plan made and the code refuted; it bounds everything a cycle does *before* the first broker permit | engineering |
| 2026-08-25 | **A shrink drains, never interrupts** — aborting a mid-commit ingest is not something the commit sequence was designed for; it is crash-*idempotent*, a different property | engineering |
| 2026-08-26 | **Route executes on the poll-driven ingest path**, diverting at the one choke point that holds the live connection and the materialised table | engineering |
| 2026-08-26 | **Fail-closed route arming — six rules, each refusing by name**, because every refused shape would drop rows *silently* | engineering |
| 2026-08-26 | **The arming rules have one statement and two callers** in two config states — restating them over raw maps is the hand-mirrored-map drift this repo has already paid for three times | engineering |
| 2026-08-26 | **The save-time arming pre-check ships, and the earlier reason for deferring it was wrong** — arming did validate at load, but neither validate nor write called it, and **a fail-closed gate the author never sees is a log line** | engineering |
| 2026-08-26 | **Provenance follows the key, never file presence** — the cap is nullable end to end, and an explicit null hands ownership back to the flag live. Before it, saving anything on the form **silently seized provenance to the file forever** | engineering |
| 2026-08-26 | **The memory-limit grammar is served, never mirrored**, anchored and portable, with the bound **inside** the pattern — a bound beside the grammar lets the form accept what the write refuses | engineering |
| 2026-08-26 | **Every scheduler-settings change is journalled with deltas**, only actual changes and only moved keys — a path-classified audit row cannot carry *what the numbers became*, which is the half an incident review asks for | operator |
| 2026-08-26 (**D11**) | **The resource pair ships, both owned by the server configuration** — the Run bound default-on in code, the memory limit served with **no code default**, because total exposure is the limit times the concurrent Run count | operator |
| 2026-08-26 | **The Run bound counts in-flight Runs even while unbounded, and clearing reverts rather than freezes** — 🔴 a semaphore subclass that re-derived "did this Run take a permit?" from the current cap ended at **seven permits under a cap of four, permanently** | engineering |
| 2026-08-29 | **The graph lane is no longer route-only** — a non-routed pipeline is admitted wherever the write is provably reproducible, and the fan-out gains **per-destination crash resumption the flat loop never had**; parity is *proven by a differencing test*, not asserted | engineering |
| 2026-08-29 | **Decision rules are a space-registry fact, not a config property** — applied once above the fork, with their *result* part of the admission | engineering |
| 2026-08-29 | **A pipeline with no scratch directory stays flat** rather than parking its branch ledger in a shared temporary directory — a stale branch log makes the coordinator skip the branch and **the batch writes nothing** | engineering |
| 2026-08-29 | **The engagement predicate counts branches, not sink nodes** — the node-count version engaged for plain fan-out and was refuted by its own falsification test, with a stale test pin encoding the wrong belief | engineering |
| 2026-08-29 | **Disabling a Step means two different things per lane** — scratch bypasses in memory, ingest pauses durably, because a bypass there would **lose rows** | engineering |
| 2026-08-29 | **One durable home for disabled steps, and the enabled flag is structural, never a declared attribute** — a per-node key in every lower branch is a seven-branch, two-mirror drift class | engineering |
| 2026-08-30 | **Sealing dropped, not deferred** — the partition-state machinery bought nothing the completeness measure does not | operator |
| 2026-08-31 (**D2**) | **A Step receives a token and resolves data by reference** — edges never carry records; the runtime model converges later | operator |
| 2026-08-31 | **The rename landed as one commit scoped by concept, not by string** — reusing the post-sync processor's name would have broken plugin authors and the one-word-one-concept rule | engineering |
| 2026-09-01 | **Both graph sources are optional in the descriptor and "pick one" is enforced at run time** — declaring either required made the at-rest job **unrepresentable** | engineering |
| 2026-09-01 | **The orphan output-store check is default-on in every space**, one signal per transition, with a kill switch — until then it fired only from a maintenance job that no shipped space but the demo had, so a stock space had **no orphan detection at all** | engineering |
| 2026-09-01 | **One concept page owns "which lanes exist"** — there had been about six framings across five files, none authoritative | docs |
| 2026-09-02 (**X1**) | **Bounded commit retry** with durable per-file sidecars and quarantine on exhaustion — the retry already existed and was **unbounded**. ⚠ Grounding **refuted the plan's premise**: nothing classifies transient from fatal, and the real gap was the opposite of the plan's fear | engineering |
| 2026-09-02 (**X2**) | **Cross-lane provenance records which ingest Consignments an at-rest run read**, fed from the selector's kept files and **never from the rows** — ordinary outputs carry no per-row batch id, and **unknown is not empty** | engineering |
| 2026-09-02 | **One recovery affordance per lane** — reprocess retracts the Consignment's dedup claims, drain resumes a park, replay re-runs at rest | engineering |
| 2026-09-02 | **The drain does not re-walk** — it registers the park tables and runs the real finalisation, keeping the branch commits as its resume record | engineering |
| 2026-09-02 | **The lane flag ships, extracted pure so it is testable** — Row 15's precondition, not its trigger | engineering |
| 2026-09-06 | **Clone mode arms, and committed branches become visible** — the durable per-branch log already existed; only the read surface was missing | operator |
| 2026-09-06 | **The fetch lane stays first-in-first-out** — no contention has been observed, and the trigger to revisit is the **first observed fetch-lane wait** | engineering |
| 2026-09-07 | **The backup tasks are contributed, not built in** — on Personal they are unknown tasks **refused loudly**, never a silent skip a chained job would read as success | engineering |
| 2026-09-08 | **The incident purge had to be removed from the built-in dispatch, not merely also provided** — a named case always beats a contributed provider, so leaving it would silently keep it on every edition | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **A dry run skips `strategy.ingest` WHOLE rather than substituting its ~20 durable sites** — per-sink gating is the shape that misses one; skipping is true by construction. ⚖ Accepted cost: a dry run answers *what would this cycle touch*, **not** *would these files parse* (§3.11) | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **`?dryRun=true` IMPLIES `?skipPostAction=true`**, OR-ed in at two levels — a dry run that acked and deleted the customer remote file is the worst failure the feature can have, and must not depend on a caller remembering | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **The dry run PUBLISHES a MARKED `ConsignmentEvent`**, which narrowly overturns *cron/event/signal fires are always real* — safe only because there is exactly ONE fan-out, so the consumer set is closed and each was made to honour or refuse loudly (§3.11) | engineering |
| 2026-09-23 (**`DRYRUN-INVISIBLE-ON-FLAT-LANE-1` a**) | **A flat-lane dry run writes ONE provenance record flagged `simulated`** — a deliberate exception to *skip the pass whole*, metadata only and the ONLY write a dry run makes (counts are the truthful zeros of a skipped pass), so the overlay promise holds on the lane the feature shipped for rather than being retracted (§3.11) | operator |
| 2026-09-24 (**`FLAT-DRYRUN-COUNTS-ZERO-1`**) | **A dry run PARSES: `strategy.ingest` runs CONTAINED instead of skipped** — over member copies, against `forScratchRun`, under a suffixed batch id, with `EventLog.CONTAINED` bound — so the 2026-09-20 row's accepted cost is lifted without substituting a single site; provenance counts become real and each member is reported by kind (`WOULD_LAND` / `REJECTED` / `SKIPPED` / `FAULT`), the evidence X4's replay default waits on. ⛔ No replay default was picked (§3.11) | engineering |

## 5. Not built

### 5.1 Tracked, externally gated

* **Row 15 — the deletion half of the legacy flat read path.** Gate: a minor release on the trunk after the
  newest ancestor tag, shipping the converter and the lane flag. ⛔ Not closable by code, and ⛔ not to be
  started on momentum. 🔴 The earlier gate was **unfalsifiable by construction** — it named a tag on a
  retired line that can never be a trunk ancestor. Of its four steps, one is shipped, the parity gate through
  the compiled path is **met** (2026-09-16, §3), one shipped 2026-09-02, and the deletion awaits a release.
  ⚠ Its stated parity blocker is the phantom-class row in §2.3.
* **Cross-lane drill-down and the step-info envelope**, and **the batch-id rename trio** — both ride the
  next major tag. ⚠ The rename trio is **not enumerated in the drafted release notes**.
* **Distributed scheduler coordination** — ⚠ **no backlog row of its own**; it exists only as an Enterprise
  board cell and an unscoped gate.

### 5.2 Tracked and actionable

* ✅ ~~**Record-level replay from quarantine**~~ **SHIPPED 2026-09-28 (X4):** `POST /runs/{name}/replay-rejects`
  and `processing.reject_mode: eject | all_or_nothing` (§3).
* ✅ ~~**The retry deferrals**~~ **SHIPPED:** the per-pipeline `processing.retry {max_attempts, initial_backoff,
  max_backoff}` block (2026-09-25, `PipelineConfigParser`, absent keys inherit `-Dingest.retry.*`) and
  `POST /runs/{name}/retries/retry-now|cancel {file}` (`RunRoutes`; retry-now clears the backoff, keeps the
  attempt count).
* A pre-materialise cap to save fetch bandwidth —
  ⚠ the latter is **design-first** (checked 2026-09-16): no unit, config key or enforcement point exists
  anywhere. `IntakeGovernor` caps FILES per cycle after listing; a pre-materialise cap would have to cap BYTES
  or files *before* the remote fetch, which needs the connector to expose size before download and a
  decision on what happens to the remainder (deferred to the next cycle, or refused). Owed as a §1 input.
* **Branch-aware residuals** — ~~multi-schema with route~~ (segment-scoped lift shipped 2026-09-24); mid-branch
  transforms in the route verb; three node kinds still unimplemented anywhere; the destination-list
  follow-ups.
* ✅ ~~**Platform services stage two and three**~~ **SHIPPED 2026-09-28 → 2026-10-04** (S2-1…S2-3: node types
  declare an execution mode, the pack Step seam `StepExecutor` / `StepContext` with grants and watchdog; S3-1…S3-3:
  packs contribute Platform Services, provider pinning, disable/enable, read-only `DatasetAccess`); the
  job-side watchdog is `JobDeadline` (2026-10-06, §3). Design archived 2026-10-04.
* **Consignment addressing residuals** — a dead field that is always zero and never read; a task that must
  be configured or every full recompute leaves a complete extra copy on disk.
* **The consignment-ELT residual** — the batch table is **structurally singular**, one schema per row while
  a Consignment emits a row set per schema. An open decision, not a bug.
* **The completeness measure** — wiring, and a job type that must **refuse loudly** when the registry
  backend is off. ⚠ Two of its components have **no production caller today**. 🔴 Its hold is neither
  carrier- nor query-gated: its first action is *engineering*.
* **Surge admission** — open. *(The memory-cap default that shared this row shipped 2026-09-26, GAP-4, §2.4.)*

### 5.3 `UNTRACKED` — found writing this spec, no board row exists

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-GREENCELL-1`, `SPEC-GLOSSARY-1`, `SPEC-PLANSTALE-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

Ranked. The first three are visible to an operator.

1. 🔴 **A release gate rests on a class that does not exist, with a claim that is false of the class that
   does** (§2.3). Fix the row's name and its claim before anyone reasons about Row 15's readiness again.
2. ✅ ~~**The served maintenance descriptor advertises four tasks Personal refuses and hides seven
   shipped ones**~~ **FIXED 2026-09-09.** `MaintenanceJob.BUILT_IN_TASKS` is now the one declaration of
   the switch's ids (20 then, 23 on 2026-11-15), `availableTasks()` adds whatever a provider on **this** classpath contributed,
   and the descriptor is built from that — so the form offers exactly what will not throw, per edition.
   Pinned by `MaintenanceTaskContractTest`, which **re-parses the switch's own `case` labels**; proven by
   mutation (removing one id failed three of its five tests, naming the id).
3. 🔴 **Replay is backed by an in-memory capped map, lost on restart**, while a board row marks it ✅ in all
   three editions and another spec misattributes its 404 to the wrong flag (§2.3). Either persist the
   lookup or caveat the row.
4. ✅ ~~**The 2 GB memory default is asserted in six more current locations**~~ — moot since 2026-09-26: a code
   default exists (GAP-4, `DuckDbUtil.defaultMemoryLimit`, §2.4), so the documents now owe *that* number,
   not "none".
5. 🔴 **"The only bound" is stale in three documents and in the class's own javadoc**, which says the
   default is unbounded directly above the constant that sets it to four (§2.5).
6. 🔴 **The status hierarchy is still stated with the retired word** in the register and three more current
   files — and the user guide **contradicts itself twelve lines apart**. One of these was recorded as
   "corrected" by an earlier spec and is still on disk.
7. 🔴 **Twenty-plus dead class citations** survive the 2026-08-31 rename, including **five in the active
   plan's "what actually runs" section** and two cited *by line number*.
8. ⚠ **"Lane" means four different partitions across five documents**, and the declared owner's own table
   **omits the post-sync lane** that another page calls one of two.
9. ⚠ **A job type id is misspelled in a concept page** — an underscore where the registered id has a dot.
   An operator copying it authors an unknown type.
10. ⚠ **An event name in a design page does not match the emitted constant** — the page uses the retired
    prefix, which survives only as a legacy alias, while a sibling page uses the canonical name and neither
    cross-references the other.
11. ⚠ **The retired pipeline word still names the concept in three execution documents**, including route
    templates and method names in prose.
12. ⚠ **The declared lane owner contradicts itself on the orphan gate thirty-four lines apart** — the
    second statement is the pre-2026-09-01 state that another page explicitly labels history.
13. ⚠ **The active plan says a new Step type cannot be added today**, contradicted by its own later row and
    by the boundary page; and it states the graph admission in its **pre-generalisation** form.
14. ⚠ **Two more self-contradictions**: a durable-store check recorded as both owed and discharged, and the
    seed count given as exactly one against a page that records the relaxation to one-or-more.
15. ⚠ **Record-level lineage is simultaneously refused and open** on one board — "won't do" in one spec and
    a live actionable row in another.
16. ⚠ **Refused-as-a-default versus planned-for-Enterprise** is the real position on distributed
    coordination, and **no document states it that way** — two say it is refused, three treat it as
    Enterprise scope.
17. ⚠ **No scheduler test class exists at all** for the ingest engine — recorded only in prose.
18. ⚠ **The retry's escape hatch is in no document**, and the descriptor's own count and the glossary's
    trigger entry are both wrong in ways nothing checks.

## 6. Refused & superseded

| Item | Verdict | Why |
|---|---|---|
| **An `ingest` job type** | ⛔ **REFUSED** 2026-06-17 | A Job is strictly downstream over data at rest; ingestion is the Pipeline's sole responsibility |
| **Sub-Pipeline / embedded-Job nesting** | ⛔ **REFUSED by design** 2026-08-02 | In-motion versus at-rest is a binding line; they compose as producer and consumer over a shared store |
| **A global priority-ordered queue** | ⛔ **REFUSED — do not re-propose** | Strict ordering *is* the starvation trap, and it reorders Consignments within a pipeline, breaking arrival order |
| **Nested semaphores per tier** | ⛔ **REFUSED** | No global view for the fairness decision, and cross-tier ordering is a deadlock hazard |
| **A separate consignment-generation service** | ⛔ **REFUSED** | Planning stays in the poll cycle; only *admission to execution* is shared |
| **Retiring the ingest run budget** | ⛔ **REFUSED — the plan claimed it, the code refuted it** | It bounds a different *phase*, not the same fact twice |
| **A cap computed from memory ÷ the live concurrency** | ⛔ **REJECTED** (the GAP-4 default divides by a fixed 4) | The divisor is routinely unknown and the ingest path has its own limiter, so any formula is wrong *in exactly the overcommit case it was meant to prevent* |
| **A temporary-directory-size default** | ⛔ **NONE, permanently** | None is defensible without knowing the volume |
| **Capping preview and dry-run connections** | ⛔ **REFUSED, and must stay so** | They run over bounded samples |
| **Read-path connection reuse** | ⛔ **NOT the lever** | Measured: a warm open is 24 ms; no contradicting measurement exists |
| **Route at rest** | ⛔ **REFUSED at two sites** | Route's home is the ingest lane. ⚠ Both refusals used to be *negatives that never told the author route works at all*; both now name its home, and **no test pinned either string** |
| **Decision rules together with route** | ⛔ **REFUSED at runtime** | A rule that actually routed rows keeps the pipeline flat; the admission cannot see rules statically |
| **Multi-schema together with route** | ✅ **ARMS since 2026-09-24** | Each per-schema write walks its own schema's slice (`PipelineLift.scope`); ONE shared `route:` whose predicates must bind in every schema, else arming refuses — [spec-ingest.md](spec-ingest.md) § *Multi-schema route* |
| **A node between projection and write on either ingest lane** | ⛔ **REFUSED on both** | Carrying it means executing it at rest, which is second-stage work; **batch independence is the dividing rule** |
| **Cross-branch all-or-nothing commit** | ⛔ **OUT OF SCOPE deliberately** | One healthy branch stays committed when a sibling fails, and the failed branch retries independently |
| **A versioned reference store per branch** | ⛔ **REFUSED** | One version history across branches is ill-defined |
| **Per-step signals for live position** | ⛔ **REFUSED** | A signal is a durable ledger write inside the run claim — and because signals trigger jobs, a mid-batch signal invites triggered work to fire *while the claim is held* |
| **Periodic persistence of progress** | ⛔ **REFUSED** | Stale by construction, recurring writes for a value that expires in seconds, crash-orphaned rows to clean — and the post-crash question is already answered by the ledgers |
| **Folding intrinsic and inherited file status into one enum** | ⛔ **REFUSED** | A committed file inside a failed Consignment is real and common; folding loses *which files made it* |
| **A rollup cache** | ⛔ **deliberately ABSENT** | Nothing depends on it, read-time aggregation has not been shown too slow, and building it adds the one mutable-looking thing in the system **ahead of evidence** |
| **An on-commit job trigger** | 🔴 **DOES NOT EXIST** | Commit-fired jobs ride the signal bus. ⛔ Do not cite the graph-structure refusal as what keeps job work at rest |
| **Growing the event enum for new facts** | ⛔ **BANNED — emit a signal** | And **per-file facts belong in a ledger row**: a signal per file floods the bus on a large Consignment |
| **A third-party scheduler library** | ⛔ **REJECTED** | The config file is the durable schedule |
| **Inbox lag or pending depth as throttle inputs** | ⛔ **BANNED** | Positive feedback; they stay observability surfaces |
| **Kafka as a second execution model** | ⛔ **REFUSED, never re-opened** | Urgency is a parameter on one node. ⛔ Do not re-file it; a Kafka *collector* is a different, open question |
| **Timeout or cancellation on either engine** | ⛔ **NONE — a recorded gap** | Cancelling a handle un-arms only future fires |
| **Sealing** | **DROPPED, not deferred** 2026-08-30 | The partition-state machinery bought nothing the completeness measure does not |
| **Per-record lineage** | ⛔ **REFUSED / won't do now** | Per-batch ancestry is the accepted grain. ⚠ See §5.3 item 15 — one board says both |
| **Distributed-by-default** | ⛔ **REFUSED as a default** | ⚠ But planned as Enterprise scope, unscoped — and no document states the distinction (§5.3 item 16) |
| **A partial archive failing its Consignment** | ⛔ **REFUSED** | The partial verdict is **reporting, never a gate**; it is a Run-level fact, never a manifest member row |
| **Nested archives** | ⛔ **REFUSED** | Depth is pinned at one |
| **Crash-mid-archive re-ingest** | **BY DESIGN** | Relies on idempotent overwrite; revisit only with a measured cost. ⚠ The bounded retry does not cover it — it records a *returned* failure, and a crash returns nothing |
| **Member-level transient classification** | ⛔ **stays as it is** | The catch sites collapse every read failure into a permanent quarantine across four strategies; nothing classifies transient from fatal (§3.7) |
| **Multi-user relational deployment** | ⛔ **PARKED** 2026-09-06 | Until a multi-operator install exists |
| **Mail delivery reporting success with no channel** | **DELIBERATE** | ⛔ Do not "fix" without an operator call — but know that a scheduled mail job **reads green while delivering nothing** |
| **A built-in with an unsatisfiable grant** | **ACCEPTED** asymmetry | Only the pack and classpath paths refuse; recorded deliberately |
| **The step-scaffold generator for a new kind** | ✅ **BUILT** for pack-hosted Steps — `node tools/scaffold.mjs new step` (isolated classloader, `StepContext`, watchdog; platform-services S2-3) | The original gate stands for a *classpath* node type: it gets no hot deploy, no isolated loader and no watchdog |
| **Third-party lowerable Step kinds** | ⛔ **CLOSED** | Until a fragment guard exists |
| **Reusing the flow-job sink writer for ingest destinations** | ⛔ **NEVER** | It is shaped for one root, with no lineage, and self-registers |

## 7. As-built pointers — where they now live

The as-built pointer table (concern → symbol → known gap) moved to
[spec-pipeline-execution.md](spec-pipeline-execution.md) — *Code pointers*. ⚠ **Cite the symbol, not the line** —
and in this area, **check the symbol still exists** (§5.3 item 7).

## 8. Verification

**What is actually enforced today**

* **The event-trigger filters** — `PipelineSchedulerEventTriggerTest` (2026-09-09, closing the last of
  `SPEC-NOPROOF-1`): a **non-SUCCESS** commit triggers nothing, a trigger naming itself does not
  re-fire on its own commit (the self-loop guard, whose absence turns one commit into an unbounded run
  loop), a paused pipeline stays paused for EVENT triggers, `from` tolerates case and a `flows/` prefix,
  and a `on: commit` pipeline ignores a Dataset write. Mutation-proven. ➕ **2026-09-15**: the `on: dataset` side now has the same three-way cover — the producing pipeline is suppressed, another pipeline’s write still fires it, and an **unowned** write (a cron/manual job) still fires it. ⛔ The third is not padding: reading “no owning pipeline” as “suppress” would disable materialize-then-trigger entirely, silently. 🔴 **The board row that asked for
  this said the scheduler "has no test class at all" — literally true, materially misleading**:
  `CollectorServiceTriggerTest` already proved its loop semantics end-to-end (DEFAULT_POLL, interval,
  cron, manual, the event trigger, the `on: dataset` fence) under a collaborator's name. ⚠ Grep the
  behaviour, not the identifier. ⛔ `dueThisTick`/`cronDue` stay proven from the outside: they are private
  and `runOne` calls real ETL, so reaching them directly would need reflection or a production change.

* **A lane-parity test that diffs flat against graph writes** — parity is proven, not asserted. The
  strongest control in this area.
* **A stage-two lift test** covering the happy path and the route refusal.
* **A bounded-retry test**, and an orphan-audit test.
* **Ingest lock tests** pinning that the per-pipeline guard is shared as one instance and that the hand-off
  is off-thread.
* **Scheduler-settings tests** pinning configuration-over-flag precedence.
* **A purge test asserting the ledger outlives the record** — including its own purge entry.
* **An operations-zone test written to be falsified on purpose.**

**Falsify, don't read — six probes**

1. **Grep for the class the backlog names as Row 15's blocker.** Zero hits falsifies the row; then grep the
   real runner's `run(` for its production caller.
2. **Open the maintenance job form on a Personal build.** Four offered tasks will be refused and seven
   working ones are missing.
3. **Restart the server, then replay a completed run.** A 404 is the §2.3 bound, not a bug in the route.
4. **Start with nothing installed and read the effective memory limit.** Nothing is the answer six docs
   deny.
5. **Read the concurrency javadoc against the constant twelve lines below it.** They disagree.
6. **Author a maintenance job by copying the post-sync page's job type id.** The underscore makes it an
   unknown type.

**A capability-pointer check over this file** — it is **`tools/check-doc-citations.mjs`, committed 2026-09-09**
and wired into `ci.yml` and `.githooks/pre-push`. It checks this file's backticked symbols
against `git ls-files` and the module tree — and in this area, that check would have caught item 7 of §5.3.
