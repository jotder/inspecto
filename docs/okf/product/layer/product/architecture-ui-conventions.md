---
type: Reference
title: inspecto-ui conventions and gotchas
description: Conventions for adding panes to inspecto-ui, and the UI build and test traps; the angular-ui skill holds the full rules.
tags: [angular, ui, conventions, gotchas]
timestamp: 2026-11-19T00:00:00Z
---

# inspecto-ui conventions and gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` §6 and the UI entries of its §4 (D-IR5, 2026-11-19). The binding rules are the `angular-ui` skill.

## Conventions (§6)

Angular 22 · Material/Tailwind · ag-Grid 35 · Chart.js · AntV G6 5. **Read the `angular-ui` skill before
touching `inspecto-ui/`.** Highlights (full detail there):

- **API clients** in `src/app/inspecto/api/` (barrel `index.ts`): `@Injectable({providedIn:'root'})`,
  `inject(HttpClient)`, `apiUrl('/path')` (→ **`/api/v1`** since W7) + `toParams({...})` from `api-base.ts`;
  interfaces inline in the service. Interceptor chain: first-position `v1Interceptor` (shape-guarded envelope
  unwrap), `spaceInterceptor` (space id **after** `/v1`), `errorInterceptor`, and `auth.interceptor` — the
  auth flow is a **no-op on Personal** (OIDC only when `bootstrap.features.authMode` says so, W6d).
- 🔴 **A surface shown BEFORE sign-in cannot use the ordinary services** (2026-09-15, landing pages).
  `GET /settings/branding` sits behind the auth gate, so `BrandingService` — which every in-shell surface
  uses — **401s on the sign-in page**, the one screen where deployment branding matters most. Branding now
  rides `GET /bootstrap` (public) and `SessionService.branding` exposes it; ⛔ do not "simplify" the sign-in
  page onto `BrandingService`. The general rule: on a pre-sign-in surface, `/bootstrap` and `/health` are the
  only routes you may assume, and anything else must be checked against `ControlApi.PUBLIC_PATHS`.
- 🔴 **Nothing in the running system knows the product version** (2026-09-15). No Java file reads
  `Implementation-Version`, no route serves a version, `environment.ts` has no version field, and
  `inspecto-ui/package.json` says **21.0.0** — the Angular scaffold's number, **not** the product's
  `4.0.0-SNAPSHOT`. ⛔ Never print `package.json`'s version as the product version. BACKLOG `HOME-VERSION-1`.
- ⚠ **A javadoc can name a dev switch that does not exist.** `session.service.ts` documents a
  `mockAuthMode: 'oidc'` switch for previewing the OIDC arm; **grep finds that one comment and zero
  implementations**, and `tools/run-backend.ps1` passes no `-Dauth.mode` either — so the sign-in page cannot
  be opened locally at all without editing source, and it shipped unit-tested but never seen in a browser
  (BACKLOG `SIGNIN-PREVIEW-1`). Check that a switch a comment promises is real before planning to use it.
- ⚠ **`TestBed` refuses a second `configureTestingModule` once instantiated**, so a spec helper that builds a
  fixture can only be called **once per `it()`** — the documented house rule. When a test genuinely needs two
  fixtures (gating read in `ngOnInit` cannot be un-called by flipping a signal afterwards), call
  `TestBed.resetTestingModule()` at the top of the helper and say why; otherwise build one fixture and mutate
  its stub signals.
- **Feature panes** in `src/app/modules/admin/<feature>/`, **signals + OnPush**. A pane can be reused across
  routes via `ActivatedRoute.snapshot.data` (Cases/Issues = one `ObjectsComponent`).
- **Second "lens" on a pane = `mat-button-toggle-group`, NOT a new nav item** (Pipelines `flow|combined`, Jobs
  `schedules|reporting`). Factor shared blocks into `<ng-template>` + `*ngTemplateOutlet`.
- **No hardcoded colors** — CI guard `npm run lint:tokens` fails on hex/`rgb()`/`levelClass`-style helpers under
  `inspecto/**` + `modules/admin/**` (allowlist: `chart-tokens.ts`, `status-badge.component.ts`). Status/level
  colors come from `<inspecto-status-badge>` only. `rgba(var(--gamma-…))` is allowed.
- **a11y gate** — `expectNoA11yViolations(el)` (`inspecto/testing/a11y.ts`, axe-core) in component specs; runs in
  CI. Manual WCAG: `docs/okf/product/layer/product/tests-accessibility-audit.md`.
- **Shared design system**: `status-badge` / `empty-state` / `skeleton` / `grid` (+ `noRowsOverlay`) /
  `connectivity-banner` / `ai-assist`. Living gallery at `/design`. **Since 2026-09-22 it also owns the page
  chrome** — `page-header` / `filter-bar` / `stat-tile` / `section-tabs` / `bulk-actions`. The programme
  shipped and was archived 2026-09-22; its as-built facts and measured traps are
  [`okf/product/layer/product/spec-page-chrome.md`](spec-page-chrome.md) — read that, not the
  bullets below, when the two disagree. A routed pane renders its title, subtitle, help affordance and actions through
  `<inspecto-page-header>`; it owns the page's ONLY `<h1>`, at the shared `text-title` size (22 px).
- 🔴 **An absent optional module is `isFeatureAbsent(err)`, not an error** (2026-09-22, `api-base.ts`):
  status `0/404/502/503/504` means "not deployed here", so the pane explains it in place with an info
  `<inspecto-alert>` and stays silent. Home, Learning and Autonomy each toasted red over a panel that
  already explained the absence. ⚠ The helper is **duck-typed on `status`**, because an `instanceof
  HttpErrorResponse` gate answers "not absent" for any caller holding a plain error object and silently
  puts the toast back. ⚠ `502` is the one every hand-rolled guard missed — behind `proxy.conf.json` a
  stopped backend arrives as a gateway error, not as `0`.
- 🔴 **A `MatTabGroup` whose `[selectedIndex]` is bound to a getter over the selected id SPRINGS BACK**
  (2026-09-22). Material decides whether to emit `selectedIndexChange` in `ngAfterContentChecked` by
  comparing the clicked index against the value its input carries, so a derived binding re-asserts the old
  index in the same pass, the emit never happens and the tab silently reverts. Hold the index in the
  component (`<inspecto-section-tabs>` does) and reconcile the id into it.
- ⚠ **A synthetic click cannot drive a Material tab in jsdom at all** (measured 2026-09-22): the FIRST
  dispatched click is swallowed whichever element it targets — the `.mat-mdc-tab` wrapper, the inner
  `.mdc-tab__content`, or the label — and later ones alternate, so the result depends on attempt order,
  not on behaviour. A tab spec must drive the component's own handler and assert the rendered active tab;
  the real click is proven in the preview.
- ⚠ **ag-Grid CLIPS a header it cannot fit, it does not wrap.** Five panes shipped with "Sev…",
  "Collec…", "What's sch…". `wrapHeaderText` + `autoHeaderHeight` in `INSPECTO_DEFAULT_COL_DEF` fix them
  all at once — and `headerHeight` must then come OUT of the theme params, or the wrapped row cannot grow.
- **`<inspecto-ai-assist>`** (`inspecto/ai-assist/`, AGT-6a) is the ONE inline AI authoring surface —
  panes **adopt** it, never fork it. The pane names a non-mutating agent tool, passes its own context as
  `[args]`, and applies the returned draft through **its own** validated route (the surface has no write
  path, so the human stays the audited actor). → `okf/product/module/intelligence/user-guide-inline-ai-authoring.md`.
- 🔴 **A root-level `{ provide: MatDialog, useValue: … }` in a component spec is SILENTLY IGNORED** (found
  2026-07-27). `MatDialogModule` sits in the standalone component's own `imports` and provides `MatDialog` at
  the **element** level, which shadows the TestBed root provider — `catalog.component.spec.ts` had mocked it
  since it was written while the component used the real service throughout. Reach it with
  `TestBed.overrideComponent(C, { add: { providers: [{ provide: MatDialog, useValue: MOCK }] } })`. Applies to
  any service a component's imported Material module also provides. ⚠ **Other specs mocking `MatDialog` at the
  root are mocking nothing — not audited beyond that one file.**
- ⚠ **`LensService` snaps to the read-only Business lens when `SessionService.capabilities()` is `[]`**, because
  `allowedLenses` qualifies Builder only via `canAuthorWorkbench`. A spec mocking capabilities as `[]` sees every
  authoring affordance hidden, and the resulting "the button never fired" failure looks like broken wiring. Pass
  `['canAuthorWorkbench']` when testing an authoring path.
- ⚠ **Narrowing a UI test run:** `npx vitest run <file>` fails here (`Cannot find package 'app/inspecto/api'` —
  the path aliases come from the Angular builder). Use `npx ng test --no-watch --include='<glob>'`, and the
  include must be a **glob**: a literal file path yields *"No test files found, exiting with code 1"*, which
  reads like a config break rather than a bad flag.
- 🔴 **The offline mock backend was DELETED 2026-08-31** (`f1553136`, plan archived at
  `archived-documents/plans-archive/mock-backend-removal-plan.md`). `inspecto-ui/src/app/inspecto/mock/`,
  `environment.offline.ts`, the `offline` build/serve configurations, `npm run start:offline` and the ten
  `environment.mock*` flags are gone; the UI talks only to a real ControlApi. **Running it now needs a
  backend** — dev: `npm start` on :4204 with `proxy.conf.json` forwarding `/api` to :8080; packaged:
  `-Dui.dir=…/dist/gamma/browser` (note the `browser` subdir). ⚠ Two directories survive the move and are
  NOT mocks: `inspecto/contracts/` (six server-published contract JSONs, read **by path** by six Java
  contract tests and exempted in `.prettierignore` because they are byte-compared against Jackson's output)
  and `inspecto/fixtures/` (spec-only sample rows). The note below is kept for the LESSON, which outlived
  the mock: it is why a mock-vs-server divergence was the whole reason for the removal.
- ⚠ **The offline mock must never be more lenient than the server** (2026-07-27, AGT-6a A5.3) — *historical;
  there is no mock any more.* A handler
  that accepts a shape the backend rejects — or returns a richer shape than the backend returns — turns a
  hard failure into a passing rehearsal. The Pipelines `pipeline_author` adoption shipped **broken through
  two slices** (flat args where the tool requires `flow`; a name string where `adaptToolResult` requires the
  graph, so a successful call rendered as *"no suggestion"* with no error) and looked correct offline
  throughout. 🔴 **The transferable lesson: a test double that is more PERMISSIVE than the real route turns a
  hard failure into a passing rehearsal** — so a double must be diffed against the route's accepted args
  *and* its result keys, and the strictness pinned by a test. *(The mock backend this was learned on was
  deleted 2026-08-31; the lesson applies to any stub or fake that stands in for a route.)*
  ⚠ **It was not one bad branch — a deliberate audit (2026-07-27, `feb6f6e7`) found the same class live in
  two more branches of the same file**, and a lenient mock hides *server* defects too: tightening
  `component_draft` immediately exposed that its `schema` kind validates the wrong `schema` entirely.
  Two rules that came out of it: (1) where full parity would mean re-implementing a backend subsystem,
  mirror **acceptance** — the same inputs refused, the same inputs rendering nothing — and say so at the
  branch; (2) a mock stand-in must emit the shape the **server** parses, never a convenient flat one, or it
  teaches the wrong contract to whatever consumes it next.
- **ag-Grid gotchas:** (a) action/string cell renderers don't render on first paint with static `rowData` →
  call `refreshCells({force:true, columns:[…]})` on `(firstDataRendered)`/`(rowDataUpdated)`; (b) the shared
  theme MUST be the gamma-token `themeQuartz.withParams(GAMMA_GRID_PARAMS)` (`app/inspecto/grid/index.ts`) — never
  bare `themeQuartz`; (c) off-screen (virtualized) columns aren't in the DOM until you scroll horizontally — set
  `scrollLeft` before asserting in preview.
- **`@if/@else` + `mat-icon` button ⇒ NG8011** (icon won't project). Keep always-on icon buttons outside the
  branch, or make the branch's only root the button.
- **A `computed()` that reads a plain `@Input` field NEVER invalidates** — computeds track *signal*
  dependencies only, so a parent flipping the input leaves the derived value permanently stale (cost us a
  disabled-forever button in `ai-assist`, 2026-07-26). Use **signal inputs** (`input()`) whenever a
  `computed()`/`effect()` reads the input. ⚠ Specs miss this by construction if they set inputs *before*
  the first render — assert the change with `fixture.componentRef.setInput(...)` *after* it, and note that
  assigning to a signal input no longer compiles.
- **TestBed `{provide: MatDialog, useValue: …}` is silently shadowed** on any pane that imports
  `DataTableComponent` (or anything else importing `MatDialogModule`): the standalone component's
  *standalone injector* re-provides the real `MatDialog` closer than the testing module, so the pane
  injects the real service and `open()` explodes in jsdom (`undefined.push` in material dialog.ts).
  Fix: after `createComponent`, `vi.spyOn(componentInstance['dialog'], 'open').mockReturnValue(...)` —
  spy on the instance the component actually got (see `alerts.component.spec.ts`). Several older specs
  carry the dead-weight provider without noticing because they never call through `open`.
- **jsdom forbids spying `window.location.assign`** (`TypeError: Cannot redefine property: assign`), so any
  code that navigates *away from the SPA* is untestable in place. `SessionService.redirect(url)` is the one
  seam for it (authorize + OIDC end-session); stub that, not `location`. Route it through the seam rather
  than adding a second `location.assign` call site.
- **Authenticated file download** — go through `HttpClient` (`responseType:'text'|'blob'`) + `Blob` +
  `createObjectURL` + transient `<a download>`; a plain anchor `href` doesn't carry headers.
- **Live tail** — `visibleInterval(ms)` (`api/auto-refresh.ts`, pauses on hidden tab); hold/resubscribe/unsub;
  `silent` flag avoids loader flash. `DEFAULT_REFRESH_MS=15000`.
- **Connectivity** — `ConnectivityService` (status 0 ⇒ unreachable) + `<inspecto-connectivity-banner>` owns the
  "backend down" UX (don't add per-screen toasts; **503 ≠ backend-down**). Banner host needs
  `:host{display:contents}` so it doesn't steal layout width.
- **Mocking** — ⛔ **there is none, and adding one back is a decision, not a convenience.** The single
  offline mock backend (`inspecto/mock/`, one interceptor over a localStorage store with seed packs) was
  **deleted 2026-08-31**: the SPA needs a running control plane, and a failed call must surface as an error
  rather than as sample data. In unit tests, mock at the service boundary; ⛔ never mock the HTTP layer
  (`.claude/skills/test-author/SKILL.md`).
- **Config-attribute forms are schema-driven** — declare `AttributeSpec[]` (tier `required|optional|advanced`,
  `dependsOn`) in `inspecto/component-model` and render with `<inspecto-schema-form>` (demo at `/design`;
  pilot: jobs `job-form.dialog`). Hand-build only bespoke sections (canvases, key/value arrays). `tier`
  (visibility) and `required` (validation) are decoupled — `required?: boolean` defaults from the tier but
  can be set explicitly, e.g. `tier:'required', required:false` for an always-visible optional field
  (`widget-option-attributes.ts`). Duplicate-name guard on create is a local `uniqueNameValidator` attached
  to the id control, skipped entirely when the field is locked on edit (jobs/dataset-editor/
  dashboard-editor/widgets all use this shape). ⚠ **Pipeline NODE attributes are a special case since
  2026-08-04: the server publishes them** on `GET /pipelines/node-types` (`attributes[]`, from
  `NodeAttributes.java`), so `pipelines/node-attributes.ts` is a **fallback**, not the source. Change a node
  attribute in BOTH, or the committed `inspecto/contracts/node-attributes.contract.json` drift check fails on one
  of the two sides (deliberately — see `okf/product/module/elt/user-guide-elt.md`). Adding an `AttributeType` still
  needs `FindingsSpec.TYPES` widened, which `NodeAttribute` now delegates to.
- **Optimistic mutations** — `optimisticMutate({apply,commit,reconcile,rollback,onError})` (`inspecto/api/
  optimistic.ts`); reassign arrays (`rows=[...]`) so the grid re-renders.
- **G6 graph** — reuse `inspecto/graph/graph-view.component.ts` (`@Input data`, `@Output nodeClick`);
  nodes are canvas-drawn (not DOM) → verify inspector logic via unit test, not preview clicks. Pipeline graph data
  via `flow-graph.ts#toFlowG6Data`.
- **Viz plugins register by side effect** — `import 'app/inspecto/viz/plugins'` runs `registerBuiltinViz()`.
  Admin shell surfaces trigger it transitively; a **guest/shell-less or lazy route that renders widgets must
  import it explicitly** or `getViz(type)` returns undefined and every tile reads "not embeddable" (bit BI-6
  `/share/:token` + BI-8). Reference: `modules/admin/share/share-viewer.component.ts`.
- **Anonymous routes** — add the path prefix to `space.interceptor` `SERVER_GLOBAL` (e.g. `/public`) or the
  active-space rewrite 404s it; the call is token/credential-addressed, not space-scoped.
- **`<inspecto-empty-state>` inputs are `title` + `message`** (not `heading`); `message` is required. Wrong
  input names fail silently (dropped in prod, caught only by a text assertion).
- **A Dataset's rows come from `DatasetRowsService`** (`inspecto/viz/dataset-rows.service.ts`, split S2
  2026-08-14) — `/db/table`, or `/db/query` with its Query Core model compiled by `compileSql`, live; the
  offline sample page otherwise. ⛔ Never re-introduce a `SAMPLE_SOURCES[ds.sourceName]` lookup in a
  feature: that synchronous read is why Studio showed sample data against a real backend. Results are
  **pages** (honour `truncated`, surface `error`). Detail + the ⛔ on the three offline arms that must NOT
  be converted: `okf/product/layer/product/user-guide-studio.md` and the `angular-ui` skill.
- **BI widget/dashboard content shape** — a `widget` component is `{vizType, datasetId, controls, options}`
  (channel mapping, NOT a raw query spec); a `dashboard` is `{name, tiles:[{widgetId, span}]}`. Anything
  writing these server-side (e.g. `BiTemplates`) must emit this shape or the Studio can't render it.
- **Dev**: `npm start` (`ng serve` :4204); `proxy.conf.json` maps `/api` → `:8080`. `.claude/launch.json`
  defines both preview servers.

## Gotchas (§4)

- ⚠ **`tsc -p tsconfig.app.json` does not compile `.spec.ts`.** Renaming an exported constant passed the
  app typecheck AND the production build, and failed only under `npm run test:ci`. For any rename or type
  change to a shared symbol, **`test:ci` is the type gate, not `build`.**

- ⚠ **`npm run format:check` (prettier) is DECLARED BUT NOT GATED** — it is in `inspecto-ui/package.json`
  and in neither `.github/workflows/ci.yml` nor `.githooks/pre-push`. Measured 2026-09-11: it was red on
  **five** files from an already-pushed commit and nothing anywhere failed, so the drift was invisible at
  commit time. ⇒ Run it in `inspecto-ui` before committing UI work. Prettier only re-wraps here (long
  signatures split, arrays exploded with a trailing comma), so `git diff -w` shows no semantic change and
  the fix is safe to take in one pass. ⚠ It is also a reminder that **a green build does not mean a clean
  tree**: this repo has more declared checks than gated ones.

- **A UI spec that RUNS green is not proof it typechecks — there are THREE tsconfigs and the root one
  is a different gate, not a superset.** Proved 2026-08-11 (`842a3a77`): a spec asserting `toHaveLength`
  on an element list passed `npm run test:ci`, passed the production `npm run build`, and passed **both**
  `tsconfig.app.json` and `tsconfig.spec.json` — while failing `npx tsc -p tsconfig.json --noEmit`. The
  root config sets no `types`, so every `@types/*` is ambient; only `tsconfig.spec.json` names
  `vitest/globals`, and the root config does not extend it. The `@types/jasmine` that used to leak a
  global `expect` in there was **removed as an orphan on 2026-09-08** (`89f09b6c`, with `jasmine-core`
  and the five karma packages), so the root config now supplies no test globals at all — the gate is
  unchanged, it just fails sooner. The fix is importing `{ describe, expect, it }` from `vitest` in the
  spec (all 334 specs now do) —
  **never** editing the tsconfigs to paper over it. Commands + rationale: `angular-ui` skill §12 step 2b.

- **A UI `ComponentType` the server does not know fails only at runtime, per call.** The union in
  `inspecto-ui/src/app/inspecto/api/components.service.ts` is a hand-maintained mirror of `WRITABLE_TYPES`
  with **nothing enforcing the mirror**. `rule-template` sat unmirrored for the entire life of the data-table
  Pro Max "save as rule" feature (fixed 2026-07-27): list/save/remove all 400'd against a real server while
  the mock served them happily, and the Registry's `allSettled` swallowed the failure so it never surfaced
  as anything louder than console noise. ⚠ Two lessons that generalize: a per-kind fan-out behind
  `Promise.allSettled` **hides a contract break as missing data**, and a docstring claiming a backend enum
  is "still closed / real backend later" is a claim to re-verify, not to trust — that one was ~15 kinds stale.

## Inspecto — operator console for the Inspecto file-processing platform

> Moved verbatim from `inspecto-ui/README.md` (2026-11-19, `INFO-RESTRUCTURE-1` W3), which is now a pointer here.


Angular SPA on the gamma-analytics admin template (Angular 21 + Angular Material + Tailwind),
with **ag-Grid Community** for grids, **Chart.js** for the dashboard charts and **AntV G6** for
the catalog metadata graph. Replaced the original DevExtreme app in v4.1 — the phase-by-phase
port plan was deleted with the other superseded migration docs (`8172de90`) and has no successor.

> The template is a licensed ThemeForest product (see `package.json` / `CREDITS`) — don't
> redistribute `src/@gamma/` outside this project.

### Layout

```
src/@gamma/            template core library (layouts, components, theming) — avoid editing
src/app/inspecto/      Inspecto layer: API services + interceptors, auth, grid kit, assist panel, chart host
src/app/modules/admin/ one folder per screen: dashboard, pipelines, pipeline-detail, jobs,
                       enrichment, catalog, diagnoses, config, assist, connect
src/e2e/               guarded backend-smoke spec (runs only when E2E_BASE_URL is set)
```

### Develop

```bash
npm install
npm start          # ng serve on :4204; /api/* proxied to ControlApi on :8080 (proxy.conf.json)
```

Start the backend with CORS for the dev origin (`-Dcontrol.cors=http://localhost:4204`), open
`http://localhost:4204/` and paste your operator token(s) on the Connect screen. There is no
username/password login — see the
[User Guide](../../../../USER_GUIDE.md).

### Test & build

```bash
npm run test:ci    # Vitest (jsdom) via @angular/build:unit-test
npm run build      # production build → dist/
## optional e2e smoke against a running backend:
E2E_BASE_URL=http://localhost:8080 E2E_TOKEN=dev npm run test:ci
```

In production the SPA is served same-origin by `ControlApi` (`-Dui.dir`); the deploy bundle is
assembled by `inspecto/package.ps1`. CI: `.github/workflows/ui.yml`.
