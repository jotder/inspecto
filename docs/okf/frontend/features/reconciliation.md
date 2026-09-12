---
type: Feature
title: Reconciliation
description: Dataset-vs-Dataset (and 3-way) reconciliation — Board aggregate tree with banded Δ%, Breaks drill with live record sets, usable as a Widget.
resource: inspecto-ui/src/app/modules/admin/reconciliation/
tags: [feature, reconciliation, breaks, board, tree-table, dat-7]
timestamp: 2026-07-16T00:00:00Z
---

# Reconciliation

Route `/reconciliation` (Business + Builder lenses). Vocabulary is locked
([`GLOSSARY.md`](../../../GLOSSARY.md) §7): a **Reconciliation** compares **Datasets** on key columns
with per-column tolerances; a **Break** is
`missing_left | missing_right | value_break | cardinality_break` with an `open/resolved/auto_closed`
lifecycle (auto-close on re-match within tolerance). Never a parallel "comparison" concept.

**Cardinality is an ASSERTION, not a matching strategy** (`RECON-CARDINALITY-1` tier 1, 2026-09-12). A
Reconciliation may declare `cardinality: one_to_one | one_to_many | many_to_one | many_to_many`; a
violation on a **matched** key becomes a `cardinality_break` carrying the per-side row count as its
evidence (in `leftValue`/`rightValue`).

⚠ **Default `many_to_many` asserts nothing**, and the server omits the key entirely unless a cardinality
is declared — so a Reconciliation authored before the option gets a byte-identical payload.

🔴 **Why it is an assertion and not a matcher.** Each side is pre-aggregated to one row per key *before*
the join, so for the canonical case — one invoice against three payments — summing the payments and
comparing to the invoice is **already the right arithmetic**. The defect was narrower and is a
*correctness* hole: a **duplicated** row was indistinguishable from a genuinely larger value, so it
reconciled clean. The multiplicity was in fact already computed (`COUNT(*) AS mr`) and carried through the
join, then discarded as a presence boolean — this reads the number that was always there, with no new SQL
and no change to the join.

⚠ A `cardinality_break` is one per **key**, unlike `value_break` which is one per (key × column): a key
has one cardinality, not one per compare column. ⛔ Row-level pairing — *which* of the N counterparts
matched — is deliberately NOT built: the Incident attrs (`reconciliation`/`breakKey`/`breakType`/`column`/
`runId`) cannot express it, and its Break shape cannot be designed without a named workflow.

🔴 **Client seam, because the obvious one is dead.** `aggregateRecon`/`reconBreakSets` in
`recon-board.ts` are an offline mirror of the backend with **no caller since the mock backend was removed
(2026-08-31)**; they survive only as a parity mirror and are the most test-covered recon code in the SPA.
The live path is `/recon/breaks` → **`breaksFromSets`** → the Break lifecycle. Wiring a new break type
into the mirror would turn specs green and change nothing in the running app.

**Aging** (`BREAK-AGING-1`, 2026-09-11). A Break carries `firstSeenAt`, stamped when it is first observed
and **carried forward by `mergeBreaks` on every later run**. Age is derived from it and rolled up by
`openAgeBuckets` into **0–30 / 30–60 / 60–90 / 90+** days, rendered as a chip strip on the Board and on the
Breaks page, plus an `Age` column on the Breaks grid. Before this a Break had a status but no time at all,
so "how long has this been broken" was unanswerable.

Four rules that are easy to get wrong and are each pinned by a test:
* 🔴 **A fresh break arrives from the engine with NO stamp on every run**, so `mergeBreaks` must carry the
  previous one rather than re-stamp. Re-stamping resets every age to zero each run and the view then
  permanently reads *"everything is new"* — a plausible-looking display that is always wrong.
* ⛔ **A missing stamp is `unknown`, never 0.** A Break persisted before this field existed has no first
  sighting; reporting it as fresh makes the oldest untracked breaks look newest. `breakAgeDays` returns
  `null` and the UI shows an em-dash.
* **Buckets are upper-exclusive**, so day 30 is `30-60` and lands in exactly one bucket.
* **Aging counts OPEN breaks only.** Including resolved or auto-closed ones would make the backlog look
  *older* the more of it you cleared.

⚠ The rollup lives in `reconciliation-types.ts`, not in either component: two panes deriving the same
histogram is how one concept ends up with two drifting definitions, and the "open only" rule is exactly
what drifts first. ⚠ The Board writes ONE instant to both `mergeBreaks(…, runAt)` and `lastRunAt`.

* **Board** (`:id` default view) — the aggregate dimension-order tree on
  [`inspecto-tree-table`](../design-system/tree-table.md): unified dimension/measure selection, parents
  carry rollups, Δ% columns **banded** ok/warn/breach (defaults 1/2 %; independent of record-level
  tolerance). Δ% is **Anchor-relative** (`datasets[0]`; 0-anchor ⇒ exact).
* **Breaks page** — drill from a Board cell to the three live record sets (only-in-A / only-in-B /
  common-but-different) with path scoping; 3-way adds a **Presence Pattern** filter bar.
* **3-way** — N=3 anchor reconciliation shipped (DAT-7 P4); N>3, non-additive aggs, and fuzzy key
  matching are explicit non-goals for now.
* **Reusable** — a Reconciliation renders as a **Widget** and rides bundle export/template flows
  (DAT-7 P3); persisted as the `reconciliation` component kind with real backend routes
  `/recon/columns|run|breaks` (DAT-7 P0 — the query gate pattern from the Data Browser).
* Runs are manual from the Board (no auto-refresh), **or scheduled**: the `recon.run` built-in Job Type
  (`ReconRunJob`, 2026-07-18) runs a saved `reconciliation` on a `cron:` and emits a `recon.run.completed`
  Signal carrying the Break counts (`WARNING` when any break exists) — it builds the identical
  `ReconService.Spec` the interactive route does, via the shared `ReconConfigLoader`. **A breach
  (`breaks > 0`) also opens a managed `ObjectType.INCIDENT`** (2026-07-19), deduped to one open Incident
  per reconciliation (correlationId = the reconciliation id), reusing the `ExpectationRoutes`
  dedup+open pattern; `ObjectService` reaches `ReconRunJob` via a `Supplier` `JobService` wires
  post-construction (`JobService.objects(...)`, resolved lazily since the built-in is constructed
  before the Object Engine exists). Break-level assignment stays with Cases.

As-built design (archived):
[`reconciliation-board-design.md`](../../../archived-documents/plans-archive/reconciliation-board-design.md) ·
review sheet: [`reviews/reconciliation.md`](../../../archived-documents/superpower-reviews/reconciliation.md).
