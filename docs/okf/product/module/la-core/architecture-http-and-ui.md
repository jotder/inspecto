---
type: Concept
title: Link Analysis — HTTP surface and Investigation UI
description: Sections 6.1-6.9 and 6.14 of the Link Analysis architecture - the gate order of an /inv request, embed links, entity profile routes, third-party integration inbound and outbound, the analyst CSV upload, the masked-alias refusal on identity writes, category exclusion lists, Grow / Held / Exclude and the Investigation tab, and the Studio UI reorganization.
resource: la/
tags: [module, la-core, link-analysis, architecture, http, investigation, spa]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — HTTP surface and Investigation UI

Section numbers are those of [architecture-la-core.md](architecture-la-core.md), which keeps the route table (§6). Sections
§6.10-§6.13 and §6.15-§6.19 are in [architecture-data-onboarding.md](architecture-data-onboarding.md), §6.20 in
[architecture-watch-this-folder.md](architecture-watch-this-folder.md).

- **HTTP surface:** [§6.1 Gate order](architecture-http-and-ui-gates-embeds.md#61-gate-order-for-an-inv-request) · §6.2 Embed links · §6.3 Entity profile
  routes · §6.4 Third-party integration (inbound) · §6.5 Third-party integration (outbound) · §6.6 Analyst CSV upload ·
  §6.7 Masked-alias refusal on identity writes · §6.8 Category exclusion lists in Investigations.
- **Investigation UI:** §6.9 Grow, Held and Exclude on the graph, with §6.9.1 The Investigation tab as one Expand
  filter · §6.14 Studio UI reorganization.

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Gate order, embeds, entity profiles and inbound integration](architecture-http-and-ui-gates-embeds.md)** — 6.1 Gate order for an `/inv` request · 6.2 Embed links (LA-EMBED-VIEW-1: API S1-S3 built 2026-10-12; the SPA shell S4 and the frame headers S5 built 2026-10-13) · 6.3 Entity profile routes (as built 2026-10-15) · 6.4 Third-party integration (inbound) (as built 2026-10-17, LA-EDITIONS-1 phase 5)
2. **[Outbound integration, CSV upload, masking and exclusion lists](architecture-http-and-ui-integration-uploads.md)** — 6.5 Third-party integration (outbound) (LA-EDITIONS-1 phase 6, built 2026-10-17) · 6.6 Analyst CSV upload (LA-ANALYST-UPLOAD-1, as built 2026-10-18) · 6.7 Masked-alias refusal on identity writes (as built 2026-10-18) · 6.8 Category exclusion lists in Investigations (LA-GROW-EXCLUDE-1 phase 4, as built 2026-10-22)
3. **[Grow, Held and Exclude on the graph](architecture-http-and-ui-grow-exclude.md)** — 6.9 Grow, Held and Exclude on the graph (LA-GROW-EXCLUDE-1, SPA, as built 2026-10-22)
4. **[Studio UI reorganization](architecture-http-and-ui-studio-reorg.md)** — 6.14 Studio UI reorganization (LA-STUDIO-UI-REORG-1, as built 2026-10-28)
