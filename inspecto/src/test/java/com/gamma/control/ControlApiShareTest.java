package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-6 public dashboard sharing, every gate: disabled-by-default 503, issue + anonymous resolve,
 * tamper/expiry → indistinguishable 404, and the public query fenced to the dashboard's datasets.
 */
class ControlApiShareTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void clearSecret() {
        System.clearProperty("bi.share.secret");
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A dashboard with one widget over the sales dataset (plus an unrelated, unreferenced dataset). */
    private void seed(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('US',5.0)) AS t(region,amount)", "2026-07-08T00:00:00Z"));
        ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
        reg.write("dataset", "sales_ds", Map.of("view", "sales_view"));
        reg.write("dataset", "secret_ds", Map.of("view", "sales_view"));   // NOT referenced by the dashboard
        reg.write("widget", "sales_w", Map.of("kind", "bar", "datasetId", "sales_ds"));
        reg.write("dashboard", "exec_board", Map.of("title", "Exec", "widgets", List.of("sales_w")));
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void sharingIsDisabledWithoutASecret(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c);
            assertEquals(503, post(c.port, "/dashboards/exec_board/share", null).statusCode(),
                    "no -Dbi.share.secret → the whole surface is inert");
            assertEquals(404, get(c.port, "/public/dashboards/whatever").statusCode());
        }
    }

    @Test
    void issueResolveAndQueryWithinTheFence(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            HttpResponse<String> issued = post(c.port, "/dashboards/exec_board/share", "{\"ttl_hours\":1}");
            assertEquals(200, issued.statusCode(), issued.body());
            JsonNode data = V1Body.of(issued.body()).has("data")
                    ? V1Body.of(issued.body()) : V1Body.of(issued.body());
            String token = data.get("token").asText();
            assertFalse(token.isBlank());

            // Anonymous resolve: dashboard + its widget come back read-only.
            HttpResponse<String> resolved = get(c.port, "/public/dashboards/" + token);
            assertEquals(200, resolved.statusCode(), resolved.body());
            JsonNode pub = V1Body.of(resolved.body()).has("data")
                    ? V1Body.of(resolved.body()) : V1Body.of(resolved.body());
            assertEquals("exec_board", pub.get("dashboard").get("id").asText());
            assertEquals(1, pub.get("widgets").size());
            assertEquals("sales_w", pub.get("widgets").get(0).get("id").asText());

            // Public query over the referenced dataset works…
            HttpResponse<String> ok = post(c.port, "/public/dashboards/" + token + "/query",
                    "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}]}");
            assertEquals(200, ok.statusCode(), ok.body());

            // …but the token is NOT a general data API: an unreferenced dataset is refused.
            HttpResponse<String> refused = post(c.port, "/public/dashboards/" + token + "/query",
                    "{\"dataset\":\"secret_ds\",\"measures\":[{\"agg\":\"count\"}]}");
            assertEquals(403, refused.statusCode());
            // ERRORCODE-DEFAULTED-1: this 403 took ErrorCodes.defaultFor(403) = PATH_JAIL_VIOLATION, which
            // told the client a path had escaped a jail. A share token not covering a dataset is a
            // permission decision, and the code is part of the v1 contract the SPA reads.
            assertEquals("PERMISSION_DENIED",
                    V1Body.envelope(refused.body()).at("/error/errorCode").asText(), refused.body());
        }
    }

    @Test
    void tamperedAndUnknownTokensAreIndistinguishable404s(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            JsonNode issued = JSON.readTree(post(c.port, "/dashboards/exec_board/share", null).body());
            String token = (issued.has("data") ? issued.get("data") : issued).get("token").asText();
            assertFalse(token.isBlank(), "share must issue a token: " + issued);
            // Tamper the FIRST character of the signature segment, not the last of the token.
            // Two traps this avoids, both of which make the "tampered" token verify and return 200:
            //   1. the old `substring(0, len-2) + "zz"` was a no-op whenever the digest already ended
            //      in "zz" (~1 in 4096 runs, since the tail is base64url(HMAC)) — an intermittent
            //      false red with no product defect behind it;
            //   2. flipping the LAST base64 character is unsound even when it changes the string: a
            //      32-byte digest encodes to 43 unpadded chars, so the final char carries 2 unused
            //      low bits and some flips (e.g. 'z'→'y') decode to the SAME bytes.
            // The first character after the '.' carries 6 significant bits, so this always changes the
            // decoded signature.
            int dot = token.indexOf('.');
            assertTrue(dot > 0 && dot < token.length() - 1, "token is payload.signature: " + token);
            char sig0 = token.charAt(dot + 1);
            String tampered = token.substring(0, dot + 1) + (sig0 == 'A' ? 'B' : 'A') + token.substring(dot + 2);
            assertNotEquals(token, tampered, "the tamper must actually change the token");
            assertEquals(404, get(c.port, "/public/dashboards/" + tampered).statusCode());
            assertEquals(404, get(c.port, "/public/dashboards/garbage.token").statusCode());
            assertEquals(404, post(c.port, "/public/dashboards/" + tampered + "/query",
                    "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"count\"}]}").statusCode());
        }
    }

    /** A field the shared dataset lacks reads as the Binder Error, not DuckDB's pending-query preamble
     *  ({@code DUCKDB-PREAMBLE-OTHER-422S-1}). */
    @Test
    void anAbsentFieldIsReportedAsTheBinderErrorNotTheDriverPreamble(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            String token = V1Body.of(post(c.port, "/dashboards/exec_board/share", null).body()).get("token").asText();
            HttpResponse<String> r = post(c.port, "/public/dashboards/" + token + "/query",
                    "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"sum\",\"field\":\"nope\"}]}");
            assertEquals(422, r.statusCode(), r.body());
            String message = V1Body.envelope(r.body()).at("/error/message").asText();
            assertTrue(message.startsWith("query failed: Binder Error: "), message);
        }
    }

    // ── SHARE-SAVED-FILTER-1: the shared Dashboard's STORED filter applies; the caller's never does ──

    private static final String SUM_AMOUNT = "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}]%s}";

    private double sharedSum(Ctx c, String dashboard, String extra) throws Exception {
        String token = V1Body.of(post(c.port, "/dashboards/" + dashboard + "/share", null).body()).get("token").asText();
        HttpResponse<String> r = post(c.port, "/public/dashboards/" + token + "/query", String.format(SUM_AMOUNT, extra));
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body()).get("rows").get(0).elements().next().asDouble();
    }

    private static Map<String, Object> group(String op, Object... items) {
        return Map.of("kind", "group", "op", op, "items", List.of(items));
    }

    private static Map<String, Object> cond(String field, String operator, String value) {
        return Map.of("kind", "condition", "field", field, "operator", operator, "value", value);
    }

    @Test
    void theSavedFilterAppliesToASharedQuery(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("dashboard", "eu_board", Map.of("widgets", List.of("sales_w"),
                    "filter", group("AND", cond("region", "=", "EU"))));
            reg.write("dashboard", "empty_filter_board", Map.of("widgets", List.of("sales_w"), "filter", Map.of()));

            assertEquals(10.0, sharedSum(c, "eu_board", ""), "EU only, as the Dashboard shows it");
            assertEquals(15.0, sharedSum(c, "exec_board", ""), "no saved filter → the whole dataset, unchanged");
            assertEquals(15.0, sharedSum(c, "empty_filter_board", ""), "an empty stored `filter:` ({}) is no filter");
        }
    }

    @Test
    void aCallerCannotInjectOrDropAFilter(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            new ComponentStore(c.root.resolve("registry")).write("dashboard", "eu_board",
                    Map.of("widgets", List.of("sales_w"), "filter", group("AND", cond("region", "=", "EU"))));

            String us = ",\"filters\":[{\"field\":\"region\",\"op\":\"=\",\"value\":\"US\"}]";
            assertEquals(10.0, sharedSum(c, "eu_board", us), "a sent filter must not replace the saved one");
            assertEquals(10.0, sharedSum(c, "eu_board", ",\"filters\":[]"), "an empty sent list must not drop it");
            assertEquals(15.0, sharedSum(c, "exec_board", us), "nor filter a Dashboard that has none");
        }
    }

    @Test
    void aSavedOrFilterIsRefusedNotDropped(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            new ComponentStore(c.root.resolve("registry")).write("dashboard", "or_board", Map.of("widgets",
                    List.of("sales_w"), "filter", group("OR", cond("region", "=", "EU"), cond("region", "=", "US"))));
            String token = V1Body.of(post(c.port, "/dashboards/or_board/share", null).body()).get("token").asText();
            HttpResponse<String> r = post(c.port, "/public/dashboards/" + token + "/query", String.format(SUM_AMOUNT, ""));
            assertEquals(422, r.statusCode(), "an unfiltered share would show more than the Dashboard: " + r.body());
        }
    }

    // ── UIE-5 (d): the shared Dashboard's DEFAULT date range applies server-side, scoped per Dataset ──

    private static final String DATED_SUM = "{\"dataset\":\"dated_ds\",\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}]%s}";

    private double datedSum(Ctx c, String token, String extra) throws Exception {
        HttpResponse<String> r = post(c.port, "/public/dashboards/" + token + "/query", String.format(DATED_SUM, extra));
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body()).get("rows").get(0).elements().next().asDouble();
    }

    private String shareToken(Ctx c, String dashboard) throws Exception {
        return V1Body.of(post(c.port, "/dashboards/" + dashboard + "/share", null).body()).get("token").asText();
    }

    @Test
    void theDefaultDateRangeFencesASharedQuery(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            // TIMESTAMPs: the first day's midnight and the last day's evening are IN; one second before and the
            // next midnight are OUT — the range is inclusive days, even on a timestamp column.
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("dated_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES (TIMESTAMP '2026-09-24 18:00:00', 1.0), (TIMESTAMP '2026-09-18 00:00:00', 2.0),"
                            + " (TIMESTAMP '2026-09-17 23:59:59', 4.0), (TIMESTAMP '2026-09-25 00:00:00', 8.0))"
                            + " AS t(sold_at, amount)", "2026-09-24T00:00:00Z"));
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("dataset", "dated_ds", Map.of("view", "dated_view",
                    "columns", List.of(Map.of("name", "sold_at", "type", "date"), Map.of("name", "amount", "type", "number"))));
            reg.write("widget", "dated_w", Map.of("kind", "bar", "datasetId", "dated_ds"));
            reg.write("dashboard", "ranged_board", Map.of("widgets", List.of("dated_w", "sales_w"),
                    "dateField", "sold_at", "asOf", "2026-09-24", "defaultRange", "last-7-days"));
            reg.write("dashboard", "custom_board", Map.of("widgets", List.of("dated_w"), "dateField", "sold_at",
                    "defaultRange", Map.of("from", "2026-09-17", "to", "2026-09-17")));
            reg.write("dashboard", "bad_range_board", Map.of("widgets", List.of("dated_w"), "dateField", "sold_at",
                    "defaultRange", "last-week"));

            String token = shareToken(c, "ranged_board");
            assertEquals(3.0, datedSum(c, token, ""), "last 7 days to 24 Sep: 18 Sep 00:00 .. 24 Sep 18:00 only");
            String widen = ",\"filters\":[{\"field\":\"sold_at\",\"op\":\">=\",\"value\":\"2000-01-01\"}]";
            assertEquals(3.0, datedSum(c, token, widen), "a recipient cannot widen the range");
            assertEquals(3.0, datedSum(c, token, ",\"filters\":[]"), "nor drop it");
            // The same share's other Dataset has no sold_at column → unranged, not an error.
            assertEquals(15.0, sharedSum(c, "ranged_board", ""), "a Dataset without the date column is not range-filtered");

            assertEquals(4.0, datedSum(c, shareToken(c, "custom_board"), ""), "a custom one-day span");

            HttpResponse<String> refused = post(c.port, "/public/dashboards/" + shareToken(c, "bad_range_board") + "/query",
                    String.format(DATED_SUM, ""));
            assertEquals(422, refused.statusCode(), "an unreadable range is refused, never dropped: " + refused.body());
        }
    }

    /** R2-02: a shared table Widget opens in its saved `tableSort` order — the resolve carries the option and the
     *  fenced public query orders by the measure alias the SPA sends, so the row limit keeps the same top rows. */
    @Test
    void aSharedTableWidgetKeepsItsSavedSortOrder(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
            reg.write("widget", "table_w", Map.of("vizType", "table", "datasetId", "sales_ds",
                    "options", Map.of("tableSort", Map.of("field", "sum_amount", "dir", "asc"))));
            reg.write("dashboard", "table_board", Map.of("widgets", List.of("table_w")));
            String token = shareToken(c, "table_board");

            JsonNode widget = V1Body.of(get(c.port, "/public/dashboards/" + token).body()).get("widgets").get(0);
            assertEquals("sum_amount", widget.at("/content/options/tableSort/field").asText(), widget.toString());
            assertEquals("asc", widget.at("/content/options/tableSort/dir").asText());

            // Both directions, so an ignored orderBy cannot pass by the unordered result happening to match one.
            assertEquals("US", topRegion(c, token, "asc"), "ascending by sum → US (5) before EU (10)");
            assertEquals("EU", topRegion(c, token, "desc"), "descending by sum → EU (10) before US (5)");
        }
    }

    private String topRegion(Ctx c, String token, String dir) throws Exception {
        HttpResponse<String> r = post(c.port, "/public/dashboards/" + token + "/query",
                "{\"dataset\":\"sales_ds\",\"groupBy\":[\"region\"],\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}],"
                        + "\"orderBy\":[{\"field\":\"sum_amount\",\"dir\":\"" + dir + "\"}],\"limit\":1}");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode rows = V1Body.of(r.body()).get("rows");
        assertEquals(1, rows.size(), "the limit keeps only the top row");
        return rows.get(0).get("region").asText();
    }

    @Test
    void unknownDashboardShareIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            assertEquals(404, post(c.port, "/dashboards/ghost/share", null).statusCode());
        }
    }
}
