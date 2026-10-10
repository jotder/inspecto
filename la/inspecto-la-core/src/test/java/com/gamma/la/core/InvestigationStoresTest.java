package com.gamma.la.core;

import com.gamma.spi.auth.ApiException;
import com.gamma.control.ApiExceptionPeek;
import com.gamma.spi.auth.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code investigations.backend} selection and its fail-closed refusals (design D-IS8, slice S6). No database needed. */
class InvestigationStoresTest {

    private static final List<String> KEYS = List.of(InvestigationStores.BACKEND_PROPERTY, InvestigationStores.URL_PROPERTY,
            InvestigationStores.USER_PROPERTY, InvestigationStores.PASSWORD_PROPERTY, "inspecto.db.url", "inspecto.db.user", "inspecto.db.password");

    @TempDir Path root;

    @AfterEach
    void clean() {
        com.gamma.util.StoreHealth.clearAll();
        KEYS.forEach(System::clearProperty);
        InvestigationStores.forTest(null);
    }

    /** A provider that records what it was asked for and returns a filesystem store (the selection, not the database, is under test). */
    private static final class Recording implements InvestigationStoreProvider {
        String space;
        Connection connection;
        IOException failWith;

        @Override public String backend() { return "db"; }

        @Override
        public InvestigationStore open(String spaceId, Connection c, java.util.function.LongSupplier maxSetBytes,
                                       java.util.function.LongSupplier maxInvestigationBytes) throws IOException {
            if (failWith != null) throw failWith;
            space = spaceId;
            connection = c;
            return new FsInvestigationStore(Path.of("unused"));
        }
    }

    private static void refused(Runnable call, String mentions) {
        ApiException e = assertThrows(ApiException.class, call::run);
        assertEquals(503, ApiExceptionPeek.status(e));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, ApiExceptionPeek.code(e));
        assertTrue(e.getMessage().contains(mentions), e.getMessage());
    }

    @Test
    void theFilesystemIsTheDefaultAndNeverNeedsAProvider() {
        assertInstanceOf(FsInvestigationStore.class, InvestigationStores.of(root));
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, " FS ");
        assertInstanceOf(FsInvestigationStore.class, InvestigationStores.of(root));
    }

    @Test
    void anUnknownBackendIsRefusedNotTreatedAsTheFilesystem() {
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "pg");
        refused(() -> InvestigationStores.of(root), "is not a backend");
    }

    @Test
    void dbWithNoModuleInTheBundleIsRefusedNotServedFromTheFilesystem() {
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "db");
        System.setProperty(InvestigationStores.URL_PROPERTY, "jdbc:postgresql://h/d");
        refused(() -> InvestigationStores.of(root), "carries no database Investigation store");
    }

    @Test
    void dbWithoutAUrlOrWithAnotherDatabasesUrlIsRefused() {
        InvestigationStores.forTest(new Recording());
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "db");
        refused(() -> InvestigationStores.of(root), "needs a connection");
        System.setProperty(InvestigationStores.URL_PROPERTY, "jdbc:duckdb:/tmp/x.db");
        refused(() -> InvestigationStores.of(root), "needs a jdbc:postgresql: URL");
    }

    @Test
    void aDatabaseThatCannotBeUsedIsRefusedWithItsReason() {
        Recording p = new Recording();
        p.failWith = new IOException("connection refused");
        InvestigationStores.forTest(p);
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "db");
        System.setProperty(InvestigationStores.URL_PROPERTY, "jdbc:postgresql://h/d");
        refused(() -> InvestigationStores.of(root), "connection refused");
    }

    @Test
    void theConnectionComesFromTheFamilyKeysThenThePlatformKeysAndTheSpaceFromTheWriteRoot() {
        Recording p = new Recording();
        InvestigationStores.forTest(p);
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "db");
        System.setProperty("inspecto.db.url", "jdbc:postgresql://platform/d");
        System.setProperty("inspecto.db.user", "platform-user");
        System.setProperty("inspecto.db.password", "platform-secret");
        InvestigationStores.of(root.resolve("spaces").resolve("north-region").resolve("config"));
        assertEquals("north-region", p.space);
        assertEquals(new InvestigationStoreProvider.Connection("jdbc:postgresql://platform/d", "platform-user", "platform-secret"), p.connection);

        System.setProperty(InvestigationStores.URL_PROPERTY, "jdbc:postgresql://own/d");
        System.setProperty(InvestigationStores.USER_PROPERTY, "own-user");
        InvestigationStores.of(root);   // a write root that is not a Space's config directory: the legacy single-tenant Space
        assertEquals("default", p.space);
        assertEquals("jdbc:postgresql://own/d", p.connection.url(), "the family key wins");
        assertEquals("own-user", p.connection.user());
        assertEquals("platform-secret", p.connection.password(), "an unset family key falls back per field");
    }

    @Test
    void anUnusableDatabaseIsShownOnHealthDetailsAndWarnedNotRefusedAtBootAndRecoversWithoutARestart() {
        Recording p = new Recording();
        p.failWith = new IOException("connection refused");
        InvestigationStores.forTest(p);
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "db");
        System.setProperty(InvestigationStores.URL_PROPERTY, "jdbc:postgresql://h/d");
        Path spaceRoot = root.resolve("spaces").resolve("north").resolve("config");

        InvestigationStores.probeAtBoot(spaceRoot);   // returns: the boot is not refused
        var down = com.gamma.util.StoreHealth.liveOf("north").get(InvestigationStores.HEALTH_FAMILY);
        assertNotNull(down, "boot recorded a live.investigations entry for the Space");
        assertEquals(com.gamma.util.StoreHealth.Status.DEGRADED, down.status());
        assertTrue(down.detail().contains("connection refused") && down.detail().contains("503"), down.detail());
        assertFalse(down.detail().contains("hunter2"));
        refused(() -> InvestigationStores.of(spaceRoot), "connection refused");   // and every request keeps the fail-closed 503

        p.failWith = null;   // the database came back: nothing was cached, so the next request works with no restart
        assertInstanceOf(FsInvestigationStore.class, InvestigationStores.of(spaceRoot));
        assertEquals(com.gamma.util.StoreHealth.Status.UP, com.gamma.util.StoreHealth.liveOf("north").get(InvestigationStores.HEALTH_FAMILY).status());
    }

    @Test
    void theFilesystemBackendRecordsNoHealthEntryAndTheBootProbeIsANoOp() {
        InvestigationStores.probeAtBoot(root);
        InvestigationStores.of(root);
        assertTrue(com.gamma.util.StoreHealth.liveOf("default").isEmpty());
    }

    @Test
    void theConnectionNeverPrintsItsPassword() {
        String shown = new InvestigationStoreProvider.Connection("jdbc:postgresql://h/d?user=u&password=hunter2", "u", "hunter2").toString();
        assertFalse(shown.contains("hunter2"), shown);
        assertTrue(shown.contains("jdbc:postgresql://h/d"), shown);
    }
}
