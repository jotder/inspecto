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
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.processor - board row detail](plan-board-detail.md) · [Control API (API)](plan-control-api.md) |
| spec | current | [Component Registry](spec-component-registry.md) · [Decision Rules & the Condition-Tree Evaluator](spec-decision-rules.md) · [Jobs & Scheduling](spec-jobs.md) · [Metadata Bundle & Transportability](spec-metadata-bundle.md) · [Pipeline closure — what belongs to a pipeline](spec-pipeline-closure.md) · [Pipeline identity — rename & save-as-template](spec-pipeline-identity.md) · [Platform Services — the plugin envelope](spec-platform-services.md) · [Query catalog & execution](spec-queries.md) · [Tags — the cross-entity label graph](spec-tags.md) |
| architecture | current | [Jobs, scheduler and multi-Space](architecture-processor.md) |
| features | current | [Jobs and scheduler — features](features-jobs-scheduler.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Diagnoses](user-guide-diagnoses.md) · [Jobs](user-guide-jobs.md) |
| admin-guide | current | [Control plane and processor host — admin guide](admin-guide-processor.md) |
| api | current | [HTTP Control API](api-control-api.md) · [Jobs and scheduler — API](api-jobs-scheduler.md) · [Onboarding authoring seams (draft lifecycle, previews, register pair)](api-onboarding-authoring.md) · [Choose a table — the Postgres snapshot seam](api-onboarding-postgres-snapshot.md) · [HTTP routes — module.processor](api-routes.md) · [Versioned API (/api/v1)](api-v1.md) |
| tests | current | [Control plane and processor host — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.processor` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P3 | feature | Platform Services Stage 2 / 3 | demand |
| `BASIC-ELT-PROFILE-1` | P2 | feature | Make Basic onboarding a locked profile of ELT, retiring the parallel Basic backend and LA UI layers that duplicate ELT parts. | — |
| `BASIC-WATCH-ARRIVALS-1` | P3 | defect | A watched inbox's scheduled run leaves no arrivals sidecar line and no watchError for a quarantined file (filed 2026-11-19, BASIC-ELT-PRO… | — |
| — | P3 | feature | Bundle / Exchange — "load as draft" residuals | — |
| `ONBOARDING-REMOTE-SYSTEMS-1` | P3 | feature | Connect a system on Standard: Add data and the guided editor's Collect stage offer remote kinds (SFTP, object stores, other databases) wi… | — |
| `DEPLOY-EXPOSURE-KIT-1` | P3 | chore | Prove the deploy kit (proxy fence for /metrics and /health/details, TLS, upgrade, off-site backup, signature verify) on a real host. | — |
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | Two items wave 1 of LA-ONBOARD-1 left: how far no-code row filtering goes in Basic, and a real drag-and-drop of a file onto Add data. | operator |
