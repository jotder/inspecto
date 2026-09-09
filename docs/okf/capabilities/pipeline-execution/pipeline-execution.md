---
type: Capability
area: PIP
title: Pipeline execution (PIP) — capability spec
description: The requirement-of-record and as-built specification for running work — the two independently-triggered engines, the two trigger models, the two concurrency mechanisms, the ingest lane fork and its two lifts, run identity and the ledgers, the resource caps and the bounded retry, the post-sync derived lane, the maintenance library and the failure affordances. The second of the two PIP specs and the last of the seventeen slots.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §3.3 PIP-2 through PIP-7, and EDITIONS JOB-03/JOB-04/CP-03 (this file corrects them, see §2)
---

# Pipeline execution (`PIP`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.3 or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is
> stated in place. §3 is the specification, §4 the dated decisions, §5 what is not built, §6 what was
> refused, §7 the pointers, §8 how it is verified.
>
> ⛔ **This is the second of two `PIP` specs.** Authoring — the editor, the config contract, the Step
> catalog — is [`pipeline-authoring`](../pipeline-authoring/pipeline-authoring.md), which owns `PIP-1`.
> This file owns **`PIP-2` through `PIP-7`**. "Orchestration" is folded in here; the word is not a
> glossary term.
>
> ⚠ **The area has twenty-seven current documents and six plans holding decisions of record**, one
> declared single-owner concept page, and exactly **one** practice-typed page — the smallest file in the
> set. The operator-facing procedure actually lives in a 71 KB *reference*, typed as a lookup table
> rather than a procedure. This spec does not restate their mechanism; it fixes the numbers and names the
> disagreements.
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
| **PIP-6** | Job templates — `SHIPPED` | ✅ Accurate, and the design detail is worth keeping: templates resolve **once, at load**, so the scheduler and the job service only ever see plain, fully-resolved configs. |
| **PIP-7** | Maintenance job library (retention, compaction, housekeeping) — `SHIPPED` | 🔴 **Understated by a factor of five, and nothing is armed.** The row names four tasks. The engine handles **19** built-in ids, and four more arrive from optional modules. The "curated library" it points at is **five example job files** an operator must copy; no scheduler config ships, and the shipped default space contains no maintenance job at all. The whole housekeeping posture is opt-in — which is correct by decision, but "SHIPPED" without that word misreads. |

### 2.2 One number per fact

Every count in this area is stated two or more ways, and the pattern from the authoring spec repeats: the
*narrative* documents are wrong and the *generated or catalogued* ones are right. Measured 2026-09-09:

| Fact | Measured | What the documents say |
|---|---|---|
| Job types | **10 core + 2 from the operations module = 12** | the glossary says 4 in its binding entry and 9 in its own rename table; the jobs concept page says 4, naming a deprecated enum; the board lists 10 — but a *different* 10, omitting alert evaluation and the sample, and including the two module ones |
| Maintenance tasks | **19 built-in + 4 contributed = 23** | the requirement says 4; the board says 16; the jobs page says 13 + 3; **the served descriptor says 16** |
| Trigger vocabulary | **two models** — pipeline: interval, cron, event (`commit`/`dataset`), manual, default-poll, with coalescing and no catch-up; job: cron, on-pipeline, on-signal + guard, manual, with catch-up | the binding glossary entry lists 4 and **omits on-signal entirely** while counting one mechanism twice; the active plan says 3; the jobs page says 4; the board and the boundary page say 5 |
| Built-in node types | **30** | an execution page says 29 — a *fifth* wrong count of this one, which the authoring spec did not catch |
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
Row 15's parity blocker — reads *"`BatchGraphRunner` has zero production callers, blocked on ingest output
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

⚠ **Intake caps are advertised as shipped and are off by default.** `JOB-04` marks the concurrency broker
with *"priority shares, intake caps"* ✅ in all three editions, and a processor row cites intake caps as
the shipped throttle. The cap's default is zero, which is byte-for-byte the pre-throttle behaviour, and
*"flip the intake cap on by default (needs a soak)"* is an open row. The concurrency page states the honest
version — the broker is **inert unless configured** — which the board's ✅ does not convey.

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
defensible without knowing the host.

### 2.5 "The only bound" is stale in three documents and in the code

Three current files state that the job concurrency flag is *the only bound on Runs*, one of them adding
*"default 0 = unbounded"*. The concurrency half of the resource-cap pair has been **on by default at four
since 2026-08-26**, owned by the server configuration with the flag as a bootstrap default only. And
🔴 **the class's own javadoc still says "0 = unbounded, the default"** directly above the constant that
sets it to four.

There are also **two** bounds, not one (§3.3), so the sentence is wrong twice.

## 3. Specification

### 3.1 Two engines

| | Ingest engine | Job engine |
|---|---|---|
| Drives | a Pipeline over files in motion | anything over data at rest |
| Triggered by | the poll cycle, or an entry-node trigger | cron, a commit, a signal, or by hand |
| Unit of work | a **Consignment** | a **Run** of an Executable |
| Bounded by | its own semaphore, sized at construction | a hot-resizable permit holder, default four |
| Exclusion | a per-pipeline non-reentrant guard | a per-job lock that records a skip |
| Terminal record | three CSV ledgers plus the commit log | the run ledger, plus an optional database projection |

They meet at exactly one place: a commit publishes an event, and that event is what a downstream trigger
consumes. An authored Pipeline's *second* stage crosses the boundary in the other direction — it is hosted
as a Job, because a Job is the only thing that reads data already written.

### 3.2 Two trigger models

**Pipeline triggers** are declared on the entry node: an interval, a cron expression, an event, manual, or
the default poll. The event kind carries what it listens for — a **commit** from an upstream pipeline, or a
**dataset** write — and the two namespaces are explicitly fenced from each other so they cannot collide.
There is a coalescing window. **There is no catch-up.**

**Job triggers** are fields on the job config: cron, on-pipeline, on-signal with an optional guard, and
manual. **Catch-up is here**, and it replays exactly **one** missed fire at startup from the run ledger's
last-start times. The guard grammar is flat and **fail-closed** — a missing field or an unparsable term is
false.

**How a commit-driven trigger actually fires**, end to end: the processor takes a commit consumer, the
service layer passes it a bus, and the bus publishes to three subscribers — the job engine's
consignment-event handler, a mirror that republishes every commit as a signal so a signal trigger also
works, and the ingest scheduler's upstream-commit and dataset-write handlers.

⚠ **There is no on-commit *job* trigger.** Commit-fired jobs ride the signal bus. Do not cite the
graph-structure refusal as the thing that keeps job work at rest — that is a pipeline-edge relation and a
different mechanism.

### 3.3 Two concurrency mechanisms, and why neither may be retired

**The job side** bounds total in-flight Runs at four by default. The holder is deliberately **not** a
standard semaphore, because it must be hot-resizable and must *drain* rather than interrupt on a shrink —
aborting a mid-commit ingest is not something the commit sequence was designed for; it is crash-idempotent,
which is a different property. Permits are taken on the **worker** thread, never the caller, so a fired Run
beyond the ceiling **queues** rather than being rejected. Per-job non-overlap is a separate per-name lock
that records a skip.

**The ingest side** has its own semaphore, sized by a different parameter, plus a per-pipeline
**non-reentrant** guard: a poll cycle *skips* when it cannot acquire, an operator trigger *blocks*. The
non-reentrancy is the point — a reentrant lock would not have deadlocked, it would have silently
re-entered and **double-ingested the inbox**.

⛔ **Retiring the ingest budget was proposed and refuted by the code.** It bounds everything a cycle does
*before* the first broker permit: the stale-marker sweep, the collect call, checksum dedup which reads whole
files, and archive expansion which writes temporaries in parallel. It guards a different **phase**, not the
same fact twice.

**Above both sits a four-layer broker** whose unit is deliberately uniform: a permit counts *a Consignment
being executed* — never a Pipeline, never a run — so the per-pipeline, space, server and priority numbers
are comparable. Fairness is **stride scheduling**, not priority ordering, so the lowest priority provably
keeps a non-zero share. ⛔ A globally priority-ordered queue was refused twice over: strict ordering *is*
the starvation trap, and it would reorder Consignments within a pipeline, breaking arrival order.

### 3.4 The ingest lane fork

Two lanes carry a write: a legacy **flat** loop and a **graph** fork. Admission is a pure function of the
config and the applied decision rules — a routed pipeline diverts when its lifted graph engages; a
non-routed one diverts when no decision rule actually routed rows **and** the graph lane provably carries
the identical write.

Two properties make this trustworthy rather than hopeful:

* **Decision rules are a space-registry fact, not a config property.** They are applied once, above the
  fork, and their *result* is part of the admission — the admission cannot see them statically, and a rule
  that really routed rows keeps the pipeline flat.
* **A pipeline with no scratch directory stays flat** rather than parking its branch ledger in a shared
  temporary directory, because a stale branch log makes the coordinator skip the branch and **the batch
  writes nothing**.

`-Dingest.lane` takes `auto`, `graph` or `flat`. `auto` trusts the admission. `graph` **disables the flat
lane** and throws, naming the specific reason the write could not be carried. `flat` is a permanent kill
switch. Anything else throws. This flag is Row 15's **precondition, not its trigger**.

The reason-for-flat enumeration is the most operator-useful thing on this path: an authored route that does
not engage, a decision rule that routed rows, no scratch directory, a destination-count mismatch, a
non-projection node between projection and write, or a sink fed through another node.

⚠ **The engagement predicate counts branches, not sink nodes.** The original node-count version engaged for
plain fan-out and was refuted by its own falsification test — and a stale test pin had encoded the wrong
belief, which is why the predicate now has a name of its own.

**What the graph lane buys** is not elegance: per-destination **crash resumption the flat loop never had**.
Parity between the lanes is *proven by a test that diffs their writes*, not asserted.

### 3.5 The at-rest runner, and the two lifts

A pipeline-type Job reads a config and lifts only the **second stage**, then runs that chain over the
already-landed store. Its graph source is mutually exclusive and enforced at run time — a config path or a
stored pipeline id, never both — and zero seeds is a hard error, because a pipeline job reads data at rest.

⚠ **Two lifts exist and both are live. Neither is stale, and confusing them is the area's classic error:**

| Lift | Produces | Callers |
|---|---|---|
| the **full** lift | the ingest topology | the lane admission, the processor, the drain command, and the editor's round trip |
| the **stage-two** lift | the at-rest remainder only | the pipeline job runner, and nothing else |

The note that "stage-two is the wrong lift" was a **scoping correction to one plan draft**, which had
proposed computing the *ingest* fork check from the stage-two lift — impossible, because it knows nothing
of ingest topology or route branches. It was corrected to the full lift, which is what the admission uses
today. Each belongs where it is called.

The stage-two lift's refusal set is complete and named: no chain, no output store, a multi-schema selector
(one seed cannot pick a store), a route step (route's home is the ingest lane), and a legacy pre-map filter
carrying pre-parse keys.

### 3.6 Run identity, ledgers and the async contract

**Run ⊇ Consignment ⊇ File.** Identity is the run-and-consignment pair, because the Run is the *attempt* —
so a reprocess is a new Run over the same Consignment.

⚠ **Intrinsic and inherited file status are deliberately separate enums.** A committed file inside a failed
Consignment is real and common, and folding them would lose *which files actually made it* — exactly what
reprocessing asks.

**The commit log is one append-only synced file** and its header carries no run timestamp: it answers *did
this Consignment finish*, and it is distinct from the per-run audit. There is no day column.

**The async contract is the same shape on both engines**: a trigger returns 202 with a run id and a
location header, and the caller polls. An idempotency key is cached on method, path and key for ten minutes
with a thousand-entry cap, only for non-server-error statuses, and a replay is flagged in the response
headers.

⚠ **The at-rest lane's always-on terminal record is the run ledger CSV, not the database projection.** The
lane table — the declared owner of this fact — names only the projection, which is **default-off**.

### 3.7 Caps and the bounded retry

**The resource-cap pair ships half-on by design.** The Run bound is on by default in code; the memory limit
is served by configuration with **no code default** (§2.4). Total exposure is the limit times the concurrent
Run count, which is exactly why an unbounded Run count would make any per-instance cap meaningless.
Precedence for the limit is per-pipeline, then server-installed, then a bootstrap flag, then the database's
own default. Preview and dry-run connections are **deliberately exempt** — they run over bounded samples.

⛔ **Two standing refusals here, both measured:** a cap computed as memory ÷ concurrency was rejected
because the divisor is routinely unknown and the ingest path has its own limiter — so any formula is wrong
*in exactly the overcommit case it was meant to prevent*; and no temporary-directory-size default is
defensible without knowing the volume.

**The commit retry is bounded at five attempts** with exponential backoff and jitter, recorded as durable
**per-file** sidecars, and exhaustion quarantines the work with one critical signal. What it fixed is
subtle and worth stating precisely: **the retry already existed** — the next poll cycle re-encountered the
files — and it was **unbounded**, so a poison Consignment failed every cycle forever. Grounding *refuted*
the plan's premise: the plan feared a lack of retrying, and the real defect was the opposite.

🔴 **There is still no transient-versus-fatal classifier**, and the class says so plainly — treating a
database lock as the trigger *"was aspirational"*. Failure is recorded on any commit or park failure
regardless of cause. A zero setting restores the pre-bound behaviour, and that escape hatch appears in no
document.

⚠ **The retry does not cover a crash mid-archive**, which relies on idempotent overwrite instead: the
sidecar records a *returned* failure, and a crash returns nothing.

### 3.8 The post-sync derived lane

Derived tables are produced **after commit**, by a Consignment-addressed job, and register onto the **same**
Consignment. Schema propagates by describing the relation and is **never re-declared** — the database is
the type authority and the file footer carries the same types, so nothing declares it and nothing can drift.

Four decisions make this lane safe rather than convenient:

* **The sink stays terminal in the batch graph.** Emitting from it would pipe the *pre-commit scratch*
  relation, and the write is driven by the commit coordinator — the reader would run before anything is
  durable.
* **Relations come from the registry, never a directory glob**, because a partition directory holds files
  from every Consignment that wrote that day and a glob silently widens the read past this unit of work.
* **Composability must be declared**; undeclared is refused rather than assumed, and a sidecar carries the
  declaration to read time — because non-composable measures produce *quietly* wrong numbers, and guarding
  the write alone never stopped a reader averaging an average.
* **A summary-write failure fails the Run**, unlike the best-effort registry write, because the numbers are
  the output and losing them silently makes a green Run a lie.

Authors never touch the Job surface: the processor context delegates member by member, and reading is a
narrow seam rather than a raw database handle — a raw handle would make the read-modify-write the design
forbids trivially expressible, which had made the plan's own acceptance test unsatisfiable.

### 3.9 Maintenance and retention

Housekeeping is **tasks on one job type**, never shell scripts or host cron, so findings emit signals an
Alert Rule can consume. **A task with no preview does nothing on a dry run** — fail-closed, never falling
through to the real action.

**Nineteen built-in tasks, four contributed** (§2.2), and the contribution seam matters: the built-in
dispatch always wins first, so the incident purge had to be **removed** from it rather than merely also
provided — otherwise the built-in would silently persist on every edition. A task claimed by two providers
is refused fail-closed, and on Personal a contributed task is an unknown task **refused loudly**, never a
silent skip that a chained job would read as success.

**Nothing is armed.** No scheduler config ships and the default space contains no maintenance job; the
library is examples. For the one destructive task this is the explicit decision — *nothing schedules the
incident purge, and nothing should*, because a shipped default that hard-deletes business records is
indefensible.

**Retention design of record**: the window is **required with no default**; retention is **derived** from
the close time rather than stamped, with the consciously-taken trade-off that shortening the window later
retroactively makes older records eligible; correctness comes from the **cutoff, not the ordering**, and
legal hold is re-checked *inside* the purge. And a purge is **not** "all trace removed" — the append-only
event ledger outlives the record, including its own purge entry, asserted by a test because it is a
reviewer's first question.

**Compaction** is quiet-window and crash-journal safe, readers are glob-based so it is query-transparent,
and a batch whose output was compacted away flips state so a reprocess **refuses instead of duplicating**.

### 3.10 Failure, park and recovery

**One recovery affordance per lane, and they are not interchangeable**: ingest has **reprocess** — which
also *retracts that Consignment's dedup-ledger claims*, or re-ingested rows would be permanently
suppressed; a parked Consignment has **drain**, which does not re-walk but registers the park tables and
runs the real finalisation; the at-rest lane has **replay** (§2.3).

⚠ **Reprocess is whole-batch only**, stated verbatim in the source. Record-level replay from quarantine is
tracked and explicitly has no driver.

**Park is a durable pause, not a bypass** — and disabling a Step means two different things per lane: on
the scratch lane an in-memory bypass, on the ingest lanes a durable pause, because a bypass there would
*lose rows*. Its arming refuses six shapes at save and again at prepare, never as a silent skip at rest —
including the one added the same day for having no park home, without which the pipeline arms, runs, and
dies at the park with a null message, **one batch every cycle**.

**The orphan output-store gate is default-on in every space**, one signal per orphan *transition*, with a
kill switch. That was a fail-open fix: it previously fired only from a maintenance job, and no shipped
space but the demo had one — so a stock space had no orphan detection at all.

⚠ **There is no timeout or cancellation on either engine.** Cancelling a cron handle un-arms only *future*
fires. A hanging Job is a recorded gap, and the watchdog that exists covers only contributed Steps.

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

## 5. Not built

### 5.1 Tracked, externally gated

* **Row 15 — the deletion half of the legacy flat read path.** Gate: a minor release on the trunk after the
  newest ancestor tag, shipping the converter and the lane flag. ⛔ Not closable by code, and ⛔ not to be
  started on momentum. 🔴 The earlier gate was **unfalsifiable by construction** — it named a tag on a
  retired line that can never be a trunk ancestor. Of its four steps, one is shipped, one is **unverified**
  (the parity gate through the compiled path), one shipped 2026-09-02, and the deletion awaits a release.
  ⚠ Its stated parity blocker is the phantom-class row in §2.3.
* **Cross-lane drill-down and the step-info envelope**, and **the batch-id rename trio** — both ride the
  next major tag. ⚠ The rename trio is **not enumerated in the drafted release notes**.
* **Distributed scheduler coordination** — ⚠ **no backlog row of its own**; it exists only as an Enterprise
  board cell and an unscoped gate.

### 5.2 Tracked and actionable

* **Record-level replay from quarantine**, with error manifests and an all-or-nothing versus
  eject-and-continue choice as per-pipeline config — ⛔ explicitly no build without a driver.
* **The retry deferrals** — a per-pipeline retry block, and an operator cancel or retry-now affordance
  (today: delete the sidecar, or reprocess).
* **Flip the intake cap on by default** (needs a soak), and a pre-materialise cap to save fetch bandwidth.
* **Branch-aware residuals** — multi-schema with route needs a **segment-scoped lift**; mid-branch
  transforms in the route verb; three node kinds still unimplemented anywhere; the destination-list
  follow-ups.
* **Platform services stage two and three** — the open Step-kind registry, gated on the decision to execute
  an intervening node at rest and on a throughput spike. ⚠ **No job-side watchdog.**
* **Consignment addressing residuals** — a dead field that is always zero and never read; a task that must
  be configured or every full recompute leaves a complete extra copy on disk.
* **The consignment-ELT residual** — the batch table is **structurally singular**, one schema per row while
  a Consignment emits a row set per schema. An open decision, not a bug.
* **The completeness measure** — wiring, and a job type that must **refuse loudly** when the registry
  backend is off. ⚠ Two of its components have **no production caller today**. 🔴 Its hold is neither
  carrier- nor query-gated: its first action is *engineering*.
* **The memory-cap default and surge admission** — ⚠ verified still open; ⛔ do not close the first off the
  resource-pair decision.

### 5.3 `UNTRACKED` — found writing this spec, no board row exists

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-GREENCELL-1`, `SPEC-GLOSSARY-1`, `SPEC-PLANSTALE-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

Ranked. The first three are visible to an operator.

1. 🔴 **A release gate rests on a class that does not exist, with a claim that is false of the class that
   does** (§2.3). Fix the row's name and its claim before anyone reasons about Row 15's readiness again.
2. ✅ ~~**The served maintenance descriptor advertises four tasks Personal refuses and hides seven
   shipped ones**~~ **FIXED 2026-09-09.** `MaintenanceJob.BUILT_IN_TASKS` is now the one declaration of
   the switch's 20 ids, `availableTasks()` adds whatever a provider on **this** classpath contributed,
   and the descriptor is built from that — so the form offers exactly what will not throw, per edition.
   Pinned by `MaintenanceTaskContractTest`, which **re-parses the switch's own `case` labels**; proven by
   mutation (removing one id failed three of its five tests, naming the id).
3. 🔴 **Replay is backed by an in-memory capped map, lost on restart**, while a board row marks it ✅ in all
   three editions and another spec misattributes its 404 to the wrong flag (§2.3). Either persist the
   lookup or caveat the row.
4. 🔴 **The 2 GB memory default is asserted in six more current locations**, one of them in the same file
   whose own gap row refutes it, and two others give two further framings (§2.4). Five framings of one
   absent default.
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
| **A cap computed from memory ÷ concurrency** | ⛔ **REJECTED** | The divisor is routinely unknown and the ingest path has its own limiter, so any formula is wrong *in exactly the overcommit case it was meant to prevent* |
| **A temporary-directory-size default** | ⛔ **NONE, permanently** | None is defensible without knowing the volume |
| **Capping preview and dry-run connections** | ⛔ **REFUSED, and must stay so** | They run over bounded samples |
| **Read-path connection reuse** | ⛔ **NOT the lever** | Measured: a warm open is 24 ms; no contradicting measurement exists |
| **Route at rest** | ⛔ **REFUSED at two sites** | Route's home is the ingest lane. ⚠ Both refusals used to be *negatives that never told the author route works at all*; both now name its home, and **no test pinned either string** |
| **Decision rules together with route** | ⛔ **REFUSED at runtime** | A rule that actually routed rows keeps the pipeline flat; the admission cannot see rules statically |
| **Multi-schema together with route** | ⛔ **STILL REFUSED** | The lift emits one route node per schema branch and the divert executes exactly one; it needs a **segment-scoped lift** — ⛔ do not simply lift the refusal |
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
| **The step-scaffold generator for a new kind** | ⛔ **GATED** | A classpath type gets no hot deploy, no isolated loader and no watchdog |
| **Third-party lowerable Step kinds** | ⛔ **CLOSED** | Until a fragment guard exists |
| **Reusing the flow-job sink writer for ingest destinations** | ⛔ **NEVER** | It is shaped for one root, with no lineage, and self-registers |

## 7. As-built pointers

⚠ **Cite the symbol, not the line** — and in this area, **check the symbol still exists**: twenty-plus
current citations name classes the 2026-08-31 rename removed (§5.3 item 7).

| Concern | Where it lives | Gap |
|---|---|---|
| The Job engine | `JobService` | 🔴 Javadoc contradicts its own default (§2.5) |
| The Job type registry | `JobTypeProvider` / `JobTypeRegistry` / `JobTypeDescriptor` | 🔴 The maintenance descriptor is wrong both ways (§2.2) |
| The legacy type enum | `JobType` | Deprecated; 4 constants; ⚠ its javadoc names a retired file and key |
| Job config and templates | `JobConfig`, `JobTemplate` | ⚠ The config javadoc omits two recognised trigger keys |
| The Run bound | `JobService.DEFAULT_MAX_CONCURRENT_RUNS`, the permit holder | Deliberately not a semaphore (§3.3) |
| Per-job non-overlap | `LockingRunner` | — |
| The ingest engine | `PipelineScheduler`, `CollectorService` | 🔴 **No test class exists at all** |
| Per-pipeline exclusion | `PipelineRunGuard` | Non-reentrant on purpose (§3.3) |
| Pipeline triggers | `PipelineTrigger` (5 kinds + a scheduler enum) | ⚠ The glossary's trigger entry omits a shipped kind |
| The commit bus | `ConsignmentEventBus` | ⚠ Cited under its **old** name in a concept page |
| The lane fork | `ConsignmentIngestStrategy.admittedLift` / `flatReason` | — |
| The graph runner | `ConsignmentGraphRunner` — `engages`, `run`, `dataFedSinkCount` | 🔴 The backlog names a **non-existent** class and denies its callers (§2.3) |
| Route arming | `RouteArming.refusals` | One statement, two callers |
| The two lifts | `PipelineLift.lift` / `.stageTwo` | ⚠ Both live; see §3.5 before citing either |
| The at-rest runner | `PipelineJobRunner` | — |
| Maintenance dispatch | `MaintenanceJob` (19 ids) | 🔴 Seven unadvertised (§2.2) |
| Contributed tasks | `MaintenanceTaskProvider` + the backup and operations modules | — |
| Compaction | `PartitionCompactor` | — |
| The bounded retry | `CommitRetry` | 🔴 No transient/fatal classifier; ⚠ escape hatch undocumented |
| The memory resolver | `DuckDbUtil.memoryLimit` | 🔴 Six docs assert a default it does not return (§2.4) |
| Quarantine | `QuarantineManager` | — |
| Reprocess and drain | `ReprocessCommand`, `DrainCommand` | Whole-batch only, verbatim |
| The commit log | `CommitLog` | No day column, by design |
| The run ledger | `JobRunLedger` (+ an optional projection) | ⚠ The lane table names only the default-off projection |
| Idempotency | `Idempotency` | — |
| Execution routes | `RunRoutes`, `JobRoutes` | 🔴 Replay's in-memory lookup (§2.3) |
| The declared lane owner | [`execution-lanes.md`](../../backend/pipeline-graph/execution-lanes.md) | ⚠ Self-contradicts on the orphan gate; omits the post-sync lane |
| The Job concept page | [`jobs.md`](../../backend/control-plane/jobs.md) | ⚠ Dead class citations, by line |
| The boundary page | [`job-vs-step.md`](../../backend/control-plane/job-vs-step.md) | ⚠ A fifth wrong node-type count |
| Concurrency | [`consignment-concurrency.md`](../../backend/engine/consignment-concurrency.md) | ✅ The most accurate page in the area |
| The ingest fork | [`branch-aware-ingest.md`](../../backend/engine/branch-aware-ingest.md) | ✅ Accurate |
| The post-sync lane | [`post-sync-step-chains.md`](../../backend/engine/post-sync-step-chains.md) | ⚠ Misspells the job type id |
| Resource caps | [`duckdb.md`](../../backend/engine/duckdb.md) | 🔴 Its bolded headline still states the absent default |
| The active plans | `superpower/pipeline-spec.md`, `pipeline-waves-drain-plan.md`, `elt-final-amendment-plan.md`, `completeness-kpi-plan.md` | ⚠ Hold decisions of record; the first's "what actually runs" is the most stale current text in the area |

Exactly **one** practice-typed page exists here, and it is the smallest file in the set; the operator-facing
procedure lives in a 71 KB *reference*.

## 8. Verification

**What is actually enforced today**

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

**A capability-pointer check over this file** — ⚠ **the checker is NOT in this repo**; it was a session
scratch script and committing it is filed as a fix. Re-derive by grepping this file's backticked symbols
against `git ls-files` and the module tree — and in this area, that check would have caught item 7 of §5.3.
