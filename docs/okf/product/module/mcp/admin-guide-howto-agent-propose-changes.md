---
type: How-to
title: "Let an AI agent propose changes"
description: "Connect an AI agent (Claude, Gemini, OpenAI or a local model) to a Standard install so it can build Pipelines, Expectations, Alert Rules, Widgets and Dashboards as proposals that another person approves: register the agent client, list it on the server, give the agent the Agent Kit, and approve what it proposes."
item: module.mcp
audience: external
status: current
verified: 2026-11-24 code-reviewed
code: features/inspecto-mcp
---

# Let an AI agent propose changes

## Goal

An AI agent builds in a Space on a person's behalf — it reads the data, drafts a Pipeline, Expectation, Alert Rule,
Job, Widget or Dashboard, validates and dry-runs it, and **proposes** it. Nothing it proposes is live until **another
person** approves it. The agent never approves, never reveals a masked value, and never changes Apps, Roles or access.

## Before you start

- **Edition:** Standard, Advanced or Preview with sign-in through an OpenID Connect provider. Basic has no sign-in and
  no MCP endpoint.
- **Who:** an operator who can change the identity provider and the server's launch settings; the person the agent
  will act for (who needs the authoring rights for what the agent builds, such as `canAuthorWorkbench` or
  `canAuthorAlertRules`); and a second person who may approve changes (`canApproveChanges`).
- **The agent:** an MCP client that can sign a person in with authorization code and PKCE (Claude Code, Gemini CLI,
  Codex, or a local client), and the Agent Kit's skills from the bundle's `agent-kit/skills` folder.

## Steps

1. **At the identity provider**, register one public client per agent product — here `claude-code` — with
   authorization code and PKCE, in the same realm as the console's client. Its tokens must carry the install's
   audience and the same roles claim as the console's, and must name the client in `azp` or `client_id`. Do not enable
   a service account on it.
2. **On the server**, list the client as an agent client and restart:

   ```text
   AUTH_OIDC_AUDIENCE=inspecto-api
   INSPECTO_JAVA_OPTS=-Dauth.oidc.agentClients=claude-code -Dmcp.publicOrigin=https://inspecto.example.com
   ```

   The server refuses to start if the client is also the console's client or a machine client.
3. **In the agent**, add the MCP server `https://inspecto.example.com/api/v1/spaces/<space>/mcp` and the Agent Kit
   skills. For **Claude Code** and **Gemini CLI**, Inspecto's team builds a package for your install that carries both
   (the server address, the agent client and the skills); also register its redirect addresses on the agent client —
   `http://localhost:8765/callback` (Claude Code) and `http://localhost:8765/oauth/callback` (Gemini CLI):

   ```text
   claude plugin marketplace add <package>/claude
   claude plugin install inspecto@inspecto-agent-kit
   gemini extensions install <package>/gemini/inspecto
   ```

   Then sign in: `/mcp` in Claude Code, `/mcp auth inspecto` in Gemini CLI. The person signs in at the identity
   provider as themselves.
4. **Ask the agent for an outcome**, for example "add a check that amount is never negative on orders, and tell me how
   many rows break it today". It validates, dry-runs where it builds a Pipeline, and proposes. It reports a Pending
   Change id (`pc-…`).
5. **A second person approves** on the Pending Changes screen. Each agent proposal reads "Proposed by *person* via
   agent client *claude-code*", with the agent's one-line reason and the diff. Approve, or decline with a note the
   agent can read on its next turn.

## Check it worked

The Pending Changes list answers, so the approver can see the agent's proposals:

```check
GET /pending-changes
as admin
expect status 200
```

Then, after step 4, the list holds a Pending Change whose `agentClient` is `claude-code` and whose status is
`pending`; and the audit search for `claude-code` shows one `mcp.tool.called` row per tool the agent called, each
naming the person as the actor.

## What can go wrong

- The agent's tools answer 403 "needs sign-in through an agent client" — the token came from the console's client, or
  the client is not in `auth.oidc.agentClients`.
- 403 "an agent client may not call …" — the agent tried a step that is closed to agents: uploading a file,
  registering a Dataset, settings, access or Apps. A person does it in the console (Add data for a file).
- The proposal never becomes live — no second person has approved it. The person the agent acts for cannot approve
  it either (four eyes, always).
- 401 on every call — the token's audience is not the install's, or the agent's client sets no `azp`/`client_id`.
- A change applies at once instead of being held — the request did not come through the agent client (a person's own
  console token is governed by the Space's approval policy, not by this rule).

## Next

- [Call the Control API with a machine client](../oidc/admin-guide-howto-machine-client.md)
- [Change a Pipeline safely](../elt/user-guide-howto-change-a-pipeline-safely.md)
