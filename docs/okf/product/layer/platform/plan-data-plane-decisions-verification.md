---
type: Capability
title: Data plane (DAT) — Decisions, not built, refused and verification
description: Part 3 of 3 of Data plane (DAT): Decisions; Not built; Refused & superseded; As-built mechanism (pointers only); Verification
tags: [dat, capability, dataset, query, parameters, result-set, bi-query, sql-guard, expression-guard, matrix, materialize, duckdb, postgres, operational-db, ducklake, warehouse, timezone]
timestamp: 2026-09-08T00:00:00Z
---

# Data plane (DAT) — Decisions, not built, refused and verification

Part 3 of 3 of [Data plane (DAT)](plan-data-plane.md) · previous: [Specification](plan-data-plane-specification.md).

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
| 2026-09-07 | Delivery receipts default **`none`** — the opposite call | default-on would mint a DB file in the CWD of every Basic install |
| 2026-08-14 (PG-1) | One selection `-Dinspecto.db`; it **moves, never enables**; unhonourable Postgres **fails at boot**; the driver is a **sidecar**, not a Maven profile | deployments had come up healthy with stores silently off; a profile would gate a runtime dependency |
| 2026-08-15 | The operational-DB screen **never writes** | the process serving the UI is the one that needs the DB; a `PUT` would be a second declaration of the same fact |
| 2026-09-07 (operator) | `PostgresStateStoreTest` needs a server you supply; skips **per test** | an `@BeforeAll` assumption prints "Tests run: 0" and hides the absent coverage |
| 2026-09-06 (operator) | **Postgres multi-user PARKED** until a multi-operator install exists — park lifted 2026-09-15 (the trigger fired), work postponed 2026-10-23 (next release ships without Postgres) | no consumer |
| 2026-08-28 (operator) | Object-storage export: **analysis kept, not scheduled**; whole Space on S3 recommended **against** | no atomic rename on S3 changes the crash-safe commit's meaning |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| **Postgres multi-user** — pool, `browseConnection()` removal, schema-per-Space and the concurrency tests are built | `BACKLOG.md` §3 *Postgres multi-user* (park lifted 2026-09-15; ⏸ postponed 2026-10-23, hold: operator); `EDITIONS.md` OPS-03 | a real two-host run and an hours-long deployment soak |
| `graph` / `spatial` / `search` / `api` query types; more `$`-resolvers | no board row (deleted 2026-11-12 as obsolete) | Deliberately not built |
| §7.4 rollup cache (deliberately unbuilt); `generation` deleted 2026-09-24 | `okf/product/module/engine/spec-db-layer.md` §3.9 — no board row | Until read-time aggregation is measurably slow |
| `retire_superseded` must be configured or a recompute keeps an extra copy forever; `DatasetRelation.temporalColumn` has no caller | `BACKLOG.md` §3 *Consignment addressing* | |
| `DatasetAccess` after the Consignment Selector; a fragment guard for third-party `LOWERED` steps | `BACKLOG.md` §3 *Platform Services Stage 2 / 3* | |
| Reference-Dataset promotion export; "the engine has no grouping transform" | `BACKLOG.md` §3 *Unification W4 / W5* | |
| The `batch_id` DDL / `__batch_id` / `.toon` key trio | `okf/product/module/api/plan-api-stability.md` release notes | ✅ Landed 2026-11-17 (MoSCoW S4) |
| Studio analytics step processors `SP-BI-*` | `EDITIONS.md` board | No BACKLOG row |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-NOPROOF-1`, `SPEC-DEADSEAM-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** `POST /queries/{id}/run` **ADOPT** (a Run action per saved query); materialization **ADOPT** (a Materialize action + one committed example job) → `BACKLOG.md` §3 `STUDIO-HALVES-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| ✅ ~~The Query Library never calls `POST /queries/{id}/run`~~ — resolved: *Run saved* calls it (`QueriesService`) | | |
| ✅ ~~No UI triggers `materialize`; no committed job schedules one~~ — resolved 2026-09-14: Materialize in the Dataset editor, `POST /datasets/{id}/materialize`, `orders_by_region_materialize_job.toon` (§3.6) | | |
| **`memory_limit` had no default in code** while two pages said it did | `DuckDbUtil.memoryLimit` → DuckDB default; `BACKLOG.md` GAP-4 | Corrected in both pages with this spec; ✅ GAP-4 then shipped a code default 2026-09-26 |
| **The `transform.dim.scd2` processor, `valid_to`, feed effective dates and per-row as-of joins are unbuilt**; `load: scd2` itself IS honoured (version store + single-instant `as_of`) | `ProcessorCatalog` `transform.dim.scd2` PLANNED; `ReferenceScd2CharacterizationTest` | A fact cannot be joined to the dimension as of its own event time |
| ~~**`DuckLakeRegistrar` has no test**~~ — wrong: `DuckLakeRegistrarTest`, `…IdempotentTest`, `…PerSinkTest` and `…RegisterWiringTest` exist (`platform/inspecto-etl/src/test/java/com/gamma/etl/`); its non-fatal promise stays unproven (§8). The warehouse runbook has no code | `inspecto-etl` test tree; `warehouse_setup.sql` at the repo root | |
| ~~**The Postgres test covers 9 of 12 families**~~ — 15 of 16 today (re-counted 2026-11-15), only Alerts uncovered | `PostgresStateStoreTest` and `PostgresOpsStoreTest` methods | The dedup ledger, acquisition ledger and delivery receipts this row named as uncovered all have a round-trip now (§3.8) |
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

Zero demand (checked 2026-08-26); ⛔ do not re-open on speculation. The sandbox loads no extensions.

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
the DDL rename needs `ALTER TABLE` plus a `payload` blob rewrite and rides the MAJOR (it landed for 4.0.0 on 2026-11-17, MoSCoW S4).

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
| `status.backend` default `file` | `db` (2026-08-31) — corrected in `db-layer.md` §2 with this spec | `OperationalDb.java` |
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
| The three data classes, the store roster and schemas, per-Space topology, Postgres, the CWD traps, proving DAT-6 | `docs/okf/product/module/engine/spec-db-layer.md` (`Reference`, 53 KB) | eleven source files via its `PostToolUse` hook | §3.1, §3.8 — the largest Reference in the bundle |
| Appender ingest, threads, D11, the three timezone rules, aggregate state | `docs/okf/product/module/engine/spec-duckdb.md` (`Concept`) | `DuckDbUtil.java` | §3.7 |
| Query as a Component, `/queries/{id}/run`, calculated columns, time grains | `docs/okf/product/module/processor/spec-queries.md` (`Concept`) | `com.gamma.query` | §3.3–§3.5 |
| The store-layout contract, `sinks:`, quarantine | `docs/okf/product/module/elt/spec-sinks.md` (`Concept`) | `inspecto-etl` | §3.2 |
| The Consignment Selector, revisions, `retire_superseded` | `docs/okf/product/module/elt/spec-consignments.md` (`Concept`) | `inspecto-engine` | §3.2 |
| `materialize`, `retire_superseded` as Job / task types | `docs/okf/product/module/processor/spec-jobs.md` (`Concept`) | `com.gamma.job` | §3.6 |
| DuckLake and the warehouse runbook | `docs/okf/product/layer/platform/api-integrations-ducklake.md` (`Reference`) | `DuckLakeRegistrar.java` | §3.10 — ⚠ runbook, not feature |
| Object-storage export (`objectstore.export`) | `docs/okf/product/module/elt/spec-sinks.md` (`Reference`) | `ObjectStoreExportJobType.java` | §3.10 |
| Retention and the operational flags | `docs/okf/product/layer/platform/admin-guide-operations-reference.md` (`Reference`) §Retention | — | the operator's view |
| Studio Datasets, Query Library, the rows seam | `docs/okf/product/layer/product/user-guide-studio.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/studio/` | §3.11 |
| The Data Browser | `docs/okf/product/layer/product/user-guide-catalog.md` (`Feature`) §Data Browser | — | §3.9 |
| ⚠ **No page owns `SqlSandbox` / `SqlGuard` / `SqlOracle` as a subject**, no page owns `DatasetRelation` beyond `db-layer.md`'s seam table, and no frontend page exists for Datasets or the Query Library outside `studio.md` | *(gap)* | `platform/inspecto-sql/src/main/java/com/gamma/sql` · `platform/inspecto-engine/src/main/java/com/gamma/query` | §3.2–§3.3 of this spec |

---

## 8. Verification

> 🔴 **One promise here is not provable in this reactor, and it is worth knowing before trying.**
> `DuckLakeRegistrar`'s class javadoc promises that, in a `single` topology, "any connectivity or SQL
> failure is caught and logged without aborting ETL success for the file" — load-bearing, because the
> ingest lane (`ConsignmentIngestor`, through `DuckLakeRegistrar.register`) runs it inside the batch's
> **durable finalisation**, where a throw would demote a batch whose data is already written (the
> pipeline-job lane, `PipelineJobRunner`, calls `registerInto`). Measured 2026-09-09: the reachability and guard tests cost **0.4 s**, while two tests
> that drove an *enabled* block cost **262 s**, because `INSTALL ducklake FROM core` is a **network**
> fetch that runs before any failure the promise covers, and `DuckDbUtil.jdbcUrl` exposes no settings
> hook to make it fail fast. ⛔ So it needs a reachable catalog — a live deployment, not the reactor.
> `SPEC-NOPROOF-1` closed on 2026-09-09 counting the reachability and guard tests, and since D10
> (2026-09-14) `DuckLakeRegistrarTest` also tests the per-topology decision (`onRegistrationFailure`)
> directly; the real driver's failure stays unproven, untracked until 2026-11-16, now the P3 board row
> `DUCKLAKE-REAL-DRIVER-FAILURE-PROOF-1`. Adding 4.4 minutes to an offline build would be a defect, and a
> `@Tag`-excluded test that never runs is not a guard.

### 8.1 Relations, guards, compiler — `inspecto-engine` · `inspecto-sql` (default reactor)

| Class | Proves |
|---|---|
| `DatasetRelationTest` | view / `physicalRef` forms; `physicalRefWithDatabaseSubtreeReadsMappedOutputOnly`; the mixed-schema-partition read (`union_by_name`); calculated wrap |
| `ExpressionGuardTest` · `ExpressionGuardContractTest` | the three rules, window-`OVER` gating; the contract JSON both sides read |
| `SqlGuardTest` · `SqlSandboxTest` · `SqlOracleTest` | statement guard; open / seal settings; the sealed oracle |
| `DuckLakeRegistrarTest` *(new 2026-09-09)* | that the DuckLake catalog step is **reachable** — `output.ducklake` → `cfg.output().duckLake()`, and the single-destination shorthand sink inheriting it — plus every no-op guard (empty outputs, absent block, `enabled: false`, absent flag). ⚠ It does **not** prove the *non-fatal* promise: see §8.3 |
| `MeasureCompilerTest` · `MeasureCompilerGrainExecutionTest` · `MeasureGrammarContractTest` | `AGGS`, grains; the compiled SQL **executed** in the sandbox; the cross-language grammar |
| `ParametersTest` · `QueryExecutorBindsTest` | `$` resolution incl. verbatim unknowns; positional binds |
| `MaterializeTaskTest` | compile / snapshot, the atomic swap, the registered `dataset` |
| `ViewStoreTest` · `OutputFormatTest` · `PartitionWriterTest` · `PartitionWriterFormatTest` | sink views; the write side DAT reads |

### 8.2 Routes and stores — `inspecto/src/test/java/com/gamma/`

`ControlApiQueryRunV1Test` (the route, `422` on structured, limits), `ControlApiBiQueryTest` (spec compile,
`422` grain, TEXT buckets), `ControlApiDbBrowserTest` (`/db/*`, limits, guard), `OperationalDbTest` (the
sixteen-family roster, `verifySelectable`, the `-Dinspecto.db` selection pinned across families),
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
at the repo root (253 lines, outside the build). `jobs/`: one committed `materialize` job (`orders_by_region_materialize`) and one `retire_superseded` job (`retire_superseded_job.toon`, 2026-09-23) that ships **DISABLED** — enabling retirement deletes bytes, so it is an operator call; the demo therefore still takes the WARN path.

### 8.5 Guards

The root `pom.xml` surefire pins on the three default-on families (§3.8); `SqlGuard` and `ExpressionGuard` at
run time; `SchemaFieldTypes` at load (`MET`); `expression-guard` and `measure-grammar` contract pairs;
`check-vocabulary` keeps *Data Store* and *Metric* (BI sense) out of the docs. <!-- vocab-allow: names the banned words the guard removes -->

### 8.6 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| ✅ ~~No client-side test of `/queries/{id}/run`~~ — `queries.component.spec.ts` covers *Run saved* | §2 |
| ✅ ~~No test of a materialization from the product~~ — `ControlApiDatasetMaterializeTest` drives the route | §3.6 |
| **The warehouse runbook has no code**; `DuckLakeRegistrar` has four test classes but none proves its non-fatal promise (§8) | `inspecto-etl` test tree; repo root |
| **Postgres coverage is opt-in and partial** — 15 of 16 families (all but Alerts), only with a server | §3.8 |
| **No test that the DuckDB session zone equals the host zone** — pinned by ⛔ comments at three call sites | `duckdb.md` |
| **No test that `retire_superseded` is ENABLED anywhere** — only a WARN; the demo job ships disabled on purpose, pinned by `DemoRetireSupersededJobShipsDisabledTest` | §3.2 |
| **No per-fact-row as-of join, `valid_to` or feed effective date for `load: scd2`** (the version store and single-instant `as_of` are built and tested) | §3.2 |
