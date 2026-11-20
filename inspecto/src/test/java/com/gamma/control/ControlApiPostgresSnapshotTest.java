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

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
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

    /**
     * BASIC-ELT-PROFILE-1 BE-4 (O4): a snapshot keeps a saved {@code postgres} Connection, and the Connection save itself -
     * not only Add data's form - takes nothing but an {@code INSPECTO_PG_} reference, on create and on update.
     */
    @Test
    void aPostgresConnectionIsSavedOnlyWithAnInspectoPgReference(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String raw = "Sup3r-Secret-Value";
            Path file = root.resolve("pgc_connection.toon");
            for (String pw : List.of("${ENV:PATH}", "${SYS:user.home}", "${ENV:INSPECTO_PG_}", raw)) {
                HttpResponse<String> r = post(c, "/connections", pgConnection("pgc", pw), null);
                assertEquals(422, r.statusCode(), pw + " " + r.body());
                assertTrue(error(r).contains(PostgresSnapshot.ENV_PREFIX), r.body());
                assertFalse(r.body().contains(raw), "the value is never answered: " + r.body());
                assertFalse(Files.exists(file), "nothing was saved for " + pw);
            }
            data(post(c, "/connections", pgConnection("pgc", "${ENV:" + PASSWORD_ENV + "}"), null), 200);
            String saved = Files.readString(file);
            HttpResponse<String> widened = put(c, "/connections/pgc", pgConnection("pgc", "${ENV:PATH}"), null);
            assertEquals(422, widened.statusCode(), widened.body());
            assertEquals(saved, Files.readString(file), "a refused update leaves the saved Connection as it was");
        }
    }

    private static String pgConnection(String id, String password) throws Exception {
        return JSON.writeValueAsString(Map.of("id", id, "connector", "postgres", "host", "127.0.0.1", "port", 5432,
                "database", "db", "username", RO, "password", password, "options", Map.of("schema", "public", "table", "t")));
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

    /**
     * BE-4 part 2: the Safety Policy's network gate applies to the snapshot read - the test and the copy (Run now reads
     * through the same connector session) - before anything is dialled or resolved: an unset reference would otherwise
     * answer "not set".
     */
    @Test
    void aHostTheSafetyPolicyRefusesIsRefusedBeforeAnythingIsDialled(@TempDir Path cfg, @TempDir Path root, @TempDir Path server)
            throws Exception {
        Files.writeString(server.resolve("safety-policy.toon"), "deny:\n  hosts[1]: pg.refused.example\n");
        String prior = System.getProperty("system.config.dir");
        System.setProperty("system.config.dir", server.toString());
        try (Ctx c = open(cfg, root)) {
            String var = "INSPECTO_PG_SURELY_UNSET_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            String refused = conn("pg.refused.example", 5432, "db", RO, "${ENV:" + var + "}");
            for (String[] call : List.of(new String[]{"/onboard/postgres/test", "{\"connection\":" + refused + "}"},
                    new String[]{"/onboard/postgres", "{\"connection\":" + refused + ",\"schema\":\"public\",\"table\":\"t\"}"})) {
                HttpResponse<String> r = post(c, call[0], call[1], null);
                assertEquals(422, r.statusCode(), call[0] + " " + r.body());
                assertTrue(error(r).contains("Safety Policy") && error(r).contains("pg.refused.example"), r.body());
                assertFalse(error(r).contains("not set"), "the gate answered first: " + r.body());
            }
            // a host the policy leaves alone reaches the password lookup as before
            HttpResponse<String> other = post(c, "/onboard/postgres/test", "{\"connection\":"
                    + conn("127.0.0.1", 5432, "db", RO, "${ENV:" + var + "}") + "}", null);
            assertEquals(422, other.statusCode(), other.body());
            assertTrue(error(other).contains("not set"), other.body());
        } finally {
            if (prior == null) System.clearProperty("system.config.dir");
            else System.setProperty("system.config.dir", prior);
        }
    }

    private HttpResponse<String> delete(Ctx c, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.DELETE().build(), BodyHandlers.ofString());
    }

    /**
     * BE-4 part 2: {@code DELETE /connections/{id}} refuses (409) the Connection a Postgres snapshot reads through - named in
     * its {@code _snapshot.toon}, or, when that file cannot be read, the one its commit saved ({@code <id>_postgres}) - and
     * the answer names the Dataset, never a server path. Every gate of the route: 503, 401, 403, 404, 409, 200.
     */
    @Test
    void aConnectionASnapshotReadsThroughCannotBeDeleted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, delete(c, "/connections/sales_postgres", null).statusCode());
        }
        String connector = "Bearer connector";
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer connector" -> Optional.of(new Subject("connector-1", Set.of("canOnboardConnections")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canAuthorWorkbench")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            String ref = "${ENV:" + PASSWORD_ENV + "}";
            data(post(c, "/connections", pgConnection("sales_postgres", ref), connector), 200);
            data(post(c, "/connections", pgConnection("other_postgres", ref), connector), 200);
            Path imports = Files.createDirectories(root.resolve(OnboardImport.CONFIG_DIR));
            Path named = imports.resolve("sales" + PostgresSnapshot.SUFFIX);
            Files.writeString(named, "source: postgres\nconnection: sales_postgres\n");
            Path broken = imports.resolve("other" + PostgresSnapshot.SUFFIX);
            Files.writeString(broken, "connection[2]: other_postgres\n");   // a bad array header: the file cannot be read

            assertEquals(401, delete(c, "/connections/sales_postgres", null).statusCode());
            assertEquals(403, delete(c, "/connections/sales_postgres", ANALYST).statusCode());
            assertEquals(404, delete(c, "/connections/nothing_here", connector).statusCode());
            for (String[] t : List.of(new String[]{"sales_postgres", "sales"}, new String[]{"other_postgres", "other"})) {
                HttpResponse<String> r = delete(c, "/connections/" + t[0], connector);
                assertEquals(409, r.statusCode(), t[0] + " " + r.body());
                assertTrue(error(r).contains("'" + t[1] + "'"), "names the Dataset: " + r.body());
                for (String leak : List.of(root.toString(), root.toString().replace('\\', '/'), OnboardImport.CONFIG_DIR + "/", ".toon"))
                    assertFalse(r.body().contains(leak), "no server path in the answer (" + leak + "): " + r.body());
                assertTrue(Files.exists(root.resolve(t[0] + "_connection.toon")), "the Connection is kept: " + t[0]);
                HttpResponse<String> still = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                        + "/api/v1/connections/" + t[0])).header("Authorization", connector).GET().build(), BodyHandlers.ofString());
                assertEquals(200, still.statusCode(), "still registered: " + t[0] + " " + still.body());
            }

            // the snapshot gone, its Connection may go
            Files.delete(named);
            data(delete(c, "/connections/sales_postgres", connector), 200);
            assertFalse(Files.exists(root.resolve("sales_postgres_connection.toon")));
            assertEquals(409, delete(c, "/connections/other_postgres", connector).statusCode(), "the other snapshot still holds its own");
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
            // BE-4 (O4): the connection is a saved postgres Connection; the snapshot settings only name it
            String connId = PostgresSnapshot.connectionId(id);
            String toon = Files.readString(snapFile);
            assertTrue(toon.contains(connId) && !toon.contains("${ENV:"), toon);
            Path connFile = root.resolve(connId + "_connection.toon");
            String saved = Files.readString(connFile);
            assertTrue(saved.contains("postgres") && saved.contains(pg.schema) && saved.contains("transfers")
                    && saved.contains("${ENV:" + PASSWORD_ENV + "}"), saved);
            assertFalse(saved.contains(pw), "the Connection holds the reference, never the value");
            JsonNode listed = data(get(c, "/connections/" + connId), 200);
            assertEquals("postgres", listed.get("connector").asText(), listed.toString());
            Map<String, Object> entry = new com.gamma.pipeline.ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
            assertEquals("postgres", entry.get("importSource"));

            pg.exec("INSERT INTO " + pg.schema + ".transfers SELECT g, 'acct1', 'acct2', 1, now(), true, md5(g::text)::uuid, NULL "
                    + "FROM generate_series(41, 45) g", "DELETE FROM " + pg.schema + ".transfers WHERE id <= 10");
            JsonNode run = data(post(c, "/datasets/" + id + "/run", null, null), 200);
            assertEquals(35, run.get("rowsAfter").asLong(), "replace everything: the table as it is now, not added to: " + run);

            Path dataRoot = Path.of("database").toAbsolutePath().normalize();
            // export, then move (operator 2026-11-05): the run took the export from the inbox like an uploaded file
            assertEquals("[]", inboxListing(dataRoot, id), "the inbox is empty again: the export was moved in and taken");
            List<Map<String, Object>> lines = DatasetArrivals.sidecarLines(dataRoot, id);
            assertEquals("replace", lines.get(lines.size() - 1).get("mode"), "a snapshot replaces everything: " + lines);
            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id = 45");
            data(post(c, "/datasets/" + id + "/run", null, null), 200);
            assertEquals(34, OnboardImport.countRows(dataRoot.resolve(id).resolve("database")));

            // O7: the commit switched the Pipeline to read Postgres itself; a run not started by Run now copies the table
            // through the Connection and replaces the rows (output.load: replace) - never a second full copy
            assertEquals("postgres", c.svc().configFor(id).orElseThrow().collector().connector());
            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id = 44");
            c.svc().runPipeline(id).orElseThrow();
            assertEquals(33, OnboardImport.countRows(dataRoot.resolve(id).resolve("database")), "the table as it is now, not added to");
            c.svc().runPipeline(id).orElseThrow();
            assertEquals(33, OnboardImport.countRows(dataRoot.resolve(id).resolve("database")), "the same table again: still one copy");
            assertEquals("[]", inboxListing(dataRoot, id), "each run's export was taken");

            // deleting the Dataset retires its import: the snapshot's settings (and their reference) go with it
            HttpRequest del = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/components/dataset/" + id))
                    .DELETE().build();
            assertEquals(200, client.send(del, BodyHandlers.ofString()).statusCode());
            assertFalse(Files.exists(snapFile), "no snapshot settings left behind");
            assertFalse(Files.exists(connFile), "nor the Connection the commit saved for it");
            assertEquals(404, get(c, "/connections/" + connId).statusCode());
        } finally {
            drop(pg);
        }
    }

    /** ADD-DATA-ROW-FILTER-1: the row filter a snapshot was committed with keeps applying on every Refresh now. */
    @Test
    void aSnapshotKeepsOnlyTheFilteredRowsOnEveryRefresh(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO)
                    + ",\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\"}", null), 200);
            String id = "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            // ok = id even; amount = id * 1.5, so amount >= 15 means id >= 10: ids 10, 12 … 40 (16 rows)
            String filter = ",\"filter\":[{\"column\":\"ok\",\"op\":\"eq\",\"value\":true},{\"column\":\"amount\",\"op\":\"range\",\"min\":15}]";
            JsonNode made = data(post(c, "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"" + filter + "}", null), 200);
            assertEquals(16, made.get("rowsAfter").asLong(), made.toString());
            assertEquals(24, made.get("rowsFilteredOut").asLong(), made.toString());

            // rows 41-45 (amount 1) arrive, rows 1-10 go: 12, 14 … 40 are kept (15 rows)
            pg.exec("INSERT INTO " + pg.schema + ".transfers SELECT g, 'acct1', 'acct2', 1, now(), true, md5(g::text)::uuid, NULL "
                    + "FROM generate_series(41, 45) g", "DELETE FROM " + pg.schema + ".transfers WHERE id <= 10");
            JsonNode run = data(post(c, "/datasets/" + id + "/run", null, null), 200);
            assertEquals(15, run.get("rowsAfter").asLong(), run.toString());
            assertEquals(20, run.get("rowsFilteredOut").asLong(), run.toString());

            Path dataRoot = Path.of("database").toAbsolutePath().normalize();
            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id = 40");
            data(post(c, "/datasets/" + id + "/run", null, null), 200);
            assertEquals(14, OnboardImport.countRows(dataRoot.resolve(id).resolve("database")), "every refresh filters");
        } finally {
            drop(pg);
        }
    }

    /** Operator 2026-11-05: a snapshot is manual only - Watch is refused and the poll cycle never copies the table. */
    @Test
    void aSnapshotIsNeverPolled(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO)
                    + ",\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\"}", null), 200);
            String id = "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            data(post(c, "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            Path db = Path.of("database").toAbsolutePath().normalize().resolve(id).resolve("database");

            HttpResponse<String> on = put(c, "/datasets/" + id + "/watch", "{\"watch\":true}", null);
            assertEquals(422, on.statusCode(), on.body());
            assertTrue(error(on).contains("not polled"), on.body());
            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id <= 5");
            ControlApiImportPreRunStepsTest.poll(c.svc());   // the engine's poll cycle (B1: the watch is a schedule)
            assertEquals(40, OnboardImport.countRows(db), "no poll copied the changed table");
            data(post(c, "/datasets/" + id + "/run", null, null), 200);
            assertEquals(35, OnboardImport.countRows(db), "Refresh now does");
        } finally {
            drop(pg);
        }
    }

    private static String inboxListing(Path dataRoot, String id) throws Exception {
        Path in = dataRoot.resolve("inbox").resolve(id);
        if (!Files.isDirectory(in)) return "[]";
        try (java.util.stream.Stream<Path> w = Files.list(in)) {
            return w.map(p -> p.getFileName().toString()).sorted().toList().toString();
        }
    }

    /**
     * O7: a snapshot committed before snapshots kept a saved Connection holds its connection inline in {@code _snapshot.toon}
     * and a Pipeline that reads its inbox. Its first Run now migrates it - the Connection saved from the inline block, the
     * settings rewritten to name it, the Pipeline switched - and copies the table; a run outside Add data then replaces the
     * rows through the Pipeline. Deleting its Dataset leaves no file behind. (The session's own read-only test moved with
     * the session to the engine: {@code PostgresConnectorFactoryTest}.)
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSnapshotCommittedBeforeConnectionsMigratesAtItsFirstRunNow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO)
                    + ",\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\"}", null), 200);
            String id = "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            data(post(c, "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            // turn it into the old shape on disk: the connection inline, no saved Connection, the Pipeline reading its inbox
            String connId = PostgresSnapshot.connectionId(id);
            Map<String, Object> legacy = new java.util.LinkedHashMap<>();
            legacy.put("source", "postgres");
            legacy.put("connection", JSON.readValue(pg.connection(RO), Map.class));
            legacy.put("schema", pg.schema);
            legacy.put("table", "transfers");
            Path snapFile = PostgresSnapshot.configFile(root, id);
            PostgresSnapshot.write(snapFile, legacy);
            Path pipelineFile = root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon");
            Map<String, Object> p = new java.util.LinkedHashMap<>(com.gamma.util.ToonHelper.load(pipelineFile.toString()));
            Map<String, Object> collector = new java.util.LinkedHashMap<>((Map<String, Object>) p.get("collector"));
            collector.put("connector", "local");
            collector.remove("connection");
            p.put("collector", collector);
            p.put("output", Map.of("format", "PARQUET", "compression", "snappy"));
            Map<String, Object> processing = new java.util.LinkedHashMap<>((Map<String, Object>) p.get("processing"));
            processing.put("duplicate_check", Map.of("enabled", true, "marker_extension", ".processed"));
            p.put("processing", processing);
            Files.writeString(pipelineFile, com.gamma.config.io.ConfigCodec.toToon(p));
            c.svc().refreshConfigs();
            Files.delete(root.resolve(connId + "_connection.toon"));
            c.svc().unregisterConnection(connId);
            assertEquals("local", c.svc().configFor(id).orElseThrow().collector().connector());

            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id <= 10");
            JsonNode run = data(post(c, "/datasets/" + id + "/run", null, null), 200);
            assertEquals(30, run.get("rowsAfter").asLong(), run.toString());
            assertEquals(connId, com.gamma.util.ToonHelper.load(snapFile.toString()).get("connection"), "the settings name the Connection");
            String saved = Files.readString(root.resolve(connId + "_connection.toon"));
            assertTrue(saved.contains(pg.schema) && saved.contains("${ENV:" + PASSWORD_ENV + "}"), saved);
            assertEquals("postgres", c.svc().configFor(id).orElseThrow().collector().connector(), "the Pipeline reads Postgres itself");
            pg.exec("DELETE FROM " + pg.schema + ".transfers WHERE id = 11");
            c.svc().runPipeline(id).orElseThrow();
            assertEquals(29, OnboardImport.countRows(Path.of("database").toAbsolutePath().resolve(id).resolve("database")),
                    "a run outside Add data replaces the rows");

            HttpRequest gone = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/components/dataset/" + id))
                    .DELETE().build();
            assertEquals(200, client.send(gone, BodyHandlers.ofString()).statusCode());
            assertFalse(Files.exists(snapFile));
        } finally {
            drop(pg);
        }
    }

    /** BE-4: the commit decides before it writes - a Connection already holding the snapshot's name refuses it whole. */
    @Test
    void aCommitWhoseConnectionNameIsTakenWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Pg pg = seeded();
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(post(c, "/onboard/postgres", "{\"connection\":" + pg.connection(RO)
                    + ",\"schema\":\"" + pg.schema + "\",\"table\":\"transfers\"}", null), 200);
            String id = "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            String connId = PostgresSnapshot.connectionId(id);
            data(post(c, "/connections", pgConnection(connId, "${ENV:" + PASSWORD_ENV + "}"), null), 200);
            String theirs = Files.readString(root.resolve(connId + "_connection.toon"));
            HttpResponse<String> r = post(c, "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(error(r).contains(connId), r.body());
            assertFalse(Files.exists(PostgresSnapshot.configFile(root, id)), "no snapshot settings");
            assertFalse(new com.gamma.pipeline.ComponentStore(root.resolve("registry")).exists("dataset", id), "no Dataset");
            assertEquals(theirs, Files.readString(root.resolve(connId + "_connection.toon")), "their Connection is untouched");
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
