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
| spec | current | [Inspecto LA - first-time user use cases (blank product to first findings)](spec-first-time-user-use-cases.md) · [Link analysis core and ports - spec](spec.md) |
| architecture | current | [Link Analysis](architecture-overview.md) · [Link Analysis backend architecture (la/ modules, entity store, host wiring)](architecture.md) |
| features | current | [Link Analysis — features](features-link-analysis.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Inspecto LA (Link Analysis) — End User Guide](user-guide-end-user.md) · [Investigation Pivot](user-guide-investigation-pivot.md) · [Link Analysis](user-guide-link-analysis.md) · [Inspecto LA (Link Analysis) — User Manual](user-guide-manual.md) |
| admin-guide | current | [Inspecto LA (Link Analysis) demo runbook (rehearsable, stakeholder presentation)](admin-guide-demo.md) · [Inspecto LA - the Link Analysis level, the Investigations add-on and the masking default](admin-guide-la-level.md) · [Inspecto LA (Link Analysis) real OIDC sign-in runbook (`la-app`, local WSO2 Identity Server)](admin-guide-real-signin.md) |
| api | current | [Link Analysis — API](api-link-analysis.md) |
| tests | current | [Link Analysis analyst coverage test (UI-only, impersonated analyst)](tests-analyst-coverage.md) · [Link Analysis data trust: live drive (S10 of `LA-DATA-TRUST-1`)](tests-data-trust-live-drive.md) · [Link analysis core and ports — tests inventory](tests-inventory.md) · [Inspecto LA onboarding - acceptance live drive (`LA-ONBOARD-1` §1)](tests-onboarding-live-drive.md) · [Link Analysis stakeholder Q&A (presenter prep and leave-behind)](tests-stakeholder-qa.md) · [D6 think-aloud session script — a real Case-desk lead](tests-think-aloud-script.md) |
| brochure | current | [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-core` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | what wave 1 of LA-ONBOARD-1 left (2026-10-22): (1) ✅ decided 2026-10-24 (assistant-decided under operator delegation, pending confirmatio… | — |
| `LA-GROW-EXCLUDE-THINK-ALOUD-1` | P3 | chore | run a think-aloud test of "Grow and clean a graph" with two analysts new to Link Analysis | external |
| `LA-INVESTIGATION-STORE-DESIGN-1` | P3 | feature | ⏸ POSTPONED 2026-10-23 (operator: the next release ships without Postgres | operator |
| `LA-INVESTIGATION-DELETE-PG-1` | P3 | feature | delete an Investigation on the Postgres store. | operator |
| `LA-INVESTIGATION-OPS-DEFERRED-1` | P3 | feature | the unbuilt half of the Investigation model. | demand |
| `LA-A11Y-AUDIT-1` | P3 | chore | what remains of the Link Analysis / Geo accessibility audit is person-only: a screen-reader reading (NVDA / JAWS / VoiceOver: the node-li… | — |
| `LA-CONFIG-LIMITS-RESIDUALS-1` | P3 | feature | what LA-CONFIG-LIMITS-1 left after it shipped (2026-10-15; as-built in okf/product/module/la-core/architecture.md §9-§10; plan archived l… | — |
| `LA-ENTITY-PROFILE-RESIDUALS-1` | P3 | feature | what the Entity profile pages left after P0-P3 shipped (2026-10-15; as-built in okf/product/module/la-core/architecture.md §6.3, plan arc… | — |
| `LA-I2-GAPS-RESIDUALS-1` | P3 | feature | what the i2-gap lanes left (2026-10-18). The canvas-styling, visual-query and entity-merge plans are fully shipped and archived (la-canva… | — |
| `LA-DAILY-INGEST-1` | P2 | feature | Daily scheduled ingestion of CSV, Hive and database feeds into Link Analysis (filed 2026-10-05; decisions answered, T1-T9 done or built;… | operator |
| `LA-WHITEPAPER-DEMO-1` | P3 | chore | narrowed 2026-10-24: the assistant walked T1-T13 alone and the run of show moved into the demo runbook section 9 with the rehearsal recor… | external |
| `LA-INVESTIGATE-CARD-FOUR-EYES-HOLD-1` | P2 | defect | the *Investigate* start card ends at "waiting for approval" in every showcase Space. | — |
| `LA-SUSPICION-FACTORS-DEMO-1` | P3 | defect | the Suspicion score's per-node "Factors" cannot be shown on any showcase Space (whitepaper claim T2). | — |
| `LA-STUDIO-UI-REORG-1` | P2 | feature | reorganize the Link Analysis Studio UI so it is easier and cleaner without removing any function (plan 2026-10-27, direction decided by t… | — |
