---
type: Concept
title: MCP endpoint (inspecto-mcp)
description: Entry page of the mcp hub — the optional module that lets AI agents (Claude, Gemini, OpenAI, local models) read a running Inspecto through the Model Context Protocol, as the signed-in person, through the Control API's own gates.
tags: [agent-kit, mcp, agents, module]
timestamp: 2026-11-20T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: features/inspecto-mcp
---

# MCP endpoint (inspecto-mcp)

The tools layer of the **Agent Kit** (`AGENT-KIT-1`): an optional module, Standard edition and up, that serves
the Model Context Protocol at `POST /api/v1/mcp` (or `/api/v1/spaces/{id}/mcp`). An MCP-capable agent signed in
as a person lists the MCP Tools and resources and calls them; each call is an in-process replay of an existing
Control API route as that person, so the agent sees exactly what the person may see, masked the same way, and
every call leaves an audit row naming the person and the agent client.

```
agent ──POST /mcp──▶ host: authenticate · rate limit · access policy · module switch
                       │
                       ▼
                 McpRoutes: Subject? Origin? one JSON-RPC object? era + header checks
                       │  tools/call  →  McpTools (fixed tool → route table, id-shaped arguments)
                       ▼
                 ApiContext.replay(GET /components/dataset/…)  ── the route's own gates and masking
                       │
                       ▼
                 result (isError on a refusal)  +  AUDIT row mcp.tool.called
```

- **Phase AK-1 (2026-11-20): read-only.** Nineteen read MCP Tools, three resources, the audit row, the pinned
  transport. Design and as-built: [spec-mcp.md](spec-mcp.md).
- **Next (AK-2):** the delegated-person allowlist and protected-resource metadata, then write tools through
  validate → dry-run → Pending Change, `propose_action` through Action Requests, the machine agent capability set.
  Design (approved for phase 1 on 2026-11-20, D-AK1…D-AK4 signed): [`2026-11-20-agent-kit-ak2-plan.md`](../../../../superpower/2026-11-20-agent-kit-ak2-plan.md).
- **Plan:** [`2026-11-19-user-enablement-and-agent-kit-plan.md`](../../../../superpower/2026-11-19-user-enablement-and-agent-kit-plan.md) §7.
