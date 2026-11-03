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
| user-guide | current | [Investigation Pivot](user-guide-investigation-pivot.md) · [Link Analysis — User Guide](user-guide-link-analysis.md) |
| admin-guide | current | [Inspecto LA (Link Analysis) demo runbook (rehearsable, stakeholder presentation)](admin-guide-demo.md) · [Inspecto LA - the Link Analysis level, the Investigations add-on and the masking default](admin-guide-la-level.md) · [Inspecto LA (Link Analysis) real OIDC sign-in runbook (`la-app`, local WSO2 Identity Server)](admin-guide-real-signin.md) |
| api | current | [Link Analysis — API](api-link-analysis.md) |
| tests | current | [Link Analysis analyst coverage test (UI-only, impersonated analyst)](tests-analyst-coverage.md) · [Link Analysis data trust: live drive (S10 of `LA-DATA-TRUST-1`)](tests-data-trust-live-drive.md) · [Link analysis core and ports — tests inventory](tests-inventory.md) · [Investigation tab - live re-measure of the undriven workflows (2026-11-02)](tests-investigation-tab-live-drive.md) · [Inspecto LA onboarding - acceptance live drive (`LA-ONBOARD-1` §1)](tests-onboarding-live-drive.md) · [Link Analysis stakeholder Q&A (presenter prep and leave-behind)](tests-stakeholder-qa.md) · [D6 think-aloud session script — a real Case-desk lead](tests-think-aloud-script.md) |
| brochure | current | [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.la-core` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LA-PRESET-ROLES-1` | P3 | defect | preset role detection guesses From/To from the first two classified columns, which can be the wrong pair (filed 2026-11-03). | demand |
| `LA-BASIC-DISCOVER-1` | P3 | feature | Discover a Dataset in Basic: entities per Entity Type, busiest entities, link kinds, period, amount totals (LA-BASIC-DISCOVERY-1 P0 + P1,… | — |
| `LA-ENTITY-SUMMARY-1` | P3 | feature | an entity summary in Basic (first / last seen, top counterparts by links and amount, link kinds, activity) - the full profile pages stay… | — |
| `LA-QUERY-BUILDER-1` | P3 | feature | a no-code query builder over a Dataset's roles with *Draw these* and *Save as preset* (LA-BASIC-DISCOVERY-1 P4). | — |
| `LA-PIPELINE-MAPPINGS-1` | P3 | feature | saved Mappings of Datasets that Pipelines keep up to date, not only of Add data Datasets (filed 2026-11-03). | operator |
| `LA-ARRIVAL-LEDGER-1` | P2 | feature | an arrival ledger per Dataset and a three-clock *Data status* (received · covers through · graph indexed through), in every edition (LA-D… | — |
| `LA-ARRIVAL-MODES-1` | P3 | feature | incremental semantics for import Datasets: *Replace the period it covers*, duplicate-upload refusal (sha256) and a stated schema-drift po… | — |
| `LA-ARRIVAL-FEEDS-1` | P3 | feature | files from a foreign process, made visible (LA-DATA-ARRIVAL-1 P3, decision D-1). | — |
| `LA-DATASET-COMPACT-1` | P3 | feature | compaction of an import Dataset's Parquet in every edition, a retention policy on Standard+ (LA-DATA-ARRIVAL-1 P4, decision D-5). | — |
| `LA-ONBOARD-RESIDUALS-1` | P3 | feature | what wave 1 of LA-ONBOARD-1 left (2026-10-22): (1) ✅ decided 2026-10-24 (assistant-decided under operator delegation, pending confirmatio… | — |
| `LA-GROW-EXCLUDE-THINK-ALOUD-1` | P3 | chore | run a think-aloud test of "Grow and clean a graph" with two analysts new to Link Analysis | external |
| `LA-INVESTIGATION-STORE-DESIGN-1` | P3 | feature | ⏸ POSTPONED 2026-10-23 (operator: the next release ships without Postgres | operator |
| `LA-INVESTIGATION-DELETE-PG-1` | P3 | feature | delete an Investigation on the Postgres store. | operator |
| `LA-INVESTIGATION-OPS-DEFERRED-1` | P3 | feature | the unbuilt half of the Investigation model. | demand |
| `LA-A11Y-AUDIT-1` | P3 | chore | what remains of the Link Analysis / Geo accessibility audit is person-only: a screen-reader reading (NVDA / JAWS / VoiceOver: the dock ta… | — |
| `LA-CONFIG-LIMITS-RESIDUALS-1` | P3 | feature | what LA-CONFIG-LIMITS-1 left after it shipped (2026-10-15; as-built in okf/product/module/la-core/architecture.md §9-§10; plan archived l… | — |
| `LA-DAILY-INGEST-1` | P3 | feature | Daily scheduled ingestion of CSV, Hive and database feeds into Link Analysis (filed 2026-10-05; decisions answered, T1-T9 done or built;… | demand |
| `LA-WHITEPAPER-DEMO-1` | P3 | chore | narrowed 2026-10-24: the assistant walked T1-T13 alone and the run of show moved into the demo runbook section 9 with the rehearsal recor… | external |
