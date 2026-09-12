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
| `EnrichmentEngine:158`/`:179` | via `EnrichJob`, which implements only the legacy no-arg `run()` |
| `PipelineJobRunner.run()` → `execute(null)` | the legacy no-arg `Job` entry point (**found 2026-09-12 during slice 2**) |

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

## 6. Open question for the operator

Slice 3 is most of the cost and has two shapes: **(a)** mint a run id at the `CollectorProcessor` entry
point and thread it down — smaller, but creates a *second* run-id generator alongside `JobService`'s; or
**(b)** bring `CollectorProcessor` and `EnrichJob` onto `JobContext` so there is exactly one generator —
larger, and it touches the `Job` SPI. ⚠ (a) risks two ids both called "run"; (b) is the one that matches
the GLOSSARY's single `Run` concept. Not decided.
