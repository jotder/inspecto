# Plan — Branch-aware executor on the ingest path (BACKLOG §6)

> **Status: IN FLIGHT (opened 2026-08-01).** Operator decisions taken 2026-08-01 reordered the stages:
> **throughput/decoupling (Stage B) goes first**; multi-destination sinks become a later slice
> expressed as a plural `sinks:` section. **B1 (per-pipeline run guard) and B2 (non-blocking dispatch)
> are shipped and green (full reactor 2451/0/0, 3 skipped).** B3 (acquisition as its own driver) is next.
> Stage A (the executor bridge) is deferred behind B; Stage C is unstarted.

## 0. Decisions of record (2026-08-01)

1. **Throughput before destinations.** The operator's priority is the non-blocking execution model, not
   multi-destination writes. Multi-destination is wanted eventually but is not the near-term slice.
2. **Multi-destination will be a plural `sinks:` section** on `*_pipeline.toon` when it lands — spec'd
   and safety-validated, with the existing single `output:`/`dirs.database` kept as the one-destination
   shorthand. *Not* a graph-native config (that would reverse W5) and *not* the B8 chain-pipelines
   workaround.
3. **Stage B is approved in principle** — the operator reaffirmed the decoupling direction after the
   reversal was flagged (§4). It is the §3.5 escalation clause being exercised.

⚠ **Correction to the original framing of this plan.** "Multi-sink" is two different things and half of
it already works. `PipelineEditable.lower` refuses `MULTI_SINK` only when persistent sinks name **>1
distinct database dir** (`PipelineEditable.java:200`); multiple sink *nodes* sharing one database are
explicitly legal and already run — pinned by `PipelineEditableTest:82` *("Multi-schema branch sinks
share one database — that is NOT a MULTI_SINK refusal")*. So the real limit is **one destination per
pipeline**, which is a config-format gap (`PipelineConfig.Output` is a single record), not an executor
gap. Wiring `PipelineExecutor` alone would not have closed it — hence decision 2.

## 1. The problem

The graph editor can now *author* the canonical `*_pipeline.toon` (W5), but the engine cannot *run*
most of what it authors. A multi-sink, `route`/`derive`, `sink.materialized`/`view`, or non-`gap`
CONTROL topology is refused at save time with a named code (`MULTI_SINK`, `UNSUPPORTED_NODE`, …).
BACKLOG §6 calls closing this "the branch-aware executor".

**The machinery already exists.** This is a *wiring* problem, not new engine work:

| Piece | Where | State |
|---|---|---|
| Topological walk + pull-model routing | `pipeline/exec/PipelineExecutor.java:90` | shipped (T12) |
| Multi-named-relation SQL assembly (`filter`/`route`/`dedup`/`split`/`merge`/`derive`) | `pipeline/exec/RowShaper.java` | shipped (T10) |
| `(batch, branch)` commit + partial-commit state | `pipeline/exec/BranchCommitLog.java:38`, `BranchCommitCoordinator.java:61` | shipped (T11) |
| Sink write callback | `pipeline/exec/PartitionSinkWriter.java` | shipped (T32) |
| Flat config → graph | `pipeline/PipelineLift.java:50` | shipped |

**What is missing is a driver with a real `SourceFinalize`.** `PipelineExecutor` is production-live,
but only as a `JobType.PIPELINE` job over data **at rest**, and it passes
`sourceFinalize = () -> {}` ([`PipelineJobRunner.java:176`](../../inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java)) —
correct for a job (no acquisition to finalise), useless for ingest. The poll-driven path that *owns*
acquisition never reaches the executor at all.

**Where the single-sink assumption physically lives:** `PipelineConfig.Output` is a single record,
not a list (`inspecto-etl/.../PipelineConfig.java:109`), and
[`BatchIngestStrategy.writeAndTrace:84`](../../inspecto-engine/src/main/java/com/gamma/inspector/BatchIngestStrategy.java)
does one `DataTransformer.materialize` then one `PartitionWriter.write`. That is the choke point.

## 2. The finding that reorders the work

`ingestLock` is **one global `ReentrantLock`** (`service/CollectorService.java:175`), shared by
reference with `PipelineScheduler` (`:436`), and `PipelineScheduler.runCycle` holds it for the
**entire poll cycle across every active pipeline** — collect *and* process (`:110`). The same lock is
taken by `runPipeline` (operator trigger, `:1266`) and by `register`/`unregisterPipeline`
(`:889`/`:922`).

Consequences:
- A slow parse blocks the next fetch, blocks other pipelines' cycles, blocks operator-triggered runs,
  and blocks registry mutation.
- **Any stage-decoupling work is worthless until this lock is split.** Per-stage queues behind one
  global lock still serialize.
- `CollectorProcessor.run` additionally blocks on `f.get()` per batch before returning (`:151`).

Parallelism that *does* exist today: per-pipeline vthread fan-out (`MultiCollectorProcessor:181`),
per-batch `Semaphore(cfg.processing().threads())` (`CollectorProcessor:117`), and parallel remote
fetch over a bounded connector-session pool (`RemoteAcquisitionHandler:71`). Parallelism exists
*within* phases; overlap *across* phases does not.

## 3. Operator's target execution model (recorded 2026-08-01)

Verbatim intent, to be designed against rather than paraphrased away:

- Acquisition over the network is slow and belongs on its own thread(s), **independent of** the
  m..n multiplexer engine, which is the performance-critical part and must not wait on poll.
- Remote strategy: **download to inbox/backup and read from there** (rather than read-in-place) —
  because backup requires the move anyway, and it unifies with locally-pushed files.
- **Every unit is a job**, possibly internally multi-threaded; on completion it **fires the next task
  on another virtual thread**. Pipeline-level progress/completeness is derived from those units.
  *Example: collection is a job; one stream's collection instance downloads three FTP files in
  parallel. The m..n multiplexer is an EL job, parallel per stream.*
- **Full housekeeping for every file and its stages.**
- Goal: non-blocking, maximum achievable throughput.
- **The execution model is separate from the UI abstraction.**

Already satisfied by the code: download-to-local with resume (`SftpConnector.fetchTo:155`),
incomplete-file protection (`StabilityGate.filter:106` — quiescence + native readiness +
`ready_marker`), and parallel fetch. **Doc conflict to pin before building:**
`okf/backend/acquisition/connectors.md:15` says `fetchTo` materialises "straight to the backup dir,
never temp-then-move"; the acquisition roadmap says the poll-root staging tree. One is stale.

## 4. Decisions this direction reopens (must not be reversed silently)

| Ref | Recorded position | Stage that reopens it |
|---|---|---|
| D1 | Runtime = topology over the existing batch engine | B |
| D7 | Back-pressure = admission only, **no inter-node queues** | B |
| B1 | No decoupled per-node scheduling | B |
| B2 | No inter-node queues / no per-edge back-pressure | B |
| B8 | Per-flow trigger granularity, not per-node | B |
| §3.8 rule 1 | "A pipeline is the ETL and is **poll-driven, period**" | B (partial) |
| R6/T23 | `ingest` job type **deleted** — a job may never re-acquire | B (partial) |
| B10 | Full per-file provenance is phase 4.5/6, not day-one | C |

**The sanctioned door** is the design doc's own escalation clause (§3.5, lines 283–288): if decoupled
scheduling becomes a real requirement, the phase-2 answer is per-edge **bounded spill-to-disk**
hand-off queues with a high-water mark that de-schedules the upstream node. Stage B is that clause
being exercised — it is anticipated, not a departure.

**Two honest caveats.** (a) R6 deleted the `ingest` job type because it re-ran acquisition on a
*separate scheduler over the same inbox with no shared lock*. Stage B is not that — it is one
execution model with explicit hand-off — but the distinction must be written into §3.8, not assumed.
(b) There is **no throughput SLA** to justify B against: NFR-1 is measured evidence
(~510k rows/s appender ingest), not a target, and NFR-8 accepts the single-node ceiling. The case for
B rests on **skew and latency** (one slow stream starving others), not on a missed number.

Near-identical unbuilt prior art to reuse rather than reinvent:
`archived-documents/plans-archive/acquire-controller-service-design.md` (List/Fetch split, per-node
`concurrency.max_tasks`, and a §10 that explicitly declines NiFi's queue runtime).

## 5. Staged plan

The stages are ordered so that **Stage A is compatible with either future** — it does not presuppose
the Stage B reversal. The trick is to draw the unit boundary now and change the driver later.

### Stage A — make the graph vocabulary executable (DEFERRED behind Stage B per §0 decision 1)

Still the right shape when it is picked up, but note §0's correction: on its own it does not deliver a
second *destination* — that needs the `sinks:` format work (§0 decision 2) as a prerequisite.

Extract the "run one materialized batch through a graph and commit it" work as a **first-class unit**
with an explicit input record, instead of inlining the executor into `writeAndTrace`. The poll loop
calls that unit today; a queue-driven driver can call the same unit in Stage B with no rework.

1. **Unit seam** — new `pipeline/exec/BatchGraphRunner`: takes (live DuckDB `Connection`, lifted
   `PipelineGraph`, seed table, `batchId`, config) → drives `PipelineExecutor.execute` with a real
   `SinkWriter` (`PartitionSinkWriter`) and a real `SourceFinalize`.
   → *verify:* unit test seeds a two-sink graph, asserts both sinks written once.
2. **Real `SourceFinalize`** — re-home `BatchProcessor.commit`'s existing bodies (DuckLake register →
   manifest → backup → **markers LAST** → dedup ledger, `BatchProcessor.java:82-186`) behind the
   `SourceFinalize` callback, preserving that exact order. This is the piece `PipelineJobRunner`
   stubs.
   → *verify:* crash-ordering test — markers must not exist unless every branch committed.
3. **Engagement predicate** — the flat tail stays byte-for-byte for a single-sink linear config; the
   unit engages only when the lifted graph needs it. Decide the predicate off the graph, not a flag.
   → *verify:* existing 618-test baseline unchanged; a flat config's write path provably untouched.
4. **Lift the editor's refusals** for exactly what now runs (`MULTI_SINK` first), keeping
   `PipelineEditable`'s named codes for the rest. Mock (`mock/pipeline-editable.ts`) must refuse
   exactly what the server refuses — the pinned strictness rule.
   → *verify:* `pipelines.handler.spec.ts` + a real-HTTP save of a two-sink pipeline.

**Not in Stage A:** `sink.materialized`/`view` on the ingest path, non-`gap` CONTROL, cross-branch
transactional commit (B9 keeps it non-transactional by design).

### Stage B — decouple the drivers (APPROVED in principle; reverses D1/D7/B1/B2/B8)

**B1 — split `ingestLock`. ✅ SHIPPED 2026-08-01 (reactor 619/0/0).**

`com.gamma.service.PipelineRunGuard` (new) replaces the single global `ingestLock` with **per-pipeline**
exclusion. The old lock was held by `PipelineScheduler.runCycle` across the entire cycle for every
pipeline, and by `runPipeline` / `register` / `unregisterPipeline`. What remains in its place:

- **`runGuard`** — one `ReentrantLock` per pipeline id. The cycle uses `tryAcquire` (a pipeline still
  running from an earlier tick is **skipped**, never queued — queueing would pile runs up behind a slow
  pipeline). `runPipeline` uses `acquire` (blocks, preserving the documented re-read-the-inbox-after-
  acquiring behaviour that prevents double-ingest).
- **`registryLock`** — narrow, serialises registry mutation + `ConfigRegistry.rebuild` against a
  cycle's *selection pass* only. Never held across a run.
- `runGuard.forget(id)` wired into `unregisterPipeline` so the lock map cannot leak under churn (the
  `IntakeGovernor.forget` idiom).

*Justification the invariant is unchanged:* the old lock's own field doc said a waiting caller re-reads
the inbox "by which time the prior cycle has written its `.processed` markers — so already-ingested
files are skipped rather than double-processed". That invariant is **per-pipeline**; the global lock
enforced it over-broadly.

`CollectorServiceIngestLockTest` was rewritten (2 tests → 3) to pin the **new** invariant rather than
deleted: same pipeline never overlaps; **`aSlowPipelineDoesNotBlockAnUnrelatedOne` is the regression
guard that fails if anyone re-widens the lock.** ⚠ The claim must be taken on a *different thread* from
the ingest call — a `ReentrantLock` is re-entrant, so same-thread claim-then-ingest proves nothing.

⚠ **Also updated a real hazard, don't undo it:** the off-thread hand-off to `triggerWorkers` is what
makes the claim mean anything. An inline run of the *same* pipeline on the publishing thread blocks
forever on the claim that thread already holds. (B1 first recorded this as "re-enters its claim and
double-ingests, which is worse" — true of the `ReentrantLock` B1 shipped, no longer true after B2
replaced it; see below.)

**B1 does NOT fix tick-level head-of-line blocking.** It fixes the *cross-entrypoint* case: a manual
trigger, a watch-triggered run, or register/unregister no longer waits on an unrelated pipeline. But
`runAllOnce` was still synchronous and the poll scheduler is fixed-delay, so a slow pipeline in cycle
*N* still delayed cycle *N+1* for every pipeline. That is what B2 closed.

**B2 — non-blocking dispatch. ✅ SHIPPED 2026-08-01 (full reactor 2451/0/0, 3 skipped).**

A cycle is now *selection* (`selectDue`, the only part under `registryLock`) plus one independent
per-pipeline task (`runOne`) per selected pipeline. The two entry points differ **only in whether they
wait**, so there is one cycle body, not two:

- `dispatchCycle()` — the periodic driver. Submits and returns; each task releases its own claim.
- `runCycle()` — `runAllOnce` (the `POST /trigger` route + every test asserting on `RunResult`). Same
  tasks, awaited, so `total`/`failed` is unchanged.

Because ticks now overlap, two things could no longer be cycle-scoped and moved to the scheduler:
the **run budget** (one process-lifetime `Semaphore`, so overlapping cycles *plus* a concurrent
`runAllOnce` share `maxConcurrentRuns` instead of each minting a fresh allowance) and the
`inspecto_active_runs` gauge (a counter — resetting it to 0 at a cycle boundary would zero runs another
cycle still has in flight). The `IntakeGovernor` signal became **per-pipeline run duration** instead of
cycle wall time, which is strictly more correct: the cap is already per-pipeline (`capFor(id)`), and
cycle wall time charged every pipeline in an overrunning tick for the slowest one's overrun.

The status-DB projection stays **once per cycle**, fired by the last task to finish — it re-projects the
whole audit for every config, so per-run would multiply that by the pipeline count.

⚠ **`PipelineRunGuard` is a binary `Semaphore`, not a `ReentrantLock` — do not "simplify" it back.**
A claim is taken on the tick thread during selection and released by the virtual thread that finished
the run. `ReentrantLock.unlock` from a non-owner thread throws `IllegalMonitorStateException`; a claim
is owned by a *run*, not a thread. Being non-reentrant is a second, deliberate gain — the double-ingest
hazard above becomes a block instead of silent corruption.

⚠ **Behaviour change worth knowing: two config files declaring the same `name:` now yield ONE run per
cycle, not two.** They were only ever two runs because `tryLock()` on the *same* thread in the
selection loop re-entered and succeeded — i.e. one cycle ran the same pipeline id twice concurrently,
which is exactly what the guard exists to prevent. Every other id-keyed structure (paused/running sets,
cadence map, intake caps, `configRegistry.getPath`) already collapsed them into one pipeline, and
`registerPipeline` **rejects** a second file reusing a registered id outright. Only the constructor path
lets such a config in — see §6 open decisions. `CollectorServiceTest` was relying on the accident and
now uses distinct ids; `TestConfigs` gained a `name(String)` setter so tests stop rewriting toon text.

⚠ **Shutdown ordering changed and B2 depends on it:** `scheduler.close()` now runs *before*
`triggerWorkers.close()`. Since a tick dispatches onto `triggerWorkers`, draining the workers while the
timers still fire would submit into a closing executor. `dispatch` also handles the
`RejectedExecutionException` by releasing the claim it took, so a rejected dispatch cannot leak one.

**B3 — (NEXT)** acquisition becomes its own driver with its own concurrency budget, handing off a **durable**
descriptor set (the `RemoteFile`/ledger pair is already that descriptor).

**B4 —** the multiplexer unit gets a queue-driven driver + bounded spill-to-disk hand-off with a
high-water mark (the §3.5 escalation, verbatim).

**B5 —** reconcile §3.8: state precisely how "collection is a unit" differs from the deleted `ingest`
job type (one execution model with explicit hand-off, not a second scheduler racing the same inbox).
### Stage C — per-file stage housekeeping (NEEDS SIGN-OFF; reverses B10 ordering)

Today every durable store records a **terminal fact**, not a progression: ledger keyed
`(sourceId, relativePath)`, markers boolean, `BatchManifest` per-`batchId` written once at commit,
`CommitLog` per batch, `LineageRow` transform→write only. The one live per-file signal
(`IngestProgress.Snapshot`) is **in-memory, single-slot, cleared at batch end**. So nothing answers
*"where is file X right now"*.

Note `StatusStore` (`FileStatusStore`/`DbStatusStore`) and the locked `Run ⊇ Batch ⊇ File` vocabulary
(GLOSSARY:284) already exist — Stage C is a **stage progression** over that grain, plus the per-edge
counters §11.3 wants, not a new store from scratch.

## 6. Open decisions for the operator

*(1 and 2 answered — see §0. Remaining:)*

1. **Justification of record for Stage B** — proposed: skew/latency (one slow stream starving others),
   since there is no throughput target to miss. Confirm this is the case to write into §3.5/§3.8 when
   B lands, so the reversal is defensible on review.
2. **Stage C now or after B?** B10 puts per-file stage tracking at phase 4.5/6; the operator's model
   implies it is co-equal with B ("full housekeeping for all files, its stages").
3. **Pin the stale `fetchTo` doc line** (§3) — `connectors.md:15` (backup dir) vs the acquisition
   roadmap (poll-root staging tree). Cheap to settle against code; blocks nothing but will mislead.
4. **Should the `CollectorService` constructor reject duplicate pipeline ids, like `registerPipeline`
   does?** Surfaced by B2 (see the behaviour-change note above). `registerPipeline` throws
   `IllegalStateException` on a second file reusing a registered id; the constructor silently accepts it
   and the duplicate is now skipped each cycle instead of double-running. Failing fast at construction
   with the same message would make the two doors agree. Small and self-contained, but it can turn a
   currently-starting deployment into a startup failure — hence an operator call, not a default.

## 7. Gotchas (carried forward)

- ⚠ `MAVEN_OPTS=--enable-native-access=ALL-UNNAMED` is mandatory (DuckDB JNI), tests included.
- ⚠ `BatchEventBus.publish` is **synchronous on the publishing thread**, which holds that pipeline's
  run-guard claim — an inline-dispatched successor of the *same* pipeline self-deadlocks. Hand off to a
  vthread pool, as `JobService` and `PipelineScheduler.onUpstreamCommit` already do.
- ⚠ A `PipelineRunGuard.Claim` crosses threads by design (taken at selection, released by the runner).
  It must stay a thread-agnostic primitive; a `ReentrantLock` cannot express it (B2).
- ⚠ `CollectorService.close()` must close `scheduler` **before** `triggerWorkers` — the poll tick
  dispatches onto `triggerWorkers` (B2).
- ⚠ `TestConfigs` defaults every pipeline to id `TEST_ETL`. Any test registering two pipelines must call
  `.name(...)`, or they are one pipeline to the run guard and only one runs.
- ⚠ Markers **LAST** is a crash-ordering invariant, not a style choice — preserve it through any
  re-homing.
- ⚠ B9 stands: multi-sink commit is **not** cross-branch transactional. A clone may have some
  branches committed and others retrying.
- ⚠ Mock strictness is load-bearing: a lenient `mock/pipeline-editable.ts` greenlights a preview the
  server 422s.

## 8. Docs to update when stages land

`okf/backend/pipeline-graph/pipeline-graph-design.md` (§3.3/§3.5/§3.8, §9 D1/D7, §12 B1/B2/B8/B10,
§13 R3/R6, §14) · `okf/backend/pipeline-graph/design.md` · `okf/backend/pipeline-graph/live-execution.md`
(the §3.8 tension) · `okf/backend/engine/ingestion.md` · `okf/backend/build-run/performance.md` ·
`okf/backend/acquisition/connectors.md` (the stale `fetchTo` line) · `BACKLOG.md` §6 · `INDEX.md`.
