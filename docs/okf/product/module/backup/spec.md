---
type: Feature
title: "Backup and restore — spec"
description: "Implementation detail of the inspecto-backup module: classes, the MaintenanceTaskProvider seam, task parameters, archive format and signals."
tags: [backup, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Backup and restore — spec

Module: `features/inspecto-backup` (package `com.gamma.backup`), `buildRole: implementation`,
`offeringRole: optional`, `bindingTime: boot` ([module.toon](../../../../../features/inspecto-backup/src/main/resources/META-INF/inspecto/module.toon)).
Operating procedure: [admin-guide-backup-restore](admin-guide-backup-restore.md).

## Classes

| Class | Role |
|---|---|
| `BackupTaskProvider` | `MaintenanceTaskProvider` (ServiceLoader). `tasks()` = `backup`, `backup_verify`, `restore`; dispatches to `BackupTask`. The classpath entry is the edition switch; there is no flag. |
| `BackupTask` | The three task bodies plus the catalog append. Package-private, static. |
| `BackupSignals` | Signal type constants, pinned by `BackupSignalsTest`. |
| `BackupEditionFeatures` | `EditionFeatureProvider` declaring `sink.archive` and `sink.ducklake`. |

ServiceLoader registrations: `META-INF/services/com.gamma.job.MaintenanceTaskProvider` and
`META-INF/services/com.gamma.etl.EditionFeatureProvider`.

## Task parameters

| Task | Required | Optional |
|---|---|---|
| `backup` | `dir`, `backup_dir` | `prefix` (default: the last segment of `dir`) |
| `backup_verify` | `backup_dir` | `archive` (one file inside `backup_dir`), `all` (`"true"` verifies every zip; default newest only) |
| `restore` | `archive`, `target_dir` | `overwrite` (`"true"`; default `false`) |

Every path except `backup_verify`'s `archive` goes through `PathJail.requireJobPathUnderAny` against
`PathJail.allowedRoots()` (from `-Dassist.safety.roots` plus every hosted Space base), with the Space config
root (`SpaceConfigRoot.current()`) as the base for relative values. `backup_verify`'s `archive` is jailed
against the resolved `backup_dir`.

## Archive format

- `<prefix>_<yyyyMMdd_HHmmssSSS>.zip`: relative paths with forward slashes, plus a `backup-manifest.json` entry.
- Sidecar `<archive>.manifest.json`: `created`, `stamp`, `source`, `files[]` (path, bytes, SHA-256) and the SHA-256 of the finished zip.
- Both are staged and renamed into place (`AtomicFiles`), so each is absent or complete.
- Excluded from the source walk: the backup directory itself, the DuckDB spill directory, and any path with a segment ending `.secrets`, named `.pending-changes.key`, `action-requests`, `regulatory-reports`, or the restricted quarantine (`BackupTask.secret`).

## Behaviour

- **Dry run** (`?dryRun=true` on trigger): `backup` reports file count and bytes; `restore` reports files, bytes and conflicts. Nothing is written.
- **Restore** refuses without the sidecar or on an archive hash mismatch before writing; extraction is jailed under `target_dir`; existing files block unless `overwrite`; every extracted file is re-hashed.
- **Catalog** (`catalogRow`): one single-row Parquet `backup_<stamp>_out.parquet` under `<dataDir>/maintenance_backups/` plus an idempotent `dataset` component in this Space's `registry`. Best effort: skipped or warned in the Run Log, never fails the backup.

## Signals

| Type | Severity | When |
|---|---|---|
| `maintenance.backup.completed` | INFO | archive written (`archive`, `archiveSha256`, `fileCount`, `totalBytes`) |
| `maintenance.backup.verify_failed` | CRITICAL | any finding (`backupDir`, `count`, `findings`) |
| `maintenance.restore.completed` | | restore finished |

Tests: [tests-inventory](tests-inventory.md).
