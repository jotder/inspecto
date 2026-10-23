package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.access.ComponentAccess;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-QUERY-MASKING-1 security review (2026-10-23; {@code docs/archived-documents/plans-archive/bi-query-masking-design.md} section 12): one test per
 * finding R1..R6, each of which failed on the code before the review, plus the consumer matrix the review walked (every BI path
 * masked for a viewer, raw for a revealer, never raw on a public share, raw under a stated {@code maskingMode none}). Real HTTP
 * and real DuckDB under an ARMED authenticator (a request with no Subject is unrestricted), except the test about an install
 * where nobody signs in.
 *
 * <p>Users: {@code viewer} holds nothing, {@code revealer} holds {@code canRevealLinkEntities}, {@code author} holds
 * {@code canAuthorWorkbench}.
 */
class ControlApiBiMaskingReviewTest {

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

    /** {@code signIn}: arm the authenticator (people sign in, masking defaults to {@code typed}); else nobody signs in ({@code none}). */
    private Ctx open(Path cfg, Path root, boolean signIn) throws Exception {
        if (signIn) Authenticators.forTest(FAKE);
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

    /** {@code calls_ds}: msisdn (classified MSISDN) first, then email, region, amount; 3 subscribers, 4 rows. */
    private void seed(Ctx c, Map<String, Object> extra) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('" + A + "','a@x.io','EU',10.0),('" + A + "','a@x.io','EU',5.0),('" + B + "','b@x.io','US',7.0),"
                        + "('" + C + "','c@x.io','US',1.0)) AS t(msisdn,email,region,amount)", "2026-07-08T00:00:00Z"));
        Map<String, Object> ds = new LinkedHashMap<>(Map.of("view", "calls_view", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "email", "classification", "PII"),
                Map.of("name", "region"), Map.of("name", "amount"))));
        ds.putAll(extra);
        new ComponentStore(c.root.resolve("registry")).write("dataset", "calls_ds", ds);
    }

    /** A dashboard over {@code dataset} shared by {@code user}; returns the link token. */
    private String share(Ctx c, String user, String dataset) throws Exception {
        ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
        reg.write("widget", "w_" + dataset, Map.of("kind", "table", "datasetId", dataset));
        reg.write("dashboard", "board_" + dataset, Map.of("title", "Calls", "widgets", List.of("w_" + dataset)));
        HttpResponse<String> r = post(c, user, "/dashboards/board_" + dataset + "/share", null);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body()).get("token").asText();
    }

    private HttpResponse<String> post(Ctx c, String user, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Ctx c, String user, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path)).GET();
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> bi(Ctx c, String user, String rest) throws Exception {
        return post(c, user, "/bi/query", "{\"dataset\":\"calls_ds\"," + rest + "}");
    }

    private HttpResponse<String> pub(Ctx c, String user, String token, String rest) throws Exception {
        return post(c, user, "/public/dashboards/" + token + "/query", "{\"dataset\":\"calls_ds\"," + rest + "}");
    }

    private static final String BY_MSISDN = "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"msisdn\"]";

    private static void assertNoRaw(HttpResponse<String> r) {
        for (String raw : List.of(A, B, C)) assertFalse(r.body().contains(raw), "raw key " + raw + " must not appear: " + r.body());
    }

    private static void assertRaw(HttpResponse<String> r) {
        assertTrue(r.body().contains(A), "the raw key is expected here: " + r.body());
    }

    private static List<String> column(HttpResponse<String> r, String name) throws Exception {
        List<String> out = new ArrayList<>();
        V1Body.of(r.body()).get("rows").forEach(row -> out.add(row.get(name).asText()));
        return out;
    }

    private static void assertAllMasked(HttpResponse<String> r, String name) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        assertNoRaw(r);
        List<String> cells = column(r, name);
        assertFalse(cells.isEmpty(), r.body());
        assertTrue(cells.stream().allMatch(s -> s.startsWith("masked:")), name + " -> " + cells);
    }

    private void writeMode(Ctx c, String mode) throws Exception {
        Files.writeString(c.root.resolve("link-analysis.toon"), "masking_mode: " + mode + "\n");
    }

    private void query(Ctx c, String id, String text, List<Map<String, Object>> parameters) throws Exception {
        Map<String, Object> q = new LinkedHashMap<>(Map.of("type", "sql", "datasetId", "calls_ds", "text", text));
        if (parameters != null) q.put("parameters", parameters);
        new ComponentStore(c.root.resolve("registry")).write("query", id, q);
    }

    /** R1: a Widget bound to a saved Query that reads the key by POSITION (it never spells the name). */
    @Test
    void aWidgetBoundToAQueryThatReadsTheKeyByPositionIsMaskedAndCannotBeFiltered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            seed(c, Map.of());
            query(c, "pos_q", "SELECT #1 AS x, region FROM calls_ds", null);
            String tile = "\"query\":\"pos_q\",\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"x\"]";
            assertAllMasked(bi(c, "viewer", tile), "x");
            HttpResponse<String> probe = bi(c, "viewer", tile + ",\"filters\":[{\"field\":\"x\",\"op\":\"=\",\"value\":\"" + A + "\"}]");
            assertEquals(422, probe.statusCode(), "a filter on the positional key is an oracle: " + probe.body());
            HttpResponse<String> revealer = bi(c, "revealer", tile);
            assertEquals(200, revealer.statusCode(), revealer.body());
            assertEquals(Set.of(A, B, C), Set.copyOf(column(revealer, "x")), "a revealer is unaffected");
        }
    }

    /** R2: DuckDB quotes the offending value in a run-time error; a compile-time error keeps its text. */
    @Test
    void aRunTimeErrorThatQuotesAKeyIsWithheldFromAViewerOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", SECRET);
        try (Ctx c = open(cfg, root, true)) {
            seed(c, Map.of("calculated", List.of(Map.of("name", "seen", "expr", "cast(msisdn AS date)"))));
            query(c, "err_q", "SELECT error(msisdn) AS e FROM calls_ds", null);
            query(c, "typo_q", "SELECT no_such_column FROM calls_ds", null);
            String token = share(c, "author", "calls_ds");

            HttpResponse<String> run = post(c, "viewer", "/queries/err_q/run", null);
            assertEquals(422, run.statusCode(), run.body());
            assertNoRaw(run);
            assertTrue(run.body().contains("withheld"), run.body());
            HttpResponse<String> rows = get(c, "viewer", "/datasets/calls_ds/rows?limit=10");
            assertEquals(422, rows.statusCode(), rows.body());
            assertNoRaw(rows);
            HttpResponse<String> shared = pub(c, null, token, "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"seen\"]");
            assertEquals(422, shared.statusCode(), shared.body());
            assertNoRaw(shared);

            // The scrub is the only variable: a revealer reads the driver's text, value and all.
            assertRaw(post(c, "revealer", "/queries/err_q/run", null));
            assertRaw(get(c, "revealer", "/datasets/calls_ds/rows?limit=10"));
            // A compile-time error reads no row, so a viewer keeps its text.
            HttpResponse<String> typo = post(c, "viewer", "/queries/typo_q/run", null);
            assertEquals(422, typo.statusCode(), typo.body());
            assertTrue(typo.body().contains("Binder Error"), typo.body());
        }
    }

    /** R3: the $parameter oracle through a key the text reads by pattern, not by name. */
    @Test
    void aParameterCannotProbeAKeyTheQueryReadsByPattern(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            seed(c, Map.of());
            query(c, "pat_q", "SELECT region, count(*) AS n FROM calls_ds WHERE COLUMNS('msi.*') LIKE $p GROUP BY region",
                    List.of(Map.of("name", "p", "type", "string", "default", "%")));
            String probe = "{\"parameters\":{\"p\":\"4417009001%\"}}";
            HttpResponse<String> viewer = post(c, "viewer", "/queries/pat_q/run", probe);
            assertEquals(422, viewer.statusCode(), "the oracle is refused: " + viewer.body());
            assertEquals(200, post(c, "revealer", "/queries/pat_q/run", probe).statusCode(), "a revealer is unrestricted");
            assertEquals(200, post(c, "viewer", "/queries/pat_q/run", null).statusCode(), "the author's default is trusted");
        }
    }

    /** R4: a calculated column over the relation it is computed from ({@code __base}) is the whole row, key included. */
    @Test
    void aCalculatedColumnOverTheWholeRowIsMasked(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            seed(c, Map.of("calculated", List.of(Map.of("name", "row_txt", "expr", "concat(__base)"))));
            assertAllMasked(get(c, "viewer", "/datasets/calls_ds/rows?limit=10"), "row_txt");
            assertAllMasked(bi(c, "viewer", "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"row_txt\"]"), "row_txt");
            assertTrue(column(get(c, "revealer", "/datasets/calls_ds/rows?limit=10"), "row_txt").stream().anyMatch(s -> s.contains(A)));
        }
    }

    /** R5: where nobody signs in the Space's unstated mode is none, but a public link holder is anonymous and cannot reveal. */
    @Test
    void aPublicShareMasksWhereNobodySignsInUnlessTheSpaceStatesNone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", SECRET);
        try (Ctx c = open(cfg, root, false)) {
            seed(c, Map.of());
            String token = share(c, null, "calls_ds");
            assertRaw(bi(c, null, BY_MSISDN));   // the install's own user: masking is off by default here (LA-ONBOARD-1 OB-6)
            assertAllMasked(pub(c, null, token, BY_MSISDN), "msisdn");
            writeMode(c, "none");                 // the Space's explicit choice (D-M2) still holds for its shares
            HttpResponse<String> off = pub(c, null, token, BY_MSISDN);
            assertEquals(200, off.statusCode(), off.body());
            assertRaw(off);
        }
    }

    /** R6: the share viewer sends a chart's cosmetic category order; over a masked key it is dropped, as /bi/query drops it. */
    @Test
    void aSharedChartOrderedByItsMaskedCategoryRendersForARecipient(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", SECRET);
        try (Ctx c = open(cfg, root, true)) {
            seed(c, Map.of());
            String token = share(c, "author", "calls_ds");
            String chart = BY_MSISDN + ",\"orderBy\":[{\"field\":\"msisdn\",\"dir\":\"asc\",\"cosmetic\":true}]";
            HttpResponse<String> r = pub(c, null, token, chart);
            assertAllMasked(r, "msisdn");
            assertEquals(3, column(r, "msisdn").size());
            assertEquals(422, pub(c, null, token, chart.replace(",\"cosmetic\":true", "")).statusCode(),
                    "an order that is not cosmetic still bisects the key");
        }
    }

    /**
     * The consumer matrix: {@code /bi/query}, {@code /queries/{id}/run}, {@code /datasets/{id}/rows} and the public share are
     * masked for a viewer and raw for a revealer; the public share stays masked even when a revealer's credential rides along;
     * a stated {@code maskingMode none} opens every path. Report exports: {@code ReportJobMaskingTest}.
     */
    @Test
    void everyConsumerMasksForAViewerRevealsForARevealerNeverOnAShareAndOpensUnderNone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", SECRET);
        try (Ctx c = open(cfg, root, true)) {
            seed(c, Map.of());
            query(c, "plain_q", "SELECT msisdn, region FROM calls_ds", null);
            String token = share(c, "author", "calls_ds");

            assertAllMasked(bi(c, "viewer", BY_MSISDN), "msisdn");
            assertAllMasked(post(c, "viewer", "/queries/plain_q/run", null), "msisdn");
            assertAllMasked(get(c, "viewer", "/datasets/calls_ds/rows?limit=10"), "msisdn");
            assertAllMasked(pub(c, null, token, BY_MSISDN), "msisdn");

            assertRaw(bi(c, "revealer", BY_MSISDN));
            assertRaw(post(c, "revealer", "/queries/plain_q/run", null));
            assertRaw(get(c, "revealer", "/datasets/calls_ds/rows?limit=10"));
            assertAllMasked(pub(c, "revealer", token, BY_MSISDN), "msisdn");

            writeMode(c, "none");
            for (HttpResponse<String> r : List.of(bi(c, "viewer", BY_MSISDN), post(c, "viewer", "/queries/plain_q/run", null),
                    get(c, "viewer", "/datasets/calls_ds/rows?limit=10"), pub(c, null, token, BY_MSISDN))) {
                assertEquals(200, r.statusCode(), r.body());
                assertRaw(r);
                JsonNode body = V1Body.of(r.body());
                assertFalse(body.has("masking"), "maskingMode none: no masking block: " + r.body());
            }
        }
    }
}
