---
type: Concept
title: Link analysis and geo routes - architecture
description: How la-api plugs into the host, the gate order of an /inv request, and how it composes la-core and la-storage.
resource: la/inspecto-la-api/
tags: [module, la-api, link-analysis, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis and geo routes - architecture

The full Link Analysis backend design is [la-core architecture](../la-core/architecture.md); this page is the la-api
slice of it. Layer overview: [layer/la architecture](../../layer/la/architecture.md).

## How it plugs in

- **ServiceLoader.** `la/inspecto-la-api/src/main/resources/META-INF/services/com.gamma.spi.http.RouteModule` lists 25
  route modules; the host `ControlApi` discovers them at boot. There is no LA main class.
- **Manifest.** `la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon`: `buildRole: implementation`,
  `offeringRole: optional`, features `geoLink` and `investigations`, requires `la-core` and `la-storage`. When the jar is
  absent the host answers its routes with the manifest's `absentMessage` (503 `CAPABILITY_UNAVAILABLE`).
- **OpenAPI.** `la/inspecto-la-api/src/main/resources/META-INF/inspecto/openapi.fragment.json` merges into the host
  contract.
- **Editions.** Staged from Basic (`offerings/basic.toon`). The explorer route modules (`InvRoutes`, `GeoRoutes`,
  `PatternRoutes`, `ValueMeasureRoutes`, `EntityIdentityRoutes`, `EntityProfileRoutes`) own `geoLink` only; the
  Investigation route modules also name `investigations`, which only `inspecto-ops` declares, so on Basic they answer 503
  "available in Standard and above" ([la-core architecture](../la-core/architecture.md) §8).

## Composition

```
 ControlApi ──RouteModule──> la-api routes ──> gates ──> handler ──> EntityMasking ──> audit
                                                         │
          ┌──────────────────────────────────────────────┼─────────────────────────────┐
          ▼                                              ▼                             ▼
  InvestigationStores.of(writeRoot)            IndexedRead gate                GraphRunServices
  (la-core: FS or la-store-pg)          index fresh? la-storage IndexReader    (la-core GraphRunService
                                         else flat DatasetProvider SQL          + la-storage RoutingGraphEngine)
```

- **Per-Space services.** `IndexBuildServices` and `GraphRunServices` keep one service per write root, created lazily
  and dropped after idling, so pools are per Space (a multi-Space host multiplies them; by design).
- **Data access** goes through la-core's `DatasetProvider` port, implemented by the bridge in
  [geo-link](../geo-link/index.md); la-api never reads engine classes.
- **Limits** come from `SpaceLimits` (per Space, cached on file content) and are passed down to la-core and la-storage.

## Gate order of an `/inv` request

Authenticate (401) → rate class (`linkAnalysis` bucket for expensive reads, 429) → host ABAC → module enabled
(`geoLink` in the Space's `modules.toon`, 404 `MODULE_DISABLED`) → capability (403) → `InvestigationRoutes.open`:
write root (503), safe id (422), header (404), membership role, Dataset visibility (404), Advanced PDP (404), deferred
role refusal (403) → handler → masking → audit. Non-members get 404-as-absence. Detail:
[la-core architecture](../la-core/architecture.md) §6.1.

## Key flows

- **Expand / neighbours / recursive paths.** `IndexedRead` checks `index.enabled`, a published index with a matching
  mapping, and staleness (`IndexStaleness`, `index.max_age_hours`); a miss returns a closed `Reason` and the flat path
  answers. Caps (`index.traversal_depth`, `index.frontier_cap`) overflowing mid-walk discard the partial index answer.
- **Grow preview (LA-GROW-EXCLUDE-1).** `POST .../expand/preview` runs `InvestigationRoutes.previewExpand`: the append's
  validation and read, then `evaluate` over the would-be entry with no write; `GrowPreview` shapes the counts (held hubs,
  per-list removed counts rounded unless the list is open) and masking uses the would-be log. `POST
  /inv/projection/neighbors/preview` (`InvRoutes.neighboursPreview`) is the explorer's flat equivalent for a set of ids.
  Detail: [la-core architecture](../la-core/architecture.md) (Grow preview).
- **Draft promote.** `DraftPromote` rebases to the main head (`DraftRebase`) and appends under the main lock, all or
  nothing, or files a four-eyes pending request when the Draft carries a sensitive expand.
- **Index build.** `IndexRoutes` (on request) and `ScheduledIndexBuild` (the `la.index.build` Job via geo-link) submit
  to la-storage's `IndexBuildService` with the plan's advice (`full` / `append` / `compact`).
- **Embed view.** Mint and revoke in `InvestigationEmbedRoutes`; the public GET in `PublicInvestigationRoutes` is
  authenticated by its token alone; every failure is one 404 ([la-core architecture](../la-core/architecture.md) §6.2).
- **Dataset read faults** are translated in one place, `DatasetReadFault` (409 for an empty store, 422 with paths
  stripped otherwise).

## Failure modes

| Symptom | Cause |
|---|---|
| 503 on every Investigation route | Basic edition, or the Investigation store is unusable (see [la-store-pg admin guide](../la-store-pg/admin-guide.md)) |
| 404 `MODULE_DISABLED` | `geoLink` not enabled in the Space |
| 429 on exploration POSTs | the `linkAnalysis` rate bucket |
| 503 on index build submit | build queue full (`index.queue`) |
| 503 on embed mint | `-Dla.embed.enabled` not `true` |
