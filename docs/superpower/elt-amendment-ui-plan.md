# ELT Amendment — unified UI plan

**Status: v1.0 (2026-08-05) — companion to [`elt-final-amendment-plan.md`](elt-final-amendment-plan.md)
(APPROVED). This plan owns every UI change the amendment implies; slices land mostly in the main
plan's Phase 5, with S1–S3 unblocked earlier. Implementation must apply the `angular-ui` skill; this
document does not repeat its rules, only the amendment-specific calls.**

> **Doctrine (operator, recorded in `branch-aware-executor-plan.md` §3):** *"the execution model is
> separate from the UI abstraction."* The UI ships the seven-verb recipe abstraction regardless of
> which engine phases have landed — the recipe editor is a **view over the existing
> `AuthoredPipeline` model**, not a second model, exactly as the canvas is today (W5: an editor
> *over* the canonical `*_pipeline.toon`, `GET /pipelines/{name}/graph/raw` → `PUT /pipelines/{name}/graph`).

---

## 1. The one design call everything hangs on

**The recipe editor is a second PROJECTION of the same `AuthoredPipeline` signal model the canvas
edits — same reducers (`pipeline-graph.ts`), same load/save path, same refusals surface.** A linear
chain (tree once `route` branches) is detected from the graph (`data`-edge walk from the entry node);
the recipe view renders it as ordered Step cards. Editing a card patches the same model
(`applyNodePatchInModel`); Save is the unchanged `PUT /pipelines/{name}/graph`. The canvas remains
mounted behind a mode toggle for graphs the recipe cannot express (fan-in / `merge`, exotic control
edges) — **if the loaded graph is not recipe-expressible, the editor opens in Canvas mode with an
`<inspecto-alert variant=info>` saying why.** No dual model, no migration risk, no divergence.

## 2. Surface-by-surface changes

### 2.1 Pipelines editor (`modules/admin/pipelines/`) — the recipe view

- **Mode toggle** `Recipe | Canvas` in the editor header (Recipe default when expressible). Persist
  per device (`localStorage`, mirror the lens/space pattern — a UI preference, not config).
- **Step cards**: one card per Step in chain order — verb icon + user `name`/`description` + a
  compact config summary + status chip (live overlay reuses the canvas' `OverlaySource` data).
  Card click opens the **existing dialogs unchanged**: parse → `GrammarEditorDialog`; collect →
  `NodeConfigDialog` embedding `<inspecto-collector-config>`; everything else → `NodeConfigDialog`
  (schema-form over served `AttributeSpec[]`, fallback `node-attributes.ts`). **No new dialog kinds.**
- **Add Step**: an insertion affordance between cards offering the **seven verbs** (+ discovered
  plugin Steps); inserting splices the node + `data` edges in the model. Until the server serves
  `step-types`, the palette maps the 7 verbs onto the existing lowerable `BuiltinNodeType` ids
  client-side (`collect→acquisition`, `map→transform.map`, `dedup→transform.dedup` *(record; the
  marker kind stays internal)*, `sink→sink.persistent`, …) — one table in `pipeline-graph.ts`, deleted
  when S4 lands.
- **Guarantees panel**: a fixed checklist panel (right dock or below the chain), rendering
  `file_dedup` / `backup` / `quarantine` / `markers` / `gap_watch` / `retention` from the config
  keys that exist today (`collector.duplicate`, `processing.duplicate_check`, `dirs.*`,
  `collector.gap_detection`). **Never draggable, never cards** — Guarantees are not Steps (GLOSSARY).
  Until the backend `guarantees:` block lands (main plan Phase 4), the panel is a *projection over
  the legacy keys*, writing them back in place — the same verbatim-sections rule the node dialogs
  follow.
- **Route branches (amendment §2.6)**: a `route` Step card expands to named branch tabs/sections,
  each holding its own nested Step-card list + `when` predicate + the `case|clone` mode toggle +
  one `default` flag. Rejects are **not** shown as wiring — a small counter chip per card surfaces
  reject counts from dry-run/provenance instead.
- **Dirty/close guard**: `guardDirtyClose` on every dialog (already the rule); the editor's own
  unsaved-state banner stays.
- **Per-Step pause (D-13, Phase-4-backend-gated — extends S7)**: each Step card gets an
  enable/disable toggle (`enabled:`) with the park-at-boundary model surfaced honestly: a disabled
  Step shows a "parked Consignments" count chip (from the Stage-C stage progression), and re-enabling
  shows the drain. Gate the toggle on `canOperateRuns()` (it is an operational action, not config
  authoring — the Runs precedent). Never present pause as instant/queue-like: the tooltip states
  that in-flight Consignments finish their current Step and park.

### 2.2 Canvas demotion

Canvas keeps: full topology rendering, live overlay, the future provenance Sankey, fan-in authoring,
control-edge visualization. It loses: being the default. Nothing is deleted. The `/design` gallery
gains the recipe/step-card pattern when S1 lands (DoD rule).

### 2.3 Palette / step-types (S4, server-gated)

`GET /pipelines/step-types` (main plan §5) replaces `node-types` for the palette: 7 verbs + plugins,
each with served `AttributeSpec[]` covering **all** verbs. UI dual-reads (`step-types`, fall back to
`node-types` + the client verb map) during rollout, mirroring how `typeAttributes` already tolerates
an old server. Mock: `pipelines.handler` serves both; **the mock must refuse exactly what the server
refuses** (the mock-strictness rule) — its lift/lower and the contract JSON
(`node-attributes.contract.json` pattern) extend to step-types.

### 2.4 Schema & Mapping CSV editors (S5, Phase-1-backend-gated)

Two flat-table component kinds (structure CSV, mapping CSV) get **grid-based editors**, not TOON
text: an editable `<inspecto-data-table>`-hosted grid (requires registering ag-Grid's editing
community module — add to the explicit 12-module set, never `AllCommunityModule`), with add/remove
row, CSV import/export via the `HttpClient` blob pattern, and **cell-level findings** rendered
inline: the `BACKWARD` compatibility gate's refusals (rename/delete/narrow/selector-move) come back
as findings naming row+column — surface them on the cells and in a summary `<inspecto-alert
variant=error>`, exactly as `/config/write` findings surface today. Schema editor and Mapping editor
are two thin hosts over one shared editable-grid component (`inspecto/` shared, since Onboarding's
mapping stage will adopt it too).

### 2.5 Pipeline Document (S6, Phase-5-backend-gated)

- **Export**: an "Export document" action on the pipeline list row + editor header →
  `GET /pipelines/{id}/document` (Markdown, blob download). Show the config fingerprint in the
  confirmation toast.
- **Import loop**: an "Import mapping changes" action on the Mapping editor → file upload →
  server validate → findings panel (reuse the S5 cell-findings rendering) → **dry-run diff
  preview**: old vs new output rows side-by-side in a standard-tier data-table → Apply writes via
  the normal component save route. The human stays the actor (the ai-assist rule generalized: the
  surface has no write path of its own beyond the standard validated route).

### 2.6 Table-entry Pipelines (S7, Phase-3-backend-gated)

The `collect` card gets a **source mode toggle** `Files (Connection) | Table` — the exact
local/Connection toggle precedent from `<inspecto-collector-config>`, including its two traps
(spec-swap re-seeds live values; un-bind via the toggle, never by blanking). Table mode asks:
`table` (autocomplete via `entity-option-loaders` over Datasets — never free text) and trigger
(`on: commit of <pipeline>` — pipeline autocomplete; or cron). The `summarize` card renders
group-by (list type) + measures (list of expressions). Jobs-pane retirement is **Phase 6 and a
nav change: three edits** (nav item, route, `ACCESS_ACTION_NODES` re-home) — not before the
converter has migrated `*_job.toon` data kinds.

### 2.7 Vocabulary sweep (S3, unblocked now)

UI copy only: "node" → "Step" across the pipelines editor (palette header "Shapes" → "Steps",
dialog titles, empty states, refusal messages), per GLOSSARY §13 row. Internal identifiers
(`PipelineNode`, `node-attributes.ts`, routes) are KEPT — the §13 row scopes the rename to copy;
`/pipelines/node-types` renames only when S4 introduces `step-types` beside it. The `dedup` verb's
copy must respect the boundary: the record Step is "Dedup"; the Guarantee checkbox is "File dedup".

## 3. What is deliberately reused, not built

`<inspecto-schema-form>` (all card forms) · `GrammarEditorDialog` · `<inspecto-collector-config>` ·
`<inspecto-enrichment-editor>` (until Phase 3 folds enrichment into `transform`, its dialog remains
the enrichment Step's editor) · `<inspecto-data-table>` (grids, dry-run previews, document diff) ·
`<inspecto-alert>` / `<inspecto-status-badge>` / `<inspecto-chip>` (branch tags, guarantee states,
reject counters) · `[inspectoDialogResize]` (all 7 pipeline dialogs already adopted) ·
`[inspectoSplit]` (guarantees dock) · dirty-close guard · lens gating via `canAuthorWorkbench()`
(mutating methods AND buttons) · the command registry (`New pipeline` palette command exists;
recipe editor changes nothing there).

## 4. Slices & gates (each = the full angular-ui Definition of Done)

| Slice | Delivers | Gated on backend? | Extra gate |
|---|---|---|---|
| **S1** | Recipe view read-only: chain detection, Step cards, mode toggle, non-expressible fallback alert | no | axe on card list; expressibility detector unit-tested against lifted fixtures incl. route/multi-sink |

> **S1 SHIPPED 2026-08-06.** `detectStepChain`/`flattenStepChain` (pure, in `pipeline-graph.ts` — the
> testable seam, per §5's G6-in-jsdom rule): single-entry `data`-edge walk, `route:*` fan-out becomes
> nested `StepBranch`es (predicate/default read from the route node's own config), and `null` — the
> Canvas-fallback signal — for fan-in, multiple entries, or mixed fan-out. `PipelineStepCardsComponent`
> (presentational: rows + typeCat + optional statusOf in, nothing out yet) renders the indented card
> list with the shared `<inspecto-chip>` for config summaries; the editor gained the `Recipe | Canvas`
> toggle (Recipe default; preference persisted at `inspecto.pipelines.viewMode` — imperative write in
> `setViewMode`, NOT a signal `effect()`, which never fires without `detectChanges()` in specs and lag
> behind in production too), `effectiveMode` (a non-expressible graph forces Canvas without touching
> the preference) and the `forcedToCanvas` info alert. Tests: chain detection (7), step-cards
> component (4, incl. axe), editor recipe-view signals (4). lint:tokens + build + test:ci green
> (2070 passed, exit 0). Three traps hit and worth carrying: the chip component's export name is
> `ChipComponent` (selector `inspecto-chip`); a template binding needs the editor's `typeCat` signal
> public; and an OnPush spec must flip inputs via `fixture.componentRef.setInput`, never by assigning
> the field.
| **S2** | Recipe editing: card dialogs wired, add/remove/reorder Step, Guarantees panel (legacy-key projection) | no | round-trip: recipe edit → save → `graph/raw` reload byte-stable on untouched sections |
| **S3** | Route branch UI + "Step" vocabulary sweep | no | branch add/remove/default rules match `PipelineValidator` refusals (mock parity spec) |
| **S4** | `step-types` palette + 7-verb specs, dual-read fallback | main plan Phase 5 endpoint | contract JSON byte-compare (extend `NodeAttributesContractTest` pattern) |
| **S5** | Schema/Mapping grid editors + compatibility findings | Phase 1 gate + CSV kinds | ag-Grid editing module registered explicitly; cell-findings a11y (`role=alert` summary) |
| **S6** | Pipeline Document export + mapping import loop | Phase 5 generator | diff preview renders the dry-run sample; fingerprint shown |
| **S7** | Table-entry collect + summarize cards; Jobs nav retirement | Phase 3 / Phase 6 | nav retirement = 3 edits incl. `ACCESS_ACTION_NODES`; `access-catalog.spec` green |

## 5. Known traps to carry (from the skill, amendment-specific)

- **Never read a shared component via `@ViewChild` in a template** — take `@Output`s (bit the
  grammar editor already).
- **Spec-list swap wipes live values** — the collect card's mode toggle must re-seed (collector-config
  precedent).
- **`@defer` + TestBed** — any CodeMirror/CSV-preview deferral needs `await TestBed.compileComponents()`.
- **Full-bleed editor height** — the recipe view lives inside the same `calc(100dvh - 120px)` bound
  shell the canvas learned to need; docks must shrink the chain list, not grow the page.
- **Mock is never more lenient than the server** — every new handler (step-types, mapping CRUD,
  document, import validate) diffs args AND result keys against the real route, pinned in a
  `*.handler.spec.ts`.
- **G6 in jsdom** — recipe-view specs don't mount the canvas; expressibility logic is a pure
  function in `pipeline-graph.ts` (the testable seam).
