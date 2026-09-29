package com.gamma.job;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-BI-PUBLICATION-1 against a REAL Postgres: full refresh (with column comments read back from
 * {@code pg_description}), then a failed refresh that must leave the old table intact. Enabled by
 * {@code INSPECTO_TEST_PG_URL} (or {@code -Dinspecto.test.pg.url}), exactly as {@code PostgresStateStoreTest};
 * with neither, it SKIPS and says how to turn it on. The runbook there (the zone alias, the Windows {@code &}) applies.
 */
class PostgresPublishPgTest {

    static String url() {
        String u = System.getProperty("inspecto.test.pg.url");
        return u != null && !u.isBlank() ? u : System.getenv("INSPECTO_TEST_PG_URL");
    }

    @Test
    void publishesToARealPostgresAndAFailedRefreshKeepsTheOldTable(@TempDir Path dir) throws Exception {
        String pgUrl = url();
        Assumptions.assumeTrue(pgUrl != null && !pgUrl.isBlank(), "SKIPPED: no Postgres — set INSPECTO_TEST_PG_URL="
                + "jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres to run publish.postgres "
                + "against a real server");
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        Files.createDirectories(data.resolve("subs"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('gold', 1.5), ('silver', 2.0)) t(plan, amount)) TO '"
                    + data.resolve("subs/data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        new ComponentStore(cfg.resolve("registry")).write("dataset", "subs", Map.of("physicalRef", "subs",
                "columns", List.of(Map.of("name", "plan", "description", "Tariff plan"))));
        System.setProperty("assist.write.root", cfg.toString());
        ConnectionRegistry.register(ConnectionProfile.fromMap(Map.of("id", "PG", "connector", "db",
                "options", Map.of("jdbc_url", "jdbc:postgresql://bi.example.test:5432/postgres"))));
        // the egress check runs on the authored host; the test then dials the configured server
        PostgresPublishJobType.resolver = h -> new InetAddress[] {InetAddress.getByName("203.0.113.10")};
        PostgresPublishJobType.opener = (u, props) -> DriverManager.getConnection(pgUrl);
        String schema = "inspecto_publish_test";
        try (Connection pg = DriverManager.getConnection(pgUrl); Statement st = pg.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        try {
            JobConfig job = new JobConfig("pub", "publish.postgres", null, null, true, false,
                    Map.of("connection", "PG", "datasets", "subs", "schema", schema, "retries", "0"), null, null);
            assertEquals("SUCCESS", run(dir, data, job).status());
            try (Connection pg = DriverManager.getConnection(pgUrl); Statement st = pg.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT count(*) FROM " + schema + ".subs");
                rs.next();
                assertEquals(2, rs.getInt(1));
                rs = st.executeQuery("SELECT col_description('" + schema + ".subs'::regclass, 1)");
                rs.next();
                assertEquals("Tariff plan", rs.getString(1));
            }
            PostgresPublishJobType.beforeCommit = () -> { throw new IllegalStateException("injected"); };
            assertEquals("FAILED", run(dir, data, job).status());
            try (Connection pg = DriverManager.getConnection(pgUrl); Statement st = pg.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT count(*) FROM " + schema + ".subs");
                rs.next();
                assertEquals(2, rs.getInt(1), "the old table survives a failed refresh");
            }
        } finally {
            PostgresPublishJobType.beforeCommit = () -> {};
            PostgresPublishJobType.resolver = com.gamma.pipeline.exec.EgressPolicy.SYSTEM;
            PostgresPublishJobType.opener = DriverManager::getConnection;
            ConnectionRegistry.remove("PG");
            System.clearProperty("assist.write.root");
            try (Connection pg = DriverManager.getConnection(pgUrl); Statement st = pg.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static JobRun run(Path dir, Path data, JobConfig job) throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            js.triggerRun(job.name(), null);
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                JobRun r = js.lastRunOf(job.name()).orElse(null);
                if (r != null && !"RUNNING".equals(r.status())) return r;
                Thread.sleep(50);
            }
        }
        throw new AssertionError("no finished run");
    }
}
