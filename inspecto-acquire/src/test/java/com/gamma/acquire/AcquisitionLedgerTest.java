package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The fingerprint ledger contract — find / record / upsert — for both the in-memory and DuckDB backends. */
class AcquisitionLedgerTest {

    /** The shared contract every {@link AcquisitionLedger} must satisfy. */
    private static void contract(AcquisitionLedger ledger) {
        assertTrue(ledger.find("S", "a/b.csv").isEmpty(), "unknown file ⇒ empty");

        ledger.record(new LedgerEntry("S", "a/b.csv", "b.csv", 100, "cs1", "etag-1", "ver-1", 1000L, 5000L, LedgerEntry.PROCESSED));
        Optional<LedgerEntry> got = ledger.find("S", "a/b.csv");
        assertTrue(got.isPresent());
        assertEquals(100, got.get().size());
        assertEquals("cs1", got.get().checksum());
        assertEquals("etag-1", got.get().etag());
        assertEquals("ver-1", got.get().version());

        // upsert: a new fingerprint for the same key replaces the prior one
        ledger.record(new LedgerEntry("S", "a/b.csv", "b.csv", 250, "cs2", "etag-2", null, 2000L, 6000L, LedgerEntry.PROCESSED));
        assertEquals(250, ledger.find("S", "a/b.csv").orElseThrow().size());
        assertEquals("cs2", ledger.find("S", "a/b.csv").orElseThrow().checksum());
        assertEquals("etag-2", ledger.find("S", "a/b.csv").orElseThrow().etag());
        assertNull(ledger.find("S", "a/b.csv").orElseThrow().version());

        // keyed by (sourceId, relativePath): a different source does not collide
        assertTrue(ledger.find("OTHER", "a/b.csv").isEmpty());

        dbWatermarkContract(ledger);
        renameSourceContract(ledger);
    }

    /** A pipeline rename's identity migration (T3, plan §3.2): fingerprints AND the DB watermark move together. */
    private static void renameSourceContract(AcquisitionLedger ledger) {
        ledger.record(new LedgerEntry("rn-old", "x.csv", "x.csv", 10, "cs", null, null, 100L, 200L, LedgerEntry.PROCESSED));
        ledger.recordDbWatermark("rn-old", "wm-1");

        ledger.renameSource("rn-old", "rn-new");

        assertTrue(ledger.find("rn-old", "x.csv").isEmpty(), "old source id no longer resolves");
        assertEquals(10, ledger.find("rn-new", "x.csv").orElseThrow().size(), "fingerprint moved to the new id");
        assertTrue(ledger.dbWatermark("rn-old").isEmpty(), "old watermark key no longer resolves");
        assertEquals("wm-1", ledger.dbWatermark("rn-new").orElseThrow(), "watermark moved to the new id");

        // a target id already holding its own row is untouched by an unrelated rename
        ledger.record(new LedgerEntry("keep", "y.csv", "y.csv", 5, "cs2", null, null, 1L, 2L, LedgerEntry.PROCESSED));
        ledger.renameSource("rn-new", "rn-final");
        assertEquals(5, ledger.find("keep", "y.csv").orElseThrow().size());
    }

    /** The row-level DB-export watermark contract: empty until recorded, then read-back + upsert, keyed per source. */
    private static void dbWatermarkContract(AcquisitionLedger ledger) {
        assertTrue(ledger.dbWatermark("db-src").isEmpty(), "unknown source ⇒ no watermark");

        ledger.recordDbWatermark("db-src", "2020-04-03 00:00:00");
        assertEquals("2020-04-03 00:00:00", ledger.dbWatermark("db-src").orElseThrow());

        // upsert: a newer watermark replaces the prior one for the same source key
        ledger.recordDbWatermark("db-src", "2020-04-04 00:00:00");
        assertEquals("2020-04-04 00:00:00", ledger.dbWatermark("db-src").orElseThrow());

        // keyed per source: a different source key is independent
        assertTrue(ledger.dbWatermark("other-src").isEmpty());
    }

    @Test
    void inMemory() {
        try (AcquisitionLedger ledger = new InMemoryAcquisitionLedger()) {
            contract(ledger);
        }
    }

    @Test
    void duckDb(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("ledger.db").toString().replace('\\', '/');
        try (AcquisitionLedger ledger = DbAcquisitionLedger.open(url, null, null)) {
            contract(ledger);
        }
    }

    /** ACQ-7 in-place migration: a ledger DB created before the etag/version columns gains them on open. */
    @Test
    void duckDbMigratesPreEtagSchema(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("old-ledger.db").toString().replace('\\', '/');
        try (java.sql.Connection c = com.gamma.util.JdbcDrivers.connect(url);
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TABLE inspecto_acquisition_ledger ("
                    + "source_id VARCHAR, relative_path VARCHAR, name VARCHAR, size BIGINT, "
                    + "checksum VARCHAR, last_modified BIGINT, processed_at BIGINT, status VARCHAR, "
                    + "PRIMARY KEY (source_id, relative_path))");
            st.execute("INSERT INTO inspecto_acquisition_ledger VALUES "
                    + "('S', 'old.csv', 'old.csv', 42, 'cs', 1000, 5000, 'PROCESSED')");
        }
        try (AcquisitionLedger ledger = DbAcquisitionLedger.open(url, null, null)) {
            LedgerEntry old = ledger.find("S", "old.csv").orElseThrow();
            assertEquals(42, old.size());
            assertNull(old.etag(), "pre-migration row reads back a null etag");
            assertNull(old.version(), "pre-migration row reads back a null version");
            ledger.record(new LedgerEntry("S", "new.csv", "new.csv", 7, null, "e", "v", 1L, 2L, LedgerEntry.PROCESSED));
            assertEquals("e", ledger.find("S", "new.csv").orElseThrow().etag());
        }
    }

    /**
     * A replace that dies between the DELETE and the INSERT keeps the OLD fingerprint (postgres-multi-user
     * plan, P0/F1).
     *
     * <p>⚠ The bug this pins is not a failed update, it is a <b>lost</b> one. Under autocommit the DELETE
     * committed on its own, so an error in the INSERT half erased the fingerprint entirely — and a file with
     * no ledger row is a file the next acquisition cycle treats as NEW and re-ingests, duplicating its records
     * downstream. Failing loudly while leaving the prior fingerprint intact is the safe outcome.
     *
     * <p>⚠ Deliberately NOT a concurrency test: {@code synchronized} plus the single shared connection already
     * serialise callers, so the interleaving the plan describes cannot happen until a pool lands. The crash /
     * exception window is the half that is live today, so that is what is injected here.
     */
    @Test
    void aFailedReplaceRollsBackAndKeepsThePriorFingerprint(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("atomic.db").toString().replace('\\', '/');
        try (java.sql.Connection real = com.gamma.util.JdbcDrivers.connect(url)) {
            DbAcquisitionLedger ledger = new DbAcquisitionLedger(real);
            ledger.record(new LedgerEntry("S", "a.csv", "a.csv", 100, "cs1", "etag-1", "ver-1", 1000L, 5000L,
                    LedgerEntry.PROCESSED));

            // A connection whose INSERT half always fails — the crash window, made deterministic.
            java.sql.Connection failing = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.Connection.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                    (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                                && String.valueOf(args[0]).startsWith("INSERT INTO inspecto_acquisition_ledger")) {
                            throw new java.sql.SQLException("injected failure in the INSERT half");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException ite) {
                            throw ite.getCause();
                        }
                    });

            DbAcquisitionLedger brittle = new DbAcquisitionLedger(failing);
            assertThrows(IllegalStateException.class, () -> brittle.record(
                    new LedgerEntry("S", "a.csv", "a.csv", 250, "cs2", "etag-2", "ver-2", 2000L, 6000L,
                            LedgerEntry.PROCESSED)),
                    "a half-applied replace must fail loudly, not silently");

            LedgerEntry survived = ledger.find("S", "a.csv").orElseThrow(
                    () -> new AssertionError("the fingerprint was DELETED and never re-INSERTed — "
                            + "this file would now re-ingest as new"));
            assertEquals(100, survived.size(), "the prior fingerprint must be intact, not the failed one");
            assertEquals("cs1", survived.checksum());

            assertTrue(real.getAutoCommit(),
                    "autocommit must be restored — every other method on this shared connection assumes it");
        }
    }
}
