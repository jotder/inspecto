---
type: Feature
title: "Execution engine — admin guide"
description: "Launch flags, roots and common failures of the execution engine: ingest retry, ingest lane, Job concurrency, deadlines, Run Log limits and Job packs."
tags: [engine, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Execution engine — admin guide

The engine (`platform/inspecto-engine`) is a base platform module present in every edition; it has no
install step of its own. What an administrator controls is a set of JVM system properties read at boot or
per run. The full cross-module flag list is [admin-guide-launch-flags](../../layer/platform/admin-guide-launch-flags.md);
the per-Space DuckDB settings are in [admin-guide-operations-reference](../../layer/platform/admin-guide-operations-reference.md).

## Roots

| Property | Effect |
|---|---|
| `-Dassist.write.root` | Config root of the default Space only; a named Space uses its own root (`SpaceConfigRoot`). |
| `-Ddata.dir` | Overrides the data root for every Space when set. |
| `-Dassist.safety.roots` | `;`-separated allowed roots for every jailed path a Job or Pipeline names (`PathJail`). Unset with no hosted Space means no root at all: jailed paths fail closed. |

## Ingest

| Property | Default | Effect |
|---|---|---|
| `-Dingest.retry.max` | `5` | COMMIT attempts per Consignment before the file is quarantined `retry_exhausted`; `0` = unbounded (`CommitRetry`). |
| `-Dingest.retry.backoff.initialMs` | `60000` | First retry delay, doubling per attempt. |
| `-Dingest.retry.backoff.maxMs` | `3600000` | Backoff cap. |
| `-Dingest.lane` | `auto` | `auto` = lane admission as designed; `graph` disables the flat lane (a write it cannot carry fails loudly); `flat` never diverts. Any other value is refused ([spec-execution-lanes](spec-execution-lanes.md)). |
| `-Dsources.max` | number of Pipelines | Concurrent Collectors in the `MultiCollectorProcessor` CLI. |

## Jobs and steps

| Property | Default | Effect |
|---|---|---|
| `-Djobs.maxConcurrentRuns` | `4` | Total in-flight Runs across all Jobs; `0` = unbounded. A server-installed value wins over the flag. |
| `-Djobs.signal.maxChainDepth` | `8` | A signal-triggered Run beyond this chain depth does not fire (loop cut). |
| `-Djobs.runlog.maxEntries` | `10000` | Run Log entries kept per Run; overflow is summarized. |
| `-Djob.deadlineCeilingSeconds` | `86400` | Ceiling on any Job deadline; a Job Type that names none gets 30 minutes (`JobDeadline`). |
| `-Dpipeline.step.timeoutCeilingSeconds` | `1800` | Ceiling on any step deadline (`StepRunner`). |
| `-Djobs.orphan.audit` | `true` | `false` disables the default-on orphan `output_store:` audit. |
| `-Dcomponents.history.keep` | `10` | Kept versions per component (`ComponentStore`, minimum 1). |

## Job packs

| Property | Effect |
|---|---|
| `-Djobs.packs.dir` | Directory scanned for Job pack jars. |
| `-Djobs.packs.stagingDir` | Override for the staging directory. |
| `-Djobs.packs.settleMillis` | Wait before loading a dropped jar (default `500`). |
| `-Djobs.packs.requireSignature` | `true` refuses unsigned packs. |
| `-Djobs.packs.allowlist` | File of SHA-256 hashes of approved jars. The boot fails if the file lies inside the packs dir, under `assist.write.root`, `spaces.root` or any allowed root, because a writer who can drop a jar must not also approve it (`PackAllowlist`). |

## Files on disk

- Run Log: JSONL under `<audit dir>/runlog/`; Run Artifacts: JSONL under `<audit dir>/artifacts/`. Prune with the `runlog_prune` maintenance task.
- Quarantined files and the reasons they carry: [admin-guide-operations-reference](../../layer/platform/admin-guide-operations-reference.md).
- Operational stores (job runs, consignment outputs, ledgers): one DuckDB file per family under `<space>/duckdb/` ([spec-db-layer](spec-db-layer.md)).

## Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `no allowed roots configured for '<field>'` | no `-Dassist.safety.roots` and no hosted Space | declare the roots at launch |
| file quarantined `retry_exhausted` | COMMIT failed `ingest.retry.max` times | fix the cause, then reprocess or replay from the Runs screen ([user-guide-runs](user-guide-runs.md)) |
| Runs queue instead of starting | `jobs.maxConcurrentRuns` reached | raise it with care: each Run's DuckDB `memory_limit` multiplies by it |
| Run recorded `FAILED` at its deadline | Job or step deadline expired | raise the Job's deadline within the ceiling, or the ceiling flag |
| boot fails naming the pack allowlist | allowlist file is in a writable root | move it outside every writable root |
| `-Dingest.lane=graph` batch fails naming a reason | the write needs the flat lane | revert to `auto`, record the dependency |

Broader triage: [admin-guide-troubleshooting](../../layer/platform/admin-guide-troubleshooting.md).
