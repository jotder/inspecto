# Retention configuration reference (G5, C4)

**What this is:** the single map of what retention exists, how each mechanism is configured, and —
stated, not hidden — which stores have none. Grounded in the code 2026-08-28. G5 called this
"documenting what exists and filling the rest"; the count was understated — there are **seven**
retention/prune tasks, all dispatched by `MaintenanceJob` and authored as ordinary `maintenance`
Jobs (`type: maintenance`, `task: <name>`, scheduled like any Job — there is no hidden built-in
cron; **nothing prunes unless an operator authors the job**).

## 1. What exists — the seven maintenance tasks

| Task | Prunes | Keys (all require an explicit `retention_days` ≥ 1 unless noted) |
|---|---|---|
| `ledger_prune` | acquisition-ledger dedup fingerprints (`AcquisitionLedgers`) | `retention_days`, optional `source` scope |
| `runlog_prune` | per-run JSONL run-log files + `JobRunLedger` rows | `retention_days`, optional `max_count` cap |
| `notification_prune` | in-app `NotificationStore` | `retention_days` |
| `receipt_prune` | delivery receipts (D8) | `retention_days` |
| `incident_purge` | ARCHIVED Incidents past `closedAt + retention_days`, cascading notes/attachments/links/tags; **legal-hold exempt** | `retention_days`, `max_count` (default 1000) |
| `cleanup` | any filesystem dir (incl. backup archives) | `dir`, `glob`, `retention_days` (default 7), `max_count`, `max_size`, **`min_keep`** (always set on backup dirs), `archive_instead_of_delete` |
| `retire_superseded` | superseded consignment/partition files | config-gated — **must be configured or disk grows unbounded** |

Source of truth for semantics: `MaintenanceJob`'s class javadoc + the `maintenance` Job Type
descriptor in `JobService` (⚠ the descriptor's `retention_days: "7"` is a **form default only** —
each task `require()`s the value at run time).

## 2. What has NO retention — stated decisions and open policy

| Store | State | Standing |
|---|---|---|
| **Event store (Parquet) — including AUDIT events and Signals** | append-only, Hive-partitioned by `level/year/month/day`; **age bound = the `event_prune` task** (since 2026-09-02; Signals are Events in the same store, so the same window applies), no size bound | Partly a **stated decision** (MNT-14 G3, recorded on `IncidentPurgeTask`): the audit trail survives the records it describes — a purged Incident's history, purge record included, is deliberately retained. The upper bound, previously undecided, is now **STATED: one year (operator, 2026-08-30)** — so the store no longer grows forever by policy, though it still does **in code** until the prune task is built. ⚠ The MNT-14 G3 stance is unaffected: a purged Incident's history is still deliberately retained *within* the window. The partition layout means retention is a **file delete by partition, not a SQL DELETE**. |
| Alerts, Cases, Tasks (`ObjectType` ≠ INCIDENT) | no purge path | **Stated decision** on `IncidentPurgeTask`: "inventing retention policy for Cases and Alerts now would be policy without a requirement" — reopen on requirement, not by sweep. |
| In-memory event ring | bounded by `capacity` (process-lifetime heap bound) | not durable retention; listed to avoid double-counting. |

## 3. The auditor answer (G5)

Retention **is configurable** for the operational stores (§1) and the configuration is evidence: the
authored `maintenance` job `.toon`s in the space's `config/jobs/` ARE the deployment's retention
policy, reviewable and diffable. The audit log is retention-manageable in code since **2026-09-02**:
the org **stated a one-year audit-retention window (2026-08-30)** and the `event_prune` maintenance
task (COMPLY-3) applies it — `EventStore.prune` deletes whole `day=` partitions of the Parquet event store
older than `retention_days` (UTC, the store's own partition frame), never a row inside the window, so the
MNT-14 G3 stance holds by construction. Both of G5's halves are done: (a) the window is stated; (b) the
task exists. ⛔ It is still **operator-authored** like every prune here — a deployment enforces the window
only once a `maintenance` job with `task: event_prune` and `retention_days: 365` is scheduled. Tell an
auditor "enforced" only after checking that job exists in the space's `config/jobs/`.

## 4. Review triggers

Re-verify on any new durable store (a store born without a prune task lands in §2 by default —
audit it in the same change) and whenever `MaintenanceJob` gains or changes a task.
