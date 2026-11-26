package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.access.ComponentAccess;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RECON-READ-UNGATED-1: the four recon reads ({@code POST /recon/columns}, {@code /recon/run}, {@code /recon/breaks},
 * {@code /recon/rows}) honour the Dataset's sharing and the caller's masking on every side. Real HTTP + real DuckDB
 * under an ARMED authenticator (a request with no Subject is unrestricted, which would hide every check here).
 *
 * <p>Users: {@code viewer} holds nothing, {@code revealer} holds {@code canRevealLinkEntities}, {@code owner} owns the
 * shared-away Dataset. {@code subs_*_ds} classify {@code msisdn} as MSISDN; {@code plain_*_ds} hold the same rows in
 * their own stores with nothing classified — the unmasked baseline.
 */
class ControlApiReconAccessTest {

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        String user = a.substring(7);
        return Optional.of(new Subject(user, user.startsWith("revealer") ? Set.of("canRevealLinkEntities") : Set.of()));
    };

    private static final List<String> RAW_KEYS = List.of("m1", "m2", "m3", "m4", "m5");
    private static final String SUBS = "\"datasets\":[\"subs_a_ds\",\"subs_b_ds\"],\"keyColumns\":[\"msisdn\"],"
            + "\"compareColumns\":[{\"column\":\"active_flag\",\"toleranceType\":\"exact\"}]";
    private static final String PLAIN = SUBS.replace("subs_a_ds", "plain_a_ds").replace("subs_b_ds", "plain_b_ds");

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** Masking by default is Standard: this module's own test classpath would otherwise read as Basic. */
    @BeforeEach
    void standardEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
    }

    @AfterEach
    void tearDown() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        Authenticators.forTest(null);
    }

    private Ctx open(Path root) throws Exception {
        Path config = root.resolve("s1").resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(root.resolve("s1").resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Path data = root.resolve("s1").resolve("data");
        // m2 active 1 vs 0 · m3 only in A · m5 only in B
        String a = "VALUES ('m1',1,199.0),('m2',1,149.0),('m3',1,99.0),('m4',0,50.0)";
        String b = "VALUES ('m1',1,199.0),('m2',0,149.0),('m4',0,50.0),('m5',1,30.0)";
        seedStore(data, "subs_a", a);
        seedStore(data, "subs_b", b);
        seedStore(data, "plain_a", a);
        seedStore(data, "plain_b", b);

        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("dataset", "subs_a_ds", dataset("subs_a", true));
        store.write("dataset", "subs_b_ds", dataset("subs_b", true));
        store.write("dataset", "plain_a_ds", dataset("plain_a", false));
        store.write("dataset", "plain_b_ds", dataset("plain_b", false));
        Map<String, Object> hidden = new LinkedHashMap<>(dataset("plain_b", false));
        hidden.put("owner", "owner");
        hidden.put("shares", List.of());   // shared with no one
        store.write("dataset", "hidden_b_ds", hidden);
        store.write("reconciliation", "hidden_recon", Map.of("datasets", List.of("plain_a_ds", "hidden_b_ds"),
                "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(Map.of("column", "active_flag", "toleranceType", "exact"))));

        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private static Map<String, Object> dataset(String store, boolean classified) {
        Map<String, Object> msisdn = new LinkedHashMap<>(Map.of("name", "msisdn"));
        if (classified) msisdn.put("classification", "MSISDN");
        return Map.of("dateField", "event_date", "physicalRef", store, "columns", List.of(msisdn,
                Map.of("name", "active_flag"), Map.of("name", "fee"), Map.of("name", "event_date")));
    }

    private static void seedStore(Path dataDir, String name, String values) throws Exception {
        Path partition = dataDir.resolve(name).resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_access_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT *, DATE '2026-09-26' AS event_date FROM (" + values
                    + ") t(msisdn, active_flag, fee)) TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private HttpResponse<String> post(Ctx c, String user, String route, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + route))
                .header("Content-Type", "application/json").method("POST", BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String user, String route, String body) throws Exception {
        HttpResponse<String> r = post(c, user, route, body);
        assertEquals(200, r.statusCode(), user + " " + route + " -> " + r.body());
        return V1Body.of(r.body());
    }

    private static void assertNoRawKey(String what, JsonNode body) {
        String text = body.toString();
        for (String k : RAW_KEYS) assertFalse(text.contains("\"" + k + "\""), what + " leaks raw key " + k + ": " + text);
    }

    /** An error body without its per-request correlation id. */
    private static String withoutCorrelation(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }

    /** The answer without its timing / cache statistics, which differ run to run. */
    private static JsonNode stable(JsonNode n) {
        JsonNode copy = n.deepCopy();
        if (copy instanceof ObjectNode o) o.remove("statistics");
        return copy;
    }

    /** The four reads, each over {@code config} (rows asks for one raw key). */
    private static Map<String, String> reads(String config) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("/recon/run", "{\"config\":{" + config + "}}");
        m.put("/recon/breaks", "{\"config\":{" + config + "}}");
        m.put("/recon/rows", "{\"config\":{" + config + "},\"key\":{\"msisdn\":\"m2\"}}");
        return m;
    }

    // ── sharing: canView on every side ─────────────────────────────────────────────────────

    @Test
    void aSideTheCallerCannotViewIsAnUnknownDatasetOnEveryRoute(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String hidden = PLAIN.replace("plain_b_ds", "hidden_b_ds");
            String absent = PLAIN.replace("plain_b_ds", "nope_ds");
            Map<String, String> bodies = reads(hidden);
            bodies.put("/recon/columns", "{\"datasets\":[\"plain_a_ds\",\"hidden_b_ds\"]}");
            Map<String, String> absentBodies = reads(absent);
            absentBodies.put("/recon/columns", "{\"datasets\":[\"plain_a_ds\",\"nope_ds\"]}");
            for (String route : bodies.keySet())
                for (String user : List.of("viewer", "revealer")) {
                    HttpResponse<String> r = post(c, user, route, bodies.get(route));
                    assertEquals(404, r.statusCode(), user + " " + route + " -> " + r.body());
                    assertTrue(r.body().contains("unknown dataset 'hidden_b_ds'"), r.body());
                    // indistinguishable from a Dataset that does not exist
                    HttpResponse<String> gone = post(c, user, route, absentBodies.get(route));
                    assertEquals(404, gone.statusCode(), gone.body());
                    assertEquals(withoutCorrelation(r.body().replace("hidden_b_ds", "nope_ds")), withoutCorrelation(gone.body()), route);
                    // the owner reads the same request
                    assertEquals(200, post(c, "owner", route, bodies.get(route)).statusCode(), "owner " + route);
                }
            // a saved Reconciliation over a shared-away side, by id
            HttpResponse<String> byId = post(c, "viewer", "/recon/run", "{\"id\":\"hidden_recon\"}");
            assertEquals(404, byId.statusCode(), byId.body());
            assertEquals(200, post(c, "owner", "/recon/run", "{\"id\":\"hidden_recon\"}").statusCode());
            // armed: no credentials at all is a 401, never a read
            assertEquals(401, post(c, null, "/recon/run", bodies.get("/recon/run")).statusCode());
        }
    }

    // ── masking per side ──────────────────────────────────────────────────────────────────

    @Test
    void aViewerSeesTheClassifiedKeyMaskedOnEverySide(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode run = ok(c, "viewer", "/recon/run", "{\"config\":{" + SUBS + "}}");
            assertEquals(5, run.get("rows").size(), run.toString());
            assertNoRawKey("run", run);
            String token = null;
            for (JsonNode row : run.get("rows")) {
                String k = row.get("key").get("msisdn").asText();
                assertTrue(k.startsWith("masked:"), k);
                if (row.get("inA").asBoolean() && row.get("inB").asBoolean() && token == null) token = k;
            }
            assertEquals(1, run.get("summary").get("byType").get("value_break").asInt(), "the comparison ran over raw keys");

            JsonNode breaks = ok(c, "viewer", "/recon/breaks", "{\"config\":{" + SUBS + "}}");
            assertNoRawKey("breaks", breaks);
            JsonNode m2 = breaks.get("value_break").get("rows").get(0);
            assertTrue(m2.get("key").get("msisdn").asText().startsWith("masked:"), m2.toString());
            assertEquals(1, breaks.get("missing_right").get("rowCount").asInt(), "m3 only in A");

            // a raw key may be asked for (D-AK15); every side's rows and the echoed key come back masked
            JsonNode rows = ok(c, "viewer", "/recon/rows", "{\"config\":{" + SUBS + "},\"key\":{\"msisdn\":\"m2\"}}");
            assertNoRawKey("rows", rows);
            assertEquals(m2.get("key").get("msisdn"), rows.get("key").get("msisdn"), "one key, one alias");
            for (String side : List.of("a", "b")) {
                JsonNode r = rows.get(side).get("rows").get(0);
                assertEquals(m2.get("key").get("msisdn"), r.get("msisdn"), side);
                assertEquals(149.0, r.get("fee").asDouble(), "an unclassified column stays raw");
            }

            // one side classified is enough to mask the shared key; that side's rows are masked, the other's are not
            String half = SUBS.replace("subs_a_ds", "plain_a_ds");
            JsonNode halfRun = ok(c, "viewer", "/recon/run", "{\"config\":{" + half + "}}");
            assertNoRawKey("half run", halfRun);
            JsonNode halfRows = ok(c, "viewer", "/recon/rows", "{\"config\":{" + half + "},\"key\":{\"msisdn\":\"m2\"}}");
            assertEquals("m2", halfRows.get("a").get("rows").get(0).get("msisdn").asText(), "plain side: raw");
            assertTrue(halfRows.get("b").get("rows").get(0).get("msisdn").asText().startsWith("masked:"), "classified side");
            assertTrue(halfRows.get("key").get("msisdn").asText().startsWith("masked:"));

            // drilling by the alias cannot match a raw value: refused, explained
            HttpResponse<String> drill = post(c, "viewer", "/recon/breaks",
                    "{\"config\":{" + SUBS + "},\"path\":{\"msisdn\":\"" + token + "\"}}");
            assertEquals(422, drill.statusCode(), drill.body());
            assertTrue(drill.body().contains("canRevealLinkEntities"), drill.body());

            // a side filter on a hidden column bisects it (LIKE 'm2%') or prints it in a conversion error: refused, on every
            // route; a filter on a clean column stays allowed, and the revealer may filter on the key
            // ... and so does the side's view name, which is the whole row as a struct
            Map<String, String> probes = new LinkedHashMap<>();
            probes.put("msisdn LIKE 'm2%'", "subs_b_ds");
            probes.put("MSISDN LIKE 'm2%'", "subs_b_ds");
            probes.put("cast(msisdn as integer) = 0", "subs_b_ds");
            probes.put("cast(__recon_b as varchar) LIKE '%m2%'", "subs_b_ds");
            probes.put("concat(__recon_b) LIKE '%m2%'", "subs_b_ds");
            probes.put("cast(cast(__recon_b as varchar) as integer) = 0", "subs_b_ds");
            probes.put("cast(__recon_a as varchar) LIKE '%m2%'", "subs_a_ds");
            int n = 0;   // a user per probe: the probes would otherwise pass the per-subject rate limit (20 burst)
            for (Map.Entry<String, String> probe : probes.entrySet()) {
                n++;
                for (Map.Entry<String, String> read : reads(SUBS + ",\"filters\":{\"" + probe.getValue() + "\":\""
                        + probe.getKey() + "\"}").entrySet()) {
                    HttpResponse<String> r = post(c, "viewer" + n, read.getKey(), read.getValue());
                    assertEquals(422, r.statusCode(), probe.getKey() + " " + read.getKey() + " -> " + r.body());
                    assertTrue(r.body().contains("canRevealLinkEntities"), r.body());
                    for (String k : RAW_KEYS) assertFalse(r.body().contains(k + "'") || r.body().contains(": " + k), r.body());
                    assertEquals(probe.getKey().contains("integer") ? 422 : 200,
                            post(c, "revealer" + n, read.getKey(), read.getValue()).statusCode(), "revealer " + probe.getKey());
                }
            }
            JsonNode clean = ok(c, "viewer", "/recon/run",
                    "{\"config\":{" + SUBS + ",\"filters\":{\"subs_b_ds\":\"active_flag = 1\"}}}");
            assertNoRawKey("clean filter", clean);

            // a sum over a hidden column (the carried impact) reads its values: refused for the viewer, not the revealer
            String sumOverKey = "\"datasets\":[\"subs_a_ds\",\"subs_b_ds\"],\"keyColumns\":[\"active_flag\"],"
                    + "\"compareColumns\":[{\"column\":\"fee\",\"agg\":\"count\",\"toleranceType\":\"exact\"}],"
                    + "\"impact\":{\"column\":\"msisdn\"}";
            HttpResponse<String> refused = post(c, "viewer", "/recon/breaks", "{\"config\":{" + sumOverKey + "}}");
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("canRevealLinkEntities"), refused.body());
            assertEquals(200, post(c, "revealer", "/recon/breaks", "{\"config\":{" + sumOverKey + "}}").statusCode());
        }
    }

    @Test
    void aCallerWithFullRightsGetsTheUnmaskedAnswer(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> classified = reads(SUBS);
            Map<String, String> baseline = reads(PLAIN);
            for (String route : classified.keySet()) {
                JsonNode revealed = ok(c, "revealer", route, classified.get(route));
                JsonNode plain = ok(c, "viewer", route, baseline.get(route));
                assertEquals(stable(plain), stable(revealed), route + ": the revealer reads what an unclassified twin shows");
                assertTrue(revealed.toString().contains("\"m2\""), route);
            }
        }
    }

    // ── the answer cache is shared: masking runs after it ────────────────────────────────

    @Test
    void oneCallersRawAnswerIsNeverServedFromTheCacheToAnother(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> bodies = reads(SUBS);
            for (String route : bodies.keySet()) {
                JsonNode raw = ok(c, "revealer", route, bodies.get(route));
                assertTrue(raw.toString().contains("\"m2\""), route + " revealer raw");
                JsonNode masked = ok(c, "viewer", route, bodies.get(route));
                assertNoRawKey(route + " after a revealer's read", masked);
                if ("/recon/run".equals(route))
                    assertTrue(masked.get("statistics").get("cached").asBoolean(), "the viewer's page came from the cache");
                // and the other way round: the viewer's masked answer never reaches the revealer
                JsonNode again = ok(c, "revealer", route, bodies.get(route));
                assertEquals(stable(raw), stable(again), route + " revealer after a viewer's read");
            }
        }
    }
}
