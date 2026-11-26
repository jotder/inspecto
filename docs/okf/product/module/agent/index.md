---
type: ProductItem
title: Assistant agent
item: module.agent
description: Documentation hub for module.agent — every document about it, by slot.
tags: [product-item, module]
---

# Assistant agent (`module.agent`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.agent`](../../../../../product-map/module/agent.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Assistant (AGT + EOI) — capability spec](plan-assistant.md) · [module.agent - board row detail](plan-board-detail.md) |
| spec | current | [eoiagent ADR log](spec-adr-log.md) · [Assist Agent](spec-assist-agent.md) · [eoiagent governance & safety](spec-governance.md) |
| architecture | current | [Assistant agent and eoiagent](architecture-agent.md) |
| features | current | [Assistant agent — features](features-agent.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Model Settings](user-guide-model-settings.md) |
| admin-guide | current | [Assistant agent — admin guide](admin-guide-agent.md) |
| api | current | [Assistant agent — API](api-agent.md) |
| tests | current | [Assistant agent — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.agent` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AGENT-KIT-1` | P2 | feature | The Agent Kit: AI agents build, ship and manage Apps on a running Inspecto safely, through an optional MCP module. | — |
| `ASSISTANT-SCREEN-HELP-1` | P3 | feature | The Assistant explains the screen or Widget a user is on | operator |
| — | P2 | feature | AGT-5 per-tool dry-run seam — BLOCKED-EXTERNAL | — |
| `AGT-SEGMENT-1` | P3 | chore | the assistant's commercial framing is an unvalidated product read. | — |
| `AGENT-ANSWERS-WITHOUT-SERVER-1` | P3 | feature | Asked which sample rows a planned schema rejects (golden task dev-13), the agent loaded no skill, called no tool and worked the answer ou… | — |
| `DOCS-SKILLS-ALERTS-AGENTIC-1` | P3 | feature | hub-routed agent skills exist for docs lookup, pipeline authoring, incident investigation and case analysis (.claude/skills/) | demand |
