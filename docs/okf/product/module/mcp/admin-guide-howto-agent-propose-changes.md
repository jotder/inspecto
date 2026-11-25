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
   audience and the same roles claim as the console's, and must name the client in `azp` or `client_id`. Allow the
   `offline_access` scope on it (Claude Code always asks for it) and check the people who will use the agent hold the
   `offline_access` role. Do not enable a service account on it.
2. **On the server**, list the client as an agent client and restart:

   ```text
   AUTH_OIDC_AUDIENCE=inspecto-api
   INSPECTO_JAVA_OPTS=-Dauth.oidc.agentClients=claude-code -Dmcp.publicOrigin=https://inspecto.example.com
   ```

   The server refuses to start if the client is also the console's client or a machine client.
3. **In the agent**, add the MCP server `https://inspecto.example.com/api/v1/spaces/<space>/mcp` and the Agent Kit
   skills. The bundle's `agent-kit/` folder holds the skills and a packager that builds, for your install, a **Claude
   Code** plugin, a **Gemini CLI** extension, an **OpenAI Codex CLI** project folder and a **local-model** project folder
   for OpenCode (with Ollama or LM Studio), each carrying both (the server address, the agent client and the skills) —
   with Node.js, from the bundle folder:

   ```text
   node agent-kit/package-agent-kit.mjs --mcp-url https://inspecto.example.com/api/v1/spaces/<space>/mcp --client-id claude-code --discovery-url <the server's AUTH_OIDC_DISCOVERY_URL>
   ```

   Register its redirect addresses on the agent client — `http://localhost:8765/callback` (Claude Code),
   `http://localhost:8765/oauth/callback` (Gemini CLI), `http://127.0.0.1:8765/callback` (OpenCode) and, for Codex, the
   address `codex mcp login` prints — and install the packages from `agent-kit/packages/`:

   ```text
   claude plugin marketplace add agent-kit/packages/claude
   claude plugin install inspecto@inspecto-agent-kit
   gemini extensions install agent-kit/packages/gemini/inspecto
   ```

   For Codex, open `agent-kit/packages/codex` as a trusted project; for a local model, open
   `agent-kit/packages/opencode` in OpenCode with a local provider. Then sign in: `/mcp` in Claude Code,
   `/mcp auth inspecto` in Gemini CLI, `codex mcp login inspecto`, `opencode mcp auth inspecto`. The person signs in at
   the identity provider as themselves.

   **Any other MCP client, by hand** — a client the packages do not cover, or any of the four without its package. The skills are the same `SKILL.md` folders for every client; only the client's own settings
   differ:

   - **Skills:** copy the folders in `agent-kit/skills/` to where the client reads skills (its skills directory, or the
     project it works in). A client that reads no `SKILL.md` can be given `agent-kit/skills/build-an-app/SKILL.md` and
     `agent-kit/skills/safe-config-change/SKILL.md` as instructions.
   - **The MCP server:** add one server in the client's MCP settings, with these values in the client's own format:

     | Setting | Value |
     |---|---|
     | Transport | Streamable HTTP (not SSE, not stdio) |
     | Address | `https://inspecto.example.com/api/v1/spaces/<space>/mcp` |
     | Sign-in | OAuth, authorization code with PKCE, as a public client (no secret) |
     | Client id | the agent client you registered in step 1, listed in `auth.oidc.agentClients` |
     | Authorization and token endpoints | from the discovery document (`AUTH_OIDC_DISCOVERY_URL`); a client that follows the server's 401 challenge finds them itself |
     | Scopes | `openid` (or what `auth.oidc.scopes` lists) |
     | Redirect address | whatever the client uses — register it on the agent client |

   - A client that cannot run an OAuth sign-in cannot be used: a token copied from the console belongs to the
     console's client, and the write tools refuse it.
4. **Ask the agent for an outcome**, for example "add a check that amount is never negative on orders, and tell me how
   many rows break it today". It validates, previews (parses a sample, suggests and checks a schema, checks a
   mapping or a lookup), dry-runs where it builds a Pipeline, and proposes once the draft is clean. It reports a
   Pending Change id (`pc-…`). The previews write nothing; an agent's preview body is at most 256 KiB and its draft
   may not name a file — it puts the schema, grammar or mapping inline.
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

- The sign-in page answers `invalid_scope` ("Invalid scopes: openid offline_access"), or the browser says
  "Authentication Successful" but the agent asks to sign in again — the agent client does not allow `offline_access`,
  or the person lacks the `offline_access` role (Keycloak logs `Offline tokens not allowed for the user or client`).
- The agent's tools answer 403 "needs sign-in through an agent client" — the token came from the console's client, or
  the client is not in `auth.oidc.agentClients`.
- 403 "an agent client may not call …" — the agent tried a step that is closed to agents: uploading a file,
  registering a Dataset, settings, access or Apps. A person does it in the console (Add data for a file).
- A preview answers 413 — the agent sent more than 256 KiB of sample; or 422 "names a file" — its draft pointed at a
  schema, grammar or mapping file instead of carrying it inline. Both are the agent's to fix; tell it so.
- The proposal never becomes live — no second person has approved it. The person the agent acts for cannot approve
  it either (four eyes, always).
- 401 on every call — the token's audience is not the install's, or the agent's client sets no `azp`/`client_id`.
- A change applies at once instead of being held — the request did not come through the agent client (a person's own
  console token is governed by the Space's approval policy, not by this rule).

## Next

- [Call the Control API with a machine client](../oidc/admin-guide-howto-machine-client.md)
- [Change a Pipeline safely](../elt/user-guide-howto-change-a-pipeline-safely.md)
