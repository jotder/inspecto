---
type: Concept
title: Link analysis storage - architecture
description: How the link index is built, published, read and routed to, and how it plugs into Link Analysis.
resource: la/inspecto-la-storage/
tags: [module, la-storage, link-analysis, index, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis storage - architecture

Full as-built detail: [la-core architecture](../la-core/architecture.md) §4. Layer overview:
[layer/la architecture](../../layer/la/architecture.md). Class reference: [spec](spec.md).

## Where it sits

```
 la-api ──IndexRoutes / ScheduledIndexBuild──> IndexBuildService ──> IndexBuilder ──> IndexStore (versions on disk)
   │                                                                                       ▲
   └──IndexedRead gate──> IndexReader / IndexedTraversal ──────────────────────────────────┘
   └──GraphRunServices──> RoutingGraphEngine ──IndexRef──> SqlGraphEngine ──> IndexReader
                                         └──Materialised──> la-core InMemoryGraphEngine
```

la-storage depends on la-core only (it implements la-core's `GraphEngine`), never on la-api, the host or a Subject.
Gates, staleness decisions and the choice of flat versus index belong to la-api. It ships in every edition
(`offerings/basic.toon`).

## Build flow

1. A build request (an analyst via `POST /inv/index/builds`, or the `la.index.build` Job through geo-link) names a
   Dataset and an `IndexMapping`.
2. `IndexPlan` classifies the request against the current manifest and today's input files: `full`, `append` or
   `compact`, with a closed reason when only `full` is sound.
3. `IndexBuildService` refuses a duplicate, estimates disk against the budget, queues the build, and holds it in
   `waiting_for_ingest` while ingest runs.
4. `IndexBuilder` writes the stage with its own DuckDB connection, verifies, writes the manifest, and `IndexStore`
   publishes it atomically. `gc` keeps the live version plus `index.keep_versions`, skips pinned and young versions,
   and removes crashed stages.

### Derived tables: two-way pairs and short loops (`LA-INDEX-PAIRS-LOOPS-1`, 2026-11-03)

- Every build (full, append, compact) ends with a `derived` step, before verify, that rewrites `pairs_back(a, b, n_ab,
  n_ba)` (`a < b`) and `loops(x, y, z, n)` (`x` the smallest id, directed, `n` = links along the loop) from the whole
  version, deltas included. Loops keep only entities with at most `index.loops_max_degree` link rows in and out
  (INSTALL, default 50; `0` writes neither). That cap counts `nodes` edges, which sum exactly across deltas; summed
  distinct links only bound from above and lost loops after an append. Self-links and `''` ids are left out. A table
  with no rows is an empty directory, and the manifest records the cap as `loopsMaxDegree`.
- `IndexPlan` treats a version whose `loopsMaxDegree` differs from the cap in force as not up to date, even when no
  input changed. With no new file it recommends `full` (`derived_tables_outdated`); an append or a compaction rewrites
  the tables anyway. Without this, an upgraded install or a changed cap kept the old tables until the data changed.
- *Two-way pairs* and *Short loops* (preset queries, `PresetRoutes`) read those tables through `IndexedRead`. When
  `maxLinks` is above the build's cap, the Dataset answers and its `source.details` gives the reason. A preset that
  the statement timeout interrupts answers 422 in a sentence, naming the index's reason when the index exists but
  cannot answer.
- Measured on IMS (39.4 M links): the full build took 289 s (`pairs_back` 2.79 M rows, `loops` 524 k rows, about 30 s
  of that). Reads take 0.18–0.35 s; before, the Dataset path timed out at 10 s or ran out of memory. A cap below the
  build's is applied through `nodes` and takes about 3.8 s.

## Read flow

- `IndexReader` opens one sealed connection per request whose `allowed_directories` is the version directory, so a read
  can see that version and nothing else. Each key is one equality statement with its bucket as a literal; a multi-key
  frontier is a `UNION ALL`.
- `IndexedTraversal` and `SqlGraphEngine` walk level by level under `index.traversal_depth` and `index.frontier_cap`;
  a wider frontier throws, and the caller answers from the flat path instead, never mixing the two.

## Design choices

- **Both directions stored** (`out/` and `in/`): data doubles, but a reverse lookup is as cheap as a forward one.
- **Bucket computed in Java**: DuckDB cannot derive a partition from a key equality, so without the literal the layout
  would not prune.
- **Versions, not in-place updates**: readers holding an older version keep a consistent view; Draft pins
  (`IndexPins`) keep that version for 30 days.

## Measured scale

At 10^8 edges a one-key lookup is about 21 ms p50 and a full build about 7.5 minutes with an 8 GB cap on a laptop; a
20-key frontier costs about 0.4 s. Method and numbers: [tests-index-scale-run](tests-index-scale-run.md) and
`LA-INDEX-SCALE-MEASURE-1` in [`docs/BACKLOG.md`](../../../../BACKLOG.md).
