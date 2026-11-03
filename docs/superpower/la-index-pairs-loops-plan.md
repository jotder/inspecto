# LA-INDEX-PAIRS-LOOPS-1 — two-way pairs and short loops written by the link index build

> **PLAN 2026-11-03 — decisions Q-1…Q-5 accepted by the operator 2026-11-03 (the recommended options, §6). P0 BUILT 2026-11-03 (§8); P1, P2 open.** Operator ask: make the *Two-way pairs*
> and *Short loops* preset queries fast on large Datasets. Follows `LA-PRESET-QUERIES-1`
> ([`la-basic-discovery-plan.md`](la-basic-discovery-plan.md) §7.2), where *Reached by many*, *Reaching many* and
> *Newcomers* moved to the link index's `nodes` table. Board row `LA-INDEX-PAIRS-LOOPS-1`.

## 1. The problem, measured

On the Basic install over IMS (`mfs-demo` / `ims_links_dataset`, index v73: 39.4 M links, 12.35 M entities):

| Read | Today | Why |
|---|---|---|
| Two-way pairs over the Dataset | past the 10 s statement timeout | a self-join of every link |
| Two-way pairs over the index `out` table | 48 s | the same self-join; the index stores every link |
| Short loops (busy entities over 200 left out) | out of memory (950 MB) | a three-way self-join |

Measured on the IMS index with DuckDB, 950 MB memory and spilling to disk (`2026-11-03`):

| Step | Time | Result |
|---|---|---|
| Fold the links to distinct `(from, to)` pairs with a count | 12.6 s | 39.3 M pairs: **folding hardly shrinks IMS** (nearly every link is its own pair) |
| Two-way pairs from the folded pairs | 3.8 s | 2.79 M reciprocal pairs |
| Distinct counterparts per entity | 40.8 s | 12.34 M of 12.35 M entities have at most 50 |
| Short loops, entities over 50 counterparts left out | 16.1 s | 525 k loops |
| On disk | — | 434 MB of folded pairs (not kept, see §3) |

So a folded pair table alone does not help: the cost is the join, not the scan. **The answers themselves must be
written at build time.** The IMS full build runs nightly at 02:00 and published at 02:10 (about 10 minutes), so about
35 s more per build is affordable.

## 2. The goal in the analyst's words

"Show me the pairs that go back and forth, and the circles of three, on the big Datasets too - in a second, not a
timeout."

## 3. Design

**Two new index tables, written by a FULL build after `nodes`** (a new phase `derived`; as built it reports the unused step 5 of 6, §8), bucketed
like the others and listed in `manifest.tables` with their row counts:

- **`pairs_back`**: `(a, b, n_ab, n_ba)` with `a < b`: each pair that links both ways, once, with the links each way.
  Built from a folded `(src, dst, n)` over `out` (self-links and empty ends left out), joined to itself. The folded
  pairs are a build-time temporary, not kept.
- **`loops`**: `(x, y, z, n)` with `x < y` and `x < z`: each directed loop `x → y → z → x` once, `n` = the links along
  its three steps. Only entities with at most `index.loops_max_degree` distinct counterparts take part (Q-1, default
  50, a new `LaLimit` with the others under `index.*`); the cap is recorded in the manifest (`derived.loopsMaxDegree`)
  so a reader knows what the table holds (as built: the top-level manifest field `loopsMaxDegree`, §8).

**Reads.** `IndexReader.twoWay(min, limit)` and `IndexReader.loops(maxDegree, limit)` (fixed SQL, bound values, the
`rankNodes` pattern). `PresetRoutes.fromIndex` serves `two_way` and `short_loops` from them through `IndexedRead.select`,
as the three `nodes` presets are, with `source` saying so. A `short_loops` cap ABOVE the build's cap cannot be answered
from the table: the Dataset answers and `source.reason` says why (or, Q-5, the parameter is capped at the build's).
A cap below it is applied by joining the three ends to `nodes`.

**Kinds.** Both tables fold across kinds of link (the `nodes` link counts do not); a kinds filter is not in them, so it
reads the Dataset (`filter_not_indexed`), as today.

**Appends (Q-2).** A delta cannot update either table exactly (a new link can close a loop between old ones). An APPEND
recomputes both over the whole version (about 35 s on IMS); the alternative is to drop them on append, so the presets
read the Dataset until the next FULL build.

**Staleness and masking** are unchanged: `IndexedRead.select` refuses a stale index as for exploration, and every entity
column goes through `ExplorationMasking.out`.

**Also in this change: the timeout answer.** A preset that runs past the statement timeout answers 422
`preset query failed: INTERRUPT Error: Interrupted!` today. It becomes a plain sentence: *"This question took longer
than this Space allows (N s) on <Dataset>. Build its link index, or ask for fewer rows."* - the 422 stays.

## 4. Phases

| Phase | Scope | Size |
|---|---|---|
| **P0** | the `derived` phase behind `index.derived_tables` (default on), the two tables, manifest entries, `IndexBuilderTest` parity with a brute-force answer on a small fixture | M |
| **P1** | `IndexReader.twoWay` / `loops`, the two presets from the index, the APPEND rule (Q-2), the timeout sentence, real-HTTP test, docs | S |
| **P2** | rebuild the IMS index on the Basic install (02:00 job, or Run now) and measure: build time added, read times | XS |

## 5. Done means

On the Basic install over IMS: *Two-way pairs* and *Short loops* answer from the index in under 1 s; the nightly full
build takes at most 1 minute longer; a preset that times out says so in a sentence; tests prove the index tables equal a
brute-force answer (including an appended version) and that the route falls back to the Dataset when a table is absent.

## 6. Decisions (operator 2026-11-03: accepted as recommended)

- **Q-1** Loops cap `index.loops_max_degree` **50** (leaves out about 9 500 of 12.35 M IMS entities; 525 k loops).
  Alternative: 200 (more loops, not measured to fit memory). *Recommended: 50.*
- **Q-2** An APPEND **recomputes** both tables over the whole version (+35 s on IMS). Alternative: drop them on append
  and read the Dataset until the next FULL build. *Recommended: recompute.*
- **Q-3** Loops are ranked by **`n`, the links along the loop**, busiest first. Alternative: by the smallest entity id
  (today's order, stable but meaningless). *Recommended: `n`.*
- **Q-4** Loops stay **directed**: `x→y→z→x` and `x→z→y→x` are two loops, listed apart. Alternative: one undirected
  triangle per three entities. *Recommended: directed (a cycle of money or calls has a direction).*
- **Q-5** A `maxLinks` above the build's cap: **the Dataset answers** (and may time out). Alternative: cap the parameter
  at the build's cap and say so. *Recommended: the Dataset answers.*

## 7. Not in scope

- The other heavy presets (*Biggest senders by amount*, *Money in, then out again*) still read the Dataset.
- `ims_cdr_dataset`'s guessed roles (From = `served_msisdn`, To = `served_imsi`: two classified columns, not the called
  number) — a role-detection defect, its own row `LA-PRESET-ROLES-1`.

## 8. As built: P0 (2026-11-03)

- `IndexBuilder.Run.derived(root, n)` runs in every mode right before verify (FULL, APPEND over the merged stage, COMPACT),
  reading the version's own `out` and `nodes`: a temp fold `(s, t, n)` without self-links, then `COPY`s of
  `pairs_back(a, b, n_ab, n_ba)` and `loops(x, y, z, n)` bucketed by `a` / `x` (loops ordered by `n` desc inside a bucket,
  Q-3). `scan` lists them in `manifest.tables` when written; `IndexManifest.loopsMaxDegree` (new optional field) records
  the cap, absent when none were written.
- **Deviation, assistant-decided:** one setting instead of two - `index.loops_max_degree` (`LaLimit`, INSTALL, default 50)
  where `0` writes neither table, in place of a separate `index.derived_tables` switch. It reaches the builder as
  `IndexBuildService.Host.loopsMaxDegree` → `IndexBuilder.Options.loopsMaxDegree` (null = none).
- **Deviation, assistant-decided:** the loops cap measures an entity by its LINK ROWS in and out (`nodes.out_edges +
  in_edges`, a self-link on both sides), not distinct counterparts: `nodes` already holds it (recounting distinct
  counterparts took 40 s on IMS), and unlike the distinct-link counts it sums EXACTLY across an APPEND's delta rows - the
  first test run caught an appended version leaving out loops a full build kept, because summed distinct counts overstate.
  It leaves out more entities than "50 counterparts" would (a busy pair counts every link); P2 measures how many on IMS.
- Test: `IndexAppendTest.theDerivedTablesHoldExactlyTheTwoWayPairsAndTheQuietLoopsInFullAndAppendedBuilds` - both tables
  equal a plain-Java answer computed from the corpus files (not from the index), after a FULL build and after an APPEND; a
  build without the cap writes neither and records none.
- A table with no rows is an EMPTY directory (no parquet file): a P1 reader must check `manifest.tables.<t>.rows` before
  globbing it. COMPACT shares `derived()` with the tested FULL and APPEND paths but has no test of its own yet.
- P1 note: the cap counts link rows, while the Dataset path of *Short loops* counts distinct counterparts (`maxLinks`); Q-5
  compares a request's `maxLinks` with the build's cap, so P1 must say which measure the parameter means.
- Not yet: the reads (`IndexReader.twoWay` / `loops`), the presets from the index, the timeout sentence (P1), and the IMS
  measurement (P2).
