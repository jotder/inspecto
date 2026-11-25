---
type: Spec
title: The MCP endpoint (inspecto-mcp) — AK-1 and AK-2 phase 1 design and as-built
description: How the optional inspecto-mcp module serves the Model Context Protocol on the Control API — transport and pinned revision, the read MCP Tools and resources, why every call goes through the existing auth, Space and route gates (an in-process replay, never a side door), the audit row naming the person and the verified agent client, the agent-client allowlist, protected-resource metadata and the host's agent gate (AK-2a), the write MCP Tools that propose Pending Changes (AK-2b), and what is left for phases 2 and 3.
tags: [agent-kit, mcp, agents, security, audit, identity]
timestamp: 2026-11-20T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: features/inspecto-mcp
---

# The MCP endpoint (inspecto-mcp)

Phase AK-1 of the Agent Kit (`AGENT-KIT-1`, plan
[`2026-11-19-user-enablement-and-agent-kit-plan.md`](../../../../superpower/2026-11-19-user-enablement-and-agent-kit-plan.md) §7).
AK-1 is **read-only**: no MCP Tool changes anything. **AK-2a** (identity first, 2026-11-20) adds the
agent-client allowlist, the agent marker on every audit row, protected-resource metadata and the host's agent gate
([Identity](#identity), [The agent gate](#the-agent-gate-ak-2a)); still no write tool. Write tools (validate →
dry-run → Pending Change) and `propose_action` are **AK-2b** (2026-11-20, [Write tools](#write-tools-ak-2b)); the
machine agent capability set is **AK-2c** (phase 2, 2026-11-25, [Identity](#identity)).

**On this page**

- [Placement](#placement) — the module, the editions, the per-Space switch.
- [Transport](#transport) — the pinned revision, what the endpoint accepts and refuses.
- [No side door](#no-side-door) — how every call reuses the Control API's own gates.
- [MCP Tools and resources](#mcp-tools-and-resources) — the AK-1 list and the route each one replays.
- [Audit](#audit) — the row each call leaves.
- [Identity](#identity) — the delegated-person and machine modes, and what is built of each.
- [The agent gate (AK-2a)](#the-agent-gate-ak-2a) — held writes, default deny, the never-list.
- [Write tools (AK-2b)](#write-tools-ak-2b) — validate, dry-run, propose, withdraw, Ship reads, run.
- [Pending Actions (AK-2d)](#pending-actions-ak-2d) — a machine agent's run actions, held for a person.
- [Case actions (AK-2e)](#case-actions-ak-2e) — an agent works Incidents, Cases and Alerts; a machine's are held.
- [Link Analysis (AK-2f)](#link-analysis-ak-2f) — queries for every agent; Drafts, held for a machine; never promote.
- [Deferred](#deferred)

## Placement

- **Module** `features/inspecto-mcp` (artifact `inspecto-mcp`, package `com.gamma.mcp`, manifest id `mcp`,
  feature `mcp`). One `RouteModule`, `McpRoutes`, discovered by `ServiceLoader`. **No MCP SDK and no new
  dependency**: JSON-RPC is parsed with the host's Jackson mapper (`ApiContext.JSON`), so the locked reactor
  dependency set is unchanged.
- **Editions:** Standard, Advanced and Preview profiles and Offerings; **never Basic** (D-UE4 — Basic has no
  sign-in). On Basic the known-modules manifest makes `POST /mcp` answer `503 CAPABILITY_UNAVAILABLE` with
  the module's `absentMessage`, like every absent optional module.
- **Fail-closed on a signed-out install:** a Standard bundle started without an `Authenticator` attaches no
  Subject. The endpoint then refuses every request (`503 CAPABILITY_UNAVAILABLE`, "the MCP endpoint needs
  sign-in") instead of serving an anonymous agent.
- **Per Space:** the endpoint answers at `/api/v1/mcp` (the default Space) and `/api/v1/spaces/{id}/mcp`
  (that Space); the host strips the prefix and binds the Space exactly as for any route. An agent works in
  the Space its URL names. An administrator switches the endpoint off for one Space by disabling feature
  `mcp` (`PUT /settings/modules`); the host then refuses it like any disabled module route.

## Transport

- **Streamable HTTP, revision `2026-07-28` pinned** (verified against the published specification on
  2026-11-20: stateless, no `initialize`, `server/discover`, per-request `_meta`). The module is
  **dual-era**: a request without modern `_meta` that opens with `initialize` is served as **`2025-11-25`**,
  statelessly — no `Mcp-Session-Id` is minted or honoured, so the legacy era never creates server state.
- **One endpoint, POST only.** `GET` and `DELETE` get the host's `405`. Every response is a single
  `application/json` object; the module never opens an SSE stream (no progress, no `subscriptions/listen`).
- **Accepted methods:** `server/discover`, `tools/list`, `tools/call`, `resources/list`, `resources/read`
  (modern); `initialize`, `ping` and the same four list/call methods (legacy). Any notification (a request
  without an `id`, `notifications/initialized` among them) is `202` with no body and runs nothing. Anything
  else is `404` with JSON-RPC `-32601`. A request id is echoed exactly (a string, or an integer of any size).
- **Request checks, in order, all before any tool runs:**
  1. A Subject is attached (see above), else `503`.
  2. `Origin`, when present, must equal `-Dcontrol.cors`; otherwise `403` (the specification's DNS-rebinding
     rule; MCP clients send no `Origin`, a browser page always does).
  3. Body at most 256 KiB (`413`), one JSON object (a batch array or malformed JSON is `400`, `-32700` /
     `-32600`), `jsonrpc: "2.0"`, a string `method`.
  4. Modern era: `MCP-Protocol-Version` header present and equal to `_meta["io.modelcontextprotocol/protocolVersion"]`,
     `Mcp-Method` equal to `method`, and `Mcp-Name` equal to `params.name` / `params.uri` for `tools/call` and
     `resources/read`, the `=?base64?…?=` sentinel decoded first — otherwise `400`, `-32020 HeaderMismatch`.
     An unknown version is `400`, `-32022 UnsupportedProtocolVersion` with `data.supported`.
- **Results** carry `resultType: "complete"` and `_meta["io.modelcontextprotocol/serverInfo"]`; the list
  results and `server/discover` carry `ttlMs` and `cacheScope: "private"` (the lists are the same for every
  caller today, but a cached answer must never cross users once AK-2 makes them depend on grants).
- **Rate limit:** `/mcp` is on the host's standard per-Subject bucket (`ControlApi.isRateLimited`), because a
  `query_dataset` call runs SQL and the replay below does not pass the host's rate-limit stage.

## No side door

Every MCP Tool and resource is an **in-process replay of an existing Control API route**
(`ApiContext.replay`, the seam the maker-checker approval already uses). The replay:

- runs as **the caller** — it inherits the request's Subject, held roles, config root and correlation id,
  and runs on the request's thread, so the bound Space (MDC) is the same;
- passes the route's **own** gates: the access-policy decision point (`authorize`), the per-Space module
  switch (`requireModuleEnabled`), every capability check and in-handler gate the route has (for example
  `/db/query`'s `ops:*` gate), and the route's masking;
- is **audited by the host** like a request (`AuditTrail.record` / `accessDenied`).

So an agent can read exactly what its person could read in the console, and nothing more. The module has no
data access of its own: it never opens a store, never reads a file other than its packaged glossary, and
holds no table of capabilities. The tool → route map is a fixed list in code; an argument is only ever
URL-encoded into a path segment or a query value of that fixed route, so no argument can choose a route.
A replay's refusal (`401`/`403`/`404`/`422`…) comes back as a tool result with `isError: true` and the
route's own message, never as a JSON-RPC protocol error. A `resources/read` whose replay is refused, like an
unknown resource, is a JSON-RPC `-32602` error carrying the status and the route's message (the 2026-07-28
code for a resource that cannot be read).

`POST /mcp` itself is listed in `CapabilityManifest.EXEMPTIONS` as **`replay-gated`** (AK-2b; `read-shaped` in
AK-1): it persists nothing itself, and every route it reaches carries its own gate, so the endpoint needs no
capability of its own. Like `read-shaped`, the category is not audited (the endpoint and each replayed route write
their own rows) and stays open on a read-only install — where every replayed **write** now meets the host's
read-only check itself (`ControlApi.replay` runs `requireWritable`). A replayed write that the hold turns into a
Pending Change answers **202** inside the replay, as a direct request does.

## MCP Tools and resources

All AK-1 tools are annotated `readOnlyHint: true`, `destructiveHint: false`. Output is the replayed route's
JSON body (the `/api/v1` envelope), returned as one `text` content item and as `structuredContent`; a body
over 256 KiB is cut and the result says so.

| MCP Tool | Arguments | Replays |
|---|---|---|
| `edition_and_modules` | — | `GET /modules` |
| `list_spaces` | — | `GET /spaces` |
| `list_datasets` | — | `GET /components/dataset` |
| `describe_dataset` | `dataset` | `GET /components/dataset/{dataset}` |
| `dataset_freshness` | `dataset` | `GET /datasets/{dataset}/freshness` |
| `query_dataset` | `dataset`, `sql`, `limit?` | `POST /db/query` (`table` = dataset; the `ops:*` groups are never reachable — the tool sends no `group`) |
| `list_pipelines` | — | `GET /pipelines` |
| `get_pipeline` | `pipeline` | `GET /pipelines/{pipeline}/graph` |
| `list_jobs` | — | `GET /jobs` |
| `list_job_runs` | `job?`, `limit?` | `GET /jobs/runs` |
| `get_run` | `run` | `GET /jobs/runs/{run}` |
| `list_incidents` | `status?`, `severity?`, `q?`, `limit?` | `GET /objects?type=incident` |
| `get_incident` | `incident` | `GET /objects/{incident}` |
| `get_incident_briefs` | `incident` | `GET /incidents/{incident}/briefs` |
| `list_alert_rules` | — | `GET /alerts/rules` |
| `list_alerts` | — | `GET /alerts` |
| `list_expectations` | — | `GET /expectations` |
| `get_lineage` | `dataset` | `GET /lineage?store={dataset}` |
| `health` | — | `GET /health/details` |

A tool whose route belongs to a module this bundle lacks (Incidents need `ops`, briefs need
`action-requests`) still lists, and its call returns the host's `503` explanation as an error result.

**Resources:** `inspecto://glossary` (`text/markdown`, `docs/GLOSSARY.md` packaged into the module jar at
build time — the canonical vocabulary every agent should use), `inspecto://catalog` (replays `GET /catalog`)
and `inspecto://modules` (replays `GET /modules`).

**Not exposed, on purpose:** connections, secrets, settings, access and role administration, bundle import,
raw files, the `ops:*` SQL groups. No tool returns a secret.

## Write tools (AK-2b)

Plan §4.4. Every tool replays one existing route; none is a new write path. The **write** tools are annotated
`readOnlyHint: false`, `destructiveHint: false` (a proposal changes nothing until a person approves it) and need an
**agent Subject** (D-AK3): with a token from the console's own client they return `isError` with HTTP 403 "needs
sign-in through an agent client", while the read tools stay open. An `OBJECT` argument (`body`, `config`, `content`)
is passed on as the replayed route's JSON body, unchanged.

| MCP Tool | Kind | Arguments | Replays |
|---|---|---|---|
| `validate_config` | read | `type`, `config` | `POST /validate` (`{type, config}`); refusal findings come back as data |
| `dry_run_pipeline` | read | `pipeline`, `body?` | `POST /pipelines/authored/{pipeline}/dry-run` |
| `propose_change` | write | `kind`, `operation`, `id?`, `version?`, `content?`, `reason?` | the kind's own write route, below; `reason` rides as the request attribute `RequestAttrs.ATTR_CHANGE_REASON`, which `PendingChanges.hold` reads before the `X-Change-Reason` header (an in-process replay need not be header-safe; a header holding "—" or "→" made the replay throw — `AGENT-PROPOSE-500-1`, fixed 2026-11-24) |
| `list_requirements` | read | — | `GET /requirements` (W2 step 2: what a `requirement-kpi` proposal starts from) |
| `list_pipeline_versions` | read | `pipeline` | `GET /pipelines/{pipeline}/history` (W2 step 2: what a `pipeline-restore` restores) |
| `list_pending_changes` | read | `status?` | `GET /pending-changes` |
| `get_pending_change`, `diff_pending_change` | read | `change` | `GET /pending-changes/{change}`, `…/diff` |
| `withdraw_change` | write | `change` | `POST /pending-changes/{change}/withdraw` (author only) |
| `propose_action` | write | `body` | `POST /action-requests` (`canWorkIncidents`; held for approval by design) |
| `export_bundle` | read | `body` | `POST /bundle/export` |
| `preview_promotion` | read | `body` | `POST /promotion/preview` (`canAdminister`) |
| `run_pipeline_now` | write | `pipeline`, `body?` | `POST /pipelines/authored/{pipeline}/run` (`canAuthorWorkbench`; D-AK2: a person, never a machine) |

**`propose_change` allowlist** (`McpTools.CHANGE_ROUTES`, the builder tutorials' kinds, each route on
`PendingChanges.REPLAYABLE`): `pipeline` (create and update `PUT /pipelines/{id}/graph`, delete
`DELETE /config/pipeline/{id}`), `expectation` (`POST /expectations`, `PUT|DELETE /expectations/{id}`), `alert-rule`
(`/alerts/rules`), `job` (`/jobs`), `widget` and `dashboard` (`POST /components/{kind}`, `PUT|DELETE /components/{kind}/{id}`);
since AK-2 widening W2 step 1 (D-AK9), `decision-rule` (`POST /decision-rules`, `PUT|DELETE /decision-rules/{id}`) and
`notification-rule` (`POST /notifications/rules`, `PUT|DELETE /notifications/rules/{id}`), both `canAuthorWorkbench`,
which the machine agent set already held, so the set does not widen. **`notification-channel` stays out** although its
route reaches the hold: the plan kept it out for `PENDING-CHANGE-SECRET-READBACK-1`, and that defect does not reach a
Channel (`ChannelConfig` has no secret field and `GET /notifications/channels` returns every field), so adding it is
the operator's call. **W2 step 2** made the operations per kind (`CHANGE_ROUTES` is `kind → operation → route`;
`OPERATIONS` is the enum of every operation, `McpTools.operations(kind)` what one kind offers): `requirement-kpi`
offers `create` only (`POST /requirements/{id}/kpi`, `id` = the delivered kpi Requirement, `content` = the builder's
`{dataset, measure, timeField, grain, …}`), and `pipeline-restore` offers `restore` only
(`POST /pipelines/{id}/history/{version}/restore`, with the `version` argument and no `content`); both
`canAuthorWorkbench`. An operation the kind does not offer, a `version` on any other kind, or a restore without one is
`-32602` before any replay. Two read tools give the agent what those proposals start from, so it never guesses:
`list_requirements` (`GET /requirements`) and `list_pipeline_versions` (`GET /pipelines/{pipeline}/history`), both
ungated reads of config metadata, no data values. `McpToolRoutesTest.everyProposeChangeRouteReachesTheHold` refuses a
kind whose route is not on `PendingChanges.REPLAYABLE`. An update or delete needs `id`, a create or update needs `content`, a delete takes none; anything else is a
`-32602` before any replay. The answer is the route's own: for an agent, always the **202 Pending Change** (the
identity-keyed hold), carrying `agentClient` and `mcpTool: propose_change`.

**The endpoint's refusal list** (`McpTools.REFUSED`): a replay whose method and path look like the never-list
(`…/approve|decline|decision|reveal|retry|mark-failed`, `/promotion/apply`, `/apps` and `/access` writes) is refused
403 before it is dispatched, whoever asks — beside the host's agent gate. `McpToolRoutesTest` asserts no router can
produce such a path for any allowlisted kind and operation, and that every non-GET route a tool replays is in
`CapabilityManifest` (the §4.7 parity).

**Deferred:** `validate_app` (no route; `APP-KIT-DELIVERY-1`).

**Tests:** `ControlApiMcpWriteToolsTest` (real HTTP: the tools listed and annotated; an agent's `propose_change` is a
202 Pending Change naming the agent client, the tool and the reason, that another person's approval applies; a
console token refused every write tool but keeping the reads; validate, dry-run and run reach their routes;
withdraw; malformed proposals are `-32602`), `McpToolRoutesTest` (unit). Mutants that turned a test red: D-AK3 off,
the refusal list off, the reason header dropped, the replay not answering a hold, the replay skipping the
read-only check (on a read-only install `propose_change` is a 423 and no Pending Change is recorded; the reads answer).
`aReasonNoHeaderCanCarryIsHeldAndKeptExactly` (2026-11-24): a reason with "—", "→" and "€" is held and kept exactly;
putting the reason back on the header turns it red with the live run's "Illegal character found in header".
`ControlApiMcpProposeKindsTest` (W2, real HTTP, per added kind): the agent's create is held and nothing is live,
another person's approval applies it, its update and delete are held, the same token's direct call is held, a machine
agent's proposal is held; a kind outside the allowlist is `-32602` and records no Pending Change. Mutant: a kind mapped
to `POST /decision-rules/{id}/simulate` (not on `REPLAYABLE`) turned the allowlist test, the W10 person's-agent test and
the real-HTTP test red; in step 2, `requirement-kpi` mapped to `POST /requirements/{id}/deliver` did the same.
`McpToolRoutesTest.aKindRefusesAnOperationItDoesNotOffer` pins the per-kind operations.

**A replay that fails** with anything but an `ApiException` answers the tool `isError` with HTTP 500 and the exception's
class name plus the correlation id, and is **logged at ERROR with its stack under that correlation id**
(`McpRoutes.replay`), as a direct request's fault is (`ControlApi.fail500`). Until 2026-11-24 it was answered and
dropped, which is why the first live golden run's 500s left no trace.

## Audit

Every `tools/call` and `resources/read` leaves **one `AUDIT` row**, action `mcp.tool.called` /
`mcp.resource.read`, category `agent`, in the bound Space's event store:

- `actor` = the person (the Subject id; a machine's client id), `actorType` as the host derives it;
- `mcpTool` or `mcpResource`, `mcpProtocolVersion`, `mcpReplay` (`METHOD path`), `httpStatus` (the
  replay's), the IP, User-Agent and correlation id;
- **the agent client:** `mcpClient` = `clientInfo.name/version` from the request `_meta` (legacy: none —
  `initialize` is not remembered, so the User-Agent is all there is). It is **self-declared**, which the
  attribute name `mcpClientDeclared: true` says. Since AK-2a the **verified** client rides beside it as
  `agent_client` ([The agent gate](#the-agent-gate-ak-2a)) — the one an audit reader may trust.

The replay additionally stamps `via: "mcp"`, `mcpTool` and `mcpClient` onto its audit attributes, which the
replayed route's own `AUDIT` row carries (an export today, a write in AK-2). A refusal the replayed route
records is an `ACCESS_DENIED` row that `AuditTrail.accessDenied` writes without those extra attributes; the
endpoint's own `mcp.tool.called` row beside it (same actor, same correlation id, `httpStatus` 403) names the
tool. `tools/list`, `resources/list`, `server/discover` and `initialize` describe the server and are not audited.

Note for policy authors: the access-policy decision point sees the outer request as action `write` on
`/mcp` (every POST that is not `canOperateRuns` is `write`, as for `/db/query`), and each replay as its own
route's action (`read`). A policy can therefore switch the endpoint off for a population by denying `write`
on `/mcp`.

## Identity

Per §7.3 and D-UE5 (signed: both modes, delegated first).

- **Delegated person — the target for interactive agents** (Claude Code, Gemini CLI, Codex). Design:
  1. `-Dauth.oidc.agentClients=<client ids>` (**built in AK-2a**) — an allowlist of agent clients that **stay
     persons**. A token whose `azp` (else `client_id`) is listed and that is **not** a client-credentials token
     (`gty`) is admitted like the SPA client's (signature, issuer, audience, expiry — no new trust root), is not
     refused by the machine client pin (`auth.oidc.clients`), holds the person's Roles **less
     `Roles.AGENT_NEVER_CAPABILITIES`** (`canApproveChanges`, `canApproveLinkExpansions`,
     `canRevealLinkEntities` — an agent never approves and never reveals a masked value), and carries the
     client id as `Subject.agentClient()`. The actor stays the person. A client-credentials token from a listed
     client stays a machine's, judged as before — **when `gty` says so**: Keycloak sets no `gty`, so the admin guide
     forbids a service account on an agent client, and an IdP naming the client only in `cid` / `appid` needs a
     mapper to `azp` (else the token is unmarked; security review 2026-11-20, below confidence 8). Boot refuses: a client in both `auth.oidc.clients` and
     `auth.oidc.agentClients`; the SPA client (`auth.oidc.clientId`) as an agent client; an allowlist without
     `auth.oidc.audience`. Bearer path only — a gateway assertion is never an agent's.
  2. The MCP authorization model (**built in AK-2a**): Protected Resource Metadata (RFC 9728) at
     `GET /api/v1/.well-known/oauth-protected-resource` (and `/api/v1/spaces/{id}/…`), public (an exact-match
     host public path), answering `resource` (the endpoint's URL: `-Dmcp.publicOrigin`, else the request's scheme
     and validated `Host`, plus the request's own `/api/v1[/spaces/{id}]` prefix), `authorization_servers`
     (`Authenticator.issuer()` — the OIDC bearer issuer), `scopes_supported` (`-Dauth.oidc.scopes`, else
     `openid`) and `bearer_methods_supported: [header]`; 404 when no Authenticator names an issuer. The host
     serves nothing outside `/api/v1`, so the RFC's root path needs a reverse-proxy alias
     ([admin guide](admin-guide-mcp.md)). A `401` from `/mcp` — only from `/mcp`, through the auth SPI's
     `AuthChallenges` registry — carries `WWW-Authenticate: Bearer resource_metadata="<origin>/api/v1/.well-known/oauth-protected-resource"`,
     so an MCP client runs authorization code with PKCE against the IdP itself. The token's audience must be
     the install (`auth.oidc.audience`).
  3. Client registration: the agent client is registered in the customer's IdP by its administrator (the
     specification's Client ID Metadata Documents and the deprecated dynamic registration are the IdP's
     business, not Inspecto's).
- **Machine — unattended agents** (**built in AK-2c**, 2026-11-25). Client credentials, admitted per Space
  exactly as any machine (`MachineTrust`, `Roles.admitMachine`, the Space's `identity.machineClients`). A machine
  client also listed in `-Dauth.oidc.agentMachineClients` (a subset of `auth.oidc.clients`; boot refuses an entry
  that is not a listed machine client, is the SPA client, or is set without `auth.oidc.clients`) is a Subject that is
  **both** `machine()` and `agent()`: id `client:<client>`, `agentClient` = the client id. At admission it keeps
  `Roles.MACHINE_CAPABILITIES` plus `Roles.AGENT_MACHINE_CAPABILITIES` (`canAuthorWorkbench`, `canAuthorAlertRules`,
  `canWorkIncidents`, `canOperateRuns`) where its roles grant them, never an `AGENT_NEVER_CAPABILITIES` one; an
  unlisted machine keeps `canOpenInboundInvestigation` only, as before. Being an agent, every config write it makes
  is held as a Pending Change, the agent gate applies, and it never approves; it may not run a Pipeline
  (`AgentGate.PERSON_ONLY`, D-AK2). The Pending Change records `authorMachine` (the token's `sub`), and
  `PendingChanges.authorNow` replays an approved machine change as that machine: re-admitted by the Space (403 when
  the Space no longer names the client) and filtered to what a machine agent keeps, so approval never widens it.
- **What is built:** the endpoint accepts any Subject the host's `Authenticator` resolves — a person's own
  token (the SPA client), a person through an allowlisted agent client (AK-2a), an admitted machine, or an admitted
  machine agent (AK-2c).

## The agent gate (AK-2a)

Plan §4.1: **an agent's write is held because of who is writing, not because it came through `/mcp`** — the same
agent token calling a route directly is treated the same.

- **The marker** is `Subject.agentClient()`, set only by `OidcAuthenticator` (above). Every audit row of that
  request carries it as `agent_client` (`AuditAttrs.AGENT_CLIENT`, also a column of the audit CSV export): rows
  written through `AuditTrail` take it from the Subject (`located`), every other emitter's from the request MDC key
  `agentClient`, which the host sets at authentication and clears when the request ends (`EventLog.emit` stamps a
  chained row that lacks it).
- **The identity-keyed hold:** `PendingChanges.hold` and `holdSet` hold a marked Subject's write **always**
  (`PendingChanges.agentRule`): with the kind's policy rule its approver applies, else
  `ApprovalPolicy.DEFAULT_APPROVER` (`canApproveChanges`); **four eyes always** (D-AK1 — neither the agent's token
  nor the person it acts for may approve; the single-administrator alternative is not built). The Pending Change
  records `agentClient` and, through the MCP endpoint, `mcpTool`; the Pending Changes screen shows "Proposed by
  *person* via agent client *client*" and the list's Author column "*person* via *client*".
- **Default deny at the host** (`AgentGate`, applied in `ControlApi.routeDispatch` after the policy stage and in
  every `ControlApi.replay`, so the MCP tools and an approved agent change replayed as its author pass it too).
  Every route is in exactly one bucket, by its registered method and pattern:
  1. **held** — on `PendingChanges.REPLAYABLE` (the routes that reach the hold), not on the never-list and not on
     `AgentGate.NOT_HELD`: `POST /origin/upgrade` (a Space Template upgrade that adds and updates nothing rewrites the
     origin record with no hold) and `POST /pipelines/{id}/save-as-template` (copies schema files before its hold
     and leaves them on a 422) are refused to an agent instead. A sweep of every other `REPLAYABLE` route
     (2026-11-20) found each reaches the hold before it persists anything; one more fast path that skipped it (an
     add-only Entity List change expiring within 24 h) now holds an agent's change. Residual, by design: an approved
     Dataset delete retires an imported Dataset's Pipeline, and an approved rename rewrites dependents, as they do
     for a person — the guards that refuse those under a policy (`holdRefusing*`) read the policy only;
  2. **allowed** — reads (GET), and `POST /mcp`, `/validate`, `/pipelines/authored/{id}/dry-run`,
     `/bundle/export`, `/promotion/preview`, `/pending-changes/{id}/withdraw`, `/action-requests` (held by its
     own design), `/db/query` (the read the AK-1 `query_dataset` tool replays) and `/pipelines/authored/{id}/run`
     (D-AK2: a delegated person only, never a machine), and `/pending-actions/{id}/withdraw`;
  3. **actions** (**AK-2d**, 2026-11-25) — `AgentGate.ACTIONS`, the operational routes of plan §4.6: `POST
     /runs/{id}/trigger|pause|resume|reprocess|replay-rejects|drain`, `…/retries/retry-now|cancel`,
     `…/quarantine/release` (since 2026-11-25, widening plan W1),
     `POST /jobs/{id}/trigger`, `POST /jobs/runs/{id}/replay`, each still gated `canOperateRuns` (`AgentGate.RUN_ACTIONS`);
     and since **AK-2e** the case actions (`AgentGate.CASE_ACTIONS`, [Case actions](#case-actions-ak-2e)). A **person's**
     agent takes them at once, within the person's rights (D-AK5 as signed), audited with `agent_client`. A
     **machine** agent's are held **before the handler** as a [Pending Action](#pending-actions-ak-2d);
  4. **refused (403)** — everything else: every route that only calls `holdRefusing*` (bundle import, onboarding
     imports, Dataset registration — they do not hold when no policy covers the kind), every other read-shaped
     POST, and the never-list.
  `AgentGateParityTest` pins every non-GET `CapabilityManifest` entry and exemption to one bucket, the held bucket
  to `REPLAYABLE` less the never-list, and the allowed list to real routes in non-writing categories.
- **The never-list** overrides the other buckets, on every method: any `…/approve`, `…/decline`, `…/decision`;
  every route gated by an `AGENT_NEVER_CAPABILITIES` capability (the Action Request approver's retry and
  mark-failed, `GET /screening/hits/{id}/reveal`, the reveal and link-expansion approval routes, the triage-run
  reads); `/promotion/apply`; every `/apps` and `/access/*` write; `/agent/approvals/{id}/decision`.
  `POST /pending-changes/{id}/approve|decline` also refuses a marked Subject in the route itself.
- **An approved agent change replays as what proposed it:** `PendingChanges.authorNow` reads the record's
  `agentClient`, sets the marker on the rebuilt author Subject and removes `AGENT_NEVER_CAPABILITIES`, so approval
  never widens what the change runs with; a machine author is re-admitted and filtered too (AK-2c, [Identity](#identity)).
- **Tests** (real HTTP unless noted): `ControlApiAgentClientTest` (a direct write held with no policy while the
  person's own token writes; bundle import and a settings write 403; reads open; neither the agent nor its person
  approves; another person's approval applies it and the applied row names the agent client; every audit row of
  an agent request names it; an agent withdraws its own proposal), `AgentGateParityTest` (unit, over the
  manifest), `OidcAgentClientTest` (the marker, the dropped capabilities, the client-credentials case, the pin, the
  boot refusals, `issuer()`), `ControlApiMcpDiscoveryTest` (the metadata, the 401 challenge on `/mcp` only, the
  pinned origin, no issuer → no metadata). Nine mutants each turned a test red: the hold ignoring the marker; the
  gate off; the gate and the approve check off together; `authorNow` dropping the marker; the MDC stamp off; the
  OIDC capability drop off; the pin refusing an agent client; the boot overlap check off; the 401 challenge off.
- **Basic:** no module (D-UE4).

## Pending Actions (AK-2d)

D-AK6 as signed (2026-11-24) and the operator's answers of 2026-11-25: a machine agent's operational action is held
as a **Pending Action** (`GLOSSARY.md`) — the Pending Change machinery under its own term; a Pending Change stays
config only.

- **The hold** — `PendingActions.holdIfMachineAction`, called by `ControlApi.routeDispatch` and every
  `ControlApi.replay` right before the handler (after the capability, policy and agent gates, so a machine still needs
  `canOperateRuns`): a Subject that is `machine()` and `agent()` calling an `ACTION` route gets **202**
  `{status: pending, ran: false, pendingAction}` and nothing runs. One pending record per action (same method and
  path; else 409).
- **The record** — `pending-actions/pa-<UTC stamp>-<6 hex>.json` in the Space's config tree: `action`
  (`METHOD path`), `route` (the matched pattern), the author (`client:<id>`), its roles, `agentClient`,
  `authorMachine`, `mcpTool`, `reason` (`X-Change-Reason`), `createdAt` / `expiresAt` (the approval policy's clock),
  `approverCapability: canApproveChanges`, `fourEyes: true`, and the request (method, path with query, body). Signed
  with the Space's Pending Change key under its own MAC domain (`PendingChanges.domainMac`, domain `pending-action`),
  so neither record type verifies as the other; written under the Pending Change store's lock.
- **Routes** (`PendingActionRoutes`): `GET /pending-actions` (`?status=`, capped at 500 with `total` and
  `truncated`, no raw request), `GET /pending-actions/{id}` (the whole request an approval replays: method, path with its query, and body — the phase-2 security review, plan §6.3),
  `POST …/{id}/approve` and `…/decline` (`canApproveChanges`; four-eyes; the approver roster; an agent refused by
  the gate and by the route), `POST …/{id}/withdraw` (the asking machine only, `self-service` exemption).
- **Approve** — under the store lock: MAC verified, expiry applied, still pending; the record must still name an
  `ACTION` route and its path must match it (else 409, nothing dispatched); the request replays as the machine
  (`PendingChanges.authorNow`: re-admitted by the Space, filtered to what a machine agent keeps) with the record as
  `PendingActions.ATTR_APPROVED_ACTION`, which lets that one request through the hold. Outcome: `approved` (2xx),
  `stale` (409 — the run moved on) or `failed` (any other status, recorded as `appliedStatus`). Every transition is
  an `AUDIT` row `pending-action.proposed|approved|stale|failed|declined|withdrawn|expired` (category `operation`).
- **Operations MCP Tools** (lane C, `McpTools.OPERATIONS_TOOLS`): reads `list_runs` (`GET /runs`), `get_run_status`
  (`GET /runs/runs/{runId}`), `get_run_batches` (`GET /runs/{run}/batches`), `list_quarantined_files`
  (`GET /runs/{run}/quarantine`, W1), `list_pending_actions`,
  `get_pending_action`; writes (`write: true`, so a console token is refused, D-AK3) `operate_run` (`action` one of
  `trigger`, `pause`, `resume`, `reprocess`, `replay-rejects`, `retry-now`, `cancel-retries`, `drain`,
  `release-quarantined` (body `{path, reprocess?}`, W1) → the matching
  `POST /runs/{run}/…`, optional `body` and one-line `reason` as `X-Change-Reason`), `trigger_job`, `replay_job_run` and
  `withdraw_action`. Each replays its route as the caller, so the gate above decides: at once for a person's agent, a
  Pending Action for a machine. `McpToolRoutesTest` expands `operate_run` over every action and pins each to a
  `canOperateRuns` manifest route.
- **The screen:** the Pending Changes screen has two tabs, *Pending Changes* and *Pending Actions*
  (`PendingActionsPanelComponent`): a list (Waiting / All), the action, the machine that asked, its reason and outcome;
  Approve and Decline for a holder of `canApproveChanges`.
- **Agent Skills and golden tasks:** `diagnose-failed-run` (read only) and `operate-runs` (act after diagnosis; ask
  before a drain or reprocess; report a `pa-…` id when held); the operations suite `agent-kit/golden/operations.json`
  (ten tasks: diagnose without acting, resume, re-run, drain asks first, a machine's resume held, a machine never
  self-approves, since W1 a quarantined file found and released, a machine's release held, and since W9 two Space
  health audits, § Read-only Agent Skills (W9)), graded by `tools/run-agent-golden.mjs`'s `agent-action`, `no-agent-action` and `pending-action`
  checks. Its live run is open, as for the developer suite's other clients.
- **Tests:** `ControlApiPendingActionsTest` (real HTTP: a person's agent pauses and resumes at once and its row
  names the agent client, a business user's agent is 403; a machine's pause is 202 and nothing ran, a second one
  409, the machine's approve 403, the list, another person's approval runs it, a second approve 409; withdraw by the
  machine only; a declined action never runs; a record naming a non-action route is not replayable; since W1 a
  person's agent releases a quarantined file at once while the route's path jail still answers, and a machine's
  release is held with the file still quarantined until a person approves),
  `AgentGateParityTest.theActionBucketIsExactlyTheNamedOperationalRoutes`, and `tools/run-agent-golden.test.mjs`,
  which fails when the grader's `isAction` misses any method and route in `AgentGate.ACTIONS` (the grader counted
  POST rows only until W1, so the PUT that attaches an Investigation to a Case went ungraded).

## Case actions (AK-2e)

Phase 3, the Incident and Case analyst (plan §1.1, §4.6 analyst rows, §6.4). The operator's answers of 2026-11-25:
exactly the §4.6 list (no Incident record edits), a new Case for people only, and `CASE-TRANSITION-FOUR-EYES-1` left a
board row. No new machinery: the routes join the action bucket, so the gate and the [Pending Action](#pending-actions-ak-2d)
hold above apply unchanged.

- **The routes** (`AgentGate.CASE_ACTIONS`), each still on its own gate: `POST /objects/{id}/comments` (the
  `collaboration` exemption — anyone who can see the object), `POST /incidents/{id}/briefs`,
  `POST /objects/{id}/ack|assign|resolve|transition`, `POST /alerts/{id}/ack|resolve` (`canWorkIncidents`), and
  `POST /cases/from-entities` (`canManageIncidents`). `AgentGateParityTest` pins the list and each route's gate.
- **A person's agent** takes them at once, within the person's rights, audited with `agent_client`; the object's own
  history records the person for an ack, assign, resolve or transition (`actorOf`: the Subject, never a body `actor`).
- **A machine agent's** are held as Pending Actions, approved by the change approvers and replayed as the machine — a
  comment it asked for is authored `client:<id>`. It never opens a Case: `canManageIncidents` is not in
  `Roles.AGENT_MACHINE_CAPABILITIES`, so the hold's capability check answers 403 and leaves no record. A comment is
  exempt from a capability, so a machine's hold on it needs `canWorkIncidents` (`AgentGate.MACHINE_HOLD_CAPABILITY`):
  otherwise a machine with no right could queue comments on any id (phase-3 security review, plan §6.5).
- **A Brief** files at most a `pending` Action Request, whose makers are the asking person and the Brief's agent
  identity; its own four-eyes decide gate refuses both, and an agent never decides one (the never-list).
- **Analyst MCP Tools** (`McpTools.CASE_TOOLS`, all `write: true`): `add_comment`, `build_incident_brief`,
  `work_incident_or_case` (`action` one of `ack`, `assign`, `resolve`, `transition` → `POST /objects/{id}/…`),
  `work_alert` (`ack`, `resolve`) and `open_case_from_entities`, each with a one-line `reason`. The reads were
  already there (`list_incidents`, `get_incident`, `get_incident_briefs`, `list_alerts`).
  `McpToolRoutesTest.everyCaseToolReplaysACaseActionAndEveryCaseActionHasATool` pins tools to routes both ways.
- **Agent Skills and golden tasks:** `triage-incident` (read, Brief, comment, accept, assign — never close) and
  `work-cases` (Workflow moves, resolve, Alerts, open a Case — always on the person's yes); the analyst suite
  `agent-kit/golden/analyst.json` (six tasks: read without acting, Brief and comment, accept and assign, resolve a
  Case, a machine's resolve held, a machine never opens a Case). `ACTION_PATH` in `tools/run-agent-golden.mjs` covers
  the case routes. Its live run is open.
- **The screen:** unchanged — the *Pending Actions* tab lists case actions too, its detail showing the comment text or
  Workflow move it will run.
- **Tests:** `ControlApiAgentCaseActionsTest` (`inspecto-case-management`, real HTTP: a person's agent comments on,
  accepts and assigns an Incident at once and its row names the agent client, a business user's agent is 403 and
  nothing is held; a machine's Case transition is 202 and nothing changed, its approve 403, the detail shows the
  move, another person's approval runs it; a machine's comment is held and on approval is the machine's; a machine's
  new Case is 403 with no record; a machine without `canWorkIncidents` cannot queue a comment), `AgentGateParityTest`,
  `McpToolRoutesTest`.

## Link Analysis (AK-2f)

Phase 3b, the analyst in Link Analysis (plan §6.6). The operator's answers of 2026-11-25: the read-shaped Link Analysis
queries open to every agent; a person's agent works in Drafts, attaches an Investigation to a Case and starts graph
runs; a machine agent works in Drafts through a new, narrower right, every write held; a machine sees an Investigation
only when a lead adds it as a member. Promote, reveal and expand decisions stay people-only.

- **Queries** (`AgentGate.LA_READS`, in the allowed bucket): the 19 read-shaped `/inv` POSTs — projection, neighbours
  and their preview, multi-Dataset projection, unsaved expand, overlap profile, reference rows, case history, entity
  resolve and suggest, deep-link resolve, recursive paths, presets, the query builder, branching and temporal
  patterns, Investigation replay, dossier verify. They save no analysis (they may add to the Space's alias book and the
  audit log, as for any caller), run under the Space's masking — only `reference-rows` and `deep-links` with `reveal`
  depend on the caller, and they need `canRevealLinkEntities`, which no agent holds — and the Link Analysis rate
  classes. No hold, for a machine either.
- **The Draft right `canDraftInvestigations`** (`Roles.CAN_DRAFT_INVESTIGATIONS`): gates the five Draft writes
  (`POST /inv/investigations/{id}/drafts`, `…/drafts/{d}/ops|undo|discard|rebase`); promote stays
  `canManageIncidents`. `Roles.holds` makes `canManageIncidents` imply it, and every capability check
  (`ApiContext.requireCapability`, `requireCapabilityIn`) goes through `holds`, so no person lost a right and no role
  table changed. `AGENT_MACHINE_CAPABILITIES` keeps it; because admission strips `canManageIncidents` first, a
  machine's role must grant `canDraftInvestigations` by name. An implied grant yields to an Access profile that
  denies `canDraftInvestigations` by name (`ApiContext.impliedButDenied`, phase-3b review): the authenticator strips
  only granted capabilities, so the deny is asked for at the check. In the Link Analysis product set, the Access
  catalogue (`linkgraph.draft`) and the LA app's Access page.
- **Actions** (`AgentGate.LA_ACTIONS`): the five Draft writes, `PUT /inv/investigations/{id}/case` and
  `POST /inv/graph/runs` with its cancel. A person's agent acts at once within the person's rights; a machine agent's
  Draft writes are Pending Actions replayed as the machine — the Draft route re-checks membership by Subject id, so a
  machine no lead added gets 404 and the action ends `failed`. A machine's attach and graph run fail the hold's
  capability check (403, no record); its graph-run cancel needs `canRunLinkGraphAnalysis`
  (`AgentGate.MACHINE_HOLD_CAPABILITY`, the cancel being self-service exempt).
- **Refused to every agent:** promote, a direct Investigation edit (`…/ops`, `…/undo`, `…/reorder`), members,
  hand-off, template, embeds, snapshots, uploads, reveal, and expand approve/deny (`AgentGateParityTest` pins each).
  The MCP endpoint's own refusal list also catches `…/promote` and `…/deny`.
- **MCP Tools** (`McpTools.LA_TOOLS`): reads `list_investigations`, `get_investigation` (a view of working set, log,
  coverage, Case, members, references, measures, data changes or Drafts), `get_draft`, `explore_entity`,
  `run_link_query` (`kind` → one of the queries above), `run_preset`, `list_graph_algorithms`, `list_graph_runs`,
  `get_graph_run`; writes `open_draft`, `work_draft` (`ops`, `undo`, `discard`, `rebase`),
  `attach_investigation_to_case`, `start_graph_run`, `cancel_graph_run`. `McpToolRoutesTest` pins every write tool to
  an action and every action to a tool, and every `/inv` write a tool makes to a named action or query.
- **Agent Skill and golden tasks:** `analyse-in-link-analysis` (explore, preview before expanding, paths and patterns,
  write only into your own Draft, hand it to a person; never promote or reveal); the suite
  `agent-kit/golden/link-analysis.json` (six tasks on an LA showcase Space: a path found without acting, a Draft by a
  person's agent, a machine's Draft held, never promote, a graph run's top result, a machine never starts a graph run).
  The grader's `ACTION_PATH` covers the Draft writes and graph runs; promote and deny count as never-list attempts. Its
  live run is open.
- **Tests:** `ControlApiAgentLinkAnalysisTest` (`inspecto-geo-link`, real HTTP: an agent's projection answers 200
  and, in a masking Space, carries no raw key; a person who holds `canManageIncidents` but whose Access profile denies
  `linkgraph.draft` cannot open a Draft, and can once the profile is gone; a person's agent opens a Draft and adds an op at once, its promote and direct edit 403; a member machine's
  Draft open is 202, nothing opened, its approve 403, another person's approval opens it with the machine as actor; a
  machine that is no member gets 404 on approval; a machine's attach, graph run and promote are 403 with no record),
  `RolesImpliedCapabilityTest`, `AgentGateParityTest`, `McpToolRoutesTest`; `ControlApiDraftsTest` (23 tests, every
  Subject holding only `canManageIncidents`) fails entirely with the implication removed. A graph run over real HTTP
  needs a built index, so its action is proven at the gate (`AgentGateParityTest`), not end to end.

## Space health, storage and scores (W3)

Built 2026-11-25 (widening plan §3 W3). Read tools only (`readOnlyHint: true`), each replaying one GET route the
agent gate already allows; no gate change. The route's own capability decides, so a caller without it gets that
route's 403 through the tool, as on the direct call.

| MCP Tool | Arguments | Replays | Capability |
|---|---|---|---|
| `get_space_integrity` | — | `GET /integrity` | `canAuthorWorkbench` |
| `get_space_origin` | — | `GET /origin` | `canAuthorWorkbench` |
| `preview_template_upgrade` | — | `GET /origin/upgrade` (report only; applying is `POST`, `canAdminister`, no tool) | `canAuthorWorkbench` |
| `get_dataset_storage` | `dataset` | `GET /datasets/{dataset}/storage` (also 403 while the Space's Add data is off, `selfImport`) | `canUploadData` |
| `verify_audit_chain` | — | `GET /audit/verify` (no `from`/`to`: one call walks at most 100,000 records) | `canAdminister` — a person's agent only; no machine agent holds it |
| `get_risk_score` | `model`, `key`, `investigation?` | `GET /risk-scores/{model}/{key}[?investigation=]` | `canWorkIncidents` |
| `get_anomaly_score` | `model`, `key` | `GET /anomaly-scores/{model}/{key}` | `canWorkIncidents` |
| `get_prediction` | `kind` (`churn-model`, `forecast-model`, `segmentation-model`), `model`, `key` | `GET /predictions/{kind}/{model}/{key}` | `canWorkIncidents` |

- **Masking.** The three score routes echo the entity key as the Space `masked:` token (`keyMasked: true`) unless the
  Subject holds `canRevealLinkEntities`, which an agent never does (`Roles.AGENT_NEVER_CAPABILITIES`). So an agent
  acting for a person who may reveal still reads the token a person without reveal sees. `get_risk_score` accepts
  that token back as the key (the route resolves the alias); the Anomaly and prediction routes take the stored key only.
- **Path segments.** An in-process replay matches its route on the raw path, so a `%3A` would reach the route still
  encoded and `masked:` would never be recognised. The W3 routers keep `:` and `@` as they are (`McpTools.idSeg`; both
  are legal in a segment, and the ID argument pattern admits no `/?#%`).
- **No tool:** `GET /screening/hits/{id}/reveal` (never-list), the score previews (`POST …/preview`, W6 group E),
  `GET /risk-score-lists/{model}`.
- **Tests:** `McpToolRoutesTest.everySpaceHealthAndScoreToolReplaysItsGatedGetRoute` (route and manifest capability
  per tool; no tool reaches the Screening Hit reveal); `ControlApiMcpTest.eachSpaceHealthAndScoreToolAnswersItsRoutesOwn403`
  (real HTTP, per tool: the token without the capability gets `HTTP 403` through the tool and 403 on the direct call,
  the holder passes the gate); `anAgentReadsTheKeyMaskedEvenForAPersonWhoMayReveal` in `ControlApiRiskScoreTest`,
  `ControlApiAnomalyScoreTest` and `ControlApiPredictionReadTest` (an agent Subject narrowed as `OidcAuthenticator`
  narrows it reads the masked token over seeded scores). `McpRoutesManifestParityTest` is unchanged: the tools add no
  route to the module.

## Read-only Agent Skills (W9)

Built 2026-11-25 (widening plan §3 W9). Two Agent Skills that only read; no tool, route or gate change.

- **`review-pending-change`** — for the person who approves: reads a Pending Change (`get_pending_change`,
  `diff_pending_change`) or a Pending Action (`get_pending_action`), traces what it touches (`describe_dataset`,
  `get_lineage`, `list_alert_rules`, `list_expectations`, `get_pipeline`, `list_runs`, `get_run_batches`), checks it
  with `query_dataset` and writes a brief: what changes, what it touches, what the data says, what to check. It never
  approves or declines, even when asked; the gate refuses it anyway. **Gap:** no MCP Tool lists Widgets or Dashboards,
  so the skill tells the approver which Dashboards it could not check.
- **`audit-space-health`** — one report per Space: `health`, `get_space_integrity` (files changed outside the server,
  broken links, duplicates, activation holds), `get_space_origin` and `preview_template_upgrade`, `list_datasets`,
  `get_dataset_storage` and `dataset_freshness`, and Pending Changes and Actions waiting long or expired. A 403 on a
  read is reported as "not checked", never worked around. Accepting a hand edit or applying an upgrade stays a person's.
- **Golden tasks.** The reviewer is in `developer.json` (`dev-08-review-brief`, `dev-09-review-never-approves`): it
  reviews the Expectation Pending Change `dev-01` left, so it needs that suite's Space, data and ground truth (3 rows
  fail), and the approver it acts for is a second person of the developer role. The audit is in `operations.json`
  (`ops-09-audit-health`, `ops-10-audit-reads-only`): a hand edit of the import Pipeline's file and the pending
  proposal `dev-07` left must appear in the report, and a request to tidy up changes nothing. Both pairs are read-only
  (`no-pending-change`, `no-agent-action`, `no-never-list-attempt`, `no-direct-write`); the new grade check
  `tool-called` (`tools/run-agent-golden.mjs`) asserts the agent's own answered call of a tool (`diff_pending_change`,
  `get_space_integrity`, `get_dataset_storage`). The machine-run (scheduled) audit has no golden task yet. Their live
  run is part of W4, not of W9.

## Security review (2026-11-20)

An independent review of the AK-1 change (the module, the `/mcp` rate-limit line, the manifest exemption)
found **no vulnerability at confidence 8 or above**. Paths it checked and why each is closed:

- **Route or query injection through arguments:** id arguments match `[A-Za-z0-9_][A-Za-z0-9._:@-]{0,199}`
  (no leading `.`, no `/ ? # & %`) and are URL-encoded; free-text values go only into query values, encoded,
  so `q` cannot add `&type=case`. Method and route prefix are fixed per tool.
- **`query_dataset` → `ops:*`:** `/db/query` takes the group only from `body.group`, which the tool never sets;
  `table: "ops:x"` names a table, not a group. SqlGuard and masking run in the same handler for the same Subject.
- **Gates a replay skips** versus a direct call: authentication, the `X-Actor` refusal and the Space check
  ran on the outer request for the same Subject and Space; `authorize` and the module switch run in the
  replay; write-root and idempotency are skipped, and every AK-1 tool is a read. Request attributes the
  replay does not copy are outputs or share/embed grants, so leaving them out can only remove access.
- **Replay attributes:** one fixed key (`ATTR_AUDIT_ATTRS`) with fixed inner keys; a client names none.
- **Authentication bypass:** the Subject check precedes everything (and covers a module dropped onto Basic).
- **Origin / CSRF:** a present `Origin` must equal `-Dcontrol.cors` exactly; a request with none is not a
  browser and still needs a credential.
- **Leakage:** a failed replay returns only the exception's class name; route messages are the routes' own.
- **Audit injection:** client-declared name and version are cut to 100 characters, control characters
  replaced, and flagged `mcpClientDeclared`.

Residual, accepted: `get_incident` replays `GET /objects/{id}`, which also answers non-Incident objects —
the direct route already does, under the same gates. The negative tests were each falsified (seven mutants:
the Subject check, replay-as-caller, the Origin check, the header check, the audit row, the id pattern and
the unknown-argument refusal — every one turned a test red).

## Security review, AK-2a (2026-11-20)

An independent review of the AK-2a change found **no vulnerability at confidence 8 or above**. It checked: the gate
keying on the matched route (Space prefixes, encodings, HEAD → 405, no method override, one listener); the replay
door and `authorNow`; the MAC over the Pending Change record (`agentClient` included); every allowed POST; approval
and reveal (four eyes against the person, the dropped capabilities failing closed in every handler that checks
them); Idempotency-Key replays (after the gate, never re-executing); `requireCapabilityIn` and Space admission
re-authenticating (the marker and the drop re-applied); the MDC key's life; the metadata route's Host and path
handling. Before it, the verification pass found one unheld write (an agent's short-lived Entity List add, fixed
and tested) and a sweep of every `REPLAYABLE` route found two more paths that write without or before the hold
(refused to agents: `AgentGate.NOT_HELD`; defects `ORIGIN-UPGRADE-UNHELD-1`, `SAVE-AS-TEMPLATE-LEFTOVER-1`).
Below confidence 8 and handled: the IdP rules in the admin guide (no service account on an agent client; the client
named in `azp` / `client_id`), and rows from emitters other than `AuditTrail` during an approved agent replay now
carry `agent_client` too (`ControlApi.replay` sets the MDC key for an agent Subject). Accepted: an agent may withdraw
its person's own proposals and writes the reason text the approver reads; the Host header is reflected into the
metadata's `resource` when `-Dmcp.publicOrigin` is unset.

## Security review, AK-2b (2026-11-20)

An independent review of the write tools found **no vulnerability at confidence 8 or above**. Checked: no argument
chooses a route (`kind` and `operation` are enums over fixed tables; every path argument matches the id pattern and
is URL-encoded, so no `/`, `%`, `?`, `&` or leading `.`); the `reason` header (now at most 500 characters and one
line, refused `-32602` otherwise); body size (256 KiB at the endpoint); every `propose_change` route lands in the
held bucket and holds before it writes; a console token writes through no tool; a replay inherits only the fixed
attributes, so an approval marker never reaches an MCP replay, and the approve replay never raises `Held`; the
`replay-gated` category changes no audit row and makes a write tool on a read-only install a 423, stricter than
before. Accepted, below confidence 8: `export_bundle` replays an ungated read-shaped route, as the person could call
directly; whether an agent's person may approve its Action Request is the Action Request policy's question.

## Security review, phase 1 close (2026-11-24)

An independent read-only review of AK-2 phase 1 as a whole (AK-2a and AK-2b together, plan
[§6.1](../../../../superpower/2026-11-20-agent-kit-ak2-plan.md)) found **no vulnerability at confidence 8 or above** in a
bearer-token OIDC deployment. It re-checked: the gate keying on the matched route's registered method and pattern
(path quirks change only which route matches, never the bucket; every RouteModule registers through one router; no
HEAD route, OPTIONS answered before routing, no method override; the gate before the idempotency cache); the never-list
and its alternate doors (`ConfigTargetGuard` refusing `roles.toon`, `access-policies.toon`, `approval.toon` and
`registry/`; access kinds refused on the generic component door); a sweep of every held route for a write before the
hold; the allowed bucket; approval and replay (agent refused in `decide`, four eyes on the person, `verifyApproved`'s
kind, name and hashes, the MAC, `authorNow` and the replay passing the gate again); MCP argument handling; the audit
stamp. Observations below 8, filed: `AGENT-GATEWAY-UNMARKED-1` (gateway-assertion mode never marks an agent — confirmed
in `OidcAuthenticator.verify`), `PENDING-CHANGE-SECRET-READBACK-1`, `DRYRUN-PREVIEW-UNMASKED-1`. Already known and
accepted: marking depends on the IdP naming the client in `azp` / `client_id` and on no service account on an agent
client (admin guide); an approved rename or Dataset delete has side effects the diff does not show (above).

## Deferred

- Phase 2 and 3 of the plan ([`2026-11-20-agent-kit-ak2-plan.md`](../../../../superpower/2026-11-20-agent-kit-ak2-plan.md)
  §1.1, §6): the machine agent capability set (AK-2c), operational actions (AK-2d), case actions (AK-2e). D-AK5, D-AK6
  and D-AK7 signed 2026-11-24 (plan §8): a person's agent takes operational and case actions within the person's rights,
  at once and audited; a machine's actions are held as **Pending Actions** (the Pending Change machinery, its own term).
- `validate_app` (no route; `APP-KIT-DELIVERY-1`).
- The plan's AK-2b end-to-end proof on a Standard install with Keycloak (validate → dry-run → propose → a second
  person approves; `propose_action` → approve → sent): the real-HTTP tests prove the same chain with an in-test
  Authenticator; the Keycloak run belongs with the phase's golden tasks (AK-4).
- Prompts, `subscriptions/listen`, progress notifications, the tasks extension, multi round-trip requests.
