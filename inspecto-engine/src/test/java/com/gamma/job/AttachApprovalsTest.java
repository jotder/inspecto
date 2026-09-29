package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ASSURE-XLSX-ATTACHMENTS-1 round 5: what the attachment fingerprint hashes. */
class AttachApprovalsTest {

    private static final Map<String, Object> JOB = Map.of("name", "mailer", "type", "report", "attach", "true",
            "scope", "dataset", "dataset", "orders_ds", "recipients", "ops@example.com");

    /** Item 2: a physicalRef Dataset hashes its REAL resolved relation — the glob under the report's own data root. */
    @Test
    void aPartitionPathDatasetHashesItsResolvedGlobUnderTheDataRoot(@TempDir Path root, @TempDir Path a, @TempDir Path b)
            throws Exception {
        Files.createDirectories(a.resolve("orders"));
        Files.createDirectories(b.resolve("orders"));
        new ComponentStore(root.resolve("registry")).write("dataset", "orders_ds", Map.of("physicalRef", "orders"));
        String underA = AttachApprovals.fingerprint(JOB, root, a.toString());
        assertEquals(underA, AttachApprovals.fingerprint(JOB, root, a.toString()), "stable");
        assertNotEquals(underA, AttachApprovals.fingerprint(JOB, root, b.toString()),
                "a different data root is a different relation — so it is a different approval");
    }

    /** Item 2: a Dataset whose relation cannot be resolved is NOT approvable (never an error string hashed). */
    @Test
    void anUnresolvableDatasetIsNotApprovable(@TempDir Path root) throws Exception {
        new ComponentStore(root.resolve("registry")).write("dataset", "orders_ds", Map.of("view", "no_such_view"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AttachApprovals.fingerprint(JOB, root, null));
        assertTrue(e.getMessage().contains("cannot be resolved") && e.getMessage().contains("not approvable"), e.getMessage());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> AttachApprovals.fingerprint(
                Map.of("name", "m", "type", "report", "attach", "true", "dataset", "ghost"), root, null));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
    }
}
