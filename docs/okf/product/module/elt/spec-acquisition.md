---
type: Concept
title: Acquisition — specification
description: The original requirement for remote file collection (14 numbered areas), the as-built acquisition engine (the poll cycle, phases A–F, slice frontiers, the stream lane, the control surface, observability and defaults), and the Unpack stage that turns Archives and compressed streams into plain files before Consignments are planned.
resource: platform/inspecto-acquire/src/main/java/com/gamma/acquire, platform/inspecto-etl/src/main/java/com/gamma/etl/unpack
tags: [acquisition, requirements, discovery, stability, dedup, watermark, guarantees, unpack, decompression, collector, archive, consignment]
timestamp: 2026-11-18T00:00:00Z
---

# Acquisition — specification

**What this covers.** Everything that happens before a byte becomes a record: how Inspecto reaches a remote
system, decides which files are new and complete, fetches each one exactly once and lands it in a Pipeline's
inbox, and how a compressed or archived file is unpacked into plain files on the way. Part 1 is the original
requirement in its own words; Part 2 is the engine as built, with its traps; Part 3 is the Unpack stage.
What is still open, what was decided and what was refused is in [the plan](plan.md); the screens are in
[the user guide](user-guide.md).

**Contents**

- [Part 1 — The original requirement](#part-1--the-original-requirement)
  - [1. Collector (file) connectivity](#1-collector-file-connectivity) · [2. File discovery](#2-file-discovery) ·
    [3. File stability detection](#3-file-stability-detection) · [4. Duplicate prevention](#4-duplicate-prevention) ·
    [5. File change detection](#5-file-change-detection) · [6. Collection guarantees](#6-collection-guarantees) ·
    [7. File retrieval](#7-file-retrieval) · [8. Parallel fetching](#8-parallel-fetching) ·
    [9. Post-processing actions](#9-post-processing-actions) · [10. Failure handling](#10-failure-handling) ·
    [11. Integrity verification](#11-integrity-verification) · [12. Observability](#12-observability) ·
    [13. Configuration example](#13-configuration-example) · [Design philosophy](#design-philosophy)
- [Part 2 — The acquisition engine as built](#part-2--the-acquisition-engine-as-built)
  - [What acquisition is, and where it stops](#what-acquisition-is-and-where-it-stops)
  - [The poll cycle and its two timers](#the-poll-cycle-and-its-two-timers)
  - [Phase A — discovery](#phase-a--discovery)
  - [Phase B — the readiness / stability gate](#phase-b--the-readiness--stability-gate)
  - [Phase C — duplicate prevention, change policy, watermarks](#phase-c--duplicate-prevention-change-policy-watermarks)
  - [Phase D — gap detection](#phase-d--gap-detection)
  - [Phase E — retrieval: plan once, stage, then land](#phase-e--retrieval-plan-once-stage-then-land)
  - [Phase F — retry, circuit breaker, post-action](#phase-f--retry-circuit-breaker-post-action)
  - [The connector SPI and the resolvable schemes](#the-connector-spi-and-the-resolvable-schemes)
  - [Connection profiles, secrets, host keys, tunnels and proxies](#connection-profiles-secrets-host-keys-tunnels-and-proxies)
  - [The control surface](#the-control-surface)
  - [Observability](#observability)
  - [Network shares — the declined design, recorded once](#network-shares--the-declined-design-recorded-once)
  - [Defaults worth not "fixing"](#defaults-worth-not-fixing)
- [Part 3 — The Unpack stage](#part-3--the-unpack-stage)
- [References](#references)

---

# Part 1 — The original requirement

> **The requirement of record, kept for the wording of its 14 numbered areas.** *(Moved from the retired
> root-level `data_acquisition_framework.md` (docs consolidation, 2026-07-16); merged here 2026-11-18.)*
> **The framework is built — Phases A–F have all shipped on `master`.** What is built is Part 2; what is left
> and what was refused is [the plan](plan.md) (the in-process NFS/SMB client is a **declined** design, not a
> pending one — mount the share and use `local`). The original phase roadmap is archived at
> [`2026-06-14-data-acquisition-framework-roadmap.md`](../../../../archived-documents/superpowers/specs/2026-06-14-data-acquisition-framework-roadmap.md),
> and the operator-facing config/runbook are
> [`configuration.md`](../config/admin-guide-configuration-reference.md#data-acquisition--the-collector-block) and
> [`../connectors/admin-guide-connectors-runbook.md`](../connectors/admin-guide-connectors-runbook.md#remote-connectors-sftp--ftp--ftps).

**GOAL:**
* **The system guarantees that every eligible data source file is collected exactly once (or according to policy), safely, efficiently, and recoverable regardless of where the file resides**
* **There would be other source connectors (`Kafka` streaming + `DataBase Tables` export shipped; more to come) to create a Data Lake House System**

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
  * SMB/CIFS — via an **OS-mounted share** (see [Network shares](#network-shares--the-declined-design-recorded-once))
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
Automatically handle: `.gz`, `.zip`, `.tar.gz`, `.bz2`. *(As built: [Part 3 — the Unpack stage](#part-3--the-unpack-stage).)*

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
[multi-location ingest](architecture.md#multi-location-ingest--the-composition-pattern).

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

Implementations: `InMemoryAcquisitionLedger` (**default — lost on restart**) and `DbAcquisitionLedger`
(durable JDBC over its own DuckDB file, single-writer, or a Postgres URL), selected once from
`-Dacquire.ledger.backend` (`memory` default | `db`, `AcquisitionLedgers`). Tables:
`inspecto_acquisition_ledger` and `inspecto_acquisition_db_watermark`
(`DbAcquisitionLedger.TABLE`, `.WM_TABLE`). `AcquisitionLedgers` is the per-space `shared()` accessor + lifecycle
manager — one of the five MDC-routed singletons. Dedup keys are SHA-256 hashed in the ledger (decision
2026-09-01, [plan](plan.md)).

🔴 **`DuplicatePolicy` is TWO orthogonal enums, not one list.** The retired `framework.md` stated the modes as
"PATH / METADATA / CHECKSUM / SKIP / REPROCESS / VERSION / FAIL", which conflates the detection axis with
the change axis and imports four tokens from the requirement's §4 wish-list that the code never
adopted. The code (`DuplicatePolicy.java`) has:

- **`Mode`** — *how a re-seen path is judged*: `PATH` (default), `METADATA` (name+size+mtime, no read),
  `CHECKSUM` (content hash, read at processing time), `ETAG` (ACQ-7 — the connector-supplied etag/version
  from the *listing*, so it costs no fetch and no read; falls back to version, then to size+mtime, so a
  connector without `ETAG`/`VERSIONING` capability degrades to METADATA).
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
[the ETL user guide](user-guide.md#113-daily-feeds--the-template-the-export-contract-and-delivery-checks)).

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
(per-day manifest + windowed `SEQUENCE_GAP`, [the ETL user guide](user-guide.md#113-daily-feeds--the-template-the-export-contract-and-delivery-checks)).
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

⚠ **Durable exactly as far as the ledger is.** Under the default in-memory ledger (Phase C) the *committed*
frontier is itself lost on restart, and a Kafka Collector re-reads from `options.start` whatever this record
says. Restart-safe delivery needs `-Dacquire.ledger.backend=db`. ⚠ `.frontier` is reserved inside the
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

## Phase F — retry, circuit breaker, post-action

`RetryPolicy` (`platform/inspecto-acquire/src/main/java/com/gamma/acquire/retry/RetryPolicy.java`) wraps the
connectivity-sensitive connector calls — `discover` and per-file `fetchTo` — so a flaky endpoint gets
bounded retries instead of failing the whole cycle on the first hiccup. The base delay before the *n*th
retry grows from `initial_delay` toward `max_delay` on the configured curve, is clamped to `max_delay`, and
is then **full-jittered** — the actual sleep is uniform in `[0, base]` — to avoid a thundering herd of
pollers retrying in lockstep. Sleep and jitter source are injectable; `RetryPolicy.NONE` performs exactly
one attempt. Config `collector.retry: { count: 0, backoff: EXPONENTIAL, initial_delay: 1s, max_delay: 60s }`
(EXPONENTIAL/FIXED); an absent block is a single attempt.

`CircuitBreaker` (`platform/inspecto-acquire/src/main/java/com/gamma/acquire/CircuitBreaker.java`) is **per-source**,
keyed by `collector.id` on a process-wide `shared()` instance. On repeated connectivity failure it trips
`OPEN` and the engine *skips* that source for a cooldown rather than hammering a dead endpoint every cycle;
after the cooldown a single `HALF_OPEN` trial runs — success closes it, another failure re-opens it. The
clock is injectable. Thresholds are **passed per call** rather than stored, so one shared instance serves
every pipeline with no per-source coupling. Config `collector.circuit_breaker: { failure_threshold: 5,
cooldown: 5m }`; absent ⇒ never trips.

`PostAction` decides what happens to the **source-side** file after successful processing, validated against
the connector's declared `Capability` set before being applied. `Kind`: `RETAIN` (default) · `DELETE` ·
`MOVE` · `RENAME` · `TAG`. `resolveTemplate` expands `archive_path` date tokens **longest-first** so `yyyy`
is not eaten by `yy` (`yyyy yy MM dd HH mm ss`). Config `collector.post_action: { on_success: RETAIN,
archive_path: …, tags: {…}, on_unsupported: WARN_AND_CONTINUE }` — `on_unsupported` is the requirement §9 policy for
"delete requested on a read-only source"; a post-action that fails at runtime bumps
`inspecto_post_actions_failed_total` and **the file is still ingested**.
⚠ Both `RemoteAcquisitionHandler` author-facing messages name **`collector.post_action.on_success`**
(corrected 2026-09-13 — they had named a key that does not exist).

## The connector SPI and the resolvable schemes

The SPI — `CollectorConnector` (`discover`, `readiness`, `open`, `fetchTo`, `post`, and the `Capability`
enum `STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG, VERSIONING`),
`CollectorConnectorFactory` (`scheme()` + `create(cfg, profile)` + the optional `workbench(profile)` hook,
registered via `META-INF/services`) and `ConnectionWorkbench` — lives in the core (`inspecto-acquire`, package
`com.gamma.acquire`) and is described with the per-scheme libraries in [connectors](../connectors/spec-connectors.md).

⚠ **"Eight connectors" is the count of the optional module, not of the registry.** Verified from `META-INF/services/com.gamma.acquire.CollectorConnectorFactory`: **seven** factories ship in `inspecto-connectors` (`sftp`, `ftp`, `ftps`, `db`, `s3`, `azure`, `gcs`), **one** (`kafka`) in the premium module `inspecto-connectors-kafka` (MODULE-REORG-1 P7, 2026-10-07; Standard and above — Basic has no Kafka), **one** more in `inspecto-engine` (`DatasetCollectorConnectorFactory` → `connector: dataset`, UI-S7), and `local` is the built-in default that needs no jar. So ten values resolve, and a bundle without the sidecar resolves only `local` and `dataset`. *(Two more entries exist under `inspecto/src/test/resources` — `FakeRemoteConnectorFactory` and `FakeOffsetTailConnectorFactory` — and are test-only; do not count them.)*

⚠ **`https` is a Connection scheme, not a Collector scheme** (2026-09-23). No `CollectorConnectorFactory` serves it: an `https` Connection is the target of the outbound **Webhook sink** (`sink.webhook`, [step-catalog](spec-step-catalog.md#webhook--sinkwebhook--the-outbound-webhook)), onboarded under the same `canOnboardConnections` grant as every other Connection. That grant is what makes a Connection the egress gate. `host`/`port`/`base_path` build the URL, `password` is the bearer-token reference, and the option `timeout_seconds` sets the request timeout. The sink refuses a tunnel or proxy. A Collector bound to an `https` Connection fails as an unknown scheme.

🔴 **For 85 days the remote connectors were build-available and deploy-absent.** `inspecto-connectors` was a
reactor module no bundle shipped, so `connector: s3` and its siblings could be authored, compiled and tested and
then did nothing in a deployment. Fixed 2026-09-07 (CONNECTORS-BUNDLE-1): it rides every bundle as the shaded
`inspecto-connectors.jar` sidecar. *(Since MODULE-REORG-1 P7, 2026-10-07, `kafka` rides its own premium
`inspecto-connectors-kafka` jar, Standard and above.)*

**The `dataset` scheme.** `inspecto-engine` registers `com.gamma.inspector.DatasetCollectorConnectorFactory`,
`scheme() == "dataset"`, which consumes another store's parquet snapshots. It is parsed with two fail-closed
refusals: `connector: dataset` without `collector.dataset` is refused, and `collector.dataset` without
`connector: dataset` is refused rather than silently ignored (`PipelineConfigParser.parseCollector`); a
`datasets/<id>` ref is normalised to the bare id.

🔴 **Its `post` action is FORCED to `RETAIN`, whatever the config says** — `post()` passes
`PostAction.RETAIN` to the delegate unconditionally
(`DatasetCollectorConnectorFactory`, whose own comment reads *"Retain is the only honest
post-action here, whatever the config says"*). ⛔ **A consumer can never delete, move or rename a
producer's snapshots.** A safety property that overrides authored config must be documented, or an operator
authoring `post: delete` will believe it took effect.

**Why this needs no watermark, and why an earlier watermark-runner design was superseded.** Snapshots are
**timestamp-named**, and the poll inbox is the pipeline's own `dirs.poll`, so the **existing marker dedup
already gives correct re-ingest-on-refresh semantics** — parquet was only ever missing an *ingest* lane, not
a new consumption mechanism, and no parser fence was needed. ⛔ Do not build a watermark here on the
assumption that one is missing.

⚠ **There is no "split acquire/ingest timers" scheduler for mid-graph Steps, and a docs simplification once
implied one.** The closest primitives are the collector poll loop, the stability gate and the high-watermark
filter — all of which gate **discovery**, not mid-graph Steps. The durable inbox is `dirs.poll` itself, and a
drain is simply the next poll.

**Object storage is deliberately SDK-free (ACQ-4).** The auth, profile mapping, capabilities and pagination of
`s3`, `gcs` and `azure` are in [connectors](../connectors/spec-connectors.md). One detail is recorded only here:
Azure lists blobs with `NextMarker` pagination, Range-resume fetch, and MOVE/RENAME = Copy Blob + Delete
**guarded on `x-ms-copy-status: success`** so a pending copy never deletes the source.

**`db` and `kafka` are the "virtual slice file" idiom.** `db` runs a date-templated JDBC query and
materialises the result as CSV; `kafka` drains a partition's unconsumed backlog per cycle into
`<topic>-p<partition>-<from>-<to>.<ext>`, which flows through the normal batch path — **no core-engine
change**. Kafka offsets are **not** a broker consumer group: the connector `assign()`+`seek()`s and the
consumed frontier rides the ledger watermark, persisted only after the batch commits (at-least-once; a crash
mid-ingest re-drains the slice rather than skipping it). Broker consumer groups stay out by operator decision
(STREAM-CONSUMER-1 Q4, 2026-09-24): one offset store, fewer ACLs, no broker-visible lag. The loop is the
Collector scan, plus — since ASSURE-PUSH-INGEST-1 (2026-09-29) — the continuous lane below for a Pipeline that
opts in with `trigger: {type: stream}`. The Kafka module itself: [connectors-kafka](../connectors-kafka/spec.md).

**One uncommitted slice per partition — the in-flight fence (STREAM-CONSUMER-1, 2026-09-24).** With split
acquire and ingest timers, a second acquisition cycle before the commit read the same stored frontier and
minted an overlapping slice under a new name, and the overlap ingested twice. Now `KafkaConnector.discover`
skips a partition, and an incremental `db` export (`watermark_column` set) skips its export, while
`AcquisitionLedgers.hasPendingDbWatermark(key)` is true: a stash for that key exists, its slice file still
exists, and its value is not already the committed one. The last two conditions make the fence release
itself. A slice quarantined out of the inbox, or a stash that outlived its commit, cannot hold a partition
forever. After a restart, `SliceFrontiers.restore` (Phase E) re-arms it. ⚠ **A slice that never ingests holds its
partition.** For example, a slice whose name the pipeline's `file_pattern` rejects stays in the inbox, so
nothing further drains from that partition. Before the fence, such a Collector re-drained a growing
overlapping slice every cycle instead. **Lag:** `inspecto_stream_lag_records{connection,topic,partition}` =
end offset − *committed* frontier, refreshed on every discovery (a landed-but-uncommitted slice still counts).
Also exported: `inspecto_stream_slices_drained_total{connection,topic}` and
`inspecto_slice_frontiers_committed_total{pipeline}` (Kafka and DB export), all three on `GET
/metrics/acquisition`. That JSON *is* the Collector's stream status surface: no field was added to `GET
/collectors`.

**The delivery grain is the slice, not the row.** Slice-name identity plus the fence give slice-level
at-least-once: after a crash at most one slice per partition is re-delivered. Row-level exactly-once is out
of scope by design. The default `options.payload: envelope` already carries `topic`, `partition` and
`offset` on every record, so a Dataset that needs row-level dedup keys on those downstream.

**Why the loop is the Collector scan (STREAM-CONSUMER-1 design pass, 2026-09-24).** Three homes were
weighed. (A) the scheduler's acquisition tick, hardened — **built**; its latency floor is the acquisition
cadence, or a `POST /collectors/{id}/notify` wake-up. (B) a continuous `trigger: stream` lane in
`PipelineScheduler` — **deferred** at the time, then built (below). (C) a long-running Job — **refused**
([plan](plan.md)). The at-least-once fixes (Phase E and the fence) were needed under every
option, so they landed inside the row rather than as a separate P1 (Q1).

**The continuous lane — option B, built (ASSURE-PUSH-INGEST-1, 2026-09-29).** D-P7 named the target the
deferral waited for (p95 ≤ 30 s event → Incident), so B shipped as designed: *A plus a different trigger*.
`trigger: {type: stream, records: N, max_wait: T}` (defaults 1000 records, 5 s) gives an active remote
Pipeline one `StreamLane` (`inspecto-acquire`): a virtual thread that holds the Collector's connector OPEN (for
Kafka, one consumer for the lane's life) and every 200 ms asks `CollectorConnector.pendingRecords()`. It drains
when the backlog reaches N, or when a non-empty backlog has waited T. The drain is option A's path unchanged —
`CollectorProcessor.acquire` under the same `acquireGuard` claim the tick takes, then the ordinary
`runPipeline` — so the fence, the durable frontier and slice-level at-least-once all carry over, and a crash
mid-slice re-delivers that one slice. Kafka's probe skips fenced partitions, so an uncommitted slice cannot make
the lane spin. A connector that cannot count (`-1`, every non-Kafka one) drains on T alone.

- An `AcquisitionException` from the probe is an ordinary source failure (reopen next probe); anything else
  thrown by the probe or the drain marks the lane DEAD — ERROR log, `inspecto_stream_lane_deaths_total{pipeline}`,
  connector closed — and a reconcile replaces it.
- Both are spaced by a bounded exponential backoff with equal jitter (`StreamLane.Backoff`,
  ASSURE-STREAM-LANE-BACKOFF-1): the first failure of a streak retries at once, then 1 s doubling to a 60 s cap
  (`-Dstream.lane.backoff.baseMs` / `-Dstream.lane.backoff.maxMs`). The death backoff is held per Pipeline by
  `PipelineScheduler` so it outlives each replaced lane; the probe backoff is per lane. The stack trace (and the
  probe-failure WARN) logs once per streak; a healthy probe or drain resets it.
- The drain re-checks paused before acquiring and again before ingesting.
- Wiring: `PipelineScheduler.reconcileStreamLanes` runs on every acquisition tick (so hot-reload, pause and
  activation start or stop lanes), `forget` closes a lane, and `CollectorService.close` closes them all.
  `STREAM` stays LOOP-scheduled: the ordinary ticks keep running as the floor, and a lane only makes drains
  happen sooner.
- ⚠ The lane is per process: under Advanced scale-out every pod runs one, and the `acquireGuard`/run lease
  make the extra ones no-ops rather than duplicates.
- **Measured** (MockConsumer, N=200, T=1 s, 569 events in 20 random bursts): event → committed slice frontier
  p50 563 ms, **p95 1030 ms**. Through a real `CollectorService` with real ingest (`faketest` connector,
  T=1 s): file arrival → committed batch 1.6 s. ⛔ **Not measured: the Incident hop.** Alert evaluation after
  the commit is outside the lane and has no harness here, so D-P7 is *not yet proven end to end*.
  `KafkaRealBrokerTest` runs against a real broker at `-Dkafka.it.bootstrap` (default `localhost:9092`) and
  SKIPS with that message when none answers — no broker ships with the build.

## Connection profiles, secrets, host keys, tunnels and proxies

**Profiles and secrets** — `<name>_connection.toon` (`ConnectionProfile`), the deliberate `base_path` /
`basePath` pair and its round-trip guard, the five `SecretResolver` reference forms, `ConnectionRegistry`, and
the four proxy dial-through ships (SOCKS5, FTP/FTPS, HTTP `CONNECT` with the `TunnellingSocket` redirect,
PostgreSQL JDBC) are owned by [connectors](../connectors/spec-connectors.md); the copy-pasteable shapes are in
the [connectors runbook](../connectors/admin-guide-connectors-runbook.md). ⛔ No `#` comment lines in any
`ConfigCodec` file — JToon rejects them. Facts recorded here because the connectors page states them
differently or not at all:

- ⚠ **SEC-07 (2026-09-06): `${FILE}` and `${KEYSTORE}` are Standard + Advanced only.** They are served by
  the `SecretsProvider` SPI in the core, whose implementation is `inspecto-secrets`'s
  `FileKeystoreSecretsProvider`, discovered by ServiceLoader
  (`providers/inspecto-secrets/src/main/resources/META-INF/services/com.gamma.auth.secrets.SecretsProvider`). A
  bundle without that module — Basic — **refuses the scheme with an `IllegalStateException` naming the
  edition, never a silent `null`**; a connection test surfaces that refusal as its failure. A Vault/KMS scope
  is the Enterprise follow-on. ⚠ `connectors-runbook.md` (formerly `integrations.md`) once listed only 2 of
  the 5 forms and omitted this gate; its current text is correct — prefer it over any older account.
- Only `ConnectionRegistry.find` is consulted at `forConfig` time; a missing profile yields `null` and the
  factory decides. Since 2026-09-25 a remote Collector whose `collector.connection` names no profile in the
  Space is an ERROR finding at save ([plan](plan.md)).
- **Proxy precedence.** A proxy is **ignored when an SSH `tunnel:` is also configured**, because the tunnel
  already rewrites the dial target to a local loopback forward. Fail-closed on what cannot be routed: an
  unrecognised proxy `type`, a JDBC driver other than PostgreSQL, and a proxy combined with an SSH tunnel on
  the JDBC path.

**Host-key policy.** `HostKeyPolicy` (`providers/inspecto-connectors/src/main/java/com/gamma/acquire/connectors/HostKeyPolicy.java`) is
derived from the profile's `options` (`HostKeyPolicy.from`). Three knobs, purely additive — with none of them
set the legacy accept-on-connect behaviour is unchanged (and logged at debug):

| Option | Effect |
|---|---|
| `host_key` | 🔴 a key **FINGERPRINT** — `SHA256:<base64>` or OpenSSH MD5 colon-hex. The field is literally named `fingerprint` and is handed to sshj's `client.addHostKeyVerifier(fingerprint)`, so a **raw `ssh-rsa AAAA…` key line cannot work**. `connectors-runbook.md`'s host-key table is right and any doc showing a raw key line is wrong. (`docs/FEATURE_INVENTORY.md` once showed one; its host-key row now says a raw key line is rejected.) |
| `known_hosts` | path to an OpenSSH `known_hosts` file; the host must have an entry. Works across hops. |
| `strict_host_key: true` | when set and neither of the above is configured, **refuse to connect** rather than silently accept any key. |

Over a bastion, `HostKeyPolicy.bastionView` encodes the asymmetry: a single `host_key`
fingerprint matches **one** host, so it pins the *target* and does not apply to the bastion; `known_hosts`
can verify both hops. For a `db`/`ftp`/`ftps` profile reached through a tunnel the bastion is the only SSH
hop, so `host_key`/`known_hosts` pin **it** (`DbConnections.open`, `FtpConnector` — both pass
`HostKeyPolicy.from(profile)` to `SshTunnel.open`).

**SSH bastion.** `SshTunnel` is an `AutoCloseable` SSH TCP-forward honoured by `sftp`, `db`, `ftp` and
`ftps` via the profile's `tunnel:` block. FTP is the special case: it opens a **control** connection *and*
separate **passive data** connections, so the bastion must carry both — set `options.passive_ports` to the
range the server advertises (`PassivePorts`); each port is forwarded loopback→server and the client is told
to dial the loopback. Active mode cannot traverse a tunnel, so a tunnelled FTP connection is always passive.
⚠ Tunnel FTP *without* `passive_ports` and only the control channel is forwarded — transfers fail unless the
server's passive ports happen to be independently reachable (the connector logs a warning).

## The control surface

| Route | Notes |
|---|---|
| `GET /collectors` | flat view of every pipeline's acquisition config (`AcquisitionRoutes`); since 2026-09-15 each row also carries the pipeline's **polling session** — `lastPollAt`, `pollCount`, `lastPollError`, `lastPollErrorAt` (`PipelineScheduler.pollStates`, `DUCKLE-C9-WATCHER-NOT-A-RUN-1`). ⚠ IN-MEMORY and lossy across restart by design: this is liveness ("is this collector alive, when did it last look, what did it last say?"), not an audit trail — the durable record of work is the Run/Consignment ledger. ⛔ It corrects no miscount: a quiet poll never minted a Run (`CollectorProcessor.ingest` returns before `RunIds.next()`, fixed `1fda46d5`), which is why the row was rescoped to observability before it was built. `lastError` is a message, not a metric, because a Prometheus gauge cannot say *what* failed. 🔴 Since `POLL-STATE-BLIND-TO-CONNECTOR-FAILURE-1` (closed 2026-09-15) an **acquisition that throws before landing anything** — a connector that cannot be built (unknown dataset, unreachable host) — is recorded as a poll that happened and failed (`lastPollError: "acquisition failed: …"`); before that the row stayed all-null while the log carried the ERROR. ⚠ Still NOT a poll: an event-triggered run (`on: dataset` / `on: commit`) — those are Runs, and `pollCount` counts scheduler polls only, by design. |
| `POST /collectors/{id}/notify` | ACQ-6 push discovery; `canOperateRuns`; `202`+`Location` under v1; audited `collector.notified` |
| `POST /streams/{id}/records` | **push ingest** (ASSURE-PUSH-INGEST-1). `{id}` is the Collector id (`GET /catalog/streams`). NDJSON (`application/x-ndjson`) or CSV (`text/csv`), validated WHOLE before anything lands; one server-named file `push-<utc>-<8 hex>.<ext>` moved atomically into `dirs.poll`, so the ordinary Collector path ingests it. Gates: `canOperateRuns` · `429` per-caller push bucket (burst 60, 10/s) · `404` unknown Stream · `501` Dataset-fed · `409` template, `pipeline paused` (paused or not active — refused rather than stranded in an unwatched inbox), or the same caller's `Idempotency-Key` still in flight · `415` other types · `413` over `-Dstreams.push.max_bytes` (8 MiB) or `-Dstreams.push.max_records` (100 000) · `413` also for a KEYED push over the W5 hash window (`Idempotency.MAX_REQUEST_BYTES`, 1 MiB), which the stage would run un-keyed · `422` not UTF-8, a malformed line (named), no records, or a `file_pattern` that would never pick the file up. A leading UTF-8 BOM is stripped for both formats, before validation and before the write. A keyed replay returns the first `{file}` to the same caller only — the key is the W5 one (Space + principal + path + body hash, SEC-IDEMPOTENCY-REPLAY-1), and the in-flight reservation reuses it, so two callers' equal keys never collide. One AUDIT row `stream.records_pushed` with `records`, `bytes`, `file` — never the payload. ⛔ **Not a config write**: data arriving at a Stream is what a Collector landing a fetched file does, so no write-root gate and no approval hold. ⚠ The idempotency store is in-memory per process (10 min): a replay after a restart lands the batch again; row-level dedup is downstream, as for Kafka. |
| `GET /metrics/acquisition` | JSON snapshot of the twelve acquisition metrics (`AcquisitionRoutes.ACQ_METRICS`), complementing the text-only Prometheus `/metrics` |
| `GET /connections`, `GET /connections/{id}` | profiles, **secret-masked** |
| `POST`/`PUT`/`DELETE /connections[/{id}]` | ⚠ writes require **`canOnboardConnections`** — its own Admin-only grant, deliberately *not* `canAuthorWorkbench`, because Connections are the credential/egress surface (`ConnectionRoutes.register`). |
| `POST /connections/{id}/test` | TCP reachability + latency + secret **resolvability**; `canOnboardConnections` since 2026-09-17 (it dials from the server's network position); `404` unknown id; `422` on `?target=tunnel\|proxy` with none configured, or an unsupported target |
| `POST /connections/test` | probes an **unsaved** profile from the create/edit form; `?target=connection\|tunnel\|proxy` picks the hop; `canOnboardConnections` since 2026-09-17 |
| `POST /connections/{id}/probe` · `GET …/explore?path=` · `GET …/sample?path=&limit=` | the graded workbench. Gates: `404` unknown id · `422` unknown check name · `400` missing sample path · **`403` path escape (jail)** · `404` unknown path · `501` connector without workbench support · `502` protocol failure. `probe` needs `canOnboardConnections` (since 2026-09-17: it reads through the saved credential); `explore` and `sample` are reads with no capability gate. |

The probe orchestration (`ConnectionProber` — 🔴 **a probe never fabricates a check it did not run**) and the
per-scheme workbenches are in [connectors](../connectors/spec-connectors.md). Their two standing disciplines:
**remote sample fetches the whole file only when the listed size is ≤ 8 MiB** (`SAMPLE_FETCH_CAP`), and
**WRITE is always *skipped* for DB, object stores and Kafka** — a workbench never mutates a database, writes a
scratch object into someone's production bucket, or produces a probe record onto a topic to prove write access.

## Observability

Events (`spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventType.java`), all emitted through `AcquisitionTelemetry` with
`CollectorProcessor`'s FQN as the event `source` so the stream stayed byte-identical when the emitters moved
out: `FILE_DISCOVERED` · `FILE_STABLE` · `FILE_FETCHED` (carries `bytes`) · `FILE_VALIDATED` ·
`FILE_FETCH_FAILED` · `FILE_QUARANTINED` · `FILE_CHANGED` · `FILE_ARCHIVED` (carries `action`) ·
`SOURCE_CIRCUIT_OPEN` · `SEQUENCE_GAP` (carries `expected`, `sequence`, `unit`).

Metrics, all labelled by pipeline: `inspecto_files_discovered_total` · `inspecto_files_downloaded_total` ·
`inspecto_downloads_failed_total` · `inspecto_duplicates_skipped_total` · `inspecto_watermark_skipped_total`
· `inspecto_post_actions_failed_total` · `inspecto_bytes_transferred_total` · `inspecto_fetch_seconds`
(histogram) · `inspecto_active_connections` (gauge) · `inspecto_files_waiting_stability` (gauge) ·
`inspecto_acquire_backpressure_skips_total` · `inspecto_slice_frontiers_committed_total`. Two more are labelled
by connection instead (see the connector SPI section): `inspecto_stream_lag_records` (gauge, + `topic`, `partition`) and
`inspecto_stream_slices_drained_total` (+ `topic`). `AcquisitionTelemetry` methods are pure leaves — each only
emits an event or bumps a metric, carrying no acquisition logic.

## Network shares — the declined design, recorded once

⛔ **NFS/SMB/CIFS is a declined design, not a gap.** The engine deliberately has **no** in-process SMB/NFS
client, and the config safety validator **rejects UNC paths** (`\\server\share`) at the path jail — that is
the security boundary. To collect from a share: mount it at the OS level (Windows
`net use X: \\server\share /persistent:yes`; Linux `mount -t nfs|cifs … /mnt/share`), add the mount point to
the jail via `-Dassist.safety.roots=<existing roots>;X:\` (a `;`-separated list), and point `dirs.poll` (or
`base_path`) at the mounted path. The built-in `local` connector then handles discovery / stability / dedup
identically to a local inbox, and credentials, reconnection and caching stay the OS's job, where they are
audited and battle-tested. Why it is refused: [plan](plan.md).

## Defaults worth not "fixing"

Recorded because both source files agreed on them and a merge could plausibly "correct" one: ledger backend
**`memory`** · staging dir **`<dirs.temp>/acquire`** · back-pressure high-water **0 (off)** · S3 region
**`us-east-1`** · duplicate mode **`path`** · guarantee **`BEST_EFFORT`** · post-action **`RETAIN`** ·
`on_unsupported` **`WARN_AND_CONTINUE`** · discovery **`poll`** · `recursive_depth` **`-1`** ·
consignment `order` **`mtime`** · stability `window` **`30s`** / `size_checks` **`2`** · retry
`initial_delay` **`1s`** / `max_delay` **`60s`** / backoff **`EXPONENTIAL`** · breaker `failure_threshold`
**`5`** / `cooldown` **`5m`** · `parallel_fetch` **`1`** · `rate_limit` **0 = unlimited**.

---

# Part 3 — The Unpack stage

> **As-built.** The Collector-level stage that expands compressed/archived inbox files into plain
> files *before* Consignments are planned, and the run-level ledger that reports what became of each
> Archive. Shipped `d6cd55b7` (2026-08-23); honesty fixes `fe6e1d7e` and the ledger 2026-08-26.
> Plan (archived, complete): [`archived-documents/plans-archive/unpack-stage-plan.md`](../../../../archived-documents/plans-archive/unpack-stage-plan.md).
> Related: [`spec-ingest.md`](spec-ingest.md) (ingest and parsing) · [`architecture.md`](architecture.md)

Vocabulary (`GLOSSARY.md`): an **Archive** is a container file; an **Entry** is one inner file — ⛔ never
*member* (a Member is a file in a Consignment); **Unpack** is the Collector-level stage that turns Archives and
compressed streams into plain files, ⛔ not a parser and not bare *decompression*.

## 1. Where it sits, and why that is the whole trick

`CollectorProcessor.run` expands the **mutable candidate list**, immediately before
`ConsignmentPlanner.plan` freezes it. Every expanded file is therefore an ordinary Consignment member
*from birth* — planner sizing, batching, schema selection, both engine lanes, audit and finalize all
work unchanged, with **zero** modification to the Consignment model. An Archive can therefore outlive one
Consignment (500 Entries at `max_files: 100` plan five).

⛔ **Do not "update the Consignment" with expanded members.** `Batch`, `Batch.Member` and
`ConsignmentManifest` are immutable records and `ManifestStore` has no merge; adding members mid-flight
means a mutation API on the crash-safe commit path, for no gain. Expanding one step earlier is free.

**Code and kinds.** `com.gamma.etl.unpack` (`UnpackStage`, `UnpackLimits`, `UnpackOrigins`, `UnpackLedger`,
`Decompressors`, `BuiltinDecompressors`, `ArchiveDecompressorPlugin`). Kinds: 1→1 streams (`.gz`, `.bz2`, `.Z`)
and 1→N Archives (`.zip`, `.tar`, `.tar.gz`); **xz and zstd have no plugin** (a dependency sign-off).

**Caps, fail-closed** (`ConfigSpecs`): `processing.unpack.max_entries` (10 000), `max_entry_bytes`,
`max_total_bytes`, `max_ratio` (the bomb cap), `depth` **= 1, refused otherwise**; the zip-slip jail
(`ArchiveDecompressorPlugin.java`, tested with a re-packed archive); `data_extensions` published, an empty list
honoured as a choice (§6).

**Engine-aware:** a format the chosen lane decodes itself is left alone — but only for `STREAM`
kinds. ⚠ A `.zip` the Java lane "reads" is only ever its FIRST entry (`Compression.firstEntry`), so
an ARCHIVE is always expanded here or its remaining members vanish silently.

**Failure is fail-open into the existing path:** when an expansion fails, the ORIGINAL stays in the
candidate list, the engine fails to read it, and the normal machinery quarantines it as `unreadable`
with a per-file audit row. Quarantining at the Collector would bypass the status ledger.

## 2. Naming, and the two things that are DATA

Entries materialize as `<NNNNN>_<flattened-entry-name>`. The zero-padded index makes **path order ==
archive order**, which matters because every entry shares an mtime and `ConsignmentPlanner` orders by
mtime with an absolute-path tie-break. Flattening (separators → `_`) removes the zip-slip vector; the
resolved path is still asserted to stay under `workDir`.

🔴 **The `NNNNN_` prefix is workspace bookkeeping and must never reach data.** Lineage and
`output.filename_column` record the **ENTRY name** (`good.csv`, never `00001_good.csv`) — the grain
the operator ratified 2026-08-26. The mechanism: the name is captured **at register time**
(`UnpackOrigins.register(actual, original, lineageName)`, kind-aware in `UnpackStage`), not
reverse-engineered per consumer, and `ArchiveDecompressorPlugin.entryName` — the exact reverse of
`entryPath` — lives in the same class so the two halves of the format cannot drift.

⚠ **`srcIdToFile` is a FIVE-site concern, not three.** The three `srcIdToFile.put` sites
(`CsvIngestStrategy`, `NativeCsvStreamingEngine`, `UnionModeIngester`) are the obvious ones; the wrap
lane names its file at `DuckDbRecordSink` construction rather than per row, so
`GenerationModeIngester` and `UnionModeIngester`'s sink path are two more. Grepping `srcIdToFile`
alone leaves that lane leaking the temp name.

⛔ Do not "simplify" `filename_column` to the container name, and do not widen it to the composite
`archive!entry` — that bakes a key downstream consumers must parse into a data column. (`archive!entry` is
the manifest and quarantine address only; the per-file ledger's `logical_name` is the Archive's identity, §5.)

## 3. A skipped entry is not silence

An encrypted or unsupported-method member has readable metadata but no readable bytes
(`!canReadEntryData`). It is skipped — and **reported**, via the `expand(source, workDir, limits,
skippedOut)` default SPI overload (default-method, so external plugins are unaffected).

Skips are recorded against the ORIGINAL (`UnpackOrigins.registerSkipped`) and drained **exactly
once** by `finalizeSource` (`takeSkipped` is atomic; the first of the archive's batches to finalize
wins) into `SKIPPED_UNREADABLE` manifest rows addressed `archive!entry`, with **srcId `-1`, no backup
and no marker** — nothing was ever planned and there are no bytes.

An **all**-unreadable archive still fails whole.

## 4. The archive verdict (operator sign-off 2026-08-26, ratified 2026-09-06)

`UnpackStatus` — the ARCHIVE's vocabulary. ⚠ Deliberately NOT the per-FILE status vocabulary: one
describes a container, the other a member. Do not merge them.

| Status | Meaning |
|---|---|
| `UNPACKED` | every entry found was ingested |
| `UNPACKED_PARTIAL` | ≥1 ingested **and** ≥1 not ingested — quarantined **or** skipped |
| `UNREADABLE` | expansion failed (corrupt / unsupported / cap breach), or entries existed and none was readable |
| `EMPTY` | archive opened, zero entries found |

🔴 **`UNPACKED_PARTIAL` COMMITS.** The verdict is **reporting, never a gate** — today's per-file
semantics are that a bad file never blocks its batch-mates, and failing whole would discard 499 good
ingests for one bad entry in a 500-entry archive. ⛔ Do not "fix" this by failing the Consignment.

🔴 **`EMPTY` and `UNREADABLE` are one code path** — both end in the same throw. They stay distinct
statuses by operator decision (an operator should hear "your zip is empty" apart from "your zip is
locked"), which is why `NoUsableEntriesException` carries `entriesFound()`: the count is the *only*
thing separating them, and it must never be recovered by string-matching the message.

**Ratified 2026-09-06 — the archive-level verdict and the entry grain.** Both were "pending ratification"
while the code already did them: `UnpackStatus` is the archive-level roll-up, and lineage records the ENTRY
(`good.csv`), never `bundle.zip!good.csv`. **Ratified as in force**; a composite grain would break
`filename_column` consumers and the origin resolver for no reported need.

## 5. The run-level unpack ledger

`<pipeline>_unpack_<runTimestamp>.csv` — the fourth ledger beside status/batches/lineage, one row per
**Archive** per **Run**. An Archive can outlive one Consignment (500 entries at `max_files: 100` plans
five batches), so its roll-up cannot be a `MemberEntry` in "the" manifest — there are five. The
expansion happens once per Run, so the Run is the honest home.

Columns: `run_id, archive_relpath, format, entries_found, entries_ingested, entries_failed,
entries_skipped, bytes_in, bytes_out, status, error, consignment_ids`.

⛔ **Declared ONCE** in `UnpackLedger.COLUMNS`; `HEADER` is `String.join`ed from it and the codec is
index-aligned to it, pinned by a test. This is a direct instruction from the plan: the **batches**
ledger restates its column list in FIVE places (writer header, row record, codec,
`OperationalTables.BATCHES`, a test literal), none generated from another — and because reads are by
header *name*, a stale mirror silently **hides** a column rather than erroring.

**Lifecycle:** accumulated in memory during the run, flushed once in `CollectorProcessor.run` **after
the batch futures join**. ⚠ Deliberately *not* written at the `UnpackOrigins.consume()` release
points, even though those are where an archive's last member lands: a batch that fails at COMMIT runs
neither the finalize nor the quarantine path, so the release never fires — for exactly the archives an
operator most needs a row for. `flush` is idempotent (it removes the run's rows).

🔴 **Keyed on the archive's normalized absolute PATH, never its basename.** `MemberAudit.origin` is a
display column and a bare filename; `in/east/data.zip` and `in/west/data.zip` share one. Keying the
roll-up on it would silently sum two archives into one row — which is why `MemberAudit` carries
`originPath` beside `origin`, captured in the same helper.

⚠ **Both origin fields must be captured at INGEST time**, not where the audit row is written:
`writeAudit` runs after `commit`, and commit's `UnpackStage.cleanup` consumes the mapping — a late
lookup reads blank for every expanded file.

**Only ARCHIVE kinds get a row.** A 1→1 stream expansion has no entries to roll up and its outcome is
fully described by its single file's own status row; a row here would double-report it.

**Read surface (2026-08-26):** `StatusStore.unpack(cfg)` — a `default` returning empty, so every
pre-existing `@PublicApi` implementer (incl. the anonymous test stores) keeps compiling — backed by
`FileStatusStore` (globs `<pipeline>_unpack_*.csv`) and `DbStatusStore` (projects into
`inspecto_status_unpack`, carried through sync/delete/rename/browse). `OperationalTables.UNPACK`
**references `UnpackLedger.COLUMNS`** rather than restating them, and `unpack` joined `STAGE1_NAMES`
so report-sql can query it.

### The per-file status ledger's `logical_name` column (2026-08-26)

Beside `origin` (§ above), the per-file `_status_*.csv` ledger carries `logical_name`: the SAME inbox
file's extension-insensitive **identity** (§6 below) — poll-relative, so unlike `origin`'s display
basename it IS a key. This is what a report groups on to unite a re-delivery with an earlier
compression spelling, rather than reading the alias hit out of the dedup log.

⚠ **For an expanded ARCHIVE this is the archive's identity, shared by every one of its entries** — one
delivery, one identity — never the entry's own name (that is what lineage records, through
`UnpackOrigins.lineageName`). ⚠ Computed from `MemberAudit.originPath()`, captured at INGEST time —
the identical trap `origin` already documents: a late lookup after `commit` reads blank. Appended
last (after `origin`) and QUOTED — a poll-relative identity may carry a comma.

## 6. Extension-insensitive identity, and why the collision is not a bug

One logical file may present as `cdr_20260823.csv.gz`, `cdr_20260823.Z`, `cdr_20260823.csv` or bare
`cdr_20260823`. `LogicalNames` derives one key for all of them: strip compression suffixes
**iteratively** (rule 1, from the *discovered* plugin suffixes — never a hand-mirrored twin list),
then strip **at most one** data extension (rule 2). ⛔ Never "everything after the first dot":
`feed.2026.08.23.csv` must key as `feed.2026.08.23`. Directories stay in the key — extension-
insensitive, never path-insensitive.

🔴 **The collision is the inescapable dual of the requirement.** Those four spellings meet *only* at
the fully-stripped tier — `cdr.csv.gz` → `cdr.csv` → `cdr`, and `cdr.Z` → `cdr`. That same strip is
why `report.csv` and `report.json` are also one logical file. ⛔ **Do not "fix" this with a two-tier
scheme** that matches on the compression-stripped form: it would break the operator's actual
requirement, which is the whole reason the mechanism exists. The remedy is the allow-list, not a
redesign.

`processing.unpack.data_extensions` (published, `FieldType.LIST`, default
`.csv .tsv .txt .json .jsonl .ndjson .xml`) is the escape hatch: narrow it, or author
`data_extensions[0]:` for an **empty** list to opt out entirely and key on verbatim names. ✅ Authorable from
the UI since 2026-09-15 (`SCHEMA-FORM-EMPTY-LIST-1`): the schema form's `list` control offers an **"Explicitly
none"** toggle on a non-required list, which writes `[]` — distinct from clearing the entries, which still writes
`null` ("unset, the default applies"). The two states never collapse into each other at the control. Empty is
honoured as a CHOICE, never as "unset". Entries are normalised (bare / upper-case / padded → leading-
dot lower-case). ⚠ TOON's scalar-list form is **counted** (`data_extensions[2]: "a", "b"`), not
bracketed — a bracketed literal parses as one comma-split string, silently.

⚠ **ONE permitted mirror.** `ConfigSpecs` restates the default because `inspecto-config` sits *below*
`inspecto-etl` and cannot import `LogicalNames`; `LogicalNamesTest` pins them equal. A drifted
published default tells an operator the engine does something it does not. ⛔ Do not add a third.

**The two dedup lanes carry very different risk:**

| Lane | On an alias hit | Risk |
|---|---|---|
| Checksum / ledger | Finds a *candidate*; the hash still decides, so different bytes ⇒ `CHANGED` ⇒ reprocessed | Harmless |
| Marker (path) | `isAlreadyProcessed` returns true ⇒ the file is **dropped**, and nothing downstream can overrule it | 🔴 Real |

Which is why the marker-mode skip logs at **WARN** and names the remedy. Exposure is scoped: the
alias is only ever *written* for compression-involved names, so two plain files collide only after a
compressed spelling of that logical name has been processed.

## 7. Standing traps

- ⚠ **The per-file status vocabulary is still five bare literals** (`SUCCESS`,
  `QUARANTINED_EMPTY`, `QUARANTINED_MISMATCH`, `QUARANTINED_UNREADABLE`, `SKIPPED_UNREADABLE`) across
  ~6 files. The plan's step 14 predicted this drift and it happened anyway when the fifth was added.
- ⚠ `UnpackOrigins` entries are removed only by `consume()`. A batch that fails at COMMIT runs
  neither path, so its mappings leak — bounded in practice, unbounded in principle.
- ⚠ **Nested archives are refused by design** (`depth` must be 1, refused by name). A `.tar.gz`
  inside a `.zip` is extracted as an opaque member and then quarantines.
- ⚠ A crash mid-archive re-ingests committed members: the container is marked only after its LAST
  member, so a crash re-expands it whole. Relies on `OVERWRITE_OR_IGNORE` output idempotence.
- ✅ **The two decompression vocabularies are UNIFIED (2026-08-28).** The legacy inline
  `Compression.java` still exists — it is the Java lane's in-stream reader for `SchemaSelector`,
  `BoundaryScanner`, `FileChunker` and `CsvIngester`, and this SPI does not replace it (the SPI
  materialises *files*; that path wraps a *stream*). What is gone is the **hand-mirrored list**:
  `Compression.INLINE_SUFFIXES` is now the one declaration, read by `Compression.isCompressed`, by
  `UnpackStage.laneReadsItself` and by `LogicalNames.LEGACY_SUFFIXES`. Before, all three spelled
  `.gz`/`.bz2`/`.zip` out separately.
  - **The drift that actually bit is one-directional, and that is what the guard checks.**
    `laneReadsItself` is what lets the stage *skip* a file ("the lane reads it itself"), so a suffix
    the inline path claims but **no plugin owns** would be skipped by the stage and then handed to a
    lane that cannot open it. `CompressionTest.inlineVocabularyIsOwnedByPlugins` makes that a red
    build. ⛔ Do **not** "complete" it with the reverse containment: the SPI is deliberately wider
    (`.Z`, `.tar`, `.tar.gz`/`.tgz` have plugins and no inline branch) and that is safe, because the
    stage expands those before any engine sees them.
  - ⚠ **DuckDB's branch of `laneReadsItself` stays literal on purpose** (`.gz`/`.zst`). It states a
    *foreign* engine's capability — what `read_csv` auto-decompresses — not a vocabulary this
    project owns, so deriving it from our plugin set would be wrong, not DRY.
  - 🔴 Grounding correction found on the way: `LogicalNames.LEGACY_SUFFIXES` was documented as
    "suffixes the legacy path understands but **no plugin owns yet**", hard-coding `.zip`. That was
    stale — `ArchiveDecompressorPlugin$Zip` has owned `.zip` since the 2026-08-23 build, so the
    entry was redundant with `Decompressors.knownSuffixes()` rather than covering a gap.
- ⚠ **The `META-INF/services` file's header comment is stale** (noticed 2026-08-28, deliberately not
  touched): it says "ORDER MATTERS: the resolver is a first-match linear scan", but
  `Decompressors.forFile` resolves by **longest matching suffix** and its own javadoc says ordering
  the services file is explicitly *not* relied on (ServiceLoader order across jars is unspecified).
  The listed order is harmless; the reason given for it is wrong.

---

## References

| Mechanism | Owning file |
|---|---|
| Plan of record — requirements, decisions, open work, refusals, verification | [plan.md](plan.md) |
| Poll cycle, phases A–F summary, the Collectors module page, multi-location ingest | [architecture.md](architecture.md) |
| Connector SPI, the remote schemes, stage-then-land, profiles, secrets, workbench, proxy chain | [connectors/spec-connectors.md](../connectors/spec-connectors.md) |
| The Kafka connector, its fence and stream lane | [connectors-kafka/spec.md](../connectors-kafka/spec.md) |
| Operator runbook: profile YAML, host-key table, FTPS, bastion, db-export | [connectors/admin-guide-connectors-runbook.md](../connectors/admin-guide-connectors-runbook.md) |
| Module boundary + dependency confinement of the connectors jar | [connectors/architecture.md](../connectors/architecture.md) |
| Consignments, the ingest strategies and commit order that consume the landed inbox | [spec-ingest.md](spec-ingest.md) (Part I ingest, Part II Consignments) |
| Screens and the Collector configuration surface | [user-guide.md](user-guide.md) |
