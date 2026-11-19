---
type: Feature
title: ELT — user guide — Parse and schema authoring
description: Part 3 of 7 of ELT — user guide: Parse — drawer vs dialog, the claimed slot and the sample thread; Schema authoring
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# ELT — user guide — Parse and schema authoring

Part 3 of 7 of [ELT — user guide](user-guide-elt.md) · previous: [Steps, Step configuration and Collect](user-guide-elt-steps.md) · next: [Transform and map — the Record Transformer](user-guide-elt-record-transformer.md).

## 6. Parse — drawer vs dialog, the claimed slot and the sample thread

The Parse surface itself — tabs, options, columns grid, Grammar CSV round-trip, plugin formats — is
[Grammar configuration](../config/user-guide-grammar-config.md)'s. What is the editor's follows.

### 6.1 A Parse Step is three things, and the drawer is its one home (decision 2026-09-06)

Operator's model: a parse Step is **a Grammar** (how the source format is read — optional when the format is
hardcoded), **the Schemas it emits** (its events), and **its other properties**. The drawer's Parse pane is
organised on those three sections, and it is the home for every parse node the drawer can represent — including
a **dangling grammar binding**, which opens the drawer with the Grammar section flagged "template missing"
instead of falling back to the custody dialog. `GrammarEditorDialog` survives only for binary fixed-width and an
unmappable generic `parser`. **Shipped 2026-09-06 (PARSE-HOME-1):** `isDrawerParse` admits a dangling per-format
binding; `definitionDraft` seeds a blank Grammar of the node's frontend and KEEPS `use:` (Apply, not opening,
replaces the binding); the host's `danglingGrammarId` feeds the pane's `[grammarMissing]`, whose Grammar section
renders the warning naming the template. Pinned by the editor and pane specs. The stale comment claiming bound
nodes keep the dialog went with it.

**The sectioned Parse pane** (`pipeline-parse-definition.component.ts`, redesign shipped `d012f721`
2026-09-04) serves every parse subtype by `frontend`; the **grammar drawer** (`<inspecto-grammar-editor>`,
sample-as-thread: choose file → view → type → options → test → table / tree) is the one Grammar surface for the
five DuckDB-native formats, with `ParserTreeComponent` for hierarchical previews; binary fixed-width keeps the
`GrammarEditorDialog` by operator decision (its geometry lives in `ingester_config`). Previews call
`POST /config/preview/parsing` and `/config/preview/schema` (`ConfigPreviewRoutes`). Field **tiers**
(`required | optional | advanced`, D13) drive disclosure — ⚠ every `tier: 'required'` field still ships
`required: false` validators (an open observation question — [plan](plan-elt.md)). ⚠ **The lone hard default in the
entire parser/grammar surface is `transform.route`'s `mode: 'case'`** — so "required" means *shown in the top
disclosure tier*, never *validator-enforced*. **A Grammar lives inline on its Step**; a **Grammar Template** is a
copy source (operator, 2026-08-15) — the `use: grammar/<id>` form stays read-supported, never authored; "Save as
template…" was replaced by a **Grammar CSV** round-trip (2026-08-19). The Onboarding Parsing stage **no longer
exists** (P6-e); the editor is the guided surface.

### 6.2 Custody, the claimed slot, and one sample thread per tab

- **Custody split**: a per-format parse Step (`PARSE_NODE_FRONTENDS`) defines in the right-dock
  Parse drawer; `GrammarEditorDialog` (`grammar-editor.dialog.ts`) survives ONLY for **two** shapes —
  binary fixed-width, and a generic `parser` whose config maps to no frontend (`isDrawerParse`). A **dangling**
  `use: grammar/<id>` opens the DRAWER (§6.1).
  **A parser is always format-specific — never author the generic type** (operator directive
  2026-08-21; D3 2026-08-31: a new pipeline asks for the parser format). A generic `parser` whose config maps to a built-in frontend migrates on edit:
  `definitionDraft` presents it re-typed with `csv_settings` folded in under the engine's own merge
  precedence (`parsing:` wins), and Apply converges the Step on the specific type. Fail-closed to
  the dialog otherwise.
- **The parse slot is claimed, not queued**: a flat config has ONE `parsing:` block, and every new
  pipeline lifts with a generic `parser` placeholder. `claimParseSlot()` runs before either palette
  entry point — an untouched placeholder is **re-typed in place** (same id, both edges intact); a parse
  Step carrying config is authored work and the add is REFUSED naming the format that holds the slot.
  Predicate: `isParseNodeType` — ⛔ never the catalog CATEGORY (the rule must hold before the catalog
  resolves). ⛔ This is NOT auto-connecting: an ordinary Step still landed unconnected on purpose
  (considered and declined). 🔴 **That decline is AMENDED (D8, signed 2026-09-22).** It rested on the Recipe
  view's insert-between as the author's alternative; `<app-pipeline-step-cards>` was deleted in `6d3c68fa`
  (2026-09-18), so there is no one-gesture insert anywhere and inserting mid-chain costs three graph
  operations. The narrow reopening: when a node **is selected**, an added Step is wired **after** it —
  the selected node's outgoing `data` edge is rewired through the new node; with nothing selected the
  bare add stands, because there is no anchor to guess from. ⚠ Validation keeps catching orphans either
  way (*“has no input connection”*), so this is ergonomics, not integrity. Tracked as `WB-14`; row
  `PALETTE-ADD-DROPS-ORPHAN-1`.
- **One sample thread per tab**: `DefinitionStateService` instances live in a `Map<id, …>` beside
  `cachedModels`, born in `select()`, dropped by `forgetTab()`. 🔴 A Map, NOT a `providers:` entry —
  DI providers are static per component instance and this editor is ONE instance hosting every tab.
  `InspectoSamplePanelComponent` takes the thread as an **input** (also keeps it pure — the D2
  rule every definition pane follows). ⚠ Reading the Map inside the `sampleThread()` computed does
  not track it — deliberate; `selectedId` is the only dependency that can change the answer.
- **The Grammar dialog shares the thread too** (2026-09-25). A new Pipeline's placeholder parse Step is
  a generic `parser` with no format, so its first configuration happens in `GrammarEditorDialog`, whose
  editor runs `sampleMode: 'own'`. It used to keep that sample to itself: a builder pasted and
  test-parsed there, pressed Save, and found the drawer's Sample card empty and the Record Transformer
  offering 0 fields until the sample was pasted **again**. `openNodeConfig` now passes the tab's
  `sampleThread()` as `GrammarEditorDialogData.sampleThread`; the dialog seeds its sample box from it,
  captures the sample into it on a **table** Test parse and on **Save** (a no-op when it is the sample the
  thread already holds — re-capturing resets every downstream result), and its `previewFn` writes the
  parsed rows exactly as the drawer's does, **clearing them on a failed re-parse**. The captured sample is
  named *"sample from Edit Grammar"* on the Sample card.
- 🔴 **The Grammar dialog's Save writes the Schema, exactly as the drawer's Apply does** (2026-09-25,
  found driving a new Pipeline `web_orders`). Save used to patch the node in memory only, so after Test
  parse (6 cols) → Save the strip still read *Schema: Not configured*, Activate was refused on Schema,
  and the drawer then opened over the carried sample with **Apply disabled** — no visible way to create
  the schema. Now, with a table Test parse (or the thread's parse it opened over), a flat format, and no
  `schema_file` on the node yet, Save writes `<pipeline>_schema` FIRST and closes naming it in
  `schema_file`; a failed write keeps the dialog open with an error alert (the node never names a missing
  file). ONE write, not two: `parse-output-schema.ts` holds the derivation (`derivedSchemaRows`,
  `inferredSchemaTypes`) and the re-read-then-write (`writeParseSchema`, with the SCHEMA-FILE-NAME-1
  `file:` naming and the partitions/extras carry-through) that `PipelineParseDefinitionComponent.submitWithSchema`
  also calls. A node that already names a schema is left alone (the drawer re-reads, never re-derives
  over a saved one). The thread's `ParsingPreview` now carries the parse's `columnTypes`, so a surface
  opening on it derives the same typed schema.
- **A derived field name keeps the sample header's case** (`FIELD-NAME-CASE-1`, builder pilot
  2026-09-25). `sanitizeIdentifier` (`inspecto/schema/schema-fields-editor.component.ts`) used to
  uppercase every parsed column, so a lowercase CSV header (`match_id,date,…`) was written as
  `MATCH_ID, DATE, …` and — since the declared name IS the landed column — the Parquet and every UI label
  came out shouty. Nothing downstream needed it: `Identifiers.validate` accepts `[A-Za-z_][A-Za-z0-9_]*`
  and the engine writes the declared name verbatim. It now rewrites only what the rule refuses (spaces,
  punctuation, a leading digit). The grid's duplicate check folds case instead, because DuckDB does: `id`
  and `ID` are one landed column. Schemas already saved keep their uppercase names; nothing rewrites them.
- **The Parse drawer arms Apply over a carried parse.** Opened on a node with no `schema_file` while the
  thread holds a table parse of the pane's own frontend (the dialog's, or its own before a Discard), the
  pane derives the schema from it through `onPreviewed` — the one derivation — so it reads *unapplied*
  and Apply writes it. Before, the thread's parse was not a form edit, nothing was dirty, and Apply stayed
  greyed out over "parsed · 6 cols · 4 rows".
- The strip mounts in the **parse drawer** (where the sample is consumed) and supplies the grammar
  editor's `previewFn` — a function because `previewed` fires on SUCCESS only, and a failing
  re-parse must not leave a stale "parsed · N cols" chip standing. Only a **table** result feeds
  the thread (parsed = "rows a downstream step can cast"); a record tree leaves it untouched.
  `Parse sample` lives in the sample strip; the FIRST derivation **expands the `types` section** —
  labelled *"How values are understood"*, a `mat-expansion-panel`, not a tab — via `showTab('types')`,
  which since the sectioned redesign means "expand";
  **Re-derive from this sample** confirms destructively naming the loss and is implemented as
  "clear `schemaHydrated` and re-run the parse" so one derivation path exists.
- **Write-ordering guards** (both falsified by deleting them and watching their tests fail):
  a saved schema is re-read on open and a fresh parse never re-derives over it (the operator's
  names/types/includes are the truth); a hand-authored `schema_file` outside the naming convention
  is reported and never touched.
- **Drift is reported, never applied** (`POST /config/suggest/schema`): only *adding new columns*
  is automatable; a type change and a missing field are human calls, shown old→new. The diff joins
  on the **selector**, not the name (renaming an output column is deliberate). ⛔ There is no
  `renamed` category and cannot be one — from draft + sample, a rename is indistinguishable from
  remove+add.
- Type suggestions come from the server (`SchemaSuggest`); `narrowToSchemaType()` maps its wider
  vocabulary (`BIGINT → DOUBLE`, unknown → `VARCHAR`) onto the four types
  `TransformCompiler.direct()` casts. The **selector** stays client-derived (frontend-dependent;
  the server sees only rows). The shared `raw.fields[]` grid lives in `inspecto/schema/` (a feature
  may not import a feature); hosts seed `[rows]`, own every write, and reach it via a **signal**
  `viewChild`.
- **Parse pane companions (`d012f721`):** the Parse drawer is sectioned (not tabbed), flat compact rows, one
  "Columns that come out" table; **partitioning moved from Parse to the Sink pane** (§10) and the Collection
  pointer is read-only on Parse — see [grammar configuration](../config/user-guide-grammar-config.md). The Parse
  checkbox that appends a read-only `file_name` row is default OFF (§6.3).
- **"Sample resolves to"** (AUTHORING-REDESIGN-1 (i)) shipped on the **Parse** pane's property rows
  ([grammar configuration](../config/user-guide-grammar-config.md) § *The sectioned Parse pane*).

### 6.3 Cross-Step fields on the Parse pane

- **`output.filename_column`** (source-file lineage) genuinely lives on the SINK. Pattern for any
  cross-Step field: the HOST resolves the target through the same `primaryOutputSink()` guard the
  enrichment seed uses (never a second derivation); `null` ⇒ the pane renders nothing, never
  guesses. The pane stays pure (`[filenameColumnTarget]` in, `(filenameColumnChange)` out); inline
  validation uses an explicit `role="alert"` paragraph — **never `<mat-error>`** (no `NgControl`,
  so the field never enters error state). The host commits through `applyNodePatch` (the immediate-
  write precedent canvas rename set). New pipelines seed `output.filename_column: file_name` in
  `pipelineScaffold()` — the one choke point both create surfaces use; a scaffold fact, never an
  engine default (which would silently grow every existing store). The lineage column is shown
  read-only in the `types` section's columns table — ⅋ never a "Column metadata list", which left Parse
  entirely with the metadata grid (D2) — (it IS an output column, stamped at write
  time) — never as a fake `schemaSeed` row, which risks being written back as authored.
- ⛔ **A created Pipeline stays FLAT at the space config root — `config/<id>/` was REFUSED (D3,
  signed 2026-09-22, 🔴 REVERSED 2026-09-23).** The scaffold writes `<name>_pipeline.toon` beside its
  satellites at the root, and that is deliberate. The decision was signed on the premise that *every
  shipped Pipeline lives in a per-Pipeline directory*; counted on disk that is false —
  `config/orders/` holds THREE Pipelines and the directory names are DOMAIN words, not ids
  (`config/postmed/` → `postmed_xdr`). Nesting by id would have added a THIRD layout.
  ✅ What the complaint cared about already holds: **the Pipeline and its satellites land together**,
  and an author who wants a domain home passes `subdir:`, which works today.
  ⚠ It was implemented and reverted TWICE. The second attempt found the cost: a bare
  `schema_file: <name>.toon` resolves BESIDE ITS OWN CONFIG, so nesting the Pipeline without moving
  its schema separates them and the save warns its own schema does not resolve.
  ✅ One durable gain survives: `ConfigFileSupport.resolveConfigFile` now finds a config in
  `<dir>/<name>/` even when nothing has registered it, so the per-DOMAIN homes every shipped Pipeline
  actually uses are reachable to read/patch/delete. Row: `UI-CREATED-PIPELINE-FLAT-HOME-1` (closed).

### 6.4 Parse traps a builder cannot see in the form

Fixed-width: `start` is a **0-based** offset (1-based counting shifts every field one character and parses
"successfully"); `has_header` defaults `true` for `fixedwidth` too, silently eating a line — a server-published
default, deliberately not flipped (migration, not UI fix). Every `parsing:` key, per frontend, with its refusals:
[ingest spec — parsing](spec-parsing.md).

---

## 7. Schema authoring

> The Parse pane's own columns table ("Columns that come out"), its sections and defaults are in
> [grammar configuration](../config/user-guide-grammar-config.md); the standalone `SchemaEditorDialog` below is unchanged
> since the 2026-09-04 authoring pass ([`parse-pane-redesign-plan.md`](../../../../archived-documents/plans-archive/parse-pane-redesign-plan.md), `d012f721`).

### 7.1 Where the schema lives

- **`processing.schema_file` is the PARSER Step's key** (where `PipelineLift` puts it and
  `PARSER_NO_SCHEMA` checks it) — the Parse drawer authors the output schema.
- ⛔ **No single drawer over schema + mapping + table** — it would span three Step types and break the
  one-node-in/one-node-out contract every drawer holds (operator, 2026-08-16).

### 7.2 The schema editor — `SchemaEditorDialog`

- **Component:** `SchemaEditorDialog` (`schema-editor.dialog.ts`) — a flat grid over
  `<inspecto-editable-grid>` with columns name / selector / **type** / description / unit / classification.
  Type is a **free-text DuckDB SQL type**, deliberately not a dropdown (comment).
- **Both hand-typed and inferred**: rows can be typed directly, or "Suggest from sample" calls
  `ConfigService.suggestSchema()` (`POST /config/suggest/schema`, TRY_CAST voting) against `data.sampleRows`
  captured by an earlier Test-Parse. Inference only fills the grid as a draft — nothing
  persists until Save. A suggested DATE or TIMESTAMP fills the **Date format** column (`raw.fields[].format`,
  a strptime pattern such as `%B %d,%Y` or `%Y-%m-%dT%H:%M:%S`) — without it a non-ISO date lands NULL; see §7.4.
  The suggestion is the same answer the parse preview's types and Add data give (one engine vote, `SampleProfiler`,
  operator rulings 2026-11-19): `09:30:00` is `TIME`, a column with one value that is not a number is `VARCHAR`, and
  `3,50` is a `DOUBLE` when the Grammar's **Decimal mark** says `,` (the dialog passes it as `decimalSeparator`). For a column holding an
  epoch count (`1700000000`, with `0` for none), declare the type `TIMESTAMP` and type `epoch` (seconds) or
  `epoch_ms` (milliseconds) in **Date format**: it lands as the UTC time and `0` lands empty — no `sql` step needed
  ([ingest spec — parsing](spec-parsing.md)).
- **Suggested selectors follow the frontend** (LA-ONBOARD-FIXES-1, F-4): the route's optional body key
  `frontend` (the value `/config/preview/parsing` reports) picks the selector. Delimited — the default when
  it is absent — and fixed-width get the column's **zero-based index** in the sample's order, because their
  load refuses a name; json / text_regex / xlsx / parquet / plugin keep the column name; an unknown value is
  a 400. The grammar editor's onward link passes its active frontend through `SchemaEditorData.frontend`
  (`plugin` for a plugin grammar). The save gate backs it: a pipeline write whose delimited/fixed-width schema still selects by name is
  refused `ERR_SELECTOR_NOT_INDEX` (active or not — it would not load), with the load's own message.
  The drift diff (`config` in the body) follows the same rule (`LA-ONBOARD-DRIFT-1`, 2026-10-23): for an
  index-addressed frontend (delimited, the default, and fixed-width) a selector of digits is the sample column
  at that POSITION, so an up-to-date index-selected draft is not drifted; any other selector is still a column
  name. ⚠ The Parse pane's drift check sends no `frontend`, so the server reads it as delimited — a json schema
  whose key is all digits would be matched by position there. A **schema written on its own**
  (`/config/write` or `/config/patch`, type `schema`) that a delimited / fixed-width Pipeline already names
  (`schema_file` or `schemas[].schema_file`, resolved beside that Pipeline, a draft whose schema does not exist
  yet included) is refused `ERR_SELECTOR_NOT_INDEX` when a selector is a name — judged by loading each such
  Pipeline against the candidate schema (`SaveGate.referencedSchemaSelectorFindings`), before the BACKWARD gate.
- **Inference (a separate, upstream endpoint):** `POST /parsers/{id}/preview` (`ParserRoutes.java`)
  runs a DuckDB `auto_detect` sniff and returns per-column types. Stateless — writes nothing.
- **Save:** `ConfigService.write('schema', config, {overwrite:true, ...compatibility})` →
  `POST /config/write type=schema`, persisted as `<name>.toon` (the typed Name — ⚠ **not** `<name>_schema.toon`,
  and nothing points the parse node's `schema_file` at it: this onward link "saves itself, node untouched",
  so a Draft Schema… named anything but the node's referenced file is a file no pipeline loads; the Parse
  pane's own Apply — and, since 2026-09-25, the Grammar dialog's own Save after a Test parse, through the
  same `writeParseSchema` (`pipelines/parse-output-schema.ts`) — is the write that keeps the two together,
  `SCHEMA-FILE-NAME-1`) beside `<name>_mapping.csv` and — since
  STRUCTURE-CSV-1 (2026-09-06) — `<name>_structure.csv` for `raw.fields` (`StructureCsv`, `ConfigFileSupport`),
  both merged back on read so the UI always sees the conflated document. A field with a key the CSV cannot hold
  (`timezone`, `partitions`, …) keeps the whole list inline; the sibling is then removed.
- **Editing an existing schema is real and gated:** a backward-compatibility diff,
  `SchemaCompatibility.check(existing, draft)` (`platform/inspecto-config/.../safety/SchemaCompatibility.java`),
  flags removed fields, non-widening type changes, and moved selectors as ERROR findings, mapped back onto
  grid cells by field name (`schema-editor.dialog.ts`) with an inline alert and a "Save
  anyway (skip compatibility check)" escape hatch (`compatibility:"none"` param,
  `ConfigWriteRoutes.java`). ⚠ **The BACKWARD gate has ONE documented escape hatch: a breaking overwrite is
  refused 422 unless the write carries `compatibility: "none"`.** Without that spelling an operator meets a 422
  with no stated way forward.
- **This gate protects the schema file only — it never reads the sibling mapping CSV.** `SchemaCompatibility`
  operates purely on `raw.fields` diffs and does not cross-check `MappingCsv`'s `targetColumn`/`sourceExpression`
  values; it only prevents the schema-side edit that *would* orphan a mapping rule, and that protection is
  bypassable via `compatibility:"none"`. Since 2026-09-06 `SchemaMappingDrift` closes the gap on the mapping side
  (§8.9, gap 4).
- **The generic downstream-staleness warning** is prose in the Parse-definition pane when a parse's produced
  columns no longer match the saved output schema (`pipeline-parse-definition.component.ts`, "anything downstream
  that expects the old columns will need updating" + a "Replace the output schema" button). It fires on a
  *parser format* change, never on a manual edit made through `SchemaEditorDialog`, and never names which
  mapping rules would break.

### 7.3 The derived output schema beside the authored one (`DERIVED-SCHEMA-PANEL-ORPHAN-1`, shipped 2026-09-15)

`DerivedSchemaPanelComponent` — a live `GET /config/schema/derived` consumer that was mounted NOWHERE for two
days — is wired into `SchemaEditorDialog`. `pipeline` threads pipeline-editor → `GrammarEditorDialogData` →
`SchemaEditorData` → the panel, because the route derives from the REGISTERED identity (`pipelineId()`), so it
was three levels of plumbing rather than a template edit. ⛔ Hidden for `home: 'registry'` — a registry schema
belongs to no pipeline, and an empty panel would read as "this pipeline writes nothing". ⚠ Labelled **"last
saved"**, and the label is load-bearing: the route derives from the SAVED config while the dialog edits a draft,
so mid-edit it shows the schema before the current edit. A test pins each direction.

### 7.4 Date formats, epoch timestamps and the source time zone

**Per-field date format (2026-09-25).** `raw.fields[].format` is a DATE/TIMESTAMP/TIMESTAMPTZ field's own
strptime pattern (`%B %d,%Y` for `March 22,2025`). When set it **replaces** — never joins — the pipeline's
`date_formats`/`timestamp_formats` for that field (`SchemaFieldTypes.formatsOf/formatsFor`), in the data
column (`RecordTransform.compile`), its `DATE_*` partition and event time (`TransformCompiler.dateExpr`) and
the cast-failure audit alike: a shared list trying other patterns first is how `%d/%m/%Y` silently reads as
`%m/%d/%Y`. A value the format does not match follows the existing coercion policy — NULL, row kept, counted
by `countCastFailures` (there is no reject/quarantine for coercion failures). `Identifiers.validateSchema`
refuses a format on a non-date (or absent) type, one with no `%` directive or a single quote (it is inlined
into a SQL literal), and a zone directive. `POST /config/suggest/schema` emits it: `SchemaSuggest` (through the
engine's `SampleProfiler.types` vote since BE-3 slice 2) tries `SampleProfiler.DATE_FORMATS` on a column no bare cast accepts, and a DATE carries the format it was proven with; a sample
under which two formats give different dates (`03/04/2025`) gets **no** format and stays VARCHAR. ⚠ DuckDB
strptime matches whitespace exactly, so a column mixing `March 22,2025` and `March 22, 2025` fits no single
format and is suggested VARCHAR. `SchemaExtractor` regeneration does not preserve `format` (it re-infers).

**Epoch counts (2026-11-15, `MAPPING-EPOCH-TIMESTAMP-1`):** the reserved values `epoch` / `epoch_ms` on a
`TIMESTAMP` field read a count of seconds / milliseconds since 1970 as its UTC wall clock — no source zone
applies, `0` is NULL and not a counted failure, a non-integer is a counted NULL; rules and reasons in
[ingest spec — parsing](spec-parsing.md).

**Time zone of the incoming data.** Precedence `raw.fields[].timezone_column > raw.fields[].timezone > parsing.source_timezone >
none` (`SourceZones`); `TIMESTAMPTZ` with no zone source and `%z` / `%Z` formats are **refused at config load**;
`FILENAME_DATE` and `DATE` are zone-exempt (a date has no instant to shift). The DuckDB session's own zone is the
host's and is a different rule. Mechanism: [engine — DuckDB](../engine/spec-duckdb.md).

### 7.5 Wide feeds — D8 and D9 on the schema side

Decided 2026-09-04 after driving the clickable mockup (Artifact *Pipeline Authoring Redesign*,
`b5e7ec6c-5bb2-467a-a513-93bfda987f3d`), built the same day (`4e64fe4a`, `c769719d`), verified 2026-09-06.

🔴 **What prompted them: the mockup's sample was chosen to flatter the design.** Both boards were drawn
over a 7-column orders CSV, where "one row per field" reads as clarity. This product's real feeds are not
that shape — ASN.1, fixed-width and network sources routinely carry hundreds of fields — and at 600 the
same table is a wall. The evidence was already in the repo (the parser lanes, the **122**<!--count:processors-->-processor catalog)
and was not applied. Both mockup boards now carry 65 columns instead of 7. ⚠ The BACKLOG row `AUTHORING-WIDE-1`
said "none built" for two days after — a fresh grep, not the row, is the source of truth ([[stale-residuals-in-shipped-rows]]).

**D8 — Parse does NOT drop columns; Transform owns exclusion.** The Parse pane settles **what a column is**:
that it exists, its name, its type, its synonym. It does **not** offer include/exclude (Parse's columns table
carries no include control; `SchemaFieldRow.include` survives only for Grammar-CSV round-trips). Leaving a field
out of the output is `transform.sql`'s job and only its job, so an author hunting a missing field has exactly one
place to look. ⚠ **The reason is cost, not tidiness.** The two affordances looked identical and were not:
excluding in Parse edits the **schema**, which `SchemaCompatibility` gates on a BACKWARD contract (§7.2) — so
undoing it can be *refused*, or force a re-test. Excluding in Transform is a clause in the SELECT: reversible for
free. The expensive one is simply not offered. 🔴 **Knock-on, deliberate:** the stored schema is now always the
full column set, so the Catalog will list columns that never reach a sink. Anything reading the schema as "what
lands in storage" is reading it wrongly — the schema describes the **file**, the SELECT describes the **output**.

**D9 — Both field tables are built for a wide feed.** Search over name and synonym (Parse also matches the
selector); a page-size choice that reaches down to 10. ⚠ The **filter idiom differs by grid**: Transform has
view-only filter chips **with counts** (All · Changed · Calculated · Needs attention), Parse has a **type dropdown
with no counts** — a type sweep is the question a wide schema asks. ⚠ The two grids deliberately **do not** share
a default: Transform pages `[10, 20, 100]` defaulting to 10 because its rows are tall (a function select plus
per-parameter controls), Parse `[10, 25, 50, 100]` defaulting to 50 because its rows are dense one-liners and a
type sweep wants many at once. Symmetry here would cost more than it buys. 🔴 **`#` is the position in the FULL
list (`seq`), never the row's index in the current view** — on Parse it is the physical column position in the
file, so renumbering it under a filter would be a lie; page 2 starts at #11. The Parse sample values render inline
per row, so they cover only the current page by construction; there is no separate caption saying so.

---

## Changes

- 2026-09-09: the processor catalog count in the wide-schema lesson corrected.
