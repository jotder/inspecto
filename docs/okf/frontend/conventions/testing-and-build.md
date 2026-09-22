---
type: Convention
title: Testing, Build & Definition of Done
description: vitest + axe, the lint:tokens guard, the production build, preview verification — run before claiming done.
resource: inspecto-ui/package.json
tags: [testing, build, vitest, ci, definition-of-done]
timestamp: 2026-06-28T00:00:00Z
---

# Testing, Build & Definition of Done

## Commands

* `npm run lint:tokens` — the [design-token guard](design-system-tokens.md) (no hardcoded colors).
* `npm run build` — production build: AOT type-check + budgets (initial ≤ 3mb warn / 5mb error). New CommonJS deps must be added to `allowedCommonJsDependencies` in `angular.json` (e.g. `alasql`).
* `npm run test:ci` — vitest (jsdom) + `TestBed`; **add `expectNoA11yViolations` to new component specs** (see [accessibility](accessibility.md)).

⚠ **A git worktree has no `node_modules`, so every command above fails there.** `npm ci` has only ever run in
the main checkout, and npm reports the absence as `could not determine executable to run` — which reads like a
broken script, not a missing dependency tree. ⛔ Don't install a second copy: it is **293 MB** per worktree
(measured 2026-09-22) and a copy installed once goes stale the moment a `package-lock.json` change lands on
`master`, so the worktree then tests against different dependency versions than CI does — silently. Point the
worktree at the main checkout's tree with a **directory junction** instead (`node_modules` is gitignored, so
the junction never appears in `git status`):

```powershell
New-Item -ItemType Junction -Path '<worktree>\inspecto-ui\node_modules' -Target 'C:\sandbox\inspecto-clean\inspecto-ui\node_modules'
```

⚠ Use PowerShell's `New-Item`, not `cmd /c mklink /J` — invoked through the POSIX shell that hosts most tooling
here, `mklink` receives a mangled path and fails with `Invalid switch`, which looks like a quoting bug in your
command rather than a shell mismatch.

## Testing notes

* Signal inputs are set with `fixture.componentRef.setInput('name', value)`.
* A component containing a **`@defer`** block requires `await TestBed.compileComponents()` before `createComponent` (e.g. the [data-table](../design-system/data-table.md), whose SQL editor is deferred).
* **G6** can't instantiate in jsdom — unit-test graph hosts on the empty/no-graph path.
* Framework-free logic (the data-table `core/`/`sql/`, [query](../design-system/query.md)) is unit-tested directly without `TestBed`.
* ⚠ **`role="alert"` is not unique on a screen that shows more than one `<inspecto-alert>`** — the shared
  component gives BOTH `warning` and `error` that role (info/success get `status`). So
  `querySelector('inspecto-alert [role="alert"]')` really asserts *whichever alert comes first*, and the
  spec then depends on document order rather than on what it means. Find the alert by its **title**, then
  assert its body. Found 2026-09-22 in the Link Analysis Attach-to-Case dialog, and only visible in the
  browser preview, where both alerts render at once — in the unit test the two orderings agreed.

## Definition of Done (run before claiming completion)

1. `lint:tokens` green. 2. `build` green (AOT + budgets). 3. `test:ci` green (unit + a11y).
4. **Verify in the browser preview** — load the route, confirm behavior in the DOM, check console for errors (don't ask the user to check manually).
5. If a pattern changed, update the [`/design` gallery](../features/design-system-gallery.md) and the shared `angular-ui` rules.
6. Commit per the repo's branch policy: `feat:`/`test:`/`docs:` → `master` only; `fix:` → oldest supported branch then merge-forward. Never push without an explicit ask.
