<!-- ARCHIVED 2026-10-03 — working artifact of D-5 step 1, retired with its design (la-separation-d5-design.md). Durable facts: docs/okf/frontend/features/link-analysis.md §SPA separation record. Kept for provenance; never maintained. -->

# LA separation — D-5 step 1: edge classification (core / library / LA-only)

Companion of [`la-separation-d5-design.md`](la-separation-d5-design.md) §2 (inventory) and §5 step 1. The operator accepted all seven
D-5 recommendations on 2026-10-02, so the classes below follow them: shared API clients stay in **core** behind a stable alias, the
graph canvas / `catalog-graph` / `unique-name` / `entity-key` stay in **core**, OIDC client code moves to **core**.

**Classes.** **CORE** = stays in `app/inspecto/**` (or the host) and the library imports it through the `@inspecto/core` alias.
**LIBRARY** = moves into `projects/link-analysis` (step 3). **LA-ONLY** = a LIBRARY file that has no host user at all.
**HOST** = host feature code that is the Inspecto side of a `la-host` token seam; it never moves and never enters the library.

## 1. Closure, re-run at the current tip (`dcb0c1ecd`) and after step 1

Method: a node script over `inspecto-ui/src/{app,@gamma,environments}/**/*.ts` (scratchpad, not committed): relative imports, the
`tsconfig` `paths` (`app/*`, `environments/*`, `@gamma`), static and dynamic `import()`, `export … from`; specs excluded. "Own" = the
design's set (`link-analysis/**`, `geo-map/**`, `inspecto/{graph,geo,investigation,la-host}/**`, `la-host.providers.ts`).

| Quantity | Design (2026-10-02) | Re-run at `dcb0c1ecd` | After step 1 |
|---|---|---|---|
| Own non-spec files | 76 (18,300 lines) | **76** (18,332 lines) | 78 (18,501 lines) — see below |
| Own spec files | 72 | 71 | 71 |
| Direct outside files | 46 | **46** | 45 |
| Transitive outside files | 225 (29,336 lines) | **225** (29,336 lines) | 224 (29,175 lines) |
| Inbound edges host → own | 30 non-spec files | 40 edges from **30** files | 39 edges from 30 files |
| Core (`app/inspecto/**`, non-own) files importing own | 4 named | **4 files / 6 edges** (`inv.service` ×2, `link-analysis-settings.service` ×2, `component-graph`, plus `rule/rule-save.dialog` → `unique-name`) | **2 files / 2 edges** (`inv.service` → `entity-key`, `rule-save.dialog` → `unique-name`: both Decision-2a core files) |

Confirmed: 76 / 46 / 225. Corrections: the design says 72 own specs, the script counts 71 (not chased; specs are not part of the closure). The own count grows by two in step 1 because step 1 **adds** two own files: the moved
`link-analysis-settings.service.ts` (§3) and the new `graph/branching-stage.ts`.

## 2. The 45 direct outside files, by folder

Importer column: the own file(s) that cause the edge (`la-host.providers` = `modules/admin/studio/la-host.providers.ts`).

| Folder / file | Class | Reason | Own importers |
|---|---|---|---|
| `@gamma/services/config` | CORE | the template's config service; `provideGamma` stays in core | `geo/map-view`, `graph/graph-view` |
| `inspecto/api/index.ts` (barrel) | CORE | the stable `@inspecto/api` alias target (Decision 1a); ⚠ a closure through it pulls all 72 clients — measure the injected set (§3) | 17+ own files |
| `inspecto/api/api-base.ts` | CORE | `apiUrl`, envelope base (step 2 removes its `environments/environment` import) | `link-analysis-snapshots.service` |
| `inspecto/api/objects.service.ts` | HOST | Cases edge: reached only by `la-host.providers`, behind `LA_CASES` | `la-host.providers` |
| `inspecto/component-model/index.ts` | CORE | component catalogue model, 28 host `ComponentsService` users | `la-host`, 3 LA files |
| `inspecto/components/*` (15 files: alert, chip, component-history dialog, dialog-resize, empty-state, entity-option-loaders, offer-share dialog, option-picker, page-header, risk-score-panel, schema-form, skeleton, split, stat-tile, status-badge) | CORE | the shared design system | LA/Geo components |
| `inspecto/confirm.service.ts`, `inspecto/dialog-dirty-guard.ts` | CORE | shared dialog helpers (88 / many host users) | LA dialogs |
| `inspecto/data-table/{data-table.component,index}` | CORE | shared grid | LA, geo-map |
| `inspecto/format/index.ts` | CORE | shared formatters | `link-analysis-comments.dialog` |
| `inspecto/query/{query-columns,query-condition-group.component,query-eval,query-types}` | CORE | shared condition-group model; the *core* graph files `graph-source` already import it | `graph/graph-filter`, `graph-snapshot`, `graph-source`, 3 LA files |
| `inspecto/theme/{chart-tokens,map-tokens}` | CORE | design tokens (the design-system guard's reason to exist) | graph, geo, LA |
| `inspecto/viz/{index,dataset-rows.service,plugins/view.plugins}` | CORE | viz-kind registry + the dataset-rows client the library re-uses (`DatasetRowsService`, 11 host users) | `geo-map.viz`, `link-analysis.viz`, LA/Geo, `la-host` |
| `inspecto/ai-assist/{ai-assist,ai-explain}.component` | HOST | AI-assist seam implementation, behind `LA_AI_ASSIST` | `la-host.providers` |
| `inspecto/tags/tag-assignment.dialog` | HOST | Tags seam, behind `LA_TAGS` | `la-host.providers` |
| `inspecto/transfer/index.ts` | HOST | import/export seam, behind `LA_TRANSFER` | `la-host.providers` |
| `modules/admin/catalog/{components-data-provider,registry.component}` | HOST | catalog seam (`LA_CATALOG`) | `la-host.providers` |
| `modules/admin/pipelines/pipeline-graph.ts` | HOST | pipeline-graph seam (`LA_PIPELINE_GRAPH`) | `la-host.providers` |
| `modules/admin/studio/dashboards/dashboard-header.component` | HOST | `LA_DASHBOARD_HEADER` | `la-host.providers` |
| `modules/admin/studio/datasets/datasets.service` | HOST | `LA_DATASETS` | `la-host.providers` |
| `modules/admin/studio/widgets/{widget-types,widgets.service}` | HOST | `LA_WIDGETS` | `la-host.providers` |

HOST rows are the 11 files the design §2 already marks as the Inspecto side of the token seam: `la-host.providers.ts` itself stays in
the host (design §5 step 3). Everything else is CORE; **no direct outside edge is LIBRARY** — the library owns its own files, and
the shared surface it needs is exactly the CORE rows above. That is what makes `@inspecto/core` (a path alias, Decision 3a) sufficient.

## 3. Injected API clients (non-spec `inject(X)` in own files, re-counted)

| Client | LA inject() | Host non-spec users | Class |
|---|---|---|---|
| `InvService` (+ `inv-identity`) | 16 | 0 | LA-ONLY |
| `GraphRunsService` | 2 | 0 | LA-ONLY |
| `GeoService` | 2 | 0 | LA-ONLY |
| `NotesService` | 1 | 0 | LA-ONLY |
| `LinkAnalysisService`, `LinkAnalysisSnapshotsService`, `GraphSourcesService`, `GeoSourcesService`, `PivotService` | 2 / 4 / 2 / 2 / 3 | 0 | already own files (LIBRARY) |
| `LinkAnalysisSettingsService` | 3 | 1 (the settings page) | LIBRARY — **moved into `link-analysis/` by step 1 (§4)**; the settings page keeps one host → library import |
| `GeoSettingsService` | 2 | 1 (the settings page) | LIBRARY (still in `api/`; same shape, move in step 3) |
| `SpacesService` | 2 | 15 | CORE |
| `PipelinesService` | 2 | 11 | CORE |
| `ComponentsService` | 5 | 27 | CORE |
| `LensService` | 3 | 58 | CORE |
| `ExchangeService`, `CatalogService` | 1 / 1 | 5 / 5 | CORE |
| `DatasetRowsService` | 4 | 11 | CORE |
| `InspectoConfirmService` | 8 | 88 | CORE |

## 4. The four inbound core → LA edges and how step 1 removed each

| Edge | What the core file needed | Removal |
|---|---|---|
| `api/inv.service` → `graph/branching-pattern-engine` | the `BranchStage` type (and its `LegThreshold`) | both interfaces moved verbatim to the new `graph/branching-stage.ts`; the engine imports and re-exports them, so the `graph` barrel and every importer see the same names. `inv.service` now imports `../graph/branching-stage`. Its other import, `graph/entity-key`, is a Decision-2a CORE file |
| `api/link-analysis-settings.service` → `graph` (barrel) and `link-analysis/entity-projection` | the service **is** LA behaviour: it pushes the per-space caps into `configureGraphLimits` / `configureProjectionLimits` | `git mv` to `link-analysis/link-analysis-settings.service.ts` (one LA-owned file, 5 importers repointed; its barrel imports `SpacesService` / `apiUrl` from `app/inspecto/api` as every LA file does). Behaviour is byte-identical; the only host importer is the settings page |
| `component-model/component-graph` → `graph` (barrel) | the `G6*` data types | imports `graph/graph-types` directly (the barrel dragged in all algorithms) |

Two further barrel imports **inside the core slice of `graph/`** were repointed in the same change, because the closure showed them as
CORE → LIBRARY (design §2 did not see them: both ends are "own"): `graph/catalog-graph` imported the `G6*` types through the barrel
(now `./graph-types`) and `graph/graph-view.component` imported `toSvg` through the barrel (now `./graph-export`).

## 5. The core slice of `inspecto/graph` and `investigation` (Decision 2a, as the lint allow-list)

| File | Class | Note |
|---|---|---|
| `graph/graph-types` | CORE | `G6Node/Edge/GraphData`; the only import is `NodeKind` from `api` |
| `graph/graph-source` | CORE | imported by `entity-key` (type) and by `graph-types` consumers; imports `query/query-types` |
| `graph/entity-key` | CORE | Decision 2a names it; `api/inv.service` and the Java `check-split-identity-fixture` mirror it |
| `graph/branching-stage` | CORE | new in step 1 (§4) |
| `graph/catalog-graph`, `graph/graph-view.component` | CORE | the canvas: 14 host files |
| `graph/graph-export` | CORE **by dependency** | the design lists "export" with the library algorithms, but the canvas calls `toSvg` from it and it has no import besides `graph-types` and `theme` — it cannot stay behind. ⚠ design correction |
| `investigation/unique-name` | CORE | 14 host dialogs |
| every other `graph/*.ts`, `geo/**`, `investigation/**`, `la-host/**` | LIBRARY | |

`eslint.config.mjs` encodes exactly this allow-list (`coreGraphFiles`), so a **new** file under `graph/` is library by default; the
Java parity fixtures (`graph/*.fixture.json`) are not moved by anything in D-5 steps 1–2.

## 6. What the design got wrong or missed

1. Core files reach the library half through the **`graph` barrel** (`export *` of everything). A core import of the barrel is the
   cycle even when the symbol it wants is core-safe; the lint therefore bans the bare `graph` specifier and permits only named core files.
2. `graph-export` belongs to the canvas (§5). Decision 2 listed it with the library algorithms.
3. Two CORE → LIBRARY edges exist *inside* `graph/` (§4 last paragraph) that "46 direct outside edges" cannot show.
4. `LinkAnalysisSettingsService`: moving it (rather than inverting it) is the least invasive removal and is already step 3's end state.

## 7. As built (steps 3 and 4, 2026-10-02)

The classification above held: every CORE row stayed, and the library reaches core only through `@inspecto/core/*`.

| Fact | As built |
|---|---|
| Files moved | 155 `git mv`s (85 non-spec: 72 `.ts`, 6 templates, 7 fixture JSON; 70 specs) into `inspecto-ui/projects/link-analysis/src/{link-analysis,geo-map,graph,geo,investigation,la-host,api}`; plus `public-api.ts` and the two library barrels (`graph/index.ts`, `investigation/index.ts`). The library is 18,455 non-spec lines. `la-host.providers.ts` stays in `modules/admin/studio` |
| `api/` clients that moved | `inv.service` (+ `inv-identity` spec), `graph-runs.service`, `geo.service`, `geo-settings.service`, `notes.service` (the 7th, `link-analysis-settings`, moved in step 1). They left the `api` barrel; library files import them by deep path |
| Core slice kept | `graph-types`, `graph-source`, `entity-key`, `branching-stage`, `catalog-graph`, `graph-view.component`, `graph-export`, `investigation/unique-name`, plus their specs; the core `graph/` and `investigation/` barrels now export only these |
| Barrels split | the old `graph` barrel exported 12 modules: 5 stay (core `graph/index.ts`), 8 form the library `graph/index.ts` (`branching-pattern-engine` re-exports the core `branching-stage`); `investigation` keeps `unique-name` in core |
| Fixtures | six `graph-*-parity.fixture.json` + `branching-parity.fixture.json` moved with the specs that read them; `entity-normaliser-parity.fixture.json` stayed in core (read by `entity-key.spec`) |
| Public surface | `@inspecto/link-analysis` = `public-api.ts` = the `la-host` seam + `registerLinkAnalysisViz` + `registerGeoMapViz` and nothing else; routes, widget components, `MapViewComponent` and the two Settings clients are imported by deep path (`@inspecto/link-analysis/*`) so the lazy chunk boundary survives |

### What the closure could not see (design corrections, continued from §6)

5. **A core file that imports a moved file is invisible until the move.** Two such edges existed: `graph/graph-source` (core) used `MultiNodeMapping` / `MultiEdgeMapping` from `api/inv.service`; both interfaces now live in `graph-source` and `inv.service` re-exports them. And `api/notes.service` imported `ObjectNote` from `api/objects.service`, a path the LA lint restricts as the Cases edge; `ObjectNote` moved to `api/models` (re-exported through the `api` barrel as before).
6. **A library barrel is not free.** The first `public-api.ts` also re-exported the two widget components and `MapViewComponent`; `app.config` imports it statically, esbuild keeps a source module's side effects, and MapLibre (1.2 MB) plus the LA widgets landed in `main` (1.88 to 1.42 MB, and a 1.48 MB lazy chunk). Only the `la-host` seam and the two (async-loader) registrations may be exported; this is pinned by the 2026-10-02 chunk comparison, not by a test (a size guard is a BACKLOG candidate).
7. **`ng test` discovery is rooted at `src/`**: `include: ["../projects/**/*.spec.ts"]`; a bare `projects/**/*.spec.ts` silently matched nothing (395 of 465 files ran, 3,791 of 4,542 tests, all green).
8. **The vocabulary guard's scope named only `inspecto-ui/src/app`**; moved files were silently unscanned until `inspecto-ui/projects` was added.
9. Eight `check-vocabulary` exemptions are keyed by path and moved with their files; a planted `flow` identifier under `projects/` proves the scope.
