---
type: ProductItem
title: ETL model and step registry
item: module.etl
description: Documentation hub for module.etl — every document about it, by slot.
tags: [product-item, module]
---

# ETL model and step registry (`module.etl`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.etl`](../../../../../product-map/module/etl.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Ingestion & parsing (ING)](plan-ingestion.md) · [Pipeline authoring (PIP) — capability spec](plan-pipeline-authoring.md) |
| spec | current | [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) |
| architecture | current | [Parsing and ingest](architecture-parsing-ingest.md) · [Pipelines and Steps](architecture-pipelines-steps.md) |
| features | current | [Parsing and ingest — features](features-parsing-ingest.md) · [Pipelines and Steps — features](features-pipelines-steps.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Enrichment](user-guide-enrichment.md) · [Parsing and ingest — screens](user-guide-parsing-ingest.md) · [Pipeline Editor](user-guide-pipeline-editor.md) · [Pipelines (Authoring) — MOVED](user-guide-pipelines.md) · [Schema, Mapping & Transformation Authoring](user-guide-schema-mapping.md) |
| admin-guide | current | [ETL model and step registry — admin guide](admin-guide.md) |
| api | current | [Parsing and ingest — API](api-parsing-ingest.md) · [Pipelines and Steps — API](api-pipelines-steps.md) |
| tests | current | [ETL model and step registry — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.etl` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AUTHORING-REDESIGN-1` | P2 | feature | the authoring redesign's still-open letters. | — |
| — | P3 | feature | P4 Test mapping on a generic parser node | — |
| — | P3 | feature | Step Processor catalog | — |
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `DATA-TIERS-1` | P2 | feature | the data layer by edition: Basic gets a one-off "Add data" import (CSV / Parquet upload, Postgres snapshot, no-code shaping) and no sched… | operator |
| `ANALYTICS-STEPS-TS-1` | P3 | feature | Steps for the Analytics Kernel time-series and cohort kernels: transform.timeseries.changepoint (ChangePoint, input a residual column + σ… | — |
