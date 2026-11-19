---
type: Capability
title: Data plane (DAT) — Specification
description: Part 2 of 3 of Data plane (DAT): Specification
tags: [dat, capability, dataset, query, parameters, result-set, bi-query, sql-guard, expression-guard, matrix, materialize, duckdb, postgres, operational-db, ducklake, warehouse, timezone]
timestamp: 2026-09-08T00:00:00Z
item: layer.platform
audience: internal
status: plan
verified: 2026-11-20 last-edit
---

# Data plane (DAT) — Specification

Part 2 of 3 of [Data plane (DAT)](plan-data-plane.md) · previous: [Purpose, scope and requirements of record](plan-data-plane-scope.md) · next: [Decisions, not built, refused and verification](plan-data-plane-decisions-verification.md).

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
(`platform/inspecto-engine/src/main/java/com/gamma/query/DatasetRelation.java`) turns it into the **trusted relation**
SQL every consumer runs against: a `view` Dataset renders its `sink.view` definition (`ViewStore`,
`@PublicApi`); a `physicalRef` Dataset reads the store through **`SqlViews`** — the one owner of read
options (`union_by_name=true`; `hive_partitioning` deliberately **off**, since turning it on would surface
partition segments as columns on every existing Dataset); `calculated[]` wraps `SELECT *, (expr) AS "name"
FROM (<base>) AS __base` after `ExpressionGuard.check`; `temporalColumn()` picks the `role:
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
config, and `upsert`/`scd2` are **built** as an append-only version store (rechecked 2026-10-06, `LA-DAILY-INGEST-1` D-ING4): `ConsignmentIngestStrategy.stampReferenceVersions` stamps `__key_hash/__row_hash/__valid_from/__op/__consignment_id` (`__batch_id` before 4.0.0), skips unchanged rows and appends changed ones under `__v_<batch>`; `ReferenceReader` derives the current view, or, for `scd2`, one `as_of` instant per binding (`__valid_from <= instant`, `ReferenceScd2AsOfTest`). What is **unbuilt** is the `transform.dim.scd2` processor (`Status.PLANNED`), a `valid_to` column, a feed-supplied effective date (`__valid_from` is ingest `now()`), and a per-fact-row as-of join.

**Daily-updated Reference file (verified 2026-10-06, `LA-DAILY-INGEST-1` T6).** `load: replace` overwrites the
SAME output file (the output stem is the input file name), so a dimension file named with its date leaves
yesterday's file in place and the Reference reads both days; land it under a stable name or use `upsert`, which
keeps a key absent from the new file and drops one only through `reference.delete`. A Reference is joined at
READ time (`ReferenceReader.sqlFor`), so a change is visible with no fact re-ingest. A Link Analysis Index does
NOT go stale when a Reference changes (its fingerprint covers the Dataset's own store files and relation SQL
only; a view-backed join Dataset has no enumerable files), so mutable dimension attributes stay out of the
index. Tests: `ReferenceDailyFileLoadTest`, `ReferenceChangeIndexStalenessTest`; detail in the Link Analysis roadmap.

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

Every SQL reaches DuckDB through **`QueryExecutor`** (`platform/inspecto-engine/src/main/java/com/gamma/query/QueryExecutor.java`)
inside a **`SqlSandbox`** (`platform/inspecto-sql/src/main/java/com/gamma/sql/SqlSandbox.java`): `open()` sets
`autoinstall_known_extensions=false`, `autoload_known_extensions=false`, `memory_limit`, `threads`;
`seal()` adds `enable_external_access=false` + `lock_configuration=true`. **Only `SqlOracle` seals**;
`QueryExecutor` and the Data Browser run **unsealed by design**, because the trusted relation legitimately
reads Parquet. Two guards, two grains:

- **`SqlGuard`** (`platform/inspecto-sql/src/main/java/com/gamma/sql/SqlGuard.java`) validates a **whole statement**: a single read-only
  `SELECT` / `WITH`; blocked functions (`read_*`, `write_*`, `*_scan`, `copy`, `getenv`, `glob`, `system`,
  `shell`, …); blocked keywords (DDL / DML / `attach` / `install` / `load` / `pragma` / `set`, …);
  string-literal-aware comment stripping; an unterminated comment is a rejection.
- **`ExpressionGuard`** (`platform/inspecto-engine/src/main/java/com/gamma/query/ExpressionGuard.java`) validates a **fragment** — the
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

**`POST /queries/{id}/run`** (`QueryRoutes.java`): `422` unless `type: sql`; **`Parameters.resolve`**
(`platform/inspecto-engine/src/main/java/com/gamma/query/Parameters.java`) substitutes the built-ins `$today`, `$now`, `$day(n)`,
`$current_user`, `$role` and user-declared `$name` (+ offset), leaves `:name` and `${ENV:KEY}` untouched, and
**leaves an unknown token verbatim** (the save-time rejection of unknown tokens is the *job* parameter
contract, a different namespace — `PIP`); then `SqlGuard.check`; dataset via `DatasetRelation`; **offset
pagination** with `DEFAULT_LIMIT=500`, `MAX_LIMIT=10 000`; returns rows plus the **Result Set** descriptor
(columns with type + analytic role dimension / measure / temporal). ⚠ **The SPA does not call it** (§2). The
three parameter namespaces are deliberately distinct and each resolver ignores the other two.

### 3.5 `POST /bi/query` — the spec-compiled path, and where the structured boundary really is

**The list limit (`LIST-LIMIT-1`, MFS review D9, 2026-10-27).** A spec that states no `limit` is read at the **Space's
list limit**: `GET|PUT /spaces/{id}/settings/list-limit` (`{rows, effectiveRows, defaultRows, maxRows}`, `canAuthorWorkbench`
to write), stored as `list-limit.toon` (`rows: N`) in the Space's config root (`ListLimitSettings`, mirroring the
pipeline-history setting). `rows` null means the shipped default, **1,000** (it was a hard-coded 500 in
`BiRoutes.DEFAULT_LIMIT` and in `ShareRoutes`' public read); a value outside 1..10,000 is refused with 422 on the PUT and
reads as the default from a hand-edited file. 10,000 is `MeasureCompiler`'s own ceiling, so a larger request is still
clamped there. A spec's own `limit` still wins. The same limit caps the Risk Scores list
([`spec-risk-scores.md`](../../module/scoring/spec-risk-scores.md)). `POST /queries/{id}/run` (the Query Library) keeps its
own 500 default: it is the Explorer's page, not a Dashboard read.

`BiRoutes` (`inspecto/src/main/java/com/gamma/control/BiRoutes.java`): **`POST /bi/query`**,
`GET|POST /bi/templates` (+ apply, capability-gated). ⛔ `GET /bi/datasets` was **retired 2026-09-14**
(`RETIRE-HALVES-1`) — no client ever listed Datasets through it. `/bi/query` takes a **spec** — measures, dimensions,
filters, an optional `grains` map — and **`MeasureCompiler`** (`platform/inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java`)
compiles it over the Dataset's trusted relation: `AGGS` = `count · countDistinct · sum · avg · min · max ·
median · stddev · variance · p05 · p25 · p75 · p90 · p95 · p99`, `GRAINS` = `day · week · month`. **Time grain travels on the wire** (2026-08-14): a grain naming a column that is
not grouped is a **`422`, not a silent no-op**; the bucket is emitted as `STRFTIME(DATE_TRUNC(...))` **and
repeated in the `GROUP BY`**; ⚠ **the bucket is TEXT** (`%Y-%m-%d`, `%Y-%m`), never a timestamp, because those
are the exact keys the SPA's offline `bucketValue` produced — one widget must label categories identically
live and offline; `MeasureCompilerGrainExecutionTest` **runs** the SQL rather than string-comparing it. The
`AGGS` vocabulary is pinned by `measure-grammar.contract.json` — ⚠ the SPA's `measure-grammar.ts` consumer is
the **pipeline `transform.summarize` editor**, not `/bi/query`; the two share a grammar, not a code path.

**Statistical aggregates (ANALYTICS-CORE-1 M3, decision D-AN5, 2026-10-17).** `median`, `stddev`, `variance` and
the fixed percentiles `p05 p25 p75 p90 p95 p99` are **fixed names**, so a Measure stays the `{agg, field}` pair
(parked-rows D10) and the id stays `agg_field` (`p95_latency_ms`). SQL: `MEDIAN(x)`, `STDDEV_SAMP(x)`,
`VAR_SAMP(x)`, `QUANTILE_CONT(x, 0.95)` (`MeasureCompiler.PERCENTILES`). Decisions as built:
- **Sample, not population** (`n − 1`): the figure Excel's `STDEV`/`VAR`, Tableau's `STDEV`, Power BI's
  `STDEV.S` and pandas default to, so a number a user cross-checks in their own tool agrees. Consequence: a group
  of one row has a NULL spread, not 0. A population variant was not added — it would be a second name for a
  near-identical number, and D-AN5 fixed the list.
- **Continuous quantiles** (D-AN3): interpolated, `p95` of {10, 30} = 29; LA's `quantile_disc` link statistics are
  untouched.
- **Numeric only**, like `sum`/`avg` (`MeasureCompiler.NUMERIC_AGGS`, the summarize type check).
- **DuckDB only.** Every `MeasureCompiler` statement runs in the DuckDB sandbox (it already emits `STRFTIME`);
  PostgreSQL is an operational-store dialect (`DbJobRunStore`, `DbEventStore`), never a Measure target, so no
  `percentile_cont … WITHIN GROUP` mapping exists. Add one only if a Measure ever runs over a JDBC store.
- **Masking:** `BiResultMasking.revealsValues` refuses every aggregate but `count`/`countDistinct` over a masked
  column (a median or percentile IS a key on an odd-sized group) — fail closed for any aggregate added later.
- **Working Set excluded:** `WorkingSetMeasures` (in-JVM, LA) evaluates only the first six and refuses the
  statistics; `AlertRule` refuses them on an Investigation rule at save. Re-implementing a median in Java is the
  duplication the analytics core plan removes.
- **Twin + goldens:** the SPA's offline engine (AlaSQL) gets DuckDB-named `QUANTILE_CONT` / `STDDEV_SAMP` /
  `VAR_SAMP` aggregates (`inspecto-ui/src/app/inspecto/data-table/sql/stat-aggregates.ts`); the SPA's median is
  `QUANTILE_CONT(x, 0.5)` because AlaSQL's own `MEDIAN` takes the upper middle of an even count. The contract file's
  `statistics` block carries golden values that `MeasureStatisticsExecutionTest` (DuckDB) and `query-spec.spec.ts`
  (AlaSQL) both assert.

**The structured boundary, stated once.** `/queries/{id}/run` answers `422` on `type: structured` by product
sign-off (R6, 2026-07-22): the builder emits SQL, so re-compiling structured bodies there duplicates the
client. `/bi/query` **is** a server-side structured evaluator for the *spec* grammar the widget explorer
speaks, and it **fails honestly** on what it cannot map (named-Measure SQL, `OR` filters). Neither route lies;
`queries.md` described only the first. Widgets preview through `DatasetResultService` → `/bi/query`.

### 3.6 Matrix materialization

**`MaterializeTask`** (`platform/inspecto-engine/src/main/java/com/gamma/job/MaterializeTask.java`, `task: materialize`
on the maintenance runner): params `dataset`, `target`, `measures`, `group_by`, `limit` (default 1 000 000);
compiles a spec-based `SELECT` through `MeasureCompiler` or takes a raw snapshot over the source Dataset's
trusted relation; `COPY … TO … (FORMAT PARQUET)` into `.tmp`; **hide-old to `.stale` → `ATOMIC_MOVE` reveal →
delete stale** (a crash leaves only glob-invisible leftovers, self-cleaning); then **registers or refreshes a
`dataset` component** stamped `materialized: {from, at, rows}` and emits `DatasetWriteSignal`. So a Matrix is
queryable everywhere a Dataset is — **zero net-new read paths**. The `materialize` → `summarize` rename was
**dropped as done-by-absence** (D-7, 2026-09-06): `summarize` shipped independently as a Step, `MaterializeTask`
stays a Job, the two share only the measure grammar.

✅ **Both of the gaps this section recorded are CLOSED 2026-09-14** (STUDIO-HALVES-1) — until then the task
was reachable in principle and never taken in practice:
- **A route:** `POST /datasets/{id}/materialize` (`DatasetRoutes`) triggers it ad hoc, **asynchronously** —
  202 + `runId` + a `Location` to poll, the shape `POST /jobs/{name}/trigger` already uses, because the
  default snapshot is a million rows. It runs through `JobService.triggerMaterializeRun`, a synthetic
  `type: maintenance` config executed on the ordinary run lifecycle but **never registered**, so asking for
  a materialization authors nothing. ⛔ That was the point of choosing a route: the rejected alternative had
  the Dataset page create and trigger a real job, making a viewer action write a job document.
  Gates: write root → 503 · unknown source → 404 · missing/unsafe/self-referential `target` → 422 · the same
  target already in flight → 409. ⚠ The 409 is load-bearing — the run name is `materialize:<target>`, so
  per-name non-overlap would otherwise admit a second fire and silently record it `SKIPPED`, handing the
  caller a `runId` for a run that does nothing. Capability: **`canOperateRuns`**, as for any job trigger —
  materializing writes data, never config, and the same effect was already reachable that way.
- **A committed job:** `spaces/demo/config/jobs/orders_by_region_materialize_job.toon` (daily 05:00,
  `region` × `sum(gross)` → the Dataset `orders_by_region`).
- **A UI action:** Materialize in the Dataset editor header, gated on `canOperateRuns`, asking only for
  the target id.

✅ **`MaterializeTask` WAS space-blind; fixed 2026-09-15** (`MATERIALIZE-SPACE-ROOT-1`, found and closed
the same day). It re-read the JVM-wide `-Dassist.write.root` on the worker thread while every control-plane
route resolves the **current space's** config root, so a multi-space run read one space's registry while
writing another's data dir and failed with `unknown dataset <the id the route had just resolved>` — after a
202. ⚠ It was never only materialize: **nine** run-time readers had the same shape.

**`com.gamma.pipeline.SpaceConfigRoot` is now the single per-Space config-root map** — published by
`SpaceBootstrap`, keyed by `EventLog.currentSpaceId()` (the space MDC, which both `JobService` submit paths
set on the worker thread), with `forSpace(id)` for a caller that already holds an id. Seven job types
resolve through it (`materialize`, `recon.run`, `report`, `metadata_validate`, `storage_report`,
`file_repository_audit`, `objects.analytics`), and `DecisionRules` — which had this design all along, for
one consumer — forwards to it rather than keeping a second map.
✅ **It carries a per-Space DATA root beside the config root** (`COLLECTOR-SPACE-ROOT-1`, 2026-09-15) —
registered together by `SpaceBootstrap`, dropped together by `forget` (pinned:
`forgetDropsBothRootsTogether`), and `DatasetCollectorConnectorFactory` resolves both through it. ⚠ Only
**one** of the five `data.dir` readers was space-blind; the other four already used the
property-as-override pattern, so this was a single straggler, not a class.
⛔ **A half-fix here is worse than none.** That connector read **two** JVM-wide properties —
`assist.write.root` *and* `data.dir` — so swapping in `SpaceConfigRoot.current()` for the first alone
would have re-created the very defect its parent closed. Both roots had to move at once.
⛔ **A named Space does not fall back to the JVM property**; falling back is what caused the defect.
⇒ **single-space deployments make the two paths identical, which is why no test ever saw it** — and why
the proof is a live multi-space run: 202 → SUCCESS, 7 rows, Parquet written, Dataset registered in `ucc`.

🔴 **Found while wiring it: `materialize`'s five parameters were UNDECLARED on the `maintenance` job type**
(`JobService`'s `JobTypeDescriptor`), so the authoring form could offer the task and not one parameter that
makes it do anything, and the resolver could not bound them. Declared 2026-09-14 — the same defect the
`min_files` comment records one line above, and worse here because nothing shipped had ever fired the task
to expose it. ⇒ **a task's presence in the runner's switch is not evidence its contract is declared.**

✅ **The consumer half is proven live too (2026-09-15).** `spaces/demo/config/orders/orders_by_region_feed_pipeline.toon`
consumes the matrix this section's job writes (`connector: dataset` + `on: dataset`), and on a live multi-Space
server the materialize's `DatasetWriteSignal` fired it within the same second — 4 rows in, 4 rows committed. The
proof, and the two defects it surfaced (a broken committed job, a space that does not boot on Basic), are in
`acquisition.md` §8.5. ⚠ The committed job now reads `measures: "sum(gross)"` — the measure grammar is `count` or
`agg(field)`, never `field,agg`.

### 3.7 The DuckDB runtime

`DuckDbUtil` (`platform/inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java`): driver load, `tempDbFile`,
`applyDuckDbSettings` (`temp_directory`, `memory_limit`, `max_temp_directory_size`), **`memoryLimit()`
precedence = per-config → the served `scheduler.toon` value → `-Dprocessing.duckdb.memory_limit` → DuckDB's
code default `defaultMemoryLimit()`** (40 % RAM ÷ 4, ≥ 1 GiB — GAP-4, 2026-09-26; until then DuckDB's own
≈80 %), `effectiveWorkerThreads` (cores ÷ batch
concurrency, against oversubscription), `buildCopyOptions` (PARQUET → SNAPPY). The D11 pair (2026-08-26):
`maxConcurrentRuns` **defaults to 4** (`JobService.java`); `memory_limit` is **served, not mirrored** —
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

**`OperationalDb.Family`** (`inspecto/src/main/java/com/gamma/service/OperationalDb.java`) is **the
roster — sixteen families**, each with its own `-D<family>.backend` toggle and default:

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
instead of coming up healthy with stores switched off; the driver rides Standard/Advanced as the
`postgresql.jar` sidecar (a Maven profile was refused — it would gate a runtime dependency); the password is a
`SecretResolver` reference. The operational-DB screen (`GET /system/operational-db`, `POST …/test` with
`OK | DRIVER_MISSING | AUTH_FAILED | UNREACHABLE`) **reports and validates, never writes** — no `PUT`, by
decision. Edition model in one line: **Basic = embedded DuckDB for everything; Standard = PostgreSQL for
the operational stores, DuckDB retained as the query engine over Parquet; business data in neither**.

**Proving it** (DAT-6): `PostgresStateStoreTest` needs a server you supply (`-Dinspecto.test.pg.url` /
`INSPECTO_TEST_PG_URL`; the embedded harness was deleted 2026-09-07) and otherwise **skips all 11 methods
per test** so the absence shows in the count. It round-trips 11 of the 16 families (one method each) and `PostgresOpsStoreTest` the four
object families, so **15 of the 16** are covered; only Alerts (`DbAlertStore`) has no Postgres round-trip
(re-counted 2026-11-15; the "nine, not the dedup ledger, acquisition ledger or delivery receipts" this sentence
said was stale). 🔴 **The CWD trap**: a default-ON family under
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

- **`DuckLakeRegistrar`** (`platform/inspecto-etl/src/main/java/com/gamma/etl/DuckLakeRegistrar.java`), gated by
  `output.ducklake.enabled`: load `ducklake`, `ATTACH 'ducklake:<catalog_url>' … DATA_PATH`, register
  the **already-written local** Parquet paths; **best-effort, non-fatal**; opens its own throwaway DuckDB, never
  a sealed connection. It **registers, it does not relocate** — bytes stay put. *(⚠ "No test class exists" stood
  here until 2026-09-11 and was wrong — `DuckLakeRegistrarTest` does.)*
  🔴 **Two things this bullet used to describe wrongly, both measured 2026-09-11.** (1) It said
  `INSTALL/LOAD ducklake`, and the code really did open with an unconditional **`INSTALL ducklake FROM
  core`** — a network fetch on every registration, so an air-gapped install egressed the moment a pipeline
  enabled DuckLake (`AIRGAP-EXTENSIONS-1`). It now goes through `DuckDbExtension`. Since 2026-09-24 (D-8) that has
  two modes: with `-Dduckdb.extension.dir` set (every bundle launcher) it loads ONLY the staged file by path
  and a missing file fails loudly; without it, the cached `LOAD` and then `INSTALL` on a networked host.
  🔴 **That ladder covered `ducklake` and NOT the catalog backend's scanner, and the gap survived until
  2026-09-14 (`AIRGAP-PGSCANNER-LOAD-1`).** `ATTACH 'ducklake:postgres:…'` **autoloads** `postgres_scanner`
  from inside DuckLake, so no `LOAD` for it existed anywhere to grep for — and DuckDB's autoload resolves
  against its own `extension_directory`, **never** against `-Dduckdb.extension.dir`, which only
  `DuckDbExtension` reads. This product sets `extension_directory` nowhere, so the staged file was invisible
  to autoload and an air-gapped attach reached for a network `INSTALL` anyway — fatal under D10 in a
  partitioned topology, i.e. the very failure the staging was added to prevent. Measured four ways against
  duckdb_jdbc 1.5.2.1: autoload over an empty extension directory fails (correct), **over the flat staged
  directory also fails**, over a `<version>/<platform>` tree succeeds, and an explicit `LOAD '<file>'`
  succeeds. Fixed by naming it: `LakehouseCatalog.backendExtension` maps `postgres:`→`postgres_scanner` and
  `mysql:`→`mysql_scanner`, and **both** attach sites — `DuckLakeRegistrar` (write) and
  `QueryExecutor.attachSharedCatalog` (read) — load it through the same ladder before attaching.
  ⛔ **The durable rule: a STAGED extension is not a LOADABLE one.** Any extension that arrives by autoload
  needs a named call site, or its staged file is dead weight. ⚠ No test caught this because every test host
  already carries the extension in `~/.duckdb/extensions`, where autoload does find it.
  (2) ⛔ **A `postgresql://` `catalog_url` does not attach at all** — DuckLake reads the value
  as a *file path*, so `ATTACH 'ducklake:postgresql://…'` fails trying to open a file of that name relative to
  the CWD. The documented example in `okf/product/layer/platform/api-integrations-ducklake.md` was that exact shape ⇒
  `AIRGAP-DUCKLAKE-PG-1`; a file catalog (`…/cat.ducklake`) was measured working end to end.
  ✅ **RESOLVED 2026-09-14 — the working spelling is `postgres:` + LIBPQ KEYWORDS**, e.g.
  `postgres:dbname=lake host=db port=5432 user=U password=P`, verified by finding the `ducklake_*` tables in
  the Postgres database and reading a row back from a second independent connection. Both URL forms
  (`postgresql://` and `postgres://`) fail; the doc now carries the working line.
  🔴 **And the failure mode above was the *milder* one.** A `catalog_url` with **no** recognised backend
  prefix does not warn at all — it silently creates a **local DuckDB file catalog** named after the whole
  string, so each node quietly gets its own private catalog while every batch reports success.
  `LakehouseCatalog.requireShared` now refuses that under `-Dinspecto.topology=partitioned`, on both the
  write and the read side, from one definition. ⚠ Registration is also no longer "non-fatal" there: it is
  fatal (D10) **and** mandatory, so an empty catalog can no longer be the only symptom of anything.
- **The warehouse query layer** is an **operator runbook** against a customer's PostgreSQL: install
  `pg_duckdb`, run the bundled `warehouse_setup.sql` (repo root; not part of the Maven build), create roles
  and views. No `pg_duckdb` code in the repo; nothing tests it; `integrations.md` carries it without an
  intent banner (corrected with this spec).
- **Object-storage export** (S3 / HDFS) **shipped 2026-09-24** (`EXPORT-1`) as the `objectstore.export`
  Job Type: on a Pipeline's commit it pushes a data-root directory to an S3-compatible Connection, skipping
  unchanged files by size + ETag and writing a manifest last (`object-storage-export.md`). A whole Space on
  S3 stays recommended **against** (no atomic rename ⇒ the crash-safe commit ordering silently changes
  meaning); HDFS only via an S3-compatible gateway. `EDITIONS.md` `OPS-05` (a *shared* object store for
  Parquet) is a different, still-open subject.

### 3.11 The SPA

Studio **Datasets** (`modules/admin/studio/datasets/`): list + editor over `DatasetConfig`; columns, measures
and calculated-column editors; `calculated-column-guard.ts` mirrors `ExpressionGuard` from the contract JSON
(not authoritative); `bind-shared-dataset.dialog.ts` builds a `physical` Dataset with `physicalRef =
shared/<owner>/<item>` for an Exchange grant; `dataset-registration.service.ts` registers a pipeline's store as
a Dataset idempotently (`putIfAbsent` by `physicalRef`, never throws). **Query Library** (`/studio/queries`):
both types authored; `$`-parameters SQL-only; *Run saved* calls `POST /queries/{id}/run`; the draft Run resolves parameters **client-side** and previews through
`DatasetRowsService` → `/db/query` (one page, `DEFAULT_ROW_LIMIT=1000`, `truncated` flag); a `503` on save is
explained (write root), a `422` is not specially handled. **Widget explorer** → `DatasetResultService` →
`/bi/query`, with a Time grain select feeding `QuerySpec.grains`. `DatasetRowsService` is **the single rows
seam** (Catalog split S2); three sample-row folds (`EntityProjectionGraphSource`, Geo point/route,
`ReconExecService`) are correct and must stay. ⚠ No `materialize` action; no timezone display handling.
