---
type: Feature
title: Schema, Mapping & Transformation Authoring
description: Current end-user authoring path for a dataset's schema, the transform.map rule grid, and transform.join lookup config — and the concrete gaps against a complete authoring experience.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipeline-load-definition.component.ts
tags: [feature, pipelines, authoring, schema, mapping, transform, gap-analysis]
timestamp: 2026-09-03T00:00:00Z
---

# Schema, Mapping & Transformation Authoring

> **Scope.** This file is the authoring-UX truth for the three surfaces end users touch to shape data:
> dataset **schema** editing, the **mapping** rule grid (`transform.map`), and **lookup/join** config
> (`transform.join`). It is a child of [Pipeline Editor](pipeline-editor.md) (the drawers described here
> live inside that shell) and reads alongside [`okf/backend/engine/catalog-vs-executors.md`](../../backend/engine/catalog-vs-executors.md),
> which covers the engine side of the same two node types. Written 2026-09-03 as a baseline for a planned
> authoring-UX pass — **current truth, not aspirational**.
>
> **The pass is now two ACTIVE plans (decided 2026-09-03):**
> [`superpower/parse-pane-redesign-plan.md`](../../../superpower/parse-pane-redesign-plan.md) (schema side)
> and [`superpower/sql-transform-v1-plan.md`](../../../superpower/sql-transform-v1-plan.md) (mapping /
> transformation side — a new `transform.sql` Step over the typed source; typing stays declarative on
> Parse). §6 below is what they were scoped against; when they ship, rewrite §2–§4 here as-built.

## 1. Schema authoring

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

## 2. Mapping authoring (`pipeline-load-definition.component.ts` — `transform.map`)

- Rule rows are a `FormArray`, seeded from the node's already-authored `rules` if present, or — if the node
  has none — one straight-through identity rule per schema field, auto-seeded as a pending "proposal" that
  arms Apply (`:483-546`). This is the only auto-map-by-name behavior; it is not a re-invocable action.
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

## 6. Concrete gaps (confirmed absent, not speculative)

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
