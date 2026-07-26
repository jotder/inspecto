# AGT-6 Plan — Inline AI Authoring & Agent Graphs

> ## ⚠️ AS-BUILT UPDATE — 2026-07-26: A1–A4 SHIPPED, and three of this plan's premises were WRONG
>
> **Plan stays active** (one A2 pane + A5 + all of 6b remain). D1–D4 answered; §8 records the calls.
>
> **Correction 3 — A4 (§3.2) is TWO affordances, not one, and only one of them is a breadth win.** The
> row's own phrasing gives it away: *"what am I looking at / why is this red"*. The first is a **vocabulary**
> explain over `glossary_lookup`/`docs_search` — deterministic, meaningful on every pane, and correctly
> **ungated** (a Business-lens user needs it most). The second is a **status** explain over
> `status_get`/`signal_timeline`/`timeline_build`, which needs real entity ids and therefore only means
> anything on the four operational panes — so it cannot satisfy "every remaining pane" and is not the cheap
> win the row priced. **Operator decision 2026-07-26: vocabulary first**, status explain → BACKLOG.
> **Shipped:** `<inspecto-ai-explain>` (trigger + dialog) on **11 panes / 12 routes**, 7 new specs, offline
> glossary mock, `/design` entry. Backend: **genuinely none** this time — the route gates only on
> `ToolSpec.mutating()`, so the read belt was already reachable. ⚠ It is a **sibling** of
> `<inspecto-ai-assist>`, not a mode of it (no write path, no authoring gate, no diff) — see
> `okf/frontend/features/inline-ai-authoring.md` for why folding them together leaves half dead per render.
>
> **Correction 1 — "no new backend capability" (§0 table, §3.2 A2) was FALSE.** The five L1 tools had
> **no invocable route**. They are `ToolSpec`s in the agent's belt, reachable only *indirectly* through
> `POST /agent/sessions/{id}/ask` — where the model chooses the tools and then **paraphrases** what they
> returned. `AgentAskResult` is `{kind,text,citations,navigationTarget,artifact}`: the validated draft and
> its anchored findings are **not** first-class fields, so a pane could neither diff nor apply them, and
> A1's exit criterion was structurally unreachable. Shipped fix: **`POST /agent/tools/{name}`** — one
> non-mutating tool, invoked deterministically, result returned verbatim, no model in the loop. Modelled on
> `AssistRoutes`' `POST /assist/{intent}`. → `okf/backend/agent/embedded-intelligence.md`.
>
> **Correction 2 — "natural-language authoring" (§3.1) does not describe what these tools do.** Four of the
> five take **structured** input, not prose: `suggest_expectations` profiles a column (its own description
> says *"Deterministic SQL, no model"*), `query_author` takes a condition tree, `kpi_report_builder` takes
> measures, `pipeline_author` takes a graph. Only `component_draft` needs a model — and it merely
> *validates* a config something else composed. So "NL → validated draft" needs a **model hop** the plan
> never scoped. **Operator decision 2026-07-26: deterministic-derive first** — the surface takes pane
> structure, no model anywhere. True NL authoring is a separate, honestly-scoped item (**A5**, BACKLOG).
>
> **Shipped:** `<inspecto-ai-assist>` (A1, D1) · adopted on **3** panes (A2) · pane context as tool args
> (A3, reframed — the deterministic path has no agent session, so "session attributes" did not apply) ·
> `runTool` SPI + route + 10 backend tests · offline mock handler · `/design` gallery entry.
>
> **NOT shipped:** **`kpi_report_builder` has no viable host pane** — it emits N widgets *plus* a
> dashboard, and no pane holds a dataset and operator-built measures *and* can create both. `explore` has
> dataset+measures but saves exactly one widget; `dashboard-editor` can build a dashboard but has no
> measures. That host is a new flow, not an adoption. → BACKLOG. **A4's status half**
> ("why is this red") → BACKLOG; its vocabulary half shipped (Correction 3).
>
> **Status: DRAFT for stakeholder review — 2026-07-25.** Scopes AGT-6, which was deliberately left
> "not scoped further on purpose" until AGT-5's tool belt + autonomy ladder shipped (P0–P5 complete
> 2026-07-21). Operator decision this session: **split the requirement** — the inline-authoring half
> (**AGT-6a**) is promoted to **Should** and is schedulable now; the agent-graph half (**AGT-6b**)
> stays **Could / demand-gated**. Decision asks in §8 need product sign-off before A1 starts.
>
> Companions: [`okf/backend/agent/embedded-intelligence.md`](../okf/backend/agent/embedded-intelligence.md)
> (AGT-5 as-built — the substrate this plan assembles) ·
> [`plans-archive/embedded-intelligence-plan.md`](../archived-documents/plans-archive/embedded-intelligence-plan.md)
> (AGT-5 phasing, §8 — archived 2026-07-25, retained as the phasing record) ·
> [`REQUIREMENTS.md`](../REQUIREMENTS.md) (§3.13 AGT rows, §5 MoSCoW) ·
> [`roadmap/ROADMAP.md`](../roadmap/ROADMAP.md) (L2/L3) · [`EDITIONS.md`](../EDITIONS.md) (edition flavors) ·
> `.claude/skills/angular-ui` (binding for every inspecto-ui artifact below).
>
> **On approval + ship**: distill as-built facts into
> [`okf/backend/agent/embedded-intelligence.md`](../okf/backend/agent/embedded-intelligence.md) (agent-side)
> + a new `okf/frontend/features/inline-ai-authoring.md` concept (UI-side, none exists today), move
> residual items to [`BACKLOG.md`](../BACKLOG.md), then `git mv` this plan to
> `archived-documents/plans-archive/`.

**Coverage map** (question → section): what's already built §1 · commercial packaging §2 · AGT-6a scope
& phases §3 · AGT-6b scope & blockers §4 · user experience §5 · exit criteria §6 · risks/anti-goals §7 ·
decision asks §8.

---

## 0. Executive summary

AGT-6 has always been two requirements wearing one ID. This plan separates them, because they have
different risk, different gates, and different value:

| | **AGT-6a — "AI behind every screen"** | **AGT-6b — Agent graphs** |
|---|---|---|
| What | Inline natural-language authoring on every console pane | Model-composed multi-step plans (provision → watch → roll back) |
| MoSCoW | **Should** — schedulable now | **Could** — demand-gated |
| Effort | **M** (UI-weighted; ~2–3 shifts for A1+A2) | **L** |
| New backend capability | **None** — reuses shipped L1 draft tools | Yes — dynamic plan composition |
| Blocked on | Nothing (GPU-optional; local models suffice for drafts) | Two upstream seams (§4.2) + named client demand |
| Risk | Low — drafts persist nothing, human applies | High — this is where trust breaks |

**The core claim: AGT-6 is assembly, not invention.** AGT-5 already built the tool belt (21 tools),
the validator-repair loop, the approval spine, the seeded runbooks, and the autonomy policy engine.
AGT-6a is a *distribution* problem — surface the existing L1 draft tools inline on the panes that
already have matching tools. AGT-6b is the one genuinely new capability, and it is correctly deferred.

**Commercially, we sell the ladder, not the ceiling** (§2). The differentiator is not raw agent
capability — it is *governed, air-gappable* AI: a capable agent that runs with zero network egress and
logs every action against the same audited routes a human uses. Autonomy is the option, not the pitch.

---

## 1. What is already built (the substrate)

Every row below is **shipped** (AGT-5 P0–P5, complete 2026-07-21). The right column is why AGT-6 is
mostly assembly.

| Shipped primitive | Consumed by |
|---|---|
| `component_draft` — validates any proposed config against the *same* structural spec + `ConfigSafetyValidator` gates the control plane enforces on write; returns anchored `Finding`s so the model repairs and re-validates to `clean=true` | **6a** — the universal "draft + findings" contract behind every inline surface |
| `pipeline_author` — parses an authored Pipeline graph, simulates `transform→sink` on a throwaway DuckDB via `PipelineDryRun` | **6a** (Pipelines pane) |
| `suggest_expectations` — profiles a column, derives `non_null` / `range` Expectation drafts | **6a** (Expectations pane) |
| `kpi_report_builder` — Measures → DRAFT Widgets + a Dashboard that tiles them | **6a** (Dashboards pane) |
| `query_author` — structured condition tree → trusted SQL (the model **never** writes SQL text) | **6a** (Queries pane) |
| `component_apply` / `component_rollback` — L2 gated writes over the audited loopback control plane | **6a** (optional apply path), **6b** |
| Approval spine — `ApprovalStore`, `AgentApprovals`, `/agent/approvals*`, the `/approvals` inbox UI, durable checkpoint/resume | **6b** (plan approval), **6a** (only if inline apply goes through L2) |
| `runbook_operator` — a **code-defined** ordered sequence of act tools as one approval-gated unit, stepwise log, halt-on-failure, durable mid-plan resume | **6b** — the direct precedent to generalize |
| `AutonomyPolicyEngine` — killSwitch → mode (`OFF`/`SHADOW`/`AUTO`) → budget; `/agent/policy*`; the `/autonomy` dashboard | §2 Tier C packaging |
| SPI + 503 degrade — `IntelligenceAgent` absent ⇒ every `/agent/*` route 503s; hosted SDKs physically absent from air-gapped builds (`EgressGuardTest`) | Both — the invariant AGT-6 must not break |

**The gap AGT-6 closes**, stated precisely: (1) the tools above are reachable only from the dedicated
agent surfaces, not from the pane where the operator is already working; (2) runbook steps are
code-defined, so the agent picks from a fixed menu rather than composing a plan.

---

## 2. Commercial framing — sell the ladder, not the ceiling

The autonomy ladder is not just an engineering structure; it is the **packaging**. Clients buy the
rung they trust, and can prove what happened at every rung. Mapped to the edition flavors:

| Tier | Rungs | Edition | Pitch |
|---|---|---|---|
| **A — Explain & Investigate** | L0 QA + L1 investigation (Cases, RCA, ranked root cause + fix draft) | **All**, incl. air-gapped | "Ask your platform why a batch failed; get a ranked root cause and a fix draft. Fully offline, local models, nothing leaves the box." |
| **B — Author & Act with approval** | L1 authoring + L2 gated action | **Standard+**, opt-in (`-Dintelligence.act.enabled`) | "The agent drafts; your operator approves in an inbox with a full diff. Every action rides the same audited route a human uses." |
| **C — Bounded autonomy** | L3 (`ops_monitor`, policy + budgets) | **Enterprise**, demand-gated | "Hands-off remediation inside limits you set — with a kill switch, per-class budgets, and a SHADOW mode to watch what it *would* do first." |

**Tier A is the wedge.** It is the highest-value, lowest-risk entry and it works in the deployments our
buyers actually have: regulated, air-gapped, no cloud LLM permitted. Most competitors cannot offer a
capable agent under those constraints. That — not autonomy — is the moat.

**AGT-6a lands squarely in Tier B** and is what makes the product *feel* AI-native without adding
mutation risk (drafts persist nothing; the human applies).

**SHADOW-first is the Tier C adoption on-ramp.** Never lead with `AUTO`. The sequence we recommend to
a client is: enable `SHADOW` for one action class → review the `/autonomy` ledger for a soak period →
promote a single class to `AUTO` with a conservative hourly budget.

**Anti-positioning (deliberate).** Do **not** market autonomy as the default or the differentiator.
For this buyer profile an over-eager autonomous agent is a liability. The winning sentence is: *"AI
that helps you understand and author, that acts only when you approve, that can run with zero network
egress, and that logs everything."*

> ⚠️ This framing is a product read from the codebase + roadmap, **not a validated market position**.
> D6 (§8) asks for the client-segment confirmation that would firm it up.

---

## 3. AGT-6a — "AI behind every screen" (Should, schedulable)

### 3.1 Scope

**In:** one shared inline surface, adopted pane-by-pane, that turns a natural-language request into a
**validated draft** the operator reviews and applies — reusing the existing L1 tools and the existing
validated write routes.

**Out, by design:**
- **No new mutating tools.** The act belt is frozen at what P3 shipped.
- **No model-authored SQL text.** `query_author`'s structured-condition-tree invariant holds.
- **No inline autonomy.** L3 stays on the `/autonomy` surface; an inline box never triggers an
  unattended action.
- **No new RAG corpus** in this phase (grounding is pane context, §3.2 A3).
- **No always-on chat dock.** The surface is invoked, pane-scoped, and dismissible.

### 3.2 Phases

| # | Phase | Effort | Deliverable |
|---|---|---|---|
| **A1** | **The inline surface primitive** | M | One shared standalone Angular component (working name `<inspecto-ai-assist>` — see D1) taking a pane context + a kind, calling the agent, and rendering: the NL input, streaming status, the returned draft, anchored `Finding`s inline, a diff against current state, and Apply / Discard. Degrades to a disabled state + toast on 503 (module absent). Ops/author actions gated via `LensService`. Must pass the no-hardcoded-color CI guard and the axe-core WCAG 2.2 AA gate. |
| **A2** | **Adoption wave 1 — the four panes with matching tools** | M | Pipelines editor (`pipeline_author`), Expectations (`suggest_expectations`), Dashboards/Widgets (`kpi_report_builder`), Queries (`query_author`). These four are first precisely because the backend tool already exists and is already validated — **zero new backend capability**. |
| **A3** | **Context grounding** | S | Pass the pane's current component ref / Dataset ref / selected column as agent **session attributes** so the operator doesn't re-state what the screen already knows. Extends the existing `goalKind` session-attribute seam; no new route. |
| **A4** | **Adoption wave 2 — "explain this screen" everywhere** | S | A read-only L0 affordance on the remaining panes ("what am I looking at / why is this red"), which needs no draft tool at all. Cheapest breadth win; ship after A1–A3 prove the surface. |
| **A5** | **True natural-language authoring — the model hop** | M+L | The phase D8 split out: prose → structured tool args via a single-turn, single-tool, schema-constrained model call, then the same deterministic invoke A1 already does. **Fully scoped in §3.4** — read F1–F4 there before estimating; the hop is cheap and the *nested* schema work is not. |

### 3.3 Invariants this must not break

- **503-degrade everywhere.** No pane may hard-fail because the intelligence module is absent — that
  is the air-gap/lean-core contract.
- **Apply goes through the same validated route a human uses.** No backdoor; `X-Agent-Session`
  attribution preserved so the audit trail still names the actor.
- **Draft ≠ mutation.** A returned draft persists nothing until an explicit Apply.
- **Canonical vocabulary** in every label and prompt (Pipeline, Dataset, Incident, Expectation /
  Alert Rule / Decision Rule, Measure, Collector, Stream / Reference) — `GLOSSARY.md` is binding.

### 3.4 A5 — true natural-language authoring (the model hop), scoped 2026-07-26

**Why this exists.** D8 shipped A1–A3 as *deterministic derive*: pane structure → `runTool` → draft, no
model anywhere. That was the honest first cut, because four of the five L1 tools take structured input.
A5 is the phase that adds the missing **NL → structured-args model hop**. Scoping it was the operator's
instruction ("scope the hop first"), and the investigation below changed its shape and its cost
materially — read the four findings before estimating.

#### 3.4.1 What the investigation found (four findings, two of them plan-changing)

- **F1 — the transport already does schema-constrained function-calling. A5 needs no new mechanism.**
  `ChatRequest` carries `List<ToolSpec>`; `ToolMapping.toLc4j` parses each `ToolSpec.jsonSchema()` into a
  LangChain4j `JsonObjectSchema` and attaches it as `ToolSpecification.parameters(...)`, so the model is
  constrained over the provider's **native tool-calling protocol** and its arguments come back parsed into
  a `Map` (`ToolMapping.toToolCalls`), with a `_raw` fallback key when a small local model emits malformed
  JSON. ⚠ **Do not build the hop as prompt-then-scrape.** `Investigator`'s `gateway.chat` →
  `extractJsonObject` → Jackson pattern is the P1 precedent, and it is the *wrong* one here — it exists
  because a ranked-hypothesis synthesis has no tool schema to constrain. An argument map does.
- **F2 — but the existing schemas constrain only the ARG ENVELOPE; every nested payload is
  `{"type":"object"}`.** `component_draft.config`, `query_author.when`, `pipeline_author.flow` are all
  unconstrained objects whose real shape is described *only in the human-readable `description` string*.
  So function-calling reliably gets the model to emit `{kind, config}` and tells it nothing about what
  belongs *inside* `config`. **This, not the hop, is A5's cost centre**, and it is what makes a naive
  "just switch the tools on in `/ask`" estimate wrong.
- **F3 — the fix for F2 is already designed and never wired: project the schema from `ConfigSpec`.**
  `FieldSpec`'s own Javadoc names its drivers as "in-memory validation, structured API output, **LLM
  grammar-constrained generation**, and generic UI form rendering"
  ([`FieldSpec.java:9`](../../inspecto-config/src/main/java/com/gamma/config/spec/FieldSpec.java)) — the
  third has never been built. A `ConfigSpecs.forType(kind)` → JSON Schema projection (`path` + `type` +
  `required` + `enumValues` + `pattern` + `defaultValue`) constrains the model with **the very spec that
  will judge its output**, which is the single biggest determinant of whether the repair loop converges on
  a local model. Highest-leverage work in this phase. ⚠ `FieldSpec.path` is **dotted**
  (`processing.threads`), so the projection must un-flatten into nested `properties` — mechanical, but it
  is real work, and `visibleWhen` is a render hint that must **not** become a schema constraint.
- **F4 — the offline degrade lies unless it is handled explicitly.** With no local model configured,
  `GatewayFactory.build()` returns a `StubLlmGateway` whose reply is **an explanatory prose sentence**
  ([`GatewayFactory.java:40`](../../inspecto-intelligence/src/main/java/com/gamma/intelligence/GatewayFactory.java)).
  It emits **no tool call**, so the hop sees "no arguments" and would report *"could not understand your
  request"* when the truth is *"no local model is configured"*. The derive path must answer **503** in
  that case, which the surface already latches into a self-explaining disabled state (invariant 2) — so
  handling it honestly needs **no new probe route**.

#### 3.4.2 The shape

One new non-mutating route beside the A1 one, in `AgentRoutes`:

```
POST /agent/tools/{name}/derive     { prompt, args?, kind? }  →  same body POST /agent/tools/{name} returns,
                                                                 plus derivedArgs
```

Server steps: resolve the tool from the existing `runTool` belt index → **refuse mutating (403) before any
model call** → build a `ChatRequest` offering **exactly one tool**, whose schema is the belt spec's, or the
`ConfigSpec`-projected narrowing (F3) when the request names a `kind` → single-turn `gateway.chat` → take
the first `ToolCall`'s `arguments` → merge with the pane's `args` → invoke the tool **deterministically**
through the existing path → return its result verbatim.

Five properties that make this safe and worth stating in review:

1. **Containment by construction.** Offering exactly one non-mutating tool and reading only its arguments
   means the model *cannot* select another tool. This is not policy, it is the request shape. It also means
   the hop never enters the deliberative loop's tool-choice or paraphrase steps.
2. **The model still never writes SQL.** `query_author`'s invariant survives untouched because the hop
   emits only `dataset` + `when`; the server still renders the relation and predicate and `SqlGuard`-checks
   the statement. ⚠ Pin this with a test asserting a model-emitted `text`/`sql` key is **dropped, not
   spliced** — the merge must be schema-keyed, not a blind `putAll`.
3. **Pane context wins over model output on identity fields** (`dataset`, `table`, `target`, the open
   component's id). The screen *knows* these; a model can hallucinate them. This is the A3 rule
   generalized, and it is why the hop takes `args` as well as `prompt` rather than replacing them.
4. **`derivedArgs` is echoed back** so the operator sees what their sentence became before they Apply.
   Non-negotiable for trust once a model is in the loop, and it is the field the specs assert on.
5. **The `_raw` key is the fail-closed signal.** Its presence means the local model produced malformed
   arguments ⇒ **422** with a retryable message. Absence of any tool call with a real gateway ⇒ 422;
   absence with the stub gateway ⇒ **503** (F4). These are different failures and must not share a message.

**Rejected alternatives**, each for a concrete reason:

- **Route it through `POST /agent/sessions/{id}/ask`.** `AgentAskResult` is `{kind,text,citations,
  navigationTarget,artifact}` and cannot carry a draft — `parseArtifact` is dead in practice, per its own
  in-code comment ("no eoiagent tool/session can produce an `INLINE_ARTIFACT` answer today"). This is the
  exact wall that forced A1's route; A5 must not walk back into it.
- **Fold the prose into `args` as an `instruction` key.** That pushes the NL parse *into* the tool and
  breaks the property every one of the five relies on: they are deterministic and model-free.
- **A fourth `<inspecto-ai-*>` sibling.** The A4 siblings are siblings because three of the four things
  `ai-assist` *is* did not apply to them. For NL authoring **all four apply** — same draft, diff, Apply,
  and `canAuthorWorkbench` gate. Only the *input* differs, so this is a mode of `ai-assist`, not a sibling.

#### 3.4.3 Which tools actually get NL, honestly

| Tool | What NL buys | Verdict |
|---|---|---|
| `query_author` | prose → condition tree; the operator stops hand-building a filter tree | **A5.1 flagship.** Smallest nested payload, highest daily value, already adopted on Queries |
| `component_draft` | prose → a whole component config of a named kind | **A5.2.** It has *no authoring logic* — it echoes `config` back with findings — so NL is the only thing that ever makes it an authoring tool. Needs F3 + a repair loop |
| `pipeline_author` | prose → a node/edge graph | **A5.2/A5.3.** Highest ceiling, highest risk: a graph is large and model errors are structural, not field-level |
| `kpi_report_builder` | prose → Measures | **Blocked** on the separate "no viable host pane" row — not an A5 problem |
| `suggest_expectations` | **nothing.** The pane supplies `table`+`column`; profiling is deterministic SQL by design | **Excluded.** Adding a prompt box here would be theatre |

⚠ **`component_draft` NL is a LOOP, not a hop.** One turn yields a probably-invalid config plus anchored
findings; converging needs findings fed back over bounded turns. Budget it as such — this is the difference
between A5.1 and A5.2, and pretending otherwise is how this phase overruns.

#### 3.4.4 Phasing

| # | Slice | Effort | Deliverable |
|---|---|---|---|
| **A5.1** | The hop, on `query_author` | M | `/derive` route + gate order + single-offered-tool call + `derivedArgs` echo + the three distinct failure answers (503 stub / 422 `_raw` / 422 no-call). `ai-assist` gains an optional prompt input, hidden unless the pane opts in. Offline mock branch. |
| **A5.2** | `ConfigSpec` → JSON Schema projection + the bounded repair loop, on `component_draft` | L | The F3 projection (un-flattened, `visibleWhen` excluded) + N-turn repair feeding findings back, with a hard turn cap. This is where the phase's cost is. |
| **A5.3** | `pipeline_author` | M | Only after A5.2 proves convergence on a local model. |

#### 3.4.5 What A5 changes about an earlier decision

**D3 needs one amendment.** It justified ungated availability partly on "the deterministic-derive decision
strengthens this — `suggest_expectations` et al. run **no model at all**, so there is not even a local-
inference cost." A5 reintroduces local inference. The *conclusion* (no edition gate; available wherever
the module is, air-gapped included) still holds — local models remain sufficient, which is the AGT-3 claim
— but the *reason* no longer applies to the NL path, and the 503-on-stub degrade (F4) is what keeps the
promise honest on a build with no model configured.

#### 3.4.6 Exit criteria

- An operator types a sentence on the Queries pane and applies the resulting Query **unchanged**, with
  `derivedArgs` visible before Apply.
- The mutating-tool **403 fires before any model call** — asserted over the real belt's mutating specs, the
  A1 test idiom, so a newly added act tool is covered automatically.
- A model-emitted `text`/`sql` key on a `query_author` derive is **dropped**, proven by a test.
- The three failure answers are distinct and asserted: stub gateway → 503 + latched disabled state;
  `_raw` → retryable 422; no tool call → retryable 422.
- Offline: the mock answers the derive branch; `EgressGuardTest` unchanged (air-gap invariant).
- GAUNTLET green.

#### 3.4.7 Implementation traps

- ⚠ **Route registration order.** `AgentRoutes` registers `POST /agent/tools/(.+)` — a **greedy** pattern
  that will swallow `/agent/tools/{name}/derive`. Register the derive route **first**, exactly as
  `/agent/cases/{id}/similar` had to precede `/agent/cases/(.+)`.
- ⚠ **Two unrelated `ToolSpec` types exist.** The live one is `com.eoiagent.core.ToolSpec`
  (`name, description, jsonSchema, mutating, requiredRole, capability`). `com.gamma.agent.kernel.tool.ToolSpec`
  in `inspecto-agent` is the superseded agent-kernel record — no schema, no `mutating`. Do not touch it.
- ⚠ **`/agent/tools/{name}` enforces no `Role`/`Capability`** — it bypasses `DefaultToolRegistry`, so
  `mutating()` is its only check and `AUTHOR_PIPELINE` is *not* enforced there; the UI's
  `canAuthorWorkbench` and the edition's `ApiContext`/`WriteGates` seam are the authoring gates. The derive
  route inherits this exactly — **do not quietly add a half-gate on one route only**.
- The prompt belongs in `InspectoPromptProfile.systemPrompt(GoalKind)`, which already has a
  `PIPELINE_AUTHOR` stub, and/or a versioned `resources/prompts/*.v1.md` file beside the two P1 prompts.
- `gateway` is already a field on `InspectoIntelligenceAgent` (`GatewayFactory.build()`, overridable with
  `StubLlmGateway` via the package-private test ctor) — the hop needs **no new wiring** to reach a model,
  and `StubLlmGateway.builder().replyToolCalls(...)` makes it deterministically testable.

---

## 4. AGT-6b — Agent graphs (Could, demand-gated)

### 4.1 Where we are vs. the target

**Today** (`runbook_operator`): the model picks a **named, code-defined** runbook + params; the operator
approves the whole resolved plan; steps execute post-approval with a per-step log, halt-on-first-failure,
and durable resume. Seeded: `triage_and_replay`, `rollback_and_rerun`, `reschedule_and_trigger`.

**Target** (L3 in the roadmap): the agent **composes** the step graph, with observation between steps
and branching — *provision → watch → roll back on failure* — rather than choosing from a fixed menu.

### 4.2 The two real blockers (both upstream, in eoiagent)

| # | Blocker | Consequence |
|---|---|---|
| **G1** | The eoiagent approval gate is a **synchronous per-call parked-thread** gate (`DefaultToolRegistry.dispatchMutating` blocks; `AgentApprovals` parks it on a `CompletableFuture`). **Nesting gated calls deadlocks** — which is exactly why runbooks take *one* approval for the whole plan, not one per step. | A dynamic graph that gates each step cannot be built on today's gate. Either the plan-level approval contract is generalized (approve the whole resolved graph up front — extends the runbook precedent, no upstream change) or the gate is redesigned upstream (larger, cross-repo). |
| **G2** | There is **no per-tool `DryRunProvider` seam** through `PlatformBuilder`, so `ApprovalRequest.preview` is empty by design and the operator-facing preview is computed by our own previewer in `AgentApprovals`. | For a *code-defined* runbook this is fine (we know the steps, so we can preview them). For a **model-composed** graph, per-step preview is what makes the plan approvable at all — so the already-open eoiagent `DryRunProvider` backlog item is a **prerequisite for 6b, not merely a refactor**. This reclassification is the main new finding of this plan. |

### 4.3 Recommended first cut (when demand lands)

Do **not** start with free-form model-driven ReAct orchestration. Generalize the runbook instead:

> **"Authored graph, approved whole, executed stepwise."** The model proposes an ordered step list
> constrained to the **existing** act-tool set, validated against a schema (unknown tool / missing
> param ⇒ pre-flight failure that mutates nothing). The operator approves the fully resolved plan —
> every step + args previewed — then execution reuses `RunbookActions`' proven stepwise executor:
> per-step log, halt-on-first-failure, durable checkpoint/resume, one gate (never nested).

This keeps G1 out of the critical path, makes G2 the single genuine prerequisite, and preserves the
property that matters: **the operator sees exactly what will run before anything runs.**

**Gate to start:** named client demand for orchestration beyond the seeded runbooks (D5).

---

## 5. User experience

**AGT-6a, the loop the operator sees** (Pipelines pane, illustrative):

1. Operator is editing a Pipeline. They invoke the inline surface and type *"add a dedup step keyed on
   `msisdn`"*.
2. The agent returns a **draft** — validated by `component_draft` + parsed/simulated by
   `pipeline_author` — with per-node relation counts if sample rows are available.
3. The pane renders the draft as a **diff against the current config**, with any `Finding`s anchored to
   the offending field (the same anchoring the validator repair loop already produces).
4. Operator clicks **Apply** → the normal validated `PUT` (`If-Match`) runs, audited, attributed. Or
   **Discard** → nothing happened; nothing was ever persisted.
5. Module absent or act tier off ⇒ the surface is a disabled affordance with an explanatory toast, and
   the pane behaves exactly as it does today.

**What the operator never sees:** an inline box that acts on its own, an unvalidated draft presented as
safe, or model-written SQL.

**AGT-6b, when built:** the plan appears in the existing `/approvals` inbox as a resolved step list with
per-step previews; execution progress and halt/resume state are visible; the `/autonomy` ledger records
what/why/spend. **No new operator surface is required** — both already exist.

---

## 6. Exit criteria

| Phase | Exit criterion |
|---|---|
| **A1** | The shared surface renders draft + anchored findings + diff + Apply on one pane; 503 degrade verified; `LensService` gating verified; no-hardcoded-color guard and axe-core gate green; Vitest specs cover wire contract, gating, apply, failure-degrade, a11y. |
| **A2** | On each of the four panes, an operator authors a valid component from a natural-language request and applies it **unchanged** — with no new backend route or tool added. |
| **A3** | The agent resolves the pane's current component/Dataset without the operator re-stating it; verified by a session-attribute assertion, not by prompt inspection. |
| **A4** | Every remaining pane offers a read-only explain affordance that degrades cleanly. |
| **A5** | An operator authors a Query from a typed sentence and applies it unchanged, with the derived args shown before Apply; the mutating-tool 403 fires **before** the model call; a model-emitted SQL key is dropped; the three failure answers (no model / malformed args / no tool call) are distinct. Full list: §3.4.6. |
| **6b** | A model-proposed step list is schema-validated, approved as one resolved plan, executes stepwise with halt + durable resume, and mutates nothing on a pre-flight failure. |
| **All** | GAUNTLET green (reactor tests + UI lint/test/build). Air-gap invariant (`EgressGuardTest`) unchanged. |

---

## 7. Risks & anti-goals

- **Surface sprawl** — an inline box on 30 panes with 30 slightly different behaviours. Mitigation: A1
  ships **one** shared component; panes adopt it, never fork it.
- **Trust inversion** — an inline AI that mutates state makes the whole product feel unsafe. Mitigation:
  draft-only inline; apply is an explicit human action through the audited route.
- **Prompt-surface creep** — per-pane bespoke prompts becoming unmaintainable. Mitigation: versioned
  prompts under `resources/prompts/` (the existing P1 convention), pane context passed as data.
- **Air-gap regression** — adding a hosted-model dependency to make inline authoring feel faster would
  break AGT-3, our strongest commercial claim. **Non-negotiable: local models remain sufficient.**
- **Anti-goal: free-form autonomous orchestration.** Model-driven ReAct over mutating tools is not on
  this plan at any phase; §4.3 is the deliberate ceiling.
- **Anti-goal: fine-tuning/training.** Unchanged from `ROADMAP.md` §8.

---

## 8. Decision asks (pending sign-off)

| # | Ask | Recommendation |
|---|---|---|
| **D1** | ✅ **ANSWERED 2026-07-26: `<inspecto-ai-assist>`.** Standalone component in the shared design system (`inspecto/ai-assist/`), adopted by panes, never forked. Listed in the `/design` gallery. | Confirm naming with the UI owner; keep it a standalone component in the shared design system, not a per-feature widget. |
| **D2** | ✅ **ANSWERED 2026-07-26: the plain validated route.** The surface only emits `(applyDraft)`; **the pane** writes through its own existing route, so the human is the audited actor. Enforced three ways: the surface has no write path at all, `runTool` refuses mutating tools (403), and each pane's apply handler stops at its form/model with `dirty` set so the operator presses the existing Save. | **Plain validated route.** The human clicked Apply, so the human is the actor — simpler, and keeps the approvals inbox meaningful for genuinely agent-initiated work. |
| **D3** | ✅ **ANSWERED 2026-07-26: available wherever the intelligence module is**, air-gapped included. No edition gate was added; the deterministic-derive decision strengthens this — `suggest_expectations` et al. run **no model at all**, so there is not even a local-inference cost. | Available wherever the intelligence module is, i.e. including air-gapped — it is draft-only, so it carries no security weight. Packaging value sits in Tier B messaging, not in a hard gate. |
| **D4** | ✅ **ANSWERED 2026-07-26, and the pane list changed** — order accepted, but two panes moved to the host that actually holds the context: **Pipelines** editor (`pipeline_author`) · **Expectations** → the **`ExpectationFormDialog`**, not the list pane (the list has no row selection, so no `table`/`column`; the dialog has both as controls) · **Queries** (`query_author`) · **Dashboards → deferred** (`kpi_report_builder` has no host — see the as-built note at the top). | Accept; it is ordered by existing-tool maturity, not guesswork. |
| **D8** *(new)* | **A1's input model** — the plan assumed free text, but four of five tools take structured input. | ✅ **ANSWERED 2026-07-26: deterministic derive first.** Pane structure → `runTool` → draft + findings + diff + Apply, no model. NL authoring is **A5**, scoped separately with its model hop made explicit. |
| **D5** | What counts as the demand trigger for 6b? | A named client asking for orchestration beyond the three seeded runbooks. Until then 6b stays parked. |
| **D6** | Client segment for the §2 framing — telecom vs. general regulated enterprise changes the emphasis. | Needs product input; the air-gap moat argument holds either way. |
| **D7** | Should the eoiagent `DryRunProvider` seam (§4.2 G2) be raised from "low priority refactor" to "6b prerequisite" in the BACKLOG? | **Yes** — it is reclassified by this plan. Still not urgent while 6b is parked. |
| **D9** *(A5)* | **Does A5.2 build the `ConfigSpec` → JSON Schema projection (§3.4 F3), or do we accept prose-guided nested payloads plus the repair loop?** This is the phase's effort fork: the projection is the difference between "M" and "L". | **Build it.** `FieldSpec` already declares grammar-constrained generation as one of its drivers, and constraining the model with the same spec that judges its output is what decides whether the loop converges on a local model. Skipping it makes A5.2 look cheaper and behave worse. |
| **D10** *(A5)* | **Which panes get the prompt box** — every pane that adopted `ai-assist`, or opt-in per pane? | **Opt-in per pane**, and explicitly **not** Expectations: `suggest_expectations` profiles real data with deterministic SQL from a `table`+`column` the dialog already holds, so a prompt box there is theatre. Queries first (A5.1). |
| **D11** *(A5)* | **Turn cap for the `component_draft` repair loop** — how many local-model round trips before it gives up and shows the operator the findings as-is? | **Hard cap of 3**, then hand over the best draft plus its anchored findings. The surface already renders findings for human repair, so the fallback is the existing A1 experience, not a dead end. |

---

*AGT-6a is `Should`; A1–A4 shipped 2026-07-26. **A5 is scoped in §3.4 and ready to schedule pending
D9–D11**; the `kpi_report_builder` host remains open as a separate new-flow item. AGT-6b is `Could`,
parked behind D5 + G2.*
