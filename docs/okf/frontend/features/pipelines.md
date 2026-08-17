---
type: Feature
title: Pipelines (Authoring)
description: The NiFi-style Pipeline graph editor (AntV G6) with node config, a Grammar editor dialog, and per-processor test.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipelines.routes.ts
tags: [feature, pipelines, authoring, g6, graph, parser, workbench]
timestamp: 2026-07-07T00:00:00Z
---

# Pipelines (Authoring)

Route `/pipelines` (Workbench nav group), dir `modules/admin/pipelines/`. An **AntV G6** interactive graph
editor for authoring **Pipelines** (the DAG artifact — never "flow"): gliffy icon nodes, hover preview,
click → a node-config dialog, and per-processor **Test**. Gesture model: click=select,
double-click=configure, plain drag=move, **Shift+drag=draw edge** (two-click Connect also available). The
editor keeps a persistent `Graph` and mutates in place (see the G6 patterns in the
[architecture](../architecture.md)).

**`GrammarEditorDialog`** (`grammar-editor.dialog.ts`, renamed from `ParserConfigDialog` 2026-08-04;
since 2026-08-15 it serves only grammar-BOUND and plain-`parser` nodes — an inline `parser.delimited`
defines in the right-dock Parse drawer instead, see [Grammar configuration](grammar-config.md))
configures PARSE nodes: a thin host over the shared `<inspecto-grammar-editor>` — see
[Grammar configuration](grammar-config.md) for the full account, shared with Onboarding's Parsing
stage. A Grammar lives **inline** on the node's own `parsing:` config by default; "Save as reusable
Grammar" extracts it to a `grammar` [component](components.md) bound via `use: grammar/<id>`. Backed
by `PipelinesService` / `ComponentsService`; offline via the `mockFlows`-gated handler of the unified
[mock backend](../conventions/mock-backends.md).

## Three lenses, and nothing loads until you ask for it (2026-08-02)

`PipelinesComponent` hosts **`view` · `editor` · `topology`**:

- **View and Edit are one component** — `PipelineEditorComponent` with `[readOnly]="mode() === 'view'"`.
  Same toolbar, tab strip, palette, canvas and docks, so switching never means relearning the screen.
  `canAuthor()` = authoring lens **AND** not read-only, and every canvas mutation path checks it, so
  the gate is defence-in-depth rather than hidden buttons.
- **Topology** is the store-joined overview: every chosen pipeline on one canvas, wired through the
  synthetic STORE nodes they share, with the multiselect and the store-aware node inspector. It is a
  separate mode because it answers a different question — tabs say *what is in this pipeline*, topology
  says *how do these fit together* — and the tabbed editor cannot express the second.

⚠ The mode ids are `view`/`editor`/`topology`. They were briefly `combined`/`editor` where `combined`
meant the **read-only editor**; that name is now taken by the thing it never referred to, so don't
resurrect it.

**Laziness is the rule, per mode.** Arrival fetches only the pipeline name list and the node-type
catalog. `GET /pipelines/combined` — the expensive whole-topology call that used to run on every visit
— fires the first time Topology is entered, guarded so re-entry reuses it (Refresh forces a refetch).

**The open set is explicit.** Arrival fetches only `GET /pipelines` (names + flags) and the node-type
catalog — *not* `GET /pipelines/combined`, which used to pull the whole topology on every visit. An
Open dialog searches the name list and ticks one or many; each becomes a canvas tab, and a tab's graph
is lifted only when it first becomes active. The dialog returns the **full desired set**, so unticking
closes a tab.

⚠ **Per-tab state is the subtle part.** Each tab parks its graph *and* its dirty flag on switch —
without that, switching tabs silently discards unsaved edits (a real bug caught in review: the first
version parked only in `activateTab`, while `select` also changes tabs). Every path that changes the
active tab goes through `parkCurrent()`. A dirty tab refuses to be closed by an untick (kept open with
a toast) and confirms on its own close button; those are the only two ways edits can be discarded.
The active tab's `dirty` mirrors into the per-tab set through **one effect**, so the dozen scattered
`dirty.set(...)` call sites stay ignorant of tabs.

**Multi-pipeline has two answers, deliberately.** Tabs (editor) for working on several pipelines one at
a time; Topology for seeing them merged. The tabbed editor never renders more than one graph at once —
that is what keeps "which pipeline does this edit belong to" answerable.

## The editor shell is full-bleed, Visio-style (2026-08-02)

Edit mode is an **editor shell, not an admin page**: `pipelines.component.html` branches on `mode()` and
gives the editor the whole route — no page header, no `p-6 sm:p-10`. View mode keeps the standard chrome,
and the mode toggle + `<inspecto-ai-explain>` project into the editor's own toolbar via `[toolbarEnd]`, so
there is exactly one bar and one `<h1>`.

Regions, all collapsing toward the canvas:

- **Toolbar** — one compact row, divider-grouped (document · file · run · edit · assist). The open
  pipeline is a **mat-menu title button**, not a `mat-form-field`: the gamma theme parks every field's
  floating label ~25px above the control (`angular-material.scss`), which alone made the bar 87px; the
  menu takes it to 53px. Don't "restore the select" without re-measuring.
- **Left dock** — the step palette (`app-pipeline-palette`), search + collapsible category sections. It is
  a **docked panel now, not the old floating popup**; a search overrides the fold state.
- **Right dock** — `Properties` / `Assist` tabs. The two `<inspecto-ai-assist>` surfaces moved here off the
  canvas band they used to occupy; `showRightTab('assist')` is the toolbar's sparkle button.
- **Bottom dock** — one `bottomTab` signal (`'dryrun' | 'validation' | null`) replacing the old
  `dryRunOpen`/`validateOpen` pair; dry-run output and findings are one dock, not two stacked bands.

Both side docks are `[inspectoSplit]` (persisted at `inspecto.split.pipelines.{palette,inspector}`) and
collapse to a 40px icon rail. **Gotchas that cost a cycle each, recorded in the `angular-ui` skill:** the
shell must bound itself to the viewport (`calc(100dvh - 120px)`) because nothing above a routed pane is
height-bounded; the split handle must stay mounted while collapsed; the G6 host needs its own
`ResizeObserver`; and the palette's `groups` must be a **signal input** — as a plain `@Input` its
`filtered()` computed cached the empty first pass and the catalog never appeared.

## The node-type vocabulary is the engine's, and only the engine's (2026-07-31)

The palette is a **faithful port of the backend enum `BuiltinNodeType`** — what
`GET /pipelines/node-types` actually serves: `acquisition`/`adapter` (SOURCE), `parser` **and
`parser.delimited`** (PARSE — a family since 2026-08-15, see
[per-format node types](../../backend/pipeline-graph/design.md)), the
`transform.*` family + `enrichment` (TRANSFORM), `sink.persistent|materialized|view` (SINK), and
`alert`/`gap`/`event` (**CONTROL**). Edge `rel`s are `PipelineRel` constants (`data` is the default
downstream edge; `success`/`failure` are **sink**-emitted; `dropped`/`invalid`/`duplicate` are the diverted
side of a record operator; `route:*` are operator-named).

⚠ **It used to be invented** — `collector.file`, `collector.database`, `collector.stream`, `sink.file`,
`parser.dsv`, `transform.record|aggregate|alert`, plus a `kept` rel that was never a `PipelineRel`. Only
`transform.filter` and `transform.route` overlapped with the engine, and CONTROL was missing entirely. It
survived unnoticed because **nothing rejects it**: `PipelineValidator` flags an unknown type as
`UNKNOWN_TYPE` (a *warning*) and never inspects config keys, `PipelineCodec` stores `config` as an
unchecked map, and `PipelineCompiler` groups nodes by matching against the enum — so an unknown-typed node
was **silently dropped** and could never become the acquisition input. The mock served the invented
palette, so it all looked correct offline: the *mock more lenient than the server* failure mode.
`pipelines.handler.spec.ts` now pins the palette to the enum. **Adding a type to the mock without adding it
to `BuiltinNodeType` re-opens exactly this hole.**

### Save-ability is a separate axis from runnability — `lowerable` (2026-08-02)

`GET /pipelines/node-types` carries **`lowerable: boolean`** per type (`PipelineProjection.catalog()` →
`PipelineEditable.isLowerable`, mirroring the `LOWERABLE` set). It answers *"can a save lower this back
to the flat `*_pipeline.toon`?"* — **9 of the 20** types. It does **not** mean "the engine can run it":
the engine runs far more than the flat config can round-trip (`RowShaper.shape` handles the whole
`transform.*` family). Keep the two questions apart; conflating them is how the palette came to present
20 equal-looking options, 11 of which failed at Save with a 422 `UNSUPPORTED_NODE`.

The palette disables non-lowerable entries (dimmed, non-draggable, explanatory tooltip). Opening a
grandfathered flow that *contains* them shows a warning banner naming the types — deliberately a
**warning, not read-only**, because deleting the offending node is the only way to make the pipeline
saveable again, and read-only would lock out that repair.

⚠ The mock's `LOWERABLE` list must stay in lockstep with the server's; `pipelines.handler.spec.ts` pins
it by name. This is the same *mock more lenient than the server* trap described above.

Related: **save refusals all land in the Validation dock** (persistent, click-to-select-node), not a
first-only toast — an n-problem graph used to mean n save→fix→save cycles.

### Both editor test affordances now work against a real server (2026-08-02 → 2026-08-14)

Neither is mock-gated any more. *Run to here* was ungated when its route landed; *Test processor* was
**replaced** rather than repointed — see below for why a repoint could not have worked.

- *Run to here* — ✅ **works against a real server since 2026-08-14.**
  `POST /pipelines/authored/{id}/run?to=` is registered (`PipelineRoutes.testRun`,
  `canAuthorWorkbench`) and `scratchRunAvailable` is now plain `true`. It parses the picked inbox files
  through the real ingest path into a scratch root — zero production side effects — then previews the
  graph over the parsed rows. `to=` **bounds the walk since 2026-08-14** to the ancestor closure of the
  chosen node — the same rule the offline mock's `subgraphTo` uses, so mock and server agree about which
  nodes a run covers. An unknown `to=` is a 400.
  Details: [`../../backend/engine/pipeline-test-run.md`](../../backend/engine/pipeline-test-run.md).
- *Test processor* — ✅ **replaced 2026-08-14 by "Test &lt;component&gt;…"**, which opens the bound
  component in `ComponentFormDialog`, where the sample-driven test already lives (`runTest`). Shown only
  when the node binds a registered component of a family the backend can dry-run
  (`TESTABLE_KINDS` = transform/grammar/sink; `schema`/`mapping` have no `/test` route).

⚠ **A repoint was never viable, and the reason is not the one recorded here through 2026-08-13.** The
old `testNode` sent the wrong two segments — but it also posted an **empty body**, while
`POST /components/{transform|grammar|sink}/{id}/test` requires `sampleRows` (transform/sink) or
`sampleText` (grammar). Fixing only the URL yields a button that reaches a live route and still fails,
because the node dialog collects no sample. `ComponentFormDialog` is the one surface that does, and it
was already the sole caller of `testGrammar`/`testTransform`/`testSink` — so the node dialog hands off to
it instead of growing a second sample-collection UI. `PipelinesService.testNode` and
`ComponentTestResult` were deleted with it (no other callers).

⛔ **An inline-config node cannot be tested by any existing backend surface** — do not "finish" this by
adding a body to the component test routes without deciding that deliberately. Those routes resolve the
component through `ComponentStore` and 404 when it is absent (`ComponentRoutes.java:266-289`), and the
two config-body previews (`/config/preview/parsing`, `/config/preview/schema`) cover only grammar parsing
and schema casting, not arbitrary node types. The test routes are **not** capability-gated — previews run
on a throwaway DuckDB — so ungating raised no access question.

Corollary worth keeping: **which connector a source uses is carried by its Connection profile**
(`collector.connection`), not by the node type — hence one `acquisition`, not a file/database/stream split.
A connector's own options (`query`, `watermark_column`, `topic`, `bootstrap_servers`) live in the
ConnectionProfile's `options:` map, read by each connector — they are **not** pipeline `collector:` keys.

The generic **node-config dialog** (non-parser nodes) is schema-form-driven from the per-type tiered
`node-attributes.ts`, keyed by those engine types. `acquisition` is the exception: since 2026-08-04 it
renders the shared **`<inspecto-collector-config>`** — the same component, over the same shared
`COLLECTOR_ATTRIBUTES` (`inspecto/component-model/`), that Onboarding's Collection stage renders, so it
gains the mode toggle, Test connection and create-a-Connection affordances and writes the DERIVED
`collector.connector` ([Collector configuration](collector-config.md)). Two hand-written tables for one
`collector:` block is precisely how this feature drifted into
keys the engine never read (`recursive` as a boolean, `min_age_seconds`; the real ones are
`recursive_depth` and a nested `stability.window`). Sinks are specced only for the `output:` keys the
backend reads (`format`, `compression`); the remaining `transform.*` types are **deliberately unspecced**
rather than guessed. Types without a schema fall back to the free-form key/value editor ("Additional
config", collapsed when a schema exists) — the conversion is non-lossy by design. Declared defaults **persist on save** even when untouched
(product-confirmed 2026-07-02: configs stay explicit/self-documenting).

### The onboarding deep link (P6-a, 2026-08-16)

`/catalog/onboard/:name/:stage` is a **redirect** into the guided editor —
`/pipelines?guided=1&open=<name>&stage=<chip>`. `onboard-redirect.ts` is the one statement of that
target, used by the route *and* by the Catalog's two navigation sites, so a bookmark and a button
cannot drift; it is its own file because `catalog.routes` imports `CatalogComponent`.

- **The matcher stays.** Only `loadComponent`/`canDeactivate` were removed, so old per-stage bookmarks
  resolve instead of 404-ing.
- ⚠ **`select()` is a load, not an idempotent setter.** The effect that consumes `?open=` fires once
  per id — re-running it refetches the graph and silently discards that tab's unsaved edits.
- ⚠ **`?open=` also switches the pane to Edit.** The wizard was never a read-only surface, and landing
  in View makes every checklist chip a no-op.
- `schema` and `keys` both map to the Schema chip — a Reference's "Keys & Load" stage authors the same
  artifact. An unknown stage carries no focus rather than inventing one.

### Guided mode: the checklist chips and the readiness gate (P6-d, 2026-08-16)

The wizard's stage rail becomes a chip strip in the editor — Collect → Parse → Schema → Enrich →
Publish, each with a status word and its finding count, each click opening that stage's node. Guided
mode rides `?guided=1` (the routing fact "the operator arrived from Onboard"); off by default.

- ⛔ **The stage model is derived from the graph, not ported from the wizard.**
  `OnboardingStateService.stageStatus` reads *blocks* of a server-held draft; the editor holds an
  `AuthoredPipeline`. `pipeline-stages.ts` answers the same question from the nodes and reuses the
  existing `NodeStatus` model — a second opinion about readiness is how a chip and the canvas card
  under it come to disagree.
- 🔴 **A `transform.map` node is on every lifted graph** whether or not anything authored it, and a
  derived one is `unconfigured`. The Schema stage therefore keys on **authored evidence** — the parse
  node's schema artifact, or a map node carrying config — never on that node's presence or status.
- ⛔ **The go-live readiness gate is guided-only.** `validatePipeline` does not require a parse node,
  so a hand-built collect→sink graph is legitimate; the wizard's five stages are the *Stream* contract,
  not the editor's. ⚠ It also waits for the served node-type catalog: every stage resolves through
  `typeCat`, so an unresolved catalog reads as five empty stages and would refuse a ready pipeline.
- 🔴 **A chip selects its Step before opening it.** `openNodeConfig` is gated on `canAuthor()`, so in
  View mode or the Business lens the strip was otherwise a silent no-op. Selecting is not gated, so the
  chip works in every mode and only the editing half is withheld.
- `rejects` (ran, but dropped rows) does **not** promote a stage to ✓ — the warning surfaces as the
  chip's finding count. The Schema stage tops out at `configured`: the editor has no preview thread
  yet, and claiming a validation it never ran would be a lie.

### Derived, not asked: the enrichment wiring seed (P6-c, 2026-08-16)

Onboarding's Enrichment stage renders **no** wiring form at all — it derives input/output/triggers from
the draft. The editor's node dialog has to ask (it has no wizard draft), but a **fresh** companion's
fields now arrive filled from what the host pipeline knows, so the two surfaces ask the same amount.

- **The convention is one function, shared:** `enrichmentWiringDefaults`
  (`inspecto/enrichment/enrichment-wiring.ts`) owns *input = the Stage-1 output · output =
  `<base>/data/enriched/<name>` · trigger = `on_pipeline`*. ⛔ It lives in shared `inspecto/`, not the
  onboarding feature, because the wizard shell that owned it is being retired (P6-e) — both hosts derive
  through it, and only pipeline-DERIVED facts travel in `NodeConfigData.enrichmentHost`.
- 🔴 **The Stage-1 store travels only when exactly ONE sink declares a `database`.** A multi-destination
  pipeline has no single "the output"; seeding one would point the transform at a store the author never
  chose, and an invented store path reads zero rows everywhere while looking like it worked. The required
  field stays blank instead. ⚠ **Quarantine is SINK-category too and carries only `dir`**, so the
  derivation filters on the config, not the category — otherwise it counts as a second destination and
  suppresses the seed on every pipeline that has quarantine.
- ⚠ **A seed is one-shot.** It is read once and deliberately not re-derived as the author renames the
  companion — a seed moving under an edited form clobbers it. A **bound** companion's file always wins,
  including a `triggers.on_pipeline` naming a different pipeline: this dialog edits the companion, and
  silently re-pointing a deployed enrichment at its host is not an edit anyone asked for.

### The enrichment wiring form's partition lists (2026-08-13)

The `enrichment` node authors a **companion** `*_enrich.toon` rather than `node.config`, through
`ENRICHMENT_WIRING_ATTRIBUTES` (`inspecto/enrichment/enrichment-attributes.ts`). Its two partition keys
are specced as `list` chips, and the shape is load-bearing in three ways worth keeping:

- **Both keys are engine-REQUIRED, and an empty list is the legal "unpartitioned" value.**
  `EnrichmentConfig.fromMap` throws `Missing or invalid list` when either is absent, so while they were
  unauthorable a **fresh** enrichment authored here wrote a config that could never load — it saved,
  then failed to register under the misleading "it will load on the next service restart". They declare
  `required: false` on the `required` tier with `default: []`, and the save sets both **explicitly** on
  the draft rather than letting `nestKeys` emit them, because `nestKeys` **prunes an empty array**.
- **Speccing a key makes it form-OWNED.** `ownedLeaves` is derived from the spec table itself, so an
  owned leaf is deleted from the existing block and replaced wholesale. An `output.partitions` entry may
  be the sink's `{column, source}` map (2026-08-11) where `source` declares event time and drives the
  recorded bounds — so the naive chips spec would have silently dropped it. The round-trip is explicit:
  hydrate flattens map entries to their `column`, save re-marries `source` onto a column the operator
  kept (`remarryPartitionSources`); a new column writes bare, a removed one takes its `source` with it.
- ⚠ **The `list` chips type does NOT compose with `flat-keys.ts`, which has its own older list
  convention.** `flattenBlock` **joins any array to a comma string** and `nestKeys` splits back only for
  a hardcoded `LIST_KEYS` leaf set. So a `list` control fed through this transport receives
  `"year,month"`, not an array. Handled *inside the dialog* — the two partition flat keys are re-seeded
  as real arrays after `flattenBlock` — deliberately NOT by making the shared plumbing spec-aware, which
  would ripple into the parser dialog and Onboarding. **Any future `list` spec on a flat-key surface
  needs the same treatment or a decision to unify the two conventions.**

~~⛔ Known hole, not fixed here: `ConfigSpecs.enrichment()` declares both keys optional while the parser
requires them~~ **CLOSED 2026-08-13 (follow-up commit)**: both keys are now `FieldSpec.required`, so a
non-UI client missing them gets the 422 at write instead of a config that only fails at registration.
An empty list still passes — `RawConfig.present` is true for `[]`, so required means "the grain is
stated", matching the engine. `ConfigLoaderTest` pins the gate both ways **and** pins that
`partitions: []` survives `toToon` → strict decode → re-validate (the steps:/sinks: file-level lesson —
JToon writes it as `partitions[0]:` and decodes it back as an empty list, so the UI's empty-grain draft
does not lose its key on the trip through the file).

## A spec `key` *is* the engine's config key — there is no mapping layer (2026-08-03)

`AttributeSpec.key` is written **verbatim** into `node.config` (`node-config.dialog.ts`), and the app has
**no case-conversion or key-mapping layer at all** — zero `toSnake`/`camelize` helpers, no interceptor doing
it. The only transform is `component-model/flat-keys.ts`. So a spec key that isn't already the exact backend
key produces a control that persists a value nothing reads, and nothing rejects it: `PipelineValidator`
never inspects config keys and `PipelineCodec` stores `config` as an unchecked map.

Keys corrected under this rule: `transform.filter` is **`where`** (`RowShaper.java:79` reads
`str(node, "where")`; `"predicate"` appears there only as the `requireExpr` error label at `:89`), and
`transform.route` offers **only `mode`** — routing is `branches[]{key, where}`, authored on the canvas
**edges**, and `route_column` was read by nothing. `partition_by` was likewise phantom on sinks: real
partitioning is schema-level `partitions[]{column, source, type}` / legacy `partitionKey`.

### ⚠ The two representations share node-type names but not runtimes

**This is the trap that makes a key-name fix look like a feature fix.** `RowShaper` — which does read
`where` — is reachable only from an authored `*_flow.toon` graph via `PipelineJobRunner`, and
`POST`/`PUT /pipelines/authored` are **405 since W5**. This editor can only write the flat
`*_pipeline.toon`, whose lower merges a `transform.filter` node's cfg **wholesale** into
`processing.csv_settings` (`PipelineEditable.java:277`, mirrored in `mock/pipeline-editable.ts`).

**Corollary for any future key check:** "read somewhere in `PipelineLift`/`PipelineCompiler`/`RowShaper`" is
too weak a right-hand side — it would have called the pre-fix `where` green. Bind a node type to the runtime
that executes **the file this editor actually saves**.

### `transform.filter` has two filtering moments — never collapse them (D7, closed 2026-08-03)

The above trap was originally written up as "a filter authored here reaches no runtime". Verifying the fix
options overturned that framing, and the corrected version is the durable lesson:

| Moment | Keys | Runtime |
|---|---|---|
| **pre-parse** | `include_prefixes`/`include_regex`/`exclude_prefixes`/`exclude_regex`, anchored on `filter_target_column` | `DuckDbCsvIngester.filterWhere:713-730`, inlined into the `read_csv` SELECT — matches **one raw physical column** before any field is named or typed |
| **post-parse** | `where` | `DataTransformer.materialize` — SQL over the **mapped, typed target columns** |

The pre-parse group **always worked** on this path and round-tripped through `PipelineLift.filterConfig`;
it was simply never declared as `AttributeSpec`s, so the dialog couldn't reach it — a working feature
hidden, not a broken one. The post-parse `where` was the genuinely missing runtime and was added 2026-08-03.

⛔ **Never substitute one for the other.** `amount > 0` is inexpressible as a `regexp_matches()` pattern over
an unparsed column, and a regex over raw text is inexpressible as a predicate over typed columns. Same-
sounding names, different capabilities — that substitution is the exact drift this contract exists to stop.

Two non-obvious consequences worth keeping:

- ⚠ **Refusing a node type in `lower` is not a safe "smallest fix"** — `PipelineLift` *emits*
  `transform.filter` for any pipeline with row filters, so an `UNSUPPORTED_NODE` refusal would break
  open-then-save on exactly those pipelines. Check whether lift emits a type before proposing to refuse it.
- ⚠ **`transform.map` being dropped by `lower` is NOT the same bug.** Its lifted config is only
  `{schema: …}` (`PipelineLift.java:189-192`), derived from the pipeline schema and regenerated on every
  lift, so dropping it is lossless. (The `PipelineEditable.java:209` comment's "companion-persisted" half
  refers to `enrichment`; the `_enrich*` files are audit ledgers, not a node-config companion.)
- ⚠ **`filter_target_column` lifts only alongside a pre-parse list.** It is the index those lists anchor on,
  so emitting it for a predicate-only pipeline made `lower` write a key the file never had — which is how a
  "verbatim" round-trip test catches an otherwise invisible asymmetry.

### The dialog bridges flat ↔ nested in both directions

Nested `collector:` blocks are authored with `__` spec keys (`duplicate__mode`, `stability__window`), so the
dialog runs `nestKeys` on **save** and `flattenBlock` on **load**. Both halves matter: without the load half,
a real `duplicate: {mode}` block matches no flat spec key, falls into the free-form editor as a JSON
**string**, and — free-form being applied last in `save()` — **overwrites the schema form's own value**.

Save **deep-merges** each nested root over the node's prior config (`mergeBlock`) rather than rebuilding it,
because several engine-read sub-keys have no `AttributeSpec`: `duplicate.algorithm`,
`stability.size_checks`/`ready_marker`/`exclude_temp_files`/`exclude_temp_patterns`, `post_action.tags`/
`on_unsupported` (`PipelineConfigParser.java:449-470,516-527`). Without the merge, a guided save silently
destroys hand-authored TOON.

⚠ `connection` is stripped by both lift and lower (*"carried on `use:` … never mirrored in config"*,
`PipelineEditable.java:124-125,247-249`), so it is discarded on every save — but **don't just delete the
attribute**: `bindKindFor('SOURCE')` is `null`, so acquisition renders no Connection picker and this is the
only discoverable way to set one. The fix is a real binding first, then a per-adopter exclusion (it must stay
in the shared table for Onboarding, which authors `collector:` directly).

### A key with zero readers may be MISNAMED, not phantom

`mock/seeds/seeded-node-config.spec.ts` runs all six seeders into a fresh `MockStore` and fails on phantom
keys, so a dead key cannot re-spread through the seeds (`partition_by` had reached **five** files that way).
Two rules are pinned in its docblock, both learned by breaking something:

- **Check for a differently-named equivalent before deleting a zero-reader key.** `key_columns` + `mode:
  'upsert'` on the case-study `sink.materialized` seeds have no Java reader, but upsert-by-key is real at
  pipeline level as `reference: {load: upsert, key: [...]}` (`PipelineConfigParser.java:406-412`). Deleting
  them broke a documented case-study invariant; the fix is to rename to the engine's shape (**D8**), not to
  drop the capability.
- **Deadness is per node type.** `mode` and `table` must never go in a global dead-key list — `mode` is real
  for `transform.route` (`RowShaper.java:104`, `ConservationCheck.java:78`) and `table` is round-tripped for
  `sink.persistent` (`PipelineEditable.java:149`).

### The name contract is checked by *driving the save*, not by comparing strings (2026-08-04)

`NodeConfigNameContractTest` (inspecto-engine) is the guard for all of the above. It does not compare two key
lists — a string check would have called D1 green while filters still no-opped. Each declared attribute is
given a sentinel value and pushed through the editor's own path, then read back off the parsed config:

```
PipelineEditable.toMap → PipelineCodec.fromMap → [set key] → PipelineEditable.lower
      → ConfigCodec.toToon → PipelineConfig.load → assert the engine field == sentinel
```

**A key that does not survive that trip is read by nothing, however it is spelled.** Three things this pinned
that are easy to get wrong again:

- ⚠ **`PipelineLift.lift` is the wrong left-hand side.** It is the legacy/authored lift and emits a
  *different vocabulary* than the editable lift the editor uses — `includes`/`excludes` plural
  (`PipelineLift.java:114-115`) vs `include`/`exclude` verbatim from the file
  (`PipelineEditable.editableConfig:122-123`). A check built on it fails correct keys and passes unreachable
  ones. **Use `PipelineEditable`** for anything about what the editor saves.
- ⚠ **`filterConfig` omits a key whose list is empty**, so a reverse ("everything the runtime carries is
  declared") assertion needs a fixture exercising *every* capability. An under-populated fixture reports a
  phantom missing spec — it did exactly that on the first run.
- **Equality is only valid where the node's cfg *is* the vocabulary** (`transform.filter`). For `acquisition`
  and `sink.persistent` the node carries the whole raw `collector:`/`output:` block and the shared tables are
  a curated subset by design — asserting equality there would be a permanently red, therefore disabled, guard.

### Node config specs are SERVED, and the client table is a fallback (2026-08-04)

`GET /pipelines/node-types` carries `attributes[]` per node type — authored in
`inspecto-engine/.../pipeline/NodeAttributes.java`, embedded by `PipelineProjection.catalog()`. The node
dialog prefers them; `pipelines/node-attributes.ts` is now the **fallback** for before the catalog resolves
and for the offline build. Before this, per-node cfg keys existed *only* client-side while the server's
catalog carried no attribute vocabulary at all, which — with no case-conversion layer anywhere — is the root
cause every config-key defect (D1–D9) traced back to.

Four things to know before touching it:

- **A served empty array is not the same as an absent one.** Empty means the server says the type has no
  schema; absent means the catalog has not answered. The dialog uses `?? `, so an empty list is honoured
  rather than silently re-enabling the client table — otherwise a type the server deliberately unspecced
  would keep drawing a stale form.
- **The control vocabulary is single-sourced.** `NodeAttribute.TYPES`/`TIERS` delegate to `FindingsSpec`
  (the other server-authored spec surface, whose `Section` was already a field-for-field mirror of
  `AttributeSpec`), so widening an `AttributeType` stays a one-place change. `FieldSpec` in inspecto-config
  is a *different* vocabulary and is deliberately not reused.
- **Both tables are byte-compared to one committed artifact**,
  `inspecto/mock/node-attributes.contract.json` — by `NodeAttributesContractTest` on the Java side and
  `node-attributes.spec.ts` on the TS side. Regenerate only deliberately
  (`mvn … -Dnode.attributes.write=true`), and when it fails decide *which* side is wrong first: regenerating
  makes the test pass by moving the goalposts.
- **The mock serves that same file**, so the offline preview cannot drive node forms from a different table
  than production.

⚠ Two nondeterminism traps if you extend the serialization: `Map.copyOf` and `Map.of` are **unordered**, so
using either makes the emitted JSON's key order vary between JVM runs and the contract test fail at random
(and get written off as flaky). Use `LinkedHashMap`.

### A shared attribute table is correct per *block*, not per *node* (2026-08-04)

`COLLECTOR_ATTRIBUTES` is deliberately shared with Onboarding so one table serves both features — but the two
adopters reach the `collector:` block differently, and a key can be real for one and unreachable for the other:

| Key | Onboarding (authors `collector:` directly) | Acquisition **node** (flat lower) | Resolution (2026-08-04) |
|---|---|---|---|
| `connection` | real | not a cfg key — rides on `use: connection/<name>` (**D3**) | the attribute **writes the binding**: seeded from `use` on load, written back to `use` on save |
| `duplicate__mode`, `duplicate__on_change` | real | ~~belongs to the fingerprint-dedup node~~ (**D9**) | **superseded 2026-08-04**: the node was removed and these are real on acquisition too — see below |

⛔ **Neither was a dead key, and pruning the shared table would have been the wrong fix** — it would break the
adopter for which the key is real. This shape has now appeared three times (D3, D9, and the near-miss on
`key_columns`), so treat "declared but unreachable" as a *per-adopter* question from the start. The fix is always
one of: **derive** the adopter's table (never fork it), or move the key to the node the engine reads it from.

⚠ **D9's resolution was the wrong one of the two, and got reversed** (2026-08-04). The derivation was
built before anyone checked where dedup *executes*: `ledgerFilter` reads `collector.duplicate` inside the
`CollectorProcessor` poll cycle, so `transform.dedup.fingerprint` had no runtime — the second fix (move the
key to the node the engine reads it from) was the right one, and the node was removed rather than fed. Both
surfaces now render the whole shared table. Full account: [Collector configuration](collector-config.md).
The lesson is the cheaper one: **ask where the engine reads the key before choosing between derive and move.**

One trap worth carrying forward from closing D3:

- **A Connection is not a `ComponentType`.** The obvious fix — `bindKindFor('SOURCE') → 'connection'` —
  produces a picker that calls `GET /components/connection`, a route that does not exist; connections have their
  own service. `bindKind` is only for the component registry (`grammar`/`transform`/`sink`). The picker lives in
  the shared `<inspecto-collector-config>` instead.

### In the recipe palette, `type` is the unique key — a verb may name two shapes (2026-08-10)

`GET /pipelines/step-types` publishes **nine** entries, not the seven verbs of the recipe grammar:
`route` has its own, and **`transform` appears twice** — once for `transform.filter`, once for
`transform.join`. The verb string stays the recipe's own word because `RecipeCompiler` has no `join`
case (a join is `transform: {join: …}`, handled inside `transform()`), so a `verb: "join"` entry would
advertise a vocabulary the compiler refuses. Consequences worth carrying:

- ⚠ **Never key anything on `verb`.** The Add-Step menu's `@for` used `track v.verb`; duplicate track
  keys are an Angular **runtime error**, so it now tracks `v.type`. A spec asserting the component's
  `verbs` *input* passes either way — assert the rendered menu overlay.
- ⚠ **A verb can serve a spec the palette never reaches.** `NodeAttributes` carried a `transform.join`
  spec while `RECIPE_VERBS` mapped `transform` to filter alone, so join was authorable **only from the
  demoted canvas** (which loads the full `node-types` catalog) from the day it began compiling. When a
  node type gains a spec, check the *palette* serves it — a spec's existence is not reachability.
- ⛔ **One polymorphic entry with a discriminator does not work here.** `PipelineEditable.lower`
  dispatches on the node's **`type`**, never on config content, so one node type cannot author two
  config blocks; a discriminator could only gate visibility and would still need a retype (which nothing
  in the editor does today). The `putAll` on the filter branch would also leak the discriminator into
  `processing.csv_settings` verbatim.
- **`processing.join` is ONE block, so a second join is refused (`MULTI_JOIN`)** rather than silently
  replacing the first. ⚠ `recordDedup`, `routeNode` and `summarizeNode` share that single-slot,
  last-one-wins shape and are **not** guarded — whether to guard them is an operator call, because a
  graph that saves today (losing a node) would start being refused. Their current behaviour is now
  **pinned** by a case per slot in `PipelineEditableTest`: the discard is silent and the **LAST** node
  wins. Those three tests are what an inversion would have to rewrite — see
  [BACKLOG](../../../BACKLOG.md) §4. The mock `pipeline-editable.ts` mirrors the same four slots and
  must move in the same commit as any refusal.
- **A step card captions the type's own label** ("Join", "Filter"), via the optional `[typeLabel]` input
  that `typeLabelMap()` builds from the served node-type catalog. Before this, cards showed the
  *category*, so every transform read "Transformer" and a join was indistinguishable from a filter. A
  type the catalog gives no label for falls back to the category label — never to the raw `type` string,
  which is not user-facing vocabulary.
- ⚠ **`uniqueNodeId` still bakes the type into the id** (`transform_join_1`), which is what a card falls
  back to when the node is unnamed. Any future retype affordance makes that id a lie.
- **`PipelineLift` no longer stamps a lifted node's `name` with its own type label.** `isNamed()` /
  `stepTitle()` show the caption twice when `name` is non-blank (title = name, plus the `[typeLabel]`
  caption beside it) — so a name that happens to equal the type's label ("Collect"/"Collect") reads as a
  visible duplicate, not a real operator-given name. `PipelineLift.java`'s node builders
  (`acquisitionNode`, `dedupMarkerNode`, `parserNode`, and the join/dedup/summarize/route builders in
  `branch()`) now pass `null` instead of the hand-written label; `stepLabel(kind)` returns `null` for
  every chain-step kind except `filter`, whose legacy "Row filter" name is kept because it differs from
  the type's own "Filter" label and is a real (more specific) name. See [BACKLOG](../../../BACKLOG.md) §4.

### D8 shipped: pipeline settings, a surface independent of the graph editor (2026-08-13)

The `key_columns`/`mode: 'upsert'` renaming this file's earlier section pointed at (**A key with zero
readers may be MISNAMED, not phantom**, above) is now built: **"Settings…"** in the pipeline editor's ⋮
menu opens `PipelineSettingsDialog` (produces select; when `reference`, a load-mode select, a
comma-separated key field, and refresh-seconds). It talks to a dedicated `GET`/`POST
/pipelines/{name}/settings` pair — **not** `PUT .../graph` — because `produces`/`reference` are
pipeline-level, and `PipelineEditable` deliberately never models non-node keys (§ above, "Node config
specs are SERVED"). Full design + backend detail in
[pipeline-graph-design.md §17](../../backend/pipeline-graph/pipeline-graph-design.md#17-pipeline-level-settings--a-dedicated-surface-for-what-the-graph-editor-never-models-2026-08-13).

A plain reactive form, not `<inspecto-schema-form>`: `reference.key` is server-specced as `FieldType.LIST`,
and `fieldSpecsToAttributes`'s `TYPE_MAP` (see "Node config specs are SERVED" above) still deliberately
skips served `LIST` — unchanged by this work, so a hand-built form was the smaller, honest choice over
half-wiring generic list rendering for one field.

## The Load stage: schema on the parser, mapping on the map node (P4, 2026-08-16)

The definition drawer gained the last two stages. Where their keys live is the whole design, and it is
**not** where the plan assumed:

- **`processing.schema_file` is the PARSER node's key.** `PipelineLift` puts it there with
  `csv_settings`/`schemas`/`segments`/`ingester*`, and `PARSER_NO_SCHEMA` checks it there. So the
  **Parse drawer** authors the output schema for the flat formats — which is what §4b's icon table
  always meant by "+ output schema".
- **`transform.map` authors exactly `{columns, rules}`** (`MAP_AUTHORED`, pinned in
  `PipelineEditable.java` and mirrored in the mock). `schema` and `csv` on a map node are
  `MAP_DERIVED` — resolved by the engine, dropped by `lower`. So the **Load drawer** authors the
  mapping and nothing else.
- ⛔ A single drawer over "schema + mapping + table" would span **three** node types and break the
  one-`[node]`-in/one-node-out contract every drawer holds. It was split instead (operator, 2026-08-16).

### The sample thread, one per tab (P6-e follow-on, 2026-08-16)

The editor now keeps a `DefinitionStateService` **per open tab** — a `Map<id, …>` beside `cachedModels`,
born in `select()` (the one path every tab open goes through) and dropped by `forgetTab()`, so closing
a tab and reopening it starts clean the same way the graph is refetched rather than restored.

🔴 **It is a Map and not a `providers: []` entry, deliberately.** DI providers are static per component
instance, and this editor is ONE instance hosting every tab — a provider would hand every open pipeline
the same sample. For the same reason `InspectoSamplePanelComponent` takes the thread as an **input**
rather than injecting it; that also keeps it pure, the D2 rule every definition pane follows.

⚠ **Reading the Map inside the `sampleThread()` computed does not track it** — deliberate: an entry is
created once when its tab opens and never replaced, so `selectedId` is the only dependency that can
change the answer.

The strip is mounted in the **parse drawer**, where the sample is consumed, and the drawer supplies the
grammar editor's `previewFn`. ⛔ That does not move where the parse runs: the stateless
`POST /parsers/{id}/preview` stays, because Onboarding only used `POST /config/preview/parsing` to post
a server-held pipeline **draft**, and this editor holds a graph. ⚠ **The failure path is why it is a
`previewFn` at all** — the grammar editor's `previewed` output fires on SUCCESS only, so a failing
re-parse would otherwise leave the previous "parsed · N cols" chip standing over a grammar that no
longer parses. ⚠ Only a **table** result feeds the thread; a record tree (ASN.1 / plugin) leaves it
untouched rather than clearing it — the parsed hop means "rows a downstream step can cast".

🔴 **The strip only appears for a per-format parse node** (`PARSE_NODE_FRONTENDS`), because only those
reach the definition drawer — a generic `parser` node still opens the grammar-editor **dialog**, which
has no thread. The offline sample `cdr_ingest` is exactly such a pipeline, which is why the preview
drive had to add a Delimited step to see the strip at all. Not a defect: the drawer is the definition
surface, and the dialog is the legacy path P3a deliberately left in place.

### Before that: the editor had no sample thread — and after P6-e, nothing else did either

Grounded 2026-08-16: that service was injected by `onboarding-shell`, `parsing-pane`, `sample-panel`
and `schema-mapping-pane` and by nothing under `modules/admin/pipelines/`. P2-0 landed it for the
wizard; adopting it in the editor was unbuilt work, not an existing seam — closed by the slice above.
Two consequences that held while the thread was absent, one of which still does:

- The Load pane takes its field list from the **parser's `schema_file`** — read-only context the host
  passes in, because the host is the only thing holding the whole graph. Rules map FROM schema fields
  anyway, so no sample is needed to author them.
- ~~**`POST /config/preview/schema`'s mapped-row half (B1) still has no consumer.**~~ Consumed since
  2026-08-16 — see below.

### The Load drawer tests a mapping against the thread (B1)

*Test mapping* posts `{raw: <the parser schema's raw>, mapping: {rules}}` over the rows the parse drawer
already parsed, and renders `mappedColumns` / `mappedRows` (first 20) under the rule grid.

- **It posts the rules being EDITED, not the node's** — the point is to try an edit before applying it.
- **The result is the thread's cast hop**, written back into `DefinitionStateService`: after a mapping
  test the parse drawer's own strip reads `parsed · N cols → cast · N ok`. A re-parse clears it, exactly
  as it clears any other downstream result.
- **An edited rule clears the grid.** A result may never outlive the config it came from; that is cheaper
  and more honest than a "stale" badge. ⚠ The clear runs from `valueChanges`, which `seedRules` emits from
  inside the node effect — probed, and those reads do NOT become dependencies of that effect, so no
  `untracked` is needed (the speculative guard was removed after the probe showed it never fires).
- 🔴 **Reachable only where the parse node is per-format** — a generic `parser` opens the grammar dialog,
  which has no thread.
- ⚠ Two things only the preview could show. The offline mock resolved a `DIRECT` source by **exact key**,
  so an identity rule seeded from a schema (field names upper-cased) rendered *"3 rows mapped"* over a grid
  of blank cells; it now folds case, matching how DuckDB binds an unquoted identifier. And the drawer's
  header labels a `transform.map` node **"PARSER"** — `kindLabel` is an acquisition/else ternary in the
  editor template; cosmetic, still open in BACKLOG.

### Two write-ordering guards, both falsified before being trusted

- **A saved schema is re-read on open and a fresh parse must not re-derive over it.** The operator's
  names, types and include flags are the truth; deriving from a new sample would replace them on Apply.
  A hand-authored `schema_file` outside the naming convention is reported and never touched.
- **A transform type the UI does not offer is preserved, not rewritten.** *Not offering* must never
  become *destroying*. ⚠ **Updated 2026-08-17:** the Load pane now authors **all four** types
  `TransformCompiler` recognises, so what this guard protects is a value the pane has never heard of
  (a hand-authored `LOOKUP`, say) — it is kept and shown alongside the four.

  **How the two specialised types are authored, given there is nowhere to put their parameters.** A rule
  is exactly `{targetColumn, sourceExpression, transformType}` — `MappingCsv` drops every other key — so
  both pack their parameters into `sourceExpression` as `|`-delimited positions, and the pane composes
  and decomposes that string rather than inventing a fourth key:
  - `CONCAT_DT` → `<dateColumn>|<timeColumn>`, **always both**. The compiler reads `parts[1]`
    unconditionally, so a bare column is an `ArrayIndexOutOfBounds` at run time — which is why
    `MappingRules` refuses it up front and why the pane never emits one. The timestamp format is *not*
    per-rule; it comes from the pipeline's `timestamp_formats`.
  - `FILENAME_DATE` → `<column>|<prefix>|<strptime>`, the last two optional. ⚠ **Trailing blanks are
    dropped, not emitted**: the compiler defaults a *missing* position (`""`, `%Y%m%d`), but an empty
    third position interpolates an empty format into the SQL — writing `col||` silently produces
    `TRY_STRPTIME(…, '')`. The 8-digit group in the extract pattern is hardcoded; the prefix is the only
    variable part. 🔴 It may only write **`EVENT_DATE`** — enforced in three places server-side
    (`TransformCompiler`, `MappingRules`, the mock validator), so the pane refuses anything else rather
    than posting a guaranteed 422.

  ⚠ **Offline, Test mapping renders these as blank cells.** The mock has no SQL engine, so its
  `mappedValue` returns null for everything but `DIRECT` — the offline preview proves the projection
  plumbing, never an expression's value. Its *validator* is a faithful mirror, so authoring is checked
  correctly even though the output is empty.

Both were verified by deleting the guard and watching exactly its own test fail — the only way to know a
first-try-green test is testing anything.

### Drift is reported, not applied — and only one third of a "re-sync" is safe

`POST /config/suggest/schema` takes the draft alongside the sample and returns a field-level diff (B3).
The Parse drawer renders it. Of §5.2's "re-sync merges, never clobbers", **only adding new columns is
automatable**: a type change cannot be auto-applied (nothing distinguishes a deliberate override from a
stale derivation) and a missing field cannot be auto-removed (the sample may simply be narrow). Both are
reported with their old→new types for a human call. The merge reads **all** grid rows, excluded ones
included, so appending never silently drops an unticked row.

⛔ **There is no `renamed` drift category and there cannot be one.** From a draft plus a fresh sample, a
renamed source column is indistinguishable from a remove+add — the same fact `SchemaCompatibility`
states for the save gate. It surfaces as a `missing`+`added` pair; calling that pair a rename is a UI
affordance over two facts, never a server claim. The diff joins on the **selector**, not the name:
`name` is an output column an author renames deliberately, so keying on it would light the indicator on
every intentional rename.

### Retiring the client type inference was not a pure deletion (D4)

`suggestTypes()` is gone; types come from `SchemaSuggest` via the server. But **the server's vocabulary
is wider than the grid's** — it votes `BIGINT`/`BOOLEAN`, while the grid offers only the four types
`TransformCompiler.direct()` actually casts. `narrowToSchemaType()` maps `BIGINT → DOUBLE` (there is no
integer cast) and anything unknown to `VARCHAR`; without it the retirement would have put unselectable
types into the grid. Only the **type** comes from the server — the **selector** stays client-derived,
being frontend-dependent (position for delimited/fixedwidth, key for json/text_regex) in a way the
server, which sees only rows, cannot know.

There was never a *second* client fork: `schema-editor.dialog`'s "Suggest from sample" already called
the server. It is a second entry point to one implementation.

### The shared `raw.fields[]` grid lives in `inspecto/schema/`

Extracted from the onboarding pane so the Parse drawer could author a schema too — a feature may not
import a feature, the rule that moved the segments editor in P3d. Host contract mirrors the segments
editor: seed `[rows]`, call `validate()` then `value()`, host owns every write, problems surface through
`problem()` so a shared grid never decides how a host reports an error. `[rows]` rebuilds on **reference**
change, so hosts hold the seed in a signal; and the onboarding pane reaches it through a **signal**
`viewChild` because `includedNames` is `computed()` off it and a plain `@ViewChild` would never re-run
that computed when the grid appears.

## Marker dedup lives on the acquisition node (P5, 2026-08-16)

File-grain duplicate detection is decided in the poll cycle, before anything is parsed. Fingerprint
policy (`collector.duplicate.*`) had ridden the acquisition node since 2026-08-04; **marker dedup
joined it in P5**, and `transform.dedup.marker` stopped being emitted. Config keys are unchanged
(`processing.duplicate_check` + `dirs.markers`).

### The toggle is authored, and authoritative in both directions

`duplicate_check` is an explicit boolean on the node, not "presence of a detail key". The lift always
emits `retention_days` when dedup is on, so presence *looks* like a usable switch — but then clearing
the retention field in the form would silently disable dedup on the next save. And when the toggle is
**present** it wins both ways: an explicit `false` must not fall through to a legacy marker node and
re-enable what the operator just switched off. One statement of the rule, `PipelineLift.markerHome`,
is called by all three readers (`PipelineEditable.lower`, `PipelineCompiler`, the guarantees panel's
mirror) — it was a copy in three places for exactly one commit before that hurt.

### Read-compat is a LOWER-only property

`transform.dedup.marker` stays in `LOWERABLE` and is still accepted; it is simply never emitted again.
An editor opened before the fold holds a lifted graph carrying one, and refusing it would delete that
operator's dedup on their next save. ⚠ Its `editableConfig` branch **was** deleted — nothing lifts it
any more, so that branch was dead the moment the lift changed.

### `authorable` ≠ `lowerable` (the palette's flag)

The palette used to filter on `lowerable`, which the retired node must keep. That single flag cannot
express "still saveable, never offerable", so the node-type catalog publishes **`authorable`**
(`PipelineEditable.isAuthorable` = lowerable ∧ not `READ_COMPAT_ONLY`). It is **optional** on the
client type: an older server omits it and the palette falls back to `lowerable`, which is right for a
server with no retired types. A graph carrying the retired node still renders and is *not* flagged
unsupported — pinned as the third case in the editor spec.

### The marker keys are NOT part of `COLLECTOR_ATTRIBUTES`

They live in `processing:`/`dirs:` and are only *borrowed* by the acquisition node, so they are their
own `MARKER_DEDUP` list and the node publishes `COLLECTOR + MARKER_DEDUP`. Folding them into the block
table would give Onboarding's Collection stage — which renders that table whole — four fields it would
write to a block nothing reads them in. This is the per-*block*-not-per-*node* rule above, in the one
case where a node's spec is legitimately wider than its block.

⚠ **`lower` dumps the acquisition node's config wholesale into `collector:`**, minus an exclusion set.
A borrowed key missing from `ACQ_FOREIGN_KEYS` therefore lands in a block nothing reads it from —
silently. The set is mirrored in the mock and the leak is asserted in both languages.

⚠ The Collection drawer renders the group with `tier: 'required'` + `required: false` (always visible,
never mandatory). As `optional` the switch sat behind the schema form's disclosure and the group
rendered as a heading over "Optional settings (1)" **and nothing else** — a unit test asserting the
heading text passed the whole time; only driving the preview showed it.

## Go-live registers the Dataset, from either surface (P6-b, 2026-08-16)

Activating a *stream* registers a Dataset over the store it lands, so landed data is queryable without
anyone authoring one. The hop is `DatasetRegistrationService` (`inspecto/api`), shared by the
onboarding shell and the Pipelines editor's toolbar action — **idempotent by `physicalRef`** whatever
the dataset's id, `sourceName` = the store (blank falls through to a default naming nothing, so the
dataset reads zero rows everywhere), and it **never reverses an activation that already succeeded**:
every failure resolves to a `failed` result the host reports as a warning.

🔴 **`produces` names two different things.** `PipelineSummary.produces` is the list of STORES a
pipeline produces; stream-vs-reference is the config-level `produces` behind the **D8 `settings`**
endpoint. Deciding the Dataset hop off the summary field is silently wrong. ⚠ When that read *fails*,
register nothing and warn — a Dataset over a Reference's store puts rows in the Catalog that nothing
should query there.

⚠ `InspectoConfirmService.confirm(message, title)` takes a **title string**; only `confirmDestructive`
takes an options object.

## The wizard shell is gone — what had to move with it (P6-e, 2026-08-16)

The onboarding stage-rail shell, its `OnboardingStateService`, all five stage panes, the placeholder
pane and `stage-attributes` were deleted (22 files, ~2.3k lines). The editor had been running the same
shared components for several phases, so the panes were thin hosts by then; what was NOT covered was
the shell's own **persistence and toolbar**. `onboarding-create.dialog` survives — it is still the
Catalog's entry point and still the only **Import** surface.

🔴 **Two shipped capabilities would have been lost silently.** Both were found by asking "who else
calls this?" of the shell's members, not by reading the plan — whose blast-radius note named neither:

- **`deletePipeline()` was a bare `remove()`.** No impact read, no `force`, and **no companion
  cascade** — so every delete this editor had ever done left orphan `<id>_schema` / `<id>_enrich`
  configs on disk. It now reads impact, names the dependents in the confirm ("2 datasets, 1 widget"),
  sends `force` only once they have been shown, and cascades. ⚠ The impact read is **advisory**: a
  failed read must still let the delete proceed — the server re-checks and 409s on its own, so
  refusing here would invent a refusal the backend does not have. Per-**segment** schemas
  (`<id>_<segmentKey>`) joined the cascade **2026-08-17** — ⚠ the earlier note here ("enumerating
  them needs the parsed block a delete no longer has") was **wrong**: a segment's schema path is
  authored config on the parse node (`parsing.<asn1|plugin>.segments` → `{key: path}`), so
  `ownedSegmentSchemas()` reads the basenames off the in-memory graph before the delete clears it.
  ⛔ **Scoped to the pipeline's own `<id>_` prefix** — a hand-authored path elsewhere may be shared
  with another pipeline, and orphaning that one is worse than leaving an unreferenced file; this is
  the same boundary the parse pane keeps when it refuses to re-derive over a foreign `schema_file`.
- **The stream-config export had no other home.** ⛔ The editor's transfer menu is the **Metadata
  Bundle** — Studio registry artifacts addressed by **id** — while a pipeline and its satellites live
  in the **config** namespace addressed by **path**, and the two collide on the word *schema*. Deleting
  the shell left `StreamTransferService.buildExport` with **zero callers** while the create dialog
  still reads a bundle: an import-only format. `exportConfig()` now sits in the editor's menu.

⚠ **`exportConfig` reads the SERVER-held config back** (`GET /config/pipeline/<id>`) rather than
lowering the open graph, and refuses while the tab is dirty. An export that carries the last saved
state while the screen shows something else is worse than no export. Stream-vs-reference comes from
**that config's own `produces`** — ⛔ not `PipelineSummary.produces`, which is the list of stores.

⚠ **One deliberate narrowing survives, recorded so it is not mistaken for a bug.** *View as graph* was
not carried over (it needs the kind, hence an extra settings hop, and the Catalog's lineage tab is
directly reachable). Re-confirmed 2026-08-17 as the end state, not a deferral.

**The export narrowing was REVERSED 2026-08-17 (A6).** Export configuration had lived inside the
`canAuthor()` menu, which contradicted `exportConfig()`'s own doc comment — inherited verbatim from
the shell — that a Business-lens operator handing a config to support is the point. Both exports are
read-only, so **both** moved out together into an always-visible **Export** menu rather than diverging
from each other (consistency with *Export document* was the narrowing's stated justification). Nothing
pinned the old placement — no spec, mock or backend. ⚠ The author menu keeps its five actions and the
exports appear **once**, in their own host: a `mat-menu`'s items cannot be shared between two menus, so
re-homing them means moving them, not duplicating them.

## `description` is declared and displayed (2026-08-17)

A pipeline's top-level `description` used to be a **spec-orphaned passthrough**: the Catalog's create
dialog has a real form input for it and `PipelineEditable.lower()` preserved it end-to-end, but nothing
declared it and no surface rendered it — an operator typed it once and could never see it again.
(`ConfigLoader.validate` walks `spec.fields()`, never `raw.keySet()`, so an undeclared key is absorbed
silently rather than rejected. That is why this was invisible rather than broken.)

Now declared in `ConfigSpecs.pipeline()`, parsed onto `PipelineConfig.description()`, projected by
`GET /pipelines` in the same **conditional** style as `template`/`displayName` (absent ⇒ the payload is
byte-identical to before), and rendered as the row subtitle in the open-pipelines dialog with the
`text-secondary` class widgets/connections/spaces already use.

⛔ **Display only** — no engine code reads it. ⚠ Still only **settable at creation**: the pipeline
settings dialog is a hand-built form, not spec-driven, so the new `FieldSpec` does not generate a
control there. Editing an existing pipeline's description needs a field added to that dialog.

## The parse slot is claimed, not queued (BUILDER-2, 2026-08-17)

A flat pipeline config has **one `parsing:` block**. Every new pipeline lifts with a *generic* `parser`
placeholder nobody authored — so a builder's natural first move, "I want CSV, I'll click Delimited",
used to add a **second** parse node whose only possible outcome was `MULTI_PARSER` at Save, reported
against an internal node id.

`claimParseSlot()` (in the editor) is the rule, and it runs before either palette entry point:

- **The untouched placeholder is RE-TYPED in place** — same id, both edges intact, so the graph stays a
  linear recipe and the Recipe view never falls back to Canvas. The visual kind is PARSE either way, so
  the canvas node needs no rebuild. A placeholder display name that is just the generic label follows
  the new type; an authored name is kept.
- **A parse Step carrying config is authored work and the add is REFUSED**, naming the format that holds
  the slot and pointing at the drawer that can change it — never a node that could not be saved.
- The predicate is `isParseNodeType` (`pipeline-parse-definition.component.ts`), which is the generic
  `parser` **plus** every `PARSE_NODE_FRONTENDS` subtype. ⛔ Do not key this on the node-type CATEGORY:
  the rule must hold before the catalog resolves, and it is about the config format, not the palette.

⛔ This is **not** auto-connecting a palette Step. An ordinary Step still lands unconnected on purpose —
that was considered and declined, because it changes authoring semantics.

## Apply is armed by an edit, and every pane has to say so (BUILDER-2, 2026-08-17)

The definition drawer's Apply is gated on `definitionDirty()`, which each pane feeds through
`dirtyChange`. The panes derive dirty **on interaction rather than streaming it**, and that convention
has now failed twice in the same way:

- the Parse drawer did not count a *derived* output schema (BUILDER-1a), and
- the Load drawer counted **no mapping edit at all** — it emitted only from its node effect and from
  `submit()`, so authoring rules and pressing Apply left the Map Step "Needs config" for ever.

The Load pane now emits on `form.valueChanges`. `dirty` is a form flag, so the programmatic seeding in
`seedRules` (which ends `markAsPristine`) cannot arm it spuriously. 🔴 **A spec for this must drive the
DOM**: `setValue` does not mark a control dirty, so a programmatic test passes against the broken build.

**A derived artifact can outlive what derived it.** The output-schema grid keeps the columns of the last
parse that WORKED, so a failed re-test leaves them describing a grammar the pane no longer holds.
`schemaStale` says so in the grid; Apply deliberately stays live, because blocking it is the dead end
BUILDER-1a closed.

## The captured sample reaches the dry-run too, and a result cannot outlive its pipeline (BUILDER-2)

The sample strip promises the captured sample "follows you through the definition, so every test shows
your data". The dry-run was the one test that broke it, asking for hand-typed JSON — it now offers
**"Use the captured sample (N rows)"** from the tab's thread (`parsePreview().rows`), passed in as an
input because the thread belongs to the TAB.

⚠ The panel is mounted **once** and outlives the tab switch, so its outcome is **stamped with the
pipeline id it came from** and `result`/`error` are computed against the current one. ⛔ Do not replace
that with an effect that clears on id change: an effect flushes on Angular's schedule and can wipe a
result the operator just produced.

## Icons are heroicons SVGs — a ligature renders as words (BUILDER-2, 2026-08-17)

The app registers heroicons/feather/material **SVG sprites** and **no Material icon font**, so
`<mat-icon>add</mat-icon>` renders the literal text `add`. Five such ligatures shipped unnoticed (four in
the segments editor, one on every fixed-width slice row) because no unit test reads icon glyphs. Always
`svgIcon="heroicons_outline:…"`. It is only visible by looking at the page.

## Fixed width: `start` is 0-based, and the header default eats a line (BUILDER-2, 2026-08-17)

Two things a builder cannot discover from the form, both grounded in the engine:

- **`start` is a 0-based character offset** — `FixedWidthRecordIngester` requires `start >= 0` and slices
  `new String(buf, start, len)`. A builder counting columns from 1 gets **every field shifted one
  character**, which parses "successfully" and is visible only in the preview values. The slice table now
  says so above the rows.
- ⚠ **`delimited.has_header` defaults `true` for the `fixedwidth` frontend too** (`BuiltinParsers`,
  described there as "header/banner line to skip before the records"), so a 3-line fixed-width sample
  previews **2 rows** and nothing says a line was skipped. Recorded, deliberately **not** changed: it is a
  server-published spec default that existing configs rely on, so flipping it is a migration, not a UI fix.

## A node dialog tests the config it is editing (2026-08-17)

`POST /components/{transform|sink}/preview` runs an **inline** config — one carried in the request body
rather than looked up in the registry — so the node dialog's **"Test this Step"** tries exactly what the
operator is in the middle of writing, over the tab's own parsed rows.

Three things make this more than a button:

- 🔴 **The gate is the node's own TYPE** (`testFamily()`), ⛔ never `data.bindKind`. `bindKind` is
  `bindKindFor(category)`, which is `'grammar'` for PARSE and **null for everything else** — and
  `openNodeConfig` routes PARSE to the grammar editor, so every node that reaches this dialog had a null
  bindKind. That is exactly why the predecessor affordance ("Test <component>…", shipped 2026-08-14) was
  unreachable dead UI: its gate could never be true in production while its specs passed by constructing
  the dialog with a `bindKind` directly.
- 🔴 **The body is assembled through the same `buildConfiguredNode` the save path uses**, so the test runs
  the config that would actually be written — nesting, free-form rows and all. A simpler second merge here
  would drift and test a config that never ships.
- The rows arrive as `NodeConfigData.sampleRows` — plain rows, not the thread. The dialog reads and never
  writes them, and the thread belongs to the TAB.

⚠ The transform arm must send the node's `type` **inside** `config`, or the route 422s
(`'type: transform.*' required`). `grammar` has a server arm but deliberately no mock arm and no UI caller.

⚠ **The offline mock rehearses the REFUSALS, not the outcome.** 400 on a missing `config`, 400 on an empty
sample and 422 on a non-`transform.*` type are decisions an operator must meet either way; the *result* it
cannot reproduce, because it has no SQL engine — a predicate is not evaluated offline, so a filter reports
every sample row through. ⛔ Do not close that gap with a second transform evaluator in the mock.

## The offline `json` preview honours the document shape (2026-08-17)

`json.format` is a real published enum (`BuiltinParsers`: `newline | array | auto`). The mock's `json` arm
used to ignore it and always read NDJSON, which made the offline preview **misleading rather than merely
limited**: a JSON *array* document with the correct "One JSON array of records" setting reported 1 row and
3 rejected, so a builder would blame their file. It now reads the array shape, tries it first under `auto`,
and 422s an NDJSON sample under `array`. The rule this restores is the mock's own: **stricter than the
server, never more lenient — and never differently right.**

## A route branch's destination is its IDENTITY, not decoration (2026-08-17)

`database` is the **branch↔sink join key on both halves of the lift/lower round trip**, because edges do
not survive the flat file:

- lowering stamps every branch entry with the database of the node its `route:<key>` edge feeds
  (`routeSection`), and skips a branch whose target declares none;
- the plural `sinks:` block is keyed by **distinct** database, so a destination-less sink contributes
  nothing and `sinks:` is not even emitted;
- the lift rebuilds branch→sink pairing from that stamped value.

🔴 So `addRouteBranch` creating its companion sink with an **empty config** was silent DATA LOSS: the
branch lowered to nothing, the save reported success and cleared the dirty flag, and reopening the
pipeline showed the branch's destination gone and its entry dangling. `branchDestination()` now derives it
beside the primary sink's own home (`<primary home>/<branchKey>/database`), falling back to
`pipelineScaffold`'s `data/<name>` convention when no sink declares one yet. ⚠ Quarantine sinks are
excluded by the `typeof database === 'string'` test — they carry only `dir`.

⛔ **Do not "simplify" this by leaving the sink blank and validating later.** "Needs configuration" on the
new node looks like the same state as any unconfigured Step, and it is not: this one cannot be saved at
all, and says nothing about why.

🔴 **A round-trip spec for the route existed and passed the whole time** — it set `database` on the new
branch sink itself, supplying by hand the one thing the editor never supplied. **A round-trip test that
configures the artifact under test is not a test of the surface that creates it.** The replacement omits
the manual step and was probe-verified to fail against the old reducer.
