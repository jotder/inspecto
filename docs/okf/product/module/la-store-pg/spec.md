---
type: Reference
title: Link analysis PostgreSQL store - spec
description: Classes, schema, locking, bootstrap and failure handling of the PostgreSQL Investigation store.
resource: la/inspecto-la-store-pg/
tags: [module, la-store-pg, link-analysis, postgres, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis PostgreSQL store - spec

Package `com.gamma.la.store.pg` in `la/inspecto-la-store-pg/`. Manifest
`la/inspecto-la-store-pg/src/main/resources/META-INF/inspecto/module.toon`: `buildRole: implementation`,
`offeringRole: provider`, provides contract `InvestigationStoreProvider`, requires `la-core`. Dependencies: la-core,
Jackson, the PostgreSQL JDBC driver. The SPI it implements is specified in [la-core spec](../la-core/spec.md).

## Classes

| Class | Role |
|---|---|
| `PgInvestigationStoreProvider` | registered in `la/inspecto-la-store-pg/src/main/resources/META-INF/services/com.gamma.la.core.InvestigationStoreProvider`; `backend()` = `db`; caches one pool per (URL, user) and one store per (URL, user, schema) |
| `PgInvestigationStore` | the `InvestigationStore` implementation; every port method is one transaction |

## Schema (one per Space)

Schema name `space_<id with '-' as '_'>`, validated against `[a-z_][a-z0-9_]{0,62}` and never concatenated from user
input; the same naming as the platform's other stores. Tables, created `IF NOT EXISTS`:

| Table | Holds |
|---|---|
| `la_space` | one row; the lock for the Space-wide Draft cap |
| `la_investigation` | id, header, mask key, Case link, `set_bytes` counter |
| `la_log`, `la_set` | sealed log lines and sets, per Investigation and Draft (`draft = ''` for the main log) |
| `la_member`, `la_reference`, `la_alert_binding`, `la_pending`, `la_template` | members, references, Alert Rule binding, four-eyes requests, templates |
| `la_draft` | Draft rows (state, version) |
| `la_embed_grant` | embed grants (opaque JSON, token hash only) |

Bootstrap runs once per Space under `pg_advisory_xact_lock(hashtext('inspecto-la-store:<schema>'))`. An `la_append_only`
trigger function refuses update and delete of sealed rows; Draft rows (`draft <> ''`) may be deleted (close, rebase), so
those triggers carry a `WHEN`. A trigger rather than a `REVOKE`, because the application connects as the table owner.

## Invariants

- **Locking:** `SELECT ... FOR NO KEY UPDATE` on the Investigation row (or the Draft row for a Draft's own log) inside a
  short transaction; preconditions (`expectedVersion`, prefix hashes) are checked inside it. `FOR NO KEY UPDATE` avoids a
  deadlock with Draft-log inserts that take `FOR KEY SHARE` through their foreign key. Lock order: Space row,
  Investigation row, Draft row.
- **Bytes:** lines, sets, headers and markers are `text`, so sealed hashes survive byte for byte.
- **Draft cap:** checked and the seat taken under the `la_space` row lock, so N pods cannot admit N × cap Drafts.
- **Promote** is one transaction; `recoverDrafts` has nothing to recover.
- **Sizes:** the per-set limit and per-Investigation budget suppliers are read per write; over either is 413 before any
  write.
- **Failure:** an unreachable database is an `IOException` from `open` (the selector answers 503); nothing is cached on
  failure, so the next request retries. The pool is HikariCP via `JdbcDrivers.source`, pool name `la-investigations`,
  sized by `-Ddb.pool.size`.

## Tests

`la/inspecto-la-store-pg/src/test/java/com/gamma/la/core/PgInvestigationStoreContractTest.java` (the shared store
contract), `PgEmbedGrantStoreTest`, `PgInvestigationStoreTwoJvmRaceTest`. They need a PostgreSQL URL in
`-Dinspecto.test.pg.url` or `INSPECTO_TEST_PG_URL` and skip without one. Inventory: [tests-inventory](tests-inventory.md).
