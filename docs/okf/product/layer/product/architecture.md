---
type: Architecture
title: Inspecto UI Architecture
description: The Angular workspace behind the Inspecto web UI — what it is, its tech stack, how it boots, its layering, shared libraries, state, the app shell and the binding conventions.
resource: inspecto-ui/
tags: [architecture, inspecto-ui, angular, tech-stack, layering, shared-libraries, signals, app-shell]
timestamp: 2026-11-12T00:00:00Z
---

# Architecture

> **Status:** current as of 2026-11-12, checked against the code. Audience: frontend engineers, architects.

This is the entry page for the Inspecto web UI. The backend entry page is
[the platform architecture](../platform/architecture.md).

## 1. What it is

`inspecto-ui/` is one Angular workspace (`inspecto-ui/angular.json`) that builds two single-page applications
from one shared core and one in-workspace library:

| Unit | Where | What it is |
|---|---|---|
| **Console** (Angular project `gamma`) | `inspecto-ui/src/` | The Inspecto console: author Pipelines, set up Connections and Collectors, browse the Catalog, build Widgets and Dashboards, work Alerts, Incidents and Cases, watch Runs, Jobs and Events. Builds to `dist/gamma`. |
| **Inspecto LA host** (project `la-app`) | `inspecto-ui/projects/la-app/` | The stand-alone Link Analysis application. Its `telco` build configuration swaps `product.generated.ts` for `product.telco.generated.ts`, so the same host also builds Inspecto Telco. Builds to `dist/la-app`. |
| **Link Analysis library** (`@inspecto/link-analysis`) | `inspecto-ui/projects/link-analysis/src/` | The Link Analysis, Geo map, Entities and Add data screens, used by both hosts. Wired by `paths` in `inspecto-ui/tsconfig.json`; there is no separate package build. |
| **Shared core** (`@inspecto/core/*`, also imported as `app/inspecto/*`) | `inspecto-ui/src/app/inspecto/` | API clients, the design system and the cross-feature libraries (§5). |

Both applications are clients of the Java control plane and nothing else: every screen reads and writes
through its HTTP API. There is no mock backend in the workspace, so the UI needs a running control plane.

## 2. Tech stack

Versions are the ones pinned in `inspecto-ui/package.json`.

| Concern | Choice |
|---|---|
| Framework | Angular 22.1.1 (CLI and `@angular/build` 22.1.3). Standalone components only: no `@NgModule` exists under `src/` or `projects/`. Templates use the built-in control flow (`@if`, `@for`, `@switch`). |
| Language | TypeScript 6.0.3. `strict` is off in `inspecto-ui/tsconfig.json`; the Angular compiler runs with `strictTemplates`, `strictInjectionParameters` and `strictInputAccessModifiers`. There is no `baseUrl`: the import roots `app/*`, `environments/*`, `@gamma`, `@inspecto/core/*` and `@inspecto/link-analysis` are explicit `paths`. |
| Change detection | Zone.js (`provideZoneChangeDetection` with event coalescing). Every component is `OnPush`: 414 of 414 component files on 2026-11-12. |
| State | Angular signals plus RxJS 7.8 for HTTP and streams. No NgRx or other global store. |
| UI kit | Angular Material and CDK 22.1.2 (themed through the M2 API in `src/@gamma/styles/themes.scss`) and Tailwind 3.4, on the vendored gamma shell in `src/@gamma/`. |
| Tables | ag-Grid Community 35.3.1, registered module by module in `inspecto-ui/src/app/inspecto/grid/index.ts`. |
| Charts, graphs, maps | Chart.js 4 (charts), AntV G6 5.1.1 (graphs), MapLibre GL 6 with PMTiles 4 (maps; the basemap ships in `src/assets/basemap/`). |
| Editors and in-browser SQL | CodeMirror 6 for the SQL editor; AlaSQL runs a query over rows the page already holds. Both live in `inspecto/data-table/sql/` and AlaSQL loads on first use. |
| Dates, toasts | Luxon (with the Material Luxon date adapter), ngx-toastr. |
| Forms | Angular reactive forms. Config-attribute forms are declared as `AttributeSpec[]` and drawn by `<inspecto-schema-form>`. |
| Tests | Vitest 4 through the `@angular/build:unit-test` builder (jsdom), `TestBed`, and axe-core assertions (`expectNoA11yViolations`). Specs are collected from `src/**` and `projects/**`. |
| Lint and format | ESLint 10 flat config (`inspecto-ui/eslint.config.mjs`: angular-eslint 22, typescript-eslint 8), Prettier 3.4. Repository guards: `lint:tokens`, `lint:forms`, `lint:denylist`, `check:bundle-shape`, `check:theme-tokens`. |
| Package manager | npm (`npm ci` in CI). |

Lazy loading is by route (`loadChildren` / `loadComponent`) and by dynamic `import()`. `@defer` is used in one
place, the shared data table's SQL editor.

Production bundle budgets are set per application in `inspecto-ui/angular.json` (the console's `main` bundle
fails the build above 2.0 MB), and `inspecto-ui/tools/bundle-budget.json` adds a check on which code may sit in
the eager bundle: MapLibre and the Link Analysis library must stay in lazy chunks.

## 3. How it boots

1. **Bootstrap.** `inspecto-ui/src/main.ts` calls `bootstrapApplication(AppComponent, appConfig)`.
   `inspecto-ui/src/app/app.config.ts` builds the provider list. The part both hosts share comes from
   `provideInspectoShell` (`inspecto-ui/src/app/inspecto/shell/provide-inspecto-shell.ts`): the environment and
   brand, change detection, `HttpClient` with four interceptors, the router, the keep-alive route reuse strategy,
   the Luxon date adapter and modal Material dialogs. Each host adds only its routes, initialisers, icons, theme
   and Link Analysis host services.
2. **HTTP.** `apiUrl()` (`inspecto/api/api-base.ts`) prefixes every call with the API base plus `/v1`, so all
   traffic goes to `/api/v1`. The interceptors run in this order:
   - `v1Interceptor` unwraps the success envelope (`{data, metadata, …}` → `data`), so services see plain DTOs.
   - `spaceInterceptor` scopes the call to the active Space: `/api/v1/pipelines` becomes
     `/api/v1/spaces/<id>/pipelines`. Server-global paths (`/health`, `/bootstrap`, `/auth`, `/spaces`,
     `/public`, `/exchange`, …) are left alone. Event streams do not pass through `HttpClient`, so they apply
     the same function (`spaceScopedUrl`) by hand.
   - `authInterceptor` attaches the bearer token and retries once after a silent refresh. It passes requests
     through unchanged unless the server reports sign-in.
   - `errorInterceptor` records whether the backend is reachable; the connectivity banner reads that state.
3. **Session.** An app initialiser runs `SessionService.init()`, which reads `GET /api/v1/bootstrap` once before
   routing starts. The answer carries the edition, the auth mode, the feature flags that decide which navigation
   entries exist, the Product and its navigation, the caller's capabilities and the entitlement state.
   - Auth mode `none` (Basic): no sign-in, no token; `authGuard` lets every route through.
   - Auth mode `oidc` (Standard and Advanced): `authGuard` redirects to `/sign-in`, which starts an
     Authorization Code + PKCE login; `/auth/callback` redeems the code. The access token is held in memory
     only; the refresh token is an httpOnly cookie the browser never reads.
4. **Routing.** `inspecto-ui/src/app/app.routes.ts` declares the console's routes:
   - `''` resolves a landing page (`landingGuard`: the Demo User's landing, then the Space's, then `/home`).
   - Routes outside the shell, with no guard: `sign-in`, `auth/callback`, `share/:token` (public Dashboard
     embed) and `embed/inv/:token` (read-only Investigation embed).
   - One shell route (`LayoutComponent`, behind `authGuard`) whose children are the feature screens, each
     lazy-loaded from `modules/admin/<feature>/`. Its resolver loads the navigation before the shell renders.
   - `**` renders a not-found page inside the shell.
5. **Product data.** Each host has a `product.generated.ts`, written by `tools/render-product-hosts.mjs` from
   `products/` and `apps/`. Both hosts take the brand from it (sign-in screen, browser tab title). The LA host
   also takes its navigation from it: it draws the navigation the server resolved for the caller when there is
   one, and otherwise the generated one filtered by feature flags (`productNav` in
   `inspecto/product/product-host.ts`). The console's sidebar comes from `navigation-data.ts` (§7).

**Dev and packaged.** `npm start` serves the console on port 4204; `ng serve la-app` serves the LA host on
port 4304. Both proxy `/api` unchanged to the control plane on `localhost:8080`
(`inspecto-ui/proxy.conf.json`). In a packaged install the control plane serves the built application from the
same origin (`-Dui.dir`), so `apiBaseUrl` is `/api` in both modes. `angular.json` has no environment file
replacement: `src/environments/environment.ts` is the file that ships.

## 4. Layering and where things live

```
inspecto-ui/
  src/
    main.ts                      # console entry point
    app/
      app.config.ts  app.routes.ts  product.generated.ts
      inspecto/                  # SHARED CORE (@inspecto/core/*), see §5
      modules/admin/<feature>/   # console features: components + <feature>.routes.ts
      layout/                    # the console shell: layout.component, classic / dense / empty layouts,
                                 #   header widgets (lens switcher, Space switcher, notifications, search, user)
      core/navigation/           # navigation-data.ts (built-in sidebar) + NavigationService
      core/icons/  core/user/    # icon registration, user menu data
    @gamma/                      # VENDORED shell kit (navigation, loading bar, config, styles)
    environments/  styles/  assets/
  projects/
    link-analysis/src/           # the library (@inspecto/link-analysis): link-analysis, geo-map, geo,
                                 #   entities, add-data, graph algorithms, investigation, la-host tokens
    la-app/src/                  # the Inspecto LA host: its own shell, landing page, routes, settings
  tools/                         # the UI guards (design tokens, form idiom, bundle shape, npm deny-list)
```

**Dependency direction: core ← library ← hosts.**

- The core never imports a feature (no file under `inspecto/` imports `app/modules/`) and never imports the
  library. ESLint enforces the second half: `@inspecto/link-analysis` and the library's folders are restricted
  imports for `src/app/inspecto/**`.
- The library never imports a host feature and may not leave its folder with `../`. What it needs from a host
  (Tags, import and export, the Assistant, Cases, feature flags) arrives through injection tokens in
  `projects/link-analysis/src/la-host/`. The console provides them in
  `modules/admin/studio/la-host.providers.ts`, the LA host in `projects/la-app/src/app/la-host.providers.ts`.
  ESLint enforces this for static and dynamic imports.
- The library's public entry (`projects/link-analysis/src/public-api.ts`) exports only what a host needs at
  start-up: the host tokens and two Visualization Type registration functions. Hosts load its pages through
  deep lazy imports such as `@inspecto/link-analysis/link-analysis/link-analysis.routes`, so G6 and MapLibre
  stay out of the eager bundle.
- `inspecto/api/` reads the build environment only through the injected `APP_ENVIRONMENT`
  (`inspecto/api/app-environment.ts`), never from `environments/*` (ESLint-enforced), so the API layer does not
  depend on a host file.

**Features.** A console feature is a folder under `modules/admin/` (54 on 2026-11-12) holding standalone
components, dialogs and a `<feature>.routes.ts` with a default export. Several older screens are now sections
of one Settings page: `/connections`, `/config`, `/spaces`, `/design` and `/notification-center` redirect to
`/settings/<section>`. The Studio routes (`modules/admin/studio/studio.routes.ts`) mount the library's Link
Analysis, Entities and Geo map pages beside the console's own Widgets, Dashboards and saved Queries.

## 5. Shared libraries and the design system

Everything two features need lives in `src/app/inspecto/`. The main folders:

| Folder | What it holds |
|---|---|
| `api/` | One `providedIn: 'root'` service per backend resource, the barrel `index.ts`, `apiUrl` and error helpers, three of the four interceptors, `optimisticMutate`, `visibleInterval`. See [API and data](spec-api-and-data.md) and [API services](api-services.md). |
| `auth/` | `SessionService`, `authGuard`, `authInterceptor`, PKCE helpers, the sign-in and callback pages. |
| `shell/`, `product/` | `provideInspectoShell` and the Product host model both applications share. |
| `components/` | The design-system components: `<inspecto-status-badge>`, `<inspecto-alert>`, `<inspecto-empty-state>`, `<inspecto-skeleton>`, `<inspecto-chart>`, `<inspecto-chip>`, `<inspecto-page-header>`, `<inspecto-schema-form>`, `<inspecto-editable-grid>`, the connectivity and entitlement banners, and more. |
| `grid/`, `data-table/`, `tree-table/` | The ag-Grid theme and module registration; `<inspecto-data-table>` (column chooser, quick filter, CSV export, SQL editor) and `<inspecto-tree-table>`. |
| `query/`, `rule/` | The condition-group editor, the query model and its SQL rendering, `<inspecto-query-panel>`, the Measure grammar; the dialog and service that save a query as a `rule-template` component. |
| `viz/` | The Visualization Type registry (`VizPlugin`), the `QuerySpec` a Widget emits, the render host and the dashboard grid. See [Studio BI](architecture-studio-bi.md). |
| `graph/` | The core slice of the graph code: `<inspecto-graph-view>` (the G6 canvas), graph types, entity keys, export. The graph algorithms live in the library. |
| `component-model/` | The Component metamodel: `AttributeSpec`, component kinds, references, the reuse graph. |
| `contracts/` | Contract JSON files published by the server (node attributes, bind kinds, attribute spec, expression guard, Measure grammar, processor catalog, column roles, SQL AST and functions, menu routes). Java contract tests read these same files by path, so they are regenerated, never hand-edited or moved. |
| `theme/` | `chart-tokens.ts` and `map-tokens.ts`: the colours canvas renderers cannot read from CSS. |
| `menu/`, `commands/` | The Menu Builder model with its `/nav/menus` client and favourites; the command registry behind the search palette. |
| `format/`, `testing/`, `fixtures/` | Formatting helpers and pipes; test helpers (`a11y.ts`, the Vitest setup file); sample rows used by specs only. |

The remaining folders hold domain helpers shared by more than one screen (for example `schema/`, `tags/`,
`transfer/`, `spaces/`, `onboarding/`, `reconciliation/`, `ai-assist/`). `investigation/` keeps only
`uniqueNameValidator`; the saved-view store, the element detail dialog and the pivot service are in the
library (`projects/link-analysis/src/investigation/`).

**Design system.** Colours come from the gamma `--gamma-*` CSS variables and Tailwind utilities.
`npm run lint:tokens` fails on a hard-coded colour, a hand-written status-colour helper or a status-tinted
background fill anywhere under `src/app` and `projects`. Five files may hold colours: the two `theme/` token
files and the status badge, alert and connectivity banner components. The living gallery is the Design section
of Settings (`/settings/design`); see [the gallery](spec-ds-gallery.md) and
[design tokens](spec-design-tokens.md).

## 6. State

- **Signals** hold component state and shared service state; `computed()` derives; `effect()` is used
  sparingly; `linkedSignal` covers "derived but locally editable" (three files use it).
- **RxJS** carries HTTP calls and streams.
- **Shared state is a root service that exposes signals.** `ConnectivityService` (`online`,
  `backendReachable`, `degraded`), `SpacesService` (`availableSpaces`, `currentSpaceId`, `currentSpace`),
  `LensService` (`currentLens` and one `can…` signal per capability) and `SessionService` (`authMode`,
  `edition`, `features`, `capabilities`, `product`) follow this pattern.
- **Mutations.** Reversible edits use `optimisticMutate` (`inspecto/api/optimistic.ts`): apply locally,
  reconcile on success, roll back and toast on failure. Creates and deletes refetch.
- **Browser storage** holds per-viewer choices only: the active Space (`inspecto.currentSpace`), the Lens
  (`inspecto.currentLens`), a mirror of the Space's custom menus and the viewer's menu favourites.
- **Kept pages.** A route flagged `data: { keepAlive: true }` (the Link Analysis page) is detached, not
  destroyed, when the user leaves it, and re-attached on return. A kept page belongs to one Space and one
  signed-in user (`inspecto/keep-alive.strategy.ts`).

Detail: [forms and state](spec-forms-state.md), [multi-Space](spec-multi-space.md).

## 7. The app shell

**Console.** `LayoutComponent` (`src/app/layout/layout.component.ts`) is a flex column. Its first two children
are `<inspecto-connectivity-banner>` and `<inspecto-entitlement-banner>`; both render nothing when there is
nothing to say, and the connectivity banner's host is `display: contents`, so it takes no space when hidden.
Below them sits one of three layouts (`classic`, `dense`, `empty`), chosen from the gamma config (`classic` by
default), a `layout` query parameter or route data. Do not give a layout-level sibling a growing `flex`: it
takes width from the content column.

The classic layout draws, in order:

- a skip link to the main content;
- the sidebar (`gamma-vertical-navigation`): logo and caption from the deployment's branding, a filter box
  over the navigation tree, then the tree;
- the header: Lens switcher, Space switcher, notification bell, the search and command palette, the user menu;
- `<main>` with the router outlet;
- a footer, hidden for a route with `data: { hideShellFooter: true }` (Link Analysis uses it for its canvas).

**Navigation.** `NavigationService` builds the sidebar on the client:

- The built-in tree is `core/navigation/navigation-data.ts`: Home, Business, Operations, Platform (Workbench,
  Studio, Catalog), System Maintenance and Assistant.
- An entry with a `navFeature` is dropped unless `/bootstrap` reports that feature, so an edition without a
  module does not offer its screen.
- The Space's custom menus (Menu Builder, stored through `/nav/menus`) and the viewer's Favourites group are
  placed above the built-in groups.
- Adding a console page is two edits: the lazy route in `app.routes.ts` and the entry in `navigation-data.ts`.

**Lens.** The Lens (`business`, `builder`, `ops`) decides three things: the home screen offered on the Home
page (`LENS_HOME` in `app.routes.ts`), which actions are enabled (`LensService`), and which sidebar entries are
shown. `AccessStateService.filterNav` narrows the sidebar to the Lens's default scope and then applies the
Space's saved Access Profiles. This filters the sidebar only: no route is removed, so a URL still opens.

**Commands.** Features register palette commands in `inspecto/commands/` through a side-effect import, so the
layout offers "New incident" or "New job" without importing a feature.

**Inspecto LA host.** `la-app` does not use the console layout. `LaShellComponent` draws a rail from the
Product's navigation, with the same two banners, the Space switcher and the user menu. Its routes mount the
library's pages and a few console pages, each behind an `areaGuard` that checks the Product's navigation.

Detail: [routing and navigation](spec-routing-navigation.md), [page chrome](spec-page-chrome.md),
[errors and connectivity](spec-errors-connectivity.md).

## 8. Binding conventions

1. **Standalone, `OnPush`, signals.** New components are standalone, `OnPush`, use `inject()` and the built-in
   control flow. Spec host components are exempt from the `OnPush` lint.
2. **Share through the core.** Code two features need goes into `inspecto/`; the core never imports a
   feature. A new feature should not import another feature. This last part is a review convention, not a
   lint: on 2026-11-12 the console holds 48 feature-to-feature imports, most of them other features reusing
   Studio's Widget and Dataset code and the Settings page composing its section screens.
3. **One screen, many routes.** A screen is reused across routes through route `data` rather than copied:
   `/cases` and `/incidents` are both `ObjectMailComponent`, told apart by `data.type`.
4. **Containers and presentational components.** A container injects services and holds signal state. A
   shared component in `inspecto/components` takes `input()` / `output()` and makes no HTTP call.
5. **API access.** Call the backend only through an `inspecto/api` service and `apiUrl()`. Report failures
   with `apiErrorMessage`. A missing optional module or an unreachable backend (`isFeatureAbsent`: status
   404, 502, 503, 504 or no response) gets an in-place explanation, not an error toast.
6. **No hard-coded colours.** Use the design-system components and tokens (§5); `lint:tokens` is a CI gate.
7. **Forms.** Reactive forms only. A typed value is an `<inspecto-pen-field>` row and a choice an
   `<inspecto-option-picker>` row; `lint:forms` allows the older boxed Material field only in the files on
   its shrinking allow-list.
8. **Accessibility.** WCAG 2.2 AA. Component specs assert `expectNoA11yViolations`; the ESLint template
   accessibility preset is on.
9. **Vendored code.** `src/@gamma/**` is vendored: it is excluded from ESLint and the design-token guard and
   is not restyled.
10. **Lazy by default.** Every feature route is lazy. Nothing heavy is added to the library's `public-api.ts`.
11. **Gates.** The UI workflow (`.github/workflows/ui.yml`) runs `lint:tokens`, `lint:denylist`,
    `format:check`, `lint`, `typecheck`, the tests with coverage, both production builds,
    `check:bundle-shape` and `check:theme-tokens`.

When code and a doc disagree, fix one of them in the same change.

## 9. Go deeper

| Topic | Page |
|---|---|
| Studio: Datasets, Measures, Widgets, Dashboards | [Studio BI](architecture-studio-bi.md) |
| API layer, envelope, auth by edition | [API and data](spec-api-and-data.md), [API services](api-services.md) |
| Spaces in the UI | [Multi-Space](spec-multi-space.md) |
| Routes, navigation, search | [Routing and navigation](spec-routing-navigation.md) |
| Errors, connectivity banner | [Errors and connectivity](spec-errors-connectivity.md) |
| Forms, signal state, optimistic edits | [Forms and state](spec-forms-state.md) |
| Design system | [Gallery](spec-ds-gallery.md), [design tokens](spec-design-tokens.md), [page chrome](spec-page-chrome.md), [data table](spec-ds-data-table.md), [tree table](spec-ds-tree-table.md), [chart](spec-ds-chart.md) |
| Accessibility | [Rules](spec-accessibility.md), [audit](tests-accessibility-audit.md) |
| Tests and build | [Testing and build](tests-testing-build.md) |
| Backend | [Platform architecture](../platform/architecture.md) |
