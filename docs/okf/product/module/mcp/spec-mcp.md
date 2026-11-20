---
type: Spec
title: The MCP endpoint (inspecto-mcp) — AK-1 design and as-built
description: How the optional inspecto-mcp module serves the Model Context Protocol on the Control API — transport and pinned revision, the read MCP Tools and resources, why every call goes through the existing auth, Space and route gates (an in-process replay, never a side door), the audit row naming the person and the agent client, the identity modes and what AK-1 leaves for AK-2.
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
AK-1 is **read-only**: no MCP Tool changes anything. Writes (validate → dry-run → Draft), `propose_action`
and the machine agent capability set are AK-2.

**On this page**

- [Placement](#placement) — the module, the editions, the per-Space switch.
- [Transport](#transport) — the pinned revision, what the endpoint accepts and refuses.
- [No side door](#no-side-door) — how every call reuses the Control API's own gates.
- [MCP Tools and resources](#mcp-tools-and-resources) — the AK-1 list and the route each one replays.
- [Audit](#audit) — the row each call leaves.
- [Identity](#identity) — the delegated-person and machine modes, and what AK-1 ships of each.
- [Deferred to AK-2](#deferred-to-ak-2)

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

`POST /mcp` itself is listed in `CapabilityManifest.EXEMPTIONS` as **`read-shaped`** in AK-1: it persists
nothing, and every route it reaches carries its own gate. AK-2 revisits the category when write tools land
(each write tool will replay a gated write route, so the endpoint still needs no capability of its own).

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

**Not exposed, on purpose:** connections, secrets, settings, access and role administration, exports and
bundles, raw files, the `ops:*` SQL groups. No tool returns a secret.

## Audit

Every `tools/call` and `resources/read` leaves **one `AUDIT` row**, action `mcp.tool.called` /
`mcp.resource.read`, category `agent`, in the bound Space's event store:

- `actor` = the person (the Subject id; a machine's client id), `actorType` as the host derives it;
- `mcpTool` or `mcpResource`, `mcpProtocolVersion`, `mcpReplay` (`METHOD path`), `httpStatus` (the
  replay's), the IP, User-Agent and correlation id;
- **the agent client:** `mcpClient` = `clientInfo.name/version` from the request `_meta` (legacy: none —
  `initialize` is not remembered, so the User-Agent is all there is). It is **self-declared**, which the
  attribute name `mcpClientDeclared: true` says; a verified client id arrives with the delegated-person
  allowlist (below) as `agentClient`.

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
  1. `-Dauth.oidc.agentClients=<client ids>` (planned, not built in AK-1) — an allowlist of agent clients that **stay persons**. A token
     whose `azp` / `client_id` is listed is admitted like the SPA client's (signature, issuer, audience,
     expiry), is never classed a machine (`MachineTrust.machineClient` skips it), holds exactly the
     person's Roles, and carries the client id as Subject attribute `agentClient`, which the audit row
     records as a verified `agentClient`. Boot refuses a client listed in both `auth.oidc.clients` and
     `auth.oidc.agentClients`.
  2. The MCP authorization model: Protected Resource Metadata (RFC 9728) at
     `/.well-known/oauth-protected-resource` naming the customer's IdP, and a `401` from `/mcp` carrying
     `WWW-Authenticate: Bearer resource_metadata="…"`, so an MCP client runs authorization code with PKCE
     against the IdP itself. The token's audience must be the install (`auth.oidc.audience`).
  3. Client registration: the agent client is registered in the customer's IdP by its administrator (the
     specification's Client ID Metadata Documents and the deprecated dynamic registration are the IdP's
     business, not Inspecto's).
- **Machine — unattended agents.** Client credentials, admitted per Space exactly as today
  (`MachineTrust`, `Roles.admitMachine`, the Space's `machineClients`). Reads need no capability
  (operator 2026-09-15), so an admitted machine can use the AK-1 tools today. The **agent capability set**
  (read plus Draft authoring, never approve, never reveal) widens `Roles.MACHINE_CAPABILITIES` and lands
  with AK-2's write tools.
- **What AK-1 ships:** the endpoint accepts any Subject the host's `Authenticator` resolves — a person's own
  token (today's SPA client) or an admitted machine. The delegated allowlist and the protected-resource
  metadata are **not built in AK-1**; they are the first item of AK-2, before any write tool, because a
  verified agent client must be on the row before an agent can author anything.
- **Basic:** no module (D-UE4).

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

## Deferred to AK-2

- The delegated-person allowlist (`auth.oidc.agentClients`, `agentClient` attribute) and RFC 9728 metadata.
- Write tools (`validate_config`, `dry_run_pipeline`, `propose_change`, …), `propose_action`, the machine agent
  capability set, and the endpoint's manifest category for writes. Design (approved for phase 1 on 2026-11-20):
  [`2026-11-20-agent-kit-ak2-plan.md`](../../../../superpower/2026-11-20-agent-kit-ak2-plan.md).
- Prompts, `subscriptions/listen`, progress notifications, the tasks extension, multi round-trip requests.
- `export_bundle`, `preview_promotion`, `validate_app` (they belong with the Ship tools in AK-2).
