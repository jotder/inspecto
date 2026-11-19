---
type: Concept
title: Link Analysis — data onboarding
description: How data reaches Link Analysis and what a first-time user meets - Add data and the shared import route, help on every screen and the onboarding counters, the explorer defects and onboarding residuals, column roles, discovery in Basic, arrivals, arrival feeds and Dataset storage (sections 6.10 to 6.13 and 6.15 to 6.19 of the backend architecture; 6.20, the watched folder, is architecture-watch-this-folder.md).
resource: la/
tags: [module, link-analysis, onboarding, add-data, arrivals, dataset, architecture]
timestamp: 2026-11-12T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-20 last-edit
code: la/inspecto-la-core
---

# Link Analysis — data onboarding

How data reaches Link Analysis and what a first-time user meets: Add data and the shared import route, help on every
screen and the onboarding counters, the explorer defects and onboarding residuals, column roles, discovery in Basic,
arrivals, arrival feeds and Dataset storage; the watched folder (6.20) is [architecture-watch-this-folder.md](architecture-watch-this-folder.md).
Section numbers continue those of [architecture-la-core.md](architecture-la-core.md), from which this page was split on 2026-11-12.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Add data, help and onboarding residuals](architecture-data-onboarding-add-data-help.md)** — 6.10 Add data: the shared import route (LA-ONBOARD-1 phase 2, as built 2026-10-22) · 6.11 Help on every screen and the onboarding counters (LA-ONBOARD-1 phase 8, as built 2026-10-22; UC-15, A-9, A-10, OB-13) · 6.12 Explorer defects found by the acceptance drive, fixed (LA-ONBOARD-DEFECTS-1, as built 2026-10-23) · 6.13 Onboarding residuals (LA-ONBOARD-RESIDUALS-1, as built 2026-10-24)
2. **[Column roles and discovery in Basic](architecture-data-onboarding-roles-discovery.md)** — 6.15 Column roles (LA-COLUMN-ROLES-1, as built 2026-11-04/05) · 6.16 Discovery in Basic: presets, Discover, entity summary, query builder (LA-BASIC-DISCOVERY-1, as built 2026-11-03/05)
3. **[Arrivals, arrival feeds and Dataset storage](architecture-data-onboarding-arrivals-storage.md)** — 6.17 Arrivals: the ledger, Data status and what a later file does (LA-ARRIVAL-LEDGER-1 + LA-ARRIVAL-MODES-1, as built 2026-11-03/05) · 6.18 Arrival feeds: the inbox and Keep this up to date (LA-ARRIVAL-FEEDS-1, as built 2026-11-05) · 6.19 Dataset storage: compaction, the merge map and retention (LA-DATASET-COMPACT-1, as built 2026-11-05) · 6.20 Watch this folder: the 30 s inbox poll (BASIC-ONBOARD-1 P2, as built 2026-11-05)
