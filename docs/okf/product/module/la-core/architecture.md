---
type: Concept
title: Link Analysis backend architecture (la/ modules, entity store, host wiring)
description: How the Link Analysis backend is built - the five la/ modules plus platform/inspecto-entity-store and platform/inspecto-graph, their dependency direction, the Investigation op log and store port (filesystem / Postgres), Drafts, masking, the link index (la-storage), Graph Run (graph + la-core), the /inv and /geo route layer, the request gate order, host registration, editions, audit, rate limits and live detection.
resource: la/
tags: [module, link-analysis, investigation, index, graph-run, drafts, architecture]
timestamp: 2026-10-17T00:00:00Z
---

# Link Analysis backend architecture

The backend of **Inspecto LA (Link Analysis)**, the product (`ED-1`); the capability, its routes, keys and modules keep
the name Link Analysis.

Grounded in a code read on 2026-10-09 (tip `957c554af`). This page is the **backend structure**; behaviour,
decisions and measurements live in the feature concept
[`okf/product/module/la-core/user-guide-link-analysis.md`](user-guide-link-analysis.md), open work in
[`superpower/link-analysis-roadmap.md`](../../../../superpower/link-analysis-roadmap.md). Class names are given
without paths; locate them with `codegraph_explore`. Source of truth for module metadata is each module's
`META-INF/inspecto/module.toon`, never this page.

## 1. Shape in one paragraph

Link Analysis (LA) is an **optional, ServiceLoader-discovered extension of the one host process**. There is no LA
main class and no LA server: `la/inspecto-la-api` contributes `RouteModule`s (the `/inv` and `/geo` routes) to the
host `ControlApi`, `la/inspecto-geo-link` is the bridge that implements la-core's ports against engine services, and
the standalone `la-app` is only a second Angular shell (`package.ps1 -Ui la-app`) served by that same host. The
domain is an **append-only, hash-sealed Investigation op log** folded deterministically into a Working Set; reads
come either from a Dataset relation (flat recursive CTE in DuckDB) or, when switched on, from a **versioned Parquet
link index**; graph algorithms run in a bounded in-process pool over either input.

### Data flow

```
 INGEST (host engine, not LA)                           IDENTITY (entity-store)
 ─────────────────────────────                          ───────────────────────
 file lands ─▶ Collector ─▶ Pipeline ─▶ Parquet          analyst / import
 (CSV, Parquet,  (DayManifest,  (PartitionWriter)  store      │ POST /entity-lists, /inv/entity-identities
  Reference)      gap check)          │                       ▼
                                      ▼                  Identity Fact log (hash chain) ─▶ fold: Entity Lists,
                              Dataset relation SQL       │                                 resolution groups
                              (DatasetRelation,          └─▶ EntityListSidecar (Parquet, SQL-readable)
                               inputFiles fingerprint)
                                      │
             ┌────────────────────────┼─────────────────────────────────────────────┐
             │ INDEX BUILD (la-storage)                                              │
             │ POST /inv/index/builds  or  la.index.build Job                        │
             │   (signal pipeline.commit* → link-index service →                     │
             │    ScheduledLinkIndexBuilder → ScheduledIndexBuild, owner re-decided) │
             │        ▼                                                              │
             │ IndexPlan.classify ─▶ IndexBuildService ─▶ IndexBuilder (DuckDB COPY) │
             │   full | append | compact                                             │
             │        ▼                                                              │
             │ la-index/<ds>/<mapping>/vN/{out,in,nodes}/bucket=B/*.parquet          │
             │        + manifest.json, CURRENT pointer, pins.json                    │
             └────────┬──────────────────────────────────────────────────────────────┘
                      │                                │
 READ (la-api)        ▼ index.enabled + fresh         ▼ otherwise (closed Reason)
 ──────────────  IndexedRead ─▶ IndexReader       flat recursive CTE over the Dataset
                 (bucket literal, 1 hop/query)    (sandboxed DuckDB, view gate)
                      └──────────────┬─────────────────┘
                                     ▼
 request ─▶ authenticate ─▶ rate limit ─▶ ABAC ─▶ module enabled ─▶ capability ─▶ open gate
            (Subject)       (linkAnalysis)                (geoLink)                (member, R3, PDP)
                                     │
            ┌────────────────────────┼──────────────────────────────┐
            ▼                        ▼                              ▼
   exploration reads        Investigation op                Graph Run
   (projection, paths,      POST …/ops (or Draft ops)       POST /inv/graph/runs
    neighbours, patterns)          │                         input: workingSet | index
            │               sealed rows ─▶ InvestigationEvaluator (pure fold)
            │                      ▼                              │
            │               InvestigationStore.append ◀──────────┤ Materialised ─▶ InMemoryGraphEngine
            │               (FS audit/snapshots/<id> | Postgres)  │ IndexRef     ─▶ SqlGraphEngine
            │               log.jsonl + sets/<step>.json          ▼
            │               sensitive expand ─▶ pending ─▶ approve/deny (four-eyes)
            │                      │                      result cache (raw)
            │               Drafts: fork ─▶ ops ─▶ rebase ─▶ promote (main lock / txn)
            │                      │                              │
            └──────────────────────┴──────────────┬───────────────┘
                                                  ▼
                                    EntityMasking (tokens out, raw stays inside)
                                                  ▼
                                    JSON response · audit event (LinkEventTypes)
                                                  │
 OUTPUTS                                          ▼
 ───────   Dossier / bundle (SHA-256 root, verify) · Case link · Working Set measures
           ─▶ la.detect Job ─▶ AlertService ─▶ StandingDetection.decide (sweep:<id>) ─▶ Alert
```

\* **Trigger.** A landed file commits a Consignment and emits `pipeline.commit` (mirrored in `JobService`);
`job.dataset.produced` is emitted **only** by the `sql.template` Job. A `la.index.build` Job meant to refresh after a
daily file must therefore use `on_signal: pipeline.commit` (guard `$signal.pipeline`) or `on_pipeline`. Each half is
unit-tested, and `ControlApiIndexRefreshOnCommitTest` (`la/inspecto-geo-link`) runs commit → `pipeline.commit` → Job → index
version N+1 → indexed read, plus the negative on `job.dataset.produced` (`LA-DEMO-INDEX-1`, DR-T1).
`ScheduledDailyChainTest` (same module, `LA-DAILY-INGEST-1`) runs the whole daily chain with no hand-driven step: a
sample feed lands one CSV per day in the Collector inbox, the 1 s poll scheduler runs the Pipeline, the Pipeline writes
Parquet partitioned by `EVENT_DATE` (`day=NN`) straight into the Dataset's `physicalRef` store (a Pipeline's
`dirs.database` may BE a Dataset store, since `physicalRef` reads `<store>/**`), and the `la.index.build` Job on
`pipeline.commit` builds FULL on day one (`allow_full: true`, no index yet) and APPENDs each later day; each read sees
the new day, one build per day. Still out of scope: Hive JDBC / Kerberos and the `db` Collector (`D-ING5`, file-first),
SCD2 / as-of joins (`D-ING4`); dimension attributes are joined at read time, never baked into the index (`D-ING6`).

Two invariants the diagram encodes: **raw values never leave through a response** (masking is the last step before
serialisation, after every cache), and **the op log never re-reads a Dataset on replay** (rows are sealed into the
entry at write time, so an index rebuild or a Dataset change cannot alter a recorded Investigation).

## 2. Modules and dependency direction

```
                    inspecto (host: ControlApi, CollectorService, JobService, AlertService)
                         ▲ ServiceLoader (RouteModule, ports, LinkIndexBuilder, InvestigationMeasureProbe)
   la/inspecto-geo-link ─┤  bridge: engine ⇄ la-core ports        (depends on inspecto-processor, la-api, la-core, entity-list, entity-store)
   la/inspecto-la-api  ──┘  routes, gates, Drafts orchestration    (la-core, la-storage, entity-store, access, http/auth/audit-spi, config, sql, util)
        │
        ├── la/inspecto-la-storage   link index: build, read, index GraphEngine   (la-core, sql, DuckDB)
        │        │
        ▼        ▼
   la/inspecto-la-core   domain: op evaluator, InvestigationStore port + FS impl, Drafts store, Graph Run service, ports
        │                (graph, entity-store, http/auth/audit-spi, config, sql, util)
        ├── platform/inspecto-graph   pure-Java graph algorithms, RunControl, UnionFind (Base; no inspecto deps; enforcer-fenced)
        └── platform/inspecto-entity-store   Entity Types, Entity Lists, Identity Fact log, MaskTokens, LinkAnalysisSettings
   la/inspecto-la-store-pg   Postgres InvestigationStore (ServiceLoader provider)   (la-core)
```

| Module (manifest id) | Role | Key classes |
|---|---|---|
| `graph` (platform, base) | Leaf algorithm library (package `com.gamma.graph`), line-for-line port of the SPA's `graph-analysis.ts`; not LA-only since D-AN2 (2026-10-17) — a core thin jar in every edition so Fraud, AML and Case Management can use it without Link Analysis | `GraphAlgorithms`, `GraphCentrality`, `GraphIterative`, `GraphPaths`, `GraphStructure`, `GraphSuspicion`, `GraphPropagation` (server-only), `UnionFind`, `RunControl`, `GraphAborted` |
| `la-core` (platform, optional, boot) | Domain + ports, no HTTP routes | `InvestigationEvaluator`, `InvestigationStore`/`InvestigationStores`/`FsInvestigationStore`, `InvestigationMembers`, `DraftStore`/`DraftLifecycle`/`DraftCheckpoints`/`DraftIndex`, `GraphRunService`, `GraphEngine`, `InMemoryGraphEngine`, `GraphInput`, `Algorithm`, `GraphBudget`, `LinkEventTypes`, ports `DatasetProvider`/`CasePort`/`CollectorCoveragePort` |
| `la-storage` | The link index (D-3) and the index-backed engine | `IndexBuilder`, `IndexBuildService`, `IndexStore`, `IndexManifest`, `IndexMapping`, `IndexPlan`, `IndexReader`, `IndexPins`, `BucketFunction`, `IndexedTraversal`, `SqlGraphEngine`, `RoutingGraphEngine` |
| `la-api` (feature `geoLink`) | HTTP surface and orchestration | 21 `RouteModule`s (below), `EntityMasking`, `InvestigationMemberStore`, `DraftAdmission`/`DraftRebase`/`DraftPromote`, `IndexedRead` + `Indexed*`, `IndexStaleness`, `IndexBuildServices`, `GraphRunServices`, `StandingDetection`, `ScheduledIndexBuild` |
| `geo-link` | Bridge to the host | `EngineDatasetProvider`, `HostCasePort`, `HostCollectorCoveragePort`, `ScheduledLinkIndexBuilder`, `WorkingSetMeasures`, `InvestigationMeasureRoutes` |
| `la-store-pg` (provider) | Postgres backend for Investigations | `PgInvestigationStore`, `PgInvestigationStoreProvider` |
| `entity-store` (platform, base) | Space-wide identity data; not LA-only | `EntityFactLog`, `EntityRegistry`, `EntityTypes`, `EntityListEntries`, `EntityListFacts`, `EntityListSidecar`, `MaskTokens`, `LinkAnalysisSettings` |

Design rules visible in the dependency graph:

* **Ports point inward.** la-core defines `GraphEngine`, `GraphInput`, `DatasetProvider`, `CasePort`,
  `CollectorCoveragePort` and `InvestigationStoreProvider`; storage, geo-link and store-pg implement them. la-core
  never names an index class: `GraphInput.IndexRef` carries only primitives (version dir, seeds, estimates).
* **The engine names no `la-*` class** (grep-true; LA route paths appear only as strings, and enforced by `tools/check-engine-names-no-la.mjs`). The host reaches LA only through ServiceLoader SPIs it owns
  (`RouteModule`, `LinkIndexBuilder`, `InvestigationMeasureProbe`); absent jars degrade to
  `AbsentModuleRoutes` (503 `CAPABILITY_UNAVAILABLE`, "Standard edition and above").
* **Entity Lists are not LA.** `/entity-lists/*` routes live in `features/inspecto-entity-list`; LA reads the same
  fact log through the host-free `EntityListFacts` helper so neither side depends on the other's routes.

## 3. Domain core (la-core)

### 3.1 Investigation op log

* An Investigation is a header, an append-only `log.jsonl` of **ops**, and one sealed Working Set file per step
  (`sets/<step>.json`, the audit/replay record, not the evaluation input).
* `InvestigationEvaluator` is a **pure fold** of the log. The apply switch handles 15 labels: `seed`, `seedBy`,
  `expand`, `exclude`, `excludeBy`, `hide`, `keep`, `threshold`, `annotate`, `window`, `resolve`, `connect`
  (2026-10-13, below), and the markers `snapshot`, `compare`, `temporal`. An unknown op throws "not evaluable" (fail closed).
* **Sealed reads (D-E3).** `expand` stores the rows it read, `compare`/`temporal` store their findings with a
  fingerprint, so replay needs no Dataset and a Dataset change cannot rewrite history.
* **Hashing.** State hash = `sha256(canonical(workingSet))`, recorded per log entry; the prefix hash
  (`DraftStore.prefixHash`) = SHA-256 over the first *k* raw log lines and guards rebase and promote. Write order is
  log line first, then the set file is sealed (CREATE_NEW).
* **Members** (`InvestigationMembers`, `members.jsonl`, append-only grant/revoke): `lead | analyst | reviewer`, owner
  is implicitly lead. Only a lead writes the main log; lead or reviewer decides a pending expand (never the
  requester); lead or analyst writes their own Draft; a change that leaves no lead is refused.
* **Pending approvals (D-U7).** A sensitive `expand` is written as a pending request, never to the log, so
  four-eyes holds by construction; `replacePending(expected, new)` is the compare-and-set that closes the
  approve/deny race.
* **Supernode suppression (2026-10-11).** An `expand` resolves `hubThreshold` (the op's own, else the Space's
  `hub_threshold`, default 500) into its sealed rung, and seals `read.hubs [{id, degree}]`: row endpoints outside the
  frontier whose distinct-contact degree (counted as `candidateDegreeMax` counts it: both directions, allowed kinds,
  excluded pruned, in-window) exceeds it. Unlike `candidateDegreeMax` (which drops), a hub is admitted and flagged
  `highConnectivity` (state hash gains the key only when flagged, so old logs hash unchanged; Working Set relation
  column `highConnectivity`). Later frontiers leave flagged entities out (`rung.hubsHeld`; `GET .../log` carries only the COUNT as `read.hubsHeld`, so a reopened Investigation's degree line says the same words as the live one) unless the op carries the
  override `expandHubs: [ids]` or `includeHubs: true`, sealed in its params and named in the log line; a NAMED hub
  without it is a 422. The degree check is one extra flat statement over the Dataset even when the index answered the
  rows (rows and fingerprint untouched). Overriding is not a four-eyes trigger of its own: the budget / fan-out
  thresholds already bound what it can admit.
  *Follow-ups (2026-10-11):* only entities a step ADDS are hub candidates — one already in the Working Set (a seed
  outside the frontier included) is never newly flagged by another frontier's rows; this is decided when the read is
  sealed (`hubs()` drops the pre-step Working Set), and the evaluator is unchanged, so logs sealed before it replay to
  their recorded hashes. `expandHubs` ids are checked against the Working Set on append (a Draft append is the same
  code), and on a Draft promote/rebase re-seal (422 → a `blocked` conflict). Fork, template and approval do not
  refuse an absent `expandHubs` id: an override only lifts a hold, an absent id has nothing to hold, so ignoring it
  cannot widen the read (fork and template ops were validated when first appended; an approval's were validated at
  request). One "nothing to expand" refusal names the held hubs everywhere; an approval whose frontier is all newly
  flagged says so (409) rather than "left the Working Set".
  *Expanded state (LA-UNEXPANDED-MARKER-1, 2026-10-13, as built):* `State.expanded` holds every Working Set entity that an effective
  `expand` had in its SEALED `read.query.frontier` (a step that found nothing still marks it; entities the step ADDED are not in it), kept
  OUTSIDE `toMap()`/`hash()` like `hubDegree` so no hash changes, dropped with the entity on exclude, and reverted by undo because undo
  replays without the step. The Working Set entities relation gains the boolean column `expanded` (last column, after `contacts`;
  `false` = still an unexpanded node). The SPA draws from the REPLAYED Working Set, not that relation, so `replay` and the Draft replay also stamp `expanded` on each entity map AFTER `toMap()` (`InvestigationRoutes.stampExpanded`; never hashed, masked with the rest). The held hubs are never in a frontier, so they stay `expanded: false`; the SPA draws a flagged hub
  with its ring only. `InvestigationExpandedFoldTest` and `ControlApiInvestigationHubDegreeTest` pin it.
  *Restricted expand (LA-EXPAND-PICK-NEIGHBOURS-1, 2026-10-13, as built):* the `expand` op takes an optional `only` (a sorted,
  de-duplicated list of at most 1 000 neighbour ids, masked aliases resolved like `ids`/`expandHubs`), sealed in the rung (`read.query.only`,
  step result `read.rung.only`) ONLY when stated, so older rungs seal and replay unchanged. The flat read applies it as a predicate on the
  candidate (`other`) endpoint BEFORE the per-anchor fan-out rank, so `maxFanOut`, `fanOutCapped`, `truncated` and the budget count only
  the chosen neighbours; a rung with `only` is never served from the index (it is answered exactly by the flat read). It restricts the NEW
  endpoint: a frontier-to-frontier row is kept only if that member is listed. `State.expanded` is NOT set for such a rung, so the entity
  stays an unexpanded node. Four-eyes: a rung with 1..50 `only` ids is exempt (`ONLY_EXEMPT_MAX`, operator 2026-10-13: it skips both the
  budget and the fan-out clause); a longer list is judged by `budget` / `maxFanOut` as any expand. The log line and the Dossier say
  "restricted to N chosen neighbours" (`InvestigationTime.rungClause`), and a saved template drops `only` with `ids` (case data).
  `ControlApiInvestigationPickNeighboursTest` and `InvestigationExpandedFoldTest` pin it.
  *Grow preview (LA-GROW-EXCLUDE-1 phases 1-2, 2026-10-22, as built, backend only):* `POST /inv/investigations/{id}/expand/preview`
  takes exactly the `expand` op body (`op` optional, `expand` only) and runs the append's own path minus the write: the lead
  open gate (`canManageIncidents` + member lead; non-member 404), `params` + `requireBindings`, the ids-in-Working-Set check,
  `expandFrontier` (the `expandHubs` / held-frontier / empty / `max_frontier` checks, extracted so the append and the preview
  share them), the same `read` (index or flat) and the same `evaluate` over the round-tripped would-be entry. Nothing is
  appended, sealed, cached or emitted. Answer (`GrowPreview`): `addsNodes` = the delta's `admitted`, `addsLinks` = `linksAdded`,
  `held [{id, contacts}]` = the read's hubs left flagged (the append's `hubsFlagged`, busiest first), `excludedByLists
  [{listId, title, removed, rounded}]` = per `excludeBy` in force the distinct not-yet-admitted read endpoints its remembered
  keys drop, `truncated`, `readPath` (`index` when the index answered, else `flat` with the fallback's `reason`),
  `needsApproval`. Held ids are masked with the WOULD-BE log (`EntityMasking.of(inv, log + entry)`), so the pseudonym is the one
  the appended step shows. A body the four-eyes thresholds make sensitive is NOT read: `needsApproval: true`, null counts,
  `exceeded[]`, no pending request. *Restricted-list counts (plan §7):* `removed` is rounded to the nearest 5, never below 5,
  0 stays 0, for every list that does not read as open; Entity Lists carry no `visibility` yet (phase 4), so every count is
  rounded (`GrowPreview.open` is the one seam to flip). The explorer twin `POST /inv/projection/neighbors/preview` (Basic too,
  read-shaped exemption, no event) counts one neighbours step from a SET of ids with no Investigation: body `{dataset,
  sourceCol, targetCol, linkKindCol?, attrCols?, ids[] (or the neighbours read's one-entity `value`), drawn?, direction?, linkKinds?, hubThreshold?, excludeBy? [listIds], filter?, limit?}`, one
  flat statement (distinct `(s, t, k, attrCols…)` links, the neighbours read's own fold; neighbours outside `ids` and
  `drawn`; each neighbour's distinct contacts over the whole Dataset), list members matched in Java by each list's normaliser
  (unknown list 404, retired 409), `limit` caps the neighbours read (`truncated` then makes the counts lower bounds), ids in
  and out as exploration aliases. `drawn` (bounded as `ids`) names what the explorer graph already shows: those neighbours
  are not added. The answer adds `linksWithinGraph`: the step's links whose both ends are already shown (between `ids`, or
  to `drawn`), not in `addsLinks`; only the SPA knows which of them it draws, so it adds the rest (`withUndrawnLinks`) - none
  on a graph its query drew whole, the missing ones on a graph cut short by a limit. Both routes are rate-classed
  EXPENSIVE. *Remove held super nodes* needs no route: the SPA appends an `exclude` with `reason: "busy number above N
  contacts"` - `exclude` already requires and seals `reason` (unchanged). `ControlApiGrowPreviewTest` pins parity (preview
  counts = the append's delta right after, log length and Working Set unchanged), list rounding, masking, four-eyes, the
  armed gate and Basic; `theNeighboursPreviewCountsWhatTheExplorerGrowAdds` pins the explorer twin's parity (preview counts
  = what one neighbours read per frontier value adds to the drawn graph: a whole graph, a grown one in each direction, a
  filtered view, attribute columns, a graph cut short by the query's limit).
  *Neighbour list (LA-EXPAND-PICK-NEIGHBOURS-1 residuals, 2026-10-13):* the *Choose neighbours* list is the `list` form of `POST /inv/projection/neighbors`
  (`{..., value, list: true, offset?, limit?}`; no new route, so no manifest / route-gating / openapi entry changed): the server folds ONE entity's
  link rows per neighbour (events across kinds, sorted kinds), strongest first (ties by id), a self-link is no neighbour, and answers
  `{neighbours[{id,count,kinds}], total, offset, limit, hasMore}` with the REAL distinct-neighbour `total` (a window past the end carries
  `total: 0`; the SPA keeps its first page's). Page default 50, ceiling 200. It is a READ-ONLY hint and is NEVER refused by the four-eyes
  fan-out threshold (the flat form still is, 403): reading a hub's list is allowed, only the expand is held; the row-budget bound still judges
  the page size. Ids leave as aliases on a masked Space, and an alias is accepted as `value` (`ControlApiExplorationMaskingTest`). It is always
  the flat read: the index serves no listing. The approval inbox summary names a held restricted expand ("restricted to N chosen neighbours (...)",
  ids masked like `ids`), so a list longer than 50 no longer reads as a plain expand. SPA: `InvService.neighbourList`, store `neighbours`
  (`total`, `hasMore`) with `moreNeighbours()` (*Show more*). Pinned by `ControlApiInvestigationPickNeighboursTest`, `ControlApiInvestigationApprovalInboxTest`.
  *Connect seeds (LA-SEED-CONNECT-1, 2026-10-13, as built, not browser-driven):* the fifteenth op, `connect {ids (2..20), maxHops (default and ceiling 3), entityType?, budget?, linkKinds?, window?, minEvents?, hubThreshold?}`, on the existing `/ops` route (no new route). `ConnectSeeds` runs ONE bounded breadth-first search per seed over the Investigation's own one-hop `read` (index-backed when it can answer, the Dataset otherwise) and reads every unordered pair off the first seed's search: hops, shortest-route count, one example route; a pair with no route within `maxHops` is disconnected and never searched deeper. A hub is reached, flagged, never walked through, and a disconnected pair names it (`notWalked`); an excluded entity is never routed through. The entry seals `read.rows` (the example routes' links, fingerprinted as an expand's), `read.pairs`, `read.hubs`, `read.noLinks`; the evaluator seeds `ids` and admits route nodes by a multi-source walk (nearest seed wins, row order never matters) and adds no `expanded` mark. A fork and a Draft rebase re-search over the new base; the Draft `/ops` route admits it as a heavy job. Not four-eyes gated (bounded by seeds, hops and a per-level budget). *Explore next degree* = a normal `expand` over the route nodes (the SPA's *Expand from these nodes*); alternating expansion is DECLINED for now (operator 2026-10-13). A click on a connected pair's row emphasises its example route on the canvas (`InvestigationSessionStore.highlightRoute`; display only, nothing recorded). *Expanded marker, decisions (operator 2026-10-13):* a legend hide of a link kind is DISPLAY ONLY - it does not change what *Expand one hop* follows and is not recorded in the log (the legend says so); the exploration canvas (no Investigation) dots nodes no expand has followed, from a browser-side `expandedNodeIds` list reset by each run (marked only after the first expand). `ConnectSeedsTest`, `InvestigationConnectFoldTest`, `ControlApiInvestigationConnectOpTest` pin it.
  *Degree surfaced + from the index (2026-10-11, as built):* the sealed degree now reaches the client, always from the
  SEALED read, never recomputed: the evaluator keeps `State.hubDegree` (id → `read.hubs[].degree` of the LATEST step that
  sealed it — a later read re-flagging it supersedes the first; dropped with the entity on exclude) OUTSIDE `toMap()`/`hash()`, so hashes are unchanged; the Working Set
  entities relation gains column `contacts` (the degree when `highConnectivity`, else null; numeric for measures); the
  step result's `read` and the log view's `read` summary carry `hubs [{id, degree}]` (ids masked like every other id).
  The read also seals `hubDegreeSource: index|flat` (beside `hubs`, not in the rung, so a reread's rung is unchanged).
  `index` when the edge index SERVED the rows and is not stale: `IndexedExpand` counts the degree from the same
  version (`IndexReader.distinctCounterparties`: both sides per candidate, allowed kinds, excluded counterparties
  pruned — exactly the flat definition; a served rung has no window). Otherwise (fallback, stale-served, window etc.)
  the flat statement as before. Parity-tested rung by rung (`ControlApiInvestigationHubDegreeTest`). Old logs: no
  `hubDegreeSource` (and, before suppression, no `hubs`) — the fields are absent and replay matches.
* **Events weighting (2026-10-11).** Optional header binding `eventsCol` (create / template instantiate; an
  INTEGER-family column, 422 otherwise; key absent when unbound, so an unweighted header and every older one is
  byte-identical). When set, the expand CTE's pair weight is `CAST(SUM(COALESCE(CAST(eventsCol AS BIGINT), 1)) AS
  BIGINT)` instead of `COUNT(*)` — a NULL count is one row's worth — and that weight drives `minEvents`, the
  `maxFanOut` rank, the budget order and the sealed `count`. `minDistinctDays` and the degree checks
  (`candidateDegree*`, hub degree: distinct counterparties) are unchanged. A weighted rung is served from the edge
  index when an index's `weightCol` equals `eventsCol` (case-insensitive): the folds sum
  `CAST(SUM(COALESCE(CAST(w AS BIGINT), 1)) AS BIGINT)` (fingerprint parity with the flat SUM tested); otherwise it
  falls back with `read.fallback {reason: rung_not_indexable, details: "...which no index carries as its weightCol"}`
  (never a silently wrong count). Caveat: the index stores `w` as DOUBLE, so an integer count above 2^53 could round. Templates carry `eventsCol` in `roles` only
  when bound; the Dossier names it in the method statement.
* **Endpoint Entity Type (ONB-LA-ENDPOINT-TYPE-1, 2026-10-11).** `POST /inv/investigations` takes an optional
  `entityType` (both endpoints) or `sourceType` / `targetType` (each side; never both forms - 422), each an Entity
  Type in force (case-insensitive, stored as its id; unknown → 422). Sealed in the header ONLY when given. Every expand
  copies them into `read.rung` (`query.sourceType/targetType`), and the evaluator types each endpoint it admits by the
  side it was reached on - replay reads the log alone; a read without the keys admits untyped endpoints, so older logs
  and untyped Investigations keep byte-identical headers, reads and hashes. Before this, only the seed carried a type,
  so node actions keyed on type (Add to watch list) found nothing for expanded entities. Masking: under `typed`, a
  masked endpoint type masks that side's ids (unknown type → masked, fail closed). Forks inherit the header; template
  instantiate does NOT carry the fields yet. Test: `ControlApiInvestigationEndpointTypeTest`.

### 3.2 InvestigationStore port and its two backends

`InvestigationStore` (~40 methods) groups: identity (create, header, fork), sealed log
(`append(Scope, expectedVersion, …)`, `log`, `set`, `version`), members, references (bounded), workflow records
(Alert Rule binding, pending, Case link, templates), `maskKey`, cache identity, and 24 Draft methods.
`Scope(investigationId, draftId|null)` addresses the main log or a Draft with the same calls.

| | Filesystem (`FsInvestigationStore`, default) | Postgres (`PgInvestigationStore`) |
|---|---|---|
| Selection | `-Dinvestigations.backend=fs` | `=db` + `investigations.db.url` (else `inspecto.db.url`, must be `jdbc:postgresql:`), password via `SecretResolver`; provider found by `SpiSlot` (exactly one) |
| Layout | `<writeRoot>/audit/snapshots/investigations/<id>/` (header, log, sets, members, references, templates, pending, caselink, binding, `mask.key`, `drafts/`) | one schema per Space; tables `la_space`, `la_investigation`, `la_log`, `la_set`, `la_member`, `la_reference`, `la_alert_binding`, `la_pending`, `la_template`, `la_draft`; sealed bytes stored as `text`, immutability trigger |
| Concurrency | JVM monitor per directory + OS `FileChannel.lock` on `.store.lock` (two-JVM safe); `expectedVersion` check → `InvestigationVersionConflictException` | `SELECT … FOR NO KEY UPDATE` on the Investigation/Draft row in one short transaction; Space-wide Draft cap = `FOR UPDATE` on the `la_space` row |
| Promote atomicity | intent file → append → mark promoted; `recoverDrafts` finishes or undoes a half promote | one transaction; nothing to recover |
| Editions | every LA edition | Preview and Advanced only |

⚠ The FS layout's Javadoc says `<audit root>/investigations/<id>`; the code writes `audit/snapshots/investigations/<id>`. Creates, forks,
references, Case link and Alert Rule binding are last-writer-wins by design on FS.

### 3.3 Drafts (D-7)

* A Draft lives **inside** its Investigation (`drafts/<draft-uuid>/{header.json, log.jsonl, sets/, discarded.json,
  promoted.json}`); its state = the main log's first `baseStep` entries folded + its own entries, numbered
  continuously. Created by staging in a scratch dot-directory and an atomic move.
* An optional human `label` (fork body; trimmed, 1..80 chars, no control characters, else 422) is a plain `header.json`
  field, returned by fork / list / read. It is the analyst's free text: **not masked**, never in a seal or Dossier input
  (promote copies ops only; `ControlApiDraftLabelTest` pins that), and `link.draft.forked` audits its length only.
* States `OPEN → HIBERNATED → DISCARDED | PROMOTED` (`DraftLifecycle`); expiry is a discard with `expired:true`.
  Defaults from `LinkAnalysisSettings.Drafts`: 50 open per Space, hibernate after 60 min, expire after 30 d.
* Admission (`DraftAdmission`, la-api): one live Draft per member, Space cap, heavy-op permit `max(1, min(4, cores/3))`.
* Rebase (`DraftRebase`) carries effective ops and classifies conflicts `no-op | changed | superseded | blocked`;
  promote (`DraftPromote`) rebases to head and appends under the main lock, all-or-nothing, or becomes a pending
  four-eyes request when it carries a sensitive expand. **Storage is in la-core, orchestration in la-api.**
* `DraftCheckpoints` caches folded state per step (keyed on size+mtime; in memory, so a restart costs one cold fold).
  `IndexPins` (la-storage) keeps the index version a Draft read alive for 30 d.

### 3.4 Masking

`MaskTokens` (entity-store): token = `masked:` + first 16 hex of HMAC-SHA256(key, id); the 32-byte key is published
by hard link so a race loser reads the winner's key. One key per Investigation (`mask.key` in its directory) and ONE
Space identity key (`MaskTokens.spaceKey`: `<write root>.secrets/.risk-score-mask.key`, owner-only, outside every
exported, imported and backed-up tree; D-M7, 2026-10-13, moved from `audit/entity-facts/mask.key`, so a token minted
before the move differs from one minted after) shared by LA exploration, Entity Lists, Screening, BI and Risk /
Anomaly Scores. A list member's token therefore differs from an Investigation's for the same value. `LinkAnalysisSettings.maskingMode` defaults
to `typed` (fail closed). `EntityMasking` (la-api) masks on the way **out**: engine results and caches stay raw; a
raw id that masking hides is answered as a nonexistent node; `reveal` needs `canRevealLinkEntities`.

**Mask the same way everywhere (DR-D2, operator 2026-10-10).** The stateless exploration reads (`/inv/projection{,/neighbors,/multi}`,
`/inv/traversal/recursive-paths`, `/inv/pattern/*`, `/inv/value-measures`) have no Investigation, so `ExplorationMasking`
(la-api) masks them under the **Space** identity key (`MaskTokens.spaceKey`, the key an Entity List member uses; D-M7): `all`
masks every id; `typed` masks every id when a bound endpoint column's classification is claimed by a masked Entity Type
(`EntityMasking.maskedColumns`, the rule an Investigation applies); `none` shows raw. Each answer carries
`masking {mode, masked, basis}`, which the SPA shows as a *Masking: mode* badge in the query panel. An alias handed back (an
expand's `value`, a traversal's `startNode`, an Investigation seed's `ids`) is resolved from an in-memory alias-to-raw
book of what this server minted for this Space (bounded LRU; nothing raw is ever sent). The book is also appended, one
`alias<TAB>base64(raw)` line per newly minted alias, to `<root>.secrets/.exploration-alias-book` beside the Space key
(owner-only, outside every exported tree), and every in-memory miss scans that file, so an alias survives a restart
or an eviction; a line is believed only when its alias is the token of its raw value, so a torn line is skipped
(LA-EDITIONS-RESIDUALS-1 4c, 2026-10-19; `ExplorationMaskingSpaceKeyTest`). **Measured and fixed 2026-10-20
(`LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1`, `ExplorationAliasBookScaleBench`):** the per-miss scan was a half-file read on average
(109 ms per alias on a 1 M-line / 39 MB book, so resolving 200 distinct evicted aliases took 21.8 s and a 1000-node graph after
a restart about 110 s: linear in the book per alias, quadratic over a graph). A per-book-file offset index (alias to line
start, 64 bits of the token, first line wins, two `long[]`, about 32 bytes per alias) is now built by ONE pass and only
extended over the bytes appended since; each miss then reads the one indexed line and still believes it only when its alias
is the token of its raw value (a torn first line of an alias falls back to the full scan, so a later good line is found). The
same 200 aliases cost 1.5 s (one 1.2 s pass plus 200 single-line reads, 7.6 ms each); a never-minted alias costs nothing after the pass.
A book above 4 M lines (about 160 MB) stops being indexed and falls back to the scan (4.5 M lines: 0.8 s per alias, accepted: far
above the 500k in-memory bound). A repeated miss on a book of unchanged size still answers from the bounded negative cache
(any mint grows the book and re-opens the question), pinned by `ExplorationMaskingSpaceKeyTest`. Case history tries the Investigation's own
pseudonym before the book, so Investigation-alias nodes never read it. An append first ends a torn last line with a
newline, so a crash mid-append cannot swallow the next line; appends go through an `APPEND` channel as one write, so
two processes sharing a Space lose no line. ⚠ The negative cache is keyed on the file's SIZE: an outside rewrite of the
book to the same size can leave a cached miss stale until restart. The
file is not compacted: it grows by one line per distinct alias minted (plus one if an evicted alias is minted again).
An alias never minted (or whose book was removed) is 422 *alias not known* and the query is re-run. ⚠ An Investigation keeps its own key, so a seeded entity has a second alias
there: Working Set answers (`/working-set`, `/replay`, op answers) carry `exploreAliases` (Investigation alias to
exploration alias, aliases only) and the SPA matches a selected node to its Working Set entity through it. Sealed logs still
bind raw ids. Known gap: an Investigation also masks a seed's explicit `entityType`, which a stateless read does not
carry, so a column no Entity Type claims is raw on the query graph but seeded ids of a masked `entityType` are aliased in the Working Set.

**Stateless four-eyes semantics (fix 2026-10-11).** A *default* is not a declaration (2026-10-12): a `recursive-paths` call that sends no `maxEdgeYield` has its default clamped to `four_eyes_fan_out_above` (the response `fences.maxEdgeYield` says what ran) instead of being refused; one that declares a larger bound is still refused. A stateless read's `limit` is a RENDER CAP, never a fan-out: `/inv/projection`,
`/multi` and the identity import are judged only as rows against `four_eyes_budget_above`. `four_eyes_fan_out_above` gates where one
entity's expansion is the thing read - an Investigation `expand` (held for approval) and `/inv/projection/neighbors` of ONE node, judged
on that node's real counted degree (distinct links; over the threshold = 403). Traversals still judge their declared `maxEdgeYield`.

**Conditional projection read (PERF-P4-2, 2026-10-24).** The flat `/inv/projection` and `/inv/projection/neighbors` (not `list`,
not `/multi`) answer with `ETag` + `Cache-Control: private, no-cache`; a repeat with a matching `If-None-Match` gets `304` and no
projection read (`ProjectionCache`: LRU 64, TTL 60 s, process-local). One read still runs before the cache is
consulted: a `/inv/projection/neighbors` request, when `four_eyes_fan_out_above` is set, first runs the one degree
`COUNT(*)`, because that gate must judge the live degree before a cached answer is allowed. It is consulted only AFTER every gate of the read (write root,
body 422, R3 view 404, filter, four-eyes, alias resolution), and the read is still audited on a hit with the miss's own
`source` / `indexVersion` / `indexStale` / `sourceReason`. Bounds: a result over 5,000 rows or 2 MB serialised is served but not
stored (no ETag); all entries together stay under 32 MB of SERIALISED bytes, evicting least-recently-used by bytes. ⚠ That
figure counts the JSON, not the heap: measured retained heap is about 5.8x the counted bytes unmasked and 4.7x masked, so the
real footprint at the bound is roughly 130-190 MB. Every store first sweeps the expired entries; an expired entry is never
served, but it stays in memory until the next store sweeps it (not at the TTL). The key (and so the ETag)
hashes: route, Space, viewer (Subject id, capabilities, data scopes, attributes, held roles), canonical body, resolved limit, the
Dataset registry entry and relation SQL, its FRESH input fingerprint, the LA settings in force (masking mode, Entity Types,
thresholds, index, caps), the Space masking key, and for neighbours each mapping's published index version. Only a `files:`
fingerprint is cached - a view, a Postgres store, too many files or a timed-out listing is read every time. Limits, stated:
(1) the fingerprint sees path, size and mtime (ms) only, so an in-place rewrite that keeps BOTH size and mtime (an
mtime-preserving copy) is served from the cache until the TTL ends; (2) a hit does not re-run masking. This adds no exposure of its own:
`ExplorationMasking.lookup` answers from the in-memory alias book first, and `remember` appends to the book file only
for a pair this process did not already hold, so a MISS does not re-write a deleted book file either. A pair the process
never held is simply unknown (422 "run the query again") whether the read was a hit or a miss; (3) the relation SQL is derived from the registry entry for every cached
(files-backed) Dataset, so it is never the only key input that changes. The executor's outer `LIMIT n+1` is
already planned by DuckDB as a Top-N over the group-by (no full sort), so the projection needs no inner limit.

**Conditional Working Set read (PERF-P4-3, hardened 2026-10-25).** `GET /inv/investigations/{id}/working-set` answers with a strong `ETag`
built from the store's `logToken` (a stat), the verified content key, table, page, pin and masking profile; a matching `If-None-Match`
is a bodiless `304` after the gate. Four rules keep it honest: (1) the token is taken BEFORE the log is read and re-checked AFTER the read
and the fold - if it moved (a failed promote or Draft append truncating inside one coarse mtime tick) the freshly read answer is served as a
correct `200` UNCACHED with NO ETag, so an old token is never cached against newer content; (2) a cached relation is trusted for 30 s on a
MONOTONIC clock (`System.nanoTime`), so a backwards wall-clock step cannot stretch the window before the full SHA-256 revalidation; (3) the test
seams (`WorkingSetRoutes` clock/window/`FULL_READS`, `FsInvestigationStore.LOG_READS`) are package-private, reached from other modules only
through same-package accessors in the test source sets; (4) an error response of any status never carries an ETag - `WorkingSetRoutes` tags
only around a successful serialisation and `ControlApi.respond` withdraws any tag from a status of 400 or above (this also covers the shared `ETags.respond`).


**Reference rows (`POST /inv/reference-rows`, 2026-10-11).** Body `{dataset, keyCol, keys[1..500], columns?, investigationId?}`
→ `{dataset, keyCol, columns, rows:[{key, row}], matchedKeys, unresolved, rawKeysRefused, truncated, masking{…, maskedColumns}}`. The node
dialog's Risk / Enrichment and the panel's *Fill nodeScores from indicators* look entities up through it instead of reading a
whole Dataset over `/db/query` (which masks nothing). Gates: 503 write root → 422 body (`keyCol`/`columns` safe identifiers)
→ 413 over 500 keys → with `investigationId` the Investigation read gate (404) → `relationFor` R3 (404) → four-eyes row
budget on the key count (403) → 422 for a column the relation lacks. Identifiers are quoted; keys are bound `?` parameters
shaped so DuckDB can prune row groups by min/max (`typeof` of one row picks the form): a VARCHAR key is compared directly
(`key IN (?, …)`), an integer key gets typed binds (`key IN (CAST(? AS BIGINT), …)`) when every key is the canonical decimal
of an in-range value, and anything else falls back to `CAST(key AS VARCHAR) IN (?, …)` (same rows, never a failure), at most 2 000 rows (`truncated`). Keys IN (only when the exploration rule masks `keyCol` - otherwise an alias is unresolved, so it cannot be matched against an arbitrary column as a membership oracle): an Investigation pseudonym
(`EntityMasking.reveal`) or an exploration alias (the book) resolves; under masking a RAW key names nothing (counted in
`rawKeysRefused`, never echoed, like `/inv/graph/runs`' hidden raw id), so the route is no raw-id oracle. Rows carry the key AS ASKED. Values
OUT, `none` shows raw; otherwise the key column is masked when the exploration rule masks `keyCol` or any key was an alias,
and any column whose registry/lineage class is claimed by a masked Entity Type or is `ColumnClassification.SENSITIVE`
(MSISDN/IMSI/ACCOUNT/PII) is masked, and under `all` every value but fractional numbers and booleans (an integral column may be an unclassified id), to the Space's exploration alias; untraceable lineage masks every column. Audited as
`LINK_REFERENCE_READ` (counts only, never the keys).

**Case history (`POST /inv/case-history`, 2026-10-11).** Body `{key, investigationId?, status?, limit?}` →
`{entityType, resolved, reason?, objects, total, truncated, masking{mode, masked}}`. The node dialog's Case history for a
key it holds only as an alias (`GET /objects/by-entity` needs the raw id). The key resolves with the reference-rows rules:
an Investigation pseudonym (readable Investigation only), else an exploration alias, and only when the masking covers the
Entity Type (`all`, or `typed` with a masked type). A raw key resolves only under mode `none`; under any other mode it
names nothing, whatever the header type (`resolved:false`, the same answer as an unknown alias — no oracle;
`LA-CASE-HISTORY-RAW-KEY-1`, 2026-10-19). So under `typed`, an uncovered type (e.g. `cell`) has no case history here. The read applies the `ObjectReadMasking.hides` filter
(D-P8, `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1` (1)): `EntityHistory.query` (the 7-argument public form `ObjectServiceAccess.byEntity` calls) builds the
filter from the Space root and the caller itself, as does the `GET /objects/by-entity` route, so a caller without `canRevealLinkEntities` gets no object
matched through a classified key and no seam caller can skip it. Entity Type = the Investigation header's `entityType`/`sourceType`/`targetType`,
else `msisdn`. The read is the ops module's own (`ObjectAccess.byEntity` → `EntityHistory.query`, reached through
`CasePort.history` / `HostCasePort`): same matching, SEC-7d / ABAC post-filter and `truncated`. No key is echoed; under
masking titles are scrubbed fail-closed: the raw id in any spelling (spaces, dashes, dots, parentheses, `+`) becomes the
key as asked, every other token of 5+ digits `[number]` and an email `[email]` (placeholders, never minted aliases), and
rows carry only an allowlist of fields.
**Attached Cases (fix, 2026-10-11).** Attach to Case (seal → `POST /inv/snapshots/attach` → `PUT …/case`) registers no
entity reference on the host, so the host's read alone missed the very Case the analyst attached the graph to. The route
now also lists, deduplicated against the host rows, each Case linked to an Investigation whose current Working Set
contains the entity (`matchedOn:"investigation"`, `via` = Investigation id) and each Case a sealed snapshot holding the
entity among its `nodes` was attached to (`matchedOn:"snapshot"`, `via` = snapshot id; alias / pseudonym node ids are
resolved server-side). Fail closed: only Investigations the caller can read (`openForRead`; a snapshot is judged by the
Investigation it is anchored to, an unanchored one is never listed) and only an existing Case the caller may see
(`CasePort.summary` + `visibleTo`); same status filter, title scrub and field allowlist. Bounded: at most 100 Case-linked
Investigations and the newest 500 attachment records are read; beyond that `truncated:true`.
Gates: 503 write root → 503 Case management not installed → 422 body → Investigation read gate (404) → 422 unsearchable
key (message never names it). Rate class expensive; audited as `LINK_CASE_HISTORY_READ` (counts only).

**Entity profiles (la-entity-profile-plan lane A, 2026-10-15; la-api `EntityProfileRoutes` + `EntityProfiles`).**
`GET /inv/entity-types` (types, bindings, cached headline; never a query), `GET /inv/entity-types/{id}/profile?sections=`
(aggregates only) and `GET /inv/entities/{type}/{key}/profile`, shapes fixed by plan §4.5. Seams and gotchas:
* **Bindings** (`EntityTypeBindings`): a type binds every viewable Dataset column whose `classification` it claims; `role`
  is source/target when a saved `link-analysis-view` (`query.projection`, `projections[]`, `multi.edges[]`, read through the
  new `DatasetProvider.linkAnalysisViews` port) or a published index manifest names the column as a link end. Time column =
  the Dataset's `eventDate`, else an index mapping's `timeColumn`.
* **One Entity = the normalised key, counted in DuckDB** through `EntityKeySql`, the SQL mirror of `EntityTypes.normalise`;
  `EntityKeySqlTest` runs the shared normaliser parity fixture through it (sharp s needed an explicit fold: DuckDB's `upper`
  keeps it). Every readable binding joins ONE `UNION ALL` relation, so distinct counts and the timeline span Datasets.
* **Shared column profile** (`ColumnProfile`): the overlap profile's former private `ColumnStats`, now also the per-Dataset
  pass of the bindings section.
* **Reads run in UTC** - DuckDB knows `UTC`, not the `Z` that `ZoneOffset.UTC` names (a `Z` zone fails every time read).
* **Cache** keyed by every bound Dataset's `files:` input fingerprint (`InputFingerprintCache`, so a new File is seen within
  its TTL); a view-backed or `no-files` Dataset is never cached. The headline is kept per caller view of the bindings.
* **Links** come from `LinkStatsSource`s in order; only `DatasetLinkStats` (`from:"dataset"`) exists until the index manifest
  carries link statistics (plan §4.2). Limits: the `entity_profile.*` items of the catalogue.
* **A raw identifier never travels in a URL** (operator 2026-10-15): for a type masked under the Space's mode the Entity
  route takes ONLY the alias and refuses a raw value 422 before reading anything, without repeating it; `POST
  /inv/entities/{type}/resolve {key}` (raw value or alias, in the body) answers the alias of the normalised key - the same alias
  the exploration reads mint - or, for an unmasked type, the normalised value. The envelope's `links.self` echoes the request
  path, which is why the rule is enforced at the route.
* **The Start box's type-ahead** (LA-ONBOARD-1 phase 6, OB-9; `reveal` §6.12): `POST /inv/entities/{type}/suggest {prefix?, dataset?, limit?, reveal?}`
  runs over the same union relation, restricted to the type's LINK-END bindings (`source` / `target`; attribute columns never
  start a graph) and optionally one Dataset: values whose raw spelling or normalised key begins with the prefix, busiest
  first, with their link rows and first / last seen, plus `distinct` (Entities matching; a value that normalises to nothing
  is no Entity). A masked type lists nothing, and is counted only for an EMPTY prefix - a count per prefix would spell a raw
  value out character by character. The prefix travels in the body; the read is a `read-shaped` exemption like resolve.
Audited as `LINK_ENTITY_TYPES_LISTED`, `LINK_ENTITY_TYPE_PROFILED`, `LINK_ENTITY_PROFILED`, `LINK_ENTITY_KEY_RESOLVED`,
`LINK_ENTITY_SUGGESTED` (never a key, a prefix or a value). Rate class: the list exempt; both profiles, resolve and suggest expensive.

**Start from... (LA-ONBOARD-1 phase 6, as built 2026-10-22).** The SPA's pure half is `link-analysis/start-from.ts`
(`startMapping` precedence: `defaultLinkMapping` > a Domain Profile `investigate` mapping over the Dataset > the first saved
single-mapping view > the classified `source` / `target` columns; `startCatalogue` over the Datasets, views and
`GET /inv/entity-types`; with the registry unreadable only the primary types start). `LaDataset` (and the console's `Dataset`,
read-only) carry `primaryEntityType` and `defaultLinkMapping`. An explorer start is `drawStart`: one neighbours read per value
(at most 25, five at a time) merged into a NEW graph, the Grow preview's `held` set as the basic held items, the values marked
expanded, and the run remembered over the mapping WITHOUT a filter, so Grow continues from the ring. One entity on the
Investigations level goes through `prepareInvestigation`: the Domain Profile binding wins (override), else `genericBinding`
(the start Dataset's mapping typed by the seed, server-default rung). **F-8 root cause** (traced 2026-10-22): an Entity
profile's *Investigate* navigates `?seed=<key>&entityType=<type>&dataset=<where[0]>`; on the Explorer level
`prepareInvestigation` returned at once with "Investigations are available in Standard and above…", queued nothing, ignored
`dataset`, and the stream dropped the three params; the empty state's only action was *Choose a Dataset* →
`exploreDataset()` → the Query panel's guided picker, whose pick ran an unseeded `POST /inv/projection` of the whole
Dataset. The seed never reached any read. On Standard an `account` fared no better: no profile investigates it, so the
same picker drew the whole Dataset with the seed merely queued. Both now draw / bind from the entity (spec
`link-analysis.component.spec.ts` "F-8: …", falsified against the old branch). `?dataset=<id>` alone (Add data's *Explore in
Link Analysis*, phase 4) preselects the Dataset in the Start box when it can start a graph, else picks it in the Query panel
as *Explore a Dataset* does.

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
  first import reads `+N rows`; a re-import reads the entry's `lastImport` (§6.10, 2026-10-28): *replaced …, 2,104 → 2,300
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

* **Identity Fact log** (`EntityFactLog`): `<Space config root>/audit/entity-facts/`, one file per fact
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
  the neutral vocabulary. ⛔ A pack **relabels; it never adds a concept**: every term is keyed by a canonical concept
  key (`concept.node|link|row|link_from|link_to`, `entity_type.<core type>`, `link_kind.<kind the pack lists>`,
  `measure.<Value Measure>`, `pattern.<pattern pack the pack lists>`, or `term.<id>` for a glossary-only entry such as
  a fraud typology), and a key that names nothing in the core refuses the whole pack.
* **Format** (`IndustryPack`, `IndustryPacks`, in `inspecto-entity-store`, a base module, so every edition ships the
  packs, Basic included, ED-5). Each jar may carry an index `META-INF/inspecto/industry-packs.toon`
  (`packs[n]: a,b`); each pack is four files under `META-INF/inspecto/industry-packs/<id>/`: `industry-pack.toon`
  (id, name, description, `space_templates`, `pattern_packs`, `profile` = measure / time column hints, suggested tool
  groups, the optional "Investigate a number" binding and the optional `suggested_lists`, LA-ONBOARD-1 phase 7), `industry-pack-terms.toon`
  (`terms[n]{key,label,abbreviation,definition}`), `industry-pack-entity-types.toon` (core Entity Types restated:
  normaliser from the closed set, masked, classifications; an id the core lacks is refused) and
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
  DuckDB version changed, input files unknown, removed or changed.
* **Staleness** (`IndexStaleness`, la-api) compares input-file fingerprints from `DatasetRelation.inputFiles`
  (platform engine); a view-backed Dataset has no file list (`fingerprintKnown=false`) and is never called stale —
  hence D-ING6: dimension data is joined at read time, never baked into the index.
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
  ([Analytics Kernel § Temporal motif matcher](../analytics/spec.md#temporal-motif-matcher)), so AML, Mobile Money
  and Fraud can match structuring rings without Link Analysis. LA keeps `PatternQueryCompiler` (stage parsing and its
  422s, the SQL prune over the whole Dataset, key normalisation), its `Stage` with edge kind, node kind and threshold
  band, and `Stage.motif()` — the mapping onto the matcher's neutral stage, where a node kind other than `entity`
  admits no nodes. `PatternRoutes` (la-api) still masks, labels and shapes the answer.

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

24 `RouteModule`s in la-api plus `InvestigationMeasureRoutes` in geo-link, **104**<!--count:la-routes--> routes (**97**<!--count:la-api-routes--> in la-api + **7**<!--count:geo-link-routes--> in geo-link;
the live `GET /audit/route-inventory` lists 78 for `/inv` and `/geo`, all present in `openapi-v1.json`):

| Area | Routes | Write capability |
|---|---|---|
| Exploration | `/geo/projection`, `/geo/routes`, `/inv/projection{,/neighbors,/multi}`, `/inv/schema/*`, `/inv/traversal/recursive-paths`, `/inv/reference-rows`, `/inv/case-history`, `/inv/pattern/{branching,temporal}`, `/inv/value-measures`, `/inv/snapshots*` | none (rate-limited as expensive); snapshots POST `canManageIncidents` |
| Investigations | `/inv/investigations` create/list, `…/{id}/ops`, `/undo`, `/reorder`, `/replay`, `/log`, `/working-set`, `/coverage`, `/compare`, `/references`, `/case`, `/members`, `/template`, `/inv/investigation-templates*` | `canManageIncidents` (+ lead) |
| Four-eyes / reveal | `…/pending/{p}/approve|deny`, `GET /inv/pending` (approver inbox), `…/reveal` | `canApproveLinkExpansions`, `canRevealLinkEntities` |
| Dossier | `…/dossier`, `/dossier/verify`, `/dossier/bundle`, `/dossier/bundle/verify` | read gate only |
| Drafts | `…/drafts` create/list, `…/drafts/{d}` + `/log`, `/working-set`, `/replay`, `/conflicts`, `/ops`, `/undo`, `/discard`, `/rebase`, `/promote` | `canManageIncidents` + per-role act check |
| Identity | `/inv/entity-identities`, `/group`, `/import`, `/{id}/retract` | `canManageIncidents` (the reads too; `/import` has no UI) |
| Graph Run | `/inv/graph/runs`, `/algorithms`, `/runs/{id}`, `/cancel` | `canRunLinkGraphAnalysis` on the POST that starts a run; list/get are read-gated; cancel is owner-or-admin, else 404 |
| Analyst upload | `POST /inv/uploads/{collector}?name=x.csv[&replace=true]`; `GET /inv/uploads` (upload-enabled Collectors), `GET /inv/uploads/{collector}` (own uploads + ingest state), `DELETE /inv/uploads/{collector}/{file}` (withdraw a pending upload) | `canUploadData` (§6.6); the list is the caller's own, the delete is uploader-or-`canAdminister` |
| Index | `/inv/index`, `/inv/index/builds`, `/builds/{id}`, `/cancel` | `canBuildLinkIndex` on the POST that starts a build; the reads and cancel are not capability-gated |
| Entity profiles (2026-10-15) | `GET /inv/entity-types`, `GET /inv/entity-types/{id}/profile`, `GET /inv/entities/{type}/{key}/profile`, `POST /inv/entities/{type}/resolve`, `POST /inv/entities/{type}/suggest` (2026-10-22, the Start box) (la-api `EntityProfileRoutes`) | none beyond a subject (like the exploration reads); Dataset view gate per binding; profiles rate-limited as expensive |
| Data trust (LA-DATA-TRUST-1, 2026-10-14) | `GET /inv/datasets/{id}/files` (one row per File: outcome, event day, rows, error rows, index state; la-api `DatasetFilesRoutes`), `GET …/{id}/data-changes` (corrected-day warning; geo-link `InvestigationDataChangesRoutes`), `…/{id}/coverage` | read-only; Dataset view gate / Investigation `open` gate. As built: [frontend concept §Data trust](user-guide-link-analysis.md) |
| Embed link | `…/{id}/embeds` (POST mint, GET list), `…/embeds/{grantId}/revoke`, and the public `GET /public/investigations/{token}` | `canShareInvestigation` on mint and revoke (+ lead role); the list is read-gated; the public GET is authenticated by its token alone (§6.2) |
| Measures & detection (geo-link) | `…/{id}/measures`, `…/alert-rules`, `…/standing-detection` | `canAuthorAlertRules` |

Capability constants are in `platform/inspecto-access` `Roles`. OpenAPI fragments
(`META-INF/inspecto/openapi.fragment.json` in la-api and geo-link) merge into `openapi-v1.json`; a new route also
needs `CapabilityManifest`, rate-class and auth-gate coverage (see the `endpoint` skill).

**A Dataset read failure is translated in one place (`DatasetReadFault`, la-api).** A file-backed Dataset whose store holds no files yet answers a closed `409 CONFLICT` ("Dataset 'X' has no data yet: nothing has been ingested into its store (S). Run the Pipeline that fills it, or check the Dataset's physicalRef.") on every exploration read (projection, neighbors, multi, traversal, pattern, value measures, geo, reference rows, identity import); any other driver message stays a 422 but loses the JDBC preamble and every absolute path (`<path>`). The SPA's `invErrorMessage` adds a one-line hint.

### 6.1 Gate order for an `/inv` request

1. `ControlApi.authenticate` → Subject (401 audited).
2. `rateLimit` → `LinkAnalysisRateClasses`: the expensive routes (the `EXPENSIVE` table in `LinkAnalysisRateClasses`, about twenty entries; the earlier "12 POSTs" figure was stale) share a per-subject `linkAnalysis` bucket (capacity 30,
   refill 1 per 2 s; install limits `rate.link_analysis_burst` / `_per_s`, else `control.rateLimit.linkAnalysis.*`; 429); Investigation routes are exempt (pinned by `LinkAnalysisRateClassCoverageTest`).
3. `authorize` → host ABAC PEP.
4. `requireModuleEnabled` → feature `geoLink` in the Space's `modules.toon` (404 `MODULE_DISABLED`); then the host's
   idempotency check, before the handler. ⚠ The gate only covers a route whose `RouteModule` overrides
   `featureIds()` → `geoLink`; as of 2026-10-12 all 21 LA route modules (20 in `la-api` + geo-link's
   `InvestigationMeasureRoutes`, whose manifest now declares `features[1]: geoLink`) do — a new LA route module
   must too (pinned by `ControlApiGeoLinkModuleGateTest`).
5. `ApiContext.withCapability` → 403. ⚠ A no-op when there is no Subject (Basic / tests without an armed
   Authenticator).
6. `InvestigationRoutes.open`: write root (503) → safe id (422) → header (404) → membership role (`Need`
   READ / LEAD / APPROVE; a member of the linked Case also passes READ) → R3 Dataset visibility (`ComponentAccess.canView`, 404) → Advanced PDP D-E7
   (`RowScope.visible`, can only narrow, 404) → deferred role refusal 403. **Non-members get 404-as-absence; a
   member lacking the role gets 403 only after R3 and the PDP have spoken.**
7. Handler → `EntityMasking` on output → audit (`LinkEventTypes` via `EventSink` + `AuditTrail`; ≥500 and 401/403
   recorded by `ControlApi`).

### 6.2 Embed links (LA-EMBED-VIEW-1: API S1-S3 built 2026-10-12; the SPA shell S4 and the frame headers S5 built 2026-10-13)

A **lead** mints a read-only URL to ONE Investigation for someone with no login (operator decisions D1-D12, 2026-10-12).
Code: `EmbedGrants` (token, hashing, resolve, revoke, counters), `InvestigationEmbedRoutes` (mint / list / revoke),
`PublicInvestigationRoutes` (the public GET), grant records behind `InvestigationStore` (`createEmbedGrant`, `embedGrant`,
`replaceEmbedGrant`, `embedGrants`; the file implementation only - another backend answers 503 until it implements the
`EmbedGrantStoreContract`).

* **Token** `<grantId>.<secret>`: grantId 16 URL-safe chars (the public half, names the record), secret 256 random bits; only the
  SHA-256 of the whole token is stored and compared in constant time; shown once at mint; never in a list, a log line or an audit row.
* **Storage is a sidecar, never the op log**: `investigations/<id>/embeds/<grantId>.json` plus a one-line pointer
  `embed-grants/<grantId>` (the lookup starts from a stranger's grant id, which `EMBED_GRANT_ID` keeps from ever becoming a path).
  Minting, using and revoking never move the Dossier root or any sealed byte (pinned by test).
* **The holder is not a Subject.** `EmbedGrant` (auth-spi) is stamped as `ATTR_EMBED_GRANT` by the one public route; no capability gate
  accepts it, and a session sent on the public path is dropped. `PublicPaths` (auth-spi) is the registry that replaces the hard-coded
  `startsWith` in `ControlApi.isSelfVerifyingPublic`: the module registers `/public/investigations/` when its routes register.
* **Redaction.** `PublicPaths.redact` keeps the token out of audit rows (`AuditTrail`), ERROR/debug log lines, the static-fallback log and
  `links.self` in the envelope. `ControlApi`'s per-token rate bucket is keyed by a hash of the path, never the token.
* **One answer.** Disabled, malformed, unknown, tampered, expired, revoked, deleted, minter-not-lead, Case closed and masking loosened are
  the same `404 NOT_FOUND "not found"`; the reason goes to the `LINK_EMBED_DENIED` audit row with an 8-hex hash prefix. (A Space with `geoLink`
  disabled answers `MODULE_DISABLED` like every LA route: that reveals a module setting, not a token.)
* **Masking follows the Space** (`maskingMode`, D3): the view is `InvestigationRoutes.masked` over the evaluated sealed log - one masking
  path. A link auto-revokes if the Space LOOSENS masking after the mint (a stricter mode never revokes). Reveal, pending expands, drafts,
  the Case, Dataset names and SQL are not in the view.
* **Expiry** (D4): default 72 h, default cap 30 d (720 h), `embed.default_ttl_hours` / `embed.max_ttl_hours` in `link-analysis.toon`; a request
  above the Space's cap is 422, never clamped; a Space admin may set `max_ttl_hours` anywhere from 1 h up to the validation ceiling of 8760 h (365 d; 422 above in the Settings API), and `default_ttl_hours` may not exceed it. `embed.refuse_classifications` refuses a mint (422) when the
  strictest classification of the Investigation's bound columns is listed.
* **Expiry sweep** (LA-EMBED-EXPIRY-SWEEP-1): `EmbedExpirySweepModule` (inspecto-geo-link, a route-less `RouteModule`) runs a daemon timer every 10 minutes over every Space whose `geoLink` module is on and calls `EmbedExpirySweep.sweep`, which drops the token hash of every expired grant (row and audit trail stay); `-Dla.embed.enabled` off = it does nothing; a failing Investigation or Space never stops the tick. Mint and list still purge on their own.
* **Who** (D6/D11): `canShareInvestigation` AND a lead of the Investigation; the Advanced PDP is asked the new ABAC action `embed`
  (`RowScope.allowed`, resource carries `classification`); `ControlApi.actionFor` maps both routes to `embed`. `-Dla.embed.enabled` (default
  off) is the kill switch: mint 503, every public request the uniform 404. At most 20 live links per Investigation.
* **Auto-revoke** (S3): the minter's membership revoke flags their links at once; every public read re-checks that the minter is still a
  lead, the linked Case is open and masking is not looser, and flags the grant when not. Expired grants lose their hash on the next mint or
  list (the row stays for the audit). There is no Investigation delete or close route today, so "closed" means its linked Case closed.
* **Scope and per-link masking** (S4): a mint body may carry `scope` (`working_set` | `with_notes` | `full`, default `full`; the Working Set is always shown) and `masking_mode`
  (`none|typed|all`, default the Space's; a mode LOOSER than the Space's is 422, so a link can only be stricter). Both are stored on the grant (`scope`, `maskingMode`), listed in the summary, and the
  view masks at `EntityMasking.stricter(Space now, link)`. The auto-revoke check still compares only the Space's mode with `maskingModeAtMint`, so a stricter Space never kills a link and a
  link stricter than its Space is not revoked by the Space staying loose. A grant minted before scopes existed reads as `full` and its Space's mode.
* **Frame headers** (S5, `FrameHeaders` in `inspecto/control`): (1) `-Dcontrol.frameDeny=true` (default OFF, read per request) puts `Content-Security-Policy: frame-ancestors 'none'` + `X-Frame-Options: DENY`
  on EVERY response except the embed surfaces (`/embed/*` the viewer page, `/public/investigations/*` the data route); the Dossier HTML's own CSP also carries `frame-ancestors 'none'`.
  `ControlApiFrameHeadersTest` pins "flag on changes every non-embed response and never an embed one, flag off changes none". (2) The viewer page `/embed/**` is always served by `ControlApi.writeEmbedShell`:
  the SPA `index.html` with every `<link href="https://...">` removed (no font host, not even a preconnect) Angular's inline `onload` stylesheet handler dropped, and every inlined `@font-face` that names another origin dropped (Angular inlines the Google font CSS into the built index; `font-src 'self'` refused each one, so the console was noisy; the viewer now uses system fonts, and the Material Icons ligature font is not loaded - the viewer draws no `mat-icon`), under
  `default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'self'; form-action 'none'; frame-ancestors <list>`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store`, nosniff. `<list>` = `embed.frame_ancestors` of `link-analysis.toon` (Settings page + `PUT .../settings/link-analysis` `embed.frameAncestors`; canonical `https://host[:port]`, `http` only for
  localhost, no wildcard/path/query; a bad stored entry is DROPPED, a bad API entry is 422) - the allow-list of the TOKEN's OWN Space only (2026-10-14: the page path `/embed/inv/<token>` carries the token, so `writeEmbedShell` asks
  `EmbedShellSpaces.spaceOf(token)` in `inspecto-auth-spi`, which `PublicInvestigationRoutes` backs by resolving the token against every attached Space's grants; Space A's list never admits Space B's origin);
  a token that is not a LIVE grant (unknown, wrong secret, revoked, expired, kill switch off) frames for nobody, so a revoked link in a portal iframe is blocked by the browser rather than shown as *not available*; empty = `'none'`. `X-Frame-Options` is sent only when the list is empty (it cannot name an allow-list). The data route stays `frame-ancestors 'none'` always (a framed JSON answer is useless).
  (Decision D2 "link-only" is superseded by the 2026-10-13 operator call that the consumer is a real customer portal.)
* **Headers** on every answer of the public route: `Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY`,
  `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, no CORS, no cookie (D2: link-only, no per-grant
  allowlist). Throttled per token and per IP (`control.rateLimit.embed.*`, burst 60 then 2/s per link; the IP bucket is the callback one).
* **Gotchas.** The host refuses to start with a mutating handler on the public path (CapabilityManifest "undeclared mutating route"), so
  GET-only is structural. A grant id differing only by case aliases a file on a case-insensitive filesystem; the full token hash still decides.
  Audit events: `LINK_EMBED_MINTED | REVOKED | VIEWED | DENIED` (never the token, the label or an entity id).
* **Audit rows, pinned and live-driven (2026-10-14).** Each successful read writes one `LINK_EMBED_VIEWED` (`actor` `embed:<grantId>`, `investigationId`, `grantId`, `issuedBy`, `step`, `uaHash`); each refusal one `LINK_EMBED_DENIED` (`reason`, `presentedHash`, and `grantId` / `investigationId` when the token names a known grant - revoked, expired, purged, minter-not-lead, masking loosened); an auto-revoke also writes `LINK_EMBED_REVOKED` (`revokedBy` `system`, the same `reason`). None carries the token, the label or an entity value. ⚠ The hash key is `presentedHash`, NOT `tokenHash`: the audit sink redacts any attribute whose key contains `token`, so the old name was always `***redacted***`. ⚠ The public path names no Space, so the host binds none; `PublicInvestigationRoutes` binds the grant's own Space (`ApiContext.spaceIdOf`) once the token resolves, or these rows reach only the in-memory global log and no Space's event store (refusals of an UNKNOWN or MALFORMED token have no Space and stay global). Pinned by `ControlApiInvestigationEmbedTest` (field by field, minter-not-lead and masking-loosened end REVOKED by `system`) and `ControlApiEmbedMultiSpaceTest` (rows land in the grant's Space only). Live (Advanced demo bundle, two Spaces with different `embed.frame_ancestors`): the viewer page is framed by the token's OWN Space only (`frame-ancestors https://portal-a.example` vs `portal-b`, `none` for a revoked or forged token, no `X-Frame-Options` on a live viewer page), the console is clean with no third-party request, masking is as minted (`all`), loosening the Space to `none` revoked the `all`-minted link on its next read (`MASKING_LOOSENED`, `system`) and the token is in no host log line. A revoked link is a 404.

### 6.3 Entity profile routes (as built 2026-10-15)

Plan archived: [`la-entity-profile-plan`](../../../../archived-documents/plans-archive/la-entity-profile-plan.md) (decisions D-EP1..D-EP8, contract §4.5, live pass §10).

* **Routes** (la-api, gated and audited like the exploration reads): on `EntityProfileRoutes`, every edition, `GET /inv/entity-types`
  (cheap, reads the cache only) and `POST /inv/entities/{type}/resolve {key}`; on `EntityPageRoutes`, Standard and up (`entityPages`,
  D-LAE4), `GET /inv/entity-types/{id}/profile?sections=` (bindings, volume, shapes, time, lineage, links, relationships) and
  `GET /inv/entities/{type}/{key}/profile`. Audit events `LINK_ENTITY_*` (three).
* **A raw identifier never travels in a URL.** The SPA resolves a typed value with the POST and navigates by the key it returns; for a masked type
  the GET is alias-only (`masked:<16 hex>`) and answers 422 to a raw key. Type pages are aggregates only (D-EP3): a dominant value is reported as
  `kind:"dominant"`, `value:null`; only the built-in sentinels (`''`, `-99`, `0`, `NULL`, `null`, `unknown`, `n/a`) are shown by value.
  **Dominance counts a value the normaliser rejects by its raw (trimmed) text (2026-10-18)**, so a name filling a number column still
  raises the sentinel (seen on the IMS Space); built-in sentinels and blanks never dominate. The SPA never rounds a share short of
  all up to 100 % (`percent()` caps it at 99.9 %: a 99.95 % one-to-one share sat beside a maximum of 15 per Entity).
* **Bindings** (`EntityTypeBindings`): every Dataset column the caller can view whose classification an Entity Type claims; `role` is `source` /
  `target` when a saved view or index mapping names it as a link end, else `attribute`. One Entity is the normalised typed key (`EntityKeySql`, D-EP5).
  **Parties (2026-10-16):** a Dataset column may carry `party` (free text, trimmed, lower-case; the binding shows it). Relationships
  and an Entity's related identifiers pair two columns of a row only when their parties match or either has none, so a served IMSI
  pairs with the served MSISDN and not with the other party's number (that pairing counts contacts, and on a 132 M-row day it ran
  past the 600 s timeout even sampled). A Dataset that names no party pairs every column as before.
* **Statistics** come from one `ColumnProfile` aggregate per Dataset; every approximate distinct is capped at its row count (the sketch overshoots
  on small counts: 109 distinct over 104 rows before the cap). Links read the index first (`IndexLinkStats` over the manifest's `kindEdges`,
  `typeNodes`, `degree`, `timeSpan`), else a Dataset query under the budget, else `assessed:false` (D-EP4). Every section carries `assessed` /
  `reason`; nothing unmeasured is reported as zero.
* **Cache** keyed by the input-file fingerprint (D-EP2); a view-backed Dataset has none and is read each time under `entity_profile.timeout_s`.
  Cold read 2.2-5.4 s on the IMS Space (29,650 rows), cached about 200 ms. **A section whose read FAILED is answered but never cached**
  (`assessed:false` plus `retryable:true`; a section that does not apply has no `retryable`), so the next read retries it.
* **Production scale (2026-10-16, a 132 M-row day).** The profile used to run under the 1 GB sandbox default and its dominant-value and
  relationship reads ran out of memory, then the failure was cached. Now: (1) profile SQL is trusted, so it runs under its own install-wide
  budget `entity_profile.memory_limit_mb` (default 4,096); (2) above `shape_sample_rows` the dominant value is read from a ROW reservoir
  (`dominantSampled`, `sampleRows`; a row sample, not an Entity sample, because a value holding a fifth of a column is certain to be in it);
  (3) a relationship read that still runs out of memory, or runs past `entity_profile.timeout_s` (MSISDN to IMSI over the
  132 M-row day exceeded even the 600 s ceiling), is retried over an Entity HASH sample, 1 in 64 (`sampled`, `sampleEvery`,
  `approximate`: each sampled Entity's count is exact, the counts are scaled up); (4) an out-of-memory reason reads "ran out of its memory
  budget (N MiB) ... raise entity_profile.memory_limit_mb", never the engine's text. Measured on the IMS day with the sandbox settings: at
  1 GB the dominant-value and relationship reads fail; at 4 GB they take 58 s and 149 s; hash-sampled at 1 GB 41 s and 123 s.
  Pinned by `ControlApiEntityProfileScaleTest`.
* **Limits** (Space layer, server-gated): `entity_profile.timeout_s` 30, `.max_datasets` 20, `.shape_sample_rows` 1,000,000, `.top_n` 20,
  `.sentinel_share` 20 (%), `.timeline_days` 400, `.relationship_threshold` 6; install layer (system admin, `la-limits.toon`):
  `entity_profile.memory_limit_mb` 4,096.
* **Data as of** reads `GET /datasets/{id}/freshness`, a read gate only (D-EP7).
* **Not built (retired to BACKLOG §6, operator 2026-10-28):** P4 — no scheduled `la.entity.profile` Job, profile Dataset, drift
  Alert Rules or 30-day history, so a cold profile still reads the whole Dataset several times (about 10 minutes on a 132 M-row day at
  2 threads). Reopen when a customer needs profile drift alerts or fast profiles on very large days. `LA-ENTITY-PROFILE-RESIDUALS-1` is closed.

### 6.4 Third-party integration (inbound) (as built 2026-10-17, LA-EDITIONS-1 phase 5)

Plan: [`inspecto-la-editions-plan`](../../../../archived-documents/plans-archive/inspecto-la-editions-plan.md) §4. Standard and above (the Investigation
surface). Outbound (case hand-off, webhooks, client-credentials trust) is a separate lane.

* **Routes** (`InvestigationInboundRoutes`, la-api, geoLink feature):
  * `POST /inv/inbound/investigations` — gated on `canManageIncidents`. A fraud product or case manager hands over its own record
    and the entities it is about; Inspecto LA opens an Investigation seeded with them and answers its id and a link.
  * `POST /inv/deep-links/resolve` — read-shaped (CapabilityManifest exemption): validates an "Open in Inspecto LA" link for the
    SPA's `/open` page.
  * Both are rate-classed **expensive** (`LinkAnalysisRateClasses`): a flood from a calling product is throttled per Subject (429).
    The inbound call is idempotent, so a retry is safe.
* **Composition, not a new write path.** The inbound route calls the existing writes through their own gates:
  `InvestigationRoutes.create` (purpose required, R3 Dataset gate, column checks), `appendOpOn` (one `seed` op per Entity Type,
  then an optional `window` op) and `InvestigationReferenceRoutes.add` (the record becomes an external reference: `trusted:false`,
  never fetched, grants nothing). Every audit event is the one those writes already emit.
* **Entities are typed** `"<type>:<key>"` by an Entity Type in force. The key is a raw value (in the BODY) or an exploration alias,
  normalised by the type's normaliser; the seed carries the normalised key, so the bound columns should hold normalised values.
* **Binding.** `binding: {dataset, sourceCol, targetCol, linkKindCol?, timeCol?, timeColZone?, eventsCol?}` when the caller knows it;
  otherwise the first link mapping the Space knows (a saved Link Analysis view, then a published index, the order `EntityTypeBindings`
  reads them) whose link end is bound to the FIRST entity's type, with its kind column, its time column (or the Dataset's `eventDate`)
  and the endpoint types. None → 422 asking for a binding. A `window` needs a time column (422 otherwise).
* **Idempotent on `(reference.system, reference.id)` without a lock.** The Investigation id is derived:
  `ext-<first 32 hex of sha256(system + "\n" + id)>`. The store creates a header CREATE_NEW, so two racing calls cannot both create,
  on any node or store backend; the loser reads as a repeat. A repeat answers the same id with `created:false`, never re-derives the
  binding, and adds nothing; if the first call died between steps, the repeat seeds or labels what is missing. A repeat by a Subject
  who cannot change that Investigation (not its lead, R3, PDP) is **409** without the id.
* **Auth.** Any Subject the installed `Authenticator` yields: an OIDC service-account token works as-is, and that Subject becomes the
  lead. Machine-to-machine client-credentials trust, token scope and replay belong to the outbound lane's security review.
* **Wire contract (inbound).**

  ```http
  POST /api/v1/inv/inbound/investigations
  Authorization: Bearer <token of a Subject holding canManageIncidents>

  {"reference": {"system": "fraudpro", "type": "alert", "id": "AL-1001", "url": "https://fraud.example/alerts/AL-1001"},
   "entities": ["msisdn:+249912000001", "imei:350000000000011"],
   "purpose": "Fraud alert triage", "title": "Alert AL-1001",
   "window": {"from": "2026-10-01T00:00:00Z", "to": "2026-10-04T00:00:00Z"}}

  200 {"data": {"investigationId": "ext-<32 hex>", "created": true, "seeded": 2,
                "reference": {"system": "fraudpro", "type": "alert", "id": "AL-1001"},
                "link": "/open?investigation=ext-<32 hex>", "note": "opened"}}
  ```

  `reference.type` defaults to `alert` (the calling system's word; not an Inspecto **Alert**). The response never echoes an entity.
* **Deep link "Open in Inspecto LA"** (roadmap I5). `<la-app>/open?entity=<type>:<key>[&from=<instant>&to=<instant>][&investigation=<id>]`;
  the console serves the same page at `/studio/open`. At least one of `entity` / `investigation`; any other parameter is refused. For a
  type masked under the Space's `maskingMode` the key must be the alias (`masked:<16 hex>`, from `POST /inv/entities/{type}/resolve`):
  a raw value is refused 422 and never repeated. The page posts the parameters to `/inv/deep-links/resolve`, which answers
  `{entity?: {type, key, masked}, investigation?: {id, title}, window?}`; an Investigation the caller cannot read is the 404 of absence.
  `reveal:true` returns a masked entity's raw value only to a holder of `canRevealLinkEntities` (403 otherwise), audited as
  `LINK_ENTITY_REVEALED` with `via: deep-link` naming the alias. Aliases are resolved from the Space's alias book (§3.4), which is
  persisted beside the Space key, so a deep link still works after a restart (2026-10-19).
* **Tests:** `ControlApiInvestigationInboundTest` (geo-link, armed Authenticator: seeding and window, idempotency incl. other system /
  id, invalid entity / reference / window / binding 422 with nothing written, 403 / 401 / 409, 503, the deep-link masking and reveal
  rules, Investigation 404), `InvestigationInboundRoutesContractTest` (TCK). SPA: `open-link.spec.ts`, `open-in-la.component.spec.ts` (axe).
* **As built 2026-10-20 (`a6866a45f`):** the `window` of a link to an Entity applies. The Open page forwards `?from=&to=` to the Entity
  page, which passes them to `GET /inv/entities/{type}/{key}/profile?from=&to=` (ISO-8601 instants with a zone, half-open `[from, to)`,
  UTC; 422 for a malformed instant or `from >= to`). Inside the window the server reads `where`, `firstSeen`, `lastSeen`, `activeDays`,
  `coveredDays` and `perDay`, and echoes `window {from, to}`; `links` and `related` stay whole-history (the page says so, with a *Show the
  whole history* link). A mixed-type inbound call binds through ONE link mapping that links a column of EVERY type it names; when no
  single mapping does, it is refused 422 naming the types (an Investigation has one mapping), unless the caller sends an explicit
  `binding`. The window bounds are 0001-01-01..9999-12-31 (422 outside). A deep link carries only `from` / `to`: an Investigation window's other settings (slots, day or timezone rules) stay on the Investigation and are not carried to the Entity page. A mapping covering all types is preferred over the first one that covers the first type.

### 6.5 Third-party integration (outbound) (LA-EDITIONS-1 phase 6, built 2026-10-17)

Standard and above (ED-6). The target products are unknown, so this is ONE generic connector: a case hand-off and
signed event webhooks, configured per Space. No named adapter exists; one is built only on a customer's request.

* **Settings** `la-integration.json` in the Space's config root, JSON (a field-mapping template must round-trip
  exactly), written only by `PUT /inv/integration` and read by `GET /inv/integration` (both `canAdminister`: the
  document decides where evidence leaves the installation). Reserved from every import (`ReservedConfigPaths`). A file
  that is present but unreadable reads as not configured, so nothing leaves. Parsed and validated by `LaIntegration`
  (la-api); a refused PUT writes nothing (422). Audited as `LINK_INTEGRATION_CHANGED` with before and after.
* **No secret in configuration.** The endpoint is a registered `https` **Connection** (onboarded under the admin-only
  `canOnboardConnections`); its token is the Connection's `password`, a `${ENV:…}` / `${SYS:…}` / `${FILE:…}` reference
  that `SecretResolver` resolves at send time. A Connection holding a literal token is refused at send. The webhook
  signing secret is itself a reference; a literal is a 422.
* **The wire is the platform's.** la-api reaches the network only through the la-core port `OutboundPort`
  (`OutboundPorts`, registered by the geo-link bridge's `HostOutboundPort`; this is the I3 "remote Case adapter" seam).
  `HostOutboundPort` composes the same three pieces as the Action Request dispatcher: `WebhookSink.endpoint` (https
  Connection, host, no tunnel or proxy), `EgressPolicy.resolve` against the Space's `EgressAllowlist` before every
  attempt (a refusal sends nothing and is not retried), and the edition's `WebhookSinkTransport.exchange` (pinned
  address, TLS verified against the name, redirects never followed; Basic ships none, so it refuses naming the
  edition). Retries: the Collector `RetryPolicy` with the Space's `retry` block (default 2 retries, exponential from
  1 s, capped 30 s, full jitter), on an I/O failure, a 5xx, 408 or 429, under ONE `Idempotency-Key`.
* **Case hand-off** `POST /inv/investigations/{id}/handoff` (`canManageIncidents`, lead only, `?at=&snapshots=` as the
  bundle export, body `{reveal?}`). Builds the Dossier bundle (`DossierBundleRoutes.build`, so the export is also
  audited as `LINK_DOSSIER_EXPORTED`), renders the template and sends it. Gates: 403 capability, 503 write root, 422 id,
  403/404 lead-only, 422 body, 403 `reveal` without `canRevealLinkEntities`, 409 nothing configured, 503 port absent,
  then 422 refused (nothing sent), 502 failed after the retries, 502 answered without a usable case id, 409 the
  reference list is full. The case id at `caseIdPointer` (and a URL at `caseUrlPointer`, kept only when it is an
  absolute http(s) URL without credentials) is linked as an **external reference** `{system, type: case}`: append-only,
  `trusted:false`, granting nothing (the D-6 model). Re-sending the same evidence answers `linked: already linked`.
  Audited as `LINK_INVESTIGATION_HANDED_OFF` or `LINK_INVESTIGATION_HANDOFF_FAILED`. Rate class EXPENSIVE.
* **Masking.** What leaves follows the Space's `maskingMode` exactly as the bundle export does. A holder of
  `canRevealLinkEntities` may send `{"reveal": true}`: the bundle then carries raw ids and `masking.mode` reads
  `revealed`, naming who lifted it. The payload always carries `masking {mode, masked, basis, note}`.
* **Webhooks** (`IntegrationWebhooks`, geo-link): a process-wide `EventLog` tap (the event bridge the Exchange Signal
  forwarder uses), so Alerts fired off a request thread are seen too. Events: `investigation.created`
  (`LINK_INVESTIGATION_CREATED`), `draft.promoted` (`LINK_DRAFT_PROMOTED`), `alert.fired` (`ALERT_FIRED` carrying an
  `investigation` attribute, which `AlertService` now stamps on an Investigation rule's event) and `approval.needed`
  (`LINK_EXPANSION_REQUESTED`). The notification webhook channel did not fit (one JVM-wide URL, unsigned, no retry).
  Delivery is asynchronous (a virtual thread) through the same `OutboundPort`; each delivery writes
  `LINK_WEBHOOK_DELIVERED` or `LINK_WEBHOOK_FAILED` on the Space's ledger. A webhook carries no entity id, so masking
  has nothing to act on; the payload says so. A signing secret that does not resolve sends nothing.
* **Signature.** `X-Inspecto-Signature: sha256=<hex HMAC-SHA256(secret, X-Inspecto-Timestamp + "." + body)>`;
  `X-Inspecto-Delivery-Id` and `Idempotency-Key` are the source event's id, identical on every retry; `X-Inspecto-Event`
  names the event. A receiver recomputes the HMAC, compares in constant time and refuses a timestamp outside its
  tolerance (replay).
* **Tests:** `ControlApiLaIntegrationTest` (real HTTP, armed Authenticator, a local stub server, targets resolved to
  private addresses by a test resolver and allowlisted): settings gates, a linked hand-off, a 5xx retried then failing
  with nothing linked, an egress-denied target and a literal token refused with nothing sent, masking and reveal, a
  verifying webhook signature, an unresolvable secret sending nothing. `IntegrationWebhooksTest`: event mapping and an
  independent HMAC vector.

Settings example:

```json
{
  "handoff": {
    "connection": "case-manager",
    "system": "case-manager",
    "authHeader": "X-Api-Key",
    "template": {
      "title": "Inspecto LA {{investigation.id}}",
      "description": "{{investigation.purpose}}",
      "evidence": "{{bundle}}",
      "findings": "{{findings}}",
      "masking": "{{masking}}",
      "externalRef": "{{seal}}"
    },
    "caseIdPointer": "/case/id",
    "caseUrlPointer": "/case/links/self"
  },
  "webhooks": {
    "connection": "fraud-hub",
    "signingSecret": "${ENV:LA_WEBHOOK_SECRET}",
    "events": ["investigation.created", "draft.promoted", "alert.fired", "approval.needed"]
  },
  "retry": { "count": 2, "backoff": "EXPONENTIAL", "initialDelayMillis": 1000, "maxDelayMillis": 30000 }
}
```

A template string that is exactly one placeholder takes the value as JSON (`{{bundle}}` stays an object); a placeholder
inside other text is replaced by its text. Placeholders: `investigation.id`, `investigation.purpose`,
`investigation.owner`, `investigation.dataset`, `bundle`, `findings` (`{summary, temporalFindings, comparisons}`),
`masking`, `seal`, `manifestRoot`, `handedOffBy`, `handedOffAt`; any other is a 422. With no template the payload is
`{source: "inspecto-la", kind: "investigation.handoff", investigation{id, purpose, owner, dataset}, findings, masking,
seal, handedOffBy, handedOffAt, bundle}`.

Webhook payload example:

```json
{
  "id": "6f1c0d2e-…",
  "event": "draft.promoted",
  "occurredAt": "2026-10-17T09:12:03.114Z",
  "space": "default",
  "investigationId": "inv-a",
  "actor": "analyst-1",
  "data": { "investigationId": "inv-a", "draftId": "d-2", "fromStep": "4", "toStep": "7", "steps": "3" },
  "masking": "no entity ids are carried; read the Investigation through the API, where the Space's masking mode applies"
}
```

**Machine-to-machine trust (I1) is NOT enabled.** Outbound calls carry only the Connection's secret header; no inbound
OIDC client-credentials trust exists. Owed before any is built, a formal security review covering:

1. **SSRF**: every outbound target is a registered `https` Connection under the egress allowlist with a pinned address;
   confirm no inbound route accepts a URL to call back, and that redirects stay unfollowed.
2. **Token scope**: a client-credentials token grants one integration role only (open an Investigation from an alert,
   read one Investigation), never a human capability; audience and issuer pinned; short lifetime; no refresh token.
3. **Replay**: inbound requests carry a nonce or timestamp checked within a tolerance window; outbound webhooks already
   sign the timestamp; idempotency keys are honoured on both sides.
4. **Audit on both sides**: every machine call names the client id as actor; the remote side's case id and our
   delivery id are cross-recorded so either party can reconcile.
5. **Secret handling**: client secrets live only behind `SecretResolver` references; rotation without downtime;
   nothing logged.
6. **Masking**: a machine principal never holds `canRevealLinkEntities` by default.

### 6.6 Analyst CSV upload (LA-ANALYST-UPLOAD-1, as built 2026-10-18)

`UploadRoutes` (la-api) lands raw CSV bytes in the inbox of a Collector and does nothing else: the Collector's Pipeline ingests the file, and the existing identity import and index build then read the Dataset. The decisions are **assistant-decided under operator delegation, pending operator confirmation** (`docs/archived-documents/plans-archive/la-analyst-upload-plan.md`).

- **Target:** only a local poll Collector with `collector.accepts_upload: true` (`ConfigSpecs`, `PipelineConfig.Collector.acceptsUpload`), resolved through `UploadLandingPort` (la-core) by the geo-link bridge `HostUploadLandingPort`. Unknown, not opted in and not local all answer one 404. The analyst never picks the Pipeline, schema, Dataset or path.
- **Capability:** `canUploadData` (default to the roles that hold `canBuildLinkIndex`); rate class EXPENSIVE; Basic edition answers 503 with the other Investigation routes.
- **Gates, in order (cheap refusals before the body is read):** (1) capability 401/403, (2) write root 503, (3) name missing 422, (4) path-shaped name 403, (5) Collector 404, (6) charset, `.csv` and the Collector's include patterns 422, (7) size 413 (`upload.max_bytes`, 25 MiB; a declared length is refused before reading, a chunked body after cap+1 bytes), (8) empty, zip/XLSX magic, NUL or non-UTF-8 content 422, (9) resolved path jail 403, (10) D-U7 four-eyes 403, (11) same name in the inbox 409 (`replace=true` opts in), (12) per-Space daily quota 413 (`upload.daily_bytes`, 200 MiB), (13) atomic temp file and move (`AtomicFiles`).
- **Four-eyes** is the existing D-U7 row budget (`four_eyes_budget_above`, rows = lines in the file) through `InvRoutes.refuseIfSensitive`; no threshold set means no gate. Classification is **not** re-judged at upload: the Pipeline's schema and the Dataset read mask as for any Dataset.
- **Audit:** `EventType.LA_UPLOAD` per attempt after the capability gate (Collector, file, bytes, sha256, outcome, refusing gate); never row content. The daily total is a per-Space ledger `<config root>/audit/la-uploads/<UTC day>.bytes` (landed bytes only, one JVM lock).
- **Status, delete and retention (LA-I2-GAPS-RESIDUALS-1 item 1, 2026-10-19).** Each landed upload also writes a metadata record `<config root>/audit/la-uploads/records/<collector>/<file>.json` (file, bytes, lines, sha256, uploader, landedAt, replaced; never content), pruned after 90 days (`UploadRoutes.RECORD_RETENTION_DAYS`); the landed file itself follows the Collector's own retention (D-UP7 stands). Three routes, all `canUploadData`: `GET /inv/uploads` lists the upload-enabled Collectors (id, Pipeline, include patterns, `maxBytes`; no paths); `GET /inv/uploads/{collector}` lists the caller's own uploads (every upload for `canAdminister`, or when no Subject is attached), newest first, at most 200, rate-classed expensive, each with `state` from `UploadLandingPort.ingest` (`pending` still in the inbox, `ingested` the Collector's `.processed` marker exists, `quarantined` the whole file sits in a quarantine reason folder, `consumed` left the inbox with no marker because duplicate check is off), `rejectedRows` when the Pipeline wrote its `_errors.csv` ledger, and `quarantineReason`. `DELETE /inv/uploads/{collector}/{file}`: 503 no write root, 404 Collector, no record (exact, case-sensitive name) or another analyst's upload (one answer, no existence oracle), 409 once the Collector has picked the file up (marker, fingerprint ledger or quarantine; its rows stay in the Dataset) or while a run of the Pipeline is in flight - the delete takes the Pipeline's run claim without blocking (`CollectorService.whileIdle`), re-checks, renames the file atomically to a `.tmp` name the poll never picks up and only then deletes it; any filesystem fault is an audited 409, never a 500. `replace=true` on the POST is likewise the uploader's or an administrator's (403 otherwise) and never changes the recorded uploader. Otherwise the inbox file and record are removed and `LA_UPLOAD` (`la.upload.delete`, outcome `deleted` / `refused`) is audited. The daily-quota ledger is not credited by a delete. **Hardening as built (LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1 (2)-(6), 2026-10-20):** (2) `HostUploadLandingPort.withdraw` reads the ingest state from the filesystem BEFORE the run claim, so a picked-up file answers 409 picked-up without touching the claim (and never "busy"); the claim is taken only for a pending file and the state re-checked inside it. (3) The fingerprint-ledger "ingested" test compares the entry's recorded `lastModified` (the file's mtime at ingest) with the file's current mtime, not the processing host's wall clock (`processedAt`), so clock skew cannot flip a state. (4) `UploadLandingPort.whileIdle` sweeps stranded `.<name>.withdraw-<nanos>.tmp` files from the inbox's top level on entry, under the claim (so none can be a live withdraw): regular files of exactly that name shape only, never a folder, junction or link (`NOFOLLOW_LINKS`, not recursive); the sweep points are the next withdraw or replace on that Collector. (5) The reject ledger is counted up to `HostUploadLandingPort.REJECT_COUNT_CAP` (10 000) rows; past it `rejectedRows` is the cap and the row carries `rejectedRowsCapped: true`. (6) `replace=true` writes through `whileIdle`, so it takes the run claim: 409 (audited gate `busy`) while a run of the Pipeline is in flight; a fresh name needs no claim. **Review follow-ups (same day):** the sweep matches only `.<name>.withdraw-<nanos>.tmp` where `<name>` is accepted by THIS Collector's include/exclude patterns, so another Pipeline polling the same inbox under different patterns is not swept (the run claim is per Pipeline; two Pipelines with overlapping patterns on one inbox remain a known limit); the reject ledger scan also stops at `REJECT_READ_CAP_BYTES` (4 MiB), and exactly the cap followed by blank lines is an exact count; a `replace=true` re-checks, under the claim, that the file is still in the inbox (409 if the Collector took it meanwhile - not covered by a test, the window is not reachable over HTTP). The SPA upload pane shows the server's busy message for a replace refused mid-run (no Replace offered) and "at least 10,000 rows rejected" when capped. Deferred: no new rate class for the delete (the pre-claim check makes repeated deletes filesystem-only); the cap is a code constant, not a Space limit. Status rows carry file metadata only, so there is nothing to mask; they are filtered to the caller by uploader id.
- **Visual Pipeline editor keeps the flag.** The editor saves through `PipelineEditable.toMap` / `lower`, which carries every `collector:` key verbatim on the acquisition node, so `accepts_upload` survives a save (also after editing another Collector key and under the strict lower); pinned by `CollectorAcceptsUploadLiftLowerTest` (engine). `PipelineLift` / `PipelineCompiler` (the typed-record rebuild) do not carry it, but nothing on the editor save path uses them.
- **Lying Content-Length (raw-socket tests):** the JDK server passes both mismatches to the route. A body shorter than declared answers 400 `MALFORMED_REQUEST` (nothing lands, no temp file); a longer one is cut to the declared N bytes by HTTP framing (201 with N bytes, bounded by the cap on N).
- **Decisions D-UP1…D-UP8 in one line:** land only in an opted-in Collector, never a scratch Dataset (1); 25 MiB per file and 200 MiB per Space per day (2); a new `canUploadData` capability (3); 409 on a same name, `replace=true` for the uploader or an administrator (4); CSV only, no XLSX converter (5); the Collector schema defines the columns and a mismatch quarantines (6); the Collector's own retention, records 90 days (7); route first, then the pane (8).
- **Follow-ups:** the 409 sees only a file still in the inbox (an ingested same-name file is caught by the Collector's checksum duplicate mode); the ledger is not shared across nodes; operator confirmation of D-UP1…D-UP8 (open work: `docs/superpower/link-analysis-roadmap.md` §D4). Tests: `ControlApiAnalystUploadTest` (geo-link).

### 6.7 Masked-alias refusal on identity writes (as built 2026-10-18)

`EntityIdentityRoutes.refuseAlias` (predicate `MaskTokens.TOKEN_PREFIX`, the one the other routes use) answers **422 before any fact is appended** when `POST /inv/entity-identities` (`a` or `b`) or `POST /inv/entity-identities/import` (any Dataset row) carries an alias-shaped value such as `msisdn:masked:<16hex>`. Why: a masked analyst only holds aliases, and asserting one appended a permanent Space-wide `identity.asserted` fact on a garbage literal key. That is an **integrity** hole, not a read leak (the response re-masks it). The server does not resolve the alias (that would need `canRevealLinkEntities`); it refuses. Retract takes a seq and resolve/seed carry no identity facts, so they are unaffected. Probes: `ControlApiEntityIdentityTest.assertRefusesAMaskedAliasAndWritesNothing`, `ControlApiEntityIdentityImportTest.aMaskedAliasInTheDatasetRefusesTheImportAndWritesNothing`.

### 6.8 Category exclusion lists in Investigations (LA-GROW-EXCLUDE-1 phase 4, as built 2026-10-22)

The list side (attributes, `PUT /entity-lists/{id}/attributes`, `canManageExclusionCategories`) is in the entity-list hub
[spec](../entity-list/spec.md), "Category lists". What Link Analysis does with them:

- **Auto-apply (GE-9).** `InvestigationRoutes.create` reads the fact log and turns every live admin exclusion list with
  `autoApply` (`EntityList.autoApplies()`, creation order) into an `excludeBy` step numbered from 1: params from the same
  `params()` an append uses, reason `auto-applied category list` (`EntityListFacts.AUTO_APPLY_REASON`), the list sealed by
  the same `sealList`. With any such step the header and the steps are written in ONE move (`store.createFork`, as a fork or
  an instantiation), so an Investigation never exists without them; with none, `create` is unchanged. A list that cannot be
  sealed (Entity Type out of force 409, more than `input.list_members` 422) refuses the create, naming the list: fail closed,
  an Investigation never starts silently without a policy list. `GET /inv/exclusion-lists` tells the administrator first
  (`LA-GROW-EXCLUDE-RESIDUALS-1` item 3, 2026-10-24): a live auto-apply list the same two checks would refuse carries
  `problem` (*its Entity Type 'x' is no longer in force* / *it has N members; a list op seals at most M*; the key is absent
  on a list that seals), and Settings ▸ Link Analysis shows a warning per such list naming that new Investigations are
  refused. The create answer adds `autoApplied: [listId]` (only when
  non-empty; the sealed header is unchanged). Template instantiation prepends the same steps, skipping a list the template's
  own method already excludes by; a fork (reorder) carries the parent's steps and adds none. Being ordinary steps, they
  replay, undo and appear in the Dossier like any `excludeBy`, and a later change to the list moves nothing already sealed.
- **Seal.** A restricted list's seal carries `visibility: "restricted"`; an open list seals exactly what it did before.
- **`seedBy` over a restricted list is refused (403)** to a caller without either grant (`requireSeedable`, on append and
  on template instantiation): a seed admits every member the Dataset holds as an entity. `excludeBy` stays allowed.
- **Restricted answers (GE-8, plan §7).** `RestrictedLists.of(ex, writeRoot, log)` names the list ops over a list sealed
  restricted OR restricted now (fail closed, as masking reads a list's flag), for a caller holding neither
  `canManageExclusionCategories` nor `canRevealLinkEntities`. Render time only, after every hash. For that caller: a step
  answer (`commit`, so `/ops`, a Draft's ops, an approved expand) has `list.unmatched: null`, `list.membersHidden: true`,
  `protected: null` and `list.removed` rounded (nearest 5, never below 5, 0 stays 0); the log view's "Excluded N entities"
  is rounded; `/replay` (main and Draft) drops the `excludedKeys` those steps remembered and says `excludedKeysHidden: true`.
  ⚠ Not hidden, deliberately: the step's Working Set **delta** still names the admitted entities it removed (the canvas
  needs them, and the analyst saw them a step earlier).
- **The Dossier** (`LA-GROW-EXCLUDE-DOSSIER-RESTRICTED-1`, fixed 2026-10-23) hides the same for that caller, in every
  form it leaves in (`json`, `steps`, `method`, `html`, the bundle and the hand-off — all go through
  `DossierRoutes.maskedDossier`): `DossierRoutes.restrictedSteps` hands `GraphDossierBuilder.Input.restrictedSteps` the
  `RestrictedLists` steps, and each such `excludeBy` renders as *"Excluded about N entities on Entity List `x` (…)
  (reason: …); which entities is not shown: the list is restricted (the count is rounded)."* in the ledger (row
  `membersHidden: true`), the undone list and the method statement; `negativeSpace.excluded` drops its ids and
  `negativeSpace.excludedHidden` carries one row per step `{step, count (rounded), reason, author, basis, membersHidden}`;
  `excludedCount` and `summary.excluded` add the rounded counts. Render time only: the manifest hashes the store, so the
  same `/dossier/verify` agrees. ⚠ Still exact, deliberately: the ledger's `entitiesAfter` (the Working Set size after
  each step) — the same difference the step's delta already shows that analyst.
- **Grow previews.** `GrowPreview.listLine` rounds a list's `removed` only when the list is restricted (now, sealed, or
  no longer known) and the caller holds neither grant; an open list's count is exact. The Basic explorer's
  `POST /inv/projection/neighbors/preview` refuses `excludeBy` over a restricted list to such a caller (403), because two
  previews, with and without the list or over a one-id frontier, would name its members by difference.
- **`GET /inv/exclusion-lists`** (`ExclusionListRoutes`, feature `geoLink`, so Basic serves it) →
  `{lists: [{id, title, entityType, ownerKind, autoApply, visibility, size}]}`: live exclusion lists, admin first, then by
  title and id; never members. Read-gated like the Entity List reads (Space access; 503 without a write root). Rate class
  exempt.
- Tests: `ControlApiInvestigationCategoryListsTest` (geo-link, 7: auto-apply recorded and replayed with an optional, an
  analyst and a retired list beside it; the unsealable list refusing the create with nothing written; the chips in Basic;
  503; restricted hiding and rounding for an analyst against the true values for a `canRevealLinkEntities` lead; the
  Dossier in all four formats for each of them, its manifest still verifying; the Undo race below).

### 6.9 Grow, Held and Exclude on the graph (LA-GROW-EXCLUDE-1, SPA, as built 2026-10-22)

The analyst side of the plan (archived:
[`la-grow-exclude-plan.md`](../../../../archived-documents/plans-archive/la-grow-exclude-plan.md); decisions GE-1…GE-12
built as recommended). How it reads to a user: [user guide §7](user-guide-link-analysis.md#7-expand-and-clean-up-the-graph) "Expand and clean up the graph".

- **Seams.** `grow-exclude.ts` is the pure half (wording, the request a Grow sends, lists in force, what they removed,
  the hint store); `GrowExcludeStore` (provided beside `InvestigationSessionStore`) holds the session state and the
  Investigation actions; the Grow panel, Held tray, Exclusions panel and Exclude dialog are presentational. Every recorded
  change is an existing op — `expand`, `exclude`, `excludeBy`, `undo` (GE-12) — so replay and the Dossier are unchanged.
  The explorer graph (Basic, or the query graph in any edition) is the `basic` mode: nothing recorded.
- **Preview (GE-3).** An Investigation previews with `POST …/expand/preview` over exactly the `expand` body the Grow
  would append (`growExpandBody`). The explorer previews with ONE `POST /inv/projection/neighbors/preview` over the
  whole frontier — `{dataset, sourceCol, targetCol, linkKindCol?, attrCols?, filter?, ids, drawn?, direction, linkKinds?
  (only with a kind column), hubThreshold}` (`neighboursPreviewBody`) — so its counts are distinct neighbours, not a
  per-node sum (the first cut sent one `{value}` call per node and over-counted shared neighbours; the server still accepts
  `value` as `ids: [value]`). ⚠ **Parity with the Grow (fixed 2026-10-22):** the Grow is one neighbours read per frontier
  node merged into `graph()`, so the preview must read with the SAME binding — the run query's `attrCols` and `filter`,
  which `expandNode` passes — and must not count what is already drawn: `drawn` = every other node of `graph()` (hidden
  ones too) by its spellings, the frontier's graph neighbours first, capped at `input.max_ids` and `input.id_length`
  (`drawnValues`); its links are the server's `addsLinks` plus `linksWithinGraph` less the `graph()` links touching the
  frontier in the Grow's direction and kinds (`withUndrawnLinks`). Raw spellings that one typed node folds still count
  once each on the server (pre-existing, small). Before the
  fix a graph its query drew whole ("Follow the money", 112 nodes) previewed "about 5 nodes and 10 links" — neighbours of
  the first 25 nodes that were the other 87 — and the Grow then said 0 and 0; a filtered view previewed neighbours only its
  unfiltered rows reach. Never fetch the neighbours themselves to preview: each `/inv/projection/neighbors` read emits
  `LINK_EXPANDED` and spends the LA rate budget, which a preview must not.
  0 / 404 / 405 / 501 / 503 read as *No preview on this server* and Grow still runs; 404 / 405 / 501 are remembered per mode
  for the session. Any other status, a 403 included, shows the server's reason in the panel (`previewFailure`), never a
  toast. *2 steps* is offered only when a ready preview says the first step stays under the frontier cap.
- **The explorer sends no `excludeBy`.** Its neighbours read (`/inv/projection/neighbors`) cannot apply a list, so a
  preview counting one would promise removals the Grow does not make. Category lists therefore apply to Investigations
  only (auto-apply on create, §6.8); the explorer's Exclude hides locally and can add to a list. **Decided 2026-10-24**
  (assistant under operator delegation, pending confirmation): not added. The read emits `LINK_EXPANDED` per call and
  records nothing, so a policy list applied there would be unaudited removal; adding `excludeBy` needs the capability,
  audit, restricted-list and masking gates of §6.8 re-proved on a second route for a mode that has no record to replay.
  Also decided: Grow previews stay unaudited (read-shaped, nothing changes; a restricted list's count is already rounded
  or refused), and the SPA keeps naming the user-guide chapter instead of linking it until it serves docs.
- **Undo.** A Grow / Exclude result line offers Undo while the steps it made are still the newest effective steps of the
  log as last read (`valid()`); it sends the `undo` op once per step, newest first, each naming its step
  (`{undoes: step}`). Ctrl+Z runs that Undo first, then the display undo. The explorer's Undo restores the graph
  snapshot only while the graph is the one the Grow drew.
- **The Undo names the step it means** (`LA-GROW-EXCLUDE-UNDO-RACE-1`, fixed 2026-10-23). In a shared Investigation
  another lead may record a step after this browser last read the log; the old route undid the newest effective step,
  so that step was the one taken back. `POST …/undo` (and a Draft's) now takes an optional body `{undoes: step}`
  (`InvestigationRoutes.expectedUndo`: a whole number from 1, else 422): when the newest effective step is another one
  the answer is **409 `CONFLICT_STALE_VERSION`** naming it (*"step 2 is not the newest step any more: step 3 (keep by
  lead-1) is - read the log again; nothing was undone"*) and nothing is written. Checked inside `untilWon`, against the
  log each attempt reads, so a step that lands while the undo retries is caught too. Without `undoes` the route is
  unchanged. The SPA always names it: the Grow / Exclude result line (`undoOf`), *switch a list off* (the list's step)
  and the op log's Undo button (`InvestigationSessionStore.undoTarget`: the newest effective step read; none while
  the log read was cut, `truncated`). On a refusal the store re-reads the log, so the screen shows the newer step,
  the refusal stays in the error line and the result line's Undo is no longer offered.
- **Switch a list off = the `undo` of its step**, so only a list applied by the newest effective step can be switched off;
  a list applied earlier, or in several steps, says why (`listsInForce`). A "skip this list for one Grow" would need an
  `expand` parameter nobody has specified — deferred (2026-10-24, assistant-decided under operator delegation, pending
  confirmation: keep as is; an older list is switched off by undoing back to its step, which the log records).
- **First-time hints** (Grow panel, Held tray, Exclusions panel): each dismissible with *Got it*, remembered per browser in
  `localStorage` (`inspecto.la.hint.grow` · `.busy` · `.exclusions`), every read and write in try/catch so a private
  window shows the hint again and never errors. Each names the user-guide chapter *Expand and clean up the graph* (`GROW_GUIDE`, user guide §7)
  as text, not a link: the SPA serves no docs (they ship in the bundle's `docs/` folder).
- **Pack-aware wording.** Every count, tooltip and hint speaks the active pack's node / link words (`packTerms`, from the
  profile labels: Telecom *subscribers / calls*, Mobile Money *wallets / transfers*); the technical term (expand, super node,
  exclusion list) stays in the tooltip.
- **Keyboard.** **G** Grow, **X** Exclude…, **Ctrl+Z** Undo — not in a text field, grid, menu or dialog; listed in the main
  app's `?` shortcuts help (`shortcuts-help.dialog.ts`) and, since 2026-10-23, the standalone `la-app`'s: its shell
  opens the same dialog on a bare `?` (never in a text field) with its own list, `LA_APP_SHORTCUTS` (no command palette
  or triage-list keys, which la-app does not have), passed as the dialog data `{shortcuts}`.
- **Access Catalog.** `canManageExclusionCategories` has its action node since 2026-10-23: `linkgraph.exclusion-categories`
  (the id `LensService` reads), under Settings, where the Exclusion categories section is its main client gate. Since
  `LA-ACCESS-NODE-IDS-1` (2026-10-23) `canRevealLinkEntities` / `canApproveLinkExpansions` read their nodes' ids
  `linkgraph.reveal` / `linkgraph.approve`; `access-lens-parity.spec.ts` fails on any node/lens id drift
  (detail: `module/access/admin-guide-auth-security.md`).
- **Gotchas and deferrals** (open rows in [BACKLOG](../../../../BACKLOG.md), `LA-GROW-EXCLUDE-RESIDUALS-1`):
  - *Previews are not audited*: neither preview route writes an audit record or event (read-shaped, rate class EXPENSIVE).
  - *A broken auto-apply list blocks every Investigation create* in the Space (fail closed, the refusal names the list)
    until an administrator fixes or retires it.
  - The think-aloud test with two new users (`tests-think-aloud-script.md`) was not run (`LA-GROW-EXCLUDE-THINK-ALOUD-1`).
- Tests: `grow-exclude.spec.ts`, `grow-exclude.store.spec.ts` (each Undo names its step; a refused one is reported, never
  undone), `link-analysis-investigation.store.spec.ts` (the 409 re-reads the log; `undoTarget`), `inv.service.spec.ts`
  (the `undoes` body), the four component specs (axe-clean, hints remembered and blocked storage),
  `link-analysis.component.spec.ts` "Grow, Held and Exclude on the graph" (one explorer preview with its body; a 403
  shown in the panel; the rail, Basic Grow, Undo, Ctrl+Z, degrading without the routes), `shortcuts-help.dialog.spec.ts`
  and `la-shell.component.spec.ts` (la-app's `?`), `access-catalog.spec.ts` (the node).

### 6.10 Add data: the shared import route (LA-ONBOARD-1 phase 2, as built 2026-10-22)

The route family lives in the **core** (every edition, Basic included, not under `/inv`); its contract, gates,
limits and the commit's write order are in
[`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md) ("Add data"). What Link Analysis relies on:

- **The draft's `entityTypes` block** proposes, per column, the Entity Type of the Space
  (`LinkAnalysisSettings.effectiveEntityTypes()`) from value shapes and names, the link's from / to / kind / time /
  amount / details, the primary Entity Type, and for a Reference the identifying column. Every proposal is
  `{value, evidence, confidence}` (OF-2); a column whose values fit no Entity Type stays a detail and says why.
- **The registry entry carries the link (OB-8).** A committed Dataset has `columns[].classification`,
  `primaryEntityType` and `defaultLinkMapping {from, to, kind?, time?, amount?, details?[]}`.
  `DatasetEntryRules` (run by `ComponentRoutes.validateKind` on every Dataset write, so the generic component
  routes refuse the same) checks the shape: from and to required and different, every named column one of the
  entry's `columns[]` when it lists any, `primaryEntityType` a non-blank id.
- **Entity profiles read `defaultLinkMapping` (F-9).** `EntityTypeBindings.resolve` adds the Dataset's own default
  mapping after saved views and published index manifests (skipped when one of those already names the same two
  ends), so link roles and the `links` section are assessed with no saved view. Pinned by
  `ControlApiOnboardLinkAnalysisTest`: wallets registered with a default mapping are assessed, agents without one
  are "not assessed"; red with the fallback removed.
- **The index is built in the user's run on Basic (DT-16).** The commit, Add a file and Run now call the
  `LinkIndexBuilder` SPI (the geo-link bridge, `ScheduledIndexBuild`) for the default mapping (source = from,
  target = to, kind, time, weight = amount; no attribute columns; owner = the importer; a first build is FULL),
  not a signal-driven `la.index.build` Job. Skipped (and said so) where `pipeline.automation` is present; that
  feature is declared by no module yet, so today every edition builds in-run. The summary reports
  `index:{result, edges, nodes}`.
- **The entry keeps its last import (`LA-ONBOARD-RESIDUALS-1` item 7, as built 2026-10-28).** The commit and *Add a file*
  write `lastImport {at, replaced, rowsBefore, rowsAfter, batch, file}` onto the Dataset's registry entry
  (`OnboardImport.Job.lastImport`; `batch` = the run's ledger `consignment_id`). The commit puts it in the entry it
  registers; Add a file re-reads the entry (an edit made meanwhile is kept) and writes it with `archive=false` (a result
  stamp, no version in the history) BEFORE the replaced rows are dropped, so a failed write still restores them. No new route: the home page's *What changed in your data* already lists these entries.
  *Run now* imports no file and records none. Session decision (assistant-decided under operator delegation, pending
  confirmation): only the LAST import is kept, not a history - the card shows one line per Dataset. Pinned by
  `ControlApiOnboardFilesTest.addAFileReplacesOrAppendsAndRunNowReRuns`.
- **Change a column's Entity Type later:** `PUT /datasets/{id}/columns/{column}` (`canAuthorWorkbench`, edit access
  to the Dataset). The profile cache keys on the binding signature, so the next read re-assesses.
- **Deleting an Add data Dataset retires its import Pipeline** (`LA-ONBOARD-ORPHAN-PIPELINE-1`, `LA-ONBOARD-RETIRE-GAPS-1`).
  `DELETE /components/dataset/{id}` claims the retirement (`OnboardImport.claimRetirement`) and retires BEFORE the entry
  is removed: the Pipeline is unregistered and its config, schema and config history removed (the data directory is
  kept); the step is idempotent and the Pipeline file goes last, so a failed delete is simply retried. The response then
  carries `retiredPipeline`. Detail: [`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md).
- **Not built here:** the screens (phase 4), the Start box reading `primaryEntityType` (phase 6), Reference details
  on nodes (phase 5), and the C1 format detections (another session).

### 6.11 Help on every screen and the onboarding counters (LA-ONBOARD-1 phase 8, as built 2026-10-22; UC-15, A-9, A-10, OB-13)

`LA-ONBOARD-1` closed with this phase; the plan is archived
(`docs/archived-documents/plans-archive/la-onboarding-plan.md`). What phases 1-7 built is in §3.4 (*Start from...*,
*My work*), §6.10 and §8 (*Trust vs level*); the acceptance drive is
[`tests-onboarding-live-drive.md`](tests-onboarding-live-drive.md).

* **Onboarding counters (OB-13).** `com.gamma.util.OnboardingCounters` (inspecto-util, below both the host and la-api,
  like `IngestActivity`): a process-wide static holder of ONE append-only file per installation,
  `onboarding-counters.tsv`, one line per event, `<ISO instant>\t<event>[\t<step>]`. The closed vocabulary is the enum
  `Event` (`launch`, `space_created`, `first_dataset`, `first_graph`, `guide_completed`, `step_abandoned`) and the set
  `STEPS` (`space`, `data`, `columns`, `graph`, `list`, `add_data:file`, `add_data:columns`, `add_data:create`); a step
  word outside it is dropped, so nothing a person typed can reach the file. `first_*` and `guide_completed` are written
  once per installation (`install` reads the file, so a restart does not repeat them). `record` never throws: a full
  disk loses the count, never the action. With no file installed every call is a no-op.
  * **Where the file lives** (decided here): the server-wide document home, the same resolution as `scheduler.toon` and
    `la-limits.toon` (`SchedulerRoutes.systemDocPath`: `-Dsystem.config.dir`, else the spaces container root, else the
    sole Space's write root), not `-Ddata.dir`, which is per Space. Reserved in `ReservedConfigPaths` (an import must
    not forge it). No home = `recording: false`.
  * **Who records what.** `OnboardingRoutes.register` installs the file at boot and records `launch` (so "first launch"
    = the first server start of this installation, the drive's clock start). `SpaceRoutes.createSpace` records
    `space_created` (template, sample or bare); `OnboardImport.Job.finishCommit` records `first_dataset` after the undo
    stack is cleared (inline and 202 commits alike); `InvRoutes.project` (projection AND the Start box's neighbours read)
    records `first_graph` when the answer holds a link. The browser reports only what it alone sees through
    `POST /onboarding/events {event, step?}`: `guide_completed` (the home page, once per app load) and `step_abandoned`
    (Create Space dialog closed without a Space; Add data left with a file chosen - `add_data:file`, a draft -
    `add_data:columns`, or a run in flight - `add_data:create`). Any other event, including `first_graph`, is 422: a
    browser cannot claim a milestone.
  * **Routes** (`OnboardingRoutes`, core, every edition): `GET /onboarding/metrics` (`canAdminister` in the manifest;
    with no sign-in anyone) answers `{recording, firstLaunch, launches, spacesCreated, firstSpace/Dataset/Graph,
    guideCompleted, each with *Seconds from the first launch, abandoned {step: n}, abandonedCapReached}`;
    `POST /onboarding/events` is a `self-limiting` exemption (open to any signed-in caller: a beginner is who reports it)
    on the standard rate bucket (`ControlApi.isRateLimited`). The file keeps at most
    `OnboardingCounters.ABANDONED_CAP_PER_STEP` (1000) `step_abandoned` lines per step, re-counted from the file at
    install, so an open route cannot grow it without bound; a report past that answers 200 and is dropped, and
    `abandonedCapReached` turns true (`LA-ONBOARD-RESIDUALS-1` item 11, 2026-10-23). Pinned by
    `ControlApiOnboardingCountersTest` (file, vocabulary, 401/403/200, 429), `OnboardingCountersTest` (the cap
    across a restart), and the milestone assertions in `ControlApiSpaceSamplesTest` and
    `ControlApiOnboardLinkAnalysisTest` (falsified: without the `InvRoutes` record the first graph stays null).
  * **SPA**: `OnboardingEventsService` (`src/app/inspecto/onboarding/`, fire-and-forget, a failure is silent) and the
    la-app **Settings → Getting started counters** section (server audience, read-only, listed when there is no sign-in
    or the person holds `canAdminister`).
* **Help rules (UC-15), all deterministic (A-9).**
  * **What can I do next?** - a button in the la-app rail (`la-shell.component.ts`), so on every screen.
    `next-steps.ts` (pure) turns the Space guide's `SpaceGuideState` plus *may create a Space* (`FirstRun.canCreate`) and
    *why Add data is locked* (`AddDataAccess.lockReason`) into at most three steps, the recommended one first, each with
    one line of why; `NextStepsDialog` draws them (a step the person may not take is an `inspecto-locked-action`) above
    the Space guide.
  * **Terms on first use** - `<inspecto-term term="...">` (`src/app/inspecto/onboarding/term-help.component.ts`, shared,
    usable by the console): the word, a **?** button (tooltip and accessible name carry the explanation, `aria-expanded`
    discloses it inline). `TERM_HELP` holds the sentences; a spec keeps them short and free of Pipeline words. Placed at
    the first mention on Entities, Columns, Entity Lists, Start from..., My work and Save as a case. ⚠ The input is `term`,
    not `id`: a static `id="..."` would also set the host's DOM id and duplicate it.
  * **Empty states** now name a reason and one action: Entities → *Open Columns* (switches the tab), Columns → *Add
    data*, Entity Lists → *New list* (for someone who may write lists; otherwise the existing line says why not), the
    Start box's no-Dataset line points at the *Add data* / *Explore a Dataset* cards under it, the saved-views menu
    says how to fill it, and My work says when the cases could not be read (*Try again*) instead of "No case yet".
* **Found by the acceptance drive and fixed here** ([`tests-onboarding-live-drive.md`](tests-onboarding-live-drive.md)):
  * **Add to a list was a dead end on the explorer.** `growEntityType` (used by *Add to a list* from Find something
    interesting and by Exclude → Add to a list) knew only the Investigation binding, the Domain Profile's `investigate`
    type and typed canvas nodes; a graph drawn by a Start box start has none, so the dialog offered no list and no *Create
    a new list…*. It now falls back to the start Dataset's `primaryEntityType` (`discoverDataset()`), and the dialog says
    why and where to make a list when nothing can take the entities.
  * **No screen turned Add data on.** With sign-in `data.self_import` is off until an administrator turns it on, and the
    locked screen says "ask an administrator" - who had only `PUT /settings/data`. la-app **Settings → This Space → Add
    data** (`AddDataSettingsComponent`, `OnboardService.setDataSettings`) is that switch (editable with no sign-in or with
    `canAdminister`, read-only otherwise), and the Add data page shows "You may turn it on: Settings, Add data" to the
    person who may.
  * The Link Analysis footer no longer says "pipeline planes" with no graph drawn (no Pipeline word on a Basic screen).
* **Two phase 7 leftovers fixed.** (1) **A case's name came back as its id in My work**: `ComponentStore.write` stamps
  the content's `name` with the id (in-file identity = URL id), so the display name a save wrote under `name` was
  overwritten on every save. The view codec (`link-analysis.service.ts`) now also writes `title` and reads
  `title ?? name ?? id`; older views read as before. The Geo map's saved views had the same defect; fixed in §6.12.
  (2) **One purpose vocabulary**: `ENTITY_LIST_PURPOSES` now carries the
  plain words (*Watch these* · *Never follow these (exclusion)* · *Known good* · *Block*) and `purposeWords()` reads
  them; the Entity Lists page, New list and Add to a list all say it that way (`PURPOSE_WORDS` is gone).

### 6.12 Explorer defects found by the acceptance drive, fixed (LA-ONBOARD-DEFECTS-1, as built 2026-10-23)

Each fix carries a test that fails on the code before it (falsified).

* **The index summary counts the whole index.** After *Add a file* in append mode the summary said "2,215 links between
  624 entities" for a Dataset of 4,430 rows over 312 accounts: `ScheduledIndexBuild` reported the APPEND build's own
  `IndexBuilder.Result` (the delta's edges) and the `nodes` table's row count, which after an append holds the parent's
  rows plus the delta's (an entity in both counts twice; only a COMPACT folds them). The outcome - BUILT and UP_TO_DATE
  alike - now reports what the published version holds: edges = the manifest's `out` rows, nodes =
  `IndexBuilder.distinctNodes(versionDir, manifest)` (a `count(DISTINCT id)` over the version's `nodes` files, read only
  when the manifest has deltas; a failed count falls back to the row count and never fails the build). The index status
  route's `nodes` (`IndexRoutes`, the manifest row count) is unchanged. Test: `ControlApiOnboardLinkAnalysisTest`
  "afterAnAppend...".
* **A start is typed by the Entity Type it names.** `drawStart(ds, values, entityType?)` types the drawn mapping by the
  Start box's pick (one entity, a paste), the Entity List's own type (a list), or the type the graph on screen was drawn
  as (*Start from these*); only with none does the Dataset's primary type (`startCatalogue`'s `mapping.entityType`) stand.
  It was always the primary, so a wallet started over a transfers file was read, normalised and masked as an account.
  A case saves the type as the optional `start.entityType` (read defensively by `startOf`; older cases read as before),
  and `growEntityType` (what *Add to a list* types a list by) reads the drawn mapping's type before the primary.
  Specs: `link-analysis.component.spec.ts` "a start types its mapping...", "a list start is typed by the list...",
  `link-analysis.service.spec.ts`.
* **The type-ahead refreshes on returning to *One entity*.** The Start box's suggestions stream read the kind only when
  another source emitted; the kind is now a source of its own, so a Dataset or type picked while on *A list* is asked
  for on the way back. Spec: `link-analysis-start-box.component.spec.ts` "switching back to One entity...".
* **A masked type can be revealed in the type-ahead.** `POST /inv/entities/{type}/suggest` takes an optional
  `reveal: true`: it needs `canRevealLinkEntities` (403 otherwise, checked in the route as the hand-off does; the route
  stays a `read-shaped` exemption), lists the masked type's raw values and counts per prefix, answers `revealed: true`
  and is audited with `revealed` on `LINK_ENTITY_SUGGESTED`. Without the ask nothing changes for anyone. The Start box
  never asks on its own: on a masked answer it offers someone who may reveal (`LensService.canRevealLinkEntities`) the
  button *Show its values (you may reveal them; recorded)*; the reveal holds for that Entity Type only. Tests:
  `ControlApiEntitySuggestTest` "aRevealerAskingReveal...", the start box spec "a masked type: someone who may reveal...".
* **Saved-view names on the Geo map and in Studio.** The Geo map's view codec (`geo-map.service.ts`) writes `title`
  and reads `title ?? name ?? id`, as the Link Analysis case codec does (§6.11); the Studio explore Widget's saved-view
  picker (`explore.component.ts`) shows `content.title` before `content.name`. Specs: `geo-map.service.spec.ts`,
  `explore.component.spec.ts`.
* **Sign-out and sign-in in one tab.** `KeepAliveRouteReuseStrategy` keyed a kept page on Space and person, but a
  sign-out clears the person (`SessionService.onAuthLost`) BEFORE it navigates to sign-in, so the page was kept for
  "nobody"; the next person landed back on Link Analysis before the session had read their name - "nobody" again - and
  got the previous person's page. With sign-in on, `shouldDetach` now keeps a page only while someone is signed in: a
  page left by nobody is destroyed. Without sign-in (Basic) nothing changed. A sign-out therefore also ends what the
  same person had open. Spec: `keep-alive.strategy.spec.ts` "signing out on the page and in as someone else...".
* **An Add data import is shared with its Space** (decided here: the smallest correct fix; the alternative, a *Share*
  control on the import's Dataset card, needs a sharing route - `PUT` on the component cannot drop a `shares` key and
  the sharing model names roles and users, not "everyone in this Space"). The commit no longer writes `shares: []`, so
  the entry behaves like every other Dataset in the Space: `owner` (the importer) is provenance, a colleague can view and
  start from it, and changing it still needs the route's capability (`canAuthorWorkbench` for its columns,
  `canUploadData` for *Add a file*). A Dataset made private through the component routes stays private. Imports
  committed before this change keep `shares: []`; nothing migrates them. A person who wants a file to themselves uses
  the analyst upload (§6.6) into their own Collector. Test: `ControlApiOnboardFilesTest`
  "withSignInSelfImportIsOffByDefault...".

### 6.13 Onboarding residuals (LA-ONBOARD-RESIDUALS-1, as built 2026-10-24)

Calls marked * were assistant-decided under operator delegation, pending confirmation.

* **One definition of "people sign in".** `BootstrapRoutes.edition()` now asks `EditionMaskingDefault.signsIn()` (an
  `auth.mode` other than `none`, or an installed Authenticator), the same test the masking default uses; before, an
  Authenticator with no `auth.mode` made masking `typed` while `/bootstrap` still said `basic`. Test:
  `BootstrapEditionTest.anInstalledAuthenticatorMeansSignInEvenWithoutAuthMode`.
* **Role label.** The Inspecto LA Access screen shows `pipeline-developer` as *Data builder* (`AccessRolesComponent`
  takes an optional `labels` map; the LA app passes it). The id is unchanged in `roles.toon` and the console.
* **Add data settings** offers *Try again* after a refused change or a failed read (it reads the setting afresh).
* **Commits stay inline up to `import.inline_max_bytes`*** (default 64 MiB, per Space; 0 sends every commit to a
  followed 202 run). Not benchmarked; the knob is the bound, so there is no code change.
* **Find paths (server) stays unlocked on Basic*:** `docs/EDITIONS.md` lists it for all four editions.
* **Still demand-gated\*:** the `pipeline.automation` feature and DT-17 trigger (item 3), no-code row filtering (6), a
  stored run summary for replace-vs-append (7), and the phase-8 notes; each is a feature, not a small change.

### 6.14 Studio UI reorganization (LA-STUDIO-UI-REORG-1, as built 2026-10-28)

The Studio was reorganized without removing a function; the layout is described in
[user-guide-link-analysis.md](user-guide-link-analysis.md#studio-layout-la-studio-ui-reorg-1-phases-1-3-as-built-2026-10-27) *Studio layout*, and the plan with its "nothing removed" map
is archived as [`la-studio-ui-reorg-plan`](../../../../archived-documents/plans-archive/la-studio-ui-reorg-plan.md) §6.

- **Decisions (operator interview 2026-10-27, UIR-D1…D16):**
  - The Investigation tab split into the Act panel, a bottom dock and one ⚙ Settings dialog. The dialog also holds the old left Query panel, Entity Types, display rules, Entity Lists, Identities and Add / Upload data.
  - A second icon toolbar carries Canvas modes, selection and graph edits.
  - Plain drag pans, Shift+drag lassos, and there is no box select.
  - One Expand dialog for N degrees, with supernodes held.
  - Collapse is visual only, everywhere.
  - Four clean-up tools.
  - Layouts and overlays over the cap are disabled with a reason.
  - One Open dialog and a File menu.
  - One algorithm catalogue with a Browser / Server toggle.
  - The Domain profile picker is removed.
  - Dashboard Widgets and the embed viewer get a read-only toolset.
  - Editions lock single actions and hide whole areas.
- **Visual polish (operator, 2026-10-28):**
  - The title and its explain button moved back up from the status bar; the page-info line is gone.
  - The tool rail and the canvas toolbar share the header row (`la-toolbars`); File ▾ is the rail's first icon.
  - Every header button is icon-only, `aria-label` kept, name as `matTooltip`. A component style shrinks icon buttons to 30 px, with `!important` because the theme pins `.mat-mdc-icon-button` to 40 px with `!important`.
  - The rail does not shrink (it overlapped the canvas toolbar when it could), and the row container wraps as a last resort instead of clipping. The canvas toolbar is still not `overflow-hidden` (LA-REORG-CLEANUP-PANEL-CLIPPED-1). Measured: one row at 1,440 px with all 112 nodes selected and at 1,100 px folded.
  - The canvas toolbar folds by the header row's OWN width (`CANVAS_TOOLS_FOLD_PX` = 1,350, a `ResizeObserver` on `la-header` → `canvasToolsFolded`), not a viewport media query, so the console's side nav counts. Measured: the console at 1,440 px with its nav open (header 1,129 px) is one row with 921 selected; at 1,100 px with the nav open (header ~790 px) the rail alone is 580 px and the canvas toolbar wraps under it, unclipped.
  - Expand uses `mat_outline:open_with` and Clean up `mat_outline:cleaning_services`, from the registered Material sets.
- **Calls UIR-A1…A3, confirmed by the operator 2026-10-28** (proposed by the assistant, built as written):
  - A1: one ⚙ dialog that also holds *This Investigation*.
  - A2: Value Measures appears only in the catalogue.
  - A3: in an Investigation, clean-up records `hide` + `annotate`, one grouped Undo (`GrowExcludeStore.cleanUp`, `recordCleanup`). `keep` is not "keep only".
- **Industry pack (operator, 2026-10-28):**
  - A saved view never applies its own pack; the Space's pack always applies.
  - `loadView` clears `boundProfile`.
  - The view's stored pack is only shown in the Open preview and saved back unchanged.
  - Only an Investigation Template's `pack` binds one.
- **Read-only toolset:** `link-view-widget-tools.ts` (`widgetGraph`, `runWidgetAnalysis`) is shared by the dashboard Widget (`LinkViewWidgetComponent`) and the embed page (`projects/link-analysis/src/link-analysis/embed-view.page.ts`, drawn on `GraphViewComponent`; served at `/embed/inv/:token` by both the console and `la-app` since 2026-10-28, `LA-CONSOLE-EMBED-VIEWER-1`). Everything runs in the browser and sends no request.
  - Export appears only with the Widget's `allowExport`.
  - The embed viewer never offers Export, because an embed grant has no export flag.
  - Minting an embed needs `canShareInvestigation`, the lead role and `-Dla.embed.enabled=true` (503 otherwise).
- **Gotchas found by the drives:**
  - The second toolbar must not be `overflow-hidden`: it clipped the Clean up panel at every width, so every Clean up tool looked dead (`LA-REORG-CLEANUP-PANEL-CLIPPED-1`, fixed 2026-10-28). The toolbar fits at 1,300 px and folds into overflow menus below it, so nothing needs clipping.
  - The ⚙ Settings Esc handler ignores an Esc that another overlay already handled.
  - A case view sets `loading` before its awaits, and an unknown graph source clears it.
- **Live drive 2026-10-28** (a Demo Auth bundle on la-showcase; each Demo User was signed in through the sign-in picker in the browser):
  - **Embed minting:** `fm.analyst` and `admin` get 200; `fm.manager` and `ra.analyst` get 403; `demo.manager`, a non-member, gets 404 on the embed list.
  - **Read-only toolsets:** the embed viewer and the `la_showcase_overview` dashboard Widget (opened as `ra.analyst`) run Centrality, Communities and Cycles with no server request.
  - **`fm.manager` and `admin`:** open `mule-hub-case` with the full File menu and ⚙ → Investigation settings. As `admin`, Share and embed offers *Create link*.
  - **`demo.manager`:** gets "This Investigation is not available…", the same answer whether or not the Investigation exists.
  - **`ra.analyst` (on Standard):** sees a "Read-only" banner. *Save this analysis* and *Delete* are off, with the reason. Expand…, Expand next degree and Expand the whole Working Set are off.
  - **Esc in ⚙ Settings,** after a real Graph model edit: the first Esc asks to discard the change; the second Esc closes that confirm without reopening it, and Settings stays open.
- **Edition and add-on locks, checked live the same day (UIR-D14).** The demo sign-in exists only on Advanced and Preview builds, and Basic has no sign-in at all. So Standard was run as the demo bundle without `inspecto-policy`, and Basic as a real `-Edition Basic` bundle.
  - **Advanced with `-Daddons.laInvestigations=false`:**
    - `/bootstrap` reports `laLevel: explorer`.
    - ⚙ has no *This Investigation* group.
    - File ▸ Close and Delete are off.
    - *Run on the server* and *Start an Investigation* are locked (`aria-disabled`) with "Needs the Investigations add-on…".
  - **Standard:**
    - `/bootstrap` reports `edition: standard`, and the Investigations add-on is present.
    - Geo, Entities and Alerts are in the navigation.
  - **Basic:**
    - `/bootstrap` reports `edition: basic` and `laLevel: explorer`, with `geoMap`, `entityPages` and `laWidgets` false and each with its reason.
    - The graph draws without sign-in.
    - Geo, Entities and Alerts are hidden from the navigation.
    - *Run on the server* is locked with "Investigations are in Standard and above…".
  - **Real `-Edition Basic` build with the console UI:**
    - The Widget picker's *Investigation views* offers only Reconciliation; there is no Link analysis or Geo map type.
    - The saved `la_showcase_overview` Widget still draws. That is by decision: the offer is cosmetic (D-LAE2).
    - Embed and Investigation routes answer 503 with the reason.
  - **Real `-Edition Standard` build with WSO2 sign-in:** `/bootstrap` reports `standard` / `investigations` / `oidc`.
  - **Every user was signed in on that real Standard build.** The browser started SSO and kept its own state, PKCE verifier and nonce.
    - The browser does not trust the local WSO2 self-signed certificate. So only the IdP login form was completed by a localhost script, using the synthetic users from `scripts/wso2-preview-la-setup.mjs`.
    - The browser then opened the returned callback, and the app finished its own exchange.
    - The la-showcase seed ran with real WSO2 tokens.
  - **Results per user:**
    - **`fm.analyst`:** the full File menu, Investigation settings with *Create link*, and *Expand…* available.
    - **`fm.manager`:** opens the Investigation; Share and embed shows no *Create link*.
    - **`admin`:** *Create link* is offered.
    - **`demo.manager`:** gets "This Investigation is not available…".
    - **`ra.analyst`:** may change Investigations here. In WSO2 it is also in the built-in `operations` group, which grants `canManageIncidents`. The demo roster makes it a reviewer only, so this is the identity setup, not a defect. Operator 2026-10-28: `ra.analyst` is a read-only reviewer on WSO2 too; `scripts/wso2-preview-la-setup.mjs` now lists it in `la-viewer` only and removes a user from any of its groups it is no longer listed in.
- **Embed Export:** stays unbuilt (operator 2026-10-28, "not now").
- **Full gate 2026-10-28 (`f0e8a50eb`):**
  - The reactor ran 56 of 56 modules: 11,280 tests, 0 failures, verdict guard PASS. The UI suite passed 6,779 tests.
  - Six stale tests that peer commits left on master were fixed first, none in the Link Analysis UI: two Job tests outran by the new retry, a new scoring route, a DELETE-only Investigation path, and two sign-in specs.
- **Deliberately not built:** Export in the embed viewer (it needs a grant flag first).

## 7. Host registration, Jobs and live detection

* **ServiceLoader files:** la-api → `RouteModule` (27); geo-link → `RouteModule` (4), `InvestigationMeasureProbe`,
  `CasePort`, `CollectorCoveragePort`, `DatasetProvider`, `OutboundPort`, `UploadLandingPort`, `LinkIndexBuilder`; la-store-pg →
  `InvestigationStoreProvider`.
* **Host wiring:** `CollectorService` registers Platform Service `link-index` and wires the alerting Investigation
  probe; `JobService` registers Job types `la.detect` (needs `alerts`) and `la.index.build` (needs `link-index`),
  both failing the Run closed when the service or module is absent. `JobAuthority.stamp` (and `TemplateSeedGate`)
  stamp an `la.index.build` Job's `owner` to the saving Subject.
* **Live detection:** `POST …/standing-detection` records an `Authority` (principal `sweep:<investigation id>`,
  owner, dataset, masking basis, capability snapshot) beside the Alert Rule binding. `la.detect` →
  `AlertService.evaluateInvestigationRules` → `WorkingSetMeasures` → `StandingDetection.decide`, which re-decides
  before every read (refusals `NOT_ENABLED`, `NO_OWNER`, `BINDING_CHANGED`, `DATASET_GONE`, `DATASET_NOT_SHARED`,
  `ROLE_SHARE_ONLY`, `NOT_LEAD`, `MASKING_TIGHTENED`, `POLICY_DENIED`, `UNDECIDABLE`). The sweep holds no capability
  of its own; a breach fires an Alert. The Alert's `evidence` carries the aggregate breach facts only
  (`LA-DETECT-ALERT-AGGREGATE-1`): `measure`, `threshold` (the `ValueMeasures.label` line), `breachCount`, and
  `worstOf` / `worstValue` (the Measure's headline column on its first, worst-ordered row). Never an entity id,
  name or alias; entities are named only inside the Investigation.

## 8. Editions and packaging

Edition names per `LA-EDITIONS-1` ED-2 (2026-10-17; formerly Personal / Professional / Enterprise). Feature placement:
[`EDITIONS.md`](../../../../EDITIONS.md) (*Inspecto LA feature placement*).

| | Basic | Standard | Advanced | Preview |
|---|---|---|---|---|
| la-core, la-storage, la-api, geo-link, entity-list (the **explorer**) | ✓ | ✓ | ✓ | ✓ |
| Investigation surface (`investigations` edition feature, declared by `inspecto-ops`, AND the Investigations add-on held) | — (503) | ✓ by default | ✓ by default | ✓ by default |
| Geo map, routes and co-location (`geoMap` route feature on `GeoRoutes`, declared by `inspecto-ops`; D-LAE5) | — (503) | ✓ | ✓ | ✓ |
| Entity profile pages (`entityPages` route feature on `EntityPageRoutes`, declared by `inspecto-ops`; D-LAE4) | — (503) | ✓ | ✓ | ✓ |
| Link analysis and Geo map Widget types (`laWidgets`, a `/bootstrap` UI flag only; D-LAE2) | hidden | ✓ | ✓ | ✓ |
| Case history beside a node (`CaseHistoryRoutes`, `investigations`; D-LAE1) | — (503) | ✓ | ✓ | ✓ |
| Sign-in (`inspecto-oidc`; Preview: demo sign-in) | — | ✓ | ✓ | ✓ |
| la-store-pg (Postgres Investigations) | — | — | ✓ | ✓ |
| D-E7 PDP (`providers/inspecto-policy`) | — | — | ✓ | ✓ |

**Basic = the Link Analysis explorer (phase 3, 2026-10-17).** The five modules are default-reactor modules and ship in
every bundle. The Investigation surface is the set of route modules whose `featureIds()` names `investigations`
(beside `geoLink`): la-api's `InvestigationRoutes`, `DossierRoutes`, `DossierBundleRoutes`, `DraftRoutes`,
`WorkingSetRoutes`, `InvestigationReferenceRoutes`, `InvestigationTemplateRoutes`, `InvestigationCoverageRoutes`,
`InvestigationComparisonRoutes`, `InvestigationCaseRoutes`, `InvestigationMemberRoutes`, `InvestigationEmbedRoutes`,
`PublicInvestigationRoutes`, `GraphRunRoutes`, `IndexRoutes`, `DatasetFilesRoutes`, and the integration surface
(ED-6, `LD-EDITIONS-12`) `InvestigationInboundRoutes` and `IntegrationRoutes` (the geo-link webhook tap sends nothing without the feature); geo-link's
`InvestigationMeasureRoutes` (measures, Alert Rules, standing detection) and `InvestigationDataChangesRoutes`. The host
answers each of them `503 CAPABILITY_UNAVAILABLE` "… available in Standard and above" at dispatch
(`ControlApi.requireModuleEnabled` → `EditionFeatures.gatesRoutes`) when no installed module declares
`EditionFeatures.INVESTIGATIONS`; only `inspecto-ops` (`OpsEditionFeatures`) does. The explorer route modules
(`InvRoutes`, `PatternRoutes`, `ValueMeasureRoutes`, `EntityIdentityRoutes`, `EntityProfileRoutes`,
`ExclusionListRoutes`) own `geoLink` only and answer with no sign-in. `GeoRoutes` names `geoLink` and `geoMap`
(D-LAE5, 2026-10-20): Basic answers `POST /geo/projection` and `POST /geo/routes` 503 `CAPABILITY_UNAVAILABLE` while
the other Link Analysis modules stay up; `/bootstrap` reports `features.geoMap`, and the console nav, the LA app Geo
area and the Menu Builder Geo view key on it. `EntityPageRoutes` names `geoLink` and `entityPages` (D-LAE4, 2026-10-25): it
holds only the two profile GETs, split out of `EntityProfileRoutes` so the Entity Type list, resolve and suggest (read by the
Basic Start box, Add data and Open in Link Analysis) stay open; Basic answers the two 503, `/bootstrap` reports
`features.entityPages`, and the console nav, the LA app Entities area and Open-in-LA's "Open the Entity" link key on it.
⚠ The canvas node dialog's Entity page link (`entityPageOf` in `link-analysis.component.ts`) does not key on it yet (row
`LA-EDITION-PLACEMENT-1`). `laWidgets` (D-LAE2) only hides the Link analysis and Geo map Widget
types in the Widget builder on Basic; their routes stay open. `/bootstrap` reports `features.investigations` (false on Basic), `laLevel` and
`featureReasons` (below). Pinned by `ControlApiBasicEditionLinkAnalysisTest` (geo-link; Basic and Standard over real HTTP).

**Trust vs level (`LA-ONBOARD-1` phase 1, 2026-10-22; OB-5, OB-6, OB-10).** Three axes are kept apart: the EDITION decides who
the user is (sign-in, masking, roles - DT-11: still the one governance switch), the **LA level** decides the screens, and
data origin decides the entry points (phase 2).
- **The Investigations add-on is enforced at runtime.** Product Map `addon.laInvestigations` (Order Code
  `INS-AO-LA-INVESTIGATIONS`, `enforcement: runtime`, a vocabulary value added for it; `offerings/standard.toon` add-on
  `laInvestigations` on module `ops`, inherited by Advanced and Preview). `EditionFeatures.present(INVESTIGATIONS)` = a module
  declares it (Standard and above) AND `EditionFeatures.holdsInvestigationsAddon()`. The add-on is held by default; the install
  setting `-Daddons.laInvestigations=false` (any value but `true`, read live) removes it. ⚠ **The hook:** that method is the
  ONE place the proof is read; a signed entitlement replaces its body (until then whoever runs the server can claim the
  add-on). Basic never has it - no module declares the feature, whatever the setting says. Without it every Investigation
  route answers `503 CAPABILITY_UNAVAILABLE` with `refusal(INVESTIGATIONS)`, which names the add-on and its Order Code on a
  Standard+ install ("… need the Investigations add-on (INS-AO-LA-INVESTIGATIONS), which this installation does not hold
  (addons.laInvestigations=false) - Link Analysis runs at the Explorer level") and keeps "available in Standard and above,
  with the Investigations add-on" on Basic. Every other gate (route dispatch, `/bootstrap`, the geo-link webhook tap) reads
  `present()`, so nothing else changed.
- **`/bootstrap` contract** (space-agnostic, also served before sign-in): `laLevel: "explorer" | "investigations"`
  (`investigations` exactly when `features.investigations` is true) and `featureReasons: {featureId: oneLine}` -
  `EditionFeatures.lockedReasons()`: one entry per WITHHELD route feature (today `investigations`, `geoMap`, `entityPages`), none for a
  present one, always an object. The line is `EditionFeatures.lockedReason(f)` ("Needs the Investigations add-on, which this
  installation does not hold." / "Investigations are in Standard and above, with the Investigations add-on." / "The Geo map
  is in Standard and above."); a feature without a short line falls back to `refusal(f)`, so DATA-TIERS-1 phase 2 extends the
  map by adding refusals. ⚠ A feature switched off per Space (`modules.toon`) reports false with NO reason (it is not
  withheld by the edition); the SPA then shows its own fallback line.
- **Locked, not hidden** (A-11). Shared `<inspecto-locked-action label reason>` (`src/app/inspecto/components/
  locked-action.component.ts`, usable by the console and `la-app`): the action's own stroked button, disabled but focusable
  (`disabledInteractive`, `aria-disabled`), a lock icon, and one visible line (`aria-describedby`). The SPA reads
  `SessionService.laLevel` / `featureReasons`; Link Analysis gets them through `LA_FEATURES.featureReasons` (optional) and
  `investigationsLockedReason()` (fallback "Investigations need the Investigations add-on (Standard and above)."). Locked on
  the Explorer level: *Start an Investigation from the top results* under a ranking (`LinkAnalysisResultNextComponent`), the
  Investigation toolbox tab (kept in the tablist; its panel is the locked *Start an Investigation*; the rail's *Open the
  Investigation* opens it), *Run on server* / *Run on index* (`LinkAnalysisServerRunComponent`, the over-cap note kept), and
  the *All algorithms (server)* and *Edge index* tool groups (listed, their action locked). Whole Standard+ pages stay out of
  the navigation (`la-nav.ts` Alerts, `investigationsGuard`). Deep links (`?investigation=`, `?seed=`) still do not open one.
  ⚠ *Find paths (server)* is NOT locked: `POST /inv/traversal/recursive-paths` is an explorer route (`InvRoutes`, every
  edition; EDITIONS "Find paths … ✅ Basic").
- **The masking default keys on sign-in** (OB-6, A-16). `EditionMaskingDefault` answers `none` when nobody signs in
  (`auth.mode` none AND no `Authenticator` installed) and `typed` otherwise, whatever the level: a sign-in install without the
  add-on stays masked. `LinkAnalysisSettings.effectiveMaskingMode` (entity-store, the one seam every masking reader and
  `GET /settings/link-analysis` `maskingModeInForce` share) asks this `MaskingDefault` ServiceLoader provider, read live; with
  no provider on the classpath (entity-store / la-api unit tests) the shipped `typed` holds. A stated mode always wins. It is
  the Space's one mode, so `BiResultMasking` (BI queries, KPIs, DB browser, Report Job exports) follows it too; a public
  dashboard share reads only a STATED mode, else `typed`, since its anonymous holder cannot reveal (`BI-QUERY-MASKING-1`
  review, 2026-10-23). ⚠ Tests: no test classpath sets `auth.mode`, so every test without an armed `Authenticators.forTest` runs
  as "no sign-in" (`none` by default); a test relying on the `typed` default sets `-Dauth.mode=oidc` or arms an
  Authenticator (the `investigations` pin no longer matters). With no sign-in there is no Subject, so the capability behind an
  audited reveal is never checked and anyone may reveal; a stated mode stops a casual glance, not a user.
- Pinned by `ControlApiInvestigationsAddonTest` (Standard without / with the add-on, Basic with the setting on),
  `ControlApiMaskingDefaultBySignInTest` (the matrix: no sign-in → none, also at the Investigations level; sign-in with and
  without the add-on → typed; a stated mode wins), `EditionFeaturesTest.investigationsNeedTheEditionAndTheRuntimeAddon`, and
  the vitest specs `locked-action.component.spec.ts`, `link-analysis-toolbox.component.spec.ts` (result next),
  `link-analysis-toolbox.server-run.spec.ts` (Explorer level) and `link-analysis.component.spec.ts` (Explorer level), each with
  axe.
⚠ `POST /inv/case-history` (Case history beside a node) is Standard and up by an explicit gate since 2026-10-20 (D-LAE1): it
moved out of `InvRoutes` into its own route module `CaseHistoryRoutes` (an edition gate is per `RouteModule`) naming
`geoLink` and `investigations`, so Basic answers 503 "available in Standard and above" at dispatch and the node dialog hides
the section. The scheduled link-index builder (`la.index.build` Job) is not edition-gated — its routes are.
⚠ Route edition features are the set `EditionFeatures.ROUTE_FEATURES` (`investigations`, `geoMap`, `entityPages`; generalised from one
id 2026-10-20), and each member needs its own `refusal()` text (`EditionFeaturesTest`). `laWidgets` is deliberately not in
the set: it is a `/bootstrap` UI flag only. A new route gate goes on a whole `RouteModule`, so a route that must differ from
its neighbours moves to a module of its own first, as Case history and the Entity profile pages did. Placement decisions
D-LAE3, D-LAE4, D-LAE6 were answered 2026-10-25: `docs/superpower/link-analysis-roadmap.md` §D3.
The Postgres store also needs `-Dinvestigations.backend=db` plus a `jdbc:postgresql:` URL; the jar alone keeps the
filesystem store.

Jars are staged by `inspecto/package.ps1`; `-Ui la-app` swaps the bundled SPA, not the edition. Enablement then runs
Installed (jar present) → Enabled (`modules.toon` `geoLink`, per Space) → Permitted (capability + gates above).

## 9. Concurrency and resource bounds (summary)

**Every bound is a configuration item (`LA-CONFIG-LIMITS-1`, built 2026-10-15).** One catalogue, `LaLimit`
(`platform/inspecto-entity-store`), holds every Link Analysis limit: key (= its TOON path and wire key), layer, whether
an analyst may override it in their browser, group, unit, default, per-item `min..max`. Decisions CL-1…CL-18 and the
lane record: [`archived plan`](../../../../archived-documents/plans-archive/la-configurable-limits-plan.md).

* **Three layers.** SPACE items live in the Space's `link-analysis.toon` (Space admin, `canAuthorWorkbench`); INSTALL
  items live in `la-limits.toon` in the server-wide document home (resolved like `/system/scheduler`:
  `-Dsystem.config.dir`, spaces container root, sole Space's write root; installed at boot; reserved in
  `ReservedConfigPaths`) and are edited by `canAdminister`; `browser:true` SPACE items may also carry a per-analyst
  value in the browser's `localStorage`, clamped to `[min, Space value]`.
* **Routes.** `GET /settings/link-analysis/limits` (every item with `default`, `min`, `max`, stated `value`,
  `inForce`, `editable` for the caller; pod-scoped), `PUT /settings/link-analysis/limits` (SPACE keys) and
  `PUT /system/link-analysis/limits` (INSTALL keys): all or nothing, an unknown key, a wrong-layer key or a value
  outside `min..max` is 422 naming the key and the range; Draft expiry > hibernation and embed default TTL ≤ max are
  checked when the body touches them.
* **Reading.** `LaLimits.forSpace(writeRoot)` → `LaLimitValues`, cached on file CONTENT; a stored value outside its
  range reads as the default. la-api sites read through `SpaceLimits`; library code (la-storage, la-core) takes the
  value as a parameter from its la-api caller. INSTALL values: install doc, else the old JVM property where one
  existed (`control.rateLimit.*`, `assist.sql.*`, `inspecto.la.draft.heavy`), else the default.
* **Writers are read-modify-write over the raw TOON map** (`LinkAnalysisSettings.write`), and the old
  `PUT /settings/link-analysis` keeps any limit key its body leaves out (`SettingsRoutes.keepUnsentLimits`; a stated
  null still clears). ⚠ Before this, the old PUT dropped every key it did not model.
* **Hot apply.** Graph Run ceilings and pool size: `GraphRunService.retune` on every request (the queue is bounded at
  submit so it can shrink); index pools: `IndexBuildService.retune`; rate buckets: rebuilt at boot and after the
  system PUT (a hand edit of `la-limits.toon` applies at the next PUT or restart); traversal memory / threads: read per
  call; heavy Draft permits: re-read per heavy job. A Graph Run service's SQL engine keeps the traversal policy it was
  built with until it idles out.
* **Index build memory.** `index.build_memory_pct` (default 80) → a DuckDB `memory_limit` of that share of physical
  RAM, passed to `IndexBuilder.Options` (unknown RAM → the shared DuckDB cap). **A build waits while ingest runs**:
  `IngestActivity` (inspecto-util) counts Consignments from before `ConcurrencyBroker` grants a slot until it is
  released; a build stays QUEUED in phase `waiting_for_ingest` (backoff 0.5 → 5 s, cancellable, FAILED
  `IngestStillActiveException` after 6 h). One-way: ingest that starts after a build starts is not held back.
  ⚠ The "8 GB" in the scale measurements is the bench's `inspecto.bench.buildMemory`, not production.
* **Bucket sizing** (`index.edges_per_bucket`, `min_buckets`, `max_buckets`, INSTALL): a full build records
  `bucketSizing` in the manifest; a changed sizing makes `IndexPlan.classify` force a full build
  (`bucket_sizing_changed`; append refused 409).
* **Suspicion score has ONE cap** for browser and server: `suspicion_node_cap` (default 1,000) drives the browser
  refusal, the server's inline-or-job decision and the ceiling echoed by `GET /inv/graph/algorithms`.
* **SPA loading.** `LaLimitsService` (root) reads the GET once per Space and publishes into the `la-limits` store the
  pure modules read. A host that projects once, up front, awaits `whenLoaded()` first — the dashboard Link analysis
  widget does, so every tile shares one GET and a failed read leaves the shipped fallbacks (built 2026-10-19). The Geo
  map widget loads none: its sources leave `limit` unset, so `GeoRoutes` applies the Space's `display.geo_point_cap`.
  Both widgets show the studio's *Truncated* wording when a cap cut the answer.
* **Parity.** `LaLimitCatalogueParityTest` parses the SPA's `LA_LIMIT_FALLBACKS` (`la-limits.ts`) and fails on a key
  the catalogue lacks or a default that differs; it also pins that no wired site still holds its old constant.

| Resource (key) | Default | On overflow |
|---|---|---|
| Graph Run pool (`graph_run.threads` / `queue`, INSTALL) | 2 threads, queue 16 | `REJECTED` |
| Graph Run ceilings (`graph_run.ceiling_*`, INSTALL) | 500,000 nodes, 5,000,000 edges, 300 s | budget clamped, `budgetClamped` echoed |
| Index builds (`index.threads` / `queue`, INSTALL) | 1 thread, queue 4, one per (Dataset, mapping) | 503 / 409 |
| Index disk (`index.max_disk_bytes`) | none; estimate `rows × 34 B` | 422 before any work |
| Index deltas (`index.max_deltas`) | 8 | `append` refused, `compact` advised |
| Index traversal (`index.traversal_depth` / `frontier_cap`) | depth 2, frontier 20 | flat fallback with a closed `Reason` |
| Recursive paths (`traversal.*`) | depth 12 / max 20, 10 s, paths 2,000 / 20,000 | fenced, `truncated` |
| Expand (`investigation.expand_budget_*`, `max_frontier`) | 4,000 / 40,000 rows, frontier 2,000 | clamped / refused |
| Connect (`connect.*`) | 40 seeds, 3 hops default, 6 max | 422 |
| Open Drafts (`drafts.max_open`) | 50 per Space, 1 per member | 409 |
| Heavy Draft ops (`drafts.heavy_jobs`, INSTALL) | `max(1, min(4, cores/3))`, never waited for | 429 |
| Expensive exploration POSTs (`rate.link_analysis_*`, INSTALL) | burst 30, 1 per 2 s per subject | 429 |

## 10. Known seams and residuals

* **Collector coverage has no expected-Collector roster** (decided 2026-10-18 under operator delegation, BACKLOG §6): a Collector
  that never delivered is invisible. A derived roster is wrong for single-schema / segmented Pipelines (their batches record no
  `output_table`); an authored list needs a new write route. ⚠ `GET /lineage` (`LineageRoutes`) still reads only the
  configured run's audit CSV, the blind spot `HostCollectorCoveragePort` fixed for coverage on 2026-10-14.
* **Limits settled 2026-10-18** (decided under operator delegation): browser betweenness (500) stays below suspicion score
  (1,000) — suspicion computes betweenness unguarded, so it is never refused in betweenness's words; Graph Run and index pools
  are one per Space (a multi-Space host multiplies them); a hand edit of `la-limits.toon` reaches rate buckets at the next system
  PUT or restart and a Graph Run service's traversal policy at its idle-out; the Case member cap is a fixed 100 on both sides
  (`CaseRoutes.MAX_CASE_ENTITIES`, 422 above it; SPA `MAX_CASE_MEMBERS`), not a configuration item. Open: the CL-10 defaults
  were measured on a laptop, not a server-class host (`LA-CONFIG-LIMITS-RESIDUALS-1`).
* Index scale is proven to 10^8 edges; 10^9 is extrapolation (`LA-INDEX-SCALE-MEASURE-1`). Entity cardinality measured
  2026-10-20 (`IndexCardinalityBench`, 10^7 edges in one file): 4.85 M nodes FULL 38 s, 12.1 M nodes FULL 48 s, peak 3.5-3.6 GB
  (1.8 M nodes: 31 s, 3.3 GB), so cardinality costs time in the nodes step, not memory. *Qualified 2026-10-21:* a peak working set
  under a generous cap is what DuckDB chose to use of it, not what the build needs (a 10^8-edge FULL build completes at 1 GB); build
  memory follows the number of distinct (node, neighbour, kind) links, see §4 *Build memory*.
* **A day that lands while a FULL runs (2026-10-20, `IndexFullPinTest`, `ScheduledIndexBuildTest`).** The scheduled run renders
  the relation, the service lists the inputs at submit, and the build reads the relation later: three moments. A store with a
  Consignment registry renders a pinned file list (a landing day never reaches that read), but the relation and the
  manifest's listing are two separate reads, and the fallback relation is a glob every statement re-expands. A FULL now reads
  exactly the listing it records in its manifest (`IndexBuilder.fullReadSql`, through the Dataset's own file-list renderer;
  no renderer or no listing = the relation as given), so the index never holds a day the manifest lacks nor lacks one it claims.
  Without it a day listed after the relation was rendered was silently missing from an index its manifest called current, and
  over a glob a day landing mid-FULL failed the build's own verification or was appended a second time. The Job that meets the
  FULL waits (audited `BUILD_IN_PROGRESS`), then re-plans and APPENDs the landed day once (6 edges, not 9).
* The index builder connection is deliberately unsandboxed; its safety rests on the trusted-relation contract and
  the caller's view gate.
* Per-key index lookup cost is linear in the key's degree; the frontier cap bounds key count, not hub cost.
* `FsInvestigationStore` carries a self-declared TRANSITIONAL note: path-keyed Draft code still reaches into the
  directory; legacy `SnapshotStore` still exists in la-core.
* Entity List purposes (`allow | block | watch | exclusion`) are a free string in the fold; no enum enforces them
  in entity-store.
