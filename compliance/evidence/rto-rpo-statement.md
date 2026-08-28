# RTO / RPO Statement — and the restore-drill record (G6, availability A1)

**What this is:** the recovery-objective statement the controls matrix (A1 / gap G6) requires,
grounded in the shipped backup/restore capability. The *procedure* lives in
[`docs/ops/backup-restore-runbook.md`](../../docs/ops/backup-restore-runbook.md) — this document
states the objectives and records the drills; it does not restate the runbook.

## 1. Recovery capability (grounded — what the product does today)

- **Backup unit:** per-space `config/` (and, after a `db_maintenance` CHECKPOINT, the space's
  DuckDB stores), produced by a scheduled `maintenance` job (`task: backup`), each archive carrying
  a SHA-256 sidecar manifest and a row in the BI-queryable `maintenance_backups` catalog Dataset.
- **Integrity:** `task: backup_verify` re-hashes the archive and every entry; a mismatch fails the
  Run and emits `maintenance.backup.verify_failed` (CRITICAL) for alerting. The demo space chains
  verification onto every backup via `on_signal`.
- **Restore:** `task: restore` is fail-closed (manifest required, hash-checked before any byte is
  written, extraction path-jailed, per-file re-hash), previews via `?dryRun=true`, and supports
  restore-into-a-new-space. Rollback of a bad restore = restoring the pre-restore backup.

## 2. Recovery Point Objective (RPO)

The achievable RPO **is the backup job's cron interval**: data and config changes after the last
verified backup are lost on a total-loss restore. With the runbook's example schedule
(`cron: "0 4 * * *"`, daily) the achievable RPO is **≤ 24 hours**. Tightening the objective is a
schedule change on the backup job, not a code change.

> **Committed RPO target: `<OPERATOR TO STATE — e.g. 24h>`**
> (an org commitment; state it per deployment, do not derive it from the example cron)

Boundary honesty: batch inputs still present in the inbox re-ingest idempotently on the next poll
cycle after a restore (`OVERWRITE_OR_IGNORE` outputs), which in practice recovers ingested data
newer than the last backup **when the source files still exist** — that is a mitigation, not part
of the stated RPO.

## 3. Recovery Time Objective (RTO)

The achievable RTO is the sum of: provisioning a node with the packaged bundle + JVM, restoring the
newest verified archive (runbook §Restore, steps 1–4), and boot/registration (configs register on
boot). It is **measured by drill, not asserted** — the table in §4 is the evidence.

> **Committed RTO target: `<OPERATOR TO STATE — e.g. 4h>`**
> (state after the first drill measurement; a target with no drill behind it is the document an
> auditor disproves first)

Deployment posture context: the platform is single-node by design (NFR-8); the supported
multi-instance posture is active/passive, so RTO covers standing the passive (or a fresh) node up —
there is no failover-time claim.

## 4. Restore-drill record

One row per drill. A drill = restore the newest verified production backup into a **new space**
(runbook §"Restore into a new space") on a non-production node, then smoke it (health, jobs list,
one representative pipeline trigger). Record honestly — a failed drill row is evidence the process
is exercised, not a finding to hide.

| Date | Operator | Backup archive (name + SHA-256 prefix) | Restore target | Elapsed (prep / restore / smoke) | Result | Notes |
|---|---|---|---|---|---|---|
| *none recorded yet* | | | | | | first drill pending — schedule one before committing the §3 target |

## 5. Review

Re-verify this statement whenever the backup mechanism changes (the runbook is the tripwire — a
change there invalidates §1) and after every drill (§4 feeds §3's committed target).
