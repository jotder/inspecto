---
type: Plan
title: Link analysis core and ports - plan of record
description: What la-core is required to do, what is built, what is left and what was refused.
resource: la/inspecto-la-core/
tags: [module, la-core, link-analysis, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis core and ports - plan of record

`la-core` is the host-free domain of Link Analysis: the Investigation op log and its evaluator, the
`InvestigationStore` port with the filesystem store, Drafts storage, Graph Run, and the ports the bridge implements.
Design: [architecture](architecture.md) §3 and §5. Layer overview: [layer/la architecture](../../layer/la/architecture.md).

## Required

- An **append-only, hash-sealed Investigation op log** folded deterministically into a Working Set
  ([architecture](architecture.md) §3.1).
- A **storage port** so Investigations can live on a filesystem or on PostgreSQL for multi-pod deployments: id-keyed,
  precondition-carrying, never falling back between backends (`LA-INVESTIGATION-STORE-DESIGN-1`, design
  [investigation-store-design](../../../../archived-documents/plans-archive/investigation-store-design.md), decisions D-IS1..D-IS12).
- **Drafts** (private forks of an Investigation) with hibernate / expire / rebase / promote (design
  [la-separation-d7-design](../../../../archived-documents/plans-archive/la-separation-d7-design.md)).
- **Ports pointing inward**: la-core names no index class, no engine class and no route; the host and the bridge reach it
  through ServiceLoader ([la-separation-execution-plan](../../../../archived-documents/plans-archive/la-separation-execution-plan.md)).
- **Graph Run**: a bounded in-process pool of graph algorithms over a Working Set or the link index, with budgets that
  refuse rather than silently cap ([architecture](architecture.md) §5).

## Built

| Item | Classes | Record |
|---|---|---|
| Op evaluator, op log, Working Set fold | `InvestigationEvaluator`, `InvestigationTime`, `LinkEventTypes` | [architecture](architecture.md) §3.1 |
| Store port + FS store (S1), `-Dinvestigations.backend` selection seam (S6), OS file lock against cross-process races (S7) | `InvestigationStore`, `FsInvestigationStore`, `InvestigationStores` | `LA-INVESTIGATION-STORE-DESIGN-1`, design §13 |
| Per-set size limit (64 MiB) and per-Investigation set budget (4 GiB), both refused 413 | `WorkingSetSizeLimit`, `InvestigationSetBudget` | same row, done 2026-10-10 |
| Drafts storage, lifecycle and checkpoints | `DraftStore`, `DraftLifecycle`, `DraftCheckpoints`, `DraftIndex` | [la-separation-d7-design](../../../../archived-documents/plans-archive/la-separation-d7-design.md) |
| Graph Run service, 29 algorithms, budgets | `GraphRunService`, `Algorithm`, `GraphBudget`, `InMemoryGraphEngine` | [architecture](architecture.md) §5 |
| Ports for the host bridge | `DatasetProvider`, `CasePort`, `CollectorCoveragePort`, `OutboundPort`, `UploadLandingPort` | implemented in [geo-link](../geo-link/index.md) |
| `threshold` and `snapshot` ops, `threshold` `measure` | evaluator | `LA-INVESTIGATION-OPS-DEFERRED-1` (partly built) |
| Every limit a configuration item, passed in by la-api | — | [la-configurable-limits-plan](../../../../archived-documents/plans-archive/la-configurable-limits-plan.md) |

## Left

Open rows in [`docs/BACKLOG.md`](../../../../BACKLOG.md):

- `LA-INVESTIGATION-STORE-DESIGN-1` (P3): the operator-run precondition mutation checks; content-addressed sets
  (D-IS12 (b)) stay demand-gated.
- `LA-INVESTIGATION-OPS-DEFERRED-1` (P3): the unbuilt half of the Investigation model.
- `FsInvestigationStore` is self-declared TRANSITIONAL (path-keyed Draft code), and the legacy `SnapshotStore` still
  exists ([architecture](architecture.md) §10).

## Refused

- **Falling back from the database store to the filesystem** when the database is unusable: a fall-back on one pod
  forks the evidence (D-IS8, `InvestigationStores`).
- **`snapshot` sealing a `/inv/snapshots` artifact** and a **re-evaluable `threshold`**, both declined 2026-10-05
  (`LA-INVESTIGATION-OPS-DEFERRED-1`).
- **A delta set format to make Draft rebase linear**, closed as a decision (`LA-DRAFT-REBASE-COST-1`, BACKLOG change
  log 2026-10-04): the cost is the sealed Working Set hash, not the file format.
