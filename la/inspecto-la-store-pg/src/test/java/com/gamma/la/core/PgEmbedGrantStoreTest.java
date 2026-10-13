package com.gamma.la.core;

import com.gamma.la.store.pg.PgInvestigationStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link EmbedGrantStoreContract} over {@link PgInvestigationStore} on a real PostgreSQL (LA-EMBED-PG-STORE-1), one throwaway schema per case.
 * Same harness and traps as {@link PgInvestigationStoreContractTest}: set {@code INSPECTO_TEST_PG_URL}; with none every case SKIPS, so
 * <b>read "Tests run" and "Skipped"</b>.
 */
class PgEmbedGrantStoreTest extends EmbedGrantStoreContract {

    private static String url() {
        String u = System.getenv("INSPECTO_TEST_PG_URL");
        return u != null && !u.isBlank() ? u : System.getProperty("inspecto.test.pg.url");
    }

    private static com.gamma.util.ConnectionSource pool;
    private final List<String> schemas = new ArrayList<>();

    private static synchronized com.gamma.util.ConnectionSource pool(String url) throws Exception {
        if (pool == null) pool = com.gamma.util.JdbcDrivers.source(url, null, null, "la-embed-test");
        return pool;
    }

    @AfterAll
    static synchronized void closePool() {
        if (pool != null) pool.close();
        pool = null;
    }

    @Override
    InvestigationStore fresh() throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres: set INSPECTO_TEST_PG_URL (jdbc:postgresql://host:5432/db?user=..&password=..) to run this");
        String schema = "la_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schemas.add(schema);
        return new PgInvestigationStore(pool(url), schema);
    }

    @AfterEach
    void dropSchemas() throws Exception {
        if (url() == null) return;
        try (Connection c = DriverManager.getConnection(url()); Statement st = c.createStatement()) {
            for (String s : schemas) st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
        }
        schemas.clear();
    }

    /** Two stores on one schema stand for two pods: a grant minted on one is read, revoked and listed from the other. */
    @Test
    void aGrantMintedOnOnePodIsSeenAndRevokedFromAnother() throws Exception {
        InvestigationStore a = fresh();
        String schema = schemas.get(schemas.size() - 1);
        InvestigationStore b = new PgInvestigationStore(pool(url()), schema);
        assertTrue(a.create("inv-1", "{}"));
        assertTrue(a.createEmbedGrant("inv-1", "Abcdefgh12345678", "{\"v\":1}"));
        assertEquals(Optional.of("{\"v\":1}"), b.embedGrant("Abcdefgh12345678"));
        assertTrue(b.replaceEmbedGrant("Abcdefgh12345678", "{\"v\":2,\"revoked\":true}"));
        assertEquals(List.of("{\"v\":2,\"revoked\":true}"), a.embedGrants("inv-1"));
    }
}
