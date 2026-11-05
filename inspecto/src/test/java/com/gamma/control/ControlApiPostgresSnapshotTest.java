package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code BASIC-ONBOARD-1} P3 over real HTTP: Choose a table. The gates and the refusals that need no database run
 * everywhere; the rest runs against a REAL Postgres (the driver is never faked) when two variables are set, and is
 * skipped otherwise - read "Tests run" and "Skipped":
 * <ul>
 *   <li>{@code INSPECTO_TEST_PG_URL} - a superuser JDBC URL, {@code jdbc:postgresql://host:5432/db?user=..&password=..}
 *       (the convention of the la-store-pg tests); the test seeds its own schema and two roles with it;</li>
 *   <li>{@code INSPECTO_PG_TEST_PASSWORD} - the password the test gives those roles: Add data only takes a
 *       {@code ${ENV:…}} reference, so the value must be in the server's environment.</li>
 * </ul>
 * Synthetic fixtures only.
 */
class ControlApiPostgresSnapshotTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader", ANALYST = "Bearer analyst", ADMIN = "Bearer admin";
    private static final String PASSWORD_ENV = "INSPECTO_PG_TEST_PASSWORD";
    private static final String RO = "onboard_snap_ro", RW = "onboard_snap_rw";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Authenticators.forTest(null);
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.POST(BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> put(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.PUT(BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private static String conn(String host, int port, String db, String user, String password) throws Exception {
        return JSON.writeValueAsString(Map.of("host", host, "port", port, "database", db, "user", user, "password", password));
    }

    private static String shaped() throws Exception {
        return conn("127.0.0.1", 5432, "nowhere", RO, "${ENV:" + PASSWORD_ENV + "}");
    }

    // ── refusals before any connection (no database needed) ─────────────────────────────────────────────────

    @Test
    void aRawPasswordIsRefusedAndNeverEchoed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String raw = "Sup3r-Secret-Value";
            for (String path : List.of("/onboard/postgres/test", "/onboard/postgres")) {
                HttpResponse<String> r = post(c, path, "{\"connection\":" + conn("127.0.0.1", 5432, "db", RO, raw)
                        + ",\"schema\":\"s\",\"table\":\"t\"}", null);
                assertEquals(422, r.statusCode(), path + " " + r.body());
                assertTrue(error(r).contains("${ENV:"), r.body());
                assertFalse(r.body().contains(raw), "the value is never answered: " + r.body());
            }
        }
    }

    @Test
    void onlyAnInspectoPgVariableIsEverResolvedSoNoOtherServerSecretCanBeSentToAChosenHost(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            // PATH is always set: were it resolved, its value would go to the host the caller chose
            for (String ref : List.of("${ENV:PATH}", "${ENV:JAVA_HOME}", "${SYS:user.home}", "${ENV:inspecto_pg_lower}", "${ENV:INSPECTO_PG_}")) {
                HttpResponse<String> r = post(c, "/onboard/postgres/test",
                        "{\"connection\":" + conn("127.0.0.1", 5432, "db", RO, ref) + "}", null);
                assertEquals(422, r.statusCode(), ref + " " + r.body());
                assertTrue(error(r).contains(PostgresSnapshot.ENV_PREFIX), r.body());
                assertFalse(error(r).contains("not set"), "refused by its name, before anything is resolved: " + r.body());
            }
        }
    }

    @Test
    void anUnsetReferenceIsNamedNotGuessed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String var = "INSPECTO_PG_SURELY_UNSET_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            HttpResponse<String> r = post(c, "/onboard/postgres/test",
                    "{\"connection\":" + conn("127.0.0.1", 5432, "db", RO, "${ENV:" + var + "}") + "}", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(error(r).contains(var) && error(r).contains("not set"), r.body());
        }
    }

    @Test
    void aMalformedConnectionOrAWriteQueryIsRefusedBeforeConnecting(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, post(c, "/onboard/postgres/test", "{}", null).statusCode());
            assertEquals(422, post(c, "/onboard/postgres/test", "{\"connection\":" + conn("bad host!", 5432, "db", RO, "${ENV:X}") + "}", null).statusCode());
            assertEquals(422, post(c, "/onboard/postgres/test", "{\"connection\":" + conn("h", 70000, "db", RO, "${ENV:X}") + "}", null).statusCode());
            for (String q : List.of("DELETE FROM t", "SELECT 1; DROP TABLE t", "UPDATE t SET a = 1 RETURNING a", "COPY t TO STDOUT")) {
                HttpResponse<String> r = post(c, "/onboard/postgres", "{\"connection\":" + shaped() + ",\"query\":" + JSON.writeValueAsString(q) + "}", null);
                assertEquals(422, r.statusCode(), q + " " + r.body());
                assertTrue(error(r).contains("SELECT") || error(r).contains("one statement"), q + " " + r.body());
            }
            HttpResponse<String> both = post(c, "/onboard/postgres", "{\"connection\":" + shaped()
                    + ",\"query\":\"SELECT 1\",\"table\":\"t\"}", null);
            assertEquals(422, both.statusCode(), both.body());
            HttpResponse<String> ident = post(c, "/onboard/postgres", "{\"connection\":" + shaped()
                    + ",\"schema\":\"public\",\"table\":\"t\\\"; drop\"}", null);
            assertEquals(422, ident.statusCode(), ident.body());
        }
    }

    @Test
    void theRoutesNeedAWriteRootSelfImportAndCanUploadData(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, post(c, "/onboard/postgres/test", "{\"connection\":" + shaped() + "}", null).statusCode());
            assertEquals(503, post(c, "/onboard/postgres", "{\"connection\":" + shaped() + "}", null).statusCode());
        }
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case UPLOADER -> Optional.of(new Subject("uploader-1", Set.of("canUploadData")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canAuthorWorkbench")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            data(put(c, "/settings/data", "{\"selfImport\":true}", ADMIN), 200);   // off by default once people sign in
            for (String path : List.of("/onboard/postgres/test", "/onboard/postgres")) {
                assertEquals(401, post(c, path, "{\"connection\":" + shaped() + "}", null).statusCode(), path);
                assertEquals(403, post(c, path, "{\"connection\":" + shaped() + "}", ANALYST).statusCode(), path);
                // an uploader passes the capability gate and reaches the body checks
                assertEquals(422, post(c, path, "{}", UPLOADER).statusCode(), path);
            }
            data(put(c, "/settings/data", "{\"selfImport\":false}", ADMIN), 200);
            for (String path : List.of("/onboard/postgres/test", "/onboard/postgres")) {
                HttpResponse<String> off = post(c, path, "{\"connection\":" + shaped() + "}", UPLOADER);
                assertEquals(403, off.statusCode(), off.body());
            }
        }
    }

    // ── against a real Postgres ─────────────────────────────────────────────────────────────────────────────

    /** The superuser fixture: where it is, and a fresh schema holding {@code transfers}. */
    private record Pg(String jdbc, String host, int port, String db, String schema) {
        Connection open() throws Exception {
            return DriverManager.getConnection(jdbc);
        }

        void exec(String... sql) throws Exception {
            try (Connection c = open(); Statement st = c.createStatement()) {
                for (String s : sql) st.execute(s);
            }
        }

        String connection(String user) throws Exception {
            return conn(host, port, db, user, "${ENV:" + PASSWORD_ENV + "}");
        }
    }

    private static Pg seeded() throws Exception {
        String url = System.getenv("INSPECTO_TEST_PG_URL");
        String pw = System.getenv(PASSWORD_ENV);
        assumeTrue(url != null && pw != null, "no Postgres: set INSPECTO_TEST_PG_URL (a superuser JDBC URL) and "
                + PASSWORD_ENV + " to run this");
        URI u = URI.create(url.substring("jdbc:".length()));
        String db = u.getPath().substring(1);
        Pg pg = new Pg(url, u.getHost(), u.getPort() < 0 ? 5432 : u.getPort(), db,
                "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        String lit = "'" + pw.replace("'", "''") + "'";
        pg.exec("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RO + "') THEN CREATE ROLE " + RO + " LOGIN; END IF; "
                        + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RW + "') THEN CREATE ROLE " + RW + " LOGIN; END IF; END $$",
                "ALTER ROLE " + RO + " PASSWORD " + lit, "ALTER ROLE " + RW + " PASSWORD " + lit,
                "CREATE SCHEMA " + pg.schema,
                "CREATE TABLE " + pg.schema + ".transfers (id bigint primary key, sender text, receiver text, amount numeric(12,2), "
                        + "sent_at timestamptz, ok boolean, ref uuid, tags text[])",
                "INSERT INTO " + pg.schema + ".transfers SELECT g, 'acct' || (g % 7), 'acct' || (g % 5), g * 1.5, "
                        + "timestamptz '2026-01-01 00:00:00+00' + g * interval '1 hour', g % 2 = 0, "
                        + "md5(g::text)::uuid, ARRAY['t' || g] FROM generate_series(1, 40) g",
                "GRANT USAGE ON SCHEMA " + pg.schema + " TO " + RO + ", " + RW,
                "GRANT SELECT ON ALL TABLES IN SCHEMA " + pg.schema + " TO " + RO,
                "GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA " + pg.schema + " TO " + RW);
        return pg;
    }

    private static void drop(Pg pg) throws Exception {
        pg.exec("DROP SCHEMA IF EXISTS " + pg.schema + " CASCADE");
    }

    @Test
    void aWriteCapableLoginIsRefusedAndAReadOnlyOneListsItsTables(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> rw = post(c, "/onboard/postgres/test", "{\"connection\":" + pg.connection(RW) + "}", null);
            assertEquals(422, rw.statusCode(), rw.body());
            assertTrue(error(rw).contains(RW) && error(rw).contains("read-only"), rw.body());
            // the copy refuses it too, not only the test
            HttpResponse<String> rwCopy = post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RW)
                    + ",\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\"}", null);
            assertEquals(422, rwCopy.statusCode(), rwCopy.body());

            JsonNode ok = data(post(c, "/onboard/postgres/test", "{\"connection\":" + pg.connection(RO) + "}", null), 200);
            assertEquals(RO, ok.get("user").asText());
            assertTrue(ok.get("readOnly").asBoolean());
            assertTrue(ok.get("tables").toString().contains("\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\""), ok.toString());
            assertFalse(ok.toString().contains(System.getenv(PASSWORD_ENV)), "the password is never answered");
        } finally {
            drop(pg);
        }
    }

    @Test
    void aTableBecomesADatasetAndRunNowReplacesEverything(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        String pw = System.getenv(PASSWORD_ENV);
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO)
                    + ",\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\"}", null), 200);
            assertFalse(d.toString().contains(pw), "the draft never holds the password");
            assertEquals("${ENV:" + PASSWORD_ENV + "}", d.at("/snapshot/connection/password").asText());
            assertEquals(40, d.at("/file/rows").asLong(), d.toString());
            Map<String, String> types = new java.util.HashMap<>();
            for (JsonNode col : d.get("columns")) types.put(col.get("name").asText(), col.at("/type/value").asText());
            assertEquals("BIGINT", types.get("id"), types.toString());
            assertEquals("DOUBLE", types.get("amount"), "numeric arrives as a number");
            assertEquals("TIMESTAMP", types.get("sent_at"), "timestamptz arrives as its UTC time");
            assertEquals("BOOLEAN", types.get("ok"));
            assertEquals("VARCHAR", types.get("ref"), "a uuid arrives as text");
            assertEquals("VARCHAR", types.get("tags"), "an array arrives as text");
            String draftFile = Files.readString(Path.of("database", OnboardRoutes.DRAFTS, d.get("draftId").asText(), "draft.json"));
            assertFalse(draftFile.contains(pw), "nor does the stored draft");

            String id = "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            JsonNode made = data(post(c, "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            assertEquals(40, made.get("rowsAfter").asLong(), made.toString());
            Path snapFile = PostgresSnapshot.configFile(root, id);
            assertTrue(Files.isRegularFile(snapFile));
            String toon = Files.readString(snapFile);
            assertTrue(toon.contains(pg.schema) && toon.contains("${ENV:" + PASSWORD_ENV + "}"), toon);
            assertFalse(toon.contains(pw), "the config holds the reference, never the value");
            Map<String, Object> entry = new com.gamma.pipeline.ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
            assertEquals("postgres", entry.get("importSource"));

            pg.exec("INSERT INTO " + pg.schema + ".transfers SELECT g, 'acct1', 'acct2', 1, now(), true, md5(g::text)::uuid, NULL "
                    + "FROM generate_series(41, 45) g", "DELETE FROM " + pg.schema + ".transfers WHERE id <= 10");
            JsonNode run = data(post(c, "/datasets/" + id + "/run", null, null), 200);
            assertEquals(35, run.get("rowsAfter").asLong(), "replace everything: the table as it is now, not added to: " + run);

            Path dataRoot = Path.of("database").toAbsolutePath().normalize();
            int arrivals = DatasetArrivals.sidecarLines(dataRoot, id).size();
            assertFalse(PostgresSnapshot.poll(root, dataRoot, c.svc, id), "an unchanged table adds no arrival");
            assertEquals(arrivals, DatasetArrivals.sidecarLines(dataRoot, id).size());
            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id = 45");
            assertTrue(PostgresSnapshot.poll(root, dataRoot, c.svc, id), "a changed table is copied again");
            assertEquals(34, OnboardImport.countRows(dataRoot.resolve(id).resolve("database")));

            // deleting the Dataset retires its import: the snapshot's settings (and their reference) go with it
            HttpRequest del = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/components/dataset/" + id))
                    .DELETE().build();
            assertEquals(200, client.send(del, BodyHandlers.ofString()).statusCode());
            assertFalse(Files.exists(snapFile), "no snapshot settings left behind");
        } finally {
            drop(pg);
        }
    }

    @Test
    void theThirtySecondRefreshCopiesTheTableOnlyWhenItChanged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        InboxWatch.pausedForTest = true;   // the test drives InboxWatch.tick() itself (BASIC-ONBOARD-1 P2 + P3)
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO)
                    + ",\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\"}", null), 200);
            String id = "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            data(post(c, "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            Path dataRoot = Path.of("database").toAbsolutePath().normalize();
            Path db = dataRoot.resolve(id).resolve("database");

            JsonNode on = data(put(c, "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            assertTrue(on.get("watchable").asBoolean() && on.get("watch").asBoolean() && on.get("snapshot").asBoolean(), on.toString());
            int arrivals = DatasetArrivals.sidecarLines(dataRoot, id).size();
            InboxWatch.tick();
            assertEquals(arrivals, DatasetArrivals.sidecarLines(dataRoot, id).size(), "an unchanged table adds no arrival");

            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id <= 5");
            InboxWatch.tick();
            assertEquals(35, OnboardImport.countRows(db), "the poll copied the changed table, replacing everything");

            data(put(c, "/datasets/" + id + "/watch", "{\"watch\":false}", null), 200);
            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id <= 10");
            InboxWatch.tick();
            assertEquals(35, OnboardImport.countRows(db), "off: the table is no longer copied");
        } finally {
            InboxWatch.pausedForTest = false;
            drop(pg);
        }
    }

    @Test
    void theSessionItselfIsReadOnlyEvenForALoginThatCouldWrite(@TempDir Path dir) throws Exception {
        Pg pg = seeded();
        try (PostgresSnapshot.Session s = PostgresSnapshot.Session.open(
                PostgresSnapshot.Conn.of(JSON.readValue(pg.connection(RW), Map.class)), dir)) {
            for (String write : List.of("INSERT INTO pg." + pg.schema + ".transfers (id) VALUES (999)",
                    "CALL postgres_execute('pg', 'INSERT INTO " + pg.schema + ".transfers (id) VALUES (998)')")) {
                java.sql.SQLException refused = assertThrows(java.sql.SQLException.class, () -> {
                    try (Statement st = s.conn.createStatement()) {
                        st.execute(write);
                    }
                }, write);
                assertTrue(refused.getMessage().contains("read-only") || refused.getMessage().contains("read only"), refused.getMessage());
            }
            try (Connection sql = pg.open(); Statement st = sql.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM " + pg.schema + ".transfers")) {
                rs.next();
                assertEquals(40, rs.getLong(1), "nothing was written");
            }
        } finally {
            drop(pg);
        }
    }

    @Test
    void aQueryIsCopiedReadOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        try (Ctx c = open(cfg, root)) {
            String q = "SELECT id, sender, amount FROM " + pg.schema + ".transfers WHERE id > 30;";
            JsonNode d = data(post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO) + ",\"query\":"
                    + JSON.writeValueAsString(q) + "}", null), 200);
            assertEquals(10, d.at("/file/rows").asLong(), d.toString());
            assertEquals("query.parquet", d.at("/file/name").asText());

            // a data-modifying WITH passes the shape check; the login cannot delete and the session is read-only
            String sneaky = "WITH gone AS (DELETE FROM " + pg.schema + ".transfers RETURNING id) SELECT id FROM gone";
            HttpResponse<String> r = post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO) + ",\"query\":"
                    + JSON.writeValueAsString(sneaky) + "}", null);
            assertEquals(422, r.statusCode(), r.body());
            try (Connection sql = pg.open(); Statement st = sql.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM " + pg.schema + ".transfers")) {
                rs.next();
                assertEquals(40, rs.getLong(1), "nothing was deleted");
            }
        } finally {
            drop(pg);
        }
    }
}
