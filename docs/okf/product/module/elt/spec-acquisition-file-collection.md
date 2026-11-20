---
type: Concept
title: Acquisition — specification — File connectivity, discovery, stability and collection guarantees
description: Part 1 of 2 of Acquisition — specification: Collector (File) Connectivity; File Discovery; File Stability Detection; Duplicate Prevention; File Change Detection; Collection Guarantees; File Retrieval; Parallel Fetching; Post Processing Actions; Failure Handling; Integrity Verification; Observability; Configuration Example; Design Philosophy; What acquisition is, and where it stops; The poll …
tags: [acquisition, requirements, discovery, stability, dedup, watermark, guarantees, unpack, decompression, collector, archive, consignment]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Acquisition — specification — File connectivity, discovery, stability and collection guarantees

Part 1 of 2 of [Acquisition — specification](spec-acquisition.md) · next: [Retry, the connector SPI, connection profiles and control](spec-acquisition-connectors-control.md).

## 1. Collector (File) Connectivity
The system shall support collecting files from multiple source types through a pluggable connector architecture.

**Supported Protocols**
* **Local File System**
  * `inbox/<data_source>`
* **Remote File Systems**
  * FTP
  * SFTP
  * SSH/SCP
  * SSH Tunneling and Proxy — **shipped**: `SshTunnel` for `sftp`/`ftp`/`ftps`/`db`, plus SOCKS5 and HTTP `CONNECT` proxy dial-through, extended to the PostgreSQL JDBC driver
  * SMB/CIFS — via an **OS-mounted share** (see [Network shares](spec-acquisition-connectors-control.md#network-shares--the-declined-design-recorded-once))
  * NFS — via an **OS-mounted share** (same)
* **Database export file**
  * Collect exported file from database table (Postgres) using sql template
  * SSH Tunnel and collect exported file from database (Postgres)
* **Object Storage**
  * Amazon S3 — `connector: s3` (SDK-free, SigV4 in-tree)
  * MinIO — `connector: s3` (`options.protocol: http` for a LAN endpoint)
  * Google Cloud Storage — `connector: gcs` (SDK-free native JSON API + service-account OAuth2); or
    `connector: s3` in GCS interoperability (HMAC) mode
  * Azure Blob Storage — `connector: azure` (SDK-free, SharedKey in-tree; Azurite-compatible)
* **Streaming**
  * Apache Kafka topic — `connector: kafka` (drained per cycle into slice files; offsets in the ledger, no consumer group)
* **Grouping Collector**
  * Has different collection point and file types
  * Tagging

**Extensibility**
New protocols shall be introduced through connector plugins without modifying the core engine.

## 2. File Discovery
The system shall identify candidate files available for processing.

**Capabilities**
* **New File Detection:** Detect newly arrived files.
  * Methods: directory listing, timestamp comparison, object version comparison, event notification (**shipped** — `POST /collectors/{id}/notify`, ACQ-6).
* **Incremental Discovery:** Collect only files newer than the previous successful scan.
  * Maintain: `last_scan_timestamp`, `last_processed_marker`, `high_watermark`.
* **Pattern Filtering:** Support `*.csv`, `*.gz`, `CDR_*.dat`, regex patterns, and exclude patterns.
* **Recursive Scanning:** Support configurable depth (e.g., `inbox/*`, `inbox/*/*`).

## 3. File Stability Detection
*One of the biggest production problems. The system shall avoid processing partially copied files.*

**Stability Checks**
* **Size Stabilization:** File size remains unchanged for N consecutive checks (e.g., 60 sec apart × 2 checks).
* **Last Modified Stabilization:** Ignore files modified within a `stability_window` (e.g., 5 minutes).
* **Temporary File Exclusion:** Ignore `*.tmp`, `*.partial`, `*.filepart`, `.~lock.*`.
* **Remote Upload Detection:** Connector-specific mechanisms.
  * SFTP: upload completed marker
  * S3: object finalized
  * GCS: generation finalized

## 4. Duplicate Prevention
The system shall prevent reprocessing.

**Marker Repository**
Maintain fingerprints. Example: `source_id`, `file_path`, `file_name`, `size`, `checksum`, `last_modified`, `processed_timestamp`, `status`.

**Duplicate Detection Modes**
* **Path Based:** same path
* **Metadata Based:** same filename, same size, same timestamp
* **Checksum Based:** MD5, SHA-256, ETag, CRC32

**Configurable Policy**
Example: `SKIP`, `REPROCESS`, `VERSION`, `FAIL`. *(As built the policy is two orthogonal enums and these four
tokens do not exist — see [Phase C](#phase-c--duplicate-prevention-change-policy-watermarks).)*

## 5. File Change Detection
*Sometimes files get replaced. The system shall detect changes after discovery.*

**Detect**
* **Modified Files:** Same filename, different checksum.
* **Re-uploaded Files:** File removed and uploaded again.
* **Object Version Changes:** For S3/GCS.

**Policies**
`IGNORE`, `REPROCESS`, `ALERT`, `ARCHIVE_OLD_VERSION`.

## 6. Collection Guarantees
*This is extremely important.*

**Modes**
* **Best Effort:** Process whatever is found.
* **At Least Once:** No files missed. Duplicates possible.
* **Exactly Once (Logical):** No file missed. No duplicate processing. Marker repository required.

**Gap Detection**
Identify missing files.
* Example Expected: `CDR_2026061301`, `CDR_2026061302`, `CDR_2026061303`
* Example Actual: `CDR_2026061301`, `CDR_2026061303`
* Raise: `missing sequence alert`

## 7. File Retrieval
The system shall retrieve discovered files.

**Retrieval Modes**
* **Direct Streaming:** Process without local copy. Suitable for S3, GCS, FTP.
* **Stage Then Process:** remote → local staging → processing.
* **Chunked Download:** Large files. Support resume.

**Compression Support**
Automatically handle: `.gz`, `.zip`, `.tar.gz`, `.bz2`. *(As built: [Part 3 — the Unpack stage](spec-acquisition-unpack.md).)*

## 8. Parallel Fetching
Improve acquisition throughput.

**Capabilities**
* **Multi-threaded Download:** Configurable (e.g., `max_parallel_fetch=20`).
* **Connector Parallelism:** Per source (e.g., FTP: 5, S3: 50, GCS: 30).
* **Multipart Download:** Support where applicable (e.g., S3 multipart, GCS parallel composite download).
* **Rate Limiting:** Prevent source overload.

## 9. Post Processing Actions
After successful processing.

**Supported Actions**
* **Retain:** Leave source unchanged.
* **Delete:** Remove source file. Only if supported.
* **Move:** Move to backup/archive (e.g., `archive/yyyy/MM/dd/`).
* **Rename:** e.g., `processed_<filename>`.
* **Tag:** Object storage metadata tagging.

**Unsupported Action Handling**
Example: Delete requested on read-only source.
Policy: `FAIL`, `WARN_AND_CONTINUE`, `IGNORE`.

## 10. Failure Handling
The acquisition layer shall tolerate transient failures.

* **Connectivity Failures:** Handle connection refused, timeout, authentication failure, DNS failure, server unavailable.
* **Retry Policies:** Configurable (e.g., `retry_count=5`, `backoff=EXPONENTIAL`, `initial_delay=30s`, `max_delay=15m`).
* **Resume Support:** Continue interrupted downloads.
* **Circuit Breaker:** Temporarily stop polling failing sources.
* **Dead Letter Handling:** Persist acquisition failures (e.g., `FAILED_FETCH`, `FAILED_DELETE`, `FAILED_MOVE`, `CORRUPT_DOWNLOAD`).

## 11. Integrity Verification
Ensure retrieved files are valid.

**Verification Methods**
* **Size Validation:** Source size equals local size.
* **Checksum Validation:** MD5, SHA-256, ETag, CRC32.
* **Optional Content Validation:** e.g., CSV header validation, ASN.1 decode probe, Parquet footer validation.

## 12. Observability
Operational visibility.

**Metrics**
`files_discovered`, `files_downloaded`, `files_processed`, `duplicates_skipped`, `downloads_failed`, `bytes_transferred`, `average_fetch_time`, `active_connections`.

**Audit Trail**
Capture lifecycle: `DISCOVERED`, `STABLE`, `FETCHED`, `VALIDATED`, `PROCESSED`, `ARCHIVED`, `FAILED`.

**Alerting**
Generate alerts for: repeated failures, missing expected files, prolonged source inactivity, excessive duplicates, connectivity degradation.

## 13. Configuration Example

*(The requirement's illustrative shape. The as-built block is `collector:`, never `source:` — see
[What acquisition is](#what-acquisition-is-and-where-it-stops).)*

```yaml
source:
  id: CDR_SFTP
  connector: SFTP
  path: /cdr/outbox
  include: "*.dat"
  stability_window: 300s
  duplicate_policy: CHECKSUM
  processing_guarantee: EXACTLY_ONCE
  fetch_mode: STAGE
  parallel_fetch: 10
  post_action: MOVE
  archive_path: archive/yyyy/MM/dd
  retry:
    count: 5
    strategy: EXPONENTIAL
  integrity:
    checksum: SHA256
  gap_detection:
    enabled: true
```

## Design Philosophy
Instead of building a "directory poller," define this component as a Data Acquisition Engine with five responsibilities:
1. **Discover** eligible files.
2. **Determine** readiness (stable and complete).
3. **Guarantee** collection semantics (exactly-once, gap detection).
4. **Retrieve** and validate data efficiently.
5. **Finalize** and audit every file lifecycle event.

This framing will help your agent generate requirements that scale from today's `inbox/<source>` folders to tomorrow's multi-protocol, high-volume enterprise ingestion platform without redesigning the core architecture.

---

# Part 2 — The acquisition engine as built

> Vocabulary: the two authored nouns are **Connection** (a named endpoint + credential definition) and
> **Collector** (a configured collection task bound to one Connection). ⛔ The acquisition entity is never a
> *Source* (flipped 2026-07-14, `GLOSSARY.md` §2/§3). <!-- vocab-allow: the doc states the ban it enforces -->
> ⚠ Do not restore a **`4.x`** attribution for phases A–F. That branch and its `v4.0.0`/`v4.0.0-RC1` tags were
> deleted 2026-08-17 (`docs/BRANCHING.md` §0-A); the phases shipped on `master`.

## What acquisition is, and where it stops

Acquisition is a **collection engine, not a directory poller**: *discover → determine readiness → guarantee
collection semantics → retrieve and validate → finalize and audit*. Its output is a byte-complete file
sitting under `dirs.poll` (the **inbox**), landed atomically. Everything after that — parse, expectations,
transform, sink — is the ingest lane's problem and does not special-case a remotely-collected file. This
seam is the whole design: `CollectorConnectors.localForConfig` exists precisely so the ingest walk uses a
**local** connector even for a pipeline whose `collector.connector` names a remote scheme, because by then
the bytes are already local.

The authored config block is **`collector:`**. ⛔ There is no `source:` block and no alias: the parser reads
`collector` only (`PipelineConfigParser.parseCollector`), zero committed `*_pipeline.toon` carries a
top-level `source:`, and since `2c310d1c` a `source:` key is refused at `/config/write` as a key nothing reads.
The retired `data-acquisition-framework.md` spelled it `source.*`; the reason it drifted is that
**`inspecto-acquire`'s own javadocs still say `source.*`** (`CollectorConnectors`, `RateLimiter`,
`CircuitBreaker`, `AcquisitionLedger`, `DuplicatePolicy`, `RetryPolicy` and others) — the prose inherited the
drift from the code comments, not the reverse. ⚠ `inspecto/examples/README.md` has the same stale prefix (the
`gap-detection`, `checksum-change` and `incremental-watermark` rows) while the example files it describes use
`collector:`.

⚠ The boundary that is easy to get wrong: **acquisition never widens to "read from N places at once."**
Multi-location ingest is composition of existing pieces — see
[multi-location ingest](architecture-elt-acquisition-parsing.md#multi-location-ingest--the-composition-pattern).

## The poll cycle and its two timers

`CollectorProcessor` (`platform/inspecto-engine/src/main/java/com/gamma/inspector/CollectorProcessor.java`) is the
entry point, in three public shapes: `run(cfg)` = `acquire` then `ingest` in one cycle — the
one-shot CLI / `reprocess` / manual path; `acquire(cfg)` = phases A–F, fetch-and-land only;
`ingest(cfg, onCommit)` = the inbox walk. `acquire` is a **no-op for a `local` collector**.

In the always-on service the two halves run on **separate timers with separate guards** (B3b): the
acquisition tick is `PipelineScheduler.dispatchAcquireCycle` (scheduled by `CollectorService`), cadenced by
`-Dacquire.pollSeconds` (defaults to the ingest poll interval) with an allowance of
`-Dacquire.maxConcurrent` (defaults to the ingest budget, both read in `CollectorService`), and each pipeline is
serialised by a dedicated `acquireGuard`. So a slow fetch neither blocks nor is blocked by ingest, and two acquisitions of
one pipeline never overlap.

⚠ **Gotcha — the two guards are deliberately not one.** A manual "run now" acquires inline under the
*ingest* `runGuard`, so a manual run **can** overlap one background acquire tick of the same pipeline. This
is benign and self-correcting: landing is atomic (Phase E) and duplicates are caught by markers/fingerprint
dedup. Do not "fix" it by merging the guards — that would serialise ingest behind fetch again.

**Back-pressure (B4) throttles the producer.** If a pipeline's inbox backlog (`CollectorProcessor.countPending`
— the exact landed count) has reached `-Dacquire.backpressure.highWater` (read in `CollectorService`),
`PipelineScheduler.selectDueForAcquire` skips that pipeline this tick and bumps
`inspecto_acquire_backpressure_skips_total`. The already-landed files wait in
the durable inbox — which *is* the spill-to-disk hand-off queue of the design's escalation ladder — until
ingest drains them below the mark. The mark is **0 = off by default**; a failed pending scan returns `-1`
and so fails open (never pauses). This is *negative* feedback on the producer, and is therefore the exact
opposite of `IntakeGovernor`/T15, which deliberately does **not** throttle on inbox lag because throttling
the ingest *consumer* on backlog would be positive feedback.

⚠ The gate bounds backlog **across** ticks, not within one: a single acquire cycle still fetches the whole
discovered listing, so one tick can overshoot the mark. A per-cycle intake cap is **live** under another
name — files via `-Dingest.maxFilesPerCycle` (default `IntakeGovernor.DEFAULT_MAX_FILES_PER_CYCLE` = 500
since 2026-10-06, `0` disables; `IntakeGovernor.capFor` read in `CollectorProcessor.collect`, per
Pipeline `processing.intake.max_files_per_cycle`) and bytes via `-Dingest.maxBytesPerCycle` (2026-09-16,
`20ff050d`) — and both run after listing, on local files. **The byte cap also bounds the fetch since
2026-11-17** (branch-aware residual (f), MoSCoW C8): the listing-with-sizes seam is `RemoteFile.size`
(`@PublicApi` since 4.0.0, `SIZE_UNKNOWN` = -1), which S3, GCS, Azure Blob, SFTP and FTP already fill from
their listings. `RemoteAcquisitionHandler.materializeRemote` calls `CollectorProcessor.capBeforeFetch`
after the pre-fetch dedup (so a re-listed duplicate never spends the budget) and before any `fetchTo`:
oldest-first by listing mtime, the same `-Dingest.maxBytesPerCycle` policy and the same starvation guard
(one file over the cap is fetched alone). A deferred file gets no fetch and no post-action, so it stays on
the source and is listed again next cycle. **Only when every candidate has a size**; one `SIZE_UNKNOWN`
(the `db` export and Kafka, which cannot know before the export or poll; the `dataset` copy, which
delegates to the local connector that never stats at discovery) and the whole listing is fetched as
before, capped after landing. The file-count cap is still not applied before the fetch: it is adaptive to
ingest overrun and on by default (500), so moving it would change every remote Collector's default.

## Phase A — discovery

`CollectorConnectors.forConfig(cfg)` resolves the connector: scheme `local` (and a blank/absent
`collector.connector`, which defaults to `local`) → the built-in `LocalFileSystemConnector`, constructed
from `dirs.poll`/`errors`/`quarantine`; any other scheme → a `ServiceLoader<CollectorConnectorFactory>`
lookup, matched case-insensitively on `scheme()`. An unknown scheme **fails fast** with a message naming
`local` as the only built-in (`CollectorConnectors.forConfig`) — not a silent fall-back to local.

`CollectorConnector.discover(ctx)` lists candidates and **never dedups** — dedup is an engine concern, so a
connector stays a protocol adapter. Discovery is shaped by four keys, all under `collector:`:

| Key | Default | Meaning |
|---|---|---|
| `include[]` | `[processing.file_pattern]` | glob list; a non-empty `include` **replaces** the pattern rather than adding to it (`PipelineConfigParser.parseCollector`) |
| `exclude[]` | `[]` | exclusion globs |
| `recursive_depth` | `-1` | unbounded |
| `discovery` | `poll` | `poll` \| `watch` |

`discovery: watch` (ACQ-6) puts a JDK `WatchService` on a local/mounted poll root, debounced ~1 s via
`-Dservice.watch.quiet.millis` (`CollectorWatcher.QUIET_MS`, default `1000`). 🔴 The interval poll loop stays
on as the backstop: **watch narrows latency, it never carries correctness.**

The push half of ACQ-6 is `POST /collectors/{id}/notify` (`AcquisitionRoutes`) — an S3 event
notification, upload script or upstream job asks for an immediate scan instead of waiting out the interval.
Gated on `canOperateRuns`; `?v1` answers `202 {runId, source, pipeline, status}` plus a `Location` to poll,
legacy answers a synchronous run result off the trigger pool; `404` for an unknown source id. The request
**body is ignored** — a notify is a hint that something arrived and the triggered cycle re-discovers
authoritatively, so a spurious notify is harmless. Audited as **`collector.notified`**
(`AuditTrail` maps the verb `notify` → `notified`).

## Phase B — the readiness / stability gate

`StabilityGate` (`platform/inspecto-acquire/src/main/java/com/gamma/acquire/StabilityGate.java`) holds back
half-written files — the requirement's stated "biggest production problem". Order of decision:

1. Ask `connector.readiness(file)` → `READY` / `NOT_READY` / `UNKNOWN`
   (`CollectorConnector.readiness`). A connector that knows natively short-circuits: an object store's listed
   object is atomic ⇒ always `READY`; a local connector with a `ready_marker` answers `READY` iff the
   sibling marker exists.
2. Only on `UNKNOWN` does the gate apply size/mtime **quiescence**: released once the file has been
   unmodified for at least `window` **and** observed at the same size on at least `size_checks`
   consecutive cycles.

⚠ The stability key is **`size_checks`** (`PipelineConfigParser.parseCollector`; `sizeChecks()` is merely the
record accessor) — the retired `framework.md` gave `stability.sizeChecks`, which was wrong.

| `collector.stability` key | Default when the block is present |
|---|---|
| `window` | `30s` |
| `size_checks` | `2` (floored at 1) |
| `ready_marker` | *none* — template with `{name}`; a template without `{name}` is treated as a suffix (`ReadyMarker.java`) |
| `exclude_temp_files` | `true` |
| `exclude_temp_patterns[]` | `*.tmp`, `*.partial`, `*.filepart`, `.~lock.*` (`PipelineConfig`) |

An **absent** `stability:` block is `Stability.DISABLED` — the legacy "a matched file is a candidate at
once" behaviour, nothing stat'd.

Two properties worth not breaking. **Idempotence under repeated evaluation:** release is gated on
wall-clock quiescence (`now - mtime >= window`), so it crosses the threshold only as real time advances —
never merely because `filter` was called again. That is what lets the read-only `countPending` scan and the
real poll cycle evaluate the same files without one consuming the other's progress. **I/O discipline:** the
gate stats a file only when gating is enabled *and* the connector said `UNKNOWN`; when the remote LIST
already carried size + mtime those are reused and no extra round-trip is made. One `shared()` gate **per
space** (keyed on `EventLog.currentSpaceId()`), so two spaces' sightings never collide.

## Phase C — duplicate prevention, change policy, watermarks

The fingerprint repository is the `AcquisitionLedger` SPI
(`platform/inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedger.java`), keyed `(sourceId,
relativePath)`. Unlike the append-only OI stores it **upserts** — a file's latest fingerprint replaces the
prior one for its key. Four operations: `find`, `record`, `highWatermark(sourceId)` (file-level, Phase C4),
`dbWatermark`/advance (row-level, DB export). `LedgerEntry` carries `sourceId, relativePath, name, size,
checksum, etag, version, lastModified, processedAt, status`.

Implementations: `DbAcquisitionLedger` (**the default on every edition**, operator 2026-11-20: durable JDBC over its
own DuckDB file, single-writer, or a Postgres URL) and `InMemoryAcquisitionLedger` (the `memory` opt-in, lost on
restart; the test reactor pins it), selected once from `-Dacquire.ledger.backend` (`db` default | `memory`,
`AcquisitionLedgers.DEFAULT_BACKEND`). Tables: `inspecto_acquisition_ledger` and `inspecto_acquisition_db_watermark`
(`DbAcquisitionLedger.TABLE`, `.WM_TABLE`); an index on `(source_id, checksum)` serves `findByChecksum`.
`entries(sourceId)` and `forget(sourceId, relativePath)` let an Add data import forget the fingerprints of rows it set
aside (B3 part 2, [arrivals](../la-core/architecture-data-onboarding-arrivals-storage.md)). A reprocess
(`ReprocessCommand`) forgets its members' fingerprints beside deleting their markers, keyed as the commit recorded them
(`ConsignmentIngestor.ledgerKey`): in a content-based mode the restored files were otherwise skipped as duplicates and
the rows their deleted outputs held never read again. `AcquisitionLedgers` is the per-space `shared()` accessor + lifecycle
manager — one of the five MDC-routed singletons. Dedup keys are SHA-256 hashed in the ledger (decision
2026-09-01, [plan](plan-elt.md)).

🔴 **`DuplicatePolicy` is TWO orthogonal enums, not one list.** The retired `framework.md` stated the modes as
"PATH / METADATA / CHECKSUM / SKIP / REPROCESS / VERSION / FAIL", which conflates the detection axis with
the change axis and imports four tokens from the requirement's §4 wish-list that the code never
adopted. The code (`DuplicatePolicy.java`) has:

- **`Mode`** — *how a re-seen path is judged*: `PATH` (default), `METADATA` (name+size+mtime, no read),
  `CHECKSUM` (content hash, read at processing time), `ETAG` (ACQ-7 — the connector-supplied etag/version
  from the *listing*, so it costs no fetch and no read; falls back to version, then to size+mtime, so a
  connector without `ETAG`/`VERSIONING` capability degrades to METADATA), `CONTENT` (`BASIC-ELT-PROFILE-1` B3 — the
  checksum against every file of the Collector, under any name: `AcquisitionLedger.findByChecksum`, so the same bytes
  re-sent under a new name, or two copies in one cycle, are a `DUPLICATE`; a known path with new bytes is judged as
  `CHECKSUM`; like `CHECKSUM` it never pre-skips a remote file and degrades to METADATA on `countPending`).
- **`OnChange`** — *what happens when a known path's content changed*: `IGNORE` (treat as duplicate, skip),
  `REPROCESS` (default), `ALERT` (process + emit `FILE_CHANGED`), `ARCHIVE_OLD_VERSION` (archive the prior
  version first). `reprocessOnChange` is `policy != IGNORE`; `alertsOnChange` is `policy == ALERT`.
- **`Decision`** — the verdict per candidate: `NEW` / `DUPLICATE` / `CHANGED`.

`decide(...)` is pure and side-effect-free (the engine performs the skip/process and the ledger write), so
it is unit-tested directly. In `ETAG` mode the strongest dimension both sides carry wins: an etag that
differs is `CHANGED` even when size+mtime happen to match.

Config: `collector.duplicate: { mode: path, algorithm: SHA256, on_change: reprocess }` — those are the
defaults when the block is present (`PipelineConfigParser.parseCollector`). `algorithm` feeds `Checksums`,
which is JDK-only (`MessageDigest`/`CRC32`), streams in 64 KB chunks, returns lowercase hex, and accepts
`MD5`, `SHA1`, `SHA256` (default), `SHA512`, `CRC32` with or without the dash. A feed that must let a
corrected same-name file land uses `mode: checksum` + `on_change: reprocess` (worked example: the daily feed in
[the ETL user guide](user-guide-elt-enrich-load-test.md#113-daily-feeds--the-template-the-export-contract-and-delivery-checks)).

**Collection guarantee.** `collector.guarantee` → `BEST_EFFORT` (default) | `AT_LEAST_ONCE` |
`EXACTLY_ONCE` (`PipelineConfig.Guarantee`; both dash and underscore spellings parse). `requiresLedger()`
is true for anything but best-effort. ⚠ **The parser refuses to over-promise silently:** a
ledger-requiring guarantee combined with `duplicate.mode: path` (marker-only, which never populates the
ledger) logs a `[CONFIG]` warning saying it will behave as best-effort + commit-log replay and naming the
three modes that would enforce it (`PipelineConfigParser.parseCollector`).

**File-level watermark (C4).** `collector.incremental.watermark: last_modified` makes each scan skip files
modified strictly before the source's high-watermark — the greatest `lastModified` the ledger holds for that
source. It is **derived from the ledger, with no separate watermark column**, hence only meaningful in a
content-based mode; the parser warns on `incremental` + `mode: path` for exactly that reason
(`PipelineConfigParser.parseCollector`). Metric: `inspecto_watermark_skipped_total`.

**Row-level DB watermark.** Distinct machinery: an opaque max of a monotonic result column, persisted per
**connection-profile id**, bound as a JDBC parameter into `… WHERE col > :watermark` and advanced **only
after the batch commits** ⇒ at-least-once on crash. Knobs live on the `db` profile's `options`:
`watermark_column` (enables the mode), `watermark_type` (`string` default | `long` | `timestamp`),
`watermark_initial`. 🔴 Gap-free **only over an append-only / monotonic column** — the compare is strictly
`>`, so a late back-dated row below the frontier is missed. Prefer an ingestion timestamp or sequence over
an event-time column.

**Consignment cutting** is a Collector concern because the planner runs in the poll cycle:
`collector.consignment: { max_files, max_bytes, order }`, canonical since CONSIGNMENT-HOME-1 (2026-09-02).
`order` is `mtime` (arrival — the default) or `name`, and **garbage refuses at parse time** rather than being
silently ignored (`PipelineConfigParser`, the consignment-caps block). The pre-move `processing.batch:`
spelling is dual-read, canonical-first, and nothing writes it any more (same block). How the planner then
cuts and runs Consignments: [engine — Consignments](spec-ingest.md) and
[engine — ingest](spec-ingest.md).

## Phase D — gap detection

`GapDetector` (`platform/inspecto-acquire/src/main/java/com/gamma/acquire/GapDetector.java`) is pure decision logic:
given a sequence template and the names one discovery cycle observed, it computes which expected keys are
missing. Template grammar = a literal prefix/suffix around a single `{…}` token holding a
`DateTimeFormatter` pattern, e.g. `CDR_{yyyyMMddHH}` ⇒ an hourly series. The **finest** field present sets
the step (`s`→seconds, `m`→minutes, `H`/`h`→hours, `d`→days, `M`→months, `y`/`u`→years); coarser fields
default (month/day→1, hour/min/sec→0, year→2000). Non-matching names are ignored. The series is enumerated
**between the lowest and highest observed key**.

`GapTracker` holds per-process, per-`sourceId` memory of which gaps have already been reported, so a
*persistent* hole fires `SEQUENCE_GAP` **once** rather than every cycle; a gap that reappears after being
filled fires again, because it is genuinely a new hole. Same `shared()` idiom as `StabilityGate` /
`AcquisitionLedgers`.

🔴 `FileSequenceGaps` is a **different instrument, not a duplicate** — windowed two-token analysis
("how many files are missing for this pipeline over this period?", completeness-KPI K2). Two reasons
`GapDetector` cannot answer it: names shaped `CDR_{yyyyMMddHH}_{seq}_*` carry a second numeric token its
grammar cannot express, and bounding the series by what was *observed* hides the largest holes — if the last
three hours produced nothing, the observed maximum simply moves earlier and the detector reports no gap.

⛔ **Two of `FileSequenceGaps`' limits are STRUCTURAL, pinned by tests, and must not be "fixed" into
guarantees.** They are properties of the naming, not of the implementation:

- **Only interior holes are countable.** `1,2,4` is short of 3; `1,2,3` is **not** complete, because the
  highest sequence received is not knowably the highest sent. **A truncated tail is undetectable from
  file names alone.**
- **An empty bucket yields no file count.** A silent hour's expected file count is unknowable, so empty
  buckets are counted as *buckets*, apart from the missing-file total. Estimating them is K3's rolling
  baseline's job — mixing an estimate into an exact count would make the exact half untrustworthy.

Config: `collector.gap_detection: { enabled: true, sequence: "…" }` — `enabled` defaults to `true` when the
block is present, and an absent block is `DISABLED`.
Two optional keys beside it (operator, 2026-10-06) — `file_template` (one date token plus an OPTIONAL `{seq}`,
e.g. `CDR_{yyyyMMddHH}_{seq}_*`, or date-only `XDR_{yyyyMMdd}.csv` = one expected file per date bucket) and
`seq_scope` (`PER_BUCKET` | `CONTINUOUS`, required when the template holds `{seq}`, refused when it does not;
operator, 2026-10-06) — are read by the `kpi.completeness` job to count missing files and by the delivery check
(per-day manifest + windowed `SEQUENCE_GAP`, [the ETL user guide](user-guide-elt-enrich-load-test.md#113-daily-feeds--the-template-the-export-contract-and-delivery-checks)).
`sequence` and `GapDetector` are unchanged. See [observability §3.9](../observability/plan-observability.md).

## Phase E — retrieval: plan once, stage, then land

**The retrieval plan writes bytes at most once.** `RetrievalPlanner` chooses per file
(`RetrievalPlan.Mode`): a configured backup/archive ⇒ `FETCH_TO_BACKUP` (write the bytes *directly* into
the backup destination and read from there — never temp, read, then move, which copies every byte twice);
no backup and the connector can `STREAM` (and nothing downstream needs a seekable local copy) ⇒ `STREAM`
(zero local bytes); otherwise ⇒ `STAGE_TEMP`. `destination` is `null` only for `STREAM`.

**Stage, then land (B3).** A connector writes to the destination `RemoteAcquisitionHandler` hands it, and
that destination is **never the inbox**: fetches go to `collector.fetch.staging_dir` (default
`<dirs.temp>/acquire`) and only a complete, integrity-verified file is **atomically renamed** into `dirs.poll`.
A partial download is never ingestible, resume still works (the staging path is deterministic), a corrupt
download is dead-lettered straight from staging, `staging_dir` must be outside `dirs.poll` (refused at fetch
time) and on the same filesystem, and the order is land-then-ack. The full account, with the three properties
and `RemoteAcquisitionStagingTest`, is owned by [connectors](../connectors/spec-connectors.md).

**The slice frontier moves with the land (KAFKA-OFFSET-REKEY-1, fixed 2026-09-24).** `kafka` and `db`
stash their reached offset / row watermark in `AcquisitionLedgers` keyed by the file they wrote — the
**staging** path — while `ConsignmentIngestor` takes it at commit by the **inbox** path. Until this fix the
two never met: the frontier was never recorded, so every later cycle re-read from the start (DB export 3 → 6
→ 10 rows over three cycles; Kafka 7 rows for 4 offsets). The one seam is `RemoteAcquisitionHandler.land`:
it takes the stash from the staging path, persists it (below), moves the slice, and re-stashes it under the
inbox path; a failed move drops the durable record, and a slice rejected by `fetchAndVerify` (quarantine /
skip) calls `discardDbWatermark`, so no stale frontier lingers (the next cycle re-fetches and re-stashes).

**The frontier survives a restart (STREAM-CONSUMER-1, operator decision Q3 2026-09-24).** The stash is an
in-process map, so a restart between land and commit used to lose it: the slice committed without advancing
the frontier and the next cycle re-read it. Now `SliceFrontiers` (`inspecto-engine`) writes one JSON record
`{"key","value"}` per landed slice under `<staging>/.frontier/`, mirrored by poll-relative path (the
`CommitRetry` sidecar idiom). Three orderings carry it:

- **Record before land.** It is written (temp file + atomic rename) *before* the slice's own atomic move into
  the inbox, so a slice is never in the inbox without its frontier. If the record cannot be written the land
  fails and the bytes stay staged — a slice without a durable frontier is the defect itself.
- **Delete after the ledger.** `ConsignmentIngestor` reads the record when the in-memory stash is empty, and
  deletes it only after `recordDbWatermark` — a crash in between re-records the same value.
- **Restore before discovery.** `CollectorProcessor.acquire` calls `SliceFrontiers.restore` before
  `discover`: every record whose slice is still in the inbox is re-stashed, so the in-flight fence (below)
  holds across a restart; a record whose slice is gone, or whose value the ledger already holds, is removed.

⚠ **Durable exactly as far as the ledger is.** Under the `memory` opt-in (or a ledger that fell back to memory)
the *committed* frontier is itself lost on restart, and a Kafka Collector re-reads from `options.start` whatever
this record says. Restart-safe delivery needs the default `-Dacquire.ledger.backend=db`. ⚠ `.frontier` is reserved inside the
staging tree: a listing path that would stage into it is skipped as a fetch failure. ⛔ **Why not the slice
name.** The design proposed renaming a Kafka slice to its reached range and deriving the frontier from the
name. Refused on the code: the commit is source-agnostic and the ledger key (profile id, topic, partition) is
not in the name, and a DB-export watermark (a timestamp with `:`) cannot live in a portable file name. One
record serves both connectors.

Pinned by `RemoteSliceFrontierRekeyTest` (engine), `RemoteSliceFrontierCommitTest` (inspecto: restart and
fence against the scheduler's split timers) and `DbExportWatermarkCycleTest` (connectors: restart and fence
against a real DuckDB export).

**Integrity (requirement §11).** `IntegrityChecker` applies two independent, cheap-to-skip checks to the staged copy:
*size* (staged bytes == the listing's `RemoteFile.size()`, skipped on `SIZE_UNKNOWN`, since some FTP servers
omit it) and *checksum* (only when the listing exposes a hash via `RemoteFile.etag()`, hashed with the
matching `Checksums` algorithm). Skipped when no etag exists — plain SFTP/FTP expose none — so it never
forces a wasted read. Pure verification: no events, no metrics; the caller decides.

**Fetch tuning.** `collector.fetch: { mode: STAGE, staging_dir: …, parallel_fetch: 1, rate_limit: … }`.
`parallel_fetch` is floored at 1. `rate_limit` parses to **bytes per second** via `parseRate`
(`PipelineConfigParser.parseRate`): a size with an optional `/s`, `ps` or `/sec` suffix — `50MBps`,
`50MB/s`, `1GBps`, `512KBps`, or a bare number; KB/MB/GB are **binary (1024-based)**; null/blank ⇒ 0 =
unlimited. `RateLimiter` is a token bucket holding at most one second's burst, shared across all parallel
fetch workers so the *whole source* is bounded; an `acquire` larger than the burst waits proportionally
rather than deadlocking; clock and sleep are injectable.
