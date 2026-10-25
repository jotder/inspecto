---
name: ui-test-traps
description: "inspecto-ui: run the suite via `npx ng test` (never raw vitest), read the EXIT CODE, treat wholesale identical failures as a dead spec file, and drive the preview for anything visible"
metadata:
  type: reference
---

- **Entry point is `npx ng test`** (`@angular/build:unit-test` calls `TestBed.initTestEnvironment()`). Raw
  `npx vitest run` fails ~280 files with TestBed/JIT errors that are not real. Raw vitest is fine only for a single
  spec needing no TestBed. Never pass `--browsers`.
- **Exit code, not the summary line:** an unhandled async error (e.g. `@antv/g6` `clearRect` in jsdom when the
  editor renders with no model -> canvas mode) exits 1 with every assertion green. Stash the suspect spec to prove.
- **28 identical constructor failures = a spec file guarding nothing**, not one bug. Fix the TEST DOUBLE to mimic
  the real shape (e.g. `ActivatedRoute.queryParamMap` observable), not production code; sweep the same stub shape.
  DI changes are invisible to type-checking — run the specs.
- **Fixtures built from the TYPE miss stored shapes:** an empty TOON key arrives as `{}` (so `??` passes it) —
  check a required member (`x?.items ? x : default`).
- **Hung page:** `[ngModel]="fn()"` returning a fresh array on `mat-select multiple` loops forever; use a
  `computed`. Bisect with console.log (readable while hung) + `@if (false && ...)`.
- **Preview catches what specs cannot** (tier/disclosure, projection, real seed data): drive the offline preview /
  a real Space for any visible change and read the whole page you land on. A raw `fetch('/api/…')` bypasses the mock
  interceptor; after an HMR compile error the OLD bundle is served (check preview_logs).
- **Browser pane:** an emulated viewport larger than the pane desyncs click mapping (dead-button symptom) — clear
  emulation; prove a click lands with a click listener before filing a UI defect. Background panes give
  half-laid-out grid measurements. DataTable `defaultColDef flex:1` makes bare `width` dead.
- **`--include` globs are relative to `inspecto-ui`, and `projects/**` specs need the `../` form:**
  `npx ng test --include='../projects/link-analysis/src/...spec.ts'`. A glob that matches nothing prints "No tests found"
  and EXITS 1 — that is not a failure of the code and not a pass. When the operator caps session usage, run only the
  targeted specs of the files you touched (plus typecheck + eslint) and SAY the full suite was not run.
- **Two pre-existing full-suite reds, not yours:** `projects/la-app app.routes.spec` "resolves the Link Analysis routes from
  the library" times out at 60 s under load (passes alone, fails identically on clean master), and `menu-landing.spec` fails
  four tests when it runs after `session.service.spec` (that spec leaves `inspecto.currentSpace=gone` in localStorage;
  the fix is a one-line cleanup). Classify before chasing.
- **Minified bundle greps** for identifiers give false negatives; probe a string literal.
- **Bundle size: measure the BROWSER's boot set, not the build table.** `main` statically imports ~30 chunks that
  `index.html` never lists, so "Initial total" understates boot JS by ~1.5 MB. A heavy library reaches `main` through ONE static
  import of a constant from a component that also imports the library (2026-10-25: `REGISTRY_KINDS` pulled `@antv/g6` into
  `main`, 1.89 MB -> 364 kB once the constant moved to a module that imports nothing). Prove it with the esbuild metafile
  (`ng build --stats-json`), BFS from the entry.
- **A `RouteReuseStrategy` is constructed BY the Router:** injecting `SessionService` (which injects `Router`) in it is
  NG0200 and a blank app, yet a spec with stubbed services passes. Resolve such services lazily via `Injector`, and
  keep one spec on the REAL services (`keep-alive.strategy.spec.ts`, 2026-10-21).

Related: [[bundle-and-process-traps]].
