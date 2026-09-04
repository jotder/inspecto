---
type: Feature
title: Schema, Mapping & Transformation Authoring
description: End-user authoring path for a dataset's schema (the redesigned Parse pane), the SQL-first transform.sql pane, the legacy transform.map rule grid, and transform.join lookup config — with the 2026-09-03 gap list annotated by what the redesign closed, absorbed or dropped.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipeline-load-definition.component.ts
tags: [feature, pipelines, authoring, schema, mapping, transform, gap-analysis]
timestamp: 2026-09-04T00:00:00Z
---

# Schema, Mapping & Transformation Authoring

> **Scope.** This file is the authoring-UX truth for the three surfaces end users touch to shape data:
> dataset **schema** editing, the **mapping** rule grid (`transform.map`), and **lookup/join** config
> (`transform.join`). It is a child of [Pipeline Editor](pipeline-editor.md) (the drawers described here
> live inside that shell) and reads alongside [`okf/backend/engine/catalog-vs-executors.md`](../../backend/engine/catalog-vs-executors.md),
> which covers the engine side of the same two node types. Written 2026-09-03 as a baseline for a planned
> authoring-UX pass — **current truth, not aspirational**.
>
> **The pass SHIPPED 2026-09-04** as two plans, both archived:
> [`parse-pane-redesign-plan.md`](../../../archived-documents/plans-archive/parse-pane-redesign-plan.md)
> (`d012f721`; as-built in [grammar-config.md](grammar-config.md) § "The sectioned Parse pane") and
> [`sql-transform-v1-plan.md`](../../../archived-documents/plans-archive/sql-transform-v1-plan.md)
> (`98ffc90b` engine + `7e13dd82` pane; as-built in §0 below and
> [`catalog-vs-executors.md`](../../backend/engine/catalog-vs-executors.md)). §1–§5 describe the surfaces
> that still exist — `SchemaEditorDialog`, the `transform.map` rule grid and the `transform.join` form
> were NOT removed; `transform.map` is now the legacy mapping path beside `transform.sql`. §6's gap list
> is annotated with what the redesign closed, absorbed, or dropped as premature.

## 0. The Transform Step pane — `transform.sql` (as built; a fields grid over a function catalog)

`PipelineTransformSqlDefinitionComponent` (`pipeline-transform-sql-definition.component.ts`), a bespoke
pane reached from the editor's routing arm for `dn.type === 'transform.sql'`
(`pipeline-editor.component.html:755-767`).

🔴 **This surface flipped twice on 2026-09-04. The SQL-only middle state is NOT current.** The Step
shipped SQL-only that morning (`24171333`, which deleted the 2026-09-03 fields grid), and the operator
rejected it the same day: *"mapping with functions and parameters is not realistic"* — a raw SQL box is
not an authoring surface for a non-technical user, and the legacy `transform.map` rule grid (four
`transformType` constants, two of which pack their arguments into a `|`-delimited string) is not a
mapping model either. The grid is back, and its verb enum is replaced by a **function catalog with typed
parameters**. D3 (no `where` — filtering stays `transform.filter`), D4 (never parse SQL back into rows)
and D7 (600+ columns stay usable) still hold; D5's five-verb list and D6's lock are superseded by the
catalog and by persisting `fields[]`.

- **The function catalog** (`sql-functions.ts`) is the vocabulary and the ONE place a function's plain
  label, its typed parameters and its SQL template live together. `SqlFunction = {id, label, category,
  template, params}`; `SqlFunctionParam = {name, label, type, default, options, optional}` where `type`
  drives BOTH the rendered control and the escaping: `column` → quoted identifier, `text` → single-quoted
  literal with quotes doubled, `number` → validated then verbatim, `enum` → one of `options` verbatim,
  `sql` → verbatim (the deliberate escape hatch, `custom` only). ~20 functions across Keep · Text ·
  Numbers · Dates · Logic · Convert · Custom. A row's source column binds to the template's `{source}`
  automatically — that IS the operator's "auto map parameters against sql functions".
  ⚠ **Every conversion is `TRY_CAST`, never bare `CAST`, and division guards with `NULLIF`** — a
  catalog-wide unit test asserts it, because a bad cell must null one cell, not kill the batch.
  ⚠ **A `text` parameter is deliberately not trimmed** — a single space is a legitimate separator for
  "Join with another column"; trimming reported a valid row as missing its separator (caught by a test).
- **The grid** (mockup layout verbatim): `#` · Field name · From · What to do · Comes out as · Sample ·
  leave-out, over a search box and view-only filter chips with counts (All · Changed · Calculated ·
  Needs attention), under a 10/20/100 pager, with "Add a calculated field" and a **Left out** tray whose
  chips put a column back. `#` is the position in the FULL ordered list, computed before filtering and
  paging, so page 2 starts at 11 — search, filters and paging are lenses that never change what is
  written. A function's parameters render as controls beneath its picker on the same row.
- **Compilation** (`pipeline-transform-sql.ts`, pure, unit-tested): `compileField` → `{expr}` or
  `{problem}`; `generateSql` emits `<expr> AS <name>` per complete row over the fixed `input` relation
  and **skips a row with a problem so the generated SQL always parses**, while `canApply()` is false
  until every problem is cleared — an incomplete row can never be saved silently.
- **Persisted shape** (`submit()`): **`{ sql, fields }`.** The engine declares and reads only `sql`
  (`node-attributes.ts`); `fields` is the authoring artifact and rides the `steps:` chain opaquely, which
  is what lets the grid round-trip exactly without ever parsing SQL. **This is why D6's lock is gone:**
  nothing has to reconstruct rows from text. `readFields` also opens a node written by the retired
  five-verb grid, mapping `verb`/`castType`/`formula` onto catalog ids.
- **Hand-written SQL is honest, not overwritten.** A node carrying `sql` with no `fields` opens in
  `sqlOnly` mode: the SQL is shown read-write in CodeMirror and the pane says it cannot be shown as a
  field list. "Start a field list from the incoming columns" replaces it — explicit, never automatic.
  ⚠ **Editing it saves — since 2026-09-04.** When the SQL became editable (CodeMirror, item (b)) two
  seams were left behind: `valueChange` was bound straight to the signal, so no `dirtyChange` was ever
  emitted and the drawer's Apply never armed; and `canApply()` still refused `sqlOnly` outright, so even
  an armed Apply would have returned early. Both are closed — the editor goes through `onSqlEdited` →
  `touched()`, and `sqlOnly` is no longer a refusal (the binder check is what guards what is typed).
  An Apply from this mode writes `{ sql, fields: [] }`, so the node reopens in `sqlOnly` — no grid is
  invented behind the author's back.
- **Opening a Step never arms Apply.** Seeding emits `dirtyChange(false)`; dirty is a real comparison of
  `{sql, fields}` against what was loaded. ⚠ This was a live defect on the legacy `transform.map` pane
  (a Step showed "unapplied" from a single click with no edit); the grid has a regression test for it.
- **Test against sample** reuses "Test this Step" verbatim: `ComponentsService.previewTransform({type,
  sql}, sampleRows)` with the GENERATED sql — `transform.sql` qualifies by the `transform.*` prefix. The
  run fills each row's **Sample** value.
- **Types now arrive without a run (2026-09-04, BACKLOG (a) closed).** A 300ms-debounced effect posts the
  generated SQL and the upstream columns to `POST /components/transform/describe`
  (`ComponentsService.describeTransform`, over `TypeFlow.describe`'s execution-free `DESCRIBE`) and fills
  **Comes out as** from the answer; a preview run's types are the fallback, not the source. The debounce
  timer is cleared on `DestroyRef.onDestroy` so a drawer closed mid-keystroke fires nothing.
- **Binder errors are as-you-type, and ONLY a 422 blocks Apply.** DuckDB's message renders in a warning
  alert above the grid and clears `canApply()`. Any other failure — offline, a 404 on an older control
  plane, a 503 — says nothing about the SQL, so it clears the alert and leaves Apply available: a pane
  that locks the author out because the backend is unreachable is worse than one that saves unverified
  SQL (three specs in `Transform pane: live schema derivation` pin all three arms).
- **Authoring without sample rows.** The grid seeds from `[upstreamColumns]`, passed by the editor
  (`upstreamSchemaColumns()`): the parsed sample's keys when a Test-Parse has run, else the field names
  read from the parser's companion schema. Before this, a Step opened with an empty grid until the
  operator ran a parse.
- **Duplicate output names refuse.** `compileFields` marks every row sharing a trimmed name with a
  problem ("Output column names must be unique"), which blocks Apply — DuckDB would otherwise accept the
  SELECT and hand the sink two columns of the same name.
- **Row order is the output order** — ▲/▼ per row reorder `fields[]`, and `generateSql` emits in that
  order.
- **Not built, on purpose:** the "Describe the fields" metadata section (description · unit ·
  sensitivity) — the backend does not carry column metadata on a `transform.sql` node; until it lands
  (BACKLOG (e)), metadata rides through the Parse pane's by-selector carry-through
  ([grammar-config.md](grammar-config.md)).
- **Audit boundary:** saving a `transform.sql` node yields one WARNING `SQL_STEP_UNAUDITED` from the
  validator; a WARNING never blocks save (proven by test — `catalog-vs-executors.md`).

## 1. Schema authoring

> 2026-09-04: the Parse pane's own columns table ("Columns that come out"), its sections and defaults are
> in [grammar-config.md](grammar-config.md); the standalone `SchemaEditorDialog` below is unchanged.

- **Component:** `SchemaEditorDialog` (`schema-editor.dialog.ts:48-118`) — a flat grid over
  `<inspecto-editable-grid>` with columns name / selector / **type** / description / unit / classification
  (`:28-37`). Type is a **free-text DuckDB SQL type**, deliberately not a dropdown (comment `:31-32`).
- **Both hand-typed and inferred**: rows can be typed directly, or "Suggest from sample" (`:102-107`) calls
  `ConfigService.suggestSchema()` (`POST /config/suggest/schema`, TRY_CAST voting) against `data.sampleRows`
  captured by an earlier Test-Parse (`:20-21,175-204`). Inference only fills the grid as a draft — nothing
  persists until Save (`:181-183`).
- **Inference (a separate, upstream endpoint):** `POST /parsers/{id}/preview` (`ParserRoutes.java:34,58-71`)
  runs a DuckDB `auto_detect` sniff and returns per-column types (`ParserRoutes.java:111-113`). Stateless —
  writes nothing.
- **Save:** `ConfigService.write('schema', config, {overwrite:true, ...compatibility})` (`:249-253`) →
  `POST /config/write type=schema`, persisted as `<name>_schema.toon` beside `<name>_mapping.csv`
  (`ConfigFileSupport.java:74-115`).
- **Editing an existing schema is real and gated:** a backward-compatibility diff,
  `SchemaCompatibility.check(existing, draft)` (`inspecto-config/.../safety/SchemaCompatibility.java:48-82`),
  flags removed fields, non-widening type changes, and moved selectors as ERROR findings, mapped back onto
  grid cells by field name (`schema-editor.dialog.ts:145-161,279-285`) with an inline alert and a "Save
  anyway (skip compatibility check)" escape hatch (`:108-111,218-225`, `compatibility:"none"` param,
  `ConfigWriteRoutes.java:195-197`).
- **This gate protects the schema file only — it never reads the sibling mapping CSV.** Its own comment
  cites "referencing Mappings" as the rationale for blocking a field removal, but `SchemaCompatibility`
  operates purely on `raw.fields` diffs; it does not cross-check `MappingCsv`'s `targetColumn`/
  `sourceExpression` values against the new field set. It only prevents the schema-side edit that *would*
  orphan a mapping rule — and that protection is fully bypassable via `compatibility:"none"`.
- **The only downstream-staleness warning found anywhere** is generic prose in the Parse-definition pane
  when a parse's produced columns no longer match the saved output schema (`pipeline-parse-definition.component.ts:407-424`,
  "anything downstream that expects the old columns will need updating" + a "Replace the output schema"
  button). This fires on a *parser format* change, never on a manual edit made through `SchemaEditorDialog`,
  and never names which mapping rules would break.

## 2. Mapping authoring (`pipeline-load-definition.component.ts` — `transform.map`, legacy path since 2026-09-04)

> The catalog's `transform.expression` and `transform.cast` entries now point at `transform.sql` (§0);
> `transform.map` and its `EXPR`/`CONCAT_DT`/`FILENAME_DATE` rules remain for stored pipelines and are
> still authored here, unchanged.

- Rule rows are a `FormArray`, seeded from the node's already-authored `rules` if present, else from a
  legacy `columns: [{name, expr}]` projection read as DIRECT rules (2026-09-04 — before that a
  columns-authored node showed an EMPTY grid, so the operator saw no mapping where the engine ran one),
  else one straight-through identity rule per schema field, auto-seeded as a pending "proposal" that
  arms Apply (`:483-546`). This is the only auto-map-by-name behavior; it is not a re-invocable action.
- ⚠ **Applying rules REPLACES `columns`.** `MAP_AUTHORED` holds both keys and `RowShaper.columnsOf` returns
  `columns` before it looks at rules, so a node keeping both would execute the old projection over the
  operator's saved edit — a write that is real and dead at once. `submit()` therefore deletes `columns`
  whenever it writes a non-empty `rules` (pinned by `emits rules and drops the columns key they replace`).
  An empty grid deletes nothing: a stray Apply must not wipe an authored projection.
- **Per row:**
  - Target column: free-text input, pattern-validated (`:127-133`).
  - Rule: `mat-select` of `transformType` (`DIRECT` / `EXPR` / `CONCAT_DT` / `FILENAME_DATE`, from
    `TRANSFORM_TYPES`) (`:135-142`).
  - The from-column control varies by type — `DIRECT`: dropdown of schema field names (`:145-152`); `CONCAT_DT`: Date +
    Time column dropdowns (`:153-180`); `FILENAME_DATE`: filename-column dropdown + free-text Prefix +
    free-text Format, placeholder `%Y%m%d` (`:181-216`); anything else (i.e. `EXPR`) — **a single free-text
    `sourceExpression` input**, tooltip: "A per-row scalar expression, emitted verbatim — you own any
    explicit cast" (`:217-225`).
- **`EXPR` is pure free text.** No syntax check, no autocomplete, no column picker. Confirmed by reading the
  whole component — the only aid is a static caption telling the author to hand-write `TRY_CAST(...)`
  themselves (`:235-239`). The engine emits it **verbatim** into the compiled SQL
  (`TransformCompiler.java:128`, dispatch table `:54`) — unparsed until DuckDB actually runs it.
- **Silent stale-reference fallback (backend):** even a `DIRECT` rule that names a column no longer in the
  schema doesn't error at mapping-save time — `TransformCompiler`'s `direct()` does
  `fieldTypes.getOrDefault(source, VARCHAR)` (`TransformCompiler.java:122`), silently defaulting the cast.
  The mismatch only surfaces as a DuckDB "unknown column in `raw_input`" error at execution time.
- **Preview exists but is opt-in and server-round-trip, not live-as-you-type:** "Test mapping" (`:248-254`)
  posts the current rules + the Parse tab's captured sample rows to `POST /config/preview/schema`
  (`:573-598`), rendering a capped result table (`MAX_PREVIEW_ROWS=20`, `:46,374`, `:262-297`). Any rule
  edit invalidates the prior preview (`invalidateMapping()`, `:407-408,600-613`) — it does **not**
  auto-rerun.
- **No drag-and-drop** field mapping anywhere in this component.
- **No dedicated whitespace-trim affordance** — only achievable by hand-typing `TRIM(col)` into the `EXPR`
  free-text box, per [`catalog-vs-executors.md`](../../backend/engine/catalog-vs-executors.md).
- **Client-side `ruleProblem()`** only structurally validates `CONCAT_DT`/`FILENAME_DATE` arity, mirroring
  the engine's `MappingRules` (`:465-481`) — `DIRECT`/`EXPR` rows get no inline semantic check beyond
  required/pattern.
- **Mapping CSV save itself validates shape only:** `MappingCsv.parse` checks required headers and row
  width, never that `targetColumn` exists in the schema or that `sourceExpression` is valid SQL
  (`MappingCsv.java:48-84`); the write path (`ConfigWriteRoutes.java:199-226`, `splitMapping`) does the same
  — no target-existence or expression validation on save.

## 3. Expression building

- **No dedicated expression-builder UI exists anywhere** — no function picker, no column-picker-into-EXPR,
  no live-preview-as-you-type editor. Confirmed by a codebase-wide search for `monaco`/`codemirror`/
  `ace-builds`/`ace-editor`.
- **CodeMirror is present** (`@codemirror/*` in `package.json`) but wired only to two unrelated surfaces:
  the SQL query workbench (`inspecto/data-table/sql/sql-codemirror.component.ts`) and the enrichment editor
  (`inspecto/enrichment/enrichment-editor.component.ts`) — never to the mapping `EXPR` field. Monaco/ace are
  absent entirely (no package, no references).
- No SQL function catalog is surfaced to end users on the mapping or join authoring surfaces — only free
  hints in tooltips/captions.

## 4. Lookup/join authoring (generic `AttributeSpec` form — `transform.join`)

- Rendered by the shared `PipelineConfigDefinitionComponent` + `InspectoSchemaFormComponent`, driven by
  `NodeAttributes.TRANSFORM_JOIN`'s spec (`node-attributes.ts:325-343`, mirroring
  `NodeAttributes.java:356-366`): `reference` (`autocomplete`, required, placeholder `reference/rates`) and
  `on` (`list`, required, join key columns).
- **`reference` is autocomplete with real suggestions, never a hard constraint:**
  `referenceOptionLoader()` (`entity-option-loaders.ts:72-79`) calls `CatalogService.references()`
  (backed by `GET /catalog/references`, `CatalogRoutes.java:32,84-85`) and maps results to
  `reference/<label>` options, wired via `[optionLoaders]` (`pipeline-config-definition.component.ts:141,265`).
  Per the shared form's own doc comment (`schema-form.component.ts:27-32`): "suggestions assist, they never
  constrain — free text stays valid."
- **No pre-save existence check** ties the typed `reference` value back to the `/catalog/references` list,
  and no validator exists for `on` (unlike `transform.summarize`, whose `measures`/`group_by` DO have
  dedicated `configValidators` — `pipeline-config-definition.component.ts:271-274`). Neither
  `ConfigSafetyValidator` nor the pipeline-graph save routes cross-check a join node's `reference`/`on`
  against the catalog or the reference dataset's real columns.
- **No dedicated join-result preview.** The closest thing is the generic, opt-in "Test this Step" button
  available to any `transform.*` node (`ComponentsService.previewFamilyFor`, matches `type.startsWith('transform.')`,
  `components.service.ts:217-221,229-234`) — posts the on-screen config + the tab's already-captured sample
  rows to `POST /components/transform/preview`, rendering `<inspecto-step-preview-result>`
  (`pipeline-config-definition.component.ts:177-201`). It only works if the tab already has sample rows
  from a prior Test-Parse, and is a manual click, not automatic feedback while typing `reference`/`on`.

## 5. Save / validation feedback loop

- **Present at save time:** schema backward-compatibility diff (§1); mapping-CSV shape validation
  (headers/columns present, §2); `transform.summarize`'s `measures`/`group_by` validators (not shared by
  `transform.join` or `transform.map`); Angular reactive-form required/pattern checks.
- **Absent at save time:** target-column existence for mapping rules; `EXPR` SQL syntax (opaque until
  DuckDB executes it); `reference` existence and `on`-column existence for joins; any cross-check between
  a schema edit and its sibling mapping CSV.
- **Real per-node test-run capability exists**, distinct from the two inline preview buttons above:
  `POST /pipelines/authored/{id}/run?to={nodeId}` (`PipelineGraphRoutes.testRun`, backed by
  `PipelineTestRun`/`PipelineExecutor.dryRun`/`PipelineDryRun`, doc'd in
  [`pipeline-test-run.md`](../../backend/engine/pipeline-test-run.md)) bounds a real run to one node's
  ancestor closure over picked inbox files — opened via `PipelineEditorComponent.openRunToHere()` /
  `RunToHereDialog`. This runs the actual compiled SQL, so an `EXPR` typo or a missing join reference WOULD
  surface here — but it is canvas-level and opt-in, and **neither the mapping drawer's "Test mapping" nor
  the join drawer's "Test this Step" cross-links to it.** Two disconnected preview mechanisms exist side by
  side with no shared entry point.
- Anything not covered above (a typo'd `EXPR`, a join key absent from the reference dataset, an
  out-of-range cast) surfaces only at Test-time or full run-time — never at keystroke time, and never as a
  blocking save-time gate (all of the above are bypassable or simply don't exist as checks).

## 6. Concrete gaps (confirmed absent 2026-09-03 — annotated 2026-09-04 with what the redesign did)

Status per gap after the two plans shipped (AUTHOR-SCHEMA-1 is CLOSED in BACKLOG; what survives is
AUTHORING-REDESIGN-1): **1 — partially absorbed** by `transform.sql` (Test this Step reports binder errors
after the fact; there is still no as-you-type validation — a DESCRIBE-backed `/describe` route is the
follow-on). **2 — re-opened 2026-09-04**: the five-verb Fields grid that briefly served as the expression
builder was retired with the SQL-first rebuild (§0); the SQL textarea is the builder, so CodeMirror (BACKLOG
(b)) is now the nearest improvement and the v2 AST table (c) the structured surface. **3 — still
valid** small follow-on (join reference-existence). **4 — partially absorbed**: the drift-into-mapping
check stays valid *because* Layer 1 remains a rule table; still open. **5, 6, 8 — dropped as premature**
for a surface that was redesigned (async validators, auto-recompute, drag-and-drop, RunToHere
cross-link). **7 — absorbed**: a `transform.sql` binder error names the stale column (DuckDB's message
verbatim) instead of defaulting to VARCHAR; the `DIRECT`-rule fallback itself is unchanged on the legacy
path.

1. **No live/as-you-type validation or preview for `EXPR`** — a bare `<input matInput>` with zero syntax
   checking (`pipeline-load-definition.component.ts:218-225`).
2. **No expression-builder UI anywhere** — no function picker, no column picker, no autocomplete for
   `EXPR`; CodeMirror exists in the codebase but is never wired to this field.
3. **No reference-existence or `on`-column-existence validation** before saving a `transform.join` node —
   `reference` only *suggests*, `on` has no validator at all (unlike `transform.summarize`).
4. **No automatic schema-drift detection** linking a schema rename/retype back to mapping rules that
   reference the old column — the only drift warning is the generic parse-vs-schema mismatch banner, which
   doesn't fire on manual `SchemaEditorDialog` edits and never names the broken mapping rules.
5. **Mapping/join preview is opt-in and stale-prone** — both "Test mapping" and "Test this Step" require a
   manual click, depend on sample rows from an earlier Test-Parse, and don't auto-recompute after an edit
   invalidates them.
6. **No drag-and-drop field mapping**, and no re-invocable "auto-map by name" beyond the one-time initial
   seed on an empty map node.
7. **Silent stale-column fallback in the engine** (`DIRECT` rule → `VARCHAR` default) means a broken
   mapping doesn't even fail loudly at test/run time in the way a user would expect — it silently produces
   wrongly-typed data instead of an error, unless the column is truly absent from `raw_input`.
8. **Two disconnected test/preview mechanisms** (drawer-level "Test mapping"/"Test this Step" vs.
   canvas-level `RunToHereDialog`) with no cross-linking, so an author working inside a drawer has no path
   to the more powerful real-run capability without leaving the drawer and finding it manually.

## Grounding

Direct reads of `schema-editor.dialog.ts`, `pipeline-load-definition.component.ts`,
`pipeline-config-definition.component.ts`, `pipeline-parse-definition.component.ts`, `schema-form.component.ts`,
`entity-option-loaders.ts`, `components.service.ts`, `node-attributes.ts`, `package.json` (frontend), and
`SchemaCompatibility.java`, `MappingCsv.java`, `TransformCompiler.java`, `ConfigWriteRoutes.java`,
`ConfigFileSupport.java`, `ParserRoutes.java`, `NodeAttributes.java`, `CatalogRoutes.java`,
`PipelineGraphRoutes.java` (backend) — 2026-09-03.
