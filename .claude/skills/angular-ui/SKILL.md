---
name: angular-ui
description: >
  Senior Frontend Architect rules for the inspecto-ui Angular SPA. MUST be read and applied BEFORE
  generating or modifying ANY frontend artifact in inspecto-ui/ — components, panes, services, forms,
  styles, routes, tests, or docs. Encodes the project's feature-based architecture, the shared design
  system (status-badge / empty-state / skeleton / grid / connectivity-banner), the no-hardcoded-color
  CI guard, WCAG 2.2 AA + axe-core gate, API / error / connectivity / optimistic-UI patterns, signals
  state, and lazy routing. Trigger on any inspecto-ui change or new pane.
---

# Inspecto Frontend Architecture (Angular UI)

> Durable inspecto-ui conventions & gotchas (mode-toggle lens, lint:tokens guard, ag-Grid refresh/theme/
> virtualization, NG8011, auth-free client, CSV-blob download, live-tail, connectivity banner, optimistic
> mutate, G6 reuse): [docs/PROJECT_NOTES.md](../../docs/PROJECT_NOTES.md) §6.

You are acting as a **Senior Frontend Architect**. The goal is not merely to make things work, but a
frontend that stays **maintainable, scalable, testable, and consistent** over years. Assume this app
becomes enterprise-scale.

> **Before writing any code, read this file and the linked artifacts. After writing, satisfy the
> Definition of Done (§12).** The full a11y findings live in `docs/ui/accessibility-audit.md`; the
> living component gallery is the in-app `/design` route (`modules/admin/design-system/`).

## 0. Non-negotiables (the build breaks or review fails otherwise)

1. **No hardcoded colors** (hex / literal `rgb()/rgba()`), **no `levelClass`-style status-color
   helpers**, and **no status-tinted background fills** (`bg-{red|amber|green|…}-NNN` — the tell-tale of a
   hand-rolled pill/banner) in `src/app/inspecto/**` or `src/app/modules/admin/**`. Enforced by
   `npm run lint:tokens` (CI step in `.github/workflows/ui.yml`). Allowlist (the sanctioned color owners) =
   `theme/chart-tokens.ts` + `components/status-badge.component.ts` + `components/alert.component.ts` +
   `components/connectivity-banner.component.ts`. Use `--gamma-*` vars / Tailwind classes /
   `<inspecto-status-badge>` / `<inspecto-alert>`. (`text-*`/`border-*` tones are NOT flagged — legit inline
   emphasis / required-field asterisks.) Escape hatch: `ds-allow` on the line.
2. **Reuse the shared design system** — never re-roll a status pill, **inline alert/banner**, empty state,
   skeleton, grid theme, confirm dialog, or connectivity banner.
3. **Reactive forms only** with inline `<mat-error>` + `markAllAsTouched()` on invalid submit.
   ⚠ **An error only renders where its control renders** — `<inspecto-schema-form>`'s `validate()` therefore
   also OPENS the collapsed `optional`/`advanced` section holding an invalid control (2026-08-30). Before
   that, an invalid value behind a collapsed disclosure blocked submit with **nothing on screen to
   correct** — the button simply did nothing. Any host rolling its own disclosure has the same hole.
4. **A11y is not optional** (§6): one `<h1>` per page, `aria-label` on icon-only buttons, `:focus-visible`
   ring (never bare `outline:none`), WCAG 2.2 AA. Add an axe-core assertion to new component specs.
5. **No new dependencies** without explicit justification — keep the bundle lean.
6. **Never round-trip a raw secret** — secrets are `${ENV:…}` references; mask is `***`.
7. **Commit policy:** `feat:`/`test:`/`docs:` → `master` only; `fix:` → oldest supported branch then
   merge-forward. **Never push without an explicit user ask + confirming the merge-forward set.**

## 1. Philosophy

Maintainability over cleverness · Consistency over preference · Reusability over duplication ·
Explicitness over magic · Accessibility over aesthetics · Simplicity over abstraction ·
Incremental evolution without breaking existing features.

## 2. Technology assumptions (do not deviate without justification)

| Concern | Choice |
|---|---|
| **Framework** | **Angular 22** (framework 22.1.1, cli/build 22.1.3, material/cdk 22.1.2 — upgraded 2026-08-13), **standalone components** (no NgModules), new control flow (`@if/@for/@switch`) |
| **Language** | **TypeScript 6.0.3** (strict); prefer explicit return types; `inject()` over constructor params in new code. ⚠ **The TS version is pinned by the framework, not by us** — `@angular/build@22` peers `typescript ">=6.0 <6.1"`, so TS 7.x (which exists) is *refused*; do not "helpfully" bump it. ⚠ **`baseUrl` is gone** — TS 6 hard-errors on it (TS5101) and TS 7 removes it, so the three src-local import roots (`app/*`, `environments/*`, `@gamma`+`@gamma/*`) are explicit `paths` in `tsconfig.json`, resolved relative to that file. A NEW top-level folder under `src/` that you want to import bare needs its own `paths` entry — there is no longer a catch-all |
| **State** | **Angular signals** (local + service-held shared state) + **RxJS** for async/streams. **No NgRx / global store.** |
| **UI** | Angular **Material (M2)** + **Tailwind** on the gamma/Fuse shell; **ag-Grid 35** tables; **Chart.js** charts; **AntV G6** graphs |
| **Forms** | **Reactive** (`FormBuilder`/`FormGroup`/`Validators`) + inline `<mat-error>`. Template-driven `ngModel` is legacy — do not add it. **Config-attribute forms are schema-driven**: declare `AttributeSpec[]` (tier: required \| optional \| advanced) in `inspecto/component-model` and render with `<inspecto-schema-form>` (pilot: jobs `job-form.dialog`; demo: `/design`). Hand-build only genuinely bespoke sections (canvases, key/value arrays). **`tier` = visibility bucket; validation is separate** — set `required: false` on a `tier:'required'` spec for an always-visible-but-optional field (option sheets where every knob shows, none mandatory; e.g. `widget-option-attributes.ts`). For an inline duplicate-id guard, attach a `uniqueNameValidator` to the schema-form's name control (`errorFor()` renders the generic `duplicate` message) — see `job-form.dialog`/`dataset-editor`. **Three mandatory form behaviors (ui-design-review R2):** (1) bind `(submitted)="save()"` on every `<inspecto-schema-form>` so Enter submits; (2) every dialog holding a form wires `readonly requestClose = guardDirtyClose(this.ref, () => …isDirty(), this.confirm)` (`inspecto/dialog-dirty-guard`) and points Cancel at it — never `mat-dialog-close` on a form dialog (Esc/backdrop/Cancel then confirm before discarding); (3) an attribute referencing an existing entity (pipeline/job/dataset/…) is `type: 'autocomplete'` + a loader from `inspecto/components/entity-option-loaders` passed via `[optionLoaders]` — never a bare free-text `string` (suggestions assist, they don't constrain); a column-of-a-target field uses `columnOptionLoader('<siblingKey>')` (1-row `/db/table` probe of the store the sibling names, type-annotated labels; missing store ⇒ no list). The when-clause `ColumnMeta[]` idiom: probe the target per change (`dbColumnType` in `inspecto/query/query-columns` maps `/db` types), seed from fields the clause already references — never hardcode a record shape (see `decision-rule-form.dialog`; a fixed, non-probed field list is fine when the rows aren't a target's own records — see `alert-rule-form.dialog`'s ledger columns). `dependsOn` also accepts `{key, notEquals}` alongside `{key, equals}` (2026-07-18) for "visible/required except when X" — e.g. Expectation's `column` hides for `kind: 'condition'`; `dependsOnMatches` (`component-model`) is the one place to extend if a third variant is ever needed. **A spec set may be SERVER-AUTHORED (2026-07-26, C3/D6)** — the Findings panel fetches `GET /findings/{type}` and maps the served sections onto `AttributeSpec[]` (`findingsAttributes` in `objects/mail-model.ts`), so a deployment configures the fields without a rebuild; the backend validates the spec fail-closed at authoring time and skips a section whose `type`/`tier` is outside the union rather than drawing a broken control. ⚠ **Adding an `AttributeType`/`AttributeTier` now needs the backend `FindingsSpec` union widened too** (`FindingsSpec.TYPES`, `inspecto-engine/.../ops/findings/`), or the server 422s a section the renderer could draw — do it in the same change, as `type: 'list'` did. **`type: 'list'`** (D7, 2026-08-03) edits a **`string[]`** as removable shared `<inspecto-chip>`s: the text box is a *draft*, committed on Enter / + / blur, and an **empty list is written as `null`, not `[]`** so it reads as blank to `required` (agreeing with Angular's own `Validators.required`, which treats `[]` as empty) and to a host's delete-on-clear. Hosts coercing spec values on save must drop an empty array explicitly (see `node-config.dialog`). It does **not** cover lists of maps (`transform.route`'s `branches`), and `fieldSpecsToAttributes`' `TYPE_MAP` still skips served `FieldType.LIST` on purpose — wiring that would change what the parser dialog renders. Two host-side gotchas from that adoption: the schema form **owns its own `<form>` element**, so fixed sibling fields must live in a separate form (never nest); and it exposes **no `markPristine()`** — after a successful save a host clears dirtiness via `schemaForm().form.markAsPristine()` (reference: `objects/postmortem-panel.component`, a non-dialog detail-pane adopter). ⚠ **A `list` field's error must be rendered EXPLICITLY, never via `<mat-error>`** (2026-08-06): its `<input>` is the draft and is deliberately not bound with `formControlName`, so `<mat-form-field>` has no `NgControl`, never enters an error state, and its `<mat-error>` **can never fire** — every list error was invisible, `required` included. `schema-form`'s `listError(spec)` + a `<p class="text-warn text-xs" role="alert">` line is the fix and the pattern. **A spec asserting `errorFor()` returns the string passes while nothing reaches the screen** — assert the rendered `[role="alert"]` element. **Host-supplied per-key validators go through `[extraValidators]`** (`Record<string, ValidatorFn[]>`, 2026-08-06) for a domain rule a declarative spec cannot express; a validator returning **`{message: '…'}`** has that string rendered verbatim by `errorFor()`. Reference: `pipelines/measure-grammar.ts` (the engine's `count | agg(field)` measure grammar). The setter is re-applied on every `specs` reassignment, because a spec swap rebuilds every control and would otherwise drop the validators silently. **Whole-value tokens go through `[tokens]` + `[tokenSyntax]`** (2026-08-10, jobs step 13): `[tokens]` is a `Record<key, AttributeToken[]>` of per-field offers (the HOST filters — the renderer stays domain-agnostic) and each offered field grows an `<inspecto-token-picker>` suffix that **replaces the field's whole value**, or on a `list` **every entry**. `[tokenSyntax]` (a non-global RegExp) marks which values are tokens so they are **exempt from `pattern`** — without it the picker authors a value the form immediately refuses, because every field it matters on (date/instant/email) carries a preset pattern. Not offered on `number` (a native numeric input shows a `$`-token as blank), `select` or `boolean`. ⚠⚠ **`matSuffix` does NOT project from inside an `<ng-template>`** rendered by `*ngTemplateOutlet` — Material resolves projection **statically at the declaration site**, so the element lands in `…-infix` beside the input instead of the suffix slot. It still renders, so a spec asserting the button *exists* passes while the layout is wrong; **assert `closest('.mat-mdc-form-field-icon-suffix')`** and put one component (not a template outlet) per widget case |
| **Testing** | **vitest** via `@angular/build:unit-test` (jsdom) + `TestBed`; **axe-core** a11y via `expectNoA11yViolations`. Two recurring compile/run traps: (a) **one `TestBed.configureTestingModule` per test** — a helper that configures TestBed must be called **once** per `it()`; to assert several states, build one fixture and mutate its `@Input`s/`detectChanges()` between assertions (don't call the helper twice). (a2) **give a spec HOST an explicit `changeDetection`** — under Angular 22 an unspecified strategy is
   **OnPush**, so a test that mutates a host field AFTER the first `detectChanges` never re-renders and
   fails while the real screen is correct (cost a gate cycle on the option-picker's blank-option case,
   2026-08-23). Spec hosts use `ChangeDetectionStrategy.Eager`. (b) **no spread over `NodeListOf`** — use `Array.from(el.querySelectorAll(...))`, not `[...el.querySelectorAll(...)]` (TS2488 under the test tsconfig). Three more recurring traps: (c) mocking `MatDialog` on a component that renders `<inspecto-data-table>` needs `TestBed.overrideProvider(MatDialog, …)` (the table injects the real one) — a plain `{provide: MatDialog, useValue: …}` in the same TestBed is **silently ignored**, and the give-away is a `TypeError: Cannot read properties of undefined (reading 'push')` from `material/dialog/dialog.ts`, i.e. the real dialog ran; (d) specs touching `LensService` must clear `inspecto.currentLens` from localStorage in `beforeEach` (state leaks across specs); (e) the condition-group editor **mutates the bound `ConditionGroup` in place** — hosts must deep-clone before binding. |
| **Package mgr** | **npm** (`npm ci` in CI — keep `package-lock.json` in sync when adding deps) |

`graphify` indexes only the Java backend — for UI work, read the TS directly (graphify won't orient you).

## 3. Architecture — feature-based

```
src/app/
  inspecto/                 # SHARED / CORE (cross-feature). Never import a feature from here.
    api/                    # @Injectable({providedIn:'root'}) services + barrel index.ts
    components/             # shared UI: status-badge, empty-state, skeleton, chart, connectivity-banner, schema-form
    grid/                   # ag-Grid theme + helpers (index.ts)
    mock/                   # THE unified mock backend: MockStore (per-space, localStorage-persisted) +
                            # framework-free domain handlers + ONE mockApiInterceptor. New mock endpoints
                            # go here as handlers — never as a new per-feature mock interceptor.
                            # ⚠ A MOCK MUST NEVER BE MORE LENIENT THAN THE SERVER (2026-07-27, AGT-6a
                            # A5.3). A handler that accepts a shape the backend 422s, or returns a
                            # richer shape than the backend does, converts a hard failure into a
                            # passing rehearsal: the Pipelines `pipeline_author` adoption shipped
                            # BROKEN through two slices (flat args where the tool wants `flow`; a name
                            # string where the adapter wants the graph) and looked correct offline the
                            # whole time. When adding/editing a handler, diff its accepted args AND its
                            # result keys against the real tool/route — and pin the strictness in a
                            # `*.handler.spec.ts`, since the preview cannot catch what the mock permits.
                            # 🔴 QUERY PARAMS REACH A HANDLER AS `req.params`, NEVER IN `req.url`
                            # (2026-08-30): Angular's `HttpRequest.url` carries no query string, so a
                            # handler that parses `url.split('?')` sees EVERY request as param-less and
                            # answers its own 400/empty branch. Unit specs that stub the service never
                            # exercise the interceptor, so this is invisible until the running app --
                            # it cost a preview cycle on `/config/schema/derived`. Note `CONFIG_FILE`-style
                            # regexes ending `([^/?]+)$` imply otherwise; they are defensive, not evidence.
    theme/                  # chart-tokens.ts (the ONLY place canvas colors are hardcoded)
    testing/                # a11y.ts (expectNoA11yViolations)
    auth.service.ts, confirm.service.ts, …
  modules/admin/<feature>/  # FEATURES: standalone component(s) + .html + <feature>.routes.ts
  layout/                   # app shell (connectivity-banner mounts here)
  core/navigation/navigation-data.ts   # nav items (served client-side by NavigationService; the Fuse
                            # mock-api/ layer was removed in the M4 shell re-plumb)
```

- A feature = `modules/admin/<feature>/`: `*.component.ts` (+ `.html`), optional `*.dialog.ts`, and
  `<feature>.routes.ts` (`export default [...] as Routes`).
- **No cross-feature dependencies.** Share via `inspecto/`. A pane may be reused across routes via
  `ActivatedRoute.snapshot.data` (Cases/Issues = one `ObjectsComponent`).
- **Vendored** gamma/Fuse code (`src/@gamma/**`, `modules/auth/**`) is out of scope — don't restyle, audit, or guard it.

## 4. Component design

- **Standalone** + `ChangeDetectionStrategy.OnPush` (default for new components).
- Separate **container** components (inject services, hold state) from **presentational** shared
  components (`@Input`/`@Output`, no HTTP, in `inspecto/components`).
- **Always reuse:** `<inspecto-status-badge [value]>` (or `statusBadgeHtml()` in cell renderers),
  `<inspecto-alert variant=info|warning|error|success [title]>` (inline per-screen notices — writes-disabled,
  feature-unavailable, test-result banners; message is content-projected, announces `status`/`alert` by
  variant), `<inspecto-empty-state>`, `<inspecto-skeleton>`,
  `InspectoConfirmService.confirm()/confirmDestructive()`, the grid helpers, `<inspecto-connectivity-banner>`
  (the app-wide offline/backend-down strip, already mounted in the layout — distinct from `<inspecto-alert>`),
  `<inspecto-chip variant=outline|soft tone=neutral|primary [removable]>` (the shared tag/token/filter
  pill — never hand-roll a `rounded-full … text-xs` span; content is projected, `(removed)` emits on
  the optional ✕; a clickable filter toggle keeps its own `<button>` around the chip for
  `aria-pressed`/keyboard).
- **Authoring an enrichment → `<inspecto-enrichment-editor>`** (`inspecto/enrichment/`, W4b 2026-08-01).
  ONE shared references+transform editor for the companion `*_enrich.toon`; adopters: the Onboarding
  Enrichment stage and the Pipelines `enrichment` config pane (drawer since canvas-UX S2) — never fork it. Hosts own everything
  around it (reference options, derived-or-asked wiring via `ENRICHMENT_WIRING_ATTRIBUTES`, preview,
  save). ⚠ The save is always `POST /config/write type=enrichment` **then** `POST /enrichment`
  (register — no mtime hot-reload), NEVER a `*_job.toon` enrich job: the registered path is a
  partition-scoped per-batch recompute, a job is a full rescan. The node carries only
  `use: enrichment/<name>` — never mirror the config into `node.config` (split-brain). `partitions`
  lists are still unspecced and must travel verbatim through a save (speccing them is now an unblocked
  follow-up — `AttributeSpec` gained a `list` type in D7, 2026-08-03); a `transform_file` config is
  refused, not overwritten. ⚠ **The derived half is `enrichmentWiringDefaults`**
  (`inspecto/enrichment/enrichment-wiring.ts`, P6-c 2026-08-16) — input = the pipeline's Stage-1 output,
  output = `<base>/data/enriched/<name>`, trigger = `on_pipeline`. Both hosts derive through it (the
  Onboarding stage silently, the config pane as the seed of its asked form); never re-state the
  convention in a host. A host passes only facts it resolved itself, and passes **nothing** when the
  fact is ambiguous — a blank required field is honest, an invented store path reads zero rows and looks
  like it worked. A seed is **one-shot**: re-deriving it while the author edits clobbers the form.
- **Asking where files come from → `<inspecto-collector-config>`** (`inspecto/collector/`, 2026-08-04).
  ONE shared surface for the `collector:` block: the local-inbox/Connection toggle, the schema form over
  the shared `COLLECTOR_ATTRIBUTES`, Test connection, create-a-Connection in place, and the derived
  connector. Adopters: Onboarding's Collection stage and the Pipelines `acquisition` Collector pane —
  never fork it. Like the enrichment editor it has **no write path** — hosts read `value()`/`resolveConnector()`
  and save through their own route, because the two persisted shapes genuinely differ (a `collector:`
  block vs. a node's raw config plus a `use: connection/<id>` binding). ⚠ **Never ask for `connector`** —
  it is derived (local ⇒ `local`, else the picked Connection's own type) because
  `CollectorConnectors.forConfig` dispatches on `collector.connector` while handing that factory the
  profile named by `collector.connection`, without checking the two agree. ⚠ A host that swaps the spec
  list at runtime must carry the live values across the swap: reassigning `<inspecto-schema-form>`'s
  `specs` rebuilds every control from its declared default (the mode toggle silently wiped the form
  until this component started re-seeding). ⚠ On the Collector pane the way to UN-bind a Connection is the
  **mode toggle**, not blanking the text — an empty picker while still in Connection mode is a refusal,
  because a non-local collector with no Connection is the state that used to fail at run time.
- **Authoring how raw bytes become rows → `<inspecto-grammar-editor>`** (`inspecto/grammar/`, 2026-08-04).
  ONE surface for a **Grammar**: the built-in + served-plugin format catalog, the schema form over
  `parsingAttributesFor()`/the served `grammarSchema`, sample + sniff suggestion, Test parse, the
  table/tree result, and the fixed-width slice table. Adopters: Onboarding's Parsing stage, the
  Pipelines per-format Parse pane, and the custody-only Grammar dialog (bound/dangling/binary nodes). **No write path** — hosts persist, because one writes a `parsing:` block
  into a pipeline config and the other writes a reusable `grammar` component. ⚠ **Vocabulary is
  binding**: this authors a *Grammar*; ⛔ never "parser config"/"parse options" in UI copy
  (`GLOSSARY.md:272`). *Parser* = the engine that applies a Grammar; different concept.
  ⚠ Same spec-swap trap as the collector: switching format rebuilds every control from its default, so
  the editor re-seeds from the live form first. ⚠ The catalog **degrades** — the four built-ins render
  even when `GET /parsers` fails; never "fix" that into an empty list. ⚠ A saved plugin is identified by
  its **`ingesterClass` FQCN**, never the parser id, so re-selection can only happen off the served
  catalog — and `configuredIngester` is a SETTER because inputs are not bound when the constructor runs.
  **Since 2026-08-19 (delimited-grammar-properties U1–U5):** the delimited spec set renders as a
  **4-tab surface** driven by the frontend-only `AttributeSpec.tab` field (≥2 distinct tabs ⇒ a
  `mat-tab-group`, one schema-form per tab; anything else renders flat). ⚠ **The tab PANELS live
  OUTSIDE the mat-tab bodies, `[hidden]`-toggled** — MatTab instantiates body content on first
  activation, so forms inside would be invisible to `value()`/`validate()` until visited (silent
  loss on save; the R9 rule). Hosts project write-path content via the `[tabTypes]`/`[tabFiles]`
  slots (rendered below the flat form for untabbed formats). ⛔ Don't give `engine`/`strict_mode`
  spec defaults — a default materializes into every `value()` and mutates faithful copies of stored
  grammars. The portable template is the **Grammar CSV** (`inspecto/grammar/grammar-csv.ts`,
  `<pipeline>_parser.csv`) — Save-as-template is gone from both parse surfaces; unknown option keys
  are LISTED, never applied. **Since 2026-08-22 the "Start from a stored Grammar" picker is gone too**
  (operator ask), leaving exactly THREE ways to seed the surface — upload a sample file · paste sample
  text · import a Grammar CSV — rendered as ONE icon row: the panel owns the first two and the host
  projects the CSV pair through the sample panel's `[sampleActions]` slot. ⚠ The panel is only mounted
  when the host keeps a thread, so the CSV pair is declared as an `ng-template` and falls back to the
  Grammar row without one — never inline it twice. The columns table (`inspecto/schema/`) is ordered ①include ②# ③icon-only
  type menu ④name ⑤synonym (unique across synonyms ∪ names), with `[autoTypes]` disabling the menu in
  Auto mode. **Field types (2026-08-22):** `SCHEMA_TYPES` mirrors the engine's
  `SchemaFieldTypes.names()` — 20 DuckDB scalar types, `DECIMAL(p,s)` authored by precision/scale
  inputs the Type cell reveals (clamped 1‥38 / 0‥p so an out-of-range value never reaches the
  engine's gate). ⚠ **The two lists MUST stay identical**: the engine now REFUSES an unhonoured type
  at config load, so offering one here authors a config that cannot load — and the old four-entry
  list existed precisely because the engine's cast switch silently stored TEXT for anything else.
  `narrowToSchemaType` no longer collapses `BIGINT`→`DOUBLE` (lossy above 2⁵³); icons/hints and the
  type filter key on `baseSchemaType()`, so every `DECIMAL(p,s)` shares one entry.
  **Since 2026-08-21 (canvas-UX S4/S5):** the parse loop is host-legible — with `sampleMode: 'host'`
  the editor HIDES its own Test parse button (the host renders **Parse sample** in the shared
  `<inspecto-sample-panel>`'s chip row via its `parseLabel`/`(parse)` inputs, beside the state it
  changes); `showTab(id)` steers a tabbed set (no-op untabbed — the host reveals `'types'` on the
  FIRST derivation only, never on re-parses); a host mounting a tabbed pane in a dock calls the
  split handle's `ensureAtLeast(420)` (transient — never persists over the stored width). And the
  ONE remaining dialog host is custody-only: a generic `parser` whose config maps to a built-in
  frontend opens the per-format drawer pane instead, re-typed to `parser.<frontend>` on Apply with
  `csv_settings` folded into the seed (`parsing:` wins — the engine's own mergeParsing precedence).
  A parser is always FORMAT-SPECIFIC (operator, 2026-08-21) — never author the generic type.
  **Step icons:** `typeHeroIcon(type, category)` (`pipelines/pipeline-graph.ts`) is the ONE glyph
  vocabulary for palette/step-cards/insert-menu — one icon per Step TYPE, tinted by `categoryColor`
  (the glyph identifies, the color groups); category-glyph fallback for served/unknown types. In the
  Pipelines editor, **selecting a Step opens its configuration pane directly** (operator ask
  2026-08-22 — the THIRD flip of this call: opened 2026-08-21, reversed to Configure-first the same
  day, re-flipped the next; confirm with the operator before changing it again). The slim summary
  renders only where the pane cannot serve: the read-only lens and dialog-custody parse nodes. The
  inspector's `compact` mode is the identity strip inside the drawer, and since the re-flip it
  carries **Name + Description as always-visible fields committed on blur** (no pencil there) — the
  ONE rename path for definition-pane nodes, on every Step kind. Unmodelled node-config keys are edited by
  `pipelines/pipeline-extra-config.component` — the ACTUAL key as label, a control per stored value
  TYPE (boolean select / validated number / validated-JSON textarea / text), typed round-trip through
  `buildConfiguredNode({extras})`, untouched entries emitted as their ORIGINAL value reference; ⛔ no
  generic Key/Value grid, and adding keys only where the node type has no schema at all.
  ⚠ The segments editor stays HOST-side (projected via `[grammarExtras]`): segments need one schema
  `.toon` written per segment before the block that references them, which is a write path this
  component deliberately does not have. ⚠ **A host must never read a shared component through
  `@ViewChild` in its TEMPLATE** — the query is unresolved on first render, and content projected INTO
  that component evaluates in the host's context, so it reads too early. Take an `@Output` and mirror
  it into a host signal (`(pluginChange)`/`(previewed)` exist for exactly this).
- **Editing a pipeline graph → the canonical `*_pipeline.toon` round-trip** (`modules/admin/pipelines/`,
  W5 2026-08-01). The Pipelines editor is an editor **over** the canonical config, not a second model.
  Load with `PipelinesService.pipelineGraphRaw(name)` (`GET /pipelines/{name}/graph/raw` — the lifted,
  lossless editable graph), save with `savePipelineGraph(name, graph)` (`PUT /pipelines/{name}/graph` —
  the backend lowers it back over the existing file through the SAME `/config/write` gate). Create a
  new pipeline via the shared **`pipelineScaffold(name)`** (`inspecto/component-model`) → `configApi.write('pipeline', …)` → `registerPipeline`; delete via `configApi.remove('pipeline', name)`.
  ⚠ Node config values are the **raw config-file sections verbatim** — never a typed shape — so
  unmodeled keys survive a save. ⚠ A `PUT …/graph` 422 carries named **`refusals[]`** (`UNSUPPORTED_NODE`
  / `MULTI_SINK` / `NO_*`) under `error.details`; surface them, don't swallow them (the editor's
  `showRefusals`). ⚠ The old `*_flow.toon` authoring writes (`POST /pipelines/authored`, `PUT`,
  `/nodes`, `/edges`) are **retired** — grandfathered flows stay readable/deletable only. ⚠ The mock's
  TS lift/lower (`mock/pipeline-editable.ts`) must keep refusing exactly what the server does, or the
  offline preview greenlights a topology the backend 422s.
- **Labelling something with cross-entity tags → `TagAssignmentDialog`** (`inspecto/tags/`, D7). Kind-
  agnostic: `dialog.open(TagAssignmentDialog, {data: {targetKind, targetId, label}})`, where `targetKind`
  is `object` or a `ComponentStore.WRITABLE_TYPES` value. Adopting it on a new pane is a menu item, not a
  new dialog. ⚠ If the pane **renders the tags itself** (the widget card's chip row), it must also
  **reload on `afterClosed()`** — the chips are a server-derived projection of the edges, so without a
  refetch they stay stale. ⚠ **Not** the mail pane's `TagDialog` — that one is bulk/tri-state and writes the
  `attributes.tags` CSV projection; this one is single-target and writes assignment edges. Keep them
  separate: one dialog spanning both persistence paths re-creates the split-brain D7 phase 2 closed.
- **Inline AI authoring on a pane → `<inspecto-ai-assist>`** (`inspecto/ai-assist/`, AGT-6a A1). ONE shared
  surface; panes **adopt** it, never fork it. The pane names a non-mutating agent tool, passes its own
  context as `[args]` (so the operator re-states nothing), and gets back `(applyDraft)`; **the pane** then
  writes through its own existing validated route — the surface has **no write path at all**, so the human
  stays the audited actor. `[current]` is the diff baseline (null on create). It self-gates on
  `LensService.canAuthorWorkbench()` and latches a **503** into a disabled, explained affordance (other
  errors toast and stay retryable), so a pane never hard-fails when the intelligence module is absent.
  Backend: `POST /agent/tools/{name}` — **mutating tools are refused 403**; a draft carrying findings is a
  **200**, not an error. Result-shape adapters + the diff are framework-free in `ai-draft.ts`.
  ⚠ Four of the five L1 tools take **structured** input, not natural language — don't build an NL box on
  them without scoping the model hop (BACKLOG AGT-6a · A5). Details + the per-pane gotchas (target-vs-table
  vocabularies, the `kind`+`min`/`max` same-patch trap, the Queries `type` switch):
  `docs/okf/frontend/features/inline-ai-authoring.md`.
- **"Explain this screen" on a pane → `<inspecto-ai-explain>`** (`inspecto/ai-assist/`, AGT-6a A4). The
  read-only sibling of the above: one icon button for the pane's header action row —
  `<inspecto-ai-explain screen="Pipelines" [terms]="['Pipeline','Step','Trigger']" />` — that resolves each
  term from `docs/GLOSSARY.md` via the non-mutating `glossary_lookup` (falling back to `docs_search`
  citations, and explaining itself on 503). Everything renders in a dialog, so adopting it can't disturb
  the header. Three rules: **the PANE declares the terms** (never a free-text box — that is a docs search
  engine, and it re-states what the screen already knows); terms are **canonical** spellings, never banned
  synonyms; and it is **NOT gated on `canAuthorWorkbench()`** — it has no write path, and a Business-lens
  user is who needs it most, so don't "make it consistent" with the authoring surface. Adopted on 12 panes.
  ⚠ A new pane's terms must also be added to the **subset** `GLOSSARY` map in `mock/handlers/agent.handler.ts`
  (verbatim from the real file) or they can't resolve offline.
- **"Why is this red" on a ROW → `<inspecto-ai-status>`** (`inspecto/ai-assist/`, AGT-6a A4-status). The third
  family member — `<inspecto-ai-assist>` authors, `<inspecto-ai-explain>` defines vocabulary, this one reports
  **deployment state**: `<inspecto-ai-status [label]="row.name" [pipelineId]="row.name" />`, or pass
  `[correlationId]` instead to get the exact causal chain rather than a time window. Three non-mutating tools
  (`status_get` · `signal_timeline` · `timeline_build`), so again **no new backend capability**, and again
  **NOT gated on `canAuthorWorkbench()`**. ⚠ **Not a breadth win — do NOT sweep it onto every pane**: it needs
  a real entity id, so it belongs only on operational panes that have one. Adopted on **four**: Alerts
  (reference) + Processing Status via `[rowActions]`; Events/signals, which prefers `correlationId` and uses
  `visible` to **hide** the action on a row carrying neither correlation id nor pipeline (an affordance that
  can only answer "nothing" is worse than none); and the Incidents/Cases `object-detail` header, shown only
  when `obj.correlationId` is set and getting the timeline half **alone** — an Incident has no pipeline, so
  there is no live state to read (the independent degrade working, not a gap to fill with a pipeline
  lookup). ⚠ Offline it answers from the **mock store's own ledger**
  (`agent.handler.ts` now takes `(req, store)`) — an empty ledger honestly answers "nothing was recorded"
  rather than inventing activity. Details: `docs/okf/frontend/features/inline-ai-authoring.md`.
  ⚠ **A `[rowActions]` column on a wide grid is horizontally virtualized out of view** — ag-Grid reports it
  as displayed while no header/button is in the DOM. Add `[pinActions]="true"` (this cost a preview cycle on
  the Alerts grid, whose 7 columns already fill the viewport).
- **Reading a Dataset's rows → `DatasetRowsService`** (`inspecto/viz/dataset-rows.service.ts`, split S2
  slice B 2026-08-14). ONE seam for "what does this `sourceName` resolve to": `rows(ds, limit?)` (live
  `GET /db/table`, or `POST /db/query` with the dataset's Query Core model compiled by `compileSql`;
  offline the `inspecto/mock/sample-sources.ts` page filtered by `evaluateRows`), `sql(store, text)` for
  authored SQL, `columns(ds)` for declared-else-1-row-probe columns, `stores()` for the space's store
  list (`/db/catalog`, business groups only — an `ops:*` table needs a group id a `sourceName` can't carry). ⛔ **Never write
  `SAMPLE_SOURCES[ds.sourceName]` in a feature again** — that synchronous lookup is why Studio showed
  sample data live. ⚠ **A result is a PAGE**: honour `truncated` and surface `error`, never render an
  empty grid for a store that 404'd. ⚠ It is async, so a `computed()` that read rows becomes an
  effect-fed `signal` — watch the ordering that creates (a saved view that patches a form AFTER an async
  column-pick will be clobbered; resolve the pick explicitly with `{emitEvent: false}` first). ⛔ Do NOT
  convert a fold that is already the **offline arm** of a server-first path (Link Analysis / Geo
  projections, `ReconExecService`) — those call `sampleDatasetRows` deliberately, after the server call
  failed. ⚠ `DatasetResultService.run` takes rows as a **thunk** for the same reason: its live branch
  never reads them.
- **Asking for ONE choice → `<inspecto-option-picker>`** (`inspecto/components/option-picker.component.ts`,
  operator ask 2026-08-22). ⛔ **`mat-select` is no longer the way to ask for a single choice.** The picker
  is a `ControlValueAccessor`, so `formControlName` / `[ngModel]` bind exactly as `mat-select` did, but the
  choices open in a **dialog**: full-length labels, an optional per-option `hint`, and a filter box once
  there are ≥8 options. `schema-form`'s `type: 'select'` renders it, so **every schema-driven form in the
  app already asks this way** — a spec set needs no change. ⚠ It renders its **own label and error line**
  and must NOT be wrapped in a `mat-form-field`: a form field derives error state from an `NgControl` on
  its projected input, so a `<mat-error>` beside it could never fire (the same trap `type: 'list'` hit).
  The error is an explicit `role="alert"` line — **assert the rendered element**, never that a getter
  returned the string. ⚠ Two behaviours are load-bearing: a **dismissed** popup writes nothing (a Cancel
  that nulled the control would destroy a stored value), and a value matching **no offered option is shown
  verbatim** rather than as unset (a stored config can name a choice this deployment no longer serves, and
  blanking it invites an accidental overwrite). ⛔ Don't build the trigger from a `mat-*-button` — Material
  lays out button content internally, so a full-width trigger came out with the chevron at the far left and
  the value jammed against the right edge. **The trigger is a compact PROPERTY ROW (operator ask 2026-08-23):**
  label and current value share ONE line — the value (or its `Select` placeholder) plus a small chevron IS the
  borderless trigger; no boxed control below the label, so dense option sheets (the Grammar 4-tab form) stay short.
  Don't reintroduce a bordered full-width trigger; the spec pins the borderless one-row class contract.
  **Still dropdowns, deliberately:** table-cell and grid-toolbar
  selects (the mapping-rules rows, the columns table's type filter and page size) — a modal per cell is
  worse than the dropdown it replaces. The remaining ~130 hand-rolled `mat-select`s outside those two
  categories are an unswept follow-up, not a decision against the picker.
  🔴 **A picker change does NOT bubble a click through its host** (2026-08-29): the choices open in a
  MatDialog, which the CDK attaches to `document.body`, so a pane that derives dirtiness from
  `@HostListener('click')` never learns about the pick — Apply stayed greyed out over a choice the
  operator had just made, on every `select` in the three `pipelines/*-definition` panes. Those now also
  carry `@HostListener('document:click')` (the re-derive already short-circuits unless the value
  transitions). **Any host that polls state on interaction has this hole for every overlay-hosted
  control** — dialogs, menus, autocomplete panels. A unit test that dirties the form directly passes
  either way; this was only visible by driving the picker in the preview.
  ⚠ **A blank-valued option is a real, named choice, not "unset"** (2026-08-23): the picker shows that
  option's LABEL rather than the placeholder, and the popup ticks it when the bound value is null. This
  is the idiom for *"the engine's own default, authored as no key at all"* — the Grammar editor's Parse
  engine ▸ Auto — because a spec `default` is banned there (it materializes into every `value()` and
  mutates faithful copies of stored grammars), and the editor drops a blank whose default is blank.
  ⛔ Don't "fix" such a field by giving it `default: 'auto'`.
- **Asking for an IANA time zone → `inspecto/schema/time-zones.ts`** (2026-08-29). `ianaTimeZones()` /
  `timeZoneOptions(blankLabel)` are the ONE vocabulary behind both source-zone surfaces (the Grammar
  editor's Types tab `source_timezone`, the columns table's per-column zone). ⛔ Never hand-roll a zone
  list: the engine refuses an unknown zone at config load (DuckDB errors hard at run time and `TRY()`
  does not catch it), and it refuses **offset forms** (`+05:30`, `Z`) that `Intl` and `ZoneId` both
  accept. ⚠ It is a SUGGESTION list, not the gate — the runtime's ICU set and the JVM's disagree on
  spellings in BOTH directions (measured: this ICU has `Asia/Calcutta`, not `Asia/Kolkata`; it omits
  `UTC` entirely, which we add back), so a stored zone must always render verbatim and survive a save
  untouched.
- **Tabular surfaces → `<inspecto-data-table [tier]>`** (`app/inspecto/data-table`), the consolidation of
  every ag-Grid host. Tiers: **mini** (grid) · **standard** (+ icon-only toolbar: column chooser · search ·
  CSV export) · **pro** (+ an **icon-toggled CodeMirror SQL editor — hidden by default** — that runs real SQL
  offline via **AlaSQL** over the loaded rows and re-renders the grid, + an icon-toggled filter builder that
  regenerates the SQL; both toolbar toggles mirror each other). Both panels also expose an **opt-in "Run on
  server"** action: set `[serverRun]="true"` and handle `(runOnServer)="…"` — the host runs that SQL against
  its own backend and feeds results back via `[rows]` (the data-table clears its client-run overlay first).
  Default off, so the ~4 client-side-only hosts (alerts/events/audit-logs/enrichment) are unaffected; the
  Data Browser wires it to `POST /db/query`. **Honest paging is opt-in via `[serverPage]="true"
  [hasMore]="…" (loadMore)="…"` (ui-design-review R6)**: when the host's fetch came back a full page,
  the table renders a "Showing N — there may be more" strip whose Load more emits `(loadMore)`; the host
  fetches the NEXT page (`offset = rows already loaded`, page-size `limit`) and APPENDS it — true offset
  paging, no refetch from 0 (never silently cap a list — adopters: object-mail, audit-logs, events; any
  full refetch — filter change, refresh, live-tail tick — resets to page 0 and re-derives `hasMore` from
  `page.length >= pageSize`; data-browser still uses its stats line + the older widen-and-refetch idiom). **Layout persistence is opt-in via `[stateKey]="'<pane>'"`**
  (data-table AND tree-table): column widths/order/visibility/sort, quick search, chooser selection (and
  the tree-table's expanded set) survive navigation/reload per space (`GridStateService`,
  `inspecto.grid.<space>.<key>` in localStorage; "Reset layout" lives in the column chooser). Give every
  *routed pane's main* table a stateKey (unique per pane; dynamic when one component serves several
  datasets — e.g. `'mail-' + type`, `'db-' + table`); skip it for embedded mini-grids in dialogs.
  **Keyboard layer (document-level, review R3):** `/` opens + focuses the first visible searchable
  table's quick filter (built in, no opt-in); `[keyNav]="true"` adds j/k row focus, Enter = `(rowClick)`,
  x = toggle selection — give it to detail-feeding triage lists (pilot: object-mail). Typed input and
  open overlays are exempt; arrows stay ag-Grid-native. Add new global bindings to the `?` shortcuts
  dialog's `SHORTCUTS` list. · **proMax** (+ "save
  as rule" → a parameterized `:fieldValue` template via the rule store). Reusable logic is framework-free in
  `core/` (csv · quick-filter · column-resolve) and `sql/` (`runSql` lazy-loads AlaSQL; `SqlHistoryService` =
  per-source history/favorites in `localStorage`; `codemirror-setup.ts` themes CM6 entirely with `--gamma-*`
  vars). The CM editor is `@defer`-loaded so mini/standard never pull CodeMirror; **a `@defer` block means
  the spec must `await TestBed.compileComponents()` before `createComponent`**. New deps (justified, lazy):
  `alasql` (offline SQL — also add to `allowedCommonJsDependencies`), `codemirror` + `@codemirror/*` +
  `@lezer/highlight`. Don't re-roll a bare `<ag-grid-angular>` host or a second SQL engine. `[rowActions]`
  appends an actions column; add `[pinActions]="true"` to keep it visible when many data columns overflow
  into a horizontal scroll (pins the `actionsColumn` right — default off so narrow grids are unaffected).
  ⚠ **`<inspecto-query-panel>` is NOT the row-preview table** (2026-07-30) — it is the query *builder*, and
  it belongs only on the two hosts that consume its `(queryChange)` output (Studio ▸ Queries, Dataset
  editor). Five panes were mounting it `[source]`-only as a dumb preview grid, which is exactly why those
  previews looked unlike every other table in the app; they are now data-tables — `tier="pro"` on the
  parsed sample (onboarding ×2 + Parser dialog), plain `standard` on the rejected rows and enrichment
  preview. **A row preview that wants SQL gets the pro tier + a `sourceName`** — the editor seeds
  `SELECT * FROM "<sourceName>"`, so the user queries the rows without knowing any table name. Give pro
  only where exploring the rows is the point; never re-mount the builder to get a table.
- **ag-Grid internals** (used inside the data-table, rarely direct): `app/inspecto/grid`
  (`INSPECTO_DEFAULT_COL_DEF`, `actionsColumn`, `fmtDateTime`, `InspectoGridThemeService`, `noRowsOverlay`).
  Bind `(firstDataRendered)` AND `(rowDataUpdated)` → `refreshActionsCells($event)` (actions column) or
  `refreshAllCells($event)` (every column) or the cells never materialize.
  **Gotcha:** ag-grid-angular 35 skips cell-renderer materialization on the *initial* render — not just the
  actions component but **any `cellRenderer`** (incl. string-returning ones like the `statusBadgeHtml`
  severity/level/status badges), which stay empty until the next data change. `refreshActionsCells` only
  force-refreshes `['actions']`; use **`refreshAllCells`** (`api.refreshCells({force:true})`) on grids with
  non-actions renderers. **The shared `<inspecto-data-table>` already binds `refreshAllCells`**, so every
  host's badge columns render regardless of tier (and survive the pro-tier AlaSQL re-run — `resultColumns()`
  reuses the host's explicit `ColDef` for matching fields) — bare direct hosts must do this themselves.
  **Module registration is TRIMMED (C3, 2026-07-21):** `grid/index.ts` registers an explicit 12-module set,
  not `AllCommunityModule` (grid chunk −200 kB). A new grid feature (e.g. number/date filters, CSV export,
  editing, row drag) needs its community module ADDED to that list — in dev builds `ValidationModule` is
  registered, so the missing module fails loudly by name (ag-Grid error 200) in test:ci/preview. Never
  "fix" that error by restoring `AllCommunityModule`.
- **G6 graph hosts — two patterns.** *Read-only* (`catalog/graph-view.component`) rebuilds the `Graph` on every
  data/scheme change — fine for static views. *Interactive editing* (`flows/flow-editor-graph.component`, T32) keeps a
  **persistent** `Graph` and mutates it in place (`add/remove/updateNodeData` + `draw()`), rebuilding only when the
  subject changes (an `@Input graphKey`) so user layout survives edits; node-add = HTML5 drop-to-add, edge-add =
  two-click (avoid G6 v5's `create-edge`), delete = a host `keydown`. Both reuse `canvasTheme()` + `nodeColor/nodeShape`
  (never hardcode canvas colours). The read-only host defaults to a `62vh` page band; pass `[fill]="true"` inside a
  full-height flex column (Link Analysis studio) to grow into the remaining space — its `ResizeObserver` re-sizes the
  canvas live when collapsible side panes open/close. Further opt-ins on the read-only host: `[display]`
  (`GraphDisplayOptions` — label toggles + per-kind colour/shape/pattern/size overrides, what Link Analysis persists with a saved view),
  `[tooltips]="true"` (G6 hover tooltip plugin), `(edgeClick)`, `fitView()`, and `[layout]` (`GraphLayoutId | null`;
  `null` = the default LR `antv-dagre`, so the 4 existing hosts are byte-identical — `GRAPH_LAYOUTS` maps the ids to
  G6 built-ins via `layoutConfig()`, cast to `LayoutOptions` at the call boundary; the 3 tree layouts gate on the pure
  `isForest()`). **G6 can't instantiate in jsdom** —
  unit-test on the empty/no-graph path (canvas not mounted) for axe, and the editing logic via the component's methods
  with a mocked host. Pure graph algorithms (`inspecto/graph/graph-analysis.ts` — path/centrality/`detectCommunities`+
  `louvainCommunities`/`matchPattern` motif search/…) are the testable seam: hand-built fixtures, no canvas.
- **View-bound widgets (geo Phase 4a).** A Studio Widget is either **dataset-bound** (vizType + dataset +
  channel mapping → QuerySpec) or **view-bound** (`VizMeta.viewKind` set on the plugin; the binding is
  `WidgetConfig.viewId` → a saved `geo-map-view`/`link-analysis-view` Component; no dataset, no query run,
  the dashboard cross-filter/drill don't apply). Heavy component-render hosts register an **async loader**
  via `registerVizComponent(key, () => import(...))` (`inspecto/viz/viz-components.ts`) — never add
  MapLibre/G6 to `viz-render`'s static `COMPONENT_BY_KEY`. Reference wrappers:
  `studio/geo-map/geo-view-widget.component`, `studio/link-analysis/link-view-widget.component`.
- **Ask the minimum (product-owner rule, 2026-07-02):** a form asks only what the action needs NOW;
  everything else is on-demand. Concretely: **create flows name the artifact at SAVE time** (a save step
  asks Name — pre-filled `<type>_<host>`-style, unique, = the id — plus optional Description) and
  **rarely-used sections (tunnels/proxies/advanced) start collapsed even on edit**, with a chip hinting
  what's configured. Reference: `app/inspecto/connections/connection-form.dialog` (two-step create,
  collapsed Routing; relocated from the connections feature to shared `inspecto/` 2026-07-16 so the
  onboarding create-in-place can open it cross-feature).
  Since ui-design-review R9 the job / expectation / alert-rule / decision-rule create dialogs follow the
  same two-step pattern (a `step` signal + a `saveForm` asked only at save time, id pre-filled from the
  config via `suggestedName()`; the config step is `[hidden]`-wrapped — never `@if`'d — so schema-form
  ViewChilds survive the step switch). New create dialogs must not ask the immutable id up front.
- **Resizable / maximizable dialogs → `[inspectoDialogResize]`** (`inspecto/components/dialog-resize.directive.ts`,
  2026-08-05): put the attribute on the dialog's `mat-dialog-title` (`#chrome="inspectoDialogResize"`). It appends a
  bottom-right drag grip (pointer + arrow keys, a11y-labelled) and tags the pane `inspecto-dialog-resizable`
  (content max-height raised to ~80vh; once sized/maximized, `inspecto-dialog-sized` drops the clamp so content
  flexes instead of scrolling — styles in `styles.scss`). Big dialogs add a maximize icon button in the title row
  calling `chrome.toggleMaximize()` / reading `chrome.maximized()`; maximize reuses the `.dialog-fullscreen`
  panel class. Never re-roll a per-dialog fullscreen toggle (the grammar dialog's local one was the extraction
  source). ⚠ Don't put an inline `[style.maxHeight]` on `mat-dialog-content` in an adopting dialog — the inline
  style beats the sized/maximized CSS and pins the scrollbar back. Adopters: the surviving Pipelines dialogs (canvas-UX S2 retired NodeConfigDialog — canvas node config lives in the Properties-dock definition drawer, NOT a dialog; do not add a new per-node config popup).
- **Resizable panes → `[inspectoSplit]`** (`inspecto/components/split.directive.ts`, R7): put it on the
  separator div between two panes (`inspectoSplit="<stateKey>"`, `#h="inspectoSplit"`, min/max/
  defaultWidth, `pane="right"` when the controlled pane sits right of the handle) and bind the pane's
  `[style.width.px]="h.width()"`. Persists per device at `inspecto.split.<key>`; `role="separator"` +
  arrow-key a11y built in; hosts add `aria-label` + responsive classes. Never re-roll pointer resize.
  ⚠ **Keep the handle MOUNTED while its pane is collapsed** (`[class.hidden]`, never `@if`) or the
  `#h="inspectoSplit"` ref the pane's width binds to stops resolving. Collapse-to-rail idiom (Pipelines
  editor, 2026-08-02): `[class.w-10]="!open()"` + `[style.width.px]="open() ? h.width() : null"` — the
  null drops the inline width so the rail class applies.
  ⚠ **A dock that MAXIMIZES must leave the flex flow, never widen to `width:100%`** (S0/D9,
  2026-08-21). Its siblings — the opposite dock and both split handles — are still-mounted `shrink-0`
  children of the same `overflow-hidden` row, so a 100%-wide dock overflows the row by exactly their
  width and its far edge (the footer buttons) is clipped off-screen. Render it `absolute inset-0 z-20`
  over the row instead (the row gets `relative`), dropping the inline width binding so `inset-0` owns
  the geometry; un-maximizing restores `h.width()`. Found in the Pipelines definition drawer, which
  shipped clipped in U5. **jsdom cannot measure layout**, so the unit test pins the class contract and
  the real proof is the preview: assert zero row overflow AND that the button hit-tests to ITSELF
  (`document.elementFromPoint`) — a rect inside the viewport says nothing about what covers it, and a
  *disabled* button legitimately hit-tests to its parent, so dirty the form before believing a miss.
- **Full-bleed editor shells (Pipelines edit mode, 2026-08-02).** ⚠ **The admin shell scrolls at document
  level — `body` is `min-height:100%` and every ancestor is `min-height:auto`, so NOTHING above a routed
  pane is viewport-bounded.** A pane that wants IDE chrome (docks that give space back to a canvas) must
  bound *itself* — `style="height: calc(100dvh - 120px)"` (classic layout = 64px header + 56px footer) —
  or `flex-1` children size to content and an opening bottom dock grows the page instead of shrinking the
  canvas. This looks fine until the second dock opens, which is why it survives a casual preview check.
  Link Analysis dodges it with a `62vh` band; a true full-bleed editor cannot.
  ⚠ A G6/Chart/Map host inside such a shell needs its **own `ResizeObserver`** — side docks resize it and
  the libraries only track the window (`pipeline-editor-graph.component.ts` learned this the same day).
- **Detail-over-list panes (R5):** when a routed detail should NOT destroy its list (runs, jobs), use ONE
  matcher-based route config covering both `''` and `':name'` (same config ⇒ router reuses the list
  component ⇒ scroll/filters survive), read `paramMap` into a `detailName` signal, and mount the detail
  as an `[embedded]` side panel behind an `inspectoSplit` handle; close = navigate back to the list URL.
  Reference: `runs/runs.routes.ts` + `runs.component`. Settings uses the same matcher for `:section`.
- Detail pages carry a breadcrumb (list → id) — use the shared `<inspecto-breadcrumb [listLink] [listLabel]
  [current]>` (`inspecto/components/breadcrumb.component.ts`), never hand-roll the trail. Reference
  everything live at `/design`.

## 5. Styling

- **Tailwind utilities + gamma `--gamma-*` CSS vars** (scheme-aware, set on `body.light`/`.dark`). No
  hardcoded colors (§0.1).
- **Status/severity/level color → only** `status-badge.component.ts`. **Canvas color → only**
  `theme/chart-tokens.ts` (Chart.js can't read CSS vars).
- **ag-Grid theme → only** `InspectoGridThemeService` / `GAMMA_GRID_PARAMS`. Never bare `themeQuartz`.
- Editing the theming plugin (`@gamma/tailwind/plugins/theming.js`) does **not** hot-reload — restart the
  dev server and verify via `getComputedStyle(body).getPropertyValue('--gamma-…')`.
- ⚠ **The breakpoint scale is gamma/Fuse's, NOT stock Tailwind** (2026-08-06): `sm:`=**600px**,
  `md:`=**960px**, `lg:`=**1280px** (stock is 640/768/1024). So `md:grid-cols-2` does nothing inside a
  ~900px dialog on a 1000px-wide window — the two columns silently stay stacked, and every unit test
  still passes because jsdom never evaluates the media query. **Inside a dialog, reach for `sm:`**; save
  `md:`/`lg:` for routed full-width panes. Caught in-preview on the mapping editor's side-by-side row
  diff; confirm a responsive class actually fired with
  `getComputedStyle(el).gridTemplateColumns` rather than by eyeballing a screenshot.

## 6. Accessibility — WCAG 2.2 AA

- One semantic `<h1>` per page. `aria-label` on every icon-only button. Never `outline:none` without a
  `:focus-visible` ring. `scope` on table headers. Status conveyed by **text + color**, never color alone.
- Reactive forms surface errors inline via `<mat-error>`. Respect `prefers-reduced-motion`.
- Async / degraded states announce via `role="alert"` + `aria-live` (see the connectivity banner).
- **Automated gate:** add `await expectNoA11yViolations(fixture.nativeElement)` (`inspecto/testing/a11y.ts`)
  to new component specs. Runs in CI via `npm run test:ci`. `color-contrast` + page-level rules are
  excluded in jsdom — contrast is covered by the token guard + the manual audit.
- Known/deferred findings: `docs/ui/accessibility-audit.md` (e.g. F1 chart `<canvas>` text alternative).

## 7. API integration

- Service per resource in `inspecto/api/`, `@Injectable({providedIn:'root'})`, `private http = inject(HttpClient)`,
  return `Observable<T>`. Build URLs with `apiUrl('/path')`, query with `toParams({…})` (both `api-base.ts`).
  Declare interfaces inline. **Export from the `index.ts` barrel** (`import { X } from 'app/inspecto/api'`).
- **Personal edition stays auth-free; the Standard edition adds an opt-in session layer (W6d, 2026-07-07).**
  The core is still auth-free by default — no per-screen auth, no `canControl`/`canAssist` gating, no bearer, no
  route guard *effect* on Personal. **Do NOT hand-roll per-screen auth or bring back the old `/connect` token
  screen / vendored `modules/auth/` template.** What exists now is a single edition-switch driven by
  `GET /bootstrap` `features.authMode`: `SessionService` (`inspecto/api`, mirrors `SpacesService` — signals
  `authMode`/`authenticated`/`capabilities` + in-memory access token; `token()`; `loginRequired()`), the
  `authInterceptor` (attaches the bearer + does one silent `/auth/refresh` on 401 — **a pass-through unless
  `authMode()==='oidc'`**), and the `authGuard` on the shell route (**returns `true` unchanged unless
  `loginRequired()`**). The flow is **backend-mediated (BFF)**: the SPA never holds a refresh token — it does
  Auth-Code+PKCE (`inspecto/api/pkce.ts`), then the backend `/auth/exchange|refresh|logout` routes keep the
  refresh token in an httpOnly cookie and return only a short-lived access token (in memory). Guest screens:
  `modules/admin/session/{sign-in,callback}.component`. **The offline switch (binding constraint): keep it
  working with no backend** — the mock `auth.handler` answers `/bootstrap` as Personal by default
  (`environment.mockAuthMode:'none'` → no login, boots straight to the app, byte-for-byte as before); set
  `mockAuthMode:'oidc'` (or `localStorage['inspecto.mockAuthMode']='oidc'`) to exercise the whole sign-in UX
  offline (the mock mints fake tokens, `auth.mock=true` skips the real IAM redirect). Real deployments read
  `bootstrap.auth` (or fall back to `environment.oidc`) for the authorize URL + public client id (no secret —
  public PKCE client). `/bootstrap` + `/auth` are server-global (exempt in `spaceInterceptor`).
- **Downloads** (CSV/blob) go through `HttpClient` (responseType `blob`/`text`) + an object
  URL — a plain `<a href>` to the API skips the token and 401s.
- **Live tail / polling** uses `visibleInterval(ms)` (pauses when the tab is hidden); unsubscribe in
  `ngOnDestroy`/`takeUntilDestroyed`.
- **Secrets:** references only (`${ENV:…}`); never echo a raw secret back to the server (`***` sentinel
  means "keep stored value").
- **Multi-space scoping (do NOT re-roll per feature):** the server hosts many isolated spaces. `SpacesService`
  (`inspecto/api`) holds the active space as a signal (`currentSpaceId`, restored from `localStorage` in its
  ctor) and the global `spaceInterceptor` rewrites `/api/<path>` → `/api/spaces/<id>/<path>` for every feature
  call — so feature services stay space-agnostic. It no-ops when there's no active space (single-tenant,
  byte-identical) and exempts server-global/already-scoped paths (`/health`,`/ready`,`/metrics`,`/spaces*`).
  Detect the mode via `GET /spaces/_meta` → `{multiSpace}` (never infer from the space-list length). The header
  `space-switcher` and the `modules/admin/spaces` admin view are the only space-aware UI; switching reloads.
- **Persona lens ("View as") + the Capability seam:** `LensService` (`inspecto/api`) mirrors
  `SpacesService`'s shape (signal + `localStorage` restore/persist) for the three lenses
  (business/builder/ops — `docs/GLOSSARY.md` §1-A). A lens is a **UI-side annotation, never a permission**
  (Lens ≠ Role — `docs/superpower/rbac-abac-plan.md`). Panes gate on
  the **named capability signals** — `lens.canAuthorWorkbench()` (Workbench create/edit/delete),
  `lens.canOperateRuns()` (Runs trigger/pause/reprocess), `lens.canTriageRequirements()` (C1 triage) —
  **never on `readOnly()`/lens identity**; add a new named capability per distinct authorization question.
  **Since RBAC R2 (2026-07-23)** each capability = the lens derivation **∧, under `authMode 'oidc'`, the
  matching grant in `SessionService.capabilities()`** (the effective set `/bootstrap` reports after
  server-side role/Access-Profile enforcement); Personal stays pure honor-system. `currentLens` is now a
  computed: the persisted preference constrained to `lens.allowedLenses()` (Business always; Builder ⇐
  `canAuthorWorkbench`; Ops ⇐ `canOperateRuns`) — the switcher iterates `allowedLenses()`, and a spec
  that stubs `SessionService` must include `authMode: () => 'none'` (+ `capabilities: () => []`) or
  every LensService capability read throws. Offline dev switch for constrained subjects:
  `localStorage['inspecto.mockCapabilities'] = 'canOperateRuns'` (with `inspecto.mockAuthMode='oidc'`).
  Default heuristic: operational actions (run-now, enable/disable, dry-run, activate) stay available in
  every lens — gate only true config-authoring — *unless* the plan explicitly says otherwise for a pane
  (Runs is "read-only observe" for Business, hence `canOperateRuns`). Gate the **mutating method**
  (defense-in-depth), not just the button, on canvas/drag surfaces. Unlike the space switcher, switching
  lens does **not** reload — capabilities are computed signals read directly in templates. Header
  `lens-switcher` mounts next to `space-switcher`, classic layout only.

## 8. Error handling

- **Global `errorInterceptor`:** **`status 0`** → `ConnectivityService.reportUnreachable()` (drives the
  persistent banner) — **do not** add a per-screen "unreachable" toast. Any success → `reportReachable()`.
  **`503` is per-screen** (e.g. assist disabled), NOT backend-down. (No 401 handling — the app is auth-free.)
  ⚠ **A 503 from an optional-module route is an EXPECTED deployment state, never an error toast** —
  latch it into an explained in-place `<inspecto-alert>` (reference: the Approvals Inbox, fixed
  2026-08-26 after shipping as a red "Failed to load approvals" toast that taught operators something
  was broken when nothing was); only OTHER failures toast, via `apiErrorMessage`. When the explained
  state replaces a grid, use `@if/@else` — **an empty ag-grid left mounted (`[class.hidden]`) fails
  axe `aria-required-children`** (gate-caught on that same fix); keep-mounted is only for surfaces a
  ViewChild must survive (the R9 tab-panel rule).
- **Shell layout:** `layout.component` is a flex **column**; the `<inspecto-connectivity-banner>` is its first
  child and is `display:contents` (consumes no space when hidden, stacks full-width on top when shown). Don't
  give layout-level siblings a growing `flex` or they'll steal width from the content column.
- **Per-call:** surface `apiErrorMessage(err, fallback)` via `ToastrService`. Independent fetches
  **degrade gracefully** (one failing call must not blank the whole page — fetch outside the core `forkJoin`).
- **Offline / backend-down:** `ConnectivityService` (signals) + `<inspecto-connectivity-banner>`
  (`role=alert`, Retry → `/health`). Already mounted in `layout.component`.

## 9. Performance

- Lazy-load every feature route. `OnPush` + signals. `trackBy`/`track` in `@for`. Unsubscribe via
  `takeUntilDestroyed(destroyRef)`. `visibleInterval` pauses hidden polling. Keep bundles lean (no heavy
  deps; production build budgets are the gate). Mind ag-Grid column virtualization when asserting in tests.

## 10. State management

- **Signals** for component + shared service state; `computed()` for derived; `effect()` sparingly. RxJS for
  async pipelines and HTTP. **No global store / NgRx.**
- Shared cross-cutting state lives in a root service exposing signals (pattern: `ConnectivityService`).
- **Mutations: optimistic by default for reversible toggles/edits** — use `optimisticMutate({apply, commit,
  reconcile, rollback, onError})` (`inspecto/api/optimistic.ts`): apply locally now, reconcile with the
  server result on success (silent), roll back + toast on error. Reassign arrays (`rows = [...rows]`) so the
  grid re-renders. Keep request→refetch for create/destroy or server-computed results.

## 11. Routing

- Lazy `{ path, loadChildren: () => import('app/modules/admin/<f>/<f>.routes') }` in `app.routes.ts` (no auth
  guard — the app is auth-free). Each `<f>.routes.ts` is `export default [...] as Routes`. Default route → `dashboard`.
- **Adding a page = two edits:** the lazy route in `app.routes.ts` **and** the nav item in
  `core/navigation/navigation-data.ts` (4 collapsable groups: Pipelines / Acquisition / Operations /
  Settings, + Dashboard/Assistant basics). `NavigationService` serves this const client-side (+ the
  per-space Menu Builder merge); there is no longer a Fuse `api/common/navigation` mock. Detail routes
  carry breadcrumbs.
- ⚠ **Moving or removing a nav item is THREE edits, not one — the Access Catalog is derived from the nav
  tree.** `ACCESS_ACTION_NODES` (`inspecto/access/access-catalog.ts`) keys each gateable functionality by
  the **nav id it hangs under**, so deleting a nav item silently takes its capability out of the catalog
  and it becomes unconfigurable. Re-home the action node under the item's new host. Caught by
  `access-catalog.spec.ts`'s "the default catalog covers every declared action node" — a real failure that
  reads as unrelated to a nav edit (2026-07-28, Connections → a Settings section). The **grant id stays the
  same**, so `LensService.identityCapability(...)` needs no change.
- **A Settings *section* is not a nav item.** `SettingsComponent.drawers` is the list; a section is a
  `{id,title,icon,description,component}` row rendering an existing standalone component through
  `NgComponentOutlet`, and its own route stays. Adding one = the drawer row + its import.
- Global search (`layout/common/search`) is a client-side jump-to-page palette over the nav — not a backend
  search. **Opened app-wide by Ctrl/Cmd+K** (a `document:keydown` HostListener in the classic layout calls
  `SearchComponent.open()`); with an empty query it shows recents (`inspecto.search.recents`) + shell
  **action commands** (`[commands]` input, `SearchCommand[]`). Shell-owned actions (lens/space/theme) stay
  in the classic layout's `paletteCommands`; **feature-scoped commands go through the command registry**
  (`inspecto/commands/command-registry.ts`): declarative `{title, icon?, group?, link, queryParams?}`,
  registered in `app-commands.ts` (side-effect import — never import a feature into the layout). The
  target pane implements the `?create=1` handshake: strip the param (`replaceUrl`) and open the create
  dialog **in the navigation promise's `.then()`** — MatDialog closes open dialogs on navigation
  (reference: object-mail / jobs `ngOnInit`). ⚠ **A handshake that REPLACES a route is the opposite
  case and must NOT be stripped** — `/pipelines?guided=1&open=<id>&stage=<chip>` (P6-a, 2026-08-16) is
  where the retired onboarding wizard redirects, so it has to survive a reload and a bookmark. Build
  such a target in ONE exported function both the route's `redirectTo` and the calling buttons use, in
  its own module (a routes file usually imports its component, so the component importing back is a
  cycle) — reference `catalog/onboard-redirect.ts`. ⚠ And when the param drives a LOAD rather than a
  setting, consume it once per value: the receiving effect must not re-run on unrelated signals, or it
  refetches and discards in-flight edits. **`?`** (outside a text field) opens the shared
  `ShortcutsHelpDialog` (`inspecto/shortcuts-help.dialog.ts`) — add new global bindings to its
  `SHORTCUTS` list.

## 12. Definition of Done (run before claiming completion)

1. `npm run lint:tokens` — design-system guard green.
2. `npm run build` (production) — AOT type-check + budgets green.
2b. **Typecheck all THREE tsconfigs — `npm run build` covers only the app one.**
   ```bash
   npx tsc --noEmit -p tsconfig.app.json && npx tsc --noEmit -p tsconfig.spec.json && npx tsc -p tsconfig.json --noEmit
   ```
   ⚠ **The root `tsconfig.json` is a genuinely DIFFERENT gate, not a superset** (learned 2026-08-11,
   `842a3a77`). It sets no `types`, so every `@types/*` is ambient — only `tsconfig.spec.json` names
   `vitest/globals`, and the root check does not extend it. So a spec using a vitest-only matcher
   (`toHaveLength` on an element list) type-errors under the root config while **passing `test:ci`,
   `npm run build`, and both other tsconfigs**. **Since the TS 6 upgrade (2026-08-13) this is stricter
   still: `@types/jasmine` no longer supplies ambient `describe`/`it`/`expect` there at all**, so the
   11 remaining specs that had leaned on it were given explicit imports and **every** spec now imports
   `{ describe, expect, it, … }` from `vitest`. A new spec MUST do the same — the fix is always the
   import, never a tsconfig edit. A spec that runs green is not proof it typechecks. A spec that runs green is not proof it typechecks. **Check the EXIT CODE, not just the pass count**: an
   unhandled error (e.g. a G6/AntV or MapLibre canvas mounting in jsdom) makes vitest exit non-zero
   even with 0 test failures → CI red. `GraphViewComponent` only mounts a canvas when
   `data.nodes.length > 0`, so test graph-hosting components on the empty/no-graph path (`EMPTY_GRAPH`)
   and assert traversal via a spy, never by rendering populated graph data. Add an
   `expectNoA11yViolations` for new components.
4. **Verify in the preview** (`.claude/launch.json` servers): load the route, confirm behavior in the DOM
   (`preview_eval`/snapshot — screenshots time out in this env), check `preview_console_logs` for errors.
   **⚠ Two ways a preview check reports a false green.** (a) **Toggling a control and clicking its submit
   button in the SAME eval call silently does nothing** — Angular has not re-rendered `[disabled]` yet, so
   the click lands on a still-disabled button while the UI looks like it worked. Split them into two calls
   and assert `button.disabled === false` before clicking. (b) **A raw `fetch('/api/…')` bypasses the
   offline mock entirely** (it is an `HttpInterceptor`, not a service worker) and returns an empty body —
   so it can neither confirm nor refute a write. Confirm a mutation by reading the mock store
   (`localStorage['inspecto.mock.v22']` → `<space>[<collection>]`) or by re-opening the UI, never by
   observing that a dialog closed. (The key is bumped whenever the seed contract changes — read
   `MOCK_STORE_KEY` in `mock/mock-store.ts` rather than trusting this literal.)
   **The preview browser does NOT deliver `ResizeObserver` callbacks** (it renders from DOM snapshots, no
   continuous paint loop) — RO-driven behavior (chart/graph/map container-resize) can't be exercised
   in-preview; rely on the unit test (observer wired + disconnected) + the shared RO→`resize()` precedent.
4b. **Formatting is pinned by `.prettierrc.json`/`.prettierignore`** (added 2026-08-13: printWidth 120,
   tabWidth 4, singleQuote, trailingComma all — measured off the pre-upgrade tree, not guessed).
   **`.prettierignore` excludes** `src/@gamma/` + `modules/auth/` (vendored, §3 bans restyling it — stays
   mergeable with upstream), `src/app/inspecto/mock/*.contract.json` (Jackson-written,
   byte/string-compared by `NodeAttributesContractTest`/`StepTypesContractTest`/
   `MeasureGrammarContractTest` in `inspecto-engine` — reformatting these breaks the **Java** build, a
   gate the UI verify loop never runs), and `src/assets/` (fixture payloads). ⚠ **Prettier's HTML
   reflow can silently change whitespace that carries MEANING**, in two ways seen so far:
   - **The `lint:tokens` `ds-allow` escape hatch is per raw SOURCE LINE** (`tools/check-design-tokens.mjs`
     just checks `line.includes('ds-allow')`). Prose reflow can wrap the flagged text onto a different
     line than its own escape-hatch comment, silently re-triggering the guard.
   - **Angular collapses inter-node whitespace by default** (`preserveWhitespaces: false`). A
     zero-width adjacency like `{{ c.source }}@if (c.locator) { … }` renders with NO gap; reflowing it
     onto separate lines inserts a newline Angular then collapses into an extra space at runtime — a
     silent rendering change (`"runs (r-9)"` → `"runs  (r-9)"`) that only a test with an exact-string
     assertion will catch, and a reformat commit's own gate won't flag by construction (it's a
     behavior check, not a diff review).

   **The fix for either is `<!-- prettier-ignore -->` immediately before the affected node** (a preceding
   *explanatory* comment above it does not defeat the ignore — confirmed by re-running `--write` and
   checking for "(unchanged)"). Always re-run `prettier --write` on a fix to confirm it actually holds
   before trusting it — "looks pinned" is not "is pinned". After ANY repo-wide `prettier --write`, grep
   for the zero-space interpolation-adjacency pattern (`}}@if`, `}}@for`, `}}@switch`) across `*.html`
   as a cheap way to surface the second failure mode before the test suite does.
   - **`ng update`'s own codemods reformat every file they touch**, on top of whatever `.prettierrc` says
     — Angular's codemod rewrites through the TypeScript printer, which does NOT read `.prettierrc`. The
     v22 core migration alone touched 116 files. Run `prettier --write` as a separate follow-up commit
     after an upgrade lands, never mixed into the upgrade commit or feature work — each stays reviewable.
   - **`ng update` refuses a dirty tree**, so land each package group as its own commit
     (core+cli, then material+cdk) — which is the right granularity anyway.
   - **Peer conflicts need `--force`.** `ngx-toastr` (20.0.5, its latest) still peers
     `@angular/core: ^21.0.0` and has no v22 build; it is forced, and the suite + a live smoke prove it
     works. Re-check when upstream publishes.
   - **v22's migrations are "retain old behaviour" shims, not modernisation**:
     `ChangeDetectionStrategy.Eager` was stamped on the **70** components that had no explicit strategy
     (v22's default flipped to OnPush), `withXhr()` on 29 `provideHttpClient` calls (v22 defaults to
     fetch), plus `$safeNavigationMigration()` in 14 templates. ⚠ **`Eager` is legacy, not a target** —
     new components still use `OnPush` (§4), and an `Eager` marker is a candidate for removal, not a
     pattern to copy.
5. If a pattern changed, update the `/design` gallery **and this skill** (the shared, profile-independent
   source of truth). Do **not** record UI conventions in per-profile session memory — teammates on this
   sandbox (e.g. `jotder`) each run under a different Windows profile and won't see it.
6. Commit per the `release-workflow` skill (§0.7); push only on explicit ask after confirming the
   merge-forward set.

---

**Source of truth for current patterns (all shared, profile-independent):** this skill, the in-app
`/design` gallery (`modules/admin/design-system/`), and `docs/ui/accessibility-audit.md`. When code and
this skill disagree, fix one of them — don't silently diverge. For build/test/run commands see the
`build-verify` skill; for backend API contracts see `java-backend`.
