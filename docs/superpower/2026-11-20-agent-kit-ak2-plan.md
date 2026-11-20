# Agent Kit AK-2 — agents propose changes, people approve them, one role at a time (plan)

> **Status: APPROVED for phase 1 on 2026-11-20 — D-AK1…D-AK4 signed (operator, "Sign recommended"); D-AK5 and
> D-AK6 open, needed before phase 2 (§8); nothing built; phase 1 (AK-2a, then AK-2b) startable. Built phase by phase, one agent role per
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
| Author at approval | `PendingChanges.authorNow` rebuilds the author's Subject from their current Roles when an approved change replays | **Gap for AK-2c:** the rebuilt Subject carries no machine flag and no `MACHINE_CAPABILITIES` filter. |
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
| **AK-2a** identity first | 1 | `auth.oidc.agentClients`, the Subject's agent marker on every audit row, RFC 9728 metadata and the 401 header, the identity-keyed hold in `PendingChanges.hold`/`holdSet`, the three-bucket default deny and never-list at the host gate, `authorNow` keeping the marker, `agentClient` on the Pending Change and its screen | real-HTTP tests: an agent-client token's direct `PUT` is held (202); the SPA client's is not; a `holdRefusing`-only write (bundle import) is 403 for the agent; the agent token's approve is 403; approval per D-AK1; falsified (remove the marker check → the direct `PUT` writes) |
| **AK-2b** write tools | 1 | the §4.4 tools, the endpoint refusal list, the manifest category, tool definitions and the `openapi.fragment.json` | end to end on a Standard install with Keycloak: validate → dry-run → propose → a second person approves → the change is live; `propose_action` → approve → sent |
| **AK-2c** machines | 2 | `auth.oidc.agentMachineClients` and the agent capability set | a machine in the set proposes and is held; one outside it keeps one capability; an approval attempt is 403 |
| **AK-2d** operational actions | 2 | the operations rows of §4.6, the held-action record per D-AK6, the operations MCP Tools and Skills | an agent re-runs a failed run (low); its reprocess is held until a person approves; a machine's resume is held |
| **AK-2e** case actions | 3 | the analyst rows of §4.6, the analyst MCP Tools and Skills | an agent comments and assigns (low); its resolve is held; an agent never approves an Action Request |
| close, per phase | 1–3 | security review of what the phase added, hub `module/mcp` spec and guides, the role's Handbook how-to | review at confidence ≥ 8 finds nothing |

AK-2a ships before any write tool (spec-mcp.md "Deferred to AK-2": a verified agent client must be on the row before an
agent can author anything). Size: phase 1 about 3 weeks, as the parent plan says for AK-2; phases 2 and 3 about
2 weeks each, plus their Skills. Prompts, subscriptions, progress
notifications and the tasks extension stay deferred to AK-3 / AK-4.

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
**D-AK5 and D-AK6 stay open**; they are needed before phase 2.

| # | Question | Recommended |
|---|---|---|
| **D-AK1** | Who may approve an agent's Pending Change? | **Another person — four-eyes always** (the agent's token never; the delegating person never). This follows the parent plan §7.2 (four-eyes for destructive or promoting changes) and the code's own precedent: four-eyes treats the person who asked and the Assistant as two makers (`IncidentBriefRoutes`). *Alternative, for single-administrator installs:* the delegating person may approve a non-destructive, non-promoting proposal (one human review), switched on per Space. A machine's proposal always needs a person. |
| **D-AK2** | Is `run_pipeline_now` in AK-2? | **Yes for a delegated person** who passes the run route's own gate (`canAuthorWorkbench`) — a run writes data, not config, and the person could press Run; **never** for a machine. The Agent Skills require a dry run first. |
| **D-AK3** | Do write tools require a verified agent client? | **Yes.** A token from the SPA client keeps AK-1's read tools; write tools answer 403 "sign in through an agent client", so every write names a verified client. |
| **D-AK4** | The machine agent capability set | The authoring capabilities of the `propose_change` kinds plus `canWorkIncidents`; listed per client in `auth.oidc.agentMachineClients`. Widens operator decision B1, as D-UE5 anticipated. Phase 2 adds `canOperateRuns` for the operations role. |
| **D-AK5** | How are an agent's operational and case actions governed (phases 2–3)? | The two tiers of §4.6: **low** actions allowed for a delegated person within that person's rights; **outcome** actions held for a person to approve; every machine action held. |
| **D-AK6** | How is a held non-config action recorded? | Reuse the Pending Change machinery — record the request, approve by replaying the same route as the proposer, refuse a stale replay — under its **own term** in `GLOSSARY.md` (a Pending Change stays config only). Name to be settled with the glossary owner before phase 2. |

## References

- Parent plan: [`2026-11-19-user-enablement-and-agent-kit-plan.md`](2026-11-19-user-enablement-and-agent-kit-plan.md) §7–§9.
- AK-1 as built: [`spec-mcp.md`](../okf/product/module/mcp/spec-mcp.md), [`architecture-mcp.md`](../okf/product/module/mcp/architecture-mcp.md).
- Pending Changes: [`spec-config-safety.md`](../okf/product/module/config/spec-config-safety.md); Action Requests:
  [`spec-action-requests.md`](../okf/product/module/action-requests/spec-action-requests.md).
- Machine identity: `providers/inspecto-oidc/src/main/java/com/gamma/oidc/MachineTrust.java`,
  `platform/inspecto-access/src/main/java/com/gamma/access/Roles.java` (`MACHINE_CAPABILITIES`).
