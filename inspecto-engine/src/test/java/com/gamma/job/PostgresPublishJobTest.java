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
        destinations("bi.example.test");
        System.setProperty("assist.write.root", cfg.toString());
        target = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + dir.resolve("target.duckdb").toString().replace('\\', '/'));
        PostgresPublishJobType.resolver = h -> new InetAddress[] {InetAddress.getByName("203.0.113.10")};
        PostgresPublishJobType.opener = (url, props) -> { dialed.set(url); dialedProps.set(props); return target.duplicate(); };
        register("BI", "jdbc:postgresql://bi.example.test:5432/bi");
        PostgresPublishJobType.installApprovalVerifier((root, rec) -> "nonce-1".equals(rec.get("nonce")) && "pc-1".equals(rec.get("pendingChange")));
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
        PostgresPublishJobType.installApprovalVerifier(null);
        ConnectionRegistry.remove("BI");
        target.close();
    }

    void destinations(String... hosts) throws Exception {
        Files.writeString(cfg.resolve(PublicationDestinations.FILE), dev.toonformat.jtoon.JToon.encode(Map.of("hosts", List.of(hosts))));
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

    /** Whether {@link #run} records a four-eyes approval of the Job as it is now (what the approve route does). */
    boolean approve = true;

    void approve(JobConfig job) throws Exception {
        PublicationApproval.record(cfg, job.name(), PublicationApproval.fingerprints(job.toMap(), cfg, ConnectionRegistry::find),
                "checker-1", Set.of("canApproveChanges"), "nonce-1", "pc-1");
    }

    JobRun run(Map<String, String> params) throws Exception {
        return run(params, null);
    }

    JobRun run(Map<String, String> params, Map<String, String> triggerArgs) throws Exception {
        Map<String, String> p = new HashMap<>(Map.of("connection", "BI", "datasets", "subs", "schema", "bi", "retries", "0"));
        p.putAll(params);
        JobConfig job = new JobConfig("pub", "publish.postgres", null, null, true, false, p, null, null);
        if (approve) approve(job);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            assertTrue((triggerArgs == null ? js.triggerRun("pub", null) : js.triggerRun("pub", null, triggerArgs)).isPresent());
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
        destinations("127.0.0.1");   // listed, so the EGRESS policy is what refuses it
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
    void aWeakerSslmodeNeedsInsecureTlsOnTheApprovedConnection() throws Exception {
        register("BI", "jdbc:postgresql://bi.example.test:5432/bi?sslmode=require");
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("needs verify-full"), r.message());
        assertNull(dialed.get());

        ConnectionRegistry.register(ConnectionProfile.fromMap(Map.of("id", "BI", "connector", "db", "username", "bi",
                "options", Map.of("jdbc_url", "jdbc:postgresql://bi.example.test:5432/bi?sslmode=require", "insecure_tls", "true"))));
        assertEquals("SUCCESS", run(Map.of()).status(), "approved WITH insecure_tls (the approver needed canAdminister)");
        assertEquals("require", dialedProps.get().getProperty("sslmode"));
    }

    @Test
    void insecureTlsSetAfterTheApprovalNeverTakesEffect() throws Exception {
        assertEquals("SUCCESS", run(Map.of()).status());   // approved on a verify-full Connection
        ConnectionRegistry.register(ConnectionProfile.fromMap(Map.of("id", "BI", "connector", "db", "username", "bi",
                "options", Map.of("jdbc_url", "jdbc:postgresql://bi.example.test:5432/bi?sslmode=disable", "insecure_tls", "true"))));
        dialed.set(null);
        approve = false;
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("the approved publication changed (connection)"), r.message());
        assertNull(dialed.get(), "nothing was dialled");
    }

    @Test
    void aConnectionEditAfterApprovalRefusesTheRun() throws Exception {
        assertEquals("SUCCESS", run(Map.of()).status());
        approve = false;
        ConnectionRegistry.register(ConnectionProfile.fromMap(Map.of("id", "BI", "connector", "db", "username", "someone-else",
                "options", Map.of("jdbc_url", "jdbc:postgresql://bi.example.test:5432/bi"))));
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("the approved publication changed (connection); re-approve"), r.message());
        register("BI", "jdbc:postgresql://bi.example.test:5432/other_db");
        assertTrue(run(Map.of()).message().contains("(connection)"), "a different database is a different publication");
    }

    @Test
    void aDatasetDefinitionEditAfterApprovalRefusesTheRun() throws Exception {
        assertEquals("SUCCESS", run(Map.of()).status());
        approve = false;
        new ComponentStore(cfg.resolve("registry")).write("dataset", "subs", Map.of("physicalRef", "subs",
                "columns", List.of(Map.of("name", "msisdn"))));   // the classification dropped
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("the approved publication changed (dataset subs)"), r.message());
    }

    @Test
    void strippingAClassificationFromASiblingDatasetRefusesTheRun() throws Exception {
        new ComponentStore(cfg.resolve("registry")).write("dataset", "subs2", Map.of("physicalRef", "subs"));
        JobRun ok = run(Map.of("datasets", "subs2"));
        assertEquals("SUCCESS", ok.status(), ok.message());
        assertEquals(List.of("plan", "amount", "day"), columnsOf("subs2"), "msisdn stays out through subs's catalog");
        new ComponentStore(cfg.resolve("registry")).write("dataset", "subs", Map.of("physicalRef", "subs",
                "columns", List.of(Map.of("name", "msisdn"))));   // the sibling's classification stripped
        approve = false;
        JobRun r = run(Map.of("datasets", "subs2"));
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("the approved publication changed (dataset subs2)"), r.message());
    }

    @Test
    void anApprovalNotBoundToAnApprovedPendingChangeIsRefused() throws Exception {
        PostgresPublishJobType.installApprovalVerifier(null);
        JobRun none = run(Map.of());
        assertEquals("FAILED", none.status(), "no verifier installed: nothing can vouch for the approval");
        assertTrue(none.message().contains("not bound to an approved Pending Change"), none.message());
        PostgresPublishJobType.installApprovalVerifier((root, rec) -> false);
        assertEquals("FAILED", run(Map.of()).status());
        assertNull(dialed.get());
    }

    @Test
    void forgetAndTheLoadSweepRemoveApprovals() throws Exception {
        assertEquals("SUCCESS", run(Map.of()).status());
        assertTrue(PublicationApproval.approved(cfg, "pub").isPresent());
        PublicationApproval.sweep(cfg, Set.of("pub"));
        assertTrue(PublicationApproval.approved(cfg, "pub").isPresent(), "a live Job keeps its approval");
        PublicationApproval.sweep(cfg, Set.of());
        assertTrue(PublicationApproval.approved(cfg, "pub").isEmpty(), "a Job gone at load loses it");
        approve = false;
        assertEquals("FAILED", run(Map.of()).status(), "so an identical re-created Job needs a new approval");
        approve = true;
        run(Map.of());
        PublicationApproval.forget(cfg, "pub");
        assertTrue(PublicationApproval.approved(cfg, "pub").isEmpty());
    }

    @Test
    void aJobThatWasNeverApprovedOrWhoseParamsChangedIsRefused() throws Exception {
        approve = false;
        JobRun never = run(Map.of());
        assertEquals("FAILED", never.status());
        assertTrue(never.message().contains("never approved"), never.message());
        approve = true;
        assertEquals("SUCCESS", run(Map.of()).status());
        approve = false;
        JobRun edited = run(Map.of("mode", "partition-incremental", "partition_column", "day"));
        assertEquals("FAILED", edited.status());
        assertTrue(edited.message().contains("(job params)"), edited.message());
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

    @Test
    void aHostThatIsNotAPublicationDestinationIsRefusedEvenWhenEgressWouldAllowIt() throws Exception {
        destinations();
        JobRun r = run(Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("not a publication destination"), r.message());
        assertNull(dialed.get());
        destinations("other.example.test");
        assertEquals("FAILED", run(Map.of()).status(), "exact host only");
    }

    @Test
    void aTriggerMayNotOverrideTheApprovedConnectionDatasetsOrSensitiveColumns() throws Exception {
        for (String k : List.of("connection", "datasets", "include_sensitive", "schema", "columns", "mode",
                "partition_column", "timeout_seconds", "retries")) {
            JobRun r = run(Map.of(), Map.of(k, k.equals("mode") ? "partition-incremental" : "9"));
            assertEquals("FAILED", r.status(), k);
            assertTrue(r.message().contains("comes from the approved Job only"), r.message());
        }
        assertNull(dialed.get());
        assertEquals("SUCCESS", run(Map.of(), Map.of("retries", "0")).status(), "the approved value itself may be given");
    }

    @Test
    void classificationMatchesCaseInsensitivelyOnTheNameAndTheClass() throws Exception {
        new ComponentStore(cfg.resolve("registry")).write("dataset", "subs", Map.of("physicalRef", "subs",
                "columns", List.of(Map.of("name", "MSISDN", "classification", " msisdn "))));
        assertEquals("SUCCESS", run(Map.of()).status());
        assertEquals(List.of("plan", "amount", "day"), columnsOf("subs"), "MSISDN/msisdn is the same column");
        JobRun r = run(Map.of("columns", "msisdn,plan"));
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("classified MSISDN"), r.message());
    }

    @Test
    void aSecondUnclassifiedDatasetOverTheSameStoreInheritsTheClassificationByName() throws Exception {
        new ComponentStore(cfg.resolve("registry")).write("dataset", "subs2", Map.of("physicalRef", "subs"));
        JobRun r = run(Map.of("datasets", "subs2"));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of("plan", "amount", "day"), columnsOf("subs2"), "msisdn stays out through the sibling's catalog");
    }

    @Test
    void aViewOrVirtualDatasetOverAClassifiedStoreNeedsTheWholeDatasetReleased() throws Exception {
        new ComponentStore(cfg.resolve("registry")).write("dataset", "renamed", Map.of("sourceName", "subs",
                "sql", "SELECT msisdn AS m, plan FROM subs"));
        PostgresPublishJobType.installAuthority(c -> author("bob"));
        JobRun r = run(Map.of("datasets", "renamed"));
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("cannot be traced"), r.message());
        assertTrue(columnsOf("renamed").isEmpty(), "msisdn AS m was not published");

        assertEquals("FAILED", run(Map.of("datasets", "renamed", "include_sensitive", "renamed.*")).status(),
                "releasing the whole Dataset still needs canAdminister");
        PostgresPublishJobType.installAuthority(c -> author("root", "canAdminister"));
        JobRun ok = run(Map.of("datasets", "renamed", "include_sensitive", "renamed.*"));
        assertEquals("SUCCESS", ok.status(), ok.message());
        assertEquals(List.of("m", "plan"), columnsOf("renamed"));
    }

    // ── ASSURE-CLASSIFICATION-PROPAGATION-1: a pipeline schema's classification follows its mapping ──────────────

    /** A pipeline "cust" whose schema classifies raw MSISDN, with the given mapping rules and extra pipeline lines,
     *  and a Dataset over it that itself classifies nothing. {@code cols} are the stored column names. */
    void custStore(String cols, String mappingFields, String extraPipeline) throws Exception {
        Files.createDirectories(cfg.resolve("cust"));
        Files.writeString(cfg.resolve("cust/cust_pipeline.toon"), "name: cust\nactive: true\n\ndirs:\n"
                + "  poll: data/inbox/cust\n  database: data/cust/database\n  backup: data/cust/backup\n"
                + "  temp: data/cust/temp\n  errors: data/cust/errors\n  quarantine: data/cust/quarantine\n"
                + "  markers: data/cust/markers\n  status_dir: data/cust/status\n  log_dir: data/cust/logs\n\n"
                + "output:\n  format: PARQUET\n  compression: snappy\n\nprocessing:\n  threads: 1\n"
                + "  file_pattern: \"glob:**/*.csv\"\n  schema_file: cust_schema.toon\n" + extraPipeline);
        Files.writeString(cfg.resolve("cust/cust_schema.toon"), "partitionKey: DAY\nraw:\n  name: CUST\n  format: CSV\n"
                + "  fields[3]{name,selector,type,description,unit,classification}:\n"
                + "    MSISDN,\"0\",VARCHAR,\"\",\"\",\"MSISDN\"\n    PLAN,\"1\",VARCHAR,\"\",\"\",\"\"\n"
                + "    DAY,\"2\",DATE,\"\",\"\",\"\"\nmapping:\n  canonicalName: cust\n  rawName: CUST\n" + mappingFields);
        String sel = String.join(", ", java.util.Arrays.stream(cols.split(", *"))
                .map(c -> c.equals("day") ? "DATE '2026-09-01' AS day" : "'x' AS " + c).toList());
        plant("cust", "SELECT " + sel + " UNION ALL SELECT " + sel.replace("'x'", "'y'"), Map.of());
        new ComponentStore(cfg.resolve("registry")).write("dataset", "cust", Map.of("physicalRef", "cust"));
    }

    static final String KEEP_RENAMED = "  fields[3]:\n    - name: m\n      from: MSISDN\n      fn: keep\n"
            + "    - name: plan\n      from: PLAN\n      fn: keep\n    - name: day\n      from: DAY\n      fn: keep\n";

    @Test
    void aRenameThroughAPipelineMappingCarriesTheRawClassificationToThePublishedColumn() throws Exception {
        custStore("m, plan, day", KEEP_RENAMED, "");
        JobRun r = run(Map.of("datasets", "cust"));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of("plan", "day"), columnsOf("cust"), "m is MSISDN under another name");
    }

    @Test
    void aDatasetsOwnLaxerClassDoesNotOverrideTheStricterLineageClass() throws Exception {
        // strictest wins (operator 2026-10-04): the Dataset labels m INTERNAL, its lineage says MSISDN -> sensitive
        custStore("m, plan, day", KEEP_RENAMED, "");
        new ComponentStore(cfg.resolve("registry")).write("dataset", "cust", Map.of("physicalRef", "cust",
                "columns", List.of(Map.of("name", "m", "classification", "INTERNAL"))));
        JobRun r = run(Map.of("datasets", "cust"));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of("plan", "day"), columnsOf("cust"), "m stays MSISDN despite the laxer own label");
    }

    @Test
    void aDatasetsOwnUnsensitiveLabelOnAnUnclassifiedLineagePublishes() throws Exception {
        // negative twin: no classified input behind plan, so its own INTERNAL label leaves it published
        custStore("m, plan, day", KEEP_RENAMED, "");
        new ComponentStore(cfg.resolve("registry")).write("dataset", "cust", Map.of("physicalRef", "cust",
                "columns", List.of(Map.of("name", "plan", "classification", "INTERNAL"))));
        JobRun r = run(Map.of("datasets", "cust"));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of("plan", "day"), columnsOf("cust"));
    }

    @Test
    void aHashOrSubstringOfAClassifiedRawColumnStaysSensitive() throws Exception {
        custStore("h, s, plan, day", "  fields[4]:\n    - name: h\n      from: \"\"\n      fn: custom\n      args:\n"
                + "        expression: \"md5(MSISDN)\"\n    - name: s\n      from: \"\"\n      fn: custom\n      args:\n"
                + "        expression: \"substr(msisdn, 1, 3)\"\n    - name: plan\n      from: PLAN\n      fn: keep\n"
                + "    - name: day\n      from: DAY\n      fn: keep\n", "");
        JobRun r = run(Map.of("datasets", "cust"));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of("plan", "day"), columnsOf("cust"));
    }

    @Test
    void anAmbiguousLineageRefusesUnlessTheWholeDatasetIsReleased() throws Exception {
        // a summarize step rewrites the columns, so the mapping no longer says what the stored columns are
        custStore("m, plan, day", KEEP_RENAMED, "steps[1]:\n  - summarize:\n      group_by: [plan]\n");
        PostgresPublishJobType.installAuthority(c -> author("bob"));
        JobRun r = run(Map.of("datasets", "cust"));
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("cannot be traced"), r.message());
        assertEquals("FAILED", run(Map.of("datasets", "cust", "include_sensitive", "cust.*")).status(),
                "releasing the whole Dataset still needs canAdminister");
        PostgresPublishJobType.installAuthority(c -> author("root", "canAdminister"));
        JobRun ok = run(Map.of("datasets", "cust", "include_sensitive", "cust.*"));
        assertEquals("SUCCESS", ok.status(), ok.message());
        assertEquals(List.of("m", "plan", "day"), columnsOf("cust"));
    }

    @Test
    void anUnreadablePipelineForTheStoreRefusesTheDataset() throws Exception {
        custStore("m, plan, day", KEEP_RENAMED, "");
        Files.writeString(cfg.resolve("cust/cust_pipeline.toon"), "name: [broken\n  : :");
        PostgresPublishJobType.installAuthority(c -> author("bob"));
        JobRun r = run(Map.of("datasets", "cust"));
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("cannot be traced"), r.message());
    }

    @Test
    void aMappingThatCannotBeReadRefusesTheDataset() throws Exception {
        custStore("m, plan, day", "  fields: oops\n", "");
        PostgresPublishJobType.installAuthority(c -> author("bob"));
        assertEquals("FAILED", run(Map.of("datasets", "cust")).status());
    }

    @Test
    void aJobOutputStoreNoPipelineClaimsDoesNotInheritThePipelineClassification() throws Exception {
        custStore("m, plan, day", KEEP_RENAMED, "");
        plant("custrollup", "SELECT * FROM (VALUES ('m1',1),('m2',2)) t(m, n)", Map.of());
        new ComponentStore(cfg.resolve("registry")).write("dataset", "custrollup", Map.of("physicalRef", "custrollup"));
        assertEquals("SUCCESS", run(Map.of("datasets", "custrollup")).status());
        assertEquals(List.of("m", "n"), columnsOf("custrollup"), "an aggregate or Job output does not inherit");
    }

    @Test
    void aClassifiedRenameCanBeReleasedByNameForACanAdministerAuthor() throws Exception {
        custStore("m, plan, day", KEEP_RENAMED, "");
        PostgresPublishJobType.installAuthority(c -> author("root", "canAdminister"));
        JobRun r = run(Map.of("datasets", "cust", "include_sensitive", "cust.m"));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of("m", "plan", "day"), columnsOf("cust"));
    }
}
