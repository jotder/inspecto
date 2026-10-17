package com.gamma.etl;

import com.gamma.config.safety.PathJail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PartitionWriter} re-checks object-store containment at the write (SCALEOUT-OBJSTORE-DATABASE-DIR-1),
 * so a caller that skipped the 422 gate still cannot COPY outside an allowed prefix. Needs no object store: the
 * refusal happens before any I/O.
 */
class PartitionWriterObjectStoreContainmentTest {

    @AfterEach
    void clear() { System.clearProperty(PathJail.OBJECT_ROOTS_PROPERTY); }

    private static Connection seeded() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE src AS SELECT i AS id, 'p' || (i % 3) AS part, 0 AS __src_id FROM range(3) t(i)");
        }
        return c;
    }

    @Test
    void aWriteOutsideTheAllowedPrefixIsRefusedBeforeAnyIo() throws Exception {
        System.setProperty(PathJail.OBJECT_ROOTS_PROPERTY, "s3://lake/space-a");
        try (Connection c = seeded()) {
            for (String bad : new String[] {"s3://lake/space-b", "s3://lake/space-a/../space-b", "s3://other/x"})
                assertThrows(PathJail.Escape.class, () -> PartitionWriter.write(
                        c, "src", bad, "PARQUET", null, "b", List.of("part")), bad);
        }
    }

    @Test
    void withNoAllowListEveryObjectStoreWriteIsRefused() throws Exception {
        try (Connection c = seeded()) {
            assertThrows(PathJail.Escape.class, () -> PartitionWriter.write(
                    c, "src", "s3://lake/space-a", "PARQUET", null, "b", List.of("part")));
        }
    }

    /** The probe the two refusals above would pass with: an allowed location gets PAST the jail and fails
     *  later, at the store (no endpoint or credentials here) — anything but a {@link PathJail.Escape}. */
    @Test
    void anAllowedLocationPassesTheJail() throws Exception {
        System.setProperty(PathJail.OBJECT_ROOTS_PROPERTY, "s3://lake/space-a");
        try (Connection c = seeded()) {
            Exception ex = assertThrows(Exception.class, () -> PartitionWriter.write(
                    c, "src", "s3://lake/space-a/orders", "PARQUET", null, "b", List.of("part")));
            assertFalse(ex instanceof PathJail.Escape, "an allowed location must not be refused by the jail: " + ex);
        }
    }
}
