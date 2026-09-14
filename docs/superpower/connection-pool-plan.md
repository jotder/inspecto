# Connection pooling — scale-out phase A, the last unbuilt item

**Status:** IN FLIGHT (started 2026-09-14). Owning workstream: `enterprise-scale-out-plan.md` §5.1,
bullet *"Un-park the connection pool"*. Source of the original design:
`archived-documents/plans-archive/postgres-multi-user-plan.md` P1/P2 — **archive tier, provenance only,
and its store counts are stale** (see §2).

> ⚠ **Why this plan exists as a separate file.** The operator chose the full refactor in one go over a
> sliced landing, after being shown the size below. That is a layer-wide change to the persistence tier,
> so the design is externalised here rather than held in a session — the repo is the source of truth and
> mid-task compaction is this project's known failure mode.

---

## 1. What phase A actually has left

Grounded 2026-09-14 against code, not against the plan text. Three of §5.1's five bullets were **already
done** and the plan had not been updated:

| §5.1 bullet | Ground truth |
|---|---|
| Edition-level topology profile | ✅ `Topology` (`inspecto-util`), read by `ServiceStores`, `StoreHealth`, `SpacePartition` |
| `events.backend=db` | ✅ `DbEventStore` selected at `ServiceStores.java:275`. ⚠ Four javadoc sites still advertise `memory\|parquet` only |
| 12/12 stores tested | ✅ **15** `Db*` stores now covered by `PostgresStateStoreTest` — the "twelve" is stale |
| **Connection pool** | ❌ **UNBUILT** — this plan |
| `DuckLakeRegistrar` fatal (D10) | ✅ SHIPPED 2026-09-14, commit-time reading (see `enterprise-scale-out-plan.md` §5.4) |

⚠ **The phase-A verify gate is ALREADY MET** and must not be re-built: `ControlApiHealthDetailsTest`
`aDegradedStoreFailsTheBootWhenPartitioned` is a real end-to-end boot that throws on an unreachable
`jobs.backend` under `-Dinspecto.topology=partitioned`, with `aStoreThatWasAskedForAndCouldNotOpenIsDown`
as its falsification arm; `StoreHealthTest:89-113` pins both directions at the unit level. What phase A
still owes the gate is only the **pool saturation test**.

## 2. The seam, as it really is

- `JdbcDrivers.connect(url[, user, pass])` (`inspecto-util`) is a bare `DriverManager` factory — no
  caching, no pooling, nothing. It is the single factory every store goes through.
- **15 production stores** hold one `Connection` for their entire lifetime, ~208 `conn` references across
  ~108 `synchronized` members. ⛔ The archived plan says *eight* (corrected to ten in its §6); both counts
  are stale. The real list is in §5 below.
- One store opens a **short-lived scratch** connection (`ConsignmentProcessJobType.java:401`) — already
  the shape pooling is free for.

### 2.1 🔴 The two constraints that shape the whole design

1. **A pool buys nothing while a store holds its connection for life.** The storm being fixed is
   `15 stores × N pods`; a pool per store that is borrowed once and never returned is just the status quo
   with extra machinery. The win *requires* stores to borrow per operation and return.
2. **DuckDB is single-writer locked per file**, and `ServiceStores` gives every store its own file. A
   second concurrent connection to one DuckDB file fails to take the lock. So for `jdbc:duckdb:` the pool
   **must be pinned to size 1** and behaviour must stay byte-identical to today. Only Postgres URLs get a
   real pool. ⛔ Do not "simplify" by giving DuckDB the same sizing as Postgres.

### 2.2 🔴 `browseConnection()` is the blocker, and why

`BrowsableStore.browseConnection()` (`inspecto-util/.../BrowsableStore.java:42`) returns the store's live
connection **by reference**, and the interface's own default methods then run
`synchronized (browseMonitor())` around `browseConnection().createStatement()`. The mutual exclusion
works *only* because one Java monitor guards one JDBC connection object. A pool breaks that identity, so
the getter cannot survive: there is no single long-lived `Connection` for a pooled store to return.

✅ **What makes this cheap:** `DbBrowserRoutes` does **not** call `browseConnection()` — it calls the
`browseTable` / `browseQuery` default methods, which own all the connection use internally. So the change
is confined to the interface, its 7 implementors, and one test
(`DbConsignmentOutputStoreTest.java:241`, which borrows it deliberately).

## 3. Design

Add to `com.gamma.util`:

- `ConnectionSource` — `<T> T with(SqlFn<Connection,T> fn) throws SQLException`, plus `close()`.
  One implementation borrows from a Hikari pool; one wraps a single connection (DuckDB, and any
  caller that must keep today's exact behaviour).
- `JdbcDrivers.source(url, user, pass)` — returns a pooled source for `jdbc:postgresql:`, a
  single-connection source for `jdbc:duckdb:`. Sizing derives from the URL scheme, per the archived
  plan's F4. `connect(...)` stays for the scratch/test callers.
- `AbstractJdbcStore` holds a `ConnectionSource` and exposes `protected <T> T withConn(...)`.
- `BrowsableStore.browseConnection()` → a scoped accessor; `exec` and `browseEngine` rewritten onto it.

**Dependency:** `com.zaxxer:HikariCP:6.3.0`. Verified 2026-09-14, not assumed: the jar is 170 KB, it is
already in the offline `~/.m2`, and its only non-optional transitive dependency is `slf4j-api` (already
present) — `javassist` and `micrometer-core` are declared `<optional>true</optional>` and so are not
transitive. ⚠ `tools/dependencies.lock` must be regenerated (`node tools/check-dependencies.mjs --update`)
**in the same commit**; that pairing is the whole control.

## 4. Open questions to settle while building

- **Does the monitor survive?** With per-operation borrowing each thread has its own connection, so
  `synchronized` is no longer needed for *connection* safety — but it may be carrying logical atomicity
  for multi-statement methods. ⛔ Do not drop `synchronized` wholesale; for DuckDB (pool of 1) it is still
  required. Decide per store, and say so in the commit.
- **Dialect probe.** `JdbcDrivers.isPostgres(conn)` is probed once per store today. It must not become a
  per-borrow round trip — cache it on the source.
- **`close()` semantics.** Today `AbstractJdbcStore.close()` closes the one connection. It must become
  "close the source" (drain the pool), and must stay non-throwing.

## 5. The 15 stores to convert

`DbStatusStore` (24 conn / 5 sync) · `DbConsignmentOutputStore` (24/15) · `DbAcquisitionLedger` (23/12) ·
`DbJobRunStore` (18/14) · `DbTagAssignmentStore` (15/8) · `DbDeliveryReceiptStore` (15/11) ·
`DbRunLease` (13/4) · `DbInboxRegistry` (13/2) · `DbFileStageStore` (13/4) · `DbProvenanceStore` (10/6) ·
`DbDedupLedger` (10/7) · `DbObjectStore` (8/5) · `DbLinkStore` (8/5) · `DbEventStore` (8/7) ·
`DbNoteStore` (6/3).

Openers that must pass a source instead of a connection: `ServiceStores` (10 families),
`OpsEngineProvider` (4), `AcquisitionLedgers` (1) — the same 13 sites that already call
`StoreHealth.degraded`, plus `DbEventStore`'s two.

## 6. Exit criteria

- `mvn -o clean test -Pedition-enterprise` green, and `PostgresStateStoreTest` still 15/15 (16 tests).
- A **pool saturation test** — phase A's last owed gate.
- A test proving a `jdbc:duckdb:` source yields an effective pool of **1** (the file-lock constraint).
- Personal/Standard behaviour unchanged: same store lifecycle, same `close()` tolerance.
- `tools/dependencies.lock` regenerated in the same commit, its diff exactly one added line.
