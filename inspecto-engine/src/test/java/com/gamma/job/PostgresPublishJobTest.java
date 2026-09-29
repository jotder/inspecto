package com.gamma.job;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.Scheduler;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-BI-PUBLICATION-1: {@code publish.postgres} end to end through {@link JobService}. The target is a DuckDB
 * database standing in for Postgres (the same quoted DDL, transactional rename and information_schema); the egress
 * check runs for real, with a stubbed DNS answer. {@code PostgresPublishPgTest} repeats the happy path against a
 * real Postgres when {@code INSPECTO_TEST_PG_URL} names one.
 */
class PostgresPublishJobTest {

    @TempDir Path dir;
    Path cfg, data;
    DuckDBConnection target;
    final AtomicReference<String> dialed = new AtomicReference<>();
    final AtomicReference<java.util.Properties> dialedProps = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        cfg = dir.resolve("config");
        data = dir.resolve("data");
        Files.createDirectories(cfg.resolve("registry"));
        System.setProperty("assist.write.root", cfg.toString());
        target = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + dir.resolve("target.duckdb").toString().replace('\\', '/'));
        PostgresPublishJobType.resolver = h -> new InetAddress[] {InetAddress.getByName("203.0.113.10")};
        PostgresPublishJobType.opener = (url, props) -> { dialed.set(url); dialedProps.set(props); return target.duplicate(); };
        register("BI", "jdbc:postgresql://bi.example.test:5432/bi");
        plant("subs", "SELECT * FROM (VALUES ('m1','gold',10.5,DATE '2026-09-01'),('m2','silver',3.0,DATE '2026-09-01'),"
                + "('m3','gold',7.25,DATE '2026-09-02')) t(msisdn, plan, amount, day)", Map.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("assist.write.root");
        PostgresPublishJobType.resolver = com.gamma.pipeline.exec.EgressPolicy.SYSTEM;
        PostgresPublishJobType.opener = DriverManager::getConnection;
        PostgresPublishJobType.beforeCommit = () -> {};
        PostgresPublishJobType.installAuthority(null);
        ConnectionRegistry.remove("BI");
        target.close();
    }

    static void register(String id, String url) {
        ConnectionRegistry.register(ConnectionProfile.fromMap(Map.of("id", id, "connector", "db", "username", "bi",
                "options", Map.of("jdbc_url", url))));
    }

    void plant(String id, String select, Map<String, Object> extra) throws Exception {
        Path d = data.resolve(id);
        Files.createDirectories(d);
        Files.deleteIfExists(d.resolve("data.parquet"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + d.resolve("data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        Map<String, Object> ds = new HashMap<>(Map.of("physicalRef", id, "description", "Subscribers' daily spend",
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"),
                        Map.of("name", "plan", "description", "Tariff plan (it's the billed one)"),
                        Map.of("name", "amount", "description", "Spend, USD"))));
        ds.putAll(extra);
        new ComponentStore(cfg.resolve("registry")).write("dataset", id, ds);
    }

    JobRun run(Map<String, String> params) throws Exception {
        Map<String, String> p = new HashMap<>(Map.of("connection", "BI", "datasets", "subs", "schema", "bi", "retries", "0"));
        p.putAll(params);
        JobConfig job = new JobConfig("pub", "publish.postgres", null, null, true, false, p, null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            assertTrue(js.triggerRun("pub", null).isPresent());
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline) {
                JobRun r = js.lastRunOf("pub").orElse(null);
                if (r != null && !"RUNNING".equals(r.status())) return r;
                Thread.sleep(50);
            }
        }
        fail("no finished run");
        return null;
    }

    List<List<Object>> query(String sql) throws Exception {
        List<List<Object>> out = new ArrayList<>();
        try (Connection c = target.duplicate(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.add(rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    List<String> columnsOf(String table) throws Exception {
        return query("SELECT column_name FROM information_schema.columns WHERE table_schema = 'bi' AND table_name = '"
                + table + "' ORDER BY ordinal_position").stream().map(r -> (String) r.get(0)).toList();
    }

    static PostgresPublishJobType.Author author(String id, String... caps) {
        return new PostgresPublishJobType.Author(id, Set.of("analyst"), Set.of(caps), false);
    }

    @Test
    void fullRefreshPublishesWithCommentsDialsTheCheckedAddressAndDropsSensitiveColumnsByDefault() throws Exception {
        JobRun r = run(Map.of());
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of("plan", "amount", "day"), columnsOf("subs"), "the MSISDN column is not published");
        assertEquals(3L, ((Number) query("SELECT count(*) FROM bi.subs").get(0).get(0)).longValue());
        assertEquals("jdbc:postgresql://bi.example.test:5432/bi", dialed.get(), "the URL keeps the authored host for TLS");
        java.util.Properties props = dialedProps.get();
        assertEquals("203.0.113.10", props.getProperty(PublishPinnedSocketFactory.PINNED), "the socket dials the checked address");
        assertEquals(PublishPinnedSocketFactory.class.getName(), props.getProperty("socketFactory"));
        assertEquals(PublishSslFactory.class.getName(), props.getProperty("sslfactory"));
        assertEquals("verify-full", props.getProperty("sslmode"), "verify-full by default");
        assertEquals("", props.getProperty("password"), "a password is always set, so ~/.pgpass is never read");
        assertEquals("disable", props.getProperty("gssEncMode"));
        assertEquals("Tariff plan (it's the billed one)", query("SELECT comment FROM duckdb_columns() WHERE "
                + "schema_name='bi' AND table_name='subs' AND column_name='plan'").get(0).get(0));
        assertEquals("Subscribers' daily spend", query("SELECT comment FROM duckdb_tables() WHERE schema_name='bi' "
                + "AND table_name='subs'").get(0).get(0));
        assertTrue(columnsOf("subs__inspecto_stage").isEmpty(), "the stage was renamed away");
    }

    @Test
    void aListedSensitiveColumnIsRefusedUnlessTheAuthorHoldsCanAdminister() throws Exception {
        PostgresPublishJobType.installAuthority(c -> author("bob"));
        JobRun r = run(Map.of("include_sensitive", "subs.msisdn"));
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("does not hold canAdminister"), r.message());
        assertTrue(columnsOf("subs").isEmpty(), "nothing was published");

        PostgresPublishJobType.installAuthority(null);   // open server: no one holds canAdminister
        assertEquals("FAILED", run(Map.of("include_sensitive", "subs.msisdn")).status());

        PostgresPublishJobType.installAuthority(c -> author("root", "canAdminister"));
        JobRun ok = run(Map.of("include_sensitive", "subs.msisdn"));
        assertEquals("SUCCESS", ok.status(), ok.message());
        assertEquals(List.of("msisdn", "plan", "amount", "day"), columnsOf("subs"));
    }

    @Test
    void anAllowlistNamingASensitiveColumnWithoutIncludeSensitiveIsRefused() throws Exception {
        PostgresPublishJobType.installAuthority(c -> author("root", "canAdminister"));
        JobRun r = run(Map.of("columns", "msisdn,plan"));
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("classified MSISDN"), r.message());
        JobRun ok = run(Map.of("columns", "plan"));
        assertEquals("SUCCESS", ok.status(), ok.message());
        assertEquals(List.of("plan"), columnsOf("subs"));
    }

    @Test
    void aDatasetTheAuthorCannotReadIsRefused() throws Exception {
        plant("subs", "SELECT 'm1' AS msisdn, 'gold' AS plan, 1.0 AS amount",
                Map.of("owner", "alice", "shares", List.of(Map.of("subjectType", "role", "subjectId", "finance", "access", "view"))));
        PostgresPublishJobType.installAuthority(c -> author("bob"));
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("may not read it"), r.message());
        assertNull(dialed.get(), "refused before dialling the target");

        PostgresPublishJobType.installAuthority(c -> new PostgresPublishJobType.Author("bob", Set.of("finance"), Set.of(), false));
        assertEquals("SUCCESS", run(Map.of()).status(), "a role share grants the read");
    }

    @Test
    void aLoopbackHostIsRefusedByTheEgressPolicy() throws Exception {
        PostgresPublishJobType.resolver = com.gamma.pipeline.exec.EgressPolicy.SYSTEM;
        register("BI", "jdbc:postgresql://127.0.0.1:5432/bi");
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("loopback"), r.message());
        assertNull(dialed.get(), "nothing was dialled");
    }

    @Test
    void aJdbcUrlWithASecondHostOrAnUnlistedParameterIsRefused() throws Exception {
        register("BI", "jdbc:postgresql://bi.example.test,evil.example.test/bi");
        assertEquals("FAILED", run(Map.of()).status());
        register("BI", "jdbc:postgresql://bi.example.test/bi?socketFactory=x.Y");
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("socketFactory"), r.message());
        assertNull(dialed.get());
    }

    @Test
    void injectionThroughAColumnOrSchemaNameIsRefusedAndTheOldTableSurvives() throws Exception {
        assertEquals("SUCCESS", run(Map.of()).status());
        plant("subs", "SELECT 'gold' AS plan, 1 AS \"x\"\"); DROP TABLE bi.subs; --\"", Map.of());
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("not a safe identifier"), r.message());
        assertEquals(3L, ((Number) query("SELECT count(*) FROM bi.subs").get(0).get(0)).longValue());

        JobRun s = run(Map.of("schema", "bi\"; DROP SCHEMA bi CASCADE; --"));
        assertEquals("FAILED", s.status());
        assertTrue(s.message().contains("not a safe identifier"), s.message());
        assertEquals(3L, ((Number) query("SELECT count(*) FROM bi.subs").get(0).get(0)).longValue());
    }

    @Test
    void aFailureBeforeCommitLeavesTheOldTableIntact() throws Exception {
        assertEquals("SUCCESS", run(Map.of()).status());
        plant("subs", "SELECT * FROM (VALUES ('m9','bronze',1.0,DATE '2026-09-03')) t(msisdn, plan, amount, day)", Map.of());
        PostgresPublishJobType.beforeCommit = () -> { throw new IllegalStateException("injected failure after the swap"); };
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("rolled back"), r.message());
        assertEquals(List.of(List.of("gold"), List.of("gold"), List.of("silver")),
                query("SELECT plan FROM bi.subs ORDER BY plan"), "the old table and its rows are intact");
        assertTrue(columnsOf("subs__inspecto_stage").isEmpty(), "no stage is left behind");
    }

    @Test
    void partitionIncrementalReplacesOnlyChangedAndVanishedPartitions() throws Exception {
        plant("subs", "SELECT * REPLACE (CAST(amount AS DOUBLE) AS amount) FROM (VALUES ('m1','gold',10.5,DATE '2026-09-01'),('m2','silver',3.0,DATE '2026-09-01'),"
                + "('m3','gold',7.25,DATE '2026-09-02')) t(msisdn, plan, amount, day)", Map.of());
        JobRun first = run(Map.of("mode", "partition-incremental", "partition_column", "day"));
        assertEquals("SUCCESS", first.status(), first.message());
        assertEquals(3L, ((Number) query("SELECT count(*) FROM bi.subs").get(0).get(0)).longValue());
        String run1 = (String) query("SELECT run_id FROM bi._inspecto_publication WHERE part = 'v:2026-09-01'").get(0).get(0);

        // 09-01 unchanged, 09-02 changed, 09-03 new
        plant("subs", "SELECT * REPLACE (CAST(amount AS DOUBLE) AS amount) FROM (VALUES ('m1','gold',10.5,DATE '2026-09-01'),('m2','silver',3.0,DATE '2026-09-01'),"
                + "('m3','gold',8.0,DATE '2026-09-02'),('m4','x',1.0,DATE '2026-09-03')) t(msisdn, plan, amount, day)", Map.of());
        JobRun second = run(Map.of("mode", "partition-incremental", "partition_column", "day"));
        assertEquals("SUCCESS", second.status(), second.message());
        assertTrue(second.message().contains("subs=2"), "two rows re-sent (09-02 + 09-03): " + second.message());
        assertEquals(List.of(List.of("2026-09-01", 2L), List.of("2026-09-02", 1L), List.of("2026-09-03", 1L)),
                query("SELECT CAST(day AS VARCHAR), count(*) FROM bi.subs GROUP BY 1 ORDER BY 1"));
        assertEquals(8.0, ((Number) query("SELECT amount FROM bi.subs WHERE day = DATE '2026-09-02'").get(0).get(0)).doubleValue());
        assertEquals(run1, query("SELECT run_id FROM bi._inspecto_publication WHERE part = 'v:2026-09-01'").get(0).get(0),
                "the unchanged partition was not re-published");

        // 09-01 vanishes
        plant("subs", "SELECT * REPLACE (CAST(amount AS DOUBLE) AS amount) FROM (VALUES ('m3','gold',8.0,DATE '2026-09-02'),('m4','x',1.0,DATE '2026-09-03')) "
                + "t(msisdn, plan, amount, day)", Map.of());
        assertEquals("SUCCESS", run(Map.of("mode", "partition-incremental", "partition_column", "day")).status());
        assertEquals(2L, ((Number) query("SELECT count(*) FROM bi.subs").get(0).get(0)).longValue());
    }

    @Test
    void sqlBuildingQuotesEveryIdentifierAndEscapesComments() {
        assertEquals("\"bi\".\"subs\"", PostgresPublishSql.qualified("bi", "subs"));
        assertThrows(IllegalArgumentException.class, () -> PostgresPublishSql.ident("a\"b", "column"));
        assertThrows(IllegalArgumentException.class, () -> PostgresPublishSql.ident("a b", "column"));
        assertThrows(IllegalArgumentException.class, () -> PostgresPublishSql.ident("x".repeat(41), "column"));
        assertEquals("subs_daily_v2", PostgresPublishSql.tableFor("Subs.daily-v2"));
        assertEquals("'it''s \\ ok'", PostgresPublishSql.literal("it's \\ ok"));
        assertEquals(List.of("DROP TABLE IF EXISTS \"bi\".\"t\"",
                "ALTER TABLE \"bi\".\"t__inspecto_stage\" RENAME TO \"t\""), PostgresPublishSql.swap("bi", "t"));
        assertEquals("numeric(18,2)", PostgresPublishSql.pgType("DECIMAL(18,2)"));
        assertNull(PostgresPublishSql.pgType("STRUCT(a INTEGER)"));
    }

    @Test
    void aWeakerSslmodeNeedsInsecureTlsOnTheConnectionAndACanAdministerAuthor() throws Exception {
        register("BI", "jdbc:postgresql://bi.example.test:5432/bi?sslmode=require");
        PostgresPublishJobType.installAuthority(c -> author("root", "canAdminister"));
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("needs verify-full"), r.message());
        assertNull(dialed.get());

        ConnectionRegistry.register(ConnectionProfile.fromMap(Map.of("id", "BI", "connector", "db", "username", "bi",
                "options", Map.of("jdbc_url", "jdbc:postgresql://bi.example.test:5432/bi?sslmode=require", "insecure_tls", "true"))));
        PostgresPublishJobType.installAuthority(c -> author("bob"));
        JobRun notAdmin = run(Map.of());
        assertEquals("FAILED", notAdmin.status());
        assertTrue(notAdmin.message().contains("canAdminister"), notAdmin.message());

        PostgresPublishJobType.installAuthority(c -> author("root", "canAdminister"));
        assertEquals("SUCCESS", run(Map.of()).status());
        assertEquals("require", dialedProps.get().getProperty("sslmode"));
    }

    @Test
    void anSslrootcertThatIsAFilePathIsRefused() throws Exception {
        register("BI", "jdbc:postgresql://bi.example.test:5432/bi?sslrootcert=/etc/ssl/ca.pem");
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("secret reference"), r.message());
        register("BI", "jdbc:postgresql://bi.example.test:5432/bi?sslrootcert=%24%7BSYS:pub.ca%7D");
        assertEquals("SUCCESS", run(Map.of()).status());
        assertEquals("${SYS:pub.ca}", dialedProps.get().getProperty(PublishSslFactory.ROOT_CERT_REF));
        assertNull(dialedProps.get().getProperty("sslrootcert"), "pgjdbc never sees a root-cert path");
    }
}
