package com.gamma.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class BackupSignalsTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("maintenance.backup.completed", BackupSignals.BACKUP_COMPLETED);
        assertEquals("maintenance.backup.verify_failed", BackupSignals.BACKUP_VERIFY_FAILED);
        assertEquals("maintenance.restore.completed", BackupSignals.RESTORE_COMPLETED);
    }
}
