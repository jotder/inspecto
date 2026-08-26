---
type: Concept
title: Consignment concurrency — the four-layer hierarchy
description: How many Consignments execute at once (per Pipeline, per space, per server), how priority shares slots without starving anyone, which knobs hot-apply, and why the run budget is NOT redundant with the broker.
resource: inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java
tags: [engine, consignment, concurrency, scheduler, priority, backpressure, operations]
timestamp: 2026-08-25T00:00:00Z
---

# Consignment concurrency — the four-layer hierarchy

As-built after the scheduler-system-config plan (shipped 2026-08-25, commits `1b872515` → `275bf764`;
[archived plan](../../../archived-documents/plans-archive/scheduler-system-config-plan.md)).
Vocabulary follows [`GLOSSARY.md`](../../../GLOSSARY.md): **Consignment**, **Pipeline**, **Collector**.

## 1. The grain rule everything follows

**A permit counts a Consignment being executed — never a Pipeline, never a run.**

The operator constraint is host I/O and CPU, and the unit of heavy work is a Consignment being
parsed → transformed → written. A run that is planning, or parked waiting for a slot, costs
essentially nothing. Because every tier counts the same unit, `3` / `12` / `16` are comparable
numbers rather than three different things wearing one name.

| Layer | Cap | Where configured | Grain |
|---|---|---|---|
| Per Pipeline | `processing.threads` | pipeline TOON (editor: sink node) | Consignments of ONE pipeline |
| Per space | `max_concurrent_consignments` | space `scheduler.toon` · Settings ▸ Scheduler | across that space |
| Per server | `max_concurrent_consignments` | system `scheduler.toon` · Settings ▸ Scheduler | across the whole process |
| Share weight | `processing.priority` (1–3) | pipeline TOON (editor: sink node) | who wins a freed slot |

⚠ **`processing.threads` IS the per-pipeline Consignment cap.** The name predates the concept and is
kept only because renaming it is a GLOSSARY §13 decision; its help text says what it means.

## 2. `ConcurrencyBroker` — one monitor, three counters, weighted grants

Process-wide singleton (`shared()`/`use()`, the `IntakeGovernor` idiom). A worker calls
`admit(space, pipeline, cap, priority)` before heavy work and closes the returned `Permit` after;
`admit` parks the virtual thread until all three tiers have room.

- **Per-pipeline FIFO queues** — Consignment arrival order within a Pipeline is preserved by
  construction (which is what makes the `mtime` planner ordering mean anything downstream).
- **Stride scheduling** for grants: `stride = 6 / priority`, lowest accumulated `pass` wins. Priority
  3 gets ~3× the throughput share of priority 1 **and priority 1 provably keeps a non-zero share** —
  weights are *shares*, never precedence. Deterministic, so the ratio is unit-testable.
- **No banked credit**: a pipeline returning from idle joins at the current virtual time, so it can
  neither burst on saved-up credit nor be punished for having been quiet.
- **Inert unless configured**: with no space/system cap the only bound is the pipeline's own cap —
  byte-identical to the run-local `Semaphore(threads)` this replaced (`CollectorProcessor.ingest`).

⛔ **Three refused designs** (do not re-propose):

- **A global priority-ordered queue.** Strict priority ordering *is* the starvation trap: a
  priority-3 pipeline with a 100k-file backlog owns the head forever. It also reorders Consignments
  within a Pipeline, breaking arrival order.
- **A separate consignment-generation service.** Planning stays in the poll cycle; only *admission
  to execution* is shared.
- **Nested semaphores per tier.** No global view for the fairness decision, and cross-tier ordering
  is a deadlock hazard. One monitor guarding three counters instead.

## 3. ⛔ The run budget is NOT redundant with the broker

`PipelineScheduler.runPermits` (`-Dservice.max.runs`) looks like a second budget for the same lane.
It is not, and **retiring it would be a real regression** — a claim the plan itself made and the code
refuted (2026-08-25).

The broker admits **Consignment execution**. `runPermits` is the only bound on everything a cycle
does **before** the first broker permit is taken (`CollectorProcessor.ingest`):

- the stale-marker sweep,
- `collect()` discovery,
- **checksum dedup, which reads whole files** (`ledgerFilter` → `Checksums.of`),
- **archive expansion, which writes to temp and is itself virtual-thread parallel**
  (`UnpackStage.expand`),
- the planner's per-file `length()`.

Remove it and every pipeline hashes and unpacks simultaneously with nothing bounding the disk. **The
two budgets govern different *phases* of a run, not the same fact twice.**

## 4. Hot-apply: what changes live, and how a shrink behaves

Everything in the table below applies to the running server. **A shrink drains, never interrupts** —
Consignments already executing finish and the new ceiling gates the next admissions. Aborting a
mid-commit ingest is not something the commit sequence was designed for (it is crash-*idempotent*,
which is a different property).

| Knob | Mechanism |
|---|---|
| system / space Consignment caps | `ConcurrencyBroker.setSystemCap` / `setSpaceCap` |
| `poll_seconds`, `acquire_poll_seconds` (per space) | hold the `ScheduledFuture`, `cancel(false)`, re-register one interval out |
| intake globals (`intake_*`) | `IntakeGovernor.setGlobalPolicy` |
| `processing.*` (threads, priority, batch, intake) | already live — re-read per run via `ConfigRegistry.rebuild` |

⚠ **A cadence change must retarget the governor's overrun threshold** (`PipelineScheduler
.pollIntervalMs`). The T15 controller measures overrun *against the poll interval*; leaving it stale
throttles pipelines against a budget no longer in force.

⚠ **A changed `IntakeGovernor` policy clears every learned cap** — each was learned under the old
thresholds and could sit above the new base or below the new floor. An unchanged re-install is a
no-op, so the per-cycle re-install does not disturb adaptation.

## 5. Precedence and provenance (the "two declarations" rule)

A **stored key** in `scheduler.toon` is the source of truth; the `-D` flag is a **bootstrap default
consulted only while that key is absent**. Provenance follows the **key, never file presence**
(fixed 2026-08-26): a document storing only the intake globals or the resource pair leaves the cap
owned by `-Dscheduler.max.consignments` — before the fix, saving *anything* on the form wrote the
effective cap into the file and silently seized its provenance to `file` forever, so the next
deploy's changed flag did nothing. The cap is now nullable end-to-end (absent key = inherit, stated
`0` = unbounded), the UI seeds it only from `source === 'file'`, and an explicit `null` in a PUT
hands ownership back to the flag, live. `GET /system/scheduler` reports, per key, the effective
value **and** its `source` (`file` | `property` | `default`), so two declarations can never leave an
operator guessing which won.

⛔ **No key may be writable through the settings document and also read from `-D` at use time.** A
knob is either file-sourced (with `-D` as its absent-file default) or it stays `-D`-only. Split
ownership of one fact is what the 2026-08-15 operational-db decision forbids — and is why
`/system/operational-db` correctly still has no PUT while `/system/scheduler` does.

**A PUT merges per key**: absent = preserve stored, explicit `null` = clear and revert live to the
`-D` default. ⚠ A cap-only PUT that rewrote the whole document would silently destroy a stored
cadence — caught in review, and the same wipe existed in the offline mock.

**Every change is journalled** (operator decision 2026-08-26). The generic `AuditTrail` already
classifies these PUTs and records who / when / from where / status; what a path-classified audit row
cannot carry is *what the numbers became*, which is the half an incident review actually asks for. So
a `SCHEDULER_SETTINGS_CHANGED` event carries the **deltas** — one attribute per changed key rendered
`"<old> -> <new>"`, plus `tier` and `scope` — alongside the actor. Three rules, each pinned by a test:

- **Only an actual change is journalled.** A re-save of identical settings emits nothing; a trail that
  logs unchanged re-saves teaches an investigator to skim past it.
- **Only the keys that moved appear** in the delta, so the entry reads as the change it was.
- **An unset value renders `inherit`, not `null`** — that is what it means, and the trail is read by
  people.

⚠ It emits to **`api.service().eventLog()`** (the bound space's log), not `EventLog.current()`: a
hosted space has its own `EventLog`, while `current()` routes by the thread's space MDC and falls
back to global — so a bare `/system/scheduler` call would file the entry in a log the operator's
`/events` view never reads. That mis-filing is exactly what the first version did, and the test
caught it. Same seam `PipelineRoutes` uses for `PIPELINE_RENAMED`. Journalling is best-effort: a
settings change that succeeded must never fail on its own audit entry.

## 6. Two mechanisms, two questions — `IntakeGovernor` vs the broker

They compose; neither replaces the other.

- **Broker** — *how many Consignments may execute right now* (instantaneous slots).
- **IntakeGovernor** — *how many files one poll cycle may admit* (backlog fairness across time),
  halving a pipeline's cap while its runs overrun the poll interval and restoring it when they fit.

The governor's signal is **cycle overrun, not inbox lag**: admitting fewer files cannot reduce inbox
age or pending depth, so throttling on those is positive feedback that pins a healthy-but-backlogged
pipeline at the floor. Inbox lag stays an *alerting* surface, never a throttle input.

That pairing is what drains a 100k-file backlog fairly: bounded runs, permit released between them,
other pipelines interleaving at the run boundaries.

## 7. Per-Pipeline cadence and fetch parallelism

- **Cadence** — `trigger: {every: 30s|5m|1d}` or `cron:`, gated per tick by
  `PipelineScheduler.dueThisTick`. Cron wins when both are stated. ⚠ **The space's poll tick is the
  resolution floor**: a 30s pipeline needs the space tick at ≤ 30s. Authored on the acquisition node
  (`trigger__every` / `trigger__cron`) — these are **top-level `trigger:` keys the node borrows**,
  not `collector:` block keys.
- **Fetch parallelism** — `collector.fetch.parallel_fetch` (default 1 = sequential) switches one
  pipeline's download loop to a bounded **connector-session pool** in `RemoteAcquisitionHandler`;
  each worker gets its own session because connectors are not thread-safe. `fetch.rate_limit` caps
  bandwidth. ⚠ **Remote Collectors only** — a local inbox has nothing to download; files are pushed
  in by the producer, and the poll cycle just discovers them (with the stability window guarding
  against a half-written file, and `discovery: watch` for immediate pickup).
- Acquisition is **already continuous**: its own timer, its own per-pipeline guard, overlapping its
  pipeline's ingest, with two fetches of the same pipeline skipped rather than queued.

## 8. Operating it

`ConfigValidator` warns when `scheduler cap × duckdb_threads > cores`. ⚠ It reads the **installed
fleet cap** via `ConfigValidator.fleetConsignmentCap(IntSupplier)` — the etl module sits below the
engine and cannot see the broker, so `CollectorService`'s constructor installs
`ConcurrencyBroker.shared()::systemCap`. The CLI orchestrator installs nothing and keeps its
`-Dsources.max` check. (Before 2026-08-25 the check read only `sources.max` and was therefore silent
in server mode — the one deployment shape that runs a fleet.)

`GET /system/scheduler`'s `live` block answers *what is waiting and why*: `system_free` (**`null` when
unbounded — a fake `0` reads as "wedged"**), per-pipeline in-flight/waiting/priority, and `throttled`
— the pipelines the governor has admitted **below** their base cap, joined in the route because the
broker cannot see the governor. Only actually-throttled rows appear, hard-capped at 50 with the true
total beside it. Rendered on **Settings ▸ Scheduler** (`canOperateRuns`).
