package com.gamma.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-utility} Space Template (Basic tier) RUN end to end from a verbatim copy: the eight Pipelines land the shipped
 * extracts with nothing quarantined, then the {@code utility_links} and {@code transformer_loss} {@code sql.template} Jobs fold
 * them. Over the REAL link Dataset it pins the generator's ground truth ({@code tools/gen-la-utility-data.ground-truth.json}):
 * every story's entities form one connected group, and no benign look-alike has a link to any of them. The facts behind
 * each story in the files themselves (drops, arrears, flat loads, token prices) are pinned by
 * {@code tools/gen-la-utility-data.test.mjs}.
 */
class UtilityLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-utility");
    private static final List<String> FEEDS = List.of("premise", "customer_account", "meter_read", "transformer_read", "field_visit",
            "service_order", "prepaid_vend", "payment");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-utility");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        for (String feed : FEEDS) {
            PipelineConfig pc = PipelineConfig.load(space.resolve("config/" + feed + "/" + feed + "_pipeline.toon").toString());
            CollectorProcessor.run(pc);
            assertFalse(hasFiles(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
            assertFalse(hasFiles(Path.of(pc.dirs().errors())), feed + ": nothing in the corpus is an error");
        }
        data = space.resolve("data");
        List<JobConfig> jobs = List.of(JobConfig.load(space.resolve("config/jobs/utility_links_job.toon").toString()),
                JobConfig.load(space.resolve("config/jobs/transformer_loss_job.toon").toString()),
                JobConfig.load(space.resolve("config/jobs/utility_bank_links_job.toon").toString()));
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        // the built-in Job Types that declare these grants must validate at registration; this test sends nothing
        for (Map.Entry<String, String> e : Map.of("mail", "com.gamma.notify.MailAccess",
                "notifications", "com.gamma.notify.NotificationAccess", "incidents", "com.gamma.objects.IncidentAccess",
                "objects", "com.gamma.objects.ObjectAccess", "datasets", "com.gamma.query.DatasetAccess",
                "schema", "com.gamma.pipeline.SchemaAccess", "link-index", "com.gamma.linkindex.LinkIndexAccess",
                "alerts", "com.gamma.alert.AlertAccess").entrySet())
            unused(platform, e.getKey(), e.getValue());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString(), null, platform)) {
            js.eventLog(com.gamma.event.EventLog.current());
            js.start();
            for (JobConfig j : jobs) {
                assertTrue(js.triggerRun(j.name(), null, Map.of()).isPresent(), j.name());
                JobRun r = awaitRun(js, j.name());
                assertNotNull(r, j.name() + " never ran");
                assertEquals("SUCCESS", r.status(), j.name() + " failed: " + r.message());
            }
        }
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-utility-data.ground-truth.json").toFile());
        links = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT src, dst, kind FROM " + parquet("utility_links")))
            links.add(new String[]{(String) r.get("src"), (String) r.get("dst"), (String) r.get("kind")});
    }

    @Test
    void theTemplateIsBasicTierOnly() throws Exception {
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/registry/alert-rules"))
            assertFalse(Files.exists(TEMPLATE.resolve(advanced)), advanced + " belongs to the Advanced tier (plan step 5)");
    }

    @Test
    void everyLandedRowIsInItsDatasetAndTheStagedMonthIsNotLanded() throws Exception {
        for (String feed : FEEDS) {
            long csv = 0;
            try (Stream<Path> w = Files.list(TEMPLATE.resolve("data/inbox").resolve(feed))) {
                for (Path f : w.toList()) csv += Files.readAllLines(f).size() - 1;
            }
            assertEquals(csv, count("SELECT count(*) FROM " + parquet(feed + "/database", "/**/*.parquet")), feed);
        }
        assertEquals(0, count("SELECT count(*) FROM " + parquet("meter_read/database", "/**/*.parquet") + " WHERE read_date > DATE '2026-09-30'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSixKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "kwh", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + parquet("utility_links") + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + parquet("utility_links") + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("account_at", "order_closed_by", "paid_from", "supplied_by", "vended_by", "visited_by"), new ArrayList<>(perKind.keySet()));
        assertEquals(1000L, perKind.get("supplied_by"), "one transformer per premise");
        assertEquals(0, count("SELECT count(*) FROM (SELECT src, dst, kind FROM " + parquet("utility_links") + " GROUP BY ALL HAVING count(*) > 1)"), "one row per pair");
        assertEquals(0, count("SELECT count(*) FROM " + parquet("utility_links") + " WHERE ts IS NULL OR event_date IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("utility_links") + " WHERE kind = 'paid_from' AND (dst IN ('', 'UNKNOWN', '00000000') OR dst NOT LIKE 'BA-%')"),
                "a blank or placeholder bank account is never a link");
    }

    @Test
    void everyStorysEntitiesFormOneConnectedGroup() {
        truth.get("stories").fields().forEachRemaining(e -> {
            Set<String> members = ids(e.getValue().get("entities"));
            Map<String, String> parent = new HashMap<>();
            for (String m : members) parent.put(m, m);
            for (String[] l : links)
                if (members.contains(l[0]) && members.contains(l[1])) parent.put(root(parent, l[0]), root(parent, l[1]));
            Set<String> groups = new TreeSet<>();
            for (String m : members) groups.add(root(parent, m));
            assertEquals(1, groups.size(), e.getKey() + ": " + members.size() + " entities fall into " + groups.size() + " groups");
            String hub = e.getValue().get("hub").asText();
            assertTrue(links.stream().anyMatch(l -> l[0].equals(hub) || l[1].equals(hub)), e.getKey() + ": the hub " + hub + " is linked");
        });
    }

    @Test
    void noLookAlikeJoinsItsStory() {
        truth.get("stories").fields().forEachRemaining(e -> {
            Set<String> members = ids(e.getValue().get("entities"));
            Set<String> lookalikes = ids(e.getValue().get("lookalikes"));
            assertFalse(lookalikes.isEmpty(), e.getKey());
            for (String a : lookalikes) {
                assertFalse(members.contains(a), e.getKey() + ": " + a + " is both");
                assertTrue(links.stream().anyMatch(l -> l[0].equals(a) || l[1].equals(a)), e.getKey() + ": the look-alike " + a + " is in the data");
            }
            for (String[] l : links)
                assertFalse(lookalikes.contains(l[0]) && members.contains(l[1]) || lookalikes.contains(l[1]) && members.contains(l[0]),
                        e.getKey() + ": the look-alike link " + String.join(" ", l) + " joins the story");
        });
    }

    @Test
    void lossClimbsOverTheThresholdOnlyOnTheTwoTamperedTransformers() throws Exception {
        String t = parquet("transformer_loss");
        assertEquals(12, count("SELECT count(DISTINCT transformer_id) FROM " + t), "12 metered transformers");
        for (String tx : List.of("TX-007", "TX-019")) {
            assertTrue(count("SELECT count(*) FROM " + t + " WHERE transformer_id = '" + tx + "' AND month = DATE '2025-10-01' AND loss_pct < 9") == 1, tx + " starts normal");
            assertTrue(count("SELECT count(*) FROM " + t + " WHERE transformer_id = '" + tx + "' AND month = DATE '2026-09-01' AND loss_pct >= 15") == 1, tx + " ends over 15 %");
        }
        assertEquals(0, count("SELECT count(*) FROM " + t + " WHERE transformer_id NOT IN ('TX-007', 'TX-019') AND loss_band <> 'loss normal'"),
                "every other metered transformer stays normal every month");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everySavedViewFitsOneCanvasAndShowsItsStories() throws Exception {
        // a saved view draws its whole (filtered) Dataset; past the canvas's projection_node_cap (1 000 nodes) or the explorer's
        // default read (traversal.rows_default, 4 000 rows) it is cut short and a story may not be on screen
        Map<String, List<String>> stories = Map.of("meter_tampering_contractor", List.of("meter_tampering", "illegal_reconnection"),
                "account_churn_by_bank_account", List.of("account_churn", "commercial_load"), "prepaid_token_fraud_vendor", List.of("prepaid_token_fraud"));
        Path dir = TEMPLATE.resolve("config/registry/link-analysis-views");
        try (Stream<Path> w = Files.list(dir)) {
            assertEquals(stories.keySet(), w.map(p -> p.getFileName().toString().replace(".toon", "")).collect(java.util.stream.Collectors.toSet()));
        }
        for (Map.Entry<String, List<String>> v : stories.entrySet()) {
            Map<String, Object> query = (Map<String, Object>) ToonHelper.load(dir.resolve(v.getKey() + ".toon").toString()).get("query");
            String datasetId = (String) ((Map<String, Object>) query.get("projection")).get("datasetId");
            String sink = (String) ToonHelper.load(TEMPLATE.resolve("config/registry/datasets/" + datasetId + ".toon").toString()).get("physicalRef");
            Set<String> kinds = new TreeSet<>();
            Map<String, Object> filter = (Map<String, Object>) query.get("filter");
            if (filter != null)
                for (Map<String, Object> c : (List<Map<String, Object>>) filter.get("items")) {
                    assertEquals("kind", c.get("field"), v.getKey() + ": views filter by link kind only");
                    kinds.add(String.valueOf(c.get("value")));
                }
            Set<String> nodes = new TreeSet<>();
            long rows = 0;
            for (Map<String, Object> r : rows("SELECT src, dst, kind FROM " + parquet(sink))) {
                if (!kinds.isEmpty() && !kinds.contains((String) r.get("kind"))) continue;
                rows++;
                nodes.add((String) r.get("src"));
                nodes.add((String) r.get("dst"));
            }
            assertTrue(rows < 4_000, v.getKey() + " fits one read: " + rows);
            assertTrue(nodes.size() < 1_000, v.getKey() + " fits the canvas: " + nodes.size());
            for (String s : v.getValue())
                for (String e : ids(truth.get("stories").get(s).get("entities")))
                    if (!(e.startsWith("TX-") && v.getKey().equals("meter_tampering_contractor")))   // transformers are in the loss Dataset
                        assertTrue(nodes.contains(e), v.getKey() + " shows " + s + " entity " + e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void unused(PlatformServiceRegistry platform, String id, String iface) {
        Class type;
        try {
            type = Class.forName(iface);
        } catch (ClassNotFoundException e) {
            return;
        }
        platform.register(id, type, java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) return m.invoke(id, a);
                    throw new UnsupportedOperationException(id + " is not wired in this test");
                }));
    }

    private static Set<String> ids(JsonNode arr) {
        Set<String> out = new TreeSet<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String root(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) x = parent.get(x);
        return x;
    }

    private static long count(String sql) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static JobRun awaitRun(JobService js, String job) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobRun r = js.lastRunOf(job).orElse(null);
            if (r != null && !"SKIPPED".equals(r.status()) && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(50);
        }
        return null;
    }

    private static String parquet(String sink) {
        return parquet(sink, "/*.parquet");
    }

    private static String parquet(String sink, String glob) {
        return "read_parquet('" + data.resolve(sink).toString().replace('\\', '/') + glob + "')";
    }

    private static List<Map<String, Object>> rows(String sql) throws Exception {
        DuckDbUtil.loadDriver();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> r = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) r.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                out.add(r);
            }
        }
        assertFalse(out.isEmpty(), "no rows: " + sql);
        return out;
    }

    private static boolean hasFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> w = Files.walk(dir)) {
            return w.anyMatch(p -> Files.isRegularFile(p) && !p.getFileName().toString().startsWith("."));
        }
    }
}
