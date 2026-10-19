---
type: Concept
title: Link analysis PostgreSQL store - architecture
description: How the PostgreSQL Investigation store is selected, how it plugs into la-core, and how it stays safe across pods.
resource: la/inspecto-la-store-pg/
tags: [module, la-store-pg, link-analysis, postgres, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis PostgreSQL store - architecture

Layer overview: [layer/la architecture](../../layer/la/architecture.md). Store comparison (filesystem versus PostgreSQL):
[la-core architecture](../la-core/architecture.md) §3.2. Class detail: [spec](spec.md).

## How it plugs in

```
 la-api route ──> InvestigationStores.of(writeRoot)            (la-core)
                     │ -Dinvestigations.backend
                     ├── fs (default) ──> FsInvestigationStore  (files under the Space write root)
                     └── db ──SpiSlot / ServiceLoader──> PgInvestigationStoreProvider ──> PgInvestigationStore
                                                          (this module)                    schema space_<id>
```

- The module only registers an `InvestigationStoreProvider`; la-core's `InvestigationStores` decides whether it is used.
  **The jar alone changes nothing**: the filesystem store stays in force until `-Dinvestigations.backend=db` and a
  `jdbc:postgresql:` URL are set.
- Edition placement: staged from Advanced (`offerings/advanced.toon`), so Advanced and Preview. Selecting `db` on a
  bundle without the jar answers 503 naming the missing module.
- Nothing above la-core names this module; routes, Drafts orchestration and masking are unchanged by the backend.

## Multi-pod safety

| Concern | How |
|---|---|
| Concurrent appends from several pods | row lock (`FOR NO KEY UPDATE`) and precondition checks inside one short transaction |
| Draft cap across pods | lock on the single `la_space` row |
| Half-finished promote | impossible: one transaction |
| Tenancy | one schema per Space, validated name |
| Tamper resistance | append-only triggers on sealed rows |
| Pool | one HikariCP pool per (URL, user), shared by every Space |

The two-JVM race harness proved the filesystem store had real cross-process races (fixed with an OS lock) and that
this store had none (`LA-INVESTIGATION-STORE-DESIGN-1`, S7).

## Availability posture

A selected but unusable database does not stop the boot (operator decision 2026-10-10). `probeAtBoot` tries the store
once and WARNs; `/health/details` shows `live.investigations` DOWN; every Investigation request of that Space answers 503
until the database is reachable again, with no restart needed for an outage. A wrong connection setting does need a
restart, because the connection comes from JVM properties.

## What it does not do

No migration from the filesystem store, no dual write, no import: a Space uses one backend.
