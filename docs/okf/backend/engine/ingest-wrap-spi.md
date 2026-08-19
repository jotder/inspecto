# The ingest wrap-SPI — feeding non-DuckDB formats into the DuckDB execution core

**Status:** as-built (canonized 2026-08-19, E3 of the delimited-grammar-properties plan Part II).
**Direction it implements:** DuckDB *is* the execution core — it loads and writes everything;
formats DuckDB cannot read natively **wrap** the core by parsing records in Java and feeding them
in. This concept names the shipped seam so nobody rebuilds it.

## The SPI pair

- **`StreamingFileIngester`** (`inspecto-etl`, `com.gamma.etl`) — `ingest(File, RecordSink, srcId,
  PipelineConfig)`. One implementation per binary/grammar-driven format. Chosen **by config**
  (`processing.ingester` / `parsing.plugin.ingester`, an FQCN instantiated reflectively) — this is
  deliberate and correct for config-chosen implementations; it is *not* a `ServiceLoader` candidate,
  because the config names one class, not a discovered set.
- **`RecordSink`** (`inspecto-etl`) — `define / emit / reject / junk`, keyed by segment. What a
  wrap implementation calls; it never touches DuckDB directly.

## The one sink implementation

**`DuckDbRecordSink`** (`inspecto-engine`, `com.gamma.inspector`) buffers 10 000 rows per segment
and flushes through the **JDBC `DuckDBAppender`** — never staged CSV, never row INSERTs — then runs
the *identical* downstream as native CSV ingest: `DataTransformer.materialize` →
`PartitionWriter.write` → `LineageCollector`. Since B4 it also honors `output.filename_column`
(the per-row source-file lineage column), and since E1 an **unkeyed segment schema writes a flat
unpartitioned store** instead of the retired `year=1900/month=01/day=01` sentinel bucket.

Two drive modes exist above it:

- **`GenerationModeIngester`** — huge single files; bounded generation flushes
  (`processing.streaming.flush_records`) cap scratch per generation.
- **`UnionModeIngester`** — many small files; union them, then one transform/write pass.
  `processing.streaming.generation_threshold_bytes` picks the mode per batch.

**Reference implementation:** `Asn1RecordIngester` (`com.gamma.ingester`) — Java decodes BER/DER
records against an X.680 grammar and emits them per segment; everything downstream is the shared
DuckDB path. This is exactly the operator's "feed Java-parsed records into it".

**Intermediate CSV is a fallback for *out-of-process* producers only.** The in-process Appender
path is already built, faster, and avoids a second disk write — never demote to staging CSV from
inside a `StreamingFileIngester`.

## The partition declaration — two contracts, deliberately

The word `partitions[]` appears in two homes with two *deliberately different* readers
(E2's unification was refused 2026-08-19 — the difference is posture, not drift):

- **Ingest schema** (`PartitionDef.fromSchema`, `inspecto-etl`): requires a `type` per entry,
  hard-fails on a malformed list, reads the legacy `partitionKey:` — fail-closed, because a wrong
  partition layout at ingest corrupts the store.
- **Sink node** (`SinkPartitions`, `inspecto-engine`): bare column or `{column, source}`, degrades
  per D3 — a write whose bytes are good must not fail over an advisory declaration; entries with no
  usable `column` are refused with a named error before a byte is written.

Both feed the ONE writer: `PartitionWriter` owns partitioned `COPY … PARTITION_BY` **and** (E1)
the unpartitioned single-file `COPY`, with staging + atomic per-file reveal for both, on both lanes.

## The contract test

`BatchProcessorPluginTest` (`inspecto-engine`) is the end-to-end pin: a toy
`StreamingFileIngester` drives records through the whole wrap into a **partitioned** store
(CALL/SMS segments, `event_type/year/month/day`) and — `unkeyedSegmentWritesAFlatStoreWithLineage`
— into an **unpartitioned flat** store, asserting rows, layout (no sentinel), and the lineage
ledger. The concurrency claims of the finalization stores are pinned at the ledger by
`BatchAuditWriterTest.concurrentFlushesKeepEachBatchBlockContiguous` (E4, narrowed — see the plan),
and since 2026-08-19 by **`FinalizeSourceConcurrencyTest`** (the E4 remainder, ex-BACKLOG §4 (b)):
8 distinct batches finalizing concurrently through the SHARED `DbConsignmentOutputStore` /
`DbAcquisitionLedger` / `DbFileStageStore` (registry reconciliation, ledger PROCESSED, the full
crash-ordered stage trail, one manifest per batch), plus the same-file marker race — exactly ONE
loser, on the atomic `Files.createFile`. ⚠ The race fixture deliberately has no backup dir (a
same-file `Files.move` race throws platform-dependent exceptions before the marker), and the
harness pins exactly the stores' own `synchronized`+one-connection claim — a future connection
pool must re-run it.

## Related

- [`pipeline-test-run.md`](pipeline-test-run.md) — Build→Test→Run containment
- [`consignment-addressing.md`](consignment-addressing.md) — Selector / `retire_superseded` over the stores
- [`output-sinks.md`](output-sinks.md) — the graph-lane sink kinds
- `docs/okf/backend/config/parsing-options-reference.md` — the delimited/native frontends this SPI wraps around
