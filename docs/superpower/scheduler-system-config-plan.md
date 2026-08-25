# Scheduler system configuration — from `-D` flags to a hot-tunable UI surface

> **Status: BUILT 2026-08-25 (first slice, uncommitted)** — the consignment-grain core shipped in one
> pass: `ConcurrencyBroker` (Part B) replaces the run-local per-run semaphore at
> `CollectorProcessor.ingest`; `processing.priority` (1..3, parse-refused out of range) end-to-end
> (parser → FieldSpec → safety bound → NodeAttributes/contract JSONs → pipeline editor);
> `scheduler.toon` at both tiers with `GET/PUT /system/scheduler` + `/settings/scheduler`
> (provenance-reporting, canOperateRuns, hot-apply, boot install); Settings ▸ Scheduler UI section;
> per-pipeline `intake__*` + `priority` on the sink node (lift/lower + mock mirror + name-contract
> rows). Tests: ConcurrencyBrokerTest (5 Part-B gates) · PipelineConfigPriorityTest ·
> ControlApiSchedulerSettingsTest · regenerated NodeAttributes/StepTypes contracts.
> **S5 CLOSED 2026-08-25 (same day):** `ConfigValidator.fleetConsignmentCap(IntSupplier)` — the etl
> module takes the fleet factor as an installed supplier (it sits below the engine, so it cannot read
> the broker; a supplier keeps the check live against a hot-tuned cap without inverting the
> dependency). `CollectorService`'s ctor installs `ConcurrencyBroker.shared()::systemCap`; the CLI
> installs nothing and keeps its `-Dsources.max` check. New warning: `cap × duckdb_threads > cores`
> (the cap counts Consignments fleet-wide, so `threads` does not multiply beyond it; auto
> `duckdb_threads=0` is excluded — it divides cores per pipeline and cannot be summed from one
> config). Pinned by `ConfigValidatorTest.oversubscriptionWarningUsesTheInstalledFleetConsignmentCap`
> including the silent-when-uninstalled arm.
> **Poll-cadence hot-apply CLOSED 2026-08-25 (same day):** `CollectorService` now holds both
> `ScheduledFuture`s; `reschedulePoll`/`rescheduleAcquire` cancel-without-interrupt and re-register
> (first fire one full interval out), and `reschedulePoll` retargets `PipelineScheduler`'s T15
> governor threshold — the overrun budget must follow the cadence or the controller throttles
> against a stale budget. Persisted as optional `poll_seconds`/`acquire_poll_seconds` in the SPACE
> `scheduler.toon` (cadence is per-space; the system tier ignores them); `PUT /settings/scheduler`
> **merges per key** — absent = preserve stored, explicit `null` = clear + revert live to the `-D`
> default (a cap-only PUT destroying a stored cadence was caught in review before it shipped, and
> the same wipe existed in the mock). UI: two cadence fields on Settings ▸ Scheduler (blank =
> inherit); mock mirrors merge/clear/bounds atomically.
> **Still open:**
> `IntakeGovernor` *globals* through the settings doc (per-pipeline overrides are UI-editable, the
> `-Dingest.*` fleet defaults are not), ingest `runPermits` retirement (left in place, now redundant
> above the broker), S8 queued-state pane (the broker snapshot is served on `GET /system/scheduler`
> and rendered in Settings ▸ Scheduler; a dedicated ops view remains open), and §7 Q2 (PUT
> journalling — current PUTs log at INFO only).
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

- ~~⛔ Priority / weighting / fair queueing~~ — **SUPERSEDED 2026-08-25 by Part B below**, which is
  the grounding pass this line asked for. The starvation risk it named is solved structurally
  (weighted shares, not precedence), not by a fairness flag on the semaphore.
- ~~⛔ Per-space shares or reservations~~ — **partially superseded**: Part B introduces a per-space
  *hard cap* (a tier in the broker). Per-space *weights/reservations* stay out of scope.
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

---

# Part B — Consignment-grain hierarchy + priority (amendment 2026-08-25)

> Commissioned by the operator the same day, superseding §6's priority non-goal. Requirements:
> **R1** ≤ N concurrent consignments per Pipeline (pipeline config, e.g. 3) · **R2** ≤ N per space
> (space config, e.g. 12) · **R3** ≤ N per server (system config, e.g. 16) · **R4** a Pipeline
> priority 1–3 giving its Consignments a larger share of processing — **with a hard constraint that
> low-priority Pipelines are never starved**.

## B0. The grain decision (the reading everything hangs on)

**A permit counts a Consignment being processed, not a Pipeline with a run open.**

- R1 at "3 per pipeline" forces consignment grain at tier 1, and mixed grains would make 3/12/16
  incomparable numbers drawn from different units.
- The operator's constraint is host I/O and CPU; the unit of heavy work is a Consignment being
  parsed/transformed/written. A run that is planning, or parked waiting for a grant, costs ~nothing.
- **The execution point is already permit-shaped.** Inside one run, every planned Consignment is
  submitted up front to a virtual-thread executor and parks on `permits.acquire()` bounded by
  `cfg.processing().threads()` (`CollectorProcessor:147-149`). Part B replaces that *run-local*
  semaphore with a *shared* broker — it does not create a new execution model.

Consequently **R1 is already shipped**: `processing.threads` *is* the per-pipeline concurrent-
consignment cap (per-run, and `PipelineRunGuard` guarantees one run per pipeline, so per-run =
per-pipeline). It keeps its name for now (a rename is a GLOSSARY §13 decision); its FieldSpec help
text should say what it means: "max concurrent Consignments of this Pipeline."

## B1. Refused alternatives (with reasons — don't re-propose)

- ⛔ **A global priority-ordered Consignment queue.** Strict priority ordering is exactly the
  starvation trap the operator named: a priority-3 Pipeline with a 100k-file backlog occupies the
  head forever. It also reorders Consignments *within* a Pipeline — breaking mtime arrival ordering
  (operator decision 2026-08-12, `ConsignmentPlanner.Order`) — and fights the run-scoped
  ledger/manifest/audit structure for no gain.
- ⛔ **A separate consignment-generation service.** Planning stays in the poll cycle
  (`ConsignmentPlanner.plan` per run). Nothing about R1–R4 needs planning moved; only *admission to
  execution* becomes shared.
- ⛔ **Three nested semaphores** (pipeline → space → system). Nested blocking acquisition across
  tiers is a deadlock/ordering hazard, gives no global view for a fairness decision, and
  `Semaphore` fairness is FIFO-per-semaphore, not weighted-across-pipelines.

## B2. The `ConcurrencyBroker`

A process-wide singleton (the `IntakeGovernor.shared()`/`.use()` idiom — static volatile + test
escape hatch). One monitor lock guarding:

- **Three counter tiers**: in-flight per pipeline / per space / process total, with limits
  `pipelineCap(id)` (= `processing.threads`), `spaceCap(spaceId)`, `systemCap`.
- **Per-pipeline FIFO wait queues** — within-pipeline arrival order preserved by construction.
- **Stride scheduling** for grants: each pipeline carries `stride = K / priority` and an
  accumulated `pass`. When any slot frees, the *eligible* pipeline (queue non-empty ∧ under all
  three caps) with the lowest `pass` is granted; its `pass += stride`. Priority 3 receives ~3× the
  throughput share of priority 1 under saturation, and priority 1 **provably keeps a non-zero
  share** — weights are shares, never precedence. Deterministic (unlike lottery), so the ratio is
  unit-testable. New/idle pipelines join at `max(minPass)` so a returning pipeline cannot burst on
  banked credit.

Worker contract: `broker.admit(pipelineId, spaceId, priority)` parks the virtual thread (cheap);
`broker.release(...)` in `finally`. The call site is the existing `permits.acquire()` /
`release()` pair in `CollectorProcessor.ingest` — a surgical swap. `PipelineTestRun` (scratch dry
runs) bypasses the broker; the T job lane (`JobService`, enrichment) is out of scope, as today.

Hot-apply: caps are volatile fields (`setSystemCap`/`setSpaceCap` — shrink drains by attrition,
§4's rule); priorities are installed idempotently per cycle from `PipelineConfig`, exactly the
`IntakeGovernor.configure` pattern (a *changed* priority resets that pipeline's `pass` to
`max(minPass)`; unchanged is a no-op).

Bounded submission: "all consignments submitted up front" is safe because the IntakeGovernor caps
files admitted per cycle — the two mechanisms compose (governor = backlog fairness across time,
broker = instantaneous slots).

## B3. What happens to the existing budgets

| Existing | Fate |
|---|---|
| Per-run `Semaphore(processing.threads)` (`CollectorProcessor:148`) | **Replaced** by the broker's pipeline tier (same value, same meaning) |
| `PipelineScheduler.runPermits` (ingest run grain) | **Retired as the primary budget** — a second competing budget at a different grain is exactly the confusion §3 forbids. Keep the field defaulting to effectively-unbounded for one release as a safety valve; remove after Part B soaks. |
| `acquirePermits` (network fetch) | **Untouched** — acquisition is a different resource (B3b) and stays run-grain |
| `IntakeGovernor` | **Untouched, complementary** — admission per cycle vs execution slots |

S1 of Part A is **restated**: `RunBudget` at run grain is superseded — the process-wide singleton
to build is the broker, and Part A's S2–S4 config/route/hot-apply work targets its caps. S5's
arithmetic becomes `system_cap × duckdb_threads ≈ cores`.

## B4. Config surface

| Key | Where | Values | Notes |
|---|---|---|---|
| `processing.threads` | pipeline TOON (exists) | ≥1 | R1; help text updated |
| `processing.priority` | pipeline TOON (**new**) | 1–3, default 1 | FieldSpec + ConfigSafetyValidator bound + `node-attributes.ts` entry beside `batch__*` |
| `max_concurrent_consignments` | per-space `scheduler.toon` (**new**) | ≥1, absent = unbounded | R2 |
| `max_concurrent_consignments` | system `scheduler.toon` (Part A S2) | ≥1, absent = §7 Q1 default | R3 |

Cross-field sanity (warn, not reject): a pipeline cap above its space cap is legal but inert —
`ConfigValidator` should say so.

## B5. Verification gates

1. **Share ratio**: 2 saturated pipelines at priority 3 vs 1 → completed-consignment ratio ≈ 3:1
   (tolerance), and the priority-1 pipeline's throughput **> 0 at all times** (the starvation pin).
2. **Within-pipeline FIFO**: grant order per pipeline = submission order.
3. **Caps hold jointly**: in-flight never exceeds pipeline/space/system limits, across 2 spaces.
4. **Hot shrink drains**: in-flight completes, next admissions honour the new cap, no restart.
5. **Hot priority change**: takes effect on the next grant; `pass` reset rule holds.
6. **No banked credit**: an idle-then-returning pipeline gets its share, not a burst.

## B6. Part B open questions

1. **Default `spaceCap` when a space's `scheduler.toon` is absent** — unbounded (system cap alone
   governs) is the least-surprise default; confirm.
2. **Should priority also weight *acquisition*?** Part B scopes priority to ingest execution slots;
   the fetch lane keeps plain FIFO. Revisit only if remote-fetch contention shows up in practice.
