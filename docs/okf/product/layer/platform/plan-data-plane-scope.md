---
type: Capability
title: Data plane (DAT) — Purpose, scope and requirements of record
description: Part 1 of 3 of Data plane (DAT): Purpose & scope; Requirements of record
tags: [dat, capability, dataset, query, parameters, result-set, bi-query, sql-guard, expression-guard, matrix, materialize, duckdb, postgres, operational-db, ducklake, warehouse, timezone]
timestamp: 2026-09-08T00:00:00Z
---

# Data plane (DAT) — Purpose, scope and requirements of record

Part 1 of 3 of [Data plane (DAT)](plan-data-plane.md) · next: [Specification](plan-data-plane-specification.md).

## 1. Purpose & scope

DAT is **where records live once they are data and how a question reaches them**: the Parquet lake a
pipeline writes, the relations the product lays over it, the one guarded path every SQL takes to DuckDB, the
Query and its parameters, the BI compiler, the materialized summary that becomes a Dataset again, and the
operational databases that hold the platform's own facts. Its defining property is that **business data is
never in a database** — it is files, read through DuckDB — and that **every read goes through one relation
builder and one guard stack**, so a Dataset means the same thing to a widget, a report, an alert, a
materialization and a share.

**In scope:** the `dataset` component and `DatasetRelation`; the store-layout contract and `SqlViews`; sink
views, Reference Datasets, output stores, superseded revisions and `retire_superseded`; the `query` kind, the
Query Library, `Parameters`, the Result Set, `POST /queries/{id}/run`; `POST /bi/query`, `MeasureCompiler`,
time grains; `SqlSandbox`, `SqlGuard`, `SqlOracle`, `ExpressionGuard`, calculated columns; `MaterializeTask`;
`DuckDbUtil`, memory limit and threads, the timezone rules; the `OperationalDb.Family` roster,
`-Dinspecto.db`, the Postgres path; the Data Browser; `DuckLakeRegistrar`, the warehouse runbook, object-storage
export; the Studio Datasets and Query Library panes.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| How Parquet is *produced* — `PartitionWriter`, `OutputFormat`, `sinks:`, the Consignment lifecycle | `PIP` (execution) — DAT owns what a written store *means* to a read, not the write |
| Widgets, Dashboards, KPIs, Reports as renderings | Studio (`BI`+`INV`) — DAT owns `/bi/query` as the engine they call |
| The Component store, ETags, history | `MET` — a Dataset and a Query are kinds in it |
| Space isolation, bundles, Exchange of Datasets | `SPC` |
| Who may author or run a query | `SEC` |
| Jobs and the scheduler that host `materialize` and `retire_superseded` | `PIP` (execution) / `OPS` — DAT owns the two tasks' semantics |
| Field types and the Schema registry | `MET` |

## 2. Requirements of record

Six requirements from `REQUIREMENTS.md` §3.4 (that section was stripped to an index on 2026-09-09 — this file is their only home now), all recorded shipped. **Three carry a client half that is
absent or a doc that says the opposite of the code.** ⚠ **`EDITIONS.md`'s feature × edition matrix is
authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `DAT-1` | **Dataset** umbrella (Table / Derived Table / View) over partitioned Parquet, described by Schemas, browsable in the Catalog | Must | ✅ SHIPPED | All |
| `DAT-2` | **Query** as a first-class Component (`sql \| structured`) + Query Library + `$`-**Parameters** + **Result Set** | Must | ✅ SHIPPED (R3 + W4) | All |
| `DAT-3` | Live query execution `POST /queries/{id}/run` on DuckDB with **server-side** parameter resolution | Must | ✅ SHIPPED, both halves. The Query Library's *Run saved* (`queries.component.ts` `runSaved` → `QueriesService`) calls the route; the editor's Run on an unsaved draft still previews through `/db/query` with `$`-parameters resolved **client-side** (§3.4). | All |
| `DAT-4` | **Matrix** materialization: persisted summary Derived Tables as managed assets | Should | ✅ SHIPPED 2026-07-08 (`task: materialize`); **surfaced 2026-09-14** — `POST /datasets/{id}/materialize` (202 + `runId`) plus one committed example job, and the task's five parameters declared at last (§3.6) | All |
| `DAT-5` | Row-level calculated columns on Datasets | Should | ✅ SHIPPED 2026-07-08; window functions 2026-07-24. Design of record: §6.2 of this spec (why a real SQL parser
was refused) and `okf/product/module/processor/spec-queries.md` §3.3–§3.5. *(Provenance:
`docs/archived-documents/plans-archive/calculated-columns-design.md`, not maintained.)* | All |
| `DAT-6` | Optional Postgres state store | Should | ✅ SHIPPED — **single operator only** (one shared `Connection` per store); the multi-user deployment is `OPS-03`: park lifted 2026-09-15, pool, schema-per-Space and the two-node soak built, ⏸ **POSTPONED 2026-10-23** (operator: the next release ships without Postgres; board row *Postgres multi-user*); the proving tests are **opt-in** and round-trip 15 of the 16 families, all but Alerts (§3.8) ⚠ **How the driver reaches a deployment (2026-09-09):** as the third-party `postgresql.jar` sidecar that `package.ps1 -Edition Standard|Advanced` stages — it is test-scoped in the reactor, so it is staged but **not** first-party (`editions.md` §3.3). 🔴 `REQUIREMENTS.md` added "NOT via `inspecto-connectors`, which no bundle ships"; the second half went stale on 2026-09-07 when `CONNECTORS-BUNDLE-1` made that sidecar ride **every** bundle (`from: 'all'` in `tools/bundle-modules.mjs`). The driver is simply a separate jar, not a connector. | S/E (`OPS-02`) |

**Corrections this table makes to its predecessor and the concept pages**, each verified against source:

- **`DAT-3`'s route has a caller, but only for saved queries.** `QueryRoutes` serves `POST /queries/{id}/run`
  with `Parameters.resolve` server-side, and the Query Library's *Run saved* action calls it. The editor's Run
  on an unsaved draft resolves `$today`/`$day(-7)`/declared defaults in `inspecto/query/parameters.ts` and
  posts the SQL to `/db/query` through `DatasetRowsService`, so a draft can preview green and still fail when
  a job runs it.
- **`DAT-4` vs the binding glossary.** `GLOSSARY.md` §6-B says Matrix is "not yet surfaced in the UI as of
  2026-07-20"; §13 records the `DERIVED_TABLE` → **Matrix** label DONE on 2026-08-04 and `MaterializeTask`
  shipped 2026-07-08. Corrected with this spec. Since 2026-09-14 the SPA triggers a materialization too
  (Materialize in the Dataset editor, §3.6).
- **The DuckDB `memory_limit` default is documented three ways and the code has none.** `duckdb.md` and the
  release-notes draft say the D11 pair `memory_limit=2GB` + `maxConcurrentRuns=4` is "on by default";
  `BACKLOG.md` GAP-4 says only the concurrency half is; `DuckDbUtil.memoryLimit` resolves config → served
  `scheduler.toon` → `-Dprocessing.duckdb.memory_limit` → **DuckDB's own default** with no numeric fallback,
  and no `scheduler.toon` ships. The board is right. Both pages corrected with this spec. ✅ **Closed
  2026-09-26 (GAP-4):** the chain now ends at `DuckDbUtil.defaultMemoryLimit()` (40 % RAM ÷ 4, ≥ 1 GiB).
- **The Postgres store count was stated as 6, 7, 10 and 11 across one page.** Measured: `OperationalDb.Family`
  is **sixteen** families (twelve until 2026-09-12, when D6 added `EVENTS` and B1 added `RUN_LEASE`;
  `INBOX_REGISTRY` followed on 2026-09-13);
  `PostgresStateStoreTest` (`features/inspecto-ops/src/test/java/com/gamma/service/`) round-trips
  **fifteen** store classes — note, tag assignment, job run, file stage, consignment output, status, provenance,
  object, link, and since 2026-09-12 event, delivery receipt, dedup ledger and the run lease, plus the
  acquisition ledger and the inbox registry — ✅ **so nothing is uncovered** (re-measured 2026-09-16; this
  bullet said *fourteen / thirteen / "leaving only the acquisition ledger uncovered"*, and all three were
  stale — see [`db-layer.md`](../../module/engine/spec-db-layer.md) §*Proving the JDBC stores on real
  PostgreSQL (DAT-6)* for how the probe that "confirmed" the gap was itself blind). ⛔ All of them SKIP without a configured server, so this is coverage, not evidence. The page
  is corrected to those numbers.
- **`status.backend` defaults to `db`**, not `file` (`OperationalDb.java`, flipped 2026-08-31); `db-layer.md`
  §2 still said `file` and its "exactly one store defaults on" paragraph predates two more defaults.
- **`structured` queries: "no server compiler" is half true.** The R6 sign-off says a server-side structured
  compiler would duplicate the builder "for no functional gain", and `/queries/{id}/run` answers `422` on a
  non-SQL body. But `POST /bi/query` **is** a server-side structured evaluator (`MeasureCompiler`), and the
  builder's full grammar (named-Measure SQL, `OR` filters) is exactly what it cannot compile. The two are
  reconciled in §3.5: two routes, one boundary.

**One standing note no status token can carry:** ⚠ **The "warehouse" is a runbook, not a feature.**
`integrations.md`'s second half describes installing `pg_duckdb` on a *customer's* PostgreSQL and running a
bundled `warehouse_setup.sql`; no `pg_duckdb` code exists in the repo and nothing tests it. `DuckLakeRegistrar`
**registers** already-written local Parquet paths in a DuckLake catalog — bytes never move. Object-storage
export is a **push Job** (`objectstore.export`, shipped 2026-09-24 as `EXPORT-1`) that copies committed files
out; the data tier itself stays local. Read every
"lakehouse" claim with those three facts.

## Changes

- 2026-11-01: DAT-3 and DAT-4 corrected — the cells said the SPA never called `POST /queries/{id}/run`, that `/db/query` had no caller, and that nothing in the SPA triggered a materialization.
