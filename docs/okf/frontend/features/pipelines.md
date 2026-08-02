---
type: Feature
title: Pipelines (Authoring)
description: The NiFi-style Pipeline graph editor (AntV G6) with node config, a rich parser-config dialog, and per-processor test.
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

A rich **parser-config dialog** configures PARSE nodes across 9 formats (ASN.1 · DSV · HTML · JSON · Other ·
Parquet · TXT · XLSX · XML) with a typed property sheet and ag-Grid/tree test output; the DSV property set
mirrors the backend `CsvSettings`. Parsers persist as reusable `grammar`
[components](components.md). Backed by `PipelinesService` / `ComponentsService`; offline via the
`mockFlows`-gated handler of the unified [mock backend](../conventions/mock-backends.md).

## Nothing loads until you open it; View is the editor with `readOnly` (2026-08-02)

**One component serves both modes.** `PipelinesComponent` is a thin host that renders
`PipelineEditorComponent` with `[readOnly]="mode() === 'combined'"`. View is not a separate page — same
toolbar, tab strip, palette, canvas and docks — so switching to Edit never means relearning the screen.
`canAuthor()` = authoring lens **AND** not read-only, and every canvas mutation path checks it, so the
gate is defence-in-depth rather than hidden buttons.

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

⚠ **Removed:** the combined "one or many pipelines joined at their shared stores" rendering, with its
synthetic STORE nodes and the store-aware node inspector. Multi-pipeline is now *tabs*, not one merged
canvas — cross-pipeline store links are no longer visualised here. `graph-view.component.ts` itself is
untouched and still serves Catalog, Link Analysis and Objects.

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
`node-attributes.ts`, keyed by those engine types. `acquisition` **reuses the shared
`COLLECTOR_ATTRIBUTES`** (`inspecto/component-model/`) — the same table Onboarding's Collection stage
uses, because two hand-written tables for one `collector:` block is precisely how this feature drifted into
keys the engine never read (`recursive` as a boolean, `min_age_seconds`; the real ones are
`recursive_depth` and a nested `stability.window`). Sinks are specced only for the `output:` keys the
backend reads (`format`, `compression`); the remaining `transform.*` types are **deliberately unspecced**
rather than guessed. Types without a schema fall back to the free-form key/value editor ("Additional
config", collapsed when a schema exists) — the conversion is non-lossy by design. Declared defaults **persist on save** even when untouched
(product-confirmed 2026-07-02: configs stay explicit/self-documenting).
