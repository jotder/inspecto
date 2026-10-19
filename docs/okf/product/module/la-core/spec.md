---
type: Reference
title: Link analysis core and ports - spec
description: Classes, ports, store selection, invariants and failure handling of la-core.
resource: la/inspecto-la-core/
tags: [module, la-core, link-analysis, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis core and ports - spec

Package `com.gamma.la.core` in `la/inspecto-la-core/`. Manifest
`la/inspecto-la-core/src/main/resources/META-INF/inspecto/module.toon`: `buildRole: platform`, `offeringRole:
optional`, `bindingTime: boot`, requires `graph`. No HTTP routes and no `META-INF/services` registrations of its own:
it defines the SPIs other modules register. Narrative design: [architecture](architecture.md).

## Classes by concern

| Concern | Classes |
|---|---|
| Op log and fold | `InvestigationEvaluator`, `InvestigationTime`, `InvestigationMembers`, `LinkEventTypes`, `LinkIds`, `LinkTemporalDetector`, `AdmiraltyGrade` |
| Storage port | `InvestigationStore` (port), `FsInvestigationStore` + `FsInvestigationLayout` (default), `InvestigationStores` (selector), `InvestigationStoreProvider` (SPI), `InvestigationVersionConflictException`, `SnapshotStore` (legacy) |
| Size limits | `WorkingSetSizeLimit` (per set), `InvestigationSetBudget` (per Investigation total) |
| Drafts | `DraftStore`, `DraftLifecycle`, `DraftCheckpoints`, `DraftIndex` |
| Graph Run | `GraphRunService`, `GraphEngine`, `InMemoryGraphEngine`, `GraphInput`, `WorkingSetGraphInput`, `GraphResult`, `GraphBudget`, `Algorithm`, `GraphRunException`, `InvalidGraphRequest`, `IndexCapExceeded`, `InputFingerprint`, `FingerprintBudget` |
| Patterns | `PatternQueryCompiler` (stage parsing; the matcher lives in `platform/inspecto-graph`) |
| Ports (+ holder) | `DatasetProvider`/`DatasetProviders`, `CasePort`/`CasePorts`, `CollectorCoveragePort`/`CollectorCoveragePorts`, `OutboundPort`/`OutboundPorts`, `UploadLandingPort`/`UploadLandingPorts` |

## Ports

Each port is an interface plus a static holder that resolves it through `SpiSlot` (ServiceLoader) and offers a
`forTest` seam, e.g. `la/inspecto-la-core/src/main/java/com/gamma/la/core/CasePorts.java`. The holder returns
`Optional.empty()` when no implementation is on the classpath; callers then answer "not installed". The production
implementations are all in `la/inspecto-geo-link/` (see the [geo-link hub](../geo-link/index.md)).

## Store selection: `InvestigationStores.of(writeRoot)`

Called once per request; no route names an implementation class
(`la/inspecto-la-core/src/main/java/com/gamma/la/core/InvestigationStores.java`).

| `-Dinvestigations.backend` | Result |
|---|---|
| `fs` (default) | `new FsInvestigationStore(writeRoot)` (holds no state) |
| `db` | the single `InvestigationStoreProvider` whose `backend()` is `db`, opened with the Space id, a `Connection` and the two size suppliers |
| anything else | 503 `CAPABILITY_UNAVAILABLE` |

Connection: `-Dinvestigations.db.url` / `.user` / `.password`, each falling back to `-Dinspecto.db.*`; the URL must start
`jdbc:postgresql:`; the password goes through `SecretResolver`, so a `${ENV:NAME}` reference works. The Space id is the
directory above `config` for a hosted Space, else `default`. Every unusable case (unknown value, no provider, a broken
or doubled registration, no or non-PostgreSQL URL, unreachable database) is **503, never a fall-back**. The outcome is
published as `live.investigations` on `/health/details`; `probeAtBoot` tries once at boot and WARNs, never stopping it.

## SPI: `InvestigationStoreProvider`

- `backend()` returns the `investigations.backend` spelling it serves (`db`).
- `open(spaceId, connection, maxSetBytes, maxInvestigationBytes)` returns the Space's store or throws `IOException`,
  which the selector turns into 503. The two `LongSupplier`s are read per write; the store must enforce both on every
  write path that stores a set.
- An implementation caches the pool and schema bootstrap (it is called per request) but **must not cache a failure**.
- `Connection.toString()` prints only the URL before `?`, never the password.
- Register in `META-INF/services/com.gamma.la.core.InvestigationStoreProvider`; exactly one may be present. The one
  implementation is [la-store-pg](../la-store-pg/spec.md).

## Invariants

- The log is append-only; a write carries `expectedVersion`, and a mismatch raises
  `InvestigationVersionConflictException`.
- Filesystem layout `<writeRoot>/audit/snapshots/investigations/<id>/`; the class Javadoc's shorter path is stale
  ([architecture](architecture.md) §3.2). Serialised by a JVM monitor plus an OS `FileChannel.lock` on `.store.lock`.
- Graph Run checks size before work and the deadline at checkpoints; overrun ends `BUDGET_EXCEEDED` with no result.
- Library code takes every limit as a parameter from its la-api caller ([architecture](architecture.md) §9).

## Tests

`la/inspecto-la-core/src/test/`; inventory in [tests-inventory](tests-inventory.md).
