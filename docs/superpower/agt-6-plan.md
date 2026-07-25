# AGT-6 Plan — Inline AI Authoring & Agent Graphs

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

### 3.3 Invariants this must not break

- **503-degrade everywhere.** No pane may hard-fail because the intelligence module is absent — that
  is the air-gap/lean-core contract.
- **Apply goes through the same validated route a human uses.** No backdoor; `X-Agent-Session`
  attribution preserved so the audit trail still names the actor.
- **Draft ≠ mutation.** A returned draft persists nothing until an explicit Apply.
- **Canonical vocabulary** in every label and prompt (Pipeline, Dataset, Incident, Expectation /
  Alert Rule / Decision Rule, Measure, Collector, Stream / Reference) — `GLOSSARY.md` is binding.

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
| **D1** | Name + shape of the shared inline surface (`<inspecto-ai-assist>`?) — must be settled against the `angular-ui` shared-design-system conventions before A1. | Confirm naming with the UI owner; keep it a standalone component in the shared design system, not a per-feature widget. |
| **D2** | Does inline **Apply** go through the plain validated route (human is the actor) or through L2 `component_apply` (agent is the actor, approval inbox)? | **Plain validated route.** The human clicked Apply, so the human is the actor — simpler, and keeps the approvals inbox meaningful for genuinely agent-initiated work. |
| **D3** | Is AGT-6a **Tier B (Standard+)** or available in Personal/air-gapped too? | Available wherever the intelligence module is, i.e. including air-gapped — it is draft-only, so it carries no security weight. Packaging value sits in Tier B messaging, not in a hard gate. |
| **D4** | A2 pane order — confirm Pipelines → Expectations → Dashboards → Queries. | Accept; it is ordered by existing-tool maturity, not guesswork. |
| **D5** | What counts as the demand trigger for 6b? | A named client asking for orchestration beyond the three seeded runbooks. Until then 6b stays parked. |
| **D6** | Client segment for the §2 framing — telecom vs. general regulated enterprise changes the emphasis. | Needs product input; the air-gap moat argument holds either way. |
| **D7** | Should the eoiagent `DryRunProvider` seam (§4.2 G2) be raised from "low priority refactor" to "6b prerequisite" in the BACKLOG? | **Yes** — it is reclassified by this plan. Still not urgent while 6b is parked. |

---

*AGT-6a is `Should` and ready to schedule pending D1–D4. AGT-6b is `Could`, parked behind D5 + G2.*
