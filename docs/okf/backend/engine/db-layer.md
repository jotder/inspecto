---
type: Reference
title: Database / persistence layer
description: How state is stored on disk — the three data classes, the operational store inventory and relational schemas, the per-space file topology, and how to run operational data on Postgres.
resource: inspecto-ops/src/main/java/com/gamma/ops
tags: [persistence, duckdb, postgres, schema, topology, stores]
timestamp: 2026-07-16T00:00:00Z
---

# Database / Persistence Layer
> **Deep reference — the detail tier.** Start at [Engine section index](index.md) for the summary; this page is the long form it points to. *(Moved from the retired root-level `DB_LAYER.md` (docs consolidation, 2026-07-16).)*

> **Scope:** how Inspecto stores state on disk — the three data classes, the operational
> (relational) table schemas, the per-space file topology, and how to run operational data on
> Postgres. Vocabulary follows [`GLOSSARY.md`](../../../GLOSSARY.md) (**Store** = physical backend,
> **Dataset** = queryable relation).

> **⚠️ Keep this current.** This doc is derived from the source files listed below — when any of
> them changes (a table's DDL/columns, a store's backend wiring, the per-space file layout, a
> `-D*.backend` toggle, or Postgres behavior), update the matching section here (and
> [`archived-documents/plans-archive/db-browser-design.md`](../../../archived-documents/plans-archive/db-browser-design.md) if browsable tables/stores
> change). A `PostToolUse` hook (`.claude/hooks/post-tool-db-layer-doc.sh`) reminds you on edits to
> these files. Source of truth for the DDL is each store's `initSchema()` — keep the SQL blocks in §3
> byte-accurate.
> **Derived from:** `ops/DbObjectStore` · `ops/link/DbLinkStore` · `ops/note/DbNoteStore` ·
> `service/DbStatusStore` · `job/DbJobRunStore` · `pipeline/exec/DbProvenanceStore` ·
> `acquire/DbAcquisitionLedger` · `event/ParquetEventStore` · `service/ServiceStores` ·
> `service/SpaceRoot` · `util/JdbcDrivers` · `util/DuckDbUtil`.

---

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
[`pipeline-graph-design.md`](../pipeline-graph/pipeline-graph-design.md) and [`configuration`](../config/configuration.md) respectively.

### Key seams (source of truth)

| Concern | File |
|---|---|
| Connection factory (driver-by-URL-scheme) | [`util/JdbcDrivers.java`](../../../../inspecto-util/src/main/java/com/gamma/util/JdbcDrivers.java) |
| DuckDB engine helpers (ETL path) | [`util/DuckDbUtil.java`](../../../../inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java) |
| Composition root (reads `-D` toggles, opens stores) | [`service/ServiceStores.java`](../../../../inspecto/src/main/java/com/gamma/service/ServiceStores.java) |
| Per-space file locations | [`service/SpaceRoot.java`](../../../../inspecto/src/main/java/com/gamma/service/SpaceRoot.java) |
| Business-data read-relation builder | [`sql/SqlViews.java`](../../../../inspecto-sql/src/main/java/com/gamma/sql/SqlViews.java) |
| Dataset → physical store resolution | [`query/DatasetRelation.java`](../../../../inspecto-engine/src/main/java/com/gamma/query/DatasetRelation.java) |

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
| Operational objects (ALERT / INCIDENT / CASE / TASK) | `ops/ObjectStore` | [`DbObjectStore`](../../../../inspecto-ops/src/main/java/com/gamma/ops/DbObjectStore.java) | `objects.backend=db\|postgres\|memory` | **`db`** (was `memory` until 2026-09-25 — `OBJECTS-BACKEND-DEFAULT-MEMORY-1`; `postgres` = Enterprise, mandatory, fail-closed; `memory` = explicit opt-in) |
| Correlation links | `ops/link/LinkStore` | [`DbLinkStore`](../../../../inspecto-ops/src/main/java/com/gamma/ops/link/DbLinkStore.java) | `objects.backend` (shared) | **`db`** |
| Notes / evidence | `ops/note/NoteStore` | [`DbNoteStore`](../../../../inspecto-ops/src/main/java/com/gamma/ops/note/DbNoteStore.java) | `objects.backend` (shared) | **`db`** |
| Events (append-only facts) | `event/EventStore` | [`ParquetEventStore`](../../../../inspecto-event/src/main/java/com/gamma/event/ParquetEventStore.java) *(Parquet, not JDBC)* · [`DbEventStore`](../../../../inspecto-event/src/main/java/com/gamma/event/DbEventStore.java) *(JDBC, since 2026-09-12)* | `events.backend=memory\|parquet\|db\|postgres\|jdbc:…` | `memory` |
| Ingest status / audit projection | `etl/StatusStore` | [`DbStatusStore`](../../../../inspecto/src/main/java/com/gamma/service/DbStatusStore.java) | `status.backend=file\|db` | **`db`** (flipped 2026-08-31; this row said `file` until 2026-09-08) |
| Job-run reporting | *(class is the API)* | [`DbJobRunStore`](../../../../inspecto-engine/src/main/java/com/gamma/job/DbJobRunStore.java) | `jobs.backend=none\|duckdb\|postgres` | `none` |
| Pipeline-run provenance (per-edge counts) | *(class is the API)* | [`DbProvenanceStore`](../../../../inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java) | `provenance.backend=none\|duckdb\|postgres` | `none` |
| Acquisition / dedup ledger + export watermark | `acquire/AcquisitionLedger` | [`DbAcquisitionLedger`](../../../../inspecto-acquire/src/main/java/com/gamma/acquire/DbAcquisitionLedger.java) | `acquire.ledger.backend=memory\|db` *(via `AcquisitionLedgers`, not `ServiceStores`)* | `memory` |
| Consignment output-file registry | *(class is the API)* | [`DbConsignmentOutputStore`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/DbConsignmentOutputStore.java) | `consignment.outputs.backend=none\|duckdb\|postgres` | **`duckdb`** — the only default-on store; see below |
| Per-file stage-progression registry (Phase 4 §2.4) | *(class is the API)* | [`DbFileStageStore`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/DbFileStageStore.java) | `file.stages.backend=none\|duckdb\|postgres` | `none` |
| Windowed record-dedup ledger (D-9) | *(class is the API)* | [`DbDedupLedger`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/DbDedupLedger.java) | `dedup.ledger.backend=none\|duckdb\|postgres` | **`duckdb`** — default-on like `consignment_outputs`: a default-off dedup ledger silently emits the duplicates it was configured to drop; costs nothing while no pipeline declares `scope: window(...)` |
| Fleet-wide inbox registry (`INBOX-REGISTRY-CROSS-POD-1`) | *(class is the API)* | [`DbInboxRegistry`](../../../../inspecto/src/main/java/com/gamma/service/DbInboxRegistry.java) | `inbox.registry.backend=none\|duckdb\|postgres\|jdbc:…` | `none` — off, the inbox audit compares only the Spaces this pod hosts, exactly as before |
| Pipeline execution watermarks | `pipeline/exec/PipelineWatermarkStore` | **none** — in-memory/file only | — | — |

> **`ALERT`s are not their own table.** Alerts, incidents, cases and tasks are all rows in
> `inspecto_ops_objects`, discriminated by the `object_type` column
> ([`ObjectType`](../../../../inspecto-engine/src/main/java/com/gamma/objects/ObjectType.java): `ALERT, INCIDENT, CASE, TASK`).

Every backend **degrades gracefully**: a failed DB open falls back to in-memory/file and logs a
warning rather than blocking startup.

> **Why `consignment_outputs` defaults on** *(2026-08-10, addressing D1 — at the time the only one; since then the dedup ledger (D-9, 2026-09-01) and `status` (2026-08-31) default on too — `okf/capabilities/data-plane/data-plane.md` §3.8)*. `consignment_outputs` was the only
> row above that opens without being asked, and the reason is a bug, not the addressing feature it was
> built for. [`ReprocessCommand`](../../../../inspecto-engine/src/main/java/com/gamma/inspector/ReprocessCommand.java)
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

## 3. Schemas (operational, non-Parquet)

Exact DDL as created by each store's `initSchema()`. All columns are `VARCHAR`/`BIGINT` only (no
engine-specific types), ids are application-generated strings (no auto-increment), and timestamps are
epoch-millis `BIGINT`. `attributes`/`payload` columns hold JSON serialized as text.

Legend: **A** = append-only (insert only), **M** = mutable (update/delete in place).

### 3.1 `inspecto_ops_objects` — alerts / incidents / cases / tasks  · **M**
File: `inspecto-ops.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_ops_objects (
  id             VARCHAR PRIMARY KEY,
  object_type    VARCHAR,   -- ALERT | INCIDENT | CASE | TASK
  title          VARCHAR,
  description     VARCHAR,
  status         VARCHAR,
  severity       VARCHAR,
  priority       VARCHAR,
  "owner"        VARCHAR,   -- quoted: reserved word
  assignee       VARCHAR,
  correlation_id VARCHAR,
  attributes     VARCHAR,   -- JSON
  created_at     BIGINT,    -- epoch ms
  updated_at     BIGINT,
  closed_at      BIGINT
);
```

### 3.2 `inspecto_ops_links` — correlation edges  · **A**
File: `inspecto-ops-links.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_ops_links (
  from_id      VARCHAR,
  from_type    VARCHAR,
  to_id        VARCHAR,
  to_type      VARCHAR,
  relationship VARCHAR,
  created_at   BIGINT
);
```

### 3.3 `inspecto_ops_notes` — notes / evidence  · **M**
File: `inspecto-ops-notes.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_ops_notes (
  id         VARCHAR PRIMARY KEY,
  object_id  VARCHAR,   -- FK (by convention) → inspecto_ops_objects.id
  kind       VARCHAR,
  author     VARCHAR,
  body       VARCHAR,
  attributes VARCHAR,   -- JSON
  created_at BIGINT
);
```

### 3.4 `inspecto_status_*` — ingest status / audit projection  · **A**
File: `inspecto-status.db` (legacy `ucc-status.db` auto-renamed on open)

Five append-only projection tables. `payload` is the JSON record; `seq` orders events within a pipeline.

#### `inspecto_run_lease` — cross-process run exclusion (`DbRunLease`, phase B, 2026-09-12)

```sql
CREATE TABLE IF NOT EXISTS inspecto_run_lease (
  space VARCHAR, scope VARCHAR, pipeline VARCHAR, owner VARCHAR, epoch BIGINT,
  acquired_at BIGINT, expires_at BIGINT,
  PRIMARY KEY (space, scope, pipeline))
```

🔴 **All three key columns earn their place.**
* **`space`** — a pipeline id is unique only within a Space; the in-heap guards key on the bare id only
  because there is one guard instance per Space. A shared table has no such boundary, so two Spaces with
  an `orders` pipeline would share one lease row.
* **`scope`** (`run` | `acquire`) — the engine holds **two** guards, and they are deliberately
  independent: `CollectorService.runGuard` gates pipeline runs, `PipelineScheduler.acquireGuard` gates
  remote acquisition. Operator decision 2026-09-12: **keep them separate.** ⛔ Without this column a
  remote fetch of `orders` would block a *run* of `orders`, so pipelines would mysteriously stall
  whenever an upstream was slow.
* **`pipeline`** — the exclusion is per pipeline, never global.

⚠ `epoch` is a **fencing token**, bumped on every acquisition. Release and heartbeat are both conditional
on `owner = ? AND epoch = ?`, so a process paused past its TTL can neither free nor extend a lease
another process has taken over. ⛔ Not a Postgres advisory lock (D5): those die with the connection, and
the connection pool recycles connections.

**Selected by `-Drun.lease.backend`** (`heap` default · `db` · `postgres` · a raw `jdbc:` URL), with
`-Drun.lease.db.url` / `.user` / `.password` and `-Drun.lease.owner`. ⛔ The default is `heap`, never a
database: a lease is exclusion *across processes*, and on one node the in-heap guard is both correct and
free — defaulting to a DB would create a file for every Personal install to coordinate a fleet of one.

⚠ A failure to open **degrades to the heap guard and is recorded as DEGRADED**, which
`-Dinspecto.topology=partitioned` turns into a boot failure (A1). On N pods a per-process lease is not a
weaker guarantee, it is *no* guarantee.

#### `inbox_registry` — the fleet-wide roster of declared inboxes (`DbInboxRegistry`, 2026-09-13)

```sql
CREATE TABLE IF NOT EXISTS inbox_registry (
  space VARCHAR, pipeline VARCHAR, poll_dir VARCHAR, pod VARCHAR, declared_at BIGINT,
  PRIMARY KEY (space, pipeline))
```

🔴 **Why it has to be shared state at all.** Two Spaces polling one `dirs.poll` is **silent
double-ingestion** — `MarkerManager` marks a file only *after* a batch commits, so nothing claims it before
it is read, and neither the marker nor the dedup ledger runs early enough to rescue it. `SpaceInboxAudit`
has caught the same-pod case since 2026-09-12, but once Spaces are partitioned across pods the dangerous
pairing is two Spaces on **different** pods, and **no pod can see another's config**. This table is the only
place that knowledge can meet.

⚠ **Detection, never prevention** (operator decision 2026-09-12). It turns an undetectable collision into a
loud one. ⛔ Prevention — requiring `dirs.poll` to resolve under its declaring Space's root — was **refused**:
an external vendor drop directory outside the Space tree is legitimate, common, and already in use.

🔴 **Keyed by Space, not by pod, and that is what makes it self-healing.** `publish` REPLACES every row a
Space owns, so a Space moved to another pod rewrites its rows on that pod's next boot instead of leaving a
ghost that collides with itself. ⛔ Keying on the pod would produce exactly that false finding — the worst
failure a detector has, because an operator who learns to ignore it has lost the real one too. ⚠ A Space
with **no** declarations still publishes: that is when the delete matters.

⚠ **Write-time hooks were deliberately NOT added**, and this deviates from the operator's own wording
("written at config-write time"). The registry is published at **boot**, where the full roster is known;
`ConfigWriteRoutes.writeConfig`/`patchConfig` and bundle import are three more seams that would each see
only their own change. ⇒ A `dirs.poll` added by a config write is detected on that pod's **next boot** —
which is also when the pre-existing local audit runs.

🔴 **Known gap: a Space DELETED outright leaves its rows behind**, because deletion happens where nothing
publishes. `pod` and `declared_at` make such a finding diagnosable; clearing it is manual
(`DELETE FROM inbox_registry WHERE space = …`). ⛔ Do not "fix" it with a TTL — an inbox declaration has no
natural lifetime, and a pod that is merely down would then vanish from the roster this table exists to fill.

**Selected by `-Dinbox.registry.backend`** (`none` default · `duckdb` · `postgres` · a raw `jdbc:` URL), with
`-Dinbox.registry.db.url` / `.user` / `.password` and `-Dinbox.registry.pod` (a label on the row, not an
identity anything keys on). ⚠ Opened **once per pod against the spaces root**, not once per Space — comparing
Spaces is the whole point — so its per-root DuckDB default file can only ever repeat what the local audit
already knows. Only a URL pointing at shared Postgres makes the cross-pod finding possible.

⛔ **Fail-open at every seam, and never silent about it.** A failed open, write or read is a WARN, and the
audit falls back to this pod's own Spaces — the pre-registry behaviour. An empty read is treated as
*unreadable*, not as *healthy*: the pod has just published into it, so an empty roster cannot be true.

#### `inspecto_events` — the shared event store (`DbEventStore`, D6, 2026-09-12)

```sql
CREATE TABLE IF NOT EXISTS inspecto_events (
  event_id VARCHAR, ts_ms BIGINT, level VARCHAR, type VARCHAR, source VARCHAR,
  pipeline VARCHAR, correlation_id VARCHAR, message VARCHAR,
  attributes VARCHAR, payload VARCHAR)
CREATE INDEX IF NOT EXISTS inspecto_events_ts ON inspecto_events (ts_ms)
```

⚠ Column names mirror `ParquetEventStore`'s exactly, so an operator reading one backend's raw table reads
the other's unchanged. `attributes` and `payload` are JSON strings (`JsonAttributes`), as in Parquet.
⛔ Append-only, like every `EventStore`: no update, no row delete. `prune(before, dryRun)` deletes whole
UTC days and returns the number of **days** — not rows — so both durable backends report the same unit
(Parquet deletes day partitions). The boundary is exclusive: an event ON the cutoff day is retained.

```sql
CREATE TABLE IF NOT EXISTS inspecto_status_commits    (pipeline VARCHAR, batch_id VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_batches    (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_files      (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_lineage    (pipeline VARCHAR, batch_id VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_quarantine (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_unpack     (pipeline VARCHAR, seq BIGINT, payload VARCHAR);

CREATE INDEX IF NOT EXISTS inspecto_status_commits_by_pipeline          ON inspecto_status_commits    (pipeline);
CREATE INDEX IF NOT EXISTS inspecto_status_batches_by_pipeline          ON inspecto_status_batches    (pipeline, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_files_by_pipeline            ON inspecto_status_files      (pipeline, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_lineage_by_pipeline_batch    ON inspecto_status_lineage    (pipeline, batch_id, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_quarantine_by_pipeline       ON inspecto_status_quarantine (pipeline, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_unpack_by_pipeline           ON inspecto_status_unpack     (pipeline, seq);
```

⚠ **The index set is derived from the predicate set, not chosen** (`DB-STATUS-INDEX-1`, 2026-09-17). Every read
against these tables goes through the `StatusStore` seam — `committedBatches` and `readRows` in `DbStatusStore`
are the only two SQL sites, and the list endpoints (`/runs/{p}/batches`, `/lineage`, `/quarantine`,
`ReportService`, `MetricsService`) all reach them through it — so the predicates are closed: `WHERE pipeline = ?`
everywhere, `AND batch_id = ?` on lineage only, `ORDER BY seq` on the five payload tables. The leading `pipeline`
column also serves `deletePipeline` (the per-sync DELETE) and `renamePipeline`. `seq` trails deliberately: on
Postgres the composite lets the sort be read off the index, while DuckDB's ART index serves the `pipeline`
lookup and sorts after — the extra column is free there rather than a DuckDB-only bet.
`DbStatusStoreTest.everyListPredicateHasABackingIndexOnTheColumnsItFilters` pins the exact (table → columns)
set in both directions, so a new predicate without an index and an index without a predicate both fail.

⚠ **These two `batch_id` columns are deliberately NOT renamed** (consignment-ELT plan §11.3, slice 3 took the
ledgers-and-manifest split only). Since 2026-08-04 the source ledgers spell the column `consignment_id` and
`Csv.readInto` canonicalises the legacy header, so `DbStatusStore` reads the row key **`consignment_id`** while
writing it into the column still named **`batch_id`**. The asymmetry is intentional: renaming a column in existing
`.duckdb` files needs an `ALTER TABLE … RENAME COLUMN` migration, and the `payload` blob embeds the literal too —
tracked in `BACKLOG.md` §4. The `payload` JSON now carries `consignment_id` for newly synced rows.

### 3.5 `inspecto_job_runs` — job-run reporting  · **A**
File: `jobs_report.duckdb`

```sql
CREATE TABLE IF NOT EXISTS inspecto_job_runs (
  run_id      VARCHAR,
  job         VARCHAR,
  type        VARCHAR,
  "trigger"   VARCHAR,   -- quoted: reserved word
  start_time  VARCHAR,   -- ISO-8601 string
  end_time    VARCHAR,
  status      VARCHAR,
  duration_ms BIGINT,
  message     VARCHAR
);

-- X2 cross-lane provenance (2026-09-02): which Consignments an at-rest run READ.
CREATE TABLE IF NOT EXISTS inspecto_job_run_sources (
  run_id         VARCHAR,
  consignment_id VARCHAR,
  pipeline       VARCHAR,   -- the producer, from consignment_outputs.producer; NULL when it had none
  table_name     VARCHAR    -- the source_store the view read
);
CREATE INDEX IF NOT EXISTS inspecto_job_run_sources_by_consignment ON inspecto_job_run_sources (consignment_id);
```

⚠ **This is a PROJECTION, not the audit — and that is why its write failures stay log-only**
(`JOBRUN-STORE-SWALLOWED-WRITES-1`, decided 2026-09-17). The record of a job run is
`jobs_runs.csv`, written by `JobRunLedger.record` and read back by `lastStartTimes` /
`lastSuccessTime` for misfire catch-up. `DbJobRunStore.record` and `recordSources` log and swallow on
failure by design — the same standing as `file_stages`, the deliberate best-effort index — because a
missed row costs reporting fidelity a re-projection repairs, not a compliance record, and no caller
could act on a throw. **The audit surface is one level up**: a failed `jobs_runs.csv` append now emits
an `audit.write_failed` Signal at `ERROR` (`AuditWriteSignal`, payload `{audit, subject, error}`) so
the gap is operator-visible on `/signals` and matchable by an Alert Rule — and still does not throw,
because the run has already completed and failing it would be worse than a recorded gap.
⛔ Do not escalate the two projection sites to the same Signal: one per missed projection row would
drown the one that means a record was lost. `StoreHealth` is deliberately **not** the channel — it
records what a store *opened* as, one replaceable entry per family, and throws under
`-Dinspecto.topology=partitioned`.

**`inspecto_job_run_sources` — one Consignment, one trail across the Stage-1 → Stage-2 boundary.** Until
X2 the `pipeline_config:` job's run was linked to its input Consignments only by convention
(`output_store:` + the store path); an operator could not ask *which chain runs consumed consignment X*.
Now the at-rest readers (`PipelineJobRunner`, `SqlTemplateJob`) report, per `source_store` view, the
Consignments behind the files the **selector actually kept** — `SourceStoreReader.registerView` returns
`ConsignmentSelector.Resolution.kept()`, mapped through `DbConsignmentOutputStore.sourcesForPaths`
(LIVE rows only, `norm()` on both sides) — via `JobContext.readConsignments`; `RunContext` collects and
`JobService` writes them here beside the run row. Read both ways: `GET /jobs/runs/{runId}` gains
`derivedFrom[]` (`{consignmentId, pipeline, tableName}`), and `GET /runs/{name}/outputs?consignmentId=`
gains `derivedRuns[]` (the same nine-column projection `recentRuns` serves).

Three decisions worth keeping: (1) **a child table, not a column on the run row** — the linkage is a
LIST, `JobRun` is nine scalars built at seven call sites, and the reverse question is one indexed lookup
here rather than a `LIKE` over a joined string (the shipped `replay:<runId>` linkage could ride the
`trigger` column only because it is one scalar). (2) **The registry, not the rows, is the source.** The
plan assumed `DISTINCT __batch_id` over the rows read; grounding showed ordinary output files carry NO
per-row batch id (only the SCD2/reference write path stamps `__batch_id`) — the file-level
`consignment_outputs` registry is the only place the identity exists for a general store, which is also
what makes retire/supersede honest (a SUPERSEDED file is excluded from the read AND the trail).
(3) **Unknown is not empty.** With the registry off the selector reads the raw glob and `kept` is `null`;
the readers then report NOTHING and both routes omit the key rather than serving `[]` — the plan's
"analyses nothing" precondition, applied: a deployment that cannot know must never read as "derived from
nothing". The CSV `jobs_runs` ledger is untouched (still the nine-column record of the run); only the DB
projection carries provenance.

### 3.6 `inspecto_pipeline_provenance` — per-edge row counts  · **A**
File: `provenance.duckdb`

```sql
CREATE TABLE IF NOT EXISTS inspecto_pipeline_provenance (
  pipeline_id VARCHAR,
  batch_id    VARCHAR,
  node_id     VARCHAR,
  rel         VARCHAR,
  row_count   BIGINT,
  run_ts      VARCHAR
);
```

`initSchema()` follows the CREATE with `ALTER TABLE inspecto_pipeline_provenance ADD COLUMN IF NOT EXISTS
simulated BOOLEAN DEFAULT FALSE` (PIPELINE-DRYRUN-1, 2026-09-18) — a row from a whole-pipeline dry run
(`JobContext.dryRun()`) is written with `simulated = true` so `GET /provenance` can render it distinctly
instead of indistinguishably from a real run's counts; a row from before the column existed reads back
`false` (a real run).

### 3.7 `inspecto_acquisition_ledger` + `_db_watermark` — acquisition dedup  · ledger **M**, watermark **M**
File: `inspecto-acquisition.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_acquisition_ledger (
  source_id      VARCHAR,
  relative_path  VARCHAR,
  name           VARCHAR,
  size           BIGINT,
  checksum       VARCHAR,
  etag           VARCHAR,   -- added in place for pre-ACQ-7 ledgers
  object_version VARCHAR,   -- (named to avoid the reserved word `version`)
  last_modified  BIGINT,
  processed_at   BIGINT,
  status         VARCHAR,
  PRIMARY KEY (source_id, relative_path)
);

CREATE TABLE IF NOT EXISTS inspecto_acquisition_db_watermark (
  source_key      VARCHAR,
  watermark_value VARCHAR,
  advanced_at     BIGINT,
  PRIMARY KEY (source_key)
);
```

### 3.8 Events — append-only, Parquet (not a SQL table)  · **A**

`ParquetEventStore` writes rolling **Hive-partitioned Parquet** under
`<eventsDir>/level=/year=/month=/day=/` (level FIRST — the severity filter is partition-pruned; an earlier
version of this line omitted `level=`), read back through an in-memory DuckDB connection (`evt_buf` is only a
transient write buffer). **Retention (COMPLY-3, 2026-09-02):** `EventStore.prune(before, dryRun)` deletes
whole `day=` partitions older than the cutoff (UTC, the flush's own frame) and collapses emptied parents; the
`event_prune` maintenance task drives it with a required `retention_days` (operator window: one year). The
in-memory backend answers `-1` — nothing durable. The event record shape:

```
event_id, ts_ms (BIGINT), type, source, pipeline, correlation_id,
message, attributes (JSON), payload (JSON), level  -- + partition cols year, month, day (VARCHAR)
```

`level` ∈ [`EventLevel`](../../../../inspecto-event/src/main/java/com/gamma/event/EventLevel.java). There is **no
JDBC/Postgres event table** — events were Parquet-only. ⚠ **That changed 2026-09-12 (D6 / phase A3):**
`DbEventStore` adds `events.backend=db`. The Parquet layout below is unchanged and remains the default
durable backend for a single node; the database backend exists because Parquet is written by exactly one
process, so on N pods each replica sees a different Signal ledger.

### 3.9 `consignment_outputs` — per-output-file registry  · **M**
File: `inspecto-consignment-outputs.db`

```sql
CREATE TABLE IF NOT EXISTS consignment_outputs (
  consignment_id VARCHAR,
  run_id         VARCHAR,
  table_name     VARCHAR,
  partition_key  VARCHAR,
  record_day     VARCHAR,  -- superseded by the bounds below; taken from them when a file's event times share a day
  path           VARCHAR,
  row_count      BIGINT,   -- (the plan sketch calls this `rows`; `ROWS` is a SQL keyword)
  bytes          BIGINT,
  written_at     VARCHAR,
  state          VARCHAR,  -- LIVE | SUPERSEDED | COMPACTED_AWAY | SIMULATED (PIPELINE-DRYRUN-1 — a dry-run
                            -- "would-have" row: no bytes written, `path` a placeholder; excluded from every
                            -- state='LIVE' readability/selection check, so it can never be read as real data
  schema_fingerprint VARCHAR,  -- §3.4.3 CanonicalHash of the schema that wrote the file; NULL pre-column / no-schema paths
  event_time_min VARCHAR,      -- addressing §3.1: ISO-8601 LOCAL, no zone offset
  event_time_max VARCHAR,
  event_time_spread_ms BIGINT, -- max - min; NULL (not 0) when bounds are unknown
  producer       VARCHAR,      -- the pipeline that wrote the file, for the §3.6 per-stream watermark
  UNIQUE (consignment_id, path, run_id)
);
```

`initSchema()` follows the CREATE with `ALTER TABLE consignment_outputs ADD COLUMN IF NOT EXISTS` for
`schema_fingerprint` and for each of the four addressing columns — the additive migration for registries
created before they existed (CREATE TABLE IF NOT EXISTS never widens an existing table). Pre-migration rows
read back `NULL`. It then runs `ALTER TABLE consignment_outputs DROP COLUMN IF EXISTS generation` — the one
**subtractive** migration (see below); it runs before the constraint check, so a pre-constraint registry is
rebuilt without the column and a constrained one loses it in place.

⛔ **`generation` was DELETED end to end on 2026-09-24** (record field, column, upsert, reader). Grounded
before the cut: every writer (`ConsignmentOutputs.build`, `DerivedTableWriter`, `SummaryWriter`,
`DryRunSinkWriter`) passed a literal `0`; the only reader was `DbConsignmentOutputStore.map` copying it into a
record field whose accessor no main-code path called; no SQL string, route (`RunRoutes` provenance omits it),
openapi entry or UI surface mentioned it. The semantics it was declared for are already carried elsewhere —
reprocess by `state = SUPERSEDED`, compaction by `COMPACTED_AWAY`, revisions by batch-id file names
(`consignment-addressing.md`, which records why a counter was refused), and torn-read safety by
`ConsignmentSelector` pinning an **enumerated file list**, not a generation. ⛔ Do not re-add a counter
"for pinning": the Selector never needed one. Pinned by
`DbConsignmentOutputStoreTest.aRegistryCarryingTheRetiredGenerationColumnLosesItOnReopen`.

**Deliberately unbuilt** (carried here when the *Consignment ELT* board row was retired with the column,
2026-09-24): the §7.4 rollup cache, until read-time aggregation is measurably slow; the §7.3 unpartitioned
fallback stands by operator call. The per-schema outputs decision shipped 2026-09-15 (this table made
per-schema accurate; `batches` stays one row per ingest). ⛔ Pointers to `CONSIGNMENT-OUTPUTS-NULLRUN-1` are
dead (refuted 2026-09-15).

✅ **`UNIQUE (consignment_id, path, run_id)` SHIPPED 2026-09-13** (Run model slice 4) — one Consignment
writing one path in one Run is **one file**, and `record` is
`INSERT … ON CONFLICT (consignment_id, path, run_id) DO UPDATE SET <every non-key column> = excluded.…`.

🔴 **`DO UPDATE`, not `DO NOTHING`, and that is the whole decision.** One Run can legitimately write one
`path` twice: two sinks may target one store (`PartitionSinkWriter:115` sums `rowsByStore`) and
`PartitionWriter:171,226` reveals each partition under a stable `<baseName>_out.<ext>` with
`OVERWRITE_OR_IGNORE`, so the second write carries a **different `row_count` for the same path**. The file on
disk really was overwritten, so last-writer-wins matches the filesystem while `DO NOTHING` would keep a count
for content that no longer exists. ⚠ This is the **opposite** choice from `file_stages` (§3.10),
deliberately: a stage is an immutable fact about a point in time, an output row describes a **mutable file**.
⚠ `state` is in the SET list too — a re-revealed file is LIVE again, and leaving a `COMPACTED_AWAY` flag
standing over content that now exists is the same stale-row defect from the other direction.

🔴 **That reasoning is filesystem-specific, and the object-store lane does not satisfy it** (added
2026-09-14 with scale-out §5.4 bullet 1, `PartitionWriter.writeToObjectStore`). On an `s3://` target
there is no staging and **no reveal at all** — a partitioned `COPY` writes straight to its final keys —
and a repeat write **ACCUMULATES rather than overwrites**: measured against MinIO, two runs left **6**
objects where the local lane's atomic reveal leaves 3. `FILENAME_PATTERN` pins the stem but DuckDB
appends its own index, so the object lane **cannot reproduce the stable `<baseName>_out.<ext>` name**.
⇒ the premise above — *"the file on disk really was overwritten, so last-writer-wins matches the
filesystem"* — is **false there**: each run writes a **new `path`**, so `DO UPDATE` never fires for it
and every run inserts its own row. ⛔ Do not "unify" the two lanes on the strength of the local one's
idempotence: re-running a batch is idempotent locally and additive on an object store, which is a
semantic difference, not a naming detail. ⚠ Nothing in production reaches that lane yet —
`dirs.database` refuses a URI at both the jail and the 422 gate until the credentials decision
(`BACKLOG.md` §1) lands — so this is a property to preserve when it does, not a live behaviour.

⚠ **`run_id` is deliberately still nullable**, even though every production path has supplied one since
slice 3. `NULL ≠ NULL` in a UNIQUE constraint on both DuckDB and Postgres, so a null-run row is **exempt**
from the key — but `NOT NULL` would be strictly worse than the exemption: `record` is fail-open, so a
violation would drop a *landed* file's row into a WARN, and a registry written before slice 3 could not be
rebuilt at all (its legacy rows have no run identity, and inventing one would report a Run that never
existed). The remaining exemption is the five-arg `EnrichmentEngine.runResult` overload that only tests call,
pinned by its own test so it stays visible.

**Measured cost (2026-09-13, DuckDB 1.5.2.1, throwaway harness).** Per row, steady from 500 to 8 000 rows:
plain `INSERT` ~610 µs · **UNIQUE constraint + plain `INSERT` ~740 µs (+15 %)** · `ON CONFLICT DO NOTHING`
~7 000 µs · `ON CONFLICT DO UPDATE` ~7 200 µs.

✅ **Three readings that matter, because the headline number misleads.** (1) The **index is nearly free**;
the ~11× belongs to the `ON CONFLICT` clause, not to the key. (2) **`DO UPDATE` costs the same as
`DO NOTHING`** — so §3.9's write-semantics decision is free, and nobody should "optimise" it back to
`DO NOTHING` for speed, which would buy nothing and reintroduce the stale-count defect. (3) The cost is
**linear, not degrading with table size** — the registry accumulates forever, so a super-linear cost would
have been disqualifying. It is not.

🔴 **But this is the FIRST default-on store to pay it.** `file_stages` (§3.10) has carried the same
`ON CONFLICT` cost since 2026-09-12 and is **default-off**; `consignment_outputs` is default-**on**, so every
deployment now pays ~7 ms per output-file row on the commit path. ⚠ Best-effort and off the data path (the
files have already landed), and at real sizes — one row per output file per Consignment, typically tens —
that is tens to a few hundred ms. ⛔ Revisit if a deployment writes thousands of output files per
Consignment; the remedy is batching or DuckDB's Appender, **not** dropping the conflict clause.

⚠ Absolute throughput above is **harness-bound**, not production throughput: ~610 µs for a plain insert is
far slower than DuckDB manages natively (JDBC `addBatch`, not the Appender API). The **ratios** are sound —
both arms shared the harness — the rows/second figures are not; do not quote them as capacity.

**Migration.** A pre-constraint table is **rebuilt on open**, the `file_stages` idiom (§3.10): the additive
`ADD COLUMN IF NOT EXISTS` widening runs **first** (the rebuild copies every column by name, so it can only
run once a legacy table has them all), then `RENAME TO consignment_outputs_v1` → create →
`INSERT … SELECT … ON CONFLICT DO NOTHING` → drop, in one transaction — DuckDB has no `ADD CONSTRAINT`.
⚠ The collapse is `DO NOTHING` here and `DO UPDATE` at write time, which is not an inconsistency: at
migration time there is no "later" writer, the rows are already interleaved and the table carries no column
that orders two rows for the same key, so keeping the first arrival is the only choice that fabricates
nothing.

🔴 **The legacy NULL-`run_id` rows are copied by a SECOND statement, and that is load-bearing** (probed on
DuckDB 1.5.2.1, 2026-09-13, after a test caught it). The constraint really does treat NULLs as distinct
*between* statements — two single-row inserts of the same null-run key both land — but
`INSERT … SELECT … ON CONFLICT DO NOTHING` **also de-duplicates the incoming rows against each other, and
there NULL matches NULL**. So a single-statement migration silently drops every repeat of a null-run key:
the exact data loss the nullable-`run_id` decision exists to avoid, arriving through the migration instead
of the constraint. ⛔ Do not merge the two inserts back together.

**How a Run id gets here** (slice 3, 2026-09-13): [`RunIds`](../../../../inspecto-engine/src/main/java/com/gamma/job/RunIds.java)
is the **single** generator — a second one at the Collector was refused so "run id" could not mean two
things depending on the path. Work outside `JobService` carries
[`StandaloneRunContext`](../../../../inspecto-engine/src/main/java/com/gamma/job/StandaloneRunContext.java),
a deliberately inert `JobContext` (no log, no signals, no artifacts) that exists to carry identity, not to
make a CLI invocation look scheduled. ⚠ **One Run spans a whole poll cycle, not one Consignment** —
`GLOSSARY.md` §6-A binds `Run ⊇ Consignment ⊇ File`, so `CollectorProcessor.ingest` mints one id and every
batch in that cycle shares it.

**Null bounds mean *unknown*, never *empty*.** A consumer that prunes on bounds must treat a null-bounds row
as a **possible match**, or it will silently drop data. Two write paths can fill them, each from its own
declaration, and neither ever guesses which column is temporal:

| Path | Bounds come from | Absent when |
|---|---|---|
| Ingest (`ConsignmentIngestor`) | `__event_time`, the coerced column `DataTransformer` materialises from the schema's date partition and excludes from written output | the schema declares no date partition, or every row failed to parse |
| Pipeline sink (`PartitionSinkWriter`) | `TRY_CAST(<source> AS TIMESTAMP)`, where `source` is a `partitions[]` entry's declared raw column — the same word `PartitionDef.source` uses | no entry declares a `source`, entries disagree on it, or it is not a plain identifier |

Enrichment writes and every row predating these columns still read back `NULL`.

**`record_day` is derived from those bounds where it can be** (addressing step 10) — from the file's real event
times when `min` and `max` share a day, else from the `year`/`month`/`day` partition segments as before. Bounds
win when the two disagree, since a partition value may have been cut in another timezone or off another column.
⚠ **Read `bounds`, not `record_day`**: one day per file cannot express a file that straddles two, which is what
an interval is for. Nothing in the engine reads the column today; it survives because it is in the schema and
cheap.

**The per-day read over these columns is `DbConsignmentOutputStore.dailyVolume`** — files + rows per
(pipeline, record-day), the completeness KPI's K1, shipped `31e00005`. ⚠ It has **no production caller**:
K4, the job that was to call it, is on hold. ⛔ Two contracts a caller must honour, or the number lies: a
day **absent** from the series is **not a zero** (absence covers both "received nothing" and "was not
expected to run"), and a **null-`bounds` sink reads UNKNOWN, never 0**. Design of record:
`okf/capabilities/observability/observability.md` §3.9.

**`supersedeOtherRevisions(table, keep)` is scoped the opposite way to `supersede(consignment)`**, and has to
be: a full recompute invalidates work it did not do, spread across however many earlier runs wrote that store
(addressing step 6). The `keep` argument is required, not optional — a call that omitted it would mark the
recompute's own freshly written files stale and empty every read of the table. It flips state only; the bytes
go later, via the `retire_superseded` maintenance task, so a read already in flight finishes on the revision
it started with.

**Two readers, and a rule they share.** `unreadablePaths()` returns every path marked `SUPERSEDED` or
`COMPACTED_AWAY` for `ConsignmentSelector` to subtract from a glob — but **never a path that also has a
`LIVE` row**. Output naming is not one-file-per-Consignment: a full recompute rewrites a stable path in
place, so one path legitimately owns an old dead row and a current live one, and returning it would drop live
data from every read. Row state is per-registration; readability is per-path.

**The two non-live states are opposites to a reader that aggregates.** `producerHighWater(table)` — the
per-producer `max(event_time_max)` the §3.6 Watermark folds — filters `state <> 'SUPERSEDED'` but keeps
`COMPACTED_AWAY`: compacted rows describe data that was genuinely delivered and still exists inside the merged
file, so dropping them would make the watermark travel **backwards** when a partition is compacted, while
superseded rows were replaced by a reprocess and would claim delivery the current data no longer supports. It
also cannot `max(written_at)` as text — that column is `Instant.toString()`, whose fractional digits vary, so
`…33.1Z` sorts *after* `…33.12Z`; it casts to a timestamp and projects epoch millis instead. `event_time_max`
is safe to `max()` as text only because §3.1 writes it in a fixed-width format.

The durable output registry from the
[consignment-ELT plan](../../../archived-documents/plans-archive/consignment-elt-architecture.md) §11.3 — the catalog substitute
its no-catalog decision implies, answering *"every file this Consignment wrote, across all partitions"* with
lifecycle state attached.

**Two things to know before using it.** (1) `consignment_id` is deliberately **not** `batch_id`: GLOSSARY §13
bans *Batch* for this concept, and a table born after that decision starts correct instead of needing the
migration the legacy CSV/manifest artifacts do. (2) **The per-Consignment JSON manifest stays authoritative
for a file's *existence*; this table is authoritative for its *state*.** The store is default-off and
`ServiceStores` degrades a failed open to `null`, so a store that can legitimately be absent must never be
the only record that a file exists — never read a missing row as proof of a missing file.

**Who writes it (slice 2, 2026-08-04).** Three paths, reached through `ConsignmentOutputStores` — a per-space
ambient registry (the `AcquisitionLedgers` idiom, needed because the write paths are `static`), whose `record()`
no-ops when the store is absent so no call site branches on default-off:

| Path | Hook | Where `row_count` comes from |
|---|---|---|
| Ingest (+ routed rules, multi-destination fan-out) | `ConsignmentIngestor.finalizeSource`, **after** the manifest write | `LineageCollector`'s matrix, summed per output file |
| Enrichment | `EnrichmentEngine.runResult` (routed files register from their own relation) | `ConsignmentOutputs.countByPartition` |
| Pipeline sinks | `PartitionSinkWriter.write` | `ConsignmentOutputs.countByPartition` (replaced its old whole-table `COUNT(*)`) |
| §7.3 summaries | `ConsignmentProcessJobType` after `SummaryWriter` reveals the files | the number of summary rows in that partition |

🔴 **`table_name` is the SCHEMA that wrote the file on a segmented ingest, not the batch's single table** (`batches` vs a per-schema row set, decided 2026-09-15). A segmented pipeline writes one output set PER SCHEMA, but `ConsignmentIngestor` pools them into one flat `outputs` list and used to stamp every row with `batch.table()` — so this table, which already IS the per-Consignment child table, could not answer *which schema wrote what*; a file's segment survived only inside its PATH. `IngestOutcome.schemaByOutput` (output file → segment key) now carries the attribution the write already knew.
⚠ It is collected in **two** places because the segmented ingesters are not alike: `UnionModeIngester` has the segment key in its own write loop, while `GenerationModeIngester` writes per MEMBER through `DuckDbRecordSink`, which fans out to segments internally — there the pairing exists only inside `generationFlush(Seg)`. Miss either and that path silently keeps the flattened value.
⚠ A file with no attribution keeps the batch's table, which is every file on a single-schema path — so the default ingest is unchanged. ⛔ And `batches` itself is untouched: it stays **one row per ingest** by decision, with the per-schema detail here rather than in a second ledger.

⚠ **Summary rows use `table_name = "<target>__summary"`, and the suffix is load-bearing.**
`GuardedSummaryEmitter.reconcile` sums detail `row_count` **by table name**, so registering a summary under the
target's own name would inflate the detail total and silently break §7.2's reconciliation. Filter on the suffix to
separate the derived summary tier (`<dataDir>/_summaries/<target>/record_day=…`) from detail outputs.

`row_count` is never a field copy — `PartitionWriter.reveal()` supplies only `(partition, outputFile, bytes)`,
because a partitioned `COPY` reports no per-file count back. **`record_day` is currently derived from the
partition key's `year`/`month`/`day` segments and is `null` for any other scheme** — a write-time approximation
that plan §10.1's pinned-timezone event-time-at-load must replace, not fall back to. ✅ `run_id` **is no
longer null** (2026-09-13): every production path now carries a Run identity distinct from its unit of
work — see §3.9's *"How a Run id gets here"*.

Reads return **all** states, not just `LIVE` — hiding `COMPACTED_AWAY` would conceal exactly the case the
registry exists to expose.

**Who changes state (2026-08-04).** Both mutators are `UPDATE`-only; only `record()` ever creates a row, so a
state flip cannot resurrect a file the registry never saw. Both are best-effort (logged, never thrown), the same
fail-open contract as `record()`.

| Mutator | Caller | Contract |
|---|---|---|
| `markCompactedAway(paths)` | [`PartitionCompactor`](../../../../inspecto-engine/src/main/java/com/gamma/job/PartitionCompactor.java), **after** the reveal + cleanup | Path-keyed, because one merged file absorbs many Consignments. **Inserts no replacement row** — no single `consignment_id` owns the merged file, and `(state, partition_key)` is all §6.2's partition rewrite needs |
| `supersede(consignmentId)` | [`ReprocessCommand`](../../../../inspecto-engine/src/main/java/com/gamma/inspector/ReprocessCommand.java), beside `ManifestStore.supersede` | Moves **only `LIVE`** rows; a `COMPACTED_AWAY` row keeps that state, since it is the evidence that tells a reprocess to rewrite the partition rather than unlink a path that is gone |

This closes a **silent data-duplication bug**: `ReprocessCommand` used to `deleteIfExists` a path compaction had
already unlinked (a no-op), restore the members, and re-ingest rows still present inside the merged file. It now
**refuses** when any row is `COMPACTED_AWAY`. ⚠ Only when the registry is enabled — default-off deployments still
need `min_age_days` beyond the reprocess horizon, and get a warning naming the risk instead.

**Two gotchas when querying this table.** (1) **`(consignment_id, path)` is not unique.** Batch ids are
`yyyyMMdd_HHmmss` (second granularity) and output file names are deterministic, so a reprocess finishing inside
the same second produces one `SUPERSEDED` and one fresh `LIVE` row sharing a path. (2) **`path` is stored with
the caller's own spelling, relative or absolute** — `record()` deliberately does not normalise, because
absolutising at write time would make the row depend on the writing process's working directory. Matching
therefore normalises *both sides in Java*; a two-spelling SQL `WHERE` cannot do it, since an already-absolute
probe normalises to itself.

### 3.10 `file_stages` — per-file stage-progression registry  · **M**
File: `inspecto-file-stages.db`

```sql
CREATE TABLE IF NOT EXISTS file_stages (
  source_id      VARCHAR,
  relative_path  VARCHAR,
  batch_id       VARCHAR,
  stage          VARCHAR,  -- FileStage: REGISTERED | MANIFESTED | OUTPUT_REGISTERED | BACKED_UP | MARKED | WATERMARK_ADVANCED
  recorded_at    VARCHAR,
  UNIQUE (batch_id, source_id, relative_path, stage)
);
```

**Idempotent since 2026-09-12 (`CONSIGNMENT-ID-DETERMINISTIC-1`, constraints half):** `record` is
`INSERT … ON CONFLICT DO NOTHING`, so a retried transition or a second executor of the same Consignment
leaves one row (the first write's `recorded_at` wins). Insert-only is what makes the key safe — there is
no state transition to collide with, unlike `consignment_outputs` (§3.9), which is **deliberately still
unconstrained** — and, as of 2026-09-12, *provably* cannot be constrained yet (§3.9). A pre-constraint table is
**rebuilt on open** — `RENAME TO file_stages_v1` → create → `INSERT … SELECT … ON CONFLICT DO NOTHING`
→ drop — in one transaction, because DuckDB has no `ADD CONSTRAINT` (probed 2026-09-12: partial
indexes and `ALTER … ADD CONSTRAINT` are both "not supported"). The already-migrated check is
`information_schema.table_constraints`, portable to Postgres.

Phase 4 §2.4's per-file stage progression: one row per `(source_id, relative_path)` file at each
boundary `ConsignmentIngestor.finalizeSource` genuinely crosses, so *"where is file X right now"* is a
query instead of a re-read of the manifest and a guess about how far a crashed commit got.
**Insert-only** — a stage is a fact about a point in time, never updated; a file's history is its
own append-only progression through `finalizeSource`'s documented crash-safe ordering (register →
manifest → backup → markers LAST → ledger/watermark). `(source_id, relative_path)` is the same key
`AcquisitionLedger` uses.

Written by `FileStages.record`, an ambient per-space registry (the `ConsignmentOutputStores` idiom)
called from `ConsignmentIngestor.finalizeSource` after each of the six boundaries; default-off and
best-effort, same fail-open contract as `consignment_outputs` — absence means no index, never a
change to the commit ordering itself. Read by `FileStages.stages(sourceId, relativePath)`, exposed
at `GET /runs/{name}/files/stage?path=<relative>`.

### 3.11 `inspecto_dedup_keys` — windowed record-dedup ledger (D-9)  · **M**
File: `inspecto-dedup-ledger.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_dedup_keys (
    pipeline        VARCHAR NOT NULL,
    key_hash        VARCHAR NOT NULL,
    window_start    DATE    NOT NULL,
    consignment_id  VARCHAR NOT NULL,
    first_seen      TIMESTAMP NOT NULL,
    PRIMARY KEY (pipeline, key_hash, window_start)
);
```

The `scope: window(<period>)` half of `transform.dedup` (D-9): one row per
`(pipeline id, SHA-256 key hash, epoch-anchored window start)`. `claim()` is `INSERT … ON CONFLICT DO
NOTHING` and returns only the hashes it won, so **first committed wins** — the database resolves two
racing Consignments, not a check-then-insert. Keys are **hashed, never verbatim** (operator decision
2026-09-01 — an MSISDN table is a data-protection surface the other ledgers don't have), so the table
cannot say WHICH key collided; the losing rows still leave on the `duplicate` relation.
`retract(consignment_id)` releases a superseded Consignment's claims (`ReprocessCommand` step 4b —
without it a reprocess would re-ingest into "already seen" and drop every row permanently);
`prune(cutoff)` advances the window by the claims' own `window_start` (event time, never mtime) via the
`dedup_prune` maintenance task (`retention_days` required). Registered per space by `DedupLedgers`
(the `ConsignmentOutputStores` idiom), opened by `ServiceStores.openDedupLedger`; **absence is
fail-closed**, not fail-open — a windowed dedup with no ledger REFUSES at run
(`RowShaper.ExecutionContext`).

⚠ **A default-ON `URL_OR_ENGINE` family plus `SpaceRoot.legacy()` writes into the working
directory.** `legacy()` resolves every store's file CWD-relative by design (the pre-spaces
single-tenant layout), so any test booting a `CollectorService` minted this ledger — and
`inspecto-consignment-outputs.db` — at whichever *module root* surefire was forked in. Both are now
pinned to in-memory DuckDB in the **root pom's surefire `systemPropertyVariables`**
(`-Ddedup.ledger.backend=jdbc:duckdb:`, `-Dconsignment.outputs.backend=jdbc:duckdb:`), which keeps
the ledger armed against the real `DbDedupLedger` while writing nothing. It is set as the **backend**
value, not the per-family `*.db.url`: a raw `jdbc:` backend is a first-class source that both
`ServiceStores` and `OperationalDb.resolve` short-circuit on, so `urlFor` is never consulted —
setting `-Ddedup.ledger.db.url` instead defeats the shared `-Dinspecto.db` selection that
`OperationalDbTest` pins across all fifteen families (it fails that test). Tests needing durable dedup
state construct `DbDedupLedger` on an explicit `@TempDir` URL. `STATUS` is `DB_FLAG` mode (`db` |
`file`) and could not take the hatch until 2026-09-02: `ServiceStores.openStatusStore` now also reads a
raw `jdbc:` backend value as "db, at exactly this URL", so the root pom pins `-Dstatus.backend=jdbc:duckdb:`
too and `inspecto/inspecto-status.db` is no longer minted (TEST-CWD-DB-1). The `db` default and
`-Dstatus.db.url` are untouched.

---


### 3.12 `inspecto_delivery_receipts` — per-delivery status receipts (D8)  · **M**
File: `inspecto-delivery-receipts.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_delivery_receipts (
  delivery_id        VARCHAR PRIMARY KEY,
  notification_id    VARCHAR,
  channel_config_id  VARCHAR,
  target             VARCHAR,
  sent_at            BIGINT,
  status_at          VARCHAR,  -- JSON {"DELIVERED":"2000","COMPLAINED":"3000"} — see below
  provider_raw       VARCHAR,
  digest             BOOLEAN
);
CREATE INDEX IF NOT EXISTS inspecto_delivery_receipts_notification ON inspecto_delivery_receipts (notification_id);
CREATE INDEX IF NOT EXISTS inspecto_delivery_receipts_sent_at ON inspecto_delivery_receipts (sent_at);
```

One row per attempted delivery to one external destination (`DeliveryReceipt`), stamped by provider
callbacks on `/public/delivery-status/{adapterId}`. Added 2026-09-07 as **D8-SUPPRESS-1's stated
precondition** — per-recipient suppression cannot be built on `InMemoryDeliveryReceiptStore`, whose
bounded map evicts oldest-first, so the bounce that should suppress an address is the record most likely
to be gone by the next send.

⛔ **Default `none`, and that is the opposite call from `inspecto_dedup_keys` on purpose.** An absent
receipt DB is not degraded correctness — it is the shipped behaviour (receipts stay in the bounded
in-memory store). Default-ON would create a DB file in the working directory under `SpaceRoot.legacy()`
for every Personal install, and EDITIONS `CP-15` is a "not for Personal" cell.

⚠ **`status_at` is a JSON column, not a child table.** `DeliveryReceipt.statusAt` is a
`Map<DeliveryStatus,Long>` because a spam-button click produces `delivered` *then* `complaint` for one
message and a single enum would erase the earlier one. Every read wants the whole map and none wants a
single status, so a child table would buy a join per query and no expressiveness. An entry naming a
`DeliveryStatus` this build does not know is **dropped on read, never thrown on** — a receipt written by
a newer build must stay readable.

⚠ **`add` is delete-then-insert, not an UPSERT** — DuckDB and Postgres spell upsert differently and this
store runs unchanged on both. It keeps `add` idempotent, matching the in-memory `put` a resend relies on.
`stamp` is read-merge-write on the store monitor, so the "first observation of a status wins" rule stays
in `DeliveryReceipt.withStatus` rather than being re-implemented in SQL.

### 3.13 `inspecto_delivery_suppression_overrides` — operator "deliver to this address again"  · **M**
File: `inspecto-delivery-receipts.db` (the receipts' own file — one family, two tables)

```sql
CREATE TABLE IF NOT EXISTS inspecto_delivery_suppression_overrides (
  target      VARCHAR PRIMARY KEY,
  cleared_at  BIGINT,
  actor       VARCHAR
);
```

Written by `DELETE /notifications/suppressions?target=…` (operator decision 2026-09-07). 🔴 **It forgives
history up to `cleared_at`; it deletes nothing.** The bounce and complaint receipts stay, because they are
the evidence that the address was bad AND that someone chose to re-enable it. A suppressing event *after*
`cleared_at` is simply not covered, so the address re-suppresses on its own — "cleared by the next
suppressing event" is a timestamp comparison in `SuppressionList`, not state that can drift and not a job
that must run.

⚠ **A separate table, deliberately.** An override is a decision *about* history, not part of it; folding a
"forgiven" flag into the receipt row would make *"was this address ever bad"* unanswerable. It is also why
the rejected alternative — pruning the target's receipts to unsuppress — was refused: it destroys the audit
trail and would permanently mask a genuinely dead destination.

## 4. File topology (per space)

**One DuckDB file per capability** — not one shared DB, and not one file per space. Each file is
single-writer-locked (documented in `ServiceStores`). Locations come from
[`SpaceRoot`](../../../../inspecto/src/main/java/com/gamma/service/SpaceRoot.java):

| Layout | Capability file locations |
|---|---|
| **`DirSpaceRoot`** (per-space dir) | `<spaceBase>/duckdb/<file>` — e.g. `spaces/demo/duckdb/inspecto-ops.db` |
| **`LegacySpaceRoot`** (flat working dir) | `./inspecto-ops.db`, `./inspecto-ops-links.db`, `./inspecto-ops-notes.db`, `./inspecto-status.db`, `./jobs_report.duckdb`, `./provenance.duckdb`, `./inspecto-acquisition.db`, `./inspecto-consignment-outputs.db`, `./inspecto-file-stages.db`, `./inspecto-delivery-receipts.db` |

So across N spaces you get N separate sets of these files. Events live under `<dataDir>/events/`
(`DirSpaceRoot`) or `./inspecto-events/` (legacy). Every `-D<capability>.db.url` flag overrides the
per-space default explicitly — note that a global `-D*.db.url` therefore funnels EVERY space into one
shared file; leave them unset in multi-space mode so each space keeps its own `duckdb/` set.

`DirSpaceRoot` **mints `<spaceBase>/duckdb/` on first URL build** (`SpaceRoot.java`, guarded by
`SpaceRootTest`): repo-checked-out spaces gitignore `duckdb/` and DuckDB does not create parent
dirs, so without the mkdir every DB-backed store silently degraded to in-memory on a fresh checkout.

---

## 5. Running operational data on Postgres

The layer was **designed** for this: stores are JDBC-pluggable by URL scheme, the DDL is deliberately
portable (`VARCHAR`/`BIGINT`, composite PKs, no auto-increment, no upserts — explicit DELETE-then-INSERT),
and there is a **real embedded-Postgres round-trip test**
([`PostgresStateStoreTest`](../../../../inspecto/src/test/java/com/gamma/service/PostgresStateStoreTest.java),
moved to `inspecto` 2026-09-17 so it runs in the DEFAULT reactor — the four `com.gamma.ops` store cases stayed
behind as [`PostgresOpsStoreTest`](../../../../inspecto-ops/src/test/java/com/gamma/service/PostgresOpsStoreTest.java))
covering **9 of the 12 families** (note, tag assignment, job run, file stage, consignment output, status, provenance,
object, link) — **the dedup ledger, the acquisition ledger and delivery receipts are the three it does not cover**
(measured 2026-09-08; this sentence said "6 of the 9" and named two covered stores as uncovered). Both DDLs are portable by construction (`VARCHAR`/`BIGINT`/`INTEGER`, no PK, no upsert), but that
is reasoned, not proved.

### 5.0 One selection: `-Dinspecto.db` (2026-08-14)

The edition model, stated once: **Personal = embedded DuckDB for everything; Standard = PostgreSQL for
the operational stores, DuckDB retained as the non-updateable query engine over Parquet.** Business data
is never in either database — it is always Parquet.

`-Dinspecto.db=duckdb|postgres` (default `duckdb`) plus `-Dinspecto.db.url` / `.user` / `.password` is
therefore **all a Standard deployment sets**, and `OperationalDb` feeds every family in §5.1 including the
acquisition ledger. Personal sets nothing at all.

- ⚠ It selects a **connection, never an on/off switch.** Every `*.backend` toggle keeps its own default,
  so choosing Postgres *moves* stores rather than enabling capabilities nobody asked for.
- ⚠ **A per-family `*.db.url` still wins** — that is what keeps existing deployments byte-identical, and
  it is the escape hatch for pointing one store elsewhere. Credentials resolve the same way, per key, so
  an overridden `status.db.user` does not drag the shared password with it.
- ⛔ **An unhonourable `postgres` selection now fails at boot** (`SpaceManager.discover` →
  `OperationalDb.verifySelectable`), naming the missing property or driver. It used to be caught *per
  store*, logged WARN, and leave that store `null` — so a deployment pointed at Postgres came up
  "healthy" with job reporting, provenance and Objects **switched off rather than moved**. The shipped
  `inspecto.jar` bundles **no JDBC driver** (`inspecto-engine/pom.xml`: *"runtime stays JDBC-driver-free
  by design"*) and that stays true — **the driver rides the Standard/Enterprise bundle as the
  `postgresql.jar` sidecar** (PG-1 Open 1, decided 2026-08-14): `package.ps1` copies it from the local
  Maven repo (version = the parent pom's `postgresql.version`), and `serve.sh`/`serve.bat` auto-detect
  it exactly as they do `inspecto-security.jar`. ⛔ The considered alternative — an edition Maven profile
  gating the dependency — was rejected: it would be the first edition seam gating a *runtime dependency*
  rather than a ServiceLoader SPI, and the sidecar mechanism already existed. The classpath entry is
  inert until `-Dinspecto.db=postgres` selects it, and the serve scripts honour a `postgresql.jar`
  dropped beside `inspecto.jar` on **any** bundle (the boot-failure message says exactly that).
- **The password is a `SecretResolver` reference, resolved at use** (PG-1 Open 2's password half,
  decided 2026-08-14): `-Dinspecto.db.password` (and any per-family `*.db.password`) takes
  `${ENV:PGPASSWORD}`, `${KEYSTORE:alias}` (the existing `secrets.keystore.*` machinery), `${FILE:…}`,
  or a literal — the `auth.oidc.clientSecret` precedent, so the secret need not sit on the process
  command line. A literal passes through unchanged, which is what keeps existing deployments working.
  ~~⚠ Open 2's **UI half stays open**~~ — **SHIPPED 2026-08-15, see §5.0-a.**

### 5.0-a The read-and-validate surface (Open 2, 2026-08-15)

**The UI reports and validates; it never writes.** Two routes, both `canConfigureAccess`:
`GET /system/operational-db` (the effective config, per family, **with the source of each value**) and
`POST /system/operational-db/test` (open the URL for real, run `SELECT 1`, return a **named** outcome —
`OK` / `DRIVER_MISSING` / `AUTH_FAILED` / `UNREACHABLE`, because "the driver is missing" means *drop
`postgresql.jar` beside `inspecto.jar`*, a different action from bad credentials). Surface:
Settings ▸ **Operational database**.

🔴 **There is deliberately no PUT, and adding one is not a follow-on.** The process serving the UI is the
one that needs the database, so it cannot configure its own dependency and no change could take effect
without a restart — and persisting from the UI would create a **second declaration of the same fact**
beside `-D`, the split-brain the enrichment companion already refused (D7). Decided 2026-08-15: the
operator applies flags through their own deployment tooling; this screen tells them what is in force.

- ⛔ **Two ledger homes were rejected for the keyed dedup window, and the reasons still bind.** The
  file-grained output ledger keys on `(consignmentId, runId, tableName, partitionKey, path)` and
  has **no column for a business key**, so carrying key hashes there would be a new table shape, not a new
  column. And **manifests are the crash-recovery record of existence, not a query surface**. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*
- ⚠ **Adding a `Family` is a COMPILING change, not a config toggle** — a label, a `*.backend` property, a
  default, a `Mode`, url/user/password properties and a root supplier. Budget it.
- **`OperationalDb.Family` is now the roster** — the **fifteen** families' property names live there and nowhere
  else, so the store openers and the report cannot drift; naming a family off the list stops compiling.
  ⛔ They had been ten **string literals** across `ServiceStores` + `SpaceBootstrap`.
  ⚠ This bullet read **fourteen** until 2026-09-16; `INBOX_REGISTRY` (scale-out §5.3) was added to the enum
  without it. The count is `Family.values()` and nothing else — see §5.0-b, which depends on it.
- ⚠ **Three irregularities the report models rather than flattens:** three different "is it on" spellings
  with three different defaults (`none` / `duckdb` / `memory` / `file`); a **`*.backend` starting with
  `jdbc:` IS the URL** and bypasses `OperationalDb` entirely (a third source beyond per-family and
  shared); and **URL grain ≠ credential grain** — the four `objects.*` families each carry their own
  `*.db.url` but share one `objects.db.user`/`.password`.
- ⛔ **A family that sends no credentials reports `user: null`, never the shared one.** Five families
  (`JOB_RUNS`, `PROVENANCE`, `CONSIGNMENT_OUTPUTS`, `FILE_STAGES`, `ACQUISITION_LEDGER`) open via
  `open(url)` and pass **no user and no password at all**, so the report uses `reportedUser` rather than
  `userFor` — `userFor`'s fall-back to `-Dinspecto.db.user` is right for a *credentialed* family
  inheriting the shared value and wrong for one that sends nothing. ⚠ Caught only by probing: the first
  cut reported `"user":"ops_user"` beside `"userProperty":null` for `JOB_RUNS`, naming a credential
  `DbJobRunStore` never sends. A diagnostic whose entire value is being trusted must not guess.
- ⛔ **No password leaves the server, in any form** — not the value, not a redaction, not a length. And
  because a JDBC URL may legally embed credentials (`jdbc:postgresql://user:pw@host/db`), every URL is
  passed through `stripUserInfo` first. ⚠ **A redaction test asserting the `password` FIELD is absent
  proves nothing** — the guard asserts the secret appears nowhere in the whole body, and was falsified by
  stubbing `stripUserInfo` to a no-op (1 failure, naming five families that leaked).
- ⛔ **A supplied password is a `SecretResolver` reference, never a literal** (422) — a literal in a form
  post is a credential in transit and in every access log. The scheme allow-list (`jdbc:postgresql:` /
  `jdbc:duckdb:`) is the other 422: an admin-gated endpoint that opens connections must not become a
  general-purpose port scanner.
- ⚠ **A new gated route must also be declared in `CapabilityManifest.ENTRIES`** — `CapabilityManifestTest`
  compares the manifest against the actual `withCapability` registration sites and fails the build on
  drift. It caught exactly this omission on the first full reactor run.

### 5.0-b Who reads a `*.backend` — `resolve()` is a MIRROR, not the implementation (2026-09-16)

*(Distilled from `ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1`, closed as REFUTED 2026-09-16 — the third
successive framing of that row to fail grounding. This subsection is its only home.)*

**Every family reads its own `*.backend` with its own `System.getProperty` at its own opener.** That is
the house idiom, not an outlier, and "bypasses `OperationalDb.resolve()`" is therefore **not a defect
signature** — it describes all fifteen:

- `OperationalDb.resolve(Family, SpaceRoot)` / `resolveAll(SpaceRoot)` have **exactly one caller
  repo-wide**: the diagnostic [`OperationalDbReport.java:47`](../../../../inspecto/src/main/java/com/gamma/service/OperationalDbReport.java).
  **No store opener calls either**, and [`ServiceStores.java:62`](../../../../inspecto/src/main/java/com/gamma/service/ServiceStores.java)
  says so outright — *"Mirrors OperationalDb.resolve"*. ⛔ Read `resolve()` as a **mirror of the openers
  for reporting**, never as their implementation; changing it changes what the report says, not what the
  service opens.
- **The shared seam that IS load-bearing is `OperationalDb.urlFor(Family, default)`** — the URL half.
  Every family goes through it, the acquisition ledger included
  ([`SpaceBootstrap.java:37-39`](../../../../inspecto/src/main/java/com/gamma/service/SpaceBootstrap.java),
  and `ServiceBootstrap.java:113` since `ACQUIRE-LEDGER-SHARED-URL-1`). ⚠ So "one source of truth" already
  holds for URLs and does **not** hold for backends — they are separate questions, and only the second is open.
- ⛔ **`resolve()` is public but unusable outside `com.gamma.service`**: it returns
  `record Resolved` ([`OperationalDb.java:209`](../../../../inspecto/src/main/java/com/gamma/service/OperationalDb.java)),
  which is **package-private** (`OperationalDb` is a `public final class`, so nested members are not
  implicitly public the way an interface's would be).
- ⛔ **Routing a leaf module's backend read through `OperationalDb` is a MODULE CYCLE.** `inspecto-acquire`
  is a deliberate leaf; `inspecto/pom.xml:96` depends **on it**, and `OperationalDb.java:3` imports
  `com.gamma.acquire.SecretResolver`. `Family` is invisible from the leaf and cannot be made visible
  without inverting that dependency.
- ⚠ **`Resolved` is not value-identical to what an opener needs**: it discards the raw backend string, and
  [`AcquisitionLedgers.java:152-156`](../../../../inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedgers.java)
  needs it **verbatim** for its `StoreHealth` message. Substituting it — even inside the package — would
  change an invalid-value message.
- ⇒ **What a real de-duplication would cost:** a public, `SpaceRoot`-free accessor (e.g.
  `backendOf(Family)` returning the raw trimmed string) in a module **below** `inspecto-acquire`, plus all
  fifteen sites. ⛔ That is an architecture decision, not a clean-up, and nothing on the board authorises
  it. Re-file only as that systemic question.
- ⚠ **`AcquisitionLedgers.shared()` never returns null** — it falls back to `InMemoryAcquisitionLedger`, so
  a caller **cannot tell memory from durable by nullness** and must ask `StoreHealth.of(spaceId)`, the one
  place the resolved backend is still known. *(This is the fact the Completeness-KPI K4 design tripped over;
  it survives the refutation.)*

🔴 **The standing lesson: "X bypasses the central helper" is only a defect if the other N sites do not.**
Count the sites before filing. This row was filed three times — first as *"not a `Family` member at all"*
(false: `OperationalDb.java:147` declares it), then as *"duplicated resolution bypassing `resolve()`"*
(true but not a defect), and only the site count settled it.

### 5.1 Flags (all read in `ServiceStores` unless noted)

| Capability | Backend flag | URL flag | Credentials |
|---|---|---|---|
| Objects **+ links + notes** | `-Dobjects.backend=db` | `-Dobjects.db.url`, `-Dobjects.links.db.url`, `-Dobjects.notes.db.url` | `-Dobjects.db.user` / `-Dobjects.db.password` (shared) |
| Status | `-Dstatus.backend=db` | `-Dstatus.db.url` | `-Dstatus.db.user` / `.password` |
| Jobs | `-Djobs.backend=postgres` | `-Djobs.db.url` | (in URL) |
| Provenance | `-Dprovenance.backend=postgres` | `-Dprovenance.db.url` | (in URL) |
| Acquisition ledger | `-Dacquire.ledger.backend=db` | (property in [`AcquisitionLedgers`](../../../../inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedgers.java)) | — |
| Consignment outputs | `-Dconsignment.outputs.backend=postgres` | `-Dconsignment.outputs.db.url` | (in URL) |
| File stages | `-Dfile.stages.backend=postgres` | `-Dfile.stages.db.url` | (in URL) |
| Delivery receipts | `-Ddelivery.receipts.backend=postgres` | `-Ddelivery.receipts.db.url` | (in URL) |
| Delivery receipts | `notify/DeliveryReceiptStore` | [`DbDeliveryReceiptStore`](../../../../inspecto-engine/src/main/java/com/gamma/notify/DbDeliveryReceiptStore.java) | `delivery.receipts.backend=duckdb\|postgres\|jdbc:…` | `none` (in-memory) |
| Events | `-Devents.backend=db` (or `postgres`, or a raw `jdbc:`) | `-Devents.db.url` | (in URL) — `-Devents.db.user` / `.password` |

Point each URL at `jdbc:postgresql://…`; the three ops URLs may share one database/schema (table names
don't collide). ⚠ Since 2026-08-14 you normally set **none** of these — §5.0's single `-Dinspecto.db`
supplies them all; the per-family flags remain as overrides and for back-compat.

### 5.2 Dialect notes & landmines

- **Only `DbJobRunStore` has a dialect branch** — it swaps DuckDB `quantile_cont(col,p)` for Postgres
  `percentile_cont(p) WITHIN GROUP (ORDER BY col)`. The probe behind it is the shared
  `JdbcDrivers.isPostgres(Connection)` (also backs `BrowsableStore.browseEngine()`'s catalog label).
  Everything else is ANSI SQL (incl. `FILTER (WHERE …)`, supported by both engines).
- **`CHECKPOINT` in `maintenance()`** (`DbJobRunStore`, `DbAcquisitionLedger`) is superuser-only on
  Postgres — currently caught-and-logged, so it degrades to a no-op VACUUM cycle. Verify that's acceptable.
- Reserved words already quoted: `"owner"`, `"trigger"`; `object_version` deliberately avoids `version`.
- No `COPY`, sequences, or DuckDB-specific types in the operational stores — those idioms live only in
  the **business-data** path (out of scope here).
- **No connection pooling anywhere** — every store uses one raw `DriverManager` connection. A real
  Postgres deployment should add a pool (e.g. HikariCP); it doesn't exist today.
- 🔴 **A raw `jdbc:` backend value used to be LOWERCASED before it was used as the URL** (fixed
  2026-09-11, scale-out phase A). Six openers read `System.getProperty(…).trim().toLowerCase()` and then
  passed that same string on as the URL, so `-Djobs.backend=jdbc:postgresql://db/MyDb?user=Alice&password=Secret`
  silently connected as `mydb`/`alice`/`secret` — Postgres database names, roles and passwords are all
  case-sensitive — and on a case-sensitive filesystem `jdbc:duckdb:/srv/Inspecto/x.duckdb` opened a
  different file. Affected: jobs, provenance, consignment outputs, dedup ledger, delivery receipts, file
  stages. `status` and `events` never lowercased and were never affected. ⛔ The rule the fix restores,
  already used by `OperationalDb.resolve`: **compare on a lowercased copy, pass on the raw value.**
- ⚠ **A store that fails to open degrades and says so only at WARN** — thirteen openers across
  `ServiceStores`, `OpsEngineProvider` and `AcquisitionLedgers` catch, log, and hand back an in-memory or
  `null` store. Since 2026-09-11 each one also records its outcome in `com.gamma.util.StoreHealth`, and
  `GET /health/details` reports one `store.<family>` subsystem per family: `UP`, `NOT_CONFIGURED` (the
  toggle is off — not a failure), or `DOWN` (a durable backend was asked for and could not be opened).
  That is what makes `VER-3` checkable. ⛔ A family with **no** entry was never opened at all — absence is
  not health, and must never be read as `UP`.
- 🔴 **`-Dinspecto.topology=partitioned` turns a degradation into a BOOT FAILURE** (`com.gamma.util.Topology`,
  D12; values `single` — the default — and `partitioned`, and it is what `/bootstrap` reports). Graceful
  degradation is right for one node and is **silent split-brain** across several sharing one database: two
  nodes each holding their own in-memory truth, neither aware of the other. ⚠ The check lives in
  `StoreHealth.record`, the one place every opener already reports through — **not** in the thirteen catch
  blocks, because thirteen checks are thirteen places a fourteenth store can forget one. ⛔ An unrecognised
  value refuses the boot rather than defaulting to `single`: defaulting would turn one typo into exactly the
  degradation the flag exists to prevent. ⚠ `partitioned` means "more than one process shares this state", so
  it covers Standard's two-node T4 standby as well as Enterprise's N pods.

### 5.3 Migration checklist

1. Put the Postgres JDBC driver on the runtime classpath (`org.postgresql.Driver`).
2. Set the flags in §5.1 with `jdbc:postgresql://…` URLs + credentials.
3. **Existing DuckDB rows don't move automatically** — there is no export/import tool
   (`BackupTask` is a filesystem zip, not a DB-row mover). Either write a one-off per-table
   `SELECT → INSERT` script, or accept a clean cutover with empty Postgres tables that the writers
   repopulate going forward.
4. Before relying on it: confirm the `CHECKPOINT` no-op is fine. (Nine store classes have a Postgres round-trip in `PostgresStateStoreTest`; `DbAcquisitionLedger`, `DbDedupLedger`
   and `DbDeliveryReceiptStore` do **not** — this parenthesis said "all seven … including `DbAcquisitionLedger`" until 2026-09-08.)
5. ~~Events cannot move — `ParquetEventStore` has no DB sibling~~ ✅ **It has one since 2026-09-12**:
   `DbEventStore` (`events.backend=db`). Events move like every other family now.

For the 9 covered stores this is essentially a **configuration change** — flags + URLs + driver + a
Postgres instance — not a code change.

---

## 6. Browsing the raw tables

The **Data Browser** pane (a per-space DB client) browses these stores live. Backend: `/db/catalog`,
`/db/table`, `/db/query` in [`control/DbBrowserRoutes.java`](../../../../inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java)
(read-only, `SqlGuard`-checked). UI: `inspecto-ui` → **Catalog › Data Browser**. Design + phasing in
[`archived-documents/plans-archive/db-browser-design.md`](../../../archived-documents/plans-archive/db-browser-design.md).

- **Business-data stores** (§1) read via an ephemeral DuckDB sandbox (`read_parquet`/`read_csv`).
- **A store id may itself be path-like** (e.g. a pipeline's `<name>/database` output dir) without being
  a smuggled file reference. `SqlGuard.check(sql)`'s `PATH_LIKE` rule can't tell the two apart from the
  SQL text alone, so `POST /db/query` calls the two-arg `SqlGuard.check(sql, trustedRelation)` overload
  (2026-09-22) with the request's own `table` param — but only once that name is proven to sit inside
  this space's data root (`DbBrowserRoutes.isJailedStore`, the same jail `browseStore` enforces). A
  relation ref matching that exact, already-jailed name is exempt from `PATH_LIKE`; anything else —
  a mismatched name, a string literal, an unrelated path — still gets rejected exactly as before. This
  closed a real gap: Link Analysis's own SQL editor couldn't query a dataset whose registered name
  contained a slash (`"mule_transfers/database"`), because the guard rejected its own FROM target.
- **Operational tables** (§3) browse through each store's own `ConnectionSource` via
  [`util/BrowsableStore.java`](../../../../inspecto-util/src/main/java/com/gamma/util/BrowsableStore.java) —
  a browse read borrows like any other operation and appears only when that capability runs on
  a `db`/`postgres` backend. ⚠ `browseConnection()` and `browseMonitor()` were **removed** 2026-09-14
  (`OPS-03`): a pooled store has no single long-lived connection to hand back, and exclusion moved onto
  the source — for DuckDB that is still one connection behind one monitor, so browse reads serialise
  against the store's writes exactly as before. Every `Db*Store` in §2/§3 implements this seam.

## Why `__consignment_id` is on the row, not just in the registry

It is not redundant with the output registry. The column is what stops compaction being a **one-way door**:
once files from several Consignments are merged, the registry can still say which files *were* produced, but
without a per-row id nothing can say which rows came from which Consignment — so a targeted supersede or a
replace-by-Consignment becomes impossible on compacted data. It is also a sortable id, so range scans over it
are cheap. ⚠ Add it **before there is data that lacks it**; backfilling it means re-deriving provenance that
no longer exists.

**Incident dedup by `correlationId` is advisory, not enforced.** `ObjectService.active(...)` looks for an open
Incident with the same correlation id; there is **no uniqueness constraint** in the schema. Concurrent
evaluations can therefore open duplicates. Treat the dedup as best-effort and do not build a guarantee on it.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

## Proving the JDBC stores on real PostgreSQL (DAT-6)

`PostgresStateStoreTest` opens JDBC-backed store classes against a real server and round-trips each one
(nine until 2026-09-12, when A3 added the event store, delivery receipts and the dedup ledger — the last
two being the gap DAT-6's own coverage claim had), plus the run lease since B1 and `DbInboxRegistry` since
2026-09-13 — **fifteen** store classes in all.

✅ **The roster is fifteen families and COVERAGE IS NOW COMPLETE** (re-measured 2026-09-16). ⚠ This
paragraph used to claim **thirteen** store classes, put the roster one short, and name the acquisition
ledger as the one still uncovered — three numbers and a named gap, all stale: it is round-tripped at
`PostgresStateStoreTest:292`, and `INBOX_REGISTRY` had joined the enum without this paragraph moving.
🔴 **Do not re-open that "gap" — measure before believing it.** ⛔ And measure with a probe that can
return a hit: a count of `Db*.open(` call sites reports **fourteen** and drops `DbDedupLedger`, which is
built with `new DbDedupLedger(conn)` at `:479`. The two idioms are the whole discrepancy, and a probe
matching only one of them reports an absence that is not there.

⚠ **That count is mirrored in nine places** and was missed by hand twice in two shifts, so
`tools/check-family-count.mjs` now fails the build when any of them drifts from
`OperationalDb.Family`.
⛔ **"Covered" is not "verified".** Every method in that class `assumeTrue`s on a configured server, so with
no `INSPECTO_TEST_PG_URL` the whole class SKIPS — the three added in A3 have never executed anywhere. Read
the skip count, not the test count.
Its load-bearing case is `DbJobRunStore.metrics`: p50/p95 are the one piece of non-portable SQL — DuckDB's
`quantile_cont` versus Postgres's `percentile_cont(..) WITHIN GROUP` — so only a real engine pins the
dialect fix.

**It needs a server you supply** (operator decision 2026-09-07). The embedded-Postgres harness and its
per-platform binaries are gone; only the JDBC *client driver* remains, so Postgres is installed separately
or pointed at:

```bash
mvn -o test -pl inspecto -am -Dtest=PostgresStateStoreTest     -Dinspecto.test.pg.url='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=…'
```

`INSPECTO_TEST_PG_URL` works too. With neither, all 11 methods report **SKIPPED with the reason** — the
skip is per-test, not on `@BeforeAll`, because an assumption there aborts the container and surefire
prints `Tests run: 0, Skipped: 0`, hiding the absent coverage entirely.

⚠ **Each run creates and drops its own schema** (`inspecto_test_<nanos>`, reached via `currentSchema`).
The assertions are exact counts, so they need a clean database and a shared server is not one — never
point this at a default schema you care about, and never remove the `@AfterAll` drop.
