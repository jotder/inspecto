# Solution suite documentation — plan (`SOLUTION-SUITE-DOCS-1`)

> **Status:** ✅ SHIPPED 2026-10-18 — all five waves built, L0 [`SOLUTION_OVERVIEW.md`](../../okf/product/layer/product/brochure-solution-overview.md) written, review pass done (edition names conformed to Basic/Standard/Advanced, Link Analysis offering corrected). Move to `plans-archive/` once the operator accepts. Decisions taken below. Follows the rewrite of
> `docs/okf/product/layer/platform/architecture-technical.md` (level 1). Operator ask: a next-level architecture and solution
> breakdown, down to small modules such as maker-checker, so that system users understand how things work.

## 1. Goal and readers

One navigable suite, from "what is Inspecto" down to "how exactly does a Pending Change get approved", each
page answering the same questions in the same order. Three readers, three depths:

| Level | Reader | Question it answers | Where |
|---|---|---|---|
| L0 Overview | buyer, executive | What does it do for me, in which Offering? | `docs/stakeholders/` |
| L1 Architecture | architect, integrator | How is the system put together? | `TECHNICAL_ARCHITECTURE.md` (done) |
| L2 Solution | solution designer, admin | How do modules combine into a solution (RA, Fraud, AML …)? | `docs/stakeholders/solutions/` |
| L3 Module | operator, admin, developer | How does this one module work, end to end? | `docs/stakeholders/modules/` |

L2 and L3 pages are audience-specific, so both sit in `docs/stakeholders/` (see D-SS1). No new root-level `docs/*.md`.

## 2. The module page template (L3)

Every module page has the same nine headings, so a reader learns the shape once:

1. **What it is** — two sentences, canonical term from `GLOSSARY.md`.
2. **Who uses it** — roles and permissions involved.
3. **Where it sits** — layer, module jar, Offering / add-on, Installed–Enabled–Permitted gates.
4. **How it works** — one SVG flow (state machine or sequence) plus a numbered walkthrough.
5. **Configuration** — TOON keys with defaults and a minimal example.
6. **Screens** — which UI screens, with what each does.
7. **API** — `/api/v1` routes, linked to the OpenAPI contract.
8. **Interactions** — Platform Services used, events emitted, other modules touched.
9. **Limits and gotchas** — known constraints, linked BACKLOG rows.

Diagrams: generated SVGs (one generator script under `tools/`, light theme, same palette as level 1) so they
can be regenerated when a module changes, stored next to the page in `assets/`.

## 3. Module breakdown (L3 pages)

Grouped by layer; ✚ marks pages that exist in some form under `docs/okf/` and are reshaped to the template
rather than written fresh.

**Platform base**
- Maker-checker (Pending Changes, Approval Policy) ✚
- Decision Kernel and condition tree (Decision Rules)
- Events and event log ✚
- Alerts and Alert Rules ✚
- Notifications and channels ✚
- Platform Services and dry run ✚
- Spaces and Space Templates ✚
- Identity, roles and access policies ✚
- Collectors (acquisition) ✚
- Parsing and ingest (Stage-1, quarantine) ✚
- Pipelines and Steps ✚
- Consignments and Jobs, scheduler ✚
- Analytics Kernel (statistics, baselines, forecasting, scores) ✚
- Graph library
- Studio & BI (Datasets, Measures, Widgets, dashboards) ✚
- Storage (lakehouse, Configuration, Operational DB, Space roots, Sinks)
- Observability and backup

**Add-ons**
- Incidents ✚
- Case management
- Workflow & SLA
- Action Requests (four-eyes, approverCheck)
- Reconciliation
- Scoring & Entity Lists
- Anomaly detection
- Screening
- Regulatory reporting
- Exchange (multi-entity)
- Link Analysis (investigations, graph index, geo) ✚
- AI assist (agent, agentic investigation)

About 30 pages; roughly half are reshapes.

## 4. Solution breakdown (L2 pages)

One page per content pack, each showing which modules it switches on and the end-to-end story:
Revenue Assurance · Telecom Fraud · Payment Fraud · AML · Mobile Money · Business Assurance · Link Analysis.
Each page: business problem, data in (Streams and References), Pipelines, Measures and dashboards, Alert and
Decision Rules, what an analyst does day to day, one solution SVG (modules lit up on the layer diagram).

Plus three cross-cutting journey pages: **from file to dashboard**, **from alert to closed Case**, and
**changing configuration safely** (maker-checker + dry run + history).

## 5. Navigation

- `docs/stakeholders/SOLUTION_SUITE.md` is not created (no new root topic); instead
  `TECHNICAL_ARCHITECTURE.md` gains a "Go deeper" section linking L2 and L3 indexes, and
  `docs/okf/product/index.md` lists L3 pages by layer.
- Each L3 page links up (its layer, its Offering) and across (interactions).

## 6. Build order

| Wave | Content | Why first |
|---|---|---|
| 1 | Template + diagram generator; pages: maker-checker, Action Requests, Decision Kernel, Platform Services | Operator's named example; governance is the most asked-about |
| 2 | Journey pages (file to dashboard, alert to Case, safe config change) | Ties modules together for new users |
| 3 | Remaining platform-base pages | Every Offering needs them |
| 4 | Add-on pages | Sold separately; needed for sales and onboarding |
| 5 | L2 solution pages per pack | Depend on the module pages they link |

Each wave: pages written from the code (`codegraph_explore` and the existing OKF concepts, never from memory),
then vocabulary, doc-link, citation guards; a verifier checks one claim per section against the code.

## 7. Decisions (taken 2026-10-17 by the agent; operator asked to proceed in parallel)

- **D-SS1** — L3 module pages live in `docs/stakeholders/modules/`, beside the L2 pages in
  `docs/stakeholders/solutions/` (both audience-specific trees, CLAUDE.md tier 1). First placed under
  `docs/okf/capabilities/modules/`, then moved the same day: `docs/okf/product/index.md` allows a directory
  there only under a `GLOSSARY.md` §14 area name, and forbids a capability doc from restating mechanism, which is
  exactly what a module page does. Existing OKF concepts are linked from the pages, not moved or rewritten.
- **D-SS2** — Repo docs only; no in-product help panel yet (no route, no UI work).
- **D-SS3** — Internal only; nothing published outside the repo.
- **D-SS5** — L0 lives at `docs/okf/product/layer/product/brochure-solution-overview.md` (an audience tree, not a root `docs/*.md`); the earlier line that no `SOLUTION_SUITE.md` is created still holds.
- **D-SS4** — All five waves run at once as seven writers on disjoint files; the lead owns every shared file
  (indexes, `TECHNICAL_ARCHITECTURE.md`). Diagrams: shared helpers `tools/doc-diagrams/svglib.py`, one script
  per page.

## 8. Done looks like

Every module in `module-taxonomy.md` and every pack in `spaces/_templates` has a page on the template; every
page has one current SVG; guards green; `USER_GUIDE.md` and `TECHNICAL_ARCHITECTURE.md` link into the suite.
