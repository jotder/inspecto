package com.gamma.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * A {@link JobType#MAINTENANCE} job: a named built-in housekeeping task. Keeps the
 * platform tidy on a schedule without a separate cron/script.
 *
 * <h3>Tasks (PIP-7 maintenance library)</h3>
 * <ul>
 *   <li>{@code cleanup} (default) — retire files under {@code dir} that breach ANY configured policy
 *       limit (MNT-2b): older than {@code retention_days} (default 7), beyond the newest {@code max_count}
 *       files, or beyond a newest-first {@code max_size} byte budget; optionally filtered by a {@code glob}
 *       (default {@code *}). With {@code archive_instead_of_delete: true} affected files move to the
 *       required {@code archive_dir} (relative structure preserved, never re-walked) instead of being
 *       deleted. Useful for pruning old audit CSVs, markers, backups, exports, or quarantine. See
 *       {@link CleanupTask}.</li>
 *   <li>{@code ledger_prune} — delete acquisition-ledger fingerprints processed more than
 *       {@code retention_days} (required) ago, optionally scoped to one {@code source}. <b>Deliberate
 *       forgetting</b>: a pruned file still present at the source re-ingests as NEW — retention must
 *       exceed the source's own file lifetime. See {@link LedgerPruneTask}.</li>
 *   <li>{@code runlog_prune} — delete Run history older than {@code retention_days} (required): the per-run
 *       JSONL files under {@code <auditDir>/runlog/} and {@code <auditDir>/artifacts/}, plus rows of the
 *       optional {@code inspecto_job_runs} projection; optional {@code max_count} caps each JSONL dir to
 *       its newest N files (System Maintenance MNT-2a). See {@link RunlogPruneTask}.</li>
 *   <li>{@code notification_prune} — delete in-app notifications older than {@code retention_days}
 *       (required) from this space's feed, whatever their read/archived state. <b>Deliberate
 *       forgetting</b> like the other prunes. (The default feed is in-memory and self-caps, so this
 *       matters most once a persistent notification backend lands.) See {@link NotificationPruneTask}.</li>
 *   <li>{@code receipt_prune} — delete delivery receipts (D8) sent more than {@code retention_days}
 *       (required) ago, whatever status they carry. Receipts accrue per <b>external delivery</b>, so they
 *       grow faster than notifications; this is what bounds them (the in-memory store's oldest-first cap
 *       is a backstop, not a retention policy). See {@link ReceiptPruneTask}.</li>
 *   <li>{@code incident_purge} — physically remove {@code ARCHIVED} Incidents closed more than
 *       {@code retention_days} (required) ago, cascading to their notes/attachments, links and tag edges
 *       (D5/MNT-14). Optional {@code max_count} (default 1000) bounds one run. Legal-held Incidents are
 *       never purged and are counted separately. ⚠ The <b>only</b> destructive task over operator business
 *       records — hence {@code purge}, not {@code prune}. The append-only event trail survives a purge.
 *       See {@link IncidentPurgeTask}.</li>
 *   <li>{@code storage_report} — read-only per-axis storage usage + largest consumers over {@code dir},
 *       recorded as Run Artifacts and — on a real run with a data/write root configured — appended as one
 *       row per axis to the queryable {@code maintenance_storage} catalog Dataset (the sample series
 *       {@code storage_trend} reads); optional {@code warn_bytes} threshold signal (MNT-3).
 *       See {@link StorageReportTask}.</li>
 *   <li>{@code storage_trend} — read-only growth-trend analysis over the {@code maintenance_storage}
 *       sample series: per-axis and total bytes/day over a {@code window_days} window, a projected
 *       {@code warn_bytes} breach ETA, and the fastest-growing axes as archive candidates; emits
 *       {@code maintenance.storage.trend} when the projected breach is within {@code warn_days} (System
 *       Maintenance COULD tier). See {@link StorageTrendTask}.</li>
 *   <li>{@code scheduler_audit} — read-only hygiene audit of the Job registry: disabled jobs, duplicate
 *       names/specs, orphan triggers (MNT-4). See {@link SchedulerAuditTask}.</li>
 *   <li>{@code backup} / {@code backup_verify} / {@code restore} — zip + SHA-256-manifest archive of a
 *       directory, hash verification against the sidecar manifest, and fail-closed restore with preview
 *       and conflict detection (MNT-5/MNT-6). See {@link BackupTask}.</li>
 *   <li>{@code metadata_validate} — read-only cross-component integrity audit: broken references,
 *       duplicate definitions, missing physical data (MNT-7). See {@link MetadataValidateTask}.</li>
 *   <li>{@code file_repository_audit} — read-only data-root audit: unregistered stores + stale
 *       partial/temp files from interrupted writes (MNT-12). See {@link FileRepositoryAuditTask}.</li>
 *   <li>{@code db_maintenance} — backend maintenance (CHECKPOINT/VACUUM) on the acquisition-ledger DB
 *       and the Consignment output registry, each over its own live connection (DuckDB is single-writer;
 *       a second connection cannot attach). See {@link DbMaintenanceTask}.</li>
 *   <li>{@code retire_superseded} — delete the bytes of output files the Consignment catalog marks
 *       unreadable once they are older than {@code retention_days} (required). The disk half of
 *       addressing step 6: full recomputes now write a new revision instead of rewriting one in place,
 *       so something has to remove the old bytes, and the retention window is the grace period during
 *       which an already-open read may still finish on them. Rows are kept — a row for a deleted file is
 *       harmless, while dropping a row for a file still on disk would let it back into every glob.
 *       See {@link RetireSupersededTask}.</li>
 *   <li>{@code compact} — merge the many small per-batch Parquet output files inside each partition
 *       directory under {@code dir} into one file. Params: {@code min_age_days} (default 1 — only files
 *       already this old are touched, the quiet-window safety), {@code min_files} (default 4 — leave
 *       small partitions alone). Readers glob {@code *.parquet}, so compaction is invisible to queries;
 *       the trade-off is that {@code reprocess} of a compacted-away batch is no longer supported (its
 *       manifest's outputFile is gone) — set {@code min_age_days} beyond your reprocess horizon.</li>
 *   <li>{@code reference_compact} — rewrite an append-only versioned Reference store (a
 *       {@code produces: reference} pipeline with {@code load: upsert|scd2}) to just the rows its read
 *       views can still return, bounding the read amplification of one-file-per-batch appends. Params:
 *       {@code dir} (required — the store root), {@code history_days} (default 0 = winning versions only,
 *       tombstones dropped; positive keeps versions inside the horizon so scd2 as-of still answers).
 *       See {@link ReferenceCompactor}.</li>
 *   <li>{@code materialize} — persist a summary Derived Table (a <b>Matrix</b>, DAT-4) from a measure
 *       spec over a source Dataset; the snapshot swaps in atomically and registers/refreshes a
 *       {@code dataset} component. See {@link MaterializeTask}.</li>
 *   <li>{@code heartbeat} / {@code noop} — do nothing but record a run (liveness probe / test).</li>
 * </ul>
 */
final class MaintenanceJob implements Job {

    private final JobConfig cfg;
    private final String dataDir;   // the space's data root — needed only by the materialize task
    private final String auditDir;  // the space's audit root — needed only by the runlog_prune task
    /** The optional DuckDB run projection ({@code -Djobs.backend=duckdb}) runlog_prune also trims; may be null. */
    private final DbJobRunStore runStore;
    /** The hosting registry the scheduler_audit task inspects (MNT-4); null (bare test construction) = nothing to audit. */
    private final JobService host;

    MaintenanceJob(JobConfig cfg) {
        this(cfg, null);
    }

    MaintenanceJob(JobConfig cfg, String dataDir) {
        this(cfg, dataDir, null, null);
    }

    MaintenanceJob(JobConfig cfg, String dataDir, String auditDir, DbJobRunStore runStore) {
        this(cfg, dataDir, auditDir, runStore, null);
    }

    MaintenanceJob(JobConfig cfg, String dataDir, String auditDir, DbJobRunStore runStore, JobService host) {
        this.cfg = cfg;
        this.dataDir = dataDir;
        this.auditDir = auditDir;
        this.runStore = runStore;
        this.host = host;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "maintenance"; }

    @Override
    public JobResult run() throws Exception {
        return execute(null);
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        return execute(ctx);
    }

    private JobResult execute(JobContext ctx) throws Exception {
        boolean dryRun = ctx != null && ctx.dryRun();
        String task = cfg.opt("task", "cleanup").toLowerCase();
        return switch (task) {
            case "cleanup"            -> CleanupTask.run(cfg, dryRun);
            case "ledger_prune"       -> LedgerPruneTask.run(cfg, dryRun);
            case "runlog_prune"       -> RunlogPruneTask.run(cfg, auditDir, runStore, dryRun);
            case "notification_prune" -> NotificationPruneTask.run(cfg, host, dryRun);
            case "receipt_prune"      -> ReceiptPruneTask.run(cfg, host, dryRun);
            case "incident_purge"     -> IncidentPurgeTask.run(cfg, host, dryRun);
            // Read-only observers: a dry run and a real run observe the same thing. (storage_report
            // additionally persists its sample to the maintenance_storage catalog — real runs only.)
            case "storage_report"     -> StorageReportTask.run(cfg, dataDir, ctx);
            case "storage_trend"      -> StorageTrendTask.run(cfg, dataDir, ctx);
            case "scheduler_audit"    -> SchedulerAuditTask.run(host, ctx);
            case "backup_verify"      -> BackupTask.verify(cfg, ctx);
            case "metadata_validate"  -> MetadataValidateTask.run(ctx, dataDir);
            case "file_repository_audit" -> FileRepositoryAuditTask.run(cfg, dataDir, ctx);
            // Phase-2 write tasks with real previews (MNT-5/MNT-6).
            case "backup"             -> BackupTask.backup(cfg, ctx, dryRun, dataDir);
            case "restore"            -> BackupTask.restore(cfg, ctx, dryRun);
            case "db_maintenance"     -> dryRun
                    ? JobResult.ok("db_maintenance[dry-run]: would run CHECKPOINT/VACUUM on the ledger store", 0L)
                    : DbMaintenanceTask.run(host);
            // Safe by default (MNT-1): a task with no preview does NOTHING on a dry run — never falls
            // through to the real action.
            case "retire_superseded"  -> RetireSupersededTask.run(cfg, dryRun);
            case "compact"            -> dryRun ? noPreview(task) : PartitionCompactor.run(cfg);
            case "reference_compact"  -> dryRun ? noPreview(task) : ReferenceCompactor.run(cfg);
            case "materialize"        -> dryRun ? noPreview(task) : MaterializeTask.run(cfg, dataDir);
            case "heartbeat", "noop"  -> JobResult.ok("heartbeat", 0L);
            default -> throw new IllegalArgumentException("unknown maintenance task '" + task + "'");
        };
    }

    private static JobResult noPreview(String task) {
        return JobResult.ok("[dry-run] task '" + task + "' has no preview — no action taken", 0L);
    }

    /** A file's mtime; unreadable files sort as freshest and are never pruned (fail-closed).
     *  Shared by {@link RunlogPruneTask} and {@link CleanupTask}. */
    static Instant mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    /** Shared by {@link FileRepositoryAuditTask} and {@link CleanupTask}. */
    static boolean olderThan(Path p, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }
}
