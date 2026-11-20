---
type: Feature
title: "ELT — admin guide"
description: "Operating the ELT layer: Pipeline directories and roots, the -D flags for acquisition, intake admission control, ingest retry and lane, Jobs and Job packs, the ledgers and files on disk, quarantine, markers, start-up warnings, health and metrics routes, and one table of common failures."
tags: [elt, etl, acquire, engine, admin-guide]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# ELT — admin guide

**What this covers.** What an operator controls and meets when running the ELT layer: the directories a
Pipeline writes and the roots every jailed path must sit under, the JVM system properties read at boot or per
run (acquisition ledger, intake admission control, ingest retry and lane, Jobs and Job packs), the ledgers and
other files left on disk, quarantine and duplicate-check markers, the start-up warnings, the health and metrics
routes, and one table of common failures with their cause and fix. None of these modules has an install step.

**Contents**

1. [The modules](#1-the-modules)
2. [Directories and roots](#2-directories-and-roots)
3. [JVM flags](#3-jvm-flags)
4. [Runtime behaviour to know](#4-runtime-behaviour-to-know)
5. [Ledgers and files on disk](#5-ledgers-and-files-on-disk)
6. [Quarantine](#6-quarantine)
7. [Duplicate check (markers)](#7-duplicate-check-markers)
8. [Start-up warnings (`ConfigValidator`)](#8-start-up-warnings-configvalidator)
9. [Monitor](#9-monitor)
10. [Common failures and troubleshooting](#10-common-failures-and-troubleshooting)

## 1. The modules

- **`platform/inspecto-acquire`** (Base, every Edition) — acquisition: Collectors, Connections, the
  acquisition ledger, the stability gate, the circuit breaker, intake admission control. Collector and
  Connection authoring is in [the user guide, §21](user-guide-elt-collectors-runs.md#21-collectors-and-collector-configuration);
  routes are in [api-elt.md](api-elt.md).
- **`platform/inspecto-etl`** holds the Pipeline config model (`PipelineConfig`, `PipelineConfigParser`),
  the step registry and the per-file machinery (ingest, partition writing, quarantine, markers, ledgers). It
  runs inside the engine; it has no service or route of its own. Authoring is in the
  [pipelines user guide](user-guide-elt.md).
- **`platform/inspecto-engine`** — the engine, a base platform module present in every edition; it has no
  install step of its own. What an administrator controls is a set of JVM system properties read at boot or
  per run (§3). The full cross-module flag list is
  [admin-guide-launch-flags](../../layer/platform/admin-guide-launch-flags.md); the per-Space DuckDB settings
  are in [admin-guide-operations-reference](../../layer/platform/admin-guide-operations-reference.md).

## 2. Directories and roots

### 2.1 Pipeline directories (`dirs` section of a Pipeline config)

Every `dirs.*` value resolves under the Space directory.

| Key | Required | Default | Holds |
|---|---|---|---|
| `poll` | yes | — | inbox the Collector delivers into |
| `database` | yes | — | output partitions (Parquet or CSV) |
| `backup` | no | — | copies of consumed inputs |
| `temp` | no | — | scratch |
| `errors` | no | `<poll>/errors` | per-file rejected-row CSVs (`<stem>_errors.csv`) |
| `quarantine` | no | `<poll>/quarantine` | rejected input files |
| `markers` | no | — | duplicate-check markers |
| `status_dir` | no | — | run ledgers (§5); legacy `status_file` names one file |
| `log_dir` | no | — | per-run log |

⚠ `database`, `backup`, `temp`, `errors`, `quarantine` and `markers` must **not** sit inside `poll`
(checked through `PathJail.contains`, symlinks included); the config is refused otherwise.

### 2.2 Roots

| Property | Effect |
|---|---|
| `-Dassist.write.root` | Config root of the default Space only; a named Space uses its own root (`SpaceConfigRoot`). |
| `-Ddata.dir` | Overrides the data root for every Space when set. |
| `-Dassist.safety.roots` | `;`-separated allowed roots for every jailed path a Job or Pipeline names (`PathJail`). Unset with no hosted Space means no root at all: jailed paths fail closed. |

## 3. JVM flags

### 3.1 Acquisition and intake admission

| Flag | Default | Effect |
|---|---|---|
| `-Dacquire.ledger.backend` | `memory` | `db` keeps the pre-fetch dedup ledger in a database. With `memory`, dedup history is lost on restart and already-fetched files can be fetched again. |
| `-Dacquire.ledger.db.url` | `jdbc:duckdb:inspecto-acquisition.db` | JDBC URL of the ledger when the backend is `db` (a relative DuckDB file lands in the working directory). |
| `-Dacquire.ledger.db.user` / `-Dacquire.ledger.db.password` | unset | Credentials for a server database ledger. |
| `-Dingest.maxFilesPerCycle` | `500` | Files one poll cycle may admit; `0` turns admission control off. |
| `-Dingest.minFilesPerCycle` | `1` | Floor the adaptive controller may halve down to. |
| `-Dingest.backpressure.adaptive` | `true` | `false` pins the cap at `maxFilesPerCycle` (a hard cap). |
| `-Dingest.maxBytesPerCycle` | `0` (off) | Byte cap per cycle — bytes, not files: one large file passes a count cap. A remote Collector whose listing gives every file a size (S3, GCS, Azure Blob, SFTP, FTP) is capped **before the fetch**: the files over the cap stay on the source, unfetched, until a later cycle. A listing with any size missing (the `db` export, Kafka, the `dataset` copy) is fetched whole and capped after landing, in the inbox. Either way, a single file over the cap is taken alone rather than never. |

A malformed numeric value falls back to its default. Code:
[`AcquisitionLedgers`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedgers.java),
[`IntakeGovernor`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/IntakeGovernor.java).

### 3.2 Ingest

| Property | Default | Effect |
|---|---|---|
| `-Dingest.retry.max` | `5` | COMMIT attempts per Consignment before the file is quarantined `retry_exhausted`; `0` = unbounded (`CommitRetry`). |
| `-Dingest.retry.backoff.initialMs` | `60000` | First retry delay, doubling per attempt. |
| `-Dingest.retry.backoff.maxMs` | `3600000` | Backoff cap. |
| `-Dingest.lane` | `auto` | `auto` = lane admission as designed; `graph` disables the flat lane (a write it cannot carry fails loudly); `flat` never diverts. Any other value is refused ([spec-execution-lanes](spec-pipeline-execution.md)). |
| `-Dsources.max` | number of Pipelines | Concurrent Collectors in the `MultiCollectorProcessor` CLI. |

### 3.3 Jobs and steps

| Property | Default | Effect |
|---|---|---|
| `-Djobs.maxConcurrentRuns` | `4` | Total in-flight Runs across all Jobs; `0` = unbounded. A server-installed value wins over the flag. |
| `-Djobs.signal.maxChainDepth` | `8` | A signal-triggered Run beyond this chain depth does not fire (loop cut). |
| `-Djobs.runlog.maxEntries` | `10000` | Run Log entries kept per Run; overflow is summarized. |
| `-Djob.deadlineCeilingSeconds` | `86400` | Ceiling on any Job deadline; a Job Type that names none gets 30 minutes (`JobDeadline`). |
| `-Dpipeline.step.timeoutCeilingSeconds` | `1800` | Ceiling on any step deadline (`StepRunner`). |
| `-Djobs.orphan.audit` | `true` | `false` disables the default-on orphan `output_store:` audit. |
| `-Dcomponents.history.keep` | `10` | Kept versions per component (`ComponentStore`, minimum 1). |

### 3.4 Job packs

| Property | Effect |
|---|---|
| `-Djobs.packs.dir` | Directory scanned for Job pack jars. |
| `-Djobs.packs.stagingDir` | Override for the staging directory. |
| `-Djobs.packs.settleMillis` | Wait before loading a dropped jar (default `500`). |
| `-Djobs.packs.requireSignature` | `true` refuses unsigned packs. |
| `-Djobs.packs.allowlist` | File of SHA-256 hashes of approved jars. The boot fails if the file lies inside the packs dir, under `assist.write.root`, `spaces.root` or any allowed root, because a writer who can drop a jar must not also approve it (`PackAllowlist`). |

## 4. Runtime behaviour to know

- **Per Space.** The ledger, the stability gate and the circuit breaker each keep one instance per Space.
- **Half-written files.** [`StabilityGate`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/StabilityGate.java)
  holds a file until the connector reports it ready, or until it is unmodified for the window and the same size
  over several polls. Producers that write a sentinel can use a `ready_marker`
  ([`ReadyMarker`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/ReadyMarker.java)); a template
  without `{name}` is treated as a suffix.
- **Dead endpoints.** [`CircuitBreaker`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/CircuitBreaker.java)
  opens after repeated connect/list failures, skips that Collector for a cooldown, then allows one half-open trial.

## 5. Ledgers and files on disk

**Pipeline ledgers (beside the status file):**

- `<pipeline>_status_<ts>.csv`, `<pipeline>_batches_<ts>.csv`, `<pipeline>_lineage_<ts>.csv`,
  `<pipeline>_unpack_<ts>.csv` — one set per run.
- `<pipeline>_commits.log` — persistent, append-only; the source of truth for "did this Consignment
  commit". Do not edit or truncate it: committed ids in it are skipped on re-run.
- `manifests/<batchId>.json` — Consignment manifests; a replaced one is kept as `.json.superseded`.
- `<batchId>__pending.json` — a parked commit awaiting completion.

Back these up with the output directory; restoring outputs without the commit log makes the next run
re-ingest.

**Job and engine files:**

- Run Log: JSONL under `<audit dir>/runlog/`; Run Artifacts: JSONL under `<audit dir>/artifacts/`. Prune with
  the `runlog_prune` maintenance task.
- Operational stores (job runs, consignment outputs, ledgers): one DuckDB file per family under
  `<space>/duckdb/` ([spec-db-layer](../engine/spec-db-layer.md)).
- The acquisition ledger, when `-Dacquire.ledger.backend=db`: the database named by `-Dacquire.ledger.db.url`
  (§3.1).

## 6. Quarantine

`QuarantineManager` moves a rejected file to `<quarantine>/<relative path>/<reason>/<file>`, mirroring
the poll tree, with its error CSV when there is one. Reasons: `field_mismatch`, `unreadable`, `empty`,
`rejects_all_or_nothing` (`processing.reject_mode: all_or_nothing`), `output_stem_collision`,
`corrupt_download`. A file whose COMMIT failed `-Dingest.retry.max` times is quarantined
`retry_exhausted` (§3.2). To retry, fix the cause and move the file back into `poll`.

Quarantined files and the reasons they carry, in full:
[admin-guide-operations-reference](../../layer/platform/admin-guide-operations-reference.md).

## 7. Duplicate check (markers)

With `processing.duplicate_check.enabled`, a zero-byte marker under `dirs.markers` (mirroring the poll path)
means "already processed" and the file is skipped. `duplicate_check.retention_days` (default 90) controls marker cleanup; `0` or
less deletes every marker on the next cleanup. A same-name re-delivery with changed content is skipped
too (the engine emits a `DUPLICATE_SKIPPED` delivery anomaly); deliver corrections under a new name, or state
`collector.duplicate: {mode: checksum, algorithm: SHA256, on_change: reprocess}` so a changed same-name file is
re-ingested and replaces its partition (the shipped `la-daily-feed` template does; [plan-ingestion](plan-elt.md)
§*Corrected same-name day*).

## 8. Start-up warnings (`ConfigValidator`)

Logged at WARN; the run continues:

- no partitioning declared — output collapses to the 1900/01/01 sentinel;
- blank delimiter — falls through to `,`;
- `duplicate_check.enabled` with `retention_days` ≤ 0;
- CPU oversubscription: `-Dsources.max` × `processing.threads` (default: core count) × `processing.duckdb_threads` (default 0 = auto) above the core count, or (server
  mode) the fleet Consignment cap × `duckdb_threads` above it;
- a plugin ingester with an empty `ingester_config`.

## 9. Monitor

- `GET /health/details` — store health; the `acquisitionLedger` entry is `NOT_CONFIGURED` (memory backend),
  `UP` (database open) or degraded (database failed to open, fell back to memory).
- `GET /metrics/acquisition` — acquisition counters.
- `POST /connections/{id}/test` and `POST /connections/{id}/probe` — check that a Connection is reachable.

## 10. Common failures and troubleshooting

In story order: acquisition, ingest, Jobs, packs.

| Symptom | Cause | Fix or where to look |
|---|---|---|
| Files re-fetched after a restart | Ledger is in memory | Set `-Dacquire.ledger.backend=db`. |
| Health shows the ledger degraded | The ledger URL did not open (path, lock, credentials); acquisition continues in memory | Fix the URL and restart. |
| A Collector stops polling for a while | Circuit breaker open after endpoint failures | Test the Connection. |
| Files never picked up | Not yet stable, or the `ready_marker` sentinel is missing | — |
| Backlog drains slowly | Admission cap | Raise `ingest.maxFilesPerCycle` or `ingest.maxBytesPerCycle`. |
| Config refused naming a `dirs` key | The directory is inside `poll` | Move it out. |
| File disappears from the inbox, no output | — | The quarantine tree, reason sub-directory, and `dirs.errors`. |
| File stays in the inbox, never processed | A marker exists for it (`dirs.markers`) | — |
| Re-run re-ingests everything | `<pipeline>_commits.log` missing or not restored | — |
| Run slow, cores saturated | — | The oversubscription warning; lower `threads` / `duckdb_threads`. |
| File quarantined `retry_exhausted` | COMMIT failed `ingest.retry.max` times | Fix the cause, then reprocess or replay from the Runs screen ([user guide §22](user-guide-elt-collectors-runs.md#22-runs-and-run-detail)). |
| `-Dingest.lane=graph` batch fails naming a reason | The write needs the flat lane | Revert to `auto`, record the dependency. |
| `no allowed roots configured for '<field>'` | No `-Dassist.safety.roots` and no hosted Space | Declare the roots at launch. |
| Runs queue instead of starting | `jobs.maxConcurrentRuns` reached | Raise it with care: each Run's DuckDB `memory_limit` multiplies by it. |
| Run recorded `FAILED` at its deadline | Job or step deadline expired | Raise the Job's deadline within the ceiling, or the ceiling flag. |
| Boot fails naming the pack allowlist | Allowlist file is in a writable root | Move it outside every writable root. |

More playbooks: [`ADVANCED_GUIDE.md`](../../../../ADVANCED_GUIDE.md) §11. Broader triage:
[admin-guide-troubleshooting](../../layer/platform/admin-guide-troubleshooting.md).
