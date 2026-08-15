# Postgres multi-user backend — plan

> **Status:** PLAN ONLY, build not started (written 2026-07-27). BACKLOG §6 requires this document to
> exist before any code lands, because "add a pool" understates the work by a factor the investigation
> below makes concrete. **Direction was captured and deferred by the operator** — this plan does not
> re-open that call, it makes the build schedulable when it is taken.
>
> As-built persistence reference: [`okf/backend/engine/db-layer.md`](../okf/backend/engine/db-layer.md).
>
> ⚠ **Not the same work as `OperationalDb` (shipped 2026-08-14, `da92dbf3`).** That shipped a single
> `-Dinspecto.db=duckdb|postgres` selection so a Standard deployment names its database once instead of
> ten times — pure config consolidation, no pool, no `JdbcDrivers` change, Personal untouched. This plan
> is the separate, still-deferred question of concurrent operators sharing one connection per store
> (F1–F4 below). They compose: `OperationalDb.url()` is exactly the URL a future pool would sit behind.
> See `BACKLOG.md` **PG-1** for what is now open around `OperationalDb` (driver bundling, UI).

## 1. What this is for

Today a Space's operational data is served by one JDBC connection per store, opened at
`CollectorService` construction and closed at shutdown. That is correct and cheap for the Personal
edition (DuckDB file, one process, one writer). It does not survive **multiple concurrent operators on
one deployment**, which is the Standard/Enterprise shape: every control-plane read serializes on a
store-wide monitor, so concurrency is bounded at one in-flight statement per store regardless of how
much Postgres could take.

**Non-goal:** moving business data. Ingested rows stay Parquet/DuckDB — that is the right engine for
them and nothing here changes it. This plan touches only the **operational** class (§1 of the db-layer
doc).

## 2. What already exists (do not rebuild)

- The stores are **interface-seamed** with a `-D*.backend` toggle resolved in `ServiceStores`.
- JDBC is **dialect-aware** — `JdbcDrivers.isPostgres(conn)` picks the percentile spelling.
- Alerts/Incidents/Cases are already `ObjectStore` rows, not a bespoke store.
- `PostgresStateStoreTest` round-trips **7 of the 8** JDBC stores against embedded Postgres
  (`io.zonky.test:embedded-postgres`, test scope only). So "does Postgres work at all" is answered.

## 3. What the investigation found — four things that break under a pool

These are the reason this needed a plan rather than a ticket. Each is a place where correctness
currently rests on *there being exactly one connection*, which is precisely what a pool removes.

**F1 — ~~`DbAcquisitionLedger.record()` is atomic only by accident~~ → ✅ FIXED 2026-08-15 (P0 shipped).**
It did DELETE-then-INSERT as two statements inside a `synchronized` block with **no explicit
transaction**; both now run in one transaction with rollback on failure and autocommit restored in a
`finally`.

⚠ **Grounding corrected this finding's threat model.** The monitor plus the single connection genuinely
*do* make the pair indivisible against **concurrent callers** today, so the interleaving described here
was latent, not live. But a second non-atomicity was live all along and is independent of pooling: under
autocommit the DELETE committed *on its own*, so a crash, JVM kill or driver error between the two
statements erased the fingerprint permanently — and a file with no ledger row re-ingests as NEW on the
next cycle, duplicating its records. That is the window P0 actually closed. The pooling hazard remains
real for P1, and this fix is still its prerequisite.

Pinned by `AcquisitionLedgerTest.aFailedReplaceRollsBackAndKeepsThePriorFingerprint`, which injects a
failing INSERT through a proxy `Connection` and asserts the PRIOR fingerprint survives. ⚠ Falsified:
with the transaction removed the test fails with *"the fingerprint was DELETED and never re-INSERTed"*.

**F2 — every store exposes `Connection browseConnection()`** for the DB-browser seam
(`BrowsableStore` → `DbBrowserRoutes`). That hands out the store's long-lived connection object. A pool
has no such thing to hand out, so this seam has to change shape (borrow-and-return, or a
`withConnection(fn)` callback) before the field can go.

**F3 — the dialect probe is per-open, not per-statement.** `isPostgres(conn)` is called once when a
store opens and cached in the store. That is right, and a pool must preserve it: **do not re-probe per
borrowed connection** (a round trip per statement), and do not let the answer become per-connection
state.

**F4 — DuckDB is single-writer by file lock.** `ServiceStores` deliberately gives objects/links/notes/
tags **separate DuckDB files** for exactly this reason. Pooling is therefore a **Postgres-only**
capability: pool size must be pinned to 1 for a DuckDB URL, or the Personal edition starts failing on a
file lock. This is the constraint that decides the shape in §4.

## 4. Shape

**The pool goes behind `JdbcDrivers`, not inside each store.** `JdbcDrivers.connect(url[,user,pass])`
is already the single factory all eight stores call, so it is the one seam where a pool reaches
everything without eight edits. The store-side change is then mechanical: `private final Connection` →
a connection *source*.

Pool sizing is derived from the URL scheme, not configured twice: **DuckDB ⇒ max 1** (F4, so the
Personal default is byte-identical in behaviour), **Postgres ⇒ configurable, default modest.** An
operator cannot accidentally set a DuckDB pool to 8.

**Schema-per-space, NOT database-per-space.** A PG connection binds to one database, so db-per-space
fragments the pool into N pools of one and defeats the exercise. Space isolation becomes a schema in
the URL/search path.

**Reads stay direct.** Do not route operational reads through the postgres-duckdb plugin: wire-protocol
scans compete with OLTP on the same server. Read PG directly for OLTP; reserve the plugin (or a
materialize-to-Parquet CQRS split) for genuinely cross-engine analytical joins.

## 5. Phases

| # | Slice | Why this order |
|---|---|---|
| ~~**P0**~~ ✅ **SHIPPED 2026-08-15** | Wrap `DbAcquisitionLedger.record()` in an explicit transaction (F1) | Independently correct, ships alone, and is a **prerequisite** — pooling on top of it would introduce a data bug, not expose one |
| **P1** | Pool behind `JdbcDrivers`; scheme-derived sizing (F4); stores take a connection source | The core change. Personal must be provably unchanged |
| **P2** | Replace `browseConnection()` with a scoped accessor (F2) | Can't remove the single connection while a public method returns it |
| **P3** | Schema-per-space URL wiring | Only meaningful once a pool exists to be shared |
| **P4** | A `CaseStore` interface + PG impl | The one operational store with **no seam at all** today (JSONL ring). Last, because it is new surface rather than a migration |

Events stay on Parquet throughout — right fit, not an oversight.

## 6. Exit criteria

- Personal edition (DuckDB default) behaviour is **unchanged**, proven by the existing reactor suite
  plus an assertion that a DuckDB URL yields a pool of exactly 1.
- `PostgresStateStoreTest` extended to cover the whole store family — a real gap regardless of this plan.
  ⚠ **This criterion was mis-sized and is corrected here (grounded 2026-08-15): it is not "8/8 with
  `DbTagAssignmentStore` the one missing".** The test covers **7** stores today (`DbJobRunStore`,
  `DbObjectStore`, `DbLinkStore`, `DbNoteStore`, `DbProvenanceStore`, `DbAcquisitionLedger`,
  `DbStatusStore`), the family is **10**, and **three** are uncovered: `DbTagAssignmentStore`,
  `DbConsignmentOutputStore` and `DbFileStageStore`. Each slots into the existing per-store
  `open(url) → write → read back` pattern, so the work is 3× the estimate but still small per store.
  ⚠ `DbTagAssignmentStore` is absent even from the `okf/backend/engine/db-layer.md` §2 inventory table, so
  the doc this criterion leaned on could not have supported the "8" either.
- A concurrency test that fails against today's single-connection stores: N concurrent writers through
  one store, asserting throughput beyond one in-flight statement and no lost update.
- A test that `record()` is atomic under concurrent callers (F1), written **before** P1.

## 7. Open questions for the operator

1. **Pool library** — HikariCP (in-process, one more dependency, works air-gapped) vs PgBouncer
   (external, no dependency, another deployment component). The air-gap and jlink discipline argues
   Hikari; the ops-simplicity argument argues neither until there is a real deployment.
2. **Does any Standard/Enterprise deployment actually have concurrent operators yet?** If not, this
   stays deferred and P0 alone should still ship — it is a latent data bug either way.
