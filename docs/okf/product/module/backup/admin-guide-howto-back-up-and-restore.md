---
type: How-to
title: "Back up and restore a Space"
description: "Schedule a nightly backup of a Space's configuration and a check of each archive, run them now, copy the archives off the machine, and restore a Space in place."
item: module.backup
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: features/inspecto-backup
---

# Back up and restore a Space

## Goal

Keep a verified copy of a Space's configuration every night, off the machine, and know how to put it back.

## Before you start

- **Edition:** Standard, Advanced or Preview — the backup tasks are not in Basic.
- **Who:** to create the Jobs, `canAuthorWorkbench`; to run them, `canOperateRuns`; a restore Job also needs
  `canAdminister`. The built-in `admin` Role cannot run Jobs on its own — give the operator `admin` and `power`.
- **What it covers:** the Space's configuration (`config\`, and its `state\` entries) — Pipelines, Schemas, Jobs,
  Expectations, Alert Rules, Dashboards, Roles. **Not** its data: Datasets and their files under `data\` and
  `duckdb\` are not in the archive (see *What can go wrong*).

## Steps

1. **Create the backup Job.** Open **Platform → Workbench → Jobs**, click **New job**, choose Type `maintenance` and
   Trigger **Cron schedule** (`0 4 * * *` is 04:00 every day), and fill the task's fields: task `backup`, dir `.`,
   backup_dir `../data/backups`, prefix `o2c_config`. Click **Continue**, give the Job id `config_backup`, and
   **Create job**.
2. **Create the check Job** the same way: id `backup_verify`, task `backup_verify`, backup_dir `../data/backups`,
   cron `30 4 * * *`. It checks the newest archive against its SHA-256 manifest.
3. **Run both now:** open each Job and click **Run now**. The first Run says `backup: archived … file(s) …`, the second
   `backup_verify: 1 archive(s) OK`. **System Maintenance → Overview** shows the last backup.
4. **Copy archives off the machine** — from the bundle folder, on a schedule of your own:

   ```powershell
   .\bin\backup-offsite.ps1 -From C:\inspecto\spaces\o2c\data\backups -To \\backup-server\inspecto
   ```

   `-VerifyOnly` checks copies that are already there; `-Command "aws s3 cp"` copies with another tool.
5. **To restore in place,** create a Job like step 1 with task `restore`, archive `../data/backups/<file>.zip`,
   target_dir `.` and overwrite `true`, and run it. Run it first with a dry run (in the API, `?dryRun=true` on the
   trigger): it says `restore[dry-run]: would restore …` and writes nothing. A restore checks every file's hash before
   it writes anything.

Paths are relative to the Space's `config\` folder: `../data/backups` is right, `spaces/<id>/data/backups` is not.

## Check it worked

The backup Job exists, runs, and archives the configuration:

```check
POST /spaces/{space}/jobs
as admin
body {"name": "config_backup", "type": "maintenance", "cron": "0 4 * * *", "task": "backup", "dir": ".", "backup_dir": "../data/backups", "prefix": "o2c_config"}
expect status 200|409
```

```check
POST /spaces/{space}/jobs/config_backup/trigger
as admin
expect status 202
save data.runId as backup
```

```check
GET /spaces/{space}/jobs/runs/{backup}
as admin
expect data.status = "SUCCESS"
expect data.message matches /^backup: archived/
retry 30 every 2s
```

## What can go wrong

- `path '…' declared by 'backup_dir' resolves to …, outside the root …` — a backup folder outside the Space's own
  folders is refused unless the server's safety roots include it. Restoring into a **different** Space is refused the
  same way.
- `unknown maintenance task '…' — not built into this bundle` — the edition has no backup tasks (Basic).
- **Run now** is missing — you lack `canOperateRuns`.
- A restore stops on a hash mismatch, and on files that differ unless overwrite is on.
- **The archive holds no data.** To recover Datasets, keep their incoming files, or include the Space's `duckdb\`
  folder in your own file-level backup of the server.

## Next

- [Upgrade the platform](../../layer/platform/admin-guide-howto-upgrade-the-platform.md) — it checks for a verified
  backup first.
- [Tutorial: run it](../../layer/platform/user-guide-tutorial-run-the-platform.md)
