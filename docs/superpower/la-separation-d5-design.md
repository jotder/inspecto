<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-02 (D-5 design of la-separation-execution-plan.md). DESIGN ONLY — nothing is built. DECISIONS 1–7 OPEN (Answer
  lines empty for the operator). Retire per the three-tier lifecycle in CLAUDE.md when D-5 ships (or is declined).
-->

# LA separation — D-5 design (SPA separation: `la-app`, `link-analysis` library, LA product flavor)

Option D's phase **D-5** ([`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.8: "`la-app` host +
`projects/la-app` shell + LA product flavor (bundle, boot smoke, licence text)", depends on D-1 and SEP-03…06 — both done).
This file is the design the operator signs before any move. ⛔ Grounding method: a transitive import closure over
`inspecto-ui/src/app/**/*.ts` (relative imports + the `tsconfig.json` `paths` `app/*`, `environments/*`, `@gamma`) computed
2026-10-02 with a script in the session scratchpad (not committed); spec files excluded. Anything not read from a file is marked
**not grounded**. When this and the code disagree, re-ground; never trust the plan.

## 1. Facts — the SPA today

| Fact | Where it was read |
|---|---|
| ONE Angular project, `gamma` (`@angular/build:application`, esbuild), `root: ""`, `sourceRoot: src`; `newProjectRoot: projects` but no `projects/` directory exists; no library project | `inspecto-ui/angular.json` |
| Angular `22.1.1`, CLI `22.1.3`. `ng-packagr` is **not** a dependency: it appears in `package-lock.json` only as an optional peer of `@angular/build` (`^22.0.0`), and there is no `node_modules` in this checkout | `package.json`, `package-lock.json` l.340 |
| `tsconfig.json` has `paths` for `app/*`, `environments/*`, `@gamma`, `@gamma/*` only; no `baseUrl` (TypeScript 6 rejects it); `strict: false` | `inspecto-ui/tsconfig.json` |
| Entry: `src/main.ts` → `bootstrapApplication(AppComponent, appConfig)`; `app.config.ts` registers 4 interceptors (`v1Interceptor`, `spaceInterceptor`, `authInterceptor`, `errorInterceptor`), `provideGamma`, `provideIcons`, Toastr, Luxon date adapter, `provideAppInitializer(SessionService.init)` (reads `GET /bootstrap`), the two viz registrations, and `...provideLaHostServices()` | `src/main.ts`, `src/app/app.config.ts` |
| LA and Geo are lazy routes under Studio: `studio.routes.ts` `loadChildren` → `link-analysis/link-analysis.routes`, `geo-map/geo-map.routes` | `studio/studio.routes.ts` l.15–16 |
| The shell (layout, nav, icons, user) is host code: `src/app/layout/**`, `src/app/core/{icons,navigation,user}`; the sign-in / OIDC callback routes are `modules/admin/session/{sign-in,callback}.component.ts`, the guard/interceptor/PKCE/session are `inspecto/api/{auth.guard,auth.interceptor,pkce,session.service}.ts` | `app.routes.ts`, directory listings |
| LA/Geo code today: `modules/admin/studio/link-analysis` (80 entries incl. specs) + `…/geo-map` (19) + `inspecto/graph`, `inspecto/geo`, `inspecto/investigation` + `inspecto/la-host` + `modules/admin/studio/la-host.providers.ts`. **76 non-spec files, 18,300 lines; 72 spec files** | closure script |
| Ten `la-host` tokens exist: `LA_DATASETS`, `LA_WIDGETS`, `LA_CATALOG`, `LA_PIPELINE_GRAPH`, `LA_DASHBOARD_HEADER`, `LA_TAGS`, `LA_TRANSFER`, `LA_AI_ASSIST`, `LA_CASES` (all throw an error naming themselves when unprovided) and `LA_FEATURES` (safe default: all flags off) | `inspecto/la-host/la-host.ts` l.42–224 |
| Lint guards, scoped to non-spec files under `link-analysis/**` and `geo-map/**`: `no-restricted-imports` (host features, `app/modules/**`, `../**`, `app/inspecto/{tags,transfer,ai-assist}`, `objects.service`, `ObjectsService`, `SessionService`) and a `no-restricted-syntax` selector set (`laDynamicImportSelectors`) for dynamic `import()` incl. computed specifiers; falsified by `inspecto-ui/tools/la-dynamic-import-lint.test.mjs` | `inspecto-ui/eslint.config.mjs` l.20–159 |
| `/bootstrap` features read by the SPA: `exchange`, `geoLink` (true only when both `POST /geo/projection` and `POST /inv/projection` are registered), `events`, `ops`, `authMode` (`none`/`oidc`/`demo`) | `session.service.ts` l.213–230, `BootstrapRoutes.java` l.92–121 |
| The Java bundle serves **one** SPA: `ControlApi` serves `-Dui.dir` as a PUBLIC fallback for any unmatched GET, extensionless paths fall back to `index.html`; `package.ps1` step 1b runs `npm ci` + `npm run build` in `inspecto-ui/`, step 3b finds the first `index.html` under `inspecto-ui/dist` and copies its folder to `<bundle>/ui` | `ControlApi.java` l.163–171, 260; `inspecto/package.ps1` l.223–241, 778–800 |
| Editions are build flavors: `bundle-modules.mjs` holds one module table (`from: all/professional/enterprise`; Preview takes everything), and a guard keeps it equal to `package.ps1`'s `$modules`. The LA backend modules `inspecto-entity-list`, `inspecto-la-graph`, `inspecto-la-core`, `inspecto-la-api`, `inspecto-geo-link` are `from: professional` today | `tools/bundle-modules.mjs` l.58–62, 95–110; `package.ps1` l.306 |
| CI: `.github/workflows/ui.yml` (paths `inspecto-ui/**`) runs design-system guard, prettier check, ESLint, typecheck (3 tsconfigs), unit tests with coverage, coverage floors, production build; `release.yml` builds in `inspecto-ui` | `ui.yml`, `release.yml` l.113 |
| The design-system guard scans `src/app` as a whole (`ROOTS = ['src/app']`); the `ds-allow` comment is its per-line exception | `inspecto-ui/tools/check-design-tokens.mjs` l.11, 34 |

## 2. Inventory — what the LA/Geo code reaches outside its own folders

"Own" = the 76 files above (LA, Geo, `inspecto/graph|geo|investigation|la-host`, `la-host.providers.ts`). Specs excluded.

**Direct** (one import hop out of "own"): **46 files** — `inspecto/components` 15, `inspecto/api` 4 (barrel `index.ts` imported 40 times, `link-analysis-settings.service` 4, `objects.service` 1, `api-base` 1), `inspecto/query` 4, `modules/admin/studio` 4 (dashboard-header, datasets, widgets ×2 — the host edges the tokens replace), `inspecto/viz` 3, `theme` 2, `data-table` 2, `ai-assist` 2, `modules/admin/catalog` 2, `@gamma/services/config` 1, `component-model` 1, `tags` 1, `transfer` 1, `modules/admin/pipelines` 1, `format` 1, `confirm.service` 1, `dialog-dirty-guard` 1.

⚠ Of the 46, **11** are host-feature files (`modules/admin/**` 7, `tags` 1, `transfer` 1, `ai-assist` 2 are the `la-host.providers.ts` implementation and the token default seams, not LA feature code). They are the Inspecto side of the token seam and stay in the host.

**Transitive: 225 files, 29,336 lines.** By folder: `inspecto/api` 72, `viz` 31, `components` 18, `component-model` 14, `data-table` 12, `modules/admin/studio` 12, `query` 10, `transfer` 9, `@gamma/services` 5, `reconciliation` 4, `tree-table` 4, `rule` 4, `ai-assist` 4, `format` 3, `modules/admin/catalog` 3, `requirement` 3, 11 further folders of 1–2 files, and `environments/environment.ts` 1. 🔴 The 72 `api` files are the **whole** `api/` directory (72 non-spec files): LA imports the barrel `inspecto/api/index.ts` 40 times, so a closure through the barrel pulls every client. The honest requirement is the set LA actually injects (next paragraph), not the barrel.

**API clients LA/Geo actually inject** (`inject(X)` in non-spec files): `InvService` 16, `ComponentsService` 5, `LinkAnalysisSettingsService` 3, `LensService` 3, `PipelinesService` 2, `GraphRunsService` 2, `GeoSettingsService` 2, `GeoService` 2, `SpacesService` 1, `NotesService` 1, `ExchangeService` 1, `CatalogService` 1 (plus the LA-owned `LinkAnalysisService`, `LinkAnalysisSnapshotsService`, `GraphSourcesService`, `GeoSourcesService`, `PivotService`, `DatasetRowsService`, `InspectoConfirmService`). Non-spec host importers of each (outside LA/Geo and outside `api/`):

| Client | Host files using it | Class |
|---|---|---|
| `InvService`, `GraphRunsService`, `GeoService`, `NotesService` | **0** | LA-only — moves with the library |
| `LinkAnalysisSettingsService`, `GeoSettingsService` | 1 each (a settings page) | LA-owned, one host consumer |
| `ExchangeService`, `CatalogService` | 5 each | shared |
| `PipelinesService` 11 · `SpacesService` 15 · `ComponentsService` 28 · `LensService` 58 | — | shared platform |
| `api-base.ts` | 61 (LA: 1) | shared base (it imports `environments/environment` by relative path) |

LA-only clients in total: 7 files, **1,678 lines** (`inv`, `graph-runs`, `link-analysis-settings`, `geo`, `geo-settings` + `notes`, `inv-identity`), with their specs.

**The `api/` directory is not a leaf.** Its own closure leaves the directory: **16 direct / 94 transitive** outside files — `component-model` (3 direct), `graph` (3: `branching-pattern-engine`, `entity-key`, `index`), `query` (2), `reconciliation` (2), `a2ui`, `decision`, `signal`, `components/line-diff`, **`environments/environment` (5 files)**, and `modules/admin/studio/link-analysis/entity-projection.ts` (imported by `link-analysis-settings.service.ts`). The `api/` directory holds 102 files (72 non-spec + 30 spec); 189 host non-spec files import its barrel.

**Inbound** (host files importing "own"): **30 non-spec files**. Three kinds matter: (1) `app.config.ts`, `studio.routes.ts`, `menu-artifact.component.ts`, `widget-types.ts` — the registration/route seams; (2) **the graph canvas and `catalog-graph` are host-shared** — Catalog, Pipelines (editor graph, layout, template), Object detail, Icon settings and the Registry import `inspecto/graph/{graph-view.component,catalog-graph,index}` (14 files); (3) **`inspecto/investigation/unique-name.ts`** is imported by 14 unrelated host dialogs/editors, and `inspecto/component-model/component-graph.ts`, `inspecto/api/inv.service.ts` (→ `graph/branching-pattern-engine`, `entity-key`) and `inspecto/api/link-analysis-settings.service.ts` (→ `graph/index`, `link-analysis/entity-projection`) import "own" back. 🔴 So the feasibility plan's tidy arrow `core ← library ← shells` is **not** true of the code: moving `inspecto/graph` and `investigation` into the library puts host-core files (`api/*`, `component-model`) *above* it in the dependency order — a cycle (see Decision 2).

Shared UI/support the LA product also needs: design-system components (`inspecto/components`, 18 transitive), `viz`, `data-table`, `query`, `theme` tokens, `format`, `@gamma` (Fuse-derived layout/config/services; `provideGamma`), Material + Tailwind + `styles/*` + `maplibre-gl` assets (`angular.json` build options), `environments/*`, and the widget-embed contract (`widget.kind.ts` dynamic imports of `link-view-widget.component`, `geo-view-widget.component`; viz kinds `geo-map-view`, `link-analysis-view`, `working-set-view`).

Hand-kept mirrors that name SPA paths and break on a move: **14 Java files** hard-code `"inspecto-ui"` paths — 9 read the parity fixtures from `inspecto-ui/src/app/inspecto/graph/*.fixture.json` (`GraphEngineParityTest` `DIR = Path.of("..","inspecto-ui","src","app","inspecto","graph")`, six `inspecto-la-graph/**Parity*Test`, `ControlApiGraphRunParityTest`, `ControlApiInvPatternTest`), plus `EntityTypesTest`, `ImportLoaderInventoryTest`, `RecordTransformContractTest`, `RoutesCatalogLoader`, `RepoPaths`; `tools/check-split-identity-fixture.mjs` (mirrors `entity-key.ts`); `tools/check-vocabulary.mjs` has ~8 exemptions keyed by SPA path (`…/link-analysis/link-analysis-toolbox.component.ts::flow-identifier` etc.); `BranchingPatternEngine.java` and six `inspecto-la-graph` classes cite SPA paths in Javadoc, and `tools/check-doc-citations.mjs` resolves `inspecto-ui/src/app` as a base.

## 3. What a standalone `projects/la-app` provides vs shares

| Concern | `la-app` must provide itself | Shared with the Inspecto host | Grounding |
|---|---|---|---|
| Shell, layout, nav | its own `AppComponent`, landing page, Investigations list, a nav of 4 entries (LA, Geo, Datasets, Cases — SEP-12) | `@gamma` layout primitives if kept | feasibility §7.5, SEP-12 |
| Routing | own `app.routes.ts`: guest routes `sign-in`, `auth/callback`; `/` → LA; the `link-analysis/**` and `geo-map/**` routes from the library | the library's `link-analysis.routes` / `geo-map.routes` | `studio.routes.ts` |
| Auth + session | `SessionService.init()` (`GET /bootstrap`), `authGuard`, `authInterceptor`, PKCE, sign-in/callback pages — OIDC against an external IAM (D12 signed: "external IAM through the moved OIDC authenticator; demo sign-in for demos only") | the backend `OidcAuthenticator` (already in `inspecto-security`, `inspecto-auth-spi` since D-1) | D12; `app.config.ts`; the SPA OIDC files are 4 files in `inspecto/api` + 2 pages. Whether `la-app` copies or imports them is Decision 5 |
| Capability / lens | the Inspecto *lens* (`business`/`builder`/`ops`) is a host concept used by 58 host files; LA needs `LensService` in 3 places (not grounded: what LA does with it) | decide in Decision 1 | injection count above |
| Interceptors | `v1Interceptor` (envelope unwrap), `spaceInterceptor` (Space scope rewrite), `authInterceptor`, error tracker — the wire contract of the API | the same four | `app.config.ts` |
| Theming/design system | Tailwind/SCSS entry styles, `themes.scss`, icons provider, Toastr | `inspecto/components`, `theme`, `@gamma` | `angular.json` styles array |
| Environment | `environments/environment*.ts` (read by relative path by `api-base.ts` and 4 more `api/` files — a library-boundary blocker already recorded in feasibility §1.5) | — | closure |
| Host tokens | an LA App implementation of the ten tokens: `LA_DATASETS`/`LA_WIDGETS`/`LA_CATALOG`/`LA_PIPELINE_GRAPH`/`LA_DASHBOARD_HEADER`/`LA_TAGS`/`LA_TRANSFER`/`LA_AI_ASSIST` likely minimal or "absent"; `LA_CASES` (own implementation or `mockCases`); `LA_FEATURES` from `/bootstrap` | the Inspecto implementation stays in `la-host.providers.ts` | `la-host.ts`; the throw-on-absent default means `la-app` must provide or accept the error (not grounded: which tokens a minimal LA surface can leave absent without a runtime throw) |
| Boot flags | `/bootstrap` must report `geoLink: true` (both `/geo/projection` and `/inv/projection` routes present — the LA modules ship them) and not require `ops`/`exchange`/`events` | — | `BootstrapRoutes.java` l.105 |

**The library `projects/link-analysis` must export** (everything the shells need and nothing else): the two route files (`link-analysis.routes`, `geo-map.routes`); the viz registration functions (`registerLinkAnalysisViz`, `registerGeoMapViz`) and the widget-embed components (`link-view-widget`, `geo-view-widget`) that the host's `widget.kind.ts` and `menu-artifact.component.ts` import; the `la-host` tokens + `provide…` contract types (`LaDataset`, `LaImportDraft`, `LaAiDraft`, …) and `LaHostSlotComponent`; whatever of `inspecto/graph` the host still imports (canvas, `catalog-graph`) if the library owns it. Not grounded: ng-packagr's secondary entry-point layout and whether a `sideEffects` / lazy-chunk split survives (G6 loads only on first graph use today, per the D-0 closure note in feasibility §4).

## 4. Decisions owed

**Decision 1 — Where do the shared API clients (`app/inspecto/api/*`) live?** Measured: `api/` = 102 files (72 non-spec); LA injects ~11 distinct clients, of which 4 are LA-only (0 host users) and 2 more LA-owned (1 host consumer each); the shared ones have 5–61 host importers; the directory's own closure reaches **16 files directly / 94 transitively outside itself**, including `graph` (3), `environments/environment` (5) and `link-analysis/entity-projection` (1).
(a) **Stay in core; LA imports a stable path alias (`@inspecto/api`)** — a published-internal contract. Files that move: **0** shared; the 7 LA-only clients (1,678 lines) move into the library. Import changes: 40 LA barrel imports repoint to the alias (the alias can keep resolving to today's directory, so the text change is a one-line `paths` entry); host importers (189) unchanged. Cycle risk: low for the alias itself, but the 3 `api → graph` and 1 `api → link-analysis` edges must go (the 4 inbound files above) — they are precisely the LA-only clients, which move out with the library. Test impact: the 30 `api/` specs stay; the 7 LA-only clients' specs move.
(b) **Move into a new shared library `projects/inspecto-api`.** Files that move: **102**, with their specs. Import changes: 189 host files + 40 LA files if the package scope renames the path (0 text change if it keeps `app/inspecto/api`). Cycle risk: **high** — the closure shows the directory is not a leaf (94 transitive outside files): `component-model`, `query`, `reconciliation`, `graph`, `signal`, `decision` and `environments` would all have to come along or be inverted, and the library would then drag 14 `component-model` + 12 `data-table` files. Test impact: the 30 specs and the `vitest-setup` wiring move; `coverageInclude` (`src/app/inspecto/**`) changes.
(c) **LA gets its own thin clients over the same wire contract**, typed from `openapi-v1.json`. Files: the ~11 clients LA uses are re-written (about 1,700 LA-only lines already, plus ~5 shared ones); `api-base`, the 4 interceptors and the envelope/Space-scope behaviour are duplicated (61 host importers of `api-base` show how much behaviour it carries). Cycle risk: none. Test impact: new specs; drift risk is the cost — the repo has a documented history of hand-kept mirrors drifting (`tools/bundle-modules.mjs` header: the SBOM module list drifted from `package.ps1` until a guard was added).
*Recommendation:* **(a), with the LA-only clients moving into the library.** The inventory says the real shared surface is small (≈6 clients + `api-base`/`models`/interceptors), the directory cannot be moved whole without dragging its 94-file closure, and duplicating the wire layer (c) buys nothing while there is one customer. (b) is the right end-state only if a third consumer appears.
**Answer:**

**Decision 2 — Where do the graph canvas (`inspecto/graph`) and `investigation/unique-name.ts` live?** Measured: 14 host files use the canvas / `catalog-graph` (Catalog, Pipelines, Object detail, Icon settings, Registry); 14 host dialogs use `unique-name.ts`; `api/inv.service`, `api/link-analysis-settings.service` and `component-model/component-graph.ts` import the graph back.
(a) The canvas, `catalog-graph` and `unique-name` stay in **core** (alongside `viz`, `components`); the library holds only LA/Geo-specific graph code (engine/algorithms/brush/snapshot/entity-key) and `geo`. Cost: split `inspecto/graph` (canvas+`catalog-graph` vs the LA algorithms) — the Java parity tests (9 files) read the six fixtures from today's `inspecto/graph` directory, so the fixtures stay put or the Java paths change.
(b) Everything under `graph/`, `geo/`, `investigation/` moves into the library (the feasibility §7.5 plan); the host (Catalog, Pipelines, 14 dialogs) then imports the library, and `api/*` + `component-model` must stop importing it (cycle). Cost: the 14 `unique-name` importers and 14 canvas importers repoint; 4 core files lose their graph imports.
*Recommendation:* (a) for `graph-view.component`, `catalog-graph`, `unique-name`, `entity-key`; the rest of `graph/` (algorithms, brush, snapshot, filter, export, history, branching engine, domain profile) with the library. Reason: the closure shows two host-wide users, and a library that the core must import is not a library. The Java fixture path is a `DIR` constant per parity test and one fixture directory; moving only the LA-specific half changes it for the algorithm fixtures (6) — **decide per fixture** (not grounded which `entity-key` tests read which fixture).
**Answer:**

**Decision 3 — Angular library (ng-packagr) or path-aliased workspace libraries?** Facts: `ng-packagr` is not installed and the offline npm cache is not grounded; `@angular/build` lists it as an optional peer. A packaged library needs a buildable `dist` and `rootDir` containment: it cannot import `app/inspecto/**` from outside its root, so every core dependency in §2 (46 direct) must be either a published dependency of the library or injected.
(a) **In-workspace libraries (no packaging step)**: `projects/link-analysis` and `projects/inspecto-ui-core` are plain folders in the workspace, wired by `tsconfig` `paths` (`@inspecto/core`, `@inspecto/link-analysis`), compiled by the app builders (no ng-packagr). Boundaries are enforced by the ESLint rules (carried over, see §6) — what `la-dynamic-import-lint.test.mjs` already proves for dynamic imports.
(b) **Real ng-packagr libraries** with `package.json` + `public-api.ts`, built before the apps.
*Recommendation:* (a). The goal is two shells over one code base with an enforced boundary, not a published npm package; (a) needs no new dependency (offline-safe) and keeps one `npm ci`. Revisit (b) only if LA must ship outside this repo.
**Answer:**

**Decision 4 — Product naming and package scope.** The repo has no npm scope today (`package.json` name `inspecto-ui`, version `21.0.0`, `private: true`; the Angular project is called `gamma`). Candidates for the aliases and project names: `@inspecto/*` (`@inspecto/core`, `@inspecto/link-analysis`) with apps `gamma` (unchanged) and `la-app`; or `@gamma/*` (collides with the existing `@gamma` path alias of the vendored template — `@gamma` and `@gamma/*` already map to `src/@gamma`). Canonical vocabulary: GLOSSARY has no entry for the product; "LA App" / "Link Analysis" are the plan's names (feasibility §7.5). Not grounded: whether GLOSSARY needs a new row for the library and product names (CLAUDE.md says one concept → one word).
*Recommendation:* `@inspecto/core`, `@inspecto/link-analysis`; apps `gamma` (rename deferred — it is not a D-5 job) and `la-app`; add the names to GLOSSARY §13 in the same change that creates the projects.
**Answer:**

**Decision 5 — Who owns sign-in code in `la-app`?** D12 is signed (external IAM through the moved OIDC authenticator; demo sign-in for demos only) — this decision is only about the SPA half: the OIDC client code is `SessionService` (416 lines) + `auth.interceptor` + `auth.guard` + `pkce` + the two session pages; `SessionService.init()` also carries the host module flags.
(a) The OIDC client code moves into **core** and both shells import it (one PKCE implementation). `la-app` provides only its landing page.
(b) `la-app` ships its own copy (a thinner session service: authMode + the LA flags only).
*Recommendation:* (a). Two OIDC clients would be two security reviews; the `/bootstrap` contract is already shared. Not grounded: whether `SessionService` can be split from the lens/Space concepts it also holds (read `session.service.ts` before step 4).
**Answer:**

**Decision 6 — What is "the LA product flavor" in the Java bundle?** Facts: editions are build flavors (`docs/EDITIONS.md`); `bundle-modules.mjs` has the module table and `package.ps1` takes `-Edition`; today the five LA backend modules are `from: professional`, so every Professional bundle already contains LA; Preview bundles everything. There is no LA edition anywhere (`EDITIONS.md` has Personal/Professional/Enterprise + Preview).
(a) **A fifth edition `LA`** (build profile `-Pedition-la`, `package.ps1 -Edition LA`, `bundleModules('LA')`): the LA modules + `inspecto-security` + only what they need; the Inspecto host SPA is replaced by `la-app`. Cost: a new edition row in EDITIONS, a Maven profile (static XML, does not auto-union), the `check-sbom-modules` guard, and a new `ui` choice in `package.ps1`.
(b) **A UI flavor, not an edition**: keep the existing editions; add `package.ps1 -Ui la-app` (default `gamma`) that builds and stages the other SPA. LA backend is already in Professional+.
*Recommendation:* (b) first — it is the smaller step and the operator's D14/D2 answers (one SKU, Geo always with LA) only need the UI to differ; promote to (a) when a customer needs a bundle without the other Professional modules. Not grounded: SEP-10's intended "non-LA optional modules absent" proof requires (a).
**Answer:**

**Decision 7 — Bundle layout for two SPAs.** Facts: `ControlApi` has one `-Dui.dir`; `package.ps1` copies the first `index.html` found under `inspecto-ui/dist` — with two applications under `dist/` that is ambiguous.
(a) One bundle ships exactly one SPA: `ui/` is `gamma` or `la-app` depending on the flavor. `package.ps1` picks the dist folder by name, not by "first `index.html`".
(b) One bundle ships both (`ui/` and `ui-la/`), `ControlApi` gains a second root. Needs a Java change (not D-5's scope as drafted).
*Recommendation:* (a). Not grounded: how `serve.sh` / `serve.bat` / the launcher checks (`tools/check-launchers.mjs` sets `AUTH_OIDC_CLIENT_ID: 'inspecto-ui'`) should name the LA client id; the IAM client for `la-app` is a deployment decision.
**Answer:**

## 5. Ordered steps — each compiles, passes its checks and ships alone

Sizes are relative: S < 1 day, M 1–3, L 3+.

| # | Step | Size | Proof |
|---|---|---|---|
| 1 | **Make the move mechanical:** classify the 46 direct edges and the ~11 injected clients into core / library / LA-only (a table in this file's §2 is the input); remove the 4 inbound edges that point host-core → LA (`api/inv.service` → `graph`, `api/link-analysis-settings.service` → `graph` and `link-analysis/entity-projection`, `component-model/component-graph` → `graph`) | S–M | typecheck (3 tsconfigs) + unit tests; closure script re-run shows 0 core → "own" edges except the route/registration seams |
| 2 | **Introduce `projects/` and the `@inspecto/core` alias** (Decision 3a): no file moves; `tsconfig.json` `paths` gets the aliases; `environments/*` read only through an injected config (kills the 5 relative imports in `api/`) | S | `ng build` byte-for-byte route table unchanged; prettier + ESLint + design-system guard green; the CI `ui.yml` paths widened to the new folders |
| 3 | **Move the LA-only API clients and the 76 own files into `projects/link-analysis`** (Decision 1a, 2a); `la-host` goes with them; `la-host.providers.ts` stays in the host | M–L | all 72 LA specs + the 30 `api/` specs; **the carried-over lint rules fire on the new paths** — re-run `la-dynamic-import-lint.test.mjs` and a mutation (re-add a forbidden import) goes red; `ng build` bundle sizes (LA chunk stays lazy) |
| 4 | **Fix the hand-kept mirrors**: the 14 Java files' fixture paths, `tools/check-split-identity-fixture.mjs`, `check-vocabulary.mjs` path-keyed exemptions, doc citations to `inspecto-ui/src/app/inspecto/graph/**` | S–M | `mvn -o test -pl inspecto-la-graph,inspecto-la-core,inspecto-geo-link,inspecto-entity-store -Dtest=…Parity…` (commas, never `+`); `node tools/check-vocabulary.mjs`, `check-doc-citations.mjs`; step 3 is not shippable without this step if the fixtures moved |
| 5 | **Move OIDC client code to core** (Decision 5a) | M | the existing 6 session/auth specs; a real sign-in against the local WSO2 per the existing runbook (not re-grounded here) |
| 6 | **Add the `la-app` project** (second `projects` entry in `angular.json`, own `main.ts`, `app.config.ts`, `app.routes.ts`, the LA App provider set for the ten tokens) | M | `ng build la-app` + `ng test`; boots against a Preview bundle: `/bootstrap` `geoLink: true`, LA and Geo routes render, an absent token reports itself (not a console error) |
| 7 | **Package the flavor** (Decision 6/7): `package.ps1` chooses the dist folder by name and gains the UI choice; `bundle-modules.mjs` / `-Pedition-la` only if 6a; the SBOM guard and `check-launchers.mjs` updated | M | `package.ps1` bundle boots; `/bootstrap` reports the LA features; boot smoke; `check-sbom-modules` green; licence text (feasibility §7.8 "licence text" — not grounded where it lives) |

## 6. Traps (read before step 1)

- **The closure through the `api` barrel lies.** 40 LA barrel imports close over all 72 clients; measure the injected set (§2), never the barrel.
- **Lint scope is a silent exemption.** Both ESLint blocks and the dynamic-import selectors are keyed to `src/app/modules/admin/studio/{link-analysis,geo-map}/**`; after the move the `files:` globs must point at `projects/link-analysis/**`, or the rules silently stop applying (a mutation must go red again). `tools/la-dynamic-import-lint.test.mjs` pins the regexes — keep the two in step.
- **`ds-allow` and the design-system guard scope** is `ROOTS = ['src/app']`: a new `projects/**` folder is outside it and **unguarded by default** (the same trap as the 2026-09-17 scope widening). Add `projects` to `ROOTS` in the same step.
- **Prettier guard** (`format:check`) covers `"src/**/*.{ts,html,scss,json}"` only; `lint` covers `src/**`; `typecheck` names three tsconfigs; coverage includes `src/app/inspecto/**`, `src/app/modules/admin/**`. Every one of these globs needs the new folders, and `.github/workflows/ui.yml` path filters (`inspecto-ui/**` — unchanged if the workspace stays in `inspecto-ui/`).
- **Dynamic imports.** `widget.kind.ts` and `menu-artifact.component.ts` import LA/Geo components by path; the 3 existing lazy imports inside LA are same-folder. A computed specifier is flagged by the rule — do not introduce one.
- **`/bootstrap` features.** `geoLink` is derived from two route registrations (`BootstrapRoutes.java` l.105); the LA flavor must not need `ops`/`exchange`; a missing flag must degrade, not throw (only `LA_FEATURES` has a safe default; the other nine throw by design).
- **Hand-kept mirrors drift** (14 Java files + 3 tool scripts, §2). A file move that passes `ng build` and fails a Java parity test in another module is the expected failure shape; run the parity tests with step 3.
- **Fixture JSON stays where Java reads it** until step 4 updates the constant; do not move `*.fixture.json` in step 3.
- `package.ps1` "first `index.html` under dist" becomes ambiguous the moment a second application builds; fix in step 7, not earlier (step 6's `ng build la-app` writes a second dist folder).
- A NEW doc/guard row: `docs/INDEX.md` must list this file in the same change (the peer-owned INDEX is not edited by the commit that adds this design).

## 7. Risks

1. **`ng-packagr` and the offline cache** — not grounded; avoided by Decision 3a.
2. **Cycle risk is real, not hypothetical**: 4 host-core files import LA code (step 1) and 14+14 host files import the graph canvas / `unique-name` (Decision 2).
3. **Two shells, one `app.config` shape** — ten tokens throw when unprovided; `la-app` must provide all or knowingly accept absent (§3).
4. **Coverage floors** (`tools/check-coverage.mjs --ui`) read `inspecto-ui/coverage/*/coverage-summary.json`; two applications may produce two summaries (not grounded).
5. **`inspecto-ui` package name / version `21.0.0`** is the template's, not the product's — unrelated to D-5, do not touch.

## 8. What is NOT in D-5

- D-6 integration (external references, Dossier export bundle, embeddable view, trust between installations) and D-7 sandboxes.
- Any backend module change beyond packaging (`bundle-modules.mjs`, `package.ps1`, a profile if Decision 6a): the LA backend is D-1…D-4 and is done.
- Module federation (D3 signed: no).
- Renaming the `gamma` project or the vendored `@gamma` template.
- A second `-Dui.dir` in `ControlApi` (Decision 7b).
- The data-getting-in work (SEP-11) and the nav menu profile content (SEP-12) beyond the 4 entries named in §3.
- A mobile or SSR build of `la-app`.

## 9. References

- [`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §1.5, §7.5, §7.8, §7.9 (D8–D21), §7.12
- [`la-separation-d1-design.md`](la-separation-d1-design.md) — the signed-design style this file follows
- [`la-separation-execution-plan.md`](la-separation-execution-plan.md)
- [`../archived-documents/plans-archive/la-separation-d4-design.md`](../archived-documents/plans-archive/la-separation-d4-design.md)
- [`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md)
- [`../EDITIONS.md`](../EDITIONS.md)
