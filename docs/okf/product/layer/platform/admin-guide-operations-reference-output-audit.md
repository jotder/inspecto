---
type: Reference
title: Operations reference — utilities, batching, output, deployment — Output structure and the status log
description: Part 1 of 4 of Operations reference — utilities, batching, output, deployment: Pre-ETL file staging — retired; Output Structure; Status Log & Auditing
tags: [operations, utilities, deployment, batching, output, onboarding]
timestamp: 2026-07-16T00:00:00Z
---

# Operations reference — utilities, batching, output, deployment — Output structure and the status log

Part 1 of 4 of [Operations reference — utilities, batching, output, deployment](admin-guide-operations-reference.md) · next: [Batch processing](admin-guide-operations-reference-batch-processing.md).

## Pre-ETL file staging — retired

The `ura` utility CLI (`bin/ura.sh` / `bin/ura.bat`, the "URA File Management Suite": `search`, `copy`,
`copy-tars`, `extract`, `backup`, `prepare-inbox`, `create-schema`, `move-by-date`, `extract-unknown`,
`extract-move`) was **retired 2026-11-06** and no bundle ships it. Its pipeline sections `search:`,
`copy_tars:` and `backup:` (the CLI's, not the engine's `backup` Guarantee) are read by nothing. Bring files in
through **Add data** or a Collector; reprocess a batch with `POST /runs/{pipeline}/reprocess` (below).

---

## Output Structure

Each input file produces one output file per partition it touches. The output file is named after the input file — no UUID noise.

```
database/<data_source>/
  year=1900/
    month=01/
      day=01/
        adj_DATE_20200116_out.parquet     ← REVERSAL_DATE sentinel (01-JAN-1900)
  year=2000/
    month=01/
      day=01/
        adj_DATE_20200101_out.parquet
        adj_DATE_20200403_out.parquet

errors/<data_source>/
  adj_DATE_20200403_errors.csv            ← only created when rows are rejected

quarantine/<data_source>/
  field_mismatch/
    wrong_source.csv                      ← 0 valid rows parsed
  unreadable/
    corrupt.csv.gz                        ← IOException during read
```

**Filename derivation:** `adj_DATE_20200403.csv.gz` → strip `.gz` → strip `.csv` → append `_out.parquet` → `adj_DATE_20200403_out.parquet`.

**Partition key:** The `partitionKey` field in the schema config names the column whose value drives `year`, `month`, and `day`. Rows where the partition key is NULL (unparseable date) land in `year=NULL/month=NULL/day=NULL`.

---

## Status Log & Auditing

Each ETL run creates a new, timestamped status CSV in `dirs.status_dir` named `<pipeline_name>_status_<yyyyMMdd_HHmmss>.csv`. Runs never overwrite each other — each run is isolated in its own file. One row per processed file.

```
start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error
```

| Column | Description |
|---|---|
| `start_time` | ISO datetime when file processing started |
| `end_time` | ISO datetime when it completed |
| `filename` | Input file name |
| `status` | `SUCCESS`, `FAILED`, `QUARANTINED_MISMATCH`, or `QUARANTINED_UNREADABLE` |
| `parsed_rows` | Rows successfully ingested into DuckDB staging table |
| `error_rows` | Rows rejected (insufficient columns, unparseable structure) |
| `output_paths` | Semicolon-separated list of output file paths |
| `output_sizes_bytes` | Semicolon-separated file sizes in bytes |
| `duration_ms` | Wall-clock processing time in milliseconds |
| `error` | Exception message when `status=FAILED`; empty otherwise |

**Example:**
```
2026-05-19 15:34:36,2026-05-19 15:37:57,adj_DATE_20200403.csv.gz,SUCCESS,419906,0,
  "database/year=2000/month=01/day=01/adj_DATE_20200403_out.parquet","1329331940",200878,""
```

### Error CSV

When rows are rejected during ingestion, a per-file error CSV is written to `errors/<source>/<basename>_errors.csv`. The file is **only created when at least one row is rejected** — no file is written for clean inputs, keeping the directory uncluttered.

```csv
line_number,columns,reason,value_fingerprint,source_file,source_size,source_sha256
42,"","Insufficient columns (expected >477, found 3)",fp:3f9a0c1d2e4b5a69,"feed.csv",18422,9c1e…
```

Values are never stored (2026-10-03): `line_number` is the record's physical start line in the source,
`value_fingerprint` a salted per-Space hash of the record. `POST /runs/{name}/replay-rejects` re-reads the
records from the source file (inbox, `backup/` or quarantine) and refuses 410 when it is gone, 409 when it changed.

### Quarantine

Files that cannot be processed at all are moved out of the inbox into `quarantine/<source>/` so they are never retried and are clearly visible for manual inspection.

| Status | Trigger | Quarantine sub-directory |
|---|---|---|
| `QUARANTINED_MISMATCH` | 0 valid rows parsed — every row fails field validation | `quarantine/<source_path>/field_mismatch/` |
| `QUARANTINED_UNREADABLE` | `IOException` thrown while opening or streaming the file | `quarantine/<source_path>/unreadable/` |
| *(corrupt download)* | a fetched remote file fails its integrity check (size/checksum) | `quarantine/<source_path>/corrupt_download/` |
| *(retry exhausted)* | the file's Consignment failed at COMMIT (or its ingest threw) `-Dingest.retry.max` times — X1, 2026-09-02 | `quarantine/<source_path>/retry_exhausted/` |

The `retry_exhausted` reason is the ingest lane's **bounded retry** (execution residuals X1). A Consignment
that fails at COMMIT leaves its files in the inbox and the next poll cycle re-ingests them — that IS the
retry, idempotent by the commit model. It used to be unbounded (a poison Consignment failed every cycle
forever). Now each member file carries an attempt record under `<status_dir>/retries/` (mirrored by
poll-relative path like the markers); a file waiting out its exponential backoff
(`-Dingest.retry.backoff.initialMs` 60000 × 2ⁿ⁻¹, capped at `-Dingest.retry.backoff.maxMs` 3600000, ±10%
jitter) is skipped on the run path but still counted as **pending**; on the `-Dingest.retry.max`-th
failure (default 5) the file is quarantined here and ONE CRITICAL `pipeline.batch.retry_exhausted` Signal
names the Consignment, its files and the last error — the Signal an alert rule should watch for a batch
that will never commit on its own. `-Dingest.retry.max=0` restores the pre-X1 unbounded behaviour. A
commit (or park) spends the record. ⚠ The outputs an exhausted Consignment wrote before its failed commit
are on disk but uncommitted — exactly as they were after every failed attempt; `reprocess` is the redo.

The `corrupt_download` reason is the acquisition-stage **dead-letter** (Data Acquisition Phase F): a fetched file
whose bytes don't match the listing size or server checksum is quarantined for inspection rather than ingested or
silently deleted; if no quarantine directory is configured it is deleted so it is never processed corrupt.

`QUARANTINED_MISMATCH` catches two cases:
- **Appender rejection** — rows reach the appender but fail column-count (`errorRows > 0`)
- **Junk-scan exhaustion** — all rows have fewer columns than the schema expects and never exit junk detection; the appender loop is never entered

The `<source_path>` mirrors the file's subdirectory path relative to the poll root. For `QUARANTINED_MISMATCH`, the companion `_errors.csv` is moved alongside the quarantined file so the rejection evidence is co-located.

---
