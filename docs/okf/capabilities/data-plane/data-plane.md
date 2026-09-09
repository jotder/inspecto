---
type: Capability
title: Data plane (DAT)
description: Datasets over partitioned Parquet and the relations they resolve to; Query as a Component with $-Parameters and a Result Set; the guarded SQL execution stack (SqlSandbox, SqlGuard, ExpressionGuard); /bi/query and time grains; Matrix materialization and superseded-revision retention; the DuckDB runtime and its timezone rules; the operational-store roster and Postgres; DuckLake, the warehouse runbook and object-storage export. The requirement of record for the DAT area, its specification, its decisions, and what was refused.
resource: inspecto-engine/src/main/java/com/gamma/query, inspecto-sql/src/main/java/com/gamma/sql, inspecto/src/main/java/com/gamma/service/OperationalDb.java
tags: [dat, capability, dataset, query, parameters, result-set, bi-query, sql-guard, expression-guard, matrix, materialize, duckdb, postgres, operational-db, ducklake, warehouse, timezone]
timestamp: 2026-09-08T00:00:00Z
---

# Data plane — capability spec (`DAT`)

> **What this page is.** The single entry point for the DAT capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Seventh of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../superpower/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §6-B, §7 — binding). A **Dataset** is the umbrella for any queryable
> relation the BI layer can bind to — **Table** | **Derived Table** | **Reference Dataset** | **View**; ⛔
> never *Data Store* (that is the physical **Store**). A **Matrix** is a Derived Table's user-facing label, <!-- vocab-allow: states the Data Store ban it enforces -->
> never a new type; *Cube* stays a verb. A **Query** is a first-class Component `{type: sql | structured, <!-- vocab-allow: states the Cube-noun ban it enforces -->
> source Dataset, text | model, Parameters}`; a **Parameter** is a `$`-namespace runtime binding — ⛔ never
> conflated with a `:fieldValue` rule-template placeholder or a `${ENV:KEY}` secret; a **Result Set** is the
> semantic description of a Query's output. A **Measure** is a BI aggregation — ⛔ never *Metric*. A
> **Watermark** unqualified means event-time completeness; the incremental read cursor is an *incremental
> cursor*.

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
| `DAT-3` | Live query execution `POST /queries/{id}/run` on DuckDB with **server-side** parameter resolution | Must | ✅ **server SHIPPED — no client consumer.** The SPA never calls the route; the Query Library previews through `/db/query` after resolving `$`-parameters **client-side** (§3.4) | All |
| `DAT-4` | **Matrix** materialization: persisted summary Derived Tables as managed assets | Should | ✅ SHIPPED 2026-07-08 (`task: materialize`) — ⚠ the glossary still says "not yet surfaced" (§2 corrections); no UI action triggers it | All |
| `DAT-5` | Row-level calculated columns on Datasets | Should | ✅ SHIPPED 2026-07-08; window functions 2026-07-24 Design of record: `docs/archived-documents/plans-archive/calculated-columns-design.md`. | All |
| `DAT-6` | Optional Postgres state store | Should | ✅ SHIPPED — **single operator only** (one shared `Connection` per store); the multi-user deployment is `OPS-03`, **PARKED**; the proving test is **opt-in** and covers 9 of 12 families (§3.8) ⚠ **How the driver reaches a deployment (2026-09-09):** as the third-party `postgresql.jar` sidecar that `package.ps1 -Edition Standard|Enterprise` stages — it is test-scoped in the reactor, so it is staged but **not** first-party (`editions.md` §3.3). 🔴 `REQUIREMENTS.md` added "NOT via `inspecto-connectors`, which no bundle ships"; the second half went stale on 2026-09-07 when `CONNECTORS-BUNDLE-1` made that sidecar ride **every** bundle (`from: 'all'` in `tools/bundle-modules.mjs`). The driver is simply a separate jar, not a connector. | S/E (`OPS-02`) |

**Corrections this table makes to its predecessor and the concept pages**, each verified against source:

- **`DAT-3`'s route has no caller in the product.** `QueryRoutes` (`inspecto/src/main/java/com/gamma/control/QueryRoutes.java:43`)
  serves `POST /queries/{id}/run` with `Parameters.resolve` server-side; `inspecto-ui/src/app` contains no
  request to it. The Query Library's Run button resolves `$today`/`$day(-7)`/declared defaults in
  `inspecto/query/parameters.ts` and posts the SQL to `/db/query` through `DatasetRowsService`. Server-side
  resolution — `$current_user`, `$role` — therefore never reaches an operator's query. Same shape as `API`'s
  `If-Match` and cursor findings.
- **`DAT-4` vs the binding glossary.** `GLOSSARY.md` §6-B says Matrix is "not yet surfaced in the UI as of
  2026-07-20"; §13 records the `DERIVED_TABLE` → **Matrix** label DONE on 2026-08-04 and `MaterializeTask`
  shipped 2026-07-08. Corrected with this spec. What remains true: **nothing in the SPA triggers a
  materialization**; `materialized` is a `DatasetKind` value modelled "for completeness".
- **The DuckDB `memory_limit` default is documented three ways and the code has none.** `duckdb.md` and the
  release-notes draft say the D11 pair `memory_limit=2GB` + `maxConcurrentRuns=4` is "on by default";
  `BACKLOG.md` GAP-4 says only the concurrency half is; `DuckDbUtil.memoryLimit` resolves config → served
  `scheduler.toon` → `-Dprocessing.duckdb.memory_limit` → **DuckDB's own default** with no numeric fallback,
  and no `scheduler.toon` ships. The board is right. Both pages corrected with this spec.
- **The Postgres store count was stated as 6, 7, 10 and 11 across one page.** Measured: `OperationalDb.Family`
  is **twelve** families; `PostgresStateStoreTest` (`inspecto-ops/src/test/java/com/gamma/service/`) round-trips
  **nine** store classes — note, tag assignment, job run, file stage, consignment output, status, provenance,
  object, link — and does not cover the dedup ledger, the acquisition ledger or delivery receipts. The page
  is corrected to those numbers.
- **`status.backend` defaults to `db`**, not `file` (`OperationalDb.java:132`, flipped 2026-08-31); `db-layer.md`
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
export is **intent** (`object-storage-export.md` says so in its own frontmatter; `EXPORT-1`). Read every
"lakehouse" claim with those three facts.

## 3. Specification

### 3.1 Three data classes, one of them a database

Inspecto has **no ORM and no single database** (`db-layer.md` §1): **business data** is Hive-partitioned
Parquet (or CSV) under `<dataDir>/<store>/**`, read through DuckDB `read_parquet` / `read_csv` — *not a
database*; **system/config data** is TOON under `registry/` and the pipeline trees; **operational data** —
the platform's own facts — is the only SQL-shaped class, per-family DuckDB files by default, Postgres by
selection (§3.8). Everything below either lays a relation over the first class or persists the third.

### 3.2 Datasets and the relation they resolve to

A `dataset` is a `ComponentStore` component (no `ConfigSpecs.dataset()` exists — written as a raw map, by
convention) whose SPA shape is `DatasetConfig {kind: physical | virtual | materialized, sourceName, query?,
physicalRef?, columns[{name, type, role}], measures[], calculated[], viz?}`. **`DatasetRelation`**
(`inspecto-engine/src/main/java/com/gamma/query/DatasetRelation.java`) turns it into the **trusted relation**
SQL every consumer runs against: a `view` Dataset renders its `sink.view` definition (`ViewStore`,
`@PublicApi`); a `physicalRef` Dataset reads the store through **`SqlViews`** — the one owner of read
options (`union_by_name=true`; `hive_partitioning` deliberately **off**, since turning it on would surface
partition segments as columns on every existing Dataset); `calculated[]` wraps `SELECT *, (expr) AS "name"
FROM (<base>) AS __base` after `ExpressionGuard.check` (`:161`); `temporalColumn()` picks the `role:
temporal` column, degrading on absence and refusing on ambiguity. A bad name or expression is **`422` at every
route, fail-closed**, because the wrap happens at relation-build time and every consumer — `/bi/query`,
`/queries/{id}/run`, reports, measure alerts, materialization, shares — inherits it.

**The store-layout contract** (2026-07-18, closing a UAT +72 % double-count): a persistent store is a
**top-level directory** under the Space data root (`requireTopLevelSinks` fails a nested sink before any byte
is written); a **pipeline-shaped store** (one with a `database/` subtree) is read at its mapped output
(`SqlViews.storeReadRoot`), so `backup/`, `quarantine/` and stray trees never leak into a read; a flat
snapshot store reads unchanged; an explicit deeper ref is honoured; `shared/…` Exchange refs are exempt.
`sourceName` is **never defaulted** (a source-less Dataset once read empty everywhere, indistinguishable from
an empty store). A **Reference Dataset** is produced by a pipeline (`produces: reference`, node `ref:<pipeline>`)
and bound by name; its load semantics (`replace | upsert | scd2`, `key[]`, `refresh_seconds`) are authorable
config, but **the SCD2 engine is unbuilt** (`transform.dim.scd2` is `Status.PLANNED`).

**Revisions and retention.** A full recompute writes a **new revision** and flips catalog state only; an
in-flight read finishes on the revision it started with. Bytes leave later through the
**`retire_superseded`** maintenance task (`RetireSupersededTask`): deletes files the Consignment catalog marks
unreadable and older than a **required** `retention_days`, **keeps the catalog rows**, and never touches a file
that is still present. ⚠ **Nothing retains by default** — an unconfigured install keeps every superseded copy
forever, and since 2026-08-29 `JobService` **warns** when no `retire_superseded` job exists. The
`consignment_outputs` registry defaults **on** (the one exception, 2026-08-10) because `ReprocessCommand`'s
refusal to re-ingest rows a compaction merged away is decidable only from its `COMPACTED_AWAY` rows; a file
with no row is *unknown, never absent*.

### 3.3 The guarded execution stack

Every SQL reaches DuckDB through **`QueryExecutor`** (`inspecto-engine/src/main/java/com/gamma/query/QueryExecutor.java`)
inside a **`SqlSandbox`** (`inspecto-sql/src/main/java/com/gamma/sql/SqlSandbox.java`): `open()` sets
`autoinstall_known_extensions=false`, `autoload_known_extensions=false`, `memory_limit`, `threads`;
`seal()` adds `enable_external_access=false` + `lock_configuration=true`. **Only `SqlOracle` seals**;
`QueryExecutor` and the Data Browser run **unsealed by design**, because the trusted relation legitimately
reads Parquet. Two guards, two grains:

- **`SqlGuard`** (`inspecto-sql/src/main/java/com/gamma/sql/SqlGuard.java`) validates a **whole statement**: a single read-only
  `SELECT` / `WITH`; blocked functions (`read_*`, `write_*`, `*_scan`, `copy`, `getenv`, `glob`, `system`,
  `shell`, …); blocked keywords (DDL / DML / `attach` / `install` / `load` / `pragma` / `set`, …);
  string-literal-aware comment stripping; an unterminated comment is a rejection.
- **`ExpressionGuard`** (`inspecto-engine/src/main/java/com/gamma/query/ExpressionGuard.java`) validates a **fragment** — the
  calculated-column expression spliced inside the trusted relation — with three cooperating rules: a **closed
  token alphabet** (plain identifiers, numeric and single-quoted literals, arithmetic and comparison
  operators, parens, commas; no semicolons, double quotes, comments, backslashes; 500 chars), a **keyword
  deny-set** for bare identifiers (kills scalar-subquery smuggling), and a **function-call whitelist**
  (`read_parquet(`, `glob(`, UDFs rejected by name; `cast` types themselves whitelisted). Since 2026-07-24 a
  **window function** is legal **only** when immediately followed by a valid `OVER (…)` (`PARTITION BY` /
  `ORDER BY` over columns and scalar functions; explicit `ROWS|RANGE` frames unsupported) — row-safe because
  the wrap is a projection. Rationale: DuckDB has no offline-safe Java parser, so the three-rule model is a
  *provably closed* surface — **grow the whitelist, never parse harder**. The vocabulary is pinned across
  languages by `inspecto-ui/src/app/inspecto/contracts/expression-guard.contract.json`
  (`ExpressionGuardContractTest` ↔ `calculated-column-guard.ts`, which mirrors the rules for instant
  feedback and is **not authoritative**).

`QueryExecutor.wrap` adds the server-built projection, sort and `LIMIT n+1 OFFSET` so truncation is detected,
and supports positional binds for Rule Templates. `SqlOracle` and `SqlSandboxPolicy` are `@PublicApi`.

### 3.4 Query as a Component

A `query` is a writable kind (`{type: sql | structured, source Dataset, text | model, parameters[]}`),
authored in the Studio **Query Library** (`/studio/queries`), which since 2026-07-19 authors **both** types —
`structured` through the shared `<inspecto-query-panel>` builder (also used by Decision Rules, Alert Rules
and Expectations) — and declares `$`-parameters for **SQL only** (a structured query has no text to scan;
deliberate cut).

**`POST /queries/{id}/run`** (`QueryRoutes.java:43`): `422` unless `type: sql`; **`Parameters.resolve`**
(`inspecto-engine/src/main/java/com/gamma/query/Parameters.java`) substitutes the built-ins `$today`, `$now`, `$day(n)`,
`$current_user`, `$role` and user-declared `$name` (+ offset), leaves `:name` and `${ENV:KEY}` untouched, and
**leaves an unknown token verbatim** (the save-time rejection of unknown tokens is the *job* parameter
contract, a different namespace — `PIP`); then `SqlGuard.check`; dataset via `DatasetRelation`; **offset
pagination** with `DEFAULT_LIMIT=500`, `MAX_LIMIT=10 000`; returns rows plus the **Result Set** descriptor
(columns with type + analytic role dimension / measure / temporal). ⚠ **The SPA does not call it** (§2). The
three parameter namespaces are deliberately distinct and each resolver ignores the other two.

### 3.5 `POST /bi/query` — the spec-compiled path, and where the structured boundary really is

`BiRoutes` (`inspecto/src/main/java/com/gamma/control/BiRoutes.java`): `GET /bi/datasets`, **`POST /bi/query`**,
`GET|POST /bi/templates` (+ apply, capability-gated). `/bi/query` takes a **spec** — measures, dimensions,
filters, an optional `grains` map — and **`MeasureCompiler`** (`inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java`)
compiles it over the Dataset's trusted relation: `AGGS` = `count · countDistinct · sum · avg · min · max`,
`GRAINS` = `day · week · month`. **Time grain travels on the wire** (2026-08-14): a grain naming a column that is
not grouped is a **`422`, not a silent no-op**; the bucket is emitted as `STRFTIME(DATE_TRUNC(...))` **and
repeated in the `GROUP BY`**; ⚠ **the bucket is TEXT** (`%Y-%m-%d`, `%Y-%m`), never a timestamp, because those
are the exact keys the SPA's offline `bucketValue` produced — one widget must label categories identically
live and offline; `MeasureCompilerGrainExecutionTest` **runs** the SQL rather than string-comparing it. The
`AGGS` vocabulary is pinned by `measure-grammar.contract.json` — ⚠ the SPA's `measure-grammar.ts` consumer is
the **pipeline `transform.summarize` editor**, not `/bi/query`; the two share a grammar, not a code path.

**The structured boundary, stated once.** `/queries/{id}/run` answers `422` on `type: structured` by product
sign-off (R6, 2026-07-22): the builder emits SQL, so re-compiling structured bodies there duplicates the
client. `/bi/query` **is** a server-side structured evaluator for the *spec* grammar the widget explorer
speaks, and it **fails honestly** on what it cannot map (named-Measure SQL, `OR` filters). Neither route lies;
`queries.md` described only the first. Widgets preview through `DatasetResultService` → `/bi/query`.

### 3.6 Matrix materialization

**`MaterializeTask`** (`inspecto-engine/src/main/java/com/gamma/job/MaterializeTask.java`, `task: materialize`
on the maintenance runner): params `dataset`, `target`, `measures`, `group_by`, `limit` (default 1 000 000);
compiles a spec-based `SELECT` through `MeasureCompiler` or takes a raw snapshot over the source Dataset's
trusted relation; `COPY … TO … (FORMAT PARQUET)` into `.tmp`; **hide-old to `.stale` → `ATOMIC_MOVE` reveal →
delete stale** (a crash leaves only glob-invisible leftovers, self-cleaning); then **registers or refreshes a
`dataset` component** stamped `materialized: {from, at, rows}` and emits `DatasetWriteSignal`. So a Matrix is
queryable everywhere a Dataset is — **zero net-new read paths**. The `materialize` → `summarize` rename was
**dropped as done-by-absence** (D-7, 2026-09-06): `summarize` shipped independently as a Step, `MaterializeTask`
stays a Job, the two share only the measure grammar. ⚠ No committed job schedules a materialization and no SPA
action triggers one.

### 3.7 The DuckDB runtime

`DuckDbUtil` (`inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java`): driver load, `tempDbFile`,
`applyDuckDbSettings` (`temp_directory`, `memory_limit`, `max_temp_directory_size`), **`memoryLimit()`
precedence = per-config → the served `scheduler.toon` value → `-Dprocessing.duckdb.memory_limit` → DuckDB's
own ≈80 %-of-RAM default** (no numeric fallback in code — §2), `effectiveWorkerThreads` (cores ÷ batch
concurrency, against oversubscription), `buildCopyOptions` (PARQUET → SNAPPY). The D11 pair (2026-08-26):
`maxConcurrentRuns` **defaults to 4** (`JobService.java:184`); `memory_limit` is **served, not mirrored** —
`GET|PUT /system/scheduler` owns it with `file > property > default` provenance and a portable, anchored
grammar (`duckdbMemoryLimitPattern`, DuckDB's `80%` form accepted). **Measured, not reasoned** (2026-07-27):
peak memory does **not** scale with input on the ingest path, and blocking operators **hard-fail instead of
spilling** below the cap — an aggressive cap turns working jobs into failing ones. Preview and dry-run
connections stay **uncapped** by decision. The JVM needs the DuckDB native-access flag (`build-verify`).

**Timezones — three rules that must not be merged.** (1) The session `TimeZone` is the **host** zone, not UTC
(probed 2026-08-15; ICU is installed and loaded): the SQL `now()::TIMESTAMP` writer and the Java
`ZoneId.systemDefault()` reader are a matched pair — "fixing" the reader to UTC would *create* the skew;
DuckDB is **blind to `-Dops.timezone`** and nothing issues `SET TimeZone`. (2) **`-Dops.timezone`**
(`OperationsZone`) governs cron firing and `$today` / `$yesterday` / `$day(-1)`; `meta.domain.timezone` is
display-only because several `*_meta.toon` merge last-non-blank-wins. (3) The **source zone** for temporal
data (2026-08-29): precedence `raw.fields[].timezone_column > raw.fields[].timezone > parsing.source_timezone
> none`, compiled as `timezone('UTC', timezone(Z, <naive-parse>))` to naive UTC; **`TIMESTAMPTZ` with no zone
source is refused at config load**, `%z`/`%Z` formats refused, `FILENAME_DATE` and `DATE` zone-exempt (a date
has no instant to shift). ⛔ The two zone checks (`SourceZoneGrammar` vs `ConfigSpecs.meta()`) stay separate:
one value reaches DuckDB, the other the JVM.

**Aggregate state cannot be persisted and restored** (§7.5 probe): `approx_quantile` cannot `EXPORT_STATE`,
`approx_count_distinct` exports but has no `BLOB → AGGREGATE_STATE` cast back; the fixed-bucket histogram is the
decided representation for non-additive measures. ⛔ Do not re-litigate from DuckDB's docs.

### 3.8 The operational stores and Postgres

**`OperationalDb.Family`** (`inspecto/src/main/java/com/gamma/service/OperationalDb.java:77-135`) is **the
roster — twelve families**, each with its own `-D<family>.backend` toggle and default:

| Family | Default | Family | Default |
|---|---|---|---|
| `JOB_RUNS` | `none` | `OBJECTS` · `LINKS` · `NOTES` · `TAGS` | `memory` |
| `PROVENANCE` | `none` | `STATUS` | **`db`** (flipped 2026-08-31) |
| `CONSIGNMENT_OUTPUTS` | **`duckdb`** (2026-08-10) | `ACQUISITION_LEDGER` | `memory` |
| `FILE_STAGES` | `none` | `DEDUP_LEDGER` | **`duckdb`** (D-9; keys hashed, absence fail-closed) |
| `DELIVERY_RECEIPTS` | `none` (the opposite call from dedup, by decision) | | |

Every DB implementation is plain JDBC over **one shared `Connection` per store**, DDL created lazily, no
migration tool; a failed open **degrades** to memory/file with a warning. **`-Dinspecto.db=duckdb|postgres`**
selects a *connection*, never an on/off switch — choosing Postgres **moves** the DB-backed families, it enables
nothing (PG-1, 2026-08-14); an unhonourable `postgres` **fails at boot** (`verifySelectable`, `OperationalDbTest`)
instead of coming up healthy with stores switched off; the driver rides Standard/Enterprise as the
`postgresql.jar` sidecar (a Maven profile was refused — it would gate a runtime dependency); the password is a
`SecretResolver` reference. The operational-DB screen (`GET /system/operational-db`, `POST …/test` with
`OK | DRIVER_MISSING | AUTH_FAILED | UNREACHABLE`) **reports and validates, never writes** — no `PUT`, by
decision. Edition model in one line: **Personal = embedded DuckDB for everything; Standard = PostgreSQL for
the operational stores, DuckDB retained as the query engine over Parquet; business data in neither**.

**Proving it** (DAT-6): `PostgresStateStoreTest` needs a server you supply (`-Dinspecto.test.pg.url` /
`INSPECTO_TEST_PG_URL`; the embedded harness was deleted 2026-09-07) and otherwise **skips all 11 methods
per test** so the absence shows in the count; it round-trips **nine** store classes and **not** the dedup
ledger, acquisition ledger or delivery receipts. 🔴 **The CWD trap**: a default-ON family under
`SpaceRoot.legacy()` mints a database in the working directory of every single-tenant install and every test
JVM; the root `pom.xml` pins `-Ddedup.ledger.backend=jdbc:duckdb:`, `-Dconsignment.outputs.backend=jdbc:duckdb:`,
`-Dstatus.backend=jdbc:duckdb:` for surefire — ⚠ a raw `jdbc:` *backend* value is a third source that bypasses
`OperationalDb`, tolerated for tests only.

### 3.9 The Data Browser

`DbBrowserRoutes` (`inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java`): `GET /db/catalog`,
`GET /db/table`, `POST /db/query` — business stores through the trusted relation + `SqlGuard`, unsealed;
operational stores through the `BrowsableStore` seam, appearing only for DB-backed backends and synchronized
on the live store's own connection; `DEFAULT_LIMIT=200`, `MAX_LIMIT=5 000`; `datasetByRef` maps a
`physicalRef` to a dataset id **first-scan-wins** (`putIfAbsent`). The SPA's Data Browser runs client-side
(AlaSQL over the loaded page) or "on server" via `/db/query`, widening `limit` by pages. **This is also the
Query Library's execution path** (§2).

### 3.10 DuckLake, the warehouse runbook, object-storage export

- **`DuckLakeRegistrar`** (`inspecto-etl/src/main/java/com/gamma/etl/DuckLakeRegistrar.java`), gated by
  `output.ducklake.enabled`: `INSTALL/LOAD ducklake`, `ATTACH 'ducklake:<catalog_url>' … DATA_PATH`, register
  the **already-written local** Parquet paths; **best-effort, non-fatal**; opens its own throwaway DuckDB, never
  a sealed connection. It **registers, it does not relocate** — bytes stay put. No test class exists.
- **The warehouse query layer** is an **operator runbook** against a customer's PostgreSQL: install
  `pg_duckdb`, run the bundled `warehouse_setup.sql` (repo root; not part of the Maven build), create roles
  and views. No `pg_duckdb` code in the repo; nothing tests it; `integrations.md` carries it without an
  intent banner (corrected with this spec).
- **Object-storage export** (S3 / HDFS) is **intent**: `object-storage-export.md` is a "recommendation of
  record" — prove the pattern with `aws s3 sync` / rclone first, then a push post-action reusing `AwsSigV4`
  only if it earns a place; a whole Space on S3 is recommended **against** (no atomic rename ⇒ the crash-safe
  commit ordering silently changes meaning); HDFS only via an S3-compatible gateway. Board: `EXPORT-1`;
  `EDITIONS.md` `OPS-05` is the Enterprise-only cell for the same subject.

### 3.11 The SPA

Studio **Datasets** (`modules/admin/studio/datasets/`): list + editor over `DatasetConfig`; columns, measures
and calculated-column editors; `calculated-column-guard.ts` mirrors `ExpressionGuard` from the contract JSON
(not authoritative); `bind-shared-dataset.dialog.ts` builds a `physical` Dataset with `physicalRef =
shared/<owner>/<item>` for an Exchange grant; `dataset-registration.service.ts` registers a pipeline's store as
a Dataset idempotently (`putIfAbsent` by `physicalRef`, never throws). **Query Library** (`/studio/queries`):
both types authored; `$`-parameters SQL-only; Run resolves parameters **client-side** and previews through
`DatasetRowsService` → `/db/query` (one page, `DEFAULT_ROW_LIMIT=1000`, `truncated` flag); a `503` on save is
explained (write root), a `422` is not specially handled. **Widget explorer** → `DatasetResultService` →
`/bi/query`, with a Time grain select feeding `QuerySpec.grains`. `DatasetRowsService` is **the single rows
seam** (Catalog split S2); three sample-row folds (`EntityProjectionGraphSource`, Geo point/route,
`ReconExecService`) are correct and must stay. ⚠ No `materialize` action; no timezone display handling.

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### Datasets and reads

| Date | Decision | Why |
|---|---|---|
| 2026-07-16 | **Three data classes; only the operational tier is a database**; business data is Parquet, always | no ORM, no single database; files are the lake |
| 2026-07-18 | **Store-layout contract**: a store is a top-level directory; a pipeline-shaped store reads at `database/`; nested sinks fail before writing | a recursive dataset glob double-counted a sink nested inside another store (UAT +72 %) |
| 2026-08-13 | **`SqlViews` owns the read options**; `hive_partitioning` stays **off** for Datasets | a bare `read_parquet` lacked `union_by_name` so one store gave two answers; turning partitioning on is a product decision, not a fix |
| 2026-08-14 | A Dataset's `sourceName` is **never defaulted** | `?? 'data'` made a source-less Dataset read empty everywhere, indistinguishable from an empty store |
| 2026-08-14 (S2) | **`DatasetRowsService` is the single rows seam**; every result is a page with `truncated` + `error`; the three sample folds stay | one seam, one shape |
| 2026-08-10 | A recompute writes a **new revision and flips state only**; bytes leave via `retire_superseded`, which deletes files and **keeps rows** | an in-flight read finishes on the revision it started with |
| 2026-08-29 | `JobService` **warns** when no `retire_superseded` is configured | the cost of never retiring had been silent |
| 2026-08-30 (operator) | **Sealing DROPPED, not deferred**; completeness is a scheduled per-pipeline KPI job | the partition-state machinery bought nothing the KPI does not |
| 2026-09-06 (D-7) | `materialize` → `summarize` rename **dropped as done-by-absence** | `summarize` shipped as a Step on its own; the task stays a Job |

### Queries and guards

| Date | Decision | Why |
|---|---|---|
| 2026-07-06 (R3) | **Query, Parameter, Result Set** are binding; `QueryType` = `sql \| structured` only — `graph` / `spatial` / `search` / `api` **not built** | geo and link views keep their own query shapes |
| 2026-07-08 | Calculated columns are a **fragment guard**, not a parser — closed alphabet + deny-set + whitelist; **no subqueries ever**, no quoted identifiers | DuckDB has no offline-safe Java parser; a closed surface is provable, "grow the whitelist, never parse harder" |
| 2026-07-19 | The Query Library authors `structured` too; `$`-parameters stay **SQL-only** | a structured query has no text to scan |
| 2026-07-22 (product, R6) | `structured` bodies are **client-compiled**; `/queries/{id}/run` answers `422` on them | the builder emits SQL; re-compiling server-side duplicates it — an accepted design, not a risk |
| 2026-07-24 | Window functions admitted **only with an immediate `OVER (…)`**; frames unsupported | a per-row window value is legal beside `*`; a bare aggregate would collapse the projection |
| 2026-08-14 | **Time grain travels on the wire**; a grain on an ungrouped column is `422`; the bucket is **TEXT** | a widget must label categories identically live and offline; a silent no-op hides the caller's mistake |
| 2026-08-14 | ⛔ No client-side time-grain fold | a fold cannot bucket rows the server already aggregated |

### The runtime and its stores

| Date | Decision | Why |
|---|---|---|
| 2026-07-25 | D12 chunking **on** by default (8 GiB); D11 declined the same day | chunking is bounded scratch; a memory cap needed a measurement first |
| 2026-07-27 | **D11 measured: the number is `2GB`** | peak memory does not scale with input; blocking operators hard-fail below the cap rather than spill |
| 2026-08-26 | D11 shipped **as a pair**; `maxConcurrentRuns=4` defaults on; `memory_limit` is **served** by `scheduler.toon` with `file > property > default` and a portable anchored grammar; preview connections stay **uncapped** | a key served by the settings tier must not also be read from `-D` at use time; ⚠ **no `memory_limit` default is in code** (§2) |
| 2026-07-25 | ⛔ No computed cap (`RAM ÷ maxConcurrentRuns`); no `max_temp_directory_size` default | the divisor is routinely unknown; no temp default is defensible without knowing the volume |
| 2026-08-15 (probed) | **The session `TimeZone` is the host zone**; the `now()` writer and the `systemDefault()` reader are a matched pair — do not "fix" either | DuckDB is blind to `-Dops.timezone`; the UTC belief holds only for an ICU-less build |
| 2026-08-15 | **`-Dops.timezone`** governs cron and `$today`; `meta.domain.timezone` stays display-only | several `*_meta.toon` merge last-non-blank-wins — wiring it would make cron depend on scan order |
| 2026-08-29 | **Source timezone**: three tiers, naive-UTC compile shape, `TIMESTAMPTZ` without a zone **refused at load**, `%z` refused, dates exempt | a wall-clock value must not mean whatever the host thought; a date has no instant to shift | <!-- vocab-allow: 'source timezone' is the parsing.source_timezone config key, not the acquisition entity -->
| §7.5 probe | Fixed-bucket histogram for non-additive measures | aggregate state is persistable but **not restorable** |
| 2026-08-10 | `consignment_outputs` defaults **on** — the one exception then | `ReprocessCommand`'s duplication refusal is decidable only from `COMPACTED_AWAY` rows |
| 2026-09-01 (D-9, operator) | Dedup ledger defaults **on**; keys **hashed, never verbatim**; absence **fail-closed** | an MSISDN table is a data-protection surface the other ledgers are not |
| 2026-09-07 | Delivery receipts default **`none`** — the opposite call | default-on would mint a DB file in the CWD of every Personal install |
| 2026-08-14 (PG-1) | One selection `-Dinspecto.db`; it **moves, never enables**; unhonourable Postgres **fails at boot**; the driver is a **sidecar**, not a Maven profile | deployments had come up healthy with stores silently off; a profile would gate a runtime dependency |
| 2026-08-15 | The operational-DB screen **never writes** | the process serving the UI is the one that needs the DB; a `PUT` would be a second declaration of the same fact |
| 2026-09-07 (operator) | `PostgresStateStoreTest` needs a server you supply; skips **per test** | an `@BeforeAll` assumption prints "Tests run: 0" and hides the absent coverage |
| 2026-09-06 (operator) | **Postgres multi-user PARKED** until a multi-operator install exists | no consumer |
| 2026-08-28 (operator) | Object-storage export: **analysis kept, not scheduled**; whole Space on S3 recommended **against** | no atomic rename on S3 changes the crash-safe commit's meaning |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| DuckDB `memory_limit` **default** (GAP-4) | `BACKLOG.md` §3 *Deployment topology gaps* | Only the concurrency half of D11 defaults on; no `scheduler.toon` ships |
| **Postgres multi-user** — pool, `browseConnection()`, schema-per-Space, `CaseStore`, the three uncovered stores, a concurrency test | `BACKLOG.md` §3 *Postgres multi-user* (PARKED by §6); `EDITIONS.md` OPS-03 | |
| **`EXPORT-1`** outbound object-storage export | `BACKLOG.md` §3 (P3); `EDITIONS.md` OPS-05 | Prove the consumption pattern with `s3 sync` first |
| `graph` / `spatial` / `search` / `api` query types; more `$`-resolvers | `BACKLOG.md` §3 *Queries / BI* (P3) | Deliberately not built |
| §7.4 rollup cache; `generation` staging; `run_id` always null | `BACKLOG.md` §3 *Consignment ELT* | Until read-time aggregation is measurably slow |
| `retire_superseded` must be configured or a recompute keeps an extra copy forever; `DatasetRelation.temporalColumn` has no caller | `BACKLOG.md` §3 *Consignment addressing* | |
| `DatasetAccess` after the Consignment Selector; a fragment guard for third-party `LOWERED` steps | `BACKLOG.md` §3 *Platform Services Stage 2 / 3* | |
| Reference-Dataset promotion export; "the engine has no grouping transform" | `BACKLOG.md` §3 *Unification W4 / W5* | |
| The `batch_id` DDL / `__batch_id` / `.toon` key trio | `BACKLOG.md` §2 release notes; §7 | Rides the MAJOR |
| Studio analytics step processors `SP-BI-*` | `EDITIONS.md` board | No BACKLOG row |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-NOPROOF-1`, `SPEC-DEADSEAM-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **The Query Library never calls `POST /queries/{id}/run`** | zero callers in `inspecto-ui/src/app`; `queries.component.ts` → `DatasetRowsService.sql()` → `/db/query` | Server-side parameter resolution (`$current_user`, `$role`), the Result Set descriptor and the 500/10 000 limits are unreachable from the product; `DAT-3` is a Must whose client half is absent |
| **No UI triggers `materialize`**; no committed job schedules one | `DatasetKind.materialized` "for completeness"; no `materialize` job in `spaces/` | `DAT-4` is reachable only by hand-authoring a maintenance job |
| **`memory_limit` has no default in code** while two pages say it does | `DuckDbUtil.memoryLimit` → DuckDB default; `BACKLOG.md` GAP-4 | Corrected in both pages with this spec; the board row stays |
| **The SCD2 Reference engine is unbuilt** behind an authorable `load: scd2` | `ProcessorCatalog` `transform.dim.scd2` PLANNED | A config value the engine accepts and does not honour |
| **`DuckLakeRegistrar` has no test**; the warehouse runbook has no code | `inspecto-etl` test tree; `warehouse_setup.sql` at the repo root | `SP-SNK-03` is ✅ on the board for a registrar nothing exercises |
| **The Postgres test covers 9 of 12 families** and the page said 6, 7 and 10 | `PostgresStateStoreTest` methods | Corrected with this spec; the three uncovered stores are `DbDedupLedger`, `DbAcquisitionLedger`, `DbDeliveryReceiptStore` |
| **`DatasetRelation.temporalColumn` has no caller** | `BACKLOG.md` §3 note | Dead seam |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 A server-side structured compiler on `/queries/{id}/run` — REFUSED (R6, 2026-07-22)

The builder emits SQL. `/bi/query` covers the *spec* grammar and fails honestly on the rest (§3.5); a second
compiler for the builder's full grammar duplicates the client for no functional gain. Revisit only if an
external `/api/v1` consumer must submit structured bodies.

### 6.2 A real SQL parser for calculated columns — REFUSED (2026-07-08)

No offline-safe Java DuckDB parser exists; the three-rule fragment guard is a provably closed surface. Grow
the whitelist. Quoted identifiers, subqueries and bare aggregates are deliberate cuts; explicit window frames
are unsupported.

### 6.3 DuckDB `spatial`; `graph` / `spatial` / `search` / `api` query types — REFUSED

Zero demand re-verified 2026-08-26; ⛔ do not re-open on speculation. The sandbox loads no extensions.

### 6.4 A computed memory cap; a `max_temp_directory_size` default; capping preview connections — REFUSED (D11)

The divisor is routinely unknown and the batch path has its own semaphore; no temp default is defensible
without the volume size; preview and dry-run stay uncapped — "do not widen this".

### 6.5 Timezone shortcuts — REFUSED

A `timezone_column` editor (a per-row column beside ~418 zone names invites the ambiguity the mutual-exclusion
rule prevents — hand-authored values are carried through a save read-only); "the offset in the data wins"
(**not built, deliberately** — a fifth precedence tier, never reached by relaxing the `%z` gate); unifying the
two zone checks; "fixing" the DuckDB reader to UTC (would *create* the skew).

### 6.6 Postgres shapes — REFUSED

A Maven edition profile for the driver (the first seam gating a runtime dependency); a `PUT` on the
operational-DB screen (split-brain with `-D`, no effect without a restart); echoing any password in any form
(not the value, not a redaction, not a length — a literal in a `POST` is `422`); reporting a shared `user`
for credential-less families; database-per-Space and PgBouncer (`SPC` §6.7).

### 6.7 `hive_partitioning=true` for Datasets; a synthetic `table` backfill; the `batch_id` rename now — REFUSED

Partition segments would appear as columns on every Dataset; a wrong lineage edge is worse than an absent one;
the DDL rename needs `ALTER TABLE` plus a `payload` blob rewrite and rides the MAJOR.

### 6.8 Sealing and the partition-state machinery — DROPPED (operator, 2026-08-30)

§8 / §8.4 / §11.4 sealing, `partition_state`, seal signals and the SLA object are **superseded in full** by the
scheduled completeness KPI; the §7.4 rollup cache stays unbuilt until aggregation is measurably slow.

### 6.9 Object-storage shapes — REFUSED (2026-08-28)

A whole Space on S3 (no atomic rename); `hadoop-client` for HDFS (⛔ never — S3-compatible gateway only).

### 6.10 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| "D11 — NOT implemented; no default `memory_limit`" (2026-07-25) → "both on by default" (2026-08-26) | **the concurrency half defaults on; `memory_limit` is served with no code default** — corrected with this spec | `duckdb.md`, `api-stability.md`, GAP-4 |
| "`DuckDB` defaults to UTC" | the host zone, probed 2026-08-15 | `duckdb.md` |
| `status.backend` default `file` | `db` (2026-08-31) — corrected in `db-layer.md` §2 with this spec | `OperationalDb.java:132` |
| "exactly one store defaults on" (2026-08-10) | three do — `consignment_outputs`, the dedup ledger (D-9), status | `db-layer.md` |
| Postgres coverage "6 of 9" / "all seven" / "all ten"; roster "ten" / "eleven" families | **9 of 12**, roster **12** — corrected with this spec | `db-layer.md` |
| `Cube` as the asset noun | **Matrix**, a label over `DERIVED_TABLE` (2026-08-04); §6-B's "not yet surfaced as of 2026-07-20" corrected with this spec | `GLOSSARY.md` |
| The embedded-Postgres harness | an operator-supplied server, opt-in test (2026-09-07) | `db-layer.md` |
| `queries.md`'s account of the structured boundary without `/bi/query` | §3.5 of this spec; the page now names both routes | `queries.md` |
| `integrations.md`'s warehouse half without an intent banner | banner added with this spec | `integrations.md` |
| The `BACKLOG.md` §5 note "DAT-6 wants a caveat"; the §7 row "the §3 row contradicts §6, its pointer is dead" | both stale — the caveat is in `REQUIREMENTS.md`, the §3 row says PARKED and points at the archive; retired with this spec | `BACKLOG.md` |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| The three data classes, the store roster and schemas, per-Space topology, Postgres, the CWD traps, proving DAT-6 | `docs/okf/backend/engine/db-layer.md` (`Reference`, 53 KB) | eleven source files via its `PostToolUse` hook | §3.1, §3.8 — the largest Reference in the bundle |
| Appender ingest, threads, D11, the three timezone rules, aggregate state | `docs/okf/backend/engine/duckdb.md` (`Concept`) | `DuckDbUtil.java` | §3.7 |
| Query as a Component, `/queries/{id}/run`, calculated columns, time grains | `docs/okf/backend/control-plane/queries.md` (`Concept`) | `com.gamma.query` | §3.3–§3.5 |
| The store-layout contract, `sinks:`, quarantine | `docs/okf/backend/engine/output-sinks.md` (`Concept`) | `inspecto-etl` | §3.2 |
| The Consignment Selector, revisions, `retire_superseded` | `docs/okf/backend/engine/consignment-addressing.md` (`Concept`) | `inspecto-engine` | §3.2 |
| `materialize`, `retire_superseded` as Job / task types | `docs/okf/backend/control-plane/jobs.md` (`Concept`) | `com.gamma.job` | §3.6 |
| DuckLake and the warehouse runbook | `docs/okf/backend/integrations.md` (`Reference`) | `DuckLakeRegistrar.java` | §3.10 — ⚠ runbook, not feature |
| Object-storage export posture | `docs/okf/backend/engine/object-storage-export.md` (`Reference`, self-labelled intent) | — | §3.10 |
| Retention and the operational flags | `docs/okf/backend/build-run/operations-reference.md` (`Reference`) §Retention | — | the operator's view |
| Studio Datasets, Query Library, the rows seam | `docs/okf/frontend/features/studio.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/studio/` | §3.11 |
| The Data Browser | `docs/okf/frontend/features/catalog.md` (`Feature`) §Data Browser | — | §3.9 |
| ⚠ **No page owns `SqlSandbox` / `SqlGuard` / `SqlOracle` as a subject**, no page owns `DatasetRelation` beyond `db-layer.md`'s seam table, and no frontend page exists for Datasets or the Query Library outside `studio.md` | *(gap)* | `inspecto-sql/src/main/java/com/gamma/sql` · `inspecto-engine/src/main/java/com/gamma/query` | §3.2–§3.3 of this spec |

---

## 8. Verification

### 8.1 Relations, guards, compiler — `inspecto-engine` · `inspecto-sql` (default reactor)

| Class | Proves |
|---|---|
| `DatasetRelationTest` | view / `physicalRef` forms; `physicalRefWithDatabaseSubtreeReadsMappedOutputOnly`; the mixed-schema-partition read (`union_by_name`); calculated wrap |
| `ExpressionGuardTest` · `ExpressionGuardContractTest` | the three rules, window-`OVER` gating; the contract JSON both sides read |
| `SqlGuardTest` · `SqlSandboxTest` · `SqlOracleTest` | statement guard; open / seal settings; the sealed oracle |
| `MeasureCompilerTest` · `MeasureCompilerGrainExecutionTest` · `MeasureGrammarContractTest` | `AGGS`, grains; the compiled SQL **executed** in the sandbox; the cross-language grammar |
| `ParametersTest` · `QueryExecutorBindsTest` | `$` resolution incl. verbatim unknowns; positional binds |
| `MaterializeTaskTest` | compile / snapshot, the atomic swap, the registered `dataset` |
| `ViewStoreTest` · `OutputFormatTest` · `PartitionWriterTest` · `PartitionWriterFormatTest` | sink views; the write side DAT reads |

### 8.2 Routes and stores — `inspecto/src/test/java/com/gamma/`

`ControlApiQueryRunV1Test` (the route, `422` on structured, limits), `ControlApiBiQueryTest` (spec compile,
`422` grain, TEXT buckets), `ControlApiDbBrowserTest` (`/db/*`, limits, guard), `OperationalDbTest` (the
twelve-family roster, `verifySelectable`, the `-Dinspecto.db` selection pinned across families),
`PostgresStateStoreTest` (`inspecto-ops`; **opt-in**, 11 methods, 9 store classes; 11 SKIPPED without a
server), `PipelineJobRunnerTest` (`sinkNestedInsideAnotherStoreFailsClosed`, `slashedSinkStoreNameFailsClosed`,
`externalDataDirStaysAllowed`, `seedReadsAPipelineShapedStoresMappedOutputOnly`).

### 8.3 UI specs — vitest (15 files)

`datasets.component.spec.ts`, `dataset-editor.component.spec.ts`, `dataset-columns.component.spec.ts`,
`dataset-measures.component.spec.ts`, `dataset-calculated.component.spec.ts`, `calculated-column-guard.spec.ts`,
`dataset-types.spec.ts`, `dataset.kind.spec.ts`, `datasets.service.spec.ts`, `bind-shared-dataset.dialog.spec.ts`,
`queries.component.spec.ts`, `query.kind.spec.ts`, `dataset-result.service.spec.ts`, `dataset-rows.service.spec.ts`,
`data-browser.component.spec.ts`; plus `expression-guard` and `measure-grammar` contract specs.

### 8.4 Committed artifacts

`registry/datasets/`: 7 in `spaces/demo` (`maintenance_backups`, `ops_analytics`, `orders_dataset`,
`orders_enriched_dataset`, `orders_rollup_dataset`, `payments_dataset`, `shipments_dataset`), 3 in
`spaces/ucc`, 1 in the starter template; `registry/queries/`: 1 (`orders_by_region`). `warehouse_setup.sql`
at the repo root (253 lines, outside the build). **No committed `materialize` or `retire_superseded` job.**

### 8.5 Guards

The root `pom.xml` surefire pins on the three default-on families (§3.8); `SqlGuard` and `ExpressionGuard` at
run time; `SchemaFieldTypes` at load (`MET`); `expression-guard` and `measure-grammar` contract pairs;
`check-vocabulary` keeps *Data Store* and *Metric* (BI sense) out of the docs. <!-- vocab-allow: names the banned words the guard removes -->

### 8.6 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **No client-side test of `/queries/{id}/run`** — no client code calls it | §2 |
| **No test of a materialization from the product** — nothing in the product triggers one | §3.6 |
| **`DuckLakeRegistrar` has no test; the warehouse runbook has no code** | `inspecto-etl` test tree; repo root |
| **Postgres coverage is opt-in and partial** — 9 of 12 families, only with a server | §3.8 |
| **No test that the DuckDB session zone equals the host zone** — pinned by ⛔ comments at three call sites | `duckdb.md` |
| **No test that `retire_superseded` is configured** — only a WARN | §3.2 |
| **The SCD2 `load` value is accepted and never honoured** — no test could pass | §3.2 |
