# Plan — Branch-aware executor on the ingest path (BACKLOG §6)

> **Status: STAGE B CLOSED (opened 2026-08-01, closed 2026-08-02).** Operator decisions taken 2026-08-01
> reordered the stages: **throughput/decoupling (Stage B) goes first**; multi-destination sinks become a
> later slice expressed as a plural `sinks:` section. **Shipped: B1 (per-pipeline run guard) + B2
> (non-blocking dispatch) — committed `2f4348f5`, full reactor 2451/0/0 · B3a (stage-then-land) —
> inspecto-engine 791/0/0 · B3b (the acquisition driver) — steps 1–2 `1328c0f1`, step 3 `a4edbe19`,
> inspecto 645/0/0, full reactor 2205/0/0 · B4 (acquisition back-pressure) — `eb69f1ee`, full reactor
> green · B5 (reconcile §3.8 — docs only) — this change.** **Stage B is now closed:** its non-blocking,
> per-pipeline, acquisition-decoupled, back-pressured ingest execution model is shipped and its as-built is
> reconciled into §3.8 (["Collection is a unit, not a second scheduler"](../okf/backend/pipeline-graph/pipeline-graph-design.md#38-pipelines-vs-jobs--two-drivers-over-one-shared-store-formalised-2026-06-17))
> and §3.5. Stage A (the node-level flow executor / executor bridge) remains **deferred** behind B; Stage C
> is unstarted (needs sign-off, §5). B3b/B4 as-built facts are in the §"B3b/B4" sections below (marked ✅)
> and distilled into [engine/ingestion](../okf/backend/engine/ingestion.md) +
> [acquisition/framework](../okf/backend/acquisition/framework.md).

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

### Stage A — make the graph vocabulary executable (steps 1–3 SHIPPED as dormant machinery; step 4 gated)

> **Status 2026-08-02.** Steps 1–3 are built + verified and committed (`318acf2a` seam, `6965f6f3`
> finalize + predicate). They land as **tested-but-dormant machinery**: an investigation this shift
> confirmed the ingest path **cannot express a multi-sink batch today** — a `Batch` is always
> single-schema (`ConsignmentPlanner` groups by schema before building batches, `Batch.java`), and a flat
> `*_pipeline.toon` cannot author route/derive/multi-destination (the editor refuses them). So the
> engagement predicate (`BatchGraphRunner.engages`) is **`false` for every real ingest config**, and the
> flat single-output path stays byte-for-byte. **Step 3's strategy wiring and step 4 (lift the editor
> refusal) are therefore GATED on the deferred `sinks:` config-format (§0 decision 2)** — the real
> prerequisite to a multi-sink ingest pipeline. Landing the machinery now means the `sinks:` work later
> flips the predicate on with no executor rework. `*_flow.toon` never touches the ingest door (it is an
> at-rest `JobType.PIPELINE` job via `PipelineJobRunner`), so it is out of scope here by design.

Still the right shape, but note §0's correction: on its own Stage A does not deliver a second
*destination* — that needs the `sinks:` format work (§0 decision 2) as a prerequisite.

Extract the "run one materialized batch through a graph and commit it" work as a **first-class unit**
with an explicit input record, instead of inlining the executor into `writeAndTrace`. The poll loop
calls that unit today; a queue-driven driver can call the same unit in Stage B with no rework.

1. ✅ **Unit seam** (`318acf2a`) — new `pipeline/exec/BatchGraphRunner`: takes (live DuckDB `Connection`, lifted
   `PipelineGraph`, seed table, `batchId`, config) → drives `PipelineExecutor.execute` with a real
   `SinkWriter` (`PartitionSinkWriter`) and a real `SourceFinalize`.
   → *verify:* unit test seeds a two-sink graph, asserts both sinks written once.
2. ✅ **Real `SourceFinalize`** (`6965f6f3`) — extracted `BatchProcessor.finalizeSource` from `commit`
   (DuckLake register → manifest → backup → **markers LAST** → dedup ledger / DB-watermark; the actual
   range was `82-195`, three lines longer than the plan's `82-186` — a DB-export-watermark-LAST step was
   added after this plan was written). `commit` now delegates, so the flat path is byte-for-byte; the
   branch-aware `BatchGraphRunner` drives the same body as its `SourceFinalizer`.
   → *verified:* `BatchGraphRunnerFinalizeTest` — markers-LAST/backup/manifest land on a two-sink graph,
   and a fresh-connection replay over the same durable `BranchCommitLog` does **not** re-finalise (the
   marker step never runs twice). Cross-branch crash-ordering is the `BranchCommitCoordinator` invariant.
3. ✅ **Engagement predicate** (`6965f6f3`) — `BatchGraphRunner.engages(g) = dataFedSinkCount(g) > 1`,
   counting `SINK`-category nodes reached by a `data`/`route:*` edge and **excluding** the quarantine sink
   wired only by `unmatched` (mirrors what `PipelineExecutor` commits as a branch). Off the graph, not a flag.
   → *verified:* `BatchGraphRunnerTest` — a single-schema-shaped graph (one data sink + `unmatched`
   quarantine) counts `1` / does not engage; a two-route-sink graph counts `2` / engages. Flat-path
   `BatchProcessorTest` 5/5 unchanged.
   → ⏳ **Not yet wired into `CsvBatchStrategy`** — gated on `sinks:` (status box): a real ingest config
   cannot trip the predicate until a multi-sink `*_pipeline.toon` can be authored, so the engage-branch in
   the strategy would be dead code until then. Predicate + runner + finalize are ready to connect.
4. ⏳ **Lift the editor's refusals** (`MULTI_SINK` first) — **GATED on `sinks:` config-format.** Lifting
   the refusal without a format that can *represent* a second destination would let the editor save a
   `*_pipeline.toon` the engine can't round-trip. Keep `PipelineEditable`'s named codes; the mock
   (`mock/pipeline-editable.ts`) must refuse exactly what the server refuses (pinned strictness).
   → *verify (when unblocked):* `pipelines.handler.spec.ts` + a real-HTTP save of a two-sink pipeline.

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

**B3 — acquisition becomes its own driver** with its own concurrency budget, handing off a **durable**
descriptor set.

⚠ **Correction to this item's premise, from mapping the code (2026-08-01).** Two things it assumed are
wrong, and one of them makes B3 unsafe as originally written:

1. **The ledger is not usable as the hand-off.** `AcquisitionLedger` is written *once, post-commit*
   (`BatchProcessor.java:178-181`) and is **in-memory by default** (`-Dacquire.ledger.backend=memory`).
   It is a terminal-fact store, not a progression record — it cannot tell a separate driver what is
   staged and ready. Making it one is Stage C's job, not B3's.
   **The durable descriptor is therefore the filesystem**, which is also what the operator asked for
   ("download to inbox/backup and read from there / for local files (someone pushed)") and how local
   pushes already work. No new store.
2. **Fetch wrote *into the inbox*, and resume appends in place.** `SftpConnector.fetchTo` compares the
   destination's size to the remote length and appends, so a partial download sat in `dirs.poll` under
   its final name. That was safe **only** because acquisition and ingest ran in the same synchronous
   `collect()` call under one run-guard claim. Decoupling them — the entire point of B3 — would have made
   partial files ingestible. This is the R6/T23 anti-pattern (a second driver over the same inbox) and it
   is why B3 splits in two.

**B3a — stage, then land. ✅ SHIPPED 2026-08-01 (inspecto-engine 791/0/0, +4).**

`RemoteAcquisitionHandler` now fetches into a staging tree outside the inbox
(`collector.fetch.staging_dir`, default `<dirs.temp>/acquire` — this finally *implements* a config key that
was parsed and round-tripped but never acted on) and atomically renames into `dirs.poll` only once the file
is complete and integrity-verified. Resume survives because the staging path is deterministic, not a UUID
temp name. A corrupt download is dead-lettered from staging and never enters the inbox. Post-action runs
**after** landing (land-then-ack: never delete the remote original before the local copy is durably
visible). mtime is set **before** the move, so the file carries the source mtime the instant it appears —
otherwise METADATA dedup would briefly see the fetch time.

Staging inside `dirs.poll` is refused before any bytes move — it would defeat the mechanism, and
`PipelineConfigParser.validateDirs` already forbids every other dir from living under the inbox.
A non-atomic filesystem falls back to a copy **with a warning**: that is a real downgrade (briefly visible
half-written), so `staging_dir` and `dirs.poll` belong on one filesystem.

⚠ Also closed here: `fetchOne` resolved `pollRoot.resolve(rf.relativePath())` with **no containment check**,
so a crafted remote listing path (`../../x`) could write outside the poll root. Both resolutions now go
through one `contained()` path-jail helper (the house `resolve → normalize → startsWith` idiom). This was
pre-existing, not introduced by B3 — but the new staging resolution could not be written safely without it.

> **Operator decision 2026-08-01 — not backported.** The same unguarded resolution exists on the supported
> `4.x` line at `inspecto/src/main/java/com/gamma/inspector/SourceProcessor.java:386` (its pre-extraction
> home; `4.x` predates the module split, so B3a itself cannot apply there). Merge-forward policy would put a
> `fix:` on `4.x` first. The operator chose **master only, no backport** — accepting the traversal path on
> `4.x`. Recorded here so it is a decision on the record rather than an oversight; revisit if a `4.x`
> deployment ever takes remote-collector input, since an untrusted remote listing is the reachable input.

**B3b — the acquisition driver (✅ SHIPPED — steps 1–2 `1328c0f1`, step 3 `a4edbe19`).**

> **As-built.** All three steps landed as designed. `CollectorProcessor.acquire(cfg)` /
> `ingest(cfg, onCommit)` are now separate public units; `run()` = `acquire`+`ingest` for the one-shot
> CLI/`reprocess`/manual path. The always-on service drives them on two timers: `dispatchAcquireCycle()`
> under `acquire.pollSeconds` / `acquire.maxConcurrent` with a dedicated per-pipeline `acquireGuard`; the
> poll tick is now ingest-only. `countPending` became the exact landed backlog (the `pendingRemoteApprox`
> hack was deleted). Verified by `AcquisitionDriverTest` (ServiceLoader `faketest` connector, no network).
> **Deferred (→ BACKLOG §6):** the separate acquisition-side "listed remotely but not yet fetched" gauge —
> not built; no metric was renamed since none was misleading. **Gotcha:** manual "run now" acquires inline
> under the ingest `runGuard`, uncoordinated with the background `acquireGuard`, so it can overlap one
> background acquisition tick — benign (atomic land + dedup). Recorded in acquisition/framework.md.

The original design (kept for provenance):

Now that landing is atomic, acquisition can run on its own timer with its own budget, and ingest can
discover remote-fetched files by scanning the inbox exactly like locally-pushed ones — the unification the
operator asked for, and the removal of the last in-memory coupling.

*The seam is already clean.* `CollectorProcessor.collect(cfg, emitSignals)`
(`inspecto-engine/.../CollectorProcessor.java:191-278`) splits at **line 268**, where `materializeRemote`
returns:

| lines | phase | moves to |
|---|---|---|
| 207-268 | connector open · circuit breaker · `discover` · `StabilityGate` · gap detection · watermark filter · `materializeRemote` | **acquisition unit** |
| 270-276 | `dedupLocal` (markers / fingerprint) · `admit` (T15 cap) | **ingest**, unchanged |

Steps:

1. **Extract the acquisition unit** — `CollectorProcessor.acquire(cfg)`, public, returning how many files it
   landed. Everything above line 268, no-op for a `local` collector. This is the plan's own "draw the unit
   boundary now, change the driver later" (§5): the poll cycle keeps calling it inline until step 3.
2. **Ingest walks the inbox for every collector.** `collect()` stops using the configured connector for
   discovery and always walks `dirs.poll` via `LocalFileSystemConnector` with the same
   includes/excludes/depth. ⚠ **Do not also apply `source.stability` to that walk for a remote collector** —
   it is tuned for the *remote* listing (e.g. an SFTP `ready_marker`), and a landed file is atomically
   complete by construction, so gating it again is both wrong and redundant. Local collectors keep the gate:
   it is what protects a half-written local push.
3. **Give it a driver** in `CollectorService`: its own timer, its own process-wide budget (separate from
   `runPermits` — network fetch and DuckDB ingest should not compete for one allowance), dispatch-and-return
   exactly like B2, and its **own** `PipelineRunGuard` instance keyed per pipeline, so acquisition and
   ingest of the same pipeline may overlap while two acquisitions of it may not.

Known consequences to handle, not discover later:

- **`countPending` changes meaning for a remote collector.** `RemoteAcquisitionHandler.pendingRemoteApprox`
  (`CollectorProcessor.java:265`) exists only because the read-only scan must never fetch. Once ingest walks
  the inbox, pending *is* the landed backlog — exact, not approximate — and "listed remotely but not yet
  fetched" becomes a separate acquisition-side gauge. Decide the metric names before touching it.
- **Fetched files become re-listable.** Today's comment at `:262` notes remote discovery bypasses the poll
  walk "so staged files are never re-listed". After step 2 they *are* listed — which is fine (markers dedup
  them) but means the marker path is now load-bearing for remote sources in a way it was not.
- Pre-fetch dedup, the watermark filter, the circuit breaker and post-actions all stay on the acquisition
  side; markers/fingerprint dedup and the T15 cap stay on the ingest side.

**B4 — acquisition back-pressure on inbox high-water (✅ SHIPPED — `eb69f1ee`).**

> **Scope decided 2026-08-02 (operator).** The line below said "the multiplexer unit gets a queue-driven
> driver + the §3.5 escalation, *verbatim*." On building it that proved mis-sequenced and largely
> unnecessary as literally written:
> - **The verbatim §3.5 escalation is per-`data`-edge, intra-flow** ("de-schedule the *upstream node*"),
>   which needs the **node-level flow executor — Stage A — that is deferred behind Stage B**. Today a batch
>   runs its subgraph synchronously; there are no inter-node edges to queue on.
> - **The skew/starvation motivation (§4b) is already met by B2 + B3b:** B2 made poll non-overlap
>   per-pipeline (a slow pipeline's tick dispatches-and-returns), and B3b decoupled acquisition from ingest.
> - An explicit RAM/spill work-queue at the multiplexer would **re-introduce the very model D7/B2/§3.5
>   rejected** (no inter-node queues; the durable inbox *is* the queue), for a problem with **no SLA**.
>
> The realizable, non-speculative slice — the one edge that *does* exist post-B3b — is **acquire → ingest**,
> whose spill-to-disk queue **is already the durable inbox**. So B4 shipped as: `selectDueForAcquire` skips a
> pipeline whose `countPending` (the exact backlog from B3b) has reached `-Dacquire.backpressure.highWater`
> (0 = off), emitting `inspecto_acquire_backpressure_skips_total`. This de-schedules the *producer*
> (acquisition) when the inbox is full — negative feedback, and deliberately the mirror of T15, which must
> not throttle the ingest *consumer* on backlog (positive feedback). **This resolves open decision #1's
> justification of record: skew/latency, now concretely "don't let a slow ingest make acquisition fill local
> disk."** **Deferred companion (BACKLOG §6):** `acquire.maxFilesPerCycle` to bound a *single* cycle's fetch
> volume — the high-water gate bounds backlog across ticks, not within one.

The original design (kept for provenance):
The multiplexer unit gets a queue-driven driver + bounded spill-to-disk hand-off with a high-water mark
(the §3.5 escalation, verbatim).

**B5 — reconcile §3.8 (✅ SHIPPED — docs only, closes Stage B).** Done in the design doc's §3.8 as the
**"Collection is a unit, not a second scheduler"** block (a side-by-side table: deleted `ingest` job = two
schedulers racing one inbox with no lock; Stage B = one loop-scheduler-side driver split into producer/consumer
timers with an explicit, `acquireGuard`/`runGuard`-coordinated, B4-back-pressured hand-off over the durable
inbox). §3.5's escalation clause gained the matching "first slice shipped on acquire→ingest" note. This also
writes open decision #1's justification of record (skew/latency → "don't let a slow ingest make acquisition fill
local disk") into §3.5/§3.8 as required. `live-execution.md` needed no change — it describes at-rest authored
pipelines (`JobType.PIPELINE`), which never touch the acquire/ingest inbox that Stage B reconciles.
### Stage C — per-file stage housekeeping (✅ SIGNED OFF 2026-08-05 — folded into [`elt-final-amendment-plan.md`](elt-final-amendment-plan.md) §2.4 + Phase 4; reverses B10 ordering)

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

1. ~~**Justification of record for Stage B**~~ — ✅ **resolved 2026-08-02 (B4).** Skew/latency, made
   concrete: *"don't let a slow ingest make acquisition fetch unboundedly and fill local disk."* B4 exercises
   §3.5 on the acquire→ingest edge (durable inbox = the spill queue), throttling the producer — negative
   feedback, no throughput SLA needed. Write this into §3.5/§3.8 with B5.
2. ~~**Stage C now or after B?**~~ — ✅ **resolved 2026-08-05 (elt-final-amendment-plan §9 D-5).**
   After B, as part of the ELT final amendment: Stage C is folded there as the per-file housekeeping
   Guarantee (§2.4) and lands in its Phase 4, after the recipe compiler (Phases 1–3). That plan is
   the sign-off of record; this stage closes here by reference.
3. ~~**Pin the stale `fetchTo` doc line**~~ — ✅ settled 2026-08-01. `connectors.md` was wrong (fetch never
   went to the backup dir; backup is post-commit in `BatchProcessor.backupFile`). Rewritten, and B3a made
   the answer concrete: fetch lands in the staging tree, then renames into `dirs.poll`.
4. ~~**Should the `CollectorService` constructor reject duplicate pipeline ids?**~~ — ✅ **answered in code
   2026-08-02 by upstream `6d371d66`** ("harden startup: reject duplicate pipeline ids…"). The constructor
   now throws `IllegalStateException` with the same message `registerPipeline` uses, so both doors agree.
   Surfaced by B2's behaviour-change note above; pinned by
   `CollectorServiceTest.duplicatePipelineIdInTheRegistryIsRejectedAtConstruction`.

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
- ⚠ Fetch **stages outside the inbox and lands atomically** (B3a). Do not point `fetchTo` at `dirs.poll`
  again — resume appends in place, so that puts partial downloads where ingest can see them. The staging
  path must stay **deterministic** or resume restarts from zero.
- ⚠ A remote listing's `relativePath` is **untrusted input** — always resolve it through the `contained()`
  path jail before writing.
- ⚠ The acquisition ledger is written **post-commit and is in-memory by default**. It cannot be used as a
  work queue or a progress record until Stage C changes it.
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
