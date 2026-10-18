# Agentic investigation — design (`AGENTIC-INVESTIGATION-1`)

**Status:** 🟡 DESIGN 2026-10-18 — nothing built. §8 decisions taken by the assistant under operator delegation (2026-10-18) — **PENDING OPERATOR CONFIRMATION**; S0 (decisions + GLOSSARY) done, S1+ not started.
**Row:** `docs/BACKLOG.md` §4, `AGENTIC-INVESTIGATION-1` (P2, competitive gap AA).
**Competitors:** Mobileum RAID Agentic and Subex AI Agent Squads (see `stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md`, rows AA and the gap table).

## 1. Goal

An agent works one Incident. It produces an **Incident Brief**, which has four parts:

1. **Why it fired.** The trigger Signal, the Expectation or Decision Rule that raised it, the threshold and the observed value.
2. **Evidence.** Bounded queries, timelines, batch diffs, similar past Incidents and linked entities. Each item is cited.
3. **Value at risk.** A monetary or unit amount, with the formula and inputs shown.
4. **A proposed next action.** This is one registered Consequence with arguments. A **human** approves it under four-eyes before it runs.

⛔ **The agent never runs a Consequence itself.** Its only mutating act is to *file a proposal*. A person runs it by approving the proposal.

## 2. What exists (grounded 2026-10-18)

| Seam | Where | What it gives us | What it lacks |
|---|---|---|---|
| Intelligence agent + tools | `features/inspecto-intelligence/.../agent/InspectoIntelligenceAgent.java`, `pack/InspectoTools.java` | Read tools: `signals_query`, `signal_timeline`, `timeline_build`, `diff_batches`, `anomaly_scan`, `status_get`, `docs_search`, `glossary_lookup`; `pack/Investigator`, `pack/Playbooks` | No tool that takes an Incident ref; no value-at-risk |
| Triage | `intelligence/triage/` (`Incident` record, `TriageQueue`, `TriageRun`, `TriageRunStore`, `TriageRunSimilarity`, `FeedbackStore`) | A triage run per Incident, similarity recall, analyst feedback | `Incident` is `(incidentRef, triggerSignal, params)` only |
| Persistent Incidents | `platform/inspecto-engine/.../objects/IncidentAccess.java`, `job/IncidentOpenJob.java`; purge in `inspecto-ops/.../IncidentPurgeTask.java` | Durable Incident objects and retention | — |
| Cases | `features/inspecto-case-management/.../ops/cases/` | Case escalation target | — |
| Approval gate | `intelligence/action/AgentApprovals.java` (an `ApprovalHandler` + `DecisionStore`), `ApprovalStore` (JSONL ring); routes `AgentRoutes` `/agent/approvals*` | Parked gate thread, dry-run preview, resume token after restart, fail-closed timeout | ⚠ **No four-eyes.** `decidedBy` is free text defaulting to `operator`; the requester is `agent:<run>`, not a person; gated only by `canAdminister` |
| Runbooks | `action/RunbookActions.java` (`runbook_operator`) | One approval covers a whole declared plan | — |
| Consequence registry | `platform/inspecto-engine/.../decision/` (`ConsequenceProvider` SPI, `Consequences`, `BuiltInConsequences`, `ConsequenceContext`); run from `DecisionRoutes.executeOne` | Kinds: `emit-signal`, `create-alert`, `start-job`, `trigger-pipeline`, `create-incident`, `invoke-api`, stubs | Four-eyes exists only on `invoke-api` |
| Four-eyes | `inspecto/.../control/ApprovalPolicy.java`, `ApproverCheck.java`; `inspecto-action-requests` (`ActionRequests`, `InvokeApiConsequence` with `DecisionRuleGuard.makers`) | Maker ≠ approver, none-eligible stays pending; reused by entity lists, screening, reg-reporting, anomaly lists | — |
| Value at risk | — | Nothing computes a monetary amount on an Incident or Expectation result | Greenfield |

**Constraints carried in:**

- **AGT-6b.** The upstream `ApprovalHandler.onApprovalRequested` is **synchronous per call**, so nested gates deadlock. Multi-step plans must be one declared plan under one approval (the `RunbookActions` pattern), never free-form ReAct over mutating tools. Reopen check: `archived-documents/plans-archive/agt-6-plan.md` §4.
- **AGT-5.** `PlatformBuilder` exposes no `dryRunProvider(...)`, so `AgentApprovals` stays the previewer. `incident_explain` was cut while it waited on the eoiagent host seam.

## 3. Design

### 3.1 The key move: proposals leave the agent gate

The design does **not** route the proposed action through the eoiagent approval gate. A parked gate thread works for a chat turn. It does not work for a four-eyes decision that may take hours or days, needs a second, different person, and must survive restarts and the 1 h resume-token TTL.

Instead, the agent's last act is a non-blocking tool, `brief_propose`. That tool writes an **Action Request** (the `inspecto-action-requests` four-eyes store already used by `invoke-api`) of kind `consequence`, with these fields:

- `consequence`: a registered `ConsequenceProvider` id, plus arguments
- `incidentRef` and `briefId`
- `requestedBy`: the **human** who started the investigation (the authenticated Subject), with `agent:<run>` recorded as the author
- `dryRun`: a preview, where the provider supports one

Approval runs through `ApprovalPolicy`/`ApproverCheck`. The approver must not be `requestedBy`, so the person who asked the agent cannot approve its proposal. On approval, the server, not the agent, calls `Consequences.execute` with the approver as the actor. This removes the deadlock (no nested gate), gives real four-eyes, and makes the result durable.

### 3.2 Read-only investigation as one declared plan

The investigation steps are **read-only**, so they need no gate. They run as a fixed **playbook** (an extension of `pack/Playbooks`/`Investigator`) per Incident category. That keeps the run bounded, testable and cheap. The model chooses the order and the summary text; it cannot invent tools. Tool budget: N calls and a row cap per query. The read tier is the existing `InspectoTools` set, plus two new read tools:

- `incident_get`: the Incident, its trigger Signal, its rule, and the threshold against the observed value
- `incident_similar`: wraps `TriageRunSimilarity` and the feedback outcome of past runs

### 3.3 Value at risk

This is declared, never inferred by the model. An Expectation or Decision Rule may carry an optional `valueAtRisk` block: `measure` (a Measure over the failing rows, e.g. `sum(charge_amount)`), `currency` or `unit`, and an optional `rate` multiplier. The investigation runs it as a bounded query and shows the formula, inputs and row count. Where no declaration exists, the Incident Brief says *"not declared"*. It never shows a model-guessed number.

### 3.4 The Incident Brief record

The record is a persisted `IncidentBrief` (`id, incidentRef, startedBy, model, why, evidence[], valueAtRisk?, proposal?, status, createdAt`), and each evidence item holds `tool, args, resultDigest, citation`. It lives beside `TriageRunStore` and follows the Incident's retention through `IncidentPurgeTask`, which cascades to it. The SPA shows it on the Incident page: an "Investigate" button, then the four sections, then a proposal card linking to the Action Request.

## 4. Slices

| Slice | Content | Size | Depends on |
|---|---|---|---|
| S0 | Decisions §8; GLOSSARY entry for **Incident Brief** | S | — |
| S1 | `incident_get` + `incident_similar` read tools; Incident-scoped playbook; persisted `IncidentBrief` (why + evidence only) | M | S0 |
| S2 | Incident page "Investigate" panel (read-only render, citations) | M | S1 |
| S3 | `valueAtRisk` declaration on Expectation / Decision Rule specs (`ConfigSafetyValidator` rule), bounded execution, display | M | S0 |
| S4 | `brief_propose` → Action Request of kind `consequence`; four-eyes via `ApprovalPolicy`; server-side execute on approval; dry-run preview where available | L | S1 |
| S5 | Close the loop: approval or denial is written to `FeedbackStore` so `incident_similar` learns outcomes; purge cascade | S | S4 |
| S6 | (Could) batch: investigate the top-N open Incidents by value at risk on a schedule — still propose-only | M | S3, S4 |

Each slice ships alone: S1+S2 already close most of the "explains why it fired" gap.

## 5. Seams touched

- `InspectoTools` (2 read tools + 1 propose tool) · `pack/Playbooks`
- `triage/` (new `BriefStore`)
- `inspecto-action-requests` (new request kind; generalize the execute path from `invoke-api` to any registered Consequence)
- `ApprovalPolicy` / `ApproverCheck` (reused, unchanged)
- Expectation / Decision Rule config specs + `ConfigSafetyValidator`
- New routes: `POST /incidents/{ref}/briefs`, `GET /incidents/{ref}/briefs`. Each needs an `openapi-v1.json` entry and the four route gates.
- `IncidentPurgeTask` cascade
- SPA Incident page

## 6. Risks

| Risk | Mitigation |
|---|---|
| The model invents causes or numbers | Every claim cites an evidence item. Value at risk comes only from a declared formula. The "why" section is built from `incident_get` facts, with model prose layered on top and marked as such. |
| Self-approval (same person asks and approves) | `requestedBy` is the human Subject; `ApproverCheck` excludes them; none-eligible stays pending |
| Deadlock / lost approval on restart | No nested gate: the proposal is a durable Action Request, not a parked thread |
| Data exposure in evidence (masked entity keys) | Tools run as the starting Subject; evidence respects `canRevealLinkEntities` masking (`BI-QUERY-MASKING-1`) |
| Cost / runaway runs | Fixed playbook, tool-call budget, row caps, one run per Incident at a time |
| Proposal goes stale (the Incident resolved before approval) | On approve, re-check that the Incident is still open; otherwise mark the proposal superseded |
| `AgentApprovals` has no four-eyes today | Out of scope here; filed as a follow-up (§9) |

## 7. Explicit non-goals

- Autonomous Consequence execution, in any edition.
- Multi-step mutating plans (AGT-6b stays gated).
- Embedding or vector recall.

## 8. Operator decisions

1. **Proposal path.** Use Action Requests (four-eyes, durable) or the existing `AgentApprovals` gate? **Recommend Action Requests.** The gate has no four-eyes and parks a thread.
2. **Who is the "maker" for four-eyes?** **Recommend the human who started the investigation.** The agent is recorded as author only. An unattended (S6) run is made by a configured service principal, and then any human may approve.
3. **Which Consequences may be proposed?** **Recommend an allow-list per Space**, default `create-alert`, `start-job`, `trigger-pipeline`, `create-incident`/case escalation. Exclude `invoke-api` until its own four-eyes path is unified.
4. **Value-at-risk source.** **Recommend a declared `valueAtRisk` block only**; never a model estimate.
5. **Incident Brief trigger.** **Recommend on demand (button) first**; scheduled top-N (S6) only on demand from a customer.
6. **Edition.** **Recommend Advanced (and Preview)**, matching the intelligence module.
7. **Model locality.** **Recommend keeping the local-models-only cut by default**, with a hosted model as an explicit Space opt-in because evidence holds customer data.
8. **Retention.** **Recommend that an Incident Brief follows its Incident's retention and legal hold.**

### 8.1 Decisions taken (assistant-decided under operator delegation 2026-10-18 — PENDING OPERATOR CONFIRMATION)

**Naming (found while grounding, not in the original list).** ⛔ **Investigation** is already the Link Analysis enquiry object (`GLOSSARY.md` §11; routes `/inv/*`), and the Triage entry bans the word for the Assistant. The agent's output is therefore the **Incident Brief** (Instance; its Type is the existing **Triage** playbook). This doc was renamed to match: `IncidentBrief`, `BriefStore`, `briefId`, `brief_propose`, `/incidents/{ref}/briefs`. *Reverse:* a global rename in this doc and the GLOSSARY entry, nothing is built.

| # | Decision | Rationale (grounded) | Reverse by |
|---|---|---|---|
| 1 | **Action Requests, as recommended.** ⚠ Correction: today an Action Request is an *outbound API call* (`ActionRequests`, `ActionDispatcher`), not a generic Consequence. S4 must add a `consequence` kind; it must reuse the same HMAC-signed per-Space store, four-eyes and expiry, never a new approval path. | `AgentApprovals` has no four-eyes and parks a thread (AGT-6b). | Route proposals through `AgentApprovals` (loses four-eyes) |
| 2 | **Maker = the authenticated human who started the Brief; agent is author only.** An unattended run (S6) uses a configured service principal, and then any human may approve. | `ApproverCheck` already excludes the maker. | — |
| 3 | **Per-Space allow-list, default empty set = nothing proposable until an admin lists kinds; suggested list `create-alert`, `start-job`, `trigger-pipeline`, `create-incident`. `invoke-api` excluded.** (Tightened from "default on": fail closed.) | A new capability that can cause side effects should be opt-in per Space. | Ship the suggested list as the default |
| 4 | **Declared `valueAtRisk` block only; never a model estimate.** Absent = "not declared". | The wrong number is worse than none. | — |
| 5 | **On demand (button) only.** S6 not scheduled. | No unattended cost. | — |
| 6 | **Advanced (and Preview) only**, matching the intelligence module. | Build flavour, not an `if`. | Add to Standard |
| 7 | **Local models only by default; hosted model is an explicit per-Space opt-in.** | Evidence holds customer data. | Allow hosted by default |
| 8 | **A Brief follows its Incident's retention and legal hold; purge cascades.** | One lifecycle, no orphan evidence. | — |

**Safety invariants (fixed, not decisions):** the agent never executes a Consequence, it only files a proposal; every claim cites an evidence item; every gate fails closed (no model, no Subject, no allow-list entry, no eligible approver = no proposal / stays pending).

**S0 scope:** these decisions and the GLOSSARY entry **Incident Brief**. S0 adds **no code, route or config key**, so it has no tests; the first code is S1.

## 9. Follow-ups (not this row)

- `AgentApprovals` four-eyes: bind `decidedBy` to the authenticated Subject and exclude the starting user.
- Generalize the `invoke-api` four-eyes path so every Consequence kind can be requested the same way.
