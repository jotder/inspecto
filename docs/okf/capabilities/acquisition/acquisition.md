---
type: Capability
title: Acquisition & connectivity (ACQ)
description: How data reaches the platform — Connections, Collectors, and the acquisition framework's poll cycle. The requirement of record for the ACQ area, its specification, its decisions, and what was refused.
resource: inspecto-acquire/src/main/java/com/gamma/acquire
tags: [acq, capability, acquisition, connections, collectors, connectors, ledger]
timestamp: 2026-09-08T00:00:00Z
---

# Acquisition & connectivity — capability spec (`ACQ`)

> **What this page is.** The single entry point for the ACQ capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of
> it — §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather
> than restating them. First of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../archived-documents/plans-archive/docs-consolidation-plan.md).
>
> **Canonical vocabulary.** The two authored nouns are **Connection** (a named endpoint + credential
> definition) and **Collector** (a configured collection task bound to one Connection). ⛔ The
> acquisition entity is never a *Source* — that flipped 2026-07-14 (`GLOSSARY.md` §2/§3). A *data <!-- vocab-allow: the doc states the ban it enforces -->
> origin* is a **Stream** or a **Reference**. The pipeline TOON block is `collector:`, and the parser
> reads only that spelling — there is no `source:` block and no alias for one.

## 1. Purpose & scope

ACQ is everything that happens **before** a byte becomes a record: reaching a remote system, deciding
which files are new, proving they are complete, and handing them to ingest exactly once. Its promise is
narrow and operational — *one declarative Collector onboards a feed, and a crash never double-loads it.*

**In scope:** Connections and their credentials; Collectors and their cadence; the connector SPI and the
schemes that implement it; discovery, stability detection, duplicate prevention, watermarks, gap
detection; retry, circuit-breaking and rate-limiting; post-fetch disposition of the remote file; the
acquisition ledger.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| Parsing a fetched file into records | `ING` — ingestion & parsing |
| What runs after a commit, and in what order | `PIP` — pipelines & orchestration |
| The Consignment as a unit of work, and its addressing | `PIP` / the engine's consignment concepts |
| The Connections **pane** and the Collector drawer | `UI` — operator console |
| Who may operate a Collector run | `SEC` — capability gating (`canOperateRuns`) |

⚠ The boundary that is easy to get wrong: **acquisition never widens to "read from N places at once."**
Multi-location ingest is composition of existing pieces — see
[multi-location ingest](../../backend/pipeline-graph/multi-location-ingest.md), which is a `PIP` concern.

## 2. Requirements of record

Seven requirements, all shipped. ⚠ **`EDITIONS.md`'s feature × edition matrix is authoritative for the
Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `ACQ-1` | **Connections** — named endpoint + credential definitions (SFTP/FTP/FTPS, database), reused by many Collectors | Must | ✅ SHIPPED | All ⚠ |
| `ACQ-2` | **Collectors** — configured collection tasks (paths/queries, cadence, filename patterns, dedup policy) bound to one Connection | Must | ✅ SHIPPED | All |
| `ACQ-3` | Acquisition framework — ledgers, dedup, watermarks, gap detection, retry (phases A–F) | Must | ✅ SHIPPED | All |
| `ACQ-4` | Object-storage (S3/GCS/Azure/MinIO) **+ network-share (NFS/SMB)** connectors on the connector SPI | **Must** | 🟡 **PARTIAL — object storage shipped, network-share REFUSED** (§6.1) | All |
| `ACQ-5` | Streaming source consumer (e.g. a Kafka topic drained by a Collector) | Should | ✅ SHIPPED 2026-07-08 | All |
| `ACQ-6` | Push / event-driven file discovery (replace poll where the remote can notify) | Could | ✅ SHIPPED 2026-07-08 | All |
| `ACQ-7` | etag / version-aware dedup dimensions | Should | ✅ SHIPPED 2026-07-08 | All |

**Three corrections this table makes to its predecessor** (`REQUIREMENTS.md` §3.1), each verified
against source:

- **`ACQ-4` was marked a flat `SHIPPED`.** It is not: the requirement asked for object storage **and**
  network-share connectors, and the NFS/SMB half was **declined by design**, not delivered. A `Must`
  recorded as shipped while half its scope was refused is the shape that makes a requirement register
  untrustworthy — the refusal now has a home in §6.1 instead of being absorbed into a green tick.
- **`ACQ-6`'s route was wrong.** It documented `POST /sources/{id}/notify`; the route is
  **`POST /collectors/{id}/notify`** (`AcquisitionRoutes.java:27`), audited as `collector.notified`.
- **`ACQ-7`'s config key was wrong.** It documented `source.duplicate.mode`; the block is
  **`collector.duplicate`** (`PipelineConfigParser.java:376,409` — `duplicate` is read from the
  `collector` map).

⚠ **`ACQ-1` carries an edition nuance the matrix column cannot express.** Connections are `All`, but two
of `SecretResolver`'s five reference forms — `${FILE:…}` and `${KEYSTORE:…}` — are **Standard +
Enterprise only** (SEC-07): they are served by `inspecto-security`'s `SecretsProvider`, so a Personal
bundle refuses the scheme *by name* and a connection test surfaces that as the failure. See §3.

🔴 **What none of these rows said, and every one of them depended on:** for 85 days the remote
connectors were **build-available and deploy-absent**. `inspecto-connectors` was a reactor module that
no bundle shipped, so `connector: s3` and its siblings could be authored, compiled and tested and then
did nothing in a deployment. Fixed 2026-09-07 (CONNECTORS-BUNDLE-1) — it now rides every bundle as the
shaded `inspecto-connectors.jar` sidecar. A requirement is not delivered until it is *reachable*;
"SHIPPED" against a module no bundle carries is the most expensive kind of true statement.

## 3. Specification

### 3.1 What acquisition is, and where it stops

Acquisition is a **collection engine, not a directory poller**: *discover → determine readiness → guarantee
collection semantics → retrieve and validate → finalize and audit*. Its output is a byte-complete file
sitting under `dirs.poll` (the **inbox**), landed atomically. Everything after that — parse, expectations,
transform, sink — is the ingest lane's problem and does not special-case a remotely-collected file. This
seam is the whole design: `CollectorConnectors.localForConfig` exists precisely so the ingest walk uses a
**local** connector even for a pipeline whose `collector.connector` names a remote scheme, because by then
the bytes are already local (`CollectorConnectors.java:47-60`).

The authored config block is **`collector:`**. ⛔ There is no `source:` block and no alias: the parser reads
`collector` only (`PipelineConfigParser.java:218`, `:376`), and zero committed `*_pipeline.toon` carries a
top-level `source:`. ⚠ `data-acquisition-framework.md` (§13 and passim) still spells
it `source.*`; both are stale, and the reason they are stale is that **`inspecto-acquire`'s own javadocs
still say `source.*`** (`CollectorConnectors.java:11`, `RateLimiter.java:3`, `CircuitBreaker.java:12`,
`AcquisitionLedger.java:16`, `StabilityGate` and others) — the prose inherited the drift from the code
comments, not the reverse. ⚠ `inspecto/examples/README.md:56-59,107,130` has the same stale prefix while the
example files it describes use `collector:`.

### 3.2 The poll cycle and its two timers

`CollectorProcessor` (`inspecto-engine/src/main/java/com/gamma/inspector/CollectorProcessor.java`) is the
entry point, in three public shapes: `run(cfg)` (`:70`) = `acquire` then `ingest` in one cycle — the
one-shot CLI / `reprocess` / manual path; `acquire(cfg)` (`:257`) = phases A–F, fetch-and-land only;
`ingest(cfg, onCommit)` (`:95`) = the inbox walk. `acquire` is a **no-op for a `local` collector**.

In the always-on service the two halves run on **separate timers with separate guards** (B3b): the
acquisition tick is `CollectorService.dispatchAcquireCycle`, cadenced by `-Dacquire.pollSeconds` (defaults
to the ingest poll interval — `CollectorService.java:1010-1012`) with an allowance of
`-Dacquire.maxConcurrent` (defaults to the ingest budget, `:574-575`), and each pipeline is serialised by a
dedicated `acquireGuard`. So a slow fetch neither blocks nor is blocked by ingest, and two acquisitions of
one pipeline never overlap.

⚠ **Gotcha — the two guards are deliberately not one.** A manual "run now" acquires inline under the
*ingest* `runGuard`, so a manual run **can** overlap one background acquire tick of the same pipeline. This
is benign and self-correcting: landing is atomic (§3.7) and duplicates are caught by markers/fingerprint
dedup. Do not "fix" it by merging the guards — that would serialise ingest behind fetch again.

**Back-pressure (B4) throttles the producer.** If a pipeline's inbox backlog (`CollectorProcessor.countPending`,
`:611` — the exact landed count) has reached `-Dacquire.backpressure.highWater`
(`CollectorService.java:579`), `selectDueForAcquire` skips that pipeline this tick and bumps
`inspecto_acquire_backpressure_skips_total` (`PipelineScheduler.java:522`). The already-landed files wait in
the durable inbox — which *is* the spill-to-disk hand-off queue of the design's escalation ladder — until
ingest drains them below the mark. The mark is **0 = off by default**; a failed pending scan returns `-1`
and so fails open (never pauses). This is *negative* feedback on the producer, and is therefore the exact
opposite of `IntakeGovernor`/T15, which deliberately does **not** throttle on inbox lag because throttling
the ingest *consumer* on backlog would be positive feedback.

⚠ The gate bounds backlog **across** ticks, not within one: a single acquire cycle still fetches the whole
discovered listing, so one tick can overshoot the mark. Bounding a single cycle's fetch volume is a separate
deferred knob (`acquire.maxFilesPerCycle`, `BACKLOG.md` §6).

### 3.3 Phase A — discovery

`CollectorConnectors.forConfig(cfg)` resolves the connector: scheme `local` (and a blank/absent
`collector.connector`, which defaults to `local`) → the built-in `LocalFileSystemConnector`, constructed
from `dirs.poll`/`errors`/`quarantine`; any other scheme → a `ServiceLoader<CollectorConnectorFactory>`
lookup, matched case-insensitively on `scheme()`. An unknown scheme **fails fast** with a message naming
`local` as the only built-in (`CollectorConnectors.java:33-35`) — not a silent fall-back to local.

`CollectorConnector.discover(ctx)` lists candidates and **never dedups** — dedup is an engine concern, so a
connector stays a protocol adapter. Discovery is shaped by four keys, all under `collector:`:

| Key | Default | Meaning |
|---|---|---|
| `include[]` | `[processing.file_pattern]` | glob list; a non-empty `include` **replaces** the pattern rather than adding to it (`PipelineConfigParser.java:383-385`) |
| `exclude[]` | `[]` | exclusion globs |
| `recursive_depth` | `-1` | unbounded |
| `discovery` | `poll` | `poll` \| `watch` |

`discovery: watch` (ACQ-6) puts a JDK `WatchService` on a local/mounted poll root, debounced ~1 s via
`-Dservice.watch.quiet.millis` (`CollectorWatcher.java:43`, default `1000`). 🔴 The interval poll loop stays
on as the backstop: **watch narrows latency, it never carries correctness.**

The push half of ACQ-6 is `POST /collectors/{id}/notify` (`AcquisitionRoutes.java:27`) — an S3 event
notification, upload script or upstream job asks for an immediate scan instead of waiting out the interval.
Gated on `canOperateRuns`; `?v1` answers `202 {runId, source, pipeline, status}` plus a `Location` to poll,
legacy answers a synchronous run result off the trigger pool; `404` for an unknown source id. The request
**body is ignored** — a notify is a hint that something arrived and the triggered cycle re-discovers
authoritatively, so a spurious notify is harmless. Audited as **`collector.notified`**
(`AuditTrail.java:151` maps the verb `notify` → `notified`).

### 3.4 Phase B — the readiness / stability gate

`StabilityGate` (`inspecto-acquire/src/main/java/com/gamma/acquire/StabilityGate.java`) holds back
half-written files — the requirement's stated "biggest production problem". Order of decision:

1. Ask `connector.readiness(file)` → `READY` / `NOT_READY` / `UNKNOWN`
   (`CollectorConnector.java:79`). A connector that knows natively short-circuits: an object store's listed
   object is atomic ⇒ always `READY`; a local connector with a `ready_marker` answers `READY` iff the
   sibling marker exists.
2. Only on `UNKNOWN` does the gate apply size/mtime **quiescence**: released once the file has been
   unmodified for at least `window` **and** observed at the same size on at least `size_checks`
   consecutive cycles.

⚠ The stability key is **`size_checks`** (`PipelineConfigParser.java:422`; `sizeChecks()` is merely the
record accessor) — `framework.md:48` gives `stability.sizeChecks`, which is wrong.

| `collector.stability` key | Default when the block is present |
|---|---|
| `window` | `30s` |
| `size_checks` | `2` (floored at 1) |
| `ready_marker` | *none* — template with `{name}`; a template without `{name}` is treated as a suffix (`ReadyMarker.java`) |
| `exclude_temp_files` | `true` |
| `exclude_temp_patterns[]` | `*.tmp`, `*.partial`, `*.filepart`, `.~lock.*` (`PipelineConfig.java:769-770`) |

An **absent** `stability:` block is `Stability.DISABLED` — the legacy "a matched file is a candidate at
once" behaviour, nothing stat'd.

Two properties worth not breaking. **Idempotence under repeated evaluation:** release is gated on
wall-clock quiescence (`now - mtime >= window`), so it crosses the threshold only as real time advances —
never merely because `filter` was called again. That is what lets the read-only `countPending` scan and the
real poll cycle evaluate the same files without one consuming the other's progress. **I/O discipline:** the
gate stats a file only when gating is enabled *and* the connector said `UNKNOWN`; when the remote LIST
already carried size + mtime those are reused and no extra round-trip is made. One `shared()` gate **per
space** (keyed on `EventLog.currentSpaceId()`), so two spaces' sightings never collide.

### 3.5 Phase C — duplicate prevention, change policy, watermarks

The fingerprint repository is the `AcquisitionLedger` SPI
(`inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedger.java`), keyed `(sourceId,
relativePath)`. Unlike the append-only OI stores it **upserts** — a file's latest fingerprint replaces the
prior one for its key. Four operations: `find`, `record`, `highWatermark(sourceId)` (file-level, Phase C4),
`dbWatermark`/advance (row-level, DB export). `LedgerEntry` carries `sourceId, relativePath, name, size,
checksum, etag, version, lastModified, processedAt, status` (`LedgerEntry.java:16-18`).

Implementations: `InMemoryAcquisitionLedger` (**default — lost on restart**) and `DbAcquisitionLedger`
(durable JDBC over its own DuckDB file, single-writer, or a Postgres URL), selected once from
`-Dacquire.ledger.backend` (`memory` default | `db`, `AcquisitionLedgers.java:142`). Tables:
`inspecto_acquisition_ledger` and `inspecto_acquisition_db_watermark`
(`DbAcquisitionLedger.java:33,36`). `AcquisitionLedgers` is the per-space `shared()` accessor + lifecycle
manager — one of the five MDC-routed singletons.

🔴 **`DuplicatePolicy` is TWO orthogonal enums, not one list.** `framework.md:53` states the modes as
"PATH / METADATA / CHECKSUM / SKIP / REPROCESS / VERSION / FAIL", which conflates the detection axis with
the change axis and imports four tokens from the requirement doc's §4 wish-list that the code never
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
defaults when the block is present (`PipelineConfigParser.java:411-414`). `algorithm` feeds `Checksums`,
which is JDK-only (`MessageDigest`/`CRC32`), streams in 64 KB chunks, returns lowercase hex, and accepts
`MD5`, `SHA1`, `SHA256` (default), `SHA512`, `CRC32` with or without the dash.

**Collection guarantee.** `collector.guarantee` → `BEST_EFFORT` (default) | `AT_LEAST_ONCE` |
`EXACTLY_ONCE` (`PipelineConfig.java:662-677`; both dash and underscore spellings parse). `requiresLedger()`
is true for anything but best-effort. ⚠ **The parser refuses to over-promise silently:** a
ledger-requiring guarantee combined with `duplicate.mode: path` (marker-only, which never populates the
ledger) logs a `[CONFIG]` warning saying it will behave as best-effort + commit-log replay and naming the
three modes that would enforce it (`PipelineConfigParser.java:445-448`).

**File-level watermark (C4).** `collector.incremental.watermark: last_modified` makes each scan skip files
modified strictly before the source's high-watermark — the greatest `lastModified` the ledger holds for that
source. It is **derived from the ledger, with no separate watermark column**, hence only meaningful in a
content-based mode; the parser warns on `incremental` + `mode: path` for exactly that reason
(`:500-504`). Metric: `inspecto_watermark_skipped_total`.

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
silently ignored (`PipelineConfigParser.java:233-237`). The pre-move `processing.batch:` spelling is
dual-read, canonical-first, and nothing writes it any more (`:213-224`).

### 3.6 Phase D — gap detection

`GapDetector` (`inspecto-acquire/src/main/java/com/gamma/acquire/GapDetector.java`) is pure decision logic:
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

### 3.7 Phase E — retrieval: plan once, stage, then land

**The retrieval plan writes bytes at most once.** `RetrievalPlanner` chooses per file
(`RetrievalPlan.Mode`): a configured backup/archive ⇒ `FETCH_TO_BACKUP` (write the bytes *directly* into
the backup destination and read from there — never temp, read, then move, which copies every byte twice);
no backup and the connector can `STREAM` (and nothing downstream needs a seekable local copy) ⇒ `STREAM`
(zero local bytes); otherwise ⇒ `STAGE_TEMP`. `destination` is `null` only for `STREAM`.

**Stage, then land (B3).** A connector writes to the destination `RemoteAcquisitionHandler` hands it, and
that destination is **never the inbox**. Fetches go to `collector.fetch.staging_dir` (default
`<dirs.temp>/acquire`); only once complete and integrity-verified is the file **atomically renamed** into
`dirs.poll`. Backup is a separate, later, post-commit step (`ConsignmentIngestor.backupFile`), not where fetch
lands. Three properties depend on this:

- **A partial download is never ingestible.** `fetchTo` *resumes by appending to its destination*, so if
  that destination were the inbox a half-downloaded file would sit there under its final name. Since B3b
  split the timers and guards, staging is the **only** thing making this safe — a fetch in flight is no
  longer shielded by a shared run-guard claim.
- **Resume still works**, because the staging path is deterministic (`<staging>/<relativePath>`), not a UUID
  temp name: the next attempt finds the partial and continues it. `SftpConnector.fetchTo` compares the
  destination's size to the remote length to pick the offset.
- **A corrupt download never enters the inbox** — it is dead-lettered to quarantine straight from staging.

⛔ `staging_dir` **must be outside `dirs.poll`** — refused at fetch time if not, because that would defeat
the whole mechanism — and should be on the same filesystem, or the rename degrades to a non-atomic copy and
warns. The ordering is **land-then-ack**: a source-side `post` action that deletes the remote original runs
only *after* the local copy is durably in the inbox.

**Integrity (§11).** `IntegrityChecker` applies two independent, cheap-to-skip checks to the staged copy:
*size* (staged bytes == the listing's `RemoteFile.size()`, skipped on `SIZE_UNKNOWN`, since some FTP servers
omit it) and *checksum* (only when the listing exposes a hash via `RemoteFile.etag()`, hashed with the
matching `Checksums` algorithm). Skipped when no etag exists — plain SFTP/FTP expose none — so it never
forces a wasted read. Pure verification: no events, no metrics; the caller decides.

**Fetch tuning.** `collector.fetch: { mode: STAGE, staging_dir: …, parallel_fetch: 1, rate_limit: … }`.
`parallel_fetch` is floored at 1. `rate_limit` parses to **bytes per second** via `parseRate`
(`PipelineConfigParser.java:1438-1456`): a size with an optional `/s`, `ps` or `/sec` suffix — `50MBps`,
`50MB/s`, `1GBps`, `512KBps`, or a bare number; KB/MB/GB are **binary (1024-based)**; null/blank ⇒ 0 =
unlimited. `RateLimiter` is a token bucket holding at most one second's burst, shared across all parallel
fetch workers so the *whole source* is bounded; an `acquire` larger than the burst waits proportionally
rather than deadlocking; clock and sleep are injectable.

### 3.8 Phase F — retry, circuit breaker, post-action

`RetryPolicy` (`inspecto-acquire/src/main/java/com/gamma/acquire/retry/RetryPolicy.java`) wraps the
connectivity-sensitive connector calls — `discover` and per-file `fetchTo` — so a flaky endpoint gets
bounded retries instead of failing the whole cycle on the first hiccup. The base delay before the *n*th
retry grows from `initial_delay` toward `max_delay` on the configured curve, is clamped to `max_delay`, and
is then **full-jittered** — the actual sleep is uniform in `[0, base]` — to avoid a thundering herd of
pollers retrying in lockstep. Sleep and jitter source are injectable; `RetryPolicy.NONE` performs exactly
one attempt. Config `collector.retry: { count: 0, backoff: EXPONENTIAL, initial_delay: 1s, max_delay: 60s }`
(EXPONENTIAL/FIXED); an absent block is a single attempt.

`CircuitBreaker` (`inspecto-acquire/src/main/java/com/gamma/acquire/CircuitBreaker.java`) is **per-source**,
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
archive_path: …, tags: {…}, on_unsupported: WARN_AND_CONTINUE }` — `on_unsupported` is the §9 policy for
"delete requested on a read-only source"; a post-action that fails at runtime bumps
`inspecto_post_actions_failed_total` and **the file is still ingested**.

### 3.9 The connector SPI, and the ten resolvable schemes

⚠ **"Eight connectors" is the count of the optional module, not of the registry.** Verified from `META-INF/services/com.gamma.acquire.CollectorConnectorFactory`: **eight** factories ship in `inspecto-connectors` (`sftp`, `ftp`, `ftps`, `db`, `s3`, `kafka`, `azure`, `gcs`), **one** more in `inspecto-engine` (`DatasetCollectorConnectorFactory` → `connector: dataset`, UI-S7), and `local` is the built-in default that needs no jar. So ten values resolve, and a bundle without the sidecar resolves only `local` and `dataset`. *(A ninth entry exists under `inspecto/src/test/resources` — `FakeRemoteConnectorFactory` — and is test-only; do not count it.)*

Three interfaces, all in the core (`inspecto-acquire`, package `com.gamma.acquire`), implemented in the
optional `inspecto-connectors` jar:

- **`CollectorConnector`** — `discover`, `readiness`, `open` (stream bytes), `fetchTo` (materialise at the
  destination it is *given*), `post` (RETAIN/DELETE/MOVE/RENAME/TAG), plus a `Capability` enum:
  `STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG, VERSIONING`
  (`CollectorConnector.java:82-92`). *(Renamed from `SourceConnector` per the Source→Collector glossary <!-- vocab-allow: names the SourceConnector rename itself -->
  flip.)*
- **`CollectorConnectorFactory`** — `scheme()` + `create(cfg, profile)` + the optional `workbench(profile)`
  hook, registered via `META-INF/services`.
- **`ConnectionWorkbench`** — the graded probe / explore / sample SPI; an implementation holds one session
  per instance, like a connector.

Eight schemes are registered by `inspecto-connectors`
(`inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory`):

| Class | Scheme | Library |
|---|---|---|
| `SftpConnector` | `sftp` | sshj + BouncyCastle |
| `FtpConnector` | `ftp` / `ftps` (two factories, one client class) | Apache commons-net |
| `DbExportConnector` | `db` | JDBC (PostgreSQL driver) |
| `S3Connector` + `AwsSigV4` | `s3` | **SDK-free** — raw REST on the JDK `HttpClient` |
| `AzureBlobConnector` + `AzureSharedKey` | `azure` | **SDK-free** |
| `GcsConnector` + `GcpServiceAccountToken` | `gcs` | **SDK-free** (gson for JSON) |
| `KafkaConnector` | `kafka` | kafka-clients |

⚠ Two precisions the sources get wrong in opposite directions. (a) `connectors-runbook.md` (its opening paragraph) and
`modules/connectors.md:16` once called S3/GCS/Azure "future"; they ship — the current text of both files is
now correct, and **NFS/SMB is a *declined* design, not a pending one** (no in-process client by intent; the
config safety validator rejects UNC paths at the path jail — that is the security boundary). (b) "Eight
schemes" is exactly right *for `inspecto-connectors`*, but the SPI has a **ninth registrant elsewhere**:
`inspecto-engine` registers `com.gamma.inspector.DatasetCollectorConnectorFactory`, `scheme() == "dataset"`
(`DatasetCollectorConnectorFactory.java:91`), which consumes another store's parquet snapshots. It is
parsed with two fail-closed refusals: `connector: dataset` without `collector.dataset` is refused, and
`collector.dataset` without `connector: dataset` is refused rather than silently ignored
(`PipelineConfigParser.java:397-403`); a `datasets/<id>` ref is normalised to the bare id.

🔴 **Its `post` action is FORCED to `RETAIN`, whatever the config says** — `post()` passes
`PostAction.RETAIN` to the delegate unconditionally
(`DatasetCollectorConnectorFactory.java:105-108`, whose own comment reads *"Retain is the only honest
post-action here, whatever the config says"*). ⛔ **A consumer can never delete, move or rename a
producer's snapshots.** A safety property that overrides authored config must be documented, or an operator
authoring `post: delete` will believe it took effect. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*

**Why this needs no watermark, and why an earlier watermark-runner design was superseded.** Snapshots are
**timestamp-named**, and the poll inbox is the pipeline's own `dirs.poll`, so the **existing marker dedup
already gives correct re-ingest-on-refresh semantics** — parquet was only ever missing an *ingest* lane, not
a new consumption mechanism, and no parser fence was needed. ⛔ Do not build a watermark here on the
assumption that one is missing.

⚠ **There is no "split acquire/ingest timers" scheduler, and a docs simplification once implied one.** The
closest primitives are the collector poll loop, the stability gate and the high-watermark filter — all of
which gate **discovery**, not mid-graph Steps. The durable inbox is `dirs.poll` itself, and a drain is
simply the next poll.

**Object storage is deliberately SDK-free (ACQ-4).** All three hand-roll their cloud's auth on plain JDK
crypto (`javax.crypto.Mac`, `MessageDigest`, `java.security.Signature`) over `java.net.http.HttpClient` —
no cloud SDK jar anywhere, keeping the SBOM small and the build air-gappable. Each maps a profile the same
way: `base_path` = `bucket-or-container[/prefix]`, `password` = a `SecretResolver` reference resolved per
use and never logged, `host`/`port`/`options.protocol` override the endpoint (MinIO / Azurite / tests). All
advertise `STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG`; MOVE/RENAME are copy+delete
(object stores have no rename); listings carry each object's ETag onto `RemoteFile.etag()` for ACQ-7 dedup;
a listed object is atomic ⇒ `readiness` is always `READY`. `s3` covers AWS S3, MinIO and **GCS in
interoperability mode** (S3-compatible XML API + HMAC keys), path-style addressing, `options.region`
default `us-east-1`. `gcs` is the distinct *native* path: the GCS JSON API with a service-account OAuth 2.0
bearer token (RS256 JWT assertion over a PKCS#8 key from the SA JSON, exchanged at the SA's `token_uri`,
cached until ~60 s before expiry — one mint per scan cycle); `Objects:list` pagination via `nextPageToken`,
object `generation` → `RemoteFile.version`, TAG = a custom-object-metadata PATCH. Azure: List Blobs
pagination via `NextMarker`, Range-resume fetch, MOVE/RENAME = Copy Blob + Delete **guarded on
`x-ms-copy-status: success`** so a pending copy never deletes the source.

**`db` and `kafka` are the "virtual slice file" idiom.** `db` runs a date-templated JDBC query and
materialises the result as CSV; `kafka` drains a partition's unconsumed backlog per cycle into
`<topic>-p<partition>-<from>-<to>.<ext>`, which flows through the normal batch path — **no core-engine
change**. Kafka offsets are **not** a broker consumer group: the connector `assign()`+`seek()`s and the
consumed frontier rides the ledger watermark, persisted only after the batch commits (at-least-once; a crash
mid-ingest re-drains the slice rather than skipping it).

### 3.10 Connection profiles, secrets, and the SEC-07 gate

Reachability and credentials live in a reusable `<name>_connection.toon` (`ConnectionProfile`), referenced
by one or more pipelines through `collector.connection`. Fields: `id`, `connector`, `host`, `port`,
`base_path`, `username`, `password`, an `options` map, an optional `tunnel` sub-block, and an optional
`proxy` sub-block (`type` HTTP|SOCKS5, `host`, `port`, `username`, `password`). ⛔ No `#` comment lines in
any `ConfigCodec` file — JToon rejects them.

⚠ **`base_path` has two spellings on purpose and `fromMap` accepts both.** Persisted forms (every
`*_connection.toon`, `toBundleMap()`) use snake_case `base_path`; the JSON API `toMap()` the SPA consumes
uses camelCase `basePath`, so neither can be unified away. Until the 2026-07-25 fix `toMap()` wrote
`basePath` while `fromMap` read only `base_path`, so `fromMap(toMap(p))` **silently dropped the path** — a
latent data-loss bug for every caller round-tripping a profile through its own map view.
`ConnectionProfileTest.everyFieldSurvivesAMapRoundTrip` asserts whole-record equality and is the guard
against a repeat. Round-trip equality holds only for `${…}` *references*: masking (`toMap`) and omitting
(`toBundleMap`) a literal is deliberate loss.

**Secrets are never literals.** `SecretResolver`
(`inspecto-acquire/src/main/java/com/gamma/acquire/SecretResolver.java`) expands **five** forms at connect
time, never at load — and `isResolvable()` answers the same question for a connection test without
exposing the value:

| Form | Resolves to |
|---|---|
| `${ENV:VAR}` | the environment variable |
| `${SYS:prop}` | the JVM system property |
| `${FILE:/path}` | a mounted secret file (Docker/K8s idiom; one trailing newline stripped) |
| `${KEYSTORE:alias}` | a `SecretKeyEntry` from the store at `-Dsecrets.keystore.path` / `.type` (default `JCEKS`) / `.password` (itself a reference, so the store password need not be in the clear) |
| `${NAME}` | environment `NAME`, falling back to system property `NAME` |

Anything not matching `${…}` is returned unchanged (a tolerated-but-discouraged literal), and a
resolved value is **never logged**.

⚠ **SEC-07 (2026-09-06): `${FILE}` and `${KEYSTORE}` are Standard + Enterprise only.** They are served by
the `SecretsProvider` SPI in the core, whose implementation is `inspecto-security`'s
`FileKeystoreSecretsProvider`, discovered by ServiceLoader
(`inspecto-security/src/main/resources/META-INF/services/com.gamma.acquire.SecretsProvider`). A bundle
without that module — Personal — **refuses the scheme with an `IllegalStateException` naming the edition,
never a silent `null`**; a connection test surfaces that refusal as its failure. A Vault/KMS scope is the
Enterprise follow-on. ⚠ `connectors-runbook.md` (formerly `integrations.md`) once listed only 2 of the 5 forms and omitted this gate; its
current text is correct — prefer it over any older account.

`ConnectionRegistry` bridges the service layer (which owns the toon files) to the static poll-cycle path,
keyed per `(spaceId, profileId)`. Only `ConnectionRegistry.find` is consulted at `forConfig` time; a
missing profile yields `null` and the factory decides.

### 3.11 Host-key policy

`HostKeyPolicy` (`inspecto-connectors/src/main/java/com/gamma/acquire/connectors/HostKeyPolicy.java`) is
derived from the profile's `options` (`:45-51`). Three knobs, purely additive — with none of them set the
legacy accept-on-connect behaviour is unchanged (and logged at debug, `:79`):

| Option | Effect |
|---|---|
| `host_key` | 🔴 a key **FINGERPRINT** — `SHA256:<base64>` or OpenSSH MD5 colon-hex. The field is literally named `fingerprint` (`:35`) and is handed to sshj's `client.addHostKeyVerifier(fingerprint)` (`:70-71`), so a **raw `ssh-rsa AAAA…` key line cannot work**. `connectors-runbook.md`'s host-key table is right and any doc showing a raw key line is wrong — `docs/FEATURE_INVENTORY.md:147` is one such. |
| `known_hosts` | path to an OpenSSH `known_hosts` file; the host must have an entry. Works across hops. |
| `strict_host_key: true` | when set and neither of the above is configured, **refuse to connect** (`:75`) rather than silently accept any key. |

Over a bastion, `HostKeyPolicy.bastionView` (`:60-61`) encodes the asymmetry: a single `host_key`
fingerprint matches **one** host, so it pins the *target* and does not apply to the bastion; `known_hosts`
can verify both hops. For a `db`/`ftp`/`ftps` profile reached through a tunnel the bastion is the only SSH
hop, so `host_key`/`known_hosts` pin **it** (`DbConnections.java:51`, `FtpConnector.java:256`).

### 3.12 Tunnels and proxy dial-through

**SSH bastion.** `SshTunnel` is an `AutoCloseable` SSH TCP-forward honoured by `sftp`, `db`, `ftp` and
`ftps` via the profile's `tunnel:` block. FTP is the special case: it opens a **control** connection *and*
separate **passive data** connections, so the bastion must carry both — set `options.passive_ports` to the
range the server advertises (`PassivePorts`); each port is forwarded loopback→server and the client is told
to dial the loopback. Active mode cannot traverse a tunnel, so a tunnelled FTP connection is always passive.
⚠ Tunnel FTP *without* `passive_ports` and only the control channel is forwarded — transfers fail unless the
server's passive ports happen to be independently reachable (the connector logs a warning).

**Proxy dial-through** shipped in four steps, and the precedence rule is the same in all of them: a proxy is
**ignored when an SSH `tunnel:` is also configured**, because the tunnel already rewrites the dial target to
a local loopback forward.

- *2026-07-20, SOCKS5, `SftpConnector`:* `SocksProxySocketFactory` is a `javax.net.SocketFactory` wrapping a
  `java.net.Proxy`. sshj's `SocketClient.connect(host, port)` already calls `socketFactory.createSocket()`
  then `socket.connect(target, timeout)` on the result, so a plain JDK socket built on a SOCKS `Proxy`
  tunnels transparently with no handshake of our own.
- *2026-07-24, FTP/FTPS:* `FtpConnector.applyProxy` hands the same factory to commons-net's
  `FTPClient.setSocketFactory`; covers both schemes since they share one client class (FTPS just layers TLS
  on the already-proxied socket).
- *2026-08-13, `HTTP` type:* previously refused fail-closed. `HttpProxySocketFactory` now dials the proxy,
  sends `CONNECT host:port HTTP/1.1` (plus `Proxy-Authorization: Basic` when the profile carries proxy
  credentials), requires a `200`, drains the headers, then hands the raw socket over. 🔴 **The redirect must
  live in the socket's own `connect()`, not in the factory's connecting overloads** — sshj takes the
  *unconnected* socket from the no-arg `createSocket()` and calls `connect(target)` itself, so a factory
  that only tunnelled in `createSocket(host, port)` is silently bypassed and dials the target **directly**.
  The first implementation did exactly that, and the failure was invisible in the happy path: `discover()`
  still returned the right file while the relay recorded no CONNECT at all. Hence the inner
  `TunnellingSocket` overriding both `connect` arities.
- *2026-09-06, JDBC — PostgreSQL only:* `DbConnections.open` honours `ConnectionProfile.proxy` through the
  driver's own seam, `socketFactory=` + a single-string `socketFactoryArg=` (`host:port[:user:password]`,
  parsed once in `ProxyArg`). The driver instantiates the factory reflectively (both are public with a
  `(String)` constructor), takes the unconnected socket and calls `connect` itself — the same shape sshj
  uses, so the `TunnellingSocket` redirect covers it unchanged.

⛔ Fail-closed on what cannot be routed: an unrecognised proxy `type` (the message names both supported
types), a driver other than PostgreSQL (DuckDB is embedded; an explicit `jdbc_url` for anything else has no
hook), and a proxy **combined with** an SSH tunnel on the JDBC path — the JDBC socket would then dial the
tunnel's local endpoint, which a proxy must not carry, and the SSH hop itself is not proxied.

### 3.13 The control surface

| Route | Notes |
|---|---|
| `GET /collectors` | flat view of every pipeline's acquisition config (`AcquisitionRoutes.java:23`) |
| `POST /collectors/{id}/notify` | ACQ-6 push discovery; `canOperateRuns`; `202`+`Location` under v1; audited `collector.notified` |
| `GET /metrics/acquisition` | JSON snapshot of the nine acquisition metrics, complementing the text-only Prometheus `/metrics` |
| `GET /connections`, `GET /connections/{id}` | profiles, **secret-masked** |
| `POST`/`PUT`/`DELETE /connections[/{id}]` | ⚠ writes require **`canOnboardConnections`** — its own Admin-only grant, deliberately *not* `canAuthorWorkbench`, because Connections are the credential/egress surface (`ConnectionRoutes.java:35-55`). None of the six sources documents these three write routes. |
| `POST /connections/{id}/test` | TCP reachability + latency + secret **resolvability**; `404` unknown id; `422` on `?target=tunnel\|proxy` with none configured, or an unsupported target |
| `POST /connections/test` | probes an **unsaved** profile from the create/edit form; `?target=connection\|tunnel\|proxy` picks the hop |
| `POST /connections/{id}/probe` · `GET …/explore?path=` · `GET …/sample?path=&limit=` | the graded workbench. Gates: `404` unknown id · `422` unknown check name · `400` missing sample path · **`403` path escape (jail)** · `404` unknown path · `501` connector without workbench support · `502` protocol failure. Read-only, no capability gate — same as `/test`. |

**Probe orchestration** (`ConnectionProber`): reachability and `secretsResolved` are answered generically by
`ConnectionTester`; the graded checks (authenticate / read / write / list) go to the profile's
`ConnectionWorkbench`. Unreachable ⇒ graded checks *skipped* ("not attempted"); no workbench ⇒ *skipped*
("not supported by the '<scheme>' connector yet"). 🔴 **A probe never fabricates a check it did not run.**

Workbench implementations and their two standing disciplines: `LocalConnectionWorkbench` (built-in; jailed
under `base_path`; WRITE = scratch write + delete; sample via `FileSampler` = the production DuckDB readers
on a throwaway scratch DB with a raw-line fallback) · `AbstractRemoteWorkbench` + nested classes for
SFTP/FTP/FTPS · `DbConnectionWorkbench` (a schema tree, not a directory tree: explore walks
`schema → table → column`; sample is `SELECT *` with a vendor-neutral `setMaxRows(limit+1)` over the
identifier-quoted `schema.table`, so a crafted name cannot break out) · `AbstractObjectStoreWorkbench` for
s3/gcs/azure (delimiter-based single-level listing; common prefixes become pseudo-directories) ·
`KafkaConnectionWorkbench` (browses the whole broker, `topic → partition`; sample assigns+seeks a throwaway
consumer — critically it **never touches the acquisition ledger watermark**, so a sample can neither disturb
nor be disturbed by the real ingest frontier).

The two disciplines: **remote sample fetches the whole file only when the listed size is ≤ 8 MiB**
(`SAMPLE_FETCH_CAP`) — larger or size-less files are refused with an honest detail, because an FTP data
stream cannot be abandoned mid-transfer safely (`completePendingCommand`); and **WRITE is always *skipped*
for DB, object stores and Kafka** — a workbench never mutates a database, writes a scratch object into
someone's production bucket, or produces a probe record onto a topic to prove write access. Remote paths are
jailed segment-wise (no absolute paths, no `..` above the base) by the shared `AbstractRemoteWorkbench.jail`.

### 3.14 Observability

Events (`inspecto-event/.../EventType.java:61-83`), all emitted through `AcquisitionTelemetry` with
`CollectorProcessor`'s FQN as the event `source` so the stream stayed byte-identical when the emitters moved
out: `FILE_DISCOVERED` · `FILE_STABLE` · `FILE_FETCHED` (carries `bytes`) · `FILE_VALIDATED` ·
`FILE_FETCH_FAILED` · `FILE_QUARANTINED` · `FILE_CHANGED` · `FILE_ARCHIVED` (carries `action`) ·
`SOURCE_CIRCUIT_OPEN` · `SEQUENCE_GAP` (carries `expected`, `sequence`, `unit`).

Metrics, all labelled by pipeline: `inspecto_files_discovered_total` · `inspecto_files_downloaded_total` ·
`inspecto_downloads_failed_total` · `inspecto_duplicates_skipped_total` · `inspecto_watermark_skipped_total`
· `inspecto_post_actions_failed_total` · `inspecto_bytes_transferred_total` · `inspecto_fetch_seconds`
(histogram) · `inspecto_active_connections` (gauge) · `inspecto_files_waiting_stability` (gauge) ·
`inspecto_acquire_backpressure_skips_total`. `AcquisitionTelemetry` methods are pure leaves — each only
emits an event or bumps a metric, carrying no acquisition logic.

### 3.15 Network shares — the declined design, recorded once

⛔ **NFS/SMB/CIFS is a declined design, not a gap.** The engine deliberately has **no** in-process SMB/NFS
client, and the config safety validator **rejects UNC paths** (`\\server\share`) at the path jail — that is
the security boundary. To collect from a share: mount it at the OS level (Windows
`net use X: \\server\share /persistent:yes`; Linux `mount -t nfs|cifs … /mnt/share`), add the mount point to
the jail via `-Dassist.safety.roots=<existing roots>;X:\` (a `;`-separated list), and point `dirs.poll` (or
`base_path`) at the mounted path. The built-in `local` connector then handles discovery / stability / dedup
identically to a local inbox, and credentials, reconnection and caching stay the OS's job, where they are
audited and battle-tested.

### 3.16 Defaults worth not "fixing"

Recorded because both source files agree on them and a merge could plausibly "correct" one: ledger backend
**`memory`** · staging dir **`<dirs.temp>/acquire`** · back-pressure high-water **0 (off)** · S3 region
**`us-east-1`** · duplicate mode **`path`** · guarantee **`BEST_EFFORT`** · post-action **`RETAIN`** ·
`on_unsupported` **`WARN_AND_CONTINUE`** · discovery **`poll`** · `recursive_depth` **`-1`** ·
consignment `order` **`mtime`** · stability `window` **`30s`** / `size_checks` **`2`** · retry
`initial_delay` **`1s`** / `max_delay` **`60s`** / backoff **`EXPONENTIAL`** · breaker `failure_threshold`
**`5`** / `cooldown` **`5m`** · `parallel_fetch` **`1`** · `rate_limit` **0 = unlimited**.

### 3.17 Still future (declared, so it is not re-proposed as new)

A presigned-URL / STS credential mode for `s3`; a Vault/KMS `SecretResolver` scope; `acquire.maxFilesPerCycle`
(bounding one cycle's fetch volume — `BACKLOG.md` §6). The SPI makes each non-disruptive.

⚠ Do not restore a **`4.x`** attribution for phases A–F. That branch and its `v4.0.0`/`v4.0.0-RC1` tags were
deleted 2026-08-17 (`docs/BRANCHING.md` §0-A); the phases shipped on `master`. Both
`data-acquisition-framework.md:13` and `acquisition/index.md` carried the wrong attribution.

---

## 4. Decisions

Dated, one line each, with the reason — because the reason is what stops a decision being re-litigated.
Only decisions that still bind are listed; where one reversed an earlier one, both appear.

### Vocabulary

| Date | Decision | Why |
|---|---|---|
| 2026-07-14 | *Source* → **Collector** (and not *Poller*) for the acquisition entity | "Source" collided with the data-origin sense; "Poller" would have excluded push inputs. Rollout is DONE end-to-end — routes `/sources`→`/collectors`, the TOON block `source:`→`collector:`. Residual by design: the lineage attribute key, `Event.source()`, and `NodeCategory.SOURCE`. `GLOSSARY.md` §13 | <!-- vocab-allow: records the Source->Collector decision -->
| 2026-07-14 | *Data Source* → **Stream** + **Reference** | Two different natures of origin were wearing one name. Acquisition config is unaffected: it stays Connection + Collector | <!-- vocab-allow: records the Data Source->Stream/Reference decision -->

### Architecture & packaging

| Date | Decision | Why |
|---|---|---|
| 2026-08-11 | `PipelineConfig.collector` is **singular, permanently** | Every stateful subsystem — ledger, watermark, dedup, gap detection — is keyed on one collector id. A list would force a ledger re-key, which is a migration, not a feature |
| 2026-09-07 | The connector sidecar is **NOT edition-gated** | Remote acquisition is core, not a paid tier. `inspecto-connectors.jar` rides every bundle; the ~32 MB was accepted deliberately (CONNECTORS-BUNDLE-1) |
| 2026-09-06 | **SEC-07** — `${FILE}` / `${KEYSTORE}` are Standard + Enterprise; the SPI core stays in `inspecto-acquire` | Keeps the lean core free of a secrets client while letting Personal refuse the scheme **by name** rather than failing obscurely. Vault deferred to policy, not code |
| 2026-06-24 → reversed | Connections as a standalone `inspecto-connect` repo with a cross-repo `@PublicApi` | See §6.2 — reversed in fact, and this spec is where that reversal is finally recorded |

### Connectivity, probe & secrets

| Date | Decision | Why |
|---|---|---|
| 2026-07-08 | Every remote connector is **SDK-free** — SigV4 for `s3`, SharedKey for `azure`, and (2026-07-22) a native JSON API + RS256 service-account JWT for `gcs` | A small SBOM is a FedRAMP asset; three cloud SDKs would have dwarfed the whole artifact |
| 2026-07-08 | `kafka` uses `assign()`+`seek()` with **no consumer group** | The consumed frontier rides the acquisition ledger watermark instead, so replay and crash-recovery reuse machinery that already exists and is already tested |
| 2026-08-13 | HTTP `CONNECT` proxy dial-through **shipped, reversing an earlier fail-closed rejection** | The rejection assumed a JDK socket could tunnel transparently. It cannot, so the tunnel had to be explicit — the earlier "no" was based on a wrong premise, not a policy |
| 2026-09-06 | JDBC dial-through is **PostgreSQL only**, fail-closed elsewhere | Each driver's socket-factory hook differs; a generic claim would have been untestable |
| 2026-07-18 | The connection **probe is graded** (`REACHABILITY` → `AUTHENTICATE` → `READ` → `WRITE` → `LIST`) and WRITE is `skipped` for every remote scheme | A workbench must never write a scratch object into a customer system. Only `local` does write-and-delete. See §6.5 |

### Poll cycle, consignments, dedup

| Date | Decision | Why |
|---|---|---|
| 2026-08-12 | Consignment ordering defaults to **`mtime`** (arrival), not filename | Filename order is a guess about someone else's naming; arrival order is a fact the filesystem already knows |
| 2026-09-02 | The batch knobs live at **`collector.consignment.{max_files,max_bytes,order}`** | The planner runs before any sink exists, so the knobs cannot hang off the sink (CONSIGNMENT-HOME-1) |
| 2026-09-01 | Dedup keys are **SHA-256 hashed** in the ledger | A ledger is an operational artifact that gets exported and read; raw paths leak customer structure |
| 2026-08-05 | `file_dedup` is a **Guarantee** (file grain); `dedup` is a **Step** (record grain) | One word was doing two jobs at two grains — the split is why either can be reasoned about |
| 2026-09-06 | The unpack verdict reports at **archive level** with **entry-grain** detail | An operator asks "did that archive land?" and then "which member failed?" — one answer cannot serve both |

🔴 **One gap in the decision record, stated rather than papered over: there is no dated decision anywhere
in the current tier for the stability gate** — the mechanism that decides a remote file has stopped
growing. It is the single most consequential piece of acquisition (get it wrong and you ingest a
half-written file), it is fully built and documented as *mechanism* in §3.4, and *why* its thresholds are
what they are is recorded nowhere. ⛔ Do not invent one. It needs an operator to state the intent, and
until then §3.4's defaults are as-built facts with no recorded rationale.

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Partial — the capability exists, the acquisition-node path is unproven

| Item | Board id | What remains |
|---|---|---|
| S3 / GCS object ingest | `SP-ACQ-06`, `SP-ACQ-08` | The connectors ship and are tested; there is **no proven end-to-end acquisition-node run**. ⚠ This is not a contradiction of `ACQ-4` — *a connector is not a Step* (§6.7) |
| Azure ADLS Gen2 | `SP-ACQ-07` | Blob works; the Gen2 hierarchical namespace is unproven |
| Kafka consumer-group-as-Collector | `SP-ACQ-09` | The per-scan drain ships; consumer-group semantics do not (by design — see §4) |
| Excel workbook fan-out | `SP-ACQ-05` | One sheet per file today; multi-sheet fan-out unbuilt |

### Planned — declared, unstarted

`SP-ACQ-10` … `SP-ACQ-20` — eleven 🔲 rows: Pulsar, Kinesis, SQS, RabbitMQ, MQTT, Debezium CDC, Delta,
Iceberg, Snowflake/BigQuery readers, a REST poller, a webhook receiver and gRPC. ⚠ They are *declared*
so they are not re-proposed as new ideas; none is scheduled.

### Open elsewhere on the board

| Item | Board id | What remains |
|---|---|---|
| Adapter stream-consumer runtime | `STREAM-CONSUMER-1` (P2, filed 2026-09-10 — until then this cell pointed at ROADMAP §3.4, i.e. at no board row) | The land-then-ack seam exists; the consumer loop does not |
| Outbound object-store export | `EXPORT-1` (P3) | The inverse direction of ACQ-4 — recommendation of record only |
| Vault / KMS secret provider | `GAP-6` | Deferred by the SEC-07 decision, not blocked |
| "Listed-not-yet-fetched" gauge | branch-aware residual **(e)** | Observability gap: the queue between list and fetch is invisible |
| `acquire.maxFilesPerCycle` | branch-aware residual **(f)** | A per-cycle intake cap; `collector.consignment.max_files` bounds the consignment, not the cycle |
| Multi-part archives | Unpack (11) | `depth: 1` is deliberate (§6.9); *multi-part* (`.z01`, split RAR) is simply unbuilt |
| Missing connection not caught until first poll | W5 | A Collector referencing an absent Connection saves cleanly and fails at runtime |
| Acquisition ledger defaults to `memory` | Completeness-KPI hold | 🔴 The default loses the ledger on restart, which is also why the completeness KPI cannot be computed on a fresh install |

### 🔴 UNTRACKED — no board row exists; file before scheduling

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> (none cross-cutting — every item here is area-specific).
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Where its design lives | Why it matters |
|---|---|---|
| Credentials & network profiles as **their own referenced resources** | `plans-archive/acquire-controller-service-design.md` §2.1 | The one untracked item with real design content and no home. Today a `tunnel` is **inline** on the Connection, so two Collectors through the same bastion duplicate it — and a rotation edits N places |
| Controller-service **VALID/INVALID lifecycle + run-gating** | same, §3 | Verified absent: validation gates *save* and *compile*, never *run*. A Collector whose Connection has gone bad still starts a run and fails inside it |
| `acquisition.list` / `acquisition.fetch` node split | same, §4 | Verified: `BuiltinNodeType.ACQUISITION` is the only acquisition node. ⚠ The SPI seam for the split already exists, so this is smaller than it reads |
| A "referencing components" read view | same | "What breaks if I change this Connection?" has no answer today |
| Presigned-URL / STS credentials for `s3` | `AwsSigV4.java:26` states the scope reason in-code | Long-lived keys are the only option today |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 An in-process NFS / SMB / CIFS client — REFUSED, and it is the security boundary

`ACQ-4` asked for "network-share (NFS/SMB) connectors on the connector SPI". **There is no share
connector and there will not be one.** The path validator **rejects UNC outright**
(`ConfigSafetyValidator.java:438` — "reject UNC/network paths"; `DataRef.java:40` bans the `\` separator
that `\\host\share` needs). That rejection is not a gap someone forgot to close: it is the boundary that
keeps an authored config from reaching an arbitrary host on the network.

**What serves the need instead:** mount the share at the OS level and point the built-in `local`
connector at it. Credential handling, caching and reconnection stay the operating system's job, where
they are already solved. `EDITIONS.md`'s `SP-ACQ-01` is titled "**Local/NFS** directory watcher —
`LocalFileSystemConnector`, the default collector" and reads `✅✅✅` — so NFS **is** served; what is
refused is an in-process protocol client.

⚠ **This is filed in two tiers and they disagree.** `ROADMAP.md` §3.2 still carries an explicit
"**Not delivered:** the NFS/SMB-CIFS half … there is no share connector", and §6 keeps the half open,
while `REQUIREMENTS.md` closes the tier as the mounted-share pattern. **The refusal wins, because the
code enforces it** — and retiring the ROADMAP wording is part of this spec's job, not a separate debate.
*(⚠ ROADMAP `T2` is flagged stale in its own §9, and ROADMAP entered the vocabulary guard's scope only
2026-09-08.)*

### 6.2 A standalone `inspecto-connect` repo with a cross-repo `@PublicApi` — REVERSED IN FACT, recorded nowhere until now

Decided 2026-06-24 (`plans-archive/brainstorm-tingly-storm.md`): build connections as their own
repository behind a stable cross-repo `@PublicApi`. **Verified against the tree: no such repo or module
exists.** What happened instead is `inspecto-acquire` (the SPI) plus the optional `inspecto-connectors`
(the implementations) — which delivers the same dependency isolation *without* the cross-repo
versioning cost the plan itself had flagged as its main risk.

The half that was **kept** is worth naming, because it is why the reversal was cheap: the UI-first
contract freeze. The connection contract was frozen from the consumer's side first, so moving the
implementation was a packaging decision rather than an interface renegotiation.

⚠ Residue: `@PublicApi(since = "4.0.0")` survives on two acquisition types and is now a **stale marker**
— `4.x` was deleted 2026-08-17.

### 6.3 NiFi's FlowFile session/queue runtime — REFUSED

Adopt NiFi's *authoring contracts*, not its *runtime*. A live inter-node queue would make the pipeline a
stateful cluster; the flow-graph decision is recorded as locked. The consequence to keep in mind when
reading any NiFi-shaped design doc: **"predecessors ran" means materialized output on disk, not a queue
handoff.**

### 6.4 Enriching `POST /components/connection/{id}/test` — SUPERSEDED

The controller-service design proposed extending that route. The UI's frozen
`/connections/{id}/probe | explore | sample` triad won, and the design predates the contract. Not a
rejection on merit — a document that aged past its subject.

### 6.5 A WRITE probe against a customer system — REFUSED

`WRITE` is deliberately `skipped` for `db`, `s3`, `gcs`, `azure` and `kafka`. A workbench must never
create a scratch object or publish a probe record into someone's production system. Only `local`
performs write-and-delete, where the blast radius is a temp file we own.

### 6.6 Edition-gating the connector sidecar — REFUSED

~32 MB, accepted. Remote acquisition is core capability, not a paid tier; gating it would make Personal
a demo rather than a product (2026-09-07).

### 6.7 "A connector is not a Step" — the standing answer to an apparent contradiction

`SP-ACQ-06` / `SP-ACQ-08` read 🟡 "no proven end-to-end acquisition-node run" while `ACQ-4` reads
FULLY CLOSED. Both are true and neither is stale: a **Connection kind** existing (tested connector,
real credentials, real listing) is a different claim from an **acquisition node** having been proven end
to end in a pipeline run. `BACKLOG.md` §5 already names this distinction; it is recorded here so the
pair is not "reconciled" by flattening one of them.

### 6.8 Other standing refusals, with their reasons

| Refused | Reason |
|---|---|
| A second Kafka lane for urgent topics | Urgency is a *parameter* on one node, not a second lane |
| Nested archives, and partial-archive failure | `depth: 1` is deliberate; a nested archive is an unbounded expansion in a jailed path |
| Crash-mid-archive exactly-once | Relies on `OVERWRITE_OR_IGNORE`; true exactly-once would need a per-entry ledger, which costs more than the failure |
| Fetch-lane fairness | Stays FIFO until a real fetch-lane wait is observed |
| Vault / KMS **now** | Client policy, not code — the SEC-07 seam is already in place for when it lands |
| `PathJail` for the watermark store | A deliberate LEAVE: the store is engine-owned, not author-supplied, so the jail would guard nothing |
| `/metrics/acquisition` as an edition-gated cell | Core and ungated — an operator on any edition must see whether data is arriving |
| Bounds-based window pruning | Measured 1.3–2.6× faster for 29–88× fewer rows — the win did not survive contact with real cardinality |

⚠ **Do not carry forward** `BACKLOG.md` §6's "the TOON `source:` block stays". It is refuted:
`PipelineConfigParser.java:218,376` read only `collector`, and no committed TOON carries a top-level
`source:`. Only `NodeCategory.SOURCE` survives, and that is an enum constant, not authored config.

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| Poll cycle, phases A–F, back-pressure | `docs/okf/backend/acquisition/framework.md` (`Concept`) | `inspecto-acquire/src/main/java/com/gamma/acquire` | the two-timer / two-guard split, the manual-run overlap gotcha, B4 vs `IntakeGovernor` |
| Connector SPI, 8 schemes, profiles, secrets, workbench, proxy chain | `docs/okf/backend/acquisition/connectors.md` (`Concept`) | `inspecto-connectors/src/main/java/com/gamma/acquire/connectors` | stage-then-land, SDK-free auth per store, the four dial-through ships, workbench disciplines |
| The 14 numbered requirement areas | `docs/okf/backend/acquisition/data-acquisition-framework.md` (`Reference`) | `inspecto-acquire/src/main/java/com/gamma/acquire` | requirement-of-record wording + the mounted-share security note |
| Operator runbook: profile YAML, host-key table, FTPS, bastion, db-export | `docs/okf/backend/acquisition/connectors-runbook.md` (`Reference`) | `inspecto-connectors/src/main/java/com/gamma/acquire/connectors` | the copy-pasteable profile shapes and the `curl` verification sequence |
| Module boundary + dependency confinement | `docs/okf/backend/modules/connectors.md` (`Module`) | `inspecto-connectors/` | why the jar is optional and what `ServiceLoader` gives you |
| Section map | `docs/okf/backend/acquisition/index.md` | *(none — index, exempt by charter)* | navigation only |

---

## 8. Verification

### 8.1 Decision-logic and ledger unit tests — `inspecto-acquire/src/test/java/com/gamma/acquire/`

22 classes. The acquisition core is deliberately built as pure, injectable-clock decision logic, so almost
every invariant in §3 is provable without a network or a sleep.

| Class | Proves |
|---|---|
| `StabilityGateTest` | growing-file hold, release on quiescence, the `size_checks` gate, per-source isolation, gate shared **per space** |
| `DuplicatePolicyTest` | `Decision{NEW,DUPLICATE,CHANGED}` across `Mode{PATH,METADATA,CHECKSUM,ETAG}` incl. the etag→version→metadata fallback; `OnChange` reprocess/alert flags; `configStringsMapToEnums()` (`:82`) pins the config-string→enum mapping and the null/unknown defaults |
| `AcquisitionLedgerTest` | the ledger contract across in-memory **and** DuckDB backends, pre-etag schema migration, and a failed replace rolling back to the prior fingerprint |
| `AcquisitionLedgerWatermarkTest` | `highWatermark` = max(`lastModified`) per source, in-memory + DB, and the safe no-op default |
| `AcquisitionLedgersTest` | ledgers isolated per space (MDC routing) |
| `ChecksumsTest` | known hash vectors, SHA-256 default + change detection, unknown-algorithm failure |
| `IntegrityCheckerTest` | post-fetch size/etag verification, skip-when-no-size, quoted-etag checksum |
| `GapDetectorTest` | timestamp-template series: hourly hole, day rollover, monthly step, invalid-date skip |
| `GapTrackerTest` | the fire-once/refire-on-reopen state machine, per-source isolation, reset |
| `FileSequenceGapsTest` | the two-token windowed variant: interior hole, trailing hours, truncated tail, zero-padding, malformed template |
| `CircuitBreakerTest` | closed→open→half-open, threshold trip, cooldown, per-source isolation |
| `retry/RetryPolicyTest` | `NONE` runs once, retry-until-success, exhaust-and-rethrow, exponential doubling + clamp, linear backoff |
| `RateLimiterTest` | unlimited short-circuit, burst-then-throttle, refill over elapsed time |
| `IntakeGovernorTest` | the T15 contrast: hot policy apply, overrun halves, floor, hysteresis band, per-pipeline override incl. zero-exempt, hard-cap mode |
| `RetrievalPlannerTest` | the plan choice — backup vs. streamable vs. temp-staging |
| `PostActionTest` | `archive_path` date-token resolution |
| `ConnectionProfileTest` | field/tunnel/options load, secret masking in `toMap`, proxy-block parse+mask, **`everyFieldSurvivesAMapRoundTrip`** (the `base_path`/`basePath` data-loss guard, §3.10) |
| `ConnectionTesterTest` | reachable/unreachable probe, local-profile no-network shortcut, missing host/port failure |
| `SecretResolverTest` | all five forms — `recognisesReferences` (`:16`), `resolvesSystemPropertyScope` (`:25`, incl. bare-scope fallback), `missingEnvReferenceResolvesToNull` (`:40`), `literalIsPassedThrough` (`:45`), **`fileAndKeystoreSchemesAreRefusedNamingTheEditionWhenNoProviderIsBundled`** (`:54` — the SEC-07 gate), `aBundledProviderServesTheSchemesItSupports` (`:73`) |
| `ConnectionRegistryTest` | register/find/remove by id, null-registration ignored, per-space isolation |
| `LocalConnectionWorkbenchTest` | probe/explore/sample, **path jailing**, CSV vs. raw-line fallback |
| `LocalFileSystemConnectorTest` | discover/open/fetchTo/post-action + ready-marker readiness |

### 8.2 Connector, workbench and proxy tests — `inspecto-connectors/src/test/java/com/gamma/acquire/connectors/`

17 acquisition test classes plus 2 relay fixtures. ⚠ The two `com/gamma/connect/notify/*DeliveryStatusAdapterTest`
classes also live in this module but are a **notification** subject, not ACQ.

- Per-scheme: `SftpConnectorTest` (host-key pinning, discover/fetch/resume, dedup-on-rerun, parallel fetch,
  workbench, SOCKS5 + HTTP-CONNECT tunnelling) · `FtpConnectorTest` (+ proxy, unknown-proxy fail-closed) ·
  `FtpsConnectorTest` (TLS mode parsing, `scheme()=="ftps"` at `:98`) · `FtpTunnelConnectorTest` (bastion +
  passive-port-range parsing) · `S3ConnectorTest` · `AzureBlobConnectorTest` · `GcsConnectorTest` (each:
  discover w/ pagination + etag, depth bound, fetch, move+delete post-actions) · `KafkaConnectorTest`
  (offset-range discover, **ledger-watermark resume**, NDJSON envelope drain, raw-payload mode,
  non-RETAIN post-action rejection) · `DbExportConnectorTest` (query→CSV, `:watermark` placeholder rewrite,
  incremental export **commits then advances**).
- Auth signing, which is what "SDK-free" rests on: `AwsSigV4Test` (vanilla vector, S3 sha256, RFC3986
  encoding) · `AzureSharedKeyTest` (string-to-sign, `x-ms-` headers, canonicalized resource).
- Workbenches: `S3ConnectionWorkbenchTest` · `AzureBlobConnectionWorkbenchTest` ·
  `GcsConnectionWorkbenchTest` (each an in-process JDK `HttpServer` stub per store) ·
  `DbConnectionWorkbenchTest` (schema/table/column explore, bounded sample + truncation flag) ·
  `KafkaConnectionWorkbenchTest` (topic→partition explore; **sample without touching the ledger
  watermark**).
- Proxy: `DbConnectionsProxyTest` — the JDBC half dialled through both relays at a bare listener, asserting
  the relay was asked for the database; unroutable shapes refused.
- Fixtures (not test classes): `MiniSocks5Relay.java`, `MiniHttpConnectRelay.java` — both multi-connection,
  because FTP opens a *second* passive data connection through the same factory; `MiniHttpConnectRelay`
  additionally records the auth header. 🔴 These relays are the only thing that caught the
  `createSocket()`-vs-`connect()` bypass of §3.12: the happy path still returned the right file.
- Test infrastructure declared in `inspecto-connectors/pom.xml`: Apache MINA `sshd-core` + `sshd-sftp` and
  Apache `ftpserver-core` (all `<scope>test</scope>`) give **real in-process SSH/SFTP and FTP servers**;
  kafka-clients' in-jar `MockConsumer` means the suite needs no broker; the one committed test resource is
  `inspecto-connectors/src/test/resources/ftps-test-keystore.jks`.

### 8.3 Control-plane real-HTTP route tests — `inspecto/src/test/java/com/gamma/control/`

Domain-primary (four):

| Class | Routes / gates |
|---|---|
| `ControlApiCollectorsAndConnectionsTest` | `GET /collectors`; `POST/GET/PUT/DELETE /connections[/{id}]` — create, duplicate id → **409**, delete-while-in-use → **409**, `POST /connections` → **503** when the write root is unavailable |
| `ControlApiCollectorNotifyTest` | `GET /collectors`; `POST /collectors/{id}/notify` → **202** + two 404-unknown cases; and `AuditTrail.classify("POST","/collectors/feed-a/notify")` — **this is the test that pins `collector.notified`** |
| `ControlApiConnectionsTest` | `GET /connections`, `GET/PUT/DELETE /connections/{id}` 404-unknown, `POST /connections/{id}/test` |
| `ControlApiConnectionProbeTest` | `POST /connections/{id}/probe`, `GET …/explore`, `GET …/sample` — **400/422/501/404** across local vs. unsupported-remote schemes |

Incidental (the route string is an example for another concern, but they do exercise the path):
`ControlApiVersionedSurfaceTest` (`/collectors` under `/api/v1`, `/api`, `/api/v2`) ·
`ApiContextV1DerivationTest` (v1 prefix derivation over `/collectors` variants) · `AuditTrailTest`
(`PUT /connections/{id}`, `POST /connections/{id}/test` classification) · `ControlApiBundleNewKindsTest`
(`POST /connections`, `GET /connections/{id}` as bundle-import kinds).

### 8.4 SPI contracts (`META-INF/services`)

| File | Providers |
|---|---|
| `inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | the **eight**: `SftpConnectorFactory`, `FtpConnectorFactory`, `FtpsConnectorFactory`, `DbExportConnectorFactory`, `S3ConnectorFactory`, `KafkaConnectorFactory`, `AzureBlobConnectorFactory`, `GcsConnectorFactory` |
| `inspecto-engine/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.inspector.DatasetCollectorConnectorFactory` — the ninth scheme, `dataset`, from a different module |
| `inspecto-security/src/main/resources/META-INF/services/com.gamma.acquire.SecretsProvider` | `com.gamma.security.FileKeystoreSecretsProvider` — the SEC-07 edition seam |
| `inspecto/src/test/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.service.FakeRemoteConnectorFactory` (test-only remote scheme) |

`inspecto-engine/src/test/java/com/gamma/inspector/DatasetCollectorConnectorFactoryTest.theFactoryIsServiceLoaderDiscoverable()`
(`:97`) is the only test that loads the SPI through `ServiceLoader`.

### 8.5 Runnable examples

⚠ **No committed example exercises remote collection.** Every `collector.connector` in every committed
`*_pipeline.toon` is `local` (20 occurrences), and `collector.fetch`, `post_action`, `rate_limit` and
`retry` appear in **zero** committed config. What is runnable is the acquisition *semantics* layer over the
local connector:

| Example | Exercises |
|---|---|
| `inspecto/examples/05-acquisition/gap-detection/pipeline.toon` | `collector.gap_detection` |
| `inspecto/examples/05-acquisition/dedup-rerun/pipeline.toon` | ⚠ **not** an acquisition key — its dedup is `processing.duplicate_check` |
| `inspecto/examples/06-serve/sequence-gap/feed_pipeline.toon` | `collector.gap_detection` under the serve runner |
| `inspecto/examples/06-serve/checksum-change/orders_pipeline.toon` | `collector.duplicate { mode: checksum, algorithm: SHA256, on_change: alert }` |
| `inspecto/examples/06-serve/incremental-watermark/feed_pipeline.toon` | `collector.duplicate { mode: metadata }` + `collector.incremental { watermark: last_modified }` |
| `inspecto/examples/07-steps/collect/pipeline.toon` | `connector`, `discovery: poll`, `include[]`, `exclude[]`, `recursive_depth`, `gap_detection` (mirrored at `spaces/default/config/collect_step/collect_step_pipeline.toon`) |
| `spaces/ucc/config/voucher/voucher_pipeline.toon` | the only committed **`collector.stability`** — `window: 10s`, `size_checks: 2` — and `collector.consignment { max_files, max_bytes }` |

Runners: `inspecto/examples/run-example.ps1` / `.sh` (batch) and `serve-example.ps1` / `.sh` (serve mode —
required for gap/fingerprint/watermark/stability, which need more than one cycle; the `phase2/` two-cycle
protocol and `mtimes.txt`). Self-checking `probes.txt` assertions exist for the three `06-serve/*` examples
above and **not** for either `05-acquisition/*`.

Connection profiles — three committed, all exercising the Connections API surface rather than collection
(no pipeline references any of them): `spaces/default/config/connections/local_demo_connection.toon`
(`sftp`, `${ENV:DEMO_SFTP_PASSWORD}`; ⚠ `host: 127.0.0.1 port: 8080` reads as a probe fixture aimed at the
control-plane port, not a working SFTP endpoint) · `spaces/demo/config/connections/demo_local_connection.toon`
(`local`, the only one that resolves offline) · `spaces/demo/config/connections/warehouse_sftp_connection.toon`
(`sftp`, `${ENV:WAREHOUSE_SFTP_PASSWORD}`; `spaces/demo/config/README.md:52` labels it "reference shape").
**No committed `.toon` anywhere contains a `tunnel:` or `proxy:` block** — those shapes live only in
`connectors-runbook.md`, `ConnectionProfileTest`'s inline TOON, and the UI's `connection-form.dialog.ts`.

### 8.6 Guards

- `tools/check-secrets.mjs` — the ACQ-relevant guard: it exists so a credential cannot be reintroduced as a
  literal, and it explicitly blesses `${ENV:…}` as *the* sanctioned way not to hold a secret. Two modes,
  because tree-scan alone hands out a false green when a secret is committed and moved one commit later;
  `.githooks/pre-push` runs both. Wired into `ci.yml`.
- `tools/check-vocabulary.mjs` — rule `source-acquisition-entity` (`:228`) enforces Collector over Source. <!-- vocab-allow: names the guard rule by id -->
  ✅ **The merge hazard this note used to describe is discharged (2026-09-08).** `DOC_ALLOW` held a
  *path-keyed* waiver for `integrations.md` whose only suppressed hit was that file's H1 ("Remote Sources"); <!-- vocab-allow: quotes the retired heading the waiver covered -->
  the connector runbook was split out to `acquisition/connectors-runbook.md`, the H1 went with the retitle,
  and the waiver was **deleted** in the same commit — the guard's self-retirement rule would otherwise have
  failed the build. The moved text needed no waiver of its own: every remaining banned-sense use is fenced
  or backticked. The guard's comments still record the identical error class fixed once before — a
  documented `GET /sources` that is really `GET /collectors`.
- `tools/check-doc-links.mjs` — new 2026-09-08, wired into `.githooks/pre-push` + `ci.yml`; any ACQ file
  move must keep its 1,378-link baseline at zero dangling.
- `tools/check-coverage.mjs` — ⚠ the floors are **repo-wide, not per-module** (`:2-6`), so there is no
  `inspecto-acquire` floor to cite; do not claim one.

### 8.7 Named coverage gaps (verified absent, not assumed)

1. 🔴 **No test asserts the registered-scheme set.** The number eight is only the line count of the
   services file; `FtpsConnectorTest:98` and `DatasetCollectorConnectorFactoryTest:97` each assert a single
   scheme. Nothing counts or lists them together, so a dropped `META-INF/services` line would ship silently
   — the exact failure mode of the palette-fallback incident.
2. 🔴 **No test feeds a raw `ssh-rsa AAAA…` line into `host_key`.** `SftpConnectorTest` covers
   `wrongHostKeyFingerprintRejectsConnect()` (`:109`, a bogus colon-hex fingerprint) and
   `strictHostKeyWithoutAnyPinRefusesConnect()` (`:120`). The rejection of a raw key line follows from
   `HostKeyPolicy:70-71` handing the string to sshj's fingerprint verifier — it is code-supported by
   construction, but **not covered by a test with that input**, so state it as a design property, never as
   a tested one.
3. `05-acquisition/gap-detection` and `05-acquisition/dedup-rerun` have no `probes.txt`, so they are
   demonstrable but not self-checking.
4. `inspecto/examples/README.md:11` itself records that templates for features needing external infra
   (SFTP/FTP/DB connections) are still to come — the honest statement of gap 8.5.

---
