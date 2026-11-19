---
type: Reference
title: Database / persistence layer — Schemas (operational, non-Parquet)
description: Part 2 of 4 of Database / persistence layer: Schemas (operational, non-Parquet)
tags: [persistence, duckdb, postgres, schema, topology, stores]
timestamp: 2026-07-16T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-engine
---

# Database / persistence layer — Schemas (operational, non-Parquet)

Part 2 of 4 of [Database / persistence layer](spec-db-layer.md) · previous: [Three data classes and the operational store inventory](spec-db-layer-data-classes.md) · next: [Schemas — consignment outputs onward](spec-db-layer-schemas-continued.md).

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
  object_type    VARCHAR,   -- INCIDENT | CASE | TASK (any other text loads inert)
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
  closed_at      BIGINT,
  version        BIGINT DEFAULT 0   -- optimistic-lock counter (see below)
);
ALTER TABLE inspecto_ops_objects ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;  -- boot migration
```

**Optimistic versioning (2026-10-04).** `OperationalObject.version` is persisted by both stores. `ObjectStore.update(obj)` writes only `WHERE id=? AND version=?` (the version the caller read) and sets `version=version+1`; zero rows means the id is gone (`NoSuchElementException`) or a racing writer won (`ObjectVersionConflictException`, nothing written). In-memory does the same compare under its lock. `create` stores version 0. ⚠ The column is `DEFAULT 0`, **not** `NOT NULL`: DuckDB cannot `ADD COLUMN` with a NOT NULL constraint, and the default fills pre-migration rows on both engines. `ObjectService` re-runs a read-modify-write that is a pure function of the fresh read up to `MAX_RMW_ATTEMPTS` (10) times (`retrying` / `rmw`: transition, patch, saveAttributes, saveImpact, watchers, assign, merge, tag projection), the SLA sweep skips a contended object (next sweep re-evaluates), and a conflict that survives the bound reaches the HTTP edge as `409 CONFLICT_STALE_VERSION` (`ObjectRoutes#scoped`, recoverable). Object JSON carries `version`. **Client-held version (2026-10-06):** `PATCH /objects/{id}` takes an optional body `version` — when present and the stored row is at any other version, `ObjectService.patch(id, expectedVersion, …)` throws `ObjectVersionConflictException` before writing (a retry re-reads and still mismatches, so it surfaces as 409); absent keeps last-write-wins; non-integer → 400 (`ControlApiObjectsTest.patchWithExpectedVersionRefusesAStaleEdit`, mutation-checked). Pinned by `ObjectStoreContractTest` (contract, 8-writer race, 4x25 increments, migration on a legacy table — in-memory/DuckDB and real PG) and `ObjectServiceRaceTest`.

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
`sync` rewrites each pipeline's rows in one transaction; on DuckDB the rows go through an Appender, which writes
inside that transaction, so a failed sync still rolls back every table (`OPSTORE-BULK-INSERT-1`, pinned by
`DbStatusStoreTest.aFailedBulkSyncRollsBackEveryTable`). Postgres keeps the batched `INSERT`.

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

🔴 **A taken-over run does not commit** (`LEASE-TAKEOVER-INFLIGHT-1`, 2026-09-30). `RunLease.Claim.isValid()`
re-reads `owner` + `epoch` in one query (fail-closed on a read error); the renewer marks a claim lost when
its fenced renew matches no row. The run holds `com.gamma.inspector.CommitFence` (keyed scope + Space MDC +
pipeline) around each claim — `PipelineScheduler.runOne`/`acquireOne`/`drainNow`, `CollectorService.runPipeline`/
`replayRejects` — and the engine checks it before the first durable side effect of
`ConsignmentIngestor.finalizeSource` (run), again just before its backup move (the step that takes the original
out of the inbox), at the top of `parkSource`, and before `materializeRemote` lands fetched files (acquire). A
lost claim throws `LeaseLostException` ("lease lost"), so the Consignment is audited FAILED with that reason,
nothing more is registered, backed up, parked or marked, and its files stay in the inbox for the new holder.
⛔ A lease loss spends **no** X1 commit-retry attempt and clears no record (`ConsignmentIngestor.process` catches
it before the generic commit catch) — five HA flaps would otherwise quarantine a good file as `RETRY_EXHAUSTED`.
A claim answers a three-way `CommitFence.State` (`HELD` / `LOST` / `UNREADABLE`): an unreadable lease table refuses
the commit with its own reason ("lease unverifiable … could not be read", not "taken over") but is never
recorded as a loss (`stillOurs` answers `null`). `CollectorProcessor.onBatchThrown` also refuses to spend an X1
attempt for a `LeaseLostException` that ever escapes `process`. The heap guard's claim is valid until released.
⚠ What a refusal leaves behind: the lost run's output files were already written by the sink (the graph lane's
`finalizeSource` runs only after every branch committed), and a glob reader can see them until the new holder
commits. ⛔ **There is deliberately NO cleanup** (a `discardLostRun` was built and removed the same day): the
batch id is `ConsignmentId`, a deterministic hash of the members' sorted relative paths and bytes, so the new
holder re-plans the same inbox — the lost run never moved the files — and mints the SAME id, hence the same output
names (`consolidatedBaseName`: the source stem for one file, the batch id for several), the same manifest name and
the same registry `consignment_id`. Deleting by id after the holder has committed destroys the holder's data, its
manifest and (via `supersede`) its LIVE registry rows, with the files already in backup. An identical re-plan
overwrites in place (`OVERWRITE_OR_IGNORE`) and self-heals; pinned by `CommitFenceTest`. A different membership
mints a different id and leaves an orphan — a bounded residual. A lease lost between the first check and the
pre-backup check also leaves that batch's manifest and registry rows; the holder rewrites the same-named manifest.
The DuckLake catalog is idempotent too: `DuckLakeRegistrar.registerOnce` checks, inserts and records in ONE
transaction against `_inspecto_registered_consignments` (`target_table`, `consignment_id`, `registered_at`), so the
holder's re-registration of the same `consignment_id` is skipped, and a crash between insert and record rolls both
back (no double, no record-less registration). The table is created on demand (`CREATE TABLE IF NOT EXISTS`), so an
existing lake needs no migration; rows registered before it have no record and are not deduplicated retroactively.
`finalizeSource` passes the batch id, suffixed per sink (`<id>|<sink.database>`, `sinkKey`) so two sinks that
resolve to one lake and table never skip each other. The job lane passes its run's `batchId` (unique per run:
`currentTimeMillis`, unless the operator pins `consignment_id`, in which case a re-run with that id is skipped), so a
full recompute over the same stable paths registers again while a retry inside one run dedupes. A caller with no
id (none remain in main code) gets a key over each file's path, size and mtime, never paths alone.
⚠ `RecordReplay.replay` reaches `finalizeSource` through `CollectorProcessor.ingestCandidates` and registers
under the replay input's own consignment id (a different file, so a different id); verified by reading, not tested.
⚠ Residual (`CONSIGNMENT-ID-DETERMINISTIC-1`): `ConsignmentId` digests path plus BYTE SIZE, so an in-place edit
of the same length keeps its id and is now SKIPPED in the lake (the old rows stay; before, it duplicated).
Pinned by `DuckLakeRegistrarIdempotentTest.aSameIdEditOfDifferentContentIsSkippedByDesign`.
⚠ Side effect: `_inspecto_registered_consignments` lives in the target schema, so anything that enumerates
lake tables sees it. ⚠ Two truly concurrent registrars could both pass the check (DuckLake has no
constraints); the lease excludes that. Identifiers and paths are quote-escaped. Verified on a native attached
DuckDB, and by `realDuckLakeRegistersOnce` against a REAL DuckLake catalog when the `ducklake` extension is
already cached in the machine's DuckDB extension directory (LOAD only). ⚠ Those real-lake tests
(`realDuckLakeRegistersOnce`, and `DuckLakeRegistrarRegisterWiringTest`, which drives `register()` with two sinks
sharing one lake and `registerInto` with a pinned id) skip QUIETLY through `assumeTrue` where the extension is not
cached, so a plain CI run leaves the real-lake transaction and the per-sink key unverified; the skip message says so.
⚠ A pinned `consignment_id` (pre-4.0.0 key `batch_id`, still read until 5.0.0) on the job lane is the same skip made opt-in: re-running an overwriting Pipeline with it is
skipped in the lake and keeps the first run's rows (logged at WARN); pinned by
`registerInto_skipsARerunWithTheSamePinnedIdEvenWhenTheDataChanged`.
⚠ A long lease-table outage makes every run refuse each cycle ("lease unverifiable"), with no attempt spent and
nothing quarantined; nothing raises a Signal for it, so the symptom is files not draining and the log line.
Job / authored-Pipeline runs hold `SCOPE_JOB` / `SCOPE_AUTHORED` claims through `RunClaims`, whose `Claim.state()` /
`isValid()` delegates to the lease (`CollectorService.claimsOver`). `JobService.runJob` holds the weaker of the two in
`CommitFence` under `Scope.JOB`, keyed by **Run id**, and `PipelineJobRunner` checks it immediately before each
§11.3 output-registry row (`PartitionSinkWriter.beforeRegister`), before the watermark / supersede / view / artifact
writes, and again before `registerInLakehouse`; a taken-over run ends FAILED "lease lost" with nothing registered and
its branch uncommitted, so the new holder's run registers once (`LEASE-TAKEOVER-JOB-RUNS-1`, shipped 2026-10-03;
`JobServiceTest.aPipelineRunWhoseLeaseWasTakenOverRegistersNothingAndTheNewHolderRegistersOnce`). ⚠ Parquet bytes
are written before the fence (batch-derived names, so the new holder overwrites them); only registration is fenced.
`commitRetryAct` needs none — it holds
its claim for one operator act, to exclude a running cycle, and commits no run output.

**Selected by `-Drun.lease.backend`** (`heap` default · `db` · `postgres` · a raw `jdbc:` URL), with
`-Drun.lease.db.url` / `.user` / `.password` and `-Drun.lease.owner`. The credentials are a family property like the others (`OperationalDb.Family.RUN_LEASE`): `userFor`/`passwordFor` resolve `-Drun.lease.db.*` first, then the shared `-Dinspecto.db.*`, for the lease connection AND the per-Space `CREATE SCHEMA` (`HA-RUNLEASE-DB-CREDENTIALS-1`, 2026-10-10 — before it the schema step read only the shared pair and skipped the Space). A Space that fails to boot is kept in `SpaceManager.skipped()` (code `SPACE_SCHEMA_CREATE_FAILED` / `SPACE_BOOT_FAILED`, no exception text): `/health/details` `spaces.skipped[]`, `/health` `DEGRADED` (count only, public), and `/ready` 503 only when ≥1 Space is configured and 0 booted (zero configured stays READY; a lease-DB outage still does not drain). `StoreHealth.mask` strips URL userinfo and `password=`/`token=` values from every stored target/detail. Known gap: the five credential-less families (jobs, events, provenance, file stages, …) still open with the URL alone. ⛔ The default is `heap`, never a
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
ALTER TABLE inspecto_events ADD COLUMN IF NOT EXISTS audit_seq BIGINT
CREATE INDEX IF NOT EXISTS inspecto_events_seq ON inspecto_events (audit_seq)
```

⚠ Column names mirror `ParquetEventStore`'s exactly, so an operator reading one backend's raw table reads
the other's unchanged. `attributes` and `payload` are JSON strings (`JsonAttributes`), as in Parquet.
⛔ Append-only, like every `EventStore`: no update, no row delete. `prune(before, dryRun)` deletes whole
UTC days and returns the number of **days** — not rows — so both durable backends report the same unit
(Parquet deletes day partitions). The boundary is exclusive: an event ON the cutoff day is retained.

```sql
CREATE TABLE IF NOT EXISTS inspecto_status_commits    (pipeline VARCHAR, consignment_id VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_batches    (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_files      (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_lineage    (pipeline VARCHAR, consignment_id VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_quarantine (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_unpack     (pipeline VARCHAR, seq BIGINT, payload VARCHAR);

CREATE INDEX IF NOT EXISTS inspecto_status_commits_by_pipeline          ON inspecto_status_commits    (pipeline);
CREATE INDEX IF NOT EXISTS inspecto_status_batches_by_pipeline          ON inspecto_status_batches    (pipeline, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_files_by_pipeline            ON inspecto_status_files      (pipeline, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_lineage_by_pipeline_consignment ON inspecto_status_lineage (pipeline, consignment_id, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_quarantine_by_pipeline       ON inspecto_status_quarantine (pipeline, seq);
CREATE INDEX IF NOT EXISTS inspecto_status_unpack_by_pipeline           ON inspecto_status_unpack     (pipeline, seq);
```

⚠ **The index set is derived from the predicate set, not chosen** (`DB-STATUS-INDEX-1`, 2026-09-17). Every read
against these tables goes through the `StatusStore` seam — `committedBatches` and `readRows` in `DbStatusStore`
are the only two SQL sites, and the list endpoints (`/runs/{p}/batches`, `/lineage`, `/quarantine`,
`ReportService`, `MetricsService`) all reach them through it — so the predicates are closed: `WHERE pipeline = ?`
everywhere, `AND consignment_id = ?` on lineage only, `ORDER BY seq` on the five payload tables. The leading `pipeline`
column also serves `deletePipeline` (the per-sync DELETE) and `renamePipeline`. `seq` trails deliberately: on
Postgres the composite lets the sort be read off the index, while DuckDB's ART index serves the `pipeline`
lookup and sorts after — the extra column is free there rather than a DuckDB-only bet.
`DbStatusStoreTest.everyListPredicateHasABackingIndexOnTheColumnsItFilters` pins the exact (table → columns)
set in both directions, so a new predicate without an index and an index without a predicate both fail.

**The two Consignment columns were `batch_id` until 4.0.0** (D-12 rename trio, MoSCoW S4, 2026-11-17). Since
2026-08-04 the source ledgers spell the key `consignment_id` (`Csv.readInto` canonicalises the legacy header), and
since 4.0.0 the columns do too. `DbStatusStore.migrateBatchIdColumns` converts a 3.x database on open, one-shot and
idempotent. The catalog is the record: it runs only while a `batch_id` column exists. It runs as two transactions,
**payloads first**: (1) rewrite the `"batch_id":` key in every `payload` (`replace`, matched with `position`, so a
JSON value that merely contains the text is untouched, because its quotes are escaped), then (2) drop the two indexes
over the old column, `RENAME COLUMN` on both tables, and let `initSchema` recreate the indexes. The rewrite runs
first because a crash between the steps then leaves `batch_id` in place and the next open redoes both. ⚠ They are
not one transaction: DuckDB 2.0 failed the payload `UPDATE` placed after the drop and rename in the same transaction
("does not have a column named batch_id"). Pinned by `DbStatusStoreTest.aPre400DatabaseIsRenamedOnOpenAndStillServesItsRows`.
⚠ **All three open-time migrations (this one, §3.6 and §3.10) probe only their own schema.** `SchemaMigration`
(`inspecto-util`) adds `table_catalog = current_database() AND table_schema = current_schema()` to every
`information_schema` probe. On Postgres every Space is a schema of one database, so an unscoped probe saw another
Space's `batch_id` and made a migrated or new store fail to open; a review caught it (`*anotherSchemas*` tests in
the three store test classes). On Postgres each migration transaction also takes a per-schema
`pg_advisory_xact_lock` and re-probes under it, so two nodes opening one schema at once do not both migrate. The
lock and the schema scoping are **proven on PostgreSQL 18** (2026-11-17) by `PostgresBatchIdMigrationTest`
(`inspecto`, `com.gamma.service`; runs with `INSPECTO_TEST_PG_URL` / `-Dinspecto.test.pg.url`, skips without it).
Per store, two schemas hold 3.x tables, and 4 nodes, each with its own connection, open the store on the first at
once. All must open, every row must be there exactly once, the second schema's `batch_id` tables must stay
untouched, and a reopen must change nothing. Falsified 2026-11-17: with `SchemaMigration.lock` a no-op, all three
stores went red in 5 of 5 runs. With the schema filter emptied, all three went red in 3 of 3 runs, at the open
(`column "batch_id" does not exist`). The probe saw the other schema's `batch_id`, but the unqualified DDL can only
act on its own schema. ⚠ The status store's migration does not depend on its lock. It stayed green in 3 of 3
runs with only the `batch_id` locks removed: `DROP INDEX` waits on the table lock, and the per-table
`columnExists` re-probe after it sees the committed rename. Its lock is what keeps `initSchema`'s DDL safe.
⚠ The first Postgres run found a defect: `migrateBatchIdColumns` drops two indexes, so every node then recreated
them in `initSchema`. Postgres's `IF NOT EXISTS` is not atomic across sessions, so one node failed on
`pg_class_relname_nsp_index` (6 of 6 runs without the fix). `initSchema`'s DDL now runs in one transaction under the
same lock. The lock also covers a table the schema does not have yet: the 3.x fixture has no
`inspecto_status_unpack`, and without the lock its `CREATE TABLE IF NOT EXISTS` raced on `pg_type_typname_nsp_index`.
**All three stores now run their schema DDL this way** (2026-11-17). `DbProvenanceStore.initSchema` (CREATE TABLE
and ADD COLUMN) and `DbFileStageStore.createUnderLock` (the CREATE) each run in one transaction under the store's
own per-schema lock. That lock is the same one the store's migration takes. A new, EMPTY schema opened by 4 nodes
at once is pinned by `PostgresBatchIdMigrationTest.*_concurrentNodesCreateANewSchemaOnce`: all nodes must open,
and every table and index must exist exactly once. Without the three DDL locks, all three of those tests went red
in 5 of 5 runs, on `pg_type_typname_nsp_index`. DuckDB needs no lock, because one process holds the file.

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
per-row batch id (only the SCD2/reference write path stamps one — `__consignment_id` since 4.0.0, `__batch_id` before) — the file-level
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
  pipeline_id    VARCHAR,
  consignment_id VARCHAR,   -- batch_id before 4.0.0; renamed on open (D-12)
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
`false` (a real run). Before the CREATE, a 3.x table's `batch_id` column is renamed to `consignment_id` (one
`ALTER TABLE … RENAME COLUMN`, run only while `information_schema.columns` still lists `batch_id`). `batches()`
returns `consignmentId` plus the deprecated alias `batchId` (same value, removed in 5.0.0).

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
transient write buffer). **Write-ahead journal (2026-09-26):** every buffered, not-yet-flushed event is also a
JSON line in `<eventsDir>/pending.jsonl` (forced to disk for `AUDIT` / `ACCESS_DENIED`), truncated by each
successful flush and replayed into Parquet when a store opens — so a hard kill loses no buffered event. It is
not a `.parquet` file, so the `read_parquet` glob never reads it. **Retention (COMPLY-3, 2026-09-02):** `EventStore.prune(before, dryRun)` deletes
whole `day=` partitions older than the cutoff (UTC, the flush's own frame) and collapses emptied parents; the
`event_prune` maintenance task drives it with a required `retention_days` (operator window: one year). The
in-memory backend answers `-1` — nothing durable. The event record shape:

```
event_id, ts_ms (BIGINT), type, source, pipeline, correlation_id,
message, attributes (JSON), payload (JSON), level  -- + partition cols year, month, day (VARCHAR)
```

`level` ∈ [`EventLevel`](../../../../../spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventLevel.java). There is **no
JDBC/Postgres event table** — events were Parquet-only. ⚠ **That changed 2026-09-12 (D6 / phase A3):**
`DbEventStore` adds `events.backend=db`. The Parquet layout below is unchanged and remains the default
durable backend for a single node; the database backend exists because Parquet is written by exactly one
process, so on N pods each replica sees a different Signal ledger.
