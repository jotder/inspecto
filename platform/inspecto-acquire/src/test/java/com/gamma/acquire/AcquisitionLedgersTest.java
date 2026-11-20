package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.gamma.event.EventLog;

import static org.junit.jupiter.api.Assertions.*;

/** The process-wide accessor resolves one {@link AcquisitionLedger} per space ({@link EventLog#currentSpaceId()}). */
class AcquisitionLedgersTest {

    @Test
    void ledgersAreIsolatedPerSpaceMdc() {
        AcquisitionLedger a = new InMemoryAcquisitionLedger();
        AcquisitionLedger b = new InMemoryAcquisitionLedger();
        try {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            AcquisitionLedgers.use(a);
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            AcquisitionLedgers.use(b);

            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            assertSame(a, AcquisitionLedgers.shared(), "space-a sees its own ledger");
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            assertSame(b, AcquisitionLedgers.shared(), "space-b sees its own ledger");

            // A fresh space lazily builds its own (the reactor pins backend = memory), distinct from a and b.
            MDC.put(EventLog.SPACE_MDC_KEY, "space-c");
            AcquisitionLedger c = AcquisitionLedgers.shared();
            assertNotSame(a, c);
            assertNotSame(b, c);
            assertSame(c, AcquisitionLedgers.shared(), "same space resolves to the same lazily-built ledger");
        } finally {
            // Drop the test spaces' entries; never touch the default space other tests share.
            for (String s : new String[]{"space-a", "space-b", "space-c"}) {
                MDC.put(EventLog.SPACE_MDC_KEY, s);
                AcquisitionLedgers.use(null);
            }
            MDC.remove(EventLog.SPACE_MDC_KEY);
        }
    }

    /**
     * BASIC-ELT-PROFILE-1 B3 part 2 (operator 2026-11-20): with {@code -Dacquire.ledger.backend} unset the ledger is the
     * durable database on every edition, so a fingerprint outlives a restart; {@code memory} only when asked for.
     */
    @Test
    void theUnsetBackendIsTheDurableDatabaseAndMemoryIsAnOptIn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        String pinned = System.getProperty("acquire.ledger.backend");   // the reactor pins memory (root pom)
        java.nio.file.Path file = dir.resolve("ledger.db");
        try {
            System.clearProperty("acquire.ledger.backend");
            AcquisitionLedger durable = AcquisitionLedgers.build("jdbc:duckdb:" + file, "b3-default");
            try {
                assertInstanceOf(DbAcquisitionLedger.class, durable, "unset: the database");
                durable.record(new LedgerEntry("C", "a.csv", "a.csv", 1, "abc", null, null, 1, 1, LedgerEntry.PROCESSED));
            } finally {
                durable.close();
            }
            AcquisitionLedger reopened = AcquisitionLedgers.build("jdbc:duckdb:" + file, "b3-default");
            try {
                assertEquals("a.csv", reopened.findByChecksum("C", "abc").orElseThrow().relativePath(), "kept across a reopen");
            } finally {
                reopened.close();
            }
            System.setProperty("acquire.ledger.backend", "memory");
            assertInstanceOf(InMemoryAcquisitionLedger.class, AcquisitionLedgers.build("jdbc:duckdb:" + file, "b3-default"));
        } finally {
            if (pinned == null) System.clearProperty("acquire.ledger.backend");
            else System.setProperty("acquire.ledger.backend", pinned);
        }
    }
}
