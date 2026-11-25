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
| plan | current | [module.processor - board row detail](plan-board-detail.md) · [Control API (API) — Decisions, not built, refused and verification](plan-control-api-decisions-verification.md) · [Control API (API) — Purpose, scope and requirements of record](plan-control-api-scope.md) · [Control API (API) — Specification](plan-control-api-specification.md) · [Control API (API)](plan-control-api.md) |
| spec | current | [Component Registry](spec-component-registry.md) · [Decision Rules & the Condition-Tree Evaluator](spec-decision-rules.md) · [Jobs & Scheduling — The Job Framework](spec-jobs-framework.md) · [Jobs & Scheduling — Maintenance jobs and the push and process jobs](spec-jobs-maintenance.md) · [Jobs & Scheduling — Open rows this concept owns](spec-jobs-open-rows.md) · [Jobs & Scheduling — The parameter contract, failure handling and single-seam properties](spec-jobs-parameters-failure.md) · [Jobs & Scheduling](spec-jobs.md) · [Metadata Bundle & Transportability](spec-metadata-bundle.md) · [Pipeline closure — what belongs to a pipeline](spec-pipeline-closure.md) · [Pipeline identity — rename & save-as-template](spec-pipeline-identity.md) · [Platform Services — the plugin envelope](spec-platform-services.md) · [Query catalog & execution](spec-queries.md) · [Tags — the cross-entity label graph](spec-tags.md) |
| architecture | current | [Jobs, scheduler and multi-Space](architecture-processor.md) |
| features | current | [Jobs and scheduler — features](features-jobs-scheduler.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Diagnoses](user-guide-diagnoses.md) · [Jobs](user-guide-jobs.md) |
| admin-guide | current | [Control plane and processor host — admin guide](admin-guide-processor.md) |
| api | current | [HTTP Control API](api-control-api.md) · [Jobs and scheduler — API](api-jobs-scheduler.md) · [Onboarding authoring seams (draft lifecycle, previews, register pair) — Add data — /onboard/files](api-onboarding-authoring-add-data.md) · [Onboarding authoring seams (draft lifecycle, previews, register pair) — Add data — the commit onward](api-onboarding-authoring-commit.md) · [Onboarding authoring seams (draft lifecycle, previews, register pair) — Drafts, previews, the register pair and reference production](api-onboarding-authoring-drafts-previews.md) · [Onboarding authoring seams (draft lifecycle, previews, register pair)](api-onboarding-authoring.md) · [Choose a table — the Postgres snapshot seam](api-onboarding-postgres-snapshot.md) · [HTTP routes — module.processor](api-routes.md) · [Versioned API (/api/v1)](api-v1.md) |
| tests | current | [Control plane and processor host — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.processor` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P3 | feature | Platform Services Stage 2 / 3 | demand |
| `IMPORT-RUN-REFUSAL-GAPS-1` | P3 | defect | Two kinds of run can still commit while Add data's run-scoped choice is open, and a held claim refuses every manual run (filed 2026-11-20… | — |
| `SNAPSHOT-OUTSIDE-RUN-NOW-1` | P3 | defect | A run of a switched Postgres snapshot outside Run now has no column check, no import.max_rows cap and only the engine's own fetch wording… | — |
| `BASIC-PROFILE-TIDY-1` | P3 | chore | Three small leftovers of the Basic profile lanes (filed 2026-11-20, BASIC-ELT-PROFILE-1 close). | — |
| `ADD-DATA-DECIMAL-SEPARATOR-1` | P3 | chore | Add data writes a decimal-comma column as a per-column expression in the mapping, not the Grammar's decimal_separator (filed 2026-11-20,… | — |
| `PIPELINE-SCHEMA-HISTORY-ROUTES-1` | P3 | feature | An import Pipeline's schema history (.history/pipelines/<id>/schema/, written since B5) is not visible or restorable through the API (fil… | — |
| `KEEP-COLUMNS-ENCODING-1` | P3 | defect | The rewrite that leaves a new column out of a delimited file ignores csv_settings.encoding (filed 2026-11-20, BASIC-ELT-PROFILE-1 B5 close). | — |
| — | P3 | feature | Bundle / Exchange — "load as draft" residuals | — |
| `ONBOARDING-REMOTE-SYSTEMS-1` | P3 | feature | Connect a system on Standard: Add data and the guided editor's Collect stage offer remote kinds (SFTP, object stores, other databases) wi… | — |
| `DEPLOY-EXPOSURE-KIT-1` | P3 | chore | Prove the deploy kit (proxy fence for /metrics and /health/details, TLS, upgrade, off-site backup, signature verify) on a real host. | — |
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | Two items wave 1 of LA-ONBOARD-1 left: how far no-code row filtering goes in Basic, and a real drag-and-drop of a file onto Add data. | operator |
