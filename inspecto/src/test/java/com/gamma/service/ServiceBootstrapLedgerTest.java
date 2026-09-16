package com.gamma.service;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The legacy (single-tenant / CLI) space's acquisition ledger resolves its URL through {@link OperationalDb},
 * exactly as a per-space one does in {@link SpaceBootstrap}.
 *
 * <p>Regression for {@code ACQUIRE-LEDGER-SHARED-URL-1}: before the fix the legacy space was the only space
 * that never registered a ledger, so {@link AcquisitionLedgers#shared()} built one from its own lazy
 * resolution — which has no {@code -Dinspecto.db.url} step and falls back to a working-directory-relative
 * file. A single-tenant deployment pointed at a shared operational database kept its dedup ledger somewhere
 * else entirely, silently.
 */
class ServiceBootstrapLedgerTest {

    private static final String[] NO_CONFIGS = new String[0];

    @AfterEach
    void dropDefaultSpaceLedger() {
        AcquisitionLedgers.unregister(EventLog.DEFAULT_SPACE_ID);
        System.clearProperty("acquire.ledger.backend");
        System.clearProperty("acquire.ledger.db.url");
        System.clearProperty("inspecto.db");
        System.clearProperty("inspecto.db.url");
        System.clearProperty("status.backend");
    }

    @Test
    void theLegacySpaceLedgerHonoursTheSharedOperationalUrl(@TempDir Path tmp) throws Exception {
        Path shared = tmp.resolve("shared-ops.db");
        System.setProperty("status.backend", "file");          // keep this test off the status projection
        System.setProperty("acquire.ledger.backend", "db");
        System.setProperty("inspecto.db", "postgres");          // what makes OperationalDb.url() answer at all
        System.setProperty("inspecto.db.url", "jdbc:duckdb:" + shared);

        try (CollectorService svc = ServiceBootstrap.buildFrom(SpaceRoot.legacy(), NO_CONFIGS, false)) {
            assertNotNull(svc, "an empty legacy bootstrap still builds");
            assertTrue(Files.exists(shared),
                    "the legacy space's ledger opened the SHARED operational URL — the lazy path in "
                            + "AcquisitionLedgers.shared() has no -Dinspecto.db.url step and would have "
                            + "opened a working-directory-relative inspecto-acquisition.db instead");
        }
    }

    @Test
    void aPerFamilyUrlStillWinsOverTheSharedOne(@TempDir Path tmp) throws Exception {
        Path shared = tmp.resolve("shared-ops.db");
        Path family = tmp.resolve("ledger-only.db");
        System.setProperty("status.backend", "file");
        System.setProperty("acquire.ledger.backend", "db");
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", "jdbc:duckdb:" + shared);
        System.setProperty("acquire.ledger.db.url", "jdbc:duckdb:" + family);

        try (CollectorService svc = ServiceBootstrap.buildFrom(SpaceRoot.legacy(), NO_CONFIGS, false)) {
            assertNotNull(svc);
            assertTrue(Files.exists(family), "the per-family escape hatch is unchanged by the fix");
            assertFalse(Files.exists(shared), "and it wins over the shared operational URL");
        }
    }

    @Test
    void aPerSpaceRootDoesNotRegisterHere(@TempDir Path tmp) throws Exception {
        // SpaceBootstrap registers the ledger for a real space AFTER buildFrom returns; registering again
        // here would open a second handle and leak the first. Only the legacy root (config() == null) is ours.
        Path base = tmp.resolve("space-x");
        Files.createDirectories(base.resolve("config"));
        System.setProperty("status.backend", "file");
        System.setProperty("acquire.ledger.backend", "db");
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", "jdbc:duckdb:" + tmp.resolve("shared-ops.db"));

        SpaceRoot root = SpaceRoot.under(base);
        try (CollectorService svc = ServiceBootstrap.buildFrom(root, new String[]{root.config().toString()}, false)) {
            assertNotNull(svc);
            assertFalse(Files.exists(tmp.resolve("shared-ops.db")),
                    "a per-space root opens no ledger in buildFrom — SpaceBootstrap owns that registration");
        }
    }
}
