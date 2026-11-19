package com.gamma.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.alert.AlertRule;
import com.gamma.control.ControlApi;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.expectation.Expectation;
import com.gamma.expectation.ExpectationEvaluator;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.recon.ReconBreaks;
import com.gamma.recon.ReconRunJob;
import com.gamma.recon.ReconStateStore;
import com.gamma.service.SpaceManager;
import com.gamma.signal.SignalEmitter;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code USER-HANDBOOK-1} P2 lane D: the Handbook's worked example, the SHIPPED {@code order-to-cash} Space Template, over the
 * SHIPPED synthetic samples ({@code tools/gen-order-to-cash-data.mjs}). The Space is created through {@code POST /spaces} (every
 * seed gate), both feeds land through the template's own Pipelines, the template's own Jobs run, and every shipped control must
 * report exactly the ground truth ({@code tools/gen-order-to-cash-data.ground-truth.json}) the tutorials tell the reader to find:
 * the Expectation breaches, the Reconciliation's Breaks, the open-items status of each planted defect, and the Incident keys the
 * Alert Rule raises (by the production grouped Measure probe). The Case half (Incidents grouped into collections Cases, the
 * write-off approval workflow) is {@code OrderToCashCaseTest} in {@code inspecto-case-management}.
 */
class OrderToCashGoldenTest {

    static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "order-to-cash").toAbsolutePath().normalize();
    static final Path TRUTH = Path.of("..", "..", "tools", "gen-order-to-cash-data.ground-truth.json").toAbsolutePath().normalize();
    private static final Map<String, String> AS_OF = Map.of("as_of", "2026-07-31");

    private static Path tmp;
    private static Path space;
    private static JsonNode truth;
    private static String reconMessage;

    @BeforeAll
    static void createIngestAndRun() throws Exception {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1",
                Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES))));
        SpaceConfigRoot.clear();
        truth = new ObjectMapper().readTree(TRUTH.toFile());
        tmp = Files.createTempDirectory("o2c-golden");
        Path root = tmp.resolve("root");
        copyTree(TEMPLATE, root.resolve("_templates").resolve("order-to-cash"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"o2c\",\"template\":\"order-to-cash\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
        } finally {
            api.close();
            spaces.close();
        }
        space = root.resolve("o2c");

        ingest("orders", truth.at("/counts/order_rows").asLong());
        ingest("payments", truth.at("/counts/payment_rows").asLong());
        runJob("o2c_open_items");
        reconMessage = reconDay(truth.at("/reconciliation/day").asText());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        SpaceConfigRoot.clear();
        System.clearProperty("assist.write.root");
        if (tmp != null) try (Stream<Path> w = Files.walk(tmp)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }

    @Test
    void eachExpectationReportsExactlyItsPlantedViolations() throws Exception {
        Map<String, Long> got = new TreeMap<>();
        try (Stream<Path> s = Files.list(config().resolve("registry").resolve("expectations"))) {
            for (Path p : s.sorted().toList()) {
                Expectation exp = Expectation.fromMap(ToonHelper.load(p.toString()));
                got.put(exp.name(), ExpectationEvaluator.evaluate(exp, data()).violations());
            }
        }
        Map<String, Long> want = new TreeMap<>();
        truth.get("expectations").fields().forEachRemaining(e -> want.put(e.getKey(), e.getValue().asLong()));
        assertEquals(want, got, "one negative payment, two orphan payments, one duplicate order id; the orders amounts are clean");
        // hand-written, not derived: the tutorial's numbers
        assertEquals(Map.of("orders_amount_non_negative", 0L, "orders_id_unique", 1L, "payments_amount_non_negative", 1L,
                "payments_order_known", 2L), got);
    }

    @Test
    void theReconciliationRecordsExactlyThePlantedBreaks() throws Exception {
        Map<String, Set<String>> byType = new TreeMap<>();
        for (ReconBreaks.Break b : new ReconStateStore(config()).read("o2c_orders_vs_payments").breaks()) {
            assertEquals(truth.at("/reconciliation/day").asText(), b.day(), b.toString());
            byType.computeIfAbsent(b.type(), k -> new TreeSet<>()).add(orderId(b.key()));
        }
        Map<String, Set<String>> want = new TreeMap<>();
        for (String type : List.of("missing_right", "missing_left", "value_break", "cardinality_break")) {
            Set<String> ids = new TreeSet<>();
            truth.at("/reconciliation/" + type).forEach(n -> ids.add(n.asText()));
            want.put(type, ids);
        }
        assertEquals(want, byType, "unpaid orders, orphan payments, short / negative / duplicated amounts, and the duplicate's cardinality");
        assertTrue(reconMessage.contains(": 8 break(s) (2 missing-left, 2 missing-right, 4 value-break)"), reconMessage);
        // the look-alikes reconcile: instalments that sum exactly, a payment on its due date, the late payment paid in full
        for (String clean : List.of("O-1030", "O-1075", "O-1099", "O-1012"))
            for (Set<String> ids : byType.values()) assertFalse(ids.contains(clean), clean + " must not break");
    }

    @Test
    void theOpenItemsNameEveryPlantedDefectAndNothingElse() throws Exception {
        Map<String, Set<String>> byStatus = new TreeMap<>();
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        for (Map<String, Object> r : read("o2c_open_items")) {
            rows.put((String) r.get("order_id"), r);
            if (!"paid".equals(r.get("status"))) byStatus.computeIfAbsent((String) r.get("status"), k -> new TreeSet<>()).add((String) r.get("order_id"));
        }
        assertEquals(truth.at("/counts/open_items").asInt(), rows.size(), "one row per order id seen in either feed");
        Map<String, Set<String>> want = new TreeMap<>();
        truth.get("open_items_status").fields().forEachRemaining(e -> {
            Set<String> ids = new TreeSet<>();
            e.getValue().forEach(n -> ids.add(n.asText()));
            want.put(e.getKey(), ids);
        });
        assertEquals(want, byStatus, "every other order is paid in full and on time");

        JsonNode late = truth.at("/planted/late_payment/0");
        Map<String, Object> o1012 = rows.get(late.get("order_id").asText());
        assertEquals(late.get("days_late").asLong(), ((Number) o1012.get("days_late")).longValue());
        assertEquals(late.get("due_date").asText(), String.valueOf(o1012.get("due_date")));
        assertEquals(0L, ((Number) rows.get("O-1099").get("days_late")).longValue(), "paid on its due date is on time");
        assertEquals(2L, ((Number) rows.get("O-1050").get("order_copies")).longValue());
        assertEquals(3L, ((Number) rows.get("O-1075").get("payment_count")).longValue());
        assertEquals(0, new BigDecimal("480.00").compareTo((BigDecimal) rows.get("O-1017").get("unmatched_value")), "240 ordered, -240 paid");
        assertEquals(0, new BigDecimal("4.50").compareTo((BigDecimal) rows.get("O-1064").get("unmatched_value")));
    }

    @Test
    void theShippedAlertRuleRaisesOneIncidentKeyPerOrderAtOrOverTheThreshold() throws Exception {
        AlertRule rule = null;
        for (ComponentRegistry.Component c : new ComponentStore(config().resolve("registry")).list("alert-rule"))
            if ("o2c_unmatched_value".equals(c.content().get("name"))) rule = AlertRule.fromMap(c.content());
        assertNotNull(rule);
        assertEquals("CRITICAL", rule.severity().toString(), "a CRITICAL Alert Rule opens an Incident per key");
        DatasetMeasureProbe.Breaches b = new DatasetMeasureProbe(OrderToCashGoldenTest::config, OrderToCashGoldenTest::data)
                .breaches(rule.dataset(), rule.measure(), rule.by(), rule.comparator(), rule.threshold(), rule.stormCap())
                .orElseThrow(() -> new AssertionError("the probe failed"));
        Set<String> got = new TreeSet<>();
        for (DatasetMeasureProbe.Breach br : b.keys()) got.add(br.key().get("customer_id") + "#" + br.key().get("order_id"));
        Set<String> want = new TreeSet<>();
        truth.get("incidents").forEach(n -> want.add(n.get("customer_id").asText() + "#" + n.get("order_id").asText()));
        assertEquals(want, got, "the short payment of 4.50 and the orphan payment of 55.00 stay Breaks and raise nothing");
        assertEquals(6, got.size());
    }

    @Test
    void theDashboardWidgetsReadColumnsTheOpenItemsJobWrites() throws Exception {
        Set<String> columns = read("o2c_open_items").get(0).keySet();
        for (ComponentRegistry.Component w : new ComponentStore(config().resolve("registry")).list("widget")) {
            assertEquals("o2c_open_items", w.content().get("datasetId"), w.name());
            String body = String.valueOf(w.content().get("controls"));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:field=)([a-z_]+)").matcher(body);
            int seen = 0;
            while (m.find()) { seen++; assertTrue(columns.contains(m.group(1)), w.name() + " reads " + m.group(1) + ": " + columns); }
            assertTrue(seen > 0, w.name() + ": " + body);
        }
    }

    // ── helpers ──

    static Path config() { return space.resolve("config"); }

    static Path data() { return space.resolve("data"); }

    /** Drop the template's sample files of one feed into its poll directory, run its Collector, and count the landed rows. */
    private static void ingest(String feed, long rows) throws Exception {
        DuckDbUtil.loadDriver();
        PipelineConfig cfg = PipelineConfig.load(config().resolve(feed).resolve(feed + "_pipeline.toon").toString());
        Path poll = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
        assertTrue(poll.startsWith(space), feed + " polls outside its Space: " + poll);
        Files.createDirectories(poll);
        try (Stream<Path> s = Files.list(TEMPLATE.resolve("data").resolve("samples").resolve(feed))) {
            for (Path p : s.toList()) Files.copy(p, poll.resolve(p.getFileName().toString()));
        }
        CollectorProcessor.run(cfg);
        Path db = Path.of(cfg.dirs().database()).toAbsolutePath().normalize();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + db.toString().replace('\\', '/')
                     + "/**/*.parquet', union_by_name = true)")) {
            rs.next();
            assertEquals(rows, rs.getLong(1), feed + ": the Pipeline must ingest every row");
        }
    }

    private static void runJob(String name) throws Exception {
        List<JobConfig> configs = new ArrayList<>();
        configs.add(JobConfig.load(config().resolve("jobs").resolve(name + "_job.toon").toString()));
        try (Scheduler sch = new Scheduler();
             JobService js = new JobService(configs, new ConsignmentEventBus(), sch, null, tmp.resolve("audit").toString(), null, null, data().toString())) {
            js.start();
            assertTrue(js.triggerRun(name, null, AS_OF).isPresent(), name);
            long deadline = System.nanoTime() + 120_000_000_000L;
            JobRun r = null;
            while (System.nanoTime() < deadline) {
                Optional<JobRun> o = js.lastRunOf(name);
                if (o.isPresent()) { r = o.get(); break; }
                Thread.sleep(25);
            }
            assertNotNull(r, name + ": no run within 120s");
            assertEquals("SUCCESS", r.status(), name + ": " + r.message());
        }
    }

    private static String reconDay(String day) throws Exception {
        System.setProperty("assist.write.root", config().toString());
        SpaceConfigRoot.clear();
        JobConfig cfg = JobConfig.load(config().resolve("jobs").resolve("o2c_orders_vs_payments_job.toon").toString());
        assertEquals("recon.run", cfg.type());
        Map<String, String> params = new HashMap<>(cfg.params());
        params.put("day", day);
        JobResult r = new ReconRunJob(cfg, data().toString(), () -> null).run(new Ctx(params));
        assertEquals("SUCCESS", r.status(), r.message());
        return r.message();
    }

    /** A Break key carries the key column; the order id is its value. */
    private static String orderId(Object key) {
        String k = String.valueOf(key);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("O-\\d{4}").matcher(k);
        assertTrue(m.find(), "a Break key names its order: " + k);
        return m.group();
    }

    private static List<Map<String, Object>> read(String dataset) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        String glob = data().resolve(dataset).toString().replace('\\', '/') + "/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM read_parquet('" + glob + "', union_by_name = true) ORDER BY order_id")) {
            var md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    /** A JobContext that swallows Signals: the Break state store is what the tests read. */
    private record Ctx(Map<String, String> params) implements JobContext {
        @Override public String runId() { return "golden"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return params; }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) {}
                @Override public void warn(String message, Object... kv) {}
                @Override public void error(String message, Throwable t, Object... kv) {}
            };
        }
        @Override public SignalEmitter signals() { return (type, sev, payload) -> { }; }
        @Override public ArtifactRecorder artifacts() { return null; }
    }
}
