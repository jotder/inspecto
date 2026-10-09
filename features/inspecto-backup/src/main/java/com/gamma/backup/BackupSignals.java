package com.gamma.backup;

/**
 * This module's dotted Signal types (operator, 2026-10-09: per-module constants; core
 * {@code com.gamma.signal.SignalType} keeps only core names). Pinned byte-for-byte by {@code BackupSignalsTest}.
 */
public final class BackupSignals {

    private BackupSignals() {}

    /** Signal type {@code maintenance.backup.completed}. */
    public static final String BACKUP_COMPLETED = "maintenance.backup.completed";

    /** Signal type {@code maintenance.backup.verify_failed}. */
    public static final String BACKUP_VERIFY_FAILED = "maintenance.backup.verify_failed";

    /** Signal type {@code maintenance.restore.completed}. */
    public static final String RESTORE_COMPLETED = "maintenance.restore.completed";
}
