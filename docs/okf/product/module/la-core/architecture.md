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
Audited as `LINK_ENTITY_TYPES_LISTED`, `LINK_ENTITY_TYPE_PROFILED`, `LINK_ENTITY_PROFILED`, `LINK_ENTITY_KEY_RESOLVED` (never a
key). Rate class: the list exempt; both profiles and resolve expensive.

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
  groups and the optional "Investigate a number" binding), `industry-pack-terms.toon`
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
* **Residuals.** The SPA renders the concept, Entity Type and Measure relabels and the term table; `link_kind.*`,
  `pattern.*` and `term.*` relabels are published and listed but not yet drawn on the canvas legend or the pattern
  picker. The node-risk mapping and the pattern-pack column defaults in the SPA are still keyed by the `telecom` id.

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

24 `RouteModule`s in la-api plus `InvestigationMeasureRoutes` in geo-link, **97**<!--count:la-routes--> routes (**90**<!--count:la-api-routes--> in la-api + **7**<!--count:geo-link-routes--> in geo-link;
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
| Entity profiles (2026-10-15) | `GET /inv/entity-types`, `GET /inv/entity-types/{id}/profile`, `GET /inv/entities/{type}/{key}/profile`, `POST /inv/entities/{type}/resolve` (la-api `EntityProfileRoutes`) | none beyond a subject (like the exploration reads); Dataset view gate per binding; profiles rate-limited as expensive |
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

* **Routes** (`EntityProfileRoutes`, la-api, gated and audited like the exploration reads): `GET /inv/entity-types` (cheap, reads the cache only),
  `GET /inv/entity-types/{id}/profile?sections=` (bindings, volume, shapes, time, lineage, links, relationships),
  `POST /inv/entities/{type}/resolve {key}` and `GET /inv/entities/{type}/{key}/profile`. Audit events `LINK_ENTITY_*` (three).
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
* **Residuals:** `LA-ENTITY-PROFILE-RESIDUALS-1` in `BACKLOG.md`.

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

`UploadRoutes` (la-api) lands raw CSV bytes in the inbox of a Collector and does nothing else: the Collector's Pipeline ingests the file, and the existing identity import and index build then read the Dataset. The decisions are **assistant-decided under operator delegation, pending operator confirmation** (`docs/superpower/la-analyst-upload-plan.md`).

- **Target:** only a local poll Collector with `collector.accepts_upload: true` (`ConfigSpecs`, `PipelineConfig.Collector.acceptsUpload`), resolved through `UploadLandingPort` (la-core) by the geo-link bridge `HostUploadLandingPort`. Unknown, not opted in and not local all answer one 404. The analyst never picks the Pipeline, schema, Dataset or path.
- **Capability:** `canUploadData` (default to the roles that hold `canBuildLinkIndex`); rate class EXPENSIVE; Basic edition answers 503 with the other Investigation routes.
- **Gates, in order (cheap refusals before the body is read):** (1) capability 401/403, (2) write root 503, (3) name missing 422, (4) path-shaped name 403, (5) Collector 404, (6) charset, `.csv` and the Collector's include patterns 422, (7) size 413 (`upload.max_bytes`, 25 MiB; a declared length is refused before reading, a chunked body after cap+1 bytes), (8) empty, zip/XLSX magic, NUL or non-UTF-8 content 422, (9) resolved path jail 403, (10) D-U7 four-eyes 403, (11) same name in the inbox 409 (`replace=true` opts in), (12) per-Space daily quota 413 (`upload.daily_bytes`, 200 MiB), (13) atomic temp file and move (`AtomicFiles`).
- **Four-eyes** is the existing D-U7 row budget (`four_eyes_budget_above`, rows = lines in the file) through `InvRoutes.refuseIfSensitive`; no threshold set means no gate. Classification is **not** re-judged at upload: the Pipeline's schema and the Dataset read mask as for any Dataset.
- **Audit:** `EventType.LA_UPLOAD` per attempt after the capability gate (Collector, file, bytes, sha256, outcome, refusing gate); never row content. The daily total is a per-Space ledger `<config root>/audit/la-uploads/<UTC day>.bytes` (landed bytes only, one JVM lock).
- **Status, delete and retention (LA-I2-GAPS-RESIDUALS-1 item 1, 2026-10-19).** Each landed upload also writes a metadata record `<config root>/audit/la-uploads/records/<collector>/<file>.json` (file, bytes, lines, sha256, uploader, landedAt, replaced; never content), pruned after 90 days (`UploadRoutes.RECORD_RETENTION_DAYS`); the landed file itself follows the Collector's own retention (D-UP7 stands). Three routes, all `canUploadData`: `GET /inv/uploads` lists the upload-enabled Collectors (id, Pipeline, include patterns, `maxBytes`; no paths); `GET /inv/uploads/{collector}` lists the caller's own uploads (every upload for `canAdminister`, or when no Subject is attached), newest first, at most 200, rate-classed expensive, each with `state` from `UploadLandingPort.ingest` (`pending` still in the inbox, `ingested` the Collector's `.processed` marker exists, `quarantined` the whole file sits in a quarantine reason folder, `consumed` left the inbox with no marker because duplicate check is off), `rejectedRows` when the Pipeline wrote its `_errors.csv` ledger, and `quarantineReason`. `DELETE /inv/uploads/{collector}/{file}`: 503 no write root, 404 Collector, no record (exact, case-sensitive name) or another analyst's upload (one answer, no existence oracle), 409 once the Collector has picked the file up (marker, fingerprint ledger or quarantine; its rows stay in the Dataset) or while a run of the Pipeline is in flight - the delete takes the Pipeline's run claim without blocking (`CollectorService.whileIdle`), re-checks, renames the file atomically to a `.tmp` name the poll never picks up and only then deletes it; any filesystem fault is an audited 409, never a 500. `replace=true` on the POST is likewise the uploader's or an administrator's (403 otherwise) and never changes the recorded uploader. Otherwise the inbox file and record are removed and `LA_UPLOAD` (`la.upload.delete`, outcome `deleted` / `refused`) is audited. The daily-quota ledger is not credited by a delete. **Hardening as built (LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1 (2)-(6), 2026-10-20):** (2) `HostUploadLandingPort.withdraw` reads the ingest state from the filesystem BEFORE the run claim, so a picked-up file answers 409 picked-up without touching the claim (and never "busy"); the claim is taken only for a pending file and the state re-checked inside it. (3) The fingerprint-ledger "ingested" test compares the entry's recorded `lastModified` (the file's mtime at ingest) with the file's current mtime, not the processing host's wall clock (`processedAt`), so clock skew cannot flip a state. (4) `UploadLandingPort.whileIdle` sweeps stranded `.<name>.withdraw-<nanos>.tmp` files from the inbox's top level on entry, under the claim (so none can be a live withdraw): regular files of exactly that name shape only, never a folder, junction or link (`NOFOLLOW_LINKS`, not recursive); the sweep points are the next withdraw or replace on that Collector. (5) The reject ledger is counted up to `HostUploadLandingPort.REJECT_COUNT_CAP` (10 000) rows; past it `rejectedRows` is the cap and the row carries `rejectedRowsCapped: true`. (6) `replace=true` writes through `whileIdle`, so it takes the run claim: 409 (audited gate `busy`) while a run of the Pipeline is in flight; a fresh name needs no claim. **Review follow-ups (same day):** the sweep matches only `.<name>.withdraw-<nanos>.tmp` where `<name>` is accepted by THIS Collector's include/exclude patterns, so another Pipeline polling the same inbox under different patterns is not swept (the run claim is per Pipeline; two Pipelines with overlapping patterns on one inbox remain a known limit); the reject ledger scan also stops at `REJECT_READ_CAP_BYTES` (4 MiB), and exactly the cap followed by blank lines is an exact count; a `replace=true` re-checks, under the claim, that the file is still in the inbox (409 if the Collector took it meanwhile - not covered by a test, the window is not reachable over HTTP). The SPA upload pane shows the server's busy message for a replace refused mid-run (no Replace offered) and "at least 10,000 rows rejected" when capped. Deferred: no new rate class for the delete (the pre-claim check makes repeated deletes filesystem-only); the cap is a code constant, not a Space limit. Status rows carry file metadata only, so there is nothing to mask; they are filtered to the caller by uploader id.
- **Visual Pipeline editor keeps the flag.** The editor saves through `PipelineEditable.toMap` / `lower`, which carries every `collector:` key verbatim on the acquisition node, so `accepts_upload` survives a save (also after editing another Collector key and under the strict lower); pinned by `CollectorAcceptsUploadLiftLowerTest` (engine). `PipelineLift` / `PipelineCompiler` (the typed-record rebuild) do not carry it, but nothing on the editor save path uses them.
- **Follow-ups:** the 409 sees only a file still in the inbox (an ingested same-name file is caught by the Collector's checksum duplicate mode); the ledger is not shared across nodes; no raw-socket test of a lying Content-Length. Tests: `ControlApiAnalystUploadTest` (geo-link).

### 6.7 Masked-alias refusal on identity writes (as built 2026-10-18)

`EntityIdentityRoutes.refuseAlias` (predicate `MaskTokens.TOKEN_PREFIX`, the one the other routes use) answers **422 before any fact is appended** when `POST /inv/entity-identities` (`a` or `b`) or `POST /inv/entity-identities/import` (any Dataset row) carries an alias-shaped value such as `msisdn:masked:<16hex>`. Why: a masked analyst only holds aliases, and asserting one appended a permanent Space-wide `identity.asserted` fact on a garbage literal key. That is an **integrity** hole, not a read leak (the response re-masks it). The server does not resolve the alias (that would need `canRevealLinkEntities`); it refuses. Retract takes a seq and resolve/seed carry no identity facts, so they are unaffected. Probes: `ControlApiEntityIdentityTest.assertRefusesAMaskedAliasAndWritesNothing`, `ControlApiEntityIdentityImportTest.aMaskedAliasInTheDatasetRefusesTheImportAndWritesNothing`.

## 7. Host registration, Jobs and live detection

* **ServiceLoader files:** la-api → `RouteModule` (21); geo-link → `RouteModule`, `InvestigationMeasureProbe`,
  `CasePort`, `CollectorCoveragePort`, `DatasetProvider`, `LinkIndexBuilder`; la-store-pg →
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
| Investigation surface (`investigations` edition feature, declared by `inspecto-ops`) | — (503) | ✓ | ✓ | ✓ |
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
(`InvRoutes`, `GeoRoutes`, `PatternRoutes`, `ValueMeasureRoutes`, `EntityIdentityRoutes`, `EntityProfileRoutes`) own
`geoLink` only and answer with no sign-in. `/bootstrap` reports `features.investigations` (false on Basic); the SPA's
`LA_FEATURES.investigations` hides the Investigation tab and buttons, the deep-link openers, Graph Run controls and the
edge-index tool. Pinned by `ControlApiBasicEditionLinkAnalysisTest` (geo-link; Basic and Standard over real HTTP).
⚠ **Masking on Basic is off by default, and display-only when on** (operator 2026-10-21). The in-force mode of a Space that
states none is `none` on Basic and `typed` elsewhere: `LinkAnalysisSettings.effectiveMaskingMode` (entity-store, the one seam
every masking reader and `GET /settings/link-analysis` `maskingModeInForce` share) asks the `MaskingDefault` ServiceLoader
provider; the control plane ships `EditionMaskingDefault` (`none` when `investigations` is absent, read live), and with no
provider on the classpath (entity-store / la-api unit tests) the shipped `typed` holds. A stated mode always wins. It is the
Space's one mode, so `BiResultMasking` (BI queries, KPIs, DB browser, public dashboard shares, Report Job exports) follows it too.
⚠ The `inspecto` module's own test classpath declares no `investigations`, so it runs as Basic: a test there that relies on
the `typed` default must pin Standard (`EditionFeatures.overrideForTest`). Pinned by `ControlApiBasicEditionMaskingDefaultTest`
(geo-link, real HTTP). With no sign-in there is no Subject, so the capability behind an audited reveal is never checked and
anyone may reveal; a stated mode stops a casual glance, not a user. Enforced masking starts in Standard. The standalone LA app
also hides its Alerts area on Basic (`la-nav.ts` `feature: 'investigations'`, `investigationsGuard` on `/alerts`).
⚠ `POST /inv/case-history` (Case history beside a node) is Standard and up by an explicit gate since 2026-10-20 (D-LAE1): it
moved out of `InvRoutes` into its own route module `CaseHistoryRoutes` (an edition gate is per `RouteModule`) naming
`geoLink` and `investigations`, so Basic answers 503 "available in Standard and above" at dispatch and the node dialog hides
the section. The scheduled link-index builder (`la.index.build` Job) is not edition-gated — its routes are.
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
