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
| plan | current | [LA-COLUMN-ROLES-2 — design notes for the three parts that need a decision (2026-11-05)](plan-column-roles-2.md) · [Link analysis core and ports - plan of record](plan.md) |
| spec | current | [Inspecto LA - first-time user use cases (blank product to first findings)](spec-first-time-user-use-cases.md) · [Link analysis core and ports - spec](spec.md) |
| architecture | current | [Link Analysis backend architecture (la/ modules, entity store, host wiring)](architecture-backend.md) · [Link Analysis — data onboarding](architecture-data-onboarding.md) · [Link Analysis — HTTP surface](architecture-http-surface.md) · [Link Analysis — Investigation UI](architecture-investigation-ui.md) · [Link Analysis — masking](architecture-masking.md) · [Link Analysis](architecture.md) |
| features | current | [Link Analysis — features](features-link-analysis.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Investigation Pivot](user-guide-investigation-pivot.md) · [Link Analysis — User Guide](user-guide-link-analysis.md) |
| admin-guide | current | [Inspecto LA (Link Analysis) demo runbook (rehearsable, stakeholder presentation)](admin-guide-demo.md) · [Inspecto LA - the Link Analysis level, the Investigations add-on and the masking default](admin-guide-la-level.md) · [Inspecto LA (Link Analysis) real OIDC sign-in runbook (`la-app`, local WSO2 Identity Server)](admin-guide-real-signin.md) |
| api | current | [Link Analysis — API](api-link-analysis.md) |
| tests | current | [Link Analysis analyst coverage test (UI-only, impersonated analyst)](tests-analyst-coverage.md) · [Link Analysis data trust: live drive (S10 of `LA-DATA-TRUST-1`)](tests-data-trust-live-drive.md) · [Link analysis core and ports — tests inventory](tests-inventory.md) · [Investigation tab - live re-measure of the undriven workflows (2026-11-02)](tests-investigation-tab-live-drive.md) · [Inspecto LA onboarding - acceptance live drive (`LA-ONBOARD-1` §1)](tests-onboarding-live-drive.md) · [Link Analysis stakeholder Q&A (presenter prep and leave-behind)](tests-stakeholder-qa.md) · [D6 think-aloud session script — a real Case-desk lead](tests-think-aloud-script.md) |
| brochure | current | [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · [Inspecto LA short videos — scripts and agency briefs](brochure-video-scripts.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-core` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-BASIC-PLACEHOLDER-CASE-1` | P2 | defect | in Basic, Attach to Case lists invented placeholder Cases, seals the Snapshot and reports it attached. | — |
| `LA-SAMPLE-EMPTY-DATASET-1` | P2 | defect | the la-sample Space's only Dataset is empty on the Basic install, so its example and its only saved view fail. | — |
| `LA-DOCK-PAGE-GROWS-1` | P2 | defect | opening the Link Analysis bottom Data dock makes the page taller than the window. | — |
| `LA-BASIC-503-EXPLAINED-1` | P2 | defect | Basic-edition 503s show as red errors or with a wrong reason. | — |
| `LA-RADIAL-LAYOUT-SQUASH-1` | P3 | defect | the Radial layout draws a two-component graph as a narrow, overlapping wedge. | — |
| `LA-PHONE-OVERFLOW-1` | P3 | defect | at phone width the Link Analysis tool bar and the Add data page scroll sideways. | — |
| `LA-CLIPPED-CONTROLS-1` | P3 | defect | Link Analysis controls clipped by their panels. | — |
| `LA-TOOLBAR-SHIFT-1` | P3 | defect | the canvas tool bar shifts under the pointer when a node search adds or removes its clear button. | — |
| `LA-OVERLAYS-HIDE-GRAPH-1` | P3 | defect | the expanded Legend and Graph counts cover the drawn graph, fit puts nodes under overlays or on the edge, and node labels start off. | — |
| `LA-OPEN-DIALOG-ACTIONS-1` | P3 | defect | File ▸ Open puts Open / Rename / Delete below the fold of its scroll area and shows Last changed in UTC. | — |
| `LA-SETTINGS-PROFILE-FLASH-1` | P3 | defect | Settings ▸ Link Analysis flashes a red "Domain Profile unavailable" error while it loads. | — |
| `LA-UI-TEXT-PASS-2` | P3 | defect | wording and vocabulary breaks left on the la-app screens. | — |
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | what wave 1 of LA-ONBOARD-1 left (2026-10-22). Re-triaged 2026-11-05: two items remain. | operator |
| `LA-GROW-EXCLUDE-THINK-ALOUD-1` | P3 | chore | run a think-aloud test of "Grow and clean a graph" with two analysts new to Link Analysis | external |
| `LA-INVESTIGATION-STORE-DESIGN-1` | P3 | feature | ⏸ POSTPONED 2026-10-23 (operator: the next release ships without Postgres | operator |
| `LA-INVESTIGATION-DELETE-PG-1` | P3 | feature | delete an Investigation on the Postgres store. | operator |
| `LA-INVESTIGATION-OPS-DEFERRED-1` | P3 | feature | the unbuilt half of the Investigation model. | demand |
| `LA-A11Y-AUDIT-1` | P3 | chore | what remains of the Link Analysis / Geo accessibility audit is person-only: a screen-reader reading (NVDA / JAWS / VoiceOver: the dock ta… | — |
| `LA-CONFIG-LIMITS-RESIDUALS-1` | P3 | feature | what LA-CONFIG-LIMITS-1 left after it shipped (2026-10-15; as-built in okf/product/module/la-core/architecture-backend.md §9-§10; plan ar… | — |
| `LA-DAILY-INGEST-1` | P3 | feature | Daily scheduled ingestion of CSV, Hive and database feeds into Link Analysis (filed 2026-10-05; decisions answered, T1-T9 done or built;… | demand |
| `LA-WHITEPAPER-DEMO-1` | P3 | chore | narrowed 2026-10-24: the assistant walked T1-T13 alone and the run of show moved into the demo runbook section 9 with the rehearsal recor… | external |
| `LA-PERF-N4-1` | P2 | feature | Layouts and large-selection repaints still block the main thread on 767 nodes / 4,000 links (N4, re-measured 2026-11-12): first open 7.4… | demand |
| `LA-PERF-P5-1` | P3 | feature | Betweenness in a Web Worker, or approximated (P5). | demand |
| `LA-PERF-P10A5-1` | P3 | feature | Expand and Grow fetch only the delta (P10a-5). | — |
