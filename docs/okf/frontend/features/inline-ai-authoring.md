# Inline AI authoring (`<inspecto-ai-assist>`, AGT-6a)

The one shared inline authoring surface: a pane names a **non-mutating** agent tool, passes its own
context as that tool's arguments, and the surface renders the returned draft with anchored findings and a
diff against current state. Panes **adopt** it; they never fork it. Backend half and the route's gate
order: [[embedded-intelligence]].

Shipped 2026-07-26 (A1–A3). Plan: `superpower/agt-6-plan.md` — still active for A4 + the deferred
`kpi_report_builder` host.

## Shape

`inspecto/ai-assist/` — `ai-assist.component.{ts,html}` (the surface) + `ai-draft.ts` (pure, framework-free
adapters and the diff) + specs. Listed in the `/design` gallery.

```html
<inspecto-ai-assist
    tool="suggest_expectations"          <!-- AiToolName; mutating tools are refused 403 -->
    [args]="aiArgs()"                    <!-- the pane's context, passed straight through -->
    [current]="aiCurrent()"              <!-- diff baseline; null on create ⇒ all fields "added" -->
    label="Suggest from the data"
    [disabled]="!target()" disabledReason="Set Target first"
    (applyDraft)="applySuggestion($event)" />
```

## The three invariants it exists to preserve

1. **Draft-only.** The surface has **no write path at all**. It emits `(applyDraft)`; the *pane* writes
   through its own existing validated route, so the human is the audited actor (decision **D2**). Backed
   by the route's mutating-tool 403.
2. **Degrade, never hard-fail.** A **503** (module absent) latches `unavailable`, disables the affordance
   and explains itself inline — the pane behaves exactly as before. Every other error is a toast via
   `apiErrorMessage` and does **not** latch, so a 422 stays retryable.
3. **Gated** on `LensService.canAuthorWorkbench()` — the UI twin of the tools' `AUTHOR_PIPELINE`
   capability. The `run()` method itself refuses, not just the button (defense in depth).

## Result shapes — `adaptToolResult` normalizes four different ones

Three of the four authoring tools already share the backend's `{kind, id?, clean, findings, draft}`
envelope, so they share a branch. The other two do not, and that is the whole reason the adapter exists:

| Tool | Its own shape | Normalized to |
|---|---|---|
| `component_draft`, `query_author` | `{kind, id?, clean, findings, draft}` | **one** `AiDraft` |
| `kpi_report_builder` | + `widgets: [{id, draft}]` | one `AiDraft` whose **`prerequisites`** are the widgets |
| `suggest_expectations` | `{table, column, profile, suggestions[]}` | **N** candidates the operator picks between |
| `pipeline_author` | `{flow, nodes[], simulated, nodeOutputs[], …}` | one `AiDraft` from `flow`, `note` from the simulation |

- ⚠ `suggest_expectations` candidates are **derived from real data by deterministic SQL**, so they carry
  `clean: true` and **no findings** — there is nothing to repair. Absence of findings there is not a bug.
- ⚠ `kpi_report_builder`'s widgets must be applied **before** the dashboard that tiles them; that
  ordering is why `prerequisites` exists rather than a flat list.
- An unrecognized shape yields `[]` → the surface shows "no suggestion", never a broken card.
- `configDiff` flattens to dotted paths and compares **arrays whole** (a per-element diff is noise an
  operator cannot act on). Unchanged fields are computed but folded behind a toggle, so the whole config
  stays auditable.

## Adopted panes (A2) and their context (A3)

A3 was **reframed**: the plan said "agent *session attributes*", but the deterministic path opens no
session — so the pane's context simply *is* the tool's args. Verified by asserting the args the pane
sent, never by prompt inspection.

| Pane | Tool | Args from | Apply lands in |
|---|---|---|---|
| `pipelines/pipeline-editor` | `pipeline_author` | `model()` → `{name, nodes, edges}` | `model.set(...)` + `dirty.set(true)`; operator presses the existing Save |
| `studio/queries` | `query_author` | form `datasetId` + `structuredModel().where` | `form.controls.text`; operator presses the existing Save |
| `expectations/expectation-form.dialog` | `suggest_expectations` | `target` + `column` controls | `schemaForm.form.patchValue(...)` + `markAsDirty()`; operator completes the two-step save |

Three gotchas that cost a debug cycle each — do not "clean these up":

- ⚠ **Expectations lives in the DIALOG, not the list pane.** The list has no row selection, so it has no
  `table`/`column` at all; the dialog has both as form controls.
- ⚠ **`target` (pipeline/job id) and `table` (browsable store) are DIFFERENT vocabularies** and nothing
  in the UI maps between them. We pass the same value for both, matching what
  `columnOptionLoader('target')` already does (it probes `/db/table` with the target verbatim) and the
  backend's own `target` defaults-to-`table`. When the target is not a browsable store the tool answers
  "unknown table" → 422; that is **expected**, not a defect. Do not invent a pipeline→table lookup.
- ⚠ **Patch `kind` in the SAME `patchValue` as `min`/`max`.** Those controls are only enabled while
  `kind === 'range'`, and a disabled control is excluded from `schemaForm.value()` — patch them
  separately and the bounds silently vanish.
- ⚠ **Applying drafted SQL must switch the Queries editor `type` to `sql`.** Left on `structured`,
  `buildQuery` persists the *model* and drops the text entirely, so the draft is silently discarded on
  save.
- Adopting a pipeline draft **preserves the open pipeline's `name` and `active`** — the tool echoes the
  graph, not the lifecycle, and adopting must never silently rename or activate a live pipeline.

## Offline

`inspecto/mock/handlers/agent.handler.ts` (gated on `mockOps`) mocks `POST /agent/tools/{name}` for all
five tools **and reproduces the gates** (403 mutating, 404 unknown, 422 missing args), because the
surface's degrade paths are what most need exercising offline. Result shapes mirror `InspectoTools` — if
those change, change these too. Registered in `mock-api.interceptor.ts`; the rest of `/agent/*` stays
real.

## Not shipped

- **`kpi_report_builder` has no viable host pane.** It emits N widgets *plus* a dashboard, and no pane
  holds a dataset **and** operator-built measures **and** can create both: `studio/widgets/explore` has
  dataset + measures (`controls()`, whose `ChannelValue.agg` enum matches the tool's exactly) but saves
  exactly **one** widget; `studio/dashboards/dashboard-editor` can build a dashboard but has no measures.
  That host is a new flow, not an adoption. → `BACKLOG.md`.
- **A4** — the read-only "explain this screen" affordance. The route already admits the read tools, so
  this needs no new backend.
- **A5 (new)** — true natural-language authoring. Needs the NL→structure model hop this plan never
  scoped; see the shape warning in [[embedded-intelligence]].
