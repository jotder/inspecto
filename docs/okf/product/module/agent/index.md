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
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Assistant (AGT + EOI) — capability spec](plan-assistant.md) |
| spec | current | [eoiagent ADR log](spec-adr-log.md) · [Assist Agent](spec-assist-agent.md) · [eoiagent governance & safety](spec-governance.md) |
| architecture | current | [Inspecto ↔ eoiagent integration](architecture-integration.md) · [Agent Module (features/inspecto-agent/)](architecture-module.md) · [eoiagent overview](architecture-overview.md) · [eoiagent architecture](architecture.md) |
| features | current | [Assistant agent — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Model Settings](user-guide-model-settings.md) |
| admin-guide | current | [Assistant agent — admin guide](admin-guide.md) |
| api | current | [Assistant agent — API](api.md) |
| tests | current | [Assistant agent — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.agent` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P2 | feature | AGT-5 per-tool dry-run seam — BLOCKED-EXTERNAL | external |
| `AGT-SEGMENT-1` | P3 | chore | the assistant's commercial framing is an unvalidated product read. | — |
