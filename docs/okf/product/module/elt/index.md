---
type: ProductItem
title: ELT — ingestion, Pipelines and step registry
item: module.elt
description: Documentation hub for module.elt — every document about it, by slot.
tags: [product-item, module]
---

# ELT — ingestion, Pipelines and step registry (`module.elt`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.elt`](../../../../../product-map/module/elt.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Parser field tiers — interview #2 observation-session kit (D13)](plan-parser-field-tiers-interview.md) · [Ingestion, parsing and Pipeline authoring (ING, PIP) — plan](plan.md) |
| spec | current | [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) |
| architecture | current | [Pipelines, Steps and ingest](architecture.md) |
| features | current | [Pipelines, parsing and ingest — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Pipelines — user guide](user-guide.md) |
| admin-guide | current | [ETL model and step registry — admin guide](admin-guide.md) |
| api | current | [Pipelines, parsing and ingest — API](api.md) |
| tests | current | [ELT — ingestion, Pipelines and step registry — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.elt` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AUTHORING-REDESIGN-1` | P3 | feature | the authoring redesign's still-open letters. | demand |
| — | P3 | feature | Step Processor catalog | — |
| `PARSING-DERIVE-EDITOR-1` | P3 | feature | slice 4 of Parsing D9: an optional profile editor for ASN.1 derive / children. | operator |
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `DUCKLAKE-REAL-DRIVER-FAILURE-PROOF-1` | P3 | chore | no test proves that a real DuckLake driver's registration failure is non-fatal (filed 2026-11-16; untracked since SPEC-NOPROOF-1 closed o… | external |
