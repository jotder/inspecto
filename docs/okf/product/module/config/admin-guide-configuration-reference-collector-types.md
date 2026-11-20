---
type: Reference
title: Configuration reference — The collector block, type mapping and the Postgres state store
description: Part 2 of 2 of Configuration reference: Type Mapping Reference; Optional Postgres state store (DAT-6)
tags: [config, toon, spaces, type-mapping, postgres]
timestamp: 2026-07-16T00:00:00Z
item: module.config
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-config
---

# Configuration reference — The collector block, type mapping and the Postgres state store

Part 2 of 2 of [Configuration reference](admin-guide-configuration-reference.md) · previous: [Configuration reference and Spaces](admin-guide-configuration-reference-spaces.md).

## Spaces (multi-project layout) (continued)

### Data acquisition — the `collector:` block

> ⚠ The block is `collector:`. `source:` is a key
> `PipelineConfigParser` never reads (only `collector:` is parsed; a `source:` block is silently
> ignored). The parser's own error messages still say `source.dataset` for `collector:` keys —
> BACKLOG row filed. ⚠ Also note: the collector-level `duplicate:` block is a **no-op on the
> legacy local poll path** — file-grain dedup there is `processing.duplicate_check` (markers);
> see [pipeline-config-keys](spec-pipeline-config-keys.md).

By default a pipeline acquires files exactly as it always has: it scans the local `dirs.poll` tree for
`processing.file_pattern`. An **optional, additive** top-level `collector:` block makes acquisition pluggable —
selecting a connector (local / SFTP / FTP), gating half-written files, deduplicating by content, detecting gaps
in an expected series, and (for remote sources) fetching with retries, integrity checks, parallelism, rate
limits, and source-side post-actions. **A pipeline with no `collector:` block is byte-for-byte unchanged** — every
sub-block below defaults to today's behaviour, and each is parsed only when present, so features can be adopted
one at a time.

```yaml
collector:
  connector: sftp                 # local (default) | sftp | ftp
  connection: prod_sftp           # id of a *_connection.toon profile (remote connectors only)
  include[1]: "glob:**/*.csv"     # defaults to processing.file_pattern; glob: or regex: prefixes
  exclude[1]: "glob:**/_*"        # patterns removed from discovery
  recursive_depth: -1             # -1 = unbounded
  stability:                      # readiness gate — never ingest a still-arriving file
    window: 30s
    size_checks: 2
    ready_marker: "{name}.done"
    exclude_temp_files: true      # also drops *.tmp,*.partial,*.filepart,.~lock.*
  duplicate:                      # dedup / change policy
    mode: CHECKSUM                # PATH (default = today's markers) | METADATA | CHECKSUM | ETAG | CONTENT
    algorithm: SHA256             # MD5 | SHA256 | CRC32
    on_change: REPROCESS          # IGNORE | REPROCESS | ALERT | ARCHIVE_OLD_VERSION
  incremental:                    # only re-examine the recent frontier each scan
    watermark: last_modified      # last_modified (etag/version future); needs a content-based duplicate.mode
  guarantee: EXACTLY_ONCE         # BEST_EFFORT (default) | AT_LEAST_ONCE | EXACTLY_ONCE
  gap_detection:                  # alert on a hole in an expected series
    enabled: true
    sequence: "CDR_{yyyyMMddHH}"
  fetch:                          # retrieval tuning (remote)
    parallel_fetch: 8             # >1 ⇒ pool of independent connector sessions
    rate_limit: 50MBps            # token-bucket; 50MB/s, 512KBps, or a bare bytes/s number
  retry:                          # transient-fault retry/backoff (remote)
    count: 5
    backoff: EXPONENTIAL          # EXPONENTIAL | LINEAR | FIXED (all full-jittered)
    initial_delay: 30s
    max_delay: 15m
  circuit_breaker:                # stop hammering a dead endpoint
    failure_threshold: 5
    cooldown: 5m
  post_action:                    # finalize the source file after success (remote)
    on_success: MOVE              # RETAIN (default) | DELETE | MOVE | RENAME | TAG
    archive_path: archive/yyyy/MM/dd
    on_unsupported: WARN_AND_CONTINUE  # FAIL | WARN_AND_CONTINUE | IGNORE
```

**`connector`** — `local` (default) reads `dirs.poll`. `sftp`/`ftp` are served by the optional
`inspecto-connectors` module (see *Integrations*); these require a `connection` profile. An unknown connector
fails fast at startup.

**`connection`** — the id of a reusable `*_connection.toon` profile (host/port/base_path/credentials/tunnel),
resolved at runtime. Secrets in a profile are **references** (`${ENV:…}`/`${SYS:…}`), never literals in the file.

**`include` / `exclude` / `recursive_depth`** — discovery filters. A bare pattern or `glob:` is a path glob;
`regex:` is a Java regex over the forward-slash relative path. `include` defaults to `processing.file_pattern`.

**`stability`** — the readiness gate (the single biggest production safety win): a discovered file is held back
until it has been quiescent for `window` *and* seen at the same size on `size_checks` consecutive poll cycles, so
a file mid-write is never ingested. `ready_marker` (`{name}.done` sibling) is a native readiness signal that
short-circuits the size/mtime wait. The gate is idempotent under repeated polling — a dashboard "pending" count
never steals a hot file's progress.

**`duplicate`** — `PATH` (default) is today's `.processed` marker. `METADATA` keys on name+size+mtime (a cheap
`stat`, no read). `CHECKSUM` hashes content (catches re-uploads that size+mtime hide; one extra read per file, so
it is opt-in). `CONTENT` hashes too and matches the bytes under any name, so a file re-sent under a new name is
skipped as well. `on_change` decides what happens when a known path's content changed. The fingerprint lives in a
dedicated DuckDB ledger (its own file, single-writer).

**`incremental`** — the high-watermark optimisation: `watermark: last_modified` makes each scan skip any file
modified **strictly before** the source's high-watermark — the greatest `last_modified` the ledger has recorded
for the source. So a re-scan only re-examines the recent frontier instead of re-LIST'ing/re-fetching (remote) or
re-stat'ing (local) the whole history; for a remote source the skip happens **before fetch**, so old objects cost
no bandwidth. The watermark is *derived* from the ledger, so this needs a content-based `duplicate.mode`
(metadata/checksum) — over the path-only default the ledger is empty and it no-ops (the engine warns). It is an
optimisation for **monotonic-arrival** sources (timestamps that only increase); a file re-uploaded below the
watermark is intentionally skipped, so leave it off if you must catch arbitrarily back-dated re-uploads. Skips
are counted in `inspecto_watermark_skipped_total`.

**`guarantee`** — declarative; `AT_LEAST_ONCE`/`EXACTLY_ONCE` need a content-based `duplicate.mode` (metadata or
checksum) to hold. The engine **warns** if a stronger guarantee is set over path-only dedup and behaves as
best-effort + commit-log replay in that case.

**`gap_detection`** — checks discovered names against a `sequence` template (literal text around one `{…}`
Java-date token) and emits a `SEQUENCE_GAP` event per missing key in the series; the service tier promotes that
to a managed ALERT object (trackable in Cases/Issues) with no extra config.

**Fetch integrity** *(remote, always on — there is no `integrity:` key)* — every fetched file is checked by
`IntegrityChecker.verify` from `RemoteAcquisitionHandler`: size vs. the listing (skipped when the listing has
no size), and checksum vs. the server etag when present, hashed with `duplicate.algorithm`. A failure discards
the bytes to quarantine (`corrupt_download`) and skips the file — never processed corrupt.

**`output.ducklake.*`** *(declared in `ConfigSpecs.pipeline()` since 2026-09-23)* — the five leaves
`DuckLakeRegistrar` reads: `enabled` (BOOL, default `false`), `catalog_url`, `data_path` (FILEPATH), `schema`
(default `main`) and `table`. All are optional in the spec; `ConfigSafetyValidator` requires `catalog_url` /
`data_path` / `table` when `enabled` is true and jails `data_path`. A `sinks[]` entry's own `ducklake` block
overrides this one. See [output sinks](../elt/spec-sinks.md).

**`fetch`** *(remote)* — `parallel_fetch > 1` fetches over a pool of independent connector sessions (the clients
hold one non-thread-safe session each, so concurrency uses extra sessions, not shared reuse). `rate_limit` is a
shared token bucket in bytes/s — accepts `50MBps`, `50MB/s`, `512KBps`, `2GBps`, or a bare bytes/s number (KB/MB/GB
are 1024-based).

**`retry`** *(remote)* — `count` retries with `backoff` (full jitter, bounded `initial_delay`…`max_delay`) wrap
discovery and each fetch, so a transient SFTP/FTP hiccup doesn't fail the cycle. Absent ⇒ a single attempt.

**`circuit_breaker`** *(remote)* — after `failure_threshold` consecutive connectivity failures the source trips
OPEN and is skipped for `cooldown` (then one trial), emitting `SOURCE_CIRCUIT_OPEN`, instead of hammering a dead
endpoint every cycle.

**`post_action`** *(remote)* — after a file is fetched, validated, and staged, optionally finalize the
**source-side** copy: `DELETE`, `MOVE` (into a date-resolved `archive_path`), `RENAME` (`processed_<name>`), or
`TAG`. The action is validated once per cycle against the connector's capabilities; `on_unsupported=FAIL` stops
the run before any byte moves, the others degrade to RETAIN. A runtime post-action failure never discards
already-staged good data.

> **Compression on the read path:** alongside `.gz`, the streaming Java ingest path now also transparently reads
> **`.bz2`** and **`.zip`** (first entry) inputs — list them in `processing.file_pattern` (e.g.
> `glob:**/*.{csv,csv.gz,csv.bz2,csv.zip}`). DuckDB's native path already handles `.gz`.

---

### Fixed-width frontend (`frontend: fixedwidth`)

For column-positional records (no delimiter), set `frontend: fixedwidth` in the grammar/`csv_settings`
and declare the byte/character geometry in a `fixedwidth:` block. **Only the tokenisation lives here —
the event `_schema.toon` (field names, types, mapping rules, partitions) is authored exactly like a
delimited source.** Slice index *i* feeds the schema field whose `selector` is *i*.

```yaml
# inline under processing.csv_settings, or in a reusable processing.grammar file
frontend: fixedwidth          # delimited (default) | fixedwidth
has_header: false
date_formats[1]: "%Y-%m-%d"
fixedwidth:
  record: line                # line (newline-delimited text) | bytes (fixed-length binary)
  record_length: 0            # REQUIRED when record: bytes; ignored for line
  trim: both                  # none | left | right | both  (default both)
  min_record_length: 0        # drop shorter lines (blanks/footers); 0 ⇒ default = widest slice end
  fields[3]{name,start,length}:   # start is 0-based; length in chars (bytes for record: bytes)
    ACCOUNT_NUMBER,0,6
    EVENT_DATE,6,10
    AMOUNT,16,8
```

- **`record: line`** (text) is parsed **natively** by DuckDB (`read_csv` reads each line as one VARCHAR
  column — empty `delim`/`quote`/`escape` so a line is never split or quote-merged — then `substring`
  carves each field), reusing the whole CSV streaming/union/chunk path. It is always native regardless
  of `engine`. A field's `selector` must index a declared slice or the config fails to load.
- **`record: bytes`** (binary) is handled by the shipped `com.gamma.ingester.FixedWidthRecordIngester`
  plugin — wire it via `processing.ingester` + `processing.segments` + `ingester_config` (see
  [Plugin Ingester](../elt/spec-ingest.md)), not the
  `fixedwidth:` block above.
- Worked example: `spaces/default/config/subscriber/` (`subscriber.grammar.toon` + `subscriber_schema.toon`
  + `subscriber_pipeline.toon`).

---

### Large files: scratch location & auto-chunking

Each batch runs on a per-batch **embedded DuckDB temp database**, and DuckDB **spills** intermediate
data to disk when it exceeds its memory budget. By default both land in the JVM temp dir
(`java.io.tmpdir`, i.e. the system `/tmp`) — which is often small or RAM-backed (`tmpfs`). A very large
input (tens of GB to TB) will exhaust it with `No space left on device` / out-of-memory. Two additive
config blocks under `processing` make the engine handle big files without touching `/tmp`
(`processing.duckdb` is opt-in; `processing.chunking` is on by default at an 8 GiB threshold).

#### `processing.duckdb` — relocate & cap engine scratch

```yaml
processing:
  duckdb:
    temp_directory: temp/<data_source>      # where the temp DB + DuckDB spill live (default: dirs.temp)
    memory_limit: "16GB"                     # RAM cap before spilling (default: 40% of RAM ÷ 4, min 1 GiB)
    max_temp_directory_size: "900GB"         # cap spill so a runaway query fails fast, not the disk
```

| Key | Default | Effect |
|---|---|---|
| `temp_directory` | `dirs.temp` | Directory for the per-batch temp database **and** DuckDB's spill scratch. **As of 3.10.0 the engine no longer uses the system `/tmp`** — scratch defaults to the pipeline's `dirs.temp` (on the data volume). Set this to override (point at the roomiest/fastest disk). |
| `memory_limit` | **40 % of RAM ÷ 4, floored at 1 GiB** (GAP-4, 2026-09-26) | RAM cap per worker connection (DuckDB size string, e.g. `"16GB"`, or DuckDB's proportional `"80%"`). Precedence: this key > the served `scheduler.toon` value > `-Dprocessing.duckdb.memory_limit` > the code default `DuckDbUtil.defaultMemoryLimit()` (a 32 GiB host ⇒ `3276MiB`; 8 GiB ⇒ `1024MiB`). Set `"80%"` to get DuckDB's own per-instance default back. DuckDB spills to `temp_directory` beyond it. |
| `max_temp_directory_size` | DuckDB default | Hard cap on spill size — a pathological query fails fast instead of filling the disk. |

> **This is usually the only change needed for a large single file.** With scratch on a roomy data
> volume, DuckDB's native `read_csv` is internally multi-threaded, so a single large file still uses all
> cores. Budget roughly **1–3× the decoded file size** of free space on `temp_directory` for the
> transient transform table + spill.

#### `processing.chunking` — bound scratch for arbitrarily large files

```yaml
processing:
  chunking:
    max_file_bytes: 5000000000               # default 8589934592 (8 GiB). Files larger than this are chunked; 0 = disabled.
    target_chunk_bytes: 2000000000           # approx size of each chunk (default: max_file_bytes)
```

Chunking is **on by default**: `max_file_bytes` defaults to **8 GiB** (`8589934592`), chosen far above any
routine input so normal workloads never change shape — the threshold only catches pathological single files.
Set it to `0` to disable chunking entirely.

When a single input file exceeds `max_file_bytes`, the CSV ingester **streams it into bounded chunks**
of ~`target_chunk_bytes` and processes them one at a time — so peak scratch stays ~one chunk regardless
of total file size, instead of materialising one multi-hundred-GB unit. Details:

- Only applies on the **native `read_csv` path** (clean files: `skip_junk_lines`/`skip_tail_lines`/
  `skip_tail_columns` all `0`). Messy SQL\*Plus dumps (Java engine) are not chunked.
- Each chunk reproduces the source's leading context (the `skip_header_lines` preamble + header row),
  so the same config reads it; `.csv.gz` inputs are decompressed on the fly.
- Chunks write their own per-partition output files (`<base>_cNNNNN_out.<ext>`), which coexist in the
  partition directories (valid Hive layout). The **original file** remains the unit for audit, lineage,
  markers, and backup — so idempotency and commit semantics are unchanged.
- Exactly one chunk file exists on disk at a time (it's deleted after processing), so chunk staging
  doesn't itself need room for the whole file.

> **Rule of thumb:** if `/tmp` can't grow, set `processing.duckdb.temp_directory` to a big data-volume
> path; if the data volume also can't hold ~1× the file, lower `processing.chunking.max_file_bytes` below
> the default 8 GiB to cap peak scratch to a smaller chunk. `processing.duckdb` is off/inherited by default;
> chunking is on but at a threshold no routine input reaches, so existing pipelines are unchanged.

#### `processing.streaming` — plugin-ingester mode selection (the `StreamingFileIngester` path)

`processing.chunking` above bounds the **CSV** path. Plugin ingesters (`processing.ingester`) get an
analogous control: the framework runs the same `StreamingFileIngester` in one of two modes, chosen per
batch by file size.

```yaml
processing:
  ingester: com.acme.etl.MyCdrIngester
  segments:
    CALL: spaces/default/config/events/call_schema.toon
  streaming:
    large_file_bytes: 268435456     # default 256 MB. A batch whose largest member is ≥ this runs in
                                     # bounded GENERATION mode (huge files); smaller batches use UNION
                                     # mode (many small files → one transform/write). 0 = always union.
    flush_records: 5000000          # rows per generation flush in generation mode (bounds scratch).
  batch:
    max_files: 1000                 # pack many small files per union batch (raise for the many-small case)
```

- **Union mode** (default for files under the threshold) consolidates a batch's members into one
  transform/write and one set of partition output files — the right choice for an exceptionally large
  *number of small files*. Pair it with a high `batch.max_files` so the planner packs many files per batch.
- **Generation mode** (members ≥ `large_file_bytes`) flushes bounded generations so a genuinely huge
  single file processes with bounded heap and scratch; it writes per-generation output files
  (`<stem>_gNNNNN_out.*`).

Both are optional and have working defaults; see [plugins.md](../elt/spec-ingest.md) for the full SPI guide.

---

## Type Mapping Reference

Transformations are applied by DuckDB during the `CREATE TABLE AS SELECT` step.

| Schema type / transform | DuckDB expression | NULL behaviour |
|---|---|---|
| `DATE` | `COALESCE(TRY_STRPTIME(col, 'fmt1'), TRY_STRPTIME(col, 'fmt2'), ...)::DATE` | Unparseable value → `NULL` |
| `TIMESTAMP` | `COALESCE(TRY_STRPTIME(col, 'fmt1'), TRY_STRPTIME(col, 'fmt2'), ...)::TIMESTAMP` | Unparseable value → `NULL` |
| `TIMESTAMP` + field `format: epoch` / `epoch_ms` | `make_timestamp(<whole count> * 1000000)` (`* 1000` for `epoch_ms`) when the count is inside the `TIMESTAMP` range — the UTC time, no source zone applied ([§2.2](../elt/spec-ingest.md)) | `0`, blank → `NULL` (not a failure); non-integer or out of range → `NULL`, counted |
| `DOUBLE` | `TRY_CAST(col AS DOUBLE)` | Non-numeric value → `NULL` |
| `VARCHAR` | Direct column reference — no cast | Never NULL from cast |
| `FILENAME_DATE` | `TRY_STRPTIME(regexp_extract(col, 'PREFIX([0-9]{8})', 1), '%Y%m%d')::DATE` | No regex match → `NULL` |

Multiple date/timestamp formats are tried left-to-right via `COALESCE`. Common Oracle patterns:

| Format string | Example value |
|---|---|
| `%d-%b-%y` | `01-JAN-00` |
| `%d-%b-%Y` | `01-JAN-2000` |
| `%d-%b-%Y %H:%M:%S` | `01-JAN-1900 00:00:00` |

---

## Optional Postgres state store (DAT-6)

Six operational-state stores persist through plain JDBC and default to the **bundled DuckDB** engine (a
local file per space, zero extra dependency). Each can instead point at **PostgreSQL** for a shared /
distributed, multi-writer deployment — one Postgres can back several Inspecto nodes, whereas a file-based
DuckDB holds a single-writer lock. The engine is chosen per store by process-global `-D` flags:

| Store | Backend toggle | URL property | Purpose |
|---|---|---|---|
| Status projection | `-Dstatus.backend=db` | `-Dstatus.db.url` | queryable projection of the run audit |
| Operational objects | `-Dobjects.backend=db` | `-Dobjects.db.url` | mutable alerts/incidents/cases |
| Object links | `-Dobjects.backend=db` | `-Dobjects.links.db.url` | correlation graph (append-only) |
| Object notes | `-Dobjects.backend=db` | `-Dobjects.notes.db.url` | evidence / comments (append-only) |
| Link Analysis Investigations | `-Dinvestigations.backend=db` (default `fs`; Enterprise bundles carry the module) | `-Dinvestigations.db.url` / `.user` / `.password`, else `-Dinspecto.db.url` / `.user` / `.password` | sealed Investigation log, Working Set sets, members, Drafts (one `space_<id>` schema per Space). PostgreSQL only; selected-but-unusable is `503`, never a filesystem fall-back, and never a boot refusal: WARN + `live.investigations` DOWN on `/health/details`; the connection properties need a restart to change. A sealed Working Set over the Space's `max_set_bytes` (`link-analysis.toon`, default 64 MiB, 1 KiB..1 GiB) is refused `413 PAYLOAD_TOO_LARGE` on both stores and audited as `LINK_WORKING_SET_TOO_LARGE`; one Investigation's TOTAL of sealed sets (main plus live Drafts) over `max_investigation_bytes` (`link-analysis.toon`, default 4 GiB, 1 MiB..1 TiB) is refused the same way (counter `sets.bytes` / `la_investigation.set_bytes`) and audited as `LINK_INVESTIGATION_TOO_LARGE` |
| Job runs | `-Djobs.backend=postgres` (or `duckdb`) | `-Djobs.db.url` | job-execution reporting (success rate, p50/p95) |
| Pipeline provenance | `-Dprovenance.backend=postgres` (or `duckdb`) | `-Dprovenance.db.url` | per-edge record counts (T21) |

To use Postgres, give the URL property a `jdbc:postgresql://host:port/db` value and set the store's
backend flag. `objects/links/notes/status` accept any `jdbc:` URL directly on their URL property; `jobs`
and `provenance` additionally accept the case-insensitive aliases `postgres`/`postgresql` on their
`*.backend` flag (which then read the matching `*.db.url`), alongside the pre-existing `duckdb` and raw
`jdbc:` forms. Credentials are `-D<store>.db.user` / `-D<store>.db.password` (or embed them in the URL) —
`objects.db.user`/`.password` are shared by the objects/links/notes trio.

**Driver on the classpath.** The PostgreSQL JDBC driver is **not** bundled in the lean core (SBOM stays
minimal by design); it ships in **inspecto-connectors**. Put that module on the runtime classpath for a
Postgres backend — a `jdbc:postgresql://…` URL with no driver present fails closed with a clear message
and the store degrades to its in-memory / off default.

**Dialect note.** The SQL is otherwise engine-neutral; the one exception is the job-metrics percentiles.
`DbJobRunStore` detects the engine once at connect (`DatabaseMetaData`) and emits the correct continuous
percentile per dialect — DuckDB `quantile_cont(col, p)` vs PostgreSQL `percentile_cont(p) WITHIN GROUP
(ORDER BY col)` — so p50/p95 are correct on both.

---


### `CONFIG-MIGRATOR-LOSES-MAPPINGS-1` — the migrator loses what its javadoc promises to refuse (FIXED 2026-09-17 — the section below is the historical defect record; `writeSchema` now keeps `mapping.fields[]` and `MainApp` strips `--apply`)

Found 2026-09-17 by **driving** `migrate-configs` on a copy of `spaces/demo`, not by reading it.

🔴 **The schema split silently drops every `mapping:` block.** `ConfigMigrator.java` does
`schema.remove("mapping")` unconditionally, then writes the Mapping CSV only when `mapping.rules[]` is a
non-empty list. **All four committed demo schemas carry `mapping.fields[]`; none carries `rules`** —
`MappingCsv.encode` reads `targetColumn`/`sourceExpression`/`transformType` while the corpus authors
`name`/`from`/`fn`/`args`. On the drive, `registry/mappings/` was never created and `registry/schemas/orders.toon`
lost all 8 field mappings, including two SQL expressions, while the original was archived away. Exit 0, no
refusal. ⛔ The class javadoc says *"Refuses rather than loses."*

⚠ **Why no test caught it:** `ConfigMigratorTest`'s `legacySpace()` fixture is the only corpus in the repo
using `mapping.rules[]`. The unit test agrees with itself, not with the space — so any fix must be pinned
against a **committed** schema, not a fixture.

🔴 **The documented `--apply` form writes to a directory named `--apply`.** `MainApp.java` strips
`--dry-run` only, so `--apply` falls into the positional list and becomes `outRoot`. The tool's own usage text
prints this form; it still reports success and still archives the originals.

✅ **The entry point, for the record:** there is no launcher script (the `ura` scripts were retired 2026-11-06), and
`MainApp` lives in the thin `inspecto-engine` jar, not in `inspecto.jar`, so the classpath must be the bundle's
`lib/modules.list`. From the bundle root:

- Linux: `runtime/bin/java -cp "$(tr '\n' ':' < lib/modules.list)" com.gamma.inspector.MainApp migrate-configs <config_root> [<registry_root>] [--apply]`
- Windows (PowerShell): `runtime\bin\java -cp ((Get-Content lib\modules.list) -join ';') com.gamma.inspector.MainApp migrate-configs <config_root> [<registry_root>] [--apply]`

✅ Separately settled by the same drive (`MIGRATE-ENRICH-DRIVE-1`, closed): the post-`ea5a9225` enrich SKIP is
real — 9 conversions / 0 refusals, the enrich config byte-identical and unarchived. ⚠ But `archived-config/`
is a convention for humans, not a runtime quarantine: `ServiceBootstrap.resolveBySuffix` walks the tree with
**no `archived-config/` exclusion**, so archiving would not have hidden the file from `EnrichJob` anyway.

## Changes

- 2026-09-01: this section spelled the block `source:`.
