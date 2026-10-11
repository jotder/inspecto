---
type: Concept
title: Link Analysis backend architecture (la/ modules, entity store, host wiring)
description: How the Link Analysis backend is built - the six la/ modules plus platform/inspecto-entity-store, their dependency direction, the Investigation op log and store port (filesystem / Postgres), Drafts, masking, the link index (la-storage), Graph Run (la-graph + la-core), the /inv and /geo route layer, the request gate order, host registration, editions, audit, rate limits and live detection.
resource: la/
tags: [module, link-analysis, investigation, index, graph-run, drafts, architecture]
timestamp: 2026-10-09T00:00:00Z
---

# Link Analysis backend architecture

Grounded in a code read on 2026-10-09 (tip `957c554af`). This page is the **backend structure**; behaviour,
decisions and measurements live in the feature concept
[`okf/frontend/features/link-analysis.md`](../../frontend/features/link-analysis.md), open work in
[`superpower/link-analysis-roadmap.md`](../../../superpower/link-analysis-roadmap.md). Class names are given
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
        │                (la-graph, entity-store, http/auth/audit-spi, config, sql, util)
        ├── la/inspecto-la-graph      pure-Java graph algorithms, RunControl   (no inspecto deps; enforcer-fenced)
        └── platform/inspecto-entity-store   Entity Types, Entity Lists, Identity Fact log, MaskTokens, LinkAnalysisSettings
   la/inspecto-la-store-pg   Postgres InvestigationStore (ServiceLoader provider)   (la-core)
```

| Module (manifest id) | Role | Key classes |
|---|---|---|
| `la-graph` | Leaf algorithm library, line-for-line port of the SPA's `graph-analysis.ts` | `GraphAlgorithms`, `GraphCentrality`, `GraphIterative`, `GraphPaths`, `GraphStructure`, `GraphSuspicion`, `GraphPropagation` (server-only), `RunControl`, `GraphAborted` |
| `la-core` (platform, optional, boot) | Domain + ports, no HTTP routes | `InvestigationEvaluator`, `InvestigationStore`/`InvestigationStores`/`FsInvestigationStore`, `InvestigationMembers`, `DraftStore`/`DraftLifecycle`/`DraftCheckpoints`/`DraftIndex`, `GraphRunService`, `GraphEngine`, `InMemoryGraphEngine`, `GraphInput`, `Algorithm`, `GraphBudget`, `LinkEventTypes`, ports `DatasetProvider`/`CasePort`/`CollectorCoveragePort` |
| `la-storage` | The link index (D-3) and the index-backed engine | `IndexBuilder`, `IndexBuildService`, `IndexStore`, `IndexManifest`, `IndexMapping`, `IndexPlan`, `IndexReader`, `IndexPins`, `BucketFunction`, `IndexedTraversal`, `SqlGraphEngine`, `RoutingGraphEngine` |
| `la-api` (feature `geoLink`) | HTTP surface and orchestration | 18 `RouteModule`s (below), `EntityMasking`, `InvestigationMemberStore`, `DraftAdmission`/`DraftRebase`/`DraftPromote`, `IndexedRead` + `Indexed*`, `IndexStaleness`, `IndexBuildServices`, `GraphRunServices`, `StandingDetection`, `ScheduledIndexBuild` |
| `geo-link` | Bridge to the host | `EngineDatasetProvider`, `HostCasePort`, `HostCollectorCoveragePort`, `ScheduledLinkIndexBuilder`, `WorkingSetMeasures`, `InvestigationMeasureRoutes` |
| `la-store-pg` (provider) | Postgres backend for Investigations | `PgInvestigationStore`, `PgInvestigationStoreProvider` |
| `entity-store` (platform, base) | Space-wide identity data; not LA-only | `EntityFactLog`, `EntityRegistry`, `EntityTypes`, `EntityListEntries`, `EntityListFacts`, `EntityListSidecar`, `MaskTokens`, `LinkAnalysisSettings` |

Design rules visible in the dependency graph:

* **Ports point inward.** la-core defines `GraphEngine`, `GraphInput`, `DatasetProvider`, `CasePort`,
  `CollectorCoveragePort` and `InvestigationStoreProvider`; storage, geo-link and store-pg implement them. la-core
  never names an index class: `GraphInput.IndexRef` carries only primitives (version dir, seeds, estimates).
* **The engine names no `la-*` class** (grep-true; LA route paths appear only as strings, and enforced by `tools/check-engine-names-no-la.mjs`). The host reaches LA only through ServiceLoader SPIs it owns
  (`RouteModule`, `LinkIndexBuilder`, `InvestigationMeasureProbe`); absent jars degrade to
  `AbsentModuleRoutes` (503 `CAPABILITY_UNAVAILABLE`, "Professional edition and above").
* **Entity Lists are not LA.** `/entity-lists/*` routes live in `features/inspecto-entity-list`; LA reads the same
  fact log through the host-free `EntityListFacts` helper so neither side depends on the other's routes.

## 3. Domain core (la-core)

### 3.1 Investigation op log

* An Investigation is a header, an append-only `log.jsonl` of **ops**, and one sealed Working Set file per step
  (`sets/<step>.json`, the audit/replay record, not the evaluation input).
* `InvestigationEvaluator` is a **pure fold** of the log. The apply switch handles 14 labels: `seed`, `seedBy`,
  `expand`, `exclude`, `excludeBy`, `hide`, `keep`, `threshold`, `annotate`, `window`, `resolve`, and the markers
  `snapshot`, `compare`, `temporal`. An unknown op throws "not evaluable" (fail closed).
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
  column `highConnectivity`). Later frontiers leave flagged entities out (`rung.hubsHeld`) unless the op carries the
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
  flagged says so (409) rather than "left the Working Set". The hub degree is still a flat statement when the index
  answered the rows — the index's folded buckets would serve it, but that read has not been added.
* **Events weighting (2026-10-11).** Optional header binding `eventsCol` (create / template instantiate; an
  INTEGER-family column, 422 otherwise; key absent when unbound, so an unweighted header and every older one is
  byte-identical). When set, the expand CTE's pair weight is `CAST(SUM(COALESCE(CAST(eventsCol AS BIGINT), 1)) AS
  BIGINT)` instead of `COUNT(*)` — a NULL count is one row's worth — and that weight drives `minEvents`, the
  `maxFanOut` rank, the budget order and the sealed `count`. `minDistinctDays` and the degree checks
  (`candidateDegree*`, hub degree: distinct counterparties) are unchanged. The edge index folds rows, so a weighted
  rung always falls back to the flat read with `read.fallback {reason: rung_not_indexable, details: "...events
  column..."}` (when an index exists; never a silently wrong count). Templates carry `eventsCol` in `roles` only
  when bound; the Dossier names it in the method statement.

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
| Editions | every LA edition | Preview and Enterprise only |

⚠ The FS layout's Javadoc says `<audit root>/investigations/<id>`; the code writes `audit/snapshots/investigations/<id>`. Creates, forks,
references, Case link and Alert Rule binding are last-writer-wins by design on FS.

### 3.3 Drafts (D-7)

* A Draft lives **inside** its Investigation (`drafts/<draft-uuid>/{header.json, log.jsonl, sets/, discarded.json,
  promoted.json}`); its state = the main log's first `baseStep` entries folded + its own entries, numbered
  continuously. Created by staging in a scratch dot-directory and an atomic move.
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
by hard link so a race loser reads the winner's key. One key per Investigation and one per Space fact log, so a
list member's token differs from an Investigation's for the same value. `LinkAnalysisSettings.maskingMode` defaults
to `typed` (fail closed). `EntityMasking` (la-api) masks on the way **out**: engine results and caches stay raw; a
raw id that masking hides is answered as a nonexistent node; `reveal` needs `canRevealLinkEntities`.

**Mask the same way everywhere (DR-D2, operator 2026-10-10).** The stateless exploration reads (`/inv/projection{,/neighbors,/multi}`,
`/inv/traversal/recursive-paths`, `/inv/pattern/*`, `/inv/value-measures`) have no Investigation, so `ExplorationMasking`
(la-api) masks them under the **Space** key (the identity fact log's `mask.key`, the key an Entity List member uses): `all`
masks every id; `typed` masks every id when a bound endpoint column's classification is claimed by a masked Entity Type
(`EntityMasking.maskedColumns`, the rule an Investigation applies); `none` shows raw. Each answer carries
`masking {mode, masked, basis}`, which the SPA shows as a *Masking: mode* badge in the query panel. An alias handed back (an
expand's `value`, a traversal's `startNode`, an Investigation seed's `ids`) is resolved from an in-memory alias-to-raw
book of what this server minted for this Space (bounded LRU; nothing raw is ever sent); after a restart or eviction it is
422 *alias not known* and the query is re-run. ⚠ An Investigation keeps its own key, so a seeded entity has a second alias
there: Working Set answers (`/working-set`, `/replay`, op answers) carry `exploreAliases` (Investigation alias to
exploration alias, aliases only) and the SPA matches a selected node to its Working Set entity through it. Sealed logs still
bind raw ids. Known gap: an Investigation also masks a seed's explicit `entityType`, which a stateless read does not
carry, so a column no Entity Type claims is raw on the query graph but seeded ids of a masked `entityType` are aliased in the Working Set.

### 3.5 Entity store (identity data)

* **Identity Fact log** (`EntityFactLog`): `<Space config root>/audit/entity-facts/`, one file per fact
  (12-digit seq), each carrying `prevHash` = SHA-256 of the previous file's bytes; written stage → fsync →
  hard-link publish (refuses overwrite, cross-process). `read()` verifies the whole chain every call
  (`BrokenChainException` → 500 `INTEGRITY_VIOLATION`); a truncated tail is caught only by a cited head hash.
* **Fold** (`EntityRegistry.fold(facts, atSeq)`): list created / members added-removed (optional `expiresAt`) /
  ranges / retired; `identity.asserted`/`retracted` union-find into resolution groups (deterministic only, D-M1).
* **Entity Types** (`EntityTypes`): nine defaults (subscriber, imsi, imei, msisdn, wallet, account, agent, handset,
  cell), max 64, closed normaliser set `default | digits | e164 | upper-trim` that must match the SPA's
  `entity-key.ts` character for character (shared parity fixture). Stored in `link-analysis.toon`.
* **Sidecar** (`EntityListSidecar`): a Parquet projection per list for SQL (`physicalRef` Dataset); a failed write
  never undoes the fact.
* `LinkAnalysisSettings` is the one reader of `link-analysis.toon` (caps, masking, four-eyes thresholds, entity
  types, distinct caps, `hub_threshold`, `index` and `drafts` records, graph-run knobs); null = inherit default, never unbounded.

## 4. Link index (la-storage)

* **Layout:** `<writeRoot>/la-index/<dataset>/<mappingHash>/vNNNNNN/` with a `CURRENT` pointer (build to
  `.tmp`, rename, atomic-move the pointer). A version holds `manifest.json` and three Parquet tables, each
  `PARTITION_BY (bucket)`, zstd, row group 100,000, sorted by (bucket, key, ts): `out/` (edges keyed by src),
  `in/` (the same edges keyed by dst — **both directions stored**, data doubles) and `nodes/`. Columns
  `src, dst, kind, ts, w, a0..ak`; NULL endpoints are dropped and counted; `ts` is naive UTC (session zone pinned).
* **Bucket:** `BucketFunction` = `md5_number_lower(id) % N` (fixed MD5 bytes, golden-tested, Java and SQL
  renderings), `N = clamp(pow2(ceil(edges / 4e6)), 16, 1024)`. DuckDB cannot derive the partition from a key
  equality, so **the reader computes the bucket in Java and writes it as a literal** — without that the layout does
  not prune.
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
  ≤ 20), neighbours, simple Investigation `expand` (byte-identical fingerprint to the flat read), temporal, and Graph
  Run with `input: "index"`. A response never mixes index and flat answers.
* **Scheduled builds:** the `la.index.build` Job → `link-index` Platform Service → `ScheduledLinkIndexBuilder`
  (geo-link) → `ScheduledIndexBuild` (la-api), principal `index-build:<job>` holding only `canBuildLinkIndex`,
  authority = the Job's stamped `owner`, re-decided every run; runs the plan's advice only (`full` needs
  `allow_full`).

## 5. Graph Run (la-graph + la-core + la-storage)

* **Input** is a sealed `GraphInput`: `Materialised` (from the Working Set, `WorkingSetGraphInput`) or `IndexRef`.
  `RoutingGraphEngine` routes **by input type only**, never by size: Materialised → `InMemoryGraphEngine`
  (exhaustive switch over `Algorithm`, so a new algorithm fails to compile until implemented), IndexRef →
  `SqlGraphEngine` (neighbourhood, ego network, seeds-only degree; caps throw `IndexCapExceeded`, no reroute).
* **29 algorithms** (`Algorithm`), each with a cost class (SYNC / JOB, a hint) and a node ceiling (e.g. 500 for
  betweenness, 100,000 for shortest path) that is the inline-versus-job threshold, not a limit — the budget refuses.
  The toolbox has dedicated controls for 18 (*Run on server*) and 3 (*Run on index*); its catalogue-driven *All
  algorithms (server)* panel runs every other one, EXCEPT an algorithm with a list/map parameter it has no control for
  (today only `propagatedRisk`), which it labels *API only* with Run held and the reason stated.
* **`propagatedRisk`** (server-only, `GraphPropagation`, no TS twin; JOB, ceiling 1,000): `raw(n) = own(n) + Σ own(o) ×
  weights[d(o,n)]` over origins `o ≠ n` within `weights.size()` (≤ 6) shortest hops; `score = min(raw, 100)`. Params:
  `nodeScores` (id → 0-100, request-only — nodes carry no attributes, so no `scoreAttribute`; no Space default yet),
  `seeds` (empty = every node with own > 0), `weights` (each in [0,1], default `[1, .6, .35, .15]`), `direction`. Each
  node returns its top 5 `factors` (origin, distance, weight, contribution) and `contributors` (the full count).
* **Budgets** (`GraphRunService`): shipped default 50,000 nodes / 500,000 edges / 30 s; ceilings 500,000 / 5,000,000 /
  300 s (both are Space settings under `graphRun`, as are `index.threads` and `index.queue`). Size is checked before work, the deadline at `RunControl` checkpoints; overrun ends `BUDGET_EXCEEDED` with
  no result — never a silent cap.
* **Pool:** 2 threads, queue 16 (`AbortPolicy` → `REJECTED`, HTTP 503), finished-run retention 200, result cache 32 entries /
  10 min. Cache key = relation + row-scope fingerprint (a tripwire against crossing scopes) + algorithm + resolved
  params + weights + input identity. Only COMPLETED results are cached; masking applies after the cache.
* **Parity:** Java and TypeScript assert the same hand-derived fixtures with canonical-v1 tie-breaks; the server has
  no node cap of its own beyond the budget.

## 6. HTTP surface (la-api, geo-link)

18 `RouteModule`s in la-api plus `InvestigationMeasureRoutes` in geo-link, **75**<!--count:la-routes--> routes (**69**<!--count:la-api-routes--> in la-api + **6**<!--count:geo-link-routes--> in geo-link;
the live `GET /audit/route-inventory` lists 75 for `/inv` and `/geo`, all present in `openapi-v1.json`):

| Area | Routes | Write capability |
|---|---|---|
| Exploration | `/geo/projection`, `/geo/routes`, `/inv/projection{,/neighbors,/multi}`, `/inv/schema/*`, `/inv/traversal/recursive-paths`, `/inv/pattern/{branching,temporal}`, `/inv/value-measures`, `/inv/snapshots*` | none (rate-limited as expensive); snapshots POST `canManageIncidents` |
| Investigations | `/inv/investigations` create/list, `…/{id}/ops`, `/undo`, `/reorder`, `/replay`, `/log`, `/working-set`, `/coverage`, `/compare`, `/references`, `/case`, `/members`, `/template`, `/inv/investigation-templates*` | `canManageIncidents` (+ lead) |
| Four-eyes / reveal | `…/pending/{p}/approve|deny`, `…/reveal` | `canApproveLinkExpansions`, `canRevealLinkEntities` |
| Dossier | `…/dossier`, `/dossier/verify`, `/dossier/bundle`, `/dossier/bundle/verify` | read gate only |
| Drafts | `…/drafts` create/list, `…/drafts/{d}` + `/log`, `/working-set`, `/replay`, `/conflicts`, `/ops`, `/undo`, `/discard`, `/rebase`, `/promote` | `canManageIncidents` + per-role act check |
| Identity | `/inv/entity-identities`, `/group`, `/import`, `/{id}/retract` | `canManageIncidents` (the reads too; `/import` has no UI) |
| Graph Run | `/inv/graph/runs`, `/algorithms`, `/runs/{id}`, `/cancel` | `canRunLinkGraphAnalysis` on the POST that starts a run; list/get are read-gated; cancel is owner-or-admin, else 404 |
| Index | `/inv/index`, `/inv/index/builds`, `/builds/{id}`, `/cancel` | `canBuildLinkIndex` on the POST that starts a build; the reads and cancel are not capability-gated |
| Measures & detection (geo-link) | `…/{id}/measures`, `…/alert-rules`, `…/standing-detection` | `canAuthorAlertRules` |

Capability constants are in `platform/inspecto-access` `Roles`. OpenAPI fragments
(`META-INF/inspecto/openapi.fragment.json` in la-api and geo-link) merge into `openapi-v1.json`; a new route also
needs `CapabilityManifest`, rate-class and auth-gate coverage (see the `endpoint` skill).

### 6.1 Gate order for an `/inv` request

1. `ControlApi.authenticate` → Subject (401 audited).
2. `rateLimit` → `LinkAnalysisRateClasses`: 11 expensive POSTs share a per-subject `linkAnalysis` bucket (capacity 20,
   refill 1 per 3 s, tunable `control.rateLimit.linkAnalysis.*`; 429); Investigation routes are exempt (pinned by `LinkAnalysisRateClassCoverageTest`).
3. `authorize` → host ABAC PEP.
4. `requireModuleEnabled` → feature `geoLink` in the Space's `modules.toon` (404 `MODULE_DISABLED`); then the host's
   idempotency check, before the handler. ⚠ The gate only covers a route whose `RouteModule` overrides
   `featureIds()` → `geoLink`; as of 2026-10-11 all 19 LA route modules (18 in `la-api` + geo-link's
   `InvestigationMeasureRoutes`, whose manifest now declares `features[1]: geoLink`) do — a new LA route module
   must too (pinned by `ControlApiGeoLinkModuleGateTest`).
5. `ApiContext.withCapability` → 403. ⚠ A no-op when there is no Subject (Personal / tests without an armed
   Authenticator).
6. `InvestigationRoutes.open`: write root (503) → safe id (422) → header (404) → membership role (`Need`
   READ / LEAD / APPROVE; a member of the linked Case also passes READ) → R3 Dataset visibility (`ComponentAccess.canView`, 404) → Enterprise PDP D-E7
   (`RowScope.visible`, can only narrow, 404) → deferred role refusal 403. **Non-members get 404-as-absence; a
   member lacking the role gets 403 only after R3 and the PDP have spoken.**
7. Handler → `EntityMasking` on output → audit (`LinkEventTypes` via `EventSink` + `AuditTrail`; ≥500 and 401/403
   recorded by `ControlApi`).

## 7. Host registration, Jobs and live detection

* **ServiceLoader files:** la-api → `RouteModule` (18); geo-link → `RouteModule`, `InvestigationMeasureProbe`,
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

| | Personal | Standard | Professional | Preview | Enterprise |
|---|---|---|---|---|---|
| la-graph, la-core, la-storage, la-api, geo-link | — | ✓ | ✓ | ✓ | ✓ |
| la-store-pg (Postgres Investigations) | — | — | — | ✓ | ✓ |
| D-E7 PDP (`providers/inspecto-policy`) | — | — | — | ✓ | ✓ |

Standard is a Professional alias in `tools/bundle-modules.mjs` (`offerings/standard.toon` does not exist). The Postgres store also needs
`-Dinvestigations.backend=db` plus a `jdbc:postgresql:` URL; the jar alone keeps the filesystem store.

Jars are staged by `inspecto/package.ps1`; `-Ui la-app` swaps the bundled SPA, not the edition. Enablement then runs
Installed (jar present) → Enabled (`modules.toon` `geoLink`, per Space) → Permitted (capability + gates above).

## 9. Concurrency and resource bounds (summary)

| Resource | Bound | On overflow |
|---|---|---|
| Graph Run pool | 2 threads, queue 16 | `REJECTED` |
| Index builds per Space | 1 thread, queue 4, one per (Dataset, mapping) | 503 / 409 |
| Index disk | `max_disk_bytes`, estimate `rows × 34 B` | 422 before any work |
| Index deltas | 8 | `append` refused, `compact` advised |
| Index traversal | depth 2, frontier 20 | flat fallback with a closed `Reason` |
| Open Drafts | 50 per Space, 1 per member | 409 |
| Heavy Draft ops | `max(1, min(4, cores/3))` permits, never waited for | 429 |
| Expensive exploration POSTs | per-subject `linkAnalysis` bucket | 429 |

## 10. Known seams and residuals

* Index scale is proven to 10^8 edges; 10^9 is extrapolation (`LA-INDEX-SCALE-MEASURE-1`).
* The index builder connection is deliberately unsandboxed; its safety rests on the trusted-relation contract and
  the caller's view gate.
* Per-key index lookup cost is linear in the key's degree; the frontier cap bounds key count, not hub cost.
* `FsInvestigationStore` carries a self-declared TRANSITIONAL note: path-keyed Draft code still reaches into the
  directory; legacy `SnapshotStore` still exists in la-core.
* Entity List purposes (`allow | block | watch | exclusion`) are a free string in the fold; no enum enforces them
  in entity-store.
