# Inline AI authoring (`<inspecto-ai-assist>`, AGT-6a)

The one shared inline authoring surface: a pane names a **non-mutating** agent tool, passes its own
context as that tool's arguments, and the surface renders the returned draft with anchored findings and a
diff against current state. Panes **adopt** it; they never fork it. Backend half and the route's gate
order: [[embedded-intelligence]].

Shipped 2026-07-26 (A1–A3, then **A4** — see *Explain this screen* below). Plan:
`superpower/agt-6-plan.md` — still active for the deferred `kpi_report_builder` host + A5.

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
five authoring tools plus the two read tools **and reproduces the gates** (403 mutating, 404 unknown, 422 missing args), because the
surface's degrade paths are what most need exercising offline. Result shapes mirror `InspectoTools` — if
those change, change these too. Registered in `mock-api.interceptor.ts`; the rest of `/agent/*` stays
real.

## Why is this red (`<inspecto-ai-status>`, A4-status — shipped 2026-07-26)

`inspecto/ai-assist/ai-status.component.ts` (the trigger) + `ai-status.dialog.ts` (the reads + render).
The **third** member of the family, and deliberately not a mode of either sibling:

| | `<inspecto-ai-assist>` | `<inspecto-ai-explain>` | `<inspecto-ai-status>` |
|---|---|---|---|
| Question | help me author this | what does this word mean | what happened to this thing |
| Answers from | a draft the pane applies | `docs/GLOSSARY.md` | **deployment state** (the ledger) |
| Needs | pane context | nothing but declared terms | **a real entity id** |
| Placement | in the pane's form | pane header, once | a **row** or detail header |
| Gated | `canAuthorWorkbench()` | no | **no** |

```html
<inspecto-ai-status [label]="row.name" [pipelineId]="row.name" />
<inspecto-ai-status [label]="incident.id" [correlationId]="incident.correlationId" />
```

**Three tools, all non-mutating, so again no new backend capability**: `status_get` (live paused state +
committed batches), `signal_timeline` (the exact causal chain for one `correlationId`), `timeline_build`
(everything in a window, narrowed by the tool's own `focus` substring). The dialog picks **the chain when
the pane has a `correlationId`, the window otherwise** — the chain is the precise answer, not everything
that happened nearby — and the status/timeline halves **degrade independently**, so an unknown pipeline
(422) still shows activity.

⚠ **This is NOT the breadth win the vocabulary half was, and must not be swept onto every pane.** It needs
a real id, so it belongs only where the pane has one. Reference adoption: the **Alerts** pane's fired-alert
grid (`firedActions`) — a fired Alert *is* the red thing, and it carries the `pipeline` to focus on.
`FiredAlert` has **no `correlationId`**, hence the focused-window path there.

⚠ **Offline it reflects the mock store's own ledger, and nothing more.** `agent.handler.ts` answers these
three from `SIGNALS_COLL` + `PIPELINES_COLL` rather than a canned shape — unlike every other tool mocked
there — because the affordance's entire value is reporting real state. An empty ledger honestly answers
"nothing was recorded". `paused` is always `false` offline: the mock pipeline record has no such flag, and
inventing one is exactly the lie this avoids. **The handler now takes `(req, store)`** — it previously
ignored the store.

⚠ **Not gated on `canAuthorWorkbench()`**, same reasoning as its sibling: no write path, and asking why an
alert fired is not an authoring act. Don't "make it consistent" with the row-actions next to it.

**All four named operational panes are adopted (2026-07-26).** Per-pane, deliberately: each addresses its
entity differently, and *how* it addresses it decides which half of the dialog answers.

| Pane | Host | Addressed by | Path taken |
|---|---|---|---|
| **Alerts** (reference) | `firedActions` on the fired-alert grid | `FiredAlert.pipeline` | window, focused |
| **Processing Status** | first `rowActions` entry | `RunStatus.pipeline` | window, focused |
| **Events** (the signal ledger) | second `actions` entry | `correlationId` ?? `pipeline` | **chain** when present |
| **Incidents / Cases** | `object-detail` header, next to the lifecycle verbs | `OperationalObject.correlationId` | **chain**, no status half |

Two shapes worth keeping:

- **Events is where the chain path earns its keep** — a signal row is the one row that usually carries a
  `correlationId`. The action is **hidden** (`visible`) on a row with neither a correlation id nor a
  pipeline: there would be nothing to address, and an affordance that can only answer "nothing" is worse
  than no affordance.
- **Incidents shows it only when `correlationId` is set**, and gets the timeline half *alone* — an Incident
  has no pipeline, so there is no live state to read. That is the independent-degrade design working, not a
  gap to fill by inventing a pipeline lookup.

⚠ **Both grid adoptions needed `[pinActions]="true"`** — same horizontal-virtualization trap as Alerts.
Processing Status already had a `rowActions` column and still needed it once a second button widened it.

⚠ **Offline the chain path cannot be exercised from the Events pane**: no mock producer sets a
`correlationId` on an event row (the same flatness noted for `GET /signals/tree`), so every row there falls
back to the pipeline window. The chain path is verifiable from **Incidents**, whose seeded objects carry
`corr-N`. Don't "fix" the mock by inventing correlation ids on events — the unit test covers the branch.

## Explain this screen (`<inspecto-ai-explain>`, A4 — shipped 2026-07-26)

The read-only half. One icon button a pane drops into its header; everything it renders lives in a
**dialog**, so adopting it cannot disturb the header row it sits in — that is what made breadth cheap.

`inspecto/ai-assist/ai-explain.component.ts` (the trigger) + `ai-explain.dialog.ts` (the lookup + render)
+ specs. Also in the `/design` gallery.

```html
<inspecto-ai-explain screen="Pipelines" [terms]="['Pipeline', 'Step', 'Trigger']" />
```

**Why it is a sibling of `<inspecto-ai-assist>` and not a mode of it** — three of the four things that
component *is* do not apply, so a mode flag would leave half of it dead on every render:

1. **No write path whatsoever** — no draft, no diff, no Apply, no `adaptToolResult`.
2. **Deliberately NOT gated on `canAuthorWorkbench()`.** A Business-lens user is exactly who needs the
   vocabulary explained. ⚠ Do not "make it consistent" with the authoring surface by adding the gate.
3. **The pane declares the terms; the operator never types one.** A free-text box would make this a docs
   search engine and would re-state what the screen already knows (the A3 rule). Terms are
   `docs/GLOSSARY.md` spellings — the canonical ones, never a banned synonym.

**Backend: none was added.** `POST /agent/tools/{name}` gates only on `ToolSpec.mutating()`, and
`glossary_lookup` / `docs_search` are non-mutating `READ_DOCS` reads, so the whole feature is UI-side.

**Resolution order per term**, each term degrading independently so one failure never blanks the others:
`glossary_lookup` → on **422** (no canonical definition) fall back to `docs_search` and render its
`{file, line, snippet}` **citations** → otherwise "No canonical definition found." A **503** latches once
and the dialog explains the module is absent instead of failing. There is **no model in the loop**: the
operator reads the binding glossary, not a paraphrase of it.

**Adopted on 11 panes** (12 routes — `object-mail` serves both `/incidents` and `/cases` and switches its
term list on `isIncident`): Pipelines · Datasets · Catalog · Expectations · Alerts · Collectors · Query
Library · Runs · Scheduler · Tags · Incidents/Case Manager.

⚠ **The offline `GLOSSARY` map in `agent.handler.ts` is a subset** of the real file (the SPA cannot read
`docs/`), so a term a new pane declares must be added there too or it falls through to a `docs_search`
that has nothing real to cite offline. Definitions are copied **verbatim** — a paraphrase would put a
second, drifting definition of the binding vocabulary in the codebase.

## Not shipped

- **`kpi_report_builder` has no viable host pane.** It emits N widgets *plus* a dashboard, and no pane
  holds a dataset **and** operator-built measures **and** can create both: `studio/widgets/explore` has
  dataset + measures (`controls()`, whose `ChannelValue.agg` enum matches the tool's exactly) but saves
  exactly **one** widget; `studio/dashboards/dashboard-editor` can build a dashboard but has no measures.
  That host is a new flow, not an adoption. → `BACKLOG.md`.
- ~~**"Why is this red"**~~ — **SHIPPED 2026-07-26**, see *Why is this red* below.
- **A5** — true natural-language authoring. **Now scoped** (2026-07-26): `superpower/agt-6-plan.md` §3.4.
  Two facts that matter to this surface specifically: NL is a **mode of `<inspecto-ai-assist>`, not a fourth
  sibling** (unlike the A4 pair, all four of this component's properties — draft, diff, Apply,
  `canAuthorWorkbench` — apply), and it is **opt-in per pane**, explicitly *not* Expectations, where the
  dialog already holds `table`+`column` and the profiling is deterministic SQL. See also the shape warning
  in [[embedded-intelligence]].
