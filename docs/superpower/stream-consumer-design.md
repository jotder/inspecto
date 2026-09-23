# Stream consumer — design pass (`STREAM-CONSUMER-1`)

**Status:** DESIGN — awaiting operator answers (§6). No code written. Written 2026-09-24.
Board row: `BACKLOG.md` §3 `STREAM-CONSUMER-1` · ROADMAP §3.4 · `okf/capabilities/acquisition/acquisition.md` §3.9.

## 1. Problem

ROADMAP §3.4's exit criterion: *a streaming topic lands records through the adapter with at-least-once
semantics.* The row frames the gap as "the consumer loop does not exist" and asks **where it should live**:
the Collector scan, or a long-running Job.

⚠ **The premise is half-stale.** A consumer already exists and ships in every edition (`ACQ-5`, 2026-07-08):
`KafkaConnector` drains each partition's backlog **per acquisition cycle** into a virtual slice file
`<topic>-p<N>-<from>-<to>.<ext>` that rides the normal batch path. So the loop *today* is the Collector
scan. What is actually missing is (a) proof that its at-least-once claim holds end to end — §2.2 finds it
likely does **not** — and (b) any mode that drains faster than the poll tick. The design question is
therefore "fix the scan-driven loop, then decide whether a continuous lane is worth adding", not
"build a loop from nothing".

## 2. Constraints found in code

### 2.1 What exists

| Fact | Where |
|---|---|
| Kafka uses `assign()`+`seek()`, no consumer group, `enable.auto.commit=false`; the frontier lives in the acquisition ledger watermark | `inspecto-connectors/.../KafkaConnector.java:44-48`, `:317` |
| `discover` computes `from` = committed ledger watermark (or `options.start`), `to = min(end, from + max_records)`; one slice per partition with backlog | `KafkaConnector.java:146-157` |
| `fetchTo` drains `[from,to)`, then **stashes** the reached offset keyed by the `dest` path it was given | `KafkaConnector.java:186-226` |
| Stash is an **in-memory** map keyed by absolute path; a restart loses it | `inspecto-acquire/.../AcquisitionLedgers.java:86-88`, `:121-131` |
| Commit takes the stash by the **inbox** path, only after the batch is durable | `inspecto-engine/.../ConsignmentIngestor.java:598-609` |
| Remote fetch goes to a **staging** path, then `land` renames it into the inbox | `inspecto-engine/.../RemoteAcquisitionHandler.java:185-201`, `:399` |
| Capabilities are `STREAM` only; any post-action except RETAIN is rejected — land-then-ack is moot for Kafka | `KafkaConnector.java:129-130`, `:239` |
| The loop driver: a scheduler tick, per-pipeline `trigger:` gating (`DEFAULT_POLL` = every tick, `event`/`manual` never loop-driven) | `inspecto/.../service/PipelineScheduler.java:324`, `:470-480` |
| Acquisition has its own permit budget and a B4 back-pressure gate (inbox `countPending` ≥ high-water ⇒ skip acquiring) | `PipelineScheduler.java:218-223`, `:667-677` |
| Cadence is hot-applied; push wake-up exists (`POST /collectors/{id}/notify`) | `CollectorService.java:640-652`, `inspecto/.../control/AcquisitionRoutes.java:27` |
| Jobs are **finite** runs: per-job non-overlap lock (a second fire records `SKIPPED`), 4 in-flight Runs total by default | `inspecto-engine/.../job/JobService.java:65-70` |
| Connector sidecar (with `kafka-clients`) ships in every edition, not gated | `tools/bundle-modules.mjs:40-45` |

### 2.2 Suspected defects (hypotheses — reproduce before fixing)

1. **The frontier never advances on the remote path.** `fetchTo` is called with the *staging* path
   (`RemoteAcquisitionHandler.java:399`, target = `part` from `:185`); commit looks up the *inbox* path
   (`ConsignmentIngestor.java:603`). The keys are distinct absolute paths (`AcquisitionLedgers.java:87`), so
   `takeDbWatermark` returns empty and the watermark is never recorded. `KafkaConnectorTest:122-135` asserts
   the stash against the same `dest` it passed in, so it cannot catch this. The same applies to
   `DbExportConnector` (`:156`).
2. **Overlapping slices ⇒ duplicate rows.** With (1), or with any slice landed but not yet committed, the next
   cycle's `discover` re-reads the old frontier and emits `[from, to')` with a larger `to'` — a *new* name,
   so name-based marker/ledger dedup does not suppress it, and rows in the overlap ingest twice.
3. **Restart after land, before commit** loses the in-memory stash (§2.1): the slice ingests, the frontier
   does not move, and (2) follows.

At-least-once is the promise; (1)–(3) turn it into *unbounded* re-delivery. None is specific to where the
loop lives — **all three must be fixed under every option**.

## 3. Options

### A. Collector scan (today's home), hardened

The loop stays the scheduler tick. Fix §2.2; add an **in-flight fence**: `discover` emits no slice for a
partition while an earlier slice for it is staged/landed-but-uncommitted. Latency = acquisition cadence
(or a `notify`).

- **Offset/ack:** ledger watermark, advanced at commit; frontier made durable by deriving it from the slice
  *name* (rename the slice to its actually-reached `[from,pos)` before landing) instead of the in-memory stash.
- **Backpressure:** existing B4 high-water + `max_records` + acquisition permits — nothing new.
- **Crash:** partial slice in staging is re-drained from `from` (deterministic name); landed slice commits
  and advances the frontier from its name. Re-delivery bounded to one slice per partition.
- **Cost:** S. **Limit:** minutes-scale latency floor; one broker connect per cycle.

### B. Continuous stream lane inside `PipelineScheduler` (opt-in `trigger: stream`)

A virtual thread per stream Collector holds the consumer open, drains a slice when backlog ≥ N records or
T elapsed, lands it, and hands an ingest run to `triggerWorkers` through the existing per-pipeline
coalescer (the `onUpstreamCommit` pattern, `PipelineScheduler.java:517-522`). Everything in A still
applies — B is A plus a different trigger.

- **Backpressure:** the fence (one uncommitted slice per partition) *is* the backpressure; B4 still applies.
- **Crash / redeploy:** lane stops on pipeline deactivate/space close; restart is identical to A.
- **Governance:** holds an acquisition permit only while draining, not while idle-waiting, so it cannot
  starve polling Collectors.
- **Cost:** M. **Gain:** seconds-scale latency. **Risk:** a new long-lived thread class needs lifecycle
  wiring (space close, run lease under Enterprise scale-out).

### C. Long-running Job

Rejected on the code. `JobService` models finite, non-overlapping Runs (`JobService.java:65-70`): a permanent
Run occupies one of the 4 global slots forever, every later cron fire records `SKIPPED`, and a Job has no
path to the pipeline's inbox, run guard or acquisition ledger — it would duplicate the scheduler's wiring
rather than reuse it. Observability of a Run that never ends (Run history, duration) is also meaningless.

### Cross-cutting (all options)

- **Dedup interplay:** slice-name identity + the fence give *slice-level* at-least-once with at most one
  slice re-delivered per partition per crash. Row-level exactly-once is out of scope; the `envelope` payload
  already carries `topic/partition/offset`, so a Dataset that needs it can dedup on that key downstream.
- **Edition gating:** none needed — the connector already ships in all editions (`bundle-modules.mjs:40-45`).
  `EDITIONS.md` `SP-ACQ-09` (🟡 in all) is the cell to update when §5 slice 3 lands.
- **Observability:** per-partition lag gauge (`endOffset − committed frontier`), slices drained/committed
  counters, and a `FileStage.WATERMARK_ADVANCED` stage already recorded at commit
  (`ConsignmentIngestor.java:610-611`) — surfaced on the Collector's status.

## 4. Recommendation

**A now, B only on demand.** Fix §2.2 and add the fence inside the Collector scan — that alone meets
ROADMAP §3.4's at-least-once exit criterion and is needed by B anyway. Reject C. Build B (`trigger: stream`)
only if an operator names a latency target the poll cadence cannot meet.

## 5. Build plan (ordered slices)

| # | Slice | Test strategy |
|---|---|---|
| 0 | **Reproduce §2.2(1)–(3)** end to end: a fake `CollectorConnector` stashing via `stashDbWatermark`, driven through `RemoteAcquisitionHandler` with a staging dir, then commit; assert the ledger watermark. Expected RED. | New engine test; mutation-check it goes green only with the fix |
| 1 | **Frontier survives land + restart:** slice renamed to reached range before land; commit derives the frontier from the landed name (Kafka) / re-keys the stash on land (DB export) | Slice-0 test green; restart variant (clear in-memory map between land and commit) green |
| 2 | **In-flight fence** in `KafkaConnector.discover` (skip partition with an uncommitted slice in staging/inbox) | Unit: two cycles before commit ⇒ one slice; integration: no overlapping ranges ingested |
| 3 | **Lag + counters** metrics, Collector status field; `SP-ACQ-09` cell + `acquisition.md` §3.9 updated | Metric unit test; doc guards |
| 4 | *(Gated on Q2)* `trigger: stream` lane in `PipelineScheduler` + lifecycle on space close | Real-HTTP activate/deactivate test; scheduler unit test with a fake clock |

Per CLAUDE.md, each slice runs its affected test classes only (`-pl <module> -Dtest=A,B`).

## 6. Open questions for the operator

1. **Is §2.2 a P1?** If slice 0 reproduces it, every Kafka and DB-export Collector re-delivers rows today. Promote slices 0–2 to a P1 row of their own, or keep them inside `STREAM-CONSUMER-1` (P2)?
2. **Is there a latency target?** Option B is only worth building if some deployment needs sub-cadence delivery. Name it, or accept A as closing the row.
3. **Frontier durability for DB export:** accept a re-key-on-land fix (restart still re-exports one slice), or require the frontier to be recoverable from the landed file like Kafka?
4. **Consumer groups:** the ledger-watermark model means no broker-visible lag and no multi-consumer sharing. Keep it (fewer ACLs, one offset store), or is broker-side visibility required by a customer?

## References

- `okf/capabilities/acquisition/acquisition.md` §3.7 (land-then-ack), §3.9 (`db`/`kafka` virtual slices)
- `roadmap/ROADMAP.md` §3.4
- `superpower/enterprise-scale-out-plan.md` (run lease — relevant to a long-lived lane under option B)
