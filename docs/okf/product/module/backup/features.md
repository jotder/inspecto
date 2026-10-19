---
type: Feature
title: "Backup and restore — features"
description: "Backup and restore contributes the `backup`, `backup_verify` and `restore` maintenance tasks."
tags: [backup, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Backup and restore — features

**Product Map Item:** [`module.backup`](../../../../../product-map/module/backup.toon) · **Status:** ga · **Availability:** Optional module, staged from Standard and every higher Edition (`offerings/standard.toon`).

## What it is

Backup and restore contributes the `backup`, `backup_verify` and `restore` maintenance tasks. A job naming one of them on a bundle without this module fails loudly instead of silently skipping.

## Who benefits

Operators and administrators responsible for recoverability and disaster-recovery drills.

## Key capabilities

- Scheduled backup, backup verification and restore as maintenance jobs — [admin-guide-backup-restore](admin-guide-backup-restore.md)
- Every path a job writes must resolve under an allowed root — [admin-guide-backup-restore](admin-guide-backup-restore.md)
- Fits the HA / DR drill procedure — [admin-guide-ha-dr-drill](../../layer/platform/admin-guide-ha-dr-drill.md)

## Related hubs

[observability](../../module/observability/index.md) · [platform](../../layer/platform/index.md)

More in this hub: [admin-guide-backup-restore](admin-guide-backup-restore.md).
