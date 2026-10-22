---
type: Feature
title: "Graph algorithms — plan"
description: "Requirement of record for the platform graph library: why it exists, what is built, what is left and what was refused."
tags: [graph, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Graph algorithms — plan

## Required

- **One graph library for every module** — decision **D-AN2** of `ANALYTICS-CORE-1`
  ([`analytics-core-plan.md`](../../../../archived-documents/plans-archive/analytics-core-plan.md), [BACKLOG](../../../../BACKLOG.md)):
  move the Link Analysis graph code to `platform/inspecto-graph`, Java only, so AML, Fraud and Case
  Management can run graph algorithms without depending on Link Analysis. The TypeScript twins and the
  parity fixtures stay with the Link Analysis library.
- **Server and browser agree** — each ported algorithm must give the same result as the SPA's
  `graph-analysis.ts` on the same fixture (parity tests, see [tests](tests-inventory.md)).
- **Bounded runs** — a run can be cancelled, given a deadline or a work budget, and never returns a
  partial ranking (`RunControl` / `GraphAborted`).

## Built

- The move (D-AN2) and the algorithm set listed in [architecture](architecture-overview.md) §4 —
  built 2026-10-17 with the `ANALYTICS-CORE-1` Must items M1–M10.
- **S6 temporal motif matcher** (`TemporalMotifMatcher`) — Should batch 2 of `ANALYTICS-CORE-1`, built
  2026-10-17.
- All 29 catalogue algorithms are reachable from the Link Analysis toolbox (`LA-ALGO-UI-PARITY-1`,
  closed 2026-10-13; see the [BACKLOG](../../../../BACKLOG.md) change log).

## Left

- Nothing open is owned by this module. Open `ANALYTICS-CORE-1` items (Could C7, scratch-name sites)
  are outside the graph library.

## Refused or deferred

- A Web Worker for the browser algorithms (`LA-07`, closed 2026-09-23) and viewport culling (`LA-06`)
  — Link Analysis standing refusals in [BACKLOG](../../../../BACKLOG.md) §6. They concern the SPA twins,
  not this library.
- Cost ceilings, budgets and the algorithm catalogue deliberately stay in Link Analysis
  (`GraphBudget`, `Algorithm`); this module keeps only the algorithms (D-AN2).
