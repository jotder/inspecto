---
type: Reference
title: Database / persistence layer — Schemas — consignment outputs onward
description: Part 3 of 4 of Database / persistence layer: 
tags: [persistence, duckdb, postgres, schema, topology, stores]
timestamp: 2026-07-16T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-engine
---

# Database / persistence layer — Schemas — consignment outputs onward

Part 3 of 4 of [Database / persistence layer](spec-db-layer.md) · previous: [Schemas (operational, non-Parquet)](spec-db-layer-schemas.md) · next: [File topology, Postgres and the raw tables](spec-db-layer-topology-postgres.md).

## 3. Schemas (operational, non-Parquet) (continued)

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
  state          VARCHAR,  -- LIVE | SUPERSEDED | COMPACTED_AWAY | REPLACED | SIMULATED (PIPELINE-DRYRUN-1 — a dry-run
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
(`spec-consignments.md`, which records why a counter was refused), and torn-read safety by
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
`path` twice: two sinks may target one store (`PartitionSinkWriter` sums `rowsByStore`) and
`PartitionWriter` reveals each partition under a stable `<baseName>_out.<ext>` with
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
semantic difference, not a naming detail. ⚠ **Live since 2026-11-01:** the ingest lane writes an
allow-listed `s3://` `dirs.database` (`SCALEOUT-OBJSTORE-DATABASE-DIR-1`; `spec-sinks.md`
§ *Object-store `dirs.database`*), so a re-run batch now really does add rows here.

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

**How a Run id gets here** (slice 3, 2026-09-13): [`RunIds`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/job/RunIds.java)
is the **single** generator — a second one at the Collector was refused so "run id" could not mean two
things depending on the path. Work outside `JobService` carries
[`StandaloneRunContext`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/job/StandaloneRunContext.java),
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
`okf/product/module/observability/plan-observability.md` §3.9.

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
data from every read. Row state is per-registration; readability is per-path. `REPLACED` (B4: a later arrival
replaced some of the file's rows; `markReplaced`) counts as readable like `LIVE`: the file keeps its path and the kept
rows ([spec-arrival-replace.md](../elt/spec-arrival-replace.md)).

**The two non-live states are opposites to a reader that aggregates.** `producerHighWater(table)` — the
per-producer `max(event_time_max)` the §3.6 Watermark folds — filters `state <> 'SUPERSEDED'` but keeps
`COMPACTED_AWAY`: compacted rows describe data that was genuinely delivered and still exists inside the merged
file, so dropping them would make the watermark travel **backwards** when a partition is compacted, while
superseded rows were replaced by a reprocess and would claim delivery the current data no longer supports. It
also cannot `max(written_at)` as text — that column is `Instant.toString()`, whose fractional digits vary, so
`…33.1Z` sorts *after* `…33.12Z`; it casts to a timestamp and projects epoch millis instead. `event_time_max`
is safe to `max()` as text only because §3.1 writes it in a fixed-width format.

The durable output registry from the
[consignment-ELT plan](../../../../archived-documents/plans-archive/consignment-elt-architecture.md) §11.3 — the catalog substitute
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
| `markCompactedAway(paths)` | [`PartitionCompactor`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/job/PartitionCompactor.java), **after** the reveal + cleanup | Path-keyed, because one merged file absorbs many Consignments. **Inserts no replacement row** — no single `consignment_id` owns the merged file, and `(state, partition_key)` is all §6.2's partition rewrite needs |
| `supersede(consignmentId)` | [`ReprocessCommand`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/inspector/ReprocessCommand.java), beside `ManifestStore.supersede` | Moves **only `LIVE`** rows; a `COMPACTED_AWAY` row keeps that state, since it is the evidence that tells a reprocess to rewrite the partition rather than unlink a path that is gone |

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

### 3.9-a `consignment_steps` — Step Receipts (X5)  · **A**
File: `inspecto-consignment-outputs.db` — the same file and the same `ConnectionSource` as `consignment_outputs`
(DuckDB is single-writer per file), so it is **on by default** with that registry (X5 D3 = c, 2026-11-17).

```sql
CREATE TABLE IF NOT EXISTS consignment_steps (
  consignment_id VARCHAR, run_id VARCHAR, pipeline VARCHAR, lane VARCHAR, seq INTEGER, step VARCHAR, kind VARCHAR, outcome VARCHAR, rows_in BIGINT, receipt VARCHAR, recorded_at VARCHAR, simulated BOOLEAN, UNIQUE (consignment_id, run_id, lane, seq)
);
CREATE INDEX IF NOT EXISTS consignment_steps_by_consignment ON consignment_steps (consignment_id);
```

One row per **Step Receipt** (GLOSSARY): one Step, one Consignment, one lane, one run. `receipt` is the receipt's
JSON (`StepReceipt.toJson()`, at most 1024 bytes, enforced by the record); the scalar columns beside it are for
filtering. Written by `recordSteps` — one batched insert per Consignment, never one per Step, best-effort (a
failure is a WARN, never thrown into the run) — and read by `steps(consignmentId)`, oldest flush first, then by
run, lane and `seq` (a failed read throws: an empty list must never stand for "could not read").

- **`run_id` is never `NULL`** — the `StepReceipt` record normalises a missing one to `''`. A `NULL` in a UNIQUE
  key exempts the row from it (`NULL ≠ NULL`), and a re-flush would duplicate a trail. Every lane writes a real one
  (X5 L3): the job run on `job`, the ingest Run (the `run_id` of the Consignment's `consignment_outputs` rows) on
  `ingest-flat` / `ingest-graph`, the drain's own Run on `drain` — so a retried ingest Consignment (same members,
  same id) keeps each attempt's trail instead of losing the second to `DO NOTHING`.
- **Immutable: `ON CONFLICT … DO NOTHING`.** A receipt is written once; a re-run of a pinned job-lane Consignment
  appends under its own `run_id`.
- **`recorded_at`** is fixed-width UTC (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`), so its text order is its time order and
  retention compares text on DuckDB and Postgres alike.
- **Switch:** `-Dconsignment.steps=false` records nothing (`ConsignmentOutputStores.stepsEnabled()`, read per call);
  the table is still created. **Retention:** `db_maintenance` deletes rows older than
  `-Dconsignment.steps.retentionDays` (default 30; below 1 keeps everything) before its CHECKPOINT / VACUUM.
- **Writers today:** the at-rest job lane (`PipelineJobRunner`, lane `job`), the ingest lanes
  (`ConsignmentIngestor`, lanes `ingest-flat` / `ingest-graph`, park included) and the drain (`DrainCommand`, lane
  `drain`) — X5 L1 and L3, [execution lanes](../elt/spec-pipeline-execution.md). The read route
  `GET /consignments/{consignmentId}` is L4. Not listed in the raw table browser
  (`browseTables()` names `consignment_outputs` only).
- **Reader (X5 L4, 2026-11-17):** `GET /consignments/{consignmentId}` (`ConsignmentRoutes`) reads
  `steps(id, limit)` / `stepCount(id)` and `outputs(id, limit)` / `outputCount(id)` (cut by `LIMIT` in SQL, the
  count only when cut; `stepsTotal` is always `stepCount`) and, for the cross-lane hop, `consignmentsOfRuns(runIds)` — the **`run_id` join** that names
  each job run's own Consignment, which the run row does not: an `IN` query on this table's `run_id`, then one on
  `consignment_outputs.run_id` for the runs that left no receipt, each chunked at 1 000 ids (PostgreSQL caps a
  statement at 65 535 bind parameters); the oldest Consignment wins per run; a `''` or
  `NULL` run id joins nothing; a failed read throws. Beside it the route reads `DbJobRunStore.runsDerivedFrom(id,
  limit)`, `countRunsDerivedFrom(id)` and `run(runId)` (a run's status after a restart), and `DbStatusStore`
  answers `consignment(cfg, id)` / `consignmentFiles(cfg, id, limit)` with a `LIKE` on the payload's
  `"consignment_id":<id>` text (`!`-escaped wildcards, exact match re-checked, `ORDER BY seq DESC`: the NEWEST row of the id, since a drain
  appends its SUCCESS after the PARKED row; member files keep the newest row per `filename`) — the batches and files tables hold
  the id only inside the JSON payload, and no index or column is added.

### 3.9-b `lookup_misses` — unmatched-key sample (LOOKUP-1)  · **A**
File: `inspecto-consignment-outputs.db`, beside `consignment_steps` (same `ConnectionSource`).

```sql
CREATE TABLE IF NOT EXISTS lookup_misses (
  pipeline VARCHAR, step VARCHAR, consignment_id VARCHAR, recorded_at VARCHAR, mapping VARCHAR, fingerprint VARCHAR, length INTEGER, shape VARCHAR, n BIGINT
);
```

The last run's sample of each keyed `transform.join`: up to 20 distinct values per mapping that found no Reference
row, most frequent first. **Never the value**: `fingerprint` is the Space-salted `FailureText.fingerprint` (the
rejected rows' own), `shape` keeps the first `min(4, length / 3)` characters and masks the rest. `pipeline` is the
receipts' name (`<pipeline>_stage2` at rest). Written by `recordLookupMisses` from `PipelineJobRunner` after the
walk — delete-then-insert for that (pipeline, step) in one transaction, so a clean run clears the old sample; never
for a dry run; best-effort (a WARN). Read by `lookupMisses(pipeline, step)` for `GET /lookups`. Bounded by design
(one run per keyed Step), so no retention.

### 3.10 `file_stages` — per-file stage-progression registry  · **M**
File: `inspecto-file-stages.db`

```sql
CREATE TABLE IF NOT EXISTS file_stages (
  source_id      VARCHAR,
  relative_path  VARCHAR,
  consignment_id VARCHAR,  -- batch_id before 4.0.0; renamed by the rebuild below (D-12)
  stage          VARCHAR,  -- FileStage: REGISTERED | MANIFESTED | OUTPUT_REGISTERED | BACKED_UP | MARKED | WATERMARK_ADVANCED
  recorded_at    VARCHAR,
  UNIQUE (consignment_id, source_id, relative_path, stage)
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
`information_schema.table_constraints`, portable to Postgres. The same rebuild carries the 4.0.0 column rename
(D-12): a table that still has a `batch_id` column, constrained or not, is rebuilt with its rows copied from
`batch_id` into `consignment_id`.

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
On DuckDB a claim is ONE statement (`OPSTORE-BULK-INSERT-1`, 2026-11-01): the hashes go through an Appender into
a per-call TEMP staging table, then `INSERT … SELECT DISTINCT … ON CONFLICT DO NOTHING RETURNING key_hash` inserts
them all, and `RETURNING` (only the inserted rows) is the won set. One call carries every distinct key of a
Consignment's window, and a row per statement ran at a few hundred rows/s. Postgres keeps the row-per-statement
claim: its pool is truly concurrent and per-row autocommit cannot deadlock two overlapping claims.
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
value, not the per-family `*.db.url`: a raw `jdbc:duckdb:` backend is a first-class source (a raw
`jdbc:postgresql:` one is refused, operator 2026-10-06, §5.0-c) that both
`ServiceStores` and `OperationalDb.resolve` short-circuit on, so `urlFor` is never consulted —
setting `-Ddedup.ledger.db.url` instead defeats the shared `-Dinspecto.db` selection that
`OperationalDbTest` pins across all the core families and `StoreFamilyParityTest` (ops) across all sixteen families (it fails those tests). Tests needing durable dedup
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

### 3.14 `inspecto_alerts` — Alert records  · **M**
File: `inspecto-alerts.db` (`SpaceRoot.alertsDbUrl()`; family `ALERTS`, `-Dalerts.backend`, default `db` on every edition)

```sql
CREATE TABLE IF NOT EXISTS inspecto_alerts (
    id           VARCHAR NOT NULL PRIMARY KEY,
    scope        VARCHAR NOT NULL,
    severity     VARCHAR,
    title        VARCHAR,
    message      VARCHAR,
    attrs_json   VARCHAR NOT NULL,
    fired_json   VARCHAR,
    state        VARCHAR NOT NULL,
    incident_id  VARCHAR,
    opened_at    BIGINT  NOT NULL,
    closed_at    BIGINT  NOT NULL,
    closed_by    VARCHAR
)
```

One row per Alert `AlertService` opened (the `AlertLifecycle` `OPEN → ACKNOWLEDGED → RESOLVED`, resolved
terminal; it was the `ALERT` object workflow until the enum value was retired). `attrs_json` carries the de-duplication attributes (`rule`, `alertKey`, ...); `fired_json` is the fired
`Alert.toMap()` verbatim, which is what re-seeds `GET /alerts` after a restart byte-compatibly; `incident_id` is the
Incident escalated from it (the Incident side keeps an `ESCALATED_FROM` link of kind `ALERT` + this id). Active-Alert
reads pull the Space's non-terminal rows of one scope and filter the JSON in Java — the open set only, never the history.
⚠ Nothing prunes resolved rows yet (a retention row is not filed).
