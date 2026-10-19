---
type: Concept
title: Entity Lists — architecture
description: How the Entity List module is built — the fact log, the fold, the routes, the sidecar projection, and its seams to Link Analysis, Risk Scores and four-eyes.
resource: features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon
tags: [entity-list, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Entity Lists — architecture

Every rule in detail: [spec](spec.md). Routes: [api-routes](api-routes.md). The Risk Score side of the
watch-list feed: [scoring architecture](../scoring/architecture-scoring-entity-lists.md).

## Where it sits

Module `entity-list` ([`module.toon`](../../../../../features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon))
is optional, bound at boot, and requires module `entity-store`. It provides feature `entityList`, the contracts
`GovernableKindProvider` and `WatchListFeed`, and the seven `/entity-lists` routes. When it is absent the host
answers those routes 503 and `/bootstrap` `features.entityList` is `false`.

Link Analysis (`inspecto-geo-link`) depends on it: identities, masking and the Investigation list-ops read and
append the same log. The reverse dependency does not exist.

## Data flow

```
POST /entity-lists...  -> EntityListRoutes --(policy? PendingChanges.hold -> 202)--+
                               |                                                  | approve -> replay
                               v                                                  v
                     EntityFactLog  audit/entity-facts/<seq>.json, SHA-256 chain <-+
                               | fold (EntityRegistry, as of atSeq)
             +-----------------+-------------------------+
             v                 v                         v
       GET / match      EntityListSidecar          Link Analysis excludeBy / seedBy
                        <dataRoot>/entity_list_<id>/entries.parquet
                        -> Dataset via POST .../register-dataset
```

- **The fact log is the truth.** One immutable JSON file per fact, published without replace; the whole chain is
  verified on every read ([design §4.2-4.3.2](../../../../archived-documents/plans-archive/link-analysis-entity-model-design.md)).
- **The sidecar is a projection.** It is rewritten atomically after every write; a failed sidecar write never
  undoes the fact, and the writer never creates a data root.
- **Expiry is judged at match time**, so an as-of fold stays deterministic.

## Seams

| Seam | Class | Effect |
|---|---|---|
| Four-eyes kind `entity-list` | `EntityListGovernableKinds` (`GovernableKindProvider`) | holds `members` and `retire` |
| Risk Score watch-list feed | `WatchListFeed` SPI, provided by `RiskWatchListFeed` through `ServiceLoader` | a `risk.score` run adds high entities to a `watch` list |
| Dataset registration | `DatasetRegistration.create` (core seam) | sidecar becomes a Dataset |
| Routes | `EntityListRoutes` (`RouteModule`) | HTTP surface |

Code: [`features/inspecto-entity-list/src/main/java/com/gamma/entitylist/`](../../../../../features/inspecto-entity-list/src/main/java/com/gamma/entitylist/).
