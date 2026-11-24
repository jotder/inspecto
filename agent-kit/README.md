# Inspecto Agent Kit

Know-how for an AI agent (Claude, Gemini, OpenAI, or a local model) working against a **running** Inspecto through its
MCP endpoint. The agent signs in as a person through an agent client; every change it makes is a **proposal** that
another person approves. Set-up: the Handbook how-to *Let an AI agent propose changes* (`/help/howto_agent_propose_changes.html`
on your install).

## Contents

| Folder | What |
|---|---|
| `skills/` | Agent Skills in the open `SKILL.md` format, one folder per job. Phase 1 covers the **developer** role. |
| `golden/` | The golden task suite: tasks, their data, and the ground truth a grader checks against. |

### Agent Skills — developer role (phase 1)

`build-an-app` (start here for an outcome) · `author-pipeline` · `add-expectations` · `author-alert-rules` ·
`build-dashboard` · `safe-config-change` (every change goes through it) · `explain-inspecto` (read-only).

Point your agent at the `skills/` folder: Claude Code reads `SKILL.md` folders from a plugin or a project's skills
directory; Gemini CLI and Codex read the same format. Per-vendor packages are not shipped yet.

## Rules every skill follows

- Validate (and dry-run a Pipeline) before proposing; never propose a draft with errors.
- Propose; never approve, decline, reveal a masked value, promote, or change Apps, Roles or access.
- Steps closed to an agent (adding a file, registering a Dataset, connections) are handed to the person.
- Use the canonical words: Pipeline, Dataset, Expectation, Alert Rule, Incident, Measure, Collector, Widget, Dashboard.

## Golden tasks

`golden/developer.json` holds seven developer tasks on `golden/data/shipments.csv`, a Dataset no Handbook page answers.
Each is graded from the platform's own state — the Pending Changes the agent proposed, the audit trail of its tool
calls, and its final reply — never by a model, with safety checks (validate or dry-run before propose, no attempt to
approve or reveal, no applied write). Run an agent session per task, then grade it with
`node tools/run-agent-golden.mjs` from a source checkout (see the tool's header for the arguments).
