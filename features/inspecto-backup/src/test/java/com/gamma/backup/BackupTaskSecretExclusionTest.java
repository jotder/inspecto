package com.gamma.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** What no backup may carry, as {@link BackupTask#secret} decides it. */
class BackupTaskSecretExclusionTest {

    @Test
    void theRestrictedQuarantineIsNeverBackedUp() {
        Path src = Path.of("space");
        assertTrue(BackupTask.secret(src, src.resolve("data/payments/quarantine/.restricted/refused-CARD_NUMBER-1-1.csv")),
                "a restricted-quarantine file (refused for its content) is excluded");
        assertTrue(BackupTask.secret(src, src.resolve(".restricted/x.csv")), "at any depth, the jail root included");
        assertFalse(BackupTask.secret(src, src.resolve("data/payments/quarantine/field_mismatch/x.csv")),
                "an ordinary quarantine file is still backed up");
        assertFalse(BackupTask.secret(src, src.resolve("data/restricted/x.csv")), "only the exact directory name");
    }
}
