# LA-BASIC-DISCOVERY-1 — discovery, an entity summary and preset queries in Basic

> **PLAN 2026-11-03 — decisions Q-1…Q-5 accepted by the operator 2026-11-03 (the recommended options, §6; presets first). P0 + P3 BUILT 2026-11-03 (§7); P1, P2, P4 open.** Operator ask: "entity profiling and some kind of
> discovery in Basic also; the user should be able to query the entity relationship data; some preset queries would be
> nice." Product hub `docs/okf/product/module/la-core/`. Related: `la-data-arrival-plan.md` (the Data page, Mappings),
> `docs/EDITIONS.md` (D-LAE4: the Entity profile **pages** are Standard and up). Board rows: `LA-BASIC-DISCOVER-1`,
> `LA-ENTITY-SUMMARY-1`, `LA-PRESET-QUERIES-1`, `LA-QUERY-BUILDER-1`.

## 1. The goal in the analyst's words

Before and beside drawing a graph, in Basic:

1. **What is in this Dataset?** How many entities of each kind, which are busiest, which kinds of link and how many,
   the period covered, amount totals.
2. **Tell me about this one entity.** First and last seen, who it links with most (by links and by amount), its kinds of
   link, its activity over time - and *Draw it*.
3. **Ask the relationship data a question** without writing SQL: "who sent the most to X last week", "numbers sharing
   a device", answered as a table I can sort and **draw**.
4. **Start from good questions** my industry already asks: presets with a few parameters.

## 2. What exists (research 2026-11-03)

| Need | Built | Edition |
|---|---|---|
| Entity Type list, resolve, suggest | `EntityProfileRoutes` (`la/inspecto-la-api`) :48-54 | open (`geoLink`) |
| Full profile pages (type + entity) | `EntityPageRoutes` -> `EntityProfileRoutes.typeProfile / entityProfile` | **Standard+** (`entityPages`, D-LAE4) |
| Profile computation | `EntityProfiles` (package-private, no HTTP): sections bindings, volume, shapes, time, lineage, links, relationships; per-entity link stats through `LINK_SOURCES` = `IndexLinkStats` + `DatasetLinkStats`; masking via `maskedUnder(mode, type)` (masked keys leave as `masked:<16 hex>`, raw keys go through `/resolve`); every call audited | reusable |
| Graph reads | `POST /inv/projection`, `/projection/neighbors`, `/projection/multi`; `POST /inv/pattern/branching`, `/inv/pattern/temporal` (server side, index-aware) | open |
| Aggregate query | `POST /bi/query` `{dataset, measures[{agg, field}], groupBy[], filters[], orderBy, limit}` via `MeasureCompiler` (validated identifiers, typed parameters), masking `BiResultMasking`, row cap `ListLimitSettings` | gate **to confirm** (V1) |
| Dataset rows + column statistics | `GET /datasets/{id}/rows` (cardinality, rowCount), `GET /db/table` | open |
| Pattern packs | stage motifs (fan-out / fan-in, windows, thresholds) in Space templates `config/registry/pattern-packs/*.toon`; named by the industry pack's `pattern_packs[]` | open |
| Industry packs | `META-INF/inspecto/industry-packs/{telecom,mobile-money}/`: entity types, link kinds, terms, `profile{measure_hints, suggested_tools, …}` | every edition |
| Preset queries | **none** (BiTemplates are dashboard templates; saved queries exist at `/queries/{id}/run`) | - |

## 3. Proposed design

**P1 Discover** (per Dataset; the Data page's *Discover* tab and the Start box's *About this Dataset*):
`GET /inv/datasets/{id}/discover` (la-api, `geoLink` only) built from `EntityProfiles` blocks over THIS Dataset's
mapping (the Dataset's or a Mapping's `defaultLinkMapping` + column classifications): entities per Entity Type, top 10
busiest per role (From / To), link kinds with counts, time range, amount sum / median when an *Amount* is mapped. Masked
types answer aliases and counts only. Cached per Dataset + last arrival (the arrival ledger's newest consignment).

**P2 Entity summary** (the *lite* profile; Basic and up): `GET /inv/entities/{type}/{key}/summary?dataset=` (la-api,
`geoLink` only) = `EntityProfiles.entityProfile` restricted to: first / last seen, counterparts ranked by links and by
amount (top 20), link kinds, activity by day (last 90 days), Entity List membership. The full page (lineage,
relationships across Datasets, Case history, Investigations) stays Standard (D-LAE4 unchanged). UI: the node detail
dialog gets *Summary*; Discover's busiest rows open it.

**P3 Preset queries:** a pack carries `preset_queries[]` in `industry-pack.toon` (new key, parsed by `IndustryPack`):
`{id, label, description, roles: {from, to, kind?, time?, amount?}, kind: aggregate | temporal, params[{name, type, default}], …}`.
`aggregate` presets compile to a `MeasureCompiler` query over the Dataset's roles (never free SQL): e.g. *Shared devices*
(group by device, count distinct numbers >= n), *Fan-in* (group by To, count distinct From >= n in window),
*Top counterparts of X*. `temporal` presets run through the existing `POST /inv/pattern/temporal` (e.g. *cash-in then
cash-out within N minutes*). Routes: `GET /inv/presets?dataset=` (the active pack's presets whose roles the Dataset maps),
`POST /inv/presets/{id}/run {dataset, params}` -> `{columns, rows, truncated}` with masking and the Space row cap.
First set: Telecom - *numbers sharing a device*, *SIMs seen in many handsets*, *most-called numbers of X*; Mobile money -
*cash-in then cash-out within N minutes*, *many wallets paying one*, *top counterparts of a wallet*.

**P4 Query builder:** a form over the Dataset's roles (From is / To is / Kind of link is / When between / Amount over /
group by From or To / count or sum / top N) that builds the same `MeasureCompiler` query; results table with **Draw
these** (start a graph from the listed entities, the existing *Start from these* path) and **Save as preset** (a
Space-level preset, stored as a component, `preset-query/<id>`).

Every answer is read-only, masked as exploration is, capped by the Space limits, and audited like profile reads.

## 4. Phases

| Phase | Scope | Size |
|---|---|---|
| **P0 verify** | V1 `/bi/query` open on Basic (and its masking for a masked Entity Type); V2 `ValueMeasures` / `ColumnProfile` cover amount totals; V3 do pattern packs also run in the browser; V4 `EntityProfiles` cost on a 10^7-row Dataset (IMS) without the link index | 1 day |
| **P1 Discover** | route + cache + Data page tab + Start box *About this Dataset* | M |
| **P2 Entity summary** | route + node dialog *Summary* | M |
| **P3 Presets** | pack key + loader + 2 routes + 6 presets + UI picker with parameters + table + *Draw these* | L |
| **P4 Builder** | form + same compiler path + *Save as preset* | M |

## 5. Done means

On the Basic install, over the IMS and Mobile money Datasets: Discover shows counts and busiest entities in under 3 s
(cached under 0.5 s); an entity summary opens from a busiest row; *numbers sharing a device* and *cash-in then
cash-out within 30 minutes* return rows that **Draw these** turns into a graph; a masked Entity Type never shows a raw
key; tests per route (real HTTP, masking, limits) and UI specs.

## 6. Decisions (operator 2026-11-03: accepted as recommended - presets first)

- **Q-1** Discover and the entity summary are **Basic and up** (read-only explorer reading); the full profile pages
  stay Standard (D-LAE4 unchanged). *Recommended.*
- **Q-2** Presets are **declarative, never free SQL**: compiled through `MeasureCompiler` or the pattern routes over
  the Dataset's mapped roles. *Recommended* (masking and guards stay where they are).
- **Q-3** Presets ship **in the industry pack** (one set per pack), plus **Space-level presets** saved from the builder.
- **Q-4** Order: **P3 presets first** (the most visible value), then P1, P2, P4. Alternative: P1 first (cheapest).
- **Q-5** A preset needs its roles mapped: a Dataset that lacks a role (e.g. no device column) does not list that
  preset rather than showing it greyed - the Mapping page is where a role is added.

## 7. As built: P0 + P3 (2026-11-03)

**P0 findings.** V1: `/bi/query` is not the presets' path - la-api may not depend on the engine (LA separation D-1), so
`MeasureCompiler` is out of reach; presets render one fixed shape in la-api instead (below), which also gives them a real
HAVING. V2: `ValueMeasures.evaluate` runs over any Dataset's roles and covers *cash-in then cash-out* (`timeToCashOut`);
its window is capped by the Space (31 days by default), so that preset defaults to `last: 30d`. V3 (pattern packs in the
browser) and V4 (cost on a 10^7-row Dataset) belong to P1 and stay open; the presets' first run on IMS is the V4 sample.

**§6a decisions taken under delegation (assistant, 2026-11-03).**
- Presets are **code keyed by Industry pack id** (`PresetQueries.forPack`: `mobile-money`, `telecom`, else a neutral
  set), not `preset_queries[]` in `industry-pack.toon`: a preset's compile rule is code either way, and a file format
  with one reader is speculative. Move them to the pack when Space-level presets (P4) need them as data.
- The **aggregate shape** (group by From or To; distinct counterparts, links, total amount; optional one-entity and
  kinds filters; a minimum on the first measure; ordered by it) is rendered in `PresetQueries.aggregateSql` with quoted
  columns and bound values - Q-2's "declarative, never free SQL" holds; the engine compiler does not.
- Roles: `defaultLinkMapping` {from, to, kind, time} when stated, else the first two columns classified as an Entity
  Type; Kind = `kind` / `*_kind`; When = `eventDate`, else the first date column; Amount = the first number column a
  pack `measure_hints` word names (never From or To). Read against the relation's REAL columns.
- The first set: *Reached by many* (fan-in), *Reaching many* (fan-out), *Top counterparts of one entity*, *Biggest
  senders by amount*, *Money in, then out again within hours* (mobile money), *Linked through one shared item* (telecom). Labels are neutral (AC-09, the vocabulary guard); the pack words stay in the ids. The
  plan's *SIMs seen in many handsets* is *Reaching many* over a SIM-to-handset Dataset; no separate preset.
- The panel sits under **Basic's Start box** only; Standard starts in the Investigation tab and gets presets with P4.

**Seams.** `la/inspecto-la-api`: `PresetQueries` (catalogue, roles, SQL), `PresetRoutes` (featureIds geoLink; gate
order 503 → 422 body → 404 Dataset via `InvRoutes.relationFor` → 404 preset → 422 role / parameter / unknown alias;
`ExplorationMasking` out on entity columns and `in` on the `entity` parameter; rows `min(top ≤ 500, fences.maxEntities)`;
statement timeout from the fences; audit `LINK_PRESET_RUN`). The POST is a read-shaped exemption (CapabilityManifest,
the contract test), rate-classed EXPENSIVE; the GET (a zero-row column probe) EXEMPT. Both are in the la-api
`module.toon` route list. UI: `link-analysis-presets.component.ts` + `presets.ts`; *Draw these* is the Start box's
`found` start (25 values per start).

**V3 (2026-11-05): yes, in the browser - over the loaded graph only.** The toolbox's motif builder runs a pattern pack
(the shipped built-ins plus the Space's `pattern-pack` components, `patternPackFromContent`) client side through
`matchPattern` (linear) and `matchBranchingPattern` (branching) over the graph on screen, on every edition. The
whole-Dataset run is the server's `POST /inv/pattern/branching` (*Run over the whole Dataset*); an Investigation's own run
goes there too. So Discover does not run packs: over a Dataset nothing is loaded yet, and the server run is a question of
its own (a preset), not a summary.

**V4 (2026-11-05, IMS on the Basic install, 39.4 M links, 12.35 M distinct numbers; timings only).**

| Read | Time |
|---|---|
| `EntityProfiles` union read (`suggest`, empty prefix: `COUNT(DISTINCT k)` + group by raw over every binding) | 112 s, 113 s (no cache) |
| a preset from the index `nodes` table (Reached by many) | 0.8 s |
| the same grouped over the Dataset (a kinds filter) | past the Space's 10 s timeout |
| rows + min / max time | 0.1 s |
| link kinds (`GROUP BY kind`) | 0.2 s |
| `approx_count_distinct` over both ends (`UNION ALL`); exact `UNION` count | 2.6 s (0.15 % off); 18.5 s |
| one pass: rows, period, 2 approx distincts, 2 measures (sum + `approx_quantile` 0.5) | 5.2 s |

So Discover never uses the `EntityProfiles` union read (one Entity Type across every Dataset, 100+ s on IMS): it reads
THIS Dataset's columns in a few cheap passes, approximate distinct counts said as such, and takes the busiest entities
from the link index when one is current, else from the Dataset under the statement timeout (a timeout = not assessed,
with "a link index answers it quickly"). Cold Discover on IMS is about 6 s, over plan §5's 3 s; the cached answer is
what meets it.

**Gotcha.** DuckDB refuses the zone id `Z`: pass `ZoneId.of("UTC")`, never `ZoneOffset.UTC` (the first test run failed on it).

A `kinds` filter on a Dataset that maps no kind of link is refused 422 (the panel does not show that field there), never
silently dropped; the Value Measure preset is cut to the same 500 rows as the aggregates.

**Tests.** `ControlApiPresetQueriesTest` (list per pack and per mapped roles, masking scan of every body - the Value
Measure preset included, alias in, the cap and `truncated`, the `LINK_PRESET_RUN` audit, gate order), `PresetRoutesContractTest`, the Basic and module-gate
route lists, `link-analysis-presets.component.spec.ts`.

### 7.1 Graph-shaped presets and panel fixes (2026-11-03, operator: "help the user choose which entities to investigate")

The core set (every Space; a pack puts its own first) gained four **graph** presets, each a fixed statement over the
links joined to themselves (`PresetQueries.graphSql`, no graph store): **Two-way pairs** (A to B and B to A, at least
*n* each way), **Short loops** (A to B to C to A, each loop once with its smallest id first; entities with more than
`maxLinks` (200, later 50 link rows - see `archived-documents/plans-archive/la-index-pairs-loops-plan.md` §9) distinct counterparts are left out before the joins, the statement timeout bounds the rest),
**Newcomers** (first seen within the last *N* days of the DATA - its newest link, not the calendar - with at least *n*
links; needs When) and **Two steps from one entity** (undirected: step 1 with its links, step 2 with the number of
step-1 entities leading to it; an alias is accepted). Their entity columns are masked like the others. Core order: Reached
by many, Reaching many, Two-way pairs, Short loops, Newcomers, Biggest senders, Top counterparts, Two steps.

Panel fixes from the live drive: the Dataset is preselected by the Start box's rule (the page's, else a valid pick, else
the first), and a measure shows at most two decimals (`presetNumber`). Not done: Bridges, Shared counterparts and
Bursts (listed in the conversation's proposal) - left for P4 or a later row if asked.

### 7.2 Presets read the link index (2026-11-03, operator: "use the index")

Found on the Basic install: over IMS (`ims_links_dataset`, 39.4 M links) EVERY preset read of the Dataset, Reached by many
included, ran past the Space's 10 s statement timeout, and Short loops ran out of DuckDB memory. The published link index
is NOT folded - `out`/`in` hold every link - but its `nodes` table holds per entity `out_edges`, `in_edges`, `out_links`,
`in_links` (distinct `(neighbour, kind)`), `first_ts`, `last_ts`. So:

- **Reached by many, Reaching many, Newcomers** read `nodes` (`IndexReader.rankNodes` / `newcomers`, fixed SQL, bound
  values) when a CURRENT index exists for the Dataset's From and To, through `IndexedRead.select` - the Space's index switch
  and the staleness refusal apply as for exploration; a stale-but-servable index answers flagged. Measured on IMS: 0.2 s
  and 0.5 s. A kinds filter is not in `nodes`, so it reads the Dataset (`source.reason: filter_not_indexed`). Newcomers uses
  the index's own time column (`source.timeColumn`).
- With appended deltas an entity has several `nodes` rows; they are summed (5.5 s on IMS) and the link counts are then an
  upper bound (`source.approximate`). Without deltas the rows are read as they are.
- **Two-way pairs, Short loops** need every link folded: 48 s and out of memory even over the index. Since
  `LA-INDEX-PAIRS-LOOPS-1` (shipped 2026-11-03) the index build writes `pairs_back` and `loops` and the two presets read them
  ([`la-index-pairs-loops-plan.md`](../archived-documents/plans-archive/la-index-pairs-loops-plan.md) §8-10).
- Every answer carries `source` (`{kind: index, version, stale…}` or `{kind: dataset, reason}`); the panel says when the
  index answered and when it is behind.
- **Counting differs, and is said.** Newcomers from the index counts each side apart: `links` = edges in + out (self-links
  included), `counterparts` = `out_links + in_links` (a counterpart on both sides twice, per kind of link); the column
  label and `source.counting` say so. Reached by many / Reaching many label their count "per kind of link" when the index
  has a kind column. An index read that fails (no `nodes` table, a read error) falls back to the Dataset with
  `source.reason: index_read_failed`, as every indexed read does. The summed-delta path is pinned by
  `IndexAppendTest.theNodesTableOfAnAppendedVersionIsSummedPerEntity…` (edges exact, links an upper bound).
