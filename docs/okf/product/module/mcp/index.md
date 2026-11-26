---
type: ProductItem
title: MCP endpoint
item: module.mcp
description: Documentation hub for module.mcp — every document about it, by slot.
tags: [product-item, module]
---

# MCP endpoint (`module.mcp`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.mcp`](../../../../../product-map/module/mcp.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | missing | |
| spec | current | [Agent case actions and record edits (AK-2e, W11) — design and as-built](spec-agent-case-actions.md) · [Agent posture (MCP-AGENT-POSTURE-1) — design and as-built](spec-agent-posture.md) · [Agent authoring previews (AGENT-KIT-1 W6 group A) — design and as-built](spec-agent-previews.md) · [Agent Space tests (AGENT-KIT-1 W6 group C) — design and as-built](spec-agent-space-tests.md) · [The MCP endpoint (inspecto-mcp) — AK-1 and AK-2 phase 1 design and as-built](spec-mcp.md) |
| architecture | current | [MCP endpoint (inspecto-mcp)](architecture-mcp.md) |
| features | missing | |
| feature-db | current | [MCP endpoint — feature database](feature-db.md) |
| user-guide | missing | |
| admin-guide | current | [Let an AI agent analyse a Case in Link Analysis](admin-guide-howto-agent-link-analysis.md) · [Let an AI agent operate runs](admin-guide-howto-agent-operate-runs.md) · [Let an AI agent propose changes](admin-guide-howto-agent-propose-changes.md) · [Let an AI agent work Incidents and Cases](admin-guide-howto-agent-work-incidents.md) · [MCP endpoint — administrator guide](admin-guide-mcp.md) |
| api | current | [HTTP routes — module.mcp](api-routes.md) |
| tests | current | [Agent Kit golden runs — the compatibility matrix](tests-agent-golden.md) · [MCP endpoint — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.mcp` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AGENT-KIT-1` | P2 | feature | The Agent Kit: AI agents build, ship and manage Apps on a running Inspecto safely, through an optional MCP module. | — |
| `AGENT-GATEWAY-UNMARKED-1` | P3 | defect | behind an API gateway (-Dauth.oidc.gateway.*) an agent client's token is never marked: | — |
| `AGENT-LOOKUP-PREVIEW-1` | P3 | feature | let an agent test a lookup (POST /lookups/test) once it is safe: | — |
| `PENDING-CHANGE-SECRET-READBACK-1` | P3 | defect | a Job's secret parameter can be read back through its Pending Change: | — |
| `DRYRUN-PREVIEW-UNMASKED-1` | P3 | defect | Pipeline dry-run and test-run previews are not masked: | — |
| `DRYRUN-SAVE-NODE-MISMATCH-1` | P3 | defect | dry_run_pipeline (POST /pipelines/authored/{id}/dry-run) seeds and runs a graph whose source.dataset node propose_change (PUT /pipelines/… | — |
| `AGENT-NOTICE-PLANTED-1` | P3 | feature | No Agent Skill tells the agent to tell the person when text it reads in data (a CSV cell, a comment, a description, a proposal's reason)… | — |
| `ALERT-RULE-ROW-FILTER-1` | P3 | feature | A Measure Alert Rule (dataset, measure, comparator, threshold) has no row filter, so "alert when more than 5 shipments are undelivered" c… | — |
| `AGENT-ANSWERS-WITHOUT-SERVER-1` | P3 | feature | Asked which sample rows a planned schema rejects (golden task dev-13), the agent loaded no skill, called no tool and worked the answer ou… | — |
