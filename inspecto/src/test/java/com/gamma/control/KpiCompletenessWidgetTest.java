package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.job.KpiCompletenessOutput;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The completeness tile (§8-D, operator 2026-10-09: Signals behind a Dataset). The shipped {@code telco-ra} Space
 * Template is created through {@code POST /spaces} (every seed gate), the {@code kpi.completeness} Job's store is
 * written through {@link KpiCompletenessOutput}, and the {@code ra_completeness_status} Widget — an ordinary table
 * over the {@code kpi_completeness} Dataset, placed on {@code ra_overview} — renders through {@code POST /bi/query},
 * the route the SPA uses.
 */
class KpiCompletenessWidgetTest {

    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1",
                Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES,
                        Roles.CAN_OPERATE_RUNS))));
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        MetricRegistry.global().reset();
    }

    @Test
    void theCompletenessWidgetRendersThePerPipelineDayRowsFromItsDataset(@TempDir Path root) throws Exception {
        Path template = Path.of("..", "spaces", "_templates", "telco-ra").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(template), "the shipped template is missing: " + template);
        copyTree(template, root.resolve("_templates").resolve("telco-ra"));
        DuckDbUtil.loadDriver();
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            String base = "http://localhost:" + api.port() + "/api/v1/spaces";
            call(base, "{\"id\":\"ra\",\"template\":\"telco-ra\"}");
        } finally {
            api.close();
            spaces.close();
        }
        Path data = root.resolve("ra").resolve("data");
        KpiCompletenessOutput.upsert(data, row("switch_xdr", "2026-08-03", "OK", 900), Instant.now());
        KpiCompletenessOutput.upsert(data, row("switch_xdr", "2026-08-04", "BREACH", 100), Instant.now());
        // A same-day re-run upserts: the 2026-08-04 row is replaced, never duplicated.
        KpiCompletenessOutput.upsert(data, row("switch_xdr", "2026-08-04", "OK", 880), Instant.now());

        spaces = SpaceManager.discover(root);
        api = new ControlApi(spaces, 0);
        try {
            api.start();
            String base = "http://localhost:" + api.port() + "/api/v1/spaces/ra";
            JsonNode dash = V1Body.of(call(base + "/components/dashboard/ra_overview", null)).get("content");
            boolean onDashboard = false;
            for (JsonNode t : dash.get("tiles")) onDashboard |= "ra_completeness_status".equals(t.get("widgetId").asText());
            assertTrue(onDashboard, "the tile is on the overview dashboard: " + dash);

            JsonNode w = V1Body.of(call(base + "/components/widget/ra_completeness_status", null)).get("content");
            assertEquals("table", w.get("vizType").asText());
            List<String> groupBy = new java.util.ArrayList<>();
            for (JsonNode x : w.get("controls").get("x")) groupBy.add(x.get("field").asText());
            JsonNode y = w.get("controls").get("y").get(0);
            String body = new ObjectMapper().writeValueAsString(Map.of("dataset", w.get("datasetId").asText(),
                    "groupBy", groupBy, "measures", List.of(Map.of("agg", y.get("agg").asText(), "field", y.get("field").asText()))));
            Map<String, String> byDay = new TreeMap<>();
            int rows = 0;
            for (JsonNode r : V1Body.of(call(base + "/bi/query", body)).get("rows")) {
                rows++;
                byDay.put(r.get("pipeline").asText() + "@" + r.get("record_day").asText(),
                        r.get("status").asText() + ":" + r.get("sum_rows").asLong());
            }
            assertEquals(2, rows, "one row per Pipeline-day — the re-run did not duplicate: " + byDay);
            assertEquals("OK:900", byDay.get("switch_xdr@2026-08-03"), byDay.toString());
            assertEquals("OK:880", byDay.get("switch_xdr@2026-08-04"), "the re-run's values win: " + byDay);
        } finally {
            api.close();
            spaces.close();
        }
    }

    @Test
    void beforeTheJobHasEverRunTheTileReadsAsEmptyNotAnIoError(@TempDir Path root) throws Exception {
        // LIVEFIX2 #1a: the template ships a zero-row schema seed (and the Job's ownership marker), so a fresh
        // Space's tile answers zero rows instead of "No files found that match the pattern <absolute path>".
        Path template = Path.of("..", "spaces", "_templates", "telco-ra").toAbsolutePath().normalize();
        copyTree(template, root.resolve("_templates").resolve("telco-ra"));
        DuckDbUtil.loadDriver();
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            call("http://localhost:" + api.port() + "/api/v1/spaces", "{\"id\":\"ra\",\"template\":\"telco-ra\"}");
            String body = new ObjectMapper().writeValueAsString(Map.of("dataset", "kpi_completeness_dataset",
                    "groupBy", List.of("pipeline", "record_day", "status"),
                    "measures", List.of(Map.of("agg", "sum", "field", "rows"))));
            JsonNode rows = V1Body.of(call("http://localhost:" + api.port() + "/api/v1/spaces/ra/bi/query", body)).get("rows");
            assertEquals(0, rows.size(), rows.toString());
        } finally {
            api.close();
            spaces.close();
        }
    }

    @Test
    void aMissingStoreRefusalNamesASpaceRelativePathNeverTheServersAbsoluteOne(@TempDir Path root) throws Exception {
        // LIVEFIX2 #1b: the DuckDB "No files found that match the pattern ..." text reaches the client, but the
        // Space's absolute data root is replaced by its directory name.
        Path template = Path.of("..", "spaces", "_templates", "telco-ra").toAbsolutePath().normalize();
        copyTree(template, root.resolve("_templates").resolve("telco-ra"));
        DuckDbUtil.loadDriver();
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            call("http://localhost:" + api.port() + "/api/v1/spaces", "{\"id\":\"ra\",\"template\":\"telco-ra\"}");
            Path store = root.resolve("ra").resolve("data").resolve("kpi_completeness");
            try (Stream<Path> s = Files.list(store)) { for (Path f : s.toList()) Files.delete(f); }
            Files.delete(store);
            String body = new ObjectMapper().writeValueAsString(Map.of("dataset", "kpi_completeness_dataset",
                    "groupBy", List.of("pipeline"), "measures", List.of(Map.of("agg", "sum", "field", "rows"))));
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + api.port() + "/api/v1/spaces/ra/bi/query"))
                    .header("Authorization", "Bearer admin").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(422, r.statusCode(), r.body());
            String abs = root.toAbsolutePath().normalize().toString();
            assertFalse(r.body().toLowerCase().contains(abs.toLowerCase()), r.body());
            assertFalse(r.body().toLowerCase().contains(abs.replace('\\', '/').toLowerCase()), r.body());
            assertTrue(r.body().contains("data/kpi_completeness"), r.body());
        } finally {
            api.close();
            spaces.close();
        }
    }

    private static Map<String, Object> row(String pipeline, String day, String status, long rows) {
        Map<String, Object> m = new HashMap<>();
        m.put("pipeline", pipeline);
        m.put("recordDay", day);
        m.put("status", status);
        m.put("rows", rows);
        m.put("unknownStreakDays", 0);
        return m;
    }

    private static String call(String url, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer admin");
        if (jsonBody != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), url + " -> " + r.body());
        return r.body();
    }

    private static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }
}
