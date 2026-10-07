package com.gamma.service;

import com.gamma.alert.AlertStore;
import com.gamma.alert.DbAlertStore;
import com.gamma.alert.InMemoryAlertStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ServiceStores.openAlertStore} — the {@code ALERTS} family's opener (MODULE-REORG-P7-INCIDENTS slice 2).
 * Pins the persistence trap this family must not fall into: <b>never write operational data into the working
 * directory</b>. A Space gets its own {@code duckdb/} file; the legacy single-tenant root has no directory of its own
 * and so stays in memory (loudly) unless {@code -Dassist.write.root} gives it one; {@code memory} is an explicit
 * opt-out; an explicit {@code db} request with an unusable URL fails loudly.
 */
class AlertStoreWiringTest {

    private static final String[] PROPS = {"alerts.backend", "alerts.db.url", "assist.write.root", "inspecto.db", "inspecto.db.url"};
    private final String[] saved = new String[PROPS.length];

    @BeforeEach
    void clean() {
        for (int i = 0; i < PROPS.length; i++) {
            saved[i] = System.getProperty(PROPS[i]);
            System.clearProperty(PROPS[i]);          // the shipped default is `db`; the reactor's memory pin is cleared here
        }
    }

    @AfterEach
    void restore() {
        for (int i = 0; i < PROPS.length; i++) {
            if (saved[i] == null) System.clearProperty(PROPS[i]);
            else System.setProperty(PROPS[i], saved[i]);
        }
    }

    @Test
    void aSpaceGetsItsOwnDurableFileByDefault(@TempDir Path dir) {
        AlertStore s = ServiceStores.openAlertStore(SpaceRoot.under(dir));
        try {
            assertInstanceOf(DbAlertStore.class, s);
            assertTrue(Files.isRegularFile(dir.resolve("duckdb").resolve("inspecto-alerts.db")));
        } finally {
            s.close();
        }
    }

    @Test
    void theLegacyRootWithNoWriteRootStaysInMemoryAndCreatesNothingInTheWorkingDirectory() {
        Path cwdFile = Path.of("inspecto-alerts.db");
        boolean existed = Files.exists(cwdFile);
        AlertStore s = ServiceStores.openAlertStore(SpaceRoot.legacy());
        assertInstanceOf(InMemoryAlertStore.class, s, "no directory of its own -> memory, never the CWD");
        assertEquals(existed, Files.exists(cwdFile), "no inspecto-alerts.db appeared in the working directory");
    }

    @Test
    void theLegacyRootResolvesUnderItsWriteRootWhenOneIsSet(@TempDir Path writeRoot) {
        System.setProperty("assist.write.root", writeRoot.toString());
        AlertStore s = ServiceStores.openAlertStore(SpaceRoot.legacy());
        try {
            assertInstanceOf(DbAlertStore.class, s);
            assertTrue(Files.isRegularFile(writeRoot.resolve("duckdb").resolve("inspecto-alerts.db")));
        } finally {
            s.close();
        }
    }

    @Test
    void memoryIsAnExplicitOptOutAndNeverTouchesDisk(@TempDir Path dir) {
        System.setProperty("alerts.backend", "memory");
        assertInstanceOf(InMemoryAlertStore.class, ServiceStores.openAlertStore(SpaceRoot.under(dir)));
        assertFalse(Files.exists(dir.resolve("duckdb").resolve("inspecto-alerts.db")));
    }

    @Test
    void anExplicitDbRequestThatCannotBeHonouredFailsLoudly(@TempDir Path dir) {
        System.setProperty("alerts.backend", "db");
        System.setProperty("alerts.db.url", "jdbc:duckdb:" + dir.resolve("no-such-dir").resolve("x").resolve("alerts.db"));
        assertThrows(IllegalStateException.class, () -> ServiceStores.openAlertStore(SpaceRoot.under(dir)));
    }

    @Test
    void theFamilyIsOnTheRosterWithTheDurableDefault() {
        OperationalDb.Resolved r = OperationalDb.resolve(OperationalDb.Family.ALERTS, SpaceRoot.legacy());
        assertTrue(r.enabled(), "the default is db, on every edition");
        assertEquals("alerts.backend", OperationalDb.Family.ALERTS.backendProperty);
    }
}
