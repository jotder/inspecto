---
type: Feature
title: "Backup and restore — architecture"
description: "How the optional backup module plugs into the maintenance Job Type and how backup, verify and restore fit together."
tags: [backup, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Backup and restore — architecture

## Placement

The core `maintenance` Job Type (`MaintenanceJob` in `inspecto-engine`) dispatches on its `task` parameter.
Tasks the core does not own are looked up through the `MaintenanceTaskProvider` ServiceLoader seam;
`inspecto-backup` is one such provider. Bundles from Standard up carry the jar and Basic does not, so on Basic
the three task names are unknown and a job naming one fails at run ([EDITIONS](../../../../EDITIONS.md) `OPS-06`).
The module has no HTTP routes, screens or settings of its own: everything is a Job, authored and triggered
through the Jobs surface.

## Lifecycle

```
backup job (cron)             -> zip + sidecar manifest -> maintenance_backups row
    | emits maintenance.backup.completed
    v
backup_verify job (on_signal) -> archive hash, then every entry hash
    | finding -> maintenance.backup.verify_failed (CRITICAL) -> Alert Rule
    v
restore job (manual, dry run first) -> sidecar + hash check -> jailed extract -> re-hash
    | emits maintenance.restore.completed
```

## Design decisions

- **Verification never trusts the archive.** Hashes live in a sidecar next to the zip, so a damaged zip cannot vouch for itself.
- **Crash-safe writes.** Zip and sidecar are each staged and renamed; a crash leaves absent or complete files.
- **Fail closed everywhere.** Missing sidecar, hash mismatch, zip-slip entry, no allowed root, or an unknown task all fail the Run rather than skip.
- **Same path rule at save and at run.** Relative paths resolve against the Space config root, the rule the 422 config write gate applies, so a value accepted at save is the one used at run.
- **No sensitive copies.** Secret stores, Action Requests, Regulatory Reports and restricted quarantine are excluded from every archive.
- **DuckDB is not hot-copied.** Operational stores are checkpointed by `db_maintenance` before a backup that includes `duckdb/`.

Related: [spec](spec.md) · [admin-guide-backup-restore](admin-guide-backup-restore.md) ·
[observability plan §3.6](../observability/plan-observability.md).
