---
type: Guide
title: MCP endpoint — administrator guide
description: How an administrator lets AI agents (Claude Code, Gemini CLI, Codex) reach a Standard install through the MCP endpoint as a delegated person — registering an agent client in the identity provider, the -Dauth.oidc.agentClients allowlist, the protected-resource metadata and its reverse-proxy alias, and what an agent token may and may not do.
tags: [agent-kit, mcp, agents, oidc, security, admin]
timestamp: 2026-11-20T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: features/inspecto-mcp
---

# MCP endpoint — administrator guide

The MCP endpoint (`POST /api/v1/mcp`) ships from the Standard edition up. An AI agent reaches it signed in **as a
person**, through an **agent client** the administrator registered and listed. Design and as-built:
[spec-mcp.md](spec-mcp.md).

## 1. Register an agent client in the identity provider

Register one public client per agent product (for example `claude-code`), authorization code with PKCE, in the
same realm or tenant as the console's own client. Its access tokens must carry this install's audience
(`-Dauth.oidc.audience`) and the same roles claim as the console's. Do **not** reuse the console's client
(`-Dauth.oidc.clientId`) and do not list it as a machine client.

**Allow `offline_access`.** Claude Code signs in with `scope=openid offline_access` (it keeps a refresh token), whatever
scopes the plugin names. Give the agent client `offline_access` as an allowed (optional) scope, and make sure the
people who will use the agent hold the `offline_access` role — Keycloak grants it through the realm's default roles. A
client that does not allow the scope answers `invalid_scope` at sign-in; a person without the role gets `Offline tokens
not allowed for the user or client` at the token exchange, after the browser has shown "Authentication Successful"
(both found in the Claude Code plugin's live sign-in, 2026-11-24).

Two rules the server cannot check for you:

- **The token must name its client in `azp` or `client_id`.** That is how a token is recognised as an agent's. An
  identity provider that names the client only in another claim (Okta's `cid`, Azure AD v1's `appid`) needs a claim
  mapper that adds `azp`; without one an agent's token is read as the person's own, unmarked.
- **Do not enable a service account (client credentials) on an agent client.** A client-credentials token is told
  apart from a person's by its `gty` claim; Keycloak does not set `gty`, so a service-account token of a listed agent
  client would be read as an agent acting for a person. Unattended agents are machine clients (`auth.oidc.clients`),
  a separate client.

## 2. The `-D` keys

| Key | Default | What it does |
|---|---|---|
| `auth.oidc.agentClients` | unset (no agent clients) | Comma list of client ids whose **person** tokens are an agent acting for that person. Needs `auth.oidc.audience`. Boot refuses a client that is also in `auth.oidc.clients` (machines) or is `auth.oidc.clientId` (the console). |
| `auth.oidc.agentMachineClients` | unset (no machine agents) | Comma list of **machine** clients (client credentials, each also in `auth.oidc.clients`) that are unattended agents. Such a client keeps, where its roles grant them, `canAuthorWorkbench`, `canAuthorAlertRules`, `canWorkIncidents` and `canOperateRuns` beside a machine's usual `canOpenInboundInvestigation`; every config write it makes is held for a person, and it never approves. The Space must still name it in `roles.toon` `identity.machineClients`. |
| `mcp.publicOrigin` | the request's scheme and `Host` | The origin agents reach the install at, for example `https://inspecto.example.com`. Set it behind a TLS-terminating reverse proxy, or the metadata names `http://…`. |
| `auth.oidc.scopes` | `openid` | The scopes the metadata lists as `scopes_supported` (space-separated; the console's sign-in uses the same key). |

## 3. Discovery and the reverse proxy

An MCP client that calls `/api/v1/mcp` without a token gets `401` with
`WWW-Authenticate: Bearer resource_metadata="<origin>/api/v1/.well-known/oauth-protected-resource"`. That document
(public, no sign-in) names the issuer as the authorization server, so the client signs the person in at the
identity provider itself. Inspecto serves it only under `/api/v1` (and `/api/v1/spaces/{id}`); clients that look
at the RFC 9728 root path instead need a reverse-proxy alias:

```
location = /.well-known/oauth-protected-resource { proxy_pass http://inspecto:8080/api/v1/.well-known/oauth-protected-resource; }
```

## 4. What an agent token may do

- **Read** what the person may read — less revealing a masked value: an agent token never holds
  `canRevealLinkEntities`, `canApproveChanges` or `canApproveLinkExpansions`, whatever the person's roles grant.
- **Propose** config changes: every write an agent makes to a route that supports approval becomes a **Pending
  Change** — with or without an approval policy — that **another person** must approve (four eyes always; the
  person the agent acts for cannot approve it either). It may withdraw its own proposal.
- **Validate, dry-run, export a bundle, preview a promotion, raise an Action Request, run a Pipeline** (within
  the person's own rights).
- **Operate runs** (trigger, pause, resume, reprocess, replay rejects, retry, drain; trigger a Job, replay a Job
  Run) **at once**, within the person's own rights — the same as the person pressing the button, with the agent
  client on the audit row.
- **Nothing else:** every other write is refused (`403`, "an agent client may not call …"), and an agent never
  approves, declines, reveals, promotes, or changes Apps or access.

A **machine agent** (`auth.oidc.agentMachineClients`) follows the same rules with its client as the actor
(`client:<id>`): it proposes and is held, and it never runs a Pipeline, approves or administers. Its run actions
are **held too**, as **Pending Actions**: a change approver (`canApproveChanges`, not the machine) approves or
declines each one under `/pending-actions`, and an approved one runs as the
machine. Give it a role in
the Space that grants only what it should author or operate; it never keeps more than the four capabilities above.

Every audit row of an agent's request names the person as the actor and the agent client as `agent_client`;
search the audit log for it to see everything an agent did. The Pending Changes screen shows "via agent client"
on each agent proposal.
