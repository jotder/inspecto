# Inspecto Agent Kit

Know-how for an AI agent (Claude, Gemini, OpenAI, or a local model) working against a **running** Inspecto through its
MCP endpoint. The agent signs in as a person through an agent client; every change it makes is a **proposal** that
another person approves. Set-up: the Handbook how-to *Let an AI agent propose changes* (`/help/howto_agent_propose_changes.html`
on your install).

The Kit ships in every bundle that carries the MCP endpoint (Standard edition and up), in the bundle's `agent-kit/`
folder: this README, `skills/`, `package-agent-kit.mjs` and `VERSION`.

## Agent Skills — developer role (phase 1)

`skills/` holds Agent Skills in the open `SKILL.md` format, one folder per job:

`build-an-app` (start here for an outcome) · `author-pipeline` · `add-expectations` · `author-alert-rules` ·
`build-dashboard` · `safe-config-change` (every change goes through it) · `explain-inspecto` (read-only).

Read-only, for the person who approves and for a Space's health: `review-pending-change` (a brief on a Pending Change
or Pending Action; never decides it) · `audit-space-health` (drift, hand edits, storage, stale proposals).

Any client that reads `SKILL.md` folders can use `skills/` as it stands.

## Packages for Claude Code, Gemini CLI, Codex CLI and a local model

A package is built **for one install**: it names that install's MCP endpoint and agent client (a Gemini CLI extension
cannot take its server address from a variable). It needs Node.js 20 or later. In the bundle's `agent-kit/` folder:

```text
node package-agent-kit.mjs --mcp-url https://inspecto.example.com/api/v1/spaces/<space>/mcp \
     --client-id claude-code --gemini-client-id gemini-cli \
     --discovery-url https://id.example.com/realms/inspecto/.well-known/openid-configuration
```

The packages are written to `agent-kit/packages/` (`--out` to choose another folder; existing packages there are never
overwritten; from a source checkout the
script is `tools/package-agent-kit.mjs` and writes to `target/agent-kit/`).

| Vendor | What you get | Install |
|---|---|---|
| Claude Code | `claude/` — a plugin marketplace with the plugin `inspecto`: the skills and an HTTP MCP server with OAuth | `claude plugin marketplace add <out>/claude`, then `claude plugin install inspecto@inspecto-agent-kit`; sign in with `/mcp` |
| Gemini CLI | `gemini/inspecto/` — an extension: the skills, `GEMINI.md`, an `httpUrl` MCP server with OAuth | `gemini extensions install <out>/gemini/inspecto`; sign in with `/mcp auth inspecto` |
| OpenAI Codex CLI | `codex/` — a project folder: `.codex/config.toml` (`[mcp_servers.inspecto]` with OAuth), `.agents/skills/`, `AGENTS.md` | open the folder in Codex as a trusted project (or merge the config into `~/.codex/config.toml` and copy `.agents/skills/` to `~/.agents/skills/`); sign in with `codex mcp login inspecto` |
| Local model (OpenCode with Ollama or LM Studio) | `opencode/` — a project folder: `opencode.json` (a remote MCP server with OAuth), `.opencode/skills/`, `AGENTS.md` | open the folder in OpenCode with a local provider; sign in with `opencode mcp auth inspecto` |

Each client may have its own agent client at the identity provider: `--gemini-client-id`, `--codex-client-id`,
`--local-client-id` (each defaults to `--client-id`).

Register these redirect addresses on the agent clients at the identity provider (default port 8765,
`--callback-port` to change it): `http://localhost:8765/callback` for Claude Code, `http://localhost:8765/oauth/callback`
for Gemini CLI, `http://127.0.0.1:8765/callback` for OpenCode, and for Codex the exact address `codex mcp login`
prints (Codex may append a server id to it).

A local model needs a network route to the install and its identity provider only, not to any cloud service.

## Any other MCP client, by hand

The packages only save a manual step; the skills are the same for every client. For any other MCP-capable client:

1. Copy the folders in `skills/` to where the client reads `SKILL.md` skills.
2. Add one MCP server in the client's settings: Streamable HTTP to `https://<host>/api/v1/spaces/<space>/mcp`; OAuth
   authorization code with PKCE as a public client, with the agent client id the administrator listed in
   `auth.oidc.agentClients`; scope `openid`; the authorization and token endpoints from the identity provider's
   discovery document (a client that follows the server's 401 challenge finds them itself); register the client's
   redirect address on the agent client.

A client that cannot run an OAuth sign-in cannot write: a token copied from the console is refused by the write tools.
Details: the Handbook how-to *Let an AI agent propose changes*, step 3.

## Rules every skill follows

- Validate (and dry-run a Pipeline) before proposing; never propose a draft with errors.
- Propose; never approve, decline, reveal a masked value, promote, or change Apps, Roles or access.
- Steps closed to an agent (adding a file, registering a Dataset, connections) are handed to the person.
- Use the canonical words: Pipeline, Dataset, Expectation, Alert Rule, Incident, Measure, Collector, Widget, Dashboard.

## Golden tasks (source repository only)

The repository's `agent-kit/golden/developer.json` holds ten developer tasks (two of them review a Pending Change; one proposes a Decision Rule with its Notification Rule) on `golden/data/shipments.csv`, a Dataset
no Handbook page answers. Each is graded from the platform's own state — the Pending Changes the agent proposed, the
audit trail of its tool calls, and its final reply — never by a model, with safety checks (validate or dry-run before
propose, no attempt to approve or reveal, no applied write). Run an agent session per task, then grade it with
`node tools/run-agent-golden.mjs` (see the tool's header for the arguments). The golden tasks do not ship in a bundle.
