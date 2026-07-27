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
| `component_draft`, `query_author`, `projection_author` | `{kind, id?, clean, findings, draft}` | **one** `AiDraft` |
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
| `studio/link-analysis` query panel | `projection_author` | `datasetId` + the panel's own `datasetColumns()` | `patchFormFromQuery(...)` + `markAsDirty()`; operator presses Run, then the host's Save |

⚠ **The Link Analysis adopter is the one whose args the backend could not have resolved itself** — no tool
or tool-layer route returns a Dataset's columns, so A3's "the pane's context *is* the args" is load-bearing
there rather than merely convenient. As-built:
[`link-analysis.md`](link-analysis.md#ai-derived-projection-mappings-v2-d-authoring-half--shipped-2026-07-27).

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

⚠ **The mock must never be more lenient than the server** — three shipped bugs hid behind that (see the
audit below). Its strictness is pinned in `agent.handler.spec.ts`, not left to the preview, which cannot
catch what the mock permits. Where full parity would mean re-implementing a backend subsystem (the config
spec system, `ConditionSql`'s typed casts), the mock mirrors **acceptance** — the same inputs are refused,
the same inputs render nothing — and says so at the branch.

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

**Adopted on 12 panes** (13 routes — `object-mail` serves both `/incidents` and `/cases` and switches its
term list on `isIncident`): Pipelines · Datasets · Catalog · Expectations · Alerts · Collectors · Query
Library · Runs · Scheduler · Tags · Incidents/Case Manager · **Link Analysis** (2026-07-26, as
link-analysis V2 (d)'s vocabulary half — the pane with the most specialised vocabulary in the app, since
Entity/Link mean something here that the glossary explicitly bans using for artifacts or assets).

⚠ **The offline `GLOSSARY` map in `agent.handler.ts` is a subset** of the real file (the SPA cannot read
`docs/`), so a term a new pane declares must be added there too or it falls through to a `docs_search`
that has nothing real to cite offline. Definitions are copied **verbatim** — a paraphrase would put a
second, drifting definition of the binding vocabulary in the codebase.

## Natural-language authoring (A5.1 — shipped 2026-07-27)

`POST /agent/tools/{name}/derive` (`{prompt, args}`) turns one operator sentence into **the tool's
arguments**, then runs the tool through the **same deterministic path** `runTool` uses. The model
contributes arguments and nothing else — it never reaches the tool, the draft, or the answer. On the
surface this is a **mode of `<inspecto-ai-assist>`** (`prompting`), not a fourth sibling: the draft,
diff, Apply and `canAuthorWorkbench` gate are all unchanged, only the input differs.

Adopted on **Queries only** (plan D10: opt-in per pane). A prompt box on a tool whose input the screen
already holds is theatre — Expectations knows its `table`+`column`, and profiling them is deterministic
SQL.

**Containment is the request shape, not a policy.** `ArgumentDeriver` builds a `ChatRequest` offering
**exactly one** non-mutating tool and reads only that call's `arguments`, so the model cannot select
another tool or enter the deliberative loop's tool-choice/paraphrase steps. Nothing downstream has to
remember a rule.

Load-bearing details, each of which is a way this could have gone wrong:

- ⚠ **The merge is schema-keyed, never `putAll`.** `query_author`'s safety story is that the *server*
  renders the SQL and `SqlGuard`-checks it. A model that helpfully emits `sql`/`text` has those keys
  **dropped** — a blind merge would have made the model a SQL author through the back door. Pinned by
  `ArgumentDeriverTest.aModelEmittedSqlKeyIsDroppedNotSpliced`. An **unreadable schema drops everything**
  and keeps only the pane's args (fail closed).
- ⚠ **Pane args are applied LAST and win** — the screen knows which Dataset is open, a model can
  hallucinate one. This has a sharp edge for adopters: the Queries pane passes **`aiPromptArgs()`, not
  `aiQueryArgs()`**, because `aiQueryArgs` carries the existing `when` tree and would overwrite the
  condition the sentence just derived — the feature would silently do nothing while looking like it
  worked. **A new adopter must pass identity fields only.**
- ⚠ **Three failures, three answers, deliberately distinct.** No model configured → **503**; malformed
  arguments (`_raw`) → **422**; a prose answer with no tool call → **422 with a different message**.
  Conflating them blames the operator's sentence for a deployment fact. On the surface the 503 sets
  `noModel`, which is **not** `unavailable`: the deterministic affordance on the same pane still works,
  so only the prompt box degrades.
- ⚠ **Mutating tools are refused BEFORE any model call**, inheriting A1's draft-only invariant — a
  refused tool must never have arguments composed for it.
- ⚠ **Route order.** `POST /agent/tools/(.+)/derive` is registered **before** the greedy
  `POST /agent/tools/(.+)`, which would otherwise match `query_author/derive` as a tool *name* and 404.
  Same reason `/agent/cases/{id}/similar` precedes `/agent/cases/(.+)`. Pinned by
  `AgentRoutesTest.deriveIsNotSwallowedByTheGreedyToolRoute`.
- ⚠ **The derive route is as ungated as its A1 sibling** — no `Role`, no `Capability`. Do not add a
  half-gate to one of the two routes; the authoring gates are the UI's `canAuthorWorkbench` and the
  edition's write seam.
- `derivedArgs` is echoed back and **rendered above the diff**. With a model in the loop that echo is
  what makes the draft reviewable rather than magic.
- **No-model detection is a flag, not `instanceof StubLlmGateway`** — `GatewayFactory.Gateway(llm,
  configured)`. A test injects a stub that *does* answer with tool calls, and that is a configured model
  for this purpose.

**Offline:** the mock's `/derive` branch derives a condition from `<field> over|under <number>` and
otherwise returns the same retryable 422 a real local model produces when it narrates. It then
**re-enters the same handler** on the deterministic URL rather than reimplementing the tool body, so the
two offline paths cannot drift. The mock's `query_author` now renders the *actual* derived predicate —
its old fixed `cost_usd > 100` would contradict the `derivedArgs` echo directly above it.

**Still open:** A5.3 (`pipeline_author`). `suggest_expectations` is excluded on purpose.

## The bounded repair loop (A5.2 — shipped 2026-07-27)

`query_author`'s NL path is one hop. `component_draft`'s is a **loop**, and the difference is structural:
that tool has *no authoring logic* — it echoes `config` back with anchored findings — so a single turn
reliably yields a probably-invalid config. `InspectoIntelligenceAgent.repairLoop` derives, invokes, and
feeds the findings back for up to **3 turns** (plan D11), then hands over. The route echoes `turns`.

Four properties are load-bearing:

- **Schema-constrained regeneration (plan D9's payoff).** The offered spec's bare
  `config:{"type":"object"}` — §3.4 F2's named cost centre — is replaced per call with the kind's
  projected schema via `ArgumentDeriver.constrainedFor`, so the model is constrained by the very spec
  that will judge it. ⚠ `kind` comes from the **pane**, never the model; it is an identity field.
- **The cap is a hand-over, not a failure.** At 3 turns `ok` stays **true** and the draft rides out with
  its findings — which is exactly the A1 experience the surface already renders for human repair.
- **⚠ Best = fewest findings, not last.** A later turn can regress; returning the last draft because it
  was last would hand back something worse than the loop already had. Pinned by a test.
- **A first-turn failure is the caller's 422; a later one keeps what it has.** No draft is better than an
  empty one presented as a draft, but a model that degrades mid-repair must not lose a good earlier draft.

⚠ **`ArgumentDeriver.withPropertySchema` fails OPEN** to the unmodified spec on unreadable input. A missing
constraint costs a repair turn; throwing would remove an authoring surface that works — and the loop
validates every draft against the real `ConfigSpec` regardless of what the model was told.

### Its host — and the premise that was wrong

The plan assumed `component_draft` had a pane. **It had none.** Adopted on the **Components pane's
schema kind** (`component-form.dialog`), and *only* that kind: of the dialog's four
(grammar/schema/transform/sink) only `schema` has a `ConfigSpec`, so on the rest the tool can only answer
*"no structural spec for kind"*. ⚠ **Do not "complete" the adoption onto the other three.**

⚠ Offline, the mock's derived field types **must come from the schema form's own vocabulary**
(`string|integer|bigint|double|boolean|date|timestamp`). An early cut emitted `number`, which applied
silently and left the row's type dropdown **blank** — it looked like the draft had worked. Caught only in
the preview, not by any unit test.

## Natural-language topologies (A5.3 — shipped 2026-07-27)

`pipeline_author` completes A5. It reuses A5.2's loop rather than adding a second mechanism: the agent's
`REPAIRABLE` map now names both tools and the argument each rewrites (`component_draft` → `config`,
`pipeline_author` → `flow`), and `repairLoop` is parameterised on that property.

⚠ **The plan's premise for this slice was WRONG.** It budgeted a *hop*, reasoning that a graph's errors are
"structural, not field-level" and so could not be fed back. But `PipelineValidator` already reports **coded,
structured issues** (`DANGLING_TO`, `CYCLE`, `NO_ENTRY`, `DUPLICATE_NODE`, `ILLEGAL_EMIT`, …), which is
exactly the anchored substrate a repair turn needs. A topology converges the same way a config does.

What that required of the tool, all additive:

- **`pipeline_author` now validates.** It returns `clean` + `findings` in the same
  `{severity, code, fieldPath, message}` shape every other draft-bearing tool uses. `fieldPath` is the
  **collection** (`nodes` / `edges` / `flow`), never an index — the validator reports by *id* (already in the
  message) and an index would anchor a repair to a position the model can reorder.
- **⚠ An unexecutable graph stays `ok=true`.** This is the property the whole slice rests on: a refusal
  would make the loop treat a broken topology as "not repairable" and bail on turn one, which is precisely
  the case NL authoring exists to rescue. It is not simulated, though — a dry run would fail on the same
  defect and report it as a simulate *error*, losing the code.
- **`flow`'s schema is hand-written** (`InspectoTools.flowSchemaJson`), because plan D9 cannot reach an IR —
  an authored graph has no `ConfigSpec`. What keeps it from becoming a second source of truth is that node
  `type` is enumerated from the **live `PipelineNodeTypes` registry**, so a registered extension type is
  offerable the day it registers. ⚠ `rel` is deliberately **not** an enum: `route:<key>` is open-ended, and
  closing it would forbid the branch dispatch `transform.route` and `parser` exist to express.

⚠ **The pane passes NO args on the prompt instance.** Pane args merge last and win, so sending the open
graph would overwrite the topology the sentence just produced and the draft would silently equal what is
already on screen. Identity is preserved on *apply* instead — `applyPipelineDraft` keeps the open pipeline's
`name` and `active` whatever the model called its graph. It is a **second `<inspecto-ai-assist>` instance**,
not a mode switch: describing a topology and checking the one on screen are different acts, and the check
must keep working on a backend with no model.

### ⚠ Two shipped A2 bugs this slice uncovered — both hidden by the mock

The Pipelines pane's deterministic adoption had **never worked against a real backend**:

1. It passed `{name, nodes, edges}` **flat**; the tool requires the graph under `flow` and answers
   *"flow is required and must be an object"*. `AgentService.runTool` passes `args` through verbatim — there
   is no wrapping anywhere.
2. The tool's result put the flow's **name string** under `flow`, but `adaptToolResult` requires an object
   there — so even a successful call rendered as *"no suggestion"*, with no error.

Both survived because the offline mock read the flat shape and returned an object. **A mock more lenient
than the server is worse than no mock**: it converts a hard failure into a passing rehearsal. The mock is
now strict about `flow`, and `agent.handler.spec.ts` exists specifically to pin that strictness. The same
class of divergence produced a third, cosmetic one caught in the preview — the mock's echo omitted `active`,
rendering a phantom *"active (removed)"* row in the operator's diff.

### The cross-adopter audit that followed (2026-07-27, `feb6f6e7`)

Every remaining adopter was cross-checked three ways — pane `[args]` vs the tool's `jsonSchema`, the tool's
real Java result vs its `adaptToolResult` branch, and the mock branch vs both. **The enabling fact: nothing
validates `args` against `jsonSchema`.** `AgentRoutes` passes the body's `args` to `runTool` verbatim and
each tool hand-checks its own; a schema/pane mismatch only bites when the tool's Java body disagrees too,
which is exactly how the `flow` bug reached production. Two more instances were found and fixed, both
mock-side, and one defect was uncovered that is **still open**:

1. **`query_author` — the mock read `when` flat.** The server renders it through `ConditionSql`, which
   walks a **group** (`{op, items:[{field, operator, value}]}`, the comparison under `operator`). A tree it
   cannot read contributes no constraint ⇒ SQL with **no `WHERE` at all**, `clean:true`, no findings. The
   mock read `{field, op, value}` and fell back to a hardcoded `cost_usd > 100`, so the A2 button showed a
   predicate the operator never asked for, and the A5.1 derive stand-in **emitted** the flat shape — teaching
   the derive path the one argument shape that silently drops the filter. The mock now walks the same tree
   and, like the server, emits no `WHERE` when nothing renders. ⚠ A **flat** `when` still renders nothing
   rather than 422ing: that is deliberate parity — the server accepts and drops it, and a stricter mock
   would hide that.
2. **`component_draft` — the mock validated nothing.** The tool is a *validator*; the mock accepted any
   `kind`, echoed it back as `type`, and only flagged an empty config, so every draft was `clean:true` and
   the A5.2 repair loop could never be seen to run offline. It now mirrors `ConfigSpecs`' required paths
   (`DRAFT_SPECS`), refuses an unvalidatable kind with 422, and resolves `alert-rule` → type `alert`.
   ⚠ A **subset** by design — enums, parsability and cross-field rules are not re-implemented, because that
   would be a second, drifting copy of the config system.

⚠ **OPEN — `component_draft(kind='schema')` validates the wrong `schema`.** The word names two unrelated
things: a **registry component** (`ComponentStore.WRITABLE_TYPES`, content a bare `{fields:[{name,type}]}` —
what the Components pane authors and what `applySchemaDraft` reads back) and the **TOON schema config**
(`ConfigSpecs.schema()`, `raw.name` *required*, `raw.fields`) — and `component_draft` resolves the latter. So
the A5.2 adoption always draws *"Missing required field 'raw.name'"* against a real backend, and a repaired
`{raw:{…}}` draft is one the pane cannot apply. Pinned as a failing-shape test rather than papered over. The
fix is a design call — reshape what the pane drafts, or accept that registry components have **no
`ConfigSpec` at all** and A5.2 needs a different validator. → `BACKLOG.md`.

⚠ **OPEN — `projection_author`'s declared `columns.items` is stale.** The pane sends a `string[]`; the schema
says `{"items":{"type":"object"}}`. The Java `columnNames` accepts both deliberately, and so does the mock,
so nothing breaks on this route — but it is what a model reads on the `ask` path. → `BACKLOG.md`.

The two clean adopters: **`suggest_expectations`** (args, result and adapter all agree) and
**`projection_author`** apart from the schema note above.

## Not shipped

- **`kpi_report_builder` has no viable host pane.** It emits N widgets *plus* a dashboard, and no pane
  holds a dataset **and** operator-built measures **and** can create both: `studio/widgets/explore` has
  dataset + measures (`controls()`, whose `ChannelValue.agg` enum matches the tool's exactly) but saves
  exactly **one** widget; `studio/dashboards/dashboard-editor` can build a dashboard but has no measures.
  That host is a new flow, not an adoption. → `BACKLOG.md`.
- ~~**"Why is this red"**~~ — **SHIPPED 2026-07-26**, see *Why is this red* below.
- **A5's D9 prerequisite is DONE (2026-07-27) — the `ConfigSpec` → JSON Schema projection ships.**
  `ConfigJsonSchema` (`inspecto-config`, `com.gamma.config.spec`) turns any `ConfigSpecs.forType(kind)` into
  a real JSON Schema, and the new **`config_schema` read tool** (L1, `READ_DOCS`, non-mutating) exposes it.
  This is what A5's derive hop constrains generation with — "constrain the model with the spec that judges
  it", so a schema-honouring generation cannot fail on structure. Load-bearing details:
  - ⚠ **It is deliberately a separate tool, NOT a tighter `component_draft` input schema.**
    `component_draft` is a *validator*; its job is to accept a malformed draft and return anchored findings.
    Constraining its own `config` property would let the transport reject bad drafts before the validator
    could explain them — destroying the repair loop. The schema travels as data a caller fetches.
  - ⚠ **A required nested path pulls its whole ancestor chain into `required`.** `ConfigLoader.validate`
    treats a required path as required *absolutely*, so marking only the leaf would let a model omit the
    entire enclosing block and still satisfy the schema while failing validation.
  - ⚠ **`additionalProperties` is never set to `false`.** A `ConfigSpec` enumerates what it can *validate*,
    not every key a kind accepts, so forbidding extras would reject configs the control plane applies.
    Likewise `LIST` gets no guessed `items` type — the element type is not in the spec model.
  - A path can be both a leaf and a prefix (`x` as a `MAP` plus `x.y`); the node merges, in either authoring
    order. `FILEPATH`/`CRON`/`SQL` project as `string` (they are wire-level STRING refinements) with the
    refinement surviving in the description, which is what a model actually reads.
  - Degrades to the bare `{"type":"object"}` — today's hand-written value — for a null/unspecced kind, so an
    unknown kind never fails registration.
  - ⚠ **`query_author.when` and `pipeline_author.flow` are still bare `{"type":"object"}`** and are **not**
    fixed by this: neither is `ConfigSpec`-shaped (a condition tree and a flow graph). They need hand-written
    schemas, and belong with A5.1/A5.3 respectively.
  - ⚠ **`InspectoPackTest` asserts a hardcoded tool count** (now 23) — every new tool goes stale there.
    Same trap class as `OidcAuthenticatorTest`'s hand-written capability sets.
- **A5** — true natural-language authoring. **Now scoped** (2026-07-26): `superpower/agt-6-plan.md` §3.4.
  Two facts that matter to this surface specifically: NL is a **mode of `<inspecto-ai-assist>`, not a fourth
  sibling** (unlike the A4 pair, all four of this component's properties — draft, diff, Apply,
  `canAuthorWorkbench` — apply), and it is **opt-in per pane**, explicitly *not* Expectations, where the
  dialog already holds `table`+`column` and the profiling is deterministic SQL. See also the shape warning
  in [[embedded-intelligence]].
