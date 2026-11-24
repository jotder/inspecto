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
| `propose_change` | write | `kind`, `operation`, `id?`, `content?`, `reason?` | the kind's own write route, below; `reason` rides as the request attribute `RequestAttrs.ATTR_CHANGE_REASON`, which `PendingChanges.hold` reads before the `X-Change-Reason` header (an in-process replay need not be header-safe; a header holding "—" or "→" made the replay throw — `AGENT-PROPOSE-500-1`, fixed 2026-11-24) |
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
(`/alerts/rules`), `job` (`/jobs`), `widget` and `dashboard` (`POST /components/{kind}`, `PUT|DELETE /components/{kind}/{id}`).
An update or delete needs `id`, a create or update needs `content`, a delete takes none; anything else is a
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
     `POST /jobs/{id}/trigger`, `POST /jobs/runs/{id}/replay`, each still gated `canOperateRuns`. A **person's**
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
  `truncated`, no raw request), `GET /pending-actions/{id}` (request method and path only),
  `POST …/{id}/approve` and `…/decline` (`canApproveChanges`; four-eyes; the approver roster; an agent refused by
  the gate and by the route), `POST …/{id}/withdraw` (the asking machine only, `self-service` exemption).
- **Approve** — under the store lock: MAC verified, expiry applied, still pending; the record must still name an
  `ACTION` route and its path must match it (else 409, nothing dispatched); the request replays as the machine
  (`PendingChanges.authorNow`: re-admitted by the Space, filtered to what a machine agent keeps) with the record as
  `PendingActions.ATTR_APPROVED_ACTION`, which lets that one request through the hold. Outcome: `approved` (2xx),
  `stale` (409 — the run moved on) or `failed` (any other status, recorded as `appliedStatus`). Every transition is
  an `AUDIT` row `pending-action.proposed|approved|stale|failed|declined|withdrawn|expired` (category `operation`).
- **Operations MCP Tools** (lane C, `McpTools.OPERATIONS_TOOLS`): reads `list_runs` (`GET /runs`), `get_run_status`
  (`GET /runs/runs/{runId}`), `get_run_batches` (`GET /runs/{run}/batches`), `list_pending_actions`,
  `get_pending_action`; writes (`write: true`, so a console token is refused, D-AK3) `operate_run` (`action` one of
  `trigger`, `pause`, `resume`, `reprocess`, `replay-rejects`, `retry-now`, `cancel-retries`, `drain` → the matching
  `POST /runs/{run}/…`, optional `body` and one-line `reason` as `X-Change-Reason`), `trigger_job`, `replay_job_run` and
  `withdraw_action`. Each replays its route as the caller, so the gate above decides: at once for a person's agent, a
  Pending Action for a machine. `McpToolRoutesTest` expands `operate_run` over every action and pins each to a
  `canOperateRuns` manifest route.
- **The screen:** the Pending Changes screen has two tabs, *Pending Changes* and *Pending Actions*
  (`PendingActionsPanelComponent`): a list (Waiting / All), the action, the machine that asked, its reason and outcome;
  Approve and Decline for a holder of `canApproveChanges`.
- **Agent Skills and golden tasks:** `diagnose-failed-run` (read only) and `operate-runs` (act after diagnosis; ask
  before a drain or reprocess; report a `pa-…` id when held); the operations suite `agent-kit/golden/operations.json`
  (six tasks: diagnose without acting, resume, re-run, drain asks first, a machine's resume held, a machine never
  self-approves), graded by `tools/run-agent-golden.mjs`'s `agent-action`, `no-agent-action` and `pending-action`
  checks. Its live run is open, as for the developer suite's other clients.
- **Tests:** `ControlApiPendingActionsTest` (real HTTP: a person's agent pauses and resumes at once and its row
  names the agent client, a business user's agent is 403; a machine's pause is 202 and nothing ran, a second one
  409, the machine's approve 403, the list, another person's approval runs it, a second approve 409; withdraw by the
  machine only; a declined action never runs; a record naming a non-action route is not replayable),
  `AgentGateParityTest.theActionBucketIsExactlyTheNamedOperationalRoutes`.

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
