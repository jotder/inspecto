# §13 Run model — the dependency that unblocks `consignment_outputs`

**Status:** ✅ **slices 1 and 2 SHIPPED 2026-09-12**; slice 3 (the expensive one) open, and it carries the
one decision in §6. ⛔ The constraint of slice 4 is NOT yet addable — see the gap list in §4.1. Opened 2026-09-12 after `CONSIGNMENT-ID-DETERMINISTIC-1` closed with
`DbConsignmentOutputStore` *deliberately unconstrained*. This is the named blocker from that row.

⚠ Until 2026-09-12 "§13's Run model" was a **forward pointer with no content** — referenced from
`BACKLOG.md:250,840,854,910,923`, `okf/backend/engine/db-layer.md`, and the scale-out plan, but never
specified anywhere. (The archived `consignment-elt-architecture.md` §13 is a *different* §13 — a table of
model additions with no run-identity design in it.) This file is that missing spec.

---

## 1. What is already decided, and by whom

`docs/GLOSSARY.md` §6-A is **binding vocabulary and it already settles the semantics**:

> **Run ⊇ Consignment ⊇ File** … Identity is `(consignment_id, run_id)` — the run is the attempt, so a
> **reprocess is a new Run over the same Consignment**.

So the operator's instinct for the key was right. What was refuted was not the *shape* but the *claim that
the shape was implementable today*. Nothing in this plan changes the vocabulary; it implements it.

## 2. 🔴 The correction that must not be lost: a run id does NOT solve split-brain

`JobService.newRunId` (`inspecto-engine/src/main/java/com/gamma/job/JobService.java:975`) mints
`name-yyyyMMddHHmmss-seq` from `LocalDateTime.now()` and an **in-memory** `AtomicLong`. Therefore:

- Two pods double-executing the same work mint **different** run ids, so a `run_id`-bearing unique key
  **cannot dedupe them**. The same is true of one pod restarting — `seq` resets.
- That is not a defect in the key. It follows from the GLOSSARY: a run is *the attempt*, and two pods
  running concurrently genuinely are two attempts.

⛔ **So do not sell this work as discharging D15.** Split-brain is stopped by the **fenced `RunLease`**
(§5.2 slices B0/B1, shipped) — every write conditional on `owner = me AND epoch = mine`, so a stale owner
is *rejected*, not deduped. The unique constraint's job is strictly narrower, and worth stating exactly:

| Hazard | Who handles it |
|---|---|
| Two pods executing one pipeline concurrently | **Fenced `RunLease`** (shipped) — not this constraint |
| A crash/retry re-writing rows **inside one run** | **This constraint** (`ON CONFLICT`) |
| A reprocess needing NEW rows, not dropped ones | **This constraint**, via `run_id` in the key |

## 3. The second blocker, which `run_id` does not touch

Even with `run_id` fully populated, `(consignment_id, path, run_id)` still collides legitimately:
two sinks may target one store (`PartitionSinkWriter.java:113-117` sums `rowsByStore`, and its comment
says so), and `PartitionWriter.java:171,226` reveals each partition under a stable `<baseName>_out.<ext>`
with `OVERWRITE_OR_IGNORE`. The second branch's row carries a **different `row_count` for the same path**.

✅ **Resolution — use `ON CONFLICT DO UPDATE`, not `DO NOTHING`, for this table.** The file on disk really
is overwritten, so last-writer-wins in the registry *matches the filesystem*. `DO NOTHING` would keep a
`row_count` for content that no longer exists. ⚠ This is the opposite choice from `file_stages`
(`db-layer.md` §3.10), and deliberately so: a stage is an immutable fact about a point in time, an output
row describes a **mutable file**.

## 4. Build order — strictly by dependency, cheapest first

⚠ **Slices 1-3 deliver no constraint on their own.** `run_id` must be non-null on *every* path before the
key can be added, because NULL ≠ NULL would silently exempt whatever is left behind. Do not add the
constraint early.

| # | Slice | Sites | Difficulty |
|---|---|---|---|
| **1** | Thread `ctx.runId()` into derived tables + summaries | `ConsignmentProcessJobType.persistDerivedTables`/`persistSummaries` already receive `ctx`; add a `runId` parameter to `DerivedTableWriter.write` (`:163`) and `SummaryWriter.write` (`:274`) | **Low** — the value is already in scope one frame up |
| **2** | Thread the run id into the pipeline sink path | `PipelineJobRunner.execute(JobContext ctx)` (`:223`) has `ctx` and never reads it — it mints a clock-derived `batchId` at `:255` instead. Pass `ctx.runId()` into the `PartitionSinkWriter` ctor → `:117` | **Low-medium** — one ctor change; ⚠ do not conflate with `batchId`, which stays the Consignment id |
| **3a** ✅ | **SHIPPED 2026-09-13** — the legacy no-arg `Job.run()` path | `PipelineJobRunner.run()` called `execute(null)`; it now builds a `StandaloneRunContext` | **Low** — done |
| **3b** ✅ | **SHIPPED 2026-09-13** — the enrichment path | `EnrichJob` adopts `run(JobContext)`; `runResult` gains a `runId` beside `consignmentId`; all three callers supply one | **Medium** — done |
| **3** | Give the two framework-less paths a run identity | `ConsignmentIngestor:440` (reached from the static `CollectorProcessor.run`/`ingest`, no `JobContext` on the path at all) and `EnrichmentEngine:158`/`:179` (via `EnrichJob`, which implements only the legacy no-arg `run()`) | **High — the real cost.** Needs either a run identity minted at the `CollectorProcessor` entry point and threaded down, or these paths adopting `JobContext` |
| **4** | Make `run_id` `NOT NULL`, then add the key | `UNIQUE (consignment_id, path, run_id)` + `ON CONFLICT DO UPDATE` (§3), migrating by rebuild | **Medium** — reuse `DbFileStageStore.rebuildWithConstraint` verbatim; every DDL it needs is probed OK |

### 4.1 As-built after slices 1 + 2 — the paths that still write NULL

✅ **Shipped:** `ctx.runId()` now reaches the registry from derived tables and summaries
(`ConsignmentProcessJobType` → `DerivedTableWriter.write` / `SummaryWriter.write`, both of which gained a
`runId` parameter threaded through their private helpers) and from the pipeline sink path
(`PipelineJobRunner` → a new six-arg `PartitionSinkWriter` constructor). Mutation-proven: reverting just
the two `ConsignmentOutput` constructions to `null` turns exactly the two new assertions red, reading
`expected: <run-1> but was: <null>`.

⚠ **A third null path surfaced while building slice 2, and it was not in the original survey.**
`PipelineJobRunner.run()` (the legacy no-arg `Job` method) calls `execute(null)` — so `ctx` is legitimately
null there and that path still writes a NULL `run_id`. The call is now `ctx == null ? null : ctx.runId()`.
**This is a supported path, not an edge case**, and it must be closed with the other two.

⛔ **Therefore `run_id` is still NOT universally non-null. The remaining gaps are:**

| Path | Why it has no run id |
|---|---|
| `ConsignmentIngestor:440` | reached from the static `CollectorProcessor.run`/`ingest`; no `JobContext` anywhere on the path |
| ~~`EnrichmentEngine:158`/`:179`~~ | ✅ **CLOSED 2026-09-13 (slice 3b)** — all three callers now supply a Run id |
| ~~`PipelineJobRunner.run()` → `execute(null)`~~ | ✅ **CLOSED 2026-09-13 (slice 3a)** — it now builds a `StandaloneRunContext` |

### 4.2 ✅ Slice 3's precondition CLEARED 2026-09-12 — and it exposed a bigger finding

`EnrichmentEngine.runResult`'s fifth parameter was named `runId` and **never held one**: it is passed as
the `consignmentId` argument to `ConsignmentOutputs.fromPartitionCounts` at `:158`/`:179`. Renamed to
`consignmentId` (no behaviour change, callers are positional).

🔴 **But do NOT rename the callers' locals to match — the conflation is real, not cosmetic.** All three
callers (`EnrichJob:36`, `EnrichmentProcessor:48`, `EnrichmentService:200`) mint ONE string and use it for
**both** identities at once:

| Use | What it is there |
|---|---|
| `EnrichmentAuditWriter` row, `ConsignmentEvent(…)` | an **audit run id** — the attempt |
| `ConsignmentOutputs.fromPartitionCounts(…)` → registry | the **Consignment id** — the unit of work |

⚠ So on the enrichment path **Run and Consignment are currently the same string**, which is why nobody
noticed the missing run id: the value was already there, wearing the wrong hat. ⛔ Slice 3 on this path is
therefore **not** "thread a run id in" — it is "**split one identity into two**", and the split changes
what the audit and event rows mean. That is a behaviour change and needs the §6 decision first.

⚠ A related **pre-existing** mismatch is left alone deliberately:
`DecisionRuleApplier.Subject.enrichment`'s third argument fills a slot the signal payload publishes under
the key `"run"` (`DecisionRuleApplier:290`), so that payload reports a Consignment id under a run label.
Changing a published key is observable behaviour, not a rename — fold it into slice 3.

🔴 **Fix the naming collision in slice 3.** `EnrichmentEngine.runResult(..., String runId)` (`:115`) passes
its `runId` local as the **`consignmentId`** argument at `:158`/`:179`, while the actual `run_id` column
gets a literal `null` beside it. That local holds `cfg.name() + "-job-" + runStamp()` from
`EnrichJob.java:36` — an audit stamp, not a framework run id. Renaming it is a precondition for touching
this code safely; two different concepts currently share one name in one argument list.

## 5. Verification

- Slices 1-3: assert `run_id` is non-null on rows written by each path — one test per path, because the
  whole defect class here is *a path nobody checked*.
- Slice 4: mutation-test **the whole feature**, never one clause. Dropping only `UNIQUE` makes DuckDB
  refuse `ON CONFLICT` outright, so every insert fails and reads return 0 — red for the wrong reason
  (`BACKLOG.md`, `CONSIGNMENT-ID-DETERMINISTIC-1`).
- The Postgres leg must run, not skip: `PostgresStateStoreTest` covers `consignment_outputs` and now
  executes (15 tests, 0 skipped on PG 18.6, `1760a143`). Its class javadoc carries the working command.

## 5.1 ✅ Slice 3a SHIPPED 2026-09-13 — and the shared pieces it built

- **`RunIds`** — the Run id generator, **extracted from `JobService` so there is exactly one**. That is the
  point of the chosen option: a second generator at the Collector would have made "run id" mean two
  different things depending on which path produced the row. ⚠ Its counter moved from
  per-`JobService`-instance to process-wide — a widening, and the timestamp dominates anyway.
  `JobService`'s `RUN_TS`, its `seq` field and the `AtomicLong` import were removed as orphans.
- **`StandaloneRunContext`** — a minimal, **inert** `JobContext` for work outside the scheduler: no run
  log, no signals, no artifacts. ⛔ Do not wire it to the real stores — a standalone context that recorded
  runs would put CLI invocations into `job_runs` as though the scheduler had run them.
  🔴 `artifacts()` **must be a no-op, not a throw.** The first attempt threw and broke **29 tests**,
  because `PipelineJobRunner.execute` resolves `ctx.artifacts()` unconditionally; dropping is the contract
  `log()` and `signals()` already kept, and throwing was an inconsistency inside one class.
- **`PipelineJobRunner.run()`** now passes a real context. Mutation-proven: restoring `execute(null)` reds
  the new test on `expected: not <null>`. Verified 32 modules, 4391 tests, 0 failures.

## 5.2 ✅ Slice 3b SHIPPED 2026-09-13 — the enrichment path

🔴 **The plan said this slice had to "split one string into two", and that framing was wrong — usefully
so.** The single value `EnrichJob` mints is simultaneously the audit row's `runId` column, the
`ConsignmentEvent` correlation id, and the registry's `consignment_id`. Splitting it would have changed
**three observable values in order to fill one null column**.

✅ **So the Run id is ADDED, not substituted.** `EnrichmentEngine.runResult` gains a sixth parameter
carrying the attempt alongside the unit of work; every previously-emitted value keeps its exact prior
content, proven by construction — the local was *renamed* to `consignmentId` and the same variable still
feeds the audit row and the event. Only `run_id`, previously always NULL, is filled.

- `EnrichJob` now overrides `run(JobContext)` and reads `ctx.runId()`; its no-arg `run()` delegates via
  `StandaloneRunContext`, so both entry points carry an identity.
- **All three production callers supply one** — `EnrichJob` from its context, `EnrichmentProcessor` (CLI)
  and `EnrichmentService` (hosted) from `RunIds`. ⚠ Missing any one of the three would have left the path
  half-closed, which is indistinguishable from closed until a constraint is added.
- The five-arg overload survives for tests and **still writes NULL**, pinned by its own test so the
  honesty is deliberate rather than an oversight.

Mutation-proven: restoring a literal `null` reds the new test with
`expected: <enrich-attempt-1> but was: <null>`. Verified 32 modules, 4393 tests, 0 failures.

⚠ **The audit CSV's column is still named `runId` while holding the unit of work.** A pre-existing
misnomer in an operator-visible persisted surface, left alone on purpose: renaming it rewrites an audit
header, which is a separate decision from filling the registry's column.

⛔ **Only 3c remains, and the constraint still cannot be added.** `CollectorProcessor`/`ConsignmentIngestor`
is the widest slice — no `Job` exists on that path at all, so a context must be built inside `ingest` and
threaded four frames down through the `finalizeSource` overloads, with the `@PublicApi` signatures of
`run`/`ingest` preserved. One NULL path exempts exactly its own rows.

## 6. ✅ DECIDED 2026-09-13 — bring them onto `JobContext`

**Operator: option (b).** The alternative — minting a run id at the `CollectorProcessor` entry point — was
smaller, but would have created a *second* generator alongside `JobService`'s, leaving "run id" meaning
two different things depending on the path. ⚠ That is not hypothetical here: the enrichment path already
conflated a run id and a Consignment id into one string, and untangling it cost a commit of its own.
Option (b) also matches the GLOSSARY's single `Run` concept.

✅ Discharged for slice 3a by extracting `RunIds`; 3b and 3c reuse it and `StandaloneRunContext`.
