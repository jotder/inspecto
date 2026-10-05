package com.gamma.la.core;

import com.gamma.la.store.pg.PgInvestigationStore;
import org.junit.jupiter.api.AfterEach;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link InvestigationStoreTwoJvmRace} over {@link PgInvestigationStore}: two real JVMs, each with its OWN connection pool, on ONE
 * throwaway schema of a real PostgreSQL (the multi-pod case). Needs {@code INSPECTO_TEST_PG_URL} (or {@code -Dinspecto.test.pg.url});
 * with neither, every case SKIPS and says so (read "Tests run" and "Skipped": an exit code of 0 proves nothing). The URL reaches the
 * child JVMs through their ENVIRONMENT, never the command line. Run exactly as {@link PgInvestigationStoreContractTest} describes.
 */
class PgInvestigationStoreTwoJvmRaceTest extends InvestigationStoreTwoJvmRace {

    public static final class Opener implements TwoJvmRaceWorker.Opener {
        @Override
        public InvestigationStore open(String schema) throws Exception {
            String url = url();
            return new PgInvestigationStore(com.gamma.util.JdbcDrivers.source(url, null, null, "la-race"), schema);
        }
    }

    private static String url() {
        String u = System.getenv("INSPECTO_TEST_PG_URL");
        return u != null && !u.isBlank() ? u : System.getProperty("inspecto.test.pg.url");
    }

    private String schema;

    @Override
    Class<? extends TwoJvmRaceWorker.Opener> openerClass() {
        return Opener.class;
    }

    @Override
    String spec() {
        assumeTrue(url() != null, "no Postgres: set INSPECTO_TEST_PG_URL (jdbc:postgresql://host:5432/db?user=..&password=..) to run this");
        if (schema == null) schema = "la_race_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return schema;
    }

    @Override
    Map<String, String> childEnv() {
        return Map.of("INSPECTO_TEST_PG_URL", url());
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (schema == null || url() == null) return;
        try (Connection c = DriverManager.getConnection(url()); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
