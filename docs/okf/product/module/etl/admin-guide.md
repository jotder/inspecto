---
type: Feature
title: "ETL model and step registry — admin guide"
description: "Directories, ledgers, quarantine, markers and start-up warnings an operator meets when running ETL Pipelines."
tags: [etl, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# ETL model and step registry — admin guide

`platform/inspecto-etl` holds the Pipeline config model (`PipelineConfig`, `PipelineConfigParser`),
the step registry and the per-file machinery (ingest, partition writing, quarantine, markers,
ledgers). It runs inside the engine; it has no service or route of its own. Authoring is in the
[pipelines user guide](user-guide-pipelines.md).

## Directories (`dirs` section of a Pipeline config)

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
| `status_dir` | no | — | run ledgers (below); legacy `status_file` names one file |
| `log_dir` | no | — | per-run log |

⚠ `database`, `backup`, `temp`, `errors`, `quarantine` and `markers` must **not** sit inside `poll`
(checked through `PathJail.contains`, symlinks included); the config is refused otherwise.

## Ledgers on disk (beside the status file)

- `<pipeline>_status_<ts>.csv`, `<pipeline>_batches_<ts>.csv`, `<pipeline>_lineage_<ts>.csv`,
  `<pipeline>_unpack_<ts>.csv` — one set per run.
- `<pipeline>_commits.log` — persistent, append-only; the source of truth for "did this Consignment
  commit". Do not edit or truncate it: committed ids in it are skipped on re-run.
- `manifests/<batchId>.json` — Consignment manifests; a replaced one is kept as `.json.superseded`.
- `<batchId>__pending.json` — a parked commit awaiting completion.

Back these up with the output directory; restoring outputs without the commit log makes the next run
re-ingest.

## Quarantine

`QuarantineManager` moves a rejected file to `<quarantine>/<relative path>/<reason>/<file>`, mirroring
the poll tree, with its error CSV when there is one. Reasons: `field_mismatch`, `unreadable`, `empty`,
`rejects_all_or_nothing` (`processing.reject_mode: all_or_nothing`), `output_stem_collision`,
`corrupt_download`. To retry, fix the cause and move the file back into `poll`.

## Duplicate check (markers)

With `processing.duplicate_check.enabled`, a zero-byte marker under `dirs.markers` (mirroring the poll path)
means "already processed" and the file is skipped. `duplicate_check.retention_days` (default 90) controls marker cleanup; `0` or
less deletes every marker on the next cleanup. A same-name re-delivery with changed content is skipped
too (the engine emits a `DUPLICATE_SKIPPED` delivery anomaly, see [BACKLOG](../../../../BACKLOG.md)
`LA-DAILY-INGEST-1`); deliver corrections under a new name.

## Start-up warnings (`ConfigValidator`)

Logged at WARN; the run continues:

- no partitioning declared — output collapses to the 1900/01/01 sentinel;
- blank delimiter — falls through to `,`;
- `duplicate_check.enabled` with `retention_days` ≤ 0;
- CPU oversubscription: `-Dsources.max` × `processing.threads` (default: core count) × `processing.duckdb_threads` (default 0 = auto) above the core count, or (server
  mode) the fleet Consignment cap × `duckdb_threads` above it;
- a plugin ingester with an empty `ingester_config`.

## Troubleshooting

| Symptom | Look at |
|---|---|
| Config refused naming a `dirs` key | the directory is inside `poll`; move it out |
| File disappears from the inbox, no output | the quarantine tree, reason sub-directory, and `dirs.errors` |
| File stays in the inbox, never processed | a marker exists for it (`dirs.markers`) |
| Re-run re-ingests everything | `<pipeline>_commits.log` missing or not restored |
| Run slow, cores saturated | the oversubscription warning; lower `threads` / `duckdb_threads` |

More playbooks: [`ADVANCED_GUIDE.md`](../../../../ADVANCED_GUIDE.md) §11.
