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
group-by (list type) + measures (list of expressions). ⚠ **Jobs-pane retirement is CANCELLED**
(2026-08-06, operator decision — user-facing 'Job' un-banned, see `docs/GLOSSARY.md` §6-A and
`docs/superpower/job-parameter-contract-plan.md` §0-A); table-entry cards ship as an *additive*
surface alongside Jobs, never a replacement for it. The retirement was originally **Phase 6 and a
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

> **S2 SHIPPED 2026-08-06.** Card click/Configure routes to the EXISTING dialogs through the host's
> `openNodeConfig` (parse → GrammarEditorDialog automatically — no new dialog kinds, per §2.1); the
> Add-Step "+" between trunk cards offers `RECIPE_VERBS` (the 7-verb → lowerable-node-type table in
> `pipeline-graph.ts`, deleted when S4's `step-types` lands; `route` deliberately absent until S3's
> branch UI); remove/move are pure reducers (`insertStepAfter`/`removeStepFromChain`/
> `moveStepInChain`) that return `null` rather than guess when a node is wired beyond the linear
> trunk — the host toasts toward the Canvas. **Trunk-only editing is the S2 scope**; branch-row cards
> configure but don't splice. The Guarantees panel is a fixed checklist (never draggable) projecting
> file dedup / gap watch / markers / quarantine / backup out of the keys the LIFTED GRAPH carries
> (acquisition `duplicate`, the `gap` node, the marker node, the quarantine sink, sink `backup`);
> Edit opens the OWNING node's existing dialog, so the panel has no write path of its own. Keys the
> graph doesn't model (`processing.retention_days`, a node-less `dirs.quarantine`) are preserved by
> lower's ownership rules and deliberately NOT shown — a value the panel can't edit would lie about
> what Edit does. **Grounding correction ridden in:** the server/mock lift hangs gap watch off the
> acquisition node via a `gap`-rel edge, so S1's detector forced Canvas for ANY pipeline with gap
> detection — contradicting §2.4 (gap watch is a Guarantee, not a Step). `detectStepChain` and
> `insertStepAfter` now tolerate `gap`/`unmatched` side-edges to leaf nodes as housekeeping
> attachments; a genuinely exotic control edge (e.g. `failure`) still forces Canvas. Gate held:
> the round-trip test drives insertStepAfter → the mock `lowerGraph` (which mirrors the server) →
> re-`liftConfig`, asserting untouched sections (`collector`, `parsing`, `dirs.quarantine`)
> byte-stable while the edit lands. lint:tokens + build + test:ci green (2087 passed, exit 0).
| **S3** | Route branch UI + "Step" vocabulary sweep | no | branch add/remove/default rules match `PipelineValidator` refusals (mock parity spec) |

> **S3 SHIPPED 2026-08-06.** Grounding first corrected the gate's own premise: `PipelineValidator`
> has NO per-branch rules (its only route check is `ILLEGAL_EMIT` — a `route:*` edge must leave a
> node with `emitsNamedRoutes`); the real branch-shape rules live in `RecipeCompiler.route()`
> (`MALFORMED_STEP` for a missing/empty branches map, `UNSUPPORTED_STEP` for anything but exactly
> one sink per branch) and `RowShaper` at runtime. Notably NO duplicate-branch-key or
> exactly-one-default rule exists anywhere server-side — the recipe's branches *map* would silently
> collapse duplicates, so the UI refuses duplicates itself, and default is zero-or-one via the
> scalar `default` key (structurally at-most-one). Branch editing = pure reducers in
> `pipeline-graph.ts` (`addRouteBranch` — entry + an unconfigured sink per RecipeCompiler's
> one-sink-per-branch; `removeRouteBranch` — subtree removal, refusing outside fan-in;
> `setRouteBranchWhere`/`setRouteDefault`; `insertRouteAfter` — a route splices by rewiring its
> downstream edge as its FIRST branch, so it refuses at the tail); `route` joined `RECIPE_VERBS`.
> Cards: `case|clone` mode toggle + add-branch draft on the route card, inline `when` input +
> default star + remove on branch rows (trunk-depth only), all host-applied behind `canAuthor()`.
> **Mock parity (the gate):** the mock was STALE-STRICT — the server has lowered `transform.route`
> since backend route lowering shipped, but the mock still refused UNSUPPORTED_NODE and lifted
> neither `route:` nor plural `sinks:`. It now mirrors `PipelineEditable.routeSection` (branch
> `database` stamped from the sink its `route:<key>` edge feeds) and `PipelineLift`'s
> branch↔sink-by-database pairing, pinned by a lift→edit→lower→re-lift parity spec. ~~The same
> staleness for `transform.dedup`/`summarize`/`join` is a flagged follow-up, not S3 scope.~~
> **Follow-up CLOSED 2026-08-06.** The staleness was in the mock's `NODE_TYPES` *catalog*, not its
> lift/lower (which already handled all three): `pipelines.handler.ts` held a **second, stale copy**
> of the lowerable set — 8 entries against the server's 12 — so `transform.dedup`/`join`/`summarize`
> never reached the offline canvas palette. The duplicate is deleted; the handler now imports the
> one `LOWERABLE` from `mock/pipeline-editable.ts`, mirroring how the server has a single definition
> (`PipelineProjection.catalog()` reads `PipelineEditable.isLowerable`). ⚠ The tripwire spec did not
> catch this because it **hand-copied the enum into itself** — both copies went stale together, so it
> passed. Pinning a mirror against a re-typed literal is not a mirror; the spec now asserts 22 types
> / 12 lowerable, but the durable lesson is that the contract-JSON idiom (S4) is the shape that
> actually holds.
> Vocab sweep (§2.7): editor canvas aria-labels, inspector hints/Delete, grammar dialog, dry-run
> panel, run-to-here, `validatePipeline` messages — the combined read-only topology view keeps
> "node" deliberately (its graph mixes Steps with synthetic store nodes). One stale spec pinned the
> old "Drag a processor" copy and was updated with the sweep.
| **S4** | `step-types` palette + 7-verb specs, dual-read fallback | main plan Phase 5 endpoint | contract JSON byte-compare (extend `NodeAttributesContractTest` pattern) |

> **S4 SHIPPED 2026-08-06** (backend endpoint + UI in one slice). Backend:
> `GET /pipelines/step-types` (`PipelineProjection.stepCatalog()`) serves the 8 palette entries
> (7 verbs + `route`) in pipeline order — verb, the node type it authors as, category/label/
> description, `lowerable`, and the type's served `AttributeSpec[]` — with plugin node types
> appended after, keyed by their own type. `NodeAttributes` gained the three missing verb specs
> (`transform.dedup` keys/order_by, `transform.summarize` group_by/measures, `transform.join`
> reference/on — all keys already proven by `NodeConfigNameContractTest`); `parse` and `map` stay
> deliberately spec-less (Grammar editor / mapping-CSV are their editors — pinned by
> `StepTypesContractTest.specsReachEveryVerbExceptTheDedicatedEditorSurfaces`). The catalog is
> pinned byte-wise to `step-types.contract.json` exactly like node-attributes (regenerate with
> `-Dstep.types.write=true`), and `node-attributes.contract.json` was regenerated for the three
> new types (TS table extended to match). UI: `PipelinesService.stepTypes()`; the editor
> dual-reads — served palette (lowerable entries only; an empty list is treated as "not served"),
> `RECIPE_VERBS` as the old-server fallback, which therefore STAYS (re-documented, not deleted) —
> and a spec pins the fallback map entry-by-entry against the served contract so neither
> vocabulary can drift alone. The cards take the verbs as an input. The mock serves the contract
> JSON verbatim. `transform.join`'s `reference` renders as an autocomplete over the Catalog's
> References (`referenceOptionLoader`, new; note `MetadataNode` carries `label`, not `name`).
> **Bug fixed en route:** `PipelineEditable.lower` fabricated an empty `output={}` on files with
> no `output:` block (`getOrNew` speculation) — caught by the fixture round-trip gate over a live
> scratch pipeline; fixed server-side + mock mirror.
| **S5** | Schema/Mapping grid editors + compatibility findings | Phase 1 gate + CSV kinds | ag-Grid editing module registered explicitly; cell-findings a11y (`role=alert` summary) |

> **S5 GROUNDED 2026-08-06 — two premise corrections.** (1) §2.4's "two flat-table CSV kinds" is
> half right: only `mapping` is CSV-backed on disk (`ComponentRegistry.CSV_KINDS`, canonical header
> `targetColumn,sourceExpression,transformType` via `MappingCsv`); a `schema` component is TOON.
> Either way the WIRE is JSON, never raw CSV: a mapping component's content is
> `{rules: [{targetColumn, sourceExpression, transformType}]}` through the generic
> `/components/{type}` CRUD (both kinds are in `ComponentStore.WRITABLE_TYPES`; the UI's
> `ComponentType` union has neither yet). (2) The BACKWARD gate (`SchemaCompatibility`) fires ONLY
> on `/config/write` + `/config/patch` for `type=schema` — generic component CRUD bypasses it — and
> findings anchor by dotted `fieldPath` (`raw.fields[NAME]`, `.type`, `.selector`; shape
> `{severity, fieldPath, message}`, HTTP 422 with `written:false` on ERROR, warnings ride the 200),
> NOT by row/column as §2.4 assumed. Consequence: the **schema** grid editor must save through
> `/config/write type=schema` (the gated path; `compatibility:"none"` is the deliberate override),
> while the **mapping** grid editor saves through `PUT /components/mapping/{id}` (rules[] JSON) —
> and fieldPath maps onto grid cells by field NAME, which the schema grid keys rows on anyway.

> **S5a SHIPPED 2026-08-06.** Shared `<inspecto-editable-grid>` (`inspecto/components/`) — ag-Grid
> spreadsheet-lite with add/remove row, text/select in-cell editors (`TextEditorModule` /
> `SelectEditorModule` / `CellStyleModule` registered explicitly, still no `AllCommunityModule`),
> CSV import/export (minimal RFC-4180 `parseCsv`), and cell findings keyed `"<rowIndex>|<colKey>"`
> rendered as inset `var(--gamma-warn)` box-shadows + tooltips (never a background fill; the
> `role=alert` summary is HOST-owned). First adopter: `MappingEditorDialog` — the mapping kind now
> opens a grid over `rules[{targetColumn, sourceExpression, transformType}]` (transformType select =
> TransformCompiler's DIRECT/EXPR/CONCAT_DT/FILENAME_DATE), saves via generic component CRUD with
> non-rules content keys preserved verbatim, refuses an empty rule list, drops all-blank rows.
> `ComponentType` union gained `schema`/`mapping`; `mapping` joined `COMPONENT_TYPES` (page section).
> Traps hit: specs need the `InspectoGridThemeService` stub (real one walks to `GAMMA_APP_CONFIG`);
> a hidden `<input type=file>` still needs an `aria-label` or axe fails the whole spec. S5b (schema
> grid over the gated `/config/write`) is next.

> **S5b SHIPPED 2026-08-06 — S5 CLOSED.** `SchemaEditorDialog`: `raw.fields[]`
> (name/selector/type/description/unit/classification) as a grid; type stays FREE-TEXT (the server
> enforces no enum, only the widening lattice on edit — a select would be stricter than the
> server). Saves through the gated `POST /config/write type=schema` with `overwrite:true`
> (`ConfigService.write` opts gained the typed `compatibility?: 'none'`); non-fields raw keys and
> the mapping section travel verbatim. A 422's `error.details.findings` (the pipeline-editor
> refusals idiom) translate onto cells by field NAME (`raw.fields[NAME]`(`.type`|`.selector`) →
> `"<rowIndex>|<colKey>"`; a removed field has no row and rides the summary only), rendered in a
> `role=alert` `<inspecto-alert>`; the refusal arms "Save anyway (skip compatibility check)" →
> confirmDestructive → re-send with `compatibility:"none"`. `schema` joined `COMPONENT_TYPES`.
> **Mock parity:** `schemaBackwardFindings` mirrors `SchemaCompatibility.check` exactly (same
> fieldPaths/messages; case-insensitive types, verbatim selectors) on both `/config/write`
> (override honored) and `/config/patch` (no override key, like the server) — and a split-brain
> was closed: the mock held registry schemas (`component:schema`) and config-file schemas
> (`schema-config`) in separate stores, so a demo-seeded schema bypassed the gate; writes now diff
> against and mirror into both. Pinned in `onboarding.handler.spec.ts` + `schema-editor.dialog.spec.ts`.
| **S6** | Pipeline Document export + mapping import loop | Phase 5 generator | diff preview renders the dry-run sample; fingerprint shown |

> **S6 GROUNDED 2026-08-06 — the premise was wrong: this is not a UI slice.** §2.5 is written as if the
> Phase 5 backend had landed. It has not — **all three** surfaces it calls are absent from the code:
> (1) no `GET /pipelines/{id}/document` and no document-generator class of any name; (2) no
> validate-without-write route for `mapping` (unlike `transform`/`grammar`/`sink`, which each have a
> `.../test`) — a bad mapping upload only fails as a side effect of `ComponentStore.encode`, surfacing
> as a bare 422 string, **not** the per-cell `findings[]` §2.5 cites as its precedent; (3)
> `POST /pipelines/authored/{id}/dry-run` exists but runs the **persisted** graph and takes no draft
> body, so it cannot produce "old vs new" anything. S6 therefore splits, and each half ships backend
> + UI together the way S4 did:
> **S6a** = document export (below). **S6b** = the import loop, which additionally needs a dry-run that
> accepts a *candidate* config — the real cost §2.5 hides.

> **S6a SHIPPED 2026-08-06.** `PipelineDocument` (inspecto-engine, pure — no I/O, no clock) renders a
> recipe as the §5.1 Markdown: header + fingerprint, a chain-overview table, one section per Step with
> §5.1's per-kind content, the `map` **field table** (mapping `rules[]` joined to schema `fields[]` by
> field name; an unmapped schema field still lists, so the reviewer sees the whole output shape),
> `route` branch tables + nested per-branch chains, then Guarantees and a referenced-components table.
> It renders the **recipe**, not the lowered graph — `RecipeConverter.toRecipe` already produces the
> ordered verb chain §5.1's contents table is written against, and it had **zero production callers**
> before this (only its own parity test), so S6a is its first live use.
> **Three deliberate calls.** (1) **No generation timestamp** — the document is bound to config by the
> fingerprint, and a clock would break both that binding and the determinism gate; a golden-file test
> (`-Dpipeline.document.write=true` to regenerate, the step-types/node-attributes idiom) pins the
> format byte-wise. (2) **Nothing is silently dropped** — every step key without a dedicated table
> still renders as a Setting row, because a sign-off document that quietly omits config is worse than
> none; secret-shaped keys mask instead. (3) **Sample rows per Step (§5.1's "worked examples") are NOT
> here** — they need a live dry-run, which is neither pure nor deterministic; they belong to S6b, which
> needs that seam anyway.
> **Route:** `GET /pipelines/{name}/document` → `text/markdown`, un-gated (a read; a Business-lens
> reviewer is exactly the audience). Fingerprint = `ContentHash.of({recipe, components})` on the
> `X-Config-Fingerprint` header — a Markdown blob has nowhere else to carry it — which needed
> `Access-Control-Expose-Headers` widened (it listed only `Correlation-ID`). Ref resolution mirrors
> `RecipeConverter`'s own registry-ref-vs-plain-path split: registry refs via `ComponentStore`, a legacy
> plain path **only when the pipeline's own `referencedFiles()` declares it** — so a document can never
> read a file the engine doesn't already parse for that pipeline. `connections/*` is deliberately never
> resolved (credentials). **Trap hit:** the first cut split any value containing `/` as `dir/name`, so
> the fixture's absolute `schema_file` (`C:/…`) resolved as component type `C:` and 500'd — caught only
> by the real-HTTP test, not by the unit tests.
> **UI:** "Export document" in the editor's overflow menu → blob download + a toast carrying the
> fingerprint's first 12 chars. `PipelinesService.document()` is the app's **only** `observe: 'response'`
> call (the header is the point); the v1 interceptor already passes blobs through untouched.
> A read failure deliberately does **not** go through `onWriteError`, which would wrongly latch the
> editor read-only. **`/document` is deliberately NOT mocked**, matching the existing blob-route
> precedent (`spaces` export isn't either): `MockResponse` carries no headers and v1-wraps every 2xx
> body, so mocking it would need shared mock-infra surgery — and not mocking cannot violate the
> mock-strictness rule, since a mock that doesn't exist can't be more lenient than the server.

> **S6b SHIPPED 2026-08-06 — scope narrowed by grounding.** Re-grounded before building: `POST /validate`
> already existed generically (schema-shaped, not mapping-shaped), and mapping rules are not a separate
> component type at all — they ride inside `type: "schema"`'s sibling CSV split. So S6b built the ONE
> piece that genuinely didn't exist — `POST /components/mapping/validate` — and deliberately dropped
> the dry-run-over-a-candidate-config half of §2.5's "diff preview": that needs a second new backend
> capability (accepting a draft config body, not the persisted graph) which is real, separable work,
> not wiring. What shipped instead is a **rule-level diff** (import vs. the rules it replaces, by target
> column) — genuinely useful for reviewing an import, but not §2.5's "old vs new output rows" data-level
> diff, which stays a follow-up.
> **The validation authority is `MappingRules` (inspecto-engine, pure)**, not a restated copy: its
> transform-type check reads `TransformCompiler.TRANSFORM_TYPES` (added to the compiler itself) so the
> vocabulary can never drift from what the compiler actually accepts. Every other check is a literal
> precondition of a `TransformCompiler` method — `CONCAT_DT` needs its `|` separator (the compiler splits
> unconditionally and throws without it), `FILENAME_DATE` only targets `EVENT_DATE` (the compiler
> rejects any other target) — so the validator fails the SAME rules the runtime would fail on, before a
> pipeline ever runs. **Deliberately not validated: an `EXPR` rule's SQL.** `TransformCompiler.expr`
> already documents that decision — schema config is operator-authored and trusted, the same model as
> Stage-2 transform SQL — so sandbox-checking it here would contradict an explicit prior call.
> **Fail-closed at both edges, not just the preview.** `POST /components/mapping/validate` never writes;
> separately, `ComponentRoutes.validateKind` (the same hook `findings-spec` already used) now refuses an
> invalid mapping on `POST`/`PUT /components/mapping/*` too — so the preview is advisory, but the write
> is the real gate, exactly the two-layer shape R6's config-safety gate uses elsewhere.
> **UI:** the shared `<inspecto-editable-grid>` gained a real `imported` output — Import CSV used to
> silently replace every row with blanks when the header matched nothing (indistinguishable from
> successfully importing an empty mapping); it now REFUSES that import and reports which headers
> matched/were unknown/were absent, matching by column key OR label (Export writes keys, a human editing
> the file sees labels). `MappingEditorDialog` renders that outcome as an `<inspecto-alert>`, shows the
> rule-diff table, validates server-side on every import AND before every save (blocking the save on an
> ERROR finding — a transport failure only warns, since the write re-checks regardless), and reuses the
> S5 cell-findings plumbing (`rules[N].<key>` is already row-anchored, simpler than the schema editor's
> by-name `raw.fields[NAME]` resolution). **The mock mirrors `MappingRules`/`validateKind` verbatim** on
> both the new validate route and the existing create/update routes, pinned in
> `components.handler.spec.ts`, so the offline preview cannot green-light a mapping the server 422s.
> **Verified live** against the real backend (not just tests): a create/update with an unknown transform
> type 422s before touching disk; clean rules write, list, and delete correctly.
> **S6 dry-run follow-up SHIPPED 2026-08-06 — `97139f24` + `bff141cc`.** Grounding this before building
> found the premise had a second, worse problem than S6b's own gap: `POST /pipelines/authored/{id}/dry-run`
> didn't just lack a candidate-body seam, it **was already broken for every registered pipeline**.
> `PipelineLift` carries a legacy schema on the `transform.map` node, but `RowShaper`'s projection required
> `columns` and nothing translated between them — any pipeline with a schema 400'd on dry-run (misattributed
> to "bad sample"). Separately, **no code path anywhere resolved a `use:` component reference before running a
> graph** — `ComponentRegistry.effectiveConfig` had zero production callers — so a `mapping` component's rules
> were invisible to the executor. Neither defect was in scope for S6b; both were fixed first (`97139f24`),
> verified with new tests (`PipelineDryRunTest` 4/4, `ComponentRegistryTest` 4/4), then the candidate-config
> body landed on that corrected foundation (`bff141cc`): a `pipeline` body key dry-runs a draft graph — parsed
> and validated through the same `parseAndValidateFlow` the save route uses, so an invalid draft 422s
> identically, and the draft never touches disk. `DataTransformer.dataColumns` is now the one authority both
> the legacy engine's `selectFor` and the graph executor's projection compile mapping rules through, so they
> cannot drift. Full reactor green throughout; vocabulary guard clean. **Still open, deliberately not done
> here:** wiring the UI's old-vs-new output-row diff and §5.1's per-Step sample rows onto this seam — the
> backend capability exists now, the UI call sites do not yet.
> **S6 UI wiring SHIPPED 2026-08-06 — `5d7706e0` + `6a2024c8`.** Grounding this found the seam did NOT
> in fact work for the case it was built for. `RowShaper.columnsOf` demanded a `PipelineConfig.CsvSettings`
> **record**, but `PipelineCodec` keeps a decoded node's config verbatim, so a JSON `csv` block is always a
> plain `Map` and the cast could never hold: **every** dry-run whose `transform.map` carried rules 400'd —
> a candidate body *and* a `GET …/graph/raw` round-trip, i.e. any pipeline with a mapping. `bff141cc`'s test
> only used `transform.filter`, so it never touched the path. Fixed in `5d7706e0` (`csvSettingsOf` resolves
> record | map | absent; `csv` is read for exactly `dateFormats`/`tsFormats`, so absent degrades to
> `TRY_CAST` instead of failing), covered at engine and route level.
> **Two design calls the plan did not anticipate.** (1) §2.5 assumed a host pipeline ("with and without the
> `pipeline` key"), but `MappingEditorDialog` is opened only from the Components pane and a mapping has
> **0..N** referencing pipelines — so each side is a **synthesized throwaway graph** (seed → `transform.map`
> → sink) with the draft rules INLINE; a `use:` ref would resolve the *stored* mapping and miss the draft.
> (2) Dry-run always needs `sampleRows` and the plan never said where they come from — the operator
> **uploads a sample data CSV** (operator decision 2026-08-06), and with no sample the rule-level diff
> remains the whole review, so nothing regresses.
> **The mock was the real risk**: it ignored the candidate body AND `sampleRows`, answering with two canned
> rows per node — an old-vs-new diff on it would have shown identical rows on both sides *forever* while the
> offline preview looked correct. It now walks the graph and projects DIRECT rules; **EXPR yields `null`, never
> a fabricated value** (`MockHandler` is synchronous, so the data-table's lazy AlaSQL is unreachable), and it
> reports `rel: data` — it said `success`, a name no dry-run response has ever carried.
> ⚠ **Still open:** §5.1's per-Step sample rows are **NOT** a UI task — `PipelineDocument.render` is pure by
> design and S6a excluded them deliberately (a live dry-run is neither pure nor deterministic, and a
> byte-wise golden-file test pins the format). Adding them is a backend change that trades away both.
| **S7** | Table-entry collect + summarize cards | Phase 3 | **SPLIT 2026-08-06** — summarize half SHIPPED (below), its `group_by` follow-up SHIPPED 2026-08-08; table-entry half remains S3-blocked. Jobs nav retirement CANCELLED 2026-08-06 (Job un-banned) — no `ACCESS_ACTION_NODES`/nav removal |

#### S7 SPLIT 2026-08-06 — the summarize half shipped; table-entry `collect` is genuinely blocked

Grounding S7 before building found the slice is **two independent halves with different gates**, and
only one of them can be built. Recorded here so the next shift does not re-derive it.

**Table-entry `collect` — NOT BUILT, and it should not be forced.** Its gate is the *deferred* Phase 3
S3 of the amendment plan, which has a design of record but no implementation. Three independent
blockers, all verified in source:
1. No Signal a Dataset write publishes and no `collect` variant to bind to one — the design exists
   (`elt-final-amendment-plan.md` §"Phase 3 S3 DESIGN"), the machinery does not.
2. `PipelineConfigParser.java:129-130` requires `dirs.poll`/`dirs.database` **at parse time**. Unlike
   `route`/`summarize`/`join` — which parse fine on an inactive draft and are refused only at arming —
   a table-sourced draft would **fail to load at all**.
3. There is no `table` mode in the published catalog and no such `CollectorMode`
   (`collector-config.component.ts:17` is `'local' | 'connection'`).
   Building the `Files | Table` toggle now would author config that is unloadable and corresponds to
   nothing executable. The amendment plan's own word for that is *fictional*; §2.6's toggle stays unbuilt
   until S3 lands.

**Summarize — was already further along than §2.6 assumed.** §2.6's literal ask ("renders group-by (list
type) + measures (list of expressions)") was **already satisfied**: `transform.summarize` is published
server-side (`NodeAttributes.java:164-168`), the mock agrees *by construction* via
`node-attributes.contract.json`, `RECIPE_VERBS` already offers the verb, and the generic
`NodeConfigDialog` renders both fields through `<inspecto-schema-form>`. So the slice's remaining value
was **not** another card — it was that **`measures` was validated nowhere**:
`PipelineConfig.prepare()` refuses arming, so a pipeline never parses its measures, and the only reader
of the `count | agg(field)` shorthand is `MaterializeTask.compileSpec` — a *separate* maintenance Job on
its own schedule. `median(x)` saved clean and failed days later, elsewhere.

**Shipped:** client-side measure validation with an inline error naming the six real aggregates
(`measure-grammar.ts`), reached through a new generic `<inspecto-schema-form>` `[extraValidators]` input;
the agg list is **contract-pinned** to `MeasureCompiler.AGGS` by `MeasureGrammarContractTest` +
`measure-grammar.spec.ts` over the committed `measure-grammar.contract.json`, so neither side can add an
aggregate alone. Deliberately stricter than the engine in one spot: `count()` is refused here though
`MeasureCompiler` skips the field check for `count`.

⚠ **A latent defect surfaced and was fixed:** a `type: 'list'` field's `<mat-error>` **could never
fire** — the `<input>` is the draft, so `<mat-form-field>` has no `NgControl`. Every list error was
invisible, **`required` included**. `listError()` + an explicit `role="alert"` line fixes it, and the
preview proved it by newly surfacing a real "Group by is required". A unit test asserting `errorFor()`
returns the string passed the whole time — assert the rendered element instead.

**`group_by` validation SHIPPED 2026-08-08 — the follow-up this section deferred.** Same failure mode as
measures, same distance from the form: `MaterializeTask:135` splits `group_by` into `body.groupBy` and
`MeasureCompiler:66` runs every entry through `safeIdent`, so `region name` also saved clean and died in a
maintenance Job days later. `groupByError`/`groupByValidator` join `measure-grammar.ts` (both list rules now
share a private `listEntryValidator`), wired as a second `configValidators` key on `NodeConfigDialog`.

Two things worth carrying:
1. **The identifier pattern is now contract-pinned too**, not just the agg list. It was a hardcoded literal
   in `measure-grammar.ts` backed only by a doc comment; it is the *whole* rule for `group_by`, so it moved
   into `measure-grammar.contract.json` as `identifier` and `MeasureCompiler.SAFE_IDENT` went
   package-private beside `AGGS` for the contract test to pin. **The pattern travels unanchored** — Java's
   `matches()` is whole-string, JS `test()` is a substring search — so the client anchors it itself; an
   unanchored `test()` would accept `1amount` on the client alone, and there is a spec for exactly that.
2. **Deliberately not the measure grammar's superset.** A grouping column is quoted verbatim into
   `GROUP BY`, so `sum(amount)` there is a category error, not a near-miss — it gets its own message
   redirecting the author to Measures rather than a restatement of the pattern.

**Still open in S7:** the table-entry half (S3-gated), and the per-measure structured builder (an
agg dropdown + column autocomplete per row) which was considered and **not** taken — it risks drifting
from the string grammar the backend actually parses.

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
