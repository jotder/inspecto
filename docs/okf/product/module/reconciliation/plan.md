---
type: Capability
title: Reconciliation — requirement of record
description: What was required of Reconciliation (2- and 3-way Dataset comparison with a Break lifecycle), what is built, what is left and what was refused.
resource: features/inspecto-reconciliation/
tags: [reconciliation, plan, requirement, breaks]
timestamp: 2026-10-19T00:00:00Z
---

# Reconciliation — requirement of record

## Required

Compare two or three Datasets at a key-column grain and report the differences as **Breaks** — only in A, only in
B, common but different — with a tree-table Board, drill to the record sets, a server-side Break lifecycle,
scheduling, and promotion of a Break to an Incident
([reconciliation-board-design](../../../../archived-documents/plans-archive/reconciliation-board-design.md),
2026-07-14, provenance). It must stay usable at production volume
([reconciliation-uplift-plan](../../../../archived-documents/plans-archive/reconciliation-uplift-plan.md)).

## Built

- The Board, N-way compare and Break lifecycle (`open | assigned | resolved | auto_closed`), the `recon.run` Job
  Type and `POST /recon/promote` ([architecture](architecture-overview.md); [spec](spec.md);
  [user guide](user-guide-reconciliation.md)).
- `RECON-CARDINALITY-1` tier 1 (2026-09-12): asserted cardinality; a violation is a `cardinality_break`
  (decided 2026-09-13, [recon-cardinality-plan § 7](../../../../archived-documents/plans-archive/recon-cardinality-plan.md)).
- `RECON-3WAY-RECORDED-BREAKS-1` (closed the day it was filed): a 3-way Reconciliation records its A-vs-C
  Breaks, pair-tagged ([BACKLOG](../../../../BACKLOG.md)).
- `RECON-PERF-1` / `RECON-PERF-RESIDUALS-1` (closed 2026-10-10): one day at a time, paging, the day cache,
  90-day state retention ([BACKLOG](../../../../BACKLOG.md)).
- `RECON-UPLIFT-1` (2026-10-18): slices B0/B1 and U1–U6, then the review amendments (per-type scans when a key
  is DATE on one side and TIMESTAMP on the other, a byte-bounded day cache, Schedule from the Board)
  ([reconciliation-uplift-plan](../../../../archived-documents/plans-archive/reconciliation-uplift-plan.md)).
- `RECON-UPLIFT-RESIDUALS-1` items (1), (2), (4b), (4c), (5b), (6) built 2026-10-18 ([BACKLOG](../../../../BACKLOG.md)).

## Left

P3 `RECON-UPLIFT-RESIDUALS-1` ([BACKLOG](../../../../BACKLOG.md)), each pickable alone:

- (3) *Test definition* sends no day, so the server reads the latest day on any side; a daily side against a
  monthly side then reads every key missing. Kept: the day policy is deliberate (decided 2026-10-18).
- (4a) *Open breaks by age* reads the last recorded run, not the selected day; needs per-day recorded state.
- (5a) `ReconDay.Cache.weigh` is an estimate; measure it against a heap histogram on a 200k-row 3-way day.

`RECON-CARDINALITY-1` tier 2 (true one-to-many matching) stays demand-gated
([recon-cardinality-plan](../../../../archived-documents/plans-archive/recon-cardinality-plan.md)).
Behaviour above about 400k rows per day was measured only by the B0 harness (`ReconPerfTest`, `RECON_PERF=1`).

## Refused

- A whole-period fallback for an undated side: a side whose Dataset has no temporal column is refused 422
  (operator, 2026-10-09; `ReconDay`).
- Uplift slices B2 and B3, closed unbuilt: B0 found no failure at 10M key groups and no deep-offset cost
  ([reconciliation-uplift-plan](../../../../archived-documents/plans-archive/reconciliation-uplift-plan.md)).
- Truncating a run over `MAX_BREAKS`: it is refused, never truncated ([spec](spec.md)).
