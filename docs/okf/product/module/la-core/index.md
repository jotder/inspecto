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
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Link analysis core and ports - plan of record](plan-la-core.md) |
| spec | current | [Inspecto LA case videos v2 — Banking and finance: six case specs](spec-case-videos-banking.md) · [Inspecto LA case videos v2 — Insurance, Utilities, Law enforcement and the shared Standard module](spec-case-videos-insurance-utilities-law.md) · [Inspecto LA case videos v2 — specs: public sector, healthcare, logistics, enterprise](spec-case-videos-public-health-logistics-enterprise.md) · [Inspecto LA case videos v2 - Telecom specs (five cases)](spec-case-videos-telecom.md) · [Inspecto LA - first-time user use cases (blank product to first findings)](spec-first-time-user-use-cases.md) · [Link analysis core and ports - spec](spec-la-core.md) · [Link analysis performance - decisions, seams and measured no-gos](spec-performance.md) |
| architecture | current | [Link Analysis — data onboarding](architecture-data-onboarding.md) · [Link Analysis — HTTP surface and Investigation UI](architecture-http-and-ui.md) · [Link Analysis — architecture](architecture-la-core.md) · [Link Analysis — as-built reference and design records](architecture-records.md) |
| features | current | [Link Analysis — features](features-la-core.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Link Analysis — User Guide](user-guide-la-core.md) · [Inspecto LA training videos — A · Getting started: narration scripts](user-guide-training-a-getting-started.md) · [Inspecto LA training videos — B · Building a graph: narration scripts](user-guide-training-b-building-a-graph.md) · [Inspecto LA training videos — C · Reading and shaping the canvas: narration scripts](user-guide-training-c-the-canvas.md) · [Inspecto LA training videos — D · The analysis toolbox: narration scripts](user-guide-training-d-analysis-toolbox.md) · [Inspecto LA training videos — E · Keeping and sharing your work: narration scripts](user-guide-training-e-keeping-and-sharing.md) · [Inspecto LA training videos — F · Investigations (Standard): narration scripts](user-guide-training-f-investigations.md) · [Inspecto LA training videos — G · Geo and entity pages (Standard): narration scripts](user-guide-training-g-geo-and-entity-pages.md) · [Inspecto LA training videos — H · Administering a Space: narration scripts](user-guide-training-h-administering-a-space.md) · [Inspecto LA training videos — I · Advanced edition: narration scripts](user-guide-training-i-advanced-edition.md) · [Inspecto LA training videos — narration scripts](user-guide-training-scripts.md) |
| admin-guide | current | [Inspecto LA demo kit - runbook and stakeholder Q&A](admin-guide-demo.md) · [Inspecto LA - administration (LA level, Investigations add-on, masking default, real OIDC sign-in)](admin-guide-la-core.md) |
| api | current | [Link Analysis — API](api-la-core.md) |
| tests | current | [Link Analysis core and ports — tests inventory](tests-inventory.md) · [Link Analysis - live drives and user sessions](tests-live-drives.md) |
| brochure | current | [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · [Inspecto LA case demos — generated, one plain live walk-through per case](brochure-video-demo-case-scripts.md) · [Inspecto LA product demo videos — a plain, live walk-through with an explainer voice](brochure-video-demo-scripts.md) · [Inspecto LA Standard demos — from the Basic walk-through to a sealed Dossier](brochure-video-demo-standard-scripts.md) · [Inspecto LA industry videos — story, data, map, investigation, conclusion](brochure-video-industry-scripts.md) · [Inspecto LA short videos — scripts and agency briefs](brochure-video-scripts.md) · [Inspecto LA short vertical videos — collage style, real product screens](brochure-video-short-scripts.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-core` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-FULL-CYCLE-RESIDUALS-1` | P3 | chore | what the full-cycle plan left on purpose, and two environment gaps its drive found. | — |
| `LA-TRAINING-SERIES-1` | P3 | feature | an Inspecto LA user training video series, planned and decided (D-TR1…D-TR7, 2026-11-16); all 41 episodes rendered 2026-11-19 (I1 and I2… | operator |
| `LA-CASE-VIDEOS-2` | P3 | feature | the twenty industry case films re-specified and re-recorded to the training-series standard (proposed 2026-11-19, D-CV1…D-CV6 open; specs… | operator |
| `LA-EXEC-DEMO-POLISH-1` | P3 | chore | what the third executive review of Inspecto LA (2026-11-16) left as polish, plus amounts on the links. | — |
| `SPACE-ADMISSION-FOLLOWUPS-1` | P3 | chore | what the closed-Space change (D5, 91f0af69a) left on purpose. | — |
| `LA-SPACE-SWITCHER-NAMES-1` | P3 | defect | the Space switcher still shows near-twin names, no name on a phone, and squeezes a two-line name. | — |
| `LA-UI-TEXT-PASS-2` | P3 | defect | la-app wording that waits on an operator call. | operator |
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | what wave 1 of LA-ONBOARD-1 left (2026-10-22). Re-triaged 2026-11-05: two items remain. | operator |
| `LA-GROW-EXCLUDE-THINK-ALOUD-1` | P3 | chore | run a think-aloud test of "Grow and clean a graph" with two analysts new to Link Analysis | external |
| `LA-INVESTIGATION-STORE-DESIGN-1` | P3 | feature | ⏸ POSTPONED 2026-10-23 (operator: the next release ships without Postgres | operator |
| `LA-INVESTIGATION-DELETE-PG-1` | P3 | feature | delete an Investigation on the Postgres store. | operator |
| `LA-INVESTIGATION-OPS-DEFERRED-1` | P3 | feature | the unbuilt half of the Investigation model. | demand |
| `LA-A11Y-AUDIT-1` | P3 | chore | what remains of the Link Analysis / Geo accessibility audit is person-only: a screen-reader reading (NVDA / JAWS / VoiceOver: the dock ta… | — |
| `LA-CONFIG-LIMITS-RESIDUALS-1` | P3 | feature | what LA-CONFIG-LIMITS-1 left after it shipped (2026-10-15; as-built in okf/product/module/la-core/architecture-la-core.md §9-§10; plan ar… | — |
| `LA-DAILY-INGEST-1` | P3 | feature | Daily scheduled ingestion of CSV, Hive and database feeds into Link Analysis (filed 2026-10-05; decisions answered, T1-T9 done or built;… | demand |
| `LA-WHITEPAPER-DEMO-1` | P3 | chore | narrowed 2026-10-24: the assistant walked T1-T13 alone and the run of show moved into the demo runbook section 9 with the rehearsal recor… | external |
| `USER-TRAINING-1` | P3 | feature | training on the Handbook's worked example: a Learning Path per track, Labs (lab-* Space Templates with an API checker), a platform video… | — |
