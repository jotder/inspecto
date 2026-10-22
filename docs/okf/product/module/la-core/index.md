---
type: ProductItem
title: Link analysis core and ports
item: module.la-core
description: Documentation hub for module.la-core — every document about it, by slot.
tags: [product-item, module]
---

# Link analysis core and ports (`module.la-core`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.la-core`](../../../../../product-map/module/la-core.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Link analysis core and ports - plan of record](plan.md) |
| spec | current | [Link analysis core and ports - spec](spec.md) |
| architecture | current | [Link Analysis](architecture-overview.md) · [Link Analysis backend architecture (la/ modules, entity store, host wiring)](architecture.md) |
| features | current | [Link Analysis — features](features-link-analysis.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Inspecto LA (Link Analysis) — End User Guide](user-guide-end-user.md) · [Investigation Pivot](user-guide-investigation-pivot.md) · [Link Analysis](user-guide-link-analysis.md) · [Inspecto LA (Link Analysis) — User Manual](user-guide-manual.md) |
| admin-guide | current | [Inspecto LA (Link Analysis) demo runbook (rehearsable, stakeholder presentation)](admin-guide-demo.md) · [Inspecto LA (Link Analysis) real OIDC sign-in runbook (`la-app`, local WSO2 Identity Server)](admin-guide-real-signin.md) |
| api | current | [Link Analysis — API](api-link-analysis.md) |
| tests | current | [Link Analysis analyst coverage test (UI-only, impersonated analyst)](tests-analyst-coverage.md) · [Link Analysis data trust: live drive (S10 of `LA-DATA-TRUST-1`)](tests-data-trust-live-drive.md) · [Link analysis core and ports — tests inventory](tests-inventory.md) · [Link Analysis stakeholder Q&A (presenter prep and leave-behind)](tests-stakeholder-qa.md) · [D6 think-aloud session script — a real Case-desk lead](tests-think-aloud-script.md) |
| brochure | current | [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-core` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `ONBOARDING-FIRST-RUN-1` | P2 | feature | first-run onboarding: from a blank install to a Dataset an application can use, in every edition. | operator |
| `LA-GROW-EXCLUDE-RESIDUALS-1` | P3 | feature | what LA-GROW-EXCLUDE-1 left after it shipped (2026-10-22; as-built in okf/product/module/la-core/architecture.md §6.8–§6.9). | — |
| `LA-GROW-EXCLUDE-DOSSIER-RESTRICTED-1` | P3 | defect | the Dossier still shows a restricted exclusion list's removed ids and true counts to an analyst who may not see its members. | — |
| `LA-GROW-EXCLUDE-UNDO-RACE-1` | P3 | defect | in a shared Investigation, a Grow / Exclude Undo can take back a team member's step. | — |
| `LA-GROW-EXCLUDE-THINK-ALOUD-1` | P3 | chore | run a think-aloud test of "Grow and clean a graph" with two analysts new to Link Analysis | external |
| `LA-INVESTIGATION-STORE-DESIGN-1` | P3 | feature | an InvestigationStore seam so the Investigation sidecar files can live on Postgres for multi-pod deployments. | demand |
| `LA-INVESTIGATION-OPS-DEFERRED-1` | P3 | feature | the unbuilt half of the Investigation model. | demand |
| `LA-A11Y-AUDIT-1` | P3 | chore | what remains of the Link Analysis / Geo accessibility audit is person-only: a screen-reader reading (NVDA / JAWS / VoiceOver: the node-li… | — |
| `LA-CONFIG-LIMITS-RESIDUALS-1` | P3 | feature | what LA-CONFIG-LIMITS-1 left after it shipped (2026-10-15; as-built in okf/product/module/la-core/architecture.md §9-§10; plan archived l… | — |
| `LA-ENTITY-PROFILE-RESIDUALS-1` | P3 | feature | what the Entity profile pages left after P0-P3 shipped (2026-10-15; as-built in okf/product/module/la-core/architecture.md §6.3, plan arc… | — |
| `LA-I2-GAPS-RESIDUALS-1` | P3 | feature | what the i2-gap lanes left (2026-10-18). The canvas-styling, visual-query and entity-merge plans are fully shipped and archived (la-canva… | — |
| `LA-DAILY-INGEST-1` | P2 | feature | Daily scheduled ingestion of CSV, Hive and database feeds into Link Analysis (filed 2026-10-05; decisions answered, T1-T9 done or built;… | — |
