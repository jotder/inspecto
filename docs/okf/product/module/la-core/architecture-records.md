---
type: Reference
title: Link Analysis — as-built reference and design records
description: The engineer-facing as-built reference for Link Analysis that the architecture pages do not carry - grounded limits and their consequences, Graph Run, the link index with its measurements, external references and the Dossier bundle, and the durable decisions of the separation (D-1, D-3, D-5, D-6) and closed-plan records.
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — as-built reference and design records

For engineers, support and power users. Each fact is dated where it was built. Where this page and the
[user guide](user-guide-la-core.md) differ on a control's name, the user guide (today's Studio) is right; the dated records
describe the behaviour, not the current layout. Module structure, op log, store port, index layout, Graph Run
budgets, route table, editions and the limits catalogue are in [architecture-la-core.md](architecture-la-core.md); gates, embed
links and the Investigation UI are in [architecture-http-and-ui.md](architecture-http-and-ui.md). This page keeps what
only it says: measurements with their numbers, decisions with their reasons, limits with their consequences.

What is where:

- [Product, code layout and the studio](architecture-records-product-layout.md#product-code-layout-and-the-studio) — naming, where the code lives, the algorithm library, V2 decisions
- [Pattern packs](architecture-records-product-layout.md#pattern-packs) · [AI-derived projection mappings](architecture-records-product-layout.md#ai-derived-projection-mappings) · [Dense-graph link labels](architecture-records-product-layout.md#dense-graph-link-labels)
- [Grounded limits and consequences](architecture-records-grounded-limits.md#grounded-limits-and-consequences) — limits, canvas, patterns, Value Measures, Cases, entity ids, evidence, Investigation surfaces
- [Graph Run](architecture-records-evidence-graph-run.md#graph-run) — server-side graph analysis
- [Studio UI and accessibility record](architecture-records-studio-index.md#studio-ui-and-accessibility-record)
- [Index](architecture-records-studio-index.md#index) · [Index design record](architecture-records-studio-index.md#index-design-record) · [Index scale measurements](architecture-records-studio-index.md#index-scale-measurements)
- [External references and the Dossier bundle](architecture-records-dossier-separation.md#external-references-and-the-dossier-bundle) — with members and Drafts
- [SPA separation record (D-5)](architecture-records-dossier-separation.md#spa-separation-record-d-5) · [Integration design record (D-6)](architecture-records-dossier-separation.md#integration-design-record-d-6) · [Platform-layer record (D-1)](architecture-records-dossier-separation.md#platform-layer-record-d-1)
- [Closed-plan record](architecture-records-closed-plans.md#closed-plan-record) — op vocabulary, threshold / snapshot / temporal / compare, timeline playback, decisions of record
- [InvestigationStore port](architecture-records-closed-plans.md#investigationstore-port) · [Consolidated as-built facts](architecture-records-closed-plans.md#consolidated-as-built-facts)

---

## Parts

This page is split by topic into 6 parts; each part lists the sections it holds.

1. **[Product, code layout, pattern packs and labels](architecture-records-product-layout.md)** — Product, code layout and the studio · Pattern packs · AI-derived projection mappings · Dense-graph link labels
2. **[Grounded limits and consequences](architecture-records-grounded-limits.md)** — Grounded limits and consequences — board rows: `LA-INVESTIGATION-OPS-DEFERRED-1`
3. **[Evidence, the op log and Graph Run](architecture-records-evidence-graph-run.md)** — Graph Run
4. **[Studio UI record and the link index](architecture-records-studio-index.md)** — Studio UI and accessibility record · Index · Index design record · Index scale measurements — board rows: `LA-A11Y-AUDIT-1`, `LA-INDEX-SCALE-MEASURE-1`
5. **[External references, the Dossier and the separation records](architecture-records-dossier-separation.md)** — External references and the Dossier bundle · SPA separation record (D-5) · Integration design record (D-6) · Platform-layer record (D-1)
6. **[Closed-plan record and consolidated as-built facts](architecture-records-closed-plans.md)** — Closed-plan record · InvestigationStore port · Consolidated as-built facts
