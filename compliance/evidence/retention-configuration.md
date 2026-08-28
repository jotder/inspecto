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
| **Event store (Parquet) — including AUDIT events and Signals** | append-only, Hive-partitioned by `level/year/month/day`; **no age or size bound exists** (`ParquetEventStore` implements no prune; Signals are Events in the same store) | Partly a **stated decision** (MNT-14 G3, recorded on `IncidentPurgeTask`): the audit trail survives the records it describes — a purged Incident's history, purge record included, is deliberately retained. What is NOT decided is an upper bound: today the store grows forever. **Filling this is decision-gated product work** — an audit-retention length is an org policy (and deleting audit records needs one), never a default to invent. The partition layout means retention, when decided, is a file delete by partition, not a SQL DELETE. |
| Alerts, Cases, Tasks (`ObjectType` ≠ INCIDENT) | no purge path | **Stated decision** on `IncidentPurgeTask`: "inventing retention policy for Cases and Alerts now would be policy without a requirement" — reopen on requirement, not by sweep. |
| In-memory event ring | bounded by `capacity` (process-lifetime heap bound) | not durable retention; listed to avoid double-counting. |

## 3. The auditor answer (G5)

Retention **is configurable** for the operational stores (§1) and the configuration is evidence: the
authored `maintenance` job `.toon`s in the space's `config/jobs/` ARE the deployment's retention
policy, reviewable and diffable. The audit log is deliberately not retention-managed today; a
deployment whose compliance regime demands a bounded audit-retention window needs (a) the org to
state the window and (b) the event-store prune task built against it — tracked in the controls
matrix as G5's remaining half.

## 4. Review triggers

Re-verify on any new durable store (a store born without a prune task lands in §2 by default —
audit it in the same change) and whenever `MaintenanceJob` gains or changes a task.
