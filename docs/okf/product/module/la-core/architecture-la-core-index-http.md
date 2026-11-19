---
type: Concept
title: Link Analysis — architecture — Domain core — discovery; link index, Graph Run and HTTP surface
description: Part 3 of 4 of Link Analysis — architecture: Link index (la-storage); Graph Run (graph + la-core + la-storage); HTTP surface (la-api, geo-link)
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — architecture — Domain core — discovery; link index, Graph Run and HTTP surface

Part 3 of 4 of [Link Analysis — architecture](architecture-la-core.md) · previous: [Domain core — masking onward](architecture-la-core-masking.md) · next: [Host registration, editions, bounds and known seams](architecture-la-core-host-editions.md).

## 3. Domain core (la-core) (continued)

**My work and discovery (LA-ONBOARD-1 phase 7, as built 2026-10-22; UC-10, UC-12, UC-13, UC-14, A-1b).** SPA only, plus one
optional pack field; no route was added.
* **A case = a saved view + the Entity Lists it uses.** `LinkAnalysisView` (the `link-analysis-view` content) gained two
  optional keys, read defensively (`listsOf` / `startOf` drop a wrong shape): `lists {start?, exclusions?[]}` (Entity List
  ids) and `start {datasetId, values?[], listId?}`. The page keeps `caseStart` / `caseLists` signals: a Start box start sets
  them (`startFrom`, `startFromEntity`), an exclusion written from this graph adds to `exclusions` (Exclude → Add to a list,
  on either level, and *Add to a list* with purpose exclusion), `clearResults` (any new graph) forgets both. *Save as a case*
  (the former *Save view* menu, same `saveView()`) writes them with the view: `start.values` = the drawn values plus every
  grown node's first raw id (at most 25); for a list start only what was grown beyond the members, because opening re-reads
  the list. **Opening a case with `start`** (`loadView` → `drawCaseStart`) draws `drawStart` over the start Dataset (live
  list members + values), then hides the drawn nodes whose raw id is a member of an `exclusions` list now (case-insensitive,
  `hideLocally`, undoable); when the Dataset no longer starts a graph or nothing is found it reruns `query`, as every older
  view does. Specs `link-analysis.component.spec.ts` "a case, and Find something interesting" (falsified: without
  `drawCaseStart` the whole-Dataset projection runs; without `caseOfState` nothing is saved) and `link-analysis.service.spec.ts`.
* **My work** (`la-app` `my-work.ts` + `my-work.component.ts`, on the home page after the Space guide): cases from
  `GET /components/link-analysis-view` (the list carries `modified`, now on `ComponentDef`), most recent first, list titles
  from `GET /entity-lists`, each a link to `/link-analysis?view=<id>` (phase 3's param). **What changed in your data** reads
  no new route: the registry's `dataset` entries with `origin: import` + `importPipeline`, then `GET /runs/{pipeline}/batches`
  (the ingest audit ledger, ungated, Basic) - the last `SUCCESS` batch's `end_time`, `total_output_rows`, `rejected_rows`. The
  first import reads `+N rows`; a re-import reads the entry's `lastImport` ([architecture-data-onboarding.md](architecture-data-onboarding.md) §6.10, 2026-10-28): *replaced …, 2,104 → 2,300
  rows (+196)* or *appended …, +196 rows (now 2,300)*. When the ledger's last `SUCCESS` batch is not `lastImport.batch` (a
  Run now, which records none) or the entry has no `lastImport`, it falls back to *N rows loaded*, never a stale net change.
  Matched by `consignment_id`, not by clock: the ledger's `end_time` is server-local time with no zone.
* **Find something interesting** (`discover.ts` pure, `link-analysis-discover.component.ts`, top of the Analysis tab):
  *Who is busiest* = `degreeCentrality` top 5 (super nodes skipped; the hint "X has N links. Add it to an exclusion list?" on
  the top row when it has at least 5 links and twice the next one's); *Which groups stick together* = `louvainCommunities`, groups
  of 3+, largest 5, links among them and the days their links span when a time column is chosen; *How does A reach B* =
  `shortestPath` direction `out`, else `both` (said so). Every reading is one sentence in `packTerms`. Row actions: *Show on
  graph* (emphasis + focus), *Start from these* = the new `StartRequest` kind `found` over the graph's Dataset (delivers phase
  6's "the busiest / a community" start), *Add to a list* = `LinkAnalysisAddToListDialog` (pick a list of the same Entity
  Type or create one with a purpose in plain words; it writes `POST /entity-lists` + `/members` and closes with the list).
* **First lists from the pack**: `IndustryPack.Profile.suggestedLists` from the manifest's optional
  `profile.suggested_lists[n]{title,purpose,entity_type,why}` (at most 10; purpose one of allow / block / watch / exclusion;
  a core Entity Type; a bad entry refuses the pack whole), served as `profile.suggestedLists`; the neutral profile suggests
  *Busy entities to leave out* and *Entities to watch* with no Entity Type. The Entity Lists page offers them while the Space
  has no list and the person may write lists; *Create* opens `CreateEntityListDialog` with `initial`. Telecom ships *Call
  centres* / *Lines to watch*, mobile money *Merchants* / *Wallets to watch* (`IndustryPacksTest`).
* **Levels.** Everything works on Explorer. *Start an Investigation from this* is shown locked in the *Save as a case* menu
  on Explorer (`inspecto-locked-action`, `featureReasons.investigations`); with Investigations the Investigation tab is
  unchanged.

### 3.5 Entity store (identity data)

* **Identity Fact log** (`EntityFactLog`): `<Space config root>/audit/entity-facts/` (the data root's `audit/` when the Space has one), one file per fact
  (12-digit seq), each carrying `prevHash` = SHA-256 of the previous file's bytes; written stage → fsync →
  hard-link publish (refuses overwrite, cross-process). `read()` verifies the whole chain every call
  (`BrokenChainException` → 500 `INTEGRITY_VIOLATION`); a truncated tail is caught only by a cited head hash.
* **Fold** (`EntityRegistry.fold(facts, atSeq)`): list created / members added-removed (optional `expiresAt`) /
  ranges / retired; `identity.asserted`/`retracted` union-find into resolution groups (deterministic only, D-M1).
* **Entity Types** (`EntityTypes`): nine defaults (ids subscriber, imsi, imei, msisdn, wallet, account, agent, handset,
  cell; their labels are NEUTRAL since LA-EDITIONS-1, e.g. *Phone number*, and the Space's Industry pack relabels them, §3.6), max 64, closed normaliser set `default | digits | e164 | upper-trim` that must match the SPA's
  `entity-key.ts` character for character (shared parity fixture). Stored in `link-analysis.toon`.
* **Sidecar** (`EntityListSidecar`): a Parquet projection per list for SQL (`physicalRef` Dataset); a failed write
  never undoes the fact.
* `LinkAnalysisSettings` is the one reader of `link-analysis.toon` (caps, masking, four-eyes thresholds, entity
  types, distinct caps, `hub_threshold`, `index` and `drafts` records, graph-run knobs); null = inherit default, never unbounded.

### 3.6 Domain Profile and Industry packs (LA-EDITIONS-1 phase 4, as built 2026-10-17; settles `LDP-D7`)

* **What it is.** A Space's **Domain Profile** names the installed **Industry pack** whose vocabulary Link Analysis
  uses: `domain_profile: <pack id>` in `link-analysis.toon`; absent, `generic` or a pack this build does not ship =
  the neutral vocabulary. ⛔ A pack relabels the core's concepts and, since `ENTITY-TYPE-CATALOG-1` (2026-11-04), may add
  Entity Types — each under a core family — and record types; further packs join through `extra_packs` (one id is one
  Entity Type in every pack; as built: [entity-store spec](../entity-store/spec-entity-store.md) "Industry packs"). Every term is
  keyed by a canonical concept key (`concept.node|link|row|link_from|link_to`, `entity_type.<core or pack type>`,
  `record_type.<record type the pack lists>`, `link_kind.<kind the pack lists>`,
  `measure.<Value Measure>`, `pattern.<pattern pack the pack lists>`, or `term.<id>` for a glossary-only entry such as
  a fraud typology), and a key that names nothing in the core refuses the whole pack.
* **Format** (`IndustryPack`, `IndustryPacks`, in `inspecto-entity-store`, a base module, so every edition ships the
  packs, Basic included, ED-5). Each jar may carry an index `META-INF/inspecto/industry-packs.toon`
  (`packs[n]: a,b`); each pack is four files (plus an optional `industry-pack-record-types.toon`) under `META-INF/inspecto/industry-packs/<id>/`: `industry-pack.toon`
  (id, name, description, `space_templates`, `pattern_packs`, `profile` = measure / time column hints, suggested tool
  groups, the optional "Investigate a number" binding and the optional `suggested_lists`, LA-ONBOARD-1 phase 7), `industry-pack-terms.toon`
  (`terms[n]{key,label,abbreviation,definition}`), `industry-pack-entity-types.toon` (the Entity Types the pack's Spaces
  read: starter ids restated, or the pack's own under a `family`, each with normaliser, masked, classifications and an
  optional value `shape` and `example`; see the entity-store spec) and
  `industry-pack-link-kinds.toon`. Normalisers stay the closed set: a pack picks one per type, it never ships code.
* **Shipped packs.** `telecom` (lead Space Template `telco-fraud`, also `la-showcase`; pattern packs
  `forwarding-relay` + the three `telecom-*` in `la-showcase`) and `mobile-money` (`la-mobile-money`, also
  `mobile-money`; the built-in money pattern packs). Each lead template's `link-analysis.toon` selects its pack.
* **Fail closed.** Discovery reads every index once (`IndustryPacks.bundled()`); a pack with a missing or unparseable
  file, any rule broken, an id two indexes declare, or the reserved id `generic`, is **refused whole** and reported
  (`refused[]`), never partly installed. `IndustryPacksTest` pins: the shipped packs load with nothing refused, each
  referenced Space Template, pattern pack and investigate Dataset ships.
* **Effective Entity Types.** `LinkAnalysisSettings.effectiveEntityTypes()` = the stated list, else
  `EntityTypes.DEFAULTS` overlaid by the active pack (`IndustryPack.overlay`: same ids and order, the pack's
  normaliser / masking / classifications and its `entity_type.*` labels). Every route that shows Entity Types
  (`entityTypesInForce`, Entity profile pages, masking) therefore speaks the pack's words.
* **Routes** (`DomainProfileRoutes`, core): `GET /settings/link-analysis/domain-profile` answers `{active, stated,
  diagnostic?, profile, installed[], refused[]}` (the active pack in full, terms included);
  `PUT /settings/link-analysis/domain-profile` `{domainProfile: id | "generic" | null}` under `canAuthorWorkbench`:
  503 without a writable Space, 422 for a missing / non-string / uninstalled id (the message names the installed
  ones), 409 when `link-analysis.toon` does not parse (left as it is). It writes ONLY `domain_profile`
  (`LinkAnalysisSettings.writeDomainProfile`, read-modify-write under `LaLimits.WRITE_LOCK`); the record's own
  `write` does not model the key, so `PUT /settings/link-analysis` keeps it. Real-HTTP test:
  `ControlApiDomainProfileTest`.
* **Neutrality (AC-09).** The banned words are DERIVED from the shipped term files (every label and abbreviation):
  `IndustryPackNeutralityTest` checks the server's neutral labels (`EntityTypes.DEFAULTS`, `IndustryPack.CONCEPTS`);
  `tools/check-vocabulary.mjs` rule `industry-pack-term` checks the text users read in core Link Analysis (the
  `link-analysis` and `la-app` SPA projects, `la/*`, `inspecto-entity-store`). A single-token literal is matched as
  written, so an id such as `msisdn` (a data contract) passes while a label `MSISDN` does not. The pack term files are
  the only place the words may live. `IndustryPack.MEASURES` mirrors `ValueMeasures.ALERTABLE`
  (`ValueMeasuresPackKeysTest`).
* **SPA relabels.** The SPA renders the concept, Entity Type and Measure relabels and the term table; the canvas
  legend reads `link_kind.*` and the pattern picker `pattern.*` (label and definition), and the tool-rail glossary
  popover lists the pack's typology terms `term.*` (2026-10-18, `link-analysis-glossary.component.ts`). The guard
  above already bans every `term.*` label and abbreviation in core text. **Residuals.** The node-risk mapping and the pattern-pack column defaults in the SPA are still keyed by the `telecom` id.

## 4. Link index (la-storage)

* **Layout:** `<writeRoot>/la-index/<dataset>/<mappingHash>/vNNNNNN/` with a `CURRENT` pointer (build to
  `.tmp`, rename, atomic-move the pointer). A version holds `manifest.json` and three Parquet tables, each
  `PARTITION_BY (bucket)`, zstd, row group 100,000, sorted by (bucket, key, ts): `out/` (edges keyed by src),
  `in/` (the same edges keyed by dst — **both directions stored**, data doubles) and `nodes/`. Columns
  `src, dst, kind, ts, w, a0..ak`; NULL endpoints are dropped and counted; `ts` is naive UTC (session zone pinned).
* **Bucket:** `BucketFunction` = `md5_number_lower(id) % N` (fixed MD5 bytes, golden-tested, Java and SQL
  renderings, both the Analytics Kernel's `Md5Bucket` — the one definition, shared with the holdout split), `N = clamp(pow2(ceil(edges / 4e6)), 16, 1024)`. DuckDB cannot derive the partition from a key
  equality, so **the reader computes the bucket in Java and writes it as a literal** — without that the layout does
  not prune.
* **Build memory (`LA-INDEX-SCALE-MEASURE-1`, 2026-10-21):** `out` and `in` are written first (sorted, bucketed); `nodes` is then
  folded ONE BUCKET AT A TIME from those two written tables (`IndexBuilder.copyNodes`: per bucket, the `FULL JOIN` of the bucket's
  `out` fold and `in` fold - a node's two sides share its bucket), where it was one statement over the whole edge relation (two
  global grouped `count(DISTINCT (neighbour, kind))`, a join, a global sort) that ran out of memory at 2x10^8 edges and above. The
  link statistics' degree is `DISTINCT (key, neighbour)` then `count(*)`. Same rows in every table (`IndexNodesFoldTest`, `IndexLinkStatsParityTest`); the
  relation is no longer scanned for `nodes` (the old statement scanned it twice). Not changed, global, unmeasured above 10^8: the `out` / `in` sorts and the statistics' distinct-links
  table and node-sized aggregates.
* **Modes** (`IndexPlan.classify`, shared by `GET /inv/index`, the submit gate and the builder's own defence):
  `full` from the relation SQL; `append` = a delta (`dNNN` files in the same bucket dirs, version N+1 hard-links its
  parent) only when the manifest's input files are an unchanged subset of today's and one is new; `compact` merges
  main + deltas. Delta cap `MAX_DELTAS = 8` (`delta_cap_reached`). FULL-forcing reasons: relation SQL / bucket fn /
  DuckDB version changed, input files unknown, removed or changed; and, with no new file, a derived-tables cap other than
  `index.loops_max_degree` (`derived_tables_outdated`).
* **Staleness** (`IndexStaleness`, la-api) compares input-file fingerprints from `DatasetRelation.inputFiles`
  (platform engine); a view-backed Dataset has no file list (`fingerprintKnown=false`) and is never called stale —
  hence D-ING6: dimension data is joined at read time, never baked into the index. It is the ONE answer every surface
  reads (`GET /inv/index` for the Data page and the Investigation's data strip, `IndexedRead` for the Expand), and its
  `details` are the plain words those surfaces show — no SQL or engine term (`LA-FULL-CYCLE-TEST-1` phase 4,
  2026-11-16; the screen side is [spec-la-core.md § Link index status](spec-la-core.md#link-index-status-one-answer-and-rebuild)).
* **A build runs in the Space that submitted it** (phase 4, finding 21). `IndexBuildService.submit` hands the
  submitter's `MDC` (its Space id) to the worker for the build and clears it after. 🔴 Gotcha, now pinned: the builder
  renders the relation AGAIN on its worker (`held()` → `QueryExecutor.rerendered`), and `ConsignmentSelector` asks the
  Consignment registry of the THREAD's Space which files to read. With no Space on the worker it read the store as a
  glob, every reader in the Space read a pinned file list, the manifest recorded another `relationSqlHash`, and the
  index read `relation_sql_changed` the moment it was published — in every Space but the default one, which is why no
  single-Space test saw it (the test drive blamed the details import; that import is SKIPPED by the index follow-on).
  Any other pool that renders a Dataset's relation must carry the Space the same way; Add data's background pool (an
  import above `import.inline_max_bytes`, answered 202) does since the same phase. An index built before the fix
  stays out of date (an APPEND keeps its parent's hash) until a FULL build: the next import, the Space's
  `la.index.build` Job (`allow_full`) or **Rebuild** runs one, since `IndexPlan` advises `full` for a changed definition.
* **Build service** (`IndexBuildService`, one per write root via `IndexBuildServices`): 1 thread, queue 4; one live
  build per (Dataset, mapping) → 409; disk estimate `rows × 34 B` vs `max_disk_bytes` → 422 (estimate bounded at
  10 s); queue full → 503. The builder uses its own **non-sandboxed** in-memory DuckDB (COPY TO is forbidden in the
  sandbox) over trusted relation SQL after the caller's view gate; failure deletes the stage, `CURRENT` untouched,
  only the exception class is stored.
* **Reader** (`IndexReader`): one sealed `SqlSandbox` connection per request limited to the version directory,
  views over `read_parquet(... hive_partitioning)`, one key equality per side `UNION ALL`ed, **one hop per query**;
  idle pool of 4 per version, siblings evicted unless pinned. Parquet is the only persisted form — no `.duckdb` file.
* **What reads from it** (`IndexedRead` gate in la-api: `index.enabled` (default ON since 2026-10-10; only an explicit `false` disables) + a matching published index
  + staleness gate, else a closed fallback `Reason`): `recursive-paths` (`IndexedTraversal`, depth ≤ 2, frontier
  ≤ 20), neighbours, simple Investigation `expand` (byte-identical fingerprint to the flat read; weighted when the
  mapping's `weightCol` is the Investigation's `eventsCol`; the supernode hub degree from the same version when fresh —
  `read.hubDegreeSource`, §3.1), temporal, and Graph
  Run with `input: "index"`. **Age gate (LA-INDEX-MAX-AGE-1, 2026-10-11):** a Dataset whose input files cannot be fingerprinted (view-backed, `fingerprintKnown=false`) is never reported stale, so the index serves it only while younger than the Space setting `index.max_age_hours` (default 24, 1..8760); an older or undated build reads flat with the Reason `stale_unknown` (the 12th closed Reason; listed in the SPA's `index-source.ts`). A fingerprinted Dataset ignores the age (stale-served and flagged, as before). A response never mixes index and flat answers — with ONE known, documented exception:
  an `expand` served from a **stale** index (input files added since the build) takes its rows from the older index
  version but counts the hub degree flat over the CURRENT Dataset (`hubDegreeSource: flat`), so a sealed `hubs[].degree`
  may count links the sealed rows do not show yet. Accepted (the degree is display + flagging only, sealed, outside
  the hash; `read.index.stale` says so); pinned by `ControlApiInvestigationHubDegreeTest
  .aStaleServedRungCountsTheHubDegreeFlatOverTheCurrentDataset`.
* **Bounded degree statements:** `IndexReader.distinctCounterparties` and the flat `hubs()` check pass their
  candidate and excluded ids as ONE bind each (`IndexReader.idRelation`: joined on U+001F, `unnest(string_split(..))`;
  a `VALUES` fallback only when an id itself carries U+001F) and prune the index by the candidates' bucket set, so a
  large exclusion list or candidate set never grows the statement (parity: `IndexReaderTest`, 2,300 keys × 5,000
  exclusions).
* **Scheduled builds:** the `la.index.build` Job → `link-index` Platform Service → `ScheduledLinkIndexBuilder`
  (geo-link) → `ScheduledIndexBuild` (la-api), principal `index-build:<job>` holding only `canBuildLinkIndex`,
  authority = the Job's stamped `owner`, re-decided every run; runs the plan's advice only (`full` needs
  `allow_full`).

## 5. Graph Run (graph + la-core + la-storage)

* **What stays in LA, what is the platform's** (D-AN2, 2026-10-17). The algorithms take plain node/edge ids and an
  edge-id → weight map, and live in `platform/inspecto-graph`. Link Analysis keeps the algorithm catalogue
  (`Algorithm`), dispatch (`InMemoryGraphEngine`), the cost ceilings and budget (`GraphBudget`), and the
  `"kind · N"` edge-label weight parse, `WorkingSetGraphInput.edgeWeight` (la-core; pinned by `EdgeWeightParityTest`
  against `graph-paths-parity.fixture.json`). `GraphSuspicion` stays in the library: it is the generic weighted blend
  of five graph factors (default weight 1 each) with no LA preset in it. The TypeScript twins and the parity fixtures
  stay under `inspecto-ui/projects/link-analysis/src/graph/`; the Java parity tests read them from there.
* **Motif search: the matcher is the platform's, the compiler stays** (S6, 2026-10-17). The windowed fan-in / fan-out
  search behind `/inv/pattern/branching` is `com.gamma.graph.TemporalMotifMatcher` in `platform/inspecto-graph`
  ([Analytics Kernel § Temporal motif matcher](../analytics/spec-analytics-cohorts-forecasting.md#temporal-motif-matcher)), so AML, Mobile Money
  and Fraud can match structuring rings without Link Analysis. LA keeps `PatternQueryCompiler` (stage parsing and its
  422s, the SQL prune over the whole Dataset, key normalisation), its `Stage` with edge kind, node kind and threshold
  band, and `Stage.motif()` — the mapping onto the matcher's neutral stage, where a node kind other than `entity`
  admits no nodes. `PatternRoutes` (la-api) still masks, labels and shapes the answer.
* **Temporal scan: cut in DuckDB, detected in Java** (`DUCKDB-2-LOOP-QUERIES-1` C3, 2026-11-14). Behind
  `/inv/pattern/temporal` and the Investigation `temporal` op, one statement (`TemporalCut`, la-core) reads the capped,
  ordered `(source, target, time)` rows and returns only the series that can hold a finding - a burst when an event's
  `(minEvents - 1)`-th successor is within the window (`lead`, not a `RANGE` count: one hot link of 2 000 000 events ran a
  `RANGE` frame past the 10 s timeout), a regular series when its gap CV is within `maxCv` plus a float tolerance - with
  `skippedNoTime` and `rowCapped`. The flat read and the link index (`IndexReader.scanTemporalCut`) share it, and
  `LinkTemporalDetector` decides on what is kept, so findings and sealed fingerprints are the same as reading every row
  (`TemporalCutParityTest`). About 0.4 % of the rows reach the JVM, so `pattern.temporal_max_rows` defaults to 2 000 000.
  Bench and the refused `USING KEY` traversal (C1):
  [`2026-11-14-duckdb-loop-queries-plan.md`](../../../../archived-documents/plans-archive/2026-11-14-duckdb-loop-queries-plan.md).
* **Index traversal stays per-key** (C1 measured 2026-11-14, refused): a `WITH RECURSIVE ... USING KEY` reachability over
  the index joins the whole `out` copy per iteration, so it lost to the per-level bucket lookups 5x at 10^6 and 40x at
  10^7 edges at depth 2. `SqlGraphEngine` / `IndexedTraversal` keep their 2-hop / 20-key caps.
* **Two more DuckDB 2.0 loop candidates measured and refused** (2026-11-14, opt-in benches in `la/inspecto-la-storage`
  tests; same answers in every run):
  * **C4, branching legs with `ASOF JOIN`** (`BranchingLegsBench`): the `afterPrevious` test of `PatternQueryCompiler.legs`
    stays the correlated `e.ts > (SELECT min(p.ts) FROM prev p WHERE p.t = e.s)`. `ASOF JOIN` took 3.2 s vs 2.3 s at
    10^6 rows and 84 s vs 26 s at 10^7; an explicit `GROUP BY` join tied at 10^6 and took 54 s at 10^7. DuckDB already
    decorrelates the subquery well.
  * **C2, components by `USING KEY` label propagation** (`ComponentsLabelPropagationBench`): equal to
    `GraphAlgorithms.connectedComponents` at 10^6 edges in 3.0 s vs the JVM's 3.6 s, but one round per step of a
    component's diameter, so adding a single 200-node chain made it 52.8 s (the JVM stayed at 3.5 s). Components stay
    in memory.
  * **C5, the expand ladder as one statement** (operator, closed without building): each degree of an Investigation's
    ladder is its own sealed step with its own hub rule and, when sensitive, its own four-eyes approval before the next
    degree runs; one statement cannot stop between degrees for an approval or seal each one, and its recursive form
    carries C1's cost.
  * **C8, DuckLake as the home of Drafts and Working Sets** (`DuckLakeDraftStoreSpike`, extension loaded from the local
    cache with auto-install off): byte-identical text and time travel work, but every one-step append is a catalog
    snapshot at about 23 ms (200 steps 4.6 s, 202 snapshots), and a second writer on the DuckDB-file catalog is refused
    ("already attached"), so more than one pod would need a Postgres catalog - where `PgInvestigationStore` already
    keeps the log with row locks and versioned writes. The sealed log already is the history. Kept on the file and
    Postgres stores.

* **Input** is a sealed `GraphInput`: `Materialised` (from the Working Set, `WorkingSetGraphInput`) or `IndexRef`.
  `RoutingGraphEngine` routes **by input type only**, never by size: Materialised → `InMemoryGraphEngine`
  (exhaustive switch over `Algorithm`, so a new algorithm fails to compile until implemented), IndexRef →
  `SqlGraphEngine` (neighbourhood, ego network, seeds-only degree; caps throw `IndexCapExceeded`, no reroute).
* **29 algorithms** (`Algorithm`), each with a cost class (SYNC / JOB, a hint) and a node ceiling (e.g. 500 for
  betweenness, 100,000 for shortest path) that is the inline-versus-job threshold, not a limit — the budget refuses.
  The toolbox has dedicated controls for 18 (*Run on server*) and 3 (*Run on index*); its catalogue-driven *All
  algorithms (server)* panel runs every other one, `propagatedRisk` included (number-list, node-list and
  fill-from-indicators controls for `DOUBLE_LIST` / `ID_LIST` / `SCORE_MAP`); only a parameter type it does not know
  is labelled *API only* with Run held.
* **`propagatedRisk`** (server-only, `GraphPropagation`, no TS twin; JOB, ceiling 1,000): `raw(n) = own(n) + Σ own(o) ×
  weights[d(o,n)]` over origins `o ≠ n` within `weights.size()` (≤ 6) shortest hops; `score = min(raw, 100)`. Params:
  `nodeScores` (id → 0-100, request-only — nodes carry no attributes, so no `scoreAttribute`),
  `seeds` (empty = every node with own > 0), `weights` (each in [0,1], default `[1, .6, .35, .15]`), `direction`. Each
  node returns its top 5 `factors` (origin, distance, weight, contribution) and `contributors` (the full count).
* **Space setting `graph_run.propagated_risk_weights`** (1..6 numbers in [0,1]; absent = `[1.0, 0.6, 0.35, 0.15]`):
  the weights a `propagatedRisk` run takes when its params state none; the start response echoes
  `propagatedRiskWeights {weights, source: request | space}`; `GET /settings/link-analysis` echoes `propagatedRiskWeightsInForce`.
* **Budgets** (`GraphRunService`): shipped default 50,000 nodes / 500,000 edges / 30 s; ceilings 500,000 / 5,000,000 /
  300 s (the defaults are Space settings `graph_run.max_nodes` / `max_edges` / `timeout_ms`; the ceilings, the pool
  and `index.threads` / `index.queue` are install-wide limits since `LA-CONFIG-LIMITS-1`, see §9). Size is checked before work, the deadline at `RunControl` checkpoints; overrun ends `BUDGET_EXCEEDED` with
  no result — never a silent cap.
* **Pool:** 2 threads, queue 16 (`AbortPolicy` → `REJECTED`, HTTP 503), finished-run retention 200, result cache 32 entries /
  10 min. Cache key = relation + row-scope fingerprint (a tripwire against crossing scopes) + algorithm + resolved
  params + weights + input identity. Only COMPLETED results are cached; masking applies after the cache.
* **Parity:** Java and TypeScript assert the same hand-derived fixtures with canonical-v1 tie-breaks; the server has
  no node cap of its own beyond the budget.

## 6. HTTP surface (la-api, geo-link)

32 `RouteModule`s in la-api plus `InvestigationMeasureRoutes` and `InvestigationDataChangesRoutes` in geo-link (its other two, `EmbedExpirySweepModule` and `IntegrationWebhooks`, serve no route), **111**<!--count:la-routes--> routes (**104**<!--count:la-api-routes--> in la-api + **7**<!--count:geo-link-routes--> in geo-link;
the live `GET /audit/route-inventory` lists 78 for `/inv` and `/geo`, all present in `openapi-v1.json`):

| Area | Routes | Write capability |
|---|---|---|
| Exploration | `/geo/projection`, `/geo/routes`, `/inv/projection{,/neighbors,/multi}`, `/inv/schema/*`, `/inv/traversal/recursive-paths`, `/inv/reference-rows`, `/inv/case-history`, `/inv/pattern/{branching,temporal}`, `/inv/value-measures`, `/inv/snapshots*` | none (rate-limited as expensive); snapshots POST `canManageIncidents` |
| Investigations | `/inv/investigations` create/list, `PATCH` / `DELETE …/{id}` (rename and restate the purpose `LA-INVESTIGATION-RENAME-1`; delete, lead only, 409 while linked to an open Case or holding a pending approval `LA-INVESTIGATION-DELETE-1`), `POST /inv/expand/preview` (the unsaved Investigation's dry run of `seed` / `expand` steps, nothing stored, `LA-UNSAVED-INVESTIGATION-1`), `…/{id}/expand/preview`, `…/{id}/ops`, `/undo`, `/reorder`, `/replay`, `/log`, `/working-set`, `/coverage`, `/compare`, `/references`, `/case`, `/members`, `/template`, `/inv/investigation-templates*` | `canManageIncidents` (+ lead) |
| Four-eyes / reveal | `…/pending/{p}/approve|deny`, `GET /inv/pending` (approver inbox), `…/reveal` | `canApproveLinkExpansions`, `canRevealLinkEntities` |
| Dossier | `…/dossier`, `/dossier/verify`, `/dossier/bundle`, `/dossier/bundle/verify` | read gate only |
| Drafts | `…/drafts` create/list, `…/drafts/{d}` + `/log`, `/working-set`, `/replay`, `/conflicts`, `/ops`, `/undo`, `/discard`, `/rebase`, `/promote` | `canManageIncidents` + per-role act check |
| Identity | `/inv/entity-identities`, `/group`, `/import`, `/{id}/retract` | `canManageIncidents` (the reads too; `/import` has no UI) |
| Graph Run | `/inv/graph/runs`, `/algorithms`, `/runs/{id}`, `/cancel` | `canRunLinkGraphAnalysis` on the POST that starts a run; list/get are read-gated; cancel is owner-or-admin, else 404 |
| Analyst upload | `POST /inv/uploads/{collector}?name=x.csv[&replace=true]`; `GET /inv/uploads` (upload-enabled Collectors), `GET /inv/uploads/{collector}` (own uploads + ingest state), `DELETE /inv/uploads/{collector}/{file}` (withdraw a pending upload) | `canUploadData` ([architecture-http-and-ui.md](architecture-http-and-ui.md) §6.6); the list is the caller's own, the delete is uploader-or-`canAdminister` |
| Index | `/inv/index`, `/inv/index/builds`, `/builds/{id}`, `/cancel` | `canBuildLinkIndex` on the POST that starts a build; the reads and cancel are not capability-gated |
| Entity profiles (2026-10-15) | `GET /inv/entity-types`, `GET /inv/entity-types/{id}/profile`, `GET /inv/entities/{type}/{key}/profile`, `POST /inv/entities/{type}/resolve`, `POST /inv/entities/{type}/suggest` (2026-10-22, the Start box) (la-api `EntityProfileRoutes`) | none beyond a subject (like the exploration reads); Dataset view gate per binding; profiles rate-limited as expensive |
| Data trust (LA-DATA-TRUST-1, 2026-10-14) | `GET /inv/datasets/{id}/files` (one row per File: outcome, event day, rows, error rows, index state; la-api `DatasetFilesRoutes`), `GET …/{id}/data-changes` (corrected-day warning; geo-link `InvestigationDataChangesRoutes`), `…/{id}/coverage` | read-only; Dataset view gate / Investigation `open` gate. As built: [user guide §Data trust](user-guide-la-core.md) |
| Embed link | `…/{id}/embeds` (POST mint, GET list), `…/embeds/{grantId}/revoke`, and the public `GET /public/investigations/{token}` | `canShareInvestigation` on mint and revoke (+ lead role); the list is read-gated; the public GET is authenticated by its token alone ([architecture-http-and-ui.md](architecture-http-and-ui.md) §6.2) |
| Measures & detection (geo-link) | `…/{id}/measures`, `…/alert-rules`, `…/standing-detection` | `canAuthorAlertRules` |

Capability constants are in `platform/inspecto-access` `Roles`. OpenAPI fragments
(`META-INF/inspecto/openapi.fragment.json` in la-api and geo-link) merge into `openapi-v1.json`; a new route also
needs `CapabilityManifest`, rate-class and auth-gate coverage (see the `endpoint` skill).

**A Dataset read failure is translated in one place (`DatasetReadFault`, la-api).** A file-backed Dataset whose store holds no files yet answers a closed `409 CONFLICT` ("Dataset 'X' has no rows yet: its Pipeline 'P' has not run." - the Pipeline is named only when the Dataset records it, `importPipeline`; the wording is `DatasetNoData` in inspecto-util, shared with the core routes) on every exploration read (projection, neighbors, multi, traversal, pattern, value measures, geo, reference rows, identity import); any other driver message stays a 422 but loses the JDBC preamble and every absolute path (`<path>`). The SPA's `invErrorMessage` adds a one-line hint.

Sections 6.1 to 6.20 are on three pages:

| Section | Page |
|---|---|
| 6.1 Gate order for an `/inv` request | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.2 Embed links | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.3 Entity profile routes | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.4 Third-party integration (inbound) | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.5 Third-party integration (outbound) | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.6 Analyst CSV upload | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.7 Masked-alias refusal on identity writes | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.8 Category exclusion lists in Investigations | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.9 Grow, Held and Exclude on the graph, with 6.9.1 The Investigation tab as one Expand filter | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.10 Add data: the shared import route | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.11 Help on every screen and the onboarding counters | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.12 Explorer defects found by the acceptance drive, fixed | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.13 Onboarding residuals | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.14 Studio UI reorganization | [architecture-http-and-ui.md](architecture-http-and-ui.md) |
| 6.15 Column roles | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.16 Discovery in Basic: presets, Discover, entity summary, query builder | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.17 Arrivals: the ledger, Data status and what a later file does | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.18 Arrival feeds: the inbox and Keep this up to date | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.19 Dataset storage: compaction, the merge map and retention | [architecture-data-onboarding.md](architecture-data-onboarding.md) |
| 6.20 Watch this folder: the 30 s inbox poll | [architecture-watch-this-folder.md](architecture-watch-this-folder.md) |

## Changes

- `pattern.temporal_max_rows` default raised from 400 000 to 2 000 000 with the temporal cut (`TemporalCutParityTest`).
