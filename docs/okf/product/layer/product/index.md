---
type: ProductItem
title: Product host
item: layer.product
description: Documentation hub for layer.product — every document about it, by slot.
tags: [product-item, layer]
---

# Product host (`layer.product`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`layer.product`](../../../../../product-map/layer/product.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Assurance capability programme — generic product before the project (as built)](plan-assurance-capability.md) · [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · [Studio (BI + INV) — capability spec](plan-studio.md) · [Surfaces & Lenses (UI) — capability spec](plan-surfaces.md) |
| spec | current | [Accessibility (WCAG 2.2 AA)](spec-accessibility.md) · [API & Data Integration](spec-api-and-data.md) · [Components Registry](spec-components.md) · [Design Tokens & Styling](spec-design-tokens.md) · [Alert](spec-ds-alert.md) · [Chart](spec-ds-chart.md) · [Chip](spec-ds-chip.md) · [Connectivity Banner](spec-ds-connectivity-banner.md) · [Data Table (tiered family)](spec-ds-data-table.md) · [Empty State](spec-ds-empty-state.md) · [Design System Gallery](spec-ds-gallery.md) · [Grid (ag-Grid theme & helpers)](spec-ds-grid.md) · [Query (AST, SQL, eval, builder)](spec-ds-query.md) · [Rule (Pro Max templates)](spec-ds-rule.md) · [Skeleton](spec-ds-skeleton.md) · [Status Badge](spec-ds-status-badge.md) · [Tree Table (aligned tree-grid)](spec-ds-tree-table.md) · [Errors & Connectivity](spec-errors-connectivity.md) · [Unified Canvas UI, Docking System & Enterprise Analytics Step Directory](spec-floating-panels.md) · [Forms & State](spec-forms-state.md) · [Multi-Space Scoping](spec-multi-space.md) · [Page chrome — the shared header, filter bar, tiles, tabs and bulk actions](spec-page-chrome.md) · [Routing & Navigation](spec-routing-navigation.md) |
| architecture | current | [Log](architecture-change-log.md) · [Inspecto UI Overview](architecture-overview.md) · [Studio BI](architecture-studio-bi.md) · [Inspecto UI Architecture](architecture.md) |
| features | current | [Inspecto — Product Capabilities](features-product-capabilities.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Catalog](user-guide-catalog.md) · [Dashboard](user-guide-dashboard.md) · [Icon Settings](user-guide-icon-settings.md) · [Journey: from an alert to a closed Case](user-guide-journey-alert-to-case.md) · [Journey: from a file to a dashboard](user-guide-journey-file-to-dashboard.md) · [Journey: a safe config change](user-guide-journey-safe-config-change.md) · [Stream & Reference Onboarding](user-guide-onboarding.md) · [Spaces](user-guide-spaces.md) · [Studio](user-guide-studio.md) |
| admin-guide | current | [Product — admin guide](admin-guide.md) |
| api | current | [API Services Catalog](api-services.md) |
| tests | current | [Accessibility audit — `inspecto-ui` (WCAG 2.2 Level AA)](tests-accessibility-audit.md) · [Testing, Build & Definition of Done](tests-testing-build.md) |
| brochure | current | [Inspecto — Competitive Landscape](brochure-competitive-landscape.md) · [Inspecto — Competitive Capability Matrix](brochure-competitive-matrix.md) · [Inspecto — Enterprise Product & Solutions Whitepaper](brochure-enterprise-whitepaper.md) · [Inspecto — Executive Brief](brochure-executive-brief.md) · [Inspecto — Solution Overview](brochure-solution-overview.md) · [Inspecto — Platform Overview & Strategy](brochure-stakeholder-overview.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `layer.product` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AUTHORING-REDESIGN-1` | P2 | feature | the authoring redesign's still-open letters. | — |
| `ONBOARDING-FIRST-RUN-1` | P2 | feature | console first-run onboarding: from a blank install to a queryable Dataset, in every edition — the console companion LA-ONBOARD-1 OB-1 exp… | operator |
| `DATASET-CALC-NAME-COLLISION-1` | P2 | defect | a calculated column named like a base column ({name:"region", expr:"upper(msisdn)"}) is renamed region_1 by DuckDB and served raw (same r… | — |
| `BI-MASKING-CALC-DERIVED-ALIAS-1` | P2 | defect | a calculated column derived from a key (substr(msisdn,1,4), coalesce('4417', msisdn)) keeps the linkable Space alias, so a Dataset author… | — |
| `LINEAGE-NONWORD-MAPPING-NAME-1` | P2 | defect | EvidenceMasker.schemaClassification's pipeline-mapping scan only reads [A-Za-z0-9_] words, so a classified raw field with a non-word name… | — |
| `AGT-SEGMENT-1` | P3 | chore | the assistant's commercial framing is an unvalidated product read. | — |
| `UI-THEME-NULL-TOKENS-1` | P3 | defect | themes.scss rebuilds each Material M2 theme without its _mat-system key, so Material 22 token functions return null; 3fa647719 restored o… | — |
| `PACK-SALES-MARKETING-1` | P3 | feature | Sales & Marketing BI pack: sales, commissions, marketing and churn analytics | operator |
| `DOCS-AGENT-WRITTEN-REVIEW-1` | P3 | chore | ~200 hub docs were written by agents on 2026-10-19 (PRODUCT-DOCS-HUB-1) | — |
| `DOCS-FUNCTIONALITY-HUBS-1` | P3 | chore | decide whether functionality/ Product Map Items get their own hubs or stay sections of their module's features slot (none exist yet, so n… | operator |
