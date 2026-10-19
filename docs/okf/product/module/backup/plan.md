---
type: Feature
title: "Backup and restore — plan"
description: "Requirement of record for the backup, backup_verify and restore maintenance tasks: what was required, built, left and refused."
tags: [backup, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Backup and restore — plan

**Requirement of record:** System Maintenance MNT-5 (backup, verify), MNT-6 (restore) and MNT-10 (backup
catalog), restated in [plan-observability](../observability/plan-observability.md) §3.6; edition placement is
EDITIONS `OPS-06` and `EDG-01` cell 2 ([EDITIONS](../../../../EDITIONS.md)). The original plan is archived at
[system-maintenance-plan](../../../../archived-documents/plans-archive/system-maintenance-plan.md).

## Required

- Per-Space configuration (and optionally DuckDB) backup as a task on the `maintenance` Job Type, never a shell script or OS cron.
- Verification of an archive against hashes that do not come from the archive itself.
- Fail-closed restore, including restore into a new Space.
- Not available in the Basic edition (`OPS-06`).

## Built

- `backup`, `backup_verify`, `restore`, contributed by the optional `inspecto-backup` module through the `MaintenanceTaskProvider` seam (moved out of the engine 2026-09-07, `EDG-01` cell 2). See [spec](spec.md).
- Sidecar manifest with per-file and archive SHA-256; atomic staging of zip and sidecar.
- `maintenance_backups` catalog Dataset (MNT-10), one Parquet row per backup.
- Signals `maintenance.backup.completed`, `maintenance.backup.verify_failed`, `maintenance.restore.completed`.
- Path values resolve against the Space config root (`JOB-DIR-CWD-CONTAINMENT-1`, `JOB-PATH-BACKUPTASK-SPLIT-1`); the catalog registers in this Space's registry (`BACKUP-CATALOG-SPACE-ROOT-1`).
- Secrets, Action Request records, Regulatory Report records and restricted-quarantine files are never archived.
- The module also declares the `sink.archive` and `sink.ducklake` edition features (`PROCESSOR-RELEASE-READINESS-1` G9).

## Left

- `PATH-2` residual, a LEAVE recorded in [BACKLOG](../../../../BACKLOG.md): the restore zip-slip jail is pinned by test; no further work.
- `INGEST-RAW-SOURCE-COPIES-RETENTION-1` (BACKLOG): a raw source file inside a backup is a residual of the never-store-values decision.
- `LA-EDITIONS-RESIDUALS-1` item (7) (BACKLOG): comments in this module still use the old edition names.

## Refused

- A silent skip when the module is absent: a job naming one of the tasks on a Basic bundle fails loudly, so a chained job never proceeds as if a backup happened.
- A working-directory default for paths: with no allowed root configured every jailed path fails closed.
