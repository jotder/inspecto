---
type: Feature
title: ELT — user guide — Transform and map — the Record Transformer
description: Part 4 of 7 of ELT — user guide: Transform and map — the Record Transformer
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# ELT — user guide — Transform and map — the Record Transformer

Part 4 of 7 of [ELT — user guide](user-guide-elt.md) · previous: [Parse and schema authoring](user-guide-elt-parse-schema.md) · next: [Authoring gap list, enrich, load, ingest options and testing](user-guide-elt-enrich-load-test.md).

## 8. Transform and map — the Record Transformer

### 8.1 The projection slot is a Record Transformer; two nodes, one Step kind

> **Two nodes, one Step kind (TRANSFORMER-TWO-NODES-1, operator 2026-11-15).** The projection slot is a
> Record Transformer **instance** named **"Shape &lt;table&gt;"**, or **"Shape records"** when no table is known
> (`PipelineLift`; before 2026-11-15 the fallback name was "Record Transformer", the Step kind's own label).
> It saves as `processing.map.fields[]` and runs at ingest, in Stage 1. A **Record Transformer added from the
> palette** (`transform.record`) is a different node: a chain `sql` Step (`steps: - sql:`) that runs **after
> load**, at rest, from a `type: pipeline` Job writing the top-level `output_store:`. A chain with no
> `output_store:` never runs, so the save refuses it naming each Step (`ERR_CHAIN_WITHOUT_OUTPUT_STORE`, 422
> on an active Pipeline; `WARN_CHAIN_WITHOUT_OUTPUT_STORE` on an inactive draft). With a store but no enabled
> `type: pipeline` Job naming the Pipeline's file in `pipeline_config:`, the chain never runs either, and the save
> warns (`WARN_CHAIN_WITHOUT_PIPELINE_JOB`) until you add that Job. To shape what lands, edit the
> "Shape …" node; add a palette Record Transformer only to re-shape the landed store into another one.

**2026-09-05 — `transform.map` is DELETED.** The projection slot `PipelineLift` fills between parser and
sink is always a **Record Transformer** (`transform.sql`, id `map` / `map_<key>`); `BuiltinNodeType.TRANSFORM_MAP`,
the `map` recipe verb, `TransformCompiler`'s rule path and the UI's Load pane are gone. A stored
`mapping.rules[]` still LOADS: `DataTransformer.recordFields` converts it to `fields[]` through
`RecordTransform.fromMappingRules` (DIRECT → `keep`, EXPR → `custom`, CONCAT_DT → `date.concat_parts`,
FILENAME_DATE → `date.from_filename`), and `PipelineLift` does the same for `processing.map.rules`. Every
schema under `spaces/` was migrated by `MappingMigrator` (block-list TOON form when a row needs `args`).
⚠ On the ingest lane `date.concat_parts` compiles exactly as CONCAT_DT did (parser timestamp formats +
the date column's source zone) — the same lane-specific special case `keep` has. Plan:
`archived-documents/plans-archive/delete-transform-map-plan.md`. **The legacy spelling is bridged at read time,
permanently**: converted on **every read**, never rewritten to disk; that is why keeping the old read path costs
nothing and why deleting it was refused — migration is by tool, never by breaking stored schemas.

Facts that once named `transform.map` now read as "the projection slot (`transform.sql`, id `map`)":

- `MAP_AUTHORED` (`columns`/`rules`/`fields`) lowers to `processing.map`; `schema`/`csv` on the slot are
  `MAP_DERIVED` — engine-resolved, dropped by `lower`.
- 🔴 The slot is on EVERY lifted graph — readiness logic keys on **authored evidence**, never that Step's
  presence or status (`isProjectionSlot` in `pipeline-editable.ts` is the discriminator — the id grammar, never
  the type: **map is a slot, not a step**). Since 2026-09-25 an empty slot reads `configured`, not
  `unconfigured` (`computeNodeStatus` exempts `isProjectionSlot`): it is the pass-through, and the old "Needs
  config" chip on every new Pipeline's Record Transformer was false.
- **The projection path.** The executor dispatches a transform node on whether it is a **projection** — decided
  by the node carrying record fields, not by its type — sending it either to the projection path or to the
  verbatim-SQL path. The projection path prefers an authored column list outright; otherwise it derives one from a
  schema, from `fields`, or from legacy rules. ⚠ **An authored column list beats both `rules` and `fields` when
  several are present**, and nothing refuses authoring more than one — the only collision refused is a column
  list together with a declared mapping file.
- **Test mapping is gone** (removed with the Load drawer and `transform.map` in `42fa41fe5`; nothing in
  `inspecto-ui/` names it). A mapping is previewed in the standalone Mapping component
  (`mapping-editor.dialog.ts`): *Preview with sample data* dry-runs a synthesized graph with the edited
  rules carried inline. The board row "P4 Test mapping on a generic `parser` node" was deleted as
  obsolete on 2026-11-12.

### 8.2 The `transform.sql` pane — a fields grid over a function catalog

`PipelineTransformSqlDefinitionComponent` (`pipeline-transform-sql-definition.component.ts`, shipped `7e13dd82`;
engine half `98ffc90b`; plan [`sql-transform-v1-plan.md`](../../../../archived-documents/plans-archive/sql-transform-v1-plan.md)),
a bespoke pane reached from the editor's routing arm `dn.type === 'transform.sql'` → `<app-pipeline-transform-sql-definition>`
(`pipeline-editor.component.html`). The `transform.map` arm and `<app-pipeline-load-definition>` were DELETED
2026-09-05 (the projection slot is a `transform.sql`, so it opens this pane); every other `transform.*` kind stays on
the generic `pipeline-config-definition` schema-form. A node TYPE routes to its own pane — the rule that already held
for map/parse/sink. ⅋ The pane is **bespoke — NOT the shared fields editor**: it does not import
`<inspecto-schema-fields-editor>`. Engine half: [Pipeline spec — model](spec-sinks.md).

🔴 **This surface flipped twice on 2026-09-04. The SQL-only middle state is NOT current.** The Step
shipped SQL-only that morning (`24171333`, which deleted the 2026-09-03 fields grid), and the operator
rejected it the same day: *"mapping with functions and parameters is not realistic"* — a raw SQL box is
not an authoring surface for a non-technical user, and the legacy `transform.map` rule grid (four
`transformType` constants, two of which pack their arguments into a `|`-delimited string) is not a
mapping model either. The grid is back, and its verb enum is replaced by a **function catalog with typed
parameters**. D3 (no `where` — filtering stays `transform.filter`), D4 (never parse SQL back into rows — **superseded 2026-09-05** by the bounded reconciler, below)
and D7 (600+ columns stay usable) still hold; D5's five-verb list and D6's lock are superseded by the
catalog and by persisting `fields[]`. (The old SQL-only wording, kept for the record: one SQL `<textarea>` seeded
for a new Step with an explicit column list over the upstream sample columns, else `SELECT * FROM input`, with
"Columns that come out" in the shared fields editor fed from the test run's DESCRIBE `columnTypes`.)

- **The function catalog** (`sql-functions.ts`) is the vocabulary and the ONE place a function's plain
  label, its typed parameters and its SQL template live together. `SqlFunction = {id, label, category,
  template, params}`; `SqlFunctionParam = {name, label, type, default, options, optional}` where `type`
  drives BOTH the rendered control and the escaping: `column` → quoted identifier, `text` → single-quoted
  literal with quotes doubled, `number` → validated then verbatim, `enum` → one of `options` verbatim,
  `sql` → verbatim (the deliberate escape hatch, `custom` only). **23**<!--count:sql-mapping-functions--> functions *(per `sql-functions.ts`: 1 Keep, 8 Text, 4 Numbers, 6 Dates, 2 Logic, 1 Convert, 1 Custom)* across Keep · Text ·
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
- **Persisted shape** (`submit()`): **`{ sql, fields }`** — `submit()` writes both on every Apply and `seedFrom`
  rehydrates the grid from `fields`. 🔴 It is NOT `{ sql }` only, and a stored `fields[]` is NOT dropped.
  `readFields` also opens a node written by the retired five-verb grid, mapping `verb`/`castType`/`formula` onto
  catalog ids. 🔴 **Except in the projection slot** (`isProjectionSlot`, id `map`): there `submit()` writes
  `{ fields }` only (SQL view → the reconciler's rows; a non-projection is refused by `slotSqlRefusal`,
  which blocks Apply), and an empty grid writes `{}` — the pass-through. `processing.map` has no home for
  `sql`, so sending both made every new Pipeline's first Save 422 `UNSUPPORTED_MAP_KEY` (2026-09-25).
- ⚠ `sql` is the single `NodeAttributes` entry — but that is the DECLARED attribute schema, **not** the
  set of keys the engine reads. 🔴 `fields` is a live executable contract: `RowShaper`'s
  `MAP_NODE_CONFIG_KEYS` includes it and runs it as the mapping, and `PipelineEditable.MAP_AUTHORED`
  allow-lists it precisely so `lower` cannot drop it — *"a key that becomes executable without joining this
  allow-list is silently dropped on save"*. ⅋ Do not "clean up" `fields` off a `transform.sql` node:
  that deletes the author's grid and, on the projection slot, its mapping. (An older page called `fields` an
  authoring artifact that "rides the `steps:` chain opaquely" — superseded; see the [plan](plan-elt.md) §2.4.)
- **Fields and SQL are two views of ONE Step (2026-09-05, operator decision; supersedes D4).** The pane's
  header carries a `Fields | SQL` segmented switch (the toolbar's Recipe | Canvas idiom). Fields → SQL is
  the compiler: the generated SQL lands in CodeMirror (`<inspecto-sql-codemirror>`, editable — never a
  `<textarea>`). SQL → Fields is the **bounded reconciler** (`pipeline-transform-sql-reconcile.ts`, pure, 21 specs):
  it accepts exactly a flat `SELECT <item>, … FROM input` projection and turns every item into a row — a bare
  column is *Keep as it is*; an expression matching a catalog template in reverse gets its function and parameters
  back, **but only when re-compiling that row reproduces the expression** (whitespace/keyword case aside — a
  recognition that would rewrite the SQL is not a recognition; bare `CAST` therefore stays custom, never becomes
  `TRY_CAST`); anything else is *Write my own expression* with the text verbatim; an unaliased expression keeps an
  empty name so the grid asks for one. Anything beyond a projection — `WHERE`, `JOIN`, `GROUP BY`, CTE, `DISTINCT`,
  `*` mixed with items — leaves the **Fields tab disabled with the reason** as its tooltip and a line under the
  editor, and never blocks saving (D3 unchanged: filtering belongs to a Filter Step). It is NOT a SQL parser and
  needs no DuckDB extension; a `WHERE` is made structurally visible on the Filter Step instead (§8.6).
  ⚠ **Switching views never changes the SQL; only an edit does.** Dirty is the generated SQL compared to what
  was loaded (not the rows), so Fields → SQL → Fields arms nothing — except that a hand-written SQL shown as
  fields regenerates in canonical spelling, which honestly arms Apply. A node carrying `sql` with no `fields`
  opens in the **SQL view, as written**, with Fields one click away; an Apply from the SQL view writes
  `{ sql, fields: [] }` so it reopens as SQL — no grid is invented behind the author's back, the reconciler
  offers it again from there. **Hand-written SQL is always saved back**, whether or not it reconciles. The
  destructive "Start a field list from the incoming columns" button is gone.
  ⚠ Editing hand-written SQL saves — since 2026-09-04: binding `valueChange` straight to the signal once
  emitted no `dirtyChange`, and `canApply()` once refused the SQL mode; both closed (`onSqlEdited` →
  `touched()`; the binder check is what guards what is typed).
- **Opening a Step never arms Apply.** Seeding emits `dirtyChange(false)`; dirty is a real comparison of
  `{sql, fields}` against what was loaded. ⚠ This was a live defect on the legacy `transform.map` pane
  (a Step showed "unapplied" from a single click with no edit); the grid has a regression test for it.
- **Test against sample** reuses "Test this Step" verbatim: `ComponentsService.previewTransform({type,
  sql}, sampleRows)` with the GENERATED sql — `transform.sql` qualifies by the `transform.*` prefix. The
  run fills each row's **Sample** value.
- **Types arrive without a run (2026-09-04, BACKLOG (a) closed).** A 300ms-debounced effect posts the
  generated SQL and the upstream columns to `POST /components/transform/describe`
  (`ComponentsService.describeTransform`, over `TypeFlow.describe`'s execution-free `DESCRIBE`) and fills
  **Comes out as** from the answer; a preview run's types are the fallback, not the source. The debounce
  timer is cleared on `DestroyRef.onDestroy` so a drawer closed mid-keystroke fires nothing.
- ⚠ **The DESCRIBE is sent the DECLARED upstream types** (`[upstreamColumnTypes]`, read off the parser
  companion's `raw.fields[]` by the editor and keyed by name). Assuming VARCHAR for every column made
  DuckDB REFUSE valid SQL — `AMOUNT * 2` over a declared DOUBLE answered "No function matches …
  *(VARCHAR, INTEGER_LITERAL)" — and that refusal blocked Apply. A false refusal in an authoring pane is
  worse than no derivation at all. Declared type wins; the sample row's shape is the fallback.
- **Binder errors are as-you-type, and ONLY a 422 blocks Apply.** DuckDB's message renders in a warning
  alert above the grid ("This does not run yet") and clears `canApply()`. The route strips the JDBC
  wrapper line the driver puts in front of every failed `DESCRIBE` ("Attempting to execute an
  unsuccessful or closed pending query result"), which was otherwise the FIRST thing the author read;
  everything from the real error on is verbatim, because it names the column and lists the candidates. Any other failure — offline, a 404 on an older control
  plane, a 503 — says nothing about the SQL, so it clears the alert and leaves Apply available: a pane
  that locks the author out because the backend is unreachable is worse than one that saves unverified
  SQL (three specs in `Transform pane: live schema derivation` pin all three arms).
- **Authoring without sample rows.** The grid seeds from `[upstreamColumns]`, passed by the editor
  (`upstreamSchemaColumns()`): the field names of the parser's companion schema when it declares any,
  else the parsed sample's keys. Before this, a Step opened with an empty grid until the operator ran a
  parse.
- 🔴 **The SCHEMA's names win over the sample header** (2026-09-25, found by driving the UI). The parse
  Step renames every raw column to its declared field by selector, so the run hands the next Step
  `ORDER_ID`, never the header's `order_id`. The editor used to prefer the sample's keys, which listed
  every field in the header's case AND — because `[upstreamColumnTypes]` is keyed by schema name — matched
  no declared type, so every field read VARCHAR however the test parse typed it. The sample rows the pane
  shows are re-keyed the same way (`schemaNamedRows`, `pipeline-transform-sql.ts`: a positional selector
  takes the i-th column, a name selector that key). ⚠ The companion is **re-read after every parse
  Apply** (`parserSchemaRevision`): a scaffolded pipeline names `<id>_schema.toon` before the file
  exists, so the first read 404s and the Apply that writes it leaves `schema_file` unchanged — tracking
  the reference alone kept the empty pre-Apply read.
- **Duplicate output names refuse.** `compileFields` marks every row sharing a trimmed name with a
  problem ("Output column names must be unique"), which blocks Apply — DuckDB would otherwise accept the
  SELECT and hand the sink two columns of the same name.
- **Row order is the output order** — ▲/▼ per row reorder `fields[]`, and `generateSql` emits in that
  order.
- **Audit boundary:** saving a `transform.sql` node yields one WARNING `SQL_STEP_UNAUDITED` from the
  validator; a WARNING never blocks save (proven by test — [Pipeline spec — model](spec-pipeline-model.md)).
  The SQL guard (`SqlGuard`) refuses data-definition statements, multiple statements and file-reading functions at
  the describe preview, at execution and — since 2026-09-09 — on save (`SQL_STEP_REFUSED`, §13.2).

**D10 — Transform opens on *Changed*, not *All*** (2026-09-04, built the same day). Default filter is `changed`,
with a banner stating what is implied — *"N other fields pass through unchanged — nothing to do for them"* — and a
**Show them** action. A wide feed becomes three rows to read instead of six hundred. ⚠ **This inverts the
Simple-vs-SQL argument** that justified the fields grid. The grid is **O(n) in fields**; SQL is **O(1) in fields and
O(k) in changes**. For wide data `SELECT *` with three overrides is genuinely the simpler surface — which is why the
grid's model must be *"everything passes through; show me what I changed"* rather than *"enumerate every field"*.
Keep this in view before adding any feature that requires the grid to list all fields.

### 8.3 Column metadata on a chain Step (AUTHORING-REDESIGN-1 (e), BUILT 2026-10-11)

Each row of a CHAIN Step's grid has a **Describe** toggle (`aria-expanded`, one row open at a time) with the
schema field's four keys — description · unit · classification · synonym — and nothing else.

- **Stored on the `fields[]` row** (`SqlField.description?/unit?/classification?/synonym?`), so a rename
  carries it and a removed row takes it along; a blanked value deletes the key. `fields[].name` is the
  authoritative output column (the grid generates the SELECT). A Step applied from the **SQL view** writes
  `fields: []`, so it carries no metadata, and neither does a raw-SQL Step — when any row is described,
  that Apply first asks (`InspectoConfirmService.confirmDestructive`, "Drop column metadata?", naming the
  count and the masking loss); Cancel leaves the grid and the SQL untouched.
- ⛔ **Not offered in the projection slot** (`isProjectionSlot`): its `fields[]` compile into
  `processing.map`, which the Catalog never reads, so the metadata would be dead config.
- **Save gate:** `ConfigRoutes.stepColumnMetadataFindings` (via `SaveGate`, so `/config/write`, patch and
  the graph PUT) refuses with **`ERR_STEP_COLUMN_METADATA_INVALID`**, always ERROR, when a value is a map or
  list, or when a row carrying metadata has a blank name or a name another row also outputs
  (case-insensitive). Route-branch sub-chains are walked too. Proof: `ControlApiStepColumnMetadataTest`.
  The classification vocabulary is the schema's: closed per Space and refused at save when unknown
  (`ERR_UNKNOWN_CLASSIFICATION`), normalised upper-case.
- **The reader — Catalog:** `PipelineSchemas.stepOutputs` → `MetadataGraphBuilder.addStepOutputs` adds a
  **`NodeKind.STEP`** node `step:<pipeline>/step<n>` (n counts `sql` Steps with `fields[]`, depth first,
  branches included; label `sql #n`), `origin -DECLARES-> step`, `step -DESCRIBES-> col:<pipeline>/step<n>/<NAME>`
  (same attrs as a schema column, minus `type`, which is not known statically), and `MATERIALIZES` from the
  LAST such Step to the pipeline's event tables. `CatalogOverlay` treats STEP as stage 1. UI: shape
  `triangle`, colour token `STEP`, label "Step" (`catalog-graph.ts`, `CATALOG_NODE_KINDS`). Proof:
  `MetadataGraphServiceTest`, `ControlApiCatalogTest.aSqlStepsDescribedColumnsAreReadableOverHttp`,
  `catalog-graph.spec.ts`.
- **The reader — masking (operator decision 2026-10-11, option B, ADDITIVE ONLY):** a Step column's
  sensitive classification is merged into `EvidenceMasker.schemaClassification` strictest-wins — the one
  resolver evidence masking, `publish.postgres`, publication approval, `transform.mask` and Link Analysis
  share. ⛔ It never clears `UNKNOWN_LINEAGE`: a `sql` Step plus a classified raw column still fails
  closed. Proof: `EvidenceMaskerTest`, `PostgresPublishJobTest` (Step-classified column dropped; stripping it
  breaks the approval; fail-closed unchanged). ⚠ The lineage reader loads pipelines with
  `loadForValidation`, so an ACTIVE `steps:` pipeline without `output_store:` (which does not load) still
  reads as unreadable → fail closed.
- **Not built:** option (C) — the Step's declaration REPLACING the fail-closed rule (it would let an
  omission unmask a column); see BACKLOG `AUTHORING-REDESIGN-1` (e).

### 8.4 Expression building

- **The expression-building surface** is the Transform pane's function picker per row over `SQL_FUNCTIONS`
  (**23**<!--count:sql-mapping-functions--> functions in 7 categories), with a form control per declared parameter and
  "Try it on the sample" (§8.2).
- **CodeMirror has THREE hosts**, not two: the SQL query workbench (`inspecto/data-table/sql/`), the
  enrichment editor (`inspecto/enrichment/enrichment-editor.component.ts`) and **the Transform pane's SQL
  view** (`pipeline-transform-sql-definition.component.ts`). Monaco/ace are absent entirely.
- No SQL function catalog is surfaced on the join authoring surface — only free hints in tooltips/captions.

### 8.5 The step workbench — what a Step reads, and which of it the Step uses (SHIPPED 2026-09-07)

Three authoring controls built as WORKBENCH-S4 (design: `archived-documents/plans-archive/step-workbench-s4-design.md`).
**No new endpoint** — every fact on screen is one the editor already held.

- **Field list (`step-workbench.component.ts`, `step-workbench-fields.ts`).** The upstream columns with their
  declared types, split into **used by this step** / **not used**, filtered by a type-ahead, clickable. It
  rides beside the `transform.sql` pane in BOTH views: in Fields it reflects the generated SQL, in SQL the
  hand-written one.
  - 🔴 **Reference detection cannot use a word boundary.** `_` is a word character, so `amount` matches inside
    `total_amount` and the list would claim a column is used when nothing reads it. The match requires a
    non-identifier character on each side — which is what a SQL identifier boundary actually is. Both specs
    pin the `amount` / `total_amount` pair.
  - ⚠ It is a **lexical scan, not a parser**, on purpose: a SQL parser here would be a second implementation
    of the dialect the engine owns. The failure mode is a field shown in the wrong GROUP — cosmetic — never
    a wrong config write, because the workbench **writes nothing**. It emits a name; the host decides.
  - **Operator decision 2026-09-07:** the list is capped (`FIELD_LIST_CAP = 50`) with the filter always
    visible. 🔴 The cap applies to what is RENDERED, never to what is SEARCHED — filtering runs over every
    column and the result is then capped, so a column at position 300 is one keystroke away. A cap that also
    narrowed the search would hide columns with no way to reach them.
  - **A click** inserts the quoted identifier at the caret in the SQL view (`SqlCodemirrorComponent.insertAtCursor`,
    which dispatches through the editor's own update path so `valueChange` carries the new document), and in
    the Fields view adds a `keep` row — or, when the column is ALREADY an output, searches the grid for it
    rather than adding a surprising duplicate.
- **Input strip (`step-workbench-inputs.ts`).** One line saying what the Step reads, from the authored
  `edges[]` — the canvas is the source of truth for edges, so this is a projection of the model, not a fetch.
  The relation is named whenever it is not a plain `DATA` edge, because a route branch is a different input.
  - 🔴 **The planned merge/join input PICKER was refused on grounding.** There are no "input keys" to write:
    `transform.merge` declares no attributes at all and takes its inputs as a positional list the executor
    builds from the graph edges (`RowShaper.merge`), and `transform.join`'s second input is a `reference`,
    not an inbound edge. A picker would have written a key nothing reads. Edges stay authored on the canvas.
    ⛔ Stronger than that: `transform.merge` is absent from `PipelineEditable.LOWERABLE` **by decision**
    (§3.4) — do not build it a config surface (BACKLOG §6).
- **Summarize grouping (`summarize-editor.component.ts`, `summarize-editor.ts`).** A group-by chip row and a
  measures table in place of two bare `list` controls, hosted as a REGION INSIDE the generic
  `pipeline-config-definition` pane — not a fourth routing arm, because that pane's S2 decision is explicitly
  one pane for every remaining kind. It writes the same two flat string lists it always did.
  - 🔴 **Two traps this shape creates, both spec-pinned.** (1) Two surfaces for one key: the schema form must
    STOP rendering `group_by`/`measures`, which `formSpecs()` does — while `specs()` stays whole, because
    `buildConfiguredNode` reads the spec list to place each key. (2) Apply would DELETE both keys once the
    form no longer carries them, so `buildNode()` merges the editor's values back in.
  - ⛔ The aggregate options are `MEASURE_AGGS`, read from `measure-grammar.contract.json` and pinned to the
    engine's `MeasureCompiler.AGGS` by `MeasureGrammarContractTest`. The component spec asserts the rendered
    `<option>` list equals it, so a hard-coded list cannot creep back.
  - 🔴 **A measure this UI cannot parse round-trips verbatim** (`MeasureRow.raw`) and is refused by
    `validate()` with the grammar's own message — never dropped. Opening a node and pressing Apply must not
    be a way to lose what someone wrote. `transform.summarize`'s `measures`/`group_by` have dedicated
    `configValidators` (`pipeline-config-definition.component.ts`).

### 8.6 Filter — the row predicate's structure (SHIPPED 2026-09-23, `AUTHORING-REDESIGN-1` (c))

A `transform.filter` Step's drawer shows **What gets kept** beneath its `where` field: DuckDB's own parse
tree of the predicate, rendered as a read-only table. Plan of record (archived):
[`authoring-ast-table-design.md`](../../../../archived-documents/plans-archive/authoring-ast-table-design.md) §7.

- **Home (Q1).** On the Filter Step, not the `transform.sql` pane, whose D3 ("filtering belongs to its own
  Step") stands. `transform.join` has no SQL to parse and is out of scope.
- **Seam.** `POST /components/sql/ast` `{sql, fragment: 'predicate'}` (`ComponentRoutes` → `SqlAst`) wraps
  the text as `SELECT 1 FROM input WHERE <pred>`, runs `json_serialize_sql(?::VARCHAR)` on a **sealed
  `SqlSandbox`** (Q4) and returns the `where_clause` node verbatim. A parse error is a **200 `{ok:false}`**
  with the position rebased onto the author's text; a "predicate" carrying its own ORDER BY / GROUP BY /
  LIMIT / UNION is refused `NOT_A_PREDICATE` by comparing the tree to a fixed skeleton. **No `SqlGuard`**
  (Q3): nothing binds; ⛔ not a precedent for `/components/transform/describe`, which binds. (The precondition —
  the engine handing back its own parsed statement as data on a sealed connection — was discharged 2026-09-07.)
- **Read-only, for good (Q2).** No AST→SQL path exists and none may be added: the round trip drops
  comments and re-spells everything. Editing goes through the shipped Query Core instead, and only when
  (1) `astToConditionGroup` recognises the tree, (2) the text carries no comment, and (3) `compileWhere`'s
  spelling re-parses to the **same** tree (`sameSqlStructure`, offsets ignored). The result is written into
  the `where` field only on an explicit accept of the exact before/after; untouched text stays byte for byte.
- **Contract (Q5).** `contracts/sql-ast.contract.json` pins ONLY the node types and keys the SPA reads;
  `SqlAstContractTest` proves the engine emits them, `sql-ast.spec.ts` proves the reader handles exactly
  them. ⛔ Never read `query_location`: start-only, and a CAST carries 2^64-1, which a JS number cannot hold.
- **Degrade.** Parse error → `<inspecto-alert>` warning, saving never blocked; route unreachable → a quiet
  status line; an unrepresentable part stays in the table as an explained row, never dropped.
- ⚠ Traps: `NOT a = 1` parses as `a != 1`; `(a AND b) AND c` flattens to one conjunction; `TRUE` is a CAST
  of `'t'`; a DECIMAL arrives as an unscaled integer + `scale`. Nesting in the table is carried by
  visually-hidden "Level N" text, not `aria-level` (valid only inside a `treegrid`).

### 8.7 Lookup / join — `transform.join` (generic `AttributeSpec` form)

- Rendered by the shared `PipelineConfigDefinitionComponent` + `InspectoSchemaFormComponent`, driven by
  `NodeAttributes.TRANSFORM_JOIN`'s spec (`node-attributes.ts`, mirroring
  `NodeAttributes.java`): `reference` (`autocomplete`, required, placeholder `reference/rates`) and
  `on` (`list`, required, join key columns) and `match` (`select`, optional, default `exact`).
- **`match` (since 2026-11-22, `LOOKUP-1` P1)** picks how the keys match:
  - `exact` (the default, and what an absent `match` means) — every `on` column equal: the `LEFT JOIN … USING`
    the node always ran.
  - `longest_prefix` — the **last** `on` column matches the Reference row whose key is its **longest prefix**, both
    read as text (phone number → operator, card BIN → issuer); earlier `on` columns are equal-matched, so
    `on: [country, msisdn]` is a tiered key. An empty Reference key is a catch-all. A row with no match keeps its
    columns with NULLs. The output is the input's columns, then the Reference's non-key columns. A Reference with
    two rows for one key is refused before the join, naming the key, because the pick would be arbitrary.
    Executed as N × |distinct key lengths| hash probes, not a `starts_with` nested loop (`RowShaper.longestPrefixJoin`).
  - Anything else is refused at save (`JOIN_MATCH_UNKNOWN`) and at run.
  - Written the same way in a `steps:` `join:` entry, the `processing.join` block, and a recipe
    `transform: {join: references/x, on: k, match: longest_prefix}`; every save path keeps it.
- **The keyed form (since 2026-11-22, `LOOKUP-1` P1b)** — set `key:` (the Reference's key column) and every `on`
  column becomes an **independent** input looked up in it: three phone-number columns against one dialling-code
  table in one Step. Keys compare as text.
  ```toon
  - join:
      reference: reference/dial_digit
      key: dial_digit_key
      match: longest_prefix                       # or exact
      on[3]: SERVED_MSISDN, OTHER_MSISDN, THIRD_PARTY_MSISDN
      columns[3]: dial_digit_key, iso_country_code, provider_desc   # default: every Reference column
      as: "{on}_"                                 # default; → OTHER_MSISDN_provider_desc
      default[1]: dial_digit_key=-99              # or a map; the value when nothing matched
      exact_when: "dm_ind = '1'"                  # longest_prefix only: rows matching only the whole value
      duplicates: last                            # refuse (default) | first | last
      order_by: update_date_time                  # an expression over Reference columns; needed by first/last
  ```
  - Returning the key column gives the **matched prefix**. A returned name clashing with an input column (or with
    another returned one) is refused, naming it — change `as`. With several `on` columns `as` must contain `{on}`.
  - `exact_when` rows count only when the key equals the whole value; a longer value falls back to a shorter key —
    the legacy telco rule for toll-free and premium numbers.
  - `duplicates: refuse` names the first repeated key; `first`/`last` keep one row per key by `order_by`, ties broken
    by the row's text so the pick never changes between runs.
  - **Cost:** every distinct value across all `on` columns is resolved once (N distinct × |distinct key lengths| hash
    probes), then joined back per column by equality. Measured 2026-11-22 with a 9.8k-key dialling-code table: 5M
    rows × 3 numbers in 2.3 s (three separate prefix joins: 6.6 s). No cache to keep: each run reads the current
    Reference.
  - A keyed join always saves as a `steps:` entry — `processing.join` holds `reference`/`on`/`match` only. Save
    refuses impossible settings with `JOIN_KEYED_INVALID` (a keyed setting without `key`, `exact_when` with `exact`,
    an unknown `duplicates`, `first`/`last` without `order_by`, several `on` with an `as` lacking `{on}`); whether
    the named columns exist is checked when it runs.
  - **`filter`** (since 2026-11-22, `LOOKUP-1` P1c, every keyed mode) — an SQL condition on Reference columns; only the
    rows meeting it take part (one table holding several rate kinds: `filter: "lookup_key = 'SDR_RATE'"`).
- **`match: range` (since 2026-11-22, `LOOKUP-1` P1c)** — a value matches the Reference row whose period holds it.
  It is keyed by `from:` / `to:` (Reference columns, either may be omitted), not `key:`.
  ```toon
  - join:
      reference: reference/service_range
      match: range
      from: valid_from                            # inclusive; an empty value = no start
      to: valid_to                                # inclusive; an empty value = no end (the current rate)
      filter: "lookup_key = 'SDR_RATE'"
      on[1]: CALL_DATE
      columns[1]: lookup_output
      as: "SDR_"
      duplicates: last                            # overlapping periods: refuse (default) | first | last
      order_by: valid_from
  ```
  - Values compare in their **own type** — a date as a date, a number as a number (`10` is above `9`), not as text
    the way `exact` / `longest_prefix` compare. Bound the columns to real dates in the Reference's schema: a text
    date such as `dd/MM/yy` never orders correctly. Against a **`DATE` bound the value is compared as a date**: a call
    at `10:30` on a period's last day is inside it, and a value that is no date matches nothing (no error).
  - **Overlapping periods** make the match ambiguous. `duplicates: refuse` stops the run, naming two overlapping
    periods; `first` / `last` keep, per value, the matching row ordered by `order_by`. Because bounds are inclusive,
    `[1 Jan – 31 Jan]` and `[31 Jan – 28 Feb]` overlap on 31 Jan. A date that does not exist (`31/06/26`) parses as
    empty and becomes an open end — which then overlaps every later period, so `refuse` finds it.
  - A value in no period (a gap) or a NULL value matches nothing; `default` fills it as in the other keyed modes.
    Each distinct value is resolved once, then joined back per `on` column.
  - Save refuses `match: range` without `from`/`to`, with `key`, and `from`/`to` on any other match (`JOIN_KEYED_INVALID`).
- **`reference` is autocomplete with real suggestions, never a hard constraint:**
  `referenceOptionLoader()` (`entity-option-loaders.ts`) calls `CatalogService.references()`
  (backed by `GET /catalog/references`, `CatalogRoutes.java`) and maps results to
  `reference/<label>` options, wired via `[optionLoaders]` (`pipeline-config-definition.component.ts`).
  Per the shared form's own doc comment (`schema-form.component.ts`): "suggestions assist, they never
  constrain — free text stays valid."
- **Save-time checks (since 2026-09-06):** `PipelineValidator` refuses `JOIN_REFERENCE_MISSING` / `JOIN_ON_MISSING`
  and `PipelineGraphRoutes` refuses `UNKNOWN_JOIN_REFERENCE` (a `reference/<pipeline>` naming no loaded pipeline, or
  one without `produces: reference`). Which columns the reference carries stays the dry run's question — nothing
  cross-checks `on` against the reference dataset's real columns.
- **An authored reference has a home for two node kinds only**; on any other kind it is refused by name
  (2026-08-14 — the picker had been keyed on category rather than type, so lowering dropped every other reference
  **in silence** while the save returned success).
- **No dedicated join-result preview.** The closest thing is the generic, opt-in "Test this Step" button
  available to any `transform.*` node (`ComponentsService.previewFamilyFor`, matches `type.startsWith('transform.')`,
  `components.service.ts`) — posts the on-screen config + the tab's already-captured sample
  rows to `POST /components/transform/preview`, rendering `<inspecto-step-preview-result>`
  (`pipeline-config-definition.component.ts`). It only works if the tab already has sample rows
  from a prior Test-Parse, and is a manual click, not automatic feedback while typing `reference`/`on`.

### 8.8 The deleted Load pane — history of the decisions it encoded

The Load pane (`pipeline-load-definition.component.ts`) was DELETED 2026-09-05. The catalog carries ONE entry for
this surface — `transform.record`, *Record Transformer*, folded 2026-09-04 from `transform.expression` +
`transform.cast` + `quality.cleanse.trim` — pointing at `transform.sql`. Type *coercion* folded in with it; the
schema *contract* stayed on Parse as `quality.schema.validator`, re-scoped, because the declared types are the
cast-failure audit's denominator. The Components feature's mapping editor
(`modules/admin/components/mapping-editor.dialog.ts`) still authors `rules[]` for a `mapping` COMPONENT; the engine
reads those as fields at run time. What follows is kept as HISTORY of the decisions the pane encoded — and 🔴 every
constraint below is still armed on a stored `mapping.rules[]`, which `RecordTransform.fromMappingRules` converts to
`fields[]` (read path only since 2026-09-05; nothing AUTHORS these any more):

- Rule rows were a `FormArray`, seeded from the node's already-authored `rules` if present, else from a
  legacy `columns: [{name, expr}]` projection read as DIRECT rules (2026-09-04 — before that a
  columns-authored node showed an EMPTY grid, so the operator saw no mapping where the engine ran one),
  else one straight-through identity rule per schema field, auto-seeded as a pending "proposal" that
  armed Apply. This was the only auto-map-by-name behavior; it was not a re-invocable action.
- ⚠ **Applying rules REPLACED `columns`.** `MAP_AUTHORED` holds both keys and `RowShaper.columnsOf` returns
  `columns` before it looks at rules, so a node keeping both would execute the old projection over the
  operator's saved edit — a write that is real and dead at once. `submit()` therefore deleted `columns`
  whenever it wrote a non-empty `rules` (pinned by `emits rules and drops the columns key they replace`).
  An empty grid deleted nothing: a stray Apply must not wipe an authored projection.
- **Per row:** target column (free-text, pattern-validated); rule — `mat-select` of `transformType`
  (`DIRECT` / `EXPR` / `CONCAT_DT` / `FILENAME_DATE`, from `TRANSFORM_TYPES`); the from-column control varied by
  type — `DIRECT`: dropdown of schema field names; `CONCAT_DT`: Date + Time column dropdowns; `FILENAME_DATE`:
  filename-column dropdown + free-text Prefix + free-text Format, placeholder `%Y%m%d`; `EXPR`: **a single
  free-text `sourceExpression` input**, tooltip "A per-row scalar expression, emitted verbatim — you own any
  explicit cast".
- **`EXPR` was pure free text** — no syntax check, no autocomplete, no column picker; the engine emitted it
  **verbatim** into the compiled SQL (`TransformCompiler.java`), unparsed until DuckDB ran it. A whitespace trim
  was only achievable by hand-typing `TRIM(col)`.
- **The two specialised types pack parameters into `sourceExpression` as `|`-delimited positions** (`MappingCsv`
  drops every other key): `CONCAT_DT` = `<dateCol>|<timeCol>` always both (compiler reads `parts[1]`
  unconditionally); `FILENAME_DATE` = `<col>|<prefix>|<strptime>` with trailing blanks DROPPED, not emitted (an
  empty third position interpolates `''` into `TRY_STRPTIME`), and may only write **`EVENT_DATE`** (enforced
  server-side; the pane refused rather than posting a guaranteed 422). A transform type nothing has heard of (a
  hand-authored `LOOKUP`) is preserved, never dropped — *not offering* must never become *destroying*.
- **Silent stale-reference fallback (backend):** a `DIRECT` rule naming a column no longer in the schema did not
  error at mapping-save time — `TransformCompiler.direct()` did `fieldTypes.getOrDefault(source, VARCHAR)`,
  silently defaulting the cast; the mismatch surfaced only as a DuckDB "unknown column in `raw_input`" error at
  execution time. (On `transform.sql` a binder error now names the stale column instead; the `DIRECT` fallback on
  the legacy path is unchanged.)
- **Preview was opt-in and server-round-trip:** "Test mapping" posted the rules + the Parse tab's captured sample
  rows to `POST /config/preview/schema`, rendering a capped result table (`MAX_PREVIEW_ROWS=20`); any rule edit
  invalidated it (`invalidateMapping()`) and it did not auto-rerun. No drag-and-drop field mapping.
- **Client-side `ruleProblem()`** only structurally validated `CONCAT_DT`/`FILENAME_DATE` arity, mirroring the
  engine's `MappingRules`. **Mapping CSV save validates shape only:** `MappingCsv.parse` checks required headers
  and row width, never that `targetColumn` exists in the schema or that `sourceExpression` is valid SQL
  (`MappingCsv.java`; the write path `ConfigWriteRoutes.java`, `splitMapping`, does the same).

## Changes

- 2026-09-09: the SQL mapping function count corrected from `sql-functions.ts` — earlier pages said "~20" and "24".
