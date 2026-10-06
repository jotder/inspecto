package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A raw {@code jdbc:postgresql:} value in a family's own {@code *.backend} IS the URL and would bypass the
 * per-Space {@code currentSchema} scoping, so it is refused at store open (operator, 2026-10-06). A raw
 * {@code jdbc:duckdb:} value (the test reactor's pins) and {@code backend=postgres} keep working.
 *
 * <p>⛔ Restores, never clears, every property it touched — surefire pins {@code -Dstatus.backend=jdbc:duckdb:}.
 */
class RawJdbcBackendRefusalTest {

    private static final String PG = "jdbc:postgresql://pg-host:5432/ops?password=s3cret";

    private static void withProps(Map<String, String> props, Runnable body) {
        List<Map.Entry<String, String>> prior = new ArrayList<>();
        props.forEach((k, v) -> {
            prior.add(Map.entry(k, String.valueOf(System.getProperty(k))));
            System.setProperty(k, v);
        });
        try {
            body.run();
        } finally {
            for (Map.Entry<String, String> e : prior) {
                if ("null".equals(e.getValue())) System.clearProperty(e.getKey());
                else System.setProperty(e.getKey(), e.getValue());
            }
        }
    }

    @Test
    void aRawPostgresBackendIsRefusedAtStoreOpen_namingTheKeyAndTheSafeAlternative(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir.resolve("north"));
        // Before the gate this opened (or degraded) UNSCOPED - no exception at all.
        withProps(Map.of("jobs.backend", PG), () -> {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ServiceStores.openJobRunStore(root));
            assertTrue(e.getMessage().contains("-Djobs.backend"), e.getMessage());
            assertTrue(e.getMessage().contains("-Djobs.backend=postgres"), e.getMessage());
            assertTrue(e.getMessage().contains("-Djobs.db.url"), e.getMessage());
            assertTrue(e.getMessage().contains("-Dinspecto.db.url"), e.getMessage());
            assertFalse(e.getMessage().contains("s3cret"), "the value can carry credentials - never echoed");
        });
        // the DB_FLAG-shaped opener (status) goes through the same gate
        withProps(Map.of("status.backend", "jdbc:postgresql://pg-host/ops"), () ->
                assertThrows(IllegalStateException.class, () -> ServiceStores.openStatusStore(root)));
        // and the diagnostic resolve cannot report a URL the openers would refuse
        withProps(Map.of("jobs.backend", PG), () ->
                assertThrows(IllegalStateException.class,
                        () -> OperationalDb.resolve(OperationalDb.Family.JOB_RUNS, root)));
    }

    @Test
    void aRawDuckdbBackendStillOpens(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir.resolve("north"));
        withProps(Map.of("jobs.backend", "jdbc:duckdb:"), () -> {
            var store = ServiceStores.openJobRunStore(root);
            assertNotNull(store, "a raw jdbc:duckdb: backend is still a first-class source");
            assertEquals("jdbc:duckdb:", OperationalDb.rawBackendUrl(OperationalDb.Family.JOB_RUNS, "jdbc:duckdb:"));
        });
    }

    @Test
    void postgresViaTheBackendKeywordIsStillSchemaScoped(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir.resolve("north"));
        withProps(Map.of("jobs.backend", "postgres", "inspecto.db", "postgres", "inspecto.db.url", "jdbc:postgresql://pg-host:5432/ops"), () ->
                assertEquals("jdbc:postgresql://pg-host:5432/ops?currentSchema=space_north",
                        OperationalDb.urlFor(OperationalDb.Family.JOB_RUNS, root, root.jobRunDbUrl())));
    }
}
