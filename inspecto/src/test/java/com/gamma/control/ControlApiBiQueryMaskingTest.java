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
 * {@code canAuthorWorkbench} (to issue a share). Design: {@code docs/archived-documents/plans-archive/bi-query-masking-design.md}.
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

    /** These tests are about masking by default, which is Standard: this module's own classpath is Basic, where it is off. */
    @org.junit.jupiter.api.BeforeEach
    void standardEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
    }

    @AfterEach
    void restoreEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

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

    /**
     * ANOMALY-DETECTION-RESIDUALS-1 (a): the telco-fraud top-10 anomaly tile, sent as the SPA's table plugin sends it
     * (group by entity key + band, max score, ORDER BY the score, row limit 10) over a scores-shaped store whose Dataset
     * classifies {@code entity_key} MSISDN: a viewer gets the top 10 by score under the alias, never a number; a
     * revealer gets the same rows raw. Twelve entities, so the limit really cuts.
     */
    @Test
    void anAnomalyTopTenTileIsMaskedOrderedAndLimitedForAViewer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            StringBuilder values = new StringBuilder();
            for (int i = 1; i <= 12; i++)
                values.append(i == 1 ? "" : ",").append("('4417009009").append(String.format("%02d", i)).append("',")
                        .append(i * 7.5).append(",'").append(i > 8 ? "high" : "normal").append("')");
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("scores_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + values + ") AS t(entity_key,score,band)", "2026-07-08T00:00:00Z"));
            new ComponentStore(c.root.resolve("registry")).write("dataset", "tf_subscriber_anomaly_latest", Map.of("view", "scores_view",
                    "columns", List.of(Map.of("name", "entity_key", "classification", "MSISDN"), Map.of("name", "score"), Map.of("name", "band"))));
            String tile = "\"groupBy\":[\"entity_key\",\"band\"],\"measures\":[{\"agg\":\"max\",\"field\":\"score\"}],"
                    + "\"orderBy\":[{\"field\":\"max_score\",\"dir\":\"desc\"}],\"limit\":10";
            HttpResponse<String> viewer = bi(c, "viewer", "tf_subscriber_anomaly_latest", tile);
            assertEquals(200, viewer.statusCode(), viewer.body());
            HttpResponse<String> revealer = bi(c, "revealer", "tf_subscriber_anomaly_latest", tile);
            assertEquals(200, revealer.statusCode(), revealer.body());
            JsonNode vRows = V1Body.of(viewer.body()).get("rows"), rRows = V1Body.of(revealer.body()).get("rows");
            assertEquals(10, vRows.size(), viewer.body());
            assertEquals(column(rRows, "max_score"), column(vRows, "max_score"), "same top 10, same order");
            assertEquals("90.0", column(vRows, "max_score").get(0), "highest score first");
            byte[] key = MaskTokens.spaceKey(c.root);
            for (int i = 0; i < 10; i++) {
                String raw = rRows.get(i).get("entity_key").asText();
                assertTrue(raw.startsWith("4417009009"), raw);
                assertEquals(MaskTokens.token(key, raw), vRows.get(i).get("entity_key").asText());
                assertFalse(viewer.body().contains(raw), "raw " + raw + " reached a viewer");
            }
        }
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

    /** C1 (D-AN12): a ratio of a value-revealing aggregate, or a window, over a masked column is refused as its flat cousins are. */
    @Test
    void aRatioOrWindowOverAMaskedColumnIsRefusedForAViewer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            String ratioOverMasked = "\"groupBy\":[\"region\"],\"ratios\":[{\"num\":{\"agg\":\"max\",\"field\":\"msisdn\"},"
                    + "\"den\":{\"agg\":\"count\"}}]";
            String windowOverMasked = "\"measures\":[{\"agg\":\"count\"}],\"window\":{\"field\":\"msisdn\",\"asOf\":\"2026-07-08\"}";
            assertEquals(422, bi(c, "viewer", "calls_ds", ratioOverMasked).statusCode());
            assertEquals(422, bi(c, "viewer", "calls_ds", windowOverMasked).statusCode());
            // a ratio over unclassified columns is fine, for the viewer too, and the revealer may read the masked one
            String clean = "\"groupBy\":[\"region\"],\"ratios\":[{\"num\":{\"agg\":\"sum\",\"field\":\"amount\"},"
                    + "\"den\":{\"agg\":\"count\"}}]";
            HttpResponse<String> ok = bi(c, "viewer", "calls_ds", clean);
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals(7.5, V1Body.of(ok.body()).get("rows").get(0).get("sum_amount_per_count").asDouble(), 1e-12);
            assertEquals(200, bi(c, "revealer", "calls_ds", ratioOverMasked).statusCode());
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

            // A bound saved query over that untraceable lineage: an undeclared column renamed to a declared-clean name
            // (email AS region) must not be filterable either (BI-MASKING-ROW-VALUE-LEAKS-1).
            reg.write("query", "email_q", Map.of("type", "sql", "datasetId", "declared_ds",
                    "text", "SELECT email AS region, amount FROM declared_ds"));
            String probe = "\"query\":\"email_q\",\"measures\":[{\"agg\":\"count\"}],"
                    + "\"filters\":[{\"field\":\"region\",\"op\":\"like\",\"value\":\"4417009001%\"}]";
            assertEquals(422, bi(c, "viewer", "declared_ds", probe).statusCode());
            assertEquals(200, bi(c, "revealer", "declared_ds", probe).statusCode());

            // A grain bucket of a date over that untraceable lineage is not shown raw through a bound Widget either.
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("dob_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('" + A + "', DATE '1980-01-15'), ('" + B + "', DATE '1980-01-20')) AS t(msisdn, dob)",
                    "2026-07-08T00:00:00Z"));
            reg.write("dataset", "sib2_ds", Map.of("physicalRef", "dob_view",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"))));
            reg.write("dataset", "un_ds", Map.of("view", "dob_view"));
            reg.write("query", "un_q", Map.of("type", "sql", "datasetId", "un_ds", "text", "SELECT * FROM un_ds"));
            JsonNode grain = V1Body.of(bi(c, "viewer", "un_ds", "\"query\":\"un_q\",\"measures\":[{\"agg\":\"count\"}],"
                    + "\"groupBy\":[\"dob\"],\"grains\":{\"dob\":\"month\"}").body());
            assertFalse(grain.get("rows").isEmpty(), grain.toString());
            assertTrue(column(grain.get("rows"), "dob").stream().allMatch(s -> s.startsWith("masked:")),
                    "an untraceable lineage withholds the grain bucket: " + grain.get("rows"));
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
    void aSavedQueryWhosePositionsCannotBeProvenMasksEveryCell(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // BI-MASKING-SQL-SHAPE-LEAKS-1: a multi-column item shifts the positions, COLUMNS() names the key by pattern.
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            Map<String, String> texts = Map.of(
                    "shift_q", "SELECT count(COLUMNS('region|amount')), max(CAST(msisdn AS BIGINT)) AS amount FROM calls_ds",
                    "unnest_q", "SELECT unnest({'a': region, 'b': amount}), CAST(msisdn AS BIGINT) AS m FROM calls_ds",
                    "pattern_q", "SELECT CAST(COLUMNS('msi.*') AS BIGINT) + 0 AS v FROM calls_ds",
                    "replace_q", "SELECT count(COLUMNS(* REPLACE (CASE WHEN msisdn = '" + A + "' THEN 1 END AS msisdn))) FROM calls_ds");
            for (Map.Entry<String, String> e : texts.entrySet())
                reg.write("query", e.getKey(), Map.of("type", "sql", "datasetId", "calls_ds", "text", e.getValue()));
            for (String id : texts.keySet()) {
                HttpResponse<String> r = post(c, "viewer", "/queries/" + id + "/run", null);
                assertEquals(200, r.statusCode(), id + " -> " + r.body());
                V1Body.of(r.body()).get("rows").forEach(row -> row.forEach(cell ->
                        assertTrue(cell.isNull() || cell.asText().startsWith("masked:"), id + " -> " + row)));
                assertEquals(200, post(c, "revealer", "/queries/" + id + "/run", null).statusCode(), "a revealer: " + id);
            }
        }
    }

    @Test
    void aSavedQueryThatRenamesTheKeyInFromOrReadsTheRowMasksEveryCell(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // BI-MASKING-ROW-VALUE-LEAKS-1: a FROM rename (column alias list, table function, UNPIVOT) or the row as a value.
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            Map<String, String> texts = Map.of(
                    "alias_q", "SELECT CAST(m AS BIGINT) AS amount FROM calls_ds t(m)",
                    "tablefn_q", "SELECT unnest AS amount FROM calls_ds, unnest([CAST(msisdn AS BIGINT)])",
                    "unpivot_q", "SELECT value AS region FROM calls_ds UNPIVOT (value FOR col IN (msisdn))",
                    "row_q", "SELECT CAST(c['msisdn'] AS BIGINT) AS amount, to_json(c) AS j FROM calls_ds c");
            for (Map.Entry<String, String> e : texts.entrySet())
                reg.write("query", e.getKey(), Map.of("type", "sql", "datasetId", "calls_ds", "text", e.getValue()));
            for (String id : texts.keySet()) {
                HttpResponse<String> r = post(c, "viewer", "/queries/" + id + "/run", null);
                assertEquals(200, r.statusCode(), id + " -> " + r.body());
                JsonNode rows = V1Body.of(r.body()).get("rows");
                assertNoRaw(rows.toString());
                rows.forEach(row -> row.forEach(cell ->
                        assertTrue(cell.isNull() || cell.asText().startsWith("masked:"), id + " -> " + row)));
                assertEquals(200, post(c, "revealer", "/queries/" + id + "/run", null).statusCode(), "a revealer: " + id);
            }
            // A caller sort on the author's alias of the key would return the rows in raw key order next to the alias.
            reg.write("query", "k_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT msisdn AS k, region, count(*) AS n FROM calls_ds GROUP BY ALL"));
            assertEquals(422, post(c, "viewer", "/queries/k_q/run", "{\"sort\":[{\"field\":\"k\",\"dir\":\"desc\"}]}").statusCode());
            assertEquals(200, post(c, "revealer", "/queries/k_q/run", "{\"sort\":[{\"field\":\"k\",\"dir\":\"desc\"}]}").statusCode());
            assertEquals(200, post(c, "viewer", "/queries/k_q/run", "{\"sort\":[{\"field\":\"n\",\"dir\":\"desc\"}]}").statusCode(),
                    "a count stays sortable");

            // A Widget bound to a saved query: a bare key keeps the Space alias (charts keep their labels), a derived value
            // under the key's name does not.
            reg.write("query", "bare_q", Map.of("type", "sql", "datasetId", "calls_ds", "text", "SELECT msisdn, region FROM calls_ds"));
            reg.write("query", "prefix_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT substr(msisdn, 1, 4) AS msisdn, region FROM calls_ds"));
            String spaceAlias = "masked:[0-9a-f]{16}";
            JsonNode run = V1Body.of(post(c, "viewer", "/queries/bare_q/run", null).body());
            Set<String> runAliases = Set.copyOf(column(run.get("rows"), "msisdn"));
            JsonNode bare = V1Body.of(bi(c, "viewer", "calls_ds", "\"query\":\"bare_q\"," + BY_MSISDN).body());
            assertTrue(column(bare.get("rows"), "msisdn").stream().allMatch(s -> s.matches(spaceAlias)), bare.get("rows").toString());
            assertTrue(runAliases.containsAll(column(bare.get("rows"), "msisdn")), "one id, one alias: " + bare.get("rows"));
            JsonNode prefix = V1Body.of(bi(c, "viewer", "calls_ds", "\"query\":\"prefix_q\"," + BY_MSISDN).body());
            assertTrue(column(prefix.get("rows"), "msisdn").stream().noneMatch(s -> s.matches(spaceAlias)), prefix.get("rows").toString());
            // An author's name collision (blank-padded, case-only, or a Measure id) does not hand a chosen value the alias.
            Map<String, String> collide = Map.of(
                    "pad_q", "SELECT msisdn AS \" k\", '" + A + "' AS \"k\" FROM calls_ds",
                    "case_q2", "SELECT substr(msisdn, 1, 4) AS \"K\", msisdn AS \"k\" FROM calls_ds",
                    "id_q", "SELECT msisdn AS \"count\", region FROM calls_ds");
            for (Map.Entry<String, String> e : collide.entrySet())
                reg.write("query", e.getKey(), Map.of("type", "sql", "datasetId", "calls_ds", "text", e.getValue()));
            for (String id : List.of("pad_q", "case_q2")) {
                JsonNode w = V1Body.of(bi(c, "viewer", "calls_ds",
                        "\"query\":\"" + id + "\",\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"k\"]").body());
                w.get("rows").forEach(row -> row.forEach(cell ->
                        assertFalse(cell.asText().matches(spaceAlias), id + " -> " + row)));
            }
            JsonNode idw = V1Body.of(bi(c, "viewer", "calls_ds",
                    "\"query\":\"id_q\",\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"region\"]").body());
            idw.get("rows").forEach(row -> assertFalse(row.path("count").asText().matches(spaceAlias), "id_q -> " + row));

            // The bound path parses the SAVED text: a FROM rename, the row as a value, a self-join star never come out raw.
            Map<String, String> bound = Map.of(
                    "brename_q", "SELECT m AS amount, region FROM calls_ds t(m)",
                    "brow_q", "SELECT c, region FROM calls_ds c",
                    "bjoin_q", "SELECT * FROM calls_ds a JOIN calls_ds b USING (region)");
            for (Map.Entry<String, String> e : bound.entrySet())
                reg.write("query", e.getKey(), Map.of("type", "sql", "datasetId", "calls_ds", "text", e.getValue()));
            Map<String, String> byCol = Map.of("brename_q", "amount", "brow_q", "c", "bjoin_q", "msisdn_1");
            for (Map.Entry<String, String> e : byCol.entrySet()) {
                HttpResponse<String> r = bi(c, "viewer", "calls_ds",
                        "\"query\":\"" + e.getKey() + "\",\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"" + e.getValue() + "\"]");
                if (r.statusCode() == 200) {
                    JsonNode rows = V1Body.of(r.body()).get("rows");
                    assertNoRaw(rows.toString());
                    rows.forEach(row -> assertFalse(row.path(e.getValue()).asText().matches(spaceAlias), e.getKey() + " -> " + row));
                } else assertEquals(422, r.statusCode(), e.getKey() + " -> " + r.body());
            }

            // The key aliased to a declared-clean name: a Widget's order or filter on that name ranks or bisects the raw key.
            reg.write("query", "wregion_q", Map.of("type", "sql", "datasetId", "calls_ds", "text", "SELECT msisdn AS region, amount FROM calls_ds"));
            for (String probe : List.of(
                    "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"region\"],\"orderBy\":[{\"field\":\"region\",\"dir\":\"desc\"}]",
                    "\"measures\":[{\"agg\":\"count\"}],\"filters\":[{\"field\":\"region\",\"op\":\"like\",\"value\":\"4417009002%\"}]")) {
                assertEquals(422, bi(c, "viewer", "calls_ds", "\"query\":\"wregion_q\"," + probe).statusCode(), probe);
                assertEquals(200, bi(c, "revealer", "calls_ds", "\"query\":\"wregion_q\"," + probe).statusCode(), "a revealer: " + probe);
            }
            // DuckDB renames a repeated output name (msisdn_1, region_1, k_1): the copy of the key must not come out raw, and a
            // value the author picked must not get the key's alias under the renamed name.
            Map<String, String[]> dedup = Map.of(
                    "dup1_q", new String[]{"SELECT msisdn, msisdn FROM calls_ds", "msisdn_1"},
                    "dup2_q", new String[]{"SELECT *, msisdn AS region FROM calls_ds", "region_1"},
                    "dup3_q", new String[]{"SELECT region AS k, msisdn AS k FROM calls_ds", "k_1"},
                    "dup4_q", new String[]{"SELECT msisdn AS k, '" + A + "' AS k, msisdn AS k_1 FROM calls_ds", "k_1_1"},
                    "dup5_q", new String[]{"SELECT true, msisdn AS \"true\", region FROM calls_ds", "true_1"},
                    // DuckDB renames the 2nd k to k_1 and the author's own k_1 to k_1_1: the typed constant lands on k_1
                    "dup6_q", new String[]{"SELECT msisdn AS k, '" + B + "' AS k, msisdn AS k_1 FROM calls_ds", "k_1"},
                    "dup7_q", new String[]{"SELECT substr(msisdn, 1, 1) AS d, substr(msisdn, 1, 1) AS d, msisdn AS d_1 FROM calls_ds", "d_1"});
            for (Map.Entry<String, String[]> e : dedup.entrySet()) {
                reg.write("query", e.getKey(), Map.of("type", "sql", "datasetId", "calls_ds", "text", e.getValue()[0]));
                HttpResponse<String> r = bi(c, "viewer", "calls_ds", "\"query\":\"" + e.getKey()
                        + "\",\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"" + e.getValue()[1] + "\"]");
                if (r.statusCode() == 200) {
                    JsonNode rows = V1Body.of(r.body()).get("rows");
                    assertNoRaw(rows.toString());
                    rows.forEach(row -> assertFalse(row.path(e.getValue()[1]).asText().matches(spaceAlias), e.getKey() + " -> " + row));
                } else assertEquals(422, r.statusCode(), e.getKey() + " -> " + r.body());
                assertEquals(422, bi(c, "viewer", "calls_ds", "\"query\":\"" + e.getKey() + "\",\"measures\":[{\"agg\":\"count\"}],"
                        + "\"filters\":[{\"field\":\"" + e.getValue()[1] + "\",\"op\":\"like\",\"value\":\"4417009001%\"}]").statusCode(),
                        "a filter on the renamed copy: " + e.getKey());
            }

            // A chart over a plain-star saved query keeps its clean categories and counts (the key column alone is masked).
            reg.write("query", "star_q", Map.of("type", "sql", "datasetId", "calls_ds", "text", "SELECT * FROM calls_ds"));
            JsonNode star = V1Body.of(bi(c, "viewer", "calls_ds",
                    "\"query\":\"star_q\",\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"region\"],"
                            + "\"orderBy\":[{\"field\":\"count\",\"dir\":\"desc\"}]").body());
            assertEquals(Set.of("EU", "US"), Set.copyOf(column(star.get("rows"), "region")), star.get("rows").toString());
            assertTrue(star.get("rows").get(0).get("count").isNumber(), "a count stays a number: " + star.get("rows"));
            // ...while a real clean column of a bound query stays filterable.
            assertEquals(200, bi(c, "viewer", "calls_ds", "\"query\":\"bare_q\",\"measures\":[{\"agg\":\"count\"}],"
                    + "\"filters\":[{\"field\":\"region\",\"op\":\"=\",\"value\":\"EU\"}]").statusCode());

            // A grain buckets a date key: the bucket must not get the key's alias, whatever the case of the grained name.
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("dob_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('" + A + "', DATE '1980-01-15'), ('" + B + "', DATE '1980-01-20')) AS t(msisdn, dob)",
                    "2026-07-08T00:00:00Z"));
            reg.write("dataset", "dob_ds", Map.of("view", "dob_view", "columns", List.of(
                    Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "dob", "classification", "MSISDN"))));
            reg.write("query", "dob_q", Map.of("type", "sql", "datasetId", "dob_ds", "text", "SELECT msisdn, dob FROM dob_ds"));
            JsonNode g = V1Body.of(bi(c, "viewer", "dob_ds", "\"query\":\"dob_q\",\"measures\":[{\"agg\":\"count\"}],"
                    + "\"groupBy\":[\"dob\",\"DOB\"],\"grains\":{\"dob\":\"month\"}").body());
            g.path("rows").forEach(row -> row.fields().forEachRemaining(f -> {
                if (f.getKey().equalsIgnoreCase("dob") || f.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("dob_"))
                    assertFalse(f.getValue().asText().matches(spaceAlias), "a grain bucket must not get the alias: " + row);
            }));
        }
    }

    /** BI-MASKING-GROUPBY-RENAME-1: DuckDB renames repeated output names in order (k, k_1, k_1_1), so the names are not the spec's. */
    @Test
    void aGroupByThatRepeatsANameIgnoringCaseIsRefusedForAViewer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, Map.of());
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("query", "c1_q", Map.of("type", "sql", "datasetId", "calls_ds",
                    "text", "SELECT '" + A + "' AS k, msisdn AS k_1 FROM calls_ds"));
            String count = "\"measures\":[{\"agg\":\"count\"}]";
            for (String probe : List.of(
                    "\"query\":\"c1_q\"," + count + ",\"groupBy\":[\"k\",\"K\",\"k_1\"]",   // k_1 = the typed constant, k_1_1 = the key
                    count + ",\"groupBy\":[\"msisdn\",\"MSISDN\"]",                           // the structured path: msisdn_1 raw
                    count + ",\"groupBy\":[\"region\",\"region\"]")) {
                HttpResponse<String> r = bi(c, "viewer", "calls_ds", probe);
                assertEquals(422, r.statusCode(), probe + " -> " + r.body());
                assertNoRaw(r.body());
            }
            assertEquals(200, bi(c, "viewer", "calls_ds", count + ",\"groupBy\":[\"region\",\"amount\"]").statusCode(),
                    "two different names stay allowed");
        }
    }

    /** BI-MASKING-NONWORD-KEY-NAME-1: a classified column named "msisdn no" is unseen by a word scan of the text. */
    @Test
    void aKeyColumnWhoseNameIsNotOneWordIsStillMasked(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("odd_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('" + A + "','EU'),('" + B + "','US')) AS t(\"msisdn no\", region)", "2026-07-08T00:00:00Z"));
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("dataset", "odd_ds", Map.of("view", "odd_view", "columns", List.of(
                    Map.of("name", "msisdn no", "classification", "MSISDN"), Map.of("name", "region"))));
            reg.write("query", "oddk_q", Map.of("type", "sql", "datasetId", "odd_ds",
                    "text", "SELECT \"msisdn no\" AS k, region FROM odd_ds"));
            reg.write("query", "oddmax_q", Map.of("type", "sql", "datasetId", "odd_ds",
                    "text", "SELECT max(\"msisdn no\") AS m FROM odd_ds"));
            // The saved queries run raw for nobody who cannot reveal.
            for (Map.Entry<String, String> e : Map.of("oddk_q", "k", "oddmax_q", "m").entrySet()) {
                HttpResponse<String> r = post(c, "viewer", "/queries/" + e.getKey() + "/run", null);
                assertEquals(200, r.statusCode(), e.getKey() + " -> " + r.body());
                assertNoRaw(r.body());
                V1Body.of(r.body()).get("rows").forEach(row ->
                        assertTrue(row.get(e.getValue()).asText().startsWith("masked:"), e.getKey() + " -> " + row));
            }
            // Other names that are no plain word: an embedded double quote, only whitespace, a non-ASCII letter whose lower case
            // depends on its position (Java's final sigma), each reached by a text that never spells the name literally.
            Map<String, String[]> odd = Map.of(
                    "odd2_ds", new String[]{"\"a\"\"b\"","SELECT \"a\"\"b\" AS k, region FROM odd2_ds", "a\"b"},
                    "odd3_ds", new String[]{"Σ", "SELECT odd3_ds.Σ AS k, region FROM odd3_ds", "Σ"},
                    "odd4_ds", new String[]{"\" \"", "SELECT \" \" AS k, region FROM odd4_ds", " "},
                    // only a quote: folds to "" once the quotes are dropped
                    "odd5_ds", new String[]{"\"\"\"\"", "SELECT \"\"\"\" AS k, region FROM odd5_ds", "\""},
                    // İ: the stored name is lower-cased as a whole string (i + U+0307), the text per code point (i)
                    "odd6_ds", new String[]{"\"msisdn İ\"", "SELECT \"msisdn İ\" AS k, region FROM odd6_ds", "msisdn İ"});
            for (Map.Entry<String, String[]> e : odd.entrySet()) {
                String ds = e.getKey();
                new ViewStore(c.root.resolve("views")).write(new ViewDefinition(ds + "_view", "flow-x", List.of(),
                        "SELECT * FROM (VALUES ('" + A + "','EU'),('" + B + "','US')) AS t(" + e.getValue()[0] + ", region)",
                        "2026-07-08T00:00:00Z"));
                reg.write("dataset", ds, Map.of("view", ds + "_view", "columns", List.of(
                        Map.of("name", e.getValue()[2], "classification", "MSISDN"), Map.of("name", "region"))));
                reg.write("query", ds + "_q", Map.of("type", "sql", "datasetId", ds, "text", e.getValue()[1]));
                HttpResponse<String> r = post(c, "viewer", "/queries/" + ds + "_q/run", null);
                assertEquals(200, r.statusCode(), ds + " -> " + r.body());
                assertNoRaw(r.body());
                V1Body.of(r.body()).get("rows").forEach(row ->
                        assertTrue(row.get("k").asText().startsWith("masked:"), ds + " -> " + row));
            }

            // A Widget over the saved query may not bisect, rank or aggregate the key under the name k.
            String count = "\"measures\":[{\"agg\":\"count\"}]";
            for (String probe : List.of(
                    count + ",\"filters\":[{\"field\":\"k\",\"op\":\"like\",\"value\":\"4417009001%\"}]",
                    count + ",\"groupBy\":[\"k\"],\"orderBy\":[{\"field\":\"k\",\"dir\":\"desc\"}]",
                    "\"measures\":[{\"agg\":\"max\",\"field\":\"k\"}]")) {
                assertEquals(422, bi(c, "viewer", "odd_ds", "\"query\":\"oddk_q\"," + probe).statusCode(), probe);
                assertEquals(200, bi(c, "revealer", "odd_ds", "\"query\":\"oddk_q\"," + probe).statusCode(), "a revealer: " + probe);
            }
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
