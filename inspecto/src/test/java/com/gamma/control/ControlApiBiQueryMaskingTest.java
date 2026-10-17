package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.entitystore.MaskTokens;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-QUERY-MASKING-1: classified entity keys in BI result rows are masked unless the caller holds
 * {@code canRevealLinkEntities}. Real HTTP + real DuckDB under an ARMED authenticator, so every capability check is real
 * (a request with no Subject is unrestricted, which would make a swapped capability invisible).
 *
 * <p>Users: {@code viewer} holds nothing, {@code revealer} holds {@code canRevealLinkEntities}, {@code author} holds
 * {@code canAuthorWorkbench} (to issue a share). Design: {@code docs/superpower/bi-query-masking-design.md}.
 */
class ControlApiBiQueryMaskingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "test-secret-0123456789"; // secret-allow: a fixed test fixture value
    private static final String A = "441700900101", B = "441700900202", C = "441700900303";

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        String user = a.substring(7);
        Set<String> caps = switch (user) {
            case "revealer" -> Set.of("canRevealLinkEntities");
            case "author" -> Set.of("canAuthorWorkbench");
            default -> Set.of();
        };
        return Optional.of(new Subject(user, caps));
    };

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("bi.share.secret");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        Authenticators.forTest(FAKE);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** {@code calls_ds}: msisdn (classified MSISDN) + email (classified PII) + region + amount; 3 subscribers, 4 rows. */
    private void seed(Ctx c, Map<String, Object> extra) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('" + A + "','a@x.io','EU',10.0),('" + A + "','a@x.io','EU',5.0),('" + B + "','b@x.io','US',7.0),"
                        + "('" + C + "','c@x.io','US',1.0)) AS t(msisdn,email,region,amount)", "2026-07-08T00:00:00Z"));
        ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
        Map<String, Object> ds = new java.util.LinkedHashMap<>(Map.of("view", "calls_view", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "email", "classification", "PII"),
                Map.of("name", "region"), Map.of("name", "amount"))));
        ds.putAll(extra);
        reg.write("dataset", "calls_ds", ds);
        // A Dataset with NO classified column over the same shape.
        reg.write("dataset", "plain_ds", Map.of("view", "calls_view", "columns", List.of(
                Map.of("name", "msisdn"), Map.of("name", "email"), Map.of("name", "region"), Map.of("name", "amount"))));
    }

    private HttpResponse<String> post(Ctx c, String user, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Ctx c, String user, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer " + user).GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> bi(Ctx c, String user, String dataset, String rest) throws Exception {
        return post(c, user, "/bi/query", "{\"dataset\":\"" + dataset + "\"," + rest + "}");
    }

    private static final String BY_MSISDN = "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"msisdn\"]";

    private static void assertNoRaw(String body) {
        for (String raw : List.of(A, B, C)) assertFalse(body.contains(raw), "raw key " + raw + " must not appear: " + body);
    }

    private static List<String> column(JsonNode rows, String name) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get(name).asText()));
        return out;
    }

    private void writeMode(Ctx c, String mode) throws Exception {
        Files.writeString(c.root.resolve("link-analysis.toon"), "masking_mode: " + mode + "\n");
    }

    // ── /bi/query ───────────────────────────────────────────────────────────────────

    @Test
    void aViewerSeesTheAliasAndARevealerSeesTheRawKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            HttpResponse<String> viewer = bi(c, "viewer", "calls_ds", BY_MSISDN);
            assertEquals(200, viewer.statusCode(), viewer.body());
            JsonNode v = V1Body.of(viewer.body());
            assertNoRaw(viewer.body());
            assertTrue(column(v.get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:") && s.length() == 7 + 16),
                    v.get("rows").toString());
            assertTrue(v.get("masking").get("masked").asBoolean());
            assertEquals("msisdn", v.get("masking").get("columns").get(0).asText());
            assertEquals("string", v.get("resultSet").get("columns").get(0).get("type").asText(), "a masked column is a string");

            JsonNode r = V1Body.of(bi(c, "revealer", "calls_ds", BY_MSISDN).body());
            assertEquals(Set.of(A, B, C), Set.copyOf(column(r.get("rows"), "msisdn")));
            assertFalse(r.get("masking").get("masked").asBoolean());
            assertEquals(0, r.get("masking").get("columns").size());
        }
    }

    @Test
    void theAliasIsTheSpaceKeyAliasAndAGroupedQueryKeepsItsGroups(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            JsonNode v = V1Body.of(bi(c, "viewer", "calls_ds", BY_MSISDN).body());
            JsonNode r = V1Body.of(bi(c, "revealer", "calls_ds", BY_MSISDN).body());
            assertEquals(3, v.get("rows").size(), "three subscribers, three groups, masked or not");
            assertEquals(r.get("rows").size(), v.get("rows").size());
            // The Space identity key (D-M7: <root>.secrets/.risk-score-mask.key) is the one Link Analysis, Entity Lists, Risk and
            // Anomaly Scores and audit reads use too: one id, one alias.
            byte[] spaceKey = MaskTokens.spaceKey(c.root);
            assertTrue(Files.isRegularFile(com.gamma.util.SpaceSecretKeys.keyFile(c.root, ".risk-score-mask.key")));
            assertFalse(Files.exists(c.root.resolve("audit").resolve("entity-facts").resolve("mask.key")), "the old home is not used");
            assertEquals(MaskTokens.token(spaceKey, A), com.gamma.mask.EvidenceMasker.forSpace(c.root).tokenFor(A),
                    "a Risk Score key reads as the same alias as the BI cell");
            assertEquals(Set.of(MaskTokens.token(spaceKey, A), MaskTokens.token(spaceKey, B), MaskTokens.token(spaceKey, C)),
                    Set.copyOf(column(v.get("rows"), "msisdn")));
            // The per-group counts follow the same key: A has two rows, the others one.
            for (JsonNode row : v.get("rows"))
                assertEquals(row.get("msisdn").asText().equals(MaskTokens.token(spaceKey, A)) ? 2 : 1, row.get("count").asInt());
        }
    }

    @Test
    void aDatasetWithNoClassifiedColumnIsUntouched(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            JsonNode viewer = V1Body.of(bi(c, "viewer", "plain_ds", BY_MSISDN).body());
            JsonNode revealer = V1Body.of(bi(c, "revealer", "plain_ds", BY_MSISDN).body());
            assertFalse(viewer.has("masking"), "no masking block when nothing is classified");
            assertEquals(revealer.get("rows"), viewer.get("rows"));
            assertEquals(revealer.get("resultSet"), viewer.get("resultSet"));
            assertEquals(Set.of(A, B, C), Set.copyOf(column(viewer.get("rows"), "msisdn")), "raw for everyone");
        }
    }

    @Test
    void typedModeLeavesAnUnclaimedPiiColumnRawAndModeAllMasksIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            String byEmail = "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"email\"]";
            JsonNode typed = V1Body.of(bi(c, "viewer", "calls_ds", byEmail).body());
            assertTrue(column(typed.get("rows"), "email").contains("a@x.io"),
                    "typed (default): PII is not claimed by a masked Entity Type, so it stays as Link Analysis leaves it");
            writeMode(c, "all");
            HttpResponse<String> all = bi(c, "viewer", "calls_ds", byEmail);
            assertFalse(all.body().contains("a@x.io"), all.body());
            assertTrue(column(V1Body.of(all.body()).get("rows"), "email").stream().allMatch(s -> s.startsWith("masked:")));
            assertEquals("all", V1Body.of(all.body()).get("masking").get("mode").asText());
            writeMode(c, "none");
            JsonNode none = V1Body.of(bi(c, "viewer", "calls_ds", BY_MSISDN).body());
            assertFalse(none.has("masking"), "maskingMode none opts BI out");
            assertEquals(Set.of(A, B, C), Set.copyOf(column(none.get("rows"), "msisdn")));
        }
    }

    @Test
    void anInferenceChannelOnAMaskedColumnIsRefusedForAViewerOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            List<String> probes = List.of(
                    "\"measures\":[{\"agg\":\"count\"}],\"filters\":[{\"field\":\"msisdn\",\"op\":\"like\",\"value\":\"4417009%\"}]",
                    "\"measures\":[{\"agg\":\"count\"}],\"filters\":[{\"field\":\"msisdn\",\"op\":\"=\",\"value\":\"" + A + "\"}]",
                    "\"measures\":[{\"agg\":\"count\"}],\"when\":{\"op\":\"and\",\"items\":[{\"kind\":\"condition\",\"field\":\"msisdn\",\"operator\":\"=\",\"value\":\"" + A + "\"}]}",
                    "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"msisdn\"],\"orderBy\":[{\"field\":\"msisdn\",\"dir\":\"desc\"}]",
                    "\"measures\":[{\"agg\":\"max\",\"field\":\"msisdn\"}]");
            for (String p : probes) {
                HttpResponse<String> viewer = bi(c, "viewer", "calls_ds", p);
                assertEquals(422, viewer.statusCode(), p + " -> " + viewer.body());
                assertNoRaw(viewer.body());
                assertEquals(200, bi(c, "revealer", "calls_ds", p).statusCode(), "a revealer is unrestricted: " + p);
            }
            // Allowed for a viewer: a count of distinct keys (a number, never the key) and a filter on an unclassified column.
            assertEquals(200, bi(c, "viewer", "calls_ds", "\"measures\":[{\"agg\":\"countDistinct\",\"field\":\"msisdn\"}]").statusCode());
            JsonNode eu = V1Body.of(bi(c, "viewer", "calls_ds",
                    "\"measures\":[{\"agg\":\"count\"}],\"filters\":[{\"field\":\"region\",\"op\":\"=\",\"value\":\"EU\"}]").body());
            assertEquals(2, eu.get("rows").get(0).get("count").asInt());
        }
    }

    @Test
    void theChartsCosmeticCategoryOrderOverAMaskedKeyIsDroppedNotRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            String cosmetic = "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"msisdn\"],"
                    + "\"orderBy\":[{\"field\":\"msisdn\",\"dir\":\"asc\",\"cosmetic\":true}]";
            HttpResponse<String> viewer = bi(c, "viewer", "calls_ds", cosmetic);
            assertEquals(200, viewer.statusCode(), viewer.body());
            assertNoRaw(viewer.body());
            JsonNode v = V1Body.of(viewer.body());
            assertEquals(3, v.get("rows").size());
            assertTrue(column(v.get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")));
            assertFalse(v.get("sql").asText().contains("\"msisdn\" ASC"), "no ordering by the raw key: " + v.get("sql"));
            assertTrue(v.get("sql").asText().contains("ORDER BY \"count\" DESC"), v.get("sql").asText());
            assertEquals(422, bi(c, "viewer", "calls_ds", "\"groupBy\":[\"msisdn\"],\"orderBy\":[{\"field\":\"msisdn\",\"dir\":\"asc\",\"cosmetic\":true}]").statusCode(),
                    "no Measure to order by: the key-order fallback would leak, so it is refused");
            // An ordering that is NOT cosmetic is still refused, and so is a cosmetic flag on a hidden aggregate.
            assertEquals(422, bi(c, "viewer", "calls_ds", cosmetic.replace(",\"cosmetic\":true", "")).statusCode());
            assertEquals(422, bi(c, "viewer", "calls_ds", "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"region\"],"
                    + "\"orderBy\":[{\"field\":\"msisdn\",\"dir\":\"asc\",\"agg\":\"min\",\"cosmetic\":true}]").statusCode());
            // A cosmetic order over an unmasked dimension is kept; a revealer keeps the order on the masked one too.
            JsonNode region = V1Body.of(bi(c, "viewer", "calls_ds", "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"region\"],"
                    + "\"orderBy\":[{\"field\":\"region\",\"dir\":\"desc\",\"cosmetic\":true}]").body());
            assertEquals(List.of("US", "EU"), column(region.get("rows"), "region"));
            JsonNode r = V1Body.of(bi(c, "revealer", "calls_ds", cosmetic).body());
            assertEquals(List.of(A, B, C), column(r.get("rows"), "msisdn"), "an admin with reveal is unaffected");
        }
    }

    @Test
    void aCalculatedColumnThatNamesAMaskedColumnIsMaskedToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of("calculated", List.of(Map.of("name", "msisdn_up", "expr", "upper(msisdn)"))));
            HttpResponse<String> viewer = get(c, "viewer", "/datasets/calls_ds/rows?limit=10");
            assertEquals(200, viewer.statusCode(), viewer.body());
            assertNoRaw(viewer.body());
            JsonNode v = V1Body.of(viewer.body());
            assertTrue(column(v.get("rows"), "msisdn_up").stream().allMatch(s -> s.startsWith("masked:")), v.get("rows").toString());
            assertTrue(column(v.get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")));
            assertEquals(4, v.get("rows").size());
            assertTrue(column(V1Body.of(get(c, "revealer", "/datasets/calls_ds/rows?limit=10").body()).get("rows"), "msisdn").contains(A));
        }
    }

    @Test
    void anUntraceableLineageFailsClosedUnlessAColumnIsDeclaredClean(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            // A sibling Dataset classifies msisdn over the same store; a view-backed Dataset over it cannot be traced by name.
            reg.write("dataset", "sib_ds", Map.of("physicalRef", "calls_view",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"))));
            reg.write("dataset", "bare_ds", Map.of("view", "calls_view"));
            reg.write("dataset", "declared_ds", Map.of("view", "calls_view", "columns", List.of(
                    Map.of("name", "region"), Map.of("name", "amount"))));
            String byRegion = "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"region\"]";

            JsonNode bare = V1Body.of(bi(c, "viewer", "bare_ds", byRegion).body());
            assertTrue(column(bare.get("rows"), "region").stream().allMatch(s -> s.startsWith("masked:")),
                    "nothing is declared clean, so nothing is shown: " + bare.get("rows"));
            JsonNode declared = V1Body.of(bi(c, "viewer", "declared_ds", byRegion).body());
            assertEquals(Set.of("EU", "US"), Set.copyOf(column(declared.get("rows"), "region")), "declared clean stays raw");
            HttpResponse<String> key = bi(c, "viewer", "declared_ds", BY_MSISDN);
            assertNoRaw(key.body());
            assertTrue(column(V1Body.of(key.body()).get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")),
                    "a column the Dataset does not declare is not provably clean");
            assertEquals(Set.of("EU", "US"), Set.copyOf(column(
                    V1Body.of(bi(c, "revealer", "bare_ds", byRegion).body()).get("rows"), "region")), "a revealer is raw");
        }
    }

    // ── /queries/{id}/run ───────────────────────────────────────────────────────────

    @Test
    void aSavedQueryMasksByNameAndByAliasForAViewer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("query", "plain_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT msisdn, region, amount FROM calls_ds"));
            reg.write("query", "alias_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT msisdn AS m, upper(msisdn) AS u, region, count(*) AS n FROM calls_ds GROUP BY ALL"));
            reg.write("query", "clean_q", Map.of("type", "sql", "datasetId", "plain_ds",
                    "text", "SELECT msisdn AS m, region FROM plain_ds"));

            HttpResponse<String> plain = post(c, "viewer", "/queries/plain_q/run", null);
            assertEquals(200, plain.statusCode(), plain.body());
            assertNoRaw(plain.body());
            JsonNode p = V1Body.of(plain.body());
            assertTrue(column(p.get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")));
            assertEquals("EU", p.get("rows").get(0).get("region").asText(), "an unclassified column stays raw");
            assertTrue(p.get("masking").get("masked").asBoolean());

            HttpResponse<String> aliased = post(c, "viewer", "/queries/alias_q/run", null);
            assertEquals(200, aliased.statusCode(), aliased.body());
            assertNoRaw(aliased.body());
            JsonNode a = V1Body.of(aliased.body());
            assertTrue(column(a.get("rows"), "m").stream().allMatch(s -> s.startsWith("masked:")), "msisdn AS m");
            assertTrue(column(a.get("rows"), "u").stream().allMatch(s -> s.startsWith("masked:")), "upper(msisdn) AS u");
            assertTrue(a.get("rows").get(0).get("n").isNumber(), "a count stays a number");
            assertEquals(Set.of("EU", "US"), Set.copyOf(column(a.get("rows"), "region")));

            assertTrue(column(V1Body.of(post(c, "revealer", "/queries/plain_q/run", null).body()).get("rows"), "msisdn").contains(A));
            // A query over the unclassified Dataset: untouched, no block.
            JsonNode clean = V1Body.of(post(c, "viewer", "/queries/clean_q/run", null).body());
            assertFalse(clean.has("masking"));
            assertTrue(column(clean.get("rows"), "m").contains(A));
            // A caller-supplied sort on a masked column is an inference channel.
            assertEquals(422, post(c, "viewer", "/queries/plain_q/run", "{\"sort\":[{\"field\":\"msisdn\",\"dir\":\"asc\"}]}").statusCode());
            assertEquals(200, post(c, "revealer", "/queries/plain_q/run", "{\"sort\":[{\"field\":\"msisdn\",\"dir\":\"asc\"}]}").statusCode());
        }
    }

    // ── public share ────────────────────────────────────────────────────────────────

    @Test
    void aPublicShareRecipientNeverSeesTheRawKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", SECRET);
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("widget", "calls_w", Map.of("kind", "table", "datasetId", "calls_ds"));
            reg.write("dashboard", "board", Map.of("title", "Calls", "widgets", List.of("calls_w")));
            String token = V1Body.of(post(c, "author", "/dashboards/board/share", null).body()).get("token").asText();

            HttpResponse<String> pub = post(c, null, "/public/dashboards/" + token + "/query",
                    "{\"dataset\":\"calls_ds\"," + BY_MSISDN + "}");
            assertEquals(200, pub.statusCode(), pub.body());
            assertNoRaw(pub.body());
            JsonNode d = V1Body.of(pub.body());
            assertEquals(3, d.get("rows").size());
            assertTrue(column(d.get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")));
            // The recipient cannot bisect the key either.
            assertEquals(422, post(c, null, "/public/dashboards/" + token + "/query", "{\"dataset\":\"calls_ds\","
                    + "\"measures\":[{\"agg\":\"count\"}],\"when\":{\"op\":\"and\",\"items\":[{\"kind\":\"condition\","
                    + "\"field\":\"msisdn\",\"operator\":\"=\",\"value\":\"" + A + "\"}]}}").statusCode());
        }
    }

    // ── gaps closed after the first slice (BI-QUERY-MASKING-1 follow-up) ─────────────

    @Test
    void aCallerSuppliedParameterCannotProbeAMaskedKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("query", "probe_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT region, count(*) AS n FROM calls_ds WHERE msisdn LIKE $prefix GROUP BY region",
                    "parameters", List.of(Map.of("name", "prefix", "type", "string", "default", "%"))));
            reg.write("query", "region_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT region, count(*) AS n FROM calls_ds WHERE region = $r GROUP BY region",
                    "parameters", List.of(Map.of("name", "r", "type", "string", "default", "EU"))));
            String probe = "{\"parameters\":{\"prefix\":\"4417009001%\"}}";
            assertEquals(422, post(c, "viewer", "/queries/probe_q/run", probe).statusCode(), "the oracle is refused");
            assertEquals(200, post(c, "revealer", "/queries/probe_q/run", probe).statusCode(), "a revealer is unrestricted");
            assertEquals(200, post(c, "viewer", "/queries/probe_q/run", null).statusCode(), "the author's default is trusted");
            assertEquals(200, post(c, "viewer", "/queries/region_q/run", "{\"parameters\":{\"r\":\"US\"}}").statusCode(),
                    "a parameter over an unclassified column is not an oracle");
        }
    }

    @Test
    void aNumericDerivationOfANumericKeyIsMaskedAndACountIsNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("query", "cast_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT CAST(msisdn AS BIGINT) AS m, msisdn::BIGINT + 1 AS m1, amount AS safe_amount, "
                            + "count(*) AS n FROM calls_ds GROUP BY ALL"));
            reg.write("query", "hide_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT CAST(msisdn AS BIGINT) AS amount, region FROM calls_ds"));
            reg.write("query", "case_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT region, count(CASE WHEN msisdn = '" + A + "' THEN 1 END) AS hits FROM calls_ds GROUP BY region"));
            HttpResponse<String> r = post(c, "viewer", "/queries/cast_q/run", null);
            assertEquals(200, r.statusCode(), r.body());
            assertNoRaw(r.body());
            assertFalse(r.body().contains("441700900102"), "the +1 derivation must not appear either: " + r.body());
            JsonNode v = V1Body.of(r.body());
            assertTrue(column(v.get("rows"), "m").stream().allMatch(s -> s.startsWith("masked:")), v.get("rows").toString());
            assertTrue(column(v.get("rows"), "m1").stream().allMatch(s -> s.startsWith("masked:")));
            assertTrue(v.get("rows").get(0).get("safe_amount").isNumber(), "an unrelated numeric stays a number");
            assertTrue(v.get("rows").get(0).get("n").isNumber(), "a count stays a number");

            // Aliasing the derivation to a name the Dataset declares clean does not hide it.
            JsonNode h = V1Body.of(post(c, "viewer", "/queries/hide_q/run", null).body());
            assertTrue(column(h.get("rows"), "amount").stream().allMatch(s -> s.startsWith("masked:")), h.get("rows").toString());
            // A count over a CASE on the key is a test of its value, not a count: masked.
            JsonNode k = V1Body.of(post(c, "viewer", "/queries/case_q/run", null).body());
            assertTrue(column(k.get("rows"), "hits").stream().allMatch(s -> s.startsWith("masked:")), k.get("rows").toString());

            JsonNode rev = V1Body.of(post(c, "revealer", "/queries/cast_q/run", null).body());
            assertTrue(rev.get("rows").get(0).get("m").isNumber(), "a revealer sees the number");
        }
    }

    @Test
    void aKpiMeasureThatReturnsARawKeyIsRefusedForAViewer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("kpi_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES (441700900101::BIGINT, 10.0, current_date), (441700900202::BIGINT, 5.0, current_date))"
                            + " AS t(msisdn, amount, seen_on)", "2026-07-08T00:00:00Z"));
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("dataset", "kpi_ds", Map.of("view", "kpi_view", "columns", List.of(
                    Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "amount"), Map.of("name", "seen_on"))));
            // The statistics (D-AN5) read the values too: a median or percentile IS a key on an odd-sized group.
            for (String agg : List.of("max", "min", "sum", "avg", "median", "p95", "stddev"))
                reg.write("kpi", "k_" + agg, Map.of("dataset", "kpi_ds", "measure", agg + "(msisdn)",
                        "timeField", "seen_on", "grain", "month", "comparison", "previous", "direction", "up"));
            reg.write("kpi", "k_cnt", Map.of("dataset", "kpi_ds", "measure", "countDistinct(msisdn)",
                    "timeField", "seen_on", "grain", "month", "comparison", "previous", "direction", "up"));
            for (String id : List.of("k_max", "k_min", "k_sum", "k_avg", "k_median", "k_p95", "k_stddev")) {
                HttpResponse<String> viewer = get(c, "viewer", "/kpis/" + id + "/value");
                assertEquals(422, viewer.statusCode(), id + " -> " + viewer.body());
                assertEquals(200, get(c, "revealer", "/kpis/" + id + "/value").statusCode(), id + " for a revealer");
            }
            HttpResponse<String> cnt = get(c, "viewer", "/kpis/k_cnt/value");
            assertEquals(200, cnt.statusCode(), "a distinct count is a number, never the key: " + cnt.body());
            assertEquals(2, V1Body.of(cnt.body()).get("value").asInt());
        }
    }

    @Test
    void aRuleSimulationMasksTheKeyAndRefusesAHoleOnIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("rule-template", "big", Map.of("id", "big", "name", "big", "source", "calls_ds",
                    "paramSql", "SELECT msisdn, amount FROM calls_ds WHERE amount > :floor",
                    "params", List.of(Map.of("name", "floor", "field", "amount", "operator", "gt", "value", "0"))));
            reg.write("rule-template", "byKey", Map.of("id", "byKey", "name", "byKey", "source", "calls_ds",
                    "paramSql", "SELECT region, amount FROM calls_ds WHERE msisdn = :who",
                    "params", List.of(Map.of("name", "who", "field", "msisdn", "operator", "eq", "value", A))));
            // canAuthorWorkbench alone does not reveal: user "author" holds only that capability.
            HttpResponse<String> sim = post(c, "author", "/rule-templates/big/simulate", "{}");
            assertEquals(200, sim.statusCode(), sim.body());
            assertNoRaw(sim.body());
            JsonNode v = V1Body.of(sim.body());
            assertTrue(column(v.get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")), v.get("rows").toString());
            assertTrue(v.get("masking").get("masked").asBoolean());
            // The hole bound to a masked column is an oracle: refused when the caller supplies the value.
            assertEquals(422, post(c, "author", "/rule-templates/byKey/simulate", "{\"params\":{\"who\":\"" + A + "\"}}").statusCode());
        }
    }
}
