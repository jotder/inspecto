---
type: Concept
title: Link Analysis — architecture — Host registration, editions, bounds and known seams
description: Part 4 of 4 of Link Analysis — architecture: Host registration, Jobs and live detection; Editions and packaging; Concurrency and resource bounds (summary); Known seams and residuals
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — architecture — Host registration, editions, bounds and known seams

Part 4 of 4 of [Link Analysis — architecture](architecture-la-core.md) · previous: [Domain core — discovery; link index, Graph Run and HTTP surface](architecture-la-core-index-http.md).

## 7. Host registration, Jobs and live detection

* **ServiceLoader files:** la-api → `RouteModule` (32, recounted 2026-11-15); geo-link → `RouteModule` (4), `InvestigationMeasureProbe`,
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
[`spec-editions-placement.md`](../../layer/platform/spec-editions-placement.md) (*Inspecto LA feature placement*).

What matters on this page is the gate behind each placement — every edition, Preview included, carries the
explorer; what Basic lacks it lacks because no installed module declares the feature:

- **Explorer** (la-core, la-storage, la-api, geo-link, entity-list): in every edition.
- **Investigation surface**: the `investigations` edition feature (declared by `inspecto-ops`) AND the Investigations
  add-on held; on by default from Standard, `503` in Basic.
- **Geo map, routes and co-location** (`geoMap` route feature on `GeoRoutes`, declared by `inspecto-ops`; D-LAE5),
  **entity profile pages** (`entityPages` on `EntityPageRoutes`, declared by `inspecto-ops`; D-LAE4) and **case history
  beside a node** (`CaseHistoryRoutes`, `investigations`; D-LAE1): from Standard, `503` in Basic.
- **Link analysis and Geo map Widget types** (`laWidgets`, a `/bootstrap` UI flag only; D-LAE2): from Standard,
  hidden in Basic.
- **Sign-in** (`inspecto-oidc`): from Standard; Preview uses demo sign-in.
- **la-store-pg** (Postgres Investigations) and the **D-E7 PDP** (`providers/inspecto-policy`): Advanced (and Preview).

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
`features.entityPages`, and the console nav, the LA app Entities area, Open-in-LA's "Open the Entity" link and the canvas node dialog's Entity page link
(`entityPageOf` in `link-analysis.component.ts`, null on Basic; it links the node's own resolved Entity Type, `5bbdc9c82`) key on it. `laWidgets` (D-LAE2) only hides the Link analysis and Geo map Widget
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
  the navigation (the Alerts entry's `feature: investigations` in `apps/link-analysis/app.toon`, `investigationsGuard`). Deep links (`?investigation=`, `?seed=`) still do not open one.
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

Jars are staged by `inspecto/package.ps1`; `-Product la` swaps the bundled SPA, not the edition. Enablement then runs
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
