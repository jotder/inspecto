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

**`GrammarEditorDialog`** (`grammar-editor.dialog.ts`, renamed from `ParserConfigDialog` 2026-08-04)
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
`GET /pipelines/node-types` actually serves: `acquisition`/`adapter` (SOURCE), `parser` (PARSE), the
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

### Two editor test affordances are mock-only (2026-08-02)

*Run to here* (inspector) and *Test processor* (node-config dialog) are gated behind
`environment.mockFlows`, because both 404 against a real backend — for **different reasons**:

- *Run to here* — `POST /pipelines/authored/{id}/run?to=` is reserved-but-unregistered
  (`PipelineRoutes.java:69`), deliberately: it must never fire a production run. Genuinely absent.
- *Test processor* — the route **does** exist
  (`ComponentRoutes.java:42-44`, `POST /components/{transform|grammar|sink}/{id}/test`). The dialog
  simply addresses it wrongly, sending the node's dotted type (`transform.filter`) and node id
  (`filter_1`) where the route wants the literal family segment and a **registered component name**.

⚠ Don't collapse those two into "no backend". The second is a plausible repoint — map dotted type to
family, pass the node's registry ref — with one catch that makes it more than a URL change: a node
carrying inline config binds no registered component, so there is nothing to look up.

Both are kept rather than deleted because `run-to-here.dialog.ts` is the finished UI for the still-open
"test against real data" work — see [`../../../BACKLOG.md`](../../../BACKLOG.md).

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
