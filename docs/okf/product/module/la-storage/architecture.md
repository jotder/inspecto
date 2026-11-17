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

Of the Link Analysis modules la-storage depends on la-core only (it implements la-core's `GraphEngine`); beyond it only
the platform libraries `inspecto-sql` and `inspecto-analytics` and DuckDB — never la-api, the host or a Subject.
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
  `spellings: {type: {complete, keys: {key: [raw ids]}}}` for each Entity Type of an end column. One type at both ends
  is pooled over both columns, as `typeNodes` is. Raw ids are in VARCHAR order. The listing is **complete** — every key
  that some raw id *other than the key itself* spells — while that fits in `IndexManifest.MAX_SPELLINGS` (10 000 raw
  ids). On the full-cycle fixture (account is `upper-trim`) that is one key: `ACC-1003: [' acc-1003 ', ACC-1003]`.
  Past the cap it lists only the keys spelt more than once (`complete: false`); past the cap again, the type maps to
  `null`. The manifest is parsed on every indexed read, so the cap also bounds that. Additive: `formatVersion` stays 1
  and no index is rebuilt; an index built before this has no `spellings`, and a start on it matches the raw value
  exactly, as before, until its next build or append.
- **A start reads every spelling of its key** (`POST /inv/projection/neighbors`, the edge form). The end columns are
  typed by `IndexRoutes.entityTypeOf`, the rule the build used. The Dataset path matches a typed end by
  `(raw = value OR EntityKeySql.key(normaliser, col) = key(value))`. `IndexedNeighbors` folds the value itself plus
  each raw id the manifest lists for the key, one bucket lookup each, and unions them. Under a complete listing an
  unlisted key can only be spelt by itself, so it folds the key too: `acc-1018` reads ACC-1018, and a key nobody
  has answers empty from the index, quickly. An untyped end, or a value whose key is empty, matches the value
  exactly on both paths. The rows keep their raw ids, so under masking each spelling keeps its own alias; the start
  alias is resolved before the spellings are found and no raw id is returned. A masked caller's raw guess is widened
  by key too (`acc-1003` finds the account), but the answer holds aliases only, so no raw id and no spelling reaches them.
- **The SQL key equals the Java key, except for the newest case pairs.** `EntityKeySql` folds, before DuckDB's one-to-one `upper` / `lower`, every
  character whose full case mapping in `Locale.ROOT` differs from its simple one (102 for upper case — sharp s,
  ligatures such as `ﬁ`, Greek with ypogegrammeni, `ŉ`, `ǰ`, `և` — and `İ` for lower case), read from the JDK at class
  load. The shared `entity-normaliser-parity.fixture.json` holds Java, the browser and DuckDB to the same answers on
  those inputs. Before this, `ﬁx-1` keyed `ﬁX-1` in SQL and `FIX-1` in Java. The raw equality stays in the Dataset match,
  and the value stays in the index fold, so neither answer — nor the four-eyes fan-out count, taken with the same match —
  can fall below the exact match if the two keys ever drift again. ⚠ They do drift for about 55 letters per case rule
  (`default`, `upper-trim`; none for `digits`, `e164`): case pairs Unicode added after DuckDB 2.0.0.0's tables, such as
  `ɤ` / U+A7CB, `ƛ` / U+A7DC, U+1C89 / U+1C8A, U+A7CC–A7DB and Garay U+10D50–10D75 (an exhaustive probe of 2026-11-16).
  DuckDB leaves them as written while Java folds them, so a key spelt with one of them may miss its other spelling on
  the Dataset path; the raw equality above keeps every read at or above its exact match. Open as
  `LA-ENTITY-KEY-NEW-CASE-PAIRS-1`.
- **Cost.** The Dataset match calls regular expressions on the column, so a filter on that column no longer prunes
  Parquet files or row groups: a typed start that the Dataset answers reads the whole relation, as a neighbours read
  with the index off always did. The index reads at most `IndexedNeighbors.MAX_KEY_SPELLINGS` (64) raw ids per end of
  one start, one bucket statement each.
- **When the index cannot say which raw ids spell the key, the Dataset answers**, with the same rows and the reason on
  `source`:
  - an end typed under another rule than the build used: `index_stale_refused`, *rebuild it*. `GET /inv/index` does not
    show this: its staleness compares the data and the definition, not the Entity Types, so it still says the index is
    current. A rebuild clears it.
  - a type with too many spellings to list, or a key spelt more than 64 ways: `frontier_over_index_cap`.
  - under an incomplete listing only, a typed value that is no raw id of that end and is not listed (`acc-1018`):
    `rung_not_indexable`. This is checked only when the end's fold came back empty (`IndexReader.rows`, one more
    bucket lookup). On a two-type mapping (say msisdn to imei) every start under an incomplete listing reaches this check
    on its other end, and so reads the Dataset. A complete listing never needs it.
- **The import's entity count** (`ScheduledIndexBuild.done`, the `index.nodes` of Add data, Add a file and Run now) is
  the sum of `typeNodes` when both end columns are typed, so `' acc-1003 '` and `ACC-1003` are one entity (137 on the
  full-cycle fixture, not 138). An untyped or half-typed mapping keeps the distinct raw ids. `typeNodes` leaves out a
  key whose only links are self-links. `GET /inv/index` still reports the `nodes` table's rows beside `typeNodes`.
- **Not widened** (still exact raw match): the Investigation `expand` (sealed reads; LA-17 `merged: true` is the opt-in
  that reads through identity groups), the neighbour list (`list: true`), the Grow preview
  (`/inv/projection/neighbors/preview`), recursive paths and graph runs.

Pinned by `ControlApiOnboardLinkAnalysisTest` (`aStartFromATypedAccountReachesEverySpellingOfItsKeyFromTheIndexAndTheDataset`,
`aMaskedStartReachesEverySpellingAndRevealsNone`, `theFourEyesFanOutGateCountsTheWidenedMatchAndNeverLessThanTheExactOne`,
`whenTheManifestCannotSayWhichRawIdsSpellAKeyTheDatasetAnswersAndSaysWhy`, the 137 in the full-cycle test),
`IndexLinkStatsTest` and the parity fixture (`EntityKeySqlTest`, `EntityTypesTest`, `entity-key.spec.ts`).

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
