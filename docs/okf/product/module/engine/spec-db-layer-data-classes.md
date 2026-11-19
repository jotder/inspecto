---
type: Reference
title: Database / persistence layer — Three data classes and the operational store inventory
description: Part 1 of 4 of Database / persistence layer: Three data classes; Operational store inventory
tags: [persistence, duckdb, postgres, schema, topology, stores]
timestamp: 2026-07-16T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-engine
---

# Database / persistence layer — Three data classes and the operational store inventory

Part 1 of 4 of [Database / persistence layer](spec-db-layer.md) · next: [Schemas (operational, non-Parquet)](spec-db-layer-schemas.md).

## 1. Three data classes

Inspecto has **no ORM and no single database**. Persistence is a thin *Store SPI* pattern, and there
are three physically distinct kinds of state:

| Class | What it is | Where it lives | Engine |
|---|---|---|---|
| **Business data** | Ingested rows — the records you actually process | Hive-partitioned Parquet/CSV under `<dataDir>/<store>/**` | Files on disk; queried through DuckDB `read_parquet`/`read_csv`. **Not a database.** |
| **System / config data** | Authored manifests: components, pipelines, views, connections | `registry/*.toon` files via `ComponentStore` / `PipelineStore` / `ViewStore` | Plain files, versioned in `.history/`. **No JDBC.** |
| **Operational data** | Control-plane metadata — facts about the system's *own* operation (alerts, incidents, cases, events, job runs, ingest status, acquisition ledger…) | Per-capability DuckDB files (Postgres-pluggable) + one Parquet-backed store for events | **JDBC** (DuckDB default) — this is the only relational DB layer |

Only the **operational** layer is a database in the SQL sense. This document covers it in full;
business data (the file lake) and config (TOON) are documented in
[`pipeline-graph-design.md`](../elt/architecture-elt.md) and [`configuration`](../config/admin-guide-configuration-reference.md) respectively.

### Key seams (source of truth)

| Concern | File |
|---|---|
| Connection factory (driver-by-URL-scheme) | [`util/JdbcDrivers.java`](../../../../../platform/inspecto-util/src/main/java/com/gamma/util/JdbcDrivers.java) |
| DuckDB engine helpers (ETL path) | [`util/DuckDbUtil.java`](../../../../../platform/inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java) |
| Composition root (reads `-D` toggles, opens stores) | [`service/ServiceStores.java`](../../../../../inspecto/src/main/java/com/gamma/service/ServiceStores.java) |
| Per-space file locations | [`service/SpaceRoot.java`](../../../../../inspecto/src/main/java/com/gamma/service/SpaceRoot.java) |
| Business-data read-relation builder | [`sql/SqlViews.java`](../../../../../platform/inspecto-sql/src/main/java/com/gamma/sql/SqlViews.java) |
| Dataset → physical store resolution | [`query/DatasetRelation.java`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/query/DatasetRelation.java) |

**`SqlViews` owns the read OPTIONS; nobody concatenates their own `read_*(`.** Three entry points, one
option list (`over`): `reader(format, glob, hive)`, `reader(format, List<String>, hive)`, and
`readerOverLiteral(format, sourceLiteral, hive)` for a caller that decides the *source* itself —
`ConsignmentSelector.sourceLiteral` renders exactly that shape and `DatasetRelation` consumes it.
The third overload exists because `DatasetRelation` was hand-building a bare `read_parquet(<glob>)`
with no options: it omitted `union_by_name=true`, so a store that gained a column mid-life read fine
as a `view`-backed Dataset and **failed** as a `physicalRef`-backed one — same store, two answers
(fixed 2026-08-13, pinned by an executing mixed-schema-partition test). `hive_partitioning` stays
**off** for datasets: turning it on would surface partition segments as new columns on every existing
Dataset, which is a product decision, not a bug fix.

**One SQL building path (2026-10-17, `ANALYTICS-CORE-1` M9).** Every module builds SQL text through the
shared builders, with no private helper in between (static imports such as `import static
com.gamma.util.SqlIdent.q;` are the house idiom):

| Need | Builder |
|---|---|
| A DuckDB identifier | `SqlIdent.q` (`SqlBuilder.quoteIdent` is the same function) |
| A string literal | `SqlIdent.sqlStr` |
| A file path as a literal (absolute, forward slashes, `'` doubled) | `SqlIdent.pathStr(path)`, `pathStr(dir, "/*.parquet")` for a glob |
| An identifier shown to an author in a Record Transform (bare when plain) | `SqlIdent.qIfNeeded` (the twin of `sql-functions.ts quoteIdentifier`) |
| A user-input string operand of a predicate | `SqlBuilder.stringOperand(value, params)`: bound `CAST(? AS VARCHAR)` when `params` is collected, else the literal |
| A typed operand of a Measure filter (compared in the column's own type) | `SqlBuilder.typedOperand(value, params)`: bound `CAST(? AS BIGINT / DOUBLE / BOOLEAN)` or a bare `?` for a string; rendered, numbers and booleans bare and strings escaped (`MeasureCompiler`'s filters) |
| A store read | `SqlViews.reader` / `readerOverLiteral` (union_by_name, VARCHAR hive values) |
| A read of files the caller wrote itself (index, compaction input, just-written snapshot) | `SqlViews.parquet(source, unionByName, Hive.AUTO / OFF / VARCHAR / TYPED)`, `SqlViews.parquetFile(path)` |

A name that must be validated before it is quoted keeps a validator that returns it **unquoted** (e.g.
`PostgresPublishSql.safeId`, the `SAFE_IDENT` checks) and quotes through `SqlIdent.q` at the call site.
Every named site is now on the path (2026-10-17): `MeasureCompiler` and `AnomalyScoreEvaluator` quote
through `SqlIdent.q` and `SqlBuilder.typedOperand`. The Parquet / partition summarizers (`ParquetSummarizer`,
`PartitionSummarizer`, `SummarizeSupport`) were first moved up to `inspecto-sql` onto `SqlViews.parquet(..., true,
Hive.AUTO)` and then **deleted on 2026-10-18** (D-AN30): no caller in the repo, no `main`, no ServiceLoader or
config reference (grep over every tracked file), so a hand-built `read_parquet(` has no place left to live.
The Measure grammar has one evaluator: `WorkingSetMeasures` (geo-link) loads the measured column of a Working
Set relation into a sealed in-memory DuckDB table and runs `MeasureCompiler`'s SELECT over it.

**The rule is counted, and the count may only fall (2026-10-17, `ANALYTICS-CORE-1` M10).**
[`tools/check-data-ops-duplication.mjs`](../../../../../tools/check-data-ops-duplication.mjs) inventories,
per module with `file:line`, every copy of a data operation that has a shared home: (a) private SQL
quoting helpers (`q`, `lit`, `sqlStr`, `ident`, `escapeIdent`, `sqlIdent`…) outside `SqlBuilder` / `SqlIdent`,
and every **inline** quote doubling — `.replace("'", "''")` (a literal, or a `COPY … TO '<path>'` rendered by
hand) and `.replace("\"", "\"\"")` (an identifier), one finding per call, its member the enclosing method, (b) hand-built
`read_*(` in a Java string literal outside `SqlViews`, (c) private median / percentile / MAD / z-score /
Jaro-Winkler / Jaccard / union-find outside the Analytics Kernel and the graph library, (d) a JVM switch
over the Measure aggregate names outside `MeasureCompiler`. It reads tracked `src/main/java` only, with
comments stripped, and states that scope on every run. Its baseline at the start (43 / 22 / 8 / 1; 0 / 0 / 2 / 0
after the M8/M9 lanes of 2026-10-17 — the two left are the Jaccard and Jaro-Winkler copies, Should item S1;
the inline count added to (a) the same day re-opened it at 35 — 18 of them in `inspecto-engine` — and the
retirement lane took it back to 0 with byte-identical SQL) is
[`tools/data-ops-duplication.baseline.json`](../../../../../tools/data-ops-duplication.baseline.json); the CI
guards job runs `--ratchet`, which fails when a module's count rises or an exemption goes stale. When a copy
is retired, lower the baseline with `--baseline-write`; never raise it.
⚠ **Exemptions are never inferred.** An HTML/XML/CSV/JSON escaper or a token validator that happens to be
named `esc` / `quote` / `ident`, and an ingest reader that opens a raw input file with its own parse
options (`DuckDbCsvIngester`, `ComponentPreview`, `FileSampler`), is listed with its reason in
[`tools/data-ops-duplication.exemptions.json`](../../../../../tools/data-ops-duplication.exemptions.json)
after someone read the site. RFC-4180 CSV quoting doubles `"` exactly like an identifier, so each CSV
writer's method (`EventRoutes.csv`, `AuditLogRoutes.csv`, `ReportRenderer.csvCell`, `ReportJob.csv`,
`CsvIngester.SourceId.csv`) is exempted by name. ⚠ **It says what it cannot see:** quoting done some other way
than the doubling `replace` (a char loop, a regex, a `String.format` of a quote), an identifier wrapped in
`"` with no escape at all (nothing to match — `EnrichmentEngine.buildFilter`'s key and
`ConsignmentIngestStrategy.stampReferenceVersions`' `src` / `dst` moved onto `SqlIdent.q` on 2026-10-17, after a name
containing `"` was shown to give broken SQL; the reference view names and `eventTimeSource` in `EnrichmentEngine` and the `dropTable` / `dropView` / member-copy names in
`ConsignmentIngestStrategy` followed on 2026-10-18, pinned by `ConsignmentDropQuotingTest`; the remaining `"\"" + name + "\""`
wraps of scratch names in 15 classes moved to `SqlIdent.q` on 2026-11-13, closing `ANALYTICS-CORE-1`), statistics computed inline (`VolumeBaseline`'s lower median), and a robust z named
only `z` (`RobustStats.z`) are not counted, so a 0 means "no copy of the escape", not "no hand-built SQL". Plan:
[`analytics-core-plan.md`](../../../../archived-documents/plans-archive/analytics-core-plan.md) §1a, §4a.

---

## 2. Operational store inventory

Each capability owns its own interface + implementations (no shared root interface). All DB
implementations are **plain JDBC**, with hand-rolled DDL created **lazily on first open** — there is no
migration tool.

⚠ **They borrow per operation, they do not hold a connection** (changed 2026-09-14, scale-out phase A /
`OPS-03`). Each store owns a `ConnectionSource` (`inspecto-util`) and wraps every operation in
`withConn(…)`. Sizing is derived from the **URL scheme**, not configured per store: ⛔ a `jdbc:duckdb:`
URL gets **exactly one** connection — each store owns a single-writer-locked file, so a second concurrent
connection cannot take the lock, and `-Ddb.pool.size` does NOT apply to it — while `jdbc:postgresql:`
gets a HikariCP pool (`-Ddb.pool.size`, default 10; `-Ddb.pool.timeoutMs`, default 30s). Fifteen stores
each pinning one connection is fine on one node and a connection storm on twenty pods.

🔴 **What this narrowed.** Every store method used to be `synchronized` on the store, so two operations
on one store could not interleave *inside a JVM*. On a pooled (Postgres) source they now can. ⚠ That
guarantee never spanned pods, so this makes the one-JVM case match what the N-pod case always was; DuckDB
is unchanged, because its source is one connection behind one monitor. Audited 2026-09-14: exactly two
read-then-write methods are affected, both maintenance-path and both bounded —
`DbEventStore.prune` (its `DELETE` covers everything under the cutoff regardless, so only the returned
day *count* can drift) and `DbConsignmentOutputStore.markCompactedAway` (a row inserted mid-method could
be missed). Left as-is deliberately rather than wrapped in transactions: both were already racy across
pods, and the DB-decided paths (`ON CONFLICT`, the fenced `UPDATE … WHERE owner = ? AND epoch = ?`) are
unaffected because the database, not the monitor, decides them.

| Domain | Interface | DB impl | Backend toggle (`-D…`) | Default |
|---|---|---|---|---|
| Operational objects (INCIDENT / CASE / TASK) | `ops/ObjectStore` | [`DbObjectStore`](../../../../../features/inspecto-ops/src/main/java/com/gamma/ops/DbObjectStore.java) | `objects.backend=db\|postgres\|memory` | **`db`** (was `memory` until 2026-09-25 — `OBJECTS-BACKEND-DEFAULT-MEMORY-1`; `postgres` = Enterprise, mandatory, fail-closed; `memory` = explicit opt-in) |
| Correlation links | `ops/link/LinkStore` | [`DbLinkStore`](../../../../../features/inspecto-ops/src/main/java/com/gamma/ops/link/DbLinkStore.java) | `objects.backend` (shared) | **`db`** |
| Notes / evidence | `ops/note/NoteStore` | [`DbNoteStore`](../../../../../features/inspecto-ops/src/main/java/com/gamma/ops/note/DbNoteStore.java) | `objects.backend` (shared) | **`db`** |
| Events (append-only facts) | `event/EventStore` | [`ParquetEventStore`](../../../../../platform/inspecto-event/src/main/java/com/gamma/event/ParquetEventStore.java) *(Parquet, not JDBC)* · [`DbEventStore`](../../../../../platform/inspecto-event/src/main/java/com/gamma/event/DbEventStore.java) *(JDBC, since 2026-09-12)* | `events.backend=memory\|parquet\|db\|postgres\|jdbc:…` | `memory` |
| Ingest status / audit projection | `etl/StatusStore` | [`DbStatusStore`](../../../../../inspecto/src/main/java/com/gamma/service/DbStatusStore.java) | `status.backend=file\|db` | **`db`** (flipped 2026-08-31; this row said `file` until 2026-09-08) |
| Job-run reporting | *(class is the API)* | [`DbJobRunStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/job/DbJobRunStore.java) | `jobs.backend=none\|duckdb\|postgres` | `none` |
| Pipeline-run provenance (per-edge counts) | *(class is the API)* | [`DbProvenanceStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java) | `provenance.backend=none\|duckdb\|postgres` | `none` |
| Acquisition / dedup ledger + export watermark | `acquire/AcquisitionLedger` | [`DbAcquisitionLedger`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/DbAcquisitionLedger.java) | `acquire.ledger.backend=memory\|db` *(via `AcquisitionLedgers`, not `ServiceStores`)* | `memory` |
| Consignment output-file registry | *(class is the API)* | [`DbConsignmentOutputStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/consignment/DbConsignmentOutputStore.java) | `consignment.outputs.backend=none\|duckdb\|postgres` | **`duckdb`** — the only default-on store; see below |
| Step Receipts (X5, `consignment_steps`, 2026-11-17) | *(class is the API: `recordSteps` / `steps` on the registry)* | [`DbConsignmentOutputStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/consignment/DbConsignmentOutputStore.java) — same file as the row above | rides `consignment.outputs.backend`; `-Dconsignment.steps=false` stops the writes | **on** with the registry; 30-day retention (§3.9-a) |
| Per-file stage-progression registry (Phase 4 §2.4) | *(class is the API)* | [`DbFileStageStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/consignment/DbFileStageStore.java) | `file.stages.backend=none\|duckdb\|postgres` | `none` |
| Windowed record-dedup ledger (D-9) | *(class is the API)* | [`DbDedupLedger`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/consignment/DbDedupLedger.java) | `dedup.ledger.backend=none\|duckdb\|postgres` | **`duckdb`** — default-on like `consignment_outputs`: a default-off dedup ledger silently emits the duplicates it was configured to drop; costs nothing while no pipeline declares `scope: window(...)` |
| Fleet-wide inbox registry (`INBOX-REGISTRY-CROSS-POD-1`) | *(class is the API)* | [`DbInboxRegistry`](../../../../../inspecto/src/main/java/com/gamma/service/DbInboxRegistry.java) | `inbox.registry.backend=none\|duckdb\|postgres\|jdbc:…` | `none` — off, the inbox audit compares only the Spaces this pod hosts, exactly as before |
| **Alert records** (`ALERTS`, P7-INCIDENTS slice 2, 2026-10-07) | `alert/AlertStore` (behind the `alert/AlertRecords` port) | [`DbAlertStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/alert/DbAlertStore.java) · [`InMemoryAlertStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/alert/InMemoryAlertStore.java) | `alerts.backend=db\|memory` (+ `alerts.db.url/.user/.password`; PostgreSQL through the shared `-Dinspecto.db=postgres`) | **`db` on EVERY edition, Personal included** — the operator decision that Personal keeps Alert history and restart-safe de-duplication. `memory` is the explicit opt-out (the test reactor pins it). ⚠ A single-tenant (`SpaceRoot.legacy()`) root with no `-Dassist.write.root` has no directory of its own, so it degrades to memory, loudly, rather than create `inspecto-alerts.db` in the CWD |
| Pipeline execution watermarks | `pipeline/exec/PipelineWatermarkStore` | **none** — in-memory/file only | — | — |

> **Alerts fired by an Alert Rule are their own table since 2026-10-07** (§3.14 `inspecto_alerts`, the `ALERTS` family).
> Incidents, cases and tasks — and the ALERT objects the Event bridge opens for a sequence gap or a conservation
> imbalance — are still rows in `inspecto_ops_objects`, discriminated by the `object_type` column
> ([`ObjectType`](../../../../../platform/inspecto-workflow/src/main/java/com/gamma/workflow/ObjectType.java): `INCIDENT, CASE, TASK`;
> `ALERT` was retired 2026-10-08 — a row of any type the build does not know, e.g. a legacy `ALERT`, loads inert: listed and
> readable, never rewritten or dropped, every write on it refused). Pre-existing still-active ALERT objects are adopted into the Alert store once at boot (`AlertMigration`); the
> object rows are left untouched.

Every backend **degrades gracefully**: a failed DB open falls back to in-memory/file and logs a
warning rather than blocking startup.

> **Why `consignment_outputs` defaults on** *(2026-08-10, addressing D1 — at the time the only one; since then the dedup ledger (D-9, 2026-09-01) and `status` (2026-08-31) default on too — `okf/product/layer/platform/plan-data-plane.md` §3.8)*. `consignment_outputs` was the only
> row above that opens without being asked, and the reason is a bug, not the addressing feature it was
> built for. [`ReprocessCommand`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/inspector/ReprocessCommand.java)
> refuses to reprocess a Consignment whose output a compaction merged away — re-ingesting rows that still
> exist inside the merged file **duplicates them silently** — and that refusal is decidable only from this
> table's `COMPACTED_AWAY` rows. Default-off meant the fix was switched off in every deployment. Turning it
> on changes nothing a reader sees: every read is still a filesystem glob, and the table is consulted only
> where the alternative is guessing. `=none` remains supported and a failed open still degrades to no
> registry — **optionality is part of the contract**, which is why any future reader must *filter* a file
> list it obtained elsewhere rather than *produce* one. A file with no row here is unknown, never absent.
>
> Operator-visible consequence: a reprocess that used to succeed while duplicating rows now **fails** with a
> refusal.

---
