---
type: Feature
title: Pipeline Editor
description: Single source of truth for the Pipeline editor UI — shell, canvas, Step configuration, definition drawer, testing, save, and pipeline-level surfaces.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipelines.routes.ts
tags: [feature, pipelines, editor, authoring, g6, graph, workbench]
timestamp: 2026-09-01T00:00:00Z
---

# Pipeline Editor

> **Scope (single source of truth, split by layer — operator decision 2026-09-01).** This file owns
> the **editor UI** truth: shell, canvas, Step configuration, drawers, testing, save, guided mode,
> and pipeline-level UI surfaces. It defers to:
> - [Grammar configuration](grammar-config.md) — the Parse pane and everything Grammar;
> - [Collector configuration](collector-config.md) — the `acquisition` Step surface;
> - [Inline AI authoring](inline-ai-authoring.md) — the `<inspecto-ai-assist>` adoption;
> - [Schema, Mapping & Transformation authoring](schema-mapping-authoring.md) — the schema editor, the
>   `transform.map` rule grid, `EXPR` free-text, and `transform.join` lookup config, plus the confirmed UX
>   gaps against a complete authoring experience;
> - [`okf/backend/pipeline-graph/`](../../backend/pipeline-graph/index.md) — the backend Pipeline model (lift/lower/validator/executor);
> - [`superpower/pipeline-spec.md`](../../../superpower/pipeline-spec.md) — the ACTIVE redesign plan (distills here when it drains).
>
> This file replaced `pipelines.md` (2026-09-01 consolidation); the dated change-log form lives in
> git history and the archived plans it cites. **Current truth only** — superseded states are kept
> only where knowing them prevents re-breaking something.

> **Vocabulary.** The canonical term is **Step** (GLOSSARY §2). ⚠ The UI copy and much of the code
> still say "node" (`node-attributes.ts`, `/pipelines/node-types`, …) — the Node→Step rename is
> tracked in GLOSSARY §13 as NOT STARTED. This doc writes *Step* and quotes code names verbatim.

Route `/pipelines` (Workbench nav group), dir `modules/admin/pipelines/`. An **AntV G6** interactive
graph editor for authoring **Pipelines** (the DAG artifact — never "flow"). Gesture model:
click = select (and open the Step's config pane — see *Selection is configuration*),
double-click = configure, plain drag = move, **Shift+drag = draw edge** (two-click Connect also
available). The editor keeps a persistent `Graph` and mutates in place (G6 patterns:
[architecture](../architecture.md)). Backed by `PipelinesService` / `ComponentsService` against a
real ControlApi — ⚠ the offline mock backend was **deleted 2026-08-31**; `npm start` needs the
backend on :4204.

## Shell: three lenses, tabs, and docks

`PipelinesComponent` hosts **`view` · `editor` · `topology`**:

- **View and Edit are one component** — `PipelineEditorComponent` with `[readOnly]="mode() === 'view'"`.
  Same toolbar, tab strip, palette, canvas and docks. `canAuthor()` = authoring lens AND not
  read-only, checked on every canvas mutation path (defence-in-depth, not hidden buttons).
- **Topology** is the store-joined overview: every chosen pipeline on one canvas wired through the
  synthetic STORE nodes they share. Tabs answer *what is in this pipeline*; topology answers *how do
  these fit together*. ⚠ Mode ids are `view`/`editor`/`topology`; `combined` once meant the
  read-only editor and must not be resurrected.
- **Laziness is the rule.** Arrival fetches only `GET /pipelines` (names + flags) and the Step-type
  catalog. `GET /pipelines/combined` fires on first Topology entry only (Refresh refetches). An Open
  dialog returns the **full desired set** of tabs; a tab's graph is lifted when it first activates.
- ⚠ **Per-tab state parks on switch** — graph AND dirty flag, via `parkCurrent()`, the one path every
  tab change takes (a first version parked only in `activateTab` while `select` also switched tabs,
  silently discarding edits). A dirty tab refuses an untick-close (toast) and confirms on its own
  close button — the only two ways edits can be discarded. The active tab's `dirty` mirrors into the
  per-tab set through **one effect**, so scattered `dirty.set(...)` sites stay tab-ignorant.
- ⚠ **`select()` is a load, not an idempotent setter** — re-running it refetches the graph and
  discards that tab's unsaved edits. The `?open=` effect fires once per id for this reason, and
  `?open=` also switches the pane to Edit (guided chips are no-ops in View).
- **Unsaved work is guarded at the browser edge too** (2026-09-01): a `beforeunload` handler arms
  when the active tab's `dirty()` OR any parked dirty flag is set — it checks the signal directly
  as well as the per-tab set, because the mirror effect runs on Angular's schedule and
  `beforeunload` fires on the browser's clock. **The open-tab set persists** across reloads
  (`localStorage 'inspecto.pipelines.openTabs'`, `{open, selected}`): restored names are filtered
  against the served list, tabs re-lift lazily, the `?open=` deep link wins selection, and ⚠ a
  failed list fetch does not arm the persist mirror — a backend-down reload must not wipe the
  stored set with `[]`. Dirty edits are deliberately NOT persisted (the guard owns that).
  **Ctrl/Cmd+S saves** (preventDefault always; `save()` only when `canAuthor() && dirty()`).
- **Undo/redo per tab** (R4, 2026-09-01): bounded snapshot stacks (50) beside `cachedModels`,
  capturing the PRE-mutation model JSON (the save-path serialization) at every mutation choke
  point — the same paths that arm dirty. Ctrl/Cmd+Z · Ctrl/Cmd+Y / Cmd+Shift+Z on the one keydown
  handler; ⚠ text-entry targets keep their NATIVE undo (deliberate). Restore is LOCAL —
  `model.set` + a `canvasEpoch` bump driving the graph host's `rebuildEpoch` input (the same
  `rebuild()` a tab switch takes) — ⛔ never `select()`, which refetches and discards. Dirty
  recomputes against a per-tab baseline stamped at load and after each save/activate write, so
  undoing to baseline clears it honestly; the stack survives a save (undo past it re-arms dirty)
  and dies with `forgetTab`. A dirty drawer confirms before an undo (declining aborts). ⚠ Node
  drags never snapshot — moves are purely visual and touch no model state.
- **Open dialog MRU + pins** (R5): `inspecto.pipelines.mru` (cap 8, recorded inside the dialog's
  own `confirm()` from newly-ticked ids) + `inspecto.pipelines.pinned` (per-row star,
  `aria-pressed`); Pinned/Recent sections render above the full list from ONE shared row
  template, stale ids dropped on render, search filters across sections.

**Edit mode is a full-bleed editor shell, not an admin page** — `pipelines.component.html` branches
on `mode()`; no page header, no `p-6 sm:p-10`; the mode toggle and `<inspecto-ai-explain>` project
into the editor toolbar via `[toolbarEnd]` (one bar, one `<h1>`). Regions, all collapsing toward the
canvas:

- **Toolbar** — one compact divider-grouped row (document · file · run · edit · assist). The open
  pipeline is a **mat-menu title button**, not a `mat-form-field` (the gamma theme's floating label
  costs ~25px of bar height — re-measure before "restoring the select"). It carries the STABLE
  selection cluster — Run to here · Preview data · Connect · Delete — whose slots **disable by
  selection kind** rather than appearing/disappearing. Run to here and Preview data are READS
  outside `canAuthor()` (the Business lens keeps them); Connect and Delete are inside it. Dry-run is
  a `beaker` (never reuse `play`), homed in the "More pipeline actions" menu, which sits OUTSIDE the
  author gate with author verbs gated item-by-item.
- **Left dock** — the Step palette (`app-pipeline-palette`), search + collapsible categories; a
  search overrides the fold state. ⚠ The palette's `groups` must be a **signal input** — as a plain
  `@Input` its `filtered()` computed caches the empty first pass and the catalog never appears.
- **Right dock** — `Properties` / `Assist` tabs; the definition drawer lives here.
  `showRightTab('assist')` is the toolbar sparkle button.
- **Bottom dock** — one `bottomTab` signal (`'dryrun' | 'validation' | null`); dry-run output and
  validation findings are one dock, never two stacked bands.

Both side docks are `[inspectoSplit]` (persisted `inspecto.split.pipelines.{palette,inspector}`),
collapsing to a 40px icon rail. Shell gotchas (recorded in the `angular-ui` skill): the shell bounds
itself to the viewport (`calc(100dvh - 120px)`); the split handle stays mounted while collapsed; the
G6 host needs its own `ResizeObserver`; a maximized dock overlays the body row absolutely
(`width:100%` beside `shrink-0` siblings clips its own footer). Opening a parse pane transiently
widens the dock to 420px (`InspectoSplitDirective.ensureAtLeast` — never persisted).

## The Step-type vocabulary is the engine's, and only the engine's

**Since 2026-09-02 the palette renders the served Step Processor TAXONOMY when it is available**
(`GET /pipelines/processor-catalog`, `ProcessorCatalog` in `inspecto-engine`, pinned by
`processor-catalog.contract.json`): eight families (Collectors & Ingestion · Extraction & Format Parsers ·
Data Quality · Transformers & Dimensional Modeling · Analytics/Time-Series · Enrichment & AI/ML · Control &
Governance · Sinks), 121 processors, **every one visible**. A processor whose `addable` flag is true (it maps
onto an authorable node type) is an ordinary add/drag entry for THAT node type; a planned processor, or a
capability that is not a Step (a Collector guarantee, a job type, a Studio surface), renders **inactive** —
`role=button aria-disabled`, tooltip and accessible name carrying why, a `soon` / `via <capability>` chip —
and never emits `pick`. Family headers count `addable/total`. The node-type groups below remain the
fallback for a server that does not serve the taxonomy (404 → `paletteProcessors` stays `null`). The
taxonomy is the operator's product board (`EDITIONS.md` §Step Processors) rendered in-product; the engine's
executable vocabulary is still exactly what follows.


The palette is a **faithful port of the backend enum `BuiltinNodeType`** as served by
`GET /pipelines/node-types`: `acquisition`/`adapter` (SOURCE), `parser` and the `parser.*`
per-format family (PARSE), the `transform.*` family + `enrichment` (TRANSFORM),
`sink.persistent|materialized|view` (SINK), `alert`/`gap`/`event` (CONTROL). Edge `rel`s are
`PipelineRel` constants (`data` default; `success`/`failure` sink-emitted;
`dropped`/`invalid`/`duplicate` diverted; `route:*` operator-named).

⚠ **An invented vocabulary survives unnoticed because nothing rejects it**: `PipelineValidator`
flags an unknown type as a *warning*, `PipelineCodec` stores `config` as an unchecked map, and
`PipelineCompiler` silently drops an unknown-typed node. The palette once carried 13 invented types
for exactly this reason. `pipelines.handler.spec.ts` pins the palette to the enum.

### `lowerable`, `authorable` — two flags, three questions

- **`lowerable`** (per type, served) answers *"can a save lower this back to the flat
  `*_pipeline.toon`?"* It does NOT mean "the engine can run it". Conflating the two is how the
  palette once presented 20 equal options, 11 failing at Save with 422 `UNSUPPORTED_NODE`. The
  palette dims non-lowerable entries; a grandfathered pipeline containing them gets a **warning
  banner, not read-only** — deleting the offending Step is the only repair, and read-only would lock
  it out.
- **`authorable`** = lowerable ∧ not read-compat-only (`PipelineEditable.isAuthorable`). A retired
  type (e.g. `transform.dedup.marker`, folded into the acquisition Step) stays `lowerable` so an old
  graph still saves, but is never offered again. The flag is optional on the client type: an older
  server omits it and the palette falls back to `lowerable`. Read-compat is a **lower-only**
  property — nothing lifts a retired type, so its `editableConfig` branch is dead the moment the
  lift changes.
- ⚠ Before refusing a type in `lower`, check whether **lift emits it**: `PipelineLift` emits
  `transform.filter` for any pipeline with row filters, so refusing it would break open-then-save.

## A spec `key` IS the engine's config key — there is no mapping layer

`AttributeSpec.key` is written **verbatim** into the Step's config; the app has no case-conversion
or key-mapping layer anywhere (the only transform is `component-model/flat-keys.ts`). A wrong spec
key produces a control persisting a value nothing reads, and nothing rejects it. Rules learned
closing D1–D9 (full history: `NodeConfigNameContractTest` docblock and git history):

- **Bind a key check to the runtime that executes the file this editor actually saves.** The editor
  writes the flat `*_pipeline.toon`; "read somewhere in `PipelineLift`/`RowShaper`" is too weak —
  `RowShaper` is reachable only from the authored-graph path, and `POST/PUT /pipelines/authored`
  are 405. Use **`PipelineEditable`**, not `PipelineLift.lift`, as the left-hand side: the legacy
  lift emits a *different vocabulary* (`includes` plural vs `include` verbatim).
- **A key with zero readers may be MISNAMED, not phantom** — check for a differently-named engine
  equivalent before deleting (upsert-by-key is `reference: {load: upsert, key: [...]}`, not
  `key_columns`). **Deadness is per Step type** (`mode` is real for `transform.route`, `table` for
  `sink.persistent`) — never a global dead-key list.
- **`transform.filter` has two filtering moments — never collapse them.** Pre-parse
  `include_*`/`exclude_*` (+ `filter_target_column`) inline into the `read_csv` SELECT over ONE raw
  physical column; post-parse `where` is SQL over the mapped, typed columns. `amount > 0` is
  inexpressible as a regex over unparsed text and vice versa. ⚠ `filter_target_column` lifts only
  alongside a pre-parse list.
- The name contract is checked by **driving the save**, not comparing strings:
  `PipelineEditable.toMap → fromMap → [set key] → lower → toToon → PipelineConfig.load → assert
  sentinel` (`NodeConfigNameContractTest`). Equality is only asserted where the Step's cfg IS the
  vocabulary; `acquisition`/`sink.persistent` carry whole raw blocks and the shared tables are a
  curated subset by design.

### Step config specs are SERVED; the client table is a fallback

`GET /pipelines/node-types` carries `attributes[]` per type (authored in
`inspecto-engine/.../pipeline/NodeAttributes.java`); the drawer prefers them and
`pipelines/node-attributes.ts` is the fallback until the catalog resolves.

- **A served empty array ≠ absent**: empty = the server says the type has no schema (honoured, never
  re-enables the client table); absent = catalog not yet answered (`??`).
- 🔴 **The Step vocabulary feeds TWO committed contracts**:
  `inspecto/contracts/node-attributes.contract.json` (`NodeAttributesContractTest` +
  `node-attributes.spec.ts`) AND `inspecto/contracts/step-types.contract.json`
  (`StepTypesContractTest`). Regen flags `-Dnode.attributes.write=true` / `-Dstep.types.write=true`
  — regenerate BOTH or the full reactor goes red after a green targeted run. ⚠ `inspecto/contracts/`
  is byte-compared and `.prettierignore`-exempted by path. When a contract test fails, decide which
  side is wrong FIRST — regenerating moves the goalposts.
- ⚠ Serialization must use `LinkedHashMap` — `Map.of`/`Map.copyOf` are unordered and make the
  contract test fail at random.
- **A shared attribute table is correct per *block*, not per *node***: `COLLECTOR_ATTRIBUTES` serves
  two adopters that reach `collector:` differently, so a key can be real for one and unreachable for
  the other. Treat "declared but unreachable" as a per-adopter question; the fix is **derive the
  adopter's table (never fork it) or move the key to the Step the engine reads it from** — and ask
  where the engine reads the key before choosing ([collector-config](collector-config.md)).
- Sinks are specced only for the `output:` keys the backend reads; remaining `transform.*` types are
  **deliberately unspecced**, falling back to the typed extra-config editor (below). Declared
  defaults **persist on save** even when untouched (product-confirmed: configs stay explicit).

## Configuring a Step: the definition drawer

Every drawer-served kind — filter, route, join, summarize, record dedup, sinks, gap detection,
plugins, and `enrichment` with its companion write+register — configures in the right-dock
definition drawer via `pipeline-config-definition.component.ts`. (`NodeConfigDialog` is retired,
2026-08-21; only PARSE keeps a dialog path — see *Parse*.)

- **Selection is configuration** (flipped THREE times, final by explicit operator ask 2026-08-22 —
  ⛔ do not change again without the operator): selecting a Step opens its config pane directly
  (`followSelectionIntoDefinition`); a DIRTY pane confirms before following. The slim summary
  survives only where the pane cannot serve: the read-only lens and dialog-custody parse Steps
  (auto-popping a modal stays obnoxious — those keep select-then-Configure). Palette add opens the
  new Step's pane.
- **Identity is on the page**: the inspector's `compact` identity strip renders above all definition
  panes — Name + Description as always-visible inputs seeded from the Step, committed on blur via
  `rename` (a no-op blur emits nothing). ⚠ All panes pass the Step's own `name`/`description`
  through their build explicitly — the Step is rebuilt from scratch, so omitting them DELETES the
  name on every apply. `renameSelected` also patches the open draft.
- **Apply is armed by an edit** — each pane feeds `dirtyChange`; deriving dirty "on interaction"
  has failed twice (a derived output schema not counted; mapping edits not counted at all — the
  Load pane now emits on `form.valueChanges`; programmatic seeding ends `markAsPristine`).
  🔴 A spec for dirty-arming must drive the DOM: `setValue` does not mark a control dirty, so a
  programmatic test passes against the broken build. Apply is an **in-memory patch** (plan D2);
  Save writes the pipeline.
- **Unmodelled keys render in a typed editor**, not a generic key/value grid:
  `pipeline-extra-config.component.ts` shows each unmodelled key by name with a TYPE-matched
  validated control (boolean select · numeric · JSON textarea · text). `buildConfiguredNode` takes
  typed `extras`; pristine entries emit the ORIGINAL value reference so an untouched value (e.g.
  route `branches`) survives an apply byte-identical. Keys are not editable; adding one is offered
  only where the type has no schema.
- **Flat ↔ nested bridging**: nested `collector:` sub-blocks are authored with `__` spec keys
  (`duplicate__mode`); the drawer runs `nestKeys` on save and `flattenBlock` on load. Both halves
  matter — without the load half a real nested block falls into the extra-config editor as a JSON
  string and, applied last, overwrites the schema form's own value. Save **deep-merges** each
  nested root over the Step's prior config (`mergeBlock`): several engine-read sub-keys have no
  `AttributeSpec` (`duplicate.algorithm`, `stability.size_checks`, `post_action.tags`, …), and a
  rebuild would silently destroy hand-authored TOON.
- ⚠ `connection` is stripped by both lift and lower (carried on `use:`, never mirrored in config) —
  the attribute **writes the binding**: seeded from `use` on load, written back to `use` on save. A
  Connection is NOT a `ComponentType` (`GET /components/connection` does not exist); the picker
  lives in the shared `<inspecto-collector-config>`.

### The `acquisition` Step

Renders the shared **`<inspecto-collector-config>`** over the shared `COLLECTOR_ATTRIBUTES` — mode
toggle (Local inbox | Connection | Dataset), Test connection, create-a-Connection, derived
`collector.connector`. Full contract: [Collector configuration](collector-config.md).

**Marker dedup lives on the acquisition Step** (file-grain duplicate detection is decided in the
poll cycle; `transform.dedup.marker` is retired read-compat). Config keys unchanged
(`processing.duplicate_check` + `dirs.markers`):

- `duplicate_check` is an explicit boolean, authoritative in both directions — presence of
  `retention_days` is NOT the switch (clearing a detail field must not silently disable dedup), and
  an explicit `false` must not fall through to a legacy marker Step. One statement of the rule,
  `PipelineLift.markerHome`, is called by all three readers.
- The marker keys are their own `MARKER_DEDUP` list, NOT part of `COLLECTOR_ATTRIBUTES` — they live
  in `processing:`/`dirs:` and are only borrowed by the Step; folding them into the block table
  would hand the other adopter fields it writes to a block nothing reads.
- ⚠ `lower` dumps the acquisition Step's config wholesale into `collector:` minus `ACQ_FOREIGN_KEYS`
  — a borrowed key missing from that set lands in the wrong block silently.
- ⚠ The group renders `tier: 'required'` + `required: false` (always visible, never mandatory) — as
  `optional` it sat behind the disclosure and rendered as a heading over nothing; only driving the
  preview showed it.

### The `enrichment` Step

Authors a **companion** `*_enrich.toon` through `ENRICHMENT_WIRING_ATTRIBUTES`, not `node.config`.
Every save re-registers (`POST /enrichment`) — enrichments do not hot-reload by mtime; a register
failure downgrades to a warning.

- **Wiring is derived through one shared function**, `enrichmentWiringDefaults`
  (`inspecto/enrichment/enrichment-wiring.ts`): input = the Stage-1 output · output =
  `<base>/data/enriched/<name>` · trigger = `on_pipeline`. Only pipeline-DERIVED facts travel in
  `NodeConfigData.enrichmentHost`.
- 🔴 The Stage-1 store seeds **only when exactly ONE sink declares a `database`** — an invented
  store path reads zero rows everywhere while looking like it worked. ⚠ Quarantine is SINK-category
  but carries only `dir`, so the derivation filters on config, not category.
- ⚠ **A seed is one-shot** — never re-derived as the author edits (a seed moving under an edited
  form clobbers it). A bound companion's file always wins, including a `triggers.on_pipeline`
  naming a different pipeline: silently re-pointing a deployed enrichment is not an edit anyone
  asked for.
- **The two partition keys are engine-REQUIRED with `[]` as the legal "unpartitioned" value**
  (`FieldSpec.required`; `RawConfig.present` is true for `[]` — required means "the grain is
  stated"). The save sets both **explicitly** because `nestKeys` prunes an empty array; JToon
  round-trips `partitions[0]:` back to an empty list (pinned in `ConfigLoaderTest`).
- Speccing a key makes it form-OWNED (`ownedLeaves` derives from the spec table; owned leaves are
  replaced wholesale). An `output.partitions` entry may be the sink's `{column, source}` map —
  hydrate flattens to `column`, save re-marries `source` onto kept columns
  (`remarryPartitionSources`).
- ⚠ **The `list` chips type does NOT compose with `flat-keys.ts`** (which joins arrays to comma
  strings and splits back only for a hardcoded leaf set) — the two partition flat keys are
  re-seeded as real arrays after `flattenBlock`, deliberately inside the dialog. Any future `list`
  spec on a flat-key surface needs the same treatment or a decision to unify the conventions.

## Parse: drawer vs dialog, the claimed slot, and the sample thread

The Parse surface itself — tabs, options, columns grid, Grammar CSV round-trip, plugin formats — is
[Grammar configuration](grammar-config.md)'s. What is the editor's:

- **Custody split**: a per-format parse Step (`PARSE_NODE_FRONTENDS`) defines in the right-dock
  Parse drawer; `GrammarEditorDialog` (`grammar-editor.dialog.ts`) survives ONLY for a dangling
  `use: grammar/<id>`, binary fixed-width, and a config-less generic `parser` — deliberate keeps.
  **A parser is always format-specific — never author the generic type** (operator directive
  2026-08-21). A generic `parser` whose config maps to a built-in frontend migrates on edit:
  `definitionDraft` presents it re-typed with `csv_settings` folded in under the engine's own merge
  precedence (`parsing:` wins), and Apply converges the Step on the specific type. Fail-closed to
  the dialog otherwise.
- **The parse slot is claimed, not queued**: a flat config has ONE `parsing:` block, and every new
  pipeline lifts with a generic `parser` placeholder. `claimParseSlot()` runs before either palette
  entry point — an untouched placeholder is **re-typed in place** (same id, both edges intact, so
  the Recipe view never falls back to Canvas); a parse Step carrying config is authored work and
  the add is REFUSED naming the format that holds the slot. Predicate: `isParseNodeType` — ⛔ never
  the catalog CATEGORY (the rule must hold before the catalog resolves). ⛔ This is NOT
  auto-connecting: an ordinary Step still lands unconnected on purpose (considered and declined).
- **One sample thread per tab**: `DefinitionStateService` instances live in a `Map<id, …>` beside
  `cachedModels`, born in `select()`, dropped by `forgetTab()`. 🔴 A Map, NOT a `providers:` entry —
  DI providers are static per component instance and this editor is ONE instance hosting every tab.
  `InspectoSamplePanelComponent` takes the thread as an **input** (also keeps it pure — the D2
  rule every definition pane follows). ⚠ Reading the Map inside the `sampleThread()` computed does
  not track it — deliberate; `selectedId` is the only dependency that can change the answer.
- The strip mounts in the **parse drawer** (where the sample is consumed) and supplies the grammar
  editor's `previewFn` — a function because `previewed` fires on SUCCESS only, and a failing
  re-parse must not leave a stale "parsed · N cols" chip standing. Only a **table** result feeds
  the thread (parsed = "rows a downstream step can cast"); a record tree leaves it untouched.
  `Parse sample` lives in the sample strip; the FIRST derivation steers to the Types & columns tab;
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

### Cross-Step fields on the Parse pane

- **`output.filename_column`** (source-file lineage) genuinely lives on the SINK. Pattern for any
  cross-Step field: the HOST resolves the target through the same `primaryOutputSink()` guard the
  enrichment seed uses (never a second derivation); `null` ⇒ the pane renders nothing, never
  guesses. The pane stays pure (`[filenameColumnTarget]` in, `(filenameColumnChange)` out); inline
  validation uses an explicit `role="alert"` paragraph — **never `<mat-error>`** (no `NgControl`,
  so the field never enters error state). The host commits through `applyNodePatch` (the immediate-
  write precedent canvas rename set). New pipelines seed `output.filename_column: file_name` in
  `pipelineScaffold()` — the one choke point both create surfaces use; a scaffold fact, never an
  engine default (which would silently grow every existing store). The lineage column is shown
  read-only in the Types tab and Column metadata list (it IS an output column, stamped at write
  time) — never as a fake `schemaSeed` row, which risks being written back as authored.
- **Partitioning** (`<inspecto-schema-partitions-editor>`, `inspecto/schema/`; **rendered on the Sink
  pane since 2026-09-04**, which reads/writes the SAME companion schema toon's `partitions[]` key directly;
  ⚠ the Parse pane still seeds `partitions[]` on load and carries it through its `overwrite: true` write, so
  a Parse Apply over a stale seed can clobber a Sink edit made meanwhile — BACKLOG): rows derive Hive
  segments `{column, source, type}` from schema fields, rendered where the pane owns the schema
  toon (`authorsSchema()`). 🔴 The read half is a data-loss fix — `partitions[]` is top-level in
  the schema toon and the pane's `overwrite: true` write DROPPED hand-authored blocks until the
  pane read them back (`loadSavedSchema`). Legacy `partitionKey:` loads as the synthesized
  year/month/day trio. Mixed date sources WARN, never block (the engine degrades catalog bounds).
  A stored `source` the schema no longer carries stays listed — visible beats vanished. The smart
  launcher pre-picks a date field only when exactly ONE exists; ⚠ native `<select>` in `@for`:
  bind `[selected]` per option, never `[value]` on the select.

## Transform: the `transform.sql` Step pane (SHIPPED `7e13dd82`; SQL-first rebuild 2026-09-04)

- **Routing arm:** the definition dock has one more type-specific arm — `dn.type === 'transform.sql'` →
  `<app-pipeline-transform-sql-definition>` (`pipeline-editor.component.html:755-767`), beside the
  `transform.map` arm (`:736-754`) that projects `<app-pipeline-load-definition>`; every other
  `transform.*` kind stays on the generic `pipeline-config-definition` schema-form. A node TYPE routes to
  its own pane — the rule that already held for map/parse/sink.
- **The pane (SQL-first, operator instruction 2026-09-04 — supersedes D5/D6):** one SQL `<textarea>`
  seeded for a new Step with an explicit column list over the upstream sample columns (else `SELECT *
  FROM input`), "Columns that come out" in the same `<inspecto-schema-fields-editor>` the Parse pane
  uses (fed from the test run's DESCRIBE `columnTypes`, read-only types), and Test this Step over
  `ComponentsService.previewTransform` (the existing path — `transform.sql` qualifies by prefix). The
  persisted config is **`{ sql }` only**; a legacy `fields[]` from the retired Simple grid is dropped on
  the next Apply. Full as-built, what the grid did and why it went:
  [schema-mapping-authoring.md](schema-mapping-authoring.md) §0; engine half:
  [`catalog-vs-executors.md`](../../backend/engine/catalog-vs-executors.md).
- ⚠ `sql` is the single `NodeAttributes` entry; the engine reads nothing else. Do not add an authoring
  artifact beside it — the retired `fields[]` is exactly that, and its presence no longer means anything.
- **Parse pane companions (`d012f721`, same shift):** the Parse drawer is sectioned (not tabbed), flat
  compact rows, one "Columns that come out" table; **partitioning moved from Parse to the Sink pane** and
  the Collection pointer is read-only on Parse — see [grammar-config.md](grammar-config.md). The
  *Cross-Step fields* facts above still hold (`output.filename_column` lives on the SINK; the Parse
  checkbox that appends a read-only `file_name` row is default OFF for exactly that reason).

## Load: mapping on the map Step, schema on the parser

> 2026-09-04: `transform.map` is the **legacy** mapping path. New computed columns / renames go through
> `transform.sql` (the catalog's `transform.expression`/`transform.cast` point there); the facts below are
> unchanged for stored pipelines.

- **`processing.schema_file` is the PARSER Step's key** (where `PipelineLift` puts it and
  `PARSER_NO_SCHEMA` checks it) — the Parse drawer authors the output schema.
- **`transform.map` authors exactly `{columns, rules}`** (`MAP_AUTHORED`, pinned); `schema`/`csv`
  on a map Step are `MAP_DERIVED` — engine-resolved, dropped by `lower`. ⛔ A single drawer over
  schema + mapping + table would span three Step types and break the one-node-in/one-node-out
  contract every drawer holds (operator, 2026-08-16).
- 🔴 A derived `transform.map` Step is on EVERY lifted graph and reads `unconfigured` — readiness
  logic keys on **authored evidence**, never that Step's presence or status.
- **Test mapping** posts `{raw: <parser schema's raw>, mapping: {rules}}` over the rows the parse
  drawer already parsed — the rules being EDITED, not the node's. The result is written back as
  the thread's cast hop; an edited rule clears the grid (a result may never outlive the config it
  came from). Reachable only where the parse Step is per-format (the dialog has no thread).
- The Load pane authors all four `TransformCompiler` types; a transform type it has never heard of
  (a hand-authored `LOOKUP`) is preserved and shown — *not offering* must never become
  *destroying*. The two specialised types pack parameters into `sourceExpression` as `|`-delimited
  positions (`MappingCsv` drops every other key): `CONCAT_DT` = `<dateCol>|<timeCol>` always both
  (compiler reads `parts[1]` unconditionally); `FILENAME_DATE` = `<col>|<prefix>|<strptime>` with
  trailing blanks DROPPED, not emitted (an empty third position interpolates `''` into
  `TRY_STRPTIME`), and may only write **`EVENT_DATE`** (enforced server-side; the pane refuses
  rather than posting a guaranteed 422).

## Route branches: the destination is IDENTITY

`database` is the **branch↔sink join key on both halves of the lift/lower round trip** (edges do
not survive the flat file): lowering stamps each branch with the database of the sink its
`route:<key>` edge feeds and skips a destination-less branch; `sinks:` is keyed by distinct
database; the lift rebuilds pairing from the stamp.

- 🔴 `addRouteBranch` creating its companion sink with an empty config was silent DATA LOSS (branch
  lowered to nothing; save reported success). `branchDestination()` derives it beside the primary
  sink's home (`<primary home>/<branchKey>/database`), falling back to `pipelineScaffold`'s
  `data/<name>`. ⚠ Quarantine sinks are excluded by the `typeof database === 'string'` test.
- ⛔ Do not "simplify" by leaving the sink blank to validate later — that state cannot be saved at
  all and looks identical to any unconfigured Step.
- 🔴 **A round-trip test that configures the artifact under test tests nothing** — the original spec
  set `database` by hand and passed the whole time.
- **Single-slot kinds** (`processing.join` refuses a second via `MULTI_JOIN`; `recordDedup`,
  `routeNode`, `summarizeNode` are last-one-wins, silently): the discard behavior is PINNED per
  slot in `PipelineEditableTest`; inverting it is an operator call (BACKLOG §4). Mid-branch
  `steps:` sub-chains are refused by both authoring surfaces — design:
  [`archived-documents/plans-archive/mid-branch-transforms-design.md`](../../../archived-documents/plans-archive/mid-branch-transforms-design.md).

## The Recipe view and the step cards

`<app-pipeline-step-cards>` (hosted in `pipeline-editor.component.html`) **is** the ordered `steps:`
chain editor — cards in chain order, insert-between, remove, move up/down, nested `route` branches,
wired with `[editable]`.

- ⚠ **Never key anything on `verb`** — `GET /pipelines/step-types` publishes entries where
  `transform` appears twice (filter, join); `type` is the unique key (duplicate `@for` track keys
  are an Angular runtime error). A verb can serve a spec the palette never reaches — when a type
  gains a spec, check the palette serves it.
- ⛔ One polymorphic entry with a discriminator does not work: `PipelineEditable.lower` dispatches
  on the node's `type`, never config content.
- A step card captions the type's own label via `[typeLabel]` (from the served catalog; fallback =
  category label, never the raw type string). `uniqueNodeId` bakes the type into the id
  (`transform_join_1`) — any future retype affordance makes that id a lie. `PipelineLift` passes
  `null` instead of stamping lifted names with type labels (a name equal to the label reads as a
  visible duplicate); `filter` keeps its legacy "Row filter" name (a real, more specific one).
- **One glyph per Step type** — `typeHeroIcon(type, category)` (`pipeline-graph.ts`) is the single
  icon vocabulary for palette, step cards, and insert menu; the **category is the color**
  (`categoryColor`). Unknown served types fall back to `paletteHeroIcon(category)`. ⛔ Never give
  two palette items one glyph.
- ⚠ Icons are **SVG sprites** (heroicons/feather/material), no icon font: `<mat-icon>add</mat-icon>`
  renders the literal word. Always `svgIcon="heroicons_outline:…"` — only visible by looking at the
  page.

## Testing what you author

- **Run to here** — `POST /pipelines/authored/{id}/run?to=` (`PipelineGraphRoutes.testRun`,
  `canAuthorWorkbench`): parses picked inbox files through the real ingest path into a scratch root
  (zero production side effects), previewing the graph over parsed rows. `to=` bounds the walk to
  the **ancestor closure** of the chosen Step; unknown `to=` is 400. Details:
  [pipeline-test-run](../../backend/engine/pipeline-test-run.md).
- **Test this Step** (drawer inline preview) — `POST /components/{transform|sink}/preview` runs an
  **inline** config over the tab's parsed rows: exactly what the operator is mid-writing.
  🔴 The gate is the Step's own TYPE (`testFamily()`), ⛔ never `data.bindKind` — `bindKind` is null
  for everything that reaches the drawer, which is exactly why a predecessor affordance was
  unreachable dead UI whose specs passed by constructing the dialog directly. 🔴 The body goes
  through the same `buildConfiguredNode` the save path uses — a second merge would drift and test
  a config that never ships. ⚠ The transform arm sends the Step's `type` INSIDE `config` or the
  route 422s. Rows arrive as plain `sampleRows` (the thread belongs to the TAB).
- ⛔ **An inline-config Step of other kinds cannot be tested by any existing backend surface** —
  the component `/test` routes resolve through `ComponentStore` and 404 on absence; do not "finish"
  this without a deliberate decision.
- **Test a bound component** — "Test <component>…" opens `ComponentFormDialog` (the one surface
  that collects a sample); `TESTABLE_KINDS` = transform/grammar/sink.
- **Dry-run** — offers **"Use the captured sample (N rows)"** from the tab's thread. ⚠ The panel is
  mounted once and outlives tab switches: its outcome is stamped with the pipeline id it came from
  and `result`/`error` are computed against the current one. ⛔ Never replace that with an effect
  that clears on id change (effects flush on Angular's schedule and can wipe a fresh result).
- Fixed-width traps a builder cannot discover from the form: `start` is a **0-based** offset
  (1-based counting shifts every field one character and parses "successfully"); `has_header`
  defaults `true` for `fixedwidth` too, silently eating a line — a server-published default,
  deliberately not flipped (migration, not UI fix).

## Save, validation, refusals

Save lowers the graph to the flat `*_pipeline.toon` (`PUT /pipelines/{name}/graph`). **Save
refusals all land in the Validation dock** (persistent, click-to-select-Step), never a first-only
toast. Apply (drawer) patches in memory; Save persists; the per-tab dirty flag guards both.

**The save route runs the arming pre-checks** (2026-09-01): `armedWithoutSchema` / `routeArming` /
`stepDisable` findings, same severity split as `/config/write` (ERROR ⇒ 422 when `active: true`,
WARNING on a draft) — before this, the editor's Save could 200 a pipeline that silently failed to
arm at the next registry rebuild. Backend detail:
[editable-round-trip](../../backend/pipeline-graph/editable-round-trip.md) §16.

**Validation is live while dirty** (2026-09-01): a debounced (~1.5s) pass recomputes `findings()`
after graph mutations (drawer keystrokes live outside the model until Apply, so they never
trigger it) — the dock badge updates live, and the Save button shows a WARNING state (icon +
tooltip naming the error count) while staying **enabled**: drafts may save with problems.
🔴 The debounced pass runs `refreshFindings()`, never the dock-opening `validate()` — auto-popping
the dock 1.5s after every edit is intrusive. ⚠ The debounce timer goes through an overridable seam
(`armValidateTimer`) on the zone-UNPATCHED `setTimeout`: a zone-patched macrotask armed from an
effect re-enters `ApplicationRef.tick` (NG0101), and neither fakeAsync nor `vi.useFakeTimers()`
survives it in this runner — specs capture the armed callback and fire it deterministically.

Known save-path defects are tracked in [BACKLOG](../../../BACKLOG.md) — see *Known gaps* below
before trusting a described save.

## Pipeline-level surfaces (not the graph)

- **Settings** (⋮ menu → `PipelineSettingsDialog`): a Description textarea (first field, optional),
  the `produces` select; when `reference`, load-mode + comma-separated key + refresh seconds.
  Dedicated `GET/POST /pipelines/{name}/settings` — NOT `PUT .../graph`; `PipelineEditable`
  deliberately never models non-node keys. A hand-built form: `fieldSpecsToAttributes` deliberately
  skips served `LIST`. Backend:
  [editable-round-trip §17](../../backend/pipeline-graph/editable-round-trip.md).
- **`description`** is declared (`ConfigSpecs.pipeline()`), parsed, projected conditionally by
  `GET /pipelines`, and rendered as the open-dialog row subtitle. ⛔ Display only. Editable
  post-creation via the settings surface since 2026-09-01 (trimmed on write; **blank clears the
  key from the file** rather than persisting an empty string).
- **New pipeline** from the editor (⋮ menu and the Open dialog's footer) navigates to the Catalog
  onboarding entry (`/catalog?onboard=stream`) — ⛔ never an import of the catalog create dialog
  (a feature may not import a feature); onboarding redirects back into the guided editor.
- **Duplicate…** (⋮ menu, canAuthor, refuses a dirty tab): a RUNNABLE copy via the proven
  stream-bundle retarget path — `StreamTransferService.exportPipeline` → `planStreamImport` under
  the new name → `applyImport` — so satellites (schema(s), per-segment schemas, enrich companion)
  come along, dirs re-derive, and the copy lands `active: false`, then opens as a tab. Distinct
  from save-as-template (non-runnable). The name dialog mirrors onboarding's unique-name rule
  locally.
- **Export from the list**: `StreamTransferService.exportPipeline(name)` owns the bundle build
  (reads server state via `GET /config/pipeline`; stream-vs-reference from that config's own
  `produces`), so the Open dialog's per-row download action exports WITHOUT opening a tab and the
  editor's menu item delegates to the same function. Un-gated (export is a read); an OPEN dirty
  row keeps the refusal toast, a closed row exports freely.
- 🔴 **`produces` names two different things**: `PipelineSummary.produces` = the list of STORES;
  stream-vs-reference is the config-level `produces` behind the settings endpoint. Deciding the
  Dataset hop off the summary field is silently wrong; when the read fails, register nothing and
  warn.
- **Go-live registers the Dataset** through the shared `DatasetRegistrationService`
  (`inspecto/api`) — idempotent by `physicalRef`, `sourceName` = the store (blank falls through to
  a default naming nothing), never reverses a succeeded activation. Streams only. **A failure or
  unknown kind raises a persistent per-tab retry banner** (R6, the shared `<inspecto-alert
  variant="warning">` explained-panel — never a toast); Retry re-invokes the registration (safe —
  idempotent), success clears it, and the issue dies with the tab. ⚠ `InspectoConfirmService.confirm(message, title)` takes a title **string**; only
  `confirmDestructive` takes options.
- **Delete pipeline** reads impact first, names dependents in the confirm, sends `force` only after
  showing them, and **cascades** the `<id>_schema` / `<id>_enrich` companions and per-segment
  schemas (`ownedSegmentSchemas()` reads basenames off the in-memory graph before delete clears
  it). ⚠ The impact read is advisory — a failed read must still let the delete proceed (the server
  re-checks and 409s). ⛔ Cascade is scoped to the pipeline's own `<id>_` prefix — a hand-authored
  path elsewhere may be shared, and orphaning another pipeline's file is worse than leaving one.
- **Export/import**: `exportConfig()` (stream-config bundle, `inspecto-stream-config` v1 — format
  contract in [onboarding](onboarding.md)) and *Export document* live in one always-visible Export
  home projected into the transfer menu via `[transferExtras]` (both are read-only; a Business-lens
  operator handing a config to support is the point — A6, 2026-08-17). ⚠ `exportConfig` reads the
  SERVER-held config back and refuses while dirty (an export carrying last-saved state while the
  screen shows something else is worse than none). Stream-vs-reference comes from that config's own
  `produces`. ⛔ The Metadata Bundle (registry artifacts by **id**) and the config namespace (by
  **path**) collide on the word *schema* — never merge the two formats. *View as graph* was
  deliberately not carried over (re-confirmed 2026-08-17).
- **Take offline** is the inverse of go-live and the same flag — an `active: false` write from the
  toolbar Deactivate; keeps landed data and the registered Dataset.

## Guided mode (the onboarding host)

Onboarding's create dialog and entry points: [onboarding](onboarding.md). The editor is the host:
`/catalog/onboard/:name(/:stage)` redirects to `/pipelines?guided=1&open=<name>&stage=<chip>`
(`onboard-redirect.ts` — the ONE statement of the target, used by the route and both Catalog
navigation sites). The matcher stays so old bookmarks resolve. `schema` and `keys` both map to the
Schema chip; an unknown stage carries no focus.

Guided mode (`?guided=1`, off by default) renders checklist chips — Collect → Parse → Schema →
Enrich → Publish — each with a status word and finding count, each click opening that stage's Step.

- ⛔ **The stage model is derived from the graph** (`pipeline-stages.ts`, reusing `NodeStatus`),
  never a second readiness opinion — that is how a chip and the canvas card under it disagree.
- ⛔ **The go-live readiness gate is guided-only**: `validatePipeline` does not require a parse
  Step; a hand-built collect→sink graph is legitimate. ⚠ Every stage resolves through the served
  catalog — unresolved catalog reads as five empty stages.
- 🔴 A chip **selects** its Step before opening it (selecting is ungated; the editing half is
  withheld by `canAuthor()`), so the strip works in every mode.
- `rejects` never promotes a stage to ✓; the Schema stage tops out at `configured` (the editor
  claims no validation it never ran).

## Known gaps (owners: [BACKLOG](../../../BACKLOG.md))

The behavior described above is the intended-and-actual state. ⚠ **Corrected 2026-09-01**: this
section previously listed AUTHOR-1, DRYRUN-1, the §5 UI residuals and BUILDER-2 #8 as open — all
were verified FIXED in code (the BACKLOG rows are struck through; the grounding sweep confirmed
each fix live). Genuinely open:

- ~~Single-slot inversion question~~ **STALE (corrected 2026-09-01)**: multiplicity was resolved
  2026-08-11 (ordered `steps:`; `MULTI_*` refusals deleted). Still deliberately last-one-wins:
  `acquisition`, `gap`, `dedup.marker` (`parser` refuses via `MULTI_PARSER`).
- ~~Mid-branch `steps:` sub-chains refused~~ **SHIPPED 2026-09-02 (R3)**: a route branch carries
  `steps[]` in the shared step grammar — authorable on the canvas (wire `route:<key>` → step →
  sink; the lift presents branch chains as ordinary flattened nodes, lower writes them back to the
  branch), rendered in the Recipe view. Mid-branch kinds = the fork-executable set; `join`,
  windowed `dedup` and nested `route` refuse by name at save. ~~One-click insert-into-branch is
  MIDBRANCH-UI-1 (BACKLOG)~~ **SHIPPED 2026-09-02**: each branch header offers "add a Step at the
  start of branch <key>" (→ `insertBranchHead`, which keeps the `route:<key>` edge on the route side)
  and every in-branch card except the branch's tail offers insert-after (plain `insertStepAfter`);
  both open a palette narrowed to `BRANCH_STEP_TYPES` (filter / dedup / summarize — the client mirror
  of `RouteArming.BRANCH_STEP_KINDS`, so it front-runs the 422 rather than offering `join`/`route`).
  Remove/move inside a branch stay canvas work. Backend:
  [editable-round-trip §19](../../backend/pipeline-graph/editable-round-trip.md).
- ~~TRANSFER-ARCH-1~~ **SHIPPED 2026-09-01** as the server-side pipeline bundle
  ([editable-round-trip §21](../../backend/pipeline-graph/editable-round-trip.md)); the residuals
  R1/R4/R5/R6 shipped the same shift. Small follow-up in BACKLOG: migrate Duplicate + the
  row export onto the bundle routes (both still ride the client stream-bundle).

## Verification culture (why this file reads the way it does)

Three lessons this feature keeps re-proving, kept here because they shape every change to it:

- **Drive the preview for anything visible** — five shipped defects were wiring between correct
  units that 2500+ green unit tests could not see (icon ligatures, disclosure-hidden groups,
  `[value]` on native selects, stale drawers).
- **A test that supplies what the surface under test should produce, tests nothing** (the route-
  branch round-trip; the dirty-arming `setValue` trap).
- **Falsify guards before trusting them** — the write-ordering guards were verified by deleting
  them and watching exactly their own tests fail.
