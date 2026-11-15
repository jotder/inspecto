---
type: Plan
title: Link analysis storage - plan of record
description: What the link index module is required to do, what is built, what is left and what was refused.
resource: la/inspecto-la-storage/
tags: [module, la-storage, link-analysis, index, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis storage - plan of record

`la-storage` is the versioned Parquet **link index** and the index-backed graph engine. Design of record:
[la-separation-d3-design](../../../../archived-documents/plans-archive/la-separation-d3-design.md) (D-3). As built:
[architecture](architecture.md) and [la-core architecture](../la-core/architecture.md) §4.

## Required

- Answer one-hop and short multi-hop reads over 10^8+ edges without materialising the graph: an edge index stored in
  both directions, bucketed so a key lookup prunes to one partition (D-3).
- Publish versions atomically; a reader sees a whole old or new version, never a partial one.
- Incremental `append` only when provably sound; anything else is a `full` rebuild (D-3 step 8).
- Never decide for the caller: index reads are fenced by caps and refuse; they never reroute to the flat path inside
  this module (`SqlGraphEngine`, `IndexedTraversal`).
- Keep a version a Draft read alive while the Draft lives (D-7 step D7-2,
  [la-separation-d7-design](../../../../archived-documents/plans-archive/la-separation-d7-design.md)).

## Built

| Item | Classes | Record |
|---|---|---|
| Full / append / compact builds, manifest, atomic publish, gc | `IndexBuilder`, `IndexStore`, `IndexManifest`, `IndexMapping`, `IndexPlan` | D-3 |
| One MD5 bucket definition in Java and SQL | `BucketFunction` (Analytics Kernel `Md5Bucket`) | D-3 Decision 3, amended 2026-10-02 |
| Build lifecycle service: one live build per (Dataset, mapping), disk budget, waits while ingest runs | `IndexBuildService` | D-3 step 4; `LA-CONFIG-LIMITS-1` CL-12 |
| Sealed per-request reader | `IndexReader` | D-3 step 5 |
| Indexed traversal and graph engine, routing by input type | `IndexedTraversal`, `SqlGraphEngine`, `RoutingGraphEngine` | D-3 steps 5 and 7 |
| Draft pins | `IndexPins` | D-7 step D7-2 |
| Configurable build memory, bucket sizing, pools | `IndexBuildService.Host`, `retune` | [la-configurable-limits-plan](../../../../archived-documents/plans-archive/la-configurable-limits-plan.md) |
| Scale measured to 10^8 edges | `IndexScaleBench` (test, gated) | `LA-INDEX-SCALE-MEASURE-1` clauses (1)-(2) |

## Left

Open rows in [`docs/BACKLOG.md`](../../../../BACKLOG.md):

- `LA-INDEX-SCALE-MEASURE-1` (P3) clause (3): a build at 10^9 edges (stopped on a laptop 2026-10-13); 10^9 is
  extrapolation today. Measurements: [tests-index-scale-run](tests-index-scale-run.md).
- `LA-DAILY-INGEST-1` (P2): scheduled index builds on daily partitions, follow-ups.
- Per-key lookup cost is linear in the key's degree; the frontier cap bounds key count, not hub cost
  ([la-core architecture](../la-core/architecture.md) §10).

## Refused

- **A persisted `.duckdb` file**: Parquet is the only persisted form.
- **Routing by size or as a fallback**: `RoutingGraphEngine` routes on input type only.
- **Baking dimension data into the index** (D-ING6): it is joined at read time, so a view-backed Dataset can be served
  under an age gate instead.
- **Appending when the input files cannot be listed**: "cannot know" is never "only added" (`IndexPlan`).
