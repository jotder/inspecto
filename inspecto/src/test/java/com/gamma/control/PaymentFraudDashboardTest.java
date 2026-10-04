package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-PACK-PAYMENT-FRAUD-1 (WS-44 dashboards + KPIs): the shipped {@code payment-fraud} Space Template is created
 * through {@code POST /spaces} (every seed gate, so the carried KPI / Widget / Dashboard kinds are checked against a
 * Subject that holds their capabilities), its feeds are ingested, its feature Jobs run, and every tile of
 * {@code payment_fraud_overview} renders through the routes the SPA uses: a KPI tile via {@code GET /kpis/{id}/value}
 * with a positive value, a bar Widget via {@code POST /bi/query} with the planted offenders.
 */
class PaymentFraudDashboardTest {

    private static final List<String> FEEDS = List.of("payment_attempts", "sim_changes", "disputes");
    private static final List<String> FEATURE_JOBS = List.of("pf_device_small_amounts", "pf_bin_declines",
            "pf_instrument_velocity", "pf_sim_swap_payments", "pf_account_activity", "pf_daily_summary");

    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1",
                Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES))));
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        MetricRegistry.global().reset();
    }

    @Test
    void everyDashboardTileRendersThePlantedValuesAfterTheGoldenRun(@TempDir Path root) throws Exception {
        Path template = Path.of("..", "spaces", "_templates", "payment-fraud").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(template), "the shipped template is missing: " + template);
        copyTree(template, root.resolve("_templates").resolve("payment-fraud"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            String base = "http://localhost:" + api.port() + "/api/v1/spaces";
            HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base))
                            .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"pay\",\"template\":\"payment-fraud\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
        } finally {
            api.close();
            spaces.close();
        }
        Path space = root.resolve("pay");
        ingest(space);
        runJobs(space, root);

        ObjectMapper json = new ObjectMapper();
        spaces = SpaceManager.discover(root);
        api = new ControlApi(spaces, 0);
        try {
            api.start();
            String base = "http://localhost:" + api.port() + "/api/v1/spaces/pay";
            JsonNode dash = V1Body.of(call(base + "/components/dashboard/payment_fraud_overview", null)).get("content");
            Map<String, Double> kpiValues = new TreeMap<>();
            Map<String, Map<String, Double>> bars = new TreeMap<>();
            int tiles = 0;
            for (JsonNode tile : dash.get("tiles")) {
                tiles++;
                String id = tile.get("widgetId").asText();
                assertTrue(tile.get("span").asInt() == 1 || tile.get("span").asInt() == 2, id);
                JsonNode w = V1Body.of(call(base + "/components/widget/" + id, null)).get("content");
                if ("kpi".equals(w.get("vizType").asText())) {
                    String kpi = w.get("options").get("kpi").get("kpiId").asText();
                    JsonNode v = V1Body.of(call(base + "/kpis/" + kpi + "/value?asOf=2026-07-03", null));
                    assertTrue(v.get("value").asDouble() > 0, id + ": the KPI tile has a value: " + v);
                    kpiValues.put(kpi, v.get("value").asDouble());
                    continue;
                }
                assertEquals("bar", w.get("vizType").asText(), id);
                String x = w.get("controls").get("x").get(0).get("field").asText();
                JsonNode y = w.get("controls").get("y").get(0);
                String agg = y.get("agg").asText(), field = y.get("field").asText();
                String body = json.writeValueAsString(Map.of("dataset", w.get("datasetId").asText(), "groupBy", List.of(x),
                        "measures", List.of(Map.of("agg", agg, "field", field))));
                Map<String, Double> rows = new TreeMap<>();
                for (JsonNode r : V1Body.of(call(base + "/bi/query", body)).get("rows"))
                    rows.put(r.get(x).asText(), r.get(agg + "_" + field).asDouble());
                bars.put(w.get("datasetId").asText(), rows);
            }
            assertEquals(8, tiles, "four KPI tiles and four typology Widgets");
            assertEquals(4, kpiValues.size());
            assertTrue(bars.get("pf_device_small_amounts").get("dev_ct_01") >= 8, "card-testing offender: " + bars);
            assertTrue(bars.get("pf_bin_declines").get("498765") >= 15, "BIN-attack offender: " + bars);
            assertTrue(bars.get("pf_instrument_velocity").get("tok_vb_01") >= 6, "velocity offender: " + bars);
            assertTrue(bars.get("pf_sim_swap_payments").get("acc_ss01") >= 1, "SIM-swap offender: " + bars);
        } finally {
            api.close();
            spaces.close();
        }
    }

    private static void ingest(Path space) throws Exception {
        DuckDbUtil.loadDriver();
        for (String feed : FEEDS) {
            PipelineConfig cfg = PipelineConfig.load(space.resolve("config").resolve(feed)
                    .resolve(feed + "_pipeline.toon").toString());
            Path poll = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
            assertTrue(poll.startsWith(space), feed + " polls outside its Space: " + poll);
            Files.createDirectories(poll);
            try (Stream<Path> samples = Files.list(space.resolve("data").resolve("samples").resolve(feed))) {
                for (Path csv : samples.toList()) Files.copy(csv, poll.resolve(csv.getFileName()));
            }
            CollectorProcessor.run(cfg);
        }
    }

    private static void runJobs(Path space, Path root) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : FEATURE_JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            for (String j : FEATURE_JOBS) {
                assertTrue(js.triggerRun(j, null).isPresent(), j);
                JobRun r;
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((r = js.lastRunOf(j).orElse(null)) == null && System.nanoTime() < deadline) Thread.sleep(50);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
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
