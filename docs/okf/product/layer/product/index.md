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
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Assurance capability programme — generic product before the project (as built)](plan-assurance-capability.md) · [layer.product - board row detail](plan-board-detail.md) · [Product maturity assessment — every client-facing item — Per-item assessments — add-ons for operations, investigation and AI](plan-maturity-assessment-addons-operations.md) · [Product maturity assessment — every client-facing item — Per-item assessments — add-ons for risk, assurance, data and Link Analysis](plan-maturity-assessment-addons-risk.md) · [Product maturity assessment — every client-facing item — Per-item assessments — Link Analysis packs, Inspecto LA and the editions](plan-maturity-assessment-la-editions.md) · [Product maturity assessment — every client-facing item — Per-item assessments — fintech and commerce solution packs](plan-maturity-assessment-packs-fintech.md) · [Product maturity assessment — every client-facing item — Per-item assessments — telecom solution packs](plan-maturity-assessment-packs-telecom.md) · [Product maturity assessment — every client-facing item — Per-item assessments — platform base](plan-maturity-assessment-platform-base.md) · [Product maturity assessment — every client-facing item — Per-item assessments — Preview edition, and references](plan-maturity-assessment-preview-references.md) · [Product maturity assessment — every client-facing item — Verdict, scoring and scorecard](plan-maturity-assessment-verdict.md) · [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · [Studio (BI + INV) — capability spec — Decisions, not built, refused and verification](plan-studio-decisions-verification.md) · [Studio (BI + INV) — capability spec — Specification — KPIs, Reports and scheduled delivery onward](plan-studio-kpis-reports.md) · [Studio (BI + INV) — capability spec — Purpose, requirements and specification](plan-studio-scope-specification.md) · [Studio (BI + INV) — capability spec](plan-studio.md) · [Surfaces & Lenses (UI) — capability spec — Decisions, not built, refused and verification](plan-surfaces-decisions-verification.md) · [Surfaces & Lenses (UI) — capability spec — Purpose, requirements and specification](plan-surfaces-scope-specification.md) · [Surfaces & Lenses (UI) — capability spec](plan-surfaces.md) |
| spec | current | [Accessibility (WCAG 2.2 AA)](spec-accessibility.md) · [API & Data Integration](spec-api-and-data.md) · [Components Registry](spec-components.md) · [Design Tokens & Styling](spec-design-tokens.md) · [Alert](spec-ds-alert.md) · [Chart](spec-ds-chart.md) · [Chip](spec-ds-chip.md) · [Connectivity Banner](spec-ds-connectivity-banner.md) · [Data Table (tiered family)](spec-ds-data-table.md) · [Empty State](spec-ds-empty-state.md) · [Design System Gallery](spec-ds-gallery.md) · [Grid (ag-Grid theme & helpers)](spec-ds-grid.md) · [Query (AST, SQL, eval, builder)](spec-ds-query.md) · [Rule (Pro Max templates)](spec-ds-rule.md) · [Skeleton](spec-ds-skeleton.md) · [Status Badge](spec-ds-status-badge.md) · [Tree Table (aligned tree-grid)](spec-ds-tree-table.md) · [Errors & Connectivity](spec-errors-connectivity.md) · [Unified Canvas UI, Docking System & Enterprise Analytics Step Directory](spec-floating-panels.md) · [Forms & State](spec-forms-state.md) · [Handbook docs as tests — the check-block format](spec-handbook-doc-tests.md) · [Multi-Space Scoping](spec-multi-space.md) · [Page chrome — the shared header, filter bar, tiles, tabs and bulk actions](spec-page-chrome.md) · [Routing & Navigation](spec-routing-navigation.md) |
| architecture | current | [Inspecto UI Architecture](architecture-product.md) · [Studio BI](architecture-studio-bi.md) · [inspecto-ui conventions and gotchas](architecture-ui-conventions.md) |
| features | current | [Inspecto — Product Capabilities](features-product-capabilities.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Catalog](user-guide-catalog.md) · [App](user-guide-concept-app.md) · [Dataset](user-guide-concept-dataset.md) · [Edition and Add-on](user-guide-concept-edition-addon.md) · [How the pieces fit](user-guide-concept-overview.md) · [Widget and Dashboard](user-guide-concept-widget-dashboard.md) · [Dashboard](user-guide-dashboard.md) · [Icon Settings](user-guide-icon-settings.md) · [Journey: from an alert to a closed Case](user-guide-journey-alert-to-case.md) · [Journey: from a file to a dashboard](user-guide-journey-file-to-dashboard.md) · [Journey: a safe config change](user-guide-journey-safe-config-change.md) · [Stream & Reference Onboarding](user-guide-onboarding.md) · [Spaces](user-guide-spaces.md) · [Studio](user-guide-studio.md) · [Tutorial 4 — Present it: a Dashboard for the business user](user-guide-tutorial-dashboard.md) |
| admin-guide | current | [Product — admin guide](admin-guide-product.md) |
| api | current | [API Services Catalog](api-services.md) |
| tests | current | [Accessibility audit — `inspecto-ui` (WCAG 2.2 Level AA)](tests-accessibility-audit.md) · [Testing, Build & Definition of Done](tests-testing-build.md) |
| brochure | current | [Inspecto — Competitive Landscape](brochure-competitive-landscape.md) · [Inspecto — Competitive Capability Matrix](brochure-competitive-matrix.md) · [Inspecto — Enterprise Product & Solutions Whitepaper](brochure-enterprise-whitepaper.md) · [Inspecto — Executive Brief](brochure-executive-brief.md) · [Inspecto — Solution Overview](brochure-solution-overview.md) · [Inspecto — Platform Overview & Strategy](brochure-stakeholder-overview.md) |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `layer.product` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AUTHORING-REDESIGN-1` | P3 | feature | Only letter (d) of the authoring redesign is left; no CREATE MACRO exists in any main source. | demand |
| `BASIC-ELT-PROFILE-1` | P2 | feature | Make Basic onboarding a locked profile of ELT, retiring the parallel Basic backend and LA UI layers that duplicate ELT parts. | — |
| — | P3 | defect | COMPONENT-DELETE-IN-USE-WORDS-1 the Components screen words every refused delete as "referenced by a pipeline" | — |
| `BI-INSIGHTS-1` | P2 | feature | Advanced insights on business data in Widgets and Dashboards, authored and tuned as Insight Profiles. | — |
| `SPACE-TO-APP-CONSOLE-1` | P3 | feature | Turn a working Space into a Space Template, and into an App, from the console. | — |
| `AGT-SEGMENT-1` | P3 | chore | the assistant's commercial framing is an unvalidated product read. | — |
| `PRODUCT-ASSEMBLY-1` | P2 | feature | Assemble finished domain Products and the Platform with chosen Add-ons from definition files, with one tool. | — |
| `ADDON-PICKER-PAGE-1` | P3 | feature | the Add-on picker has no page yet: tools/pick-addons.mjs (PA-6c, 2026-11-11) is a CLI. | demand |
| `UI-ZONELESS-1` | P3 | chore | the app runs Zone.js on purpose; going zoneless needs a component-by-component audit first (filed 2026-11-15, so the code's pointer has a… | demand |
| `UI-PICKER-OPTIONS-1` | P3 | feature | <inspecto-option-picker> gains nested options (a parent that opens to its children), a disabled option with its reason, and a per-picker… | demand |
| `UI-SCHEMA-FORM-EDITOR-1` | P3 | feature | Move <inspecto-schema-form>'s in-row editor onto pen-field, which first needs several new pen-field features. | demand |
| `UI-BUNDLE-BUDGETS-RED-1` | P3 | defect | The la-app and gamma bundle checks fail on master (seen 2026-11-19). | — |
| `PACK-SALES-MARKETING-1` | P3 | feature | A Sales & Marketing BI pack: sales, commissions, marketing and churn analytics. | operator |
| `INFO-RESTRUCTURE-1` | P2 | chore | The information base is restructured and kept right: one fact one home, current state only, pages an agent can read whole. | — |
| `USER-HANDBOOK-1` | P2 | feature | The Handbook: users learn to build, ship and manage an App from the console's Help, organised by lifecycle for three tracks. | — |
| `USER-TRAINING-1` | P3 | feature | training on the Handbook's worked example: a Learning Path per track, Labs (lab-* Space Templates with an API checker), a platform video… | — |
