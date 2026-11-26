package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.access.ComponentAccess;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOOKUP-TEST-OPEN-READ-1 — {@code POST /lookups/test} over real HTTP, with an armed authenticator: no credential 401,
 * no {@code canAuthorWorkbench} 403; a Dataset over the Reference's store that the caller cannot view 404 (as an
 * unknown Step); a caller who may not reveal gets the classified key and mapped column masked, and at most five fuzzy
 * candidates, each masked; a revealer gets the raw answer as before; a {@code path:} Reference has no classification,
 * so it answers raw; repeated {@code refresh: true} and repeated calls are 429 with Retry-After.
 */
class ControlApiLookupTestAccessTest {

    private static final String AUTHOR = "author", FULL = "full", NOCAP = "nocap";

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        String user = a.substring(7);
        Set<String> caps = switch (user) {
            case AUTHOR -> Set.of("canAuthorWorkbench");
            case FULL -> Set.of("canAuthorWorkbench", BiResultMasking.REVEAL);
            default -> Set.of();
        };
        return Optional.of(new Subject(user, caps));
    };

    /** Seven subscribers whose keys differ only in the last digit, so one fuzzy value matches all of them. */
    private static final List<String> KEYS = List.of("249910000001", "249910000002", "249910000003", "249910000004",
            "249910000005", "249910000006", "249910000007");
    private static final String OWNER_OF_FIRST = "447700900123";

    private static final String STEPS = """
            output_store: enriched_calls
            steps[2]:
              - join:
                  reference: reference/region_dim
                  key: msisdn
                  match: exact
                  on: ID
                  columns[2]: owner_msisdn, region
                  details: true
              - join:
                  reference: reference/region_dim
                  key: msisdn
                  match: fuzzy
                  method: jaro_winkler
                  threshold: 0.5
                  top_k: 10
                  on: AMT
                  columns: region
            """;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final String PRIOR_ROOTS = System.getProperty("assist.safety.roots");

    @BeforeEach
    void setUp() {
        Authenticators.forTest(FAKE);
        // masking by default is Standard; this module's own classpath is Basic (ControlApiBiQueryMaskingTest)
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
        LookupRoutes.clearWarm();
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        if (PRIOR_ROOTS != null) System.setProperty("assist.safety.roots", PRIOR_ROOTS);
        else System.clearProperty("assist.safety.roots");
        LookupRoutes.clearWarm();
    }

    private record Ctx(CollectorService svc, ControlApi api, Path root, String pipeline, String exact, String fuzzy)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /**
     * {@code REGION_DIM} ({@code produces: reference}) holding msisdn, owner_msisdn, region; the registry Dataset
     * {@code regions} over its store classifies msisdn and owner_msisdn as MSISDN, with {@code dataset} merged in.
     */
    private Ctx open(Path dir, String steps, Map<String, Object> dataset) throws Exception {
        System.setProperty("assist.safety.roots", dir.toString());
        Path root = dir.resolve("space");
        Path region = TestConfigs.csv(dir.resolve("region"), PipelineConfigBatchTest.miniSchema())
                .name("REGION_DIM").format("PARQUET").write();
        Files.writeString(region, "produces: reference\n" + Files.readString(region).replace("active: true", "active: false"));
        Path db = dir.resolve("region").resolve("db");
        Files.createDirectories(db);
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < KEYS.size(); i++)
            values.append(i == 0 ? "" : ",").append("('").append(KEYS.get(i)).append("','")
                    .append(i == 0 ? OWNER_OF_FIRST : "44770090000" + i).append("','R").append(i).append("')");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS t(msisdn, owner_msisdn, region)) TO '"
                    + db.resolve("part0.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.writeString(toon, Files.readString(toon).replace("active: true", "active: false") + steps);

        Map<String, Object> ds = new LinkedHashMap<>(Map.of("physicalRef", "region_dim", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"),
                Map.of("name", "owner_msisdn", "classification", "MSISDN"), Map.of("name", "region"))));
        ds.putAll(dataset);
        new ComponentStore(root.resolve("registry")).write("dataset", "regions", ds);

        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon, region), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String name = svc.loadedPipelines().stream().map(p -> p.identity().pipelineName())
                    .filter(n -> !n.equalsIgnoreCase("REGION_DIM")).findFirst().orElseThrow();
            List<String> stepIds = new java.util.ArrayList<>();
            V1Body.of(send(api, FULL, "GET", "/lookups", null).body()).get("lookups")
                    .forEach(r -> stepIds.add(r.get("step").asText()));
            return new Ctx(svc, api, root, name, stepIds.get(0), stepIds.size() > 1 ? stepIds.get(1) : null);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private Ctx open(Path dir) throws Exception { return open(dir, STEPS, Map.of()); }

    private HttpResponse<String> send(ControlApi api, String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> test(Ctx c, String user, String step, String value, String extra) throws Exception {
        return send(c.api, user, "POST", "/lookups/test", "{\"pipeline\":\"" + c.pipeline + "\",\"step\":\"" + step
                + "\",\"value\":\"" + value + "\"" + extra + "}");
    }

    private static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static void assertNoRaw(String body) {
        for (String raw : KEYS) assertFalse(body.contains(raw), "raw key " + raw + " must not appear: " + body);
        assertFalse(body.contains(OWNER_OF_FIRST), "raw owner must not appear: " + body);
    }

    @Test
    void noCredentialIs401AndNoCapabilityIs403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(401, test(c, null, c.exact, KEYS.get(0), "").statusCode());
            HttpResponse<String> r = test(c, NOCAP, c.exact, KEYS.get(0), "");
            assertEquals(403, r.statusCode(), r.body());
            assertNoRaw(r.body());
            ok(test(c, AUTHOR, c.exact, KEYS.get(0), ""));
        }
    }

    @Test
    void aRevealerGetsTheRawAnswerAsBefore(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode a = ok(test(c, FULL, c.exact, KEYS.get(0), ""));
            assertTrue(a.get("hit").asBoolean(), a.toString());
            assertEquals(KEYS.get(0), a.get("matchedKey").asText());
            JsonNode cols = a.get("mappings").get(0).get("columns");
            assertEquals(OWNER_OF_FIRST, cols.get("ID_owner_msisdn").asText(), a.toString());
            assertEquals("R0", cols.get("ID_region").asText());
            assertEquals(KEYS.get(0), cols.get("ID_matched_key").asText());
            JsonNode f = ok(test(c, FULL, c.fuzzy, "24991000000", ""));
            assertEquals(5, f.get("candidates").size(), "capped at five even for a revealer: " + f);
            assertTrue(KEYS.contains(f.get("candidates").get(0).get("matchedKey").asText()), f.toString());
        }
    }

    @Test
    void aCallerWhoMayNotRevealGetsTheClassifiedColumnsAndCandidatesMasked(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = test(c, AUTHOR, c.exact, KEYS.get(0), "");
            JsonNode a = ok(r);
            assertNoRaw(r.body().replace("\"compared\":\"" + KEYS.get(0) + "\"", ""));   // the caller's own value echoes
            assertTrue(a.get("hit").asBoolean(), a.toString());
            assertTrue(a.get("matchedKey").asText().startsWith("masked:"), a.toString());
            JsonNode cols = a.get("mappings").get(0).get("columns");
            assertTrue(cols.get("ID_owner_msisdn").asText().startsWith("masked:"), a.toString());
            assertTrue(cols.get("ID_matched_key").asText().startsWith("masked:"), a.toString());
            assertEquals("R0", cols.get("ID_region").asText(), "an unclassified column stays raw: " + a);

            HttpResponse<String> fr = test(c, AUTHOR, c.fuzzy, "24991000000", "");
            JsonNode f = ok(fr);
            assertNoRaw(fr.body());
            assertEquals(5, f.get("candidates").size(), "seven match; five are served: " + f);
            for (JsonNode cand : f.get("candidates"))
                assertTrue(cand.get("matchedKey").asText().startsWith("masked:"), f.toString());
            assertTrue(f.get("matchedKey").asText().startsWith("masked:"), f.toString());
        }
    }

    /** Verification probe: a classified Dataset over the store under any spelling (store/table, case, sourceName) masks too. */
    @Test
    void aDatasetOverTheStoreUnderAnotherSpellingStillMasks(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            List<Map<String, Object>> classified = List.of(Map.of("name", "msisdn", "classification", "MSISDN"),
                    Map.of("name", "owner_msisdn", "classification", "MSISDN"), Map.of("name", "region"));
            List<Map<String, Object>> spellings = List.of(
                    Map.of("physicalRef", "region_dim/region_dim", "columns", classified),
                    Map.of("physicalRef", "REGION_DIM", "columns", classified),
                    Map.of("sourceName", "region_dim", "sql", "SELECT * FROM region_dim", "columns", classified));
            for (Map<String, Object> ds : spellings) {
                new ComponentStore(c.root.resolve("registry")).write("dataset", "regions", ds);
                HttpResponse<String> r = test(c, AUTHOR, c.exact, KEYS.get(0), "");
                JsonNode a = ok(r);
                assertNoRaw(r.body().replace("\"compared\":\"" + KEYS.get(0) + "\"", ""));
                assertTrue(a.get("matchedKey").asText().startsWith("masked:"), ds + " -> " + a);
                assertTrue(a.get("mappings").get(0).get("columns").get("ID_owner_msisdn").asText().startsWith("masked:"),
                        ds + " -> " + a);
            }
        }
    }

    @Test
    void aDatasetOverTheReferenceThatTheCallerCannotViewIsAsAbsentAsAnUnknownStep(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, STEPS, Map.of("owner", "someone-else", "shares", List.of()))) {
            HttpResponse<String> r = test(c, FULL, c.exact, KEYS.get(0), "");
            assertEquals(404, r.statusCode(), r.body());
            assertNoRaw(r.body());
            HttpResponse<String> unknown = test(c, FULL, "no-such-step", KEYS.get(0), "");
            assertEquals(404, unknown.statusCode());
            assertTrue(r.body().contains("no keyed lookup '" + c.exact + "' in pipeline '" + c.pipeline + "'"),
                    "the same answer as a Step that does not exist: " + r.body());
            // shared with the caller: it answers
            new ComponentStore(c.root.resolve("registry")).write("dataset", "regions", Map.of("physicalRef", "region_dim",
                    "owner", "someone-else", "shares", List.of(Map.of("subjectType", "user", "subjectId", FULL, "access", "view"))));
            assertEquals(KEYS.get(0), ok(test(c, FULL, c.exact, KEYS.get(0), "")).get("matchedKey").asText());
        }
    }

    @Test
    void aPathReferenceHasNoClassificationSoItAnswersRawToAnAuthor(@TempDir Path dir) throws Exception {
        Path ref = dir.resolve("refdata").resolve("dial.csv");
        Files.createDirectories(ref.getParent());
        Files.writeString(ref, "dial_digit_key,provider_desc\n249,Sudan\n44,UK\n");
        String steps = """
                output_store: enriched_calls
                steps[1]:
                  - join:
                      reference: %s
                      key: dial_digit_key
                      match: exact
                      on: ID
                      columns: provider_desc
                """.formatted(ref.toString().replace('\\', '/'));
        try (Ctx c = open(dir, steps, Map.of())) {
            // no Dataset or Pipeline schema describes the file, so nothing in it is classified (an author can dry-run it too)
            JsonNode a = ok(test(c, AUTHOR, c.exact, "44", ""));
            assertEquals("44", a.get("matchedKey").asText(), a.toString());
            assertEquals("UK", a.get("mappings").get(0).get("columns").get("ID_provider_desc").asText());
            assertEquals(403, test(c, NOCAP, c.exact, "44", "").statusCode(), "the capability still gates it");
        }
    }

    @Test
    void repeatedReloadsAndRepeatedCallsAre429WithRetryAfter(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // every build spends the build budget (burst 3): the cold build, then two asked reloads
            assertEquals("first use", ok(test(c, FULL, c.exact, KEYS.get(0), "")).get("index").get("reloaded").asText());
            for (int i = 0; i < 2; i++)
                assertEquals("reload asked", ok(test(c, FULL, c.exact, KEYS.get(0), ",\"refresh\":true"))
                        .get("index").get("reloaded").asText());
            HttpResponse<String> refused = test(c, FULL, c.exact, KEYS.get(0), ",\"refresh\":true");
            assertEquals(429, refused.statusCode(), refused.body());
            assertNotNull(refused.headers().firstValue("Retry-After").orElse(null));
            // the per-caller reload budget is the caller's own; a plain test still answers from the kept index
            assertEquals("reload asked", ok(test(c, AUTHOR, c.exact, KEYS.get(0), ",\"refresh\":true"))
                    .get("index").get("reloaded").asText());
            ok(test(c, FULL, c.exact, KEYS.get(0), ""));

            // every call: the standard burst of 20 per caller (2 plain and 3 reload calls, the refused one included, spent above)
            int answered = 0;
            HttpResponse<String> last = null;
            for (int i = 0; i < 25; i++) {
                last = test(c, FULL, c.exact, KEYS.get(0), "");
                if (last.statusCode() != 200) break;
                answered++;
            }
            assertEquals(429, last.statusCode(), last.body());
            assertEquals(20 - 5, answered, "the burst is 20 calls per caller");

            // a cold index is a build too (dropped from the 8-slot cache): AUTHOR spent one build above, so two more
            // cold plain tests build and the third is refused - only refresh: true being charged would let it through
            for (int i = 0; i < 2; i++) {
                LookupRoutes.dropWarmForTest();
                assertEquals("first use", ok(test(c, AUTHOR, c.exact, KEYS.get(0), "")).get("index").get("reloaded").asText());
            }
            LookupRoutes.dropWarmForTest();
            HttpResponse<String> cold = test(c, AUTHOR, c.exact, KEYS.get(0), "");
            assertEquals(429, cold.statusCode(), "a cold rebuild spends the build budget: " + cold.body());
        }
    }
}
