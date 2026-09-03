---
type: Plan
title: Close AUTHOR-SCHEMA-1 — Schema/Mapping/Transform Authoring UX
status: SUPERSEDED 2026-09-03 — never started
timestamp: 2026-09-03T00:00:00Z
---

> **SUPERSEDED the day it was drafted.** The operator judged "add validation gates over the existing
> surface" too complex and chose a redesign instead: `docs/superpower/parse-pane-redesign-plan.md` +
> `docs/superpower/sql-transform-v1-plan.md`. What survived from here (DESCRIBE-based validation, the
> join reference-existence check, schema-drift-into-mapping) is listed in the SQL plan's "What survived"
> section. Kept for provenance only — nothing below was built.

# Plan: Close AUTHOR-SCHEMA-1 — Schema/Mapping/Transform Authoring UX

## Context

`docs/BACKLOG.md` §4 **AUTHOR-SCHEMA-1** and `docs/okf/frontend/features/schema-mapping-authoring.md`
document that the current authoring UI for dataset schemas, `transform.map` rule grids, and
`transform.join` lookups is "close but not there": all synchronous validation happens on structural
shape only, `EXPR` is opaque free text, joins can reference a non-existent dataset/column, schema edits
never check the sibling mapping CSV they can silently orphan, and preview is opt-in/stale-prone with two
disconnected test mechanisms. The user wants this closed so authors get real feedback before Save/Run
instead of discovering breakage at execution time.

This plan targets the 8 gaps recorded in the backlog row, in priority order (fail-closed correctness
first, UX polish last), reusing existing patterns confirmed by codebase research rather than introducing
new ones where avoidable.

## Reused patterns (confirmed present)

- **`configValidators: Record<string, ValidatorFn[]>`** on `PipelineConfigDefinitionComponent`
  (`pipeline-config-definition.component.ts:271-274`), passed to `<inspecto-schema-form>` via
  `[extraValidators]`. Validator shape: `ValidatorFn = (control) => {message: string} | null`
  (`measure-grammar.ts:92-113`) — this is the extension point for a `transform.join` `on`/`reference`
  validator (gap 3).
- **`MappingCsv` / `SchemaCompatibility`** (`inspecto-util/.../MappingCsv.java`,
  `inspecto-config/.../safety/SchemaCompatibility.java`) are the two backend gates to extend for
  cross-validation (gap 4) and existence checks (gap 3, 7).
- **`ComponentsService.previewTransform`** (`components.service.ts:217-234`) is full-node-config-only
  (422s without a real `transform.*` type) — reused as-is for gap 5's auto-recompute, NOT retrofitted
  into a bare-expression evaluator (that would need a new route — out of scope, see Non-goals).
- **No debounce/async-validator or reference-column-list endpoint exists** — both are net-new per the
  research; built following standard Angular `AsyncValidatorFn` conventions, not an in-repo template.

## Non-goals (explicitly deferred, not part of this plan)

- A full expression-builder UI (Monaco/CodeMirror wiring, function catalog, autocomplete) — gap 2 is
  large enough to warrant its own follow-up plan once gaps 1/3/4 (the correctness gates) are shipped.
  This plan only makes `EXPR` fail loudly and early (a syntax-validity check), not pleasant to author.
- Drag-and-drop field mapping (gap 6) — cosmetic, no correctness risk; left as a BACKLOG stretch item.
- Refactoring `RunToHereDialog` into a fully headless service — too large a UI change for this pass; gap
  8 is addressed by cross-linking (a button that opens it), not by merging the mechanisms.

## Phase 1 — Backend: fail-closed existence & syntax checks (gaps 3, 4, 7)

**Goal:** nothing silently defaults or gets orphaned; bad references become a 422 at save time.

1. **`transform.join` existence validation** — in `ConfigSafetyValidator` (or a sibling validator invoked
   from the pipeline-graph save route), when a node's `type == transform.join`: resolve `reference`
   against the real catalog (same source `GET /catalog/references` reads from) and reject with a
   cell-anchored finding if absent; resolve the reference dataset's column set and reject if any `on` key
   is missing. This mirrors `SchemaCompatibility.check`'s finding shape so the UI can reuse its existing
   cell-mapping code (`schema-editor.dialog.ts:145-161` pattern).
2. **Mapping target/source existence validation** — in the `splitMapping`/mapping-CSV write path
   (`ConfigWriteRoutes.java:199-226`), after `MappingCsv.parse`, validate that every `DIRECT` rule's
   source column exists in the schema's field set and reject (not silently `VARCHAR`-default) if not.
   This also closes gap 7 — `TransformCompiler.direct()`'s `getOrDefault(source, VARCHAR)` becomes
   unreachable for a properly-saved mapping; keep it as a defensive fallback only, not the primary
   contract.
3. **`EXPR` syntax pre-check** — add a lightweight `POST /config/validate/expr` (or extend
   `/config/preview/schema`) that runs the candidate `EXPR` string through DuckDB's `PREPARE`/parse-only
   path (no execution) against the schema's known columns, returning a parse error if invalid. This is
   the backend half gap 1 needs — cheap enough to call synchronously from the UI.

**Files:** `ConfigSafetyValidator.java`, `ConfigWriteRoutes.java`, `MappingCsv.java` (only if the parse
contract needs a stricter mode flag), a new small route class or an addition to `ParserRoutes.java`/
`ConfigWriteRoutes.java` for the EXPR check, plus a Java test class covering each new gate (per
`endpoint`/`java-backend` skill conventions — real-HTTP tests for the new/changed routes).

**Verification:** new backend tests: (a) saving a `transform.join` with a bogus `reference` → 422; (b)
saving one with an `on` key absent from the reference's columns → 422; (c) saving a mapping `DIRECT` rule
whose source column doesn't exist in the schema → 422; (d) a syntactically invalid `EXPR` string → the new
endpoint returns an error, a valid one returns success. Run via `mvn -o test` per `build-verify` skill.

## Phase 2 — Backend: schema-drift cross-check into mapping (gap 4, continued)

**Goal:** editing a schema through `SchemaEditorDialog` that would orphan a mapping rule is caught, named,
and requires explicit override — not just blocked blindly or silently allowed via `compatibility:"none"`.

1. Extend `SchemaCompatibility.check(existing, draft)` to also read the sibling `MappingCsv` (via
   `ConfigFileSupport.mergeSiblingMapping`, already used on the read path) and, for each field the diff
   would remove/rename, emit a finding naming the specific mapping rule(s) (`targetColumn`) that reference
   it — reusing the existing `Finding` shape so the UI's cell-mapping code needs no changes.
2. Keep `compatibility:"none"` as the override escape hatch (unchanged), but the override confirm dialog
   (`schema-editor.dialog.ts:108-111`) should now surface the named mapping rules in its warning text
   instead of generic prose.

**Files:** `SchemaCompatibility.java`, `schema-editor.dialog.ts` (warning text only — the mechanism
already exists).

**Verification:** backend test — edit a schema removing a field referenced by an authored mapping rule →
finding names the rule; with `compatibility:"none"` the save still succeeds. Manually drive the dialog in
the browser preview to confirm the named-rule warning renders.

## Phase 3 — Frontend: live validators wired to Phase 1 gates (gaps 1, 3)

**Goal:** the two riskiest free-text fields (`EXPR`, `transform.join`'s `reference`/`on`) get real-time
feedback, not just a 422 on Save.

1. **`transform.join` validator** — add `reference`/`on` entries to `configValidators` in
   `pipeline-config-definition.component.ts` (same map as `measuresValidator`/`groupByValidator`), each an
   `AsyncValidatorFn` doing `debounceTime(300) + distinctUntilChanged() + switchMap(...)` against the
   Phase 1 existence check (new small `CatalogService`/`ConfigService` method wrapping the backend route).
   This is the first async-validator in the codebase — write it as a small reusable helper (e.g.
   `asyncExistenceValidator(check$)`) rather than one-off inline logic, so gap-1's `EXPR` validator (next)
   can share the debounce/switchMap scaffolding.
2. **`EXPR` validator** — in `pipeline-load-definition.component.ts`, wire the `sourceExpression` control
   (only when `transformType === 'EXPR'`) to an async validator calling the Phase 1
   `/config/validate/expr` endpoint with the same debounce helper. Render the parse error inline (reuse
   `<inspecto-schema-form>`'s `errorFor`-style rendering, or a local `mat-error` since this drawer is
   bespoke, not schema-form-driven).
3. Add a `CatalogService`/`ConfigService` method for reference-columns lookup (the missing piece the
   research flagged — no such endpoint exists yet) backing both the Phase 1 backend check and this
   validator's local error messaging (e.g. "column `ccy` not found on `reference/rates`").

**Files:** `pipeline-config-definition.component.ts`, `pipeline-load-definition.component.ts`, a new
shared `async-existence-validator.ts` (or similar) under `inspecto/investigation/` or `inspecto/components/`
alongside `unique-name.ts`, `catalog.service.ts`/`config.service.ts` (new methods).

**Verification:** Angular vitest specs per `test-author` skill conventions covering: validator fires only
for EXPR rows, debounces correctly (fakeAsync/tick), surfaces the backend's error message; run
`npm test` (per `build-verify`). Then drive it live in the browser preview — type a bad column into `on`,
confirm the inline error appears without clicking Save.

## Phase 4 — Frontend: preview auto-recompute & cross-linking (gaps 5, 8)

**Goal:** preview stops going stale silently, and the drawer-level testers point at the more powerful
canvas-level run.

1. **Auto-recompute:** in `pipeline-load-definition.component.ts`'s `invalidateMapping()`
   (`:600-613`) and the join drawer's equivalent, instead of only marking the preview stale, debounce
   (500ms) and auto-re-invoke `previewSchema`/`previewTransform` when the form is valid — falling back to
   the current "stale, click to refresh" state only while the form is invalid (so a validator error from
   Phase 3 suppresses a wasted round-trip).
2. **Cross-link to `RunToHereDialog`:** add a "Run full test to this Step" affordance in both drawers that
   calls `PipelinesService.runToNode(pipelineId, nodeId, files)` directly (confirmed callable headlessly
   per research) — either opening `RunToHereDialog` pre-scoped to the current node, or, if file-selection
   state is awkward to thread in from the drawer, simply opening the dialog with the node pre-selected
   (smallest change: reuse the dialog, pass `data.node` — it already accepts this per its existing
   `MAT_DIALOG_DATA` contract).

**Files:** `pipeline-load-definition.component.ts`, `pipeline-config-definition.component.ts`,
`run-to-here.dialog.ts` (only if pre-scoping needs a new input, likely none — it already takes a node).

**Verification:** browser-preview walkthrough — edit a mapping rule, confirm the preview table
auto-refreshes without a click once the row is valid; click the new "Run full test" button from inside
the mapping drawer and confirm `RunToHereDialog` opens pre-scoped to that node.

## Sequencing & risk notes

- Phases 1–2 are backend-only and additive (new 422s on previously-silent-failure paths) — ship and
  verify with tests before touching the frontend, since they change save-time behavior authors depend on.
- Phase 3 depends on Phase 1's endpoints existing; Phase 4 is independent of 1–3 and could ship first if
  sequencing needs to change, but is listed last because it's pure polish with no correctness payoff.
- Each phase gets its own commit(s) and its own `verify-runner` pass (`mvn -o test` for 1–2, `npm test`
  for 3–4) per this repo's per-shift, incremental-commit convention — do not bundle all four phases into
  one commit.
- Gap 2 (expression builder) and gap 6 (drag-drop) stay in `docs/BACKLOG.md` as follow-on rows, not part
  of this plan's scope — call this out explicitly when AUTHOR-SCHEMA-1 is marked partially closed.
