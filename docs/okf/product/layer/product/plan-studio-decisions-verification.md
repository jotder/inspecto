---
type: Capability
title: Studio (BI + INV) — capability spec — Decisions, not built, refused and verification
description: Part 3 of 3 of Studio (BI + INV) — capability spec: Decisions (dated one-liners); Not built; Refused & superseded; As-built pointers; Verification
status: current
item: layer.product
audience: internal
verified: 2026-11-19 last-edit
---

# Studio (BI + INV) — capability spec — Decisions, not built, refused and verification

Part 3 of 3 of [Studio (BI + INV) — capability spec](plan-studio.md) · previous: [Specification — KPIs, Reports and scheduled delivery onward](plan-studio-kpis-reports.md).

## 4. Decisions (dated one-liners)

| Date | Decision | Who / where |
|---|---|---|
| 2026-06-28 | Report-builder design locked with the user: UI-first and phased; a Visualization Type declares a config schema and in code that is a `VizPlugin`; ⛔ never call the instance a "chart" | archived `report-builder-design.md` |
| 2026-07-01 | `chart → widget` rename taken as part of M1, while it was still mock-only and cheap | archived `widget-library-spec.md` |
| 2026-07-04 | **C6 — a schedule IS a Job**, `type: 'report'` with the dashboard in `params`; no separate scheduling entity | `kpi-reports.md` |
| 2026-07-04 | Link Analysis scoped **FULL** by the owner: include the business Entity/Link graph over Datasets, not only system graphs; all four analysis operations plus node search, kind filtering and canvas highlighting | archived `link-analysis-studio-plan.md` |
| 2026-07-05 | Geo decisions locked with the user: **D1** MapLibre GL + PMTiles; **D2** basemap fully bundled offline, consequence accepted — no satellite or terrain imagery in-bundle; **D3** mock-first delivery with the backend later; **D4** geocoding demoted out of the MVP, lat/lon columns required, returning later as a pluggable seam | archived `geo-map-analysis-plan.md` |
| 2026-07-07 | Studio persistence becomes **real** — datasets/widgets/dashboards/queries join the writable component kinds (W3/W4) | `plan-studio.md`; `MET` |
| 2026-07-08 | Link Analysis and Geo both go **backend-first**: `POST /inv/projection` folds server-side, the studio stops folding rows in the browser | `REQUIREMENTS` `INV-1`; `link-analysis.md` |
| 2026-07-08 | The BI Could-tier shipped in one pass: headless `POST /bi/query`, HMAC share tokens, the template seed pack, measure alerting, report delivery | `REQUIREMENTS` §3.5 |
| 2026-07-20 | Schema-relationship inference shipped (`GET /inv/schema/relationships`), closing the design's other deferred half; self-references included, unusable Datasets skipped rather than fatal | `link-analysis.md` |
| 2026-07-20 | **PDF export** shipped as the PNG-wrapped-in-PDF fallback — no PDF library on the classpath and the build is offline, so a snapshot, not a general-purpose export | `kpi-reports.md` |
| 2026-07-20 | The investigation pivot (design review R8): a point resolving an `objectRef` offers "View in graph" over a shared contract | `geo-map.md` |
| 2026-09-27 | **A KPI is a `kpi` component, evaluated server-side** — the tile reads target, bands, comparison and format from `GET /kpis/{id}/value`; the period is compared to date, like for like | `ASSURE-KPI-DEFINITIONS-1` |
| 2026-07-22 | **A KPI target is a business acceptance criterion**, authored by Business on the Requirement — not by the Builder inside the component that implements it | product sign-off; `kpi-reports.md` |
| 2026-07-22 | Geo Phase 4 backend shipped (`GeoRoutes`) to scale past the browser point cap | `geo-map.md` |
| 2026-07-24 | Four Link-Analysis V2 tracks shipped (advanced traversal, algorithm library, suspicion scoring, pattern packs), plus the edge-only timeline and per-view version history | `link-analysis.md` |
| 2026-07-24 | Geo client-perf follow-ons **closed as obsoleted**: the server fold plus a hard 5,000-point cap leave nothing to offload; the binner is retained but unwired and the UI has no worker infrastructure | `geo-map.md`; `BACKLOG` §7 |
| 2026-07-26 | Pattern packs become a per-Space `pattern-pack` component kind rather than a constant; the Exchange `kind` axis gains `link-analysis-view`; the pane declares its six canonical terms in-product | `link-analysis.md` |
| 2026-08-14 | **Time grain travels on the wire** — `QuerySpec.grains` is the one source of truth and ⛔ a client-side fold must not be re-added | `plan-studio.md` |
| 2026-08-14 | The Dataset store picker lists **real catalogued stores**, business groups only; and ⛔ a Dataset's `sourceName` is **never defaulted** | `plan-studio.md` |
| 2026-08-14 | The rows seam is asked in **one** place (`DatasetRowsService`), under the spec-running result service; every result is a page carrying `truncated` and an error | `plan-studio.md` |
| 2026-08-31 | **`MOCK-DEAD-COMPUTE-1`** — the former client-side folds are retained as reference oracles after the mock backend's deletion, because the live paths are asserted against them; deleting them deletes the guards | code contracts in `entity-projection.ts`, `geo-projection.ts` |
| 2026-09-02 | `trend-monitor` added to the template pack — the temporal starter board | `plan-studio.md`; `EDITIONS` `CP-08` |
| 2026-09-07 | **EDG-01 cell 3b** — `GeoRoutes` + `InvRoutes` move to `inspecto-geo-link` (Standard+); Basic gets a core 503 stub | operator; `EDITIONS` `CP-09` |
| 2026-09-07 | 🔴 `hasRoute` **excludes absent-module stubs**, so a capability flag stays honest; stubs register last and only for unclaimed patterns | `ApiContext` contract |
| 2026-09-08 | This spec: `INV-3` moved to `INC` and its edition corrected; `INV-1`/`INV-2`'s stale open items and mislabelled deferral fixed; `BI-1`'s Datasets pane, `BI-4`'s formats and `BI-7`'s follow-up corrected; the Visualization Type count pinned at 13 | this file §2 |

## 5. Not built

### 5.1 Tracked (a `docs/BACKLOG.md` row exists)

| Item | Row |
|---|---|
| DuckDB `spatial` extension for geo — deferred for want of any `ST_*` demand; progressive loading obsoleted by the 5,000-point cap | §7 *Geo map* |
| Template pack **enrichment** — the corpus is deliberately small and grows on demand | §7 C7 (continuous) |
| Widget/Dashboard sharing and RBAC beyond the Exchange axis — gated on the security module | `plan-studio.md`; `SEC` |

### 5.2 UNTRACKED — found 2026-09-08, no board row yet

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-GREENCELL-1`, `SPEC-MOCKRESIDUE-1`, `SPEC-PLANSTALE-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** `GET /bi/datasets` **RETIRE** (Studio keeps its own discovery) → `RETIRE-HALVES-1`; `POST /queries/{id}/run` **ADOPT** (Run action) → `STUDIO-HALVES-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

1. 🔴 **The deleted mock backend's doc residue is repo-wide and unowned.** Twenty current-tier documents
   still describe the offline mock as if it existed, and `plan-studio.md` names
   `inspecto-ui/src/app/inspecto/mock/sample-sources.ts` — a **dead path**: there is no `mock/`
   directory, and the file itself moved to `inspecto-ui/src/app/inspecto/fixtures/sample-sources.ts`. This
   commit fixes the Studio-owned pages; the other pages belong to `PIP`, `MET`, `ACQ` and `OPS` and need
   one sweep with a board row, because every one of them can send a reader to a dead path. The `SPC`
   spec had already recorded the deletion date, which is how this was noticed.
2. ~~**`GET /bi/datasets` has no client consumer**~~ ✅ **CLOSED by retirement 2026-09-14**
   (`RETIRE-HALVES-1`) — the seventh instance of a shipped server half with no caller, and the second
   inside `BI-7` alone. Of the two options, the operator took the route's deletion over adopting it.
3. ~~**`POST /queries/{id}/run` still has no client consumer**~~ ✅ **CLOSED** (`STUDIO-HALVES-1`) — the
   Query Library's *Run saved* calls it (`queries.component.ts` `runSaved`).
4. **A Measure has no server home.** `BI-3` and `BI-5` both speak of Measures, but a named Measure is a
   client-side expression while the wire only carries validated `{agg, field}` pairs. A named Measure can
   therefore never cross to `/bi/query`, be embedded, or drive a measure alert. That gap is deliberate
   today; whether Measures become a server concept is a product decision, not a doc edit.
5. **The two investigation panes keep routes the nav hides.** With `features.geoLink` false the routes
   stay registered, so a deep link reaches a pane that can only 503. It renders an edition message, so
   this is honest, but it is an affordance that exists only to explain itself — the same pattern the code
   comments flag elsewhere.
6. **One bridge, called "bridges".** `INV-4` is plural and shipped as exactly one direction. The
   deferred full hand-off into Link Analysis is recorded only in a dialog comment and an archived plan
   phase, with no board row.
7. **No `SchemaRelationship` model reaches the client.** `attrCols` is a flat list of extra columns; the
   inference route returns suggestions. Whether the richer relationship model the design named is
   actually wanted is undecided.
8. **A `g6` render kind exists as a placeholder** in the render dispatch, with the real graph and map
   hosts arriving through the async component loader instead. Either the kind is dead and should go, or
   it is a seam and should be documented as one.
9. **The template gallery's own specs use ids that do not ship.** Its tests name `kpi_total`,
   `sum_by_dim` and `kpi_board` alongside the one real id, so a reader grepping for shipped ids finds
   fixtures. Ids are server-owned; the fixtures should say so.
10. 🔴 **`BI-4`, `BI-6` and `BI-7` promise Standard+ for ungated code** (the note under §2). A Basic
    install can mint a public share link, run a headless BI query and render a PDF. Either the three rows
    become `All`, or a seventh EDG-01 cell moves them. A product decision either way.
11. 🔴 **The anonymous public embed has no compliance control.** `compliance/controls-matrix.md` holds no
    occurrence of share, embed, anonymous or public-embed, yet `/public/dashboards/{token}` is an
    unauthenticated surface returning customer data. The mechanism is fail-closed and well built; what is
    missing is the control row an auditor would rely on.
12. **`SP-BI-07`'s processor id is `transform.semantic.metric`** — the banned BI word, in an id, in the very
    `EDITIONS` cell that calls the concept a Measure. An id is a rename with a migration, so this is a
    decision to take deliberately, not a doc fix.
13. **The Studio pane roster is stated four ways and none matches the route table.** `plan-studio.md` and
    `okf/product/index.md` both omit the **Templates** pane that `BI-8` says shipped;
    `USER_GUIDE.md` lists **Menu Builder** as a Studio pane and omits Templates; `EDITIONS` `CP-08` still
    lists Datasets, which left. This commit fixes `plan-studio.md`; the other three need one pass.
14. **`link-analysis.md`'s toolbox inventory has a hole where community detection should be.** It lists
    fifteen algorithms and no community tool, while `INV-1` and the user guide both promise Louvain — and
    Louvain ships. The one doc meant to be the mechanism's source of truth is the one that omits it;
    §3.9 above states it, the concept page still needs the entry.
15. **Ten Studio review sheets in the archive are cited by nothing** (~58 KB under
    `docs/archived-documents/superpower-reviews/`, including the only written-up account of that community
    detection). Two archived plans link them through a `reviews/` relative path that does not resolve.
16. **Three archived plans still label themselves ACTIVE or not-started for work that shipped** — pattern
    packs ("code-complete, NOT VERIFIED"), projection authoring ("build not started") and the widget-tags
    migration ("build NOT started"), the latter two shipped 2026-07-27. The archive is unmaintained by
    policy, but current pages cite these three *as design of record*.
17. **`exchange-sharing.md` is named authoritative for sharing and never mentions editions.** Sharing a
    Widget or a saved view needs `inspecto-exchange` (`SEC-10`, gated 2026-09-07), so a Basic install
    cannot do it — and the authoritative doc does not say so.

## 6. Refused & superseded

| Item | Verdict | Why / source |
|---|---|---|
| An external **marketplace/exchange** for Widgets and Dashboards | **Out of scope by design** | `BI-8` shipped as a curated seed pack; cross-space sharing stays the bundle path |
| The DuckDB `spatial` extension | **Deliberately refused, not deferred work** | no geometry operation is needed, and the hardened sandbox disables extension loading |
| Progressive loading and worker-side binning for geo | **Closed as obsoleted 2026-07-24** | the server fold plus the 5,000-point cap leave nothing to offload; revisit only if the cap is deliberately raised, and then the candidate is the toolbox analyses, not binning |
| Satellite and terrain imagery in the bundle | **Accepted consequence of D2 (2026-07-05)** | the basemap is fully offline; imagery would need the network |
| A **PMTiles** planet extract as the bundled basemap | **Refused at bundling, 2026-07-05** | ~100 MB at z0–6; slimmed Natural Earth GeoJSON at ~2.7 MB shipped instead, and no code references the protocol today |
| SVG export from the map | **Refused** | MapLibre is WebGL/canvas; PNG, GeoJSON and CSV cover it |
| Routing engines (turn-by-turn, isochrones, road network) | **COULD/WON'T** | cannot ship offline cheaply; great-circle covers the investigation cases |
| Cross-dataset joins or blending inside a Widget | **Not planned in either milestone** | a Widget binds exactly one Dataset |
| Porting the bespoke G6 gallery demos (Fishbone, Arc, SubGraph, cluster-sort) | **Refused 2026-07-04 (owner)** | expose G6's real layout types instead |
| A `link-analysis-view` ConfigSpec | **Refused 2026-07-26** | buys validation, not authoring; the defensive UI mapper is already the boundary |
| Guessing a projection mapping when scoring is ambiguous | **⛔ Refused** | an arbitrary column pair "produces a graph that looks authored and is wrong" — refuse instead |
| Setting `entityType` on a drafted single mapping | **Refused** | it would break node-id byte-identity with every existing saved view and export |
| A reserved `_`-prefixed system Space to own pattern packs (D16) | **Overturned by the operator 2026-07-26** | it passes discovery then dies at id validation, logging a spurious WARN every boot; per-Space forking was accepted |
| Re-keying notes by component type+id (the narrow D10 option) | **Refused 2026-07-25** | it "buys the same feature and guarantees a third caller becomes a third special case" |
| Two tag systems on one Widget card (D7 options a and b) | **Refused 2026-07-26; the operator chose (c)** | "two tag systems on one card is the split-brain phase 2 existed to end" |
| Geocoding in the geo MVP | **Demoted 2026-07-05 (D4)** | lat/lon columns fit the core cases; it returns as a pluggable seam with an offline lookup table |
| A client-side fold for time grain | **⛔ Must not be re-added** | a fold cannot bucket rows the server already aggregated |
| Defaulting a Dataset's `sourceName` | **⛔ Refused 2026-08-14** | the old `?? 'data'` named a nonexistent key and made a source-less Dataset read empty everywhere |
| A generic `storeOptionLoader` for schema forms | **Deliberately not built** | one field names a store; build it when a second genuinely does |
| Deleting the retained fold functions | **⛔ Refused (`MOCK-DEAD-COMPUTE-1`, 2026-08-31)** | they are the reference oracles the live paths are asserted against |
| Per-rule filters on a measure alert | **Refused in v1, enforced in the constructor** | `when`/`window` are rejected outright on a measure rule |
| Attachments on emailed report artifacts | **Channel limitation, stated** | SMTP carries the path; `INC` owns the channel |
| A general-purpose PDF export | **Refused 2026-07-20** | no PDF library on the classpath and an offline build, so PDF is a PNG snapshot wrapped in a minimal container |
| Free SQL text on the embed and BI wire | **Refused by construction** | only validated `{agg, field}` pairs cross; a view-bound or expression Widget degrades to "not embeddable" |
| Distinguishable failures on a share token | **Refused** | tampered, expired, malformed and unknown all return the same 404 |
| Counting absent-module stubs in `hasRoute` | **Refused 2026-09-07, learned the hard way** | it reported `geoLink: true` on a Basic build |
| `/studio/datasets` as a Studio pane | **Superseded (Phase B.2)** | a Dataset is a Catalog asset; the path survives as a redirect |
| The offline mock arm under every Studio result path | **Superseded by the mock backend's deletion** | a backend failure now surfaces |
| Distributed graph databases, streaming graph analytics, live co-editing, 3D/VR/AR, GPU compute, federation, blockchain, OSINT/dark-web ingestion, media extraction, forensics, cyber attack graphs, interactive graphs beyond 100M nodes | **Won't, per the owner's own list** | archived `link-analysis-studio-plan.md` |
| **`INV-3` as a Studio requirement** | **Superseded by this spec** | a Case is an operational object; `INC` owns the chain |

## 7. As-built pointers

| Concern | Code | Docs |
|---|---|---|
| Plugin seam, registry, render dispatch | `inspecto-ui/src/app/inspecto/viz/viz-types.ts`, `viz-registry.ts`, `viz-components.ts`, `viz-render.component.ts`, `plugins/index.ts` (+ `standard.plugins.ts`, `table.plugin.ts`, `kpi.plugin.ts`, `bubble.plugin.ts`, `gauge.plugin.ts`, `scatter.plugin.ts`, `funnel.plugin.ts`, `view.plugins.ts`) | [`plan-studio.md`](user-guide-studio.md) |
| Spec, grain, result and rows seams | `inspecto-ui/src/app/inspecto/viz/query-spec.ts`, `time-grain.ts`, `result-set.ts`, `show-me.ts`, `dataset-result.service.ts`, `dataset-rows.service.ts`; `inspecto-ui/src/app/inspecto/api/bi-query.service.ts` | [`queries.md`](../../module/processor/spec-queries.md); `DAT` §3 |
| Headless BI + templates | `inspecto/src/main/java/com/gamma/control/BiRoutes.java`, `BiTemplates.java`; `platform/inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java` | `EDITIONS.md` `CP-08` |
| Share tokens + embed | `inspecto/src/main/java/com/gamma/control/ShareTokens.java`, `ShareRoutes.java`; `inspecto-ui/src/app/modules/admin/share/share-viewer.component.ts`, `inspecto-ui/src/app/inspecto/api/share.service.ts` | `SEC` §3 (public paths) |
| Studio panes | `inspecto-ui/src/app/modules/admin/studio/studio.routes.ts`; `queries/`, `widgets/`, `dashboards/`, `templates/`, `link-analysis/`, `geo-map/` | [`plan-studio.md`](user-guide-studio.md) |
| Dashboards | `inspecto-ui/src/app/modules/admin/studio/dashboards/dashboard-types.ts`, `dashboard-editor.component.ts`, `dashboard-filter-bar.component.ts`, `dashboard-drill-drawer.component.ts` | [`dashboard.md`](user-guide-dashboard.md) |
| KPIs, Reports, delivery | `inspecto-ui/src/app/modules/admin/kpi-reports/kpi-reports.component.ts`; `platform/inspecto-engine/src/main/java/com/gamma/job/ReportJob.java`, `TablePngRenderer.java`, `PdfRenderer.java` | [`kpi-reports.md`](../../module/analytics/user-guide-kpi-reports.md) |
| Measure alerting | `platform/inspecto-engine/src/main/java/com/gamma/alert/AlertRule.java`, `AlertService.java`; `platform/inspecto-engine/src/main/java/com/gamma/query/DatasetMeasureProbe.java`; `spaces/demo/config/registry/alert-rules/orders_low_volume.toon` | `INC` §3 (the Alert object) |
| Link Analysis | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java`; `inspecto-ui/projects/link-analysis/src/api/inv.service.ts`; `inspecto-ui/projects/link-analysis/src/graph/graph-analysis.ts`, `graph-source.ts`, `graph-export.ts`, `graph-history.ts` (pure libs); `inspecto-ui/src/app/inspecto/graph/graph-view.component.ts` (**the host component + `GRAPH_LAYOUTS`**); `inspecto-ui/projects/link-analysis/src/link-analysis/entity-projection.ts`, `graph-sources.ts`, `pattern-packs.ts`, `link-analysis-toolbox.component.ts`, `link-view-widget.component.ts` | [`link-analysis.md`](../../module/la-core/architecture-records.md) |
| Geo Map Analysis | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java`; `inspecto-ui/projects/link-analysis/src/api/geo.service.ts`; `inspecto-ui/projects/link-analysis/src/geo-map/geo-projection.ts`, `geo-map.component.ts`, `geo-view-widget.component.ts`, `colocation-graph.dialog.ts` | [`geo-map.md`](../../module/geo-link/user-guide-geo-map.md) |
| Edition gate | `inspecto/src/main/java/com/gamma/control/AbsentModuleRoutes.java`, `ApiContext.java`, `BootstrapRoutes.java`; `la/inspecto-la-api/src/main/resources/META-INF/services/com.gamma.spi.http.RouteModule`; `inspecto-ui/src/app/inspecto/auth/session.service.ts` | `EDITIONS.md` `CP-09`; `PKG` |
| Saved-view + widget kinds | `platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentStore.java` (`WRITABLE_TYPES`); `spaces/demo/config/registry/` | `MET` §3 |
| Cases (**`INC`'s**) | `platform/inspecto-workflow/src/main/java/com/gamma/workflow/ObjectType.java`; `features/inspecto-ops/src/main/java/com/gamma/ops/ObjectService.java`; `inspecto-ui/src/app/modules/admin/objects/cases.routes.ts` | [`incidents/incidents.md`](../../addon/incidents/plan-incidents.md) |

**Gap rows** (a pointer that should exist and does not): 🔴 **this area has effectively no backend
tier.** The consolidation plan measured it — *Widget Builder* and *Dashboard Builder* appear **zero times**
under `okf/backend/`, `/bi/query` gets two lines, and *Entity Projection*, *GeoQuery* and *MapLibre* appear
zero times — so `okf/product/layer/product/user-guide-studio.md` has been the de-facto backend spec. Five route families
(`/bi/query`, `/bi/templates`, the share routes, `GeoRoutes`, `InvRoutes`) have **no concept page at all**,
which is why §7 points at code for them and why this spec is the first place they are written down
together. Also missing: a Feature page for the Viz Library / Widget Builder and for the Template Gallery,
and a page for the shared G6 host.

**Archive cited as authority** (history tier, never maintained): `report-builder-design.md` (the locked
2026-06-28 design and the Visualization-Type/`VizPlugin` vocabulary), `widget-library-spec.md` (the
`chart → widget` rename), `studio-implementation-plan.md`, `studio-bi-improvements-plan.md` (⚠ its "8 viz
plugins" is a 2026-07 snapshot, not the count), `link-analysis-studio-plan.md` (the owner's 2026-07-04
scope and the won't-do list), `link-analysis-and-graphsource.md`, `link-analysis-toolboxes-plan.md`,
`link-analysis-pattern-packs-plan.md`, `link-analysis-projection-authoring-plan.md`,
`geo-map-analysis-plan.md` (D1–D4 and the deferred hand-off), `geo-map-case-studies.md`,
`widget-tags-assignment-migration-plan.md`, `agt5-p1-investigation-plan.md`. Cite them for *why*; cite
this file and the concept pages for *what is*.

## 8. Verification

* **Pointer check** — a capability-pointer check over this file (`tools/check-doc-citations.mjs` — **committed 2026-09-09**, wired into `ci.yml` and `.githooks/pre-push`) indexes the tracked and
  untracked tree and checks every backticked repo path, Java class and test name behind a control probe
  that must pass first. **One MISSING hit is deliberate:** `inspecto-ui/src/app/inspecto/mock/sample-sources.ts` no longer exists,
  and §3.3 and §5.2 quote it precisely *because* of that — it is the dead path `plan-studio.md`
  was sending readers to. Do not "fix" it by deleting the citation.
* **Backend tests** (14 classes): `ControlApiBiQueryTest`, `ControlApiBiTemplatesTest`,
  `ControlApiShareTest`, `ControlApiComponentSharesTest`, `ControlApiAlertRuleWriteTest`,
  `NoGeoLinkShipsInThePersonalBuildTest` (core); `ControlApiGeoProjectionTest`,
  `ControlApiInvProjectionTest` (`inspecto-geo-link`); `MeasureCompilerTest`,
  `MeasureCompilerGrainExecutionTest`, `AlertRuleTest`, `AlertServiceTest`, `ReportJobDeliveryTest`
  (`inspecto-engine`); `AlertServicePersistenceTest` (`inspecto-ops`).
* **UI tests** — 62 vitest specs across the viz seam, the Studio panes, KPI & Reports and the share
  viewer. The load-bearing ones: `viz-registry.spec.ts` and `view.plugins.spec.ts` (the plugin seam),
  `entity-projection.spec.ts` and `graph-sources.spec.ts` (backend-first projection and `attrCols`, and
  the reference-fold agreement `MOCK-DEAD-COMPUTE-1` exists to keep), `geo-projection.spec.ts` and
  `geo-case-studies.spec.ts` (the geo fold and the point cap), `pattern-packs.spec.ts` (the six packs),
  `share-viewer.component.spec.ts` (embed degradation), `dataset-result.service.spec.ts` and
  `dataset-rows.service.spec.ts` (the two seams).
* **Guards** — `node tools/check-vocabulary.mjs` (this area is where the Measure and Visualization
  Type/Widget distinctions are most likely to slip), `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs`, all from the repo root.
* **Falsify, don't read** — three claims worth re-probing on a running server: `POST /bi/query` with a
  free-text expression must be a 422, never an executed string; a tampered share token and an expired
  one must return the identical 404 body; and on a Basic bundle `POST /inv/projection` must be a 503
  naming the module while `/bootstrap` reports `geoLink: false`.
