<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-09-27 from a brainstorming session. FEASIBILITY + DECISIONS ONLY — nothing is built.
  DECISIONS SIGNED 2026-09-30 — option D is the target; no phase is started by this.
  Retire per the three-tier lifecycle in CLAUDE.md when the work ships (or is declined).
-->

# Link Analysis + Geo as a separate deployment — feasibility plan

**Can Link Analysis (LA) + Geo be delivered as a separate deployment and still stay highly integrable with
Inspecto — with a Case, and later fingerprinting and 360 analysis?**

| | |
|---|---|
| Status | ✅ **DECISIONS SIGNED 2026-09-30 — option D is the target; no phase is started by this** (operator; D1–D21 answered on each **Answer** line). **FEASIBILITY — near term (options A/B, §3–§5) and the LONG-TERM target, option D (§7, added 2026-09-27)**; 7 + 14 operator decisions answered (§5, §7.9); nothing built |
| Trigger | A customer request to deliver LA (+ Geo) as a separate deployment |
| Drivers | Licensing / editions · architecture hygiene · customer ask · team parallelism |
| Scope asked for | Split the module (Geo vs LA) · LA as a standalone product · move LA leftovers out of core · separate LA UI |
| Module today | `inspecto-geo-link` (Professional / Enterprise; `CP-09` / `EDG-01`) |
| Related plans | [`link-analysis-backlog-plan.md`](../archived-documents/plans-archive/link-analysis-backlog-plan.md) (the LA feature backlog) · [`link-analysis-entity-model-design.md`](link-analysis-entity-model-design.md) · [`assurance-capability-plan.md`](assurance-capability-plan.md) (D-P10: Entity List is ONE kind shared with assurance) · [`enterprise-scale-out-plan.md`](enterprise-scale-out-plan.md) |
| Grounding | Three independent code reads on 2026-09-27 (backend coupling · SPA coupling · packaging). ⛔ When this plan and the code disagree, re-ground; never trust the plan |

---

## 0. Verdict

**Feasible without forking.** A packaging flavor is cheap (**M**). A *separate but integrated* deployment is
genuinely new work (**L**, phased), because **no installation-to-installation mechanism exists today**.
Near term: **option A** (an LA edition flavor) if a delivery date forces it, over **Phase 0** seam work that is
no-regret for every option. **Long-term target: option D (§7)** — LA as a product with its own core,
**part of Inspecto and sellable separately** from the same reactor by build flavor plus a few extra pieces,
with **no duplicated code**, a server-side graph engine for big Datasets, and its own partitioned Parquet
storage. Option B (§3) is superseded as a target by D; its ports become D's stepping stones.

---

## 1. Grounded findings

### 1.1 Backend — outbound (what the module needs)

- **One compile dependency**: `inspecto-processor` (`inspecto-geo-link/pom.xml`). No compile import of
  `com.gamma.etl`, `com.gamma.acquire`, `SpaceRoot`, Cases/`ObjectType`, or the Enterprise policy engine
  (`AccessDecider` is resolved by the core at runtime). **Favourable for extraction.**
- **SPI-shaped (fine):** `RouteModule` / `ApiContext` / `ApiException` / `ErrorCodes` (17 of 21 classes),
  audit `Event` / `EventLog` / `EventType` (10), `SqlGuard` / `SqlSandboxPolicy` (2), stateless `com.gamma.util`
  helpers (6).
- **Deep, concrete (the real coupling):** LA reads Datasets by constructing core storage classes directly —
  `new ComponentStore(writeRoot.resolve("registry"))`, `ViewStore`, `DatasetRelation.relationSql(...)`,
  `QueryExecutor` (e.g. `inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` ~l.265–275).
  "Drop the jar beside `inspecto.jar`" works **only because it is the same JVM**. A remote LA needs a real
  Dataset query API, which does not exist.
- **Moderate:** `WriteGates.requireWriteRoot`, `ComponentAccess.canView`, `LinkAnalysisSettings.forRoot`,
  `Subject` / `RowScope` / `EntityTypes`; Alert Rules bound to Investigations (`AlertRule`, `AlertService`,
  `InvestigationMeasureProbe`).

### 1.2 Backend — inbound (what core knows about LA)

All of these are **hand-kept mirrors** of the module's real surface:

- `inspecto/src/main/java/com/gamma/control/AbsentGeoLinkRoutes.java` — the Personal 503 stub and its
  `SURFACE` table. Its own header records a drift incident: routes added to the module and forgotten here
  silently 404'd **and vanished from `docs/api/openapi-v1.json`** while the guard stayed green.
- `inspecto/src/main/java/com/gamma/control/CapabilityManifest.java` — LA capabilities named by class.
- `inspecto-audit-spi/src/main/java/com/gamma/event/EventType.java` — ~24 `LINK_*` / `LINK_INVESTIGATION_*` constants.
- `inspecto/src/main/java/com/gamma/control/BootstrapRoutes.java` — `features.geoLink`, derived from
  `ApiContext.hasRoute(...)` (honest: it reflects what actually registered).
- `inspecto-policy/pom.xml` — a **test-scope** dependency on `inspecto-geo-link` (the only Java edge into it).

### 1.3 Data

LA is **read-only against Datasets** and never ingests. Its own state is file-based under the Space write root
handed in by the core: `SnapshotStore` (one JSON per snapshot + append-only `attachments.jsonl`),
`EntityFactLog` (hash-chained, `CREATE_NEW`, never overwritten), `EntityRegistry` (in-memory).
⇒ **A standalone LA needs its own way to get data in.**

### 1.4 Runtime and packaging

- The runtime floor is the **whole non-optional core** (`inspecto-processor` → acquire, api, config, engine,
  etl, event, sql, util). `inspecto/src/main/java/com/gamma/service/CollectorService.java` always builds the
  scheduler; with no Pipelines it idles. ⇒ **A packaging flavor, not a lean binary.**
- **A new edition flavor is cheap**: a Maven profile, an entry in `tools/bundle-modules.mjs`, a
  `-Edition` in `inspecto/package.ps1` (which already boot-smokes the geo-link `RouteModule` service file).
  PREVIEW (2026-09-21) is the precedent and needed no core changes. ⚠ A static profile module list in
  `pom.xml` must be hand-synced with `bundle-modules.mjs` — the same trap PREVIEW carries
  ([`../okf/backend/editions/editions-model.md`](../okf/backend/editions/editions-model.md)).
- **No Dataset without a Pipeline.** There is no upload / attach route that registers a Dataset;
  `DbBrowserRoutes` only browses files already laid out in a Space's data directory.
- **No installation-to-installation anything.** `inspecto-exchange/src/main/java/com/gamma/exchange/Exchange.java`
  is installation-scope, cross-Space only. `inspecto-agent-hosted` is hosted-LLM adapters, **not** a remote
  protocol. Webhooks are outbound notification only. A machine-to-machine credential distinct from an OIDC
  bearer token was not found (search time-boxed — verify before §4 Phase 2).

### 1.5 SPA

- The real features live under `inspecto-ui/src/app/modules/admin/studio/link-analysis` (~11.3k LOC, 34 impl
  files) and `inspecto-ui/src/app/modules/admin/studio/geo-map` (~2.5k LOC), plus pure-TS libraries under
  `inspecto-ui/src/app/inspecto/graph` (and `…/geo`, `…/investigation`). Both are lazy routes
  (`inspecto-ui/src/app/modules/admin/studio/studio.routes.ts`).
- 🔴 **The graph canvas belongs to Catalog**, not LA:
  `inspecto-ui/src/app/modules/admin/catalog/graph-view.component.ts` is shared with the lineage view and
  imported directly by LA and Geo. `@antv/g6` is used only there and in the Pipeline editor graph.
- 🔴 **LA and Geo import each other** (`inspecto-ui/src/app/modules/admin/studio/link-analysis/geo-link-brush.ts`)
  ⇒ they ship as **one unit**; a Geo/LA split can be a licence boundary, not independent releases.
- LA imports Catalog internals, the Pipeline graph, sibling Studio features (Datasets, Widgets, the dashboard
  header), `ObjectsService` (Cases — with a mock fallback when `inspecto-ops` is absent,
  `inspecto-ui/src/app/modules/admin/studio/link-analysis/link-analysis-case-field.component.ts`), tags,
  transfer, AI assist — and `environments/environment` **by relative path** (a library-boundary blocker).
- Inbound: dashboards embed LA / Geo by dynamic `import()` of their component files
  (`inspecto-ui/src/app/modules/admin/studio/widgets/widget.kind.ts`, viz kinds `geo-map-view`,
  `link-analysis-view`, `working-set-view`); nav gating via `features.geoLink` in
  `inspecto-ui/src/app/inspecto/api/session.service.ts` and `inspecto-ui/src/app/core/navigation/navigation.service.ts`.
- Build: Angular 22, `@angular/build:application` (esbuild), **one** project, no library projects, **no
  module / native federation** installed (would be green-field and must work offline).

---

## 2. What "integrable" requires

Integration with a *separate* Inspecto means five contracts, none of which exists:

| # | Contract | Direction | Today |
|---|---|---|---|
| I1 | Machine-to-machine trust between installations | both | none (OIDC user tokens only) |
| I2 | Read a Dataset in another installation (row scope + audit honoured) | LA → Inspecto | none; `/bi/query` and `/db` unaudited for this |
| I3 | Create / attach to a Case (Investigation + Dossier as evidence) | LA → Inspecto | local only, via `ObjectsService` |
| I4 | Entity context — fingerprint, 360 profile (future) | LA → Inspecto | none |
| I5 | Open LA from Inspecto with context (entity ids, Dataset, Case) | Inspecto → LA | none (same-SPA routes only) |

Cross-cutting concerns each contract carries: Dossier chain of custody across installations · entity-id masking
on the way out (LA backlog `D-U6`) · audit on **both** sides · data residency (query-through vs copy).

---

## 3. Options

| | Option | Cost | Verdict |
|---|---|---|---|
| **A** | **LA edition flavor only** — same reactor, new profile: geo-link + `inspecto-ops` (Cases) + `inspecto-security`, a Space Template with an import Pipeline, a restricted nav menu | **M** | Meets the ask fast; on its own it is an **island** — no integration with another installation |
| **B** | **A + ports and adapters** — LA reaches the platform only through a Dataset-read port, a Case port and an Entity-context port; each has a local adapter (co-deployed) and a remote HTTP adapter (separate Inspecto) | **L**, phased | ✅ **Recommended** — "separate but integrated" without a fork |
| **C** | **Separate codebase / process** | **XL** | ⛔ Reject — duplicates DuckDB, auth, audit, config; loses the same-JVM Dataset reads; two products drift |
| **D** | **LA as a product with its own core, hosted by Inspecto AND by a standalone LA App** — LA modules depend on a thin shared platform layer (extracted, not copied), own their graph engine and partitioned storage; Inspecto includes them, the LA product ships them with a small host (§7) | **XL**, long term | ✅ **Long-term target** (operator direction 2026-09-27) — the only option whose standalone deployment does not carry the whole Inspecto core |

**Push-back on the micro-frontend.** Both deployments come from one codebase, so the **same SPA with a
restricted nav profile** serves the standalone case, and "Inspecto embeds LA" becomes a **URL contract** (I5).
Module federation only pays when LA must live inside a host app we do not build; nothing offline-friendly for
it exists here. Recommended: defer it (D3).

---

## 4. Phased plan (option B)

### Phase 0 — seam hygiene, no behaviour change (**M–L**) — no-regret for A, B and D

| Id | Work | Proof |
|---|---|---|
| SEP-01 | A **Dataset-read port** in core wrapping `ComponentStore` / `ViewStore` / `DatasetRelation` / `QueryExecutor`; geo-link uses only the port | geo-link compiles with no `new ComponentStore(` · LA test classes green |
| SEP-02 | **Module-contributed surface**: a `RouteModule` contributes its route list, capabilities and event types; the core's 503 stub and `openapi-v1.json` are generated from that, retiring the hand-kept `AbsentGeoLinkRoutes.SURFACE` | a deliberately-dropped route turns a guard RED (mutation-checked) |
| SEP-03 | Move `graph-view.component.ts` out of Catalog into a shared graph-canvas library | Catalog lineage + LA + Pipeline editor specs green; preview-driven |
| SEP-04 | Break the LA ↔ Geo cycle: the brush service moves to the shared `inspecto-ui/src/app/inspecto/graph` library | no import from `geo-map` into `link-analysis` or back |
| SEP-05 | Inject the environment config through a token instead of the relative import | no `environments/environment` import under either feature |
| SEP-06 | Published widget-embed contract: LA / Geo register their own viz kinds | `widget.kind.ts` no longer imports LA / Geo files; dashboards still render all three kinds |
| SEP-07 | *(only if D2 = two SKUs)* `features.geo` + `features.linkAnalysis` replacing `features.geoLink`; optionally split the Maven module (Geo is one backend class) | each flag hides exactly its own nav + widgets |
| SEP-08 | *(depends on D7)* Entity List home: core or module | assurance can use Entity Lists in an edition without LA |

**D-0 status (2026-10-01, worktree lane).** SEP-01 done: `DatasetRead` (inspecto-engine, `com.gamma.query`) is the Dataset-read port; geo-link main has no `new ComponentStore(` / `new ViewStore(` (the Alert Rule write in `InvestigationMeasureRoutes` goes through `DatasetRead.registry()`; `QueryExecutor.Request/Result` types are still used directly). SEP-03 done: `graph-view.component` + `catalog-graph` now live in `inspecto/graph/` (imported by path, NOT via the barrel, so G6 stays lazy). SEP-04 done: the brush service + the pure entity-id mint (`entityId`/`endpointId`/`entityIdCandidates`/`resolveEntityId`/`typedOrEntityId`) moved to `inspecto/graph/`; geo-map and link-analysis no longer import each other. SEP-05 done: the only `environments/environment` imports under the two features were two specs; now `apiUrl('')`. SEP-06 done: `geo-map.viz.ts` / `link-analysis.viz.ts` register their own render hosts from `app.config`; `widget.kind.ts` no longer imports either feature (reconciliation's host still registers there). **SEP-02 deferred** -- the absent stub must exist precisely when the module is NOT on the classpath, so a module-contributed list needs a design call (a build-time generated surface descriptor the core reads, vs. keeping `SURFACE` with the existing both-directions `GeoLinkAbsentSurfaceParityTest`); the parity test is the current guard.

**D-0 closed (2026-10-01).** SEP-03 driven in the browser pane against `inspecto-ui` + the geo-link bundle: the shared graph canvas renders in Catalog → Lineage (137 nodes / 135 edges), in Link Analysis (the Lineage graph source, same 137 / 135, caps footer present) and in the Pipeline editor (`join_step` opened, G6 canvases present); the G6 chunk loads only on first graph use; no console errors beyond pre-existing `agent/approvals` 503s (inspecto-agent absent) and a `provenance/batches` 404 (no ingest yet). **SEP-02 decision: keep `AbsentGeoLinkRoutes.SURFACE`** guarded by `GeoLinkAbsentSurfaceParityTest`; revisit when the standalone host (D-5) exists ([`la-separation-execution-plan.md`](la-separation-execution-plan.md) §1.2, plan approved by the operator 2026-10-01).

### Phase 1 — the LA edition (**M**)

| Id | Work | Proof |
|---|---|---|
| SEP-10 | Maven profile + `bundle-modules.mjs` entry + `package.ps1 -Edition` + boot smoke | bundle boots; `/bootstrap` reports the LA features; non-LA optional modules absent |
| SEP-11 | Getting data in (D4): an import Pipeline in a Space Template (file-drop Collector), or a new upload route | a fresh bundle goes from an empty Space to an LA graph with no hand-authored config |
| SEP-12 | Restricted nav menu profile (LA, Geo, Datasets, Cases) | preview walkthrough on the bundle |
| SEP-13 | Auth: OIDC (Professional path); Demo auth only for demos | real-HTTP tests with a Subject attached |

### Phase 2 — the integration contract (**L**, genuinely new)

| Id | Work | Contract |
|---|---|---|
| SEP-20 | Machine-to-machine trust: OIDC client credentials between installations (WSO2 supports it) | I1 |
| SEP-21 | Versioned, audited, row-scoped Dataset query endpoint on Inspecto + the remote Dataset-read adapter in LA | I2 |
| SEP-22 | Remote Case adapter: create a Case / attach an Investigation + Dossier in the other installation | I3 |
| SEP-23 | Entity-context port **defined now**, implemented later (fingerprint, 360) | I4 |
| SEP-24 | "Open in Link Analysis" URL contract from Inspecto (entity ids, Dataset, Case) | I5 |

⚠ Each new mutating route clears the four gates in the `endpoint` skill and needs an `openapi-v1.json` entry.

---

## 5. Decisions owed (operator)

⛔ None may be answered by an implementer in passing. Write the answer on the **Answer** line.

**D1 — Topology.** Does the separate LA deployment (a) hold its **own data** (island + ingest), (b) **query a
remote Inspecto's** data (thin, query-through), or (c) **both**?
*Recommendation:* (c), built (a) first — it is Phase 1 and needs no new contract; (b) is Phase 2.
*Operator direction 2026-09-27 (confirm below):* long term, LA owns its storage — Parquet in an LA partitioning
scheme (§7.4); integration with Inspecto is by reference (§7.6).
**Answer:** (c), built (a) first — it is Phase 1 and needs no new contract; (b) is Phase 2. — operator 2026-09-30

**D2 — Licensing.** Geo + LA as **one SKU**, or **two**?
*Recommendation:* one SKU unless a buyer needs them apart — the SPA cycle (SEP-04) makes them one unit anyway;
two SKUs adds SEP-07.
**Answer:** one SKU unless a buyer needs them apart — the SPA cycle (SEP-04) makes them one unit anyway; two SKUs adds SEP-07. — operator 2026-09-30

**D3 — UI shape.** Same SPA with a restricted nav profile + URL contract, **or** a micro-frontend (federation)?
*Recommendation:* same SPA; revisit federation only if LA must embed in a host we do not build.
*Operator direction 2026-09-27 (confirm below):* a **UI module** — an Angular library consumed by two shells
(Inspecto SPA, LA App), still no federation (§7.5).
**Answer:** same SPA; revisit federation only if LA must embed in a host we do not build. — operator 2026-09-30

**D4 — Getting data in.** Pipeline templates only (file-drop Collector + import Pipeline in a Space Template),
**or** a new upload / attach route that registers a Dataset?
*Recommendation:* templates first (no new route, reuses the engine); upload as a follow-up if the buyer's
analysts must self-serve.
**Answer:** templates first (no new route, reuses the engine); upload as a follow-up if the buyer's analysts must self-serve. — operator 2026-09-30

**D5 — Cases in standalone mode.** Local (ship `inspecto-ops` in the LA edition), remote (Cases live in
Inspecto, via SEP-22), or both?
*Recommendation:* both — local by default, remote when paired with an Inspecto installation.
**Answer:** both — local by default, remote when paired with an Inspecto installation. — operator 2026-09-30

**D6 — Timeline.** The customer's date. Does Phase 1 ship **before** Phase 0 completes (packaging over today's
seams), or after?
*Recommendation:* Phase 1 may ship over today's seams (they work in one JVM); Phase 0 must precede Phase 2.
**Answer:** Phase 1 may ship over today's seams (they work in one JVM); Phase 0 must precede Phase 2. — operator 2026-09-30

**D7 — Entity List home.** `assurance-capability-plan.md` D-P10 makes the assurance list and the LA Entity List
**one kind**, but today its routes live in `inspecto-geo-link` (grounded 2026-09-27: no core or SPA code outside
LA uses them yet). Move Entity Lists to core so assurance works without LA, or keep them LA-only?
*Recommendation:* move to core before assurance WS-12 starts — cheaper now than after both sides depend on it.
**Answer:** move to core before assurance WS-12 starts — cheaper now than after both sides depend on it. — operator 2026-09-30

---

## 6. Risks

- **Hand-kept mirrors drift** (§1.2) — the separation multiplies the surfaces that must agree; SEP-02 first.
- **Cross-installation security is new ground** — SSRF, token scope, replay and audit on both sides; needs a
  formal review before SEP-21/22 (precedent: `ses-sns-adapter-design.md`).
- **Evidence across a boundary** — a Dossier's SHA-256 root covers raw entity ids; masking on the way out
  (LA `D-U6`) must keep it verifiable.
- **"Separate" still ships the whole core** under A/B — sizing, footprint and licence text must say so
  honestly. Option D exists to remove this.

---

## 7. Option D — LA as a separable product (LONG-TERM TARGET)

*Added 2026-09-27 from the operator's proposal. A later delivery, not the near-term ask.*

### 7.1 Operator direction (2026-09-27)

1. **Part of Inspecto, sellable separately** — the LA product is produced from the same reactor by a build
   flavor plus the extra pieces it needs (a host, a landing page, auth wiring). **No duplicated code**: a piece
   both products need is *extracted* into a shared module, never copied.
2. **Big Datasets** — the product must handle data far beyond today's browser caps.
3. **A strong graph on the backend** — the analysis the SPA runs today must also run server side.
4. **Storage:** Parquet queried by DuckDB, in an **LA-specific partitioning scheme**. (DuckPGQ was dropped 2026-10-01; see D9.)
5. **An optional graph database, with or without a vector database, is acceptable** — provided it is
   **LA-exclusive** (it never becomes a dependency of the rest of Inspecto).
6. The operator's eight points: metadata from Parquet · LA-owned storage · Investigation metadata under an LA
   location · public API · UI module · integration by external attributes (e.g. `caseId`) · shareable results
   and widgets · long-running Investigation sessions.

### 7.2 Why today's shape cannot meet it

- **The graph is a browser feature.** All 27 algorithms run in the browser on the main thread; the backend
  folds and filters only ([`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md)).
  The projection is capped at 500 nodes **before** any centrality runs, and the analysis cap (2 000) throws
  ([`link-analysis-backlog-plan.md`](../archived-documents/plans-archive/link-analysis-backlog-plan.md) §1). The server-side exceptions so far are
  traversal, the hop ladder and the branching pattern
  (`inspecto-la-core/src/main/java/com/gamma/la/core/PatternQueryCompiler.java`,
  `inspecto-la-core/src/main/java/com/gamma/la/core/BranchingPatternEngine.java`).
- **Every read is an aggregation over a Dataset at request time** (`GROUP BY` fold, no persisted graph).
  Fine at demo scale; the wrong shape for multi-hop over billions of rows.
- **The module cannot leave the core** (§1.1, §1.4): 17 of 21 classes use request types in
  `inspecto-processor`; the audit log (`inspecto-event`) depends on `inspecto-etl`; `Authenticator`
  (`inspecto/src/main/java/com/gamma/control/Authenticator.java`) and `OidcAuthenticator`
  (`inspecto-security/src/main/java/com/gamma/security/OidcAuthenticator.java`) both sit on the processor.

### 7.3 Target architecture

```
                       ┌──────────────── shared platform layer (extracted, not copied) ───────────────┐
                       │ inspecto-api · inspecto-util · inspecto-sql · inspecto-config                  │
                       │ + NEW: http-spi (request/response/error contract)                              │
                       │ + NEW: auth-spi (Authenticator, Subject, capabilities) + the OIDC impl moved   │
                       │ + NEW: audit-spi (event sink; no ETL dependency)                               │
                       └──────────────────────────────────────────────────────────────────────────────┘
                                                        ▲
         ┌──────────────── LA modules (LA-exclusive dependencies allowed here only) ─────────────────┐
         │ la-core     model, Investigation log + evaluator, pattern engine, Snapshot/Fact stores     │
         │ la-graph    GraphEngine SPI + server-side algorithms (§7.4)                                │
         │ la-storage  edge/node index builder + reader over partitioned Parquet (§7.4)               │
         │ la-data     DatasetProvider SPI: Parquet directory · LA index · (Inspecto impl in bridge)  │
         │ la-api      routes over http-spi + the module-owned OpenAPI fragment                       │
         └───────────────────────────────────────────────────────────────────────────────────────────┘
                     ▲                                                          ▲
   ┌──────── la-inspecto (bridge) ─────────┐                  ┌──────────── la-app (LA product host) ─────────┐
   │ RouteModule adapter · Inspecto        │                  │ JDK HttpServer · landing page · OIDC wiring   │
   │ DatasetProvider (ComponentStore /     │                  │ · static UI · LA-only settings · ingest jobs  │
   │ DatasetRelation) · EventLog sink ·    │                  │                                               │
   │ Alert Rules · Cases · Pipeline node   │                  │                                               │
   │ type for the index builder            │                  │                                               │
   └───────────────────────────────────────┘                  └───────────────────────────────────────────────┘
        Inspecto editions bundle LA + bridge                         LA product flavor bundles LA + la-app
```

Rules: LA modules never import `inspecto-processor`, `inspecto-etl` or `inspecto-engine`; the bridge is the only
place that knows both worlds. The Inspecto-only features (Alert Rules bound to Investigations, `ComponentAccess`
Dataset sharing, row scope from the Inspecto catalog) exist **only through the bridge** and report themselves
absent in the LA product — never half-working.

### 7.4 Data and graph layer

**Storage — LA index over Parquet (operator direction 4).**

| Layer | Shape | Purpose |
|---|---|---|
| Raw | Hive-partitioned Parquet as delivered (Inspecto Datasets or a customer drop) | Read in place; nothing copied (point 1: schema from the Parquet footer, enriched from the Inspecto catalog when bridged) |
| **Edge index** | Parquet, partitioned by **hash bucket of the from-entity**, sorted by (entity, time); a **mirrored copy keyed by the to-entity** for reverse traversal; bloom filters on entity keys; row-group min/max on time | One-hop lookup reads one bucket and skips row groups — the building block of multi-hop |
| **Node index** | Parquet keyed by typed entity id (`<type>:<key>`, per `link-analysis-entity-model-design.md`) with degree, first/last seen, attributes | Node attributes without re-aggregating edges; feeds centrality and masking |
| Derived | Per-Investigation materialised working sets and algorithm results | Long sessions (point 8) and shareable results (point 7) |

The index **builder is one class used two ways** (no duplication): an Inspecto Pipeline node type through the
existing seam (`inspecto-engine/src/main/java/com/gamma/pipeline/PipelineNodeType.java`) via the bridge, and a
`la-app` ingest job in the product. ⚠ The index is a **second copy of the data**: retention, legal hold,
freshness (incremental vs rebuild) and residency need an owner — decided in D11.

**Graph engine — behind one SPI (operator directions 3–5).**

| Engine | When | Notes |
|---|---|---|
| DuckDB SQL (recursive CTE + the index) | Default, always present | What traversal already does; the index makes it scale |
| **DuckPGQ** (SQL/PGQ on DuckDB) | ⛔ **Dropped 2026-10-01** (operator) | A community extension with no build for the pinned DuckDB 1.5.2 (spike D-S2, §7.10.1). Graph features arrive with DuckDB 2.0's own capabilities; revisit when the pin moves, as a new spike against whatever 2.0 ships — not as DuckPGQ |
| External graph database | Only if depth × volume measurements exceed the two above | LA-exclusive; evaluated at spike time on licence, air-gap install, JVM embedding |
| Vector index (e.g. DuckDB `vss`, or an external engine) | With fingerprinting / entity similarity | LA-exclusive; pairs with the future entity-context port (I4) |

**Server-side algorithms.** Port the browser algorithms to `la-graph`, run over the index, with the browser kept
for small graphs. Parity is pinned the way the branching pattern already is — one golden fixture asserted by
both the TS spec and the Java test
(`inspecto-ui/src/app/modules/admin/studio/link-analysis/branching-parity.fixture.json`). Long runs are
**asynchronous jobs** with progress, cancel and a stated budget — never a silent cap.

⚠ **"DuckDB 2.0" is not verified.** The repo pins DuckDB **1.5.2.1** (`pom.xml`, 2026-09-27). Treat the target
as "the DuckDB major that is current when option D starts"; spike D-S1 confirms the version and that
partition, row-group and bloom-filter pruning actually happen for entity-key lookups (measured, not assumed).

### 7.5 UI

- `projects/link-analysis` — an Angular **library** in the `inspecto-ui` workspace: LA + Geo + the graph canvas
  (moved out of Catalog, SEP-03) + the pure-TS graph and geo libraries.
- Two shells: the Inspecto SPA (today's routes, now importing the library) and `projects/la-app` (landing
  page, sign-in, Investigations list, LA + Geo).
- Everything host-specific (Datasets picker, Cases, tags, AI assist, dashboards) enters the library through
  **injected host services** with an Inspecto implementation and an LA App implementation — the SPA mirror of
  the backend bridge. SEP-03…06 are the prerequisites.

### 7.6 Integration with Inspecto (and the world)

- **By reference first (point 6).** An Investigation carries appended external references
  `{system, type, id, url}` — `caseId` is one. ⚠ The Investigation header is write-once, so references are
  appended records (like `attachments.jsonl`), not header fields. This replaces the remote Case write (I3)
  for most needs.
- **Shareable results (point 7).** The Dossier (already SHA-256-rooted) exported as a verifiable bundle; a
  read-only embeddable view (URL + scoped token) as the "shared widget"; optionally Inspecto importing a working
  set as a Dataset. Masking at export must keep the root verifiable (LA `D-U6`).
- **Trust between installations** (I1) is still needed for any live call; by-reference links need none.

### 7.7 Parallel analyst sandboxes (point 8 — clarified by the operator 2026-09-27)

**Meaning (operator):** several analysts work **in parallel**, each in an **isolated sandbox** with a
**pristine set of groupings and dynamic filter lists** for their Investigation — on the same or on different
Investigation Datasets. It is a **stateful, server-side analysis session**, not a sequence of queries fired at
the database.

⚠ **Name owed (D16).** "Sandbox" already means the SQL lockdown in code
(`inspecto-sql/src/main/java/com/gamma/sql/SqlSandboxPolicy.java`); GLOSSARY §0 forbids one word on two concepts.
"Sandbox" is used below as a placeholder only.

#### 7.7.1 What already exists (grounded 2026-09-27)

- The Investigation is an ordered, replayable op log (`seed` · `expand` · `exclude` · `hide` · `keep` …) that
  evaluates to a Working Set (`inspecto-la-core/src/main/java/com/gamma/la/core/InvestigationEvaluator.java`).
- **Forks exist**: `POST /inv/investigations/{id}/reorder` makes a new Investigation naming its parent,
  re-applies the ops and seals a fresh read; the fork is assembled off to the side and moved in with one rename.
- **Entity Lists are pinnable**: `GET /entity-lists/{id}?at=<seq>` returns `{atSeq, headHash}`, a
  self-verifying pin over the hash-chained fact log (`EntityFactLog`); a list op keeps the list it sealed,
  and a fork does not re-resolve it.
- 🔴 **Access is owner-only** (D-E7, LA-20): a non-owner reads 404. **Parallel multi-analyst work contradicts a
  signed decision** — D-E7 must be revisited (D19), not worked around.

#### 7.7.2 Model

```
Investigation  (the shared line of enquiry; its log is the record)
 ├─ members: lead · analyst · reviewer                         ← replaces owner-only (D19)
 ├─ main log  op1 … opN
 └─ Sandboxes  (one per analyst, or a shared team sandbox — D17)
      ├─ baseline   = main log @ position k  +  PINNED inputs:
      │                 Dataset version(s) · Entity List pins {atSeq, headHash} · grouping-set version
      ├─ own ops    = a branch of the log from k
      ├─ own groupings      (analyst judgement: "these 3 accounts are one person", "ring A")
      ├─ own filter lists   (ad-hoc lists; "dynamic" lists are predicates SEALED at baseline)
      └─ derived state      (materialised Working Set, grouping tables, algorithm results, graph in memory)
```

- **Pristine** means: a sandbox sees only its baseline plus its own changes. A newer Entity List version, a
  newer Dataset partition, or another analyst's work reaches it **only by an explicit rebase** — never silently.
  Replaying the baseline and the sandbox's ops reproduces the same result; that is the determinism contract, and
  it needs **versioned inputs** — immutable Parquet files named by manifest in the LA index (§7.4), not a mutable
  table.
- **Groupings are judgement, not rules.** They stay separate from deterministic entity resolution (LA-17 slice
  2) and from stated rules in the Dossier (which already separates stated rules from analyst judgement).
- **Dynamic filter lists** are evaluated and sealed at baseline (members recorded), re-evaluated only on rebase.
  The shared Entity List log is never written by a sandbox.
- **Same or different Datasets.** A sandbox binds its own Dataset set; entities meet across Datasets by typed
  id (`<type>:<key>`).
- **Promotion (D18).** Sandbox work reaches the Investigation by **promote**: its ops and groupings are
  re-applied on the current main log (a rebase). Op-level conflicts — for example, an `exclude` of an entity the
  new base no longer holds — are **reported to the analyst, never dropped**. Optionally a reviewer approves
  (the four-eyes design in the LA backlog, D-U7 option A). Alternative: publish the sandbox as a fork.
- **Evidence (D20).** Recommended: a Dossier is built from the **promoted** Investigation only; sandbox work is
  exploration, not evidence, until promoted. Every op is still audited with sandbox id and actor.

#### 7.7.3 Runtime — what makes it more than a query

| Concern | Design |
|---|---|
| Isolation of state | Each sandbox owns a **DuckDB database file** in the derived layer, attaching the shared base **read-only**. The base is immutable, so readers take no locks and sandboxes never see each other's tables |
| Isolation of compute | Per-sandbox memory limit, thread count and temp directory; a **global admission controller** caps concurrent heavy jobs so one analyst's algorithm cannot starve the others |
| Incremental evaluation | Apply each op to the materialised Working Set instead of replaying the whole log; **checkpoints** every k ops make undo, redo and "branch from here" cheap |
| Graph in memory | Algorithms load a compact adjacency structure from the materialised Working Set once per change, not per call |
| Long jobs | Asynchronous, with progress, cancel and a stated budget; results land as sandbox tables |
| Idle | **Hibernate** (free memory, keep the file); rehydrate on return. Derived files may be evicted — they rebuild by replay from the pinned baseline. **The log is never evicted** |
| Writers | One writer per sandbox via an **edit lease** (a second tab or a second team member reads, or takes the lease) |
| Masking | Applied per viewer at render and at export (LA `D-U6`), the same in every sandbox |

#### 7.7.4 Size and prerequisites

This is **L–XL** on its own and depends on: versioned inputs (§7.4 index manifests) · the membership model (D19)
· the server-side graph engine (D-4). It is the largest single piece of option D.

### 7.8 Phases

| Phase | Content | Size | Depends on |
|---|---|---|---|
| **D-0** | §4 Phase 0 (SEP-01…06) — shared with A/B | M–L | — |
| **D-1** | Extract the shared platform layer: `http-spi`, `auth-spi` (+ move OIDC), `audit-spi` (cut the ETL edge); move LA onto them; `la-inspecto` bridge; Inspecto behaviour unchanged | L | D-0 |
| **D-2** | Spikes D-S1…D-S4 (§7.10) — decide storage and engine from measurements | M | — (parallel) |
| **D-3** | `la-storage` edge/node index + builder (both hosts); `la-data` providers | L | D-1, D-2 |
| **D-4** | `la-graph`: GraphEngine SPI, server-side algorithms with parity fixtures, async jobs; optional graph / vector engines | L–XL | D-3 |
| **D-5** | `la-app` host + `projects/la-app` shell + LA product flavor (bundle, boot smoke, licence text) | M–L | D-1, SEP-03…06 |
| **D-6** | Integration: external references, Dossier export bundle, embeddable view; I1 trust only if live calls are needed | M | D-5 |
| **D-7** | Parallel analyst sandboxes (§7.7): membership model, pinned baselines, per-sandbox DuckDB files, incremental evaluation + checkpoints, admission control, promote/rebase with conflict report, hibernate/rehydrate | L–XL | D-3, D-4, D16–D21 |

### 7.9 Decisions owed (option D)

**D8 — Shared platform layer.** Extract `http-spi` / `auth-spi` / `audit-spi` as new modules, or widen existing
light modules (`inspecto-api` holds one class today)?
*Recommendation:* new, narrowly named modules — easier to police with a dependency guard.
**Answer:** new, narrowly named modules — easier to police with a dependency guard. — operator 2026-09-30

**D9 — Graph engine order.** DuckDB SQL + index first, DuckDB 2.0's own graph features when the pin moves (DuckPGQ dropped 2026-10-01, operator), external graph DB only
after D-S3 measures a gap?
*Recommendation:* yes, in that order.
**Answer:** yes, in that order. — operator 2026-09-30

**D10 — Partitioning scheme.** Hash bucket of the from-entity + mirrored to-entity copy (§7.4), or time-first
partitions with entity bloom filters?
*Recommendation:* decide from D-S1 on a realistic corpus; default to entity-hash.
**Answer:** decide from D-S1 on a realistic corpus; default to entity-hash. — operator 2026-09-30

**D11 — Index ownership.** The edge/node index is a second copy: incremental or rebuild; retention and legal hold
inherited from the raw Dataset or LA's own?
*Recommendation:* incremental append by partition; retention inherited, never longer than the raw data.
**Answer:** incremental append by partition; retention inherited, never longer than the raw data. — operator 2026-09-30

**D12 — Who owns sign-in in the LA product.** `la-app` with the moved OIDC authenticator, or an external IAM
only (as Professional today)?
*Recommendation:* external IAM through the moved OIDC authenticator; demo sign-in for demos only.
**Answer:** external IAM through the moved OIDC authenticator; demo sign-in for demos only. — operator 2026-09-30

**D13 — Vector index scope.** Only with fingerprinting / entity similarity, or earlier?
*Recommendation:* only with the entity-context work (I4).
**Answer:** only with the entity-context work (I4). — operator 2026-09-30

**D14 — Product boundary of Geo.** Does the LA product always include Geo (they are one UI unit today)?
*Recommendation:* yes — matches D2's one-SKU recommendation.
**Answer:** yes — matches D2's one-SKU recommendation. — operator 2026-09-30

**D15 — Order against the near term.** Does option A ship first (over today's seams), with D later, or does the
customer wait for D?
*Recommendation:* A first if the customer has a date; D-0 in parallel because it is no-regret.
**Answer:** A first if the customer has a date; D-0 in parallel because it is no-regret. — operator 2026-09-30

**D16 — Name of the per-analyst working copy.** "Sandbox" collides with the SQL sandbox. Candidates:
*Analysis Workspace* · *Draft* · *Branch*. ⛔ Not *Workbench* — GLOSSARY already gives it to the Builder
authoring surface.
*Recommendation:* pick one and enter it in GLOSSARY §13 before any code; *Draft* reads naturally with
*promote*.
**Answer:** pick one and enter it in GLOSSARY §13 before any code; *Draft* reads naturally with *promote*. — operator 2026-09-30

**D17 — Granularity.** One private sandbox per analyst per Investigation, a shared team sandbox (edit lease), or
both?
*Recommendation:* private per analyst by default; team sandboxes later if asked for.
**Answer:** private per analyst by default; team sandboxes later if asked for. — operator 2026-09-30

**D18 — Promotion model.** Rebase-and-promote into the main log (conflicts reported), or publish as a fork only?
Reviewer approval required?
*Recommendation:* rebase-and-promote; approval optional per Investigation, required when it is marked sensitive.
**Answer:** rebase-and-promote; approval optional per Investigation, required when it is marked sensitive. — operator 2026-09-30

**D19 — Membership replaces owner-only (revisits signed D-E7).** Investigation members with roles
(lead · analyst · reviewer); the Enterprise policy verdict still applies (a DENY hides it from members too).
*Recommendation:* yes — parallel work is impossible under owner-only.
**Answer:** yes — parallel work is impossible under owner-only. — operator 2026-09-30

**D20 — What is evidence.** Dossier from the promoted Investigation only, or sandboxes too?
*Recommendation:* promoted only; sandboxes are exploration.
**Answer:** promoted only; sandboxes are exploration. — operator 2026-09-30

**D21 — Concurrency target.** How many analysts and concurrent sandboxes per installation, over what data
size? This sizes memory, the admission controller and spike D-S5.
*Recommendation:* the operator states a target (for example 20 analysts, 50 sandboxes, 10⁹ edges); spike D-S5
measures against it.
**Answer:** 20 analysts, 50 concurrent Drafts, 10⁹ edges — operator 2026-09-30

### 7.10 Spikes (measure before building — D-2)

| Id | Question | Pass condition |
|---|---|---|
| D-S1 | Does DuckDB (current major) prune partitions, row groups and bloom filters for an entity-key lookup on the proposed index? | `EXPLAIN ANALYZE` shows files / row groups skipped on a planted corpus; one-hop latency measured at 3 sizes |
| ~~D-S2~~ | ~~Does DuckPGQ load offline on an LA-exclusive connection and beat recursive SQL on 2–4 hop paths over the index?~~ **Dropped 2026-10-01** (operator): no build exists for DuckDB 1.5.2; DuckDB 2.0's graph features are assessed when the pin moves | — |
| D-S3 | At what depth × volume does DuckDB stop being enough? | A curve, not an opinion; only a measured gap opens the external graph DB question |
| D-S4 | Can the browser algorithms run server side with identical results? | Parity fixture green in both languages for the ported set |
| D-S5 | Do N concurrent sandboxes (per-sandbox DuckDB file, shared read-only base) hold the D21 target? | Latency and memory per sandbox at the target concurrency; one heavy job does not move the others' p95 beyond a stated bound |

#### 7.10.1 Spike results — 2026-09-30 (first measurement)

**Method.** Harness `inspecto-geo-link/src/test/java/com/gamma/control/InvTraversalBench.java` (`@Tag("bench")`,
skipped unless `-Dinspecto.bench.dir` is set; generated Parquet lives outside git under `.claude/worktrees/`).
Machine: Intel i7-9850H (6 cores / 12 threads, 2.6 GHz), 32 GB RAM, Windows 11, JDK 27, DuckDB **1.5.2**
(the repo pin). Corpus: `nodes = edges / 5`, source `= floor(nodes · u³)`, target `= floor(nodes · u²)`, `u` a
deterministic hash of the row — a heavy-tailed out-degree, mean 5, median 3, p99 ≈ 39, top hub 17 k / 79 k / 368 k
edges at 10⁶ / 10⁷ / 10⁸. Warm figures drop the first run; the OS file cache is always warm (no cache drop on
Windows), so "cold" means a fresh engine, not a cold disk.

| Spike | Verdict | Evidence |
|---|---|---|
| **D-S1** pruning | ✅ **PASS for partitions and row groups; bloom filters not isolated** | One-hop lookup of a median-degree node, p50 (p95): flat unsorted file 20 (23) / 126 (156) / **1 031 (1 215) ms** at 10⁶ / 10⁷ / 10⁸; entity-hash partitioned (64 buckets) + sorted by (entity, time), bucket predicate supplied: 24 (28) / 39 (55) / **43 (49) ms**; same layout without the bucket predicate: 29 / 40 / 97 ms. `EXPLAIN ANALYZE` at 10⁸: *Total Files Read* 64 → **1** with the bucket predicate; the 10× gain without it is row-group min/max skipping on the sorted key. Bloom-filter skipping was not separated from min/max skipping. |
| ~~**D-S2** DuckPGQ~~ | ⛔ **DROPPED 2026-10-01 (operator)** — no DuckPGQ build exists for the repo pin DuckDB 1.5.2 (HTTP 404 at `community-extensions.duckdb.org/v1.5.2/…`, 2026-09-30; builds exist for 1.5.1 and earlier, extensions are ABI-bound to the exact engine). Not pursued: DuckDB 2.0's own graph features are the intended route once the pin moves. | Nothing was installed or staged; nothing enters the bundle. The bench's D-S2 probe was deleted. |
| **D-S3** depth × volume | ✅ **curve measured — the gap is VOLUME, not depth** | Route-shaped recursive CTE, p99-degree start, yield fence 10 000 (100 000), p50 ms at depth 2 / 4 / 6 / 8. **10⁶:** 61 / 133 / 134 / 172 (56 / 123 / 196 / 286). **10⁷:** 306 / 392 / 467 / 438 (286 / 471 / 594 / 656). **10⁸:** 2 698 / 3 087 / 3 091 / 3 340 (3 110 / 6 115 / 6 087 / 6 347). Depth costs ≈ linear in rows walked (bounded by depth × yield); volume costs ≈ linear in edges scanned, because a flat Dataset is re-scanned per level. Materialising the edge CTE is 1.5–2× faster at 10⁶–10⁷ and **2× slower at 10⁸** (4 966 – 17 223 ms), so it is not the fix. |
| **D-S4** algorithm parity | ✅ **PASS 2026-10-01 — all 28 algorithms of classes A, A′, B, B′ ported (17 + 5 + 4 + 2 — an earlier version of this row said 34, an addition error); the 11 class-C transforms stay in the browser by design (§7.13)** | Grounded only: `graph-analysis.ts` exports ~40 algorithms; exactly one (branching pattern) has a Java port with a shared golden fixture, green in both languages. Iterative ones (PageRank, eigenvector, Katz, HITS, Louvain) will need tolerance-based parity, not equality. |
| **D-S5** concurrency | ⏸ **NOT RUN** | Not cheap on one laptop; left for D-2 proper. |

**Extrapolation to 10⁹ edges (D21).** Flat layout: one hop scales ≈ linearly (20 → 126 → 1 031 ms per decade), so
≈ 10 s per hop at 10⁹ — unusable, and a 5-hop walk would breach the 5 s fence at 10⁸ already. Partitioned
layout: one hop grew 24 → 39 → 43 ms over two decades; with the bucket count raised so a bucket stays near
10⁶–10⁷ edges (≈ 256–1 024 buckets at 10⁹) one hop should stay in the tens of milliseconds, and a 5-hop walk
that does one bucket lookup per frontier node lands in the low seconds. ⚠ This is an **extrapolation**: 10⁹ was
not generated (≈ 20 GB of Parquet and hours on this machine), the lookup was a single-key probe rather than a
frontier batch, and one machine says nothing about concurrent Drafts (D-S5).

**Implications.** **D9 holds** — DuckDB SQL plus the index is enough up to 10⁸ on a laptop *provided the traversal
reads the index, not a flat Dataset*; no measured gap opens the external graph DB question, and DuckPGQ was
dropped (D-S2; no build for the pinned DuckDB 1.5.2), so D9 rests on SQL + index alone. **D10 confirmed** — entity-hash partitioning with (entity, time) sort is the
default, and the reader **must compute and push the bucket predicate** (2.3× at 10⁸ over relying on pruning
alone). Today's route walks a flat Dataset, so its practical ceiling is ≈ 10⁷ edges within the 5 s fence.

### 7.11 Grounded dependency edges — 2026-10-01 (input to the D-1 design)

Read from the module poms (compile/provided/test scope, profiles excluded) and from `import` lines in `inspecto-geo-link/src/main`.
⚠ Maven artifact `inspecto-processor` is the directory `inspecto/` (the control plane + `com.gamma.control`).

**Reactor edges.** Core chain: `inspecto-api` ← `inspecto-util` ← `inspecto-config`/`inspecto-sql` ← `inspecto-etl` ← `inspecto-event` and `inspecto-acquire` ← `inspecto-engine` ← `inspecto-processor`. Every optional module (`agent`, `backup`, `connectors`, `events`, `exchange`, `geo-link`, `metrics`, `ops`, `policy`, `security`, …) depends on `inspecto-processor` alone (compile or `provided`) plus a test-scope `inspecto-etl`. `inspecto-geo-link` → `inspecto-processor`, `inspecto-etl[test]`. The only Java edge INTO it is `inspecto-policy` → `inspecto-geo-link[test]`.

**What geo-link main imports from core (by owning module).**

| Owner | Classes | Imports | Note |
|---|---|---|---|
| `inspecto-processor` (`com.gamma.control`) | `ApiContext` `ApiException` `ErrorCodes` `RouteModule` (the http-spi seed); `Subject` `RowScope` `ComponentAccess` (auth-spi seed); `WriteGates` `EntityTypes` `LinkAnalysisSettings` `PendingChanges` `AnnotationTargets` | ~95 | `http-spi` + `auth-spi` must be cut OUT of this module; five of these are not SPI-shaped (`WriteGates`, `EntityTypes`, `LinkAnalysisSettings`, `PendingChanges`, `AnnotationTargets`) and each needs a home or an adapter |
| `inspecto-event` | `Event` `EventLog` `EventType` | 39 | the `audit-spi` seed; EXTRACTED 2026-10-01 into `inspecto-audit-spi` (D-1 step 2; also takes `MetricRegistry`); `inspecto-event` keeps the edge to `inspecto-etl` |
| `inspecto-engine` | `QueryExecutor` `DatasetRead` `ResultSetDescriptor` `MeasureCompiler` `DatasetMeasureProbe` `ConditionSql` · `AlertRule` `AlertService` `InvestigationMeasureProbe` · `ComponentRegistry` · `ObjectAccess` · `WatchListFeed` | ~30 | the Dataset-read port (`DatasetRead`, SEP-01) is the start; Alert Rule + Pipeline + Cases usage belongs in the bridge, not `la-core` |
| `inspecto-util` | `SqlIdent` `DuckDbUtil` `JsonAttributes` | 14 | stays shared |
| `inspecto-sql` | `SqlGuard` `SqlSandboxPolicy` | 6 | stays shared |
| `inspecto-config` | `SourceZoneGrammar` | 1 | stays shared |

**Inbound references to LA.** Real code: `ControlApi` registers `AbsentGeoLinkRoutes` (the Personal 503 stub). `inspecto-engine` (`AlertRule`, `AlertService`, `InvestigationMeasureProbe`, `WatchListFeed`) names LA only in prose, not in an import. Other mentions are `package.ps1`, `NoGeoLinkShipsInThePersonalBuildTest`, `BootstrapRoutes`, `CollectorService`, SBOMs.

**Consequence for D-1.** `la-core`/`la-graph`/`la-storage` can already stay clear of `inspecto-processor`, `inspecto-etl` and `inspecto-engine` only if the 12 `com.gamma.control` classes, the three event classes and the engine's query/alert/pipeline/objects classes are each re-homed behind an SPI. The engine group is the biggest: Alert Rule, Pipeline and Cases usage moves to `la-inspecto`.

### 7.12 Grounded SPA dependency edges — 2026-10-01 (input to D-5)

Non-spec `import` lines under `inspecto-ui/src/app/modules/admin/studio/link-analysis` (LA) and `…/geo-map` (Geo), after D-0 (SEP-03…06).

| Import target | LA | Geo | Target home |
|---|---|---|---|
| `inspecto/graph` (engine, canvas, brush, entity-id mint) | 25 | 3 | the `link-analysis` library |
| `inspecto/geo`, `inspecto/investigation` | 2 (investigation) | 8 + 2 | the `link-analysis` library |
| `inspecto/components`, `theme`, `format`, `data-table`, `viz`, `query`, `confirm.service`, `dialog-dirty-guard`, `component-model` | ~75 | ~13 | shared core library |
| `inspecto/api` | 34 | 4 | API client base shared; LA's own routes move into the library |
| `studio/datasets` (6 LA / 3 Geo), `widgets` (3), `dashboards/dashboard-header`, `catalog` (2), `pipelines` (1), `tags`, `transfer`, `ai-assist`, Cases via `ObjectsService` | ~18 | ~5 | **host-service tokens** (Inspecto and LA-App implementations) |

Inbound references into the two features: `app.config.ts` (viz-kind registration, via `provideAppInitializer` since SEP-06), `inspecto/api/link-analysis-settings.service.ts` (a client the library will own), `modules/admin/menu/menu-artifact.component.ts` (a host feature that opens LA; becomes a URL or a library-exposed route). Nav gating by `features.geoLink` stays in the Inspecto shell.

Target: `projects/inspecto-ui-core` (shared) ← `projects/link-analysis` (library; reaches the host only through injected tokens) ← two shells (the Inspecto SPA and `projects/la-app`). No module federation (D3). Library rules: no import from `modules/admin/**` (enforce with a lint rule), configuration only through tokens, and a missing host service makes its feature report itself absent. Owed before extraction: ~8 host-service interfaces (datasets, widgets, Cases, tags, transfer, AI assist, dashboard header, catalog link) and the move out of `modules/admin/studio/{link-analysis,geo-map}`. D-5 depends on D-1 and SEP-03…06.

**D-5 prep done in place (2026-10-01).** Before any physical move, the LA and Geo feature code now reaches the host only through five injection tokens in `inspecto-ui/src/app/inspecto/la-host/`: `LA_DATASETS` (`list`, `get`, over an LA-owned `LaDataset`), `LA_WIDGETS` (`saveWorkingSetWidget`; `WorkingSetBinding` moved to the contract), `LA_CATALOG` (`kinds`, `list`), `LA_PIPELINE_GRAPH` (`toG6Data`, `provenanceCounts`) and `LA_DASHBOARD_HEADER` (the host's header component, rendered with `NgComponentOutlet`). A token with no provider throws an error naming itself. The Inspecto implementation is `modules/admin/studio/la-host.providers.ts` (`provideLaHostServices()`, registered in `app.config.ts`). An ESLint `no-restricted-imports` block in `eslint.config.mjs` forbids `app/modules/**` and `../**` in non-spec files under `link-analysis/` and `geo-map/`; it went red when a forbidden import was re-added. **Part 2 (2026-10-01) — the four `app/inspecto/**` host edges are tokenised too:** `LA_TAGS` (`open(target)`: the host opens the Tag assignment dialog), `LA_TRANSFER` (`menu`, `banner`: the host's Import/export menu and import-draft banner; `LaImportDraft` is the LA-owned draft shape), `LA_AI_ASSIST` (`assist`, `explain`: the host's AI components; `LaAiDraft` carries only the `config` LA reads) and `LA_CASES` (`available` signal, `list()`, `openFromEntities()`; the host decides real vs placeholder — `available` is false when `inspecto-ops` is absent and LA then offers its own `mockCases`, never calling the others). Components cannot be reached through a service, so `LA_TRANSFER` / `LA_AI_ASSIST` hand over component types rendered with `<inspecto-la-host-slot>` (`LaHostSlotComponent` in `la-host/`: applies inputs in `ngDoCheck` and subscribes outputs, which `NgComponentOutlet` cannot). The `no-restricted-imports` rule now also forbids, in the same non-spec files, `app/inspecto/{tags,transfer,ai-assist}` (and sub-paths), `app/inspecto/api/objects.service`, and the `ObjectsService` name from the `app/inspecto/api` barrel; a mutation (re-added tags / transfer / `ObjectsService` imports) went red and was restored. The shared design-system/library paths (components, theme, format, data-table, viz, query, graph, geo, investigation, confirm, dialog-dirty-guard, api) stay allowed. **Dynamic `import()` is covered too (2026-10-01):** `no-restricted-imports` ignores `ImportExpression`, so `eslint.config.mjs` adds a `no-restricted-syntax` selector set (`laDynamicImportSelectors`) with the same host-feature and host-edge restrictions on string-literal specifiers and flags any computed specifier in the same non-spec files; the 3 existing lazy imports (`link-analysis.viz.ts`, `geo-map.viz.ts`) are all same-folder, and `inspecto-ui/tools/la-dynamic-import-lint.test.mjs` (`node --test`) falsifies it. Not covered by the rule: the remaining `app/inspecto/api` imports (`SessionService`, `InvService`, `NotesService`, `LinkAnalysisSettingsService` … and `opsEnabled`/`exchangeEnabled` read straight from `SessionService` in `link-analysis.component.ts`), which are LA's own API clients or session facts and still have to be classified into `inspecto-ui-core` (moves with the library) versus LA-owned (moves into `projects/link-analysis`). Left for the physical move: create `projects/link-analysis` and `projects/inspecto-ui-core`, move the two folders and `inspecto/la-host`, point the `paths` entries at them, and write the LA App provider set.

### 7.13 D-S4 inventory and tranche A slice 1 — 2026-10-01

**Inventory of `inspecto-ui/src/app/inspecto/graph/graph-analysis.ts`** (~45 exports; grep found no `Math.random`, `Date.now` or `new Date`, so every algorithm is deterministic given node and edge order).

| Class | Exports | Parity rule |
|---|---|---|
| **A — discrete** | `shortestPath` `allPaths` `neighborhood` `egoNetwork` `connectedComponents` `degreeCentrality` `kCore` `triangleCount` `articulationPoints` `bridges` `isForest` `descendants` `findCycles` `cliques` `maximumSpanningForest` `weightedShortestPath` `maxFlow` | Exact equality, including BFS order and edge ids |
| **A′ — single-pass float** | `betweennessCentrality` `closenessCentrality` `jaccardSimilarity` `linkPrediction` `suspicionScore` | Same operation order ⇒ the same IEEE doubles (Java `double` = JS `number`); assert with a tiny epsilon, never `==` on a sum whose order differs |
| **B — iterative** | `pageRank` `eigenvectorCentrality` `katzCentrality` `hits` | Fixed iteration count; epsilon per algorithm written on the fixture |
| **B′ — order-sensitive partition** | `detectCommunities` (label propagation) `louvainCommunities` | Pin node order in the fixture; decide per algorithm between label equality and partition equivalence |
| **C — transform / UI, not ported** | `mergeGraphs` `searchNodes` `filterByKinds` `filterByTime` `collapseBranches` `aggregateSuperNodes` `superMembersOf` `explainNode` `matchPattern` (+ `followsInTime` `edgeTimeIndex` `patternNeedsTime`) `configureGraphLimits` and caps | Server already folds and filters; the linear matcher stays in the browser (branching is already ported) |

**Traps found while grounding.**
- ✅ **CLOSED 2026-10-01 by `canonical-v1` (D-4 Decision 3): equal scores rank by UTF-16 code-unit order of the normalised entity id, then label — `compareCanonicalV1` (TS) / `BY_SCORE_THEN_ID_THEN_LABEL` (Java), pinned by the mixed-case `canonicalV1` row of `graph-algorithms-parity.fixture.json` in both languages.** Original note: 🔴 **Tie-break by label.** `scored()` sorts equal scores with `localeCompare`; Java ports use ordinal compare. They agree only on lowercase ASCII labels, so fixtures use such labels and a server result shown in the browser needs a stated canonical order before this ships. Not solved, recorded.
- **Closed-graph assumption.** An edge whose endpoint is not a node leaks into `undirectedNeighbors` (the real node gets a phantom neighbour). The Java port keeps the assumption; fixtures contain no dangling edges.
- **Multigraph rules.** Degree counts parallel edges and a self-loop twice; `kCore`, `triangleCount` and `cliques` collapse them. The fixture plants both (`e4`, `e11`).

**Slice 1 (done).** `GraphAlgorithms` in `inspecto-la-graph` (moved from `inspecto-geo-link`, D-1 step 1) ports `shortestPath`, `neighborhood`, `degreeCentrality`, `connectedComponents`, `kCore`, `triangleCount`. The fixture `graph-algorithms-parity.fixture.json` is **derived by hand**, not produced by either implementation, and is asserted by `graph-algorithms-parity.spec.ts` (12 tests) and `GraphAlgorithmsParityTest` (6 tests); both green. **Mutation-checked:** dropping the running max in `kCore` → red at rank 1 (`b` vs `e`); swapping in/out order in `BOTH` → `connectedComponents` red (`[a,b,c,…]` vs `[a,c,b,…]`); keeping self-loops in the simple graph → `triangleCount` red at rank 6. **Remaining in class A:** 11 exports, then A′, B, B′. D-S4 stays *in progress*.

**Slices 2–5 (done, same day).** Four parallel lanes in isolated worktrees plus a final slice, each a NEW class + fixture + TS spec + Java test, each fixture hand-derived or (float-heavy) produced by an independent Python transcription of the TS, never by either implementation under test:

| Class | Java | Algorithms | Tests (Java / TS) |
|---|---|---|---|
| slice 1 | `GraphAlgorithms` | `shortestPath` `neighborhood` `degreeCentrality` `connectedComponents` `kCore` `triangleCount` | 6 / 12 |
| paths and flow | `GraphPaths` | `allPaths` `egoNetwork` `weightedShortestPath` `maxFlow` `maximumSpanningForest` (+ `edgeWeight`) | 6 / — |
| structure | `GraphStructure` | `articulationPoints` `bridges` `isForest` `descendants` `findCycles` `cliques` | 6 / — |
| float centrality | `GraphCentrality` | `betweennessCentrality` `closenessCentrality` `jaccardSimilarity` `linkPrediction` | 4 / — |
| iterative + communities | `GraphIterative` | `pageRank` `eigenvectorCentrality` `katzCentrality` `hits` `detectCommunities` `louvainCommunities` | 7 / — |
| composite | `GraphSuspicion` | `suspicionScore` (needs the four classes above) | 1 / 4 |

Integrated result: **Java 30 parity tests green** (the five parity classes in `inspecto-la-graph` plus `GraphSuspicionParityTest`; 29 + 1) and **TypeScript 128 tests green** over the six `graph-*-parity.spec.ts` files (124 + 4). Floats assert at 1e-9 (Java `double` = JS `number`; the arithmetic order is ported, so the doubles agree far inside that). **Every port was mutation-checked** — about 60 mutants across the lanes, each turned red on the right values; the equivalent mutants are named in the commit messages' lane reports, not hidden.

🔴 **Findings the ports surfaced.**
- ~~*Tie-break order is still open*~~ closed by `canonical-v1` (§7.13 above): ordinal vs `localeCompare`; every fixture uses lowercase ASCII labels.
- *Label propagation floods a bridge* (`detectCommunities` on the lane's `main` fixture merges two triangles joined by a bridge into one community), which contradicts the TS comment about avoiding that. Java agrees with the TS, so this is browser behaviour to decide on, not a port defect.
- *`hits` eigenvector of the `hitsGraph` fixture is omitted*: three values sit within 2e-16 of each other, so their rank order is arithmetic noise. Any server result shown ranked needs an explicit tie rule.
- *The graph model carries no edge data.* `GraphAlgorithms.Edge` is `(id, source, target)`, so the weighted functions take a `Map<String, Double>` of edge id → weight. The real server graph model (D-3/D-4) must decide where weights and kinds live.
- *An iterative mutant can hang the suite* (a Louvain epsilon change looped forever); a parity test for an iterative algorithm needs a timeout when it is wired into CI.

**Residuals.** (1) ~~`findCycles` in-walk `limit` mutant survives~~ — **closed 2026-10-01**: fixture key `cyclesTwoInOneWalk` (a start with two cycles in one walk, limit 1; hand-derived, green in both languages) kills it — removing the check turns the Java test red with `expected 1 but was 2`. (2) ~~private helper copies~~ — **closed 2026-10-01**: `GraphCentrality` and `GraphIterative` use `GraphAlgorithms`' `adjacency` / `scored` / `undirectedNeighbors` / `neighborsOf` / `BY_SCORE_THEN_LABEL` (≈66 duplicated lines gone; all 30 parity tests unchanged). (3) None of this is wired to a route or an engine yet: the `GraphEngine` SPI, async jobs and the index-backed reader are D-4. D-S4's question — *can the browser algorithms run server side with identical results?* — is answered **yes**.
