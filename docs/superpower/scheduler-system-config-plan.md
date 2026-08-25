# Scheduler system configuration — from `-D` flags to a hot-tunable UI surface

> **Status:** PROPOSED 2026-08-25, not started.
> **Goal:** move the fleet-level ingest concurrency controls off JVM system properties onto a
> persisted, UI-editable system configuration that applies **without a server restart**, so an
> operator can tune a live host instead of editing a launch script and bouncing the service.
> Vocabulary follows [`GLOSSARY.md`](../GLOSSARY.md): **Pipeline**, **Consignment**, **Collector**.

## 1. What exists today (grounded, not assumed)

The control stack is fully built. Every layer works; none of it is configurable at runtime.

| Layer | Mechanism | Property | Default | On exhaustion |
|---|---|---|---|---|
| Fleet ingest budget | `Semaphore runPermits` — `PipelineScheduler:152` | `-Dservice.max.runs` | **= registry size** (`ServiceBootstrap:68`) | **Queues** (`acquire()` on the worker vthread, `:291`) |
| Fleet acquisition budget | `Semaphore acquirePermits` — separate by design (B3b) | `-Dacquire.maxConcurrent` | = `service.max.runs` (`CollectorService:540`) | Queues |
| Acquisition high-water | back-pressure trip | `-Dacquire.backpressure.highWater` | 0 (off) | — |
| Poll cadence | `Scheduler.everySeconds("poll-all", …)` — `CollectorService:912` | `-Dservice.poll.seconds` | 60 | — |
| Acquire cadence | `everySeconds("acquire-all", …)` — `:917` | `-Dacquire.pollSeconds` | = poll seconds | — |
| Per-pipeline exclusion | `PipelineRunGuard.tryAcquire(id)` — `:238` | — | always on | **Skips** (never overlaps itself) |
| Admission control / fair share | `IntakeGovernor` | `-Dingest.maxFilesPerCycle` · `.minFilesPerCycle` · `.backpressure.adaptive` | **0 = OFF** | Caps files admitted per cycle; halves on overrun, restores when runs fit |
| Within one run | `processing.threads` × `processing.duckdb_threads` × `processing.batch.*` | per-pipeline TOON | 4 × auto | — |

Two facts about this drive the whole design:

**(a) The two halves are at different grains.** `SpaceBootstrap:33` builds one `CollectorService` —
and therefore one `PipelineScheduler` with its own two semaphores — **per space**. But
`IntakeGovernor.shared()` is a `static volatile` **process-wide** singleton (`IntakeGovernor:82`).
So a `service.max.runs` of 16 across four spaces is 64 concurrent runs on one host, while the
admission caps are already host-wide. The operator's constraint (disk/network I/O, CPU) is
host-level, so the budget must move to the governor's grain — not the other way round.

**(b) The oversubscription validator is wired to the wrong property.** `ConfigValidator:91` reads
`-Dsources.max` — the **CLI** orchestrator's flag (`MultiCollectorProcessor`) — and warns when
`sources.max × threads × duckdb_threads > cores`. In **server mode**, the mode that actually runs a
fleet, the multiplier is `service.max.runs`, which the validator never reads. The warning that
exists precisely for this failure is silent in the deployment shape that needs it.

## 2. Operator decisions (2026-08-25) — do not re-litigate

1. **Process-wide ceiling.** One shared budget every space draws from. "16" must mean 16 on the box.
2. **Fleet + per-pipeline in one slice.** The system settings surface ships together with the
   per-pipeline `processing.intake.*` overrides in the pipeline editor, so an operator can cap the
   fleet and then exempt or throttle one noisy Pipeline in the same session.
3. **Hot-apply is the point.** A change takes effect on the running server. Restart-only knobs are
   out of scope for this surface (see §6).

## 3. The `-D` objection, and why a PUT is justified here

`SystemRoutes` carries a recorded decision (2026-08-15) that `/system/operational-db` has
**deliberately no PUT**, on three grounds: the process cannot reconfigure its own dependency, no
change could take effect without a restart, and persisting one would create *a second declaration of
the same fact beside `-D`*.

The first two do not apply here — the scheduler budget is not a dependency of the process serving
the UI, and every knob in §4 demonstrably applies live. The third **does** apply and is binding. It
is discharged by making precedence unambiguous and visible, not by declining to write:

- **The file is the source of truth when present.** `-D` is a *bootstrap default consulted only when
  the file is absent*, exactly as `processing.intake` already overrides the `-Dingest.*` globals.
- **The read route reports provenance, not just value.** `GET` returns, per key, the effective value
  **and** where it came from (`file` | `property` | `default`) — the same "what is this deployment
  ACTUALLY using" contract `OperationalDbReport` already sets. An operator is never left guessing
  which of two declarations won.
- ⛔ **No key may be writable here and also read from `-D` at use time.** A knob is either sourced
  through the settings document (with `-D` as its absent-file default) or it stays `-D`-only. Split
  ownership of one fact is the thing the 2026-08-15 decision forbids.

## 4. Hot-apply feasibility — verified per knob

| Knob | Mechanism | Notes |
|---|---|---|
| `max_concurrent_runs`, `max_concurrent_acquisitions` | `Semaphore.release(n)` grows; `reducePermits(n)` (protected → small `ResizableSemaphore` subclass) shrinks | **A shrink drains, never interrupts** — see below |
| `poll_seconds`, `acquire_poll_seconds` | `Scheduler.everySeconds` already returns a `ScheduledFuture`; `CollectorService:912/917` **discards it**. Hold it → `cancel(false)` → re-register | `cancel(false)` lets a running tick finish |
| `max_files_per_cycle`, `min_files_per_cycle`, `adaptive` | `IntakeGovernor.policy` → volatile + setter that **clears learned caps on change** | The rule already exists for per-pipeline overrides (`IntakeGovernor.configure`, `:120`) — reuse it verbatim, don't invent a second one |
| `acquire_backpressure_high_water` | constructor-captured field → volatile + setter | same shape as the budgets |
| `processing.threads` / `duckdb_threads` / `batch.*` | **already hot** — re-read from `PipelineConfig` per run via `ConfigRegistry.rebuild` | no work |

⚠ **Shrink semantics (state this in the UI, not just the code).** Reducing 16 → 8 while 16 runs are
in flight lets them finish and blocks the next 8 admissions. Aborting a mid-commit ingest is not
safe — the commit sequence (`BatchProcessor.finalizeSource`: register → manifest → backup → markers)
is ordered so a *crash* is idempotent on rerun, but a deliberate mid-sequence kill is not a case it
was designed for. The budget contracts by attrition.

## 5. Phases

### S1 — `RunBudget`: lift the semaphores to a process-wide singleton
Extract `runPermits` / `acquirePermits` out of `PipelineScheduler` into `RunBudget.shared()`,
mirroring the **existing blessed idiom** `IntakeGovernor.shared()` / `.use()` (static volatile plus a
test escape hatch) rather than inventing a registry of schedulers to walk. `PipelineScheduler` takes
the budget by reference, exactly as it already takes the run guard and registry by reference. Backed
by a `ResizableSemaphore`.
→ **verify:** existing `CollectorServiceIngestLockTest` / trigger tests stay green; a new test proves
two `SpaceContext`s share one budget (N+M pipelines across two spaces admit ≤ ceiling).

> ⚠ **S1 changes a default and needs the §7 Q1 answer first.** Today the default is
> `registry.size()` *per space*. Summed process-wide it preserves current behaviour; anything
> host-derived (e.g. cores) is a silent behavioural change for every existing deployment on upgrade.

### S2 — the settings document
`scheduler.toon`, read through a provenance-reporting reader (`value` + `source` per key). Home
resolution, in order: explicit `-Dsystem.config.dir` → the spaces container root (`-Dspaces.root`) →
the single space's config dir (in single-tenant mode there is exactly one space, so per-space *is*
process-wide). ⛔ Never jail this path against `configDir`; the root is the `-Dassist.safety.roots`
list.
→ **verify:** unit tests for all three homes, absent-file fallback to `-D`, and a malformed value
falling back to the default (the `IntakeGovernor.Policy.intProperty` posture).

### S3 — routes (`endpoint` skill house style)
`GET /system/scheduler` → effective values + provenance + live gauges.
`PUT /system/scheduler` → validate, persist, hot-apply, return the new effective state.
Gate order: write-root 503 → spec/bounds 422 → path jail 403 → act atomically.
Capability **`canOperateRuns`** (the `ops` role) — this is runtime operation, not workbench
authoring, and not `canConfigureAccess` infrastructure. `ETags.respond` on the read.
→ **verify:** mandatory real-HTTP test class covering **every** gate, per the `endpoint` skill.

### S4 — hot-apply wiring
`RunBudget.resize`, `IntakeGovernor.setPolicy` (clearing learned caps on change), and holding +
re-registering the two cadence `ScheduledFuture`s.
→ **verify:** a test that shrinks the budget under load and asserts (a) in-flight runs complete,
(b) admissions stop at the new ceiling, (c) no restart; a test that changes `poll_seconds` and
asserts the next tick honours the new cadence.

### S5 — fix the oversubscription validator
`ConfigValidator` reads the **effective** run budget (`RunBudget.shared()`), not `-Dsources.max`,
keeping `sources.max` as the CLI-mode input. Smallest fix here, highest value per effort.
→ **verify:** a test asserting the warning fires in server mode at a budget the CLI flag never set.

### S6 — the UI system settings page (`angular-ui` skill)
Effective value + provenance chip per key, `canOperateRuns`-gated, with the drain semantics stated in
copy, the multiply rule (`budget × threads × duckdb_threads ≈ cores`) shown as live arithmetic
against the host's core count, and server validation surfaced inline.
→ **verify:** vitest specs **plus** driving the live preview as an operator — a green unit suite has
repeatedly missed wiring defects on this codebase.

### S7 — per-pipeline intake in the pipeline editor
Add `intake__max_files_per_cycle` / `__min_files_per_cycle` / `__adaptive` to `node-attributes.ts`
beside the three `batch__*` knobs. The server `FieldSpec`s already exist (`ConfigSpecs:204-209`) and
the values are already hot-applied per cycle — this is a UI-only addition.
→ **verify:** the `node-attributes.spec.ts` key-order pin is updated deliberately, not patched to
match output.

### S8 — queued-state observability (Tier 2, optional in this slice)
`inspecto_active_runs` and the `running` set exist; what is missing is *what is waiting and why* —
free permits, queue depth, and which pipelines the governor has throttled and to what cap. A
throttled pipeline is currently invisible outside the logs.

## 6. Explicit non-goals

- ⛔ **Priority / weighting / fair queueing.** The semaphore is non-fair (`new Semaphore(n)`, no
  fairness flag), so ordering under contention is arbitrary and a pipeline can barge. At 16-of-100
  that is a real starvation risk — but changing it is a *fairness contract* change deserving its own
  grounding pass, not a rider on a config slice.
- ⛔ **Per-space shares or reservations.** Deferred with the process-wide decision (§2.1); revisit
  only if multi-tenant hosts need isolation guarantees rather than a shared ceiling.
- ⛔ **Making `-Doperational-db` or other restart-only infrastructure writable.** The 2026-08-15
  decision stands on its own grounds and is untouched by this plan.

## 7. Open questions for the operator

1. **What is the process-wide default when `scheduler.toon` is absent?** Summing today's per-space
   `registry.size()` preserves current behaviour exactly, but keeps shipping the unbounded default
   that motivated this work. A host-derived default (e.g. `cores`) fixes the out-of-box case but
   silently changes behaviour for every existing deployment on upgrade. *Recommendation:* preserve
   behaviour, and let S5's now-working warning plus a one-click "recommended value" in S6 do the
   teaching — a config surface that changes what running systems do on upgrade is the wrong first
   impression.
2. **Should a `PUT` be journalled?** Every other write surface in this product leaves an audit trail,
   and a live concurrency change is exactly what an incident review asks about later.
