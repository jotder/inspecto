package com.gamma.service;

import com.gamma.audit.AuditChain;
import com.gamma.event.DbEventStore;
import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.audit.EventType;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ASSURE-AUDIT-CHAIN-RESIDUALS-1 (2): a {@code DbEventStore} shared by several processes has ONE chain writer —
 * the second refuses (it used to link onto the same head and fork the chain). The Postgres cases need a server
 * ({@code INSPECTO_TEST_PG_URL}) and SKIP PER TEST without one; the DuckDB cases always run.
 */
class DbEventStoreChainWriterTest {

    private static String adminUrl;
    private static String pgUrl;
    private static String pgSchema;

    @BeforeAll
    static void prepareAnIsolatedSchema() throws Exception {
        adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;
        // its own schema: the advisory-lock key is per schema, so a concurrent run on this server cannot interfere
        String schema = "chainw_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        pgSchema = schema;
        pgUrl = adminUrl + (adminUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    @AfterAll
    static void dropTheIsolatedSchema() throws Exception {
        if (pgSchema == null) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + pgSchema + " CASCADE");
        }
    }

    private static void needPg() {
        assumeTrue(pgUrl != null, "needs a PostgreSQL server: set INSPECTO_TEST_PG_URL");
    }

    private static Event audit(String msg) {
        return Event.builder(EventType.AUDIT).source("audit").message(msg).actor("alice")
                .action("pipeline.updated").build();
    }

    // ── DuckDB: unchanged ─────────────────────────────────────────────────────────────────────────

    @Test
    void aDuckDbStoreStillClaimsAndLinksAsBefore(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("events.db").toString().replace('\\', '/');
        try (DbEventStore store = DbEventStore.open(url, null, null)) {
            store.claimChainWriter();
            store.claimChainWriter();   // idempotent
            EventLog log = EventLog.create();
            log.installStore(store);
            log.emit(audit("one"));
            log.emit(audit("two"));
            assertEquals(2, AuditChain.seq(store.chainHead()));
        }
    }

    // ── PostgreSQL: one writer ────────────────────────────────────────────────────────────────────

    @Test
    void aSecondWriterOnASharedPostgresStoreIsRefusedAndTheFirstKeepsWorking() throws Exception {
        needPg();
        try (DbEventStore first = DbEventStore.open(pgUrl, null, null);
             DbEventStore second = DbEventStore.open(pgUrl, null, null)) {
            first.claimChainWriter();
            first.claimChainWriter();   // idempotent for the holder
            IllegalStateException e = assertThrows(IllegalStateException.class, second::claimChainWriter);
            assertTrue(e.getMessage().contains("another process holds the audit chain writer lock"), e.getMessage());
            // and the refusal is not a one-off: it holds on every later link attempt
            assertThrows(IllegalStateException.class, second::claimChainWriter);
            first.claimChainWriter();
        }
    }

    @Test
    void theSecondPodsAuditRowsAreNeverLinkedSoTheChainDoesNotFork() throws Exception {
        needPg();
        try (DbEventStore a = DbEventStore.open(pgUrl, null, null);
             DbEventStore b = DbEventStore.open(pgUrl, null, null)) {
            EventLog logA = EventLog.create();
            logA.installStore(a);
            EventLog logB = EventLog.create();
            logB.installStore(b);
            logA.emit(audit("a1"));
            logA.emit(audit("a2"));
            logB.emit(audit("b1"));   // refused to link: stored marked unlinked, never claims a seq
            List<Event> chain = a.chainPage(1, 100);
            assertEquals(List.of(1L, 2L), chain.stream().map(AuditChain::seq).toList(),
                    "exactly the first writer's rows are chained — no duplicate seq");
            assertTrue(a.unlinkedSince(0) >= 1, "the refused writer's audit row is stored, marked unlinked");
        }
    }

    @Test
    void startupRefusesTheSecondWriterNamingTheSpaceAndTheRemedyAndClosesItsLockConnection() throws Exception {
        needPg();
        try (DbEventStore first = DbEventStore.open(pgUrl, null, null)) {
            first.claimChainWriter();
            DbEventStore second = DbEventStore.open(pgUrl, null, null);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ServiceStores.claimChainWriterOrRefuse("telco-a", second));
            assertTrue(e.getMessage().contains("'telco-a'") && e.getMessage().contains("refuses to start"), e.getMessage());
            assertTrue(e.getMessage().contains("-Devents.backend=parquet"), "names the remedy: " + e.getMessage());
        }
    }

    @Test
    void theLockIsReleasedWithTheStoreSoARestartIsNotWedged() throws Exception {
        needPg();
        try (DbEventStore first = DbEventStore.open(pgUrl, null, null)) {
            first.claimChainWriter();
        }
        try (DbEventStore restarted = DbEventStore.open(pgUrl, null, null)) {
            assertDoesNotThrow(restarted::claimChainWriter, "the lock died with the first store's session");
            assertDoesNotThrow(() -> ServiceStores.claimChainWriterOrRefuse("telco-a", restarted));
        }
    }

    @Test
    void aPostgresStoreWithoutItsDedicatedLockConnectionRefusesRatherThanLinksUnguarded() throws Exception {
        needPg();
        // built from a bare pooled source (not DbEventStore.open): no session to hold a lock on — fail closed
        try (DbEventStore bare = new DbEventStore(JdbcDrivers.source(pgUrl, null, null, "events"))) {
            IllegalStateException e = assertThrows(IllegalStateException.class, bare::claimChainWriter);
            assertTrue(e.getMessage().contains("lock connection"), e.getMessage());
        }
    }
}
