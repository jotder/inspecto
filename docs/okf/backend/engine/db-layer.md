# Database / Persistence Layer
> *Moved from `docs/DB_LAYER.md` (docs consolidation, 2026-07-16).*

> **Scope:** how Inspecto stores state on disk — the three data classes, the operational
> (relational) table schemas, the per-space file topology, and how to run operational data on
> Postgres. Vocabulary follows [`GLOSSARY.md`](../../../GLOSSARY.md) (**Store** = physical backend,
> **Dataset** = queryable relation).

> **⚠️ Keep this current.** This doc is derived from the source files listed below — when any of
> them changes (a table's DDL/columns, a store's backend wiring, the per-space file layout, a
> `-D*.backend` toggle, or Postgres behavior), update the matching section here (and
> [`superpower/db-browser-design.md`](../../../archived-documents/plans-archive/db-browser-design.md) if browsable tables/stores
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
implementations are **plain JDBC over a single shared `Connection`**, with hand-rolled DDL created
**lazily on first open** — there is no migration tool.

| Domain | Interface | DB impl | Backend toggle (`-D…`) | Default |
|---|---|---|---|---|
| Operational objects (ALERT / INCIDENT / CASE / TASK) | `ops/ObjectStore` | [`DbObjectStore`](../../../../inspecto-engine/src/main/java/com/gamma/ops/DbObjectStore.java) | `objects.backend=memory\|db` | `memory` |
| Correlation links | `ops/link/LinkStore` | [`DbLinkStore`](../../../../inspecto-engine/src/main/java/com/gamma/ops/link/DbLinkStore.java) | `objects.backend` (shared) | `memory` |
| Notes / evidence | `ops/note/NoteStore` | [`DbNoteStore`](../../../../inspecto-engine/src/main/java/com/gamma/ops/note/DbNoteStore.java) | `objects.backend` (shared) | `memory` |
| Events (append-only facts) | `event/EventStore` | [`ParquetEventStore`](../../../../inspecto-event/src/main/java/com/gamma/event/ParquetEventStore.java) *(Parquet, not JDBC)* | `events.backend=memory\|parquet` | `memory` |
| Ingest status / audit projection | `etl/StatusStore` | [`DbStatusStore`](../../../../inspecto/src/main/java/com/gamma/service/DbStatusStore.java) | `status.backend=file\|db` | `file` |
| Job-run reporting | *(class is the API)* | [`DbJobRunStore`](../../../../inspecto-engine/src/main/java/com/gamma/job/DbJobRunStore.java) | `jobs.backend=none\|duckdb\|postgres` | `none` |
| Pipeline-run provenance (per-edge counts) | *(class is the API)* | [`DbProvenanceStore`](../../../../inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java) | `provenance.backend=none\|duckdb\|postgres` | `none` |
| Acquisition / dedup ledger + export watermark | `acquire/AcquisitionLedger` | [`DbAcquisitionLedger`](../../../../inspecto-acquire/src/main/java/com/gamma/acquire/DbAcquisitionLedger.java) | `acquire.ledger.backend=memory\|db` *(via `AcquisitionLedgers`, not `ServiceStores`)* | `memory` |
| Consignment output-file registry | *(class is the API)* | [`DbConsignmentOutputStore`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/DbConsignmentOutputStore.java) | `consignment.outputs.backend=none\|duckdb\|postgres` | **`duckdb`** — the only default-on store; see below |
| Per-file stage-progression registry (Phase 4 §2.4) | *(class is the API)* | [`DbFileStageStore`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/DbFileStageStore.java) | `file.stages.backend=none\|duckdb\|postgres` | `none` |
| Windowed record-dedup ledger (D-9) | *(class is the API)* | [`DbDedupLedger`](../../../../inspecto-engine/src/main/java/com/gamma/consignment/DbDedupLedger.java) | `dedup.ledger.backend=none\|duckdb\|postgres` | **`duckdb`** — default-on like `consignment_outputs`: a default-off dedup ledger silently emits the duplicates it was configured to drop; costs nothing while no pipeline declares `scope: window(...)` |
| Ops escalation queues | `ops/queue/QueueStore` | **none** — in-memory only | — | — |
| Pipeline execution watermarks | `pipeline/exec/PipelineWatermarkStore` | **none** — in-memory/file only | — | — |

> **`ALERT`s are not their own table.** Alerts, incidents, cases and tasks are all rows in
> `inspecto_ops_objects`, discriminated by the `object_type` column
> ([`ObjectType`](../../../../inspecto-engine/src/main/java/com/gamma/ops/ObjectType.java): `ALERT, INCIDENT, CASE, TASK`).

Every backend **degrades gracefully**: a failed DB open falls back to in-memory/file and logs a
warning rather than blocking startup.

> **Why exactly one store defaults on** *(2026-08-10, addressing D1)*. `consignment_outputs` is the only
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

```sql
CREATE TABLE IF NOT EXISTS inspecto_status_commits    (pipeline VARCHAR, batch_id VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_batches    (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_files      (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_lineage    (pipeline VARCHAR, batch_id VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_quarantine (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
```

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
```

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

`ParquetEventStore` writes rolling **Hive-partitioned Parquet** under `<eventsDir>/year=/month=/day=/`,
read back through an in-memory DuckDB connection (`evt_buf` is only a transient write buffer). The event
record shape:

```
event_id, ts_ms (BIGINT), type, source, pipeline, correlation_id,
message, attributes (JSON), payload (JSON), level  -- + partition cols year, month, day (VARCHAR)
```

`level` ∈ [`EventLevel`](../../../../inspecto-event/src/main/java/com/gamma/event/EventLevel.java). There is **no
JDBC/Postgres event table** — events are Parquet-only.

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
  generation     INTEGER,
  state          VARCHAR,  -- LIVE | SUPERSEDED | COMPACTED_AWAY
  schema_fingerprint VARCHAR,  -- §3.4.3 CanonicalHash of the schema that wrote the file; NULL pre-column / no-schema paths
  event_time_min VARCHAR,      -- addressing §3.1: ISO-8601 LOCAL, no zone offset
  event_time_max VARCHAR,
  event_time_spread_ms BIGINT, -- max - min; NULL (not 0) when bounds are unknown
  producer       VARCHAR       -- the pipeline that wrote the file, for the §3.6 per-stream watermark
);
```

`initSchema()` follows the CREATE with `ALTER TABLE consignment_outputs ADD COLUMN IF NOT EXISTS` for
`schema_fingerprint` and for each of the four addressing columns — the additive migration for registries
created before they existed (CREATE TABLE IF NOT EXISTS never widens an existing table). Pre-migration rows
read back `NULL`.

**Null bounds mean *unknown*, never *empty*.** A consumer that prunes on bounds must treat a null-bounds row
as a **possible match**, or it will silently drop data. Two write paths can fill them, each from its own
declaration, and neither ever guesses which column is temporal:

| Path | Bounds come from | Absent when |
|---|---|---|
| Ingest (`BatchProcessor`) | `__event_time`, the coerced column `DataTransformer` materialises from the schema's date partition and excludes from written output | the schema declares no date partition, or every row failed to parse |
| Pipeline sink (`PartitionSinkWriter`) | `TRY_CAST(<source> AS TIMESTAMP)`, where `source` is a `partitions[]` entry's declared raw column — the same word `PartitionDef.source` uses | no entry declares a `source`, entries disagree on it, or it is not a plain identifier |

Enrichment writes and every row predating these columns still read back `NULL`.

**`record_day` is derived from those bounds where it can be** (addressing step 10) — from the file's real event
times when `min` and `max` share a day, else from the `year`/`month`/`day` partition segments as before. Bounds
win when the two disagree, since a partition value may have been cut in another timezone or off another column.
⚠ **Read `bounds`, not `record_day`**: one day per file cannot express a file that straddles two, which is what
an interval is for. Nothing in the engine reads the column today; it survives because it is in the schema and
cheap.

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
[consignment-ELT plan](../../../superpower/consignment-elt-architecture.md) §11.3 — the catalog substitute
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
| Ingest (+ routed rules, multi-destination fan-out) | `BatchProcessor.finalizeSource`, **after** the manifest write | `LineageCollector`'s matrix, summed per output file |
| Enrichment | `EnrichmentEngine.runResult` (routed files register from their own relation) | `ConsignmentOutputs.countByPartition` |
| Pipeline sinks | `PartitionSinkWriter.write` | `ConsignmentOutputs.countByPartition` (replaced its old whole-table `COUNT(*)`) |
| §7.3 summaries | `ConsignmentProcessJobType` after `SummaryWriter` reveals the files | the number of summary rows in that partition |

⚠ **Summary rows use `table_name = "<target>__summary"`, and the suffix is load-bearing.**
`GuardedSummaryEmitter.reconcile` sums detail `row_count` **by table name**, so registering a summary under the
target's own name would inflate the detail total and silently break §7.2's reconciliation. Filter on the suffix to
separate the derived summary tier (`<dataDir>/_summaries/<target>/record_day=…`) from detail outputs.

`row_count` is never a field copy — `PartitionWriter.reveal()` supplies only `(partition, outputFile, bytes)`,
because a partitioned `COPY` reports no per-file count back. **`record_day` is currently derived from the
partition key's `year`/`month`/`day` segments and is `null` for any other scheme** — a write-time approximation
that plan §10.1's pinned-timezone event-time-at-load must replace, not fall back to. `run_id` is `null`
everywhere: no path yet has a Run identity distinct from its unit of work.

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
  recorded_at    VARCHAR
);
```

Phase 4 §2.4's per-file stage progression: one row per `(source_id, relative_path)` file at each
boundary `BatchProcessor.finalizeSource` genuinely crosses, so *"where is file X right now"* is a
query instead of a re-read of the manifest and a guess about how far a crashed commit got.
**Insert-only** — a stage is a fact about a point in time, never updated; a file's history is its
own append-only progression through `finalizeSource`'s documented crash-safe ordering (register →
manifest → backup → markers LAST → ledger/watermark). `(source_id, relative_path)` is the same key
`AcquisitionLedger` uses.

Written by `FileStages.record`, an ambient per-space registry (the `ConsignmentOutputStores` idiom)
called from `BatchProcessor.finalizeSource` after each of the six boundaries; default-off and
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

---

## 4. File topology (per space)

**One DuckDB file per capability** — not one shared DB, and not one file per space. Each file is
single-writer-locked (documented in `ServiceStores`). Locations come from
[`SpaceRoot`](../../../../inspecto/src/main/java/com/gamma/service/SpaceRoot.java):

| Layout | Capability file locations |
|---|---|
| **`DirSpaceRoot`** (per-space dir) | `<spaceBase>/duckdb/<file>` — e.g. `spaces/demo/duckdb/inspecto-ops.db` |
| **`LegacySpaceRoot`** (flat working dir) | `./inspecto-ops.db`, `./inspecto-ops-links.db`, `./inspecto-ops-notes.db`, `./inspecto-status.db`, `./jobs_report.duckdb`, `./provenance.duckdb`, `./inspecto-acquisition.db`, `./inspecto-consignment-outputs.db`, `./inspecto-file-stages.db` |

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
([`PostgresStateStoreTest`](../../../../inspecto/src/test/java/com/gamma/service/PostgresStateStoreTest.java))
covering 6 of the 9 DB-backed stores — **`consignment_outputs` and `file_stages` are two of the three it does
not cover.** Both DDLs are portable by construction (`VARCHAR`/`BIGINT`/`INTEGER`, no PK, no upsert), but that
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

- **`OperationalDb.Family` is now the roster** — the ten families' property names live there and nowhere
  else, so the store openers and the report cannot drift; naming a family off the list stops compiling.
  ⛔ They had been ten **string literals** across `ServiceStores` + `SpaceBootstrap`.
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
| Events | `-Devents.backend=parquet` | — | **No Postgres path** |

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

### 5.3 Migration checklist

1. Put the Postgres JDBC driver on the runtime classpath (`org.postgresql.Driver`).
2. Set the flags in §5.1 with `jdbc:postgresql://…` URLs + credentials.
3. **Existing DuckDB rows don't move automatically** — there is no export/import tool
   (`BackupTask` is a filesystem zip, not a DB-row mover). Either write a one-off per-table
   `SELECT → INSERT` script, or accept a clean cutover with empty Postgres tables that the writers
   repopulate going forward.
4. Before relying on it: confirm the `CHECKPOINT` no-op is fine. (All seven JDBC stores now have a
   Postgres round-trip in `PostgresStateStoreTest`, including `DbAcquisitionLedger`.)
5. Events cannot move — `ParquetEventStore` has no DB sibling; moving events off Parquet needs new code.

For the 7 covered stores this is essentially a **configuration change** — flags + URLs + driver + a
Postgres instance — not a code change.

---

## 6. Browsing the raw tables

The **Data Browser** pane (a per-space DB client) browses these stores live. Backend: `/db/catalog`,
`/db/table`, `/db/query` in [`control/DbBrowserRoutes.java`](../../../../inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java)
(read-only, `SqlGuard`-checked). UI: `inspecto-ui` → **Catalog › Data Browser**. Design + phasing in
[`superpower/db-browser-design.md`](../../../archived-documents/plans-archive/db-browser-design.md).

- **Business-data stores** (§1) read via an ephemeral DuckDB sandbox (`read_parquet`/`read_csv`).
- **Operational tables** (§3) browse through each store's *live* connection via
  [`util/BrowsableStore.java`](../../../../inspecto-util/src/main/java/com/gamma/util/BrowsableStore.java) —
  reads are `synchronized` on the store (single-writer lock) and appear only when that capability runs on
  a `db`/`postgres` backend. Every `Db*Store` in §2/§3 implements this seam.
