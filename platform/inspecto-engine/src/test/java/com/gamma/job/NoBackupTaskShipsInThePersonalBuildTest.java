package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EDG-01 cell 2 — the assertion that makes the OPS-06 gating REAL rather than claimed.
 *
 * <p>This runs in the DEFAULT reactor, which is the **Personal** build (there is no
 * {@code -Pedition-personal}). On that classpath {@code task: backup} must be an UNKNOWN maintenance task,
 * because `EDITIONS.md` `OPS-06` says backup/restore is not in the edition and — since 2026-09-07 — the
 * build finally agrees: `BackupTask` lives in `inspecto-backup`, a Standard+/Enterprise-only module that
 * reaches `MaintenanceJob` only through the {@link MaintenanceTaskProvider} seam.
 *
 * <p>🔴 <b>Why it asserts a REFUSAL and not a skip.</b> A job that asks for a task its edition lacks must
 * fail loudly. Returning SKIPPED would look exactly like a successful no-op — the `ConservationCheck`
 * shape this codebase keeps rediscovering — and a chained job downstream would happily run as if the
 * backup had happened. The demo space ships a nightly backup chain; on Personal that chain now stops HERE,
 * with a message that names the cause.
 *
 * <p>⛔ Do not "fix" a failure here by deleting this test. If {@code backup} resolves on this classpath, a
 * backup implementation is back in the Personal bundle — which is the defect, not the test.
 */
class NoBackupTaskShipsInThePersonalBuildTest {

    private static JobConfig job(String task) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, Map.of("task", task));
    }

    @Test
    void backupTasksAreUnknownOnThePersonalClasspath() {
        for (String task : new String[]{"backup", "backup_verify", "restore"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new MaintenanceJob(job(task)).run(),
                    "'" + task + "' must NOT resolve in the default (Personal) build — EDITIONS OPS-06");
            assertTrue(e.getMessage().contains("unknown maintenance task '" + task + "'"), e.getMessage());
            // …and the message says WHY, so an operator reading a failed run is not left guessing.
            assertTrue(e.getMessage().contains("optional edition module"), e.getMessage());
        }
    }

    /** The other half: nothing ELSE moved. A built-in task still runs, so the switch was cut, not broken. */
    @Test
    void aBuiltInTaskStillRunsSoTheSwitchWasCutNotBroken() throws Exception {
        JobResult r = new MaintenanceJob(job("heartbeat")).run();
        assertEquals("SUCCESS", r.status(), r.message());
    }

    /** No provider at all is discovered here — the seam is empty on this classpath, by construction. */
    @Test
    void noMaintenanceTaskProviderIsOnThePersonalClasspath() {
        assertFalse(java.util.ServiceLoader.load(MaintenanceTaskProvider.class).iterator().hasNext(),
                "a MaintenanceTaskProvider on the default classpath means an optional module leaked into Personal");
    }
}
