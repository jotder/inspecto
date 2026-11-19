package com.gamma.inspector;

import com.gamma.acquire.CollectorConnectors;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.RemoteFile;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code postgres} Collector scheme ({@code BASIC-ELT-PROFILE-1} BE-4). The profile checks every Connection save runs
 * need no database and run everywhere; the copy runs against a REAL Postgres when {@code INSPECTO_TEST_PG_URL} (a superuser
 * JDBC URL) and {@code INSPECTO_PG_TEST_PASSWORD} (the password the test gives its two roles) are set, and is skipped
 * otherwise - read "Skipped". Same variables as {@code ControlApiPostgresSnapshotTest}. Synthetic fixtures only.
 */
class PostgresConnectorFactoryTest {

    private static final String PASSWORD_ENV = "INSPECTO_PG_TEST_PASSWORD";
    private static final String RO = "scheme_pg_ro", RW = "scheme_pg_rw";
    private static final String REF = "${ENV:INSPECTO_PG_SALES}";

    private static ConnectionProfile profile(String password, Map<String, String> options) {
        return new ConnectionProfile("sales", "postgres", "db.example", 5432, "sales", null, "reader", password, options, null, null);
    }

    private static Map<String, String> table() {
        return Map.of("schema", "public", "table", "transfers");
    }

    private static String refusal(ConnectionProfile p) {
        return assertThrows(IllegalArgumentException.class, () -> new PostgresConnectorFactory().validate(p)).getMessage();
    }

    // ── the profile, as every Connection save checks it ─────────────────────────────────────────────────────────

    @Test
    void aTableOrAQueryWithAnInspectoPgReferenceIsAccepted() {
        new PostgresConnectorFactory().validate(profile(REF, table()));
        new PostgresConnectorFactory().validate(profile(REF, Map.of("query", "WITH a AS (SELECT 1 AS x) SELECT x FROM a")));
        // no port: the Postgres default
        new PostgresConnectorFactory().validate(new ConnectionProfile("p", "postgres", "h", 0, "d", null, "u", REF, table(), null, null));
    }

    @Test
    void onlyAnInspectoPgVariableMayBeNamedSoNoOtherServerSecretCanBeSentToAChosenHost() {
        // PATH is always set: were it accepted, its value would go to the host the Connection names
        for (String ref : List.of("${ENV:PATH}", "${ENV:JAVA_HOME}", "${SYS:user.home}", "${ENV:inspecto_pg_lower}", "${ENV:INSPECTO_PG_}",
                "${FILE:/etc/passwd}")) {
            String why = refusal(profile(ref, table()));
            assertTrue(why.contains(PostgresCollectorConnector.ENV_PREFIX), ref + ": " + why);
        }
    }

    @Test
    void aRawPasswordIsRefusedAndNeverEchoed() {
        String raw = "Sup3r-Secret-Value";
        String why = refusal(profile(raw, table()));
        assertTrue(why.contains("${ENV:"), why);
        assertFalse(why.contains(raw), why);
        assertTrue(refusal(profile(null, table())).contains(PostgresCollectorConnector.ENV_PREFIX));
    }

    /** The registration itself: the save seam finds the scheme on the classpath and its refusal reaches the caller. */
    @Test
    void theSaveSeamFindsTheSchemeAndRefusesThroughIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CollectorConnectors.validate(profile("${ENV:PATH}", table())));
        assertTrue(e.getMessage().contains(PostgresCollectorConnector.ENV_PREFIX), e.getMessage());
        CollectorConnectors.validate(profile(REF, table()));
    }

    @Test
    void anythingTheSchemeCannotReadIsRefusedNotHalfUsed() {
        ConnectionProfile tunnel = new ConnectionProfile("t", "postgres", "h", 5432, "d", null, "u", REF, table(),
                new ConnectionProfile.Tunnel("bastion", 22, "u", REF), null);
        assertTrue(refusal(tunnel).contains("tunnel"));
        ConnectionProfile proxy = new ConnectionProfile("p", "postgres", "h", 5432, "d", null, "u", REF, table(), null,
                new ConnectionProfile.Proxy("SOCKS5", "px", 1080, null, null));
        assertTrue(refusal(proxy).contains("proxy"));
        Map<String, String> extra = new HashMap<>(table());
        extra.put("jdbc_url", "jdbc:postgresql://elsewhere/x");
        assertTrue(refusal(profile(REF, extra)).contains("jdbc_url"));
        assertTrue(refusal(profile(REF, Map.of())).contains("options.table"), "a Collector needs to know what to copy");
        assertTrue(refusal(profile(REF, Map.of("schema", "public", "table", "t\"; drop", "query", "SELECT 1"))).contains("not both"));
        assertTrue(refusal(profile(REF, Map.of("schema", "public", "table", "t\"; drop"))).contains("options.table"));
        for (String q : List.of("DELETE FROM t", "SELECT 1; DROP TABLE t", "COPY t TO STDOUT"))
            refusal(profile(REF, Map.of("query", q)));
        refusal(new ConnectionProfile("h", "postgres", "bad host!", 5432, "d", null, "u", REF, table(), null, null));
        refusal(new ConnectionProfile("h", "postgres", "h", 70_000, "d", null, "u", REF, table(), null, null));
        refusal(new ConnectionProfile("h", "postgres", "h", 5432, null, null, "u", REF, table(), null, null));
    }

    @Test
    void oneExportIsDiscoveredPerCycleNamedAfterTheTable() throws Exception {
        try (PostgresCollectorConnector c = PostgresConnectorFactory.connector(profile(REF, table()), 0)) {
            List<RemoteFile> found = c.discover(new DiscoveryContext(List.of(), List.of(), -1));
            assertEquals(1, found.size());
            assertEquals("transfers.parquet", found.get(0).relativePath());
            assertEquals(com.gamma.acquire.CollectorConnector.Readiness.READY, c.readiness(found.get(0)));
        }
        assertEquals("query.parquet", PostgresConnectorFactory.connector(profile(REF, Map.of("query", "SELECT 1")), 0).fileName());
    }

    @Test
    void anUnsetReferenceIsNamedNotGuessed(@TempDir Path dir) {
        String var = "INSPECTO_PG_SURELY_UNSET_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        PostgresCollectorConnector.Refused r = assertThrows(PostgresCollectorConnector.Refused.class,
                () -> PostgresConnectorFactory.connector(profile("${ENV:" + var + "}", table()), 0).export(dir.resolve("t.parquet")));
        assertTrue(r.getMessage().contains(var) && r.getMessage().contains("not set"), r.getMessage());
        assertFalse(r.tooLarge());
    }

    // ── against a real Postgres ─────────────────────────────────────────────────────────────────────────────────

    private record Pg(String jdbc, String host, int port, String db, String schema) {
        void exec(String... sql) throws Exception {
            try (Connection c = DriverManager.getConnection(jdbc); Statement st = c.createStatement()) {
                for (String s : sql) st.execute(s);
            }
        }

        long count() throws Exception {
            try (Connection c = DriverManager.getConnection(jdbc); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM " + schema + ".transfers")) {
                rs.next();
                return rs.getLong(1);
            }
        }

        ConnectionProfile profile(String user, Map<String, String> options) {
            return new ConnectionProfile("pg-test", "postgres", host, port, db, null, user, "${ENV:" + PASSWORD_ENV + "}", options, null, null);
        }

        Map<String, String> table() {
            return Map.of("schema", schema, "table", "transfers");
        }
    }

    private static Pg seeded() throws Exception {
        String url = System.getenv("INSPECTO_TEST_PG_URL");
        String pw = System.getenv(PASSWORD_ENV);
        assumeTrue(url != null && pw != null, "no Postgres: set INSPECTO_TEST_PG_URL (a superuser JDBC URL) and " + PASSWORD_ENV);
        URI u = URI.create(url.substring("jdbc:".length()));
        Pg pg = new Pg(url, u.getHost(), u.getPort() < 0 ? 5432 : u.getPort(), u.getPath().substring(1),
                "pgs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        String lit = "'" + pw.replace("'", "''") + "'";
        pg.exec("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RO + "') THEN CREATE ROLE " + RO + " LOGIN; END IF; "
                        + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RW + "') THEN CREATE ROLE " + RW + " LOGIN; END IF; END $$",
                "ALTER ROLE " + RO + " PASSWORD " + lit, "ALTER ROLE " + RW + " PASSWORD " + lit,
                "CREATE SCHEMA " + pg.schema,
                "CREATE TABLE " + pg.schema + ".transfers (id bigint primary key, amount numeric(12,2), sent_at timestamptz, ok boolean, "
                        + "ref uuid, tags text[])",
                "INSERT INTO " + pg.schema + ".transfers SELECT g, g * 1.5, timestamptz '2026-01-01 00:00:00+00' + g * interval '1 hour', "
                        + "g % 2 = 0, md5(g::text)::uuid, ARRAY['t' || g] FROM generate_series(1, 40) g",
                "GRANT USAGE ON SCHEMA " + pg.schema + " TO " + RO + ", " + RW,
                "GRANT SELECT ON ALL TABLES IN SCHEMA " + pg.schema + " TO " + RO,
                "GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA " + pg.schema + " TO " + RW);
        return pg;
    }

    @Test
    void theSessionItselfIsReadOnlyEvenForALoginThatCouldWrite(@TempDir Path dir) throws Exception {
        Pg pg = seeded();
        try (PostgresCollectorConnector.Session s = PostgresCollectorConnector.Session.open(
                PostgresConnectorFactory.connector(pg.profile(RW, pg.table()), 0), dir)) {
            for (String write : List.of("INSERT INTO pg." + pg.schema + ".transfers (id) VALUES (999)",
                    "CALL postgres_execute('pg', 'INSERT INTO " + pg.schema + ".transfers (id) VALUES (998)')")) {
                SQLException refused = assertThrows(SQLException.class, () -> {
                    try (Statement st = s.conn.createStatement()) {
                        st.execute(write);
                    }
                }, write);
                assertTrue(refused.getMessage().contains("read-only") || refused.getMessage().contains("read only"), refused.getMessage());
            }
            assertEquals(40, pg.count(), "nothing was written");
        } finally {
            pg.exec("DROP SCHEMA IF EXISTS " + pg.schema + " CASCADE");
        }
    }

    /** The Collector path: discover, then fetchTo, gives typed Parquet; a write-capable login is refused there too. */
    @Test
    void aCollectorCycleFetchesTheTableAsTypedParquet(@TempDir Path dir) throws Exception {
        Pg pg = seeded();
        try {
            try (PostgresCollectorConnector c = (PostgresCollectorConnector) new PostgresConnectorFactory()
                    .create(null, pg.profile(RO, pg.table()))) {
                RemoteFile f = c.discover(new DiscoveryContext(List.of(), List.of(), -1)).get(0);
                Path out = c.fetchTo(f, dir.resolve("landed").resolve(f.relativePath()));
                Map<String, String> types = new LinkedHashMap<>();
                long rows;
                try (Connection duck = DuckDbUtil.openInMemory(null, List.of(dir), null); Statement st = duck.createStatement()) {
                    try (ResultSet r = st.executeQuery("DESCRIBE SELECT * FROM " + com.gamma.sql.SqlViews.parquetFile(out))) {
                        while (r.next()) types.put(r.getString(1), r.getString(2));
                    }
                    try (ResultSet r = st.executeQuery("SELECT count(*) FROM " + com.gamma.sql.SqlViews.parquetFile(out))) {
                        r.next();
                        rows = r.getLong(1);
                    }
                }
                assertEquals(40, rows);
                assertEquals("BIGINT", types.get("id"), types.toString());
                assertEquals("TIMESTAMP", types.get("sent_at"), "timestamptz arrives as its UTC time");
                assertEquals("BOOLEAN", types.get("ok"));
                assertEquals("VARCHAR", types.get("ref"), "a uuid arrives as text");
                assertEquals("VARCHAR", types.get("tags"), "an array arrives as text");
            }
            try (PostgresCollectorConnector rw = PostgresConnectorFactory.connector(pg.profile(RW, pg.table()), 0)) {
                RemoteFile f = rw.discover(new DiscoveryContext(List.of(), List.of(), -1)).get(0);
                com.gamma.acquire.AcquisitionException e = assertThrows(com.gamma.acquire.AcquisitionException.class,
                        () -> rw.fetchTo(f, dir.resolve("rw").resolve(f.relativePath())));
                assertTrue(e.getMessage().contains(RW) && e.getMessage().contains("read-only"), e.getMessage());
                assertFalse(e.getMessage().contains(System.getenv(PASSWORD_ENV)), "the password is never in an error");
            }
            PostgresCollectorConnector.Refused big = assertThrows(PostgresCollectorConnector.Refused.class,
                    () -> PostgresConnectorFactory.connector(pg.profile(RO, pg.table()), 39).export(dir.resolve("cap").resolve("t.parquet")));
            assertTrue(big.tooLarge() && big.limit() == 39, big.getMessage());
        } finally {
            pg.exec("DROP SCHEMA IF EXISTS " + pg.schema + " CASCADE");
        }
    }
}
