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
| spec | current | [The MCP endpoint (inspecto-mcp) — AK-1 and AK-2 phase 1 design and as-built](spec-mcp.md) |
| architecture | current | [MCP endpoint (inspecto-mcp)](architecture-mcp.md) |
| features | missing | |
| feature-db | current | [MCP endpoint — feature database](feature-db.md) |
| user-guide | missing | |
| admin-guide | current | [Let an AI agent propose changes](admin-guide-howto-agent-propose-changes.md) · [MCP endpoint — administrator guide](admin-guide-mcp.md) |
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
| `AGENT-PROPOSE-500-1` | P2 | defect | an agent's proposals fail with HTTP 500 a quarter of the time: | — |
| `PENDING-CHANGE-SECRET-READBACK-1` | P3 | defect | a Job's secret parameter can be read back through its Pending Change: | — |
| `DRYRUN-PREVIEW-UNMASKED-1` | P3 | defect | Pipeline dry-run and test-run previews are not masked: | — |
