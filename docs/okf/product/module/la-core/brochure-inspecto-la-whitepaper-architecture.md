---
type: Brochure
title: Inspecto LA whitepaper — Systems architecture and data plane engineering
description: Part 3 of 6 of Inspecto LA whitepaper: · Systems architecture & data plane engineering
---

# Inspecto LA whitepaper — Systems architecture and data plane engineering

Part 3 of 6 of [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · previous: [Analytical depth, graph algorithms and explainable detection](brochure-inspecto-la-whitepaper-analytics.md) · next: [Enterprise governance, security and access control](brochure-inspecto-la-whitepaper-governance.md).

# 4 · Systems architecture & data plane engineering

Inspecto LA runs inside a single host process. The backend is partitioned into five `la/` Maven modules and two platform modules, with dependency arrows that a guard enforces. It reads the same Datasets the platform ingests and assures, so there is no copy to keep in sync.

**Architecture at a glance — ingest to analyst.** Files flow through Collectors into Parquet Datasets; a versioned link index is appended each day; requests pass the gates, read either the index or the Dataset, and are masked before they leave.

```
                      LINK ANALYSIS ARCHITECTURE · INGEST TO ANALYST

  Files: Hive exports, database extracts, CSV, reference files
        │
        ▼
  Collectors ──► Pipelines ──► Parquet Datasets (daily partitions) ───────────────┐
                                    │                                             │
                                    │ la.index.build Job                          │ direct read
                                    ▼ (append a day · compact · full)             │ (flat path)
                       Versioned link index: out/ · in/ · nodes/ ───────┐         │
                                                                        ▼         ▼
  Analyst (console lens or la-app) ──► request ──► gates (5.1) ──► read: index  OR  Dataset
                                                                        │
                                           op evaluator · Graph Run pool│
                                                                        ▼
                                             mask on the way out ──► response + audit event
```

- **Engine.** DuckDB executes every query, in a sandbox limited to the files that request may read. There is no long-running graph database; Parquet is the only stored format of the link index.
- **Investigation store.** By default Investigations are files on local disk, with operating-system locks that are safe across two server processes; Advanced can keep them in PostgreSQL instead, one schema per Space, with each promotion in one transaction (Section 4.2).
- **Same algorithms in browser and server.** The server's Java algorithms are a line-for-line port of the browser's TypeScript and are checked against shared fixtures, so a small graph in the browser and a large one on the server give the same answer.

**Life of a request — four gates, one read choice.** After sign-in, rate limit, access policy, module and capability gates (Section 5.1), an Investigation request passes four gates in a fixed order, each failing closed; then the read path is chosen per request.

```
              LIFE OF AN INVESTIGATION REQUEST · 4 GATES, 1 READ CHOICE

  request (signed in, within rate, module on, capability held)
        │
        ▼
  ① member of the Investigation? ────────── no ──► 404  (same answer as "does not exist")
        │ yes
        ▼
  ② may see the underlying Dataset? ─────── no ──► 404
        │ yes
        ▼
  ③ policy engine allows? (Advanced) ────── no ──► 404
        │ yes
        ▼
  ④ role allows the action? ─────────────── no ──► 403
        │ yes
        ▼
  read choice: index enabled for the Space, built for this mapping, fresh enough,
               depth ≤ 2 and ≤ 20 keys per level?
        ├── yes ──► indexed read
        └── no ───► Dataset read; the response names the closed Reason
        │           (a response never mixes index rows and Dataset rows)
        ▼
  mask identifiers ──► response + audit event
```

### 4.1 Modular component architecture & seam enforcement

Link Analysis is **an optional, ServiceLoader-discovered extension of the one host process**: there is no Link Analysis main class and no separate server. The standalone `la-app` is only a second Angular shell served by that same host.

```
                               MODULE DEPENDENCY TOPOLOGY
                               (an arrow points to what a module depends on)

   ┌─────────────────────────────────────┐   ┌──────────────────────────────────────┐
   │ Inspecto console (/studio/...)      │   │ la-app (inspecto-ui/projects/la-app) │
   │ shared library @inspecto/link-analysis  — two Angular shells, one library      │
   └──────────────────┬──────────────────┴───┴──────────────────┬───────────────────┘
                      │            HTTP REST / OpenAPI v1       │
                      ▼                                         ▼
   ┌───────────────────────────────────────────────────────────────────────────────┐
   │ host process: ControlApi — discovers RouteModules through ServiceLoader       │
   └──────────────────────────────────────┬────────────────────────────────────────┘
                                          │
   ┌──────────────────────────┐   ┌───────┴──────────────────┐
   │ la/inspecto-geo-link     │──►│ la/inspecto-la-api       │   routes, gates, masking,
   │ bridge: engine ⇄ ports   │   │ (90 routes)              │   Drafts orchestration
   │ (7 routes)               │   └───┬──────────────────┬───┘
   └──────┬────────┬──────────┘       │                  │
          │        │                  ▼                  ▼
          │        │      ┌────────────────────┐ ┌──────────────────────┐
          │        └─────►│ la/inspecto-la-core│◄┤ la/inspecto-la-storage│  link index
          │               │ domain, ports,     │ │ (DuckDB, Parquet)     │
          │               │ op evaluator,      │ └──────────────────────┘
          │               │ filesystem store   │◄── la/inspecto-la-store-pg
          │               └─────┬──────────┬───┘     (Postgres store provider)
          ▼                     ▼          ▼
   entity-list, entity-store   ┌─────────────────────┐  ┌──────────────────────────┐
                               │ platform/            │  │ platform/                │
                               │ inspecto-graph       │  │ inspecto-entity-store    │
                               │ (algorithms, JDK     │  │ (Entity Types, Lists,    │
                               │  only)               │  │  identity fact log,      │
                               └─────────────────────┘  │  mask tokens, limits)    │
                                                         └──────────────────────────┘
```

The surface is **111**<!--count:la-routes--> routes: **104**<!--count:la-api-routes--> in `la-api` and **7**<!--count:geo-link-routes--> in `geo-link`, counted from each module's `module.toon` (`provides.routes`) and kept in step with the code by `LaApiRoutesManifestParityTest`. Every route is in `docs/api/openapi-v1.json`. `la-storage` depends on `la-core`, the SQL module and DuckDB; `geo-link` depends on `la-api`, `la-core`, `entity-list` and `entity-store`; `la-core` depends on `inspecto-graph` and `entity-store` among others. Ports point inward: `la-core` defines the engine, dataset, case and store ports, and storage, the bridge and the Postgres module implement them. The core never knows how the index is stored, and the host engine never names a Link Analysis class.

| Module | Responsibility | Depends on (Inspecto modules, from its pom) |
| :--- | :--- | :--- |
| `platform/inspecto-graph` | The graph algorithms and run control (`RunControl`: cancel, deadline, budget) | nothing (JDK only) |
| `la/inspecto-la-core` | The domain: op evaluator, `InvestigationStore` port and filesystem store, Drafts, Graph Run service, the algorithm catalogue, ports to the host | `inspecto-graph`, `inspecto-entity-store`, `inspecto-sql`, `inspecto-analytics`, `inspecto-config`, `inspecto-util`, platform SPIs |
| `la/inspecto-la-storage` | The link index: builder, plan, reader, pins and the index-backed graph engine | `la-core`, `inspecto-sql`, `inspecto-analytics` |
| `la/inspecto-la-api` | The HTTP surface (**104**<!--count:la-api-routes--> routes), the access gates, masking, Draft rebase and promote orchestration, scheduled builds, standing detection | `la-core`, `la-storage`, `inspecto-entity-store`, `inspecto-access`, `inspecto-analytics`, `inspecto-config`, `inspecto-sql`, `inspecto-util`, platform SPIs |
| `la/inspecto-geo-link` | The bridge to the host (**7**<!--count:geo-link-routes--> routes): Datasets, Cases, Collector coverage, the index-build service, Alert measures | `la-api`, `la-core`, `inspecto-entity-list`, `inspecto-entity-store`, `inspecto-etl`, `inspecto-processor` |
| `la/inspecto-la-store-pg` | Investigations on PostgreSQL (Advanced, opt-in) | `la-core` |
| `platform/inspecto-entity-store` | Entity Types, the Identity Fact log behind Entity Lists and identities, mask keys, industry packs, Link Analysis settings and limits | platform only (`inspecto-api`, `inspecto-util`, `inspecto-graph`, `inspecto-config` and SPIs) |

- **Clean Inversion of Control**: `inspecto-graph` and `la-storage` contain **no HTTP or UI dependencies**. Algorithms and storage primitives can be tested, benchmarked and embedded headless. `inspecto-graph` reaches no other Inspecto module at all.
- **Seam Enforcement**: Module dependency arrows are checked twice — by `tools/check-module-deps.mjs` (a CI guard that derives the module graph from the poms and compares it with a policy table) and by a Maven Enforcer rule in each of the governed modules. A reverse import (for example `la-core` reaching `la-storage` or `la-api`) fails the build. A second guard, `tools/check-engine-names-no-la.mjs`, keeps the engine from naming any `la-*` class; the host reaches Link Analysis only through ServiceLoader interfaces it owns. Seam enforcement describes structure, not behaviour. In D-AN2 (2026-10-17) the graph library moved to the platform module `inspecto-graph` so that other capabilities can use it without Link Analysis, and the windowed motif matcher (`TemporalMotifMatcher`) moved there as well; Link Analysis keeps its pattern compiler. Link Analysis's SQL builders quote identifiers through the shared `SqlIdent` in `inspecto-util`.
- **Host Discovery via ServiceLoader**: Link Analysis registers with the core process through ServiceLoader. If a Link Analysis module is absent from the classpath, its routes answer `503 CAPABILITY_UNAVAILABLE` from the core's absent-module stub and the core boots cleanly with no Link Analysis footprint; Basic withholds the Investigation surface by the edition feature described in Section 1.

### 4.2 Dual-storage engine architecture

Depending on the deployment, Investigations (their op logs and Working Sets) are persisted by one of two backends behind the `InvestigationStore` port:

```
               STORAGE ENGINE SELECTION & TOPOLOGY

                               InvestigationStore
                                (Abstract Port)
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
        ┌───────────────────────┐             ┌───────────────────────┐
        │  FsInvestigationStore │             │  PgInvestigationStore │
        │ (default, every       │             │ (Advanced and Preview,│
        │  edition)             │             │  opt-in)              │
        └───────────┬───────────┘             └───────────┬───────────┘
                    │                                     │
   <write root>/audit/snapshots/              one schema per Space; tables
   investigations/<id>/                       la_space, la_investigation,
   ├── header, log.jsonl (append)             la_log, la_set, la_member,
   ├── sets/<step>.json (sealed)              la_reference, la_alert_binding,
   ├── members, references, templates         la_pending, la_template,
   ├── pending, caselink, binding             la_draft
   ├── mask.key                               sealed bytes stored as text,
   └── drafts/<draft>/...                     immutability trigger
```

1. **Filesystem store (the default in every edition, Advanced included)**:
   - Persists under the Space write root: a header, an append-only `log.jsonl`, one sealed Working Set file per step (`sets/<step>.json`, written create-new), members, references, templates, pending requests, the Case link, the Alert Rule binding, the Investigation's mask key and Drafts. There is no manifest file in the store: the Dossier route builds its manifest on request.
   - Concurrency: a per-directory monitor plus an operating-system file lock (`.store.lock`) make it safe across two JVMs; appends check an expected version.
   - Requires no database, which suits ruggedized laptops, air-gapped units and isolated field servers.
2. **PostgreSQL store (Advanced and Preview, opt-in)**:
   - Selected by `-Dinvestigations.backend=db` with `investigations.db.url` (or `inspecto.db.url`) naming a `jdbc:postgresql:` database; the password is read through the secret resolver. The jar alone keeps the filesystem store.
   - One schema per Space; the tables are listed above. Sealed bytes are stored as text and an immutability trigger refuses their change; writers take row locks (`FOR NO KEY UPDATE`) in one short transaction, and a Draft promotion is one transaction with nothing to recover.
   - It lets a second pod share a Space. Contract tests: `PgInvestigationStoreContractTest` and, for two JVMs racing on one store, `PgInvestigationStoreTwoJvmRaceTest` (module `la/inspecto-la-store-pg`).
   - **Embed links are not supported on the PostgreSQL store yet**: the embed routes answer 503 until that backend implements the embed-grant store contract.

### 4.3 Dual query & execution engines

Graph traversal workloads fall into two categories: ad-hoc exploration over moderate Datasets, and localized lookups over very large ones. Inspecto provides two read paths:

```
               QUERY EXECUTION PATH ROUTING DECISION
                                 
                         Client Request (/inv/...)
                                     │
                                     ▼
                  Index enabled, matching and not too stale,
                  depth ≤ 2, frontier ≤ 20 keys, rung indexable?
                                     │
                    ┌────────────────┴────────────────┐
             YES    ▼                                 ▼    NO
        ┌───────────────────────┐             ┌───────────────────────┐
        │  Indexed read         │             │  Flat recursive CTE   │
        │  (inspecto-la-storage)│             │  (sandboxed DuckDB)   │
        └───────────┬───────────┘             └───────────┬───────────┘
                    │                                     │
         Bucket read, one key per               The fallback names a closed
         statement, one hop per query           Reason; the answer never mixes
                                                index and flat rows
```

A Graph Run is routed by its **input type** (`RoutingGraphEngine`), never by size: a Working Set input runs in the in-memory engine; an index input runs in the SQL-backed engine, which serves neighbourhood, ego network and seeds-only degree with hard caps. A traversal deeper than 2 hops, or wider than the frontier cap, falls back to the flat path with a stated Reason.

#### Engine A: Sandboxed DuckDB recursive CTEs (flat path)
For un-indexed Datasets and for deep, iterative walks, Inspecto compiles traversals into sandboxed, recursive Common Table Expressions executed in embedded DuckDB. A simplified, illustrative form (the shipped statements add direction, link-kind, time-window, fan-out and budget fences):

```sql
WITH RECURSIVE traversal(entity, depth, path) AS (
    SELECT source_entity, 0, [source_entity]
    FROM input_relation
    WHERE source_entity = 'ACC-9921'
  UNION ALL
    SELECT r.target_entity, t.depth + 1, list_append(t.path, r.target_entity)
    FROM input_relation r
    JOIN traversal t ON r.source_entity = t.entity
    WHERE t.depth < 2 AND NOT list_contains(t.path, r.target_entity)
)
SELECT * FROM traversal;
```

DuckDB executes such queries vectorised across CPU cores. Measured through the real route (gate `G-R4`, `InvTraversalBench`): a 5-hop recursive path search over a heavy-tailed $10^6$-edge Dataset answered at a warm p95 of $209 / 188 / 171\text{ ms}$ from a median-degree, a 99th-percentile-degree and a top-hub start, all under the $350\text{ ms}$ gate, with no index.

#### Engine B: Versioned Parquet Link Index (high-scale path)
Scanning flat lakehouse files makes interactive latency unattractive as edge counts grow into the tens of millions. `la-storage` builds a **Versioned Parquet Link Index** (Standard and above):

```
                       VERSIONED PARQUET INDEX LAYOUT
                       
 <write root>/la-index/<datasetId>/<mappingHash>/
 ├── CURRENT                    ◄── Atomic 1-line pointer to the live version (e.g. "v000002")
 ├── pins.json                  ◄── Versions kept alive for in-flight Draft reads
 └── v000002/
     ├── manifest.json          ◄── Schema, input-file fingerprints, DuckDB version, bucket sizing,
     │                              link statistics (kindEdges, typeNodes, degree, timeSpan)
     ├── out/                   ◄── Edges keyed by source, sorted by (bucket, key, time)
     │   ├── bucket=0/data.parquet
     │   └── bucket=B/data.parquet        (+ up to 8 delta files per bucket)
     ├── in/                    ◄── The same edges keyed by target
     │   └── bucket=B/data.parquet
     └── nodes/                 ◄── Distinct nodes with degree counts
         └── bucket=B/data.parquet
```

- **Bidirectional Partitioning**: Every link is stored twice — keyed by source in `out/` and by target in `in/` — so an expansion in either direction reads one direction. The data roughly doubles.
- **MD5 Hash Bucketing**: Entities are assigned to buckets by a fixed, golden-tested hash:
  $$\text{Bucket}(v) = \text{md5\_number\_lower}(v) \bmod N, \qquad N = \text{clamp}\left(2^{\lceil \log_2(\lceil \text{edges} / E_b \rceil) \rceil},\; N_{\min},\; N_{\max}\right)$$
  where $E_b$ is `index.edges_per_bucket` (default $4{,}000{,}000$), $N_{\min}$ is `index.min_buckets` (default 16) and $N_{\max}$ is `index.max_buckets` (default 1,024) — all install-configurable; a changed sizing forces a full rebuild. The reader computes the bucket in Java and writes it as a literal, because DuckDB cannot derive the partition from a key equality; with that, a single-key lookup reads **one bucket** (plus up to 8 delta files, `MAX_DELTAS`).
- **Incremental Appends & Compaction**: `IndexPlan.classify` decides the build mode. When a new day lands and the manifest's input files are an unchanged subset of today's, an `append` version indexes only the delta. When the delta count reaches its cap, `compact` merges main and deltas. Compaction runs **on demand** (`POST /inv/index/builds`) or from the `la.index.build` Job, which runs only the mode the plan advises (a `full` build only when the Job allows it); it is not a background process. Measured append and full-build times are in Section 6.1.
- **Index statistics**: The manifest records link statistics (`kindEdges`, `typeNodes`, `degree`, `timeSpan`), served by `GET /inv/index` and read first by the Entity profile pages (Section 4.7).
- **File format**: each table is written partitioned by bucket, sorted by (bucket, key, time), in row groups of 100,000 rows, compressed with zstd. MD5 is fixed (golden-tested), so an engine upgrade cannot move a key to another bucket.
- **Build plan**: `IndexPlan.classify` compares the current input files with those the live version was built from. Only new files means an `append` delta; a removed, changed or unknown input file, a changed relation SQL, a changed bucket function or a changed DuckDB version forces a `full` build; reaching 8 deltas means `compact`.
- **Build safety**: every build writes a staging directory with its own DuckDB connection and is published atomically by the one-line `CURRENT` pointer, so a failed build deletes its stage and leaves the live version untouched. One build service per Space runs one build at a time with a queue of 4 by default (install limits `index.threads`, `index.queue`), one live build per Dataset and mapping (a duplicate is `409`), and a build whose disk estimate exceeds the budget is refused before it starts. Builds run off the request path.
- **Reads**: each request opens a sealed DuckDB connection whose readable directory is that one version directory; one hop per query, each direction answered from its own copy. An idle pool keeps up to four readers per version.
- **Freshness**: the read gate compares the Dataset's input-file fingerprints with the index's. An index read served after new files landed says so (`read.index.stale`); a Dataset whose files cannot be fingerprinted is served from the index only while the index is younger than `index.max_age_hours` (default 24); otherwise the read goes to the Dataset with a closed Reason. Reference (dimension) data is joined at read time and never baked into the index, so a dimension change needs no rebuild (decision `D-ING6`).

### 4.4 User interface dual-delivery

Inspecto LA accommodates different personas through two Angular shells that share one library:

```
                       FRONTEND DELIVERY ARCHITECTURES
                       
      ┌─────────────────────────────────────────────────────────────┐
      │                      INSPECTO PLATFORM                      │
      └──────────────┬──────────────────────────────┬───────────────┘
                     │                              │
                     ▼                              ▼
      ┌─────────────────────────────┐┌──────────────────────────────┐
      │   Embedded Builder Lens     ││     Standalone `la-app`      │
      │   (/studio/link-analysis)   ││    (Dedicated Forensic UI)   │
      ├─────────────────────────────┤├──────────────────────────────┤
      │ • Part of main web console  ││ • Independent Angular bundle │
      │ • Full access to Pipelines, ││ • Left rail, no header chrome│
      │   Datasets, BI, Catalog     ││ • Maximum screen for canvas  │
      │ • Persona: Data Engineers,  ││ • Persona: Dedicated Fraud   │
      │   Analytics Authors         ││   & Intelligence Analysts    │
      └─────────────────────────────┘└──────────────────────────────┘
```

1. **Embedded Studio Lens (`/studio/link-analysis`)**: Integrated into the Inspecto console for data engineers and authors who build and test graph views alongside dashboards and Pipelines.
2. **Standalone `la-app` shell**: A dedicated bundle (`package.ps1 -Product la`; it swaps the bundled application, not the edition). It has a left navigation rail (Link Analysis, Geo, Entities, Entity Lists, Alerts, Settings; Basic omits Geo and Alerts), no header chrome, and OIDC sign-in in Standard and above. Its **Settings** page is a master-detail split by audience: *My settings*, *This Space* (Link Analysis settings, analysis limits and an **Access** section with the console's roles editor), and *This server* (host resources); a section the user may not change opens read-only and says so, and the server remains the judge. The footer status bar carries the node and link counts, the caps in force and the installed modules. Working Set tiles never sum an identifier column.

Both shells share one Studio layout (reorganized 2026-10-27): a single header row with a **File** menu (New, Open…, Save view, Save view as…, Save this analysis, Import / export views, Close, Delete), the view tools and the canvas toolbar side by side (Canvas mode — Pan, Lasso, Fisheye, Link filter — selection, Expand…, Collapse, Clean up, Held and Hidden); one **⚙ Settings** dialog for the graph model, Entity Types, display rules, data and the open Investigation's settings; a right panel with the **Analysis** catalogue (one entry per algorithm, a Browser | Server switch) and the **Investigation** tab; and a bottom dock (rows, results, op log, Working Set table, coverage, annotations, excluded entities). Dashboard Widgets and the read-only embed viewer (`/embed/inv/<token>`, served by both shells) offer the viewing tools only. Both shells talk to the same server and, in Standard and above, sign in through the deployment's OpenID Connect provider. Deployment follows the platform:
- **Per Space**: each Space switches Link Analysis on or off (the module gate, Section 5.1) and keeps its own Investigations, Entity Lists, link index and masking keys.
- **On premises, offline**: DuckDB runs in process, and the bundle can embed its own Java runtime (a `jlink` image built by `package.ps1`); no internet access is needed and the data never leaves the operator's estate.
- **Documentation**: analysts get one user guide (`architecture-records.md` in this hub: Part I for analysts, Part II reference); every limit, its default and its range are listed in the product's own Settings (Section 6.3).

### 4.5 Spatio-temporal & cross-domain convergence

Fraud and national security threats rarely occur in a topological vacuum; they exist across physical geography and time.
- **Geo Map Analysis (Standard and above)**: a 100 % offline map — a bundled MapLibre basemap with vector land, boundaries and places, no tile server and no internet — with origin-destination routes, heatmaps, a time slider with playback, and a toolbox (measure, radius, polygon, notes) over a layer manager with GeoJSON overlays; saved Geo Views make an analysis repeatable. Its routes (`POST /geo/projection`, `POST /geo/routes`) are withheld on Basic (Section 1).
- **Cross-Route Geo Brushing**: The single-page application's brushing service keeps the graph and the **Geo Map Analysis** page in step. A selection on one canvas is published and the other canvas highlights the matching elements, joined only by the entity key (never by a display label); selecting entities on the graph highlights their points on the map, and an area drawn on the map selects the matching entities. This is front-end behaviour, not a backend module.
- **Continuous Timeline Slicing**: Every link retains its event timestamp. A timeline cut-off slider and a playback strip step through time over a chosen time column and highlight the links whose date falls in the current slice; they write nothing to the graph.

### 4.6 Data Trust Architecture & provenance strip (LA-DATA-TRUST-1)

*Standard and above.* A persistent hazard in intelligence operations is analyzing a graph without knowing whether the underlying data is fresh, complete, or corrupted by pipeline delays. Inspecto LA resolves this with an integrated **Data Trust Strip** rendered above the open Investigation:

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ DATA TRUST STRIP: mule_ring_legs_dataset (Consignment #1048)            (ILLUSTRATIVE) │
 │ [● Fresh: 24m ago] [✔ 3/3 Files Landed] [⚡ Index: Synced v4] [⚠ 0 Quarantined Rows]   │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Graph Lineage & Landed File Attribution**: `GET /inv/datasets/{id}/files` lists one row per landed File with its event day, rows, error rows, ingest time, outcome (success, partial, quarantined, failed) and index state. Analysts can see which landed files support the current view.
- **Continuous Staleness & Lag Detection**: The strip compares event time, lakehouse ingest commits (`pipeline.commit`) and the index version. A corrected-day warning (`GET …/data-changes`) appears when a delivery manifest corrects a day inside the Investigation's window after the analyst's last look; only a step that reads data counts as a look.
- **Data Trust Cache**: The `data-as-of` read is cached against the input-file stamp (the relation plus the path, size and modification time of every input file), so the cache can never show fresher or staler data than the disk. Measured on 70 Files and 322 thousand rows on a live bundle over loopback: a cache miss took $895\text{--}993\text{ ms}$, a hit $132\text{--}138\text{ ms}$ (a single recorded set of live measurements, not a benchmark gate).
- **Integrated Trust Alerts**: Freshness and index-lag Alert Rules, a volume band against the median of the prior days, and one Alert per refused or failed index build and per problem File (the Alert text carries no entity key) surface upstream delivery problems. The Dossier footer states the Dataset's state at export time and is explicitly outside the custody hash and the seal. Stated limits: completeness is judged on days and File sequence, not against an expected-Collector roster.

### 4.7 Entity profile pages (built 2026-10-15)

*Explorer feature: every edition, Basic included.* An Entity profile answers *what is this identifier, where does it appear, and how does it behave* before an analyst commits to an Investigation. Three pages are served by the shared library and mounted in `la-app` (navigation entry **Entities**) and in the console: **Entities** (one card per Entity Type), the **Entity Type Profile** and the **Entity Profile**; the graph's node dialog links to the Entity page.

- **Routes** (`EntityProfileRoutes`): `GET /inv/entity-types` (types, bindings and the cached headline; never runs a query), `GET /inv/entity-types/{id}/profile?sections=`, `GET /inv/entities/{type}/{key}/profile`, and `POST /inv/entities/{type}/resolve`.
- **Entity Type Profile sections**: Identity, Where it appears, Lineage, Volume and quality, Value shapes, Time, Links, Relationships, and *What is not here*. **Entity Profile** shows: seen, rows per day (timeline), where, links, related identifiers, lists and groups, and freshness.
- **Where the Links statistics come from**: the link index first (the manifest's `kindEdges`, `typeNodes`, `degree`, `timeSpan`), else a budgeted Dataset query, else the section answers `assessed: false`.
- **Masking**: A raw identifier never travels in a URL. For a type masked under the Space's mode, the `GET` is alias-only (`masked:<16 hex>`) and a raw key is refused `422` before anything is read, without repeating it; a raw value goes in the **body** of the `POST …/resolve`, and the page navigates by the key it returns. Type pages are aggregates only: a dominant value is reported as `kind: "dominant"` with `value: null`, and only the built-in sentinels (blank, `-99`, `0`, `NULL`, `null`, `unknown`, `n/a`) are shown by value.
- **Gating**: no dedicated capability. Every Dataset read goes through the caller's view gate, masking applies, and the profile routes are rate-limited as expensive. Audited as `LINK_ENTITY_TYPES_LISTED`, `LINK_ENTITY_TYPE_PROFILED`, `LINK_ENTITY_PROFILED` and `LINK_ENTITY_KEY_RESOLVED` (never a key).
- **Honesty rules**: an approximate count says *approx.*; a section the server could not read says *Not assessed* with its reason and is listed again under *What is not here*; a failed read answers `assessed: false, retryable: true` and is **never cached**.
- **Cache**: keyed by the input-file fingerprint of every bound Dataset. Measured cold at $2.2\text{--}5.4\text{ s}$ and cached at about $200\text{ ms}$ on a Space of 29,650 rows.
- **Scale hardening (2026-10-16, after a 132-million-row day, Section 6.4)**: profile SQL runs under its own memory budget (`entity_profile.memory_limit_mb`, default 4,096 MB) instead of the 1 GB sandbox default; above `entity_profile.shape_sample_rows` the dominant value is read from a row reservoir; a relationship read that runs out of memory or times out is retried over a 1-in-64 Entity hash sample and flagged `sampled`, `sampleEvery` and `approximate`; and a Dataset column may carry a `party`, so relationships pair only columns of the same party (or where either has none) — a served IMSI pairs with the served number, not the other party's.
- **Limits** are listed in Section 6.3. **Not built**: P4 — a scheduled `la.entity.profile` Job, a profile Dataset, drift Alert Rules and a 30-day history. **Open residuals** (`LA-ENTITY-PROFILE-RESIDUALS-1`): dominance ignores values that fail the normaliser; a 99.95 % share renders as 100 %; *Links* reads *Not assessed* for a type that is no link end; a cold profile still reads the Dataset several times.
- Tests: `ControlApiEntityProfileTest`, `ControlApiEntityProfileScaleTest` (sandbox-scale figures only).

### 4.8 From investigation to operations: the daily chain (LA-DAILY-INGEST-1)

*Standard and above.* A day's records can land as files in the morning and be in the analyst's graph shortly after ingest; the analyst can see how fresh and complete the data is, and a finding can become a standing check that raises Alerts.

- **Daily ingestion**: Hive exports (landed as Parquet files; a native Hive/Kerberos connector is not built, decision `D-ING5`), database extracts and reference files arrive through the platform's Collectors and Pipelines into daily-partitioned Datasets. A scheduled `la.index.build` Job then appends the new day to the link index; in the Data Trust live drive each append took $0.5\text{--}0.8\text{ s}$ (`docs/okf/product/module/la-core/tests-live-drives.md#data-trust-drive-la-data-trust-1-s10-2026-10-14`), and the scale of the daily chain is in Section 6.1 (Bench C).
- **Data Trust**: each Investigation shows the strip of Section 4.6 (data as of, missing days, Files still waiting to be indexed, the index version), and a drawer lists every File with its event day, row count and rejected rows.
- **Delivery problems are Alerts**: a missing day raises a sequence-gap Alert; a quarantined File, a File loaded with rejected rows, a stopped feed and a lagging index each raise one too, and a corrected day re-sent under the same name is signalled (`DUPLICATE_SKIPPED`) rather than skipped silently. Each Alert names the File, never an entity value.
- **Standing detection and Cases**: a value-measure Alert Rule bound to an Investigation is swept on a schedule under the owner's re-checked authority (Section 5.6), and an Investigation links to a Case, so the evidence and the case workflow stay connected.

---
