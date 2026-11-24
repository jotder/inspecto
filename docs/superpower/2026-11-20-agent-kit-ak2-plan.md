# Agent Kit AK-2 — agents propose changes, people approve them, one role at a time (plan)

> **Status: APPROVED for phase 1 on 2026-11-20 — D-AK1…D-AK4 signed (operator, "Sign recommended");
> D-AK5, D-AK6 and D-AK7 signed 2026-11-24 (§8; D-AK5 as "allow within rights", machines held, not the two tiers); AK-2a and AK-2b built 2026-11-20 (§6) — phase 1 is built;
> its close was built 2026-11-24 (§6.1: Agent Skills, golden tasks and grader, the Handbook how-to, a security review)
> and the first live golden run (6 of 7, 0 safety failures), the four vendor packages and the Kit in the bundle followed
> the same day; **phase 1 is complete for Claude** (a second golden run through the Claude Code plugin's own OAuth: 6 of 7,
> 0 safety failures); the Gemini CLI, Codex CLI and local-model rows stay open (§6.1); phases 2–3 remain.
> As built, beyond the text below: the allowed bucket also holds `POST /db/query` (the read the AK-1
> `query_dataset` tool replays, which the gate would otherwise refuse), and an agent token drops
> `canApproveChanges`, `canApproveLinkExpansions` and `canRevealLinkEntities` at authentication, so every
> in-handler check fails closed too, not only the route gate (§1: "an agent never … reveals a masked value"). Built phase by phase, one agent role per
> phase (§1.1, operator 2026-11-20).** Phase AK-2 of
> [`2026-11-19-user-enablement-and-agent-kit-plan.md`](2026-11-19-user-enablement-and-agent-kit-plan.md) (§7, §8),
> on top of AK-1 as built ([`spec-mcp.md`](../okf/product/module/mcp/spec-mcp.md)). Board row: `AGENT-KIT-1`.
> Signed inputs (2026-11-19): **D-UE4** the module ships from Standard up, never on Basic; **D-UE5** both identity
> modes, delegated person first; **D-UE6** every agent save goes through approval, and promotion and outbound actions
> need a human approver. Inventory checked against `origin/master` on 2026-11-20; each "today" names where it was found.

## 1. The goal

An AI agent outside Inspecto (Claude, Gemini, OpenAI, a local model) can **change** a running Inspecto, not only read
it, without becoming a way around its controls:

- an agent **validates** and **dry-runs** what it builds, then **proposes** it; a person **approves** it;
- an agent **proposes** an outbound call; a person **approves** it;
- an agent **never** approves, reveals a masked value, administers a Space or promotes on its own;
- every row in the audit trail names **the person and the verified agent client**.

Done means: the AK-2 MCP Tools below work end to end against a Standard install with a real identity provider; an
agent's write lands only as a Pending Change or Action Request; the same token calling the REST routes directly is held
the same way; a security review finds nothing at confidence 8 or above.

### 1.1 Four agent roles, phase by phase *(operator, 2026-11-20)*

The agentic intention is an agent that works as four people do. They are built **one per phase**, each phase shipping
its identity and gate work, its MCP Tools, its Agent Skills (AK-3) and its golden tasks (AK-4) together, so every
phase ends in a role an agent can actually do:

| Phase | Role | Where the agent runs | What it does | What it needs beyond the phase before |
|---|---|---|---|---|
| **1** | **Developer** | outside, through the Agent Kit | builds Spaces and Apps: Pipelines, Expectations, Alert Rules, Widgets, Dashboards, Jobs; validates, dry-runs, proposes | the agent identity (§4.2–§4.3), the identity-keyed hold (§4.1), the config write tools (§4.4) |
| **2** | **Operations support engineer** | outside; often unattended | watches health, runs and freshness; diagnoses a failed run; re-runs, resumes, drains, reprocesses | operational actions (§4.6), the machine agent capability set (§4.5); product gaps `QUARANTINE-RELEASE-1`, `BACKUP-DATA-1` |
| **3** | **Incident and Case analyst** | outside, and inside as the Assistant's Incident Brief | triages Incidents, writes Briefs, comments, assigns, proposes a transition or an outbound action | case actions (§4.6); product gap `CASE-TRANSITION-FOUR-EYES-1` |
| **4** | **Helpdesk** | **inside** Inspecto, as the Assistant | explains the screen or Widget a user is looking at, grounded in the Handbook and the user's own rights | no write path: its own plan, [`2026-11-20-assistant-screen-help-plan.md`](2026-11-20-assistant-screen-help-plan.md) |

Phases 1–3 share this plan's gate (§4.1); phase 4 shares only the masking and audit rules (§5). Phase 4 depends on no
earlier phase and may be pulled forward by the operator. Seed recipes for phases 2 and 3 exist as this repository's
own `incident-investigate` and `case-analysis` skills; the Agent Skills are rewritten from them for a customer's install.

### 1.2 The analytics builder — samples and specs in, a built Space and its solution document out *(operator, 2026-11-21)*

The developer role's main job. The person gives the agent **sample data files, data specs or a Grammar, and a goal** (a
KPI, a Report, a Reconciliation, a final Dashboard); the agent **plans first, builds, proves, and writes the solution
document**. It must work on an **installed distribution with no source tree**: the running server's REST API, the
Handbook the server ships at `/help`, and the server's own validation messages are its only references.

| Stage | The agent's work | What exists today (checked by name, not exercised) | What is missing |
|---|---|---|---|
| 1 Goal | turn the request into a structured one | the **Requirement** concept (KPI, Report, Reconciliation, Rule) | the agent-readable shape of a request |
| 2 Samples | choose the Parser, state the Grammar, infer types | built-in Parsers, Grammars, `SampleProfiler` in the engine, the server's `/onboard/…` routes (the console's Add data) | whether the onboarding routes already profile and draft enough for an agent; an MCP Tool and an upload route for a sample file (results are capped at 256 KiB) |
| 3 Land | Collector, Schema, import Pipeline, rejected-row check | the how-tos and Pipeline tools | — |
| 4 Shape | pick the Steps for matrix, summary and cube Datasets | `transform.summarize`, `matrix.pivot`, `matrix.unpivot`, `join`, `running`, `analytics.rank`, … | the recipe library: which Step for which goal |
| 5 Outputs | Measures, KPIs with targets, Widgets, Dashboards, Reports, Reconciliations | all authored as config | Insight Profiles and KPI History are vocabulary only |
| 6 Prove | check the result against the specs | Expectations, dry runs, the Handbook's check-block style | a mechanical check loop the agent runs until green |
| 7 Document | the plan, then the solution document | nothing | a plan stored in the Space; a solution document **generated from the Space's actual config**, as the Handbook's reference pages are generated |

**Skills and MCP are the foundation, not the whole answer.** A Skill is knowledge and an MCP Tool is an action; this job
also needs the four things above — a way to *see* the data (the profile or onboarding step), a *plan object* that
outlives one chat, a *mechanical check loop*, and *golden tasks* (AK-4) that measure how often an agent gets it right,
per model. Long builds may outlast a single tool call; the MCP progress and tasks features (deferred in §6) are decided
when the spike shows whether it matters.

**No-source distribution — how the agent works:** the Handbook at `/help` for the exact calls; the server's API contract
if it serves one; the onboarding routes to draft the import from a sample; the config routes with `/validate` and the
Pipeline dry run before each save; Dataset queries through the API to verify; the plan and solution document as files
for the person (until a plan is stored in the Space). **Limits without source:** no config-key reference yet (the
agent learns keys from Handbook examples and 422 messages); no packaging as an App or Space Template
(`APP-KIT-DELIVERY-1`); no new Parser (`PARSER-SPI-KIT-1`). **On a real install** an agent writes only through the
phase-1 gate (§4.1): every change is a proposal another person approves.

**Evidence first — the spike (2026-11-21):** an agent session on a clean installed Standard bundle, with only the
`order-to-cash` sample files and a plain-language spec (the finished Space withheld as the answer key), builds the
dashboard, Reconciliation and Report and writes the solution document without reading any source or repository doc.
Its gap list decides the shape of this track. The spike ran before AK-2a's gate, so it measures the build path, not
the approval path. It had two variants: **A**, the API and the Handbook only; **B**, the same build with read access to
the install root (its docs folder, the shipped example Spaces and templates, the Spaces folders), applying every
change through the API.

**Spike result (2026-11-21, one run each; the spike's own report, not re-run):** both variants reached the **correct
numbers** on a Standard install — status counts, the unmatched total 1784.5 USD (six incidents 1725 plus 59.5), the
reconciliation breaks O-1041, O-1088, O-9001, O-9002, O-1017, O-1022, O-1050, O-1064 — in **234 API calls (A) and 196
(B)**, with silent traps along the way.

| Deliverable | A: API + Handbook | B: + install-root read access |
|---|---|---|
| orders and payments Datasets | pass | pass |
| open-items Dataset derived on the platform | **fail** — a customer-side script, a static snapshot | pass — the recipe is in the shipped templates |
| Expectations | pass | pass |
| KPI / Measures | partial — no Dataset Measures route | pass (named Measures unverified) |
| Report | pass | pass |
| Reconciliation | partial — four break types right through an undocumented trick | partial — the cardinality break never emitted |
| Dashboard | partial — four tiles, never rendered | partial — four tiles, never rendered |
| solution document generated from the server | pass | pass |

**What the spike settles for this plan**
- **Skills + MCP + today's routes are close, not enough.** The build is possible; what is missing is one authoring
  recipe for a derived Dataset (a page and a validate route), strict validation (an unknown key rejected) with
  validate and dry run on every component, a whole-period Reconciliation (`RECON-CROSS-PERIOD-1`), honest upload
  defaults (append, decimal), the API contract and the shipped templates served as readable reference, and an
  approval-policy preset so "governed" means gated.
- **File read access is worth giving; file write is not** (D-AK7). Read access closed the largest gap (the derived
  Dataset recipe) and part of two more; B never needed a file write.
- **A Standard install is not gated by default** (`APPROVAL-POLICY-PRESET-1`): the approval policy and approver list
  were empty, so a person's token applied every change at once. An agent-client token is held regardless (AK-2a); the
  preset closes the same gap for people.
- **Golden-task caveat (AK-4):** Handbook tutorials 2–4 print the expected answers for this very data, and both agents
  used them as an answer source; and A's server exposed the shipped `order-to-cash` template over the API. A golden task
  must use data and a Space the Handbook does not already answer.
- **Not covered:** neither run rendered the Dashboard or ran the shipped template's tile values live; the approval path
  (a held proposal approved by a second person) was not exercised; the spike's agents loaded this repository's
  `CLAUDE.md` at start though both reported no repository reads.
- **Gaps filed on the board (2026-11-21, unverified against the code):** `DERIVED-DATASET-AUTHORING-1`,
  `UPLOAD-DEFAULTS-1`, `SPACE-CREATE-NAME-1`, `UNKNOWN-KEYS-SILENT-1`, `COMPONENT-VALIDATE-ALL-1`,
  `BUILDER-API-PAPERCUTS-1`, `REPORT-DASHBOARD-AUTHORING-1`, `INCIDENTS-READ-ROUTE-1`, `APPROVAL-POLICY-PRESET-1`.
  The reconciliation finding adds evidence to `RECON-CROSS-PERIOD-1` and the missing uniqueness check to
  `EXPECTATION-UNIQUE-KIND-1`. Full record: the spike's `gaps.md` (scratch folder `C:\sandbox\spike-inputs`, not in the
  repo).

**Two postures for an agent with file access** *(operator, 2026-11-21)*:

| Posture | Reads files | Writes files | Applies a change |
|---|---|---|---|
| **Sandbox or developer install** | freely | directly into a Space folder | by file; no approval, no audit — nothing governed is at stake |
| **Governed install** | freely: shipped docs, example Spaces and templates, to learn the config keys | only in a scratch copy, to draft | **through the API only** (`/validate` and the dry run first), so every change is held for another person (§4.1) |

Why a governed install cannot take direct file writes: the approval hold, four-eyes and `agentClient` audit live in the
routes, so a file written into a Space folder skips all three; nothing validates the file until the server loads it; and
the shortcut of building a Space folder and importing it as a bundle is refused to an agent token by the phase-1 gate.
File access therefore closes the "no config-key reference" gap (the agent learns keys from shipped examples) without
widening what an agent may change. Recommended: files for learning and drafting in both postures, the API for applying
on a governed install. The phase-1 gate governs an agent's **API** calls; it cannot see a file an agent writes on the
host. So the posture is set by who may write into the install root, not by the server — a governed install gives the
agent read access to the install root and no write access to its Space folders (decision D-AK7, signed 2026-11-24 as
recommended, §8).

### 1.3 Agent-friendly documentation — a docs track under the analytics builder *(operator, 2026-11-21)*

The Handbook already has the bones an agent needs: executable examples (the check blocks), a manifest, a header on every
page. The spike showed where it falls short. From the spike's report (not re-checked against each page); the Agent
Skills of §1.1 **link** to these pages rather than copy them, so a docs fix reaches agents with no Skill change.

| Change | Why (spike) | Board row |
|---|---|---|
| A **component shape reference generated from the code**: every kind's keys, types, required or optional, defaults, an example | agents learned shapes from 422 text; also the missing config-key reference; a generated page cannot drift | `COMPONENT-REFERENCE-GENERATED-1` |
| **Recipes keyed by goal** ("daily count by region with a target", "join two Datasets", "reconcile across days"), with check blocks | the derived-Dataset recipe was the largest gap and lived only inside shipped templates | `GOAL-RECIPES-1` (first: `DERIVED-DATASET-AUTHORING-1`) |
| **Fix pages that disagree with the product** | the Handbook says Add data is on by default (it is off); Handbook and guide disagree on scheduling a Dashboard; an agent follows a page literally | `HANDBOOK-PRODUCT-DISAGREE-1` |
| **Machine-readable entry points shipped with the install**: the API contract, the shipped templates, a flat page index (id, audience, edition, permission, URL) | both runs said the contract and the templates closed their largest gaps | `AGENT-ENTRYPOINTS-1` |
| **An error catalogue**: each 4xx and 422 message, cause and fix, generated from the error codes | 49 of the blind run's 234 calls were refused, often without a usable message | `ERROR-CATALOGUE-1` |
| **Assumptions on every page**: edition, sign-in posture, the permission needed; the Handbook checker rejects a new page without them | agents hit "works on Basic, refused on Standard" with no warning | `PAGE-ASSUMPTIONS-1` |

**Left alone:** the tutorials that print expected answers stay as they are for people; the fix is in how agents are
tested — AK-4's golden tasks use data and a Space the Handbook does not already answer (§1.2). The check-block format
is kept and extended. **Order:** the generated component reference first, because the authoring-gaps lane
(`UNKNOWN-KEYS-SILENT-1`, `COMPONENT-VALIDATE-ALL-1`) is fixing the validation it will describe; then the entry points
and the assumptions block, which are small; recipes grow with the Agent Skills.

## 2. Vocabulary — the plan's "Draft" is a Pending Change

The parent plan says "save as a Draft". **Draft** is taken: it is a Link Analysis analyst's private copy of an
Investigation (`GLOSSARY.md`). The config concept that already does what D-UE6 asks is the **Pending Change** — a config
write an Approval Policy held for a second person, approved by replaying the same route, refused when stale. So:

- the MCP Tool `save_draft` (plan §7.2) is named **`propose_change`**, and "Draft" in AK-2's sense is a **Pending Change**;
- an outbound call is an **Action Request** (unchanged);
- the `/agent/approvals` inbox is the in-product **Assistant**'s tool governance — not used by the Agent Kit.

The parent plan's §7.2 is corrected in the same change as this plan.

## 3. What exists today (the seams AK-2 builds on)

| Seam | Where | What it gives AK-2 |
|---|---|---|
| In-process replay | `ApiContext.replay` (`spi/inspecto-http-spi`), used by `McpRoutes.replay` | Each write tool replays the existing write route as the caller: its capability gate, path jail, validation and audit all run; the endpoint adds no side door. |
| The approval hold | `PendingChanges.hold` (`inspecto/…/control/PendingChanges.java`) | A write route calls it before writing; it records a Pending Change and answers **202**. It already holds **without** a policy rule when a write is approval-required (`JobWriteGuard.mandatory`) — AK-2 adds one more such reason. Reached from 16 route classes (`ConfigWriteRoutes`, `PipelineGraphRoutes`, `JobRoutes`, `AlertRoutes`, `ExpectationRoutes`, …). |
| Approval | `POST /pending-changes/{id}/approve` (`canApproveChanges` + the kind's approver capability), four-eyes, `verifyApproved` refuses a stale or altered replay | Unchanged; the person approves. |
| Withdraw | `POST /pending-changes/{id}/withdraw`, author-only (`self-service` exemption) | An agent may take back its own proposal. |
| Action Requests | `POST /action-requests` (`canWorkIncidents`); approve / decline `canApproveChanges` | `propose_action` replays the create; a person approves. |
| Validate, dry-run | `POST /validate`, `POST /pipelines/authored/{id}/dry-run` — both `read-shaped`, write nothing | `validate_config`, `dry_run_pipeline`. |
| Ship reads | `POST /bundle/export` (read-shaped), `POST /promotion/preview` (`canAdminister`) | `export_bundle`, `preview_promotion`. |
| Machine identity | `MachineTrust.machineClient` (`providers/inspecto-oidc`): a token is a machine's when `gty` says client credentials or its client is in `auth.oidc.clients` | Unchanged for machines. **Gap:** no way to say "this client is an agent acting as a person". |
| Machine capabilities | `Roles.MACHINE_CAPABILITIES = {canOpenInboundInvestigation}`; every other grant is dropped at `admitMachine` | AK-2c widens it (D-UE5, D-AK4). |
| MCP audit attributes | `McpRoutes.replay` stamps `via: mcp`, the tool name and `mcpClient` | **Gap:** `mcpClient` is self-reported and unverified — read from each request's `_meta` key `io.modelcontextprotocol/clientInfo` (the modern revision has no `initialize`). |
| The hold's route list | `PendingChanges.REPLAYABLE`, pinned by `ConfigWriteFunnelTest` | The routes that reach the hold — the agent allowlist's starting point (§4.1). |
| Author at approval | `PendingChanges.authorNow` rebuilds the author's Subject from their current Roles when an approved change replays | **Gap for AK-2c** (closed 2026-11-25): the rebuilt Subject carried no machine flag and no `MACHINE_CAPABILITIES` filter; the record now keeps `authorMachine` and the replay is re-admitted and filtered. |
| An agent is a second maker | `IncidentBriefRoutes` (four-eyes treats the person who asked and the Assistant as two makers, so neither may approve) | The precedent D-AK1 follows. |

## 4. Design

### 4.1 The hold follows the identity, not the endpoint

The decisive rule: **an agent's write is held because of who is writing, not because it came through `/mcp`.** If the
hold keyed on `via: mcp`, the same agent token calling `PUT /…` directly would write unheld. So:

- the authenticated Subject carries an **agent marker** — `agentClient` (the verified client id) for a delegated person
  whose token came from an allowlisted agent client, or the machine's client id for a machine admitted with the agent
  capability set;
- `PendingChanges.hold` and `holdSet` read the marker. A marked Subject's write is held **always**, with the kind's
  policy rule when there is one, else a mandatory rule: approver `ApprovalPolicy.DEFAULT_APPROVER` (`canApproveChanges`),
  four-eyes per D-AK1. Same shape as `JobWriteGuard.mandatory` today ("a policy is opt-in and the risk is not");
- **default deny for a marked Subject**, at the host's gate (not only at `/mcp`), with every non-GET manifest entry in
  exactly one bucket, pinned by a parity test over `CapabilityManifest.ENTRIES` and `EXEMPTIONS` — three in phase 1,
  plus a fourth, **actions**, that phases 2 and 3 fill from the refused bucket (§4.6):
  1. **held** — the route reaches `PendingChanges.hold` / `holdSet`; starts from `PendingChanges.REPLAYABLE` minus the
     never-list below, which **overrides** this bucket (`/promotion/apply` and the `/access/*` writes are on
     `REPLAYABLE` but are refused to an agent, never held);
  2. **allowed, never held** — writes nothing governed: `POST /mcp` itself, `/validate`, dry-runs, `/bundle/export`,
     `/promotion/preview`, Pending Change withdraw, `POST /action-requests` (held by its own design), and
     `…/run` if D-AK2 allows it;
  3. **refused (403)** — everything else, including every route that only calls `holdRefusing` /
     `holdRefusingPaths` (those return normally when no policy covers the kind, so they do **not** hold: bundle import,
     onboarding imports, Dataset registration, Widget tags and the like), and the never-list below;
- **the never-list holds for a marked Subject on every door:** any `…/approve`, `…/decline`, the approver's
  `/action-requests/{id}/retry` and `…/mark-failed` (not a run's `/runs/{id}/retries/retry-now`, which is an action, §4.6),
  `/promotion/apply`, `/apps` writes, Access writes (`/access/*`), `/agent/approvals/*/decision`, reveal and
  link-expansion approval routes. In particular `POST /pending-changes/{id}/approve` refuses a marked Subject, so an
  agent token can never approve — whatever D-AK1 says about the person;
- **an approved agent change replays as what proposed it:** `authorNow` carries the agent marker and, for a machine,
  the `MACHINE_CAPABILITIES` filter, so approval never widens what the change runs with (AK-2c prerequisite);
- the Pending Change record gains `agentClient` and, when it came through `/mcp`, `mcpTool`; the Pending Changes screen
  shows "proposed by *person* via *agent client*".

### 4.2 Delegated person: the agent-client allowlist

- New key `-Dauth.oidc.agentClients` (comma list of client ids). A token whose `azp` (else `client_id`) is listed and
  that is **not** a client-credentials token is a **person's** token with `agentClient` set. Listing a client in both
  `auth.oidc.clients` and `auth.oidc.agentClients` is a boot refusal.
- `auth.oidc.agentClients` uses the same audience, issuer and JWKS checks as the SPA client; no new trust root.
- The audit actor stays the person; `agentClient` rides every audit row of that request (not only MCP ones).

### 4.3 Discovery: protected-resource metadata (RFC 9728)

- `GET /.well-known/oauth-protected-resource` answers `resource`, `authorization_servers` (the configured issuer),
  `scopes_supported`, `bearer_methods_supported: [header]`. Served only when `inspecto-mcp` is loaded and OIDC is on.
- A 401 from `/mcp` carries `WWW-Authenticate: Bearer resource_metadata="…"`, so an MCP client can find the sign-in
  (authorization code with PKCE, the MCP authorization model).
- Check at build: whether the host serves a path outside `/api/v1` (`ControlApi` context mapping); if not, the
  metadata is served under `/api/v1/.well-known/…` and the reverse-proxy guide gains the alias.

### 4.4 The AK-2 MCP Tools

Every tool replays one existing route; none is a new write path. Write tools are annotated `readOnlyHint: false`,
`destructiveHint: false` (a proposal changes nothing until a person approves it).

| MCP Tool | Replays | Notes |
|---|---|---|
| `validate_config` | `POST /validate` | read-shaped; returns the 422 findings as data |
| `dry_run_pipeline` | `POST /pipelines/authored/{id}/dry-run` | read-shaped |
| `propose_change` | the kind's own write route (`PUT`/`POST`/`DELETE`), from an allowlist kind → route | answers the 202 Pending Change; a kind whose route does not reach the hold is not on the allowlist |
| `list_pending_changes`, `get_pending_change`, `diff_pending_change` | `GET /pending-changes`, `GET …/{id}`, `GET …/{id}/diff` | reads |
| `withdraw_change` | `POST /pending-changes/{id}/withdraw` | the person's own proposals only (the route's author check) |
| `propose_action` | `POST /action-requests` | needs `canWorkIncidents`; held for approval by design |
| `export_bundle` | `POST /bundle/export` | read-shaped |
| `preview_promotion` | `POST /promotion/preview` | needs `canAdminister` — so in practice a person tool, never a machine one |
| `run_pipeline_now` | `POST /pipelines/authored/{id}/run` (gate `canAuthorWorkbench`) | **only if D-AK2 says so** |

**Never offered:** the §4.1 never-list. The host refuses it to a marked Subject on every door; the endpoint refuses it
too, and a test asserts no tool's router can produce such a path.

**Deferred:** `validate_app` — there is no route; the App kit runs from the source tree only (`APP-KIT-DELIVERY-1`).
It joins when that row ships.

### 4.5 The machine agent capability set (AK-2c)

- `Roles.MACHINE_CAPABILITIES` gains a named **agent set**, admitted only for clients listed in a new
  `-Dauth.oidc.agentMachineClients` (a subset of `auth.oidc.clients`), so widening it does not hand every existing
  machine client new rights.
- Contents per D-AK4: the authoring capabilities of the kinds on the `propose_change` allowlist plus `canWorkIncidents`
  — useful **only because** a marked Subject's every write is held (§4.1). Never `canApproveChanges`, `canAdminister`,
  any reveal or link-expansion approval capability.

### 4.6 Operational and case actions (phases 2 and 3)

The phase-1 gate refuses every write that is not a config save. Phases 2 and 3 move named routes out of the refused
bucket into a fourth bucket, **actions**: writes that change a run's or a Case's state, not config. Today's routes:

| Role | Route (gate) | Tier (D-AK5 recommended) |
|---|---|---|
| Operations | `POST /runs/{id}/trigger`, `…/pause`, `…/resume`, `…/retries/retry-now`, `…/retries/cancel`, `POST /jobs/runs/{id}/replay` (`canOperateRuns`) | **low** — allowed for a delegated person |
| Operations | `POST /runs/{id}/reprocess`, `…/replay-rejects`, `…/drain` (`canOperateRuns`) | **outcome** — held for a person |
| Analyst | `POST /objects/{id}/comments` (collaboration), `POST /incidents/{id}/briefs`, `POST /objects/{id}/ack`, `…/assign`, `POST /alerts/{id}/ack` (`canWorkIncidents`) | **low** |
| Analyst | `POST /objects/{id}/resolve`, `…/transition`, `POST /alerts/{id}/resolve` (`canWorkIncidents`), `POST /cases/from-entities` (`canManageIncidents`) | **outcome** |
| Analyst | `POST /action-requests` | already held by design (phase 1) |

- **Low**: the person could do it with one click and it changes no outcome; allowed for a delegated person within
  that person's rights, audited with `agentClient`.
- **Outcome**: it closes, resolves, rewrites or discards something; **held for a person to approve**. There is no hold
  for a non-config action today — a Pending Change is config only (`GLOSSARY.md`). How it is held is D-AK6.
- **Machines**: propose only — every action from a machine is held, whatever its tier.

> **Signed 2026-11-24 (§8): D-AK5 is "allow within rights", not the two tiers above.** A person's agent takes every
> action in this table that the person's rights allow, at once and audited; only a **machine's** actions are held (as a
> Pending Action, D-AK6). The tier column is kept as the record of what was weighed.

### 4.7 The endpoint's manifest category

`POST /mcp` stays an Exemption (it has no capability of its own), re-categorised from `read-shaped` to a new
**`replay-gated`** category whose reason says every tool replays a gated route as the caller.
`McpRoutesManifestParityTest` extends to every AK-2 tool: its replayed route is in `ENTRIES` or `EXEMPTIONS`.

## 5. Safeguards carried over from AK-1

Masked values stay masked; no tool returns a secret; every call leaves the endpoint's `mcp.tool.called` row plus the
replayed route's own row; the list results stay `cacheScope: private` (AK-2's lists depend on grants); the per-Subject
rate limit applies; a body over 256 KiB is cut and says so.

## 6. Phases

Built in the role order of §1.1. Phase 1 is AK-2a then AK-2b; phase 2 adds AK-2c and the operational half of §4.6;
phase 3 the case half. Each phase closes with its role's Agent Skills (AK-3), its golden tasks (AK-4), a Handbook how-to
and a security review of what it added.

| Step | Phase | Delivers | Proof |
|---|---|---|---|
| **AK-2a** identity first — **built 2026-11-20** ([spec-mcp.md § The agent gate](../okf/product/module/mcp/spec-mcp.md#the-agent-gate-ak-2a)) | 1 | `auth.oidc.agentClients`, the Subject's agent marker on every audit row, RFC 9728 metadata and the 401 header, the identity-keyed hold in `PendingChanges.hold`/`holdSet`, the three-bucket default deny and never-list at the host gate, `authorNow` keeping the marker, `agentClient` on the Pending Change and its screen | real-HTTP tests: an agent-client token's direct `PUT` is held (202); the SPA client's is not; a `holdRefusing`-only write (bundle import) is 403 for the agent; the agent token's approve is 403; approval per D-AK1; falsified (remove the marker check → the direct `PUT` writes) |
| **AK-2b** write tools — **built 2026-11-20** ([spec-mcp.md § Write tools](../okf/product/module/mcp/spec-mcp.md#write-tools-ak-2b); the Keycloak end-to-end run is left to AK-4) | 1 | the §4.4 tools, the endpoint refusal list, the manifest category, tool definitions and the `openapi.fragment.json` | end to end on a Standard install with Keycloak: validate → dry-run → propose → a second person approves → the change is live; `propose_action` → approve → sent |
| **AK-2c** machines — **built 2026-11-25** (§6.2 lane A; [spec-mcp.md § Identity](../okf/product/module/mcp/spec-mcp.md#identity)) | 2 | `auth.oidc.agentMachineClients` and the agent capability set | a machine in the set proposes and is held; one outside it keeps one capability; an approval attempt is 403 |
| **AK-2d** operational actions | 2 | the operations rows of §4.6, the held-action record per D-AK6, the operations MCP Tools and Skills | a person's agent re-runs a failed run and reprocesses at once, within the person's rights, audited with `agentClient` (D-AK5 as signed); a machine's resume is held as a Pending Action |
| **AK-2e** case actions | 3 | the analyst rows of §4.6, the analyst MCP Tools and Skills | a person's agent comments, assigns and resolves at once, within the person's rights (D-AK5 as signed); a machine's resolve is held as a Pending Action; an agent never approves an Action Request |
| close, per phase | 1–3 | security review of what the phase added, hub `module/mcp` spec and guides, the role's Handbook how-to | review at confidence ≥ 8 finds nothing |

AK-2a ships before any write tool (spec-mcp.md "Deferred to AK-2": a verified agent client must be on the row before an
agent can author anything). Size: phase 1 about 3 weeks, as the parent plan says for AK-2; phases 2 and 3 about
2 weeks each, plus their Skills. Prompts, subscriptions, progress
notifications and the tasks extension stay deferred to AK-3 / AK-4.

### 6.1 Phase 1 close as built (2026-11-24)

- **AK-3, developer Agent Skills** — `agent-kit/skills/<name>/SKILL.md`, seven: `build-an-app` (orchestrator: plan,
  land, shape, guard, watch, show, prove, solution document), `author-pipeline`, `add-expectations`,
  `author-alert-rules`, `build-dashboard`, `safe-config-change` (the validate → dry-run → propose loop every other skill
  ends in) and `explain-inspecto` (read-only). Model-neutral, no repository path, each ending in a `## MCP Tools` list
  and linking external Handbook pages. `onboard-data` is **not** a skill of its own: the agent gate refuses an agent
  the upload, onboarding and Dataset-registration routes (§4.1, refused bucket), so landing a new file is the person's
  step and `author-pipeline` / `build-an-app` say so. `reconcile`, `package-an-app` and `ship-and-upgrade` wait on
  `RECON-CROSS-PERIOD-1` and `APP-KIT-DELIVERY-1`.
- **The guard** `tools/check-agent-kit.mjs` (CI `guards:`): every backticked tool-shaped word in a skill is a real MCP
  Tool (read from `McpTools.java`) and is in the skill's list; every `/help/<page>.html` is an external Handbook page;
  no repository path; the golden suite is well-formed. Tests with negative fixtures: `tools/check-agent-kit.test.mjs`.
  It caught seven unlisted tools on its first run.
- **AK-4, developer golden tasks** — `agent-kit/golden/developer.json`, seven tasks on `agent-kit/golden/data/shipments.csv`
  (36 synthetic rows no Handbook page answers, per §1.2's caveat; ground truth in the suite): an Expectation, a derived
  Dataset, an Alert Rule, a Dashboard, an explain question, a refused upload, and a request to self-approve. **Graded
  from platform state, never by a model** (`tools/run-agent-golden.mjs`): the Pending Changes carrying the agent client,
  the agent's audit rows (tool order: `validate_config` or `dry_run_pipeline` before `propose_change`), refusals on the
  never-list (an *attempt* fails safety even though the server refused it), and fixed strings in the final reply. The
  grader reads with a person's token, so the agent cannot hide what it did. Grading logic tested on recorded states
  (`tools/run-agent-golden.test.mjs`); the "no applied write" check matches audit action names by pattern and skips a
  held write's own 202 row. *(The first live run, 2026-11-24, below, exercised it and fixed its audit query and session
  window.)*
- **Found writing the skills:** `validate_config` with `type: pipeline` judges the Pipeline *config file* form, while
  `propose_change` takes the *graph*; for a graph the dry run is the validation (the candidate is parsed and validated
  before it runs). The alert config type is `alert`, the `propose_change` kind `alert-rule`. Both are written into the
  skills.
- **Handbook how-to** `howto_agent_propose_changes` (chapter *Run the platform*, external, Standard): register the
  agent client, list it, give the agent the Kit, approve its proposals.
- **Security review** of phase 1 as a whole — see the hub's [spec-mcp.md](../okf/product/module/mcp/spec-mcp.md#security-review-phase-1-close-2026-11-24).
- **Vendor packages, Claude Code and Gemini CLI (2026-11-24)** — `tools/package-agent-kit.mjs` builds both **per
  install** from `agent-kit/skills` unchanged: a Claude Code plugin marketplace (`.claude-plugin/marketplace.json`,
  plugin `inspecto` with `.claude-plugin/plugin.json`, `.mcp.json` `type: http` + `oauth {clientId, callbackPort,
  scopes}`) and a Gemini CLI extension (`gemini-extension.json` with `httpUrl` + `oauth {enabled, clientId,
  authorizationUrl, tokenUrl, scopes, redirectUri}`, `contextFileName: GEMINI.md`, `skills/`). Formats checked against
  each vendor's documentation on 2026-11-24. **Why per install:** Gemini substitutes only `${extensionPath}`,
  `${workspacePath}` and `${/}` in its manifest, so `httpUrl` cannot come from a variable, and env expansion in a
  Claude plugin's `.mcp.json` `url` is not documented; Inspecto's IdP needs a pre-registered agent client and a fixed
  localhost redirect, so both are baked in, and Gemini's authorization and token endpoints come from the OIDC
  discovery document. No secret is written. Proof: `tools/package-agent-kit.test.mjs` (in the guard step) and
  `claude plugin validate` passing on the generated plugin and marketplace. **Not proven:** a sign-in and tool call
  through either package against a live install (Gemini CLI is not installed on the build host; that the Claude plugin
  honours its server's `oauth` block is documented for `.mcp.json` generally, not for plugins); PKCE is not stated by
  either vendor's pages. Caveat: Gemini CLI's site carries a notice that Antigravity CLI replaced it on 2026-06-18 for
  unpaid and Google One users — confirm the target before a customer release.
- **Still open for phase 1 (updated 2026-11-24):** the Gemini CLI, Codex CLI and local-model matrix rows (a sign-in and
  golden run through each package); whether Gemini CLI or Antigravity CLI is the Gemini target. *(The Claude Code
  package's sign-in and golden run are done, below.)*
- **First live golden run (2026-11-24)** — developer suite, Claude (`sonnet`) through headless Claude Code against a
  Standard bundle with Keycloak: **6 of 7 pass, 0 safety failures**; dev-04 (Dashboard) hit the 20-minute limit. Every
  write was held as a Pending Change naming the agent client; the agent never tried an approval. Server fault found:
  HTTP 500 on 53 of 237 calls (`AGENT-PROPOSE-500-1`; fixed the same day: the agent's reason, holding "—" or "→",
  rode the replay as a header the JDK refuses — it is now a request attribute, and replay faults are logged). The agent's token came from the harness's own PKCE sign-in as the
  agent client. **Second run the same day, through the Claude Code plugin's own OAuth** (generated by the bundle's
  packager; no harness token): 6 of 7, 0 safety failures — the plugin sign-in, stored token and refresh work; Claude Code
  always asks for `offline_access` (the agent client must allow it and the person hold the role); an Expectation with no
  `kind` was a 500 (fixed); dev-03 proposed nothing because an Alert Rule cannot count only some rows. Record, method and the matrix:
  [`tests-agent-golden.md`](../okf/product/module/mcp/tests-agent-golden.md).
- **Codex and local packages (2026-11-24)** — the same packager adds `codex/` (an OpenAI Codex CLI project:
  `.codex/config.toml` `[mcp_servers.inspecto]` `url`, `scopes` and `[…oauth]` `client_id`, `callback_port`;
  `.agents/skills/`; `AGENTS.md`) and `opencode/` — the **local-model** package targets **OpenCode** with an Ollama or
  LM Studio model, the one candidate whose documentation covers a Streamable HTTP remote server with OAuth and a pre-set
  client id, a fixed callback port, `SKILL.md` skills and local providers (LM Studio documents only static headers and no
  skills; Goose's docs did not load): `opencode.json` `mcp.servers.inspecto` `type: remote` + `oauth {client_id, scope,
  callback_port, redirect_uri}`, `.opencode/skills/`, `AGENTS.md`. Formats from each vendor's documentation, 2026-11-24.
  **Not proven:** neither CLI is installed on the build host (the TOML parses with Python's `tomllib`); Codex may append
  a server id to its callback path, so the redirect to register is the one `codex mcp login` prints; OpenCode's `mcp.servers`
  is its documented V2 shape (older releases put servers directly under `mcp`); neither page states PKCE.
- **The Kit in the bundle (2026-11-24)** — `inspecto/package.ps1` step 7 stages `<bundle>/agent-kit/` (README, `skills/`,
  `package-agent-kit.mjs`, `VERSION`) **only when `lib/modules.list` carries the `inspecto-mcp` jar**, so Standard and up
  ship it and Basic, or an offering without the module, never does; staging it without the module fails the package, as
  does a stray file under it (an allow-list). The bundle root's layout list (BUNDLE-LAYOUT-1) gains `agent-kit`, as this
  plan's §7.5 named it. `tools/check-agent-kit.mjs` runs before the copy. The packager finds `skills/` beside itself and
  reads `VERSION` in place of `pom.xml`, so an operator builds the vendor packages on site with Node.js (writes
  `agent-kit/packages/`). The golden tasks stay in the repo (grading needs `tools/run-agent-golden.mjs`).

### 6.2 Phase 2 build (operator, 2026-11-25: "take on phase 2")

Two operator answers on 2026-11-25 settle what §8 left to the build: a **Pending Action is approved by the change
approvers** (`canApproveChanges`, four-eyes, the approver roster — as a Pending Change), and **approvers decide it on
the Pending Changes screen** (a second tab, the same approve / decline / withdraw flow). Built in three lanes, each
pushed on its own:

| Lane | Delivers | Proof |
|---|---|---|
| **A — AK-2c machines** | `-Dauth.oidc.agentMachineClients` (a subset of `auth.oidc.clients`, boot refusal otherwise); a machine token from a listed client is a Subject that is both `machine()` and `agent()` (`agentClient` = its client id); `Roles.AGENT_MACHINE_CAPABILITIES` = `canAuthorWorkbench`, `canAuthorAlertRules`, `canWorkIncidents`, `canOperateRuns` kept at `admitMachine` beside `MACHINE_CAPABILITIES`, never an `AGENT_NEVER_CAPABILITIES` one; the Pending Change records `authorMachine`, and `authorNow` re-applies the machine filter and marker (the §3 gap) | a listed machine's config write is held (202); an unlisted machine keeps one capability; the machine's approve is 403; an approved machine change replays with the machine filter (falsified: drop the filter → the replay runs with a person's capabilities) |
| **B — AK-2d actions** | `AgentGate.Bucket.ACTION` for the operations routes of §4.6 plus `POST /jobs/{id}/trigger`: allowed for a person's agent within the person's rights (D-AK5); for a machine agent the host holds the request **before the handler** as a **Pending Action** (`pa-` id, same store, MAC, lock and expiry as a Pending Change; `GLOSSARY.md` gains the term); `GET /pending-actions`, `…/{id}`, `…/{id}/approve`, `…/decline`, `…/withdraw`; approve replays the request as the machine (`authorNow`), a stale one is the route's own refusal (409) or expiry | a person's agent re-runs and reprocesses at once (200, `agent_client` on the row); a machine's resume is 202 and nothing ran; a second person approves → it runs as the machine; the machine's approve is 403; a replay of a record naming a non-action route dispatches nothing |
| **C — tools, Skills, screen, close** | MCP Tools `list_runs`, `get_run_status`, `operate_run`, `replay_job_run`, `trigger_job`, `list_pending_actions`, `get_pending_action`, `withdraw_action`; the Pending Changes screen's *Pending Actions* tab; Agent Skills `diagnose-failed-run` and `operate-runs`; an operations golden suite; the Handbook how-to; the hub docs; a security review | as §6, close per phase |

Not in phase 2: the role's product gaps `QUARANTINE-RELEASE-1` and `BACKUP-DATA-1` (§1.1) stay board rows; an agent
cannot release a quarantined file or take a backup until they ship.

## 7. Risks

- **A write route that skips the hold.** Mitigated by §4.1's default deny and the bucket parity test over every
  non-GET manifest entry; a route that only calls `holdRefusing*` counts as not holding.
- **One administrator, four-eyes.** On a single-admin install no second person can approve — D-AK1's alternative.
- **Tool sprawl.** The allowlist for `propose_change` starts with the kinds the Handbook's builder tutorials use
  (Pipeline, Expectation, Alert Rule, Widget, Dashboard, Job) and grows with AK-3's Agent Skills.
- **Client self-description.** `clientInfo` stays a label only; the verified `agentClient` is what audit and the
  Pending Change trust.

## 8. Decisions

**D-AK1…D-AK4 signed 2026-11-20** (operator, "Sign recommended"): each as its *Recommended* column says. For D-AK1 that
is **four-eyes always**; the single-administrator alternative is not signed and is not built unless the operator asks.
**D-AK5, D-AK6 and D-AK7 signed 2026-11-24** (operator interview) — D-AK6 and D-AK7 as recommended; **D-AK5 NOT as
recommended**, see the **Signed** lines under the table. Phase 2 is no longer blocked on a decision.

| # | Question | Recommended |
|---|---|---|
| **D-AK1** | Who may approve an agent's Pending Change? | **Another person — four-eyes always** (the agent's token never; the delegating person never). This follows the parent plan §7.2 (four-eyes for destructive or promoting changes) and the code's own precedent: four-eyes treats the person who asked and the Assistant as two makers (`IncidentBriefRoutes`). *Alternative, for single-administrator installs:* the delegating person may approve a non-destructive, non-promoting proposal (one human review), switched on per Space. A machine's proposal always needs a person. |
| **D-AK2** | Is `run_pipeline_now` in AK-2? | **Yes for a delegated person** who passes the run route's own gate (`canAuthorWorkbench`) — a run writes data, not config, and the person could press Run; **never** for a machine. The Agent Skills require a dry run first. |
| **D-AK3** | Do write tools require a verified agent client? | **Yes.** A token from the SPA client keeps AK-1's read tools; write tools answer 403 "sign in through an agent client", so every write names a verified client. |
| **D-AK4** | The machine agent capability set | The authoring capabilities of the `propose_change` kinds plus `canWorkIncidents`; listed per client in `auth.oidc.agentMachineClients`. Widens operator decision B1, as D-UE5 anticipated. Phase 2 adds `canOperateRuns` for the operations role. |
| **D-AK5** | How are an agent's operational and case actions governed (phases 2–3)? | The two tiers of §4.6: **low** actions allowed for a delegated person within that person's rights; **outcome** actions held for a person to approve; every machine action held. |
| **D-AK6** | How is a held non-config action recorded? | Reuse the Pending Change machinery — record the request, approve by replaying the same route as the proposer, refuse a stale replay — under its **own term** in `GLOSSARY.md` (a Pending Change stays config only). Name to be settled with the glossary owner before phase 2. |
| **D-AK7** | Which file access does an agent get on a customer's install (§1.2)? | **Governed install:** read access to the install root (shipped docs, example Spaces and templates), no write access to Space folders, every change through the API. **Sandbox or developer install:** direct file writes allowed. The Agent Skills say which posture they assume. Settle with the spike's variant A/B comparison. |

**Signed 2026-11-24 (operator interview):**
- **D-AK5 — allow within rights (not the two tiers).** An agent signed in as a person may take **any** operational or
  case action that person's rights allow — re-run, resume, reprocess, drain, comment, assign, resolve, transition, open
  a Case — **at once, with no hold**, audited with `agentClient` on every row. The low / outcome split of §4.6 is not
  built for persons' agents. **A machine (unattended) agent's every action is held** for a person to approve
  (follow-up answer), as the table's *Machines* line in §4.6 says. The never-list is unchanged: no agent approves,
  declines, reveals, promotes or changes Apps or access.
- **D-AK6 — as recommended, named "Pending Action".** A held non-config action reuses the Pending Change machinery
  (record the request, approve by replaying the same route as the proposer, refuse a stale replay) under its own
  term **Pending Action**; a Pending Change stays config only. With D-AK5 as signed, only machine actions are held, so
  the Pending Action is the machine path. `GLOSSARY.md` gains the term with the phase that builds it (AK-2d).
- **D-AK7 — as recommended (two postures).** Governed install: read access to the install root, writes only in a
  scratch copy, every change applied through the API; sandbox or developer install: direct file writes allowed. The
  Agent Skills say which posture they assume.

## References

- Parent plan: [`2026-11-19-user-enablement-and-agent-kit-plan.md`](2026-11-19-user-enablement-and-agent-kit-plan.md) §7–§9.
- AK-1 as built: [`spec-mcp.md`](../okf/product/module/mcp/spec-mcp.md), [`architecture-mcp.md`](../okf/product/module/mcp/architecture-mcp.md).
- Pending Changes: [`spec-config-safety.md`](../okf/product/module/config/spec-config-safety.md); Action Requests:
  [`spec-action-requests.md`](../okf/product/module/action-requests/spec-action-requests.md).
- Machine identity: `providers/inspecto-oidc/src/main/java/com/gamma/oidc/MachineTrust.java`,
  `platform/inspecto-access/src/main/java/com/gamma/access/Roles.java` (`MACHINE_CAPABILITIES`).
