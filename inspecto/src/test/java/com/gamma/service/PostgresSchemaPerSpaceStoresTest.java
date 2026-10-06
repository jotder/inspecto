package com.gamma.service;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.DbDedupLedger;
import com.gamma.consignment.DbFileStageStore;
import com.gamma.consignment.FileStage;
import com.gamma.consignment.FileStageRecord;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.etl.TestConfigs;
import com.gamma.event.DbEventStore;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventType;
import com.gamma.notify.DbDeliveryReceiptStore;
import com.gamma.notify.DeliveryReceipt;
import com.gamma.notify.DeliveryStatus;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.pipeline.exec.ProvenanceRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two-Space probe on <b>real PostgreSQL</b>, schema-per-space, for the families the "Postgres multi-user" row still
 * owed: status, provenance, dedup ledger, events, file stages, consignment outputs and delivery receipts. Each test
 * writes the SAME keys into Space A and Space B concurrently (one store per Space, as two Spaces in one process
 * would) and asserts each Space reads back only its own rows. Skips PER TEST without {@code INSPECTO_TEST_PG_URL} /
 * {@code -Dinspecto.test.pg.url}; throwaway schemas, dropped afterwards.
 */
class PostgresSchemaPerSpaceStoresTest {

    private static String adminUrl, urlA, urlB;
    private static String schemaA, schemaB;

    @BeforeAll
    static void connect() throws Exception {
        adminUrl = System.getProperty("inspecto.test.pg.url");
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;
        String tag = Long.toHexString(System.nanoTime());
        schemaA = OperationalDb.schemaFor("pgstores-a-" + tag);
        schemaB = OperationalDb.schemaFor("pgstores-b-" + tag);
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schemaA);
            s.execute("CREATE SCHEMA " + schemaB);
        }
        urlA = OperationalDb.withSchema(adminUrl, schemaA);
        urlB = OperationalDb.withSchema(adminUrl, schemaB);
    }

    @AfterAll
    static void drop() throws Exception {
        if (schemaA == null) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schemaA + " CASCADE");
            s.execute("DROP SCHEMA IF EXISTS " + schemaB + " CASCADE");
        }
    }

    @BeforeEach
    void requireServer() {
        assumeTrue(urlA != null, "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -Dinspecto.test.pg.url");
    }

    /** Runs {@code body} for Space A and Space B at the same instant, rethrowing either failure. */
    private static void bothAtOnce(SpaceWrite body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> fa = pool.submit((Callable<Void>) () -> { go.await(); body.write(urlA, "A"); return null; });
            Future<Void> fb = pool.submit((Callable<Void>) () -> { go.await(); body.write(urlB, "B"); return null; });
            go.countDown();
            fa.get();
            fb.get();
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface SpaceWrite { void write(String url, String space) throws Exception; }

    @Test
    void dedupLedger_sameKeysAreClaimedIndependentlyPerSpace() throws Exception {
        LocalDate window = LocalDate.of(2026, 10, 6);
        List<String> hashes = List.of(DbDedupLedger.hash(List.of("k", "1")), DbDedupLedger.hash(List.of("k", "2")));
        bothAtOnce((url, s) -> {
            try (DbDedupLedger l = new DbDedupLedger(DriverManager.getConnection(url))) {
                assertEquals(2, l.claim("orders", window, "C-" + s, hashes).size(),
                        "Space " + s + " wins every key — the other Space's claim is not visible here");
                assertEquals(0, l.claim("orders", window, "C2-" + s, hashes).size(), "a re-claim wins nothing");
                assertEquals(2L, l.size(), "Space " + s + " holds only its own two keys");
            }
        });
    }

    @Test
    void provenance_isolatedPerSpace() throws Exception {
        bothAtOnce((url, s) -> {
            try (DbProvenanceStore st = DbProvenanceStore.open(url)) {
                st.record(List.of(new ProvenanceRow("flow-1", "batch-1", "parse", "data-" + s, 1, "2026-10-06T00:00:00Z")));
            }
        });
        for (String[] p : new String[][] {{urlA, "A"}, {urlB, "B"}}) {
            try (DbProvenanceStore st = DbProvenanceStore.open(p[0])) {
                List<Map<String, Object>> rows = st.query("flow-1", "batch-1");
                assertEquals(1, rows.size(), "Space " + p[1] + " sees one row: " + rows);
                assertTrue(rows.get(0).toString().contains("data-" + p[1]), rows.toString());
            }
        }
    }

    @Test
    void events_isolatedPerSpace() throws Exception {
        bothAtOnce((url, s) -> {
            try (DbEventStore st = DbEventStore.open(url, null, null)) {
                for (int i = 0; i < 20; i++)
                    st.append(new Event("EVT-" + i, 1_000L + i, EventLevel.INFO, EventType.AUDIT,
                            "com.gamma.Src", "orders", "corr", s, Map.of(), Map.of()));
            }
        });
        for (String[] p : new String[][] {{urlA, "A"}, {urlB, "B"}}) {
            try (DbEventStore st = DbEventStore.open(p[0], null, null)) {
                assertEquals(20L, st.count(), "Space " + p[1] + " counts only its own events");
                assertTrue(st.recent(50).stream().allMatch(e -> p[1].equals(e.message())));
            }
        }
    }

    @Test
    void fileStages_isolatedPerSpace() throws Exception {
        bothAtOnce((url, s) -> {
            try (DbFileStageStore st = DbFileStageStore.open(url)) {
                st.record(List.of(new FileStageRecord("sftp", "a.csv", "b-" + s, FileStage.REGISTERED,
                        "2026-10-06T00:00:00Z")));
            }
        });
        for (String[] p : new String[][] {{urlA, "A"}, {urlB, "B"}}) {
            try (DbFileStageStore st = DbFileStageStore.open(p[0])) {
                List<FileStageRecord> got = st.stages("sftp", "a.csv");
                assertEquals(1, got.size(), "Space " + p[1] + ": " + got);
                assertEquals("b-" + p[1], got.get(0).batchId());
            }
        }
    }

    @Test
    void consignmentOutputs_isolatedPerSpace() throws Exception {
        bothAtOnce((url, s) -> {
            try (DbConsignmentOutputStore st = DbConsignmentOutputStore.open(url)) {
                st.record(List.of(new ConsignmentOutput("cons-1", "run-" + s, "cdr", "day=2026-10-06", "2026-10-06",
                        "/data/" + s + "/part-0.parquet", 1L, 1L, "2026-10-06T00:00:00Z", ConsignmentOutput.State.LIVE)));
            }
        });
        for (String[] p : new String[][] {{urlA, "A"}, {urlB, "B"}}) {
            try (DbConsignmentOutputStore st = DbConsignmentOutputStore.open(p[0])) {
                List<ConsignmentOutput> got = st.outputs("cons-1");
                assertEquals(1, got.size(), "Space " + p[1] + ": " + got);
                assertEquals("run-" + p[1], got.get(0).runId());
            }
        }
    }

    @Test
    void deliveryReceipts_isolatedPerSpace() throws Exception {
        bothAtOnce((url, s) -> {
            try (DbDeliveryReceiptStore st = DbDeliveryReceiptStore.open(url, null, null)) {
                st.add(new DeliveryReceipt("DLV-1", "NOTIF-1", "email", s + "@example.com",
                        1_000L, Map.of(DeliveryStatus.BOUNCED_HARD, 1_000L), "550", false));
            }
        });
        for (String[] p : new String[][] {{urlA, "A"}, {urlB, "B"}}) {
            try (DbDeliveryReceiptStore st = DbDeliveryReceiptStore.open(p[0], null, null)) {
                assertEquals(1, st.forNotification("NOTIF-1").size(), "Space " + p[1]);
                assertEquals(Set.of(p[1] + "@example.com"), Set.copyOf(st.targetsWithStatus(DeliveryStatus.BOUNCED_HARD)),
                        "suppression in Space " + p[1] + " must not see the other Space's bounce");
            }
        }
    }

    @Test
    void status_isolatedPerSpace(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write().toString());
        bothAtOnce((url, s) -> {
            StatusStore source = new StatusStore() {
                @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of("b-" + s); }
                @Override public List<Map<String, String>> batches(PipelineConfig c) {
                    return List.of(Map.of("consignment_id", "b-" + s, "status", "SUCCESS"));
                }
                @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(Map.of("file", s + ".csv")); }
                @Override public List<Map<String, String>> lineage(PipelineConfig c, String b) { return List.of(); }
                @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
            };
            try (DbStatusStore db = DbStatusStore.open(url, null, null)) {
                db.sync(source, List.of(cfg));
            }
        });
        for (String[] p : new String[][] {{urlA, "A"}, {urlB, "B"}}) {
            try (DbStatusStore db = DbStatusStore.open(p[0], null, null)) {
                assertEquals(Set.of("b-" + p[1]), db.committedBatches(cfg), "Space " + p[1]);
                assertEquals(1, db.files(cfg).size(), "Space " + p[1]);
            }
        }
    }
    /**
     * The connection budget (operator decision 2026-10-06): ONE shared pool per process. Forty stores over twenty
     * Spaces were forty pools (at least forty idle server connections); now the process holds at most
     * {@code -Ddb.pool.process.size} (default 20), and a Space's schema never leaks onto the next borrower.
     */
    @Test
    void manySpacesAndFamilies_shareOneBoundedPool_andTheSchemaNeverLeaks() throws Exception {
        List<AutoCloseable> stores = new java.util.ArrayList<>();
        int before = serverConnections();   // other classes in this fork may hold connections already
        try {
            for (int i = 0; i < 20; i++) {
                String url = OperationalDb.withSchema(adminUrl, i % 2 == 0 ? schemaA : schemaB);
                // ⚠ Not DbEventStore: on PostgreSQL each one ALSO holds a dedicated, never-pooled advisory-lock
                // connection for its life (the chain writer), deliberately outside the pool budget.
                DbProvenanceStore pv = DbProvenanceStore.open(url);
                stores.add(pv);
                DbFileStageStore fs = DbFileStageStore.open(url);
                stores.add(fs);
                pv.query("f", "b");
                fs.stages("x", "y");
            }
            int added = serverConnections() - before;
            assertTrue(added <= 20, "40 stores over 20 Spaces must add at most the process pool, added " + added);
            // A schema-less view on the same server must see the server default, not the last Space's schema.
            try (com.gamma.util.ConnectionSource none = com.gamma.util.JdbcDrivers.source(adminUrl, null, null, "events")) {
                for (int i = 0; i < 5; i++) {
                    String path = none.with(c -> {
                        try (Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery("SHOW search_path")) {
                            rs.next();
                            return rs.getString(1);
                        }
                    });
                    assertFalse(path.contains(schemaA) || path.contains(schemaB), "search_path leaked: " + path);
                }
            }
        } finally {
            for (AutoCloseable c : stores) c.close();
        }
    }
    private static int serverConnections() throws Exception {
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity"
                     + " WHERE datname = current_database() AND pid <> pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
