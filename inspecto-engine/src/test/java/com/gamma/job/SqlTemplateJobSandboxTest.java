package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SQL-TEMPLATE-SANDBOX-1} — a {@code sql.template} job's authored SQL reaches DuckDB only through
 * {@link com.gamma.sql.SqlGuard} and on a locked-down connection.
 *
 * <p>Until 2026-09-24 {@link SqlTemplateJob} ran {@code CREATE TABLE … AS <authored sql>} on a plain
 * {@code jdbc:duckdb:} connection: no allow-list, extension autoload on, external access on. Each probe
 * below names something the authored SQL could do there that the job's contract (a read-only query over
 * the named source Datasets) never granted — and asserts the side effect did <b>not</b> happen, not
 * merely that the run failed, so a probe cannot pass because the SQL was malformed.
 */
class SqlTemplateJobSandboxTest {

    private static JobRun await(Supplier<JobRun> s) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 20s");
        return r;
    }

    private static void seedTransactions(Path dataDir) throws Exception {
        DuckDbUtil.loadDriver();
        Path store = Files.createDirectories(dataDir.resolve("transactions"));
        String out = store.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t AS SELECT * FROM (VALUES (1, 100.0), (2, 25.0)) AS v(account_id, amount)");
            st.execute("COPY t TO '" + out + "' (FORMAT PARQUET)");
        }
    }

    /** Run one sql.template job with {@code sql} over the seeded store; returns the finished run. */
    private static JobRun run(Path dir, String sql) throws Exception {
        Path dataDir = dir.resolve("data");
        seedTransactions(dataDir);
        JobConfig job = new JobConfig("probe", "sql.template", null, null, true, false,
                Map.of("sql", sql, "sources", "transactions", "sink_dataset", "probe_out"), null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            assertTrue(js.triggerRun("probe", null).isPresent());
            return await(() -> js.lastRunOf("probe").orElse(null));
        }
    }

    private static String lit(Path p) {
        return "'" + p.toAbsolutePath().toString().replace('\\', '/') + "'";
    }

    @Test
    void aPlainReadOnlyQueryOverTheNamedSourceStillRuns(@TempDir Path dir) throws Exception {
        JobRun r = run(dir, "SELECT account_id, sum(amount) AS total FROM transactions GROUP BY account_id");
        assertEquals("SUCCESS", r.status(), "the positive control must run: " + r.message());
    }

    @Test
    void readingAFileOutsideTheDataRootIsRefused(@TempDir Path dir) throws Exception {
        Path secret = Files.createDirectories(dir.resolve("outside")).resolve("secret.csv");
        Files.writeString(secret, "k,v\nsecret,42\n");
        JobRun r = run(dir, "SELECT * FROM read_csv(" + lit(secret) + ")");
        assertNotEquals("SUCCESS", r.status(), "read_csv of a file outside the data root ran: " + r.message());
        assertFalse(Files.exists(dir.resolve("data").resolve("probe_out")) && hasParquet(dir.resolve("data").resolve("probe_out")),
                "no snapshot of the secret file may be materialized");
    }

    @Test
    void aReplacementScanOfAFileLiteralIsRefused(@TempDir Path dir) throws Exception {
        Path secret = Files.createDirectories(dir.resolve("outside")).resolve("secret.csv");
        Files.writeString(secret, "k,v\nsecret,42\n");
        JobRun r = run(dir, "SELECT * FROM " + lit(secret));
        assertNotEquals("SUCCESS", r.status(), "FROM '<file>' ran: " + r.message());
    }

    /** The lexical layer on its own: this file IS inside the data root, so the connection seal admits it —
     *  only {@code SqlGuard} knows a file literal in {@code FROM} bypasses the named-sources contract. */
    @Test
    void aFileLiteralInsideTheDataRootIsStillRefusedByTheGuard(@TempDir Path dir) throws Exception {
        JobRun r = run(dir, "SELECT * FROM " + lit(dir.resolve("data").resolve("transactions").resolve("data.parquet")));
        assertNotEquals("SUCCESS", r.status(), "FROM '<parquet under the data root>' ran: " + r.message());
        assertTrue(r.message().contains("refused"), "refused by the guard, not by DuckDB: " + r.message());
    }

    /** The connection layer on its own: the guard is swapped for one that passes everything (the job's test
     *  seam), so only the sealed connection keeps a file outside the data root out of the snapshot. (Until
     *  2026-09-24 this probe used a SQL shape the guard missed — first {@code parquet_metadata(...)}, then a
     *  file literal after a FROM-list comma; {@code SQLGUARD-PARQUET-METADATA-1} and
     *  {@code SQLGUARD-COMMA-RELATION-1} closed both, so the seal is now isolated by disabling the guard.) */
    @Test
    void aFileLiteralTheGuardMissesIsStoppedByTheConnectionSeal(@TempDir Path dir) throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside")).resolve("other.parquet");
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 42 AS secret) TO " + lit(outside) + " (FORMAT PARQUET)");
        }
        String sql = "SELECT b.secret FROM (SELECT 1 AS x) a, " + lit(outside) + " b";
        assertFalse(com.gamma.sql.SqlGuard.check(sql).isEmpty(),
                "the real guard refuses this query (SQLGUARD-COMMA-RELATION-1)");
        var real = SqlTemplateJob.guard;
        SqlTemplateJob.guard = s -> List.of();
        try {
            JobRun r = run(dir, sql);
            assertNotEquals("SUCCESS", r.status(), "a file outside the data root was read: " + r.message());
            assertFalse(r.message().contains("refused:"), "stopped by DuckDB's seal, not a guard: " + r.message());
        } finally {
            SqlTemplateJob.guard = real;
        }
    }

    @Test
    void aSecondStatementCannotWriteOutsideTheJail(@TempDir Path dir) throws Exception {
        Path leak = Files.createDirectories(dir.resolve("outside")).resolve("leak.csv");
        JobRun r = run(dir, "SELECT 1 AS x; COPY (SELECT 42 AS v) TO " + lit(leak) + " (FORMAT CSV)");
        assertFalse(Files.exists(leak), "a smuggled COPY wrote outside the data root: " + leak);
        assertNotEquals("SUCCESS", r.status(), r.message());
    }

    @Test
    void attachingADatabaseOutsideTheJailIsRefused(@TempDir Path dir) throws Exception {
        Path evil = Files.createDirectories(dir.resolve("outside")).resolve("evil.duckdb");
        JobRun r = run(dir, "SELECT 1 AS x; ATTACH " + lit(evil) + " AS evil");
        assertFalse(Files.exists(evil), "ATTACH created a database outside the data root: " + evil);
        assertNotEquals("SUCCESS", r.status(), r.message());
    }

    @Test
    void loadingAnExtensionIsRefused(@TempDir Path dir) throws Exception {
        JobRun r = run(dir, "SELECT 1 AS x; LOAD httpfs");
        assertNotEquals("SUCCESS", r.status(), "LOAD httpfs ran: " + r.message());
    }

    @Test
    void anHttpReadNeverReachesTheNetwork(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/x", ex -> {
            hits.incrementAndGet();
            byte[] body = "k,v\nremote,1\n".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x";
            JobRun r = run(dir, "SELECT * FROM read_csv('" + url + "')");
            assertEquals(0, hits.get(), "the authored SQL reached a network endpoint (" + url + "): " + r.message());
            assertNotEquals("SUCCESS", r.status(), r.message());
        } finally {
            server.stop(0);
        }
    }

    private static boolean hasParquet(Path d) throws Exception {
        try (var s = Files.list(d)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        }
    }
}
