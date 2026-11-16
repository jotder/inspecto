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
   `waiting_for_ingest` while ingest runs. The worker runs the build under the submitter's logging context (its Space,
   `MDC`) and clears it after: the relation rendered again on the worker reads the Space's Consignment registry, and
   without the Space it read a glob where the Space reads a pinned list, so every index outside the default Space was
   out of date at once (`LA-FULL-CYCLE-TEST-1` phase 4; [la-core architecture](../la-core/architecture.md) §4).
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

### Spellings of one key (`LA-INDEX-SPELLINGS-1`, 2026-11-16)

A node is the raw column value everywhere — the buckets, the `nodes` table, Investigations, Cases, Drafts, aliases — and
stays so. What changed is how one start is read, and what the import counts (operator decision 2026-11-16: options b
and c; keying nodes by the type's rule, option a, was not taken).

- **The manifest records the spellings.** In `linkStats` (every mode, over the whole version) the build writes
  `spellings: {type: {key: [raw ids]}}`: for each Entity Type of an end column, every key that more than one raw id
  spells, with those ids in VARCHAR order. One type at both ends is pooled over both columns, as `typeNodes` is. A key
  spelt once is not listed, so the entry is small on clean data. Above `IndexManifest.MAX_SPELLINGS` (10 000 raw ids) a
  type maps to `null`. Additive: `formatVersion` stays 1 and no index is rebuilt; an index built before this has no
  `spellings` and a start on it matches the raw value exactly, as before, until its next build or append.
- **A start reads every spelling of its key** (`POST /inv/projection/neighbors`, the edge form). The end columns are
  typed by `IndexRoutes.entityTypeOf`, the rule the build used. The Dataset path matches a typed end by
  `EntityKeySql.key(normaliser, col) = key(value)`. `IndexedNeighbors` folds each raw id the manifest lists for the
  key, one bucket lookup each, and unions them. An untyped end, or a value whose key is empty, matches the value
  exactly on both paths. The rows keep their raw ids, so under masking each spelling keeps its own alias; the start
  alias is resolved before the spellings are found and no raw id is returned.
- **When the index cannot say which raw ids spell the key, the Dataset answers.** An end typed under another rule than
  the build used (`index_stale_refused`, *rebuild it*); a type with too many spellings to record; or a typed value that
  is no raw id of that end and is not listed (`acc-1018` when only `ACC-1018` exists: `rung_not_indexable`). The last
  is checked only when the end's fold came back empty (`IndexReader.rows`, one more bucket lookup).
- **The import's entity count** (`ScheduledIndexBuild.done`, the `index.nodes` of Add data, Add a file and Run now) is
  the sum of `typeNodes` when both end columns are typed, so `' acc-1003 '` and `ACC-1003` are one entity (137 on the
  full-cycle fixture, not 138). An untyped or half-typed mapping keeps the distinct raw ids. `typeNodes` leaves out a
  key whose only links are self-links. `GET /inv/index` still reports the `nodes` table's rows beside `typeNodes`.
- **Not widened** (still exact raw match): the Investigation `expand` (sealed reads; LA-17 `merged: true` is the opt-in
  that reads through identity groups), the neighbour list (`list: true`), the Grow preview
  (`/inv/projection/neighbors/preview`), recursive paths and graph runs.

Pinned by `ControlApiOnboardLinkAnalysisTest` (`aStartFromATypedAccountReachesEverySpellingOfItsKeyFromTheIndexAndTheDataset`,
`aMaskedStartReachesEverySpellingAndRevealsNone`, the 137 in the full-cycle test) and `IndexLinkStatsTest`.

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
