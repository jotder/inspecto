---
type: Concept
title: Link Analysis — architecture
description: How Link Analysis is built - an overview (what it is, who uses it, configuration, interactions, limits), then the backend in sections 1-10 - the five la/ modules on entity-store and graph, the Investigation op log and store port (filesystem / Postgres), Drafts, masking (section 3.4), the link index, Graph Run, the /inv and /geo route table, host registration, editions, bounds and residuals.
resource: la/
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — architecture

- [Overview](architecture-la-core-shape-modules.md#overview) - what it is, who uses it, how it works, configuration, interactions, limits and gotchas.
- [§1 Shape in one paragraph](architecture-la-core-shape-modules.md#1-shape-in-one-paragraph) and its data flow.
- [§2 Modules and dependency direction](architecture-la-core-shape-modules.md#2-modules-and-dependency-direction).
- [§3 Domain core](architecture-la-core-shape-modules.md#3-domain-core-la-core): op log (§3.1), store port (§3.2), Drafts (§3.3), [masking (§3.4)](architecture-la-core-masking.md#34-masking), entity store (§3.5), Domain Profile and Industry packs (§3.6).
- [§4 Link index](architecture-la-core-index-http.md#4-link-index-la-storage) · [§5 Graph Run](architecture-la-core-index-http.md#5-graph-run-graph--la-core--la-storage).
- [§6 HTTP surface](architecture-la-core-index-http.md#6-http-surface-la-api-geo-link) - the route table; §6.1-§6.9 and §6.14 are in
  [architecture-http-and-ui.md](architecture-http-and-ui.md), §6.10-§6.13 and §6.15-§6.20 in
  [architecture-data-onboarding.md](architecture-data-onboarding.md).
- [§7 Host registration, Jobs and live detection](architecture-la-core-host-editions.md#7-host-registration-jobs-and-live-detection) ·
  [§8 Editions and packaging](architecture-la-core-host-editions.md#8-editions-and-packaging) · [§9 Bounds](architecture-la-core-host-editions.md#9-concurrency-and-resource-bounds-summary) ·
  [§10 Seams and residuals](architecture-la-core-host-editions.md#10-known-seams-and-residuals).

The product is **Inspecto LA (Link Analysis)** (`ED-1`); the capability, its routes, keys and modules keep the name
Link Analysis. Behaviour as the analyst sees it: [user guide](user-guide-la-core.md); as-built records of the SPA:
[architecture-records.md](architecture-records.md); open work: [BACKLOG](../../../../BACKLOG.md) and
[`superpower/link-analysis-roadmap.md`](../../../../superpower/link-analysis-roadmap.md). Class names are given without
paths; locate them with `codegraph_explore`. The source of truth for module metadata is each module's
`META-INF/inspecto/module.toon`, never this page. The backend sections were grounded in a code read on 2026-10-09
(tip `957c554af`) and carry dated as-built updates since.

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Shape, modules and the domain core](architecture-la-core-shape-modules.md)** — Overview · 1. Shape in one paragraph · 2. Modules and dependency direction · 3. Domain core (la-core)
2. **[Domain core — masking onward](architecture-la-core-masking.md)**
3. **[Domain core — discovery; link index, Graph Run and HTTP surface](architecture-la-core-index-http.md)** — 4. Link index (la-storage) · 5. Graph Run (graph + la-core + la-storage) · 6. HTTP surface (la-api, geo-link)
4. **[Host registration, editions, bounds and known seams](architecture-la-core-host-editions.md)** — 7. Host registration, Jobs and live detection · 8. Editions and packaging · 9. Concurrency and resource bounds (summary) · 10. Known seams and residuals
