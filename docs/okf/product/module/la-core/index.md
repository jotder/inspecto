---
type: ProductItem
title: Link Analysis core and ports
item: module.la-core
description: Documentation hub for module.la-core — every document about it, by slot.
tags: [product-item, module]
---

# Link Analysis core and ports (`module.la-core`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.la-core`](../../../../../product-map/module/la-core.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Link analysis core and ports - plan of record](plan.md) |
| spec | current | [Inspecto LA - first-time user use cases (blank product to first findings)](spec-first-time-user-use-cases.md) · [Link analysis performance - decisions, seams and measured no-gos](spec-performance.md) · [Link analysis core and ports - spec](spec.md) |
| architecture | current | [Link Analysis — data onboarding](architecture-data-onboarding.md) · [Link Analysis — HTTP surface and Investigation UI](architecture-http-and-ui.md) · [Link Analysis — as-built reference and design records](architecture-records.md) · [Link Analysis — architecture](architecture.md) |
| features | current | [Link Analysis — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Link Analysis — User Guide](user-guide.md) |
| admin-guide | current | [Inspecto LA demo kit - runbook and stakeholder Q&A](admin-guide-demo.md) · [Inspecto LA - administration (LA level, Investigations add-on, masking default, real OIDC sign-in)](admin-guide.md) |
| api | current | [Link Analysis — API](api.md) |
| tests | current | [Link Analysis core and ports — tests inventory](tests-inventory.md) · [Link Analysis - live drives and user sessions](tests-live-drives.md) |
| brochure | current | [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · [Inspecto LA short videos — scripts and agency briefs](brochure-video-scripts.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-core` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-FULL-CYCLE-RESIDUALS-1` | P3 | chore | what the full-cycle plan left on purpose, and two environment gaps its drive found. | — |
| `SPACE-ADMISSION-FOLLOWUPS-1` | P3 | chore | what the closed-Space change (D5, 91f0af69a) left on purpose. | — |
| `LA-SPACE-SWITCHER-NAMES-1` | P3 | defect | the Space switcher still shows near-twin names, no name on a phone, and squeezes a two-line name. | — |
| `LA-UI-TEXT-PASS-2` | P3 | defect | la-app wording that waits on an operator call. | operator |
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | what wave 1 of LA-ONBOARD-1 left (2026-10-22). Re-triaged 2026-11-05: two items remain. | operator |
| `LA-GROW-EXCLUDE-THINK-ALOUD-1` | P3 | chore | run a think-aloud test of "Grow and clean a graph" with two analysts new to Link Analysis | external |
| `LA-INVESTIGATION-STORE-DESIGN-1` | P3 | feature | ⏸ POSTPONED 2026-10-23 (operator: the next release ships without Postgres | operator |
| `LA-INVESTIGATION-DELETE-PG-1` | P3 | feature | delete an Investigation on the Postgres store. | operator |
| `LA-INVESTIGATION-OPS-DEFERRED-1` | P3 | feature | the unbuilt half of the Investigation model. | demand |
| `LA-A11Y-AUDIT-1` | P3 | chore | what remains of the Link Analysis / Geo accessibility audit is person-only: a screen-reader reading (NVDA / JAWS / VoiceOver: the dock ta… | — |
| `LA-CONFIG-LIMITS-RESIDUALS-1` | P3 | feature | what LA-CONFIG-LIMITS-1 left after it shipped (2026-10-15; as-built in okf/product/module/la-core/architecture.md §9-§10; plan archived l… | — |
| `LA-DAILY-INGEST-1` | P3 | feature | Daily scheduled ingestion of CSV, Hive and database feeds into Link Analysis (filed 2026-10-05; decisions answered, T1-T9 done or built;… | demand |
| `LA-WHITEPAPER-DEMO-1` | P3 | chore | narrowed 2026-10-24: the assistant walked T1-T13 alone and the run of show moved into the demo runbook section 9 with the rehearsal recor… | external |
