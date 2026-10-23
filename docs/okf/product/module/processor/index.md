---
type: ProductItem
title: Control plane and processor host
item: module.processor
description: Documentation hub for module.processor — every document about it, by slot.
tags: [product-item, module]
---

# Control plane and processor host (`module.processor`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.processor`](../../../../../product-map/module/processor.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Control API (API)](plan-control-api.md) |
| spec | current | [Component Registry](spec-component-registry.md) · [Decision Rules & the Condition-Tree Evaluator](spec-decision-rules.md) · [Jobs & Scheduling](spec-jobs.md) · [Metadata Bundle & Transportability](spec-metadata-bundle.md) · [Pipeline closure — what belongs to a pipeline](spec-pipeline-closure.md) · [Pipeline identity — rename & save-as-template](spec-pipeline-identity.md) · [Platform Services — the plugin envelope](spec-platform-services.md) · [Query catalog & execution](spec-queries.md) · [Tags — the cross-entity label graph](spec-tags.md) |
| architecture | current | [Job vs Pipeline Step — capability boundary](architecture-job-vs-step.md) · [Jobs and scheduler](architecture-jobs-scheduler.md) · [Multi-Space Hosting](architecture-multi-space.md) |
| features | current | [Jobs and scheduler — features](features-jobs-scheduler.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Diagnoses](user-guide-diagnoses.md) · [Jobs](user-guide-jobs.md) |
| admin-guide | current | [Control plane and processor host — admin guide](admin-guide.md) |
| api | current | [HTTP Control API](api-control-api.md) · [Jobs and scheduler — API](api-jobs-scheduler.md) · [Onboarding authoring seams (draft lifecycle, previews, register pair)](api-onboarding-authoring.md) · [HTTP routes — module.processor](api-routes.md) · [Versioned API (/api/v1)](api-v1.md) |
| tests | current | [Control plane and processor host — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.processor` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P3 | feature | Platform Services Stage 2 / 3 | — |
| `ONBOARDING-FIRST-RUN-1` | P2 | feature | console first-run onboarding: from a blank install to a queryable Dataset, in every edition — the console companion LA-ONBOARD-1 OB-1 exp… | operator |
| — | P3 | feature | Queries / BI | demand |
| — | P3 | feature | Bundle / Exchange — "load as draft" residuals | — |
| `LA-ONBOARD-RETIRE-GAPS-1` | P3 | defect | small gaps left by c392cda7a (LA-ONBOARD-ORPHAN-PIPELINE-1, LA-ONBOARD-DRIFT-1, LA-SAMPLE-GALLERY-DEMOAUTH-1; 2026-10-23): (1) the Parse… | operator |
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | what wave 1 of LA-ONBOARD-1 left (2026-10-22): (1) *Find paths (server)* stays unlocked on Basic (open explorer route, a Basic feature in… | — |
| `BI-QUERY-MASKING-1` | P2 | defect | /bi/query and /queries/{id}/run return a classified entity key raw to any viewer (decided 2026-10-12, operator: its own gated, reviewed r… | — |
