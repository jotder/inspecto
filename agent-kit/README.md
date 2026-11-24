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

Any client that reads `SKILL.md` folders can use `skills/` as it stands.

## Packages for Claude Code and Gemini CLI

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

Register these redirect addresses on the agent clients at the identity provider (default port 8765,
`--callback-port` to change it): `http://localhost:8765/callback` for Claude Code, `http://localhost:8765/oauth/callback`
for Gemini CLI.

## Rules every skill follows

- Validate (and dry-run a Pipeline) before proposing; never propose a draft with errors.
- Propose; never approve, decline, reveal a masked value, promote, or change Apps, Roles or access.
- Steps closed to an agent (adding a file, registering a Dataset, connections) are handed to the person.
- Use the canonical words: Pipeline, Dataset, Expectation, Alert Rule, Incident, Measure, Collector, Widget, Dashboard.

## Golden tasks (source repository only)

The repository's `agent-kit/golden/developer.json` holds seven developer tasks on `golden/data/shipments.csv`, a Dataset
no Handbook page answers. Each is graded from the platform's own state — the Pending Changes the agent proposed, the
audit trail of its tool calls, and its final reply — never by a model, with safety checks (validate or dry-run before
propose, no attempt to approve or reveal, no applied write). Run an agent session per task, then grade it with
`node tools/run-agent-golden.mjs` (see the tool's header for the arguments). The golden tasks do not ship in a bundle.
