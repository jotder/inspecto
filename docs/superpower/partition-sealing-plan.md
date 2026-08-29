# Partition sealing — slicing plan for Consignment §8 + §11.4

**Status:** discussion input, not an approved plan. Written 2026-08-30 after grounding both rows
against the code. ⚠ This plan deliberately **does not answer** the two questions in §5 — they are the
operator's, and building past them would bake a guess into a durable table.

**Why this exists:** the gate register carried "Consignment §8 end-of-period summary pass · §11.4
`partition_state`" as two one-line rows. Grounded, they are one workstream of real size, and the
register's own framing understated it. The design is sound and already written
(`consignment-elt-architecture.md` §8, §11.4) — what was missing is a slicing that can be scheduled.

---

## 1. Ground truth (verified 2026-08-30, not quoted from the row)

- **Nothing is implemented.** `partition_state` / `PartitionState` / `SEALED` / `REOPENED` return
  **zero hits** across all Java sources. §11.4's own heading is literally *"Partition state — nothing
  exists"*. The only `histogram` hits in the tree are unrelated operational metrics
  (`AcquisitionTelemetry`, `MetricRegistry`).
- **The representation question is genuinely CLOSED.** The fixed-bucket histogram is decided
  (`consignment-elt-architecture.md:414`), reached by a live DuckDB probe showing `approx_quantile`
  exposes no mergeable state (`:402-412`, verified 2026-08-28). 🔴 BACKLOG said "still unverified"
  for two days after; corrected 2026-08-30. **Do not reopen this.**
- **The dependency is real and namable.** `Measure.Composability`
  (`inspecto-engine/src/main/java/com/gamma/consignment/Measure.java:33-44`) already has
  `BUCKETED` / `COMPUTED_FROM_DETAIL`, and `GuardedSummaryEmitter:96-105` already **refuses** a
  mislabelled non-additive measure. Both shipped. Neither computes a histogram or a detail
  recompute — that logic *is* §8. So the guard is in place ahead of the thing it guards.
- **What to build on:** `SummaryWriter` + `GuardedSummaryEmitter` (the §7.2/§7.3 summary tier,
  shipped 2026-08-04), the `ConsignmentOutput` registry (`ConsignmentOutput.java:60`,
  `DbConsignmentOutputStore.java:47`), and `CommitLog` (`inspecto-etl/.../CommitLog.java:36`).
- **Not decision-gated in the docs** — but see §5: two inputs are unstated, and they are load-bearing
  in a way a shift cannot resolve by reading code.

---

## 2. The shape, in one paragraph

§8.1 needs **no** end-of-day: when a Consignment lands, recompute the non-additive summary for every
record-day it touched, debounced, with a `computed_at` freshness stamp. §8.2 adds **finality** —
a day partition gets an explicit `OPEN → SEALED → REOPENED` state, sealing when the completeness rule
is met **or** the lateness horizon expires, whichever is first, and publishing which one fired.
Sealed-on-timeout should open an Incident rather than quietly produce a short number.

---

## 3. Slices, each shippable, each with a verify gate

| # | Slice | Verify gate |
|---|---|---|
| **P1** | `partition_state` table + a `PartitionState` record and store, following the `ConsignmentOutput` registry's shape. State + `sealed_at` + `seal_reason` + `row_count` + `summary_computed_at` only — **not** the §10.3 compaction columns, which have no consumer yet | Round-trip through the store; an unknown transition is refused, not coerced |
| **P2** | §8.1 debounced recompute on Consignment landing, stamping `summary_computed_at` | A day converges as stragglers arrive; recompute count respects the debounce; the stamp moves |
| **P3** | Signal-type **constants class** first, then the new types (`partition.sealed`, `partition.reopened`, `summary.recomputed`, …) | ⛔ The design is explicit: **do not grow the `EventType` enum** — these ride `Signal` on one `SIGNAL` Event. A typo'd literal silently breaks a subscriber, which is why the constants class leads |
| **P4** | The sealing evaluator: completeness rule **or** horizon expiry → `SEALED` with the reason, publishing `partition.sealed` | Both arms fire and are distinguishable; sealed-on-timeout raises an Incident, not a silent short number |
| **P5** | `REOPENED` on late data: recompute, republish, **emit the reopen** | A consumer of the sealed number can detect its copy is stale — this is a contract break by design, so it must be loud |
| **P6** | §8.3 horizon-ordering check in the config validator: `lateness ≤ seal ≤ compaction ≤ raw retention` | A misordered config is a **422 at authoring time**, not a corrupt partition six weeks later |

**P1–P3 are independent of the two open questions; P4 onward are not.** That is the natural stopping
point if the questions stay unanswered.

---

## 4. Traps

- 🔴 **`baseline_eligible` is derived from `seal_reason`** (§9.3) — store it derived or not at all;
  a second hand-maintained copy of a derivable fact is the drift this repo has been bitten by
  repeatedly.
- ⚠ **Per-file facts are ledger rows, not signals** (design, §11.5). At high file counts one signal
  per file floods the Event store. Sealing is per *partition*, which is the right grain — keep it
  there.
- ⚠ The §10.3 compaction columns appear in the design's table sketch. They have **no consumer today**;
  adding them in P1 would ship columns nothing writes or reads.
- 🔴 The lateness horizon interacts with the **one-year audit-retention window** (operator,
  2026-08-30) and with `retire_superseded`. §8.3's chain ends at *raw retention* — do not set a
  horizon that outlives the data it would need to recompute from.

---

## 5. ⛔ The two questions this plan will not answer for you

1. **What is the completeness rule?** §8.2 says a day seals when "the completeness rule is satisfied",
   and the table carries `reported_contributors` — implying an expected-contributor count. Whether
   that is a declared per-table list, a learned baseline, or an operator-stated number is a **product
   decision**, and every option writes a different column. Guessing here bakes the guess into a
   durable table.
2. **What is the default lateness horizon — and is there one?** The repo's standing posture is that a
   retention-shaped default is "never a default to invent" (the same reasoning that made the audit
   window an operator call). A horizon that is too short seals short numbers; too long and nothing
   ever finalises. This likely has no safe default and should be **required per table**, but that is
   the operator's call to state.

Until both are answered, P1–P3 are safe to build and P4–P6 are not.
