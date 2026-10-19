---
type: Plan
title: Link analysis and geo routes - plan of record
description: What la-api is required to do, what is built, what is left and what was refused.
resource: la/inspecto-la-api/
tags: [module, la-api, link-analysis, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis and geo routes - plan of record

`la-api` is the HTTP surface and orchestration of Link Analysis: the `/inv` and `/geo` routes, their gates, masking,
Draft orchestration, indexed reads and the per-Space build and Graph Run services. Route list: [api-routes](api-routes.md);
design: [architecture](architecture.md); layer: [layer/la architecture](../../layer/la/architecture.md).

## Required

- Contribute every Link Analysis route to the one host `ControlApi` as ServiceLoader `RouteModule`s, with no LA server
  and no engine class named ([la-separation-execution-plan](../../../../archived-documents/plans-archive/la-separation-execution-plan.md)).
- Enforce the request gate order (authenticate, rate, ABAC, module enabled, capability, Investigation membership, Dataset
  visibility, PDP) and mask entities on every output ([la-core architecture](../la-core/architecture.md) §6.1, §3.4).
- Orchestrate Drafts (admission, rebase, promote, four-eyes) over la-core storage
  ([la-separation-d7-design](../../../../archived-documents/plans-archive/la-separation-d7-design.md)).
- Serve reads from the link index when a fresh index exists, else from the flat Dataset relation, never mixing the two
  in one answer ([la-separation-d3-design](../../../../archived-documents/plans-archive/la-separation-d3-design.md)).
- Split the surface by edition: the explorer on Basic, the Investigation surface from Standard
  ([inspecto-la-editions-plan](../../../../archived-documents/plans-archive/inspecto-la-editions-plan.md)).

## Built

| Item | Record |
|---|---|
| 87 routes in 25 route modules (`META-INF/services/com.gamma.spi.http.RouteModule`) | [api-routes](api-routes.md) |
| Investigations, Dossier and Dossier bundle, references, templates, coverage, compare, Case link | [la-core architecture](../la-core/architecture.md) §6 |
| Drafts: `DraftAdmission`, `DraftRebase`, `DraftPromote` | D-7 design |
| Indexed reads (`IndexedRead`, `IndexedExpand`, `IndexedNeighbors`, `IndexedRecursivePaths`, `IndexedTemporal`) and index builds (`IndexRoutes`, `ScheduledIndexBuild`) | D-3 design; `LA-INDEX-MAX-AGE-1`, `LA-INDEXED-RESTRICTED-EXPAND-1` (closed) |
| Embed links (`EmbedGrants`, `InvestigationEmbedRoutes`, `PublicInvestigationRoutes`, `EmbedExpirySweep`) | [la-embed-view-design](../../../../archived-documents/plans-archive/la-embed-view-design.md); `LA-EMBED-VIEW-1` closed 2026-10-14 |
| Entity profile pages (`EntityProfileRoutes`, `EntityProfiles`) | [la-entity-profile-plan](../../../../archived-documents/plans-archive/la-entity-profile-plan.md) |
| Data trust: Dataset files, coverage (`DatasetFilesRoutes`) | [la-data-trust-plan](../../../../archived-documents/plans-archive/la-data-trust-plan.md); `LA-DATA-TRUST-1` closed |
| Every limit read through `SpaceLimits` | [la-configurable-limits-plan](../../../../archived-documents/plans-archive/la-configurable-limits-plan.md); `LA-CONFIG-LIMITS-1` |
| Inbound / outbound integration (`InvestigationInboundRoutes`, `IntegrationRoutes`, `LaIntegration`), analyst CSV upload (`UploadRoutes`) | `LA-EDITIONS-1`; [la-core architecture](../la-core/architecture.md) §6.4-§6.6 |

## Left

Open rows in [`docs/BACKLOG.md`](../../../../BACKLOG.md):

- `LA-EDITIONS-RESIDUALS-1` (P3): what LA-EDITIONS-1 left.
- `LA-ENTITY-PROFILE-RESIDUALS-1` (P3), `LA-I2-GAPS-RESIDUALS-1` (P3): residuals of the profile and i2-gap lanes.
- `LA-CONFIG-LIMITS-RESIDUALS-1` (P3): re-measure the doubled defaults on a server-class host.
- `LA-A11Y-AUDIT-1` (P3): person-only accessibility checks.
- `LA-DAILY-INGEST-1` (P2): daily feeds into Link Analysis; index-build follow-ups.

## Refused

- **A mixed answer** from index and flat reads, with one documented exception (the hub degree of a stale-served
  `expand`, [la-core architecture](../la-core/architecture.md) §4).
- **Distinguishing why an embed token failed** to the holder: every failure is the same 404 (embed design D1-D12).
- **An integration-settings SPA screen**, retired to §6 as demand-gated (`LA-EDITIONS-RESIDUALS-1`, 2026-10-18).
