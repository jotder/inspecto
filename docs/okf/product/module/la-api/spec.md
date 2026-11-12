---
type: Reference
title: Link analysis and geo routes - spec
description: Route modules, helper classes, invariants and failure handling of la-api.
resource: la/inspecto-la-api/
tags: [module, la-api, link-analysis, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis and geo routes - spec

Package `com.gamma.la.api` in `la/inspecto-la-api/`. Routes and their capabilities: [api-routes](api-routes.md).
Design: [architecture](architecture.md).

## Route modules

Registered in `la/inspecto-la-api/src/main/resources/META-INF/services/com.gamma.spi.http.RouteModule`.

| Area | Route modules |
|---|---|
| Explorer (feature `geoLink` only) | `GeoRoutes`, `InvRoutes`, `PatternRoutes`, `ValueMeasureRoutes`, `EntityIdentityRoutes`, `EntityProfileRoutes` |
| Investigations | `InvestigationRoutes`, `WorkingSetRoutes`, `InvestigationReferenceRoutes`, `InvestigationTemplateRoutes`, `InvestigationCoverageRoutes`, `InvestigationComparisonRoutes`, `InvestigationCaseRoutes`, `InvestigationMemberRoutes` |
| Evidence | `DossierRoutes`, `DossierBundleRoutes` |
| Drafts | `DraftRoutes` |
| Sharing | `InvestigationEmbedRoutes`, `PublicInvestigationRoutes` |
| Graph Run and index | `GraphRunRoutes`, `IndexRoutes`, `DatasetFilesRoutes` |
| Integration and upload | `InvestigationInboundRoutes`, `IntegrationRoutes`, `UploadRoutes` |

## Helper classes

| Concern | Classes |
|---|---|
| Masking | `EntityMasking`, `ExplorationMasking` |
| Draft orchestration | `DraftAdmission` (one live Draft per member, Space cap, heavy-op permits), `DraftRebase`, `DraftPromote` |
| Indexed reads | `IndexedRead` (the gate), `IndexedExpand`, `IndexedNeighbors`, `IndexedRecursivePaths`, `IndexedTemporal`, `IndexStaleness`, `IndexLinkStats`, `LinkStatsSource` |
| Per-Space services | `IndexBuildServices`, `GraphRunServices`, `ScheduledIndexBuild`, `InputFingerprintCache` |
| Dossier | `GraphDossierBuilder`, `DossierHtml`, `DossierDataFooter` |
| Embed links | `EmbedGrants`, `EmbedExpirySweep` |
| Entity profiles | `EntityProfiles`, `EntityTypeBindings`, `EntityKeySql`, `ColumnProfile` |
| Detection and measures | `StandingDetection`, `ValueMeasures`, `TemporalFindings`, `WindowComparison` |
| Other | `InvestigationMemberStore`, `CaseHistory`, `ConnectSeeds`, `ReferenceRows`, `DatasetLinkStats`, `DatasetReadFault`, `SpaceLimits`, `LaIntegration` |

## Invariants

- **Every route module overrides `featureIds()`** to name `geoLink` (and `investigations` for the Investigation
  surface); a new LA route module must too, or the module gate does not cover it (pinned by
  `ControlApiGeoLinkModuleGateTest`).
- **Every Investigation request opens through `InvestigationRoutes.open`**, so membership, Dataset visibility and the
  PDP are checked in one order; a non-member always gets 404.
- **Masking is applied after any cache** (Graph Run results, coverage), never baked into a cached value.
- **Index and flat answers never mix** in one response, except the documented hub-degree case.
- **`POST /inv/projection` and `/inv/projection/neighbors` answer the compact edge format** (PERF-P10-1, 2026-11-12),
  media type `application/vnd.inspecto.la-edges.v2+json`: `columns` named once (`source, target, kind, count`, then
  `total` when a Measure is read, then the `attrCols`), `attrCols`, and `rows` as arrays in that order; `masking`,
  `truncated`, `columnTypes` and (neighbours) `source` are unchanged. It REPLACED the per-row object shape; nothing serves
  the old one. A media type rather than a `v2` path because every path-keyed table (rate classes, capability manifest,
  OpenAPI, absent-module stubs, the projection cache key, the SPA's conditional-read key) stays put. `ApiContext.respondJson`
  keeps a handler's `…+json` type on a success and writes plain `application/json` on any error. The arrays are built
  straight from the statement row (`InvRoutes.edgeArrays`), the ends masked in place only when the read masks. The
  neighbours `list: true` page, `/inv/projection/multi` and the Investigation routes keep their object shapes. The SPA
  decodes once in `edge-wire.ts` (`decodeEdges`) before the conditional-read cache holds the body, so a 304 still returns
  the same decoded object. Parity: `EdgeWireParityTest`; route: `ControlApiEdgeWireFormatTest`.
- **Limits are read through `SpaceLimits`** per request; library modules receive values as parameters.
- **A Dataset read failure is translated only by `DatasetReadFault`**: 409 for a store with no files, 422 with the JDBC
  preamble and absolute paths stripped otherwise.
- **Embed tokens** are stored as a SHA-256 hash only, compared in constant time, and redacted from audit and logs.
- A new route also needs an `openapi.fragment.json` entry, a capability manifest entry and rate-class coverage (the
  `endpoint` skill).

## Configuration read

| Key | Where | Effect |
|---|---|---|
| `-Dla.embed.enabled` | JVM, `EmbedGrants` | `true` enables embed mint; otherwise 503 |
| `index.*`, `investigation.*`, `drafts.*`, `graph_run.*`, `embed.*` | Space `link-analysis.toon` / install `la-limits.toon` | see [admin-guide](admin-guide.md) |

## Tests

`la/inspecto-la-api/src/test/`; inventory in [tests-inventory](tests-inventory.md).
