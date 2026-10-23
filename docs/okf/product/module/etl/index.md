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
| plan | current | [Ingestion & parsing (ING)](plan-ingestion.md) · [Parser field tiers — interview #2 observation-session kit (D13)](plan-parser-field-tiers-interview.md) · [Pipeline authoring (PIP) — capability spec](plan-pipeline-authoring.md) |
| spec | current | [Console first-time user use cases (blank slate to a queryable Dataset)](spec-first-time-user-use-cases.md) · [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) |
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
| `PARSING-DERIVE-EDITOR-1` | P3 | feature | slice 4 of Parsing D9: an optional profile editor for ASN.1 derive / children. | operator |
| `ONBOARDING-FIRST-RUN-1` | P2 | feature | console first-run onboarding: from a blank install to a queryable Dataset, in every edition — the console companion LA-ONBOARD-1 OB-1 exp… | operator |
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `DATA-TIERS-1` | P2 | feature | the data layer by edition, under one product-wide edition switch: Basic gets a files-only "Add data" import (CSV / TSV, Parquet, JSON / N… | operator |
| `LA-ONBOARD-RETIRE-GAPS-1` | P3 | defect | small gaps left by c392cda7a (LA-ONBOARD-ORPHAN-PIPELINE-1, LA-ONBOARD-DRIFT-1, LA-SAMPLE-GALLERY-DEMOAUTH-1; 2026-10-23): (1) the Parse… | operator |
| `ANALYTICS-STEPS-TS-1` | P3 | feature | Steps for the Analytics Kernel time-series and cohort kernels: transform.timeseries.changepoint (ChangePoint, input a residual column + σ… | — |
